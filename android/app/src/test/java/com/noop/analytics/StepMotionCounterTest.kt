package com.noop.analytics

import com.noop.data.StepRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepMotionCounterTest {
    @Test
    fun acceptsWalkingAndRunTicksSoftWeightsStillWhenNoNoiseFloor() {
        val result = StepMotionCounter.accumulate(
            listOf(
                StepRow(1, 100, 1),
                StepRow(2, 102, 1), // +2 walk → 2.0
                StepRow(3, 104, 0), // +2 still, rate=2 → soft 0.50 → 1.0
                StepRow(4, 106, 2), // +2 run, weighted 1.05 → 2.1
                StepRow(5, 500, 1), // discontinuity, excluded
            ),
        )

        assertEquals(5.1, result.acceptedTicks, 0.001)
        // discontinuity 396-ish raw; still (+2) is soft-accepted so not in rejected
        assertEquals(394, result.rejectedTicks)
        assertEquals(3, result.acceptedPairs)
    }

    @Test
    fun productionStillSoftWhenActivityClassStuckAtZero_mgCaptureShape() {
        // Mirrors live MG: act@63 always 0 while counter climbs (walk).
        val samples = (0 until 20).map { i ->
            StepRow(ts = i * 2L, counter = 1000 + i * 3, activityClass = 0)
        }
        val result = StepMotionCounter.accumulate(samples, mode = StepMotionCounter.Mode.Production)
        assertTrue("stuck act@0 must not zero all steps", result.acceptedTicks > 0.0)
        val steps = StepMotionCounter.ticksToSteps(result.acceptedTicks, ticksPerStep = 1.5)
        assertTrue(steps > 0)
    }

    @Test
    fun noiseFloorRejectsSlowStillCreep() {
        val result = StepMotionCounter.accumulate(
            listOf(
                StepRow(0, 100, 0),
                StepRow(10, 101, 0), // rate 0.1 ticks/s
            ),
            mode = StepMotionCounter.Mode.Production,
            noiseFloorTicksPerSec = 0.2,
        )
        assertEquals(0.0, result.acceptedTicks, 0.001)
    }

    @Test
    fun honorsCounterWrap() {
        val result = StepMotionCounter.accumulate(
            listOf(StepRow(1, 65534, 1), StepRow(2, 1, 1)),
        )
        assertEquals(3.0, result.acceptedTicks, 0.001)
        assertEquals(0, result.rejectedTicks)
    }

    @Test
    fun ticksToSteps_zeroWhenNoMotion() {
        assertEquals(0, StepMotionCounter.ticksToSteps(0.0, 1.5))
    }

    @Test
    fun ticksToSteps_dividesByPersonalK() {
        assertEquals(100, StepMotionCounter.ticksToSteps(150.0, 1.5))
    }
}
