package com.noop.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.noop.BuildConfig
import com.noop.analytics.BatteryEstimator
import com.noop.ble.WhoopModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Capture activity UI under a Dialog window, then smash into frosted glass
 * (downsample/upsample). Dialog LocalContext is often not an Activity — walk wrappers.
 */
private fun captureHeavyFrostBitmap(context: Context): ImageBitmap? {
    val activity = context.findActivity() ?: return null
    val root = activity.findViewById<View>(android.R.id.content) ?: return null
    val w = root.width
    val h = root.height
    if (w <= 0 || h <= 0) return null
    return runCatching {
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(raw))
        val midW = (w / 8).coerceAtLeast(2)
        val midH = (h / 8).coerceAtLeast(2)
        val mid = Bitmap.createScaledBitmap(raw, midW, midH, true)
        raw.recycle()
        val tinyW = (w / 24).coerceAtLeast(2)
        val tinyH = (h / 24).coerceAtLeast(2)
        val tiny = Bitmap.createScaledBitmap(mid, tinyW, tinyH, true)
        mid.recycle()
        val soft = Bitmap.createScaledBitmap(tiny, w, h, true)
        tiny.recycle()
        soft.asImageBitmap()
    }.getOrNull()
}

/** Heavy frosted-glass blur radius (px) for window APIs. */
private const val FROST_WINDOW_BLUR_PX = 480

/**
 * Debug / emulator / Fold preview of the AirPods-style charging UI without requiring charging=true.
 * Production auto-show still uses [LiveState.charging].
 */
object ChargingUiPreview {
    const val ACTION = "com.noop.whoop.debug.PREVIEW_CHARGING"
    const val EXTRA_PCT = "pct"

    data class Request(val pct: Double, val id: Long)

    private val _request = MutableStateFlow<Request?>(null)
    /** Non-null while preview should show. [Request.id] changes every [show] so re-taps always fire. */
    val request: StateFlow<Request?> = _request.asStateFlow()

    fun show(batteryPct: Double = 67.0) {
        _request.value = Request(batteryPct.coerceIn(0.0, 100.0), System.nanoTime())
    }

    fun clear() {
        _request.value = null
    }

    /** Handles adb --ef/--ed pct and --ez noop_preview_charging. */
    fun showFromIntent(intent: Intent?) {
        if (intent == null) return
        val wantsPreview = intent.action == ACTION ||
            intent.getBooleanExtra("noop_preview_charging", false)
        if (!wantsPreview) return
        show(readPct(intent))
    }

    private fun readPct(intent: Intent): Double {
        if (!intent.hasExtra(EXTRA_PCT)) return 67.0
        val asFloat = intent.getFloatExtra(EXTRA_PCT, Float.NaN)
        if (!asFloat.isNaN()) return asFloat.toDouble()
        val asDouble = intent.getDoubleExtra(EXTRA_PCT, Double.NaN)
        if (!asDouble.isNaN()) return asDouble
        return 67.0
    }
}

/**
 * App-wide AirPods-style full-screen charging presentation.
 *
 * - Auto-opens when [LiveState.charging] flips false -> true (with ding).
 * - Full-screen ring, battery %, time-to-full, runtime estimate when useful.
 * - Honest: charge limit not available on open GATT.
 * - Dismissible; re-open from Live charge hero via [requestShow].
 * - Debug: [ChargingUiPreview] / adb broadcast [ChargingUiPreview.ACTION] to preview without a strap.
 */
@Composable
fun StrapChargingHost(viewModel: AppViewModel) {
    val chargingState by viewModel.live
        .map { state -> state.charging to state.batteryPct }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = null to null)
    val model by viewModel.selectedModel.collectAsStateWithLifecycle()
    val preview by ChargingUiPreview.request.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val appOpen = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val chargingTone = remember { BreathTonePlayer(context) }
    DisposableEffect(Unit) { onDispose { chargingTone.release() } }

    var previousCharging by remember { mutableStateOf<Boolean?>(null) }
    var showFull by remember { mutableStateOf(false) }
    // User dismissed while still charging - don't re-pop until next charge start.
    var dismissedThisCharge by remember { mutableStateOf(false) }

    // Debug builds: allow adb to force the full-screen charge UI on emulator/Fold without BLE.
    DisposableEffect(Unit) {
        if (!BuildConfig.DEBUG) return@DisposableEffect onDispose { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                ChargingUiPreview.showFromIntent(intent)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ChargingUiPreview.ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Ding plays inside FullScreenChargingContent (phase 1 of the sequence) - not here.

    LaunchedEffect(chargingState.first, appOpen) {
        val now = chargingState.first == true
        val wasCharging = previousCharging == true
        // Rising edge: full-screen animation only while the app is open (RESUMED).
        // Background / locked → BatteryAlertNotifier notification only.
        if (now && !wasCharging) {
            dismissedThisCharge = false
            showFull = appOpen
            viewModel.getBattery()
        }
        if (!now) {
            dismissedThisCharge = false
            if (showFull) showFull = false
        }
        previousCharging = chargingState.first
    }

    val liveChargingVisible = showFull && chargingState.first == true && !dismissedThisCharge
    val previewVisible = preview != null
    // key(preview?.id) so each Preview tap remounts the dialog / animation.
    val previewKey = preview?.id ?: 0L
    if (liveChargingVisible || previewVisible) {
        androidx.compose.runtime.key(if (previewVisible) previewKey else "live") {
            FullScreenChargingDialog(
                pct = preview?.pct ?: chargingState.second,
                model = model,
                viewModel = viewModel,
                tonePlayer = chargingTone,
                onDismiss = {
                    if (previewVisible) ChargingUiPreview.clear()
                    dismissedThisCharge = true
                    showFull = false
                },
            )
        }
    }
}

/** Call from Live hero to re-open the full-screen charge UI while still charging. */
@Composable
fun rememberChargeFullScreenOpener(
    viewModel: AppViewModel,
    externalShow: Boolean,
    onExternalConsumed: () -> Unit,
): Unit {
    // Kept for call-site flexibility; host owns auto-show.
    LaunchedEffect(externalShow) {
        if (externalShow) onExternalConsumed()
    }
}

/** Richer plug-in chime: low → mid → high with soft gaps (not a thin two-beep). */
private suspend fun playChargeDing(player: BreathTonePlayer) {
    player.play(BreathTone.ChargeLo)
    delay(110)
    player.play(BreathTone.ChargeMid)
    delay(85)
    player.play(BreathTone.ChargeHi)
    delay(70)
}

/**
 * Wall-clock ramp — ignores Android animator_duration_scale (emulator often has it at 0,
 * which makes Compose tween animations complete in one frame and look like a "pop").
 */
private suspend fun rampFloat(
    from: Float,
    to: Float,
    durationMs: Int,
    stepMs: Int = 16,
    onValue: suspend (Float) -> Unit,
) {
    val steps = maxOf(1, durationMs / stepMs)
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val e = 1f - (1f - t) * (1f - t) // ease-out quad
        onValue(from + (to - from) * e)
        if (i < steps) delay(stepMs.toLong())
    }
}

private enum class ChargeAnimPhase {
    Sound,
    Blur,
    Spin,
    Settled,
}

@Composable
fun FullScreenChargingDialog(
    pct: Double?,
    model: WhoopModel,
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    tonePlayer: BreathTonePlayer? = null,
    /** How long to hold after the ring settles before auto-close. */
    autoDismissAfterMs: Long = 4200L,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val context = LocalContext.current
        val ownedTone = remember(context) { BreathTonePlayer(context) }
        val tone = tonePlayer ?: ownedTone
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                // Never dark-dim the room — frost is blur + light grey milk only.
                window.setDimAmount(0f)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setBackgroundDrawableResource(android.R.color.transparent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        window.setBackgroundBlurRadius(FROST_WINDOW_BLUR_PX)
                        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        val lp = window.attributes
                        try {
                            WindowManager.LayoutParams::class.java
                                .getField("blurBehindRadius")
                                .setInt(lp, FROST_WINDOW_BLUR_PX)
                        } catch (_: Throwable) { }
                        window.attributes = lp
                    }
                }
            }
            onDispose {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window != null) {
                    runCatching { window.setBackgroundBlurRadius(0) }
                }
            }
        }
        DisposableEffect(tonePlayer) {
            onDispose {
                if (tonePlayer == null) ownedTone.release()
            }
        }
        FullScreenChargingContent(
            pct = pct,
            model = model,
            viewModel = viewModel,
            tonePlayer = tone,
            onDismiss = onDismiss,
            autoDismissAfterMs = autoDismissAfterMs,
        )
    }
}

@Composable
fun FullScreenChargingContent(
    pct: Double?,
    model: WhoopModel,
    viewModel: AppViewModel,
    tonePlayer: BreathTonePlayer,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissAfterMs: Long = 4200L,
) {
    val p = (pct ?: 0.0).coerceIn(0.0, 100.0)
    // Fixed mint green — never Palette.statusPositive / chargeColor (classic theme turns those gold/yellow).
    val ringGreen = Color(0xFF2DD4A0)
    val context = LocalContext.current

    var frostSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var frostAlpha by remember { mutableFloatStateOf(0f) }
    var contentAlpha by remember { mutableFloatStateOf(0f) }
    var titleAlpha by remember { mutableFloatStateOf(0f) }
    var detailsAlpha by remember { mutableFloatStateOf(0f) }
    var ringScale by remember { mutableFloatStateOf(0.94f) }
    var boltPulse by remember { mutableFloatStateOf(1f) }
    /** 0->360: clear glass track (finishes faster than green). */
    var trackSweep by remember { mutableFloatStateOf(0f) }
    /** 0->p with slight overshoot bounce. */
    var fillPct by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableStateOf(ChargeAnimPhase.Sound) }

    LaunchedEffect(Unit) {
        phase = ChargeAnimPhase.Sound
        frostAlpha = 0f
        contentAlpha = 0f
        titleAlpha = 0f
        detailsAlpha = 0f
        ringScale = 0.94f
        boltPulse = 1f
        trackSweep = 0f
        fillPct = 0f
        playChargeDing(tonePlayer)

        // Capture app behind Dialog (findActivity — Dialog context is a wrapper). Retry if not laid out.
        var snap: ImageBitmap? = captureHeavyFrostBitmap(context)
        if (snap == null) {
            delay(32)
            snap = captureHeavyFrostBitmap(context)
        }
        if (snap == null) {
            delay(64)
            snap = captureHeavyFrostBitmap(context)
        }
        frostSnapshot = snap

        // Frosted glass in (blurred snapshot + light glass milk — not a black dim)
        phase = ChargeAnimPhase.Blur
        rampFloat(0f, 1f, 180) { frostAlpha = it }

        phase = ChargeAnimPhase.Spin
        val peak = (p.toFloat() * 1.045f).coerceAtMost(100f)
        coroutineScope {
            launch { rampFloat(0f, 1f, 100) { contentAlpha = it } }
            launch {
                delay(80)
                rampFloat(0f, 1f, 140) { titleAlpha = it }
            }
            // Clear finishes faster; green starts with it but runs longer + slight bounce
            launch { rampFloat(0f, 360f, 170) { trackSweep = it } }
            launch {
                rampFloat(0.94f, 1.02f, 200) { ringScale = it }
                rampFloat(1.02f, 1f, 90) { ringScale = it }
            }
            launch {
                rampFloat(0f, peak, 400) { fillPct = it }
                rampFloat(peak, p.toFloat(), 120) { fillPct = it } // soft bounce settle
                rampFloat(1f, 1.12f, 70) { boltPulse = it }
                rampFloat(1.12f, 1f, 90) { boltPulse = it }
            }
            launch {
                delay(280)
                rampFloat(0f, 1f, 180) { detailsAlpha = it }
            }
        }
        phase = ChargeAnimPhase.Settled
        delay(autoDismissAfterMs.coerceAtLeast(800L))
        onDismiss()
    }

    var timeToFull by remember { mutableStateOf("…") }
    var runtimeLeft by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(p) {
        val remain = 100.0 - p
        timeToFull = if (remain <= 0.5) {
            "Full"
        } else {
            val hours = remain / 40.0
            if (hours < 2.0) String.format("~%.0f min left on charger", hours * 60)
            else String.format("~%.1f h left on charger", hours)
        }
    }
    LaunchedEffect(model) {
        val rated = if (model == WhoopModel.WHOOP5_MG) BatteryEstimator.ratedLifeHoursWhoop5
        else BatteryEstimator.ratedLifeHoursWhoop4
        val now = System.currentTimeMillis() / 1000L
        val samples = withContext(Dispatchers.IO) {
            runCatching {
                viewModel.repo.batterySamples("my-whoop", from = now - 14L * 86400L, to = now, limit = 400)
                    .mapNotNull { row -> row.soc?.let { row.ts to it } }
            }.getOrDefault(emptyList())
        }
        val est = BatteryEstimator.estimate(samples, rated)
        runtimeLeft = est?.let {
            when {
                it.daysRemaining >= 1.5 -> String.format("%.1f days runtime when unplugged (est.)", it.daysRemaining)
                it.daysRemaining >= 0.1 -> String.format("%.0f h runtime when unplugged (est.)", it.remainingHours)
                else -> null
            }
        }
    }

    val drawingTrack = phase == ChargeAnimPhase.Spin || phase == ChargeAnimPhase.Settled
    val displayPct = fillPct.coerceIn(0f, 100f)

    Box(
        modifier
            .fillMaxSize()
            .semantics {
                contentDescription =
                    "Charging ${p.roundToInt()} percent. $timeToFull."
            },
    ) {
        // Glassmorphism: smashed snapshot + Compose blur; light glass so blur stays readable.
        val snap = frostSnapshot
        if (snap != null) {
            Image(
                bitmap = snap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = frostAlpha }
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(48.dp)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = frostAlpha }
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A1A28).copy(alpha = 0.28f),
                            Color(0xFF12121C).copy(alpha = 0.36f),
                            Color(0xFF1A1A28).copy(alpha = 0.28f),
                        ),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = frostAlpha * 0.7f }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 48.dp)
                .graphicsLayer { alpha = contentAlpha },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = titleAlpha },
            ) {
                Text(
                    "Charging",
                    style = NoopType.overline,
                    color = ringGreen.copy(alpha = 0.95f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Strap on charger",
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(280.dp)
                    .graphicsLayer {
                        scaleX = ringScale
                        scaleY = ringScale
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 14.dp.toPx()
                    val pad = stroke * 1.6f
                    val dim = size.minDimension - pad * 2
                    val tl = Offset(pad, pad)
                    val arcSize = Size(dim, dim)
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = dim / 2f

                    // Soft atmosphere (no hard color blocks)
                    if (drawingTrack) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    ringGreen.copy(alpha = 0.08f),
                                    Color.Transparent,
                                ),
                                center = Offset(cx, cy),
                                radius = r * 1.4f,
                            ),
                            radius = r * 1.4f,
                            center = Offset(cx, cy),
                        )
                    }

                    val clearSweep = trackSweep.coerceIn(0f, 360f).let {
                        if (phase == ChargeAnimPhase.Settled) 360f else it
                    }
                    if (drawingTrack && clearSweep > 0.5f) {
                        val clearLayers = listOf(
                            2.4f to 0.035f,
                            1.8f to 0.05f,
                            1.35f to 0.07f,
                            1.05f to 0.12f,
                        )
                        for ((mul, a) in clearLayers) {
                            drawArc(
                                color = Color.White.copy(alpha = a),
                                startAngle = -90f,
                                sweepAngle = clearSweep,
                                useCenter = false,
                                topLeft = tl,
                                size = arcSize,
                                style = Stroke(width = stroke * mul, cap = StrokeCap.Round),
                            )
                        }
                    }

                    val fillSweep = 360f * (fillPct / 100f).coerceIn(0f, 1f)
                    if (fillSweep > 0.4f) {
                        val greenLayers = listOf(
                            3.2f to 0.035f,
                            2.6f to 0.05f,
                            2.0f to 0.07f,
                            1.55f to 0.11f,
                            1.25f to 0.18f,
                            1.08f to 0.32f,
                            1.0f to 0.95f,
                        )
                        for ((mul, a) in greenLayers) {
                            drawArc(
                                color = ringGreen.copy(alpha = a),
                                startAngle = -90f,
                                sweepAngle = fillSweep,
                                useCenter = false,
                                topLeft = tl,
                                size = arcSize,
                                style = Stroke(width = stroke * mul, cap = StrokeCap.Round),
                            )
                        }
                        val endA = Math.toRadians((-90.0 + fillSweep))
                        val tip = Offset(
                            cx + r * kotlin.math.cos(endA).toFloat(),
                            cy + r * kotlin.math.sin(endA).toFloat(),
                        )
                        drawCircle(color = ringGreen.copy(alpha = 0.10f), radius = stroke * 2.0f, center = tip)
                        drawCircle(color = ringGreen.copy(alpha = 0.20f), radius = stroke * 1.15f, center = tip)
                        drawCircle(color = ringGreen.copy(alpha = 0.9f), radius = stroke * 0.3f, center = tip)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = ringGreen,
                        modifier = Modifier
                            .size(36.dp)
                            .graphicsLayer {
                                scaleX = boltPulse
                                scaleY = boltPulse
                            },
                    )
                    // Optical charge read: compact %, baseline-aligned digits (not a single off-center string).
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            displayPct.roundToInt().toString(),
                            style = NoopType.display(52f).copy(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                            ),
                            color = Palette.textPrimary,
                        )
                        Text(
                            "%",
                            style = NoopType.display(26f).copy(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                            ),
                            color = Palette.textPrimary.copy(alpha = 0.72f),
                            modifier = Modifier
                                .padding(start = 2.dp, bottom = 6.dp)
                                .offset(x = (-1).dp),
                        )
                    }
                    Text(
                        if (p >= 99.5 && fillPct >= p - 0.5) "Full" else "Charged",
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = detailsAlpha },
            ) {
                Text(
                    "Time left on charger",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                Text(
                    timeToFull,
                    style = NoopType.display(28f),
                    color = ringGreen,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Charge limit",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                Text(
                    "Not available on open Bluetooth",
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Charging toward 100%. WHOOP keeps the limit private.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                )
                runtimeLeft?.let {
                    Text(
                        it,
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
