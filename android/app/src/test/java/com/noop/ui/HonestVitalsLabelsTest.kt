package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HonestVitalsLabelsTest {

    @Test
    fun bp_blankWithoutCuff() {
        val line = HonestVitalsLabels.bpLine(null, null, null)
        assertEquals("—", line.valueText)
        assertEquals(HonestVitalsLabels.Provenance.BLANK, line.provenance)
        assertTrue(line.caption.contains("Lab Book", ignoreCase = true) || line.caption.contains("cuff", ignoreCase = true))
        assertFalse(line.caption.contains("decoded live", ignoreCase = true))
        assertEquals(StrandTone.Neutral, HonestVitalsLabels.provenanceTone(line.provenance))
    }

    @Test
    fun bp_labBookWhenBothPresent() {
        val line = HonestVitalsLabels.bpLine(118.4, 76.2, "2026-07-09")
        assertEquals("118/76", line.valueText)
        assertEquals(HonestVitalsLabels.Provenance.LAB_BOOK, line.provenance)
        assertTrue(line.caption.contains("2026-07-09") || line.caption.contains("Cuff", ignoreCase = true))
        assertEquals("Lab Book", HonestVitalsLabels.provenanceLabel(line.provenance))
    }

    @Test
    fun spo2_blankWhenMissing() {
        val line = HonestVitalsLabels.spo2Line(null)
        assertEquals("—", line.valueText)
        assertEquals(HonestVitalsLabels.Provenance.BLANK, line.provenance)
    }

    @Test
    fun spo2_measuredWhenBanked() {
        val line = HonestVitalsLabels.spo2Line(97.0)
        assertEquals("97%", line.valueText)
        assertEquals(HonestVitalsLabels.Provenance.MEASURED, line.provenance)
    }

    @Test
    fun vo2_estimateLabeled() {
        val line = HonestVitalsLabels.vo2Line(42.0)
        assertEquals("42", line.valueText)
        assertEquals(HonestVitalsLabels.Provenance.ESTIMATE, line.provenance)
        assertTrue(line.caption.contains("Estimate", ignoreCase = true))
    }

    @Test
    fun vo2_blankWhenNone() {
        assertEquals(HonestVitalsLabels.Provenance.BLANK, HonestVitalsLabels.vo2Line(null).provenance)
    }
}
