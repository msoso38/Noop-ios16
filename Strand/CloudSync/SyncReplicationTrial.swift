// Compiled ONLY when the CLOUD_SYNC compilation condition is set (by the untracked
// OuraSecrets.xcconfig — see OuraConfig.xcconfig). A default build contains none of this code,
// keeping "fully offline" a byte-level property of the shipped binary, not a runtime promise.
#if CLOUD_SYNC
import Foundation
import WhoopStore

/// The on/off switch and launch hook for the page-replication device trial.
///
/// ## What the trial is for
///
/// `noop-cloud`'s current sync uploads the whole database (153.4 MB, 90–150 s) on every sync. The
/// replacement ships WAL frames instead. The one number that decides whether that is a 25–380× win
/// or a full upload with extra steps is **how often a push degrades to a full snapshot**, and it
/// cannot be reasoned about — iOS relaunches the process constantly, and a replicator that loses its
/// resume offset re-uploads everything. It has to be measured on VK's device, on VK's data.
///
/// Enabling the trial does two things and nothing else:
///
/// 1. hands WAL checkpointing to an external replicator (`StoreReplication.configure(.external)`),
///    which is the precondition for a replicator to see every transaction; and
/// 2. records one `SyncPushObservation` per push (`SyncPushTelemetry`).
///
/// It uploads nothing differently. Today's `/ingest` path is untouched, so the trial's downside is
/// bounded to "the WAL is now checkpointed by the backstop at 64 MiB instead of by SQLite at 4 MB."
///
/// ## Default: off
///
/// `.external` is a real commitment — with autocheckpoint disabled, WAL growth is bounded only by
/// `WalBackstopPolicy` (see `WalCheckpointing`'s obligation note, and the measured 166 MB/day). It
/// stays off until VK turns it on, on his own device, and it is a plain `UserDefaults` flag rather
/// than a Keychain item because it is a preference, not a secret.
enum SyncReplicationTrial {
    /// `UserDefaults` key. Absent (the shipped state) reads as `false`.
    static let enabledKey = "cloudsync.replicationTrialEnabled"

    /// Whether the trial is on. Reading this is *not* the same as asking whether `.external` is in
    /// force — a flip only takes effect at the next launch, because `applyAtLaunch` runs before any
    /// store opens and a live `DatabasePool`'s `wal_autocheckpoint` cannot be changed after the fact.
    /// Ask `StoreReplication.walCheckpointing` for the state that is actually in force.
    static var isEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: enabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: enabledKey) }
    }

    /// Install the process-wide store policy. **Must be called before the first `WhoopStore` open**,
    /// i.e. at the top of the app's `init()`, ahead of `AppModel()`.
    ///
    /// Both real openers (`Repository.ensureStore()`, `BLEManager.bootstrapStore()`) are `async` and
    /// therefore strictly later than `init()`, including the CoreBluetooth state-restoration path —
    /// UIKit constructs the `App` before it delivers a restoration callback. If that ever stops being
    /// true, `StoreReplication.configuredAfterFirstOpen` flips to `true` and says so rather than
    /// leaving a half-configured process to be inferred from a bad snapshot rate.
    ///
    /// Deliberately does nothing when the trial is off: the `.automatic` branch would set the same
    /// values `StoreReplication` already ships, and not calling `configure` at all keeps
    /// `openedStoreCount`/`configuredAfterFirstOpen` meaningful for a build that never opted in.
    static func applyAtLaunch(enabled: Bool = isEnabled) {
        guard enabled else { return }
        StoreReplication.configure(walCheckpointing: .external, walBackstop: .standard)
        // Deliberately does not build `statusLine` here: that touches `shared`, and this runs on the
        // launch-critical path before the first frame. The interesting half of the trial's state is
        // logged after each push, where file I/O has already happened.
        NSLog("SyncReplicationTrial: ON — external WAL checkpointing, backstop at %lld bytes",
              StoreReplication.walBackstop.ceilingBytes)
    }

    /// The shared recorder, opened lazily so a build with the trial off never creates the file.
    /// `nil` if Application Support is unavailable (it never is on a real device) — telemetry is
    /// best-effort and must never be able to fail a sync.
    ///
    /// Also `nil` under XCTest, for the same reason `CloudSyncModel.autoSyncIfDue` refuses to run
    /// there: `StrandTests` executes inside the full app via `TEST_HOST`, so it resolves the developer
    /// Mac's REAL Application Support directory. Without this, every test that exercises an upload
    /// would append junk records to the live trial file and corrupt the one measurement this exists to
    /// produce. Tests that want to assert on telemetry inject their own instance over a temp URL.
    static let shared: SyncPushTelemetry? = {
        guard !CloudSyncModel.isRunningUnderXCTest else { return nil }
        guard let url = try? SyncPushTelemetry.defaultURL() else { return nil }
        return SyncPushTelemetry(url: url)
    }()

    /// One line describing the trial's state, for the log and for a diagnostics row. Combines the
    /// *intent* (the flag), the *reality* (what the store actually opened with), and the *result*
    /// (the telemetry aggregate) — the three are not the same and have to be read together.
    static var statusLine: String {
        let inForce: String
        switch StoreReplication.walCheckpointing {
        case .external: inForce = "external"
        case .automatic: inForce = "automatic"
        }
        let late = StoreReplication.configuredAfterFirstOpen ? " LATE-CONFIGURE" : ""
        let telemetry = shared.map { " " + $0.oneLineSummary } ?? ""
        return "replicationTrial enabled=\(isEnabled) checkpointing=\(inForce)\(late)\(telemetry)"
    }
}
#endif
