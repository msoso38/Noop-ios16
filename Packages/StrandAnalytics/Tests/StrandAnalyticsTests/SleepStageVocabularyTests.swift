import XCTest
@testable import StrandAnalytics

/// #979 — both spellings of the wake stage occur in stored hypnograms, and five segment comparisons
/// only recognised one of them.
///
/// The damaging shape is `stage != "wake"`, used to mean "asleep": an imported `"awake"` segment fell
/// through it and was counted as SLEEP, inflating the efficiency figure. The mirror shape,
/// `stage == "wake"`, under-counted wake time and made the #987 wake refinement skip those segments.
///
/// Twin of the Kotlin `SleepStageVocabularyTest`; same cases in the same order.
final class SleepStageVocabularyTests: XCTestCase {

    /// Both spellings are wake. This is the whole point.
    func testBothSpellingsAreWake() {
        XCTAssertTrue(SleepStageVocabulary.isWake("wake"))
        XCTAssertTrue(SleepStageVocabulary.isWake("awake"))
    }

    /// Sleep stages are not wake — the predicate must not swallow the rest of the vocabulary.
    func testSleepStagesAreNotWake() {
        for s in ["deep", "light", "rem"] {
            XCTAssertFalse(SleepStageVocabulary.isWake(s), "\(s) must not read as wake")
        }
    }

    /// Imported JSON is not guaranteed tidy; casing and padding must not decide a sleep score.
    func testCasingAndWhitespaceAreFolded() {
        XCTAssertTrue(SleepStageVocabulary.isWake("Awake"))
        XCTAssertTrue(SleepStageVocabulary.isWake("  WAKE "))
        XCTAssertTrue(SleepStageVocabulary.isWake("\tAwAkE"))
    }

    /// An absent or unknown stage is NOT wake, which preserves the existing behaviour of the callers
    /// that treat "anything that is not wake" as asleep. Widening that would be a separate change.
    func testUnknownAndEmptyAreNotWake() {
        XCTAssertFalse(SleepStageVocabulary.isWake(""))
        XCTAssertFalse(SleepStageVocabulary.isWake("   "))
        XCTAssertFalse(SleepStageVocabulary.isWake("restless"))
    }

    /// The regression itself, in the shape the importers use: a night of `awake` + `deep` must count
    /// only the `deep` span as asleep. Before the fix the `awake` span fell through `!= "wake"` and was
    /// added to the asleep total, so this asserted 2x the true value.
    func testAwakeSegmentIsNotCountedAsAsleep() {
        let segs: [(stage: String, seconds: Int)] = [("awake", 1800), ("deep", 1800)]
        let asleep = segs.filter { !SleepStageVocabulary.isWake($0.stage) }.reduce(0) { $0 + $1.seconds }
        XCTAssertEqual(asleep, 1800)
    }

    /// And the mirror shape: wake time must include the `awake` span, which `== "wake"` dropped.
    func testWakeTotalIncludesBothSpellings() {
        let segs: [(stage: String, seconds: Int)] = [("wake", 600), ("awake", 300), ("rem", 1200)]
        let wake = segs.filter { SleepStageVocabulary.isWake($0.stage) }.reduce(0) { $0 + $1.seconds }
        XCTAssertEqual(wake, 900)
    }
}
