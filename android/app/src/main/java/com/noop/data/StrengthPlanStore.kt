package com.noop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-authored strength workouts (sets / reps / muscle groups).
 * Local-only; never invents HR or cardio Effort. Volume is note-based until a session is logged.
 */
class StrengthPlanStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Exercise(
        val id: Long,
        val name: String,
        val muscleGroup: String,
        val sets: Int,
        val reps: Int,
        val weightKg: Double? = null,
        val notes: String = "",
    )

    data class Plan(
        val id: Long,
        val name: String,
        val exercises: List<Exercise>,
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = System.currentTimeMillis(),
    )

    fun loadPlans(): List<Plan> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ex = o.optJSONArray("exercises") ?: JSONArray()
                    val exercises = buildList {
                        for (j in 0 until ex.length()) {
                            val e = ex.getJSONObject(j)
                            add(
                                Exercise(
                                    id = e.optLong("id"),
                                    name = e.optString("name"),
                                    muscleGroup = e.optString("muscleGroup", "full"),
                                    sets = e.optInt("sets", 3),
                                    reps = e.optInt("reps", 8),
                                    weightKg = e.optDouble("weightKg").takeIf { !it.isNaN() && it > 0 },
                                    notes = e.optString("notes"),
                                ),
                            )
                        }
                    }
                    add(
                        Plan(
                            id = o.optLong("id"),
                            name = o.optString("name"),
                            exercises = exercises,
                            createdAtMs = o.optLong("createdAtMs"),
                            updatedAtMs = o.optLong("updatedAtMs"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun savePlans(plans: List<Plan>) {
        val arr = JSONArray()
        for (p in plans.takeLast(40)) {
            val ex = JSONArray()
            for (e in p.exercises) {
                ex.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("name", e.name)
                        .put("muscleGroup", e.muscleGroup)
                        .put("sets", e.sets)
                        .put("reps", e.reps)
                        .put("weightKg", e.weightKg ?: JSONObject.NULL)
                        .put("notes", e.notes),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("exercises", ex)
                    .put("createdAtMs", p.createdAtMs)
                    .put("updatedAtMs", p.updatedAtMs),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(plan: Plan) {
        val all = loadPlans().toMutableList()
        val idx = all.indexOfFirst { it.id == plan.id }
        if (idx >= 0) all[idx] = plan.copy(updatedAtMs = System.currentTimeMillis())
        else all.add(plan)
        savePlans(all)
    }

    fun delete(id: Long) {
        savePlans(loadPlans().filterNot { it.id == id })
    }

    companion object {
        private const val PREFS = "noop_strength_plans"
        private const val KEY = "plans_json"
        val MUSCLE_GROUPS = listOf(
            "chest", "back", "shoulders", "arms", "core", "quads", "hamstrings", "glutes", "full",
        )

        @Volatile private var instance: StrengthPlanStore? = null
        fun from(context: Context): StrengthPlanStore {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: StrengthPlanStore(app).also { instance = it }
            }
        }
    }
}
