// Compiled ONLY when the CLOUD_SYNC compilation condition is set (by the untracked
// OuraSecrets.xcconfig) AND LITERS is set (by the generated Config/LitersLocal.xcconfig, i.e. only
// after Rust/build-ios.sh has produced the xcframework). A default build contains none of this code.
#if CLOUD_SYNC && LITERS
import Foundation

/// Owns the one `LitersWriter` for the app's whole lifetime and performs one push per sync.
///
/// ## Why this is a process-lifetime singleton and not a per-push object
///
/// This is the single most load-bearing decision in the liters path, and it was measured rather
/// than assumed. `liters::Writer` resumes an incremental push from a WAL offset it recorded on the
/// previous push. Dropping the writer releases the WAL-pinning read lock, and SQLite checkpoints
/// and truncates the WAL when the *last* connection to a database closes — `wal_autocheckpoint = 0`
/// does not prevent that, it only stops the threshold-triggered one. The next open therefore finds
/// a WAL whose header salt has been reset and can only recover by shipping the whole database.
///
/// Measured on VK's real 782 MB database against a real `noop-cloud` `/liters` endpoint
/// (2026-07-27): a writer opened and dropped around each push snapshotted **every single time** —
/// 5 pushes, 5 snapshots, ~312 MB each. The same database with one writer held across pushes
/// snapshotted once (the first, which has to) and then ran incremental: 151–176 KB per 10-minute
/// delta, ~2.0 MB per 4-hour delta. That is the difference between page replication and a full
/// upload with extra steps, and it is entirely a function of writer lifetime.
///
/// So: opened lazily on the first push, kept, and never closed except by `reset()` — which exists
/// for the recovery path, not for routine use.
///
/// ## Threading
///
/// `LitersWriter`'s methods are synchronous and block. `push()` is called from a `Task`'s
/// background executor by `CloudSyncUploader`, and serialized here by an `NSLock` because the FFI
/// object's own lock would otherwise queue a second push behind an in-flight one with no timeout of
/// ours. One sync at a time is already guaranteed upstream by `CloudSyncGate`; the lock is a
/// belt-and-braces against a future second caller.
final class LitersReplicator: @unchecked Sendable {
    static let shared = LitersReplicator()

    private let lock = NSLock()
    private var writer: LitersWriter?
    /// The endpoint the live writer was opened against. A settings change (new server or rotated
    /// token) has to rebuild the writer, or pushes would keep going to the old destination.
    private var openedFor: String?

    private init() {}

    /// `<base>/liters` — the mount `noop-cloud`'s Express proxy serves. Everything after it is
    /// liters' own grammar (`/ltx/0/{min}-{max}.ltx`) and is not this app's business.
    static func endpoint(base: URL) -> String {
        base.appendingPathComponent("liters").absoluteString
    }

    /// One push. Returns liters' own summary so the caller records the real numbers rather than a
    /// guess. Throws `LitersError` — the caller is expected to fall back to `/ingest`.
    func push(databasePath: String, endpoint: String, token: String) throws -> PushSummary {
        lock.lock()
        defer { lock.unlock() }

        if openedFor != endpoint { closeLocked() }
        if writer == nil {
            writer = try LitersWriter.newWithHttpClient(
                dbPath: databasePath,
                storage: .http(url: endpoint, authToken: token),
                httpClient: LitersURLSessionClient.shared)
            openedFor = endpoint
        }
        guard let writer else { throw LitersError.Other(message: "writer unavailable") }
        return try writer.push()
    }

    /// **`maintain()` is deliberately never called from the push path**, and that is a decision,
    /// not an omission.
    ///
    /// liters' `maintain()` runs compaction, retention *and* snapshotting on litestream's default
    /// cadence — `snapshot_interval` is 24 h — and `LitersWriter.maintain()` hardcodes
    /// `MaintenanceOptions::default()`, so the interval is not reachable from Swift. Measured on
    /// the real 782 MB database (2026-07-27): one `maintain()` run took **177 s** and uploaded a
    /// fresh **312 MB** snapshot. A `BGAppRefreshTask` gets tens of seconds, so this cannot run
    /// there at all; and calling it daily would put a 312 MB upload back into a scheme whose entire
    /// point is not doing 165 MB uploads.
    ///
    /// The cost of not calling it is bucket growth, and it is small and bounded on this
    /// deployment: the bucket is a transport, not an archive — the server keeps a live
    /// `mirror.sqlite` and only ever applies files forward. One base snapshot (~312 MB) plus
    /// ~2 MB per 4-hour sync is ~12 MB/day, i.e. ~4.7 GB after a year on a 10.5 GB volume, and it
    /// is swept server-side rather than paid for on the phone's radio. Revisit only if a *fresh*
    /// restore (as opposed to incremental follow) ever becomes a normal operation.

    /// Whether a writer is currently open. Diagnostics only — a `false` here after a successful
    /// sync means something closed it, which is the state that costs a snapshot.
    var isOpen: Bool {
        lock.lock(); defer { lock.unlock() }
        return writer != nil
    }

    /// Recovery for liters' one non-transient local error ("local ltx file missing or corrupt"):
    /// wipes local replication state so the next push re-derives everything from the bucket. The
    /// database content is untouched. Costs one snapshot.
    func resetLocalState() {
        lock.lock(); defer { lock.unlock() }
        try? writer?.resetLocal()
    }

    /// Drops the writer, releasing the WAL read lock and every fd. **Costs a snapshot on the next
    /// push** (see the type doc), so this is for teardown and endpoint changes, not for tidiness.
    func close() {
        lock.lock(); defer { lock.unlock() }
        closeLocked()
    }

    private func closeLocked() {
        writer?.close()
        writer = nil
        openedFor = nil
    }
}
#endif // CLOUD_SYNC && LITERS
