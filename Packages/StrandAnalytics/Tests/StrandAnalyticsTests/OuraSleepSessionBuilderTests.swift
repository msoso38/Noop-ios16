import XCTest
@testable import StrandAnalytics

/// Tests for building a NOOP sleep session from the ring's OWN anchored phase timeline
/// (OURA_SLEEP_PHASE events), the bridge that lets an Oura night flow through the gravity-free path.
final class OuraSleepSessionBuilderTests: XCTestCase {
    private let base = 1_700_000_000

    private func build(_ phases: [(ts: Int, stage: Int)],
                       minSessionMinutes: Int = 60,
                       splitGapMinutes: Int = 120) -> [SleepSession] {
        OuraSleepSessionBuilder.sessions(fromPhases: phases,
                                         minSessionMinutes: minSessionMinutes,
                                         splitGapMinutes: splitGapMinutes)
    }

    // MARK: - Stage mapping

    func testStageCodeMapping() {
        XCTAssertEqual(OuraSleepSessionBuilder.stageName(forPhaseCode: 0), "wake")
        XCTAssertEqual(OuraSleepSessionBuilder.stageName(forPhaseCode: 1), "light")
        XCTAssertEqual(OuraSleepSessionBuilder.stageName(forPhaseCode: 2), "deep")
        XCTAssertEqual(OuraSleepSessionBuilder.stageName(forPhaseCode: 3), "rem")
        XCTAssertNil(OuraSleepSessionBuilder.stageName(forPhaseCode: 7), "unknown code is never guessed")
    }

    // MARK: - Core build

    func testBuildsOneSessionWithStagesAndEfficiency() {
        // light 30m | wake 30m | deep 60m | rem 30m(end) -> 150m in bed, 120m asleep -> eff 0.8.
        let s = build([
            (base + 0,    1),   // light
            (base + 1800, 0),   // wake
            (base + 3600, 2),   // deep
            (base + 7200, 3),   // rem
            (base + 9000, 0),   // wake (end marker)
        ])
        XCTAssertEqual(s.count, 1)
        let night = s[0]
        XCTAssertEqual(night.start, base)
        XCTAssertEqual(night.end, base + 9000)
        XCTAssertEqual(night.stages.map { $0.stage }, ["light", "wake", "deep", "rem"])
        XCTAssertEqual(night.stages.map { $0.end - $0.start }, [1800, 1800, 3600, 1800])
        XCTAssertEqual(night.efficiency, 0.8, accuracy: 0.0001)
        XCTAssertNil(night.restingHR)   // enriched downstream, never fabricated from phase codes
        XCTAssertNil(night.avgHRV)
    }

    func testAdjacentSameStageSegmentsAreMerged() {
        // Two consecutive light epochs collapse into one [0, 3600) light segment.
        let s = build([
            (base + 0,    1),   // light
            (base + 1800, 1),   // light (merges)
            (base + 3600, 2),   // deep
            (base + 5400, 0),   // wake (end)
        ])
        XCTAssertEqual(s.count, 1)
        XCTAssertEqual(s[0].stages.map { $0.stage }, ["light", "deep"])
        XCTAssertEqual(s[0].stages[0].start, base)
        XCTAssertEqual(s[0].stages[0].end, base + 3600)   // merged 60m of light
    }

    func testUnknownPhaseCodeSegmentIsDropped() {
        // A misframed/unknown code (7) contributes no segment and no asleep time - never guessed.
        let s = build([
            (base + 0,    1),   // light
            (base + 1800, 7),   // unknown -> dropped
            (base + 3600, 2),   // deep
            (base + 5400, 0),   // wake (end)
        ])
        XCTAssertEqual(s.count, 1)
        XCTAssertEqual(s[0].stages.map { $0.stage }, ["light", "deep"])
    }

    // MARK: - Session splitting / gating

    func testLargeGapSplitsIntoSeparateSessions() {
        let s = build([
            (base + 0,     1),                 // run A: light
            (base + 3600,  0),                 // run A: wake (end) - 60m span
            (base + 3600 + 7260, 2),           // >120m gap -> run B: deep
            (base + 3600 + 7260 + 3600, 0),    // run B: wake (end) - 60m span
        ])
        XCTAssertEqual(s.count, 2)
        XCTAssertEqual(s[0].stages.first?.stage, "light")
        XCTAssertEqual(s[1].stages.first?.stage, "deep")
    }

    func testTooShortRunIsDropped() {
        // 30m span < the 60m floor -> not a session.
        XCTAssertTrue(build([(base + 0, 1), (base + 1800, 0)]).isEmpty)
    }

    func testAllWakeRunIsNotASession() {
        // A full-length run with zero asleep time is not sleep.
        XCTAssertTrue(build([(base + 0, 0), (base + 3600, 0)]).isEmpty)
    }

    func testDuplicateTimestampsCollapseKeepingFirst() {
        // A doubled event at the same ts must not create a zero-length segment or shift the stage.
        let s = build([
            (base + 0,    1),   // light (kept)
            (base + 0,    2),   // duplicate ts -> ignored
            (base + 3600, 0),   // wake (end)
        ])
        XCTAssertEqual(s.count, 1)
        XCTAssertEqual(s[0].stages.map { $0.stage }, ["light"])
    }

    func testFewerThanTwoEventsYieldsNoSession() {
        XCTAssertTrue(build([]).isEmpty)
        XCTAssertTrue(build([(base, 1)]).isEmpty)
    }

    func testUnsortedInputIsHandled() {
        // Same night as the core test but shuffled - result must be identical (sorted internally).
        let s = build([
            (base + 9000, 0),
            (base + 3600, 2),
            (base + 0,    1),
            (base + 7200, 3),
            (base + 1800, 0),
        ])
        XCTAssertEqual(s.count, 1)
        XCTAssertEqual(s[0].start, base)
        XCTAssertEqual(s[0].end, base + 9000)
        XCTAssertEqual(s[0].efficiency, 0.8, accuracy: 0.0001)
    }

    // MARK: - check_sleep window session (§6.15)

    func testSessionFromWindowIsOneAsleepSegment() {
        let end = base + 7 * 3600 + 56 * 60          // 7 h 56 m, like the real check_sleep capture
        let s = OuraSleepSessionBuilder.session(fromWindowStart: base, end: end)
        XCTAssertNotNil(s)
        XCTAssertEqual(s?.start, base)
        XCTAssertEqual(s?.end, end)
        XCTAssertEqual(s?.efficiency, 1.0)
        XCTAssertEqual(s?.stages.count, 1)
        XCTAssertEqual(s?.stages.first?.stage, "asleep")
        XCTAssertNil(s?.restingHR)
    }

    func testSessionFromWindowRejectsNonPositiveSpan() {
        XCTAssertNil(OuraSleepSessionBuilder.session(fromWindowStart: base, end: base))
        XCTAssertNil(OuraSleepSessionBuilder.session(fromWindowStart: base, end: base - 1))
    }

    func testWindowSessionCountsAsSleepTimeButNotStages() {
        // The honest contract: a stage-unknown window contributes its full duration to TST and efficiency,
        // but ZERO to deep/REM/light — so the card shows total sleep, stages blank, nothing fabricated.
        let end = base + 8 * 3600
        let s = OuraSleepSessionBuilder.session(fromWindowStart: base, end: end)!
        let m = SleepStager.hypnogramMetrics(s)
        XCTAssertEqual(m.tstS, Double(8 * 3600), accuracy: 0.5)   // full window is sleep time
        XCTAssertEqual(m.efficiency, 1.0, accuracy: 0.0001)
        XCTAssertEqual(m.deepMin, 0.0, accuracy: 0.0001)
        XCTAssertEqual(m.remMin, 0.0, accuracy: 0.0001)
        XCTAssertEqual(m.lightMin, 0.0, accuracy: 0.0001)
    }
}
