package com.spshin.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spshin.phone.model.NightAlert

object NotificationHelper {
    private const val parentAlertsChannelId = "parent_alerts"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            parentAlertsChannelId,
            "부모 경고 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "자녀의 오후 10시 이후 사용을 부모 기기에 알립니다."
        }
        manager.createNotificationChannel(channel)
    }

    fun showParentAlert(context: Context, alert: NightAlert) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            alert.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, parentAlertsChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${alert.childName} 야간 사용 감지")
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(alert.id.hashCode(), notification)
    }
}
