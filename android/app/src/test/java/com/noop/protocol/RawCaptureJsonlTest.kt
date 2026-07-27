package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class RawCaptureJsonlTest {

    @Test
    fun encodesCaptureRecordAsStableJsonLine() {
        val line = RawCaptureJsonl.encode(
            RawCaptureRecord(
                hex = "aa01",
                char = "fd4b0005",
                tsMs = 1234L,
                hr = 61,
                typeName = "METADATA",
                seq = 7,
                crcOk = true,
                ok = true,
                sessionId = "raw-1234",
                offload = true,
                size = 36,
                parsed = mapOf(
                    "meta_type" to "HISTORY_END(2)",
                    "trim_cursor" to 4512,
                    "rr_intervals" to intArrayOf(801, 802),
                ),
            ),
        )

        assertEquals(
            "{" +
                "\"hex\":\"aa01\"," +
                "\"char\":\"fd4b0005\"," +
                "\"ts_ms\":1234," +
                "\"hr\":61," +
                "\"type_name\":\"METADATA\"," +
                "\"seq\":7," +
                "\"crc_ok\":true," +
                "\"ok\":true," +
                "\"session_id\":\"raw-1234\"," +
                "\"offload\":true," +
                "\"size\":36," +
                "\"parsed\":{\"meta_type\":\"HISTORY_END(2)\",\"rr_intervals\":[801,802],\"trim_cursor\":4512}" +
                "}",
            line,
        )
    }

    @Test
    fun escapesStringsAndNullFields() {
        val line = RawCaptureJsonl.encode(
            RawCaptureRecord(
                hex = "aa\\bb",
                char = "fd4b0003",
                tsMs = 1L,
                hr = null,
                typeName = null,
                seq = null,
                crcOk = null,
                ok = false,
                sessionId = "s\"1",
                offload = false,
                size = 2,
                parsed = mapOf("note" to "line\nbreak"),
            ),
        )

        assertEquals(
            "{" +
                "\"hex\":\"aa\\\\bb\"," +
                "\"char\":\"fd4b0003\"," +
                "\"ts_ms\":1," +
                "\"hr\":null," +
                "\"type_name\":null," +
                "\"seq\":null," +
                "\"crc_ok\":null," +
                "\"ok\":false," +
                "\"session_id\":\"s\\\"1\"," +
                "\"offload\":false," +
                "\"size\":2," +
                "\"parsed\":{\"note\":\"line\\nbreak\"}" +
                "}",
            line,
        )
    }
}
