package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedAlarmMigrationTest {

    private val allWeekdays = setOf(1, 2, 3, 4, 5, 6, 7)

    // Helper: encode a "dow:min" StringSet identical to NoopPrefs.smartAlarmDayOverrides.
    private fun overrideSet(vararg pairs: Pair<Int, Int>): Set<String> =
        pairs.map { (d, m) -> "$d:$m" }.toSet()

    private fun weekdaysSet(vararg d: Int): Set<String> = d.map { it.toString() }.toSet()

    @Test fun noLegacyStateWritesEmptyListAndMarksMigrated() {
        val newPrefs = InMemorySharedPreferences()
        val changed = UnifiedAlarmMigration.migrate(newPrefs, InMemorySharedPreferences(), InMemorySharedPreferences())
        assertEquals(true, changed)
        assertEquals(true, newPrefs.getBoolean(UnifiedAlarmStore.KEY_MIGRATED, false))
        assertTrue(UnifiedAlarmStore(newPrefs).alarms.value.isEmpty())
    }

    @Test fun strapOnlyYieldsOneStrapAlarm() {
        val newPrefs = InMemorySharedPreferences()
        val legacy = InMemorySharedPreferences().apply {
            edit().putBoolean("noop.smartAlarmEnabled", true)
                .putInt("noop.smartAlarmMinutes", 6 * 60 + 30)
                .putStringSet("noop.smartAlarmWeekdays", weekdaysSet(2, 3, 4, 5, 6))
                .apply()
        }
        assertEquals(true, UnifiedAlarmMigration.migrate(newPrefs, legacy, InMemorySharedPreferences()))
        val alarms = UnifiedAlarmStore(newPrefs).alarms.value
        assertEquals(1, alarms.size)
        val a = alarms.first()
        assertEquals(AlarmSource.STRAP, a.source)
        assertEquals(6 * 60 + 30, a.wakeMinutes)
        assertEquals(setOf(2, 3, 4, 5, 6), a.weekdays)
        assertEquals(true, a.enabled)
    }

    @Test fun phoneOnlyYieldsOnePhoneAlarmWithSmartWakeOnAndWindow() {
        val newPrefs = InMemorySharedPreferences()
        val phone = InMemorySharedPreferences().apply {
            edit().putBoolean("alarm.enabled", true)
                .putInt("alarm.targetMinutes", 6 * 60 + 30)
                .putInt("alarm.windowMinutes", 45)
                .apply()
        }
        assertEquals(true, UnifiedAlarmMigration.migrate(newPrefs, InMemorySharedPreferences(), phone))
        val a = UnifiedAlarmStore(newPrefs).alarms.value.single()
        assertEquals(AlarmSource.PHONE, a.source)
        assertEquals(6 * 60 + 30, a.wakeMinutes)
        assertEquals(allWeekdays, a.weekdays)
        assertEquals(45, a.preWakeWindowMinutes)
        assertEquals(true, a.smartWake)
    }

    @Test fun strapPlusPhonePlusBuzzCompanionYieldsStrapAndPhone() {
        val newPrefs = InMemorySharedPreferences()
        val legacy = InMemorySharedPreferences().apply {
            edit().putBoolean("noop.smartAlarmEnabled", true)
                .putInt("noop.smartAlarmMinutes", 6 * 60 + 30)
                .putBoolean("noop.buzzWhoop4WithAlarm", true)
                .apply()
        }
        val phone = InMemorySharedPreferences().apply {
            edit().putBoolean("alarm.enabled", true)
                .putInt("alarm.targetMinutes", 6 * 60 + 30)
                .apply()
        }
        assertEquals(true, UnifiedAlarmMigration.migrate(newPrefs, legacy, phone))
        val a = UnifiedAlarmStore(newPrefs).alarms.value.single()
        assertEquals(AlarmSource.STRAP_AND_PHONE, a.source)
        assertEquals(6 * 60 + 30, a.wakeMinutes)
        assertEquals(allWeekdays, a.weekdays)
    }

    @Test fun perDayOverridesFanIntoExtraRows() {
        val newPrefs = InMemorySharedPreferences()
        val legacy = InMemorySharedPreferences().apply {
            edit().putBoolean("noop.smartAlarmEnabled", true)
                .putInt("noop.smartAlarmMinutes", 6 * 60 + 30)
                .putStringSet("noop.smartAlarmWeekdays", weekdaysSet(2, 3, 4, 5, 6))  // weekdays
                .putStringSet("noop.smartAlarmDayOverrides", overrideSet(7 to 9 * 60))   // Sat 09:00
                .apply()
        }
        assertEquals(true, UnifiedAlarmMigration.migrate(newPrefs, legacy, InMemorySharedPreferences()))
        val alarms = UnifiedAlarmStore(newPrefs).alarms.value
        // Base row with weekdays {2..6} at 06:30 + one override row for weekday=7 (Sat) at 09:00.
        assertEquals(2, alarms.size)
        val base = alarms.first { it.weekdays == setOf(2, 3, 4, 5, 6) }
        assertEquals(6 * 60 + 30, base.wakeMinutes)
        val override = alarms.first { it.weekdays == setOf(7) }
        assertEquals(9 * 60, override.wakeMinutes)
    }

    @Test fun isIdempotentOnRerun() {
        val newPrefs = InMemorySharedPreferences()
        val legacy = InMemorySharedPreferences().apply {
            edit().putBoolean("noop.smartAlarmEnabled", true)
                .putInt("noop.smartAlarmMinutes", 6 * 60 + 30)
                .apply()
        }
        assertEquals(true, UnifiedAlarmMigration.migrate(newPrefs, legacy, InMemorySharedPreferences()))
        val firstSize = UnifiedAlarmStore(newPrefs).alarms.value.size
        // Re-run: must NOT re-import or duplicate.
        assertEquals(false, UnifiedAlarmMigration.migrate(newPrefs, legacy, InMemorySharedPreferences()))
        assertEquals(firstSize, UnifiedAlarmStore(newPrefs).alarms.value.size)
    }

    @Test fun rerunAfterPartialWriteReplacesRowsInsteadOfDuplicating() {
        val newPrefs = InMemorySharedPreferences()
        UnifiedAlarmStore(newPrefs).add(UnifiedAlarm(id = "partial", wakeMinutes = 5 * 60))
        val legacy = InMemorySharedPreferences().apply {
            edit().putBoolean("noop.smartAlarmEnabled", true)
                .putInt("noop.smartAlarmMinutes", 6 * 60 + 30)
                .apply()
        }

        assertEquals(true, UnifiedAlarmMigration.migrate(newPrefs, legacy, InMemorySharedPreferences()))

        val alarms = UnifiedAlarmStore(newPrefs).alarms.value
        assertEquals(1, alarms.size)
        assertEquals(6 * 60 + 30, alarms.single().wakeMinutes)
        assertEquals(true, newPrefs.getBoolean(UnifiedAlarmStore.KEY_MIGRATED, false))
    }

    @Test fun doesNotDeleteLegacyKeys() {
        val newPrefs = InMemorySharedPreferences()
        val legacy = InMemorySharedPreferences().apply {
            edit().putBoolean("noop.smartAlarmEnabled", true)
                .putInt("noop.smartAlarmMinutes", 6 * 60 + 30)
                .apply()
        }
        assertEquals(true, UnifiedAlarmMigration.migrate(newPrefs, legacy, InMemorySharedPreferences()))
        assertEquals(true, legacy.getBoolean("noop.smartAlarmEnabled", false))
        assertEquals(6 * 60 + 30, legacy.getInt("noop.smartAlarmMinutes", 0))
    }
}
