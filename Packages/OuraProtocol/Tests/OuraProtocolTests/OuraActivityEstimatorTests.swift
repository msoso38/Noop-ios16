import XCTest
@testable import OuraProtocol

/// Phase-1 activity-estimate roll-up tests. The estimator is pure, so these pin the band classification,
/// the cadence-linear scaling, the net-of-resting kcal, and the labelled step proxy — the numbers that
/// will be eyeballed against the WHOOP band in the field.
final class OuraActivityEstimatorTests: XCTestCase {
    private let day = "2026-07-08"

    private func sample(_ met: Double, ts: Int = 1_000) -> OuraActivitySample {
        OuraActivitySample(ts: ts, met: met)
    }

    func testEmptyIsAllZero() {
        let e = OuraActivityEstimator.summarize([], day: day, bodyweightKg: 75,
                                                assumedIntervalSec: 30, stepsPerActiveMin: 100)
        XCTAssertEqual(e.sampleCount, 0)
        XCTAssertEqual(e.activeMinutes, 0)
        XCTAssertEqual(e.estActiveKcal, 0)
        XCTAssertEqual(e.stepProxy, 0)
        XCTAssertNil(e.firstTs)
    }

    func testBandClassificationEdges() {
        // Exact edges land in the HIGHER band (half-open [floor, ceil)).
        let samples = [0.9, 1.5, 2.9, 3.0, 5.9, 6.0, 7.4].map { sample($0) }
        let e = OuraActivityEstimator.summarize(samples, day: day, bodyweightKg: 75,
                                                assumedIntervalSec: 30, stepsPerActiveMin: 100)
        XCTAssertEqual(e.sedentarySamples, 1)          // 0.9
        XCTAssertEqual(e.lightSamples, 2)              // 1.5, 2.9
        XCTAssertEqual(e.moderateSamples, 2)           // 3.0, 5.9
        XCTAssertEqual(e.vigorousSamples, 2)           // 6.0, 7.4
        XCTAssertEqual(e.maxMet, 7.4, accuracy: 1e-9)
    }

    func testActiveMinutesScaleWithAssumedInterval() {
        // 4 active samples (>=1.5 MET) + 1 sedentary. At 30 s/sample → 4*30/60 = 2.0 active min.
        let samples = [0.9, 2.0, 2.0, 4.0, 7.0].map { sample($0) }
        let e30 = OuraActivityEstimator.summarize(samples, day: day, bodyweightKg: 75,
                                                  assumedIntervalSec: 30, stepsPerActiveMin: 100)
        XCTAssertEqual(e30.activeMinutes, 2.0, accuracy: 1e-9)
        XCTAssertEqual(e30.stepProxy, 200)             // 2.0 * 100

        // Doubling the assumed cadence doubles every time-based figure (the WHOOP-calibration lever).
        let e60 = OuraActivityEstimator.summarize(samples, day: day, bodyweightKg: 75,
                                                  assumedIntervalSec: 60, stepsPerActiveMin: 100)
        XCTAssertEqual(e60.activeMinutes, 4.0, accuracy: 1e-9)
        XCTAssertEqual(e60.stepProxy, 400)
    }

    func testActiveKcalIsNetOfResting() {
        // One 5.0-MET sample for 3600 s (interval) at 70 kg → (5-1)*70*1.0 = 280 kcal. A resting 1.0-MET
        // sample adds nothing (net-of-resting), and a sub-resting 0.5 never goes negative.
        let samples = [sample(5.0), sample(1.0), sample(0.5)]
        let e = OuraActivityEstimator.summarize(samples, day: day, bodyweightKg: 70,
                                                assumedIntervalSec: 3600, stepsPerActiveMin: 100)
        XCTAssertEqual(e.estActiveKcal, 280, accuracy: 1e-6)
    }

    func testMeanAndCoverageTimestamps() {
        let samples = [sample(1.0, ts: 100), sample(3.0, ts: 500), sample(2.0, ts: 300)]
        let e = OuraActivityEstimator.summarize(samples, day: day, bodyweightKg: 75,
                                                assumedIntervalSec: 30, stepsPerActiveMin: 100)
        XCTAssertEqual(e.meanMet, 2.0, accuracy: 1e-9)
        XCTAssertEqual(e.firstTs, 100)
        XCTAssertEqual(e.lastTs, 500)
    }

    func testLogLineIsGreppableAndLabelled() {
        let e = OuraActivityEstimator.summarize([sample(4.0)], day: day, bodyweightKg: 75,
                                                assumedIntervalSec: 30, stepsPerActiveMin: 100)
        let line = e.logLine(final: true)
        XCTAssertTrue(line.contains("activity estimate FINAL"))
        XCTAssertTrue(line.contains("day=\(day)"))
        XCTAssertTrue(line.contains("PROXY, not stored"))
    }
}
