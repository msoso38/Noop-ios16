package com.noop.analytics

/**
 * Crude ESTIMATE sport buckets from motion + HR reserve.
 *
 * Port of localhoop/analytics `sessions.ts` `classifyType` (walk / run/cardio / strength/other).
 * Transparent thresholds only — not a trained HAR model. Flash-drained 1 Hz gravity has enough
 * texture for this heuristic; Mannini-style high-rate HAR stays deferred (needs ~100 Hz live IMU).
 *
 * Phone IMU features ([PhoneMotionHints]) and prior user labels ([priorLabelBoost]) refine the
 * guess when available — still capped as an ESTIMATE.
 */
object SportClassifier {

    data class Guess(
        val sport: String,
        /** Honest ESTIMATE confidence; matches localhoop's 0.4 heuristic cap unless labels boost. */
        val confidence: Double = 0.4,
    )

    /** Optional phone-IMU window stats collected during a live workout. */
    data class PhoneMotionHints(
        val meanAccelMag: Double = 0.0,
        val stdAccelMag: Double = 0.0,
        val meanGyroMag: Double = 0.0,
        val stdGyroMag: Double = 0.0,
        val sampleCount: Int = 0,
    )

    /**
     * @param meanAct mean gravity-change intensity over the bout
     * @param dailyMedianAct median intensity across the day (or bout fallback)
     * @param avgHr bout average HR
     * @param restingHr day resting HR
     * @param maxHr HRmax used for reserve math
     * @param phone optional phone accel/gyro features for the same window
     * @param priorLabelBoost 0..1 from how often the user confirmed this sport before
     */
    fun classify(
        meanAct: Double,
        dailyMedianAct: Double,
        avgHr: Double,
        restingHr: Double,
        maxHr: Double,
        phone: PhoneMotionHints? = null,
        priorLabelBoost: Double = 0.0,
    ): Guess {
        val reserve = maxHr - restingHr
        val hrReservePct = if (reserve > 0.0) (avgHr - restingHr) / reserve else 0.0
        val highActivity = meanAct > dailyMedianAct * 2.0
        // Phone gyro energy separates rhythmic cardio from more static strength when strap gravity is flat.
        val gyroBusy = (phone?.meanGyroMag ?: 0.0) > 0.55 && (phone?.stdGyroMag ?: 0.0) > 0.35
        val accelBusy = (phone?.stdAccelMag ?: 0.0) > 1.8
        val sport = when {
            highActivity && hrReservePct >= 0.6 -> "Running"
            phone != null && phone.sampleCount >= 40 && gyroBusy && hrReservePct >= 0.55 && accelBusy -> "Running"
            !highActivity && hrReservePct >= 0.6 -> "Strength Training"
            phone != null && phone.sampleCount >= 40 && !gyroBusy && hrReservePct >= 0.5 -> "Strength Training"
            else -> "Walking"
        }
        val conf = (0.4 + priorLabelBoost.coerceIn(0.0, 0.25)).coerceAtMost(0.65)
        return Guess(sport = sport, confidence = conf)
    }

    /** Prefer a labelled HC / WHOOP sport when present; otherwise keep the heuristic. */
    fun preferLabeled(existing: String?, guess: Guess): String {
        val label = existing?.trim().orEmpty()
        if (label.isEmpty() || label.equals("detected", ignoreCase = true) ||
            label.equals("workout", ignoreCase = true) || label.equals("other", ignoreCase = true)
        ) {
            return guess.sport
        }
        return label
    }
}
