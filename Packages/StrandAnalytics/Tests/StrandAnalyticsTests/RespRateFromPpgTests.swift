import XCTest
@testable import StrandAnalytics
import WhoopProtocol

/// Tests `SleepStager.respRateFromPpg` (issue #103) — the top-third-by-confidence aggregation over the
/// PPG-derived per-burst stream (`PpgResp.deriveRespRate`), used by `AnalyticsEngine`'s prefer-PPG-
/// else-RSA fusion. Mirrors `RespRateRsaTests.swift`'s style: synthetic sample arrays, not captures.
final class RespRateFromPpgTests: XCTestCase {
    private let start = 1_700_000_000
    private let end = 1_700_030_000

    /// `n` bursts, evenly spaced across [start, end], with bpm/conf from parallel arrays.
    private func samples(bpm: [Double], conf: [Double]) -> [PpgRespSample] {
        precondition(bpm.count == conf.count)
        let n = bpm.count
        return (0..<n).map { i in
            PpgRespSample(ts: start + i * ((end - start) / max(n, 1)), bpm: bpm[i], conf: conf[i])
        }
    }

    func testRecoversMedianOfTopThirdByConfidence() {
        // 9 bursts (minPpgBurstsForEstimate): the top 3 by conf are bpm 14/15/16 → median 15.
        // The other 6 are noisy/low-confidence and must be ignored, not dragged into the result.
        let bpm  = [14.0, 15.0, 16.0, 5.0, 30.0, 9.0, 25.0, 6.0, 28.0]
        let conf = [10.0, 12.0, 11.0, 2.0, 1.5, 2.0, 1.5, 2.0, 1.5]
        let est = SleepStager.respRateFromPpg(samples(bpm: bpm, conf: conf), start: start, end: end)
        XCTAssertEqual(est, 15.0, accuracy: 0.001)
    }

    func testTooFewBurstsIsNaN() {
        // minPpgBurstsForEstimate - 1 bursts, all otherwise well-formed → NaN, not a fabricated median.
        let n = SleepStager.minPpgBurstsForEstimate - 1
        let bpm = Array(repeating: 15.0, count: n)
        let conf = Array(repeating: 10.0, count: n)
        XCTAssertTrue(SleepStager.respRateFromPpg(samples(bpm: bpm, conf: conf), start: start, end: end).isNaN)
        XCTAssertTrue(SleepStager.respRateFromPpg([], start: start, end: end).isNaN)
    }

    func testExactlyMinimumBurstsProducesAnEstimate() {
        let n = SleepStager.minPpgBurstsForEstimate
        let bpm = Array(repeating: 15.0, count: n)
        let conf = Array(repeating: 10.0, count: n)
        let est = SleepStager.respRateFromPpg(samples(bpm: bpm, conf: conf), start: start, end: end)
        XCTAssertFalse(est.isNaN)
    }

    func testFiltersBurstsOutsideTheSessionWindow() {
        // 9 in-window bursts at 15 bpm, plus 20 far-outside-window bursts at 99 bpm that must not
        // count toward the minimum OR pollute the median.
        var recs = samples(bpm: Array(repeating: 15.0, count: 9), conf: Array(repeating: 10.0, count: 9))
        recs += (0..<20).map { PpgRespSample(ts: start - 100_000 - $0, bpm: 99.0, conf: 50.0) }
        let est = SleepStager.respRateFromPpg(recs, start: start, end: end)
        XCTAssertEqual(est, 15.0, accuracy: 0.001)
    }

    func testMedianOutsidePlausibleBandIsNaN() {
        // 9 bursts all agreeing on an implausible 3 bpm (below the 8-25 band) — never surfaced.
        let bpm = Array(repeating: 3.0, count: 9)
        let conf = Array(repeating: 10.0, count: 9)
        XCTAssertTrue(SleepStager.respRateFromPpg(samples(bpm: bpm, conf: conf), start: start, end: end).isNaN)
    }

    func testTopThirdRoundingIsFloorDivision() {
        // 10 bursts → top third = max(1, 10/3) = 3 (floor division, not rounded/ceiled). The top 3 by
        // conf are 14/15/16 (median 15); if rounding were ceil (4), bpm 5.0 (conf 9.0) would enter the
        // top group and pull the median down to 14.5.
        let bpm  = [14.0, 15.0, 16.0, 5.0, 30.0, 9.0, 25.0, 6.0, 28.0, 2.0]
        let conf = [10.0, 12.0, 11.0, 9.0, 1.5, 2.0, 1.5, 2.0, 1.5, 1.0]
        let est = SleepStager.respRateFromPpg(samples(bpm: bpm, conf: conf), start: start, end: end)
        XCTAssertEqual(est, 15.0, accuracy: 0.001)
    }

    func testEndNotAfterStartIsNaN() {
        let bpm = Array(repeating: 15.0, count: 9)
        let conf = Array(repeating: 10.0, count: 9)
        let recs = samples(bpm: bpm, conf: conf)
        XCTAssertTrue(SleepStager.respRateFromPpg(recs, start: end, end: start).isNaN)
        XCTAssertTrue(SleepStager.respRateFromPpg(recs, start: start, end: start).isNaN)
    }
}
