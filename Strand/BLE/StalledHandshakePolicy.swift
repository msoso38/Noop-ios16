import Foundation

/// Recovery decision for a link that CoreBluetooth calls connected but that never became usable.
///
/// The failure this exists for: iOS state restoration hands back a peripheral whose `state` is already
/// `.connected`, so `willRestoreState` marks the link up, seeds the bond flags (a restored link WAS bonded
/// once) and re-discovers services — but the CLIENT_HELLO / bond write that follows is `.withResponse`, and
/// if the strap is wedged or dead it is never acked. `didWriteValueFor` therefore never fires, which is the
/// ONLY place that sets `connectHandshakeDone` **and the only place that calls `startKeepAlive()`** — so the
/// 120 s liveness watchdog inside `keepAliveFire` (the machinery that would have bounced exactly this link)
/// is never armed. The app then retries the same dead handle forever: an on-device strap log showed
/// "Backfill: deferred — connect handshake not done yet" repeating every ~1–3 min for 40+ minutes, straight
/// through the strap coming off its charger, and cost the user a whole night of biometrics.
///
/// So the recovery is deliberately NOT a new reconnect mechanism: it cancels the peripheral connection and
/// lets `didDisconnectPeripheral`'s existing 3 s rescan (the same recovery the liveness watchdog uses at its
/// own fuse) bring the link back. This type only decides WHETHER to pull that trigger. Pure + no
/// CoreBluetooth, so the decision is unit-testable without a radio.
enum StalledHandshakePolicy {

    /// How long a link may sit connected-but-unproven before we cancel it.
    ///
    /// Chosen against the fuses this codebase already runs, not invented:
    ///   * a healthy handshake resolves in well under a second (the post-ack work defers the offload by 1.5 s),
    ///     so 90 s is ~60× the expected latency — it can never clip a strap that is merely slow;
    ///   * it sits ABOVE the 60 s reboot settle backstop, so a reboot-driven reconnect resolves itself first
    ///     (`noteRebootReconnectIfNeeded`) instead of being pre-empted by a bounce;
    ///   * it sits BELOW the 120 s `keepAliveFire` liveness fuse, so on any link that IS delivering data the
    ///     data-driven watchdog stays the one making the call — `shouldRecover`'s `sawData` guard defers to it
    ///     anyway, so the two can never fight over the same link.
    static let fuseSeconds: TimeInterval = 90

    /// Cancel-and-rescan this link?
    ///
    /// Every input is a reason to STAY OUT of the way, which is the point — this fires only on the narrow
    /// case nothing else owns:
    /// - `connected`: the link already dropped → the normal disconnect path owns the rescan.
    /// - `handshakeDone`: the connect handshake completed → this is a healthy link, never touch it.
    /// - `sawData`: ANY notification arrived since the link came up (0x2A37 HR, a puffin frame, a battery
    ///   read). The strap is talking to us and is useful even if the puffin handshake lags, and the 120 s
    ///   liveness fuse already covers a link that goes quiet later. Bouncing here would throw away a working
    ///   HR stream — the regression this guard exists to prevent.
    /// - `intentionalDisconnect`: a user teardown is in flight.
    /// - `autoReconnectPaused`: the #617/#747 bond-loop give-up deliberately STOPPED hammering this strap and
    ///   told the user to free it; a bounce here would restart exactly the loop that machinery just paused.
    /// - `bondRefused`: the #78/#221 refusal state ("Connected · not paired") is a diagnosed, surfaced,
    ///   user-actionable condition with its own guidance on the Devices card. It is not a stall, and churning
    ///   the link would only hide the guidance behind reconnect noise.
    static func shouldRecover(connected: Bool,
                              handshakeDone: Bool,
                              sawData: Bool,
                              intentionalDisconnect: Bool,
                              autoReconnectPaused: Bool,
                              bondRefused: Bool) -> Bool {
        guard connected else { return false }
        guard !handshakeDone else { return false }
        guard !sawData else { return false }
        guard !intentionalDisconnect else { return false }
        guard !autoReconnectPaused else { return false }
        guard !bondRefused else { return false }
        return true
    }
}
