import Foundation
import GRDB

/// The size ceiling that bounds WAL growth when `WalCheckpointing.external` has disabled SQLite's
/// own autocheckpoint.
///
/// ## Why a ceiling is mandatory, not optional
///
/// `.external` exists so a page-level replicator (Litestream, or its embeddable Rust port `liters`)
/// can be the *only* checkpointer. That replicator checkpoints from inside its own push loop:
/// `liters` runs a PASSIVE checkpoint once the WAL passes `min_checkpoint_page_n = 1000` pages
/// (~4 MB) and an emergency TRUNCATE at `truncate_page_n = 121_359` pages (~500 MB) — but **both
/// live inside `push()`**. If the replicator never runs, neither fires, and nothing else in the
/// process will: `WhoopStore.checkpointWAL()` is called only from bulk-import and backup paths, and
/// the hot BLE ingest path never checkpoints at all.
///
/// Measured on a NOOP-shaped workload (2M-row base, one transaction per `Collector` flush), each
/// commit dirties ~14 pages regardless of payload, so the cost tracks the **commit count**, not the
/// data volume: at the live lane's ~30 s cadence (2,880 commits/day) the WAL grows ~166 MB/day and
/// ~1.17 GB/week with nothing checkpointing. That is a strictly worse failure than the full
/// re-upload `.external` was adopted to avoid.
///
/// ## Choosing `ceilingBytes` — the trade
///
/// Firing the backstop is not free. A checkpoint taken while the replicator is asleep truncates the
/// WAL out from under its resume offset; on the next push it finds the resume frame gone and may
/// have to upload a **full snapshot** of the whole database. (`liters`' `meta.rs` deliberately does
/// not persist `synced_to_wal_end` across a writer close/reopen, so after an app relaunch a
/// truncation is always "unexpected".) So the ceiling must be high enough never to be reached in
/// healthy operation, and low enough that a dead replicator cannot exhaust storage.
///
/// `standard` is **64 MiB**, chosen against the measured 6.9 MB/hour:
///
/// | property | value | why it matters |
/// |---|---|---|
/// | vs. `liters`' own PASSIVE threshold (~4 MB) | 16× | a *healthy* replicator keeps the WAL near 4 MB, so it never comes close — a firing is evidence of a problem, never routine |
/// | time to reach at 166 MB/day | ~9.3 h | longer than any normal gap between foregrounds/BG refreshes, so it fires zero times in healthy operation |
/// | vs. `liters`' emergency TRUNCATE (~500 MB) | 1/8 | the app-side floor always acts first, which is required: `liters`' threshold cannot act while `liters` is asleep |
/// | vs. the 766 MB production database | 8.4% | peak disk stays ~1.08× the database, not ~1.65× |
/// | TRUNCATE duration | sub-second on iPhone NVMe | cannot strand an app suspension (`0xdead10cc`) |
/// | crash-recovery WAL scan at launch | ≤ ~16k frames | a multi-hundred-MB WAL makes every cold launch pay a full WAL scan |
///
/// Lower ceilings (16–32 MB) fire every 2–5 h and would routinely cost a full snapshot. Higher ones
/// (256 MB+) make cold launch and checkpoint latency user-visible and double peak disk. 64 MiB is
/// the point where the backstop is a genuine backstop: it only ever fires when the thing it is
/// backing up has stopped.
public struct WalBackstopPolicy: Sendable, Equatable {
    /// Force a checkpoint once the `-wal` sibling reaches this many bytes. `0` disables the backstop
    /// entirely — only correct if something else provably bounds the WAL.
    public let ceilingBytes: Int64

    /// After an attempt that did not bring the WAL back under the ceiling — a `TRUNCATE` checkpoint
    /// can be blocked by a `DatabasePool` reader holding an open snapshot — wait at least this long
    /// before trying again, so a pinned WAL cannot turn every commit into a checkpoint attempt.
    public let minRetryInterval: TimeInterval

    public init(ceilingBytes: Int64, minRetryInterval: TimeInterval = 60) {
        self.ceilingBytes = ceilingBytes
        self.minRetryInterval = minRetryInterval
    }

    /// 64 MiB. See the type's documentation for the derivation.
    public static let standard = WalBackstopPolicy(ceilingBytes: 64 * 1024 * 1024)

    /// No backstop. Passing this with `.external` asserts that the caller has another bound on WAL
    /// growth; there is no such bound inside this package.
    public static let disabled = WalBackstopPolicy(ceilingBytes: 0)

    public var isEnabled: Bool { ceilingBytes > 0 }
}

/// The backstop's decision logic, with no I/O and no SQLite — so the interesting behaviour
/// (threshold, in-flight suppression, retry backoff) is unit-testable without a database.
struct WalBackstopCore {
    let policy: WalBackstopPolicy

    private(set) var checkpointInFlight = false
    private(set) var nextAttemptAt: TimeInterval = 0
    /// Number of checkpoints this backstop has started. Telemetry: a non-zero count on a device
    /// running the replicator means the replicator stopped running.
    private(set) var firings = 0
    /// Attempts that finished with the WAL still at or above the ceiling (blocked checkpoint).
    private(set) var ineffectiveAttempts = 0

    init(policy: WalBackstopPolicy) {
        self.policy = policy
    }

    /// Called once per committed transaction with the current `-wal` size.
    /// Returns `true` when the caller should start a checkpoint.
    mutating func shouldCheckpoint(walBytes: Int64, now: TimeInterval) -> Bool {
        guard policy.isEnabled else { return false }
        guard !checkpointInFlight else { return false }
        guard walBytes >= policy.ceilingBytes else { return false }
        guard now >= nextAttemptAt else { return false }
        checkpointInFlight = true
        firings += 1
        return true
    }

    /// Called when the checkpoint started by `shouldCheckpoint` has finished, with the `-wal` size
    /// measured afterwards. A checkpoint that did not get the WAL back under the ceiling arms the
    /// retry backoff instead of letting the next commit try again immediately.
    mutating func didFinishCheckpoint(walBytes: Int64, now: TimeInterval) {
        checkpointInFlight = false
        if walBytes >= policy.ceilingBytes {
            ineffectiveAttempts += 1
            nextAttemptAt = now + policy.minRetryInterval
        } else {
            nextAttemptAt = 0
        }
    }
}

/// Watches the `-wal` file from inside the write path and forces a checkpoint when it crosses the
/// policy ceiling.
///
/// ## Why a `TransactionObserver`
///
/// The trigger has to be somewhere the write path passes through, or the backstop is only as good
/// as the caller's memory. GRDB notifies `databaseDidCommit(_:)` for **every** committed
/// transaction on the connection, regardless of what `observes(eventsOfKind:)` returns — so this
/// observer costs one `stat(2)` per commit (~1 µs) and *nothing* per row: returning `false` from
/// `observes` means GRDB never even installs the SQLite update hook for us.
///
/// A `DatabasePool` only commits on its single writer connection, so exactly one observer fires.
///
/// ## Why the checkpoint is dispatched, not run inline
///
/// `databaseDidCommit` runs on the writer's serial queue inside statement execution. Calling
/// `writeWithoutTransaction` there would re-enter that queue. The checkpoint is therefore handed to
/// a private utility queue, which then blocks on the writer normally — after the commit that
/// triggered it has finished.
final class WalBackstopMonitor: TransactionObserver, @unchecked Sendable {
    private let walPath: String
    private let lock = NSLock()
    private var core: WalBackstopCore
    /// Weak: the pool's writer connection holds this observer (weakly, `.observerLifetime`) and the
    /// store holds it strongly. Capturing the pool strongly here would close a retain cycle through
    /// the store.
    private weak var pool: DatabasePool?
    private let queue = DispatchQueue(label: "whoopstore.wal-backstop", qos: .utility)

    init(databasePath: String, policy: WalBackstopPolicy) {
        self.walPath = databasePath + "-wal"
        self.core = WalBackstopCore(policy: policy)
    }

    /// Installs the observer on `pool`'s writer connection. `.observerLifetime` (GRDB's default)
    /// keeps the connection's reference weak, so the store's strong reference is what keeps this
    /// alive — and there is no cycle.
    func attach(to pool: DatabasePool) {
        self.pool = pool
        pool.add(transactionObserver: self, extent: .observerLifetime)
    }

    /// Size of the `-wal` sibling, or 0 if it does not exist. A raw `stat(2)`, deliberately not
    /// `FileManager.attributesOfItem` — this runs on every commit and must not allocate.
    private func walSizeBytes() -> Int64 {
        var st = stat()
        guard walPath.withCString({ stat($0, &st) }) == 0 else { return 0 }
        return Int64(st.st_size)
    }

    // MARK: Telemetry (read by tests and by the device-trial recorder)

    /// Checkpoints this backstop has forced since the store opened.
    var firings: Int { lock.withLock { core.firings } }
    /// Forced checkpoints that finished with the WAL still over the ceiling.
    var ineffectiveAttempts: Int { lock.withLock { core.ineffectiveAttempts } }
    /// Current `-wal` size in bytes. Safe to call from anywhere.
    var currentWalBytes: Int64 { walSizeBytes() }

    // MARK: - TransactionObserver

    /// `false` for every event kind: this observer wants transaction boundaries, not row changes.
    /// GRDB skips installing the update hook entirely when no observer wants events, so per-row
    /// cost is zero — while `databaseDidCommit` is still delivered for every transaction.
    func observes(eventsOfKind eventKind: DatabaseEventKind) -> Bool { false }

    func databaseDidChange(with event: DatabaseEvent) {}

    func databaseDidCommit(_ db: Database) {
        let now = Date().timeIntervalSinceReferenceDate
        let walBytes = walSizeBytes()
        let start = lock.withLock { core.shouldCheckpoint(walBytes: walBytes, now: now) }
        guard start else { return }
        queue.async { [self] in runCheckpoint() }
    }

    func databaseDidRollback(_ db: Database) {}

    // MARK: - Checkpointing

    /// Mirrors `WhoopStore.checkpointWAL()`: a TRUNCATE checkpoint, run outside any transaction.
    /// Best-effort — under a `DatabasePool` a reader holding an open snapshot can block it, which is
    /// what `minRetryInterval` exists for.
    private func runCheckpoint() {
        if let pool {
            try? pool.writeWithoutTransaction { db in
                try db.execute(sql: "PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
        let after = walSizeBytes()
        let now = Date().timeIntervalSinceReferenceDate
        lock.withLock { core.didFinishCheckpoint(walBytes: after, now: now) }
    }

    /// Runs any pending checkpoint synchronously. Tests only: production fires from `databaseDidCommit`
    /// on a private queue, and this is how a test waits for it deterministically.
    func drainForTest() {
        queue.sync {}
    }
}

private extension NSLock {
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
