import XCTest
@testable import WhoopProtocol

/// #174: the R22 DISABLE path — the sequence bytes, the key-aware `SET_FF_VALUE(120)` allowlist, and the
/// staged write→read-back run that turns the inferred off value into a measurement.
///
/// Everything here runs with no app, no strap and no CoreBluetooth: `R22DisableReport` is pure and
/// order-dependent, so the whole verdict table — including the case where the strap refuses `'0'` — is
/// covered without hardware. That matters more than usual here, because the one thing that CANNOT be
/// tested off-strap is whether the firmware accepts the value, and the design is built so that the run
/// itself answers that on first use rather than assuming it.
final class R22DisableTests: XCTestCase {

    private func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined(separator: " ") }

    // MARK: - The sequence

    func testDisableSequenceMirrorsEnableExactly() {
        let enable = Whoop5Config.enableR22Sequence
        let disable = Whoop5Config.disableR22Sequence
        XCTAssertEqual(disable.count, enable.count, "the undo must cover every key the enable sets")
        XCTAssertEqual(disable.map(\.name), enable.map(\.name),
                       "same keys in the same order — the master flag is cleared first")
        XCTAssertTrue(disable.allSatisfy { $0.value == Whoop5Config.featureFlagOffValue })
    }

    func testOffValueIsAsciiZero() {
        XCTAssertEqual(Whoop5Config.featureFlagOffValue, 0x30)
        XCTAssertEqual(Character(UnicodeScalar(Whoop5Config.featureFlagOffValue)), "0")
    }

    /// The disable frame differs from the enable frame in EXACTLY one byte — the value at body offset 32
    /// (frame offset 12 + 32 = 44). Anything else changing means the encoding drifted.
    func testDisableFrameDiffersFromEnableInOnlyTheValueByte() {
        let on = Whoop5Config.frame(flag: Whoop5Config.Flag("enable_r22_packets", 0x32), seq: 1)
        let off = Whoop5Config.frame(flag: Whoop5Config.Flag("enable_r22_packets", 0x30), seq: 1)
        XCTAssertEqual(on.count, off.count)
        let differing = (0..<on.count).filter { on[$0] != off[$0] }
        // The value byte, plus the 4-byte CRC32 trailer it necessarily changes.
        XCTAssertEqual(differing.first, 44, "the value byte sits at frame offset 44")
        XCTAssertEqual(differing.count, 5, "value byte + CRC32 trailer only")
        XCTAssertEqual(off[44], 0x30)
    }

    func testDisableFramesVerifyAndCarryDistinctSeqs() {
        let frames = Whoop5Config.disableSequenceFrames(firstSeq: 1)
        XCTAssertEqual(frames.count, 16)
        XCTAssertEqual(frames.map { $0[9] }, Array(1...16))
        for f in frames {
            XCTAssertTrue(verifyFrame(f, family: .whoop5).ok, "every disable frame must pass 5/MG CRCs")
        }
    }

    /// `disable_pip_r26_packets` gets the SAME off byte as the `enable_*` keys. The sequence is defined as
    /// the undo of the enable sequence, not as a per-key semantic inversion — see the doc on
    /// `disableR22Sequence`. Pinned because it is the one line a reviewer is most likely to "fix".
    func testDisablePipR26GetsTheSameOffByteAsTheEnableKeys() {
        let pip = Whoop5Config.disableR22Sequence.first { $0.name == "disable_pip_r26_packets" }
        XCTAssertEqual(pip?.value, Whoop5Config.featureFlagOffValue)
        XCTAssertEqual(Whoop5Config.enableR22Sequence.first { $0.name == "disable_pip_r26_packets" }?.value, 0x32,
                       "and the enable value stays '2' — this key is why boolean semantics are not assumed")
    }

    // MARK: - The write gate

    func testGateAdmitsOnlyOpcode120() {
        let payload = [0x01] + Whoop5Config.payloadBody(name: "enable_r22_packets", value: 0x30)
        for opcode in UInt8.min...UInt8.max {
            let admitted = FeatureFlagWriteGate.admitsSend(opcode: opcode, payload: payload, deepDataOptIn: true)
            XCTAssertEqual(admitted, opcode == 120, "opcode \(opcode) admission")
        }
    }

    func testGateRefusesDeviceConfigWriteVerb() {
        let payload = [0x01] + Whoop5Config.payloadBody(name: "enable_r22_packets", value: 0x30)
        XCTAssertFalse(FeatureFlagWriteGate.admitsSend(opcode: 119, payload: payload, deepDataOptIn: true),
                       "119 keeps its own Broadcast-HR clause and must never be reachable from here")
    }

    func testGateRefusesEverythingWithoutTheOptIn() {
        for flag in Whoop5Config.enableR22Sequence {
            for value in [flag.value, Whoop5Config.featureFlagOffValue] {
                let payload = [0x01] + Whoop5Config.payloadBody(name: flag.name, value: value)
                XCTAssertFalse(FeatureFlagWriteGate.admitsSend(opcode: 120, payload: payload, deepDataOptIn: false),
                               "\(flag.name)=\(value) must be refused with the opt-in off")
            }
        }
    }

    func testGateAdmitsEveryR22KeyForBothItsEnableValueAndOff() {
        for flag in Whoop5Config.enableR22Sequence {
            for value in [flag.value, Whoop5Config.featureFlagOffValue] {
                let payload = [0x01] + Whoop5Config.payloadBody(name: flag.name, value: value)
                XCTAssertTrue(FeatureFlagWriteGate.admitsSend(opcode: 120, payload: payload, deepDataOptIn: true),
                              "\(flag.name)=\(value) must be admitted")
            }
        }
    }

    /// The tightening this gate exists for: before it, opcode 120 was admitted on the opt-in alone, so ANY
    /// feature-flag key with ANY value could travel. Both halves of that are now closed.
    func testGateRefusesUnknownKeysAndUnknownValues() {
        for key in ["general_ab_test", "enable_pdaf_walk_det", "enable_maverick_model", "enable_r22_v7_packets"] {
            let payload = [0x01] + Whoop5Config.payloadBody(name: key, value: 0x30)
            XCTAssertFalse(FeatureFlagWriteGate.admitsSend(opcode: 120, payload: payload, deepDataOptIn: true),
                           "\(key) is not an R22 key and must be refused")
        }
        for value: UInt8 in [0x00, 0x29, 0x33, 0x39, 0x41, 0xFF] {
            let payload = [0x01] + Whoop5Config.payloadBody(name: "enable_r22_packets", value: value)
            XCTAssertFalse(FeatureFlagWriteGate.admitsSend(opcode: 120, payload: payload, deepDataOptIn: true),
                           "value 0x\(String(format: "%02x", value)) is neither the enable nor the off value")
        }
        // enable_r22_v4_packets' enable value is '1', so '2' must be refused for THAT key specifically.
        let wrongForV4 = [0x01] + Whoop5Config.payloadBody(name: "enable_r22_v4_packets", value: 0x32)
        XCTAssertFalse(FeatureFlagWriteGate.admitsSend(opcode: 120, payload: wrongForV4, deepDataOptIn: true),
                       "the admitted value is per-key, not a shared pair")
    }

    func testGateRefusesMalformedBodies() {
        let good = Whoop5Config.payloadBody(name: "enable_r22_packets", value: 0x30)
        // Wrong inner b3 byte.
        XCTAssertFalse(FeatureFlagWriteGate.admitsSend(opcode: 120, payload: [0x02] + good, deepDataOptIn: true))
        // Truncated.
        XCTAssertFalse(FeatureFlagWriteGate.admitsSend(opcode: 120, payload: [0x01] + good.dropLast(1), deepDataOptIn: true))
        // Empty.
        XCTAssertFalse(FeatureFlagWriteGate.admitsSend(opcode: 120, payload: [], deepDataOptIn: true))
        // Non-NUL padding after the name.
        var dirty = good
        dirty[25] = 0x41
        XCTAssertFalse(FeatureFlagWriteGate.admitsSend(opcode: 120, payload: [0x01] + dirty, deepDataOptIn: true),
                       "a name field whose padding is not NUL is not a name field")
        // Binary in the name field.
        var binary = good
        binary[3] = 0x01
        XCTAssertFalse(FeatureFlagWriteGate.admitsSend(opcode: 120, payload: [0x01] + binary, deepDataOptIn: true))
    }

    func testKeyAndValueRoundTripsEveryR22Key() {
        for flag in Whoop5Config.enableR22Sequence {
            let payload = [0x01] + Whoop5Config.payloadBody(name: flag.name, value: Whoop5Config.featureFlagOffValue)
            let parsed = FeatureFlagWriteGate.keyAndValue(inSendPayload: payload)
            XCTAssertEqual(parsed?.key, flag.name)
            XCTAssertEqual(parsed?.value, 0x30)
        }
    }

    func testReadBackOpcodeIsOnly128() {
        for opcode in UInt8.min...UInt8.max {
            XCTAssertEqual(FeatureFlagWriteGate.isReadBackOpcode(opcode), opcode == 128)
        }
    }

    // MARK: - The staged run

    /// Build a synthetic GET_FF_VALUE reply record: the key NUL-padded to 32 bytes, then the value byte —
    /// the layout `ValueResponse.value(for:)` reads.
    private func reply(key: String, value: UInt8, resultCode: Int = 1) -> DeviceConfigReadProbe.ValueResponse {
        var record = [UInt8](repeating: 0, count: 32)
        for (i, b) in Array(key.utf8).enumerated() where i < 32 { record[i] = b }
        record.append(value)
        return DeviceConfigReadProbe.ValueResponse(resultCode: resultCode, record: record)
    }

    private func failureReply() -> DeviceConfigReadProbe.ValueResponse {
        DeviceConfigReadProbe.ValueResponse(resultCode: 0, record: [])
    }

    func testPlanStartsWithTheProbeWriteThenItsReadBack() {
        var r = R22DisableReport()
        let first = r.nextStep()
        XCTAssertEqual(first?.stage, .probeWrite)
        XCTAssertEqual(first?.key, R22DisableReport.probeKey)
        XCTAssertEqual(first?.opcode, 120)
        let second = r.nextStep()
        XCTAssertEqual(second?.stage, .probeRead)
        XCTAssertEqual(second?.key, R22DisableReport.probeKey)
        XCTAssertEqual(second?.opcode, 128)
    }

    /// The gate: a probe read-back that still shows the old value STOPS the run with fifteen keys untouched.
    /// This is the case the staging exists for.
    func testProbeRejectionStopsTheRunAndLeavesFifteenKeysUntouched() {
        var r = R22DisableReport()
        let write = r.nextStep()!
        r.noteWriteAck(resultCode: 1, for: write)          // SUCCESS ack — deliberately not believed
        let read = r.nextStep()!
        r.noteReadBack(reply(key: R22DisableReport.probeKey, value: 0x31), for: read)   // still '1'

        XCTAssertNil(r.nextStep(), "no further steps once the off value is rejected")
        XCTAssertEqual(r.verdict, .offValueRejected)
        XCTAssertEqual(r.outcomes[R22DisableReport.probeKey], .unchanged)
        let skipped = r.keys.filter { r.outcomes[$0] == .skipped }
        XCTAssertEqual(skipped.count, 15, "the other fifteen must be left alone")
        XCTAssertTrue(r.render().contains("is NOT how this firmware clears a feature flag"))
        XCTAssertEqual(r.writeAcks[R22DisableReport.probeKey], 1,
                       "the SUCCESS ack is recorded even though the verdict is a rejection")
    }

    /// A clean run: probe accepts, the other fifteen are written, all sixteen read back as '0'.
    func testFullRunClearsAllSixteen() {
        var r = R22DisableReport()
        var steps = 0
        while let step = r.nextStep() {
            steps += 1
            XCTAssertLessThan(steps, 100, "plan must terminate")
            if step.isWrite {
                XCTAssertEqual(step.opcode, 120)
                r.noteWriteAck(resultCode: 1, for: step)
            } else {
                XCTAssertEqual(step.opcode, 128)
                r.noteReadBack(reply(key: step.key, value: 0x30), for: step)
            }
        }
        XCTAssertEqual(r.verdict, .allCleared)
        XCTAssertEqual(r.keys.count, 16)
        for key in r.keys {
            XCTAssertEqual(r.outcomes[key], .cleared, "\(key) should be cleared")
        }
        // 1 probe write + 1 probe read + 15 clear writes + 16 verify reads
        XCTAssertEqual(steps, 33)
        XCTAssertTrue(r.summary.contains("All 16 R22 flags cleared"))
    }

    /// FAILURE on a read-back means the key no longer has a stored value at all. Nothing predicted this
    /// outcome, so it is reported as its own state rather than folded into "cleared".
    func testFailureReadBackIsReportedAsUnsetNotCleared() {
        var r = R22DisableReport()
        let write = r.nextStep()!
        r.noteWriteAck(resultCode: 1, for: write)
        let read = r.nextStep()!
        r.noteReadBack(failureReply(), for: read)
        XCTAssertEqual(r.outcomes[R22DisableReport.probeKey], .unset)
        XCTAssertTrue(r.probePassed, "an unset key is a success — the run should continue")
        XCTAssertNotNil(r.nextStep(), "the clear stage must follow a passing probe")
    }

    func testPartialRunIsReportedAsPartialNotSuccess() {
        var r = R22DisableReport()
        while let step = r.nextStep() {
            if step.isWrite {
                r.noteWriteAck(resultCode: 1, for: step)
            } else if step.key == "wear_detect_bias" {
                r.noteReadBack(reply(key: step.key, value: 0x32), for: step)   // refused this one
            } else {
                r.noteReadBack(reply(key: step.key, value: 0x30), for: step)
            }
        }
        XCTAssertEqual(r.verdict, .partial)
        XCTAssertEqual(r.outcomes["wear_detect_bias"], .unchanged)
        XCTAssertTrue(r.summary.contains("15 of 16"))
    }

    /// A SUCCESS ack whose read-back did not move must never render as success — the #907/#891 rule.
    func testSuccessAckWithUnmovedValueIsNeverReportedAsSuccess() {
        var r = R22DisableReport()
        let write = r.nextStep()!
        r.noteWriteAck(resultCode: 1, for: write)
        let read = r.nextStep()!
        r.noteReadBack(reply(key: R22DisableReport.probeKey, value: 0x31), for: read)
        let text = r.render()
        XCTAssertFalse(r.verdict == .allCleared)
        XCTAssertTrue(text.contains("recorded, not proof"))
        XCTAssertTrue(text.contains("NOT treated as proof"))
    }

    func testSilentProbeStopsTheRun() {
        var r = R22DisableReport()
        let write = r.nextStep()!
        r.noteTimeout(for: write, seconds: 8)
        XCTAssertNil(r.outcomes[R22DisableReport.probeKey],
                     "a silent WRITE decides nothing — only the read-back does")
        let read = r.nextStep()!
        r.noteTimeout(for: read, seconds: 8)
        XCTAssertEqual(r.verdict, .probeInconclusive)
        XCTAssertNil(r.nextStep())
    }

    func testAbandonMarksRemainingKeysSkipped() {
        var r = R22DisableReport()
        let write = r.nextStep()!
        r.noteWriteAck(resultCode: 1, for: write)
        r.noteAbandoned("link dropped")
        XCTAssertEqual(r.verdict, .abandoned)
        XCTAssertNil(r.nextStep())
        XCTAssertEqual(r.keys.filter { r.outcomes[$0] == .skipped }.count, 16)
    }

    func testUndecodableReadBackIsNotTreatedAsCleared() {
        var r = R22DisableReport()
        let write = r.nextStep()!
        r.noteWriteAck(resultCode: 1, for: write)
        let read = r.nextStep()!
        r.noteReadFailure(.crc, for: read)
        XCTAssertEqual(r.outcomes[R22DisableReport.probeKey], .undecodable)
        XCTAssertFalse(r.probePassed)
        XCTAssertEqual(r.verdict, .probeInconclusive)
    }

    /// The report must state the four standing caveats verbatim, so the log, the UI and the PR cannot drift
    /// apart on what was actually established.
    func testRenderCarriesTheCaveats() {
        var r = R22DisableReport()
        while let step = r.nextStep() {
            if step.isWrite { r.noteWriteAck(resultCode: 1, for: step) }
            else { r.noteReadBack(reply(key: step.key, value: 0x30), for: step) }
        }
        let text = r.render()
        XCTAssertTrue(text.contains("'0' as the off value is INFERRED"))
        XCTAssertTrue(text.contains("not the same as reverted BEHAVIOUR"))
        XCTAssertTrue(text.contains("does not restore a snapshot"))
        XCTAssertTrue(text.contains("disable_pip_r26_packets inverts"))
        // Every key appears in the per-key table with its before value.
        for key in r.keys { XCTAssertTrue(text.contains(key), "\(key) missing from the report") }
    }

    func testProbeKeyIsTheOneWithAHardwareWriteDemonstration() {
        XCTAssertEqual(R22DisableReport.probeKey, "enable_sig12")
        XCTAssertTrue(Whoop5Config.enableR22Sequence.contains { $0.name == R22DisableReport.probeKey })
    }
}
