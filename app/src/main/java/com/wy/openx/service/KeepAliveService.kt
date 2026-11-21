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
import com.wy.openx.MainActivity
import com.wy.openx.R

/**
 * 后台保活前台服务（偏好设置「后台保活」开关）。
 *
 * 为什么用前台服务：Android 从 8.0 起对后台进程限制很严，普通后台进程随时可能被回收；
 * 前台服务（常驻通知）是系统认可的唯一可靠保活手段。代价是通知栏会常驻一条通知，
 * 这是平台强制要求，无法规避。
 *
 * 服务本身**不做任何事**，只负责「存在」——它的价值就是让进程带上前台优先级。
 * 真正的任务执行仍在 ViewModel / 容器里。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "openx_keep_alive"
        private const val NOTIFICATION_ID = 1001

        /** 按开关状态启动/停止保活服务。幂等，可反复调用。 */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, KeepAliveService::class.java)
            runCatching {
                if (enabled) {
                    // Android 8.0+ 必须 startForegroundService，服务须在 5 秒内 startForeground
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.stopService(intent)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    /**
     * 被系统杀掉后若带 STICKY 重启，重进 onStartCommand 也要保证处于前台态，
     * 否则会抛 "Context.startForegroundService() did not then call Service.startForeground()"。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 渠道重要性设为最低：保活通知不该响铃/震动打扰用户
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台运行",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持 OpenX 在后台运行"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenX 正在后台运行")
            .setContentText("已开启后台保活，可在「偏好设置」中关闭")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
