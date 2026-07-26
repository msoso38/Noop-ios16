import XCTest
import StrandAnalytics
@testable import Strand

/// `StrapChargeInference` — the charging state the app is willing to stand behind.
///
/// The bug: a 5/MG on its charging puck reported `battery_charging = 0` while its SoC climbed, so the app
/// stated "not charging" and showed a "~2 days left" DISCHARGE estimate to a user watching it charge. The
/// 5.0 charge bit's offset (@30) is unverified — the decoder's own docs confirm only the SoC and mV fields
/// against a real capture — so the bit alone is not a fact worth reporting.
final class StrapChargeInferenceTests: XCTestCase {
    private let now = 1_800_000_000
    /// Two readings ~8 min apart, the strap's real BATTERY_LEVEL cadence.
    private func series(_ a: Double, _ b: Double, gap: Int = 8 * 60, age: Int = 60) -> [(ts: Int, soc: Double)] {
        [(ts: now - age - gap, soc: a), (ts: now - age, soc: b)]
    }

    /// THE bug: the flag says "not charging" and the SoC is visibly climbing. Trust the charge, not the bit.
    func testRisingSoc_overridesAFalseFlag() {
        XCTAssertEqual(StrapChargeInference.resolve(flag: false, samples: series(31.6, 38.2), nowUnix: now),
                       true)
    }

    /// Same rescue when the flag never arrives at all.
    func testRisingSoc_rescuesASilentFlag() {
        XCTAssertEqual(StrapChargeInference.resolve(flag: nil, samples: series(31.6, 38.2), nowUnix: now),
                       true)
    }

    /// A confirmed `true` is always honoured, no corroboration needed.
    func testConfirmedFlagWins() {
        XCTAssertEqual(StrapChargeInference.resolve(flag: true, samples: series(50, 49), nowUnix: now), true)
        XCTAssertEqual(StrapChargeInference.resolve(flag: true, samples: [], nowUnix: now), true)
    }

    /// A normal discharge is not a charge — the estimate must keep working.
    func testDischarging_isNotCharging() {
        XCTAssertEqual(StrapChargeInference.resolve(flag: false, samples: series(50, 49.7), nowUnix: now),
                       false)
    }

    /// Nothing to go on ⇒ nil (unknown), NOT false. The UI must be able to say nothing rather than assert
    /// "not charging" — the false statement this whole type exists to stop.
    func testNoEvidence_isUnknownNotFalse() {
        XCTAssertNil(StrapChargeInference.resolve(flag: nil, samples: [], nowUnix: now))
        XCTAssertNil(StrapChargeInference.resolve(flag: nil, samples: series(50, 50), nowUnix: now))
    }

    /// The quantisation seam: a 5/MG's live SoC arrives from 0x2A19 as a whole-percent u8, so consecutive
    /// readings can legitimately step by exactly 1.0 pp with no charge. The threshold is strictly
    /// greater-than `chargeStepPct`, so a 1.0 step must NOT read as charging.
    func testWholePercentStep_isNotMistakenForACharge() {
        XCTAssertEqual(StrapChargeInference.resolve(flag: false, samples: series(31, 32), nowUnix: now),
                       false)
        XCTAssertFalse(StrapChargeInference.isRising(samples: series(31, 32), nowUnix: now))
        // A real charge is nowhere near that subtle (~50 pp/h vs ~1.65 pp/h discharge).
        XCTAssertTrue(StrapChargeInference.isRising(samples: series(31, 38), nowUnix: now))
    }

    /// The threshold is BatteryEstimator's existing charge step, not a second invented notion of "charging".
    func testUsesTheEstimatorsOwnChargeThreshold() {
        let justUnder = BatteryEstimator.chargeStepPct
        let justOver = BatteryEstimator.chargeStepPct + 0.2
        XCTAssertFalse(StrapChargeInference.isRising(samples: series(50, 50 + justUnder), nowUnix: now))
        XCTAssertTrue(StrapChargeInference.isRising(samples: series(50, 50 + justOver), nowUnix: now))
    }

    /// A rise from an hour ago says nothing about NOW — the strap may already be off the puck.
    func testStaleRise_doesNotClaimChargingNow() {
        let old = [(ts: now - 7200, soc: 31.0), (ts: now - 6800, soc: 40.0)]
        XCTAssertFalse(StrapChargeInference.isRising(samples: old, nowUnix: now))
        XCTAssertEqual(StrapChargeInference.resolve(flag: false, samples: old, nowUnix: now), false)
    }

    /// Two readings separated by a huge gap aren't a slope — a strap that reconnects hours later at a higher
    /// SoC was charged in the meantime, but is not necessarily charging now.
    func testWideGapBetweenReadings_isNotASlope() {
        XCTAssertFalse(StrapChargeInference.isRising(samples: series(31, 90, gap: 4 * 3600), nowUnix: now))
    }

    /// A single reading can't show a trend.
    func testSingleReading_isNotARise() {
        XCTAssertFalse(StrapChargeInference.isRising(samples: [(ts: now, soc: 50)], nowUnix: now))
    }
}
