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

# Deliberately out-of-order: the 3rd timestamped line (21:36:55) arrives BEFORE
# the previous one (21:37:00) - e.g. an interleaved writer or a clock step.
# It must be dropped without rolling the reference timestamp back to 21:36:55;
# otherwise the following gap would read 65s (from the rolled-back 21:36:55)
# instead of the correct 60s (from the last forward-moving timestamp, 21:37:00).
OUT_OF_ORDER = [
    "2026-07-24 21:36:50 INFO  QSim:552 - a",
    "2026-07-24 21:37:00 INFO  QSim:552 - b",
    "2026-07-24 21:36:55 INFO  QSim:552 - late-arriving interleaved line",
    "2026-07-24 21:38:00 INFO  QSim:552 - c",
]

# Two out-of-order lines in a row, to check the dropped count actually
# accumulates rather than saturating at 1 or being overwritten.
MULTI_OUT_OF_ORDER = [
    "2026-07-24 21:36:50 INFO  QSim:552 - a",
    "2026-07-24 21:37:00 INFO  QSim:552 - b",
    "2026-07-24 21:36:40 INFO  QSim:552 - late c",
    "2026-07-24 21:36:45 INFO  QSim:552 - late d",
    "2026-07-24 21:38:00 INFO  QSim:552 - e",
]


def test_parse_gaps_skips_untimestamped_lines():
    gaps, dropped = m.parse_gaps(FIXTURE)
    # 4 timestamped lines -> 3 gaps. The two JVM lines and the batch header are skipped.
    assert [g[1] for g in gaps] == [1, 3600, 7]
    assert dropped == 0


def test_summarise_reports_max_and_location():
    gaps, dropped = m.parse_gaps(FIXTURE)
    s = m.summarise(gaps, dropped)
    assert s["count"] == 3
    assert s["max_gap_s"] == 3600
    assert s["max_gap_at"] == "2026-07-24 22:36:51"
    # With only 3 gaps, idx = min(2, int(3*0.999)) = 2 collapses to the same
    # index as the max, so this assertion alone cannot distinguish a correct
    # percentile calculation from a broken one - see
    # test_summarise_p999_differs_from_max_on_a_larger_sample below for that.
    assert s["p999_gap_s"] == 3600
    assert s["dropped"] == 0


def test_summarise_p999_differs_from_max_on_a_larger_sample():
    # The p999 index formula (idx = min(len-1, int(len*0.999))) only diverges
    # from "last element" (i.e. the max) once len(values) > 1000: at len=1000,
    # int(1000*0.999) == 999 == len-1 still; the first divergence is at
    # len=1001. A 15-20 element fixture is therefore incapable of telling a
    # correct percentile index apart from a broken one - idx collapses to
    # len-1 for any len <= 1000 regardless of the values involved, exactly the
    # blind spot this test exists to close. 2000 gaps (1998 identical small
    # values + two distinct large outliers) forces p999 to land on the
    # second-largest value, proving the index is doing real percentile work.
    gaps = [("2026-07-24 00:00:00", 10) for _ in range(1998)]
    gaps.append(("2026-07-24 00:00:01", 500))
    gaps.append(("2026-07-24 00:00:02", 1000))
    s = m.summarise(gaps)
    assert s["count"] == 2000
    assert s["max_gap_s"] == 1000
    assert s["max_gap_at"] == "2026-07-24 00:00:02"
    assert s["p999_gap_s"] == 500


def test_parse_gaps_drops_out_of_order_without_rolling_back_reference():
    gaps, dropped = m.parse_gaps(OUT_OF_ORDER)
    assert gaps == [
        ("2026-07-24 21:37:00", 10),
        ("2026-07-24 21:38:00", 60),
    ]
    assert dropped == 1


def test_parse_gaps_counts_multiple_dropped_lines():
    gaps, dropped = m.parse_gaps(MULTI_OUT_OF_ORDER)
    assert dropped == 2
    assert gaps == [
        ("2026-07-24 21:37:00", 10),
        ("2026-07-24 21:38:00", 60),
    ]


def test_parse_gaps_empty_input():
    assert m.parse_gaps([]) == ([], 0)
    assert m.summarise([]) == {
        "count": 0,
        "max_gap_s": 0,
        "p999_gap_s": 0,
        "max_gap_at": None,
        "dropped": 0,
    }
