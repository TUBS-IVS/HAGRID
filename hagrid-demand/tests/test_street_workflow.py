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
    report = json.loads((run / "report_data.json").read_text(encoding="utf-8"))
    assert report["views"]["anchor"]["q_dhl"] == pytest.approx(anchor["q_dhl"])
    assert "plausibility" in report["views"]["anchor"] and "buildings" in report["views"]["anchor"]
    markdown = (run / "report.md").read_text(encoding="utf-8")
    assert "Street anchor" in markdown and "OpenStreetMap" in markdown
    assert "including fallback" in markdown
    assert "Days and stops" in markdown
    assert "b2c_parcels_per_person_year" in report["views"]["anchor"]["plausibility"][0]
    assert report["views"]["stops"]["days"][0]["stops_active"] > 0


def test_shipping_transit_daily_run(tmp_path):
    from hagrid_demand.baseline.workflow import run_baseline

    config_path = write_street_fixture(tmp_path)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config.update({"output_scope": "daily", "years": [2021, 2025], "dates": ["2025-05-16", "2025-05-17"],
                   "anchor": {"mode": "street", "min_streets": 99}, "temporal": {"mode": "shipping_transit"},
                   "spatial": {**config.get("spatial", {}), "carrier_plz_log_sd": .15, "site_frailty_cv": .5}})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(config_path, "street-shipping")

    status = json.loads((run / "daily_status.json").read_text(encoding="utf-8"))
    assert status["temporal"]["mode"] == "shipping_transit" and status["temporal"]["carrier_day_log_sd"] == .03
    calendar = pd.read_parquet(run / "delivery_calendar.parquet")
    projection = pd.read_parquet(run / "annual_projection.parquet")
    profiles = pd.read_parquet(run / "carrier_profiles.parquet")
    totals = projection.groupby(["year", "segment"]).annual_expected.sum()
    for (year, segment, carrier), expected in calendar.groupby(["year", "segment", "carrier"]).expected.sum().items():
        share = profiles.set_index(["year", "segment", "carrier"]).share[(year, segment, carrier)]
        assert expected == pytest.approx(totals[(year, segment)] * share, rel=1e-6)
    daily = pd.read_parquet(run / "daily_aggregates.parquet")
    daily["date"] = pd.to_datetime(daily.date).dt.strftime("%Y-%m-%d")
    by_day = daily.groupby(["date", "segment"])["count"].sum().unstack(fill_value=0)
    b2b = by_day.business / by_day.sum(axis=1)
    assert b2b["2025-05-17"] < b2b["2025-05-16"]
    assert "Weekday profile by carrier and segment" in (run / "report.md").read_text(encoding="utf-8")
