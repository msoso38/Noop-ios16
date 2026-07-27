import Foundation
import RawCapture

/// Map an already-parsed frame into the shared, brand-neutral capture record.
///
/// Pure mapper — no `parseFrame` call in here. Callers parse once (on the WHOOP4 classic-envelope
/// path, or the WHOOP5 puffin path) and thread that single `ParsedFrame` both to the live router
/// and to this adapter, instead of the frame being reparsed just to capture it (#47's "parse once,
/// thread the result" convention). Works for both `.whoop4` and `.whoop5` frames alike — the
/// per-family decode hints (`typeName`, `seq`, `crcOK`, `ok`) already live on `ParsedFrame`, so
/// there's nothing family-specific left to do here.
public func rawCaptureRecord(for parsed: ParsedFrame, char: String, tsMs: Int, hr: Int?) -> RawCaptureRecord {
    RawCaptureRecord(
        hex: parsed.rawHex,
        char: char,
        tsMs: tsMs,
        hr: hr,
        typeName: parsed.ok ? parsed.typeName : nil,
        seq: parsed.seq,
        crcOK: parsed.crcOK,
        ok: parsed.ok
    )
}
