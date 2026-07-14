package com.noop.alarm

import android.content.Context
import android.content.SharedPreferences

/**
 * One-shot, idempotent migration from the legacy strap-alarm + phone-alarm + buzz-companion keys to
 * the UnifiedAlarmStore. Read-only on the legacy SharedPreferences files; a future cleanup release
 * deletes them in a separate one-file change.
 *
 * Android-only because it migrates Android legacy SharedPreferences keys. The unified alarm model is
 * platform-neutral, but this file does not modify the Apple UserDefaults/GRDB stores.
 */
object UnifiedAlarmMigration {

    private val ALL_WEEKDAYS = setOf(1, 2, 3, 4, 5, 6, 7)

    // Legacy key constants - keep them centralised so a copy-paste typo can't silently drop a field.
    private const val NOOP_PREFS = "noop_prefs"
    private const val SMART_ALARM_PREFS = "noop_smart_alarm"

    private const val L_STRAP_ENABLED = "noop.smartAlarmEnabled"
    private const val L_STRAP_MINUTES = "noop.smartAlarmMinutes"
    private const val L_STRAP_WEEKDAYS = "noop.smartAlarmWeekdays"
    private const val L_STRAP_OVERRIDES = "noop.smartAlarmDayOverrides"
    private const val L_BUZZ_COMPANION = "noop.buzzWhoop4WithAlarm"

    private const val L_PHONE_ENABLED = "alarm.enabled"
    private const val L_PHONE_TARGET = "alarm.targetMinutes"
    private const val L_PHONE_WINDOW = "alarm.windowMinutes"

    fun migrateIfNeeded(context: Context): Boolean {
        val newPrefs = context.getSharedPreferences(
            UnifiedAlarmStore.PREFS_NAME, Context.MODE_PRIVATE,
        )
        val legacy = context.getSharedPreferences(NOOP_PREFS, Context.MODE_PRIVATE)
        val phone = context.getSharedPreferences(SMART_ALARM_PREFS, Context.MODE_PRIVATE)
        return migrate(newPrefs, legacy, phone)
    }

    fun migrate(
        newPrefs: SharedPreferences,
        legacyPrefs: SharedPreferences,
        phonePrefs: SharedPreferences,
    ): Boolean {
        val store = UnifiedAlarmStore(newPrefs)
        if (store.migrationComplete) return false

        val strapOn = legacyPrefs.getBoolean(L_STRAP_ENABLED, false)
        val phoneOn = phonePrefs.getBoolean(L_PHONE_ENABLED, false)
        val buzzCompanion = legacyPrefs.getBoolean(L_BUZZ_COMPANION, false)

        if (!strapOn && !phoneOn) {
            store.markMigrationComplete()
            return true
        }

        val strapMinutes = legacyPrefs.getInt(L_STRAP_MINUTES, UnifiedAlarm.DEFAULT_WAKE)
        val strapWeekdays = legacyPrefs.getStringSet(L_STRAP_WEEKDAYS, emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.filter { it in 1..7 }?.toSet() ?: emptySet()
        val overrides = legacyPrefs.getStringSet(L_STRAP_OVERRIDES, emptySet())
            ?.mapNotNull {
                val parts = it.split(":")
                if (parts.size != 2) return@mapNotNull null
                val dow = parts[0].toIntOrNull() ?: return@mapNotNull null
                val min = parts[1].toIntOrNull() ?: return@mapNotNull null
                if (dow !in 1..7 || min !in 0 until UnifiedAlarm.MINUTES_PER_DAY) return@mapNotNull null
                dow to min
            }?.toMap() ?: emptyMap()
        val phoneTarget = phonePrefs.getInt(L_PHONE_TARGET, UnifiedAlarm.DEFAULT_WAKE)
        // Legacy "alarm.windowMinutes" was an AFTER-target deadline lead. The new model uses a
        // BEFORE-target pre-wake window. Magnitudes are user-meaningful (they tuned 30 min etc.) so
        // we carry the number across, even though the semantics flip. With smart wake on the user
        // ends up with a window of the same size on the other side of the wake time, which keeps
        // the migrated alarm familiar.
        val phoneWindow = phonePrefs.getInt(L_PHONE_WINDOW, UnifiedAlarm.DEFAULT_PRE_WAKE)

        // Base row source:
        //   strap + phone + buzz companion -> STRAP_AND_PHONE
        //   strap only                     -> STRAP
        //   phone only                     -> PHONE
        val baseSource = when {
            strapOn && phoneOn && buzzCompanion -> AlarmSource.STRAP_AND_PHONE
            strapOn                              -> AlarmSource.STRAP
            else                                 -> AlarmSource.PHONE
        }
        val baseWeekdays = if (baseSource == AlarmSource.PHONE) {
            ALL_WEEKDAYS  // legacy phone alarm was daily; unified empty weekdays now means one-shot
        } else {
            (strapWeekdays.ifEmpty { ALL_WEEKDAYS }) - overrides.keys
        }
        val baseWake = if (baseSource == AlarmSource.PHONE) phoneTarget else strapMinutes
        val baseWindow = if (baseSource == AlarmSource.PHONE) phoneWindow else UnifiedAlarm.DEFAULT_PRE_WAKE
        val baseSmartWake = baseSource == AlarmSource.PHONE  // legacy phone alarm always ran the watcher

        val migrated = buildList {
            add(UnifiedAlarm(
                id = UnifiedAlarm.newId(),
                enabled = true,
                wakeMinutes = baseWake,
                weekdays = baseWeekdays,
                source = baseSource,
                smartWake = baseSmartWake,
                preWakeWindowMinutes = baseWindow,
            ))

            // Per-day overrides become independent STRAP rows (the override only lived on the strap side).
            for ((dow, min) in overrides) {
                add(UnifiedAlarm(
                    id = UnifiedAlarm.newId(),
                    enabled = true,
                    wakeMinutes = min,
                    weekdays = setOf(dow),
                    source = AlarmSource.STRAP,
                ))
            }
        }

        store.replaceAlarmsFromMigration(migrated)
        return true
    }
}
