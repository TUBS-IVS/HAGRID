# -*- coding: utf-8 -*-
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import extract_drt
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


def _stub_fleet(**overrides):
    """A recon["fleet"] shaped like drt_service_time.reconstruct returns it when
    the DVRP fleet file WAS found (capacity + the shift-denominated keys present)."""
    fleet = {
        "capacity": 10,
        "fleet_file_known": True,
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
    fleet.update(overrides)
    return {"fleet": fleet}


def test_recon_stub_used_without_touching_events_file():
    stub_recon = _stub_fleet()

    rows = extract(FIX, "DRT_TEST", fleet_file=None, recon=stub_recon)
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


def test_capacity_kpi_is_emitted_for_the_map_layer():
    """maps._read_cap reads exactly this KPI; without it the occupancy colouring
    silently falls back to a hardcoded 8 seats."""
    k = _by_name(extract(FIX, "DRT_TEST", recon=_stub_fleet(capacity=10)))
    assert k["drt_vehicle_capacity"]["value"] == 10
    assert k["drt_vehicle_capacity"]["kpi_group"] == "system"


def test_no_fleet_file_omits_denominated_kpis_and_flags_it():
    """The regression guard: an unlocatable fleet file must DROP the
    capacity/shift KPIs, not compute them against a guessed 8 seats or a
    sim-horizon stand-in for the shift window."""
    recon = _stub_fleet(capacity=None, fleet_file_known=False)
    for key in ("util_by_time", "util_by_trips", "ratio_shift", "sum_shift_s"):
        recon["fleet"].pop(key)

    k = _by_name(extract(FIX, "DRT_TEST", recon=recon))

    for name in ("fleet_utilisation_by_time", "fleet_utilisation_by_trips",
                 "service_ratio_shift", "fleet_shift_hours", "drt_vehicle_capacity"):
        assert name not in k, name
    assert k["fleet_file_missing"]["kpi_group"] == "meta"
    # Everything that does NOT need the fleet file must still be there.
    assert k["service_ratio_active"]["value"] == pytest.approx(0.77)
    assert "mean_pax_aboard" in k


def test_customer_stats_tile_kpis_without_recon():
    # Values hard-coded from tests/fixtures/drtrun/DRT_TEST.drt_customer_stats_drt.csv
    # last (only) row: rides_pax=9171, distance_m_mean=10967.72
    rows = extract(FIX, "DRT_TEST")
    k = _by_name(rows)
    assert k["drt_passengers"]["value"] == 9171
    assert k["drt_trip_distance_mean"]["value"] == pytest.approx(10.96772)


# --- 1d paper-readiness fixwave (review C1/I2/I3): marker survives --no-events,
# corrected *_pax recipe, set relocation -----------------------------------------

def test_marker_present_with_modular_true_and_no_events():
    """Review C1: the marker used to live only inside the `recon is not None`
    branch, so a run with NO events cache at all (recon=None, no fleet_file)
    published every contaminated KPI with no marker. `modular=True` alone must
    now be enough."""
    rows = extract(FIX, "DRT_TEST", modular=True)
    k = _by_name(rows)
    assert extract_drt.MODULAR_CONTAMINATION_KPI in k
    assert k[extract_drt.MODULAR_CONTAMINATION_KPI]["kpi_group"] == "meta"
    assert "modular_secondary_contaminated" in k


def test_marker_absent_on_a_baseline_run():
    """`modular=False` (the default, and what a non-1d run's caller passes) must
    keep the baseline fixture exactly as marker-free as before this change."""
    rows = extract(FIX, "DRT_TEST", modular=False)
    k = _by_name(rows)
    assert extract_drt.MODULAR_CONTAMINATION_KPI not in k
    assert "modular_secondary_contaminated" not in k


def test_marker_recipe_uses_rescale_not_flat_subtraction():
    """Review I2: the marker's source text must name the per-KPI correction verb,
    not the old flat 'subtract drt_freight_hours_total to compare' recipe that
    was wrong for 3 of the 4 window KPIs."""
    rows = extract(FIX, "DRT_TEST", modular=True)
    src = _by_name(rows)[extract_drt.MODULAR_CONTAMINATION_KPI]["source"]
    assert "rescale" in src
    assert "not recoverable" in src
    assert "subtract drt_freight_hours_total to compare" not in src
    assert src.isascii()


def test_fleet_utilisation_by_trips_moved_to_uncorrectable():
    assert "fleet_utilisation_by_trips" in extract_drt.MODULAR_UNCORRECTABLE
    assert "fleet_utilisation_by_trips" not in extract_drt.MODULAR_FREIGHT_IN_WINDOW


def test_pax_rows_use_the_correct_recipe():
    """tour_s=7200 (2h), freight_s=1800 (0.5h) -> tour_h - freight_h = 1.5 (a plain
    subtraction); the rescale factor is tour_h/(tour_h-freight_h) = 7200/5400."""
    recon = _stub_fleet(tour_s=7200.0, freight_s=1800.0, ratio_active=0.5)
    k = _by_name(extract(FIX, "DRT_TEST", recon=recon, modular=True))

    assert k["drt_tour_hours_total_pax"]["value"] == pytest.approx(1.5)
    assert k["drt_tour_hours_total_pax"]["kpi_group"] == "system"
    assert k["service_ratio_active_pax"]["value"] == pytest.approx(0.5 * 7200 / 5400)
    assert k["service_ratio_active_pax"]["kpi_group"] == "system"
    assert k["mean_pax_aboard_pax"]["kpi_group"] == "passenger"
    seg_t = recon["fleet"]["seg_time"]
    tot_t = sum(seg_t.values())
    mean_pax = sum(lv * s for lv, s in seg_t.items()) / tot_t
    assert k["mean_pax_aboard_pax"]["value"] == pytest.approx(mean_pax * 7200 / 5400)
    assert k["fleet_utilisation_by_time_pax"]["value"] == pytest.approx(0.42 * 7200 / 5400)


def test_pax_rows_absent_when_not_modular():
    """Same recon (real freight time to correct) but `modular=False` -- the
    caller's signal that this run is not a 1d Modular run -- must suppress the
    `*_pax` rows entirely."""
    recon = _stub_fleet(tour_s=7200.0, freight_s=1800.0, ratio_active=0.5)
    k = _by_name(extract(FIX, "DRT_TEST", recon=recon, modular=False))
    for name in ("drt_tour_hours_total_pax", "service_ratio_active_pax",
                 "fleet_utilisation_by_time_pax", "mean_pax_aboard_pax"):
        assert name not in k, name


def test_pax_rows_absent_without_freight_time():
    """A non-1d recon (freight_s absent/0) must never grow `*_pax` rows even if
    the caller mistakenly passes modular=True -- there is nothing to correct."""
    k = _by_name(extract(FIX, "DRT_TEST", recon=_stub_fleet(), modular=True))
    for name in ("drt_tour_hours_total_pax", "service_ratio_active_pax",
                 "fleet_utilisation_by_time_pax", "mean_pax_aboard_pax"):
        assert name not in k, name


def test_pax_rescale_guard_omits_the_three_ratios_when_freight_meets_tour():
    """tour_h <= freight_h cannot be rescaled into a meaningful ratio (the
    active window would have to be entirely freight or less) -- only the
    honest subtraction is published, not a nonsense/negative ratio."""
    recon = _stub_fleet(tour_s=1800.0, freight_s=1800.0)
    k = _by_name(extract(FIX, "DRT_TEST", recon=recon, modular=True))
    assert k["drt_tour_hours_total_pax"]["value"] == pytest.approx(0.0)
    for name in ("service_ratio_active_pax", "fleet_utilisation_by_time_pax",
                 "mean_pax_aboard_pax"):
        assert name not in k, name


def test_baseline_rows_byte_identical_without_modular_flag():
    """Control-arm pin: on a genuine non-1d run (modular=False, the default) with
    a fleet file found (the normal successful-build shape), the row set must be
    exactly what extract() emitted before this fixwave -- no marker rows, no
    `*_pax` rows, and `fleet_utilisation_by_trips` still a plain KPI row (its
    home-set relocation must not touch its own emission, only its provenance
    classification)."""
    recon = _stub_fleet()
    rows = extract(FIX, "DRT_TEST", recon=recon)
    names = {r["kpi_name"] for r in rows}
    assert extract_drt.MODULAR_CONTAMINATION_KPI not in names
    assert "modular_secondary_contaminated" not in names
    assert not any(n.endswith("_pax") for n in names)
    assert "fleet_utilisation_by_trips" in names
    k = _by_name(rows)
    assert k["fleet_utilisation_by_trips"]["value"] == pytest.approx(0.25)
