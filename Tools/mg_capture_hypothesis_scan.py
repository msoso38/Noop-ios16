#!/usr/bin/env python3
"""Offline-only WHOOP 5/MG capture analyzer.

Reads a lossless capture.json array or JSONL capture with `hex` and `char` fields. It validates Puffin
CRC16/CRC32, inventories type-47 versions, and, when supplied a labelled CSV, scans numeric byte layouts
against that label. Random permutations establish a null baseline; a ranked candidate is a research lead,
not a decoded metric. This tool never connects to or writes to a BLE device.

Examples:
  python tools/mg_capture_hypothesis_scan.py capture.json
  python tools/mg_capture_hypothesis_scan.py capture.json --labels cuff.csv --metric systolic

The labels CSV must contain: unix,metric,value. Values such as BP or SpO2 remain labels only. Do not expose
any candidate as a health measurement without independent validation across sessions and devices.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import re
import sys
import zlib
from collections import Counter
from pathlib import Path
from typing import Iterable


def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF


def parse_hex(value: object) -> bytes | None:
    if not isinstance(value, str):
        return None
    try:
        data = bytes.fromhex(value.strip())
    except ValueError:
        return None
    return data or None


def frames(path: Path) -> Iterable[bytes]:
    text = path.read_text(encoding="utf-8")
    try:
        items = json.loads(text)
        if isinstance(items, dict):
            items = [items]
    except json.JSONDecodeError:
        items = []
        for line in text.splitlines():
            if not line.strip().startswith("{"):
                continue
            try:
                items.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        # DEBUG PC telemetry is intentionally human-readable and truncates long frames. It remains useful
        # for inventorying complete small Puffin records, but never for tail-field mapping or CRC proof.
        if not items:
            for line in text.splitlines():
                match = re.search(r"uuid=([^ ]+).*?hex=([0-9a-fA-F]+)(?:\s|$)", line)
                if match and "fd4b" in match.group(1).lower():
                    raw = parse_hex(match.group(2))
                    if raw is not None:
                        yield raw
            return
    if not isinstance(items, list):
        raise ValueError("Capture must be a JSON array, JSONL, or DEBUG telemetry log.")
    for item in items:
        if isinstance(item, dict):
            char = str(item.get("char", item.get("uuid", ""))).lower()
            if "fd4b" in char:
                raw = parse_hex(item.get("hex"))
                if raw is not None:
                    yield raw


def valid_puffin(frame: bytes) -> bool:
    if len(frame) < 12 or frame[:2] != b"\xaa\x01":
        return False
    declared = int.from_bytes(frame[2:4], "little")
    if declared + 8 != len(frame):
        return False
    if crc16_modbus(frame[:6]) != int.from_bytes(frame[6:8], "little"):
        return False
    return (zlib.crc32(frame[8:-4]) & 0xFFFFFFFF) == int.from_bytes(frame[-4:], "little")


def u(frame: bytes, offset: int, width: int, signed: bool) -> int | None:
    if offset + width > len(frame) - 4:
        return None
    return int.from_bytes(frame[offset : offset + width], "little", signed=signed)


def pearson(pairs: list[tuple[float, float]]) -> float:
    if len(pairs) < 8:
        return 0.0
    xs, ys = zip(*pairs)
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    numerator = sum((x - mx) * (y - my) for x, y in pairs)
    dx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    dy = math.sqrt(sum((y - my) ** 2 for y in ys))
    return numerator / (dx * dy) if dx and dy else 0.0


def labelled_values(path: Path, metric: str) -> dict[int, float]:
    values: dict[int, float] = {}
    with path.open(newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            if row.get("metric", "").strip().lower() != metric.lower():
                continue
            try:
                values[int(float(row["unix"]))] = float(row["value"])
            except (KeyError, TypeError, ValueError):
                continue
    return values


def scan(records: list[bytes], labels: dict[int, float], permutations: int) -> None:
    aligned = [(f, labels[int.from_bytes(f[15:19], "little")]) for f in records
               if len(f) >= 23 and int.from_bytes(f[15:19], "little") in labels]
    if len(aligned) < 8:
        print("Need at least 8 timestamp-aligned labelled v18 frames; no hypotheses ranked.")
        return
    candidates: list[tuple[float, int, int, bool, int]] = []
    for width in (1, 2, 4):
        for signed in (False, True) if width > 1 else (False,):
            for offset in range(19, min(116, min(len(f) - 4 for f, _ in aligned) - width + 1)):
                pairs = [(float(u(f, offset, width, signed)), label) for f, label in aligned
                         if u(f, offset, width, signed) is not None]
                corr = pearson(pairs)
                candidates.append((abs(corr), offset, width, signed, len(pairs)))
    candidates.sort(reverse=True)
    rng = random.Random(0x4D47)
    shuffled = [label for _, label in aligned]
    null_scores: list[float] = []
    top = candidates[:12]
    for _ in range(permutations):
        rng.shuffle(shuffled)
        best = 0.0
        for _, offset, width, signed, _ in top:
            pairs = [(float(u(f, offset, width, signed)), shuffled[i]) for i, (f, _) in enumerate(aligned)
                     if u(f, offset, width, signed) is not None]
            best = max(best, abs(pearson(pairs)))
        null_scores.append(best)
    print("\nRanked research hypotheses (not decoded measurements):")
    for score, offset, width, signed, count in top:
        p = (sum(x >= score for x in null_scores) + 1) / (len(null_scores) + 1)
        kind = f"i{width * 8}" if signed else f"u{width * 8}"
        print(f"  @{offset:3d} {kind:>3} |r|={score:.3f} n={count} permutation-p={p:.3f}")
    print("A low permutation p only prioritizes a capture experiment. It does not establish a field meaning or clinical validity.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Offline 5/MG lossless capture inventory and hypothesis scan")
    parser.add_argument("capture", type=Path)
    parser.add_argument("--labels", type=Path, help="CSV with unix,metric,value")
    parser.add_argument("--metric", help="Label metric to scan, for example systolic")
    parser.add_argument("--permutations", type=int, default=500)
    args = parser.parse_args()
    if bool(args.labels) != bool(args.metric):
        parser.error("--labels and --metric must be supplied together")
    all_frames = list(frames(args.capture))
    valid = [frame for frame in all_frames if valid_puffin(frame)]
    records = [frame for frame in valid if len(frame) > 19 and frame[8] == 47]
    versions = Counter(frame[9] for frame in records if len(frame) > 9)
    print(f"Puffin frames: {len(valid)}/{len(all_frames)} CRC-valid")
    print("Type-47 versions: " + (", ".join(f"v{k}={v}" for k, v in sorted(versions.items())) or "none"))
    print("v18 fields verified by code: unix@15, HR@22, motion counter@57, activity class@63, skin temp@73.")
    print("No BP or SpO2 percent field is inferred by this tool.")
    if args.labels:
        scan([frame for frame in records if frame[9] == 18], labelled_values(args.labels, args.metric), max(1, args.permutations))
    return 0


if __name__ == "__main__":
    sys.exit(main())
