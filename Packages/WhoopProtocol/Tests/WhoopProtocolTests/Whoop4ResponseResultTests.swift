import XCTest
@testable import WhoopProtocol

/// COMMAND_RESPONSE origin-seq echo + result code — the Swift twin of Android's `Whoop4ResponseResultTest`
/// (#894, closing the gap #791 closed on the other platform).
///
/// Kotlin has published `resp_seq` and `result` on 5/MG since the port and on 4.0 since #791. Swift published
/// neither, on either family: an Apple strap log named the command a response was for and then said nothing
/// about whether it had succeeded. The consequence was not only cosmetic — every probe that needed the answer
/// re-derived the byte privately (`FeatureFlagProbe.resultLabel`, `DeviceConfigReadProbe`,
/// `BodyLocationProbe`'s "Result code @12"), three copies of one two-line decode.
///
/// The frames below are the **same real captures the Kotlin test uses**, byte for byte, so the two suites now
/// assert the same decode of the same bytes — which is the only thing that actually holds two independent
/// reimplementations together. They come from the #791 report (WHOOP 4.0, Galaxy S24 Ultra).
final class Whoop4ResponseResultTests: XCTestCase {

    private func bytes(_ s: String) -> [UInt8] {
        var out = [UInt8](); var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            out.append(UInt8(s[i..<j], radix: 16)!); i = j
        }
        return out
    }

    /// A `GET_DATA_RANGE` the strap actually answered: result `0x01` = SUCCESS, origin-seq echo `0x0A`.
    private let answeredDataRangeHex =
        "aa4c00a7247e220a01010000000050070100a307010050070100000000000000020035030000" +
        "c2fc13008046394b00000000bbe1636aa02d0000bbe1636aa02d0000c6e4636ac07c000000000447ea04"

    func testAnsweredDataRangeReportsSuccessAndItsOriginSeq() {
        let f = parseFrame(bytes(answeredDataRangeHex))
        XCTAssertEqual(f.crcOK, true)
        XCTAssertEqual(f.parsed["resp_cmd"]?.stringValue, "GET_DATA_RANGE(34)")
        XCTAssertEqual(f.parsed["resp_seq"]?.intValue, 0x0A)
        XCTAssertEqual(f.parsed["result"]?.stringValue, "SUCCESS(1)")
    }

    /// The empty extended-battery reply at the heart of #791: acknowledged, correct echo, result FAILURE with
    /// an all-zero payload. Without the result byte this is indistinguishable from a success carrying zeros —
    /// a materially different conclusion, and the one the reporter had to hand-decode from raw hex.
    private var emptyExtendedBatteryHex: String {
        "aa2400fa2495622200" + String(repeating: "00", count: 27) + "0ac33306"
    }

    func testEmptyExtendedBatteryReplyReportsFailureNotEmptySuccess() {
        let f = parseFrame(bytes(emptyExtendedBatteryHex))
        XCTAssertEqual(f.crcOK, true)
        XCTAssertEqual(f.parsed["resp_cmd"]?.stringValue, "GET_EXTENDED_BATTERY_INFO(98)")
        XCTAssertEqual(f.parsed["resp_seq"]?.intValue, 0x22)
        XCTAssertEqual(f.parsed["result"]?.stringValue, "FAILURE(0)")
    }

    /// The duplicate-write symptom the echo makes legible. One `GET_DATA_RANGE` send produced three CRC-valid
    /// responses whose strap-side seq advanced (0x7E/0x7F/0x80) while the origin-seq echo stayed at 0x0A — so
    /// the strap really did receive the command three times, visible in a log instead of by frame archaeology.
    func testDuplicateResponsesShareOneOriginSeqAcrossAdvancingStrapSeq() {
        let bodyAfterCmd = "0a01010000000050070100a307010050070100000000000000020035030000" +
            "c2fc13008046394b00000000bbe1636aa02d0000bbe1636aa02d0000c6e4636ac07c000000000447ea04"
        let echoes = ["7e", "7f", "80"].map { strapSeq -> Int? in
            let f = parseFrame(bytes("aa4c00a724\(strapSeq)22" + bodyAfterCmd))
            XCTAssertEqual(f.parsed["resp_cmd"]?.stringValue, "GET_DATA_RANGE(34)")
            return f.parsed["resp_seq"]?.intValue
        }
        XCTAssertEqual(echoes, [0x0A, 0x0A, 0x0A] as [Int?])
    }

    /// Fails closed. A response whose declared payload stops before the result byte must decode neither field
    /// rather than read the CRC32 trailer as a result code — the reason both fields are read from the bounded
    /// payload slice and not from a raw frame offset.
    func testShortResponseDecodesNoResultRatherThanReadingTheCrc() {
        // Inner record is [type][seq][cmd] only: declared length 7, so the payload is empty and the four
        // bytes at offsets 7..10 are the CRC32 trailer.
        let inner: [UInt8] = [36, 0x11, 34]
        var frame: [UInt8] = [0xAA, 7, 0, 0]
        frame[3] = crc8(Array(frame[1..<3]))
        frame += inner
        let c32 = crc32(inner)
        frame += [UInt8(c32 & 0xFF), UInt8((c32 >> 8) & 0xFF),
                  UInt8((c32 >> 16) & 0xFF), UInt8((c32 >> 24) & 0xFF)]

        let f = parseFrame(frame)
        XCTAssertEqual(f.crcOK, true)
        XCTAssertEqual(f.parsed["resp_cmd"]?.stringValue, "GET_DATA_RANGE(34)")
        XCTAssertNil(f.parsed["resp_seq"])
        XCTAssertNil(f.parsed["result"])
    }

    /// A counterexample to the result-byte reading itself, pinned rather than left to surprise someone.
    ///
    /// This is a real 4.0 `GET_BATTERY_LEVEL` reply carrying a valid 42.5% — it plainly succeeded — and its
    /// `pay[1]` is `0x00`, which the #791 mapping renders `FAILURE(0)`. The parity corpus frame below and the
    /// Kotlin `FramingTest` battery fixture (91.2%) both have that shape, so it is not a one-off.
    ///
    /// The #791 evidence for `pay[1] == result` was an answered `GET_DATA_RANGE` (`0x01`) against an empty
    /// `GET_EXTENDED_BATTERY_INFO` (`0x00`), which those two frames fit and this one does not.
    ///
    /// The telling detail is that `resp_seq` is `0` here too — the whole two-byte prefix is zeroed, where
    /// every other real capture in the tree (both families) carries a plausible non-zero echo. So the likely
    /// reading is not that `0x00` means something other than FAILURE, but that a 4.0 battery reply does not
    /// carry a `[seq][result]` prefix at all, its value simply sitting at a fixed `pay[2..4]`. Android has
    /// rendered this same frame as FAILURE since #791, so the question is upstream of this change rather
    /// than introduced by it — matching the twin is the point here. Tracked in #900; asserted meanwhile so
    /// the behaviour is deliberate and a future correction has to come past this test.
    func testSuccessfulBatteryReadStillReportsResultZero() {
        let f = parseFrame(bytes("aa0f00c324141a0000a9010000000052cd1a49"))
        XCTAssertEqual(f.crcOK, true)
        XCTAssertEqual(f.parsed["battery_pct"]?.doubleValue, 42.5)   // it succeeded
        XCTAssertEqual(f.parsed["resp_seq"]?.intValue, 0)            // echo zeroed too, not just the result
        XCTAssertEqual(f.parsed["result"]?.stringValue, "FAILURE(0)")
    }

    /// An undocumented result code stays a number rather than becoming an invented name, and renders exactly
    /// as the Kotlin twin's `hexLabel` does.
    func testUnknownResultCodeRendersAsHexNotAName() {
        let inner: [UInt8] = [36, 0x11, 34, 0x0A, 0x7F]
        var frame: [UInt8] = [0xAA, 9, 0, 0]
        frame[3] = crc8(Array(frame[1..<3]))
        frame += inner
        let c32 = crc32(inner)
        frame += [UInt8(c32 & 0xFF), UInt8((c32 >> 8) & 0xFF),
                  UInt8((c32 >> 16) & 0xFF), UInt8((c32 >> 24) & 0xFF)]

        let f = parseFrame(frame)
        XCTAssertEqual(f.parsed["result"]?.stringValue, "0x7F(127)")
    }
}
