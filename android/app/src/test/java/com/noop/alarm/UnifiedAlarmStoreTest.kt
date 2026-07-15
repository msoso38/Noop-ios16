package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedAlarmStoreTest {

    private fun store(): UnifiedAlarmStore = UnifiedAlarmStore(InMemorySharedPreferences())

    private fun mk(id: String, wake: Int = 7 * 60) = UnifiedAlarm(id = id, wakeMinutes = wake)

    @Test fun emptyByDefault() {
        assertTrue(store().alarms.value.isEmpty())
    }

    @Test fun addAppendsAtEnd() {
        val s = store()
        s.add(mk("a"))
        s.add(mk("b"))
        assertEquals(listOf("a", "b"), s.alarms.value.map { it.id })
    }

    @Test fun addSanitizesBadValues() {
        val s = store()
        s.add(UnifiedAlarm(id = "x", wakeMinutes = 99_999, preWakeWindowMinutes = 1))
        val out = s.alarms.value.first()
        assertEquals(1439, out.wakeMinutes)
        assertEquals(5, out.preWakeWindowMinutes)
    }

    @Test fun updateReplacesById() {
        val s = store()
        s.add(mk("a", wake = 6 * 60))
        s.update("a", mk("a", wake = 8 * 60))
        assertEquals(8 * 60, s.alarms.value.first().wakeMinutes)
    }

    @Test fun deleteRemoves() {
        val s = store()
        s.add(mk("a"))
        s.add(mk("b"))
        s.delete("a")
        assertEquals(listOf("b"), s.alarms.value.map { it.id })
    }

    @Test fun setEnabledFlipsFlag() {
        val s = store()
        s.add(mk("a"))
        s.setEnabled("a", false)
        assertEquals(false, s.alarms.value.first().enabled)
    }

    @Test fun disableIfOneShotDisablesEmptyWeekdayAlarm() {
        val s = store()
        s.add(UnifiedAlarm(id = "a", wakeMinutes = 7 * 60, weekdays = emptySet()))

        assertEquals(true, s.disableIfOneShot("a"))

        assertEquals(false, s.alarms.value.first().enabled)
    }

    @Test fun disableIfOneShotLeavesRecurringAlarmEnabled() {
        val s = store()
        s.add(UnifiedAlarm(id = "a", wakeMinutes = 7 * 60, weekdays = setOf(2)))

        assertEquals(false, s.disableIfOneShot("a"))

        assertEquals(true, s.alarms.value.first().enabled)
    }

    @Test fun reorderMovesIndex() {
        val s = store()
        s.add(mk("a")); s.add(mk("b")); s.add(mk("c"))
        s.reorder(0, 2)  // a -> end
        assertEquals(listOf("b", "c", "a"), s.alarms.value.map { it.id })
    }

    @Test fun reorderIsNoopOnBadIndices() {
        val s = store()
        s.add(mk("a")); s.add(mk("b"))
        s.reorder(-1, 5)
        assertEquals(listOf("a", "b"), s.alarms.value.map { it.id })
    }

    @Test fun persistsAcrossInstances() {
        val prefs = InMemorySharedPreferences()
        UnifiedAlarmStore(prefs).add(mk("a"))
        val reloaded = UnifiedAlarmStore(prefs)
        assertEquals(listOf("a"), reloaded.alarms.value.map { it.id })
    }

    @Test fun armedStrapIdPersistsAndNullable() {
        val prefs = InMemorySharedPreferences()
        val s1 = UnifiedAlarmStore(prefs)
        assertNull(s1.armedStrapAlarmId.value)
        assertNull(s1.armedStrapAlarmEpochSec())
        s1.setArmedStrapAlarm("xyz", 1_700_000_000L)
        val s2 = UnifiedAlarmStore(prefs)
        assertEquals("xyz", s2.armedStrapAlarmId.value)
        assertEquals(1_700_000_000L, s2.armedStrapAlarmEpochSec())
        s2.setArmedStrapAlarm(null, null)
        val s3 = UnifiedAlarmStore(prefs)
        assertNull(s3.armedStrapAlarmId.value)
        assertNull(s3.armedStrapAlarmEpochSec())
    }

    @Test fun scheduledPhoneIdsPersistAcrossInstances() {
        val prefs = InMemorySharedPreferences()
        val s1 = UnifiedAlarmStore(prefs)
        s1.setScheduledPhoneAlarmIds(setOf("a", "b"))

        val s2 = UnifiedAlarmStore(prefs)
        assertEquals(setOf("a", "b"), s2.scheduledPhoneAlarmIds())

        s2.setScheduledPhoneAlarmIds(setOf("b"))
        assertEquals(setOf("b"), UnifiedAlarmStore(prefs).scheduledPhoneAlarmIds())
    }

    @Test fun awaitingStrapDismissIdPersistsAndNullable() {
        val prefs = InMemorySharedPreferences()
        val s1 = UnifiedAlarmStore(prefs)
        assertNull(s1.awaitingStrapDismissAlarmId())
        s1.setAwaitingStrapDismissAlarmId("alarm-1")

        val s2 = UnifiedAlarmStore(prefs)
        assertEquals("alarm-1", s2.awaitingStrapDismissAlarmId())

        s2.setAwaitingStrapDismissAlarmId(null)
        assertNull(UnifiedAlarmStore(prefs).awaitingStrapDismissAlarmId())
    }
}
