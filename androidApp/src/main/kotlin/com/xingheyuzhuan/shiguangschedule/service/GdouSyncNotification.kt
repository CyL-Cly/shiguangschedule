package com.xingheyuzhuan.shiguangschedule.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.xingheyuzhuan.shiguangschedule.MainActivity
import com.xingheyuzhuan.shiguangschedule.R
import org.koin.java.KoinJavaComponent.getKoin

object GdouSyncNotification {
    const val CHANNEL_ID = "gdou_sync"
    const val EXTRA_OPEN_GDOU = "open_gdou_yangjiang"
    private const val NOTIFICATION_ID = 71001

    fun show(title: String, message: String) {
        val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "广海课表同步",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_GDOU, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
