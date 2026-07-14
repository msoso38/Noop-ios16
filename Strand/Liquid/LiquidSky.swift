//  LiquidSky.swift
//  NOOP · Liquid design language
//
//  The time-of-day sky: a gradient that flows continuously through the day's
//  keyframes, a quiet starfield, and two subtle sheets of light. No objects,
//  no blur — clean and crisp, the atmosphere of the app's header.

import SwiftUI
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// PROTOTYPE (#weather): load an optional weather-overlay image from the asset catalog, or nil when the
/// asset isn't present. The weather layer draws this image (blended over the sky) when it exists and falls
/// back to the procedural draw otherwise — so dropping `weather_<mood>` art in upgrades the look with no
/// code change. Asset names: weather_hazy / weather_overcast / weather_rain / weather_fog / weather_snow.
private func loadWeatherImage(_ name: String) -> Image? {
    #if canImport(UIKit)
    if let ui = UIImage(named: name) { return Image(uiImage: ui) }
    #elseif canImport(AppKit)
    if let ns = NSImage(named: name) { return Image(nsImage: ns) }
    #endif
    return nil
}

struct LiquidSkyStop {
    let h: Double
    let top: Color, mid: Color, hor: Color
    let stars: Double, warm: Double
}

private func hx(_ hex: UInt32) -> Color {
    Color(.sRGB,
          red: Double((hex >> 16) & 0xff) / 255,
          green: Double((hex >> 8) & 0xff) / 255,
          blue: Double(hex & 0xff) / 255, opacity: 1)
}

/// PROTOTYPE (#weather): an optional weather MOOD layered over the time-of-day gradient. Aesthetic only —
/// no real conditions, no location or network (NOOP stays offline). Every mode is procedural and tinted by
/// the CURRENT sky, so a cloud at dusk reads mauve and at noon reads white. `.clear` (default) = today's look.
enum LiquidWeather: String, CaseIterable, Identifiable {
    case clear, hazy, overcast, rain, fog, snow
    var id: String { rawValue }
    static let storageKey = "liquid.weather"
    var label: String {
        switch self {
        case .clear:    return "Clear"
        case .hazy:     return "Hazy"
        case .overcast: return "Overcast"
        case .rain:     return "Rain"
        case .fog:      return "Fog"
        case .snow:     return "Snow"
        }
    }
}

/// The ten keyframes mirror the real app's day-cycle scenes (SceneHeroBackground),
/// as pure gradients rather than painted art.
let liquidSkyKeys: [LiquidSkyStop] = [
    .init(h: 0,    top: hx(0x05060f), mid: hx(0x0b0e22), hor: hx(0x1a1440), stars: 1,   warm: 0),
    .init(h: 5,    top: hx(0x0a0d24), mid: hx(0x1c1a4a), hor: hx(0x4a2a6a), stars: 0.6, warm: 0),
    .init(h: 6.5,  top: hx(0x1b1b4d), mid: hx(0x4a2f7d), hor: hx(0xb0567a), stars: 0.25, warm: 0.2),
    .init(h: 8.5,  top: hx(0x2a4a8f), mid: hx(0x7a5aa0), hor: hx(0xf0a060), stars: 0,   warm: 0.6),
    .init(h: 11,   top: hx(0x2a6ac8), mid: hx(0x5a9ae0), hor: hx(0xa8cef0), stars: 0,   warm: 0.95),
    .init(h: 14,   top: hx(0x2f74d0), mid: hx(0x66a6e8), hor: hx(0xb8d8f4), stars: 0,   warm: 1),
    .init(h: 17.5, top: hx(0x3a4a90), mid: hx(0x9a5a80), hor: hx(0xf0924a), stars: 0,   warm: 0.4),
    .init(h: 19.5, top: hx(0x221c50), mid: hx(0x4a2a70), hor: hx(0x8a4a80), stars: 0.45, warm: 0),
    .init(h: 22,   top: hx(0x070818), mid: hx(0x141335), hor: hx(0x2a1d55), stars: 1,   warm: 0),
    .init(h: 24,   top: hx(0x05060f), mid: hx(0x0b0e22), hor: hx(0x1a1440), stars: 1,   warm: 0),
]

private func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }
private func lerpColor(_ a: Color, _ b: Color, _ t: Double) -> Color {
    let x = a.liquidComponents(), y = b.liquidComponents()
    return Color(.sRGB, red: lerp(x.r, y.r, t), green: lerp(x.g, y.g, t), blue: lerp(x.b, y.b, t), opacity: 1)
}

func liquidSkyAt(_ hour: Double) -> (top: Color, mid: Color, hor: Color, stars: Double, warm: Double) {
    var i = 0
    while i < liquidSkyKeys.count - 2 && liquidSkyKeys[i + 1].h <= hour { i += 1 }
    let a = liquidSkyKeys[i], b = liquidSkyKeys[i + 1]
    let t = max(0, min(1, (hour - a.h) / (b.h - a.h)))
    return (lerpColor(a.top, b.top, t), lerpColor(a.mid, b.mid, t), lerpColor(a.hor, b.hor, t),
            lerp(a.stars, b.stars, t), lerp(a.warm, b.warm, t))
}

/// A precomputed quiet star field (positions fixed; only the count that render
/// depends on how starry the hour is).
private struct LiquidStar { let x, y, z, ph, sp: Double }
private let liquidStars: [LiquidStar] = (0..<70).map { _ in
    LiquidStar(x: .random(in: 0...1), y: .random(in: 0...0.78), z: .random(in: 0...1),
               ph: .random(in: 0..<7), sp: 0.2 + .random(in: 0..<0.5))
}

struct LiquidSky: View {
    /// Hour of day 0...24. Defaults to live time when nil.
    var hour: Double?
    /// How fully the sky dissolves into the canvas at the bottom (1 = the default seamless fade; <1 holds
    /// the atmosphere so the sky still reads under a full-height "sky behind cards" backdrop).
    var settleStrength: Double = 1
    @Environment(\.colorScheme) private var scheme
    /// PROTOTYPE (#weather): the selected weather mood; `.clear` by default (unchanged look). Reactive, so
    /// flipping it in Settings live-updates the sky.
    @AppStorage(LiquidWeather.storageKey) private var weatherRaw = LiquidWeather.clear.rawValue

    /// The theme-aware canvas colour the sky dissolves into (no hard seam where sky meets page).
    private var settleColor: Color {
        let dark = scheme == .dark
        return Color(.sRGB, red: dark ? 18.0 / 255.0 : 242.0 / 255.0,
                     green: dark ? 21.0 / 255.0 : 242.0 / 255.0,
                     blue: dark ? 24.0 / 255.0 : 247.0 / 255.0, opacity: 1)
    }

    /// PROTOTYPE (#weather): a shown weather IMAGE is static, so it doesn't need the per-frame animation —
    /// and re-blitting a full-screen bitmap at 20fps behind a scrolling list stutters. Skip the TimelineView
    /// when an image is present (it also hides the animated breath/stars underneath). Procedural moods and
    /// the plain sky keep the live animation.
    private var hasWeatherImage: Bool {
        (LiquidWeather(rawValue: weatherRaw) ?? .clear) != .clear
            && loadWeatherImage("weather_\(weatherRaw)") != nil
    }

    var body: some View {
        if hasWeatherImage {
            Canvas { ctx, size in
                render(ctx, size, hour: hour ?? liveHour(), now: 0, settle: settleColor)
            }
        } else {
            TimelineView(.animation(minimumInterval: 1.0 / 20.0)) { tl in
                Canvas { ctx, size in
                    render(ctx, size, hour: hour ?? liveHour(), now: liquidSeconds(tl.date), settle: settleColor)
                }
            }
        }
    }

    private func liveHour() -> Double {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        return Double(c.hour ?? 0) + Double(c.minute ?? 0) / 60
    }

    private func render(_ base: GraphicsContext, _ size: CGSize, hour: Double, now: Double,
                        settle: Color) {
        let S = liquidSkyAt(hour)
        let w = size.width, h = size.height
        var ctx = base
        // the gradient IS the scene
        ctx.fill(Path(CGRect(x: 0, y: 0, width: w, height: h)),
                 with: .linearGradient(Gradient(stops: [
                    .init(color: S.top, location: 0),
                    .init(color: S.mid, location: 0.5),
                    .init(color: S.hor, location: 0.9)]),
                                       startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: 0, y: h)))
        // slow breath of light low in the sky
        let breathe = 0.5 + 0.5 * sin(now * 0.22)
        ctx.fill(Path(CGRect(x: 0, y: h * 0.45, width: w, height: h * 0.55)),
                 with: .linearGradient(Gradient(colors: [.white.opacity(0), .white.opacity(0.05 + breathe * 0.03)]),
                                       startPoint: CGPoint(x: 0, y: h * 0.45), endPoint: CGPoint(x: 0, y: h)))
        if S.warm > 0.01 {
            let warm = Color(.sRGB, red: 1, green: 200/255, blue: 120/255, opacity: 1)
            ctx.fill(Path(CGRect(x: 0, y: h * 0.55, width: w, height: h * 0.45)),
                     with: .linearGradient(Gradient(colors: [warm.opacity(0), warm.opacity(S.warm * 0.10)]),
                                           startPoint: CGPoint(x: 0, y: h * 0.55), endPoint: CGPoint(x: 0, y: h)))
        }
        // PROTOTYPE (#weather): the weather mood sits between the warm sheet and the stars, so it reads
        // under the starfield and over the same sky. Image art when present, else the procedural draw.
        drawWeatherLayer(&ctx, size: size, weather: LiquidWeather(rawValue: weatherRaw) ?? .clear, sky: S, now: now)
        // stars
        if S.stars > 0.01 {
            for s in liquidStars {
                let baseA = 0.04 + s.z * 0.16
                let tw = pow(max(0, sin(s.ph + now * s.sp)), 6)
                let o = S.stars * (baseA + tw * 0.28)
                if o < 0.02 { continue }
                let sz = 0.6 + s.z * 0.8
                ctx.fill(Path(CGRect(x: s.x * w, y: s.y * h, width: sz, height: sz)), with: .color(.white.opacity(o)))
            }
        }
        // Settle into the page: a long fade to the theme's surfaceBase over the lower half so the sky
        // dissolves seamlessly into the body — no hard cut (the light-mode dark→white slam is gone).
        ctx.fill(Path(CGRect(x: 0, y: h * 0.45, width: w, height: h * 0.55)),
                 with: .linearGradient(Gradient(colors: [settle.opacity(0), settle.opacity(settleStrength)]),
                                       startPoint: CGPoint(x: 0, y: h * 0.45), endPoint: CGPoint(x: 0, y: h)))
    }
}

/// A subtle full-bleed time-of-day sky for any `ScreenScaffold.topBackground`, so the liquid
/// atmosphere carries across EVERY tab. Same live sky as Today at a modest header height, so the
/// charts/cards below sit on the dark canvas — the redesign's "the options change, not the page"
/// feel. Non-interactive + accessibility-hidden (pure decoration).
func liquidScaffoldSky(height: CGFloat = 240) -> AnyView {
    AnyView(
        LiquidSkyStatic(hour: nil)
            .frame(maxWidth: .infinity)
            .frame(height: height, alignment: .top)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    )
}

/// A STATIC time-of-day sky, rendered ONCE (no TimelineView → CoreAnimation caches it as a stable layer,
/// zero per-frame cost) for the scaffold backgrounds on the chart-heavy tabs. An always-animating Canvas
/// behind the charts stole frame headroom and caused stutter (2026-07-02); this is the same look
/// minus the twinkle/breath, matching the classic app's static scene image for scroll perf.
struct LiquidSkyStatic: View {
    var hour: Double?
    /// See `LiquidSky.settleStrength` — 1 = default seamless fade; <1 holds the atmosphere for the
    /// full-height "sky behind cards" backdrop.
    var settleStrength: Double = 1
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let h = hour ?? liveHour()
        let dark = scheme == .dark
        let settle = Color(.sRGB,
                           red: dark ? 18.0 / 255.0 : 242.0 / 255.0,
                           green: dark ? 21.0 / 255.0 : 242.0 / 255.0,
                           blue: dark ? 24.0 / 255.0 : 247.0 / 255.0,
                           opacity: 1)
        Canvas { ctx, size in
            let S = liquidSkyAt(h)
            let w = size.width, hh = size.height
            ctx.fill(Path(CGRect(x: 0, y: 0, width: w, height: hh)),
                     with: .linearGradient(Gradient(stops: [
                        .init(color: S.top, location: 0),
                        .init(color: S.mid, location: 0.5),
                        .init(color: S.hor, location: 0.9)]),
                                           startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: 0, y: hh)))
            if S.stars > 0.01 {
                for s in liquidStars {
                    let o = S.stars * (0.04 + s.z * 0.16)
                    if o < 0.02 { continue }
                    let sz = 0.6 + s.z * 0.8
                    ctx.fill(Path(CGRect(x: s.x * w, y: s.y * hh, width: sz, height: sz)),
                             with: .color(.white.opacity(o)))
                }
            }
            ctx.fill(Path(CGRect(x: 0, y: hh * 0.45, width: w, height: hh * 0.55)),
                     with: .linearGradient(Gradient(colors: [settle.opacity(0), settle.opacity(settleStrength)]),
                                           startPoint: CGPoint(x: 0, y: hh * 0.45), endPoint: CGPoint(x: 0, y: hh)))
        }
    }

    private func liveHour() -> Double {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        return Double(c.hour ?? 0) + Double(c.minute ?? 0) / 60
    }
}

// MARK: - PROTOTYPE weather layers (#weather)
//
// Procedural weather moods over the gradient, tinted by the current sky `S` (so they read at any hour).
// Cheap Canvas fills only — soft radial "clouds", short falling streaks for rain, drifting specks for snow,
// gradient veils for haze/fog. Values are a FIRST PASS to tune on-device (SwiftUI Canvas can't be previewed
// off-device). No-op when `.clear`.

/// PROTOTYPE (#weather): draw the weather as ART if a `weather_<mood>` image is in the asset catalog,
/// otherwise fall back to the procedural draw. The image is aspect-FILLED to the width, top-aligned (so the
/// sky shows), and blended so the gradient reads through it — `.screen` lets a light cloud image lighten the
/// sky at any hour; opacity + blend are the on-device tuning knobs once real art is in.
private func drawWeatherLayer(_ ctx: inout GraphicsContext, size: CGSize, weather: LiquidWeather,
                              sky S: (top: Color, mid: Color, hor: Color, stars: Double, warm: Double),
                              now: Double) {
    guard weather != .clear else { return }
    if let img = loadWeatherImage("weather_\(weather.rawValue)") {
        var wctx = ctx
        // Normal (source-over) is the universal default: an OPAQUE scene (e.g. a storm sky) covers the
        // gradient, and a TRANSPARENT overlay shows the gradient through its alpha. opacity lets the sky
        // bleed at the edges. TUNE per art: .plusLighter/.screen only suit art on a black field. (#weather)
        wctx.opacity = 0.9
        wctx.draw(img, in: CGRect(x: 0, y: 0, width: size.width, height: size.height))
        return
    }
    drawLiquidWeather(&ctx, size: size, weather: weather, sky: S, now: now)   // procedural fallback
}

private func drawLiquidWeather(_ ctx: inout GraphicsContext, size: CGSize, weather: LiquidWeather,
                               sky S: (top: Color, mid: Color, hor: Color, stars: Double, warm: Double),
                               now: Double) {
    guard weather != .clear else { return }
    let w = size.width, h = size.height
    switch weather {
    case .clear:
        break
    case .hazy:
        // A soft band of horizon-tinted haze low in the sky.
        let band = S.hor
        ctx.fill(Path(CGRect(x: 0, y: h * 0.5, width: w, height: h * 0.5)),
                 with: .linearGradient(Gradient(colors: [band.opacity(0), band.opacity(0.16), band.opacity(0.04)]),
                                       startPoint: CGPoint(x: 0, y: h * 0.5), endPoint: CGPoint(x: 0, y: h)))
    case .overcast:
        drawLiquidClouds(&ctx, w: w, h: h, tint: S.mid, now: now, coverage: 0.7)
    case .rain:
        drawLiquidClouds(&ctx, w: w, h: h, tint: S.mid, now: now, coverage: 0.85)
        drawLiquidRain(&ctx, w: w, h: h, now: now)
    case .fog:
        let fog = lerpColor(S.mid, .white, 0.45)
        ctx.fill(Path(CGRect(x: 0, y: h * 0.35, width: w, height: h * 0.65)),
                 with: .linearGradient(Gradient(colors: [fog.opacity(0), fog.opacity(0.34)]),
                                       startPoint: CGPoint(x: 0, y: h * 0.35), endPoint: CGPoint(x: 0, y: h)))
    case .snow:
        drawLiquidSnow(&ctx, w: w, h: h, now: now)
    }
}

/// A few soft radial "clouds" drifting slowly across, tinted toward the mid-sky so they read at any hour.
private func drawLiquidClouds(_ ctx: inout GraphicsContext, w: CGFloat, h: CGFloat, tint: Color,
                              now: Double, coverage: Double) {
    let cloud = lerpColor(tint, .white, 0.5)
    // (x0, y, radiusFrac, driftSpeed) — x0/y in [0,1]; drifts right and wraps.
    let blobs: [(x: Double, y: Double, r: Double, sp: Double)] = [
        (0.15, 0.24, 0.30, 0.006), (0.55, 0.18, 0.36, 0.004),
        (0.85, 0.30, 0.26, 0.008), (0.38, 0.36, 0.32, 0.005),
    ]
    for b in blobs {
        let x = CGFloat((b.x + now * b.sp).truncatingRemainder(dividingBy: 1.25) - 0.1) * w
        let y = CGFloat(b.y) * h
        let rx = CGFloat(b.r) * w, ry = CGFloat(b.r) * w * 0.5
        ctx.fill(Path(ellipseIn: CGRect(x: x - rx, y: y - ry, width: rx * 2, height: ry * 2)),
                 with: .radialGradient(Gradient(colors: [cloud.opacity(coverage * 0.5), cloud.opacity(0)]),
                                       center: CGPoint(x: x, y: y), startRadius: 0, endRadius: rx))
    }
}

/// Short diagonal streaks falling on a loop.
private func drawLiquidRain(_ ctx: inout GraphicsContext, w: CGFloat, h: CGFloat, now: Double) {
    for i in 0..<70 {
        let sx = (Double(i) * 0.61803).truncatingRemainder(dividingBy: 1)
        let x = CGFloat(sx) * w
        let phase = (now * 1.1 + Double(i) * 0.137).truncatingRemainder(dividingBy: 1)
        let y = CGFloat(phase) * h
        var p = Path()
        p.move(to: CGPoint(x: x, y: y))
        p.addLine(to: CGPoint(x: x - 4, y: y + 15))
        ctx.stroke(p, with: .color(.white.opacity(0.10)), lineWidth: 1)
    }
}

/// Drifting specks that sway as they fall — the starfield feel, brighter and moving.
private func drawLiquidSnow(_ ctx: inout GraphicsContext, w: CGFloat, h: CGFloat, now: Double) {
    for i in 0..<70 {
        let sx = (Double(i) * 0.61803).truncatingRemainder(dividingBy: 1)
        let sway = CGFloat(sin(now * 0.5 + Double(i))) * 8
        let phase = (now * 0.05 + Double(i) * 0.11).truncatingRemainder(dividingBy: 1)
        let x = CGFloat(sx) * w + sway
        let y = CGFloat(phase) * h
        let sz = CGFloat(1.4 + sx * 1.6)
        ctx.fill(Path(ellipseIn: CGRect(x: x, y: y, width: sz, height: sz)),
                 with: .color(.white.opacity(0.55)))
    }
}
