package com.noop.analytics

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Exact Fold debug period_start days pulled from noop_period_calendar.xml (USB <ADB_SERIAL_USB>).
 * Guards the August blank-calendar bug: last start 2026-06-04 with today mid-July.
 */
class PeriodCalendarDeviceFixtureTest {
    private val deviceStarts = listOf(
        "2019-07-18", "2019-07-22", "2019-08-09", "2020-01-17", "2020-07-13", "2020-07-25",
        "2020-07-30", "2020-08-07", "2020-08-11", "2020-08-13", "2020-08-15", "2020-08-17",
        "2020-08-30", "2020-09-12", "2020-09-18", "2021-02-23", "2021-07-28", "2021-08-02",
        "2021-08-06", "2021-08-08", "2021-08-13", "2021-08-18", "2021-08-20", "2021-08-30",
        "2021-09-06", "2021-09-09", "2021-09-11", "2022-03-21", "2022-05-02", "2022-08-20",
        "2022-08-25", "2022-08-27", "2022-09-10", "2022-10-06", "2022-10-12", "2022-10-17",
        "2022-11-14", "2023-03-02", "2023-03-07", "2023-03-10", "2023-03-22", "2023-04-07",
        "2023-04-12", "2023-04-15", "2023-04-24", "2023-04-28", "2023-05-01", "2023-05-27",
        "2023-09-12", "2023-09-16", "2023-09-19", "2023-09-24", "2023-09-29", "2023-10-02",
        "2023-10-08", "2023-10-19", "2023-10-21", "2023-10-23", "2023-10-27", "2023-11-27",
        "2023-12-03", "2024-03-30", "2024-05-01", "2024-05-06", "2024-05-17", "2024-06-03",
        "2024-06-07", "2024-06-21", "2025-11-01", "2025-11-19", "2026-06-04",
    )

    @Test
    fun foldPcImportProjectsAugust2026() {
        val today = LocalDate.of(2026, 7, 12)
        val events = deviceStarts.map {
            PeriodCalendar.Event(it, PeriodCalendar.EventKind.PERIOD_START, source = "pc_import")
        }
        val snap = PeriodCalendar.evaluate(today, events, PeriodCalendar.Prefs(enabled = true))
        assertTrue("expected forecast windows, got none (avg=${snap.avgCycleLength})", snap.forecastWindows.isNotEmpty())
        assertTrue(snap.nextPeriodLikely != null)
        assertTrue(!LocalDate.parse(snap.nextPeriodLikely!!).isBefore(today))
        val aug = PeriodCalendar.monthGrid(YearMonth.of(2026, 8), today, events, snap)
        assertTrue(
            "August predicted period missing; next=${snap.nextPeriodLikely} windows=${snap.forecastWindows.take(4)}",
            aug.any { it.inMonth && it.isPredictedPeriod },
        )
    }
}
