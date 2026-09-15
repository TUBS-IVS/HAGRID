import json
import sys

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


def test_daily_scope_stops_before_reporting_success(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline

    config = json.loads(fixture_config.read_text())
    config["output_scope"] = "daily"
    fixture_config.write_text(json.dumps(config), encoding="utf-8")
    with pytest.raises(NotImplementedError, match="Plan 02"):
        run_baseline(fixture_config, "daily-fixture")


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
    """A valid JSON report must not prevent repair of the sibling Markdown report."""
    from hagrid_demand.baseline.dashboard import render_baseline
    from hagrid_demand.baseline.workflow import run_baseline
    from hagrid_demand.common.provenance import resource_hash

    run = run_baseline(fixture_config, f"markdown-{replacement is None}")
    public = run / "report.md"
    stage_copy = run / "report" / "report.md"
    if replacement is None:
        public.unlink()
    else:
        public.write_text(replacement, encoding="utf-8")

    render_baseline(run)

    manifest = json.loads((run / "stage_manifest.json").read_text(encoding="utf-8"))
    assert public.read_bytes() == stage_copy.read_bytes()
    assert resource_hash(public) == manifest["stages"]["report"]["run_artifacts"]["report/report.md"]
    assert (run / "report_data.json").read_bytes() == (run / "report" / "report_data.json").read_bytes()


def test_renderer_rejects_a_tampered_markdown_stage_copy_even_when_the_public_copy_is_valid(fixture_config):
    """A public artifact is only trustworthy when its preserved source still matches the manifest."""
    from hagrid_demand.baseline.dashboard import render_baseline
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(fixture_config, "tampered-stage-markdown")
    (run / "report" / "report.md").write_text("tampered stage markdown", encoding="utf-8")

    with pytest.raises(ValueError, match="hash mismatch"):
        render_baseline(run)


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
