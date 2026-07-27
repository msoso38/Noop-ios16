// Compiled ONLY when the CLOUD_SYNC compilation condition is set (by the untracked
// OuraSecrets.xcconfig — see OuraConfig.xcconfig). A default build contains none of this code,
// keeping "fully offline" a byte-level property of the shipped binary, not a runtime promise.
#if CLOUD_SYNC
import Foundation
import WhoopStore

/// Abstracts the raw-bytes ingest POST so `CloudSyncUploader` is testable without a live network —
/// mirrors `CloudSyncCoordinator`'s `CloudEditFetching` seam. `CloudSyncClient` conforms below.
protocol CloudIngesting {
    func ingest(fileURL: URL) async throws -> (bytes: Int, latestDay: String?)
}
extension CloudSyncClient: CloudIngesting {}

/// The export half's user-facing failure. The network half of an upload throws `CloudSyncError` (same
/// typed error every other CloudSync network call throws); this covers only "never got as far as
/// having bytes to send".
enum CloudSyncUploadError: LocalizedError, Equatable {
    case exportFailed(String)
    /// The store has no real data yet — refused BEFORE export ever runs. Distinct from
    /// `exportFailed`: nothing went wrong, there was simply nothing to upload. Guards against the
    /// incident where the macOS TEST HOST (`StrandTests` running inside the full `Staging.app` via
    /// `TEST_HOST`) executed the launch-time auto-sync `.task` with bundle credentials present, and
    /// auto-uploaded the Mac's empty database, replacing the production mirror. This check protects
    /// EVERY upload path — fresh installs, a never-paired Mac container, and any future race — not
    /// just the test-host case (see `CloudSyncModel.isRunningUnderXCTest` for that separate guard).
    case emptyStore

    var errorDescription: String? {
        switch self {
        case .exportFailed(let detail):
            return "Couldn't prepare the backup to upload. \(detail)"
        case .emptyStore:
            return "Nothing to upload yet — the local database is empty."
        }
    }
}

/// Produces this device's own checkpointed, integrity-verified `.noopbak` and POSTs it to the
/// noop-cloud server's `/ingest` endpoint — the "upload" half of Phase 3.5's zero-touch sync (the
/// "pull" half is `CloudSyncCoordinator`). A pure coordination step with no state of its own, like
/// `CloudSyncCoordinator`, so it's a namespace of static functions rather than an instance.
enum CloudSyncUploader {
    /// Produces a `.noopbak` at `dest` from `store`, returning `DataBackup.BackupResult` so a real
    /// export failure's message survives. Injectable so a test can supply canned bytes without
    /// touching the app's real on-disk database: `WhoopStore.inMemory()` test stores have no backing
    /// file at all (see `WhoopStore.inMemory()`'s doc comment — a `DatabaseQueue`, not a file-backed
    /// `DatabasePool`), and the production default below is hardcoded to
    /// `StorePaths.defaultDatabasePath()` regardless of which `WhoopStore` instance is passed in. That
    /// fixed path is correct for production — there is only ever one real on-disk database, and
    /// `FolderBackup.backupNow`/`DataBackup.runExport` resolve it the exact same way — but it makes the
    /// default exporter untestable against a throwaway store, hence the seam.
    typealias Exporter = (WhoopStore, URL) async -> DataBackup.BackupResult

    /// The real export: checkpoint `store`'s WAL (so the single `.sqlite` file is whole), then reuse
    /// the SAME checkpointed, `PRAGMA quick_check`-verified export `BackupSync`/`FolderBackup` use — an
    /// auto-uploaded snapshot is byte-identical to a manual "Export backup".
    static let defaultExporter: Exporter = { store, dest in
        await DataBackup.writeBackup(checkpoint: { (try? await store.checkpointWAL()) != nil }, to: dest)
    }

    /// Export the live store to a disposable temp file in Caches (never Documents — nothing here is
    /// meant to persist or be user-visible) and POST it to `<base>/ingest`. The temp file is removed in
    /// `defer`, whatever happens: success, an export failure, or a network failure. The DB can be
    /// 100-300MB, so `CloudSyncClient.ingest` streams it from this file via
    /// `URLSession.upload(for:fromFile:)` rather than loading it into memory.
    ///
    /// `telemetry` records one `SyncPushObservation` per successful upload. Today every one of them
    /// is `snapshotted: true` with reason `full-ingest`, because `/ingest` *is* a full snapshot —
    /// that is not a placeholder, it is the honest baseline the page-replication trial is measured
    /// against. What the field actually buys before a replicator exists is the other half of each
    /// record: the `-wal` size at push time and the interval between pushes. Those are the two
    /// numbers that decide whether `WalCheckpointing.external` is safe on this device, and neither is
    /// observable anywhere else. Injectable so a test writes to a temp directory rather than the
    /// app's real Application Support.
    static func upload(store: WhoopStore, client: any CloudIngesting,
                        exporter: Exporter = defaultExporter,
                        telemetry: SyncPushTelemetry? = SyncReplicationTrial.shared)
                        async throws -> (bytes: Int, latestDay: String?) {
        // Refuse an empty/trivial store BEFORE touching export or the network at all — see
        // `CloudSyncUploadError.emptyStore`'s doc comment for the incident this guards against.
        // `dailyMetric` is written by every ingest path (BLE-derived recompute, WHOOP/Apple
        // Health/Oura/Xiaomi imports), so a genuinely fresh/never-populated store has zero rows here.
        guard try await store.hasAnyDailyMetrics() else {
            throw CloudSyncUploadError.emptyStore
        }
        let cachesDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let tempURL = cachesDir.appendingPathComponent("cloudsync-upload-\(UUID().uuidString).noopbak")
        defer { try? FileManager.default.removeItem(at: tempURL) }

        // Sampled BEFORE the exporter runs, because `defaultExporter`'s first act is
        // `checkpointWAL()` (a `wal_checkpoint(TRUNCATE)`), which leaves the `-wal` at ~0 bytes.
        // Reading it afterwards would record that zero and measure nothing. What is wanted is how far
        // the WAL had grown by the time a sync began — the quantity `WalCheckpointing.external` puts
        // at risk. One `stat(2)`; nil only for an in-memory test store.
        //
        // Worth flagging for the replicator work that follows: that same `checkpointWAL()` is exactly
        // what a page replicator must be the only caller of. Leaving it here while a replicator is
        // also running would restart the WAL underneath it and force a full snapshot on every sync —
        // i.e. the upload path would defeat the thing it is being replaced by.
        let walBytes = store.walFileSizeBytes() ?? 0

        switch await exporter(store, tempURL) {
        case .exported:
            let result = try await client.ingest(fileURL: tempURL)
            // Recorded only on success, deliberately: a failed upload shipped nothing, and counting it
            // would understate the byte cost per delivered sync. Never allowed to throw — `record` is
            // best-effort by construction (see `SyncPushTelemetry.persist`).
            if let telemetry {
                telemetry.record(snapshotted: true,
                                 snapshotReason: "full-ingest",
                                 bytesUploaded: Int64(result.bytes),
                                 walBytes: walBytes,
                                 txid: 0)
                // The trial has no UI. This line is how it is read — off a device console, or from the
                // container's log — so it has to carry the aggregate and not just this one push.
                NSLog("SyncPushTelemetry: %@ walAtPush=%lld backstopFirings=%d",
                      telemetry.oneLineSummary, walBytes, store.walBackstopFirings)
            }
            return result
        case .failure(let message):
            throw CloudSyncUploadError.exportFailed(message)
        case .cancelled, .imported:
            // The checkpointed export path (no picker, no import flow) never actually returns these —
            // handled explicitly so the switch stays exhaustive without a silently-wrong `default`.
            throw CloudSyncUploadError.exportFailed("The export step returned an unexpected result.")
        }
    }
}
#endif // CLOUD_SYNC
