# -*- coding: utf-8 -*-
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
