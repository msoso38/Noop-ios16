package com.noop.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.noop.R
import com.noop.ui.appLaunchIntent

/**
 * Fires when the guaranteed wake alarm goes off (scheduled by [SmartAlarmScheduler] via AlarmManager).
 *
 * Raises a FULL-SCREEN, high-priority, alarm-category notification with the device alarm sound and a
 * vibration pattern — the standard way a sideloaded app delivers a dependable wake without owning a
 * foreground Activity. The full-screen intent re-opens NOOP; on a locked screen the system promotes
 * the notification to a heads-up / full-screen alarm. This path is reached whether the alarm fired at
 * the smart (light-sleep) time or the hard deadline, so the user is woken either way.
 *
 * Registered in the manifest (exported=false) so it survives the app being killed. Unified phone
 * alarms carry their alarm id, so after one fires we resolve the next occurrence from
 * [UnifiedAlarmStore] instead of re-entering the retired legacy scheduler.
 */
class SmartAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != SmartAlarmScheduler.ACTION_FIRE) return
        val smart = intent.getBooleanExtra(SmartAlarmScheduler.EXTRA_SMART, false)
        val alarmId = intent.getStringExtra(SnoozeReceiver.EXTRA_ALARM_ID) ?: ""
        val deadline = intent.getLongExtra(SmartAlarmScheduler.EXTRA_DEADLINE_MS, 0L)

        runCatching {
            UnifiedAlarmMigration.migrateIfNeeded(context)
            if (alarmId.isNotBlank()) {
                val store = UnifiedAlarmStore.from(context)
                store.disableIfOneShot(alarmId)
                val resolvedNow = maxOf(System.currentTimeMillis(), deadline)
                UnifiedPhoneScheduler.recompute(context, store, resolvedNow)
            } else {
                val store = SmartAlarmStore.from(context)
                store.scheduledDeadlineMs = 0L
                store.scheduledWindowStartMs = 0L
                store.scheduledAlarmId = null
                if (!UnifiedAlarmStore.from(context).migrationComplete && store.enabled) {
                    SmartAlarmScheduler.arm(context, store)
                }
            }
        }

        ensureChannel(context)
        // Defensive: a notify() throw (OEM quirk / revoked POST_NOTIFICATIONS) must not crash the
        // broadcast. The system alarm sound below is the fallback-of-the-fallback audible cue.
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildNotification(context, smart, alarmId, deadline))
        }
    }

    private fun buildNotification(
        context: Context,
        smart: Boolean,
        alarmId: String = "",
        deadlineMs: Long = 0L,
    ): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, 0, appLaunchIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = "Good morning"
        val body = if (smart) {
            "You're in a lighter sleep phase - time to wake up."
        } else {
            "Your wake window has ended - time to get up."
        }
        val snoozeIntent = android.content.Intent(context, SnoozeReceiver::class.java)
            .setAction(SnoozeReceiver.ACTION_SNOOZE)
            .putExtra(SnoozeReceiver.EXTRA_ALARM_ID, alarmId)
            .putExtra(SmartAlarmScheduler.EXTRA_DEADLINE_MS, deadlineMs)
        val snoozePi = PendingIntent.getBroadcast(
            context, ("snooze-action:$alarmId").hashCode(), snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissIntent = android.content.Intent(context, SnoozeReceiver::class.java)
            .setAction(SnoozeReceiver.ACTION_DISMISS)
            .putExtra(SnoozeReceiver.EXTRA_ALARM_ID, alarmId)
            .putExtra(SmartAlarmScheduler.EXTRA_DEADLINE_MS, deadlineMs)
        val dismissPi = PendingIntent.getBroadcast(
            context, ("dismiss-action:$alarmId").hashCode(), dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)   // promote to a full-screen alarm on a locked phone
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(0, "Snooze", snoozePi)
            .addAction(0, "Dismiss", dismissPi)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart alarm",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "The phone wake alarm NOOP fires inside your chosen wake window."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
                setBypassDnd(true)   // a wake alarm should sound through Do Not Disturb
                if (alarmSound != null) {
                    setSound(
                        alarmSound,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }
            }
            mgr.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "noop_smart_alarm"
        internal const val NOTIF_ID = 4307
    }
}
