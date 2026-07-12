package com.noop.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.analytics.PeriodCalendar
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * True first-run Cycle setup (Menstrudel-style IA, NOOP product voice).
 * Steps: welcome → last start → typical lengths → what predictions mean → how to log.
 */
@Composable
fun CycleOnboardingFlow(
    onFinished: (
        lastStart: String?,
        cycleLen: Int,
        periodLen: Int,
        enableWhoopLearn: Boolean,
    ) -> Unit,
    onImport: () -> Unit,
    onSkipForNow: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var lastStart by remember { mutableStateOf<String?>(null) }
    var cycleLen by remember { mutableFloatStateOf(28f) }
    var periodLen by remember { mutableFloatStateOf(5f) }
    var whoopLearn by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val totalSteps = 5

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.surfaceRaised.copy(alpha = 0.72f))
            .border(1.dp, Palette.hairline, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Step ${step + 1} of $totalSteps",
            style = NoopType.overline,
            color = Palette.textTertiary,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(totalSteps) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(
                            if (i <= step) Palette.accent else Palette.hairline,
                        ),
                )
            }
        }

        when (step) {
            0 -> OnboardWelcome()
            1 -> OnboardLastStart(
                lastStart = lastStart,
                onPick = {
                    val cal = Calendar.getInstance()
                    lastStart?.let { iso ->
                        runCatching {
                            val d = LocalDate.parse(iso)
                            cal.set(d.year, d.monthValue - 1, d.dayOfMonth)
                        }
                    }
                    DatePickerDialog(
                        context,
                        { _, y, m, d ->
                            lastStart = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                        },
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH),
                    ).show()
                },
                onClear = { lastStart = null },
            )
            2 -> OnboardLengths(
                cycleLen = cycleLen,
                periodLen = periodLen,
                onCycle = { cycleLen = it },
                onPeriod = { periodLen = it },
            )
            3 -> OnboardPredictions(whoopLearn = whoopLearn, onWhoop = { whoopLearn = it })
            else -> OnboardHowToLog()
        }

        Spacer(Modifier.height(4.dp))

        when (step) {
            0 -> {
                WetBounceButton(
                    label = "Set up Cycle",
                    modifier = Modifier.fillMaxWidth(),
                    tint = Palette.accent,
                    onClick = { step = 1 },
                )
                WetBounceButton(
                    label = "Import .pc or CSV instead",
                    modifier = Modifier.fillMaxWidth(),
                    tint = Palette.metricCyan,
                    onClick = onImport,
                )
                TextButton(
                    onClick = onSkipForNow,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Not now", style = NoopType.footnote, color = Palette.textTertiary)
                }
            }
            totalSteps - 1 -> {
                WetBounceButton(
                    label = if (lastStart != null) "Start tracking" else "Start without a date",
                    modifier = Modifier.fillMaxWidth(),
                    tint = Palette.accent,
                    onClick = {
                        onFinished(
                            lastStart,
                            cycleLen.toInt().coerceIn(21, 40),
                            periodLen.toInt().coerceIn(2, 10),
                            whoopLearn,
                        )
                    },
                )
                TextButton(onClick = { step -= 1 }) {
                    Text("Back", style = NoopType.footnote, color = Palette.textSecondary)
                }
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { step -= 1 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Back", style = NoopType.subhead, color = Palette.textSecondary)
                    }
                    WetBounceButton(
                        label = "Continue",
                        modifier = Modifier.weight(1.4f),
                        tint = Palette.accent,
                        onClick = { step += 1 },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardWelcome() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cycle, on your terms", style = NoopType.title1, color = Palette.textPrimary)
        Text(
            "Log period starts and symptoms privately on this phone. " +
                "Optional strap clues can refine phase windows — they never invent a bleed day.",
            style = NoopType.body,
            color = Palette.textSecondary,
        )
        StatePill("Private on this phone", tone = StrandTone.Positive, showsDot = true)
    }
}

@Composable
private fun OnboardLastStart(
    lastStart: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("When did your last period start?", style = NoopType.title2, color = Palette.textPrimary)
        Text(
            "One real start unlocks phase labels. Two starts unlock the 12-month planning window.",
            style = NoopType.subhead,
            color = Palette.textSecondary,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.surfaceInset)
                .clickable(onClick = onPick)
                .padding(horizontal = 14.dp, vertical = 16.dp)
                .semantics { contentDescription = "Pick last period start date" },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                lastStart?.let { prettyOnboardDay(it) } ?: "Tap to pick a date",
                style = NoopType.headline,
                color = if (lastStart != null) Palette.textPrimary else Palette.textTertiary,
            )
        }
        if (lastStart != null) {
            TextButton(onClick = onClear) {
                Text("Clear date", style = NoopType.footnote, color = Palette.metricRose)
            }
        }
    }
}

@Composable
private fun OnboardLengths(
    cycleLen: Float,
    periodLen: Float,
    onCycle: (Float) -> Unit,
    onPeriod: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Typical lengths", style = NoopType.title2, color = Palette.textPrimary)
        Text(
            "Used until your own log builds an average. You can change these later.",
            style = NoopType.subhead,
            color = Palette.textSecondary,
        )
        LengthSlider(
            label = "Cycle length",
            valueLabel = "${cycleLen.toInt()} days",
            value = cycleLen,
            range = 21f..40f,
            onChange = onCycle,
        )
        LengthSlider(
            label = "Period length",
            valueLabel = "${periodLen.toInt()} days",
            value = periodLen,
            range = 2f..10f,
            onChange = onPeriod,
        )
    }
}

@Composable
private fun LengthSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = NoopType.subhead, color = Palette.textPrimary)
            Text(valueLabel, style = NoopType.subhead, color = Palette.accent)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = (range.endInclusive - range.start).toInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = Palette.accent,
                activeTrackColor = Palette.accent,
                inactiveTrackColor = Palette.hairline,
            ),
        )
    }
}

@Composable
private fun OnboardPredictions(whoopLearn: Boolean, onWhoop: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What predictions mean", style = NoopType.title2, color = Palette.textPrimary)
        Text(
            "Forecasts are windows, not a single guaranteed day. Soft rose marks likely days; " +
                "amber marks the wider window. Awareness only — not contraception or medical advice.",
            style = NoopType.body,
            color = Palette.textSecondary,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.surfaceInset)
                .clickable { onWhoop(!whoopLearn) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Strap phase clues", style = NoopType.subhead, color = Palette.textPrimary)
                Text(
                    "Skin temp, RHR, HRV may hint at shifts. Your taps stay truth.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            Text(
                if (whoopLearn) "On" else "Off",
                style = NoopType.subhead,
                color = if (whoopLearn) Palette.accent else Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun OnboardHowToLog() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("How to log", style = NoopType.title2, color = Palette.textPrimary)
        LogTip("Hold a calendar day", "Toggle period start on that date.")
        LogTip("Tap a day, then chips", "Flow, symptoms, spotting, intimacy.")
        LogTip("Import anytime", "My Calendar .pc or CSV from Downloads.")
        Text(
            PeriodCalendar.awarenessLine,
            style = NoopType.footnote,
            color = Palette.textTertiary,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun LogTip(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = NoopType.subhead, color = Palette.textPrimary)
        Text(body, style = NoopType.footnote, color = Palette.textSecondary)
    }
}

private fun prettyOnboardDay(iso: String): String = runCatching {
    val d = LocalDate.parse(iso)
    "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${d.year}"
}.getOrDefault(iso)
