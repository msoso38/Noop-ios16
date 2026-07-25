#!/usr/bin/env python3
"""Multi-device validation of the WHOOP 5/MG v18 SpO₂ candidate at frame byte @82.

Context (see docs/WHOOP5_DEEP_DATA.md and issue #103):
  • There is no GET_SPO2 BLE command.
  • On 5.0/MG, SpO₂ is believed to be a strap-computed scalar banked during sleep.
  • NOOP decodes in-band values (70–100) at absolute frame offset 82 as spo2_candidate_82
    (instrumentation only — never spo2Pct until multi-device evidence is solid).

This tool answers the promote-or-not question as *known plaintext*:

    capture.json  +  whoop_export  ──►  nightly mean(@82 | asleep, 70–100)
                                       vs CSV blood_oxygen_pct
                                       ──►  r, MAE, bias, offset-specificity

Owners can validate one strap, or batch several devices, and post **only aggregate
stats** on #103 (never raw SpO₂ values). Stdlib only.

Usage:

    # Single device (privacy-safe summary):
    python3 validate_spo2_candidate.py capture.json my_whoop_data/ --device strap-a

    # Show per-night table locally (still stays on your machine):
    python3 validate_spo2_candidate.py capture.json export.zip --show-nights

    # Multi-device batch:
    python3 validate_spo2_candidate.py --batch devices.json

devices.json example:
    [
      {"device": "strap-a", "capture": "a.json", "export": "export_a/"},
      {"device": "strap-b", "capture": "b.json", "export": "export_b.zip"}
    ]
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import math
import os
import statistics
import sys
import zipfile
from datetime import datetime, timezone
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

import whoop_activity as wa
import whoop_frame as wf

# Absolute frame offsets (Interpreter.decodeWhoop5Historical / whoop_activity.decode_v18).
OFF_HIST_VERSION = 9
OFF_UNIX = 15
OFF_SLEEP_BYTE = 81
OFF_SPO2_CANDIDATE = 82

# In-band SpO₂ % range (tri-mode filter — matches Swift spo2_candidate_82).
INBAND = range(70, 101)

# Band sleep_state high-nibble: 2 = asleep.
SLEEP_ASLEEP = 2

# Scan window for offset-specificity (docs checklist: only @82 should win).
SPECIFICITY_SCAN = range(74, 93)

# English + localized cycle headers → canonical keys (mirrors StrandImport HeaderNorm subset).
HEADER_ALIASES = {
    # English (official export; lowercase after norm)
    "cycle start time": "cycle_start_time",
    "cycle end time": "cycle_end_time",
    "sleep onset": "sleep_onset",
    "wake onset": "wake_onset",
    "blood oxygen %": "blood_oxygen_pct",
    "skin temp (celsius)": "skin_temp_celsius",
    "resting heart rate (bpm)": "resting_heart_rate_bpm",
    "heart rate variability (ms)": "heart_rate_variability_ms",
    "respiratory rate (rpm)": "respiratory_rate_rpm",
    # German
    "startzeit des zyklus": "cycle_start_time",
    "blutsauerstoff %": "blood_oxygen_pct",
    "hauttemperatur (celsius)": "skin_temp_celsius",
    # Spanish
    "hora de inicio del ciclo": "cycle_start_time",
    "oxígeno en sangre %": "blood_oxygen_pct",
    "temp. cutánea (grados centígrados)": "skin_temp_celsius",
}

CYCLES_FILES = {
    "physiological_cycles.csv",
    "physiologische_zyklen.csv",
    "ciclos_fisiologicos.csv",
}


# --- CSV ground truth ------------------------------------------------------------------------------

def _norm_header(h: str) -> str:
    key = h.strip().lower().lstrip("\ufeff")
    return HEADER_ALIASES.get(key, key)


def _parse_float(raw: str) -> Optional[float]:
    raw = (raw or "").strip()
    if not raw:
        return None
    try:
        return float(raw.replace(",", "."))
    except ValueError:
        return None


def _parse_export_ts(raw: str) -> Optional[int]:
    """Parse WHOOP export timestamps to unix seconds (naive wall clock → UTC for windowing).

    Export stamps are wall-clock; for night bucketing we only need a consistent second scale
    that lines up with the strap's unix field. Absolute TZ offset cancels out when both sides
    use the same convention for a given local night.
    """
    raw = (raw or "").strip()
    if not raw:
        return None
    cleaned = raw.replace("T", " ").rstrip("Z")
    for n, fmt in ((19, "%Y-%m-%d %H:%M:%S"), (16, "%Y-%m-%d %H:%M")):
        try:
            dt = datetime.strptime(cleaned[:n], fmt)
            return int(dt.replace(tzinfo=timezone.utc).timestamp())
        except ValueError:
            continue
    return None


def load_cycles(export_path: str) -> List[dict]:
    """Load physiological cycle rows with canonical keys from a WHOOP export zip/folder."""
    files: Dict[str, str] = {}
    if zipfile.is_zipfile(export_path):
        with zipfile.ZipFile(export_path) as z:
            for name in z.namelist():
                base = name.rsplit("/", 1)[-1].lower()
                if base.endswith(".csv"):
                    files[base] = z.read(name).decode("utf-8-sig", errors="replace")
    else:
        for root, _dirs, names in os.walk(export_path):
            for n in names:
                if n.lower().endswith(".csv"):
                    with open(os.path.join(root, n), encoding="utf-8-sig", errors="replace") as f:
                        files[n.lower()] = f.read()

    text = None
    for base, body in files.items():
        if base in CYCLES_FILES:
            text = body
            break
    if text is None:
        # Header sniff: any CSV that carries blood oxygen + cycle start.
        for body in files.values():
            head = body.splitlines()[0].lower() if body else ""
            if "blood oxygen" in head or "blutsauerstoff" in head or "oxígeno en sangre" in head:
                text = body
                break
    if not text:
        return []

    reader = csv.reader(io.StringIO(text))
    rows = list(reader)
    if not rows:
        return []
    headers = [_norm_header(h) for h in rows[0]]
    out = []
    for raw in rows[1:]:
        if not any(cell.strip() for cell in raw):
            continue
        row = {headers[i]: raw[i] for i in range(min(len(headers), len(raw)))}
        spo2 = _parse_float(row.get("blood_oxygen_pct", ""))
        if spo2 is None:
            continue  # incomplete night / nap without SpO₂ — not a validation target
        sleep0 = _parse_export_ts(row.get("sleep_onset", ""))
        sleep1 = _parse_export_ts(row.get("wake_onset", ""))
        cyc0 = _parse_export_ts(row.get("cycle_start_time", ""))
        cyc1 = _parse_export_ts(row.get("cycle_end_time", ""))
        # Prefer sleep window; fall back to cycle span.
        t0 = sleep0 if sleep0 is not None else cyc0
        t1 = sleep1 if sleep1 is not None else cyc1
        if t0 is None:
            continue
        if t1 is None:
            t1 = t0 + 12 * 3600  # open-ended night: generous upper bound
        if t1 <= t0:
            t1 = t0 + 12 * 3600
        out.append({
            "cycle_start_time": row.get("cycle_start_time", ""),
            "spo2_export": spo2,
            "t0": t0,
            "t1": t1,
        })
    return out


# --- Capture decode --------------------------------------------------------------------------------

def iter_v18_records(capture_records: Sequence[dict]) -> Iterable[dict]:
    """Yield decoded v18 fields from capture.json entries (absolute frame offsets)."""
    for rec in capture_records:
        hx = rec.get("hex") if isinstance(rec, dict) else None
        if not hx:
            continue
        try:
            frame = bytes.fromhex(hx)
        except ValueError:
            continue
        if len(frame) <= 116:
            continue
        # Prefer CRC-valid WHOOP 5 frames; still accept synthetic absolute buffers used in tests
        # (make_v18 style: length 124, hist_version @9, no real CRC).
        if frame[0] == 0xAA and len(frame) > 8:
            if not (wf.verify_whoop5_frame(frame) or frame[OFF_HIST_VERSION] == 18):
                continue
        d = wa.decode_v18(frame)
        if d is None:
            continue
        d = dict(d)
        raw82 = frame[OFF_SPO2_CANDIDATE] if len(frame) > OFF_SPO2_CANDIDATE else d.get("aux_byte_82", 0)
        d["aux_byte_82"] = raw82
        d["spo2_candidate_82"] = raw82 if raw82 in INBAND else None
        # Neighbor bytes for specificity scan (absolute offsets).
        d["_frame"] = frame
        yield d


def night_mean_at_offset(
    records: Sequence[dict],
    t0: int,
    t1: int,
    offset: int,
    *,
    require_asleep: bool = True,
) -> Optional[Tuple[float, int]]:
    """Mean of in-band u8 samples at `offset` inside [t0, t1). Returns (mean, n) or None."""
    vals = []
    for r in records:
        u = r["unix"]
        if u < t0 or u >= t1:
            continue
        if require_asleep and r.get("sleep_state") != SLEEP_ASLEEP:
            continue
        frame = r.get("_frame")
        if frame is None or len(frame) <= offset:
            continue
        v = frame[offset]
        if v in INBAND:
            vals.append(v)
    if not vals:
        return None
    return (statistics.fmean(vals), len(vals))


def pearson_r(xs: Sequence[float], ys: Sequence[float]) -> Optional[float]:
    n = len(xs)
    if n < 2:
        return None
    mx = statistics.fmean(xs)
    my = statistics.fmean(ys)
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    denx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    deny = math.sqrt(sum((y - my) ** 2 for y in ys))
    if denx == 0 or deny == 0:
        return None
    return num / (denx * deny)


def mae(xs: Sequence[float], ys: Sequence[float]) -> Optional[float]:
    if not xs:
        return None
    return statistics.fmean(abs(x - y) for x, y in zip(xs, ys))


# --- Per-device validation -------------------------------------------------------------------------

def validate_device(
    capture_path: str,
    export_path: str,
    *,
    device: str = "device",
    require_asleep: bool = True,
) -> dict:
    with open(capture_path) as f:
        capture = json.load(f)
    if not isinstance(capture, list):
        raise SystemExit(f"{capture_path}: expected a JSON list of frame records")

    cycles = load_cycles(export_path)
    records = list(iter_v18_records(capture))

    nights = []
    for c in cycles:
        hit = night_mean_at_offset(
            records, c["t0"], c["t1"], OFF_SPO2_CANDIDATE, require_asleep=require_asleep
        )
        if hit is None:
            nights.append({
                "cycle_start_time": c["cycle_start_time"],
                "export": c["spo2_export"],
                "candidate_mean": None,
                "n_samples": 0,
                "matched": False,
            })
            continue
        mean_v, n = hit
        nights.append({
            "cycle_start_time": c["cycle_start_time"],
            "export": c["spo2_export"],
            "candidate_mean": mean_v,
            "n_samples": n,
            "matched": True,
            "abs_err": abs(mean_v - c["spo2_export"]),
        })

    paired_export = [n["export"] for n in nights if n["matched"]]
    paired_cand = [n["candidate_mean"] for n in nights if n["matched"]]
    r = pearson_r(paired_export, paired_cand) if len(paired_cand) >= 2 else None
    err = mae(paired_export, paired_cand)
    bias = (
        statistics.fmean(c - e for c, e in zip(paired_cand, paired_export))
        if paired_cand else None
    )

    # Offset specificity: among SPECIFICITY_SCAN, which offset maximises |r| on paired nights?
    spec_scores = []
    for off in SPECIFICITY_SCAN:
        xs, ys = [], []
        for c in cycles:
            hit = night_mean_at_offset(
                records, c["t0"], c["t1"], off, require_asleep=require_asleep
            )
            if hit is None:
                continue
            xs.append(c["spo2_export"])
            ys.append(hit[0])
        rr = pearson_r(xs, ys) if len(xs) >= 2 else None
        if rr is not None:
            spec_scores.append({"offset": off, "r": rr, "nights": len(xs)})
    spec_scores.sort(key=lambda s: abs(s["r"]), reverse=True)
    best_off = spec_scores[0]["offset"] if spec_scores else None
    r_at_82 = next((s["r"] for s in spec_scores if s["offset"] == OFF_SPO2_CANDIDATE), r)

    # Checklist gates (docs/WHOOP5_DEEP_DATA.md) — conservative defaults for instrumentation.
    n_paired = len(paired_cand)
    checklist = {
        "min_nights": n_paired >= 5,
        "real_spread": (
            (max(paired_export) - min(paired_export) >= 1.0) if n_paired >= 2 else False
        ),
        "corr_ge_0_7": (r is not None and r >= 0.7),
        "mae_le_1_0": (err is not None and err <= 1.0),
        "offset_82_wins": best_off == OFF_SPO2_CANDIDATE,
    }
    checklist["pass"] = all(checklist.values())

    return {
        "device": device,
        "v18_records": len(records),
        "export_nights_with_spo2": len(cycles),
        "paired_nights": n_paired,
        "r": r,
        "r_at_82_specificity": r_at_82,
        "mae": err,
        "bias": bias,
        "best_specificity_offset": best_off,
        "specificity_top3": spec_scores[:3],
        "checklist": checklist,
        "nights": nights,
        "inband_samples_total": sum(n["n_samples"] for n in nights),
    }


def format_summary(result: dict, *, show_nights: bool = False) -> str:
    """Privacy-safe summary: aggregates only (no raw SpO₂ unless --show-nights)."""
    lines = []
    cl = result["checklist"]
    lines.append(
        f"device={result['device']}  v18_records={result['v18_records']}  "
        f"export_nights={result['export_nights_with_spo2']}  paired={result['paired_nights']}  "
        f"inband_samples={result['inband_samples_total']}"
    )
    r = result["r"]
    mae_v = result["mae"]
    bias = result["bias"]
    lines.append(
        f"  @82 nightly mean vs export:  r="
        f"{'n/a' if r is None else f'{r:+.3f}'}  "
        f"MAE={'n/a' if mae_v is None else f'{mae_v:.3f}'}  "
        f"bias={'n/a' if bias is None else f'{bias:+.3f}'}"
    )
    lines.append(
        f"  specificity: best_offset={result['best_specificity_offset']}  "
        f"top3="
        + ", ".join(
            f"@{s['offset']} r={s['r']:+.3f} (n={s['nights']})"
            for s in result.get("specificity_top3", [])
        )
    )
    flags = " ".join(
        f"{k}={'PASS' if v else 'FAIL'}"
        for k, v in cl.items()
        if k != "pass"
    )
    lines.append(f"  checklist: {flags}")
    lines.append(f"  OVERALL: {'PASS (candidate strengthens)' if cl['pass'] else 'FAIL (keep instrumentation-only)'}")
    if show_nights:
        lines.append("  per-night (local only — do not post raw values):")
        for n in result["nights"]:
            if not n["matched"]:
                lines.append(f"    {n['cycle_start_time']}: export={n['export']:.2f}  candidate=—  (no in-band samples)")
            else:
                lines.append(
                    f"    {n['cycle_start_time']}: export={n['export']:.2f}  "
                    f"candidate={n['candidate_mean']:.2f}  n={n['n_samples']}  "
                    f"|err|={n['abs_err']:.2f}"
                )
    return "\n".join(lines)


def format_postable(results: Sequence[dict]) -> str:
    """One-liner block safe to paste on GitHub #103 (no health values)."""
    lines = [
        "spo2_candidate_82 multi-device validation (validate_spo2_candidate.py)",
        "device,paired_nights,r,mae,bias,best_offset,checklist_pass",
    ]
    for res in results:
        r = res["r"]
        mae_v = res["mae"]
        bias = res["bias"]
        lines.append(
            f"{res['device']},{res['paired_nights']},"
            f"{'' if r is None else f'{r:.3f}'},"
            f"{'' if mae_v is None else f'{mae_v:.3f}'},"
            f"{'' if bias is None else f'{bias:.3f}'},"
            f"{res['best_specificity_offset']},"
            f"{res['checklist']['pass']}"
        )
    # Multi-device aggregate: promote only if every device with ≥5 paired nights passes.
    eligible = [res for res in results if res["paired_nights"] >= 5]
    if eligible:
        all_pass = all(res["checklist"]["pass"] for res in eligible)
        lines.append(
            f"multi_device_eligible={len(eligible)} all_pass={all_pass} "
            f"(promote spo2Pct only if all_pass and ≥2 devices)"
        )
    else:
        lines.append("multi_device_eligible=0 (need ≥5 paired nights per device)")
    return "\n".join(lines)


# --- CLI -------------------------------------------------------------------------------------------

def main(argv: Optional[Sequence[str]] = None) -> int:
    p = argparse.ArgumentParser(
        description="Validate WHOOP 5/MG v18 spo2_candidate_82 against a CSV export (#103)."
    )
    p.add_argument("capture", nargs="?", help="capture.json (from hci_extract / whoop_capture)")
    p.add_argument("export", nargs="?", help="WHOOP CSV export .zip or folder")
    p.add_argument("--device", default="device", help="label for this strap (default: device)")
    p.add_argument(
        "--batch",
        metavar="devices.json",
        help="JSON list of {device,capture,export} for multi-device validation",
    )
    p.add_argument(
        "--show-nights",
        action="store_true",
        help="print per-night export vs candidate (local only — contains health values)",
    )
    p.add_argument(
        "--allow-any-sleep-state",
        action="store_true",
        help="do not require sleep_state=asleep when averaging @82 (debug)",
    )
    p.add_argument(
        "--postable",
        action="store_true",
        help="print a CSV-ish block safe to paste on GitHub (no raw SpO₂ values)",
    )
    args = p.parse_args(argv)

    require_asleep = not args.allow_any_sleep_state
    results: List[dict] = []

    if args.batch:
        with open(args.batch) as f:
            batch = json.load(f)
        if not isinstance(batch, list) or not batch:
            print("batch file must be a non-empty JSON list", file=sys.stderr)
            return 1
        for entry in batch:
            results.append(
                validate_device(
                    entry["capture"],
                    entry["export"],
                    device=entry.get("device", "device"),
                    require_asleep=require_asleep,
                )
            )
    else:
        if not args.capture or not args.export:
            p.error("capture and export are required unless --batch is set")
        results.append(
            validate_device(
                args.capture,
                args.export,
                device=args.device,
                require_asleep=require_asleep,
            )
        )

    for res in results:
        print(format_summary(res, show_nights=args.show_nights))
        print()
    if args.postable or len(results) > 1:
        print("--- postable summary (safe for #103) ---")
        print(format_postable(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
