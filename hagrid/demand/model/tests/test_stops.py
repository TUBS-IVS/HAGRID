import geopandas as gpd
import pandas as pd
import pytest
from shapely.geometry import Point

import street_fixtures as fx


def _units():
    keys = ["a", "b", "c", "d", "big", "off"]
    return gpd.GeoDataFrame({
        "building_key": keys, "plz": ["01000"] * 6, "sid": [0, 0, 0, 0, 0, -1], "part": [0, 0, 0, 0, 0, None],
        "side": ["left", "left", "left", "right", "left", None], "position_m": [10., 50., 95., 12., 60., None]},
        geometry=[Point(fx.X0 + p, fx.Y0 + 5) for p in (10, 50, 95, 12, 60)] + [Point(fx.X0 + 900, fx.Y0 + 900)], crs=fx.CRS)


def test_stops_group_same_side_within_walking_distance_and_isolate_large_recipients():
    from hagrid_demand.baseline.stops import build_stops

    expected = pd.Series({"a": 1., "b": 2., "c": 1., "d": 1., "big": 30., "off": 1.})
    stops, mapping = build_stops(_units(), expected, fx.streets())
    by_unit = mapping.set_index("building_key").stop_id
    assert by_unit["a"] == by_unit["b"] != by_unit["c"]          # 10 m and 50 m within an 80 m span; 95 m starts a new stop
    assert by_unit["d"] not in {by_unit["a"], by_unit["c"]}         # other side of the street
    assert (mapping.stop_id == by_unit["big"]).sum() == 1           # large recipient has its own stop
    stop = stops.set_index("stop_id").loc[by_unit["a"]]
    assert stop.geometry.y == pytest.approx(fx.Y0) and stop.geometry.x == pytest.approx(fx.X0 + (10 + 2 * 50) / 3)
    assert stops.set_index("stop_id").loc[by_unit["off"], "str_idx"] == -1
    assert stops.stop_id.is_unique and stops.stop_index.is_unique
    first, _ = build_stops(_units(), expected, fx.streets())
    assert first.stop_id.tolist() == stops.stop_id.tolist()          # stable ids
