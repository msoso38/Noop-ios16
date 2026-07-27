package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #103: byte-parity twin of the Swift `DeviceConfigReadProbeTests` — same synthetic fixtures, same
 * expectations — for the read-only device-config read probe. Fixtures are SYNTHETIC (built here with
 * real CRCs) because no strap has ever answered opcode 121 or 128 in this project's hands; establishing
 * whether one does is what the probe is for. They pin the allowlist, the decode, the plan and the
 * report, including every "the verb is not implemented" path the BLE handler must survive.
 */
class DeviceConfigReadProbeTest {

    /** WHOOP 4.0 COMMAND_RESPONSE: [0xAA][len u16 LE][crc8(len)][type=36][seq][cmd][payload…][crc32 LE]. */
    private fun whoop4Response(cmd: Int, payload: ByteArray, seq: Int = 1): ByteArray {
        val inner = byteArrayOf(36, seq.toByte(), cmd.toByte()) + payload
        val length = inner.size + 4
        val lenBytes = byteArrayOf((length and 0xFF).toByte(), ((length shr 8) and 0xFF).toByte())
        val c = Crc.crc32(inner)
        return byteArrayOf(0xAA.toByte()) + lenBytes + byteArrayOf(Crc.crc8(lenBytes).toByte()) + inner +
            byteArrayOf(
                (c and 0xFFL).toByte(), ((c shr 8) and 0xFFL).toByte(),
                ((c shr 16) and 0xFFL).toByte(), ((c shr 24) and 0xFFL).toByte(),
            )
    }

    /** WHOOP 5/MG COMMAND_RESPONSE in the puffin envelope: type @8, seq @9, cmd @10, record from @11. */
    private fun whoop5Response(cmd: Int, payload: ByteArray, seq: Int = 1): ByteArray {
        var inner = byteArrayOf(36, seq.toByte(), cmd.toByte()) + payload
        val pad = (4 - inner.size % 4) % 4
        if (pad > 0) inner += ByteArray(pad)
        val declLen = inner.size + 4
        val head = byteArrayOf(
            0xAA.toByte(), 0x01, (declLen and 0xFF).toByte(), ((declLen shr 8) and 0xFF).toByte(),
            0x00, 0x01,
        )
        val c16 = Crc.crc16Modbus(head)
        val c32 = Crc.crc32(inner)
        return head + byteArrayOf((c16 and 0xFF).toByte(), ((c16 shr 8) and 0xFF).toByte()) + inner +
            byteArrayOf(
                (c32 and 0xFFL).toByte(), ((c32 shr 8) and 0xFFL).toByte(),
                ((c32 shr 16) and 0xFFL).toByte(), ((c32 shr 24) and 0xFFL).toByte(),
            )
    }

    /** The 2-byte response header every COMMAND_RESPONSE carries, then the record. */
    private fun payload(result: Int, record: ByteArray): ByteArray =
        byteArrayOf(0x0A, result.toByte()) + record

    /** A record shaped like the SET side's body: the key NUL-padded to 32 bytes, then the value byte. */
    private fun echoRecord(key: String, value: Int, lead: ByteArray = ByteArray(0)): ByteArray {
        val field = ByteArray(DeviceConfigReadProbe.NAME_FIELD_BYTES)
        val bytes = key.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(field.size, bytes.size)) field[i] = bytes[i]
        return lead + field + byteArrayOf(value.toByte())
    }

    private val flagKeys: List<String> get() = Whoop5Config.enableR22Sequence.map { it.name }

    private fun report(
        family: DeviceFamily = DeviceFamily.WHOOP5,
        flags: List<String> = flagKeys,
        batch: ConfigKeySweep.Batch = ConfigKeySweep.batch(0),
    ) = DeviceConfigReadProbeReport(family, flags, batch)

    // MARK: - The read-only allowlist (the hard safety constraint)

    @Test
    fun allowlistAdmitsOnlyTheFourReadVerbs() {
        assertEquals(121, DeviceConfigReadProbe.GET_DEVICE_CONFIG_VALUE_CMD)              // 0x79
        assertEquals(128, DeviceConfigReadProbe.GET_FEATURE_FLAG_VALUE_CMD)               // 0x80
        assertEquals(115, ConfigKeySweep.START_DEVICE_CONFIG_KEY_EXCHANGE_CMD)            // 0x73
        assertEquals(116, ConfigKeySweep.SEND_NEXT_DEVICE_CONFIG_CMD)                     // 0x74
        assertEquals(setOf(115, 116, 121, 128), DeviceConfigReadProbe.READ_ONLY_OPCODES)
        for (op in DeviceConfigReadProbe.READ_ONLY_OPCODES) {
            assertTrue(DeviceConfigReadProbe.isReadOnlyOpcode(op))
        }
    }

    /**
     * The load-bearing safety test: the two config WRITE verbs must be rejected by the same predicate
     * the BLE send path consults while a probe is in flight.
     */
    @Test
    fun allowlistRejectsBothConfigWriteVerbs() {
        assertEquals(119, DeviceConfigReadProbe.SET_DEVICE_CONFIG_VALUE_CMD)   // 0x77
        assertEquals(120, DeviceConfigReadProbe.SET_FEATURE_FLAG_VALUE_CMD)    // 0x78
        assertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(119))
        assertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(120))
        assertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(Whoop5Config.SET_CONFIG_CMD))         // 0x78
        assertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(Whoop5Config.SET_DEVICE_CONFIG_CMD))  // 0x77
        for (op in DeviceConfigReadProbe.WRITE_OPCODES) {
            assertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(op))
        }
        assertTrue(
            DeviceConfigReadProbe.READ_ONLY_OPCODES.intersect(DeviceConfigReadProbe.WRITE_OPCODES).isEmpty(),
        )
    }

    /** Nothing outside the four passes either — including the feature-flag enumerate verbs #872 owns
     *  (their own probe, their own gate) and the destructive opcodes that must never come near this path. */
    @Test
    fun allowlistRejectsEveryOtherOpcode() {
        var rejected = 0
        for (op in 0..255) {
            if (DeviceConfigReadProbe.READ_ONLY_OPCODES.contains(op)) continue
            assertFalse("opcode $op must not pass", DeviceConfigReadProbe.isReadOnlyOpcode(op))
            rejected += 1
        }
        assertEquals("four admitted, every other opcode rejected", 252, rejected)
        assertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(FeatureFlagProbe.START_KEY_EXCHANGE_CMD))
        assertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(FeatureFlagProbe.SEND_NEXT_FLAG_CMD))
        assertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(25))   // FORCE_TRIM
        assertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(29))   // REBOOT_STRAP
        assertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(32))   // POWER_CYCLE_STRAP
    }

    // MARK: - Request body

    @Test
    fun requestBodyIsTheB3ByteThenA32ByteNulPaddedName() {
        val body = DeviceConfigReadProbe.requestBody("enable_spo2")
        assertEquals(33, body.size)
        assertEquals(0x01, body[0].toInt())
        assertEquals("enable_spo2", String(body.copyOfRange(1, 12), Charsets.UTF_8))
        assertTrue(body.copyOfRange(12, body.size).all { it.toInt() == 0 })
        // It carries no value byte — that is exactly what separates it from the SET bodies.
        assertEquals(33, Whoop5Config.deviceConfigBody("enable_spo2", 0x31).size)
        assertFalse(
            body.copyOfRange(1, body.size).contentEquals(Whoop5Config.deviceConfigBody("enable_spo2", 0x31)),
        )
    }

    @Test
    fun requestBodyTruncatesAnOverlongNameLikeTheSetSide() {
        val body = DeviceConfigReadProbe.requestBody("z".repeat(40))
        assertEquals(33, body.size)
        assertTrue(body.copyOfRange(1, body.size).all { (it.toInt() and 0xFF) == 0x7A })
    }

    // MARK: - Parsing

    @Test
    fun parseDecodesTheRecordAndResultCodeOnWhoop5() {
        val record = echoRecord("enable_r22_packets", 0x32, byteArrayOf(0x01))
        val frame = whoop5Response(128, payload(1, record))
        val r = DeviceConfigReadProbe.parse(frame, DeviceFamily.WHOOP5, 128).value
        assertNotNull(r)
        assertEquals(1, r!!.resultCode)
        assertFalse(r.isUnsupported)
        assertEquals(0x32, r.valueFor("enable_r22_packets"))
        assertEquals(1, r.echoOffset("enable_r22_packets"))
    }

    @Test
    fun parseOnWhoop4LeavesTheResultCodeUnlabelled() {
        val frame = whoop4Response(121, payload(1, echoRecord("k", 0x31)))
        val r = DeviceConfigReadProbe.parse(frame, DeviceFamily.WHOOP4, 121).value
        assertNotNull(r)
        assertNull(r!!.resultCode)
        assertEquals(0x31, r.valueFor("k"))
    }

    @Test
    fun unsupportedResultIsRecognised() {
        val frame = whoop5Response(121, payload(3, byteArrayOf(0, 0, 0)))
        val r = DeviceConfigReadProbe.parse(frame, DeviceFamily.WHOOP5, 121).value
        assertNotNull(r)
        assertTrue(r!!.isUnsupported)
        assertNull(r.valueFor("whatever"))
    }

    @Test
    fun noValueIsClaimedWhenTheReplyDoesNotEchoTheKey() {
        val frame = whoop5Response(128, payload(1, echoRecord("some_other_key", 0x32)))
        val r = DeviceConfigReadProbe.parse(frame, DeviceFamily.WHOOP5, 128).value
        assertNotNull(r)
        assertNull(r!!.valueFor("enable_spo2"))
        assertNull(r.echoOffset("enable_spo2"))
    }

    @Test
    fun keyMustSitInARealNulPaddedFieldNotJustAppearInTheBytes() {
        // "enable_spo2" appears, but immediately followed by more text — so it is not the 32-byte field.
        val record = "enable_spo2_extra_suffix_bytes__".toByteArray(Charsets.UTF_8) + byteArrayOf(0x31)
        val frame = whoop5Response(121, payload(1, record))
        val r = DeviceConfigReadProbe.parse(frame, DeviceFamily.WHOOP5, 121).value
        assertNotNull(r)
        assertNull("a substring match is not a name field", r!!.valueFor("enable_spo2"))
    }

    /**
     * On WHOOP 4.0, where the envelope adds no padding, a record that ends at the name field yields no
     * value at all. (On 5/MG the puffin envelope pads the inner payload to a 4-byte boundary, so the
     * same record would carry up to three trailing NULs that are envelope padding, not data.)
     */
    @Test
    fun valueIsNotClaimedWhenTheRecordStopsAtTheNameField() {
        val field = ByteArray(32)
        "enable_spo2".toByteArray(Charsets.UTF_8).forEachIndexed { i, b -> field[i] = b }
        val frame = whoop4Response(121, payload(1, field))
        val r = DeviceConfigReadProbe.parse(frame, DeviceFamily.WHOOP4, 121).value
        assertNotNull(r)
        assertEquals(0, r!!.echoOffset("enable_spo2"))
        assertNull("null is 'no value claimed', never 'value is zero'", r.valueFor("enable_spo2"))
    }

    // MARK: - Failure paths (the handler must survive every one)

    @Test
    fun badCrcIsRejected() {
        val frame = whoop4Response(121, payload(1, echoRecord("k", 0x31)))
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0xFF).toByte()
        assertEquals(
            DeviceConfigReadProbe.ParseFailure.CRC,
            DeviceConfigReadProbe.parse(frame, DeviceFamily.WHOOP4, 121).failure,
        )

        val five = whoop5Response(128, payload(1, echoRecord("k", 0x31)))
        five[7] = (five[7].toInt() xor 0xFF).toByte()
        assertEquals(
            DeviceConfigReadProbe.ParseFailure.CRC,
            DeviceConfigReadProbe.parse(five, DeviceFamily.WHOOP5, 128).failure,
        )

        assertEquals(
            DeviceConfigReadProbe.ParseFailure.CRC,
            DeviceConfigReadProbe.parse(ByteArray(0), DeviceFamily.WHOOP4, 121).failure,
        )
    }

    @Test
    fun wrongCommandAndWrongTypeAreRejected() {
        val frame = whoop4Response(128, payload(1, echoRecord("k", 0x31)))
        assertEquals(
            DeviceConfigReadProbe.ParseFailure.WRONG_COMMAND,
            DeviceConfigReadProbe.parse(frame, DeviceFamily.WHOOP4, 121).failure,
        )

        // Same bytes, but the packet type is COMMAND (35) rather than COMMAND_RESPONSE (36).
        val inner = byteArrayOf(35, 1, 121.toByte()) + payload(1, byteArrayOf(0x01, 0x02))
        val length = inner.size + 4
        val lenBytes = byteArrayOf((length and 0xFF).toByte(), ((length shr 8) and 0xFF).toByte())
        val c = Crc.crc32(inner)
        val wrong = byteArrayOf(0xAA.toByte()) + lenBytes + byteArrayOf(Crc.crc8(lenBytes).toByte()) +
            inner + byteArrayOf(
            (c and 0xFFL).toByte(), ((c shr 8) and 0xFFL).toByte(),
            ((c shr 16) and 0xFFL).toByte(), ((c shr 24) and 0xFFL).toByte(),
        )
        assertEquals(
            DeviceConfigReadProbe.ParseFailure.ENVELOPE,
            DeviceConfigReadProbe.parse(wrong, DeviceFamily.WHOOP4, 121).failure,
        )
    }

    @Test
    fun truncatedRecordIsRejected() {
        val header = whoop4Response(121, byteArrayOf(0x0A, 0x01))
        assertEquals(
            DeviceConfigReadProbe.ParseFailure.TRUNCATED,
            DeviceConfigReadProbe.parse(header, DeviceFamily.WHOOP4, 121).failure,
        )
    }

    // ---- Enumeration frame builders (the 117/118 record layouts, reused for 115/116) ----

    /** `START_DEVICE_CONFIG_KEY_EXCHANGE` reply: record = [revision][count u16 LE]. */
    private fun enumStart(result: Int, revision: Int, count: Int): ByteArray =
        whoop5Response(
            ConfigKeySweep.START_DEVICE_CONFIG_KEY_EXCHANGE_CMD,
            payload(
                result,
                byteArrayOf(revision.toByte(), (count and 0xFF).toByte(), ((count shr 8) and 0xFF).toByte()),
            ),
        )

    /** `SEND_NEXT_DEVICE_CONFIG` reply: record = [revision][index][validKey][key ASCII NUL-terminated]. */
    private fun enumNext(index: Int, key: String?, validKey: Boolean = true, result: Int = 1): ByteArray {
        var record = byteArrayOf(0x0A, index.toByte(), if (validKey) 1 else 0)
        if (key != null) record += key.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        return whoop5Response(ConfigKeySweep.SEND_NEXT_DEVICE_CONFIG_CMD, payload(result, record))
    }

    private fun startReply(frame: ByteArray): FeatureFlagProbe.StartResponse =
        FeatureFlagProbe.parseStart(
            frame,
            DeviceFamily.WHOOP5,
            ConfigKeySweep.START_DEVICE_CONFIG_KEY_EXCHANGE_CMD,
        ).value!!

    private fun nextReply(frame: ByteArray): FeatureFlagProbe.NextResponse =
        FeatureFlagProbe.parseNext(
            frame,
            DeviceFamily.WHOOP5,
            ConfigKeySweep.SEND_NEXT_DEVICE_CONFIG_CMD,
        ).value!!

    /** A two-flag report with a two-name candidate slice — small enough to drive step by step. */
    private fun smallReport(limit: Int = 2) = DeviceConfigReadProbeReport(
        DeviceFamily.WHOOP5,
        listOf("enable_r22_packets", "hr_ch_switching"),
        ConfigKeySweep.batch(0, limit),
    )

    private fun valueReply(resultCode: Int?, record: ByteArray) =
        DeviceConfigReadProbe.ValueResponse(resultCode, record)

    // ---- The plan: enumerate first, guess last ----

    /** The whole point of the restructure: nothing is guessed until the strap has been asked to list its
     *  own keys. */
    @Test
    fun theProbeAsksTheStrapToEnumerateBeforeItGuessesAnything() {
        val report = smallReport()
        val first = report.nextStep()!!
        assertEquals(ConfigKeySweep.START_DEVICE_CONFIG_KEY_EXCHANGE_CMD, first.opcode)
        assertEquals(DeviceConfigReadProbeReport.Group.ENUMERATE, first.group)
        assertNull(first.derivation)
    }

    /** A successful enumeration is evidence about what the firmware HOLDS, not proof of what it would
     *  ACCEPT — and it says nothing at all about the feature-flag namespace, where most of the catalogue
     *  is aimed. So a run can be asked to sweep anyway, and then the candidate steps must actually happen. */
    @Test
    fun aForcedSweepAsksTheCandidatesEvenAfterEnumerationSucceeds() {
        val report = DeviceConfigReadProbeReport(
            DeviceFamily.WHOOP5,
            listOf("enable_r22_packets", "hr_ch_switching"),
            ConfigKeySweep.batch(0, 2),
            forceCandidateSweep = true,
        )
        assertTrue(report.runsCandidateSweep)

        report.nextStep()!!
        report.noteEnumerationStart(startReply(enumStart(1, 10, 1)))
        report.nextStep()!!
        // The Broadcast-HR key: enumerated, and one NOOP already had — so newKeysFound stays empty and the
        // headline is free to report what the sweep established rather than what enumeration found.
        assertTrue(report.noteEnumerationNext(nextReply(enumNext(1, "whoop_live_hr_in_adv_ind_pkt"))))
        report.nextStep()!!
        assertFalse(report.noteEnumerationNext(nextReply(enumNext(0xFF, null, validKey = false))))
        assertEquals(DeviceConfigReadProbeReport.VerbStatus.ANSWERED, report.enumerationVerb)
        assertTrue(report.enumeratedKeys.isNotEmpty())

        val candidates = mutableListOf<String>()
        var guard = 0
        while (guard < 200) {
            val step = report.nextStep() ?: break
            guard += 1
            val isCandidate = step.group == DeviceConfigReadProbeReport.Group.CANDIDATE
            if (isCandidate) candidates.add(step.key)
            report.noteReply(valueReply(if (isCandidate) 0 else 1, echoRecord(step.key, 0x30)), step)
        }
        assertEquals(
            ConfigKeySweep.batch(0, 2).candidates.map { it.key },
            candidates.distinct(),
        )
        val text = report.render()
        assertTrue(text.contains("FULL SWEEP: asked even though enumeration succeeded"))
        assertFalse(text.contains("skipped — the strap enumerated its own device-config keys"))
        assertTrue(report.verdict, report.verdict.contains("clean negative"))
        assertTrue(report.verdict, report.verdict.contains("enumerated in full"))
    }

    /** The routing fix itself: every candidate is asked through BOTH value verbs when both answered. */
    @Test
    fun everyCandidateIsAskedThroughEveryAnsweringVerb() {
        val (report, first) = driveToCandidates(2)
        var step = first
        val byKey = mutableMapOf<String, MutableList<Int>>()
        while (step != null) {
            byKey.getOrPut(step.key) { mutableListOf() }.add(step.opcode)
            report.noteReply(valueReply(0, ByteArray(0)), step)
            step = report.nextStep()
        }
        for ((key, opcodes) in byKey) {
            assertEquals("$key must be asked through both verbs", setOf(121, 128), opcodes.toSet())
        }
        val text = report.render()
        assertTrue(text, text.contains("121=unknown · 128=unknown"))
        assertTrue(text, text.contains("each name asked through 2 verb(s)"))
    }

    /** If the strap lists its own device-config keys there is nothing left to guess, so the sweep is
     *  skipped entirely rather than spending round-trips on names the answer already covers. */
    @Test
    fun anAnsweringEnumerationSkipsTheGuessedSweepEntirely() {
        val report = smallReport()
        val s1 = report.nextStep()!!
        assertEquals(115, s1.opcode)
        report.noteEnumerationStart(startReply(enumStart(1, 10, 2)))

        val s2 = report.nextStep()!!
        assertEquals(ConfigKeySweep.SEND_NEXT_DEVICE_CONFIG_CMD, s2.opcode)
        assertTrue(report.noteEnumerationNext(nextReply(enumNext(1, "whoop_live_hr_in_adv_ind_pkt"))))
        report.nextStep()!!
        assertTrue(report.noteEnumerationNext(nextReply(enumNext(2, "whoop_sleep_coach_enabled"))))
        report.nextStep()!!
        assertFalse(report.noteEnumerationNext(nextReply(enumNext(0xFF, null, validKey = false))))

        assertEquals(
            listOf("whoop_live_hr_in_adv_ind_pkt", "whoop_sleep_coach_enabled"),
            report.enumeratedKeys,
        )
        assertEquals(DeviceConfigReadProbeReport.VerbStatus.ANSWERED, report.enumerationVerb)

        var guard = 0
        while (guard < 200) {
            val step = report.nextStep() ?: break
            guard += 1
            assertFalse(
                "the sweep must not run once enumeration answered",
                step.group == DeviceConfigReadProbeReport.Group.CANDIDATE,
            )
            report.noteReply(valueReply(1, echoRecord(step.key, 0x32)), step)
        }
        assertTrue(report.render().contains("skipped — the strap enumerated its own device-config keys"))
        // The Broadcast-HR key is one NOOP already writes, so only the second name is NEW.
        assertEquals(listOf("whoop_sleep_coach_enabled"), report.newKeysFound)
        assertTrue(report.verdict.startsWith("1 config key name(s) found that NOOP did not have"))
    }

    /** The #874 discipline, inherited: the strap's own end marker stops the walk, but a name OUR parser
     *  declines is counted and stepped over — one bad entry must not throw away every key after it. */
    @Test
    fun anUndecodableNameIsSteppedOverRatherThanEndingTheWalk() {
        val report = smallReport()
        report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(1, 10, 3)))
        report.nextStep()
        assertTrue(
            report.noteEnumerationNext(
                FeatureFlagProbe.NextResponse(1, 10, 1, validKey = true, key = null),
            ),
        )
        report.nextStep()
        assertTrue(report.noteEnumerationNext(nextReply(enumNext(2, "whoop_after_the_bad_one"))))
        assertEquals(listOf("whoop_after_the_bad_one"), report.enumeratedKeys)
        assertEquals(1, report.enumerationSkipped)
    }

    /** A refused enumeration is the case the guessing fallback exists for — and it must cost exactly one
     *  round-trip, not one per key. */
    @Test
    fun anUnsupportedEnumerationCostsOneRoundTripAndOpensTheFallback() {
        val report = smallReport()
        val s1 = report.nextStep()!!
        assertEquals(115, s1.opcode)
        report.noteEnumerationStart(startReply(enumStart(3, 0, 0)))
        assertEquals(DeviceConfigReadProbeReport.VerbStatus.UNSUPPORTED, report.enumerationVerb)

        val s2 = report.nextStep()!!
        assertEquals(
            "116 must not be asked once 115 refused",
            DeviceConfigReadProbe.GET_FEATURE_FLAG_VALUE_CMD,
            s2.opcode,
        )
        assertEquals(DeviceConfigReadProbeReport.Group.DISCOVERY, s2.group)
    }

    /** A silent enumeration retires the pair after ONE timeout rather than one per entry. */
    @Test
    fun aSilentEnumerationRetiresAfterOneTimeout() {
        val report = smallReport()
        val s1 = report.nextStep()!!
        report.noteTimeout(s1, 8)
        assertEquals(DeviceConfigReadProbeReport.VerbStatus.SILENT, report.enumerationVerb)
        assertEquals(DeviceConfigReadProbeReport.Group.DISCOVERY, report.nextStep()!!.group)
        assertTrue(report.render().contains("(none — no reply to 115)"))
    }

    @Test
    fun anUndecodableEnumerationReplyRetiresIt() {
        val report = smallReport()
        val s1 = report.nextStep()!!
        report.noteFailure(DeviceConfigReadProbe.ParseFailure.CRC, s1)
        assertEquals(DeviceConfigReadProbeReport.VerbStatus.UNDECODABLE, report.enumerationVerb)
        assertEquals(DeviceConfigReadProbeReport.Group.DISCOVERY, report.nextStep()!!.group)
        assertEquals("CRC failed — frame rejected (never decoded)", report.stopReason)
    }

    // ---- Cross-namespace ----

    /** Two round-trips that settle whether the namespaces are separate — the result shapes every later
     *  sweep, so it is asked of each verb that answered. */
    @Test
    fun crossNamespaceIsAskedOfEachAnsweringVerb() {
        val report = smallReport()
        report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(3, 0, 0)))

        val d1 = report.nextStep()!!
        report.noteReply(valueReply(1, echoRecord(d1.key, 0x32)), d1)
        val d2 = report.nextStep()!!
        report.noteReply(valueReply(1, echoRecord(d2.key, 0x30)), d2)

        val x1 = report.nextStep()!!
        assertEquals(DeviceConfigReadProbeReport.Group.CROSS_NAMESPACE, x1.group)
        assertEquals(DeviceConfigReadProbe.GET_FEATURE_FLAG_VALUE_CMD, x1.opcode)
        assertEquals(DeviceConfigReadProbe.DEVICE_CONFIG_DISCOVERY_KEY, x1.key)
        report.noteReply(valueReply(0, ByteArray(0)), x1)

        val x2 = report.nextStep()!!
        assertEquals(DeviceConfigReadProbeReport.Group.CROSS_NAMESPACE, x2.group)
        assertEquals(DeviceConfigReadProbe.GET_DEVICE_CONFIG_VALUE_CMD, x2.opcode)
        assertEquals("enable_r22_packets", x2.key)
        report.noteReply(valueReply(0, ByteArray(0)), x2)

        assertEquals(ConfigKeySweep.Existence.UNKNOWN, report.featureFlagVerbOnDeviceConfigKey)
        assertEquals(ConfigKeySweep.Existence.UNKNOWN, report.deviceConfigVerbOnFlagKey)
        assertTrue(report.render().contains("the namespaces are separate"))
    }

    /** If one verb turns out to serve both namespaces, everything afterwards goes through it — halving
     *  the work every future sweep needs, on evidence gathered in the same run. */
    @Test
    fun aVerbShownToServeBothNamespacesCarriesEverythingAfterwards() {
        val report = smallReport()
        report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(3, 0, 0)))
        val d1 = report.nextStep()!!
        report.noteReply(valueReply(1, echoRecord(d1.key, 0x32)), d1)
        val d2 = report.nextStep()!!
        report.noteReply(valueReply(1, echoRecord(d2.key, 0x30)), d2)
        val x1 = report.nextStep()!!
        report.noteReply(valueReply(0, ByteArray(0)), x1)
        val x2 = report.nextStep()!!
        report.noteReply(valueReply(1, echoRecord(x2.key, 0x32)), x2)
        assertEquals(ConfigKeySweep.Existence.EXISTS, report.deviceConfigVerbOnFlagKey)

        val k1 = report.nextStep()!!
        assertEquals(
            "the verb proved to serve both namespaces carries the rest of the plan",
            DeviceConfigReadProbe.GET_DEVICE_CONFIG_VALUE_CMD,
            k1.opcode,
        )
        assertTrue(report.render().contains("GET_DEVICE_CONFIG_VALUE(121) serves BOTH namespaces."))
    }

    // ---- The sweep ----

    /** Drive the plan with enumeration refused, stopping at the FIRST candidate step and handing it back
     *  alongside the report (a pulled step cannot be pushed back). */
    private fun driveToCandidates(limit: Int): Pair<DeviceConfigReadProbeReport, DeviceConfigReadProbeReport.Step?> {
        val report = smallReport(limit)
        report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(3, 0, 0)))
        while (true) {
            val step = report.nextStep() ?: return report to null
            if (step.group == DeviceConfigReadProbeReport.Group.CANDIDATE) return report to step
            report.noteReply(valueReply(1, echoRecord(step.key, 0x32)), step)
        }
    }

    /** A fully-negative sweep is a RESULT, and the verdict must say so rather than reading like a
     *  failure. */
    @Test
    fun aFullyNegativeSweepIsACleanNegativeVerdict() {
        val (report, first) = driveToCandidates(2)
        var step = first
        val asked = mutableListOf<String>()
        while (step != null) {
            assertEquals(DeviceConfigReadProbeReport.Group.CANDIDATE, step.group)
            asked.add(step.key)
            report.noteReply(valueReply(0, ByteArray(0)), step)
            step = report.nextStep()
        }
        // Each NAME is asked through every verb that answered — here both, so each appears twice.
        assertEquals(listOf("enable_sig1", "enable_sig1", "enable_sig2", "enable_sig2"), asked)
        assertEquals(2, asked.distinct().size)
        assertEquals(
            "asked 2 candidate key name(s); this firmware has none of them (a clean negative)",
            report.verdict,
        )
        assertTrue(report.newKeysFound.isEmpty())
    }

    /** And a hit is the headline, named in the verdict so a strap log's first line carries the finding. */
    @Test
    fun aCandidateThatExistsBecomesTheHeadline() {
        val (report, first) = driveToCandidates(2)
        val c1 = first!!
        report.noteReply(valueReply(1, echoRecord(c1.key, 0x31)), c1)
        val c2 = report.nextStep()!!
        report.noteReply(valueReply(0, ByteArray(0)), c2)
        assertEquals(listOf("enable_sig1"), report.newKeysFound)
        assertEquals("1 config key name(s) found that NOOP did not have: enable_sig1", report.verdict)
        assertTrue(report.trace.any { it.contains("enable_sig1") && it.contains("exists") })
        assertFalse(report.trace.any { it.contains("enable_sig2") })
    }

    /** No silent truncation: the report states how many names it asked, how many the catalogue holds, and
     *  how many remain untested, plus where the next run resumes. */
    @Test
    fun theReportStatesTestedAndUntestedCountsAndWhereToResume() {
        val (report, first) = driveToCandidates(2)
        var step = first
        while (step != null) {
            report.noteReply(valueReply(0, ByteArray(0)), step)
            step = report.nextStep()
        }
        val text = report.render()
        val total = ConfigKeySweep.CATALOGUE.size
        assertTrue(text, text.contains("(2 asked of $total in the catalogue; ${total - 2} untested)"))
        assertTrue(text, text.contains("Run the probe again to continue from catalogue entry 3."))
    }

    /** The default catalogue is smaller than one run's budget, so a real run reports nothing untested. */
    @Test
    fun aFullRunOfTodaysCatalogueLeavesNothingUntested() {
        val report = report()
        report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(3, 0, 0)))
        var candidates = 0
        var steps = 0
        while (steps < DeviceConfigReadProbe.MAX_STEPS) {
            val step = report.nextStep() ?: break
            steps += 1
            val isCandidate = step.group == DeviceConfigReadProbeReport.Group.CANDIDATE
            if (isCandidate) candidates += 1
            report.noteReply(valueReply(if (isCandidate) 0 else 1, echoRecord(step.key, 0x32)), step)
        }
        // One round-trip per (name x answering verb) — both verbs answered here.
        assertEquals(ConfigKeySweep.CATALOGUE.size * 2, candidates)
        assertNull("a full default run must not hit the safety cap", report.stopReason)
        assertTrue(report.render().contains("none untested"))
    }

    /** The safety cap still binds, whatever the plan holds. */
    @Test
    fun thePlanIsCappedEvenWhenTheStrapEnumeratesForever() {
        val report = smallReport()
        report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(1, 10, 9999)))
        var seen = 0
        while (seen < 500) {
            val step = report.nextStep() ?: break
            seen += 1
            if (step.group == DeviceConfigReadProbeReport.Group.ENUMERATE) {
                report.noteEnumerationNext(nextReply(enumNext(1, "whoop_stuck")))
            } else {
                report.noteReply(valueReply(0, ByteArray(0)), step)
            }
        }
        assertTrue(report.steps <= DeviceConfigReadProbe.MAX_STEPS)
        assertNotNull(report.stopReason)
    }

    // ---- Report ----

    /** Byte-for-byte golden, asserted identically by the Swift twin, so a shared strap log reads the same
     *  either side and a wording drift fails here rather than in a user's log. */
    @Test
    fun goldenReportIsByteIdenticalAcrossPlatforms() {
        val report = smallReport()
        val s1 = report.nextStep()!!
        assertEquals(115, s1.opcode)
        report.noteEnumerationStart(startReply(enumStart(3, 0, 0)))

        val s2 = report.nextStep()!!
        report.noteReply(valueReply(1, echoRecord("enable_r22_packets", 0x32)), s2)
        val s3 = report.nextStep()!!
        report.noteReply(
            valueReply(1, echoRecord(DeviceConfigReadProbe.DEVICE_CONFIG_DISCOVERY_KEY, 0x30)),
            s3,
        )
        val s4 = report.nextStep()!!
        report.noteReply(valueReply(0, byteArrayOf(0x01, 0x00)), s4)
        val s5 = report.nextStep()!!
        report.noteReply(valueReply(0, byteArrayOf(0x01, 0x00)), s5)
        val s6 = report.nextStep()!!
        report.noteReply(valueReply(1, echoRecord("hr_ch_switching", 0x32)), s6)
        // Two names × two answering verbs = four candidate round-trips.
        repeat(4) {
            val c = report.nextStep()!!
            assertEquals(DeviceConfigReadProbeReport.Group.CANDIDATE, c.group)
            report.noteReply(valueReply(0, byteArrayOf(0x01, 0x00)), c)
        }
        assertNull(report.nextStep())

        assertEquals(GOLDEN_REPORT, report.render())
    }

    private companion object {
        const val GOLDEN_REPORT = """#103 CONFIG KEY PROBE — WHOOP 5/MG
Read-only: START_DEVICE_CONFIG_KEY_EXCHANGE(115), SEND_NEXT_DEVICE_CONFIG(116), GET_DEVICE_CONFIG_VALUE(121), GET_FF_VALUE(128).
No value is written; SET_DEVICE_CONFIG_VALUE(119) and SET_FF_VALUE(120) are never sent from this path.
Oracle: result=SUCCESS(1) means the key NAME exists; result=FAILURE(0) means the firmware has no such key.

Verdict: asked 2 candidate key name(s); this firmware has none of them (a clean negative)

Verbs:
  device-config enumerate(115/116)  unsupported
  GET_FF_VALUE(128)                 answered
  GET_DEVICE_CONFIG_VALUE(121)      answered

Device-config keys the strap listed for itself (115/116) (0):
  (none — the firmware refused 115 as UNSUPPORTED)

Namespace separation:
  128 asked for a device-config key     unknown
  121 asked for a feature-flag key      unknown
  ⇒ the namespaces are separate: neither verb sees the other's keys.

Discovery — one round-trip per value verb against a key it should know (2):
   1. enable_r22_packets              = '2' (0x32)
   2. whoop_live_hr_in_adv_ind_pkt    = '0' (0x30)

Known key values (the flags NOOP writes, plus anything enumeration returned) (1):
   1. hr_ch_switching                 = '2' (0x32)

Candidate key names — GUESSES, never observed on a wire or in any table (2 asked of 54 in the catalogue; 52 untested):
  0 exist · 2 do not · 0 inconclusive  (each name asked through 2 verb(s))

  sig<N> series (T8) — the firmware numbers its signal chains; sig11/sig12 are the two we have (2):
    1. enable_sig1                     121=unknown · 128=unknown
    2. enable_sig2                     121=unknown · 128=unknown

  Run the probe again to continue from catalogue entry 3.

Exchange:
  START_DEVICE_CONFIG_KEY_EXCHANGE(115) → result=UNSUPPORTED(3) — the firmware does not serve this verb
  GET_FF_VALUE(128) key="enable_r22_packets" → result=SUCCESS(1) exists value='2' (0x32) record=[65 6e 61 62 6c 65 5f 72 32 32 5f 70 61 63 6b 65 74 73 00 00 00 00 00 00 00 00 00 00 00 00 00 00 32]
  GET_DEVICE_CONFIG_VALUE(121) key="whoop_live_hr_in_adv_ind_pkt" → result=SUCCESS(1) exists value='0' (0x30) record=[77 68 6f 6f 70 5f 6c 69 76 65 5f 68 72 5f 69 6e 5f 61 64 76 5f 69 6e 64 5f 70 6b 74 00 00 00 00 30]
  GET_FF_VALUE(128) key="whoop_live_hr_in_adv_ind_pkt" → result=FAILURE(0) unknown record=[01 00]
  GET_DEVICE_CONFIG_VALUE(121) key="enable_r22_packets" → result=FAILURE(0) unknown record=[01 00]
  GET_FF_VALUE(128) key="hr_ch_switching" → result=SUCCESS(1) exists value='2' (0x32) record=[68 72 5f 63 68 5f 73 77 69 74 63 68 69 6e 67 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 32]
"""
    }
}
