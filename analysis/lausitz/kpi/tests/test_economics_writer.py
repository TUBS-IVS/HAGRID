# -*- coding: utf-8 -*-
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from common import row
from economics import extract as econ
from kpi_writer import write_long, write_wide, COLUMNS
from run_meta import parse_legacy_dir_name


def _base_rows():
    return [
        row("system", "fleet_shift_hours", 2880.0, "h", "events/fleet file"),
        row("passenger", "drt_rides", 9171, "trips", "drt_customer_stats"),
        row("economic", "freight_total_costs", 13605.0, "EUR", "TimeDistance_perCarrier"),
        row("freight", "parcels_handled", 6381, "parcels", "Load_perVehicle"),
    ]


def test_placeholder_cost_model():
    k = {r["kpi_name"]: r for r in econ(_base_rows())}
    # 2880 shift hours x (20+5) EUR/h
    assert k["drt_cost_bottom_up_placeholder"]["value"] == pytest.approx(2880 * 25.0)
    assert k["drt_cost_per_ride_placeholder"]["value"] == pytest.approx(2880 * 25.0 / 9171)
    assert k["drt_labour_share_placeholder"]["value"] == pytest.approx(20.0 / 25.0)
    assert k["freight_cost_per_parcel"]["value"] == pytest.approx(13605.0 / 6381)
    assert all(r["kpi_group"] == "economic" for r in econ(_base_rows()))


def test_fleet_size_fallback_when_no_events():
    rows = [r for r in _base_rows() if r["kpi_name"] != "fleet_shift_hours"]
    k = {r["kpi_name"]: r for r in econ(rows, fleet_size=120)}
    # fallback: 120 vehicles x 24 h shift
    assert k["drt_cost_bottom_up_placeholder"]["value"] == pytest.approx(120 * 24 * 25.0)


def test_write_long_and_wide(tmp_path):
    meta = parse_legacy_dir_name("DRT_BASELINE_13052025_married120_iter150_jsprit100")
    rows = _base_rows()
    long_f, wide_f = tmp_path / "kpis_long.csv", tmp_path / "kpis_wide.csv"
    write_long(rows, meta, long_f)
    write_wide(rows, meta, wide_f)

    lines = long_f.read_text(encoding="utf-8").splitlines()
    assert lines[0] == ";".join(COLUMNS)
    assert lines[1].startswith(
        "DRT_BASELINE_13052025_married120;lausitz_hoyerswerda;DRT_BASELINE;conventional;")
    assert any(";drt_rides;9171;trips;" in l for l in lines)

    wlines = wide_f.read_text(encoding="utf-8").splitlines()
    assert wlines[0].startswith("run_id;study_area;scenario;operation_mode;")
    assert "passenger.drt_rides" in wlines[0]
    assert len(wlines) == 2
