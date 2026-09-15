import json
import os
from pathlib import Path
import subprocess
import sys

import numpy as np
import pytest


def test_named_rng_is_order_independent_and_channels_are_separate():
    """Changing the named stream must change draws; argument ordering must not."""
    from hagrid_demand.common.rng import named_rng

    first = named_rng(42, date="2030-01-02", channel="count").integers(0, 1000, 20)
    repeated = named_rng(42, channel="count", date="2030-01-02").integers(0, 1000, 20)
    other_channel = named_rng(42, date="2030-01-02", channel="location").integers(0, 1000, 20)

    assert np.array_equal(first, repeated)
    assert not np.array_equal(first, other_channel)


def test_canonical_digest_normalizes_unicode_and_rejects_colliding_keys():
    """Equivalent Unicode context must hash identically without silently merging keys."""
    from hagrid_demand.common.provenance import canonical_digest

    assert canonical_digest({"cafe\u0301": "e\u0301"}) == canonical_digest({"caf\u00e9": "\u00e9"})
    with pytest.raises(ValueError, match="collision"):
        canonical_digest({"cafe\u0301": 1, "caf\u00e9": 2})
    with pytest.raises(ValueError):
        canonical_digest({"value": float("nan")})


def test_assert_balance_rejects_material_error_and_accepts_tolerance():
    """A balance guard must catch a real quantity mismatch while allowing its documented tolerance."""
    from hagrid_demand.common.contracts import assert_balance

    assert_balance(100.0 + 5e-9, 100.0)
    with pytest.raises(ValueError, match="Balance"):
        assert_balance(99, 100)


def test_baseline_config_resolves_paths_and_rejects_unsafe_or_unknown_values(tmp_path):
    """Config paths are anchored at their file and cannot overwrite consumed inputs."""
    from hagrid_demand.baseline.config import load_baseline_config

    inputs = tmp_path / "inputs"
    inputs.mkdir()
    config = tmp_path / "baseline.json"
    config.write_text(json.dumps({
        "schema_version": 1,
        "rng_version": 1,
        "seed": 42,
        "input_dir": "inputs",
        "output_dir": "runs",
        "source_mode": "raw",
        "reference_year": 2021,
        "reference_operating_days": 313,
        "output_scope": "reference",
        "dates": ["2021-01-01"],
    }), encoding="utf-8")
    loaded = load_baseline_config(config)
    assert loaded["input_dir"] == str(inputs.resolve())
    assert loaded["output_dir"] == str((tmp_path / "runs").resolve())
    assert loaded["cache_root"] == str((tmp_path / "runs" / ".stage-cache").resolve())

    unsafe = json.loads(config.read_text(encoding="utf-8"))
    unsafe["output_dir"] = "inputs/results"
    config.write_text(json.dumps(unsafe), encoding="utf-8")
    with pytest.raises(ValueError, match="output"):
        load_baseline_config(config)

    unsafe["output_dir"] = "runs"
    unsafe["unknown"] = True
    config.write_text(json.dumps(unsafe), encoding="utf-8")
    with pytest.raises(ValueError, match="Unknown"):
        load_baseline_config(config)

    unsafe.pop("unknown")
    unsafe["dates"] = ["2021-01-01", "2021-01-01"]
    config.write_text(json.dumps(unsafe), encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate"):
        load_baseline_config(config)


def test_stage_key_includes_dependencies_code_schema_and_rng_versions(tmp_path):
    """Changing any consumed semantic input must produce a distinct cache identity."""
    from hagrid_demand.common.cache import stage_key

    source = tmp_path / "input.json"
    source.write_text('{"value": 1}', encoding="utf-8")
    code = tmp_path / "module.py"
    code.write_text("VALUE = 1\n", encoding="utf-8")
    base = stage_key("reference", {"source": source}, {"schema_version": 1, "rng_version": 1, "seed": 42}, {"code": code})

    source.write_text('{"value": 2}', encoding="utf-8")
    assert stage_key("reference", {"source": source}, {"schema_version": 1, "rng_version": 1, "seed": 42}, {"code": code}) != base
    source.write_text('{"value": 1}', encoding="utf-8")
    code.write_text("VALUE = 2\n", encoding="utf-8")
    assert stage_key("reference", {"source": source}, {"schema_version": 1, "rng_version": 1, "seed": 42}, {"code": code}) != base
    assert stage_key("reference", {"source": source}, {"schema_version": 2, "rng_version": 1, "seed": 42}, {"code": code}) != base
    assert stage_key("reference", {"source": source}, {"schema_version": 1, "rng_version": 1, "seed": 43}, {"code": code}) != base


def test_publish_stage_never_exposes_failed_writer(tmp_path):
    """A failed builder must leave neither a final stage nor a valid cache marker."""
    from hagrid_demand.common.cache import publish_stage

    target = tmp_path / "stage"

    def broken_writer(work):
        (work / "partial.txt").write_text("partial", encoding="utf-8")
        raise RuntimeError("stop")

    with pytest.raises(RuntimeError, match="stop"):
        publish_stage(target, broken_writer, lambda work: None)
    assert not target.exists()
    assert not list(tmp_path.glob(".stage.tmp-*"))


def test_resolve_stage_rebuilds_incomplete_cache_and_records_verified_manifest(tmp_path):
    """A manifest without artifact hashes is incomplete and cannot be reused."""
    from hagrid_demand.common.cache import resolve_stage

    run = tmp_path / "run"
    cache = tmp_path / "cache"
    calls = []

    def build(work):
        calls.append(work)
        (work / "result.json").write_text('{"ok": true}', encoding="utf-8")

    def validate(work):
        assert json.loads((work / "result.json").read_text(encoding="utf-8"))["ok"]

    stage = resolve_stage(run, "reference", "fingerprint", cache_root=cache,
                          dependencies={"source": "abc"}, build=build, validate=validate)
    assert stage == cache / "reference" / "fingerprint"
    assert len(calls) == 1
    manifest = json.loads((stage / "stage_manifest.json").read_text(encoding="utf-8"))
    assert manifest["status"] == "complete"
    assert manifest["artifacts"]["result.json"]
    assert manifest["runtime"]["numpy"]
    assert manifest["runtime"]["scipy"]
    resolve_stage(run, "reference", "fingerprint", cache_root=cache,
                  dependencies={"source": "abc"}, build=build, validate=validate)
    assert len(calls) == 1

    (stage / "stage_manifest.json").write_text(json.dumps({"status": "complete", "dependencies": {"source": "abc"}}), encoding="utf-8")
    resolve_stage(run, "reference", "fingerprint", cache_root=cache,
                  dependencies={"source": "abc"}, build=build, validate=validate)
    assert len(calls) == 2


def test_baseline_help_has_no_experimental_model_imports():
    """The baseline command boundary remains usable without fitting dependencies."""
    result = subprocess.run(
        [sys.executable, "-c", "import sys; from hagrid_demand.cli import main; sys.argv=['hagrid-demand','baseline','--help'];\ntry: main()\nexcept SystemExit as exc: assert exc.code == 0\nprint('MODEL='+str('hagrid_demand.model' in sys.modules)); print('SKLEARN='+str(any(name.startswith('sklearn') for name in sys.modules)) )"],
        capture_output=True,
        text=True,
        check=True,
        env={**os.environ, "PYTHONPATH": str(Path(__file__).parents[1] / "src")},
    )
    assert "MODEL=False" in result.stdout
    assert "SKLEARN=False" in result.stdout
