"""Land-use dynamics 2025-2035: where persons live, how much they order and where firms sit and grow.

Persons follow the official small-area population forecast of Region Hannover (50 forecast districts), their age mix
ages by one year per year with an adjustable cohort effect on the online-ordering propensity, firms grow by WZ
section, and the large new residential and commercial areas become new demand sites. All effects redistribute the
regional volume of the volume scenario; they never change its level.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd

_DATA = Path(__file__).with_name("data") / "land_use.json"
_KEYS = {"enabled", "base_year", "variant", "cohort_shift", "new_firm_share", "grid_m", "persons", "developments", "firm_rates"}
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
    resolved = {**inputs["defaults"], **copy.deepcopy(cfg)}
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
    district_ids = {unit["id"] for unit in inputs["districts"]}
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
