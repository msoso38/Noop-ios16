import Foundation
import WhoopProtocol

/// Whether to (re)issue a standard Battery Level (0x2A19) READ on the live link.
///
/// The failure this exists for: the ONLY 0x2A19 read was a one-shot fired from
/// `didDiscoverCharacteristicsFor` — i.e. PRE-bond, on an unencrypted link. A WHOOP 5/MG rejects that read
/// ("Authentication is insufficient", the same refusal that gates its notify subscriptions), the failure
/// landed in `didUpdateValueFor`'s error branch, was logged, and was then DROPPED. Nothing ever retried it.
/// `enableLiveNotifications` — whose own comment claims it recovers the "standard HR/battery that failed
/// pre-bond" — only ever re-issued `setNotifyValue`, never `readValue`. That asymmetry is the whole bug: HR
/// recovers because it is notify-driven and the strap pushes it at 1 Hz, so the UI says "Live" while the
/// battery percent stays `nil` for the entire session. On a strap that then died on the user's wrist, the
/// app could not show a single battery reading all day.
///
/// FAMILY-CORRECT BY CONSTRUCTION (#77): a WHOOP 4.0's 0x2A19 is a firmware STUB that always reports 100,
/// so reading it would flash a fake 100% over the real charge. The 4.0's true value comes only from the
/// proprietary `GET_BATTERY_LEVEL` command (COMMAND_RESPONSE, u16/10) that the keep-alive already sends
/// every ~60 s. Refusing the read for `.whoop4` here is what keeps that invariant intact, and it is pinned
/// by `StrapBatteryReadPolicyTests` so a future edit can't quietly re-open #77.
enum StrapBatteryReadPolicy {

    /// Floor between reads, matching the keep-alive tick (`BLEManager.keepAliveIntervalSeconds`).
    ///
    /// The throttle is load-bearing, not politeness: `didWriteValueFor` re-enters the 5/MG post-bond branch
    /// on EVERY `.withResponse` ack — including every HISTORY_END ack during a multi-minute historical
    /// offload — and that branch calls `enableLiveNotifications` unconditionally. The existing work there is
    /// idempotent-cheap (`where !c.isNotifying` collapses to a no-op once subscribed); an unthrottled
    /// `readValue` would NOT be, and would fire hundreds of ATT reads at a strap mid-sync. With the floor,
    /// the steady-state cadence is one read per keep-alive tick, which is strictly lighter than the 4.0's
    /// existing every-60 s `GET_BATTERY_LEVEL` poll.
    static let minIntervalSeconds: TimeInterval = 30

    /// - Parameters:
    ///   - family: the connected strap's family. `.whoop4` always refuses (see #77 above).
    ///   - canRead: the characteristic exists and advertises `.read`.
    ///   - lastReadAt: when we last issued a read this session; nil = never, which always reads (this is the
    ///     self-heal — the first post-bond call re-issues the read the pre-bond link refused).
    ///   - now: monotonic-ish seconds; injected so the throttle is testable without a clock.
    static func shouldRead(family: DeviceFamily,
                           canRead: Bool,
                           lastReadAt: TimeInterval?,
                           now: TimeInterval) -> Bool {
        guard family != .whoop4 else { return false }   // #77: the 4.0's 0x2A19 is a stub-100
        guard canRead else { return false }
        guard let last = lastReadAt else { return true }
        return now - last >= minIntervalSeconds
    }
}
