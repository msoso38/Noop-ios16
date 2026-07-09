import Foundation

/// Build a NOOP sleep session from the Oura ring's OWN anchored sleep-phase timeline
/// (`OURA_SLEEP_PHASE` events, Tier-A 2-bit codes; OURA_PROTOCOL.md §6.12).
///
/// Why this exists (OURA_PROTOCOL.md §6.12.1): the polished 4-stage hypnogram the Oura APP shows is
/// produced by SleepNet, an encrypted, cloud-key-gated PyTorch model on the phone — it is NOT on the BLE
/// wire and NOOP neither can nor does reproduce it. What DOES cross BLE is the ring's own coarse
/// per-epoch phase classification (awake/light/deep/REM). NOOP builds its OWN session from that, the
/// same honest-data stance as every other NOOP metric.
///
/// This ALSO bridges an architectural gap: `AnalyticsEngine.analyzeDay` derives sleep from
/// `SleepStager.detectSleep`, which is **gravity-driven** — and an Oura ring streams no accelerometer, so
/// the detector returns nothing (`grav.count < 2 → []`). The sessions built here are injected into
/// `analyzeDay` as `providedSleepSessions`, taking the place the gravity detector fills for WHOOP, so the
/// ring's night flows through the SAME funnels (dailyMetric sleep totals, the skin-temp window, rest).
///
/// Pure and platform-neutral: input is `(ts, stage)` pairs (wall-clock unix seconds + the ring's 2-bit
/// code), so this file needs no OuraProtocol dependency and is unit-tested in the fast package loop.
public enum OuraSleepSessionBuilder {

    /// The ring's 2-bit sleep-phase code (OURA_PROTOCOL.md §6.12: `0=awake, 1=light, 2=deep, 3=REM`)
    /// mapped to the `StageSegment.stage` string the rest of analytics uses. Returns nil for an
    /// unknown code (a corrupt/misframed value), so it is dropped rather than guessed (honest-data).
    static func stageName(forPhaseCode code: Int) -> String? {
        switch code {
        case 0: return "wake"
        case 1: return "light"
        case 2: return "deep"
        case 3: return "rem"
        default: return nil
        }
    }

    /// The stage-UNKNOWN label for a `check_sleep` window: the ring gives a real bedtime→wake span but no
    /// stage breakdown. `hypnogramMetrics` counts it as sleep TIME (TST) but NOT toward deep/REM/light, so
    /// the day's total-sleep is honest while the stage split stays blank (never fabricated).
    static let unknownStage = "asleep"

    /// Build ONE session from the ring's OWN `check_sleep` window (OURA_PROTOCOL.md §6.15) — bedtime→wake
    /// unix seconds. This is the honest sleep-DURATION source: the ring's coarse phase events (§6.12) are
    /// sparse connection-time bursts that under-count, whereas `check_sleep s:/e:` is the firmware's own
    /// sleep-period decision (validated on device: 7 h 56 m vs a wearer's real 7 h 52 m).
    ///
    /// The whole window is one stage-unknown `asleep` segment: no HR streams overnight (the ring isn't
    /// connected), so efficiency is not measurable and we do not invent one — the window IS the sleep
    /// period, so efficiency is 1.0 and stages are left unknown. `restingHR`/`avgHRV` are nil (enriched by
    /// the engine from any night HR/RR it does have). Returns nil for a non-positive span.
    public static func session(fromWindowStart start: Int, end: Int) -> SleepSession? {
        guard end > start else { return nil }
        let seg = StageSegment(start: start, end: end, stage: unknownStage)
        return SleepSession(start: start, end: end, efficiency: 1.0, stages: [seg],
                            restingHR: nil, avgHRV: nil)
    }

    /// Build sleep session(s) from the ring's anchored phase timeline.
    ///
    /// Each phase event marks a stage that HOLDS until the next event, so consecutive events
    /// `[tsᵢ, tsᵢ₊₁)` form one `StageSegment` with `stageᵢ`; the session spans `first → last` event.
    /// Adjacent same-stage segments are merged for a clean hypnogram. Efficiency = asleep / in-bed
    /// (asleep = non-wake duration). A large inter-event gap splits into separate sessions (a nap vs the
    /// overnight), and a session shorter than `minSessionMinutes` or with no asleep time is dropped as
    /// noise. `restingHR`/`avgHRV` are left nil here — they are enriched by the caller/engine from the
    /// night's HR/RR streams, not fabricated from the phase codes.
    ///
    /// - Parameters:
    ///   - phases: `(ts, stage)` pairs — wall-clock unix seconds and the ring's 2-bit phase code. Need
    ///     not be pre-sorted; duplicate timestamps keep the first-seen stage.
    ///   - minSessionMinutes: shortest span kept (default 60 — drops stray fragments; a real nap the ring
    ///     staged still clears this, an isolated blip does not).
    ///   - splitGapMinutes: an inter-event gap longer than this starts a new session (default 120).
    public static func sessions(fromPhases phases: [(ts: Int, stage: Int)],
                                minSessionMinutes: Int = 60,
                                splitGapMinutes: Int = 120) -> [SleepSession] {
        // Sort by time; collapse duplicate timestamps (keep first) so a doubled event can't make a
        // zero-length segment.
        let sorted = phases.sorted { $0.ts < $1.ts }
        var events: [(ts: Int, stage: Int)] = []
        for e in sorted where e.ts != events.last?.ts { events.append(e) }
        guard events.count >= 2 else { return [] }

        // Split into contiguous runs on a large gap (nap vs overnight).
        let splitGap = splitGapMinutes * 60
        var runs: [[(ts: Int, stage: Int)]] = []
        var current: [(ts: Int, stage: Int)] = [events[0]]
        for e in events.dropFirst() {
            if e.ts - current[current.count - 1].ts > splitGap {
                runs.append(current)
                current = [e]
            } else {
                current.append(e)
            }
        }
        runs.append(current)

        let minSpan = minSessionMinutes * 60
        var out: [SleepSession] = []
        for run in runs where run.count >= 2 {
            let start = run[0].ts
            let end = run[run.count - 1].ts
            guard end - start >= minSpan else { continue }

            // One segment per [tsᵢ, tsᵢ₊₁); drop segments whose code is unknown (never guess a stage).
            var segments: [StageSegment] = []
            for i in 0..<(run.count - 1) {
                guard let name = stageName(forPhaseCode: run[i].stage) else { continue }
                let seg = StageSegment(start: run[i].ts, end: run[i + 1].ts, stage: name)
                // Merge with the previous segment when the stage is identical and they abut.
                if var last = segments.last, last.stage == seg.stage, last.end == seg.start {
                    last.end = seg.end
                    segments[segments.count - 1] = last
                } else {
                    segments.append(seg)
                }
            }
            guard !segments.isEmpty else { continue }

            var asleepSeconds = 0
            for seg in segments where seg.stage != "wake" {
                asleepSeconds += seg.end - seg.start
            }
            guard asleepSeconds > 0 else { continue }   // an all-wake run is not a sleep session
            let inBed = end - start
            let efficiency = inBed > 0 ? min(1.0, Double(asleepSeconds) / Double(inBed)) : 0

            out.append(SleepSession(start: start, end: end, efficiency: efficiency,
                                    stages: segments, restingHR: nil, avgHRV: nil))
        }
        return out
    }
}
