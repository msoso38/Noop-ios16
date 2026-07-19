package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

/**
 * [PpgResp] — respiratory rate from the WHOOP 5/MG v26 optical PPG buffer (#103): a band-limited
 * spectral peak search (0.15-0.40 Hz) on the RIAV (amplitude envelope) / RIIV (baseline) channels of
 * a ~40 s burst. Independently-written synthetic tests (not shared vectors with the Swift lane),
 * matching [PpgHrTest]'s style for the sibling per-second HR estimator on the same buffer.
 */
class PpgRespTest {
    private val fs = PpgResp.SAMPLE_RATE_HZ // 24

    /** One synthetic burst: a ~1.1 Hz pulse carrier whose AMPLITUDE and BASELINE are both modulated
     *  at [breathBpm] breaths/min — RIAV picks up the amplitude modulation, RIIV the baseline shift,
     *  so either channel alone is enough to recover the planted rate. */
    private fun breathingBurst(
        breathBpm: Double,
        seconds: Int = 40,
        baseTs: Long = 1_780_000_000L,
        pulseHz: Double = 1.1,
        carrierAmp: Double = 1000.0,
        modDepth: Double = 400.0,
        baselineAmp: Double = 300.0,
    ): List<PpgHr.Sample> {
        val breathHz = breathBpm / 60.0
        val total = seconds * fs
        val samples = ArrayList<PpgHr.Sample>(total)
        for (n in 0 until total) {
            val t = n.toDouble() / fs
            val breath = sin(2.0 * PI * breathHz * t)
            val amplitude = carrierAmp + modDepth * breath
            val pulse = amplitude * sin(2.0 * PI * pulseHz * t)
            val baseline = baselineAmp * breath
            samples.add(PpgHr.Sample(ts = baseTs + (n / fs), value = (pulse + baseline).toInt()))
        }
        return samples
    }

    @Test
    fun recoversKnownBreathingRate() {
        val (bpm, conf) = PpgResp.estimate(breathingBurst(15.0).map { it.value })!!
        assertTrue("bpm $bpm not within 15±1", bpm in 14.0..16.0)
        assertTrue("conf $conf below gate", conf >= PpgResp.MIN_PROMINENCE)
    }

    @Test
    fun recoversASlowBreathingRate() {
        // Near the low end of the band (11 breaths/min = 0.1833 Hz, clear of the BAND_EDGE_GUARD_HZ
        // margin around the 9 breaths/min floor) — must not fold to the high end.
        val (bpm, _) = PpgResp.estimate(breathingBurst(11.0).map { it.value })!!
        assertTrue("bpm $bpm not within 11±1", bpm in 10.0..12.0)
    }

    @Test
    fun recoversAFastBreathingRate() {
        // Near the high end of the band (19 breaths/min = 0.3167 Hz) — exercises the upper half of
        // the 0.15-0.40 Hz search, not just the low/mid values the other tests cover.
        val (bpm, _) = PpgResp.estimate(breathingBurst(19.0).map { it.value })!!
        assertTrue("bpm $bpm not within 19±1", bpm in 18.0..20.0)
    }

    @Test
    fun deriveRespRateOneSamplePerBurst() {
        val series = PpgResp.deriveRespRate(breathingBurst(15.0))
        assertEquals("one ~40s burst must yield exactly one estimate, not per-second", 1, series.size)
        assertEquals("ts must be the burst's FIRST record timestamp", 1_780_000_000L, series[0].ts)
        assertTrue("bpm ${series[0].bpm} not within 15±1", series[0].bpm in 14.0..16.0)
    }

    @Test
    fun flatSignalProducesNoEstimate() {
        // Constant DC (no variation at all) → after centering, every band bin is exactly zero power →
        // no fabricated rate. Long enough to clear the minimum-burst-length gate.
        val samples = (0 until 40 * fs).map { i -> PpgHr.Sample(ts = 1_780_000_000L + i / fs, value = 5000) }
        assertTrue(
            "a flat signal must not produce a fabricated respiratory rate",
            PpgResp.deriveRespRate(samples).isEmpty(),
        )
    }

    @Test
    fun estimateRejectsTooShortBurst() {
        // A single ~1 second of breathing modulation is nowhere near enough to resolve 0.15 Hz.
        val oneSecond = breathingBurst(15.0, seconds = 1).map { it.value }
        assertEquals(null, PpgResp.estimate(oneSecond))
    }

    @Test
    fun burstShorterThanMinimumIsDropped() {
        // MIN_BURST_RECORDS (32) worth of a run is required; 20 records must not yield an estimate
        // even though each individual second is well-formed.
        val records = breathingBurst(15.0, seconds = 40).take(20 * fs)
        assertTrue(PpgResp.deriveRespRate(records).isEmpty())
    }

    @Test
    fun gapBreaksRunsIntoSeparateBursts() {
        // Two full 40 s bursts separated by a gap — both estimate independently, none inside the gap.
        val recs = breathingBurst(12.0, baseTs = 1_780_000_000L) +
            breathingBurst(18.0, baseTs = 1_780_001_000L)
        val series = PpgResp.deriveRespRate(recs)
        assertEquals(2, series.size)
        assertTrue("bpm ${series[0].bpm} not within 12±1", series[0].bpm in 11.0..13.0)
        assertTrue("bpm ${series[1].bpm} not within 18±1", series[1].bpm in 17.0..19.0)
        assertEquals(1_780_000_000L, series[0].ts)
        assertEquals(1_780_001_000L, series[1].ts)
    }

    @Test
    fun deriveRespRateSamplesMayBeUnsorted() {
        val series = PpgResp.deriveRespRate(breathingBurst(15.0).shuffled(kotlin.random.Random(7)))
        assertEquals(1, series.size)
        assertEquals(1_780_000_000L, series[0].ts)
    }
}
