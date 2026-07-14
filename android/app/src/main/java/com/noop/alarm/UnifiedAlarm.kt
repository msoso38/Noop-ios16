package com.noop.alarm

import java.util.UUID

/**
 * Tri-state alarm source.
 *  - STRAP: only the strap firmware buzzes (no phone notification).
 *  - STRAP_AND_PHONE: the strap buzzes and the phone alarm also rings as a backup, delayed by
 *    [UnifiedAlarm.phoneBackupDelayMinutes] AFTER the wake time.
 *  - PHONE: phone alarm only (Android, exact alarm via SmartAlarmScheduler).
 *
 * On iOS/macOS the source is forced to STRAP at the screen level - the picker is hidden.
 */
enum class AlarmSource { STRAP, STRAP_AND_PHONE, PHONE }

/**
 * One alarm row in the unified Smart Alarm list (#207 v2).
 *
 * Single source of truth for one wake. The resolver consumes a list of these plus `now` and decides
 * which gets armed on the strap (single-slot firmware constraint) and which gets a phone exact
 * alarm. Display order is list order (user drag); the resolver is independent of list position.
 *
 * SEMANTICS (per the user's mental model):
 *   - [wakeMinutes] is the user's wake TIME (the moment the strap firmware buzzes / the phone-only
 *     alarm rings / the latest moment smart wake may fire).
 *   - [smartWake] enables the heart-rate light-sleep watcher.
 *   - [weekdays] is the recurrence set. Empty means one-shot: schedule the next occurrence only,
 *     then disable this row after it fires. Non-empty means repeat on those Calendar weekdays.
 *   - [preWakeWindowMinutes] is how long BEFORE [wakeMinutes] the smart logic may fire early
 *     (advancing the wake forward inside the window). Only meaningful when [smartWake] is on.
 *   - [phoneBackupDelayMinutes] only applies when [source] = STRAP_AND_PHONE: it's how much LATER
 *     than [wakeMinutes] the backup phone alarm rings (a safety net if the strap didn't wake you).
 *
 * Persisted as JSON by [UnifiedAlarmStore]. iOS counterpart (Strand/Data/UnifiedAlarm.swift) is
 * a follow-up; Android keeps unknown JSON keys ignored so older payloads still decode.
 */
data class UnifiedAlarm(
    val id: String,
    val enabled: Boolean = true,
    val wakeMinutes: Int,
    val weekdays: Set<Int> = emptySet(),
    val source: AlarmSource = AlarmSource.STRAP,
    val smartWake: Boolean = false,
    val preWakeWindowMinutes: Int = DEFAULT_PRE_WAKE,
    val phoneBackupDelayMinutes: Int = DEFAULT_PHONE_BACKUP,
) {
    /** Clamp every numeric range to its spec'd interval, filter weekdays to 1..7. */
    fun sanitized(): UnifiedAlarm = copy(
        wakeMinutes = wakeMinutes.coerceIn(0, MINUTES_PER_DAY - 1),
        weekdays = weekdays.filter { it in 1..7 }.toSet(),
        preWakeWindowMinutes = preWakeWindowMinutes.coerceIn(PRE_WAKE_MIN, PRE_WAKE_MAX),
        phoneBackupDelayMinutes = phoneBackupDelayMinutes.coerceIn(PHONE_BACKUP_MIN, PHONE_BACKUP_MAX),
    )

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_WAKE = 7 * 60        // 07:00
        const val DEFAULT_PRE_WAKE = 30
        const val PRE_WAKE_MIN = 5
        const val PRE_WAKE_MAX = 60
        const val DEFAULT_PHONE_BACKUP = 5
        const val PHONE_BACKUP_MIN = 1
        const val PHONE_BACKUP_MAX = 15

        fun newId(): String = UUID.randomUUID().toString()
    }
}
