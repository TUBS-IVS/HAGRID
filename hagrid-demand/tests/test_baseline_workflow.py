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
    assert "2021" in report and "Betriebstage" in report and "B2B" in report
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
    assert "in-scope Beobachtungen" in public.read_text(encoding="utf-8")
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
