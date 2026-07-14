# Weather overlay art (PROTOTYPE, #weather)

The day-cycle sky (`LiquidSky`) can draw an **image** for each weather mood, blended over the time-of-day
gradient. When the image for a mood is **absent, the sky falls back to the procedural draw** — so the app
works with zero art, and dropping a file in upgrades that mood with no code change.

## Asset names (one PNG per mood; all optional)

| Mood     | Asset base name     |
|----------|---------------------|
| Hazy     | `weather_hazy`      |
| Overcast | `weather_overcast`  |
| Rain     | `weather_rain`      |
| Fog      | `weather_fog`       |
| Snow     | `weather_snow`      |

(`clear` has no art by design.)

## Where the files go

- **iOS / macOS:** add each as an image set in `Strand/Resources/Assets.xcassets/` (and
  `StrandiOS/Resources/Assets.xcassets/`), named exactly `weather_rain`, etc. Loaded via `UIImage(named:)` /
  `NSImage(named:)`.
- **Android:** drop each PNG/WebP in `android/app/src/main/res/drawable-nodpi/` as `weather_rain.png` (etc.).
  Resolved by name at runtime (`getIdentifier`), so no `R.drawable` reference is needed.

## Art guidance

- **Transparent PNG**, wide/landscape (the image is aspect-filled to the screen width, top-aligned), sky
  content toward the top. The lower part fades into the page, so weather near the top reads best.
- Designed to **blend** over a coloured gradient (drawn with `.screen` at ~0.85 opacity by default), so the
  sky still shows through and tints it. Light, semi-transparent cloud/precip art works best; avoid a baked-in
  opaque sky colour (that would fight the gradient).
- The blend mode + opacity are the on-device tuning knobs — see the `TUNE:` comments in `LiquidSky.swift` /
  `LiquidSky.kt`.

## Licensing — REQUIRED before committing any art

Unlike the inherited `scene1–10` backdrops (which carry no recorded source), **every weather image added here
must have its license recorded** — add a line per file below with the source and license. Only commit art you
have the right to ship (self-generated, commissioned, or a clearly-permissive/CC licence with attribution as
required).

| File | Source | License |
|------|--------|---------|
| `weather_rain.png` (1500×1000) | ⚠️ **TO CONFIRM** — supplied for the prototype; provenance not yet recorded | ⚠️ **TO CONFIRM before this ships/merges** |
