#!/usr/bin/env python3
"""Train / evaluate stress tip calibration + sleep-stage plumbing + effort→strain.

CPU-first (desk RX 7900 / laptop RTX idle is fine; these fits are linear / tiny).
Never invents clinical vitals. Labels must come from WHOOP app UI / screenshots /
adb dumps in pairing-logs/whoop-app-labels.jsonl.

Usage (from AI app store root OR noop checkout with pairing-logs/):
  python Tools/stress_sleep_train.py
  python Tools/stress_sleep_train.py --pairing pairing-logs --as-of 2026-07-14

Outputs:
  pairing-logs/stress-sleep-train-status.json
  pairing-logs/ml-stress-tip-weights.json   (when ≥2 clock-matched tip pairs)
  pairing-logs/sleep-stage-eval.json       (via sleep_stage_eval)
  pairing-logs/ml-engine-status.json       (via ml_engine_train)
  pairing-logs/calibration-report.json     (via calibrate_whoop_noop when present)
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import subprocess
import sys
from datetime import date, datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def resolve_pairing(explicit: Path | None) -> Path:
    if explicit is not None:
        return explicit
    candidates = [
        ROOT / "pairing-logs",
        ROOT.parent / "pairing-logs",
        Path(r"C:\Users\Gilbert\Documents\Ai app store\pairing-logs"),
    ]
    for c in candidates:
        if c.is_dir():
            return c
    return candidates[0]


def load_jsonl(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        t = line.strip().lstrip("\ufeff")
        if not t or t.startswith("#"):
            continue
        try:
            rows.append(json.loads(t))
        except json.JSONDecodeError:
            continue
    return rows


def affine_fit(xs: list[float], ys: list[float]) -> tuple[float, float]:
    n = len(xs)
    if n < 2:
        return 1.0, 0.0
    mx, my = statistics.mean(xs), statistics.mean(ys)
    varx = sum((x - mx) ** 2 for x in xs)
    if varx < 1e-12:
        return 1.0, my - mx
    cov = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    a = cov / varx
    return a, my - a * mx


def mae(pairs: list[tuple[float, float]]) -> float | None:
    if not pairs:
        return None
    return sum(abs(a - b) for a, b in pairs) / len(pairs)


def stress_tip_pairs(rows: list[dict]) -> list[dict]:
    """Clock-matched tip pairs: need whoop stress_tip + noop_tip on 0–3 scale."""
    out = []
    for r in rows:
        if r.get("stress_tip") is None or r.get("noop_tip") is None:
            continue
        if r.get("tip_clock_matched") is False:
            continue
        try:
            w = float(r["stress_tip"])
            n = float(r["noop_tip"])
        except (TypeError, ValueError):
            continue
        out.append(
            {
                "day": r.get("day"),
                "clock": r.get("clock"),
                "whoop_tip": w,
                "noop_tip": n,
                "delta": round(n - w, 4),
                "whoop_band": r.get("stress_band"),
                "noop_band": r.get("noop_band"),
                "source": r.get("source"),
                "serial": r.get("serial"),
                "whoop_file": r.get("whoop_file"),
                "noop_file": r.get("noop_file"),
            }
        )
    return out


def sleep_rest_pairs(rows: list[dict]) -> list[dict]:
    """WHOOP sleep_pct (0–100) vs NOOP Rest (noop_rest) when both present."""
    out = []
    for r in rows:
        if r.get("sleep_pct") is None or r.get("noop_rest") is None:
            continue
        try:
            w = float(r["sleep_pct"])
            n = float(r["noop_rest"])
        except (TypeError, ValueError):
            continue
        out.append(
            {
                "day": r.get("day"),
                "whoop_sleep_pct": w,
                "noop_rest": n,
                "delta": round(n - w, 4),
                "whoop_sws_pct": r.get("whoop_sws_pct"),
                "whoop_rem_pct": r.get("whoop_rem_pct"),
                "noop_deep_pct": r.get("noop_deep_pct"),
                "noop_rem_pct": r.get("noop_rem_pct"),
                "source": r.get("source"),
                "serial": r.get("serial"),
            }
        )
    return out


def fit_tip_head(pairs: list[dict], scale_note: str) -> dict:
    xs = [p["noop_tip"] if "noop_tip" in p else p["noop_rest"] for p in pairs]
    ys = [p["whoop_tip"] if "whoop_tip" in p else p["whoop_sleep_pct"] for p in pairs]
    raw = list(zip(xs, ys))
    before = mae(raw)
    fitted = len(pairs) >= 2
    a, b = affine_fit(xs, ys) if fitted else (1.0, 0.0)
    after = mae([(a * x + b, y) for x, y in raw]) if fitted else before
    # Same qualitative band for stress 0–3: LOW <1, MEDIUM <2, else HIGH
    same_band = None
    if pairs and "whoop_tip" in pairs[0]:
        def band(v: float) -> str:
            if v < 1.0:
                return "LOW"
            if v < 2.0:
                return "MEDIUM"
            return "HIGH"

        same_band = sum(1 for p in pairs if band(p["whoop_tip"]) == band(p["noop_tip"])) / len(pairs)
    accuracy_valid = len(pairs) >= 4 and (same_band is None or same_band >= 0.75)
    return {
        "n": len(pairs),
        "mae_before": before,
        "mae_after_affine": after,
        "affine_a": a,
        "affine_b": b,
        "fitted": fitted,
        "accuracy_valid": accuracy_valid,
        "same_band_frac": same_band,
        "scale": scale_note,
        "pairs": pairs,
        "honest_rule": (
            "accuracy_valid only with ≥4 tip@clock pairs and ≥75% same band. "
            "Do not retune DaytimeStress constants from a single δ."
        ),
    }


def run_script(script: Path, args: list[str], cwd: Path) -> dict:
    if not script.is_file():
        return {"ok": False, "missing": str(script)}
    cmd = [sys.executable, str(script), *args]
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(cwd),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=600,
        )
        return {
            "ok": proc.returncode == 0,
            "returncode": proc.returncode,
            "cmd": cmd,
            "stdout_tail": (proc.stdout or "")[-2000:],
            "stderr_tail": (proc.stderr or "")[-1000:],
        }
    except Exception as e:
        return {"ok": False, "error": str(e), "cmd": cmd}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairing", type=Path, default=None)
    ap.add_argument("--as-of", type=str, default=date.today().isoformat())
    ap.add_argument("--skip-subprocess", action="store_true")
    args = ap.parse_args()
    pairing = resolve_pairing(args.pairing)
    pairing.mkdir(parents=True, exist_ok=True)
    labels_path = pairing / "whoop-app-labels.jsonl"
    rows = load_jsonl(labels_path)

    tip = stress_tip_pairs(rows)
    sleep = sleep_rest_pairs(rows)
    # Remap sleep pairs field names for fit_tip_head
    sleep_for_fit = [
        {
            "day": p["day"],
            "noop_rest": p["noop_rest"],
            "whoop_sleep_pct": p["whoop_sleep_pct"],
            **{k: v for k, v in p.items() if k not in ("noop_rest", "whoop_sleep_pct")},
        }
        for p in sleep
    ]
    # fit_tip_head expects noop_tip/whoop_tip OR we specialize:
    sleep_head = {
        "n": len(sleep),
        "mae_before": mae([(p["noop_rest"], p["whoop_sleep_pct"]) for p in sleep]),
        "pairs": sleep,
        "note": (
            "Rest (NOOP) vs Sleep% (WHOOP) are related but not identical scales. "
            "Use for trend checks; RestScorer tickets live in SLEEP_FACTORS / Fable backlog."
        ),
        "accuracy_valid": False,
    }
    if len(sleep) >= 2:
        xs = [p["noop_rest"] for p in sleep]
        ys = [p["whoop_sleep_pct"] for p in sleep]
        a, b = affine_fit(xs, ys)
        sleep_head["affine_a"] = a
        sleep_head["affine_b"] = b
        sleep_head["mae_after_affine"] = mae([(a * x + b, y) for x, y in zip(xs, ys)])
        sleep_head["fitted"] = True
    else:
        sleep_head["fitted"] = False

    stress_head = fit_tip_head(tip, "WHOOP/NOOP stress tip 0-3")

    weights = {
        "version": "0.1.0-stress-tip-affine",
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "formula": "whoop_tip_hat = a * noop_tip + b",
        "a": stress_head["affine_a"],
        "b": stress_head["affine_b"],
        "n_pairs": stress_head["n"],
        "mae_before": stress_head["mae_before"],
        "mae_after": stress_head["mae_after_affine"],
        "accuracy_valid": stress_head["accuracy_valid"],
        "citations": [
            "docs/STRESS_FACTORS_AND_LITERATURE.md",
            "docs/WHOOP_NOOP_SCREENSHOT_COMPARE.md",
            "docs/STRESS_SLEEP_ML_REPRO.md",
        ],
    }
    weights_path = pairing / "ml-stress-tip-weights.json"
    weights_path.write_text(json.dumps(weights, indent=2), encoding="utf-8")

    sub = {}
    if not args.skip_subprocess:
        tools = ROOT / "Tools"
        # Prefer scripts next to this file; fall back to store Tools if running from noop-v8 copy
        sleep_py = tools / "sleep_stage_eval.py"
        if not sleep_py.is_file():
            sleep_py = Path(r"C:\Users\Gilbert\Documents\Ai app store\Tools\sleep_stage_eval.py")
        train_py = tools / "ml_engine_train.py"
        cal_py = tools / "calibrate_whoop_noop.py"
        sub["sleep_stage_eval"] = run_script(sleep_py, ["--synthetic"], ROOT)
        sub["ml_engine_train"] = run_script(
            train_py,
            ["--labels", str(labels_path), "--as-of", args.as_of],
            ROOT,
        )
        if cal_py.is_file():
            sub["calibrate_whoop_noop"] = run_script(
                cal_py, ["--pairing", str(pairing)], ROOT
            )

    status = {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "host_hint": "Prefer desk Tailscale Host desk (DESKTOP-UEJPJSH); CPU affine is enough",
        "pairing": str(pairing),
        "n_label_rows": len(rows),
        "stress_tip": stress_head,
        "sleep_rest": sleep_head,
        "weights_file": str(weights_path),
        "subprocess": sub,
        "fold_status": (
            "Fold plugged-in but powered OFF this session — no live USB ADB, tip@clock, "
            "bank pull, or live-edit. Device verification deferred until phone is on."
        ),
        "factor_docs": [
            "docs/STRESS_FACTORS_AND_LITERATURE.md",
            "docs/SLEEP_FACTORS_AND_LITERATURE.md",
            "docs/SLEEP_ML_DATASETS.md",
            "docs/WHOOP_NOOP_SCREENSHOT_COMPARE.md",
            "docs/FABLE5_300_NOT_INTUITIVE.md",
        ],
        "tickets": ["STRESS-TIP", "STRESS-SHAPE", "REST-WEIGHT", "SLEEP-SELF-1", "G4_sleep_kappa"],
        "next": [
            "Decode more tip@clock pairs (≥4) before touching DaytimeStress constants",
            "Download PhysioNet sleep-accel → python Tools/sleep_stage_eval.py --sleep-accel-dir …",
            "When Fold is on: bank pull + FoldStressReplay with screenshot cutoff",
        ],
        "honest_rule": "Never invent SpO2/BP/clinical stages. accuracy_valid stays false until N gates pass.",
    }
    out = pairing / "stress-sleep-train-status.json"
    out.write_text(json.dumps(status, indent=2), encoding="utf-8")
    print(json.dumps({k: status[k] for k in (
        "generated_at", "n_label_rows", "stress_tip", "sleep_rest", "weights_file", "fold_status"
    )}, indent=2))
    print(f"Wrote {out}")
    print(f"Wrote {weights_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
