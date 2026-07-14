package com.noop.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Phone-side snooze for unified Smart Alarm. Wired from the notification posted by
 * SmartAlarmReceiver. Fixed to [SNOOZE_MINUTES] for this release, not user-configurable.
 *
 * iOS has no equivalent: the only iOS source is .strap, which is single-fire firmware.
 */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(SmartAlarmReceiver.NOTIF_ID)
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID)?.takeIf { it.isNotBlank() } ?: return
        when (intent.action) {
            ACTION_DISMISS -> Unit  // notification was the only thing to cancel
            ACTION_SNOOZE -> {
                val fireAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
                val deadline = intent.getLongExtra(SmartAlarmScheduler.EXTRA_DEADLINE_MS, 0L)
                    .takeIf { it > 0L } ?: fireAt
                SmartAlarmScheduler.scheduleOneShot(
                    context,
                    alarmId = alarmId,
                    fireAtMs = fireAt,
                    deadlineAtMs = deadline,
                )
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.noop.alarm.action.SNOOZE_SMART_ALARM"
        const val ACTION_DISMISS = "com.noop.alarm.action.DISMISS_SMART_ALARM"
        const val EXTRA_ALARM_ID = "com.noop.alarm.extra.alarmId"
        const val SNOOZE_MINUTES = 9
    }
}
