package com.noop.alarm

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit as JdkChronoUnit

/**
 * Resolved strap-arm slot. The strap firmware can only hold ONE armed alarm at a time, so the
 * resolver picks the next-firing STRAP / STRAP_AND_PHONE alarm.
 *
 *  - [wakeEpochMs] is the moment the firmware buzzes (also the latest moment smart wake may fire).
 *  - [windowStartEpochMs] is the earliest moment the smart watcher may advance the wake to;
 *    equal to [wakeEpochMs] when smart wake is off (no window, no early fire).
 */
data class NextFire(
    val alarmId: String,
    val wakeEpochMs: Long,
    val windowStartEpochMs: Long,
    val smartWake: Boolean,
    val preWakeWindowMinutes: Int,
)

/**
 * Resolved phone-alarm fire. The phone scheduler arms one OS alarm per row.
 *
 *  - PHONE source: [fireAtEpochMs] is the user's wake time. With smart wake the watcher may
 *    advance it to anywhere inside [windowStartEpochMs, fireAtEpochMs].
 *  - STRAP_AND_PHONE source: [fireAtEpochMs] is `wakeTime + phoneBackupDelayMinutes` (the backup
 *    rings AFTER the strap buzz, only if the strap did not wake the user). Smart wake here applies
 *    to the strap, not to this phone backup, so windowStart == fireAt and the watcher does not
 *    touch this entry.
 */
data class PhoneFire(
    val alarmId: String,
    val fireAtEpochMs: Long,
    val windowStartEpochMs: Long,
    val smartWake: Boolean,
    val isStrapPhoneBackup: Boolean,
)

data class Schedule(
    val nextStrapArm: NextFire?,
    val phoneAlarms: List<PhoneFire>,
)

/**
 * Pure scheduling logic. No I/O, no AlarmManager, no BLE. The coordinator wraps this output and
 * diffs it against the current armed state.
 *
 * weekdays uses Calendar's 1=Sun..7=Sat numbering. Empty weekdays means a one-shot alarm: compute the
 * next wall-clock occurrence only; the fire path disables it after it rings. java.time.DayOfWeek is
 * 1=Mon..7=Sun, so we convert via `(dayOfWeek.value % 7) + 1`.
 */
object UnifiedAlarmResolver {

    fun nextFireAtEpochMs(
        alarm: UnifiedAlarm,
        nowEpochMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (!alarm.enabled) return null
        val wake = alarm.wakeMinutes.coerceIn(0, UnifiedAlarm.MINUTES_PER_DAY - 1)
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowEpochMs), zone)
        val candidateTime = LocalTime.of(wake / 60, wake % 60)

        // Up to 8 days ahead is enough to find any matching weekday plus a same-day case.
        for (offset in 0..7) {
            val candidateDate: LocalDate = now.toLocalDate().plusDays(offset.toLong())
            val calendarWeekday = (candidateDate.dayOfWeek.value % 7) + 1   // Mon=1..Sun=7 -> Sun=1..Sat=7
            val weekdayMatches = alarm.weekdays.isEmpty() || calendarWeekday in alarm.weekdays
            if (!weekdayMatches) continue

            // Build a ZonedDateTime; DST gaps shift forward to the next valid instant automatically
            // when constructed via LocalDateTime.atZone - java.time picks the later offset.
            val zdt: ZonedDateTime = LocalDateTime.of(candidateDate, candidateTime).atZone(zone)
            val epoch = zdt.toInstant().toEpochMilli()
            if (epoch > nowEpochMs) return epoch
        }
        return null
    }

    fun resolveSchedule(
        alarms: List<UnifiedAlarm>,
        nowEpochMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Schedule {
        data class Sized(val alarm: UnifiedAlarm, val wakeEpochMs: Long)
        val sized: List<Sized> = alarms.mapNotNull { a ->
            val fire = nextFireAtEpochMs(a, nowEpochMs, zone) ?: return@mapNotNull null
            Sized(a, fire)
        }

        // Strap queue: enabled STRAP or STRAP_AND_PHONE alarms, sorted by wakeTime, head wins.
        val strapHead = sized
            .filter { it.alarm.source == AlarmSource.STRAP || it.alarm.source == AlarmSource.STRAP_AND_PHONE }
            .minByOrNull { it.wakeEpochMs }
            ?.let { s ->
                val windowStart = if (s.alarm.smartWake) {
                    s.wakeEpochMs - s.alarm.preWakeWindowMinutes.toLong() * 60_000L
                } else {
                    s.wakeEpochMs
                }
                NextFire(
                    alarmId = s.alarm.id,
                    wakeEpochMs = s.wakeEpochMs,
                    windowStartEpochMs = windowStart,
                    smartWake = s.alarm.smartWake,
                    preWakeWindowMinutes = s.alarm.preWakeWindowMinutes,
                )
            }

        // Phone fires: every enabled PHONE or STRAP_AND_PHONE alarm.
        //   PHONE                  -> fires AT wake time; smart-wake watcher may advance earlier.
        //   STRAP_AND_PHONE        -> fires AT wake + phoneBackupDelay (backup); never advanced.
        val phone = sized
            .filter { it.alarm.source == AlarmSource.PHONE || it.alarm.source == AlarmSource.STRAP_AND_PHONE }
            .map { s ->
                if (s.alarm.source == AlarmSource.PHONE) {
                    val windowStart = if (s.alarm.smartWake) {
                        s.wakeEpochMs - s.alarm.preWakeWindowMinutes.toLong() * 60_000L
                    } else {
                        s.wakeEpochMs
                    }
                    PhoneFire(
                        alarmId = s.alarm.id,
                        fireAtEpochMs = s.wakeEpochMs,
                        windowStartEpochMs = windowStart,
                        smartWake = s.alarm.smartWake,
                        isStrapPhoneBackup = false,
                    )
                } else {
                    val backupAt = s.wakeEpochMs + s.alarm.phoneBackupDelayMinutes.toLong() * 60_000L
                    PhoneFire(
                        alarmId = s.alarm.id,
                        fireAtEpochMs = backupAt,
                        windowStartEpochMs = backupAt,
                        smartWake = false,
                        isStrapPhoneBackup = true,
                    )
                }
            }

        return Schedule(nextStrapArm = strapHead, phoneAlarms = phone)
    }
}

/**
 * Pure label derived from when the alarm fires next:
 *  disabled -> "Off"
 *  same calendar day -> "Today"
 *  next calendar day -> "Tomorrow"
 *  2..6 days ahead -> full weekday name (e.g. "Monday")
 *  7+ days ahead -> short date (e.g. "Tue, Jun 30")
 *
 * "Today", "Tomorrow", and "Off" are returned in English so they can be wrapped at the screen
 * layer with stringResource() for localization. Weekday + date formatting use the supplied locale.
 */
fun displayLabel(
    alarm: UnifiedAlarm,
    nowEpochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String {
    if (!alarm.enabled) return "Off"
    val fire = UnifiedAlarmResolver.nextFireAtEpochMs(alarm, nowEpochMs, zone) ?: return "Off"
    val nowDate = java.time.Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    val fireDate = java.time.Instant.ofEpochMilli(fire).atZone(zone).toLocalDate()
    val days = JdkChronoUnit.DAYS.between(nowDate, fireDate)
    return when {
        days <= 0L -> "Today"
        days == 1L -> "Tomorrow"
        days in 2L..6L -> fireDate.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> fireDate.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
    }
}
