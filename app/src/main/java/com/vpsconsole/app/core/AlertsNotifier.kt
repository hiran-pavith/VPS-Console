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
        // Separate channels so vibration/sound settings actually work on Android 8+
        // (per-notification settings are overridden by the channel).
        data class ChannelSpec(val id: String, val vibration: Boolean, val sound: Boolean)
        val channels = listOf(
            ChannelSpec(CHANNEL_ID, true, true),
            ChannelSpec(CHANNEL_VIBRATE_ONLY, true, false),
            ChannelSpec(CHANNEL_SOUND_ONLY, false, true),
            ChannelSpec(CHANNEL_SILENT, false, false)
        )
        channels.forEach { spec ->
            val id = spec.id
            val vib = spec.vibration
            val sound = spec.sound
            nm?.createNotificationChannel(
                NotificationChannel(id, "$name (${label(vib, sound)})", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = desc
                    enableVibration(vib)
                    vibrationPattern = if (vib) longArrayOf(0, 300, 150, 300) else null
                    if (!sound) setSound(null, null)
                }
            )
        }
    }

    private fun label(vibration: Boolean, sound: Boolean) = when {
        vibration && sound -> "default"
        vibration -> "vibrate only"
        sound -> "sound only"
        else -> "silent"
    }

    private fun channel(vibration: Boolean, sound: Boolean) = when {
        vibration && sound -> CHANNEL_ID
        vibration -> CHANNEL_VIBRATE_ONLY
        sound -> CHANNEL_SOUND_ONLY
        else -> CHANNEL_SILENT
    }

    fun alert(id: Long, title: String, body: String, vibration: Boolean, sound: Boolean = true) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel(vibration, sound))
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
        const val CHANNEL_VIBRATE_ONLY = "vps_alerts_vibrate"
        const val CHANNEL_SOUND_ONLY = "vps_alerts_sound"
        const val CHANNEL_SILENT = "vps_alerts_silent"
    }
}
