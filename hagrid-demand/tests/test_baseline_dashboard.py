import json


def test_dashboard_accepts_failed_run_and_uses_manifest_stage_status(tmp_path):
    from hagrid_demand.baseline.dashboard import register_report, render_catalog

    root = tmp_path / "dashboard"; run = tmp_path / "failed-run"; run.mkdir()
    (run / "run.json").write_text(json.dumps({"run_id": "failed-run", "status": "failed", "baseline_fingerprint": "a" * 64}), encoding="utf-8")
    (run / "stage_manifest.json").write_text(json.dumps({"stages": {"daily": {"status": "blocked"}, "reference": {"status": "complete"}}}), encoding="utf-8")
    (run / "config.resolved.json").write_text(json.dumps({"output_dir": str(tmp_path), "dashboard_root": str(root)}), encoding="utf-8")

    entry = register_report(run, root)
    index = render_catalog(root)
    catalog = json.loads((root / "report_catalog.json").read_text(encoding="utf-8"))
    assert entry["stage_status"]["daily"] == "blocked"
    assert next(item for item in catalog["runs"] if item["run_id"] == "failed-run")["stage_status"]["daily"] == "blocked"
    assert index.name == "index.html" and not list(root.glob("daily.html"))
