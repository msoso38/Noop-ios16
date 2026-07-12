package com.noop.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.noop.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * DEBUG-only review pins: tap the pin FAB → tap anywhere → leave a note.
 * Pins persist to app external files so `Tools/pull_review_pins.ps1` can pull them for batch fixes.
 */
data class ReviewPin(
    val id: String,
    val xFrac: Float,
    val yFrac: Float,
    val note: String,
    val route: String?,
    val createdAtMs: Long,
)

object ReviewPinStore {
    private const val JSON_NAME = "review_pins.json"
    private const val MD_NAME = "review_pins.md"

    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, JSON_NAME)
    }

    fun markdownFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, MD_NAME)
    }

    fun load(context: Context): List<ReviewPin> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ReviewPin(
                            id = o.getString("id"),
                            xFrac = o.getDouble("xFrac").toFloat(),
                            yFrac = o.getDouble("yFrac").toFloat(),
                            note = o.getString("note"),
                            route = o.optString("route").takeIf { it.isNotBlank() && it != "null" },
                            createdAtMs = o.optLong("createdAtMs", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, pins: List<ReviewPin>) {
        val arr = JSONArray()
        pins.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("xFrac", p.xFrac.toDouble())
                    .put("yFrac", p.yFrac.toDouble())
                    .put("note", p.note)
                    .put("route", p.route)
                    .put("createdAtMs", p.createdAtMs),
            )
        }
        val json = file(context)
        json.parentFile?.mkdirs()
        json.writeText(arr.toString(2))
        markdownFile(context).writeText(toMarkdown(pins))
    }

    fun toMarkdown(pins: List<ReviewPin>): String {
        if (pins.isEmpty()) {
            return "# Review pins\n\n_No pins yet._\n"
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return buildString {
            appendLine("# Review pins")
            appendLine()
            appendLine("Batch these UI fixes. Each pin is a screen-relative spot + note.")
            appendLine()
            pins.forEachIndexed { i, p ->
                val whenStr = if (p.createdAtMs > 0L) fmt.format(Date(p.createdAtMs)) else "?"
                appendLine("## ${i + 1}. ${p.route ?: "unknown screen"}")
                appendLine()
                appendLine("- **Note:** ${p.note}")
                appendLine("- **Spot:** x=${"%.0f".format(p.xFrac * 100)}% y=${"%.0f".format(p.yFrac * 100)}%")
                appendLine("- **When:** $whenStr")
                appendLine("- **Id:** `${p.id}`")
                appendLine()
            }
        }
    }
}

private enum class PinMode { Idle, Placing, List }

@Composable
fun ReviewPinOverlay(currentRoute: String?) {
    if (!BuildConfig.DEBUG) return

    val context = LocalContext.current
    val pins = remember { mutableStateListOf<ReviewPin>() }
    var mode by remember { mutableStateOf(PinMode.Idle) }
    var draftFrac by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var draftNote by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        pins.clear()
        pins.addAll(ReviewPinStore.load(context))
    }

    fun persist() {
        ReviewPinStore.save(context, pins.toList())
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(80f),
    ) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current

        // Place mode: intercept taps; otherwise pass through except markers/FAB.
        if (mode == PinMode.Placing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .pointerInput(currentRoute) {
                        detectTapGestures { offset ->
                            draftFrac = (offset.x / wPx).coerceIn(0f, 1f) to
                                (offset.y / hPx).coerceIn(0f, 1f)
                            draftNote = ""
                            editingId = null
                        }
                    }
                    .semantics { contentDescription = "Tap to place a review pin" },
            ) {
                Text(
                    "Tap anywhere to pin a note",
                    style = NoopType.subhead,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 56.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // Existing pins (always visible in debug).
        pins.forEachIndexed { index, pin ->
            val x = (pin.xFrac * wPx).roundToInt()
            val y = (pin.yFrac * hPx).roundToInt()
            Box(
                modifier = Modifier
                    .offset { IntOffset(x - with(density) { 16.dp.roundToPx() }, y - with(density) { 16.dp.roundToPx() }) }
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Palette.accent)
                    .border(1.5.dp, Palette.surfaceBase, CircleShape)
                    .clickable {
                        editingId = pin.id
                        draftFrac = pin.xFrac to pin.yFrac
                        draftNote = pin.note
                        mode = PinMode.Idle
                    }
                    .semantics { contentDescription = "Review pin ${index + 1}" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${index + 1}",
                    style = NoopType.captionNumber,
                    color = Palette.surfaceBase,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Controls: pin FAB + list
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 14.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (pins.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { mode = if (mode == PinMode.List) PinMode.Idle else PinMode.List },
                    containerColor = Palette.surfaceRaised,
                    contentColor = Palette.textPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(2.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Review pin list")
                }
            }
            FloatingActionButton(
                onClick = {
                    mode = if (mode == PinMode.Placing) PinMode.Idle else PinMode.Placing
                    toast = null
                },
                containerColor = if (mode == PinMode.Placing) Palette.statusPositive
                else Palette.surfaceRaised.copy(alpha = 0.92f),
                contentColor = if (mode == PinMode.Placing) Palette.surfaceBase else Palette.accent,
                elevation = FloatingActionButtonDefaults.elevation(2.dp),
                modifier = Modifier.semantics {
                    contentDescription = if (mode == PinMode.Placing) "Cancel pin mode" else "Add UI thought pin"
                },
            ) {
                Icon(
                    if (mode == PinMode.Placing) Icons.Filled.Close else Icons.Filled.EditNote,
                    contentDescription = null,
                )
            }
        }

        if (toast != null) {
            Text(
                toast!!,
                style = NoopType.footnote,
                color = Palette.textPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 100.dp)
                    .background(Palette.surfaceRaised, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        if (mode == PinMode.List) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 160.dp)
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.surfaceRaised)
                    .border(1.dp, Palette.hairline, RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Pins (${pins.size})", style = NoopType.headline, color = Palette.textPrimary)
                    Row {
                        TextButton(
                            onClick = {
                                persist()
                                toast = "Saved — pull with Tools\\pull_review_pins.ps1"
                                mode = PinMode.Idle
                            },
                        ) {
                            Text("Save", color = Palette.accent)
                        }
                        IconButton(onClick = { mode = PinMode.Idle }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close list")
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pins.forEachIndexed { i, pin ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Palette.surfaceBase)
                                .clickable {
                                    editingId = pin.id
                                    draftFrac = pin.xFrac to pin.yFrac
                                    draftNote = pin.note
                                    mode = PinMode.Idle
                                }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                "${i + 1}",
                                style = NoopType.captionNumber,
                                color = Palette.accent,
                                modifier = Modifier.widthIn(min = 18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    pin.route ?: "unknown",
                                    style = NoopType.caption,
                                    color = Palette.textTertiary,
                                )
                                Text(pin.note, style = NoopType.body, color = Palette.textPrimary)
                            }
                            IconButton(
                                onClick = {
                                    pins.removeAll { it.id == pin.id }
                                    persist()
                                },
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete pin", tint = Palette.statusCritical)
                            }
                        }
                    }
                    if (pins.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                pins.clear()
                                persist()
                                mode = PinMode.Idle
                                toast = "Cleared all pins"
                            },
                        ) {
                            Text("Clear all", color = Palette.statusCritical)
                        }
                    }
                }
            }
        }
    }

    val frac = draftFrac
    if (frac != null) {
        AlertDialog(
            onDismissRequest = {
                draftFrac = null
                editingId = null
                draftNote = ""
                mode = PinMode.Idle
            },
            icon = { Icon(Icons.Filled.EditNote, contentDescription = null, tint = Palette.accent) },
            title = {
                Text(
                    if (editingId != null) "Edit thought" else "UI thought",
                    style = NoopType.headline,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "What feels wrong or missing? Pin the spot. Screenshots: save to Photos, then note “shot: …” here.",
                        style = NoopType.caption,
                        color = Palette.textTertiary,
                    )
                    Text(
                        "Screen: ${currentRoute ?: "unknown"} · ${"%.0f".format(frac.first * 100)}%, ${"%.0f".format(frac.second * 100)}%",
                        style = NoopType.caption,
                        color = Palette.textTertiary,
                    )
                    OutlinedTextField(
                        value = draftNote,
                        onValueChange = { draftNote = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Ugly / missing / move this…") },
                        minLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val note = draftNote.trim()
                        if (note.isEmpty()) return@TextButton
                        val id = editingId
                        if (id != null) {
                            val idx = pins.indexOfFirst { it.id == id }
                            if (idx >= 0) {
                                pins[idx] = pins[idx].copy(note = note, route = currentRoute ?: pins[idx].route)
                            }
                        } else {
                            pins.add(
                                ReviewPin(
                                    id = UUID.randomUUID().toString().take(8),
                                    xFrac = frac.first,
                                    yFrac = frac.second,
                                    note = note,
                                    route = currentRoute,
                                    createdAtMs = System.currentTimeMillis(),
                                ),
                            )
                        }
                        persist()
                        draftFrac = null
                        editingId = null
                        draftNote = ""
                        mode = PinMode.Idle
                        toast = "Pinned (${pins.size}) — pull when ready"
                    },
                ) {
                    Text(if (editingId != null) "Update" else "Pin it", color = Palette.accent)
                }
            },
            dismissButton = {
                Row {
                    if (editingId != null) {
                        TextButton(
                            onClick = {
                                pins.removeAll { it.id == editingId }
                                persist()
                                draftFrac = null
                                editingId = null
                                draftNote = ""
                            },
                        ) {
                            Text("Delete", color = Palette.statusCritical)
                        }
                    }
                    TextButton(
                        onClick = {
                            draftFrac = null
                            editingId = null
                            draftNote = ""
                            mode = PinMode.Idle
                        },
                    ) {
                        Text("Cancel", color = Palette.textSecondary)
                    }
                }
            },
            containerColor = Palette.surfaceRaised,
            titleContentColor = Palette.textPrimary,
            textContentColor = Palette.textPrimary,
        )
    }
}
