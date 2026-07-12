package com.noop.analytics

import kotlin.math.roundToInt

/**
 * Experimental WHOOP 5/MG blood-pressure plumbing.
 *
 * Discord/dev notes point to BP as a derived signal from local optical streams: raw PPG cadence
 * around 24/28 Hz, pulse/HRV timing, and user calibration. It is not a plain decoded "blood pressure"
 * field in the current NOOP store. This object keeps that boundary honest: it can summarize whether a
 * window has enough local evidence for BP work, and it can run an injected calibrated model, but it will
 * not fabricate systolic/diastolic values from generic heuristics.
 */
object ExperimentalBloodPressure {

    data class InputWindow(
        val ppgHrBpm: List<Int> = emptyList(),
        val ppgConfidence: List<Double> = emptyList(),
        val rrMs: List<Int> = emptyList(),
        val spo2Red: List<Int> = emptyList(),
        val spo2Ir: List<Int> = emptyList(),
        val ageYears: Int? = null,
        val heightCm: Double? = null,
        val weightKg: Double? = null,
        val lastManualSystolic: Int? = null,
        val lastManualDiastolic: Int? = null,
    )

    data class Features(
        val samples: Int,
        val meanPpgHr: Double?,
        val meanRrMs: Double?,
        val meanSpo2Ratio: Double?,
        val meanPpgConfidence: Double?,
        val hasUserCalibration: Boolean,
    )

    data class Estimate(
        val systolic: Int,
        val diastolic: Int,
        val confidence: Double,
        val source: String = "experimental_local_model",
    )

    sealed class Result {
        data class NotReady(val reason: String, val features: Features) : Result()
        data class Ready(val features: Features) : Result()
        data class Estimated(val value: Estimate, val features: Features) : Result()
    }

    /**
     * Build a compact, model-friendly feature snapshot from streams NOOP already persists today.
     * The full 24/28 Hz waveform should replace [ppgHrBpm] once stored; until then this is a readiness
     * gate and calibration point, not a BP decoder.
     */
    fun features(input: InputWindow): Features {
        val redIr = input.spo2Red.zip(input.spo2Ir)
            .filter { (red, ir) -> red > 0 && ir > 0 }
            .map { (red, ir) -> red.toDouble() / ir.toDouble() }
        return Features(
            samples = maxOf(input.ppgHrBpm.size, input.rrMs.size, redIr.size),
            meanPpgHr = input.ppgHrBpm.averageIntOrNull(),
            meanRrMs = input.rrMs.averageIntOrNull(),
            meanSpo2Ratio = redIr.averageDoubleOrNull(),
            meanPpgConfidence = input.ppgConfidence.averageDoubleOrNull(),
            hasUserCalibration = input.lastManualSystolic in 70..250 &&
                input.lastManualDiastolic in 40..150,
        )
    }

    /**
     * Evaluate readiness, optionally running a caller-supplied calibrated model.
     *
     * The model callback is intentionally explicit so we can later plug in a small on-device model
     * trained on local/manual cuffs or verified reverse-engineered features. Without that model, Ready
     * means "we have enough inputs to try", not "we know your BP".
     */
    fun evaluate(
        input: InputWindow,
        model: ((Features) -> Estimate?)? = null,
    ): Result {
        val f = features(input)
        val reason = notReadyReason(f)
        if (reason != null) return Result.NotReady(reason, f)

        val estimate = model?.invoke(f)?.takeIf { validEstimate(it) }
        return if (estimate != null) {
            Result.Estimated(
                estimate.copy(confidence = estimate.confidence.coerceIn(0.0, 1.0).round3()),
                f,
            )
        } else {
            Result.Ready(f)
        }
    }

    private fun notReadyReason(f: Features): String? = when {
        f.samples < 60 -> "Need at least 60 seconds of local optical/timing samples."
        f.meanPpgHr == null -> "Need PPG-derived heart-rate features."
        f.meanRrMs == null -> "Need beat-to-beat timing features."
        f.meanPpgConfidence != null && f.meanPpgConfidence < 0.30 -> "PPG signal confidence is too low."
        !f.hasUserCalibration -> "Need a recent cuff/manual blood-pressure calibration."
        else -> null
    }

    private fun validEstimate(e: Estimate): Boolean =
        e.systolic in 70..250 &&
            e.diastolic in 40..150 &&
            e.diastolic < e.systolic &&
            e.confidence > 0.0

    private fun List<Int>.averageIntOrNull(): Double? =
        if (isEmpty()) null else average()

    private fun List<Double>.averageDoubleOrNull(): Double? =
        if (isEmpty()) null else average()

    private fun Double.round3(): Double = (this * 1000.0).roundToInt() / 1000.0
}
