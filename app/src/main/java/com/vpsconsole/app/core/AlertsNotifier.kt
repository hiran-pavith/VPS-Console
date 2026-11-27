package com.vpsconsole.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vpsconsole.app.MainActivity
import com.vpsconsole.app.R

class AlertsNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        val name = context.getString(R.string.notification_channel_alerts)
        val desc = context.getString(R.string.notification_channel_desc)
        // Two channels so the Vibration setting actually works on Android 8+
        // (per-notification vibration is overridden by the channel).
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = desc
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
            }
        )
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_SILENT, "$name (silent)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = desc
                enableVibration(false)
                vibrationPattern = null
            }
        )
    }

    fun alert(id: Long, title: String, body: String, vibration: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, if (vibration) CHANNEL_ID else CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(id.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        }
    }

    companion object {
        const val CHANNEL_ID = "vps_alerts"
        const val CHANNEL_SILENT = "vps_alerts_silent"
    }
}
