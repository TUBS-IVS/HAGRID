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


def test_out_of_home_share_follows_the_trend():
    inputs = load_out_of_home_inputs()
    assert out_of_home_share(2025, "DHL", inputs) == pytest.approx(.12)
    shares = [out_of_home_share(year, "DHL", inputs) for year in range(2019, 2031)]
    assert all(later > earlier for earlier, later in zip(shares, shares[1:]))
    assert .03 < shares[0] < .045 and .2 < shares[-1] < .3
    assert out_of_home_share(2025, "FedEx/TNT", inputs) == 0.
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
    home, points, overflow = divert(counts, propensity, np.ones(2), primary, secondary, np.array([12, 20]),
                                    np.random.default_rng(3))
    assert (points.sum(axis=1) <= [12, 20]).all() and points[0].sum() == 12
    assert (home.sum(axis=0) + points.sum(axis=0) == counts.sum(axis=0)).all() and overflow == 0
    tight_home, tight_points, tight_overflow = divert(counts, propensity, np.ones(2), primary, secondary, np.array([12, 14]),
                                                      np.random.default_rng(3))
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
    gpd.GeoDataFrame({"point_id": ["osm:n1", "osm:n2"], "kind": ["locker", "shared_locker"], "carriers": ["DHL", "DPD|GLS|Hermes|UPS"],
                      "brand": ["DHL Packstation", "Myflexbox"], "synthetic": [False, False]},
                     geometry=[Point(15, 15), Point(115, 15)], crs="EPSG:25832").to_parquet(root / "inputs" / "parcel_points.parquet", index=False)
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
    assert days.out_of_home.sum() > 0 and set(points.kind) == {"locker", "shared_locker"}
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
