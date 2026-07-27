import XCTest
import WhoopProtocol
@testable import Strand

/// Pins the WHOOP 4.0 GET_ALARM_TIME (cmd 67) arm-readback decode (#401 close-out; two-byte prefix
/// confirmed against real hardware, 2026-07-27).
///
/// armStrapAlarm follows every 4.0 arm with GET_ALARM_TIME so the strap log proves what the STRAP
/// believes is armed. `FrameRouter.armedAlarmEpoch` tries, in priority order: the CONFIRMED two-byte
/// SET_ALARM_TIME-mirror shape (`[0x01, 0x01][u32 LE epoch]…`, proven by a real capture whose readback
/// byte-for-byte matched the epoch just armed), the older single-byte-prefix guess
/// (`[0x01][u32 LE epoch]…`) kept as a fallback for payloads that don't match the confirmed shape, then
/// a bare leading u32 LE — each candidate plausibility-gated to a real wall-clock window; everything
/// else decodes to nil and the router logs raw hex instead. These tests pin all three accepted shapes
/// and the fail-to-hex behaviour so a firmware variant can never silently log a misleading date.
final class AlarmReadbackDecodeTests: XCTestCase {

    /// Build a synthetic WHOOP 4.0 COMMAND_RESPONSE frame around `payload`:
    /// `[0xAA][len u16 LE][crc8][type=36][seq][cmd][origin_seq][result][payload…][crc32 x4]`.
    /// `len` marks where the crc32 trailer starts, exactly as `WhoopCommand.frame` lays it out. The
    /// decode helpers never check CRCs (parseFrame does that on the live path before the router runs),
    /// so fixed filler bytes stand in for crc8/crc32 here.
    private func responseFrame(cmd: UInt8 = 67, result: UInt8 = 1, payload: [UInt8]) -> [UInt8] {
        let inner: [UInt8] = [36, 0x29, cmd, 0x42, result] + payload
        let length = UInt16(inner.count + 4)
        return [0xAA, UInt8(length & 0xFF), UInt8(length >> 8), 0x57] + inner + [0xDE, 0xAD, 0xBE, 0xEF]
    }

    /// The SET-mirror shape, using the #535 capture epoch (1781912880 = 0x6A35D530 → LE 30 D5 35 6A):
    /// a strap echoing back the exact 9-byte payload we armed with decodes to that epoch.
    func testSetMirrorPayload_decodesCaptureEpoch() {
        let frame = responseFrame(payload: [0x01, 0x30, 0xD5, 0x35, 0x6A, 0x00, 0x00, 0x00, 0x00])
        XCTAssertEqual(FrameRouter.armedAlarmEpoch(in: frame), 1_781_912_880)
    }

    /// A bare leading u32 LE (no form byte) is the other plausible firmware answer; same epoch decodes.
    func testBareU32Payload_decodesCaptureEpoch() {
        let frame = responseFrame(payload: [0x30, 0xD5, 0x35, 0x6A])
        XCTAssertEqual(FrameRouter.armedAlarmEpoch(in: frame), 1_781_912_880)
    }

    /// The SET-mirror form wins over the bare read: a payload whose form byte is 0x01 decodes from
    /// offset 1, never from offset 0 (offset 0 would misread the form byte into the epoch). Bytes
    /// chosen so BOTH offsets yield plausible epochs - offset 1 reads 0x685E0060 = 1750990944
    /// (2025), offset 0 would read 0x5E000060|0x01 = 1577082881 (2019) - so this genuinely pins the
    /// precedence, not just the happy path.
    func testSetMirrorForm_takesPrecedenceOverBareRead() {
        let frame = responseFrame(payload: [0x01, 0x60, 0x00, 0x5E, 0x68])
        XCTAssertEqual(FrameRouter.armedAlarmEpoch(in: frame), 1_750_990_944)
    }

    /// A result-style single byte (e.g. an UNSUPPORTED echo) must not decode; the router falls back to
    /// the raw-hex line.
    func testShortGarbagePayload_decodesNil() {
        let frame = responseFrame(payload: [0x03])
        XCTAssertNil(FrameRouter.armedAlarmEpoch(in: frame))
    }

    /// An implausible epoch (5 = 1970) is a disarmed/garbage answer, not an armed alarm - nil, raw hex.
    func testImplausibleEpoch_decodesNil() {
        let frame = responseFrame(payload: [0x01, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
        XCTAssertNil(FrameRouter.armedAlarmEpoch(in: frame))
    }

    // MARK: - "No alarm stored" (epoch 0) detection (#34, issue comment 2026-07-12)

    /// The exact payload from the field report `01 00 00 00 00 00 00 00 04 00 20`: the SET-mirror epoch
    /// field is 0, so this is the strap's "nothing armed" sentinel — armedAlarmEpoch fails (epoch 0 is not
    /// plausible) AND readbackReportsNoAlarm is true, so the router logs "NO alarm stored", not "unrecognised".
    func testFieldReportPayload_reportsNoAlarm() {
        let frame = responseFrame(payload: [0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x20])
        XCTAssertNil(FrameRouter.armedAlarmEpoch(in: frame))
        XCTAssertTrue(FrameRouter.readbackReportsNoAlarm(in: frame))
    }

    /// A bare leading u32 = 0 (no form byte) is also the "no alarm" sentinel.
    func testBareZeroU32_reportsNoAlarm() {
        let frame = responseFrame(payload: [0x00, 0x00, 0x00, 0x00])
        XCTAssertTrue(FrameRouter.readbackReportsNoAlarm(in: frame))
    }

    /// A plausible armed epoch is NOT "no alarm" — the two branches are mutually exclusive, so a genuinely
    /// armed strap never mislogs as "no alarm stored".
    func testArmedEpoch_isNotReportedAsNoAlarm() {
        let frame = responseFrame(payload: [0x01, 0x30, 0xD5, 0x35, 0x6A, 0x00, 0x00, 0x00, 0x00])
        XCTAssertNotNil(FrameRouter.armedAlarmEpoch(in: frame))
        XCTAssertFalse(FrameRouter.readbackReportsNoAlarm(in: frame))
    }

    /// A short result-style payload (0x03) is neither an armed epoch NOR the epoch-0 sentinel — it's
    /// genuinely unparseable, so it still falls through to the raw-hex "unrecognised" branch.
    func testShortGarbage_isNotReportedAsNoAlarm() {
        let frame = responseFrame(payload: [0x03])
        XCTAssertFalse(FrameRouter.readbackReportsNoAlarm(in: frame))
    }

    /// An empty payload (header-only response) decodes nil and yields no hex either.
    func testEmptyPayload_decodesNilAndNoHex() {
        let frame = responseFrame(payload: [])
        XCTAssertNil(FrameRouter.armedAlarmEpoch(in: frame))
        XCTAssertNil(FrameRouter.commandResponsePayloadHex(in: frame))
    }

    /// A truncated frame (shorter than its declared length) must decode nil, never read out of bounds.
    func testTruncatedFrame_decodesNil() {
        var frame = responseFrame(payload: [0x01, 0x30, 0xD5, 0x35, 0x6A, 0x00, 0x00, 0x00, 0x00])
        frame.removeLast(10)
        XCTAssertNil(FrameRouter.armedAlarmEpoch(in: frame))
    }

    /// The raw-hex fallback renders the payload bytes space-separated lowercase, exactly the payload
    /// (no envelope, no crc32), so a report reader sees the strap's answer verbatim.
    func testPayloadHexFallback_rendersPayloadBytes() {
        let frame = responseFrame(payload: [0x03, 0xAB])
        XCTAssertEqual(FrameRouter.commandResponsePayloadHex(in: frame), "03 ab")
    }

    /// Pins the plausibility window bounds (2017..2100, inclusive) so a tweak can't silently widen it.
    func testPlausibilityBounds() {
        XCTAssertTrue(FrameRouter.isPlausibleAlarmEpoch(1_500_000_000))
        XCTAssertFalse(FrameRouter.isPlausibleAlarmEpoch(1_499_999_999))
        XCTAssertTrue(FrameRouter.isPlausibleAlarmEpoch(4_102_444_800))
        XCTAssertFalse(FrameRouter.isPlausibleAlarmEpoch(4_102_444_801))
    }

    // MARK: - Real WHOOP 4.0 capture (2026-07-27 close-out): confirms the two-byte SET-mirror prefix

    /// The strap was armed for epoch 1785176100 (`Set Alarm Time payload=0124a0676a00000000`) and read
    /// back payload `01 01 24 a0 67 6a 00 00 04 00 20` — an exact byte-for-byte match at offset 2, not
    /// offset 1. The old single-byte-prefix guess would have misdecoded this as 1738548225 (2025-02-03,
    /// a full year and a half off the real armed date) instead of falling through correctly; this test
    /// pins the two-byte-prefix shape winning first so that regression can't return silently.
    func testRealWhoop4Capture_twoBytePrefixDecodesExactArmedEpoch() {
        let frame = responseFrame(payload: [0x01, 0x01, 0x24, 0xa0, 0x67, 0x6a, 0x00, 0x00, 0x04, 0x00, 0x20])
        XCTAssertEqual(FrameRouter.armedAlarmEpoch(in: frame), 1_785_176_100)
    }

    /// A second readback from the same session (captured before bonding completed) — payload
    /// `01 01 38 17 48 68 00 00 04 00 20` — decodes to a different, still-plausible epoch (2025-06-10),
    /// a genuinely stale alarm rather than the one just armed. Pins that the two-byte-prefix shape isn't
    /// a one-off fluke: it decodes consistently across independent real captures in the same trace.
    func testRealWhoop4Capture_twoBytePrefixDecodesStalePreBondEpoch() {
        let frame = responseFrame(payload: [0x01, 0x01, 0x38, 0x17, 0x48, 0x68, 0x00, 0x00, 0x04, 0x00, 0x20])
        XCTAssertEqual(FrameRouter.armedAlarmEpoch(in: frame), 1_749_555_000)
    }

    // MARK: - Dispatch (handle) coverage

    /// Build a crc32-valid WHOOP 4.0 COMMAND_RESPONSE (type 36) that parseFrame accepts, carrying the
    /// GET_ALARM_TIME cmd byte (67) and the given readback payload. frameFromPayload lays out
    /// [0xAA][len][crc8][type][seq][cmd][data…][crc32] with a real crc32, so handle() will not reject it
    /// on the crcOK gate. `data` is [origin_seq, result, payload…], matching the inner walk the decode
    /// helpers use.
    @MainActor
    private func alarmResponseFrame(cmd: UInt8 = 67, payload: [UInt8]) -> [UInt8] {
        frameFromPayload([0x42, 0x01] + payload, type: 36, seq: 0x29, cmd: cmd)
    }

    /// The dispatch regression the ship-blocker fixed: cmdName is "GET_ALARM_TIME(67)" (Schema.enumName
    /// appends the "(rawValue)" suffix), so an equality compare against "GET_ALARM_TIME" was dead code and
    /// nothing ever logged. With hasPrefix matching, a synthesized readback frame now fires the branch and
    /// writes the "strap reports armed" line - proving the branch is reachable, not just the pure decode.
    @MainActor
    func testHandle_alarmReadbackFrame_logsStrapReports() {
        let live = LiveState()
        let router = FrameRouter(state: live)
        router.family = .whoop4
        // SET-mirror payload with the #535 capture epoch (1781912880 = LE 30 D5 35 6A).
        router.handle(frame: alarmResponseFrame(payload: [0x01, 0x30, 0xD5, 0x35, 0x6A, 0x00, 0x00, 0x00, 0x00]))
        XCTAssertTrue(live.log.contains { $0.contains("strap reports armed") && $0.contains("1781912880") },
                      "GET_ALARM_TIME readback branch must fire via handle(): \(live.log)")
    }

    /// An unrecognised readback payload still fires the branch and logs the raw-hex fallback, proving the
    /// else arm is reachable too (not just the happy path).
    @MainActor
    func testHandle_unrecognisedReadbackPayload_logsRawHex() {
        let live = LiveState()
        let router = FrameRouter(state: live)
        router.family = .whoop4
        router.handle(frame: alarmResponseFrame(payload: [0x03, 0xAB]))
        XCTAssertTrue(live.log.contains { $0.contains("unrecognised payload") && $0.contains("03 ab") },
                      "unrecognised readback must log raw hex via handle(): \(live.log)")
    }

    /// The field-report readback (epoch 0) now fires the "NO alarm stored" branch, NOT the "unrecognised"
    /// one — proving the reframe is reachable via handle() and that the misleading label is gone.
    @MainActor
    func testHandle_noAlarmStoredReadback_logsNoAlarm() {
        let live = LiveState()
        let router = FrameRouter(state: live)
        router.family = .whoop4
        router.handle(frame: alarmResponseFrame(payload: [0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x20]))
        XCTAssertTrue(live.log.contains { $0.contains("NO alarm currently stored") && $0.contains("did not persist") },
                      "epoch-0 readback must log the 'no alarm stored' line: \(live.log)")
        XCTAssertFalse(live.log.contains { $0.contains("unrecognised payload") },
                       "epoch-0 readback must NOT log the misleading 'unrecognised' line: \(live.log)")
    }

    /// A SET_ALARM_TIME (cmd 66) COMMAND_RESPONSE now logs the strap's raw result byte — the accept/reject
    /// datum previously thrown away. No verdict is claimed (4.0 result-code meaning is unverified), so the
    /// test pins only that the raw byte is surfaced.
    @MainActor
    func testHandle_setAlarmResponse_logsResultByte() {
        let live = LiveState()
        let router = FrameRouter(state: live)
        router.family = .whoop4
        // frameFromPayload lays out [origin_seq, result, payload…] as `data`; result byte = 0x03 here.
        router.handle(frame: frameFromPayload([0x42, 0x03], type: 36, seq: 0x29, cmd: 66))
        XCTAssertTrue(live.log.contains { $0.contains("SET_ALARM_TIME") && $0.contains("result=0x03") },
                      "SET_ALARM_TIME response must log the raw result byte: \(live.log)")
    }

    // MARK: - WHOOP 5.0/MG (#864 close-out) — envelope offset confirmed, GET_ALARM_TIME body still guessed

    /// The real captured SET_ALARM_TIME ack from a 5/MG strap (2026-07-26 trace). Decodes at the whoop5
    /// inner offset as [type 0x24][seq 0x4b][cmd 0x42=SET_ALARM_TIME][origin_seq 0x04][result 0x01=SUCCESS]
    /// — confirming the envelope-offset mirror from 4.0, independent of the (still-unconfirmed)
    /// GET_ALARM_TIME response body shape.
    private let realWhoop5SetAlarmAck: [UInt8] = [
        0xaa, 0x01, 0x0c, 0x00, 0x01, 0x00, 0x27, 0x11, 0x24, 0x4b, 0x42, 0x04, 0x01,
        0x04, 0x01, 0x00, 0x19, 0x61, 0xac, 0x4f,
    ]

    func testWhoop5RealCapturedSetAlarmAck_resultByteDecodesSuccess() {
        XCTAssertEqual(FrameRouter.commandResultByte(in: realWhoop5SetAlarmAck, family: .whoop5), 1)
    }

    func testWhoop5RealCapturedSetAlarmAck_payloadDecodesTrailingBytes() {
        XCTAssertEqual(FrameRouter.commandResponsePayloadHex(in: realWhoop5SetAlarmAck, family: .whoop5), "04 01 00")
    }

    /// The 4.0 offset must NOT accidentally decode the whoop5 frame — proves the two offsets are
    /// genuinely distinct, not coincidentally compatible.
    func testWhoop5RealCapturedSetAlarmAck_whoop4OffsetDoesNotAlias() {
        XCTAssertNotEqual(FrameRouter.commandResultByte(in: realWhoop5SetAlarmAck, family: .whoop4), 1)
    }

    /// A GET_ALARM_TIME SET-mirror shape decodes the same way at the whoop5 offset as it does at the
    /// whoop4 offset — this is a mirrored GUESS (no real 5/MG GET_ALARM_TIME response has been captured),
    /// pinned so the guess itself can't silently drift.
    func testWhoop5MirroredSetMirrorPayload_decodesCaptureEpoch() {
        let inner: [UInt8] = [36, 0x29, 67, 0x42, 1, 0x01, 0x30, 0xD5, 0x35, 0x6A, 0x00, 0x00, 0x00, 0x00]
        let declLen = inner.count + 4
        var frame: [UInt8] = [0xAA, 0x01, UInt8(declLen & 0xFF), UInt8(declLen >> 8), 0x00, 0x01, 0x00, 0x00]
        frame.append(contentsOf: inner)
        frame.append(contentsOf: [0xDE, 0xAD, 0xBE, 0xEF])
        XCTAssertEqual(FrameRouter.armedAlarmEpoch(in: frame, family: .whoop5), 1_781_912_880)
    }

    /// Real captured trace (2026-07-26): STRAP_DRIVEN_ALARM_SET → STRAP_DRIVEN_ALARM_EXECUTED (event 57)
    /// → HAPTICS_FIRED → dismiss — the evidence that flipped 5/MG alarm-fire from "never captured" to
    /// confirmed (#864). Pins that the router fires the wake callback and logs the event on the whoop5
    /// family, not just on 4.0.
    @MainActor
    func testHandle_whoop5RealCapturedAlarmExecuted_firesWakeCallback() {
        let live = LiveState()
        var fired = false
        live.onSmartAlarmFired = { fired = true }
        let router = FrameRouter(state: live)
        router.family = .whoop5
        let frame: [UInt8] = [
            0xaa, 0x01, 0x10, 0x00, 0x01, 0x00, 0x20, 0x81, 0x30, 0xc1, 0x39, 0x00, 0x10, 0xe3, 0x65, 0x6a,
            0x7a, 0x34, 0x00, 0x00, 0x39, 0xdb, 0x95, 0x79,
        ]
        router.handle(frame: frame)
        XCTAssertTrue(fired, "a real captured STRAP_DRIVEN_ALARM_EXECUTED frame must fire onSmartAlarmFired")
        XCTAssertTrue(live.log.contains { $0.contains("strap-driven wake fired") },
                      "must log the fire on whoop5, not just whoop4: \(live.log)")
    }

    /// Real captured GET_ALARM_TIME readback from the same 5/MG session (2026-07-26): immediately after a
    /// SUCCESSFUL SET_ALARM_TIME, the readback itself answers result=FAILURE(0). Before this fix, the
    /// zero-ish payload fell through to `readbackReportsNoAlarm` and logged "the arm did NOT persist" —
    /// actively wrong, since the arm had just succeeded one line earlier. Pins that a FAILURE result is
    /// now reported plainly, and that the misleading "did not persist" line never fires for it.
    @MainActor
    func testHandle_whoop5RealCapturedFailedReadback_reportsResultNotFalseNegative() {
        let live = LiveState()
        let router = FrameRouter(state: live)
        router.family = .whoop5
        let frame: [UInt8] = [
            0xaa, 0x01, 0x10, 0x00, 0x01, 0x00, 0x20, 0x81, 0x24, 0x25, 0x43, 0x05, 0x00, 0x00, 0x00, 0x00,
            0x00, 0xdf, 0x00, 0x00, 0x9c, 0x8e, 0xe6, 0x2e,
        ]
        router.handle(frame: frame)
        XCTAssertTrue(live.log.contains { $0.contains("readback (GET_ALARM_TIME)") && $0.contains("FAILURE") },
                      "a FAILURE readback must be reported plainly: \(live.log)")
        XCTAssertFalse(live.log.contains { $0.contains("did not persist") },
                        "a failed QUERY must never be misread as a failed ARM: \(live.log)")
    }
}
