import XCTest
import GRDB
import WhoopProtocol
@testable import WhoopStore

/// `WalCheckpointing.external` turns SQLite's autocheckpoint off, which leaves **nothing** bounding
/// WAL growth: the external replicator's own thresholds live inside its push loop, so they do not
/// fire when it is asleep, crashed, or never started. The backstop is what makes that safe.
///
/// Two halves are tested separately on purpose:
///
/// * `WalBackstopCore` is pure — threshold, in-flight suppression and retry backoff are asserted
///   with no database and no clock, so those rules cannot regress silently behind a slow test.
/// * The integration tests then prove the rule is actually wired into the write path: a real
///   `DatabasePool`, real commits, and a real `-wal` file that has to come back down.
final class WalBackstopTests: XCTestCase {

    private func tempPath() -> String {
        NSTemporaryDirectory() + "whoopstore-backstop-\(UUID().uuidString).sqlite"
    }

    private func removeDB(_ path: String) {
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: path + suffix)
        }
    }

    private func walBytes(_ path: String) -> Int64 {
        let attrs = try? FileManager.default.attributesOfItem(atPath: path + "-wal")
        return (attrs?[.size] as? NSNumber)?.int64Value ?? 0
    }

    /// One transaction per call, mirroring NOOP's real commit shape (a `Collector` flush).
    @discardableResult
    private func writeTransactions(_ store: WhoopStore, count: Int, rowsEach: Int = 400,
                                   startingAt ts0: Int = 0) async throws -> Int {
        var ts = ts0
        for _ in 0..<count {
            var hr: [HRSample] = []
            for _ in 0..<rowsEach { ts += 1; hr.append(HRSample(ts: ts, bpm: 60 + ts % 40)) }
            _ = try await store.insert(Streams(hr: hr), deviceId: "dev")
        }
        return ts
    }

    // MARK: - The chosen ceiling

    /// The default ceiling is a decision, not an implementation detail — it trades "how often a
    /// forced checkpoint costs the replicator its incremental resume point" against "how much disk a
    /// dead replicator can consume". Pin it so a change has to be deliberate.
    func testStandardCeilingIs64MiB() {
        XCTAssertEqual(WalBackstopPolicy.standard.ceilingBytes, 64 * 1024 * 1024)
        XCTAssertTrue(WalBackstopPolicy.standard.isEnabled)
        XCTAssertFalse(WalBackstopPolicy.disabled.isEnabled)
    }

    /// The ceiling has to sit between the replicator's own two thresholds: far above the ~4 MB it
    /// keeps the WAL at when healthy (so a firing means something is wrong, never routine), and far
    /// below its ~500 MB emergency truncate (so the app-side floor acts first — which it must,
    /// because the replicator's own thresholds cannot fire while it is asleep).
    func testStandardCeilingSitsBetweenTheReplicatorsThresholds() {
        let litersPassive: Int64 = 1_000 * 4096          // liters min_checkpoint_page_n
        let litersEmergency: Int64 = 121_359 * 4096      // liters truncate_page_n
        let ceiling = WalBackstopPolicy.standard.ceilingBytes
        XCTAssertGreaterThan(ceiling, litersPassive * 8)
        XCTAssertLessThan(ceiling, litersEmergency / 4)
    }

    // MARK: - Pure decision core

    func testCoreDoesNotFireBelowCeiling() {
        var core = WalBackstopCore(policy: WalBackstopPolicy(ceilingBytes: 1000))
        XCTAssertFalse(core.shouldCheckpoint(walBytes: 999, now: 0))
        XCTAssertEqual(core.firings, 0)
    }

    func testCoreFiresAtCeiling() {
        var core = WalBackstopCore(policy: WalBackstopPolicy(ceilingBytes: 1000))
        XCTAssertTrue(core.shouldCheckpoint(walBytes: 1000, now: 0))
        XCTAssertEqual(core.firings, 1)
    }

    /// Many commits can land between crossing the ceiling and the dispatched checkpoint finishing.
    /// Only the first may start one, or a burst of writes would queue a checkpoint per commit.
    func testCoreSuppressesWhileCheckpointInFlight() {
        var core = WalBackstopCore(policy: WalBackstopPolicy(ceilingBytes: 1000))
        XCTAssertTrue(core.shouldCheckpoint(walBytes: 5000, now: 0))
        XCTAssertFalse(core.shouldCheckpoint(walBytes: 6000, now: 0))
        XCTAssertFalse(core.shouldCheckpoint(walBytes: 7000, now: 1))
        XCTAssertEqual(core.firings, 1)
    }

    /// A checkpoint that worked re-arms immediately: the next time the WAL climbs back to the
    /// ceiling it must be caught, with no cooldown.
    func testCoreReArmsAfterEffectiveCheckpoint() {
        var core = WalBackstopCore(policy: WalBackstopPolicy(ceilingBytes: 1000))
        XCTAssertTrue(core.shouldCheckpoint(walBytes: 5000, now: 0))
        core.didFinishCheckpoint(walBytes: 0, now: 1)
        XCTAssertEqual(core.ineffectiveAttempts, 0)
        XCTAssertTrue(core.shouldCheckpoint(walBytes: 1000, now: 2))
        XCTAssertEqual(core.firings, 2)
    }

    /// A `TRUNCATE` blocked by a pool reader holding an open snapshot leaves the WAL over the
    /// ceiling. Without a backoff the very next commit would try again, turning a pinned WAL into a
    /// checkpoint attempt per commit — a retry storm on the ingest path.
    func testCoreBacksOffAfterIneffectiveCheckpoint() {
        var core = WalBackstopCore(policy: WalBackstopPolicy(ceilingBytes: 1000, minRetryInterval: 60))
        XCTAssertTrue(core.shouldCheckpoint(walBytes: 5000, now: 0))
        core.didFinishCheckpoint(walBytes: 5000, now: 10)   // still over: blocked
        XCTAssertEqual(core.ineffectiveAttempts, 1)

        XCTAssertFalse(core.shouldCheckpoint(walBytes: 5000, now: 11), "must back off, not retry per commit")
        XCTAssertFalse(core.shouldCheckpoint(walBytes: 5000, now: 69))
        XCTAssertTrue(core.shouldCheckpoint(walBytes: 5000, now: 70), "must retry once the interval elapses")
        XCTAssertEqual(core.firings, 2)
    }

    func testCoreNeverFiresWhenDisabled() {
        var core = WalBackstopCore(policy: .disabled)
        XCTAssertFalse(core.shouldCheckpoint(walBytes: Int64.max, now: 0))
        XCTAssertEqual(core.firings, 0)
    }

    // MARK: - Wiring: `.automatic` pays nothing

    /// The default build must be untouched: no observer, no `stat(2)` per commit, no behaviour
    /// change. This package is upstream-shared and `.automatic` is what upstream ships.
    func testAutomaticInstallsNoBackstop() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let store = try await WhoopStore(path: path)
        XCTAssertNil(store.walBackstopMonitor, "automatic checkpointing must not install a WAL observer")
        XCTAssertEqual(store.walBackstopFirings, 0)
    }

    func testExternalWithDisabledPolicyInstallsNoBackstop() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let store = try await WhoopStore(path: path, walCheckpointing: .external, walBackstop: .disabled)
        XCTAssertNil(store.walBackstopMonitor, "an explicitly disabled backstop must not be installed")
    }

    func testExternalInstallsBackstopByDefault() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let store = try await WhoopStore(path: path, walCheckpointing: .external)
        XCTAssertNotNil(store.walBackstopMonitor,
                        "`.external` must discharge its own obligation — the caller must not have to remember")
    }

    // MARK: - Wiring: the write path actually enforces the ceiling

    /// THE CORE ASSERTION. With autocheckpoint off and nothing else checkpointing, the WAL must
    /// still come back down — from inside the write path, with no cooperation from any caller.
    func testBackstopBoundsTheWalWithNoExternalCheckpointer() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        // A tiny ceiling so the test is fast; the mechanism is identical at 64 MiB.
        let ceiling: Int64 = 256 * 1024
        let store = try await WhoopStore(path: path,
                                         walCheckpointing: .external,
                                         walBackstop: WalBackstopPolicy(ceilingBytes: ceiling,
                                                                        minRetryInterval: 0))
        var peak: Int64 = 0
        var ts = 0
        for _ in 0..<40 {
            ts = try await writeTransactions(store, count: 5, rowsEach: 400, startingAt: ts)
            store.walBackstopMonitor?.drainForTest()
            peak = max(peak, walBytes(path))
        }
        store.walBackstopMonitor?.drainForTest()

        XCTAssertGreaterThan(store.walBackstopFirings, 0,
                             "the backstop must have fired — the WAL crossed \(ceiling) bytes")
        // Generous headroom: the ceiling is checked per commit, so the WAL can overshoot by up to
        // one transaction plus whatever lands while the dispatched checkpoint is in flight. What
        // must NOT happen is unbounded growth.
        XCTAssertLessThan(peak, ceiling * 8,
                          "WAL peaked at \(peak) bytes against a \(ceiling)-byte ceiling — not bounded")

        // The data must be intact: a forced checkpoint may not lose a committed row.
        let counts = try await store.storageStats_rowCountsForTest()
        XCTAssertEqual(counts.hr, 40 * 5 * 400)
    }

    /// The contrast case, which is what proves the previous test measured the backstop and not some
    /// incidental checkpoint: the identical workload with the backstop disabled grows the WAL far
    /// past the same ceiling and never comes back.
    func testWithoutBackstopTheSameWorkloadGrowsUnbounded() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let ceiling: Int64 = 256 * 1024
        let store = try await WhoopStore(path: path, walCheckpointing: .external, walBackstop: .disabled)
        var ts = 0
        for _ in 0..<40 { ts = try await writeTransactions(store, count: 5, rowsEach: 400, startingAt: ts) }

        XCTAssertGreaterThan(walBytes(path), ceiling * 4,
                             "without a backstop the WAL must run away — this is the failure the backstop prevents")
        XCTAssertEqual(store.walBackstopFirings, 0)
    }

    /// `walFileSizeBytes()` isolates the component `.external` puts at risk, and is the cheap poll a
    /// diagnostics screen or the device-trial recorder uses.
    func testWalFileSizeBytesTracksTheWalAlone() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let store = try await WhoopStore(path: path, walCheckpointing: .external, walBackstop: .disabled)
        _ = try await writeTransactions(store, count: 20, rowsEach: 400)

        let reported = store.walFileSizeBytes()
        XCTAssertNotNil(reported)
        XCTAssertEqual(reported, walBytes(path))
        XCTAssertGreaterThan(reported ?? 0, 0)

        try await store.checkpointWAL()
        XCTAssertLessThan(store.walFileSizeBytes() ?? -1, reported ?? 0)
    }

    func testWalFileSizeBytesIsNilForInMemoryStore() async throws {
        let store = try await WhoopStore.inMemory()
        XCTAssertNil(store.walFileSizeBytes())
        XCTAssertEqual(store.walBackstopFirings, 0)
    }
}
