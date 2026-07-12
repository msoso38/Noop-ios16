package com.noop.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RestedWakeEvaluatorTest {
    @Test
    fun wakesWhenSleepNeedMet() {
        assertTrue(
            RestedWakeEvaluator.shouldWake(
                sleepMinutesSoFar = 420.0,
                sleepNeedMinutes = 450.0,
                chargeScore = null,
                sleepFraction = 0.90,
            ),
        )
    }

    @Test
    fun wakesWhenChargeGreen() {
        assertTrue(
            RestedWakeEvaluator.shouldWake(
                sleepMinutesSoFar = 100.0,
                sleepNeedMinutes = 450.0,
                chargeScore = 72.0,
                chargeThreshold = 67.0,
            ),
        )
    }

    @Test
    fun abstainsWhenNeitherMet() {
        assertFalse(
            RestedWakeEvaluator.shouldWake(
                sleepMinutesSoFar = 200.0,
                sleepNeedMinutes = 450.0,
                chargeScore = 40.0,
            ),
        )
    }
}

class CustomAlarmSchedulerTest {
    @Test
    fun nextOccurrenceSkipsDisabledWeekdays() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 13, 8, 0, 0) // Monday
            set(Calendar.MILLISECOND, 0)
        }
        // Only Wednesday (4)
        val next = CustomAlarmScheduler.nextOccurrenceMs(
            minutes = 7 * 60,
            weekdays = setOf(Calendar.WEDNESDAY),
            nowMs = cal.timeInMillis,
        )
        assertNotNull(next)
        val out = Calendar.getInstance().apply { timeInMillis = next!! }
        assertTrue(out.get(Calendar.DAY_OF_WEEK) == Calendar.WEDNESDAY)
    }

    @Test
    fun emptyWeekdaysMeansEveryDay() {
        val now = System.currentTimeMillis()
        assertNotNull(CustomAlarmScheduler.nextOccurrenceMs(6 * 60 + 30, emptySet(), now))
    }

    @Test
    fun invalidWeekdaysYieldNull() {
        assertNull(CustomAlarmScheduler.nextOccurrenceMs(6 * 60, setOf(99), System.currentTimeMillis()))
    }
}

class CustomAlarmCodecTest {
    @Test
    fun roundTrip() {
        val alarms = listOf(
            CustomAlarm(id = "a1", label = "Gym", minutes = 5 * 60 + 45, enabled = true, weekdays = setOf(2, 4, 6)),
        )
        val decoded = CustomAlarmCodec.decode(CustomAlarmCodec.encode(alarms))
        assertTrue(decoded.size == 1)
        assertTrue(decoded[0].label == "Gym")
        assertTrue(decoded[0].minutes == 5 * 60 + 45)
        assertTrue(decoded[0].weekdays == setOf(2, 4, 6))
    }
}
