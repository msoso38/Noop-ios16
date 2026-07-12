package com.noop.analytics

import com.noop.data.DailyMetric
import kotlin.math.abs
import org.json.JSONArray

/**
 * Health Connect ↔ NOOP metric alignment helpers.
 *
 * Ground truth for this track is **WHOOP-app / phone data via Health Connect**, not open BLE.
 * Pure functions only — no invented vitals. When HC has staged sleep and NOOP's night is far off,
 * we gently blend toward HC (still keep some NOOP signal so staging isn't discarded wholesale).
 */
object HcNoopAlign {

    /** Absolute asleep-minute gap that triggers fusion toward HC. */
    const val SLEEP_GAP_BLEND_MIN = 25.0

    /** Weight on HC asleep minutes when blending (rest stays on NOOP). */
    const val HC_SLEEP_BLEND = 0.65

    data class HcNight(
        val asleepMin: Double,
        val deepMin: Double? = null,
        val remMin: Double? = null,
        val lightMin: Double? = null,
        val hasStages: Boolean = false,
    )

    /**
     * Parse importer stagesJSON `[{stage,min},…]` into deep/rem/light minutes.
     * Returns nulls when JSON is missing or empty — never fabricates stages.
     */
    fun stagesFromJson(stagesJSON: String?): Triple<Double?, Double?, Double?> {
        if (stagesJSON.isNullOrBlank()) return Triple(null, null, null)
        return runCatching {
            val arr = JSONArray(stagesJSON)
            var deep = 0.0
            var rem = 0.0
            var light = 0.0
            var any = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val stage = o.optString("stage").lowercase()
                val min = o.optDouble("min", Double.NaN)
                if (min.isNaN() || min <= 0.0) continue
                any = true
                when {
                    stage.contains("deep") -> deep += min
                    stage.contains("rem") -> rem += min
                    stage.contains("light") || stage.contains("core") -> light += min
                }
            }
            if (!any) Triple(null, null, null)
            else Triple(
                deep.takeIf { it > 0.0 },
                rem.takeIf { it > 0.0 },
                light.takeIf { it > 0.0 },
            )
        }.getOrDefault(Triple(null, null, null))
    }

    fun nightFromSession(
        asleepMin: Double,
        stagesJSON: String?,
    ): HcNight {
        val (deep, rem, light) = stagesFromJson(stagesJSON)
        val hasStages = deep != null || rem != null || light != null
        return HcNight(
            asleepMin = asleepMin,
            deepMin = deep,
            remMin = rem,
            lightMin = light,
            hasStages = hasStages,
        )
    }

    /**
     * When HC has staged sleep and NOOP's TST is off by ≥ [SLEEP_GAP_BLEND_MIN], blend TST toward HC
     * and prefer HC stage minutes. Small gaps leave NOOP alone (strap staging often matches).
     */
    fun fuseDaily(daily: DailyMetric, hc: HcNight?): DailyMetric {
        val noopTst = daily.totalSleepMin ?: return daily
        if (hc == null || hc.asleepMin <= 0.0) return daily
        if (!hc.hasStages) return daily
        if (abs(noopTst - hc.asleepMin) < SLEEP_GAP_BLEND_MIN) return daily
        val fused = HC_SLEEP_BLEND * hc.asleepMin + (1.0 - HC_SLEEP_BLEND) * noopTst
        return daily.copy(
            totalSleepMin = (fused * 10.0).toInt() / 10.0,
            deepMin = hc.deepMin ?: daily.deepMin,
            remMin = hc.remMin ?: daily.remMin,
            lightMin = hc.lightMin ?: daily.lightMin,
        )
    }

    /** Absolute step gap for diagnostics (null if either side missing). */
    fun stepsGap(noopOrEst: Int?, hc: Int?): Int? {
        if (noopOrEst == null || hc == null) return null
        return abs(noopOrEst - hc)
    }

    /**
     * Display preference: strap counter > HC phone/watch > motion estimate.
     * Never sums sources (double-count).
     */
    fun preferSteps(strap: Int?, hc: Int?, estimate: Int?): Int? =
        strap?.takeIf { it > 0 } ?: hc?.takeIf { it > 0 } ?: estimate?.takeIf { it > 0 }

    /** Duration-as-% of 8h need — honest proxy when WHOOP Sleep Performance isn't in the export. */
    fun durationAsSleepPerf(totalSleepMin: Double?): Double? =
        totalSleepMin?.let { (it / 480.0 * 100.0).coerceIn(0.0, 120.0) }
}
