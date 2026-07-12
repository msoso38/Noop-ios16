# NOOP MG workspace status - 2026-07-12

**Purpose:** Structured check-in for future developers. Paste into a project-owned GitHub PR/issue when a remote is available.
**AI assistance:** Drafted by an AI coding agent from local handoff docs.
**Privacy:** This public-facing report uses placeholders only. Real Tailscale IPs, MagicDNS names, ADB serials, SSH hostnames, and local usernames live in private handoff files and must not be committed.
**Check back later:** After Fold USB ADB is re-enabled, >=3 WHOOP-labeled days land, and deploy gate passes.

### Placeholder legend

| Placeholder | Meaning |
|-------------|---------|
| `<WORKSPACE_ROOT>` | Local clone / workspace root |
| `<ADB_SERIAL_USB>` | Primary Fold USB ADB serial |
| `<FOLD_TAILSCALE_IP>` | Fold Tailscale / MagicDNS address |
| `<DESKTOP_TAILSCALE_IP>` | Dev desktop Tailscale address |
| `<DESKTOP_SSH_HOST>` | SSH Host alias for the desktop (not a real hostname) |
| `<WHOOP_EXPORT_PATH>` | Path to WHOOP / backup export files |
| `<EMULATOR_SERIAL>` | Local emulator id if used as secondary |

---

## 1. Verdict (read this first)

| Area | State |
|------|--------|
| Cycle forecast / period calendar | **Shipped** (engine + on-device verified) |
| WHOOP compare (Charge / Effort / Sleep / Stress) | **Shipped** |
| Sleep UI redesign | **Shipped** |
| Workout phone IMU + sport label confirm | **Shipped** |
| ML day1 to now + affine calibration | **In progress** - accuracy_valid: false |
| Radical UI polish (~100) | **In progress** - ~31 shipped / ~69 backlog; not on Fold (deploy gate) |
| Wireless Fold deploy | **Blocked** until calibration gate + wireless ADB to `<FOLD_TAILSCALE_IP>:5555` |
| Play Store publish | **Out of scope** (do not publish) |

**Bottom line:** UI and cycle/WHOOP surfaces moved a lot on 2026-07-12. Accuracy and Fold wireless deploy wait on paired labels and one USB ADB session on `<ADB_SERIAL_USB>`. Check back after those land.

---

## 2. Remotes / GitHub reality

| Location | Remote | Notes |
|----------|--------|--------|
| `<WORKSPACE_ROOT>` | **none** | Local git may be empty / unlinked; not the impeccable remote |
| `noop-v8.4.0-src` (active MG build) | **no own .git** | Under workspace; local MG fork |
| `noop-v8.5.2-upstream` | https://github.com/ryanbr/noop.git | Upstream reference only |
| `Tools/impeccable` | https://github.com/pbakaus/impeccable.git | Design skill; AI agents must not open issues/PRs without pbakaus/abdulwahabone instruction |
| Menstrudel | https://github.com/J-shw/Menstrudel.git | Unrelated reference |

**Official upstream for NOOP releases:** ryanbr/noop / NoopApp wiki.
**This status is about the local MG workspace**, not an upstream contribution request.

---

## 3. Current state - what recently shipped

Sources (under `<WORKSPACE_ROOT>` / `noop-v8.4.0-src/docs/`): ANY_MODEL_CONTINUE.md, ASKING_FOR_BUT_NEVER_GET.md, UI_POLISH_PASS_2026-07-12.md, CALIBRATION.md, ML_DAY1_TO_NOW.md.

### Cycle / period calendar
- Roll-forward so next period is never stuck in the past; long-range windows keep projecting.
- Gap-aware cycle length; Cycle tab on bar; auto-open month of next likely start.
- Flat card surfaces; orange-flood after .pc import fixed.
- Verified: unit tests + device NoopCycle on Fold (next approx 2026-08-01, August windows).
- True Cycle onboarding (5-step) shipped; import / existing starts skip it.

### WHOOP surfaces
- Vessels show WHOOP app labels when NOOP Charge/Effort null; HC null-HRV no longer blocks baseline seed.
- Compare card: Charge / Effort / Sleep / Stress with honest empty hints.
- Sleep: full night-first redesign (hero, list trends, tools strip, honest empty).

### Workouts / sensors / chrome
- Phone accel/gyro/mag during live workouts; post-workout sport confirm.
- Hold-+ frosted glass + triangle radial; nav symmetry with Cycle; theme packs as dropdown.
- Charge / Rest / Effort vessels optically equalized.

### Desk / ML plumbing (CPU-only)
- SSH via `<DESKTOP_SSH_HOST>` (maps to `<DESKTOP_TAILSCALE_IP>`); Hermes auto-start killed; do not start Gemma/CUDA from this pipeline.
- CPU train / calibrate / 12h report; scheduled NOOP-WhoopReport12h.
- docs/CALIBRATION.md + Tools/pull_fold_calibration.ps1.

---

## 4. In progress / incomplete (at time of this report)

| Workstream | Status | Detail |
|------------|--------|--------|
| ML day1 to now vs WHOOP + 12h reports | In progress | Pipeline runs; not accuracy-valid |
| WHOOP-NOOP affine calibration | In progress | Method in CALIBRATION.md; sparse N |
| Radical UI polish + per-page UX | In progress | ~31 shipped; ~69 backlog |
| Dual-app / Fold side-by-side leftovers | Open | |
| Full release DB merge after .noopbak | Open | Export from `<WHOOP_EXPORT_PATH>` / release backup, then Consolidate |
| Theme pack leftovers | Open | |
| Cycle onboarding replay control | Open | Nice-to-have |
| Reduced-motion mute for + hold aura/radial | Open | |
| High-rate strap IMU (R10 100 Hz) sport ID | Open | Phone IMU shipped; strap flood off |
| localhoop protocol/edge sport ID + HC enrichment | Open | Refs under Tools/_refs/ |
| Custom + rested-wake alarms | Open | In ANY_MODEL_CONTINUE.md |

---

## 5. Blockers

### Calibration / deploy gate
From pairing-logs/calibration-report.json (generated_at 2026-07-12T08:36:47Z):

- accuracy_valid: **false**
- deploy_gate: **FAIL** - need >=3 paired days on Charge, Effort, Sleep
- Effort: **N=1**, MAE before affine approx **2.88** (shared 0-100); affine not fitted
- Charge / Sleep / Stress: **N=0**
- Policy: never invent WHOOP labels; sparse N = gate fail

Wireless Fold deploy blocked until gate passes or explicit maintainer exception.

### ADB wireless
- Fold may be reachable on the VPN while wireless ADB to `<FOLD_TAILSCALE_IP>:5555` refuses.
- Need one USB session on `<ADB_SERIAL_USB>`: `adb -s <ADB_SERIAL_USB> tcpip 5555`, then `Tools/pull_fold_calibration.ps1`.
- Secondary only: `<EMULATOR_SERIAL>` (not primary).
- Deploy: `Tools/deploy_live_edit.ps1 -Serial <ADB_SERIAL_USB>`.

### User data actions still needed
1. Log >=3 days WHOOP app Recovery %, Sleep %, Day Strain into NOOP / JSONL (or import from `<WHOOP_EXPORT_PATH>`).
2. Wear overnight so NOOP scores exist for pairing.
3. Export release .noopbak, then Consolidate into debug for full DB merge.
4. On Fold: confirm Cycle tab near August (rose=likely, amber=window).

---

## 6. What to do next / check back later

**When to check back**
- After USB ADB tcpip on `<ADB_SERIAL_USB>` + successful pull_fold_calibration.ps1
- After >=3 labeled paired days: re-run calibrate; look for accuracy_valid: true
- Then Fold deploy of polish backlog; continue toward ~100 polish items
- Then localhoop sport ID / alarms / release consolidate

**Agent paste (token-cheap):**

```text
Read ANY_MODEL_CONTINUE.md. USB Fold <ADB_SERIAL_USB>. Cycle August should work - confirm UI.
Export release .noopbak then Consolidate. Then localhoop ML / alarms.
```

**Do not**
- Force-push
- Play Store publish
- Open PRs/issues on pbakaus/impeccable from an AI agent without maintainer instruction
- Commit real Tailscale IPs, MagicDNS names, ADB serials, or local usernames
- Treat synthetic ML pipeline pass as model accuracy

---

## 7. Doc index

| Doc | Role |
|-----|------|
| ANY_MODEL_CONTINUE.md | Live handoff (private; may contain real device ids) |
| docs/ASKING_FOR_BUT_NEVER_GET.md | Repeated asks + ship status |
| docs/CALIBRATION.md | Affine method + citations + gate |
| docs/ML_DAY1_TO_NOW.md | Training checkpoints |
| docs/UI_POLISH_PASS_2026-07-12.md | Polish shipped vs backlog |
| pairing-logs/calibration-report.json | Machine-readable gate |

*Generated 2026-07-12 for PR/handoff. Update when gate flips or major asks move to done.*
