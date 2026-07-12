package com.noop.analytics

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Period calendar — Flo / PC-style cycle tracking fused with WHOOP physiology.
 *
 * Science basis (awareness only, not contraception / diagnosis):
 * - Distal skin temp rises ~0.3–0.5 °C in luteal vs follicular (biphasic BBT literature;
 *   wearable replications e.g. PMC9375297, Oura period-prediction method notes).
 * - Luteal: RHR tends higher, HRV (RMSSD) tends lower (parasympathetic → sympathetic shift).
 * - Logged period starts + average cycle length remain the primary calendar; WHOOP signals
 *   corroborate phase and refine next-period windows when confidence is solid.
 *
 * WELLNESS / AWARENESS ONLY. Not a medical device. Predictions are WINDOWS, never a single
 * clinical date. Never invents fertility “safe days.”
 */
object PeriodCalendar {

    const val awarenessLine =
        "Awareness only — not contraception, not a fertility predictor, not medical advice. " +
            "You own the log; the strap can only offer optional clues."

    private val DAY = DateTimeFormatter.ISO_LOCAL_DATE

    enum class EventKind(val raw: String, val label: String) {
        PERIOD_START("period_start", "Period start"),
        PERIOD_DAY("period_day", "Period day"),
        PERIOD_END("period_end", "Period end"),
        SPOTTING("spotting", "Spotting"),
        SEX("sex", "Sex"),
        FLOW_LIGHT("flow_light", "Flow light"),
        FLOW_MEDIUM("flow_medium", "Flow medium"),
        FLOW_HEAVY("flow_heavy", "Flow heavy"),
        PAD_CHANGE("pad_change", "Pad / product"),
        CRAMPS("cramps", "Cramps"),
        HEADACHE("headache", "Headache"),
        MOOD_LOW("mood_low", "Low mood"),
        MOOD_HIGH("mood_high", "High mood"),
        ENERGY_LOW("energy_low", "Low energy"),
        ENERGY_HIGH("energy_high", "High energy"),
        SLEEP_POOR("sleep_poor", "Poor sleep"),
        BLOATING("bloating", "Bloating"),
        APPETITE("appetite", "Appetite change"),
        NOTE("note", "Note"),
        /** Auto-suggested from WHOOP temp shift — never a clinical claim. */
        WHOOP_SHIFT_MARKER("whoop_shift", "WHOOP temp shift"),
    }

    data class Event(
        val day: String,
        val kind: EventKind,
        val note: String = "",
        val source: String = "manual", // manual | import | whoop_signal
        val intensity: Int = 0, // 0–3 optional for symptoms
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    enum class CalendarPhase(val label: String, val shortLabel: String) {
        MENSTRUAL("Menstrual", "Period"),
        FOLLICULAR("Follicular", "Follicular"),
        PERI_OVULATORY("Peri-ovulatory", "Mid-cycle"),
        LUTEAL("Luteal", "Luteal"),
        UNKNOWN("Unknown", "Unknown"),
        LEARNING("Learning", "Learning"),
    }

    data class PhaseModifiers(
        val recoveryNote: String,
        val strainNote: String,
        val bmrNote: String,
        val recoveryCapacityFactor: Double,
        val strainCostFactor: Double,
        val bmrFactor: Double,
        val scienceCite: String,
    )

    data class DayCell(
        val day: String,
        val inMonth: Boolean,
        val isToday: Boolean,
        val phase: CalendarPhase?,
        val isPredictedPeriod: Boolean,
        val isPredictedWindow: Boolean,
        val hasPeriod: Boolean,
        val hasSpotting: Boolean,
        val hasSex: Boolean,
        val hasSymptom: Boolean,
        val hasWhoopMarker: Boolean,
        val cycleDay: Int?,
    )

    /** A conditional planning window derived only from historical logged starts. */
    data class ForecastWindow(
        val sequence: Int,
        val earliestDay: String,
        val likelyDay: String,
        val latestDay: String,
    )

    data class Snapshot(
        val enabled: Boolean,
        val phase: CalendarPhase,
        val cycleDay: Int?,
        val avgCycleLength: Int?,
        val avgPeriodLength: Int?,
        val lastPeriodStart: String?,
        /** Number of past logged starts eligible as planning evidence. */
        val loggedStartCount: Int,
        val nextPeriodEarliest: String?,
        val nextPeriodLatest: String?,
        val nextPeriodLikely: String?,
        val daysUntilLikely: Int?,
        val daysUntilWindow: Int?,
        val reminderNightBefore: Boolean,
        val reminderMorningOf: Boolean,
        val padReminderHours: Int,
        val modifiers: PhaseModifiers,
        val enginePhase: CyclePhaseEngine.Phase?,
        val engineNote: String?,
        val whoopConfidence: CyclePhaseEngine.Confidence?,
        val whoopLearning: Boolean,
        /** Long-range estimates are withheld until four logged starts establish three intervals. */
        val forecastWindows: List<ForecastWindow>,
        val note: String,
        val scienceNote: String,
    )

    data class Prefs(
        val enabled: Boolean = false,
        val avgCycleLengthOverride: Int? = null,
        val avgPeriodLengthOverride: Int? = null,
        val remindersEnabled: Boolean = true,
        val nightBeforeReminder: Boolean = true,
        val morningOfReminder: Boolean = true,
        val padReminderHours: Int = 4,
        val whoopLearningEnabled: Boolean = true,
        /** True after the multi-step Cycle first-run (or when an import already seeded starts). */
        val onboardingComplete: Boolean = false,
    )

    /**
     * Cycle-length model inspired by self-tracking literature (Li/Urteaga/Elhadad):
     * observed gaps mix physiology with adherence artifacts (skipped logs, double-taps).
     * We keep only physiological gaps, down-weight ancient history, and widen uncertainty
     * when evidence is thin — not a full generative model, but the same separation of concerns.
     */
    data class CycleLengthModel(
        val meanDays: Int,
        val sdDays: Double,
        val validGapCount: Int,
        val skippedArtifactCount: Int,
    )

    fun periodStarts(events: List<Event>): List<String> =
        events.filter { it.kind == EventKind.PERIOD_START }
            .map { it.day }
            .distinct()
            .sorted()

    /**
     * Forecast / cycle-length evidence: collapse near period_start noise (common after .pc
     * imports) so short gaps don't flood amber windows or pull mean cycle length to 21d.
     * Display still sees every logged start via [periodStarts]; planning uses this list.
     */
    fun planningPeriodStarts(events: List<Event>, minGapDays: Int = 14): List<String> {
        val raw = periodStarts(events).mapNotNull(::parse)
        if (raw.size <= 1) return raw.map { it.format(DAY) }
        val out = ArrayList<LocalDate>()
        var prev: LocalDate? = null
        for (d in raw) {
            if (prev == null || ChronoUnit.DAYS.between(prev, d) >= minGapDays) {
                out.add(d)
                prev = d
            }
            // else: keep earlier start of the cluster (true onset), drop later near-noise
        }
        return out.map { it.format(DAY) }
    }

    /** Observed day gaps between consecutive logged starts (unsorted filter applied by callers). */
    fun rawCycleGaps(starts: List<String>): List<Int> {
        val dates = starts.mapNotNull(::parse).distinct().sorted()
        if (dates.size < 2) return emptyList()
        return dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
    }

    /**
     * Physiological gaps are 21–40d. Gaps ~2× a running mean (42–80d) are treated as a skipped
     * log (self-tracking artifact), not a 60-day cycle — matching the papers' latent-skip idea.
     */
    fun cycleLengthModel(starts: List<String>, fallback: Int = 28): CycleLengthModel {
        val raw = rawCycleGaps(starts)
        if (raw.isEmpty()) {
            val f = fallback.coerceIn(21, 40)
            return CycleLengthModel(f, 4.0, 0, 0)
        }
        val phys = mutableListOf<Int>()
        var skipped = 0
        var running = fallback.toDouble()
        for (g in raw) {
            when {
                g in 21..40 -> {
                    phys.add(g)
                    // Online mean of physiological gaps only.
                    running = if (phys.size == 1) g.toDouble() else (running * 0.7 + g * 0.3)
                }
                g in 42..80 -> {
                    val half = g / 2.0
                    if (kotlin.math.abs(half - running) <= 8.0 || half in 21.0..40.0) {
                        // Likely one missed period start between two real ones.
                        skipped++
                        val repaired = half.roundToInt().coerceIn(21, 40)
                        phys.add(repaired)
                        running = running * 0.7 + repaired * 0.3
                    }
                    // else: discard as non-cycle noise (travel, app gaps, .pc mining junk)
                }
                // <21 or >80: adherence / mining noise — ignore for length
            }
        }
        if (phys.isEmpty()) {
            val f = fallback.coerceIn(21, 40)
            return CycleLengthModel(f, 5.0, 0, skipped)
        }
        // Exponential weights: most recent gaps dominate (recency / local stationarity).
        val n = phys.size
        var wSum = 0.0
        var xSum = 0.0
        for (i in phys.indices) {
            val age = (n - 1 - i).toDouble()
            val w = kotlin.math.exp(-age / 3.0) // ~3-cycle half-life
            wSum += w
            xSum += w * phys[i]
        }
        val mean = (xSum / wSum).roundToInt().coerceIn(21, 40)
        val recent = phys.takeLast(minOf(6, phys.size))
        val sd = if (recent.size >= 2) {
            val m = recent.average()
            sqrt(recent.sumOf { (it - m) * (it - m) } / (recent.size - 1))
        } else {
            4.0
        }
        // Thin evidence → slightly wider calibrated window (MLHC calibration mindset).
        val calibratedSd = when {
            phys.size >= 6 -> sd
            phys.size >= 3 -> maxOf(sd, 3.5)
            else -> maxOf(sd, 5.0)
        }
        return CycleLengthModel(mean, calibratedSd, phys.size, skipped)
    }

    fun averageCycleLength(starts: List<String>, fallback: Int = 28): Int =
        cycleLengthModel(starts, fallback).meanDays

    fun averagePeriodLength(events: List<Event>, fallback: Int = 5): Int {
        val byStart = periodStarts(events)
        if (byStart.isEmpty()) return fallback.coerceIn(2, 10)
        val lengths = mutableListOf<Int>()
        for (start in byStart) {
            val s = parse(start) ?: continue
            var len = 1
            for (d in 1..10) {
                val day = s.plusDays(d.toLong()).format(DAY)
                val hasBleed = events.any {
                    it.day == day && it.kind in setOf(
                        EventKind.PERIOD_DAY, EventKind.FLOW_LIGHT, EventKind.FLOW_MEDIUM,
                        EventKind.FLOW_HEAVY, EventKind.SPOTTING,
                    )
                }
                if (hasBleed) len++ else break
            }
            // Prefer explicit period_end if present
            events.firstOrNull { it.kind == EventKind.PERIOD_END && it.day >= start }?.let { end ->
                daysBetween(start, end.day)?.let { if (it in 1..10) lengths.add(it + 1) }
            } ?: lengths.add(len)
        }
        if (lengths.isEmpty()) return fallback.coerceIn(2, 10)
        return lengths.average().roundToInt().coerceIn(2, 10)
    }

    /**
     * Advance [candidate] by [cycleLen] until it is not before [today].
     * When a logged start is weeks old and the user hasn't tapped the next bleed yet, the calendar
     * must still project the *upcoming* cycle(s) — otherwise August stays blank after a June start.
     */
    fun rollForwardLikely(today: LocalDate, candidate: LocalDate, cycleLen: Int): LocalDate {
        val step = cycleLen.coerceIn(21, 40).toLong()
        var next = candidate
        var guard = 0
        while (next.isBefore(today) && guard < 36) {
            next = next.plusDays(step)
            guard++
        }
        return next
    }

    /**
     * Conditional planning estimates through [horizon]. Only logged starts qualify as evidence;
     * windows widen with observed variation and forecast horizon (calibrated uncertainty).
     */
    fun longRangeForecastWindows(
        today: LocalDate,
        starts: List<String>,
        firstLikely: LocalDate,
        horizon: LocalDate = today.plusMonths(12),
        cycleLenHint: Int? = null,
    ): List<ForecastWindow> {
        val dates = starts.mapNotNull(::parse).distinct().sorted()
        if (dates.size < 2) return emptyList()
        val model = cycleLengthModel(starts, cycleLenHint ?: 28)
        val mean = model.meanDays.toDouble()
        val standardDeviation = model.sdDays
        // Roll a stale firstLikely forward instead of dropping the whole year view.
        val startLikely = rollForwardLikely(today, firstLikely, mean.roundToInt())
        if (startLikely.isBefore(today) || startLikely.isAfter(horizon)) return emptyList()

        val windows = ArrayList<ForecastWindow>()
        var sequence = 1
        while (true) {
            val likely = startLikely.plusDays((mean * (sequence - 1)).roundToInt().toLong())
            if (likely.isAfter(horizon)) break
            // Never advertise a past likely day as an upcoming window.
            if (!likely.isBefore(today)) {
                // 95%-ish half-width growing with horizon; floor rises when evidence is thin.
                // Cap at ±4 so one noisy .pc import cannot amber-wash half the month.
                val halfWidth = maxOf(
                    if (model.validGapCount >= 3) 2 else 3,
                    ceil(1.96 * standardDeviation * sqrt(sequence.toDouble())).toInt(),
                ).coerceAtMost(4)
                windows.add(
                    ForecastWindow(
                        sequence = sequence,
                        earliestDay = maxOf(likely.minusDays(halfWidth.toLong()), today).format(DAY),
                        likelyDay = likely.format(DAY),
                        latestDay = likely.plusDays(halfWidth.toLong()).format(DAY),
                    ),
                )
            }
            sequence++
            if (sequence > 24) break
        }
        return windows
    }

    fun evaluate(
        today: LocalDate = LocalDate.now(),
        events: List<Event>,
        prefs: Prefs,
        engine: CyclePhaseEngine.Result? = null,
    ): Snapshot {
        val scienceNote =
            "Your log is the calendar truth. Optional strap clues (skin temp, resting HR, HRV) follow " +
                "published wearable BBT/autonomic patterns — they never invent a period day."

        if (!prefs.enabled) {
            return Snapshot(
                enabled = false,
                phase = CalendarPhase.UNKNOWN,
                cycleDay = null,
                avgCycleLength = null,
                avgPeriodLength = null,
                lastPeriodStart = null,
                loggedStartCount = 0,
                nextPeriodEarliest = null,
                nextPeriodLatest = null,
                nextPeriodLikely = null,
                daysUntilLikely = null,
                daysUntilWindow = null,
                reminderNightBefore = false,
                reminderMorningOf = false,
                padReminderHours = prefs.padReminderHours,
                modifiers = neutralModifiers(),
                enginePhase = engine?.phase,
                engineNote = engine?.note,
                whoopConfidence = engine?.confidence,
                whoopLearning = false,
                forecastWindows = emptyList(),
                note = "Turn on the cycle calendar when you want to log.",
                scienceNote = scienceNote,
            )
        }

        // Future starts are plans/predictions, not evidence that a bleed has begun. They stay visible in
        // the calendar, but must never force today's phase to day one.
        val historicalEvents = events.filter { parse(it.day)?.let { day -> !day.isAfter(today) } == true }
        // Planning uses de-noised starts; raw logs stay on the grid as hasPeriod.
        val starts = planningPeriodStarts(historicalEvents)
        var avgCycle = prefs.avgCycleLengthOverride
            ?: averageCycleLength(starts, engine?.cycleLengthDays ?: 28)
        // When WHOOP cycle length is solid and close to log-based length, blend (science corroboration).
        if (prefs.whoopLearningEnabled && engine?.confidence == CyclePhaseEngine.Confidence.SOLID) {
            engine.cycleLengthDays?.let { wLen ->
                if (wLen in 21..40) {
                    avgCycle = ((avgCycle + wLen) / 2.0).roundToInt().coerceIn(21, 40)
                }
            }
        }
        val avgPeriod = prefs.avgPeriodLengthOverride ?: averagePeriodLength(historicalEvents, 5)
        val lastStart = starts.lastOrNull()

        if (lastStart == null) {
            return Snapshot(
                enabled = true,
                phase = CalendarPhase.LEARNING,
                cycleDay = null,
                avgCycleLength = avgCycle,
                avgPeriodLength = avgPeriod,
                lastPeriodStart = null,
                loggedStartCount = 0,
                nextPeriodEarliest = null,
                nextPeriodLatest = null,
                nextPeriodLikely = null,
                daysUntilLikely = null,
                daysUntilWindow = null,
                reminderNightBefore = false,
                reminderMorningOf = false,
                padReminderHours = prefs.padReminderHours,
                modifiers = neutralModifiers(),
                enginePhase = engine?.phase,
                engineNote = engine?.note,
                whoopConfidence = engine?.confidence,
                whoopLearning = prefs.whoopLearningEnabled,
                forecastWindows = emptyList(),
                note = "Log your last period start (or import Flo/PC CSV). Wear overnight so WHOOP temp can corroborate phase.",
                scienceNote = scienceNote,
            )
        }

        val last = parse(lastStart)!!
        val cycleDay = (ChronoUnit.DAYS.between(last, today).toInt() + 1).coerceAtLeast(1)
        var next = last.plusDays(avgCycle.toLong())
        // WHOOP shift markers can nudge the next-period likely day when solid (not override logs).
        if (prefs.whoopLearningEnabled && engine?.confidence == CyclePhaseEngine.Confidence.SOLID) {
            engine.nextPeriodWindow?.let { w ->
                val e = parse(w.earliestDay)
                val l = parse(w.latestDay)
                if (e != null && l != null) {
                    val mid = e.plusDays(ChronoUnit.DAYS.between(e, l) / 2)
                    // Only nudge if within ±5 days of calendar prediction
                    if (kotlin.math.abs(ChronoUnit.DAYS.between(next, mid)) <= 5) {
                        next = mid
                    }
                }
            }
        }
        // Missed / unlogged cycles: keep projecting into the future (Jul → Aug → …).
        next = rollForwardLikely(today, next, avgCycle)
        // Hard invariant: "next" is never a past day. Logged starts stay in lastPeriodStart only.
        if (next.isBefore(today)) next = today
        val earliest = maxOf(next.minusDays(2), today)
        val latest = next.plusDays(2)
        val daysUntilLikely = ChronoUnit.DAYS.between(today, next).toInt().coerceAtLeast(0)
        val daysUntilWindow = ChronoUnit.DAYS.between(today, earliest).toInt().coerceAtLeast(0)
        val forecastWindows = longRangeForecastWindows(
            today = today,
            starts = starts,
            firstLikely = next,
            cycleLenHint = avgCycle,
        ).filter { parse(it.likelyDay)?.let { d -> !d.isBefore(today) } == true }

        val calendarPhase = phaseForCycleDay(cycleDay, avgPeriod, avgCycle)
        val fusedPhase = fusePhase(calendarPhase, engine, prefs.whoopLearningEnabled)
        val note = buildString {
            append(phaseNote(fusedPhase, cycleDay, avgCycle))
            if (engine != null && engine.confidence != CyclePhaseEngine.Confidence.LEARNING) {
                append(" WHOOP signals read ${engine.phase.raw} (${engine.confidence.raw}).")
            }
        }

        val nightBefore = prefs.remindersEnabled && prefs.nightBeforeReminder && daysUntilLikely == 1
        val morningOf = prefs.remindersEnabled && prefs.morningOfReminder &&
            (today == next || daysUntilLikely == 0)

        return Snapshot(
            enabled = true,
            phase = fusedPhase,
            cycleDay = cycleDay,
            avgCycleLength = avgCycle,
            avgPeriodLength = avgPeriod,
            lastPeriodStart = lastStart,
            loggedStartCount = starts.size,
            nextPeriodEarliest = earliest.format(DAY),
            nextPeriodLatest = latest.format(DAY),
            nextPeriodLikely = next.format(DAY),
            daysUntilLikely = daysUntilLikely,
            daysUntilWindow = daysUntilWindow,
            reminderNightBefore = nightBefore,
            reminderMorningOf = morningOf,
            padReminderHours = prefs.padReminderHours,
            modifiers = modifiersFor(fusedPhase),
            enginePhase = engine?.phase,
            engineNote = engine?.note,
            whoopConfidence = engine?.confidence,
            whoopLearning = prefs.whoopLearningEnabled,
            forecastWindows = forecastWindows,
            note = note,
            scienceNote = scienceNote,
        )
    }

    /** Month grid for Flo/PC-style calendar (Sun–Sat or Mon–Sun via firstDayOfWeek). */
    fun monthGrid(
        yearMonth: YearMonth,
        today: LocalDate = LocalDate.now(),
        events: List<Event>,
        snap: Snapshot,
        firstDayOfWeek: java.time.DayOfWeek = java.time.DayOfWeek.SUNDAY,
    ): List<DayCell> {
        val first = yearMonth.atDay(1)
        val startOffset = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val gridStart = first.minusDays(startOffset.toLong())
        val byDay = events.groupBy { it.day }
        val cells = ArrayList<DayCell>(42)
        for (i in 0 until 42) {
            val d = gridStart.plusDays(i.toLong())
            val key = d.format(DAY)
            val dayEvents = byDay[key].orEmpty()
            val hasPeriod = dayEvents.any {
                it.kind in setOf(
                    EventKind.PERIOD_START, EventKind.PERIOD_DAY, EventKind.FLOW_LIGHT,
                    EventKind.FLOW_MEDIUM, EventKind.FLOW_HEAVY,
                )
            }
            val hasSpot = dayEvents.any { it.kind == EventKind.SPOTTING }
            val hasSex = dayEvents.any { it.kind == EventKind.SEX }
            val hasSymptom = dayEvents.any {
                it.kind in setOf(
                    EventKind.CRAMPS, EventKind.HEADACHE, EventKind.MOOD_LOW, EventKind.MOOD_HIGH,
                    EventKind.ENERGY_LOW, EventKind.ENERGY_HIGH, EventKind.SLEEP_POOR, EventKind.BLOATING,
                    EventKind.APPETITE,
                )
            }
            val hasWhoop = dayEvents.any { it.kind == EventKind.WHOOP_SHIFT_MARKER }
            val cycleDay = snap.lastPeriodStart?.let { ls ->
                val start = parse(ls) ?: return@let null
                val cd = ChronoUnit.DAYS.between(start, d).toInt() + 1
                if (cd in 1..(snap.avgCycleLength ?: 28) + 7) cd else null
            }
            val phase = cycleDay?.let {
                phaseForCycleDay(it, snap.avgPeriodLength ?: 5, snap.avgCycleLength ?: 28)
            }
            val forecastWindows = snap.forecastWindows.ifEmpty {
                if (snap.nextPeriodLikely == null || snap.nextPeriodEarliest == null || snap.nextPeriodLatest == null) {
                    emptyList()
                } else {
                    listOf(ForecastWindow(1, snap.nextPeriodEarliest, snap.nextPeriodLikely, snap.nextPeriodLatest))
                }
            }
            // Restrained paint: only windows that intersect THIS month (not every horizon
            // sequence). Stops multi-cycle amber/rose wash when SD is noisy after .pc import.
            val monthStart = yearMonth.atDay(1).format(DAY)
            val monthEnd = yearMonth.atEndOfMonth().format(DAY)
            val paintWindows = forecastWindows.filter {
                it.earliestDay <= monthEnd && it.latestDay >= monthStart
            }
            val inPredWindow = paintWindows.any { key in it.earliestDay..it.latestDay }
            val isLikely = paintWindows.any { forecast ->
                key >= forecast.likelyDay &&
                    daysBetween(forecast.likelyDay, key)?.let { it < (snap.avgPeriodLength ?: 5) } == true
            }

            cells.add(
                DayCell(
                    day = key,
                    inMonth = d.month == yearMonth.month,
                    isToday = d == today,
                    phase = phase,
                    isPredictedPeriod = isLikely && !hasPeriod,
                    isPredictedWindow = inPredWindow && !hasPeriod,
                    hasPeriod = hasPeriod,
                    hasSpotting = hasSpot,
                    hasSex = hasSex,
                    hasSymptom = hasSymptom,
                    hasWhoopMarker = hasWhoop,
                    cycleDay = cycleDay,
                ),
            )
        }
        return cells
    }

    /**
     * When WHOOP CyclePhaseEngine reports solid shift markers, emit optional log events
     * (source=whoop_signal) so the calendar can show corroboration without inventing bleed days.
     */
    fun whoopSuggestedEvents(engine: CyclePhaseEngine.Result?): List<Event> {
        if (engine == null) return emptyList()
        if (engine.confidence == CyclePhaseEngine.Confidence.LEARNING) return emptyList()
        return engine.shiftMarkers.map {
            Event(
                day = it.day,
                kind = EventKind.WHOOP_SHIFT_MARKER,
                note = "Inferred from nightly skin-temp/RHR/HRV shift (not a period log)",
                source = "whoop_signal",
            )
        }
    }

    fun phaseForCycleDay(cycleDay: Int, periodLen: Int, cycleLen: Int): CalendarPhase {
        val p = periodLen.coerceIn(2, 10)
        val c = cycleLen.coerceIn(21, 40)
        val ovMid = (c / 2.0).roundToInt()
        return when {
            cycleDay <= p -> CalendarPhase.MENSTRUAL
            cycleDay in (ovMid - 2)..(ovMid + 2) -> CalendarPhase.PERI_OVULATORY
            cycleDay < ovMid - 2 -> CalendarPhase.FOLLICULAR
            cycleDay <= c -> CalendarPhase.LUTEAL
            // A late prediction is not an observed period. Leave the phase unknown until a new start is
            // logged instead of showing a perpetual menstrual phase.
            else -> CalendarPhase.UNKNOWN
        }
    }

    private fun fusePhase(
        calendar: CalendarPhase,
        engine: CyclePhaseEngine.Result?,
        whoopLearning: Boolean,
    ): CalendarPhase {
        if (!whoopLearning || engine == null) return calendar
        if (engine.confidence != CyclePhaseEngine.Confidence.SOLID) return calendar
        // Never override menstrual if user logged bleed recently via calendar day math.
        if (calendar == CalendarPhase.MENSTRUAL) return calendar
        return when (engine.phase) {
            CyclePhaseEngine.Phase.LUTEAL -> CalendarPhase.LUTEAL
            CyclePhaseEngine.Phase.FOLLICULAR -> CalendarPhase.FOLLICULAR
            CyclePhaseEngine.Phase.PERI_OVULATORY -> CalendarPhase.PERI_OVULATORY
            else -> calendar
        }
    }

    fun modifiersFor(phase: CalendarPhase): PhaseModifiers = when (phase) {
        CalendarPhase.MENSTRUAL -> PhaseModifiers(
            recoveryNote = "Menstrual window: many people feel recovery is harder; listen to symptoms.",
            strainNote = "Consider slightly lower intensity if cramps or fatigue show up.",
            bmrNote = "Metabolic demand is often near baseline early in bleed.",
            recoveryCapacityFactor = 0.97,
            strainCostFactor = 1.03,
            bmrFactor = 0.99,
            scienceCite = "Symptom-linked performance variation is individual; factors are awareness context only.",
        )
        CalendarPhase.FOLLICULAR -> PhaseModifiers(
            recoveryNote = "Follicular: recovery often feels easier (lower RHR / higher HRV tendency in literature).",
            strainNote = "Higher-intensity work often feels more available in this window.",
            bmrNote = "BMR often near personal baseline in the follicular half.",
            recoveryCapacityFactor = 1.03,
            strainCostFactor = 0.97,
            bmrFactor = 1.00,
            scienceCite = "Autonomic / BBT literature: follicular often lower RHR, higher HRV.",
        )
        CalendarPhase.PERI_OVULATORY -> PhaseModifiers(
            recoveryNote = "Mid-cycle shift: energy can feel peaky — watch sleep and temperature.",
            strainNote = "High efforts may feel strong; still respect measured HR.",
            bmrNote = "Metabolic demand often rising toward luteal.",
            recoveryCapacityFactor = 1.02,
            strainCostFactor = 1.00,
            bmrFactor = 1.02,
            scienceCite = "Temp nadir / peri-ovulatory transition described in biphasic BBT research.",
        )
        CalendarPhase.LUTEAL -> PhaseModifiers(
            recoveryNote = "Luteal: recovery often costlier; RHR may run higher, HRV lower (group-level findings).",
            strainNote = "Same effort can cost more — soft strain context elevated.",
            bmrNote = "BMR often slightly higher luteal for many people.",
            recoveryCapacityFactor = 0.94,
            strainCostFactor = 1.06,
            bmrFactor = 1.05,
            scienceCite = "Luteal RHR↑ HRV↓ skin-temp↑ patterns in wearable + clinical autonomic studies.",
        )
        else -> neutralModifiers()
    }

    private fun neutralModifiers() = PhaseModifiers(
        recoveryNote = "No cycle phase applied yet.",
        strainNote = "No cycle phase applied yet.",
        bmrNote = "No cycle phase applied yet.",
        recoveryCapacityFactor = 1.0,
        strainCostFactor = 1.0,
        bmrFactor = 1.0,
        scienceCite = "",
    )

    private fun phaseNote(phase: CalendarPhase, cycleDay: Int, cycleLen: Int): String = when (phase) {
        CalendarPhase.MENSTRUAL -> "Cycle day $cycleDay — menstrual / bleed window (from your logs)."
        CalendarPhase.FOLLICULAR -> "Cycle day $cycleDay of ~$cycleLen — follicular range."
        CalendarPhase.PERI_OVULATORY -> "Cycle day $cycleDay of ~$cycleLen — mid-cycle window."
        CalendarPhase.LUTEAL -> "Cycle day $cycleDay of ~$cycleLen — luteal range."
        CalendarPhase.LEARNING -> "Learning your cycle — log starts and wear overnight."
        CalendarPhase.UNKNOWN -> "Phase unclear — keep logging."
    }

    fun parseImportCsv(text: String): List<Event> {
        val out = ArrayList<Event>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val lower = line.lowercase()
            if (lower.startsWith("date") || lower.startsWith("day") || lower.startsWith("start")) continue
            val parts = line.split(',', ';', '\t').map { it.trim().trim('"') }
            if (parts.isEmpty()) continue
            val day = normalizeDay(parts[0]) ?: continue
            val typeRaw = parts.getOrNull(1)?.lowercase().orEmpty()
            val kind = when {
                typeRaw in setOf("period", "period_start", "start", "menses", "period start") -> EventKind.PERIOD_START
                typeRaw in setOf("period_end", "end") -> EventKind.PERIOD_END
                typeRaw in setOf("period_day", "bleed") -> EventKind.PERIOD_DAY
                typeRaw in setOf("spotting", "spot") -> EventKind.SPOTTING
                typeRaw in setOf("sex", "intercourse") -> EventKind.SEX
                typeRaw in setOf("light", "flow_light") -> EventKind.FLOW_LIGHT
                typeRaw in setOf("medium", "flow_medium") -> EventKind.FLOW_MEDIUM
                typeRaw in setOf("heavy", "flow_heavy") -> EventKind.FLOW_HEAVY
                typeRaw in setOf("pad", "pad_change", "product") -> EventKind.PAD_CHANGE
                typeRaw in setOf("cramps", "pain") -> EventKind.CRAMPS
                typeRaw in setOf("headache") -> EventKind.HEADACHE
                typeRaw.contains("mood") -> EventKind.MOOD_LOW
                typeRaw.contains("energy") -> EventKind.ENERGY_LOW
                typeRaw.contains("bloat") -> EventKind.BLOATING
                else -> EventKind.NOTE
            }
            val note = parts.drop(2).joinToString(" ").ifBlank { parts.getOrNull(2).orEmpty() }
            out.add(Event(day = day, kind = kind, note = note, source = "import"))
        }
        return out
    }

    fun parse(day: String): LocalDate? = runCatching { LocalDate.parse(day, DAY) }.getOrNull()

    fun daysBetween(a: String, b: String): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        return ChronoUnit.DAYS.between(x, y).toInt()
    }

    private fun normalizeDay(raw: String): String? = runCatching {
        val t = raw.trim()
        parse(t)?.let { return@runCatching it.format(DAY) }
        val slash = t.split('/', '-')
        if (slash.size != 3) return@runCatching null
        val a = slash[0].toIntOrNull() ?: return@runCatching null
        val b = slash[1].toIntOrNull() ?: return@runCatching null
        val c = slash[2].toIntOrNull() ?: return@runCatching null
        when {
            a > 31 -> LocalDate.of(a, b, c).format(DAY)
            c > 31 -> if (a > 12) LocalDate.of(c, b, a).format(DAY) else LocalDate.of(c, a, b).format(DAY)
            else -> null
        }
    }.getOrNull()
}
