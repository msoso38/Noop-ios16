package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.noop.data.NoopGoalBoard

/**
 * Human goals board: complete / partial / not complete / blocked.
 * Every incomplete goal has a **Go test** action that navigates to a real screen —
 * answers are not dead-end copy.
 */
@Composable
fun GoalsBoardScreen(
    onOpenLive: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenTestCentre: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
    onOpenToday: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
    onOpenHealth: () -> Unit = {},
) {
    val complete = NoopGoalBoard.complete()
    val notComplete = NoopGoalBoard.notComplete()
    val (done, partial, rest) = NoopGoalBoard.summary()

    fun actionFor(id: String): (() -> Unit)? = when (id) {
        "G1" -> onOpenSettings // Accessibility / capture settings
        "G2" -> onOpenToday // dual-scale compare card
        "G3" -> onOpenLive // RAW listen inventory
        "G4" -> onOpenSleep
        "G5" -> onOpenToday
        "G6" -> onOpenLive // charge hero
        "G7" -> onOpenToday
        "G8" -> onOpenHealth // blanks / Lab Book honesty
        "G9" -> onOpenDevices
        "G10" -> null // already here
        else -> onOpenTestCentre
    }

    fun actionLabel(id: String): String? = when (id) {
        "G1" -> "Open Settings (grant capture)"
        "G2" -> "Open Today compare"
        "G3" -> "Open Live RAW listen"
        "G4" -> "Open Sleep"
        "G5" -> "Open Today (labels + pass)"
        "G6" -> "Open Live charging"
        "G7" -> "Open Today UI"
        "G8" -> "Open Health (honest blanks)"
        "G9" -> "Open Devices / collect path"
        "G10" -> null
        else -> "Open Test Centre"
    }

    LazyScreenScaffold(
        title = "Goals",
        subtitle = "Complete · not complete · how to test · future impact",
        topBackground = { LiquidScreenSky() },
    ) {
        item {
            GlowCard(tint = Palette.accent) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Progress", style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        "$done complete · $partial partial · $rest not complete / blocked",
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.weight(1f)) {
                            ProgressChip("Complete", complete.size, Palette.statusPositive)
                        }
                        Box(Modifier.weight(1f)) {
                            ProgressChip("Not complete", notComplete.size, Palette.metricRose)
                        }
                    }
                    Text(
                        "Each Go test button jumps to a real screen. Completing a goal means verified data, " +
                            "not a green badge. Never invent clinical SpO2/BP.",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Complete",
                subtitle = "Verified — keep regression-watching",
                color = Palette.statusPositive,
            )
        }
        if (complete.isEmpty()) {
            item {
                Text(
                    "Nothing fully complete yet.",
                    style = NoopType.body,
                    color = Palette.textTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        } else {
            complete.forEach { g ->
                item {
                    GoalCard(
                        g = g,
                        actionLabel = actionLabel(g.id),
                        onAction = actionFor(g.id),
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Not complete",
                subtitle = "Partial · not done · blocked — remaining work with real tests",
                color = Palette.metricRose,
            )
        }
        notComplete.forEach { g ->
            item {
                GoalCard(
                    g = g,
                    actionLabel = actionLabel(g.id),
                    onAction = actionFor(g.id),
                )
            }
        }

        item {
            Text(
                "Loops update this board. Schedulers expire — goals stay until DONE.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ProgressChip(
    label: String,
    count: Int,
    color: androidx.compose.ui.graphics.Color,
) {
    GlowCard(tint = color.copy(alpha = 0.45f)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$count", style = NoopType.title2, color = color)
            Text(label, style = NoopType.footnote, color = Palette.textSecondary)
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = NoopType.title2, color = color)
        Text(subtitle, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun GoalCard(
    g: NoopGoalBoard.Goal,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    val (dot, pillTone) = when (g.status) {
        NoopGoalBoard.Status.DONE -> Palette.statusPositive to StrandTone.Positive
        NoopGoalBoard.Status.PARTIAL -> Palette.metricAmber to StrandTone.Accent
        NoopGoalBoard.Status.NOT_DONE -> Palette.metricRose to StrandTone.Critical
        NoopGoalBoard.Status.BLOCKED -> Palette.textTertiary to StrandTone.Neutral
    }
    GlowCard(tint = dot.copy(alpha = 0.28f)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Text(g.id, style = NoopType.footnote, color = Palette.textTertiary)
                    Text(g.title, style = NoopType.subhead, color = Palette.textPrimary)
                }
                StatePill(NoopGoalBoard.statusLabel(g.status), tone = pillTone, showsDot = false)
            }

            Text(
                if (g.isComplete) "Is complete: yes" else "Is complete: no",
                style = NoopType.footnote,
                color = if (g.isComplete) Palette.statusPositive else Palette.metricRose,
            )
            FieldLabel("Does it work now?")
            Text(g.worksNow, style = NoopType.body, color = Palette.textSecondary)
            FieldLabel("How to test")
            Text(g.howToTest, style = NoopType.body, color = Palette.textSecondary)
            FieldLabel("If incomplete, later…")
            Text(g.futureImpact, style = NoopType.body, color = Palette.textSecondary)
            FieldLabel("Why it’s nice when done")
            Text(g.humanBenefit, style = NoopType.body, color = Palette.textSecondary)

            if (actionLabel != null && onAction != null && !g.isComplete) {
                WetBounceButton(
                    label = actionLabel,
                    modifier = Modifier.fillMaxWidth(),
                    tint = Palette.accent,
                    onClick = onAction,
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = NoopType.footnote, color = Palette.accent)
}
