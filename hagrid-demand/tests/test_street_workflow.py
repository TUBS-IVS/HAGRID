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
    assert (buildings.sid >= 0).all() and set(buildings.match_stage) <= {"name", "nearest", "nearest_far"}
    report = json.loads((run / "buildings" / "buildings_report.json").read_text(encoding="utf-8"))
    assert report["persons_in_buildings_share"] == pytest.approx(1.0)


def test_street_reference_and_daily_run_use_buildings_and_fixed_dhl(tmp_path):
    from hagrid_demand.baseline.workflow import run_baseline

    config_path = write_street_fixture(tmp_path)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config.update({"output_scope": "daily", "years": [2021, 2025], "dates": ["2025-05-13"],
                   "anchor": {"mode": "street", "min_streets": 99}})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(config_path, "street-daily")

    anchor = json.loads((run / "reference_anchor.json").read_text(encoding="utf-8"))
    assert 0 < anchor["q_dhl"] < 1
    sites = pd.read_parquet(run / "reference_sites.parquet")
    assert sites.site_id.str.startswith(("osm:", "pt:", "syn:")).all()
    profiles = pd.read_parquet(run / "carrier_profiles.parquet")
    dhl = profiles[(profiles.carrier == "DHL") & (profiles.year == 2025)].q.iloc[0]
    assert dhl == pytest.approx(anchor["q_dhl"] * anchor["b2b_by_year"]["2025"] / anchor["b2b_by_year"]["2021"])
    assert (run / "reference_streets.parquet").is_file() and (run / "reference_units.parquet").is_file()
    site_stops = pd.read_parquet(run / "reference_site_stops.parquet")
    assert set(sites.site_id) <= set(site_stops.site_id)
    stops = gpd.read_parquet(run / "reference_stops.parquet")
    assert set(site_stops.stop_id) == set(stops.stop_id) and stops.stop_index.is_unique
    manifest = json.loads((run / "matsim" / "matsim_export.json").read_text(encoding="utf-8"))
    day = manifest["days"][0]
    frame = gpd.read_file(run / "matsim" / day["file"])
    assert set(frame.stop_id) <= set(stops.stop_id) and int(frame.total.sum()) == day["parcels"]
