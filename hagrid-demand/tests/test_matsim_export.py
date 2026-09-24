import json
import struct

import geopandas as gpd
import pandas as pd
import pytest
from shapely.geometry import Point

from baseline_fixtures import write_fixture


def _dbf_fields(path):
    with open(path, "rb") as stream:
        header = stream.read(32)
        header_length = struct.unpack("<H", header[8:10])[0]
        fields = {}
        for _ in range((header_length - 33) // 32):
            raw = stream.read(32)
            fields[raw[:11].split(b"\x00")[0].decode()] = (raw[11:12].decode(), raw[16], raw[17])
    return fields


def test_matsim_day_matches_the_java_demand_contract(tmp_path):
    from hagrid_demand.compatibility.matsim_export import matsim_file_name, write_matsim_day

    chunk = pd.DataFrame({
        "date": pd.Timestamp("2025-05-13"), "site_id": ["res:a", "res:a", "biz:b", "biz:b", "res:c"],
        "plz": ["30159", "30159", "30161", "30161", "30161"],
        "segment": ["private", "private", "business", "business", "private"],
        "carrier": ["DHL", "Amazon", "UPS", "FedEx/TNT", "Hermes"], "count": [3, 2, 4, 1, 0],
    })
    geometry = gpd.GeoDataFrame({"site_id": ["res:a", "biz:b", "res:c"]},
                                geometry=[Point(1, 1), Point(2, 2), Point(3, 3)], crs="EPSG:25832")
    ledger = write_matsim_day(chunk, geometry, tmp_path)

    assert matsim_file_name(pd.Timestamp("2025-05-13")) == "hagrid_parcel_demand_2025-05-13_(Tuesday).shp"
    assert ledger == {"date": "2025-05-13", "file": "hagrid_parcel_demand_2025-05-13_(Tuesday).shp", "features": 2,
                      "parcels": 10, "b2b": 5, "b2c": 5, "unlocated_parcels": 0, "unlocated_sites": 0}
    frame = gpd.read_file(tmp_path / ledger["file"]).set_index("site_id")
    assert frame.crs.to_epsg() == 25832 and frame.geom_type.eq("Point").all()
    assert frame.loc["res:a", ["dhl_tag", "amazon_tag", "dhl_type"]].tolist() == [3, 2, 0]
    assert frame.loc["biz:b", ["ups_type", "fedex_type", "ups_tag"]].tolist() == [4, 1, 0]
    assert frame.loc["biz:b", ["ups_b2b", "fxt_b2b"]].tolist() == [4, 1]
    assert (frame.total == frame.wl_tag).all() and (frame.total == frame.total_sim).all()
    assert frame.loc["res:a", "total"] == 5 and frame.loc["biz:b", "total"] == 5
    assert frame.total.sum() == 10 and frame.loc["res:a", "postal_cod"] == "30159"
    fields = _dbf_fields((tmp_path / ledger["file"]).with_suffix(".dbf"))
    for name in ("dhl_tag", "amazon_tag", "amazon_typ", "hermes_typ", "dhl_type", "wl_tag", "total"):
        kind, width, decimals = fields[name]
        # GeoTools maps numeric DBF fields with 10..18 digits and no decimals to Long (Java casts to Long).
        assert kind == "N" and 10 <= width <= 18 and decimals == 0, (name, fields[name])
    assert fields["postal_cod"][0] == "C"


def test_matsim_day_rejects_unknown_carriers_and_ledgers_missing_geometry(tmp_path):
    from hagrid_demand.compatibility.matsim_export import write_matsim_day

    geometry = gpd.GeoDataFrame({"site_id": ["res:a"]}, geometry=[Point(1, 1)], crs="EPSG:25832")
    chunk = pd.DataFrame({"date": pd.Timestamp("2025-05-13"), "site_id": ["res:a", "res:x"], "plz": ["30159", "30159"],
                          "segment": ["private", "private"], "carrier": ["DHL", "DHL"], "count": [1, 2]})
    ledger = write_matsim_day(chunk, geometry, tmp_path)
    assert (ledger["features"], ledger["unlocated_sites"], ledger["unlocated_parcels"]) == (1, 1, 2)
    with pytest.raises(ValueError, match="carriers without MATSim"):
        write_matsim_day(chunk.assign(carrier="Post"), geometry, tmp_path)


def test_daily_run_writes_one_matsim_file_per_delivery_day(tmp_path):
    from hagrid_demand.baseline.workflow import run_baseline

    config_path = write_fixture(tmp_path)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config.update({"output_scope": "daily", "years": [2021], "dates": ["2021-05-11", "2021-05-16"]})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(config_path, "daily-matsim")

    manifest = json.loads((run / "matsim" / "matsim_export.json").read_text(encoding="utf-8"))
    days = {day["date"]: day for day in manifest["days"]}
    assert days["2021-05-16"]["file"] is None and days["2021-05-16"]["parcels"] == 0  # Sunday: no file
    tuesday = days["2021-05-11"]
    assert tuesday["file"] == "hagrid_parcel_demand_2021-05-11_(Tuesday).shp"
    frame = gpd.read_file(run / "matsim" / tuesday["file"])
    daily = pd.read_parquet(run / "daily_aggregates.parquet")
    expected = int(daily.loc[daily.date.astype(str).str.startswith("2021-05-11"), "count"].sum())
    assert int(frame.total.sum()) + tuesday["unlocated_parcels"] == expected == tuesday["parcels"]
    b2b_columns = [column for column in frame.columns if column.endswith(("_type", "_typ"))]
    assert int(frame[b2b_columns].to_numpy().sum()) == tuesday["b2b"]


def test_matsim_day_by_stop_splits_rows_above_the_limit_and_keeps_totals(tmp_path):
    from hagrid_demand.compatibility.matsim_export import write_matsim_day

    chunk = pd.DataFrame({"date": pd.Timestamp("2025-05-13"), "site_id": ["h1", "h2", "f1", "f1"], "plz": ["30159"] * 4,
                          "segment": ["private", "private", "business", "business"],
                          "carrier": ["DHL", "DHL", "UPS", "DHL"], "count": [3, 4, 900, 5]})
    geometry = gpd.GeoDataFrame({"site_id": ["h1", "h2", "f1"]}, geometry=[Point(1, 1), Point(2, 2), Point(9, 9)], crs="EPSG:25832")
    stops = {"site_stops": pd.DataFrame({"site_id": ["h1", "h2", "f1"], "stop_id": ["s1", "s1", "s2"]}),
             "stops": gpd.GeoDataFrame({"stop_id": ["s1", "s2"], "stop_index": [0, 1], "str_idx": [7, 8],
                                        "section_id": ["7-0-0", "8-0-1"], "plz": ["30159", "30159"]},
                                       geometry=[Point(1.5, 0), Point(9, 0)], crs="EPSG:25832")}
    ledger = write_matsim_day(chunk, geometry, tmp_path, stops=stops, max_parcels_per_row=400)

    frame = gpd.read_file(tmp_path / ledger["file"])
    assert frame.total.sum() == 912 and frame.groupby("stop_id").total.sum().to_dict() == {"s1": 7, "s2": 905}
    assert (frame[["ups_type", "dhl_type", "dhl_tag"]] <= 400).all().all()
    assert frame.id.is_unique and set(frame.loc[frame.stop_id.eq("s2"), "id"]) == {100, 101, 102}
    assert frame.loc[frame.stop_id.eq("s1"), "dhl_tag"].tolist() == [7]
    assert ledger["stops_active"] == 2 and ledger["rows"] == 4
    assert frame.loc[frame.stop_id.eq("s2"), "str_idx"].eq(8).all() and frame.crs.to_epsg() == 25832


def test_matsim_day_rejects_invalid_postal_codes_before_java_sees_them(tmp_path):
    from hagrid_demand.compatibility.matsim_export import write_matsim_day

    chunk = pd.DataFrame({"date": pd.Timestamp("2025-05-13"), "site_id": ["h1"], "plz": ["<NA>"], "segment": ["private"],
                          "carrier": ["DHL"], "count": [2]})
    geometry = gpd.GeoDataFrame({"site_id": ["h1"]}, geometry=[Point(1, 1)], crs="EPSG:25832")
    with pytest.raises(ValueError, match="postal"):
        write_matsim_day(chunk, geometry, tmp_path)
    stops = {"site_stops": pd.DataFrame({"site_id": ["h1"], "stop_id": ["s1"]}),
             "stops": gpd.GeoDataFrame({"stop_id": ["s1"], "stop_index": [0], "str_idx": [1], "section_id": ["1-0-0"], "plz": ["<NA>"]},
                                       geometry=[Point(1, 0)], crs="EPSG:25832")}
    with pytest.raises(ValueError, match="postal"):
        write_matsim_day(chunk.assign(plz="30159"), geometry, tmp_path, stops=stops)
