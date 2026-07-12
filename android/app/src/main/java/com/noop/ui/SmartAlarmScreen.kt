package com.noop.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Smart alarm (#207) — Android phone-based wake, with a guaranteed hard-deadline fallback.
 *
 * The user picks the EARLIEST acceptable wake time and a window length. NOOP watches the overnight
 * strap stream and, if it spots a lighter sleep phase inside the window, wakes you then — but a
 * GUARANTEED exact OS alarm is always scheduled at the window's END (via AlarmManager), independent
 * of Bluetooth, the strap, or the app being alive. The smart logic can only ever move the alarm
 * EARLIER; it can never cancel or skip the fallback. So you're woken by the window's end no matter
 * what. This screen is explicit about that safety guarantee.
 *
 * This is the ONE alarm surface (#766). It hosts the phone-based Wake Window above, the strap's own
 * standalone firmware wake-alarm (moved here from Automations), and the cross-platform WIND-DOWN nudge,
 * so every wake/alarm control lives together instead of being split across two screens.
 */
@Composable
fun SmartAlarmScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val enabled by vm.phoneAlarmEnabled.collectAsStateWithLifecycle()
    val targetMinutes by vm.phoneAlarmTargetMinutes.collectAsStateWithLifecycle()
    val windowMinutes by vm.phoneAlarmWindowMinutes.collectAsStateWithLifecycle()
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val buzzWhoop4 by vm.buzzWhoop4Enabled.collectAsStateWithLifecycle()
    // #536: the hint adapts to bond state — the strap can only be armed when a WHOOP 4.0 is connected.
    val strapState by vm.live
        .map { state -> AlarmStrapState(state.bonded, state.whoop5Detected) }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = AlarmStrapState(false, false))
    val bonded = strapState.bonded
    // #821: the strap-buzz row was hardcoded to "WHOOP 4", which reads wrong on a connected 5/MG (issue
    // #730 follow-up). Name the actual strap generation instead: a detected 5/MG says "WHOOP 5/MG", anything
    // else (a 4.0, or nothing connected yet) keeps "WHOOP 4.0", so the label never claims the wrong device.
    val strapName = if (strapState.whoop5Detected) "WHOOP 5/MG" else "WHOOP 4.0"

    // True when exact alarms are permitted. Re-read on each (re)composition because the user can grant
    // it in Settings and come back — there's no result callback for this special-access permission.
    var canSchedule by remember { mutableStateOf(vm.canScheduleExactAlarms()) }

    // PERF (#707): lazy scaffold — each of the four cards is one `item { }` (all unconditional). Order +
    // spacing unchanged (LazyColumn reproduces the eager `spacedBy(20.dp)`); only on-screen cards compose +
    // are accessibility-walked.
    LazyScreenScaffold(
        // #766: "Alarms" because this screen now holds the phone Wake Window, the strap's firmware
        // wake-alarm (moved here from Automations), and the wind-down reminder, so the broader title fits.
        title = "Alarm",
        subtitle = "Wake window, strap buzz, wind-down.",
    ) {
        // The guaranteed-wake card always shows so the safety promise is the first thing read.
        item { WindowCard(enabled = enabled, targetMinutes = targetMinutes, windowMinutes = windowMinutes) }
        item { PersonalSleepPlanCard(days = days, targetMinutes = targetMinutes) }

        item {
        AlarmSettingsCard {
            ToggleRowLocal(
                label = "Wake me up",
                help = "NOOP sets a guaranteed phone alarm and can use an early HR-based cue inside your chosen window. It does not diagnose sleep stages.",
                checked = enabled,
                onChange = { want ->
                    if (want && !vm.canScheduleExactAlarms()) {
                        // No callback for this special-access grant — send the user to the system page,
                        // and re-read the state when they return (canSchedule recomputes on recompose).
                        requestExactAlarmAccess(context)
                        canSchedule = vm.canScheduleExactAlarms()
                    } else {
                        val ok = vm.setPhoneAlarmEnabled(want)
                        canSchedule = vm.canScheduleExactAlarms()
                        if (!ok) requestExactAlarmAccess(context)
                    }
                },
            )

            if (enabled && !canSchedule) {
                RowDividerLocal()
                Text(
                    "NOOP doesn't have permission to set exact alarms, so your wake isn't guaranteed. " +
                        "Tap to allow it in system settings.",
                    style = NoopType.footnote,
                    color = Palette.statusWarning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            requestExactAlarmAccess(context)
                            canSchedule = vm.canScheduleExactAlarms()
                        },
                )
            }

            if (enabled) {
                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Wake window starts", style = NoopType.body, color = Palette.textPrimary)
                        Text("An early cue is possible from here when live HR changes. The deadline alarm is always kept.", style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    Spacer(Modifier.width(16.dp))
                    TimeChip(
                        minutes = targetMinutes,
                        accessibilityLabel = "Earliest wake time",
                        onPicked = { vm.setPhoneAlarmTargetMinutes(it) },
                    )
                }

                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Window length", style = NoopType.body, color = Palette.textPrimary)
                        Text(
                            "The phone alarm fires at the end if the strap or HR stream does not wake you first.",
                            style = NoopType.footnote, color = Palette.textTertiary,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    WindowStepper(
                        windowMinutes = windowMinutes,
                        onChange = { vm.setPhoneAlarmWindowMinutes(it) },
                    )
                }
            }

            // #536: companion strap-buzz, always visible so it's discoverable. Arms the strap's own firmware
            // alarm at the earliest wake time, so the strap buzzes first and the OS alarm backs it up.
            // #821: label + copy name the CONNECTED strap generation (strapName), not a hardcoded "WHOOP 4".
            RowDividerLocal()
            ToggleRowLocal(
                label = "Buzz connected strap",
                help = if (bonded)
                    "Arms your $strapName to buzz with this alarm."
                else
                    "Connect your WHOOP 3/4/MG to use strap buzz. The phone alarm still works as the backup.",
                checked = buzzWhoop4,
                onChange = { vm.setBuzzWhoop4Enabled(it) },
            )

            RowDividerLocal()
            val turnBack by vm.turnBackEnabled.collectAsStateWithLifecycle()
            val turnBackWatch by vm.turnBackWatchMinutes.collectAsStateWithLifecycle()
            val turnBackDrop by vm.turnBackDropBpm.collectAsStateWithLifecycle()
            val turnBackPhone by vm.turnBackPhoneCue.collectAsStateWithLifecycle()
            ToggleRowLocal(
                label = "Turn-back alarm",
                help = "After you wake, if your heart rate rises then falls again (likely dozing), NOOP cues you once more. Coarse HR heuristic — not sleep-stage detection.",
                checked = turnBack,
                onChange = { vm.setTurnBackEnabled(it) },
            )
            if (turnBack) {
                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Watch after wake", style = NoopType.body, color = Palette.textPrimary)
                        Text("How long to keep watching live HR.", style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StepperButton(
                            symbol = "−",
                            onClick = { vm.setTurnBackWatchMinutes((turnBackWatch - 5).coerceAtLeast(15)) },
                            label = "Shorter watch",
                        )
                        Text("$turnBackWatch min", style = NoopType.bodyNumber, color = Palette.textPrimary)
                        StepperButton(
                            symbol = "+",
                            onClick = { vm.setTurnBackWatchMinutes((turnBackWatch + 5).coerceAtMost(90)) },
                            label = "Longer watch",
                        )
                    }
                }
                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("HR drop to cue", style = NoopType.body, color = Palette.textPrimary)
                        Text("$turnBackDrop bpm below your post-wake high.", style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StepperButton(symbol = "−", onClick = { vm.setTurnBackDropBpm(turnBackDrop - 1) }, label = "Less sensitive")
                        Text("$turnBackDrop", style = NoopType.bodyNumber, color = Palette.textPrimary)
                        StepperButton(symbol = "+", onClick = { vm.setTurnBackDropBpm(turnBackDrop + 1) }, label = "More sensitive")
                    }
                }
                RowDividerLocal()
                ToggleRowLocal(
                    label = "Phone cue too",
                    help = "Also fire a phone notification when turn-back triggers (strap buzz always tries).",
                    checked = turnBackPhone,
                    onChange = { vm.setTurnBackPhoneCue(it) },
                )
            }

            RowDividerLocal()
            val wakeRested by vm.wakeWhenRested.collectAsStateWithLifecycle()
            val restedCharge by vm.restedChargeThreshold.collectAsStateWithLifecycle()
            val restedSleepPct by vm.restedSleepNeedPercent.collectAsStateWithLifecycle()
            ToggleRowLocal(
                label = "Wake when rested",
                help = "Inside your window, wake early once sleep need looks met or Charge is already green. Hard deadline still stands.",
                checked = wakeRested,
                onChange = { vm.setWakeWhenRested(it) },
            )
            if (wakeRested) {
                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Charge threshold", style = NoopType.body, color = Palette.textPrimary)
                        Text("Wake if overnight Charge is at least this.", style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StepperButton(symbol = "−", onClick = { vm.setRestedChargeThreshold(restedCharge - 1) }, label = "Lower threshold")
                        Text("$restedCharge", style = NoopType.bodyNumber, color = Palette.textPrimary)
                        StepperButton(symbol = "+", onClick = { vm.setRestedChargeThreshold(restedCharge + 1) }, label = "Higher threshold")
                    }
                }
                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Sleep need met", style = NoopType.body, color = Palette.textPrimary)
                        Text("$restedSleepPct% of your recent average night.", style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StepperButton(symbol = "−", onClick = { vm.setRestedSleepNeedPercent(restedSleepPct - 5) }, label = "Lower percent")
                        Text("$restedSleepPct%", style = NoopType.bodyNumber, color = Palette.textPrimary)
                        StepperButton(symbol = "+", onClick = { vm.setRestedSleepNeedPercent(restedSleepPct + 5) }, label = "Higher percent")
                    }
                }
            }
        }
        }

        item { CustomAlarmsCard(vm) }

        item { StrapAlarmCard(vm, strapState) }

        // The cross-platform wind-down nudge lives here too.
        item { WindDownCard(vm) }

        // #821: the "how the smart wake works" explainer sat in the MIDDLE of the page (between the wake-alarm
        // settings and the strap alarm), which read as an interruption. It's reference detail, not a control,
        // so it belongs at the BOTTOM after every alarm/reminder control, moved here.
        item { ExplanationCard() }
    }
}

private data class AlarmStrapState(val bonded: Boolean, val whoop5Detected: Boolean)

/**
 * Classic exact-time phone alarms — flat list, no nested chrome. Uses the same weekday picker
 * vocabulary as the strap alarm.
 */
@Composable
private fun CustomAlarmsCard(vm: AppViewModel) {
    val alarms by vm.customAlarms.collectAsStateWithLifecycle()
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Overline("Exact time")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Alarm, contentDescription = null, tint = Palette.accent)
                    Spacer(Modifier.width(10.dp))
                    Text("Custom alarms", style = NoopType.title2, color = Palette.textPrimary)
                }
                Text(
                    "Up to five exact phone alarms. Separate from the smart wake window.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            alarms.forEach { alarm ->
                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(alarm.label, style = NoopType.body, color = Palette.textPrimary)
                        AlarmWeekdayPicker(
                            selected = alarm.weekdays,
                            onToggle = { dow ->
                                vm.upsertCustomAlarm(
                                    alarm.copy(weekdays = toggledSmartAlarmWeekday(dow, alarm.weekdays)),
                                )
                            },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    TimeChip(
                        minutes = alarm.minutes,
                        accessibilityLabel = "${alarm.label} time",
                        onPicked = { vm.upsertCustomAlarm(alarm.copy(minutes = it)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = alarm.enabled,
                        onCheckedChange = { vm.upsertCustomAlarm(alarm.copy(enabled = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                    )
                }
                Text(
                    "Remove",
                    style = NoopType.footnote,
                    color = Palette.statusCritical,
                    modifier = Modifier
                        .clickable { vm.deleteCustomAlarm(alarm.id) }
                        .padding(top = 4.dp),
                )
            }
            if (alarms.size < com.noop.alarm.SmartAlarmStore.MAX_CUSTOM_ALARMS) {
                RowDividerLocal()
                Text(
                    "Add alarm",
                    style = NoopType.body,
                    color = Palette.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.upsertCustomAlarm(
                                com.noop.alarm.CustomAlarm(
                                    label = "Alarm ${alarms.size + 1}",
                                    minutes = 7 * 60,
                                ),
                            )
                        }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * The strap's standalone silent wake-alarm (#766, moved from AutomationsScreen). Arms the strap's own
 * firmware alarm at the chosen time/weekdays over BLE, so it buzzes even if NOOP is closed. Reuses the
 * shared [AlarmWeekdayPicker] / [AlarmDayOverridePicker] from AutomationsScreen (same behaviour, just a
 * new home). Functions are untouched: it drives the same `viewModel.setSmartAlarm*` calls as before.
 */
@Composable
private fun StrapAlarmCard(vm: AppViewModel, strapState: AlarmStrapState) {
    val smartAlarm by vm.smartAlarmEnabled.collectAsStateWithLifecycle()
    val alarmMinutes by vm.smartAlarmMinutes.collectAsStateWithLifecycle()
    val alarmWeekdays by vm.smartAlarmWeekdays.collectAsStateWithLifecycle()
    val alarmDayOverrides by vm.smartAlarmDayOverrides.collectAsStateWithLifecycle()

    NoopCard(padding = 20.dp, tint = null) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Overline("Morning")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Alarm, contentDescription = null, tint = Palette.accent)
                    Spacer(Modifier.width(10.dp))
                    Text("Strap wake-alarm", style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            // Truth-sync (#535): the WHOOP 4.0 alarm payload was captured from the official app and
            // confirmed buzzing on a real 4.0 by the capture author, so the copy no longer calls the
            // 4.0 path experimental. The 5/MG Experimental-gate branch below is deliberately untouched.
            ToggleRowLocal(
                label = "Wake me with a strap buzz",
                help = "Arms the strap to buzz at your wake time, even if NOOP is closed. Sends the exact alarm command the official app sends, confirmed buzzing on a real WHOOP 4.0 (community wire capture + on-device test, #535). Keep a backup alarm for anything you truly can't miss.",
                checked = smartAlarm,
                onChange = { vm.setSmartAlarmEnabled(it) },
            )
            if (smartAlarm) {
                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Wake at", style = NoopType.body, color = Palette.textPrimary)
                    Spacer(Modifier.weight(1f))
                    TimeChip(
                        minutes = alarmMinutes,
                        accessibilityLabel = "Strap alarm wake time",
                        onPicked = { vm.setSmartAlarmMinutes(it) },
                    )
                }
                RowDividerLocal()
                AlarmWeekdayPicker(
                    selected = alarmWeekdays,
                    onToggle = { dow -> vm.setSmartAlarmWeekdays(toggledSmartAlarmWeekday(dow, alarmWeekdays)) },
                )
                RowDividerLocal()
                // Per-weekday wake-time OVERRIDES (#554): a different time for any day the alarm fires on.
                AlarmDayOverridePicker(
                    defaultMinutes = alarmMinutes,
                    enabledDays = alarmWeekdays,
                    overrides = alarmDayOverrides,
                    onSetOverride = { dow, minutes -> vm.setSmartAlarmDayOverride(dow, minutes) },
                )
                RowDividerLocal()
                if (strapState.whoop5Detected) {
                    // 5/MG with Experimental ON: the strap IS armed (experimental rev-4 payload) but a
                    // strap-driven wake has NEVER been captured on 5/MG, so the "confirmed on 4.0" copy must
                    // NOT show here (#864 honesty). Byte-identical wording to the Swift SmartAlarmView twin.
                    Text(
                        if (strapState.bonded)
                            "Armed on the strap itself with the acknowledged 5/MG command. Keep the phone alarm on as backup for anything you truly can't miss."
                        else
                            "Connect your strap to arm this; it's set on the strap's own firmware alarm. Keep the phone alarm on as backup.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                } else {
                    Text(
                        if (strapState.bonded)
                            // Truth-sync (#535): confirmed buzzing on a real WHOOP 4.0; byte-identical
                            // wording to the Swift SmartAlarmView.
                            "Armed on the strap itself, so it can buzz at your wake time even if your phone is asleep or NOOP is closed. Sends the exact alarm command the official app sends, confirmed buzzing on a real WHOOP 4.0 (community wire capture + on-device test, #535). Keep a backup alarm for anything you truly can't miss."
                        else
                            "Connect your strap to arm this; it's set on the strap's own firmware alarm. Confirmed working on WHOOP 4.0; still experimental on 5.0 and MG. Keep a backup alarm for anything you truly can't miss.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

// MARK: - Cards

/**
 * The always-visible "you WILL be woken by" guarantee card — a small Rest-world frosted hero. The
 * wake window reads as a clean earliest→deadline time pairing in big rounded numerals over a scenic
 * Rest backdrop (it's about waking, so it lives in the indigo world, not the brand-green chrome).
 */
@Composable
private fun WindowCard(enabled: Boolean, targetMinutes: Int, windowMinutes: Int) {
    val deadline = (targetMinutes + windowMinutes) % (24 * 60)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.cardRadius)),
    ) {
        ScenicHeroBackground(modifier = Modifier.matchParentSize(), domain = DomainTheme.Rest)
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = DomainTheme.Rest.color)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Overline("Guaranteed wake")
                if (enabled) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(hhmm(targetMinutes), style = NoopType.number(28f), color = DomainTheme.Rest.color)
                        Text("→", style = NoopType.title2, color = Palette.textTertiary)
                        Text(hhmm(deadline), style = NoopType.number(28f), color = DomainTheme.Rest.bright)
                    }
                    Text(
                        "A backup alarm is set for ${hhmm(deadline)}. It fires even if Bluetooth drops, the strap isn't worn, or NOOP is closed.",
                        style = NoopType.footnote, color = Palette.textSecondary,
                    )
                } else {
                    Text("Off", style = NoopType.title2, color = Palette.textSecondary)
                    Text(
                        "Turn on the smart alarm to wake inside a window you choose.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalSleepPlanCard(days: List<com.noop.data.DailyMetric>, targetMinutes: Int) {
    val nights = days.mapNotNull { it.totalSleepMin?.takeIf { minutes -> minutes > 0.0 } }.takeLast(28)
    val learnedNeed = nights.takeIf { it.size >= 3 }?.average()?.coerceAtLeast(450.0)
    val bedtime = learnedNeed?.let { ((targetMinutes - it.roundToInt()) % (24 * 60) + (24 * 60)) % (24 * 60) }
    // Flat on sky — no Rest-tinted card wash for a schedule cue.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Your sleep plan", style = NoopType.subhead, color = Palette.textPrimary)
        if (bedtime != null) {
            Text(
                "For a ${hhmm(targetMinutes)} wake, wind down around ${hhmm(bedtime)}.",
                style = NoopType.title2,
                color = DomainTheme.Rest.bright,
            )
            Text(
                "${nights.size} nights · avg ${durationLabel(learnedNeed!!)} asleep. Schedule cue, not a health target.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        } else {
            Text("Record three nights for a personal bedtime cue.", style = NoopType.footnote, color = Palette.textSecondary)
        }
    }
}

@Composable
private fun AlarmSettingsCard(content: @Composable () -> Unit) {
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Alarm, contentDescription = null, tint = Palette.accent)
                Spacer(Modifier.width(10.dp))
                Text("Wake alarm", style = NoopType.headline, color = Palette.textPrimary)
            }
            content()
        }
    }
}

/** The cross-platform evening wind-down nudge — a gentle reminder, not an alarm. Rest-tinted when on. */
@Composable
private fun WindDownCard(vm: AppViewModel) {
    val enabled by vm.windDownEnabled.collectAsStateWithLifecycle()
    NoopCard(padding = 20.dp, tint = null) {
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

@Composable
private fun ExplanationCard() {
    // Flat footnote — no nested card chrome for reference copy.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("How smart wake works", style = NoopType.subhead, color = Palette.textPrimary)
        Text(
            "Inside the window, a rise from the lowest stable heart-rate readings may cue an early wake. " +
                "That is a coarse HR cue, not sleep-stage detection. If the strap is not streaming, only the " +
                "guaranteed end-of-window alarm fires.",
            style = NoopType.footnote, color = Palette.textTertiary,
        )
    }
}

// MARK: - Window stepper (5–60 min in 5-min steps)

@Composable
private fun WindowStepper(windowMinutes: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepperButton(symbol = "−", onClick = { onChange((windowMinutes - 5).coerceAtLeast(5)) }, label = "Shorten window")
        Text("$windowMinutes min", style = NoopType.bodyNumber, color = Palette.textPrimary)
        StepperButton(symbol = "+", onClick = { onChange((windowMinutes + 5).coerceAtMost(60)) }, label = "Lengthen window")
    }
}

// MARK: - Local toggle / divider (mirror the AutomationsScreen idiom, kept local to this lane's file)

@Composable
private fun ToggleRowLocal(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = NoopType.body, color = Palette.textPrimary)
            Text(help, style = NoopType.footnote, color = Palette.textTertiary)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.surfaceBase,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.textSecondary,
                uncheckedTrackColor = Palette.surfaceInset,
                uncheckedBorderColor = Palette.hairline,
            ),
        )
    }
}

@Composable
private fun RowDividerLocal() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.hairline),
    )
}

// MARK: - Helpers

private fun hhmm(minutes: Int): String {
    val m = ((minutes % (24 * 60)) + 24 * 60) % (24 * 60)
    return "%02d:%02d".format(m / 60, m % 60)
}

private fun durationLabel(minutes: Double): String = "%dh %02dm".format(minutes.toInt() / 60, minutes.toInt() % 60)

/** Open the system page where the user grants the exact-alarm special-access permission (API 31+).
 *  There's no runtime dialog for this; the user toggles it in Settings and returns. */
private fun requestExactAlarmAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        // Fall back to the app-details page if the OEM lacks the specific action.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
