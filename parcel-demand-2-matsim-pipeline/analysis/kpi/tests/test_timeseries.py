# -*- coding: utf-8 -*-
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from timeseries import extract, write
from run_meta import parse_legacy_dir_name

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def _series(rows, name):
    return {r["hour"]: r["value"] for r in rows if r["series"] == name}


def test_hourly_series():
    rows = extract(FIX, "DRT_TEST")
    rides = _series(rows, "drt_rides")
    # departures 25200/25900 -> hour 7, 29100 -> hour 8, 36400 -> hour 10
    assert rides == {7: 2, 8: 1, 10: 1}
    waits = _series(rows, "drt_wait_mean")
    assert waits[7] == pytest.approx(350.0)
    rej = _series(rows, "drt_rejections")
    assert rej == {9: 1, 10: 1}   # 35928->9, 36100->10


def test_freight_cache_series(tmp_path):
    cache = tmp_path / "f.txt"
    cache.write_text(
        '<event time="30000.0" type="actstart" person="freight_dhl_veh_1" actType="service"/>\n'
        '<event time="30100.0" type="actstart" person="freight_dhl_veh_1" actType="service"/>\n'
        '<event time="40000.0" type="actstart" person="freight_ups_veh_1" actType="service"/>\n',
        encoding="utf-8")
    rows = extract(FIX, "DRT_TEST", freight_cache=cache)
    stops = _series(rows, "freight_service_stops")
    assert stops == {8: 2, 11: 1}


def test_write(tmp_path):
    meta = parse_legacy_dir_name("DRT_BASELINE_13052025_married120_iter150_jsprit100")
    out = tmp_path / "kpi_timeseries.csv"
    write(extract(FIX, "DRT_TEST"), meta, out)
    lines = out.read_text(encoding="utf-8").splitlines()
    assert lines[0] == "run_id;series;hour;value;unit"
    assert lines[1].startswith("DRT_BASELINE_13052025_married120;")
