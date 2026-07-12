#!/usr/bin/env python3
"""Build daily ML feature store from BLE samples + refresh status vs WHOOP labels.

CPU-only. Writes pairing-logs/ml-daily-features.json + ml-engine-status.json.
Does not invent accuracy — accuracy_valid comes from calibrate_whoop_noop.py.
"""
from __future__ import annotations

import json
import math
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAIRING = ROOT.parent / "pairing-logs"
if not PAIRING.is_dir():
    PAIRING = ROOT / "pairing-logs"

SAMPLES = PAIRING / "ml-samples.jsonl"
FEATURES_OUT = PAIRING / "ml-daily-features.json"
STATUS_OUT = PAIRING / "ml-engine-status.json"
SPORT_OUT = PAIRING / "ml-sport-session-features.json"
LABELS = PAIRING / "whoop-app-labels.jsonl"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets" / "whoop_app_labels.jsonl"


def _rmssd(rr_ms: list[float]) -> float | None:
    if len(rr_ms) < 8:
        return None
    diffs = [rr_ms[i + 1] - rr_ms[i] for i in range(len(rr_ms) - 1)]
    # Malik ectopic gate: drop successive diffs >20% of prior RR
    clean = []
    for i, d in enumerate(diffs):
        prev = rr_ms[i]
        if prev <= 0:
            continue
        if abs(d) / prev > 0.2:
            continue
        clean.append(d)
    if len(clean) < 4:
        return None
    return math.sqrt(sum(d * d for d in clean) / len(clean))


def _effort_proxy(mean_hr: float, frac100: float, max_hr: float) -> float:
    # Heuristic 0–100 — NOT WHOOP accuracy. Used only as feature until StrainScorer export lands.
    z = 0.45 * max(0.0, (mean_hr - 60) / 60) + 0.35 * frac100 + 0.2 * max(0.0, (max_hr - 90) / 90)
    return max(0.0, min(100.0, z * 100.0))


def load_samples(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        t = line.strip()
        if not t:
            continue
        try:
            out.append(json.loads(t))
        except json.JSONDecodeError:
            continue
    return out


def day_key(ts) -> str | None:
    try:
        if isinstance(ts, (int, float)):
            # seconds or ms
            if ts > 1e12:
                ts = ts / 1000.0
            return datetime.fromtimestamp(ts, tz=timezone.utc).astimezone().date().isoformat()
        if isinstance(ts, str) and len(ts) >= 10:
            return ts[:10]
    except (OSError, ValueError, OverflowError):
        return None
    return None


def day_key_from_sample(s: dict) -> str | None:
    if s.get("day"):
        return str(s["day"])[:10]
    recv = s.get("recv_at")
    if isinstance(recv, str) and len(recv) >= 10:
        return recv[:10]
    return day_key(s.get("ts_ms") or s.get("ts") or s.get("t") or s.get("timestamp"))


def build_features(samples: list[dict]) -> dict:
    by: dict[str, list] = defaultdict(list)
    for s in samples:
        d = day_key_from_sample(s)
        if not d:
            continue
        by[d].append(s)
    today = date.today().isoformat()
    days = {}
    total = 0
    for d, rows in sorted(by.items()):
        if d >= today:
            continue
        hrs = [float(r["hr"]) for r in rows if r.get("hr") not in (None, 0, "")]
        rrs = []
        for r in rows:
            if isinstance(r.get("rr"), list):
                for v in r["rr"]:
                    try:
                        fv = float(v)
                        rrs.append(fv * 1000 if fv < 10 else fv)
                    except (TypeError, ValueError):
                        pass
            elif r.get("rr_ms") is not None:
                rrs.append(float(r["rr_ms"]))
            elif r.get("rr") is not None:
                v = float(r["rr"])
                rrs.append(v * 1000 if v < 10 else v)
        steps = [float(r["steps"]) for r in rows if r.get("steps") is not None]
        if not hrs:
            continue
        mean_hr = sum(hrs) / len(hrs)
        max_hr = max(hrs)
        min_hr = min(hrs)
        frac100 = sum(1 for h in hrs if h >= 100) / len(hrs)
        frac130 = sum(1 for h in hrs if h >= 130) / len(hrs)
        days[d] = {
            "day": d,
            "n_samples": len(rows),
            "mean_hr": round(mean_hr, 2),
            "max_hr": max_hr,
            "min_hr": min_hr,
            "frac_hr_ge_100": round(frac100, 4),
            "frac_hr_ge_130": round(frac130, 4),
            "rr_rmssd_ms": None if _rmssd(rrs) is None else round(_rmssd(rrs), 2),
            "steps_max": int(max(steps)) if steps else None,
            "effort_proxy_0_100": round(_effort_proxy(mean_hr, frac100, max_hr), 2),
            "note": "effort_proxy is heuristic — not accuracy vs WHOOP app",
        }
        total += len(rows)
    return {
        "days": days,
        "n_days": len(days),
        "completed_before": today,
        "n_ml_samples_ingested": total,
        "note": "Current and future days are excluded until their local day completes.",
    }


def count_labels() -> tuple[int, int]:
    n = 0
    days = set()
    for path in (LABELS, ASSETS):
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            t = line.strip()
            if not t or t.startswith("#"):
                continue
            try:
                o = json.loads(t)
            except json.JSONDecodeError:
                continue
            day = o.get("day")
            if not day:
                continue
            if any(o.get(k) is not None for k in ("strain_021", "dayStrain021", "recovery_pct", "recoveryPct", "sleep_pct")):
                n += 1
                days.add(day)
    return n, len(days)


def main() -> int:
    samples = load_samples(SAMPLES)
    features = build_features(samples)
    FEATURES_OUT.parent.mkdir(parents=True, exist_ok=True)
    FEATURES_OUT.write_text(json.dumps(features, indent=2), encoding="utf-8")
    if not SPORT_OUT.is_file():
        SPORT_OUT.write_text(
            json.dumps({"sessions": [], "note": "Sport sessions filled by on-device WorkoutLabelStore emit"}, indent=2),
            encoding="utf-8",
        )

    n_labels, n_label_days = count_labels()
    n_feat = features["n_days"]
    # Pair days that have both features and a strain label
    label_days = set()
    for path in (LABELS, ASSETS):
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            t = line.strip()
            if not t:
                continue
            try:
                o = json.loads(t)
            except json.JSONDecodeError:
                continue
            if o.get("day") and (o.get("strain_021") is not None or o.get("dayStrain021") is not None):
                label_days.add(o["day"])
    n_pairs = len(set(features["days"]) & label_days)
    accuracy_valid = False  # only calibrate_whoop_noop.py may flip this after MAE gate
    msg = (
        f"Built {n_feat} day(s) of features from BLE samples. "
        f"Need ≥2 days of WHOOP **app** Strain labels to fit Effort→Strain. "
        "Do NOT treat synthetic/pipeline pass as model accuracy."
    )
    status = {
        "accuracy_valid": accuracy_valid,
        "n_label_rows": n_labels,
        "n_completed_label_rows": n_label_days,
        "n_excluded_current_or_future_labels": 0,
        "n_pairs": n_pairs,
        "n_feature_days": n_feat,
        "n_ml_samples_ingested": features.get("n_ml_samples_ingested", len(samples)),
        "completed_before": features["completed_before"],
        "future_day_guard": "Current local day and future dates are excluded until their day has completed.",
        "feature_store": str(FEATURES_OUT),
        "sport_session_feature_store": str(SPORT_OUT),
        "n_sport_labels": 0,
        "n_sport_sessions_eligible": 0,
        "goals": {
            "G1_auto_labels": "PARTIAL" if n_labels else "NOT_DONE",
            "G2_compare_app": "PARTIAL" if n_pairs else "NOT_DONE",
            "G4_sleep_kappa": "NOT_DONE",
            "G5_ml_train": "NOT_DONE" if n_pairs < 3 else "READY_TO_CALIBRATE",
            "feature_days_ready": n_feat,
            "label_rows": n_labels,
            "paired_days": n_pairs,
            "accuracy_valid": False,
            "message": "Underfit — need more distinct labeled days." if n_pairs < 3 else "Run Tools/calibrate_whoop_noop.py",
        },
        "next_actions": [
            "Enable Accessibility NOOP WHOOP app capture; open WHOOP daily",
            "Or run Tools/whoop_app_adb_capture.ps1 with wireless adb",
            "Append JSONL {day, strain_021, recovery_pct, sleep_pct, noop_*} to pairing-logs/whoop-app-labels.jsonl",
            "Re-run Tools/calibrate_whoop_noop.py after ≥3 distinct labeled days",
            "Export NOOP daily metrics to pairing-logs/noop-daily-metrics.jsonl for Charge/Sleep/Stress pairs",
        ],
        "honest_rule": "Never treat pipeline HR collect or synthetic eval as model accuracy vs WHOOP app.",
        "status": "FEATURE_STORE_READY" if n_feat else "NO_SAMPLES",
        "message": msg,
        "gpu": "none (CPU-only)",
        "calibration_doc": "docs/CALIBRATION.md",
    }
    STATUS_OUT.write_text(json.dumps(status, indent=2), encoding="utf-8")
    # Mirror into assets for on-device honesty strip
    assets_status = ROOT / "android" / "app" / "src" / "main" / "assets" / "ml_engine_status.json"
    try:
        assets_status.write_text(json.dumps(status, indent=2), encoding="utf-8")
    except OSError:
        pass
    print(json.dumps({"status": status["status"], "n_feature_days": n_feat, "n_pairs": n_pairs, "accuracy_valid": False}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
