# -*- coding: utf-8 -*-
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import extract_iterations as ei

FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


def _vals(rows, series):
    return [(r["iteration"], r["value"]) for r in rows if r["series"] == series]


def test_drt_convergence_all_rows():
    rows = ei.extract(FIX, "MINI")
    assert _vals(rows, "drt_rides") == [(0, 100), (1, 200), (2, 220)]
    assert dict(_vals(rows, "wait_p95"))[2] == 1200.0


def test_modal_shares_per_mode():
    rows = ei.extract(FIX, "MINI")
    assert dict(_vals(rows, "modal_share_drt"))[1] == 0.011


def test_carrier_scores_present():
    rows = ei.extract(FIX, "MINI")
    assert dict(_vals(rows, "carrier_score_best"))[2] == -55.0


def test_carrier_scores_absent_graceful(tmp_path):
    # copy only the DRT csv, omit carrier_scores.txt -> no carrier_score_* rows, no crash
    import shutil
    shutil.copy(FIX / "MINI.drt_customer_stats_drt.csv", tmp_path / "MINI.drt_customer_stats_drt.csv")
    shutil.copy(FIX / "MINI.modestats.csv", tmp_path / "MINI.modestats.csv")
    rows = ei.extract(tmp_path, "MINI")
    assert not any(r["series"].startswith("carrier_score_") for r in rows)
    assert any(r["series"] == "drt_rides" for r in rows)
