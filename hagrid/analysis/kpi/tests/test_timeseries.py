# -*- coding: utf-8 -*-
import shutil
import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from timeseries import extract, write
from run_meta import parse_legacy_dir_name
from events_cache import ensure_caches

FIX = Path(__file__).parent / "fixtures" / "drtrun"
MINI_FIXTURE = Path(__file__).parent / "fixtures" / "mini_events" / "MINI.output_events.xml.gz"


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


def test_requests_submitted_series():
    rows = extract(FIX, "DRT_TEST")
    submitted = _series(rows, "drt_requests_submitted")
    # submissionTime 25000->6, 25500->7, 29000->8, 36100->10
    assert submitted == {6: 1, 7: 1, 8: 1, 10: 1}


def test_feeder_trips_series():
    rows = extract(FIX, "DRT_TEST")
    feeder = _series(rows, "drt_feeder_trips")
    # only trip 4 (modes walk-drt-walk-pt-walk) has both drt & pt; dep_time 08:20:00 -> hour 8
    assert feeder == {8: 1}
    units = {r["series"]: r["unit"] for r in rows}
    assert units["drt_requests_submitted"] == "requests/h"
    assert units["drt_feeder_trips"] == "trips/h"


def test_requests_submitted_absent_column_skips_gracefully(tmp_path):
    legs_src = FIX / "DRT_TEST.output_drt_legs_drt.csv"
    legs = pd.read_csv(legs_src, sep=";")
    legs = legs.drop(columns=["submissionTime"])
    legs.to_csv(tmp_path / "NOSUB.output_drt_legs_drt.csv", sep=";", index=False)
    rows = extract(tmp_path, "NOSUB")
    assert _series(rows, "drt_requests_submitted") == {}
    # other series from the same file still extracted
    assert _series(rows, "drt_rides") != {}


def test_freight_cache_series(tmp_path):
    # richer v2 cache also contains 'entered link' and 'actend' freight lines
    # (Task 1) -- only actstart+service lines must be counted as stops.
    cache = tmp_path / "f.txt"
    cache.write_text(
        '<event time="29000.0" type="entered link" link="l1" vehicle="freight_dhl_veh_1"/>\n'
        '<event time="30000.0" type="actstart" person="freight_dhl_veh_1" actType="service"/>\n'
        '<event time="30050.0" type="actend" person="freight_dhl_veh_1" actType="service"/>\n'
        '<event time="30100.0" type="actstart" person="freight_dhl_veh_1" actType="service"/>\n'
        '<event time="40000.0" type="actstart" person="freight_ups_veh_1" actType="service"/>\n',
        encoding="utf-8")
    rows = extract(FIX, "DRT_TEST", freight_cache=cache)
    stops = _series(rows, "freight_service_stops")
    assert stops == {8: 2, 11: 1}


def test_freight_service_stops_filters_richer_cache_from_mini_fixture(tmp_path):
    # v2 Plan D Task 1: the richer freight cache built by ensure_caches() from
    # the mini fixture has 9 lines total (entered-link + actstart/actend +
    # the shared drt_freight_shared_1 line) -- but only the 2 actstart+service
    # lines (t=29400 -> hour 8, t=32400 -> hour 9) may count as service stops.
    prefix = "MINI"
    run = tmp_path / (prefix + "_run")
    run.mkdir()
    shutil.copyfile(MINI_FIXTURE, run / (prefix + ".output_events.xml.gz"))
    _drt_cache, freight_cache = ensure_caches(run, prefix)

    assert len(freight_cache.read_text(encoding="utf-8").splitlines()) == 9  # sanity on the cache itself

    rows = extract(FIX, "DRT_TEST", freight_cache=freight_cache)
    stops = _series(rows, "freight_service_stops")
    assert stops == {8: 1, 9: 1}
    assert sum(stops.values()) == 2  # NOT 9 (the unfiltered line count)


def test_write(tmp_path):
    meta = parse_legacy_dir_name("DRT_BASELINE_13052025_married120_iter150_jsprit100")
    out = tmp_path / "kpi_timeseries.csv"
    write(extract(FIX, "DRT_TEST"), meta, out)
    lines = out.read_text(encoding="utf-8").splitlines()
    assert lines[0] == "run_id;series;hour;value;unit"
    assert lines[1].startswith("DRT_BASELINE_13052025_married120;")


def _seed_legs(dirpath, prefix, rows):
    (dirpath / (prefix + ".output_drt_legs_drt.csv")).write_text(
        "submissionTime;departureTime;personId;waitTime\n" + "".join(rows), encoding="utf-8")


def test_hourly_series_exclude_parcel_legs(tmp_path):
    """The hourly passenger charts must not count parcel_ phantom legs -- otherwise
    the Shared-Use dashboard's charts contradict its own (pax-only corrected) tiles.
    Fixture: 2 pax legs in hour 8, 3 parcel legs in the same hour."""
    _seed_legs(tmp_path, "SU", [
        "28000;28800;p1;100\n",
        "28000;28900;p2;200\n",
        "28000;29000;parcel_dhl_1_B2C;9999\n",
        "28000;29100;parcel_dhl_2_B2C;9999\n",
        "28000;29200;parcel_dhl_3_B2C;9999\n",
    ])

    ts = {(r["series"], r["hour"]): r["value"] for r in extract(tmp_path, "SU")}

    assert ts[("drt_rides", 8)] == 2
    assert ts[("drt_wait_mean", 8)] == pytest.approx(150.0)   # not dragged up by 9999
    # the mixed series survives alongside it, explicitly named
    assert ts[("drt_rides_incl_parcels", 8)] == 5


def test_incl_parcels_series_absent_without_parcels(tmp_path):
    """Baseline runs must keep byte-identical series names -- no spurious twins."""
    _seed_legs(tmp_path, "BASE", ["28000;28800;p1;100\n", "28000;28900;p2;200\n"])

    series = {r["series"] for r in extract(tmp_path, "BASE")}

    assert "drt_rides" in series
    assert not any(s.endswith("_incl_parcels") for s in series)


def test_rejection_series_excludes_parcel_retries(tmp_path):
    """A chi-starved parcel is rejected repeatedly; unfiltered, this series would
    measure parcel retries rather than passenger service."""
    (tmp_path / "REJ.output_drt_rejections_drt.csv").write_text(
        "time;personIds;requestId;cause\n"
        "28800;p1;drt_1;no_insertion_found\n"
        "28900;parcel_dhl_1_B2C;drt_2;no_insertion_found\n"
        "29000;parcel_dhl_1_B2C;drt_3;no_insertion_found\n",
        encoding="utf-8")

    ts = {(r["series"], r["hour"]): r["value"] for r in extract(tmp_path, "REJ")}

    assert ts[("drt_rejections", 8)] == 1
