from pathlib import Path


def test_topdown_provenance_hash_changes_when_a_consumed_neutral_dependency_changes(tmp_path):
    """Changing data.py must alter the exact provenance map emitted by topdown."""
    from hagrid_demand.experimental import topdown

    package = tmp_path / "hagrid_demand"
    for relative in ("experimental/topdown.py", "experimental/model.py", "data.py", "pipeline.py"):
        path = package / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"# {relative}\nvalue = 1\n", encoding="utf-8")

    first = topdown._code_hashes(package)
    (package / "data.py").write_text("value = 2\n", encoding="utf-8")
    changed = topdown._code_hashes(package)

    assert set(first) == {"experimental/topdown.py", "experimental/model.py", "data.py", "pipeline.py"}
    assert first["data.py"] != changed["data.py"]
    assert first != changed


def test_experimental_commands_inventory_direct_neutral_dependencies_with_stable_package_keys(tmp_path):
    """Every formerly standalone command records the neutral modules it executes."""
    from hagrid_demand.experimental import (
        continuation_report,
        households,
        logistics_osm,
        reconstruct,
        street_reconstruct,
        topdown,
    )

    package = tmp_path / "hagrid_demand"
    expected = {
        topdown: {"experimental/topdown.py", "experimental/model.py", "data.py", "pipeline.py"},
        reconstruct: {"experimental/reconstruct.py", "experimental/model.py", "data.py", "pipeline.py", "scope.py"},
        logistics_osm: {"experimental/logistics_osm.py", "data.py", "pipeline.py", "scope.py"},
        households: {"experimental/households.py", "data.py", "pipeline.py"},
        continuation_report: {"experimental/continuation_report.py", "experimental/logistics_osm.py", "data.py", "pipeline.py"},
        street_reconstruct: {"experimental/street_reconstruct.py", "experimental/model.py", "data.py", "pipeline.py", "scope.py"},
    }
    for paths in expected.values():
        for relative in paths:
            path = package / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"# {relative}\n", encoding="utf-8")

    for module, paths in expected.items():
        assert set(module._code_hashes(package)) == paths
