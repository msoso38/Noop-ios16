package com.noop.data

/**
 * One wire slot in the 5/MG v18 auxiliary-field record. [index] is the slot's bit position in the
 * persisted presence bitmap, so it is a STORAGE CONTRACT: never reorder, renumber, or reuse a value.
 * Appending a new entry at the end is the only safe evolution (an old reader ignores a bit it has no
 * entry for; a new reader sees the bit absent on old rows).
 *
 * Every slot is carried as an UNSIGNED integer of [width] bytes, little-endian, exactly as it sits on the
 * wire — including [UNKNOWN_F32_113], which is banked as its raw 32-bit pattern rather than a decoded
 * float. One uniform integer path is what makes the Kotlin and Swift codecs verifiably byte-identical,
 * and banking the bit pattern means a future reader can re-interpret those 4 bytes as something other
 * than a float if the census says so.
 *
 * Names are the DECODER's own names, verbatim ([decoderKey]). Several are frankly unpinned bytes —
 * `cardiac_flags`/`cardiac_status` are outside-report names for bytes near the HR fields that do not
 * decode consistently across firmwares, and `optical_amp_a`/`_b` are optical channel amplitudes, not a
 * named physiological quantity. Nothing here is renamed to imply a meaning it has not earned.
 *
 * Mirror of the Swift `V18AuxSlot`.
 */
enum class V18AuxSlot(val index: Int, val width: Int, val decoderKey: String) {
    RECORD_INDEX(0, 4, "record_index"),                 // @11  per-record counter, +1 per record
    RR_COUNT(1, 1, "rr_count"),                         // @23  the strap's OWN R-R count (stream caps at 4)
    CARDIAC_FLAGS(2, 1, "cardiac_flags"),               // @33  raw byte near the HR fields; not pinned
    HR_FIXED_8_8(3, 2, "hr_fixed_8_8"),                 // @36  higher-precision HR: bpm = value/256
    RR_PACKED(4, 2, "rr_packed"),                       // @38  raw u16 near the R-R fields; not pinned
    CARDIAC_STATUS(5, 1, "cardiac_status"),             // @40  raw status-like byte near the HR fields
    STEP_CADENCE(6, 1, "step_cadence"),                 // @59  cadence-like byte (never 0)
    STATUS_WORD(7, 2, "status_word"),                   // @75  packed status word; NOT a deep-sleep marker
    STATUS_WORD_1(8, 2, "status_word_1"),               // @77  near-static sibling of @75
    STATUS_WORD_2(9, 2, "status_word_2"),               // @79  sibling of @75
    AUX_BYTE_82(10, 1, "aux_byte_82"),                  // @82  RAW byte; spo2_candidate_82 is a gated view
    OPTICAL_BASELINE_106(11, 2, "optical_baseline_106"), // @106 analog optical/ADC baseline
    OPTICAL_AMP_A(12, 1, "optical_amp_a"),              // @108 paired amplitude-like channel; 128 = invalid
    OPTICAL_AMP_B(13, 1, "optical_amp_b"),              // @109 the other half of the @108/@109 pair
    UNKNOWN_F32_113(14, 4, "unknown_f32_113"),          // @113 carried as its raw u32 bit pattern
}

/**
 * Every remaining 5/MG v18 per-second field the decoder produces and the extractor used to DROP.
 *
 * Carried VERBATIM under the decoder's own names — no scaling, no renaming, no physiological claim —
 * precisely so a later census decides what these are rather than this change pre-judging it.
 *
 * Fields that already have a durable home are deliberately NOT duplicated here: `heart_rate`,
 * `rr_intervals`, `gravity_*`, `skin_temp_raw`, `step_motion_counter`, `activity_class`, `sleep_state`
 * and `unix` all have their own columns. `motion_wear_quality@63` is the same byte as `activity_class`
 * under a second name and the same 0-2 gate. `spo2_candidate_82` is a gated 70-100 view of [auxByte82]
 * and is recoverable from the raw byte.
 *
 * INSTRUMENTATION ONLY: nothing reads this stream. Every slot is nullable, so an absent field stays
 * absent and never becomes a fabricated 0. Mirror of the Swift `V18AuxSample`.
 */
data class V18AuxRow(
    val ts: Long,
    val recordIndex: Int? = null,
    val rrCount: Int? = null,
    val cardiacFlags: Int? = null,
    val hrFixed88: Int? = null,
    val rrPacked: Int? = null,
    val cardiacStatus: Int? = null,
    val stepCadence: Int? = null,
    val statusWord: Int? = null,
    val statusWord1: Int? = null,
    val statusWord2: Int? = null,
    val auxByte82: Int? = null,
    val opticalBaseline106: Int? = null,
    val opticalAmpA: Int? = null,
    val opticalAmpB: Int? = null,
    /** The raw 32-bit pattern of `unknown_f32_113`, NOT a decoded float. See [unknownF32At113]. */
    val unknownF32Bits: Int? = null,
) {
    /**
     * The slot values in wire order — position == [V18AuxSlot.index]. The storage codec's only view of
     * this row, so the field order lives in exactly one place on each platform.
     */
    val slotValues: List<Int?>
        get() = listOf(
            recordIndex, rrCount, cardiacFlags, hrFixed88, rrPacked, cardiacStatus, stepCadence,
            statusWord, statusWord1, statusWord2, auxByte82, opticalBaseline106, opticalAmpA,
            opticalAmpB, unknownF32Bits,
        )

    /**
     * `unknown_f32_113` re-read as the float the decoder saw. The BITS are what is stored; this is a
     * convenience view, and the only place those four bytes are interpreted as a number at all.
     */
    val unknownF32At113: Double?
        get() = unknownF32Bits?.let { Float.fromBits(it).toDouble() }

    /** True when the record carried none of the slots — such a row is never banked. */
    val isEmpty: Boolean get() = slotValues.all { it == null }

    companion object {
        /**
         * Rebuild from the codec's slot list. [values] is indexed by [V18AuxSlot.index]; a short list
         * (a blob written by an older build) leaves the missing tail null rather than throwing.
         */
        fun fromSlotValues(ts: Long, values: List<Int?>): V18AuxRow {
            fun v(s: V18AuxSlot): Int? = values.getOrNull(s.index)
            return V18AuxRow(
                ts = ts,
                recordIndex = v(V18AuxSlot.RECORD_INDEX),
                rrCount = v(V18AuxSlot.RR_COUNT),
                cardiacFlags = v(V18AuxSlot.CARDIAC_FLAGS),
                hrFixed88 = v(V18AuxSlot.HR_FIXED_8_8),
                rrPacked = v(V18AuxSlot.RR_PACKED),
                cardiacStatus = v(V18AuxSlot.CARDIAC_STATUS),
                stepCadence = v(V18AuxSlot.STEP_CADENCE),
                statusWord = v(V18AuxSlot.STATUS_WORD),
                statusWord1 = v(V18AuxSlot.STATUS_WORD_1),
                statusWord2 = v(V18AuxSlot.STATUS_WORD_2),
                auxByte82 = v(V18AuxSlot.AUX_BYTE_82),
                opticalBaseline106 = v(V18AuxSlot.OPTICAL_BASELINE_106),
                opticalAmpA = v(V18AuxSlot.OPTICAL_AMP_A),
                opticalAmpB = v(V18AuxSlot.OPTICAL_AMP_B),
                unknownF32Bits = v(V18AuxSlot.UNKNOWN_F32_113),
            )
        }
    }
}

/**
 * Storage codec for the 5/MG v18 auxiliary-field stream (`v18AuxSample.fields`).
 *
 * WHY A BLOB AND NOT COLUMNS. There are fifteen slots and they arrive once per strap-second. Fifteen
 * nullable columns on a hot per-second table costs a header byte per column per row even when the value
 * is NULL, makes every future slot a migration, and spends schema surface on bytes whose meaning nobody
 * has pinned yet. One compact blob costs a 3-byte header plus only the bytes actually present, and a new
 * slot appends a bitmap bit instead of a column. The tradeoff is that SQL cannot filter on a slot — the
 * right trade here, because nothing queries these yet and a census reads whole rows anyway.
 *
 * WIRE FORMAT (little-endian throughout, no padding, no alignment):
 *
 *     byte  0      format version ([FORMAT_VERSION])
 *     bytes 1..2   u16 presence bitmap; bit [V18AuxSlot.index] set == that slot follows
 *     bytes 3..    each PRESENT slot's value, in ascending slot order, [V18AuxSlot.width] bytes each
 *
 * Absence is a first-class state: a clear bit means "the strap did not report this field", never 0.
 *
 * Byte-identical to the Swift `V18AuxCodec` (`Packages/WhoopStore/Sources/WhoopStore/V18Aux.swift`).
 * Both sides derive slot order and widths from their `V18AuxSlot`, so neither can drift without the
 * other failing its own test.
 */
object V18AuxCodec {
    /**
     * Bumped only for an INCOMPATIBLE layout change. A reader that meets a version it does not know
     * returns an empty row rather than mis-parsing bytes as a slot they are not.
     */
    const val FORMAT_VERSION = 1

    /** version byte + u16 bitmap. */
    const val HEADER_BYTES = 3

    private val SLOTS = V18AuxSlot.entries.sortedBy { it.index }

    /**
     * Pack a row's slots. Returns an EMPTY array when nothing is present, so a caller can skip the row
     * entirely rather than banking an all-absent record.
     */
    fun pack(row: V18AuxRow): ByteArray {
        val values = row.slotValues
        var bitmap = 0
        val body = ArrayList<Byte>(32)
        for (slot in SLOTS) {
            val v = values.getOrNull(slot.index) ?: continue
            bitmap = bitmap or (1 shl slot.index)
            // Truncate to the slot's declared width. Every value here came off the wire at that width,
            // so this is a no-op in practice and a guard against a caller inventing an out-of-range one.
            for (b in 0 until slot.width) body.add(((v ushr (8 * b)) and 0xFF).toByte())
        }
        if (bitmap == 0) return ByteArray(0)
        val out = ByteArray(HEADER_BYTES + body.size)
        out[0] = FORMAT_VERSION.toByte()
        out[1] = (bitmap and 0xFF).toByte()
        out[2] = ((bitmap ushr 8) and 0xFF).toByte()
        for (i in body.indices) out[HEADER_BYTES + i] = body[i]
        return out
    }

    /**
     * Inverse of [pack]. A malformed, truncated, or unknown-version blob yields an all-null row rather
     * than throwing: a read path over durable rows must never crash on one bad blob. Trailing bytes past
     * the last known slot are ignored, so a row written by a LATER build with more slots still decodes
     * the slots this build knows.
     */
    fun unpack(data: ByteArray, ts: Long): V18AuxRow {
        if (data.size < HEADER_BYTES || (data[0].toInt() and 0xFF) != FORMAT_VERSION) {
            return V18AuxRow(ts = ts)
        }
        val bitmap = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        val values = arrayOfNulls<Int>(SLOTS.size)
        var i = HEADER_BYTES
        for (slot in SLOTS) {
            if (bitmap and (1 shl slot.index) == 0) continue
            if (i + slot.width > data.size) return V18AuxRow.fromSlotValues(ts, values.toList())
            var u = 0
            for (b in 0 until slot.width) u = u or ((data[i + b].toInt() and 0xFF) shl (8 * b))
            values[slot.index] = u
            i += slot.width
        }
        return V18AuxRow.fromSlotValues(ts, values.toList())
    }
}
