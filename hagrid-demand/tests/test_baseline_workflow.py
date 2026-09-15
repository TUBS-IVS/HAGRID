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
