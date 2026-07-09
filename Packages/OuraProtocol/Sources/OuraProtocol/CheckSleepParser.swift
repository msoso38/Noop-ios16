import Foundation

/// Parse the ring's OWN computed sleep window out of its `check_sleep` debug-text (`0x43`) stream
/// (OURA_PROTOCOL.md §6.15). This is a PROTOTYPE / INVESTIGATION source (OURA_PROTOCOL.md §6.12.1):
/// the ring's firmware periodically logs its sleep-detection state as plain ASCII lines —
///
///     check_sleep
///     s: 114643            ← bedtime, a ring timestamp in the anchor's domain
///     e: 446340            ← wake,    a ring timestamp in the anchor's domain
///     not needed
///
/// — and those `s:` / `e:` values are ring timestamps in the SAME domain as the `0x42` UTC anchor
/// (§5.5), so `OuraDriver.unixSeconds(forRingTimestamp:)` converts them straight to bedtime/wake UTC.
///
/// Why this matters: the decoded `OURA_SLEEP_PHASE` (`0x4E`) events turn out to be sparse bursts the
/// ring emits at connection time, NOT a continuous overnight timeline — so a session built from them
/// under-counts (a 9.2 h night read as ~5 h). The `s:`/`e:` window is the ring's own boundary decision
/// and is far more reliable for sleep DURATION. Unlike the Tier-B `sleep_summary` tags (`0x49/4B/4C/
/// 57/58`, which are ASCII letters `I/K/L/W/X` a framing desync aliases out of debug text — verified as
/// junk, never a real summary), the `s:`/`e:` lines are unambiguous ASCII and safe to read.
///
/// Honest-data stance: this only reads what the ring itself computed; it never fabricates stages. Kept
/// pure and platform-neutral (input is text lines) so it unit-tests in the fast package loop. It is an
/// INVESTIGATION prototype — the caller LOGS the anchored window, does not yet persist a session.
public struct OuraCheckSleepParser {
    private var lastStartRt: UInt32?
    private var lastEndRt: UInt32?
    private var lastEmitted: Window?

    /// A ring-timestamp sleep window: `startRt` = bedtime, `endRt` = wake, both in the anchor domain.
    public struct Window: Equatable, Sendable {
        public let startRt: UInt32
        public let endRt: UInt32
        public init(startRt: UInt32, endRt: UInt32) { self.startRt = startRt; self.endRt = endRt }
    }

    /// Longest plausible in-bed span, in ring ticks (100 ms/tick, §5.5): 18 h. A `check_sleep` block can
    /// emit a lone `e:` (wake) with no fresh `s:`, which would otherwise pair the NEW wake against the
    /// PREVIOUS night's stale `s:` — observed live as a phantom 33 h window (2026-07-09). No real sleep is
    /// 18 h, so reject any window longer than this rather than emit a cross-block mis-pairing (honest-data).
    private static let maxWindowTicks: UInt32 = 648_000   // 18 h × 3600 s × 10 ticks/s

    public init() {}

    /// Clear the accumulated state (call on stop/disconnect so a new session starts fresh).
    public mutating func reset() {
        lastStartRt = nil
        lastEndRt = nil
        lastEmitted = nil
    }

    /// Feed ONE trimmed debug-text line. Returns a `Window` when a NEW, complete (`endRt > startRt`)
    /// sleep window is recognized; nil otherwise (an unrelated line, an incomplete pair, or a window
    /// identical to the last one emitted — so repeated `e:` refinements collapse to one emit each).
    ///
    /// Matches ONLY the exact lowercase boundary lines `s: <digits>` / `e: <digits>` the firmware emits
    /// for sleep (so `tsc:…`, `bed:…`, `ns=…`, etc. are ignored), keeping the guess honest.
    public mutating func ingest(line: String) -> Window? {
        if let rt = Self.ringValue(line, prefix: "s:") {
            lastStartRt = rt
        } else if let rt = Self.ringValue(line, prefix: "e:") {
            lastEndRt = rt
        } else {
            return nil                         // not a boundary line — nothing to update
        }
        guard let s = lastStartRt, let e = lastEndRt, e > s, e - s <= Self.maxWindowTicks else { return nil }
        let window = Window(startRt: s, endRt: e)
        guard window != lastEmitted else { return nil }   // unchanged since last emit → don't re-log
        lastEmitted = window
        return window
    }

    /// Parse `"<prefix> <digits>"` (single ASCII space) into a ring timestamp, or nil if the line is
    /// not exactly that shape. Rejects a non-numeric or overflowing tail rather than guessing.
    private static func ringValue(_ line: String, prefix: String) -> UInt32? {
        guard line.hasPrefix(prefix) else { return nil }
        let rest = line.dropFirst(prefix.count).drop(while: { $0 == " " })
        guard !rest.isEmpty, rest.allSatisfy(\.isNumber) else { return nil }
        return UInt32(rest)
    }
}
