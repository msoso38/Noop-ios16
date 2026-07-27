import XCTest
@testable import RawCapture

/// Tests for the pure, brand-neutral capture/serialisation type. Callers (WhoopProtocol,
/// OuraProtocol, …) parse/validate frames themselves and hand this package the finished record —
/// there is no `parseFrame` anywhere in this package, so these tests only exercise storage/JSON.
final class RawCaptureTests: XCTestCase {

    private let helloHex = "aa0108000001e67123019101363e5c8d"

    private func makeRecord(
        hex: String = "aa0108000001e67123019101363e5c8d",
        char: String = "fd4b0003",
        tsMs: Int = 1_700_000_000_123,
        hr: Int? = 62,
        typeName: String? = "CLIENT_HELLO",
        seq: Int? = 1,
        crcOK: Bool? = true,
        ok: Bool = true
    ) -> RawCaptureRecord {
        RawCaptureRecord(hex: hex, char: char, tsMs: tsMs, hr: hr, typeName: typeName, seq: seq, crcOK: crcOK, ok: ok)
    }

    func testRecordsCanonicalHexAndProvenance() {
        let cap = RawCapture()
        let rec = cap.record(makeRecord())

        XCTAssertEqual(cap.count, 1)
        XCTAssertEqual(rec.hex, helloHex)
        XCTAssertEqual(rec.char, "fd4b0003")
        XCTAssertEqual(rec.tsMs, 1_700_000_000_123)
        XCTAssertEqual(rec.hr, 62)
        XCTAssertTrue(rec.ok)
        XCTAssertEqual(rec.crcOK, true)
    }

    func testNilHeartRateIsAllowed() {
        let cap = RawCapture()
        let rec = cap.record(makeRecord(char: "fd4b0005", tsMs: 1, hr: nil))
        XCTAssertNil(rec.hr)
    }

    func testMalformedFrameIsCapturedButFlaggedNotOK() {
        let cap = RawCapture()
        let rec = cap.record(makeRecord(
            hex: "aa0100", char: "fd4b0007", tsMs: 5, hr: nil, typeName: nil, seq: nil, crcOK: nil, ok: false
        ))
        XCTAssertFalse(rec.ok)
        XCTAssertEqual(rec.hex, "aa0100")
        XCTAssertEqual(cap.count, 1)
    }

    func testReset() {
        let cap = RawCapture()
        cap.record(makeRecord())
        cap.reset()
        XCTAssertEqual(cap.count, 0)
    }

    func testEncodedJSONUsesSnakeCaseKeys() throws {
        let cap = RawCapture()
        cap.record(makeRecord(tsMs: 7, hr: 55))
        let data = try cap.encodedJSON()
        let obj = try JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        let first = try XCTUnwrap(obj?.first)
        XCTAssertEqual(first["hex"] as? String, helloHex)
        XCTAssertEqual(first["ts_ms"] as? Int, 7)
        XCTAssertEqual(first["hr"] as? Int, 55)
        XCTAssertEqual(first["crc_ok"] as? Bool, true)
        XCTAssertNotNil(first["type_name"])
    }

    /// The capture file's `hex` projection must be a drop-in `frames.json` fixture.
    func testFramesFixtureJSONIsParityCompatible() throws {
        let cap = RawCapture()
        cap.record(makeRecord(tsMs: 1, hr: nil))
        cap.record(makeRecord(char: "fd4b0005", tsMs: 2, hr: 60))
        let data = try cap.framesFixtureJSON()
        let obj = try JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        XCTAssertEqual(obj?.count, 2)
        XCTAssertEqual(obj?.first?.keys.sorted(), ["hex"])
        XCTAssertEqual(obj?.first?["hex"] as? String, helloHex)
    }
}
