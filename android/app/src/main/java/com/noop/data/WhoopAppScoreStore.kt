package com.noop.data

import android.content.Context
import org.json.JSONObject

/**
 * Official **WHOOP mobile app** scores only â€” NOT open BLE, NOT NOOP-computed Charge/Effort.
 *
 * The WHOOP app turns strap BLE + cloud ML into Recovery %, Day Strain (0â€“21), Stress, etc.
 * Those numbers never appear as open GATT fields. Comparative labels must come from:
 *   1. WHOOP Data Export CSV (physiological_cycles) â†’ [WhoopCsvImporter] into deviceId [DEVICE_ID]
 *   2. Manual entry here (user reads Recovery / Strain from the WHOOP app UI today)
 *   3. Bundled assets/whoop_app_labels.jsonl (real adb / capture only â€” never synthetic)
 *
 * Never invent app scores from 2A37 / type47.
 */
class WhoopAppScoreStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class DayScores(
        val day: String,
        /** WHOOP Recovery % as shown in the app (0â€“100). */
        val recoveryPct: Double?,
        /** WHOOP Day Strain as shown in the app (0â€“21 scale). */
        val dayStrain021: Double?,
        /** WHOOP Sleep performance % as shown in the app (0–100), when captured. */
        val sleepPct: Double? = null,
        /** Optional stress index if user logs it (app-native units; null if unknown). */
        val stressNote: String? = null,
        /** Optional numeric stress 0–100 when logged from app / accessibility. */
        val stressPct: Double? = null,
        val source: String, // "export" | "manual" | "adb_ui" | "accessibility"
        val updatedAtMs: Long = System.currentTimeMillis(),
    )

    fun get(day: String): DayScores? {
        val raw = prefs.getString(key(day), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            DayScores(
                day = day,
                recoveryPct = if (o.has("recoveryPct") && !o.isNull("recoveryPct")) o.getDouble("recoveryPct") else null,
                dayStrain021 = if (o.has("dayStrain021") && !o.isNull("dayStrain021")) o.getDouble("dayStrain021") else null,
                sleepPct = if (o.has("sleepPct") && !o.isNull("sleepPct")) o.getDouble("sleepPct") else null,
                stressNote = o.optString("stressNote").takeIf { it.isNotBlank() },
                stressPct = if (o.has("stressPct") && !o.isNull("stressPct")) o.getDouble("stressPct") else null,
                source = o.optString("source", "manual"),
                updatedAtMs = o.optLong("updatedAtMs", 0L),
            )
        }.getOrNull()
    }

    fun put(scores: DayScores) {
        // Never overwrite a user manual entry with a weaker auto seed of the same day unless newer.
        val existing = get(scores.day)
        if (existing != null && existing.source == "manual" && scores.source != "manual") {
            if (existing.updatedAtMs >= scores.updatedAtMs) return
        }
        val o = JSONObject()
            .put("recoveryPct", scores.recoveryPct ?: JSONObject.NULL)
            .put("dayStrain021", scores.dayStrain021 ?: JSONObject.NULL)
            .put("sleepPct", scores.sleepPct ?: JSONObject.NULL)
            .put("stressNote", scores.stressNote ?: JSONObject.NULL)
            .put("stressPct", scores.stressPct ?: JSONObject.NULL)
            .put("source", scores.source)
            .put("updatedAtMs", scores.updatedAtMs)
        prefs.edit().putString(key(scores.day), o.toString()).apply()
    }

    fun clear(day: String) {
        prefs.edit().remove(key(day)).apply()
    }

    /** Most recent logged app-score days (newest first), up to [limit]. Prefs-only — no Room. */
    fun recentDays(limit: Int = 14): List<DayScores> {
        val out = ArrayList<DayScores>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith("day.") || v !is String) continue
            val day = k.removePrefix("day.")
            get(day)?.let { out.add(it) }
        }
        return out.sortedByDescending { it.day }.take(limit.coerceAtLeast(1))
    }

    /**
     * Import real WHOOP **app** labels from assets/whoop_app_labels.jsonl (adb UI captures, etc.).
     * Idempotent per asset generation stamp. Never invents rows â€” only parses present JSONL lines.
     * @return number of days newly written or refreshed.
     */
    fun seedFromAssetsIfNeeded(assetGeneration: Int = ASSET_GENERATION): Int {
        if (prefs.getInt(KEY_ASSET_GEN, 0) >= assetGeneration) return 0
        val imported = importJsonlFromAssets("whoop_app_labels.jsonl")
        prefs.edit().putInt(KEY_ASSET_GEN, assetGeneration).apply()
        return imported
    }

    /** Parse JSONL: {day, strain_021, recovery_pct, source?} â€” real captures only. */
    fun importJsonlFromAssets(assetName: String): Int {
        val text = runCatching {
            appContext.assets.open(assetName).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return 0
        return importJsonlText(text)
    }

    fun importJsonlText(text: String): Int {
        var n = 0
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val scores = parseLabelLine(t) ?: continue
            put(scores)
            n++
        }
        return n
    }

    private fun parseLabelLine(line: String): DayScores? = runCatching {
        val o = JSONObject(line)
        val day = o.optString("day").takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) } ?: return null
        val strain = when {
            o.has("strain_021") && !o.isNull("strain_021") -> o.getDouble("strain_021")
            o.has("dayStrain021") && !o.isNull("dayStrain021") -> o.getDouble("dayStrain021")
            o.has("day_strain") && !o.isNull("day_strain") -> o.getDouble("day_strain")
            else -> null
        }?.coerceIn(0.0, 21.0)
        val recovery = when {
            o.has("recovery_pct") && !o.isNull("recovery_pct") -> o.getDouble("recovery_pct")
            o.has("recoveryPct") && !o.isNull("recoveryPct") -> o.getDouble("recoveryPct")
            else -> null
        }?.coerceIn(0.0, 100.0)
        val sleep = when {
            o.has("sleep_pct") && !o.isNull("sleep_pct") -> o.getDouble("sleep_pct")
            o.has("sleepPct") && !o.isNull("sleepPct") -> o.getDouble("sleepPct")
            else -> null
        }?.coerceIn(0.0, 100.0)
        val stress = when {
            o.has("stress_pct") && !o.isNull("stress_pct") -> o.getDouble("stress_pct")
            o.has("stressPct") && !o.isNull("stressPct") -> o.getDouble("stressPct")
            else -> null
        }?.coerceIn(0.0, 100.0)
        if (strain == null && recovery == null && sleep == null && stress == null) return null
        DayScores(
            day = day,
            recoveryPct = recovery,
            dayStrain021 = strain,
            sleepPct = sleep,
            stressPct = stress,
            source = o.optString("source", "adb_ui").ifBlank { "adb_ui" },
            updatedAtMs = o.optLong("captured_at_ms", System.currentTimeMillis()),
        )
    }.getOrNull()

    private fun key(day: String) = "day.$day"

    companion object {
        /** Room deviceId reserved for official WHOOP **app** export rows (native scales). */
        const val DEVICE_ID = "whoop-app"
        private const val PREFS = "noop_whoop_app_scores"
        private const val KEY_ASSET_GEN = "asset_gen"
        /** Bump when assets/whoop_app_labels.jsonl gains new real capture days. */
        const val ASSET_GENERATION = 2

        fun from(context: Context) = WhoopAppScoreStore(context)
    }
}
