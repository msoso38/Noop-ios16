package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * DaytimeStress.kt — an intraday (hour-by-hour) read of the SAME autonomic stress proxy
 * the daily Stress monitor shows, computed from the day's banked HR + R-R.
 *
 * Faithful Kotlin port of StrandAnalytics/DaytimeStress.swift (verified on macOS).
 *
 * The daily Stress score (StressScreen / StressView) maps "resting HR up + HRV down vs a
 * personal baseline" onto a 0–3 logistic. This helper applies that SAME math at the
 * per-hour grain so the Stress screen can show *when* in the day stress ran high — not a
 * new score. For each waking hour it computes:
 *
 *   • mean HR over the hour                    (HR up   = stress, like daily RHR)
 *   • RMSSD over the hour's clean R-R          (HRV down = stress, like daily avgHRV)
 *
 * and z-scores each against the day's OWN quiet reference (the calm-hour quartile + the
 * spread across hours), then squashes the z-sum onto 0–3 with the identical logistic
 *   stress = 3 / (1 + e^(−raw)). 0 calm · 1.5 baseline · 3 high — same bands as the daily
 * score. The day is its own baseline: a desk day with one tense afternoon reads that
 * afternoon as elevated *relative to that person's own calm hours*, no cloud, no history
 * needed beyond the day itself.
 *
 * "Sustained high stress" is an honest, conservative flag: the most recent
 * [sustainedHours] covered hours must ALL sit in the HIGH band (≥ [highBandFloor]). It
 * drives a passive in-app suggestion to run a Breathe session — never a notification.
 *
 * APPROXIMATE and non-clinical: an hour with too little data (few HR samples / too few
 * clean beats) is reported with a null level and never invented.
 */
object DaytimeStress {

    // MARK: - Tunables

    /** Minimum HR samples in an hour before its mean HR is trusted (~5 min at 1 Hz). */
    const val minHourHrSamples: Int = 300
    /** Bucket width for the timeline, in seconds (one hour). */
    const val bucketSeconds: Long = 3_600L
    /** Band floor for "high" on the shared 0–3 scale (matches StressBand.High). */
    const val highBandFloor: Double = 2.0
    /** Consecutive most-recent covered hours that must all be HIGH to flag sustained stress. */
    const val sustainedHours: Int = 3
    /** First/last local hour-of-day treated as "waking" for the timeline (06:00–22:00). */
    const val wakingStartHour: Int = 6
    const val wakingEndHour: Int = 22

    /**
     * VALIDATED (26-day Oura-reference correlation, HR-only): a personal daytime-HR elevation
     * of ~15 bpm over a POOLED/ROLLING baseline — the 10th-percentile daytime HR pooled across
     * days, ~65 bpm in the reference set — is where elevated HR starts reading as
     * Oura-comparable "high" stress (r≈0.6 against Oura's own stress signal). A PER-DAY
     * (day-relative) baseline scored WORSE in the same comparison (r 0.43–0.53): an all-day
     * elevated day pulls its own floor up and masks the stress, which is exactly why
     * [ScoringMode.BaselineRelative] leans on [Baselines]' cross-day rolling EWMA instead of a
     * day-local reference. TUNING SEAM: this is HR-only; HR+HRV (WHOOP-era, RMSSD included) is
     * expected to beat this r≈0.6 ceiling — re-validate this margin once that comparison exists.
     * See [marginToSigma] for how it's translated onto the shared 0–3 squash curve.
     */
    const val baselineRelativeHighMarginBPM: Double = 15.0

    /**
     * Gate for whether the personal daytime-RMSSD baseline feeds the live 0–3 score. `false`:
     * [ScoringMode.BaselineRelative] scores HR-only, exactly the channel the r≈0.6 margin above was
     * validated on. The RMSSD half of the pipeline — [DaytimeBaselines.dayDaytimeAggregate],
     * [DaytimeBaselines.foldDaytimeBaselines], the `daytime_rmssd` config, and [rawScore]'s HRV
     * term — is built and unit-tested, but stays OUT of the live score until it has its OWN
     * Oura-reference validation pass.
     *
     * WHY OFF (validated against real WHOOP data, 2026-07): daytime RMSSD off the wrist is
     * artifact-dominated — hourly values swing ~40→430 ms as posture / motion / talking break the
     * R-R stream, an order of magnitude noisier than the overnight recumbent HRV the nightly
     * baselines use. [rawScore] sums the HRV z EQUAL-WEIGHT with the HR z, so an artifact hour can
     * swing the combined score by ±3 (the full band) on noise alone. Enabling it before it is shown
     * to IMPROVE the correlation risks pushing the combined score BELOW the HR-only r≈0.6 ceiling —
     * the exact regression [baselineRelativeHighMarginBPM]'s comment warns against. Flip to `true`
     * only once daytime HR+RMSSD is validated to beat HR-only on an Oura-style stress reference.
     */
    const val daytimeRMSSDScoringEnabled: Boolean = false

    // MARK: - Scoring mode

    /**
     * WHERE each hour's "calm" reference point + spread come from. Every other step —
     * bucketing, the waking-hour filter, the squash curve, sustained-high, high-stress-minutes
     * — is identical between modes; only the reference differs.
     *
     * Relationship to the rest of the Stress screen: the DAILY 0–3 score already compares last
     * night's NIGHTLY resting-HR/HRV to a plain trailing 30-day mean/SD (a once-a-day number
     * from SLEEP vitals). The Advanced HRV card ([StressIndex], [HrvFreqDomain]) is a today-only
     * descriptive lens with no baseline at all. [BaselineRelative] is neither: it's an HOURLY
     * breakdown of TODAY from DAYTIME/waking-hours HR+RMSSD against a PERSONAL cross-day rolling
     * baseline. Daytime HR runs warmer than nocturnal resting HR (posture, thermic effect), so it
     * needs its OWN baseline (`daytime_hr`/`daytime_rmssd`) rather than reusing the nightly
     * `resting_hr`/`hrv` configs — reusing the nightly ones would systematically over-read stress.
     * The three surfaces are complementary lenses on the same underlying autonomic signal, not
     * competing implementations of one baseline.
     */
    sealed interface ScoringMode {
        /**
         * DEFAULT — unchanged from before this mode existed. Each hour is z-scored against
         * THIS DAY's own calm-hour reference: the lower quartile of the day's own waking-hour
         * mean HR, the upper quartile of its own waking-hour RMSSD. No personal history needed —
         * the day is its own baseline. Byte-identical output to the pre-existing single-mode
         * `analyze` for the same hr/rr/tzOffsetSeconds.
         */
        object DayRelative : ScoringMode

        /**
         * Oura-style — each hour is z-scored against the PERSONAL rolling baseline for daytime
         * HR (and, when available, daytime RMSSD): the SAME Winsorized-EWMA machinery
         * ([Baselines.update] / [Baselines.foldHistory]) that backs the nightly HRV / resting-HR
         * baselines elsewhere, using [Baselines.daytimeHRCfg] / [Baselines.daytimeRMSSDCfg].
         *
         * The HR reference point is [hr]'s baseline, but the HIGH-band threshold does NOT scale
         * with this person's own day-to-day spread — see [baselineRelativeHighMarginBPM]: the
         * validated model is a roughly FIXED bpm margin over the personal floor, not a
         * variability-scaled one.
         *
         * [rmssd] is null when no personal RMSSD baseline exists yet — e.g. an imported, Oura-era
         * day with no R-R stream. The stressor then honestly falls back to HR-only scoring for the
         * whole read and flags [Result.hrOnlyFallback]; this mirrors the per-hour graceful-null
         * already in [rawScore], just at the whole-baseline grain. The RMSSD term (when present)
         * DOES still scale by [rmssd]'s spread via [Baselines.sigma] — only the HR term has a
         * validated fixed-margin figure so far.
         */
        data class BaselineRelative(val hr: BaselineState, val rmssd: BaselineState?) : ScoringMode
    }

    // MARK: - Output

    /**
     * One hour of the daytime timeline. [level] is the shared 0–3 stress proxy, or null when
     * the hour had too little signal to score honestly.
     */
    data class HourPoint(
        /** Hour-of-day on the LOCAL clock (0–23), the bucket this point covers. */
        val hour: Int,
        /** Unix seconds at the start of the bucket (wall-clock). */
        val startTs: Long,
        /** Shared 0–3 stress proxy for the hour, or null when no data. */
        val level: Double?,
        /** Mean HR over the hour (bpm), or null. */
        val meanHr: Double?,
        /** RMSSD over the hour's clean R-R (ms), or null (too few clean beats). */
        val rmssd: Double?,
    ) {
        /** True when the hour was scored (had enough HR to place on the curve). */
        val hasData: Boolean get() = level != null
    }

    /** The full daytime read: the hourly timeline plus the sustained-high summary. */
    data class Result(
        /** Waking-hour timeline, earliest → latest. Hours with no signal carry level == null. */
        val hours: List<HourPoint>,
        /** True when the most recent [sustainedHours] SCORED hours all sit in the HIGH band. */
        val sustainedHigh: Boolean,
        /** Count of trailing high hours backing [sustainedHigh] (0 when not sustained). */
        val sustainedRun: Int,
        /** Mean stress across the SCORED hours, or null when none were scorable. */
        val dayMean: Double?,
        /** Peak scored hour (highest level), or null. */
        val peak: HourPoint?,
        /**
         * ADDITIVE — total minutes across SCORED waking hours at/above [highBandFloor], the
         * Oura-comparable "time in high stress" figure. Each scored hour is one [bucketSeconds]
         * bucket, so this is `(# high-band scored hours) * bucketSeconds / 60`. Compare against
         * Oura's `stress_high_s / 60` — NOOP's timeline is hourly-grain vs Oura's ~5-minute grain,
         * so treat this as a coarse approximation, not a precise match. 0 for [EMPTY] and for any
         * day with no scored hours.
         */
        val highStressMinutes: Int = 0,
        /**
         * ADDITIVE — true when [ScoringMode.BaselineRelative] mode was requested but had no
         * personal RMSSD baseline to score against (e.g. an imported Oura-era day with no R-R
         * history), so the whole read honestly fell back to HR-only scoring. Always false in
         * [ScoringMode.DayRelative] mode (there, a missing RMSSD is already handled per-hour by
         * [rawScore], not flagged day-wide) and false for [EMPTY].
         */
        val hrOnlyFallback: Boolean = false,
    ) {
        /** The scored hours only (level non-null), in time order. */
        val scored: List<HourPoint> get() = hours.filter { it.level != null }

        companion object {
            /** Empty read — used when the day had no usable intraday HR at all. */
            val EMPTY = Result(emptyList(), sustainedHigh = false, sustainedRun = 0,
                dayMean = null, peak = null, highStressMinutes = 0, hrOnlyFallback = false)
        }
    }

    // MARK: - Shared stress math (identical formula to the daily StressModel)

    internal fun mean(xs: List<Double>): Double? =
        if (xs.isEmpty()) null else xs.sum() / xs.size

    /** Population standard deviation; 0 when there's no spread. (Matches StressMath.std.) */
    private fun std(xs: List<Double>, m: Double?): Double {
        if (m == null || xs.size <= 1) return 0.0
        val v = xs.sumOf { (it - m) * (it - m) } / xs.size
        return sqrt(v)
    }

    /**
     * Combined autonomic z-score. HR-up and HRV-down both push it positive — the SAME
     * directionality as the daily score (RHR up = stress, HRV down = stress).
     */
    private fun rawScore(
        hr: Double?, meanHr: Double?, sdHr: Double,
        rmssd: Double?, meanRmssd: Double?, sdRmssd: Double,
    ): Double {
        var sum = 0.0
        if (hr != null && meanHr != null && sdHr > 0.0001) {
            sum += (hr - meanHr) / sdHr            // HR up = stress
        }
        if (rmssd != null && meanRmssd != null && sdRmssd > 0.0001) {
            sum += (meanRmssd - rmssd) / sdRmssd   // HRV (RMSSD) down = stress
        }
        return sum
    }

    /**
     * Logistic squash of the raw z-sum onto 0–3 (baseline 0 → 1.5). Identical to
     * StressMath.squash, so an hourly point shares the daily score's scale and bands.
     */
    internal fun squash(raw: Double): Double =
        (3.0 / (1.0 + exp(-raw))).coerceIn(0.0, 3.0)

    /**
     * Solve for the z-score spread `sd` such that a raw elevation of exactly [marginBPM] (or any
     * unit — this is unit-agnostic) squashes to exactly [band] on the shared 0–3 curve:
     * `band = 3 / (1 + e^(−marginBPM/sd))`. Used to translate [baselineRelativeHighMarginBPM]'s
     * validated bpm figure into the `sd` the shared [squash] curve expects, so "baseline + margin"
     * lands exactly on [band] by construction rather than by a second, separate threshold check.
     * Defensive fallback (never divides by zero/negative-log) if [band] is ever configured at or
     * outside the curve's open range (0, 3).
     */
    internal fun marginToSigma(marginBPM: Double, band: Double): Double {
        val ratio = 3.0 / band - 1.0
        if (ratio <= 0.0 || marginBPM <= 0.0) return max(marginBPM, 1e-9)
        return marginBPM / (-ln(ratio))
    }

    // MARK: - Public API

    /**
     * Build the daytime stress timeline from a day's banked HR + R-R.
     *
     * @param hr the day's HR samples (any order; bucketed by ts here).
     * @param rr the day's R-R intervals.
     * @param tzOffsetSeconds seconds east of UTC, for placing each bucket on the LOCAL clock
     *   (so "waking hours" and the hour labels are local). Defaults to UTC.
     * @param mode [ScoringMode.DayRelative] (DEFAULT — unchanged existing behaviour) or
     *   [ScoringMode.BaselineRelative] (Oura-style, vs a personal rolling baseline). ADDITIVE and
     *   opt-in: existing callers that don't pass `mode` keep the exact prior behaviour.
     *
     * Returns [Result.EMPTY] when there isn't a single hour with enough HR to score.
     */
    fun analyze(
        hr: List<HrSample>,
        rr: List<RrInterval>,
        tzOffsetSeconds: Long = 0L,
        mode: ScoringMode = ScoringMode.DayRelative,
    ): Result {
        if (hr.isEmpty()) return Result.EMPTY

        // 1) Bucket HR + R-R into LOCAL hour-of-day buckets, keyed by the bucket start
        //    (floored to the hour on the local clock).
        val hrByBucket = HashMap<Long, MutableList<Double>>()
        for (s in hr) {
            val localTs = s.ts + tzOffsetSeconds
            val bucket = floorDiv(localTs, bucketSeconds) * bucketSeconds
            hrByBucket.getOrPut(bucket) { ArrayList() }.add(s.bpm.toDouble())
        }
        val rrByBucket = HashMap<Long, MutableList<Double>>()
        for (s in rr) {
            val localTs = s.ts + tzOffsetSeconds
            val bucket = floorDiv(localTs, bucketSeconds) * bucketSeconds
            rrByBucket.getOrPut(bucket) { ArrayList() }.add(s.rrMs.toDouble())
        }

        // 2) Per-hour mean HR + RMSSD (RMSSD via the shared HRV cleaner, so ectopic beats
        //    can't fabricate variability). An hour with < minHourHrSamples HR is left
        //    unscored (null level) — never invented.
        data class HourAgg(val bucket: Long, val meanHr: Double?, val rmssd: Double?)
        val orderedBuckets = hrByBucket.keys.sorted()
        val aggs = ArrayList<HourAgg>(orderedBuckets.size)
        for (b in orderedBuckets) {
            val hrs = hrByBucket[b] ?: emptyList<Double>()
            val mHr = if (hrs.size >= minHourHrSamples) mean(hrs) else null
            val rrRes = HrvAnalyzer.analyzeRaw(rrByBucket[b] ?: emptyList())
            aggs.add(HourAgg(b, mHr, rrRes.rmssd))
        }

        // 3) The reference point + spread for each signal — WHERE they come from depends on
        //    `mode`. Every other step (bucketing above, the waking-hour filter, the squash
        //    curve, sustained-high, high-stress-minutes below) is identical between modes.
        val refHr: Double?
        val sdHr: Double
        val refRmssd: Double?
        val sdRmssd: Double
        val hrOnlyFallback: Boolean
        when (mode) {
            is ScoringMode.DayRelative -> {
                // The day's OWN quiet reference: centre on the CALM end (the lower quartile of
                // hourly mean HR, the upper quartile of hourly RMSSD), and spread from the
                // across-hour SD. This makes a flat day read ~baseline and a spiky day surface its
                // tense hours — without any cross-day history. Falls back to the plain mean when
                // there are too few scored hours for a quartile.
                //
                // Built from the WAKING hours only — the same hours scored in step 4. Sleep is the
                // calmest, lowest-HR / highest-HRV stretch of the day, and the analysis window
                // always begins at local midnight, so the current day routinely carries several
                // hours of it. Letting those night hours into the reference drags the "calm" anchor
                // far beneath every waking hour, inflating an ordinary calm day toward HIGH and
                // falsely tripping the sustained-high Breathe nudge.
                val referenceAggs = aggs.filter { isWakingHour(it.bucket) }
                val hrMeans = referenceAggs.mapNotNull { it.meanHr }
                val rmssdVals = referenceAggs.mapNotNull { it.rmssd }
                refHr = calmReference(hrMeans, calmIsLow = true)         // calm HR is LOW
                refRmssd = calmReference(rmssdVals, calmIsLow = false)   // calm HRV is HIGH
                sdHr = std(hrMeans, mean(hrMeans))
                sdRmssd = std(rmssdVals, mean(rmssdVals))
                hrOnlyFallback = false
            }
            is ScoringMode.BaselineRelative -> {
                // The PERSONAL cross-day baseline, folded by the caller from past daytime
                // aggregates via Baselines.update/foldHistory (see the ScoringMode doc).
                refHr = mode.hr.baseline
                // VALIDATED tuning, not Baselines.sigma(mode.hr): the correlation study behind
                // baselineRelativeHighMarginBPM found a roughly FIXED bpm margin over the personal
                // floor — not one scaled by this person's own day-to-day spread — best matched
                // Oura's stress signal. marginToSigma solves for the sd that makes exactly
                // refHr + baselineRelativeHighMarginBPM land on highBandFloor on the shared squash
                // curve, so the validated margin IS the "high" cutoff by construction.
                sdHr = marginToSigma(baselineRelativeHighMarginBPM, highBandFloor)
                val rmssdBaseline = mode.rmssd
                if (rmssdBaseline != null) {
                    refRmssd = rmssdBaseline.baseline
                    // No independently validated RMSSD margin yet (see the constant's doc) — this
                    // term still scales by the person's own spread via the shared σ conversion.
                    sdRmssd = Baselines.sigma(rmssdBaseline)
                    hrOnlyFallback = false
                } else {
                    // No personal RMSSD baseline exists (e.g. an Oura-era day with no R-R history to
                    // fold one from). rawScore already treats a null meanRmssd as "skip this term",
                    // so passing null here gracefully degrades to HR-only scoring — flagged honestly
                    // in the output rather than silently.
                    refRmssd = null
                    sdRmssd = 0.0
                    hrOnlyFallback = true
                }
            }
        }

        // 4) Score each waking-hour bucket on the shared 0–3 curve.
        val points = ArrayList<HourPoint>(aggs.size)
        for (a in aggs) {
            if (!isWakingHour(a.bucket)) continue
            val hourOfDay = (floorDiv(a.bucket, bucketSeconds) % 24).toInt()
            // The wall-clock bucket start (undo the local shift applied above).
            val wallStart = a.bucket - tzOffsetSeconds
            // Score only when HR cleared the count gate (HR is the always-available anchor;
            // RMSSD enriches it when beats allow).
            val level: Double? = if (a.meanHr != null) {
                squash(rawScore(a.meanHr, refHr, sdHr, a.rmssd, refRmssd, sdRmssd))
            } else {
                null
            }
            points.add(HourPoint(hourOfDay, wallStart, level, a.meanHr, a.rmssd))
        }

        val scored = points.mapNotNull { p -> p.level?.let { p to it } }
        if (scored.isEmpty()) {
            // No scorable waking hour — still return the (unscored) timeline so the UI can
            // show "not enough data" rather than nothing. hrOnlyFallback is a MODE property
            // (whether a personal RMSSD baseline existed to score against), so it's still worth
            // reporting even though nothing ended up scored.
            return if (points.isEmpty()) Result.EMPTY
            else Result(points, sustainedHigh = false, sustainedRun = 0, dayMean = null, peak = null,
                highStressMinutes = 0, hrOnlyFallback = hrOnlyFallback)
        }

        // 5) Sustained-high flag: walk back from the latest SCORED hour while each is HIGH.
        var run = 0
        for ((_, lvl) in scored.asReversed()) {
            if (lvl >= highBandFloor) run += 1 else break
        }
        val sustained = run >= sustainedHours

        val dayMean = mean(scored.map { it.second })
        val peak = scored.maxByOrNull { it.second }?.first

        // 6) Oura-comparable "time in high stress": each scored hour at/above highBandFloor is one
        //    full bucketSeconds bucket, converted to minutes. Uses the SAME threshold the
        //    sustained-high check above already uses, so all stay in lockstep by construction.
        val highStressMinutes = scored.count { it.second >= highBandFloor } * (bucketSeconds / 60L).toInt()

        return Result(points, sustained, run, dayMean, peak,
            highStressMinutes = highStressMinutes, hrOnlyFallback = hrOnlyFallback)
    }

    // MARK: - Helpers

    /**
     * Floor-division that is correct for negative numerators (so a local time just before
     * the UTC epoch still buckets to the hour below, not toward zero).
     */
    internal fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        val r = a % b
        return if (r != 0L && (r < 0L) != (b < 0L)) q - 1 else q
    }

    /**
     * Whether a local hour-bucket start falls inside the waking window the timeline scores
     * (06:00–22:00). The single source of truth for "waking" — used both to build the calm
     * reference and to pick the hours to score, so the two can never drift apart.
     */
    internal fun isWakingHour(bucket: Long): Boolean {
        val hourOfDay = (floorDiv(bucket, bucketSeconds) % 24).toInt()
        return hourOfDay >= wakingStartHour && hourOfDay < wakingEndHour
    }

    /**
     * The day's "calm" reference for a signal: the quartile toward the calm end (lower
     * quartile when calm is LOW, e.g. HR; upper quartile when calm is HIGH, e.g. RMSSD).
     * Falls back to the plain mean below 4 values, and to null when empty.
     */
    private fun calmReference(xs: List<Double>, calmIsLow: Boolean): Double? {
        if (xs.isEmpty()) return null
        if (xs.size < 4) return mean(xs)
        val s = xs.sorted()
        return if (calmIsLow) quantile(s, 0.25) else quantile(s, 0.75)
    }

    /** Linear-interpolated quantile of an already-sorted, non-empty list. */
    internal fun quantile(sorted: List<Double>, q: Double): Double {
        val n = sorted.size
        if (n == 0) return 0.0   // defensive: callers guard emptiness; never index []
        if (n == 1) return sorted[0]
        val pos = q * (n - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, n - 1)
        val frac = pos - lo
        return sorted[lo] + frac * (sorted[hi] - sorted[lo])
    }
}
