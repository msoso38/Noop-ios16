package com.noop.analytics

import kotlin.math.round

/**
 * Banister impulse-response fitness / fatigue / form (CTL / ATL / TSB).
 *
 * Port of localhoop/analytics `fitness.ts` `calcFitnessModel`:
 *   Fitness (CTL) = EWMA(τ≈42 d), Fatigue (ATL) = EWMA(τ≈7 d), Form = Fitness−Fatigue
 *   measured BEFORE today's strain (freshness coming into today).
 *
 * ESTIMATE tier. Needs ≥7 strain days; confidence ramps to 1.0 at ~42 days.
 * Uses NOOP Effort (0–100) or WHOOP-like strain interchangeably — relative shape only.
 */
object FitnessModel {

    data class Result(
        val fitness: Double?,
        val fatigue: Double?,
        val form: Double?,
        val confidence: Double,
        val daysUsed: Int,
    )

    data class DayStrain(val day: String, val strain: Double)

    fun evaluate(dailyStrain: List<DayStrain>): Result {
        val sorted = dailyStrain
            .filter { it.strain.isFinite() && it.strain >= 0.0 }
            .sortedBy { it.day }
        val days = sorted.size
        val conf = (days / 42.0).coerceIn(0.0, 1.0)
        if (days < 7) {
            return Result(fitness = null, fatigue = null, form = null, confidence = conf, daysUsed = days)
        }
        val aCtl = 2.0 / (42 + 1)
        val aAtl = 2.0 / (7 + 1)
        var ctl = sorted[0].strain
        var atl = sorted[0].strain
        var prevCtl = ctl
        var prevAtl = atl
        for (d in sorted) {
            prevCtl = ctl
            prevAtl = atl
            ctl += aCtl * (d.strain - ctl)
            atl += aAtl * (d.strain - atl)
        }
        return Result(
            fitness = round2(ctl),
            fatigue = round2(atl),
            form = round2(prevCtl - prevAtl),
            confidence = conf,
            daysUsed = days,
        )
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
