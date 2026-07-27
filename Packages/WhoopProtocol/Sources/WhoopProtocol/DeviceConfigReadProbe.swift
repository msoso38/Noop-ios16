import Foundation

/// #103: READ-ONLY probe of the two config **read** verbs — `GET_DEVICE_CONFIG_VALUE` (121 / 0x79) and
/// `GET_FF_VALUE` (128 / 0x80). Follow-up to the #761 enumeration probe, which asked the strap for key
/// NAMES; this asks for a key's VALUE.
///
/// ## Why
///
/// NOOP writes config two ways and reads it neither way. `SET_FF_VALUE` (120 / 0x78) writes the sixteen
/// R22 feature flags in `Whoop5Config.enableR22Sequence`; `SET_DEVICE_CONFIG_VALUE` (119 / 0x77) writes
/// the one device-config key NOOP knows (the Broadcast-HR flag, #181). Those are two *different
/// namespaces* sharing a body layout — and the 117/118 enumerate pair #761 built covers the feature-flag
/// namespace ONLY. The device-config namespace has never been read or enumerated here at all.
///
/// That matters for #103. NOOP already decodes a byte the deep record carries at offset 82 that reads as
/// real SpO2 on some straps and is flat 0x00 on others, which is what a subscription gate would look
/// like. If a config value governs it, it is far more likely to live in the device-config namespace than
/// among the sixteen flags NOOP already writes — and nobody has ever asked a strap for one.
///
/// ## What this establishes, and the honest failure case
///
/// Both read verbs answered on a real WHOOP 5 MG (WS50_r03), and the reply turned out to be an
/// **existence oracle**: `SUCCESS(1)` for a key name the firmware has, `FAILURE(0)` for one it does not.
/// That is what makes a key-name search possible at all, and `ConfigKeySweep` is where it lives.
///
/// The probe now runs a plan built around one principle: **ask the strap before guessing.** It opens with
/// the DEVICE-CONFIG ENUMERATION pair `START_DEVICE_CONFIG_KEY_EXCHANGE` (115) and
/// `SEND_NEXT_DEVICE_CONFIG` (116) — named in the repo's own `CommandNumber` table, never sent by
/// anything here, and the structural twin of the 117/118 feature-flag pair #872 shipped and a strap
/// answered. If 115/116 answer, the strap lists its own device-config keys and no name needs guessing at
/// all; the candidate sweep is skipped and the report says so. A clean "115/116 are not served" is
/// equally useful and publishable — it is what promotes the guessing fallback from a shortcut to the only
/// available method.
///
/// After enumeration the probe reads the values of keys already known to exist (the sixteen in
/// `Whoop5Config.enableR22Sequence`, plus anything enumeration returned), spends two round-trips
/// establishing whether the two namespaces are actually separate, and only then — and only when
/// enumeration produced nothing — asks the guessed names in `ConfigKeySweep.catalogue`.
///
/// ## Read-only by construction
///
/// The probe writes command frames purely in order to read, the same shape as the Oura feature-status
/// probes NOOP already ships (`Packages/OuraProtocol/…/Commands.swift`) and as the #761 enumeration. The
/// SET verbs are named in `writeOpcodes` for exactly one reason: so the send allowlist can be expressed
/// as "`readOnlyOpcodes` only" and a unit test can prove 119/120 are rejected by it. Nothing in this file
/// or on its BLE path can form a SET frame.
///
/// ## Wire shape (a documented guess, and it fails closed)
///
/// Request: command 121 or 128 with body `[0x01]` — the inner b3 convention `CLIENT_HELLO` and the
/// SET_CONFIG family use — followed by the key name as ASCII NUL-padded to 32 bytes, the same name field
/// `Whoop5Config.payloadBody` / `deviceConfigBody` build for the SET side, minus the trailing value byte
/// a read has no reason to carry. That body shape is inferred from the SET side, not observed; if it is
/// wrong the strap answers FAILURE or nothing, which the report says plainly rather than papering over.
///
/// Response: an ordinary COMMAND_RESPONSE echoing the command byte, whose record sits behind the 2-byte
/// response header (`pay[1]` is the 5/MG result code) — the same `pay[2]` record start
/// `GET_BATTERY_LEVEL`, `GET_CLOCK` and the #761 replies all decode from. Beyond that offset **no field
/// layout is assumed**: the record is kept and reported as raw hex. A value is only ever *claimed* when
/// the reply echoes the key that was asked for inside a 32-byte NUL-padded name field, in which case the
/// byte immediately after that field is reported as the value — the SET side's own layout, checked
/// rather than assumed (`ValueResponse.value(for:)`). Note that on 5/MG the puffin envelope pads the
/// inner payload to a 4-byte boundary, so up to three trailing NULs in any record are envelope padding
/// rather than data; reading the value as "the byte after the echoed name field" rather than "the last
/// byte" is what keeps that padding out of the answer.
///
/// Everything fails closed: a frame whose CRCs don't verify, whose type isn't COMMAND_RESPONSE, or whose
/// record is too short is REJECTED rather than guessed at.
public enum DeviceConfigReadProbe {

    // MARK: - Wire constants

    /// `GET_DEVICE_CONFIG_VALUE` (121 / 0x79) — ask for one device-config value by key name. Read-only.
    /// Named in the repo's protocol table; never before sent by NOOP, and possibly unimplemented.
    public static let getDeviceConfigValueCmd: UInt8 = 121

    /// `GET_FF_VALUE` (128 / 0x80) — ask for one feature-flag value by key name. Read-only. Named in the
    /// repo's protocol table; never before sent by NOOP, and possibly unimplemented.
    public static let getFeatureFlagValueCmd: UInt8 = 128

    /// `SET_DEVICE_CONFIG_VALUE` (119 / 0x77). Listed here ONLY so the allowlist below can name what it
    /// excludes. This probe never sends it.
    public static let setDeviceConfigValueCmd: UInt8 = 119

    /// `SET_FF_VALUE` (120 / 0x78). Listed here ONLY so the allowlist below can name what it excludes.
    /// This probe never sends it.
    public static let setFeatureFlagValueCmd: UInt8 = 120

    /// The complete set of opcodes this probe may put on the wire: the two VALUE reads above, plus the two
    /// DEVICE-CONFIG ENUMERATION verbs the probe now tries first (`ConfigKeySweep`, 115/116 — the
    /// structural twins of the 117/118 pair #872 shipped and a real strap answered read-only). The BLE
    /// send path admits these four and **only** these four while a probe is in flight; `isReadOnlyOpcode`
    /// is the predicate it asks, and unit tests prove it rejects 119, 120 and every other opcode.
    public static let readOnlyOpcodes: Set<UInt8> = [
        getDeviceConfigValueCmd, getFeatureFlagValueCmd,
        ConfigKeySweep.startDeviceConfigKeyExchangeCmd, ConfigKeySweep.sendNextDeviceConfigCmd,
    ]

    /// The config WRITE verbs, which this probe must never emit. Kept as a named set so the read-only
    /// contract is testable as a property of the allowlist rather than as a claim in a comment.
    public static let writeOpcodes: Set<UInt8> = [setDeviceConfigValueCmd, setFeatureFlagValueCmd]

    /// The allowlist predicate itself. `false` for both SET verbs, for every opcode outside the pair, and
    /// therefore for anything a future edit might accidentally route through this path.
    public static func isReadOnlyOpcode(_ opcode: UInt8) -> Bool { readOnlyOpcodes.contains(opcode) }

    /// Width of the key-name field in the SET bodies (`Whoop5Config.payloadBody` /
    /// `deviceConfigBody` both NUL-pad the name to 32 bytes). The request mirrors it; a reply that echoes
    /// the key is read against it.
    public static let nameFieldBytes = 32

    /// Hard ceiling on round-trips in one probe, independent of how many keys the plan holds. A firmware
    /// that answers oddly must not be able to drive an unbounded write loop on the command characteristic.
    ///
    /// The plan is 1 enumerate-start + up to `ConfigKeySweep.maxEnumerationSteps` enumerate-next + 2
    /// discovery + 2 cross-namespace + 16 known flags, and then EITHER up to
    /// `ConfigKeySweep.maxEnumeratedValueReads` value reads (when enumeration produced a list) OR up to
    /// `ConfigKeySweep.maxKeysPerRun` candidate names (when it did not) — never both, because guessing is
    /// pointless once the strap has handed over its own list. Worst case is 101 round-trips, comfortably
    /// under this; a plan that somehow exceeds it stops with a named reason rather than truncating silently.
    /// Hard ceiling on round-trips in one probe. Raised from 128 when the sweep began asking every
    /// candidate through EVERY answering verb: the worst case is enumeration + 2 discovery + 2 cross +
    /// the known-key reads + 2 × the catalogue, which passes 128 and would otherwise have been silently
    /// truncated by the cap — reported as "safety cap reached", but still a short sweep presented as a
    /// finished one.
    public static let maxSteps = 320

    /// The one device-config key NOOP already knows a real strap accepts: the Broadcast-HR flag written
    /// via `SET_DEVICE_CONFIG_VALUE` and hardware-validated in #181. Used as the discovery key for opcode
    /// 121 precisely because it is *known-good* — a FAILURE on this key is evidence about the verb, not
    /// about the key.
    public static let deviceConfigDiscoveryKey = "whoop_live_hr_in_adv_ind_pkt"

    /// The request body for one read: the inner b3 byte `0x01`, then the key name as ASCII NUL-padded to
    /// `nameFieldBytes`. A name longer than the field is truncated to it, exactly as the SET side does.
    public static func requestBody(key: String) -> [UInt8] {
        var body = [UInt8](repeating: 0, count: nameFieldBytes)
        let bytes = Array(key.utf8)
        for i in 0..<min(nameFieldBytes, bytes.count) { body[i] = bytes[i] }
        return [0x01] + body
    }

    // MARK: - Decoded response

    /// Why a frame was not decoded. Distinct cases so the report can say *what* went wrong instead of
    /// silently dropping a reply. Mirrors `FeatureFlagProbe.ParseFailure`.
    public enum ParseFailure: String, Error, Equatable, Sendable {
        /// A CRC did not verify. Bad bytes never drive state (BLE safety contract §2).
        case crc
        /// Not a COMMAND_RESPONSE frame, or too short to hold the envelope.
        case envelope
        /// A COMMAND_RESPONSE for a different command.
        case wrongCommand
        /// The record is shorter than a response record can be.
        case truncated
    }

    /// One decoded config-read reply. Deliberately shallow: the record is kept RAW because no field
    /// layout for these two opcodes has ever been observed. The only structured read offered is
    /// `value(for:)`, and it only answers when the strap echoed the key back in the SET side's own
    /// 32-byte name field.
    public struct ValueResponse: Equatable, Sendable {
        /// Result code byte (`pay[1]`), labelled only on 5/MG where its meaning is pinned
        /// (0 FAILURE / 1 SUCCESS / 2 PENDING / 3 UNSUPPORTED); nil on WHOOP 4.0.
        public let resultCode: Int?
        /// The record bytes behind the 2-byte response header, exactly as received.
        public let record: [UInt8]

        public init(resultCode: Int?, record: [UInt8]) {
            self.resultCode = resultCode
            self.record = record
        }

        /// The firmware explicitly refused the verb. The single most informative outcome this probe can
        /// get, and the one that settles "is 121/128 implemented at all" in one round-trip.
        public var isUnsupported: Bool { resultCode == 3 }

        /// The firmware answered but reported failure — the verb exists, the request did not satisfy it
        /// (wrong body shape, or an unknown key).
        public var isFailure: Bool { resultCode == 0 }

        /// What this reply says about whether the key NAME exists, per the oracle a real WHOOP 5 MG
        /// established: `SUCCESS(1)` = the firmware has this key, `FAILURE(0)` = it does not, anything
        /// else (and WHOOP 4.0, where the result byte's meaning is not pinned here) = inconclusive.
        /// Deliberately reads the RESULT CODE and nothing else — no inference from the record bytes.
        public var existence: ConfigKeySweep.Existence { ConfigKeySweep.existence(resultCode: resultCode) }

        /// Raw record bytes as lowercase space-separated hex; always reported, whatever else decodes.
        public var recordHex: String { DeviceConfigReadProbe.hex(record) }

        /// Where `key` appears in the record as a NUL-padded 32-byte name field, or nil if it does not.
        /// Requires the padding to actually be NUL so a coincidental substring can't be mistaken for the
        /// name field.
        public func echoOffset(of key: String) -> Int? {
            let needle = Array(key.utf8)
            guard !needle.isEmpty, needle.count <= DeviceConfigReadProbe.nameFieldBytes else { return nil }
            let field = DeviceConfigReadProbe.nameFieldBytes
            guard record.count >= field else { return nil }
            for start in 0...(record.count - field) {
                var matched = true
                for i in 0..<needle.count where record[start + i] != needle[i] { matched = false; break }
                guard matched else { continue }
                // The rest of the field must be NUL padding, or this is not the name field.
                var padded = true
                for i in needle.count..<field where record[start + i] != 0 { padded = false; break }
                if padded { return start }
            }
            return nil
        }

        /// The value byte, reported ONLY when the strap echoed `key` in a 32-byte NUL-padded name field
        /// and the record extends one byte past it — the same `[name 32][value]` layout the SET bodies
        /// use. nil means "no value claimed", never "value is zero".
        ///
        /// **First byte only.** Values are not all one byte (see `stringValue(for:)`); this stays as it is
        /// because every caller that reports a single flag character wants exactly this, and widening it
        /// would silently change what the sweep prints.
        public func value(for key: String) -> UInt8? {
            guard let off = echoOffset(of: key) else { return nil }
            let valueIndex = off + DeviceConfigReadProbe.nameFieldBytes
            guard valueIndex < record.count else { return nil }
            return record[valueIndex]
        }

        /// The value as the strap actually stores it — the WHOLE NUL-terminated ASCII string after the
        /// echoed name field, not just its first byte.
        ///
        /// Device-config values are **not** all single characters. A WHOOP 5 MG's own 115/116 enumeration
        /// listed `max_collection_backlog`, whose value reads `"0.0"` — three characters. `value(for:)`
        /// would report that as `'0'` and quietly lose the rest, which is fine for a flag and wrong for
        /// anything else, so any caller comparing a value against what it asked for must use this.
        ///
        /// Stops at the first NUL, which is what keeps the puffin envelope's 4-byte-boundary padding out
        /// of the answer (the same reasoning `value(for:)` relies on for reading the byte after the name
        /// rather than the last byte of the record). Returns nil — "no value claimed", never "empty" — when
        /// the key was not echoed, when nothing follows the name field, or when what follows is not
        /// printable ASCII, since a non-ASCII run is not a value this layout can honestly claim to have read.
        public func stringValue(for key: String) -> String? {
            guard let off = echoOffset(of: key) else { return nil }
            let start = off + DeviceConfigReadProbe.nameFieldBytes
            guard start < record.count else { return nil }
            var bytes: [UInt8] = []
            for i in start..<record.count {
                let b = record[i]
                if b == 0 { break }
                guard (0x20...0x7E).contains(b) else { return nil }
                bytes.append(b)
            }
            guard !bytes.isEmpty else { return nil }
            return String(decoding: bytes, as: UTF8.self)
        }
    }

    // MARK: - Parsing

    /// Decode a config-read COMMAND_RESPONSE for `expecting` (121 or 128). CRC-gated: a frame whose
    /// checksums fail is rejected before any field is read.
    public static func parse(frame: [UInt8], family: DeviceFamily,
                             expecting: UInt8) -> Result<ValueResponse, ParseFailure> {
        // BLE safety contract §2: CRC-gate everything. A frame that fails either checksum is not data.
        guard verifyFrame(frame, family: family).ok else { return .failure(.crc) }
        let typeOff = family == .whoop5 ? 8 : 4
        let cmdOff = typeOff + 2
        guard frame.count > cmdOff + 4 else { return .failure(.envelope) }
        guard frame[typeOff] == commandResponseType else { return .failure(.envelope) }
        guard frame[cmdOff] == expecting else { return .failure(.wrongCommand) }
        let payStart = cmdOff + 1
        let payEnd = frame.count - 4          // strip the CRC32 trailer
        guard payEnd > payStart else { return .failure(.truncated) }
        let pay = Array(frame[payStart..<payEnd])
        guard pay.count > responseHeaderBytes else { return .failure(.truncated) }
        let resultCode: Int? = family == .whoop5 ? Int(pay[1]) : nil
        return .success(ValueResponse(resultCode: resultCode, record: Array(pay[responseHeaderBytes...])))
    }

    /// COMMAND_RESPONSE packet type (`PacketType.COMMAND_RESPONSE` = 36 / 0x24).
    static let commandResponseType: UInt8 = 36

    /// Bytes of response header ahead of the packet record. `pay[1]` is the 5/MG result code.
    static let responseHeaderBytes = 2

    /// Lowercase space-separated hex, capped so one oversized record can't flood the report. The cap is
    /// reported when it bites rather than silently truncating.
    static func hex(_ bytes: [UInt8]) -> String {
        let shown = bytes.prefix(maxHexBytes)
        var out = shown.map { String(format: "%02x", $0) }.joined(separator: " ")
        if bytes.count > maxHexBytes { out += " … (\(bytes.count) bytes)" }
        return out.isEmpty ? "(empty)" : out
    }

    /// Record bytes rendered in the report before the hex is elided.
    static let maxHexBytes = 48

    /// Pad `s` on the right to `width`, used instead of a printf `%-Ns` so Swift and Kotlin render the
    /// report identically (Swift's `%s` takes a C string, Kotlin's takes a `String`).
    static func padded(_ s: String, to width: Int) -> String {
        s.count >= width ? s : s + String(repeating: " ", count: width - s.count)
    }

    /// A value byte rendered as both its character (when printable, which the SET side's ASCII digits
    /// always are) and its number.
    static func valueLabel(_ v: UInt8) -> String {
        (32...126).contains(v) ? "'\(Character(UnicodeScalar(v)))' (0x\(String(format: "%02x", v)))"
                               : "0x\(String(format: "%02x", v))"
    }
}
// MARK: - Report

/// The running result of one config probe: the strap's own device-config key list when it will give one,
/// the per-verb verdict, the values read, the candidate sweep when guessing is still necessary, and the
/// copyable transcript. Pure and order-dependent (`nextStep` → `note…` → `nextStep` → …), so `swift test`
/// covers the whole probe — plan, verdicts, rendering — without a strap. Kotlin twin:
/// `DeviceConfigReadProbeReport` in `android/…/protocol/DeviceConfigReadProbe.kt`; the rendered text is
/// byte-identical across platforms so a shared strap log reads the same either side.
///
/// The plan is ordered so the cheapest decisive question is asked first:
///
/// 1. **enumerate** — 115 then repeated 116. If the strap answers, it has just listed its own
///    device-config keys and no name needs guessing.
/// 2. **discovery** — one 128 read and one 121 read, each against a key that verb should know, to
///    establish whether the VALUE verbs answer at all.
/// 3. **crossNamespace** — ask each verb for the OTHER namespace's known key. Settles in two round-trips
///    whether the namespaces are really separate, which halves every future sweep if they are not.
/// 4. **knownKey** — read the values of the sixteen flags NOOP writes, plus any key enumeration produced.
/// 5. **candidate** — the guessed-name sweep, and **only when enumeration produced no list**.
public struct DeviceConfigReadProbeReport: Equatable, Sendable {

    /// Which part of the plan a step belongs to. Drives both the ordering and the report's sections.
    public enum Group: String, Equatable, Sendable {
        /// 115/116: ask the strap to list its own device-config keys.
        case enumerate
        /// One round-trip per VALUE verb against a key that verb should know.
        case discovery
        /// Each VALUE verb asked for the other namespace's known key.
        case crossNamespace
        /// Keys already known to exist — the sixteen flags, plus anything enumeration returned.
        case knownKey
        /// Guessed key names. Labelled as guesses everywhere.
        case candidate
    }

    /// One planned round-trip. `derivation` is set only for candidate steps.
    public struct Step: Equatable, Sendable {
        public let opcode: UInt8
        public let key: String
        public let group: Group
        public let derivation: ConfigKeySweep.Derivation?
        public init(opcode: UInt8, key: String, group: Group,
                    derivation: ConfigKeySweep.Derivation? = nil) {
            self.opcode = opcode
            self.key = key
            self.group = group
            self.derivation = derivation
        }
    }

    /// What one verb has been shown to do. `untried` until its first step resolves.
    public enum VerbStatus: String, Equatable, Sendable {
        case untried
        /// A decodable COMMAND_RESPONSE came back and was not an explicit UNSUPPORTED.
        case answered
        /// The firmware refused the opcode (5/MG result code 3).
        case unsupported
        /// No reply inside the probe's per-step window.
        case silent
        /// A reply arrived but could not be decoded (CRC, envelope, or a short record).
        case undecodable
    }

    /// One value read (or attempted) for one key.
    public struct Reading: Equatable, Sendable {
        public let group: Group
        public let opcode: UInt8
        public let key: String
        /// The value byte, only when the strap echoed the key in a 32-byte name field. nil is "not
        /// claimed", never "zero".
        public let value: UInt8?
        public let resultCode: Int?
        public let recordHex: String
        public let derivation: ConfigKeySweep.Derivation?
        public init(group: Group, opcode: UInt8, key: String, value: UInt8?,
                    resultCode: Int?, recordHex: String,
                    derivation: ConfigKeySweep.Derivation? = nil) {
            self.group = group
            self.opcode = opcode
            self.key = key
            self.value = value
            self.resultCode = resultCode
            self.recordHex = recordHex
            self.derivation = derivation
        }
        /// The oracle's verdict on whether this key NAME exists.
        public var existence: ConfigKeySweep.Existence {
            ConfigKeySweep.existence(resultCode: resultCode)
        }
    }

    // MARK: Inputs

    /// Which family the probe ran against (labels only).
    public let family: DeviceFamily
    /// The flag names whose values to read — supplied by the caller from `Whoop5Config.enableR22Sequence`
    /// so this file never restates them.
    public let knownFlagKeys: [String]
    /// This run's slice of the candidate catalogue, and the cursor to hand the next run.
    public let batch: ConfigKeySweep.Batch
    /// Run the candidate sweep EVEN IF enumeration succeeded. Default false — see `runsCandidateSweep`.
    public let forceCandidateSweep: Bool

    // MARK: Accumulated state

    /// Status of `GET_FF_VALUE` (128).
    public private(set) var featureFlagVerb: VerbStatus = .untried
    /// Status of `GET_DEVICE_CONFIG_VALUE` (121).
    public private(set) var deviceConfigVerb: VerbStatus = .untried
    /// Status of the device-config ENUMERATION pair (115/116), taken as one verb: 116 cannot be asked
    /// without 115 having answered, so a single verdict describes the pair.
    public private(set) var enumerationVerb: VerbStatus = .untried
    /// Device-config key names the strap listed for itself. The headline result when it is non-empty.
    public private(set) var enumeratedKeys: [String] = []
    /// The key count `START_DEVICE_CONFIG_KEY_EXCHANGE` announced, when it answered.
    public private(set) var enumeratedCount: Int?
    /// Entries the strap called real keys whose NAME did not decode, stepped over rather than trusted as
    /// a terminator (the discipline #874 established for the 117/118 walk).
    public private(set) var enumerationSkipped = 0
    /// `GET_FF_VALUE(128)` asked for the known DEVICE-CONFIG key: does the flag verb see that namespace?
    public private(set) var featureFlagVerbOnDeviceConfigKey: ConfigKeySweep.Existence?
    /// `GET_DEVICE_CONFIG_VALUE(121)` asked for a known FLAG key: does the device-config verb see that one?
    public private(set) var deviceConfigVerbOnFlagKey: ConfigKeySweep.Existence?
    /// Every reading, in the order the strap served it.
    public private(set) var readings: [Reading] = []
    /// Trace lines. Candidate round-trips are summarised in their own section rather than repeated here,
    /// EXCEPT the ones that are not a plain `unknown` — a hit or an odd reply always appears in full.
    public private(set) var trace: [String] = []
    /// Round-trips attempted. Bounds the walk against `DeviceConfigReadProbe.maxSteps`.
    public private(set) var steps = 0
    /// Set once the walk stopped for a reason worth naming beyond "the plan ran out".
    public private(set) var stopReason: String?

    // MARK: Plan cursors

    private var phase = 0            // 0 enumerate, 1 discovery, 2 cross, 3 known keys, 4 candidates, 5 done
    private var cursor = 0
    private var enumPhase = 0        // 0 send 115, 1 send 116 repeatedly, 2 done
    private var enumSteps = 0
    /// `"opcode:key"` pairs already attempted, so an earlier phase's key is not re-read in a later one.
    private var attempted: Set<String> = []

    public init(family: DeviceFamily, knownFlagKeys: [String], batch: ConfigKeySweep.Batch,
                forceCandidateSweep: Bool = false) {
        self.family = family
        self.knownFlagKeys = knownFlagKeys
        self.batch = batch
        self.forceCandidateSweep = forceCandidateSweep
    }

    /// Whether this run will ask the guessed names at all.
    ///
    /// By default the sweep is a FALLBACK: a strap that enumerated its own device-config keys has already
    /// answered the question guessing was for, so the sweep is skipped.
    ///
    /// That default is right for the device-config namespace and **incomplete as a general claim**, which
    /// is why `forceCandidateSweep` exists. Enumeration reports the keys the firmware holds; a key it
    /// would accept but has never been given a value for need not be among them, and the oracle cannot
    /// separate that case from "no such key" because both answer `FAILURE(0)`. A successful enumeration is
    /// therefore evidence about what the strap HAS, not proof of what it would ACCEPT — and it says
    /// nothing at all about the FEATURE-FLAG namespace, which is where most of the catalogue is aimed.
    ///
    /// So the forced run stays available, and stays explicit: it costs one round-trip per catalogue name,
    /// which is not something to spend by default.
    public var runsCandidateSweep: Bool { forceCandidateSweep || enumeratedKeys.isEmpty }

    // MARK: - Plan

    /// The next round-trip to send, or nil when the probe is done. Called after the previous reply has
    /// been noted, so a verb proved dead is skipped for every step it would otherwise have owned.
    public mutating func nextStep() -> Step? {
        guard steps < DeviceConfigReadProbe.maxSteps else {
            stopReason = stopReason ?? "safety cap of \(DeviceConfigReadProbe.maxSteps) round-trips reached"
            phase = 5
            return nil
        }
        while phase < 5 {
            if let step = stepInCurrentPhase() {
                cursor += 1
                // Enumeration deliberately repeats one (opcode, key) pair — the strap walks its own
                // cursor — so it is the one group the de-duplicator must not police.
                if step.group != .enumerate {
                    let id = "\(step.opcode):\(step.key)"
                    if attempted.contains(id) { continue }
                    attempted.insert(id)
                }
                steps += 1
                return step
            }
            phase += 1
            cursor = 0
        }
        return nil
    }

    /// One step from the current phase, or nil when that phase is exhausted.
    private mutating func stepInCurrentPhase() -> Step? {
        switch phase {
        case 0:
            // Ask the strap to list its own device-config keys. 115 once; then 116 until the strap says
            // stop, exactly as the 117/118 walk does.
            switch enumPhase {
            case 0:
                return Step(opcode: ConfigKeySweep.startDeviceConfigKeyExchangeCmd, key: "",
                            group: .enumerate)
            case 1:
                guard enumSteps < ConfigKeySweep.maxEnumerationSteps else {
                    stopReason = stopReason
                        ?? "device-config enumeration hit its cap of \(ConfigKeySweep.maxEnumerationSteps) entries; the rest of the plan still ran"
                    enumPhase = 2
                    return nil
                }
                enumSteps += 1
                return Step(opcode: ConfigKeySweep.sendNextDeviceConfigCmd, key: "", group: .enumerate)
            default:
                return nil
            }
        case 1:
            // Discovery: one round-trip per VALUE verb, each against a key that verb has a reason to know.
            // 128 gets a flag NOOP writes; 121 gets the Broadcast-HR key NOOP has written since #181, so a
            // FAILURE there is evidence about the VERB, not about the key.
            let plan: [Step] = [
                Step(opcode: DeviceConfigReadProbe.getFeatureFlagValueCmd,
                     key: knownFlagKeys.first ?? DeviceConfigReadProbe.deviceConfigDiscoveryKey,
                     group: .discovery),
                Step(opcode: DeviceConfigReadProbe.getDeviceConfigValueCmd,
                     key: DeviceConfigReadProbe.deviceConfigDiscoveryKey,
                     group: .discovery),
            ]
            guard cursor < plan.count else { return nil }
            return plan[cursor]
        case 2:
            // Cross-namespace: each answering verb asked for the OTHER namespace's known-good key. Two
            // round-trips that settle whether the namespaces are actually separate.
            var plan: [Step] = []
            if featureFlagVerb == .answered {
                plan.append(Step(opcode: DeviceConfigReadProbe.getFeatureFlagValueCmd,
                                 key: DeviceConfigReadProbe.deviceConfigDiscoveryKey,
                                 group: .crossNamespace))
            }
            if deviceConfigVerb == .answered, let flag = knownFlagKeys.first {
                plan.append(Step(opcode: DeviceConfigReadProbe.getDeviceConfigValueCmd,
                                 key: flag, group: .crossNamespace))
            }
            guard cursor < plan.count else { return nil }
            return plan[cursor]
        case 3:
            let plan = knownKeyPlan
            guard cursor < plan.count else { return nil }
            let entry = plan[cursor]
            guard let verb = verb(for: entry.namespace) else { return nil }
            return Step(opcode: verb, key: entry.key, group: .knownKey)
        case 4:
            // Guessing is the FALLBACK. If the strap enumerated its own device-config keys there is
            // nothing to guess at in that namespace, so the sweep is skipped and said so in the report —
            // unless this run was explicitly asked to sweep anyway (see `runsCandidateSweep` for why a
            // successful enumeration does not close the question).
            //
            // EVERY candidate goes through EVERY answering verb, not through the one its `namespace`
            // field guesses. That field is an author's expectation, and the namespaces are now PROVEN
            // SEPARATE on hardware: 128 asked for a device-config key answers FAILURE, and 121 asked for a
            // feature-flag key answers FAILURE. So a candidate that really is a device-config key, asked
            // only through 128, comes back FAILURE and is indistinguishable from "no such key" — which
            // would have made a negative sweep worthless for exactly the names it most needed to settle.
            // Asking both costs one extra round-trip per name and is what lets a negative be called clean.
            guard runsCandidateSweep else { return nil }
            let verbs = candidateVerbs
            guard !verbs.isEmpty else { return nil }
            let idx = cursor / verbs.count
            guard idx < batch.candidates.count else { return nil }
            let candidate = batch.candidates[idx]
            return Step(opcode: verbs[cursor % verbs.count], key: candidate.key, group: .candidate,
                        derivation: candidate.derivation)
        default:
            return nil
        }
    }

    /// The VALUE verbs a candidate name is asked through — every one that answered, in a stable order
    /// (121 before 128) so the plan is deterministic. Empty when neither answered, which retires the sweep.
    private var candidateVerbs: [UInt8] {
        var verbs: [UInt8] = []
        if deviceConfigVerb == .answered { verbs.append(DeviceConfigReadProbe.getDeviceConfigValueCmd) }
        if featureFlagVerb == .answered { verbs.append(DeviceConfigReadProbe.getFeatureFlagValueCmd) }
        return verbs
    }

    /// The keys whose values are worth reading because they are already known to exist: the sixteen flags
    /// NOOP writes, then whatever the strap enumerated for itself (capped, and never re-listing a flag).
    private var knownKeyPlan: [(key: String, namespace: ConfigKeySweep.Namespace)] {
        var plan = knownFlagKeys.map { (key: $0, namespace: ConfigKeySweep.Namespace.featureFlag) }
        for key in enumeratedKeys.prefix(ConfigKeySweep.maxEnumeratedValueReads)
        where !knownFlagKeys.contains(key) {
            plan.append((key: key, namespace: .deviceConfig))
        }
        return plan
    }

    /// The verb to ask a key of, or nil when neither VALUE verb answered.
    ///
    /// A verb SHOWN in this same run to serve the other namespace too is preferred for everything — fewer
    /// moving parts, and the evidence is from this run rather than an assumption. Otherwise each namespace
    /// uses its own verb, falling back to the other one as a look worth taking.
    private func verb(for namespace: ConfigKeySweep.Namespace) -> UInt8? {
        if deviceConfigVerbOnFlagKey == .exists, deviceConfigVerb == .answered {
            return DeviceConfigReadProbe.getDeviceConfigValueCmd
        }
        if featureFlagVerbOnDeviceConfigKey == .exists, featureFlagVerb == .answered {
            return DeviceConfigReadProbe.getFeatureFlagValueCmd
        }
        let ff = featureFlagVerb == .answered ? DeviceConfigReadProbe.getFeatureFlagValueCmd : nil
        let dc = deviceConfigVerb == .answered ? DeviceConfigReadProbe.getDeviceConfigValueCmd : nil
        return namespace == .featureFlag ? (ff ?? dc) : (dc ?? ff)
    }

    // MARK: - Notes

    /// Record the `START_DEVICE_CONFIG_KEY_EXCHANGE` reply. An implausible count is reported but never
    /// trusted as a loop bound — the walk is bounded by `ConfigKeySweep.maxEnumerationSteps` and by the
    /// strap's own end marker.
    public mutating func noteEnumerationStart(_ r: FeatureFlagProbe.StartResponse) {
        enumeratedCount = r.count
        if r.resultCode == 3 {
            enumerationVerb = .unsupported
            enumPhase = 2
            trace.append("START_DEVICE_CONFIG_KEY_EXCHANGE(115) → result=UNSUPPORTED(3) — the firmware does not serve this verb")
            return
        }
        enumerationVerb = .answered
        enumPhase = 1
        var line = "START_DEVICE_CONFIG_KEY_EXCHANGE(115) →"
        if let c = r.resultCode { line += " result=\(FeatureFlagProbe.resultLabel(c))(\(c))" }
        line += " revision=\(r.revision) count=\(r.count)"
        if !r.countIsPlausible { line += " (implausible — walked to the strap's own end marker instead)" }
        trace.append(line)
    }

    /// Record one `SEND_NEXT_DEVICE_CONFIG` reply. Returns true when the walk should continue.
    ///
    /// Mirrors the #874 discipline on the 117/118 walk: the strap's own end marker terminates the walk,
    /// but a name OUR parser declines (`isSkippable`) is counted and stepped over — one undecodable entry
    /// must not throw away every key after it.
    @discardableResult
    public mutating func noteEnumerationNext(_ r: FeatureFlagProbe.NextResponse) -> Bool {
        if r.isExhausted {
            enumPhase = 2
            trace.append("SEND_NEXT_DEVICE_CONFIG(116) → end of list (index=\(r.index) validKey=\(r.validKey))")
            return false
        }
        if r.isSkippable {
            enumerationSkipped += 1
            trace.append("SEND_NEXT_DEVICE_CONFIG(116) → index=\(r.index) name did not decode — stepped over")
            return true
        }
        if let key = r.key {
            enumeratedKeys.append(key)
            trace.append("SEND_NEXT_DEVICE_CONFIG(116) → index=\(r.index) key=\"\(key)\"")
        }
        return true
    }

    /// Record one decoded VALUE reply.
    public mutating func noteReply(_ r: DeviceConfigReadProbe.ValueResponse, for step: Step) {
        setStatus(r.isUnsupported ? .unsupported : .answered, for: step.opcode)
        if step.group == .crossNamespace {
            if step.opcode == DeviceConfigReadProbe.getFeatureFlagValueCmd {
                featureFlagVerbOnDeviceConfigKey = r.existence
            } else if step.opcode == DeviceConfigReadProbe.getDeviceConfigValueCmd {
                deviceConfigVerbOnFlagKey = r.existence
            }
        }
        let value = r.value(for: step.key)
        readings.append(Reading(group: step.group, opcode: step.opcode, key: step.key, value: value,
                                resultCode: r.resultCode, recordHex: r.recordHex,
                                derivation: step.derivation))
        // The candidate section lists every name it asked, so repeating a plain "unknown" in the
        // transcript would double the report for no information. Anything else is always traced.
        guard step.group != .candidate || r.existence != .unknown else { return }
        var line = "\(DeviceConfigReadProbeReport.opcodeLabel(step.opcode)) key=\"\(step.key)\""
        if let c = r.resultCode { line += " → result=\(FeatureFlagProbe.resultLabel(c))(\(c))" } else { line += " →" }
        line += " \(r.existence.label)"
        if let v = value { line += " value=\(DeviceConfigReadProbe.valueLabel(v))" }
        line += " record=[\(r.recordHex)]"
        trace.append(line)
    }

    /// Record a reply that could not be decoded. The verb is marked undecodable, which retires it.
    public mutating func noteFailure(_ f: DeviceConfigReadProbe.ParseFailure, for step: Step) {
        let why: String
        switch f {
        case .crc:          why = "CRC failed — frame rejected (never decoded)"
        case .envelope:     why = "not a COMMAND_RESPONSE envelope"
        case .wrongCommand: why = "COMMAND_RESPONSE for a different command"
        case .truncated:    why = "record too short to hold a response"
        }
        setStatus(.undecodable, for: step.opcode)
        trace.append("\(DeviceConfigReadProbeReport.opcodeLabel(step.opcode)) key=\"\(step.key)\" reply not decoded: \(why)")
        stopReason = stopReason ?? why
    }

    /// Record the strap answering nothing at all within the per-step window. The verb is marked silent,
    /// which retires it — one no-reply must not cost another twenty timeouts.
    public mutating func noteTimeout(for step: Step, seconds: Int) {
        setStatus(.silent, for: step.opcode)
        trace.append("\(DeviceConfigReadProbeReport.opcodeLabel(step.opcode)) key=\"\(step.key)\" → no COMMAND_RESPONSE within \(seconds)s")
    }

    /// A verb's status only ever moves off `untried`; a later step never upgrades a verdict already
    /// reached, so one lucky reply after an UNSUPPORTED cannot rewrite the headline.
    private mutating func setStatus(_ s: VerbStatus, for opcode: UInt8) {
        switch opcode {
        case DeviceConfigReadProbe.getFeatureFlagValueCmd:
            if featureFlagVerb == .untried || featureFlagVerb == .answered { featureFlagVerb = s }
        case DeviceConfigReadProbe.getDeviceConfigValueCmd:
            if deviceConfigVerb == .untried || deviceConfigVerb == .answered { deviceConfigVerb = s }
        case ConfigKeySweep.startDeviceConfigKeyExchangeCmd, ConfigKeySweep.sendNextDeviceConfigCmd:
            if enumerationVerb == .untried || enumerationVerb == .answered { enumerationVerb = s }
            if s != .answered { enumPhase = 2 }
        default:
            break
        }
    }

    // MARK: - Render

    /// Short opcode label used in the transcript.
    static func opcodeLabel(_ opcode: UInt8) -> String {
        switch opcode {
        case DeviceConfigReadProbe.getDeviceConfigValueCmd: return "GET_DEVICE_CONFIG_VALUE(121)"
        case ConfigKeySweep.startDeviceConfigKeyExchangeCmd: return "START_DEVICE_CONFIG_KEY_EXCHANGE(115)"
        case ConfigKeySweep.sendNextDeviceConfigCmd: return "SEND_NEXT_DEVICE_CONFIG(116)"
        default: return "GET_FF_VALUE(128)"
        }
    }

    /// Candidate readings only.
    private var candidateReadings: [Reading] { readings.filter { $0.group == .candidate } }

    /// Every key name this run proved EXISTS that NOOP did not already have — the whole point of the
    /// exercise. Enumerated names count; so does any candidate the oracle confirmed.
    public var newKeysFound: [String] {
        var out = enumeratedKeys.filter {
            !knownFlagKeys.contains($0) && $0 != DeviceConfigReadProbe.deviceConfigDiscoveryKey
        }
        for r in candidateReadings where r.existence == .exists && !out.contains(r.key) { out.append(r.key) }
        return out
    }

    /// One-line summary of what the probe established.
    public var verdict: String {
        if !newKeysFound.isEmpty {
            return "\(newKeysFound.count) config key name(s) found that NOOP did not have: \(newKeysFound.joined(separator: ", "))"
        }
        // Only claim "enumeration settled it" when enumeration was in fact the whole run. A forced sweep
        // asked dozens of names as well, and its clean negative is the more informative headline.
        if enumerationVerb == .answered, candidateReadings.isEmpty {
            return "the strap enumerated its device-config namespace and returned no key NOOP did not already have"
        }
        let answered = [featureFlagVerb, deviceConfigVerb].filter { $0 == .answered }.count
        if answered == 0 {
            let both = "neither GET_FF_VALUE(128) nor GET_DEVICE_CONFIG_VALUE(121) is served by this firmware"
            if featureFlagVerb == .unsupported || deviceConfigVerb == .unsupported {
                return "\(both) — rejected as UNSUPPORTED"
            }
            if featureFlagVerb == .silent && deviceConfigVerb == .silent {
                return "\(both) — no reply to either"
            }
            return both
        }
        // Counted in NAMES, not round-trips: each name is asked through every answering verb, so the
        // headline would otherwise double. A name only counts as "does not exist" when EVERY verb that
        // asked it said so.
        var names: [String] = []
        for r in candidateReadings where !names.contains(r.key) { names.append(r.key) }
        let asked = names.count
        if asked == 0 {
            return "\(answered) of 2 read verbs answered; no candidate name was asked"
        }
        let unknown = names.filter { key in
            let rows = candidateReadings.filter { $0.key == key }
            return !rows.isEmpty && rows.allSatisfy { $0.existence == .unknown }
        }.count
        if unknown == asked {
            // A fully-negative sweep is worth more when enumeration ALSO answered: the device-config
            // namespace is then fully listed and the guessed names are all refused, which is a much
            // stronger negative than a sweep run against a strap that never listed anything.
            return enumerationVerb == .answered
                ? "asked \(asked) candidate key name(s); this firmware has none of them, and its device-config namespace enumerated in full (a clean negative)"
                : "asked \(asked) candidate key name(s); this firmware has none of them (a clean negative)"
        }
        return "asked \(asked) candidate key name(s); \(unknown) do not exist, \(asked - unknown) inconclusive"
    }

    /// The full copyable report.
    public func render() -> String {
        let fam = family == .whoop5 ? "WHOOP 5/MG" : "WHOOP 4.0"
        var sb = "#103 CONFIG KEY PROBE — \(fam)\n"
        sb += "Read-only: START_DEVICE_CONFIG_KEY_EXCHANGE(115), SEND_NEXT_DEVICE_CONFIG(116), "
        sb += "GET_DEVICE_CONFIG_VALUE(121), GET_FF_VALUE(128).\n"
        sb += "No value is written; SET_DEVICE_CONFIG_VALUE(119) and SET_FF_VALUE(120) are never sent from this path.\n"
        sb += "Oracle: result=SUCCESS(1) means the key NAME exists; result=FAILURE(0) means the firmware has no such key.\n"
        sb += "\nVerdict: \(verdict)\n"
        if let stopReason { sb += "Stopped: \(stopReason)\n" }

        sb += "\nVerbs:\n"
        sb += "  " + DeviceConfigReadProbe.padded("device-config enumerate(115/116)", to: 34)
            + enumerationVerb.rawValue + "\n"
        sb += "  " + DeviceConfigReadProbe.padded("GET_FF_VALUE(128)", to: 34) + featureFlagVerb.rawValue + "\n"
        sb += "  " + DeviceConfigReadProbe.padded("GET_DEVICE_CONFIG_VALUE(121)", to: 34)
            + deviceConfigVerb.rawValue + "\n"

        sb += enumerationSection()
        sb += namespaceSection()
        sb += section(.discovery,
                      title: "Discovery — one round-trip per value verb against a key it should know",
                      empty: "(none — no reply was decoded)")
        sb += section(.knownKey,
                      title: "Known key values (the flags NOOP writes, plus anything enumeration returned)",
                      empty: "(none — no value verb answered)")
        sb += candidateSection()

        sb += "\nExchange:\n"
        for line in trace { sb += "  " + line + "\n" }
        return sb
    }

    /// The strap's own device-config key list — the result that makes guessing unnecessary.
    private func enumerationSection() -> String {
        var sb = "\nDevice-config keys the strap listed for itself (115/116) (\(enumeratedKeys.count)):\n"
        if enumeratedKeys.isEmpty {
            switch enumerationVerb {
            case .unsupported:
                sb += "  (none — the firmware refused 115 as UNSUPPORTED)\n"
            case .silent:
                sb += "  (none — no reply to 115)\n"
            case .undecodable:
                sb += "  (none — the reply did not decode)\n"
            case .answered:
                sb += "  (none — 115 answered but the walk produced no names)\n"
            case .untried:
                sb += "  (none — not reached)\n"
            }
            return sb
        }
        for (i, key) in enumeratedKeys.enumerated() {
            sb += String(format: "  %2d. ", i + 1) + key + "\n"
        }
        if enumerationSkipped > 0 {
            sb += "  (\(enumerationSkipped) further entr(ies) the strap called real but whose name did not decode)\n"
        }
        if let c = enumeratedCount, c != enumeratedKeys.count + enumerationSkipped {
            sb += "  (the strap announced \(c); the walk served \(enumeratedKeys.count + enumerationSkipped))\n"
        }
        return sb
    }

    /// Whether the two namespaces are really separate — two round-trips that shape every future sweep.
    private func namespaceSection() -> String {
        var sb = "\nNamespace separation:\n"
        let ffLabel = featureFlagVerbOnDeviceConfigKey?.label ?? "not asked"
        let dcLabel = deviceConfigVerbOnFlagKey?.label ?? "not asked"
        sb += "  " + DeviceConfigReadProbe.padded("128 asked for a device-config key", to: 38) + ffLabel + "\n"
        sb += "  " + DeviceConfigReadProbe.padded("121 asked for a feature-flag key", to: 38) + dcLabel + "\n"
        switch (featureFlagVerbOnDeviceConfigKey, deviceConfigVerbOnFlagKey) {
        case (.some(.exists), _):
            sb += "  ⇒ GET_FF_VALUE(128) serves BOTH namespaces.\n"
        case (_, .some(.exists)):
            sb += "  ⇒ GET_DEVICE_CONFIG_VALUE(121) serves BOTH namespaces.\n"
        case (.some(.unknown), .some(.unknown)):
            sb += "  ⇒ the namespaces are separate: neither verb sees the other's keys.\n"
        default:
            sb += "  ⇒ inconclusive.\n"
        }
        return sb
    }

    /// The candidate sweep, grouped by derivation, with the tested/untested arithmetic spelled out.
    private func candidateSection() -> String {
        let rows = candidateReadings
        // NAMES, not round-trips: each name is now asked through every answering verb, so counting rows
        // would report "108 asked of 54 in the catalogue". The arithmetic has to stay in the same units
        // the catalogue is measured in or the untested figure goes negative and stops meaning anything.
        var seen: [String] = []
        for r in rows where !seen.contains(r.key) { seen.append(r.key) }
        let tested = seen.count
        let total = ConfigKeySweep.catalogue.count
        let untested = total - batch.start - tested
        var sb = "\nCandidate key names — GUESSES, never observed on a wire or in any table"
        if forceCandidateSweep {
            sb += " [FULL SWEEP: asked even though enumeration succeeded]"
        }
        sb += " (\(tested) asked of \(total) in the catalogue"
        sb += untested > 0 ? "; \(untested) untested" : "; none untested"
        sb += "):\n"
        if rows.isEmpty {
            if !enumeratedKeys.isEmpty && !forceCandidateSweep {
                sb += "  (skipped — the strap enumerated its own device-config keys, so nothing needs guessing.\n"
                sb += "   Enumeration lists what the firmware HOLDS, not everything it would ACCEPT, and says\n"
                sb += "   nothing about the feature-flag namespace: re-run with the full name sweep to ask anyway.)\n"
            } else if featureFlagVerb != .answered && deviceConfigVerb != .answered {
                sb += "  (none — no value verb answered, so no name could be asked)\n"
            } else {
                sb += "  (none asked)\n"
            }
            return sb
        }
        // A NAME exists if ANY verb said so, and is only "does not exist" when EVERY verb that asked said
        // so — the whole reason both verbs are asked.
        let exists = seen.filter { key in rows.contains { $0.key == key && $0.existence == .exists } }.count
        let unknown = seen.filter { key in
            let asked = rows.filter { $0.key == key }
            return !asked.isEmpty && asked.allSatisfy { $0.existence == .unknown }
        }.count
        sb += "  \(exists) exist · \(unknown) do not · \(tested - exists - unknown) inconclusive"
        sb += "  (each name asked through \(candidateVerbs.count) verb(s))\n"
        for derivation in ConfigKeySweep.Derivation.allCases {
            let names = seen.filter { key in rows.contains { $0.key == key && $0.derivation == derivation } }
            guard !names.isEmpty else { continue }
            sb += "\n  \(derivation.title) (\(names.count)):\n"
            for (i, key) in names.enumerated() {
                sb += String(format: "   %2d. ", i + 1) + DeviceConfigReadProbe.padded(key, to: 32)
                // Per-verb, so a name that answered differently on 121 and 128 is visible rather than
                // collapsed into one word.
                let asked = rows.filter { $0.key == key }
                sb += asked.map { r in
                    var cell = "\(r.opcode)=\(r.existence.label)"
                    if let v = r.value { cell += "(" + DeviceConfigReadProbe.valueLabel(v) + ")" }
                    return cell
                }.joined(separator: " · ")
                sb += "\n"
            }
        }
        if untested > 0 {
            sb += "\n  Run the probe again to continue from catalogue entry \(batch.nextCursor + 1).\n"
        }
        return sb
    }

    /// One rendered section of readings.
    private func section(_ group: Group, title: String, empty: String) -> String {
        let rows = readings.filter { $0.group == group }
        var sb = "\n\(title) (\(rows.count)):\n"
        if rows.isEmpty {
            sb += "  " + empty + "\n"
            return sb
        }
        for (i, r) in rows.enumerated() {
            var line = String(format: "  %2d. ", i + 1) + DeviceConfigReadProbe.padded(r.key, to: 32)
            if let v = r.value {
                line += "= " + DeviceConfigReadProbe.valueLabel(v)
            } else if let c = r.resultCode {
                line += "— no value (result=\(FeatureFlagProbe.resultLabel(c))(\(c)))"
            } else {
                line += "— no value (the reply did not echo the key)"
            }
            sb += line + "\n"
        }
        return sb
    }
}
