# -*- coding: utf-8 -*-
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from events_cache import ensure_caches
from freight_events import FreightEvents
from geometry import (
    LinkGeo,
    reconstruct_drt_paths_detailed,
    douglas_peucker,
    drop_collinear,
    freight_used_links,
    load_link_geometry,
    polyline_runs,
    reconstruct_drt_paths,
)

FIXTURES = Path(__file__).parent / "fixtures" / "mini_events"
MINI_EVENTS = FIXTURES / "MINI.output_events.xml.gz"
MINI_NETWORK = FIXTURES / "MINI.output_network.xml.gz"

# A small synthetic drt cache exercising the occupancy transition that the
# frozen MINI fixture cannot: drt_veh_1 enters s1 at occ 0, boards a
# passenger (occ -> 1), then enters s2 at occ 1, then the passenger leaves
# (occ -> 0).
SYNTHETIC_DRT_CACHE = """<event time="10.0" type="entered link" link="s1" vehicle="drt_veh_1"/>
<event time="15.0" type="PersonEntersVehicle" person="p1" vehicle="drt_veh_1"/>
<event time="20.0" type="entered link" link="s2" vehicle="drt_veh_1"/>
<event time="30.0" type="PersonLeavesVehicle" person="p1" vehicle="drt_veh_1"/>
"""


def _make_mini_run(tmp_path):
    prefix = "MINI"
    run = tmp_path / (prefix + "_run")
    run.mkdir()
    shutil.copyfile(MINI_EVENTS, run / (prefix + ".output_events.xml.gz"))
    return run, prefix


def test_reconstruct_drt_paths_occupancy_transition(tmp_path):
    cache = tmp_path / "synthetic.drt_events_filtered.txt"
    cache.write_text(SYNTHETIC_DRT_CACHE, encoding="utf-8")

    veh_path, used_links = reconstruct_drt_paths(cache)

    assert veh_path["drt_veh_1"] == [("s1", 0), ("s2", 1)]
    assert used_links == {"s1", "s2"}


def test_reconstruct_drt_paths_driver_excluded_from_occupancy(tmp_path):
    # The driver (person == vehicle) must never affect occupancy.
    cache = tmp_path / "driver.drt_events_filtered.txt"
    cache.write_text(
        '<event time="1.0" type="PersonEntersVehicle" person="drt_veh_2" vehicle="drt_veh_2"/>\n'
        '<event time="2.0" type="entered link" link="s1" vehicle="drt_veh_2"/>\n',
        encoding="utf-8",
    )
    veh_path, _ = reconstruct_drt_paths(cache)
    assert veh_path["drt_veh_2"] == [("s1", 0)]


def test_reconstruct_drt_paths_smoke_on_mini_fixture(tmp_path):
    run, prefix = _make_mini_run(tmp_path)
    drt_cache, _ = ensure_caches(run, prefix)

    veh_path, used_links = reconstruct_drt_paths(drt_cache)

    assert veh_path["drt_veh_1"] == [("d1", 0)]
    assert veh_path["drt_freight_shared_1"] == [("l3", 0)]
    assert used_links == {"d1", "l3"}


def test_freight_used_links_unions_veh_links():
    fev = FreightEvents()
    fev.veh_links["veh_a"] = [("l1", 10.0), ("l2", 20.0)]
    fev.veh_links["veh_b"] = [("l2", 30.0), ("l3", 40.0)]

    assert freight_used_links(fev) == {"l1", "l2", "l3"}


def test_load_link_geometry_used_links_present_with_wgs84_coords():
    link_geo = load_link_geometry(MINI_NETWORK, {"l1", "l2"})

    assert set(link_geo.keys()) == {"l1", "l2"}
    for lid in ("l1", "l2"):
        g = link_geo[lid]
        assert isinstance(g, LinkGeo)
        assert 14.0 < g.flon < 15.0
        assert 51.0 < g.flat < 52.0
        assert 14.0 < g.tlon < 15.0
        assert 51.0 < g.tlat < 52.0
        # 5 decimal places
        assert g.flon == round(g.flon, 5)
        assert g.flat == round(g.flat, 5)
        assert g.tlon == round(g.tlon, 5)
        assert g.tlat == round(g.tlat, 5)
        assert g.length_m > 0


def test_load_link_geometry_excludes_unused_link():
    link_geo = load_link_geometry(MINI_NETWORK, {"l1", "l2"})
    assert "l9" not in link_geo


def test_load_link_geometry_length_m_from_projected_coords():
    link_geo = load_link_geometry(MINI_NETWORK, {"l1"})
    # n1=(864000,5705000) -> n2=(864500,5705100): hypot(500,100)
    import math
    expected = math.hypot(500.0, 100.0)
    assert abs(link_geo["l1"].length_m - expected) < 1e-6


def test_douglas_peucker_drops_near_collinear_midpoint():
    pts = [(0, 0), (0.5, 1e-9), (1, 0)]
    out = douglas_peucker(pts, 1e-6)
    assert out == [(0, 0), (1, 0)]


def test_douglas_peucker_keeps_significant_midpoint():
    pts = [(0, 0), (0.5, 1.0), (1, 0)]
    out = douglas_peucker(pts, 1e-6)
    assert out == [(0, 0), (0.5, 1.0), (1, 0)]


def test_drop_collinear_removes_exactly_collinear_point():
    pts = [(0, 0), (0.5, 0.0), (1, 0)]
    out = drop_collinear(pts)
    assert out == [(0, 0), (1, 0)]


def test_polyline_runs_chains_consecutive_links_into_one_run():
    link_geo = load_link_geometry(MINI_NETWORK, {"l1", "l2"})
    path = [("l1", 0), ("l2", 0)]

    runs = polyline_runs(path, link_geo)

    assert len(runs) == 1
    run = runs[0]
    assert len(run) == 3
    n1 = (link_geo["l1"].flat, link_geo["l1"].flon)
    n2 = (link_geo["l1"].tlat, link_geo["l1"].tlon)
    n3 = (link_geo["l2"].tlat, link_geo["l2"].tlon)
    assert run == [n1, n2, n3]


def test_polyline_runs_breaks_when_links_not_chained():
    # A single-link path repeated with no shared node -> two separate runs.
    link_geo = load_link_geometry(MINI_NETWORK, {"l1", "l2"})
    # l2's "to" (n3) does not match l1's "from" (n1), so l2 then l1 must
    # produce two runs, not one.
    path = [("l2", 0), ("l1", 0)]

    runs = polyline_runs(path, link_geo)

    assert len(runs) == 2
    assert len(runs[0]) == 2
    assert len(runs[1]) == 2


# --- detailed variant (parcel/pax split + timestamps) ---------------------

# 1c-style cache: a passenger AND a parcel-person board the same vehicle.
# The legacy single counter cannot tell them apart -- that is the point.
SHAREDUSE_DRT_CACHE = """<event time="10.0" type="entered link" link="s1" vehicle="drt_veh_9"/>
<event time="12.0" type="PersonEntersVehicle" person="p42" vehicle="drt_veh_9"/>
<event time="14.0" type="PersonEntersVehicle" person="parcel_7" vehicle="drt_veh_9"/>
<event time="20.0" type="entered link" link="s2" vehicle="drt_veh_9"/>
<event time="25.0" type="PersonLeavesVehicle" person="parcel_7" vehicle="drt_veh_9"/>
<event time="30.0" type="entered link" link="s3" vehicle="drt_veh_9"/>
"""


def test_detailed_splits_parcels_from_passengers(tmp_path):
    """1c models parcels as persons (SharedUse.PARCEL_PERSON_PREFIX), so a
    single occupancy counter conflates freight and pax. The split comes free
    from the person id."""
    cache = tmp_path / "shareduse.drt_events_filtered.txt"
    cache.write_text(SHAREDUSE_DRT_CACHE, encoding="utf-8")
    veh_path, used = reconstruct_drt_paths_detailed(cache)
    assert veh_path["drt_veh_9"] == [("s1", 0, 0, 10.0),
                                     ("s2", 1, 1, 20.0),
                                     ("s3", 1, 0, 30.0)]
    assert used == {"s1", "s2", "s3"}


def test_plain_variant_is_exactly_the_sum_projection(tmp_path):
    """The 2-tuple entry point must stay bit-identical to the legacy
    semantics (occupancy = everyone aboard), because maps/veh_km/occ_km all
    consume it. Here: the mixed 1c cache projects to 0, 2, 1."""
    cache = tmp_path / "shareduse.drt_events_filtered.txt"
    cache.write_text(SHAREDUSE_DRT_CACHE, encoding="utf-8")
    plain, used_p = reconstruct_drt_paths(cache)
    detailed, used_d = reconstruct_drt_paths_detailed(cache)
    assert plain["drt_veh_9"] == [("s1", 0), ("s2", 2), ("s3", 1)]
    assert used_p == used_d
    for v, path in detailed.items():
        assert plain[v] == [(lid, pax + par) for lid, pax, par, _t in path]


def test_detailed_carries_event_times_for_task_window_intersection(tmp_path):
    """The modular (1d) freight arm intersects the link path with
    MODULAR_FREIGHT_DRIVE task windows, so the entry time must survive."""
    cache = tmp_path / "synthetic.drt_events_filtered.txt"
    cache.write_text(SYNTHETIC_DRT_CACHE, encoding="utf-8")
    veh_path, _ = reconstruct_drt_paths_detailed(cache)
    assert [t for _l, _p, _c, t in veh_path["drt_veh_1"]] == [10.0, 20.0]


def test_project_paths_is_the_one_projection_both_entry_points_use(tmp_path):
    """build_kpis reconstructs ONCE (detailed) and projects for the legacy
    2-tuple consumers, so the projection rule now has two call sites. It
    must live in one place: a second inline `pax + parcels` copy in
    build_kpis is exactly how the two semantics drift apart."""
    import geometry
    cache = tmp_path / "shareduse.drt_events_filtered.txt"
    cache.write_text(SHAREDUSE_DRT_CACHE, encoding="utf-8")
    detailed, _ = reconstruct_drt_paths_detailed(cache)
    plain, _ = reconstruct_drt_paths(cache)
    assert geometry.project_paths(detailed) == plain
