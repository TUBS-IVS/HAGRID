"""Canonical values and semantic resource hashes for reproducible stages."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any
import unicodedata


RESOURCE_SUFFIXES = {".py", ".html", ".json"}
SHAPEFILE_SUFFIXES = {".shp", ".shx", ".dbf", ".prj", ".cpg", ".qix", ".fix"}


def _normalise(value: Any) -> Any:
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        normalised = {}
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError("Canonical JSON object keys must be strings")
            key = unicodedata.normalize("NFC", key)
            if key in normalised:
                raise ValueError(f"Unicode-normalized key collision: {key!r}")
            normalised[key] = _normalise(item)
        return normalised
    if isinstance(value, (list, tuple)):
        return [_normalise(item) for item in value]
    return value


def canonical_json(value: Any) -> str:
    """Serialize a JSON-compatible value using the baseline's byte contract."""
    try:
        return json.dumps(
            _normalise(value), sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
        )
    except (TypeError, ValueError) as exc:
        raise ValueError(f"Value is not canonical JSON: {exc}") from exc


def canonical_digest(value: dict) -> str:
    """Return the SHA-256 of canonical UTF-8 JSON."""
    if not isinstance(value, dict):
        raise ValueError("canonical_digest requires a dictionary")
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resource_hash(path: Path, *, resources_only: bool = False) -> Any:
    """Hash a file or a recursively ordered resource tree without timestamps."""
    path = Path(path)
    if not path.exists():
        raise FileNotFoundError(path)
    if path.is_file():
        if path.suffix.lower() == ".shp":
            files = [path]
            for suffix in sorted(SHAPEFILE_SUFFIXES - {".shp"}):
                companion = path.with_suffix(suffix)
                if companion.exists():
                    files.append(companion)
            return {file.name: file_digest(file) for file in sorted(files, key=lambda item: item.name)}
        return file_digest(path)
    files = [file for file in path.rglob("*") if file.is_file()]
    if resources_only:
        files = [file for file in files if file.suffix.lower() in RESOURCE_SUFFIXES]
    return {
        file.relative_to(path).as_posix(): resource_hash(file)
        for file in sorted(files, key=lambda item: item.relative_to(path).as_posix())
    }
