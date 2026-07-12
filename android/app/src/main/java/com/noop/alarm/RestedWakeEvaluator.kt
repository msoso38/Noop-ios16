package com.noop.alarm

/**
 * Decide whether the phone smart alarm may advance early because the athlete looks rested.
 *
 * Pure / testable. Honest scope: uses overnight sleep-minutes estimate + optional Charge score.
 * Not sleep-stage detection. The hard deadline still stands if this never fires.
 */
object RestedWakeEvaluator {

    /** Green Charge band floor used elsewhere in NOOP (~67). */
    const val DEFAULT_CHARGE_THRESHOLD = 67.0
    /** Fraction of learned sleep need that counts as "enough". */
    const val DEFAULT_SLEEP_FRACTION = 0.90

    /**
     * @param sleepMinutesSoFar estimated minutes asleep tonight (trough-era span or staged sleep)
     * @param sleepNeedMinutes learned / default need (e.g. 450)
     * @param chargeScore overnight Charge 0–100 when already scored, else null
     * @param chargeThreshold wake when Charge ≥ this (default green band)
     * @param sleepFraction wake when sleepMinutes ≥ need × fraction
     */
    fun shouldWake(
        sleepMinutesSoFar: Double,
        sleepNeedMinutes: Double,
        chargeScore: Double?,
        chargeThreshold: Double = DEFAULT_CHARGE_THRESHOLD,
        sleepFraction: Double = DEFAULT_SLEEP_FRACTION,
    ): Boolean {
        if (sleepNeedMinutes <= 0.0) return false
        val sleepOk = sleepMinutesSoFar >= sleepNeedMinutes * sleepFraction.coerceIn(0.5, 1.0)
        val chargeOk = chargeScore != null && chargeScore >= chargeThreshold
        return sleepOk || chargeOk
    }
}
