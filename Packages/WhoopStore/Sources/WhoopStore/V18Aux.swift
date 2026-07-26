import Foundation
import WhoopProtocol

/// Storage codec for the 5/MG v18 auxiliary-field stream (`v18AuxSample.fields`).
///
/// WHY A BLOB AND NOT COLUMNS. There are fifteen slots and they arrive once per strap-second. Fifteen
/// nullable columns on a hot per-second table costs a header byte per column per row even when the value
/// is NULL, makes every future slot a migration, and spends schema surface on bytes whose meaning nobody
/// has pinned yet. One compact blob costs a 3-byte header plus only the bytes actually present, and a new
/// slot appends a bitmap bit instead of a column. The tradeoff is that SQL cannot filter on a slot — which
/// is the right trade here, because nothing queries these yet and a census reads whole rows anyway.
///
/// WIRE FORMAT (little-endian throughout, no padding, no alignment):
///
///     byte  0      format version (`formatVersion`)
///     bytes 1..2   u16 presence bitmap; bit `V18AuxSlot.rawValue` set == that slot follows
///     bytes 3..    each PRESENT slot's value, in ascending slot order, `slot.width` bytes each
///
/// Absence is a first-class state: a clear bit means "the strap did not report this field", never 0. Slots
/// are unsigned integers of 1, 2, or 4 bytes — including `unknownF32At113`, which is banked as its raw
/// 32-bit pattern. One uniform integer path is what lets the Swift and Kotlin codecs be checked
/// byte-for-byte against each other; interpretation happens at the accessor, not on the wire.
///
/// Byte-identical to the Kotlin `V18AuxCodec` (`android/.../data/V18AuxCodec.kt`). Both sides derive the
/// slot order and widths from `V18AuxSlot`, so neither can drift without the other failing its own test.
public enum V18AuxCodec {
    /// Bumped only for an INCOMPATIBLE layout change. A reader that meets a version it does not know
    /// returns an empty sample rather than mis-parsing bytes as a slot they are not.
    public static let formatVersion = 1
    /// version byte + u16 bitmap.
    static let headerBytes = 3

    /// Pack a sample's slots. Returns an empty `Data` when nothing is present, so a caller can skip the
    /// row entirely rather than banking an all-absent record.
    public static func pack(_ sample: V18AuxSample) -> Data {
        let values = sample.slotValues
        var bitmap = 0
        var body = Data()
        for slot in V18AuxSlot.allCases {
            guard slot.rawValue < values.count, let v = values[slot.rawValue] else { continue }
            bitmap |= (1 << slot.rawValue)
            // Truncate to the slot's declared width. Every value here came off the wire at that width,
            // so this is a no-op in practice and a guard against a caller inventing an out-of-range one.
            let u = UInt32(truncatingIfNeeded: v)
            for byte in 0..<slot.width { body.append(UInt8(truncatingIfNeeded: u >> (8 * byte))) }
        }
        if bitmap == 0 { return Data() }
        var out = Data(capacity: headerBytes + body.count)
        out.append(UInt8(formatVersion))
        out.append(UInt8(truncatingIfNeeded: bitmap))
        out.append(UInt8(truncatingIfNeeded: bitmap >> 8))
        out.append(body)
        return out
    }

    /// Inverse of `pack`. A malformed, truncated, or unknown-version blob yields an all-nil sample rather
    /// than throwing: a read path over durable rows must never crash on one bad blob. Trailing bytes past
    /// the last known slot are ignored, so a row written by a LATER build with more slots still decodes
    /// the slots this build knows.
    public static func unpack(_ data: Data, ts: Int) -> V18AuxSample {
        let bytes = [UInt8](data)
        let empty = V18AuxSample(ts: ts)
        guard bytes.count >= headerBytes, Int(bytes[0]) == formatVersion else { return empty }
        let bitmap = Int(bytes[1]) | (Int(bytes[2]) << 8)
        var values = [Int?](repeating: nil, count: V18AuxSlot.allCases.count)
        var i = headerBytes
        for slot in V18AuxSlot.allCases {
            guard bitmap & (1 << slot.rawValue) != 0 else { continue }
            guard i + slot.width <= bytes.count else { return V18AuxSample(ts: ts, slotValues: values) }
            var u = 0
            for byte in 0..<slot.width { u |= Int(bytes[i + byte]) << (8 * byte) }
            values[slot.rawValue] = u
            i += slot.width
        }
        return V18AuxSample(ts: ts, slotValues: values)
    }
}
