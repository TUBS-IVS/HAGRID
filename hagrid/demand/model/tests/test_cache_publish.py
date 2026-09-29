from pathlib import Path


def _cache(tmp_path: Path, files: int) -> tuple[Path, dict[str, str]]:
    from hagrid_demand.common.cache import _artifact_hashes

    cache = tmp_path / "cache" / "daily" / "abc"
    (cache / "matsim").mkdir(parents=True)
    for index in range(files):
        (cache / "matsim" / f"day-{index}.bin").write_bytes(bytes([index]) * 1024)
    (cache / "aggregates.parquet").write_bytes(b"agg")
    return cache, _artifact_hashes(cache)


def test_publish_run_artifacts_hashes_the_cache_tree_once(tmp_path, monkeypatch):
    """Publishing N artifacts must not hash the whole cache N times (quadratic on multi-year stages)."""
    from hagrid_demand.common import cache as module

    cache, artifacts = _cache(tmp_path, files=12)
    calls: list[Path] = []
    original = module._artifact_hashes

    def counting(path: Path):
        calls.append(Path(path))
        return original(path)

    monkeypatch.setattr(module, "_artifact_hashes", counting)
    run = tmp_path / "run"
    expected = module._publish_run_artifacts(run, stage_name="daily", cache_path=cache, artifacts=artifacts)
    assert set(expected) == {f"daily/{relative}" for relative in artifacts}
    assert (run / "daily" / "matsim" / "day-11.bin").is_file()
    assert sum(1 for path in calls if path == cache) == 1
    assert len(calls) <= 3


def test_publish_run_artifacts_rejects_a_changed_cache_file(tmp_path):
    import pytest

    from hagrid_demand.common.cache import _publish_run_artifacts

    cache, artifacts = _cache(tmp_path, files=2)
    (cache / "matsim" / "day-0.bin").write_bytes(b"changed")
    with pytest.raises(ValueError, match="changed before copy"):
        _publish_run_artifacts(tmp_path / "run", stage_name="daily", cache_path=cache, artifacts=artifacts)


def test_publish_run_artifacts_hard_links_the_cache_files(tmp_path):
    import os

    from hagrid_demand.common.cache import _publish_run_artifacts

    cache, artifacts = _cache(tmp_path, files=3)
    _publish_run_artifacts(tmp_path / "run", stage_name="daily", cache_path=cache, artifacts=artifacts)
    published = tmp_path / "run" / "daily" / "matsim" / "day-1.bin"
    assert published.read_bytes() == (cache / "matsim" / "day-1.bin").read_bytes()
    assert os.path.samefile(published, cache / "matsim" / "day-1.bin")  # one copy on disk
