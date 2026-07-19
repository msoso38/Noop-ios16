package com.noop.protocol

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Respiratory rate derived from the WHOOP 5.0 type-47 **v26** optical PPG buffer (issue #103).
 *
 * v26 records carry a single-wavelength 24 Hz PPG waveform (see [PpgHr] for the per-second HR
 * sibling estimator on the same buffer). Breathing modulates that waveform two ways — amplitude
 * (RIAV, respiratory-induced amplitude variation) and baseline (RIIV, respiratory-induced intensity
 * variation) — so a spectral peak in the respiratory band (0.15-0.40 Hz = 9-24 breaths/min) recovers
 * the rate without needing a second wavelength (SpO2 is NOT derivable this way — confirmed no second
 * channel exists on v26, see the #103 discussion).
 *
 * v26 records arrive in ~40 s bursts: the strap alternates, over the course of a night, between v18
 * per-second summaries and v26 raw-PPG stretches — never both at once — so v26 coverage is a sparse
 * minority of any given night. One estimate is produced per consecutive-second run (a "burst"), not
 * per second like [PpgHr], since resolving 0.15 Hz needs the whole burst, not a sliding window.
 *
 * Validated against 2 real overnight captures against the WHOOP app's own respiratory-rate reading:
 * landed within 2-6% using top-confidence-burst aggregation (see
 * com.noop.analytics.SleepStager.respRateFromPpg), vs. the existing R-R/RSA-based estimator's 6-11%
 * low bias on the same nights. That is thin evidence — one person, two nights, a person whose own
 * night-to-night respiratory rate barely moves — so treat [Estimate.conf] as a real per-burst quality
 * filter, not a reason to trust any single burst in isolation.
 *
 * Byte-for-byte mirror of the Swift estimator (WhoopProtocol/PpgResp.swift, #219-style parity), reusing
 * [PpgHr.Sample] as the input type since [PpgHr] and [PpgResp] consume the SAME accumulated v26
 * waveform buffer during historical-stream extraction.
 */
object PpgResp {
    /** v26 carries 24 samples per 1-second record. */
    const val SAMPLE_RATE_HZ = 24

    /** Reject a run shorter than this many one-second records (~1.33 s short of a full ~40 s burst) —
     *  too little data to resolve 0.15 Hz cleanly, so no fabricated estimate from a truncated burst. */
    const val MIN_BURST_RECORDS = 32

    const val RESP_LO_HZ = 0.15 // 9 breaths/min
    const val RESP_HI_HZ = 0.40 // 24 breaths/min

    /** ~1 s sliding window for the RIAV amplitude envelope. */
    const val ENVELOPE_WINDOW_SAMPLES = 24

    /** ~1 s causal moving-average window for the RIIV baseline. */
    const val BASELINE_WINDOW_SAMPLES = 24

    /** ~12.5 s causal moving-average window subtracted for the high-pass detrend ahead of the
     *  spectral search — removes sub-~0.08 Hz drift (perfusion/posture, not breathing). */
    const val DETREND_WINDOW_SAMPLES = 300

    /** Frequency step (Hz) for the band scan below. Does NOT itself add resolution beyond a ~40 s
     *  burst's native ~0.025 Hz (~1.5 breaths/min) DFT bin spacing (fs/n) — it only interpolates the
     *  direct-sum DTFT more finely between those bins, avoiding the ~1.5 bpm quantization steps a
     *  bin-quantized search would report. Matches the step used by the validated Python
     *  proof-of-concept this mirrors. */
    const val RESP_FREQ_STEP_HZ = 0.004

    /** Reject a peak found within this many Hz of the band edge (loHz/hiHz) — a real breathing peak
     *  is a local maximum with power falling off on both sides, but low-frequency drift (perfusion/
     *  posture) that leaked past the high-pass detrend has power that only INCREASES toward 0 Hz, so
     *  within a bounded search it deterministically "wins" at whichever edge is closest to 0 Hz
     *  regardless of real physiology. Confirmed on real data (#103 review): without this guard,
     *  32%/15% of bursts across the two validation nights landed exactly at the band floor (9
     *  breaths/min) — far more than plausible slow breathing — and top-confidence-burst aggregation
     *  was measurably CLOSER to the WHOOP app's ground truth with the guard than without it (14.52 vs
     *  14.04 breaths/min against a 14.6 truth). A rejected edge peak returns null for that channel
     *  (RIAV or RIIV), same honest-no-data handling as everywhere else in this estimator. Mirrors the
     *  Swift PpgResp.bandEdgeGuardHz. */
    const val BAND_EDGE_GUARD_HZ = 0.02

    /** Reject a burst whose best channel's peak isn't at least this many times the band's median
     *  power — a flat/noisy burst must not fabricate a rate. Mirrors [PpgHr.MIN_CONFIDENCE]'s role;
     *  set well below every real-capture burst observed so far (min ~2.0 across 57 real bursts from
     *  2 nights) while still rejecting a genuinely flat band (prominence 1.0 = no peak at all). */
    const val MIN_PROMINENCE = 1.5

    /** A derived respiratory-rate estimate: [ts] = the burst's FIRST record second, [bpm] (NOT
     *  rounded — unlike PpgHr's whole-bpm convention, resp-rate precision at ~14-15 bpm is the point),
     *  [conf] = spectral prominence (peak band-power / median band-power). */
    data class Estimate(val ts: Long, val bpm: Double, val conf: Double)

    /** Causal (trailing) moving average with window [w] samples; window shrinks at the start rather
     *  than padding, so the first w-1 outputs are a partial mean, not zero-biased. */
    private fun movingAverage(x: DoubleArray, w: Int): DoubleArray {
        if (w <= 1 || x.isEmpty()) return x
        val out = DoubleArray(x.size)
        var sum = 0.0
        val win = ArrayDeque<Double>()
        for (i in x.indices) {
            win.addLast(x[i]); sum += x[i]
            if (win.size > w) sum -= win.removeFirst()
            out[i] = sum / win.size
        }
        return out
    }

    /** High-pass detrend: subtract a [window]-sample causal moving average. */
    private fun highPassDetrend(x: DoubleArray, window: Int): DoubleArray {
        val m = movingAverage(x, window)
        return DoubleArray(x.size) { x[it] - m[it] }
    }

    /** Sliding amplitude envelope (max-min) over a `±window/2`-sample neighbourhood — the RIAV signal. */
    private fun amplitudeEnvelope(x: DoubleArray, window: Int): DoubleArray {
        if (x.isEmpty()) return x
        val half = window / 2
        return DoubleArray(x.size) { i ->
            val lo = maxOf(0, i - half)
            val hi = minOf(x.size, i + half)
            var mn = x[lo]; var mx = x[lo]
            for (j in lo until hi) { if (x[j] < mn) mn = x[j]; if (x[j] > mx) mx = x[j] }
            mx - mn
        }
    }

    /**
     * Direct-DFT power spectrum over [loHz, hiHz] on a DC-removed, uniformly-sampled ([fs] Hz)
     * signal, scanned at a FIXED [RESP_FREQ_STEP_HZ] step rather than bin-quantized to the burst's
     * own length (see that constant's doc for why). Returns the peak's (frequencyHz,
     * power/medianPower), or null when the signal/band is degenerate or the peak falls within
     * [BAND_EDGE_GUARD_HZ] of an edge (see that constant's doc).
     */
    private fun bandPeak(x: DoubleArray, fs: Double, loHz: Double, hiHz: Double): Pair<Double, Double>? {
        val n = x.size
        if (n <= 1 || hiHz <= loHz) return null
        var mean = 0.0
        for (v in x) mean += v
        mean /= n
        val centered = DoubleArray(n) { x[it] - mean }
        val freqs = ArrayList<Double>()
        val powers = ArrayList<Double>()
        var f = loHz
        while (f <= hiHz) {
            val w = -2.0 * PI * f / fs
            var re = 0.0; var im = 0.0
            for (j in 0 until n) {
                val a = w * j
                re += centered[j] * cos(a)
                im += centered[j] * sin(a)
            }
            freqs.add(f)
            powers.add(re * re + im * im)
            f += RESP_FREQ_STEP_HZ
        }
        if (powers.isEmpty()) return null
        var peakIdx = 0
        for (i in powers.indices) if (powers[i] > powers[peakIdx]) peakIdx = i
        val median = powers.sorted()[powers.size / 2]
        if (median <= 0.0) return null
        val peakHz = freqs[peakIdx]
        if (peakHz < loHz + BAND_EDGE_GUARD_HZ || peakHz > hiHz - BAND_EDGE_GUARD_HZ) return null
        return peakHz to (powers[peakIdx] / median)
    }

    /**
     * Estimate (breaths/min, confidence) for one burst's raw samples via the higher-prominence of
     * the RIAV (amplitude envelope) / RIIV (baseline) spectral peak, or null when the burst is too
     * short, neither channel clears the band, or the best peak doesn't clear [MIN_PROMINENCE]
     * (flat/garbage PPG → no fabricated respiratory rate).
     */
    internal fun estimate(samples: List<Int>, fs: Int = SAMPLE_RATE_HZ): Pair<Double, Double>? {
        if (samples.size < MIN_BURST_RECORDS * fs) return null
        val x = DoubleArray(samples.size) { samples[it].toDouble() }
        val riav = highPassDetrend(amplitudeEnvelope(x, ENVELOPE_WINDOW_SAMPLES), DETREND_WINDOW_SAMPLES)
        val riiv = highPassDetrend(movingAverage(x, BASELINE_WINDOW_SAMPLES), DETREND_WINDOW_SAMPLES)
        val fsD = fs.toDouble()
        val a = bandPeak(riav, fsD, RESP_LO_HZ, RESP_HI_HZ)
        val b = bandPeak(riiv, fsD, RESP_LO_HZ, RESP_HI_HZ)
        val best = when {
            a != null && b != null -> if (a.second >= b.second) a else b
            a != null -> a
            b != null -> b
            else -> null
        } ?: return null
        if (best.second < MIN_PROMINENCE) return null
        return (best.first * 60) to best.second
    }

    /**
     * One respiratory-rate estimate per ~40 s v26 burst (a consecutive-second run of records) — NOT
     * per-second like [PpgHr.estimate], since resolving 0.15 Hz needs the whole burst. [Estimate.ts]
     * is the run's FIRST record timestamp. [samples] may be unsorted / contain gaps (mirror of
     * [PpgHr.estimate]'s own grouping).
     */
    fun deriveRespRate(samples: List<PpgHr.Sample>): List<Estimate> {
        if (samples.isEmpty()) return emptyList()
        val secs = LinkedHashMap<Long, ArrayList<Int>>()
        for (s in samples) secs.getOrPut(s.ts) { ArrayList() }.add(s.value)
        val order = secs.keys.sorted()

        val runs = ArrayList<ArrayList<Long>>()
        var cur = arrayListOf(order[0])
        for (i in 1 until order.size) {
            val u = order[i]
            if (u - cur.last() == 1L) cur.add(u) else { runs.add(cur); cur = arrayListOf(u) }
        }
        runs.add(cur)

        val out = ArrayList<Estimate>()
        for (run in runs) {
            if (run.size < MIN_BURST_RECORDS) continue
            val sig = ArrayList<Int>()
            for (t in run) sig.addAll(secs[t]!!)
            estimate(sig)?.let { (bpm, conf) -> out.add(Estimate(ts = run[0], bpm = bpm, conf = conf)) }
        }
        out.sortBy { it.ts }
        return out
    }
}
