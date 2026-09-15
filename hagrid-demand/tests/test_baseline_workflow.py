import json

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
