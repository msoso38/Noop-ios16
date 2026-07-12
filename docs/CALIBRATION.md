# WHOOP ↔ NOOP calibration

Honest, paper-grounded alignment of NOOP Charge / Effort / Sleep / Stress to
**WHOOP app** labels. Never invent WHOOP numbers. Never treat synthetic pipeline
pass as accuracy.

## Approach (what we fit)

When ≥3 completed days have both a NOOP score and a WHOOP **app** label for a
head, fit a per-head **affine map**:

```
y_whoop ≈ a · y_noop + b
```

on the shared 0–100 scale (WHOOP Day Strain 0–21 is mapped with `×100/21`
before the fit; display still shows `/21`). Evaluate with leave-one-out MAE
and Pearson *r* when *n* ≥ 3. Below that gate, report paired deltas only and
set `accuracy_valid = false`.

Optional Ridge (α=1) multivariate recovery fit (HRV, RHR, sleep hours, prior
strain) mirrors personal WHOOP-insights style models when feature rows exist —
still labels-only targets, never synthetic y.

## Sources (citations)

| Topic | Source | How we use it |
|-------|--------|----------------|
| Recovery / HRV readiness | Plews DJ et al. *Sports Med* 2013 — ln-RMSSD vs personal baseline | Matches NOOP `RecoveryScorer` / OpenStrap `calcRecovery` z-score mindset |
| Strain / load | Banister EW. *Can J Appl Sport Sci* 1991; Morton / Fitz-Clarke & Banister *J Appl Physiol* 1990 — TRIMP | Matches `StrainScorer` TRIMP → log Effort |
| Sleep staging / actigraphy | Cole RJ, Kripke DF et al. *Sleep* 1992 | Sleep window / efficiency priors in Rest path |
| Stress | Baevsky RM & Berseneva AP, 2008 — Stress Index | `StressIndex` histogram SI |
| ACWR load context | Gabbett TJ *BJSM* 2016; Hulin et al. 2016 | Effort trend context only (not a WHOOP clone) |
| Open reference impl | [OpenStrap/analytics](https://github.com/OpenStrap/analytics) (`recovery.ts`, `ALGORITHMS.md` in `Tools/_refs/analytics`) | Published metric catalog we already vendor locally |
| Personal WHOOP MLR | [idossha/whoop-insights](https://github.com/idossha/whoop-insights) `mlr.py` — Ridge on recovery | Affine / Ridge calibration pattern against app labels |
| Zepp personal z-score | [n0Pnyk/zepp-health-analytics](https://github.com/n0Pnyk/zepp-health-analytics) | 7d vs 60d z → 0–100 readiness (inspiration for baseline windows) |
| NOOP science | [NoopApp/noop Wiki — The Science](https://github.com/NoopApp/noop/wiki/The-Science) | Documents Charge logistic anchor (~58% at z=0) |

Local refs: `Tools/_refs/analytics/ALGORITHMS.md`, `android/.../WhoopNoopAlignment.kt`,
`RecoveryScorer.kt`, `StrainScorer` / Rest / `StressIndex.kt`.

## Code path

| Piece | Path |
|-------|------|
| On-device alignment / bands | `android/.../WhoopNoopAlignment.kt` |
| App labels store | `android/.../WhoopAppScoreStore.kt` + `assets/whoop_app_labels.jsonl` |
| CPU train / affine cal | `Tools/ml_engine_train.py`, `Tools/calibrate_whoop_noop.py` |
| 12h report | `Tools/whoop_noop_12h_report.py` |
| Status | `pairing-logs/ml-engine-status.json`, `pairing-logs/calibration-report.json` |
| Living log | `docs/ML_DAY1_TO_NOW.md` |

## GPU policy (desk)

Do **not** start CUDA / llama / Gemma jobs from this pipeline. Scripts are
CPU-only (stdlib + optional numpy). If a sibling later runs heavier ML on the
desktop, cap GPU ≈75% (AMD: Adrenalin / process affinity; NVIDIA:
`torch.cuda.set_per_process_memory_fraction(0.75)` or power limit). Documented
in `ANY_MODEL_CONTINUE.md`.

## Gate for deploy

Ship wireless Fold deploy only when `calibration-report.json` shows
`accuracy_valid: true` with honest *N* and before/after MAE (or correlation)
for Charge, Effort, Sleep, Stress — or an explicit documented exception with
user OK. Sparse *N* (e.g. 1 label day) → **gate fail**, keep improving labels.

## What the user must sync (when Fold ADB is dark)

As of 2026-07-12: Tailscale host may ping while **ADB :5555 refuses**. One USB
session to run `adb -s <ADB_SERIAL_USB> tcpip 5555`, then
`Tools/pull_fold_calibration.ps1`.

For ≥3 paired days on Charge / Effort / Sleep (Stress optional):

1. Open **WHOOP app** each morning; capture Recovery %, Sleep %, Day Strain
   (0–21), Stress if the UI shows it.
2. In **NOOP**, Log WHOOP app scores or enable Accessibility auto-capture for
   those completed days (assets/JSONL must carry `recovery_pct` / `sleep_pct`,
   not Strain alone).
3. **Health Connect**: grant Sleep + Heart Rate; let WHOOP sync into HC.
4. **Wear overnight** so NOOP scores Charge / Rest / Effort. Feature
   `effort_proxy_0_100` is heuristic only — never user-facing Effort.
5. Prefer exporting `pairing-logs/noop-daily-metrics.jsonl` when Charge/Sleep
   NOOP values exist beside labels.
