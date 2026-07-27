// Compiled ONLY when the CLOUD_SYNC compilation condition is set (by the untracked
// OuraSecrets.xcconfig — see OuraConfig.xcconfig). A default build contains none of this code,
// keeping "fully offline" a byte-level property of the shipped binary, not a runtime promise.
#if CLOUD_SYNC
import Foundation

/// One observed page-replication push, recorded locally so the device trial can answer the single
/// question that gates adopting `liters`: **how often does a push degrade to a full snapshot?**
///
/// A snapshotting push ships the entire database. If that is rare, incremental page replication is
/// a 25–380× win over today's whole-file upload. If it is routine, `liters` is a full upload with
/// extra steps and the trial should stop there. Nothing about `PushSummary`'s other fields answers
/// this — a snapshot and a delta differ only in size, and size alone cannot distinguish "a big
/// day" from "re-uploaded everything".
///
/// Fields mirror `liters`' FFI `PushSummary` one-for-one plus two the app supplies, so wiring the
/// real thing later is a struct literal and nothing else. Deliberately a plain value type with no
/// `liters` import: this compiles and is tested with no Rust in the project.
struct SyncPushObservation: Codable, Equatable, Sendable {
    /// When the push completed.
    var at: Date
    /// True when the push wrote a full snapshot of the database rather than a WAL delta.
    var snapshotted: Bool
    /// `liters`' reason from its `verify()` decision tree, e.g. "wal truncated by another process".
    /// One of a small fixed set, so it is safe to group on. `nil` when not snapshotting.
    var snapshotReason: String?
    /// Bytes of LTX uploaded.
    var bytesUploaded: Int64
    /// Size of the `-wal` file when the push ran — the other half of the story, since a large WAL
    /// is what precedes a foreign checkpoint and therefore a snapshot.
    var walBytes: Int64
    /// Seconds since the previous recorded push; `nil` for the first one after install.
    var secondsSinceLastPush: Double?
    /// `liters`' replication position after the push, for correlating with the server.
    var txid: UInt64
}

/// Aggregate view of the trial so far. `snapshotRate` is the go/no-go number.
struct SyncPushStats: Equatable {
    var pushes: Int
    var snapshots: Int
    var bytesUploaded: Int64
    var bytesUploadedBySnapshots: Int64
    /// Count per `snapshotReason`, so a bad rate can be attributed to a branch rather than guessed at.
    var reasonCounts: [String: Int]
    var maxWalBytes: Int64
    var medianSecondsBetweenPushes: Double?

    /// Fraction of pushes that shipped the whole database. This is what decides the trial.
    var snapshotRate: Double { pushes == 0 ? 0 : Double(snapshots) / Double(pushes) }
    /// Fraction of uploaded bytes spent on snapshots — the cost, as opposed to the frequency. A low
    /// rate can still be a bad deal if each snapshot is 766 MB.
    var snapshotByteShare: Double {
        bytesUploaded == 0 ? 0 : Double(bytesUploadedBySnapshots) / Double(bytesUploaded)
    }
}

/// Append-only, bounded, local recorder for `SyncPushObservation`s.
///
/// ## Why not a table in the database
///
/// The obvious place is a `syncPushLog` table in `whoop.sqlite`. That would be wrong twice over.
/// Every insert is another commit in the database whose *commit rate* is the thing being measured
/// (~14 dirtied pages each, which is exactly what drives WAL growth), and every row would then be
/// replicated by the replicator it is measuring — the instrument would be part of the experiment.
/// A small JSON file in Application Support is outside the replication set entirely.
///
/// ## Cost
///
/// One `stat(2)` for the WAL size plus one atomic rewrite of a file that is capped at
/// `maxRecords` (200 ≈ 30 KB). At the expected push cadence — single-digit per day, ~2/hour even
/// on an aggressive schedule — that is immaterial, and it is bounded no matter how long the trial
/// runs. Trimming drops old *records* but never the counters, so `stats` stays correct over the
/// whole trial while the record window stays small.
///
/// Not an actor: pushes are already serialized by the replicator, and an `NSLock` keeps this usable
/// from a `BGTask` completion handler without an `await`.
final class SyncPushTelemetry: @unchecked Sendable {
    /// Records kept on disk. Older ones are dropped; the aggregate counters are not.
    static let maxRecords = 200

    private let url: URL
    private let lock = NSLock()
    private var state: State

    private struct State: Codable {
        var version: Int = 1
        var records: [SyncPushObservation] = []
        /// Monotonic totals, kept across trimming so `stats` covers the whole trial.
        var totalPushes: Int = 0
        var totalSnapshots: Int = 0
        var totalBytes: Int64 = 0
        var totalSnapshotBytes: Int64 = 0
        var reasonCounts: [String: Int] = [:]
        var maxWalBytes: Int64 = 0
    }

    /// The default location: `Application Support/CloudSync/push-telemetry.json`. Not in Caches —
    /// the OS may evict Caches under pressure, and losing the trial's data to a cache purge would
    /// be a silent, unrecoverable hole in the one measurement the trial exists for.
    static func defaultURL() throws -> URL {
        let dir = try FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("CloudSync", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("push-telemetry.json")
    }

    /// Loads existing state, or starts empty. A corrupt or unreadable file starts empty rather than
    /// throwing: losing telemetry must never break a sync.
    init(url: URL) {
        self.url = url
        if let data = try? Data(contentsOf: url),
           let decoded = try? JSONDecoder().decode(State.self, from: data) {
            self.state = decoded
        } else {
            self.state = State()
        }
    }

    /// Records one push. `walBytes` comes from `WhoopStore.walFileSizeBytes()`;
    /// `secondsSinceLastPush` is derived here so callers cannot forget it.
    ///
    /// Best-effort persistence: a failed write loses the record, never the sync.
    @discardableResult
    func record(at now: Date = Date(),
                snapshotted: Bool,
                snapshotReason: String?,
                bytesUploaded: Int64,
                walBytes: Int64,
                txid: UInt64) -> SyncPushObservation {
        lock.lock()
        defer { lock.unlock() }

        let previous = state.records.last?.at
        let observation = SyncPushObservation(
            at: now,
            snapshotted: snapshotted,
            snapshotReason: snapshotted ? snapshotReason : nil,
            bytesUploaded: bytesUploaded,
            walBytes: walBytes,
            secondsSinceLastPush: previous.map { now.timeIntervalSince($0) },
            txid: txid)

        state.records.append(observation)
        if state.records.count > Self.maxRecords {
            state.records.removeFirst(state.records.count - Self.maxRecords)
        }
        state.totalPushes += 1
        state.totalBytes += bytesUploaded
        if snapshotted {
            state.totalSnapshots += 1
            state.totalSnapshotBytes += bytesUploaded
            state.reasonCounts[snapshotReason ?? "unspecified", default: 0] += 1
        }
        state.maxWalBytes = max(state.maxWalBytes, walBytes)
        persist()
        return observation
    }

    /// The whole trial so far, including pushes whose records have since been trimmed.
    var stats: SyncPushStats {
        lock.lock()
        defer { lock.unlock() }
        let gaps = state.records.compactMap(\.secondsSinceLastPush).sorted()
        return SyncPushStats(
            pushes: state.totalPushes,
            snapshots: state.totalSnapshots,
            bytesUploaded: state.totalBytes,
            bytesUploadedBySnapshots: state.totalSnapshotBytes,
            reasonCounts: state.reasonCounts,
            maxWalBytes: state.maxWalBytes,
            medianSecondsBetweenPushes: gaps.isEmpty ? nil : gaps[gaps.count / 2])
    }

    /// The retained window, oldest first. For a diagnostics screen or a manual export.
    var recentRecords: [SyncPushObservation] {
        lock.lock()
        defer { lock.unlock() }
        return state.records
    }

    /// A one-line summary for the log, so the trial is readable without a UI.
    var oneLineSummary: String {
        let s = stats
        let pct = String(format: "%.1f%%", s.snapshotRate * 100)
        let bytePct = String(format: "%.1f%%", s.snapshotByteShare * 100)
        let reasons = s.reasonCounts.sorted { $0.value > $1.value }
            .map { "\($0.key)=\($0.value)" }.joined(separator: ", ")
        return "pushes=\(s.pushes) snapshots=\(s.snapshots) (\(pct) of pushes, \(bytePct) of bytes) "
            + "maxWal=\(s.maxWalBytes)B"
            + (reasons.isEmpty ? "" : " reasons[\(reasons)]")
    }

    /// Discards everything. For starting a clean trial run.
    func reset() {
        lock.lock()
        defer { lock.unlock() }
        state = State()
        persist()
    }

    /// Atomic: write a sibling temp file and rename, so a kill mid-write cannot leave a truncated
    /// JSON that the next launch silently discards as corrupt.
    private func persist() {
        guard let data = try? JSONEncoder().encode(state) else { return }
        let tmp = url.appendingPathExtension("tmp")
        do {
            try data.write(to: tmp, options: .atomic)
            _ = try? FileManager.default.replaceItemAt(url, withItemAt: tmp)
        } catch {
            try? FileManager.default.removeItem(at: tmp)
        }
    }
}
#endif
