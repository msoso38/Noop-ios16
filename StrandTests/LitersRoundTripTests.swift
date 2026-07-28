// The liters round trip, run against real SQLite files on disk.
//
// Gated on BOTH flags. CLOUD_SYNC because this is fork-local sync work; LITERS because the symbols
// come from Liters.xcframework, which `Rust/build-ios.sh` produces and which is deliberately not
// tracked (see Config/Liters.xcconfig). A checkout that has never built the Rust side compiles none
// of this rather than failing to link.
#if CLOUD_SYNC && LITERS
import XCTest
import SQLite3
import WhoopProtocol
import WhoopStore
@testable import Strand

/// End-to-end proof that the Rust archive in `Liters.xcframework` actually replicates a database,
/// rather than merely linking.
///
/// ## Why these tests open SQLite through `import SQLite3` and not GRDB
///
/// The single load-bearing decision in `Rust/Cargo.toml` is `default-features = false`, which makes
/// liters link the *platform* `libsqlite3` instead of compiling its own copy of the amalgamation.
/// The reason is that two SQLite libraries in one process do not share the process-global
/// `unixInodeInfo` table SQLite uses to work around POSIX's "close any descriptor, lose all locks"
/// rule, so either can silently drop the other's advisory locks — and liters' correctness rests on a
/// long-running read lock.
///
/// `import SQLite3` resolves to exactly the `libsqlite3.dylib` GRDB links through
/// `.systemLibrary(name: "CSQLite")`. So these tests are not a stand-in for the app's arrangement;
/// they *are* it: one process, one SQLite, opened from Swift on one side and from Rust on the other,
/// against the same file. A test written through GRDB would prove the same thing less directly and
/// drag a package dependency into a target that does not otherwise need one.
///
/// ## Why a real file and not an in-memory database
///
/// liters replicates WAL frames. An in-memory database has no WAL, no `-wal` sidecar, and no file
/// locks, so it would exercise none of the machinery under test and would pass whether or not the
/// linkage were correct.
final class LitersRoundTripTests: XCTestCase {

    private var dir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("liters-rt-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let dir { try? FileManager.default.removeItem(at: dir) }
        try super.tearDownWithError()
    }

    private var originPath: String { dir.appendingPathComponent("origin.sqlite").path }
    private var bucketPath: String { dir.appendingPathComponent("bucket", isDirectory: true).path }
    private var replicaPath: String { dir.appendingPathComponent("replica.sqlite").path }

    // MARK: - The writer, which is the half the phone is

    /// A push captures the database's committed content and uploads it.
    ///
    /// This is the whole premise in one test: if the Swift bindings, the UniFFI scaffolding, the Rust
    /// archive, and the platform `libsqlite3` are all wired correctly, a database written through
    /// Swift's SQLite becomes LTX files in a bucket that only Rust ever wrote. Any break in that
    /// chain fails here rather than three integration layers later.
    func testWriterPushesCommittedContent() throws {
        try makeOriginDatabase(rows: 1...50)
        // Establishes the baseline the replica half will be compared against once liters' restore
        // works under system SQLite (see testReplicaRestoreIsBrokenUnderSystemSQLite), and proves the
        // fixture really is 50 committed rows rather than an empty file liters would happily push.
        XCTAssertEqual(try readIds(at: originPath), Array(1...50))

        let writer = try LitersWriter(dbPath: originPath, storage: .dir(path: bucketPath))
        defer { writer.close() }
        let push = try writer.push()

        XCTAssertTrue(push.synced, "the first push must capture the database's committed content")
        XCTAssertGreaterThan(push.uploaded, 0, "the first push must upload at least one LTX file")
        XCTAssertGreaterThan(push.bytesUploaded, 0)
        XCTAssertGreaterThan(push.txid, 0)
        XCTAssertGreaterThan(push.remoteTxid, 0, "the bucket must know about the push that just happened")
    }

    /// The second push must ship a WAL delta, not another copy of the database.
    ///
    /// `snapshotted` is the number the whole trial exists to measure. A replicator that snapshots on
    /// every push is a full upload with extra steps, which is precisely what the current `/ingest`
    /// path already does. Asserting it is `false` for a plain "write more rows, push again" is the
    /// minimum bar; anything less and there is no reason to adopt this at all.
    func testASecondPushIsIncrementalAndNotASnapshot() throws {
        try makeOriginDatabase(rows: 1...10)

        let writer = try LitersWriter(dbPath: originPath, storage: .dir(path: bucketPath))
        defer { writer.close() }
        let first = try writer.push()
        XCTAssertTrue(first.synced)

        try appendRows(11...20, to: originPath)
        let second = try writer.push()

        XCTAssertFalse(second.snapshotted,
                       "an ordinary follow-up push must ship a WAL delta. reason: \(second.snapshotReason ?? "nil")")
        XCTAssertGreaterThan(second.txid, first.txid)
    }

    // MARK: - The `/ingest` fallback, which shares the file with the replicator

    /// **The regression this group exists for**, and the measurement that decided its shape.
    ///
    /// `CloudSyncUploader`'s `/ingest` fallback used to checkpoint the live store before archiving
    /// it. That checkpoint runs on exactly the syncs where the page-replication push did not — a push
    /// that failed to open a writer at all, or a launch where the app foregrounds and backs up before
    /// it syncs — so it lands in the one window where no replicator read lock is holding the WAL.
    ///
    /// Both modes are asserted because the gentler one is the trap. `FULL` leaves the `-wal` file at
    /// its full size, which looks harmless and is not: once the WAL is fully backfilled and no reader
    /// needs those frames, SQLite restarts it on the **next write transaction**, new salt and all.
    /// The replicator's resume offset is just as gone as after a `TRUNCATE`.
    ///
    /// This is VK's device failure reproduced: 2026-07-28, `/ingest` at 06:03, then a push at 14:23
    /// reporting `wal truncated by another process` and uploading 640 MB.
    func testAnyForeignCheckpointCostsTheNextPushASnapshot() throws {
        for mode in ["TRUNCATE", "FULL"] {
            let scratch = dir.appendingPathComponent("ckpt-\(mode)", isDirectory: true)
            try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
            let db = scratch.appendingPathComponent("origin.sqlite").path
            let bucket = scratch.appendingPathComponent("bucket", isDirectory: true).path

            try makeDatabase(at: db, rows: 1...200)
            let first = try LitersWriter(dbPath: db, storage: .dir(path: bucket))
            _ = try first.push()
            try appendRows(201...210, to: db)
            // Closed, because that is the window the fallback's checkpoint actually runs in. With the
            // writer open its read lock blocks a foreign checkpoint outright — which protects the
            // replicator but leaves the archive missing rows, so it is not a fix either.
            first.close()

            try foreignCheckpoint(mode, at: db)
            // The next write is what converts a backfilled WAL into a restarted one.
            try appendRows(211...220, to: db)

            let second = try LitersWriter(dbPath: db, storage: .dir(path: bucket))
            defer { second.close() }
            let push = try second.push()

            XCTAssertTrue(push.snapshotted,
                          "\(mode): a completed foreign checkpoint must cost the replicator its resume "
                          + "point — if this stops being true, liters changed and the staged-copy export "
                          + "may no longer be needed")
            print("[liters] foreign \(mode): snapshotted=\(push.snapshotted) "
                  + "reason=\(push.snapshotReason ?? "nil") bytes=\(push.bytesUploaded)")
        }
    }

    /// **The fix, end to end.** The identical sequence, with the export staged through
    /// `WhoopStore.writeConsistentCopy(to:)` — SQLite's Online Backup API, which reads through the
    /// WAL and never checkpoints — instead of a checkpoint. The next push ships a delta.
    ///
    /// Through a real `WhoopStore` rather than raw `SQLite3` on purpose: GRDB's `DatabasePool` is
    /// what actually holds this file in the app, it links the same platform `libsqlite3` liters does
    /// (see the type doc), and the copy has to survive a pool's connections, not a single handle's.
    func testAStagedConsistentCopyDoesNotCostTheNextPushASnapshot() async throws {
        let db = dir.appendingPathComponent("store.sqlite").path
        let store = try await WhoopStore(path: db, walCheckpointing: .external, walBackstop: .disabled)
        try await insertHR(store, count: 20)

        let first = try LitersWriter(dbPath: db, storage: .dir(path: bucketPath))
        let firstPush = try first.push()
        XCTAssertTrue(firstPush.synced)
        try await insertHR(store, count: 2, startingAt: 20 * 400)
        first.close()

        // The export step, in the same window the checkpoint above ran in.
        let staged = dir.appendingPathComponent("staged.sqlite").path
        try await store.writeConsistentCopy(to: staged)
        try await insertHR(store, count: 2, startingAt: 22 * 400)

        let second = try LitersWriter(dbPath: db, storage: .dir(path: bucketPath))
        defer { second.close() }
        let push = try second.push()

        XCTAssertFalse(push.snapshotted,
                       "staging a copy must leave the replicator's resume point intact. reason: "
                       + "\(push.snapshotReason ?? "nil")")
        XCTAssertGreaterThan(push.txid, firstPush.txid)
        // The size claim, against the only honest yardstick available: the FIRST push, which was a
        // snapshot of this same database. Comparing against the `.sqlite` file's size would be
        // meaningless here — on a replicated store nothing checkpoints, so almost the whole database
        // lives in the `-wal` and the main file stays near empty.
        XCTAssertLessThan(push.bytesUploaded, firstPush.bytesUploaded / 2,
                          "a delta must be a fraction of the snapshot it follows")
        print("[liters] staged copy: snapshotted=\(push.snapshotted) bytes=\(push.bytesUploaded) "
              + "vs first-push snapshot \(firstPush.bytesUploaded)B")
    }

    // MARK: - The iOS-shaped failure mode

    /// Close the writer, reopen it, push. This is what iOS does to the app on every relaunch, and the
    /// design document names it as the one thing that must be measured before committing:
    ///
    /// > the issue-#927 "expected truncation" shortcut is deliberately not trusted across an app
    /// > restart — which on iOS is every single time.
    ///
    /// The test does not assert `snapshotted == false`, because a snapshot here would be *correct*
    /// behaviour, not a bug — liters is refusing to trust a stale shortcut. It asserts the weaker,
    /// genuinely required property: a reopened writer resumes against the same bucket and the replica
    /// still converges. Whether the reopen costs a snapshot in practice is what
    /// `SyncPushTelemetry.snapshotRate` measures on VK's device; recording the observed value here
    /// documents which way it fell on this platform.
    func testWriterReopenResumesAgainstTheSameBucket() throws {
        try makeOriginDatabase(rows: 1...10)

        let first = try LitersWriter(dbPath: originPath, storage: .dir(path: bucketPath))
        let firstPush = try first.push()
        first.close()

        try appendRows(11...25, to: originPath)

        let second = try LitersWriter(dbPath: originPath, storage: .dir(path: bucketPath))
        defer { second.close() }
        let secondPush = try second.push()

        XCTAssertGreaterThan(secondPush.txid, firstPush.txid,
                             "a reopened writer must resume from the bucket, not start over at txid 0")
        XCTAssertTrue(secondPush.synced, "the commits made while closed must be captured on reopen")

        // Not an assertion: the measurement. Printed so a CI log records which way the reopen fell.
        print("[liters] reopen push snapshotted=\(secondPush.snapshotted) reason=\(secondPush.snapshotReason ?? "nil")")
    }

    // MARK: - The replica, which is the half the SERVER is, and which system SQLite breaks

    /// **This test asserts a bug, on purpose.** When it starts failing, liters has been fixed and the
    /// assertion below should be inverted into the real round trip (restore, then compare
    /// `readIds(at: replicaPath)` against the origin's rows).
    ///
    /// `Replica.sync()` cannot restore when liters is linked against the platform `libsqlite3` —
    /// which is the configuration NOOP ships, and the whole point of `default-features = false`.
    /// The cause is `crates/liters/src/replica.rs:648`, the only read-only open in the crate, reached
    /// only from `check_integrity` after a restore:
    ///
    /// ```rust
    /// rusqlite::Connection::open_with_flags(&self.db_path, OpenFlags::SQLITE_OPEN_READ_ONLY)
    /// ```
    ///
    /// Apple's libsqlite3 (3.51.0) cannot run a statement on a WAL-mode database opened
    /// `SQLITE_OPEN_READONLY` when the `-shm` sidecar is absent — and the restore deletes `-wal`/
    /// `-shm` immediately before this call. Reduced to a four-case C program against
    /// `/usr/lib/libsqlite3.dylib`; opening the same file READWRITE, or leaving the sidecars in
    /// place, succeeds. liters' own suite passes 80/80 with its bundled SQLite and 31/80 without,
    /// and every one of those 49 failures is on this path.
    ///
    /// **It does not block the phone.** NOOP is a Writer; `writer.rs` contains no read-only open at
    /// all, and the tests above prove the writer half works in exactly this linkage. The Replica runs
    /// on the server, in a Rust process with no GRDB, which therefore builds liters with bundled
    /// SQLite and never reaches this. The test exists so that "we knew, and here is the boundary"
    /// is a property of the codebase rather than of someone's memory.
    func testReplicaRestoreIsBrokenUnderSystemSQLite() throws {
        try makeOriginDatabase(rows: 1...10)
        let writer = try LitersWriter(dbPath: originPath, storage: .dir(path: bucketPath))
        defer { writer.close() }
        _ = try writer.push()

        let replica = try LitersReplica(dbPath: replicaPath, storage: .dir(path: bucketPath), autoReset: true)
        defer { replica.close() }

        XCTAssertThrowsError(try replica.sync(), "if this no longer throws, liters is fixed — invert this test") { error in
            XCTAssertTrue("\(error)".contains("unable to open database file"),
                          "expected the known SQLITE_CANTOPEN from check_integrity, got: \(error)")
        }
    }

    // MARK: - Errors cross the FFI as errors

    /// A failure has to arrive in Swift as a thrown `LitersError`, not a crash and not a silent
    /// success. UniFFI's error mapping is generated code, so this checks the generated code works for
    /// the one path the app will actually hit at 3am.
    func testOpeningAMissingDatabaseThrows() {
        let missing = dir.appendingPathComponent("nope/absent.sqlite").path
        XCTAssertThrowsError(try LitersWriter(dbPath: missing, storage: .dir(path: bucketPath)))
    }

    // MARK: - SQLite helpers (the platform libsqlite3, the same one GRDB links)

    /// Creates a WAL-mode database and inserts `rows`, through Apple's system SQLite.
    private func makeOriginDatabase(rows: ClosedRange<Int>) throws {
        try makeDatabase(at: originPath, rows: rows)
    }

    private func makeDatabase(at path: String, rows: ClosedRange<Int>) throws {
        var db: OpaquePointer?
        let rc = sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil)
        defer { sqlite3_close(db) }
        guard rc == SQLITE_OK, db != nil else {
            throw SQLiteFailure(code: rc, message: "open \(path)")
        }

        // WAL is not incidental. liters replicates WAL frames, so a rollback-journal database would
        // have nothing for it to ship and the round trip would prove nothing.
        let mode = try queryString(db, "PRAGMA journal_mode = WAL;")
        guard mode == "wal" else { throw SQLiteFailure(code: -1, message: "journal_mode is \(mode)") }
        try exec(db, "CREATE TABLE sample (id INTEGER PRIMARY KEY, payload TEXT NOT NULL);")
        try insert(db, rows)
    }

    /// `PRAGMA wal_checkpoint(<mode>)` from a SECOND connection, which is what
    /// `WhoopStore.checkpointWALForBackup()` is from liters' point of view: a foreign checkpointer on
    /// the same file, while the writer holds its read lock. Returns SQLite's own three-integer answer
    /// — `busy` is 1 when a reader stopped the checkpoint before it finished, and `log`/`checkpointed`
    /// are frames present versus frames transferred into the database file.
    @discardableResult
    private func foreignCheckpoint(_ mode: String, at path: String) throws
        -> (busy: Int, log: Int, checkpointed: Int) {
        var db: OpaquePointer?
        let rc = sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE, nil)
        defer { sqlite3_close(db) }
        guard rc == SQLITE_OK, db != nil else { throw SQLiteFailure(code: rc, message: "reopen \(path)") }

        var stmt: OpaquePointer?
        let sql = "PRAGMA wal_checkpoint(\(mode));"
        let prepared = sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
        guard prepared == SQLITE_OK else { throw SQLiteFailure(code: prepared, message: sql) }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { throw SQLiteFailure(code: -1, message: "no row from \(sql)") }
        return (Int(sqlite3_column_int(stmt, 0)),
                Int(sqlite3_column_int(stmt, 1)),
                Int(sqlite3_column_int(stmt, 2)))
    }

    /// Runs a statement that returns one text column. `PRAGMA journal_mode = WAL` is such a
    /// statement — it reports the mode actually in force, which is the only way to know the switch
    /// took (it silently stays in rollback mode inside a transaction, for instance).
    private func queryString(_ db: OpaquePointer?, _ sql: String) throws -> String {
        var stmt: OpaquePointer?
        let rc = sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
        guard rc == SQLITE_OK else { throw SQLiteFailure(code: rc, message: sql) }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW, let text = sqlite3_column_text(stmt, 0) else {
            throw SQLiteFailure(code: -1, message: "no row from \(sql)")
        }
        return String(cString: text)
    }

    /// Writes through a SECOND connection while the liters writer holds its read lock open. That is
    /// the app's real arrangement — GRDB writing while the replicator watches — and it is the case
    /// the read lock is designed to allow: it blocks foreign *checkpoints*, not foreign writes.
    private func appendRows(_ rows: ClosedRange<Int>, to path: String) throws {
        var db: OpaquePointer?
        let rc = sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE, nil)
        defer { sqlite3_close(db) }
        guard rc == SQLITE_OK, db != nil else { throw SQLiteFailure(code: rc, message: "reopen \(path)") }
        try insert(db, rows)
    }

    private func insert(_ db: OpaquePointer?, _ rows: ClosedRange<Int>) throws {
        try exec(db, "BEGIN;")
        for i in rows {
            // Padded so each row is big enough that a batch spans more than one 4 KB page, which is
            // what makes "the second push shipped a delta" a statement about pages and not about an
            // empty WAL.
            try exec(db, "INSERT INTO sample (id, payload) VALUES (\(i), '\(String(repeating: "x", count: 512))');")
        }
        try exec(db, "COMMIT;")
    }

    /// Reads back every id, ascending. The assertion target for "did the bytes arrive".
    /// One `WhoopStore` transaction per call, mirroring a `Collector` flush — the commit shape whose
    /// *count*, not payload, is what fills the WAL.
    private func insertHR(_ store: WhoopStore, count: Int, rowsEach: Int = 400,
                          startingAt ts0: Int = 0) async throws {
        var ts = ts0
        for _ in 0..<count {
            var hr: [HRSample] = []
            for _ in 0..<rowsEach { ts += 1; hr.append(HRSample(ts: ts, bpm: 60 + ts % 40)) }
            _ = try await store.insert(Streams(hr: hr), deviceId: "dev")
        }
    }

    private func readIds(at path: String) throws -> [Int] {
        var db: OpaquePointer?
        // READWRITE, not READONLY, and that is not arbitrary: Apple's libsqlite3 cannot run a
        // statement on a WAL-mode database opened SQLITE_OPEN_READONLY when the -shm sidecar is
        // absent, which it is right after a liters restore. Opening read-only here would fail with
        // SQLITE_CANTOPEN for reasons that have nothing to do with whether the bytes arrived.
        let rc = sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE, nil)
        defer { sqlite3_close(db) }
        guard rc == SQLITE_OK, db != nil else { throw SQLiteFailure(code: rc, message: "open replica \(path)") }

        var stmt: OpaquePointer?
        let prepared = sqlite3_prepare_v2(db, "SELECT id FROM sample ORDER BY id;", -1, &stmt, nil)
        guard prepared == SQLITE_OK else { throw SQLiteFailure(code: prepared, message: "prepare select") }
        defer { sqlite3_finalize(stmt) }

        var ids: [Int] = []
        while sqlite3_step(stmt) == SQLITE_ROW { ids.append(Int(sqlite3_column_int64(stmt, 0))) }
        return ids
    }

    private func exec(_ db: OpaquePointer?, _ sql: String) throws {
        var err: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(db, sql, nil, nil, &err)
        guard rc == SQLITE_OK else {
            let message = err.map { String(cString: $0) } ?? sql
            sqlite3_free(err)
            throw SQLiteFailure(code: rc, message: message)
        }
    }

    private struct SQLiteFailure: Error, CustomStringConvertible {
        let code: Int32
        let message: String
        var description: String { "sqlite rc \(code): \(message)" }
    }
}
#endif
