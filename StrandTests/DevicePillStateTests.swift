import XCTest
@testable import Strand

/// Pins the Devices card's state-pill priority (#221): "Connected · not paired" must beat "Active · Live"
/// but yield to a reboot's "Reconnecting…". Mirrors the Kotlin `DevicePillStateTest` exactly — a silent
/// reorder on either platform would otherwise only be caught by eyeballing a screenshot.
final class DevicePillStateTests: XCTestCase {

    func testBondRefused_beatsActiveLive_butYieldsToReconnecting() {
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: true, isReconnecting: false,
                                     bondRefused: true, isLiveConnected: true).label,
            "Connected · not paired")
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: true, isReconnecting: true,
                                     bondRefused: true, isLiveConnected: true).label,
            "Reconnecting…")
    }

    func testNormalConnect_isUnaffected() {
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: true, isReconnecting: false,
                                     bondRefused: false, isLiveConnected: true).label,
            "Active · Live")
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: true, isReconnecting: false,
                                     bondRefused: false, isLiveConnected: false).label,
            "Active")
    }

    func testNonActiveAndArchived() {
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: false, isReconnecting: false,
                                     bondRefused: false, isLiveConnected: false).label,
            "Paired")
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: true, isActive: false, isReconnecting: false,
                                     bondRefused: false, isLiveConnected: false).label,
            "Removed")
    }

    /// An UNPROVEN link must not pulse "Active · Live". `isLiveConnected` is built from `live.connected`,
    /// which BLEManager publishes the instant CoreBluetooth's didConnect fires — before service discovery,
    /// before any characteristic exists, before a byte moves. With iOS state restoration handing back a
    /// `.connected` peripheral whose strap had died hours earlier, the card confidently advertised "Live"
    /// for a corpse. "Active" is the honest fallback: still your active device, no claim about data.
    func testUnprovenLink_doesNotClaimLive() {
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: true, isReconnecting: false,
                                     bondRefused: false, isLiveConnected: true, linkProven: false).label,
            "Active")
        XCTAssertFalse(
            DevicePillState.resolve(isArchived: false, isActive: true, isReconnecting: false,
                                     bondRefused: false, isLiveConnected: true, linkProven: false).pulsing)
    }

    /// A proven link is unchanged, and the higher-priority states still win over an unproven one — the
    /// #221 ordering must not be perturbed by the new gate.
    func testLinkProven_defaultsTrueAndKeepsThePriorityOrder() {
        // Default (omitted) linkProven keeps every existing call site behaving exactly as before.
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: true, isReconnecting: false,
                                     bondRefused: false, isLiveConnected: true).label,
            "Active · Live")
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: true, isReconnecting: false,
                                     bondRefused: false, isLiveConnected: true, linkProven: true).label,
            "Active · Live")
        // Reconnecting and bond-refused still outrank the live/unproven decision entirely.
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: true, isReconnecting: true,
                                     bondRefused: false, isLiveConnected: true, linkProven: false).label,
            "Reconnecting…")
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: true, isReconnecting: false,
                                     bondRefused: true, isLiveConnected: true, linkProven: false).label,
            "Connected · not paired")
        // An unproven link on a NON-active card is still just "Paired".
        XCTAssertEqual(
            DevicePillState.resolve(isArchived: false, isActive: false, isReconnecting: false,
                                     bondRefused: false, isLiveConnected: false, linkProven: false).label,
            "Paired")
    }
}
