package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class UnifiedAlarmResolverTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(utc).toInstant().toEpochMilli()

    @Test fun disabledAlarmIsIgnored() {
        val a = UnifiedAlarm(id = "a", enabled = false, wakeMinutes = 7 * 60)
        val now = epoch(2026, 6, 23, 10, 0)
        assertNull(UnifiedAlarmResolver.nextFireAtEpochMs(a, now, utc))
    }

    @Test fun oneShotFiresLaterToday() {
        val a = UnifiedAlarm(id = "a", wakeMinutes = 18 * 60)   // 18:00 once, next occurrence
        val now = epoch(2026, 6, 23, 10, 0)
        assertEquals(epoch(2026, 6, 23, 18, 0), UnifiedAlarmResolver.nextFireAtEpochMs(a, now, utc))
    }

    @Test fun oneShotFiresTomorrowWhenTodayIsPast() {
        val a = UnifiedAlarm(id = "a", wakeMinutes = 6 * 60)    // 06:00 once, next occurrence
        val now = epoch(2026, 6, 23, 10, 0)
        assertEquals(epoch(2026, 6, 24, 6, 0), UnifiedAlarmResolver.nextFireAtEpochMs(a, now, utc))
    }

    @Test fun explicitAllDaysAlsoFiresTomorrowWhenTodayIsPast() {
        val a = UnifiedAlarm(id = "a", wakeMinutes = 6 * 60, weekdays = setOf(1, 2, 3, 4, 5, 6, 7))
        val now = epoch(2026, 6, 23, 10, 0)
        assertEquals(epoch(2026, 6, 24, 6, 0), UnifiedAlarmResolver.nextFireAtEpochMs(a, now, utc))
    }

    @Test fun weekdaySetSkipsToNextMatch() {
        // 2026-06-23 is a Tuesday. Calendar weekday Tue = 3. Alarm fires only on Saturday (7).
        val a = UnifiedAlarm(id = "a", wakeMinutes = 9 * 60, weekdays = setOf(7))
        val now = epoch(2026, 6, 23, 10, 0)
        // Next Saturday after Tue 23 is Sat 27.
        assertEquals(epoch(2026, 6, 27, 9, 0), UnifiedAlarmResolver.nextFireAtEpochMs(a, now, utc))
    }

    @Test fun weekdayTodayBeforeFireTimeFiresToday() {
        // Same Tuesday (cal weekday 3), 06:00 now, alarm at 09:00 only on Tue.
        val a = UnifiedAlarm(id = "a", wakeMinutes = 9 * 60, weekdays = setOf(3))
        val now = epoch(2026, 6, 23, 6, 0)
        assertEquals(epoch(2026, 6, 23, 9, 0), UnifiedAlarmResolver.nextFireAtEpochMs(a, now, utc))
    }

    @Test fun emptyListHasEmptySchedule() {
        val s = UnifiedAlarmResolver.resolveSchedule(emptyList(), epoch(2026, 6, 23, 10, 0), utc)
        assertNull(s.nextStrapArm)
        assertTrue(s.phoneAlarms.isEmpty())
    }

    @Test fun strapHeadIsEarliestStrapOrStrapAndPhone() {
        val a = UnifiedAlarm(id = "a", wakeMinutes = 18 * 60, source = AlarmSource.STRAP)         // 18:00
        val b = UnifiedAlarm(id = "b", wakeMinutes = 16 * 60, source = AlarmSource.STRAP_AND_PHONE) // 16:00 (earlier)
        val c = UnifiedAlarm(id = "c", wakeMinutes = 14 * 60, source = AlarmSource.PHONE)         // 14:00 (phone-only -> not strap-eligible)
        val now = epoch(2026, 6, 23, 10, 0)
        val s = UnifiedAlarmResolver.resolveSchedule(listOf(a, b, c), now, utc)
        assertEquals("b", s.nextStrapArm?.alarmId)
        // Phone fires include b and c, but NOT a (a is .STRAP).
        assertEquals(setOf("b", "c"), s.phoneAlarms.map { it.alarmId }.toSet())
    }

    @Test fun strapHeadHasNoLeadWhenSmartWakeOff() {
        // Smart wake off -> windowStart == wakeTime (no early-fire window).
        val a = UnifiedAlarm(id = "a", wakeMinutes = 16 * 60, smartWake = false, source = AlarmSource.STRAP)
        val now = epoch(2026, 6, 23, 10, 0)
        val s = UnifiedAlarmResolver.resolveSchedule(listOf(a), now, utc)
        val expectedWakeAt = epoch(2026, 6, 23, 16, 0)
        assertEquals(expectedWakeAt, s.nextStrapArm?.wakeEpochMs)
        assertEquals(expectedWakeAt, s.nextStrapArm?.windowStartEpochMs)
    }

    @Test fun strapHeadWindowOpensBeforeWakeWhenSmartWakeOn() {
        val a = UnifiedAlarm(
            id = "a", wakeMinutes = 16 * 60, smartWake = true,
            preWakeWindowMinutes = 30, source = AlarmSource.STRAP,
        )
        val now = epoch(2026, 6, 23, 10, 0)
        val s = UnifiedAlarmResolver.resolveSchedule(listOf(a), now, utc)
        val wakeAt = epoch(2026, 6, 23, 16, 0)
        assertEquals(wakeAt, s.nextStrapArm?.wakeEpochMs)
        assertEquals(wakeAt - 30 * 60_000L, s.nextStrapArm?.windowStartEpochMs)
    }

    @Test fun strapAndPhonePhoneFiresAfterWakeAsBackup() {
        val a = UnifiedAlarm(
            id = "a", wakeMinutes = 16 * 60, source = AlarmSource.STRAP_AND_PHONE,
            phoneBackupDelayMinutes = 7,
        )
        val now = epoch(2026, 6, 23, 10, 0)
        val s = UnifiedAlarmResolver.resolveSchedule(listOf(a), now, utc)
        val wakeAt = epoch(2026, 6, 23, 16, 0)
        val phoneFire = s.phoneAlarms.single()
        assertEquals(wakeAt + 7 * 60_000L, phoneFire.fireAtEpochMs)
        assertTrue(phoneFire.isStrapPhoneBackup)
        assertEquals(phoneFire.fireAtEpochMs, phoneFire.windowStartEpochMs)
    }

    @Test fun phoneOnlySmartWakePhoneFiresAtWakeWithEarlierWindow() {
        val a = UnifiedAlarm(
            id = "a", wakeMinutes = 6 * 60, source = AlarmSource.PHONE,
            smartWake = true, preWakeWindowMinutes = 30,
        )
        // Use a now BEFORE today's 06:00 so the head fires today (not tomorrow).
        val now = epoch(2026, 6, 23, 3, 0)
        val s = UnifiedAlarmResolver.resolveSchedule(listOf(a), now, utc)
        val wakeAt = epoch(2026, 6, 23, 6, 0)
        val phoneFire = s.phoneAlarms.single()
        assertEquals(wakeAt, phoneFire.fireAtEpochMs)
        assertEquals(wakeAt - 30 * 60_000L, phoneFire.windowStartEpochMs)
        assertTrue(phoneFire.smartWake)
        assertTrue(!phoneFire.isStrapPhoneBackup)
    }

    @Test fun displayOrderDoesNotAffectStrapHead() {
        val a = UnifiedAlarm(id = "a", wakeMinutes = 18 * 60, source = AlarmSource.STRAP)  // 18:00
        val b = UnifiedAlarm(id = "b", wakeMinutes = 16 * 60, source = AlarmSource.STRAP)  // 16:00
        val s1 = UnifiedAlarmResolver.resolveSchedule(listOf(a, b), epoch(2026, 6, 23, 10, 0), utc)
        val s2 = UnifiedAlarmResolver.resolveSchedule(listOf(b, a), epoch(2026, 6, 23, 10, 0), utc)
        assertEquals("b", s1.nextStrapArm?.alarmId)
        assertEquals("b", s2.nextStrapArm?.alarmId)
    }

    @Test fun phoneOnlyAlarmYieldsNoStrapHead() {
        val a = UnifiedAlarm(id = "a", wakeMinutes = 6 * 60, source = AlarmSource.PHONE)
        val s = UnifiedAlarmResolver.resolveSchedule(listOf(a), epoch(2026, 6, 23, 10, 0), utc)
        assertNull(s.nextStrapArm)
        assertEquals(listOf("a"), s.phoneAlarms.map { it.alarmId })
    }

    @Test fun disabledAlarmsDropOutOfSchedule() {
        val a = UnifiedAlarm(id = "a", enabled = false, wakeMinutes = 6 * 60, source = AlarmSource.STRAP)
        val s = UnifiedAlarmResolver.resolveSchedule(listOf(a), epoch(2026, 6, 23, 10, 0), utc)
        assertNull(s.nextStrapArm)
        assertTrue(s.phoneAlarms.isEmpty())
    }

    @Test fun dstSpringForwardSkipsTo0300() {
        // 2026-03-08 02:30 does not exist in America/New_York (spring forward at 02:00 to 03:00).
        // The resolver must hand back a valid future instant, NOT crash.
        val ny = ZoneId.of("America/New_York")
        val a = UnifiedAlarm(id = "a", wakeMinutes = 150)   // 02:30
        val nowNy = LocalDateTime.of(2026, 3, 8, 1, 0).atZone(ny).toInstant().toEpochMilli()
        val fire = UnifiedAlarmResolver.nextFireAtEpochMs(a, nowNy, ny)
        assertNotNull(fire)
        assertTrue(fire!! > nowNy)
    }
}
