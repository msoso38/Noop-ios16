import Foundation
import StrandAnalytics

/// The charging state the app is willing to STAND BEHIND — the strap's reported flag, corroborated (or
/// rescued) by the one thing that cannot lie: the state-of-charge going UP.
///
/// Why this exists. The `battery_charging` flag is a single bit the decoder reads at a FIXED offset in the
/// BATTERY_LEVEL event (4.0 @26, 5.0 @30). For the 5.0 that offset is **unverified**: `decodeWhoop5Event`'s
/// own documentation states the deci-percent SoC was "confirmed by a clean monotonic discharge across a
/// real capture (49.9 → 47.7 %)" and makes no such claim for the charge bit — it is derived from the same
/// "+4 rule" as the other fields and has never been checked against a labelled charging session. Field
/// evidence says it is wrong or at least incomplete: a 5/MG sitting on its charging puck reported
/// `battery_charging = 0` while its SoC climbed. The `ch <= 1` guard fails closed on a wild byte, but it
/// cannot catch a byte that is plausibly 0 and simply means something else.
///
/// So the app used to state "not charging" — and, worse, show a "~2 days left" DISCHARGE estimate — about a
/// strap that was visibly on the charger. That's an unverified bit being reported as fact.
///
/// The fix is to stop treating the bit as the only witness. A rising SoC is direct, family-independent,
/// decoder-independent evidence of a charge, and this codebase ALREADY trusts exactly that inference:
/// `BatteryEstimator.chargeStepPct` is documented as "a SoC rise larger than this (percentage points)
/// between two consecutive readings marks a CHARGE", and the runtime estimate has always restarted its
/// discharge run on it. This reuses that same threshold rather than inventing a second notion of "charging".
///
/// Strictly additive: a confirmed `true` is still `true`, and inference can only ever ADD a charge, never
/// deny one. If neither witness speaks, it answers `nil` (unknown) rather than asserting "not charging".
enum StrapChargeInference {

    /// A rise must be observed within this window to count as "charging NOW". The strap emits BATTERY_LEVEL
    /// every ~8 min, so two consecutive readings are ~8 min apart; 20 min allows a missed event without
    /// letting an hour-old charge keep claiming the strap is still on the puck.
    static let recentRiseWindowSeconds = 20 * 60

    /// Is the SoC series RISING fast enough to only be explicable as a charge?
    ///
    /// Uses `BatteryEstimator.chargeStepPct` (1.0 pp) between the two most recent readings, strictly
    /// greater-than — which is what makes it robust against the quantisation seam: a 5/MG's live SoC comes
    /// from 0x2A19 as a whole-percent u8, so consecutive readings can legitimately step by exactly 1.0 pp
    /// without a charge. A real charge is nowhere near that subtle: a WHOOP 5 charges at roughly 50 pp/h
    /// against a ~1.65 pp/h discharge, so a genuine charge moves several points per ~8-minute event.
    static func isRising(samples: [(ts: Int, soc: Double)],
                         nowUnix: Int,
                         windowSeconds: Int = recentRiseWindowSeconds) -> Bool {
        guard samples.count >= 2 else { return false }
        let sorted = samples.sorted { $0.ts < $1.ts }
        guard let last = sorted.last, let prev = sorted.dropLast().last else { return false }
        guard nowUnix - last.ts <= windowSeconds else { return false }   // stale: says nothing about NOW
        guard last.ts - prev.ts <= windowSeconds else { return false }   // a gap that wide isn't a slope
        return (last.soc - prev.soc) > BatteryEstimator.chargeStepPct
    }

    /// The charging state to display and to gate the runtime estimate on.
    ///
    /// - `true`  — the strap said so, OR its charge is measurably climbing.
    /// - `false` — the strap said not-charging AND the SoC is not climbing. Two witnesses agreeing.
    /// - `nil`   — no flag yet and no rise seen. Unknown, and the UI should say nothing rather than imply
    ///   "not charging" (which is exactly the false statement this whole type exists to stop).
    static func resolve(flag: Bool?,
                        samples: [(ts: Int, soc: Double)],
                        nowUnix: Int,
                        windowSeconds: Int = recentRiseWindowSeconds) -> Bool? {
        if flag == true { return true }
        if isRising(samples: samples, nowUnix: nowUnix, windowSeconds: windowSeconds) { return true }
        return flag   // false (corroborated by a non-rising SoC) or nil (nothing to say)
    }
}
