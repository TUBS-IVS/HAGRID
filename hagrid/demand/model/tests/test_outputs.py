import numpy as np
import pandas as pd
import pyarrow.parquet as pq


def _chunk(date: str, sites: int, outer: int = 0, inner: int = 0) -> pd.DataFrame:
    stamp = pd.Timestamp(date)
    return pd.DataFrame({"date": stamp, "year": stamp.year, "outer_id": outer, "inner_id": inner,
                         "site_id": [f"s{i}" for i in range(sites)][::-1], "plz": "30159", "segment": "private", "carrier": "DHL",
                         "allocation_status": "located", "baseline_expected": 1.5, "conditional_expected": 1.5,
                         "count": np.arange(sites, dtype=np.int64)})


def test_detail_draws_append_one_row_group_per_day_without_rewriting(tmp_path):
    from hagrid_demand.baseline.outputs import write_daily_aggregates

    chunks = [_chunk("2025-01-02", 4), _chunk("2025-01-03", 3), _chunk("2025-01-03", 2, inner=1)]
    summary = write_daily_aggregates(iter(chunks), tmp_path, {(0, 0)})
    path = tmp_path / "details" / "outer-0-inner-0.parquet"
    assert path.is_file() and not (tmp_path / "details" / "outer-0-inner-1.parquet").exists()
    assert pq.ParquetFile(path).metadata.num_row_groups == 2
    details = pd.read_parquet(path)
    assert len(details) == 7
    assert details.date.is_monotonic_increasing
    assert details.loc[details.date.eq(pd.Timestamp("2025-01-02")), "site_id"].tolist() == ["s0", "s1", "s2", "s3"]
    aggregates = pd.read_parquet(tmp_path / "daily_aggregates.parquet")
    assert int(aggregates["count"].sum()) == sum(int(chunk["count"].sum()) for chunk in chunks)
    assert summary["rows"] == len(aggregates)


def test_detail_draws_replace_a_leftover_file_and_keep_the_first_schema(tmp_path):
    from hagrid_demand.baseline.outputs import DetailWriters

    path = tmp_path / "details" / "outer-0-inner-0.parquet"
    path.parent.mkdir(parents=True)
    _chunk("2020-01-01", 9).to_parquet(path, index=False)
    writers = DetailWriters(tmp_path)
    writers.write((0, 0), _chunk("2025-01-02", 2))
    later = _chunk("2025-01-03", 2).assign(count=lambda frame: frame["count"].astype("int32"))
    writers.write((0, 0), later)
    writers.close()
    details = pd.read_parquet(path)
    assert len(details) == 4 and str(details["count"].dtype) == "int64"


def test_aggregates_sum_many_chunks_per_day(tmp_path):
    from hagrid_demand.baseline.outputs import write_daily_aggregates

    chunks = (_chunk(f"2025-01-{day:02d}", 2) for day in range(1, 32) for _ in range(4))
    write_daily_aggregates(chunks, tmp_path, set())
    aggregates = pd.read_parquet(tmp_path / "daily_aggregates.parquet")
    assert len(aggregates) == 31 and int(aggregates["count"].sum()) == 31 * 4
