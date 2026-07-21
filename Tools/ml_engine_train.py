#!/usr/bin/env python3
"""NOOP ML engine — train/eval Effort toward WHOOP **app** Strain labels.

Accuracy is ONLY reported when real labels exist (whoop-app dumps / manual JSON / export).
Synthetic or empty labels → status accuracy_valid=false (never claim %).

Pipeline always builds a daily feature store from ML_SAMPLE / log so the moment labels
arrive, fitting is ready (G5). Loops should read pairing-logs/ml-engine-status.json and
update Goals (G4/G5), not invent pass scores from pipeline-only HR.

Usage:
  python Tools/ml_engine_train.py
  python Tools/ml_engine_train.py --labels pairing-logs/whoop-app-labels.jsonl
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "pairing-logs" / "ml-engine-status.json"
WEIGHTS = ROOT / "pairing-logs" / "ml-effort-weights.json"
FEATURES_OUT = ROOT / "pairing-logs" / "ml-daily-features.json"
SPORT_FEATURES_OUT = ROOT / "pairing-logs" / "ml-sport-session-features.json"
GOALS_OUT = ROOT / "pairing-logs" / "goals-from-ml.json"
LOG = ROOT / "pairing-logs" / "noop-pairing-log.txt"
SAMPLES = ROOT / "pairing-logs" / "ml-samples.jsonl"
DUMPS = ROOT / "pairing-logs" / "whoop-app-dumps"
LABELS_JSONL = ROOT / "pairing-logs" / "whoop-app-labels.jsonl"


def _read_json_text(path: Path) -> str:
    """Read text accepting UTF-8 BOM (PowerShell Set-Content -Encoding utf8)."""
    return path.read_text(encoding="utf-8-sig", errors="replace")


def load_labels(path: Path) -> list[dict]:
    rows: list[dict] = []
    if path.exists():
        for line in _read_json_text(path).splitlines():
            line = line.strip().lstrip("\ufeff")
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    if DUMPS.is_dir():
        for p in sorted(DUMPS.glob("scores-*.json")):
            try:
                d = json.loads(_read_json_text(p))
                if d.get("day_strain_021") is not None or d.get("recovery_pct") is not None:
                    rows.append(
                        {
                            "day": d.get("day") or p.stem,
                            "strain_021": d.get("day_strain_021"),
                            "recovery_pct": d.get("recovery_pct"),
                            "source": d.get("source") or "adb_dump",
                        }
                    )
            except Exception:
                pass
    # Dedupe by day: keep richest row (prefer recovery present, then later in list)
    by_day: dict[str, dict] = {}
    for r in rows:
        day = str(r.get("day") or r.get("day_raw") or "").strip()
        if not day or day.startswith("scores-"):
            # keep undated rows with synthetic keys so they don't collapse
            day = f"_undated_{len(by_day)}_{r.get('source')}"
        prev = by_day.get(day)
        if prev is None:
            by_day[day] = r
            continue
        prev_score = (1 if prev.get("recovery_pct") is not None else 0) + (
            1 if prev.get("strain_021") is not None else 0
        )
        new_score = (1 if r.get("recovery_pct") is not None else 0) + (
            1 if r.get("strain_021") is not None else 0
        )
        if new_score >= prev_score:
            by_day[day] = r
    return list(by_day.values())


def _day_key_from_recv(recv_at: str | None, ts_ms: int | None) -> str | None:
    if recv_at:
        # 2026-07-10T18:41:15+00:00
        try:
            return recv_at[:10]
        except Exception:
            pass
    if ts_ms and ts_ms > 1_000_000_000_000:
        try:
            return datetime.fromtimestamp(ts_ms / 1000.0, tz=timezone.utc).strftime("%Y-%m-%d")
        except Exception:
            pass
    return None


def load_ml_samples() -> list[dict]:
    rows = []
    if SAMPLES.exists():
        for line in SAMPLES.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    # Also scrape last ~1.5MB of pairing log for ML_SAMPLE lines (richer R-R sometimes)
    if LOG.exists():
        data = LOG.read_bytes()[-1_500_000:].decode("utf-8", "replace")
        for m in re.finditer(
            r"ML_SAMPLE[^\n]*?(?:ts_ms=(\d+))?[^\n]*?hr=(\d{2,3})(?:[^\n]*?rr=\[([^\]]*)\])?",
            data,
        ):
            ts_s, hr_s, rr_s = m.group(1), m.group(2), m.group(3)
            # Try to get ISO prefix on same line
            line_start = data.rfind("\n", 0, m.start()) + 1
            line = data[line_start : m.end()]
            day = None
            iso = re.match(r"(\d{4}-\d{2}-\d{2})", line)
            if iso:
                day = iso.group(1)
            rr = []
            if rr_s:
                rr = [int(x) for x in re.findall(r"\d+", rr_s)]
            rows.append(
                {
                    "kind": "ML_SAMPLE",
                    "ts_ms": int(ts_s) if ts_s else None,
                    "hr": int(hr_s),
                    "rr": rr,
                    "day": day,
                    "source": "log_scrape",
                }
            )
    return rows


def load_sport_labels(samples: list[dict]) -> list[dict]:
    """Load debug-only, user-confirmed workout labels without mixing them into daily WHOOP labels."""
    rows = [s for s in samples if s.get("kind") == "ML_WORKOUT_LABEL"]
    if LOG.exists():
        data = LOG.read_bytes()[-1_500_000:].decode("utf-8", "replace")
        pattern = re.compile(
            r"ML_WORKOUT_LABEL\s+v=(?P<v>\d+)\s+label_ts_ms=(?P<label>\d+)\s+"
            r"start_ts_ms=(?P<start>\d+)\s+end_ts_ms=(?P<end>\d+)\s+"
            r"sport_key=(?P<sport>[a-z]+)\s+provenance=(?P<provenance>[a-z_]+)"
        )
        for match in pattern.finditer(data):
            rows.append({
                "kind": "ML_WORKOUT_LABEL",
                "label_ts_ms": int(match["label"]),
                "start_ts_ms": int(match["start"]),
                "end_ts_ms": int(match["end"]),
                "sport_key": match["sport"],
                "provenance": match["provenance"],
            })
    # A corrected label for the same window supersedes the older one. Keep ineligible rows for audit.
    latest: dict[tuple[int, int], dict] = {}
    for row in rows:
        try:
            key = (int(row["start_ts_ms"]), int(row["end_ts_ms"]))
        except (KeyError, TypeError, ValueError):
            continue
        if key[1] <= key[0] or not row.get("sport_key"):
            continue
        if int(row.get("label_ts_ms") or 0) >= int(latest.get(key, {}).get("label_ts_ms") or 0):
            latest[key] = row
    return list(latest.values())


def build_sport_session_features(samples: list[dict], labels: list[dict], as_of: date) -> list[dict]:
    """Make an auditable session manifest. It collects features only; it does not train or claim detection."""
    output: list[dict] = []
    for label in sorted(labels, key=lambda r: (r.get("start_ts_ms", 0), r.get("label_ts_ms", 0))):
        try:
            start, end = int(label["start_ts_ms"]), int(label["end_ts_ms"])
        except (KeyError, TypeError, ValueError):
            continue
        end_day = datetime.fromtimestamp(end / 1000.0).astimezone().date()
        window = [s for s in samples if isinstance(s.get("ts_ms"), int) and start <= s["ts_ms"] <= end]
        hrs = [int(s["hr"]) for s in window if str(s.get("hr", "")).isdigit() and 30 <= int(s["hr"]) <= 220]
        steps = [int(s["steps"]) for s in window if str(s.get("steps", "")).isdigit()]
        coverage_s = ((max(s["ts_ms"] for s in window) - min(s["ts_ms"] for s in window)) / 1000.0) if len(window) >= 2 else 0.0
        eligible = end_day < as_of and len(hrs) >= 20 and coverage_s >= min(300.0, (end - start) / 1000.0 * 0.5)
        output.append({
            "version": 1,
            "sport_key": label.get("sport_key"),
            "provenance": label.get("provenance"),
            "label_ts_ms": label.get("label_ts_ms"),
            "start_ts_ms": start,
            "end_ts_ms": end,
            "duration_s": round((end - start) / 1000.0, 1),
            "n_samples": len(window),
            "n_hr_samples": len(hrs),
            "coverage_s": round(coverage_s, 1),
            "mean_hr": round(statistics.fmean(hrs), 2) if hrs else None,
            "max_hr": max(hrs) if hrs else None,
            "min_hr": min(hrs) if hrs else None,
            "hr_stdev": round(statistics.pstdev(hrs), 2) if len(hrs) >= 2 else None,
            "steps_delta": max(steps) - min(steps) if len(steps) >= 2 else None,
            "eligible_for_future_training": eligible,
            "eligibility_note": "user-confirmed label with sufficient completed-session coverage" if eligible else "retained for audit; needs a completed session and denser overlapping samples",
        })
    return output


def rmssd_ms(rr: list[float]) -> float | None:
    if len(rr) < 3:
        return None
    diffs = [rr[i + 1] - rr[i] for i in range(len(rr) - 1)]
    if not diffs:
        return None
    return math.sqrt(sum(d * d for d in diffs) / len(diffs))


def completed_day(day: object, as_of: date) -> bool:
    """Only completed local calendar days may become ML features or labels."""
    try:
        return date.fromisoformat(str(day)) < as_of
    except (TypeError, ValueError):
        return False


def build_daily_features(samples: list[dict], as_of: date) -> dict[str, dict]:
    """Aggregate HR / R-R / steps into per-UTC-day features for Effort calibration."""
    by_day: dict[str, dict] = defaultdict(lambda: {"hrs": [], "rrs": [], "steps": [], "n": 0})
    for s in samples:
        day = s.get("day") or _day_key_from_recv(s.get("recv_at"), s.get("ts_ms"))
        if not day or day.startswith("1970") or not completed_day(day, as_of):
            continue
        hr = s.get("hr")
        if hr is not None:
            try:
                h = int(hr)
                if 40 <= h <= 220:
                    by_day[day]["hrs"].append(h)
            except (TypeError, ValueError):
                pass
        rr = s.get("rr") or []
        if isinstance(rr, str):
            rr = [int(x) for x in re.findall(r"\d+", rr)]
        for r in rr:
            if 300 <= r <= 2000:
                by_day[day]["rrs"].append(float(r))
        steps = s.get("steps")
        if steps is not None:
            try:
                by_day[day]["steps"].append(int(steps))
            except (TypeError, ValueError):
                pass
        by_day[day]["n"] += 1

    out: dict[str, dict] = {}
    for day, bucket in sorted(by_day.items()):
        hrs = bucket["hrs"]
        if len(hrs) < 10:
            continue
        mean_hr = statistics.fmean(hrs)
        max_hr = max(hrs)
        min_hr = min(hrs)
        # Crude TRIMP-like: minutes above 100 / 120 as zone proxies (sample density unknown)
        z1 = sum(1 for h in hrs if h >= 100) / len(hrs)
        z2 = sum(1 for h in hrs if h >= 130) / len(hrs)
        # Effort proxy 0–100 (heuristic, NOT WHOOP Strain)
        effort_proxy = max(0.0, min(100.0, (mean_hr - 55) * 1.8 + z1 * 25 + z2 * 35 + (max_hr - mean_hr) * 0.15))
        rr_rmssd = rmssd_ms(bucket["rrs"][-500:]) if bucket["rrs"] else None
        steps_max = max(bucket["steps"]) if bucket["steps"] else None
        out[day] = {
            "day": day,
            "n_samples": len(hrs),
            "mean_hr": round(mean_hr, 2),
            "max_hr": max_hr,
            "min_hr": min_hr,
            "frac_hr_ge_100": round(z1, 4),
            "frac_hr_ge_130": round(z2, 4),
            "rr_rmssd_ms": round(rr_rmssd, 2) if rr_rmssd is not None else None,
            "steps_max": steps_max,
            "effort_proxy_0_100": round(effort_proxy, 2),
            "note": "effort_proxy is heuristic — not accuracy vs WHOOP app",
        }
    return out


def fit_linear(xs: list[float], ys: list[float]) -> tuple[float, float, float]:
    n = len(xs)
    if n < 2:
        return 1.0, 0.0, float("nan")
    mx = sum(xs) / n
    my = sum(ys) / n
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    den = sum((x - mx) ** 2 for x in xs) or 1e-9
    a = num / den
    b = my - a * mx
    rmse = math.sqrt(sum((a * x + b - y) ** 2 for x, y in zip(xs, ys)) / n)
    return a, b, rmse


def fit_multifeature(
    X: list[list[float]], y: list[float]
) -> tuple[list[float], float, float]:
    """Ridge y ≈ w·x + b with tiny L2; pure Python for portability."""
    n = len(X)
    if n < 2 or not X:
        return [1.0], 0.0, float("nan")
    d = len(X[0])
    # Augment with bias column
    A = [row[:] + [1.0] for row in X]
    # (A^T A + λI) w = A^T y
    dim = d + 1
    lam = 1e-2
    ata = [[0.0] * dim for _ in range(dim)]
    aty = [0.0] * dim
    for i in range(n):
        for r in range(dim):
            aty[r] += A[i][r] * y[i]
            for c in range(dim):
                ata[r][c] += A[i][r] * A[i][c]
    for i in range(dim):
        ata[i][i] += lam
    # Gaussian elimination
    M = [ata[r][:] + [aty[r]] for r in range(dim)]
    for col in range(dim):
        piv = max(range(col, dim), key=lambda r: abs(M[r][col]))
        M[col], M[piv] = M[piv], M[col]
        if abs(M[col][col]) < 1e-12:
            continue
        div = M[col][col]
        for c in range(col, dim + 1):
            M[col][c] /= div
        for r in range(dim):
            if r == col:
                continue
            f = M[r][col]
            for c in range(col, dim + 1):
                M[r][c] -= f * M[col][c]
    w = [M[r][dim] for r in range(dim)]
    weights, bias = w[:-1], w[-1]
    preds = [sum(wi * xi for wi, xi in zip(weights, X[i])) + bias for i in range(n)]
    rmse = math.sqrt(sum((preds[i] - y[i]) ** 2 for i in range(n)) / n)
    return weights, bias, rmse


def goals_status(
    *,
    n_pairs: int,
    accuracy_valid: bool,
    n_feature_days: int,
    n_labels: int,
) -> dict:
    """Mirror durable Goals board for PC loops (G1–G5 relevant to ML)."""
    g5 = "DONE" if accuracy_valid and n_pairs >= 7 else ("PARTIAL" if n_pairs >= 2 else "NOT_DONE")
    g4 = "NOT_DONE"  # real κ needs sleep-accel; engine does not flip this alone
    return {
        "G1_auto_labels": "PARTIAL" if n_labels == 0 else "PARTIAL",
        "G2_compare_app": "PARTIAL",
        "G4_sleep_kappa": g4,
        "G5_ml_train": g5,
        "feature_days_ready": n_feature_days,
        "label_rows": n_labels,
        "paired_days": n_pairs,
        "accuracy_valid": accuracy_valid,
        "message": (
            "Feature store ready; waiting for WHOOP **app** Strain labels to train."
            if n_pairs == 0
            else (
                "Trained with valid multi-day labels."
                if accuracy_valid
                else "Underfit — need more distinct labeled days."
            )
        ),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--labels", type=str, default=str(LABELS_JSONL))
    ap.add_argument(
        "--as-of",
        type=str,
        default=datetime.now().astimezone().date().isoformat(),
        help="Local date whose in-progress and future days are excluded (YYYY-MM-DD).",
    )
    args = ap.parse_args()
    try:
        as_of = date.fromisoformat(args.as_of)
    except ValueError:
        ap.error("--as-of must use YYYY-MM-DD")

    samples = load_ml_samples()
    sport_labels = load_sport_labels(samples)
    sport_sessions = build_sport_session_features(samples, sport_labels, as_of)
    daily = build_daily_features(samples, as_of)
    FEATURES_OUT.parent.mkdir(parents=True, exist_ok=True)
    FEATURES_OUT.write_text(
        json.dumps(
            {
                "days": daily,
                "n_days": len(daily),
                "completed_before": as_of.isoformat(),
                "note": "Current and future days are excluded until their local day completes.",
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    SPORT_FEATURES_OUT.write_text(
        json.dumps({
            "sessions": sport_sessions,
            "n_labels": len(sport_labels),
            "n_eligible": sum(1 for session in sport_sessions if session["eligible_for_future_training"]),
            "completed_before": as_of.isoformat(),
            "status": "COLLECTING_ONLY",
            "note": "User-confirmed sport labels joined to open-BLE samples. No sport classifier or accuracy claim is produced here.",
        }, indent=2),
        encoding="utf-8",
    )

    labels = load_labels(Path(args.labels))
    eligible_labels = [lab for lab in labels if completed_day(lab.get("day"), as_of)]
    xs: list[float] = []
    ys: list[float] = []
    X_multi: list[list[float]] = []
    pairs: list[dict] = []

    for lab in eligible_labels:
        s = lab.get("strain_021") or lab.get("dayStrain021")
        if s is None:
            continue
        try:
            s = float(s)
        except (TypeError, ValueError):
            continue
        target = max(0.0, min(100.0, s / 21.0 * 100.0))
        day = lab.get("day")
        feat = daily.get(str(day)) if day else None
        if lab.get("noop_effort") is not None:
            try:
                x = float(lab["noop_effort"])
            except (TypeError, ValueError):
                x = None
        elif feat:
            x = float(feat["effort_proxy_0_100"])
        else:
            x = None
        if x is None or x <= 0:
            continue
        xs.append(x)
        ys.append(target)
        if feat:
            X_multi.append(
                [
                    feat["mean_hr"] / 100.0,
                    feat["frac_hr_ge_100"],
                    feat["frac_hr_ge_130"],
                    (feat["rr_rmssd_ms"] or 40.0) / 100.0,
                    x / 100.0,
                ]
            )
        else:
            X_multi.append([x / 100.0, 0.0, 0.0, 0.4, x / 100.0])
        pairs.append(
            {
                "day": day,
                "noop_effort": x,
                "app_strain_021": s,
                "app_pct": target,
                "features": feat,
            }
        )

    distinct_days = {p.get("day") for p in pairs if p.get("day")}
    accuracy_valid = len(pairs) >= 3 and len(distinct_days) >= 3

    goals = goals_status(
        n_pairs=len(pairs),
        accuracy_valid=accuracy_valid,
        n_feature_days=len(daily),
        n_labels=len(labels),
    )
    GOALS_OUT.write_text(json.dumps(goals, indent=2), encoding="utf-8")

    base_status = {
        "accuracy_valid": False,
        "n_label_rows": len(labels),
        "n_completed_label_rows": len(eligible_labels),
        "n_excluded_current_or_future_labels": len(labels) - len(eligible_labels),
        "n_pairs": len(pairs),
        "n_feature_days": len(daily),
        "n_ml_samples_ingested": len(samples),
        "completed_before": as_of.isoformat(),
        "future_day_guard": "Current local day and future dates are excluded until their day has completed.",
        "feature_store": str(FEATURES_OUT),
        "sport_session_feature_store": str(SPORT_FEATURES_OUT),
        "n_sport_labels": len(sport_labels),
        "n_sport_sessions_eligible": sum(1 for session in sport_sessions if session["eligible_for_future_training"]),
        "goals": goals,
        "next_actions": [
            "Enable Accessibility NOOP WHOOP app capture; open WHOOP daily",
            "Or run Tools/whoop_app_adb_capture.ps1 with wireless adb",
            "Append JSONL {day, strain_021, recovery_pct, noop_effort?} to pairing-logs/whoop-app-labels.jsonl",
            "Re-run this script after ≥3 distinct labeled days for accuracy_valid",
            "For sleep κ: download sleep-accel → Tools/sleep_stage_eval.py (G4)",
            "Save and correct real workouts in NOOP debug builds to collect user-confirmed sport windows; this pipeline does not classify sports yet.",
        ],
        "honest_rule": "Never treat pipeline HR collect or synthetic eval as model accuracy vs WHOOP app.",
    }

    if len(pairs) < 2:
        status = {
            **base_status,
            "status": "FEATURE_STORE_READY" if daily else "NOT_READY",
            "message": (
                f"Built {len(daily)} day(s) of features from BLE samples. "
                "Need ≥2 days of WHOOP **app** Strain labels to fit Effort→Strain. "
                "Do NOT treat synthetic/pipeline pass as model accuracy."
            ),
        }
    else:
        a, b, rmse = fit_linear(xs, ys)
        w, bias, rmse_m = fit_multifeature(X_multi, ys)
        mae = sum(abs(a * x + b - y) for x, y in zip(xs, ys)) / len(xs)
        softs = [max(0.0, 100.0 - (abs(a * x + b - y) / 15.0) * 100.0) for x, y in zip(xs, ys)]
        pass_score = sum(softs) / len(softs)
        weights = {
            "version": "0.2.0-linear+ridge",
            "linear": {
                "a": a,
                "b": b,
                "formula": "app_strain_pct_hat = a * noop_effort + b",
            },
            "ridge": {
                "feature_names": [
                    "mean_hr/100",
                    "frac_hr_ge_100",
                    "frac_hr_ge_130",
                    "rr_rmssd/100",
                    "effort_proxy/100",
                ],
                "weights": w,
                "bias": bias,
                "rmse": rmse_m,
            },
            "n_pairs": len(pairs),
            "rmse_linear": rmse,
            "mae": mae,
            "pass_score": pass_score,
            "accuracy_valid": accuracy_valid,
        }
        WEIGHTS.write_text(json.dumps(weights, indent=2), encoding="utf-8")
        status = {
            **base_status,
            "accuracy_valid": accuracy_valid,
            "status": "TRAINED" if accuracy_valid else "UNDERFIT_N<3_DAYS",
            "pass_score": round(pass_score, 2) if accuracy_valid else None,
            "mae_pct": round(mae, 2),
            "rmse_pct": round(rmse, 2),
            "weights_file": str(WEIGHTS),
            "pairs": pairs[:20],
            "message": (
                "Linear+ridge calibration from NOOP features → WHOOP app Strain%."
                if accuracy_valid
                else "Fit unstable — need more distinct labeled days before claiming accuracy."
            ),
        }

    OUT.write_text(json.dumps(status, indent=2), encoding="utf-8")
    print(json.dumps(status, indent=2))
    print(f"Wrote {OUT}")
    print(f"Wrote {FEATURES_OUT} ({len(daily)} days)")
    print(f"Wrote {SPORT_FEATURES_OUT} ({len(sport_sessions)} user-confirmed sessions)")
    print(f"Wrote {GOALS_OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
