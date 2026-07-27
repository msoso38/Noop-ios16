import Foundation

/// One captured raw BLE frame plus the provenance a protocol mapper needs to correlate bytes
/// against ground truth. Brand-neutral: every field here is a value the caller has already
/// decided (or already decoded elsewhere) — this type never parses or validates anything itself.
///
/// The `hex` key is intentionally the same shape the test fixtures use (`frames.json` is an array
/// of `{"hex": …}`), so a capture file is *directly* usable as a parity fixture — the extra fields
/// are a superset the decoder ignores. Keys are snake_case to match the existing `golden.json`
/// style.
public struct RawCaptureRecord: Codable, Equatable {
    /// Full on-wire frame as lowercase hex — the protocol package's canonical raw-hex rendering.
    public let hex: String
    /// Source notify characteristic UUID (e.g. `fd4b0005-…`) — tells you which channel the frame
    /// arrived on, which is itself a clue to its meaning.
    public let char: String
    /// Capture wall-clock as unix milliseconds. Lets you line a frame up against a known event time.
    public let tsMs: Int
    /// Live heart rate from the *standard* `2A37` profile at capture time, when known. Ground-truth
    /// cross-check: find the byte that tracks this value to locate a brand's HR field.
    public let hr: Int?
    /// Best-effort decoded packet type from the caller's own parse, or nil if it didn't frame.
    public let typeName: String?
    /// Sequence byte — for historical records this doubles as the record *version*, so it matters.
    public let seq: Int?
    /// Did the caller's CRC/checksum check pass?
    public let crcOK: Bool?
    /// Did the frame parse as a well-formed envelope at all, per the caller's own parse?
    public let ok: Bool

    public init(
        hex: String,
        char: String,
        tsMs: Int,
        hr: Int?,
        typeName: String?,
        seq: Int?,
        crcOK: Bool?,
        ok: Bool
    ) {
        self.hex = hex
        self.char = char
        self.tsMs = tsMs
        self.hr = hr
        self.typeName = typeName
        self.seq = seq
        self.crcOK = crcOK
        self.ok = ok
    }

    enum CodingKeys: String, CodingKey {
        case hex, char
        case tsMs = "ts_ms"
        case hr
        case typeName = "type_name"
        case seq
        case crcOK = "crc_ok"
        case ok
    }
}

/// Accumulates already-decided capture records and serialises them in a fixture-compatible JSON
/// shape. Pure (no CoreBluetooth, no file IO, no parsing) — callers decode/validate frames
/// themselves and hand this type the finished `RawCaptureRecord`.
public final class RawCapture {
    public private(set) var records: [RawCaptureRecord] = []

    public init() {}

    public var count: Int { records.count }

    public func reset() { records.removeAll() }

    @discardableResult
    public func record(_ record: RawCaptureRecord) -> RawCaptureRecord {
        records.append(record)
        return record
    }

    /// The full capture (provenance + decode hints), pretty-printed with stable key order.
    public func encodedJSON() throws -> Data {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return try enc.encode(records)
    }

    /// The `[{"hex": …}]` subset — byte-for-byte the shape `Tests/.../Resources/frames.json` expects,
    /// so a capture can be dropped straight into the parity suite.
    public func framesFixtureJSON() throws -> Data {
        struct HexOnly: Encodable { let hex: String }
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
        return try enc.encode(records.map { HexOnly(hex: $0.hex) })
    }
}
