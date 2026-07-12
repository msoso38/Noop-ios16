package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhoopNoopAlignmentTest {

    @Test
    fun awaitingWhenNoWhoopLabels() {
        val a = WhoopNoopAlignment.evaluateDay(
            day = "2026-07-10",
            noopRecovery = 72.0,
            noopStrain = 40.0,
            noopSleep = 80.0,
            noopStressPct = 30.0,
            whoopRecovery = null,
            whoopStrain = null,
            whoopSleep = null,
            whoopStressPct = null,
        )
        assertNull(a.passScore)
        assertEquals(WhoopNoopAlignment.Grade.AWAITING, a.grade)
        assertEquals(0, a.pairedHeads)
    }

    @Test
    fun strongWhenCloseOnAllHeads() {
        val a = WhoopNoopAlignment.evaluateDay(
            day = "2026-07-10",
            noopRecovery = 70.0,
            noopStrain = 40.0,
            noopSleep = 80.0,
            noopStressPct = 25.0,
            whoopRecovery = 72.0,
            whoopStrain = 8.0, // 0–21 → ~38 on 0–100
            whoopSleep = 82.0,
            whoopStressPct = 28.0,
        )
        assertNotNull(a.passScore)
        assertTrue(a.passScore!! >= 60.0)
        assertTrue(a.pairedHeads >= 3)
    }

    @Test
    fun normalizesWhoopStrain21Scale() {
        val n = WhoopNoopAlignment.normalizeWhoopStrain(10.5)
        assertEquals(50.0, n!!, 0.01)
    }

    @Test
    fun dualScaleDisplayForStrain_14p1_vs_35() {
        // User case: WHOOP 14.1/21 (~67%) vs NOOP Effort 35/100 — not the same number, honest dual display.
        val a = WhoopNoopAlignment.evaluateDay(
            day = "2026-07-10",
            noopRecovery = null,
            noopStrain = 35.0,
            noopSleep = null,
            noopStressPct = null,
            whoopRecovery = null,
            whoopStrain = 14.1,
            whoopSleep = null,
            whoopStressPct = null,
        )
        val head = a.heads.first { it.name.startsWith("Effort") }
        assertTrue(head.whoopDisplay.contains("/21"))
        assertTrue(head.whoopDisplay.contains("67") || head.whoopDisplay.contains("67.1") || head.whoopDisplay.contains("67.0"))
        assertTrue(head.noopDisplay.contains("/100"))
        assertTrue(head.noopDisplay.contains("/21"))
        // Pass compares 35 vs ~67 on 0–100 → large gap, not "same score"
        assertNotNull(head.absError)
        assertTrue(head.absError!! > 20.0)
    }

    @Test
    fun dualScaleDisplayForLiveLabel_14p7() {
        // Fold adb / app UI: 14.7/21 ≈ 70% — must not display as 14.7/100.
        val a = WhoopNoopAlignment.evaluateDay(
            day = "2026-07-10",
            noopRecovery = null,
            noopStrain = 35.0,
            noopSleep = null,
            noopStressPct = null,
            whoopRecovery = null,
            whoopStrain = 14.7,
            whoopSleep = null,
            whoopStressPct = null,
        )
        val head = a.heads.first { it.name.startsWith("Effort") }
        assertTrue(head.whoopDisplay.startsWith("14.7") || head.whoopDisplay.contains("14.7/21"))
        assertTrue(head.whoopDisplay.contains("/21"))
        assertTrue(head.whoopDisplay.contains("%"))
        val whoop100 = WhoopNoopAlignment.normalizeWhoopStrain(14.7)!!
        assertEquals(14.7 / 21.0 * 100.0, whoop100, 0.05)
        assertNotNull(a.passScore)
        assertEquals(1, a.pairedHeads)
    }
}
