package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedAlarmTest {

    @Test fun defaultsMatchSpec() {
        val a = UnifiedAlarm(id = "x", wakeMinutes = 7 * 60)
        assertEquals(true, a.enabled)
        assertEquals(emptySet<Int>(), a.weekdays)
        assertEquals(AlarmSource.STRAP, a.source)
        assertEquals(false, a.smartWake)
        assertEquals(30, a.preWakeWindowMinutes)
        assertEquals(5, a.phoneBackupDelayMinutes)
    }

    @Test fun sanitizedClampsAllRanges() {
        val dirty = UnifiedAlarm(
            id = "x",
            wakeMinutes = 99_999,
            weekdays = setOf(0, 1, 5, 8, 99),
            preWakeWindowMinutes = 999,
            phoneBackupDelayMinutes = -5,
        )
        val clean = dirty.sanitized()
        assertTrue(clean.wakeMinutes in 0..1439)
        assertEquals(setOf(1, 5), clean.weekdays)
        assertEquals(60, clean.preWakeWindowMinutes)
        assertEquals(1, clean.phoneBackupDelayMinutes)
    }

    @Test fun sanitizedFloorsWindow() {
        val dirty = UnifiedAlarm(id = "x", wakeMinutes = 0, preWakeWindowMinutes = 1)
        assertEquals(5, dirty.sanitized().preWakeWindowMinutes)
    }

    @Test fun sanitizedCeilsPhoneBackup() {
        val dirty = UnifiedAlarm(id = "x", wakeMinutes = 0, phoneBackupDelayMinutes = 999)
        assertEquals(15, dirty.sanitized().phoneBackupDelayMinutes)
    }

    @Test fun newIdReturnsDistinctUuids() {
        assertNotEquals(UnifiedAlarm.newId(), UnifiedAlarm.newId())
    }

    @Test fun roundTripsThroughStoreJson() {
        val a = UnifiedAlarm(
            id = "abc", enabled = true, wakeMinutes = 390,
            weekdays = setOf(2, 3, 4), source = AlarmSource.STRAP_AND_PHONE,
            smartWake = true, preWakeWindowMinutes = 45, phoneBackupDelayMinutes = 7,
        )
        val prefs = InMemorySharedPreferences()
        UnifiedAlarmStore(prefs).add(a)
        val back = UnifiedAlarmStore(prefs).alarms.value.single()
        assertEquals(a, back)
    }
}
