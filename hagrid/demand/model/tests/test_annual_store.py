import json

import geopandas as gpd
import pandas as pd
import pytest

from street_fixtures import write_street_fixture

DATES = ["2025-05-16", "2025-05-18"]


@pytest.fixture(scope="module")
def annual_run(tmp_path_factory):
    from hagrid_demand.baseline.workflow import run_baseline

    root = tmp_path_factory.mktemp("annual")
    config_path = write_street_fixture(root)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config.update({"output_scope": "daily", "years": [2021, 2025], "dates": DATES,
                   "anchor": {"mode": "street", "min_streets": 99}, "temporal": {"mode": "shipping_transit"},
                   "annual_store": True})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    return run_baseline(config_path, "street-annual")


def test_store_totals_match_days(annual_run):
    days = pd.read_parquet(annual_run / "annual" / "days.parquet")
    assert len(days) == 365 * 2 and days.parcels.sum() > 0
    stops = pd.read_parquet(annual_run / "annual" / "stop_daily.parquet")
    counts = [column for column in stops.columns if column not in {"date", "stop"}]
    assert len(counts) == 14
    per_day = stops.assign(total=stops[counts].sum(axis=1)).groupby("date").total.sum()
    parcels = days.set_index(pd.to_datetime(days.date).dt.date).parcels
    assert (per_day.reindex(parcels.index, fill_value=0).to_numpy() == parcels.to_numpy()).all()
    plz = pd.read_parquet(annual_run / "annual" / "plz_daily.parquet")
    by_plz = plz.groupby(pd.to_datetime(plz.date).dt.date).parcels.sum().reindex(parcels.index, fill_value=0)
    assert (by_plz.to_numpy(dtype="int64") == parcels.to_numpy(dtype="int64")).all()
    summary = json.loads((annual_run / "annual" / "annual_summary.json").read_text(encoding="utf-8"))
    assert {"weekday_profile", "weekly", "monthly", "files"}.issubset(summary)


def test_export_day_equals_direct_export(annual_run, tmp_path):
    from hagrid_demand.baseline.annual import export_day

    ledger = export_day(annual_run, DATES[0], tmp_path)
    manifest = json.loads((annual_run / "matsim" / "matsim_export.json").read_text(encoding="utf-8"))
    direct_day = next(day for day in manifest["days"] if day["date"] == DATES[0])
    assert ledger["file"] == direct_day["file"] and ledger["parcels"] == direct_day["parcels"]
    ours = gpd.read_file(tmp_path / ledger["file"]).sort_values("id").reset_index(drop=True)
    direct = gpd.read_file(annual_run / "matsim" / direct_day["file"]).sort_values("id").reset_index(drop=True)
    columns = [column for column in direct.columns if column not in {"geometry"}]
    assert list(ours.columns) == list(direct.columns)
    assert ours[columns].equals(direct[columns])


def test_export_day_empty_sunday(annual_run, tmp_path):
    from hagrid_demand.baseline.annual import export_day

    manifest = json.loads((annual_run / "matsim" / "matsim_export.json").read_text(encoding="utf-8"))
    direct_day = next(day for day in manifest["days"] if day["date"] == DATES[1])
    ledger = export_day(annual_run, DATES[1], tmp_path)
    assert direct_day["file"] is None and ledger["file"] is None and ledger["parcels"] == 0


def test_writer_abort_closes_file(tmp_path):
    import numpy as np
    from hagrid_demand.baseline.allocation import SegmentDay
    from hagrid_demand.baseline.annual import AnnualStoreWriter

    stops = pd.DataFrame({"stop_id": ["a"], "stop_index": [0], "plz": ["30159"]})
    writer = AnnualStoreWriter(tmp_path, stops, pd.DataFrame({"site_id": ["s1"], "stop_id": ["a"]}))
    bad = SegmentDay(pd.DataFrame({"site_id": ["s1"], "plz": ["30159"]}), ["Unknown"], np.array([[1]]), np.array([1.]), np.array([1]))
    with pytest.raises(ValueError):
        writer.add_day(pd.Timestamp("2025-05-16"), {"private": bad})
    writer.abort()
    (tmp_path / "annual" / "stop_daily.parquet").unlink()


def test_daily_stage_code_includes_temporal_inputs():
    from hagrid_demand.baseline.workflow import _daily_code

    code = _daily_code()
    assert code["temporal_inputs"].name == "temporal_inputs.json" and code["temporal_inputs"].is_file()
    assert {"shipping", "shipping_draws", "annual", "allocation", "out_of_home"} <= set(code)
    assert all(code[name].is_file() for name in ("events", "out_of_home_inputs"))


def test_draw_dates_skip_unselected_without_writer():
    from hagrid_demand.baseline.workflow import _draw_indices

    dates = pd.date_range("2025-01-01", periods=10)
    assert _draw_indices(dates, {pd.Timestamp("2025-01-03"), pd.Timestamp("2025-01-07")}, has_writer=False).tolist() == [2, 6]
    assert len(_draw_indices(dates, {pd.Timestamp("2025-01-03")}, has_writer=True)) == 10
    assert len(_draw_indices(dates, None, has_writer=False)) == 10


def test_daily_stage_code_includes_network_growth():
    from hagrid_demand.baseline.workflow import _daily_code

    code = _daily_code()
    assert code["network_growth"].name == "network_growth.py" and code["network_growth"].is_file()
    assert code["series"].name == "series.py" and code["series"].is_file()


def _point_store(root, extra_rows=()):
    """Minimal annual store: two home stops, a Packstation of the reference network and one opened in 2026."""
    import datetime

    import numpy as np
    from shapely.geometry import Point

    from hagrid_demand.baseline.annual import store_columns

    run = root / "point-store"
    (run / "annual").mkdir(parents=True)
    gpd.GeoDataFrame({"stop_id": ["s0", "s1"], "stop_index": [0, 1], "str_idx": [0, 0], "section_id": ["a", "a"],
                      "plz": ["30159", "30159"]}, geometry=[Point(0, 0), Point(10, 0)], crs="EPSG:25832") \
        .to_parquet(run / "reference_stops.parquet", index=False)
    gpd.GeoDataFrame({"stop_index": [2, 3], "point_id": ["osm:n1", "syn:locker:DHL:2026:0"], "kind": ["locker", "locker"],
                      "carriers": ["DHL", "DHL"], "plz": ["30159", "30159"], "year_opened": [2025, 2026],
                      "poi_type": [None, "shop=supermarket"]},
                     geometry=[Point(20, 0), Point(30, 0)], crs="EPSG:25832").to_parquet(run / "annual" / "out_of_home_points.parquet", index=False)
    first, second = datetime.date(2025, 5, 16), datetime.date(2026, 5, 15)
    rows = [(first, 0, 5), (first, 2, 3), (second, 0, 4), (second, 2, 2), (second, 3, 6), *extra_rows]
    table = pd.DataFrame({"date": [row[0] for row in rows], "stop": np.array([row[1] for row in rows], dtype=np.int32)})
    for column in store_columns():
        table[column] = np.zeros(len(rows), dtype=np.uint16)
    table["dhl_b2c"] = np.array([row[2] for row in rows], dtype=np.uint16)
    table.to_parquet(run / "annual" / "stop_daily.parquet", index=False)
    totals = table.groupby("date").dhl_b2c.sum()
    pd.DataFrame({"date": pd.to_datetime([first, second]), "parcels": [int(totals[first]), int(totals[second])],
                  "b2b": [0, 0], "b2c": [int(totals[first]), int(totals[second])], "sites_active": [2, 3]}) \
        .to_parquet(run / "annual" / "days.parquet", index=False)
    return run


def test_export_day_excludes_points_opened_later(tmp_path):
    import datetime

    from hagrid_demand.baseline.annual import export_day

    run = _point_store(tmp_path)
    early = export_day(run, "2025-05-16", tmp_path / "early")
    exported = gpd.read_file(tmp_path / "early" / early["file"])
    assert set(exported.stop_id) == {"s0", "ooh:osm:n1"} and int(exported.total.sum()) == 8
    late = export_day(run, "2026-05-15", tmp_path / "late")
    exported = gpd.read_file(tmp_path / "late" / late["file"])
    assert set(exported.stop_id) == {"s0", "ooh:osm:n1", "ooh:syn:locker:DHL:2026:0"}
    assert set(exported.loc[exported.stop_id.str.startswith("ooh:"), "stop_type"]) == {"locker"}
    # parcels at a station that only opens next year cannot be exported as if it were there
    broken = _point_store(tmp_path / "broken", extra_rows=[(datetime.date(2025, 5, 16), 3, 1)])
    with pytest.raises(ValueError, match="not open"):
        export_day(broken, "2025-05-16", tmp_path / "broken-export")


def test_store_streams_plz_rows_and_point_rows(annual_run):
    import numpy as np
    import pyarrow.parquet as pq

    store = annual_run / "annual"
    plz = pd.read_parquet(store / "plz_daily.parquet")
    days = pd.read_parquet(store / "days.parquet")
    assert plz.parcels.dtype == np.int32 and str(plz.date.dtype).startswith("datetime64")
    assert int(plz.parcels.sum()) == int(days.parcels.sum())
    assert pq.ParquetFile(store / "plz_daily.parquet").metadata.num_row_groups >= 2  # one row group per day, not one table
    points = pd.read_parquet(store / "point_daily.parquet")
    assert len(points) == 0 or points.stop.min() > pd.read_parquet(annual_run / "reference_stops.parquet").stop_index.max()
