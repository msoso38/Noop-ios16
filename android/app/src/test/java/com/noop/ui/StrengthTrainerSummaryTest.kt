package com.noop.ui

import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Test

class StrengthTrainerSummaryTest {

    @Test
    fun parsesLiftingVolumeSetsAndExercises() {
        val rows = listOf(
            WorkoutRow(
                deviceId = "lifting",
                startTs = 100,
                endTs = 3_700,
                sport = "Strength",
                source = "lifting",
                durationS = 3_600.0,
                notes = "Strength - volume load 12,400 kg - 18 sets - 5 exercises",
            ),
            WorkoutRow(
                deviceId = "lifting",
                startTs = 4_000,
                endTs = 5_800,
                sport = "Strength Training",
                source = "lifting",
                durationS = 1_800.0,
                notes = "Strength - volume load 600 kg - 4 sets - 2 exercises",
            ),
            WorkoutRow(
                deviceId = "my-whoop",
                startTs = 6_000,
                endTs = 6_600,
                sport = "Running",
                source = "whoop",
                durationS = 600.0,
            ),
        )

        val summary = strengthSummary(rows)

        assertEquals(2, summary.sessions)
        assertEquals(13_000.0, summary.volumeLoadKg, 0.001)
        assertEquals(22, summary.sets)
        assertEquals(7, summary.exercises)
        assertEquals(90, summary.minutes)
    }
}
