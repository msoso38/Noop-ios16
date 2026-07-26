import Foundation

/// #827: format a `GET_CLOCK` COMMAND_RESPONSE into a clean, readable, copyable report — a verdict, the
/// full raw hex on one line, an offset-labelled payload hex grid, a decoded clock value, and a per-byte
/// diff vs the previous capture. Pure + deterministic, so it's unit-tested without a strap.
///
/// The decode (payload bytes 2..6, u32 LE, unix seconds) is confirmed on BOTH families: WHOOP4 by
/// `PostHooks.swift`'s `"command_response"` hook, and WHOOP 5/MG by two real captures 62s apart on
/// 2026-07-26 (#827) whose decoded clocks advanced by exactly the wall-clock gap between them — not just
/// a plausible-looking single value. The 5/MG offset was reached via the "+4 rule" observed on other
/// COMMAND_RESPONSE fields (e.g. extended-battery soc/mv/charge, `docs/BLE_REVERSE_ENGINEERING.md`): both
/// families' payload arrays start immediately after their own command byte (frame[7] on 4.0, frame[11] on
/// 5/MG — a whole-frame +4 shift that cancels out for a payload-relative offset), so an unchanged field's
/// relative offset carries over.
public enum ClockProbe {

    /// Returns the display text and the payload hex to persist for the next capture's diff (nil when there
    /// is no decodable payload). `cmdOff` is the response-command byte offset (6 on WHOOP4, 10 on 5/MG);
    /// the 4-byte CRC32 trailer both families carry is excluded from the payload.
    public static func format(frame: [UInt8], cmdOff: Int, isWhoop5: Bool, prevPayloadHex: String?) -> (text: String, payloadHex: String?) {
        let fam = isWhoop5 ? "WHOOP 5/MG" : "WHOOP 4.0"
        let payStart = cmdOff + 1
        let payEnd = frame.count - 4
        let hasPayload = payEnd > payStart
        let pay: [UInt8] = hasPayload ? Array(frame[payStart..<payEnd]) : []

        var sb = ""
        sb += "#827 GET_CLOCK PROBE — \(fam)\n"
        sb += "\nRaw frame (\(frame.count) B):\n"
        sb += frame.map { String(format: "%02x", $0) }.joined() + "\n"

        var payloadHex: String?
        if hasPayload {
            payloadHex = pay.map { String(format: "%02x", $0) }.joined()
            sb += "\nPayload (\(pay.count) B, CRC excluded):\n"
            sb += hexGrid(pay)
            if pay.count >= 6 {
                let v = UInt32(pay[2]) | (UInt32(pay[3]) << 8) | (UInt32(pay[4]) << 16) | (UInt32(pay[5]) << 24)
                let plausible = v >= 63_072_000  // ConnectionTrace.rtcEpochCeilingUnix (1972-01-01)
                let date = Date(timeIntervalSince1970: Double(v))
                sb += "\nDecoded clock @2 (u32 LE): \(v) → \(date)"
                sb += plausible ? "  (plausible unix time)\n" : "  (epoch-era — RTC never set, or offset is wrong)\n"
            } else {
                sb += "\nPayload too short (\(pay.count) B) to decode a u32 clock at @2\n"
            }
            sb += "\n"
            if let prevPayloadHex, prevPayloadHex.count == payloadHex!.count {
                let prev = hexToBytes(prevPayloadHex)
                var deltas = ""
                for i in pay.indices where prev[i] != Int(pay[i]) {
                    deltas += String(format: " @%02d:%02x→%02x", i, prev[i], Int(pay[i]))
                }
                sb += deltas.isEmpty
                    ? "Δ vs previous capture: identical"
                    : "Δ vs previous capture:\(deltas)\n"
            } else {
                sb += "Δ vs previous capture: first capture"
            }
        } else {
            sb += "\nNo payload beyond the command byte (bare stub) — GET_CLOCK may not be served on this firmware"
        }
        return (sb, payloadHex)
    }

    /// Offset-labelled hex grid, 8 bytes per row ("  @00  0d 01 …").
    private static func hexGrid(_ bytes: [UInt8]) -> String {
        var sb = ""
        var i = 0
        while i < bytes.count {
            sb += String(format: "  @%02d ", i)
            var j = i
            while j < min(i + 8, bytes.count) {
                sb += String(format: " %02x", bytes[j])
                j += 1
            }
            sb += "\n"
            i += 8
        }
        return sb
    }

    private static func hexToBytes(_ hex: String) -> [Int] {
        let chars = Array(hex)
        var out: [Int] = []
        out.reserveCapacity(chars.count / 2)
        var i = 0
        while i + 1 < chars.count {
            out.append((Int(String(chars[i]), radix: 16) ?? 0) << 4 | (Int(String(chars[i + 1]), radix: 16) ?? 0))
            i += 2
        }
        return out
    }
}
