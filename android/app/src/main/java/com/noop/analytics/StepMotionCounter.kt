package com.noop.analytics

import com.noop.data.StepRow
import com.noop.data.StepSample

/**
 * The one accepted-tick calculation used by both daily estimates and calibration sessions. Keeping the
 * gates together prevents a trainer from fitting raw counter endpoints that production later rejects.
 *
 * Modes:
 *  - [Mode.Production]: still (activity class 0) contributes 0 — fidget must not inflate daily steps.
 *  - [Mode.Training]: still is soft-weighted so a mis-labelled class@63 (often 0 while the user walks)
 *    can still teach ticks-per-step from labelled taps. Endpoint deltas remain gated for wrap/rate.
 */
object StepMotionCounter {
    enum class Mode { Production, Training }

    data class Result(
        val acceptedTicks: Double,
        val rejectedTicks: Int,
        val acceptedPairs: Int,
        val rawDelta: Int = 0,
    )

    fun accumulate(
        samples: List<StepRow>,
        mode: Mode = Mode.Production,
        noiseFloorTicksPerSec: Double = 0.0,
    ): Result {
        val sorted = samples.sortedBy { it.ts }
        if (sorted.size < 2) {
            return Result(0.0, 0, 0, rawDelta = 0)
        }
        var accepted = 0.0
        var rejected = 0
        var acceptedPairs = 0
        val first = sorted.first().counter
        val last = sorted.last().counter
        val raw = (last - first).let { d -> if (d < 0) d + 65536 else d }
        for (i in 1 until sorted.size) {
            val previous = sorted[i - 1]
            val current = sorted[i]
            val delta = (current.counter - previous.counter) and 0xFFFF
            val elapsed = (current.ts - previous.ts).coerceAtLeast(1)
            val rate = delta.toDouble() / elapsed
            if (delta <= 0 || delta >= MAX_DELTA || rate > MAX_TICKS_PER_SECOND) {
                rejected += delta
                continue
            }
            // Shake-learned noise floor: slow counter creep while "still" is discarded in production.
            if (mode == Mode.Production &&
                noiseFloorTicksPerSec > 0.0 &&
                (current.activityClass == null || current.activityClass == 0) &&
                rate <= noiseFloorTicksPerSec * 1.15
            ) {
                rejected += delta
                continue
            }
            // Production class-0 used to hard-zero every tick. On a sample MG capture, act@63 stayed
            // 0 for 1231/1231 complete v18 frames while step@57 still moved — so hard-zero hid real walks.
            // Soft-weight still when the rate is above the shake-learned noise floor (or when no floor yet).
            val weight = when (mode) {
                Mode.Production -> when (current.activityClass) {
                    null -> 1.0
                    0 -> when {
                        noiseFloorTicksPerSec > 0.0 && rate > noiseFloorTicksPerSec * 1.15 -> 0.75
                        noiseFloorTicksPerSec > 0.0 -> 0.0 // already rejected above; keep 0
                        // No noise model yet: damp fidget but do not erase all @57 motion.
                        rate >= 0.35 -> 0.50
                        else -> 0.15
                    }
                    1 -> 1.0
                    2 -> 1.05
                    else -> 0.5
                }
                // Training: do not zero still — @63 often stays 0 during short walks; user taps are ground truth.
                Mode.Training -> when (current.activityClass) {
                    null -> 1.0
                    0 -> 0.55
                    1 -> 1.0
                    2 -> 1.05
                    else -> 0.7
                }
            }
            if (weight == 0.0) {
                rejected += delta
                continue
            }
            accepted += delta * weight
            acceptedPairs++
        }
        return Result(accepted, rejected, acceptedPairs, rawDelta = raw)
    }

    fun accumulatePersisted(
        samples: List<StepSample>,
        mode: Mode = Mode.Production,
        noiseFloorTicksPerSec: Double = 0.0,
    ): Result =
        accumulate(
            samples.map { StepRow(ts = it.ts, counter = it.counter, activityClass = it.activityClass) },
            mode = mode,
            noiseFloorTicksPerSec = noiseFloorTicksPerSec,
        )

    /**
     * Map accepted motion ticks → whole steps using personal ticks-per-step `k`.
     * Pure; never invents when ticks are zero.
     */
    fun ticksToSteps(acceptedTicks: Double, ticksPerStep: Double): Int {
        if (acceptedTicks <= 0.0) return 0
        val k = ticksPerStep.coerceIn(0.5, 30.0)
        return (acceptedTicks / k).toInt().coerceIn(0, 60_000)
    }

    private const val MAX_DELTA = 256
    private const val MAX_TICKS_PER_SECOND = 4.0
}
