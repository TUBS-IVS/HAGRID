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
