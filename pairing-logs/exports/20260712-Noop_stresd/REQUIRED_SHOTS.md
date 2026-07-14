# Required shots - pack kind stress

Playbook: docs/WHOOP_NOOP_SCREENSHOT_COMPARE.md
Export: C:\Users\Gilbert\Downloads\Noop stresd-20260712T213610Z-2-001\Noop stresd
Copied to: C:\Users\Gilbert\Documents\Ai app store\pairing-logs\exports\20260712-Noop_stresd

| Id | Need | App | Target screen | Status |
|----|------|-----|---------------|--------|
| S1 | Home Stress Monitor card (tip+band) | WHOOP | whoop_home | HAVE WHOOP files (3) - assign screen=whoop_home in manifest during decode |
| S2 | Full Stress Monitor chart + high-zone copy | WHOOP | whoop_stress_monitor | HAVE WHOOP files (3) - assign screen=whoop_stress_monitor in manifest during decode |
| S3 | Today Health/Key Metrics stress row | NOOP | noop_today_health | HAVE NOOP files (4) - assign screen=noop_today_health in manifest during decode |
| S4 | Stress hero Now tip | NOOP | noop_stress_hero | HAVE NOOP files (4) - assign screen=noop_stress_hero in manifest during decode |
| S5 | Intraday timeline + time-in-band (do not skip) | NOOP | noop_stress_timeline | HAVE NOOP files (4) - assign screen=noop_stress_timeline in manifest during decode |

After decode: mark each Id DONE only when a row has that screen value filled.
Stress packs: S5 (noop_stress_timeline) was missing in Noop-mg 2026-07-14 - always capture it.
