package com.noop.oura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tier-B CVA raw-PPG research-corpus JSONL line encoder. The line is asserted verbatim so the
 * format is pinned byte-for-byte AND stays interchangeable with the Swift `OuraCvaPpgDumpLine` corpus
 * (same key order, same `values` formatting).
 */
class OuraCvaPpgDumpLineTest {

    @Test
    fun encodesFixedShapeVerbatim() {
        val line = OuraCvaPpgDumpLine.encode(
            deviceId = "oura-2H3B2405003655", ringTs = 3_584_349, utc = 1_753_440_000,
            iso = "2026-07-30T09:09:01Z", values = listOf(395015, 394873, 394679),
        )
        assertEquals(
            "{\"schema\":1,\"deviceId\":\"oura-2H3B2405003655\",\"ringTs\":3584349," +
                "\"utc\":1753440000,\"iso\":\"2026-07-30T09:09:01Z\",\"values\":[395015,394873,394679]}",
            line,
        )
    }

    @Test
    fun emptyValuesIsEmptyArray() {
        val line = OuraCvaPpgDumpLine.encode("d", 1, 2, "x", emptyList())
        assertTrue(line.endsWith("\"values\":[]}"))
    }
}
