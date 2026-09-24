import json

import geopandas as gpd
import pandas as pd
import pytest

from street_fixtures import write_street_fixture


def test_buildings_stage_assigns_every_person_and_firm_once(tmp_path):
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(write_street_fixture(tmp_path), "street-reference")

    buildings = gpd.read_parquet(run / "buildings" / "buildings.parquet")
    mapping = pd.read_parquet(run / "buildings" / "site_buildings.parquet")
    assert buildings.population.sum() == 4 and buildings.companies.sum() == 2
    assert mapping.site_id.is_unique and set(mapping.stage) <= {"within", "nearest", "point", "cell", "fallback"}
    assert (buildings.sid >= 0).all() and set(buildings.match_stage) <= {"name", "nearest"}
    report = json.loads((run / "buildings" / "buildings_report.json").read_text(encoding="utf-8"))
    assert report["persons_in_buildings_share"] == pytest.approx(1.0)
