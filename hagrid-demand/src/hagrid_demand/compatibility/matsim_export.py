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


def write_matsim_day(chunk: pd.DataFrame, geometry: gpd.GeoDataFrame, output_dir: Path) -> dict:
    """Write one day of site/carrier counts as a MATSim demand shapefile and return its ledger."""
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
    points = geometry.drop_duplicates("site_id").set_index("site_id").geometry
    located = table.index.isin(points.index) & points.reindex(table.index).notna().to_numpy()
    missing_geometry = table.loc[~located]
    ledger["unlocated_sites"] = int(len(missing_geometry))
    ledger["unlocated_parcels"] = int(missing_geometry["total"].sum())
    table = table.loc[located]
    frame = pd.DataFrame({"site_id": table.index.astype(str), "postal_cod": table.plz.astype(str).to_numpy(),
                          "date": ledger["date"]})
    for column in _count_columns():
        frame[column] = table[column].to_numpy(dtype=np.int64)
    result = gpd.GeoDataFrame(frame, geometry=points.reindex(table.index).to_numpy(), crs=geometry.crs)
    if not result.geometry.geom_type.eq("Point").all():
        raise ValueError("MATSim demand sites must be Point geometries")
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    name = matsim_file_name(date)
    result.to_file(output_dir / name, driver="ESRI Shapefile", encoding="UTF-8")
    ledger.update({"file": name, "features": int(len(result))})
    return ledger


def with_matsim_export(chunks: Iterator[pd.DataFrame], geometry: gpd.GeoDataFrame, output_dir: Path,
                       ledgers: list[dict]) -> Iterator[pd.DataFrame]:
    """Pass daily chunks through unchanged while writing one MATSim file per date."""
    for chunk in chunks:
        for _, day in chunk.groupby(pd.to_datetime(chunk.date).dt.normalize(), sort=True):
            ledgers.append(write_matsim_day(day, geometry, output_dir))
        yield chunk


def write_matsim_manifest(ledgers: list[dict], output_dir: Path, crs: str | None) -> Path:
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / "matsim_export.json"
    payload = {"schema": "hagrid_parcel_demand shapefile (DemandProcessor)", "crs": crs,
               "fields": {"<provider>_tag": "B2C parcels", "<provider>_type (DBF 10 chars)": "B2B parcels",
                          "<short>_b2c/<short>_b2b": "notebook aliases", "total = total_sim = wl_tag": "all parcels",
                          "postal_cod": "PLZ", "site_id": "baseline demand site"},
               "days": ledgers}
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path
