"""Stable code-dependency hashes for experimental command outputs."""

from __future__ import annotations

from pathlib import Path

from hagrid_demand.common.provenance import file_digest


def code_hashes(package_root: Path, *relative_paths: str) -> dict[str, str]:
    """Hash declared package files under stable, POSIX-relative keys."""
    root = Path(package_root).resolve()
    hashes = {}
    for relative in relative_paths:
        path = (root / relative).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise ValueError(f"code dependency is not a package file: {relative}")
        hashes[path.relative_to(root).as_posix()] = file_digest(path)
    return hashes
