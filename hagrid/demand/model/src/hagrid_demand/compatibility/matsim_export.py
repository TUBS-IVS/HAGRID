"""Daily demand shapefiles in the layout the MATSim pipeline reads (DemandProcessor).

The files replace ``ParcelDemandScenarioGenerator`` exports: one Point per demand site with
integer parcel counts per carrier, ``<provider>_tag`` = B2C and ``<provider>_type`` (DBF name
cut to 10 characters, e.g. ``amazon_typ``) = B2B.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterator

import geopandas as gpd
import numpy as np
import pandas as pd


# Baseline carrier label -> (Java provider key, notebook short key)
CARRIER_FIELDS = {
    "Amazon": ("amazon", "ama"), "DHL": ("dhl", "dhl"), "DPD": ("dpd", "dpd"), "FedEx/TNT": ("fedex", "fxt"),
    "GLS": ("gls", "gls"), "Hermes": ("hermes", "her"), "UPS": ("ups", "ups"),
}
_WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


def _safe10(name: str) -> str:
    return name[:10]


def matsim_file_name(date: pd.Timestamp) -> str:
    """``hagrid_parcel_demand_<ISO date>_(<English weekday>).shp`` independent of the OS locale."""
    date = pd.Timestamp(date)
    return f"hagrid_parcel_demand_{date.date().isoformat()}_({_WEEKDAYS[date.dayofweek]}).shp"


def _count_columns() -> list[str]:
    columns = []
    for provider, _ in CARRIER_FIELDS.values():
        columns.append(f"{provider}_tag")
    for provider, _ in CARRIER_FIELDS.values():
        columns.append(_safe10(f"{provider}_type"))
    for _, short in CARRIER_FIELDS.values():
        columns.extend([f"{short}_b2b", f"{short}_b2c"])
    return columns + ["total_sim", "total", "wl_tag"]


def _split_rows(table: pd.DataFrame, counts: list[str], limit: int) -> pd.DataFrame:
    """Split stops whose largest carrier/segment count exceeds *limit* into equal rows at the same point."""
    frame = table[counts].astype(np.int64)
    pieces = np.maximum(1, np.ceil(frame.max(axis=1).to_numpy() / limit)).astype(np.int64)
    result = frame[pieces == 1].assign(row_part=0, plz=table.plz[pieces == 1]).rename_axis("stop_id").reset_index()
    rows = []
    for stop_id, row in table[pieces > 1].iterrows():
        values = {column: int(row[column]) for column in counts}
        count = int(np.ceil(max(values.values()) / limit))
        for part in range(count):
            split = {column: value // count + (1 if part < value % count else 0) for column, value in values.items()}
            rows.append({"stop_id": stop_id, "row_part": part, "plz": row.plz, **split})
    if rows:
        result = pd.concat([result, pd.DataFrame(rows)], ignore_index=True)
    result = result.sort_values(["stop_id", "row_part"], kind="stable").reset_index(drop=True)
    tags = [f"{provider}_tag" for provider, _ in CARRIER_FIELDS.values()]
    types = [_safe10(f"{provider}_type") for provider, _ in CARRIER_FIELDS.values()]
    result["total"] = result[tags].sum(axis=1) + result[types].sum(axis=1)
    result["total_sim"] = result.total
    result["wl_tag"] = result.total
    return result


def stop_table_frame(grouped: pd.DataFrame, stops: dict, ledger: dict, limit: int) -> gpd.GeoDataFrame:
    """Turn per-stop count columns (index ``stop_id``) into split MATSim rows at the stop points."""
    counts = [column for column in _count_columns() if column not in {"total", "total_sim", "wl_tag"}]
    info = stops["stops"].drop_duplicates("stop_id").set_index("stop_id")
    grouped = grouped[counts].copy()
    grouped.index.name = "stop_id"
    grouped["plz"] = info.plz.reindex(grouped.index).astype(str).to_numpy()
    parts = _split_rows(grouped, counts, limit)
    frame = pd.DataFrame({
        "id": (info.stop_index.reindex(parts.stop_id).to_numpy() * 100 + parts.row_part.to_numpy()).astype(np.int64),
        "stop_id": parts.stop_id.to_numpy(), "str_idx": info.str_idx.reindex(parts.stop_id).to_numpy().astype(np.int64),
        "section_id": info.section_id.reindex(parts.stop_id).fillna("").astype(str).to_numpy(),
        "postal_cod": parts.plz.astype(str).to_numpy(), "date": ledger["date"]})
    for column in _count_columns():
        frame[column] = parts[column].to_numpy(dtype=np.int64)
    # Home stops, parcel lockers, shared boxes and pickup shops; the Java pipeline maps the type to its delivery mode.
    frame["stop_type"] = (info.stop_type.reindex(parts.stop_id).fillna("home").astype(str).to_numpy()
                          if "stop_type" in info else "home")
    per_stop = parts.groupby("stop_id").total.sum()
    ledger.update({"stops_active": int(len(per_stop)), "rows": int(len(parts)),
                   "parcels_per_stop": {"mean": float(per_stop.mean()), "median": float(per_stop.median()),
                                        "p90": float(per_stop.quantile(.9)), "max": int(per_stop.max())}})
    return gpd.GeoDataFrame(frame, geometry=info.geometry.reindex(parts.stop_id).to_numpy(), crs=stops["stops"].crs)


def _stop_frame(table: pd.DataFrame, stops: dict, ledger: dict, limit: int) -> gpd.GeoDataFrame:
    stop_of = stops["site_stops"].drop_duplicates("site_id").set_index("site_id").stop_id
    missing = sorted(set(table.index) - set(stop_of.index))
    if missing:
        raise ValueError(f"sites without stop mapping: {missing[:5]}")
    counts = [column for column in _count_columns() if column not in {"total", "total_sim", "wl_tag"}]
    result = stop_table_frame(table[counts].groupby(table.index.map(stop_of)).sum(), stops, ledger, limit)
    ledger["sites_active"] = int(len(table))
    return result


def write_demand_frame(result: gpd.GeoDataFrame, output_dir: Path, date: pd.Timestamp, ledger: dict) -> dict:
    """Validate and write one day's MATSim demand shapefile; update and return the ledger."""
    if not result.geometry.geom_type.eq("Point").all():
        raise ValueError("MATSim demand sites must be Point geometries")
    invalid = ~result.postal_cod.astype(str).str.fullmatch(r"\d{5}")
    if invalid.any():
        # The Java pipeline cuts carrier ids at the PLZ ("dhl_30159") and fails on anything else.
        raise ValueError(f"invalid postal codes for MATSim export: {sorted(set(result.loc[invalid, 'postal_cod'].astype(str)))[:5]}")
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    name = matsim_file_name(pd.Timestamp(date))
    result.to_file(output_dir / name, driver="ESRI Shapefile", encoding="UTF-8")
    ledger.update({"file": name, "features": int(len(result))})
    return ledger


def write_matsim_day(chunk: pd.DataFrame, geometry: gpd.GeoDataFrame, output_dir: Path, stops: dict | None = None,
                     max_parcels_per_row: int = 400) -> dict:
    """Write one day of site/carrier counts as a MATSim demand shapefile and return its ledger.

    With ``stops`` the sites are aggregated to their stable stop points and rows above
    ``max_parcels_per_row`` are split (the Java pipeline drops features with ``dhl_tag`` > 450).
    """
    required = {"date", "site_id", "plz", "segment", "carrier", "count"}
    if missing := required.difference(chunk.columns):
        raise ValueError(f"daily detail missing columns for MATSim export: {sorted(missing)}")
    dates = pd.to_datetime(chunk.date).dt.normalize().unique()
    if len(dates) != 1:
        raise ValueError("a MATSim demand file covers exactly one date")
    date = pd.Timestamp(dates[0])
    unknown = sorted(set(chunk.carrier.astype(str)) - set(CARRIER_FIELDS))
    if unknown:
        raise ValueError(f"carriers without MATSim field mapping: {unknown}")
    active = chunk.loc[chunk["count"].gt(0), ["site_id", "plz", "segment", "carrier", "count"]].copy()
    ledger = {"date": date.date().isoformat(), "file": None, "features": 0, "parcels": int(chunk["count"].sum()),
              "b2b": int(active.loc[active.segment.eq("business"), "count"].sum()),
              "b2c": int(active.loc[active.segment.eq("private"), "count"].sum()),
              "unlocated_parcels": 0, "unlocated_sites": 0}
    offered = chunk.groupby("segment").site_id.nunique()
    delivered = active.groupby("segment").site_id.nunique()
    ledger["active_share"] = {segment: float(delivered.get(segment, 0) / offered[segment]) for segment in offered.index}
    if active.empty:
        return ledger
    if not active.segment.isin(["private", "business"]).all():
        raise ValueError("MATSim export requires private/business segments")
    provider = active.carrier.map(lambda label: CARRIER_FIELDS[label][0])
    short = active.carrier.map(lambda label: CARRIER_FIELDS[label][1])
    private = active.segment.eq("private")
    active["java"] = np.where(private, provider + "_tag", (provider + "_type").map(_safe10))
    active["short"] = short + np.where(private, "_b2c", "_b2b")
    sites = active.groupby("site_id", sort=True).agg(plz=("plz", "first"))
    java = active.pivot_table(index="site_id", columns="java", values="count", aggfunc="sum", fill_value=0)
    notebook = active.pivot_table(index="site_id", columns="short", values="count", aggfunc="sum", fill_value=0)
    table = sites.join(java).join(notebook)
    for column in _count_columns():
        if column not in table:
            table[column] = 0
    tag_columns = [f"{provider}_tag" for provider, _ in CARRIER_FIELDS.values()]
    type_columns = [_safe10(f"{provider}_type") for provider, _ in CARRIER_FIELDS.values()]
    table["total"] = table[tag_columns].sum(axis=1) + table[type_columns].sum(axis=1)
    table["total_sim"] = table["total"]
    table["wl_tag"] = table["total"]
    if stops is not None:
        result = _stop_frame(table, stops, ledger, int(max_parcels_per_row))
    else:
        points = geometry.drop_duplicates("site_id").set_index("site_id").geometry
        located = table.index.isin(points.index) & points.reindex(table.index).notna().to_numpy()
        missing_geometry = table.loc[~located]
        ledger["unlocated_sites"] = int(len(missing_geometry))
        ledger["unlocated_parcels"] = int(missing_geometry["total"].sum())
        table = table.loc[located]
        frame = pd.DataFrame({"id": np.arange(len(table), dtype=np.int64), "site_id": table.index.astype(str),
                              "postal_cod": table.plz.astype(str).to_numpy(), "date": ledger["date"]})
        for column in _count_columns():
            frame[column] = table[column].to_numpy(dtype=np.int64)
        result = gpd.GeoDataFrame(frame, geometry=points.reindex(table.index).to_numpy(), crs=geometry.crs)
    return write_demand_frame(result, output_dir, date, ledger)


def with_matsim_export(chunks: Iterator[pd.DataFrame], geometry: gpd.GeoDataFrame, output_dir: Path,
                       ledgers: list[dict], stops: dict | None = None, max_parcels_per_row: int = 400) -> Iterator[pd.DataFrame]:
    """Pass daily chunks through unchanged while writing one MATSim file per date."""
    for chunk in chunks:
        for _, day in chunk.groupby(pd.to_datetime(chunk.date).dt.normalize(), sort=True):
            ledgers.append(write_matsim_day(day, geometry, output_dir, stops, max_parcels_per_row))
        yield chunk


def _day_totals(path: Path) -> tuple[pd.Series, float, float]:
    frame = gpd.read_file(path, columns=None)
    tags = [f"{provider}_tag" for provider, _ in CARRIER_FIELDS.values()]
    types = [_safe10(f"{provider}_type") for provider, _ in CARRIER_FIELDS.values()]
    b2c = frame[[column for column in tags if column in frame]].sum(axis=1)
    b2b = frame[[column for column in types if column in frame]].sum(axis=1)
    postal = (b2c + b2b).groupby(frame.postal_cod.astype(str)).sum()
    return postal, float(b2c.sum()), float(b2b.sum())


def compare_with_notebook(ledgers: list[dict], output_dir: Path, notebook_dir: Path) -> list[dict]:
    """Compare exported days with ParcelDemandScenarioGenerator files of the same name (totals, B2B, PLZ pattern)."""
    rows = []
    for ledger in ledgers:
        name = ledger.get("file")
        if not name or not (Path(output_dir) / name).is_file() or not (Path(notebook_dir) / name).is_file():
            continue
        new, new_b2c, new_b2b = _day_totals(Path(output_dir) / name)
        old, old_b2c, old_b2b = _day_totals(Path(notebook_dir) / name)
        joined = pd.concat([old, new], axis=1, keys=["old", "new"]).fillna(0.)
        parcels, notebook = new_b2c + new_b2b, old_b2c + old_b2b
        rows.append({"date": ledger["date"], "file": name, "parcels": int(parcels), "notebook_parcels": int(notebook),
                     "diff_pct": float(100. * (parcels / notebook - 1.)) if notebook else None,
                     "b2b_share": float(new_b2b / parcels) if parcels else None,
                     "notebook_b2b_share": float(old_b2b / notebook) if notebook else None,
                     "postal_correlation": float(joined.old.corr(joined.new)) if len(joined) > 1 else None})
    return rows


def write_matsim_manifest(ledgers: list[dict], output_dir: Path, crs: str | None, stop_mode: bool = False,
                          comparison: list[dict] | None = None) -> Path:
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / "matsim_export.json"
    fields = {"<provider>_tag": "B2C parcels", "<provider>_type (DBF 10 chars)": "B2B parcels",
              "<short>_b2c/<short>_b2b": "notebook aliases", "total = total_sim = wl_tag": "all parcels", "postal_cod": "PLZ"}
    if stop_mode:
        fields.update({"id": "stop_index * 100 + split row (Long)", "stop_id": "stable delivery stop",
                       "str_idx": "LSP street index (-1 off street)", "section_id": "50 m LSP street section",
                       "stop_type": "home | locker | shared_locker | counter | shop (out-of-home points; Java maps them to PARCEL_LOCKER_EXISTING)"})
    else:
        fields.update({"id": "running row index (Long)", "site_id": "baseline demand site"})
    payload = {"schema": "hagrid_parcel_demand shapefile (DemandProcessor)", "crs": crs, "fields": fields, "days": ledgers}
    if comparison is not None:
        payload["notebook_comparison"] = comparison
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path
