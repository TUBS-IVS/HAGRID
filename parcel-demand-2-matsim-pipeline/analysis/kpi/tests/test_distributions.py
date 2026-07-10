# -*- coding: utf-8 -*-
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import distributions as dist

FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


def test_wait_bins_60s():
    rows = dist.extract(FIX, "MINI", recon=None)
    w = {(r["bin_lo"], r["bin_hi"]): r["value"] for r in rows if r["series"] == "drt_wait"}
    assert w[(0, 60)] == 2
    assert w[(60, 120)] == 1
    assert w[(120, 180)] == 1


def test_lmd_distance_10km_bins():
    rows = dist.extract(FIX, "MINI", recon=None)
    d = [r for r in rows if r["series"] == "lmd_tour_distance"]
    assert d, "expected lmd_tour_distance rows from TimeDistance_perVehicle.tsv"
    # 4 vehicles: 60,40,20,200 km -> bins [20,30),[40,50),[60,70),[200,210)
    binned = {(r["bin_lo"], r["bin_hi"]): r["value"] for r in d}
    assert binned.get((60, 70)) == 1
    assert binned.get((200, 210)) == 1


def test_occupancy_rows_from_recon():
    recon = {"per_veh": {"v0": {"active_s": 3600.0}},
             "fleet": {"seg_time": {0: 100.0, 1: 300.0}, "seg_count": {0: 2, 1: 6}}}
    rows = dist.extract(FIX, "MINI", recon=recon)
    ot = {r["bin_lo"]: r["value"] for r in rows if r["series"] == "occ_time"}
    assert abs(ot[1] - 0.75) < 1e-9        # 300/(100+300)
    assert not any(r["series"] == "occ_km" for r in rows)   # deferred
