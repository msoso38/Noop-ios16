# Sleep-stage ML plan (public datasets + NOOP)

**Date:** 2026-07-11  
**Hard rule:** Stages remain **wellness approximations**, not PSG medical diagnoses. Never invent SpO2/BP from open BLE.

Community context (Discord 2026-07-10):  
- Original NOOP-class algorithm ~**55%** epoch accuracy on a labeled wearable set.  
- Community ML (MADSOLSEN-style / paper architecture) ~**75%** on same minimal example; κ ~0.2–0.25 better claimed.  
- Path A (algorithm): push rules until gains &lt;5%/step, then ML.  
- Path B (ML): train on paper datasets; prefer **κ (Cohen’s kappa)** + 4-class (wake/light/deep/rem).

NOOP today ships **4-class** [SleepStager] V1 (default) and opt-in **SleepStagerV2** (`PuffinExperiment.experimentalSleepV2`). Both take gravity + HR + R-R — same modality family as Walch Apple Watch + PSG.

## Primary public / requestable datasets

| Dataset | Access | Sensors | Labels | Fit for NOOP |
|---------|--------|---------|--------|--------------|
| **sleep-accel** (Walch 2019) [PhysioNet](https://physionet.org/content/sleep-accel/1.0.0/) | **Open** (ODC-By) | Apple Watch accel + PPG HR | PSG 0–5 (wake/N1/N2/N3/REM) | **Best first LOSO bench** — same paper NOOP cites (~65–73% EEG-free ceiling) |
| **DREAMT** [PhysioNet](https://physionet.org/content/dreamt/2.2.0/) | **Restricted** DUA | Empatica E4 BVP/ACC/EDA/TEMP + HR/IBI | AASM W/N1/N2/N3/R + apnea cohort n=100 | Strong wearable multi-channel; request DUA |
| **BIDSLEEP** [PhysioNet](https://physionet.org/content/bidsleep-dataset/1.0.0/) | Check portal | Multi-night IHR + accel (Apple Watch) + Dreem EEG stages | EEG stages | Multi-night free-living-ish |
| **AAUWSS** [Zenodo](https://zenodo.org/records/16919071) | Open/terms | E4 IBI + ECG gold R-R | AASM 30s n=13 | Good for κ vs non-ML + ML |
| **GalaxyPPG** Zenodo | Open | Wrist PPG + Polar H10 ECG | No sleep labels | HRV gold R-R only |
| **PPG-DaLiA** UCI | Open | E4 BVP + chest ECG | Daytime, no sleep | HRV only |
| **MADSOLSEN** minimal_example [GitHub](https://github.com/MADSOLSEN/SleepStagePrediction) | Open | ACC+PPG @32Hz | wake/light/deep/rem 1 subject | Paper mini-set; not full public train |
| **MESA / SHHS** [sleepdata.org](https://sleepdata.org/datasets) | Application | PPG/PSG rich | AASM | Fine-tune / SOTA papers (DAT-sleep etc.) |

Paper often cited for flexible DL (accel+PPG):  
https://backend.orbit.dtu.dk/ws/files/279922590/A_flexible_deep_learning_architecture_for_temporal_sleep_stage_classification_using_accelerometry_and_photoplethysmography_1_.pdf  
(Full training data often **private**; use MADSOLSEN mini + public PhysioNet instead.)

## Label mapping (PSG → NOOP 4-class)

| PSG / Walch | NOOP stage |
|-------------|------------|
| 0 wake | wake |
| 1 N1 | light |
| 2 N2 | light |
| 3 N3 | deep |
| 5 REM | rem |

(DREAMT: W→wake, N1+N2→light, N3→deep, R→rem.)

## Evaluation metrics (report all)

1. **Epoch accuracy** (30 s) — easy to game with majority light.  
2. **Cohen’s κ** — primary for community compare.  
3. **Per-class F1** (wake / light / deep / rem).  
4. Optional 3-class collapse (wake / nrem / rem) for papers that only publish 3-class.

## Pipeline (PC training → optional on-device)

```
sleep-accel / DREAMT / AAUWSS
        │
        ▼  Tools/sleep_stage_eval.py  (download manual; open license)
   epoch features: HR, ΔHR, motion energy, (optional RMSSD from IBI)
        │
        ├── benchmark SleepStager V1/V2 logic ports (algorithm lane)
        └── train sklearn / tiny TFLite model (ML lane) → export coefficients
                │
                ▼  future SleepStagerV3 (opt-in) on Android
   WHOOP: type47 motion proxy + 2A37/R-R overnight bank
```

## WHOOP raw features available for staging

| Feature | Source |
|---------|--------|
| HR series | 2A37 + type47@22 |
| R-R / RMSSD | 2A37 R-R when present |
| Motion / still | gravity + type47 act@63 + step counter@57 |
| Resp proxy | RSA from R-R (V2) |
| Skin temp | banked when present |

## Honest limits

- Wrist-only 4-class **ceiling** is well below PSG; Walch ~65–73% for simpler tasks.  
- Community 75% on **mini** set ≠ production κ on apnea-heavy DREAMT.  
- NOOP must stay **opt-in** for experimental stagers; V1 remains default.  
- Never claim clinical sleep diagnosis.

## Download (you run once)

```bash
# Open: ~550 MB zip
# https://physionet.org/content/sleep-accel/1.0.0/
# Place under: pairing-logs/datasets/sleep-accel/

# Then:
python Tools/sleep_stage_eval.py --sleep-accel-dir pairing-logs/datasets/sleep-accel
```

## References

- Walch et al. SLEEP 2019 (sleep-accel).  
- DREAMT CHIL 2024 / PhysioNet 2.2.0.  
- MADSOLSEN SleepStagePrediction (GitHub).  
- NOOP: `SleepStager.kt`, `SleepStagerV2.kt`, Settings → Experimental sleep V2.  
