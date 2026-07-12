# ML day-1 → now vs WHOOP

Living log of **real** training status. Numbers come only from
`Tools/ml_engine_train.py`, `Tools/calibrate_whoop_noop.py`, and
`pairing-logs/*.json`. Never invent pass % or accuracy.

See also: `docs/CALIBRATION.md` (citations + affine method).

## Day 1 baseline (documented 2026-07-12)

- Feature store from BLE `ML_SAMPLE` ingest
- WHOOP **app** labels required for `accuracy_valid`
- Goal G5 (fit Effort→Strain) blocked until ≥3 distinct labeled days (≥2 for first fit attempt per train script message)

## How to refresh (CPU-only — do not start GPU jobs)

```bash
python Tools/ml_engine_train.py
python Tools/calibrate_whoop_noop.py
python Tools/whoop_noop_12h_report.py          # one shot
python Tools/whoop_noop_12h_report.py --loop   # every 12h (PC Task Scheduler preferred)
```

Reports land in `pairing-logs/reports/whoop-noop-*.md`.
Calibration table: `pairing-logs/calibration-report.json`.

### Checkpoint 2026-07-12 07:26Z
- accuracy_valid: False
- labels: 1 · features: 2 · samples: 2666
- note: Built 2 day(s) of features from BLE samples. Need ≥2 days of WHOOP **app** Strain labels to fit Effort→Strain. Do NOT treat synthetic/pipeline pass as model accuracy.

### Checkpoint 2026-07-12 08:22Z (calibrate path restored)
- accuracy_valid: **False** (deploy gate FAIL)
- features: 2 days · pairs Effort: **N=1** · Charge/Sleep/Stress: **N=0**
- Effort MAE before affine: **2.88** (shared 0–100; WHOOP 14.7/21 → ~70; heuristic effort_proxy — not fitted)
- Affine fitted: no (need ≥3 per head for Charge/Effort/Sleep)
- GPU: none (CPU-only); desk GPU hog kill owned by sibling — do not start Gemma/CUDA from this pipeline
- Citations: Plews 2013, Banister TRIMP, Cole–Kripke, Baevsky; OpenStrap analytics; whoop-insights Ridge pattern — `docs/CALIBRATION.md`
