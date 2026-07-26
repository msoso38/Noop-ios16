import Foundation

/// What to tell the user about strap-history recovery, as a pure decision.
///
/// This exists because the recovery was INVISIBLE. The app already showed "Syncing strap history…" while a
/// single offload session ran, but nothing said how far behind the strap was, whether the gap was closing,
/// or whether a quiet app meant "done" or "waiting 15 minutes for the next 60-second session". A user
/// sitting on an 18-hour backlog spent hours unable to tell whether his data was lost or merely pending —
/// and the honest answer ("~15h behind, recovering") was already computable from numbers BLEManager tracks.
///
/// Both inputs are already on `LiveState`: `strapRange.newestUnix` (the newest record the strap says it
/// holds, from GET_DATA_RANGE) and `dataFrontierUnix` (the newest record we've actually persisted, read
/// once per offload session for the auto-continue decision). The gap between them IS the backlog.
enum BackfillProgress: Equatable {
    /// Nothing honest to say — no link, or the strap hasn't answered a range yet.
    case unknown
    /// Behind, and actively draining right now.
    case recovering(behindSeconds: Int, frontierUnix: Int)
    /// Behind, but not draining this instant (between sessions / waiting out the periodic floor).
    case pending(behindSeconds: Int, frontierUnix: Int)
    /// The frontier is within `behindGapSeconds` of the strap's newest record — as caught up as the strap
    /// can tell us. Deliberately the SAME threshold `BackfillContinuation` stops draining at, so the
    /// readout can never say "behind" about a gap the drain has already decided is closed.
    case upToDate

    /// `nowUnix` is unused for the decision itself (the gap is strap-newest vs our frontier, both strap-side
    /// timestamps) but is taken so a caller can't be tempted to reach for a clock inside a pure function.
    static func resolve(connected: Bool,
                        backfilling: Bool,
                        frontierUnix: Int?,
                        strapNewestUnix: Int?,
                        behindGapSeconds: Int = BackfillContinuation.defaultBehindGapSeconds) -> BackfillProgress {
        guard connected, let frontier = frontierUnix, let newest = strapNewestUnix else { return .unknown }
        let behind = newest - frontier
        // A frontier AHEAD of the strap's reported newest is a stale/wrong-epoch range answer (#451) or a
        // future-dated strap clock (#928), not negative backlog. Claim nothing rather than a nonsense figure.
        guard behind > behindGapSeconds else { return behind >= -behindGapSeconds ? .upToDate : .unknown }
        return backfilling
            ? .recovering(behindSeconds: behind, frontierUnix: frontier)
            : .pending(behindSeconds: behind, frontierUnix: frontier)
    }

    /// How far behind, in whole hours/minutes — nil when there's nothing to report.
    var behindSeconds: Int? {
        switch self {
        case let .recovering(b, _), let .pending(b, _): return b
        case .unknown, .upToDate: return nil
        }
    }

    /// The strap-side instant our data currently reaches — the "we have your data up to HERE" anchor.
    var frontierUnix: Int? {
        switch self {
        case let .recovering(_, f), let .pending(_, f): return f
        case .unknown, .upToDate: return nil
        }
    }

    /// Compact "~15h behind" / "~42m behind". Whole hours once past an hour, minutes below it — the same
    /// shape the battery runtime estimate uses, so the two readouts read as one family.
    ///
    /// A plain (unlocalized) string, deliberately: this mirrors `DevicePillState.label`, whose call site
    /// wraps it as `StatePill(LocalizedStringKey(label))`. Keeping the resolver free of `String(localized:)`
    /// is what lets the tests assert on the decision rather than on the user's current locale.
    var behindLabel: String? {
        guard let s = behindSeconds else { return nil }
        if s >= 3600 { return "~\(s / 3600)h behind" }
        return "~\(max(1, s / 60))m behind"
    }
}
