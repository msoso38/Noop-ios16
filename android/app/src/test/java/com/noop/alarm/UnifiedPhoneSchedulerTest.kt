package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedPhoneSchedulerTest {

    private class FakeOneShotScheduler(
        private val failures: Set<String> = emptySet(),
    ) : OneShotAlarmScheduler {
        val scheduled = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override fun schedule(fire: PhoneFire): Boolean {
            scheduled += fire.alarmId
            return fire.alarmId !in failures
        }

        override fun cancel(alarmId: String) {
            cancelled += alarmId
        }
    }

    private fun fire(id: String, at: Long = 1_700_000_000_000L): PhoneFire = PhoneFire(
        alarmId = id,
        fireAtEpochMs = at,
        windowStartEpochMs = at,
        smartWake = false,
        isStrapPhoneBackup = false,
    )

    @Test fun reconcilePersistsOnlyActuallyScheduledPhoneAlarms() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        val oneShot = FakeOneShotScheduler(failures = setOf("b"))
        val scheduler = UnifiedPhoneScheduler(AlarmTestContext(), store, oneShot)

        scheduler.reconcile(listOf(fire("a"), fire("b")))

        assertEquals(listOf("a", "b"), oneShot.scheduled)
        assertEquals(listOf("b"), oneShot.cancelled)
        assertEquals(setOf("a"), store.scheduledPhoneAlarmIds())
    }

    @Test fun reconcileCancelsStaleIdsBeforeSchedulingDesiredSet() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.setScheduledPhoneAlarmIds(setOf("old", "keep"))
        val oneShot = FakeOneShotScheduler()
        val scheduler = UnifiedPhoneScheduler(AlarmTestContext(), store, oneShot)

        scheduler.reconcile(listOf(fire("keep"), fire("new")))

        assertTrue("old" in oneShot.cancelled)
        assertEquals(listOf("keep", "new"), oneShot.scheduled)
        assertEquals(setOf("keep", "new"), store.scheduledPhoneAlarmIds())
    }
}
