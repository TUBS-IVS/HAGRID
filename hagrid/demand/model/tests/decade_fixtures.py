"""Synthetic scenario runs for the decade dashboard, written through the real ``AnnualStoreWriter``.

A run covers six Hannover postcodes (2 km boxes in EPSG:25832), DHL, Hermes, Amazon and DPD with parcels plus UPS
with business parcels only, and a pickup network of six points in 2025 that grows by a supermarket locker and a
kiosk counter in 2026 (the layout of ``out_of_home_points.parquet`` / ``out_of_home_network.parquet`` after the
network-growth stage). Counts are Poisson draws from a fixed seed, so every run is deterministic.
"""

from __future__ import annotations

import json
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd
from shapely.geometry import Point, box

from hagrid_demand.baseline.allocation import SegmentDay
from hagrid_demand.baseline.annual import AnnualStoreWriter

CRS = "EPSG:25832"
X0, Y0 = 550_000.0, 5_800_000.0
CODES = ["30159", "30161", "30163", "30165", "30167", "30169"]
POPULATION = [12_000, 9_000, 7_000, 5_000, 3_000, 400]
COMPANIES = [300, 150, 120, 80, 40, 200]
CARRIERS = ["DHL", "Hermes", "Amazon", "DPD", "UPS"]
MARKET = {"DHL": .42, "Hermes": .1, "Amazon": .18, "DPD": .09, "UPS": .1}
SHARES = {"private": {"DHL": .45, "Hermes": .2, "Amazon": .25, "DPD": .1, "UPS": 0.},
          "business": {"DHL": .5, "Hermes": .05, "Amazon": 0., "DPD": .15, "UPS": .3}}
HOLIDAYS = {
    2025: ["2025-01-01", "2025-04-18", "2025-04-21", "2025-05-01", "2025-05-29", "2025-06-09", "2025-10-03",
           "2025-10-31", "2025-12-25", "2025-12-26"],
    2026: ["2026-01-01", "2026-04-03", "2026-04-06", "2026-05-01", "2026-05-14", "2026-05-25", "2026-10-03",
           "2026-10-31", "2026-12-25", "2026-12-26"],
}
WEEKDAY_FACTOR = [1.0, 1.05, 1.1, 1.05, 1.0, .7]
# point_id, kind, carriers, brand, context, synthetic, compartments per year, year_opened, poi_type, PLZ position,
# parcels wanted per delivery day. The small Amazon counter is often full and turns parcels away.
POINTS = [
    ("osm:n1", "locker", "DHL", "DHL Packstation", "transit", False, {2025: 76, 2026: 90}, 2025, None, 0, 6.),
    ("osm:n2", "locker", "DHL", "DHL Packstation", "other", False, {2025: 90, 2026: 90}, 2025, None, 1, 4.),
    ("osm:n3", "locker", "DHL", "DHL Packstation", "retail", False, {2025: 120, 2026: 120}, 2025, None, 3, 3.),
    ("osm:n4", "locker", "Amazon", "Amazon Locker", "retail", False, {2025: 60, 2026: 60}, 2025, None, 0, 3.),
    ("osm:n5", "shared_locker", "Hermes|DPD|UPS", "Pickup Station", "other", False, {2025: 40, 2026: 40}, 2025, None, 2, 2.),
    ("syn:counter:Amazon:0", "counter", "Amazon", "synthetic", "retail", True, {2025: 8, 2026: 8}, 2025, None, 1, 6.),
    ("syn:locker:DHL:2026:0", "locker", "DHL", "synthetic", "retail", True, {2026: 76}, 2026, "shop=supermarket", 4, 4.),
    ("syn:counter:Amazon:2026:0", "counter", "Amazon", "synthetic", "retail", True, {2026: 30}, 2026, "shop=kiosk", 5, 2.),
]
HOME_SITES_PER_PLZ = 3
PICKUP_RETAINED = .4  # share of the afternoon occupancy still in the compartments the next morning


def _cell(position: int):
    column, row = position % 3, position // 3
    return X0 + column * 2000., Y0 + row * 2000.


def _postal() -> gpd.GeoDataFrame:
    return gpd.GeoDataFrame({"plz": CODES}, geometry=[box(*_cell(k), _cell(k)[0] + 2000., _cell(k)[1] + 2000.)
                                                       for k in range(len(CODES))], crs=CRS)


def _sites() -> tuple[pd.DataFrame, pd.DataFrame]:
    home = pd.DataFrame([{"site_id": f"res:{code}:{i}", "plz": code, "size": POPULATION[k] / HOME_SITES_PER_PLZ / 1000. * 4.}
                         for k, code in enumerate(CODES) for i in range(HOME_SITES_PER_PLZ)])
    business = pd.DataFrame([{"site_id": f"biz:{code}", "plz": code, "size": COMPANIES[k] / 10.}
                             for k, code in enumerate(CODES)])
    return home, business


def _points(years: list[int], point_years: bool) -> gpd.GeoDataFrame:
    rows = []
    for point_id, kind, carriers, brand, context, synthetic, compartments, opened, poi_type, position, demand in POINTS:
        if opened > max(years) or (not point_years and opened > min(years)):
            continue
        x, y = _cell(position)
        rows.append({"point_id": point_id, "kind": kind, "carriers": carriers, "brand": brand, "context": context,
                     "synthetic": synthetic, "compartments_by_year": compartments, "year_opened": opened,
                     "poi_type": poi_type, "plz": CODES[position], "demand": demand, "geometry": Point(x + 700., y + 900.)})
    return gpd.GeoDataFrame(rows, geometry="geometry", crs=CRS)


def _json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2), encoding="utf-8")


def write_decade_run(root: Path, name: str, *, years=(2025, 2026), growth: float = 1.03, scenario: dict | None = None,
                     seed: int = 7, out_of_home: bool = True, network_file: bool = True, point_years: bool = True,
                     side_files: bool = True, config_years=None) -> Path:
    """Write run directory *root*/*name* with an annual store for *years* and the run files the dashboard reads.

    ``out_of_home=False`` writes no pickup files, ``network_file=False`` no ``out_of_home_network.parquet``,
    ``point_years=False`` a point register without ``year_opened``/``poi_type`` (and without network growth),
    ``side_files=False`` only the annual store (no config, status, series, profiles, projection, units, polygons).
    ``config_years`` overrides the years listed in ``config.resolved.json`` (default: *years*).
    """
    years = sorted(int(year) for year in years)
    run = Path(root) / name
    run.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    home, business = _sites()
    points = _points(years, point_years) if out_of_home else _points(years, point_years).iloc[:0]
    first_point = len(home) + len(business)
    points["stop_index"] = np.arange(len(points)) + first_point
    stops = pd.DataFrame({"stop_id": [*home.site_id, *business.site_id, *("ooh:" + points.point_id)],
                          "stop_index": np.arange(first_point + len(points)),
                          "plz": [*home.plz, *business.plz, *points.plz]})
    site_stops = pd.DataFrame({"site_id": stops.stop_id, "stop_id": stops.stop_id})
    writer = AnnualStoreWriter(run, stops, site_stops, {day for year in years for day in HOLIDAYS[year]})
    private = np.array([SHARES["private"][carrier] for carrier in CARRIERS])
    firms = np.array([SHARES["business"][carrier] for carrier in CARRIERS])
    home_of_plz = {code: int(np.flatnonzero(home.plz.eq(code).to_numpy())[0]) for code in CODES}
    occupancy = []
    for year in years:
        level = growth ** (year - 2025)
        active = points.loc[points.year_opened.le(year)].reset_index(drop=True)
        private_sites = pd.concat([home[["site_id", "plz"]],
                                   pd.DataFrame({"site_id": "ooh:" + active.point_id, "plz": active.plz})], ignore_index=True)
        business_sites = business[["site_id", "plz"]]
        compartments = np.array([row[year] for row in active.compartments_by_year], dtype=np.int64)
        carry = np.zeros(len(active), dtype=np.int64)
        served = [[CARRIERS.index(carrier) for carrier in value.split("|")] for value in active.carriers]
        for date in pd.date_range(f"{year}-01-01", f"{year}-12-31"):
            open_day = date.dayofweek < 6 and date.strftime("%Y-%m-%d") not in HOLIDAYS[year]
            stored = np.zeros(len(active), dtype=np.int64)
            rejected = np.zeros(len(active), dtype=np.int64)
            if not open_day:
                writer.add_day(date, {})
            else:
                factor = level * WEEKDAY_FACTOR[date.dayofweek] * (1.6 if date.month == 12 and date.day < 24 else 1.)
                home_counts = rng.poisson(home["size"].to_numpy()[:, None] * private[None, :] * factor)
                firm_counts = rng.poisson(business["size"].to_numpy()[:, None] * firms[None, :] * factor)
                point_counts = np.zeros((len(active), len(CARRIERS)), dtype=np.int64)
                for k, point in active.iterrows():
                    wanted = int(rng.poisson(point.demand * factor))
                    stored[k] = min(wanted, int(compartments[k] - carry[k]))
                    rejected[k] = wanted - stored[k]
                    weights = private[served[k]] / private[served[k]].sum()
                    point_counts[k, served[k]] = rng.multinomial(stored[k], weights)
                    # parcels that do not fit stay home deliveries of the point's postcode
                    home_counts[home_of_plz[point.plz], served[k][0]] += rejected[k]
                writer.add_day(date, {
                    "private": SegmentDay(private_sites, CARRIERS, np.vstack([home_counts, point_counts]),
                                          np.zeros(len(private_sites)), np.zeros(len(CARRIERS))),
                    "business": SegmentDay(business_sites, CARRIERS, firm_counts, np.zeros(len(business_sites)),
                                           np.zeros(len(CARRIERS)))})
            occupied = carry + stored
            occupancy.append(pd.DataFrame({"date": date, "stop_index": active.stop_index.to_numpy(dtype=np.int64),
                                           "compartments": compartments, "occupied": occupied, "stored": stored,
                                           "rejected": rejected,
                                           "occupied_next_morning": np.floor(occupied * PICKUP_RETAINED).astype(np.int64)}))
            carry = np.floor(occupied * PICKUP_RETAINED).astype(np.int64)
    writer.close({"years": years})
    store = run / "annual"
    if len(points):
        pd.concat(occupancy, ignore_index=True).to_parquet(store / "locker_occupancy.parquet", index=False)
        register = points.assign(compartments=[row[max(k for k in row if k <= max(years))] for row in points.compartments_by_year])
        columns = ["stop_index", "point_id", "kind", "carriers", "brand", "context", "synthetic", "compartments", "plz"]
        if point_years:
            columns += ["year_opened", "poi_type"]
        gpd.GeoDataFrame(register[columns], geometry=register.geometry, crs=CRS).to_parquet(store / "out_of_home_points.parquet", index=False)
        if network_file and point_years:
            wgs = register.geometry.to_crs(4326)
            network = pd.concat([register.loc[register.year_opened.le(year)].assign(
                year=year, compartments=lambda frame, year=year: [row[year] for row in frame.compartments_by_year],
                lon=wgs.x.round(6), lat=wgs.y.round(6)) for year in years], ignore_index=True)
            network[["year", "stop_index", "point_id", "kind", "carriers", "brand", "context", "synthetic", "year_opened",
                     "poi_type", "plz", "compartments", "lon", "lat"]].to_parquet(store / "out_of_home_network.parquet", index=False)
    if not side_files:
        return run
    postal = _postal()
    (run / "sources").mkdir(exist_ok=True)
    postal.to_parquet(run / "sources" / "postal_support.parquet", index=False)
    gpd.GeoDataFrame({"plz": CODES, "population": [float(value) for value in POPULATION],
                      "companies": [float(value) for value in COMPANIES]},
                     geometry=[Point(_cell(k)[0] + 1000., _cell(k)[1] + 1000.) for k in range(len(CODES))], crs=CRS
                     ).to_parquet(run / "reference_units.parquet", index=False)
    pd.DataFrame([{"year": year, "segment": segment, "carrier": carrier, "market_share": MARKET[carrier],
                   "share": SHARES[segment][carrier], "q": .2}
                  for year in years for segment in ("business", "private") for carrier in CARRIERS]
                 ).to_parquet(run / "carrier_profiles.parquet", index=False)
    home_size, firm_size = home.groupby("plz")["size"].sum(), business.set_index("plz")["size"]
    pd.DataFrame([{"year": year, "plz": code, "segment": segment,
                   "annual_expected": float((home_size if segment == "private" else firm_size)[code]) * 303 * growth ** (year - 2025),
                   "growth_factor": .97 * growth ** (year - 2025), "b2b_share": .2}
                  for year in years for code in CODES for segment in ("business", "private")]
                 ).to_parquet(run / "postal_projection.parquet", index=False)
    volume_years = sorted({2021, *years})
    volume = pd.DataFrame({"year": volume_years,
                           "value": [4.51e9 if year == 2021 else 4.364696e9 * growth ** (year - 2025) for year in volume_years],
                           "status": ["observed" if year == 2021 else "forecast" if year <= 2025 or scenario is None
                                      else "scenario_projection" for year in volume_years],
                           "curve": ["observed_anchor" if year == 2021 else "linear" if scenario is None or year <= 2025
                                     else f"{scenario['policy']}/{scenario['curve']}" for year in volume_years]})
    volume = volume.assign(linear=volume.value, logistic=volume.value * .98, exponential=volume.value * 1.02)
    if scenario is not None:
        volume["scenario"] = scenario["name"]
    (run / "series").mkdir(exist_ok=True)
    volume.to_parquet(run / "series" / "volume.parquet", index=False)
    config = {"years": sorted(config_years or years), "reference_year": 2021, "seed": seed,
              "temporal": {"mode": "shipping_transit"}, "assumptions": [f"Fixture scenario run {name}."]}
    if out_of_home:
        config["out_of_home"] = {"enabled": True}
    if scenario is not None:
        config["volume_scenario"] = scenario
    _json(run / "config.resolved.json", config)
    status = {"output_scope": "daily", "years": years,
              "temporal": {"mode": "shipping_transit", "holiday_spread_days": 3, "christmas_pull_forward_days": 5,
                           "events": [{"name": "Prime Day", "carriers": ["Amazon"], "uplift": 2.0}]}}
    if out_of_home:
        status["temporal"]["out_of_home"] = {
            "kinds": ["locker", "shared_locker", "counter"],
            "network_growth": {  # the status block of network_growth.plan_network
                "enabled": True, "reference_year": 2025, "elasticity": 0.6, "resize_existing": True, "growth_years": [2026],
                "reference_points": {"locker:DHL": 3, "locker:Amazon": 1, "shared_locker:Hermes|DPD|UPS": 1, "counter:Amazon": 1},
                "years": {"2026": {"locker:DHL": {"target": 4, "added": 1, "candidates": 3, "shortfall": 0, "demand": 900.},
                                   "counter:Amazon": {"target": 3, "added": 1, "candidates": 1, "shortfall": 1, "demand": 700.}}}}}
    _json(run / "daily_status.json", status)
    return run


def write_land_use_files(run: Path, years=(2025, 2026)) -> None:
    """The land-use registers of a run: two districts over the fixture cells, two development homes (opening in the
    second year) and one new firm, the region's age bands and the status block."""
    years = sorted(int(year) for year in years)
    first, last = years[0], years[-1]
    ids, names, kinds = ["A", "B"], ["Stadtbezirk", "Umlandgemeinde"], ["city", "umland"]
    rows = []
    for year in years:
        step = year - first
        for index, (district, name, kind) in enumerate(zip(ids, names, kinds)):
            rows.append({"year": year, "district_id": district, "name": name, "kind": kind,
                         "population_index": 1. + (0.02 if index == 0 else -0.01) * step, "propensity_index": 1. - 0.01 * step,
                         "persons_model": 1000. * (1. + (0.02 if index == 0 else -0.01) * step), "employees_model": 500. * (1. + 0.01 * step),
                         "forecast_index": 1. + (0.02 if index == 0 else -0.01) * step})
    pd.DataFrame(rows).to_parquet(run / "land_use_districts.parquet", index=False)
    gpd.GeoDataFrame({"district_id": ids, "name": names, "kind": kinds},
                     geometry=[box(X0, Y0, X0 + 4000., Y0 + 4000.), box(X0 + 4000., Y0, X0 + 8000., Y0 + 4000.)], crs=CRS) \
        .to_parquet(run / "land_use_district_shapes.parquet", index=False)
    sites = gpd.GeoDataFrame({"site_id": ["lu:res:neubau:0", "lu:res:neubau:1", "lu:biz:Q:%d:0" % last],
                              "segment": ["private", "private", "business"], "plz": [CODES[0]] * 3, "district_id": ["A", "A", "B"],
                              "area": ["Neubau", "Neubau", None], "year_opened": [last, last, last], "population": [5., 5., np.nan],
                              "employees": [np.nan, np.nan, 12.], "branch": [None, None, "Q"], "historical_share": [.001, .001, .002],
                              "allocation_status": "located"},
                             geometry=[Point(X0 + 500., Y0 + 500.), Point(X0 + 550., Y0 + 500.), Point(X0 + 4500., Y0 + 500.)], crs=CRS)
    sites.to_parquet(run / "land_use_sites.parquet", index=False)
    pd.DataFrame({"year": [year for year in years for _ in range(12)],
                  "band": [f"{start}-{start + 4}" if start < 55 else "55+" for _ in years for start in range(0, 60, 5)],
                  "age_from": [start for _ in years for start in range(0, 60, 5)],
                  "persons": [10. + start for _ in years for start in range(0, 60, 5)],
                  "propensity": [0. if start < 15 else .8 for _ in years for start in range(0, 60, 5)]}) \
        .to_parquet(run / "land_use_ages.parquet", index=False)
    pd.DataFrame({"year": years, "name": "Neubau", "district_id": "A", "residents_model": [0.] * (len(years) - 1) + [10.]}) \
        .to_parquet(run / "land_use_developments.parquet", index=False)
    pd.DataFrame({"year": [last, last], "site_id": ["lu:res:neubau:0", "lu:res:neubau:1"], "segment": "private",
                  "annual_expected": [303., 303.]}).to_parquet(run / "annual_projection.parquet", index=False)
    status = json.loads((run / "daily_status.json").read_text(encoding="utf-8"))
    status["land_use"] = {"variant": "prognose", "base_year": first, "cohort_shift": 0.7, "new_firm_share": 0.3, "districts": 2,
                          "clamped": [], "new_sites": {str(last): 3}, "development_sites": 2, "new_firms": 1, "developments": ["Neubau"]}
    _json(run / "daily_status.json", status)


def write_change_files(run: Path, years=(2025, 2026)) -> None:
    """Site positions and an annual projection for the change section: two homes in one hexagon of the 800 m grid,
    a home and a firm in another; the last-year demand of the first home is doubled by land use."""
    years = sorted(int(year) for year in years)
    first, last = years[0], years[-1]
    sites = pd.DataFrame({"site_id": ["h1", "h2", "h3", "f1"], "segment": ["private", "private", "private", "business"],
                          "historical_share": [.25, .25, .5, 1.]})
    sites.to_parquet(run / "reference_sites.parquet", index=False)
    size = 800.                                    # pointy-top hexagons of the dashboard, anchored at (0, 0)
    row = round(Y0 / (1.5 * size))
    column = round(X0 / (size * 3 ** .5) - row / 2)
    west = (size * 3 ** .5 * (column + row / 2), 1.5 * size * row)
    east = (west[0] + 3 * size * 3 ** .5, west[1])
    gpd.GeoDataFrame({"stop_id": ["s1", "s2", "s3", "s4"], "stop_index": [0, 1, 2, 3], "str_idx": [1, 1, 2, 3]},
                     geometry=[Point(west[0] - 100., west[1]), Point(west[0] + 100., west[1] + 50.), Point(east[0], east[1] - 80.),
                               Point(east[0] + 60., east[1] + 60.)], crs=CRS).to_parquet(run / "reference_stops.parquet", index=False)
    pd.DataFrame({"site_id": ["h1", "h2", "h3", "f1"], "stop_id": ["s1", "s2", "s3", "s4"]}).to_parquet(run / "reference_site_stops.parquet", index=False)
    rows = []
    for year in years:
        boost = 2. if year == last and year != first else 1.
        private = {"h1": 250. * boost, "h2": 250., "h3": 500.}
        scale = 1000. * (1.1 if year == last else 1.) / sum(private.values())
        for site, value in private.items():
            rows.append({"year": year, "site_id": site, "segment": "private", "annual_expected": value * scale})
        rows.append({"year": year, "site_id": "f1", "segment": "business", "annual_expected": 400. * (1.1 if year == last else 1.)})
    pd.DataFrame(rows).to_parquet(run / "annual_projection.parquet", index=False)
