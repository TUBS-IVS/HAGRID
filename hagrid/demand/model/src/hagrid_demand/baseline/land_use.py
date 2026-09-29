"""Land-use dynamics 2025-2035: where persons live, how much they order and where firms sit and grow.

Persons follow the official small-area population forecast of Region Hannover (50 forecast districts), their age mix
ages by one year per year with an adjustable cohort effect on the online-ordering propensity, firms grow by WZ
section, and the large new residential and commercial areas become new demand sites. All effects redistribute the
regional volume of the volume scenario; they never change its level.
"""

from __future__ import annotations

import copy
import json
from dataclasses import dataclass
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd

_DATA = Path(__file__).with_name("data") / "land_use.json"
_KEYS = {"enabled", "base_year", "variant", "cohort_shift", "new_firm_share", "grid_m", "persons", "landuse", "developments",
         "firm_rates", "districts"}
_DISTRICT_KEYS = {"id", "name", "kind", "pop_2024", "pop_2034"}
_AREA_KEYS = {"name", "district_id", "residents", "start_year", "ramp_years", "geometry"}


def load_land_use_inputs() -> dict:
    """The packaged forecast table, propensity curve, firm rates, development areas, variants and defaults."""
    return json.loads(_DATA.read_text(encoding="utf-8"))


def _geometry_ok(geometry: object) -> bool:
    if not isinstance(geometry, dict):
        return False
    if isinstance(geometry.get("osm_landuse_name"), str) and geometry["osm_landuse_name"].strip():
        return True
    center, radius = geometry.get("center"), geometry.get("radius_m")
    return (isinstance(center, (list, tuple)) and len(center) == 2 and all(isinstance(value, (int, float)) for value in center)
            and isinstance(radius, (int, float)) and radius > 0)


def resolve_land_use(cfg: dict | None) -> dict | None:
    """Merge a run's ``land_use`` block with the packaged defaults; None when the block is missing or disabled."""
    if cfg is None:
        return None
    if not isinstance(cfg, dict):
        raise ValueError("land_use must be a mapping")
    if unknown := sorted(set(cfg) - _KEYS):
        raise ValueError(f"unknown land_use keys: {', '.join(unknown)}")
    inputs = load_land_use_inputs()
    resolved = {"districts": "standard", "landuse": None, **inputs["defaults"], **copy.deepcopy(cfg)}
    if not resolved.get("enabled", True):
        return None
    if type(resolved["base_year"]) is not int or resolved["base_year"] < 2025:
        raise ValueError("land_use.base_year must be an integer year from 2025 (the forecast starts at the end of 2024)")
    if resolved["variant"] not in inputs["variants"]:
        raise ValueError(f"land_use.variant must be one of {sorted(inputs['variants'])}")
    for key in ("cohort_shift", "new_firm_share"):
        value = resolved[key]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0. <= float(value) <= 1.:
            raise ValueError(f"land_use.{key} must lie in [0, 1]")
        resolved[key] = float(value)
    if isinstance(resolved["grid_m"], bool) or not isinstance(resolved["grid_m"], (int, float)) or resolved["grid_m"] <= 0:
        raise ValueError("land_use.grid_m must be positive")
    resolved["grid_m"] = float(resolved["grid_m"])
    if resolved["developments"] == "standard":
        resolved["developments"] = copy.deepcopy(inputs["developments"]["areas"])
    if resolved["firm_rates"] == "standard":
        resolved["firm_rates"] = dict(inputs["firm_rates"]["rates"])
    if not isinstance(resolved["firm_rates"], dict) or "default" not in resolved["firm_rates"]:
        raise ValueError("land_use.firm_rates must map WZ sections to annual rates and include 'default'")
    if resolved["districts"] == "standard":
        resolved["districts"] = copy.deepcopy(inputs["districts"])
    if not isinstance(resolved["districts"], list) or not resolved["districts"]:
        raise ValueError("land_use.districts must be 'standard' or a list of forecast districts")
    for unit in resolved["districts"]:
        if not isinstance(unit, dict) or _DISTRICT_KEYS - set(unit) or unit["kind"] not in ("city", "umland"):
            raise ValueError(f"land_use districts need {sorted(_DISTRICT_KEYS)} and kind city or umland")
        if not (isinstance(unit["pop_2024"], (int, float)) and unit["pop_2024"] > 0 and isinstance(unit["pop_2034"], (int, float))
                and unit["pop_2034"] > 0):
            raise ValueError(f"land_use district {unit['id']!r} needs positive pop_2024 and pop_2034")
        if unit["kind"] == "city" and not unit.get("stadtteile") or unit["kind"] == "umland" and not unit.get("municipality"):
            raise ValueError(f"land_use district {unit['id']!r} needs stadtteile (city) or municipality (umland)")
    for key in ("persons", "landuse"):
        if resolved[key] is not None and not (isinstance(resolved[key], str) and resolved[key].strip()):
            raise ValueError(f"land_use.{key} must be a file name relative to input_dir")
    district_ids = {unit["id"] for unit in resolved["districts"]}
    if not isinstance(resolved["developments"], list):
        raise ValueError("land_use.developments must be 'standard' or a list of areas")
    for area in resolved["developments"]:
        if not isinstance(area, dict) or set(area) - _AREA_KEYS - {"approximate", "note"} or _AREA_KEYS - set(area):
            raise ValueError(f"land_use development areas need exactly {sorted(_AREA_KEYS)}")
        if area["district_id"] not in district_ids:
            raise ValueError(f"land_use development {area['name']!r} names an unknown district {area['district_id']!r}")
        if not _geometry_ok(area["geometry"]):
            raise ValueError(f"land_use development {area['name']!r} needs osm_landuse_name or center + radius_m")
        if (not isinstance(area["residents"], (int, float)) or area["residents"] < 0 or type(area["start_year"]) is not int
                or type(area["ramp_years"]) is not int or area["ramp_years"] < 1):
            raise ValueError(f"land_use development {area['name']!r} needs residents >= 0, integer start_year and ramp_years >= 1")
    return resolved


# --- districts ------------------------------------------------------------------------------------------------------

def districts(boundaries: gpd.GeoDataFrame, inputs: dict) -> gpd.GeoDataFrame:
    """Polygons of the forecast districts: municipalities (``admin_level=8``) for the Umland and unions of Stadtteile
    (``admin_level=10`` inside the municipality Hannover) for the city districts."""
    level8 = boundaries.loc[boundaries.admin_level.eq(8)]
    level10 = boundaries.loc[boundaries.admin_level.eq(10)]
    units = inputs["districts"]
    rows: list[dict] = []
    city = [unit for unit in units if unit["kind"] == "city"]
    if city:
        hannover = level8.loc[level8.name.eq("Hannover")]
        if hannover.empty:
            raise ValueError("boundaries lack the municipality Hannover (admin_level 8)")
        inside = level10.loc[level10.representative_point().within(hannover.union_all()).to_numpy()]
        wanted = {name for unit in city for name in unit["stadtteile"]}
        if missing := sorted(wanted - set(inside.name)):
            raise ValueError(f"boundaries lack the Stadtteile: {', '.join(missing)}")
        for unit in city:
            geometry = inside.loc[inside.name.isin(unit["stadtteile"])].union_all()
            rows.append({"district_id": unit["id"], "name": unit["name"], "kind": "city", "geometry": geometry})
    umland = [unit for unit in units if unit["kind"] == "umland"]
    if missing := sorted({unit["municipality"] for unit in umland} - set(level8.name)):
        raise ValueError(f"boundaries lack the municipalities: {', '.join(missing)}")
    for unit in umland:
        geometry = level8.loc[level8.name.eq(unit["municipality"])].union_all()
        rows.append({"district_id": unit["id"], "name": unit["name"], "kind": "umland", "geometry": geometry})
    return gpd.GeoDataFrame(rows, geometry="geometry", crs=boundaries.crs)


def assign_districts(xy: np.ndarray, frame: gpd.GeoDataFrame) -> np.ndarray:
    """District of every point (x, y in the districts' CRS); points outside all districts take the nearest one."""
    xy = np.asarray(xy, dtype=float).reshape(-1, 2)
    points = gpd.GeoDataFrame(geometry=gpd.points_from_xy(xy[:, 0], xy[:, 1]), crs=frame.crs)
    polygons = frame[["district_id", "geometry"]]
    joined = gpd.sjoin(points, polygons, how="left", predicate="within")
    result = joined.loc[~joined.index.duplicated(keep="first"), "district_id"].reindex(points.index)
    missing = result.isna().to_numpy()
    if missing.any():
        nearest = gpd.sjoin_nearest(points.loc[missing], polygons, how="left")
        nearest = nearest.loc[~nearest.index.duplicated(keep="first"), "district_id"]
        result.loc[missing] = nearest.reindex(points.index[missing]).to_numpy()
    return result.to_numpy(dtype=object)


# --- population -----------------------------------------------------------------------------------------------------

def _forecast_value(pop_2024: float, pop_2034: float, year: int) -> float:
    """Linear between the forecast base (end of 2024) and horizon (end of 2034), then the mean annual rate."""
    if year <= 2034:
        return pop_2024 + (pop_2034 - pop_2024) * (year - 2024) / 10.
    rate = (pop_2034 / pop_2024) ** 0.1 - 1. if pop_2024 > 0 else 0.
    return pop_2034 * (1. + rate) ** (year - 2034)


def district_population(inputs: dict, years, base_year: int, variant: str) -> pd.DataFrame:
    """Population of every district and year (forecast persons) with its index relative to *base_year*.

    Variant offsets change the annual growth of city and Umland districts; each year is rescaled to the regional
    forecast total, so a variant only moves persons between districts. ``forecast_index`` is the index without the
    variant. The table always holds *base_year*.
    """
    offsets = inputs["variants"][variant]
    units = inputs["districts"]
    span = sorted({*[int(year) for year in years], int(base_year)})
    forecast = np.array([[_forecast_value(unit["pop_2024"], unit["pop_2034"], year) for unit in units] for year in span])
    shifted = forecast * np.array([[(1. + float(offsets.get(unit["kind"], 0.))) ** (year - base_year) for unit in units] for year in span])
    shifted *= (forecast.sum(axis=1) / shifted.sum(axis=1))[:, None]
    base = span.index(int(base_year))
    rows = [{"year": year, "district_id": unit["id"], "kind": unit["kind"], "population": float(shifted[i, j]),
             "population_index": float(shifted[i, j] / shifted[base, j]) if shifted[base, j] > 0 else 1.,
             "forecast_index": float(forecast[i, j] / forecast[base, j]) if forecast[base, j] > 0 else 1.}
            for i, year in enumerate(span) for j, unit in enumerate(units)]
    return pd.DataFrame(rows)


# --- age structure and propensity -----------------------------------------------------------------------------------

MAX_AGE = 100


def aged_histograms(persons: pd.DataFrame, years, base_year: int, population_index: pd.DataFrame) -> pd.DataFrame:
    """Age histogram (0..100) of every district and year: the base-year histogram ages by one year per year, the
    youngest ages keep their base-year counts, and the total follows the district's population index."""
    base = (persons.assign(age=persons.age.clip(0, MAX_AGE).astype(int)).groupby(["district_id", "age"]).persons.sum()
            .unstack(fill_value=0.).reindex(columns=range(MAX_AGE + 1), fill_value=0.))
    index = population_index.set_index(["year", "district_id"]).population_index
    frames = []
    for year in sorted({*[int(value) for value in years], int(base_year)}):
        shift = max(0, year - int(base_year))
        values = base.to_numpy(float)
        if shift == 0:
            aged = values.copy()
        else:
            aged = np.zeros_like(values)
            if shift <= MAX_AGE:
                aged[:, shift:MAX_AGE] = values[:, :MAX_AGE - shift]          # everybody is `shift` years older
                aged[:, MAX_AGE] = values[:, MAX_AGE - shift:].sum(axis=1)     # ages from 100 on share the last bucket
            else:
                aged[:, MAX_AGE] = values.sum(axis=1)
            young = min(shift, MAX_AGE)
            aged[:, :young] = values[:, :young]                                # the youngest ages keep their base counts
        totals = aged.sum(axis=1)
        target = values.sum(axis=1) * np.array([float(index.get((year, district), 1.)) for district in base.index])
        aged *= np.divide(target, totals, out=np.zeros_like(target), where=totals > 0)[:, None]
        frame = pd.DataFrame(aged, index=base.index, columns=range(MAX_AGE + 1)).stack().rename("persons").reset_index()
        frames.append(frame.rename(columns={"level_1": "age"}).assign(year=year))
    return pd.concat(frames, ignore_index=True)[["year", "district_id", "age", "persons"]]


def _curve(ages: np.ndarray, curve: list[dict]) -> np.ndarray:
    ages = np.clip(np.floor(ages), curve[0]["from"], curve[-1]["to"])
    result = np.zeros(len(ages))
    for band in curve:
        result[(ages >= band["from"]) & (ages <= band["to"])] = float(band["p"])
    return result


def propensity(ages: np.ndarray, year: int, base_year: int, curve: list[dict], cohort_shift: float) -> np.ndarray:
    """Online-ordering propensity by age in *year*: ``p_y(a) = max(curve(a), curve(a - s))`` with
    ``s = cohort_shift * (year - base_year)``.

    A cohort keeps the propensity of its younger self as it ages (``cohort_shift`` 1 = fully, 0 = not at all); the age
    curve of the base year is the floor, so young adults who were children in the base year order like young adults.
    """
    ages = np.asarray(ages, dtype=float)
    shift = float(cohort_shift) * (int(year) - int(base_year))
    return np.maximum(_curve(ages, curve), _curve(ages - shift, curve))


def propensity_index(histograms: pd.DataFrame, curve: list[dict], cohort_shift: float, base_year: int) -> pd.DataFrame:
    """Mean propensity per person of every district and year relative to the base year."""
    rows = []
    for year, group in histograms.groupby("year"):
        weights = propensity(group.age.to_numpy(), int(year), base_year, curve, cohort_shift)
        mean = (group.assign(weighted=group.persons.to_numpy() * weights).groupby("district_id")[["weighted", "persons"]].sum())
        rows.append(pd.DataFrame({"year": int(year), "district_id": mean.index,
                                  "mean": np.divide(mean.weighted, mean.persons, out=np.zeros(len(mean)), where=mean.persons > 0)}))
    table = pd.concat(rows, ignore_index=True)
    base = table.loc[table.year.eq(int(base_year))].set_index("district_id")["mean"]
    reference = base.reindex(table.district_id).to_numpy()
    table["propensity_index"] = np.where(reference > 0, table["mean"].to_numpy() / np.where(reference > 0, reference, 1.), 1.)
    return table[["year", "district_id", "propensity_index"]]


# --- existing stock, new residents and firms -------------------------------------------------------------------------

def development_residents(developments: list[dict], years, ratio: pd.Series) -> pd.DataFrame:
    """Model persons in every development area and year: linear ramp over ``ramp_years`` from ``start_year``, scaled
    from forecast persons to model persons with the district's ratio (model / forecast population in the base year)."""
    rows = []
    for area in developments:
        scale = float(ratio.get(area["district_id"], 1.))
        for year in sorted(int(value) for value in years):
            ramp = min(1., max(0., (year - int(area["start_year"]) + 1) / float(area["ramp_years"])))
            rows.append({"year": year, "name": area["name"], "district_id": area["district_id"],
                         "residents_model": float(area["residents"]) * scale * ramp})
    return pd.DataFrame(rows, columns=["year", "name", "district_id", "residents_model"])


def existing_factor(model_persons: pd.Series, population_index: pd.DataFrame,
                    development_residents: pd.DataFrame) -> tuple[pd.DataFrame, list[dict]]:
    """Factor of the existing residential stock: ``max(0, P * index - R) / P`` per district and year, where ``R`` are
    the model persons of the development areas already moved in. Clamped districts are reported."""
    table = population_index[["year", "district_id", "population_index"]].copy()
    moved = development_residents.groupby(["year", "district_id"]).residents_model.sum() if len(development_residents) else pd.Series(dtype=float)
    persons = model_persons.reindex(table.district_id).to_numpy(float)
    persons = np.nan_to_num(persons, nan=0.)
    residents = np.array([float(moved.get((int(year), district), 0.)) for year, district in zip(table.year, table.district_id)])
    wanted = persons * table.population_index.to_numpy(float) - residents
    factor = np.where(persons > 0, np.maximum(wanted, 0.) / np.where(persons > 0, persons, 1.), table.population_index.to_numpy(float))
    warnings = [{"year": int(year), "district_id": district, "missing_persons": float(-value)}
                for year, district, value, base in zip(table.year, table.district_id, wanted, persons) if base > 0 and value < 0]
    return table.assign(existing_factor=factor)[["year", "district_id", "existing_factor"]], warnings


def firm_factor(branch: pd.Series, years, base_year: int, rates: dict, new_firm_share: float) -> pd.DataFrame:
    """Factor of every existing firm: its branch grows at ``rates[branch]`` (else ``rates['default']``) and keeps
    ``1 - new_firm_share`` of that growth; the rest goes to new firms."""
    rate = branch.map(lambda value: rates.get(value, rates["default"]) if isinstance(value, str) else rates["default"]).astype(float)
    frames = [pd.DataFrame({"year": int(year), "site_id": branch.index.astype(str),
                            "factor": 1. + (1. - float(new_firm_share)) * ((1. + rate.to_numpy()) ** (int(year) - int(base_year)) - 1.)})
              for year in sorted({*[int(value) for value in years], int(base_year)})]
    return pd.concat(frames, ignore_index=True)


def site_firm_factor(companies: pd.DataFrame, years, base_year: int, rates: dict, new_firm_share: float) -> pd.DataFrame:
    """Factor of every existing business site (building): the employee-weighted mean of its companies' branch growth
    (``firm_factor``); a site whose companies report no employees weights them equally."""
    companies = companies.reset_index(drop=True)
    keys = pd.Series(companies.branch.to_numpy(), index=companies.index.astype(str))
    table = firm_factor(keys, years, base_year, rates, new_firm_share)
    position = table.site_id.astype(int).to_numpy()
    weights = companies.employees.fillna(0.).clip(lower=0.).to_numpy(float)[position]
    sites = companies.site_id.astype(str).to_numpy()[position]
    frame = pd.DataFrame({"year": table.year.to_numpy(), "site_id": sites, "factor": table.factor.to_numpy(), "weight": weights})
    frame["weighted"] = frame.factor * frame.weight
    grouped = frame.groupby(["year", "site_id"], sort=True).agg(weighted=("weighted", "sum"), weight=("weight", "sum"),
                                                                 plain=("factor", "mean")).reset_index()
    grouped["factor"] = np.where(grouped.weight > 0, grouped.weighted / grouped.weight.where(grouped.weight > 0, 1.), grouped.plain)
    return grouped[["year", "site_id", "factor"]]


# --- new sites ------------------------------------------------------------------------------------------------------

def _slug(name: str) -> str:
    import re
    import unicodedata

    ascii_name = unicodedata.normalize("NFKD", str(name)).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^a-z0-9]+", "-", ascii_name.lower()).strip("-")


def development_geometry(area: dict, landuse: gpd.GeoDataFrame):
    """Polygon of a development area: the OSM land-use polygons with exactly its name, else a circle around its centre."""
    from shapely.geometry import Point

    geometry = area["geometry"]
    name = geometry.get("osm_landuse_name")
    if name:
        matches = landuse.loc[landuse.name.eq(name)]
        if matches.empty:
            raise ValueError(f"no OSM land-use polygon named {name!r} for development {area['name']!r}")
        return matches.union_all()
    return Point(float(geometry["center"][0]), float(geometry["center"][1])).buffer(float(geometry["radius_m"]), 64)


def _postal_codes(points: gpd.GeoSeries, postal: gpd.GeoDataFrame) -> np.ndarray:
    frame = gpd.GeoDataFrame(geometry=points.reset_index(drop=True), crs=points.crs)
    joined = gpd.sjoin(frame, postal[["plz", "geometry"]].to_crs(frame.crs), how="left", predicate="within")
    codes = joined.loc[~joined.index.duplicated(keep="first"), "plz"].reindex(frame.index)
    missing = codes.isna().to_numpy()
    if missing.any():
        nearest = gpd.sjoin_nearest(frame.loc[missing], postal[["plz", "geometry"]].to_crs(frame.crs), how="left")
        codes.loc[missing] = nearest.loc[~nearest.index.duplicated(keep="first"), "plz"].reindex(frame.index[missing]).to_numpy()
    return codes.astype(str).to_numpy()


def _grid_points(polygon, grid_m: float, minimum: int = 5) -> list:
    from shapely.geometry import Point

    spacing = float(grid_m)
    minx, miny, maxx, maxy = polygon.bounds
    while True:
        points = [Point(x, y) for y in np.arange(miny + spacing / 2., maxy, spacing) for x in np.arange(minx + spacing / 2., maxx, spacing)]
        points = [point for point in points if polygon.contains(point)]
        if len(points) >= minimum or spacing <= 1.:
            return points or [polygon.representative_point()]
        spacing /= 2.


def development_sites(areas: list[dict], landuse: gpd.GeoDataFrame, residents: pd.DataFrame, share_per_person: pd.Series,
                      grid_m: float, postal: gpd.GeoDataFrame) -> gpd.GeoDataFrame:
    """New residential sites on a regular grid inside each development area; the area's full model residents are split
    evenly and each site gets ``population x`` the district's reference share per person."""
    full = residents.groupby("name").residents_model.max() if len(residents) else pd.Series(dtype=float)
    frames = []
    for area in areas:
        points = _grid_points(development_geometry(area, landuse), grid_m)
        people = float(full.get(area["name"], 0.)) / len(points)
        share = float(share_per_person.get(area["district_id"], 0.))
        frames.append(pd.DataFrame({"site_id": [f"lu:res:{_slug(area['name'])}:{index}" for index in range(len(points))],
                                    "segment": "private", "district_id": area["district_id"], "area": area["name"],
                                    "year_opened": int(area["start_year"]), "population": people, "employees": np.nan,
                                    "branch": None, "historical_share": people * share, "allocation_status": "located",
                                    "geometry": points}))
    columns = ["site_id", "segment", "plz", "district_id", "area", "year_opened", "population", "employees", "branch",
               "historical_share", "allocation_status", "geometry"]
    if not frames:
        return gpd.GeoDataFrame({name: [] for name in columns if name != "geometry"}, geometry=gpd.GeoSeries([], crs=landuse.crs), crs=landuse.crs)
    sites = gpd.GeoDataFrame(pd.concat(frames, ignore_index=True), geometry="geometry", crs=landuse.crs)
    sites["plz"] = _postal_codes(sites.geometry, postal)
    return sites[columns]


COMMERCIAL_LANDUSE = ("commercial", "industrial")


def _random_point(polygon, rng: np.random.Generator):
    from shapely.geometry import Point

    minx, miny, maxx, maxy = polygon.bounds
    for _ in range(100):
        point = Point(rng.uniform(minx, maxx), rng.uniform(miny, maxy))
        if polygon.contains(point):
            return point
    return polygon.representative_point()


def new_firms(companies: pd.DataFrame, share_per_employee: float, years, base_year: int, rates: dict, new_firm_share: float,
              parcels: gpd.GeoDataFrame, postal: gpd.GeoDataFrame, rng_for_year) -> gpd.GeoDataFrame:
    """New business sites: each year ``new_firm_share`` of a branch's employment growth opens firms of the branch's mean
    size in OSM commercial and industrial areas (drawn by area); branches that do not grow open none."""
    areas = parcels.loc[parcels.fclass.isin(COMMERCIAL_LANDUSE)].reset_index(drop=True)
    weights = areas.geometry.area.to_numpy(float)
    groups = companies.assign(branch=companies.branch.where(companies.branch.notna(), "other").astype(str))
    totals = groups.groupby("branch").employees.agg(["sum", "count"])
    rows = []
    span = sorted(int(value) for value in years if int(value) > int(base_year))
    for year in span:
        rng = rng_for_year(year)
        for branch in sorted(totals.index):
            employees, count = float(totals.at[branch, "sum"]), int(totals.at[branch, "count"])
            rate = float(rates.get(branch, rates["default"]))
            growth = employees * ((1. + rate) ** (year - base_year) - (1. + rate) ** (year - 1 - base_year))
            size = max(1., employees / count) if count else 1.
            firms = int(np.floor(float(new_firm_share) * growth / size + 0.5)) if growth > 0 else 0
            if firms <= 0 or not len(areas):
                continue
            chosen = rng.choice(len(areas), size=firms, p=weights / weights.sum())
            for index, polygon in enumerate(areas.geometry.iloc[chosen]):
                rows.append({"site_id": f"lu:biz:{branch}:{year}:{index}", "segment": "business", "district_id": None, "area": None,
                             "year_opened": year, "population": np.nan, "employees": size, "branch": branch,
                             "historical_share": size * float(share_per_employee), "allocation_status": "located",
                             "geometry": _random_point(polygon, rng)})
    columns = ["site_id", "segment", "plz", "district_id", "area", "year_opened", "population", "employees", "branch",
               "historical_share", "allocation_status", "geometry"]
    if not rows:
        return gpd.GeoDataFrame({name: [] for name in columns if name != "geometry"}, geometry=gpd.GeoSeries([], crs=parcels.crs), crs=parcels.crs)
    sites = gpd.GeoDataFrame(rows, geometry="geometry", crs=parcels.crs)
    sites["plz"] = _postal_codes(sites.geometry, postal)
    return sites[columns]


def land_use_stops(sites: gpd.GeoDataFrame, first_index: int) -> tuple[gpd.GeoDataFrame, pd.DataFrame]:
    """One stop per new site, numbered from *first_index* in opening order; every development area forms one street
    group (``str_idx = -(1 + area code)``) and every new firm its own (``-(1000 + i)``)."""
    order = sites.assign(_year=pd.to_numeric(sites.year_opened, errors="coerce")).sort_values(["_year", "site_id"], kind="stable")
    order = order.reset_index(drop=True)
    codes = {name: code for code, name in enumerate(sorted(order["area"].dropna().unique()))}
    firm = order["area"].isna().to_numpy()
    firm_rank = np.cumsum(firm) - 1
    str_idx = np.where(firm, -(1000 + firm_rank), [-(1 + codes.get(name, 0)) for name in order["area"].fillna("")])
    stops = gpd.GeoDataFrame({"stop_id": order.site_id.to_numpy(), "stop_index": np.arange(len(order), dtype=np.int64) + int(first_index),
                              "str_idx": str_idx.astype(np.int64), "part": np.nan, "side": None, "section_id": "",
                              "plz": order.plz.astype(str).to_numpy(), "n_units": 1, "expected_daily": 0.,
                              "year_opened": order._year.to_numpy()}, geometry=order.geometry.to_numpy(), crs=sites.crs)
    return stops, pd.DataFrame({"site_id": order.site_id.to_numpy(), "stop_id": order.site_id.to_numpy()})


def site_factors(sites: pd.DataFrame, years, base_year: int, existing: pd.DataFrame, propensity: pd.DataFrame, firms: pd.DataFrame,
                 residents: pd.DataFrame) -> pd.DataFrame:
    """Weight factor of every site row (``site_id``, ``segment``: a building can be a home and a firm) and year: existing homes = existing-stock factor x propensity index of the district,
    existing firms = their branch growth, development sites = ramp x propensity index, new firms = 1 from their opening
    year on. All existing sites have factor 1 in *base_year*."""
    opened = pd.to_numeric(sites.year_opened, errors="coerce").to_numpy(float)
    area = sites["area"].to_numpy(object)  # a GeoDataFrame's .area is the geometric area
    segment = sites.segment.astype(str).to_numpy()
    development = pd.notna(area)
    new_firm = (segment == "business") & ~np.isnan(opened) & ~development
    existing_home = (segment == "private") & ~development
    existing_firm = (segment == "business") & np.isnan(opened)
    stock = existing.set_index(["year", "district_id"]).existing_factor
    mean = propensity.set_index(["year", "district_id"]).propensity_index
    growth = firms.set_index(["year", "site_id"]).factor
    full = residents.groupby("name").residents_model.max() if len(residents) else pd.Series(dtype=float)
    moved = residents.set_index(["year", "name"]).residents_model if len(residents) else pd.Series(dtype=float)
    ids = sites.site_id.astype(str).to_numpy()
    districts_of = sites.district_id.to_numpy(object)
    frames = []
    for year in sorted({*[int(value) for value in years], int(base_year)}):
        keys = list(zip([year] * len(ids), districts_of))
        index = mean.reindex(pd.MultiIndex.from_tuples(keys)).to_numpy(float) if len(keys) else np.zeros(0)
        index = np.where(np.isnan(index), 1., index)
        factor = np.ones(len(ids))
        stock_values = stock.reindex(pd.MultiIndex.from_tuples(keys)).to_numpy(float) if len(keys) else np.zeros(0)
        factor[existing_home] = np.where(np.isnan(stock_values), 1., stock_values)[existing_home] * index[existing_home]
        firm_values = growth.reindex(pd.MultiIndex.from_tuples(list(zip([year] * len(ids), ids)))).to_numpy(float) if len(ids) else np.zeros(0)
        factor[existing_firm] = np.where(np.isnan(firm_values), 1., firm_values)[existing_firm]
        ramp = np.array([float(moved.get((year, name), 0.)) / float(full.get(name, 0.)) if full.get(name, 0.) else 0.
                         for name in area[development]])
        factor[development] = ramp * index[development]
        factor[new_firm] = (year >= opened[new_firm]).astype(float)
        frames.append(pd.DataFrame({"year": year, "site_id": ids, "segment": segment, "factor": factor}))
    return pd.concat(frames, ignore_index=True)


# --- the land-use plan of a run --------------------------------------------------------------------------------------

LAND_USE_STOP_BASE = 1_000_000  # land-use stops follow the pickup points, so those keep the indices of a run without land use
REFERENCE_SITE_COLUMNS = ["site_id", "plz", "segment", "population", "employees", "branch", "weight", "historical_share",
                          "structural_share", "reference_annual", "allocation_status"]
DISTRICT_COLUMNS = ["year", "district_id", "name", "kind", "population_index", "propensity_index", "persons_model",
                    "employees_model", "forecast_index"]


@dataclass
class LandUsePlan:
    """Everything the daily stage needs from the land-use dynamics of one run."""

    sites: pd.DataFrame            # reference sites plus the new sites (reference columns)
    factors: pd.DataFrame          # year, site_id, segment, factor
    new_sites: gpd.GeoDataFrame    # development homes and new firms with district, area, year_opened
    stops: gpd.GeoDataFrame        # one stop per new site
    site_stops: pd.DataFrame       # site_id -> stop_id of the new sites
    districts: gpd.GeoDataFrame    # forecast district polygons
    table: pd.DataFrame            # DISTRICT_COLUMNS per year and district
    status: dict
    ages: pd.DataFrame | None = None          # region: persons and propensity per 5-year age band and year
    developments: pd.DataFrame | None = None  # model residents per development area and year


def read_persons(path: Path) -> pd.DataFrame:
    """Age and position (x, y) of every synthetic person (``POINT (x y)`` in the geometry column)."""
    persons = pd.read_csv(path, usecols=["age", "geometry"])
    coordinates = persons.geometry.str.extract(r"POINT\s*\(\s*([-\d.eE+]+)\s+([-\d.eE+]+)\s*\)").astype(float)
    return pd.DataFrame({"age": pd.to_numeric(persons.age, errors="coerce").fillna(0.).to_numpy(),
                         "x": coordinates[0].to_numpy(), "y": coordinates[1].to_numpy()}).dropna()


def read_landuse(path: Path, crs) -> gpd.GeoDataFrame:
    """OSM land-use polygons (``fclass``, ``name`` and a WKT geometry, EPSG:25832)."""
    from shapely import wkt

    frame = pd.read_csv(path, encoding="utf-8", encoding_errors="replace")
    frame["name"] = frame["name"].where(frame["name"].notna(), None) if "name" in frame else None
    return gpd.GeoDataFrame(frame[["fclass", "name"]], geometry=frame.geometry.map(wkt.loads), crs=crs)


def build_land_use_plan(cfg: dict, years, seed: int, reference_sites: pd.DataFrame, stops: gpd.GeoDataFrame, site_stops: pd.DataFrame,
                        postal: gpd.GeoDataFrame, boundaries: gpd.GeoDataFrame, persons: pd.DataFrame, companies: pd.DataFrame,
                        landuse: gpd.GeoDataFrame) -> LandUsePlan:
    """Districts, factors and new sites of all simulated *years* (``cfg`` = ``resolve_land_use`` output).

    *companies* are the located source firms with their building (``site_id``), ``branch`` and ``employees``;
    *persons* carries ``age``, ``x`` and ``y``.
    """
    from hagrid_demand.common.rng import named_rng

    base = int(cfg["base_year"])
    span = sorted({*[int(year) for year in years], base})
    inputs = load_land_use_inputs()
    units = {"districts": cfg["districts"], "variants": inputs["variants"]}
    frame = districts(boundaries.to_crs(stops.crs), units)
    # every reference site sits at its stop; sites without a stop take their postal area's centre
    stop_xy = stops.drop_duplicates("stop_id").set_index("stop_id").geometry
    stop_of = site_stops.drop_duplicates("site_id").set_index("site_id").stop_id
    ids = reference_sites.site_id.astype(str).drop_duplicates().to_numpy()
    located = gpd.GeoSeries(stop_xy.reindex(stop_of.reindex(ids).to_numpy()).to_numpy(), crs=stops.crs)
    fallback = postal.set_index(postal.plz.astype(str)).representative_point().to_crs(stops.crs)
    plz_of = reference_sites.drop_duplicates("site_id").set_index("site_id").plz.astype(str)
    missing = located.isna().to_numpy()
    if missing.any():
        located[missing] = fallback.reindex(plz_of.reindex(ids[missing]).to_numpy()).to_numpy()
    district_of = pd.Series(assign_districts(np.column_stack([located.x.to_numpy(), located.y.to_numpy()]), frame), index=ids)
    sites = reference_sites.assign(district_id=district_of.reindex(reference_sites.site_id.astype(str)).to_numpy())
    private, business = sites.loc[sites.segment.eq("private")], sites.loc[sites.segment.eq("business")]
    model_persons = private.groupby("district_id").population.sum()
    population = district_population(units, span, base, cfg["variant"])
    forecast_base = population.loc[population.year.eq(base)].set_index("district_id").population
    ratio = (model_persons / forecast_base.reindex(model_persons.index)).fillna(1.)
    residents = development_residents(cfg["developments"], span, ratio)
    existing, warnings = existing_factor(model_persons, population, residents)
    ages = persons.assign(district_id=assign_districts(persons[["x", "y"]].to_numpy(float), frame), persons=1.)
    histograms = aged_histograms(ages[["district_id", "age", "persons"]], span, base, population)
    curve = inputs["propensity_curve"]["bands"]
    propensity_table = propensity_index(histograms, curve, cfg["cohort_shift"], base)
    firm_table = site_firm_factor(companies, span, base, cfg["firm_rates"], cfg["new_firm_share"])
    share_per_person = (private.groupby("district_id").historical_share.sum() / model_persons).replace([np.inf, -np.inf], np.nan).fillna(0.)
    employees = float(business.employees.fillna(0.).sum())
    share_per_employee = float(business.historical_share.sum()) / employees if employees > 0 else 0.
    homes = development_sites(cfg["developments"], landuse, residents, share_per_person, cfg["grid_m"], postal)
    firms = new_firms(companies[["branch", "employees"]], share_per_employee, span, base, cfg["firm_rates"], cfg["new_firm_share"],
                      landuse, postal, lambda year: named_rng(int(seed), year=int(year), channel="land-use-firms"))
    if len(firms):
        firms["district_id"] = assign_districts(np.column_stack([firms.geometry.x, firms.geometry.y]), frame)
    new_sites = gpd.GeoDataFrame(pd.concat([homes, firms], ignore_index=True), geometry="geometry", crs=stops.crs)
    first = max(LAND_USE_STOP_BASE, int(stops.stop_index.max()) + 1)
    new_stops, new_links = land_use_stops(new_sites, first)
    everything = pd.concat([sites[["site_id", "segment", "district_id"]].assign(year_opened=np.nan, area=None),
                            new_sites[["site_id", "segment", "district_id", "year_opened", "area"]]], ignore_index=True)
    factors = site_factors(everything, span, base, existing, propensity_table, firm_table, residents)
    added = new_sites.assign(weight=new_sites.population.fillna(new_sites.employees).fillna(0.), structural_share=0.,
                             reference_annual=0.)
    extended = pd.concat([reference_sites, pd.DataFrame(added[REFERENCE_SITE_COLUMNS])], ignore_index=True)
    table = _district_table(frame, population, propensity_table, existing, residents, model_persons, business, firm_table, firms, span)
    status = {"variant": cfg["variant"], "base_year": base, "cohort_shift": cfg["cohort_shift"], "new_firm_share": cfg["new_firm_share"],
              "districts": int(len(frame)), "clamped": warnings,
              "new_sites": {str(int(year)): int(count) for year, count in new_sites.groupby("year_opened").size().items()},
              "development_sites": int(len(homes)), "new_firms": int(len(firms)),
              "developments": [area["name"] for area in cfg["developments"]]}
    return LandUsePlan(extended, factors, new_sites, new_stops, new_links, frame, table, status,
                       age_bands(histograms, curve, cfg["cohort_shift"], base), residents)


def age_bands(histograms: pd.DataFrame, curve: list[dict], cohort_shift: float, base_year: int, width: int = 5) -> pd.DataFrame:
    """Region-wide persons and mean online propensity per age band (``width`` years, the last band open) and year."""
    rows = []
    for year, group in histograms.groupby("year"):
        weights = propensity(group.age.to_numpy(), int(year), base_year, curve, cohort_shift)
        frame = group.assign(start=np.minimum(group.age.to_numpy() // width * width, 90), weighted=group.persons.to_numpy() * weights)
        bands = frame.groupby("start")[["persons", "weighted"]].sum()
        for start, row in bands.iterrows():
            rows.append({"year": int(year), "band": f"{int(start)}+" if start >= 90 else f"{int(start)}-{int(start) + width - 1}",
                         "age_from": int(start), "persons": float(row.persons),
                         "propensity": float(row.weighted / row.persons) if row.persons > 0 else 0.})
    return pd.DataFrame(rows, columns=["year", "band", "age_from", "persons", "propensity"])


def _district_table(frame, population, propensity_table, existing, residents, model_persons, business, firm_table, firms, span) -> pd.DataFrame:
    """Model persons and employees per district and year next to the forecast indices (register and dashboard)."""
    table = population.merge(propensity_table, on=["year", "district_id"], how="left").merge(existing, on=["year", "district_id"], how="left")
    table = table.merge(frame[["district_id", "name"]], on="district_id", how="left")
    moved = residents.groupby(["year", "district_id"]).residents_model.sum() if len(residents) else pd.Series(dtype=float)
    persons = model_persons.reindex(table.district_id).fillna(0.).to_numpy()
    table["persons_model"] = persons * table.existing_factor.fillna(1.).to_numpy() + np.array(
        [float(moved.get((int(year), district), 0.)) for year, district in zip(table.year, table.district_id)])
    staff = business.assign(site_id=business.site_id.astype(str))[["site_id", "district_id", "employees"]]
    grown = staff.merge(firm_table, on="site_id", how="left")
    grown["employees_now"] = grown.employees.fillna(0.) * grown.factor.fillna(1.)
    employees = grown.groupby(["year", "district_id"]).employees_now.sum()
    opened = firms.assign(year_opened=firms.year_opened.astype(int)) if len(firms) else None
    extra = [float(opened.loc[(opened.district_id == district) & (opened.year_opened <= int(year)), "employees"].sum()) if opened is not None else 0.
             for year, district in zip(table.year, table.district_id)]
    table["employees_model"] = np.array([float(employees.get((int(year), district), 0.)) for year, district in zip(table.year, table.district_id)]) + extra
    table["propensity_index"] = table.propensity_index.fillna(1.)
    return table.loc[table.year.isin(span), DISTRICT_COLUMNS].sort_values(["year", "district_id"]).reset_index(drop=True)
