package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalBloodPressureTest {

    @Test
    fun refusesToEstimateWithoutCalibration() {
        val result = ExperimentalBloodPressure.evaluate(
            ExperimentalBloodPressure.InputWindow(
                ppgHrBpm = List(120) { 72 },
                ppgConfidence = List(120) { 0.8 },
                rrMs = List(120) { 830 },
                spo2Red = List(120) { 18_000 },
                spo2Ir = List(120) { 17_000 },
            )
        )

        assertTrue(result is ExperimentalBloodPressure.Result.NotReady)
        assertEquals(
            "Need a recent cuff/manual blood-pressure calibration.",
            (result as ExperimentalBloodPressure.Result.NotReady).reason,
        )
    }

    @Test
    fun readyWhenLocalInputsAndCalibrationExist() {
        val result = ExperimentalBloodPressure.evaluate(
            ExperimentalBloodPressure.InputWindow(
                ppgHrBpm = List(120) { 72 },
                ppgConfidence = List(120) { 0.8 },
                rrMs = List(120) { 830 },
                spo2Red = List(120) { 18_000 },
                spo2Ir = List(120) { 17_000 },
                lastManualSystolic = 120,
                lastManualDiastolic = 80,
            )
        )

        assertTrue(result is ExperimentalBloodPressure.Result.Ready)
    }

    @Test
    fun acceptsOnlyValidModelOutput() {
        val input = ExperimentalBloodPressure.InputWindow(
            ppgHrBpm = List(120) { 72 },
            ppgConfidence = List(120) { 0.8 },
            rrMs = List(120) { 830 },
            spo2Red = List(120) { 18_000 },
            spo2Ir = List(120) { 17_000 },
            lastManualSystolic = 120,
            lastManualDiastolic = 80,
        )

        val result = ExperimentalBloodPressure.evaluate(input) {
            ExperimentalBloodPressure.Estimate(systolic = 121, diastolic = 79, confidence = 1.2)
        }

        assertTrue(result is ExperimentalBloodPressure.Result.Estimated)
        assertEquals(1.0, (result as ExperimentalBloodPressure.Result.Estimated).value.confidence, 0.0)
    }
}
