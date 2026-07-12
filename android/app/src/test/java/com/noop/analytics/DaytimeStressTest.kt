package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun duplicateOrImplausibleHrDoesNotCreateStressEvidence() {
        val duplicates = List(DaytimeStress.minHourHrSamples) {
            HrSample(deviceId = "t", ts = 6L * 3_600L, bpm = 80)
        }
        val invalid = hourHr(hour = 6, bpm = 255)
        assertTrue(DaytimeStress.analyze(duplicates, emptyList()).scored.isEmpty())
        assertTrue(DaytimeStress.analyze(invalid, emptyList()).scored.isEmpty())
    }
}
