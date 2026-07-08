import Foundation

// MARK: - Phase-1 Oura activity estimate (Tier-B INVESTIGATION — never persisted, never scored)

/// PHASE 1 of promoting the Oura `0x50 activity_info` (MET) stream out of Tier B. This pure roll-up
/// folds decoded MET samples into a per-local-day estimate that `OuraLiveSource` LOGS (never stores)
/// so the numbers can be eyeballed against the WHOOP band's own steps / active-kcal on the following
/// day (the user wears both). It exists ONLY to decide whether 0x50 is worth promoting to Tier A; it
/// never touches `OuraStreamMapping` / `Streams` / scoring, and it never mints an honest step count
/// (MET ≠ steps — the `stepProxy` below is explicitly a labelled proxy, not a stored value).
///
/// HONEST UNCERTAINTY: the MET sample *spacing* is undocumented (docs/OURA_PROTOCOL.md §6.13 marks the
/// layout "UNVERIFIED - partial"). So the cadence-independent facts (sample counts per MET band, mean /
/// max MET) are reported as-is, while every minute / kcal / step figure is derived under an EXPLICIT
/// `assumedIntervalSec` and labelled as such. The WHOOP cross-check is precisely what calibrates that
/// unknown: if Oura active-minutes at the assumed interval read ~2× WHOOP, the true spacing is ~2×.
public struct OuraActivitySample: Equatable, Sendable {
    /// Wall-clock unix seconds. In Phase 1 this is the live *arrival* time (history-backlog anchoring is
    /// future work, and blocked today by the history-fetch cursor regression — see CLAUDE.md).
    public let ts: Int
    public let met: Double
    public init(ts: Int, met: Double) { self.ts = ts; self.met = met }
}

/// The per-day roll-up. Cadence-independent fields (`*Samples`, `meanMet`, `maxMet`) carry no interval
/// assumption; the `active*` / `est*` / `stepProxy` fields do (see `assumedIntervalSec`).
public struct OuraActivityEstimate: Equatable, Sendable {
    public let day: String
    public let sampleCount: Int
    public let firstTs: Int?
    public let lastTs: Int?
    public let meanMet: Double
    public let maxMet: Double

    // Cadence-INDEPENDENT: how many MET samples fell in each standard activity band.
    public let sedentarySamples: Int   // met < 1.5
    public let lightSamples: Int       // 1.5 <= met < 3
    public let moderateSamples: Int    // 3   <= met < 6
    public let vigorousSamples: Int    // met >= 6

    // Cadence-DEPENDENT: everything below scales linearly with `assumedIntervalSec`.
    public let assumedIntervalSec: Double
    public let activeMinutes: Double    // (light+moderate+vigorous) samples * interval / 60
    public let estActiveKcal: Double    // Σ max(met-1,0) * bodyweightKg * interval/3600 (net-of-resting)
    public let stepProxy: Int           // PROXY ONLY: activeMinutes * stepsPerActiveMin (never stored)

    /// A single-line, grep-friendly log string. `final == true` means the local day rolled over (a
    /// complete day for whatever coverage we got); `false` is a running "so far" snapshot.
    public func logLine(final: Bool) -> String {
        let tag = final ? "FINAL" : "so far"
        func f(_ v: Double, _ p: Int = 1) -> String { String(format: "%.\(p)f", v) }
        return "activity estimate \(tag) day=\(day) samples=\(sampleCount) "
            + "meanMET=\(f(meanMet, 2)) maxMET=\(f(maxMet, 2)) "
            + "bands[sed/light/mod/vig]=\(sedentarySamples)/\(lightSamples)/\(moderateSamples)/\(vigorousSamples) "
            + "activeMin≈\(f(activeMinutes)) estKcal≈\(f(estActiveKcal, 0)) stepProxy≈\(stepProxy) "
            + "[assumed \(f(assumedIntervalSec, 0))s/sample — PROXY, not stored]"
    }
}

/// Pure, deterministic summariser. No I/O, no clock, no persistence — fully unit-testable.
public enum OuraActivityEstimator {
    // Standard compendium-of-physical-activities MET band edges.
    public static let lightFloor = 1.5      // below this = sedentary
    public static let moderateFloor = 3.0
    public static let vigorousFloor = 6.0

    /// Fold MET samples for a single local `day` into an estimate. `bodyweightKg` feeds the (secondary)
    /// active-kcal figure; `assumedIntervalSec` is the UNVERIFIED per-sample spacing that all time-based
    /// outputs scale with; `stepsPerActiveMin` is the labelled step-proxy cadence (never an honest count).
    public static func summarize(_ samples: [OuraActivitySample],
                                 day: String,
                                 bodyweightKg: Double,
                                 assumedIntervalSec: Double,
                                 stepsPerActiveMin: Double) -> OuraActivityEstimate {
        guard !samples.isEmpty else {
            return OuraActivityEstimate(day: day, sampleCount: 0, firstTs: nil, lastTs: nil,
                                        meanMet: 0, maxMet: 0,
                                        sedentarySamples: 0, lightSamples: 0, moderateSamples: 0,
                                        vigorousSamples: 0, assumedIntervalSec: assumedIntervalSec,
                                        activeMinutes: 0, estActiveKcal: 0, stepProxy: 0)
        }
        var sum = 0.0, maxMet = 0.0
        var sed = 0, light = 0, mod = 0, vig = 0
        var activeKcal = 0.0
        let hours = assumedIntervalSec / 3600.0
        for s in samples {
            sum += s.met
            if s.met > maxMet { maxMet = s.met }
            switch s.met {
            case ..<lightFloor:        sed += 1
            case ..<moderateFloor:     light += 1
            case ..<vigorousFloor:     mod += 1
            default:                   vig += 1
            }
            // Net-of-resting active energy: only MET above 1.0 (resting) burns "active" kcal.
            activeKcal += max(s.met - 1.0, 0.0) * bodyweightKg * hours
        }
        let activeSamples = light + mod + vig
        let activeMinutes = Double(activeSamples) * assumedIntervalSec / 60.0
        let stepProxy = Int((activeMinutes * stepsPerActiveMin).rounded())
        return OuraActivityEstimate(
            day: day,
            sampleCount: samples.count,
            firstTs: samples.map(\.ts).min(),
            lastTs: samples.map(\.ts).max(),
            meanMet: sum / Double(samples.count),
            maxMet: maxMet,
            sedentarySamples: sed, lightSamples: light, moderateSamples: mod, vigorousSamples: vig,
            assumedIntervalSec: assumedIntervalSec,
            activeMinutes: activeMinutes,
            estActiveKcal: activeKcal,
            stepProxy: stepProxy)
    }
}
