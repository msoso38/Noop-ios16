package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhoopAppScoreParserTest {
    @Test
    fun parsesRecoveryAndStrainFromAppLikeText() {
        val t = """
            TODAY
            Recovery
            67%
            Day Strain
            14.7
            / 21
        """.trimIndent()
        val p = WhoopAppScoreParser.parseScreenText(t)
        assertEquals(67.0, p.recoveryPct!!, 0.01)
        assertEquals(14.7, p.dayStrain021!!, 0.01)
    }

    @Test
    fun parsesStrainSlash21() {
        val p = WhoopAppScoreParser.parseScreenText("Your strain is 14.1/21 today")
        assertNotNull(p.dayStrain021)
        assertTrue(p.dayStrain021!! in 14.0..15.0)
    }

    @Test
    fun notificationRecovery() {
        val p = WhoopAppScoreParser.parseNotification(
            "WHOOP",
            "Recovery is 72% — green",
            null,
        )
        assertEquals(72.0, p.recoveryPct!!, 0.01)
    }
}
