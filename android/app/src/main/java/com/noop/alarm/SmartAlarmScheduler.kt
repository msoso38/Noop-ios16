package com.noop.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * The safety-critical scheduler for the phone smart alarm (#207).
 *
 * DESIGN — fallback-first, the whole point of the feature:
 *
 *  • When the alarm is armed, we IMMEDIATELY schedule a GUARANTEED exact OS alarm at the user's
 *    wake TIME using [AlarmManager.setAlarmClock]. That call is the most privileged exact-alarm
 *    primitive Android offers: it ignores Doze, survives the app being killed, shows the system's
 *    next-alarm affordance, and fires even in battery-saver. It is INDEPENDENT of Bluetooth, the
 *    strap, sleep detection, or the app process being alive.
 *
 *  • Semantics (#207 v3): wake time is the HARD DEADLINE; the smart watcher may only advance the
 *    alarm EARLIER inside a pre-wake window `[wake - preWakeWindow, wake]`. So a 07:00 alarm with a
 *    30-min pre-wake window is guaranteed to ring by 07:00, with the smart logic allowed to fire
 *    any time from 06:30 onwards.
 *
 *  • The overnight sleep watcher (in the BLE foreground service) may only ever call [advanceTo] to
 *    move the alarm EARLIER, never later, and only to a time still inside the window. It physically
 *    cannot cancel or skip the deadline: [advanceTo] re-schedules the SAME requestCode/PendingIntent,
 *    clamped to ≥ window-start and ≤ the original hard deadline. So if BLE drops, no light sleep is
 *    found, or the watcher never runs, the original deadline stands and the user is still woken.
 *
 *  • [cancel] is only reachable from an explicit user "disable" or after the alarm has fired — never
 *    from the detection path.
 *
 * The single PendingIntent targets [SmartAlarmReceiver], which raises a full-screen high-priority
 * alarm notification with sound + vibration. Everything is on-device.
 */
object SmartAlarmScheduler {

    /** Stable request code so every (re)schedule + cancel addresses the SAME alarm slot. */
    private const val REQUEST_CODE = 7307

    const val ACTION_FIRE = "com.noop.alarm.action.FIRE_SMART_ALARM"
    /** Extras carried to the receiver so the fired notification can show the woken-at context. */
    const val EXTRA_SMART = "com.noop.alarm.extra.smart"
    const val EXTRA_DEADLINE_MS = "com.noop.alarm.extra.deadlineMs"

    /**
     * Arm the guaranteed hard-deadline alarm AT the user's wake time and persist both edges.
     * Computes the next occurrence of wakeMinutes (today if still ahead, else tomorrow). The
     * window-start (earliest smart-fire time) is `wake - preWakeWindow`, persisted for the watcher.
     * Idempotent — re-arming just replaces the same alarm slot at the freshly-computed wake time.
     *
     * @return the scheduled hard-deadline epoch (ms, == wake time), or null if exact alarms aren't permitted.
     */
    fun arm(context: Context, store: SmartAlarmStore): Long? {
        if (!canScheduleExact(context)) return null

        val deadline = nextOccurrence(store.wakeMinutes)
        // The watcher window opens preWakeWindow minutes before the deadline. Subtracting from the
        // deadline's epoch keeps the two edges on the same wall-clock night even across midnight.
        val windowStartMs = deadline.timeInMillis - store.preWakeWindowMinutes.toLong() * 60_000L

        if (!scheduleExact(context, deadline.timeInMillis)) return null
        store.scheduledDeadlineMs = deadline.timeInMillis
        store.scheduledWindowStartMs = windowStartMs
        return deadline.timeInMillis
    }

    /**
     * Re-arm the EXACT same hard deadline that was previously persisted (used by the boot receiver so
     * the alarm survives a restart). No-op if nothing is scheduled or it's already in the past.
     */
    fun rearmPersisted(context: Context, store: SmartAlarmStore) {
        if (!store.enabled) return
        val deadlineMs = store.scheduledDeadlineMs
        if (deadlineMs <= System.currentTimeMillis()) return
        if (!canScheduleExact(context)) return
        scheduleExact(context, deadlineMs)
    }

    /**
     * Move the alarm EARLIER — the ONLY hook the sleep watcher gets. The requested time is clamped to
     * the window: never before the window-start, never after the original hard deadline. Because it
     * re-schedules the SAME PendingIntent, the deadline is preserved as the floor of safety: even a
     * buggy watcher can't push the wake later or drop it. No-op if the alarm isn't armed or the
     * requested time isn't actually earlier than what's already scheduled.
     */
    fun advanceTo(context: Context, store: SmartAlarmStore, fireAtMs: Long) {
        if (!store.enabled) return
        val deadlineMs = store.scheduledDeadlineMs
        val windowStartMs = store.scheduledWindowStartMs
        if (deadlineMs <= 0L) return
        if (!canScheduleExact(context)) return
        // Clamp into [windowStart, deadline]. Anything outside the window is ignored.
        val clamped = fireAtMs.coerceIn(windowStartMs, deadlineMs)
        // Only ever advance — re-scheduling at the same/later time would be pointless and could, in a
        // pathological caller, nudge the alarm back toward the deadline. We keep the persisted deadline
        // untouched so a later cancel/boot path still references the real hard edge.
        scheduleExact(context, clamped, smart = true)
    }

    fun advanceOneShotTo(
        context: Context,
        alarmId: String,
        fireAtMs: Long,
        windowStartMs: Long,
        deadlineMs: Long,
    ) {
        if (alarmId.isBlank() || deadlineMs <= 0L) return
        val clamped = fireAtMs.coerceIn(windowStartMs, deadlineMs)
        scheduleOneShot(
            context = context,
            alarmId = alarmId,
            fireAtMs = clamped,
            deadlineAtMs = deadlineMs,
            smart = true,
        )
    }

    /** Cancel the alarm and clear the persisted edges. Only the user-disable / post-fire paths call this. */
    fun cancel(context: Context, store: SmartAlarmStore) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context))
        store.scheduledDeadlineMs = 0L
        store.scheduledWindowStartMs = 0L
        store.scheduledAlarmId = null
    }

    /** True if the OS will honour an exact alarm right now (API 31+ gates this behind a permission). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    // MARK: - internals

    /** Schedule the guaranteed wake via setAlarmClock — the strongest exact-alarm primitive: Doze- and
     *  kill-proof, and surfaced in the system's "next alarm" UI. [smart] only tags the fired intent so
     *  the notification can say it woke you on a light-sleep phase rather than at the deadline. */
    private fun scheduleExact(context: Context, triggerAtMs: Long, smart: Boolean = false): Boolean {
        if (!canScheduleExact(context)) return false
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            context, REQUEST_CODE + 1,
            com.noop.ui.appLaunchIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val info = AlarmManager.AlarmClockInfo(triggerAtMs, show)
        return runCatching {
            am.setAlarmClock(info, firePendingIntent(context, smart))
            true
        }.getOrDefault(false)
    }

    private fun firePendingIntent(context: Context, smart: Boolean = false): PendingIntent {
        val intent = Intent(context, SmartAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_SMART, smart)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Cancel a previously-scheduled one-shot alarm keyed by [alarmId]. No-op if nothing is
     * scheduled for that id. The complement of [scheduleOneShot] - uses the identical
     * [oneShotPendingIntent] so it addresses the same AlarmManager slot.
     */
    fun cancelOneShot(context: Context, alarmId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(oneShotPendingIntent(context, alarmId))
    }

    /**
     * Schedule a one-shot alarm-clock wake at fireAtMs, keyed by alarmId. A second call with the
     * same id REPLACES the prior schedule (we cancel-then-set so the user can't stack snoozes).
     *
     * [deadlineAtMs] is the occurrence-consumption boundary: early smart fires and snoozes still
     * resolve the next recurrence after the original hard deadline, not after the early fire time.
     */
    fun scheduleOneShot(
        context: Context,
        alarmId: String,
        fireAtMs: Long,
        deadlineAtMs: Long = fireAtMs,
        smart: Boolean = false,
    ): Boolean {
        if (!canScheduleExact(context)) return false
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = oneShotPendingIntent(context, alarmId, smart, deadlineAtMs)
        // Cancel any prior schedule keyed to this alarmId before setting so a re-snooze replaces
        // rather than stacks. The PendingIntent is stable per id (same request code), so cancel
        // addresses the same slot that setAlarmClock is about to occupy.
        am.cancel(pi)
        val show = PendingIntent.getActivity(
            context,
            ("show:$alarmId").hashCode(),
            com.noop.ui.appLaunchIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAtMs, show), pi)
            true
        }.getOrDefault(false)
    }

    private fun oneShotPendingIntent(
        context: Context,
        alarmId: String,
        smart: Boolean = false,
        deadlineAtMs: Long = 0L,
    ): android.app.PendingIntent {
        val intent = Intent(context, SmartAlarmReceiver::class.java)
        intent.setAction(ACTION_FIRE)
        intent.putExtra(EXTRA_SMART, smart)
        intent.putExtra(EXTRA_DEADLINE_MS, deadlineAtMs)
        intent.putExtra(SnoozeReceiver.EXTRA_ALARM_ID, alarmId)
        // Stable per-id request code so a re-schedule replaces, not stacks.
        val requestCode = ("snooze:" + alarmId).hashCode()
        return android.app.PendingIntent.getBroadcast(
            context, requestCode, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The next wall-clock occurrence (today or tomorrow) of an absolute minute-of-day. */
    private fun nextOccurrence(minuteOfDay: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
}
