package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #174: the R22 DISABLE path — the sequence bytes, the key-aware SET_FF_VALUE(120) allowlist, and the
 * staged write→read-back run. Twin of the Swift `R22DisableTests`; keep the assertions in lockstep.
 *
 * Everything here runs with no app, no strap and no Bluetooth. That matters more than usual: the one
 * thing that CANNOT be tested off-strap is whether the firmware accepts the off value, and the design is
 * built so the run itself answers that on first use rather than assuming it.
 */
class R22DisableTest {

    // The sequence ------------------------------------------------------------------------------

    @Test
    fun disableSequenceMirrorsEnableExactly() {
        val enable = Whoop5Config.enableR22Sequence
        val disable = Whoop5Config.disableR22Sequence
        assertEquals("the undo must cover every key the enable sets", enable.size, disable.size)
        assertEquals(enable.map { it.name }, disable.map { it.name })
        assertTrue(disable.all { it.value == Whoop5Config.FEATURE_FLAG_OFF_VALUE })
    }

    @Test
    fun offValueIsAsciiZero() {
        assertEquals(0x30, Whoop5Config.FEATURE_FLAG_OFF_VALUE)
        assertEquals('0'.code, Whoop5Config.FEATURE_FLAG_OFF_VALUE)
    }

    /** The disable frame differs from the enable frame in EXACTLY one byte — the value at frame offset 44
     *  — plus the CRC32 trailer it necessarily changes. */
    @Test
    fun disableFrameDiffersFromEnableInOnlyTheValueByte() {
        val on = Whoop5Config.frame(Whoop5Config.Flag("enable_r22_packets", 0x32), 1)
        val off = Whoop5Config.frame(Whoop5Config.Flag("enable_r22_packets", 0x30), 1)
        assertEquals(on.size, off.size)
        val differing = on.indices.filter { on[it] != off[it] }
        assertEquals("the value byte sits at frame offset 44", 44, differing.first())
        assertEquals("value byte + CRC32 trailer only", 5, differing.size)
        assertEquals(0x30, off[44].toInt() and 0xFF)
    }

    @Test
    fun disableFramesVerifyAndCarryDistinctSeqs() {
        val frames = Whoop5Config.disableSequenceFrames(1)
        assertEquals(16, frames.size)
        assertEquals((1..16).toList(), frames.map { it[9].toInt() and 0xFF })
        for (f in frames) {
            assertTrue("every disable frame must pass 5/MG CRCs", Framing.frameCrcOk(f, DeviceFamily.WHOOP5))
        }
    }

    /** `disable_pip_r26_packets` gets the SAME off byte as the `enable_*` keys: this sequence is the undo
     *  of the enable sequence, not a per-key semantic inversion. Pinned because it is the one line a
     *  reviewer is most likely to "fix". */
    @Test
    fun disablePipR26GetsTheSameOffByteAsTheEnableKeys() {
        val pip = Whoop5Config.disableR22Sequence.firstOrNull { it.name == "disable_pip_r26_packets" }
        assertEquals(Whoop5Config.FEATURE_FLAG_OFF_VALUE, pip?.value)
        assertEquals(
            "and the enable value stays '2' — this key is why boolean semantics are not assumed",
            0x32,
            Whoop5Config.enableR22Sequence.firstOrNull { it.name == "disable_pip_r26_packets" }?.value,
        )
    }

    // The write gate ----------------------------------------------------------------------------

    @Test
    fun gateAdmitsOnlyOpcode120() {
        val payload = byteArrayOf(0x01) + Whoop5Config.payloadBody("enable_r22_packets", 0x30)
        for (opcode in 0..255) {
            assertEquals(
                "opcode $opcode admission",
                opcode == 120,
                FeatureFlagWriteGate.admitsSend(opcode, payload, deepDataOptIn = true),
            )
        }
    }

    @Test
    fun gateRefusesDeviceConfigWriteVerb() {
        val payload = byteArrayOf(0x01) + Whoop5Config.payloadBody("enable_r22_packets", 0x30)
        assertFalse(
            "119 keeps its own Broadcast-HR clause and must never be reachable from here",
            FeatureFlagWriteGate.admitsSend(119, payload, deepDataOptIn = true),
        )
    }

    @Test
    fun gateRefusesEverythingWithoutTheOptIn() {
        for (flag in Whoop5Config.enableR22Sequence) {
            for (value in listOf(flag.value, Whoop5Config.FEATURE_FLAG_OFF_VALUE)) {
                val payload = byteArrayOf(0x01) + Whoop5Config.payloadBody(flag.name, value)
                assertFalse(
                    "${flag.name}=$value must be refused with the opt-in off",
                    FeatureFlagWriteGate.admitsSend(120, payload, deepDataOptIn = false),
                )
            }
        }
    }

    @Test
    fun gateAdmitsEveryR22KeyForBothItsEnableValueAndOff() {
        for (flag in Whoop5Config.enableR22Sequence) {
            for (value in listOf(flag.value, Whoop5Config.FEATURE_FLAG_OFF_VALUE)) {
                val payload = byteArrayOf(0x01) + Whoop5Config.payloadBody(flag.name, value)
                assertTrue(
                    "${flag.name}=$value must be admitted",
                    FeatureFlagWriteGate.admitsSend(120, payload, deepDataOptIn = true),
                )
            }
        }
    }

    /** The tightening this gate exists for: before it, opcode 120 was admitted on the opt-in alone, so ANY
     *  feature-flag key with ANY value could travel. Both halves of that are now closed. */
    @Test
    fun gateRefusesUnknownKeysAndUnknownValues() {
        for (key in listOf("general_ab_test", "enable_pdaf_walk_det", "enable_maverick_model", "enable_r22_v7_packets")) {
            val payload = byteArrayOf(0x01) + Whoop5Config.payloadBody(key, 0x30)
            assertFalse("$key is not an R22 key", FeatureFlagWriteGate.admitsSend(120, payload, deepDataOptIn = true))
        }
        for (value in listOf(0x00, 0x29, 0x33, 0x39, 0x41, 0xFF)) {
            val payload = byteArrayOf(0x01) + Whoop5Config.payloadBody("enable_r22_packets", value)
            assertFalse(
                "value 0x%02x is neither the enable nor the off value".format(value),
                FeatureFlagWriteGate.admitsSend(120, payload, deepDataOptIn = true),
            )
        }
        // enable_r22_v4_packets' enable value is '1', so '2' must be refused for THAT key specifically.
        val wrongForV4 = byteArrayOf(0x01) + Whoop5Config.payloadBody("enable_r22_v4_packets", 0x32)
        assertFalse(
            "the admitted value is per-key, not a shared pair",
            FeatureFlagWriteGate.admitsSend(120, wrongForV4, deepDataOptIn = true),
        )
    }

    @Test
    fun gateRefusesMalformedBodies() {
        val good = Whoop5Config.payloadBody("enable_r22_packets", 0x30)
        assertFalse(FeatureFlagWriteGate.admitsSend(120, byteArrayOf(0x02) + good, deepDataOptIn = true))
        assertFalse(FeatureFlagWriteGate.admitsSend(120, byteArrayOf(0x01) + good.copyOfRange(0, good.size - 1), deepDataOptIn = true))
        assertFalse(FeatureFlagWriteGate.admitsSend(120, ByteArray(0), deepDataOptIn = true))
        val dirty = good.copyOf(); dirty[25] = 0x41
        assertFalse(
            "a name field whose padding is not NUL is not a name field",
            FeatureFlagWriteGate.admitsSend(120, byteArrayOf(0x01) + dirty, deepDataOptIn = true),
        )
        val binary = good.copyOf(); binary[3] = 0x01
        assertFalse(FeatureFlagWriteGate.admitsSend(120, byteArrayOf(0x01) + binary, deepDataOptIn = true))
    }

    @Test
    fun keyAndValueRoundTripsEveryR22Key() {
        for (flag in Whoop5Config.enableR22Sequence) {
            val payload = byteArrayOf(0x01) + Whoop5Config.payloadBody(flag.name, Whoop5Config.FEATURE_FLAG_OFF_VALUE)
            val parsed = FeatureFlagWriteGate.keyAndValue(payload)
            assertEquals(flag.name, parsed?.key)
            assertEquals(0x30, parsed?.value)
        }
    }

    @Test
    fun readBackOpcodeIsOnly128() {
        for (opcode in 0..255) {
            assertEquals(opcode == 128, FeatureFlagWriteGate.isReadBackOpcode(opcode))
        }
    }

    // The staged run ----------------------------------------------------------------------------

    /** A synthetic GET_FF_VALUE reply record: the key NUL-padded to 32 bytes, then the value byte. */
    private fun reply(key: String, value: Int, resultCode: Int = 1): DeviceConfigReadProbe.ValueResponse {
        val record = ByteArray(33)
        val bytes = key.toByteArray(Charsets.US_ASCII)
        for (i in bytes.indices) if (i < 32) record[i] = bytes[i]
        record[32] = (value and 0xFF).toByte()
        return DeviceConfigReadProbe.ValueResponse(resultCode, record)
    }

    private fun failureReply() = DeviceConfigReadProbe.ValueResponse(0, ByteArray(0))

    @Test
    fun planStartsWithTheProbeWriteThenItsReadBack() {
        val r = R22DisableReport()
        val first = r.nextStep()
        assertEquals(R22DisableReport.Stage.PROBE_WRITE, first?.stage)
        assertEquals(R22DisableReport.PROBE_KEY, first?.key)
        assertEquals(120, first?.opcode)
        val second = r.nextStep()
        assertEquals(R22DisableReport.Stage.PROBE_READ, second?.stage)
        assertEquals(128, second?.opcode)
    }

    /** The gate: a probe read-back still showing the old value STOPS the run with fifteen keys untouched. */
    @Test
    fun probeRejectionStopsTheRunAndLeavesFifteenKeysUntouched() {
        val r = R22DisableReport()
        val write = r.nextStep()!!
        r.noteWriteAck(1, write)                     // SUCCESS ack — deliberately not believed
        val read = r.nextStep()!!
        r.noteReadBack(reply(R22DisableReport.PROBE_KEY, 0x31), read)

        assertNull("no further steps once the off value is rejected", r.nextStep())
        assertEquals(R22DisableReport.Verdict.OFF_VALUE_REJECTED, r.verdict)
        assertEquals(R22DisableReport.KeyOutcome.UNCHANGED, r.outcomes[R22DisableReport.PROBE_KEY])
        assertEquals(15, r.keys.count { r.outcomes[it] == R22DisableReport.KeyOutcome.SKIPPED })
        assertTrue(r.render().contains("is NOT how this firmware clears a feature flag"))
        assertEquals(1, r.writeAcks[R22DisableReport.PROBE_KEY])
    }

    @Test
    fun fullRunClearsAllSixteen() {
        val r = R22DisableReport()
        var steps = 0
        while (true) {
            val step = r.nextStep() ?: break
            steps++
            assertTrue("plan must terminate", steps < 100)
            if (step.isWrite) {
                assertEquals(120, step.opcode)
                r.noteWriteAck(1, step)
            } else {
                assertEquals(128, step.opcode)
                r.noteReadBack(reply(step.key, 0x30), step)
            }
        }
        assertEquals(R22DisableReport.Verdict.ALL_CLEARED, r.verdict)
        assertEquals(16, r.keys.size)
        for (key in r.keys) assertEquals("$key should be cleared", R22DisableReport.KeyOutcome.CLEARED, r.outcomes[key])
        // 1 probe write + 1 probe read + 15 clear writes + 16 verify reads
        assertEquals(33, steps)
        assertTrue(r.summary.contains("All 16 R22 flags cleared"))
    }

    /** FAILURE on a read-back means the key no longer has a stored value at all — its own state, not
     *  folded into "cleared". */
    @Test
    fun failureReadBackIsReportedAsUnsetNotCleared() {
        val r = R22DisableReport()
        r.noteWriteAck(1, r.nextStep()!!)
        r.noteReadBack(failureReply(), r.nextStep()!!)
        assertEquals(R22DisableReport.KeyOutcome.UNSET, r.outcomes[R22DisableReport.PROBE_KEY])
        assertTrue("an unset key is a success — the run should continue", r.probePassed)
        assertNotNull("the clear stage must follow a passing probe", r.nextStep())
    }

    @Test
    fun partialRunIsReportedAsPartialNotSuccess() {
        val r = R22DisableReport()
        while (true) {
            val step = r.nextStep() ?: break
            when {
                step.isWrite -> r.noteWriteAck(1, step)
                step.key == "wear_detect_bias" -> r.noteReadBack(reply(step.key, 0x32), step)
                else -> r.noteReadBack(reply(step.key, 0x30), step)
            }
        }
        assertEquals(R22DisableReport.Verdict.PARTIAL, r.verdict)
        assertEquals(R22DisableReport.KeyOutcome.UNCHANGED, r.outcomes["wear_detect_bias"])
        assertTrue(r.summary.contains("15 of 16"))
    }

    /** A SUCCESS ack whose read-back did not move must never render as success — the #907/#891 rule. */
    @Test
    fun successAckWithUnmovedValueIsNeverReportedAsSuccess() {
        val r = R22DisableReport()
        r.noteWriteAck(1, r.nextStep()!!)
        r.noteReadBack(reply(R22DisableReport.PROBE_KEY, 0x31), r.nextStep()!!)
        val text = r.render()
        assertFalse(r.verdict == R22DisableReport.Verdict.ALL_CLEARED)
        assertTrue(text.contains("recorded, not proof"))
        assertTrue(text.contains("NOT treated as proof"))
    }

    @Test
    fun silentProbeStopsTheRun() {
        val r = R22DisableReport()
        val write = r.nextStep()!!
        r.noteTimeout(write, 8)
        assertNull("a silent WRITE decides nothing — only the read-back does", r.outcomes[R22DisableReport.PROBE_KEY])
        r.noteTimeout(r.nextStep()!!, 8)
        assertEquals(R22DisableReport.Verdict.PROBE_INCONCLUSIVE, r.verdict)
        assertNull(r.nextStep())
    }

    @Test
    fun abandonMarksRemainingKeysSkipped() {
        val r = R22DisableReport()
        r.noteWriteAck(1, r.nextStep()!!)
        r.noteAbandoned("link dropped")
        assertEquals(R22DisableReport.Verdict.ABANDONED, r.verdict)
        assertNull(r.nextStep())
        assertEquals(16, r.keys.count { r.outcomes[it] == R22DisableReport.KeyOutcome.SKIPPED })
    }

    @Test
    fun undecodableReadBackIsNotTreatedAsCleared() {
        val r = R22DisableReport()
        r.noteWriteAck(1, r.nextStep()!!)
        r.noteReadFailure(DeviceConfigReadProbe.ParseFailure.CRC, r.nextStep()!!)
        assertEquals(R22DisableReport.KeyOutcome.UNDECODABLE, r.outcomes[R22DisableReport.PROBE_KEY])
        assertFalse(r.probePassed)
        assertEquals(R22DisableReport.Verdict.PROBE_INCONCLUSIVE, r.verdict)
    }

    /** The report must state the four standing caveats, so the log, the UI and the PR cannot drift on what
     *  was actually established. */
    @Test
    fun renderCarriesTheCaveats() {
        val r = R22DisableReport()
        while (true) {
            val step = r.nextStep() ?: break
            if (step.isWrite) r.noteWriteAck(1, step) else r.noteReadBack(reply(step.key, 0x30), step)
        }
        val text = r.render()
        assertTrue(text.contains("'0' as the off value is INFERRED"))
        assertTrue(text.contains("not the same as reverted BEHAVIOUR"))
        assertTrue(text.contains("does not restore a snapshot"))
        assertTrue(text.contains("disable_pip_r26_packets inverts"))
        for (key in r.keys) assertTrue("$key missing from the report", text.contains(key))
    }

    @Test
    fun probeKeyIsTheOneWithAHardwareWriteDemonstration() {
        assertEquals("enable_sig12", R22DisableReport.PROBE_KEY)
        assertTrue(Whoop5Config.enableR22Sequence.any { it.name == R22DisableReport.PROBE_KEY })
    }

    /** Cross-platform parity: the Swift and Kotlin reports must render the same verdict labels, or a
     *  shared strap log reads differently on the two platforms. */
    @Test
    fun verdictAndOutcomeLabelsMatchTheSwiftSpelling() {
        assertEquals("allCleared", R22DisableReport.Verdict.ALL_CLEARED.label)
        assertEquals("partial", R22DisableReport.Verdict.PARTIAL.label)
        assertEquals("offValueRejected", R22DisableReport.Verdict.OFF_VALUE_REJECTED.label)
        assertEquals("probeInconclusive", R22DisableReport.Verdict.PROBE_INCONCLUSIVE.label)
        assertEquals("abandoned", R22DisableReport.Verdict.ABANDONED.label)
        assertEquals("cleared", R22DisableReport.KeyOutcome.CLEARED.label)
        assertEquals("unset", R22DisableReport.KeyOutcome.UNSET.label)
        assertEquals("unchanged", R22DisableReport.KeyOutcome.UNCHANGED.label)
        assertEquals("notClaimed", R22DisableReport.KeyOutcome.NOT_CLAIMED.label)
        assertEquals("skipped", R22DisableReport.KeyOutcome.SKIPPED.label)
    }
}
