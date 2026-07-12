package com.noop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device store for user-confirmed workout sport labels + phone motion features.
 * Feeds the localhoop-style sport predictor / PC ML join without inventing clinical vitals.
 */
class WorkoutLabelStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(
        val startTsMs: Long,
        val endTsMs: Long,
        val sportKey: String,
        val provenance: String,
        val labelTsMs: Long,
        val meanAccelMag: Double = 0.0,
        val stdAccelMag: Double = 0.0,
        val meanGyroMag: Double = 0.0,
        val stdGyroMag: Double = 0.0,
        val sampleCount: Int = 0,
        val hasGyro: Boolean = false,
        val hasMag: Boolean = false,
    )

    fun record(entry: Entry) {
        val list = load().toMutableList()
        list.removeAll { it.startTsMs == entry.startTsMs && it.endTsMs == entry.endTsMs }
        list.add(0, entry)
        while (list.size > MAX) list.removeAt(list.lastIndex)
        prefs.edit().putString(KEY, encode(list)).apply()
    }

    fun load(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Entry(
                            startTsMs = o.getLong("startTsMs"),
                            endTsMs = o.getLong("endTsMs"),
                            sportKey = o.getString("sportKey"),
                            provenance = o.optString("provenance", "user"),
                            labelTsMs = o.optLong("labelTsMs", 0L),
                            meanAccelMag = o.optDouble("meanAccelMag", 0.0),
                            stdAccelMag = o.optDouble("stdAccelMag", 0.0),
                            meanGyroMag = o.optDouble("meanGyroMag", 0.0),
                            stdGyroMag = o.optDouble("stdGyroMag", 0.0),
                            sampleCount = o.optInt("sampleCount", 0),
                            hasGyro = o.optBoolean("hasGyro", false),
                            hasMag = o.optBoolean("hasMag", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Prior labels for the same sportKey window — used to nudge SportClassifier confidence. */
    fun labelCountFor(sportKey: String): Int =
        load().count { it.sportKey.equals(sportKey, ignoreCase = true) }

    private fun encode(list: List<Entry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("startTsMs", e.startTsMs)
                    .put("endTsMs", e.endTsMs)
                    .put("sportKey", e.sportKey)
                    .put("provenance", e.provenance)
                    .put("labelTsMs", e.labelTsMs)
                    .put("meanAccelMag", e.meanAccelMag)
                    .put("stdAccelMag", e.stdAccelMag)
                    .put("meanGyroMag", e.meanGyroMag)
                    .put("stdGyroMag", e.stdGyroMag)
                    .put("sampleCount", e.sampleCount)
                    .put("hasGyro", e.hasGyro)
                    .put("hasMag", e.hasMag),
            )
        }
        return arr.toString()
    }

    companion object {
        private const val PREFS = "noop_workout_labels"
        private const val KEY = "labels_v1"
        private const val MAX = 400

        @Volatile private var instance: WorkoutLabelStore? = null
        fun from(context: Context): WorkoutLabelStore =
            instance ?: synchronized(this) {
                instance ?: WorkoutLabelStore(context).also { instance = it }
            }
    }
}
