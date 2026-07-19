import XCTest
@testable import WhoopProtocol

/// PPG-derived respiratory rate from the WHOOP 5.0 v26 optical buffer (issue #103).
///
/// The estimator is a band-limited spectral peak search (0.15-0.40 Hz) on the RIAV (amplitude
/// envelope) / RIIV (baseline) channels of a ~40 s burst. These tests drive it with SYNTHETIC bursts
/// (a carrier pulse amplitude- and baseline-modulated at a known breathing rate → that rate recovered;
/// a flat/noisy burst → no fabricated estimate) so they are deterministic and need no capture fixtures,
/// mirroring `PpgHrTests.swift`'s style for the sibling per-second HR estimator on the same buffer.
final class PpgRespTests: XCTestCase {
    private let fs = PpgResp.sampleRateHz   // 24

    /// One synthetic burst: a ~1 Hz pulse carrier whose AMPLITUDE and BASELINE are both modulated at
    /// `breathBpm` breaths/min — RIAV picks up the amplitude modulation, RIIV the baseline shift, so
    /// either channel alone is enough to recover the planted rate.
    private func breathingBurst(breathBpm: Double, seconds: Int = 40, base: Int = 1_780_000_000,
                                pulseHz: Double = 1.1, carrierAmp: Double = 1000, modDepth: Double = 400,
                                baselineAmp: Double = 300) -> [(ts: Int, samples: [Int])] {
        let breathHz = breathBpm / 60.0
        var samples = [Int]()
        samples.reserveCapacity(seconds * fs)
        for n in 0..<(seconds * fs) {
            let t = Double(n) / Double(fs)
            let breath = sin(2 * Double.pi * breathHz * t)
            let amplitude = carrierAmp + modDepth * breath
            let pulse = amplitude * sin(2 * Double.pi * pulseHz * t)
            let baseline = baselineAmp * breath
            samples.append(Int(pulse + baseline))
        }
        return (0..<seconds).map { s in
            (ts: base + s, samples: Array(samples[(s * fs)..<((s + 1) * fs)]))
        }
    }

    func testEstimateRecoversKnownBreathingRate() throws {
        let records = breathingBurst(breathBpm: 15)
        let sig = records.flatMap { $0.samples }
        let est = try XCTUnwrap(PpgResp.estimate(sig))
        XCTAssertEqual(est.bpm, 15, accuracy: 1.0)
        XCTAssertGreaterThanOrEqual(est.conf, PpgResp.minProminence)
    }

    func testEstimateRecoversASlowBreathingRate() throws {
        // Near the low end of the band (11 breaths/min = 0.1833 Hz, clear of the bandEdgeGuardHz
        // margin around the 9 breaths/min floor) — must not fold to the high end.
        let records = breathingBurst(breathBpm: 11)
        let sig = records.flatMap { $0.samples }
        let est = try XCTUnwrap(PpgResp.estimate(sig))
        XCTAssertEqual(est.bpm, 11, accuracy: 1.0)
    }

    func testEstimateRecoversAFastBreathingRate() throws {
        // Near the high end of the band (19 breaths/min = 0.3167 Hz) — exercises the upper half of
        // the 0.15-0.40 Hz search, not just the low/mid values the other tests cover.
        let records = breathingBurst(breathBpm: 19)
        let sig = records.flatMap { $0.samples }
        let est = try XCTUnwrap(PpgResp.estimate(sig))
        XCTAssertEqual(est.bpm, 19, accuracy: 1.0)
    }

    func testDeriveRespRateOneSamplePerBurst() {
        let series = PpgResp.deriveRespRate(records: breathingBurst(breathBpm: 15))
        XCTAssertEqual(series.count, 1, "one ~40s burst must yield exactly one estimate, not per-second")
        let s = try! XCTUnwrap(series.first)
        XCTAssertEqual(s.ts, 1_780_000_000, "ts must be the burst's FIRST record timestamp")
        XCTAssertEqual(s.bpm, 15, accuracy: 1.0)
    }

    func testFlatSignalProducesNoEstimate() {
        // Constant DC (no variation at all) → after centering, every band bin is exactly zero power →
        // no fabricated rate. Long enough to clear the minimum-burst-length gate.
        let records: [(ts: Int, samples: [Int])] = (0..<40).map { s in
            (ts: 1_780_000_000 + s, samples: Array(repeating: 5000, count: fs))
        }
        XCTAssertTrue(PpgResp.deriveRespRate(records: records).isEmpty,
                      "a flat signal must not produce a fabricated respiratory rate")
    }

    func testEstimateRejectsTooShortBurst() {
        // A single ~1 breaths/min-modulated second is nowhere near enough to resolve 0.15 Hz.
        let oneSecond = breathingBurst(breathBpm: 15, seconds: 1).first!.samples
        XCTAssertNil(PpgResp.estimate(oneSecond))
    }

    func testBurstShorterThanMinimumIsDropped() {
        // minBurstRecords (32) worth of a run is required; 20 records must not yield an estimate even
        // though each individual second is well-formed.
        let records = Array(breathingBurst(breathBpm: 15, seconds: 40).prefix(20))
        XCTAssertTrue(PpgResp.deriveRespRate(records: records).isEmpty)
    }

    func testGapBreaksRunsIntoSeparateBursts() {
        // Two full 40 s bursts separated by a gap — both estimate independently, none inside the gap.
        var recs = breathingBurst(breathBpm: 12, base: 1_780_000_000)
        recs += breathingBurst(breathBpm: 18, base: 1_780_001_000)
        let series = PpgResp.deriveRespRate(records: recs)
        XCTAssertEqual(series.count, 2)
        XCTAssertEqual(series[0].bpm, 12, accuracy: 1.0)
        XCTAssertEqual(series[1].bpm, 18, accuracy: 1.0)
        XCTAssertEqual(series[0].ts, 1_780_000_000)
        XCTAssertEqual(series[1].ts, 1_780_001_000)
    }

    func testDeriveRespRateRecordsMayBeUnsorted() {
        var recs = breathingBurst(breathBpm: 15)
        recs.shuffle()
        let series = PpgResp.deriveRespRate(records: recs)
        XCTAssertEqual(series.count, 1)
        XCTAssertEqual(series[0].ts, 1_780_000_000)
    }

    /// Streams decode tolerance: a JSON missing `ppg_resp` still decodes (defaults to empty), and a
    /// present `ppg_resp` round-trips. Mirrors the equivalent `ppg_hr` guard in `PpgHrTests`.
    func testStreamsDecodeToleratesMissingAndPresentPpgResp() throws {
        let dec = JSONDecoder()
        let s1 = try dec.decode(Streams.self, from: Data(#"{"hr":[]}"#.utf8))
        XCTAssertTrue(s1.ppgResp.isEmpty)
        let json = #"{"ppg_resp":[{"ts":1780000000,"bpm":15.0,"conf":3.5}]}"#
        let s2 = try dec.decode(Streams.self, from: Data(json.utf8))
        XCTAssertEqual(s2.ppgResp, [PpgRespSample(ts: 1_780_000_000, bpm: 15.0, conf: 3.5)])
        let round = try dec.decode(Streams.self, from: JSONEncoder().encode(s2))
        XCTAssertEqual(round.ppgResp, s2.ppgResp)
    }
}
