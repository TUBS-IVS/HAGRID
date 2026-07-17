# -*- coding: utf-8 -*-
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import carriers_parse
from events_cache import ensure_caches
from freight_events import (FreightEvents, hourly_series,
                             parcels_per_hour_by_provider, parse_freight_cache)

MINI_FIXTURE = Path(__file__).parent / "fixtures" / "mini_events" / "MINI.output_events.xml.gz"
CARRIERS_FIXTURE = (Path(__file__).parent / "fixtures" / "mini_lmd"
                    / "MINI.output_carriers.xml.gz")
VEH = "freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0"


def _mini_freight_cache(tmp_path):
    prefix = "MINI"
    run = tmp_path / (prefix + "_iter1_jsprit1")
    run.mkdir()
    shutil.copyfile(MINI_FIXTURE, run / (prefix + ".output_events.xml.gz"))
    _drt, frt = ensure_caches(run, prefix)
    return frt


def test_parses_service_starts(tmp_path):
    frt = _mini_freight_cache(tmp_path)
    fev = parse_freight_cache(frt)
    assert isinstance(fev, FreightEvents)
    assert fev.service_starts[VEH] == [29400.0, 32400.0]


def test_parses_depot_departures(tmp_path):
    frt = _mini_freight_cache(tmp_path)
    fev = parse_freight_cache(frt)
    assert fev.depot_departures[VEH] == [28800.0]


def test_parses_depot_arrivals(tmp_path):
    frt = _mini_freight_cache(tmp_path)
    fev = parse_freight_cache(frt)
    assert fev.depot_arrivals[VEH] == [36000.0]


def test_parses_veh_links(tmp_path):
    frt = _mini_freight_cache(tmp_path)
    fev = parse_freight_cache(frt)
    assert fev.veh_links[VEH] == [("l1", 29000.0), ("l2", 29100.0)]


def test_veh_links_keys_on_vehicle_not_person(tmp_path):
    # "entered link" events key on `vehicle`, not `person` -- the shared
    # drt_freight_shared_1 vehicle line (present in the same cache since
    # membership is non-exclusive, see events_cache.py) must land under its
    # own vehicle key rather than being dropped or merged into VEH.
    frt = _mini_freight_cache(tmp_path)
    fev = parse_freight_cache(frt)
    assert fev.veh_links["drt_freight_shared_1"] == [("l3", 29050.0)]


# ---------------------------------------------------------------------------
# Task 3: event-derived freight hourly series
# ---------------------------------------------------------------------------

def _mini_fev(tmp_path):
    frt = _mini_freight_cache(tmp_path)
    return parse_freight_cache(frt)


def _mini_carriers():
    return carriers_parse.parse_carriers(CARRIERS_FIXTURE)


def test_parcels_per_hour_by_provider_real_fixture(tmp_path):
    # dhl tour v0 has services s0(60)+s1(30) and exactly 2 service-start
    # events (29400 -> h8, 32400 -> h9) -> 1:1 zip by stop order.
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    out = parcels_per_hour_by_provider(fev, carriers, excluded=set())
    assert out["dhl"] == {8: 60, 9: 30}


def test_parcels_per_hour_by_provider_mismatch_conserves_total():
    # stub FreightEvents: dhl v0 vehicle has only 1 start time, but its
    # stop_demands (s0=60 + s1=30) sum to 90 -> mismatch path spreads the
    # total evenly over the available starts, conserving the total.
    fev = FreightEvents(service_starts={VEH: [29400.0]})
    carriers = _mini_carriers()
    out = parcels_per_hour_by_provider(fev, carriers, excluded=set())
    assert out["dhl"] == {8: 90}


def test_parcels_per_hour_by_provider_excluded_contributes_nothing(tmp_path):
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    out = parcels_per_hour_by_provider(fev, carriers, excluded={VEH})
    assert out.get("dhl", {}) == {}


def test_hourly_series_parcels_rows(tmp_path):
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    rows = hourly_series(fev, carriers, excluded=set())
    parcels = {r["hour"]: r["value"] for r in rows if r["series"] == "freight_parcels_h_dhl"}
    assert parcels == {8: 60, 9: 30}
    units = {r["unit"] for r in rows if r["series"] == "freight_parcels_h_dhl"}
    assert units == {"parcels/h"}


def test_hourly_series_depot_departures_and_arrivals(tmp_path):
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    rows = hourly_series(fev, carriers, excluded=set())
    dep = {r["hour"]: r["value"] for r in rows if r["series"] == "freight_depot_departures"}
    assert dep == {8: 1}
    arr = {r["hour"]: r["value"] for r in rows if r["series"] == "freight_depot_arrivals"}
    assert arr == {10: 1}


def test_hourly_series_active_vehicles_span_8_to_10_in_5min_steps(tmp_path):
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    rows = hourly_series(fev, carriers, excluded=set())
    active = [r for r in rows if r["series"] == "freight_active_vehicles_dhl"]
    hours = sorted(round(r["hour"], 4) for r in active)
    expected = [round(8.0 + i / 12.0, 4) for i in range(24)]  # 8.0 .. 9.9167, half-open before 10.0
    assert hours == expected
    assert all(r["value"] == 1 for r in active)
    assert all(r["unit"] == "vehicles" for r in active)


def test_hourly_series_active_vehicles_skips_excluded(tmp_path):
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    rows = hourly_series(fev, carriers, excluded={VEH})
    active = [r for r in rows if r["series"] == "freight_active_vehicles_dhl"]
    assert active == []
