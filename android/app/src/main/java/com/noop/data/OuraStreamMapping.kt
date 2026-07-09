package com.noop.data

import com.noop.oura.OuraEvent
import com.noop.protocol.SkinTempSample
import com.noop.protocol.Spo2Sample
import com.noop.protocol.Streams
import com.noop.protocol.WhoopEvent

/**
 * Pure, JVM-testable mapping from the Oura ring's decoded [OuraEvent]s onto the datastore's
 * protocol [Streams] shape, so the WHOOP-isolated `OuraLiveSource` can persist its samples through
 * the SAME [WhoopRepository.insert] path (via [StreamPersistence.toBatch]) the WHOOP pipeline uses,
 * without duplicating row construction in the (untestable) app/BLE target. Kotlin twin of the Swift
 * `OuraStreamMapping` (WhoopStore), built from the architecture plan's section-4 table.
 *
 * HONEST-DATA INVARIANT (hard): we surface ONLY the ring's decoded raw signals and its OWN open
 * event tags. We never read or display Oura's encrypted readiness/sleep scores. NOOP computes its
 * own Charge/Rest downstream:
 *   - the IBI stream becomes [Streams.rr], from which RecoveryScorer reconstructs NOOP's OWN RMSSD;
 *   - the HR stream feeds resting-HR + strain;
 *   - the ring's open 0x5D HRV tag is recorded as an `OURA_HRV` diagnostic event carrying ITS RAW
 *     decoded fields (time_ms/b1/b2) ONLY, never a fabricated rmssd_ms (the int8 b1/b2 byte->ms
 *     scale is not Tier-A; NOOP's scoring RMSSD comes from `rr`, not this tag);
 *   - the open sleep-phase tags become `OURA_SLEEP_PHASE` events folded into a sleep session.
 *
 * Each event carries a ring-clock `ringTimestamp` (not wall-clock). To stay pure and avoid baking a
 * clock model in here, the caller supplies an [anchor] resolving a ring timestamp to wall-clock unix
 * seconds (driven by the ring's 0x42/0x85 time-sync events upstream). When the anchor cannot place a
 * record (anchor returns null), the sample is DROPPED rather than stamped with a guessed time
 * (honest-data invariant), a ts-less biometric row is unstorable anyway.
 */
object OuraStreamMapping {

    /** The event `kind` recorded for the ring's own open HRV (0x5D) tag. Must match Swift exactly. */
    const val EVENT_HRV = "OURA_HRV"

    /** The event `kind` recorded for the ring's own open sleep-phase (0x49.../0x58) tags. */
    const val EVENT_SLEEP_PHASE = "OURA_SLEEP_PHASE"

    /** The event `kind` for the ring's OWN `check_sleep` sleep window (§6.15): the firmware's bedtime->wake
     *  decision, anchored to unix seconds, payload `{start, end}`. The honest sleep-DURATION source —
     *  IntelligenceEngine prefers it over the sparse [EVENT_SLEEP_PHASE] bursts. Stored at `ts = start`
     *  (stable per night). Must match Swift exactly. */
    const val EVENT_SLEEP_WINDOW = "OURA_SLEEP_WINDOW"

    /**
     * Plausible SpO2 oxygen-saturation percentage band. Aligned with open_oura `tools/run_spo2.py`,
     * which computes SpO2 from the r-ratio (tag 0x8b) and CLAMPS the result to [85, 100] - Oura's own
     * reporting floor and the physiologically plausible band for a worn ring. Used as a REJECT gate at
     * the persist boundary (drop outside), NEVER a clamp: an out-of-band value is either a raw
     * sub-channel that is not a percentage (0x77 dc_raw PPG waveform, 0x7B unpinned uint16) or a
     * reassembler-misaligned phantom (the -63..4.7M garbage seen on a SpO2-gated-off Gen 3 Horizon), so
     * there is no genuine reading to clamp - forcing garbage to "100%" would fabricate an oxygen value,
     * violating the honest-data invariant. Must match the Swift twin (OuraStreamMapping.plausibleSpO2Percent).
     */
    val PLAUSIBLE_SPO2_PERCENT = 85..100

    /**
     * Fold a batch of decoded [events] into a protocol [Streams] for one flush. [anchor] maps a
     * ring-clock timestamp to wall-clock unix seconds (null => drop the sample). Pure: no BLE, no DB,
     * no clock, fully JVM-unit-testable. Tier-B events never reach scoring; if any leak in (they only
     * appear when the driver's allowTierB is set), they are ignored here so they cannot fabricate a
     * stream value.
     */
    fun streams(events: List<OuraEvent>, anchor: (Long) -> Int?): Streams {
        val out = Streams()
        for (ev in events) {
            when (ev) {
                is OuraEvent.Hr -> {
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.hr.add(com.noop.protocol.HrSample(ts, ev.value.bpm))
                }

                is OuraEvent.Ibi -> {
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.rr.add(com.noop.protocol.RrInterval(ts, ev.value.ibiMs))
                }

                is OuraEvent.Hrv -> {
                    // The ring's OWN open HRV tag, recorded raw for diagnostics/parity. NOT Oura's
                    // readiness score, and NOT used as NOOP's RMSSD (that comes from `rr`).
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.events.add(
                        WhoopEvent(
                            ts = ts,
                            kind = EVENT_HRV,
                            payload = linkedMapOf(
                                "time_ms" to ev.value.timeMs,
                                "b1" to ev.value.b1,
                                "b2" to ev.value.b2,
                            ),
                        ),
                    )
                }

                is OuraEvent.Spo2 -> {
                    // Persist only PLAUSIBLE SpO2 percentages (PLAUSIBLE_SPO2_PERCENT, open_oura's
                    // [85,100]). The ONLY decoder with established percentage semantics is 0x6F (direct
                    // per-second %, ~95-96; OURA_PROTOCOL.md s6.5); the 0x7B uint16 (unpinned scale) and
                    // 0x77 dc_raw PPG waveform are NOT oxygen-saturation percentages, so any value outside
                    // the band is either one of those raw sub-channels or a reassembler-misaligned phantom
                    // (the -63..4.7M garbage seen on a SpO2-gated-off Gen 3 Horizon). DROP it rather than
                    // persist an impossible reading: nothing downstream reads spo2 red, so this loses no
                    // real signal and yields ZERO rows on a ring with SpO2 feature 0x04 OFF. A genuine 0x6F
                    // ~95-96 still passes. The ring exposes ONE combined channel: value in `red`, `ir` 0
                    // (unread channel, never fabricated), `unit` carries the decoder's own scale tag.
                    // PARITY: mirror the Swift twin's [85,100] gate exactly.
                    if (ev.value.value !in PLAUSIBLE_SPO2_PERCENT) continue
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.spo2.add(Spo2Sample(ts = ts, red = ev.value.value, ir = 0, unit = ev.value.unit))
                }

                is OuraEvent.Temp -> {
                    // The ring exposes skin temperature in degrees C; the store's raw integer uses the
                    // codebase-wide CENTI-degree-C convention (°C = raw / 100, the scale the analytics
                    // reader divides by), so persist celsius * 100 and tag the unit. PARITY: the Swift
                    // twin stores the IDENTICAL celsius * 100, so the same decoded celsius yields the same
                    // raw integer on both platforms.
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.skinTemp.add(
                        SkinTempSample(
                            ts = ts,
                            raw = Math.round(ev.value.celsius * 100.0).toInt(),
                            unit = "centi_c",
                        ),
                    )
                }

                is OuraEvent.SleepPhaseEvent -> {
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.events.add(
                        WhoopEvent(
                            ts = ts,
                            kind = EVENT_SLEEP_PHASE,
                            payload = linkedMapOf<String, Any?>(
                                "phase" to ev.value.stage.raw,
                                "index" to ev.value.index,
                            ),
                        ),
                    )
                }

                is OuraEvent.Battery -> {
                    // Live battery percent. No ring timestamp on a battery reading (it is a command
                    // response), so it is stamped by the live source's `onBattery` path, not persisted
                    // as a tied-to-ts row here. Leave the batch's battery list empty (honest: no faked ts).
                }

                // Motion / state / time-sync / rtc / debug / TierB / ActivityInfo never map onto a
                // scored stream. In particular the 0x50 activity/MET decode (PR #960) NEVER mints a
                // `steps` row: the formula is third-party and unvalidated (Tier B, OURA_PROTOCOL.md
                // s6.13), and MET is not a step count - fabricating one would break the honest-data
                // invariant and the per-source day-owner rules.
                else -> Unit
            }
        }
        return out
    }
}
