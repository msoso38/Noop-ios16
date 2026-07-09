package com.noop.oura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the `check_sleep` s:/e: sleep-window parser (OURA_PROTOCOL.md §6.15 prototype). Byte-for-byte
 * twin of the Swift CheckSleepParserTests; fixtures are the exact debug lines captured from a real Gen3.
 */
class CheckSleepParserTest {

    @Test
    fun testExtractsWindowFromRealCheckSleepSequence() {
        val p = OuraCheckSleepParser()
        assertNull(p.ingest("check_sleep"))
        assertNull(p.ingest("s: 114643"))                         // start alone -> incomplete
        assertEquals(OuraCheckSleepParser.Window(114_643L, 446_001L), p.ingest("e: 446001"))
        assertNull(p.ingest("not needed"))                        // unrelated line
        assertEquals(OuraCheckSleepParser.Window(114_643L, 446_340L), p.ingest("e: 446340"))
        assertNull(p.ingest("e: 446340"))                         // repeat window -> no re-emit
    }

    @Test
    fun testIgnoresLookalikeAndNonBoundaryLines() {
        val p = OuraCheckSleepParser()
        p.ingest("s: 114643")
        assertNull(p.ingest("tsc:60464"))
        assertNull(p.ingest("bed: 114643"))
        assertNull(p.ingest("ns=1025898"))
        assertNull(p.ingest("e: pp_stop"))
        assertNull(p.ingest("e:"))
        assertEquals(OuraCheckSleepParser.Window(114_643L, 446_340L), p.ingest("e: 446340"))
    }

    @Test
    fun testRejectsInvertedWindow() {
        val p = OuraCheckSleepParser()
        p.ingest("s: 500000")
        assertNull(p.ingest("e: 446340"))   // wake before bedtime -> not a window
    }

    @Test
    fun testRejectsCrossBlockPhantomWindow() {
        // Real 2026-07-09 capture: last night's wake `e: 1311598` arrived while the PREVIOUS night's
        // `s: 114643` was still latched -> a 33 h window. The max-duration guard must reject it…
        val p = OuraCheckSleepParser()
        p.ingest("s: 114643")
        assertNull(p.ingest("e: 1311598"))               // ~33.2 h -> rejected
        // A fresh `s:` completes the window against the latched `e:`; the emit fires on THIS `s:` line.
        assertEquals(OuraCheckSleepParser.Window(1_025_598L, 1_311_598L), p.ingest("s: 1025598"))
    }

    @Test
    fun testResetClearsState() {
        val p = OuraCheckSleepParser()
        p.ingest("s: 114643")
        p.ingest("e: 446340")
        p.reset()
        assertNull(p.ingest("e: 446340"))   // start was cleared -> cannot complete
    }

    @Test
    fun testAnchoredWindowConvertsToRealUtc() {
        val key = IntArray(16) { it }
        val d = OuraDriver(ringGen = OuraRingGen.GEN3, authKey = key)
        val anchorEpoch = 1_700_000_000L
        val anchorRt = 500_000L
        val payload = IntArray(8) { ((anchorEpoch shr (it * 8)) and 0xFFL).toInt() } + intArrayOf(0x00)
        d.ingest(OuraRecord(type = OuraEventTag.TIME_SYNC.raw, ringTimestamp = anchorRt, payload = payload))
        val p = OuraCheckSleepParser()
        p.ingest("s: 400000")
        val w = p.ingest("e: 450000")!!
        assertEquals(anchorEpoch - 10_000L, d.unixSeconds(forRingTimestamp = w.startRt))
        assertEquals(anchorEpoch - 5_000L, d.unixSeconds(forRingTimestamp = w.endRt))
    }
}
