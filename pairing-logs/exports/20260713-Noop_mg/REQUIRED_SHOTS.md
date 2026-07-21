# Required shots - pack kind mixed

Playbook: docs/WHOOP_NOOP_SCREENSHOT_COMPARE.md
Export: C:\Users\Gilbert\Downloads\Noop mg-20260714T022949Z-1-001\Noop mg
Copied to: C:\Users\Gilbert\Documents\Ai app store\pairing-logs\exports\20260713-Noop_mg

| Id | Need | App | Target screen | Status |
|----|------|-----|---------------|--------|
| M1 | Home rings + Stress card | WHOOP | whoop_home | HAVE WHOOP files (7) - assign screen=whoop_home in manifest during decode |
| M2 | Stress Monitor if comparing stress | WHOOP | whoop_stress_monitor | HAVE WHOOP files (7) - assign screen=whoop_stress_monitor in manifest during decode |
| M3 | Sleep detail if comparing Rest | WHOOP | whoop_sleep_detail | HAVE WHOOP files (7) - assign screen=whoop_sleep_detail in manifest during decode |
| M4 | Today Health row | NOOP | noop_today_health | HAVE NOOP files (9) - assign screen=noop_today_health in manifest during decode |
| M5 | NOOP stress chart (often missing) | NOOP | noop_stress_timeline | HAVE NOOP files (9) - assign screen=noop_stress_timeline in manifest during decode |
| M6 | Sleep Rest hero | NOOP | noop_sleep_hero | HAVE NOOP files (9) - assign screen=noop_sleep_hero in manifest during decode |

After decode: mark each Id DONE only when a row has that screen value filled.
Stress packs: S5 (noop_stress_timeline) was missing in Noop-mg 2026-07-14 - always capture it.
