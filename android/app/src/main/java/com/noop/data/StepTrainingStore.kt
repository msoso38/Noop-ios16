package com.noop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Evolving personal step model from interactive training sessions.
 *
 * Training modes (user-driven, on-device):
 *  - TAP: user taps once per real step while wearing the strap; we compare WHOOP @57
 *    counter deltas to tap counts → ticks-per-step (the production k factor).
 *  - SHAKE: arm-shake while stationary → motion-noise floor (ticks/sec) so still/fidget
 *    overcount can be down-weighted in [StepMotionCounter] production mode.
 *
 * Never invents daily steps — only updates [ProfileStore.stepTicksPerStep] and an optional
 * noise floor from measured counter deltas vs user labels.
 */
class StepTrainingStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Session(
        val id: Long,
        val mode: String, // tap | shake
        val startedAtMs: Long,
        val endedAtMs: Long,
        val strapTicks: Int,
        val labeledSteps: Int, // 0 for pure shake
        val ticksPerStep: Double?,
        val acceptedTicks: Double = strapTicks.toDouble(),
        val rejectedTicks: Int = 0,
        val acceptedPairs: Int = 0,
        /** Shake-only: estimated noise ticks per second while not walking. */
        val noiseTicksPerSec: Double? = null,
    )

    fun loadSessions(): List<Session> {
        val raw = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Session(
                            id = o.optLong("id"),
                            mode = o.optString("mode"),
                            startedAtMs = o.optLong("startedAtMs"),
                            endedAtMs = o.optLong("endedAtMs"),
                            strapTicks = o.optInt("strapTicks"),
                            labeledSteps = o.optInt("labeledSteps"),
                            ticksPerStep = o.optDouble("ticksPerStep").takeIf { !it.isNaN() && it > 0 },
                            acceptedTicks = o.optDouble("acceptedTicks", o.optDouble("strapTicks")),
                            rejectedTicks = o.optInt("rejectedTicks"),
                            acceptedPairs = o.optInt("acceptedPairs"),
                            noiseTicksPerSec = o.optDouble("noiseTicksPerSec").takeIf { !it.isNaN() && it >= 0 },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addSession(session: Session) {
        val all = loadSessions().toMutableList()
        all.add(session)
        val trimmed = all.takeLast(40)
        val arr = JSONArray()
        for (s in trimmed) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("mode", s.mode)
                    .put("startedAtMs", s.startedAtMs)
                    .put("endedAtMs", s.endedAtMs)
                    .put("strapTicks", s.strapTicks)
                    .put("labeledSteps", s.labeledSteps)
                    .put("ticksPerStep", s.ticksPerStep ?: JSONObject.NULL)
                    .put("acceptedTicks", s.acceptedTicks)
                    .put("rejectedTicks", s.rejectedTicks)
                    .put("acceptedPairs", s.acceptedPairs)
                    .put("noiseTicksPerSec", s.noiseTicksPerSec ?: JSONObject.NULL),
            )
        }
        prefs.edit().putString(KEY_SESSIONS, arr.toString()).apply()
        // Persist derived model so AnalyticsEngine can read without re-walking sessions.
        val model = learnedModel(prefs.getFloat(KEY_TICKS, 1f).toDouble())
        prefs.edit()
            .putFloat(KEY_TICKS, model.ticksPerStep.toFloat())
            .putFloat(KEY_NOISE, model.noiseFloorTicksPerSec.toFloat())
            .putString(KEY_CONF, model.confidence)
            .putInt(KEY_EVIDENCE, model.labeledSteps)
            .apply()
    }

    data class Model(
        val ticksPerStep: Double,
        val labeledSteps: Int,
        val sessions: Int,
        val confidence: String,
        val noiseFloorTicksPerSec: Double = 0.0,
        val shakeSessions: Int = 0,
    )

    /**
     * Weighted, outlier-resistant ticks/step + shake-learned noise floor.
     * Longer labelled walks dominate brief taps (weighted median by labelled step count).
     */
    fun learnedModel(fallback: Double = 1.0): Model {
        val all = loadSessions()
        val candidates = all
            .asReversed()
            .filter { it.mode == "tap" && it.labeledSteps >= 8 && (it.ticksPerStep ?: 0.0) > 0 }
            .take(12)
        val shakes = all.asReversed().filter { it.mode == "shake" && (it.noiseTicksPerSec ?: -1.0) >= 0 }.take(8)
        val noise = if (shakes.isEmpty()) {
            prefs.getFloat(KEY_NOISE, 0f).toDouble()
        } else {
            val vals = shakes.mapNotNull { it.noiseTicksPerSec }.sorted()
            vals[vals.size / 2]
        }.coerceIn(0.0, 2.5)

        if (candidates.isEmpty()) {
            val stored = prefs.getFloat(KEY_TICKS, fallback.toFloat()).toDouble().coerceIn(0.5, 30.0)
            return Model(stored, 0, 0, if (stored != fallback) "Stored" else "Untrained", noise, shakes.size)
        }
        val center = candidates.mapNotNull { it.ticksPerStep }.sorted().let { it[it.size / 2] }
        val deviations = candidates.map { kotlin.math.abs((it.ticksPerStep ?: center) - center) }.sorted()
        val mad = deviations[deviations.size / 2]
        val accepted = candidates.filter {
            val ratio = it.ticksPerStep ?: return@filter false
            if (mad > 0.01) kotlin.math.abs(ratio - center) <= 3 * mad else ratio in center * 0.5..center * 1.5
        }.ifEmpty { candidates }
        val weighted = accepted.sortedBy { it.ticksPerStep }.let { ordered ->
            val half = ordered.sumOf { it.labeledSteps }.toDouble() / 2.0
            var cumulative = 0.0
            ordered.first { session ->
                cumulative += session.labeledSteps
                cumulative >= half
            }.ticksPerStep!!
        }.coerceIn(0.5, 30.0)
        // EMA blend with previous stored k so one odd walk cannot fully replace a strong model.
        val previous = prefs.getFloat(KEY_TICKS, weighted.toFloat()).toDouble()
        val evidence = accepted.sumOf { it.labeledSteps }
        val alpha = when {
            evidence >= 200 -> 0.55
            evidence >= 60 -> 0.40
            else -> 0.28
        }
        val blended = (alpha * weighted + (1.0 - alpha) * previous).coerceIn(0.5, 30.0)
        val confidence = when {
            evidence >= 200 && accepted.size >= 3 -> "High"
            evidence >= 60 -> "Building"
            else -> "Early"
        }
        return Model(blended, evidence, accepted.size, confidence, noise, shakes.size)
    }

    fun blendedTicksPerStep(fallback: Double = 1.0): Double = learnedModel(fallback).ticksPerStep

    fun noiseFloorTicksPerSec(): Double = learnedModel().noiseFloorTicksPerSec

    fun lastTrainAgeDays(): Long? {
        val last = loadSessions().maxByOrNull { it.endedAtMs } ?: return null
        return max(0L, (System.currentTimeMillis() - last.endedAtMs) / 86_400_000L)
    }

    fun needsWeeklyRetrain(): Boolean = (lastTrainAgeDays() ?: 99L) >= 7L

    companion object {
        private const val PREFS = "noop_step_training"
        private const val KEY_SESSIONS = "sessions_json"
        private const val KEY_TICKS = "model_ticks_per_step"
        private const val KEY_NOISE = "model_noise_floor_tps"
        private const val KEY_CONF = "model_confidence"
        private const val KEY_EVIDENCE = "model_evidence_steps"

        @Volatile private var instance: StepTrainingStore? = null
        fun from(context: Context): StepTrainingStore {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: StepTrainingStore(app).also { instance = it }
            }
        }
    }
}
