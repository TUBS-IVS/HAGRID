import json
import sys
import threading

import pandas as pd
import pytest

from baseline_fixtures import write_fixture


@pytest.fixture
def fixture_config(tmp_path):
    return write_fixture(tmp_path)


def test_reference_run_and_resume(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, "reference-fixture")
    assert json.loads((run / "run.json").read_text())["status"] == "complete_reference"
    assert (run / "reference_postal.parquet").exists()
    assert (run / "report_data.json").exists()
    report = (run / "report.md").read_text(encoding="utf-8")
    assert "2021" in report and "operating days" in report and "B2B" in report
    assert (run.parent / "dashboard" / "index.html").exists()
    assert run_baseline(fixture_config, "reference-fixture", resume=True) == run


def test_daily_scope_publishes_aggregate_and_daily_run_status(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    config = json.loads(fixture_config.read_text())
    config["output_scope"] = "daily"
    fixture_config.write_text(json.dumps(config), encoding="utf-8")
    config["dates"] = ["2021-01-01"]
    fixture_config.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(fixture_config, "daily-fixture")
    assert json.loads((run / "run.json").read_text())["status"] == "complete_daily"
    assert (run / "daily_aggregates.parquet").is_file()


@pytest.mark.parametrize(("key", "value"), [("reference_year", 2022), ("dhl_exclude_above", 999)])
def test_reference_milestone_rejects_noncanonical_scope_before_creating_run(fixture_config, key, value):
    from hagrid_demand.baseline.workflow import run_baseline

    config = json.loads(fixture_config.read_text())
    config[key] = value
    fixture_config.write_text(json.dumps(config), encoding="utf-8")
    with pytest.raises(ValueError, match="2021|1000"):
        run_baseline(fixture_config, "invalid-milestone")
    assert not (fixture_config.parent / "outputs" / "invalid-milestone").exists()


def test_renderer_rejects_incomplete_run(fixture_config):
    from hagrid_demand.baseline.dashboard import render_baseline

    run = fixture_config.parent / "incomplete"
    run.mkdir()
    (run / "config.resolved.json").write_text(fixture_config.read_text(), encoding="utf-8")
    with pytest.raises(ValueError, match="complete"):
        render_baseline(run)


def test_resume_rejects_changed_resolved_config(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    run_baseline(fixture_config, "reference-fixture")
    config = json.loads(fixture_config.read_text())
    config["seed"] = 8
    fixture_config.write_text(json.dumps(config), encoding="utf-8")
    with pytest.raises(ValueError, match="configuration"):
        run_baseline(fixture_config, "reference-fixture", resume=True)


def test_resume_rejects_a_runtime_contract_that_no_longer_matches(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, "runtime-fixture")
    runtime = json.loads((run / "runtime.json").read_text(encoding="utf-8"))
    runtime["python"] = "tampered"
    (run / "runtime.json").write_text(json.dumps(runtime), encoding="utf-8")
    with pytest.raises(ValueError, match="runtime contract"):
        run_baseline(fixture_config, "runtime-fixture", resume=True)


def test_projection_consumes_the_canonical_reference_artifacts(fixture_config):
    import pandas as pd

    from hagrid_demand.baseline.projection import project_annual
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, "projection-consumer")
    reference = {
        "regional_annual": json.loads((run / "reference_regional_annual.json").read_text(encoding="utf-8"))["regional_annual"],
        "sites": pd.read_parquet(run / "reference_sites.parquet"),
    }
    series = {name: pd.read_parquet(run / "series" / f"{name}.parquet") for name in ("volume", "market", "b2b", "providers")}
    result = project_annual(reference, series, [2021], {"memory": {"fixed": 1}, "regional_level": {"mode": "national_series"}})

    assert result.sites.columns.tolist() == ["year", "site_id", "plz", "segment", "allocation_status", "support_status", "annual_expected", "share"]
    assert result.postal.columns.tolist() == ["year", "plz", "segment", "annual_expected", "support_status", "memory_weight", "regional_level_mode", "growth_factor", "b2b_share"]
    assert all(len(value) == 64 for value in result.checks["hashes"].values())


def test_external_projection_uses_the_scope_id_from_canonical_reference_artifacts(fixture_config):
    import pandas as pd

    from hagrid_demand.baseline.projection import project_annual
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, "projection-external-scope")
    annual = json.loads((run / "reference_regional_annual.json").read_text(encoding="utf-8"))
    checks = json.loads((run / "reference_checks.json").read_text(encoding="utf-8"))
    assert annual["scope_id"] == checks["scope_id"]
    reference = {"regional_annual": annual["regional_annual"], "scope_id": annual["scope_id"],
                 "sites": pd.read_parquet(run / "reference_sites.parquet")}
    series = {name: pd.read_parquet(run / "series" / f"{name}.parquet") for name in ("volume", "market", "b2b", "providers")}
    cfg = {"memory": {"fixed": 1}, "regional_level": {"mode": "external_annual_series", "series": [{
        "year": 2021, "value": annual["regional_annual"], "unit": "packages/year", "provenance": "fixture",
        "scope": annual["scope_id"],
    }]}}

    result = project_annual(reference, series, [2021], cfg)
    assert result.checks["scope_id"] == annual["scope_id"]
    cfg["regional_level"]["series"][0]["scope"] = "wrong"
    with pytest.raises(ValueError, match="scope"):
        project_annual(reference, series, [2021], cfg)


def test_dashboard_root_uses_a_relative_link_to_each_preserved_run(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    config = json.loads(fixture_config.read_text())
    config["dashboard_root"] = "shared/reports"
    fixture_config.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(fixture_config, "custom-dashboard")
    index = (fixture_config.parent / "shared" / "reports" / "index.html").read_text(encoding="utf-8")
    assert "../../outputs/custom-dashboard/report_data.json" in index
    assert (run / "report_data.json").is_file()


def test_report_cache_is_distinct_per_run_id_and_dashboard_lists_both(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    first = run_baseline(fixture_config, "reference-one")
    second = run_baseline(fixture_config, "reference-two")
    assert json.loads((first / "report_data.json").read_text())["run_id"] == "reference-one"
    assert json.loads((second / "report_data.json").read_text())["run_id"] == "reference-two"
    index = (first.parent / "dashboard" / "index.html").read_text(encoding="utf-8")
    assert "reference-one" in index and "reference-two" in index


def test_resume_compares_persisted_source_fingerprint_even_after_early_failure(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    weekly = fixture_config.parent / "inputs" / "weekly.xlsx"
    weekly.write_bytes(b"not an xlsx")
    with pytest.raises(Exception):
        run_baseline(fixture_config, "failed-source")
    weekly.write_bytes(b"changed bytes")
    with pytest.raises(ValueError, match="source files changed"):
        run_baseline(fixture_config, "failed-source", resume=True)


@pytest.mark.parametrize("replacement", [None, "tampered markdown"])
def test_renderer_restores_markdown_independently_when_its_public_copy_is_missing_or_tampered(
        fixture_config, replacement):
    """The renderer rebuilds each public presentation artifact from frozen reference data."""
    from hagrid_demand.baseline.dashboard import render_baseline
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, f"markdown-{replacement is None}")
    public = run / "report.md"
    if replacement is None:
        public.unlink()
    else:
        public.write_text(replacement, encoding="utf-8")

    render_baseline(run)

    state = json.loads((run / "run.json").read_text(encoding="utf-8"))
    assert "in-scope observations" in public.read_text(encoding="utf-8")
    assert json.loads((run / "report_data.json").read_text(encoding="utf-8"))["baseline_fingerprint"] == state["baseline_fingerprint"]


def test_renderer_ignores_a_tampered_nonsemantic_report_stage_copy_when_rebuilding_from_reference(fixture_config):
    """A stale cached view cannot prevent deterministic regeneration from the frozen reference."""
    from hagrid_demand.baseline.dashboard import render_baseline
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, "tampered-stage-markdown")
    (run / "report" / "report.md").write_text("tampered stage markdown", encoding="utf-8")

    assert render_baseline(run).is_file()
    assert "tampered stage markdown" not in (run / "report.md").read_text(encoding="utf-8")


@pytest.mark.parametrize("malformed_manifest", [[], "not-a-manifest"])
def test_renderer_skips_sibling_runs_with_list_or_scalar_stage_manifests(fixture_config, malformed_manifest):
    """A malformed sibling must not block the shared dashboard for a valid run."""
    from hagrid_demand.baseline.dashboard import render_baseline
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, "valid-dashboard-run")
    sibling = run.parent / f"00-malformed-{type(malformed_manifest).__name__}"
    sibling.mkdir()
    (sibling / "run.json").write_text(json.dumps({"status": "complete_reference"}), encoding="utf-8")
    (sibling / "report_data.json").write_text("{}", encoding="utf-8")
    (sibling / "stage_manifest.json").write_text(json.dumps(malformed_manifest), encoding="utf-8")

    dashboard = render_baseline(run)

    assert dashboard.is_file()
    assert "valid-dashboard-run" in dashboard.read_text(encoding="utf-8")


def test_renderer_skips_a_sibling_with_a_nonobject_run_state(fixture_config):
    """Candidate state must be a mapping before renderer code reads its status."""
    from hagrid_demand.baseline.dashboard import render_baseline
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, "valid-state-run")
    sibling = run.parent / "00-list-state"
    sibling.mkdir()
    (sibling / "run.json").write_text("[]", encoding="utf-8")
    (sibling / "report_data.json").write_text("{}", encoding="utf-8")
    (sibling / "stage_manifest.json").write_text("{}", encoding="utf-8")

    assert render_baseline(run).is_file()


def test_valid_report_rejects_boolean_annual_amount_and_accepts_numeric_scalar():
    """Booleans are not quantities, while real numeric scalar values remain valid."""
    import numpy as np

    from hagrid_demand.baseline.dashboard import _valid_report

    report = {
        "run_id": "numeric-report",
        "reference_year": 2021,
        "operating_days": 313,
        "regional_annual": np.int64(7),
        "postal": [],
        "excluded_quantities": {},
        "b2b_adjustment": {},
        "remaining_potentials": {},
        "status": "complete_reference",
    }
    assert _valid_report(report, "numeric-report")
    report["regional_annual"] = True
    assert not _valid_report(report, "numeric-report")


def test_cli_baseline_run_prints_the_path_returned_by_the_custom_dashboard_renderer(
        fixture_config, monkeypatch, capsys):
    """CLI output must not reconstruct the default dashboard path after rendering."""
    config = json.loads(fixture_config.read_text(encoding="utf-8"))
    config["dashboard_root"] = "shared/custom-dashboard"
    fixture_config.write_text(json.dumps(config), encoding="utf-8")
    expected = fixture_config.parent / "shared" / "custom-dashboard" / "index.html"
    monkeypatch.setattr(sys, "argv", [
        "hagrid", "baseline", "run", "--config", str(fixture_config), "--run-id", "cli-dashboard",
    ])

    from hagrid_demand.cli import main

    assert main() == 0
    assert capsys.readouterr().out.strip().endswith(f"Baseline dashboard: {expected}")


def test_reference_run_publishes_the_frozen_consumer_contract_and_dhl_identity(fixture_config):
    """A reference run has all semantic artifacts, with DHL stored only at postal grain."""
    import geopandas as gpd
    import pandas as pd

    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, "frozen-contract")
    required = {
        "reference_sites.parquet", "reference_geometry.parquet", "reference_postal.parquet",
        "reference_carrier_profiles.parquet", "reference_reconciliation.json",
        "reference_regional_annual.json", "reference_checks.json", "checks.json",
    }
    assert required.issubset({path.name for path in run.iterdir()})
    sites = pd.read_parquet(run / "reference_sites.parquet")
    geometry = gpd.read_parquet(run / "reference_geometry.parquet")
    postal = pd.read_parquet(run / "reference_postal.parquet")
    profiles = pd.read_parquet(run / "reference_carrier_profiles.parquet")
    reconciliation = json.loads((run / "reference_reconciliation.json").read_text(encoding="utf-8"))
    state = json.loads((run / "run.json").read_text(encoding="utf-8"))
    runtime = json.loads((run / "runtime.json").read_text(encoding="utf-8"))

    assert {"site_id", "plz", "segment", "population", "employees", "branch", "weight",
            "historical_share", "structural_share", "reference_annual", "allocation_status"}.issubset(sites.columns)
    assert geometry.columns.tolist() == ["site_id", "geometry"]
    assert geometry.crs.to_epsg() == 25832
    assert not postal.duplicated("plz").any()
    assert postal.columns.tolist()[:7] == ["plz", "dhl_retained_mean", "reference_annual", "private_annual",
                                            "business_annual", "b2b_share", "dhl_share"]
    assert "dhl_retained_mean" not in sites.columns
    assert {"year", "segment", "carrier", "market_share", "q_prior", "q_scale", "lower", "upper", "q_adjusted", "share"}.issubset(profiles.columns)
    assert {"market", "providers", "adjusted_q", "conditional", "diagnostics", "reference_balance"}.issubset(reconciliation)
    assert {"initial_endpoints", "expanded_endpoints", "reachable_range", "log_k", "status", "residual"}.issubset(
        reconciliation["reference_balance"]
    )
    assert {"python", "packages"}.issubset(runtime)
    assert {"numpy", "scipy", "pandas", "geopandas", "shapely", "pyarrow"}.issubset(runtime["packages"])
    assert isinstance(state["baseline_fingerprint"], str) and len(state["baseline_fingerprint"]) == 64
    assert {"structure", "geometry", "series", "scope"}.issubset(state["baseline_fingerprint_artifacts"])
    assert "series/volume.parquet" in state["baseline_fingerprint_artifacts"]["series"]

    dhl_market_share = profiles.loc[profiles.carrier.eq("DHL"), "market_share"].iloc[0]
    expected = postal.dhl_retained_mean.sum() * state["config"]["reference_operating_days"] / dhl_market_share
    assert state["regional_annual"] == pytest.approx(expected, rel=1e-10)


def test_renderer_rejects_tampered_semantic_reference_artifact(fixture_config):
    """A display refresh may not hide a changed frozen reference geometry."""
    from hagrid_demand.baseline.dashboard import render_baseline
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, "tampered-reference")
    (run / "reference_geometry.parquet").write_bytes(b"not parquet")
    with pytest.raises(ValueError, match="baseline fingerprint|semantic reference"):
        render_baseline(run)


def test_workflow_excludes_out_of_scope_dhl_before_anchor_support_and_ledgers_it(fixture_config):
    """DHL rows outside verified postal support cannot create an anchor or a missing-potential error."""
    import geopandas as gpd
    import pandas as pd
    from shapely.geometry import LineString

    from hagrid_demand.baseline.workflow import run_baseline

    dhl_path = fixture_config.parent / "inputs" / "dhl.shp"
    current = gpd.read_file(dhl_path)
    outside = gpd.GeoDataFrame(
        {"plz": ["99991", "99992", "99993"], "name": ["outside-positive", "outside-zero", "outside-large"],
         "tagesschni": [7., 0., 1001.]},
        geometry=[LineString([(200, 0), (200, 30)])] * 3, crs=current.crs,
    )
    gpd.GeoDataFrame(pd.concat([current, outside], ignore_index=True), geometry="geometry", crs=current.crs).to_file(dhl_path)

    run = run_baseline(fixture_config, "scoped-dhl")
    postal = pd.read_parquet(run / "reference_postal.parquet")
    checks = json.loads((run / "reference_checks.json").read_text(encoding="utf-8"))
    assert set(postal.plz) == {"01000", "02000"}
    ledger = checks["scope_ledger"]
    assert ledger["out_of_scope_positive_rows"] == 2
    assert ledger["out_of_scope_zero_rows"] == 1
    assert ledger["out_of_scope_above_threshold_rows"] == 1


def test_workflow_rejects_invalid_employees_for_business_inside_verified_postal_scope(fixture_config):
    """The default company-location potential cannot silently erase a bad in-scope employee value."""
    import geopandas as gpd

    from hagrid_demand.baseline.workflow import run_baseline

    companies = fixture_config.parent / "inputs" / "companies.shp"
    data = gpd.read_file(companies)
    data.loc[0, "employees"] = -1
    data.to_file(companies)
    with pytest.raises(ValueError, match="invalid employees.*business"):
        run_baseline(fixture_config, "invalid-business")


def test_conflicted_geometry_is_not_presented_as_an_independently_known_postal_site(fixture_config):
    """A polygon join may locate valid points, but it cannot certify a conflicted building's PLZ."""
    import geopandas as gpd

    from hagrid_demand.baseline.workflow import run_baseline

    persons = fixture_config.parent / "inputs" / "persons.csv"
    persons.write_text(
        "id,Building,Household,geometry\n"
        "p1,A,h1,POINT (10 10)\n"
        "p2,A,h1,POINT (40 40)\n"
        "p3,B,h2,POINT (110 10)\n"
        "p4,B,h2,POINT (110 10)\n"
        "p5,C,h3,POINT (20 10)\n",
        encoding="utf-8",
    )
    run = run_baseline(fixture_config, "conflicted-postal")
    sites = gpd.read_parquet(run / "sources" / "sites.parquet")
    conflicted = sites.loc[sites.location_status.eq("conflicting_building_coordinates")].iloc[0]
    assert conflicted["allocation_status"] == "unlocated"
    assert str(conflicted["plz"]) in {"<NA>", "nan", "None"}


def test_dashboard_catalog_is_atomic_idempotent_and_exposes_stage_navigation_without_mutating_reference(fixture_config):
    """Two report refreshes share one catalog and leave semantic demand artifacts byte-identical."""
    from hagrid_demand.baseline.dashboard import render_baseline
    from hagrid_demand.baseline.workflow import run_baseline

    first = run_baseline(fixture_config, "catalog-one")
    second = run_baseline(fixture_config, "catalog-two")
    before = {path.name: path.read_bytes() for path in first.glob("reference_*")}
    errors = []

    def refresh(run):
        try:
            render_baseline(run)
        except BaseException as exc:
            errors.append(exc)

    threads = [threading.Thread(target=refresh, args=(run,)) for run in (first, second)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=10)
    assert not errors and all(not thread.is_alive() for thread in threads)
    assert before == {path.name: path.read_bytes() for path in first.glob("reference_*")}

    root = first.parent / "dashboard"
    catalog = json.loads((root / "report_catalog.json").read_text(encoding="utf-8"))
    assert catalog["schema_version"] == 1
    assert len(catalog["runs"]) == 2
    entry = next(item for item in catalog["runs"] if item["run_id"] == "catalog-one")
    assert entry["stage_status"]["regional_reference"] == "complete"
    assert entry["stage_status"]["daily"] == "not_run"
    assert set(entry["views"]) == {"reference", "market_b2b", "quality"}
    assert entry["views"]["reference"]["regional_annual"] > 0
    html = (root / "index.html").read_text(encoding="utf-8")
    assert "report_catalog.json" in html
    assert "data-quality" in html and "market-b2b" in html and "regional-reference" in html
    assert "localStorage" in html and "stage=" in html


def test_daily_scope_streams_selected_date_and_resumes_with_the_same_artifact(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    config = json.loads(fixture_config.read_text(encoding="utf-8"))
    config.update({"output_scope": "daily", "years": [2021], "dates": ["2021-01-01"],
                   "spatial": {"mode": "dirichlet", "between": 20., "within": 20.}})
    fixture_config.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(fixture_config, "daily-scope")
    first = (run / "daily_aggregates.parquet").read_bytes()
    resumed = run_baseline(fixture_config, "daily-scope", resume=True)
    assert resumed == run
    assert (run / "daily_aggregates.parquet").read_bytes() == first


def test_daily_scope_wires_weekly_profile_weekdays_and_public_holidays(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    config = json.loads(fixture_config.read_text(encoding="utf-8"))
    dates = [f"2021-05-{day:02d}" for day in range(10, 17)] + ["2021-12-06"]
    config.update({"output_scope": "daily", "years": [2021], "dates": dates,
                   "spatial": {"mode": "dirichlet", "between": 20., "within": 20.}})
    fixture_config.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(fixture_config, "daily-calendar")

    calendar = pd.read_parquet(run / "calendar_weights.parquet")
    private = calendar.loc[calendar.segment.eq("private")].set_index("date")
    assert private.loc["2021-05-16", "calendar_weight"] == 0  # Sunday
    assert private.loc["2021-05-13", "calendar_weight"] == 0  # Christi Himmelfahrt (NI)
    assert private.loc["2021-05-12", "weekday_factor"] == pytest.approx(0.19)
    # The fixture's source weekly profile rises with the ISO week; it must reach the daily calendar.
    assert private.loc["2021-12-06", "season_factor"] > private.loc["2021-05-10", "season_factor"]
    assert calendar.groupby("segment").calendar_weight.sum().tolist() == pytest.approx([1., 1.])

    daily = pd.read_parquet(run / "daily_aggregates.parquet")
    per_day = daily.groupby(daily.date.astype(str).str[:10])["count"].sum()
    assert per_day.get("2021-05-16", 0) == 0 and per_day.get("2021-05-13", 0) == 0
    assert per_day["2021-12-06"] > per_day["2021-05-10"]
    status = json.loads((run / "daily_status.json").read_text(encoding="utf-8"))
    assert status["calendar"]["weekly_profile"] == "source"
    assert status["calendar"]["delivery_days"]["2021"] == 313 - len([d for d in status["calendar"]["holiday_dates"] if pd.Timestamp(d).dayofweek < 6])


def test_daily_scope_for_future_years_only_still_builds_the_reference_year(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    config = json.loads(fixture_config.read_text(encoding="utf-8"))
    config.update({"output_scope": "daily", "years": [2025], "dates": ["2025-05-13"]})
    fixture_config.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(fixture_config, "future-only")

    assert json.loads((run / "run.json").read_text(encoding="utf-8"))["status"] == "complete_daily"
    projection = pd.read_parquet(run / "annual_projection.parquet")
    assert projection.year.unique().tolist() == [2025]


def test_default_business_potential_is_one_unit_per_company(fixture_config):
    from hagrid_demand.baseline.workflow import _business_employee_weight

    assert _business_employee_weight({}) is None
    assert _business_employee_weight({"business_potential": {"model": "company_plus_employees"}}) == 0.1


_NETWORK_COLUMNS = ["year", "stop_index", "point_id", "kind", "carriers", "brand", "context", "synthetic", "year_opened", "poi_type",
                    "plz", "compartments", "lon", "lat"]


def _growth_config(root, years):
    """Street fixture with two DHL Packstations, one shared box and 20 retail POIs; DHL's out-of-home share doubles in 2026."""
    import geopandas as gpd
    from shapely.geometry import Point

    from street_fixtures import write_street_fixture

    config_path = write_street_fixture(root)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    gpd.GeoDataFrame({"point_id": ["osm:n1", "osm:n2", "osm:n3"], "kind": ["locker", "locker", "shared_locker"],
                      "carriers": ["DHL", "DHL", "DPD|GLS|Hermes|UPS"], "brand": ["DHL Packstation", "DHL Packstation", "Myflexbox"],
                      "synthetic": [False] * 3},
                     geometry=[Point(15, 15), Point(115, 15), Point(120, 40)], crs="EPSG:25832") \
        .to_parquet(root / "inputs" / "parcel_points.parquet", index=False)
    types = [("shop", "supermarket"), ("shop", "kiosk"), ("amenity", "fuel"), ("shop", "convenience"), ("shop", "bakery")]
    offsets = [(3, 3), (25, 3), (47, 3), (3, 30), (47, 30), (3, 47), (25, 47), (47, 47), (30, 30), (35, 40)]
    xy = [(x0 + dx, dy) for x0 in (0, 100) for dx, dy in offsets]
    gpd.GeoDataFrame({"osm_id": [f"poi{index}" for index in range(20)], "addr_street": [None] * 20, "addr_housenumber": [None] * 20,
                      "shop": [value if column == "shop" else None for column, value in (types[index % 5] for index in range(20))],
                      "amenity": [value if column == "amenity" else None for column, value in (types[index % 5] for index in range(20))]},
                     geometry=[Point(x, y) for x, y in xy], crs="EPSG:25832").to_parquet(root / "inputs" / "osm_points.parquet", index=False)
    shares = {"DHL": .3, "DPD": .3, "GLS": .3, "Hermes": .3, "UPS": .3, "Amazon": 0., "FedEx/TNT": 0.}
    config.update({"output_scope": "daily", "years": years, "dates": [f"{year}-05-{16 - (year - 2025)}" for year in years],
                   "anchor": {"mode": "street", "min_streets": 99}, "temporal": {"mode": "shipping_transit"}, "annual_store": True,
                   "osm_parcel_points": "inputs/parcel_points.parquet",
                   "out_of_home": {"shares_2025": shares, "shares_by_year": {"DHL": {"2026": .6}}, "synthetic_shops": False,
                                   "network_growth": {"elasticity": 1., "min_spacing_m": 5., "demand_radius_m": 100., "gap_scale_m": 30.}}})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    return config_path


@pytest.fixture(scope="module")
def growth_run(tmp_path_factory):
    from hagrid_demand.baseline.workflow import run_baseline

    root = tmp_path_factory.mktemp("growth")
    return run_baseline(_growth_config(root, [2025, 2026]), "street-ooh-growth")


def test_two_year_run_grows_the_pickup_network(growth_run):
    import geopandas as gpd

    network = pd.read_parquet(growth_run / "out_of_home_network.parquet")
    assert network.columns.tolist() == _NETWORK_COLUMNS
    assert pd.read_parquet(growth_run / "annual" / "out_of_home_network.parquet").equals(network)
    per_year = network.groupby("year").size()
    assert per_year.index.tolist() == [2025, 2026] and per_year[2026] > per_year[2025] == 3
    new = network.loc[network.year_opened.eq(2026)]
    assert set(new.year) == {2026} and new.synthetic.all() and (new.kind == "locker").all() and (new.carriers == "DHL").all()
    assert new.point_id.str.fullmatch(r"syn:locker:DHL:2026:\d+").all() and new.poi_type.str.fullmatch(r"(shop|amenity)=\w+").all()
    assert new.brand.eq("synthetic").all() and new.context.eq("retail").all() and network.lon.between(-180, 180).all()
    # stations of both years keep or grow their compartments
    sizes = network.pivot(index="point_id", columns="year", values="compartments").dropna()
    assert len(sizes) == 3 and (sizes[2026] >= sizes[2025]).all()
    # stable stop register: the reference stations first, the additions after them
    points = gpd.read_parquet(growth_run / "out_of_home_points.parquet")
    assert points.point_id.iloc[:3].tolist() == ["osm:n1", "osm:n2", "osm:n3"] and len(points) == per_year[2026]
    assert points.stop_index.is_monotonic_increasing and points.year_opened.tolist() == [2025] * 3 + [2026] * len(new)
    assert points.poi_type.iloc[3:].tolist() == new.poi_type.tolist() and points.poi_type.iloc[:3].isna().all()
    assert points.compartments.tolist() == network.loc[network.year.eq(2026), "compartments"].tolist()
    growth = json.loads((growth_run / "daily_status.json").read_text(encoding="utf-8"))["temporal"]["out_of_home"]["network_growth"]
    assert growth["growth_years"] == [2026] and growth["reference_points"] == {"locker:DHL": 2, "shared_locker:DPD|GLS|Hermes|UPS": 1}
    entry = growth["years"]["2026"]["locker:DHL"]
    assert entry["added"] == len(new) and entry["target"] == entry["points"] == 2 + len(new) and entry["shortfall"] == 0
    assert entry["candidates"] > 0 and entry["demand"] > growth["reference_demand"]["locker:DHL"]
    lockers = network.loc[network.year.eq(2026) & network.kind.eq("locker")]
    assert entry["compartments"] == int(lockers.compartments.sum())
    assert growth["years"]["2026"]["shared_locker:DPD|GLS|Hermes|UPS"]["added"] == 0
    # the candidate POIs key the daily stage cache
    assert "osm_points" in json.loads((growth_run / "stage_manifest.json").read_text(encoding="utf-8"))["stages"]["daily"]["dependencies"]
    assert growth["years"]["2025"]["locker:DHL"] == {"points": 2, "compartments": int(network.loc[network.year.eq(2025) & network.kind.eq("locker"), "compartments"].sum())}


def test_first_year_of_a_growing_run_equals_the_single_year_run(growth_run, tmp_path):
    from hagrid_demand.baseline.workflow import run_baseline

    single = run_baseline(_growth_config(tmp_path, [2025]), "street-ooh-2025")
    for name in ("stop_daily.parquet", "locker_occupancy.parquet", "plz_daily.parquet", "days.parquet"):
        grown = pd.read_parquet(growth_run / "annual" / name)
        first = grown.loc[pd.to_datetime(grown.date).dt.year.eq(2025)].reset_index(drop=True)
        assert pd.read_parquet(single / "annual" / name).equals(first), name
    network = pd.read_parquet(growth_run / "out_of_home_network.parquet")
    assert pd.read_parquet(single / "out_of_home_network.parquet").equals(network.loc[network.year.eq(2025)].reset_index(drop=True))


def test_new_stations_take_parcels_from_their_opening_year_on(growth_run):
    network = pd.read_parquet(growth_run / "out_of_home_network.parquet")
    new_stops = set(network.loc[network.year_opened.eq(2026), "stop_index"])
    occupancy = pd.read_parquet(growth_run / "annual" / "locker_occupancy.parquet")
    year = pd.to_datetime(occupancy.date).dt.year
    assert not occupancy.loc[year.eq(2025), "stop_index"].isin(new_stops).any()
    assert new_stops <= set(occupancy.loc[year.eq(2026), "stop_index"])
    sized = occupancy.assign(year=year).groupby(["year", "stop_index"]).compartments.first()
    assert sized.to_dict() == network.set_index(["year", "stop_index"]).compartments.to_dict()
    stop_daily = pd.read_parquet(growth_run / "annual" / "stop_daily.parquet")
    assert not stop_daily.loc[pd.to_datetime(stop_daily.date).dt.year.eq(2025), "stop"].isin(new_stops).any()
    days = pd.read_parquet(growth_run / "annual" / "days.parquet")
    at_points = stop_daily.loc[stop_daily.stop.isin(network.stop_index)]
    assert int(at_points.filter(like="_b2c").to_numpy().sum()) == int(days.out_of_home.sum()) > 0


def test_annual_dashboard_shows_the_network_of_its_year(growth_run):
    from hagrid_demand.baseline.annual_dashboard import build_annual_dashboard_data

    network = pd.read_parquet(growth_run / "out_of_home_network.parquet")
    for year in (2025, 2026):
        data = build_annual_dashboard_data(growth_run, year)
        register = network.loc[network.year.eq(year)]
        lockers = data["lockers"]
        assert lockers["ids"] == register.point_id.tolist() and lockers["compartments"] == register.compartments.astype(int).tolist()
        assert len(lockers["fill"]) == len(register) * len(data["days"]["date"])
        assert sum(lockers["stored"]) == sum(data["days"]["out_of_home"])


def test_network_growth_requires_osm_points(growth_run):
    import geopandas as gpd

    from hagrid_demand.baseline.out_of_home import resolve_out_of_home
    from hagrid_demand.baseline.workflow import _out_of_home_points

    config = json.loads((growth_run / "config.resolved.json").read_text(encoding="utf-8"))
    config.pop("osm_points")
    ooh, crs = resolve_out_of_home(config["out_of_home"]), gpd.read_parquet(growth_run / "reference_stops.parquet").crs
    with pytest.raises(ValueError, match="osm_points"):
        _out_of_home_points(config, growth_run, ooh, crs)
    # a single simulated year keeps the reference network and needs no POIs
    points, status = _out_of_home_points({**config, "years": [2026]}, growth_run, ooh, crs)
    assert points.point_id.tolist() == ["osm:n1", "osm:n2", "osm:n3"] and status["network_growth"]["growth_years"] == []
    assert points.year_opened.tolist() == [2025] * 3


def test_network_growth_projects_an_unsimulated_reference_year(growth_run):
    import geopandas as gpd
    import numpy as np

    from hagrid_demand.baseline.network_growth import carrier_ooh_demand
    from hagrid_demand.baseline.out_of_home import resolve_out_of_home
    from hagrid_demand.baseline.workflow import _out_of_home_points, _project_years

    config = json.loads((growth_run / "config.resolved.json").read_text(encoding="utf-8"))
    ooh = resolve_out_of_home(config["out_of_home"])
    stored = pd.read_parquet(growth_run / "annual_projection.parquet")
    profiles = pd.read_parquet(growth_run / "carrier_profiles.parquet")
    # the supplementary projection repeats the daily stage's projection of a year
    again = _project_years(config, growth_run, [2025])
    expected = stored.loc[stored.year.eq(2025)].reset_index(drop=True)
    assert again.sites.site_id.tolist() == expected.site_id.tolist()
    assert np.allclose(again.sites.annual_expected, expected.annual_expected, rtol=1e-12, atol=0.)
    assert np.allclose(again.profiles.share, profiles.loc[profiles.year.eq(2025)].share, rtol=1e-12, atol=0.)
    # a run of 2026 and 2027 grows from the 2025 reference network and the projected 2025 demand
    later = {**config, "years": [2026, 2027]}
    stops = gpd.read_parquet(growth_run / "reference_stops.parquet")
    links = pd.read_parquet(growth_run / "reference_site_stops.parquet")
    points, status = _out_of_home_points(later, growth_run, ooh, stops.crs, projection=_project_years(later, growth_run, [2026, 2027]),
                                         site_xy_of=stops.drop_duplicates("stop_id").set_index("stop_id").geometry,
                                         site_stop_of=links.drop_duplicates("site_id").set_index("site_id").stop_id)
    growth = status["network_growth"]
    reference = carrier_ooh_demand(expected, profiles.loc[profiles.year.eq(2025)], 2025, ooh)
    assert growth["growth_years"] == [2026, 2027] and growth["reference_points"]["locker:DHL"] == 2
    assert growth["reference_demand"]["locker:DHL"] == pytest.approx(reference["DHL"], rel=1e-12)
    assert points.year_opened.iloc[:3].tolist() == [2025] * 3 and points.year_opened.iloc[3:].isin([2026, 2027]).all()
    run_points = gpd.read_parquet(growth_run / "out_of_home_points.parquet")
    run_growth = json.loads((growth_run / "daily_status.json").read_text(encoding="utf-8"))["temporal"]["out_of_home"]["network_growth"]
    # same reference, demand and random stream: 2026 grows exactly as in the run that simulated 2025
    assert growth["years"]["2026"]["locker:DHL"]["target"] == run_growth["years"]["2026"]["locker:DHL"]["target"]
    assert points.loc[points.year_opened.eq(2026), "point_id"].tolist() == run_points.loc[run_points.year_opened.eq(2026), "point_id"].tolist()
    assert points.loc[points.year_opened.eq(2026)].geometry.reset_index(drop=True).geom_equals(
        run_points.loc[run_points.year_opened.eq(2026)].geometry.reset_index(drop=True)).all()


def test_multi_year_run_writes_a_dashboard_page_per_year(growth_run):
    """A run over several years writes annual_dashboard_<year>.html next to the last year's page."""
    run = growth_run["run"] if isinstance(growth_run, dict) else growth_run
    assert (run / "annual_dashboard.html").is_file()
    for year in (2025, 2026):
        page = run / f"annual_dashboard_{year}.html"
        assert page.is_file(), page
        assert f'"year":{year}' in page.read_text(encoding="utf-8")


def test_annual_dashboard_out_of_home_block_is_per_year(growth_run):
    """Each year's page reports the parcels stored at its own network, not the run's cumulated status."""
    from hagrid_demand.baseline.annual_dashboard import build_annual_dashboard_data

    status = json.loads((growth_run / "daily_status.json").read_text(encoding="utf-8"))["temporal"]["out_of_home"]
    blocks = {year: build_annual_dashboard_data(growth_run, year)["meta"]["temporal"]["out_of_home"] for year in (2025, 2026)}
    for carrier, total in status["delivered"].items():
        assert blocks[2025]["delivered"][carrier] + blocks[2026]["delivered"][carrier] == total
    assert sum(blocks[2026]["delivered"].values()) > 0
    network = pd.read_parquet(growth_run / "out_of_home_network.parquet")
    for year in (2025, 2026):
        assert blocks[year]["osm_points"] == {str(k): int(v) for k, v in network.loc[network.year.eq(year)].kind.value_counts().items()}
        assert blocks[year]["scope"] == f"year {year}"


def test_run_status_records_overflow_per_year(growth_run):
    from hagrid_demand.baseline.annual_dashboard import build_annual_dashboard_data

    status = json.loads((growth_run / "daily_status.json").read_text(encoding="utf-8"))["temporal"]["out_of_home"]
    by_year = status["overflow_home_by_year"]
    assert set(by_year) == {"2025", "2026"} and sum(by_year.values()) == status["overflow_home"]
    block = build_annual_dashboard_data(growth_run, 2026)["meta"]["temporal"]["out_of_home"]
    assert block["overflow_home"] == by_year["2026"]


# --- land-use dynamics -----------------------------------------------------------------------------------------------

def _land_use_config(root, years):
    """The growth fixture plus land use: city district A (PLZ 01000) with a development area, municipality B (PLZ 02000)
    with a commercial area that receives one new office firm in 2026."""
    import geopandas as gpd
    from shapely.geometry import box

    config_path = _growth_config(root, years)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    inputs = root / "inputs"
    gpd.GeoDataFrame({"osm_id": ["1", "2", "3"], "name": ["Hannover", "S1", "Testdorf"], "admin_level": [8, 10, 8]},
                     geometry=[box(-10, -10, 60, 60), box(-10, -10, 60, 60), box(90, -10, 160, 60)], crs="EPSG:25832") \
        .to_parquet(inputs / "boundaries.parquet", index=False)
    pd.DataFrame({"age": [30, 70, 40, 10], "geometry": ["POINT (10 10)", "POINT (10 10)", "POINT (110 10)", "POINT (110 10)"]}) \
        .to_csv(inputs / "persons_age.csv", index=False)
    pd.DataFrame({"osm_id": [1], "code": [0], "fclass": ["commercial"], "name": [None], "geometry": [box(120, 30, 140, 45).wkt]}) \
        .to_csv(inputs / "landuse.csv", index=False)
    config["osm_boundaries"] = "inputs/boundaries.parquet"
    config["land_use"] = {
        "enabled": True, "persons": "persons_age.csv", "landuse": "landuse.csv", "new_firm_share": 0.5,
        "firm_rates": {"office": 1.0, "default": 0.0},
        "districts": [{"id": "A", "name": "Stadt", "kind": "city", "pop_2024": 100, "pop_2034": 10000, "stadtteile": ["S1"]},
                      {"id": "B", "name": "Testdorf", "kind": "umland", "pop_2024": 100, "pop_2034": 90, "municipality": "Testdorf"}],
        "developments": [{"name": "Neubau", "district_id": "A", "residents": 400, "start_year": 2026, "ramp_years": 1,
                          "geometry": {"center": [25.0, 40.0], "radius_m": 6.0}}]}
    config_path.write_text(json.dumps(config), encoding="utf-8")
    return config_path


@pytest.fixture(scope="module")
def land_use_run(tmp_path_factory):
    from hagrid_demand.baseline.workflow import run_baseline

    root = tmp_path_factory.mktemp("land-use")
    return run_baseline(_land_use_config(root, [2025, 2026]), "street-land-use")


def test_land_use_run_writes_its_registers(land_use_run):
    import geopandas as gpd

    districts = pd.read_parquet(land_use_run / "land_use_districts.parquet")
    assert list(districts.columns) == ["year", "district_id", "name", "kind", "population_index", "propensity_index",
                                       "persons_model", "employees_model", "forecast_index"]
    assert sorted(zip(districts.year, districts.district_id)) == [(2025, "A"), (2025, "B"), (2026, "A"), (2026, "B")]
    factors = pd.read_parquet(land_use_run / "land_use_factors.parquet")
    assert list(factors.columns) == ["year", "site_id", "segment", "factor"]
    sites = gpd.read_parquet(land_use_run / "land_use_sites.parquet")
    homes = sites.loc[sites.segment.eq("private")]
    assert len(homes) >= 5 and set(homes["area"]) == {"Neubau"} and set(homes.year_opened) == {2026}
    assert sites.loc[sites.segment.eq("business"), "site_id"].tolist() == ["lu:biz:office:2026:0"]
    stops = gpd.read_parquet(land_use_run / "land_use_stops.parquet")
    assert stops.stop_index.min() >= 1_000_000 and len(stops) == len(sites)
    status = json.loads((land_use_run / "daily_status.json").read_text(encoding="utf-8"))["land_use"]
    assert status["variant"] == "prognose" and status["new_sites"] == {"2026": len(sites)}


def test_land_use_base_year_equals_the_run_without_land_use(land_use_run, growth_run):
    for name in ("stop_daily.parquet", "plz_daily.parquet"):
        with_land_use = pd.read_parquet(land_use_run / "annual" / name)
        without = pd.read_parquet(growth_run / "annual" / name)
        first = with_land_use.loc[pd.to_datetime(with_land_use.date).dt.year.eq(2025)].reset_index(drop=True)
        second = without.loc[pd.to_datetime(without.date).dt.year.eq(2025)].reset_index(drop=True)
        pd.testing.assert_frame_equal(first, second)
    for day in sorted((growth_run / "matsim").glob("*2025-*.dbf")):
        assert (land_use_run / "matsim" / day.name).read_bytes() == day.read_bytes()


def test_land_use_sites_take_parcels_from_their_opening_year(land_use_run):
    stops = pd.read_parquet(land_use_run / "annual" / "stop_daily.parquet")
    year = pd.to_datetime(stops.date).dt.year
    land_use = stops.stop.ge(1_000_000)
    assert not (land_use & year.eq(2025)).any() and (land_use & year.eq(2026)).any()


def test_site_groups_for_land_use_sites_are_per_area(land_use_run):
    import geopandas as gpd

    stops = gpd.read_parquet(land_use_run / "land_use_stops.parquet")
    homes = stops.loc[stops.stop_id.str.startswith("lu:res:")]
    firms = stops.loc[stops.stop_id.str.startswith("lu:biz:")]
    assert homes.str_idx.nunique() == 1 and int(homes.str_idx.iloc[0]) < 0
    assert (firms.str_idx <= -1000).all() and not set(firms.str_idx) & set(homes.str_idx)


def test_export_day_includes_land_use_stops_and_points(land_use_run, tmp_path):
    import geopandas as gpd

    from hagrid_demand.baseline.annual import export_day

    stops = pd.read_parquet(land_use_run / "annual" / "stop_daily.parquet")
    points = pd.read_parquet(land_use_run / "annual" / "out_of_home_points.parquet")
    stops["day"] = pd.to_datetime(stops.date)
    has_land_use = stops.loc[stops.stop.ge(1_000_000)].groupby("day").size()
    has_point = stops.loc[stops.stop.isin(points.stop_index)].groupby("day").size()
    day = sorted(set(has_land_use.index) & set(has_point.index))[0]
    ledger = export_day(land_use_run, day.date().isoformat(), tmp_path)
    frame = gpd.read_file(tmp_path / ledger["file"])
    assert frame.stop_id.str.startswith("lu:").any() and frame.stop_type.ne("home").any()
    index = pd.concat([gpd.read_parquet(land_use_run / "reference_stops.parquet")[["stop_id", "stop_index"]],
                       gpd.read_parquet(land_use_run / "land_use_stops.parquet")[["stop_id", "stop_index"]],
                       points.assign(stop_id="ooh:" + points.point_id)[["stop_id", "stop_index"]]], ignore_index=True)
    assert not index.stop_index.duplicated().any()
    assert points.stop_index.max() < gpd.read_parquet(land_use_run / "land_use_stops.parquet").stop_index.min()
