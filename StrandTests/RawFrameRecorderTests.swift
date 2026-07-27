import XCTest
import CoreBluetooth
import WhoopProtocol
@testable import Strand

/// Ticket 03: the raw-frame-capture Settings toggle now records BOTH a WHOOP 4.0 classic-envelope
/// connection and a WHOOP 5.0/MG puffin connection, with the same fidelity (full frame hex + decode
/// hints) — not just the WHOOP5-only behaviour `PuffinFrameRecorder` used to have. These tests drive
/// `RawFrameRecorder.capture(parsed:char:)` the same way `BLEManager.didUpdateValueFor` now does: with
/// an already-parsed `ParsedFrame`, never re-parsing inside the recorder.
@MainActor
final class RawFrameRecorderTests: XCTestCase {

    /// A real WHOOP 4.0 classic-envelope frame straight out of the parity fixtures.
    private let whoop4Hex = "aa1800ff28000f3de10100003c01e8030000000000000000c64efbea"
    private func whoop4Bytes() -> [UInt8] {
        var out = [UInt8]()
        var idx = whoop4Hex.startIndex
        while idx < whoop4Hex.endIndex {
            let next = whoop4Hex.index(idx, offsetBy: 2)
            out.append(UInt8(whoop4Hex[idx..<next], radix: 16)!)
            idx = next
        }
        return out
    }

    private func whoop5HelloFrame() -> [UInt8] { DeviceFamily.whoop5ClientHello }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: RawFrameRecorder.enabledKey)
        super.tearDown()
    }

    func testCaptureIsNoOpWhenToggleIsOff() {
        UserDefaults.standard.set(false, forKey: RawFrameRecorder.enabledKey)
        let live = LiveState()
        let recorder = RawFrameRecorder(state: live)
        let parsed = parseFrame(whoop4Bytes(), family: .whoop4, collectFields: true)

        recorder.capture(parsed: parsed, char: CBUUID(string: "fd4b0001"))

        XCTAssertEqual(live.rawCaptureCount, 0)
    }

    func testCapturesWhoop4FrameWhenEnabled() throws {
        UserDefaults.standard.set(true, forKey: RawFrameRecorder.enabledKey)
        let live = LiveState()
        live.heartRate = 58
        let recorder = RawFrameRecorder(state: live)
        let parsed = parseFrame(whoop4Bytes(), family: .whoop4, collectFields: true)

        recorder.capture(parsed: parsed, char: CBUUID(string: "fd4b0001"))
        XCTAssertEqual(live.rawCaptureCount, 1)

        recorder.flush()
        let url = try XCTUnwrap(live.rawCaptureURL)
        defer { try? FileManager.default.removeItem(at: url) }

        let data = try Data(contentsOf: url)
        let obj = try JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        let first = try XCTUnwrap(obj?.first)
        XCTAssertEqual(first["hex"] as? String, whoop4Hex)
        XCTAssertEqual(first["hr"] as? Int, 58)
        XCTAssertEqual(first["ok"] as? Bool, true)
        XCTAssertNotNil(first["type_name"])
    }

    func testCapturesWhoop5FrameWhenEnabled() throws {
        UserDefaults.standard.set(true, forKey: RawFrameRecorder.enabledKey)
        let live = LiveState()
        let recorder = RawFrameRecorder(state: live)
        let parsed = parseFrame(whoop5HelloFrame(), family: .whoop5, collectFields: true)

        recorder.capture(parsed: parsed, char: CBUUID(string: "fd4b0003"))
        XCTAssertEqual(live.rawCaptureCount, 1)

        recorder.flush()
        let url = try XCTUnwrap(live.rawCaptureURL)
        defer { try? FileManager.default.removeItem(at: url) }

        // Regression check against PuffinCapture's old fixture-compatible output shape (#2 acceptance).
        XCTAssertTrue(url.lastPathComponent.hasPrefix("raw-"))
        let data = try Data(contentsOf: url)
        let obj = try JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        let first = try XCTUnwrap(obj?.first)
        XCTAssertEqual(first["crc_ok"] as? Bool, true)
        XCTAssertEqual(first["ok"] as? Bool, true)
    }

    func testBothFamiliesCanBeCapturedInTheSameSession() {
        UserDefaults.standard.set(true, forKey: RawFrameRecorder.enabledKey)
        let live = LiveState()
        let recorder = RawFrameRecorder(state: live)

        recorder.capture(parsed: parseFrame(whoop4Bytes(), family: .whoop4, collectFields: true),
                          char: CBUUID(string: "fd4b0001"))
        recorder.capture(parsed: parseFrame(whoop5HelloFrame(), family: .whoop5, collectFields: true),
                          char: CBUUID(string: "fd4b0003"))

        XCTAssertEqual(live.rawCaptureCount, 2)
        recorder.flush()
        if let url = live.rawCaptureURL { try? FileManager.default.removeItem(at: url) }
    }

    /// Regression for the #47 parse-once DEBUG assert (`FrameRouter.handle(parsed:frame:)`): when raw
    /// capture is on, `BLEManager` threads a `collectFields: true` parse to both the router and the
    /// capture call. The assert's own fresh reparse must match that flag (it infers it from
    /// `parsed.rawHex.isEmpty`) or every live frame would trip it while capture is enabled.
    func testRouterAcceptsACollectFieldsTrueParseWithoutTrippingTheParseOnceAssert() {
        let live = LiveState()
        let router = FrameRouter(state: live)
        let frame = whoop4Bytes()
        let parsed = parseFrame(frame, family: .whoop4, collectFields: true)

        router.handle(parsed: parsed, frame: frame)   // must not assert-crash in DEBUG
    }
}
