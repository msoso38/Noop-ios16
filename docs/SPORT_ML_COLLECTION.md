# Sport ML Collection

## Status

**Collecting only.** NOOP does not currently identify a sport automatically and does not report sport-classification accuracy.

The existing detector finds a sustained likely workout from heart rate and motion. It deliberately saves that as a neutral detected activity until the user chooses a sport. This avoids relabeling a walk, gym session, or false positive as a specific activity.

## What the debug build now collects

After a user saves a live/manual workout or relabels a detected bout, a debug build emits a local-PC-only record:

```text
ML_WORKOUT_LABEL v=1 label_ts_ms=... start_ts_ms=... end_ts_ms=... sport_key=running provenance=user_live
```

- Only catalogue sports are accepted.
- Free-text sport names, notes, routes, device identifiers, and health claims are excluded.
- A later label for the same time window supersedes an earlier label.
- Release builds do nothing.
- Labels are emitted only after the workout write succeeds.

`Tools/ml_engine_train.py` writes `pairing-logs/ml-sport-session-features.json`. It retains sparse or current-day windows for audit, but marks them ineligible until the session is complete and has enough overlapping samples. It does not train a model.

## Why this is the first step

WHOOP does not publish its activity-classification model. Public wearable activity-recognition systems generally combine time-window features from motion sensors with heart rate, pace/GPS when available, temporal context, and many user-confirmed labels. A single heart-rate trace cannot reliably distinguish many sports, especially strength training, court sports, and walking with an elevated heart rate.

NOOP currently has open-BLE heart rate, R-R intervals, an activity class on some 5/MG frames, a step counter on some 5/MG frames, optional GPS routes, and user-confirmed workout windows. The dataset must be built from those signals before choosing a model.

## Collection tips

1. Use a debug build connected to the private local collector, not a release build.
2. Start a workout with the right catalogue sport, or relabel a detected activity immediately after it ends.
3. Keep the strap connected for at least five minutes of the session so the label overlaps real samples.
4. Correct mistakes. The newest label for an identical time window wins.
5. Collect varied sessions, people, durations, and intensities. Do not use fabricated or copied labels.
6. Keep imported workout labels separate until an explicit weak-label policy and signal-overlap check exist.

## Before a classifier ships

1. Split train/validation by person and by session date, never by individual sensor row.
2. Publish per-sport support counts, confusion matrix, confidence calibration, and an abstain/"Activity" path.
3. Evaluate sparse-signal and no-GPS cases separately.
4. Require enough independently labeled sessions before showing a sport suggestion.
5. Never turn a low-confidence prediction into a persisted sport without confirmation.
