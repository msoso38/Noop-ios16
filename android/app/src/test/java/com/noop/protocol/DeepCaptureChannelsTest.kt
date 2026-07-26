package com.noop.protocol

import com.noop.data.V18AuxCodec
import com.noop.data.V18AuxRow
import com.noop.data.V18AuxSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v18 fields the decoder produced and `extractHistoricalStreams` used to DISCARD (Swift
 * `DeepCaptureChannelsTests` twin).
 *
 * Every expectation is read off the SAME real worn 5/MG frame `Whoop5HistoricalDecodeTest` uses (and
 * that `decoder_oracle.json` pins on both platforms), so nothing here asserts a value the decoder was
 * not already independently proven to produce — these tests are about the STORAGE funnel, which is where
 * the loss happened. The numbers are IDENTICAL to the Swift twin's, which is the parity guarantee.
 */
class DeepCaptureChannelsTest {

    private fun bytes(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Real worn WHOOP 5 v18 frame — the same fixture as Whoop5HistoricalDecodeTest / the Swift tests.
    private val wornV18 =
        "aa01740001003fb12f1280733d8401b69f266a66460066025a0265020000000000007b0a8d656463ff0012163cf6a439bf2924fd3ed763fe3e3200aa000000000000000000f7000901f10b0007010c020c00000000000000000000000000000000000000000000000100656f1e1e0000009d61a7c00000003e862817"

    // Synthetic WHOOP 4.0 v24 record (the HistoricalV24 fixture) — must add nothing.
    private val v24 =
        "aa5a008e2f18000000000000f153650000000000003f0152030000000000000000dc053075" +
            "000000cdcc4c3dcdcccc3d5a657e3f00000040cdcc4c3dcdcccc3d5a657e3f504668428403" +
            "200364006400b80bb80b000000000000c25c1a88"

    private val ts = 1_780_916_150L

    /**
     * The fixture frame with one byte replaced AND its CRC32 trailer recomputed. The reseal matters:
     * `extractHistoricalStreams` skips any frame whose CRC fails, so a mutated frame would otherwise
     * silently test nothing at all. WHOOP5: declaredLength@2, total = len + 8, CRC32 LE at total - 4.
     */
    private fun mutated(index: Int, value: Int): ByteArray {
        val b = bytes(wornV18)
        b[index] = value.toByte()
        val declaredLength = (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8)
        val payloadEnd = declaredLength + 8 - 4
        val crc = Crc.crc32(b, 8, payloadEnd)
        for (k in 0 until 4) b[payloadEnd + k] = ((crc ushr (8 * k)) and 0xFFL).toByte()
        return b
    }

    private fun extract(frames: List<ByteArray>) =
        extractHistoricalStreams(frames, ts.toInt(), ts.toInt(), DeviceFamily.WHOOP5)

    // ── The four named channels ──────────────────────────────────────────────────────────────────────

    @Test
    fun dynamicAccelerationRidesTheGravityRow() {
        val g = extract(listOf(bytes(wornV18))).gravity.single()
        assertEquals(0.0091596, g.dynAccel!!, 1e-6)
        // Stored BESIDE the vector, never instead of it — the stager still reads x/y/z.
        assertEquals(1.0, Math.sqrt(g.x * g.x + g.y * g.y + g.z * g.z), 0.05)
    }

    @Test
    fun auxThermalChannelsRideTheSkinTempRow() {
        val s = extract(listOf(bytes(wornV18))).skinTemp.single()
        assertEquals(3057, s.raw)      // primary, unchanged (°C = raw/100 = 30.6)
        assertEquals(247, s.aux1Raw)   // @69, °C = raw/10 = 24.7
        assertEquals(265, s.aux2Raw)   // @71, °C = raw/10 = 26.5
    }

    @Test
    fun sleepStateCarriesTheWholeFlagByteAndStateIsUnchanged() {
        // 0xE9 = 1110 1001: onwrist(b0-1)=1, wake_quality(b2-3)=2, sleep_state(b4-5)=2, reserved(b6-7)=3.
        // The reserved bits are the point: they read 0 on every real capture and have NO interpretation,
        // so a per-nibble store would make them permanently unrecoverable.
        val s = extract(listOf(mutated(81, 0xE9))).sleepState.single()
        assertEquals(0xE9, s.rawByte)
        assertEquals("state must remain (rawByte shr 4) and 3", 2, s.state)
        assertEquals(s.state, (s.rawByte!! shr 4) and 3)
        assertEquals(1, s.rawByte!! and 3)            // onwrist
        assertEquals(2, (s.rawByte!! shr 2) and 3)    // wake_quality
        assertTrue("the fixture must exercise the reserved bits", (s.rawByte!! and 0xC0) != 0)
    }

    @Test
    fun zeroFlagByteIsCarriedNotTreatedAsAbsent() {
        // 0 is a REAL reading (worn daytime wake), not an absent one.
        val s = extract(listOf(bytes(wornV18))).sleepState.single()
        assertEquals(0, s.rawByte)
        assertEquals(0, s.state)
    }

    // ── The remaining v18 slots ──────────────────────────────────────────────────────────────────────

    @Test
    fun everyRemainingV18SlotIsCollected() {
        val a = extract(listOf(bytes(wornV18))).v18Aux.single()
        assertEquals(ts, a.ts)
        assertEquals(25_443_699, a.recordIndex)     // @11  u32
        assertEquals(2, a.rrCount)                  // @23  u8
        assertEquals(0, a.cardiacFlags)             // @33  u8
        assertEquals(25_997, a.hrFixed88)           // @36  u16 (bpm = /256 ≈ 101.6 vs hr 102)
        assertEquals(25_444, a.rrPacked)            // @38  u16
        assertEquals(255, a.cardiacStatus)          // @40  u8
        assertEquals(170, a.stepCadence)            // @59  u8
        assertEquals(1_792, a.statusWord)           // @75  u16
        assertEquals(3_073, a.statusWord1)          // @77  u16
        assertEquals(3_074, a.statusWord2)          // @79  u16
        assertEquals(0, a.auxByte82)                // @82  u8 (raw; the gated 70-100 view is derived)
        assertEquals(28_517, a.opticalBaseline106)  // @106 u16
        assertEquals(30, a.opticalAmpA)             // @108 u8
        assertEquals(30, a.opticalAmpB)             // @109 u8
        assertEquals(0xC0A7619D.toInt(), a.unknownF32Bits)   // @113 raw 32-bit pattern
        assertEquals(-5.2307, a.unknownF32At113!!, 0.001)
    }

    @Test
    fun slotValuesOrderMatchesTheSlotEnum() {
        val a = V18AuxRow(
            ts = 1, recordIndex = 10, rrCount = 11, cardiacFlags = 12, hrFixed88 = 13, rrPacked = 14,
            cardiacStatus = 15, stepCadence = 16, statusWord = 17, statusWord1 = 18, statusWord2 = 19,
            auxByte82 = 20, opticalBaseline106 = 21, opticalAmpA = 22, opticalAmpB = 23,
            unknownF32Bits = 24,
        )
        assertEquals(V18AuxSlot.entries.size, a.slotValues.size)
        // Slot i must round-trip to position i — this is what makes the persisted bitmap meaningful.
        assertEquals((10..24).toList(), a.slotValues)
        assertEquals(a, V18AuxRow.fromSlotValues(1, a.slotValues))
        // Indices must be a dense 0..<n range in declaration order (bitmap bit positions).
        assertEquals(V18AuxSlot.entries.indices.toList(), V18AuxSlot.entries.map { it.index })
    }

    @Test
    fun shortSlotListLeavesTheTailAbsent() {
        val a = V18AuxRow.fromSlotValues(1, listOf(7, 8))
        assertEquals(7, a.recordIndex)
        assertEquals(8, a.rrCount)
        assertNull(a.unknownF32Bits)
        assertNull(a.opticalAmpA)
    }

    // ── What must NOT change ─────────────────────────────────────────────────────────────────────────

    @Test
    fun whoop4V24RecordAddsNothing() {
        val s = extractHistoricalStreams(
            listOf(bytes(v24)), 1_700_000_000, 1_700_000_000, DeviceFamily.WHOOP4,
        )
        assertTrue("a 4.0 v24 record must bank no aux row", s.v18Aux.isEmpty())
        assertNull(s.gravity.firstOrNull()?.dynAccel)
        assertNull(s.skinTemp.firstOrNull()?.aux1Raw)
        assertNull(s.skinTemp.firstOrNull()?.aux2Raw)
        // The 4.0 schema DOES emit rr_count, which is why the aux gate keys on hist_version rather than
        // on which keys happen to be present — a presence test would bank a near-empty row per 4.0 second.
        assertEquals(1, decodeHistorical(bytes(v24), DeviceFamily.WHOOP4)!!["rr_count"])
    }

    @Test
    fun auxCollectionIsGatedOnLayoutV18() {
        assertEquals(18, decodeHistorical(bytes(wornV18), DeviceFamily.WHOOP5)!!["hist_version"])
        // A v26 (optical PPG) record carries no v18 biometric slots at all.
        assertTrue(extract(listOf(mutated(9, 26))).v18Aux.isEmpty())
    }

    @Test
    fun dynAccelDiagnosticStillFoldsAlongsideTheNewColumn() {
        val s = extract(listOf(bytes(wornV18)))
        assertEquals(1, s.dynAccel.count)
        assertEquals(1, s.gravity.size)
        assertNotNull(s.gravity.single().dynAccel)
    }

    @Test
    fun noSlotDuplicatesAnAlreadyPersistedField() {
        val persistedElsewhere = setOf(
            "unix", "heart_rate", "rr_intervals", "gravity_x", "gravity_y", "gravity_z",
            "dynamic_acceleration", "skin_temp_raw", "temp_aux_1_raw", "temp_aux_2_raw",
            "step_motion_counter", "activity_class", "motion_wear_quality", "sleep_state",
            "sleep_state_byte", "spo2_candidate_82", "ppg_waveform",
        )
        for (slot in V18AuxSlot.entries) {
            assertFalse(
                "${slot.decoderKey} already has a durable home — banking it twice is waste",
                slot.decoderKey in persistedElsewhere,
            )
        }
    }

    // ── Codec (byte-identical to the Swift V18AuxCodec) ──────────────────────────────────────────────

    @Test
    fun codecHeaderShapeAndSize() {
        val full = V18AuxRow(
            ts = 0, recordIndex = 1, rrCount = 2, cardiacFlags = 3, hrFixed88 = 4, rrPacked = 5,
            cardiacStatus = 6, stepCadence = 7, statusWord = 8, statusWord1 = 9, statusWord2 = 10,
            auxByte82 = 11, opticalBaseline106 = 12, opticalAmpA = 13, opticalAmpB = 14,
            unknownF32Bits = 15,
        )
        val blob = V18AuxCodec.pack(full)
        assertEquals(V18AuxCodec.FORMAT_VERSION, blob[0].toInt() and 0xFF)
        val bitmap = (blob[1].toInt() and 0xFF) or ((blob[2].toInt() and 0xFF) shl 8)
        assertEquals((1 shl V18AuxSlot.entries.size) - 1, bitmap)
        // 3-byte header + the sum of the declared slot widths — a full row is 30 bytes.
        assertEquals(3 + V18AuxSlot.entries.sumOf { it.width }, blob.size)
        assertEquals(30, blob.size)
        assertEquals(full, V18AuxCodec.unpack(blob, 0))
    }

    @Test
    fun emptyRowPacksToEmptyArray() {
        assertTrue(V18AuxCodec.pack(V18AuxRow(ts = 0)).isEmpty())
        assertTrue(V18AuxRow(ts = 0).isEmpty)
    }

    @Test
    fun malformedBlobsDegradeToAbsentRatherThanThrow() {
        assertTrue(V18AuxCodec.unpack(ByteArray(0), 5).isEmpty)
        assertTrue(V18AuxCodec.unpack(byteArrayOf(1, 0xFF.toByte()), 5).isEmpty)          // header cut short
        assertTrue(V18AuxCodec.unpack(byteArrayOf(99, -1, -1, 1, 2, 3, 4), 5).isEmpty)    // future version
        // Truncated body: bitmap 0b0110 claims slots 1 and 2 (1 byte each) but only one body byte
        // follows — slot 1 decodes and slot 2 must read absent rather than borrowing a byte.
        val partial = V18AuxCodec.unpack(byteArrayOf(1, 0b0000_0110, 0, 42), 5)
        assertEquals(42, partial.rrCount)
        assertNull(partial.cardiacFlags)
        assertEquals(5L, partial.ts)
    }

    @Test
    fun wideSlotsSurviveAtFullWidth() {
        val r = V18AuxRow(ts = 0, recordIndex = 0xFEDCBA98.toInt(), unknownF32Bits = 0xC0A7619D.toInt())
        val back = V18AuxCodec.unpack(V18AuxCodec.pack(r), 0)
        assertEquals(0xFEDCBA98.toInt(), back.recordIndex)
        assertEquals(0xC0A7619D.toInt(), back.unknownF32Bits)
    }

    /**
     * The exact bytes the Swift codec produces for the real fixture's slots. Hard-coded rather than
     * derived, so a one-sided edit to either codec's layout fails HERE instead of silently writing blobs
     * the other platform cannot read out of a `.noopbak`.
     */
    @Test
    fun fixtureRowPacksToTheExactCrossPlatformBytes() {
        val a = V18AuxRow(
            ts = ts, recordIndex = 25_443_699, rrCount = 2, cardiacFlags = 0, hrFixed88 = 25_997,
            rrPacked = 25_444, cardiacStatus = 255, stepCadence = 170, statusWord = 1_792,
            statusWord1 = 3_073, statusWord2 = 3_074, auxByte82 = 0, opticalBaseline106 = 28_517,
            opticalAmpA = 30, opticalAmpB = 30, unknownF32Bits = 0xC0A7619D.toInt(),
        )
        val hex = V18AuxCodec.pack(a).joinToString("") { "%02x".format(it) }
        assertEquals(
            "01ff7f" +          // version 1, bitmap 0x7FFF (all 15 slots present)
                "733d8401" +    // record_index  25443699 u32 LE
                "02" +          // rr_count      2
                "00" +          // cardiac_flags 0
                "8d65" +        // hr_fixed_8_8  25997 u16 LE
                "6463" +        // rr_packed     25444 u16 LE
                "ff" +          // cardiac_status 255
                "aa" +          // step_cadence  170
                "0007" +        // status_word   1792 u16 LE
                "010c" +        // status_word_1 3073 u16 LE
                "020c" +        // status_word_2 3074 u16 LE
                "00" +          // aux_byte_82   0
                "656f" +        // optical_baseline_106 28517 u16 LE
                "1e" +          // optical_amp_a 30
                "1e" +          // optical_amp_b 30
                "9d61a7c0",     // unknown_f32_113 bits 0xC0A7619D u32 LE
            hex,
        )
    }
}
