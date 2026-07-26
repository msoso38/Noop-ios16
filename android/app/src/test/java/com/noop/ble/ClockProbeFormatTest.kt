package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #827: the pure formatter for the GET_CLOCK probe result. Byte-parity twin of the Swift ClockProbeTests —
 * includes the REAL WHOOP 5.0 captures that confirmed the offset (two captures 62s apart whose decoded
 * clocks moved by exactly that gap).
 */
class ClockProbeFormatTest {

    private fun hexToBytes(h: String) = ByteArray(h.length / 2) { ((h[it * 2].digitToInt(16) shl 4) or h[it * 2 + 1].digitToInt(16)).toByte() }

    // WHOOP4-shaped synthetic frame: cmd byte (0x0b = GET_CLOCK) @6, payload @7, clock u32 LE @pay[2] =
    // 0x60000000 (1610612736, a plausible 2021-01-13 timestamp), crc32 trailer.
    private val whoop4Frame = "aa00000000000b00000000006046758858"

    // Real WHOOP 5.0 captures (#827), 62s apart, cmd byte 0x0b @10.
    private val realCapture1 = "aa011400010021b1241c0b040151b7656a51380000000000efda48d5"
    private val realCapture2 = "aa011400010021b1241d0b05018fb7656a1e450000000000b000f3e9"

    @Test fun whoop4_plausibleClock_decodesAndFlagsPlausible() {
        val (text, payHex) = WhoopBleClient.formatClockProbe(hexToBytes(whoop4Frame), cmdOff = 6, isWhoop5 = false, prevPayloadHex = null)
        assertTrue(text.contains("WHOOP 4.0"))
        assertTrue(text.contains("Decoded clock @2 (u32 LE): 1610612736"))
        assertTrue(text.contains("plausible unix time"))
        assertEquals(6 * 2, payHex!!.length)
    }

    @Test fun whoop5RealCapture_decodesConfirmedOffset() {
        val (text, payHex) = WhoopBleClient.formatClockProbe(hexToBytes(realCapture1), cmdOff = 10, isWhoop5 = true, prevPayloadHex = null)
        assertTrue(text.contains("WHOOP 5/MG"))
        assertTrue(text.contains("Decoded clock @2 (u32 LE): 1785050961"))
        assertTrue(text.contains("plausible unix time"))
        assertEquals(13 * 2, payHex!!.length)
    }

    @Test fun whoop5RealCaptures_trackElapsedWallTime() {
        // The decode isn't just "plausible" on one frame — two captures 62s apart moved by exactly 62s,
        // which is the actual confirmation (a wrong offset landing in-plausible-range twice, 62s apart, by
        // coincidence, is not realistic).
        val (_, prev) = WhoopBleClient.formatClockProbe(hexToBytes(realCapture1), cmdOff = 10, isWhoop5 = true, prevPayloadHex = null)
        val (text, _) = WhoopBleClient.formatClockProbe(hexToBytes(realCapture2), cmdOff = 10, isWhoop5 = true, prevPayloadHex = prev)
        assertTrue(text.contains("Decoded clock @2 (u32 LE): 1785051023"))
        assertEquals(62, 1785051023 - 1785050961)
        assertTrue(text.contains("Δ vs previous capture:"))
    }

    @Test fun epochEraClock_flagsNotPlausible() {
        // clock = 100 (1970-01-01ish), LE bytes 64 00 00 00.
        val frame = "aa00000000000b00006400000046758858"
        val (text, _) = WhoopBleClient.formatClockProbe(hexToBytes(frame), cmdOff = 6, isWhoop5 = false, prevPayloadHex = null)
        assertTrue(text.contains("Decoded clock @2 (u32 LE): 100"))
        assertTrue(text.contains("epoch-era"))
    }

    @Test fun diff_flagsTheChangedBytes() {
        val first = hexToBytes(whoop4Frame)
        val (_, prev) = WhoopBleClient.formatClockProbe(first, 6, false, null)
        val second = first.copyOf().also { it[12] = 0x61 } // payload offset 5 (frame[7+5]=frame[12])
        val (text, _) = WhoopBleClient.formatClockProbe(second, 6, false, prev)
        assertTrue(text.contains("Δ vs previous capture:"))
        assertTrue(text.contains("@05:60→61"))
    }

    @Test fun bareStub_isCalledOut() {
        // 11-byte frame: cmd@6=0x0b then only the 4-byte CRC tail, so payEnd(7) == payStart(7) ⇒ no payload.
        val (text, payHex) = WhoopBleClient.formatClockProbe(hexToBytes("aa0700fa00000b46758858"), cmdOff = 6, isWhoop5 = false, prevPayloadHex = null)
        assertTrue(text.contains("bare stub"))
        assertNull(payHex)
    }
}
