# Asking for but never get

Living wishlist of things the user has asked for repeatedly. Agents must update status here when starting and finishing related work. Cross-link from `ANY_MODEL_CONTINUE.md`.

| Ask | Status | Notes |
|-----|--------|-------|
| **True Cycle onboarding** (multi-step first-run: opt-in, last period, typical length, what predictions mean, how to log; teaching empty states) | **done** | Shipped 2026-07-12 — `CycleOnboarding.kt` 5-step flow; import / existing starts skip it |
| **Cycle UI redo** (usable month, phase, forecast, logging; not ugly nested chrome) | **done** | Flat phase/forecast/calendar/day panel; GlowCards removed from phase/reminders |
| **Nav bar symmetry when Cycle tab is present** | **done** | `barLeftTabCount`: 5 tabs → 3\|2 (Today·Trends·Cycle \| Sleep·More) + weight by count |
| **Press-and-hold on +** (Apple-like 3D/force feel: charging-style blur + tiny sparkle shimmer) | **done** | Superseded by glass+triangle hold (2026-07-12 later) |
| **Period calendar not orange-flooded after .pc import** | **done** | 2026-07-12 — near-start collapse + halfWidth cap ±4 + month-only paint + quiet DayCell ticks (no phase letters) |
| **Crescent center nested / + kiss after nav+hold work** | **done** | 2026-07-12 — + in gutter; re-tightened 2026-07-12 eve (gutter 36dp, shallower bite, theme aura restored) |
| **WHOOP recovery & strain visible when data exists** | **done** | 2026-07-12 — HC null-HRV no longer blocks baseline seed; vessels show WHOOP **app** labels when NOOP Charge/Effort null; one-shot rescore |
| **WHOOP/HC sleep in Sleep + Sleep UI overhaul** | **done** | 2026-07-12 eve — **full redesign** (night-first hero, list trends, tools strip, honest empty); not restyle of card stack |
| **Next period never past / never June-as-next in July** | **done** | 2026-07-12 — roll-forward + filter windows ≥ today; UI hides past `nextPeriodLikely`; Last logged ≠ next; unit tests |
| **WHOOP vs NOOP comparison shows Charge/Effort/Sleep/Stress** | **done** | 2026-07-12 — compare pulls recent WHOOP app labels + HC/my-whoop sleep; all 4 heads + honest empty hints |
| **Hold + frosted glass + triangle radial (hold-and-swipe)** | **done** | 2026-07-12 eve — New Workout **at top**; bouncy spring pop; easier swipe threshold; theme aura on + |
| **Turn off Cycle tab from nav (setting that removes it)** | **done** | 2026-07-12 — `showPeriodCalendarTab = cycleTrackingEnabled` (was hardcoded true); Settings copy updated; More still has Cycle |
| **Theme pack selection less AI (dropdowns not card galore)** | **done** | 2026-07-12 — Appearance theme packs → dropdown |
| **Charge / Rest / Effort vessels optically equal** | **done** | 2026-07-12 eve — equal diameter + weight(1f) columns + reserved badge slot height |
| **Workout motion sensors + post-workout sport label ask** | **done** | 2026-07-12 eve — phone accel/gyro/mag during live workouts; confirm sheet; `WorkoutLabelStore` + SportClassifier phone hints |
| **Apple Watch–like hand grip gesture controls** | **done** | 2026-07-12 eve — phone grip-pulse approx (double → Workouts); **limits**: not Watch force/EMG; prefer strap double-tap |
| **ML day1→now vs WHOOP + every-12h reports** | **in progress** | CPU pipeline + `pull_fold_calibration.ps1`. Gate **FAIL**: Effort N=1 MAE≈2.9; Charge/Sleep/Stress N=0. Fold pingable on Tailscale but ADB 5555 refused — need USB once for `adb tcpip 5555`. User must log ≥3 WHOOP Recovery/Sleep/Strain days + wear overnight for NOOP scores. |
| **WHOOP↔NOOP affine calibration (papers/repos)** | **in progress** | `docs/CALIBRATION.md`; report still sparse. Deploy blocked until accuracy_valid. |
| **Radical UI polish (~100) + per-page UX** | **in progress** | `docs/UI_POLISH_PASS_2026-07-12.md` — **~34 shipped** / ~66 backlog (wrong numbers + flatten batch 17–31). Not on Fold (deploy gate). |
| Dual-app / Fold side-by-side polish leftovers | open | |
| Full release DB merge after `.noopbak` export | open | User action required |
| Theme pack leftovers | open | |
| Cycle onboarding reset control (debug / Settings “replay setup”) | open | Nice-to-have if user wants to re-see the flow after import |
| Reduced-motion mute for + hold aura / radial | open | Honor system remove-animations |
| Sleep track still less ugly (continued overhaul) | **done** | Superseded by full redesign 2026-07-12 eve — reopen only if user still hates composition |
| Kill Hermes auto-start on PC via SSH | **done** | 2026-07-12 — `\Hermes_Gateway` Scheduled Task State=Disabled; sibling owns live GPU hog kill. Gemma Run key absent; no Gemma process at quiet check. |
| Kill Gemma local model server / startup | **done** | 2026-07-12 — no `Gemma4 Local Model Server` in HKCU Run / no gemma process in tasklist; sibling continues GPU kill. Do not restart. |
| High-rate strap IMU (R10 100 Hz) for sport ID | open | Phone IMU shipped; strap R10 flood still disabled (battery) |

## Rules
- Mark **in progress** when work starts; **done** only when shipped to Fold (or emulator if Fold unavailable) and paths work end-to-end.
- Add new repeated asks here instead of burying them only in chat.
- Do not clear rows — set status to done with a short ship note.
