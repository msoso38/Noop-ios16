import Foundation

// OuraDriver: the transport-agnostic protocol state machine (architecture plan s1). It holds NO BLE
// handle: the app's OuraLiveSource owns the CBCentralManager / BluetoothGatt and feeds the driver
// only bytes + transition events. This is what makes the protocol headless-testable (no CoreBluetooth,
// no android.bluetooth anywhere in this package).
//
// Two entry points:
//   - nextStep(after:) -> [OuraCommand]   : given the last transition, return the commands to write.
//   - ingest(record:) -> [OuraEvent]      : given a parsed TLV record, return decoded events.
//   - ingestLiveHRPush(body:) -> [OuraEvent] : given a 0x2F sub-op 0x28 push body, return live HR.
//
// The flow mirrors OURA_PROTOCOL.md s3 (auth) + s5 (live HR / fetch): scan -> connect -> notify ->
// auth (nonce, proof) -> enable live HR (gen-appropriate triplet) -> stream. RingGen swaps the
// command set, not the code path.
//
// Tier discipline: Tier-B decoders are present but gated behind `allowTierB` (default false). When
// false, a Tier-B tag decodes to nothing (the event is dropped), so Tier-B values can never feed
// scoring silently. Per the brief's TIER DISCIPLINE and OURA_PROTOCOL.md s7.3.

/// A transport-level transition the app reports to the driver to advance the flow. The driver answers
/// with the next batch of commands. This keeps all BLE specifics (CBPeripheral, GATT callbacks) in
/// the app and all protocol specifics here.
public enum OuraTransition: Equatable, Sendable {
    /// Service + characteristics discovered and notifications enabled on ...0003. Begin auth.
    case ready
    /// A 15-byte nonce arrived (from the GetAuthNonce response). Compute + submit the proof.
    case nonceReceived([UInt8])
    /// The auth handshake completed with this status. On success, begin enabling live HR.
    case authCompleted(OuraAuthStatus)
    /// A live-HR enable/subscribe ACK arrived; advance the triplet (or, when done, mark streaming).
    case enableAckReceived
    /// The app wants to fetch buffered history from this cursor (optional path).
    case startHistoryFetch(cursor: UInt32)
    /// The last GetEvents response advanced the cursor to this value; continue or stop.
    case historyCursorAdvanced(cursor: UInt32, moreData: Bool)
}

/// The driver's coarse phase, exposed for the app and tests to assert on.
public enum OuraDriverPhase: Equatable, Sendable {
    case idle
    case authenticating
    case enablingLiveHR
    case streaming
    case fetchingHistory
    case needsKeyInstall      // ring is in factory reset; honest pairing path (s3.5 status 0x02)
    case installingKey        // post-factory-reset key install in flight (s3.2); awaiting the 0x25 ack
    case authFailed(OuraAuthStatus)
    case stopped
}

public final class OuraDriver {
    public let ringGen: OuraRingGen
    /// The 16-byte application auth key (injected, never hardcoded). nil drives the honest
    /// needs-pairing path (the app surfaces "needsPairing" instead of faking data, Huami precedent).
    private let authKey: [UInt8]?
    /// When false (default), Tier-B (UNVERIFIED) tags decode to nothing so they can never feed scoring.
    public let allowTierB: Bool
    /// When false (default), the driver MUST NOT sequence a post-factory-reset key install: it stays at
    /// needsKeyInstall and writes nothing dangerous. Only an explicit opt-in adopt flow sets this true.
    /// Per OURA_PROTOCOL.md s3.2 (the 0x24 SetAuthKey is a DANGEROUS, one-time provisioning write).
    public let allowKeyInstall: Bool

    public private(set) var phase: OuraDriverPhase = .idle
    /// Tracks how many of the live-HR enable triplet ACKs have been seen.
    private var liveHREnableStep = 0
    /// The most recent ring time seen on any record, used to stamp live-HR pushes (which are not TLV
    /// records and carry no timestamp of their own).
    private var lastRingTimestamp: UInt32 = 0
    /// Ring-time -> UTC anchor (OURA_PROTOCOL.md s5.5): the ring's clock ticks at 100 ms/tick by default
    /// (burst-mode 1 ms/tick, s5.5, is NOT modeled in v1). Set from the ring's own 0x42 time-sync event
    /// (primary) or, only while no 0x42 has arrived yet THIS session, the coarser 1s-granularity 0x85 RTC
    /// beacon (secondary). nil until the first anchor event of this session: a record decoded before then
    /// has no computable UTC time, and `unixSeconds(forRingTimestamp:)` honestly returns nil rather than
    /// guessing. A stale anchor from a PREVIOUS session is never reused - the ring may have rebooted.
    private var anchorUtcMs: Int64?
    private var anchorRingTime: UInt32?
    /// The freshly-provisioned key the transport generated during an adopt flow (s3.2). Once set by
    /// beginKeyInstall it becomes the effective key for the post-install re-auth. nil otherwise.
    private var installedKey: [UInt8]?

    /// The key the auth handshake should use: the freshly-installed key takes precedence over the
    /// injected one (so re-auth after a key install uses the new key). Per OURA_PROTOCOL.md s3.2.
    private var effectiveKey: [UInt8]? { installedKey ?? authKey }

    public init(ringGen: OuraRingGen, authKey: [UInt8]?, allowTierB: Bool = false,
                allowKeyInstall: Bool = false) {
        self.ringGen = ringGen
        self.authKey = authKey
        self.allowTierB = allowTierB
        self.allowKeyInstall = allowKeyInstall
    }

    // MARK: - Command flow

    /// Given the last transport transition, return the commands the app should write next. Pure: it
    /// only mutates the driver's own phase, never touches BLE. Per OURA_PROTOCOL.md s3 / s5.
    public func nextStep(after transition: OuraTransition) -> [OuraCommand] {
        switch transition {
        case .ready:
            // No app key -> we cannot authenticate; surface the honest pairing path (no faked data).
            guard effectiveKey != nil else {
                phase = .needsKeyInstall
                return []
            }
            phase = .authenticating
            // Enable notifications, then request the auth nonce. SyncTime can follow auth.
            return [OuraCommands.enableAllNotifications(),
                    OuraCommand(label: "get_nonce", bytes: OuraAuth.getAuthNonceCommand())]

        case .nonceReceived(let nonce):
            guard let key = effectiveKey else {
                phase = .needsKeyInstall
                return []
            }
            // Compute the proof and submit it. On any crypto error, fail honestly (no proof sent).
            guard let cmd = try? OuraAuth.authenticateCommand(nonce: nonce, key: key) else {
                phase = .authFailed(.authError)
                return []
            }
            return [OuraCommand(label: "submit_proof", bytes: cmd)]

        case .authCompleted(let status):
            switch status {
            case .success:
                phase = .enablingLiveHR
                liveHREnableStep = 0
                // Begin the live-HR enable triplet (gen-appropriate; gen3 verified, gen4/5 same path).
                return [OuraCommands.liveHREnableSequence()[0]]
            case .inFactoryReset:
                // Ring needs a key install first; this is an explicit, named provisioning step the app
                // drives, not the normal flow. Surface honestly.
                phase = .needsKeyInstall
                return []
            case .authError, .notOriginalDevice:
                phase = .authFailed(status)
                return []
            }

        case .enableAckReceived:
            guard phase == .enablingLiveHR else { return [] }
            liveHREnableStep += 1
            let seq = OuraCommands.liveHREnableSequence()
            if liveHREnableStep < seq.count {
                return [seq[liveHREnableStep]]
            }
            // All three ACKed: HR/IBI now streams as 0x2F sub-op 0x28 pushes.
            phase = .streaming
            return []

        case .startHistoryFetch(let cursor):
            phase = .fetchingHistory
            // Flush flash buffer first, then fetch up to 255 events from the cursor (s5.3).
            return [OuraCommands.flushBuffer(),
                    OuraCommands.getEvents(cursor: cursor, maxEvents: 255)]

        case .historyCursorAdvanced(let cursor, let moreData):
            guard moreData else {
                phase = .streaming
                return []
            }
            // Ack-fetch (max=0) at the new cursor advances without re-pulling data (s5.3 step 4).
            return [OuraCommands.getEvents(cursor: cursor, maxEvents: 0)]
        }
    }

    /// Re-engage live HR (daytime-HR auto-reverts after ~20 s; the app calls this every ~15 s while a
    /// live session is open). Per OURA_PROTOCOL.md s5.7. Returns the enable+subscribe commands.
    public func reengageLiveHRCommands() -> [OuraCommand] {
        [OuraCommands.liveHREnable(), OuraCommands.liveHRSubscribe()]
    }

    // MARK: - Post-factory-reset key install (adopt flow, s3.2)

    /// Begin the one-time post-factory-reset key install (OURA_PROTOCOL.md s3.2). The transport in the
    /// adopt flow generates a fresh 16-byte key, persists it, and calls this to obtain the dangerous
    /// `24 10 <key>` write; once the ring replies `25 01 00` the transport calls keyInstallAcknowledged
    /// to drive re-auth.
    ///
    /// SAFETY GATE: this only sequences an install when phase == needsKeyInstall AND allowKeyInstall is
    /// true. When allowKeyInstall is false it stays at needsKeyInstall and returns no commands, so the
    /// dangerous 0x24 write is never emitted outside an explicit opt-in adopt flow. Returns nil (and
    /// leaves phase unchanged) when not gated on, the key length is wrong, or the command cannot build.
    public func beginKeyInstall(key: [UInt8]) -> OuraCommand? {
        guard allowKeyInstall, phase == .needsKeyInstall else { return nil }
        guard let cmd = try? OuraDangerousCommands.installKey(key) else { return nil }
        installedKey = key                 // re-auth after the ack must use the freshly-provisioned key
        phase = .installingKey
        return cmd
    }

    /// Handle the ring's 0x25 SetAuthKey ack (`25 01 00`, s3.2) by driving re-auth with the freshly
    /// installed key: transition installingKey -> authenticating and return the same enable+nonce
    /// commands the ready path uses. Returns [] (phase unchanged) when not in installingKey or when no
    /// installed key is present, so a stray ack cannot advance the flow.
    public func keyInstallAcknowledged() -> [OuraCommand] {
        guard phase == .installingKey, installedKey != nil else { return [] }
        phase = .authenticating
        return [OuraCommands.enableAllNotifications(),
                OuraCommand(label: "get_nonce", bytes: OuraAuth.getAuthNonceCommand())]
    }

    /// Stop: reset the flow so a fresh session re-runs auth (the app key is session-scoped, s3.1).
    public func stop() {
        phase = .stopped
        liveHREnableStep = 0
        lastRingTimestamp = 0
        installedKey = nil
        anchorUtcMs = nil
        anchorRingTime = nil
    }

    // MARK: - Ring-time -> UTC anchor (s5.5)

    /// Convert a record's ring-clock timestamp to unix seconds using the current session's anchor
    /// (OURA_PROTOCOL.md s5.5). Returns nil when no anchor has arrived yet this session, so the caller
    /// can honestly fall back (e.g. to wall-clock arrival time) instead of guessing.
    /// Whether a ring-time→UTC anchor has arrived this session. Lets a caller tell the TWO reasons
    /// `unixSeconds(forRingTimestamp:)` returns nil apart: `false` ⇒ no anchor yet (park / honest
    /// wall-clock fallback is OK); `true` ⇒ an anchor EXISTS but the ring timestamp was rejected as
    /// phantom/out-of-window, so the record must be DROPPED, never stamped at wall-clock (honest-data).
    public var hasAnchor: Bool { anchorUtcMs != nil && anchorRingTime != nil }

    public func unixSeconds(forRingTimestamp rt: UInt32) -> Int? {
        guard let anchorUtcMs, let anchorRingTime else { return nil }
        let deltaTicks = Int64(rt) - Int64(anchorRingTime)
        let ms = anchorUtcMs + deltaTicks * 100   // default 100 ms/tick (s5.5); bounded input, no overflow
        // #968: a corrupt/misaligned ring timestamp (seen on a full cursor=0 history dump) can convert to
        // an implausible epoch. Gate the RESULT to the same 2020-2035 plausible window used for anchoring
        // (was a weak `ms > 0`), so the caller honestly falls back to arrival time instead of banking a
        // 1970 or far-future sample.
        let seconds = ms / 1000
        guard seconds >= Self.minPlausibleEpochSeconds, seconds <= Self.maxPlausibleEpochSeconds else { return nil }
        // Phantom-record guard (anchor-relative). The absolute 2020-2035 gate above is far too loose to
        // catch a MISFRAMED record: a framing desync on a 0x43 debug byte mints a record with a GARBAGE
        // ring timestamp that, at 100 ms/tick, lands days-to-years from the anchor yet still INSIDE
        // 2020-2035 - e.g. rt ~= 1.9e9 converts to +6 years (2033), rt ~= 16.7M to +19 days. Every such
        // sample was persisted with a bogus date, scattering real streams across the calendar (observed:
        // ~25% of a night's skin-temp rows landed in 2020-2034). A genuine history-fetched sample is
        // ALWAYS in the recent past relative to the session anchor (which reflects ~now via the 0x42
        // time-sync): at most a small clock-skew margin AFTER it, and at most the ring's history depth
        // BEFORE it. Reject anything outside that window so the caller drops/parks it instead of banking a
        // mis-dated row (honest-data invariant). This is the single chokepoint every history sample passes.
        let anchorSeconds = anchorUtcMs / 1000
        guard seconds <= anchorSeconds + Self.maxFutureAnchorOffsetSeconds,
              seconds >= anchorSeconds - Self.maxPastAnchorOffsetSeconds else { return nil }
        return Int(seconds)
    }

    /// Anchor-relative plausibility window for a history-fetched sample (phantom-record guard, above).
    /// History is always in the recent past relative to the session anchor (≈ now): a real sample can be
    /// at most a clock-skew/timezone margin AFTER the anchor (+1 day) and at most the ring's history depth
    /// BEFORE it (−90 days, generous - the ring's flash cannot bank months of multi-stream 30 s data). A
    /// misframed record's garbage ring timestamp lands far outside this and is dropped. Tunable if a real
    /// deep-backlog capture ever proves the past bound too tight.
    private static let maxFutureAnchorOffsetSeconds: Int64 = 86_400        // +1 day
    private static let maxPastAnchorOffsetSeconds: Int64 = 7_776_000       // −90 days

    /// Bounds for a plausible anchor epoch (unix seconds): 2020-01-01 to 2035-01-01. A decoded 0x42/0x85
    /// value outside this range is a corrupt/misaligned record (seen on real hardware: a full cursor=0
    /// history dump hit one deep in the backlog) and is never trusted as an anchor (honest-data invariant).
    /// This gate ALSO bounds the input to the seconds->ms `* 1000` conversion so it can never overflow
    /// Int64 (a naive multiply on a near-Int64.max raw value traps).
    private static let minPlausibleEpochSeconds: Int64 = 1_577_836_800
    private static let maxPlausibleEpochSeconds: Int64 = 2_051_222_400

    private static func plausibleAnchorMs(fromEpochSeconds seconds: Int64) -> Int64? {
        guard seconds >= minPlausibleEpochSeconds, seconds <= maxPlausibleEpochSeconds else { return nil }
        return seconds * 1000   // safe: bounded input, cannot overflow
    }

    /// Ceiling for a plausible anchor RING-TIME (100 ms ticks, boot-relative): 500M ticks ≈ 579 days of
    /// uptime. The epoch gate alone is not enough — a misframed 0x42/0x85 can carry a plausible epoch
    /// (2020-2035) yet a GARBAGE ring-time (the 4 rt bytes read off a wrong offset land in the billions).
    /// Anchoring on that pins `anchorRingTime` to a value ~2e9 ticks away from every real sample, so the
    /// anchor-relative phantom guard then drops ALL genuine history — the ring's data starves even though
    /// the epoch looked fine. A consumer ring reboots/charges (resetting its clock) long before 579 days,
    /// so a ring-time above this in an anchor record is corrupt and never trusted. Mirrors the
    /// `maxPlausibleResumeTicks` ceiling OuraLiveSource applies to the persisted resume cursor.
    private static let maxPlausibleAnchorRingTime: UInt32 = 500_000_000

    /// True when `seconds` falls inside the anchor plausibility window (2020-01-01 .. 2035-01-01), i.e. the
    /// `.timeSync` / `.rtcBeacon` ingest would accept it. A record whose epoch is outside this is silently
    /// ignored so a garbage value can't anchor history to ~1970. Exposed READ-ONLY so OuraLiveSource can log
    /// WHY an anchor was rejected (#91) without duplicating the bounds or reaching into anchor state. Pure.
    public static func isPlausibleAnchorEpoch(_ seconds: Int64) -> Bool {
        plausibleAnchorMs(fromEpochSeconds: seconds) != nil
    }

    /// True when `ringTime` is a plausible boot-relative ring-time for an anchor record (≤ the 579-day
    /// ceiling). A misframed anchor record can pass `isPlausibleAnchorEpoch` yet carry a garbage ring-time;
    /// this is the second half of the gate. Exposed READ-ONLY so OuraLiveSource can log WHY an anchor was
    /// rejected (#91) without duplicating the bound. Pure.
    public static func isPlausibleAnchorRingTime(_ ringTime: UInt32) -> Bool {
        ringTime <= maxPlausibleAnchorRingTime
    }

    // MARK: - Record ingest (decode)

    /// Decode one parsed TLV inner record into zero or more events. A malformed/short record (or an
    /// unknown tag) yields []. Tier-B tags yield [] unless allowTierB is set. Per OURA_PROTOCOL.md s6.
    public func ingest(record: OuraRecord) -> [OuraEvent] {
        lastRingTimestamp = record.ringTimestamp
        guard let tag = OuraEventTag(rawValue: record.type) else {
            // Unknown tag: decode to nothing, never a guessed value (honest-data invariant).
            return []
        }
        // Tier-B gate: when not explicitly allowed, drop the event so it cannot feed scoring.
        if tag.tier == .tierB && !allowTierB {
            return []
        }
        switch tag {
        // --- Tier A: HR / IBI ---
        case .ibiAmplitude:
            return (OuraDecoders.decodeIBIAmplitude(record) ?? []).map { OuraEvent.ibi($0) }
        case .greenIbiQuality:
            return (OuraDecoders.decodeGreenIBIQuality(record) ?? []).map { OuraEvent.ibi($0) }
        case .spo2IbiAmplitude:
            return (OuraDecoders.decodeSpO2IBI(record) ?? []).map { OuraEvent.ibi($0) }
        case .ibi:
            // The bare 0x44 IBI tag shares the bit-packed layout family; route through the same decoder.
            return (OuraDecoders.decodeIBIAmplitude(record) ?? []).map { OuraEvent.ibi($0) }
        case .greenIbiAmp:
            return (OuraDecoders.decodeIBIAmplitude(record) ?? []).map { OuraEvent.ibi($0) }

        // --- Tier A: HRV ---
        case .hrvRmssd:
            return (OuraDecoders.decodeHRV(record) ?? []).map { OuraEvent.hrv($0) }

        // --- Tier A: SpO2 ---
        case .spo2PerSample:
            return (OuraDecoders.decodeSpO2PerSample(record) ?? []).map { OuraEvent.spo2($0) }
        case .spo2Stable:
            if let s = OuraDecoders.decodeSpO2Stable(record) { return [.spo2(s)] }
            return []
        case .spo2Dc:
            return (OuraDecoders.decodeSpO2DC(record) ?? []).map { OuraEvent.spo2($0) }

        // --- Tier A: Temperature ---
        case .temp:
            return (OuraDecoders.decodeTemp(record) ?? []).map { OuraEvent.temp($0) }
        case .tempPeriod:
            if let t = OuraDecoders.decodeTempPeriod(record) { return [.temp(t)] }
            return []
        case .sleepTemp:
            return (OuraDecoders.decodeSleepTemp(record) ?? []).map { OuraEvent.temp($0) }

        // --- Tier A: Motion ---
        case .motionPeriod:
            return (OuraDecoders.decodeMotionPeriod(record) ?? []).map { OuraEvent.motion($0) }
        case .motion:
            // 0x47 motion_events: surfaced as state-free motion is out of v1 scope; decode to nothing
            // rather than guess the partial layout. Per OURA_PROTOCOL.md s6.13.
            return []

        // --- Tier A: Sleep phase (2-bit codes are verified) ---
        case .sleepPhase, .sleepPhaseAlt:
            return (OuraDecoders.decodeSleepPhase(record) ?? []).map { OuraEvent.sleepPhase($0) }

        // --- Tier A: Lifecycle / state / time ---
        case .timeSync:
            // Primary UTC anchor (s5.5): always wins over a secondary RTC-beacon anchor already set.
            guard let ts = OuraDecoders.decodeTimeSync(record) else { return [] }
            // UNIT CORRECTION (s6.11): the 0x42 wire value is unix SECONDS, not ms. OURA_PROTOCOL.md s6.11
            // cited it as ms from an unverified write-up; treating it as ms anchored history-fetched samples
            // to ~1970. The decoder stays a faithful byte-level parse of the documented layout (OuraTimeSync.
            // epochMs still names what the doc claims); the seconds->ms conversion lives here.
            // CRASH-SAFETY (s6.11): a full cursor=0 history dump can hit a 0x42 record with an implausible
            // raw value (a misaligned/corrupt record deep in the backlog); a naive `* 1000` overflows Int64
            // and traps. plausibleAnchorMs bounds-checks BEFORE multiplying, so an implausible value is
            // safely ignored (honest: never anchors to a garbage time) instead of crashing.
            // Both halves must be plausible: a plausible epoch paired with a garbage ring-time (misframed
            // record) would pin the anchor ~2e9 ticks off and starve all real history (see
            // maxPlausibleAnchorRingTime). Reject unless BOTH the epoch and the ring-time check out.
            if let ms = Self.plausibleAnchorMs(fromEpochSeconds: ts.epochMs),
               Self.isPlausibleAnchorRingTime(ts.ringTimestamp) {
                anchorUtcMs = ms
                anchorRingTime = ts.ringTimestamp
            }
            return [.timeSync(ts)]
        case .rtcBeacon:
            // Secondary UTC anchor (s5.5, 1s granularity): only fills in while no 0x42 anchor exists yet
            // this session, so a coarser beacon never overrides the primary time-sync anchor.
            guard let r = OuraDecoders.decodeRtcBeacon(record) else { return [] }
            if anchorUtcMs == nil, let ms = Self.plausibleAnchorMs(fromEpochSeconds: Int64(r.unixSeconds)),
               Self.isPlausibleAnchorRingTime(r.ringTimestamp) {
                anchorUtcMs = ms
                anchorRingTime = r.ringTimestamp
            }
            return [.rtcBeacon(r)]
        case .stateChange, .wearEvent:
            if let s = OuraDecoders.decodeState(record) { return [.state(s)] }
            return []
        case .debugText:
            if let t = OuraDecoders.decodeDebugText(record) {
                return [.debugText(ringTimestamp: record.ringTimestamp, text: t)]
            }
            return []
        case .ringStart:
            // 0x41 ring_start_ind: a lifecycle marker (the app uses it to invalidate the UTC anchor on
            // rt regression). It carries no biometric value, so emit nothing here. Per OURA_PROTOCOL.md
            // s5.5 / s6.15. The app observes ring-start via the record stream directly.
            return []

        // --- Tier B (only reached when allowTierB == true; otherwise dropped above) ---
        case .sleepSummary1, .sleepSummaryB, .sleepSummaryC, .sleepSummaryD, .sleepSummaryE, .sleepSummaryF:
            return [.tierB(OuraTierBSummary(tag: record.type, ringTimestamp: record.ringTimestamp,
                                            rawPayload: record.payload, kind: "sleep_summary"))]
        case .activityInfo:
            // Split out of the raw-bytes .tierB wrapper: this ONE activity tag has a plausible decode
            // formula (Decoders.decodeActivityInfo, third-party [oura-rs], PR #960 investigation). Still
            // Tier B - only reached behind allowTierB (gated above), and OuraStreamMapping never folds
            // .activityInfo into a durable stream. 0x51/0x52 summaries stay raw below.
            guard let info = OuraDecoders.decodeActivityInfo(record) else { return [] }
            return [.activityInfo(info)]
        case .activitySummary1, .activitySummary2:
            return [.tierB(OuraTierBSummary(tag: record.type, ringTimestamp: record.ringTimestamp,
                                            rawPayload: record.payload, kind: "activity"))]
        case .realSteps1, .realSteps2:
            return [.tierB(OuraTierBSummary(tag: record.type, ringTimestamp: record.ringTimestamp,
                                            rawPayload: record.payload, kind: "real_steps"))]
        case .spo2Smoothed:
            return [.tierB(OuraTierBSummary(tag: record.type, ringTimestamp: record.ringTimestamp,
                                            rawPayload: record.payload, kind: "spo2_smoothed"))]
        }
    }

    /// Convenience: ingest a whole notification value by reassembling records and decoding each. The
    /// caller passes a fresh notification value; the supplied reassembler buffers partial trailing
    /// bytes across calls. Per OURA_PROTOCOL.md s2.4.
    public func ingest(notification value: [UInt8], reassembler: OuraReassembler) -> [OuraEvent] {
        var out: [OuraEvent] = []
        for rec in reassembler.feed(value) {
            out.append(contentsOf: ingest(record: rec))
        }
        return out
    }

    /// Decode a live-HR push (0x2F sub-op 0x28). The body is the bytes AFTER `2f 0f 28`; the push is
    /// not a TLV record, so it is stamped with the last seen ring time. Per OURA_PROTOCOL.md s5.6.
    public func ingestLiveHRPush(body: [UInt8]) -> [OuraEvent] {
        guard let hr = OuraDecoders.decodeLiveHRPush(body, ringTimestamp: lastRingTimestamp) else {
            return []
        }
        // The push also carries the IBI; surface both so HRV analytics see the R-R.
        return [.hr(hr), .ibi(OuraIBI(ringTimestamp: lastRingTimestamp, ibiMs: hr.ibiMs))]
    }

    /// Route a parsed secure sub-frame: extract the auth nonce / status, or a live-HR push body, so
    /// the app does not need to know the 0x2F sub-op map. Returns the matching transition or push
    /// events. Per OURA_PROTOCOL.md s4.2 / s5.6.
    public func handleSecureFrame(_ frame: OuraSecureFrame) -> SecureRouting {
        if let nonce = OuraAuth.nonce(from: frame) {
            return .nonce(nonce)
        }
        if let status = OuraAuth.authStatus(from: frame) {
            return .authStatus(status)
        }
        // Sub-op 0x28 carries the live-HR push samples (s5.6). subBody is everything after the subop.
        if frame.subop == 0x28 {
            return .liveHRPush(frame.subBody)
        }
        // Live-HR enable ACKs advance the triplet (s5.6): 0x21 is the dhr_read feature-read ACK from
        // step 1 (`2f 06 21 02 01 11 02 00`), 0x23 acks the enable write (step 2), 0x27 acks the
        // subscribe write (step 3). All three must be recognised or the sequencer stalls at step 0.
        if frame.subop == 0x21 || frame.subop == 0x23 || frame.subop == 0x27 {
            return .enableAck
        }
        return .unhandled
    }

    /// What handleSecureFrame resolved a 0x2F sub-frame to.
    public enum SecureRouting: Equatable {
        case nonce([UInt8])
        case authStatus(OuraAuthStatus)
        case liveHRPush([UInt8])
        case enableAck
        case unhandled
    }
}
