package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// MARK: - LiquidSky (the time-of-day liquid sky) — Compose port of Strand/Liquid/LiquidSky.swift
//
// The time-of-day sky: a gradient that flows continuously through the day's keyframes, a quiet
// starfield, and two subtle sheets of light. No objects, no blur — clean and crisp, the atmosphere
// of the app's header. Ported 1:1 from the SwiftUI Canvas: same 10 keyframes, same hex values, same
// interpolation, the same 70-star fixed field, the same layering order (gradient scene → slow breath
// of light low in the sky → warm horizon wash when warm>0 → twinkling stars → SETTLE fade to the
// theme canvas colour over the lower half so the sky dissolves into the page with no seam).
//
// The one theme-aware substitution the iOS source itself makes: the SETTLE colour is the canvas the
// body dissolves into. iOS hard-coded (18,21,24) dark / (242,242,247) light; here it reads the real
// Android canvas token [Palette.surfaceBase] (dark #121518 == the iOS dark literal; light #EAE3D4 ==
// the warm-paper canvas), theme-aware via the active palette, so the sky meets the page with no seam.

/** One keyframe of the day-cycle sky: the hour it sits at, the three vertical gradient stops
 *  (top/mid/horizon), and how starry / how warm the sky is there. */
data class LiquidSkyStop(
    val h: Double,
    val top: Color,
    val mid: Color,
    val hor: Color,
    val stars: Double,
    val warm: Double,
)

/** Build an opaque sRGB colour from a 0xRRGGBB hex (mirrors the iOS `hx` helper). */
private fun hx(hex: Long): Color = Color(
    red = ((hex shr 16) and 0xff).toInt() / 255f,
    green = ((hex shr 8) and 0xff).toInt() / 255f,
    blue = (hex and 0xff).toInt() / 255f,
    alpha = 1f,
)

/** The ten keyframes mirror the real app's day-cycle scenes (SceneHeroBackground), as pure
 *  gradients rather than painted art. Hex values are byte-identical to the iOS source. */
// MARK: - PROTOTYPE weather (#weather) — mirrors the iOS LiquidWeather
//
// An optional weather MOOD layered over the time-of-day gradient. Aesthetic only — no real conditions,
// location or network (NOOP stays offline). Procedural + tinted by the CURRENT sky, so a cloud at dusk
// reads mauve and at noon white. CLEAR (default) = the unchanged look. `raw` matches the iOS rawValues
// (clear/hazy/overcast/rain/fog/snow) so the persisted value is identical across platforms.

enum class LiquidWeather(val label: String) {
    CLEAR("Clear"), HAZY("Hazy"), OVERCAST("Cloud"), RAIN("Rain"), FOG("Fog"), SNOW("Snow");

    val raw: String get() = name.lowercase()

    companion object {
        const val STORAGE_KEY = "liquid.weather"
        fun fromRaw(s: String?): LiquidWeather = entries.firstOrNull { it.raw == s } ?: CLEAR
    }
}

/** Reactive holder so the Settings picker live-updates the sky (mirrors [CardAppearance]). Initialised
 *  from NoopPrefs at app start via [init]; the Settings picker writes both this and the pref. */
object LiquidWeatherState {
    var mode by mutableStateOf(LiquidWeather.CLEAR)
    fun init(context: Context) { mode = NoopPrefs.weather(context) }
}

/** PROTOTYPE (#weather): resolve the optional `weather_<mood>` drawable to an [ImageBitmap], or null when
 *  the asset isn't in res/drawable (→ procedural fallback). getIdentifier returns 0 for a missing name, so
 *  dropping the art in later upgrades the look with no code change. Decoded once per mood via remember. */
@Composable
private fun rememberWeatherImage(weather: LiquidWeather): ImageBitmap? {
    if (weather == LiquidWeather.CLEAR) return null
    val context = LocalContext.current
    return remember(weather) {
        @Suppress("DiscouragedApi")
        val resId = context.resources.getIdentifier("weather_${weather.raw}", "drawable", context.packageName)
        if (resId == 0) return@remember null
        // #weather PERF: decode at half res (inSampleSize=2, ~750px). The sky is a soft, stretched wash, so
        // full res buys nothing visible but quadruples the texture memory the scrolling cards composite over.
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeResource(context.resources, resId, opts)?.asImageBitmap()
    }
}

val liquidSkyKeys: List<LiquidSkyStop> = listOf(
    LiquidSkyStop(h = 0.0, top = hx(0x05060f), mid = hx(0x0b0e22), hor = hx(0x1a1440), stars = 1.0, warm = 0.0),
    LiquidSkyStop(h = 5.0, top = hx(0x0a0d24), mid = hx(0x1c1a4a), hor = hx(0x4a2a6a), stars = 0.6, warm = 0.0),
    LiquidSkyStop(h = 6.5, top = hx(0x1b1b4d), mid = hx(0x4a2f7d), hor = hx(0xb0567a), stars = 0.25, warm = 0.2),
    LiquidSkyStop(h = 8.5, top = hx(0x2a4a8f), mid = hx(0x7a5aa0), hor = hx(0xf0a060), stars = 0.0, warm = 0.6),
    LiquidSkyStop(h = 11.0, top = hx(0x2a6ac8), mid = hx(0x5a9ae0), hor = hx(0xa8cef0), stars = 0.0, warm = 0.95),
    LiquidSkyStop(h = 14.0, top = hx(0x2f74d0), mid = hx(0x66a6e8), hor = hx(0xb8d8f4), stars = 0.0, warm = 1.0),
    LiquidSkyStop(h = 17.5, top = hx(0x3a4a90), mid = hx(0x9a5a80), hor = hx(0xf0924a), stars = 0.0, warm = 0.4),
    LiquidSkyStop(h = 19.5, top = hx(0x221c50), mid = hx(0x4a2a70), hor = hx(0x8a4a80), stars = 0.45, warm = 0.0),
    LiquidSkyStop(h = 22.0, top = hx(0x070818), mid = hx(0x141335), hor = hx(0x2a1d55), stars = 1.0, warm = 0.0),
    LiquidSkyStop(h = 24.0, top = hx(0x05060f), mid = hx(0x0b0e22), hor = hx(0x1a1440), stars = 1.0, warm = 0.0),
)

private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** Linear-interpolate two colours in sRGB space (mirrors the iOS `lerpColor` over `liquidComponents`).
 *  Compose `Color.red/green/blue` already give sRGB 0..1 floats, so no component extraction is needed. */
private fun lerpColor(a: Color, b: Color, t: Double): Color {
    val tf = t.toFloat()
    return Color(
        red = a.red + (b.red - a.red) * tf,
        green = a.green + (b.green - a.green) * tf,
        blue = a.blue + (b.blue - a.blue) * tf,
        alpha = 1f,
    )
}

/** The interpolated sky at a given [hour] (0..24): the three gradient stops plus the star / warm
 *  amounts. Walks the keyframes exactly like the iOS source. */
fun liquidSkyAt(hour: Double): LiquidSkyResolved {
    var i = 0
    while (i < liquidSkyKeys.size - 2 && liquidSkyKeys[i + 1].h <= hour) i += 1
    val a = liquidSkyKeys[i]
    val b = liquidSkyKeys[i + 1]
    val t = max(0.0, min(1.0, (hour - a.h) / (b.h - a.h)))
    return LiquidSkyResolved(
        top = lerpColor(a.top, b.top, t),
        mid = lerpColor(a.mid, b.mid, t),
        hor = lerpColor(a.hor, b.hor, t),
        stars = lerp(a.stars, b.stars, t),
        warm = lerp(a.warm, b.warm, t),
    )
}

/** The resolved sky for an hour (Kotlin has no anonymous tuple return, so this names the fields the
 *  iOS `(top:mid:hor:stars:warm:)` tuple carried). */
data class LiquidSkyResolved(
    val top: Color,
    val mid: Color,
    val hor: Color,
    val stars: Double,
    val warm: Double,
)

/** A precomputed quiet star field (positions fixed; only the count that render depends on how starry
 *  the hour is). Mirrors the iOS `LiquidStar` record + the 70-star field. */
private data class LiquidStar(val x: Double, val y: Double, val z: Double, val ph: Double, val sp: Double)

private val liquidStars: List<LiquidStar> = (0 until 70).map {
    LiquidStar(
        x = Random.nextDouble(0.0, 1.0),
        y = Random.nextDouble(0.0, 0.78),
        z = Random.nextDouble(0.0, 1.0),
        ph = Random.nextDouble(0.0, 7.0),
        sp = 0.2 + Random.nextDouble(0.0, 0.5),
    )
}

/** Live hour of day as hour + minute/60 (the normal Android clock — the iOS `liveHour()`). */
private fun liquidLiveHour(): Double {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY).toDouble() + c.get(Calendar.MINUTE).toDouble() / 60.0
}

// MARK: - Render (shared draw core for the animated + static skies)
//
// A single DrawScope routine so the two entry points draw pixel-identically. `now` is a monotonic
// seconds clock (drives twinkle + breath); `animate` gates the two time-varying layers so the static
// sky draws the SAME picture minus the breath/twinkle (matching the iOS LiquidSkyStatic).

private fun androidx.compose.ui.graphics.drawscope.DrawScope.renderLiquidSky(
    hour: Double,
    now: Double,
    settle: Color,
    animate: Boolean,
    // How fully the sky dissolves into the canvas at the bottom (1 = the default seamless fade to the flat
    // page; <1 holds the atmosphere so the sky still reads under a full-height "sky behind cards" backdrop).
    settleStrength: Float = 1f,
    // PROTOTYPE (#weather): the weather mood layered over the gradient. CLEAR = no-op.
    weather: LiquidWeather = LiquidWeather.CLEAR,
    // PROTOTYPE (#weather): optional `weather_<mood>` art. When present it's drawn (blended) instead of the
    // procedural draw; null → procedural fallback. Resolved at the composable level (rememberWeatherImage).
    weatherImage: ImageBitmap? = null,
) {
    val s = liquidSkyAt(hour)
    val w = size.width
    val h = size.height

    // The gradient IS the scene: a vertical 3-stop top → mid(0.5) → horizon(0.9) fill over the box.
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to s.top,
                0.5f to s.mid,
                0.9f to s.hor,
            ),
            startY = 0f,
            endY = h,
        ),
    )

    // Slow breath of light low in the sky (animated sky only). White wash rising over the lower 55%.
    if (animate) {
        val breathe = 0.5 + 0.5 * sin(now * 0.22)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0f),
                    Color.White.copy(alpha = (0.05 + breathe * 0.03).toFloat()),
                ),
                startY = h * 0.45f,
                endY = h,
            ),
            topLeft = Offset(0f, h * 0.45f),
            size = Size(w, h * 0.55f),
        )
    }

    // Warm horizon wash when the hour is warm (dawn/day/dusk). Rises over the lower 45%.
    // Animated sky only — iOS LiquidSkyStatic omits both the breath AND the warm wash (it draws only
    // gradient → base stars → settle), so gate warm on `animate` too, else the static scaffold sky used
    // behind every chart-heavy tab would carry a warm band the iOS static never draws.
    if (animate && s.warm > 0.01) {
        val warm = Color(red = 1f, green = 200f / 255f, blue = 120f / 255f, alpha = 1f)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    warm.copy(alpha = 0f),
                    warm.copy(alpha = (s.warm * 0.10).toFloat()),
                ),
                startY = h * 0.55f,
                endY = h,
            ),
            topLeft = Offset(0f, h * 0.55f),
            size = Size(w, h * 0.45f),
        )
    }

    // PROTOTYPE (#weather): weather sits between the warm wash and the stars. Image ART when present
    // (aspect-filled to width, top-aligned, blended so the sky reads through), else the procedural draw.
    // `now` is 0 on the static sky, so procedural rain/snow are frozen there but still visible.
    if (weather != LiquidWeather.CLEAR && weatherImage != null) {
        // Fill the WHOLE sky canvas (stretch), matching the iOS draw — an aspect-fit-to-width draw left a
        // short 3:2 image with a hard bottom edge partway down the tall sky band, which read as "not
        // stretched" and (split by a floating card) as "doubled". The settle fade below dissolves the lower
        // part into the page. Normal (SrcOver): an OPAQUE scene covers the gradient; a TRANSPARENT overlay
        // shows the gradient through its alpha. (#weather)
        drawImage(
            image = weatherImage,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(weatherImage.width, weatherImage.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
            alpha = 0.9f,                  // TUNE per the art
            blendMode = BlendMode.SrcOver,
        )
    } else {
        drawLiquidWeather(weather = weather, s = s, now = now)
    }

    // Stars. Animated sky twinkles (phase-driven pow(sin,6) flare); static sky draws the base alpha only.
    if (s.stars > 0.01) {
        for (star in liquidStars) {
            val o: Double
            if (animate) {
                val baseA = 0.04 + star.z * 0.16
                val tw = max(0.0, sin(star.ph + now * star.sp)).pow(6)
                o = s.stars * (baseA + tw * 0.28)
            } else {
                o = s.stars * (0.04 + star.z * 0.16)
            }
            if (o < 0.02) continue
            val sz = (0.6 + star.z * 0.8).toFloat()
            drawRect(
                color = Color.White.copy(alpha = o.toFloat()),
                topLeft = Offset((star.x * w).toFloat(), (star.y * h).toFloat()),
                size = Size(sz, sz),
            )
        }
    }

    // Settle into the page: a long fade to the theme's surfaceBase over the lower half so the sky
    // dissolves seamlessly into the body — no hard cut (the light-mode dark→canvas slam is gone).
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                settle.copy(alpha = 0f),
                settle.copy(alpha = settleStrength.coerceIn(0f, 1f)),
            ),
            startY = h * 0.45f,
            endY = h,
        ),
        topLeft = Offset(0f, h * 0.45f),
        size = Size(w, h * 0.55f),
    )
}

// MARK: - PROTOTYPE weather layers (#weather) — mirrors the iOS drawLiquidWeather
//
// Procedural weather over the gradient, tinted by the current sky [s] so it reads at any hour. Cheap
// DrawScope fills only. Values are a FIRST PASS to tune on-device. No-op when CLEAR.

private fun DrawScope.drawLiquidWeather(weather: LiquidWeather, s: LiquidSkyResolved, now: Double) {
    if (weather == LiquidWeather.CLEAR) return
    val w = size.width
    val h = size.height
    when (weather) {
        LiquidWeather.CLEAR -> Unit
        LiquidWeather.HAZY -> {
            // A soft band of horizon-tinted haze low in the sky.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(s.hor.copy(alpha = 0f), s.hor.copy(alpha = 0.16f), s.hor.copy(alpha = 0.04f)),
                    startY = h * 0.5f, endY = h,
                ),
                topLeft = Offset(0f, h * 0.5f), size = Size(w, h * 0.5f),
            )
        }
        LiquidWeather.OVERCAST -> drawLiquidClouds(tint = s.mid, now = now, coverage = 0.7f)
        LiquidWeather.RAIN -> {
            drawLiquidClouds(tint = s.mid, now = now, coverage = 0.85f)
            drawLiquidRain(now = now)
        }
        LiquidWeather.FOG -> {
            val fog = lerp(s.mid, Color.White, 0.45f)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(fog.copy(alpha = 0f), fog.copy(alpha = 0.34f)),
                    startY = h * 0.35f, endY = h,
                ),
                topLeft = Offset(0f, h * 0.35f), size = Size(w, h * 0.65f),
            )
        }
        LiquidWeather.SNOW -> drawLiquidSnow(now = now)
    }
}

/** A few soft radial "clouds" drifting slowly across, tinted toward the mid-sky so they read at any hour. */
private fun DrawScope.drawLiquidClouds(tint: Color, now: Double, coverage: Float) {
    val cloud = lerp(tint, Color.White, 0.5f)
    val w = size.width
    val h = size.height
    // (x0, y, radiusFrac, driftSpeed) — x0/y in [0,1]; drifts right and wraps.
    val blobs = listOf(
        doubleArrayOf(0.15, 0.24, 0.30, 0.006), doubleArrayOf(0.55, 0.18, 0.36, 0.004),
        doubleArrayOf(0.85, 0.30, 0.26, 0.008), doubleArrayOf(0.38, 0.36, 0.32, 0.005),
    )
    for (b in blobs) {
        val x = (((b[0] + now * b[3]) % 1.25) - 0.1).toFloat() * w
        val y = b[1].toFloat() * h
        val rx = b[2].toFloat() * w
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cloud.copy(alpha = coverage * 0.5f), cloud.copy(alpha = 0f)),
                center = Offset(x, y), radius = rx,
            ),
            radius = rx, center = Offset(x, y),
        )
    }
}

/** Short diagonal streaks falling on a loop. */
private fun DrawScope.drawLiquidRain(now: Double) {
    val w = size.width
    val h = size.height
    for (i in 0 until 70) {
        val sx = (i * 0.61803) % 1.0
        val x = sx.toFloat() * w
        val phase = (now * 1.1 + i * 0.137) % 1.0
        val y = phase.toFloat() * h
        drawLine(
            color = Color.White.copy(alpha = 0.10f),
            start = Offset(x, y), end = Offset(x - 4f, y + 15f), strokeWidth = 1f,
        )
    }
}

/** Drifting specks that sway as they fall — the starfield feel, brighter and moving. */
private fun DrawScope.drawLiquidSnow(now: Double) {
    val w = size.width
    val h = size.height
    for (i in 0 until 70) {
        val sx = (i * 0.61803) % 1.0
        val sway = (sin(now * 0.5 + i) * 8).toFloat()
        val phase = (now * 0.05 + i * 0.11) % 1.0
        val x = sx.toFloat() * w + sway
        val y = phase.toFloat() * h
        val sz = (1.4 + sx * 1.6).toFloat()
        drawCircle(color = Color.White.copy(alpha = 0.55f), radius = sz, center = Offset(x, y))
    }
}

/** The canvas colour the sky dissolves into — the active theme's surfaceBase (dark #121518 / light
 *  #EAE3D4), read live so a theme flip re-resolves it. Replaces the iOS hard-coded settle literals. */
private val liquidSettleColor: Color
    get() = Palette.surfaceBase

// MARK: - LiquidSky (animated) — per-frame twinkle + breath

/**
 * The animated time-of-day liquid sky. Drives twinkle + the slow breath of light per frame via
 * `withFrameNanos`. Under Reduce Motion it collapses to the static picture (no frame loop) — matching
 * the house motion rule. [hour] defaults to live local time (hour + minute/60) when null.
 *
 * Mirrors the iOS `LiquidSky` view (TimelineView.animation at 20fps → here an unbounded frame loop;
 * only the sinusoid phase matters, so the picture is identical).
 */
@Composable
fun LiquidSky(hour: Double? = null, modifier: Modifier = Modifier) {
    val reduced = rememberReduceMotion()
    val settle = liquidSettleColor
    val h = hour ?: liquidLiveHour()
    val weather = LiquidWeatherState.mode   // #weather: reactive so the picker live-updates
    val weatherImage = rememberWeatherImage(weather)

    // #weather PERF: pose the sky ONCE (no per-frame loop) under Reduce Motion OR when a weather IMAGE is
    // shown — re-blitting a full-screen bitmap every frame behind a scrolling list stutters, and the image
    // is static (it hides the animated breath/stars underneath anyway). Cached in the scaffold's
    // graphicsLayer, so scroll composites a texture, not a redraw.
    if (reduced || weatherImage != null) {
        Canvas(modifier = modifier) { renderLiquidSky(hour = h, now = 0.0, settle = settle, animate = false, weather = weather, weatherImage = weatherImage) }
        return
    }

    // Monotonic seconds clock: accumulate raw frame nanos → seconds. Only the sinusoid phase matters
    // (the iOS `now` is seconds-since-reference), so a from-zero accumulator gives the same motion.
    var seconds by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { frame ->
                if (last != 0L) seconds += (frame - last) / 1_000_000_000.0
                last = frame
            }
        }
    }

    Canvas(modifier = modifier) {
        renderLiquidSky(hour = h, now = seconds, settle = settle, animate = true, weather = weather, weatherImage = weatherImage)
    }
}

// MARK: - LiquidSkyStatic — rendered once, no frame loop, no twinkle/breath (scroll perf)

/**
 * A STATIC time-of-day sky, rendered ONCE (no frame loop → the Compose layer is cached, zero per-frame
 * cost) for the scaffold backgrounds on the chart-heavy tabs. An always-animating Canvas behind the
 * charts steals frame headroom and causes stutter; this is the same look minus the twinkle/breath,
 * matching the classic app's static scene image for scroll perf. Mirrors the iOS `LiquidSkyStatic`.
 *
 * [hour] defaults to live local time when null.
 */
@Composable
fun LiquidSkyStatic(hour: Double? = null, modifier: Modifier = Modifier, settleStrength: Float = 1f) {
    val settle = liquidSettleColor
    val h = hour ?: liquidLiveHour()
    val weather = LiquidWeatherState.mode   // #weather
    val weatherImage = rememberWeatherImage(weather)
    Canvas(modifier = modifier) {
        renderLiquidSky(hour = h, now = 0.0, settle = settle, animate = false, settleStrength = settleStrength, weather = weather, weatherImage = weatherImage)
    }
}

// MARK: - liquidScaffoldSky — the full-bleed header background any screen drops in

/**
 * A subtle full-bleed time-of-day sky for any screen's top background, so the liquid atmosphere carries
 * across EVERY tab. Same static sky as the chart-heavy tabs at a modest header [height], top-aligned,
 * so the charts/cards below sit on the dark canvas — the redesign's "the options change, not the page"
 * feel. Non-interactive + accessibility-hidden (pure decoration). Mirrors the iOS `liquidScaffoldSky`.
 */
@Composable
fun liquidScaffoldSky(height: Dp = 240.dp) {
    LiquidSkyStatic(
        hour = null,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics {}, // decorative — invisible to TalkBack
    )
}
