# -*- coding: utf-8 -*-
"""Emission KPI extractor: freight arm, modular freight arm, DRT arm,
mass allocation, KPI rows.

Plan: docs/superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md
"""
import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

TSV_COLS = ["vehicleId", "carrierId", "vehicleTypeId", "tourId",
            "tourDuration[s]", "tourDuration[h]", "travelDistance[m]",
            "travelDistance[km]", "travelTime[s]", "travelTime[h]",
            "costPerSecond[EUR/s]", "costPerMeter[EUR/m]",
            "fixedCosts[EUR]", "varCostsTime[EUR]", "varCostsDist[EUR]",
            "totalCosts[EUR]"]


def _run_dir(tmp_path):
    fr = tmp_path / "analysis" / "freight"
    fr.mkdir(parents=True)
    rows = [
        ["freight_dhl_veh_a_1", "dhl", "ct_cep_size_m", 1, 25000, 6.9,
         120000.0, 120.0, 14400, 4.0, 0, 3.7e-4, 171.78, 0, 44.4, 216.2],
        ["freight_ups_veh_b_2", "ups", "ct_cep_size_l", 2, 20000, 5.6,
         60000.0, 60.0, 7200, 2.0, 0, 3.9e-4, 189.15, 0, 23.2, 212.4],
    ]
    pd.DataFrame(rows, columns=TSV_COLS).to_csv(
        fr / "TimeDistance_perVehicle.tsv", sep="\t", index=False)
    return tmp_path


def test_freight_arm_sums_tours_at_tour_mean_speed(tmp_path):
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    totals, detail = ee.freight_arm(_run_dir(tmp_path), fac)
    # Tour 1: 120 km bei 120/4=30 km/h (size_m -> N1-III)
    # Tour 2:  60 km bei  60/2=30 km/h (size_l -> N1-III)
    exp = em.vehicle_emissions(120.0, 30.0, "diesel", "N1-III", fac)
    exp2 = em.vehicle_emissions(60.0, 30.0, "diesel", "N1-III", fac)
    assert totals["diesel"]["CO2E_WTW"] == pytest.approx(
        exp["CO2E_WTW"] + exp2["CO2E_WTW"])
    assert totals["bev"]["CO2E_WTW"] > 0
    assert len(detail) == 4                      # 2 Touren x 2 Antriebe
    d0 = [d for d in detail if d["powertrain"] == "diesel"][0]
    assert d0["fleet"] == "freight" and d0["v_kmh"] == pytest.approx(30.0)
    assert d0["vehicle_type"] == "ct_cep_size_m"
    assert d0["segment"] == "N1-III"


def test_freight_arm_size_s_gets_the_lighter_segment(tmp_path):
    """Rev. B Kernverhalten: size_s ist N1-II, nicht N1-III. In base10c
    liegen 93 % der Freight-km auf size_s -- eine Einheitsklasse N1-III
    wuerde die Baseline-Energie um ~39 % ueberschaetzen."""
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    fr = tmp_path / "analysis" / "freight"
    fr.mkdir(parents=True)
    rows = [["dhl_s", "dhl", "ct_cep_size_s", 1, 25000, 6.9,
             100000.0, 100.0, 12000, 3.33, 0, 3.6e-4, 154.41, 0, 36.0, 190.4]]
    pd.DataFrame(rows, columns=TSV_COLS).to_csv(
        fr / "TimeDistance_perVehicle.tsv", sep="\t", index=False)
    _, detail = ee.freight_arm(tmp_path, fac)
    d = [x for x in detail if x["powertrain"] == "diesel"][0]
    assert d["segment"] == "N1-II"
    exp = em.vehicle_emissions(100.0, 30.0, "diesel", "N1-II", fac)
    assert d["ENERGY_MJ"] == pytest.approx(exp["ENERGY_MJ"])
    # und deutlich unter der N1-III-Rechnung
    n3 = em.vehicle_emissions(100.0, 30.0, "diesel", "N1-III", fac)
    assert d["ENERGY_MJ"] < 0.75 * n3["ENERGY_MJ"]


def test_segment_for_type_covers_generated_sweep_types():
    """CarrierVehicleFactory erzeugt ct_cep_<cap>_<tpl> zur Laufzeit; die
    Regel muss die abdecken, sonst faellt der Hannover-Sweep still auf
    null Emissionen."""
    import extract_emissions as ee
    assert ee.segment_for_type("ct_cep_size_s") == "N1-II"
    assert ee.segment_for_type("ct_cep_size_m") == "N1-III"
    assert ee.segment_for_type("ct_cep_size_l") == "N1-III"
    assert ee.segment_for_type("ct_cep_60_m", capacity=60.0) == "N1-II"
    assert ee.segment_for_type("ct_cep_300_l", capacity=300.0) == "N1-III"
    assert ee.segment_for_type("ct_cep_120_m", capacity=120.0) == "N1-II"
    assert ee.segment_for_type("ct_cep_121_m", capacity=121.0) == "N1-III"


def test_segment_for_type_raises_on_unknown_without_capacity():
    """Lieber laut scheitern als still als N1-III bepreisen."""
    import extract_emissions as ee
    with pytest.raises(ValueError):
        ee.segment_for_type("ct_cargobike_xl")


def test_freight_arm_missing_tsv_returns_none(tmp_path):
    import emissions_emep as em
    import extract_emissions as ee
    assert ee.freight_arm(tmp_path, em.load_factors()) is None
