package com.wy.openx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaStyleNotificationHelper
import com.wy.openx.MainActivity
import com.wy.openx.R
import com.wy.openx.audio.AudioPlaybackHub

/**
 * 音频播放的前台服务 + 媒体通知。
 *
 * ## 为什么需要它
 * 播放器本身在 [AudioPlaybackHub] 里，与 Service 生命周期无关；这个 Service 只负责两件事：
 *  1. **让进程在前台优先级**——否则用户切走 App、系统回收后台进程，音乐就断了；
 *  2. **通知栏控制**（播放/暂停/停止），并借 MediaSession 拿耳机键与蓝牙控制。
 *
 * targetSdk 28，无需声明 `foregroundServiceType`（见 AndroidManifest 里 KeepAliveService 的注释）。
 *
 * ## 不播就撤
 * 停止播放时调用 [stop] 撤掉前台通知——播放器不该在白占通知栏。
 */
class AudioPlaybackService : Service() {

    companion object {
        private const val TAG = "AudioPlaybackService"
        private const val CHANNEL_ID = "openx_audio_playback"

        const val ACTION_TOGGLE = "com.wy.openx.audio.action.TOGGLE"
        const val ACTION_STOP = "com.wy.openx.audio.action.STOP"
        const val ACTION_PREV = "com.wy.openx.audio.action.PREV"
        const val ACTION_NEXT = "com.wy.openx.audio.action.NEXT"

        /** 通知 ID（外部取消通知时要用，所以 public）。 */
        const val NOTIFICATION_ID = 1002

        /** 拉起播放服务（幂等）。 */
        fun start(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                com.wy.openx.container.FileLogger.e(TAG, "启动播放服务失败: ${it.message}")
            }
        }

        /** 撤掉播放服务与通知。 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, AudioPlaybackService::class.java))
            }
        }
    }

    /** Hub 状态监听器；onDestroy 里要摘掉，避免服务销毁后还被回调。 */
    private var hubListener: ((AudioPlaybackHub.State) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val listener: (AudioPlaybackHub.State) -> Unit = { state -> updateNotification(state) }
        hubListener = listener
        AudioPlaybackHub.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> AudioPlaybackHub.toggle()
            ACTION_PREV -> AudioPlaybackHub.previous()
            ACTION_NEXT -> AudioPlaybackHub.next()
            ACTION_STOP -> {
                AudioPlaybackHub.stop()
                // stop() 内部已 stopService，这里直接返回
                return START_NOT_STICKY
            }
        }
        // Android 8+ 要求 startForegroundService 后 5 秒内 startForeground
        startForeground(NOTIFICATION_ID, buildNotification(AudioPlaybackHub.state()))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hubListener?.let { AudioPlaybackHub.removeListener(it) }
        hubListener = null
        // 必须显式 stopForeground(true)：Android 在服务销毁时会自动
        // stopForeground(false)，那条路径只是把通知标记成
        // FLAG_ONGOING_EVENT_CANCELLED(0x8) 而**不把它从通知栏移除**——
        // 用户会看到一个点不动的空播放条。传 true 才连带删掉通知。
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        // MIUI 上 stopForeground(REMOVE) 之后仍会留一条「已取消」的通知记录
        // （dumpsys 里 flags=0x8 = FLAG_ONGOING_EVENT_CANCELLED），通知栏里还能看到
        // 停止前的内容。这里再显式 cancel 一次把它彻底清掉。
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "音频播放",
            // DEFAULT 而不是 LOW：LOW 在部分 ROM（如 OriginOS）会把媒体控件降级/收起
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "插件播放音乐/电台时的控制条"
            setShowBadge(false)
            // 是媒体控制，不该响铃震动
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(state: AudioPlaybackHub.State) {
        // 没有内容时**不刷新**：否则 stop() 之后空状态会把通知内容刷成
        // "OpenX 音频 / 无播放内容"，留下一条用户看得见但点不动的空条。
        // 通知的正确归宿是随服务一起被 stopForeground(REMOVE) 清掉。
        if (state.nowPlaying == null && state.error == null) {
            return
        }
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(state))
        }.onFailure {
            com.wy.openx.container.FileLogger.e(TAG, "刷新通知失败: ${it.message}")
        }
    }

    private fun fmtTime(ms: Long): String {
        val total = (ms / 1000).toInt()
        val m = total / 60
        val sec = total % 60
        return "$m:" + (if (sec < 10) "0" else "") + sec
    }

    private fun buildNotification(state: AudioPlaybackHub.State): Notification {
        val np = state.nowPlaying
        val title = when {
            state.error != null -> "播放失败"
            np != null -> np.title
            else -> "OpenX 音频"
        }
        val text = when {
            state.error != null -> state.error
            state.buffering -> "缓冲中…"
            np != null && np.artist.isNotBlank() -> np.artist
            np != null && np.isLive -> "网络电台"
            np != null -> "正在播放"
            else -> "正在准备…"
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun svcIntent(id: Int, action: String): PendingIntent = PendingIntent.getService(
            this,
            id,
            Intent(this, AudioPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prevAction = svcIntent(3, ACTION_PREV)
        val nextAction = svcIntent(4, ACTION_NEXT)
        val toggleAction = svcIntent(1, ACTION_TOGGLE)
        val stopAction = svcIntent(2, ACTION_STOP)

        // 大图标：媒体卡片没图会很空。插件没给封面时用 App 图标占位。
        val largeIcon = runCatching {
            android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        }.getOrNull()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(state.playing || state.buffering)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 媒体通知必须带 transport，否则部分 ROM（OriginOS 等）不认它是可控制的媒体，
            // 只当普通通知渲染 —— 表现就是"没有暂停键、没有时长、没有上下曲"。
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                android.R.drawable.ic_media_previous,
                "上一曲",
                prevAction
            )
            .addAction(
                if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.playing) "暂停" else "播放",
                toggleAction
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "下一曲",
                nextAction
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                stopAction
            )

        // 点播显示真实进度与时长；直播流时长无意义，只标"直播中"，不画进度
        if (np != null && !np.isLive && state.durationMs > 0) {
            val span = 1000
            val pos = ((state.positionMs.coerceIn(0L, state.durationMs) * span) / state.durationMs).toInt()
            builder.setProgress(span, pos, false)
            builder.setSubText(
                "OpenX 音频 · " + fmtTime(state.positionMs) + " / " + fmtTime(state.durationMs)
            )
        } else if (np != null) {
            builder.setSubText("OpenX 音频 · 直播中")
        }
        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        // 有 MediaSession 时用媒体样式：锁屏/耳机键能拿到曲目与进度
        val session = AudioPlaybackHub.currentSession()
        if (session != null) {
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    // 折叠态显示「上一曲 / 播放暂停 / 下一曲」，三个都露出来
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return builder.build()
    }
}
