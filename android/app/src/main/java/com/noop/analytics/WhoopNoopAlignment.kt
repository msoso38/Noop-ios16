package com.noop.analytics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * WHOOP ↔ NOOP **alignment model** — transparent agreement scoring, not a clinical claim.
 *
 * ## Architecture (v0.3)
 *
 * ```
 * Open BLE (2A37 HR/RR, 2A19 batt, open notify)
 *        │
 *        ▼
 * Feature bank (Room: HR, RR, sleep windows, steps)
 *        │
 *        ├──► RecoveryScorer  → Charge 0–100     (W_HRV 0.55, RHR 0.20, sleep 0.15, …)
 *        ├──► StrainScorer    → Effort 0–100     (TRIMP → log 0–100; WHOOP-like 0–21 maps ×100/21)
 *        ├──► Rest / sleep performance            (existing RestScorer path)
 *        └──► StressModel     → 0–3  (display ×100/3 for compare)
 *                │
 *                ▼
 *        Alignment layer (this file)
 *          · Pair (noop, whoop) when WHOOP labels exist (export / HC / banked day)
 *          · Per-head MAE, within-band pass, Pearson r when n≥3
 *          · Composite pass_score 0–100
 *                │
 *                ▼
 *        Evolution log (ModelEvolutionStore) — version, n_pairs, pass, notes
 * ```
 *
 * **Never invents WHOOP numbers.** Missing labels → pass score is null (awaiting), not zero.
 * Scale note: WHOOP Strain is often 0–21; NOOP Effort is 0–100. Alignment normalizes WHOOP
 * strain via [normalizeWhoopStrain] before MAE.
 */
object WhoopNoopAlignment {

    const val MODEL_ID = "whoop-noop-alignment"
    /** Current live model revision shown in UI. Bump when Recovery/Strain/weights change. */
    const val MODEL_VERSION = "0.3.3"

    /** WHOOP Strain official scale max (not 100). */
    const val WHOOP_STRAIN_MAX = 21.0

    /** Within this absolute delta (on the normalized 0–100 scale) a head "passes". */
    const val BAND_RECOVERY = 12.0
    const val BAND_STRAIN = 15.0
    const val BAND_SLEEP = 15.0
    const val BAND_STRESS = 20.0

    data class HeadResult(
        val name: String,
        /** Values used for pass math (both on the same 0–100-ish scale). */
        val noop: Double?,
        val whoop: Double?,
        /** Absolute error on shared 0–100 scale when both present. */
        val absError: Double?,
        val withinBand: Boolean?,
        val band: Double,
        val higherIsBetter: Boolean,
        /**
         * Human display strings — dual units so 14.1/21 is never shown as if it were /100.
         * Example strain: noopDisplay="35/100 · ≈7.4/21", whoopDisplay="14.1/21 · ≈67%".
         */
        val noopDisplay: String = "—",
        val whoopDisplay: String = "—",
        val scaleNote: String? = null,
    )

    data class DayAlignment(
        val day: String,
        val modelVersion: String,
        val heads: List<HeadResult>,
        /**
         * 0–100 composite when ≥1 head has both NOOP and WHOOP.
         * null = awaiting WHOOP labels (honest empty).
         */
        val passScore: Double?,
        val pairedHeads: Int,
        val passedHeads: Int,
        val grade: Grade,
        val summary: String,
    )

    enum class Grade(val label: String) {
        AWAITING("Awaiting WHOOP labels"),
        FAIL("Below band"),
        BUILDING("Building"),
        PASS("Pass"),
        STRONG("Strong match"),
    }

    data class EvolutionEntry(
        val version: String,
        val recordedAtMs: Long,
        val passScore: Double?,
        val nDaysPaired: Int,
        val notes: String,
    )

    /** Seed history of what shipped so far (documentation + UI evolution strip). */
    fun seedEvolutions(): List<EvolutionEntry> = listOf(
        EvolutionEntry(
            version = "0.1.0",
            recordedAtMs = 0L,
            passScore = null,
            nDaysPaired = 0,
            notes = "RecoveryScorer Charge 0–100 (HRV-dominant logistic). No WHOOP pairing yet.",
        ),
        EvolutionEntry(
            version = "0.2.0",
            recordedAtMs = 0L,
            passScore = null,
            nDaysPaired = 0,
            notes = "StrainScorer Effort rescaled 0–100 (log TRIMP). Live HR 2A37 bank. Gap-aware RMSSD.",
        ),
        EvolutionEntry(
            version = "0.2.5",
            recordedAtMs = 0L,
            passScore = null,
            nDaysPaired = 0,
            notes = "Alongside WHOOP app open collect + ML_SAMPLE/RAW_GATT upload to PC :8091.",
        ),
        EvolutionEntry(
            version = "0.3.0",
            recordedAtMs = 0L,
            passScore = null,
            nDaysPaired = 0,
            notes = "Alignment layer: pass_score, band checks, evolution store, live compare UI.",
        ),
        EvolutionEntry(
            version = "0.3.1",
            recordedAtMs = 0L,
            passScore = null,
            nDaysPaired = 0,
            notes = "Dual-scale honesty: WHOOP Strain shown as X/21 AND ≈%; NOOP Effort as /100 AND ≈/21. Pass math only on shared 0–100.",
        ),
        EvolutionEntry(
            version = "0.3.2",
            recordedAtMs = 0L,
            passScore = null,
            nDaysPaired = 0,
            notes = "WHOOP column = app labels only (whoop-app + manual/adb). Example 14.7/21 ≈70% vs Effort /100 never faked equal. Emu/root dumps + Fold adb for labels.",
        ),
        EvolutionEntry(
            version = "0.3.3",
            recordedAtMs = System.currentTimeMillis(),
            passScore = null,
            nDaysPaired = 0,
            notes = "Seed real adb UI labels from assets (strain 14.7/21 for 2026-07-10). Dual-scale bar on Effort head. Pass math still shared 0–100 only; never invent WHOOP.",
        ),
    )

    /**
     * WHOOP Strain is commonly 0–21; if value ≤ 21 treat as WHOOP scale and map to 0–100.
     * Values already > 21 assumed already 0–100-ish.
     */
    fun normalizeWhoopStrain(whoopStrain: Double?): Double? {
        if (whoopStrain == null) return null
        return if (whoopStrain <= 21.0 + 1e-6) {
            (whoopStrain / 21.0 * 100.0).coerceIn(0.0, 100.0)
        } else {
            whoopStrain.coerceIn(0.0, 100.0)
        }
    }

    fun evaluateDay(
        day: String,
        noopRecovery: Double?,
        noopStrain: Double?,
        noopSleep: Double?,
        noopStressPct: Double?,
        whoopRecovery: Double?,
        whoopStrain: Double?,
        whoopSleep: Double?,
        whoopStressPct: Double?,
    ): DayAlignment {
        val whoopStrain100 = normalizeWhoopStrain(whoopStrain)
        val heads = listOf(
            head(
                name = "Charge / Recovery",
                noop = noopRecovery,
                whoop = whoopRecovery,
                band = BAND_RECOVERY,
                higherIsBetter = true,
                noopDisplay = noopRecovery?.let { "${fmt1(it)}/100" } ?: "—",
                whoopDisplay = whoopRecovery?.let { "${fmt1(it)}/100" } ?: "—",
            ),
            // Strain is the scale trap: WHOOP 14.1/21 ≠ 14/100. Pass uses 0–100; UI shows both.
            head(
                name = "Effort / Strain",
                noop = noopStrain,
                whoop = whoopStrain100,
                band = BAND_STRAIN,
                higherIsBetter = false,
                noopDisplay = when {
                    noopStrain == null -> "—"
                    else -> "${fmt1(noopStrain)}/100 · ≈${fmt1(noopStrain / 100.0 * WHOOP_STRAIN_MAX)}/21"
                },
                whoopDisplay = when {
                    whoopStrain == null -> "—"
                    whoopStrain <= WHOOP_STRAIN_MAX + 1e-6 ->
                        "${fmt1(whoopStrain)}/21 · ≈${fmt1(whoopStrain100 ?: 0.0)}%"
                    else -> "${fmt1(whoopStrain)}/100"
                },
                scaleNote = "WHOOP Strain is 0–21; NOOP Effort is 0–100. Pass compares % scales only.",
            ),
            head(
                name = "Rest / Sleep",
                noop = noopSleep,
                whoop = whoopSleep,
                band = BAND_SLEEP,
                higherIsBetter = true,
                noopDisplay = noopSleep?.let { "${fmt1(it)}%" } ?: "—",
                whoopDisplay = whoopSleep?.let { "${fmt1(it)}%" } ?: "—",
            ),
            head(
                name = "Stress",
                noop = noopStressPct,
                whoop = whoopStressPct,
                band = BAND_STRESS,
                higherIsBetter = false,
                noopDisplay = noopStressPct?.let { "${fmt1(it)}%" } ?: "—",
                whoopDisplay = whoopStressPct?.let { "${fmt1(it)}%" } ?: "— (not on open BLE)",
            ),
        )
        val paired = heads.filter { it.absError != null }
        val passed = paired.count { it.withinBand == true }
        val passScore = if (paired.isEmpty()) {
            null
        } else {
            // Soft score: each head contributes max(0, 100 - error/band*100) then average.
            val parts = paired.map { h ->
                val e = h.absError!!
                max(0.0, 100.0 - (e / h.band) * 100.0)
            }
            parts.average()
        }
        val grade = when {
            passScore == null -> Grade.AWAITING
            passScore >= 80.0 && passed == paired.size -> Grade.STRONG
            passScore >= 60.0 && passed >= (paired.size + 1) / 2 -> Grade.PASS
            passScore >= 40.0 -> Grade.BUILDING
            else -> Grade.FAIL
        }
        val summary = when (grade) {
            Grade.AWAITING ->
                "Import WHOOP export or grant Health Connect so we can score agreement. NOOP side is live."
            Grade.STRONG ->
                "Strong match on ${passed}/${paired.size} heads · pass ${passScore!!.roundToInt()}."
            Grade.PASS ->
                "Within band on ${passed}/${paired.size} heads · pass ${passScore!!.roundToInt()}."
            Grade.BUILDING ->
                "Partial agreement (${passScore!!.roundToInt()}) — model still learning your baselines."
            Grade.FAIL ->
                "Large gap vs WHOOP (${passScore!!.roundToInt()}) — check export day match / more nights."
        }
        return DayAlignment(
            day = day,
            modelVersion = MODEL_VERSION,
            heads = heads,
            passScore = passScore,
            pairedHeads = paired.size,
            passedHeads = passed,
            grade = grade,
            summary = summary,
        )
    }

    private fun head(
        name: String,
        noop: Double?,
        whoop: Double?,
        band: Double,
        higherIsBetter: Boolean,
        noopDisplay: String = "—",
        whoopDisplay: String = "—",
        scaleNote: String? = null,
    ): HeadResult {
        val err = if (noop != null && whoop != null) abs(noop - whoop) else null
        return HeadResult(
            name = name,
            noop = noop,
            whoop = whoop,
            absError = err,
            withinBand = err?.let { it <= band },
            band = band,
            higherIsBetter = higherIsBetter,
            noopDisplay = noopDisplay,
            whoopDisplay = whoopDisplay,
            scaleNote = scaleNote,
        )
    }

    private fun fmt1(v: Double): String =
        if (abs(v) >= 10.0 && abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString()
        else String.format("%.1f", v)

    /** Multi-day rollup when several paired days exist. */
    fun rollup(days: List<DayAlignment>): DayAlignment? {
        val usable = days.filter { it.passScore != null }
        if (usable.isEmpty()) return days.lastOrNull()
        val avg = usable.mapNotNull { it.passScore }.average()
        val last = usable.last()
        return last.copy(
            passScore = avg,
            pairedHeads = usable.sumOf { it.pairedHeads },
            passedHeads = usable.sumOf { it.passedHeads },
            grade = when {
                avg >= 80 -> Grade.STRONG
                avg >= 60 -> Grade.PASS
                avg >= 40 -> Grade.BUILDING
                else -> Grade.FAIL
            },
            summary = "Rollup ${usable.size} paired days · mean pass ${avg.roundToInt()} · model $MODEL_VERSION",
        )
    }

    /** Pearson r for two equal-length series; null if n < 3. */
    fun pearson(a: List<Double>, b: List<Double>): Double? {
        if (a.size != b.size || a.size < 3) return null
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val xa = a[i] - ma
            val xb = b[i] - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        val den = sqrt(da * db)
        if (den < 1e-9) return null
        return (num / den).coerceIn(-1.0, 1.0)
    }
}
