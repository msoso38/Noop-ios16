import XCTest
@testable import OuraProtocol

/// Tests for the `check_sleep` s:/e: sleep-window parser (OURA_PROTOCOL.md §6.15 prototype). Fixtures
/// are the exact debug-text lines captured from a real Gen3 ring.
final class CheckSleepParserTests: XCTestCase {

    func testExtractsWindowFromRealCheckSleepSequence() {
        var p = OuraCheckSleepParser()
        // The real captured sequence: check_sleep, then s:/e: boundaries.
        XCTAssertNil(p.ingest(line: "check_sleep"))
        XCTAssertNil(p.ingest(line: "s: 114643"))            // start alone → incomplete
        XCTAssertEqual(p.ingest(line: "e: 446001"), .init(startRt: 114_643, endRt: 446_001))
        XCTAssertNil(p.ingest(line: "not needed"))           // unrelated line
        // A refined wake emits the new window once…
        XCTAssertEqual(p.ingest(line: "e: 446340"), .init(startRt: 114_643, endRt: 446_340))
        // …but a repeat of the same window does not re-emit.
        XCTAssertNil(p.ingest(line: "e: 446340"))
    }

    func testIgnoresLookalikeAndNonBoundaryLines() {
        var p = OuraCheckSleepParser()
        _ = p.ingest(line: "s: 114643")
        // `tsc:`, `bed:`, `ns=`, `e:` with a non-numeric tail, and empty tails must NOT be read as e:/s:.
        XCTAssertNil(p.ingest(line: "tsc:60464"))
        XCTAssertNil(p.ingest(line: "bed: 114643"))
        XCTAssertNil(p.ingest(line: "ns=1025898"))
        XCTAssertNil(p.ingest(line: "e: pp_stop"))
        XCTAssertNil(p.ingest(line: "e:"))
        // A valid e: still completes the window afterwards.
        XCTAssertEqual(p.ingest(line: "e: 446340"), .init(startRt: 114_643, endRt: 446_340))
    }

    func testRejectsInvertedWindow() {
        var p = OuraCheckSleepParser()
        _ = p.ingest(line: "s: 500000")
        XCTAssertNil(p.ingest(line: "e: 446340"))   // wake before bedtime → not a window
    }

    func testRejectsCrossBlockPhantomWindow() {
        // Real 2026-07-09 capture: last night's wake `e: 1311598` arrived while the PREVIOUS night's
        // `s: 114643` was still latched → a 33 h window. The max-duration guard must reject it…
        var p = OuraCheckSleepParser()
        _ = p.ingest(line: "s: 114643")
        XCTAssertNil(p.ingest(line: "e: 1311598"))               // 1_196_955 ticks ≈ 33.2 h → rejected
        // …but a fresh `s:` completes the window against the latched `e:` (≈ 7.9 h) — and, matching the
        // real log, the emit fires on THIS `s:` line (the `e:` is already latched).
        XCTAssertEqual(p.ingest(line: "s: 1025598"), .init(startRt: 1_025_598, endRt: 1_311_598))
    }

    func testResetClearsState() {
        var p = OuraCheckSleepParser()
        _ = p.ingest(line: "s: 114643")
        _ = p.ingest(line: "e: 446340")
        p.reset()
        // After reset, a lone e: cannot complete a window (start was cleared).
        XCTAssertNil(p.ingest(line: "e: 446340"))
    }

    func testAnchoredWindowConvertsToRealUtc() {
        // End to end: feed the driver a UTC anchor, then anchor the parsed s:/e: to unix seconds.
        let key = [UInt8](0..<16)
        let d = OuraDriver(ringGen: .gen3, authKey: key)
        let anchorEpoch: Int64 = 1_700_000_000
        let anchorRt: UInt32 = 500_000
        _ = d.ingest(record: OuraRecord(type: OuraEventTag.timeSync.rawValue, ringTimestamp: anchorRt,
                                        payload: (0..<8).map { UInt8((UInt64(bitPattern: anchorEpoch) >> ($0 * 8)) & 0xFF) } + [0x00]))
        var p = OuraCheckSleepParser()
        _ = p.ingest(line: "s: 400000")
        let w = p.ingest(line: "e: 450000")!
        // 400000 ticks = 100_000 before anchor → -10_000 s; 450000 = 50_000 before → -5_000 s.
        XCTAssertEqual(d.unixSeconds(forRingTimestamp: w.startRt), Int(anchorEpoch) - 10_000)
        XCTAssertEqual(d.unixSeconds(forRingTimestamp: w.endRt), Int(anchorEpoch) - 5_000)
    }
}
