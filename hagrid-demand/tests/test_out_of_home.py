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
