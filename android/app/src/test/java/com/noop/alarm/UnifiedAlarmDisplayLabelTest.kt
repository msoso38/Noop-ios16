package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class UnifiedAlarmDisplayLabelTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val enUS = Locale.US

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(utc).toInstant().toEpochMilli()

    @Test fun disabledIsOff() {
        val a = UnifiedAlarm(id = "a", enabled = false, wakeMinutes = 7 * 60)
        assertEquals("Off", displayLabel(a, epoch(2026, 6, 23, 10, 0), utc, enUS))
    }

    @Test fun todayWhenFireLaterToday() {
        val a = UnifiedAlarm(id = "a", wakeMinutes = 18 * 60)
        assertEquals("Today", displayLabel(a, epoch(2026, 6, 23, 10, 0), utc, enUS))
    }

    @Test fun tomorrowWhenFireIsNextCalendarDay() {
        val a = UnifiedAlarm(id = "a", wakeMinutes = 6 * 60)
        assertEquals("Tomorrow", displayLabel(a, epoch(2026, 6, 23, 10, 0), utc, enUS))
    }

    @Test fun weekdayNameInWindow2to6() {
        // Tuesday 2026-06-23 10:00, alarm only on Saturday (cal weekday 7) - 4 days ahead.
        val a = UnifiedAlarm(id = "a", wakeMinutes = 9 * 60, weekdays = setOf(7))
        assertEquals("Saturday", displayLabel(a, epoch(2026, 6, 23, 10, 0), utc, enUS))
    }

    @Test fun shortDateWhenSevenOrMoreDaysAhead() {
        // Alarm only on the same Tuesday (cal 3); now Wed 10:00 -> next fire is in 6 days
        // (cal 3 again falls 6 days after Wed). Bump to a setup that yields exactly 7 days:
        // now = Tue 10:00, alarm at Tue 09:00 only -> next Tue is 7 days away.
        val a = UnifiedAlarm(id = "a", wakeMinutes = 9 * 60, weekdays = setOf(3))
        val nowTueAfterFire = epoch(2026, 6, 23, 10, 0)  // Tue 10:00, today's 09:00 already past
        val label = displayLabel(a, nowTueAfterFire, utc, enUS)
        // 7 days ahead -> short date branch.
        assertEquals("Tue, Jun 30", label)
    }
}
