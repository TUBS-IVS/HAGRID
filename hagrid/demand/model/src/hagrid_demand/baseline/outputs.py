"""Streaming writers for daily demand artefacts."""

from __future__ import annotations

from pathlib import Path
from typing import Iterator

import pandas as pd


_GROUP_COLUMNS = ["date", "year", "outer_id", "inner_id", "plz", "segment", "carrier", "allocation_status"]
_DETAIL_COLUMNS = _GROUP_COLUMNS[:4] + ["site_id", "plz", "segment", "carrier", "allocation_status", "baseline_expected", "conditional_expected", "count"]


def write_detail_draws(chunk: pd.DataFrame, selected_draws: set[tuple[int, int]], output: Path) -> None:
    """Persist selected draw detail from the already-consumed daily chunk."""
    output = Path(output)
    required = set(_DETAIL_COLUMNS)
    if missing := required.difference(chunk.columns):
        raise ValueError(f"daily detail missing columns: {sorted(missing)}")
    for (outer_id, inner_id), rows in chunk.groupby(["outer_id", "inner_id"], sort=False):
        key = (int(outer_id), int(inner_id))
        if key not in selected_draws:
            continue
        path = output / "details" / f"outer-{key[0]}-inner-{key[1]}.parquet"
        path.parent.mkdir(parents=True, exist_ok=True)
        value = rows.loc[:, _DETAIL_COLUMNS].copy()
        if path.exists():
            value = pd.concat([pd.read_parquet(path), value], ignore_index=True)
        value.sort_values(["date", "segment", "plz", "site_id", "carrier"], kind="stable").to_parquet(path, index=False)


def write_daily_aggregates(chunks: Iterator[pd.DataFrame], output: Path,
                           detail_draws: set[tuple[int, int]]) -> dict:
    """Consume a daily iterator once, writing aggregates and selected details."""
    output = Path(output)
    aggregate_frames = []
    for chunk in chunks:
        if not isinstance(chunk, pd.DataFrame):
            raise ValueError("daily chunks must be DataFrames")
        write_detail_draws(chunk, detail_draws, output)
        required = set(_GROUP_COLUMNS + ["baseline_expected", "conditional_expected", "count"])
        if missing := required.difference(chunk.columns):
            raise ValueError(f"daily aggregate missing columns: {sorted(missing)}")
        value = chunk.groupby(_GROUP_COLUMNS, as_index=False, dropna=False).agg(
            baseline_expected=("baseline_expected", "sum"), conditional_expected=("conditional_expected", "sum"), count=("count", "sum")
        )
        aggregate_frames.append(value)
    if aggregate_frames:
        result = pd.concat(aggregate_frames, ignore_index=True).groupby(_GROUP_COLUMNS, as_index=False, dropna=False).agg(
            baseline_expected=("baseline_expected", "sum"), conditional_expected=("conditional_expected", "sum"), count=("count", "sum")
        )
    else:
        result = pd.DataFrame(columns=_GROUP_COLUMNS + ["baseline_expected", "conditional_expected", "count"])
    if not result.empty and not pd.api.types.is_integer_dtype(result["count"]):
        if (result["count"] % 1 != 0).any():
            raise ValueError("daily counts must be integers")
        result["count"] = result["count"].astype("int64")
    result.sort_values(_GROUP_COLUMNS, kind="stable").to_parquet(output / "daily_aggregates.parquet", index=False)
    return {"path": str(output / "daily_aggregates.parquet"), "rows": int(len(result)), "detail_draws": sorted([list(item) for item in detail_draws])}
