import XCTest
@testable import StrandAnalytics

final class DayOwnerResolverTests: XCTestCase {
    func testActiveStrapOwnsDayItHasData() {
        let candidates = [
            DayOwnerResolver.Candidate(deviceId: "my-whoop", priority: 0, hasData: true),
            DayOwnerResolver.Candidate(deviceId: "oura", priority: 2, hasData: true),
        ]
        XCTAssertEqual(
            DayOwnerResolver.resolve(day: "2026-06-15", lockedOwner: nil, candidates: candidates),
            "my-whoop"
        )
    }

    func testImportOnlyFillsGap() {
        let candidates = [
            DayOwnerResolver.Candidate(deviceId: "my-whoop", priority: 0, hasData: false),
            DayOwnerResolver.Candidate(deviceId: "oura", priority: 2, hasData: true),
        ]
        XCTAssertEqual(
            DayOwnerResolver.resolve(day: "2026-06-15", lockedOwner: nil, candidates: candidates),
            "oura"
        )
    }

    func testLockedOwnerAlwaysWins() {
        let candidates = [
            DayOwnerResolver.Candidate(deviceId: "my-whoop", priority: 0, hasData: false),
            DayOwnerResolver.Candidate(deviceId: "oura", priority: 2, hasData: true),
        ]
        XCTAssertEqual(
            DayOwnerResolver.resolve(day: "2026-06-15", lockedOwner: "my-whoop", candidates: candidates),
            "my-whoop"
        )
    }

    func testNoDataYieldsNil() {
        let candidates = [
            DayOwnerResolver.Candidate(deviceId: "my-whoop", priority: 0, hasData: false),
        ]
        XCTAssertNil(
            DayOwnerResolver.resolve(day: "2026-06-15", lockedOwner: nil, candidates: candidates)
        )
    }

    // #oura(§6.15): an active Oura ring with only a bare check_sleep window (richData:false) must NOT
    // displace an imported WHOOP night that has a full HR-backed record (richData:true) — same duration,
    // but the import keeps its stages/recovery/HRV. The richer record wins despite the worse priority.
    func testRichImportBeatsActiveWindowOnlyRing() {
        let candidates = [
            DayOwnerResolver.Candidate(deviceId: "oura", priority: 0, hasData: true, richData: false),
            DayOwnerResolver.Candidate(deviceId: "whoop-import", priority: 2, hasData: true, richData: true),
        ]
        XCTAssertEqual(
            DayOwnerResolver.resolve(day: "2026-07-08", lockedOwner: nil, candidates: candidates),
            "whoop-import"
        )
    }

    // …but on a day nothing richer recorded, the window-only ring is the sole data source and owns it.
    func testWindowOnlyRingOwnsDayWithNoRicherRecord() {
        let candidates = [
            DayOwnerResolver.Candidate(deviceId: "oura", priority: 0, hasData: true, richData: false),
            DayOwnerResolver.Candidate(deviceId: "whoop-import", priority: 2, hasData: false, richData: true),
        ]
        XCTAssertEqual(
            DayOwnerResolver.resolve(day: "2026-07-09", lockedOwner: nil, candidates: candidates),
            "oura"
        )
    }

    // Two window-only rings (both richData:false) still fall back to device priority (active wins).
    func testWindowOnlyTieBreaksOnPriority() {
        let candidates = [
            DayOwnerResolver.Candidate(deviceId: "oura-active", priority: 0, hasData: true, richData: false),
            DayOwnerResolver.Candidate(deviceId: "oura-other", priority: 1, hasData: true, richData: false),
        ]
        XCTAssertEqual(
            DayOwnerResolver.resolve(day: "2026-07-09", lockedOwner: nil, candidates: candidates),
            "oura-active"
        )
    }
}
