---
name: NOOP Android
description: On-device WHOOP companion — dark lacquer, kinpaku gold accent, Material 3 structure, dual-scale honesty.
colors:
  accent-gold: "#D4A84B"
  charge: "#E8C36A"
  effort: "#4A90E2"
  rest: "#7EB8A8"
  surface-base: "#0C0D10"
  surface-raised: "#16181E"
  surface-inset: "#1C1F26"
  text-primary: "#F2F0EA"
  text-secondary: "#A8A49A"
  text-tertiary: "#6E6A62"
  hairline: "#FFFFFF22"
  status-positive: "#3D9B6E"
  status-warning: "#C9A227"
  status-critical: "#C45C5C"
  whoop-app-blue: "#5B9DFF"
typography:
  roles: [display, headline, title, body, label, footnote, overline]
  body: system sans (Roboto / device)
  numbers: tabular, medium-bold for scores
spacing:
  unit: 4dp
  card-padding: 16–18dp
  section-gap: 12–20dp
  nav-height: 58dp
shape:
  card-radius: 16–20dp
  pill: 999dp
  bar: 50%
components:
  - GlassBottomBar (liquid pill, center +)
  - GlowRing / BevelGauge (crisp arc, NO bloom glow)
  - WhoopScoreCompareCard (dual-scale Effort track)
  - WetBounceButton (opaque flat, no radial wash)
  - StatePill (dot off by default)
  - LiquidBatteryRing (bolt when charging)
motion:
  nav-crossfade: 160ms ease-out
  liquid-spring: stiffness 720, damping 0.58
  ban: bounce, elastic, random glow pulses on static chrome
anti_patterns:
  - random radial glows / ring bloom underlays
  - nested cards
  - inventing WHOOP app numbers from open BLE
  - showing Strain 14.7 as if /100
platform: android
register: product
---

# DESIGN — NOOP Android (Impeccable)

## Visual theme

Dark warm-black lacquer canvas, gold accent for Charge / primary actions, cyan/blue for Effort and WHOOP-app column. Liquid sky backdrop optional (day-cycle pref). **No decorative glow.** Rings are crisp arcs on tracks.

## Layout

- Bottom navigation: Today · Trends · (P.C.) · Sleep · More with center **+** (Log workout / Strength trainer)
- Today: liquid header → wordmark → Charge/Effort/Rest hero → **NOOP vs WHOOP app** compare → sessions
- Compare card always explains 0–21 vs 0–100

## Components rules

| Do | Don't |
|----|--------|
| Opaque buttons, hairline borders | Soft centre glow orbs |
| Dual-scale labels on Effort/Strain | Single number without units |
| Awaiting WHOOP labels | Synthetic 100% pass |
| One What's New sheet per version bump | Silent feature ships |

## Impeccable force-use

All UI PRs must cite PRODUCT.md + this file. Agent AGENTS.md requires Impeccable skill before Compose edits.
