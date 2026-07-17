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
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import geometry
import maps

FIXTURES = Path(__file__).parent / "fixtures" / "mini_events"
MINI_NETWORK = FIXTURES / "MINI.output_network.xml.gz"

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
    # tmp_path has no hagrid-input/hagrid-output siblings -> shp/depots/rail
    # schedule are all absent. Must not raise, and the keys must be omitted.
    run = _make_run(tmp_path)
    veh_path, link_geo = _aligned_inputs()

    data = maps.build_map_data(run, "MINI", veh_path=veh_path, link_geo=link_geo)

    assert "service_area" not in data["drt"]
    assert "depots" not in data["drt"]
    assert "rail_stops" not in data["drt"]


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
    assert loaded["center"] == data["center"]
    assert loaded["lmd"] == {}
    assert loaded["drt"]["cap"] == 8
