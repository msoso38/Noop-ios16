package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #979 — both spellings of the wake stage occur in stored hypnograms, and five segment comparisons
 * only recognised one of them.
 *
 * The damaging shape is `stage != "wake"`, used to mean "asleep": an imported `"awake"` segment fell
 * through it and was counted as SLEEP, inflating the efficiency figure. The mirror shape,
 * `stage == "wake"`, under-counted wake time and made the #987 wake refinement skip those segments.
 *
 * Twin of the Swift `SleepStageVocabularyTests`; same cases in the same order.
 */
class SleepStageVocabularyTest {

    /** Both spellings are wake. This is the whole point. */
    @Test fun bothSpellingsAreWake() {
        assertTrue(SleepStageVocabulary.isWake("wake"))
        assertTrue(SleepStageVocabulary.isWake("awake"))
    }

    /** Sleep stages are not wake — the predicate must not swallow the rest of the vocabulary. */
    @Test fun sleepStagesAreNotWake() {
        for (s in listOf("deep", "light", "rem")) {
            assertFalse("$s must not read as wake", SleepStageVocabulary.isWake(s))
        }
    }

    /** Imported JSON is not guaranteed tidy; casing and padding must not decide a sleep score. */
    @Test fun casingAndWhitespaceAreFolded() {
        assertTrue(SleepStageVocabulary.isWake("Awake"))
        assertTrue(SleepStageVocabulary.isWake("  WAKE "))
        assertTrue(SleepStageVocabulary.isWake("\tAwAkE"))
    }

    /**
     * An absent or unknown stage is NOT wake, which preserves the existing behaviour of the callers
     * that treat "anything that is not wake" as asleep. Widening that would be a separate change.
     */
    @Test fun unknownAndEmptyAreNotWake() {
        assertFalse(SleepStageVocabulary.isWake(""))
        assertFalse(SleepStageVocabulary.isWake("   "))
        assertFalse(SleepStageVocabulary.isWake("restless"))
    }

    /**
     * The regression itself, in the shape the importers use: a night of `awake` + `deep` must count
     * only the `deep` span as asleep. Before the fix the `awake` span fell through `!= "wake"` and was
     * added to the asleep total, so this asserted 2x the true value.
     */
    @Test fun awakeSegmentIsNotCountedAsAsleep() {
        val segs = listOf("awake" to 1800, "deep" to 1800)
        val asleep = segs.filter { !SleepStageVocabulary.isWake(it.first) }.sumOf { it.second }
        assertEquals(1800, asleep)
    }

    /** And the mirror shape: wake time must include the `awake` span, which `== "wake"` dropped. */
    @Test fun wakeTotalIncludesBothSpellings() {
        val segs = listOf("wake" to 600, "awake" to 300, "rem" to 1200)
        val wake = segs.filter { SleepStageVocabulary.isWake(it.first) }.sumOf { it.second }
        assertEquals(900, wake)
    }
}
