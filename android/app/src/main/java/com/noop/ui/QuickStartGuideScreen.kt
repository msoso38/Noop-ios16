package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * First-time / anytime quick start — short path to getting value from NOOP.
 */
@Composable
fun QuickStartGuideScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    LazyScreenScaffold(
        title = "Quick start",
        subtitle = "Get set in under two minutes",
        topBackground = { LiquidScreenSky() },
    ) {
        item {
            GlowCard(tint = Palette.accent) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Welcome to NOOP", style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        "Local health hub for WHOOP 3 / 4 / 5/MG. Numbers stay honest — if the strap " +
                            "doesn't expose a metric, we leave it blank instead of inventing it.",
                        style = NoopType.body,
                        color = Palette.textSecondary,
                    )
                }
            }
        }
        item {
            StepRow(1, Icons.Filled.Bluetooth, "Connect your strap",
                "More → Devices → Add device. Pick WHOOP 3, 4, or 5/MG, then pair. " +
                    "Leave Live open until heart rate streams.")
        }
        item {
            StepRow(2, Icons.Filled.Home, "Today is home",
                "Bottom bar: Today · Trends · P.C. · Sleep · More. " +
                    "Swipe left/right on the main tabs to switch. System back returns to the previous screen.")
        }
        item {
            StepRow(3, Icons.Filled.FavoriteBorder, "Live console",
                "More → Live (or quick actions). See live HR, bond state, and datastream. " +
                    "MG: use Settings → Step training for accurate steps.")
        }
        item {
            StepRow(4, Icons.Filled.CalendarMonth, "P.C. period calendar",
                "Optional. Log period starts & symptoms. WHOOP temp/HRV can refine phase — never invents bleed days.")
        }
        item {
            StepRow(5, Icons.Filled.FitnessCenter, "Workouts & strength",
                "Import Hevy/Liftosaur in Data Sources. Start workouts from Workouts or Live.")
        }
        item {
            GlowCard(tint = Palette.metricAmber) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Overline("What we will not invent")
                    Text(
                        "• Blood pressure from WHOOP until a verified decode exists (use Lab Book cuff)\n" +
                            "• SpO₂ % without a calibrated source (raw ADC is not clinical %)\n" +
                            "• AFib diagnosis — rhythm tools stay experimental / awareness only",
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }
            }
        }
        item {
            NoopButton(
                text = "Got it — start using NOOP",
                kind = NoopButtonKind.Primary,
                fullWidth = true,
                onClick = {
                    NoopPrefs.of(context).edit().putBoolean("noop.quickStartSeen", true).apply()
                    onDone()
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StepRow(
    n: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    GlowCard(tint = Palette.surfaceRaised) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Palette.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", style = NoopType.headline, color = Palette.accent)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                    Text(title, style = NoopType.headline, color = Palette.textPrimary)
                }
                Text(body, style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
    }
}
