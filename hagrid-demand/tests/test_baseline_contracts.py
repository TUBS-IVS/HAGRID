import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

import numpy as np
import pytest


_RESOLVE_STAGE_SUBPROCESS = """
import os
from pathlib import Path
import sys
import time
from hagrid_demand.common.cache import resolve_stage

root = Path(sys.argv[1])
mode = sys.argv[2]
cache_root = root / 'cache'

if mode == 'holder':
    def build(work):
        (work / 'result.json').write_text('{\"winner\": true}', encoding='utf-8')
        (root / 'holder-started').write_text('ready', encoding='utf-8')
        deadline = time.monotonic() + 10
        while not (root / 'release-holder').exists():
            if time.monotonic() > deadline:
                raise TimeoutError('holder release marker was not written')
            time.sleep(.01)
    resolve_stage(root / 'run-holder', 'reference', 'fingerprint', cache_root=cache_root,
                  dependencies={'source': 'abc'}, build=build, validate=lambda work: None)
elif mode == 'waiter':
    from hagrid_demand.common import cache as stage_cache
    original_try_os_lock = stage_cache._try_os_lock
    def observing_try_os_lock(stream):
        acquired = original_try_os_lock(stream)
        if not acquired:
            (root / 'waiter-contended').write_text('ready', encoding='utf-8')
        return acquired
    stage_cache._try_os_lock = observing_try_os_lock
    def build(work):
        (root / 'waiter-built').write_text('unexpected', encoding='utf-8')
        (work / 'result.json').write_text('{\"waiter\": true}', encoding='utf-8')
    resolve_stage(root / 'run-waiter', 'reference', 'fingerprint', cache_root=cache_root,
                  dependencies={'source': 'abc'}, build=build, validate=lambda work: None)
elif mode == 'crash-owner':
    def build(work):
        (root / 'crash-owner-started').write_text('ready', encoding='utf-8')
        os._exit(0)
    resolve_stage(root / 'run-crash-owner', 'reference', 'fingerprint', cache_root=cache_root,
                  dependencies={'source': 'abc'}, build=build, validate=lambda work: None)
elif mode == 'recovery':
    def build(work):
        (work / 'result.json').write_text('{\"recovered\": true}', encoding='utf-8')
    resolve_stage(root / 'run-recovery', 'reference', 'fingerprint', cache_root=cache_root,
                  dependencies={'source': 'abc'}, build=build, validate=lambda work: None)
else:
    raise ValueError(mode)
"""


def _stage_process(tmp_path, mode):
    package_root = Path(__file__).parents[1] / "src"
    return subprocess.Popen(
        [sys.executable, "-c", _RESOLVE_STAGE_SUBPROCESS, str(tmp_path), mode],
        cwd=Path(__file__).parents[1],
        env={**os.environ, "PYTHONPATH": f"{package_root}{os.pathsep}{os.environ.get('PYTHONPATH', '')}"},
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def _wait_for_marker(marker, *processes):
    deadline = time.monotonic() + 5
    while not marker.exists():
        finished = [process for process in processes if process.poll() is not None]
        if finished:
            output = [process.communicate() for process in finished]
            raise AssertionError(f"process exited before marker {marker.name}: {output}")
        if time.monotonic() >= deadline:
            raise AssertionError(f"timed out waiting for marker {marker.name}")
        time.sleep(0.01)


def _assert_process_succeeded(process):
    stdout, stderr = process.communicate(timeout=10)
    assert process.returncode == 0, f"stdout={stdout}\nstderr={stderr}"


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


def test_stage_key_rejects_non_string_mapping_keys_before_they_can_collide(tmp_path):
    """Integer and string keys must not be silently stringified into one semantic key."""
    from hagrid_demand.common.cache import stage_key

    source = tmp_path / "input.json"
    source.write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError, match="keys"):
        stage_key("reference", {1: source, "1": source}, {"seed": 42}, {})


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


@pytest.mark.parametrize("malformed", [[], {"status": "complete", "runtime": "wrong", "dependencies": [], "artifacts": []}])
def test_resolve_stage_rebuilds_parseable_but_malformed_manifests(tmp_path, malformed):
    """A parseable manifest with invalid shapes cannot trigger attribute errors or cache reuse."""
    from hagrid_demand.common.cache import resolve_stage

    cache = tmp_path / "cache"
    stage = cache / "reference" / "fingerprint"
    stage.mkdir(parents=True)
    (stage / "result.json").write_text('{"old": true}', encoding="utf-8")
    (stage / "stage_manifest.json").write_text(json.dumps(malformed), encoding="utf-8")
    calls = []

    def build(work):
        calls.append(1)
        (work / "result.json").write_text('{"fresh": true}', encoding="utf-8")

    resolve_stage(tmp_path / "run", "reference", "fingerprint", cache_root=cache,
                  dependencies={"source": "abc"}, build=build, validate=lambda work: None)
    assert calls == [1]
    assert json.loads((stage / "result.json").read_text(encoding="utf-8")) == {"fresh": True}


def test_resolve_stage_concurrent_writers_keep_the_first_valid_publication(tmp_path):
    """Two contenders for one fingerprint must build once and retain that valid winner."""
    from hagrid_demand.common.cache import resolve_stage

    barrier = threading.Barrier(2)
    cache = tmp_path / "cache"
    built = []
    failures = []

    def contender(label):
        try:
            barrier.wait(timeout=5)

            def build(work):
                built.append(label)
                (work / "winner.txt").write_text(label, encoding="utf-8")
                time.sleep(0.15)

            resolve_stage(tmp_path / f"run-{label}", "reference", "fingerprint", cache_root=cache,
                          dependencies={"source": "abc"}, build=build, validate=lambda work: None)
        except BaseException as exc:  # report failures after both threads join
            failures.append(exc)

    threads = [threading.Thread(target=contender, args=(label,)) for label in ("first", "second")]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=10)
    assert not failures
    assert all(not thread.is_alive() for thread in threads)
    assert len(built) == 1
    assert (cache / "reference" / "fingerprint" / "winner.txt").read_text(encoding="utf-8") == built[0]


def test_resolve_stage_reclaims_a_crashed_legacy_lock_directory_without_waiting(tmp_path, monkeypatch):
    """A lock directory left by a dead older process must not block the new OS lock."""
    from hagrid_demand.common import cache as stage_cache

    cache_root = tmp_path / "cache"
    legacy_lock = cache_root / "reference" / ".fingerprint.lock"
    legacy_lock.mkdir(parents=True)
    clock = iter([0, 31])
    monkeypatch.setattr(stage_cache.time, "monotonic", lambda: next(clock))
    calls = []

    def build(work):
        calls.append(1)
        (work / "result.json").write_text('{"ok": true}', encoding="utf-8")

    stage_cache.resolve_stage(tmp_path / "run", "reference", "fingerprint", cache_root=cache_root,
                              dependencies={"source": "abc"}, build=build, validate=lambda work: None)
    assert calls == [1]


def test_resolve_stage_waiter_reuses_winner_after_a_build_outlasts_old_deadline(tmp_path, monkeypatch):
    """A legitimate long build makes a waiter wait and reuse, never time out or steal it."""
    from hagrid_demand.common import cache as stage_cache

    cache_root = tmp_path / "cache"
    build_started = threading.Event()
    release_build = threading.Event()
    waiter_started = threading.Event()
    failures = []
    calls = []
    clock = iter([0, 31, 62, 93])
    monkeypatch.setattr(stage_cache.time, "monotonic", lambda: next(clock))

    def build(work):
        calls.append("winner")
        (work / "result.json").write_text('{"winner": true}', encoding="utf-8")
        build_started.set()
        assert release_build.wait(timeout=5)

    def winner():
        try:
            stage_cache.resolve_stage(tmp_path / "run-winner", "reference", "fingerprint", cache_root=cache_root,
                                      dependencies={"source": "abc"}, build=build, validate=lambda work: None)
        except BaseException as exc:
            failures.append(exc)

    def waiter():
        try:
            assert build_started.wait(timeout=5)
            waiter_started.set()
            stage_cache.resolve_stage(tmp_path / "run-waiter", "reference", "fingerprint", cache_root=cache_root,
                                      dependencies={"source": "abc"}, build=lambda work: calls.append("waiter"),
                                      validate=lambda work: None)
        except BaseException as exc:
            failures.append(exc)

    winner_thread = threading.Thread(target=winner)
    waiter_thread = threading.Thread(target=waiter)
    winner_thread.start()
    assert build_started.wait(timeout=5)
    waiter_thread.start()
    assert waiter_started.wait(timeout=5)
    time.sleep(0.05)
    release_build.set()
    winner_thread.join(timeout=10)
    waiter_thread.join(timeout=10)
    assert not failures
    assert all(not thread.is_alive() for thread in (winner_thread, waiter_thread))
    assert calls == ["winner"]


def test_resolve_stage_subprocess_waiter_reuses_os_lock_winner(tmp_path):
    """A separate process waits on the held OS lock and then reuses its winner."""
    holder = _stage_process(tmp_path, "holder")
    waiter = None
    try:
        _wait_for_marker(tmp_path / "holder-started", holder)
        waiter = _stage_process(tmp_path, "waiter")
        _wait_for_marker(tmp_path / "waiter-contended", holder, waiter)
        assert holder.poll() is None
        assert waiter.poll() is None
        assert not (tmp_path / "waiter-built").exists()
        (tmp_path / "release-holder").write_text("release", encoding="utf-8")
        _assert_process_succeeded(holder)
        _assert_process_succeeded(waiter)
    finally:
        (tmp_path / "release-holder").write_text("release", encoding="utf-8")
        for process in (holder, waiter):
            if process is not None and process.poll() is None:
                process.terminate()
                process.wait(timeout=5)
    stage = tmp_path / "cache" / "reference" / "fingerprint"
    assert json.loads((stage / "result.json").read_text(encoding="utf-8")) == {"winner": True}
    assert not (tmp_path / "waiter-built").exists()


def test_resolve_stage_subprocess_recovers_after_owner_abruptly_exits(tmp_path):
    """OS process-exit cleanup releases a lock held by an abruptly terminated owner."""
    owner = _stage_process(tmp_path, "crash-owner")
    _wait_for_marker(tmp_path / "crash-owner-started", owner)
    _assert_process_succeeded(owner)
    assert not (tmp_path / "cache" / "reference" / "fingerprint").exists()

    recovery = _stage_process(tmp_path, "recovery")
    _assert_process_succeeded(recovery)
    stage = tmp_path / "cache" / "reference" / "fingerprint"
    assert json.loads((stage / "result.json").read_text(encoding="utf-8")) == {"recovered": True}


def test_resolve_stage_copies_verified_artifacts_into_the_run_and_retries_copy_failure(tmp_path, monkeypatch):
    """Run artifacts are hash-checked copies, and failed copies never become visible."""
    from hagrid_demand.common import cache as stage_cache

    cache_root = tmp_path / "cache"
    run = tmp_path / "run"

    def build(work):
        (work / "result.json").write_text('{"ok": true}', encoding="utf-8")

    original_copy2 = stage_cache.shutil.copy2

    def broken_copy(*args, **kwargs):
        raise OSError("disk full")

    monkeypatch.setattr(stage_cache.shutil, "copy2", broken_copy)
    with pytest.raises(OSError, match="disk full"):
        stage_cache.resolve_stage(run, "reference", "fingerprint", cache_root=cache_root,
                                  dependencies={"source": "abc"}, build=build, validate=lambda work: None)
    assert not (run / "reference").exists()
    assert not list(run.glob(".reference.run.tmp-*"))

    monkeypatch.setattr(stage_cache.shutil, "copy2", original_copy2)
    stage = stage_cache.resolve_stage(run, "reference", "fingerprint", cache_root=cache_root,
                                      dependencies={"source": "abc"}, build=build, validate=lambda work: None)
    copied = run / "reference" / "result.json"
    assert copied.read_text(encoding="utf-8") == (stage / "result.json").read_text(encoding="utf-8")
    copied.write_text('{"tampered": true}', encoding="utf-8")
    stage_cache.resolve_stage(run, "reference", "fingerprint", cache_root=cache_root,
                              dependencies={"source": "abc"}, build=build, validate=lambda work: None)
    assert copied.read_text(encoding="utf-8") == '{"ok": true}'
    run_manifest = json.loads((run / "stage_manifest.json").read_text(encoding="utf-8"))
    assert run_manifest["stages"]["reference"]["run_artifacts"] == {"reference/result.json": run_manifest["stages"]["reference"]["artifacts"]["result.json"]}


def test_resolve_stage_uses_one_dependency_snapshot_and_aborts_if_a_live_input_changes_during_build(tmp_path):
    """A cache key and its manifest cannot certify different bytes after a build-time mutation."""
    from hagrid_demand.common.cache import dependency_snapshot, resolve_stage, stage_key

    source = tmp_path / "input.json"
    source.write_text('{"revision": 1}', encoding="utf-8")
    dependencies = {"source": source}
    snapshot = dependency_snapshot(dependencies)
    fingerprint = stage_key("reference", dependencies, {"schema_version": 1, "rng_version": 1}, {},
                            dependency_snapshot=snapshot)
    build_started, release = threading.Event(), threading.Event()
    errors = []

    def build(work):
        (work / "result.json").write_text('{"built": true}', encoding="utf-8")
        build_started.set()
        assert release.wait(timeout=5)

    def worker():
        try:
            resolve_stage(tmp_path / "run", "reference", fingerprint, cache_root=tmp_path / "cache",
                          dependencies=dependencies, dependency_snapshot=snapshot,
                          build=build, validate=lambda work: None)
        except BaseException as exc:
            errors.append(exc)

    thread = threading.Thread(target=worker)
    thread.start()
    assert build_started.wait(timeout=5)
    source.write_text('{"revision": 2}', encoding="utf-8")
    release.set()
    thread.join(timeout=10)
    assert errors and isinstance(errors[0], ValueError)
    assert "changed during stage build" in str(errors[0])
    assert not (tmp_path / "cache" / "reference" / fingerprint).exists()
    assert not (tmp_path / "run" / "reference").exists()


def test_baseline_config_rejects_outputs_inside_all_declared_input_paths(tmp_path):
    """Every declared source and baseline input protects its subtree from outputs."""
    from hagrid_demand.baseline.config import load_baseline_config

    (tmp_path / "inputs").mkdir()
    (tmp_path / "baseline-input").mkdir()
    (tmp_path / "source-input").mkdir()
    config = tmp_path / "baseline.json"
    base = {
        "schema_version": 1, "rng_version": 1, "seed": 42, "input_dir": "inputs", "output_dir": "runs",
        "source_mode": "raw", "reference_year": 2021, "reference_operating_days": 313,
        "output_scope": "reference", "baseline_run": "baseline-input",
        "sources": [{"id": "raw", "path": "source-input"}],
    }
    for output_key, output_value in [
        ("output_dir", "baseline-input/runs"),
        ("cache_root", "source-input/cache"),
        ("dashboard_root", "source-input/dashboard"),
    ]:
        payload = {**base, output_key: output_value}
        config.write_text(json.dumps(payload), encoding="utf-8")
        with pytest.raises(ValueError, match="output"):
            load_baseline_config(config)

    payload = {**base, "reference_operating_days": True}
    config.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError, match="operating_days"):
        load_baseline_config(config)


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
