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
    """Return (gaps, dropped).

    gaps: [(timestamp_str, gap_seconds), ...] between consecutive
    forward-moving timestamped lines.
    dropped: count of timestamped lines whose timestamp did not move forward
    relative to the last forward-moving timestamp (clock steps / interleaved
    streams). Such a line is discarded WITHOUT moving the reference timestamp
    backward - if it were allowed to become the new reference, the gap
    measured for the next legitimate line would be inflated or deflated by an
    out-of-order artifact rather than reflecting a real quiet period.
    """
    gaps = []
    dropped = 0
    prev = None
    for line in lines:
        match = TS_RE.match(line)
        if not match:
            continue
        stamp = match.group(1)
        current = datetime.strptime(stamp, TS_FMT)
        if prev is None:
            prev = current
            continue
        delta = int((current - prev).total_seconds())
        if delta >= 0:
            gaps.append((stamp, delta))
            prev = current
        else:  # guard against clock steps / interleaved streams
            dropped += 1
    return gaps, dropped


def summarise(gaps, dropped=0):
    """Aggregate gap statistics. p999 excludes single freak values from the decision.

    dropped: count of out-of-order timestamped lines parse_gaps discarded;
    surfaced here so an operator can see whether the guard fired on a given
    log, rather than having that evidence silently discarded.
    """
    if not gaps:
        return {"count": 0, "max_gap_s": 0, "p999_gap_s": 0, "max_gap_at": None, "dropped": dropped}
    values = sorted(g[1] for g in gaps)
    worst = max(gaps, key=lambda g: g[1])
    idx = min(len(values) - 1, int(len(values) * 0.999))
    return {
        "count": len(gaps),
        "max_gap_s": worst[1],
        "p999_gap_s": values[idx],
        "max_gap_at": worst[0],
        "dropped": dropped,
    }


def main(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        gaps, dropped = parse_gaps(handle)
    stats = summarise(gaps, dropped)
    print("timestamped lines compared : %d" % stats["count"])
    print("largest quiet gap          : %d s (%.1f min) at %s"
          % (stats["max_gap_s"], stats["max_gap_s"] / 60.0, stats["max_gap_at"]))
    print("p99.9 quiet gap            : %d s (%.1f min)"
          % (stats["p999_gap_s"], stats["p999_gap_s"] / 60.0))
    print("out-of-order lines dropped : %d" % stats["dropped"])
    recommended = max(600, int(stats["max_gap_s"] * 20))
    print("RECOMMENDED progress grace : %d s (%.0f min) = max(10 min, 20x observed max)"
          % (recommended, recommended / 60.0))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: python measure_log_gaps.py <path-to-batch-log>")
        sys.exit(2)
    main(sys.argv[1])
