# -*- coding: utf-8 -*-
"""Derive the progress-check grace window from a real MATSim batch log.

The heartbeat treats "newest log file advanced" as proof of progress. To set the
grace window we need the largest LEGITIMATE quiet period in a healthy run - the
jsprit routing phase is the quiet candidate. Anything above that margin is a stall.

Usage:  python measure_log_gaps.py <path-to-batch-log>
"""
import re
import sys
from datetime import datetime

# Measured 2026-07-31 against stepB_weekend_batch.log (10 complete runs).
# JVM unified-logging lines ("[0.004s][info][nmt] ...") carry no wall clock and
# are skipped rather than parsed.
TS_RE = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(?:INFO|WARN|ERROR|DEBUG)")
TS_FMT = "%Y-%m-%d %H:%M:%S"


def parse_gaps(lines):
    """Return [(timestamp_str, gap_seconds), ...] between consecutive timestamped lines."""
    gaps = []
    prev = None
    for line in lines:
        match = TS_RE.match(line)
        if not match:
            continue
        stamp = match.group(1)
        current = datetime.strptime(stamp, TS_FMT)
        if prev is not None:
            delta = int((current - prev).total_seconds())
            if delta >= 0:  # guard against clock steps / interleaved streams
                gaps.append((stamp, delta))
        prev = current
    return gaps


def summarise(gaps):
    """Aggregate gap statistics. p999 excludes single freak values from the decision."""
    if not gaps:
        return {"count": 0, "max_gap_s": 0, "p999_gap_s": 0, "max_gap_at": None}
    values = sorted(g[1] for g in gaps)
    worst = max(gaps, key=lambda g: g[1])
    idx = min(len(values) - 1, int(len(values) * 0.999))
    return {
        "count": len(gaps),
        "max_gap_s": worst[1],
        "p999_gap_s": values[idx],
        "max_gap_at": worst[0],
    }


def main(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        stats = summarise(parse_gaps(handle))
    print("timestamped lines compared : %d" % stats["count"])
    print("largest quiet gap          : %d s (%.1f min) at %s"
          % (stats["max_gap_s"], stats["max_gap_s"] / 60.0, stats["max_gap_at"]))
    print("p99.9 quiet gap            : %d s (%.1f min)"
          % (stats["p999_gap_s"], stats["p999_gap_s"] / 60.0))
    recommended = max(3600, int(stats["max_gap_s"] * 2))
    print("RECOMMENDED progress grace : %d s (%.0f min) = max(60 min, 2x observed max)"
          % (recommended, recommended / 60.0))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: python measure_log_gaps.py <path-to-batch-log>")
        sys.exit(2)
    main(sys.argv[1])
