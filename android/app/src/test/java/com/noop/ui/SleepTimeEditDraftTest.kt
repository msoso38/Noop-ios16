package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SleepTimeEditDraftTest {
    private val zone = ZoneId.of("UTC")

    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toEpochSecond()

    @Test
    fun splitNightCorrectionSavesOneFinalWindow() {
        val original = SleepTimeEditDraft(
            startTs = ts(2026, 7, 16, 0, 3),
            endTs = ts(2026, 7, 16, 1, 30),
        )

        val finalDraft = original
            .withBedCandidate(
                candidateBedTs = ts(2026, 7, 16, 0, 0),
                nowTs = ts(2026, 7, 16, 8, 0),
                zone = zone,
            )
            .withWakeCandidate(ts(2026, 7, 16, 7, 0))

        assertEquals(
            ts(2026, 7, 16, 0, 0) to ts(2026, 7, 16, 7, 0),
            finalDraft.validatedWindow(nowTs = ts(2026, 7, 16, 8, 0)),
        )
    }

    @Test
    fun crossMidnightBedAndExplicitWakeDateRemainOneWindow() {
        val original = SleepTimeEditDraft(
            startTs = ts(2026, 7, 16, 1, 6),
            endTs = ts(2026, 7, 16, 5, 0),
        )

        val finalDraft = original
            .withBedCandidate(
                candidateBedTs = ts(2026, 7, 16, 23, 0),
                nowTs = ts(2026, 7, 16, 8, 0),
                zone = zone,
            )
            .withWakeCandidate(ts(2026, 7, 17, 7, 0))

        assertEquals(
            ts(2026, 7, 15, 23, 0) to ts(2026, 7, 17, 7, 0),
            finalDraft.validatedWindow(nowTs = ts(2026, 7, 17, 8, 0)),
        )
    }

    @Test
    fun issue970CorrectionPreservesExplicitSameDayWakeDate() {
        val draft = SleepTimeEditDraft(
            startTs = ts(2026, 7, 28, 22, 30),
            endTs = ts(2026, 7, 29, 7, 30),
        ).withBedCandidate(
            candidateBedTs = ts(2026, 7, 29, 2, 30),
            nowTs = ts(2026, 7, 29, 8, 0),
            zone = zone,
        ).withWakeCandidate(ts(2026, 7, 29, 7, 30))

        assertEquals(
            ts(2026, 7, 29, 2, 30) to ts(2026, 7, 29, 7, 30),
            draft.validatedWindow(nowTs = ts(2026, 7, 29, 8, 0)),
        )
    }

    @Test
    fun explicitLaterWakeDateIsPreserved() {
        val draft = SleepTimeEditDraft(
            startTs = ts(2026, 7, 16, 23, 0),
            endTs = ts(2026, 7, 17, 7, 0),
        ).withWakeCandidate(ts(2026, 7, 18, 7, 0))

        assertEquals(ts(2026, 7, 18, 7, 0), draft.endTs)
    }

    @Test
    fun explicitWakeBeforeBedRemainsInvalid() {
        val draft = SleepTimeEditDraft(
            startTs = ts(2026, 7, 16, 23, 0),
            endTs = ts(2026, 7, 17, 5, 0),
        ).withWakeCandidate(ts(2026, 7, 16, 22, 30))

        assertEquals(ts(2026, 7, 16, 22, 30), draft.endTs)
        assertNull(draft.validatedWindow(nowTs = ts(2026, 7, 17, 8, 0)))
    }

    @Test
    fun invalidIntermediateWindowCannotBeSaved() {
        val draft = SleepTimeEditDraft(
            startTs = ts(2026, 7, 16, 6, 0),
            endTs = ts(2026, 7, 16, 5, 0),
        )

        assertNull(draft.validatedWindow(nowTs = ts(2026, 7, 16, 8, 0)))
    }
}
