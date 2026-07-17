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
    assert not any(r["series"] == "occ_km" for r in rows)   # deferred (no occ_km_shares given)

    # Zero-range duration: single vehicle active_s/3600 = 1.0 hour
    # bin_equal_width detects zero range and returns single degenerate bin (1.0, 1.0)
    dur = [r for r in rows if r["series"] == "drt_tour_duration"]
    assert len(dur) == 1
    assert dur[0]["value"] == 1
    assert dur[0]["bin_lo"] == 1.0 and dur[0]["bin_hi"] == 1.0


def test_drt_tour_distance_rows_from_veh_km():
    veh_km = {"v0": 100.0, "v1": 300.0}
    rows = dist.extract(FIX, "MINI", recon=None, veh_km=veh_km)
    d = [r for r in rows if r["series"] == "drt_tour_distance"]
    assert d, "expected drt_tour_distance rows when veh_km is given"
    assert all(r["unit"] == "km" for r in d)
    assert sum(r["value"] for r in d) == 2


def test_occ_km_rows_from_occ_km_shares():
    occ_km_shares = {0: 0.4, 1: 0.6}
    rows = dist.extract(FIX, "MINI", recon=None, occ_km_shares=occ_km_shares)
    ok = {r["bin_lo"]: r["value"] for r in rows if r["series"] == "occ_km"}
    assert ok[0] == 0.4
    assert ok[1] == 0.6
    # bin_lo == bin_hi == level, like occ_time
    ok_rows = [r for r in rows if r["series"] == "occ_km"]
    for r in ok_rows:
        assert r["bin_lo"] == r["bin_hi"]
        assert r["unit"] == "share"


def test_deferred_note_both_absent(capsys):
    dist.extract(FIX, "MINI", recon=None)
    out = capsys.readouterr().out
    assert "drt_tour_distance" in out
    assert "occ_km" in out


def test_deferred_note_only_missing_one(capsys):
    dist.extract(FIX, "MINI", recon=None, veh_km={"v0": 100.0})
    out = capsys.readouterr().out
    assert "occ_km" in out
    assert "drt_tour_distance" not in out

    dist.extract(FIX, "MINI", recon=None, occ_km_shares={0: 1.0})
    out2 = capsys.readouterr().out
    assert "drt_tour_distance" in out2
    assert "occ_km" not in out2


def test_no_deferred_note_when_both_provided(capsys):
    dist.extract(FIX, "MINI", recon=None, veh_km={"v0": 100.0}, occ_km_shares={0: 1.0})
    out = capsys.readouterr().out
    assert "deferred" not in out


def test_occ_km_shares_empty_dict_not_deferred(capsys):
    # An empty-but-provided occ_km_shares (e.g. tot==0) must be treated as
    # "provided" (no rows emitted, but NOT listed as still-deferred).
    rows = dist.extract(FIX, "MINI", recon=None, veh_km={"v0": 100.0}, occ_km_shares={})
    assert not any(r["series"] == "occ_km" for r in rows)
    out = capsys.readouterr().out
    assert "deferred" not in out


def test_lmd_carrier_score_bins():
    rows = dist.extract(FIX, "MINI", recon=None)
    sc = [r for r in rows if r["series"] == "lmd_carrier_score"]
    assert sc, "expected lmd_carrier_score rows"
    assert sum(r["value"] for r in sc) == 3          # 3 carriers with a score
    assert min(r["bin_lo"] for r in sc) <= -500.0


def test_write_schema(tmp_path):
    rows = dist.extract(FIX, "MINI", recon=None)
    class M:
        run_id = "MINI"
    out = tmp_path / "kpi_distributions.csv"
    dist.write(rows, M, out)
    lines = out.read_text(encoding="utf-8").splitlines()
    assert lines[0] == "run_id;series;bin_lo;bin_hi;value;unit"
    assert all(len(l.split(";")) == 6 for l in lines[1:])
    assert lines[1].split(";")[0] == "MINI"
