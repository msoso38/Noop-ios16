package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #103: byte-parity twin of the Swift `ConfigKeySweepTests` — the key-existence ORACLE, the candidate
 * catalogue's derivation discipline, and the batching arithmetic that makes a catalogue larger than one
 * run's budget truncate visibly instead of silently.
 *
 * The catalogue is data, so these tests are about its INVARIANTS — no duplicates, nothing already known,
 * nothing already ruled out, nothing that cannot survive the 32-byte wire field — plus a golden string
 * the Swift twin asserts in the same shape so the two lists cannot drift.
 */
class ConfigKeySweepTest {

    private val flagKeys: List<String> get() = Whoop5Config.enableR22Sequence.map { it.name }

    // ---- The oracle ----

    @Test
    fun oracleReadsTheResultCodeAndNothingElse() {
        assertEquals(ConfigKeySweep.Existence.EXISTS, ConfigKeySweep.existence(1))        // SUCCESS
        assertEquals(ConfigKeySweep.Existence.UNKNOWN, ConfigKeySweep.existence(0))       // FAILURE
        assertEquals(ConfigKeySweep.Existence.INCONCLUSIVE, ConfigKeySweep.existence(2))  // PENDING
        assertEquals(ConfigKeySweep.Existence.INCONCLUSIVE, ConfigKeySweep.existence(3))  // UNSUPPORTED
        assertEquals(ConfigKeySweep.Existence.INCONCLUSIVE, ConfigKeySweep.existence(9))
    }

    /** WHOOP 4.0 carries no labelled result code here, so the oracle must decline rather than read the
     *  absence as "the key does not exist". */
    @Test
    fun absentResultCodeIsInconclusiveNotUnknown() {
        assertEquals(ConfigKeySweep.Existence.INCONCLUSIVE, ConfigKeySweep.existence(null))
        assertNotEquals(ConfigKeySweep.Existence.UNKNOWN, ConfigKeySweep.existence(null))
    }

    @Test
    fun existenceLabelsAreStableAcrossPlatforms() {
        assertEquals("exists", ConfigKeySweep.Existence.EXISTS.label)
        assertEquals("unknown", ConfigKeySweep.Existence.UNKNOWN.label)
        assertEquals("inconclusive", ConfigKeySweep.Existence.INCONCLUSIVE.label)
    }

    // ---- Enumeration verbs ----

    @Test
    fun enumerationOpcodesAreTheDeviceConfigPair() {
        assertEquals(115, ConfigKeySweep.START_DEVICE_CONFIG_KEY_EXCHANGE_CMD)   // 0x73
        assertEquals(116, ConfigKeySweep.SEND_NEXT_DEVICE_CONFIG_CMD)            // 0x74
        // The body is the bare inner b3 byte, exactly as the 117/118 pair sends it.
        assertTrue(byteArrayOf(0x01).contentEquals(ConfigKeySweep.ENUMERATION_REQUEST_BODY))
    }

    // ---- Catalogue invariants ----

    @Test
    fun catalogueHasNoDuplicateNames() {
        val keys = ConfigKeySweep.CATALOGUE.map { it.key }
        assertEquals("a duplicate spends a round-trip for no information", keys.size, keys.toSet().size)
    }

    /** A candidate that NOOP already writes is not a candidate — it is a known key, and asking it in the
     *  candidate phase would inflate an "exists" count with something the probe already knew. */
    @Test
    fun catalogueNeverRepeatsAKeyNoopAlreadyWrites() {
        for (c in ConfigKeySweep.CATALOGUE) {
            assertFalse("${c.key} is already in enableR22Sequence", flagKeys.contains(c.key))
            assertNotEquals(DeviceConfigReadProbe.DEVICE_CONFIG_DISCOVERY_KEY, c.key)
        }
    }

    /** The eight plain-English oxygen names already came back FAILURE on a real strap. Re-asking them
     *  would spend round-trips to re-learn a known negative. */
    @Test
    fun catalogueNeverRepeatsARetiredName() {
        for (c in ConfigKeySweep.CATALOGUE) {
            assertFalse(
                "${c.key} already answered FAILURE — it belongs in RETIRED_KEYS, not the catalogue",
                ConfigKeySweep.RETIRED_KEYS.contains(c.key),
            )
        }
        assertEquals(ConfigKeySweep.RETIRED_KEYS.size, ConfigKeySweep.RETIRED_KEYS.toSet().size)
    }

    /** Names are TRUNCATED to 32 bytes on the wire, not rejected, so two candidates sharing a 32-byte
     *  prefix would be indistinguishable — and a longer name could never match anyway. */
    @Test
    fun everyCandidateFitsTheWireNameField() {
        for (c in ConfigKeySweep.CATALOGUE) {
            val bytes = c.key.toByteArray(Charsets.UTF_8)
            assertTrue("${c.key} is too long", bytes.size <= DeviceConfigReadProbe.NAME_FIELD_BYTES)
            assertTrue(bytes.isNotEmpty())
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                assertTrue(
                    "${c.key} is not lowercase snake_case, which every confirmed key is",
                    v in 97..122 || v in 48..57 || v == 95,
                )
            }
        }
    }

    /** The derivation is the product: a candidate whose family is unexplained is a guess with no argument
     *  behind it, and a negative result on it rules nothing out. */
    @Test
    fun everyDerivationIsUsedAndTitled() {
        for (d in ConfigKeySweep.Derivation.entries) {
            assertTrue(
                "${d.name} has a title but no candidates",
                ConfigKeySweep.CATALOGUE.any { it.derivation == d },
            )
            assertTrue(d.title.isNotEmpty())
        }
    }

    /** Only the `whoop_…` family belongs to the device-config namespace — that prefix is the one shape a
     *  confirmed device-config key has. */
    @Test
    fun namespaceAssignmentFollowsTheOnlyConfirmedDeviceConfigShape() {
        for (c in ConfigKeySweep.CATALOGUE) {
            if (c.namespace == ConfigKeySweep.Namespace.DEVICE_CONFIG) {
                assertTrue("${c.key} is asked of 121 but is not whoop_-shaped", c.key.startsWith("whoop_"))
            } else {
                assertFalse("${c.key} is whoop_-shaped but asked of 128", c.key.startsWith("whoop_"))
            }
        }
    }

    /** The single highest-value entry: v7 is the hole in an OBSERVED contiguous series, so a SUCCESS on it
     *  would prove the oracle finds keys NOOP does not already know. */
    @Test
    fun theObservedSeriesHoleIsInTheCatalogue() {
        val keys = ConfigKeySweep.CATALOGUE.map { it.key }
        assertTrue(keys.contains("enable_r22_v7_packets"))
        assertFalse(flagKeys.contains("enable_r22_v7_packets"))
        assertTrue(flagKeys.contains("enable_r22_v6_packets"))
        assertTrue(flagKeys.contains("enable_r22_v8_packets"))
    }

    // ---- Batching ----

    @Test
    fun todaysCatalogueFitsInOneRun() {
        assertTrue(ConfigKeySweep.CATALOGUE.size <= ConfigKeySweep.MAX_KEYS_PER_RUN)
        val b = ConfigKeySweep.batch(0)
        assertEquals(0, b.start)
        assertEquals(ConfigKeySweep.CATALOGUE.size, b.candidates.size)
        assertEquals(0, b.remaining)
        assertTrue(b.completesCatalogue)
        assertEquals("a completed catalogue restarts the next run at the top", 0, b.nextCursor)
    }

    /** The property the sweep exists to guarantee: a catalogue larger than one run's budget is truncated
     *  VISIBLY (`remaining` is non-zero) and resumed from `nextCursor`, never silently cut. */
    @Test
    fun anOversizeCatalogueTruncatesVisiblyAndResumes() {
        val total = ConfigKeySweep.CATALOGUE.size
        val first = ConfigKeySweep.batch(0, 10)
        assertEquals(10, first.candidates.size)
        assertEquals(0, first.start)
        assertEquals(total - 10, first.remaining)
        assertFalse(first.completesCatalogue)
        assertEquals(10, first.nextCursor)

        val second = ConfigKeySweep.batch(first.nextCursor, 10)
        assertEquals(10, second.start)
        assertEquals(ConfigKeySweep.CATALOGUE[10].key, second.candidates.first().key)
        assertEquals(total - 20, second.remaining)

        val seen = mutableListOf<String>()
        var cursor = 0
        do {
            val b = ConfigKeySweep.batch(cursor, 10)
            seen.addAll(b.candidates.map { it.key })
            cursor = b.nextCursor
        } while (cursor != 0)
        assertEquals(ConfigKeySweep.CATALOGUE.map { it.key }, seen)
    }

    @Test
    fun aStaleOrNonsenseCursorRestartsRatherThanWastingARun() {
        assertEquals(0, ConfigKeySweep.batch(-1).start)
        assertEquals(0, ConfigKeySweep.batch(10_000).start)
        assertEquals(0, ConfigKeySweep.batch(ConfigKeySweep.CATALOGUE.size).start)
        assertTrue(ConfigKeySweep.batch(-1).candidates.isNotEmpty())
    }

    /** A slice never wraps mid-batch, so one run can never ask the same name twice. */
    @Test
    fun aSliceNeverWrapsWithinOneRun() {
        val b = ConfigKeySweep.batch(ConfigKeySweep.CATALOGUE.size - 3, 10)
        assertEquals(3, b.candidates.size)
        assertEquals(0, b.nextCursor)
        assertEquals(b.candidates.size, b.candidates.map { it.key }.toSet().size)
    }

    // ---- Cross-platform lockstep ----

    /** The catalogue is duplicated in Swift by hand, so pin it as one string the Swift twin asserts in the
     *  same shape. A name added on one platform and not the other fails HERE, not on a user's strap. */
    @Test
    fun catalogueIsPinnedForTheSwiftTwin() {
        val pinned = ConfigKeySweep.CATALOGUE.joinToString("\n") {
            "${it.derivation.name}:${it.namespace.name}:${it.key}"
        }
        assertEquals(GOLDEN_CATALOGUE, pinned)
        assertEquals(54, ConfigKeySweep.CATALOGUE.size)
        assertEquals(GOLDEN_RETIRED, ConfigKeySweep.RETIRED_KEYS.joinToString("\n"))
    }

    private companion object {
        const val GOLDEN_CATALOGUE = """SIG_SERIES:FEATURE_FLAG:enable_sig1
SIG_SERIES:FEATURE_FLAG:enable_sig2
SIG_SERIES:FEATURE_FLAG:enable_sig3
SIG_SERIES:FEATURE_FLAG:enable_sig4
SIG_SERIES:FEATURE_FLAG:enable_sig5
SIG_SERIES:FEATURE_FLAG:enable_sig6
SIG_SERIES:FEATURE_FLAG:enable_sig7
SIG_SERIES:FEATURE_FLAG:enable_sig8
SIG_SERIES:FEATURE_FLAG:enable_sig9
SIG_SERIES:FEATURE_FLAG:enable_sig10
SIG_SERIES:FEATURE_FLAG:enable_sig13
SIG_SERIES:FEATURE_FLAG:enable_sig14
SIG_SERIES:FEATURE_FLAG:enable_sig15
SIG_SERIES:FEATURE_FLAG:enable_sig16
SIG_SERIES:FEATURE_FLAG:enable_sig11
SIG_SERIES:FEATURE_FLAG:enable_sig12_during_sleep
R22_VERSION_GAPS:FEATURE_FLAG:enable_r22_v1_packets
R22_VERSION_GAPS:FEATURE_FLAG:enable_r22_v7_packets
R22_VERSION_GAPS:FEATURE_FLAG:enable_r22_v9_packets
R22_VERSION_GAPS:FEATURE_FLAG:enable_r22_v10_packets
REVISION_SLOT:FEATURE_FLAG:enable_r7_packets
REVISION_SLOT:FEATURE_FLAG:enable_r10_packets
REVISION_SLOT:FEATURE_FLAG:enable_r11_packets
REVISION_SLOT:FEATURE_FLAG:enable_r16_packets
REVISION_SLOT:FEATURE_FLAG:enable_r17_packets
REVISION_SLOT:FEATURE_FLAG:enable_r20_packets
REVISION_SLOT:FEATURE_FLAG:enable_r21_packets
REVISION_SLOT:FEATURE_FLAG:enable_pip_r26_packets
OPTICAL_AFE:FEATURE_FLAG:enable_optical_data
OPTICAL_AFE:FEATURE_FLAG:enable_optical_packets
OPTICAL_AFE:FEATURE_FLAG:make_optical_visible
OPTICAL_AFE:FEATURE_FLAG:enable_afe_packets
OPTICAL_AFE:FEATURE_FLAG:red_hw_switching
OPTICAL_AFE:FEATURE_FLAG:green_hw_switching
LABRADOR_ECG:FEATURE_FLAG:enable_labrador_packets
LABRADOR_ECG:FEATURE_FLAG:enable_labrador_raw_save
LABRADOR_ECG:FEATURE_FLAG:enable_labrador_filtered
LABRADOR_ECG:FEATURE_FLAG:make_labrador_visible
LABRADOR_ECG:FEATURE_FLAG:enable_ecg_packets
RESEARCH_HIGH_RATE:FEATURE_FLAG:enable_research_packets
RESEARCH_HIGH_RATE:FEATURE_FLAG:make_research_visible
RESEARCH_HIGH_RATE:FEATURE_FLAG:enable_raw_packets
RESEARCH_HIGH_RATE:FEATURE_FLAG:enable_hrfm_packets
SIGPROC_OXYGEN:FEATURE_FLAG:make_spo2_visible
SIGPROC_OXYGEN:FEATURE_FLAG:enable_spo2_during_sleep
SIGPROC_OXYGEN:FEATURE_FLAG:enable_spo2_gen5
SIGPROC_OXYGEN:FEATURE_FLAG:spo2_ch_switching
SIGPROC_OXYGEN:FEATURE_FLAG:disable_spo2_packets
SIGPROC_OXYGEN:FEATURE_FLAG:enable_sigproc_spo2
SIGPROC_OXYGEN:FEATURE_FLAG:sigproc_spo2_during_sleep
DEVICE_CONFIG_NAMESPACE:DEVICE_CONFIG:whoop_live_hrv_in_adv_ind_pkt
DEVICE_CONFIG_NAMESPACE:DEVICE_CONFIG:whoop_live_spo2_in_adv_ind_pkt
DEVICE_CONFIG_NAMESPACE:DEVICE_CONFIG:whoop_live_temp_in_adv_ind_pkt
DEVICE_CONFIG_NAMESPACE:DEVICE_CONFIG:whoop_live_ecg_in_adv_ind_pkt"""

        const val GOLDEN_RETIRED = """enable_spo2
enable_spo2_packets
spo2_enable
enable_blood_oxygen
blood_oxygen_enable
enable_pulse_ox
enable_oxygen_packets
spo2_subscription_enabled"""
    }
}
