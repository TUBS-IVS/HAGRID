# -*- coding: utf-8 -*-
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from events_cache import ensure_caches
from freight_events import FreightEvents, parse_freight_cache

MINI_FIXTURE = Path(__file__).parent / "fixtures" / "mini_events" / "MINI.output_events.xml.gz"
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
