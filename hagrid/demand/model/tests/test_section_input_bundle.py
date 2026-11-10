import json
import shutil
from pathlib import Path

import geopandas as gpd
import pandas as pd
from shapely.geometry import Point, LineString

from hagrid_demand.compatibility import matsim_export


def test_section_bundle_uses_actual_stop_register_and_survives_relocation(tmp_path):
    stops = gpd.GeoDataFrame({"stop_id": ["home", "new-home"], "str_idx": [4, 4],
                             "part": [0, 0], "side": ["left", "right"],
                             "position_m": [15., 55.]},
                            geometry=[Point(15, 0), Point(55, 0)], crs="EPSG:25832")
    streets = gpd.GeoDataFrame({"source_row": [4], "street": ["Test"]},
                              geometry=[LineString([(0, 0), (100, 0)])], crs=stops.crs)
    source = tmp_path / "sources" / "dhl_observations.parquet"
    source.parent.mkdir(); streets.to_parquet(source)
    staging = tmp_path / "daily-staging" / "matsim"
    manifest = matsim_export.write_section_inputs({"stops": stops}, source, staging)
    moved = tmp_path / "published"; shutil.copytree(staging, moved)
    config = json.loads((moved / manifest.name).read_text())
    actual = gpd.read_parquet(moved / config["referenceStops"])
    assert actual.stop_id.tolist() == ["home", "new-home"]
    assert actual.side.tolist() == ["left", "right"]
    assert actual.geometry.tolist() == stops.geometry.tolist()
    assert not Path(config["referenceStops"]).is_absolute()
    assert not Path(config["streetObservations"]).is_absolute()
    assert config["allowStreetCrossing"] is False
    assert config["allowLegacyCoordinateRecovery"] is False
    assert (moved / config["streetObservations"]).read_bytes() == source.read_bytes()
