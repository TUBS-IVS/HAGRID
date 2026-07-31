# -*- coding: utf-8 -*-
"""Fixture test for the batch-log gap parser (pytest)."""
import measure_log_gaps as m

# Real format, measured 2026-07-31. Includes a JVM unified-logging line with no
# wall clock (must be skipped) and a deliberate 3600 s quiet gap.
FIXTURE = [
    "=== STEP B weekend batch start 24.07.2026 13:54:34,70 ===",
    "[0.004s][info][nmt] NMT initialized: summary",
    "2026-07-24 21:36:50 INFO  QSim:552 - SIMULATION AT 13:00:00",
    "2026-07-24 21:36:51 INFO  AbstractQNetsimEngine:356 - SIMULATION AT 14:00:00",
    "[12.5s][info][gc] GC(3) Pause Young",
    "2026-07-24 22:36:51 INFO  Router:755 - jsprit done",
    "2026-07-24 22:36:58 WARN  DashboardGenerator:91 - placeholder",
]


def test_parse_gaps_skips_untimestamped_lines():
    gaps = m.parse_gaps(FIXTURE)
    # 4 timestamped lines -> 3 gaps. The two JVM lines and the batch header are skipped.
    assert [g[1] for g in gaps] == [1, 3600, 7]


def test_summarise_reports_max_and_location():
    s = m.summarise(m.parse_gaps(FIXTURE))
    assert s["count"] == 3
    assert s["max_gap_s"] == 3600
    assert s["max_gap_at"] == "2026-07-24 22:36:51"


def test_parse_gaps_empty_input():
    assert m.parse_gaps([]) == []
    assert m.summarise([]) == {"count": 0, "max_gap_s": 0, "p999_gap_s": 0, "max_gap_at": None}
