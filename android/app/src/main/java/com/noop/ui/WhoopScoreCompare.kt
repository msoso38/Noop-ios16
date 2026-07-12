package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.noop.analytics.WhoopNoopAlignment
import com.noop.analytics.HcNoopAlign
import com.noop.data.DailyMetric
import kotlin.math.roundToInt

/**
 * Side-by-side **NOOP (open BLE + our algo)** vs **WHOOP mobile app scores** + pass score.
 *
 * WHOOP column = official **app** Recovery / Day Strain (0–21) from Data Export or manual log —
 * NEVER open BLE, NEVER NOOP-computed rows mislabeled as WHOOP.
 * Pass score from [WhoopNoopAlignment] on a shared 0–100 scale (Strain 0–21 → ×100/21 for math only).
 */
data class ScoreCompareRow(
    val label: String,
    val noop: Double?,
    val whoop: Double?,
    val higherIsBetter: Boolean = true,
    val unit: String = "",
)

@Composable
fun WhoopScoreCompareCard(
    dayLabel: String,
    rows: List<ScoreCompareRow>,
    whoopSourceNote: String,
    alignment: WhoopNoopAlignment.DayAlignment,
    evolutions: List<WhoopNoopAlignment.EvolutionEntry>,
    onOpenHealthConnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val passColor = when (alignment.grade) {
        WhoopNoopAlignment.Grade.STRONG -> Palette.statusPositive
        WhoopNoopAlignment.Grade.PASS -> Palette.accent
        WhoopNoopAlignment.Grade.BUILDING -> Palette.metricAmber
        WhoopNoopAlignment.Grade.FAIL -> Palette.metricRose
        WhoopNoopAlignment.Grade.AWAITING -> Color.White.copy(0.45f)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.surfaceRaised)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("NOOP algo vs WHOOP app", style = NoopType.headline, color = Color.White)
                    Text(
                        "$dayLabel · model ${alignment.modelVersion}",
                        style = NoopType.footnote,
                        color = Color.White.copy(0.55f),
                    )
                }
                StatePill(
                    when (alignment.grade) {
                        WhoopNoopAlignment.Grade.AWAITING -> "Need WHOOP app labels"
                        else -> alignment.grade.label
                    },
                    tone = when (alignment.grade) {
                        WhoopNoopAlignment.Grade.STRONG, WhoopNoopAlignment.Grade.PASS -> StrandTone.Positive
                        WhoopNoopAlignment.Grade.BUILDING -> StrandTone.Accent
                        else -> StrandTone.Neutral
                    },
                    showsDot = true,
                )
            }

            // Pass score hero
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(0.06f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Match vs WHOOP app", style = NoopType.footnote, color = Color.White.copy(0.55f))
                    Text(
                        alignment.passScore?.let { "${it.roundToInt()}" } ?: "—",
                        style = NoopType.display(44f),
                        color = passColor,
                    )
                    Text("/ 100", style = NoopType.caption, color = Color.White.copy(0.4f))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${alignment.passedHeads}/${alignment.pairedHeads} heads in band",
                        style = NoopType.subhead,
                        color = Color.White.copy(0.75f),
                    )
                    Text(
                        alignment.summary,
                        style = NoopType.footnote,
                        color = Color.White.copy(0.5f),
                        modifier = Modifier.padding(start = 24.dp),
                    )
                }
            }

            // Impeccable: short training status — honest, not a fake accuracy badge.
            TrainingStatusStrip()
            Text(
                "LEFT = NOOP open BLE + scorers (Effort 0–100 · ≈/21). " +
                    "RIGHT = WHOOP **app** only (export / Log / adb) — never open BLE as Strain. " +
                    "Example: 14.7/21 ≈ 70% vs Effort 35/100 is an honest gap.",
                style = NoopType.footnote,
                color = Color.White.copy(0.55f),
            )
            // Scale cheat-sheet so 14/21 is never misread as /100
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF5B9DFF).copy(0.12f))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("WHOOP Strain scale", style = NoopType.footnote, color = Color(0xFF8EC0FF))
                Text("0 – 21 (×100/21 → % for pass only)", style = NoopType.footnote, color = Color.White.copy(0.7f))
            }

            alignment.heads.forEach { h ->
                val delta = if (h.noop != null && h.whoop != null) h.noop - h.whoop else null
                val emptyHint = when {
                    h.noop == null && h.whoop == null && h.name.startsWith("Charge") ->
                        "Need NOOP Charge (wear nights) and WHOOP app Recovery % (export / Log)."
                    h.noop == null && h.whoop == null && h.name.startsWith("Effort") ->
                        "Need NOOP Effort and WHOOP Day Strain 0–21 (export / Log)."
                    h.noop == null && h.whoop == null && h.name.startsWith("Rest") ->
                        "Need NOOP Rest or Health Connect sleep; WHOOP sleep from export/HC."
                    h.noop == null && h.whoop == null && h.name.startsWith("Stress") ->
                        "NOOP stress guess appears after HRV baseline; WHOOP app stress is rarely on open export."
                    h.whoop == null ->
                        "WHOOP app side empty — Import Data Export, grant Health Connect, or Log what the app shows."
                    h.noop == null ->
                        "NOOP side empty — wear overnight so Charge / Effort / Rest can score."
                    else -> null
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(0.04f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(h.name, style = NoopType.subhead, color = Color.White.copy(0.9f))
                        Text(
                            when (h.withinBand) {
                                true -> "in band ✓"
                                false -> "gap"
                                null -> "unpaired"
                            },
                            style = NoopType.footnote,
                            color = when (h.withinBand) {
                                true -> Palette.statusPositive
                                false -> Palette.metricAmber
                                null -> Color.White.copy(0.35f)
                            },
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("NOOP algo", style = NoopType.footnote, color = Palette.accent)
                            Text(h.noopDisplay, style = NoopType.number(16f), color = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("WHOOP app", style = NoopType.footnote, color = Color(0xFF5B9DFF))
                            Text(h.whoopDisplay, style = NoopType.number(16f), color = Color(0xFF8EC0FF))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Δ %", style = NoopType.footnote, color = Color.White.copy(0.45f))
                            Text(
                                delta?.let { d -> "${if (d > 0) "+" else ""}${d.roundToInt()}" } ?: "—",
                                style = NoopType.number(16f),
                                color = Color.White.copy(0.7f),
                            )
                        }
                    }
                    // Dual-scale track for Effort/Strain: both markers on shared 0–100 so 14.7/21
                    // (~70%) never sits next to 35/100 looking like "almost equal numbers".
                    if (h.name.startsWith("Effort") && (h.noop != null || h.whoop != null)) {
                        DualScaleCompareTrack(
                            noopPct = h.noop,
                            whoopPct = h.whoop,
                            band = h.band,
                        )
                    }
                    h.scaleNote?.let {
                        Text(it, style = NoopType.footnote, color = Color.White.copy(0.4f))
                    }
                    emptyHint?.let {
                        Text(it, style = NoopType.footnote, color = Palette.metricAmber.copy(alpha = 0.85f))
                    }
                }
            }

            // Evolution strip
            Text("Model evolutions", style = NoopType.footnote, color = Color.White.copy(0.45f))
            evolutions.takeLast(6).forEach { e ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "v${e.version}",
                        style = NoopType.caption,
                        color = if (e.version == alignment.modelVersion) Palette.accent else Color.White.copy(0.55f),
                    )
                    Text(
                        e.passScore?.let { "pass ${it.roundToInt()}" } ?: "—",
                        style = NoopType.caption,
                        color = Color.White.copy(0.45f),
                    )
                }
                Text(e.notes, style = NoopType.footnote, color = Color.White.copy(0.35f))
            }

            Text(whoopSourceNote, style = NoopType.footnote, color = Color.White.copy(0.4f))
            if (onOpenHealthConnect != null && alignment.pairedHeads == 0) {
                WetBounceButton(
                    label = "Import WHOOP app export (Data Management)",
                    modifier = Modifier.fillMaxWidth(),
                    tint = Color(0xFF5B9DFF),
                    onClick = onOpenHealthConnect,
                )
            }
        }
    }
}

/**
 * Build WHOOP **app** side from export daily row (device whoop-app, strain on 0–21) and/or manual store.
 * Returns nulls when we have no official app labels — never fill from NOOP days[].
 */
fun whoopAppMetricFromSources(
    exportDay: DailyMetric?,
    manual: com.noop.data.WhoopAppScoreStore.DayScores?,
): Pair<DailyMetric?, Double?> {
    val recovery = manual?.recoveryPct ?: exportDay?.recovery
    val strain021 = manual?.dayStrain021 ?: exportDay?.strain
    if (recovery == null && strain021 == null) return null to null
    val metric = DailyMetric(
        deviceId = com.noop.data.WhoopAppScoreStore.DEVICE_ID,
        day = exportDay?.day ?: manual?.day ?: "",
        recovery = recovery,
        strain = strain021, // keep 0–21 native for dual-scale display
        totalSleepMin = exportDay?.totalSleepMin,
        avgHrv = exportDay?.avgHrv,
        restingHr = exportDay?.restingHr,
    )
    return metric to null // stress from app not in open export usually
}

/** User types Recovery % and Day Strain 0–21 as shown in the official WHOOP app UI. */
@Composable
fun WhoopAppScoreLogDialog(
    day: String,
    initial: com.noop.data.WhoopAppScoreStore.DayScores?,
    onDismiss: () -> Unit,
    onSave: (com.noop.data.WhoopAppScoreStore.DayScores) -> Unit,
) {
    var rec by remember {
        mutableStateOf(initial?.recoveryPct?.let { String.format("%.0f", it) } ?: "")
    }
    var strain by remember {
        mutableStateOf(initial?.dayStrain021?.let { String.format("%.1f", it) } ?: "")
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WHOOP app scores", style = NoopType.headline, color = Palette.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Open the official WHOOP app and type Recovery % and Day Strain (0–21) for $day. " +
                        "This is how we compare our algo to the app — not to bracelet raw.",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = rec,
                    onValueChange = { rec = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Recovery % (app)") },
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = strain,
                    onValueChange = { strain = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Day Strain 0–21 (app)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val r = rec.toDoubleOrNull()?.coerceIn(0.0, 100.0)
                    val s = strain.toDoubleOrNull()?.coerceIn(0.0, 21.0)
                    onSave(
                        com.noop.data.WhoopAppScoreStore.DayScores(
                            day = day,
                            recoveryPct = r,
                            dayStrain021 = s,
                            source = "manual",
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun fmt(v: Double?): String {
    if (v == null) return "—"
    return if (kotlin.math.abs(v) >= 10) v.roundToInt().toString() else String.format("%.1f", v)
}

/**
 * On-device training honesty strip. Reads bundled assets when present; never claims accuracy_valid
 * without enough labeled days (Impeccable: no fake 100% pass).
 */
@Composable
private fun TrainingStatusStrip() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val status = remember {
        runCatching {
            context.assets.open("ml_engine_status.json").bufferedReader().use { it.readText() }
        }.getOrNull()?.let { raw ->
            runCatching {
                val o = org.json.JSONObject(raw)
                val nLab = o.optInt("n_label_rows", 0)
                val nFeat = o.optInt("n_feature_days", 0)
                val nSamples = o.optInt("n_ml_samples_ingested", 0)
                val valid = o.optBoolean("accuracy_valid", false)
                val msg = o.optString("message", "")
                "ML: ${nSamples} samples · ${nFeat} feature days · ${nLab} WHOOP app labels · " +
                    if (valid) "accuracy valid" else "need ≥3 labeled days (underfit)"
            }.getOrNull()
        } ?: "ML: open collect active · accuracy needs ≥3 distinct WHOOP app Strain days"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(0.05f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            status,
            style = NoopType.footnote,
            color = Color.White.copy(0.65f),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Shared 0–100 track with NOOP (gold) and WHOOP-app (blue) markers.
 * [whoopPct] must already be normalized (Strain 0–21 → ×100/21 via alignment).
 */
@Composable
private fun DualScaleCompareTrack(
    noopPct: Double?,
    whoopPct: Double?,
    band: Double,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Shared 0–100 track (WHOOP Strain already ×100/21 for this bar)",
            style = NoopType.footnote,
            color = Color.White.copy(0.4f),
        )
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(0.08f)),
        ) {
            val wPx = maxWidth
            // Band corridor around WHOOP marker.
            whoopPct?.let { w ->
                val lo = ((w - band) / 100.0).coerceIn(0.0, 1.0)
                val hi = ((w + band) / 100.0).coerceIn(0.0, 1.0)
                Box(
                    Modifier
                        .offset(x = wPx * lo.toFloat())
                        .width(wPx * (hi - lo).toFloat().coerceAtLeast(0.02f))
                        .height(12.dp)
                        .background(Color(0xFF5B9DFF).copy(0.20f)),
                )
            }
            // NOOP fill from 0 → value
            noopPct?.let { n ->
                val f = (n / 100.0).coerceIn(0.0, 1.0).toFloat()
                Box(
                    Modifier
                        .width(wPx * f.coerceAtLeast(0.02f))
                        .height(12.dp)
                        .background(Palette.accent.copy(0.35f)),
                )
            }
            // WHOOP marker (blue)
            whoopPct?.let { w ->
                val f = (w / 100.0).coerceIn(0.0, 1.0).toFloat()
                Box(
                    Modifier
                        .offset(x = (wPx * f) - 4.dp)
                        .size(8.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF8EC0FF)),
                )
            }
            // NOOP marker (gold)
            noopPct?.let { n ->
                val f = (n / 100.0).coerceIn(0.0, 1.0).toFloat()
                Box(
                    Modifier
                        .offset(x = (wPx * f) - 4.dp)
                        .size(8.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Palette.accent),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = NoopType.caption, color = Color.White.copy(0.35f))
            Text("gold=NOOP  blue=WHOOP app", style = NoopType.caption, color = Color.White.copy(0.4f))
            Text("100", style = NoopType.caption, color = Color.White.copy(0.35f))
        }
    }
}

fun buildCompareRows(
    noopRecovery: Double?,
    noopStrain: Double?,
    noopSleepPerf: Double?,
    noopStress: Double?,
    whoop: DailyMetric?,
    whoopStress: Double?,
    /** HC / WHOOP-app sleep duration minutes when official Sleep Performance isn't labeled. */
    hcSleepMin: Double? = null,
): List<ScoreCompareRow> {
    val whoopSleepPerf = whoop?.let { d ->
        // Prefer an explicit sleep_performance-like field if ever present on the export row via recovery-adjacent;
        // otherwise duration-vs-8h need is an honest proxy (not invented clinical %).
        HcNoopAlign.durationAsSleepPerf(d.totalSleepMin)
            ?: HcNoopAlign.durationAsSleepPerf(hcSleepMin)
    } ?: HcNoopAlign.durationAsSleepPerf(hcSleepMin)
    return listOf(
        ScoreCompareRow("Charge / Recovery", noopRecovery, whoop?.recovery, higherIsBetter = true),
        ScoreCompareRow("Effort / Strain", noopStrain, whoop?.strain, higherIsBetter = false),
        ScoreCompareRow(
            "Rest / Sleep %",
            noopSleepPerf,
            whoopSleepPerf,
            higherIsBetter = true,
        ),
        ScoreCompareRow("Stress (0–3 band)", noopStress, whoopStress, higherIsBetter = false),
    )
}

fun alignmentFromRows(
    day: String,
    rows: List<ScoreCompareRow>,
): WhoopNoopAlignment.DayAlignment {
    fun row(labelPrefix: String) = rows.firstOrNull { it.label.startsWith(labelPrefix) }
    val rec = row("Charge")
    val str = row("Effort")
    val slp = row("Rest")
    val st = row("Stress")
    return WhoopNoopAlignment.evaluateDay(
        day = day,
        noopRecovery = rec?.noop,
        noopStrain = str?.noop,
        noopSleep = slp?.noop,
        noopStressPct = st?.noop,
        whoopRecovery = rec?.whoop,
        whoopStrain = str?.whoop,
        whoopSleep = slp?.whoop,
        whoopStressPct = st?.whoop,
    )
}
