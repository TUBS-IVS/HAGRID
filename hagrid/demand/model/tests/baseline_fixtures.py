"""Small, local source files used by deterministic baseline workflow tests."""

from __future__ import annotations

import json
from pathlib import Path

import geopandas as gpd
import pandas as pd
from shapely.geometry import LineString, Point, box

from hagrid_demand.baseline.config import SCHEMA_VERSION
from hagrid_demand.common.rng import RNG_VERSION


CRS = "EPSG:25832"


def write_fixture(root: Path) -> Path:
    """Write a self-contained raw baseline configuration and its source files."""
    root = Path(root)
    inputs = root / "inputs"
    inputs.mkdir(parents=True, exist_ok=True)
    (inputs / "persons.csv").write_text(
        "id,Building,Household,geometry\n"
        "p1,A,h1,POINT (10 10)\n"
        "p2,A,h1,POINT (10 10)\n"
        "p3,B,h2,POINT (110 10)\n"
        "p4,B,h2,POINT (110 10)\n",
        encoding="utf-8",
    )
    gpd.GeoDataFrame(
        {"id": ["c1", "c2"], "employees": [10, 100], "branch": ["retail", "office"], "type": ["shop", "office"]},
        geometry=[Point(20, 20), Point(120, 20)], crs=CRS,
    ).to_file(inputs / "companies.shp")
    gpd.GeoDataFrame(
        {"plz": ["01000", "01000", "02000", "02000"], "name": ["Alpha", "Beta", "Gamma", "Delta"],
         "tagesschni": [10, 20, 30, 40]},
        geometry=[LineString([(5, 0), (5, 30)]), LineString([(30, 0), (30, 30)]),
                  LineString([(105, 0), (105, 30)]), LineString([(130, 0), (130, 30)])], crs=CRS,
    ).to_file(inputs / "dhl.shp")
    pd.DataFrame({"postal_cod": ["01000", "02000"],
                  "geometry": [box(0, 0, 50, 50).wkt, box(100, 0, 150, 50).wkt]}).to_csv(inputs / "postal.csv", index=False)
    (inputs / "hermes.csv").write_text("PLZ;2019;2020;2021\n01000;1;2;3\n02000;4;5;6\n", encoding="utf-8")
    weekly = pd.DataFrame({"week": list(range(1, 53)), "2019": list(range(1, 53)),
                           "2020": list(range(2, 54)), "2021": list(range(3, 55))})
    with pd.ExcelWriter(inputs / "weekly.xlsx") as writer:
        weekly.to_excel(writer, sheet_name="Tabelle1", index=False, startrow=1)
    config = {
        "schema_version": SCHEMA_VERSION, "rng_version": RNG_VERSION, "seed": 7,
        "input_dir": "inputs", "output_dir": "outputs", "source_mode": "raw",
        "sources": [
            {"id": "H01", "adapter": "persons", "file": "persons.csv", "kind": "persons", "unit": "persons", "year": 2021},
            {"id": "H02", "adapter": "companies", "file": "companies.shp", "kind": "companies", "unit": "sites", "year": 2021},
            {"id": "H03", "adapter": "dhl", "file": "dhl.shp", "kind": "streets", "unit": "daily_mean", "year": 2021},
            {"id": "H04", "adapter": "hermes", "file": "hermes.csv", "kind": "postal_inventory", "unit": "unconfirmed", "year": 2021},
            {"id": "H06", "adapter": "plz", "file": "postal.csv", "kind": "postal", "unit": "geometry", "year": 2021},
        ],
        "weekly_source": "inputs/weekly.xlsx", "reference_year": 2021, "reference_operating_days": 313,
        "output_scope": "reference", "legacy_export": False, "persons_crs": CRS, "plz_crs": CRS,
        "target_crs": CRS, "dhl_exclude_above": 1000, "assumptions": ["Fixture-only reference run."],
    }
    path = root / "baseline.json"
    path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    return path
