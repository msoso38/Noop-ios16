import XCTest
import RawCapture
@testable import WhoopProtocol

/// Tests for the pure `ParsedFrame` -> `RawCaptureRecord` mapper. No `parseFrame` call lives in the
/// adapter itself — these tests call `parseFrame` once per fixture, exactly like a real call site
/// would, and hand the result to the adapter.
final class RawCaptureAdapterTests: XCTestCase {

    /// A real WHOOP 4.0 classic-envelope frame straight out of the parity fixtures.
    private let whoop4Hex = "aa1800ff28000f3de10100003c01e8030000000000000000c64efbea"

    /// A known-good, fully-formed WHOOP 5.0 puffin frame (valid CRC16 header + CRC32 trailer): CLIENT_HELLO.
    private let whoop5Hex = "aa0108000001e67123019101363e5c8d"
    private func whoop5HelloFrame() -> [UInt8] { DeviceFamily.whoop5ClientHello }

    private func bytes(_ hex: String) -> [UInt8] {
        var out = [UInt8]()
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2)
            out.append(UInt8(hex[idx..<next], radix: 16)!)
            idx = next
        }
        return out
    }

    func testWhoop4FrameProducesCorrectDecodeHints() {
        let parsed = parseFrame(bytes(whoop4Hex), family: .whoop4, collectFields: true)
        let rec = rawCaptureRecord(for: parsed, char: "fd4b0001", tsMs: 1, hr: 58)

        XCTAssertEqual(rec.hex, whoop4Hex)
        XCTAssertEqual(rec.char, "fd4b0001")
        XCTAssertEqual(rec.tsMs, 1)
        XCTAssertEqual(rec.hr, 58)
        XCTAssertTrue(rec.ok)
        XCTAssertEqual(rec.typeName, parsed.typeName)
        XCTAssertEqual(rec.seq, parsed.seq)
        XCTAssertEqual(rec.crcOK, parsed.crcOK)
    }

    func testWhoop5FrameProducesCorrectDecodeHints() {
        let parsed = parseFrame(whoop5HelloFrame(), family: .whoop5, collectFields: true)
        let rec = rawCaptureRecord(for: parsed, char: "fd4b0003", tsMs: 2, hr: 62)

        XCTAssertEqual(rec.hex, whoop5Hex)
        XCTAssertTrue(rec.ok)
        XCTAssertEqual(rec.crcOK, true)
        XCTAssertEqual(rec.typeName, parsed.typeName)
        XCTAssertEqual(rec.seq, parsed.seq)
    }

    func testUnparseableFrameIsCapturedButFlaggedNotOK() {
        let raw: [UInt8] = [0xAA, 0x01, 0x00]
        let parsed = parseFrame(raw, family: .whoop5, collectFields: true)
        let rec = rawCaptureRecord(for: parsed, char: "fd4b0007", tsMs: 5, hr: nil)

        XCTAssertFalse(rec.ok)
        XCTAssertEqual(rec.hex, parsed.rawHex)
        XCTAssertNil(rec.typeName)
    }

    func testNilHeartRateIsAllowed() {
        let parsed = parseFrame(whoop5HelloFrame(), family: .whoop5, collectFields: true)
        let rec = rawCaptureRecord(for: parsed, char: "fd4b0005", tsMs: 1, hr: nil)
        XCTAssertNil(rec.hr)
    }

    /// The adapter output must round-trip through `RawCapture`'s fixture-compatible JSON writer,
    /// matching what `PuffinCaptureTests.testFramesFixtureJSONIsParityCompatible` checks today.
    func testFramesFixtureJSONIsParityCompatible() throws {
        let cap = RawCapture()
        let parsed = parseFrame(whoop5HelloFrame(), family: .whoop5, collectFields: true)
        cap.record(rawCaptureRecord(for: parsed, char: "fd4b0003", tsMs: 1, hr: nil))
        cap.record(rawCaptureRecord(for: parsed, char: "fd4b0005", tsMs: 2, hr: 60))

        let data = try cap.framesFixtureJSON()
        let obj = try JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        XCTAssertEqual(obj?.count, 2)
        XCTAssertEqual(obj?.first?.keys.sorted(), ["hex"])
        XCTAssertEqual(obj?.first?["hex"] as? String, whoop5Hex)
    }
}
