package com.noop.alarm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted list of UnifiedAlarms plus the one nullable strap-armed id. JSON-backed in a dedicated
 * SharedPreferences file. Display order is user-controlled.
 *
 * The coordinator is the only thing that writes armedStrapAlarmId. The screen layer never touches
 * it.
 */
class UnifiedAlarmStore(private val prefs: SharedPreferences) {

    private val _alarms = MutableStateFlow(loadAlarms())
    val alarms: StateFlow<List<UnifiedAlarm>> = _alarms.asStateFlow()

    /** True if at least one enabled alarm has smart wake enabled. Used by the BLE service. */
    val smartWakeOn: Boolean get() = _alarms.value.any { it.enabled && it.smartWake }

    private val _armedStrap = MutableStateFlow(prefs.getString(KEY_ARMED_STRAP_ID, null))
    val armedStrapAlarmId: StateFlow<String?> = _armedStrap.asStateFlow()

    /** Epoch seconds for [armedStrapAlarmId], when known. Older prefs may have an id but no epoch. */
    fun armedStrapAlarmEpochSec(): Long? = prefs.getLong(KEY_ARMED_STRAP_EPOCH_SEC, 0L).takeIf { it > 0L }

    /** Alarm ids with phone-side AlarmManager entries currently registered. */
    fun scheduledPhoneAlarmIds(): Set<String> =
        prefs.getStringSet(KEY_SCHEDULED_PHONE_IDS, emptySet())?.toSet().orEmpty()

    fun setScheduledPhoneAlarmIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_SCHEDULED_PHONE_IDS, ids).apply()
    }

    /** Strap alarm id that fired and is waiting for the firmware disabled/dismissed event. */
    fun awaitingStrapDismissAlarmId(): String? = prefs.getString(KEY_AWAITING_STRAP_DISMISS_ID, null)

    fun setAwaitingStrapDismissAlarmId(id: String?) {
        prefs.edit().apply {
            if (id.isNullOrBlank()) remove(KEY_AWAITING_STRAP_DISMISS_ID) else putString(KEY_AWAITING_STRAP_DISMISS_ID, id)
        }.apply()
    }

    val migrationComplete: Boolean
        get() = prefs.getBoolean(KEY_MIGRATED, false)

    fun add(alarm: UnifiedAlarm) = mutate { it + alarm.sanitized() }

    fun update(id: String, alarm: UnifiedAlarm) = mutate { list ->
        list.map { if (it.id == id) alarm.copy(id = id).sanitized() else it }
    }

    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    fun setEnabled(id: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /**
     * Disable [id] after it fired when it is a one-shot alarm. Empty weekdays is the one-shot marker;
     * non-empty weekday sets are recurring and must stay enabled for their next matching day.
     */
    fun disableIfOneShot(id: String): Boolean {
        var changed = false
        mutate { list ->
            list.map { alarm ->
                if (alarm.id == id && alarm.enabled && alarm.weekdays.isEmpty()) {
                    changed = true
                    alarm.copy(enabled = false)
                } else alarm
            }
        }
        return changed
    }

    fun reorder(fromIndex: Int, toIndex: Int) = mutate { list ->
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) list
        else {
            val mutable = list.toMutableList()
            val item = mutable.removeAt(fromIndex)
            mutable.add(toIndex, item)
            mutable.toList()
        }
    }

    /** Finish the one-time legacy prefs migration without duplicating the JSON persistence contract. */
    internal fun replaceAlarmsFromMigration(alarms: List<UnifiedAlarm>) {
        val next = alarms.map { it.sanitized() }
        prefs.edit()
            .putString(KEY_ALARMS, encodeAlarms(next))
            .putBoolean(KEY_MIGRATED, true)
            .apply()
        _alarms.value = next
    }

    internal fun markMigrationComplete() {
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    fun setArmedStrapAlarmId(id: String?) {
        setArmedStrapAlarm(id, null)
    }

    fun setArmedStrapAlarm(id: String?, epochSec: Long?) {
        prefs.edit().apply {
            if (id == null) {
                remove(KEY_ARMED_STRAP_ID)
                remove(KEY_ARMED_STRAP_EPOCH_SEC)
            } else {
                putString(KEY_ARMED_STRAP_ID, id)
                if (epochSec != null && epochSec > 0L) putLong(KEY_ARMED_STRAP_EPOCH_SEC, epochSec)
                else remove(KEY_ARMED_STRAP_EPOCH_SEC)
            }
        }.apply()
        _armedStrap.value = id
    }

    private fun mutate(op: (List<UnifiedAlarm>) -> List<UnifiedAlarm>) {
        val next = op(_alarms.value)
        prefs.edit().putString(KEY_ALARMS, encodeAlarms(next)).apply()
        _alarms.value = next
    }

    private fun loadAlarms(): List<UnifiedAlarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        return decodeAlarms(raw).map { it.sanitized() }
    }

    private fun encodeAlarms(alarms: List<UnifiedAlarm>): String {
        val array = JSONArray()
        for (alarm in alarms) array.put(alarm.toJson())
        return array.toString()
    }

    private fun decodeAlarms(raw: String): List<UnifiedAlarm> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(obj.toUnifiedAlarm() ?: continue)
            }
        }
    }.getOrDefault(emptyList())

    private fun UnifiedAlarm.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("enabled", enabled)
        .put("wakeMinutes", wakeMinutes)
        .put("weekdays", JSONArray().also { out -> weekdays.sorted().forEach { out.put(it) } })
        .put("source", source.name)
        .put("smartWake", smartWake)
        .put("preWakeWindowMinutes", preWakeWindowMinutes)
        .put("phoneBackupDelayMinutes", phoneBackupDelayMinutes)

    private fun JSONObject.toUnifiedAlarm(): UnifiedAlarm? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val source = runCatching {
            AlarmSource.valueOf(optString("source", AlarmSource.STRAP.name))
        }.getOrDefault(AlarmSource.STRAP)
        val weekdayArray = optJSONArray("weekdays")
        val weekdays = buildSet {
            if (weekdayArray != null) {
                for (i in 0 until weekdayArray.length()) add(weekdayArray.optInt(i))
            }
        }
        return UnifiedAlarm(
            id = id,
            enabled = optBoolean("enabled", true),
            wakeMinutes = optInt("wakeMinutes", UnifiedAlarm.DEFAULT_WAKE),
            weekdays = weekdays,
            source = source,
            smartWake = optBoolean("smartWake", false),
            preWakeWindowMinutes = optInt("preWakeWindowMinutes", UnifiedAlarm.DEFAULT_PRE_WAKE),
            phoneBackupDelayMinutes = optInt("phoneBackupDelayMinutes", UnifiedAlarm.DEFAULT_PHONE_BACKUP),
        )
    }

    companion object {
        const val PREFS_NAME = "noop_alarm"
        const val KEY_ALARMS = "alarms"
        const val KEY_ARMED_STRAP_ID = "armedStrapAlarmId"
        const val KEY_ARMED_STRAP_EPOCH_SEC = "armedStrapAlarmEpochSec"
        const val KEY_SCHEDULED_PHONE_IDS = "scheduledPhoneAlarmIds"
        const val KEY_AWAITING_STRAP_DISMISS_ID = "awaitingStrapDismissAlarmId"
        const val KEY_MIGRATED = "noop.alarm.migrated"

        fun from(context: Context): UnifiedAlarmStore =
            UnifiedAlarmStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
