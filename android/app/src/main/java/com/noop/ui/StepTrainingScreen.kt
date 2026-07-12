package com.noop.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.StepMotionCounter
import com.noop.ble.WhoopModel
import com.noop.data.StepRow
import com.noop.data.StepTrainingStore
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Interactive step calibration for WHOOP 5/MG.
 *
 * Prefer the live BLE type-47 v18 counter ([LiveState.liveStepCounter]) so training works even
 * before history banks. Tap sessions update ticks/step; shake sessions update the still-noise floor.
 */
@Composable
fun StepTrainingScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val store = remember { StepTrainingStore.from(context) }
    val profile = remember { ProfileStore.from(context) }
    val live by vm.live.collectAsStateWithLifecycle()
    val selected by vm.selectedModel.collectAsStateWithLifecycle()
    val isMg = selected == WhoopModel.WHOOP5_MG

    var sessions by remember { mutableStateOf(store.loadSessions()) }
    var model by remember { mutableStateOf(store.learnedModel(profile.stepTicksPerStep)) }
    var mode by remember { mutableStateOf<String?>(null) } // null | tap | shake
    var taps by remember { mutableIntStateOf(0) }
    var startCounter by remember { mutableStateOf<Int?>(null) }
    var liveCounter by remember { mutableStateOf<Int?>(null) }
    var sessionSamples by remember { mutableStateOf<List<StepRow>>(emptyList()) }
    var startMs by remember { mutableLongStateOf(0L) }
    var pulse by remember { mutableFloatStateOf(1f) }
    var feedback by remember { mutableStateOf("Pick a mode. Wear your WHOOP MG and keep Live connected.") }
    var shakeHits by remember { mutableIntStateOf(0) }

    // Prefer BLE live counter; fall back to banked DB samples.
    LaunchedEffect(mode, live.connected, live.liveStepCounter) {
        while (true) {
            val now = System.currentTimeMillis() / 1000
            val ble = live.liveStepCounter
            val sample = if (ble != null) {
                StepRow(now, ble, live.liveActivityClass)
            } else {
                runCatching {
                    val dayStart = java.time.LocalDate.now()
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()
                    val samples = vm.repo.stepSamples(vm.activeStrapId, dayStart, now, 80)
                    samples.maxByOrNull { it.ts }?.let { StepRow(it.ts, it.counter, it.activityClass) }
                }.getOrNull()
            }
            liveCounter = sample?.counter
            if (mode != null && startCounter == null && sample != null) startCounter = sample.counter
            if (mode != null && sample != null) {
                sessionSamples = (sessionSamples + sample).distinctBy { it.ts }.takeLast(800)
            }
            delay(500)
        }
    }

    DisposableEffect(mode) {
        if (mode != "shake") return@DisposableEffect onDispose { }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastHit = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()
                if (g > 1.85f && now - lastHit > 280) {
                    lastHit = now
                    shakeHits++
                    pulse = 1.12f
                    feedback = "Shake $shakeHits · strap ticks keep counting (noise floor sample)"
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (accel != null) sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    val scale by animateFloatAsState(pulse, animationSpec = tween(160), label = "pulse") {
        if (pulse != 1f) pulse = 1f
    }

    val ticksDelta = if (startCounter != null && liveCounter != null) {
        (liveCounter!! - startCounter!!).let { d -> if (d < 0) d + 65536 else d }
    } else 0
    val accepted = StepMotionCounter.accumulate(sessionSamples, mode = StepMotionCounter.Mode.Training)
    // Prefer filter-accepted ticks; if class@63 zeroes everything, fall back to raw endpoint delta.
    val trainTicks = when {
        accepted.acceptedTicks >= 1.0 -> accepted.acceptedTicks
        ticksDelta > 0 -> ticksDelta.toDouble()
        else -> 0.0
    }
    val liveStepsEst = if (trainTicks > 0 && profile.stepTicksPerStep > 0) {
        (trainTicks / profile.stepTicksPerStep).roundToInt()
    } else null

    fun endSession() {
        val m = mode ?: return
        val durationSec = ((System.currentTimeMillis() - startMs).coerceAtLeast(1L) / 1000.0)
        val labeled = if (m == "tap") taps else 0
        val ratio = if (m == "tap" && labeled >= 8 && trainTicks > 0) trainTicks / labeled else null
        val noise = if (m == "shake" && durationSec >= 3.0 && ticksDelta >= 0) {
            ticksDelta / durationSec
        } else null
        val session = StepTrainingStore.Session(
            id = System.currentTimeMillis(),
            mode = m,
            startedAtMs = startMs,
            endedAtMs = System.currentTimeMillis(),
            strapTicks = ticksDelta,
            labeledSteps = labeled,
            ticksPerStep = ratio,
            acceptedTicks = trainTicks,
            rejectedTicks = accepted.rejectedTicks,
            acceptedPairs = accepted.acceptedPairs,
            noiseTicksPerSec = noise,
        )
        store.addSession(session)
        val learned = store.learnedModel(profile.stepTicksPerStep)
        model = learned
        if (ratio != null) {
            profile.stepTicksPerStep = learned.ticksPerStep
            vm.applyStepCalibration()
            feedback = "Model updated → k=${"%.2f".format(learned.ticksPerStep)} ticks/step " +
                "(${learned.confidence.lowercase()}, ${learned.labeledSteps} labelled steps). " +
                "Daily steps rescore now."
        } else if (m == "shake" && noise != null) {
            vm.applyStepCalibration()
            feedback = "Noise floor updated → ${"%.2f".format(learned.noiseFloorTicksPerSec)} ticks/s " +
                "from shake (filters still-creep in daily totals). Does not change k."
        } else if (m == "shake") {
            feedback = "Shake too short — hold ~5s of shaking while still."
        } else {
            feedback = "Need ≥8 taps and moving strap ticks. Walk + tap; keep MG connected (live counter)."
        }
        sessions = store.loadSessions()
        mode = null
        taps = 0
        shakeHits = 0
        sessionSamples = emptyList()
        startCounter = null
        startMs = 0L
    }

    LazyScreenScaffold(
        title = "Step training",
        subtitle = if (isMg) "WHOOP MG · live @57 counter + learned model" else "Works best on WHOOP 5/MG",
        topBackground = { LiquidScreenSky() },
    ) {
        item {
            GlowCard(tint = Palette.effortColor) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Overline("Live strap counter")
                    Text(
                        liveCounter?.toString() ?: "—",
                        style = NoopType.number(42f),
                        color = if (liveCounter != null) Palette.effortColor else Palette.textTertiary,
                    )
                    Text(
                        when {
                            !live.connected -> "Connect your WHOOP — training needs the live motion counter."
                            live.liveStepCounter != null -> "Live type-47 v18 @57 (BLE notify). Source of truth for this session."
                            liveCounter != null -> "From banked samples (waiting for live v18 frames…)."
                            else -> "Connected — walk a few steps so the counter appears."
                        },
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MiniStat("Model k", String.format(java.util.Locale.US, "%.2f", model.ticksPerStep), modifier = Modifier.weight(1f))
                        MiniStat("Noise/s", String.format(java.util.Locale.US, "%.2f", model.noiseFloorTicksPerSec), modifier = Modifier.weight(1f))
                        MiniStat("Accepted", if (mode != null) "${trainTicks.roundToInt()}" else "—", modifier = Modifier.weight(1f))
                        MiniStat("Est steps", liveStepsEst?.toString() ?: "—", modifier = Modifier.weight(1f))
                    }
                    Text(
                        "Confidence: ${model.confidence} · ${model.labeledSteps} labelled steps · " +
                            "${model.sessions} tap sessions · ${model.shakeSessions} shake" +
                            if (store.needsWeeklyRetrain()) " · retrain due" else "",
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                }
            }
        }

        if (mode == null) {
            item {
                // Impeccable: primary actions first, short copy, live counter state visible.
                NoopCard(tint = Palette.effortColor) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Train steps", style = NoopType.headline, color = Palette.textPrimary)
                            StatePill(
                                title = if (liveCounter != null) "Counter live" else "No counter",
                                tone = if (liveCounter != null) StrandTone.Positive else StrandTone.Warning,
                                showsDot = liveCounter != null,
                                pulsing = live.connected && liveCounter == null,
                            )
                        }
                        Text(
                            "Learn personal k from taps; noise floor from shake. Never invents daily steps.",
                            style = NoopType.subhead,
                            color = Palette.textSecondary,
                        )
                        WetBounceButton(
                            label = "Tap walk · train k",
                            modifier = Modifier.fillMaxWidth(),
                            tint = Palette.effortColor,
                            onClick = {
                                mode = "tap"; taps = 0; sessionSamples = emptyList()
                                startCounter = liveCounter; startMs = System.currentTimeMillis()
                                feedback = "Walk and tap once per step. Aim for 20–40 taps, then Save."
                            },
                        )
                        WetBounceButton(
                            label = "Shake still · noise floor",
                            modifier = Modifier.fillMaxWidth(),
                            tint = Palette.metricAmber,
                            onClick = {
                                mode = "shake"; shakeHits = 0; sessionSamples = emptyList()
                                startCounter = liveCounter; startMs = System.currentTimeMillis()
                                feedback = "Shake while standing still for ~5–15 seconds, then Save."
                            },
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ModeCard(
                        title = "Tap train",
                        body = "Walk. Tap once per real step. Updates ticks/step (k) used for daily totals.",
                        icon = Icons.Filled.TouchApp,
                        modifier = Modifier.weight(1f),
                    ) {
                        mode = "tap"; taps = 0; sessionSamples = emptyList()
                        startCounter = liveCounter; startMs = System.currentTimeMillis()
                        feedback = "Walk and tap once per step. Aim for 20–40 taps."
                    }
                    ModeCard(
                        title = "Shake train",
                        body = "Stand still and shake. Learns noise floor so fidget does not inflate steps.",
                        icon = Icons.Filled.Vibration,
                        modifier = Modifier.weight(1f),
                    ) {
                        mode = "shake"; shakeHits = 0; sessionSamples = emptyList()
                        startCounter = liveCounter; startMs = System.currentTimeMillis()
                        feedback = "Shake while standing still for ~5–15 seconds, then Save."
                    }
                }
            }
        } else {
            item {
                GlowCard(tint = Palette.metricAmber) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (mode == "tap") "TAP once per step" else "SHAKE (noise sample)",
                            style = NoopType.title2,
                            color = Palette.textPrimary,
                        )
                        Text(feedback, style = NoopType.subhead, color = Palette.textSecondary, textAlign = TextAlign.Center)
                        if (mode == "tap") {
                            Box(
                                modifier = Modifier
                                    .size(160.dp)
                                    .scale(scale)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(Palette.effortColor.copy(0.45f), Palette.effortColor.copy(0.12f)),
                                        ),
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        taps++
                                        pulse = 1.15f
                                        feedback = "Tap $taps · train ticks ${trainTicks.roundToInt()} · live k≈" +
                                            if (taps > 0 && trainTicks > 0) "%.2f".format(trainTicks / taps)
                                            else "…"
                                    }
                                    .semantics { contentDescription = "Tap for step" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = Palette.effortColor, modifier = Modifier.size(36.dp))
                                    Text("$taps", style = NoopType.number(40f), color = Palette.textPrimary)
                                    Text("taps", style = NoopType.footnote, color = Palette.textTertiary)
                                }
                            }
                        } else {
                            Text("Shake bursts: $shakeHits", style = NoopType.number(28f), color = Palette.metricAmber)
                            Text(
                                "Raw $ticksDelta ticks · ${"%.1f".format((System.currentTimeMillis() - startMs) / 1000.0)}s · " +
                                    "noise≈${if (startMs > 0) "%.2f".format(ticksDelta / ((System.currentTimeMillis() - startMs).coerceAtLeast(1) / 1000.0)) else "—"}/s",
                                style = NoopType.subhead,
                                color = Palette.textSecondary,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = {
                                mode = null; taps = 0; shakeHits = 0; sessionSamples = emptyList(); startCounter = null
                                feedback = "Cancelled."
                            }) { Text("Cancel") }
                            NoopButton(
                                text = "Save session",
                                kind = NoopButtonKind.Primary,
                                onClick = { endSession() },
                            )
                        }
                    }
                }
            }
        }

        item {
            GlowCard(tint = Palette.restColor) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Overline("Recent sessions")
                    if (sessions.isEmpty()) {
                        Text("No sessions yet — train once, then again each week.", style = NoopType.subhead, color = Palette.textSecondary)
                    } else {
                        sessions.takeLast(10).asReversed().forEach { s ->
                            Text(
                                when (s.mode) {
                                    "tap" -> "TAP · ticks ${s.acceptedTicks.roundToInt()} · steps ${s.labeledSteps}" +
                                        (s.ticksPerStep?.let { " · k=${"%.2f".format(it)}" } ?: "")
                                    else -> "SHAKE · ticks ${s.strapTicks}" +
                                        (s.noiseTicksPerSec?.let { " · noise=${"%.2f".format(it)}/s" } ?: "")
                                },
                                style = NoopType.footnote,
                                color = Palette.textSecondary,
                            )
                        }
                    }
                    Text(
                        "Honest scope: calibrates WHOOP motion counter vs your labels. " +
                            "Does not invent SpO₂ or blood pressure.",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    body: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .frostedCardSurface(tint = Palette.effortColor, cornerRadius = 18.dp)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Palette.effortColor, modifier = Modifier.size(22.dp))
        Text(title, style = NoopType.headline, color = Palette.textPrimary)
        Text(body, style = NoopType.footnote, color = Palette.textTertiary)
    }
}
