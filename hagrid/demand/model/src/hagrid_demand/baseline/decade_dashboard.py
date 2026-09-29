"""Decade dashboard: one payload over the annual stores of several scenario runs, and its HTML writer.

``build_decade_dashboard_data`` reads one run directory per volume scenario (the first is the primary scenario and
must cover every configured year) and keeps the page small: postcode values are arrays in ``plz.codes`` order, the
calendar is one array per year and every pickup point carries one value per year. Optional run files may be
missing; the matching part of the payload is then ``None``. Only the primary scenario's ``annual/days.parquet``
is required.
"""

from __future__ import annotations

import json
import math
from datetime import datetime, timezone
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd
import pyarrow.compute as pc
import pyarrow.parquet as pq

from hagrid_demand.compatibility.matsim_export import CARRIER_FIELDS

from .annual_dashboard import PLZ_NAMES, _geo


TEMPLATE = Path(__file__).with_name("templates") / "decade_dashboard.html"
PLACEHOLDER = "__DECADE_DATA__"
KINDS = ("locker", "shared_locker", "counter", "shop")
LABELS = {"trend": "Trend", "saettigung": "Saturation", "boom": "Boom"}
MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
DAY_NAMES = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
OOH_ASSUMPTIONS = ("shares_2025", "trend", "kinds", "reach_m", "choice_k", "choice_decay_m", "compartments_by_demand",
                   "pickup_profile")
TEMPORAL_ASSUMPTIONS = ("holiday_spread_days", "christmas_pull_forward_days", "events", "half_delivery_days",
                        "transit_days", "business_saturday_open")


def peak_reason(day: pd.Timestamp, holidays, temporal: dict) -> str:
    """Rule-based reason for a peak day: the rules of ``peakReason`` in the annual dashboard template."""
    day = pd.Timestamp(day).normalize()
    if "12-01" <= day.strftime("%m-%d") < "12-25":
        extra = (", including the orders that would otherwise ship on 24–26 December"
                 if temporal.get("christmas_pull_forward_days") else "")
        return f"It falls in the pre-Christmas peak: gifts are ordered and shipped before 24 December{extra}."
    before = [pd.Timestamp(value) for value in holidays if 0 <= (day - pd.Timestamp(value)).days <= 6]
    if before:
        last = max(before)
        return (f"It follows the public holiday on {DAY_NAMES[last.dayofweek]}, {last.day} {MONTHS[last.month - 1]} "
                f"{last.year}: orders from the holiday ship over the next {temporal.get('holiday_spread_days') or 1} "
                "shipping days.")
    return f"It reflects the season of week {day.isocalendar()[1]} and the weekday pattern."


# ---------------------------------------------------------------- reading one run


def _json_file(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}


def _parquet(path: Path, columns: list[str] | None = None) -> pd.DataFrame | None:
    return pd.read_parquet(path, columns=columns) if path.is_file() else None


def _plz_sums(path: Path) -> pd.DataFrame | None:
    """Parcels per year, PLZ, segment and carrier; the daily PLZ table has millions of rows, so Arrow sums it."""
    if not path.is_file():
        return None
    table = pq.read_table(path, columns=["date", "plz", "segment", "carrier", "parcels"])
    table = table.append_column("year", pc.year(table["date"]))
    frame = table.group_by(["year", "plz", "segment", "carrier"]).aggregate([("parcels", "sum")]).to_pandas()
    return frame.rename(columns={"parcels_sum": "parcels"}).astype({"year": int, "plz": str, "parcels": np.int64})


def _register(store: Path, first_year: int) -> tuple[pd.DataFrame | None, pd.DataFrame | None]:
    """Pickup points (one row each, WGS84 lon/lat) and, when the network register exists, their active years.

    ``out_of_home_network.parquet`` (one row per point and active year) comes first; without it the point register
    ``out_of_home_points.parquet`` is used, where a missing ``year_opened`` means the point serves from *first_year*.
    """
    network_path, points_path = store / "out_of_home_network.parquet", store / "out_of_home_points.parquet"
    yearly = None
    if network_path.is_file():
        network = pd.read_parquet(network_path).astype({"point_id": str})
        yearly = network[["point_id", "year", "compartments"]].astype({"year": int})
        register = network.sort_values(["year", "stop_index"]).drop_duplicates("point_id").drop(columns=["year"])
    elif points_path.is_file():
        points = gpd.read_parquet(points_path)
        wgs = points.geometry.to_crs(4326) if points.crs is not None else points.geometry
        register = pd.DataFrame(points.drop(columns=points.geometry.name)).assign(lon=wgs.x.to_numpy(), lat=wgs.y.to_numpy())
    else:
        return None, None
    register = register.astype({"point_id": str}).reset_index(drop=True)
    defaults = {"brand": "", "context": "other", "synthetic": False, "poi_type": None, "year_opened": first_year,
                "compartments": np.nan, "plz": "", "carriers": "", "lon": np.nan, "lat": np.nan}
    for column, default in defaults.items():
        if column not in register:
            register[column] = default
    register["year_opened"] = pd.to_numeric(register.year_opened, errors="coerce").fillna(first_year).astype(int)
    register["poi_type"] = register.poi_type.where(register.poi_type.notna(), None)
    register["plz"] = register.plz.astype(str)
    return register.sort_values(["year_opened", "stop_index"], kind="stable").reset_index(drop=True), yearly


def _occupancy(path: Path, days: pd.DataFrame) -> pd.DataFrame | None:
    """Per point and year: parcels stored and turned away, peak compartments, and fill on delivery days."""
    if not path.is_file():
        return None
    frame = pd.read_parquet(path, columns=["date", "stop_index", "compartments", "occupied", "stored", "rejected"])
    frame["date"] = pd.to_datetime(frame.date).dt.normalize()
    frame["year"] = frame.date.dt.year.astype(int)
    delivery = frame.date.map(days.set_index("date").delivery).fillna(False).astype(bool).to_numpy()
    capacity = frame.compartments.where(frame.compartments > 0)
    frame["fill"] = (frame.occupied / capacity).clip(0, 1)
    frame["full"] = frame.occupied.ge(frame.compartments) & frame.compartments.gt(0)
    totals = frame.groupby(["stop_index", "year"]).agg(stored=("stored", "sum"), rejected=("rejected", "sum"),
                                                       compartments=("compartments", "max"))
    service = frame.loc[delivery].groupby(["stop_index", "year"]).agg(
        fill=("fill", "mean"), occupied=("occupied", "sum"), capacity=("compartments", "sum"),
        full=("full", "sum"), point_days=("full", "size"))
    return totals.join(service, how="left").reset_index()


def _ooh_inputs(path: Path, config: dict) -> dict | None:
    """The out-of-home inputs the run used: its own ``out_of_home_inputs.json``; older runs fall back to the package
    defaults merged with their config block (which drift when the packaged defaults change)."""
    stored = _json_file(path / "out_of_home_inputs.json")
    if isinstance(stored, dict) and stored:
        return stored
    from .out_of_home import resolve_out_of_home

    try:
        return resolve_out_of_home(config.get("out_of_home"))
    except (ValueError, TypeError, KeyError):
        return None


def _point_daily_by_carrier(frame: pd.DataFrame | None, year: int) -> dict[str, float] | None:
    """Exact B2C parcels stored per carrier in *year* from the pickup-point rows of the stop store (None without them)."""
    if frame is None or frame.empty:
        return None
    rows = frame.loc[pd.to_datetime(frame.date).dt.year.eq(int(year))]
    return {carrier: float(rows[f"{short}_b2c"].sum()) for carrier, (_, short) in CARRIER_FIELDS.items() if f"{short}_b2c" in rows}


def _events(config: dict, year: int) -> list[dict]:
    """Shipping-day windows of the carrier events (Prime Day, Black Week, ...) as day-of-year indices."""
    from .shipping import _event_range, resolve_temporal

    try:
        temporal = resolve_temporal(config.get("temporal"))
    except (ValueError, TypeError, KeyError):
        return []
    rows = []
    for event in (temporal or {}).get("events", []):
        window = _event_range(event, year)
        if window is not None:
            rows.append({"name": event["name"], "carriers": event["carriers"], "uplift": event["uplift"],
                         "from": int(window[0].dayofyear) - 1, "to": int(window[1].dayofyear) - 1})
    return rows


def _load_run(path: Path) -> dict | None:
    """The tables of one run directory; ``None`` without an annual store."""
    store = path / "annual"
    if not (store / "days.parquet").is_file():
        return None
    days = pd.read_parquet(store / "days.parquet")
    days["date"] = pd.to_datetime(days.date).dt.normalize()
    days = days.sort_values("date").reset_index(drop=True)
    days["year"] = days.date.dt.year.astype(int)
    days["holiday"] = days.holiday.astype(bool) if "holiday" in days else False
    days["delivery"] = days.date.dt.dayofweek.lt(6) & ~days.holiday
    config = _json_file(path / "config.resolved.json")
    register, yearly = _register(store, int(days.year.min()))
    occupancy = _occupancy(store / "locker_occupancy.parquet", days)
    # parcels stored per point and year with the point's kind, carriers and postcode
    stored = (occupancy.merge(register[["stop_index", "kind", "carriers", "plz"]], on="stop_index", how="inner")
              if occupancy is not None and register is not None else None)
    return {"path": path, "days": days, "config": config, "status": _json_file(path / "daily_status.json"),
            "plz": _plz_sums(store / "plz_daily.parquet"), "register": register, "yearly": yearly,
            "occupancy": occupancy, "stored": stored,
            "profiles": _parquet(path / "carrier_profiles.parquet"), "projection": _parquet(path / "postal_projection.parquet"),
            "volume": _parquet(path / "series" / "volume.parquet"), "ooh": _ooh_inputs(path, config),
            "point_daily": _parquet(store / "point_daily.parquet") if (store / "point_daily.parquet").is_file() else None}


# ---------------------------------------------------------------- small helpers


def _int(value) -> int:
    return int(round(float(value)))


def _ratio(numerator, denominator, digits: int = 4) -> float | None:
    if denominator is None or numerator is None or not denominator or not math.isfinite(float(denominator)):
        return None
    value = float(numerator) / float(denominator)
    return round(value, digits) if math.isfinite(value) else None


def _cagr(first, last, span: int) -> float | None:
    if first is None or last is None or span <= 0 or first <= 0 or last <= 0:
        return None
    return round((float(last) / float(first)) ** (1. / span) - 1., 5)


def _shares(values: pd.Series) -> list[float]:
    total = float(values.sum())
    return [round(float(value) / total, 4) if total else 0. for value in values]


def _shortfall(value) -> int:
    """Sum of every ``shortfall`` count in a (nested) network-growth status block."""
    if isinstance(value, dict):
        return sum(int(item) if key == "shortfall" and isinstance(item, (int, float)) else _shortfall(item)
                   for key, item in value.items())
    if isinstance(value, list):
        return sum(_shortfall(item) for item in value)
    return 0


def _clean(value):
    """JSON-safe copy: numpy scalars to Python, NaN and infinities to ``None``."""
    if isinstance(value, dict):
        return {str(key): _clean(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_clean(item) for item in value]
    if isinstance(value, (np.bool_, bool)):
        return bool(value)
    if isinstance(value, (np.integer, int)):
        return int(value)
    if isinstance(value, (np.floating, float)):
        return float(value) if math.isfinite(float(value)) else None
    if value is None or isinstance(value, str):
        return value
    return str(value)


def _kinds(register: pd.DataFrame | None) -> list[str]:
    """Pickup kinds of a register in the dashboard's order (lockers, shared boxes, counters, shops, others)."""
    if register is None:
        return []
    found = register.kind.astype(str).unique().tolist()
    return [kind for kind in KINDS if kind in found] + sorted(set(found) - set(KINDS))


# ---------------------------------------------------------------- region, carriers, national series


def _region(paths: dict[str, Path], loaded: dict[str, dict | None]) -> dict:
    """Postcode codes (polygon order, then codes seen only in the stores), names, residents, firms, polygons."""
    def first_file(relative: str) -> Path | None:
        return next((path / relative for path in paths.values() if (path / relative).is_file()), None)

    postal_path = first_file("sources/postal_support.parquet")
    postal = gpd.read_parquet(postal_path).astype({"plz": str}).sort_values("plz").reset_index(drop=True) if postal_path else None
    codes = postal.plz.tolist() if postal is not None else []
    seen = set()
    for run in filter(None, loaded.values()):
        if run["plz"] is not None:
            seen |= set(run["plz"].plz)
        if run["register"] is not None:
            seen |= set(run["register"].plz) - {"", "nan", "None"}
    codes += sorted(seen - set(codes))
    persons = firms = area = None
    units_path = first_file("reference_units.parquet")
    if units_path is not None:
        units = pd.read_parquet(units_path, columns=["plz", "population", "companies"]).astype({"plz": str}).groupby("plz").sum()
        persons = units.population.reindex(codes, fill_value=0.).round().astype(int).tolist()
        firms = units.companies.reindex(codes, fill_value=0.).round().astype(int).tolist()
    if postal is not None:
        km2 = (postal.set_index("plz").geometry.area / 1e6).round(3)
        area = [float(km2[code]) if code in km2.index else None for code in codes]
    return {"codes": codes, "names": [PLZ_NAMES.get(code, "") for code in codes], "persons": persons, "firms": firms,
            "area_km2": area, "geo": _geo(postal) if postal is not None else None}


def _carriers(loaded: dict[str, dict | None], primary: str) -> list[str]:
    """Carriers with parcels or a market share, the primary scenario's largest market share first."""
    shares: dict[str, float] = {}
    present: set[str] = set()
    for name, run in loaded.items():
        if run is None:
            continue
        profiles = run["profiles"]
        if profiles is not None and not profiles.empty:
            first = profiles.loc[profiles.year.eq(profiles.year.min())].groupby("carrier").market_share.first()
            if name == primary:
                shares = {str(carrier): float(value) for carrier, value in first.items()}
            present |= {str(carrier) for carrier, value in first.items() if value > 0}
        if run["plz"] is not None:
            present |= set(run["plz"].loc[run["plz"].parcels.gt(0), "carrier"].astype(str))
        present |= {carrier for carrier in CARRIER_FIELDS if carrier in run["days"] and run["days"][carrier].sum() > 0}
    return sorted(present, key=lambda carrier: (-shares.get(carrier, 0.), carrier))


def _national(loaded: dict[str, dict | None]) -> dict:
    """Observed national anchors (2000-2023) and every scenario's national volume, in billion parcels per year."""
    from .sources import packaged_series_inputs

    anchors = packaged_series_inputs()["volume_inputs"]["anchors"]
    observed = {int(row["year"]): float(row["value"]) for row in anchors if row.get("status") == "observed"}
    scenarios = {}
    for name, run in loaded.items():
        volume = None if run is None else run["volume"]
        if volume is None or volume.empty:
            scenarios[name] = None
            continue
        volume = volume.sort_values("year")
        curve = volume["curve"] if "curve" in volume else pd.Series("", index=volume.index)
        for row in volume.loc[volume.status.isin(["observed", "observed_anchor"]) | curve.eq("observed_anchor")].itertuples():
            observed.setdefault(int(row.year), float(row.value))
        scenarios[name] = {"years": volume.year.astype(int).tolist(), "values": (volume.value / 1e9).round(4).tolist(),
                           "status": volume.status.astype(str).tolist()}
    years = sorted(observed)
    return {"unit": "billion parcels per year",
            "observed": {"years": years, "values": [round(observed[year] / 1e9, 4) for year in years]},
            "scenarios": scenarios}


# ---------------------------------------------------------------- one scenario


def _point_years(run: dict, years: list[int]) -> dict | None:
    """Per point (register order) and year: active flag, compartments and mean fill on delivery days (percent).

    Compartments come from the network register, else from the occupancy table (the year's largest value), else
    from the point register; points outside their active years get NaN.
    """
    register = run["register"]
    if register is None:
        return None
    ids, columns = pd.Index(register.point_id), pd.Index(years)
    stops = register.stop_index.to_numpy() if "stop_index" in register else np.full(len(register), -1)
    if run["yearly"] is not None:
        yearly = run["yearly"]
        active = (yearly.assign(on=1).pivot_table(index="point_id", columns="year", values="on", aggfunc="max")
                  .reindex(index=ids, columns=columns).notna().to_numpy())
        compartments = (yearly.pivot_table(index="point_id", columns="year", values="compartments", aggfunc="max")
                        .reindex(index=ids, columns=columns).to_numpy(float))
    else:
        active = register.year_opened.to_numpy()[:, None] <= np.asarray(years)[None, :]
        compartments = np.full(active.shape, np.nan)
    fill = np.full(active.shape, np.nan)
    occupancy = run["occupancy"]
    if occupancy is not None:
        def table(column: str) -> np.ndarray:
            return (occupancy.pivot_table(index="stop_index", columns="year", values=column, aggfunc="max")
                    .reindex(index=stops, columns=columns).to_numpy(float))
        compartments = np.where(np.isnan(compartments), table("compartments"), compartments)
        fill = table("fill") * 100.
    static = pd.to_numeric(register.compartments, errors="coerce").to_numpy(float)
    compartments = np.where(np.isnan(compartments), static[:, None], compartments)
    return {"active": active, "compartments": np.where(active, compartments, np.nan), "fill": np.where(active, fill, np.nan)}


def _carrier_ooh(stored: pd.DataFrame, b2c: dict[str, int], targets: dict[str, float | None]) -> dict[str, float]:
    """Out-of-home parcels per carrier: exact for single-carrier points; a shared point's parcels are split among its
    carriers in proportion to each carrier's target out-of-home volume (B2C parcels x target share)."""
    result: dict[str, float] = {}
    for carriers, parcels in stored.groupby("carriers").stored.sum().items():
        served = [carrier for carrier in str(carriers).split("|") if carrier]
        if not served:
            continue
        weights = np.asarray([b2c.get(carrier, 0) * (targets.get(carrier) if targets.get(carrier) is not None else 1.)
                              for carrier in served], dtype=float)
        weights = weights / weights.sum() if weights.sum() > 0 else np.full(len(served), 1. / len(served))
        for carrier, weight in zip(served, weights):
            result[carrier] = result.get(carrier, 0.) + float(parcels) * weight
    return result


def _volume_kpis(days: pd.DataFrame, persons: float | None, temporal: dict) -> dict:
    """Parcels, delivery days, mean per delivery day, the peak day and its reason, active stops per day."""
    delivery = days.loc[days.delivery]
    parcels, count = int(days.parcels.sum()), int(len(delivery))
    mean = parcels / count if count else None
    peak = int(np.argmax(days.parcels.to_numpy()))
    holidays = days.loc[days.holiday, "date"].dt.strftime("%Y-%m-%d").tolist()
    return {"parcels": parcels, "b2c": int(days.b2c.sum()), "b2b": int(days.b2b.sum()),
            "per_resident": _ratio(parcels, persons, 2), "delivery_days": count,
            "per_delivery_day": _int(mean) if mean is not None else None,
            "peak": {"date": days.date.iloc[peak].strftime("%Y-%m-%d"), "parcels": int(days.parcels.iloc[peak]),
                     "reason": peak_reason(days.date.iloc[peak], holidays, temporal),
                     "ratio": _ratio(days.parcels.iloc[peak], mean, 3)},
            "stops_per_day": _int(delivery.stops_active.mean()) if count and "stops_active" in delivery else None}


def _carrier_kpis(run: dict, year: int, carriers: list[str], days: pd.DataFrame) -> dict:
    """Parcels per carrier (total, B2C, B2B), market shares and the regional expectation of the projection."""
    projection, profiles = run["projection"], run["profiles"]
    block = {"expected": (_int(projection.loc[projection.year.eq(year), "annual_expected"].sum())
                          if projection is not None and projection.year.eq(year).any() else None),
             "market_share": ({str(carrier): round(float(value), 4) for carrier, value in
                               profiles.loc[profiles.year.eq(year)].groupby("carrier").market_share.first().items()}
                              if profiles is not None and profiles.year.eq(year).any() else None)}
    if run["plz"] is None:  # without the PLZ table only the carriers' day totals are known
        block["carriers"] = {carrier: {"total": int(days[carrier].sum()) if carrier in days else 0, "b2c": None, "b2b": None}
                             for carrier in carriers}
        return block
    sums = run["plz"].loc[run["plz"].year.eq(year)].groupby(["carrier", "segment"]).parcels.sum()
    block["carriers"] = {}
    for carrier in carriers:
        b2c, b2b = int(sums.get((carrier, "private"), 0)), int(sums.get((carrier, "business"), 0))
        block["carriers"][carrier] = {"total": b2c + b2b, "b2c": b2c, "b2b": b2b}
    return block


def _pickup_kpis(run: dict, year: int, block: dict, carriers: list[str], points: dict | None, years: list[int]) -> dict:
    """Channels (home and each pickup kind), out-of-home shares per carrier, network size and utilisation."""
    from .out_of_home import out_of_home_share

    days, register = run["days"].loc[run["days"].year.eq(year)], run["register"]
    stored = run["stored"].loc[run["stored"].year.eq(year)] if run["stored"] is not None else None
    kinds = _kinds(register)
    if stored is not None:
        by_kind = stored.groupby("kind").stored.sum()
        channels = {kind: int(by_kind.get(kind, 0)) for kind in kinds}
    else:  # without the point tables only the day totals of pickup parcels are known
        total = int(days.out_of_home.sum()) if "out_of_home" in days else 0
        channels = {"ooh": total} if total else {}
    result = {"channels": {"home": block["parcels"] - sum(channels.values()), **channels},
              "ooh_b2c_share": _ratio(sum(channels.values()), block["b2c"])}
    inputs = run["ooh"]
    targets = {carrier: round(out_of_home_share(year, carrier, inputs), 4) if inputs is not None else None for carrier in carriers}
    result["ooh_target"] = targets if inputs is not None else None
    result["ooh_share"] = None
    if stored is not None and run["plz"] is not None:
        b2c = {carrier: block["carriers"][carrier]["b2c"] for carrier in carriers}
        exact = _point_daily_by_carrier(run.get("point_daily"), year)
        per_carrier = exact if exact is not None else _carrier_ooh(stored, b2c, targets)
        result["ooh_share_exact"] = exact is not None
        result["ooh_share"] = {carrier: _ratio(per_carrier.get(carrier, 0.), b2c[carrier]) for carrier in carriers}
    result["network"] = None
    if points is not None:
        column = years.index(year)
        on = points["active"][:, column]
        opened = register.loc[register.year_opened.eq(year) & register.poi_type.notna()]
        result["network"] = {"points": {kind: int((on & register.kind.eq(kind).to_numpy()).sum()) for kind in kinds},
                             "compartments": _int(np.nansum(points["compartments"][:, column])),
                             "new_sites": {str(kind): int(count) for kind, count in opened.poi_type.value_counts(sort=False).items()}}
    result["utilisation"] = None if stored is None else {
        "fill": _ratio(stored.occupied.sum(), stored.capacity.sum()), "full_share": _ratio(stored.full.sum(), stored.point_days.sum()),
        "rejected": int(stored.rejected.sum()), "stored": int(stored.stored.sum())}
    return result


def _year_block(run: dict, year: int, carriers: list[str], persons: float | None, points: dict | None,
                years: list[int]) -> dict:
    """KPIs of one scenario year: volumes, peak, carriers, channels, out-of-home shares, network, utilisation."""
    days = run["days"].loc[run["days"].year.eq(year)].reset_index(drop=True)
    block = _volume_kpis(days, persons, run["status"].get("temporal", {}))
    block.update(_carrier_kpis(run, year, carriers, days))
    block.update(_pickup_kpis(run, year, block, carriers, points, years))
    return block


def _plz_block(run: dict, year: int, codes: list[str]) -> dict | None:
    """Home deliveries (B2C, B2B) and out-of-home parcels per postcode; pickup parcels count at the point's postcode."""
    if run["plz"] is None:
        return None
    sums = run["plz"].loc[run["plz"].year.eq(year)].groupby(["segment", "plz"]).parcels.sum()

    def segment(name: str) -> np.ndarray:
        values = sums[name] if name in sums.index.get_level_values(0) else pd.Series(dtype=float)
        return values.reindex(codes, fill_value=0).to_numpy(np.int64)

    stored = run["stored"]
    ooh = (stored.loc[stored.year.eq(year)].groupby("plz").stored.sum().reindex(codes, fill_value=0).to_numpy(np.int64)
           if stored is not None else np.zeros(len(codes), dtype=np.int64))
    return {"home_b2c": np.clip(segment("private") - ooh, 0, None).tolist(), "home_b2b": segment("business").tolist(),
            "ooh": ooh.tolist()}


def _calendar_block(run: dict, year: int) -> dict:
    """Parcels on every day of the year (``None`` for days missing in the store), holidays and event windows."""
    days = run["days"].loc[run["days"].year.eq(year)].set_index("date")
    parcels = days.parcels.reindex(pd.date_range(f"{year}-01-01", f"{year}-12-31"))
    return {"start": f"{year}-01-01", "parcels": [None if pd.isna(value) else int(value) for value in parcels],
            "holidays": [int(date.dayofyear) - 1 for date in days.index[days.holiday.to_numpy()]],
            "events": _events(run["config"], year)}


def _weekday_block(run: dict, year: int) -> dict:
    """Share of the year's parcels per weekday (Monday first), all and per segment."""
    days = run["days"].loc[run["days"].year.eq(year)]
    sums = days.groupby(days.date.dt.dayofweek)[["parcels", "b2c", "b2b"]].sum().reindex(range(7), fill_value=0)
    return {"all": _shares(sums.parcels), "b2c": _shares(sums.b2c), "b2b": _shares(sums.b2b)}


def _network_block(run: dict, points: dict | None) -> dict | None:
    """Every pickup point with its attributes and, per year, compartments and mean fill (``None`` while closed)."""
    register = run["register"]
    if register is None or points is None:
        return None
    return {"ids": register.point_id.tolist(), "kind": register.kind.astype(str).tolist(),
            "carriers": register.carriers.astype(str).tolist(), "brand": register.brand.fillna("").astype(str).tolist(),
            "context": register.context.fillna("other").astype(str).tolist(), "plz": register.plz.tolist(),
            "synthetic": register.synthetic.fillna(False).astype(bool).tolist(),
            "year_opened": register.year_opened.astype(int).tolist(), "poi_type": register.poi_type.tolist(),
            "lon": pd.to_numeric(register.lon, errors="coerce").round(5).tolist(),
            "lat": pd.to_numeric(register.lat, errors="coerce").round(5).tolist(),
            "compartments": [[None if np.isnan(value) else _int(value) for value in row] for row in points["compartments"]],
            "fill": [[None if np.isnan(value) else _int(value) for value in row] for row in points["fill"]]}


def _definition(config: dict) -> dict | None:
    """Volume scenario of a run: its ``volume_scenario`` block, else the plain fit path (linear curve)."""
    if not config:
        return None
    block = config.get("volume_scenario")
    if isinstance(block, dict):
        return {"policy": block.get("policy"), "curve": block.get("curve"), "chain_year": block.get("chain_year"), "chained": True}
    return {"policy": config.get("volume_fit_policy", "observed_only"), "curve": "linear", "chain_year": None, "chained": False}


def _growth(years: list[int], values: dict[int, float]) -> dict | None:
    """First and last value over *years* with the compound annual growth rate between them."""
    listed = [year for year in years if values.get(year) is not None]
    if not listed:
        return None
    first, last = listed[0], listed[-1]
    return {"from_year": first, "to_year": last, "from": values[first], "to": values[last],
            "cagr": _cagr(values[first], values[last], last - first)}


def _scenario_meta(name: str, run: dict | None, path: Path, years: list[int], present: list[int], annual: dict,
                   national: dict | None) -> dict:
    """Name, label, run id, years, national and regional growth, and the network-growth status of one scenario."""
    run = run or {}
    status = run.get("status", {})
    growth = (status.get("out_of_home", {}).get("network_growth")
              or status.get("temporal", {}).get("out_of_home", {}).get("network_growth"))
    return {"name": name, "label": LABELS.get(name.lower(), name.replace("_", " ").replace("-", " ").title()),
            "run_id": path.name, "years": present, "missing_years": [year for year in years if year not in present],
            "definition": _definition(run.get("config", {})),
            "national": _growth(present, dict(zip(national["years"], national["values"])) if national else {}),
            "regional": _growth(present, {year: annual[str(year)]["parcels"] for year in present}),
            "network_status": growth, "shortfall": _shortfall(growth) if growth else 0,
            "assumptions": list(run.get("config", {}).get("assumptions", []))}


# ---------------------------------------------------------------- payload and page


def build_decade_dashboard_data(runs: dict[str, Path]) -> dict:
    """Payload of the decade dashboard over *runs* (scenario name -> run directory; the first is the primary)."""
    if not runs:
        raise ValueError("the decade dashboard needs at least one run")
    paths = {str(name): Path(path) for name, path in runs.items()}
    names = list(paths)
    loaded = {name: _load_run(path) for name, path in paths.items()}
    primary = loaded[names[0]]
    if primary is None:
        raise FileNotFoundError(f"the primary scenario {names[0]} has no annual store: {paths[names[0]] / 'annual' / 'days.parquet'}")
    years = sorted(int(year) for year in primary["days"].year.unique())
    missing = sorted({int(year) for year in primary["config"].get("years", [])} - set(years))
    if missing:
        raise ValueError(f"the primary scenario {names[0]} lacks the simulated years {missing}; it must cover every configured year")
    region = _region(paths, loaded)
    carriers = _carriers(loaded, names[0])
    persons = float(sum(region["persons"])) if region["persons"] else None
    national = _national(loaded)
    annual, plz_values, calendar, network, weekday, scenarios = {}, {}, {}, {}, {}, []
    for name in names:
        run = loaded[name]
        present = sorted(set(run["days"].year) & set(years)) if run is not None else []
        points = _point_years(run, years) if run is not None else None

        def per_year(build) -> dict:
            return {str(year): (build(year) if year in present else None) for year in years}

        annual[name] = per_year(lambda year: _year_block(run, year, carriers, persons, points, years))
        plz_values[name] = per_year(lambda year: _plz_block(run, year, region["codes"]))
        calendar[name] = per_year(lambda year: _calendar_block(run, year))
        weekday[name] = per_year(lambda year: _weekday_block(run, year))
        network[name] = _network_block(run, points) if run is not None else None
        scenarios.append(_scenario_meta(name, run, paths[name], years, present, annual[name], national["scenarios"][name]))
    inputs, temporal = primary["ooh"], primary["status"].get("temporal", {})
    kinds: list[str] = []
    for run in filter(None, loaded.values()):
        kinds += [kind for kind in _kinds(run["register"]) if kind not in kinds]
    meta = {"generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "primary": names[0],
            "scenarios": scenarios, "carriers": carriers, "kinds": kinds,
            "region": {"persons": persons, "firms": float(sum(region["firms"])) if region["firms"] else None,
                       "plz": len(region["codes"])},
            "assumptions": {"scenarios": {item["name"]: item["definition"] for item in scenarios},
                            "out_of_home": {key: inputs[key] for key in OOH_ASSUMPTIONS if key in inputs} if inputs else None,
                            "network_growth": (inputs or {}).get("network_growth"),
                            "temporal": {key: temporal[key] for key in TEMPORAL_ASSUMPTIONS if key in temporal},
                            "texts": list(primary["config"].get("assumptions", []))},
            "attribution": "© OpenStreetMap contributors (ODbL)"}
    return _clean({"meta": meta, "years": years, "national": national, "annual": annual,
                   "plz": {**region, "values": plz_values}, "calendar": calendar, "network": network, "weekday": weekday})


def write_decade_dashboard(runs: dict[str, Path], out_html: Path, standalone: bool = True) -> Path:
    """Render the decade dashboard over *runs* into *out_html*.

    ``standalone`` writes a complete HTML document for opening from disk; ``False`` writes the page body for hosts
    (such as claude.ai artifacts) that add doctype, charset and viewport themselves.
    """
    payload = json.dumps(build_decade_dashboard_data(runs), ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    # "<" only occurs inside JSON strings; escaping it keeps "</script>" and "<!--" out of the inline script.
    payload = payload.replace("<", "\\u003c")
    page = TEMPLATE.read_text(encoding="utf-8")
    if page.count(PLACEHOLDER) != 1:
        raise ValueError("decade dashboard template needs exactly one data placeholder")
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
