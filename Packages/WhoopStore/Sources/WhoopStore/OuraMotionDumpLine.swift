import Foundation

/// Pure, deterministic encoder for ONE line of the Oura motion (0x47 motion_events) research corpus — a
/// diagnostic JSONL sidecar, NOT a datastore row.
///
/// WHY a sidecar and not a stream/table (yet): the 0x47 decode is Tier-A (validated against open_oura's
/// `decode_motion`), but mapping its averaged `(x, y, z)` vector into a durable `gravitySample` needs the
/// LSB→g scale the sleep stager's 0.01 g stillness threshold depends on, and that scale can only be pinned
/// from a STILL capture (issue #804). Until it is, the honest-data invariant says: decode + store + log the
/// vector BESIDE the incumbent, never feed it to scoring on a guessed scale. This corpus is that store — a
/// separate, clearly-labeled file the app appends to so the raw motion series can be calibrated offline
/// (resting magnitude → g, cadence, coverage during stillness). It never feeds scoring and is safe to delete.
///
/// FORMAT: newline-delimited JSON (JSONL), one record per line, append-only, FIXED key order so it is stable
/// and testable byte-for-byte. Parallels `OuraActivityDumpLine`.
public enum OuraMotionDumpLine {
    /// Bump when the record shape changes so a downstream reader can branch on `schema`.
    public static let schema = 1

    /// One JSONL record (NO trailing newline — the writer adds it). `deviceId` is a controlled registry id
    /// (e.g. `oura-<serial>`) and `iso` is app-generated, so neither needs JSON string-escaping here.
    ///   - ringTs:        the record's raw ring-clock timestamp (the dedup key: strictly increases per record).
    ///   - utc:           the anchored wall-clock (unix seconds) for the record envelope.
    ///   - iso:           human-readable UTC of `utc` (convenience for eyeballing).
    ///   - orientation:   0…3 orientation code (record byte0 low 2 bits).
    ///   - x/y/z:         the ring's averaged accel vector, signed record byte × 8 (open_oura convention).
    ///   - highIntensity: the period's high-intensity count (record byte5).
    public static func encode(deviceId: String, ringTs: UInt32, utc: Int, iso: String,
                              orientation: Int, x: Int, y: Int, z: Int, highIntensity: Int) -> String {
        return "{\"schema\":\(schema),\"deviceId\":\"\(deviceId)\",\"ringTs\":\(ringTs),"
             + "\"utc\":\(utc),\"iso\":\"\(iso)\",\"orientation\":\(orientation),"
             + "\"x\":\(x),\"y\":\(y),\"z\":\(z),\"high_intensity\":\(highIntensity)}"
    }
}
