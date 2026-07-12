package com.noop.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.noop.R
import com.noop.ui.appLaunchIntent

/** Fires a classic custom phone alarm scheduled by [CustomAlarmScheduler]. */
class CustomAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CustomAlarmScheduler.ACTION_FIRE) return
        val id = intent.getStringExtra(CustomAlarmScheduler.EXTRA_ALARM_ID).orEmpty()
        val label = intent.getStringExtra(CustomAlarmScheduler.EXTRA_LABEL)?.ifBlank { "Alarm" } ?: "Alarm"
        val store = SmartAlarmStore.from(context)
        // Re-arm remaining custom alarms (including this one's next day) while enabled.
        runCatching { CustomAlarmScheduler.rescheduleAll(context, store) }
        runCatching { (context.applicationContext as? com.noop.NoopApplication)?.ble?.buzz(3) }
        ensureChannel(context)
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(notifId(id), buildNotification(context, label))
        }
    }

    private fun buildNotification(context: Context, label: String): Notification {
        val fullScreen = android.app.PendingIntent.getActivity(
            context, 0, appLaunchIntent(context),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(label)
            .setContentText("Custom alarm")
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Custom alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Exact-time custom phone alarms"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun notifId(alarmId: String): Int = 7400 + (alarmId.hashCode() and 0xFF)

    companion object {
        private const val CHANNEL_ID = "noop_custom_alarm"
    }
}
