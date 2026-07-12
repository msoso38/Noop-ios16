package com.noop.data

import android.content.Context
import com.noop.analytics.WhoopNoopAlignment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists model evolution entries + last day alignment pass scores.
 * On-device only; mirrored to PC via DEBUG telemetry when lines are logged.
 */
class ModelEvolutionStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadEvolutions(): List<WhoopNoopAlignment.EvolutionEntry> {
        val raw = prefs.getString(KEY_EVO, null)
        if (raw.isNullOrBlank()) {
            val seed = WhoopNoopAlignment.seedEvolutions()
            saveEvolutions(seed)
            return seed
        }
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        WhoopNoopAlignment.EvolutionEntry(
                            version = o.getString("version"),
                            recordedAtMs = o.optLong("recordedAtMs", 0L),
                            passScore = if (o.isNull("passScore")) null else o.getDouble("passScore"),
                            nDaysPaired = o.optInt("nDaysPaired", 0),
                            notes = o.optString("notes"),
                        ),
                    )
                }
            }
        }.getOrElse { WhoopNoopAlignment.seedEvolutions() }
    }

    fun saveEvolutions(list: List<WhoopNoopAlignment.EvolutionEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("version", e.version)
                    .put("recordedAtMs", e.recordedAtMs)
                    .put("passScore", e.passScore ?: JSONObject.NULL)
                    .put("nDaysPaired", e.nDaysPaired)
                    .put("notes", e.notes),
            )
        }
        prefs.edit().putString(KEY_EVO, arr.toString()).apply()
    }

    /** Append or update current model version with a new pass score sample. */
    fun recordPassSample(passScore: Double?, nDaysPaired: Int, notes: String = "") {
        val list = loadEvolutions().toMutableList()
        val ver = WhoopNoopAlignment.MODEL_VERSION
        val idx = list.indexOfFirst { it.version == ver }
        val entry = WhoopNoopAlignment.EvolutionEntry(
            version = ver,
            recordedAtMs = System.currentTimeMillis(),
            passScore = passScore,
            nDaysPaired = nDaysPaired,
            notes = notes.ifBlank {
                "Live alignment sample · pass=${passScore?.toInt() ?: "—"} · pairs=$nDaysPaired"
            },
        )
        if (idx >= 0) list[idx] = entry else list.add(entry)
        saveEvolutions(list)
        if (passScore != null) {
            prefs.edit().putFloat(KEY_LAST_PASS, passScore.toFloat()).apply()
        }
    }

    fun lastPassScore(): Double? {
        if (!prefs.contains(KEY_LAST_PASS)) return null
        return prefs.getFloat(KEY_LAST_PASS, Float.NaN).toDouble().takeIf { !it.isNaN() }
    }

    companion object {
        private const val PREFS = "noop_model_evolution"
        private const val KEY_EVO = "evolutions_json"
        private const val KEY_LAST_PASS = "last_pass_score"

        fun from(context: Context) = ModelEvolutionStore(context)
    }
}
