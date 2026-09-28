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
