# Golden pairing-logs subset

Checked into the stress/sleep ML reproduce PR so another engineer can recreate results
without the full BLE dump or every JPG.

## Included

| File | Purpose |
|------|---------|
| `../whoop-app-labels.jsonl` | Real labels (adb + screenshot decode) |
| `../exports/20260712-Noop_stresd/manifest.csv` | Decoded stress pack |
| `../exports/20260713-Noop_mg/manifest.csv` | Decoded mg stress+sleep pack |
| `../exports/*/REQUIRED_SHOTS.md` | Coverage vs capture checklist |
| `../exports/*/labels_stub.jsonl.example` | JSONL shape for new packs |
| `../stress-sleep-train-status.json` | Last orchestrator run (regenerate anytime) |
| `../ml-stress-tip-weights.json` | Stress tip affine weights |
| `../sleep-stage-eval.json` | Sleep stage bench output |
| `../ml-engine-status.json` | Effort→strain status |

## Excluded (rebuild scripts)

- Full screenshot JPGs → `Tools/ingest_export.ps1`
- `ml-samples.jsonl` / `noop-pairing-log.txt` → Fold collectors when phone is on
- sleep-accel dataset → `docs/SLEEP_ML_DATASETS.md`

See `docs/STRESS_SLEEP_ML_REPRO.md`.
