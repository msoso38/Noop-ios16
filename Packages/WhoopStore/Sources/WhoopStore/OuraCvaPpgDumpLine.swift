import Foundation

/// Pure, deterministic encoder for ONE line of the Oura CVA raw-PPG (0x81) research corpus — a
/// diagnostic JSONL sidecar, NOT a datastore row.
///
/// WHY a sidecar and not a stream/table: the 0x81 decode is Tier-B (a plausible third-party
/// delta/absolute-anchor formula, not ground-truth-validated — OURA_PROTOCOL.md s6.14). The
/// honest-data invariant forbids Tier-B ever minting a durable scoring row, so it must never touch
/// `Streams`/SQLite. This corpus is a separate, clearly-labeled file the app appends to purely so the
/// reconstructed PPG series can be accumulated for offline investigation (does it correlate with a
/// known PPG channel? do the rare anchor anomalies recur?). It never feeds scoring and is safe to
/// delete. Parallels `OuraActivityDumpLine` / `OuraMotionDumpLine`.
public enum OuraCvaPpgDumpLine {
    /// Bump when the record shape changes so a downstream reader can branch on `schema`.
    public static let schema = 1

    /// One JSONL record (NO trailing newline — the writer adds it). `deviceId` is a controlled registry id
    /// (e.g. `oura-<serial>`) and `iso` is app-generated, so neither needs JSON string-escaping here.
    ///   - ringTs: the record's raw ring-clock timestamp (the dedup key: strictly increases per record).
    ///   - utc:    the anchored wall-clock (unix seconds) for the record envelope.
    ///   - iso:    human-readable UTC of `utc` (convenience for eyeballing).
    ///   - values: the decoded running-total PPG series this ONE record contributed, verbatim.
    public static func encode(deviceId: String, ringTs: UInt32, utc: Int, iso: String, values: [Int]) -> String {
        let valuesStr = values.map { String($0) }.joined(separator: ",")
        return "{\"schema\":\(schema),\"deviceId\":\"\(deviceId)\",\"ringTs\":\(ringTs),"
             + "\"utc\":\(utc),\"iso\":\"\(iso)\",\"values\":[\(valuesStr)]}"
    }
}
