# -*- coding: utf-8 -*-
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from extract_drt import extract

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def _by_name(rows):
    return {r["kpi_name"]: r for r in rows}


def test_extract_headline_kpis():
    rows = extract(FIX, "DRT_TEST")
    k = _by_name(rows)
    assert k["drt_rides"]["value"] == 9171
    assert k["drt_rejections"]["value"] == 24
    # integer-based rate, NOT the rounded rejectionRate column
    assert k["drt_rejection_rate"]["value"] == pytest.approx(24 / (9171 + 24))
    assert k["wait_median"]["value"] == 753.0
    assert k["wait_below_15min"]["value"] == pytest.approx(0.6231)
    assert k["detour_factor"]["value"] == pytest.approx(10967.72 / 7595.93)
    assert k["drt_vehicles"]["value"] == 120
    assert k["drt_vehicle_km"]["value"] == pytest.approx(48885.4898)
    assert k["drt_empty_ratio"]["value"] == pytest.approx(0.12)
    assert k["pooling_rate"]["value"] == pytest.approx(0.98092, abs=1e-4)
    assert k["modal_share_drt"]["value"] == pytest.approx(0.0607166, abs=1e-6)
    # feeder: 1 of 4 drt trips contains pt; 1 pt main-mode trip is drt-fed
    assert k["drt_feeder_trips"]["value"] == 1
    assert k["drt_feeder_share"]["value"] == pytest.approx(0.25)
    assert k["rail_trips_drt_fed_share"]["value"] == pytest.approx(1.0)
    # every row well-formed
    for r in rows:
        assert set(r) == {"kpi_group", "kpi_name", "value", "unit", "source"}


def test_no_events_no_service_rows():
    rows = extract(FIX, "DRT_TEST")
    assert "service_ratio_shift" not in _by_name(rows)


def test_recon_stub_used_without_touching_events_file():
    stub_recon = {
        "fleet": {
            "ratio_active": 0.77,
            "ratio_shift": 0.55,
            "util_by_time": 0.42,
            "sum_shift_s": 3600.0 * 10,
            "seg_time": {0: 100.0, 1: 200.0, 2: 50.0},
            "util_by_trips": 0.25,
            "tour_s": 360000.0,
            "drive_s": 200000.0,
            "waiting_s": 100000.0,
            "stop_s": 60000.0,
        }
    }
    bogus_events_path = FIX / "does_not_exist.output_events.xml.gz"
    assert not bogus_events_path.exists()

    rows = extract(FIX, "DRT_TEST", fleet_file=None,
                    drt_events_cache=bogus_events_path, recon=stub_recon)
    k = _by_name(rows)

    assert k["service_ratio_active"]["value"] == pytest.approx(0.77)
    assert k["service_ratio_shift"]["value"] == pytest.approx(0.55)
    assert k["fleet_utilisation_by_time"]["value"] == pytest.approx(0.42)
    assert k["fleet_shift_hours"]["value"] == pytest.approx(10.0)
    seg_t = stub_recon["fleet"]["seg_time"]
    tot_t = sum(seg_t.values())
    expected_mean_pax = sum(lv * s for lv, s in seg_t.items()) / tot_t
    assert k["mean_pax_aboard"]["value"] == pytest.approx(expected_mean_pax)
    assert k["fleet_utilisation_by_trips"]["value"] == pytest.approx(0.25)
    assert k["drt_tour_hours_total"]["value"] == pytest.approx(100.0)
    assert k["drt_drive_hours_total"]["value"] == pytest.approx(55.5556, abs=1e-4)
    assert k["drt_wait_hours_total"]["value"] == pytest.approx(27.7778, abs=1e-4)
    assert k["drt_service_hours_total"]["value"] == pytest.approx(16.6667, abs=1e-4)


def test_customer_stats_tile_kpis_without_recon():
    # Values hard-coded from tests/fixtures/drtrun/DRT_TEST.drt_customer_stats_drt.csv
    # last (only) row: rides_pax=9171, distance_m_mean=10967.72
    rows = extract(FIX, "DRT_TEST")
    k = _by_name(rows)
    assert k["drt_passengers"]["value"] == 9171
    assert k["drt_trip_distance_mean"]["value"] == pytest.approx(10.96772)
