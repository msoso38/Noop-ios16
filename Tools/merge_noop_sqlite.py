#!/usr/bin/env python3
"""Merge two NOOP SQLite DBs (release + debug) into one.

Strategy:
- Base = dest (usually debug)
- Sample / session tables: INSERT OR IGNORE from source (union history)
- dailyMetric: field-wise COALESCE preferring non-null; when both set, prefer higher sleep/
  recovery when present
- sleepSession: INSERT OR IGNORE; if conflict keep longer asleep window

Usage:
  python merge_noop_sqlite.py release.db debug.db merged.db
"""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path


SAMPLE_TABLES = [
    "hrSample",
    "ppgHrSample",
    "rrInterval",
    "event",
    "battery",
    "spo2Sample",
    "skinTempSample",
    "stepSample",
    "sleepStateSample",
    "respSample",
    "gravitySample",
    "workout",
    "dismissedWorkout",
    "dismissedSleep",
    "appleDaily",
    "liveSession",
    "journal",
]


def table_exists(con: sqlite3.Connection, name: str) -> bool:
    row = con.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        (name,),
    ).fetchone()
    return row is not None


def copy_or_ignore(con: sqlite3.Connection, table: str) -> int:
    if not table_exists(con, table):
        return 0
    before = con.total_changes
    con.execute(f"INSERT OR IGNORE INTO main.{table} SELECT * FROM src.{table}")
    return con.total_changes - before


def merge_daily_metric(con: sqlite3.Connection) -> int:
    if not table_exists(con, "dailyMetric"):
        return 0
    cols = [r[1] for r in con.execute("PRAGMA table_info(main.dailyMetric)").fetchall()]
    if "deviceId" not in cols or "day" not in cols:
        return copy_or_ignore(con, "dailyMetric")
    # Ensure all src rows exist
    con.execute("INSERT OR IGNORE INTO main.dailyMetric SELECT * FROM src.dailyMetric")
    # Fill nulls on main from src
    value_cols = [c for c in cols if c not in ("deviceId", "day")]
    n = 0
    for c in value_cols:
        cur = con.execute(
            f"""
            UPDATE main.dailyMetric AS m
            SET {c} = (
              SELECT s.{c} FROM src.dailyMetric AS s
              WHERE s.deviceId = m.deviceId AND s.day = m.day
            )
            WHERE m.{c} IS NULL
              AND EXISTS (
                SELECT 1 FROM src.dailyMetric AS s
                WHERE s.deviceId = m.deviceId AND s.day = m.day AND s.{c} IS NOT NULL
              )
            """
        )
        n += cur.rowcount
    return n


def merge_sleep_session(con: sqlite3.Connection) -> int:
    if not table_exists(con, "sleepSession"):
        return 0
    con.execute("INSERT OR IGNORE INTO main.sleepSession SELECT * FROM src.sleepSession")
    # Prefer longer sessions when both exist
    cur = con.execute(
        """
        UPDATE main.sleepSession AS m
        SET endTs = (
          SELECT s.endTs FROM src.sleepSession AS s
          WHERE s.deviceId = m.deviceId AND s.startTs = m.startTs
        ),
        stagesJSON = COALESCE(m.stagesJSON, (
          SELECT s.stagesJSON FROM src.sleepSession AS s
          WHERE s.deviceId = m.deviceId AND s.startTs = m.startTs
        ))
        WHERE EXISTS (
          SELECT 1 FROM src.sleepSession AS s
          WHERE s.deviceId = m.deviceId AND s.startTs = m.startTs
            AND (s.endTs - s.startTs) > (m.endTs - m.startTs)
        )
        """
    )
    return cur.rowcount


def merge(src_path: Path, dest_path: Path, out_path: Path) -> None:
    out_path.write_bytes(dest_path.read_bytes())
    con = sqlite3.connect(str(out_path))
    try:
        con.execute("PRAGMA foreign_keys=OFF")
        con.execute(f"ATTACH DATABASE ? AS src", (str(src_path),))
        stats = {}
        for t in SAMPLE_TABLES:
            stats[t] = copy_or_ignore(con, t)
        stats["dailyMetric"] = merge_daily_metric(con)
        stats["sleepSession"] = merge_sleep_session(con)
        for t in ("device", "pairedDevice", "dayOwnership"):
            stats[t] = copy_or_ignore(con, t)
        con.commit()
        print("merged →", out_path)
        for k, v in sorted(stats.items()):
            if v:
                print(f"  {k}: +{v}")
    finally:
        con.close()


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    src, dest, out = map(Path, sys.argv[1:4])
    if not src.exists() or not dest.exists():
        print("missing input db", file=sys.stderr)
        return 1
    merge(src, dest, out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
