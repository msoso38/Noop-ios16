import XCTest
@testable import WhoopStore

final class OuraMotionDumpLineTests: XCTestCase {
    func testEncodeFixedKeyOrderAndValues() {
        let line = OuraMotionDumpLine.encode(
            deviceId: "oura-2H3B2405003655", ringTs: 730333, utc: 1_753_440_000,
            iso: "2026-07-25T09:00:00Z", orientation: 3, x: 96, y: 232, z: 56, highIntensity: 7)
        XCTAssertEqual(line,
            "{\"schema\":1,\"deviceId\":\"oura-2H3B2405003655\",\"ringTs\":730333,"
          + "\"utc\":1753440000,\"iso\":\"2026-07-25T09:00:00Z\",\"orientation\":3,"
          + "\"x\":96,\"y\":232,\"z\":56,\"high_intensity\":7}")
    }

    func testEncodeIsValidJSONWithNegativeAxes() throws {
        let line = OuraMotionDumpLine.encode(
            deviceId: "oura-x", ringTs: 1, utc: 2, iso: "i", orientation: 0,
            x: -96, y: 0, z: -1024, highIntensity: 0)
        let obj = try JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any]
        XCTAssertEqual(obj?["schema"] as? Int, OuraMotionDumpLine.schema)
        XCTAssertEqual(obj?["x"] as? Int, -96)
        XCTAssertEqual(obj?["z"] as? Int, -1024)
    }
}
