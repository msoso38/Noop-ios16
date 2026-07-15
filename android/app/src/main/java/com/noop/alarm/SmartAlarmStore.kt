package com.noop.alarm

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted state for the PHONE-based smart alarm (#207).
 *
 * This is deliberately SEPARATE from the strap's firmware buzz-alarm (NoopPrefs.smartAlarm*, which
 * arms the WHOOP itself). This one is a guaranteed phone alarm: a hard OS alarm is scheduled at the
 * LATEST edge of the wake window via AlarmManager, and the overnight sleep watcher may only move it
 * EARLIER inside the window when it detects light sleep — it can never cancel the fallback. So the
 * user is woken even if Bluetooth drops, no light sleep is found, or the app is killed.
 *
 * Times are stored as minutes since local midnight. [wakeMinutes] is the user's wake TIME (the
 * latest moment the smart watcher may fire). [preWakeWindowMinutes] is how long BEFORE
 * [wakeMinutes] the smart logic may advance the alarm forward (e.g. wake 07:00 with a 30-min
 * pre-wake window means the smart watcher may fire any time from 06:30 onwards).
 *
 * Single-user, on-device. Mirrors the macOS UserDefaults pattern; nothing is ever sent off-device.
 */
class SmartAlarmStore(private val prefs: SharedPreferences) {

    /** Master enable. Default OFF (every automation in NOOP is opt-in). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Wake time, minutes since midnight. Default 07:00. */
    var wakeMinutes: Int
        get() = prefs.getInt(KEY_WAKE, DEFAULT_WAKE).coerceIn(0, MINUTES_PER_DAY - 1)
        set(v) = prefs.edit().putInt(KEY_WAKE, v.coerceIn(0, MINUTES_PER_DAY - 1)).apply()

    /** Pre-wake window length in minutes. Clamped 0..60; default 30. A 0 window means smart wake
     *  is effectively off (the watcher has nothing to advance to). 0 stays legal so the phone
     *  scheduler can persist a "no-smart" entry by writing 0 here. */
    var preWakeWindowMinutes: Int
        get() = prefs.getInt(KEY_PRE_WAKE, DEFAULT_PRE_WAKE).coerceIn(WINDOW_MIN, WINDOW_MAX)
        set(v) = prefs.edit().putInt(KEY_PRE_WAKE, v.coerceIn(WINDOW_MIN, WINDOW_MAX)).apply()

    /** The wall-clock epoch (ms) of the currently-scheduled HARD deadline, or 0 if none. Persisted so
     *  the boot receiver can re-arm the exact alarm after a restart without recomputing intent. */
    var scheduledDeadlineMs: Long
        get() = prefs.getLong(KEY_DEADLINE_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_DEADLINE_MS, v).apply()

    /** The earliest epoch (ms) the smart logic may fire (the window's opening edge), for the watcher. */
    var scheduledWindowStartMs: Long
        get() = prefs.getLong(KEY_WINDOW_START_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_WINDOW_START_MS, v).apply()

    /** Unified alarm id mirrored for the HR watcher so smart-wake advances the per-id one-shot. */
    var scheduledAlarmId: String?
        get() = prefs.getString(KEY_ALARM_ID, null)
        set(v) = prefs.edit().apply {
            if (v.isNullOrBlank()) remove(KEY_ALARM_ID) else putString(KEY_ALARM_ID, v)
        }.apply()

    companion object {
        private const val PREFS = "noop_smart_alarm"
        private const val KEY_ENABLED = "alarm.enabled"
        // KEY_WAKE replaces the legacy "alarm.targetMinutes" (same wall-clock meaning under the new
        // model: wake TIME = previous "target"). KEY_PRE_WAKE replaces "alarm.windowMinutes"
        // (semantics flipped from after-target to before-target). Keys differ so a process that
        // re-reads old prefs gets defaults rather than silently inheriting wrong semantics.
        private const val KEY_WAKE = "alarm.wakeMinutes"
        private const val KEY_PRE_WAKE = "alarm.preWakeWindowMinutes"
        private const val KEY_DEADLINE_MS = "alarm.scheduledDeadlineMs"
        private const val KEY_WINDOW_START_MS = "alarm.scheduledWindowStartMs"
        private const val KEY_ALARM_ID = "alarm.scheduledAlarmId"

        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_WAKE = 7 * 60          // 07:00
        const val DEFAULT_PRE_WAKE = 30
        // 0 permitted (no smart-wake path keeps a row here); the smart-wake UI enforces a 5-min floor.
        const val WINDOW_MIN = 0
        const val WINDOW_MAX = 60

        fun from(context: Context): SmartAlarmStore =
            SmartAlarmStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
