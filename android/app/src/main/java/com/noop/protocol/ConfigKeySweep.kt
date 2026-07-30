package com.noop.protocol

/**
 * #103: the DEVICE-CONFIG ENUMERATION verbs (115/116), the key-existence ORACLE that #890's read verbs
 * turned out to be, and the candidate-name catalogue the sweep falls back to when enumeration is refused.
 *
 * Kotlin twin of Swift `ConfigKeySweep` (`Packages/WhoopProtocol/…/ConfigKeySweep.swift`). The catalogue,
 * the batching arithmetic and the oracle mapping are byte-identical across platforms; unit tests on both
 * sides assert it.
 *
 * ## 1. Enumerate. Only guess what enumeration cannot reach.
 *
 * This repo's own protocol table (`whoop_protocol.json`, [CommandNumber]) names two symmetric config
 * namespaces, four verbs each:
 *
 * ```
 * feature-flag    117 START_FF_KEY_EXCHANGE            118 SEND_NEXT_FF
 *                 120 SET_FF_VALUE                     128 GET_FF_VALUE
 * device-config   115 START_DEVICE_CONFIG_KEY_EXCHANGE 116 SEND_NEXT_DEVICE_CONFIG
 *                 119 SET_DEVICE_CONFIG_VALUE          121 GET_DEVICE_CONFIG_VALUE
 * ```
 *
 * #872 built the feature-flag enumerate pair (117/118) and a WHOOP 5 MG answered it, listing the sixteen
 * keys in [Whoop5Config.enableR22Sequence]. #890 built both VALUE reads (121/128) and the same strap
 * answered those too. **The device-config enumerate pair — 115/116 — has never been sent by anything
 * here**, and it is the structural twin of the pair that already works.
 *
 * That matters more than any amount of name-guessing: if 115/116 answer, the strap simply hands over its
 * own device-config key list. No dictionary, no morphology, no oracle sweep. So this probe asks first and
 * guesses second, and the candidate catalogue below exists only for the case where it is refused.
 *
 * The 115/116 record layouts are ASSUMED to match their 117/118 twins ([FeatureFlagProbe.parseStart] /
 * [FeatureFlagProbe.parseNext], parameterised by opcode). That is an inference from the naming symmetry in
 * this repo's own table, not an observation, and it fails closed: a layout mismatch surfaces as a short
 * record or an implausible count and retires the walk with a named reason rather than inventing key names.
 *
 * ## 2. The oracle
 *
 * On a WHOOP 5 MG (WS50_r03) the 121/128 reads answer differently for a key name the firmware knows and
 * one it does not: `SUCCESS(1)` means the key EXISTS in that namespace, `FAILURE(0)` means the firmware
 * has no key by that name. One round-trip is therefore a key-existence test — read-only, cheap, and
 * decisive. [Existence] is that mapping and it is the ONLY thing the sweep concludes from a reply:
 * `UNSUPPORTED(3)`, anything else, and the unlabelled result byte on WHOOP 4.0 all stay
 * [Existence.INCONCLUSIVE] rather than being folded into either answer.
 *
 * ## 3. How the fallback candidates were DERIVED
 *
 * Not by free association. Every name is a cross-product of two things already in this repo.
 *
 * **A — morphology**, the templates the seventeen CONFIRMED key names follow (the sixteen in
 * [Whoop5Config.enableR22Sequence], plus `whoop_live_hr_in_adv_ind_pkt`, the Broadcast-HR key NOOP has
 * written since #181 and which #890 uses as its known-good device-config control):
 *
 * ```
 * T1  enable_<subsystem>_packets          enable_r22_packets
 * T2  enable_<rev>_v<N>_packets           enable_r22_v2_packets … enable_r22_v8_packets
 * T3  disable_<subsystem>_<rev>_packets   disable_pip_r26_packets
 * T4  make_<subsystem>_visible            make_hrfm_visible
 * T5  <signal>_<domain>_switching         hr_ch_switching, ir_hw_switching
 * T6  <thing>_detect_bias                 wear_detect_bias
 * T7  enable_<feature>_gen5               enable_passive_strap_fit_gen5
 * T8  enable_sig<N>[_during_sleep]        enable_sig11_during_sleep, enable_sig12
 * T9  <codename>_inhibit_<abbrev>         dorset_inhibit_wpt
 * T10 whoop_<metric>_in_<transport>       whoop_live_hr_in_adv_ind_pkt   (device-config namespace)
 * ```
 *
 * **B — vocabulary**, the subsystem and revision tokens the firmware uses about itself. Two in-repo
 * sources, no others:
 *
 * - the [CommandNumber] table: `optical` (107 `ENABLE_OPTICAL_DATA`, 108 `TOGGLE_OPTICAL_MODE`),
 *   `labrador` (124 `TOGGLE_LABRADOR_DATA_GENERATION`, 125 `TOGGLE_LABRADOR_RAW_SAVE`, 139
 *   `TOGGLE_LABRADOR_FILTERED`), `research` (131/132), `afe` (61/62), `led`+`drive` (39/40), `raw`
 *   (81/82), and the revision tokens `r7` (16 `TOGGLE_R7_DATA_COLLECTION`), `r10`/`r11` (63
 *   `SEND_R10_R11_REALTIME`), `r20`/`r21` (153/154 `TOGGLE_PERSISTENT_R20`/`_R21`);
 * - the strap's own plaintext console log, whose subsystem tags this package already documents:
 *   `SENSORS: AFE configuration changed`, and — directly on point —
 *   **`SIGPROC: generated a valid SPO2 during sleep`**. That single line pairs the firmware's SpO2
 *   computation with the `SIGPROC` tag and with the exact `during sleep` phrasing the confirmed key
 *   `enable_sig11_during_sleep` uses, which is the strongest in-repo reason to think the `sig<N>` series
 *   is where an oxygen gate would live.
 *
 * Every candidate is (template × token). None is a product name invented in English — which matters,
 * because the eight plain-English oxygen names in [RETIRED_KEYS] were all asked of a real WHOOP 5 MG and
 * all came back FAILURE.
 *
 * **They are still guesses.** A candidate is a question, not a claim; the answer is [Existence], and a
 * fully-negative sweep rules out a whole family of names, which is a publishable result.
 */
object ConfigKeySweep {

    /**
     * `START_DEVICE_CONFIG_KEY_EXCHANGE` (115 / 0x73) — ask the strap how many device-config keys it
     * knows. Read-only, and the structural twin of `START_FF_KEY_EXCHANGE` (117), which #872 shipped and a
     * real strap answered. Named in this repo's `CommandNumber` table; never before sent by NOOP.
     */
    const val START_DEVICE_CONFIG_KEY_EXCHANGE_CMD = 115

    /**
     * `SEND_NEXT_DEVICE_CONFIG` (116 / 0x74) — advance the strap's own cursor and report one key name.
     * Read-only; the twin of `SEND_NEXT_FF` (118). Like 118 it carries a CURSOR, not an index: the same
     * body is sent repeatedly and the strap walks its own list.
     */
    const val SEND_NEXT_DEVICE_CONFIG_CMD = 116

    /** Request body for both enumeration commands: the inner b3 byte `0x01`. */
    val ENUMERATION_REQUEST_BODY: ByteArray get() = byteArrayOf(0x01)

    /**
     * Hard ceiling on 116 round-trips in one probe, independent of the count the strap announces. A
     * firmware that answers with a nonsense count — or never advances its own cursor — must not be able to
     * drive an unbounded write loop on the command characteristic.
     */
    const val MAX_ENUMERATION_STEPS = 40

    /**
     * Ceiling on how many enumerated device-config key names the probe then reads VALUES for, so a long
     * key list cannot spend the whole step budget.
     */
    const val MAX_ENUMERATED_VALUE_READS = 40

    /**
     * What one 121/128 reply says about whether the key NAME exists. The result code, not the value, is
     * the signal — that is what makes a name sweep possible at all.
     *
     * Confirmed on a WHOOP 5 MG (WS50_r03): a key the firmware knows answers `SUCCESS(1)` and carries a
     * value byte; a name it does not know answers `FAILURE(0)`. Every other code — including
     * `UNSUPPORTED(3)`, and the result byte on WHOOP 4.0 where this codebase has never pinned its
     * meaning — is [INCONCLUSIVE] rather than being coerced into an answer.
     */
    enum class Existence(val label: String) {
        /** `result = SUCCESS(1)`. The firmware has this key. */
        EXISTS("exists"),

        /** `result = FAILURE(0)`. The firmware has no key by this name. */
        UNKNOWN("unknown"),

        /** Any other result code, or none at all. Says nothing either way. */
        INCONCLUSIVE("inconclusive"),
    }

    /**
     * Map a 5/MG result code onto the oracle. `null` — WHOOP 4.0, where this codebase has not established
     * the byte's meaning — is [Existence.INCONCLUSIVE], never [Existence.UNKNOWN].
     */
    fun existence(resultCode: Int?): Existence = when (resultCode) {
        1 -> Existence.EXISTS
        0 -> Existence.UNKNOWN
        else -> Existence.INCONCLUSIVE
    }

    /**
     * Which namespace a candidate is asked through. The two are separate: 117/118 enumerated the sixteen
     * R22 flags and nothing else, while the Broadcast-HR key `whoop_live_hr_in_adv_ind_pkt` (#181) is a
     * device-config key and is not among them. The probe's cross-namespace step can override this at run
     * time if one verb turns out to serve both.
     */
    enum class Namespace { FEATURE_FLAG, DEVICE_CONFIG }

    /**
     * Which derivation produced a candidate. Groups the report, and — more usefully — lets a negative
     * sweep rule out a whole FAMILY of names rather than just a list of strings. [title] states the
     * derivation so a strap log pasted into an issue explains where the names came from.
     */
    enum class Derivation(val title: String) {
        SIG_SERIES(
            "sig<N> series (T8) — the firmware numbers its signal chains; sig11/sig12 are the two we have",
        ),
        R22_VERSION_GAPS(
            "r22 version gaps (T2) — NOOP writes v2…v6 and v8; v7 and v1 are absent from an otherwise contiguous run",
        ),
        REVISION_SLOT(
            "revision slot (T1/T3) — r7/r10/r11/r20/r21 are named in this repo's own CommandNumber table",
        ),
        OPTICAL_AFE(
            "optical + AFE (T1/T4/T5) — 107 ENABLE_OPTICAL_DATA, 108 TOGGLE_OPTICAL_MODE, 61/62 AFE_PARAMETERS",
        ),
        LABRADOR_ECG(
            "labrador / ECG (T1/T4) — 124/125/139 name LABRADOR in this repo's CommandNumber table",
        ),
        RESEARCH_HIGH_RATE(
            "research + high-rate (T1/T4) — 131/132 RESEARCH_PACKET, 81/82 RAW_DATA, and hrfm from make_hrfm_visible",
        ),
        SIGPROC_OXYGEN(
            "SIGPROC + oxygen (T1/T4/T5/T7) — the strap's console log says \"SIGPROC: generated a valid SPO2 during sleep\"",
        ),
        DEVICE_CONFIG_NAMESPACE(
            "device-config namespace (T10) — the whoop_<metric>_in_<transport> shape of the one key we know",
        ),
    }

    /**
     * One candidate key name: a QUESTION for the oracle, with the derivation that produced it and the
     * namespace it is asked through. None has been observed on a wire, in a capture, or in any table.
     */
    data class Candidate(
        val key: String,
        val derivation: Derivation,
        val namespace: Namespace = Namespace.FEATURE_FLAG,
    )

    /** Build one derivation's candidates without repeating it on every line. */
    private fun names(
        derivation: Derivation,
        namespace: Namespace,
        keys: List<String>,
    ): List<Candidate> = keys.map { Candidate(it, derivation, namespace) }

    /**
     * **The candidate catalogue — the one place to add a name.** Every entry is (template × token); both
     * lists are in this file's doc comment. Order is the sweep order, strongest derivation first. Keep in
     * lockstep with the Swift `ConfigKeySweep.catalogue`.
     */
    val CATALOGUE: List<Candidate> =
        // T8. sig11 and sig12 are the only members of this series anyone here has seen, and the strap's own
        // console tag SIGPROC — the tag on the line "generated a valid SPO2 during sleep" — is the likeliest
        // expansion of "sig". A contiguous walk of the number line needs no guessing at all: it asks which
        // N exist. Crossing the survivors with qualifiers is a cheap second pass once the line is known.
        names(
            Derivation.SIG_SERIES,
            Namespace.FEATURE_FLAG,
            listOf(
                "enable_sig1", "enable_sig2", "enable_sig3", "enable_sig4", "enable_sig5",
                "enable_sig6", "enable_sig7", "enable_sig8", "enable_sig9", "enable_sig10",
                "enable_sig13", "enable_sig14", "enable_sig15", "enable_sig16",
                // The two qualifier swaps on the pair we do have.
                "enable_sig11", "enable_sig12_during_sleep",
            ),
        ) +
            // T2. The series NOOP writes is v2, v3, v4, v5, v6, v8 — v7 is MISSING from an otherwise
            // contiguous run, and there is no v1. Interpolating a hole in an OBSERVED series is the cheapest
            // possible test that the oracle finds keys NOOP does not already know: if v7 answers SUCCESS,
            // the method is proven on the first run and every other family becomes worth extending.
            names(
                Derivation.R22_VERSION_GAPS,
                Namespace.FEATURE_FLAG,
                listOf(
                    "enable_r22_v1_packets", "enable_r22_v7_packets",
                    "enable_r22_v9_packets", "enable_r22_v10_packets",
                ),
            ) +
            // T1/T3. The revision slot. r22 and r26 appear in the confirmed keys; r7, r10, r11, r20 and r21
            // appear as revision tokens in this repo's own CommandNumber table. r16 and r17 fill the gap
            // between the two attested clusters, and are the pair worth settling either way.
            names(
                Derivation.REVISION_SLOT,
                Namespace.FEATURE_FLAG,
                listOf(
                    "enable_r7_packets", "enable_r10_packets", "enable_r11_packets",
                    "enable_r16_packets", "enable_r17_packets",
                    "enable_r20_packets", "enable_r21_packets",
                    // The polarity swap on disable_pip_r26_packets, the one T3 instance there is.
                    "enable_pip_r26_packets",
                ),
            ) +
            // T1/T4/T5. SpO2 is an optical measurement, so if a config key gates it, the firmware's optical
            // and analog-front-end vocabulary is where it would be spelled. Note 107 ENABLE_OPTICAL_DATA is
            // literally the T1 template already, which is why enable_optical_data leads.
            names(
                Derivation.OPTICAL_AFE,
                Namespace.FEATURE_FLAG,
                listOf(
                    "enable_optical_data", "enable_optical_packets", "make_optical_visible",
                    "enable_afe_packets", "red_hw_switching", "green_hw_switching",
                ),
            ) +
            // T1/T4. LABRADOR is the firmware's own codename for a data path this repo's CommandNumber
            // table gives three verbs (124 data generation, 125 raw save, 139 filtered) and which NOOP has
            // never enabled. Its DATA_GENERATION / RAW_SAVE / FILTERED triad mirrors the ECG family's shape.
            names(
                Derivation.LABRADOR_ECG,
                Namespace.FEATURE_FLAG,
                listOf(
                    "enable_labrador_packets", "enable_labrador_raw_save", "enable_labrador_filtered",
                    "make_labrador_visible", "enable_ecg_packets",
                ),
            ) +
            // T1/T4. The research and high-rate paths: 131/132 SET/GET_RESEARCH_PACKET, 81/82
            // START/STOP_RAW_DATA, and `hrfm`, which the confirmed key make_hrfm_visible already names.
            names(
                Derivation.RESEARCH_HIGH_RATE,
                Namespace.FEATURE_FLAG,
                listOf(
                    "enable_research_packets", "make_research_visible",
                    "enable_raw_packets", "enable_hrfm_packets",
                ),
            ) +
            // T1/T4/T5/T7, plus the console tag. The eight plain-English oxygen names in RETIRED_KEYS all
            // returned FAILURE, so these deliberately do not repeat that approach: each is a CONFIRMED
            // template with `spo2` dropped into the token slot, and the last two use the strap's own
            // `SIGPROC` tag and its own "during sleep" phrasing.
            names(
                Derivation.SIGPROC_OXYGEN,
                Namespace.FEATURE_FLAG,
                listOf(
                    "make_spo2_visible", "enable_spo2_during_sleep", "enable_spo2_gen5",
                    "spo2_ch_switching", "disable_spo2_packets",
                    "enable_sigproc_spo2", "sigproc_spo2_during_sleep",
                ),
            ) +
            // T10, and the only family asked through 121 by default. The one confirmed device-config key is
            // `whoop_live_hr_in_adv_ind_pkt`: `whoop_` + a live metric + the transport it rides. Swapping
            // the metric is the most direct template swap available in that namespace — and it is the
            // namespace 115/116 would have enumerated outright, so these only get asked when enumeration
            // is refused.
            names(
                Derivation.DEVICE_CONFIG_NAMESPACE,
                Namespace.DEVICE_CONFIG,
                listOf(
                    "whoop_live_hrv_in_adv_ind_pkt", "whoop_live_spo2_in_adv_ind_pkt",
                    "whoop_live_temp_in_adv_ind_pkt", "whoop_live_ecg_in_adv_ind_pkt",
                ),
            )

    /**
     * Names ALREADY ANSWERED `FAILURE(0)` by a real WHOOP 5 MG (WS50_r03) — the firmware has no key by any
     * of them. Kept OUT of [CATALOGUE] so nobody spends round-trips re-asking, and kept here rather than
     * deleted so nobody proposes them again.
     *
     * They are also the evidence for how [CATALOGUE] is built: all eight are product English ("blood
     * oxygen", "pulse ox", "subscription"), and all eight are wrong.
     */
    val RETIRED_KEYS: List<String> = listOf(
        "enable_spo2",
        "enable_spo2_packets",
        "spo2_enable",
        "enable_blood_oxygen",
        "blood_oxygen_enable",
        "enable_pulse_ox",
        "enable_oxygen_packets",
        "spo2_subscription_enabled",
    )

    /**
     * How many candidate names one run may test. Bounds the wall clock: with the read verbs live a
     * round-trip is one BLE write plus one notification, so a whole run stays inside a couple of minutes.
     * [CATALOGUE] is smaller than this today, so every run tests all of it; the batching exists so a
     * catalogue GROWN past the budget truncates VISIBLY and resumably instead of silently.
     */
    const val MAX_KEYS_PER_RUN = 64

    /** One run's slice of the catalogue. */
    data class Batch(
        /** The candidates this run may test, in order. */
        val candidates: List<Candidate>,
        /** Zero-based index of the first candidate in the slice (the report shows it 1-based). */
        val start: Int,
        /** Cursor to hand the NEXT run. Wraps to 0 once a slice reaches the end of the catalogue. */
        val nextCursor: Int,
    ) {
        /** Names in the catalogue this run does not reach. */
        val remaining: Int get() = CATALOGUE.size - start - candidates.size

        /** True when this slice ends at the end of the catalogue. */
        val completesCatalogue: Boolean get() = remaining == 0
    }

    /**
     * The slice to test starting at [cursor]. A cursor outside the catalogue — negative, or left over from
     * a longer catalogue — restarts at 0 rather than wasting a run. A slice never wraps mid-batch: it
     * stops at the end and hands back 0, so no name is asked twice in one run.
     *
     * [limit] defaults to [MAX_KEYS_PER_RUN] and is a parameter only so tests can exercise the
     * truncate-and-resume path today, while the catalogue is still smaller than one run's budget.
     */
    fun batch(cursor: Int, limit: Int = MAX_KEYS_PER_RUN): Batch {
        if (CATALOGUE.isEmpty() || limit <= 0) return Batch(emptyList(), 0, 0)
        val start = if (cursor < 0 || cursor >= CATALOGUE.size) 0 else cursor
        val end = minOf(start + limit, CATALOGUE.size)
        return Batch(CATALOGUE.subList(start, end).toList(), start, if (end >= CATALOGUE.size) 0 else end)
    }
}
