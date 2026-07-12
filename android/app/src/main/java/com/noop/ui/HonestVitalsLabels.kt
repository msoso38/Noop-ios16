package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Pure mapping for Live/Health vitals display — Measured / Estimate / Lab Book / blank.
 * Never invents open-stream BP, SpO2%, AFib, or clinical VO2.
 *
 * Impeccable product rules: short labels, honest provenance, no marketing wall of text.
 */
object HonestVitalsLabels {

    enum class Provenance {
        MEASURED,
        ESTIMATE,
        LAB_BOOK,
        BLANK,
    }

    data class VitalLine(
        val valueText: String,
        val provenance: Provenance,
        val caption: String,
    )

    fun bpLine(systolic: Double?, diastolic: Double?, day: String?): VitalLine {
        if (systolic == null || diastolic == null || day.isNullOrBlank()) {
            return VitalLine(
                valueText = "—",
                provenance = Provenance.BLANK,
                caption = "Log a cuff reading in Lab Book",
            )
        }
        val s = kotlin.math.round(systolic).toInt()
        val d = kotlin.math.round(diastolic).toInt()
        return VitalLine(
            valueText = "$s/$d",
            provenance = Provenance.LAB_BOOK,
            caption = "Cuff · $day",
        )
    }

    fun spo2Line(bankedPct: Double?): VitalLine {
        if (bankedPct == null || bankedPct <= 0.0 || bankedPct > 100.0) {
            return VitalLine(
                valueText = "—",
                provenance = Provenance.BLANK,
                caption = "Waits for banked overnight SpO₂",
            )
        }
        return VitalLine(
            valueText = "${kotlin.math.round(bankedPct).toInt()}%",
            provenance = Provenance.MEASURED,
            caption = "Banked on-device",
        )
    }

    fun vo2Line(estimate: Double?): VitalLine {
        if (estimate == null || estimate <= 0.0 || estimate > 90.0) {
            return VitalLine(
                valueText = "—",
                provenance = Provenance.BLANK,
                caption = "Waits for vo2max_est",
            )
        }
        return VitalLine(
            valueText = "${kotlin.math.round(estimate).toInt()}",
            provenance = Provenance.ESTIMATE,
            caption = "Model estimate",
        )
    }

    fun provenanceLabel(p: Provenance): String = when (p) {
        Provenance.MEASURED -> "Measured"
        Provenance.ESTIMATE -> "Estimate"
        Provenance.LAB_BOOK -> "Lab Book"
        Provenance.BLANK -> "Blank"
    }

    fun provenanceTone(p: Provenance): StrandTone = when (p) {
        Provenance.MEASURED -> StrandTone.Positive
        Provenance.ESTIMATE -> StrandTone.Accent
        Provenance.LAB_BOOK -> StrandTone.Accent
        Provenance.BLANK -> StrandTone.Neutral
    }
}

/**
 * One vital tile: short name, big value, provenance pill. Shared Live/Health vocabulary.
 * Impeccable: no nested cards; density over marketing copy.
 */
@Composable
fun HonestVitalTile(
    name: String,
    line: HonestVitalsLabels.VitalLine,
    modifier: Modifier = Modifier,
    valueTint: Color = Palette.textPrimary,
) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, style = NoopType.footnote, color = Palette.textSecondary)
        Text(
            line.valueText,
            style = NoopType.number(28f),
            color = if (line.provenance == HonestVitalsLabels.Provenance.BLANK) {
                Palette.textTertiary
            } else {
                valueTint
            },
        )
        StatePill(
            title = HonestVitalsLabels.provenanceLabel(line.provenance),
            tone = HonestVitalsLabels.provenanceTone(line.provenance),
            showsDot = line.provenance != HonestVitalsLabels.Provenance.BLANK,
        )
        Text(line.caption, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

/** Horizontal strip of provenance legend — one place, not an eyebrow on every section. */
@Composable
fun HonestProvenanceLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatePill("Measured", tone = StrandTone.Positive, showsDot = true)
        StatePill("Estimate", tone = StrandTone.Accent, showsDot = false)
        StatePill("Lab Book", tone = StrandTone.Accent, showsDot = false)
        StatePill("Blank", tone = StrandTone.Neutral, showsDot = false)
    }
}
