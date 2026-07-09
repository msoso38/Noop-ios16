package com.noop.analytics

/**
 * Build a NOOP sleep session from the Oura ring's OWN anchored sleep-phase timeline
 * (`OURA_SLEEP_PHASE` events, Tier-A 2-bit codes; OURA_PROTOCOL.md §6.12).
 *
 * Why this exists (OURA_PROTOCOL.md §6.12.1): the polished 4-stage hypnogram the Oura APP shows is
 * produced by SleepNet, an encrypted, cloud-key-gated PyTorch model on the phone — it is NOT on the BLE
 * wire and NOOP neither can nor does reproduce it. What DOES cross BLE is the ring's own coarse
 * per-epoch phase classification (awake/light/deep/REM). NOOP builds its OWN session from that, the
 * same honest-data stance as every other NOOP metric.
 *
 * This ALSO bridges an architectural gap: [AnalyticsEngine.analyzeDay] derives sleep from
 * [SleepStager.detectSleep], which is **gravity-driven** — and an Oura ring streams no accelerometer, so
 * the detector returns nothing. The sessions built here are injected into `analyzeDay` as
 * `providedSleepSessions`, taking the place the gravity detector fills for WHOOP, so the ring's night
 * flows through the SAME funnels (dailyMetric sleep totals, the skin-temp window, rest).
 *
 * Pure and platform-neutral: input is `(ts, stage)` pairs (wall-clock unix seconds + the ring's 2-bit
 * code). Byte-for-byte twin of Swift `OuraSleepSessionBuilder`.
 */
object OuraSleepSessionBuilder {

    /**
     * The ring's 2-bit sleep-phase code (OURA_PROTOCOL.md §6.12: `0=awake, 1=light, 2=deep, 3=REM`)
     * mapped to the [StageSegment.stage] string the rest of analytics uses. Returns null for an
     * unknown code (a corrupt/misframed value), so it is dropped rather than guessed (honest-data).
     */
    internal fun stageName(forPhaseCode code: Int): String? = when (code) {
        0 -> "wake"
        1 -> "light"
        2 -> "deep"
        3 -> "rem"
        else -> null
    }

    /**
     * A single anchored phase event: wall-clock unix seconds and the ring's 2-bit phase code.
     */
    data class Phase(val ts: Long, val stage: Int)

    /** The stage-UNKNOWN label for a `check_sleep` window: the ring gives a real bedtime->wake span but no
     *  stage breakdown. [hypnogramMetrics] counts it as sleep TIME (TST) but NOT toward deep/REM/light, so
     *  the day's total-sleep is honest while the stage split stays blank (never fabricated). Mirrors Swift. */
    const val UNKNOWN_STAGE = "asleep"

    /**
     * Build ONE session from the ring's OWN `check_sleep` window (OURA_PROTOCOL.md §6.15) — bedtime->wake
     * unix seconds. The honest sleep-DURATION source: the coarse phase events (§6.12) are sparse
     * connection-time bursts that under-count, whereas `check_sleep s:/e:` is the firmware's own sleep-period
     * decision (validated on device: 7 h 56 m vs a wearer's real 7 h 52 m). The whole window is one
     * stage-unknown `asleep` segment (efficiency 1.0; no overnight HR to measure one, so none is invented).
     * `restingHR`/`avgHRV` are null. Returns null for a non-positive span. Mirrors Swift `session(fromWindowStart:end:)`.
     */
    fun sessionFromWindow(start: Long, end: Long): DetectedSleep? {
        if (end <= start) return null
        val seg = StageSegment(start = start, end = end, stage = UNKNOWN_STAGE)
        return DetectedSleep(
            start = start, end = end, efficiency = 1.0,
            stages = listOf(seg), restingHR = null, avgHRV = null,
        )
    }

    /**
     * Build sleep session(s) from the ring's anchored phase timeline.
     *
     * Each phase event marks a stage that HOLDS until the next event, so consecutive events
     * `[tsᵢ, tsᵢ₊₁)` form one [StageSegment] with `stageᵢ`; the session spans `first → last` event.
     * Adjacent same-stage segments are merged for a clean hypnogram. Efficiency = asleep / in-bed
     * (asleep = non-wake duration). A large inter-event gap splits into separate sessions (a nap vs the
     * overnight), and a session shorter than [minSessionMinutes] or with no asleep time is dropped as
     * noise. `restingHR`/`avgHRV` are left null here — they are enriched by the caller/engine from the
     * night's HR/RR streams, not fabricated from the phase codes.
     *
     * @param phases `(ts, stage)` pairs. Need not be pre-sorted; duplicate timestamps keep the
     *   first-seen stage.
     * @param minSessionMinutes shortest span kept (default 60 — drops stray fragments; a real nap the
     *   ring staged still clears this, an isolated blip does not).
     * @param splitGapMinutes an inter-event gap longer than this starts a new session (default 120).
     */
    fun sessions(
        fromPhases: List<Phase>,
        minSessionMinutes: Int = 60,
        splitGapMinutes: Int = 120,
    ): List<DetectedSleep> {
        // Sort by time; collapse duplicate timestamps (keep first) so a doubled event can't make a
        // zero-length segment.
        val sorted = fromPhases.sortedBy { it.ts }
        val events = ArrayList<Phase>()
        for (e in sorted) if (e.ts != events.lastOrNull()?.ts) events.add(e)
        if (events.size < 2) return emptyList()

        // Split into contiguous runs on a large gap (nap vs overnight).
        val splitGap = splitGapMinutes.toLong() * 60L
        val runs = ArrayList<MutableList<Phase>>()
        var current = mutableListOf(events[0])
        for (e in events.drop(1)) {
            if (e.ts - current[current.size - 1].ts > splitGap) {
                runs.add(current)
                current = mutableListOf(e)
            } else {
                current.add(e)
            }
        }
        runs.add(current)

        val minSpan = minSessionMinutes.toLong() * 60L
        val out = ArrayList<DetectedSleep>()
        for (run in runs) {
            if (run.size < 2) continue
            val start = run[0].ts
            val end = run[run.size - 1].ts
            if (end - start < minSpan) continue

            // One segment per [tsᵢ, tsᵢ₊₁); drop segments whose code is unknown (never guess a stage).
            val segments = ArrayList<StageSegment>()
            for (i in 0 until run.size - 1) {
                val name = stageName(forPhaseCode = run[i].stage) ?: continue
                val seg = StageSegment(start = run[i].ts, end = run[i + 1].ts, stage = name)
                // Merge with the previous segment when the stage is identical and they abut.
                val last = segments.lastOrNull()
                if (last != null && last.stage == seg.stage && last.end == seg.start) {
                    last.end = seg.end
                } else {
                    segments.add(seg)
                }
            }
            if (segments.isEmpty()) continue

            var asleepSeconds = 0L
            for (seg in segments) if (seg.stage != "wake") asleepSeconds += seg.end - seg.start
            if (asleepSeconds <= 0L) continue   // an all-wake run is not a sleep session
            val inBed = end - start
            val efficiency = if (inBed > 0L) minOf(1.0, asleepSeconds.toDouble() / inBed.toDouble()) else 0.0

            out.add(
                DetectedSleep(
                    start = start, end = end, efficiency = efficiency,
                    stages = segments, restingHR = null, avgHRV = null,
                )
            )
        }
        return out
    }
}
