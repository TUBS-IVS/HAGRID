"""Compact annual store of daily deliveries per stop and PLZ, and on-demand MATSim export of any stored day."""

from __future__ import annotations

import json
import time
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

from hagrid_demand.compatibility.matsim_export import CARRIER_FIELDS, _safe10, stop_table_frame, write_demand_frame


_SUFFIX = {"private": "b2c", "business": "b2b"}


def store_columns() -> list[str]:
    """Count columns of ``stop_daily.parquet``: ``<short>_b2c`` and ``<short>_b2b`` per carrier."""
    return [f"{short}_{suffix}" for _, short in CARRIER_FIELDS.values() for suffix in ("b2c", "b2b")]


class AnnualStoreWriter:
    """Aggregate every simulated day to stops and PLZ and append it to the annual store."""

    def __init__(self, output_dir: Path, stops: pd.DataFrame, site_stops: pd.DataFrame, holidays: set[str] | None = None):
        self.directory = Path(output_dir) / "annual"
        self.directory.mkdir(parents=True, exist_ok=True)
        info = stops.drop_duplicates("stop_id").sort_values("stop_index", kind="stable")
        self.stop_index = info.stop_index.to_numpy(dtype=np.int32)
        self.position = pd.Series(np.arange(len(info)), index=info.stop_id.to_numpy())
        self.site_stop = site_stops.drop_duplicates("site_id").set_index("site_id").stop_id
        self.columns = store_columns()
        self.holidays = set(holidays or ())
        self.schema = pa.schema([("date", pa.date32()), ("stop", pa.int32())] + [(name, pa.uint16()) for name in self.columns])
        self.writer = pq.ParquetWriter(self.directory / "stop_daily.parquet", self.schema, compression="zstd")
        self.cache: dict[int, tuple] = {}
        self.plz_rows: list[tuple] = []
        self.day_rows: list[dict] = []
        self.weekday: dict[tuple[str, str], np.ndarray] = {}
        self.started = time.perf_counter()

    def _positions(self, sites: pd.DataFrame) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        # The frame itself is kept in the cache so its id cannot be reused by another frame.
        cached = self.cache.get(id(sites))
        if cached is None:
            ids = sites.site_id.astype(str)
            stop = self.site_stop.reindex(ids.to_numpy())
            if stop.isna().any():
                raise ValueError(f"sites without stop mapping: {ids[stop.isna().to_numpy()].tolist()[:5]}")
            positions = self.position.reindex(stop.to_numpy())
            if positions.isna().any():
                raise ValueError("site stop mapping refers to unknown stops")
            codes, index = np.unique(sites.plz.astype(str).to_numpy(), return_inverse=True)
            cached = (sites, positions.to_numpy(dtype=np.int64), codes, index)
            self.cache[id(sites)] = cached
        return cached[1], cached[2], cached[3]

    def add_day(self, date: pd.Timestamp, segment_days: dict) -> dict:
        date = pd.Timestamp(date).normalize()
        matrix = np.zeros((len(self.stop_index), len(self.columns)), dtype=np.int64)
        row = {"date": date, "weekday": int(date.dayofweek), "holiday": date.date().isoformat() in self.holidays,
               "b2c": 0, "b2b": 0, "sites_active": 0}
        for segment, item in segment_days.items():
            positions, codes, index = self._positions(item.sites)
            row["sites_active"] += int((item.counts.sum(axis=1) > 0).sum())
            for column_index, carrier in enumerate(item.carriers):
                if carrier not in CARRIER_FIELDS:
                    raise ValueError(f"carrier without store column: {carrier}")
                values = item.counts[:, column_index].astype(np.int64)
                total = int(values.sum())
                row[_SUFFIX[segment]] += total
                row[carrier] = row.get(carrier, 0) + total
                self.weekday.setdefault((segment, carrier), np.zeros(7))[date.dayofweek] += total
                if total == 0:
                    continue
                column = self.columns.index(f"{CARRIER_FIELDS[carrier][1]}_{_SUFFIX[segment]}")
                matrix[:, column] += np.bincount(positions, weights=values, minlength=len(self.stop_index)).astype(np.int64)
                by_plz = np.bincount(index, weights=values, minlength=len(codes)).astype(np.int64)
                self.plz_rows.extend((date, code, segment, carrier, int(parcels)) for code, parcels in zip(codes, by_plz) if parcels)
        per_stop = matrix.sum(axis=1)
        active = per_stop > 0
        if active.any():
            if matrix.max() > np.iinfo(np.uint16).max:
                raise ValueError("a stop-day count exceeds the uint16 store range")
            columns = {"date": pa.array([date.date()] * int(active.sum()), type=pa.date32()),
                       "stop": pa.array(self.stop_index[active], type=pa.int32())}
            columns.update({name: pa.array(matrix[active, position].astype(np.uint16), type=pa.uint16())
                            for position, name in enumerate(self.columns)})
            self.writer.write_table(pa.table(columns, schema=self.schema))
        served = per_stop[active]
        row.update({"parcels": int(per_stop.sum()), "stops_active": int(active.sum()),
                    "parcels_per_stop_mean": float(served.mean()) if len(served) else 0.,
                    "parcels_per_stop_median": float(np.median(served)) if len(served) else 0.,
                    "parcels_per_stop_p90": float(np.quantile(served, .9)) if len(served) else 0.,
                    "parcels_per_stop_max": int(served.max()) if len(served) else 0})
        self.day_rows.append(row)
        return row

    def close(self, extra: dict | None = None) -> dict:
        self.writer.close()
        plz = pd.DataFrame(self.plz_rows, columns=["date", "plz", "segment", "carrier", "parcels"])
        plz["parcels"] = plz.parcels.astype(np.int32)
        plz.to_parquet(self.directory / "plz_daily.parquet", index=False)
        days = pd.DataFrame(self.day_rows).fillna(0)
        for carrier in CARRIER_FIELDS:
            if carrier in days:
                days[carrier] = days[carrier].astype(np.int64)
        days.to_parquet(self.directory / "days.parquet", index=False)
        iso = days.date.dt.isocalendar()
        weekly = days.assign(iso_year=iso.year.to_numpy(), iso_week=iso.week.to_numpy()).groupby(["iso_year", "iso_week"]).parcels.sum()
        monthly = days.groupby(days.date.dt.strftime("%Y-%m")).parcels.sum()
        summary = {**(extra or {}),
                   "weekday_profile": [{"segment": segment, "carrier": carrier,
                                        "shares": (values / values.sum()).round(4).tolist() if values.sum() else [0.] * 7}
                                       for (segment, carrier), values in sorted(self.weekday.items())],
                   "weekly": [{"iso_year": int(year), "iso_week": int(week), "parcels": int(value)}
                              for (year, week), value in weekly.items()],
                   "monthly": {str(month): int(value) for month, value in monthly.items()},
                   "files": {path.name: path.stat().st_size for path in sorted(self.directory.glob("*.parquet"))},
                   "runtime_s": round(time.perf_counter() - self.started, 1)}
        (self.directory / "annual_summary.json").write_text(json.dumps(summary, indent=2, default=str), encoding="utf-8")
        return summary


def export_day(run_dir: Path, date: str, output_dir: Path | None = None, max_parcels_per_row: int = 400) -> dict:
    """Write the MATSim demand shapefile of one stored day and return its ledger."""
    run_dir = Path(run_dir)
    store = run_dir / "annual"
    timestamp = pd.Timestamp(date).normalize()
    days = pd.read_parquet(store / "days.parquet")
    match = days.loc[pd.to_datetime(days.date).dt.normalize().eq(timestamp)]
    if match.empty:
        raise ValueError(f"{timestamp.date()} is not in the annual store")
    table = pq.read_table(store / "stop_daily.parquet", filters=[("date", "==", timestamp.date())]).to_pandas()
    ledger = {"date": timestamp.date().isoformat(), "file": None, "features": 0, "parcels": int(match.parcels.iloc[0]),
              "b2b": int(match.b2b.iloc[0]), "b2c": int(match.b2c.iloc[0]), "unlocated_parcels": 0, "unlocated_sites": 0,
              "sites_active": int(match.sites_active.iloc[0])}
    if table.empty:
        return ledger
    stops = gpd.read_parquet(run_dir / "reference_stops.parquet")
    stop_id = stops.drop_duplicates("stop_id").set_index("stop_index").stop_id
    grouped = pd.DataFrame(index=pd.Index(stop_id.reindex(table.stop.to_numpy()).to_numpy(), name="stop_id"))
    for provider, short in CARRIER_FIELDS.values():
        grouped[f"{provider}_tag"] = table[f"{short}_b2c"].to_numpy(np.int64)
        grouped[_safe10(f"{provider}_type")] = table[f"{short}_b2b"].to_numpy(np.int64)
        grouped[f"{short}_b2b"] = table[f"{short}_b2b"].to_numpy(np.int64)
        grouped[f"{short}_b2c"] = table[f"{short}_b2c"].to_numpy(np.int64)
    result = stop_table_frame(grouped, {"stops": stops}, ledger, int(max_parcels_per_row))
    return write_demand_frame(result, Path(output_dir) if output_dir else run_dir / "matsim_on_demand", timestamp, ledger)
