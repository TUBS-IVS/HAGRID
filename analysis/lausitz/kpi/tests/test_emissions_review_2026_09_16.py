# -*- coding: utf-8 -*-
"""Tests for the emission-channel review fixes of 2026-09-16.

  I1  emission_factor_set row -- vintage becomes visible in kpis_long
  I2  three allocation rules side by side, each reconciling to the total
  I3  pm10_total = exhaust + wear (the old "pm10" meant wear only)
  I4  boundary stated in every absolute row's source; cradle-to-grave family
  I7  speed clamps are counted instead of silent

Reuses the fixtures of test_extract_emissions (network, freight run dir).
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from test_extract_emissions import _network, _rows_by_name, _run_dir   # noqa: E402


def _drt_extract(tmp_path, n_pax=40, n_parcels=500):
    import extract_emissions as ee
    veh_path = {"drt_1": [("l1", 1, 20, 10.0), ("l2", 1, 20, 20.0)]}
    recon = {"per_veh": {"drt_1": {"drive_s": 200.0, "task_seq": []}}}
    rows, detail = ee.extract(tmp_path, "test", recon=recon, veh_path=veh_path,
                              network_gz=_network(tmp_path),
                              n_pax=n_pax, n_parcels=n_parcels)
    return _rows_by_name(rows), detail


# --- I3 ---------------------------------------------------------------------

def test_pm10_total_is_exhaust_plus_wear():
    import emissions_emep as em
    fac = em.load_factors()
    d = em.vehicle_emissions(100.0, 30.0, "diesel", "N1-III", fac)
    assert d["PM_EXHAUST"] > 0
    assert d["PM10_TOTAL"] == pytest.approx(d["PM_EXHAUST"] + d["PM10_NONEXHAUST"])
    b = em.vehicle_emissions(100.0, 30.0, "bev", "N1-III", fac)
    assert b["PM_EXHAUST"] == 0.0
    assert b["PM10_TOTAL"] == pytest.approx(b["PM10_NONEXHAUST"])


def test_pm10_total_row_reconciles_with_its_parts(tmp_path):
    import extract_emissions as ee
    by = _rows_by_name(ee.extract(_run_dir(tmp_path), "test")[0])
    assert by["freight_pm10_total"]["unit"] == "g"
    assert by["freight_pm10_total"]["value"] == pytest.approx(
        by["freight_pm_exhaust"]["value"] + by["freight_pm10_nonexhaust"]["value"])
    assert by["total_pm10_total"]["value"] == pytest.approx(by["freight_pm10_total"]["value"])


# --- I7 ---------------------------------------------------------------------

def test_speed_clamps_are_counted_and_reset():
    import emissions_emep as em
    fac = em.load_factors()
    coef = fac["diesel"]["N1-III"]["EC"]
    em.clamp_report()                                   # reset whatever ran before
    em.ef(coef["vmin"] - 1.0, coef)
    em.ef(coef["vmax"] + 1.0, coef)
    em.ef(0.5 * (coef["vmin"] + coef["vmax"]), coef)
    r = em.clamp_report()
    assert r == {"evaluations": 3, "below_vmin": 1, "above_vmax": 1}
    assert em.clamp_report()["evaluations"] == 0        # reset happened


# --- I2 ---------------------------------------------------------------------

def test_three_allocation_rules_each_reconcile_to_the_total(tmp_path):
    by, _ = _drt_extract(tmp_path)
    tot = by["total_co2e_wtw"]["value"]
    for rule in ("mass", "slots", "units"):
        parts = (by["co2e_wtw_per_pax_" + rule]["value"] * 40
                 + by["co2e_wtw_per_parcel_" + rule]["value"] * 500)
        assert parts == pytest.approx(tot, rel=1e-9), rule
    # the unsuffixed rows ARE the mass rule, and say so
    assert by["co2e_wtw_per_parcel"]["value"] == pytest.approx(by["co2e_wtw_per_parcel_mass"]["value"])
    assert "rule: mass" in by["co2e_wtw_per_parcel"]["source"]


def test_allocation_rules_order_parcels_units_over_slots_over_mass(tmp_path):
    """1 parcel = 1 unit, 0.4 seat-equivalents, 1.65/80 of a passenger -- the
    parcel share must fall in exactly that order, so a reader sees the
    convention's leverage instead of one number."""
    by, _ = _drt_extract(tmp_path)
    u = by["alloc_share_parcels_units_km"]["value"]
    s = by["alloc_share_parcels_slots_km"]["value"]
    m = by["alloc_share_parcels_mass_km"]["value"]
    assert u > s > m > 0
    assert m == pytest.approx(by["alloc_share_parcels_mass"]["value"])


def test_allocate_by_weights_is_the_general_form_of_mass():
    import extract_emissions as ee
    path = [("l1", 2, 10, 0.0), ("l2", 0, 0, 5.0)]
    ll = {"l1": 1000.0, "l2": 500.0}
    sup = {"kg_per_parcel": 1.65, "kg_per_passenger": 80.0}
    a = ee.allocate_vehicle_by_mass(path, ll, 100.0, sup)
    b = ee.allocate_vehicle_by_weights(path, ll, 100.0, 80.0, 1.65)
    assert a == b
    units = ee.allocate_vehicle_by_weights(path, ll, 100.0, 1.0, 1.0)
    assert units["parcels"] == pytest.approx(100.0 * 10 / 12)   # 2 pax + 10 parcels aboard


# --- I1 ---------------------------------------------------------------------

def test_factor_set_row_is_numeric_and_traceable(tmp_path):
    import extract_emissions as ee
    by = _rows_by_name(ee.extract(_run_dir(tmp_path), "test")[0])
    r = by["emission_factor_set"]
    assert isinstance(r["value"], int)
    assert r["value"] == int(ee.factor_set_hash()[:6], 16)
    assert ee.factor_set_hash()[:12] in r["source"]
    assert r["unit"] == "id"


# --- I4 ---------------------------------------------------------------------

def test_absolute_rows_state_their_system_boundary(tmp_path):
    import extract_emissions as ee
    by = _rows_by_name(ee.extract(_run_dir(tmp_path), "test")[0])
    assert "well-to-wheel" in by["freight_co2e_wtw"]["source"]
    assert "vehicle operation" in by["freight_nox"]["source"]
    assert "vehicle operation" in by["freight_pm10_total"]["source"]
    assert "tank-to-wheel" in by["freight_co2e_ttw"]["source"]


def test_ctg_rows_are_wtw_plus_rate_times_km(tmp_path):
    """Cradle-to-grave = WTW + vehicle-cycle rate x fleet km, per fleet and
    powertrain, and the fleet total is the sum. Rates come from the
    supplement; without them the family must be absent, not partial."""
    import emissions_emep as em
    import extract_emissions as ee
    sup = em.load_factors()["sup"]
    if "ctg_g_per_km_lmd_diesel" not in sup:
        pytest.skip("no ctg rates in the supplement")
    rows, detail = ee.extract(_run_dir(tmp_path), "test")
    by = _rows_by_name(rows)
    km = sum(d["km"] for d in detail if d["powertrain"] == "diesel")
    assert km == pytest.approx(180.0)                    # fixture: 120 + 60 km
    for pt, sfx in (("diesel", ""), ("bev", "_bev")):
        rate = sup["ctg_g_per_km_lmd_" + pt]
        want = by["freight_co2e_wtw" + sfx]["value"] + rate * km * 1e-3
        assert by["freight_co2e_ctg" + sfx]["value"] == pytest.approx(want, rel=1e-9)
        assert by["total_co2e_ctg" + sfx]["value"] == pytest.approx(want, rel=1e-9)
        assert "cradle-to-grave" in by["total_co2e_ctg" + sfx]["source"]


def test_ctg_uses_the_drt_rate_for_the_drt_fleet(tmp_path):
    import emissions_emep as em
    sup = em.load_factors()["sup"]
    if "ctg_g_per_km_drt_diesel" not in sup:
        pytest.skip("no ctg rates in the supplement")
    by, detail = _drt_extract(tmp_path)
    km = sum(d["km"] for d in detail if d["fleet"] == "drt" and d["powertrain"] == "diesel")
    want = by["drt_co2e_wtw"]["value"] + sup["ctg_g_per_km_drt_diesel"] * km * 1e-3
    assert by["drt_co2e_ctg"]["value"] == pytest.approx(want, rel=1e-9)
    assert "class=drt" in by["drt_co2e_ctg"]["source"]
