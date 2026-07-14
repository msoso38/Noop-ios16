#!/usr/bin/env python3
"""Sleep-stage evaluation harness for NOOP-class 4-class staging.

Primary open bench: PhysioNet sleep-accel (Walch 2019).
  https://physionet.org/content/sleep-accel/1.0.0/

Metrics: epoch accuracy + Cohen's kappa + per-class F1 (wake/light/deep/rem).
Does NOT invent clinical claims. Algorithm lane first; ML lane is optional sklearn.

Usage:
  python Tools/sleep_stage_eval.py --synthetic
  python Tools/sleep_stage_eval.py --sleep-accel-dir pairing-logs/datasets/sleep-accel
"""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "pairing-logs" / "sleep-stage-eval.json"

# Walch labels: 0 wake, 1 N1, 2 N2, 3 N3, 5 REM → NOOP 4-class
STAGES = ("wake", "light", "deep", "rem")


def walch_to_noop(label: int) -> str:
    if label == 0:
        return "wake"
    if label in (1, 2):
        return "light"
    if label == 3:
        return "deep"
    if label == 5:
        return "rem"
    return "light"  # unknown → light (majority sleep)


def cohen_kappa(y_true: list[str], y_pred: list[str], labels: Iterable[str] = STAGES) -> float:
    labels = list(labels)
    n = len(y_true)
    if n == 0:
        return 0.0
    # confusion
    idx = {lab: i for i, lab in enumerate(labels)}
    mat = [[0] * len(labels) for _ in labels]
    for t, p in zip(y_true, y_pred):
        if t not in idx or p not in idx:
            continue
        mat[idx[t]][idx[p]] += 1
    po = sum(mat[i][i] for i in range(len(labels))) / n
    row = [sum(mat[i]) for i in range(len(labels))]
    col = [sum(mat[i][j] for i in range(len(labels))) for j in range(len(labels))]
    pe = sum((row[i] / n) * (col[i] / n) for i in range(len(labels)))
    if abs(1 - pe) < 1e-12:
        return 0.0
    return (po - pe) / (1 - pe)


def accuracy(y_true: list[str], y_pred: list[str]) -> float:
    if not y_true:
        return 0.0
    return sum(a == b for a, b in zip(y_true, y_pred)) / len(y_true)


def f1_per_class(y_true: list[str], y_pred: list[str]) -> dict[str, float]:
    out = {}
    for lab in STAGES:
        tp = sum(1 for t, p in zip(y_true, y_pred) if t == lab and p == lab)
        fp = sum(1 for t, p in zip(y_true, y_pred) if t != lab and p == lab)
        fn = sum(1 for t, p in zip(y_true, y_pred) if t == lab and p != lab)
        prec = tp / (tp + fp) if (tp + fp) else 0.0
        rec = tp / (tp + fn) if (tp + fn) else 0.0
        out[lab] = (2 * prec * rec / (prec + rec)) if (prec + rec) else 0.0
    return out


def baseline_majority(y_true: list[str]) -> list[str]:
    maj = Counter(y_true).most_common(1)[0][0]
    return [maj] * len(y_true)


def heuristic_hr_motion(
    hr: list[float],
    motion: list[float],
    epoch_s: float = 30.0,
) -> list[str]:
    """Tiny transparent heuristic (not V1 port) for smoke-test when full Kotlin isn't available.

    Rules inspired by SleepStager comments: high motion → wake; low HR + low motion → deep;
    high HR variability proxy → rem; else light.
    """
    n = len(hr)
    if n == 0:
        return []
    # z-score HR
    mean_hr = sum(hr) / n
    var = sum((h - mean_hr) ** 2 for h in hr) / max(n, 1)
    sd = math.sqrt(var) or 1.0
    preds = []
    for i, h in enumerate(hr):
        m = motion[i] if i < len(motion) else 0.0
        z = (h - mean_hr) / sd
        # motion high
        if m > 0.05:
            preds.append("wake")
        elif m < 0.01 and z < -0.4:
            preds.append("deep")
        elif z > 0.3 and m < 0.02:
            preds.append("rem")
        else:
            preds.append("light")
    return preds


def load_sleep_accel_subject(base: Path, sid: str) -> tuple[list[str], list[float], list[float]] | None:
    """Load one subject into 30s epochs: labels, mean HR, motion energy."""
    lab_path = base / "labels" / f"{sid}_labeled_sleep.txt"
    hr_path = base / "heart_rate" / f"{sid}_heartrate.txt"
    acc_path = base / "motion" / f"{sid}_acceleration.txt"
    if not lab_path.exists():
        # alternate layout: flat files
        lab_path = base / f"{sid}_labeled_sleep.txt"
        hr_path = base / f"{sid}_heartrate.txt"
        acc_path = base / f"{sid}_acceleration.txt"
    if not lab_path.exists() or not hr_path.exists():
        return None

    labels_raw: list[tuple[float, int]] = []
    for line in lab_path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.strip().split()
        if len(parts) < 2:
            continue
        try:
            labels_raw.append((float(parts[0]), int(float(parts[1]))))
        except ValueError:
            continue
    if not labels_raw:
        return None

    hr_pts: list[tuple[float, float]] = []
    for line in hr_path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.strip().split()
        if len(parts) < 2:
            continue
        try:
            hr_pts.append((float(parts[0]), float(parts[1])))
        except ValueError:
            continue

    acc_pts: list[tuple[float, float]] = []
    if acc_path.exists():
        for line in acc_path.read_text(encoding="utf-8", errors="replace").splitlines():
            parts = line.strip().split()
            if len(parts) < 4:
                continue
            try:
                t, x, y, z = float(parts[0]), float(parts[1]), float(parts[2]), float(parts[3])
                # energy of successive samples approximated later; store magnitude
                acc_pts.append((t, math.sqrt(x * x + y * y + z * z)))
            except ValueError:
                continue

    # labels are already ~30s epochs starting at t
    y_true: list[str] = []
    hr_e: list[float] = []
    mot_e: list[float] = []
    for t0, lab in labels_raw:
        t1 = t0 + 30.0
        hrs = [h for t, h in hr_pts if t0 <= t < t1]
        if not hrs:
            continue
        mags = [m for t, m in acc_pts if t0 <= t < t1]
        # motion energy = std of magnitude as proxy for activity
        if mags:
            mm = sum(mags) / len(mags)
            mv = sum((m - mm) ** 2 for m in mags) / len(mags)
            motion = math.sqrt(mv)
        else:
            motion = 0.0
        y_true.append(walch_to_noop(lab))
        hr_e.append(sum(hrs) / len(hrs))
        mot_e.append(motion)
    if len(y_true) < 10:
        return None
    return y_true, hr_e, mot_e


def discover_sleep_accel_ids(base: Path) -> list[str]:
    labels_dir = base / "labels"
    if labels_dir.is_dir():
        return sorted({p.name.split("_")[0] for p in labels_dir.glob("*_labeled_sleep.txt")})
    return sorted({p.name.split("_")[0] for p in base.glob("*_labeled_sleep.txt")})


def eval_pair(y_true: list[str], y_pred: list[str], name: str) -> dict:
    return {
        "name": name,
        "n_epochs": len(y_true),
        "accuracy": round(accuracy(y_true, y_pred), 4),
        "cohen_kappa": round(cohen_kappa(y_true, y_pred), 4),
        "f1": {k: round(v, 4) for k, v in f1_per_class(y_true, y_pred).items()},
        "label_hist": dict(Counter(y_true)),
        "pred_hist": dict(Counter(y_pred)),
    }


def mini_ml_predict(
    hr: list[float],
    motion: list[float],
    y_true: list[str] | None = None,
) -> list[str]:
    """Tiny feature → class rules with optional train-on-half thresholds.

    Not a real sleep model. Used only to prove the ML-lane plumbing (features → κ).
    When y_true is provided, thresholds are fit on the first half (leaky demo on synthetic).
    """
    n = len(hr)
    if n == 0:
        return []
    mean_hr = sum(hr) / n
    # Default thresholds
    wake_m = 0.04
    deep_z = -0.35
    rem_z = 0.25
    if y_true is not None and n >= 40:
        half = n // 2
        # Fit motion threshold for wake from first half
        wake_mots = [motion[i] for i in range(half) if y_true[i] == "wake"]
        if wake_mots:
            wake_m = max(0.02, sum(wake_mots) / len(wake_mots) * 0.7)
    sd = math.sqrt(sum((h - mean_hr) ** 2 for h in hr) / n) or 1.0
    preds = []
    for i, h in enumerate(hr):
        m = motion[i] if i < len(motion) else 0.0
        z = (h - mean_hr) / sd
        if m > wake_m:
            preds.append("wake")
        elif m < 0.01 and z < deep_z:
            preds.append("deep")
        elif z > rem_z and m < 0.02:
            preds.append("rem")
        else:
            preds.append("light")
    return preds


def synthetic_demo() -> dict:
    """No download required — sanity check metrics plumbing. accuracy_valid=false always."""
    y_true = (
        ["wake"] * 20
        + ["light"] * 80
        + ["deep"] * 40
        + ["light"] * 60
        + ["rem"] * 50
        + ["light"] * 40
        + ["wake"] * 10
    )
    hr = [70.0] * len(y_true)
    for i, s in enumerate(y_true):
        if s == "wake":
            hr[i] = 78
        elif s == "deep":
            hr[i] = 58
        elif s == "rem":
            hr[i] = 68
        else:
            hr[i] = 62
    motion = [0.08 if s == "wake" else 0.005 for s in y_true]
    y_pred = heuristic_hr_motion(hr, motion)
    y_ml = mini_ml_predict(hr, motion, y_true=y_true)
    return {
        "mode": "synthetic",
        "accuracy_valid": False,
        "goal_G4": "NOT_DONE",
        "note": (
            "No public dataset on disk — metrics plumbing only. "
            "Do NOT cite these κ/acc as product accuracy. "
            "Download PhysioNet sleep-accel → re-run for real LOSO κ (G4)."
        ),
        "results": [
            eval_pair(y_true, y_pred, "heuristic_hr_motion"),
            eval_pair(y_true, y_ml, "mini_ml_lane_synthetic"),
            eval_pair(y_true, baseline_majority(y_true), "majority_baseline"),
        ],
    }


def eval_sleep_accel(base: Path, max_subjects: int = 8) -> dict:
    ids = discover_sleep_accel_ids(base)
    if not ids:
        return {
            "mode": "sleep-accel",
            "error": f"No subjects found under {base}. Expected labels/*_labeled_sleep.txt",
        }
    results = []
    all_true: list[str] = []
    all_pred: list[str] = []
    for sid in ids[:max_subjects]:
        loaded = load_sleep_accel_subject(base, sid)
        if not loaded:
            continue
        y_true, hr, mot = loaded
        y_pred = heuristic_hr_motion(hr, mot)
        results.append(eval_pair(y_true, y_pred, f"subject_{sid}"))
        all_true.extend(y_true)
        all_pred.extend(y_pred)
    if not all_true:
        return {
            "mode": "sleep-accel",
            "accuracy_valid": False,
            "goal_G4": "NOT_DONE",
            "error": "Subjects found but none loadable",
        }
    # Mini-ML lane on real data: train thresholds on subject 0, eval rest (crude LOSO-ish)
    ml_true: list[str] = []
    ml_pred: list[str] = []
    loaded_all = []
    for sid in ids[:max_subjects]:
        loaded = load_sleep_accel_subject(base, sid)
        if loaded:
            loaded_all.append((sid, loaded))
    if loaded_all:
        _, (train_y, train_hr, train_mot) = loaded_all[0]
        # Use train subject only to fit (via y_true half-fit inside mini_ml)
        for sid, (y_true, hr, mot) in loaded_all[1:] or loaded_all[:1]:
            pred = mini_ml_predict(hr, mot, y_true=train_y)
            # length mismatch if different nights — re-predict with local stats only
            if len(pred) != len(y_true):
                pred = mini_ml_predict(hr, mot, y_true=None)
            ml_true.extend(y_true)
            ml_pred.extend(pred[: len(y_true)])
    pooled_ml = eval_pair(ml_true, ml_pred, "pooled_mini_ml") if ml_true else None
    kappa = (eval_pair(all_true, all_pred, "pooled_heuristic")).get("cohen_kappa", 0)
    return {
        "mode": "sleep-accel",
        "accuracy_valid": True,
        "goal_G4": "PARTIAL" if kappa is not None else "NOT_DONE",
        "path": str(base),
        "n_subjects_used": len(results),
        "subjects_available": len(ids),
        "pooled": eval_pair(all_true, all_pred, "pooled_heuristic"),
        "pooled_mini_ml": pooled_ml,
        "pooled_majority": eval_pair(all_true, baseline_majority(all_true), "pooled_majority"),
        "per_subject": results,
        "note": (
            "Real labels from sleep-accel — report cohen_kappa as primary. "
            "Heuristic/mini_ml are smoke benches, NOT SleepStager V1/V2 port. "
            "Community ML ~0.75 acc on mini-set; Walch ceiling ~0.65–0.73 wearable."
        ),
        "datasets_doc": "docs/SLEEP_ML_DATASETS.md",
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--synthetic", action="store_true", help="Run without datasets")
    ap.add_argument(
        "--sleep-accel-dir",
        type=str,
        default="",
        help="Path to unpacked sleep-accel 1.0.0 root",
    )
    ap.add_argument("--max-subjects", type=int, default=8)
    args = ap.parse_args()

    if args.sleep_accel_dir:
        payload = eval_sleep_accel(Path(args.sleep_accel_dir), max_subjects=args.max_subjects)
    else:
        default = ROOT / "pairing-logs" / "datasets" / "sleep-accel"
        if default.is_dir() and discover_sleep_accel_ids(default):
            payload = eval_sleep_accel(default, max_subjects=args.max_subjects)
        else:
            payload = synthetic_demo()

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps(payload, indent=2)[:4000])
    print(f"\nWrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
