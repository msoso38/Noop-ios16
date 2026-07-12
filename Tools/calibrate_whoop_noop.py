#!/usr/bin/env python3
"""CPU-only WHOOP↔NOOP affine calibration + honest metric table.

Reads pairing-logs labels + daily features / optional noop day JSON.
Writes pairing-logs/calibration-report.json and prints a markdown table.

No GPU. No synthetic WHOOP labels.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
from datetime import date, datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAIRING = ROOT.parent / "pairing-logs"
if not PAIRING.is_dir():
    PAIRING = ROOT / "pairing-logs"

LABELS = PAIRING / "whoop-app-labels.jsonl"
FEATURES = PAIRING / "ml-daily-features.json"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets" / "whoop_app_labels.jsonl"
NOOP_DAYS = PAIRING / "noop-daily-metrics.jsonl"  # optional export
OUT = PAIRING / "calibration-report.json"

MIN_N_FIT = 3
BAND = {
    "charge": 12.0,
    "effort": 15.0,
    "sleep": 15.0,
    "stress": 20.0,
}


def _load_jsonl(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        t = line.strip()
        if not t or t.startswith("#"):
            continue
        try:
            rows.append(json.loads(t))
        except json.JSONDecodeError:
            continue
    return rows


def _strain_to_100(v: float | None) -> float | None:
    if v is None:
        return None
    if v <= 21.0 + 1e-6:
        return max(0.0, min(100.0, v / 21.0 * 100.0))
    return max(0.0, min(100.0, v))


def _effort_proxy_to_100(features_day: dict | None) -> float | None:
    if not features_day:
        return None
    p = features_day.get("effort_proxy_0_100")
    return float(p) if p is not None else None


def _affine_fit(xs: list[float], ys: list[float]) -> tuple[float, float]:
    """Least-squares y ≈ a*x + b. Degenerate → identity."""
    n = len(xs)
    if n < 2:
        return 1.0, 0.0
    mx = statistics.mean(xs)
    my = statistics.mean(ys)
    varx = sum((x - mx) ** 2 for x in xs)
    if varx < 1e-12:
        return 1.0, my - mx
    cov = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    a = cov / varx
    b = my - a * mx
    return a, b


def _mae(pairs: list[tuple[float, float]]) -> float | None:
    if not pairs:
        return None
    return sum(abs(a - b) for a, b in pairs) / len(pairs)


def _pearson(xs: list[float], ys: list[float]) -> float | None:
    n = len(xs)
    if n < 3:
        return None
    mx, my = statistics.mean(xs), statistics.mean(ys)
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    denx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    deny = math.sqrt(sum((y - my) ** 2 for y in ys))
    if denx < 1e-12 or deny < 1e-12:
        return None
    return num / (denx * deny)


def _loo_mae(xs: list[float], ys: list[float]) -> float | None:
    n = len(xs)
    if n < 3:
        return None
    errs = []
    for i in range(n):
        tx = xs[:i] + xs[i + 1 :]
        ty = ys[:i] + ys[i + 1 :]
        a, b = _affine_fit(tx, ty)
        pred = a * xs[i] + b
        errs.append(abs(pred - ys[i]))
    return sum(errs) / len(errs)


def collect_pairs(features: dict) -> dict[str, list[dict]]:
    """Build per-head pairs from labels + optional noop metrics + feature proxies."""
    label_rows = _load_jsonl(LABELS) + _load_jsonl(ASSETS)
    by_day: dict[str, dict] = {}
    for r in label_rows:
        day = r.get("day")
        if not day:
            continue
        by_day.setdefault(day, {}).update(
            {
                "whoop_recovery": r.get("recovery_pct") if r.get("recovery_pct") is not None else r.get("recoveryPct"),
                "whoop_strain_021": r.get("strain_021") if r.get("strain_021") is not None else r.get("dayStrain021"),
                "whoop_sleep": r.get("sleep_pct") if r.get("sleep_pct") is not None else r.get("sleepPct"),
                "whoop_stress": r.get("stress_pct") if r.get("stress_pct") is not None else r.get("stressPct"),
                "source": r.get("source"),
            }
        )

    for r in _load_jsonl(NOOP_DAYS):
        day = r.get("day")
        if not day:
            continue
        by_day.setdefault(day, {}).update(
            {
                "noop_recovery": r.get("recovery"),
                "noop_strain": r.get("strain"),
                "noop_sleep": r.get("sleep_performance") or r.get("rest"),
                "noop_stress": r.get("stress_pct"),
            }
        )

    feat_days = (features or {}).get("days") or {}
    today = date.today().isoformat()
    out = {"charge": [], "effort": [], "sleep": [], "stress": []}
    for day, row in sorted(by_day.items()):
        if day >= today:
            continue  # incomplete day guard
        f = feat_days.get(day)
        whoop_e = _strain_to_100(row.get("whoop_strain_021"))
        noop_e = row.get("noop_strain")
        if noop_e is None:
            noop_e = _effort_proxy_to_100(f)

        if row.get("whoop_recovery") is not None and row.get("noop_recovery") is not None:
            out["charge"].append(
                {
                    "day": day,
                    "noop": float(row["noop_recovery"]),
                    "whoop": float(row["whoop_recovery"]),
                }
            )
        if whoop_e is not None and noop_e is not None:
            out["effort"].append({"day": day, "noop": float(noop_e), "whoop": float(whoop_e)})
        if row.get("whoop_sleep") is not None and row.get("noop_sleep") is not None:
            out["sleep"].append(
                {
                    "day": day,
                    "noop": float(row["noop_sleep"]),
                    "whoop": float(row["whoop_sleep"]),
                }
            )
        if row.get("whoop_stress") is not None and row.get("noop_stress") is not None:
            out["stress"].append(
                {
                    "day": day,
                    "noop": float(row["noop_stress"]),
                    "whoop": float(row["whoop_stress"]),
                }
            )
    return out


def evaluate_head(name: str, pairs: list[dict]) -> dict:
    xs = [p["noop"] for p in pairs]
    ys = [p["whoop"] for p in pairs]
    raw = list(zip(xs, ys))
    mae_before = _mae(raw)
    a, b = (1.0, 0.0)
    mae_after = mae_before
    loo = None
    r = _pearson(xs, ys)
    fitted = False
    if len(pairs) >= MIN_N_FIT:
        a, b = _affine_fit(xs, ys)
        fitted = True
        cal = [(a * x + b, y) for x, y in raw]
        mae_after = _mae(cal)
        loo = _loo_mae(xs, ys)
    within = None
    if pairs:
        band = BAND[name]
        within = sum(1 for x, y in raw if abs(x - y) <= band) / len(raw)
    return {
        "head": name,
        "n": len(pairs),
        "mae_before": mae_before,
        "mae_after_affine": mae_after,
        "loo_mae": loo,
        "pearson_r": r,
        "affine_a": a,
        "affine_b": b,
        "fitted": fitted,
        "within_band_frac": within,
        "band": BAND[name],
        "days": [p["day"] for p in pairs],
        "citations": "Plews2013 / Banister1991 / Cole-Kripke1992 / Baevsky2008; affine per docs/CALIBRATION.md",
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairing", type=Path, default=PAIRING)
    args = ap.parse_args()
    pairing = args.pairing
    features = {}
    feat_path = pairing / "ml-daily-features.json"
    if feat_path.is_file():
        features = json.loads(feat_path.read_text(encoding="utf-8"))

    global LABELS, ASSETS, NOOP_DAYS, OUT
    LABELS = pairing / "whoop-app-labels.jsonl"
    NOOP_DAYS = pairing / "noop-daily-metrics.jsonl"
    OUT = pairing / "calibration-report.json"

    pairs = collect_pairs(features)
    heads = [evaluate_head(k, pairs[k]) for k in ("charge", "effort", "sleep", "stress")]
    n_fit = sum(1 for h in heads if h["fitted"])
    n_any = sum(h["n"] for h in heads)
    accuracy_valid = n_fit >= 2 and all(
        h["n"] >= MIN_N_FIT or h["n"] == 0 for h in heads if h["head"] in ("charge", "effort", "sleep")
    )
    # Stricter: Charge + Effort + Sleep must each have n>=3 to claim valid multi-head accuracy.
    accuracy_valid = all(h["n"] >= MIN_N_FIT for h in heads if h["head"] in ("charge", "effort", "sleep"))

    report = {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "accuracy_valid": accuracy_valid,
        "min_n_fit": MIN_N_FIT,
        "n_label_rows": len(_load_jsonl(LABELS) + _load_jsonl(ASSETS)),
        "n_pairs_total": n_any,
        "heads_fitted": n_fit,
        "gpu": "none (CPU-only)",
        "method": "affine least-squares + LOO MAE; shared 0-100 (strain ×100/21)",
        "citations_doc": "docs/CALIBRATION.md",
        "heads": heads,
        "deploy_gate": "PASS" if accuracy_valid else "FAIL - need >=3 paired days on Charge, Effort, Sleep",
        "note": "Never invent WHOOP labels. Sparse N means gate fail, not fake high accuracy.",
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, indent=2), encoding="utf-8")

    def _fmt(v):
        return "-" if v is None else f"{v:.2f}" if isinstance(v, float) else str(v)

    print("| Head | N | MAE before | MAE after affine | Pearson r | Fitted |")
    print("|------|---|------------|------------------|-----------|--------|")
    for h in heads:
        pr = "-" if h["pearson_r"] is None else f"{h['pearson_r']:.3f}"
        print(
            f"| {h['head']} | {h['n']} | {_fmt(h['mae_before'])} | "
            f"{_fmt(h['mae_after_affine'])} | {pr} | {h['fitted']} |"
        )
    print()
    print(f"accuracy_valid={accuracy_valid}  deploy_gate={report['deploy_gate']}")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
