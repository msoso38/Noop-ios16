package com.noop.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Exact OS alarms for [CustomAlarm] rows — classic clock times, independent of the smart wake window.
 * Request codes are derived from alarm id so each slot is stable across re-arms.
 */
object CustomAlarmScheduler {

    const val ACTION_FIRE = "com.noop.alarm.action.FIRE_CUSTOM_ALARM"
    const val EXTRA_ALARM_ID = "com.noop.alarm.extra.customId"
    const val EXTRA_LABEL = "com.noop.alarm.extra.customLabel"

    fun rescheduleAll(context: Context, store: SmartAlarmStore) {
        val alarms = store.customAlarms
        // Cancel known ids plus a small stale range from prior edits.
        for (a in alarms) cancel(context, a.id)
        if (!SmartAlarmScheduler.canScheduleExact(context)) return
        for (a in alarms) {
            if (!a.enabled) continue
            val next = nextOccurrenceMs(a.minutes, a.weekdays) ?: continue
            scheduleExact(context, a, next)
        }
    }

    fun cancel(context: Context, alarmId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, CustomAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode(alarmId), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    fun cancelAll(context: Context, store: SmartAlarmStore) {
        for (a in store.customAlarms) cancel(context, a.id)
    }

    private fun scheduleExact(context: Context, alarm: CustomAlarm, fireAtMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, CustomAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, alarm.id)
            putExtra(EXTRA_LABEL, alarm.label)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode(alarm.id), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAtMs, pi), pi)
    }

    internal fun requestCode(alarmId: String): Int =
        0x4C000000 or (alarmId.hashCode() and 0x00FFFFFF)

    /**
     * Next strictly-future occurrence honouring weekday set (empty = every day).
     * Pure for tests.
     */
    fun nextOccurrenceMs(
        minutes: Int,
        weekdays: Set<Int>,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? {
        val valid = weekdays.filter { it in 1..7 }.toSet()
        if (weekdays.isNotEmpty() && valid.isEmpty()) return null
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        for (dayOffset in 0..7) {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, dayOffset)
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (weekdays.isNotEmpty() && !valid.contains(dow)) continue
            c.set(Calendar.HOUR_OF_DAY, minutes / 60)
            c.set(Calendar.MINUTE, minutes % 60)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis > nowMs) return c.timeInMillis
        }
        return null
    }
}
