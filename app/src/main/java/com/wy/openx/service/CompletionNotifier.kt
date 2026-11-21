package com.wy.openx.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wy.openx.MainActivity

/**
 * 「AI 完成」系统通知。
 *
 * 为什么需要：原先完成提醒只有响铃/震动（[com.wy.openx.ui.viewmodel.MainViewModel] 收尾处），
 * App 在后台时用户很可能根本没听到，回来一看不知道是哪个会话跑完了。这里在**后台**补一条
 * 可点击的通知，点开直接回到 App。
 *
 * 只做「完成」通知，不做「需要你回答/授权」——后者要看用户是否在别的会话里，
 * 贸然弹通知容易打断。
 */
object CompletionNotifier {

    private const val CHANNEL_ID = "openx_agent_complete"
    private const val NOTIFICATION_ID = 1001

    /** 后台完成时调用；[silent] 对应设置里的「完成提示音 = 无」。 */
    fun notifyComplete(context: Context, title: String, body: String, silent: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "任务完成提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "AI 在后台完成一轮任务时提醒"
                    enableVibration(!silent)
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(silent)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** 回到前台时清掉，避免用户已经看到结果还挂着一条。 */
    fun cancel(context: Context) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }
    }
}
