"""Streaming writers for daily demand artefacts."""

from __future__ import annotations

from pathlib import Path
from typing import Iterator

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq


_GROUP_COLUMNS = ["date", "year", "outer_id", "inner_id", "plz", "segment", "carrier", "allocation_status"]
_DETAIL_COLUMNS = _GROUP_COLUMNS[:4] + ["site_id", "plz", "segment", "carrier", "allocation_status", "baseline_expected", "conditional_expected", "count"]


_DETAIL_SORT = ["date", "segment", "plz", "site_id", "carrier"]


class DetailWriters:
    """Open Parquet writers of the selected draws: every day appends one row group instead of rewriting the file.

    Rewriting the growing detail file each day made multi-year runs quadratic in time and memory (the whole file was
    read back every day); appending keeps the file sorted by date with each day sorted internally.
    """

    def __init__(self, output: Path):
        self.output = Path(output)
        self.writers: dict[Path, tuple[pq.ParquetWriter, pa.Schema]] = {}

    def write(self, key: tuple[int, int], rows: pd.DataFrame) -> None:
        path = self.output / "details" / f"outer-{key[0]}-inner-{key[1]}.parquet"
        value = rows.loc[:, _DETAIL_COLUMNS].sort_values(_DETAIL_SORT, kind="stable")
        table = pa.Table.from_pandas(value, preserve_index=False)
        entry = self.writers.get(path)
        if entry is None:
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.exists():
                path.unlink()  # leftover of an interrupted build
            entry = self.writers[path] = (pq.ParquetWriter(path, table.schema, compression="zstd"), table.schema)
        else:
            table = table.cast(entry[1])
        entry[0].write_table(table)

    def close(self) -> None:
        for writer, _ in self.writers.values():
            writer.close()
        self.writers.clear()


def write_detail_draws(chunk: pd.DataFrame, selected_draws: set[tuple[int, int]], output: Path,
                       writers: DetailWriters | None = None) -> None:
    """Persist selected draw detail from the already-consumed daily chunk (appending to open *writers*)."""
    required = set(_DETAIL_COLUMNS)
    if missing := required.difference(chunk.columns):
        raise ValueError(f"daily detail missing columns: {sorted(missing)}")
    own = writers is None
    writers = DetailWriters(output) if own else writers
    try:
        for (outer_id, inner_id), rows in chunk.groupby(["outer_id", "inner_id"], sort=False):
            key = (int(outer_id), int(inner_id))
            if key in selected_draws:
                writers.write(key, rows)
    finally:
        if own:
            writers.close()


def _aggregate(chunk: pd.DataFrame, frames: list[pd.DataFrame]) -> None:
    """Fold one daily chunk into the aggregate list, consolidating every 100 days to bound memory."""
    required = set(_GROUP_COLUMNS + ["baseline_expected", "conditional_expected", "count"])
    if missing := required.difference(chunk.columns):
        raise ValueError(f"daily aggregate missing columns: {sorted(missing)}")
    frames.append(chunk.groupby(_GROUP_COLUMNS, as_index=False, dropna=False).agg(
        baseline_expected=("baseline_expected", "sum"), conditional_expected=("conditional_expected", "sum"), count=("count", "sum")))
    if len(frames) >= 100:
        merged = pd.concat(frames, ignore_index=True).groupby(_GROUP_COLUMNS, as_index=False, dropna=False).agg(
            baseline_expected=("baseline_expected", "sum"), conditional_expected=("conditional_expected", "sum"), count=("count", "sum"))
        frames.clear()
        frames.append(merged)


def write_daily_aggregates(chunks: Iterator[pd.DataFrame], output: Path,
                           detail_draws: set[tuple[int, int]]) -> dict:
    """Consume a daily iterator once, writing aggregates and selected details."""
    output = Path(output)
    aggregate_frames: list[pd.DataFrame] = []
    writers = DetailWriters(output)
    try:
        for chunk in chunks:
            if not isinstance(chunk, pd.DataFrame):
                raise ValueError("daily chunks must be DataFrames")
            write_detail_draws(chunk, detail_draws, output, writers)
            _aggregate(chunk, aggregate_frames)
    finally:
        writers.close()
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
