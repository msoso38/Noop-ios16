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

    /// Where a page-replication push would go, and with what credential — the same server and the
    /// same token `ingest` uses. `nil` means "this ingester has no liters destination", which is
    /// the correct answer for every test double and makes the liters branch inert for them without
    /// each one having to opt out.
    var litersDestination: (endpoint: String, token: String)? { get }
}

extension CloudIngesting {
    /// Default: no liters destination. Only `CloudSyncClient` overrides this.
    var litersDestination: (endpoint: String, token: String)? { nil }
}

extension CloudSyncClient: CloudIngesting {
    var litersDestination: (endpoint: String, token: String)? {
        #if LITERS
        return (LitersReplicator.endpoint(base: baseURL), token)
        #else
        // The xcframework is not in this build, so there is nothing to push with. Reporting `nil`
        // rather than an endpoint keeps the trial flag from selecting a path that cannot exist.
        return nil
        #endif
    }
}

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
    ///
    /// ## …except when a page replicator owns the WAL, and that exception is the whole point
    ///
    /// This fallback runs on exactly the syncs where the liters push did *not*, so whatever it does
    /// to the WAL happens between every pair of pushes. `checkpointWAL()` is
    /// `wal_checkpoint(TRUNCATE)`, which restarts the WAL and destroys the replicator's resume
    /// offset. Measured on VK's device 2026-07-28: this checkpoint at 06:03, then a push at 14:23
    /// reporting `snapshotReason: "wal truncated by another process"` and uploading 640 MB. Every
    /// recorded push was a full upload — page replication reduced to a full upload with extra steps,
    /// by the path it was replacing.
    ///
    /// A gentler checkpoint mode does **not** fix it: `FULL` leaves the `-wal` file at its full size
    /// and still costs the next push a snapshot, because SQLite restarts a fully-backfilled WAL on
    /// the next write. Both are measured in `LitersRoundTripTests`.
    ///
    /// So under `.external` the fallback takes no checkpoint at all. It stages a consistent full copy
    /// through SQLite's Online Backup API — which reads *through* the WAL and never checkpoints —
    /// and archives that. `/ingest` still ships a complete, fresh, `quick_check`-verified database;
    /// the replicator's resume point is untouched. The cost is one full-size temp write, paid only on
    /// the fallback path, and only on a device running the trial.
    ///
    /// If staging fails for any reason (disk, I/O), it degrades to the checkpointing path rather than
    /// failing the sync: a sync that ships everything and costs one snapshot beats a sync that ships
    /// nothing.
    static let defaultExporter: Exporter = { store, dest in
        guard case .external = store.walCheckpointing else {
            return await DataBackup.writeBackup(checkpoint: { (try? await store.checkpointWAL()) != nil },
                                                to: dest)
        }
        // Alongside `dest` (Caches), which the caller already `defer`s a removal of — one directory,
        // one cleanup story, and never Documents.
        let staged = dest.deletingLastPathComponent()
            .appendingPathComponent("cloudsync-staged-\(UUID().uuidString).sqlite")
        defer {
            let fm = FileManager.default
            for suffix in ["", "-wal", "-shm"] {
                try? fm.removeItem(atPath: staged.path + suffix)
            }
        }
        do {
            try await store.writeConsistentCopy(to: staged.path)
        } catch {
            NSLog("cloudsync: staging a consistent copy failed (%@) — falling back to a checkpointed "
                  + "export, which costs the replicator one snapshot", String(describing: error))
            return await DataBackup.writeBackup(checkpoint: { (try? await store.checkpointWAL()) != nil },
                                                to: dest)
        }
        return await DataBackup.writeBackup(stagedDatabaseAt: staged, to: dest)
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

        // --- page replication, when the trial is on -------------------------------------------
        //
        // Placed HERE, before the exporter, and that placement is the whole design. `exporter`'s
        // first act is `checkpointWAL()` — a `wal_checkpoint(TRUNCATE)` — which is precisely what a
        // page replicator must be the sole caller of (see the note above `walBytes`). Running the
        // export and then pushing would restart the WAL underneath the writer and force
        // `snapshotted: true` on every push: the old path would defeat the new one while both ran.
        //
        // Three conditions, all required, all cheap to check:
        //   * the trial flag is on (`UserDefaults`, default false — a shipped build is unaffected);
        //   * `.external` is actually in force, not merely requested. `isEnabled` and `isInForce`
        //     differ for exactly one launch after every flip, and pushing while SQLite still owns
        //     autocheckpoint is the configuration that snapshots on every sync;
        //   * the client has a liters destination (it does not when LITERS is not compiled in, and
        //     no test double has one).
        //
        // A failure here is NOT fatal and NOT retried in place: it falls through to `/ingest`
        // below, which is untouched and remains both the fallback and the recovery path. The one
        // thing that must not happen is a sync that ships nothing and reports success.
        if let result = await litersPushIfEnabled(client: client, walBytes: walBytes,
                                                  telemetry: telemetry) {
            return result
        }

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

    /// One page-replication push, or `nil` to mean "not this time — use `/ingest`".
    ///
    /// Returns `nil` for every reason a push does not happen (trial off, `.external` not in force,
    /// no destination, liters not compiled in) *and* for a push that failed, because the caller's
    /// only correct response to all of them is the same: fall through to the whole-database upload.
    /// The distinction is preserved in the log, not in the control flow.
    ///
    /// `latestDay` is `nil` on this path by construction: `/liters` is a byte pipe into
    /// `mirror.sqlite` and answers with liters' protocol, not with `/ingest`'s
    /// `{ok,bytes,latestDay}`. `CloudSyncModel` uses only `bytes`.
    /// `UserDefaults` key carrying the outcome of the LAST liters attempt — which branch was taken
    /// and, on a failure, the error verbatim.
    ///
    /// Not decoration. Every non-push outcome below is otherwise reported only through `NSLog`, and
    /// an `NSLog` on a phone that is not attached to Xcode goes nowhere a person can read: a trial
    /// that is failing every push and a trial that was never entered produce byte-identical evidence
    /// (no telemetry record, an empty bucket, and a perfectly healthy `/ingest`). That ambiguity cost
    /// a full debugging session — the server said `applies: 0`, the phone said the flag was on, and
    /// nothing anywhere named the actual failure. Mirrors
    /// `CloudSyncAppDelegate.registrationBreadcrumbKey`, which exists for the same reason.
    static let litersBreadcrumbKey = "cloudsync.liters.lastOutcome"

    /// Records one liters outcome. Best-effort and never able to fail a sync, like every other
    /// breadcrumb on this lane.
    private static func noteLiters(_ outcome: String) {
        UserDefaults.standard.set("\(outcome) \(Date())", forKey: litersBreadcrumbKey)
    }

    private static func litersPushIfEnabled(
        client: any CloudIngesting, walBytes: Int64, telemetry: SyncPushTelemetry?
    ) async -> (bytes: Int, latestDay: String?)? {
        #if LITERS
        guard SyncReplicationTrial.isEnabled else { return nil }
        // `isEnabled` is the intent; `isInForce` is the reality. Pushing while SQLite still owns
        // wal_autocheckpoint means a foreign checkpoint can restart the WAL between pushes, and
        // every push then ships the whole database — measurably, not theoretically.
        guard SyncReplicationTrial.isInForce else {
            NSLog("liters: trial is on but WAL checkpointing is still .automatic "
                  + "(restart pending) — using /ingest for this sync")
            noteLiters("skipped: not-in-force (restart pending)")
            return nil
        }
        guard let dest = client.litersDestination else {
            noteLiters("skipped: no liters destination")
            return nil
        }

        do {
            // The one real on-disk database, resolved exactly the way `DataBackup`/`FolderBackup`
            // resolve it. Throwing is folded into the same catch: a store path we cannot even name
            // is a reason to use `/ingest`, not a reason to fail the sync.
            let dbPath = try StorePaths.defaultDatabasePath()
            // Synchronous and blocking by liters' contract; kept off the cooperative pool's
            // forward progress by running it on a detached background task.
            let summary = try await Task.detached(priority: .utility) {
                try LitersReplicator.shared.push(databasePath: dbPath,
                                                 endpoint: dest.endpoint,
                                                 token: dest.token)
            }.value

            // A push that captured content into an L0 file but shipped NO file is not a completed
            // sync, and `push()` does not throw for it — `synced`/`uploaded` are separate fields and
            // the old code read neither. Returning here on `uploaded == 0` would report a sync that
            // delivered nothing as a success, skip `/ingest`, and stamp `lastUploadToken` so the NEXT
            // sync skips the upload too. Fall through to `/ingest` instead, which is the same answer
            // this function gives every other way of not pushing.
            guard summary.uploaded > 0 else {
                NSLog("liters: push shipped no files (txid=%llu synced=%d remoteTxid=%llu) "
                      + "— using /ingest for this sync", summary.txid, summary.synced ? 1 : 0,
                      summary.remoteTxid)
                noteLiters("no-op: uploaded=0 synced=\(summary.synced) txid=\(summary.txid) "
                           + "remoteTxid=\(summary.remoteTxid)")
                return nil
            }

            telemetry?.record(snapshotted: summary.snapshotted,
                              snapshotReason: summary.snapshotReason,
                              bytesUploaded: Int64(summary.bytesUploaded),
                              walBytes: walBytes,
                              txid: summary.txid)
            NSLog("liters: pushed txid=%llu bytes=%llu snapshotted=%d reason=%@ walAtPush=%lld",
                  summary.txid, summary.bytesUploaded, summary.snapshotted ? 1 : 0,
                  summary.snapshotReason ?? "-", walBytes)
            noteLiters("pushed: files=\(summary.uploaded) bytes=\(summary.bytesUploaded) "
                       + "txid=\(summary.txid) snapshotted=\(summary.snapshotted)")
            return (Int(summary.bytesUploaded), nil)
        } catch {
            // Deliberately swallowed. Every liters failure mode — an unreachable sink (503 from
            // the proxy when LITERS_SINK_ENABLED is unset), a full volume (507), a lease conflict,
            // a rotated token — is survivable by uploading the whole database instead, and a sync
            // that ships data on the old path beats a sync that reports a new-path error.
            NSLog("liters: push failed (%@) — falling back to /ingest for this sync",
                  String(describing: error))
            // Verbatim, not a category: the whole point is that the next person to look does not
            // have to guess which of liters' failure modes this was.
            noteLiters("failed: \(error)")
            return nil
        }
        #else
        _ = (client, walBytes, telemetry)
        return nil
        #endif
    }
}
#endif // CLOUD_SYNC
