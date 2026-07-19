package com.noop.analytics

import com.noop.data.PpgRespSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [SleepStager.respRateFromPpg] (issue #103) — the top-third-by-confidence aggregation over
 * the PPG-derived per-burst stream (com.noop.protocol.PpgResp.deriveRespRate), used by
 * AnalyticsEngine's prefer-PPG-else-RSA fusion. Mirrors [RespRateRsaTest]'s style: synthetic sample
 * lists, not captures.
 */
class RespRateFromPpgTest {
    private val start = 1_700_000_000L
    private val end = 1_700_030_000L

    /** [n] bursts, evenly spaced across [start, end], with bpm/conf from parallel lists. */
    private fun samples(bpm: List<Double>, conf: List<Double>): List<PpgRespSample> {
        val n = bpm.size
        val step = (end - start) / maxOf(n, 1)
        return (0 until n).map { i ->
            PpgRespSample(deviceId = "my-whoop", ts = start + i * step, bpm = bpm[i], conf = conf[i])
        }
    }

    @Test
    fun recoversMedianOfTopThirdByConfidence() {
        // 9 bursts (minPpgBurstsForEstimate): the top 3 by conf are bpm 14/15/16 → median 15. The
        // other 6 are noisy/low-confidence and must be ignored, not dragged into the result.
        val bpm = listOf(14.0, 15.0, 16.0, 5.0, 30.0, 9.0, 25.0, 6.0, 28.0)
        val conf = listOf(10.0, 12.0, 11.0, 2.0, 1.5, 2.0, 1.5, 2.0, 1.5)
        val est = SleepStager.respRateFromPpg(samples(bpm, conf), start, end)
        assertEquals(15.0, est, 0.001)
    }

    @Test
    fun tooFewBurstsIsNaN() {
        // minPpgBurstsForEstimate - 1 bursts, all otherwise well-formed → NaN, not a fabricated median.
        val n = SleepStager.minPpgBurstsForEstimate - 1
        val bpm = List(n) { 15.0 }
        val conf = List(n) { 10.0 }
        assertTrue(SleepStager.respRateFromPpg(samples(bpm, conf), start, end).isNaN())
        assertTrue(SleepStager.respRateFromPpg(emptyList(), start, end).isNaN())
    }

    @Test
    fun exactlyMinimumBurstsProducesAnEstimate() {
        val n = SleepStager.minPpgBurstsForEstimate
        val bpm = List(n) { 15.0 }
        val conf = List(n) { 10.0 }
        assertTrue(!SleepStager.respRateFromPpg(samples(bpm, conf), start, end).isNaN())
    }

    @Test
    fun filtersBurstsOutsideTheSessionWindow() {
        // 9 in-window bursts at 15 bpm, plus 20 far-outside-window bursts at 99 bpm that must not
        // count toward the minimum OR pollute the median.
        val inWindow = samples(List(9) { 15.0 }, List(9) { 10.0 })
        val outside = (0 until 20).map {
            PpgRespSample(deviceId = "my-whoop", ts = start - 100_000 - it, bpm = 99.0, conf = 50.0)
        }
        val est = SleepStager.respRateFromPpg(inWindow + outside, start, end)
        assertEquals(15.0, est, 0.001)
    }

    @Test
    fun medianOutsidePlausibleBandIsNaN() {
        // 9 bursts all agreeing on an implausible 3 bpm (below the 8-25 band) — never surfaced.
        val bpm = List(9) { 3.0 }
        val conf = List(9) { 10.0 }
        assertTrue(SleepStager.respRateFromPpg(samples(bpm, conf), start, end).isNaN())
    }

    @Test
    fun topThirdRoundingIsFloorDivision() {
        // 10 bursts → top third = max(1, 10/3) = 3 (floor division, not rounded/ceiled). The top 3 by
        // conf are 14/15/16 (median 15); if rounding were ceil (4), bpm 5.0 (conf 9.0) would enter the
        // top group and pull the median down to 14.5.
        val bpm = listOf(14.0, 15.0, 16.0, 5.0, 30.0, 9.0, 25.0, 6.0, 28.0, 2.0)
        val conf = listOf(10.0, 12.0, 11.0, 9.0, 1.5, 2.0, 1.5, 2.0, 1.5, 1.0)
        val est = SleepStager.respRateFromPpg(samples(bpm, conf), start, end)
        assertEquals(15.0, est, 0.001)
    }

    @Test
    fun endNotAfterStartIsNaN() {
        val recs = samples(List(9) { 15.0 }, List(9) { 10.0 })
        assertTrue(SleepStager.respRateFromPpg(recs, end, start).isNaN())
        assertTrue(SleepStager.respRateFromPpg(recs, start, start).isNaN())
    }
}
