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
///
/// ────────────────────────────────────────────────────────────────────────────────────────────────────
/// KNOWN ISSUE — the premise is CONFIRMED, but this threshold probably never fires. NOT YET FIXED.
///
/// Checked against the cloud mirror on 2026-07-26 (GitHub issues are disabled on this fork, so the
/// finding lives here and in docs/bugs/2026-07-15-…md §A6 rather than in a tracker).
///
/// CONFIRMED — the bit really is wrong. One charging session with unambiguous ground truth
/// (CHARGING_ON ×10, BATTERY_PACK_CONNECTED ×3; 2026-07-15 11:07→13:34 MDT; SoC 4.7% → 100.0%) carries
/// `charging = false` on every one of its ~300 readings. Not `nil` — FALSE, i.e. the BATTERY_LEVEL path
/// decoded the bit and the strap asserted "not charging" while going from nearly flat to full.
///
/// BUT — `isRising` requires > `chargeStepPct` (1.0 pp) between CONSECUTIVE readings, and that charge
/// stepped +0.3…+0.7 pp per reading at a ~30 s cadence. It never once cleared 1.0. The
/// `recentRiseWindowSeconds` comment below assumes the opposite ("~8 min apart"), which is what makes
/// 1.0 pp look like a low bar; at the real cadence it is not. The live path looks no better: a 5/MG's
/// live SoC is whole-percent 0x2A19, so a charge steps by exactly +1, and the strict `>` that exists to
/// survive that quantisation also excludes exactly 1.0.
///
/// So `chargingEffective` likely degenerates to the raw flag and this type is inert for the very case it
/// was written for. It is not HARMFUL — the inference is additive, so behaviour equals pre-fix — but it
/// is not doing its job either. Do not read the passing tests as coverage: the synthetic samples in
/// `StrapChargeInferenceTests` step by more than 1.0 pp, which is exactly why they pass.
///
/// LIKELY FIX: make it a RATE test (pp per unit time) instead of a fixed per-reading delta. A WHOOP 5
/// charges at ~50 pp/h against a ~1.65 pp/h discharge — a ~30x separation that holds at any cadence,
/// where a fixed step does not.
///
/// UNMEASURED, and needed before picking a constant: the `battery` table this came from is fed by the
/// decoded OFFLOAD stream, not the live path that actually populates `LiveState.batterySamples`. The
/// ~30 s cadence is therefore evidence about banked records; the LIVE cadence and quantisation are still
/// unconfirmed and want a real charging session to settle.
/// ────────────────────────────────────────────────────────────────────────────────────────────────────
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
