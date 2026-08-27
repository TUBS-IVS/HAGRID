# -*- coding: utf-8 -*-
import gzip
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import extract_freight_provider as efp
from extract_freight import extract

FIX = Path(__file__).parent / "fixtures" / "drtrun"
FIX_LMD = Path(__file__).parent / "fixtures" / "mini_lmd"


def test_extract_freight_kpis():
    k = {r["kpi_name"]: r for r in extract(FIX, "DRT_TEST")}
    assert k["carriers"]["value"] == 2
    assert k["freight_tours"]["value"] == 35
    assert k["freight_vehicle_km"]["value"] == pytest.approx(4047.687, abs=1e-3)
    # freight_total_costs: NOT asserted here (v2 Plan D Task 10 #3) -- this
    # fixture's carriers XML ids ("dhl_1"/"ups_1") don't match the TSV
    # carrierIds ("dhl"/"ups") and carry no costDistance/costTime/
    # costOvertime attributes, so the carrier-attribute cost basis correctly
    # totals 0.0 here; that is a fixture artifact, not a behavior to pin.
    # Cost-aggregate consistency against a realistic carrier-attribute
    # fixture is covered by test_freight_cost_aggregate_matches_provider_sum
    # below and by test_full_married250_freight_cost_aggregate_matches_legacy
    # in test_real_married250.py.
    assert k["freight_vehicles"]["value"] == 3
    assert k["parcels_handled"]["value"] == 410
    assert k["avg_max_load"]["value"] == pytest.approx((72.7 + 90.0 + 86.9) / 300.0)
    assert k["parcels_total"]["value"] == 500
    assert k["parcels_missed"]["value"] == 10
    assert k["parcels_unassigned"]["value"] == 5
    # Zustellquoten-Konvention (2026-08-10): delivery_rate ist OPERATIV, das
    # Not-at-home-Overlay (parcels_missed) wird NICHT abgezogen -- nur unassigned zaehlt
    # als Zustellausfall. Der Netto-Wert steht daneben.
    assert k["delivery_rate"]["value"] == pytest.approx(495 / 500)
    assert k["delivery_rate_net_overlay"]["value"] == pytest.approx(485 / 500)
    assert k["parcels_delivered_operational"]["value"] == 495
    # bewusst netto belassen (Nenner von economics.freight_cost_per_parcel)
    assert k["parcels_per_vehicle_km"]["value"] == pytest.approx(485 / 4047.687, abs=1e-6)


def test_freight_cost_aggregate_matches_provider_sum():
    """freight_var_costs_dist/freight_total_costs (the aggregate, 'economic'
    category rows) must be sourced from the SAME carrier-attribute cost basis
    as extract_freight_provider (legacy DashboardGenerator-analogous
    costDistance/costTime/costOvertime + fixed vehicle-type fixed cost, with
    the low-util ratio re-allocation) -- NOT MATSim's own TSV
    varCostsDist[EUR]/totalCosts[EUR] columns. Those TSV columns accumulate
    distance on LinkEnterEvent only (structurally missing each tour's first
    link) and totalCosts[EUR] drops costOvertime entirely, which is why the
    old TSV-sourced aggregate silently diverged from both the per-provider
    table and the legacy dashboard. The aggregate must equal the sum of the
    real (non-'all', non-'type:'/'vtype:') per-provider parts."""
    rows = extract(FIX_LMD, "MINI")
    k = {r["kpi_name"]: r for r in rows}

    prov_rows = efp.extract(FIX_LMD, "MINI")
    real = [r for r in prov_rows if r["provider"] != "all"
            and not r["provider"].startswith(("type:", "vtype:"))]
    expected_dist = sum(r["value"] for r in real if r["kpi_name"] == "cost_dist")
    expected_total = sum(r["value"] for r in real if r["kpi_name"] == "cost_total")

    assert k["freight_var_costs_dist"]["value"] == pytest.approx(expected_dist)
    assert k["freight_total_costs"]["value"] == pytest.approx(expected_total)
    # fixture-level values pinned (dhl+hermes+amazon carrier-attribute basis)
    # so a future accidental TSV-sourcing regression is caught even if the
    # provider extractor changes underneath this test.
    assert k["freight_var_costs_dist"]["value"] == pytest.approx(1040.0)
    assert k["freight_total_costs"]["value"] == pytest.approx(1610.0)
    assert k["freight_var_costs_dist"]["source"] != "TimeDistance_perCarrier"
    assert k["freight_total_costs"]["source"] != "TimeDistance_perCarrier"


_CARRIERS_XML = """<?xml version="1.0" encoding="UTF-8"?>
<carriers>
  <carrier id="dhl_1">
    <attributes>
      <attribute name="numberOfParcels" class="java.lang.Integer">300</attribute>
      <attribute name="missedParcels" class="java.lang.Integer">10</attribute>
      <attribute name="unassignedParcels" class="java.lang.Integer">5</attribute>
    </attributes>
  </carrier>
  <carrier id="ups_1">
    <attributes>
      <attribute name="numberOfParcels" class="java.lang.Integer">200</attribute>
      <attribute name="missedParcels" class="java.lang.Integer">0</attribute>
    </attributes>
  </carrier>
</carriers>
"""


def test_extract_freight_falls_back_when_load_per_vehicle_is_empty(tmp_path):
    """Real HAGRID runs model parcels as MATSim carrier SERVICES, not SHIPMENTS.
    MATSim's CarrierLoadAnalysis only listens for CarrierShipmentPickupStartEvent /
    CarrierShipmentDeliveryStartEvent, so on every real run Load_perVehicle.tsv is
    header-only (0 data rows). Confirmed against both real acceptance run dirs
    (married120 DRT+freight and LMD) for this task. extract() must fall back to
    TimeDistance_perVehicle.tsv (always populated) for freight_vehicles, and to the
    carrier-attribute delivered count for parcels_handled -- and must never emit a
    NaN avg_max_load KPI."""
    fr = tmp_path / "analysis" / "freight"
    fr.mkdir(parents=True)
    (fr / "TimeDistance_perCarrier.tsv").write_text(
        "carrierId\tnuOfTours\ttourDurations[s]\ttourDurations[h]\ttravelDistances[m]\t"
        "travelDistances[km]\ttravelTimes[s]\ttravelTimes[h]\tfixedCosts[EUR]\t"
        "varCostsTime[EUR]\tvarCostsDist[EUR]\ttotalCosts[EUR]\n"
        "dhl\t25\t573730.0\t159.36944444444444\t3047687.000000002\t3047.6870000000017\t"
        "296685.0\t82.4125\t4103.4299999999985\t0.0\t1113.0607007804679\t5216.490700780467\n"
        "ups\t10\t200000.0\t55.55555555555556\t1000000.0\t1000.0\t100000.0\t"
        "27.77777777777778\t1000.0\t0.0\t400.0\t1400.0\n",
        encoding="utf-8")
    (fr / "Load_perVehicle.tsv").write_text(
        "vehicleId\tvehicleTypeId\tcapacity\tmaxLoad\tmaxLoadPercentage\thandledDemand\t"
        "load state during tour\n", encoding="utf-8")
    (fr / "TimeDistance_perVehicle.tsv").write_text(
        "vehicleId\tcarrierId\tvehicleTypeId\ttourId\ttourDuration[s]\ttourDuration[h]\t"
        "travelDistance[m]\ttravelDistance[km]\ttravelTime[s]\ttravelTime[h]\t"
        "costPerSecond[EUR/s]\tcostPerMeter[EUR/m]\tfixedCosts[EUR]\tvarCostsTime[EUR]\t"
        "varCostsDist[EUR]\ttotalCosts[EUR]\n"
        "dhl_v1\tdhl\tct_cep_size_m\t1\t100.0\t0.03\t1000.0\t1.0\t100.0\t0.03\t0.0\t0.0\t10.0\t0.0\t1.0\t11.0\n"
        "dhl_v2\tdhl\tct_cep_size_m\t2\t100.0\t0.03\t1000.0\t1.0\t100.0\t0.03\t0.0\t0.0\t10.0\t0.0\t1.0\t11.0\n"
        "ups_v1\tups\tct_cep_size_l\t1\t100.0\t0.03\t1000.0\t1.0\t100.0\t0.03\t0.0\t0.0\t10.0\t0.0\t1.0\t11.0\n",
        encoding="utf-8")
    with gzip.open(tmp_path / "REAL_TEST.output_carriers.xml.gz", "wt", encoding="utf-8") as f:
        f.write(_CARRIERS_XML)

    k = {r["kpi_name"]: r for r in extract(tmp_path, "REAL_TEST")}
    assert k["freight_vehicles"]["value"] == 3
    assert k["freight_vehicles"]["source"] == "TimeDistance_perVehicle"
    assert "avg_max_load" not in k
    assert k["parcels_handled"]["value"] == 485  # delivered = 500 - 10 - 5 (fallback)


def test_extract_freight_omits_vehicles_when_time_distance_per_vehicle_missing(tmp_path):
    """Guard for the real-run path: Load_perVehicle.tsv is header-only on every
    real HAGRID run (service-based carriers), so the else-branch read of
    TimeDistance_perVehicle.tsv is the guaranteed path, not a rare fallback.
    If that file is missing, extract() must omit the freight_vehicles KPI
    entirely (never emit 0 or crash) while still emitting the carrier-attribute
    rows (parcels_total, delivery_rate, ...)."""
    fr = tmp_path / "analysis" / "freight"
    fr.mkdir(parents=True)
    (fr / "TimeDistance_perCarrier.tsv").write_text(
        "carrierId\tnuOfTours\ttourDurations[s]\ttourDurations[h]\ttravelDistances[m]\t"
        "travelDistances[km]\ttravelTimes[s]\ttravelTimes[h]\tfixedCosts[EUR]\t"
        "varCostsTime[EUR]\tvarCostsDist[EUR]\ttotalCosts[EUR]\n"
        "dhl\t25\t573730.0\t159.36944444444444\t3047687.000000002\t3047.6870000000017\t"
        "296685.0\t82.4125\t4103.4299999999985\t0.0\t1113.0607007804679\t5216.490700780467\n"
        "ups\t10\t200000.0\t55.55555555555556\t1000000.0\t1000.0\t100000.0\t"
        "27.77777777777778\t1000.0\t0.0\t400.0\t1400.0\n",
        encoding="utf-8")
    (fr / "Load_perVehicle.tsv").write_text(
        "vehicleId\tvehicleTypeId\tcapacity\tmaxLoad\tmaxLoadPercentage\thandledDemand\t"
        "load state during tour\n", encoding="utf-8")
    # No TimeDistance_perVehicle.tsv at all.
    with gzip.open(tmp_path / "REAL_TEST.output_carriers.xml.gz", "wt", encoding="utf-8") as f:
        f.write(_CARRIERS_XML)

    rows = extract(tmp_path, "REAL_TEST")
    k = {r["kpi_name"]: r for r in rows}

    assert "freight_vehicles" not in k
    assert "parcels_total" in k
    assert "delivery_rate" in k
