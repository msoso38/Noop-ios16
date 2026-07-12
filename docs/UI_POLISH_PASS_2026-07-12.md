# UI polish pass — 2026-07-12 (continuous)

Living inventory toward ~100 concrete polish items + ~30 UX ideas per major page.
**Shipped this pass** vs **backlog**. Keep flat / product register / UGLY_AVOID.

## Shipped this pass (wrong numbers + clunk first)

32. Sleep tools strip: Bed/Wake/Wake-window one row (cover) — SleepToolsStrip one-row
33. Sleep StagesVsTypical: tighter vertical rhythm
34. StageRow label/bar gap tightened

1. Rest vessel: WHOOP app `sleepPct` fallback when NOOP Rest null (`TodayScreen` + `WhoopAppScoreStore`)
2. Effort vessel: explicit `/21` or `/100` source pill so app Strain is not misread as /100
3. `WhoopAppScoreStore` parses `sleep_pct` / `stress_pct` (was dropping sleep)
4. `WhoopAppScoreParser.toDayScores` writes `sleepPct` field (was stuffing into stressNote)
5. Auto-capture merges sleep into store + logs sleep
6. Trends Week-in-review: removed tinted `NoopCard` → flat on sky
7. Trends Charge history: untinted card, shorter footnote, no hairline divider stack
8. Alarm subtitle shortened
9. Alarm ExplanationCard: flat footnote, no nested card + icon chrome
10. Alarm PersonalSleepPlanCard: flat on sky, quieter copy
11. Workouts empty: flat copy (no DataPendingNote card wash)
12. Workouts HR Zones: untinted card, shorter caption, no CardDivider
13. Settings sections: no accent wash tint, tighter padding, footnote blurbs
14. `docs/CALIBRATION.md` + CPU calibrate/train/12h report scripts
15. Metric verification report written (`pairing-logs/calibration-report.json`)
16. ASSET path for sleep labels ready for next adb captures
17. Hero provenance + source pill collapse when identical (On-device≈NOOP, Whoop≈WHOOP app)
18. Your-cards / key-metric SpO2: hide when no sample (no fake %)
19. Hydration already gated; SpO2 join keeps empty rows from reserving space
20. Charge breakdown: drivers + contributors flat (no nested NoopCard)
21. Stress pin: honest `0–3 load band` + `/3` unit (not Baevsky SI / not WHOOP %)
22. Compare card Stress label: `Stress (0–3 band)`
23. Sleep dual strip: show WHOOP Sleep % when only app has it
24. Trends MetricTrendCard: unit once in title; Mean/Min/Max bare
25. Trends export PDF footer: untinted, shorter copy
26. Week-in-review chevrons: 48dp hit targets
27. Workouts SportCard + session Effort card: untint Effort wash
28. Alarm Strap wake + Wind-down: untint washes
29. SettingsSection: drop repeating "Settings" overline
30. Cycle ImportCard: untint cyan/accent wash
31. `Tools/pull_fold_calibration.ps1` for Fold pull + recalibrate (ADB gate)

## Backlog toward ~100 (implement next)

### Today (continue)
18. Quieter key-metric grid gaps on Fold cover *(partial via SpO2 hide)*
21. Live Effort caption when 0.0 on easy day (already exists — verify Fold)
22. Mini collapsed vessels: ensure equal optical weight after sink
23. Remove any leftover large NOOP wordmark if reintroduced
26. Compare card: move below fold less aggressively on cover screen

### Sleep
28. Stage rows: tighten vertical rhythm another 4dp **done**
29. Tools strip: one row, not two wraps on cover **done**
30. Empty: single sentence (already redesigned — verify)
31. Trends list: weekday abbreviation consistency
32. Nap editor: quieter confirm
33. Motion strip: label “relative shape” always visible
34. Hero Rest vessel aura chasm check on Fold fold crease
35. Import CTA only when truly no nights

### Trends
36. ChartCard liquid hero: optional flatten for ALL range
38. Range caption: one line only (already partly)
41. Sparse placeholder copy shorter
42. Effort chart Y tick format 1 decimal on WHOOP scale *(UnitFormatter path — verify)*
43. Remove staggeredAppear if Reduce Motion
44. Small-multiples vertical gap −4dp

### Cycle
47. Day panel: less padding on cover
48. Phase hero: one line recovery note max
49. Forecast tick contrast tweak
50. Onboarding replay control (ask-list)
51. Month swipe inertia
52. Selected day hairline stronger
53. Hide past nextPeriodLikely always (regression guard)

### Workouts
55. Effort hero: scale caption under vessel *(untinted; caption verify)*
56. Filter pills: fewer simultaneous actives
57. Selection mode bar flatter
58. Post-log banner: auto-dismiss timing
59. Zones section header trailing quieter
60. Manual add dialog: one column on cover
61. Sport confirm sheet (already): verify after live end
62. IMU note in session detail when annotated

### Alarm
63. WindowCard: flatten if still nested *(scenic hero kept)*
65. CustomAlarmsCard: flat list *(already)*
67. Charge threshold stepper hit size
68. Exact-alarm permission row: one tap target
69. Reduce Motion mute for any remaining aura

### More / Settings
71. Advanced disclosure default closed verify
72. Theme pack dropdown already — ensure no card galore regress
73. Data Sources row density
74. Devices: single active band emphasis
75. Experimental probes: smaller type
76. Backup/export: one primary CTA
77. How NOOP works: link from Effort pill only
78. Notifications settings: group quieter
79. Cycle tab toggle copy: one sentence
80. Effort scale control: preview 14.7/21 example inline

### Cross-cutting / scoring
81. Export `noop-daily-metrics.jsonl` from debug for Charge/Sleep pairs *(pull script ready; on-device export still open)*
82. Affline cal apply path on-device when `accuracy_valid` (future)
83. Stress head: capture WHOOP stress when UI shows it
84. Sleep κ eval script G4 when accel available
85. Compare TrainingStatusStrip refresh from new status JSON
86. Bump `WhoopNoopAlignment` MODEL_VERSION when affine ships on-device
87. HC sleep duration vs Rest % dual display *(Sleep strip improved)*
88. Never show effort_proxy as user-facing Effort
89. Fold cover: vessel diameter clamp review
90. Tablet / unfold: maxWidth vessel row

### Nav / chrome
91. Reduced-motion mute for + hold aura (ask-list)
92. Triangle New Workout top — regression check
93. Crescent gutter 36dp — regression check
94. Grip double-pulse → Workouts — experimental badge
95. Alongside dual-app polish leftovers (ask-list)

### Copy / a11y
96. TalkBack labels on vessel source pills
97. Alarm guaranteed-wake announcement
98. Empty states: no “seamless/robust” banned prose
99. Compare empty hints: shorter
100. Changelog one-liner for this polish batch (when releasing)

## ~30 UX ideas per major page (highest impact subset shipped; rest backlog)

| Page | Shipped now | Backlog examples (see numbers above) |
|------|-------------|--------------------------------------|
| Today | 1–5, 17–22 | 18, 21–23, 26 |
| Sleep | store sleep path + 23 | 28–35 |
| Trends | 6–7, 24–26 | 36–44 |
| Cycle | 30 | 47–53 |
| Workouts | 11–12, 27 | 55–62 |
| Alarm | 8–10, 28 | 63–69 |
| More/Settings | 13, 29 | 70–80 |

## Counts

- Prior batch: ~16
- This session: **+15** (items 17–31)
- Total shipped inventory: **~31**
- Remaining backlog: **~69**

## Deploy

**No** — calibration gate FAIL (`accuracy_valid=false`). Fold Tailscale ping OK but ADB `:5555` refused; USB not attached. Charge/Sleep/Stress N=0, Effort N=1. Do not wireless/USB push until ≥3 paired days + accuracy_valid.
