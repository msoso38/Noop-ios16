import XCTest
@testable import WhoopStore

final class OuraCvaPpgDumpLineTests: XCTestCase {
    func testEncodeFixedKeyOrderAndValues() {
        let line = OuraCvaPpgDumpLine.encode(
            deviceId: "oura-2H3B2405003655", ringTs: 3_584_349, utc: 1_753_440_000,
            iso: "2026-07-30T09:09:01Z", values: [395015, 394873, 394679])
        XCTAssertEqual(line,
            "{\"schema\":1,\"deviceId\":\"oura-2H3B2405003655\",\"ringTs\":3584349,"
          + "\"utc\":1753440000,\"iso\":\"2026-07-30T09:09:01Z\",\"values\":[395015,394873,394679]}")
    }

    func testEncodeEmptyValuesIsValidJSON() throws {
        let line = OuraCvaPpgDumpLine.encode(deviceId: "oura-x", ringTs: 1, utc: 2, iso: "i", values: [])
        let obj = try JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any]
        XCTAssertEqual(obj?["schema"] as? Int, OuraCvaPpgDumpLine.schema)
        XCTAssertEqual((obj?["values"] as? [Any])?.count, 0)
    }
}
