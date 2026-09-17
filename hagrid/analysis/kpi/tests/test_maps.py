# -*- coding: utf-8 -*-
"""Tests for maps.py (v2 KPI dashboard, Plan D Task 6 -- DRT map layers).

Fixture strategy: the mini_events DRT cache links ("d1", "l3") are NOT in the
network fixture ("l1/l2/l9"), so the vehicles layer is NOT driven from the
real events cache here. Instead veh_path/link_geo (both plain params of
build_map_data) are built as ALIGNED, hand-authored inputs against the real
MINI network fixture (see geometry.py's own tests for that fixture's
node/link layout). A stub MINI.output_drt_legs_drt.csv is written directly
into the tmp run dir per test.
"""
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import carriers_parse
import geometry
import maps
from events_cache import ensure_caches
from freight_events import FreightEvents, parse_freight_cache

FIXTURES = Path(__file__).parent / "fixtures" / "mini_events"
MINI_NETWORK = FIXTURES / "MINI.output_network.xml.gz"
MINI_EVENTS = FIXTURES / "MINI.output_events.xml.gz"
CARRIERS_FIXTURE = (Path(__file__).parent / "fixtures" / "mini_lmd"
                    / "MINI.output_carriers.xml.gz")
FREIGHT_VEH = "freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0"

# Coordinates are EPSG:25832, near Hoyerswerda (same order of magnitude as
# the MINI network fixture's n1/n2/n3). departureTime is already ascending
# with the CSV row order so "departure order" == "row order" here.
LEGS_HEADER = (
    "submissionTime;departureTime;personId;requestId;vehicleId;fromLinkId;fromX;fromY;"
    "toLinkId;toX;toY;waitTime;arrivalTime;inVehicleTravelTime;travelDistance_m;"
    "directTravelDistance_m;fareForLeg;earliestDepartureTime;latestDepartureTime;latestArrivalTime\n"
)
LEGS_ROWS = [
    "0;28800;p1;r1;drt_veh_1;l1;864050;5705010;l2;864900;5705080;120;29000;80;500;450;0;0;0;0\n",
    "60;29400;p2;r2;drt_veh_1;l1;864100;5705020;l2;864800;5705070;90;29600;70;480;440;0;0;0;0\n",
    "120;30000;p3;r3;drt_veh_1;l1;864150;5705030;l2;864700;5705060;60;30150;60;400;380;0;0;0;0\n",
]
LEGS_CSV = LEGS_HEADER + "".join(LEGS_ROWS)


def _make_run(tmp_path, legs_csv=LEGS_CSV, name="MINI_run"):
    run = tmp_path / name
    run.mkdir()
    if legs_csv is not None:
        (run / "MINI.output_drt_legs_drt.csv").write_text(legs_csv, encoding="utf-8")
    return run


def _aligned_inputs():
    """veh_path/link_geo built directly against the MINI network fixture so
    the vehicle's [(link, occ)] path always resolves real WGS84 geometry --
    sidesteps the mini_events-cache/network fixture mismatch (see module
    docstring)."""
    link_geo = geometry.load_link_geometry(MINI_NETWORK, {"l1", "l2"})
    veh_path = {"drt_veh_1": [("l1", 0), ("l2", 1)]}
    return veh_path, link_geo


def test_vehicles_segs_grouped_by_occupancy_level_with_wgs84_coords(tmp_path):
    run = _make_run(tmp_path)
    veh_path, link_geo = _aligned_inputs()

    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo)

    segs = data["drt"]["vehicles"]["drt_veh_1"]["segs"]
    # occ level 0 comes from l1, occ level 1 from l2 -- both keys are STRINGS.
    assert set(segs.keys()) == {"0", "1"}
    for level in ("0", "1"):
        runs = segs[level]
        assert isinstance(runs, list) and len(runs) >= 1
        for run_pts in runs:
            assert isinstance(run_pts, list) and len(run_pts) >= 2
            for lat, lon in run_pts:
                assert 51.0 < lat < 52.0
                assert 14.0 < lon < 15.0
                assert lat == round(lat, 5)
                assert lon == round(lon, 5)


def test_vehicle_with_no_resolvable_links_keeps_empty_segs():
    veh_path = {"ghost_veh": [("nowhere", 0)]}
    link_geo = geometry.load_link_geometry(MINI_NETWORK, {"l1", "l2"})

    data = maps.build_map_data("does-not-matter", "MINI", veh_path=veh_path, link_geo=link_geo)

    assert data["drt"]["vehicles"]["ghost_veh"]["segs"] == {}


def test_n_sample_caps_pu_and_do_lists(tmp_path):
    run = _make_run(tmp_path)
    veh_path, link_geo = _aligned_inputs()

    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo, n_sample=1)

    assert len(data["drt"]["pu"]) == 1
    assert len(data["drt"]["do"]) == 1


def test_pu_do_coords_are_wgs84_rounded_5dp(tmp_path):
    run = _make_run(tmp_path)
    veh_path, link_geo = _aligned_inputs()

    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo)

    assert len(data["drt"]["pu"]) == 3
    assert len(data["drt"]["do"]) == 3
    for lat, lon in data["drt"]["pu"] + data["drt"]["do"]:
        assert 51.0 < lat < 52.0
        assert 14.0 < lon < 15.0
        assert lat == round(lat, 5)
        assert lon == round(lon, 5)


def test_per_vehicle_stops_numbered_in_departure_order(tmp_path):
    run = _make_run(tmp_path)
    veh_path, link_geo = _aligned_inputs()

    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo)

    stops = data["drt"]["vehicles"]["drt_veh_1"]["stops"]
    assert len(stops) == 6  # 3 legs x (pu + do)
    pu_ns = [s["n"] for s in stops if s["kind"] == "pu"]
    do_ns = [s["n"] for s in stops if s["kind"] == "do"]
    assert pu_ns == [1, 2, 3]
    assert do_ns == [1, 2, 3]
    assert stops[0]["kind"] == "pu"
    assert stops[0]["t"] == 28800
    assert all(isinstance(s["lat"], float) and isinstance(s["lon"], float) for s in stops)


def test_missing_optional_layers_are_absent_without_raising(tmp_path):
    # tmp_path has no hagrid-input/hagrid-output siblings -> shp/depots are
    # absent. Must not raise, and the keys must be omitted.
    run = _make_run(tmp_path)
    veh_path, link_geo = _aligned_inputs()

    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo)

    assert "service_area" not in data["drt"]
    assert "depots" not in data["drt"]


def test_depots_happy_path_parses_real_csv_and_transforms_coords(tmp_path):
    # run_dir sits two levels below the hagrid-input tree (matches the real
    # <lausitz-run>/hagrid-output/<prefix> layout), so build the fixture
    # relative to run_dir.parent.parent, not tmp_path directly.
    run = tmp_path / "runs" / "hagrid-output" / "MINI_run"
    run.mkdir(parents=True)
    hubs_dir = run.parent.parent / "hagrid-input" / "lausitz" / "hubs"
    hubs_dir.mkdir(parents=True)
    (hubs_dir / "lmd-depots.csv").write_text(
        "provider;x;y\ndhl;864000;5705000\nhermes;865000;5705050\n", encoding="utf-8"
    )

    data = maps.build_map_data(run, "MINI")

    assert data["drt"]["depots"] == [
        {"name": "dhl", "lat": 51.37924, "lon": 14.23179},
        {"name": "hermes", "lat": 51.37905, "lon": 14.24615},
    ]


def test_lmd_key_present_but_empty(tmp_path):
    run = _make_run(tmp_path)
    veh_path, link_geo = _aligned_inputs()

    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo)

    assert data["lmd"] == {}


def test_cap_defaults_to_8_when_no_kpi_present(tmp_path):
    run = _make_run(tmp_path)
    veh_path, link_geo = _aligned_inputs()

    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo)

    assert data["drt"]["cap"] == 8


def test_missing_legs_csv_yields_empty_pu_do_and_stops(tmp_path):
    run = _make_run(tmp_path, legs_csv=None, name="MINI_norun")
    veh_path, link_geo = _aligned_inputs()

    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo)

    assert data["drt"]["pu"] == []
    assert data["drt"]["do"] == []
    assert data["drt"]["vehicles"]["drt_veh_1"]["stops"] == []


def test_no_veh_path_or_link_geo_does_not_raise(tmp_path):
    run = _make_run(tmp_path)

    data = maps.build_map_data(run, "MINI")

    assert data["drt"]["vehicles"] == {}
    assert data["lmd"] == {}


def test_center_defaults_to_hoyerswerda_when_nothing_available(tmp_path):
    run = _make_run(tmp_path, legs_csv=None, name="MINI_norun")

    data = maps.build_map_data(run, "MINI")

    assert data["center"] == [51.44, 14.24]


def test_write_produces_valid_json_roundtrip(tmp_path):
    run = _make_run(tmp_path)
    veh_path, link_geo = _aligned_inputs()
    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo)

    out_file = tmp_path / "map_data.json"
    maps.write(data, out_file)

    loaded = json.loads(out_file.read_text(encoding="utf-8"))
    assert loaded == data


# ---------------------------------------------------------------------------
# Plan D Task 7: LMD map layers (tours/stops/heat/depots)
# ---------------------------------------------------------------------------
# fev comes from the REAL mini_events freight cache (parsed via
# events_cache.ensure_caches + freight_events.parse_freight_cache), same as
# test_freight_events.py: the dhl vehicle's veh_links == [("l1", 29000.0),
# ("l2", 29100.0)], service_starts == [29400.0, 32400.0]; the shared
# drt_freight_shared_1 vehicle's veh_links == [("l3", 29050.0)] (l3 is NOT in
# the network fixture -> no geometry). carriers come from the real mini_lmd
# carriers fixture (dhl tour "0", services s0=60/s1=30, vehicle resolves to
# FREIGHT_VEH via event_vehicle_id). link_geo is loaded from the real MINI
# network fixture, restricted to {"l1", "l2"} (l3 deliberately excluded, same
# as the module docstring's DRT fixture strategy).

def _mini_fev(tmp_path):
    prefix = "MINI"
    run = tmp_path / (prefix + "_iter1_jsprit1")
    run.mkdir()
    shutil.copyfile(MINI_EVENTS, run / (prefix + ".output_events.xml.gz"))
    _drt, frt = ensure_caches(run, prefix)
    return parse_freight_cache(frt)


def _mini_carriers():
    return carriers_parse.parse_carriers(CARRIERS_FIXTURE)


def _mini_link_geo():
    return geometry.load_link_geometry(MINI_NETWORK, {"l1", "l2"})


def test_lmd_tours_one_tour_for_dhl_vehicle_with_provider_and_run(tmp_path):
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    link_geo = _mini_link_geo()

    data = maps.build_map_data("does-not-matter", "MINI", fev=fev, carriers=carriers,
                                excluded=set(), link_geo=link_geo)

    tours = data["lmd"]["tours"]
    assert len(tours) == 1
    tour = tours[0]
    assert tour["veh"] == FREIGHT_VEH
    assert tour["provider"] == "dhl"
    assert tour["carrier"] == "dhl"
    assert len(tour["runs"]) == 1
    run_pts = tour["runs"][0]
    assert len(run_pts) == 3  # l1 (2 pts) + l2 (1 more pt) chained into one run
    for lat, lon in run_pts:
        assert 51.0 < lat < 52.0
        assert 14.0 < lon < 15.0
        assert lat == round(lat, 5)
        assert lon == round(lon, 5)


def test_lmd_tours_excludes_vehicle_in_excluded_set(tmp_path):
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    link_geo = _mini_link_geo()

    data = maps.build_map_data("does-not-matter", "MINI", fev=fev, carriers=carriers,
                                excluded={FREIGHT_VEH}, link_geo=link_geo)

    assert data["lmd"]["tours"] == []


def test_lmd_tours_shared_vehicle_with_no_carrier_match_omitted(tmp_path):
    # drt_freight_shared_1 is in fev.veh_links but resolves to no carrier
    # tour -> not a real LMD freight tour, must be omitted entirely.
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    link_geo = _mini_link_geo()

    data = maps.build_map_data("does-not-matter", "MINI", fev=fev, carriers=carriers,
                                excluded=set(), link_geo=link_geo)

    vehs = {t["veh"] for t in data["lmd"]["tours"]}
    assert "drt_freight_shared_1" not in vehs
    assert len(data["lmd"]["tours"]) == 1


def test_lmd_tours_keeps_tour_with_empty_runs_when_no_geometry(tmp_path):
    # Matched tour (carrier lookup succeeds) whose only link (l3) has no
    # geometry -> kept as a real tour with runs: [].
    fev = FreightEvents(veh_links={FREIGHT_VEH: [("l3", 29000.0)]})
    carriers = _mini_carriers()
    link_geo = _mini_link_geo()  # only has l1/l2, not l3

    data = maps.build_map_data("does-not-matter", "MINI", fev=fev, carriers=carriers,
                                excluded=set(), link_geo=link_geo)

    tours = data["lmd"]["tours"]
    assert len(tours) == 1
    assert tours[0]["veh"] == FREIGHT_VEH
    assert tours[0]["runs"] == []


def test_lmd_stops_demand_in_order_at_link_midpoint(tmp_path):
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    link_geo = _mini_link_geo()

    data = maps.build_map_data("does-not-matter", "MINI", fev=fev, carriers=carriers,
                                excluded=set(), link_geo=link_geo)

    stops = data["lmd"]["stops"]
    assert len(stops) == 2
    assert [s["demand"] for s in stops] == [60, 30]
    assert [s["t"] for s in stops] == [29400, 32400]

    g2 = link_geo["l2"]
    expected_lat = round((g2.flat + g2.tlat) / 2, 5)
    expected_lon = round((g2.flon + g2.tlon) / 2, 5)
    for s in stops:
        # at both t=29400 and t=32400 the nearest preceding entered-link is
        # l2 (entered at 29100) -- both stops sit at l2's midpoint.
        assert s["lat"] == expected_lat
        assert s["lon"] == expected_lon
        assert s["provider"] == "dhl"
        assert s["veh"] == FREIGHT_VEH
        assert isinstance(s["lat"], float) and isinstance(s["lon"], float)


def test_lmd_stops_excludes_vehicle_in_excluded_set(tmp_path):
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()
    link_geo = _mini_link_geo()

    data = maps.build_map_data("does-not-matter", "MINI", fev=fev, carriers=carriers,
                                excluded={FREIGHT_VEH}, link_geo=link_geo)

    assert data["lmd"]["stops"] == []


def test_lmd_stops_skipped_when_no_preceding_link_known(tmp_path):
    # service start earlier than any entered-link time -> no preceding link
    # known yet -> the stop is skipped (not emitted with bogus coords).
    fev = FreightEvents(veh_links={FREIGHT_VEH: [("l1", 29000.0), ("l2", 29100.0)]},
                        service_starts={FREIGHT_VEH: [100.0]})
    carriers = _mini_carriers()
    link_geo = _mini_link_geo()

    data = maps.build_map_data("does-not-matter", "MINI", fev=fev, carriers=carriers,
                                excluded=set(), link_geo=link_geo)

    assert data["lmd"]["stops"] == []


def test_lmd_heat_counts_link_enters_with_weight_one(tmp_path):
    fev = _mini_fev(tmp_path)
    link_geo = _mini_link_geo()

    data = maps.build_map_data("does-not-matter", "MINI", fev=fev, carriers=[],
                                excluded=set(), link_geo=link_geo)

    heat = data["lmd"]["heat"]
    assert len(heat) == 2  # l1, l2 -- l3 has no geometry, skipped
    expected = set()
    for lid in ("l1", "l2"):
        g = link_geo[lid]
        expected.add((round((g.flat + g.tlat) / 2, 5), round((g.flon + g.tlon) / 2, 5)))
    seen = set()
    for lat, lon, w in heat:
        assert (lat, lon) in expected
        assert w == 1
        seen.add((lat, lon))
    assert seen == expected


def test_lmd_depots_shared_with_drt_depots(tmp_path):
    run = tmp_path / "runs" / "hagrid-output" / "MINI_run"
    run.mkdir(parents=True)
    hubs_dir = run.parent.parent / "hagrid-input" / "lausitz" / "hubs"
    hubs_dir.mkdir(parents=True)
    (hubs_dir / "lmd-depots.csv").write_text(
        "provider;x;y\ndhl;864000;5705000\n", encoding="utf-8"
    )
    fev = _mini_fev(tmp_path)
    carriers = _mini_carriers()

    data = maps.build_map_data(run, "MINI", fev=fev, carriers=carriers, excluded=set())

    assert data["lmd"]["depots"] == data["drt"]["depots"]
    assert data["lmd"]["depots"] == [{"name": "dhl", "lat": 51.37924, "lon": 14.23179}]


def test_lmd_stays_empty_dict_when_fev_not_supplied(tmp_path):
    # Already covered for the veh_path/link_geo-only call by
    # test_lmd_key_present_but_empty; this pins the same contract when
    # carriers/excluded are supplied but fev is not.
    run = _make_run(tmp_path)
    veh_path, link_geo = _aligned_inputs()
    carriers = _mini_carriers()

    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo,
                                carriers=carriers, excluded=set())

    assert data["lmd"] == {}
