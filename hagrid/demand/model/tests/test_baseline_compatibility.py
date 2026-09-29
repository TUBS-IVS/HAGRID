import json

import geopandas as gpd
import pandas as pd
from shapely.geometry import box
from shapely import wkt


def test_legacy_contract_preserves_cell_ids_geometry_and_maps_mass_once(tmp_path):
    from hagrid_demand.baseline.compatibility import export_legacy, make_legacy_contract
    from hagrid_demand.common.contracts import AnnualProjection

    grid = gpd.GeoDataFrame({"cell_id": ["old-b", "old-a"], "postal_cod": ["01000", "01000"],
                             "total_coun": [1., 3.]}, geometry=[box(1, 0, 2, 1), box(0, 0, 1, 1)], crs="EPSG:25832")
    grid_path = tmp_path / "grid.geojson"; grid.to_file(grid_path)
    samples = pd.DataFrame({"site_id": ["s1", "s2", "orphan"], "cell_id": ["old-a", "old-b", "missing"]})
    sample_path = tmp_path / "samples.csv"; samples.to_csv(sample_path, index=False)
    contract = make_legacy_contract(grid_path, sample_path, tmp_path / "contract.json")
    projection = AnnualProjection(
        pd.DataFrame({"year": [2021, 2021, 2021], "site_id": ["s1", "s2", "orphan"], "plz": ["01000"] * 3,
                      "segment": ["private", "private", "private"], "allocation_status": ["located"] * 3,
                      "annual_expected": [30., 10., 9.], "share": [.6, .2, .2]}),
        pd.DataFrame({"year": [2021], "segment": ["private"], "carrier": ["DHL"], "share": [1.]}),
        pd.DataFrame(), {"hashes": {}})
    result = export_legacy({}, {"regional_annual": 49.}, projection, contract, [2021], tmp_path / "legacy", 1)
    frame = gpd.read_file(tmp_path / "legacy" / "05_ga_corrected_b2b_with_marked_adjust_gdf.csv")

    assert frame.cell_id.tolist() == ["old-b", "old-a"]
    assert all(wkt.loads(value).equals(expected) for value, expected in zip(frame.geometry, grid.geometry, strict=True))
    assert pd.to_numeric(frame.total_coun).tolist() == [10., 30.]
    assert result["mapped_mass"] + result["residual_mass"] == 49.
    assert json.loads(contract.read_text())["source"]["grid_sha256"]


def test_daily_writer_consumes_chunks_once_aggregates_and_writes_selected_details(tmp_path):
    from hagrid_demand.baseline.outputs import write_daily_aggregates

    seen = []
    def chunks():
        seen.append("one")
        yield pd.DataFrame({"date": [pd.Timestamp("2024-01-01")] * 2, "year": [2024] * 2,
                            "outer_id": [1] * 2, "inner_id": [2] * 2, "site_id": ["a", "b"], "plz": ["01", "01"],
                            "segment": ["private"] * 2, "carrier": ["DHL"] * 2,
                            "allocation_status": ["located"] * 2, "baseline_expected": [1., 2.],
                            "conditional_expected": [1., 2.], "count": [1, 2]})

    result = write_daily_aggregates(chunks(), tmp_path, {(1, 2)})
    aggregate = pd.read_parquet(tmp_path / "daily_aggregates.parquet")
    detail = pd.read_parquet(tmp_path / "details" / "outer-1-inner-2.parquet")
    assert seen == ["one"]
    assert aggregate["count"].tolist() == [3]
    assert detail["count"].sum() == 3
    assert result["rows"] == 1
