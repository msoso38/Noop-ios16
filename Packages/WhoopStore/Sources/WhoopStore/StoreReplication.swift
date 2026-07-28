import Foundation

/// Process-wide default for the WAL-checkpointing parameters of `WhoopStore(path:)`.
///
/// ## The hazard this exists to remove
///
/// `WalCheckpointing.external` is a **per-connection** `PRAGMA wal_autocheckpoint = 0`, so it only
/// achieves anything if *every* connection to the file has it. This app opens the same database from
/// two independent places, each with its own `DatabasePool` — `Repository.ensureStore()` and
/// `BLEManager.bootstrapStore()` (see `StoreOpenGate`) — and both open through the same
/// `WhoopStore(path:)` initialiser.
///
/// Passing `.external` as an argument at those two call sites would work, and would keep working
/// exactly until someone adds a third opener. The failure that follows is silent in the worst way:
/// the new pool auto-checkpoints at ~4 MB, restarts the WAL underneath the replicator, the
/// replicator finds its resume offset overwritten, and every push degrades to a full snapshot of the
/// whole database. Nothing throws. Nothing logs. The only symptom is that the upload never gets
/// smaller — i.e. the exact thing the replicator was adopted to fix, failing in a way that looks
/// like the replicator simply not being very good.
///
/// So the mode is not an argument that call sites remember to pass. It is the **default value** of
/// `WhoopStore(path:)`'s parameters, resolved here at each call. Every opener that exists, and every
/// opener anyone writes later, is correct without knowing this type exists. Explicitly passing
/// `walCheckpointing:` still overrides it, which is what the tests do.
///
/// ## Ordering
///
/// `configure(...)` must run before the first store opens; afterwards it applies only to stores
/// opened later, which is a real (if narrow) way to get a half-configured process. That case is not
/// silent: it sets `configuredAfterFirstOpen`, which a diagnostics screen can read and a test can
/// assert on, and it logs. On iOS the correct call site is the top of `StrandiOSApp.init()`, ahead
/// of `AppModel()` — every store open in this app is `async` and therefore strictly later.
///
/// ## Default
///
/// `.automatic` + `.standard`, i.e. byte-for-byte the behaviour of a build that never calls
/// `configure`. Upstream links this file and gets SQLite's own autocheckpoint, unchanged.
public enum StoreReplication {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var _walCheckpointing: WalCheckpointing = .automatic
    nonisolated(unsafe) private static var _walBackstop: WalBackstopPolicy = .standard
    nonisolated(unsafe) private static var _openCount = 0
    nonisolated(unsafe) private static var _configuredAfterFirstOpen = false

    /// The checkpointing mode a `WhoopStore(path:)` gets when the caller does not pass one.
    public static var walCheckpointing: WalCheckpointing {
        lock.lock(); defer { lock.unlock() }
        return _walCheckpointing
    }

    /// The WAL ceiling policy a `WhoopStore(path:)` gets when the caller does not pass one. Inert
    /// under `.automatic`; under `.external` it is what bounds WAL growth if the replicator stops.
    public static var walBackstop: WalBackstopPolicy {
        lock.lock(); defer { lock.unlock() }
        return _walBackstop
    }

    /// How many stores have been opened through `WhoopStore(path:)` in this process. Only interesting
    /// relative to `configure` — see `configuredAfterFirstOpen`.
    public static var openedStoreCount: Int {
        lock.lock(); defer { lock.unlock() }
        return _openCount
    }

    /// True if `configure` ran after a store had already opened, meaning at least one live pool is
    /// still using the previous mode. Under `.external` that pool will keep auto-checkpointing and
    /// will restart the WAL under the replicator. Surface it; do not let it be inferred from an
    /// unexplained snapshot rate.
    public static var configuredAfterFirstOpen: Bool {
        lock.lock(); defer { lock.unlock() }
        return _configuredAfterFirstOpen
    }

    /// Set the process-wide default. Call once, before any store opens.
    ///
    /// Choosing `.external` is an assertion that something outside this package checkpoints the WAL.
    /// `walBackstop` is what keeps that assertion from being load-bearing — see `WalCheckpointing`.
    public static func configure(walCheckpointing: WalCheckpointing,
                                 walBackstop: WalBackstopPolicy = .standard) {
        lock.lock()
        _walCheckpointing = walCheckpointing
        _walBackstop = walBackstop
        let late = _openCount > 0
        if late { _configuredAfterFirstOpen = true }
        lock.unlock()
        if late {
            NSLog("StoreReplication: configure() ran AFTER %d store(s) were already open — those "
                  + "pools keep their previous checkpointing mode. Under .external they will restart "
                  + "the WAL under the replicator.", openedStoreCount)
        }
    }

    /// Restore the shipped defaults. Tests only — production configures once and never reverts.
    public static func resetForTesting() {
        lock.lock(); defer { lock.unlock() }
        _walCheckpointing = .automatic
        _walBackstop = .standard
        _openCount = 0
        _configuredAfterFirstOpen = false
    }

    /// Called by `WhoopStore(path:)` on every successful open, so a late `configure` can be detected.
    static func noteStoreOpened() {
        lock.lock(); defer { lock.unlock() }
        _openCount += 1
    }
}
