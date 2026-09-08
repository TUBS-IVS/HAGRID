# -*- coding: utf-8 -*-
"""Unified cost model (cost_model.py + economics.py Lausitz path).

The anchors here are deliberately NOT recomputed with the production formula.
Two independent kinds are used:

1. the rates cost_parameters.csv states in its own `note` fields, written by a
   human during the derivation (28.99 / 33.45 / 14.80 / 16.60 / 18.80 / 22.74);
2. arithmetic written out longhand in the test from the raw SET parameters.

A test that called cost_model to predict cost_model would pass through any
sign error or unit slip. Each anchor is also mutation-checked: perturb the
input, assert the check actually fails.
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cost_model
from common import row
from economics import extract as econ


class _Meta:
    def __init__(self, study_area="lausitz_hoyerswerda", scenario="DRT_BASELINE"):
        self.study_area = study_area
        self.scenario = scenario


# ---------------------------------------------------------------- parameters
def test_derived_rates_match_the_values_the_csv_documents():
    """Every DERIVED rate reproduces the number in its own note field."""
    got = cost_model.selftest()
    assert got["c_time_lmd"] == pytest.approx(28.99, abs=0.005)
    assert got["c_time_drt"] == pytest.approx(33.45, abs=0.005)
    assert got["c_veh_drt_pax"] == pytest.approx(22.74, abs=0.005)
    assert got["c_veh_ct_cep_size_s"] == pytest.approx(14.80, abs=0.005)


def test_selftest_actually_discriminates():
    """Mutation check: a wrong parameter must make selftest raise."""
    raw = cost_model.load()
    raw["drt_wage_tariff_hourly"] = dict(raw["drt_wage_tariff_hourly"], value="30.00")
    with pytest.raises(AssertionError, match="c_time_drt"):
        cost_model.selftest(cost_model.CostParams(raw))


def test_labour_rate_longhand_from_the_raw_tariff():
    """c_time_drt rebuilt by hand: tariff -> gross -> on-costs -> per
    PRODUCTIVE hour. Dividing by contracted hours instead would understate
    labour by about a fifth, which is the mistake this pins."""
    p = cost_model.build()
    gross = 21.11 * 38.0 * 52.0 + 1150.0          # tariff year + special payment
    employer = gross * 1.21                        # 21 % employer on-costs
    productive = (52 * 5 - 30 - 11 - 15) * (38.0 / 5.0)   # 204 d x 7.6 h
    assert p.c_time_drt == pytest.approx(employer / productive)
    assert productive == pytest.approx(1550.4)
    # contracted hours would be 38*52 = 1976 -> a 21 % cheaper driver
    assert employer / (38.0 * 52.0) < 0.85 * p.c_time_drt


def test_vehicle_capital_is_straight_line_to_residual():
    p = cost_model.build()
    # (50000 net, 35 % residual, 5 years, +1800 insurance/tax) / 365 days
    assert p.c_veh_drt_pax == pytest.approx((50000 * 0.65 / 5 + 1800) / 365)
    # the van arm runs 300 days, so the SAME monthly rate gives a HIGHER daily
    # one -- the two operating-day conventions must not be merged
    assert p.c_veh_lmd["ct_cep_size_s"] == pytest.approx((220 * 12 + 1800) / 300)


# ------------------------------------------------------------------ gating
def _rows(**kw):
    base = {"drt_vehicles": 10, "drt_tour_hours_total": 100.0,
            "drt_vehicle_km": 1000.0, "total_energy_final": 3565.0,
            "drt_rides": 500}
    base.update(kw)
    return [row("system", k, v, "x", "fixture") for k, v in base.items()]


def test_hannover_keeps_the_legacy_placeholder():
    out = {r["kpi_name"]: r for r in
           econ(_rows(fleet_shift_hours=240.0), meta=_Meta(study_area="hannover"))}
    assert "drt_cost_bottom_up_placeholder" in out
    assert not any(k.startswith("cost_total") for k in out)


def test_lausitz_uses_the_unified_model_and_drops_the_placeholder():
    out = {r["kpi_name"]: r for r in econ(_rows(), meta=_Meta())}
    assert "cost_total" in out
    assert not any("placeholder" in k for k in out)


def test_no_meta_returns_legacy():
    """A caller with no RunMeta must not silently get Lausitz pricing."""
    out = {r["kpi_name"]: r for r in econ(_rows(fleet_shift_hours=240.0))}
    assert "drt_cost_bottom_up_placeholder" in out


# ------------------------------------------------------------------ the sum
def test_components_add_up_to_the_total():
    out = {r["kpi_name"]: r["value"] for r in econ(_rows(), meta=_Meta())}
    parts = sum(out["cost_" + k] for k in
                ("capital", "labour", "maintenance", "energy"))
    assert parts == pytest.approx(out["cost_total"])


def test_each_term_longhand():
    p = cost_model.build()
    out = {r["kpi_name"]: r["value"] for r in econ(_rows(), meta=_Meta())}
    assert out["cost_capital"] == pytest.approx(10 * p.c_veh_drt_pax)
    assert out["cost_labour"] == pytest.approx(100.0 * p.c_time_drt)
    # 3565 MJ / 35.65 MJ/l = exactly 100 l
    assert out["cost_energy"] == pytest.approx(100.0 * 1.5546)


def test_maintenance_is_maint_only_never_c_dist():
    """c_dist_* bundles maintenance AND fuel and is a comparison figure only.
    Using it here while energy also comes from ENERGY_MJ would count fuel
    twice -- this pins the distance term to c_maint_per_km alone."""
    out = {r["kpi_name"]: r["value"] for r in econ(_rows(), meta=_Meta())}
    assert out["cost_maintenance"] == pytest.approx(1000.0 * 0.11)
    # the N1-III comparison rate is ~0.246 EUR/km; landing there means the
    # fuel component leaked into the distance term
    assert out["cost_maintenance"] < 1000.0 * 0.20


# --------------------------------------------------------- separability
def test_integrated_arm_emits_no_per_unit_cost():
    """One fleet carrying both services cannot be split without the mass
    allocation of METHODS-LOG 2.26. Dividing the FULL total by parcels would
    charge the whole passenger operation to the freight side."""
    out = {r["kpi_name"]: r for r in
           econ(_rows(parcels_served=6052, parcels_missed_overlay=415),
                meta=_Meta(scenario="DRT_MODULAR"))}
    assert "cost_per_unit_separable" in out
    assert "cost_per_parcel" not in out
    assert "cost_per_ride" not in out


def test_disjoint_fleets_price_each_service_on_its_own_fleet():
    out = {r["kpi_name"]: r["value"] for r in
           econ(_rows(freight_vehicles=3, freight_tour_hours=20.0,
                      freight_vehicle_km=200.0, parcels_handled=400,
                      drt_energy_final=3565.0, freight_energy_final=0.0),
                meta=_Meta())}
    assert out["cost_drt_total"] + out["cost_lmd_total"] == pytest.approx(
        out["cost_total"])
    # EUR/parcel must come from the VAN fleet, not from the system total --
    # the system total is ~20x larger here, so this discriminates hard
    assert out["cost_per_parcel"] == pytest.approx(out["cost_lmd_total"] / 400)
    assert out["cost_per_ride"] == pytest.approx(out["cost_drt_total"] / 500)


def test_van_capital_is_omitted_loudly_when_the_type_mix_is_unknown():
    """No default van mix may be substituted: it would look plausible and
    misprice the whole baseline freight arm silently."""
    out = [r for r in econ(_rows(freight_vehicles=3, freight_tour_hours=20.0,
                                 freight_vehicle_km=200.0),
                           meta=_Meta())]
    src = {r["kpi_name"]: r["source"] for r in out}
    assert "CAVEAT" in src["cost_total"]
    assert "type mix" in src["cost_total"]


# ------------------------------------------------------------- honesty rows
def test_driver_count_row_exposes_the_free_swap_assumption():
    """100 h over 10 vehicles = 10 h/vehicle, exactly the ArbZG maximum."""
    out = {r["kpi_name"]: r["value"] for r in econ(_rows(), meta=_Meta())}
    assert out["cost_drivers_per_vehicle_min"] == 1
    # 16.1 h -- the real f135 figure -- needs two
    out2 = {r["kpi_name"]: r["value"] for r in
            econ(_rows(drt_tour_hours_total=161.0), meta=_Meta())}
    assert out2["cost_drivers_per_vehicle_min"] == 2


def test_uninstrumented_terms_are_emitted_not_dropped():
    out = {r["kpi_name"]: r for r in econ(_rows(), meta=_Meta())}
    assert out["cost_overtime_sensitivity_instrumented"]["value"] == 0
    assert out["cost_evening_surcharge_instrumented"]["value"] == 0


def test_missing_energy_caveats_every_euro_row():
    rows = [r for r in _rows() if r["kpi_name"] != "total_energy_final"]
    out = {r["kpi_name"]: r for r in econ(rows, meta=_Meta())}
    assert out["cost_energy"]["value"] == 0.0
    assert "ENERGY_MJ" in out["cost_total"]["source"]


def test_a_run_with_no_fleet_at_all_falls_back_and_says_so():
    out = {r["kpi_name"]: r for r in
           econ([row("system", "fleet_shift_hours", 240.0, "h", "f")],
                meta=_Meta())}
    assert out["cost_model_failed"]["value"] == 1
    assert "drt_cost_bottom_up_placeholder" in out
