import XCTest
import GRDB
import WhoopProtocol
@testable import WhoopStore

/// `WalCheckpointing` decides whether SQLite auto-checkpoints the WAL back into the main database.
///
/// The failure this guards against is subtle: `wal_autocheckpoint` is a **per-connection** setting,
/// and a `DatabasePool` opens one writer plus N reader connections. A page-level replicator that
/// holds a long-running read transaction to pin the WAL is defeated if *any* other connection in the
/// process still auto-checkpoints — the WAL restarts, the replicator's resume offset is overwritten,
/// and it must re-upload the whole database. So the assertion that matters is not "the pragma was
/// set" but "the pragma was set on EVERY connection the pool hands out".
///
/// `.automatic` must remain byte-for-byte today's behaviour: it is what upstream and every default
/// build ship, and this package is upstream-shared.
final class WalCheckpointingTests: XCTestCase {

    private func tempPath() -> String {
        NSTemporaryDirectory() + "whoopstore-walcp-\(UUID().uuidString).sqlite"
    }

    private func removeDB(_ path: String) {
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: path + suffix)
        }
    }

    /// Reads `PRAGMA wal_autocheckpoint` on a WRITER connection.
    private func autocheckpointOnWriter(_ store: WhoopStore) async throws -> Int {
        try await store.registryWriter.write { db in
            try Int.fetchOne(db, sql: "PRAGMA wal_autocheckpoint") ?? -1
        }
    }

    /// Reads `PRAGMA wal_autocheckpoint` on a READER connection — a `DatabasePool` serves `.read`
    /// from a separate connection than `.write`, which is exactly where the leak would hide.
    private func autocheckpointOnReader(_ store: WhoopStore) async throws -> Int {
        try await store.registryWriter.read { db in
            try Int.fetchOne(db, sql: "PRAGMA wal_autocheckpoint") ?? -1
        }
    }

    // MARK: - Default behaviour is unchanged

    /// The default must be indistinguishable from the pre-change store: SQLite's stock 1000-page
    /// autocheckpoint, on both connection kinds. Upstream and Android depend on this.
    func testDefaultLeavesAutocheckpointAtSQLiteDefault() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let store = try await WhoopStore(path: path)
        let writer = try await autocheckpointOnWriter(store)
        let reader = try await autocheckpointOnReader(store)

        XCTAssertEqual(writer, 1000, "default store must keep SQLite's stock autocheckpoint (writer)")
        XCTAssertEqual(reader, 1000, "default store must keep SQLite's stock autocheckpoint (reader)")
    }

    /// Passing `.automatic` explicitly is the same as passing nothing.
    func testExplicitAutomaticMatchesDefault() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let store = try await WhoopStore(path: path, walCheckpointing: .automatic)
        let value = try await autocheckpointOnWriter(store)
        XCTAssertEqual(value, 1000)
    }

    // MARK: - Opt-in disables it everywhere

    /// THE CORE ASSERTION: `.external` must reach every connection the pool opens, not just the
    /// writer. A reader that still auto-checkpoints restarts the WAL and forces the replicator into
    /// a full re-upload — the precise failure this option exists to prevent.
    func testExternalDisablesAutocheckpointOnWriterAndReader() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let store = try await WhoopStore(path: path, walCheckpointing: .external)
        let writer = try await autocheckpointOnWriter(store)
        let reader = try await autocheckpointOnReader(store)

        XCTAssertEqual(writer, 0, "external checkpointing must disable autocheckpoint on the writer")
        XCTAssertEqual(reader, 0, "external checkpointing must ALSO disable it on reader connections")
    }

    /// Several concurrent readers force the pool to open additional connections; every one of them
    /// must carry the pragma, since `prepareDatabase` — not a one-shot statement at open — is what
    /// applies it.
    func testExternalAppliesToEveryPooledReaderConnection() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let store = try await WhoopStore(path: path, walCheckpointing: .external)
        // Seed a row so reads have something to do.
        _ = try await store.insert(Streams(hr: [HRSample(ts: 1, bpm: 60)]), deviceId: "dev")

        let values = try await withThrowingTaskGroup(of: Int.self) { group -> [Int] in
            for _ in 0..<8 {
                group.addTask {
                    try await store.registryWriter.read { db in
                        try Int.fetchOne(db, sql: "PRAGMA wal_autocheckpoint") ?? -1
                    }
                }
            }
            var out: [Int] = []
            for try await v in group { out.append(v) }
            return out
        }

        XCTAssertEqual(values.count, 8)
        XCTAssertTrue(values.allSatisfy { $0 == 0 },
                      "every pooled reader connection must have autocheckpoint disabled, got \(values)")
    }

    // MARK: - Behaviour, not just the pragma value

    /// The observable consequence, and the reason `.external` is dangerous without a checkpointer:
    /// under `.automatic` the WAL plateaus near SQLite's ~4 MB (1000-page) threshold, because each
    /// crossing checkpoints and rewinds the WAL to the start and the file is then REUSED in place.
    /// Under `.external` nothing rewinds it, so it grows monotonically with the number of commits.
    ///
    /// The writes must therefore comfortably exceed 4 MB of WAL or neither store checkpoints and the
    /// two are trivially identical.
    func testExternalLetsWalGrowPastTheAutocheckpointThreshold() async throws {
        let autoPath = tempPath()
        let extPath = tempPath()
        defer { removeDB(autoPath); removeDB(extPath) }

        let transactions = 250
        let rowsPerTransaction = 500

        // NOOP's real commit shape: one transaction per Collector flush.
        func writeManySmallTransactions(_ store: WhoopStore) async throws {
            var ts = 0
            for _ in 0..<transactions {
                var hr: [HRSample] = []
                for _ in 0..<rowsPerTransaction { ts += 1; hr.append(HRSample(ts: ts, bpm: 60 + ts % 40)) }
                _ = try await store.insert(Streams(hr: hr), deviceId: "dev")
            }
        }

        func walBytes(_ path: String) -> Int64 {
            let attrs = try? FileManager.default.attributesOfItem(atPath: path + "-wal")
            return (attrs?[.size] as? NSNumber)?.int64Value ?? 0
        }

        let autoStore = try await WhoopStore(path: autoPath, walCheckpointing: .automatic)
        try await writeManySmallTransactions(autoStore)

        let extStore = try await WhoopStore(path: extPath, walCheckpointing: .external)
        try await writeManySmallTransactions(extStore)

        let autoWal = walBytes(autoPath)
        let extWal = walBytes(extPath)

        XCTAssertGreaterThan(
            extWal, autoWal * 2,
            "with autocheckpoint off the WAL must grow far past the self-checkpointing one "
            + "(external=\(extWal) bytes, automatic=\(autoWal) bytes)")
        // And the store still works: nothing about disabling checkpointing may change stored data.
        let counts = try await extStore.storageStats_rowCountsForTest()
        XCTAssertEqual(counts.hr, transactions * rowsPerTransaction,
                       "disabling autocheckpoint must not change what is stored")
    }

    /// An explicit `checkpointWAL()` must still reclaim the WAL when autocheckpoint is off — this is
    /// the safety valve an `.external` caller needs when its replicator is not running.
    func testExplicitCheckpointStillWorksWithExternalMode() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let store = try await WhoopStore(path: path, walCheckpointing: .external)
        var ts = 0
        for _ in 0..<40 {
            var hr: [HRSample] = []
            for _ in 0..<200 { ts += 1; hr.append(HRSample(ts: ts, bpm: 60 + ts % 40)) }
            _ = try await store.insert(Streams(hr: hr), deviceId: "dev")
        }

        func walBytes() -> Int64 {
            let attrs = try? FileManager.default.attributesOfItem(atPath: path + "-wal")
            return (attrs?[.size] as? NSNumber)?.int64Value ?? 0
        }
        let grown = walBytes()
        XCTAssertGreaterThan(grown, 0, "WAL should have accumulated with autocheckpoint off")

        try await store.checkpointWAL()
        XCTAssertLessThan(walBytes(), grown, "explicit checkpointWAL() must still truncate the WAL")
    }
}
