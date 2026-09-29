"""Demand-driven growth of the pickup network (spec 2026-09-28, section 4.3)."""

import geopandas as gpd
import numpy as np
import pandas as pd
import pytest
from shapely.geometry import Point

from hagrid_demand.baseline.network_growth import (candidate_weights, carrier_ooh_demand, group_targets, grow_network,
                                                   network_groups, plan_network)
from hagrid_demand.baseline.out_of_home import load_out_of_home_inputs, out_of_home_share, resolve_out_of_home

CRS = "EPSG:25832"
SHARED = "DPD|GLS|Hermes|UPS"
LOCKERS, BOXES = ("locker", "DHL"), ("shared_locker", SHARED)


def _cfg(**changes) -> dict:
    return {**resolve_out_of_home({"enabled": True})["network_growth"], **changes}


def _points() -> gpd.GeoDataFrame:
    """Reference network: three DHL Packstations and two shared boxes."""
    return gpd.GeoDataFrame({"point_id": ["osm:n1", "osm:n2", "osm:n3", "osm:n4", "osm:n5"],
                             "kind": ["locker", "locker", "locker", "shared_locker", "shared_locker"],
                             "carriers": ["DHL", "DHL", "DHL", SHARED, SHARED],
                             "brand": ["DHL Packstation"] * 3 + ["Myflexbox"] * 2, "synthetic": [False] * 5,
                             "compartments": [np.nan] * 5, "context": ["other"] * 5},
                            geometry=[Point(0, 0), Point(2000, 0), Point(0, 1600), Point(1000, 800), Point(2000, 1600)], crs=CRS)


def _candidates() -> gpd.GeoDataFrame:
    """30 retail POIs on a 6 x 5 grid with 400 m spacing, offset from the reference points."""
    types = [("shop", "supermarket"), ("shop", "kiosk"), ("amenity", "fuel"), ("shop", "bakery"), ("shop", "convenience")]
    rows = [{"osm_id": f"poi{index}", "shop": value if column == "shop" else None, "amenity": value if column == "amenity" else None}
            for index, (column, value) in enumerate(types[index % len(types)] for index in range(30))]
    return gpd.GeoDataFrame(rows, geometry=[Point(200 + 400 * (index % 6), 200 + 400 * (index // 6)) for index in range(30)], crs=CRS)


def _demand() -> tuple[np.ndarray, np.ndarray]:
    """50 B2C demand sites: a dense cluster in the north-east, a thin spread elsewhere."""
    rng = np.random.default_rng(0)
    cluster = rng.normal((2100., 1700.), 120., size=(40, 2))
    spread = np.column_stack([np.linspace(0., 2200., 10), np.linspace(1800., 0., 10)])
    return np.vstack([cluster, spread]), np.concatenate([np.full(40, 100.), np.full(10, 1.)])


def _carrier_demand(factor: float) -> dict[str, float]:
    return {"DHL": 100. * factor, "DPD": 10. * factor, "GLS": 10. * factor, "Hermes": 10. * factor, "UPS": 10. * factor}


def _plan(years, demand, cfg=None, seed=11, candidates=None):
    xy, weights = _demand()
    return plan_network(_points(), years, demand, _candidates() if candidates is None else candidates,
                        {year: xy for year in years}, {year: weights for year in years}, cfg or _cfg(), seed)


def _three_pois() -> gpd.GeoDataFrame:
    return gpd.GeoDataFrame({"osm_id": ["a", "b", "c"], "shop": ["supermarket", None, "bakery"], "amenity": [None, "fuel", None]},
                            geometry=[Point(1000, 400), Point(1400, 400), Point(1000, 1200)], crs=CRS)


def test_packaged_defaults_follow_the_spec():
    defaults = load_out_of_home_inputs()["network_growth"]
    assert {key: defaults[key] for key in ("enabled", "reference_year", "elasticity", "resize_existing", "demand_radius_m", "gap_scale_m",
                                           "min_spacing_m", "off_preference_weight")} == {
        "enabled": True, "reference_year": 2025, "elasticity": .6, "resize_existing": True, "demand_radius_m": 500,
        "gap_scale_m": 600, "min_spacing_m": 150, "off_preference_weight": .25}
    assert defaults["candidate_types"] == {"shop": ["supermarket", "convenience", "kiosk", "chemist", "newsagent", "tobacco",
                                                    "variety_store", "bakery"], "amenity": ["fuel"]}
    assert defaults["kind_preferences"] == {
        "locker": {"shop": ["supermarket", "convenience"], "amenity": ["fuel"]},
        "shared_locker": {"shop": ["supermarket"], "amenity": ["fuel"]},
        "counter": {"shop": ["kiosk", "convenience", "newsagent", "tobacco", "chemist", "variety_store", "bakery"]}}


def test_network_groups_are_the_kind_and_carrier_sets_of_the_reference_network():
    assert network_groups(_points()) == [LOCKERS, BOXES]


def test_carrier_ooh_demand_multiplies_b2c_volume_carrier_share_and_ooh_share():
    inputs = load_out_of_home_inputs()
    sites = pd.DataFrame({"year": [2026, 2026, 2026, 2027], "site_id": ["a", "b", "c", "a"],
                          "segment": ["private", "private", "business", "private"], "annual_expected": [100., 300., 500., 999.]})
    profiles = pd.DataFrame({"year": [2026, 2026, 2026, 2027], "segment": ["private", "private", "business", "private"],
                             "carrier": ["DHL", "Hermes", "DHL", "DHL"], "share": [.6, .4, 1., 1.]})
    demand = carrier_ooh_demand(sites, profiles, 2026, inputs)
    assert set(demand) == {"DHL", "Hermes"}
    assert demand["DHL"] == pytest.approx(400. * .6 * out_of_home_share(2026, "DHL", inputs))
    assert demand["Hermes"] == pytest.approx(400. * .4 * out_of_home_share(2026, "Hermes", inputs))


def test_group_targets_follow_elasticity_and_never_shrink():
    reference = {LOCKERS: 10, ("counter", "Amazon"): 3}
    demand_ref = {LOCKERS: 100., ("counter", "Amazon"): 50.}
    grown = group_targets(reference, demand_ref, {LOCKERS: 150., ("counter", "Amazon"): 60.}, reference, .6)
    # 10 x 1.5^0.6 = 12.75 -> 13; 3 x 1.2^0.6 = 3.34 -> 3 (rounded to whole stations)
    assert grown == {LOCKERS: 13, ("counter", "Amazon"): 3}
    assert all(isinstance(value, int) for value in grown.values())
    assert group_targets(reference, demand_ref, {LOCKERS: 100., ("counter", "Amazon"): 75.}, reference, .6)[("counter", "Amazon")] == 4
    # falling demand never takes stations away
    assert group_targets(reference, demand_ref, {LOCKERS: 40., ("counter", "Amazon"): 10.}, grown, .6) == grown
    # elasticity 1 is proportional, elasticity 0 keeps the reference count
    assert group_targets(reference, demand_ref, {LOCKERS: 200., ("counter", "Amazon"): 50.}, reference, 1.)[LOCKERS] == 20
    assert group_targets(reference, demand_ref, {LOCKERS: 200., ("counter", "Amazon"): 50.}, reference, 0.)[LOCKERS] == 10
    # without reference demand there is nothing to scale: the previous count stays
    assert group_targets({LOCKERS: 4}, {LOCKERS: 0.}, {LOCKERS: 80.}, {LOCKERS: 5}, .6) == {LOCKERS: 5}


def test_targets_use_reference_network_when_reference_year_not_simulated():
    demand = {2025: _carrier_demand(1.), 2026: _carrier_demand(2.), 2027: _carrier_demand(3.)}
    network, status = _plan([2026, 2027], demand)
    assert status["reference_year"] == 2025 and status["growth_years"] == [2026, 2027]
    assert status["reference_points"] == {"locker:DHL": 3, f"shared_locker:{SHARED}": 2}
    years = status["years"]
    # N(y) = N(ref) x (D(y) / D(ref))^0.6 with N(ref) and D(ref) of the reference network, not of the first simulated year
    assert years["2026"]["locker:DHL"]["target"] == round(3 * 2 ** .6) == 5
    assert years["2027"]["locker:DHL"]["target"] == round(3 * 3 ** .6) == 6
    assert years["2026"][f"shared_locker:{SHARED}"]["target"] == round(2 * 2 ** .6) == 3
    assert years["2026"]["locker:DHL"]["added"] == 2 and years["2027"]["locker:DHL"]["added"] == 1
    assert (network.year_opened.iloc[:5] == 2025).all() and network.poi_type.iloc[:5].isna().all()
    # 2026: two Packstations and one box; 2027: one more of each (3 x 3^0.6 = 5.8 -> 6, 2 x 3^0.6 = 3.9 -> 4)
    assert network.year_opened.iloc[5:].tolist() == [2026, 2026, 2026, 2027, 2027] and len(network) == 5 + 3 + 2
    with pytest.raises(ValueError, match="reference year"):
        _plan([2026, 2027], {2026: demand[2026], 2027: demand[2027]})


def test_candidate_weights_prefer_gaps_and_demand():
    cfg = _cfg()
    candidates = gpd.GeoDataFrame({"shop": ["supermarket"] * 4, "amenity": [None] * 4},
                                  geometry=[Point(100, 0), Point(300, 0), Point(0, 3000), Point(3000, 0)], crs=CRS)
    demand_xy = np.array([[310., 0.], [10., 3000.], [3010., 0.], [3000., 10.]])
    demand = np.full(4, 50.)

    def gap(distance):
        return 1. - np.exp(-distance / cfg["gap_scale_m"])

    weights = candidate_weights(candidates, np.array([[0., 0.]]), demand_xy, demand, "locker", cfg)
    assert weights[0] == 0.  # closer than min_spacing_m to a point of the same group
    assert weights.tolist() == pytest.approx([0., 50. * gap(300.), 50. * gap(3000.), 100. * gap(3000.)])
    assert weights[3] > weights[2] > weights[1] > 0.
    # a group without stations yet has no coverage: the weight is the demand nearby
    alone = candidate_weights(candidates, np.empty((0, 2)), demand_xy, demand, "locker", cfg)
    assert alone.tolist() == pytest.approx([50., 50., 50., 100.])


def test_candidate_weights_apply_kind_preference():
    cfg = _cfg()
    candidates = gpd.GeoDataFrame({"shop": ["supermarket", "bakery", None], "amenity": [None, None, "fuel"]},
                                  geometry=[Point(1000, 0), Point(1000, 10), Point(1010, 0)], crs=CRS)
    demand_xy, demand = np.array([[1000., 5.]]), np.array([80.])
    lockers = candidate_weights(candidates, np.empty((0, 2)), demand_xy, demand, "locker", cfg)
    assert lockers.tolist() == pytest.approx([80., 80. * .25, 80.])  # Packstations: supermarkets and fuel stations
    counters = candidate_weights(candidates, np.empty((0, 2)), demand_xy, demand, "counter", cfg)
    assert counters.tolist() == pytest.approx([80. * .25, 80., 80. * .25])  # counters: kiosks, bakeries, ...
    boxes = candidate_weights(candidates, np.empty((0, 2)), demand_xy, demand, "shared_locker", {**cfg, "off_preference_weight": .5})
    assert boxes.tolist() == pytest.approx([80., 40., 80.])


def test_grow_network_adds_points_with_attributes():
    points = _points()
    xy, weights = _demand()
    grown, status = grow_network(points, {LOCKERS: 3}, _three_pois(), xy, weights, 2026, _cfg(), np.random.default_rng(4))
    assert len(grown) == 8 and grown.crs == points.crs
    assert grown.point_id.iloc[:5].tolist() == points.point_id.tolist()
    assert grown.geometry.iloc[:5].geom_equals(points.geometry).all()
    added = grown.iloc[5:]
    assert added.point_id.tolist() == ["syn:locker:DHL:2026:0", "syn:locker:DHL:2026:1", "syn:locker:DHL:2026:2"]
    assert added.poi_type.tolist() == ["shop=supermarket", "amenity=fuel", "shop=bakery"]
    assert (added.kind == "locker").all() and (added.carriers == "DHL").all() and (added.brand == "synthetic").all()
    assert added.synthetic.astype(bool).all() and (added.context == "retail").all() and (added.year_opened == 2026).all()
    assert added.compartments.isna().all()
    assert [(point.x, point.y) for point in added.geometry] == [(1000., 400.), (1400., 400.), (1000., 1200.)]
    assert status == {LOCKERS: {"target_added": 3, "added": 3, "candidates": 3, "shortfall": 0}}


def test_grow_network_keeps_min_spacing_between_the_additions_of_a_year():
    cfg = _cfg()
    candidates = gpd.GeoDataFrame({"shop": ["supermarket"] * 3, "amenity": [None] * 3},
                                  geometry=[Point(5000, 5000), Point(5020, 5000), Point(9000, 9000)], crs=CRS)
    demand_xy, demand = np.array([[5010., 5000.], [9000., 9010.]]), np.array([1000., 1.])
    grown, status = grow_network(_points(), {LOCKERS: 2}, candidates, demand_xy, demand, 2026, cfg, np.random.default_rng(0))
    added = [(point.x, point.y) for point in grown.geometry.iloc[5:]]
    assert status[LOCKERS] == {"target_added": 2, "added": 2, "candidates": 3, "shortfall": 0}
    # the two neighbouring supermarkets are 20 m apart: once one of them has a new station the other is out this year
    assert (9000., 9000.) in added and len({(5000., 5000.), (5020., 5000.)} & set(added)) == 1
    grown, status = grow_network(_points(), {LOCKERS: 2}, candidates.iloc[:2], demand_xy, demand, 2026, cfg, np.random.default_rng(0))
    assert status[LOCKERS] == {"target_added": 2, "added": 1, "candidates": 2, "shortfall": 1} and len(grown) == 6


def test_grow_network_reports_shortfall():
    xy, weights = _demand()
    grown, status = grow_network(_points(), {LOCKERS: 5, BOXES: 1}, _three_pois(), xy, weights, 2026, _cfg(), np.random.default_rng(4))
    assert status[LOCKERS] == {"target_added": 5, "added": 3, "candidates": 3, "shortfall": 2}
    # every POI hosts at most one new station: nothing is left for the shared boxes
    assert status[BOXES] == {"target_added": 1, "added": 0, "candidates": 0, "shortfall": 1}
    assert len(grown) == 8
    demand = {2025: _carrier_demand(1.), 2026: _carrier_demand(4.)}
    network, plan_status = _plan([2025, 2026], demand, candidates=_three_pois())
    entry = plan_status["years"]["2026"]["locker:DHL"]
    assert entry["target"] == round(3 * 4 ** .6) == 7 and entry["added"] == 3 and entry["shortfall"] == 1
    assert len(network) == 8  # the run goes on with the stations that could be placed


def test_plan_network_is_deterministic_and_monotone():
    years = list(range(2025, 2031))
    demand = {year: _carrier_demand(1. + .4 * (year - 2025)) for year in years}
    first, status = _plan(years, demand)
    second, _ = _plan(years, demand)
    assert first.point_id.tolist() == second.point_id.tolist() and first.geometry.geom_equals(second.geometry).all()
    assert first.point_id.iloc[:5].tolist() == _points().point_id.tolist()
    assert first.year_opened.is_monotonic_increasing  # reference points first, then the additions in year order
    added = first.iloc[5:]
    assert len(added) > 0 and not pd.Series([(point.x, point.y) for point in added.geometry]).duplicated().any()
    reference = _points()
    for group, key in ((LOCKERS, "locker:DHL"), (BOXES, f"shared_locker:{SHARED}")):
        members = first.loc[first.kind.eq(group[0]) & first.carriers.eq(group[1])]
        counts = [int(members.year_opened.le(year).sum()) for year in years]
        assert counts == sorted(counts) and counts[0] == int((reference.kind.eq(group[0]) & reference.carriers.eq(group[1])).sum())
        for year, count in zip(years[1:], counts[1:]):
            assert count == status["years"][str(year)][key]["target"]
    other, _ = _plan(years, demand, seed=12)
    assert len(other) == len(first)
    assert other.iloc[5:].point_id.tolist() == first.iloc[5:].point_id.tolist()  # ids follow group and year, not the seed
    assert not other.iloc[5:].geometry.geom_equals(first.iloc[5:].geometry).all()  # another seed opens other sites


def test_capacity_inputs_select_open_stations_and_keep_their_sizes():
    from hagrid_demand.baseline.network_growth import capacity_inputs

    points = _points().assign(year_opened=[2025] * 5)
    points = gpd.GeoDataFrame(pd.concat([points, points.iloc[:1].assign(point_id="syn:locker:DHL:2026:0", year_opened=2026)],
                                        ignore_index=True), crs=CRS)
    sized_last = np.array([120, 76, 90, 40, 40, 0])
    reference = points.year_opened.le(2025).to_numpy()
    active = np.flatnonzero(points.year_opened.le(2026).to_numpy())
    # without growth every year is sized on its own
    subset, previous = capacity_inputs(points, active, sized_last, reference, _cfg(), grows=False)
    assert previous is None and subset.point_id.tolist() == points.point_id.tolist()
    # with growth untagged stations never shrink; the new station has no size yet
    subset, previous = capacity_inputs(points, np.array([0, 1, 5]), sized_last, reference, _cfg(), grows=True)
    assert previous.tolist() == [120, 76, 0] and subset.point_id.tolist() == ["osm:n1", "osm:n2", "syn:locker:DHL:2026:0"]
    assert subset.compartments.isna().all()
    # resize_existing = false: reference stations keep their first size like an OSM capacity tag, new ones still grow
    subset, previous = capacity_inputs(points, np.array([0, 1, 5]), sized_last, reference, _cfg(resize_existing=False), grows=True)
    assert subset.compartments.tolist()[:2] == [120., 76.] and np.isnan(subset.compartments.iloc[2]) and previous.tolist() == [120, 76, 0]
    assert points.compartments.isna().all()  # the network itself is not changed


def test_grow_network_disabled_adds_nothing():
    demand = {year: _carrier_demand(1. + year - 2025) for year in (2025, 2026, 2027)}
    network, status = _plan([2025, 2026, 2027], demand, cfg=_cfg(enabled=False))
    assert len(network) == 5 and (network.year_opened == 2025).all() and network.poi_type.isna().all()
    assert status["enabled"] is False and status["growth_years"] == [] and status["years"] == {}
    # a single simulated year never grows the network, and needs no demand for it
    single, status = _plan([2027], {})
    assert len(single) == 5 and (single.year_opened == 2025).all() and status["growth_years"] == []
    # years before the reference year use the reference network from the first simulated year on
    early, status = _plan([2021, 2022], {})
    assert len(early) == 5 and (early.year_opened == 2021).all() and status["growth_years"] == []


def test_growth_ranking_is_shared_between_scenarios():
    """A scenario that needs one station more opens the same sites plus one: the ranking is shared."""
    xy, weights = _demand()
    uniforms = {("locker", "DHL"): np.random.default_rng(3).random(30)}
    small, _ = grow_network(_points(), {("locker", "DHL"): 3}, _candidates(), xy, weights, 2026, _cfg(),
                            np.random.default_rng(1), uniforms=uniforms)
    large, _ = grow_network(_points(), {("locker", "DHL"): 5}, _candidates(), xy, weights * 1.3, 2026, _cfg(),
                            np.random.default_rng(2), uniforms=uniforms)
    small_sites = {(point.x, point.y) for point in small.iloc[5:].geometry}
    large_sites = {(point.x, point.y) for point in large.iloc[5:].geometry}
    assert len(small_sites) == 3 and len(large_sites) == 5 and small_sites <= large_sites
