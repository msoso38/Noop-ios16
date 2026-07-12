package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

class PeriodCalendarTest {
    @Test
    fun futureStartDoesNotForceTodayIntoMenstrualPhase() {
        val snapshot = PeriodCalendar.evaluate(
            today = LocalDate.of(2026, 7, 10),
            events = listOf(
                PeriodCalendar.Event("2026-06-15", PeriodCalendar.EventKind.PERIOD_START),
                PeriodCalendar.Event("2026-07-13", PeriodCalendar.EventKind.PERIOD_START),
            ),
            prefs = PeriodCalendar.Prefs(enabled = true),
        )

        assertEquals("2026-06-15", snapshot.lastPeriodStart)
        assertEquals(1, snapshot.loggedStartCount)
        assertEquals(26, snapshot.cycleDay)
        assertEquals(PeriodCalendar.CalendarPhase.LUTEAL, snapshot.phase)
    }

    @Test
    fun futureOnlyStartLeavesCurrentPhaseInLearning() {
        val snapshot = PeriodCalendar.evaluate(
            today = LocalDate.of(2026, 7, 10),
            events = listOf(PeriodCalendar.Event("2026-07-13", PeriodCalendar.EventKind.PERIOD_START)),
            prefs = PeriodCalendar.Prefs(enabled = true),
        )

        assertEquals(PeriodCalendar.CalendarPhase.LEARNING, snapshot.phase)
        assertNull(snapshot.lastPeriodStart)
        assertEquals(0, snapshot.loggedStartCount)
        assertNull(snapshot.cycleDay)
    }

    @Test
    fun malformedImportDateDoesNotDiscardLaterValidEvent() {
        val events = PeriodCalendar.parseImportCsv("date,type,note\n2026-99-99,period_start,bad\n2026-07-10,period_start,ok")
        assertEquals(1, events.size)
        assertEquals("2026-07-10", events.single().day)
        assertEquals(PeriodCalendar.EventKind.PERIOD_START, events.single().kind)
    }

    @Test
    fun twelveMonthPlanningUnlocksWithTwoLoggedStarts() {
        // Engine needs ≥2 starts (one gap in 21–40d). UI copy matches /2 starts.
        val starts = listOf("2026-01-01", "2026-01-29")
        val windows = PeriodCalendar.longRangeForecastWindows(
            today = LocalDate.of(2026, 2, 5),
            starts = starts,
            firstLikely = LocalDate.of(2026, 2, 26),
        )
        assertTrue(windows.isNotEmpty())
        assertTrue(windows.all { !LocalDate.parse(it.likelyDay).isAfter(LocalDate.of(2026, 2, 5).plusMonths(12)) })
    }

    @Test
    fun twelveMonthPlanningNeedsTwoLoggedStarts_threeStillWorks() {
        val starts = listOf("2026-01-01", "2026-01-29", "2026-02-26")
        val windows = PeriodCalendar.longRangeForecastWindows(
            today = LocalDate.of(2026, 3, 1),
            starts = starts,
            firstLikely = LocalDate.of(2026, 3, 26),
        )
        assertTrue(windows.isNotEmpty())
    }

    @Test
    fun twelveMonthPlanningUsesOnlyHistoryAndWidensUncertainWindows() {
        val today = LocalDate.of(2026, 5, 1)
        val starts = listOf("2026-01-01", "2026-01-29", "2026-02-28", "2026-03-27", "2026-04-27")
        val windows = PeriodCalendar.longRangeForecastWindows(
            today = today,
            starts = starts,
            firstLikely = LocalDate.of(2026, 5, 26),
        )
        assertTrue(windows.isNotEmpty())
        assertTrue(windows.all { !LocalDate.parse(it.likelyDay).isAfter(today.plusMonths(12)) })
        assertTrue(windows.last().latestDay >= windows.first().latestDay)
        assertTrue(windows.last().earliestDay <= windows.last().likelyDay)
    }

    @Test
    fun stalePeriodAnchorRollsForwardInsteadOfBlankingYear() {
        val today = LocalDate.of(2026, 7, 12)
        val windows = PeriodCalendar.longRangeForecastWindows(
            today = today,
            starts = listOf("2026-01-01", "2026-01-29", "2026-02-26", "2026-03-26"),
            firstLikely = LocalDate.of(2026, 4, 23),
            cycleLenHint = 28,
        )
        assertTrue(windows.isNotEmpty())
        assertTrue(windows.all { !LocalDate.parse(it.likelyDay).isBefore(today) })
        assertTrue(windows.any { LocalDate.parse(it.likelyDay).monthValue == 8 })
    }

    @Test
    fun juneStartProjectsAugustPredictedPeriodDays() {
        // Device case: last .pc start 2026-06-04, today mid-July → next unlogged cycles into Aug.
        val today = LocalDate.of(2026, 7, 12)
        val events = listOf(
            PeriodCalendar.Event("2026-05-06", PeriodCalendar.EventKind.PERIOD_START),
            PeriodCalendar.Event("2026-06-04", PeriodCalendar.EventKind.PERIOD_START),
        )
        val snap = PeriodCalendar.evaluate(today, events, PeriodCalendar.Prefs(enabled = true))
        assertTrue(snap.forecastWindows.isNotEmpty())
        assertTrue(!LocalDate.parse(snap.nextPeriodLikely!!).isBefore(today))
        val aug = PeriodCalendar.monthGrid(YearMonth.of(2026, 8), today, events, snap)
        assertTrue(
            "August must show predicted period days; windows=${snap.forecastWindows}",
            aug.any { it.inMonth && it.isPredictedPeriod },
        )
        assertTrue(aug.any { it.inMonth && it.isPredictedWindow })
    }

    @Test
    fun irregularPcGapsStillForecastWithCycleHint() {
        // Mined .pc often has gaps outside 21–40; planning must not go blank.
        val today = LocalDate.of(2026, 7, 12)
        val windows = PeriodCalendar.longRangeForecastWindows(
            today = today,
            starts = listOf("2025-11-01", "2025-11-19", "2026-06-04"),
            firstLikely = LocalDate.of(2026, 7, 2),
            cycleLenHint = 28,
        )
        assertTrue(windows.isNotEmpty())
        assertTrue(windows.any { it.likelyDay.startsWith("2026-08") || it.latestDay.startsWith("2026-08") })
    }

    @Test
    fun calendarShadesLaterConditionalPlanningWindows() {
        val today = LocalDate.of(2026, 5, 1)
        val events = listOf("2026-01-01", "2026-01-29", "2026-02-28", "2026-03-27", "2026-04-27")
            .map { PeriodCalendar.Event(it, PeriodCalendar.EventKind.PERIOD_START) }
        val snapshot = PeriodCalendar.evaluate(today, events, PeriodCalendar.Prefs(enabled = true))
        assertEquals(5, snapshot.loggedStartCount)
        assertTrue(snapshot.forecastWindows.size > 1)
        val later = snapshot.forecastWindows[1]
        val grid = PeriodCalendar.monthGrid(YearMonth.from(LocalDate.parse(later.likelyDay)), today, emptyList(), snapshot)
        assertTrue(grid.any { it.day == later.likelyDay && it.isPredictedPeriod })
    }

    @Test
    fun skippedTrackingGapIsNotTreatedAsLongCycle() {
        // 28d + 56d (missed log) + 29d → model should repair ~28, not average in 56.
        val starts = listOf("2026-01-01", "2026-01-29", "2026-03-26", "2026-04-24")
        val model = PeriodCalendar.cycleLengthModel(starts)
        assertTrue(model.skippedArtifactCount >= 1)
        assertTrue(model.meanDays in 26..32)
        assertTrue(model.validGapCount >= 2)
    }

    @Test
    fun planningPeriodStartsCollapsesNearImportNoise() {
        val events = listOf(
            "2023-04-07", "2023-04-10", "2023-04-12", "2023-04-15", // near cluster
            "2023-05-27",
            "2023-09-12", "2023-09-16", "2023-09-19",
        ).map { PeriodCalendar.Event(it, PeriodCalendar.EventKind.PERIOD_START) }
        val planned = PeriodCalendar.planningPeriodStarts(events, minGapDays = 14)
        assertEquals(listOf("2023-04-07", "2023-05-27", "2023-09-12"), planned)
    }

    @Test
    fun forecastHalfWidthCappedSoMonthIsNotWashed() {
        val today = LocalDate.of(2026, 7, 12)
        // High-variance history would previously widen ± halfWidth past 4.
        val starts = listOf(
            "2025-11-01", "2025-11-19", "2026-06-04",
        )
        val windows = PeriodCalendar.longRangeForecastWindows(
            today = today,
            starts = starts,
            firstLikely = LocalDate.of(2026, 7, 16),
            cycleLenHint = 28,
        )
        assertTrue(windows.isNotEmpty())
        for (w in windows.take(3)) {
            val half = ChronoUnit.DAYS.between(
                LocalDate.parse(w.earliestDay),
                LocalDate.parse(w.likelyDay),
            )
            assertTrue("halfWidth capped at 4, got $half for ${w.likelyDay}", half <= 4)
        }
    }

    @Test
    fun neverAdvertisesPastDateAsNextPeriod() {
        val today = LocalDate.of(2026, 7, 12)
        // Stale June anchor must roll forward; "next" cannot be June.
        val events = listOf(
            PeriodCalendar.Event("2026-05-06", PeriodCalendar.EventKind.PERIOD_START),
            PeriodCalendar.Event("2026-06-04", PeriodCalendar.EventKind.PERIOD_START),
        )
        val snap = PeriodCalendar.evaluate(today, events, PeriodCalendar.Prefs(enabled = true))
        assertTrue(snap.nextPeriodLikely != null)
        val next = LocalDate.parse(snap.nextPeriodLikely!!)
        assertFalse("next=$next must not be before today=$today", next.isBefore(today))
        assertTrue("next must not stay in June when today is July", next.monthValue != 6 || next.year != 2026)
        assertTrue(snap.daysUntilLikely != null && snap.daysUntilLikely!! >= 0)
        assertTrue(snap.forecastWindows.all { !LocalDate.parse(it.likelyDay).isBefore(today) })
        // Last logged start may be past; that is history, not "next".
        assertEquals("2026-06-04", snap.lastPeriodStart)
    }

    @Test
    fun rollForwardNeverReturnsPast() {
        val today = LocalDate.of(2026, 7, 12)
        val rolled = PeriodCalendar.rollForwardLikely(
            today,
            LocalDate.of(2026, 6, 4).plusDays(28),
            28,
        )
        assertFalse(rolled.isBefore(today))
    }

    @Test
    fun monthGridOnlyPaintsWindowsIntersectingViewedMonth() {
        val today = LocalDate.of(2026, 7, 12)
        val events = listOf(
            PeriodCalendar.Event("2026-05-06", PeriodCalendar.EventKind.PERIOD_START),
            PeriodCalendar.Event("2026-06-04", PeriodCalendar.EventKind.PERIOD_START),
        )
        val snap = PeriodCalendar.evaluate(today, events, PeriodCalendar.Prefs(enabled = true))
        val jul = PeriodCalendar.monthGrid(YearMonth.of(2026, 7), today, events, snap)
        val aug = PeriodCalendar.monthGrid(YearMonth.of(2026, 8), today, events, snap)
        // July should not inherit August's predicted wash, and vice versa for out-of-month cells.
        assertTrue(jul.any { it.inMonth && (it.isPredictedPeriod || it.isPredictedWindow) })
        assertTrue(aug.any { it.inMonth && it.isPredictedPeriod })
        val julPredictedInAug = jul.filter {
            it.inMonth && it.isPredictedPeriod && it.day.startsWith("2026-08")
        }
        assertTrue(julPredictedInAug.isEmpty())
    }
}
