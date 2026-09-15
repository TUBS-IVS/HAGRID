"""Content-addressed, validated stage cache publication."""

from __future__ import annotations

import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import tempfile
import uuid
from typing import Any, Callable

from hagrid_demand.baseline.config import SCHEMA_VERSION
from hagrid_demand.common.provenance import canonical_digest, resource_hash
from hagrid_demand.common.rng import RNG_VERSION


_MANIFEST = "stage_manifest.json"


def _semantic(value: Any, *, resource_tree: bool = False) -> Any:
    if isinstance(value, Path):
        return resource_hash(value, resources_only=resource_tree)
    if isinstance(value, dict):
        return {str(key): _semantic(item, resource_tree=resource_tree) for key, item in value.items()}
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
    return {
        file.relative_to(path).as_posix(): resource_hash(file)
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


def _is_valid_stage(path: Path, *, stage_name: str, fingerprint: str, dependencies: dict) -> bool:
    manifest_path = path / _MANIFEST
    if not (path.is_dir() and manifest_path.is_file()):
        return False
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    try:
        return (
            manifest.get("status") == "complete"
            and manifest.get("stage_name") == stage_name
            and manifest.get("fingerprint") == fingerprint
            and manifest.get("dependencies") == _semantic(dependencies)
            and manifest.get("schema_version") == SCHEMA_VERSION
            and manifest.get("rng_version") == RNG_VERSION
            and manifest.get("runtime") == {"numpy": importlib.metadata.version("numpy"), "scipy": importlib.metadata.version("scipy")}
            and bool(manifest.get("artifacts"))
            and manifest["artifacts"] == _artifact_hashes(path)
        )
    except (OSError, ValueError):
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
        except FileExistsError:
            # A concurrent writer won. It owns the published cache entry.
            if not path.is_dir():
                raise
    finally:
        if work.exists():
            shutil.rmtree(work)


def _record_run_stage(run_dir: Path, *, stage_name: str, fingerprint: str, cache_path: Path,
                      dependencies: dict, artifacts: dict[str, str]) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = run_dir / _MANIFEST
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        manifest = {"stages": {}}
    manifest.setdefault("stages", {})[stage_name] = {
        "fingerprint": fingerprint,
        "cache_path": str(cache_path),
        "dependencies": _semantic(dependencies),
        "artifacts": artifacts,
    }
    _atomic_json(manifest_path, manifest)


def resolve_stage(run_dir: Path, stage_name: str, fingerprint: str, *, cache_root: Path,
                  dependencies: dict, build: Callable[[Path], None], validate: Callable[[Path], None]) -> Path:
    """Reuse only a complete verified cache entry; otherwise build and publish one."""
    cache_path = Path(cache_root) / stage_name / fingerprint
    if not _is_valid_stage(cache_path, stage_name=stage_name, fingerprint=fingerprint, dependencies=dependencies):
        if cache_path.exists():
            stale = cache_path.with_name(f".{cache_path.name}.invalid-{uuid.uuid4().hex}")
            try:
                os.rename(cache_path, stale)
            except FileNotFoundError:
                pass
            else:
                shutil.rmtree(stale)

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
    _record_run_stage(Path(run_dir), stage_name=stage_name, fingerprint=fingerprint, cache_path=cache_path,
                      dependencies=dependencies, artifacts=manifest["artifacts"])
    return cache_path
