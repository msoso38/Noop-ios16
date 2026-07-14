package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartAlarmStoreTest {

    @Test fun scheduledAlarmIdPersistsAndClearsBlankValues() {
        val prefs = InMemorySharedPreferences()
        val store = SmartAlarmStore(prefs)

        store.scheduledAlarmId = "alarm-1"
        assertEquals("alarm-1", SmartAlarmStore(prefs).scheduledAlarmId)

        store.scheduledAlarmId = ""
        assertNull(SmartAlarmStore(prefs).scheduledAlarmId)
    }
}
