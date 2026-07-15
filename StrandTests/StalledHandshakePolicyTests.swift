import XCTest
@testable import Strand

/// `StalledHandshakePolicy` — the cancel-and-rescan decision for a link CoreBluetooth calls connected but
/// that never became usable. Pure value logic, no CoreBluetooth seam.
///
/// The case that matters is `testRestoredZombie_recovers`: it encodes the real on-device failure (iOS state
/// restoration returns a `.connected` peripheral whose strap is dead, the CLIENT_HELLO is never acked, so
/// `didWriteValueFor` never fires — and that callback is the ONLY caller of `startKeepAlive()`, so the 120 s
/// liveness watchdog that would have bounced the link never even arms). Every other test here is a guard
/// AGAINST firing, because a watchdog that bounces healthy links is worse than the bug it fixes.
final class StalledHandshakePolicyTests: XCTestCase {

    /// Defaults describe a link in the exact stalled state; each test flips ONE input.
    private func recover(connected: Bool = true,
                         handshakeDone: Bool = false,
                         sawData: Bool = false,
                         intentionalDisconnect: Bool = false,
                         autoReconnectPaused: Bool = false,
                         bondRefused: Bool = false) -> Bool {
        StalledHandshakePolicy.shouldRecover(connected: connected,
                                             handshakeDone: handshakeDone,
                                             sawData: sawData,
                                             intentionalDisconnect: intentionalDisconnect,
                                             autoReconnectPaused: autoReconnectPaused,
                                             bondRefused: bondRefused)
    }

    /// THE bug: connected, no handshake, not one byte ever received, and nothing else owns the link.
    /// Left alone this spun for 40+ minutes ("Backfill: deferred — connect handshake not done yet") and
    /// cost a full night of biometrics.
    func testRestoredZombie_recovers() {
        XCTAssertTrue(recover())
    }

    /// A completed handshake means the strap acked us — this is a healthy link, hands off.
    func testCompletedHandshake_neverRecovers() {
        XCTAssertFalse(recover(handshakeDone: true))
        XCTAssertFalse(recover(handshakeDone: true, sawData: true))
    }

    /// The strap is talking (e.g. a 5/MG streaming HR over the standard 0x2A37 profile while its puffin
    /// handshake lags). Bouncing would throw away a working HR stream; the 120 s liveness fuse already owns
    /// a link that goes quiet later.
    func testDataFlowing_neverRecovers() {
        XCTAssertFalse(recover(sawData: true))
    }

    /// The link already dropped — didDisconnectPeripheral's rescan owns it.
    func testNotConnected_neverRecovers() {
        XCTAssertFalse(recover(connected: false))
    }

    /// A user teardown is in flight; reconnecting would fight it.
    func testIntentionalDisconnect_neverRecovers() {
        XCTAssertFalse(recover(intentionalDisconnect: true))
    }

    /// #617/#747: the bond-loop give-up DELIBERATELY stopped hammering this strap and told the user to free
    /// it. A bounce here restarts precisely the loop that machinery just paused.
    func testAutoReconnectPaused_neverRecovers() {
        XCTAssertFalse(recover(autoReconnectPaused: true))
    }

    /// #78/#221: a refused bond ("Connected · not paired") is diagnosed, surfaced and user-actionable — not
    /// a stall. Churning the link would bury the guidance under reconnect noise.
    func testBondRefused_neverRecovers() {
        XCTAssertFalse(recover(bondRefused: true))
    }

    /// The fuse must clear the 60 s reboot settle backstop (so a reboot reconnect resolves itself first) and
    /// stay under the 120 s liveness fuse (so the data-driven watchdog owns any link that has data).
    func testFuseSitsBetweenTheRebootSettleAndTheLivenessFuse() {
        XCTAssertGreaterThan(StalledHandshakePolicy.fuseSeconds, 60)
        XCTAssertLessThan(StalledHandshakePolicy.fuseSeconds, 120)
    }
}
