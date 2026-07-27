import XCTest
import WhoopProtocol
@testable import Strand

/// `StrapBatteryReadPolicy` — whether to (re)issue a standard Battery Level (0x2A19) read.
///
/// `testWhoop4_neverReads` is the load-bearing one: it pins #77. A WHOOP 4.0's 0x2A19 is a firmware stub
/// that always reports 100, so reading it flashes a fake 100% over the strap's real charge (the 4.0's true
/// value comes only from the proprietary GET_BATTERY_LEVEL command). The retry that fixes the 5/MG must
/// never reach the 4.0, and that is a family gate a future refactor could silently drop.
final class StrapBatteryReadPolicyTests: XCTestCase {
    private let floor = StrapBatteryReadPolicy.minIntervalSeconds   // 30

    /// #77: the 4.0's 0x2A19 is a stub-100. No read, ever — not even the first one.
    func testWhoop4_neverReads() {
        XCTAssertFalse(StrapBatteryReadPolicy.shouldRead(family: .whoop4, canRead: true,
                                                          lastReadAt: nil, now: 1000))
        XCTAssertFalse(StrapBatteryReadPolicy.shouldRead(family: .whoop4, canRead: true,
                                                          lastReadAt: 0, now: 1_000_000))
    }

    /// The self-heal: a 5/MG that has never had a successful read reads immediately. This is the retry the
    /// pre-bond rejection ("Authentication is insufficient") needed and never got.
    func testWhoop5_firstReadAlwaysRuns() {
        XCTAssertTrue(StrapBatteryReadPolicy.shouldRead(family: .whoop5, canRead: true,
                                                         lastReadAt: nil, now: 1000))
    }

    /// The throttle: `enableLiveNotifications` re-runs on EVERY .withResponse ack, including every
    /// HISTORY_END during an offload. Without the floor the retry would storm the strap with ATT reads.
    func testWhoop5_throttledWithinTheFloor() {
        XCTAssertFalse(StrapBatteryReadPolicy.shouldRead(family: .whoop5, canRead: true,
                                                          lastReadAt: 1000, now: 1000 + floor - 1))
        XCTAssertTrue(StrapBatteryReadPolicy.shouldRead(family: .whoop5, canRead: true,
                                                         lastReadAt: 1000, now: 1000 + floor))
        XCTAssertTrue(StrapBatteryReadPolicy.shouldRead(family: .whoop5, canRead: true,
                                                         lastReadAt: 1000, now: 1000 + floor * 10))
    }

    /// A characteristic without the .read property can't be read regardless of family.
    func testNoReadProperty_neverReads() {
        XCTAssertFalse(StrapBatteryReadPolicy.shouldRead(family: .whoop5, canRead: false,
                                                          lastReadAt: nil, now: 1000))
    }

    /// The floor matches the keep-alive tick, so the steady-state 5/MG cadence is one read per tick —
    /// strictly lighter than the 4.0's existing every-60s GET_BATTERY_LEVEL poll.
    func testFloorMatchesTheKeepAliveTick() {
        XCTAssertEqual(StrapBatteryReadPolicy.minIntervalSeconds,
                       TimeInterval(BLEManager.keepAliveIntervalSeconds))
    }
}
