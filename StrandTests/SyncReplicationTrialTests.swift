// Tests the CLOUD_SYNC-gated page-replication trial switch; compiled only when the flag is set
// (StrandTests shares the app's OuraConfig.xcconfig, so flag + creds arrive together).
#if CLOUD_SYNC
import XCTest
import WhoopStore
@testable import Strand

/// `SyncReplicationTrial` is the one switch that decides whether this device hands WAL checkpointing
/// to an external replicator. Getting its default wrong is not a cosmetic bug: `.external` disables
/// SQLite's autocheckpoint process-wide, and on a measured 166 MB/day of WAL growth the only thing
/// left bounding the file is `WalBackstopPolicy`. So "off unless VK turned it on" is the property
/// under test, not an implementation detail.
final class SyncReplicationTrialTests: XCTestCase {

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: SyncReplicationTrial.enabledKey)
        StoreReplication.resetForTesting()
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: SyncReplicationTrial.enabledKey)
        StoreReplication.resetForTesting()
        super.tearDown()
    }

    // MARK: - Default off

    /// A fresh install has never written the key. `UserDefaults.bool(forKey:)` reads a missing key as
    /// `false`, and the whole safety argument for shipping this rests on that.
    func testDisabledWhenTheKeyWasNeverWritten() {
        XCTAssertFalse(SyncReplicationTrial.isEnabled)
    }

    /// The launch hook must leave an opted-out process byte-for-byte identical to today's: SQLite's
    /// own autocheckpoint, no backstop observer on the pool, nothing configured at all.
    func testApplyAtLaunchIsANoOpWhenDisabled() {
        SyncReplicationTrial.applyAtLaunch(enabled: false)

        if case .external = StoreReplication.walCheckpointing {
            XCTFail("a disabled trial must not hand checkpointing to an external replicator")
        }
        XCTAssertEqual(StoreReplication.openedStoreCount, 0)
    }

    // MARK: - Opted in

    /// Turning it on selects `.external` *and* keeps the standard backstop. The pairing is the point:
    /// `.external` alone would leave WAL growth unbounded if the replicator never runs, which is a
    /// strictly worse failure than the full re-upload it is meant to avoid.
    func testApplyAtLaunchSelectsExternalWithABackstopWhenEnabled() {
        SyncReplicationTrial.applyAtLaunch(enabled: true)

        if case .automatic = StoreReplication.walCheckpointing {
            XCTFail("an enabled trial must select .external")
        }
        XCTAssertEqual(StoreReplication.walBackstop, .standard)
        XCTAssertTrue(StoreReplication.walBackstop.isEnabled,
                      "the backstop is what discharges .external's obligation; it cannot be off")
    }

    /// The flag round-trips through `UserDefaults`, which is how VK flips it on his own device.
    func testEnabledFlagRoundTrips() {
        SyncReplicationTrial.isEnabled = true
        XCTAssertTrue(SyncReplicationTrial.isEnabled)
        SyncReplicationTrial.isEnabled = false
        XCTAssertFalse(SyncReplicationTrial.isEnabled)
    }

    /// The default argument reads the live flag, so the real launch hook and these tests exercise the
    /// same code path rather than the tests only proving the injectable overload works.
    func testApplyAtLaunchDefaultsToTheStoredFlag() {
        SyncReplicationTrial.isEnabled = true

        SyncReplicationTrial.applyAtLaunch()

        if case .automatic = StoreReplication.walCheckpointing {
            XCTFail("applyAtLaunch() with no argument must read the stored flag")
        }
    }

    // MARK: - Status

    /// Intent and reality are separate facts and the status line has to carry both: flipping the flag
    /// does NOT change a running process (a live pool's `wal_autocheckpoint` is fixed at open), so a
    /// diagnostics row that showed only the flag would claim external checkpointing was in force
    /// during the entire session before the next launch.
    func testStatusLineSeparatesTheFlagFromWhatIsActuallyInForce() {
        SyncReplicationTrial.isEnabled = true

        let line = SyncReplicationTrial.statusLine

        XCTAssertTrue(line.contains("enabled=true"), line)
        XCTAssertTrue(line.contains("checkpointing=automatic"), // not applied yet — no relaunch
                      "status must report the mode actually in force, not the flag: \(line)")
    }

    /// A late `configure` leaves live pools on the old mode, which is exactly the state that produces
    /// a permanently-snapshotting replicator with no other symptom. It must reach the status line.
    func testStatusLineSurfacesALateConfigure() async throws {
        let path = NSTemporaryDirectory() + "trial-late-\(UUID().uuidString).sqlite"
        defer { for s in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + s) } }
        _ = try await WhoopStore(path: path)

        SyncReplicationTrial.applyAtLaunch(enabled: true)

        XCTAssertTrue(SyncReplicationTrial.statusLine.contains("LATE-CONFIGURE"),
                      SyncReplicationTrial.statusLine)
    }

    // MARK: - Test-host isolation

    /// `StrandTests` runs inside the full app via `TEST_HOST`, so `SyncPushTelemetry.defaultURL()`
    /// resolves the developer Mac's real Application Support. If the shared recorder were live here,
    /// every upload test would append records to the live trial file — silently corrupting the single
    /// measurement the trial exists to produce. Same guard, same reason, as
    /// `CloudSyncModel.autoSyncIfDue`'s XCTest refusal.
    func testSharedRecorderIsInertUnderXCTest() {
        XCTAssertNil(SyncReplicationTrial.shared,
                     "the shared recorder must not write to the real container from a test run")
    }
}
#endif
