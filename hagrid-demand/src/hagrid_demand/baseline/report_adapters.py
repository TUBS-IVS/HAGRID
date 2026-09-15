"""Adapters for reports produced before the consolidated dashboard schema."""

from __future__ import annotations

import json
from pathlib import Path


def adapt_existing_report(run: Path) -> dict:
    """Return a safe report envelope with explicit provenance and unsupported states."""
    run = Path(run)
    path = run / "report_data.json"
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"schema_version": 1, "status": "unsupported", "provenance": str(path),
                "diagnostic": "Missing or malformed historical report data."}
    if isinstance(value, dict) and value.get("schema_version") == 1:
        return {"schema_version": 1, "status": "adapted", "provenance": str(path), "report": value}
    return {"schema_version": 1, "status": "unsupported", "provenance": str(path),
            "diagnostic": "Unknown historical report schema."}
