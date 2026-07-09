# -*- coding: utf-8 -*-
import gzip
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from extract_freight import extract

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def test_extract_freight_kpis():
    k = {r["kpi_name"]: r for r in extract(FIX, "DRT_TEST")}
    assert k["carriers"]["value"] == 2
    assert k["freight_tours"]["value"] == 35
    assert k["freight_vehicle_km"]["value"] == pytest.approx(4047.687, abs=1e-3)
    assert k["freight_total_costs"]["value"] == pytest.approx(6616.4907, abs=1e-3)
    assert k["freight_vehicles"]["value"] == 3
    assert k["parcels_handled"]["value"] == 410
    assert k["avg_max_load"]["value"] == pytest.approx((72.7 + 90.0 + 86.9) / 300.0)
    assert k["parcels_total"]["value"] == 500
    assert k["parcels_missed"]["value"] == 10
    assert k["parcels_unassigned"]["value"] == 5
    assert k["delivery_rate"]["value"] == pytest.approx(485 / 500)
    assert k["parcels_per_vehicle_km"]["value"] == pytest.approx(485 / 4047.687, abs=1e-6)


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
