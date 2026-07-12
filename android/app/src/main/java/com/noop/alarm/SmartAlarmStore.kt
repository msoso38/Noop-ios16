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
 * Times are stored as minutes since local midnight. The "target" is the EARLIEST the user wants to
 * be woken; [windowMinutes] is how much later the hard deadline sits (e.g. target 06:30 + 30 min
 * window = guaranteed wake by 07:00, with the smart logic allowed to fire any time from 06:30).
 *
 * Single-user, on-device. Mirrors the macOS UserDefaults pattern; nothing is ever sent off-device.
 */
class SmartAlarmStore(private val prefs: SharedPreferences) {

    /** Master enable. Default OFF (every automation in NOOP is opt-in). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Earliest acceptable wake time, minutes since midnight. Default 06:30. */
    var targetMinutes: Int
        get() = prefs.getInt(KEY_TARGET, DEFAULT_TARGET).coerceIn(0, MINUTES_PER_DAY - 1)
        set(v) = prefs.edit().putInt(KEY_TARGET, v.coerceIn(0, MINUTES_PER_DAY - 1)).apply()

    /** Window length in minutes — how long after [targetMinutes] the guaranteed hard deadline sits.
     *  Clamped 5..60; default 30. A 0 window would collapse smart + fallback into one exact alarm,
     *  so we keep a floor that leaves the watcher room to find a lighter phase. */
    var windowMinutes: Int
        get() = prefs.getInt(KEY_WINDOW, DEFAULT_WINDOW).coerceIn(WINDOW_MIN, WINDOW_MAX)
        set(v) = prefs.edit().putInt(KEY_WINDOW, v.coerceIn(WINDOW_MIN, WINDOW_MAX)).apply()

    /** The wall-clock epoch (ms) of the currently-scheduled HARD deadline, or 0 if none. Persisted so
     *  the boot receiver can re-arm the exact alarm after a restart without recomputing intent. */
    var scheduledDeadlineMs: Long
        get() = prefs.getLong(KEY_DEADLINE_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_DEADLINE_MS, v).apply()

    /** The earliest epoch (ms) the smart logic may fire (the window's opening edge), for the watcher. */
    var scheduledWindowStartMs: Long
        get() = prefs.getLong(KEY_WINDOW_START_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_WINDOW_START_MS, v).apply()

    /** Actual next fire time. It can move earlier than the hard deadline but never later. */
    var scheduledFireAtMs: Long
        get() = prefs.getLong(KEY_FIRE_AT_MS, scheduledDeadlineMs)
        set(v) = prefs.edit().putLong(KEY_FIRE_AT_MS, v).apply()

    /** After the wake alarm fires, NOOP watches live HR briefly and can buzz again if HR settles back down. */
    var postWakeWatchUntilMs: Long
        get() = prefs.getLong(KEY_POST_WAKE_UNTIL_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_POST_WAKE_UNTIL_MS, v).apply()

    /** Highest HR seen during the post-wake watch; a later drop suggests the user may have fallen asleep. */
    var postWakeHighBpm: Int
        get() = prefs.getInt(KEY_POST_WAKE_HIGH_BPM, 0)
        set(v) = prefs.edit().putInt(KEY_POST_WAKE_HIGH_BPM, v.coerceAtLeast(0)).apply()

    /** Last follow-up buzz time, used to avoid buzzing every HR sample. */
    var postWakeLastBuzzMs: Long
        get() = prefs.getLong(KEY_POST_WAKE_LAST_BUZZ_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_POST_WAKE_LAST_BUZZ_MS, v).apply()

    /**
     * Opt-in "turn-back" alarm: after wake, if HR rises then falls (likely dozing again), cue again.
     * Default OFF — explicit consent; never surprise-buzz.
     */
    var turnBackEnabled: Boolean
        get() = prefs.getBoolean(KEY_TURN_BACK_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_TURN_BACK_ENABLED, v).apply()

    /** How long after wake to watch HR (minutes). Clamped 10..90; default 45. */
    var turnBackWatchMinutes: Int
        get() = prefs.getInt(KEY_TURN_BACK_WATCH_MIN, DEFAULT_TURN_BACK_WATCH).coerceIn(10, 90)
        set(v) = prefs.edit().putInt(KEY_TURN_BACK_WATCH_MIN, v.coerceIn(10, 90)).apply()

    /** Drop from post-wake high HR (bpm) that counts as settling. Clamped 5..20; default 8. */
    var turnBackDropBpm: Int
        get() = prefs.getInt(KEY_TURN_BACK_DROP_BPM, DEFAULT_TURN_BACK_DROP).coerceIn(5, 20)
        set(v) = prefs.edit().putInt(KEY_TURN_BACK_DROP_BPM, v.coerceIn(5, 20)).apply()

    /** Also raise a phone alarm-style notification on turn-back (not only strap buzz). Default ON when enabled. */
    var turnBackPhoneCue: Boolean
        get() = prefs.getBoolean(KEY_TURN_BACK_PHONE, true)
        set(v) = prefs.edit().putBoolean(KEY_TURN_BACK_PHONE, v).apply()

    /**
     * Inside the wake window, advance early when sleep need looks met or overnight Charge is already
     * in the green band ("recovery set full"). Default OFF — opt-in; hard deadline still stands.
     */
    var wakeWhenRested: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WHEN_RESTED, false)
        set(v) = prefs.edit().putBoolean(KEY_WAKE_WHEN_RESTED, v).apply()

    /** Charge threshold for rested wake (0–100). Default 67 (green band). */
    var restedChargeThreshold: Int
        get() = prefs.getInt(KEY_RESTED_CHARGE, DEFAULT_RESTED_CHARGE).coerceIn(50, 90)
        set(v) = prefs.edit().putInt(KEY_RESTED_CHARGE, v.coerceIn(50, 90)).apply()

    /** Fraction of learned sleep need (percent 50–100). Default 90. */
    var restedSleepNeedPercent: Int
        get() = prefs.getInt(KEY_RESTED_SLEEP_PCT, DEFAULT_RESTED_SLEEP_PCT).coerceIn(50, 100)
        set(v) = prefs.edit().putInt(KEY_RESTED_SLEEP_PCT, v.coerceIn(50, 100)).apply()

    /** Learned / last-used sleep need minutes for rested evaluation. Default 450 (7h30). */
    var restedSleepNeedMinutes: Int
        get() = prefs.getInt(KEY_RESTED_SLEEP_NEED, DEFAULT_SLEEP_NEED).coerceIn(300, 600)
        set(v) = prefs.edit().putInt(KEY_RESTED_SLEEP_NEED, v.coerceIn(300, 600)).apply()

    /** Last overnight Charge (0–100) the UI/engine pushed for rested evaluation; 0 = unknown. */
    var restedChargeHint: Int
        get() = prefs.getInt(KEY_RESTED_CHARGE_HINT, 0).coerceIn(0, 100)
        set(v) = prefs.edit().putInt(KEY_RESTED_CHARGE_HINT, v.coerceIn(0, 100)).apply()

    /** Classic custom phone alarms (JSON). Cap enforced on write. */
    var customAlarms: List<CustomAlarm>
        get() = CustomAlarmCodec.decode(prefs.getString(KEY_CUSTOM_ALARMS, null))
        set(v) = prefs.edit().putString(KEY_CUSTOM_ALARMS, CustomAlarmCodec.encode(v.take(MAX_CUSTOM_ALARMS))).apply()

    companion object {
        private const val PREFS = "noop_smart_alarm"
        private const val KEY_ENABLED = "alarm.enabled"
        private const val KEY_TARGET = "alarm.targetMinutes"
        private const val KEY_WINDOW = "alarm.windowMinutes"
        private const val KEY_DEADLINE_MS = "alarm.scheduledDeadlineMs"
        private const val KEY_WINDOW_START_MS = "alarm.scheduledWindowStartMs"
        private const val KEY_FIRE_AT_MS = "alarm.scheduledFireAtMs"
        private const val KEY_POST_WAKE_UNTIL_MS = "alarm.postWakeWatchUntilMs"
        private const val KEY_POST_WAKE_HIGH_BPM = "alarm.postWakeHighBpm"
        private const val KEY_POST_WAKE_LAST_BUZZ_MS = "alarm.postWakeLastBuzzMs"
        private const val KEY_TURN_BACK_ENABLED = "alarm.turnBackEnabled"
        private const val KEY_TURN_BACK_WATCH_MIN = "alarm.turnBackWatchMinutes"
        private const val KEY_TURN_BACK_DROP_BPM = "alarm.turnBackDropBpm"
        private const val KEY_TURN_BACK_PHONE = "alarm.turnBackPhoneCue"
        private const val KEY_WAKE_WHEN_RESTED = "alarm.wakeWhenRested"
        private const val KEY_RESTED_CHARGE = "alarm.restedChargeThreshold"
        private const val KEY_RESTED_SLEEP_PCT = "alarm.restedSleepNeedPercent"
        private const val KEY_RESTED_SLEEP_NEED = "alarm.restedSleepNeedMinutes"
        private const val KEY_RESTED_CHARGE_HINT = "alarm.restedChargeHint"
        private const val KEY_CUSTOM_ALARMS = "alarm.customAlarmsJson"

        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_TARGET = 6 * 60 + 30   // 06:30
        const val DEFAULT_WINDOW = 30
        const val WINDOW_MIN = 5
        const val WINDOW_MAX = 60
        const val DEFAULT_TURN_BACK_WATCH = 45
        const val DEFAULT_TURN_BACK_DROP = 8
        const val DEFAULT_RESTED_CHARGE = 67
        const val DEFAULT_RESTED_SLEEP_PCT = 90
        const val DEFAULT_SLEEP_NEED = 450
        const val MAX_CUSTOM_ALARMS = 5

        fun from(context: Context): SmartAlarmStore =
            SmartAlarmStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
