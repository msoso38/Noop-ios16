# Product

## Register

product

## Platform

android

## Users

Athletes and health-curious people who wear a WHOOP strap (or similar band) and want honest recovery / effort / sleep scores **on their phone**, offline, without a WHOOP subscription cloud. They compare NOOP's open-BLE algorithm against the official WHOOP **app** scores. Power users on Android (Fold, wireless adb, dual-app alongside mode).

## Product Purpose

NOOP is an on-device WHOOP companion: bank strap history and live HR over BLE, score Charge / Effort / Rest locally, and show a **truthful** side-by-side with official WHOOP app labels (export, adb UI, or manual). Never invent clinical vitals (BP, SpO2%, AFib, VO2). Success = the user trusts empty states, dual-scale Strain (0–21) vs Effort (0–100), and can train alignment from real paired days.

## Brand Personality

Honest, calm, athletic, precise. Short labels. No marketing essays. Gold on warm-black lacquer (kinpaku-adjacent restraint), Material 3 structure, liquid sky accents that don't glow randomly.

Three-word personality: **honest, calm, precise**.

## Anti-references

- Fake WHOOP parity (invented Recovery/Strain from open GATT)
- Random glows, bloom orbs, nested glass cards, purple SaaS gradients
- Hero-metric clichés with empty "AI accuracy 100%" when labels are missing
- Clinical claims (ECG, SpO2%, blood pressure, VO2 max) not on the open link

## Design Principles

1. **Honest empty > fake full.** Awaiting WHOOP labels shows "—" and how to import, never zeros dressed as scores.
2. **Dual-scale always.** WHOOP Day Strain is 0–21; NOOP Effort is 0–100; pass math only after ×100/21.
3. **Material 3 structure, NOOP tokens.** Bottom nav 3–5 destinations, system Back, 48dp targets, no iOS-only chrome.
4. **Impeccable is mandatory.** Every UI change follows PRODUCT.md + DESIGN.md + the Impeccable skill. No exception.
5. **One primary action per surface.** Log WHOOP app scores, Sync, Restart strap — not a wall of equal buttons.

## Accessibility & Inclusion

- WCAG-minded contrast on dark and light themes
- Dynamic type (sp), large-text German/Spanish/French layouts
- `prefers-reduced-motion` / Android remove animations: crossfade only
- Touch targets ≥ 48dp
- Cycle / period calendar is women-first copy, not a bolted fitness toggle

## Impeccable

Always-on for this repo. Skill: `.agents/skills/impeccable`. Hooks: design anti-patterns on UI edits. Live config: `.impeccable/live/config.json` (android product).
