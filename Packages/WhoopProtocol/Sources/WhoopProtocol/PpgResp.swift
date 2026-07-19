import Foundation

/// Respiratory rate derived from the WHOOP 5.0 type-47 **v26** optical PPG buffer (issue #103).
///
/// v26 records carry a single-wavelength 24 Hz PPG waveform (see `PpgHr.swift` for the per-second HR
/// sibling estimator on the same buffer). Breathing modulates that waveform two ways — amplitude
/// (RIAV, respiratory-induced amplitude variation) and baseline (RIIV, respiratory-induced intensity
/// variation) — so a spectral peak in the respiratory band (0.15–0.40 Hz = 9–24 breaths/min) recovers
/// the rate without needing a second wavelength (SpO2 is NOT derivable this way — confirmed no second
/// channel exists on v26, see the #103 discussion).
///
/// v26 records arrive in ~40 s bursts: the strap alternates, over the course of a night, between v18
/// per-second summaries and v26 raw-PPG stretches — never both at once — so v26 coverage is a sparse
/// minority of any given night. One estimate is produced per consecutive-second run (a "burst"), not
/// per second like `PpgHr`, since resolving 0.15 Hz needs the whole burst, not a sliding window.
///
/// Validated against 2 real overnight captures against the WHOOP app's own respiratory-rate reading:
/// landed within 2–6% using top-confidence-burst aggregation (see `SleepStager.respRateFromPpg`), vs.
/// the existing R-R/RSA-based estimator's 6–11% low bias on the same nights. That is thin evidence —
/// one person, two nights, a person whose own night-to-night respiratory rate barely moves — so treat
/// `conf` as a real per-burst quality filter, not a reason to trust any single burst in isolation. Also
/// checked whether `conf` tracks time-of-night (stillness/sleep-stage could otherwise bias WHICH part
/// of the night the top-confidence bursts sample from): the two nights showed a weak correlation of
/// opposite sign (-0.22 / +0.22) — no consistent bias, but only 2 data points, so this is worth
/// re-checking as more nights accumulate rather than treated as settled.
///
/// Pure + Foundation-only (mirrors `PpgHr.swift`) so it stays unit-testable and Linux-buildable.
public struct PpgRespSample: Equatable, Codable, Sendable {
    public let ts: Int          // wall-clock unix seconds of the burst's FIRST record
    public let bpm: Double      // breaths/min — NOT rounded (unlike PpgHr's whole-bpm convention;
                                 // resp-rate precision at ~14-15 bpm is the entire point here)
    public let conf: Double     // spectral prominence (peak band-power / median band-power) behind
                                 // `bpm` — >1 when the breathing peak stands out from the band's floor
    public init(ts: Int, bpm: Double, conf: Double) {
        self.ts = ts; self.bpm = bpm; self.conf = conf
    }
}

public enum PpgResp {
    public static let sampleRateHz = 24            // v26 carries 24 samples per 1-second record
    /// Reject a run shorter than this many one-second records (~1.33 s short of a full ~40 s burst) —
    /// too little data to resolve 0.15 Hz cleanly, so no fabricated estimate from a truncated burst.
    public static let minBurstRecords = 32
    public static let respLoHz = 0.15               // 9 breaths/min
    public static let respHiHz = 0.40               // 24 breaths/min
    /// ~1 s sliding window for the RIAV amplitude envelope.
    public static let envelopeWindowSamples = 24
    /// ~1 s causal moving-average window for the RIIV baseline.
    public static let baselineWindowSamples = 24
    /// ~12.5 s causal moving-average window subtracted for the high-pass detrend ahead of the
    /// spectral search — removes sub-~0.08 Hz drift (perfusion/posture, not breathing).
    public static let detrendWindowSamples = 300

    /// Causal (trailing) moving average with window `w` samples; window shrinks at the start rather
    /// than padding, so the first `w-1` outputs are a partial mean, not zero-biased.
    static func movingAverage(_ x: [Double], window w: Int) -> [Double] {
        guard w > 1, !x.isEmpty else { return x }
        var out = [Double](repeating: 0, count: x.count)
        var sum = 0.0
        var win = [Double]()
        win.reserveCapacity(w)
        for (i, v) in x.enumerated() {
            win.append(v); sum += v
            if win.count > w { sum -= win.removeFirst() }
            out[i] = sum / Double(win.count)
        }
        return out
    }

    /// High-pass detrend: subtract a `window`-sample causal moving average.
    static func highPassDetrend(_ x: [Double], window: Int) -> [Double] {
        let m = movingAverage(x, window: window)
        return zip(x, m).map { $0 - $1 }
    }

    /// Sliding amplitude envelope (max−min) over a `±window/2`-sample neighbourhood — the RIAV signal.
    static func amplitudeEnvelope(_ x: [Double], window: Int) -> [Double] {
        guard !x.isEmpty else { return x }
        let half = window / 2
        var out = [Double](repeating: 0, count: x.count)
        for i in x.indices {
            let lo = max(0, i - half)
            let hi = min(x.count, i + half)
            let slice = x[lo..<hi]
            out[i] = (slice.max() ?? 0) - (slice.min() ?? 0)
        }
        return out
    }

    /// Frequency step (Hz) for the band scan below. Does NOT itself add resolution beyond a ~40 s
    /// burst's native ~0.025 Hz (~1.5 breaths/min) DFT bin spacing (fs/n) — it only interpolates the
    /// direct-sum DTFT more finely between those bins, avoiding the ~1.5 bpm quantization steps a
    /// bin-quantized search would report. Matches the step used by the validated Python
    /// proof-of-concept this ports.
    static let respFreqStepHz = 0.004

    /// Reject a peak found within this many Hz of the band edge (`loHz`/`hiHz`) — a real breathing
    /// peak is a local maximum with power falling off on both sides, but low-frequency drift
    /// (perfusion/posture) that leaked past the high-pass detrend has power that only INCREASES
    /// toward 0 Hz, so within a bounded search it deterministically "wins" at whichever edge is
    /// closest to 0 Hz regardless of real physiology. Confirmed on real data (#103 review): without
    /// this guard, 32%/15% of bursts across the two validation nights landed exactly at the band
    /// floor (9 breaths/min) — far more than plausible slow breathing — and top-confidence-burst
    /// aggregation was measurably CLOSER to the WHOOP app's ground truth with the guard than without
    /// it (14.52 vs 14.04 breaths/min against a 14.6 truth). A rejected edge peak returns nil for that
    /// channel (RIAV or RIIV), same honest-no-data handling as everywhere else in this estimator.
    static let bandEdgeGuardHz = 0.02

    /// Direct-DFT power spectrum over `[loHz, hiHz]` on a DC-removed, uniformly-sampled (`fs` Hz)
    /// signal — the same hand-rolled, no-FFT-library technique `StrandAnalytics.SleepStagerV2
    /// .respRegularity` uses one layer up (ported here, not imported, since this package can't depend
    /// on StrandAnalytics), but scanned at a FIXED `respFreqStepHz` step rather than bin-quantized to
    /// the burst's own length (see that constant's doc for why). Returns the peak's
    /// (frequencyHz, power/medianPower), or nil when the signal/band is degenerate or the peak falls
    /// within `bandEdgeGuardHz` of an edge (see that constant's doc).
    static func bandPeak(_ x: [Double], fs: Double, loHz: Double, hiHz: Double) -> (hz: Double, prominence: Double)? {
        let n = x.count
        guard n > 1, hiHz > loHz else { return nil }
        let mean = x.reduce(0, +) / Double(n)
        let centered = x.map { $0 - mean }
        var powers = [(hz: Double, p: Double)]()
        var f = loHz
        while f <= hiHz {
            let w = -2.0 * Double.pi * f / fs
            var re = 0.0, im = 0.0
            for j in 0..<n {
                let a = w * Double(j)
                re += centered[j] * cos(a)
                im += centered[j] * sin(a)
            }
            powers.append((f, re * re + im * im))
            f += respFreqStepHz
        }
        guard let peak = powers.max(by: { $0.p < $1.p }) else { return nil }
        let median = powers.map(\.p).sorted()[powers.count / 2]
        guard median > 0 else { return nil }
        guard peak.hz >= loHz + bandEdgeGuardHz, peak.hz <= hiHz - bandEdgeGuardHz else { return nil }
        return (hz: peak.hz, prominence: peak.p / median)
    }

    /// Reject a burst whose best channel's peak isn't at least this many times the band's median power
    /// — a flat/noisy burst must not fabricate a rate. Mirrors `PpgHr.minConfidence`'s role; the value
    /// is set well below every real-capture burst observed so far (min ~2.0 across 57 real bursts from
    /// 2 nights) while still rejecting a genuinely flat band (prominence 1.0 = no peak at all).
    public static let minProminence = 1.5

    /// Estimate (breaths/min, confidence) for one burst's raw samples via the higher-prominence of the
    /// RIAV (amplitude envelope) / RIIV (baseline) spectral peak, or nil when the burst is too short,
    /// neither channel clears the band, or the best peak doesn't clear `minProminence` (flat/garbage
    /// PPG → no fabricated respiratory rate).
    static func estimate(_ samples: [Int], fs: Int = sampleRateHz) -> (bpm: Double, conf: Double)? {
        guard samples.count >= minBurstRecords * fs else { return nil }
        let x = samples.map(Double.init)
        let riav = highPassDetrend(amplitudeEnvelope(x, window: envelopeWindowSamples),
                                    window: detrendWindowSamples)
        let riiv = highPassDetrend(movingAverage(x, window: baselineWindowSamples),
                                    window: detrendWindowSamples)
        let fsD = Double(fs)
        let a = bandPeak(riav, fs: fsD, loHz: respLoHz, hiHz: respHiHz)
        let b = bandPeak(riiv, fs: fsD, loHz: respLoHz, hiHz: respHiHz)
        let best: (hz: Double, prominence: Double)?
        switch (a, b) {
        case let (a?, b?): best = a.prominence >= b.prominence ? a : b
        case let (a?, nil): best = a
        case let (nil, b?): best = b
        case (nil, nil): best = nil
        }
        guard let picked = best, picked.prominence >= minProminence else { return nil }
        return (bpm: picked.hz * 60, conf: picked.prominence)
    }

    /// One respiratory-rate estimate per ~40 s v26 burst (a consecutive-second run of records) — NOT
    /// per-second like `PpgHr.derivePpgHr`, since resolving 0.15 Hz needs the whole burst. `ts` on the
    /// returned sample is the run's FIRST record timestamp. Records may be unsorted / contain gaps.
    public static func deriveRespRate(records: [(ts: Int, samples: [Int])],
                                      fs: Int = sampleRateHz) -> [PpgRespSample] {
        guard !records.isEmpty else { return [] }
        // One waveform per second (last write wins on a duplicate ts).
        var secs = [Int: [Int]]()
        for r in records { secs[r.ts] = r.samples }
        let order = secs.keys.sorted()
        // Split into consecutive-second runs.
        var runs = [[Int]]()
        var cur = [order[0]]
        for u in order.dropFirst() {
            if u - cur.last! == 1 { cur.append(u) }
            else { runs.append(cur); cur = [u] }
        }
        runs.append(cur)

        var out = [PpgRespSample]()
        for run in runs where run.count >= minBurstRecords {
            var sig = [Int]()
            for t in run { sig.append(contentsOf: secs[t]!) }
            if let est = estimate(sig, fs: fs) {
                out.append(PpgRespSample(ts: run[0], bpm: est.bpm, conf: est.conf))
            }
        }
        out.sort { $0.ts < $1.ts }
        return out
    }
}
