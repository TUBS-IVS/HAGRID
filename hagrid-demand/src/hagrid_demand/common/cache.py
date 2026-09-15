"""Content-addressed, validated stage cache publication."""

from __future__ import annotations

from contextlib import contextmanager
import errno
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import tempfile
import threading
import time
import uuid
from typing import Any, Callable

from hagrid_demand.baseline.config import SCHEMA_VERSION
from hagrid_demand.common.provenance import canonical_digest, resource_hash
from hagrid_demand.common.rng import RNG_VERSION


_MANIFEST = "stage_manifest.json"
_LOCAL_LOCKS: dict[str, threading.Lock] = {}
_LOCAL_LOCKS_GUARD = threading.Lock()


def _semantic(value: Any, *, resource_tree: bool = False) -> Any:
    if isinstance(value, Path):
        return resource_hash(value, resources_only=resource_tree)
    if isinstance(value, dict):
        if any(not isinstance(key, str) for key in value):
            raise ValueError("Semantic mapping keys must be strings")
        return {key: _semantic(item, resource_tree=resource_tree) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_semantic(item, resource_tree=resource_tree) for item in value]
    return value


def stage_key(name: str, dependencies: dict, config: dict, code_hashes: dict) -> str:
    """Fingerprint every supplied input, resource tree, config value, and runtime version."""
    if not isinstance(name, str) or not name:
        raise ValueError("stage name must be a non-empty string")
    return canonical_digest({
        "stage": name,
        "dependencies": _semantic(dependencies),
        "config": _semantic(config),
        "code": _semantic(code_hashes, resource_tree=True),
        "schema_version": config.get("schema_version", SCHEMA_VERSION),
        "rng_version": config.get("rng_version", RNG_VERSION),
        "packages": {"numpy": importlib.metadata.version("numpy"), "scipy": importlib.metadata.version("scipy")},
    })


def _artifact_hashes(path: Path) -> dict[str, str]:
    def digest(file: Path) -> str:
        value = resource_hash(file)
        return value if isinstance(value, str) else canonical_digest({"files": value})

    return {
        file.relative_to(path).as_posix(): digest(file)
        for file in sorted(path.rglob("*"), key=lambda item: item.as_posix())
        if file.is_file() and file.name != _MANIFEST
    }


def _atomic_json(path: Path, value: dict) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False), encoding="utf-8")
    os.replace(temporary, path)


def _complete_manifest(path: Path, *, stage_name: str, fingerprint: str, dependencies: dict) -> dict:
    return {
        "status": "complete",
        "stage_name": stage_name,
        "fingerprint": fingerprint,
        "dependencies": _semantic(dependencies),
        "schema_version": SCHEMA_VERSION,
        "rng_version": RNG_VERSION,
        "runtime": {"numpy": importlib.metadata.version("numpy"), "scipy": importlib.metadata.version("scipy")},
        "artifacts": _artifact_hashes(path),
    }


def _valid_artifact_map(artifacts: Any) -> bool:
    if not isinstance(artifacts, dict) or not artifacts:
        return False
    for relative, digest in artifacts.items():
        relative_path = Path(relative) if isinstance(relative, str) else None
        if (
            relative_path is None
            or relative_path.is_absolute()
            or ".." in relative_path.parts
            or not isinstance(digest, str)
            or len(digest) != 64
            or any(character not in "0123456789abcdef" for character in digest)
        ):
            return False
    return True


def _valid_manifest_shape(manifest: Any) -> bool:
    if not isinstance(manifest, dict):
        return False
    if not isinstance(manifest.get("dependencies"), dict):
        return False
    runtime = manifest.get("runtime")
    if not isinstance(runtime, dict) or set(runtime) != {"numpy", "scipy"} or not all(
        isinstance(version, str) for version in runtime.values()
    ):
        return False
    return _valid_artifact_map(manifest.get("artifacts"))


def _is_valid_stage(path: Path, *, stage_name: str, fingerprint: str, dependencies: dict) -> bool:
    manifest_path = path / _MANIFEST
    if not (path.is_dir() and manifest_path.is_file()):
        return False
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    if not _valid_manifest_shape(manifest):
        return False
    try:
        return (
            manifest.get("status") == "complete"
            and manifest.get("stage_name") == stage_name
            and manifest.get("fingerprint") == fingerprint
            and manifest.get("dependencies") == _semantic(dependencies)
            and type(manifest.get("schema_version")) is int
            and manifest.get("schema_version") == SCHEMA_VERSION
            and type(manifest.get("rng_version")) is int
            and manifest.get("rng_version") == RNG_VERSION
            and manifest.get("runtime") == {"numpy": importlib.metadata.version("numpy"), "scipy": importlib.metadata.version("scipy")}
            and bool(manifest.get("artifacts"))
            and manifest["artifacts"] == _artifact_hashes(path)
        )
    except (AttributeError, OSError, TypeError, ValueError):
        return False


def publish_stage(path: Path, write: Callable[[Path], None], validate: Callable[[Path], None]) -> None:
    """Write and validate a sibling temporary directory before atomically publishing it."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=f".{path.name}.tmp-", dir=path.parent))
    try:
        write(work)
        validate(work)
        try:
            os.rename(work, path)
        except OSError as exc:
            # A concurrent writer won. It owns the published cache entry.
            if exc.errno not in {errno.EEXIST, errno.ENOTEMPTY} or not path.is_dir():
                raise
    finally:
        if work.exists():
            shutil.rmtree(work)


def _record_run_stage(run_dir: Path, *, stage_name: str, fingerprint: str, cache_path: Path,
                      dependencies: dict, artifacts: dict[str, str], run_artifacts: dict[str, str]) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = run_dir / _MANIFEST
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        manifest = {"stages": {}}
    if not isinstance(manifest, dict) or not isinstance(manifest.get("stages"), dict):
        manifest = {"stages": {}}
    manifest.setdefault("stages", {})[stage_name] = {
        "fingerprint": fingerprint,
        "cache_path": str(cache_path),
        "dependencies": _semantic(dependencies),
        "artifacts": artifacts,
        "run_artifacts": run_artifacts,
    }
    _atomic_json(manifest_path, manifest)


def _local_lock(cache_path: Path) -> threading.Lock:
    key = str(cache_path.resolve())
    with _LOCAL_LOCKS_GUARD:
        return _LOCAL_LOCKS.setdefault(key, threading.Lock())


def _try_os_lock(stream) -> bool:
    if os.name == "nt":
        import msvcrt

        stream.seek(0)
        try:
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            return True
        except OSError as exc:
            if exc.errno in {errno.EACCES, errno.EAGAIN} or getattr(exc, "winerror", None) in {32, 33}:
                return False
            raise
    import fcntl

    try:
        fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except BlockingIOError:
        return False


def _unlock_os_lock(stream) -> None:
    if os.name == "nt":
        import msvcrt

        stream.seek(0)
        msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        return
    import fcntl

    fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


@contextmanager
def _fingerprint_lock(cache_path: Path):
    """Claim a fingerprint with an OS lock that is released if its owner exits."""
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    # Keep the file after release: deleting a locked pathname could let another
    # process create and lock a different inode while the first owner is live.
    lock_path = cache_path.with_name(f".{cache_path.name}.lockfile")
    with _local_lock(cache_path):
        with lock_path.open("a+b") as stream:
            if lock_path.stat().st_size == 0:
                stream.write(b"\0")
                stream.flush()
            while not _try_os_lock(stream):
                time.sleep(0.01)
            try:
                yield
            finally:
                _unlock_os_lock(stream)


def _discard_invalid_stage(cache_path: Path) -> None:
    if not cache_path.exists():
        return
    stale = cache_path.with_name(f".{cache_path.name}.invalid-{uuid.uuid4().hex}")
    os.rename(cache_path, stale)
    shutil.rmtree(stale)


def _publish_run_artifacts(run_dir: Path, *, stage_name: str, cache_path: Path,
                           artifacts: dict[str, str]) -> dict[str, str]:
    """Atomically publish a verified, run-local copy of immutable cache artifacts."""
    target = run_dir / stage_name
    expected = {f"{stage_name}/{relative}": digest for relative, digest in artifacts.items()}
    if target.exists() and _artifact_hashes(target) == artifacts:
        return expected
    if target.exists():
        stale = target.with_name(f".{target.name}.run.invalid-{uuid.uuid4().hex}")
        os.rename(target, stale)
        shutil.rmtree(stale)
    target.parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=f".{target.name}.run.tmp-", dir=target.parent))
    try:
        for relative, digest in artifacts.items():
            source = cache_path / relative
            destination = work / relative
            if not source.is_file() or _artifact_hashes(cache_path).get(relative) != digest:
                raise ValueError(f"Cache artifact changed before copy: {relative}")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        if _artifact_hashes(work) != artifacts:
            raise ValueError("Copied run artifacts did not match verified cache hashes")
        try:
            os.rename(work, target)
        except OSError as exc:
            if exc.errno not in {errno.EEXIST, errno.ENOTEMPTY} or _artifact_hashes(target) != artifacts:
                raise
    finally:
        if work.exists():
            shutil.rmtree(work)
    return expected


def resolve_stage(run_dir: Path, stage_name: str, fingerprint: str, *, cache_root: Path,
                  dependencies: dict, build: Callable[[Path], None], validate: Callable[[Path], None]) -> Path:
    """Reuse only a complete verified cache entry; otherwise build and publish one."""
    cache_path = Path(cache_root) / stage_name / fingerprint
    with _fingerprint_lock(cache_path):
        if not _is_valid_stage(cache_path, stage_name=stage_name, fingerprint=fingerprint, dependencies=dependencies):
            _discard_invalid_stage(cache_path)

            def write(work: Path) -> None:
                build(work)
                validate(work)
                _atomic_json(work / _MANIFEST, _complete_manifest(
                    work, stage_name=stage_name, fingerprint=fingerprint, dependencies=dependencies
                ))

            publish_stage(cache_path, write, lambda work: _is_valid_stage(
                work, stage_name=stage_name, fingerprint=fingerprint, dependencies=dependencies
            ))
    if not _is_valid_stage(cache_path, stage_name=stage_name, fingerprint=fingerprint, dependencies=dependencies):
        raise ValueError(f"Stage cache was not validly published: {stage_name}")
    manifest = json.loads((cache_path / _MANIFEST).read_text(encoding="utf-8"))
    run_artifacts = _publish_run_artifacts(Path(run_dir), stage_name=stage_name, cache_path=cache_path,
                                           artifacts=manifest["artifacts"])
    _record_run_stage(Path(run_dir), stage_name=stage_name, fingerprint=fingerprint, cache_path=cache_path,
                      dependencies=dependencies, artifacts=manifest["artifacts"], run_artifacts=run_artifacts)
    return cache_path
