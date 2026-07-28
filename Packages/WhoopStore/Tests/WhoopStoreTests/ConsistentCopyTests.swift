import XCTest
import GRDB
import WhoopProtocol
@testable import WhoopStore

/// `writeConsistentCopy(to:)` exists because a file-level backup and a page replicator both want to
/// decide what happens to the WAL, and only one of them can. It has to satisfy both:
///
/// 1. **The copy must be whole.** A `.noopbak` is one `.sqlite` with no `-wal` sidecar, so a copy
///    that misses what the WAL held is silent data loss on restore. Asserted by opening the copy
///    somewhere its siblings do not exist and counting rows.
/// 2. **The live WAL must be untouched.** Not "not truncated" — *untouched*. A checkpoint that
///    fully backfills the WAL is just as fatal as one that truncates it, because SQLite restarts a
///    fully-backfilled WAL on the next write, which is what invalidates a replicator's resume
///    offset. So this asserts the `-wal` is byte-identical afterwards, and that a subsequent write
///    still *appends* rather than restarting.
///
/// The end-to-end consequence — that a real `liters` writer ships a delta rather than a snapshot
/// after one of these — is asserted in `LitersRoundTripTests`, which has an actual replicator.
final class ConsistentCopyTests: XCTestCase {

    private var paths: [String] = []

    override func tearDown() {
        for path in paths {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        paths = []
        super.tearDown()
    }

    private func tempPath(_ tag: String = "src") -> String {
        let path = NSTemporaryDirectory() + "whoopstore-copy-\(tag)-\(UUID().uuidString).sqlite"
        paths.append(path)
        return path
    }

    private func fileSize(_ path: String) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: path))?[.size] as? NSNumber)?.int64Value ?? 0
    }

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

    /// Reads the file at `path` and nothing else — the way the server reads an inflated `.noopbak`.
    private func hrRows(inFileAlone path: String) throws -> Int {
        XCTAssertFalse(FileManager.default.fileExists(atPath: path + "-wal"),
                       "a staged copy must leave no -wal sidecar; a .noopbak carries one file")
        let queue = try DatabaseQueue(path: path)
        return try queue.read { db in try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM hrSample") ?? -1 }
    }

    // MARK: - The copy is complete

    /// Everything committed — including the rows that exist only in the WAL, which is most of them
    /// on a replicated store because nothing checkpoints it.
    func testTheCopyCarriesEveryCommittedRowIncludingTheOnesStillInTheWal() async throws {
        let source = tempPath()
        let store = try await WhoopStore(path: source, walCheckpointing: .external, walBackstop: .disabled)
        try await writeTransactions(store, count: 20)

        XCTAssertGreaterThan(store.walFileSizeBytes() ?? 0, 0,
                             "the test is only meaningful if rows are sitting in the WAL")

        let copy = tempPath("dst")
        try await store.writeConsistentCopy(to: copy)

        XCTAssertEqual(try hrRows(inFileAlone: copy), 20 * 400)
    }

    /// The same under `.automatic`, so the method is not quietly coupled to the replication mode.
    func testTheCopyIsCompleteUnderAutomaticCheckpointingToo() async throws {
        let source = tempPath()
        let store = try await WhoopStore(path: source)
        try await writeTransactions(store, count: 10)

        let copy = tempPath("dst")
        try await store.writeConsistentCopy(to: copy)

        XCTAssertEqual(try hrRows(inFileAlone: copy), 10 * 400)
    }

    // MARK: - The live WAL is untouched

    /// THE CORE ASSERTION. Byte-identical, not merely "not empty": the replicator's resume offset is
    /// a position in this file, and any checkpoint — `TRUNCATE`, `FULL`, `PASSIVE` — ends with the
    /// WAL fully backfilled and about to be restarted by the next write.
    func testTheCopyDoesNotTouchTheLiveWal() async throws {
        let source = tempPath()
        let store = try await WhoopStore(path: source, walCheckpointing: .external, walBackstop: .disabled)
        try await writeTransactions(store, count: 20)

        let walBefore = fileSize(source + "-wal")
        let mainBefore = fileSize(source)
        XCTAssertGreaterThan(walBefore, 0)

        let copy = tempPath("dst")
        try await store.writeConsistentCopy(to: copy)

        XCTAssertEqual(fileSize(source + "-wal"), walBefore,
                       "the live -wal must be byte-identical — this is the replicator's transport")
        XCTAssertEqual(fileSize(source), mainBefore,
                       "the live database file must not be backfilled either; a fully-backfilled WAL "
                       + "is restarted by the next write, which is what costs a replicator its resume point")
    }

    /// The consequence of the assertion above, stated as behaviour rather than as file sizes: writes
    /// after a copy still *append* to the WAL. If the copy had checkpointed, the next write would
    /// restart it and the `-wal` would shrink.
    func testWritesAfterACopyStillAppendToTheSameWal() async throws {
        let source = tempPath()
        let store = try await WhoopStore(path: source, walCheckpointing: .external, walBackstop: .disabled)
        var ts = try await writeTransactions(store, count: 20)

        let copy = tempPath("dst")
        try await store.writeConsistentCopy(to: copy)
        let walAfterCopy = fileSize(source + "-wal")

        _ = try await writeTransactions(store, count: 5, startingAt: ts)
        ts += 1

        XCTAssertGreaterThan(fileSize(source + "-wal"), walAfterCopy,
                             "the WAL must have grown, not restarted")
    }

    // MARK: - Housekeeping

    /// Called once per fallback sync, forever, over a path the caller reuses on failure paths. An
    /// existing file (and any stale sidecars) must be replaced, not appended to or refused.
    func testWritingOverAnExistingCopyReplacesIt() async throws {
        let source = tempPath()
        let store = try await WhoopStore(path: source, walCheckpointing: .external, walBackstop: .disabled)
        try await writeTransactions(store, count: 5)

        let copy = tempPath("dst")
        try await store.writeConsistentCopy(to: copy)
        XCTAssertEqual(try hrRows(inFileAlone: copy), 5 * 400)

        _ = try await writeTransactions(store, count: 5, startingAt: 5 * 400)
        try await store.writeConsistentCopy(to: copy)
        XCTAssertEqual(try hrRows(inFileAlone: copy), 10 * 400,
                       "the second copy must replace the first, not be refused because the file exists")
    }
}
