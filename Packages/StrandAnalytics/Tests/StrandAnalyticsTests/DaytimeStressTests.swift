import XCTest
@testable import StrandAnalytics
import WhoopProtocol

final class DaytimeStressTests: XCTestCase {

    /// Fill one local hour-of-day with `n` 1 Hz HR samples at `bpm` (UTC, tz offset 0).
    private func hourHR(_ hour: Int, bpm: Int, n: Int = DaytimeStress.minHourHRSamples) -> [HRSample] {
        let base = hour * 3_600
        return (0..<n).map { HRSample(ts: base + $0, bpm: bpm) }
    }

    func testEmptyWhenNoHR() {
        XCTAssertEqual(DaytimeStress.analyze(hr: [], rr: []), .empty)
    }

    func testHourBelowGateIsUnscored() {
        // One waking hour with too few HR samples → present but unscored (honest gap).
        let hr = hourHR(9, bpm: 70, n: DaytimeStress.minHourHRSamples - 1)
        let r = DaytimeStress.analyze(hr: hr, rr: [])
        XCTAssertTrue(r.scored.isEmpty, "an under-gate hour must not be scored")
    }

    func testScoresMapOntoZeroToThree() {
        // Three calm hours + one tense hour (high HR). All scored values stay within 0…3.
        var hr: [HRSample] = []
        hr += hourHR(8, bpm: 62)
        hr += hourHR(9, bpm: 60)
        hr += hourHR(10, bpm: 61)
        hr += hourHR(11, bpm: 95)   // the spike
        let r = DaytimeStress.analyze(hr: hr, rr: [])
        XCTAssertFalse(r.scored.isEmpty)
        for p in r.scored {
            let lvl = p.level!
            XCTAssertGreaterThanOrEqual(lvl, 0)
            XCTAssertLessThanOrEqual(lvl, 3)
        }
        // The high-HR hour must be the day's peak and read above the calm hours.
        XCTAssertEqual(r.peak?.hour, 11)
        let calm = r.scored.first { $0.hour == 9 }!.level!
        let tense = r.scored.first { $0.hour == 11 }!.level!
        XCTAssertGreaterThan(tense, calm)
    }

    func testNonWakingHoursAreExcluded() {
        // A 3 am hour (outside 06:00–22:00) is never placed on the waking timeline.
        let hr = hourHR(3, bpm: 80) + hourHR(9, bpm: 60)
        let r = DaytimeStress.analyze(hr: hr, rr: [])
        XCTAssertFalse(r.hours.contains { $0.hour == 3 })
        XCTAssertTrue(r.hours.contains { $0.hour == 9 })
    }

    func testSustainedHighFlagsAfterThreeConsecutiveHighHours() {
        // A calm morning, then three increasingly tense afternoon hours that finish HIGH.
        var hr: [HRSample] = []
        for h in [8, 9, 10] { hr += hourHR(h, bpm: 58) }   // calm baseline hours
        hr += hourHR(13, bpm: 120)
        hr += hourHR(14, bpm: 125)
        hr += hourHR(15, bpm: 130)
        let r = DaytimeStress.analyze(hr: hr, rr: [])
        XCTAssertTrue(r.sustainedHigh, "three trailing HIGH hours should flag sustained stress")
        XCTAssertGreaterThanOrEqual(r.sustainedRun, DaytimeStress.sustainedHours)
    }

    func testFlatDayDoesNotFlagSustained() {
        // Every hour at the same HR → no hour is meaningfully elevated, no flag.
        var hr: [HRSample] = []
        for h in 8...16 { hr += hourHR(h, bpm: 64) }
        let r = DaytimeStress.analyze(hr: hr, rr: [])
        XCTAssertFalse(r.sustainedHigh)
        // A flat day sits around the baseline (≈1.5), not pinned high.
        if let mean = r.dayMean { XCTAssertLessThan(mean, DaytimeStress.highBandFloor) }
    }

    func testSleepHoursInTheWindowDoNotShiftTheWakingTimeline() {
        // Regression: the calm reference is built from the WAKING hours that are actually
        // scored, not the whole 24 h. The analysis window always starts at local midnight, so
        // the current day routinely carries several hours of sleep — the calmest, lowest-HR
        // stretch of the day. If those night hours leak into the reference they drag the "calm"
        // anchor far below every waking hour, inflating an ordinary calm day into sustained
        // high stress (tripping the passive Breathe nudge). So adding calm sleep hours to the
        // input must NOT change the waking timeline.
        let waking: [HRSample] = zip(6...17, [62, 64, 63, 65, 64, 63, 62, 64, 66, 63, 64, 65])
            .flatMap { hourHR($0.0, bpm: $0.1) }
        let sleep: [HRSample] = zip(0...5, [50, 51, 52, 51, 50, 53])
            .flatMap { hourHR($0.0, bpm: $0.1) }

        let wakingOnly = DaytimeStress.analyze(hr: waking, rr: [])
        let withSleep = DaytimeStress.analyze(hr: sleep + waking, rr: [])

        XCTAssertEqual(withSleep.sustainedHigh, wakingOnly.sustainedHigh,
            "sleep hours sharing the window must not change the sustained-high verdict")
        for h in 6...17 {
            guard let withLvl = withSleep.scored.first(where: { $0.hour == h })?.level,
                  let withoutLvl = wakingOnly.scored.first(where: { $0.hour == h })?.level else {
                XCTFail("waking hour \(h) should be scored in both runs"); continue
            }
            XCTAssertEqual(withLvl, withoutLvl, accuracy: 1e-9,
                "the night's sleep hours leaked into the daytime reference and shifted waking hour \(h)")
        }
        // The plain sanity check the bug violated: an ordinary calm day is not "sustained high".
        XCTAssertFalse(withSleep.sustainedHigh,
            "a calm desk day must not read as sustained high stress")
    }

    func testTimezoneOffsetShiftsWakingWindow() {
        // ts at UTC hour 4 with a +3 h offset lands at local hour 7 → inside waking hours.
        let hr = hourHR(4, bpm: 60)
        let r = DaytimeStress.analyze(hr: hr, rr: [], tzOffsetSeconds: 3 * 3_600)
        XCTAssertTrue(r.hours.contains { $0.hour == 7 })
    }

    func testRMSSDLowersStressDirectionMatchesDailyScore() {
        // Same HR across hours; the hour with the LOWEST HRV (RMSSD) should read more
        // stressed — the same directionality as the daily score (HRV down = stress).
        var hr: [HRSample] = []
        var rr: [RRInterval] = []
        for h in [8, 9, 10, 11] { hr += hourHR(h, bpm: 65) }
        // High-variability (relaxed) hours vs one low-variability (tense) hour.
        rr += hourRRVariable(8, rrMs: 900, jitter: 40)
        rr += hourRRVariable(9, rrMs: 900, jitter: 40)
        rr += hourRRVariable(10, rrMs: 900, jitter: 40)
        rr += hourRRVariable(11, rrMs: 900, jitter: 2)   // suppressed HRV
        let r = DaytimeStress.analyze(hr: hr, rr: rr)
        let relaxed = r.scored.first { $0.hour == 9 }!.level!
        let tense = r.scored.first { $0.hour == 11 }!.level!
        XCTAssertGreaterThan(tense, relaxed)
    }

    /// R-R for one hour with a controllable beat-to-beat jitter (drives RMSSD).
    private func hourRRVariable(_ hour: Int, rrMs: Int, jitter: Int, n: Int = 60) -> [RRInterval] {
        let base = hour * 3_600
        return (0..<n).map { RRInterval(ts: base + $0 * 50, rrMs: rrMs + ($0 % 2 == 0 ? jitter : -jitter)) }
    }

    // MARK: - Additivity: the `mode` parameter is opt-in, day-relative stays the default

    func testDayRelativeDefaultIsByteIdenticalToExplicitMode() {
        // The additive `mode` parameter defaults to `.dayRelative`. Confirms the implicit call
        // (every pre-existing call site, unmodified) and the explicit `.dayRelative` case
        // produce a BYTE-IDENTICAL `Result` — every field, not just the pre-existing ones —
        // proving the new mode is purely additive and never a silent behaviour change.
        var hr: [HRSample] = []
        for h in [8, 9, 10] { hr += hourHR(h, bpm: 58) }
        hr += hourHR(13, bpm: 120)
        hr += hourHR(14, bpm: 125)
        hr += hourHR(15, bpm: 130)
        var rr: [RRInterval] = []
        rr += hourRRVariable(9, rrMs: 900, jitter: 40)
        rr += hourRRVariable(14, rrMs: 900, jitter: 5)

        let implicit = DaytimeStress.analyze(hr: hr, rr: rr, tzOffsetSeconds: 3_600)
        let explicit = DaytimeStress.analyze(hr: hr, rr: rr, tzOffsetSeconds: 3_600, mode: .dayRelative)
        XCTAssertEqual(implicit, explicit,
            "omitting `mode` must be byte-identical to passing `.dayRelative` explicitly")
    }

    func testHighStressMinutesCountsAllHighBandHoursNotJustTheTrailingRun() {
        // An isolated morning spike, then a calm run ending the day: sustainedHigh only cares
        // about the TRAILING run (and must be false here, since the day ends calm), but
        // highStressMinutes is a day-wide tally and must still count the earlier spike hour —
        // proving it is computed independently, not derived from sustainedRun.
        var hr: [HRSample] = []
        hr += hourHR(7, bpm: 130)   // isolated high spike
        hr += hourHR(8, bpm: 60)
        hr += hourHR(9, bpm: 60)
        hr += hourHR(10, bpm: 60)
        hr += hourHR(11, bpm: 60)   // trailing hour is calm -> NOT sustained
        let r = DaytimeStress.analyze(hr: hr, rr: [])

        XCTAssertFalse(r.sustainedHigh, "the trailing hour is calm, so sustained-high must not fire")
        let expectedHighHours = r.scored.filter { $0.level! >= DaytimeStress.highBandFloor }.count
        XCTAssertGreaterThan(expectedHighHours, 0, "the isolated morning spike should read as high band")
        XCTAssertEqual(r.highStressMinutes, expectedHighHours * (DaytimeStress.bucketSeconds / 60))
        XCTAssertFalse(r.hrOnlyFallback, "day-relative mode never sets the baseline-relative fallback flag")
    }

    // MARK: - Baseline-relative mode (Oura-style, vs a PERSONAL rolling baseline)
    //
    // Fixtures below use a 65 bpm personal HR baseline (matching the ~65 bpm pooled
    // 10th-percentile figure from the validated 26-day Oura-reference correlation — see
    // `DaytimeStress.baselineRelativeHighMarginBPM`) and elevations measured from it in terms of
    // that validated ~15 bpm margin, so the expected band crossings are exact, not approximate.

    func testMarginToSigmaLandsExactlyOnBand() {
        // The validated 15 bpm margin over baseline must land EXACTLY on highBandFloor (2.0) on
        // the shared squash curve — the core identity `.baselineRelative` scoring relies on.
        let sd = DaytimeStress.marginToSigma(marginBPM: DaytimeStress.baselineRelativeHighMarginBPM,
                                             atBand: DaytimeStress.highBandFloor)
        XCTAssertEqual(DaytimeStress.squash(DaytimeStress.baselineRelativeHighMarginBPM / sd),
                      DaytimeStress.highBandFloor, accuracy: 1e-9)
    }

    func testBaselineRelativeModeRecoversMultipleInjectedElevations() {
        // Personal daytime-HR baseline: 20 constant "days" at 65 bpm converges the EWMA center
        // to exactly 65 (spread is folded but NOT used for the HR high-band threshold — see
        // baselineRelativeHighMarginBPM).
        let hrBaseline = Baselines.foldHistory(Array(repeating: 65.0, count: 20), cfg: Baselines.daytimeHRCfg)
        XCTAssertEqual(hrBaseline.baseline, 65.0, accuracy: 1e-6)

        // FOUR distinct injected HR elevations across the SAME day's waking hours — the repo's
        // derived-signal rule (CLAUDE.md "validate against the artifact, not one match") requires
        // recovering MULTIPLE injected values, not a single high-vs-low pair. 65 (at baseline),
        // 72 (+7, mild), 80 (+15, exactly the validated margin), 95 (+30, well past it).
        let levels: [(hour: Int, bpm: Int)] = [(8, 65), (10, 72), (13, 80), (16, 95)]
        var hr: [HRSample] = []
        for (h, bpm) in levels { hr += hourHR(h, bpm: bpm) }

        let r = DaytimeStress.analyze(hr: hr, rr: [], mode: .baselineRelative(hr: hrBaseline, rmssd: nil))
        let scores = levels.map { pair in r.scored.first { $0.hour == pair.hour }!.level! }

        // Strictly increasing with the injected elevation — all four levels recovered, in order.
        for i in 1..<scores.count {
            XCTAssertGreaterThan(scores[i], scores[i - 1],
                "hour \(levels[i].hour) (\(levels[i].bpm) bpm) should score higher than hour \(levels[i - 1].hour) (\(levels[i - 1].bpm) bpm)")
        }
        // The at-baseline hour reads at the 1.5 midpoint; +15 bpm (the validated margin) lands
        // exactly on highBandFloor; the most-elevated hour clears well past it.
        XCTAssertEqual(scores[0], 1.5, accuracy: 0.05)
        XCTAssertEqual(scores[2], DaytimeStress.highBandFloor, accuracy: 0.01,
            "the validated +15 bpm margin should land exactly on highBandFloor")
        XCTAssertGreaterThan(scores.last!, DaytimeStress.highBandFloor)
        XCTAssertTrue(r.hrOnlyFallback, "rmssd: nil must flag the HR-only fallback")
    }

    func testBaselineRelativeCalmDayAtPersonalBaselineReadsLowNotHigh() {
        let hrBaseline = Baselines.foldHistory(Array(repeating: 65.0, count: 20), cfg: Baselines.daytimeHRCfg)
        var hr: [HRSample] = []
        for h in [8, 10, 13, 16] { hr += hourHR(h, bpm: 65) }   // every hour sits exactly at baseline
        let r = DaytimeStress.analyze(hr: hr, rr: [], mode: .baselineRelative(hr: hrBaseline, rmssd: nil))

        for p in r.scored {
            XCTAssertEqual(p.level!, 1.5, accuracy: 0.05,
                "a day flat at the personal baseline should read ~1.5, not elevated")
        }
        XCTAssertEqual(r.highStressMinutes, 0)
        XCTAssertFalse(r.sustainedHigh)
    }

    func testBaselineRelativeElevatedDayProducesHighStressMinutes() {
        let hrBaseline = Baselines.foldHistory(Array(repeating: 65.0, count: 20), cfg: Baselines.daytimeHRCfg)
        var hr: [HRSample] = []
        for h in 8...16 { hr += hourHR(h, bpm: 95) }   // +30 bpm — twice the validated high-band margin
        let r = DaytimeStress.analyze(hr: hr, rr: [], mode: .baselineRelative(hr: hrBaseline, rmssd: nil))

        XCTAssertGreaterThan(r.highStressMinutes, 0)
        XCTAssertEqual(r.highStressMinutes,
                      r.scored.filter { $0.level! >= DaytimeStress.highBandFloor }.count * (DaytimeStress.bucketSeconds / 60))
        for p in r.scored { XCTAssertGreaterThanOrEqual(p.level!, DaytimeStress.highBandFloor) }
    }

    func testBaselineRelativeNilRMSSDFallsBackToHROnlyAndFlagsDegraded() {
        // An imported, Oura-era day: no personal RMSSD baseline exists yet (rmssd: nil) and no
        // R-R stream is available either. The read must still complete honestly, never crash.
        let hrBaseline = Baselines.foldHistory(Array(repeating: 65.0, count: 20), cfg: Baselines.daytimeHRCfg)
        var hr: [HRSample] = []
        for h in [9, 14] { hr += hourHR(h, bpm: 80) }   // right at the validated +15 bpm margin
        let r = DaytimeStress.analyze(hr: hr, rr: [], mode: .baselineRelative(hr: hrBaseline, rmssd: nil))

        XCTAssertTrue(r.hrOnlyFallback)
        XCTAssertFalse(r.scored.isEmpty, "HR-only scoring must still produce a timeline")
        for p in r.scored { XCTAssertNotNil(p.level) }
    }

    func testBaselineRelativeUsesRMSSDBaselineWhenAvailable() {
        // Personal baselines: HR steady at 65 bpm, RMSSD steady at 40 ms (both spread-floored).
        let hrBaseline = Baselines.foldHistory(Array(repeating: 65.0, count: 20), cfg: Baselines.daytimeHRCfg)
        let rmssdBaseline = Baselines.foldHistory(Array(repeating: 40.0, count: 20), cfg: Baselines.daytimeRMSSDCfg)

        var hr: [HRSample] = []
        var rr: [RRInterval] = []
        for h in [9, 14] { hr += hourHR(h, bpm: 65) }        // HR AT baseline in both hours — isolates RMSSD
        rr += hourRRVariable(9, rrMs: 900, jitter: 40)        // normal variability
        rr += hourRRVariable(14, rrMs: 900, jitter: 2)        // suppressed HRV -> more stressed

        let r = DaytimeStress.analyze(hr: hr, rr: rr,
                                      mode: .baselineRelative(hr: hrBaseline, rmssd: rmssdBaseline))
        XCTAssertFalse(r.hrOnlyFallback, "an RMSSD baseline was supplied — no fallback")
        let normal = r.scored.first { $0.hour == 9 }!.level!
        let suppressed = r.scored.first { $0.hour == 14 }!.level!
        XCTAssertGreaterThan(suppressed, normal,
            "suppressed RMSSD vs. the personal baseline should read MORE stressed than normal variability")
    }

    // MARK: - Motion gate (MOVE E): an ambulatory hour is EXERTION — masked, not scored as stress

    /// Fill one local hour with gravity whose consecutive L2 delta ≈ `delta` on every step (x
    /// alternates 0↔delta), so the derived activity intensity ≈ `delta` across the hour. delta >
    /// `WorkoutDetector.motionThreshold` (0.20) ⇒ AMBULATORY; a small delta (0.02, desk) ⇒ still.
    private func hourGravity(_ hour: Int, delta: Double, n: Int = 180) -> [GravitySample] {
        let base = hour * 3_600
        let step = max(1, 3_600 / n)
        return (0..<n).map { i in
            GravitySample(ts: base + i * step, x: (i % 2 == 0) ? 0.0 : delta, y: 0, z: 0)
        }
    }

    func testAmbulatoryHourIsMaskedNotScored() {
        // Three calm desk hours + a high-HR hour that was spent WALKING (high gravity). The walking
        // hour's elevated HR is EXERTION, so it's masked out of the score — not read as stress.
        var hr: [HRSample] = []
        for h in [8, 9, 10] { hr += hourHR(h, bpm: 60) }
        hr += hourHR(11, bpm: 110)                                    // elevated — but from walking
        var grav: [GravitySample] = []
        for h in [8, 9, 10] { grav += hourGravity(h, delta: 0.02) }   // desk-still
        grav += hourGravity(11, delta: 0.30)                          // walking (> 0.20 floor)

        let r = DaytimeStress.analyze(hr: hr, rr: [], gravity: grav)
        let h11 = r.hours.first { $0.hour == 11 }!
        XCTAssertNil(h11.level, "an ambulatory hour must not be scored")
        XCTAssertTrue(h11.maskedForActivity, "the walking hour must be flagged masked-for-activity")
        XCTAssertFalse(r.scored.contains { $0.hour == 11 }, "masked hour is excluded from the scored set")
        XCTAssertEqual(r.activityMaskedHours, 1)
    }

    func testSameElevatedHRIsScoredWhenStill() {
        // CONTROL for the test above: identical HR, but the elevated hour is STILL (low gravity). The
        // elevation is no longer explained by motion, so it IS scored as stress — proving the mask is
        // driven by MOTION, not by the HR value.
        var hr: [HRSample] = []
        for h in [8, 9, 10] { hr += hourHR(h, bpm: 60) }
        hr += hourHR(11, bpm: 110)
        var grav: [GravitySample] = []
        for h in [8, 9, 10, 11] { grav += hourGravity(h, delta: 0.02) }   // ALL still

        let r = DaytimeStress.analyze(hr: hr, rr: [], gravity: grav)
        let h11 = r.hours.first { $0.hour == 11 }!
        XCTAssertNotNil(h11.level, "a still hour with elevated HR must be scored, not masked")
        XCTAssertFalse(h11.maskedForActivity)
        XCTAssertEqual(r.activityMaskedHours, 0)
        XCTAssertEqual(r.peak?.hour, 11, "the still elevated hour is the day's stress peak")
    }

    func testNoGravityIsByteIdenticalToOmittingIt() {
        // The motion gate is purely additive: no gravity (or an empty array) must produce a
        // byte-identical Result to the pre-motion call — nothing is masked without a motion signal.
        var hr: [HRSample] = []
        for h in [8, 9, 10] { hr += hourHR(h, bpm: 60) }
        hr += hourHR(11, bpm: 110)
        let withoutArg = DaytimeStress.analyze(hr: hr, rr: [])
        let emptyGrav = DaytimeStress.analyze(hr: hr, rr: [], gravity: [])
        XCTAssertEqual(withoutArg, emptyGrav)
        XCTAssertEqual(emptyGrav.activityMaskedHours, 0)
        XCTAssertTrue(emptyGrav.scored.contains { $0.hour == 11 },
            "with no gravity the elevated hour scores as before (the pre-motion behaviour)")
    }

    func testPostExerciseShadowMasksTheNextStillButElevatedHour() {
        // A walking hour (11), then a STILL hour (12) whose HR is still elevated (post-exercise
        // recovery) → 12 is shadow-masked even though it wasn't itself ambulatory.
        var hr: [HRSample] = []
        for h in [8, 9, 10] { hr += hourHR(h, bpm: 58) }
        hr += hourHR(11, bpm: 120)   // walking
        hr += hourHR(12, bpm: 95)    // still, HR still elevated
        var grav: [GravitySample] = []
        for h in [8, 9, 10] { grav += hourGravity(h, delta: 0.02) }
        grav += hourGravity(11, delta: 0.30)               // ambulatory
        grav += hourGravity(12, delta: 0.02)               // still

        let r = DaytimeStress.analyze(hr: hr, rr: [], gravity: grav)
        XCTAssertTrue(r.hours.first { $0.hour == 11 }!.maskedForActivity, "the walking hour is masked")
        let h12 = r.hours.first { $0.hour == 12 }!
        XCTAssertTrue(h12.maskedForActivity, "the still-but-elevated hour after exercise is shadow-masked")
        XCTAssertNil(h12.level)
    }

    func testShadowReleasesWhenHRHasRecovered() {
        // Same walking hour (11), but the following still hour (12) has HR already back at the calm
        // reference → the shadow self-limits and 12 is scored normally, not masked.
        var hr: [HRSample] = []
        for h in [8, 9, 10] { hr += hourHR(h, bpm: 58) }
        hr += hourHR(11, bpm: 120)   // walking
        hr += hourHR(12, bpm: 58)    // still, HR fully recovered
        var grav: [GravitySample] = []
        for h in [8, 9, 10] { grav += hourGravity(h, delta: 0.02) }
        grav += hourGravity(11, delta: 0.30)               // ambulatory
        grav += hourGravity(12, delta: 0.02)               // still

        let r = DaytimeStress.analyze(hr: hr, rr: [], gravity: grav)
        XCTAssertTrue(r.hours.first { $0.hour == 11 }!.maskedForActivity, "the walking hour is masked")
        let h12 = r.hours.first { $0.hour == 12 }!
        XCTAssertFalse(h12.maskedForActivity, "a recovered following hour is not shadow-masked")
        XCTAssertNotNil(h12.level, "the recovered hour is scored normally")
    }
}
