package com.noop.data

import android.content.Context
import com.noop.analytics.PeriodCalendar
import org.json.JSONArray
import org.json.JSONObject

/** On-device period / cycle event store. Privacy-first SharedPreferences only. */
class PeriodCalendarStore private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadEvents(): List<PeriodCalendar.Event> {
        val raw = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val kind = PeriodCalendar.EventKind.entries
                        .firstOrNull { it.raw == o.optString("kind") }
                        ?: PeriodCalendar.EventKind.NOTE
                    add(
                        PeriodCalendar.Event(
                            day = o.getString("day"),
                            kind = kind,
                            note = o.optString("note"),
                            source = o.optString("source", "manual"),
                            intensity = o.optInt("intensity", 0),
                            createdAtMs = o.optLong("createdAtMs", 0L),
                        ),
                    )
                }
            }.sortedWith(compareBy({ it.day }, { it.createdAtMs }))
        }.getOrDefault(emptyList())
    }

    fun saveEvents(events: List<PeriodCalendar.Event>) {
        val arr = JSONArray()
        for (e in events.sortedWith(compareBy({ it.day }, { it.createdAtMs }))) {
            arr.put(
                JSONObject()
                    .put("day", e.day)
                    .put("kind", e.kind.raw)
                    .put("note", e.note)
                    .put("source", e.source)
                    .put("intensity", e.intensity)
                    .put("createdAtMs", e.createdAtMs),
            )
        }
        prefs.edit().putString(KEY_EVENTS, arr.toString()).apply()
    }

    fun addEvent(event: PeriodCalendar.Event) {
        val next = loadEvents().toMutableList()
        next.add(event)
        saveEvents(next)
    }

    /** Remove one event by day + kind + createdAtMs (stable identity). */
    fun removeEvent(day: String, kind: PeriodCalendar.EventKind, createdAtMs: Long) {
        val next = loadEvents().filterNot {
            it.day == day && it.kind == kind && it.createdAtMs == createdAtMs
        }
        saveEvents(next)
    }

    /** Clear every log on [day] (user asked to “remove dates”). */
    fun removeAllForDay(day: String) {
        saveEvents(loadEvents().filterNot { it.day == day })
    }

    /** Toggle period start on [day]: remove if present, else add. */
    fun togglePeriodStart(day: String): Boolean {
        val existing = loadEvents()
        val starts = existing.filter {
            it.day == day && it.kind == PeriodCalendar.EventKind.PERIOD_START
        }
        return if (starts.isNotEmpty()) {
            saveEvents(existing.filterNot {
                it.day == day && it.kind == PeriodCalendar.EventKind.PERIOD_START
            })
            false
        } else {
            addEvent(PeriodCalendar.Event(day = day, kind = PeriodCalendar.EventKind.PERIOD_START))
            true
        }
    }

    fun eventsForDay(day: String): List<PeriodCalendar.Event> =
        loadEvents().filter { it.day == day }

    fun mergeImport(imported: List<PeriodCalendar.Event>) {
        if (imported.isEmpty()) return
        val existing = loadEvents().toMutableList()
        val keys = existing.map { "${it.day}|${it.kind.raw}|${it.note}" }.toMutableSet()
        for (e in imported) {
            val k = "${e.day}|${e.kind.raw}|${e.note}"
            if (k in keys) continue
            existing.add(e)
            keys.add(k)
        }
        saveEvents(existing)
        // One-shot cleanup of near-duplicate pc_import starts so the calendar does not
        // keep mid-bleed noise as separate period_start rows after re-import.
        refineNearPeriodStarts()
    }

    /**
     * Collapse period_start rows closer than 14d (keep earliest). Manual logs are kept;
     * only densifies when ≥8 starts (typical noisy .pc). Idempotent.
     * Also runs when the user already imported before this cleanup shipped.
     */
    fun refineNearPeriodStarts(minGapDays: Int = 14): Int {
        val all = loadEvents()
        val starts = all.filter { it.kind == PeriodCalendar.EventKind.PERIOD_START }
            .sortedBy { it.day }
        if (starts.size < 8) return 0
        val keepDays = PeriodCalendar.planningPeriodStarts(starts, minGapDays).toHashSet()
        if (keepDays.size >= starts.map { it.day }.toSet().size) return 0
        val drop = starts.filter { it.day !in keepDays }
        if (drop.isEmpty()) return 0
        val dropKeys = drop.map { "${it.day}|${it.createdAtMs}" }.toHashSet()
        val next = all.filterNot {
            it.kind == PeriodCalendar.EventKind.PERIOD_START &&
                "${it.day}|${it.createdAtMs}" in dropKeys
        }
        saveEvents(next)
        return drop.size
    }

    /** Merge WHOOP shift markers without duplicating the same day. */
    fun mergeWhoopSignals(markers: List<PeriodCalendar.Event>) {
        if (markers.isEmpty()) return
        val existing = loadEvents().toMutableList()
        val whoopDays = existing.filter { it.kind == PeriodCalendar.EventKind.WHOOP_SHIFT_MARKER }
            .map { it.day }.toMutableSet()
        var changed = false
        for (m in markers) {
            if (m.day in whoopDays) continue
            existing.add(m)
            whoopDays.add(m.day)
            changed = true
        }
        if (changed) saveEvents(existing)
    }

    fun loadPrefs(): PeriodCalendar.Prefs = PeriodCalendar.Prefs(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        avgCycleLengthOverride = prefs.getInt(KEY_CYCLE_LEN, 0).takeIf { it in 21..40 },
        avgPeriodLengthOverride = prefs.getInt(KEY_PERIOD_LEN, 0).takeIf { it in 2..10 },
        remindersEnabled = prefs.getBoolean(KEY_REMINDERS, true),
        nightBeforeReminder = prefs.getBoolean(KEY_NIGHT_BEFORE, true),
        morningOfReminder = prefs.getBoolean(KEY_MORNING_OF, true),
        padReminderHours = prefs.getInt(KEY_PAD_HOURS, 4).coerceIn(1, 12),
        whoopLearningEnabled = prefs.getBoolean(KEY_WHOOP_LEARN, true),
        onboardingComplete = prefs.getBoolean(KEY_ONBOARDING, false),
    )

    fun savePrefs(p: PeriodCalendar.Prefs) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, p.enabled)
            .putInt(KEY_CYCLE_LEN, p.avgCycleLengthOverride ?: 0)
            .putInt(KEY_PERIOD_LEN, p.avgPeriodLengthOverride ?: 0)
            .putBoolean(KEY_REMINDERS, p.remindersEnabled)
            .putBoolean(KEY_NIGHT_BEFORE, p.nightBeforeReminder)
            .putBoolean(KEY_MORNING_OF, p.morningOfReminder)
            .putInt(KEY_PAD_HOURS, p.padReminderHours)
            .putBoolean(KEY_WHOOP_LEARN, p.whoopLearningEnabled)
            .putBoolean(KEY_ONBOARDING, p.onboardingComplete)
            .apply()
    }

    fun periodStartDays(): List<String> = PeriodCalendar.periodStarts(loadEvents())

    companion object {
        private const val PREFS = "noop_period_calendar"
        private const val KEY_EVENTS = "events_json"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CYCLE_LEN = "avg_cycle_len"
        private const val KEY_PERIOD_LEN = "avg_period_len"
        private const val KEY_REMINDERS = "reminders"
        private const val KEY_NIGHT_BEFORE = "night_before"
        private const val KEY_MORNING_OF = "morning_of"
        private const val KEY_PAD_HOURS = "pad_hours"
        private const val KEY_WHOOP_LEARN = "whoop_learn"
        private const val KEY_ONBOARDING = "onboarding_complete"

        @Volatile private var instance: PeriodCalendarStore? = null

        fun from(context: Context): PeriodCalendarStore {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: PeriodCalendarStore(app).also { instance = it }
            }
        }
    }
}
