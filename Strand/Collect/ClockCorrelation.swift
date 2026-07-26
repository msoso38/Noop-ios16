import Foundation
import WhoopProtocol
import WhoopStore

/// Pure helper: correlate the strap's own clock reading to wall time. On WHOOP 4.0, REALTIME_DATA
/// timestamps are a device monotonic epoch that needs this (device, wall) pair to map to unix time. On
/// WHOOP 5/MG the device timestamps are already real-unix, so the resulting offset is small — but it
/// still captures the strap's actual RTC drift vs wall time, which a forced-identity ref (device==wall)
/// would hide (#827). No CoreBluetooth, no I/O — fully unit-testable.
enum ClockCorrelation {
    /// Build a `ClockRef` from a decoded GET_CLOCK COMMAND_RESPONSE frame and the wall
    /// time observed when the response arrived. Returns nil unless the frame parsed OK,
    /// passed CRC, and carries a `clock` value.
    static func clockRef(from parsed: ParsedFrame, wall: Int) -> ClockRef? {
        guard parsed.ok, parsed.crcOK != false,
              let device = parsed.parsed["clock"]?.intValue else { return nil }
        return ClockRef(device: device, wall: wall)
    }
}
