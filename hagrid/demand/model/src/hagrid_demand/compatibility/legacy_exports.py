"""Legacy CSV exports built from the new, auditable baseline contract."""

from __future__ import annotations

import json
from pathlib import Path

import geopandas as gpd
import pandas as pd

from hagrid_demand.common.provenance import resource_hash


_CARRIERS = ["DHL", "Hermes", "UPS", "DPD", "GLS", "FedEx/TNT", "Amazon"]


def _write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def make_legacy_contract(grid_input: Path, sample_reference: Path, out: Path) -> Path:
    """Freeze old identifiers/geometries and explicit sample-to-cell mappings."""
    grid_input, sample_reference, out = Path(grid_input), Path(sample_reference), Path(out)
    grid = gpd.read_file(grid_input)
    if not {"cell_id", "postal_cod"}.issubset(grid.columns) or not grid.cell_id.is_unique:
        raise ValueError("legacy grid requires unique cell_id and postal_cod")
    samples = pd.read_csv(sample_reference)
    if not {"site_id", "cell_id"}.issubset(samples.columns) or samples.site_id.duplicated().any():
        raise ValueError("legacy samples require unique site_id and cell_id")
    contract = {
        "schema_version": 1,
        "source": {"grid": str(grid_input), "grid_sha256": resource_hash(grid_input),
                   "samples": str(sample_reference), "samples_sha256": resource_hash(sample_reference)},
        "field_semantics": {"total_coun": "newly derived cell total; not an inherited legacy demand value",
                            "market_shares_2021": "carrier market-share dictionary", "cell_id": "preserved source identifier"},
        "grid": [{"cell_id": str(row.cell_id), "postal_cod": str(row.postal_cod), "geometry": row.geometry.wkt,
                  "source_order": int(index)} for index, row in grid.reset_index(drop=True).iterrows()],
        "sample_to_cell": {str(row.site_id): str(row.cell_id) for row in samples.itertuples(index=False)},
    }
    _write_json(out, contract)
    return out


def export_legacy(series: dict, reference: dict, projection, contract: Path, years: list[int], output: Path,
                  schema_version: int) -> dict:
    """Export consumer-readable legacy files without treating legacy values as inputs."""
    if schema_version != 1:
        raise ValueError("unsupported legacy export schema version")
    payload = json.loads(Path(contract).read_text(encoding="utf-8"))
    grid_rows = payload.get("grid")
    if not isinstance(grid_rows, list) or not isinstance(payload.get("sample_to_cell"), dict):
        raise ValueError("invalid legacy contract")
    grid = gpd.GeoDataFrame(grid_rows, geometry=gpd.GeoSeries.from_wkt([row["geometry"] for row in grid_rows]), crs="EPSG:25832")
    annual = projection.sites.copy()
    annual = annual.loc[annual.year.isin(years)].copy()
    mapping = payload["sample_to_cell"]
    annual["cell_id"] = annual.site_id.astype(str).map(mapping)
    mapped = annual.loc[annual.cell_id.isin(grid.cell_id.astype(str))]
    residual = annual.loc[~annual.index.isin(mapped.index)]
    totals = mapped.groupby("cell_id").annual_expected.sum()
    profile = projection.profiles.loc[projection.profiles.year.eq(min(years))]
    market = profile.groupby("carrier").apply(lambda rows: float((rows["market_share"] if "market_share" in rows else rows["share"]).mean()), include_groups=False).to_dict()
    market = {carrier: float(market.get(carrier, 0.)) for carrier in _CARRIERS}
    denominator = sum(market.values())
    market = {carrier: value / denominator for carrier, value in market.items()} if denominator else {carrier: 0. for carrier in _CARRIERS}
    grid["total_coun"] = grid.cell_id.astype(str).map(totals).fillna(0.).astype(float)
    grid["market_shares_2021"] = json.dumps(market, sort_keys=True)
    grid["b2b_share"] = 0. if annual.annual_expected.sum() == 0 else float(annual.loc[annual.segment.eq("business"), "annual_expected"].sum() / annual.annual_expected.sum())
    output = Path(output); output.mkdir(parents=True, exist_ok=True)
    # CSV keeps the historical filename and uses WKT geometry that GDAL can read directly.
    grid.drop(columns="geometry").assign(geometry=grid.geometry.to_wkt()).to_csv(output / "05_ga_corrected_b2b_with_marked_adjust_gdf.csv", index=False)
    years_frame = pd.DataFrame({"Year": years})
    for carrier in _CARRIERS:
        years_frame[carrier] = 100 * market[carrier]
    years_frame.to_csv(output / "00_markedshare_with_amazon.csv", index=False)
    pd.DataFrame({"Year": years, "Actual_B2B": [100 * grid.b2b_share.iloc[0]] * len(years)}).to_csv(output / "01_b2b_forecast_complete.csv", index=False)
    pd.DataFrame({"year": years, "volume": [float(annual.loc[annual.year.eq(year), "annual_expected"].sum()) for year in years]}).to_csv(output / "02_parcel_volumen_estimation_complete.csv", index=False)
    q = pd.DataFrame({"year": years})
    for carrier in _CARRIERS:
        q[carrier] = [float(profile.loc[profile.carrier.eq(carrier), "share"].mean()) if not profile.loc[profile.carrier.eq(carrier)].empty else 0.5] * len(years)
    q.to_csv(output / "05_optimized_b2b_shares_by_year.csv", index=False)
    return {"files": sorted(path.name for path in output.iterdir()), "source_hashes": payload["source"],
            "mapped_mass": float(mapped.annual_expected.sum()), "residual_mass": float(residual.annual_expected.sum()),
            "field_semantics": payload["field_semantics"]}
