import XCTest
@testable import Strand

/// `BackfillProgress` — the honest answer to "is my data lost, or merely pending?".
///
/// The gap this closes: the app said "Syncing strap history…" while a session ran and NOTHING between
/// sessions, so an idle app looked finished when it was 18 hours behind waiting out a 15-minute floor. Both
/// numbers were already tracked (the strap's reported newest, and our persisted frontier) — nothing ever
/// subtracted them and said so.
final class BackfillProgressTests: XCTestCase {
    private let now = 1_800_000_000
    private var frontier18hBehind: Int { now - 18 * 3600 }

    /// The user's real case: 18h behind, actively draining.
    func testRecovering() {
        let p = BackfillProgress.resolve(connected: true, backfilling: true,
                                          frontierUnix: frontier18hBehind, strapNewestUnix: now)
        XCTAssertEqual(p, .recovering(behindSeconds: 18 * 3600, frontierUnix: frontier18hBehind))
        XCTAssertEqual(p.behindLabel, "~18h behind")
        XCTAssertEqual(p.frontierUnix, frontier18hBehind)
    }

    /// The state that used to be INVISIBLE: behind, but idle between sessions. Silence read as "done".
    func testPending_isDistinctFromRecovering() {
        let p = BackfillProgress.resolve(connected: true, backfilling: false,
                                          frontierUnix: frontier18hBehind, strapNewestUnix: now)
        XCTAssertEqual(p, .pending(behindSeconds: 18 * 3600, frontierUnix: frontier18hBehind))
        XCTAssertEqual(p.behindLabel, "~18h behind")
    }

    /// Caught up uses the SAME threshold the drain stops at, so the readout can never say "behind" about a
    /// gap `BackfillContinuation` has already decided is closed.
    func testUpToDate_matchesTheDrainsOwnThreshold() {
        let gap = BackfillContinuation.defaultBehindGapSeconds
        XCTAssertEqual(BackfillProgress.resolve(connected: true, backfilling: false,
                                                 frontierUnix: now - gap, strapNewestUnix: now),
                       .upToDate)
        XCTAssertEqual(BackfillProgress.resolve(connected: true, backfilling: false,
                                                 frontierUnix: now - gap - 1, strapNewestUnix: now)
                        .behindSeconds, gap + 1)
        XCTAssertNil(BackfillProgress.resolve(connected: true, backfilling: false,
                                               frontierUnix: now, strapNewestUnix: now).behindLabel)
    }

    /// Not connected, or the strap hasn't answered a range — claim nothing.
    func testUnknown() {
        XCTAssertEqual(BackfillProgress.resolve(connected: false, backfilling: false,
                                                 frontierUnix: frontier18hBehind, strapNewestUnix: now),
                       .unknown)
        XCTAssertEqual(BackfillProgress.resolve(connected: true, backfilling: false,
                                                 frontierUnix: nil, strapNewestUnix: now),
                       .unknown)
        XCTAssertEqual(BackfillProgress.resolve(connected: true, backfilling: false,
                                                 frontierUnix: frontier18hBehind, strapNewestUnix: nil),
                       .unknown)
    }

    /// A frontier far AHEAD of the strap's reported newest is a stale/wrong-epoch range (#451) or a
    /// future-dated clock (#928) — not negative backlog. Say nothing rather than a nonsense figure.
    func testFrontierAheadOfStrap_claimsNothing() {
        XCTAssertEqual(BackfillProgress.resolve(connected: true, backfilling: false,
                                                 frontierUnix: now, strapNewestUnix: now - 30 * 24 * 3600),
                       .unknown)
    }

    /// Sub-hour gaps read in minutes, so a nearly-caught-up drain doesn't round to a bare "~0h behind".
    func testMinuteLabel() {
        XCTAssertEqual(BackfillProgress.resolve(connected: true, backfilling: true,
                                                 frontierUnix: now - 42 * 60, strapNewestUnix: now)
                        .behindLabel, "~42m behind")
    }
}
