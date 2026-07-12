package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noop.analytics.Sport
import com.noop.analytics.WorkoutSport

/**
 * Post-workout ask: "What was this workout?" so sport-ID / ML labels improve over time.
 * Shown after a live session ends (or when a detected bout needs confirmation).
 */
@Composable
fun WorkoutSportConfirmSheet(
    suggested: String?,
    onConfirm: (Sport) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val initial = remember(suggested) {
        WorkoutSport.all.firstOrNull { it.name.equals(suggested, ignoreCase = true) }
            ?: WorkoutSport.default
    }
    var selected by remember { mutableStateOf(initial) }
    val filtered = WorkoutSport.all.filter { it.name.contains(query, ignoreCase = true) }
    val scroll = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceRaised,
        title = {
            Text("What was this workout?", style = NoopType.headline, color = Palette.textPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (!suggested.isNullOrBlank()) {
                        "Suggested: $suggested. Confirm or pick the real sport — this trains on-device sport ID."
                    } else {
                        "Pick the sport so future predictions get better. Labels stay on this phone."
                    },
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search sports", color = Palette.textTertiary) },
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    filtered.forEach { sport ->
                        val on = sport.name == selected.name
                        Text(
                            sport.name,
                            style = if (on) NoopType.subhead else NoopType.body,
                            color = if (on) Palette.accent else Palette.textPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = sport }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.effortColor,
                    contentColor = Palette.surfaceBase,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Save label", style = NoopType.subhead) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Skip", style = NoopType.footnote, color = Palette.textSecondary)
            }
        },
    )
}
