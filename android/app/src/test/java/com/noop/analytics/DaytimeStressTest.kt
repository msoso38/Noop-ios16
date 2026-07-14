package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests DaytimeStress.analyze — the intraday (hour-by-hour) autonomic stress timeline.
 * Pure-function tests; no DB. Kotlin twin of the StrandAnalytics DaytimeStressTests.
 */
class DaytimeStressTest {

    /** Fill one local hour-of-day with `n` 1 Hz HR samples at `bpm` (UTC, tz offset 0). */
    private fun hourHr(hour: Int, bpm: Int, n: Int = DaytimeStress.minHourHrSamples): List<HrSample> {
        val base = hour.toLong() * 3_600L
        return (0 until n).map { HrSample(deviceId = "t", ts = base + it, bpm = bpm) }
    }

    @Test
    fun sleepHoursInTheWindow_doNotShiftTheWakingTimeline() {
        // Regression (#357): the calm reference is built from the WAKING hours that are actually
        // scored, not the whole 24 h. The analysis window always starts at local midnight, so the
        // current day routinely carries several hours of sleep — the calmest, lowest-HR stretch of
        // the day. If those night hours leak into the reference they drag the "calm" anchor far
        // below every waking hour, inflating an ordinary calm day into sustained high stress
        // (tripping the passive Breathe nudge). So adding calm sleep hours to the input must NOT
        // change the waking timeline.
        val wakingBpm = listOf(62, 64, 63, 65, 64, 63, 62, 64, 66, 63, 64, 65) // hours 6..17
        val waking = (6..17).flatMapIndexed { i, h -> hourHr(h, wakingBpm[i]) }
        val sleepBpm = listOf(50, 51, 52, 51, 50, 53) // hours 0..5
        val sleep = (0..5).flatMapIndexed { i, h -> hourHr(h, sleepBpm[i]) }

        val noRr = emptyList<RrInterval>()
        val wakingOnly = DaytimeStress.analyze(waking, noRr)
        val withSleep = DaytimeStress.analyze(sleep + waking, noRr)

        assertEquals(
            "sleep hours sharing the window must not change the sustained-high verdict",
            wakingOnly.sustainedHigh, withSleep.sustainedHigh,
        )
        for (h in 6..17) {
            val withLvl = withSleep.scored.firstOrNull { it.hour == h }?.level
            val withoutLvl = wakingOnly.scored.firstOrNull { it.hour == h }?.level
            assertNotNull("waking hour $h should be scored in both runs", withLvl)
            assertNotNull("waking hour $h should be scored in both runs", withoutLvl)
            assertEquals(
                "the night's sleep hours leaked into the daytime reference and shifted waking hour $h",
                withoutLvl!!, withLvl!!, 1e-9,
            )
        }
        // The plain sanity check the bug violated: an ordinary calm day is not "sustained high".
        assertFalse(
            "a calm desk day must not read as sustained high stress",
            withSleep.sustainedHigh,
        )
    }

    @Test
    fun lateSleepWindow_excludesMorningFromWakingReference() {
        // 13 Jul shape: sleep through ~13:00. Without sleepWindows, hours 6–12 (low HR) pollute
        // the waking calm reference. With a sleep window covering those hours, afternoon tip
        // should not be inflated relative to a day that never included them in the reference.
        val morningSleep = (6..12).flatMap { h -> hourHr(h, 52) }
        val afternoon = listOf(14, 15, 16, 17, 18, 19).flatMap { h -> hourHr(h, 72) }
        val noRr = emptyList<RrInterval>()
        // Sleep window 06:00–13:00 wall clock (tz 0).
        val window = listOf(6L * 3600L to 13L * 3600L)
        val withWindow = DaytimeStress.analyze(morningSleep + afternoon, noRr, sleepWindows = window)
        val afternoonOnly = DaytimeStress.analyze(afternoon, noRr)

        val tipWith = withWindow.scored.lastOrNull { it.hour == 19 }?.level
        val tipOnly = afternoonOnly.scored.lastOrNull { it.hour == 19 }?.level
        assertNotNull(tipWith)
        assertNotNull(tipOnly)
        // Sleep-window shaping should keep the afternoon tip close to the afternoon-only day
        // (not dragged high by treating morning sleep as waking calm).
        assertEquals(tipOnly!!, tipWith!!, 0.35)
        // Morning sleep hours may appear but must not drive sustained-high.
        assertFalse(withWindow.sustainedHigh)
    }
}
