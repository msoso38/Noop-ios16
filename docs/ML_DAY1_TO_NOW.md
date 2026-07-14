# ML day-1 → now vs WHOOP

Living log of real training status. Numbers come only from `Tools/ml_engine_train.py` /
`Tools/stress_sleep_train.py` / `pairing-logs/*-status.json`.

### Checkpoint 2026-07-14 (stress + sleep reproduce PR)
- Orchestrator: `python Tools/stress_sleep_train.py --as-of 2026-07-14`
- Stress tip pairs (clock-matched): 1 (WHOOP 1.4 ↔ NOOP 1.8 @ 22:14 on 2026-07-13) — `accuracy_valid=false` until ≥4
- Sleep Rest vs WHOOP Sleep%: 1 pair (Rest 73 vs Sleep% 64) — RestScorer ticket REST-WEIGHT, not a fit claim
- Sleep stages: synthetic plumbing only until PhysioNet sleep-accel lands (`docs/SLEEP_ML_DATASETS.md`)
- Effort→Strain: still underfit (need ≥3 distinct strain label days)
- Fold: plugged in but powered OFF — no bank pull this session
- Repro: `docs/STRESS_SLEEP_ML_REPRO.md`

### Checkpoint 2026-07-12 07:26Z
- accuracy_valid: False
- labels: 1 · features: 2 · samples: 2666
- note: Built 2 day(s) of features from BLE samples. Need ≥2 days of WHOOP **app** Strain labels to fit Effort→Strain. Do NOT treat synthetic/pipeline pass as model accuracy.
