import json
import re
import shutil
import subprocess

import numpy as np
import pandas as pd
import pytest

from decade_fixtures import CODES, write_decade_run

BOOM = {"name": "boom", "policy": "legacy_assumptions", "curve": "exponential", "chain_year": 2025}
KEYS = {"meta", "years", "national", "annual", "plz", "calendar", "network", "weekday", "structure", "change"}
PAYLOAD = re.compile(r'<script id="hagrid-decade" type="application/json">(.*?)</script>', re.S)
SECTIONS = ["hero", "growth", "mix", "channels", "map", "hotspots", "structure", "change", "network", "calendar", "method"]


@pytest.fixture(scope="module")
def runs(tmp_path_factory):
    root = tmp_path_factory.mktemp("decade")
    return {"trend": write_decade_run(root, "decade-trend"),
            "boom": write_decade_run(root, "decade-boom", growth=1.08, scenario=BOOM, seed=11)}


@pytest.fixture(scope="module")
def payload(runs):
    from hagrid_demand.baseline.decade_dashboard import build_decade_dashboard_data

    return build_decade_dashboard_data(runs)


def _year(frame: pd.DataFrame, year: int) -> pd.DataFrame:
    return frame.loc[pd.to_datetime(frame.date).dt.year.eq(year)]


def _stored_by_point(run, year: int) -> pd.DataFrame:
    occupancy = _year(pd.read_parquet(run / "annual" / "locker_occupancy.parquet"), year)
    points = pd.read_parquet(run / "annual" / "out_of_home_points.parquet", columns=["stop_index", "kind", "plz"])
    return occupancy.merge(points, on="stop_index", how="left")


def test_decade_payload_shape(payload, runs):
    assert set(payload) == KEYS and payload["years"] == [2025, 2026]
    assert [scenario["name"] for scenario in payload["meta"]["scenarios"]] == ["trend", "boom"]
    days = _year(pd.read_parquet(runs["trend"] / "annual" / "days.parquet"), 2026)
    year = payload["annual"]["trend"]["2026"]
    assert year["parcels"] == int(days.parcels.sum()) and year["b2b"] == int(days.b2b.sum())
    assert year["delivery_days"] == 303 and year["per_delivery_day"] == round(days.parcels.sum() / 303)
    assert year["peak"]["date"] == days.date.iloc[int(np.argmax(days.parcels.to_numpy()))].strftime("%Y-%m-%d")
    assert year["peak"]["reason"]
    calendar = payload["calendar"]["trend"]["2026"]
    assert len(calendar["parcels"]) == 365 and sum(calendar["parcels"]) == year["parcels"] and len(calendar["holidays"]) == 10
    plz = payload["plz"]
    assert plz["codes"] == CODES and len(plz["geo"]["features"]) == len(CODES) and plz["persons"][0] == 12_000
    assert all(len(values) == len(CODES) for values in plz["values"]["boom"]["2026"].values())
    network = payload["network"]["trend"]
    assert len(network["ids"]) == 8 and all(len(row) == 2 for row in network["compartments"] + network["fill"])
    assert all(len(payload["weekday"]["trend"]["2025"][part]) == 7 for part in ("all", "b2c", "b2b"))
    observed = payload["national"]["observed"]
    assert observed["years"][0] == 2000 and observed["values"][0] == pytest.approx(1.701) and max(observed["years"]) == 2023
    assert payload["national"]["scenarios"]["trend"]["values"][0] == pytest.approx(4.51)


def test_decade_payload_channels_add_up(payload, runs):
    year = payload["annual"]["trend"]["2026"]
    stored = _stored_by_point(runs["trend"], 2026).groupby("kind").stored.sum()
    channels = year["channels"]
    assert {kind: channels[kind] for kind in ("locker", "shared_locker", "counter")} == {
        kind: int(stored[kind]) for kind in ("locker", "shared_locker", "counter")}
    assert sum(channels.values()) == year["parcels"] and channels["home"] == year["parcels"] - int(stored.sum())


def test_decade_payload_network_follows_opening_years(payload):
    first, second = payload["annual"]["trend"]["2025"]["network"], payload["annual"]["trend"]["2026"]["network"]
    assert first["points"] == {"locker": 4, "shared_locker": 1, "counter": 1} and first["compartments"] == 394
    assert second["points"] == {"locker": 5, "shared_locker": 1, "counter": 2} and second["compartments"] == 514
    assert first["new_sites"] == {} and second["new_sites"] == {"shop=supermarket": 1, "shop=kiosk": 1}
    network = payload["network"]["trend"]
    new = network["ids"].index("syn:locker:DHL:2026:0")
    assert network["year_opened"][new] == 2026 and network["poi_type"][new] == "shop=supermarket"
    assert network["compartments"][new] == [None, 76] and network["fill"][new][0] is None and network["fill"][new][1] >= 0
    assert network["compartments"][network["ids"].index("osm:n1")] == [76, 90]


def test_decade_payload_utilisation_counts_full_point_days(payload, runs):
    frame = _stored_by_point(runs["trend"], 2025)
    days = _year(pd.read_parquet(runs["trend"] / "annual" / "days.parquet"), 2025)
    delivery = set(days.loc[days.weekday.lt(6) & ~days.holiday.astype(bool), "date"])
    frame = frame.loc[frame.date.isin(delivery)]
    utilisation = payload["annual"]["trend"]["2025"]["utilisation"]
    assert utilisation["rejected"] == int(frame.rejected.sum()) > 0
    assert utilisation["full_share"] == pytest.approx(float(frame.occupied.ge(frame.compartments).mean()), abs=1e-4)
    assert utilisation["fill"] == pytest.approx(float(frame.occupied.sum() / frame.compartments.sum()), abs=1e-4)


def test_decade_payload_plz_values_split_home_and_ooh(payload, runs):
    values = payload["plz"]["values"]["trend"]["2026"]
    plz = _year(pd.read_parquet(runs["trend"] / "annual" / "plz_daily.parquet"), 2026)
    sums = plz.groupby(["segment", "plz"]).parcels.sum()
    private, business = sums["private"].reindex(CODES, fill_value=0), sums["business"].reindex(CODES, fill_value=0)
    ooh = _stored_by_point(runs["trend"], 2026).groupby("plz").stored.sum().reindex(CODES, fill_value=0)
    assert values["ooh"] == ooh.astype(int).tolist() and values["home_b2b"] == business.astype(int).tolist()
    assert (np.asarray(values["home_b2c"]) + np.asarray(values["ooh"])).tolist() == private.astype(int).tolist()


def test_decade_payload_ooh_share_per_carrier(payload, runs):
    year = payload["annual"]["trend"]["2026"]
    plz = _year(pd.read_parquet(runs["trend"] / "annual" / "plz_daily.parquet"), 2026)
    b2c = plz.loc[plz.segment.eq("private")].groupby("carrier").parcels.sum()
    stored = _stored_by_point(runs["trend"], 2026)
    points = pd.read_parquet(runs["trend"] / "annual" / "out_of_home_points.parquet", columns=["stop_index", "carriers"])
    by_point = stored.merge(points, on="stop_index")
    dhl = by_point.query("carriers == 'DHL'").stored.sum()  # single-carrier points: exact
    assert year["ooh_share"]["DHL"] == pytest.approx(dhl / b2c["DHL"], abs=1e-4)
    assert year["ooh_share"]["UPS"] is None  # UPS has no B2C parcels
    # the shared box splits by target out-of-home volume: Hermes and DPD have the same target share, UPS gets nothing
    shared = by_point.query("carriers == 'Hermes|DPD|UPS'").stored.sum()
    hermes, dpd = year["ooh_share"]["Hermes"], year["ooh_share"]["DPD"]
    assert year["ooh_target"]["Hermes"] == year["ooh_target"]["DPD"] and hermes == pytest.approx(dpd, abs=1e-4)
    assert hermes * b2c["Hermes"] + dpd * b2c["DPD"] == pytest.approx(shared, abs=2)


def test_decade_payload_json_has_no_nan(payload):
    json.dumps(payload, allow_nan=False)
    assert payload["annual"]["trend"]["2025"]["ooh_share"]["UPS"] is None


def test_decade_payload_marks_missing_years(tmp_path):
    from hagrid_demand.baseline.decade_dashboard import build_decade_dashboard_data

    runs = {"trend": write_decade_run(tmp_path, "decade-trend"),
            "boom": write_decade_run(tmp_path, "decade-boom", years=(2025,), scenario=BOOM),
            "saettigung": tmp_path / "not-run"}
    data = build_decade_dashboard_data(runs)
    assert [scenario["missing_years"] for scenario in data["meta"]["scenarios"]] == [[], [2026], [2025, 2026]]
    assert data["annual"]["boom"]["2025"] is not None and data["annual"]["boom"]["2026"] is None
    assert data["calendar"]["boom"]["2026"] is None and data["plz"]["values"]["boom"]["2026"] is None
    assert data["weekday"]["boom"]["2026"] is None and data["annual"]["saettigung"] == {"2025": None, "2026": None}
    json.dumps(data, allow_nan=False)


def test_decade_payload_requires_complete_primary(tmp_path):
    from hagrid_demand.baseline.decade_dashboard import build_decade_dashboard_data

    primary = write_decade_run(tmp_path, "decade-trend", years=(2025,), config_years=(2025, 2026))
    with pytest.raises(ValueError, match="2026"):
        build_decade_dashboard_data({"trend": primary})
    with pytest.raises(FileNotFoundError):
        build_decade_dashboard_data({"trend": tmp_path / "missing"})


def test_decade_payload_without_optional_inputs(tmp_path):
    from hagrid_demand.baseline.decade_dashboard import build_decade_dashboard_data

    data = build_decade_dashboard_data({"bare": write_decade_run(tmp_path, "bare", out_of_home=False, side_files=False)})
    year = data["annual"]["bare"]["2025"]
    assert data["network"]["bare"] is None and year["channels"] == {"home": year["parcels"]} and year["utilisation"] is None
    assert data["national"]["scenarios"]["bare"] is None and data["plz"]["geo"] is None and data["plz"]["codes"] == CODES
    json.dumps(data, allow_nan=False)


def test_decade_payload_falls_back_to_the_point_register(tmp_path):
    from hagrid_demand.baseline.decade_dashboard import build_decade_dashboard_data

    data = build_decade_dashboard_data({"legacy": write_decade_run(tmp_path, "legacy", network_file=False, point_years=False)})
    points = {"locker": 4, "shared_locker": 1, "counter": 1}
    assert [data["annual"]["legacy"][year]["network"]["points"] for year in ("2025", "2026")] == [points, points]
    network = data["network"]["legacy"]
    assert set(network["year_opened"]) == {2025} and network["compartments"][network["ids"].index("osm:n1")] == [76, 90]


def test_decade_payload_opens_points_from_the_register_without_network_file(tmp_path):
    from hagrid_demand.baseline.decade_dashboard import build_decade_dashboard_data

    data = build_decade_dashboard_data({"trend": write_decade_run(tmp_path, "decade-trend", network_file=False)})
    assert [data["annual"]["trend"][year]["network"]["points"] for year in ("2025", "2026")] == [
        {"locker": 4, "shared_locker": 1, "counter": 1}, {"locker": 5, "shared_locker": 1, "counter": 2}]


def test_decade_payload_takes_compartments_from_the_network_register(tmp_path):
    from hagrid_demand.baseline.decade_dashboard import build_decade_dashboard_data

    run = write_decade_run(tmp_path, "decade-trend")
    (run / "annual" / "locker_occupancy.parquet").unlink()
    data = build_decade_dashboard_data({"trend": run})
    network, year = data["network"]["trend"], data["annual"]["trend"]["2026"]
    assert network["compartments"][network["ids"].index("osm:n1")] == [76, 90] and year["network"]["compartments"] == 514
    days = _year(pd.read_parquet(run / "annual" / "days.parquet"), 2026)
    assert year["utilisation"] is None and year["channels"] == {"home": int(days.parcels.sum() - days.out_of_home.sum()),
                                                                "ooh": int(days.out_of_home.sum())}


def test_peak_reason_follows_the_annual_dashboard_rules():
    from hagrid_demand.baseline.decade_dashboard import peak_reason

    holidays = ["2025-04-18", "2025-04-21", "2025-06-09"]
    assert "pre-Christmas" in peak_reason(pd.Timestamp("2025-12-15"), holidays, {"christmas_pull_forward_days": 5})
    assert "public holiday on Monday, 21 Apr 2025" in peak_reason(pd.Timestamp("2025-04-22"), holidays, {"holiday_spread_days": 3})
    assert "next 3 shipping days" in peak_reason(pd.Timestamp("2025-04-22"), holidays, {"holiday_spread_days": 3})
    assert "season of week 25" in peak_reason(pd.Timestamp("2025-06-17"), holidays, {})


def test_write_decade_dashboard_embeds_payload(runs, tmp_path):
    from hagrid_demand.baseline.decade_dashboard import write_decade_dashboard

    text = write_decade_dashboard(runs, tmp_path / "decade.html").read_text(encoding="utf-8")
    assert text.startswith("<!doctype html>") and "<title>Hannover Parcel Decade</title>" in text[:4096]
    assert "__DECADE_DATA__" not in text and "const D =" in text
    embedded = PAYLOAD.search(text).group(1)
    assert "</" not in embedded and set(json.loads(embedded)) == KEYS
    artifact = write_decade_dashboard(runs, tmp_path / "artifact.html", standalone=False).read_text(encoding="utf-8")
    assert artifact.startswith("<title>") and "<!doctype" not in artifact[:200]


def test_cli_decade_dashboard(runs, tmp_path, capsys):
    from hagrid_demand.cli import main

    out = tmp_path / "cli" / "decade.html"
    assert main(["baseline", "decade-dashboard", "--run", f"trend={runs['trend']}", "--run", f"boom={runs['boom']}",
                 "--out", str(out)]) == 0
    assert f"Decade dashboard: {out}" in capsys.readouterr().out
    data = json.loads(PAYLOAD.search(out.read_text(encoding="utf-8")).group(1))
    assert [scenario["name"] for scenario in data["meta"]["scenarios"]] == ["trend", "boom"]


@pytest.mark.parametrize("spec", ["trend", "=path", "tre nd=path"])
def test_cli_decade_dashboard_rejects_malformed_runs(spec, tmp_path):
    from hagrid_demand.cli import main

    with pytest.raises(SystemExit) as exit_info:
        main(["baseline", "decade-dashboard", "--run", spec, "--out", str(tmp_path / "x.html")])
    assert exit_info.value.code == 2


def test_template_has_no_external_scripts(runs, tmp_path):
    from hagrid_demand.baseline.decade_dashboard import TEMPLATE, write_decade_dashboard

    for text in (TEMPLATE.read_text(encoding="utf-8"), write_decade_dashboard(runs, tmp_path / "d.html").read_text(encoding="utf-8")):
        assert not re.search(r"<script[^>]*\ssrc\s*=", text, re.I) and not re.search(r"<link\b", text, re.I)
        assert not re.search(r"@import|url\(\s*['\"]?https?:", text, re.I)


def test_template_sections_present(runs, tmp_path):
    from hagrid_demand.baseline.decade_dashboard import write_decade_dashboard

    text = write_decade_dashboard(runs, tmp_path / "sections.html").read_text(encoding="utf-8")
    assert re.findall(r'<section\b[^>]*\bid="([a-z]+)"', text) == SECTIONS


@pytest.mark.skipif(shutil.which("node") is None, reason="needs Node.js for the syntax check")
def test_template_script_is_valid_javascript(tmp_path):
    from hagrid_demand.baseline.decade_dashboard import TEMPLATE

    scripts = re.findall(r"<script>(.*?)</script>", TEMPLATE.read_text(encoding="utf-8"), re.S)
    assert scripts
    source = tmp_path / "page.js"
    source.write_text("\n".join(scripts), encoding="utf-8")
    result = subprocess.run(["node", "--check", str(source)], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr


def test_point_daily_by_carrier_sums_the_year():
    import datetime as dt

    import pandas as pd

    from hagrid_demand.baseline.decade_dashboard import _point_daily_by_carrier

    frame = pd.DataFrame({"date": [dt.date(2025, 3, 1), dt.date(2025, 3, 2), dt.date(2026, 3, 1)], "stop": [7, 8, 7],
                          "dhl_b2c": [3, 4, 9], "dhl_b2b": [0, 0, 0], "ama_b2c": [1, 0, 2]})
    assert _point_daily_by_carrier(frame, 2025) == {"DHL": 7., "Amazon": 1.}
    assert _point_daily_by_carrier(frame, 2027) == {"DHL": 0., "Amazon": 0.}
    assert _point_daily_by_carrier(None, 2025) is None



def test_structure_payload_from_land_use_files(tmp_path):
    from decade_fixtures import write_land_use_files

    from hagrid_demand.baseline.decade_dashboard import build_decade_dashboard_data

    run = write_decade_run(tmp_path, "decade-trend")
    write_land_use_files(run)
    block = build_decade_dashboard_data({"trend": run})["structure"]["trend"]
    districts = block["districts"]
    assert districts["ids"] == ["A", "B"] and districts["kinds"] == ["city", "umland"]
    assert districts["population_index"]["2026"] == pytest.approx([1.02, 0.99]) and districts["population_index"]["2025"] == [1.0, 1.0]
    assert districts["employees_index"]["2026"] == pytest.approx([1.01, 1.01]) and len(districts["geo"]["features"]) == 2
    assert districts["forecast_index"]["2026"] == pytest.approx([1.02, 0.99])
    sites = block["sites"]
    assert sites["ids"] == ["lu:res:neubau:0", "lu:res:neubau:1", "lu:biz:Q:2026:0"] and sites["year_opened"] == [2026] * 3
    assert sites["segment"] == ["private", "private", "business"] and sites["size"] == pytest.approx([5., 5., 12.])
    [development] = block["developments"]
    assert (development["name"], development["district_id"], development["residents"]) == ("Neubau", "A", {"2025": 0.0, "2026": 10.0})
    assert development["parcels_per_day"]["2025"] == 0.0 and development["parcels_per_day"]["2026"] > 1.   # expected demand
    assert block["age"]["bands"][0] == "0-4" and block["age"]["persons"]["2025"][0] == pytest.approx(10.)
    assert block["meta"]["variant"] == "prognose"


def test_structure_payload_is_null_without_land_use(payload):
    assert payload["structure"] == {"trend": None, "boom": None}



def test_change_payload_aggregates_sites_to_hexagons(tmp_path):
    from decade_fixtures import write_change_files

    from hagrid_demand.baseline.decade_dashboard import build_decade_dashboard_data

    run = write_decade_run(tmp_path, "decade-trend")
    write_change_files(run)
    change = build_decade_dashboard_data({"trend": run})["change"]
    hexes = change["hex"]
    assert len(hexes["ids"]) == 2 and len(hexes["geo"]["features"]) == 2 and hexes["km2"][0] == pytest.approx(1.663, abs=.01)
    days = pd.read_parquet(run / "annual" / "days.parquet")
    days["date"] = pd.to_datetime(days.date)
    delivery = days.loc[days.date.dt.dayofweek.lt(6) & ~days.holiday.astype(bool)].groupby(days.date.dt.year).size()
    values = change["values"]["trend"]
    west = hexes["ids"].index(min(hexes["ids"], key=lambda key: hexes["centre"][hexes["ids"].index(key)][0]))
    assert values["2025"]["total"][west] == pytest.approx(500. / delivery[2025], abs=.05)       # h1 + h2 in the western hexagon
    total_2026 = 1100. * (500. / 1250.) + 1100. * (250. / 1250.)
    assert values["2026"]["total"][west] == pytest.approx(total_2026 / delivery[2026], abs=.05)
    assert values["2026"]["plain"][west] == pytest.approx(1100. * .5 / delivery[2026], abs=.05)  # without land use: shares of 2025
    east = 1 - west
    assert sum(values["2026"]["total"]) == pytest.approx((1100. + 440.) / delivery[2026], abs=.1)
    assert values["2026"]["total"][east] + values["2026"]["total"][west] == pytest.approx(sum(values["2026"]["total"]))


def test_change_payload_is_null_without_projection(payload):
    assert payload["change"] is None



def test_change_drivers_use_the_model_districts_of_the_sites(tmp_path):
    """The drivers and the city share follow the model's district of every site, not a new geometric assignment."""
    from decade_fixtures import write_change_files, write_land_use_files

    from hagrid_demand.baseline.decade_dashboard import build_decade_dashboard_data

    run = write_decade_run(tmp_path, "decade-trend")
    write_land_use_files(run)
    write_change_files(run)
    geometric = build_decade_dashboard_data({"trend": run})["change"]["districts"]["trend"]
    pd.DataFrame({"site_id": ["h1", "h2", "h3", "f1"], "district_id": ["B", "B", "B", "B"]}).to_parquet(run / "land_use_site_districts.parquet", index=False)
    model = build_decade_dashboard_data({"trend": run})["change"]["districts"]["trend"]
    assert model["2026"]["total"][model["ids"].index("A")] == 0.0 and model["2026"]["total"][model["ids"].index("B")] > 0.
    assert geometric["2026"]["total"] != model["2026"]["total"]
