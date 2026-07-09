package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for building a sleep session from the ring's OWN `check_sleep` window (§6.15). Byte-for-byte twin
 * of the Swift OuraSleepSessionBuilderTests window cases.
 */
class OuraSleepSessionBuilderTest {
    private val base = 1_700_000_000L

    @Test
    fun testSessionFromWindowIsOneAsleepSegment() {
        val end = base + 7 * 3600 + 56 * 60          // 7 h 56 m, like the real check_sleep capture
        val s = OuraSleepSessionBuilder.sessionFromWindow(base, end)
        assertEquals(base, s?.start)
        assertEquals(end, s?.end)
        assertEquals(1.0, s?.efficiency)
        assertEquals(1, s?.stages?.size)
        assertEquals("asleep", s?.stages?.first()?.stage)
        assertNull(s?.restingHR)
    }

    @Test
    fun testSessionFromWindowRejectsNonPositiveSpan() {
        assertNull(OuraSleepSessionBuilder.sessionFromWindow(base, base))
        assertNull(OuraSleepSessionBuilder.sessionFromWindow(base, base - 1))
    }

    @Test
    fun testWindowSessionCountsAsSleepTimeButNotStages() {
        // Honest contract: a stage-unknown window contributes its full duration to TST and efficiency, but
        // ZERO to deep/REM/light — total sleep shows, stages blank, nothing fabricated.
        val end = base + 8 * 3600
        val s = OuraSleepSessionBuilder.sessionFromWindow(base, end)!!
        val m = SleepStager.hypnogramMetrics(s)
        assertEquals((8 * 3600).toDouble(), m.tstS, 0.5)   // full window is sleep time
        assertEquals(1.0, m.efficiency, 0.0001)
        assertEquals(0.0, m.deepMin, 0.0001)
        assertEquals(0.0, m.remMin, 0.0001)
        assertEquals(0.0, m.lightMin, 0.0001)
    }
}
