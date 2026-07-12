#!/usr/bin/env python3
"""One-shot or --loop (12h) WHOOP vs NOOP status report. CPU-only."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAIRING = ROOT.parent / "pairing-logs"
if not PAIRING.is_dir():
    PAIRING = ROOT / "pairing-logs"
REPORTS = PAIRING / "reports"


def run_once() -> Path:
    subprocess.check_call([sys.executable, str(ROOT / "Tools" / "ml_engine_train.py")])
    subprocess.check_call([sys.executable, str(ROOT / "Tools" / "calibrate_whoop_noop.py")])
    status = json.loads((PAIRING / "ml-engine-status.json").read_text(encoding="utf-8"))
    cal = {}
    cal_path = PAIRING / "calibration-report.json"
    if cal_path.is_file():
        cal = json.loads(cal_path.read_text(encoding="utf-8"))
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%MZ")
    REPORTS.mkdir(parents=True, exist_ok=True)
    out = REPORTS / f"whoop-noop-{stamp}.md"
    lines = [
        f"# WHOOP vs NOOP report — {stamp}",
        "",
        "Honest pipeline status (no invented pass %).",
        "",
        f"- status: `{status.get('status')}`",
        f"- accuracy_valid: `{cal.get('accuracy_valid', status.get('accuracy_valid'))}`",
        f"- n_label_rows: `{status.get('n_label_rows')}`",
        f"- n_feature_days: `{status.get('n_feature_days')}`",
        f"- n_ml_samples_ingested: `{status.get('n_ml_samples_ingested')}`",
        f"- n_pairs: `{status.get('n_pairs')}`",
        f"- deploy_gate: `{cal.get('deploy_gate', 'n/a')}`",
        f"- gpu: `{status.get('gpu', 'none')}`",
        f"- message: {status.get('message')}",
        "",
        "## Calibration heads",
        "",
        "| Head | N | MAE before | MAE after | r | Fitted |",
        "|------|---|------------|-----------|---|--------|",
    ]
    for h in cal.get("heads", []):
        mb = "—" if h.get("mae_before") is None else f"{h['mae_before']:.2f}"
        ma = "—" if h.get("mae_after_affine") is None else f"{h['mae_after_affine']:.2f}"
        pr = "—" if h.get("pearson_r") is None else f"{h['pearson_r']:.3f}"
        lines.append(f"| {h['head']} | {h['n']} | {mb} | {ma} | {pr} | {h['fitted']} |")
    lines += ["", "## Goals", ""]
    for k, v in (status.get("goals") or {}).items():
        lines.append(f"- `{k}`: {v}")
    lines += ["", "## Next", ""]
    for a in status.get("next_actions") or []:
        lines.append(f"- {a}")
    lines.append("")
    out.write_text("\n".join(lines), encoding="utf-8")
    print(out)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--loop", action="store_true", help="Re-run every 12 hours")
    args = ap.parse_args()
    if not args.loop:
        run_once()
        return 0
    while True:
        run_once()
        time.sleep(12 * 3600)


if __name__ == "__main__":
    raise SystemExit(main())
