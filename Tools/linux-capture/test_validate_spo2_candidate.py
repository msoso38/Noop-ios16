"""Tests for validate_spo2_candidate — multi-device @82 SpO₂ candidate validation.

Run: python3 -m unittest test_validate_spo2_candidate -v
Stdlib only; synthetic planted captures — no personal health data.
"""

from __future__ import annotations

import json
import os
import struct
import tempfile
import unittest
from datetime import datetime, timezone

import validate_spo2_candidate as vs
from test_whoop_activity import make_v18


def _utc(y, m, d, hh=0, mm=0, ss=0) -> int:
    return int(datetime(y, m, d, hh, mm, ss, tzinfo=timezone.utc).timestamp())


def _plant_night(
    t0: int,
    t1: int,
    spo2: int,
    *,
    asleep: bool = True,
    neighbor_offset: int | None = None,
    neighbor_value: int = 0,
    step_s: int = 60,
) -> list[dict]:
    """Plant v18 records for one night with @82 = spo2 while asleep (default 1/min)."""
    out = []
    sleep_state = 2 if asleep else 0
    for u in range(t0, t1, step_s):
        f = bytearray(
            make_v18(
                unix=u,
                sleep_state=sleep_state,
                aux_byte_82=spo2 if asleep else 0,
                length=124,
            )
        )
        if neighbor_offset is not None and asleep:
            f[neighbor_offset] = neighbor_value & 0xFF
        out.append({"hex": bytes(f).hex(), "dir": "rx"})
    return out


def _write_export(folder: str, nights: list[tuple[str, float, int, int]]) -> str:
    """nights: (cycle_start_str, spo2_export, sleep_unix, wake_unix)"""
    path = os.path.join(folder, "physiological_cycles.csv")
    lines = [
        "Cycle start time,Cycle end time,Blood oxygen %,Sleep onset,Wake onset\n"
    ]
    for start, spo2, t0, t1 in nights:
        s0 = datetime.fromtimestamp(t0, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        s1 = datetime.fromtimestamp(t1, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        lines.append(f"{start},,{spo2:.2f},{s0},{s1}\n")
    with open(path, "w") as f:
        f.writelines(lines)
    return folder


class HeaderAndTsTests(unittest.TestCase):
    def test_english_blood_oxygen_header(self):
        self.assertEqual(vs._norm_header("Blood oxygen %"), "blood_oxygen_pct")
        self.assertEqual(vs._norm_header("Cycle start time"), "cycle_start_time")

    def test_parse_export_ts(self):
        self.assertEqual(
            vs._parse_export_ts("2026-07-15 23:25:58"),
            _utc(2026, 7, 15, 23, 25, 58),
        )


class PlantedValidationTests(unittest.TestCase):
    def test_recovers_perfect_correlation_across_nights(self):
        # 6 nights with real spread; @82 equals round(export) while asleep.
        base = _utc(2026, 6, 1, 0, 0, 0)
        nights_meta = []
        frames = []
        exports = []
        for i, spo2 in enumerate([95, 96, 97, 98, 94, 99]):
            t0 = base + i * 86400 + 23 * 3600
            t1 = t0 + 7 * 3600
            frames.extend(_plant_night(t0, t1, spo2))
            start = datetime.fromtimestamp(t0, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
            exports.append((start, float(spo2), t0, t1))
            nights_meta.append(spo2)

        with tempfile.TemporaryDirectory() as td:
            cap = os.path.join(td, "capture.json")
            with open(cap, "w") as f:
                json.dump(frames, f)
            exp = _write_export(td, exports)
            res = vs.validate_device(cap, exp, device="planted-a")

        self.assertEqual(res["paired_nights"], 6)
        self.assertIsNotNone(res["r"])
        self.assertGreaterEqual(res["r"], 0.99)
        self.assertLessEqual(res["mae"], 0.01)
        self.assertEqual(res["best_specificity_offset"], 82)
        self.assertTrue(res["checklist"]["pass"])

    def test_incomplete_nights_without_export_spo2_are_skipped(self):
        t0 = _utc(2026, 6, 10, 23, 0, 0)
        t1 = t0 + 6 * 3600
        frames = _plant_night(t0, t1, 97)
        with tempfile.TemporaryDirectory() as td:
            cap = os.path.join(td, "capture.json")
            with open(cap, "w") as f:
                json.dump(frames, f)
            # Export row with empty SpO₂ — must not pair.
            path = os.path.join(td, "physiological_cycles.csv")
            with open(path, "w") as f:
                f.write(
                    "Cycle start time,Blood oxygen %,Sleep onset,Wake onset\n"
                    "2026-06-10 23:00:00,,"
                    f"{datetime.fromtimestamp(t0, tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S')},"
                    f"{datetime.fromtimestamp(t1, tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S')}\n"
                )
            res = vs.validate_device(cap, td, device="empty")
        self.assertEqual(res["export_nights_with_spo2"], 0)
        self.assertEqual(res["paired_nights"], 0)
        self.assertFalse(res["checklist"]["pass"])

    def test_awake_samples_do_not_contribute(self):
        t0 = _utc(2026, 6, 11, 23, 0, 0)
        t1 = t0 + 6 * 3600
        # All wake, @82=97 — should not pair when require_asleep (default).
        frames = _plant_night(t0, t1, 97, asleep=False)
        # Force aux byte even while wake for the test plant.
        for rec in frames:
            f = bytearray(bytes.fromhex(rec["hex"]))
            f[82] = 97
            rec["hex"] = bytes(f).hex()
        with tempfile.TemporaryDirectory() as td:
            cap = os.path.join(td, "capture.json")
            with open(cap, "w") as f:
                json.dump(frames, f)
            start = datetime.fromtimestamp(t0, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
            exp = _write_export(td, [(start, 97.0, t0, t1)])
            res = vs.validate_device(cap, exp, device="wake-only")
        self.assertEqual(res["paired_nights"], 0)

    def test_offset_specificity_prefers_true_offset(self):
        # Plant signal at @82; put a constant 98 at @80 that should not track spread.
        base = _utc(2026, 7, 1, 0, 0, 0)
        frames = []
        exports = []
        for i, spo2 in enumerate([92, 94, 96, 98, 95, 97]):
            t0 = base + i * 86400 + 22 * 3600
            t1 = t0 + 8 * 3600
            frames.extend(
                _plant_night(t0, t1, spo2, neighbor_offset=80, neighbor_value=98)
            )
            start = datetime.fromtimestamp(t0, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
            exports.append((start, float(spo2), t0, t1))
        with tempfile.TemporaryDirectory() as td:
            cap = os.path.join(td, "capture.json")
            with open(cap, "w") as f:
                json.dump(frames, f)
            exp = _write_export(td, exports)
            res = vs.validate_device(cap, exp, device="spec")
        self.assertEqual(res["best_specificity_offset"], 82)

    def test_postable_summary_has_no_raw_values(self):
        # Perfect single-device result → postable block must not contain SpO₂ numbers from nights.
        base = _utc(2026, 8, 1, 0, 0, 0)
        frames, exports = [], []
        for i, spo2 in enumerate([95, 96, 97, 98, 94, 99]):
            t0 = base + i * 86400 + 23 * 3600
            t1 = t0 + 7 * 3600
            frames.extend(_plant_night(t0, t1, spo2))
            start = datetime.fromtimestamp(t0, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
            exports.append((start, float(spo2), t0, t1))
        with tempfile.TemporaryDirectory() as td:
            cap = os.path.join(td, "capture.json")
            with open(cap, "w") as f:
                json.dump(frames, f)
            exp = _write_export(td, exports)
            res = vs.validate_device(cap, exp, device="post")
        blob = vs.format_postable([res])
        self.assertIn("spo2_candidate_82 multi-device validation", blob)
        self.assertIn("checklist_pass", blob)
        # Nightly export values must not appear as raw 95.00-style lines.
        self.assertNotIn("95.00", blob)
        self.assertNotIn("cycle_start", blob.lower())


class MultiDeviceBatchTests(unittest.TestCase):
    def test_batch_all_pass(self):
        def make_device(td, label, spo2s):
            base = _utc(2026, 5, 1, 0, 0, 0)
            frames, exports = [], []
            for i, spo2 in enumerate(spo2s):
                t0 = base + i * 86400 + 23 * 3600
                t1 = t0 + 7 * 3600
                frames.extend(_plant_night(t0, t1, spo2))
                start = datetime.fromtimestamp(t0, tz=timezone.utc).strftime(
                    "%Y-%m-%d %H:%M:%S"
                )
                exports.append((start, float(spo2), t0, t1))
            cap = os.path.join(td, f"{label}.json")
            with open(cap, "w") as f:
                json.dump(frames, f)
            exp_dir = os.path.join(td, label)
            os.makedirs(exp_dir)
            _write_export(exp_dir, exports)
            return {"device": label, "capture": cap, "export": exp_dir}

        with tempfile.TemporaryDirectory() as td:
            a = make_device(td, "strap-a", [95, 96, 97, 98, 94, 99])
            b = make_device(td, "strap-b", [93, 94, 95, 96, 97, 98])
            batch = os.path.join(td, "devices.json")
            with open(batch, "w") as f:
                json.dump([a, b], f)
            rc = vs.main(["--batch", batch, "--postable"])
            self.assertEqual(rc, 0)


if __name__ == "__main__":
    unittest.main()
