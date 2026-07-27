package com.noop.protocol

/**
 * #103: READ-ONLY probe of the two config **read** verbs — `GET_DEVICE_CONFIG_VALUE` (121 / 0x79) and
 * `GET_FF_VALUE` (128 / 0x80). Kotlin twin of Swift `DeviceConfigReadProbe` /
 * `DeviceConfigReadProbeReport` (`Packages/WhoopProtocol/…/DeviceConfigReadProbe.swift`); the decoded
 * fields, the plan the probe walks and the rendered report text are byte-identical across platforms, so
 * a strap log reads the same either side.
 *
 * Follow-up to the #761 enumeration probe, which asked the strap for key NAMES; this asks for a key's
 * VALUE.
 *
 * ## Why
 *
 * NOOP writes config two ways and reads it neither way. `SET_FF_VALUE` (120 / 0x78) writes the sixteen
 * R22 feature flags in [Whoop5Config.enableR22Sequence]; `SET_DEVICE_CONFIG_VALUE` (119 / 0x77) writes
 * the one device-config key NOOP knows (the Broadcast-HR flag, #181). Those are two *different
 * namespaces* sharing a body layout — and the 117/118 enumerate pair #761 built covers the feature-flag
 * namespace ONLY. The device-config namespace has never been read or enumerated here at all.
 *
 * That matters for #103. NOOP already decodes a byte the deep record carries at offset 82 that reads as
 * real SpO2 on some straps and is flat 0x00 on others, which is what a subscription gate would look
 * like. If a config value governs it, it is far more likely to live in the device-config namespace than
 * among the sixteen flags NOOP already writes — and nobody has ever asked a strap for one.
 *
 * ## What this establishes, and the honest failure case
 *
 * Both read verbs answered on a real WHOOP 5 MG (WS50_r03), and the reply turned out to be an
 * **existence oracle**: `SUCCESS(1)` for a key name the firmware has, `FAILURE(0)` for one it does not.
 * That is what makes a key-name search possible at all, and [ConfigKeySweep] is where it lives.
 *
 * The probe now runs a plan built around one principle: **ask the strap before guessing.** It opens with
 * the DEVICE-CONFIG ENUMERATION pair `START_DEVICE_CONFIG_KEY_EXCHANGE` (115) and
 * `SEND_NEXT_DEVICE_CONFIG` (116) — named in the repo's own `CommandNumber` table, never sent by anything
 * here, and the structural twin of the 117/118 feature-flag pair #872 shipped and a strap answered. If
 * 115/116 answer, the strap lists its own device-config keys and no name needs guessing at all; the
 * candidate sweep is skipped and the report says so. A clean "115/116 are not served" is equally useful
 * and publishable — it is what promotes the guessing fallback from a shortcut to the only available
 * method.
 *
 * After enumeration the probe reads the values of keys already known to exist (the sixteen in
 * [Whoop5Config.enableR22Sequence], plus anything enumeration returned), spends two round-trips
 * establishing whether the two namespaces are actually separate, and only then — and only when
 * enumeration produced nothing — asks the guessed names in [ConfigKeySweep.CATALOGUE].
 *
 * ## Read-only by construction
 *
 * The probe writes command frames purely in order to read, the same shape as the Oura feature-status
 * probes NOOP already ships and as the #761 enumeration. The SET verbs are named in [WRITE_OPCODES] for
 * exactly one reason: so the send allowlist can be expressed as "[READ_ONLY_OPCODES] only" and a unit
 * test can prove 119/120 are rejected by it. Nothing in this file or on its BLE path can form a SET
 * frame.
 *
 * ## Wire shape (a documented guess, and it fails closed)
 *
 * Request: command 121 or 128 with body `[0x01]` — the inner b3 convention CLIENT_HELLO and the
 * SET_CONFIG family use — followed by the key name as ASCII NUL-padded to 32 bytes, the same name field
 * [Whoop5Config.payloadBody] / [Whoop5Config.deviceConfigBody] build for the SET side, minus the
 * trailing value byte a read has no reason to carry. That body shape is inferred from the SET side, not
 * observed; if it is wrong the strap answers FAILURE or nothing, which the report says plainly.
 *
 * Response: an ordinary COMMAND_RESPONSE echoing the command byte, whose record sits behind the 2-byte
 * response header (`pay[1]` is the 5/MG result code) — the same offset every other COMMAND_RESPONSE
 * decoder here uses. Beyond that offset **no field layout is assumed**: the record is kept and reported
 * as raw hex. A value is only ever *claimed* when the reply echoes the key that was asked for inside a
 * 32-byte NUL-padded name field, in which case the byte immediately after that field is reported as the
 * value — the SET side's own layout, checked rather than assumed.
 *
 * Everything fails closed: a frame whose CRCs don't verify, whose type is not COMMAND_RESPONSE, or whose
 * record is too short is REJECTED rather than guessed at.
 */
object DeviceConfigReadProbe {

    /** `GET_DEVICE_CONFIG_VALUE` (121 / 0x79) — one device-config value by key name. Read-only. */
    const val GET_DEVICE_CONFIG_VALUE_CMD = 121

    /** `GET_FF_VALUE` (128 / 0x80) — one feature-flag value by key name. Read-only. */
    const val GET_FEATURE_FLAG_VALUE_CMD = 128

    /** `SET_DEVICE_CONFIG_VALUE` (119 / 0x77). Named ONLY so the allowlist can name what it excludes. */
    const val SET_DEVICE_CONFIG_VALUE_CMD = 119

    /** `SET_FF_VALUE` (120 / 0x78). Named ONLY so the allowlist can name what it excludes. */
    const val SET_FEATURE_FLAG_VALUE_CMD = 120

    /** The complete set of opcodes this probe may put on the wire: the two VALUE reads above, plus the
     *  two DEVICE-CONFIG ENUMERATION verbs the probe now tries first ([ConfigKeySweep], 115/116 — the
     *  structural twins of the 117/118 pair #872 shipped and a real strap answered read-only). The BLE
     *  send path admits these four and only these four while a probe is in flight; [isReadOnlyOpcode] is
     *  the predicate it asks, and unit tests prove it rejects 119, 120 and every other opcode. */
    val READ_ONLY_OPCODES = setOf(
        GET_DEVICE_CONFIG_VALUE_CMD,
        GET_FEATURE_FLAG_VALUE_CMD,
        ConfigKeySweep.START_DEVICE_CONFIG_KEY_EXCHANGE_CMD,
        ConfigKeySweep.SEND_NEXT_DEVICE_CONFIG_CMD,
    )

    /** The config WRITE verbs, which this probe must never emit. Kept as a named set so the read-only
     *  contract is testable as a property of the allowlist rather than as a claim in a comment. */
    val WRITE_OPCODES = setOf(SET_DEVICE_CONFIG_VALUE_CMD, SET_FEATURE_FLAG_VALUE_CMD)

    /** The allowlist predicate itself. False for both SET verbs and for every opcode outside the pair. */
    fun isReadOnlyOpcode(opcode: Int): Boolean = READ_ONLY_OPCODES.contains(opcode)

    /** Width of the key-name field in the SET bodies (both NUL-pad the name to 32 bytes). */
    const val NAME_FIELD_BYTES = 32

    /** Hard ceiling on round-trips in one probe, independent of how many keys the plan holds. The plan is
     *  1 enumerate-start + up to [ConfigKeySweep.MAX_ENUMERATION_STEPS] enumerate-next + 2 discovery + 2
     *  cross-namespace + 16 known flags, then EITHER up to [ConfigKeySweep.MAX_ENUMERATED_VALUE_READS]
     *  value reads (when enumeration produced a list) OR up to [ConfigKeySweep.MAX_KEYS_PER_RUN] candidate
     *  names (when it did not) — never both, because guessing is pointless once the strap has handed over
     *  its own list. Worst case is 101 round-trips, comfortably under this. */
    const val MAX_STEPS = 128

    /** The one device-config key NOOP already knows a real strap accepts: the Broadcast-HR flag written
     *  via SET_DEVICE_CONFIG_VALUE and hardware-validated in #181. Used as the discovery key for opcode
     *  121 precisely because it is known-good — a FAILURE on this key is evidence about the verb. */
    const val DEVICE_CONFIG_DISCOVERY_KEY = "whoop_live_hr_in_adv_ind_pkt"

    /** COMMAND_RESPONSE packet type (36 / 0x24). */
    private const val COMMAND_RESPONSE_TYPE = 36

    /** Bytes of response header ahead of the packet record. `pay[1]` is the 5/MG result code. */
    private const val RESPONSE_HEADER_BYTES = 2

    /** Record bytes rendered in the report before the hex is elided. */
    const val MAX_HEX_BYTES = 48

    /** The request body for one read: the inner b3 byte `0x01`, then the key name as ASCII NUL-padded to
     *  [NAME_FIELD_BYTES]. A name longer than the field is truncated to it, exactly as the SET side does. */
    fun requestBody(key: String): ByteArray {
        val body = ByteArray(NAME_FIELD_BYTES + 1)
        body[0] = 0x01
        val bytes = key.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(NAME_FIELD_BYTES, bytes.size)) body[i + 1] = bytes[i]
        return body
    }

    /** Why a frame was not decoded. Named cases so the report can say what went wrong. */
    enum class ParseFailure { CRC, ENVELOPE, WRONG_COMMAND, TRUNCATED }

    /**
     * One decoded config-read reply. Deliberately shallow: the record is kept RAW because no field layout
     * for these two opcodes has ever been observed. The only structured read offered is [valueFor], and
     * it only answers when the strap echoed the key back in the SET side's own 32-byte name field.
     */
    data class ValueResponse(val resultCode: Int?, val record: ByteArray) {

        /** The firmware explicitly refused the verb — the outcome that settles "is 121/128 implemented". */
        val isUnsupported: Boolean get() = resultCode == 3

        /** The firmware answered but reported failure: the verb exists, the request did not satisfy it. */
        val isFailure: Boolean get() = resultCode == 0

        /** What this reply says about whether the key NAME exists, per the oracle a real WHOOP 5 MG
         *  established: `SUCCESS(1)` = the firmware has this key, `FAILURE(0)` = it does not, anything else
         *  (and WHOOP 4.0, where the result byte's meaning is not pinned here) = inconclusive.
         *  Deliberately reads the RESULT CODE and nothing else — no inference from the record bytes. */
        val existence: ConfigKeySweep.Existence get() = ConfigKeySweep.existence(resultCode)

        /** Raw record bytes as lowercase space-separated hex; always reported, whatever else decodes. */
        val recordHex: String get() = hex(record)

        /**
         * Where [key] appears in the record as a NUL-padded 32-byte name field, or null if it does not.
         * Requires the padding to actually be NUL so a coincidental substring can't be mistaken for the
         * name field.
         */
        fun echoOffset(key: String): Int? {
            val needle = key.toByteArray(Charsets.UTF_8)
            if (needle.isEmpty() || needle.size > NAME_FIELD_BYTES) return null
            if (record.size < NAME_FIELD_BYTES) return null
            for (start in 0..(record.size - NAME_FIELD_BYTES)) {
                var matched = true
                for (i in needle.indices) {
                    if (record[start + i] != needle[i]) { matched = false; break }
                }
                if (!matched) continue
                // The rest of the field must be NUL padding, or this is not the name field.
                var padded = true
                for (i in needle.size until NAME_FIELD_BYTES) {
                    if (record[start + i].toInt() != 0) { padded = false; break }
                }
                if (padded) return start
            }
            return null
        }

        /**
         * The value byte, reported ONLY when the strap echoed [key] in a 32-byte NUL-padded name field
         * and the record extends one byte past it — the same `[name 32][value]` layout the SET bodies
         * use. null means "no value claimed", never "value is zero".
         */
        fun valueFor(key: String): Int? {
            val off = echoOffset(key) ?: return null
            val valueIndex = off + NAME_FIELD_BYTES
            if (valueIndex >= record.size) return null
            return record[valueIndex].toInt() and 0xFF
        }

        // ByteArray fields need explicit equals/hashCode for a data class to compare by content.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ValueResponse) return false
            return resultCode == other.resultCode && record.contentEquals(other.record)
        }

        override fun hashCode(): Int = 31 * (resultCode ?: 0) + record.contentHashCode()
    }

    /** One decode outcome: exactly one of [value] / [failure] is non-null. */
    data class Parsed(val value: ValueResponse?, val failure: ParseFailure?)

    /**
     * Decode a config-read COMMAND_RESPONSE for [expecting] (121 or 128). CRC-gated: a frame whose
     * checksums fail is rejected before any field is read.
     */
    fun parse(frame: ByteArray, family: DeviceFamily, expecting: Int): Parsed {
        // BLE safety contract: CRC-gate everything. A frame that fails either checksum is not data.
        if (!Framing.frameCrcOk(frame, family)) return Parsed(null, ParseFailure.CRC)
        val typeOff = if (family == DeviceFamily.WHOOP5) 8 else 4
        val cmdOff = typeOff + 2
        if (frame.size <= cmdOff + 4) return Parsed(null, ParseFailure.ENVELOPE)
        if ((frame[typeOff].toInt() and 0xFF) != COMMAND_RESPONSE_TYPE) return Parsed(null, ParseFailure.ENVELOPE)
        if ((frame[cmdOff].toInt() and 0xFF) != expecting) return Parsed(null, ParseFailure.WRONG_COMMAND)
        val payStart = cmdOff + 1
        val payEnd = frame.size - 4       // strip the CRC32 trailer
        if (payEnd <= payStart) return Parsed(null, ParseFailure.TRUNCATED)
        val pay = frame.copyOfRange(payStart, payEnd)
        if (pay.size <= RESPONSE_HEADER_BYTES) return Parsed(null, ParseFailure.TRUNCATED)
        val resultCode = if (family == DeviceFamily.WHOOP5) pay[1].toInt() and 0xFF else null
        return Parsed(ValueResponse(resultCode, pay.copyOfRange(RESPONSE_HEADER_BYTES, pay.size)), null)
    }

    /** Lowercase space-separated hex, capped so one oversized record can't flood the report. */
    fun hex(bytes: ByteArray): String {
        val shown = if (bytes.size > MAX_HEX_BYTES) bytes.copyOfRange(0, MAX_HEX_BYTES) else bytes
        var out = shown.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        if (bytes.size > MAX_HEX_BYTES) out += " … (${bytes.size} bytes)"
        return if (out.isEmpty()) "(empty)" else out
    }

    /** Pad [s] on the right to [width], used instead of a printf `%-Ns` so Swift and Kotlin render the
     *  report identically. */
    fun padded(s: String, width: Int): String =
        if (s.length >= width) s else s + " ".repeat(width - s.length)

    /** A value byte rendered as both its character (when printable) and its number. */
    fun valueLabel(v: Int): String =
        if (v in 32..126) "'${v.toChar()}' (0x${"%02x".format(v)})" else "0x${"%02x".format(v)}"
}

/**
 * The running result of one config probe: the strap's own device-config key list when it will give one,
 * the per-verb verdict, the values read, the candidate sweep when guessing is still necessary, and the
 * copyable transcript. Pure and order-dependent (`nextStep` → `note…` → `nextStep` → …). Twin of Swift
 * `DeviceConfigReadProbeReport`; [render] is byte-identical across platforms.
 *
 * The plan is ordered so the cheapest decisive question is asked first:
 *
 * 1. **ENUMERATE** — 115 then repeated 116. If the strap answers, it has just listed its own
 *    device-config keys and no name needs guessing.
 * 2. **DISCOVERY** — one 128 read and one 121 read, each against a key that verb should know.
 * 3. **CROSS_NAMESPACE** — ask each verb for the OTHER namespace's known key. Settles in two round-trips
 *    whether the namespaces are really separate.
 * 4. **KNOWN_KEY** — read the values of the sixteen flags NOOP writes, plus anything enumeration produced.
 * 5. **CANDIDATE** — the guessed-name sweep, and **only when enumeration produced no list**.
 */
class DeviceConfigReadProbeReport(
    private val family: DeviceFamily,
    /** The flag names whose values to read — supplied by the caller from [Whoop5Config.enableR22Sequence]
     *  so this file never restates them. */
    private val knownFlagKeys: List<String>,
    /** This run's slice of the candidate catalogue, and the cursor to hand the next run. */
    val batch: ConfigKeySweep.Batch,
) {

    /** Which part of the plan a step belongs to. Drives both the ordering and the report's sections. */
    enum class Group { ENUMERATE, DISCOVERY, CROSS_NAMESPACE, KNOWN_KEY, CANDIDATE }

    /** One planned round-trip. [derivation] is set only for candidate steps. */
    data class Step(
        val opcode: Int,
        val key: String,
        val group: Group,
        val derivation: ConfigKeySweep.Derivation? = null,
    )

    /** What one verb has been shown to do. `UNTRIED` until its first step resolves. */
    enum class VerbStatus(val label: String) {
        UNTRIED("untried"),

        /** A decodable COMMAND_RESPONSE came back and was not an explicit UNSUPPORTED. */
        ANSWERED("answered"),

        /** The firmware refused the opcode (5/MG result code 3). */
        UNSUPPORTED("unsupported"),

        /** No reply inside the probe's per-step window. */
        SILENT("silent"),

        /** A reply arrived but could not be decoded (CRC, envelope, or a short record). */
        UNDECODABLE("undecodable"),
    }

    /** One value read (or attempted) for one key. */
    data class Reading(
        val group: Group,
        val opcode: Int,
        val key: String,
        /** The value byte, only when the strap echoed the key in a 32-byte name field. */
        val value: Int?,
        val resultCode: Int?,
        val recordHex: String,
        val derivation: ConfigKeySweep.Derivation? = null,
    ) {
        /** The oracle's verdict on whether this key NAME exists. */
        val existence: ConfigKeySweep.Existence get() = ConfigKeySweep.existence(resultCode)
    }

    /** Status of `GET_FF_VALUE` (128). */
    var featureFlagVerb: VerbStatus = VerbStatus.UNTRIED
        private set

    /** Status of `GET_DEVICE_CONFIG_VALUE` (121). */
    var deviceConfigVerb: VerbStatus = VerbStatus.UNTRIED
        private set

    /** Status of the device-config ENUMERATION pair (115/116), taken as one verb: 116 cannot be asked
     *  without 115 having answered, so a single verdict describes the pair. */
    var enumerationVerb: VerbStatus = VerbStatus.UNTRIED
        private set

    private val _enumeratedKeys = mutableListOf<String>()

    /** Device-config key names the strap listed for itself. The headline result when it is non-empty. */
    val enumeratedKeys: List<String> get() = _enumeratedKeys

    /** The key count `START_DEVICE_CONFIG_KEY_EXCHANGE` announced, when it answered. */
    var enumeratedCount: Int? = null
        private set

    /** Entries the strap called real keys whose NAME did not decode, stepped over rather than trusted as a
     *  terminator (the discipline #874 established for the 117/118 walk). */
    var enumerationSkipped: Int = 0
        private set

    /** `GET_FF_VALUE(128)` asked for the known DEVICE-CONFIG key: does the flag verb see that namespace? */
    var featureFlagVerbOnDeviceConfigKey: ConfigKeySweep.Existence? = null
        private set

    /** `GET_DEVICE_CONFIG_VALUE(121)` asked for a known FLAG key: does that verb see the other namespace? */
    var deviceConfigVerbOnFlagKey: ConfigKeySweep.Existence? = null
        private set

    private val _readings = mutableListOf<Reading>()

    /** Every reading, in the order the strap served it. */
    val readings: List<Reading> get() = _readings

    private val _trace = mutableListOf<String>()

    /** Trace lines. Candidate round-trips are summarised in their own section rather than repeated here,
     *  EXCEPT the ones that are not a plain `unknown` — a hit or an odd reply always appears in full. */
    val trace: List<String> get() = _trace

    /** Round-trips attempted. Bounds the walk against [DeviceConfigReadProbe.MAX_STEPS]. */
    var steps: Int = 0
        private set

    /** Set once the walk stopped for a reason worth naming beyond "the plan ran out". */
    var stopReason: String? = null
        private set

    private var phase = 0        // 0 enumerate, 1 discovery, 2 cross, 3 known keys, 4 candidates, 5 done
    private var cursor = 0
    private var enumPhase = 0    // 0 send 115, 1 send 116 repeatedly, 2 done
    private var enumSteps = 0

    /** `"opcode:key"` pairs already attempted, so an earlier phase's key is not re-read in a later one. */
    private val attempted = mutableSetOf<String>()

    /**
     * The next round-trip to send, or null when the probe is done. Called after the previous reply has
     * been noted, so a verb proved dead is skipped for every step it would otherwise have owned.
     */
    fun nextStep(): Step? {
        if (steps >= DeviceConfigReadProbe.MAX_STEPS) {
            if (stopReason == null) {
                stopReason = "safety cap of ${DeviceConfigReadProbe.MAX_STEPS} round-trips reached"
            }
            phase = 5
            return null
        }
        while (phase < 5) {
            val step = stepInCurrentPhase()
            if (step != null) {
                cursor += 1
                // Enumeration deliberately repeats one (opcode, key) pair — the strap walks its own
                // cursor — so it is the one group the de-duplicator must not police.
                if (step.group != Group.ENUMERATE) {
                    val id = "${step.opcode}:${step.key}"
                    if (attempted.contains(id)) continue
                    attempted.add(id)
                }
                steps += 1
                return step
            }
            phase += 1
            cursor = 0
        }
        return null
    }

    /** One step from the current phase, or null when that phase is exhausted. */
    private fun stepInCurrentPhase(): Step? = when (phase) {
        0 -> when (enumPhase) {
            // Ask the strap to list its own device-config keys. 115 once; then 116 until the strap says
            // stop, exactly as the 117/118 walk does.
            0 -> Step(ConfigKeySweep.START_DEVICE_CONFIG_KEY_EXCHANGE_CMD, "", Group.ENUMERATE)
            1 -> if (enumSteps >= ConfigKeySweep.MAX_ENUMERATION_STEPS) {
                if (stopReason == null) {
                    stopReason = "device-config enumeration hit its cap of " +
                        "${ConfigKeySweep.MAX_ENUMERATION_STEPS} entries; the rest of the plan still ran"
                }
                enumPhase = 2
                null
            } else {
                enumSteps += 1
                Step(ConfigKeySweep.SEND_NEXT_DEVICE_CONFIG_CMD, "", Group.ENUMERATE)
            }
            else -> null
        }
        1 -> {
            // Discovery: one round-trip per VALUE verb, each against a key that verb has a reason to know.
            // 128 gets a flag NOOP writes; 121 gets the Broadcast-HR key NOOP has written since #181, so a
            // FAILURE there is evidence about the VERB, not about the key.
            val plan = listOf(
                Step(
                    DeviceConfigReadProbe.GET_FEATURE_FLAG_VALUE_CMD,
                    knownFlagKeys.firstOrNull() ?: DeviceConfigReadProbe.DEVICE_CONFIG_DISCOVERY_KEY,
                    Group.DISCOVERY,
                ),
                Step(
                    DeviceConfigReadProbe.GET_DEVICE_CONFIG_VALUE_CMD,
                    DeviceConfigReadProbe.DEVICE_CONFIG_DISCOVERY_KEY,
                    Group.DISCOVERY,
                ),
            )
            if (cursor < plan.size) plan[cursor] else null
        }
        2 -> {
            // Cross-namespace: each answering verb asked for the OTHER namespace's known-good key. Two
            // round-trips that settle whether the namespaces are actually separate.
            val plan = mutableListOf<Step>()
            if (featureFlagVerb == VerbStatus.ANSWERED) {
                plan.add(
                    Step(
                        DeviceConfigReadProbe.GET_FEATURE_FLAG_VALUE_CMD,
                        DeviceConfigReadProbe.DEVICE_CONFIG_DISCOVERY_KEY,
                        Group.CROSS_NAMESPACE,
                    ),
                )
            }
            val flag = knownFlagKeys.firstOrNull()
            if (deviceConfigVerb == VerbStatus.ANSWERED && flag != null) {
                plan.add(
                    Step(DeviceConfigReadProbe.GET_DEVICE_CONFIG_VALUE_CMD, flag, Group.CROSS_NAMESPACE),
                )
            }
            if (cursor < plan.size) plan[cursor] else null
        }
        3 -> {
            val plan = knownKeyPlan()
            if (cursor >= plan.size) {
                null
            } else {
                val entry = plan[cursor]
                val verb = verbFor(entry.second)
                if (verb == null) null else Step(verb, entry.first, Group.KNOWN_KEY)
            }
        }
        4 -> {
            // Guessing is the FALLBACK. If the strap enumerated its own device-config keys there is
            // nothing to guess at in that namespace, so the sweep is skipped and said so in the report.
            if (_enumeratedKeys.isNotEmpty() || cursor >= batch.candidates.size) {
                null
            } else {
                val candidate = batch.candidates[cursor]
                val verb = verbFor(candidate.namespace)
                if (verb == null) {
                    null
                } else {
                    Step(verb, candidate.key, Group.CANDIDATE, candidate.derivation)
                }
            }
        }
        else -> null
    }

    /**
     * The keys whose values are worth reading because they are already known to exist: the sixteen flags
     * NOOP writes, then whatever the strap enumerated for itself (capped, and never re-listing a flag).
     */
    private fun knownKeyPlan(): List<Pair<String, ConfigKeySweep.Namespace>> {
        val plan = knownFlagKeys.map { it to ConfigKeySweep.Namespace.FEATURE_FLAG }.toMutableList()
        for (key in _enumeratedKeys.take(ConfigKeySweep.MAX_ENUMERATED_VALUE_READS)) {
            if (!knownFlagKeys.contains(key)) plan.add(key to ConfigKeySweep.Namespace.DEVICE_CONFIG)
        }
        return plan
    }

    /**
     * The verb to ask a key of, or null when neither VALUE verb answered.
     *
     * A verb SHOWN in this same run to serve the other namespace too is preferred for everything — fewer
     * moving parts, and the evidence is from this run rather than an assumption. Otherwise each namespace
     * uses its own verb, falling back to the other one as a look worth taking.
     */
    private fun verbFor(namespace: ConfigKeySweep.Namespace): Int? {
        if (deviceConfigVerbOnFlagKey == ConfigKeySweep.Existence.EXISTS &&
            deviceConfigVerb == VerbStatus.ANSWERED
        ) {
            return DeviceConfigReadProbe.GET_DEVICE_CONFIG_VALUE_CMD
        }
        if (featureFlagVerbOnDeviceConfigKey == ConfigKeySweep.Existence.EXISTS &&
            featureFlagVerb == VerbStatus.ANSWERED
        ) {
            return DeviceConfigReadProbe.GET_FEATURE_FLAG_VALUE_CMD
        }
        val ff = if (featureFlagVerb == VerbStatus.ANSWERED) {
            DeviceConfigReadProbe.GET_FEATURE_FLAG_VALUE_CMD
        } else {
            null
        }
        val dc = if (deviceConfigVerb == VerbStatus.ANSWERED) {
            DeviceConfigReadProbe.GET_DEVICE_CONFIG_VALUE_CMD
        } else {
            null
        }
        return if (namespace == ConfigKeySweep.Namespace.FEATURE_FLAG) ff ?: dc else dc ?: ff
    }

    /**
     * Record the `START_DEVICE_CONFIG_KEY_EXCHANGE` reply. An implausible count is reported but never
     * trusted as a loop bound — the walk is bounded by [ConfigKeySweep.MAX_ENUMERATION_STEPS] and by the
     * strap's own end marker.
     */
    fun noteEnumerationStart(r: FeatureFlagProbe.StartResponse) {
        enumeratedCount = r.count
        if (r.resultCode == 3) {
            enumerationVerb = VerbStatus.UNSUPPORTED
            enumPhase = 2
            _trace.add(
                "START_DEVICE_CONFIG_KEY_EXCHANGE(115) → result=UNSUPPORTED(3) — " +
                    "the firmware does not serve this verb",
            )
            return
        }
        enumerationVerb = VerbStatus.ANSWERED
        enumPhase = 1
        var line = "START_DEVICE_CONFIG_KEY_EXCHANGE(115) →"
        val code = r.resultCode
        if (code != null) line += " result=${FeatureFlagProbe.resultLabel(code)}($code)"
        line += " revision=${r.revision} count=${r.count}"
        if (!r.countIsPlausible) line += " (implausible — walked to the strap's own end marker instead)"
        _trace.add(line)
    }

    /**
     * Record one `SEND_NEXT_DEVICE_CONFIG` reply. Returns true when the walk should continue.
     *
     * Mirrors the #874 discipline on the 117/118 walk: the strap's own end marker terminates the walk, but
     * a name OUR parser declines ([FeatureFlagProbe.NextResponse.isSkippable]) is counted and stepped over
     * — one undecodable entry must not throw away every key after it.
     */
    fun noteEnumerationNext(r: FeatureFlagProbe.NextResponse): Boolean {
        if (r.isExhausted) {
            enumPhase = 2
            _trace.add("SEND_NEXT_DEVICE_CONFIG(116) → end of list (index=${r.index} validKey=${r.validKey})")
            return false
        }
        if (r.isSkippable) {
            enumerationSkipped += 1
            _trace.add("SEND_NEXT_DEVICE_CONFIG(116) → index=${r.index} name did not decode — stepped over")
            return true
        }
        val key = r.key
        if (key != null) {
            _enumeratedKeys.add(key)
            _trace.add("SEND_NEXT_DEVICE_CONFIG(116) → index=${r.index} key=\"$key\"")
        }
        return true
    }

    /** Record one decoded VALUE reply. */
    fun noteReply(r: DeviceConfigReadProbe.ValueResponse, step: Step) {
        setStatus(if (r.isUnsupported) VerbStatus.UNSUPPORTED else VerbStatus.ANSWERED, step.opcode)
        if (step.group == Group.CROSS_NAMESPACE) {
            if (step.opcode == DeviceConfigReadProbe.GET_FEATURE_FLAG_VALUE_CMD) {
                featureFlagVerbOnDeviceConfigKey = r.existence
            } else if (step.opcode == DeviceConfigReadProbe.GET_DEVICE_CONFIG_VALUE_CMD) {
                deviceConfigVerbOnFlagKey = r.existence
            }
        }
        val value = r.valueFor(step.key)
        _readings.add(
            Reading(step.group, step.opcode, step.key, value, r.resultCode, r.recordHex, step.derivation),
        )
        // The candidate section lists every name it asked, so repeating a plain "unknown" in the
        // transcript would double the report for no information. Anything else is always traced.
        if (step.group == Group.CANDIDATE && r.existence == ConfigKeySweep.Existence.UNKNOWN) return
        var line = "${opcodeLabel(step.opcode)} key=\"${step.key}\""
        val code = r.resultCode
        line += if (code != null) " → result=${FeatureFlagProbe.resultLabel(code)}($code)" else " →"
        line += " ${r.existence.label}"
        if (value != null) line += " value=${DeviceConfigReadProbe.valueLabel(value)}"
        line += " record=[${r.recordHex}]"
        _trace.add(line)
    }

    /** Record a reply that could not be decoded. The verb is marked undecodable, which retires it. */
    fun noteFailure(f: DeviceConfigReadProbe.ParseFailure, step: Step) {
        val why = when (f) {
            DeviceConfigReadProbe.ParseFailure.CRC -> "CRC failed — frame rejected (never decoded)"
            DeviceConfigReadProbe.ParseFailure.ENVELOPE -> "not a COMMAND_RESPONSE envelope"
            DeviceConfigReadProbe.ParseFailure.WRONG_COMMAND -> "COMMAND_RESPONSE for a different command"
            DeviceConfigReadProbe.ParseFailure.TRUNCATED -> "record too short to hold a response"
        }
        setStatus(VerbStatus.UNDECODABLE, step.opcode)
        _trace.add("${opcodeLabel(step.opcode)} key=\"${step.key}\" reply not decoded: $why")
        if (stopReason == null) stopReason = why
    }

    /**
     * Record the strap answering nothing at all within the per-step window. The verb is marked silent,
     * which retires it — one no-reply must not cost another twenty timeouts.
     */
    fun noteTimeout(step: Step, seconds: Int) {
        setStatus(VerbStatus.SILENT, step.opcode)
        _trace.add("${opcodeLabel(step.opcode)} key=\"${step.key}\" → no COMMAND_RESPONSE within ${seconds}s")
    }

    /**
     * A verb's status only ever moves off UNTRIED; a later step never upgrades a verdict already reached,
     * so one lucky reply after an UNSUPPORTED cannot rewrite the headline.
     */
    private fun setStatus(s: VerbStatus, opcode: Int) {
        when (opcode) {
            DeviceConfigReadProbe.GET_FEATURE_FLAG_VALUE_CMD ->
                if (featureFlagVerb == VerbStatus.UNTRIED || featureFlagVerb == VerbStatus.ANSWERED) {
                    featureFlagVerb = s
                }
            DeviceConfigReadProbe.GET_DEVICE_CONFIG_VALUE_CMD ->
                if (deviceConfigVerb == VerbStatus.UNTRIED || deviceConfigVerb == VerbStatus.ANSWERED) {
                    deviceConfigVerb = s
                }
            ConfigKeySweep.START_DEVICE_CONFIG_KEY_EXCHANGE_CMD,
            ConfigKeySweep.SEND_NEXT_DEVICE_CONFIG_CMD,
            -> {
                if (enumerationVerb == VerbStatus.UNTRIED || enumerationVerb == VerbStatus.ANSWERED) {
                    enumerationVerb = s
                }
                if (s != VerbStatus.ANSWERED) enumPhase = 2
            }
            else -> Unit
        }
    }

    /** Short opcode label used in the transcript. */
    private fun opcodeLabel(opcode: Int): String = when (opcode) {
        DeviceConfigReadProbe.GET_DEVICE_CONFIG_VALUE_CMD -> "GET_DEVICE_CONFIG_VALUE(121)"
        ConfigKeySweep.START_DEVICE_CONFIG_KEY_EXCHANGE_CMD -> "START_DEVICE_CONFIG_KEY_EXCHANGE(115)"
        ConfigKeySweep.SEND_NEXT_DEVICE_CONFIG_CMD -> "SEND_NEXT_DEVICE_CONFIG(116)"
        else -> "GET_FF_VALUE(128)"
    }

    /** Candidate readings only. */
    private val candidateReadings: List<Reading> get() = _readings.filter { it.group == Group.CANDIDATE }

    /**
     * Every key name this run proved EXISTS that NOOP did not already have — the whole point of the
     * exercise. Enumerated names count; so does any candidate the oracle confirmed.
     */
    val newKeysFound: List<String>
        get() {
            val out = _enumeratedKeys.filter {
                !knownFlagKeys.contains(it) && it != DeviceConfigReadProbe.DEVICE_CONFIG_DISCOVERY_KEY
            }.toMutableList()
            for (r in candidateReadings) {
                if (r.existence == ConfigKeySweep.Existence.EXISTS && !out.contains(r.key)) out.add(r.key)
            }
            return out
        }

    /** One-line summary of what the probe established. */
    val verdict: String
        get() {
            val found = newKeysFound
            if (found.isNotEmpty()) {
                return "${found.size} config key name(s) found that NOOP did not have: ${found.joinToString(", ")}"
            }
            if (enumerationVerb == VerbStatus.ANSWERED) {
                return "the strap enumerated its device-config namespace and returned no key NOOP did not already have"
            }
            val answered = listOf(featureFlagVerb, deviceConfigVerb).count { it == VerbStatus.ANSWERED }
            if (answered == 0) {
                val both =
                    "neither GET_FF_VALUE(128) nor GET_DEVICE_CONFIG_VALUE(121) is served by this firmware"
                if (featureFlagVerb == VerbStatus.UNSUPPORTED || deviceConfigVerb == VerbStatus.UNSUPPORTED) {
                    return "$both — rejected as UNSUPPORTED"
                }
                if (featureFlagVerb == VerbStatus.SILENT && deviceConfigVerb == VerbStatus.SILENT) {
                    return "$both — no reply to either"
                }
                return both
            }
            val asked = candidateReadings.size
            if (asked == 0) {
                return "$answered of 2 read verbs answered; no candidate name was asked"
            }
            val unknown = candidateReadings.count { it.existence == ConfigKeySweep.Existence.UNKNOWN }
            if (unknown == asked) {
                return "asked $asked candidate key name(s); this firmware has none of them (a clean negative)"
            }
            return "asked $asked candidate key name(s); $unknown do not exist, ${asked - unknown} inconclusive"
        }

    /** The full copyable report (byte-identical to the Swift `render()`). */
    fun render(): String {
        val fam = if (family == DeviceFamily.WHOOP5) "WHOOP 5/MG" else "WHOOP 4.0"
        val sb = StringBuilder()
        sb.append("#103 CONFIG KEY PROBE — $fam\n")
        sb.append("Read-only: START_DEVICE_CONFIG_KEY_EXCHANGE(115), SEND_NEXT_DEVICE_CONFIG(116), ")
        sb.append("GET_DEVICE_CONFIG_VALUE(121), GET_FF_VALUE(128).\n")
        sb.append(
            "No value is written; SET_DEVICE_CONFIG_VALUE(119) and SET_FF_VALUE(120) are never sent from this path.\n",
        )
        sb.append(
            "Oracle: result=SUCCESS(1) means the key NAME exists; result=FAILURE(0) means the firmware has no such key.\n",
        )
        sb.append("\nVerdict: $verdict\n")
        stopReason?.let { sb.append("Stopped: $it\n") }

        sb.append("\nVerbs:\n")
        sb.append("  ").append(DeviceConfigReadProbe.padded("device-config enumerate(115/116)", 34))
            .append(enumerationVerb.label).append("\n")
        sb.append("  ").append(DeviceConfigReadProbe.padded("GET_FF_VALUE(128)", 34))
            .append(featureFlagVerb.label).append("\n")
        sb.append("  ").append(DeviceConfigReadProbe.padded("GET_DEVICE_CONFIG_VALUE(121)", 34))
            .append(deviceConfigVerb.label).append("\n")

        sb.append(enumerationSection())
        sb.append(namespaceSection())
        sb.append(
            section(
                Group.DISCOVERY,
                "Discovery — one round-trip per value verb against a key it should know",
                "(none — no reply was decoded)",
            ),
        )
        sb.append(
            section(
                Group.KNOWN_KEY,
                "Known key values (the flags NOOP writes, plus anything enumeration returned)",
                "(none — no value verb answered)",
            ),
        )
        sb.append(candidateSection())

        sb.append("\nExchange:\n")
        for (line in _trace) sb.append("  ").append(line).append("\n")
        return sb.toString()
    }

    /** The strap's own device-config key list — the result that makes guessing unnecessary. */
    private fun enumerationSection(): String {
        val sb = StringBuilder()
        sb.append("\nDevice-config keys the strap listed for itself (115/116) (${_enumeratedKeys.size}):\n")
        if (_enumeratedKeys.isEmpty()) {
            sb.append(
                when (enumerationVerb) {
                    VerbStatus.UNSUPPORTED -> "  (none — the firmware refused 115 as UNSUPPORTED)\n"
                    VerbStatus.SILENT -> "  (none — no reply to 115)\n"
                    VerbStatus.UNDECODABLE -> "  (none — the reply did not decode)\n"
                    VerbStatus.ANSWERED -> "  (none — 115 answered but the walk produced no names)\n"
                    VerbStatus.UNTRIED -> "  (none — not reached)\n"
                },
            )
            return sb.toString()
        }
        _enumeratedKeys.forEachIndexed { i, key ->
            sb.append("  %2d. ".format(i + 1)).append(key).append("\n")
        }
        if (enumerationSkipped > 0) {
            sb.append(
                "  ($enumerationSkipped further entr(ies) the strap called real but whose name did not decode)\n",
            )
        }
        val announced = enumeratedCount
        if (announced != null && announced != _enumeratedKeys.size + enumerationSkipped) {
            sb.append(
                "  (the strap announced $announced; the walk served ${_enumeratedKeys.size + enumerationSkipped})\n",
            )
        }
        return sb.toString()
    }

    /** Whether the two namespaces are really separate — two round-trips that shape every future sweep. */
    private fun namespaceSection(): String {
        val sb = StringBuilder()
        sb.append("\nNamespace separation:\n")
        val ffLabel = featureFlagVerbOnDeviceConfigKey?.label ?: "not asked"
        val dcLabel = deviceConfigVerbOnFlagKey?.label ?: "not asked"
        sb.append("  ").append(DeviceConfigReadProbe.padded("128 asked for a device-config key", 38))
            .append(ffLabel).append("\n")
        sb.append("  ").append(DeviceConfigReadProbe.padded("121 asked for a feature-flag key", 38))
            .append(dcLabel).append("\n")
        sb.append(
            when {
                featureFlagVerbOnDeviceConfigKey == ConfigKeySweep.Existence.EXISTS ->
                    "  ⇒ GET_FF_VALUE(128) serves BOTH namespaces.\n"
                deviceConfigVerbOnFlagKey == ConfigKeySweep.Existence.EXISTS ->
                    "  ⇒ GET_DEVICE_CONFIG_VALUE(121) serves BOTH namespaces.\n"
                featureFlagVerbOnDeviceConfigKey == ConfigKeySweep.Existence.UNKNOWN &&
                    deviceConfigVerbOnFlagKey == ConfigKeySweep.Existence.UNKNOWN ->
                    "  ⇒ the namespaces are separate: neither verb sees the other's keys.\n"
                else -> "  ⇒ inconclusive.\n"
            },
        )
        return sb.toString()
    }

    /** The candidate sweep, grouped by derivation, with the tested/untested arithmetic spelled out. */
    private fun candidateSection(): String {
        val rows = candidateReadings
        val tested = rows.size
        val total = ConfigKeySweep.CATALOGUE.size
        val untested = total - batch.start - tested
        val sb = StringBuilder()
        sb.append("\nCandidate key names — GUESSES, never observed on a wire or in any table")
        sb.append(" ($tested asked of $total in the catalogue")
        sb.append(if (untested > 0) "; $untested untested" else "; none untested")
        sb.append("):\n")
        if (rows.isEmpty()) {
            sb.append(
                when {
                    _enumeratedKeys.isNotEmpty() ->
                        "  (skipped — the strap enumerated its own device-config keys, so nothing needs guessing)\n"
                    featureFlagVerb != VerbStatus.ANSWERED && deviceConfigVerb != VerbStatus.ANSWERED ->
                        "  (none — no value verb answered, so no name could be asked)\n"
                    else -> "  (none asked)\n"
                },
            )
            return sb.toString()
        }
        val exists = rows.count { it.existence == ConfigKeySweep.Existence.EXISTS }
        val unknown = rows.count { it.existence == ConfigKeySweep.Existence.UNKNOWN }
        sb.append("  $exists exist · $unknown do not · ${tested - exists - unknown} inconclusive\n")
        for (derivation in ConfigKeySweep.Derivation.entries) {
            val group = rows.filter { it.derivation == derivation }
            if (group.isEmpty()) continue
            sb.append("\n  ${derivation.title} (${group.size}):\n")
            group.forEachIndexed { i, r ->
                sb.append("   %2d. ".format(i + 1)).append(DeviceConfigReadProbe.padded(r.key, 32))
                    .append(r.existence.label)
                val v = r.value
                if (v != null) sb.append(" = ").append(DeviceConfigReadProbe.valueLabel(v))
                sb.append("\n")
            }
        }
        if (untested > 0) {
            sb.append("\n  Run the probe again to continue from catalogue entry ${batch.nextCursor + 1}.\n")
        }
        return sb.toString()
    }

    /** One rendered section of readings. */
    private fun section(group: Group, title: String, empty: String): String {
        val rows = _readings.filter { it.group == group }
        val sb = StringBuilder()
        sb.append("\n$title (${rows.size}):\n")
        if (rows.isEmpty()) {
            sb.append("  ").append(empty).append("\n")
            return sb.toString()
        }
        rows.forEachIndexed { i, r ->
            val line = StringBuilder()
            line.append("  %2d. ".format(i + 1)).append(DeviceConfigReadProbe.padded(r.key, 32))
            val v = r.value
            val code = r.resultCode
            when {
                v != null -> line.append("= ").append(DeviceConfigReadProbe.valueLabel(v))
                code != null ->
                    line.append("— no value (result=${FeatureFlagProbe.resultLabel(code)}($code))")
                else -> line.append("— no value (the reply did not echo the key)")
            }
            sb.append(line).append("\n")
        }
        return sb.toString()
    }
}
