import XCTest
import GRDB
@testable import WhoopStore

/// `StoreReplication` is the process-wide default behind `WhoopStore(path:)`'s checkpointing
/// parameters. It exists because `wal_autocheckpoint` is per-connection and this app opens the same
/// database from two independent places (`Repository.ensureStore()` and `BLEManager.bootstrapStore()`),
/// so a single opener that does not select `.external` silently defeats it for every other opener —
/// and the only symptom is that the replicator's uploads never get smaller.
///
/// These tests assert the *behaviour* — `PRAGMA wal_autocheckpoint` as observed on real pooled
/// connections — not that a stored enum echoes its argument back.
///
/// Every test resets the global in `tearDown`. `WalCheckpointingTests.testDefaultLeaves…` asserts the
/// stock 1000-page autocheckpoint on a bare `WhoopStore(path:)`, and XCTest runs the whole bundle in
/// one process, so a leaked `.external` here would fail that test instead of this one.
final class StoreReplicationTests: XCTestCase {

    /// Reset on BOTH sides. `tearDown` alone is not enough: `openedStoreCount` is process-wide, and
    /// the ~90 other test classes in this bundle open real stores between these tests, so a count
    /// assertion that only cleaned up after itself passed in isolation and failed in the full run.
    override func setUp() {
        super.setUp()
        StoreReplication.resetForTesting()
    }

    override func tearDown() {
        StoreReplication.resetForTesting()
        super.tearDown()
    }

    private func tempPath() -> String {
        NSTemporaryDirectory() + "whoopstore-storerep-\(UUID().uuidString).sqlite"
    }

    private func removeDB(_ path: String) {
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: path + suffix)
        }
    }

    private func autocheckpoint(_ store: WhoopStore, reader: Bool) async throws -> Int {
        let sql = "PRAGMA wal_autocheckpoint"
        return reader
            ? try await store.registryWriter.read { try Int.fetchOne($0, sql: sql) ?? -1 }
            : try await store.registryWriter.write { try Int.fetchOne($0, sql: sql) ?? -1 }
    }

    // MARK: - Defaults

    /// An unconfigured process is upstream's process. This is the property that lets this file live
    /// in an upstream-shared package at all.
    func testUnconfiguredDefaultsAreUpstreamBehaviour() {
        StoreReplication.resetForTesting()
        if case .external = StoreReplication.walCheckpointing {
            XCTFail("default checkpointing must be .automatic")
        }
        XCTAssertEqual(StoreReplication.walBackstop, .standard)
        XCTAssertFalse(StoreReplication.configuredAfterFirstOpen)
    }

    // MARK: - The policy reaches a store that passes no arguments

    /// The whole point: an opener written with no knowledge of replication — `WhoopStore(path:)`,
    /// which is literally what both real openers call — still gets `.external`.
    func testConfiguredExternalReachesAnOpenerThatPassesNoArguments() async throws {
        let path = tempPath()
        defer { removeDB(path) }
        StoreReplication.configure(walCheckpointing: .external)

        let store = try await WhoopStore(path: path)

        let writer = try await autocheckpoint(store, reader: false)
        let reader = try await autocheckpoint(store, reader: true)
        XCTAssertEqual(writer, 0, "policy must disable autocheckpoint on the writer connection")
        XCTAssertEqual(reader, 0, "policy must disable autocheckpoint on reader connections too")
        if case .automatic = store.walCheckpointing {
            XCTFail("store should report the mode it actually opened with")
        }
    }

    /// Two independently-opened stores on the SAME file both get the policy. This is the multi-opener
    /// case that the argument-at-each-call-site approach cannot guarantee: a second pool still
    /// auto-checkpointing at ~4 MB restarts the WAL under the replicator and forces a full snapshot.
    func testEverySeparateOpenerOfTheSameFileGetsThePolicy() async throws {
        let path = tempPath()
        defer { removeDB(path) }
        StoreReplication.configure(walCheckpointing: .external)

        let first = try await WhoopStore(path: path)
        let second = try await WhoopStore(path: path)

        for (name, store) in [("first", first), ("second", second)] {
            let writer = try await autocheckpoint(store, reader: false)
            let reader = try await autocheckpoint(store, reader: true)
            XCTAssertEqual(writer, 0, "\(name) opener's writer must have autocheckpoint off")
            XCTAssertEqual(reader, 0, "\(name) opener's readers must have autocheckpoint off")
        }
        XCTAssertEqual(StoreReplication.openedStoreCount, 2)
    }

    /// The backstop that discharges `.external`'s obligation must be installed when the mode came
    /// from the policy, not only when it was passed explicitly. Without this, a policy-configured
    /// store would have autocheckpoint off and nothing at all bounding WAL growth.
    func testPolicySelectedExternalStillInstallsTheWalBackstop() async throws {
        let path = tempPath()
        defer { removeDB(path) }
        StoreReplication.configure(walCheckpointing: .external, walBackstop: .standard)

        let store = try await WhoopStore(path: path)

        XCTAssertNotNil(store.walBackstopMonitor,
                        "policy-selected .external must install a backstop, or nothing bounds the WAL")
    }

    /// A caller that opts out of the backstop through the policy gets no monitor — the same escape
    /// hatch the explicit argument has, and the same assertion it carries.
    func testPolicyCanDisableTheBackstop() async throws {
        let path = tempPath()
        defer { removeDB(path) }
        StoreReplication.configure(walCheckpointing: .external, walBackstop: .disabled)

        let store = try await WhoopStore(path: path)

        XCTAssertNil(store.walBackstopMonitor)
    }

    // MARK: - Explicit arguments still win

    /// The policy is a default, not an override. Tests (and any future caller with a specific need)
    /// keep full control.
    func testExplicitArgumentOverridesThePolicy() async throws {
        let path = tempPath()
        defer { removeDB(path) }
        StoreReplication.configure(walCheckpointing: .external)

        let store = try await WhoopStore(path: path, walCheckpointing: .automatic)

        let writer = try await autocheckpoint(store, reader: false)
        let reader = try await autocheckpoint(store, reader: true)
        XCTAssertEqual(writer, 1000)
        XCTAssertEqual(reader, 1000)
    }

    // MARK: - Late configuration is reported, not silent

    /// Configuring after a store is already open leaves that store's pool auto-checkpointing. That is
    /// a genuine half-configured process, and the failure it produces (a replicator that snapshots
    /// forever) is otherwise indistinguishable from the replicator being bad. It must be observable.
    func testConfiguringAfterAStoreIsOpenIsFlagged() async throws {
        let path = tempPath()
        defer { removeDB(path) }

        let early = try await WhoopStore(path: path)
        XCTAssertFalse(StoreReplication.configuredAfterFirstOpen)

        StoreReplication.configure(walCheckpointing: .external)

        XCTAssertTrue(StoreReplication.configuredAfterFirstOpen,
                      "a late configure must be visible; it leaves live pools on the old mode")
        // And the claim is literally true of the already-open store: it kept SQLite's autocheckpoint.
        let earlyPragma = try await autocheckpoint(early, reader: false)
        XCTAssertEqual(earlyPragma, 1000)

        // Stores opened afterwards do get the new policy.
        let path2 = tempPath()
        defer { removeDB(path2) }
        let late = try await WhoopStore(path: path2)
        let latePragma = try await autocheckpoint(late, reader: false)
        XCTAssertEqual(latePragma, 0)
    }

    /// A failed open must not count: `configure` landing between a throwing open and a successful
    /// retry is on time, not late. `quarantineIncompatibleDatabase` + the migrator make a throwing
    /// open a real thing on this path (#222, #261).
    func testAFailedOpenDoesNotCountAsAnOpenStore() async {
        let dir = NSTemporaryDirectory() + "storerep-nodir-\(UUID().uuidString)/nested"
        _ = try? await WhoopStore(path: dir + "/whoop.sqlite")

        XCTAssertEqual(StoreReplication.openedStoreCount, 0,
                       "an open that threw must not be counted, or a legitimate configure reads as late")
    }
}
