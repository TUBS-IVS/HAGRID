"""Decade dashboard: one payload over the annual stores of several scenario runs, and its HTML writer.

``build_decade_dashboard_data`` reads one run directory per volume scenario (the first is the primary scenario and
must cover every configured year) and keeps the page small: postcode values are arrays in ``plz.codes`` order, the
calendar is one array per year and every pickup point carries one value per year. Optional run files may be
missing; the matching part of the payload is then ``None``. Only the primary scenario's ``annual/days.parquet``
is required.
"""

from __future__ import annotations

import hashlib
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
LABELS = {"trend": "Trend", "saettigung": "Saturation", "boom": "Boom", "trend-innen": "Trend · infill", "trend-suburban": "Trend · suburban"}
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


def _fit_history(inputs: dict, config: dict, scenario: dict) -> dict | None:
    """A scenario's fitted curve from the first observed year to its start year, in billion parcels.

    The trend path is the linear fit of the observed volumes itself; a chained scenario follows its candidate curve
    scaled to the level of its start year (``apply_volume_scenario``), so the chart shows how each path meets the data.
    """
    from .series import _volume

    starts = [year for year, status in zip(scenario["years"], scenario["status"]) if status != "observed"]
    if not starts:
        return None
    start = starts[0]
    block = (config or {}).get("volume_scenario")
    policy = block["policy"] if isinstance(block, dict) else (config or {}).get("volume_fit_policy", "observed_only")
    curve = block["curve"] if isinstance(block, dict) else "linear"
    first = min(int(row["year"]) for row in inputs["volume_inputs"]["anchors"])
    frame = _volume(inputs, list(range(first, start + 1)), policy).set_index("year")[curve]
    if not np.isfinite(frame.to_numpy(float)).all() or frame.loc[start] <= 0:
        return None
    level = scenario["values"][scenario["years"].index(start)] / float(frame.loc[start])
    return {"years": [int(year) for year in frame.index], "values": [round(float(value) * level, 4) for value in frame],
            "curve": curve}


def _national(loaded: dict[str, dict | None]) -> dict:
    """Observed national anchors (2000-2023), the notebook estimates (2024-2028), every scenario's national volume and
    its fitted curve over the observed years, in billion parcels per year."""
    from .sources import packaged_series_inputs

    inputs = packaged_series_inputs()
    anchors = inputs["volume_inputs"]["anchors"]
    observed = {int(row["year"]): float(row["value"]) for row in anchors if row.get("status") == "observed"}
    estimates = {int(row["year"]): float(row["value"]) for row in anchors if row.get("status") == "legacy_estimate"}
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
        scenarios[name]["fit"] = _fit_history(inputs, run.get("config", {}), scenarios[name])
    years = sorted(observed)
    return {"unit": "billion parcels per year",
            "observed": {"years": years, "values": [round(observed[year] / 1e9, 4) for year in years]},
            "estimates": {"years": sorted(estimates), "values": [round(estimates[year] / 1e9, 4) for year in sorted(estimates)]},
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


def _regional_reference(path: Path) -> dict | None:
    """The calibrated regional volume of the reference year (``reference_regional_annual.json``), if the run has it."""
    for candidate in (path / "reference" / "reference_regional_annual.json", path / "reference_regional_annual.json"):
        if candidate.is_file():
            data = json.loads(candidate.read_text(encoding="utf-8"))
            if np.isfinite(float(data.get("regional_annual", np.nan))) and data.get("year") is not None:
                return {"year": int(data["year"]), "parcels": int(round(float(data["regional_annual"])))}
    return None


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
            "reference": _regional_reference(path),
            "network_status": growth, "shortfall": _shortfall(growth) if growth else 0,
            "assumptions": list(run.get("config", {}).get("assumptions", []))}


# ---------------------------------------------------------------- payload and page


def _district_geo(shapes: gpd.GeoDataFrame) -> dict:
    """Forecast district polygons as a WGS84 FeatureCollection (``id`` property), coordinates rounded to 1e-5."""
    import shapely

    wgs = shapes.to_crs(4326)
    geometries = shapely.remove_repeated_points(shapely.transform(wgs.geometry.values, lambda xy: np.round(xy, 5)))
    features = [{"type": "Feature", "properties": {"id": str(district)}, "geometry": shapely.geometry.mapping(geometry)}
                for district, geometry in zip(wgs.district_id.astype(str), geometries)]
    return json.loads(json.dumps({"type": "FeatureCollection", "features": features}))


def _structure_block(run: dict | None, years: list[int]) -> dict | None:
    """Land-use registers of a run for the structural-change section; None when the run has no land use."""
    if run is None or not (run["path"] / "land_use_districts.parquet").is_file():
        return None
    path = run["path"]
    table = pd.read_parquet(path / "land_use_districts.parquet")
    order = table.drop_duplicates("district_id")
    ids = order.district_id.astype(str).tolist()
    by_year = {int(year): frame.set_index(frame.district_id.astype(str)) for year, frame in table.groupby("year")}

    def per_year(column: str, relative: bool = False) -> dict:
        first = by_year.get(min(by_year)) if by_year else None
        out = {}
        for year in years:
            frame = by_year.get(int(year))
            if frame is None:
                out[str(year)] = None
                continue
            values = frame[column].reindex(ids).to_numpy(float)
            if relative:
                base = first[column].reindex(ids).to_numpy(float)
                values = np.divide(values, base, out=np.full(len(values), np.nan), where=base > 0)
            out[str(year)] = [round(float(value), 5) if np.isfinite(value) else None for value in values]
        return out

    shapes_path = path / "land_use_district_shapes.parquet"
    geo = _district_geo(gpd.read_parquet(shapes_path)) if shapes_path.is_file() else None
    districts = {"ids": ids, "names": order.name.astype(str).tolist(), "kinds": order.kind.astype(str).tolist(), "geo": geo,
                 "population_index": per_year("population_index"), "forecast_index": per_year("forecast_index"),
                 "propensity_index": per_year("propensity_index"), "persons_model": per_year("persons_model"),
                 "employees_index": per_year("employees_model", relative=True)}
    sites = None
    if (path / "land_use_sites.parquet").is_file():
        frame = gpd.read_parquet(path / "land_use_sites.parquet")
        wgs = frame.geometry.to_crs(4326)
        size = frame.population.where(frame.segment.eq("private"), frame.employees).fillna(0.)
        sites = {"ids": frame.site_id.astype(str).tolist(), "segment": frame.segment.astype(str).tolist(),
                 "area": [value if isinstance(value, str) else None for value in frame["area"]],
                 "year_opened": [int(value) for value in frame.year_opened], "lon": wgs.x.round(5).tolist(), "lat": wgs.y.round(5).tolist(),
                 "size": [round(float(value), 3) for value in size]}
    developments = []
    if (path / "land_use_developments.parquet").is_file():
        residents = pd.read_parquet(path / "land_use_developments.parquet")
        projection = _parquet(path / "annual_projection.parquet", ["year", "site_id", "annual_expected"])
        area_of = pd.Series(sites["area"], index=sites["ids"]) if sites else pd.Series(dtype=object)
        expected = None
        if projection is not None:
            homes = projection.loc[projection.site_id.astype(str).isin(area_of.dropna().index)]
            expected = homes.assign(area=homes.site_id.astype(str).map(area_of)).groupby(["year", "area"]).annual_expected.sum()
        delivery_days = run["days"].loc[run["days"].delivery].groupby("year").size()
        for name, group in residents.groupby("name", sort=False):
            values = group.set_index("year").residents_model
            per_day = {str(year): (round(float(expected.get((int(year), name), 0.)) / float(delivery_days.get(int(year), 1)), 2)
                                   if expected is not None else None) for year in years}
            developments.append({"name": str(name), "district_id": str(group.district_id.iloc[0]),
                                 "residents": {str(year): round(float(values.get(int(year), 0.)), 1) for year in years},
                                 "parcels_per_day": per_day})
    age = None
    if (path / "land_use_ages.parquet").is_file():
        ages = pd.read_parquet(path / "land_use_ages.parquet").sort_values(["year", "age_from"])
        bands = ages.drop_duplicates("age_from").band.astype(str).tolist()
        age = {"bands": bands,
               "persons": {str(int(year)): [round(float(value), 1) for value in frame.persons] for year, frame in ages.groupby("year")},
               "propensity": {str(int(year)): [round(float(value), 4) for value in frame.propensity] for year, frame in ages.groupby("year")}}
    meta = dict(run["status"].get("land_use") or {})
    try:
        from .land_use import load_land_use_inputs

        inputs = load_land_use_inputs()
        meta["sources"] = {"population": inputs["source"]["title"] + " (" + inputs["source"]["tables"] + ")",
                           "propensity": inputs["propensity_curve"]["source"], "firms": inputs["firm_rates"]["source"],
                           "developments": inputs["developments"]["source"]}
    except (OSError, KeyError, ValueError):
        pass
    return {"districts": districts, "sites": sites, "developments": developments, "age": age, "meta": meta}


def _pool_structure(structure: dict[str, dict | None]) -> tuple[dict, dict]:
    """Store the district shapes and new sites that several scenarios share once: each block keeps a key into the pool."""
    pool: dict[str, dict] = {"geo": {}, "sites": {}}
    pooled: dict[str, dict | None] = {}
    for name, block in structure.items():
        if block is None:
            pooled[name] = None
            continue
        block = {**block, "districts": dict(block["districts"])}
        for kind, holder, field in (("geo", block["districts"], "geo"), ("sites", block, "sites")):
            value = holder.get(field)
            if value is None:
                continue
            key = hashlib.sha1(json.dumps(_clean(value), sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()[:12]
            pool[kind].setdefault(key, value)
            holder[field] = key
        pooled[name] = block
    return pooled, pool


# --- change over time: hexagon grid, land-use decomposition, city share ------------------------------------------------

HEX_SIZE_M = 800.   # centre-to-vertex of the pointy-top hexagons (about 1.4 km across, 1.66 km2)
_HEX_KEY = 10_000_000


def _hex_cells(x: np.ndarray, y: np.ndarray, size: float = HEX_SIZE_M) -> tuple[np.ndarray, np.ndarray]:
    """Axial coordinates (q, r) of the pointy-top hexagon that contains each point (grid anchored at 0, 0)."""
    q = (np.sqrt(3.) / 3. * x - y / 3.) / size
    r = (2. / 3. * y) / size
    cube_x, cube_z = q, r
    cube_y = -cube_x - cube_z
    rx, ry, rz = np.round(cube_x), np.round(cube_y), np.round(cube_z)
    dx, dy, dz = np.abs(rx - cube_x), np.abs(ry - cube_y), np.abs(rz - cube_z)
    fix_x = (dx > dy) & (dx > dz)
    fix_z = ~fix_x & (dz >= dy)
    rx = np.where(fix_x, -ry - rz, rx)
    rz = np.where(fix_z, -rx - ry, rz)
    return rx.astype(np.int64), rz.astype(np.int64)


def _hex_polygon(q: int, r: int, size: float = HEX_SIZE_M):
    from shapely.geometry import Polygon

    cx, cy = size * np.sqrt(3.) * (q + r / 2.), 1.5 * size * r
    angles = np.radians(30. + 60. * np.arange(6))
    return Polygon(np.column_stack([cx + size * np.cos(angles), cy + size * np.sin(angles)]))


def _site_positions(path: Path) -> pd.DataFrame | None:
    """x, y (EPSG:25832) of every site id: reference sites at their stop, new land-use sites at theirs."""
    if not (path / "reference_stops.parquet").is_file() or not (path / "reference_site_stops.parquet").is_file():
        return None
    stops = gpd.read_parquet(path / "reference_stops.parquet")[["stop_id", "geometry"]]
    links = pd.read_parquet(path / "reference_site_stops.parquet")[["site_id", "stop_id"]]
    if (path / "land_use_stops.parquet").is_file() and (path / "land_use_site_stops.parquet").is_file():
        extra = gpd.read_parquet(path / "land_use_stops.parquet").to_crs(stops.crs)[["stop_id", "geometry"]]
        stops = gpd.GeoDataFrame(pd.concat([stops, extra], ignore_index=True), geometry="geometry", crs=stops.crs)
        links = pd.concat([links, pd.read_parquet(path / "land_use_site_stops.parquet")[["site_id", "stop_id"]]], ignore_index=True)
    xy = stops.to_crs(25832).drop_duplicates("stop_id").set_index("stop_id").geometry
    links = links.drop_duplicates("site_id")
    located = xy.reindex(links.stop_id.to_numpy())
    frame = pd.DataFrame({"x": located.x.to_numpy(), "y": located.y.to_numpy()}, index=links.site_id.astype(str).to_numpy())
    return frame.dropna()


def _projection_rows(path: Path) -> int:
    file = path / "annual_projection.parquet"
    return int(pq.ParquetFile(file).metadata.num_rows) if file.is_file() else 0


def _change_run(run: dict, positions: pd.DataFrame) -> dict | None:
    """Expected demand per site and year with land use (``total``) and with the reference shares (``plain``)."""
    path = run["path"]
    projection = _parquet(path / "annual_projection.parquet", ["year", "site_id", "segment", "annual_expected"])
    if projection is None or projection.empty:
        return None
    projection = projection.assign(site_id=projection.site_id.astype(str))
    plain = projection
    reference = _parquet(path / "reference_sites.parquet", ["site_id", "segment", "historical_share"])
    if reference is not None:
        shares = reference.assign(site_id=reference.site_id.astype(str))
        shares["share"] = shares.historical_share / shares.groupby("segment").historical_share.transform("sum")
        totals = projection.groupby(["year", "segment"]).annual_expected.sum().rename("segment_total").reset_index()
        plain = shares.merge(totals, on="segment")
        plain = plain.assign(annual_expected=plain.share * plain.segment_total)[["year", "site_id", "segment", "annual_expected"]]
    return {"total": projection, "plain": plain, "positions": positions}


def _change_values(run: dict, positions: pd.DataFrame, cell_of: pd.Series, cells: int, years: list[int]) -> tuple | None:
    """Hexagon values, district decomposition and city share of one run; its site projection is released on return."""
    frames = _change_run(run, positions)
    if frames is None:
        return None
    delivery = run["days"].loc[run["days"].delivery].groupby("year").size()
    values = {}
    for key in ("total", "plain"):
        frame = frames[key]
        cell = frame.site_id.map(cell_of)
        grouped = frame.assign(cell=cell).dropna(subset=["cell"]).groupby(["year", "cell"]).annual_expected.sum()
        for year in years:
            entry = values.setdefault(str(year), {})
            days = float(delivery.get(int(year), 0))
            if days <= 0 or int(year) not in grouped.index.get_level_values(0):
                entry[key] = None
                continue
            series = grouped.loc[int(year)]
            series.index = series.index.astype(int)
            entry[key] = [round(float(value), 2) for value in (series.reindex(range(cells), fill_value=0.) / days).to_numpy()]
    shapes_path = run["path"] / "land_use_district_shapes.parquet"
    if not shapes_path.is_file():
        return values, None, None
    from .land_use import assign_districts

    shapes = gpd.read_parquet(shapes_path).to_crs(25832)
    kinds = shapes.set_index(shapes.district_id.astype(str)).kind.astype(str)
    register = _parquet(run["path"] / "land_use_site_districts.parquet")
    if register is not None:  # the model's district of every site (full polygons, configured areas)
        district_of = pd.Series(register.district_id.astype(str).to_numpy(), index=register.site_id.astype(str).to_numpy())
    else:
        district_of = pd.Series(assign_districts(positions[["x", "y"]].to_numpy(float), shapes), index=positions.index)
    block = {"ids": shapes.district_id.astype(str).tolist(), "names": shapes.name.astype(str).tolist(), "kinds": shapes.kind.astype(str).tolist()}
    sums = {key: frames[key].assign(district_id=frames[key].site_id.map(district_of)).groupby(["year", "district_id"]).annual_expected.sum()
            for key in ("total", "plain")}
    for year in years:
        days = float(delivery.get(int(year), 0))
        block[str(year)] = None if days <= 0 or int(year) not in sums["total"].index.get_level_values(0) else {
            key: [round(float(sums[key].get((int(year), district), 0.)) / days, 2) for district in block["ids"]] for key in sums}
    total = sums["total"].groupby(level=0).sum()
    city_mask = np.array([kinds.get(str(district)) == "city" for _, district in sums["total"].index])
    city = sums["total"].loc[city_mask].groupby(level=0).sum()
    share = {str(year): (round(float(city.get(int(year), 0.)) / float(total[int(year)]), 5)
                         if int(year) in total.index and total[int(year)] > 0 else None) for year in years}
    return values, block, share


def _change_block(loaded: dict[str, dict | None], names: list[str], years: list[int]) -> dict | None:
    """Hexagon map values, land-use decomposition by forecast district and the city share of every scenario.

    Two passes keep one run's site projection in memory at a time: the site positions of all runs fix the hexagon
    cells first, then every run's projection is aggregated and released."""
    positions = {}
    for name in names:
        run = loaded.get(name)
        frame = _site_positions(run["path"]) if run is not None and _projection_rows(run["path"]) else None
        if frame is not None:
            positions[name] = frame
    if not positions:
        return None
    site_keys = {}
    for name, frame in positions.items():
        q, r = _hex_cells(frame.x.to_numpy(), frame.y.to_numpy())
        site_keys[name] = pd.Series(q * _HEX_KEY + r, index=frame.index)
    keys = np.unique(np.concatenate([series.to_numpy() for series in site_keys.values()]))
    cells = [(int(key // _HEX_KEY), int(key % _HEX_KEY)) for key in keys]
    polygons = gpd.GeoSeries([_hex_polygon(q, r) for q, r in cells], crs=25832)
    wgs = polygons.to_crs(4326)
    centres = polygons.centroid.to_crs(4326)
    features = [{"type": "Feature", "properties": {"id": f"{q},{r}"},
                 "geometry": {"type": "Polygon", "coordinates": [np.round(np.asarray(geometry.exterior.coords), 5).tolist()]}}
                for (q, r), geometry in zip(cells, wgs)]
    hexes = {"ids": [f"{q},{r}" for q, r in cells], "geo": {"type": "FeatureCollection", "features": features},
             "km2": [round(float(value) / 1e6, 3) for value in polygons.area],
             "centre": [[round(point.x, 5), round(point.y, 5)] for point in centres], "size_m": HEX_SIZE_M}
    values, districts, city_share = {}, {}, {}
    for name in names:
        values[name] = districts[name] = city_share[name] = None
        if name not in positions:
            continue
        cell_of = pd.Series(np.searchsorted(keys, site_keys[name].to_numpy()), index=site_keys[name].index)
        result = _change_values(loaded[name], positions[name], cell_of, len(cells), years)
        if result is not None:
            values[name], districts[name], city_share[name] = result
    return {"hex": hexes, "values": values, "districts": districts, "city_share": city_share}


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
    annual, plz_values, calendar, network, weekday, structure, scenarios = {}, {}, {}, {}, {}, {}, []
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
        structure[name] = _structure_block(run, years)
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
    structure, structure_pool = _pool_structure(structure)
    return _clean({"meta": meta, "years": years, "national": national, "annual": annual,
                   "plz": {**region, "values": plz_values}, "calendar": calendar, "network": network, "weekday": weekday,
                   "structure": structure, "structure_pool": structure_pool, "change": _change_block(loaded, names, years)})


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
