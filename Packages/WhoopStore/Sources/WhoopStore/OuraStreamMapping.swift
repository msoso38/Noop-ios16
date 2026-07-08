import Foundation
import WhoopProtocol
import OuraProtocol

/// Pure, testable mapping from a batch of decoded `OuraEvent` (emitted by `OuraProtocol.OuraDriver`)
/// onto the datastore's `Streams` shape, so the isolated live Oura source (`OuraLiveSource` in the app
/// target) can persist its samples through the SAME `StreamStore.insert` path the WHOOP pipeline uses,
/// keyed by the ring's own `deviceId`, without duplicating row-construction logic in the app target
/// where it can't be unit-tested. Parallels `StandardHRMapping`.
///
/// Honest-data invariant (hard): we surface only the ring's decoded raw signals + its own open event
/// tags (HR/IBI/HRV/SpO2/temp/sleep-phase/battery). We NEVER read or surface Oura's encrypted readiness
/// or sleep scores. NOOP computes its own Charge/Rest downstream from these per-device streams. The
/// `OuraHRV` 0x5D tag is the ring's OWN RMSSD-derived HRV signal (OURA_PROTOCOL.md s6.9), not a readiness
/// score; NOOP also independently reconstructs RMSSD from the IBI streams for its own scoring.
///
/// Timestamping: the live source streams a batch and stamps every row at the arrival wall-clock `ts`
/// (unix seconds), exactly as `StandardHRMapping.samples(...at:)` does. The decoded events carry only a
/// ring-clock `ringTimestamp` (a `(session << 16) | counter` value, NOT wall-clock), so anchoring is the
/// transport's job; the mapping stays pure and deterministic by taking the wall-clock `ts` as input. A
/// signal that could not be decoded never reaches this layer (the decoders return nil upstream), so a
/// missing stream stays empty here, never faked (Huami precedent).
///
/// Tier-B (UNVERIFIED) events are dropped: only Tier-A decoded signals map into `Streams`, so an
/// unverified summary can never silently feed scoring.
public enum OuraStreamMapping {
    /// WhoopEvent.kind for the ring's own HRV 0x5D tag. The payload carries the RAW decoded fields
    /// (`time_ms`/`b1`/`b2`) only, never a fabricated `rmssd_ms` (the b1/b2 byte -> ms scale is not
    /// Tier-A; see OURA_PROTOCOL.md s6.9). Must match the Kotlin twin (OuraStreamMapping.kt) exactly.
    public static let hrvEventKind = "OURA_HRV"
    /// WhoopEvent.kind for a decoded sleep-phase code (2-bit: awake/light/deep/rem).
    public static let sleepPhaseEventKind = "OURA_SLEEP_PHASE"

    /// Plausible SpO2 oxygen-saturation percentage band. Aligned with open_oura `tools/run_spo2.py`,
    /// which computes SpO2 from the r-ratio (tag 0x8b) and CLAMPS the result to `[85, 100]` — Oura's own
    /// reporting floor and the physiologically plausible band for a worn ring. NOOP uses it as a REJECT
    /// gate at the persist boundary (drop outside), NEVER a clamp: an out-of-band value is either a raw
    /// sub-channel that is not a percentage (0x77 `dc_raw` PPG waveform, 0x7B unpinned uint16) or a
    /// reassembler-misaligned phantom (the −63…4.7M garbage seen on a SpO2-gated-off Gen 3 Horizon), so
    /// there is no genuine reading to clamp — forcing garbage to "100 %" would fabricate an oxygen value,
    /// violating the honest-data invariant. Must match the Kotlin twin (OuraStreamMapping.kt).
    public static let plausibleSpO2Percent = 85...100

    /// Build a `Streams` from a batch of decoded Oura events, all stamped at the arrival wall-clock `ts`
    /// (unix seconds). Pure → unit-testable. Section-4 table:
    ///   - `.hr`         (0x55 live-HR push)            → `hr:[HRSample]`
    ///   - `.ibi`        (0x44/0x60 IBI)                → `rr:[RRInterval]`
    ///   - `.hrv`        (0x5D HRV tag, raw int8 b1/b2)  → `events:[WhoopEvent(kind: OURA_HRV)]`
    ///   - `.spo2`       (0x6F direct %)                → `spo2:[SpO2Sample]` (only plausible % [85,100])
    ///   - `.temp`       (0x46/0x75)                    → `skinTemp:[SkinTempSample(raw_adc)]`
    ///   - `.sleepPhase` (0x4E/0x5A 2-bit codes)        → `events:[WhoopEvent(kind: OURA_SLEEP_PHASE)]`
    ///   - `.battery`                                   → `battery:[BatterySample]`
    /// Every other event case (`.motion`, `.state`, `.timeSync`, `.rtcBeacon`, `.debugText`, `.tierB`,
    /// `.activityInfo`) is intentionally not folded into a durable stream here. In particular the 0x50
    /// activity/MET decode NEVER mints a `steps` row: the formula is third-party and unvalidated (Tier B,
    /// OURA_PROTOCOL.md s6.13), and MET is not a step count - fabricating one would break the honest-data
    /// invariant and the per-source day-owner rules.
    public static func streams(from events: [OuraEvent], at ts: Int) -> Streams {
        var out = Streams()
        for e in events {
            switch e {
            case .hr(let v):
                // Honest HR: surface only the ring's decoded BPM. The push also carries one IBI, but the
                // dedicated `.ibi` events are the R-R source, so we do not synthesise an RR row from the HR
                // push here to avoid double-counting the same interval.
                out.hr.append(HRSample(ts: ts, bpm: v.bpm))

            case .ibi(let v):
                out.rr.append(RRInterval(ts: ts, rrMs: v.ibiMs))

            case .hrv(let v):
                // The ring's own 0x5D tag, carried RAW for diagnostics/parity. The two int8 fields
                // (b1/b2) plus the sample's relative time offset are surfaced under units-neutral keys.
                // We do NOT mint an `rmssd_ms` here: the int8 b1/b2 byte -> millisecond scaling is NOT
                // Tier-A (OURA_PROTOCOL.md s6.9 leaves it unpinned), so labelling a raw byte as a
                // millisecond RMSSD would fabricate units (honest-data invariant). NOOP's own scoring
                // RMSSD is reconstructed from the IBI stream (`rr`), never from this open tag. Keys and
                // values are IDENTICAL to the Kotlin twin (OuraStreamMapping.kt) so both platforms emit
                // byte-for-byte the same OURA_HRV payload.
                out.events.append(WhoopEvent(ts: ts, kind: hrvEventKind, payload: [
                    "time_ms": .int(v.timeMs),
                    "b1": .int(v.b1),
                    "b2": .int(v.b2),
                ]))

            case .spo2(let v):
                // Persist only PLAUSIBLE SpO2 percentages (`plausibleSpO2Percent`, open_oura's [85,100]).
                // The ONLY decoder with established percentage semantics is 0x6F (direct per-second %,
                // ~95-96; OURA_PROTOCOL.md s6.5); the 0x7B uint16 (unpinned scale) and 0x77 `dc_raw` PPG
                // waveform are NOT oxygen-saturation percentages, so any value outside the band is either
                // one of those raw sub-channels or a reassembler-misaligned phantom (the −63…4.7M garbage
                // seen on a SpO2-gated-off Gen 3 Horizon). We DROP it rather than persist an impossible
                // reading: nothing downstream reads spo2 `red` (AnalyticsEngine nulls spo2Pct), so this
                // loses no real signal and yields ZERO rows on a ring with SpO2 feature 0x04 OFF. A genuine
                // 0x6F ~95-96 still passes. `SpO2Sample` is the WHOOP-shaped two-channel raw row: decoded
                // value on `red`, `ir` at 0 (no second channel), `unit` carries the decoder's own scale
                // tag. PARITY: mirror this gate in the Kotlin twin (OuraStreamMapping.kt) exactly.
                guard plausibleSpO2Percent.contains(v.value) else { continue }
                out.spo2.append(SpO2Sample(ts: ts, red: v.value, ir: 0, unit: v.unit))

            case .temp(let v):
                // The decoder yields degrees C. The durable `SkinTempSample.raw` is an integer in the
                // codebase-wide CENTI-degree-C convention: the WHOOP @73 historical path stores raw at
                // this scale and the analytics reader (AnalyticsEngine.skinTempFunnel) divides raw by 100
                // to recover °C. We store the SAME centi-°C scale so an Oura night reads on the SAME gates
                // as a WHOOP night with no scorer change, and tag the unit so the convention is explicit
                // and never silently assumed. PARITY: the Kotlin twin stores the SAME celsius * 100, so a
                // given decoded celsius yields an IDENTICAL raw integer on both platforms.
                out.skinTemp.append(SkinTempSample(ts: ts, raw: Int((v.celsius * 100).rounded()), unit: "centi_c"))

            case .sleepPhase(let v):
                out.events.append(WhoopEvent(ts: ts, kind: sleepPhaseEventKind, payload: [
                    "phase": .int(v.stage.rawValue),
                    "index": .int(v.index),
                ]))

            case .battery(let v):
                out.battery.append(BatterySample(
                    ts: ts,
                    soc: Double(v.percent),
                    mv: v.voltageMv,
                    charging: v.charging))

            case .motion, .state, .timeSync, .rtcBeacon, .debugText, .tierB, .activityInfo:
                // Not a durable per-device stream row (timeSync/rtcBeacon anchor the transport's clock;
                // motion/state/debug are diagnostics; Tier-B / .activityInfo are UNVERIFIED and must
                // never feed scoring or the steps stream).
                continue
            }
        }
        return out
    }
}
