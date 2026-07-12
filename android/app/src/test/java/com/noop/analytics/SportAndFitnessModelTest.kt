package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportClassifierTest {
    @Test
    fun highMotionHighHrReserveIsRunning() {
        val g = SportClassifier.classify(
            meanAct = 0.8, dailyMedianAct = 0.2, avgHr = 160.0, restingHr = 55.0, maxHr = 190.0,
        )
        assertEquals("Running", g.sport)
        assertEquals(0.4, g.confidence, 0.0)
    }

    @Test
    fun lowMotionHighHrReserveIsStrength() {
        val g = SportClassifier.classify(
            meanAct = 0.15, dailyMedianAct = 0.2, avgHr = 150.0, restingHr = 55.0, maxHr = 190.0,
        )
        assertEquals("Strength Training", g.sport)
    }

    @Test
    fun lowReserveIsWalking() {
        val g = SportClassifier.classify(
            meanAct = 0.3, dailyMedianAct = 0.2, avgHr = 95.0, restingHr = 55.0, maxHr = 190.0,
        )
        assertEquals("Walking", g.sport)
    }

    @Test
    fun preferLabeledKeepsRealSport() {
        assertEquals(
            "Cycling",
            SportClassifier.preferLabeled("Cycling", SportClassifier.Guess("Running")),
        )
        assertEquals(
            "Running",
            SportClassifier.preferLabeled("detected", SportClassifier.Guess("Running")),
        )
    }
}

class FitnessModelTest {
    @Test
    fun needsSevenDays() {
        val days = (1..6).map { FitnessModel.DayStrain("2026-01-0$it", 40.0) }
        assertNull(FitnessModel.evaluate(days).form)
    }

    @Test
    fun risingLoadRaisesFatigue() {
        val days = (1..21).map { i ->
            val strain = if (i > 14) 80.0 else 30.0
            FitnessModel.DayStrain("2026-01-%02d".format(i), strain)
        }
        val r = FitnessModel.evaluate(days)
        assertTrue(r.form != null)
        assertTrue(r.fatigue!! > r.fitness!! * 0.5)
        assertTrue(r.confidence > 0.4)
    }
}
