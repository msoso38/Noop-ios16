import XCTest
@testable import WhoopProtocol

/// #103: the read-only device-config read probe's allowlist, request shape, parse and plan contract.
///
/// Fixtures are SYNTHETIC and built with real CRCs by the two helpers below (the WHOOP 4.0 harvard
/// envelope and the 5/MG puffin envelope). No strap has ever answered opcode 121 or 128 in this
/// project's hands — establishing whether one does is what the probe is for — so these tests pin the
/// decode, the plan and the report, including every "the verb is not implemented" path the BLE handler
/// must survive.
final class DeviceConfigReadProbeTests: XCTestCase {

    // MARK: - Frame builders (mirror the two envelopes verifyFrame(_:family:) validates)

    /// WHOOP 4.0 COMMAND_RESPONSE: [0xAA][len u16 LE][crc8(len)][type=36][seq][cmd][payload…][crc32 LE].
    private func whoop4Response(cmd: UInt8, payload: [UInt8], seq: UInt8 = 1) -> [UInt8] {
        let inner: [UInt8] = [36, seq, cmd] + payload
        let length = UInt16(inner.count + 4)
        let lenBytes: [UInt8] = [UInt8(length & 0xFF), UInt8(length >> 8)]
        var frame: [UInt8] = [0xAA] + lenBytes + [crc8(lenBytes)] + inner
        let c = crc32(inner)
        frame += [UInt8(c & 0xFF), UInt8((c >> 8) & 0xFF), UInt8((c >> 16) & 0xFF), UInt8((c >> 24) & 0xFF)]
        return frame
    }

    /// WHOOP 5/MG COMMAND_RESPONSE in the puffin envelope: type @8, seq @9, cmd @10, record from @11.
    private func whoop5Response(cmd: UInt8, payload: [UInt8], seq: UInt8 = 1) -> [UInt8] {
        var inner: [UInt8] = [36, seq, cmd] + payload
        let pad = (4 - inner.count % 4) % 4
        if pad > 0 { inner += [UInt8](repeating: 0, count: pad) }
        let declLen = inner.count + 4
        var frame: [UInt8] = [0xAA, 0x01, UInt8(declLen & 0xFF), UInt8((declLen >> 8) & 0xFF), 0x00, 0x01]
        let c16 = crc16Modbus(Array(frame[0..<6]))
        frame += [UInt8(c16 & 0xFF), UInt8((c16 >> 8) & 0xFF)]
        frame += inner
        let c32 = crc32(inner)
        frame += [UInt8(c32 & 0xFF), UInt8((c32 >> 8) & 0xFF), UInt8((c32 >> 16) & 0xFF), UInt8((c32 >> 24) & 0xFF)]
        return frame
    }

    /// The 2-byte response header every COMMAND_RESPONSE carries ahead of its record, then the record.
    private func payload(result: UInt8, record: [UInt8]) -> [UInt8] { [0x0A, result] + record }

    /// A record shaped like the SET side's body: the key NUL-padded to 32 bytes, then the value byte.
    private func echoRecord(_ key: String, value: UInt8, lead: [UInt8] = []) -> [UInt8] {
        var field = [UInt8](repeating: 0, count: DeviceConfigReadProbe.nameFieldBytes)
        let bytes = Array(key.utf8)
        for i in 0..<min(field.count, bytes.count) { field[i] = bytes[i] }
        return lead + field + [value]
    }

    private var flagKeys: [String] { Whoop5Config.enableR22Sequence.map(\.name) }

    // MARK: - The read-only allowlist (the hard safety constraint)

    func testAllowlistAdmitsOnlyTheFourReadVerbs() {
        XCTAssertEqual(DeviceConfigReadProbe.getDeviceConfigValueCmd, 121)                // 0x79
        XCTAssertEqual(DeviceConfigReadProbe.getFeatureFlagValueCmd, 128)                 // 0x80
        XCTAssertEqual(ConfigKeySweep.startDeviceConfigKeyExchangeCmd, 115)               // 0x73
        XCTAssertEqual(ConfigKeySweep.sendNextDeviceConfigCmd, 116)                       // 0x74
        XCTAssertEqual(DeviceConfigReadProbe.readOnlyOpcodes, [115, 116, 121, 128])
        for op in DeviceConfigReadProbe.readOnlyOpcodes {
            XCTAssertTrue(DeviceConfigReadProbe.isReadOnlyOpcode(op))
        }
    }

    /// The load-bearing safety test: the two config WRITE verbs must be rejected by the same predicate
    /// the BLE send path consults while a probe is in flight.
    func testAllowlistRejectsBothConfigWriteVerbs() {
        XCTAssertEqual(DeviceConfigReadProbe.setDeviceConfigValueCmd, 119)   // 0x77
        XCTAssertEqual(DeviceConfigReadProbe.setFeatureFlagValueCmd, 120)    // 0x78
        XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(119), "SET_DEVICE_CONFIG_VALUE must never pass")
        XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(120), "SET_FF_VALUE must never pass")
        XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(Whoop5Config.setConfigCmd))       // 0x78
        XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(Whoop5Config.setDeviceConfigCmd)) // 0x77
        for op in DeviceConfigReadProbe.writeOpcodes {
            XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(op))
        }
        XCTAssertTrue(DeviceConfigReadProbe.readOnlyOpcodes.isDisjoint(with: DeviceConfigReadProbe.writeOpcodes))
    }

    /// Nothing outside the four passes either — including the feature-flag enumerate verbs #872 owns
    /// (they have their own probe and their own gate) and the destructive opcodes that must never come
    /// near this path.
    func testAllowlistRejectsEveryOtherOpcode() {
        var rejected = 0
        for op in UInt8.min...UInt8.max where !DeviceConfigReadProbe.readOnlyOpcodes.contains(op) {
            XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(op), "opcode \(op) must not pass")
            rejected += 1
        }
        XCTAssertEqual(rejected, 252, "four admitted, every other opcode rejected")
        XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(FeatureFlagProbe.startKeyExchangeCmd))
        XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(FeatureFlagProbe.sendNextFlagCmd))
        XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(25))   // FORCE_TRIM
        XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(29))   // REBOOT_STRAP
        XCTAssertFalse(DeviceConfigReadProbe.isReadOnlyOpcode(32))   // POWER_CYCLE_STRAP
    }

    // MARK: - Request body

    func testRequestBodyIsTheB3ByteThenA32ByteNulPaddedName() {
        let body = DeviceConfigReadProbe.requestBody(key: "enable_spo2")
        XCTAssertEqual(body.count, 33, "b3 byte + a 32-byte name field, and no value byte")
        XCTAssertEqual(body[0], 0x01)
        XCTAssertEqual(Array(body[1..<12]), Array("enable_spo2".utf8))
        XCTAssertTrue(body[12...].allSatisfy { $0 == 0 }, "the rest of the name field is NUL padding")
        // It carries no value byte — that is exactly what separates it from the SET bodies.
        XCTAssertEqual(Whoop5Config.deviceConfigBody(name: "enable_spo2", value: 0x31).count, 33)
        XCTAssertNotEqual(Array(body.dropFirst()),
                          Whoop5Config.deviceConfigBody(name: "enable_spo2", value: 0x31))
    }

    func testRequestBodyTruncatesAnOverlongNameLikeTheSetSide() {
        let long = String(repeating: "z", count: 40)
        let body = DeviceConfigReadProbe.requestBody(key: long)
        XCTAssertEqual(body.count, 33)
        XCTAssertEqual(Array(body[1...]), [UInt8](repeating: 0x7A, count: 32))
    }

    // MARK: - Parsing

    func testParseDecodesTheRecordAndResultCodeOnWhoop5() {
        let record = echoRecord("enable_r22_packets", value: 0x32, lead: [0x01])
        let frame = whoop5Response(cmd: 128, payload: payload(result: 1, record: record))
        guard case .success(let r) = DeviceConfigReadProbe.parse(frame: frame, family: .whoop5, expecting: 128) else {
            return XCTFail("expected a decoded reply")
        }
        XCTAssertEqual(r.resultCode, 1)
        XCTAssertFalse(r.isUnsupported)
        XCTAssertEqual(r.value(for: "enable_r22_packets"), 0x32)
        XCTAssertEqual(r.echoOffset(of: "enable_r22_packets"), 1)
    }

    func testParseOnWhoop4LeavesTheResultCodeUnlabelled() {
        let frame = whoop4Response(cmd: 121, payload: payload(result: 1, record: echoRecord("k", value: 0x31)))
        guard case .success(let r) = DeviceConfigReadProbe.parse(frame: frame, family: .whoop4, expecting: 121) else {
            return XCTFail("expected a decoded reply")
        }
        XCTAssertNil(r.resultCode, "the result byte's meaning is only established on 5/MG")
        XCTAssertEqual(r.value(for: "k"), 0x31)
    }

    func testUnsupportedResultIsRecognised() {
        let frame = whoop5Response(cmd: 121, payload: payload(result: 3, record: [0x00, 0x00, 0x00]))
        guard case .success(let r) = DeviceConfigReadProbe.parse(frame: frame, family: .whoop5, expecting: 121) else {
            return XCTFail("expected a decoded reply")
        }
        XCTAssertTrue(r.isUnsupported)
        XCTAssertNil(r.value(for: "whatever"), "an UNSUPPORTED reply must never yield a value")
    }

    func testNoValueIsClaimedWhenTheReplyDoesNotEchoTheKey() {
        // A plausible-looking record that simply isn't the key we asked for.
        let frame = whoop5Response(cmd: 128, payload: payload(result: 1, record: echoRecord("some_other_key", value: 0x32)))
        guard case .success(let r) = DeviceConfigReadProbe.parse(frame: frame, family: .whoop5, expecting: 128) else {
            return XCTFail("expected a decoded reply")
        }
        XCTAssertNil(r.value(for: "enable_spo2"))
        XCTAssertNil(r.echoOffset(of: "enable_spo2"))
    }

    func testKeyMustSitInARealNulPaddedFieldNotJustAppearInTheBytes() {
        // "enable_spo2" appears, but immediately followed by more text — so it is not the 32-byte field.
        var record = Array("enable_spo2_extra_suffix_bytes__".utf8)   // exactly 32 bytes, no NUL padding
        record += [0x31]
        let frame = whoop5Response(cmd: 121, payload: payload(result: 1, record: record))
        guard case .success(let r) = DeviceConfigReadProbe.parse(frame: frame, family: .whoop5, expecting: 121) else {
            return XCTFail("expected a decoded reply")
        }
        XCTAssertNil(r.value(for: "enable_spo2"), "a substring match is not a name field")
    }

    /// On WHOOP 4.0, where the envelope adds no padding, a record that ends at the name field yields no
    /// value at all. (On 5/MG the puffin envelope pads the inner payload to a 4-byte boundary, so the
    /// same record would carry up to three trailing NULs that are envelope padding, not data — which is
    /// why `value(for:)` is only ever read as "the byte after the echoed field", never as "the last byte".)
    func testValueIsNotClaimedWhenTheRecordStopsAtTheNameField() {
        var field = [UInt8](repeating: 0, count: 32)
        for (i, b) in Array("enable_spo2".utf8).enumerated() { field[i] = b }
        let frame = whoop4Response(cmd: 121, payload: payload(result: 1, record: field))
        guard case .success(let r) = DeviceConfigReadProbe.parse(frame: frame, family: .whoop4, expecting: 121) else {
            return XCTFail("expected a decoded reply")
        }
        XCTAssertEqual(r.echoOffset(of: "enable_spo2"), 0)
        XCTAssertNil(r.value(for: "enable_spo2"), "nil is 'no value claimed', never 'value is zero'")
    }

    // MARK: - Failure paths (the handler must survive every one)

    func testBadCRCIsRejected() {
        var frame = whoop4Response(cmd: 121, payload: payload(result: 1, record: echoRecord("k", value: 0x31)))
        frame[frame.count - 1] ^= 0xFF          // corrupt the CRC32 trailer
        XCTAssertEqual(DeviceConfigReadProbe.parse(frame: frame, family: .whoop4, expecting: 121), .failure(.crc))

        var five = whoop5Response(cmd: 128, payload: payload(result: 1, record: echoRecord("k", value: 0x31)))
        five[7] ^= 0xFF                          // corrupt the CRC16 header
        XCTAssertEqual(DeviceConfigReadProbe.parse(frame: five, family: .whoop5, expecting: 128), .failure(.crc))

        XCTAssertEqual(DeviceConfigReadProbe.parse(frame: [], family: .whoop4, expecting: 121), .failure(.crc))
    }

    func testWrongCommandAndWrongTypeAreRejected() {
        let frame = whoop4Response(cmd: 128, payload: payload(result: 1, record: echoRecord("k", value: 0x31)))
        XCTAssertEqual(DeviceConfigReadProbe.parse(frame: frame, family: .whoop4, expecting: 121),
                       .failure(.wrongCommand))

        // Same bytes, but the packet type is COMMAND (35) rather than COMMAND_RESPONSE (36).
        let inner: [UInt8] = [35, 1, 121] + payload(result: 1, record: [0x01, 0x02])
        let length = UInt16(inner.count + 4)
        let lenBytes: [UInt8] = [UInt8(length & 0xFF), UInt8(length >> 8)]
        var wrong: [UInt8] = [0xAA] + lenBytes + [crc8(lenBytes)] + inner
        let c = crc32(inner)
        wrong += [UInt8(c & 0xFF), UInt8((c >> 8) & 0xFF), UInt8((c >> 16) & 0xFF), UInt8((c >> 24) & 0xFF)]
        XCTAssertEqual(DeviceConfigReadProbe.parse(frame: wrong, family: .whoop4, expecting: 121),
                       .failure(.envelope))
    }

    func testTruncatedRecordIsRejected() {
        let header = whoop4Response(cmd: 121, payload: [0x0A, 0x01])
        XCTAssertEqual(DeviceConfigReadProbe.parse(frame: header, family: .whoop4, expecting: 121),
                       .failure(.truncated))
    }


    // MARK: - Enumeration frame builders (the 117/118 record layouts, reused for 115/116)

    /// `START_DEVICE_CONFIG_KEY_EXCHANGE` reply: record = [revision][count u16 LE].
    private func enumStart(result: UInt8, revision: UInt8, count: UInt16) -> [UInt8] {
        whoop5Response(cmd: ConfigKeySweep.startDeviceConfigKeyExchangeCmd,
                       payload: payload(result: result,
                                        record: [revision, UInt8(count & 0xFF), UInt8(count >> 8)]))
    }

    /// `SEND_NEXT_DEVICE_CONFIG` reply: record = [revision][index][validKey][key ASCII NUL-terminated].
    private func enumNext(index: UInt8, key: String?, validKey: Bool = true,
                          result: UInt8 = 1) -> [UInt8] {
        var record: [UInt8] = [0x0A, index, validKey ? 1 : 0]
        if let key { record += Array(key.utf8) + [0] }
        return whoop5Response(cmd: ConfigKeySweep.sendNextDeviceConfigCmd,
                              payload: payload(result: result, record: record))
    }

    private func startReply(_ frame: [UInt8]) -> FeatureFlagProbe.StartResponse {
        guard case .success(let r) = FeatureFlagProbe.parseStart(
            frame: frame, family: .whoop5,
            expecting: ConfigKeySweep.startDeviceConfigKeyExchangeCmd) else {
            fatalError("fixture did not decode")
        }
        return r
    }

    private func nextReply(_ frame: [UInt8]) -> FeatureFlagProbe.NextResponse {
        guard case .success(let r) = FeatureFlagProbe.parseNext(
            frame: frame, family: .whoop5,
            expecting: ConfigKeySweep.sendNextDeviceConfigCmd) else {
            fatalError("fixture did not decode")
        }
        return r
    }

    /// A two-flag report with a two-name candidate slice — small enough to drive step by step.
    private func smallReport(limit: Int = 2) -> DeviceConfigReadProbeReport {
        DeviceConfigReadProbeReport(family: .whoop5,
                                    knownFlagKeys: ["enable_r22_packets", "hr_ch_switching"],
                                    batch: ConfigKeySweep.batch(from: 0, limit: limit))
    }

    // MARK: - The plan: enumerate first, guess last

    /// The whole point of the restructure: nothing is guessed until the strap has been asked to list its
    /// own keys.
    func testTheProbeAsksTheStrapToEnumerateBeforeItGuessesAnything() {
        var report = smallReport()
        guard let first = report.nextStep() else { return XCTFail("no first step") }
        XCTAssertEqual(first.opcode, ConfigKeySweep.startDeviceConfigKeyExchangeCmd)
        XCTAssertEqual(first.group, .enumerate)
        XCTAssertNil(first.derivation)
    }

    /// If the strap lists its own device-config keys there is nothing left to guess, so the sweep is
    /// skipped entirely rather than spending round-trips on names the answer already covers.
    func testAnAnsweringEnumerationSkipsTheGuessedSweepEntirely() {
        var report = smallReport()
        guard let s1 = report.nextStep() else { return XCTFail("s1") }
        report.noteEnumerationStart(startReply(enumStart(result: 1, revision: 10, count: 2)))
        XCTAssertEqual(s1.opcode, 115)

        guard let s2 = report.nextStep() else { return XCTFail("s2") }
        XCTAssertEqual(s2.opcode, ConfigKeySweep.sendNextDeviceConfigCmd)
        XCTAssertTrue(report.noteEnumerationNext(nextReply(enumNext(index: 1, key: "whoop_live_hr_in_adv_ind_pkt"))))

        guard report.nextStep() != nil else { return XCTFail("s3") }
        XCTAssertTrue(report.noteEnumerationNext(nextReply(enumNext(index: 2, key: "whoop_sleep_coach_enabled"))))

        guard report.nextStep() != nil else { return XCTFail("s4") }
        XCTAssertFalse(report.noteEnumerationNext(nextReply(enumNext(index: 0xFF, key: nil, validKey: false))))

        XCTAssertEqual(report.enumeratedKeys, ["whoop_live_hr_in_adv_ind_pkt", "whoop_sleep_coach_enabled"])
        XCTAssertEqual(report.enumerationVerb, .answered)
        // The Broadcast-HR key is one NOOP already writes, so only the second name is NEW.

        // Drive the rest of the plan; nothing may ever be a candidate step.
        var guard_ = 0
        while let step = report.nextStep(), guard_ < 200 {
            guard_ += 1
            XCTAssertNotEqual(step.group, .candidate, "the sweep must not run once enumeration answered")
            report.noteReply(.init(resultCode: 1, record: echoRecord(step.key, value: 0x32)), for: step)
        }
        XCTAssertTrue(report.render().contains("skipped — the strap enumerated its own device-config keys"))
        XCTAssertEqual(report.newKeysFound, ["whoop_sleep_coach_enabled"])
        XCTAssertTrue(report.verdict.hasPrefix("1 config key name(s) found that NOOP did not have"))
    }

    /// The #874 discipline, inherited: the strap's own end marker stops the walk, but a name OUR parser
    /// declines is counted and stepped over — one bad entry must not throw away every key after it.
    func testAnUndecodableNameIsSteppedOverRatherThanEndingTheWalk() {
        var report = smallReport()
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 1, revision: 10, count: 3)))
        _ = report.nextStep()
        // validKey = 1 but the name bytes are not printable ASCII, so `key` is nil: skippable, not the end.
        XCTAssertTrue(report.noteEnumerationNext(
            FeatureFlagProbe.NextResponse(resultCode: 1, revision: 10, index: 1, validKey: true, key: nil)))
        _ = report.nextStep()
        XCTAssertTrue(report.noteEnumerationNext(nextReply(enumNext(index: 2, key: "whoop_after_the_bad_one"))))
        XCTAssertEqual(report.enumeratedKeys, ["whoop_after_the_bad_one"])
        XCTAssertEqual(report.enumerationSkipped, 1)
    }

    /// A refused enumeration is the case the guessing fallback exists for — and it must cost exactly one
    /// round-trip, not one per key.
    func testAnUnsupportedEnumerationCostsOneRoundTripAndOpensTheFallback() {
        var report = smallReport()
        guard let s1 = report.nextStep() else { return XCTFail("s1") }
        XCTAssertEqual(s1.opcode, 115)
        report.noteEnumerationStart(startReply(enumStart(result: 3, revision: 0, count: 0)))
        XCTAssertEqual(report.enumerationVerb, .unsupported)

        guard let s2 = report.nextStep() else { return XCTFail("s2") }
        XCTAssertEqual(s2.opcode, DeviceConfigReadProbe.getFeatureFlagValueCmd,
                       "116 must not be asked once 115 refused")
        XCTAssertEqual(s2.group, .discovery)
    }

    /// A silent enumeration retires the pair after ONE timeout rather than one per entry.
    func testASilentEnumerationRetiresAfterOneTimeout() {
        var report = smallReport()
        guard let s1 = report.nextStep() else { return XCTFail("s1") }
        report.noteTimeout(for: s1, seconds: 8)
        XCTAssertEqual(report.enumerationVerb, .silent)
        guard let s2 = report.nextStep() else { return XCTFail("s2") }
        XCTAssertEqual(s2.group, .discovery)
        XCTAssertTrue(report.render().contains("(none — no reply to 115)"))
    }

    // MARK: - The verdict says only what the run established

    /// Every verb timing out is three timeouts, not a finding about firmware. `BLEManager.send` can
    /// `return` without transmitting at all — no `cmdCharacteristic`, or a 5/MG allowlist that does not
    /// carry the opcode — so a run in which nothing reached the strap must not print a claim about what
    /// the firmware serves.
    func testAWhollySilentRunNeverClaimsAFirmwareBehaviour() {
        var report = smallReport()
        while let step = report.nextStep() { report.noteTimeout(for: step, seconds: 8) }
        XCTAssertEqual(report.enumerationVerb, .silent)
        XCTAssertEqual(report.featureFlagVerb, .silent)
        XCTAssertEqual(report.deviceConfigVerb, .silent)
        XCTAssertEqual(report.verdict,
                       "no read verb answered — device-config enumerate(115/116) served no reply in 8s "
                       + "— unconfirmed; GET_FF_VALUE(128) served no reply in 8s — unconfirmed; "
                       + "GET_DEVICE_CONFIG_VALUE(121) served no reply in 8s — unconfirmed")
        XCTAssertFalse(report.render().contains("served by this firmware"),
                       "silence is not the firmware answering — it is not even proof a frame was sent")
    }

    /// One value verb refused and the other timed out: the refusal belongs to the verb that was refused.
    /// The old `||` printed "neither … is served by this firmware — rejected as UNSUPPORTED" over a 121
    /// that was never refused, only never heard from.
    func testOneRefusalIsNotGeneralisedToTheVerbThatWasNeverHeardFrom() {
        var report = smallReport()
        guard let s115 = report.nextStep() else { return XCTFail("115") }
        report.noteEnumerationStart(startReply(enumStart(result: 3, revision: 0, count: 0)))
        XCTAssertEqual(s115.opcode, 115)

        guard let s128 = report.nextStep() else { return XCTFail("128") }
        XCTAssertEqual(s128.opcode, DeviceConfigReadProbe.getFeatureFlagValueCmd)
        report.noteReply(.init(resultCode: 3, record: [0x00]), for: s128)

        guard let s121 = report.nextStep() else { return XCTFail("121") }
        XCTAssertEqual(s121.opcode, DeviceConfigReadProbe.getDeviceConfigValueCmd)
        report.noteTimeout(for: s121, seconds: 8)

        XCTAssertEqual(report.featureFlagVerb, .unsupported)
        XCTAssertEqual(report.deviceConfigVerb, .silent)
        XCTAssertEqual(report.verdict,
                       "no read verb answered — device-config enumerate(115/116) refused by firmware "
                       + "(UNSUPPORTED); GET_FF_VALUE(128) refused by firmware (UNSUPPORTED); "
                       + "GET_DEVICE_CONFIG_VALUE(121) served no reply in 8s — unconfirmed")
        XCTAssertFalse(report.render().contains("neither GET_FF_VALUE(128) nor GET_DEVICE_CONFIG_VALUE(121)"),
                       "one refusal does not speak for the other verb")
    }

    /// Both value verbs refused BY THE FIRMWARE is the one run that supports the strong sentence, so it
    /// keeps it.
    func testBothValueVerbsRefusedKeepsTheStrongSentence() {
        var report = smallReport()
        guard report.nextStep() != nil else { return XCTFail("115") }
        report.noteEnumerationStart(startReply(enumStart(result: 3, revision: 0, count: 0)))
        for _ in 0..<2 {
            guard let step = report.nextStep() else { return XCTFail("a value verb") }
            report.noteReply(.init(resultCode: 3, record: [0x00]), for: step)
        }
        XCTAssertEqual(report.featureFlagVerb, .unsupported)
        XCTAssertEqual(report.deviceConfigVerb, .unsupported)
        XCTAssertEqual(report.verdict,
                       "neither GET_FF_VALUE(128) nor GET_DEVICE_CONFIG_VALUE(121) is served by this "
                       + "firmware — rejected as UNSUPPORTED")
    }

    /// An undecodable reply is affirmative evidence the strap DID transmit, so "not served by this
    /// firmware" states the opposite of what the run observed.
    func testAnUndecodableReplyIsNotReportedAsUnserved() {
        var report = smallReport()
        guard let s115 = report.nextStep() else { return XCTFail("115") }
        report.noteFailure(.crc, for: s115)
        guard let s128 = report.nextStep() else { return XCTFail("128") }
        report.noteFailure(.envelope, for: s128)
        guard let s121 = report.nextStep() else { return XCTFail("121") }
        report.noteFailure(.truncated, for: s121)

        XCTAssertEqual(report.featureFlagVerb, .undecodable)
        XCTAssertEqual(report.deviceConfigVerb, .undecodable)
        XCTAssertEqual(report.verdict,
                       "no read verb answered — device-config enumerate(115/116) replied but the frame "
                       + "did not decode — unconfirmed; GET_FF_VALUE(128) replied but the frame did not "
                       + "decode — unconfirmed; GET_DEVICE_CONFIG_VALUE(121) replied but the frame did "
                       + "not decode — unconfirmed")
        XCTAssertFalse(report.render().contains("served by this firmware"))
    }

    /// An `inconclusive` enumeration is the strap ANSWERING and declining the request — evidence the verb
    /// exists. It must never be worded like a verb that was never heard from.
    func testAnInconclusiveEnumerationIsReportedAsAReplyNotAsSilence() {
        var report = smallReport()
        guard report.nextStep() != nil else { return XCTFail("115") }
        report.noteEnumerationStart(startReply(enumStart(result: 0, revision: 0, count: 0)))
        XCTAssertEqual(report.enumerationVerb, .inconclusive)
        while let step = report.nextStep() { report.noteTimeout(for: step, seconds: 8) }

        XCTAssertEqual(report.verdict,
                       "no read verb answered — device-config enumerate(115/116) replied but declined "
                       + "the request — unconfirmed; GET_FF_VALUE(128) served no reply in 8s — "
                       + "unconfirmed; GET_DEVICE_CONFIG_VALUE(121) served no reply in 8s — unconfirmed")
        XCTAssertFalse(report.render().contains("served by this firmware"))
    }

    /// A probe that ended before any verb went out says so, rather than reporting an empty run as a
    /// finding about the firmware.
    func testAProbeThatAskedNothingClaimsNothing() {
        let report = smallReport()
        XCTAssertEqual(report.verdict,
                       "no read verb answered — device-config enumerate(115/116) not asked; "
                       + "GET_FF_VALUE(128) not asked; GET_DEVICE_CONFIG_VALUE(121) not asked")
    }

    func testAnUndecodableEnumerationReplyRetiresIt() {
        var report = smallReport()
        guard let s1 = report.nextStep() else { return XCTFail("s1") }
        report.noteFailure(.crc, for: s1)
        XCTAssertEqual(report.enumerationVerb, .undecodable)
        guard let s2 = report.nextStep() else { return XCTFail("s2") }
        XCTAssertEqual(s2.group, .discovery)
        XCTAssertEqual(report.stopReason, "CRC failed — frame rejected (never decoded)")
    }

    // MARK: - Cross-namespace

    /// Two round-trips that settle whether the namespaces are separate — the result shapes every later
    /// sweep, so it is asked of each verb that answered.
    func testCrossNamespaceIsAskedOfEachAnsweringVerb() {
        var report = smallReport()
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 3, revision: 0, count: 0)))

        guard let d1 = report.nextStep() else { return XCTFail("d1") }
        report.noteReply(.init(resultCode: 1, record: echoRecord(d1.key, value: 0x32)), for: d1)
        guard let d2 = report.nextStep() else { return XCTFail("d2") }
        report.noteReply(.init(resultCode: 1, record: echoRecord(d2.key, value: 0x30)), for: d2)

        guard let x1 = report.nextStep() else { return XCTFail("x1") }
        XCTAssertEqual(x1.group, .crossNamespace)
        XCTAssertEqual(x1.opcode, DeviceConfigReadProbe.getFeatureFlagValueCmd)
        XCTAssertEqual(x1.key, DeviceConfigReadProbe.deviceConfigDiscoveryKey)
        report.noteReply(.init(resultCode: 0, record: []), for: x1)

        guard let x2 = report.nextStep() else { return XCTFail("x2") }
        XCTAssertEqual(x2.group, .crossNamespace)
        XCTAssertEqual(x2.opcode, DeviceConfigReadProbe.getDeviceConfigValueCmd)
        XCTAssertEqual(x2.key, "enable_r22_packets")
        report.noteReply(.init(resultCode: 0, record: []), for: x2)

        XCTAssertEqual(report.featureFlagVerbOnDeviceConfigKey, .unknown)
        XCTAssertEqual(report.deviceConfigVerbOnFlagKey, .unknown)
        XCTAssertTrue(report.render().contains("the namespaces are separate"))
    }

    /// If one verb turns out to serve both namespaces, everything afterwards goes through it — halving
    /// the work every future sweep needs, on evidence gathered in the same run.
    func testAVerbShownToServeBothNamespacesCarriesEverythingAfterwards() {
        var report = smallReport()
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 3, revision: 0, count: 0)))
        guard let d1 = report.nextStep() else { return XCTFail("d1") }
        report.noteReply(.init(resultCode: 1, record: echoRecord(d1.key, value: 0x32)), for: d1)
        guard let d2 = report.nextStep() else { return XCTFail("d2") }
        report.noteReply(.init(resultCode: 1, record: echoRecord(d2.key, value: 0x30)), for: d2)
        guard let x1 = report.nextStep() else { return XCTFail("x1") }
        report.noteReply(.init(resultCode: 0, record: []), for: x1)
        guard let x2 = report.nextStep() else { return XCTFail("x2") }
        // 121 DOES see a feature-flag key.
        report.noteReply(.init(resultCode: 1, record: echoRecord(x2.key, value: 0x32)), for: x2)
        XCTAssertEqual(report.deviceConfigVerbOnFlagKey, .exists)

        guard let k1 = report.nextStep() else { return XCTFail("k1") }
        XCTAssertEqual(k1.opcode, DeviceConfigReadProbe.getDeviceConfigValueCmd,
                       "the verb proved to serve both namespaces carries the rest of the plan")
        XCTAssertTrue(report.render().contains("GET_DEVICE_CONFIG_VALUE(121) serves BOTH namespaces."))
    }

    // MARK: - The sweep

    /// A fully-negative sweep is a RESULT, and the verdict must say so rather than reading like a failure.
    func testAFullyNegativeSweepIsACleanNegativeVerdict() {
        var (report, first) = driveToCandidates(limit: 2)
        guard var step: DeviceConfigReadProbeReport.Step = first else { return XCTFail("no candidate") }
        var asked: [String] = []
        while true {
            XCTAssertEqual(step.group, .candidate)
            asked.append(step.key)
            report.noteReply(.init(resultCode: 0, record: []), for: step)
            guard let next = report.nextStep() else { break }
            step = next
        }
        XCTAssertEqual(asked, ["enable_sig1", "enable_sig2"])
        XCTAssertEqual(report.verdict,
                       "asked 2 candidate key name(s); this firmware has none of them (a clean negative)")
        XCTAssertTrue(report.newKeysFound.isEmpty)
    }

    /// And a hit is the headline, named in the verdict so a strap log's first line carries the finding.
    func testACandidateThatExistsBecomesTheHeadline() {
        var (report, first) = driveToCandidates(limit: 2)
        guard let c1 = first else { return XCTFail("c1") }
        report.noteReply(.init(resultCode: 1, record: echoRecord(c1.key, value: 0x31)), for: c1)
        guard let c2 = report.nextStep() else { return XCTFail("c2") }
        report.noteReply(.init(resultCode: 0, record: []), for: c2)
        XCTAssertEqual(report.newKeysFound, ["enable_sig1"])
        XCTAssertEqual(report.verdict,
                       "1 config key name(s) found that NOOP did not have: enable_sig1")
        // A hit is always traced in full, unlike a plain "unknown".
        XCTAssertTrue(report.trace.contains { $0.contains("enable_sig1") && $0.contains("exists") })
        XCTAssertFalse(report.trace.contains { $0.contains("enable_sig2") })
    }

    /// No silent truncation: the report states how many names it asked, how many the catalogue holds, and
    /// how many remain untested, plus where the next run resumes.
    func testTheReportStatesTestedAndUntestedCountsAndWhereToResume() {
        var (report, first) = driveToCandidates(limit: 2)
        var step = first
        while let s = step {
            report.noteReply(.init(resultCode: 0, record: []), for: s)
            step = report.nextStep()
        }
        let text = report.render()
        let total = ConfigKeySweep.catalogue.count
        XCTAssertTrue(text.contains("(2 asked of \(total) in the catalogue; \(total - 2) untested)"), text)
        XCTAssertTrue(text.contains("Run the probe again to continue from catalogue entry 3."), text)
    }

    /// The default catalogue is smaller than one run's budget, so a real run reports nothing untested.
    func testAFullRunOfTodaysCatalogueLeavesNothingUntested() {
        var report = DeviceConfigReadProbeReport(family: .whoop5, knownFlagKeys: flagKeys,
                                                 batch: ConfigKeySweep.batch(from: 0))
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 3, revision: 0, count: 0)))
        var candidates = 0
        var steps = 0
        while let step = report.nextStep(), steps < DeviceConfigReadProbe.maxSteps {
            steps += 1
            if step.group == .candidate { candidates += 1 }
            report.noteReply(.init(resultCode: step.group == .candidate ? 0 : 1,
                                   record: echoRecord(step.key, value: 0x32)), for: step)
        }
        XCTAssertEqual(candidates, ConfigKeySweep.catalogue.count)
        XCTAssertNil(report.stopReason, "a full default run must not hit the safety cap")
        XCTAssertTrue(report.render().contains("none untested"))
    }

    /// The safety cap still binds, whatever the plan holds.
    func testThePlanIsCappedEvenWhenTheStrapEnumeratesForever() {
        var report = smallReport()
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 1, revision: 10, count: 9999)))
        var seen = 0
        while let step = report.nextStep(), seen < 500 {
            seen += 1
            if step.group == .enumerate {
                // A firmware whose cursor never advances: always a valid entry, never the end marker.
                _ = report.noteEnumerationNext(nextReply(enumNext(index: 1, key: "whoop_stuck")))
            } else {
                report.noteReply(.init(resultCode: 0, record: []), for: step)
            }
        }
        XCTAssertLessThanOrEqual(report.steps, DeviceConfigReadProbe.maxSteps)
        XCTAssertNotNil(report.stopReason)
    }

    /// Drive the plan with enumeration refused, stopping at the FIRST candidate step and handing it back
    /// alongside the report (a pulled step cannot be pushed back, so the helper must not swallow it).
    ///
    /// `control` is the result code every NON-candidate step is answered with. `1` is the calibrated run:
    /// the known-good keys exist, so a later FAILURE on a guessed name really does mean "no such key".
    /// `0` is the uncalibrated run, where even the control failed and the oracle has proved nothing.
    private func driveToCandidates(limit: Int, control: Int = 1)
        -> (report: DeviceConfigReadProbeReport, first: DeviceConfigReadProbeReport.Step?) {
        var report = smallReport(limit: limit)
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 3, revision: 0, count: 0)))
        while let step = report.nextStep() {
            if step.group == .candidate { return (report, step) }
            let record = control == 1 ? echoRecord(step.key, value: 0x32) : []
            report.noteReply(.init(resultCode: control, record: record), for: step)
        }
        return (report, nil)
    }

    // MARK: - The oracle's calibration control (a FAILURE only means "no such key" once a known key passed)

    /// The defect this pins: `FAILURE(0)` is documented in `ValueResponse.isFailure` as "the verb exists,
    /// the request did not satisfy it (**wrong body shape**, or an unknown key)", and the request body is
    /// itself an inference from the SET side. So an all-FAILURE sweep is only a statement about the NAMES
    /// when a name known to exist answered SUCCESS in the same run. Here the known-good control failed
    /// too, so the run proves nothing about the candidates and must not be published as a clean negative.
    func testAnAllFailureSweepIsNotACleanNegativeWhenTheControlFailedToo() {
        var (report, first) = driveToCandidates(limit: 2, control: 0)
        guard var step = first else { return XCTFail("no candidate") }
        while true {
            report.noteReply(.init(resultCode: 0, record: []), for: step)
            guard let next = report.nextStep() else { break }
            step = next
        }
        XCTAssertFalse(report.oracleCalibrated,
                       "no discovery or known-key read came back SUCCESS, so nothing calibrated the oracle")
        XCTAssertEqual(report.verdict,
                       "asked 2 candidate key name(s); all returned FAILURE, but the known-good control "
                       + "key did too — the oracle is uncalibrated this run (inconclusive)")
        XCTAssertFalse(report.verdict.contains("clean negative"),
                       "an uncalibrated run must never publish a negative about the names")
    }

    /// The other side of the same gate: when the control DID answer, a fully-negative sweep is a real
    /// result and keeps its original wording.
    func testTheCleanNegativeSurvivesWhenTheControlAnswered() {
        var (report, first) = driveToCandidates(limit: 2)
        guard var step = first else { return XCTFail("no candidate") }
        while true {
            report.noteReply(.init(resultCode: 0, record: []), for: step)
            guard let next = report.nextStep() else { break }
            step = next
        }
        XCTAssertTrue(report.oracleCalibrated)
        XCTAssertEqual(report.verdict,
                       "asked 2 candidate key name(s); this firmware has none of them (a clean negative)")
    }

    // MARK: - Opcode 115: only SUCCESS starts a walk

    /// The headline defect: `noteEnumerationStart` treated every code except UNSUPPORTED(3) as an
    /// enumeration, so a strap that answers 115 with FAILURE(0) and a zeroed record produced a verb marked
    /// `answered`, an empty key list, and a verdict asserting a complete enumeration of the namespace —
    /// a positive claim built from a refusal.
    func testAFailureReplyToOpcode115IsNotAnEnumeration() {
        var report = smallReport()
        guard let s1 = report.nextStep() else { return XCTFail("s1") }
        XCTAssertEqual(s1.opcode, 115)
        report.noteEnumerationStart(startReply(enumStart(result: 0, revision: 0, count: 0)))

        XCTAssertNotEqual(report.enumerationVerb, .answered, "a FAILURE never reads as an enumeration")
        XCTAssertEqual(report.enumerationVerb, .inconclusive)

        guard let s2 = report.nextStep() else { return XCTFail("s2") }
        XCTAssertEqual(s2.group, .discovery, "116 must not be asked once 115 declined the request")
        XCTAssertNotEqual(s2.opcode, ConfigKeySweep.sendNextDeviceConfigCmd)

        XCTAssertFalse(report.verdict.contains("the strap enumerated its device-config namespace"),
                       "verdict was: \(report.verdict)")
        XCTAssertTrue(report.render().contains("inconclusive"))
    }

    /// PENDING(2) is the same class of answer: the verb replied, no walk started.
    func testAPendingReplyToOpcode115IsNotAnEnumerationEither() {
        var report = smallReport()
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 2, revision: 0, count: 0)))
        XCTAssertEqual(report.enumerationVerb, .inconclusive)
        XCTAssertFalse(report.verdict.contains("the strap enumerated its device-config namespace"))
        XCTAssertTrue(report.trace.contains { $0.contains("PENDING(2)") && $0.contains("did not start a walk") })
    }

    /// WHOOP 4.0 carries no pinned result byte, so nil must still open the walk rather than being
    /// swallowed by the new gate.
    func testAWhoop4StartWithNoResultCodeStillOpensTheWalk() {
        var report = DeviceConfigReadProbeReport(family: .whoop4,
                                                 knownFlagKeys: ["enable_r22_packets"],
                                                 batch: ConfigKeySweep.batch(from: 0, limit: 1))
        _ = report.nextStep()
        report.noteEnumerationStart(.init(resultCode: nil, revision: 10, count: 2))
        XCTAssertEqual(report.enumerationVerb, .answered)
        guard let s2 = report.nextStep() else { return XCTFail("s2") }
        XCTAssertEqual(s2.opcode, ConfigKeySweep.sendNextDeviceConfigCmd)
    }

    // MARK: - A walk only proves a namespace when it actually walked

    /// 115 answered SUCCESS and announced two keys, but the very first 116 was the end marker. The walk
    /// listed nothing, so the run cannot say what the namespace does or does not contain.
    func testAnEnumerationThatListedNothingIsInconclusiveNotAnEmptyNamespace() {
        var report = smallReport()
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 1, revision: 10, count: 2)))
        guard let s2 = report.nextStep() else { return XCTFail("s2") }
        XCTAssertEqual(s2.opcode, ConfigKeySweep.sendNextDeviceConfigCmd)
        XCTAssertFalse(report.noteEnumerationNext(nextReply(enumNext(index: 0xFF, key: nil, validKey: false))))

        XCTAssertTrue(report.enumeratedKeys.isEmpty)
        XCTAssertEqual(report.enumerationVerb, .answered)
        XCTAssertEqual(report.verdict,
                       "115 answered but listed no key — enumeration inconclusive (the strap announced 2)")
    }

    /// Every entry the strap served was a real key it could not render for us. Blaming the strap for OUR
    /// parser is the #874 defect; `FeatureFlagProbe.verdict` already carries this branch and the walk that
    /// borrows its parser must carry it too.
    func testAnEnumerationWhoseNamesAllFailedOurParserBlamesOurParserNotTheStrap() {
        var report = smallReport()
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 1, revision: 10, count: 2)))
        for i in 1...2 {
            _ = report.nextStep()
            XCTAssertTrue(report.noteEnumerationNext(
                FeatureFlagProbe.NextResponse(resultCode: 1, revision: 10, index: i,
                                              validKey: true, key: nil)))
        }
        _ = report.nextStep()
        XCTAssertFalse(report.noteEnumerationNext(nextReply(enumNext(index: 0xFF, key: nil, validKey: false))))

        XCTAssertEqual(report.enumerationSkipped, 2)
        XCTAssertTrue(report.enumeratedKeys.isEmpty)
        let v = report.verdict
        XCTAssertTrue(v.contains("this is our parser rejecting them, NOT the strap serving blanks"), v)
        XCTAssertFalse(v.contains("returned no key NOOP did not already have"), v)
    }

    /// A walk truncated at `maxEnumerationSteps` has seen a PREFIX of the namespace. The cap was reported
    /// in `stopReason` only, while the verdict went on claiming a complete listing.
    func testAnEnumerationTruncatedAtTheCapMakesNoCompletenessClaim() {
        var report = smallReport()
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 1, revision: 10, count: 9999)))
        // A strap with more keys than the cap allows: every entry is a name NOOP already has, so nothing
        // is "new" and the verdict falls through to the completeness claim.
        var walked = 0
        while let step = report.nextStep(), step.group == .enumerate {
            walked += 1
            _ = report.noteEnumerationNext(nextReply(enumNext(index: 1, key: "enable_r22_packets")))
        }
        XCTAssertEqual(walked, ConfigKeySweep.maxEnumerationSteps)
        XCTAssertTrue(report.enumerationTruncated)
        XCTAssertTrue(report.newKeysFound.isEmpty)
        let v = report.verdict
        XCTAssertTrue(v.contains("stopped at its cap"), v)
        XCTAssertFalse(v.contains("returned no key NOOP did not already have"), v)
    }

    /// A walk cut off before the strap's own end marker is likewise a prefix, not a namespace.
    func testAWalkThatNeverReachedTheEndMarkerMakesNoCompletenessClaim() {
        var report = smallReport()
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 1, revision: 10, count: 2)))
        _ = report.nextStep()
        XCTAssertTrue(report.noteEnumerationNext(nextReply(enumNext(index: 1, key: "enable_r22_packets"))))
        // The strap stops replying: the pair is retired mid-walk, and no end marker was ever served.
        guard let s3 = report.nextStep() else { return XCTFail("s3") }
        report.noteTimeout(for: s3, seconds: 8)
        XCTAssertFalse(report.enumerationReachedEnd)
        XCTAssertFalse(report.verdict.contains("returned no key NOOP did not already have"))
    }

    /// The guard against over-correcting: a walk that really did complete, and really did list only keys
    /// NOOP already had, keeps the original claim word for word.
    func testACompletedWalkOfOnlyKnownKeysStillReadsAsACompleteEnumeration() {
        var report = smallReport()
        _ = report.nextStep()
        report.noteEnumerationStart(startReply(enumStart(result: 1, revision: 10, count: 1)))
        _ = report.nextStep()
        XCTAssertTrue(report.noteEnumerationNext(nextReply(enumNext(index: 1, key: "enable_r22_packets"))))
        _ = report.nextStep()
        XCTAssertFalse(report.noteEnumerationNext(nextReply(enumNext(index: 0xFF, key: nil, validKey: false))))
        XCTAssertTrue(report.enumerationReachedEnd)
        XCTAssertTrue(report.newKeysFound.isEmpty)
        XCTAssertEqual(report.verdict,
                       "the strap enumerated its device-config namespace and returned no key NOOP did not already have")
    }

    // MARK: - Report

    /// Byte-for-byte golden, asserted identically by the Kotlin twin, so a shared strap log reads the same
    /// either side and a wording drift fails here rather than in a user's log.
    func testGoldenReportIsByteIdenticalAcrossPlatforms() {
        var report = smallReport()
        guard let s1 = report.nextStep() else { return XCTFail("s1") }
        XCTAssertEqual(s1.opcode, 115)
        report.noteEnumerationStart(startReply(enumStart(result: 3, revision: 0, count: 0)))

        guard let s2 = report.nextStep() else { return XCTFail("s2") }
        report.noteReply(.init(resultCode: 1, record: echoRecord("enable_r22_packets", value: 0x32)), for: s2)
        guard let s3 = report.nextStep() else { return XCTFail("s3") }
        report.noteReply(.init(resultCode: 1,
                               record: echoRecord(DeviceConfigReadProbe.deviceConfigDiscoveryKey,
                                                  value: 0x30)), for: s3)
        guard let s4 = report.nextStep() else { return XCTFail("s4") }
        report.noteReply(.init(resultCode: 0, record: [0x01, 0x00]), for: s4)
        guard let s5 = report.nextStep() else { return XCTFail("s5") }
        report.noteReply(.init(resultCode: 0, record: [0x01, 0x00]), for: s5)
        guard let s6 = report.nextStep() else { return XCTFail("s6") }
        report.noteReply(.init(resultCode: 1, record: echoRecord("hr_ch_switching", value: 0x32)), for: s6)
        guard let c1 = report.nextStep() else { return XCTFail("c1") }
        report.noteReply(.init(resultCode: 0, record: [0x01, 0x00]), for: c1)
        guard let c2 = report.nextStep() else { return XCTFail("c2") }
        report.noteReply(.init(resultCode: 0, record: [0x01, 0x00]), for: c2)
        XCTAssertNil(report.nextStep())

        XCTAssertEqual(report.render(), DeviceConfigReadProbeTests.goldenReport)
    }

    static let goldenReport = """
#103 CONFIG KEY PROBE — WHOOP 5/MG
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
  0 exist · 2 do not · 0 inconclusive

  sig<N> series (T8) — the firmware numbers its signal chains; sig11/sig12 are the two we have (2):
    1. enable_sig1                     unknown
    2. enable_sig2                     unknown

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
