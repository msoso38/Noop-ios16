import Foundation
import CoreBluetooth
import WhoopProtocol
import RawCapture

/// App-side glue around the pure `RawCapture` (`Packages/RawCapture`) + `rawCaptureRecord` adapter
/// (`Packages/WhoopProtocol`): gates on a user toggle, stamps each frame with a wall-clock time and
/// the live (standard-profile) heart rate, and persists the growing capture to a JSON file under
/// Application Support. Read-only with respect to the strap — it only records frames that already
/// arrived, it never writes to the device — so it is always safe to leave on. Covers BOTH WHOOP 4.0
/// (classic envelope) and WHOOP 5.0/MG (puffin envelope) connections; callers thread in an
/// already-parsed `ParsedFrame` (#47's "parse once" convention) rather than raw bytes, so this type
/// never reparses a frame just to capture it.
///
/// `@MainActor` because it reads `LiveState.heartRate` and updates published capture status; the
/// BLEManager delegate callbacks that feed it are already on the main queue.
@MainActor
final class RawFrameRecorder {
    /// UserDefaults flag, mirrored by the Settings toggle (`@AppStorage`). Separate from the puffin
    /// *probe* switch (`PuffinExperiment`): capturing is passive/safe, probing actively guesses.
    static let enabledKey = "noopRawFrameCapture"

    /// Flush to disk every this-many frames so a crash/yank loses at most a handful of frames.
    private static let flushEvery = 25

    /// Soft cap on the total size of the raw-captures directory (#27). One file is written per app
    /// launch and never trimmed, so without a cap the directory grows without bound — an experimental
    /// capture toggle a 5/MG user left on reached 19 GB. After each flush, oldest files are evicted
    /// (by filename, which is timestamp-sorted) until the total is back under the cap. Never deletes
    /// the file the current session is still writing.
    private static let directorySoftCapBytes = 50 * 1024 * 1024

    private weak var state: LiveState?
    private let buffer = RawCapture()
    private var sinceFlush = 0
    private var fileURL: URL?

    init(state: LiveState) {
        self.state = state
    }

    /// Whether the Settings toggle is on. Public so a caller can decide, BEFORE parsing, whether to
    /// pass `collectFields: true` — `rawHex` costs a per-byte allocation pass (D#969) that's wasted
    /// unless capture is actually going to read it.
    var isEnabled: Bool { UserDefaults.standard.bool(forKey: Self.enabledKey) }

    /// `<AppSupport>/OpenWhoop/raw-captures/`, created on demand.
    private static func captureDirectory() throws -> URL {
        let fm = FileManager.default
        let dir = try fm.url(for: .applicationSupportDirectory, in: .userDomainMask,
                             appropriateFor: nil, create: true)
            .appendingPathComponent("OpenWhoop", isDirectory: true)
            .appendingPathComponent("raw-captures", isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Record one already-parsed frame (WHOOP 4.0 classic envelope, off `dataNotifyChar` /
    /// `cmdNotifyChar` / `eventNotifyChar`, or WHOOP 5.0/MG puffin, off `fd4b0003/0004/0005/0007`).
    /// No-op unless capture is enabled. Takes a `ParsedFrame` the caller already computed — never
    /// reparses.
    func capture(parsed: ParsedFrame, char: CBUUID) {
        guard isEnabled else { return }
        let tsMs = Int(Date().timeIntervalSince1970 * 1000)
        let rec = rawCaptureRecord(for: parsed, char: char.uuidString.lowercased(),
                                   tsMs: tsMs, hr: state?.heartRate)
        buffer.record(rec)
        sinceFlush += 1
        state?.rawCaptureCount = buffer.count
        if sinceFlush >= Self.flushEvery { flush() }
    }

    /// Write the full capture to disk (best-effort, atomic). Called periodically and on disconnect.
    func flush() {
        guard buffer.count > 0 else { return }
        do {
            let url = try sessionFileURL()
            let data = try buffer.encodedJSON()
            try data.write(to: url, options: .atomic)
            sinceFlush = 0
            state?.rawCaptureURL = url
            // Bound on-disk growth (#27): evict oldest captures beyond the soft cap, never the
            // file this session is still writing.
            Self.evictOldCaptures(keeping: url)
        } catch {
            // Best-effort: a failed flush just means the next one rewrites the whole file.
        }
    }

    /// Discard everything captured so far (in-memory and on disk) and start a fresh session file on the
    /// next `flush()`. User-triggered from the Test Centre "Clear captured frames" button so a reporter
    /// can wipe an unrelated capture, reproduce the bug, then export a clean sample. Lazy reopen (rather
    /// than eagerly recreating the file here, as the Android twin's `clearRawFrameCapture` does) is fine
    /// on this side: iOS only ever writes one file per app launch, so the next `capture()` call — not a
    /// reconnect — is always imminent while a session is live.
    func clear() {
        buffer.reset()
        sinceFlush = 0
        if let url = fileURL {
            try? FileManager.default.removeItem(at: url)
        }
        fileURL = nil
        state?.rawCaptureCount = 0
        state?.rawCaptureURL = nil
    }

    /// Enforce the directory soft cap by deleting the oldest capture files (best-effort). Filenames are
    /// `raw-yyyyMMdd-HHmmss.json`, so lexicographic order is chronological — delete from the front
    /// until the total is back under the cap. `keep` (the active session file) is never deleted.
    private static func evictOldCaptures(keeping keep: URL) {
        let fm = FileManager.default
        guard let dir = try? captureDirectory() else { return }
        guard let entries = try? fm.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.fileSizeKey],
            options: [.skipsHiddenFiles]) else { return }
        // Sort oldest-first by name (timestamped). Pair each with its size up front.
        let files = entries
            .filter { $0.pathExtension == "json" }
            .map { (url: $0, size: (try? $0.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0) }
            .sorted { $0.url.lastPathComponent < $1.url.lastPathComponent }
        var total = files.reduce(0) { $0 + $1.size }
        for file in files {
            guard total > directorySoftCapBytes else { break }
            if file.url == keep { continue }   // never delete the active session file
            do {
                try fm.removeItem(at: file.url)
                total -= file.size
            } catch {
                // Best-effort: skip a file we couldn't remove; the next flush retries.
            }
        }
    }

    /// One file per recorder lifetime (i.e. per app launch), named on first use. Re-flushing rewrites
    /// the same file, so the capture file always holds the complete session.
    private func sessionFileURL() throws -> URL {
        if let url = fileURL { return url }
        let stamp = Self.fileStampFormatter.string(from: Date())
        let url = try Self.captureDirectory().appendingPathComponent("raw-\(stamp).json")
        fileURL = url
        return url
    }

    private static let fileStampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        return f
    }()
}
