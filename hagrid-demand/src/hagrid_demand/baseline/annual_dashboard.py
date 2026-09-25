"""Annual calendar dashboard: data builder over the annual store and HTML writer."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd
import shapely

from hagrid_demand.compatibility.matsim_export import CARRIER_FIELDS

from .annual import store_columns


TEMPLATE = Path(__file__).with_name("templates") / "annual_dashboard.html"
PLACEHOLDER = "__HAGRID_ANNUAL_DATA__"
# District or town names for orientation only; postcode areas do not follow district boundaries exactly.
PLZ_NAMES = {
    "30159": "Hannover-Mitte", "30161": "List/Oststadt", "30163": "List", "30165": "Vahrenwald", "30167": "Nordstadt",
    "30169": "Calenberger Neustadt", "30171": "Südstadt", "30173": "Südstadt/Bult", "30175": "Zoo/Oststadt",
    "30177": "List-Nord", "30179": "Vahrenheide/Sahlkamp", "30419": "Stöcken/Herrenhausen", "30449": "Linden-Süd",
    "30451": "Linden-Nord", "30453": "Limmer/Davenstedt", "30455": "Badenstedt/Ahlem", "30457": "Wettbergen/Mühlenberg",
    "30459": "Ricklingen", "30519": "Döhren/Wülfel", "30521": "Mittelfeld/Messe", "30539": "Bemerode/Kronsberg",
    "30559": "Kirchrode/Anderten", "30625": "Kleefeld/Groß-Buchholz", "30627": "Roderbruch", "30629": "Misburg",
    "30655": "Groß-Buchholz/Lahe", "30657": "Bothfeld", "30659": "Lahe/Bothfeld", "30669": "Airport",
    "30823": "Garbsen", "30826": "Garbsen", "30827": "Garbsen", "30851": "Langenhagen", "30853": "Langenhagen",
    "30855": "Langenhagen", "30880": "Laatzen", "30890": "Barsinghausen", "30900": "Wedemark", "30916": "Isernhagen",
    "30926": "Seelze", "30938": "Burgwedel", "30952": "Ronnenberg", "30966": "Hemmingen", "30974": "Wennigsen",
    "30982": "Pattensen", "30989": "Gehrden", "31275": "Lehrte", "31303": "Burgdorf", "31311": "Uetze",
    "31319": "Sehnde", "31515": "Wunstorf", "31535": "Neustadt a. Rbge.", "31832": "Springe",
}


def _geo(postal: gpd.GeoDataFrame) -> dict:
    simplified = shapely.coverage_simplify(postal.geometry.values, 20., simplify_boundary=True)
    wgs = gpd.GeoSeries(simplified, crs=postal.crs).to_crs(4326).values
    wgs = shapely.remove_repeated_points(shapely.transform(wgs, lambda xy: np.round(xy, 5)))
    features = [{"type": "Feature", "properties": {"plz": str(code)}, "geometry": shapely.geometry.mapping(geometry)}
                for code, geometry in zip(postal.plz.astype(str), wgs)]
    return json.loads(json.dumps({"type": "FeatureCollection", "features": features}))


def build_annual_dashboard_data(run_dir: Path, year: int | None = None) -> dict:
    """Collect one simulated year of a run's annual store into the dashboard payload."""
    run_dir = Path(run_dir)
    store = run_dir / "annual"
    days = pd.read_parquet(store / "days.parquet")
    days["date"] = pd.to_datetime(days.date).dt.normalize()
    year = int(days.date.dt.year.max()) if year is None else int(year)
    days = days.loc[days.date.dt.year.eq(year)].sort_values("date").reset_index(drop=True)
    if days.empty:
        raise ValueError(f"the annual store has no days for {year}")
    postal = gpd.read_parquet(run_dir / "sources" / "postal_support.parquet").sort_values("plz").reset_index(drop=True)
    codes = postal.plz.astype(str).tolist()
    columns = store_columns()
    column_of = {(carrier, segment): columns.index(f"{short}_{'b2c' if segment == 'private' else 'b2b'}")
                 for carrier, (_, short) in CARRIER_FIELDS.items() for segment in ("private", "business")}
    plz = pd.read_parquet(store / "plz_daily.parquet")
    plz["date"] = pd.to_datetime(plz.date).dt.normalize()
    plz = plz.loc[plz.date.dt.year.eq(year)]
    unknown = sorted(set(plz.plz.astype(str)) - set(codes))
    if unknown:
        raise ValueError(f"PLZ in the annual store without a postal polygon: {unknown[:5]}")
    cube = np.zeros((len(days), len(codes), len(columns)), dtype=np.int64)
    day_index = pd.Series(np.arange(len(days)), index=days.date)
    plz_index = pd.Series(np.arange(len(codes)), index=codes)
    np.add.at(cube, (day_index.reindex(plz.date).to_numpy(), plz_index.reindex(plz.plz.astype(str)).to_numpy(),
                     np.asarray([column_of[(carrier, segment)] for carrier, segment in zip(plz.carrier, plz.segment)])),
              plz.parcels.to_numpy(dtype=np.int64))
    profiles = pd.read_parquet(run_dir / "carrier_profiles.parquet")
    profiles = profiles.loc[profiles.year.eq(year)]
    carriers = (profiles.groupby("carrier").market_share.first().sort_values(ascending=False).index.tolist()
                if not profiles.empty else list(CARRIER_FIELDS))
    units = pd.read_parquet(run_dir / "reference_units.parquet", columns=["plz", "population", "companies"])
    units["plz"] = units.plz.astype(str)
    persons = units.groupby("plz").population.sum().reindex(codes, fill_value=0.)
    firms = units.groupby("plz").companies.sum().reindex(codes, fill_value=0.)
    stops = gpd.read_parquet(run_dir / "reference_stops.parquet")
    stops_per_plz = stops.plz.astype(str).value_counts().reindex(codes, fill_value=0)
    weekday = []
    calendar_path = run_dir / "delivery_calendar.parquet"
    if calendar_path.is_file():
        calendar = pd.read_parquet(calendar_path)
        calendar = calendar.loc[calendar.year.eq(year)].assign(weekday=lambda frame: pd.to_datetime(frame.date).dt.dayofweek)
        for (segment, carrier), group in calendar.groupby(["segment", "carrier"], sort=True):
            entry = {"segment": segment, "carrier": carrier}
            for kind in ("expected", "delivered"):
                by_day = group.groupby("weekday")[kind].sum().reindex(range(7), fill_value=0.)
                entry[kind] = (by_day / by_day.sum()).round(5).tolist() if by_day.sum() > 0 else [0.] * 7
            weekday.append(entry)
    config_path = run_dir / "config.resolved.json"
    spatial = json.loads(config_path.read_text(encoding="utf-8")).get("spatial", {}) if config_path.is_file() else {}
    status_path = run_dir / "daily_status.json"
    temporal = json.loads(status_path.read_text(encoding="utf-8")).get("temporal", {}) if status_path.is_file() else {}
    return {
        "meta": {"run_id": run_dir.name, "year": year, "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                 "persons": float(persons.sum()), "firms": float(firms.sum()), "stops_total": int(stops.stop_id.nunique()),
                 "carriers": carriers, "columns": columns,
                 "column_carriers": [carrier for carrier in CARRIER_FIELDS for _ in (0, 1)],
                 "column_segments": ["private", "business"] * len(CARRIER_FIELDS),
                 "holidays": days.loc[days.holiday.astype(bool), "date"].dt.strftime("%Y-%m-%d").tolist(),
                 "temporal": temporal, "attribution": "© OpenStreetMap contributors (ODbL)",
                 "spatial": {name: float(spatial.get(name, 0.) or 0.) for name in ("carrier_plz_log_sd", "site_frailty_cv")}},
        "days": {"date": days.date.dt.strftime("%Y-%m-%d").tolist(), "weekday": days.weekday.astype(int).tolist(),
                 "holiday": days.holiday.astype(bool).tolist(), "parcels": days.parcels.astype(int).tolist(),
                 "b2c": days.b2c.astype(int).tolist(), "b2b": days.b2b.astype(int).tolist(),
                 "stops_active": days.stops_active.astype(int).tolist(),
                 "out_of_home": (days.out_of_home.astype(int).tolist() if "out_of_home" in days else [0] * len(days)),
                 "parcels_per_stop_mean": days.parcels_per_stop_mean.round(3).tolist(),
                 "carriers": {carrier: (days[carrier].astype(int).tolist() if carrier in days else [0] * len(days))
                              for carrier in CARRIER_FIELDS}},
        "plz": {"codes": codes, "names": [PLZ_NAMES.get(code, "") for code in codes],
                "persons": persons.round(0).astype(int).tolist(), "firms": firms.round(0).astype(int).tolist(),
                "area_km2": (postal.geometry.area / 1e6).round(3).tolist(), "stops": stops_per_plz.astype(int).tolist()},
        "plz_daily": cube.ravel().tolist(),
        "profiles": [{"carrier": carrier, "market_share": float(group.market_share.iloc[0]), "q_b2b": float(group.q.iloc[0]),
                      "p_b2c": float(group.loc[group.segment.eq("private"), "share"].sum()),
                      "p_b2b": float(group.loc[group.segment.eq("business"), "share"].sum())}
                     for carrier, group in profiles.groupby("carrier")],
        "weekday": weekday,
        "geo": _geo(postal),
    }


def write_annual_dashboard(run_dir: Path, out_html: Path, year: int | None = None, standalone: bool = True) -> Path:
    """Render the annual dashboard page for *run_dir* into *out_html*.

    ``standalone`` writes a complete HTML document for opening from disk; ``False`` writes the page
    body for hosts (such as claude.ai artifacts) that add doctype, charset and viewport themselves.
    """
    payload = json.dumps(build_annual_dashboard_data(run_dir, year), ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    payload = payload.replace("</", "<\\/").replace("<!--", "<\\!--")
    page = TEMPLATE.read_text(encoding="utf-8")
    if page.count(PLACEHOLDER) != 1:
        raise ValueError("annual dashboard template needs exactly one data placeholder")
    out_html = Path(out_html)
    out_html.parent.mkdir(parents=True, exist_ok=True)
    page = page.replace(PLACEHOLDER, payload)
    if standalone:
        head, _, body = page.partition("</style>")
        page = ('<!doctype html>\n<html lang="en"><head><meta charset="utf-8">'
                '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n'
                + head + "</style></head><body>" + body + "</body></html>\n")
    out_html.write_text(page, encoding="utf-8")
    return out_html
