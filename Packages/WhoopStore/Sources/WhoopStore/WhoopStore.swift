import Foundation
import GRDB
import WhoopProtocol

/// OpenWhoop persistence library — decoded streams are durable; raw frames are a
/// transient, compressed, prunable outbox. Built on GRDB/SQLite.
public enum WhoopStoreInfo {
    /// Bumped whenever the migrator gains a new migration.
    public static let schemaVersion = 18
}

/// Who is responsible for checkpointing the WAL back into the main database file.
///
/// SQLite's `wal_autocheckpoint` is a **per-connection** setting, not a database property, so this
/// is applied inside `Configuration.prepareDatabase` and therefore reaches every connection a
/// `DatabasePool` opens.
///
/// ## Why this exists
///
/// A page-level replicator (Litestream and its embeddable Rust port, `liters`) ships **WAL frames**,
/// so it must observe every transaction. To stop the WAL being restarted underneath it, it holds a
/// long-running read transaction — which starves every *other* checkpointer, including SQLite's own
/// autocheckpoint. If a foreign connection nevertheless succeeds in restarting the WAL, the
/// replicator finds its resume offset overwritten and must fall back to a **full snapshot of the
/// entire database**. On iOS that would be a multi-hundred-megabyte upload, on every app launch.
///
/// Passing `.external` is what lets such a replicator be the single checkpointer.
///
/// ## The obligation `.external` carries — read before using it
///
/// With autocheckpoint off, **nothing in this package bounds WAL growth**. `checkpointWAL()` is only
/// called from bulk-import and backup paths; the hot BLE ingest path never checkpoints. Measured on
/// a NOOP-shaped workload (a 2M-row base, `Collector.flushStandardHR()`'s ~30 s commit cadence, one
/// transaction per flush):
///
/// | cadence | commits/day | WAL after 1 day | after 7 days |
/// |---|---|---|---|
/// | every 10 s | 8,640 | 341 MB | — |
/// | **every 30 s (NOOP today)** | **2,880** | **166 MB** | **1.17 GB** |
/// | every 5 min | 288 | 37 MB | — |
///
/// The cost is driven by the **commit count**, not the data volume: each commit dirties ~14 pages
/// (the file header's change counter, each table's rightmost leaf, each index leaf, the interior
/// spine) whether it carries 30 rows or 300. Only ~11 MB/day of that is new data; the rest is
/// re-written pages that autocheckpoint would normally reclaim.
///
/// So an `.external` caller MUST guarantee a checkpointer actually runs. If it stops running — the
/// user disables sync, the network is down for a week, the replicator is never started, or it
/// crashes on launch — the WAL grows without limit and will eventually exhaust device storage. That
/// is a strictly worse failure than the full re-upload it was meant to avoid.
///
/// ## How that obligation is discharged
///
/// It is **not** left to the caller. Choosing `.external` installs a `WalBackstopMonitor` on the
/// pool's writer connection: one `stat(2)` of the `-wal` sibling per committed transaction, and a
/// forced `wal_checkpoint(TRUNCATE)` once it crosses `WalBackstopPolicy.ceilingBytes` (64 MiB by
/// default — see `WalBackstopPolicy` for how that number was chosen). Growth is therefore bounded
/// even when the replicator never runs at all.
///
/// Opting out is possible (`walBackstop: .disabled`) but is an assertion that something else bounds
/// the WAL. Nothing in this package does.
///
/// ## The multi-pool caveat, which is load-bearing
///
/// `StoreOpenGate` gives every opener its **own** `DatabasePool` on the same file — in this app both
/// `Repository` and `BLEManager` open one (see `StoreOpenGate`'s note). `wal_autocheckpoint` is
/// per-connection, so a second store opened with `.automatic` on the same file will happily restart
/// the WAL under a replicator that the first store's `.external` was protecting. **Every** opener of
/// a replicated file must pass `.external`, or the option does nothing.
public enum WalCheckpointing: Sendable {
    /// SQLite's default: each connection auto-checkpoints at ~1000 WAL pages (~4 MB). This is the
    /// behaviour of every build that does not opt out, and the only behaviour upstream ships.
    case automatic
    /// Disable `wal_autocheckpoint` on every connection this store opens, because an external
    /// component checkpoints instead. See the obligation documented above — which the
    /// `walBackstop` parameter of `init(path:walCheckpointing:walBackstop:)` discharges by default.
    case external
}

/// Serializes `DatabasePool` creation + migration so two concurrent opens of the SAME file can never
/// run their GRDB migrators at once (#261).
///
/// `WhoopStore(path:)` is opened from more than one place on the same file — the BLEManager's backfill
/// store and the app's MetricsRepository (see the `init(path:)` note). On the first launch after an
/// update that adds a migration, a cold *background* relaunch (iOS CoreBluetooth state restoration) can
/// fire both opens at once. Each `DatabaseMigrator` reads "migration N unapplied", both apply it, and
/// the loser's bookkeeping `INSERT` collides: `UNIQUE constraint failed: grdb_migrations.identifier`
/// (SQLITE_CONSTRAINT). That open throws — on iOS the backfill sees "store not ready" and the offload
/// is deferred to the next tick. It self-heals once one migrator commits, but the failed open is a
/// real, user-visible sync stall.
///
/// `openAndMigrate` is actor-isolated and fully synchronous (no `await` inside), so the actor's serial
/// executor runs exactly one open+migrate to completion before starting the next — closing the race at
/// the source, for every opener present and future, not just the two we know about. Opens are
/// launch-time-rare and a fully-migrated DB migrates nothing, so the serial gate costs nothing in
/// practice. Each caller still gets its OWN pool; only the open+migrate step is serialized.
private actor StoreOpenGate {
    static let shared = StoreOpenGate()

    func openAndMigrate(path: String, configuration config: Configuration) throws -> DatabasePool {
        // Self-heal a foreign DB left in place by a bad cross-platform restore (#222): an Android
        // (Room) backup that slipped past the import guard replaces our file with one that has our
        // data tables but NO `grdb_migrations` bookkeeping. The migrator then thinks nothing is
        // applied, re-runs v1, and crashes with `table "device" already exists` on every open — the
        // store never bootstraps. Quarantine such a file BEFORE opening so we start fresh instead of
        // looping forever. (A normal GRDB backup carries grdb_migrations and is left untouched.)
        WhoopStore.quarantineIncompatibleDatabase(at: path)
        let pool = try DatabasePool(path: path, configuration: config)
        try WhoopStore.makeMigrator().migrate(pool)
        return pool
    }
}

/// WhoopStore is an `actor`: its public API is `async`, and all GRDB work runs on the
/// actor's serial executor rather than the caller's (the main actor).
///
/// The connection is a GRDB `DatabasePool` (WAL): reads (`.read`) run CONCURRENTLY with the
/// backfill's bulk writes (`.write`) instead of serializing behind them (#755). A `DatabaseQueue`
/// funnels every read AND write through one serial executor, so the dashboard's ~40-55 reads
/// queued behind a multi-thousand-row import and froze Today for seconds. A Pool keeps a single
/// writer (writes still serialize, exactly as before, so every read-modify-write inside one
/// `.write` stays atomic) but serves reads from WAL snapshots in parallel (committed data only,
/// never a partial write). The actor still moves the synchronous-blocking GRDB calls off the
/// caller's (main) thread; what changed is read/write CONCURRENCY at the SQLite layer, not the
/// data or the query results.
public actor WhoopStore {

    /// v18 aux rows banked since the retention sweep last ran, PER DEVICE — the sweep is per device too,
    /// so a shared counter would let one strap spend another's budget. See `StreamStore`.
    var v18AuxRowsSincePrune: [String: Int] = [:]
    let dbWriter: any DatabaseWriter

    /// Read-only handle to the underlying GRDB writer for the synchronous `DeviceRegistryStore`.
    /// `nonisolated` because a GRDB `DatabaseWriter` (here a `DatabasePool`) is `Sendable` and
    /// manages its own concurrency, so concurrent access alongside the actor's own DB work is safe
    /// (the Pool serializes writes and runs reads in parallel under WAL).
    public nonisolated var registryWriter: any DatabaseWriter { dbWriter }

    /// Bounds WAL growth when `walCheckpointing` is `.external`. `nil` for `.automatic` (SQLite's own
    /// autocheckpoint is the bound) and for a store with no backstop policy. Held strongly here
    /// because GRDB's `.observerLifetime` registration keeps only a weak reference — this property is
    /// what keeps the observer alive for the life of the store.
    let walBackstop: WalBackstopMonitor?

    /// The checkpointing mode this store actually opened with, whether it came from an explicit
    /// argument or from `StoreReplication`. Worth surfacing on a diagnostics screen next to
    /// `walBackstopFirings`: "external + 0 firings" and "automatic" are very different states that
    /// otherwise look identical from outside.
    public nonisolated let walCheckpointing: WalCheckpointing

    private init(dbWriter: any DatabaseWriter,
                 walCheckpointing: WalCheckpointing = .automatic,
                 walBackstop: WalBackstopMonitor? = nil) throws {
        self.dbWriter = dbWriter
        self.walCheckpointing = walCheckpointing
        self.walBackstop = walBackstop
        try WhoopStore.makeMigrator().migrate(dbWriter)
    }

    /// Store an already-open, already-migrated writer WITHOUT re-running the migrator: the
    /// `StoreOpenGate` (below) opened the pool and migrated it under the process-wide open lock, so
    /// re-migrating here would be a redundant (and, if it raced a sibling opener, failing) second run.
    /// (#261)
    private init(preMigrated dbWriter: any DatabaseWriter,
                 walCheckpointing: WalCheckpointing = .automatic,
                 walBackstop: WalBackstopMonitor? = nil) {
        self.dbWriter = dbWriter
        self.walCheckpointing = walCheckpointing
        self.walBackstop = walBackstop
    }

    /// Open (creating if needed) a database at `path` and run migrations.
    /// Uses a `DatabasePool`, which enables WAL automatically, plus a 5-second busy timeout so two
    /// handles to the same file (BLEManager + MetricsRepository) don't deadlock on write contention.
    ///
    /// Open + migrate runs through `StoreOpenGate` so two concurrent openers of the SAME file never
    /// run their GRDB migrators at once (#261) — see that actor's note for the failure it prevents.
    ///
    /// Both checkpointing parameters default to the process-wide `StoreReplication` policy, which is
    /// itself `.automatic` + `.standard` unless something called `StoreReplication.configure`. So a
    /// build that never configures replication behaves exactly as every build does today.
    ///
    /// The default deliberately comes from a process-wide value rather than being written out at each
    /// call site: `wal_autocheckpoint` is per-connection, this app opens the same file from two
    /// independent places, and an opener that forgets `.external` silently defeats it for all the
    /// others. See `StoreReplication` for the full argument. Only an embedder that has taken over
    /// checkpointing should select `.external` — see `WalCheckpointing` for the obligation that
    /// carries. Passing the arguments explicitly still overrides the policy, which is what tests do.
    ///
    /// `walBackstop` is inert under `.automatic` (SQLite's autocheckpoint already bounds the WAL) and
    /// is what discharges `.external`'s obligation: it forces a checkpoint if the `-wal` sibling
    /// crosses its ceiling no matter what the external replicator is doing. Pass `.disabled` only if
    /// something outside this package provably bounds WAL growth.
    public init(path: String,
                walCheckpointing: WalCheckpointing = StoreReplication.walCheckpointing,
                walBackstop: WalBackstopPolicy = StoreReplication.walBackstop) async throws {
        var config = Configuration()
        config.prepareDatabase { db in
            // `DatabasePool` puts the database in WAL mode itself (reads run as concurrent snapshots
            // alongside the single writer, #755), so there is no explicit `PRAGMA journal_mode = WAL`.
            // Bulk-write/read tuning. NORMAL is the durable, recommended pairing with WAL (only an
            // OS crash/power loss can lose the last transaction — acceptable here). Bigger page cache
            // + mmap + in-memory temp tables speed the multi-thousand-row import/backfill writes.
            try db.execute(sql: "PRAGMA synchronous = NORMAL")
            try db.execute(sql: "PRAGMA cache_size = -16000")     // ~16 MB page cache
            try db.execute(sql: "PRAGMA mmap_size = 268435456")   // 256 MB memory-mapped I/O
            try db.execute(sql: "PRAGMA temp_store = MEMORY")
            // `wal_autocheckpoint` is a PER-CONNECTION setting, so this has to run inside
            // `prepareDatabase` — it is applied to every connection the pool opens (the writer and
            // each reader), not once at open. Setting it anywhere else would leave the pool's other
            // connections still auto-checkpointing, which is the exact bug this guards against.
            if case .external = walCheckpointing {
                try db.execute(sql: "PRAGMA wal_autocheckpoint = 0")
            }
        }
        config.busyMode = .timeout(5)
        let pool = try await StoreOpenGate.shared.openAndMigrate(path: path, configuration: config)

        // Only `.external` needs a backstop: under `.automatic` SQLite's own autocheckpoint already
        // bounds the WAL, and installing an observer there would be pure cost for upstream builds.
        var monitor: WalBackstopMonitor?
        if case .external = walCheckpointing, walBackstop.isEnabled {
            let m = WalBackstopMonitor(databasePath: path, policy: walBackstop)
            m.attach(to: pool)
            monitor = m
        }
        // Recorded after the open succeeds, so a `configure` that lands between a failed open and a
        // successful retry is not reported as late. See `StoreReplication.configuredAfterFirstOpen`.
        StoreReplication.noteStoreOpened()
        self.init(preMigrated: pool, walCheckpointing: walCheckpointing, walBackstop: monitor)
    }

    /// Move aside a database file that has our data tables but no GRDB migration bookkeeping — the
    /// signature of a foreign (Android/Room) DB dropped over ours by a bad restore (#222). Opening it
    /// would make the migrator re-run v1 and throw `table "device" already exists` forever. Moving it
    /// to a `.incompatible-<ts>` sidecar lets the next open create a clean store. A valid GRDB DB
    /// (has `grdb_migrations`) and a fresh/empty file are both left untouched. Best-effort + silent.
    static func quarantineIncompatibleDatabase(at path: String) {
        let fm = FileManager.default
        guard fm.fileExists(atPath: path) else { return }
        let names: Set<String>
        do {
            // Read-only probe of sqlite_master; a raw queue does NOT run migrations.
            let probe = try DatabaseQueue(path: path)
            names = try probe.read { db in
                try Set(String.fetchAll(db, sql: "SELECT name FROM sqlite_master WHERE type = 'table'"))
            }
        } catch {
            return // unreadable/locked → let the real open + migrator deal with it
        }
        let isForeign = !names.contains("grdb_migrations")
            && (names.contains("device") || names.contains("hrSample"))
        guard isForeign else { return }
        let stamp = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "")
        let quarantine = "\(path).incompatible-\(stamp)"
        try? fm.removeItem(atPath: quarantine)
        do { try fm.moveItem(atPath: path, toPath: quarantine) } catch { return }
        // Drop the now-orphaned WAL/SHM sidecars so the fresh DB starts clean.
        for suffix in ["-wal", "-shm"] { try? fm.removeItem(atPath: path + suffix) }
    }

    /// An in-memory store (migrations applied). For tests.
    ///
    /// Backed by a `DatabaseQueue`, not a `DatabasePool`: GRDB has no in-memory `DatabasePool`
    /// (a Pool needs a real file so its reader connections can open WAL snapshots of it). A
    /// `DatabaseQueue` is also a `DatabaseWriter`, so this is API-identical; only the concurrency
    /// differs, which an in-memory test store doesn't exercise. The production `init(path:)` path
    /// is the one that gets the Pool (#755). Tests that need real read/write concurrency open a
    /// file-backed Pool directly.
    public static func inMemory() async throws -> WhoopStore {
        try WhoopStore(dbWriter: try DatabaseQueue())
    }

    // MARK: - Synchronous GRDB helpers
    // GRDB 6 marks its sync read/write overloads @_disfavoredOverload so that in an async
    // context Swift would otherwise pick the async overloads. These thin wrappers are
    // regular (non-async) functions, so overload resolution always selects the synchronous
    // GRDB API — which then blocks on the actor's serial executor (off main thread).

    @inline(__always)
    func syncRead<T>(_ block: (Database) throws -> T) throws -> T {
        try dbWriter.read(block)
    }

    @inline(__always)
    func syncWrite<T>(_ block: (Database) throws -> T) throws -> T {
        try dbWriter.write(block)
    }

    // MARK: - Maintenance

    /// Fully checkpoint the WAL into the main database file and truncate the -wal file.
    /// Used before a file-level backup so the single `whoop.sqlite` carries all committed data
    /// (the -wal/-shm siblings can then be ignored). Runs outside a transaction — `wal_checkpoint`
    /// must. Best-effort: throws on a hard SQLite error so callers can fall back to a plain copy.
    public func checkpointWAL() async throws {
        try checkpointWALImpl()
    }

    /// Non-async so GRDB's synchronous `writeWithoutTransaction` overload is chosen (mirrors the
    /// syncRead/syncWrite pattern). Runs on the actor's executor, off the main thread.
    private func checkpointWALImpl() throws {
        try dbWriter.writeWithoutTransaction { db in
            try db.execute(sql: "PRAGMA wal_checkpoint(TRUNCATE)")
        }
    }

    /// Write a complete, consistent, single-file copy of this database at `path` — **without
    /// checkpointing the live one.**
    ///
    /// ## Why a file-level backup cannot just checkpoint when a replicator owns the WAL
    ///
    /// A `.noopbak` archives the main `.sqlite` alone, with no `-wal` sidecar, so a backup has to get
    /// every committed page into one file somehow. The obvious way is `checkpointWAL()`. Under
    /// `WalCheckpointing.external` that is exactly what must not happen, and — this is the part that
    /// is easy to get wrong — **a gentler checkpoint mode does not help.**
    ///
    /// Measured against a real `liters` writer and Apple's `libsqlite3` (`LitersRoundTripTests`,
    /// 2026-07-28), with no replicator read lock held:
    ///
    /// | foreign checkpoint | `-wal` afterwards | next push |
    /// |---|---|---|
    /// | `TRUNCATE` | 0 bytes | **snapshot**, "wal truncated by another process" |
    /// | `FULL` | 168,952 bytes — unchanged | **snapshot**, same reason |
    ///
    /// `FULL` looks harmless because the `-wal` file keeps its size, and it is not: once a checkpoint
    /// has fully backfilled the WAL and no reader still needs those frames, **SQLite restarts the WAL
    /// on the next write transaction** — new salt, frame 1 — and the replicator's resume offset is
    /// gone just the same. `PASSIVE` has the identical endpoint whenever it happens to complete. The
    /// property that matters is not the pragma's name; it is "did the WAL end up fully backfilled
    /// with nothing pinning it", and every checkpoint mode reaches that state.
    ///
    /// ## What this does instead
    ///
    /// SQLite's Online Backup API (via GRDB's `backup(to:)`) reads the source through a read
    /// transaction, so it sees the WAL's contents and copies them into the destination — and it never
    /// checkpoints, never restarts, and never touches the source's `-wal` at all. The replicator's
    /// resume point survives untouched, and the caller gets a single file that carries every
    /// committed row.
    ///
    /// The cost is one full-size write. That is why this is not the default path: under `.automatic`
    /// a checkpoint is free and correct, and this is reserved for the case where a checkpoint is not
    /// available (see `CloudSyncUploader.defaultExporter`, its only production caller).
    ///
    /// Removes `path` and its `-wal`/`-shm` siblings first, and leaves no sidecars behind, so the
    /// result is one self-contained file ready to archive.
    public func writeConsistentCopy(to path: String) async throws {
        try writeConsistentCopyImpl(to: path)
    }

    /// Non-async for the same reason `checkpointWALImpl` is: it runs on the actor's executor, off the
    /// main thread, and GRDB's calls here are synchronous and blocking.
    private func writeConsistentCopyImpl(to path: String) throws {
        let fm = FileManager.default
        for suffix in ["", "-wal", "-shm"] { try? fm.removeItem(atPath: path + suffix) }

        let destination = try DatabaseQueue(path: path)
        try dbWriter.backup(to: destination)
        // Close before anyone reads the file: GRDB flushes on close, and the caller's next act is to
        // hand this path to a ZIP writer. `close()` throws only if a statement is still live, which
        // cannot be the case here.
        try destination.close()

        // A `DatabaseQueue` on a fresh file is in rollback-journal mode, but the copied page 1 header
        // carries the source's WAL marker, so a `-wal`/`-shm` pair can be left behind. They hold
        // nothing — everything was flushed by `close()` — and a `.noopbak` must be one file.
        for suffix in ["-wal", "-shm"] { try? fm.removeItem(atPath: path + suffix) }
    }

    /// Permanently delete every recorded sample/derived row for one device across all `deviceId`-keyed
    /// tables (16+ `DELETE FROM <table> WHERE deviceId = ?` in one GRDB transaction). Wraps the
    /// synchronous `DeviceRegistryStore.deleteAllData` so the heavy multi-table write runs on the actor's
    /// own serial executor, OFF the main thread. The "Delete all of this device's data" and "Remove
    /// Apple Health data" actions previously ran this same store write synchronously on the main actor and
    /// froze the UI on a large dataset. The `pairedDevice` registry row is left intact (archiving/removing
    /// it is a separate op). Async entry point; the actual write is on `deleteAllDataImpl`.
    public func deleteAllData(deviceId: String) async throws {
        try deleteAllDataImpl(deviceId: deviceId)
    }

    /// Non-async so the synchronous `DeviceRegistryStore.deleteAllData` (a blocking GRDB write) is called
    /// directly (mirrors the syncRead/syncWrite pattern). Runs on the actor's executor, off the main
    /// thread. Builds the synchronous registry wrapper over the same GRDB writer the store owns.
    private func deleteAllDataImpl(deviceId: String) throws {
        try DeviceRegistryStore(dbQueue: dbWriter).deleteAllData(deviceId: deviceId)
    }

    /// Total on-disk size of the database — the main file plus its `-wal`/`-shm` siblings — in bytes.
    /// Drives the iOS Storage diagnostics screen (#590). `nil` for an in-memory store (no path). Runs
    /// on the actor's executor, off the main thread. Note (#755): under the `DatabasePool` the `-wal`
    /// component can stay non-zero while a reader connection holds an open snapshot, so a `checkpointWAL`
    /// may not fully truncate it; this total stays correct (it always includes the sidecars) but can
    /// read a little higher than the old single-connection `DatabaseQueue` did right after a checkpoint.
    public func databaseFileSizeBytes() async -> Int64? {
        let base = dbWriter.path
        guard base != ":memory:", !base.isEmpty else { return nil }
        let fm = FileManager.default
        var total: Int64 = 0
        var found = false
        for suffix in ["", "-wal", "-shm"] {
            let path = base + suffix
            if let size = (try? fm.attributesOfItem(atPath: path))?[.size] as? NSNumber {
                total += size.int64Value
                found = true
            }
        }
        return found ? total : nil
    }

    // MARK: - WAL backstop

    /// Size of the `-wal` sibling alone, in bytes; `0` when there is no WAL and `nil` for an
    /// in-memory store. A single `stat(2)` — cheap enough to poll. Unlike `databaseFileSizeBytes()`
    /// this isolates the component that `WalCheckpointing.external` puts at risk.
    public nonisolated func walFileSizeBytes() -> Int64? {
        let base = dbWriter.path
        guard base != ":memory:", !base.isEmpty else { return nil }
        var st = stat()
        guard (base + "-wal").withCString({ stat($0, &st) }) == 0 else { return 0 }
        return Int64(st.st_size)
    }

    /// How many times the WAL backstop has forced a checkpoint because the `-wal` crossed its
    /// ceiling. `0` when no backstop is installed (`.automatic`, or `.disabled`).
    ///
    /// **This is the number to watch on a replicated device.** A healthy external replicator keeps
    /// the WAL near its own ~4 MB threshold, so any non-zero value means the replicator stopped
    /// running long enough for the WAL to grow 16× past that — and that each firing may have cost
    /// the replicator its incremental resume point.
    public nonisolated var walBackstopFirings: Int { walBackstop?.firings ?? 0 }

    /// Forced checkpoints that finished with the WAL still over the ceiling — i.e. a `TRUNCATE`
    /// blocked by a pool reader holding an open snapshot. Persistently non-zero means the ceiling is
    /// not actually being enforced and a reader is pinning the WAL.
    public nonisolated var walBackstopIneffectiveAttempts: Int { walBackstop?.ineffectiveAttempts ?? 0 }

    /// The installed backstop, or `nil` when none is (`.automatic`, or `.disabled`). `nonisolated`
    /// for the same reason `registryWriter` is: the monitor does its own locking. Used by tests and
    /// by the fork-side sync telemetry.
    nonisolated var walBackstopMonitor: WalBackstopMonitor? { walBackstop }

    // MARK: - Introspection (used by tests)

    public func tableNames() async throws -> Set<String> {
        try syncRead { db in
            try Set(String.fetchAll(db,
                sql: "SELECT name FROM sqlite_master WHERE type = 'table'"))
        }
    }

    public func primaryKeyColumns(_ table: String) async throws -> [String] {
        try syncRead { db in
            try db.primaryKey(table).columns
        }
    }

    public func columnNamesForTest(table: String) async throws -> [String] {
        try syncRead { db in
            try db.columns(in: table).map(\.name)
        }
    }

    /// True when `column` is NULLABLE and carries NO SQL DEFAULT; nil when the column does not exist.
    /// Migration tests use this to prove an added column is genuinely additive: a NOT NULL or a DEFAULT
    /// would turn "the strap never reported this" into a fabricated value that reads identically to a
    /// real one.
    public func columnIsNullableWithoutDefaultForTest(table: String, column: String) async throws -> Bool? {
        try syncRead { db in
            guard let c = try db.columns(in: table).first(where: { $0.name == column }) else { return nil }
            return !c.isNotNull && c.defaultValueSQL == nil
        }
    }

    public func indexNamesForTest(table: String) async throws -> Set<String> {
        try syncRead { db in
            try Set(db.indexes(on: table).map(\.name))
        }
    }
}
