package com.noop.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAlarmSchedulerTest {

    private val start = 1_000L
    private val deadline = 3_000L

    @Test
    fun earlyCueCanMoveDeadlineEarlierWithinWindow() {
        assertTrue(SmartAlarmScheduler.shouldAdvanceScheduledAlarm(1_500L, deadline, start, deadline))
    }

    @Test
    fun laterAndEqualCuesNeverMoveAnAlreadyAdvancedAlarmLater() {
        assertFalse(SmartAlarmScheduler.shouldAdvanceScheduledAlarm(1_500L, 1_500L, start, deadline))
        assertFalse(SmartAlarmScheduler.shouldAdvanceScheduledAlarm(2_500L, 1_500L, start, deadline))
        assertFalse(SmartAlarmScheduler.shouldAdvanceScheduledAlarm(9_000L, 1_500L, start, deadline))
    }

    @Test
    fun outOfWindowCueClampsButStillCannotViolateDirection() {
        assertTrue(SmartAlarmScheduler.shouldAdvanceScheduledAlarm(-500L, deadline, start, deadline))
        assertFalse(SmartAlarmScheduler.shouldAdvanceScheduledAlarm(-500L, start, start, deadline))
        assertFalse(SmartAlarmScheduler.shouldAdvanceScheduledAlarm(1_500L, deadline, deadline, start))
    }
}
