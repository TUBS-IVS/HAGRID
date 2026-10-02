import geopandas as gpd
import numpy as np
import pytest
from shapely.geometry import Point

from hagrid_demand.baseline.out_of_home import (calibrate_propensity, divert, load_out_of_home_inputs, out_of_home_share,
                                                point_carriers, synthesize_shops)

SHARED = ("Hermes", "DPD", "GLS", "UPS")


def test_point_carriers_from_osm_tags():
    assert point_carriers({"amenity": "parcel_locker", "brand": "DHL Packstation"}, SHARED) == ("locker", ("DHL",))
    assert point_carriers({"amenity": "parcel_locker", "brand": "Amazon Locker"}, SHARED) == ("locker", ("Amazon",))
    assert point_carriers({"amenity": "parcel_locker", "brand": "Myflexbox"}, SHARED) == ("shared_locker", ("DPD", "GLS", "Hermes", "UPS"))
    assert point_carriers({"amenity": "parcel_locker"}, SHARED) == ("shared_locker", ("DPD", "GLS", "Hermes", "UPS"))
    assert point_carriers({"amenity": "post_office", "name": "Hermes PaketShop"}, SHARED) == ("shop", ("Hermes",))
    assert point_carriers({"post_office": "post_partner", "post_office:brand": "DHL"}, SHARED) == ("shop", ("DHL",))
    assert point_carriers({"amenity": "post_office", "brand": "Deutsche Post;DHL"}, SHARED) == ("shop", ("DHL",))
    assert point_carriers({"amenity": "post_depot", "operator": "DHL"}, SHARED) is None
    assert point_carriers({"shop": "outpost", "brand": "Amazon Hub Locker"}, SHARED) == ("locker", ("Amazon",))
    assert point_carriers({"shop": "outpost", "name": "Zalando"}, SHARED) is None
    # Open lockers that also take DHL parcels (DeinFach is a DHL subsidiary, inboxx is carrier-neutral)
    assert point_carriers({"amenity": "parcel_locker", "brand": "Dein Fach"}, SHARED) == ("shared_locker", ("DHL", "DPD", "GLS", "Hermes", "UPS"))
    assert point_carriers({"amenity": "parcel_locker", "operator": "inboxx"}, SHARED) == ("shared_locker", ("DHL", "DPD", "GLS", "Hermes", "UPS"))
    assert point_carriers({"amenity": "parcel_locker", "brand": "FedEx myflexbox"}, SHARED) == ("shared_locker", ("DPD", "GLS", "Hermes", "UPS"))


def test_out_of_home_share_follows_the_trend():
    inputs = load_out_of_home_inputs()
    assert out_of_home_share(2025, "DHL", inputs) == pytest.approx(.10)
    shares = [out_of_home_share(year, "DHL", inputs) for year in range(2019, 2031)]
    assert all(later > earlier for earlier, later in zip(shares, shares[1:]))
    # DHL Packstation share: 3 % 2019, 5 % 2021, 10 % 2025, about 20 % 2030
    assert shares[0] == pytest.approx(.03, abs=.005) and shares[2] == pytest.approx(.05, abs=.01) and .18 < shares[-1] < .25
    assert out_of_home_share(2025, "FedEx/TNT", inputs) == pytest.approx(.02)
    assert out_of_home_share(2030, "DHL", {**inputs, "shares_by_year": {"DHL": {"2030": .5}}}) == .5


def test_calibrated_propensity_hits_the_target_share():
    weights = np.array([10., 20., 30., 40.])
    base = np.array([1., .5, .25, 0.])
    propensity = calibrate_propensity(weights, base, .2, .9)
    assert (weights * propensity).sum() / weights.sum() == pytest.approx(.2, rel=1e-9)
    assert propensity[3] == 0 and propensity.max() <= .9
    capped = calibrate_propensity(weights, base, .8, .9)
    assert np.allclose(capped, [.9, .9, .9, 0.])


def test_divert_respects_capacity_and_keeps_totals():
    counts = np.array([[10, 0], [5, 5], [0, 8]])
    propensity = np.array([[1., 0.], [1., 1.], [0., 1.]])
    primary = np.array([[0, -1], [0, 1], [-1, 1]])
    secondary = np.array([[1, -1], [1, -1], [-1, -1]])
    home, points, overflow, turned_away = divert(counts, propensity, np.ones(2), primary, secondary, np.array([12, 20]),
                                                 np.random.default_rng(3))
    assert turned_away.tolist() == [3, 0]
    assert (points.sum(axis=1) <= [12, 20]).all() and points[0].sum() == 12
    assert (home.sum(axis=0) + points.sum(axis=0) == counts.sum(axis=0)).all() and overflow == 0
    tight_home, tight_points, tight_overflow, tight_away = divert(counts, propensity, np.ones(2), primary, secondary, np.array([12, 14]),
                                                                  np.random.default_rng(3))
    assert tight_away.sum() >= tight_overflow
    assert tight_points.sum() == 26 and tight_overflow == 2
    assert (tight_home.sum(axis=0) + tight_points.sum(axis=0) == counts.sum(axis=0)).all()


def test_synthetic_shops_fill_missing_carrier_points():
    points = gpd.GeoDataFrame({"point_id": ["osm:1"], "kind": ["shop"], "carriers": ["Hermes"], "brand": ["Hermes"],
                               "synthetic": [False]}, geometry=[Point(0, 0)], crs="EPSG:25832")
    candidates = gpd.GeoDataFrame({"poi_id": [f"p{index}" for index in range(10)]},
                                  geometry=[Point(index * 100, 0) for index in range(10)], crs="EPSG:25832")
    result = synthesize_shops(points, candidates, np.arange(1., 11.), {"Hermes": 3, "DPD": 2}, np.random.default_rng(1))
    added = result.loc[result.synthetic]
    assert sorted(added.carriers.tolist()) == ["DPD", "DPD", "Hermes", "Hermes"]
    assert added.geometry.apply(lambda point: point.x).nunique() == 4 and (added.kind == "shop").all()
    assert len(result) == 5


def test_points_from_elements_maps_and_clips():
    from shapely.geometry import box
    from hagrid_demand.baseline.out_of_home import points_from_elements

    elements = [{"type": "node", "id": 1, "lat": 52.40, "lon": 9.70, "tags": {"amenity": "parcel_locker", "brand": "DHL Packstation"}},
                {"type": "way", "id": 2, "center": {"lat": 52.41, "lon": 9.71}, "tags": {"amenity": "post_office", "name": "Hermes PaketShop"}},
                {"type": "node", "id": 3, "lat": 53.50, "lon": 9.70, "tags": {"amenity": "parcel_locker", "brand": "Amazon Locker"}},
                {"type": "node", "id": 4, "lat": 52.42, "lon": 9.72, "tags": {"amenity": "post_depot", "operator": "DHL"}}]
    points = points_from_elements(elements, box(9.5, 52.3, 9.9, 52.5), "EPSG:25832", SHARED)
    assert points.point_id.tolist() == ["osm:n1", "osm:w2"] and points.kind.tolist() == ["locker", "shop"]
    assert points.carriers.tolist() == ["DHL", "Hermes"] and points.crs.to_epsg() == 25832


@pytest.fixture(scope="module")
def ooh_run(tmp_path_factory):
    import json

    from street_fixtures import write_street_fixture

    from hagrid_demand.baseline.workflow import run_baseline

    root = tmp_path_factory.mktemp("ooh")
    config_path = write_street_fixture(root)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    gpd.GeoDataFrame({"point_id": ["osm:n1", "osm:n2", "osm:n3"], "kind": ["locker", "shared_locker", "shop"],
                      "carriers": ["DHL", "DPD|GLS|Hermes|UPS", "Hermes"], "brand": ["DHL Packstation", "Myflexbox", "Hermes PaketShop"],
                      "synthetic": [False, False, False]},
                     geometry=[Point(15, 15), Point(115, 15), Point(20, 20)], crs="EPSG:25832").to_parquet(root / "inputs" / "parcel_points.parquet", index=False)
    shares = {"DHL": .3, "DPD": .3, "GLS": .3, "Hermes": .3, "UPS": .3, "Amazon": 0., "FedEx/TNT": 0.}
    config.update({"output_scope": "daily", "years": [2025], "dates": ["2025-05-16"], "anchor": {"mode": "street", "min_streets": 99},
                   "temporal": {"mode": "shipping_transit"}, "annual_store": True, "osm_parcel_points": "inputs/parcel_points.parquet",
                   "out_of_home": {"shares_2025": shares, "synthetic_shops": False}})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    return run_baseline(config_path, "street-ooh")


def test_out_of_home_run_routes_parcels_to_points(ooh_run):
    import pandas as pd

    days = pd.read_parquet(ooh_run / "annual" / "days.parquet")
    points = gpd.read_parquet(ooh_run / "annual" / "out_of_home_points.parquet")
    stop_daily = pd.read_parquet(ooh_run / "annual" / "stop_daily.parquet")
    at_points = stop_daily.loc[stop_daily.stop.isin(points.stop_index)]
    # Shops stay out of the out-of-home routing for now (they will come with the failed-delivery model).
    assert days.out_of_home.sum() > 0 and set(points.kind) == {"locker", "shared_locker"}
    assert not points.point_id.eq("osm:n3").any()
    assert at_points.filter(like="_b2b").to_numpy().sum() == 0
    assert int(at_points.filter(like="_b2c").to_numpy().sum()) == int(days.out_of_home.sum())
    assert int(stop_daily.filter(regex="_b2[bc]$").to_numpy().sum()) == int(days.parcels.sum())
    calendar = pd.read_parquet(ooh_run / "delivery_calendar.parquet")
    assert int(calendar.delivered.sum()) == int(days.parcels.sum())


def test_out_of_home_points_reach_the_matsim_export(ooh_run):
    import pandas as pd

    from hagrid_demand.baseline.annual import export_day

    direct = gpd.read_file(next((ooh_run / "matsim").glob("*.shp")))
    assert "stop_type" in direct and set(direct.stop_type) <= {"home", "locker", "shared_locker", "shop"}
    days = pd.read_parquet(ooh_run / "annual" / "days.parquet")
    busiest = days.loc[days.out_of_home.idxmax()]
    ledger = export_day(ooh_run, str(pd.Timestamp(busiest.date).date()), ooh_run / "ooh_export")
    exported = gpd.read_file(ooh_run / "ooh_export" / ledger["file"])
    assert {"locker", "shared_locker"} & set(exported.stop_type)
    assert int(exported.total.sum()) == int(busiest.parcels)


def test_plan_prefers_lockers_within_reach():
    import pandas as pd

    from hagrid_demand.baseline.out_of_home import build_plan

    inputs = {**load_out_of_home_inputs(), "shares_2025": {"DHL": .1}}
    sites = pd.DataFrame({"site_id": ["a", "b"], "plz": ["30159", "30159"], "annual_expected": [100., 100.]})
    points = gpd.GeoDataFrame({"point_id": ["shop", "locker"], "kind": ["shop", "locker"], "carriers": ["DHL", "DHL"],
                               "synthetic": [False, False], "plz": ["30159", "30159"]},
                              geometry=[Point(100, 0), Point(800, 0)], crs="EPSG:25832")
    xy = np.array([[0., 0.], [5000., 0.]])
    plan = build_plan(sites, ["DHL"], xy, np.array([2., 10.]), points, inputs, 2025, 365, lambda carrier: np.random.default_rng(1))
    assert plan.primary[0, 0] == 1 and plan.secondary[0, 0] == 0 and plan.primary[1, 0] == -1
    shop_first = build_plan(sites, ["DHL"], xy, np.array([2., 10.]), points, {**inputs, "prefer_lockers": False}, 2025, 365,
                            lambda carrier: np.random.default_rng(1))
    assert shop_first.primary[0, 0] == 0 and shop_first.secondary[0, 0] == 1


def test_synthetic_lockers_and_shared_boxes():
    points = gpd.GeoDataFrame({"point_id": ["osm:1"], "kind": ["locker"], "carriers": ["Amazon"], "brand": ["Amazon Locker"],
                               "synthetic": [False]}, geometry=[Point(0, 0)], crs="EPSG:25832")
    candidates = gpd.GeoDataFrame({"poi_id": [f"p{index}" for index in range(10)]},
                                  geometry=[Point(index * 100, 0) for index in range(10)], crs="EPSG:25832")
    weights = np.arange(1., 11.)
    lockers = synthesize_shops(points, candidates, weights, {"Amazon": 3}, np.random.default_rng(2), kind="locker")
    added = lockers.loc[lockers.synthetic]
    assert len(added) == 2 and (added.kind == "locker").all() and (added.carriers == "Amazon").all()
    shared = synthesize_shops(points, candidates, weights, {"shared": 2}, np.random.default_rng(2), kind="shared_locker",
                              shared_carriers=SHARED)
    boxes = shared.loc[shared.synthetic]
    assert len(boxes) == 2 and (boxes.kind == "shared_locker").all() and (boxes.carriers == "DPD|GLS|Hermes|UPS").all()
    assert boxes.point_id.str.startswith("syn:shared_locker:").all()


def test_run_adds_synthetic_lockers_on_top_of_mapped_ones(tmp_path):
    import json

    from street_fixtures import write_street_fixture

    from hagrid_demand.baseline.workflow import run_baseline

    config_path = write_street_fixture(tmp_path)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    gpd.GeoDataFrame({"point_id": ["osm:n1"], "kind": ["locker"], "carriers": ["Amazon"], "brand": ["Amazon Locker"], "synthetic": [False]},
                     geometry=[Point(15, 15)], crs="EPSG:25832").to_parquet(tmp_path / "inputs" / "parcel_points.parquet", index=False)
    gpd.GeoDataFrame({"osm_id": ["p1", "p2"], "addr_street": ["Alpha", "Gamma"], "addr_housenumber": ["9", "9"], "shop": ["kiosk", "supermarket"]},
                     geometry=[Point(10, 30), Point(110, 30)], crs="EPSG:25832").to_parquet(tmp_path / "inputs" / "osm_points.parquet", index=False)
    config.update({"output_scope": "daily", "years": [2025], "dates": ["2025-05-16"], "anchor": {"mode": "street", "min_streets": 99},
                   "temporal": {"mode": "shipping_transit"}, "annual_store": True, "osm_parcel_points": "inputs/parcel_points.parquet",
                   "out_of_home": {"shares_2025": {"Amazon": .3}, "synthetic_lockers": {"Amazon": 2}}})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(config_path, "street-ooh-lockers")
    points = gpd.read_parquet(run / "annual" / "out_of_home_points.parquet")
    assert len(points) == 3 and int(points.synthetic.sum()) == 2 and (points.kind == "locker").all()


def test_locker_queue_frees_compartments_after_pickup():
    from hagrid_demand.baseline.out_of_home import LockerQueue

    rng = np.random.default_rng(0)
    same_day = LockerQueue(np.array([10]), np.array([[1., 0., 0.]]))
    assert same_day.free(0).tolist() == [10]
    same_day.store(np.array([10]), rng)
    assert same_day.free(0).tolist() == [0] and same_day.free(1).tolist() == [10]
    next_day = LockerQueue(np.array([10, 5]), np.array([[0., 1., 0.], [0., 0., 1.]]))
    next_day.store(np.array([4, 5]), rng)
    assert next_day.free(1).tolist() == [6, 0] and next_day.free(2).tolist() == [10, 0] and next_day.free(3).tolist() == [10, 5]
    assert next_day.occupancy().tolist() == [0, 0]
    late = LockerQueue(np.array([3]), np.array([[0., 1.]]))
    late.free(5)
    late.store(np.array([3]), rng)
    assert late.free(5).tolist() == [0] and late.free(40).tolist() == [3]


def test_points_keep_osm_compartments():
    from shapely.geometry import box
    from hagrid_demand.baseline.out_of_home import points_from_elements

    elements = [{"type": "node", "id": 1, "lat": 52.4, "lon": 9.7, "tags": {"amenity": "parcel_locker", "brand": "DHL Packstation", "capacity": "96"}},
                {"type": "node", "id": 2, "lat": 52.41, "lon": 9.71, "tags": {"amenity": "parcel_locker", "brand": "DHL Packstation"}}]
    points = points_from_elements(elements, box(9.5, 52.3, 9.9, 52.5), "EPSG:25832", SHARED)
    assert points.compartments.tolist()[0] == 96 and np.isnan(points.compartments.tolist()[1])


def test_out_of_home_run_writes_locker_occupancy(ooh_run):
    import pandas as pd

    occupancy = pd.read_parquet(ooh_run / "annual" / "locker_occupancy.parquet")
    points = gpd.read_parquet(ooh_run / "annual" / "out_of_home_points.parquet")
    assert {"date", "stop_index", "compartments", "occupied", "stored", "rejected", "occupied_next_morning"} <= set(occupancy.columns)
    assert (occupancy.occupied_next_morning <= occupancy.occupied).all() and (occupancy.occupied >= occupancy.stored).all()
    assert set(occupancy.stop_index) == set(points.stop_index) and len(occupancy) == 365 * len(points)
    assert (occupancy.occupied <= occupancy.compartments).all() and occupancy.stored.sum() > 0
    assert points.compartments.tolist() == [76, 40]  # sized to the (small) fixture demand: the standard size of the kind


def test_default_pickup_profile_is_the_literature_reference():
    from hagrid_demand.baseline.out_of_home import locker_queue, resolve_out_of_home

    inputs = resolve_out_of_home({"enabled": True})
    assert inputs["pickup_profile"]["locker"] == [.6, .2, .2] and "pickup_first_day_concentration" not in inputs
    points = gpd.GeoDataFrame({"point_id": ["a", "b"], "kind": ["locker", "shared_locker"], "carriers": ["DHL", "DPD|GLS|Hermes|UPS"],
                               "synthetic": [False, False], "compartments": [np.nan, 96.]}, geometry=[Point(0, 0), Point(1, 1)], crs="EPSG:25832")
    queue = locker_queue(points, inputs, np.random.default_rng(0))
    assert np.allclose(queue.profiles, [[.6, .2, .2], [.6, .2, .2]]) and queue.compartments.tolist() == [76, 96]


def test_context_profile_keeps_first_day_and_scales_the_mean():
    from hagrid_demand.baseline.out_of_home import context_profile

    base = np.array([.6, .2, .2])
    retail, transit, other = context_profile(base, .965), context_profile(base, 1.092), context_profile(base, 1.)
    classes = np.arange(1, 4)
    assert np.allclose(other, base) and np.allclose(retail.sum(), 1.) and np.allclose(transit.sum(), 1.)
    assert retail[0] == transit[0] == .6
    assert (retail * classes).sum() == pytest.approx(1.6 * .965) and (transit * classes).sum() == pytest.approx(1.6 * 1.092)
    assert np.allclose(context_profile(base, 3.), [.6, 0., .4])  # mean is capped by the last class


def test_point_context_from_osm_surroundings():
    from hagrid_demand.baseline.out_of_home import point_context

    points = gpd.GeoDataFrame({"point_id": ["a", "b", "c", "d"]}, geometry=[Point(0, 0), Point(500, 0), Point(1000, 0), Point(1500, 0)],
                              crs="EPSG:25832")
    transit = gpd.GeoDataFrame({"kind": ["station"]}, geometry=[Point(60, 0)], crs="EPSG:25832")
    retail = gpd.GeoDataFrame({"kind": ["supermarket", "kiosk"]}, geometry=[Point(40, 0), Point(1050, 0)], crs="EPSG:25832")
    context = point_context(points, transit, retail, transit_m=150., retail_m=75.)
    assert context.tolist() == ["transit", "other", "retail", "other"]


def test_locker_queue_uses_the_point_context():
    from hagrid_demand.baseline.out_of_home import locker_queue, resolve_out_of_home

    inputs = resolve_out_of_home({"enabled": True})
    points = gpd.GeoDataFrame({"point_id": ["a", "b", "c"], "kind": ["locker"] * 3, "carriers": ["DHL"] * 3, "synthetic": [False] * 3,
                               "compartments": [np.nan] * 3, "context": ["retail", "transit", "other"]},
                              geometry=[Point(0, 0), Point(1, 1), Point(2, 2)], crs="EPSG:25832")
    queue = locker_queue(points, inputs, np.random.default_rng(0))
    means = (queue.profiles * np.arange(1, queue.profiles.shape[1] + 1)).sum(axis=1)
    assert means[0] < means[2] < means[1] and np.allclose(queue.profiles[:, 0], .6)


def test_run_adds_amazon_counters_at_retail_pois(tmp_path):
    import json

    from street_fixtures import write_street_fixture

    from hagrid_demand.baseline.annual import export_day
    from hagrid_demand.baseline.workflow import run_baseline

    config_path = write_street_fixture(tmp_path)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    gpd.GeoDataFrame({"point_id": ["osm:n1"], "kind": ["locker"], "carriers": ["Amazon"], "brand": ["Amazon Locker"], "synthetic": [False]},
                     geometry=[Point(15, 15)], crs="EPSG:25832").to_parquet(tmp_path / "inputs" / "parcel_points.parquet", index=False)
    gpd.GeoDataFrame({"osm_id": ["p1", "p2", "p3"], "addr_street": ["Alpha", "Gamma", "Beta"], "addr_housenumber": ["9", "9", "9"],
                      "shop": [None, "supermarket", "bakery"], "amenity": ["fuel", None, None]},
                     geometry=[Point(10, 30), Point(110, 30), Point(20, 40)], crs="EPSG:25832").to_parquet(tmp_path / "inputs" / "osm_points.parquet", index=False)
    config.update({"output_scope": "daily", "years": [2025], "dates": ["2025-05-16"], "anchor": {"mode": "street", "min_streets": 99},
                   "temporal": {"mode": "shipping_transit"}, "annual_store": True, "osm_parcel_points": "inputs/parcel_points.parquet",
                   "out_of_home": {"shares_2025": {"Amazon": .3}, "synthetic_counters": {"Amazon": 2}}})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(config_path, "street-ooh-counters")
    points = gpd.read_parquet(run / "annual" / "out_of_home_points.parquet")
    counters = points.loc[points.kind.eq("counter")]
    assert len(counters) == 2 and counters.synthetic.all() and (counters.carriers == "Amazon").all() and (counters.compartments >= 40).all()
    assert set(counters.geometry.apply(lambda p: (p.x, p.y))) <= {(10., 30.), (110., 30.)}  # fuel station and supermarket, not the bakery
    days = __import__("pandas").read_parquet(run / "annual" / "days.parquet")
    ledger = export_day(run, "2025-05-16", run / "counter_export")
    exported = gpd.read_file(run / "counter_export" / ledger["file"])
    assert "counter" in set(exported.stop_type)


def test_dashboard_payload_lists_lockers_with_daily_fill(ooh_run):
    from hagrid_demand.baseline.annual_dashboard import build_annual_dashboard_data, write_annual_dashboard

    data = build_annual_dashboard_data(ooh_run)
    lockers = data["lockers"]
    assert lockers["ids"] == ["osm:n1", "osm:n2"] and lockers["kind"] == ["locker", "shared_locker"]
    assert lockers["compartments"] == [76, 40] and all(-180 < lon < 180 for lon in lockers["lon"]) and lockers["context"] == ["other", "other"]
    n_days = len(data["days"]["date"])
    assert len(lockers["fill"]) == len(lockers["stored"]) == len(lockers["rejected"]) == 2 * n_days
    assert max(lockers["fill"]) <= 100 and sum(lockers["stored"]) == sum(data["days"]["out_of_home"])
    page = write_annual_dashboard(ooh_run, ooh_run / "lockers.html").read_text(encoding="utf-8")
    assert 'id="lockers"' in page and "renderLockers" in page


def test_compartments_follow_demand_where_osm_has_no_tag():
    from hagrid_demand.baseline.out_of_home import size_compartments

    points = gpd.GeoDataFrame({"point_id": ["a", "b", "c", "d"], "kind": ["locker", "locker", "locker", "shared_locker"],
                               "compartments": [96., np.nan, np.nan, np.nan]}, geometry=[Point(i, 0) for i in range(4)], crs="EPSG:25832")
    rule = {"days_factor": 1.6, "safety": 1.25, "min": 48, "max": 300, "step": 10}
    sizes = size_compartments(points, np.array([200., 10., 60., 5.]), {"locker": 70, "shared_locker": 40}, rule)
    assert sizes.tolist() == [96, 48, 120, 48]  # tagged stays; 60/day -> 1.6 x 1.25 x 60 = 120; small demand -> minimum
    per_kind = {**rule, "min": {"locker": 76, "shared_locker": 40}}
    assert size_compartments(points, np.array([200., 10., 60., 5.]), {"locker": 70, "shared_locker": 40}, per_kind).tolist() == [96, 76, 120, 40]
    assert size_compartments(points, np.array([10., 10., 1000., 10.]), {"locker": 70, "shared_locker": 40}, rule).tolist()[2] == 300
    assert size_compartments(points, np.array([10., 10., 60., 10.]), {"locker": 70, "shared_locker": 40}, None).tolist() == [96, 70, 70, 40]


def test_sites_choose_among_the_nearest_points():
    import pandas as pd

    from hagrid_demand.baseline.out_of_home import build_plan, load_out_of_home_inputs

    inputs = {**load_out_of_home_inputs(), "shares_2025": {"DHL": .2}, "choice_k": 3, "choice_decay_m": 300.}
    sites = pd.DataFrame({"site_id": [f"s{i}" for i in range(400)], "plz": ["30159"] * 400, "annual_expected": [50.] * 400})
    xy = np.column_stack([np.random.default_rng(3).uniform(0, 100, 400), np.zeros(400)])
    points = gpd.GeoDataFrame({"point_id": ["near", "far"], "kind": ["locker", "locker"], "carriers": ["DHL", "DHL"], "synthetic": [False, False],
                               "compartments": [np.nan, np.nan], "context": ["other", "other"], "plz": ["30159", "30159"]},
                              geometry=[Point(50, 100), Point(50, 400)], crs="EPSG:25832")
    plan = build_plan(sites, ["DHL"], xy, np.full(400, 3.), points, inputs, 2025, 365, lambda carrier: np.random.default_rng(1))
    share_far = (plan.primary[:, 0] == 1).mean()
    assert .1 < share_far < .5 and (plan.secondary[:, 0] >= 0).all()
    nearest_only = build_plan(sites, ["DHL"], xy, np.full(400, 3.), points, {**inputs, "choice_k": 1}, 2025, 365, lambda carrier: np.random.default_rng(1))
    assert (nearest_only.primary[:, 0] == 0).all()


def test_build_plan_never_shrinks_untagged_compartments():
    import pandas as pd

    from hagrid_demand.baseline.out_of_home import build_plan

    inputs = {**load_out_of_home_inputs(), "shares_2025": {"DHL": .1}}
    sites = pd.DataFrame({"site_id": ["a"], "plz": ["30159"], "annual_expected": [100.]})
    points = gpd.GeoDataFrame({"point_id": ["new", "tagged", "grown"], "kind": ["locker"] * 3, "carriers": ["DHL"] * 3,
                               "synthetic": [False] * 3, "compartments": [np.nan, 96., np.nan], "plz": ["30159"] * 3},
                              geometry=[Point(10, 0), Point(20, 0), Point(30, 0)], crs="EPSG:25832")

    def plan(previous=None):
        return build_plan(sites, ["DHL"], np.array([[0., 0.]]), np.array([2.]), points, inputs, 2026, 365,
                          lambda carrier: np.random.default_rng(1), previous_compartments=previous)

    assert plan().queue.compartments.tolist() == [76, 96, 76]  # small demand: the standard Packstation, the OSM tag stays
    # last year's size (120) is kept when this year's demand asks for less; the OSM tag stays fixed; 0 = not sized yet
    assert plan(np.array([0, 200, 120])).queue.compartments.tolist() == [76, 96, 120]
    assert plan(np.array([500, 0, 0])).queue.compartments.tolist() == [390, 96, 76]  # never above the largest module


def test_resolve_out_of_home_merges_and_validates_network_growth():
    from hagrid_demand.baseline.out_of_home import resolve_out_of_home

    defaults = resolve_out_of_home({"enabled": True})["network_growth"]
    assert defaults["enabled"] is True and defaults["reference_year"] == 2025 and defaults["elasticity"] == .7
    assert defaults["candidate_types"]["amenity"] == ["fuel"] and defaults["kind_preferences"]["counter"]["shop"][0] == "kiosk"
    merged = resolve_out_of_home({"network_growth": {"elasticity": 1, "kind_preferences": {"locker": {"shop": ["kiosk"]}}}})["network_growth"]
    assert merged["elasticity"] == 1. and merged["min_spacing_m"] == 150. and merged["resize_existing"] is True
    assert merged["kind_preferences"]["locker"] == {"shop": ["kiosk"]}
    assert merged["kind_preferences"]["counter"] == defaults["kind_preferences"]["counter"]
    assert resolve_out_of_home({"network_growth": {"enabled": False}})["network_growth"]["enabled"] is False
    for bad in ({"enabled": "yes"}, {"resize_existing": 1}, {"elasticity": -.1}, {"elasticity": True}, {"demand_radius_m": 0},
                {"gap_scale_m": -5}, {"min_spacing_m": 0}, {"off_preference_weight": 1.5}, {"reference_year": "2025"},
                {"elasticty": .5}, {"candidate_types": {"shop": "kiosk"}}, {"kind_preferences": {"locker": ["kiosk"]}}, "on"):
        with pytest.raises(ValueError, match="network_growth"):
            resolve_out_of_home({"network_growth": bad})


def test_single_year_run_keeps_the_reference_network(ooh_run):
    import json

    import pandas as pd

    points = gpd.read_parquet(ooh_run / "out_of_home_points.parquet")
    assert (points.year_opened == 2025).all() and points.poi_type.isna().all()
    network = pd.read_parquet(ooh_run / "out_of_home_network.parquet")
    assert network.columns.tolist() == ["year", "stop_index", "point_id", "kind", "carriers", "brand", "context", "synthetic",
                                        "year_opened", "poi_type", "plz", "compartments", "lon", "lat"]
    assert network.year.eq(2025).all() and network.point_id.tolist() == points.point_id.tolist()
    assert network.compartments.tolist() == points.compartments.tolist() == [76, 40]
    assert pd.read_parquet(ooh_run / "annual" / "out_of_home_network.parquet").equals(network)
    growth = json.loads((ooh_run / "daily_status.json").read_text(encoding="utf-8"))["temporal"]["out_of_home"]["network_growth"]
    assert growth["growth_years"] == [] and growth["reference_points"] == {"locker:DHL": 1, "shared_locker:DPD|GLS|Hermes|UPS": 1}
    assert growth["years"]["2025"]["locker:DHL"] == {"points": 1, "compartments": 76}


def test_locker_queue_carries_parcels_across_years():
    import numpy as np

    from hagrid_demand.baseline.out_of_home import LockerQueue

    profiles = np.array([[.6, .2, .2], [.6, .2, .2], [.6, .2, .2]])
    previous = LockerQueue(np.array([10, 10, 10]), profiles)
    previous.free(364)
    previous.pending[:] = [[5, 3, 2], [0, 1, 0], [4, 4, 4]]
    following = LockerQueue(np.array([10, 10, 10, 10]), np.vstack([profiles, profiles[:1]]))
    following.carry_from(previous, gap_days=1, prefix=2)  # the third old station is not part of the new prefix
    assert following.pending.tolist() == [[3, 2, 0], [1, 0, 0], [0, 0, 0], [0, 0, 0]]
    assert following.free(0).tolist() == [5, 9, 10, 10]
    following.free(3)
    assert following.free(3).tolist() == [10, 10, 10, 10]
