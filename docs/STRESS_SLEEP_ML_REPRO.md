# Stress + sleep ML reproduce guide

End-to-end path to recreate the **stress tip** and **sleep Rest / stage** evaluation results
checked into this PR. Wellness approximations only — never invent SpO2/BP/clinical stages.

## What was trained (2026-07-14)

| Head | Method | Inputs | Gate |
|------|--------|--------|------|
| **Stress tip** | Affine `whoop_tip ≈ a·noop_tip + b` on 0–3 scale | Clock-matched screenshot labels | `accuracy_valid` only with ≥4 tip@clock pairs + ≥75% same band |
| **Sleep Rest vs Sleep%** | Affine trend check (related, not identical scales) | WHOOP `sleep_pct` + NOOP `noop_rest` | Always honest; RestScorer tickets in factor docs |
| **Sleep stages (G4)** | `Tools/sleep_stage_eval.py` heuristic + mini-ML lane | Synthetic smoke **or** PhysioNet sleep-accel | Real κ only after sleep-accel download |
| **Effort → Strain** | `Tools/ml_engine_train.py` linear+ridge | BLE `ml-samples.jsonl` + `strain_021` labels | `accuracy_valid` needs ≥3 distinct labeled days |

Artifacts (generated under `pairing-logs/`):

- `stress-sleep-train-status.json` — orchestrator summary
- `ml-stress-tip-weights.json` — stress tip affine coefficients
- `sleep-stage-eval.json` — stage bench (synthetic until sleep-accel present)
- `ml-engine-status.json` / `ml-effort-weights.json` — effort→strain
- `calibration-report.json` — multi-head Charge/Effort/Sleep/Stress table

## Data (golden + how to rebuild)

### Checked in (small)

| Path | Role |
|------|------|
| `pairing-logs/whoop-app-labels.jsonl` | Real WHOOP/NOOP labels (adb UI + decoded screenshots) |
| `pairing-logs/exports/20260712-Noop_stresd/manifest.csv` | Decoded stress pack taxonomy + values |
| `pairing-logs/exports/20260713-Noop_mg/manifest.csv` | Decoded mg pack (stress tip + sleep stages) |
| `pairing-logs/exports/*/REQUIRED_SHOTS.md` | Pack coverage checklist |
| `pairing-logs/golden/README.md` | What is/isn't in git |

### Not checked in (rebuild locally)

| Path | Why | Rebuild |
|------|-----|---------|
| Full JPGs under `exports/*/` | Large binary dumps | `Tools/ingest_export.ps1 -PackKind stress|sleep -ExportDir <Downloads folder>` |
| `ml-samples.jsonl` / `noop-pairing-log.txt` | Large BLE banks | Fold collectors when phone is **on**; this session Fold was plugged-in but **powered OFF** |
| PhysioNet sleep-accel | License + size | See `docs/SLEEP_ML_DATASETS.md` → unpack to `pairing-logs/datasets/sleep-accel` |

Primary clock-matched stress anchor (mg pack):

```text
2026-07-13 22:14  WHOOP tip 1.4 MEDIUM  ↔  NOOP tip 1.8 / 3 MEDIUM  (δ +0.4)
WHOOP sleep 64% / Recovery 43% / Strain 10.2
NOOP Rest 73 Strong with Deep 2% / REM 0%  (REST-WEIGHT ticket)
```

## Reproduce (desk or laptop)

Desk SSH (Tailscale Host alias `desk` → DESKTOP-UEJPJSH, user `Gilbert`, key `~/.ssh/id_ed25519`).
GPU on desk is AMD RX 7900 XTX; these scripts are **CPU affine** — fine while Hermes/Gemma stay killed.

```powershell
# From AI app store root (or this noop checkout with pairing-logs present):
python Tools\stress_sleep_train.py --as-of 2026-07-14

# Individual lanes:
python Tools\ml_engine_train.py --labels pairing-logs\whoop-app-labels.jsonl --as-of 2026-07-14
python Tools\sleep_stage_eval.py --synthetic
# After sleep-accel download:
# python Tools\sleep_stage_eval.py --sleep-accel-dir pairing-logs\datasets\sleep-accel
python Tools\calibrate_whoop_noop.py --pairing pairing-logs
```

Remote one-shot (from laptop):

```powershell
scp Tools\stress_sleep_train.py Tools\sleep_stage_eval.py Tools\ml_engine_train.py desk:"C:/Users/Gilbert/Documents/Ai app store/Tools/"
scp pairing-logs\whoop-app-labels.jsonl desk:"C:/Users/Gilbert/Documents/Ai app store/pairing-logs/"
ssh desk powershell -NoProfile -Command "cd 'C:\Users\Gilbert\Documents\Ai app store'; python Tools\stress_sleep_train.py --as-of 2026-07-14"
```

## Screenshot ingest + compare

1. `docs/WHOOP_NOOP_SCREENSHOT_COMPARE.md` — three-lane playbook (WHOOP UI / NOOP UI / band replay)
2. `Tools/ingest_export.ps1 -PackKind stress -ExportDir ...`
3. Fill `manifest.csv` `screen` + `values`, append tip@clock rows to `whoop-app-labels.jsonl`
4. Re-run `stress_sleep_train.py`

## Factor docs + Fable backlog

- `docs/STRESS_FACTORS_AND_LITERATURE.md` — DaytimeStress map, tip@clock tables, STRESS-* tickets
- `docs/SLEEP_FACTORS_AND_LITERATURE.md` — RestScorer / stage gaps, REST-WEIGHT, SLEEP-SELF-1
- `docs/SLEEP_ML_DATASETS.md` — public datasets + κ metrics
- `docs/FABLE5_300_NOT_INTUITIVE.md` / `docs/FABLE_200_UI_IMPROVEMENTS.md` — UX backlog (do not conflate with ML gates)

## Fold offline this session

Gilbert left the Fold **plugged in but powered OFF**. Do **not** wait on USB ADB, tip@clock live capture, bank pull, or live-edit deploy. Re-verify on device after the phone is powered on again.

## Honesty rules

- `accuracy_valid=false` until documented N gates pass — never cite synthetic κ as product accuracy.
- Do not retune `DaytimeStress` from a single tip δ (STRESS-TIP acceptance: ≥4 matched calm/MEDIUM anchors).
- SpO2 on NOOP Health cards is **strap/UI reported** when present; never invent from open BLE.
