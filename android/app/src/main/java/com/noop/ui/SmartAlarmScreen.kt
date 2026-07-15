package com.noop.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.alarm.AlarmSource
import com.noop.alarm.StrapArmState
import com.noop.alarm.StrapArmStatus
import com.noop.alarm.UnifiedAlarm
import com.noop.alarm.UnifiedAlarmResolver
import com.noop.alarm.displayLabel

// MARK: - Entry point

/**
 * Smart alarm screen - multi-alarm list with tri-state source picker and 24h highlight.
 *
 * Owns its own root [LazyColumn] (mirroring [LazyScreenScaffold]) rather than nesting one inside the
 * eager [ScreenScaffold]. A LazyColumn inside ScreenScaffold's `Modifier.verticalScroll(...)` crashed
 * during the NavHost crossfade enter transition: AnimatedContent briefly measures the incoming child
 * with infinity height, which Compose rejects for a vertically scrollable inside a vertical scroller.
 * Keeping the LazyColumn AT the top means the row is the scroller, not nested in one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmartAlarmScreen(vm: AppViewModel) {
    val alarms by vm.unifiedAlarms.collectAsStateWithLifecycle()
    val strapArmStatus by vm.strapArmStatus.collectAsStateWithLifecycle()
    val nowMs = remember { System.currentTimeMillis() }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }
        }
    }

    fun hasPhonePath(alarm: UnifiedAlarm): Boolean =
        alarm.source == AlarmSource.PHONE || alarm.source == AlarmSource.STRAP_AND_PHONE

    fun updateAlarmIfAllowed(updated: UnifiedAlarm) {
        if (updated.enabled && hasPhonePath(updated) && !vm.canScheduleExactAlarms()) {
            requestExactAlarmAccess()
            return
        }
        vm.updateAlarm(updated.id, updated)
    }

    fun setAlarmEnabledIfAllowed(alarm: UnifiedAlarm, enabled: Boolean) {
        if (enabled && hasPhonePath(alarm) && !vm.canScheduleExactAlarms()) {
            requestExactAlarmAccess()
            return
        }
        vm.setAlarmEnabled(alarm.id, enabled)
    }

    val listState = rememberLazyListState()
    val alarmDrag = remember { AlarmRowDragState() }
    val alarmDragActive = alarmDrag.key != null
    val dragEnabled = expandedId == null
    LaunchedEffect(alarmDragActive, alarms) {
        while (alarmDrag.key != null) {
            withFrameNanos { }
            swapTargetForDraggedAlarm(listState, alarmDrag, alarms)?.let { (draggedId, targetId) ->
                val fromIndex = alarms.indexOfFirst { it.id == draggedId }
                val toIndex = alarms.indexOfFirst { it.id == targetId }
                if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                    vm.reorderAlarms(fromIndex, toIndex)
                }
            }
            if (alarmDrag.autoScrollPxPerFrame != 0f) {
                listState.scrollBy(alarmDrag.autoScrollPxPerFrame)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.surfaceBase),
        contentPadding = PaddingValues(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Header (parity with ScreenScaffold / LazyScreenScaffold).
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Smart alarm", style = NoopType.title1, color = Palette.textPrimary)
                Text("Wake in a lighter sleep phase.", style = NoopType.subhead, color = Palette.textSecondary)
            }
        }

        if (alarms.isEmpty()) {
            item { EmptyStateCard(onAdd = { expandedId = vm.addAlarm() }) }
        } else {
            items(alarms, key = { alarm -> ALARM_ROW_KEY_PREFIX + alarm.id }) { alarm ->
                val isExpanded = alarm.id == expandedId
                val nextFire = UnifiedAlarmResolver.nextFireAtEpochMs(alarm, nowMs)
                val is24h = alarm.enabled &&
                    nextFire != null &&
                    nextFire - nowMs < 24 * 60 * 60_000L
                val rowStrapStatus = strapArmStatus?.takeIf { it.alarmId == alarm.id }

                ReorderableAlarmRow(
                    alarm = alarm,
                    listState = listState,
                    drag = alarmDrag,
                    dragEnabled = dragEnabled,
                    nowMs = nowMs,
                    isExpanded = isExpanded,
                    is24hHighlight = is24h,
                    strapStatus = rowStrapStatus,
                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onExpandToggle = {
                        expandedId = if (isExpanded) null else alarm.id
                    },
                    onToggle = { on -> setAlarmEnabledIfAllowed(alarm, on) },
                    onUpdate = { updated -> updateAlarmIfAllowed(updated) },
                    onDelete = { vm.deleteAlarm(alarm.id) },
                )
            }
            item { AddAlarmButton(onClick = { expandedId = vm.addAlarm() }) }
        }
        item { WindDownCard(vm) }
        item { ExplanationCard() }
    }
}

// MARK: - Alarm row (collapsed + expanded)

private const val ALARM_ROW_KEY_PREFIX = "alarmRow:"

private class AlarmRowDragState {
    var key by mutableStateOf<String?>(null)
    var distance by mutableFloatStateOf(0f)
    var pickedUpAt = 0f
    var autoScrollPxPerFrame = 0f
}

private fun swapTargetForDraggedAlarm(
    listState: LazyListState,
    drag: AlarmRowDragState,
    alarms: List<UnifiedAlarm>,
): Pair<String, String>? {
    val key = drag.key ?: return null
    val info = listState.layoutInfo
    val current = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
    val middle = drag.pickedUpAt + drag.distance + current.size / 2f
    val target = info.visibleItemsInfo.firstOrNull { item ->
        item.key != key && (item.key as? String)?.startsWith(ALARM_ROW_KEY_PREFIX) == true &&
            middle >= item.offset && middle <= item.offset + item.size
    } ?: return null

    val draggedId = key.removePrefix(ALARM_ROW_KEY_PREFIX)
    val targetId = (target.key as String).removePrefix(ALARM_ROW_KEY_PREFIX)
    val draggedIndex = alarms.indexOfFirst { it.id == draggedId }
    val targetIndex = alarms.indexOfFirst { it.id == targetId }
    if (draggedIndex == -1 || targetIndex == -1 || draggedIndex == targetIndex) return null

    val targetCentre = target.offset + target.size / 2f
    val movingDown = targetIndex > draggedIndex
    if (movingDown && middle < targetCentre) return null
    if (!movingDown && middle > targetCentre) return null
    return draggedId to targetId
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.ReorderableAlarmRow(
    alarm: UnifiedAlarm,
    listState: LazyListState,
    drag: AlarmRowDragState,
    dragEnabled: Boolean,
    nowMs: Long,
    isExpanded: Boolean,
    is24hHighlight: Boolean,
    strapStatus: StrapArmStatus?,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    onExpandToggle: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUpdate: (UnifiedAlarm) -> Unit,
    onDelete: () -> Unit,
) {
    val key = ALARM_ROW_KEY_PREFIX + alarm.id
    val isDragging = drag.key == key
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .then(
                if (isDragging) {
                    Modifier.graphicsLayer {
                        val current = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                        translationY = if (current != null) drag.pickedUpAt + drag.distance - current.offset else 0f
                        shadowElevation = 12f
                        scaleX = 1.01f
                        scaleY = 1.01f
                    }
                } else {
                    Modifier.animateItemPlacement()
                },
            )
            .then(
                if (dragEnabled && !isExpanded) {
                    Modifier.pointerInput(key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                drag.key = key
                                drag.distance = 0f
                                drag.pickedUpAt = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == key }?.offset?.toFloat() ?: 0f
                                drag.autoScrollPxPerFrame = 0f
                                onDragStarted()
                            },
                            onDragEnd = {
                                drag.key = null
                                drag.distance = 0f
                                drag.autoScrollPxPerFrame = 0f
                                onDragStopped()
                            },
                            onDragCancel = {
                                drag.key = null
                                drag.distance = 0f
                                drag.autoScrollPxPerFrame = 0f
                                onDragStopped()
                            },
                            onDrag = onDrag@{ change, amount ->
                                change.consume()
                                drag.distance += amount.y
                                val info = listState.layoutInfo
                                val current = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@onDrag
                                val zone = 96.dp.toPx()
                                val maxV = 18.dp.toPx()
                                val top = drag.pickedUpAt + drag.distance
                                val bottom = top + current.size
                                drag.autoScrollPxPerFrame = when {
                                    bottom > info.viewportEndOffset - zone ->
                                        maxV * ((bottom - (info.viewportEndOffset - zone)) / zone).coerceAtMost(1f)
                                    top < info.viewportStartOffset + zone ->
                                        -maxV * (((info.viewportStartOffset + zone) - top) / zone).coerceAtMost(1f)
                                    else -> 0f
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        AlarmRow(
            alarm = alarm,
            nowMs = nowMs,
            isExpanded = isExpanded,
            is24hHighlight = is24hHighlight,
            strapStatus = strapStatus,
            onExpandToggle = onExpandToggle,
            onToggle = onToggle,
            onUpdate = onUpdate,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun AlarmRow(
    alarm: UnifiedAlarm,
    modifier: Modifier = Modifier,
    nowMs: Long,
    isExpanded: Boolean,
    is24hHighlight: Boolean,
    strapStatus: StrapArmStatus?,
    onExpandToggle: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUpdate: (UnifiedAlarm) -> Unit,
    onDelete: () -> Unit,
) {
    // Next-24h rows get the accented surface. Later alarms stay on the normal card background;
    // expanded state affects layout only, not alarm urgency colour.
    val bg = if (is24hHighlight) Palette.accentMuted else Palette.surfaceRaised
    val atAGlanceWeekdayAccent = if (is24hHighlight) Palette.accent else Palette.accentMuted
    val atAGlanceWeekdayContent = if (is24hHighlight) Palette.surfaceBase else Palette.textPrimary
    // Only show the row's date/status label when it adds info the weekday pills already don't:
    //  - "Off" when disabled
    //  - "Today" / "Tomorrow" - because those are not directly visible in the pills row
    //  - "Tue, Jun 30" - for 7+-days-out cases (full weekday names are skipped: pills cover them)
    val rawLabel = remember(alarm, nowMs) { displayLabel(alarm, nowMs) }
    val label = when {
        !alarm.enabled -> "Off"
        rawLabel == "Today" || rawLabel == "Tomorrow" -> rawLabel
        // Anything containing a comma is the "EEE, MMM d" branch from displayLabel().
        rawLabel.contains(",") -> rawLabel
        else -> ""   // weekday name (Mon..Sun): redundant with pills below
    }

    // No NoopCard wrapper here: the row needs one continuous surface so the compact and expanded
    // states read as the same alarm, with the outer click handling expansion.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .background(bg, shape = RoundedCornerShape(Metrics.cardRadius))
            .clickable(onClick = onExpandToggle),
    ) {
        // Collapsed row header. No inner .clickable: the outer Column already routes taps
        // anywhere on the card to onExpandToggle.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                // Time - tappable, opens the TimePicker. Its own .clickable absorbs the touch
                // so the surrounding row's expand-toggle handler does NOT also fire.
                HeaderTimePicker(
                    minutes = alarm.wakeMinutes,
                    onPicked = { onUpdate(alarm.copy(wakeMinutes = it)) },
                )
                Spacer(Modifier.width(10.dp))

                // Label chip - only shown when it carries info the pill row doesn't (Off, Today,
                // Tomorrow, or a far-out date). Plain weekday names are suppressed.
                if (label.isNotEmpty()) {
                    Text(label, style = NoopType.footnote, color = Palette.accent)
                }

                Spacer(Modifier.weight(1f))

                // Strap firmware status pill: pending until the desired alarm matches known firmware state.
                if (strapStatus != null) {
                    val statusColor = when (strapStatus.state) {
                        StrapArmState.ARMED -> DomainTheme.Rest.color
                        StrapArmState.PENDING -> Palette.statusWarning
                    }
                    val statusLabel = stringResource(
                        when (strapStatus.state) {
                            StrapArmState.ARMED -> R.string.alarm_strap_status_armed
                            StrapArmState.PENDING -> R.string.alarm_strap_status_pending
                        },
                    )
                    Text(
                        statusLabel,
                        style = NoopType.footnote,
                        color = statusColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Palette.surfaceInset)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }

                Switch(
                        checked = alarm.enabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                    ),
                )
            }

            // Weekday pills row - only when collapsed. Hidden when expanded because the editor
            // body already shows the interactive WeekdayPicker. The strip spans the full row
            // width so the days read as a complete spectrum. The wake-source badge sits beside
            // the days and under the switch, now that the caret is gone.
            if (!isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WeekdayPills(
                        weekdays = alarm.weekdays,
                        accentColor = atAGlanceWeekdayAccent,
                        activeContentColor = atAGlanceWeekdayContent,
                        fullWidth = true,
                        modifier = Modifier.weight(1f),
                    )
                    WakeSourceBadge(source = alarm.source)
                }
            }

            // Expanded editor - no divider above it; the editor's own padding gives the gap.
        if (isExpanded) {
            AlarmEditor(
                alarm = alarm,
                onUpdate = onUpdate,
                onDelete = onDelete,
            )
        }
    }
}

// Shared width for the right-hand control column inside the AlarmEditor. Every settings row
// (segmented source picker, smart-wake switch, pre-wake stepper, phone-backup stepper) parks
// its control inside a Box of this width, right-aligned. This is the "invisible line" that
// separates label text on the left from controls on the right.
private val EditorControlColumnWidth = 180.dp
private val AlarmStepperWidth = 140.dp
private val AlarmStepperValueWidth = 64.dp
private val WeekdayPickerMaxWidth = 280.dp

// MARK: - Alarm editor (expanded section)

@Composable
private fun AlarmEditor(
    alarm: UnifiedAlarm,
    onUpdate: (UnifiedAlarm) -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Wake time row removed - the collapsed-row header time is itself a TimePicker trigger
        // (HeaderTimePicker), so a second editor row for the same value is redundant.

        // Weekday picker
        WeekdayPicker(
            weekdays = alarm.weekdays,
            onChanged = { onUpdate(alarm.copy(weekdays = it)) },
        )

        RowDividerLocal()

        // Source segmented control (Android only - tri-state)
        SourcePicker(
            source = alarm.source,
            onChanged = { onUpdate(alarm.copy(source = it)) },
        )

        // Smart wake + Earliest wake-up are presented as one grouped setting. Earliest wake-up
        // stays visible when Smart wake is off, but is visually disabled so the dependency is clear.
        RowDividerLocal()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRowLocal(
                label = "Smart wake",
                help = "",
                checked = alarm.smartWake,
                onChange = { onUpdate(alarm.copy(smartWake = it)) },
                alignToControlColumn = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Earliest wake-up",
                    style = NoopType.footnote,
                    color = if (alarm.smartWake) Palette.textSecondary else Palette.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.width(EditorControlColumnWidth),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    PreWakeWindowStepper(
                        preWakeWindowMinutes = alarm.preWakeWindowMinutes,
                        enabled = alarm.smartWake,
                        onChange = { onUpdate(alarm.copy(preWakeWindowMinutes = it)) },
                    )
                }
            }
        }

        // Backup alarm - only for STRAP_AND_PHONE. Semantics: how many minutes AFTER the
        // wake time the backup phone alarm rings as a safety net if the strap didn't wake you.
        // Renamed from "Phone backup delay" - the user already picked phone+strap, "backup" is
        // the role and "alarm" is what it is; "delay" was internal jargon.
        if (alarm.source == AlarmSource.STRAP_AND_PHONE) {
            RowDividerLocal()
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Backup alarm",
                    style = NoopType.body,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.width(EditorControlColumnWidth),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    PhoneBackupStepper(
                        phoneBackupDelayMinutes = alarm.phoneBackupDelayMinutes,
                        onChange = { onUpdate(alarm.copy(phoneBackupDelayMinutes = it)) },
                    )
                }
            }
        }

        RowDividerLocal()

        // Delete is intentionally neutral here. The confirmation dialog is the guardrail; the row
        // itself should not read as the primary action in the editor.
        TextButton(
            onClick = { showDeleteConfirm = true },
            colors = ButtonDefaults.textButtonColors(contentColor = Palette.textSecondary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = Palette.textSecondary)
            Spacer(Modifier.width(8.dp))
            Text("Delete alarm", style = NoopType.body)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete alarm?", style = NoopType.title2, color = Palette.textPrimary) },
            text = { Text("This alarm will be removed.", style = NoopType.body, color = Palette.textSecondary) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Delete", color = Palette.statusCritical)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Palette.textSecondary)
                }
            },
            containerColor = Palette.surfaceOverlay,
        )
    }
}

// MARK: - Weekday pills (display)

/**
 * Read-only display of weekdays as small pill buttons. Mon-first order.
 * When [weekdays] is empty, no pill is active: that is the one-shot state.
 */
@Composable
private fun WeekdayPills(
    weekdays: Set<Int>,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = false,
    accentColor: Color = Palette.accent,
    activeContentColor: Color = Palette.surfaceBase,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Mon=2..Sun=1 -> display Mon-first: 2,3,4,5,6,7,1
        val orderedDays = listOf(2, 3, 4, 5, 6, 7, 1)
        val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
        orderedDays.forEachIndexed { i, dow ->
            WeekdayToken(
                label = dayLabels[i],
                active = dow in weekdays,
                accentColor = accentColor,
                activeContentColor = activeContentColor,
                modifier = if (fullWidth) Modifier.weight(1f) else Modifier,
            )
        }
    }
}

@Composable
private fun WeekdayToken(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = Palette.accent,
    activeContentColor: Color = Palette.surfaceBase,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val shape = if (active) CircleShape else RoundedCornerShape(6.dp)
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(shape)
                .background(if (active) accentColor else Palette.surfaceInset)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = NoopType.footnote,
                color = if (active) activeContentColor else Palette.textTertiary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// MARK: - Weekday picker (editor)

@Composable
private fun WeekdayPicker(
    weekdays: Set<Int>,
    accentColor: Color = Palette.accent,
    activeContentColor: Color = Palette.surfaceBase,
    onChanged: (Set<Int>) -> Unit,
) {
    // Header text and subtitle deliberately omitted - the pill row is self-evident as a
    // weekday picker. Empty means one-shot, so no weekday is active.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = WeekdayPickerMaxWidth),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Mon-first: 2,3,4,5,6,7,1
            val orderedDays = listOf(2, 3, 4, 5, 6, 7, 1)
            val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            orderedDays.forEachIndexed { i, dow ->
                val active = dow in weekdays
                WeekdayToken(
                    label = dayLabels[i].take(1),
                    active = active,
                    accentColor = accentColor,
                    activeContentColor = activeContentColor,
                    modifier = Modifier.weight(1f),
                    onClick = {
                            val next = if (dow in weekdays) {
                                val after = weekdays - dow
                                if (after.isEmpty()) emptySet() else after
                            } else {
                                weekdays + dow
                            }
                            onChanged(next)
                    },
                )
            }
        }
    }
}

// MARK: - Wake source badge

@Composable
private fun WakeSourceBadge(source: AlarmSource, accentColor: Color = Palette.accent) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = source.accessibilityLabel() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when (source) {
            AlarmSource.STRAP -> {
                Icon(Icons.Filled.Watch, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
            }
            AlarmSource.STRAP_AND_PHONE -> {
                Icon(Icons.Filled.Watch, contentDescription = null, tint = accentColor, modifier = Modifier.size(15.dp))
                Icon(Icons.Filled.Smartphone, contentDescription = null, tint = accentColor, modifier = Modifier.size(15.dp))
            }
            AlarmSource.PHONE -> {
                Icon(Icons.Filled.Smartphone, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// MARK: - Source picker (Android-only tri-state segmented control)

@Composable
private fun SourcePicker(
    source: AlarmSource,
    accentColor: Color = Palette.accent,
    selectedContentColor: Color = Palette.surfaceBase,
    onChanged: (AlarmSource) -> Unit,
) {
    val sources = AlarmSource.entries
    // Icon per source: watch for STRAP, watch+phone for STRAP_AND_PHONE, phone for PHONE.
    // Rendered as icon-only segments to keep the row compact: the "Wake source" label sits
    // on the left and the segmented control on the right.
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Wake source",
            style = NoopType.body,
            color = Palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        // Right-side control parked in the shared control column so its left edge
        // lines up with every other settings row's control.
        Box(
            modifier = Modifier.width(EditorControlColumnWidth),
            contentAlignment = Alignment.CenterEnd,
        ) {
            CompactSourceSegments(
                sources = sources,
                selected = source,
                accentColor = accentColor,
                selectedContentColor = selectedContentColor,
                onChanged = onChanged,
            )
        }
    }
}

@Composable
private fun CompactSourceSegments(
    sources: List<AlarmSource>,
    selected: AlarmSource,
    accentColor: Color = Palette.accent,
    selectedContentColor: Color = Palette.surfaceBase,
    onChanged: (AlarmSource) -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .width(EditorControlColumnWidth)
            .height(44.dp)
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sources.forEachIndexed { index, source ->
            SourceSegment(
                source = source,
                selected = source == selected,
                accentColor = accentColor,
                selectedContentColor = selectedContentColor,
                onClick = { onChanged(source) },
                modifier = Modifier.weight(1f),
            )
            if (index < sources.lastIndex) {
                Spacer(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(Palette.hairline),
                )
            }
        }
    }
}

@Composable
private fun SourceSegment(
    source: AlarmSource,
    selected: Boolean,
    accentColor: Color = Palette.accent,
    selectedContentColor: Color = Palette.surfaceBase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) selectedContentColor else Palette.textSecondary
    Box(
        modifier = modifier
            .height(44.dp)
            .background(if (selected) accentColor else Palette.surfaceInset)
            .clickable(onClick = onClick)
            .semantics { contentDescription = source.accessibilityLabel() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            when (source) {
                AlarmSource.STRAP -> {
                    Icon(Icons.Filled.Watch, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
                }
                AlarmSource.STRAP_AND_PHONE -> {
                    Icon(Icons.Filled.Watch, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Icon(Icons.Filled.Smartphone, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                }
                AlarmSource.PHONE -> {
                    Icon(Icons.Filled.Smartphone, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

private fun AlarmSource.accessibilityLabel(): String = when (this) {
    AlarmSource.STRAP -> "Strap"
    AlarmSource.STRAP_AND_PHONE -> "Strap and phone"
    AlarmSource.PHONE -> "Phone"
}

// MARK: - Empty state

@Composable
private fun EmptyStateCard(onAdd: () -> Unit) {
    NoopCard(padding = 20.dp) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Alarm, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(40.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No alarms yet", style = NoopType.title2, color = Palette.textPrimary)
                Text("Add your first alarm to get started.", style = NoopType.footnote, color = Palette.textTertiary)
            }
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.accentMuted,
                    contentColor = Palette.accent,
                ),
            ) {
                Text("Set your first alarm", style = NoopType.body)
            }
        }
    }
}

// MARK: - Add alarm button

@Composable
private fun AddAlarmButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("+ Add an alarm", style = NoopType.body, color = Palette.accent)
    }
}

// MARK: - Explanation card

@Composable
private fun ExplanationCard() {
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = Palette.accent)
                Spacer(Modifier.width(10.dp))
                Text("Smart wake", style = NoopType.headline, color = Palette.textPrimary)
            }
            Text(
                "When Smart wake is on, NOOP starts checking at your Earliest wake-up setting " +
                    "and can wake you when your body looks closer to stirring.",
                style = NoopType.footnote, color = Palette.textSecondary,
            )
            Text(
                "If the strap is not streaming, the scheduled alarm still fires.",
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - Wind-down card

/** The cross-platform evening wind-down nudge - a gentle reminder, not an alarm. Rest-tinted when on. */
@Composable
private fun WindDownCard(vm: AppViewModel) {
    val enabled by vm.windDownEnabled.collectAsStateWithLifecycle()
    NoopCard(padding = 20.dp, tint = if (enabled) DomainTheme.Rest.color else null) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Overline("Evening")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bedtime, contentDescription = null, tint = DomainTheme.Rest.color)
                    Spacer(Modifier.width(10.dp))
                    Text("Wind-down nudge", style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            ToggleRowLocal(
                label = "Remind me to wind down",
                help = "A gentle evening notification, timed from your wake time and usual sleep need, so you can settle in time. It's a suggestion, not an alarm.",
                checked = enabled,
                onChange = { vm.setWindDownEnabled(it) },
            )
        }
    }
}

// MARK: - Header time picker

/**
 * Prominent, tappable wake-time display used as the collapsed-row header. Opens a full
 * TimePicker dialog on tap; its own .clickable consumes the touch so the surrounding row's
 * expand-toggle does NOT also fire.
 *
 * Typography is intentionally larger than the editor's chip-style TimeChip - this is the
 * row's primary visual anchor, not a setting in a list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderTimePicker(
    minutes: Int,
    accentColor: Color = Palette.accent,
    onPicked: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val hour = minutes / 60
    val minute = minutes % 60
    Text(
        text = "%02d:%02d".format(hour, minute),
        style = NoopType.number(34f),
        color = Palette.textPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { showPicker = true }
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = "Wake time" },
    )

    if (showPicker) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        Dialog(onDismissRequest = { showPicker = false }) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.surfaceOverlay)
                    .border(1.dp, Palette.hairline, RoundedCornerShape(20.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Wake time", style = NoopType.headline, color = Palette.textPrimary)
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Palette.surfaceInset,
                        clockDialSelectedContentColor = Palette.surfaceBase,
                        clockDialUnselectedContentColor = Palette.textPrimary,
                        selectorColor = accentColor,
                        periodSelectorBorderColor = Palette.hairline,
                        timeSelectorSelectedContainerColor = Palette.accentMuted,
                        timeSelectorUnselectedContainerColor = Palette.surfaceInset,
                        timeSelectorSelectedContentColor = accentColor,
                        timeSelectorUnselectedContentColor = Palette.textPrimary,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Text(
                        "Cancel",
                        style = NoopType.body,
                        color = Palette.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { showPicker = false }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        "Set",
                        style = NoopType.body,
                        color = accentColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                onPicked(state.hour * 60 + state.minute)
                                showPicker = false
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// MARK: - Steppers

/** Pre-wake window stepper: 5-60 min in 5-min steps. */
@Composable
private fun PreWakeWindowStepper(
    preWakeWindowMinutes: Int,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(AlarmStepperWidth),
    ) {
        InlineStepperButton(
            symbol = "-",
            enabled = enabled,
            onClick = { onChange((preWakeWindowMinutes - 5).coerceAtLeast(5)) },
            label = "Shorten pre-wake window",
        )
        StepperValue("$preWakeWindowMinutes min", enabled = enabled)
        InlineStepperButton(
            symbol = "+",
            enabled = enabled,
            onClick = { onChange((preWakeWindowMinutes + 5).coerceAtMost(60)) },
            label = "Lengthen pre-wake window",
        )
    }
}

@Composable
private fun InlineStepperButton(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Palette.surfaceInset else Palette.hairline)
            .border(1.dp, Palette.hairline, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = NoopType.body,
            color = if (enabled) Palette.textPrimary else Palette.textTertiary,
        )
    }
}

/** Phone-backup-delay stepper: 1-15 min in 1-min steps. */
@Composable
private fun PhoneBackupStepper(phoneBackupDelayMinutes: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(AlarmStepperWidth),
    ) {
        StepperButton(symbol = "-", onClick = { onChange((phoneBackupDelayMinutes - 1).coerceAtLeast(1)) }, label = "Decrease backup delay")
        StepperValue("$phoneBackupDelayMinutes min", enabled = true)
        StepperButton(symbol = "+", onClick = { onChange((phoneBackupDelayMinutes + 1).coerceAtMost(15)) }, label = "Increase backup delay")
    }
}

@Composable
private fun StepperValue(value: String, enabled: Boolean) {
    Box(
        modifier = Modifier.width(AlarmStepperValueWidth),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value,
            style = NoopType.bodyNumber,
            color = if (enabled) Palette.textPrimary else Palette.textTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// MARK: - Local toggle / divider helpers

@Composable
private fun ToggleRowLocal(
    label: String,
    help: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    // When true, the switch is parked in the shared editor control column for vertical
    // alignment with the other settings rows. The wind-down card and any other free-standing
    // use of this composable leaves this false so it doesn't get the alarm-editor alignment.
    alignToControlColumn: Boolean = false,
    accentColor: Color = Palette.accent,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = NoopType.body, color = Palette.textPrimary)
            if (help.isNotEmpty()) {
                Text(help, style = NoopType.footnote, color = Palette.textTertiary)
            }
        }
        Spacer(Modifier.width(16.dp))
        val switch: @Composable () -> Unit = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.surfaceBase,
                    checkedTrackColor = accentColor,
                    uncheckedThumbColor = Palette.textSecondary,
                    uncheckedTrackColor = Palette.surfaceInset,
                    uncheckedBorderColor = Palette.hairline,
                ),
            )
        }
        if (alignToControlColumn) {
            Box(
                modifier = Modifier.width(EditorControlColumnWidth),
                contentAlignment = Alignment.CenterEnd,
            ) { switch() }
        } else {
            switch()
        }
    }
}

@Composable
private fun RowDividerLocal() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.hairline.copy(alpha = 0.45f)),
    )
}

// MARK: - Previews

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
internal fun SmartAlarmScreenPreview_Empty() {
    // Empty state preview - no alarms
    EmptyStateCard(onAdd = {})
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
internal fun SmartAlarmScreenPreview_AlarmRow() {
    val alarm = UnifiedAlarm(
        id = "preview-1",
        enabled = true,
        wakeMinutes = 6 * 60 + 30,
        weekdays = setOf(2, 3, 4, 5, 6),
        source = AlarmSource.STRAP,
        smartWake = false,
        preWakeWindowMinutes = 30,
        phoneBackupDelayMinutes = 5,
    )
    AlarmRow(
        alarm = alarm,
        nowMs = System.currentTimeMillis(),
        isExpanded = false,
        is24hHighlight = true,
        strapStatus = StrapArmStatus("preview-1", StrapArmState.ARMED),
        onExpandToggle = {},
        onToggle = {},
        onUpdate = {},
        onDelete = {},
    )
}
