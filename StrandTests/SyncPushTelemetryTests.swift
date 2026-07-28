// Compiled ONLY when the CLOUD_SYNC compilation condition is set (StrandTests shares the app's
// OuraConfig.xcconfig, so the flag arrives with the app's).
#if CLOUD_SYNC
import XCTest
@testable import Strand

/// `SyncPushTelemetry` exists to answer one question about a real device: how often does a
/// page-replication push degrade to a full snapshot? Everything asserted here is a property that
/// number depends on — if trimming lost the counters, or a corrupt file threw, or the gap between
/// pushes were computed from the wrong record, the trial would produce a plausible wrong answer
/// rather than an obvious failure. That is the risk being tested.
final class SyncPushTelemetryTests: XCTestCase {

    private var dir: URL!

    override func setUpWithError() throws {
        dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("push-telemetry-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func makeURL() -> URL { dir.appendingPathComponent("push-telemetry.json") }

    private func record(_ t: SyncPushTelemetry,
                        at: Date,
                        snapshotted: Bool = false,
                        reason: String? = nil,
                        bytes: Int64 = 1_000,
                        wal: Int64 = 4_096,
                        txid: UInt64 = 1) {
        t.record(at: at, snapshotted: snapshotted, snapshotReason: reason,
                 bytesUploaded: bytes, walBytes: wal, txid: txid)
    }

    // MARK: - The number the trial turns on

    func testSnapshotRateAndByteShare() {
        let t = SyncPushTelemetry(url: makeURL())
        let t0 = Date(timeIntervalSince1970: 1_000)
        record(t, at: t0, snapshotted: true, reason: "first sync, no local position", bytes: 800_000_000)
        for i in 1...9 { record(t, at: t0.addingTimeInterval(Double(i) * 600), bytes: 400_000) }

        let s = t.stats
        XCTAssertEqual(s.pushes, 10)
        XCTAssertEqual(s.snapshots, 1)
        XCTAssertEqual(s.snapshotRate, 0.1, accuracy: 1e-9)
        // Frequency and cost are different questions: 10% of pushes, >99% of the bytes.
        XCTAssertGreaterThan(s.snapshotByteShare, 0.99)
    }

    /// The reason histogram is what turns "the rate is bad" into "and here is which branch of
    /// verify() caused it" — the difference between a decision and a shrug.
    func testReasonsAreCountedPerBranch() {
        let t = SyncPushTelemetry(url: makeURL())
        let t0 = Date(timeIntervalSince1970: 0)
        record(t, at: t0, snapshotted: true, reason: "wal truncated by another process")
        record(t, at: t0.addingTimeInterval(10), snapshotted: true, reason: "wal truncated by another process")
        record(t, at: t0.addingTimeInterval(20), snapshotted: true, reason: "full or restart checkpoint detected, snapshotting")
        record(t, at: t0.addingTimeInterval(30))

        XCTAssertEqual(t.stats.reasonCounts, [
            "wal truncated by another process": 2,
            "full or restart checkpoint detected, snapshotting": 1,
        ])
    }

    /// A non-snapshotting push must not carry a reason even if one is passed — otherwise the
    /// histogram would count branches that did not fire.
    func testNonSnapshotPushDropsAnyReason() {
        let t = SyncPushTelemetry(url: makeURL())
        let o = t.record(at: Date(), snapshotted: false, snapshotReason: "stale",
                         bytesUploaded: 10, walBytes: 20, txid: 3)
        XCTAssertNil(o.snapshotReason)
        XCTAssertTrue(t.stats.reasonCounts.isEmpty)
    }

    // MARK: - Bounded, but not lossy where it counts

    /// The record window is capped so the file cannot grow without bound over a long trial — but
    /// the aggregate counters must survive trimming, or the rate would silently become "the rate
    /// over the last 200 pushes" while still being read as "the rate".
    func testTrimmingDropsRecordsButNeverTheCounters() {
        let t = SyncPushTelemetry(url: makeURL())
        let t0 = Date(timeIntervalSince1970: 0)
        let n = SyncPushTelemetry.maxRecords + 50
        for i in 0..<n {
            record(t, at: t0.addingTimeInterval(Double(i) * 60), snapshotted: i % 10 == 0,
                   reason: "wal truncated by another process", bytes: 100)
        }

        XCTAssertEqual(t.recentRecords.count, SyncPushTelemetry.maxRecords)
        let s = t.stats
        XCTAssertEqual(s.pushes, n, "totals must cover the whole trial, not the retained window")
        XCTAssertEqual(s.snapshots, (n + 9) / 10)
        XCTAssertEqual(s.bytesUploaded, Int64(n) * 100)
    }

    // MARK: - Cadence and WAL size

    func testGapIsMeasuredFromThePreviousPush() {
        let t = SyncPushTelemetry(url: makeURL())
        let t0 = Date(timeIntervalSince1970: 5_000)
        let first = t.record(at: t0, snapshotted: false, snapshotReason: nil,
                             bytesUploaded: 1, walBytes: 1, txid: 1)
        XCTAssertNil(first.secondsSinceLastPush, "the first push after install has no predecessor")

        let second = t.record(at: t0.addingTimeInterval(900), snapshotted: false, snapshotReason: nil,
                              bytesUploaded: 1, walBytes: 1, txid: 2)
        XCTAssertEqual(second.secondsSinceLastPush ?? 0, 900, accuracy: 1e-6)
        XCTAssertEqual(t.stats.medianSecondsBetweenPushes ?? 0, 900, accuracy: 1e-6)
    }

    /// Peak WAL is the other half of the story: a snapshot is usually preceded by a WAL that grew
    /// large enough for something to checkpoint it.
    func testPeakWalIsRetained() {
        let t = SyncPushTelemetry(url: makeURL())
        let t0 = Date(timeIntervalSince1970: 0)
        record(t, at: t0, wal: 4_096)
        record(t, at: t0.addingTimeInterval(60), wal: 96 * 1024 * 1024)
        record(t, at: t0.addingTimeInterval(120), wal: 8_192)
        XCTAssertEqual(t.stats.maxWalBytes, 96 * 1024 * 1024)
    }

    // MARK: - Durability

    func testStateSurvivesAReopen() {
        let url = makeURL()
        let t0 = Date(timeIntervalSince1970: 0)
        do {
            let t = SyncPushTelemetry(url: url)
            record(t, at: t0, snapshotted: true, reason: "wal truncated by another process")
            record(t, at: t0.addingTimeInterval(60))
        }
        let reopened = SyncPushTelemetry(url: url)
        XCTAssertEqual(reopened.stats.pushes, 2)
        XCTAssertEqual(reopened.stats.snapshots, 1)
        XCTAssertEqual(reopened.recentRecords.count, 2)
    }

    /// Losing telemetry must never break a sync: a truncated or garbage file starts a fresh log
    /// instead of throwing out of the push path.
    func testCorruptFileStartsEmptyRatherThanThrowing() throws {
        let url = makeURL()
        try Data("{ not json".utf8).write(to: url)
        let t = SyncPushTelemetry(url: url)
        XCTAssertEqual(t.stats.pushes, 0)
        record(t, at: Date())
        XCTAssertEqual(t.stats.pushes, 1)
    }

    func testResetClearsEverything() {
        let url = makeURL()
        let t = SyncPushTelemetry(url: url)
        record(t, at: Date(), snapshotted: true, reason: "x")
        t.reset()
        XCTAssertEqual(t.stats.pushes, 0)
        XCTAssertTrue(t.recentRecords.isEmpty)
        XCTAssertTrue(t.stats.reasonCounts.isEmpty)
        XCTAssertEqual(SyncPushTelemetry(url: url).stats.pushes, 0, "reset must be persisted too")
    }

    func testOneLineSummaryNamesTheRateAndTheReasons() {
        let t = SyncPushTelemetry(url: makeURL())
        let t0 = Date(timeIntervalSince1970: 0)
        record(t, at: t0, snapshotted: true, reason: "wal truncated by another process")
        for i in 1...3 { record(t, at: t0.addingTimeInterval(Double(i) * 60)) }
        let line = t.oneLineSummary
        XCTAssertTrue(line.contains("pushes=4"), line)
        XCTAssertTrue(line.contains("snapshots=1"), line)
        XCTAssertTrue(line.contains("25.0%"), line)
        XCTAssertTrue(line.contains("wal truncated by another process=1"), line)
    }
}
#endif
