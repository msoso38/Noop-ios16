import XCTest
@testable import WhoopProtocol

/// #827: the pure formatter for the GET_CLOCK probe result. Byte-parity twin (once ported) of the eventual
/// Kotlin `ClockProbeFormatTest` — includes the REAL WHOOP 5.0 captures that confirmed the offset (two
/// captures 62s apart whose decoded clocks moved by exactly that gap).
final class ClockProbeTests: XCTestCase {

    private func hexToBytes(_ h: String) -> [UInt8] {
        let c = Array(h)
        return stride(from: 0, to: c.count, by: 2).map {
            UInt8((Int(String(c[$0]), radix: 16)! << 4) | Int(String(c[$0 + 1]), radix: 16)!)
        }
    }

    // WHOOP4-shaped synthetic frame: cmd byte (0x0b = GET_CLOCK) @6, payload @7, clock u32 LE @pay[2] =
    // 0x60000000 (1610612736, a plausible 2021-01-13 timestamp), crc32 trailer.
    private let whoop4Frame = "aa00000000000b00000000006046758858"

    // Real WHOOP 5.0 captures (#827), 62s apart, cmd byte 0x0b @10.
    private let realCapture1 = "aa011400010021b1241c0b040151b7656a51380000000000efda48d5"
    private let realCapture2 = "aa011400010021b1241d0b05018fb7656a1e450000000000b000f3e9"

    func testWhoop4_plausibleClock_decodesAndFlagsPlausible() {
        let (text, payHex) = ClockProbe.format(frame: hexToBytes(whoop4Frame), cmdOff: 6, isWhoop5: false, prevPayloadHex: nil)
        XCTAssertTrue(text.contains("WHOOP 4.0"))
        XCTAssertTrue(text.contains("Decoded clock @2 (u32 LE): 1610612736"))
        XCTAssertTrue(text.contains("plausible unix time"))
        XCTAssertEqual(payHex?.count, 6 * 2)
    }

    func testWhoop5RealCapture_decodesConfirmedOffset() {
        let (text, payHex) = ClockProbe.format(frame: hexToBytes(realCapture1), cmdOff: 10, isWhoop5: true, prevPayloadHex: nil)
        XCTAssertTrue(text.contains("WHOOP 5/MG"))
        XCTAssertTrue(text.contains("Decoded clock @2 (u32 LE): 1785050961"))
        XCTAssertTrue(text.contains("plausible unix time"))
        XCTAssertEqual(payHex?.count, 13 * 2)
    }

    func testWhoop5RealCaptures_trackElapsedWallTime() {
        // The decode isn't just "plausible" on one frame — two captures 62s apart moved by exactly 62s,
        // which is the actual confirmation (a wrong offset landing in-plausible-range twice, 62s apart, by
        // coincidence, is not realistic).
        let (_, prev) = ClockProbe.format(frame: hexToBytes(realCapture1), cmdOff: 10, isWhoop5: true, prevPayloadHex: nil)
        let (text, _) = ClockProbe.format(frame: hexToBytes(realCapture2), cmdOff: 10, isWhoop5: true, prevPayloadHex: prev)
        XCTAssertTrue(text.contains("Decoded clock @2 (u32 LE): 1785051023"))
        XCTAssertEqual(1785051023 - 1785050961, 62)
        XCTAssertTrue(text.contains("Δ vs previous capture:"))
    }

    func testEpochEraClock_flagsNotPlausible() {
        // clock = 100 (1970-01-01ish), LE bytes 64 00 00 00.
        let frame = "aa00000000000b00006400000046758858"
        let (text, _) = ClockProbe.format(frame: hexToBytes(frame), cmdOff: 6, isWhoop5: false, prevPayloadHex: nil)
        XCTAssertTrue(text.contains("Decoded clock @2 (u32 LE): 100"))
        XCTAssertTrue(text.contains("epoch-era"))
    }

    func testDiff_flagsTheChangedBytes() {
        let first = hexToBytes(whoop4Frame)
        let (_, prev) = ClockProbe.format(frame: first, cmdOff: 6, isWhoop5: false, prevPayloadHex: nil)
        var second = first
        second[12] = 0x61   // payload offset 5 (frame[7+5]=frame[12])
        let (text, _) = ClockProbe.format(frame: second, cmdOff: 6, isWhoop5: false, prevPayloadHex: prev)
        XCTAssertTrue(text.contains("Δ vs previous capture:"))
        XCTAssertTrue(text.contains("@05:60→61"))
    }

    func testBareStub_isCalledOut() {
        // 11-byte frame: cmd@6=0x0b then only the 4-byte CRC tail, so payEnd(7) == payStart(7) ⇒ no payload.
        let (text, payHex) = ClockProbe.format(frame: hexToBytes("aa0700fa00000b46758858"), cmdOff: 6, isWhoop5: false, prevPayloadHex: nil)
        XCTAssertTrue(text.contains("bare stub"))
        XCTAssertNil(payHex)
    }
}
