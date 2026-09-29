"""Annual calendar dashboard: data builder over the annual store and HTML writer."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd
import pyarrow.parquet as pq
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


def _lockers_payload(store: Path, days: pd.DataFrame) -> dict:
    """Pickup points with their daily fill, stored and rejected parcels (points x days, row-major)."""
    points = gpd.read_parquet(store / "out_of_home_points.parquet").sort_values("stop_index").reset_index(drop=True)
    year = int(days.date.dt.year.max()) if len(days) else None
    if year is not None and "year_opened" in points:
        # a grown network: only the stations open in the dashboard year, with that year's compartments
        points = points.loc[pd.to_numeric(points.year_opened, errors="coerce").fillna(-np.inf).le(year).to_numpy()].reset_index(drop=True)
        if (store / "out_of_home_network.parquet").is_file():
            network = pd.read_parquet(store / "out_of_home_network.parquet", columns=["year", "stop_index", "compartments"])
            sized = network.loc[network.year.eq(year)].set_index("stop_index").compartments
            points["compartments"] = sized.reindex(points.stop_index).fillna(points.compartments.set_axis(points.stop_index)).to_numpy()
    wgs = points.geometry.to_crs(4326)
    occupancy = pd.read_parquet(store / "locker_occupancy.parquet")
    occupancy["date"] = pd.to_datetime(occupancy.date).dt.normalize()
    occupancy = occupancy.loc[occupancy.date.isin(days.date)]
    frame = occupancy.set_index(["stop_index", "date"])
    index = pd.MultiIndex.from_product([points.stop_index, days.date], names=["stop_index", "date"])
    fill = (frame.occupied / frame.compartments.replace(0, np.nan) * 100).reindex(index).fillna(0.).clip(0, 100).round().astype(int)
    stored = frame.stored.reindex(index).fillna(0).astype(int)
    rejected = frame.rejected.reindex(index).fillna(0).astype(int)
    return {"ids": points.point_id.astype(str).tolist(), "stop_index": points.stop_index.astype(int).tolist(),
            "kind": points.kind.astype(str).tolist(), "carriers": points.carriers.astype(str).tolist(),
            "brand": (points.brand.astype(str).tolist() if "brand" in points else [""] * len(points)),
            "context": (points.context.astype(str).tolist() if "context" in points else ["other"] * len(points)),
            "synthetic": points.synthetic.astype(bool).tolist(), "compartments": points.compartments.astype(int).tolist(),
            "plz": points.plz.astype(str).tolist(), "lon": wgs.x.round(5).tolist(), "lat": wgs.y.round(5).tolist(),
            "fill": fill.tolist(), "stored": stored.tolist(), "rejected": rejected.tolist()}


def _out_of_home_year(store: Path, days: pd.DataFrame, plz: pd.DataFrame, year: int, status: dict, *,
                      single_year: bool) -> dict:
    """The out-of-home status of one year: parcels stored at the points open in *year* per carrier (exact, from
    the stop store), the B2C parcels per carrier and the network of that year. The run status cumulates all
    simulated years, which misreports every year of a multi-year run; the overflow is only tracked per run."""
    points_path = store / "out_of_home_points.parquet"
    if days.empty or not points_path.is_file() or not (store / "stop_daily.parquet").is_file():
        return status
    points = gpd.read_parquet(points_path)
    if "year_opened" in points:
        points = points.loc[pd.to_numeric(points.year_opened, errors="coerce").fillna(-np.inf).le(year).to_numpy()]
    if points.empty:
        return status
    stops = points.stop_index.astype(int).to_numpy()
    columns = ["stop", *[f"{short}_b2c" for _, short in CARRIER_FIELDS.values()]]
    table = pq.read_table(store / "stop_daily.parquet", columns=columns,
                          filters=[("date", ">=", days.date.min().date()), ("date", "<=", days.date.max().date()),
                                   ("stop", ">=", int(stops.min()))]).to_pandas()
    table = table.loc[table.stop.isin(stops)]
    private = plz.loc[plz.segment.eq("private")].groupby("carrier").parcels.sum() if len(plz) else pd.Series(dtype=float)
    kinds = points.kind.astype(str).value_counts()
    synthetic = points.synthetic.astype(bool) if "synthetic" in points else pd.Series(False, index=points.index)
    return {**status,
            "delivered": {carrier: int(table[f"{short}_b2c"].sum()) for carrier, (_, short) in CARRIER_FIELDS.items()},
            "b2c_delivered": {carrier: int(private.get(carrier, 0)) for carrier in CARRIER_FIELDS},
            "osm_points": {str(kind): int(count) for kind, count in kinds.items()},
            "synthetic_counters": int((points.kind.eq("counter") & synthetic).sum()), "synthetic_lockers": 0,
            "overflow_home": status.get("overflow_home") if single_year else None, "scope": f"year {year}"}


def build_annual_dashboard_data(run_dir: Path, year: int | None = None) -> dict:
    """Collect one simulated year of a run's annual store into the dashboard payload."""
    run_dir = Path(run_dir)
    store = run_dir / "annual"
    days = pd.read_parquet(store / "days.parquet")
    days["date"] = pd.to_datetime(days.date).dt.normalize()
    year = int(days.date.dt.year.max()) if year is None else int(year)
    store_years = sorted(int(value) for value in days.date.dt.year.unique())
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
    locker = lockers = None
    occupancy_path = store / "locker_occupancy.parquet"
    if occupancy_path.is_file():
        lockers = _lockers_payload(store, days)
        occupancy = pd.read_parquet(occupancy_path)
        occupancy["date"] = pd.to_datetime(occupancy.date).dt.normalize()
        occupancy = occupancy.loc[occupancy.date.dt.year.eq(year)]
        by_day = occupancy.groupby("date").agg(occupied=("occupied", "sum"), compartments=("compartments", "sum"), stored=("stored", "sum"))
        by_day["full"] = occupancy.assign(full=occupancy.occupied.ge(occupancy.compartments)).groupby("date").full.mean()
        by_day = by_day.reindex(days.date).fillna(0.)
        locker = {"points": int(occupancy.stop_index.nunique()), "compartments": int(occupancy.groupby("stop_index").compartments.first().sum()),
                  "fill": (by_day.occupied / by_day.compartments.replace(0, np.nan)).fillna(0.).round(4).tolist(),
                  "full_share": by_day.full.round(4).tolist(), "stored": by_day.stored.astype(int).tolist()}
    status_path = run_dir / "daily_status.json"
    temporal = json.loads(status_path.read_text(encoding="utf-8")).get("temporal", {}) if status_path.is_file() else {}
    if isinstance(temporal.get("out_of_home"), dict) and temporal["out_of_home"].get("delivered"):
        temporal = {**temporal, "out_of_home": _out_of_home_year(store, days, plz, year, temporal["out_of_home"],
                                                                 single_year=len(store_years) == 1)}
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
        "locker": locker,
        "lockers": lockers,
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
