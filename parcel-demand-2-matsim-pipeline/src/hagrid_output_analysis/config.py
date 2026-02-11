"""
HAGRID Output Analysis – Central Configuration
================================================

All global constants, emission factors, vehicle-class parameters, EV
model settings, and colour palettes live here.

**Design decisions**

* Temperature / season multipliers that depend on :data:`SIM_DATE` are
  exposed as *functions* (not module-level constants) so they stay
  correct even when the date is changed at runtime.
* A :class:`RunConfig` frozen dataclass groups per-run toggles so they
  can be passed through the pipeline without monkey-patching.

Usage
-----
>>> from hagrid_output_analysis.config import EMISSION_CO2_gpkm, DAYEND
>>> from hagrid_output_analysis.config import RunConfig
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum
from typing import Final

import pandas as pd

# ====================================================================
# Simulation boundary
# ====================================================================

DAYEND: Final[int] = 24 * 3600
SIM_DATE: pd.Timestamp = pd.Timestamp("2025-05-13")


# ====================================================================
# Area-type classification (Raumtypen)
# ====================================================================

class AreaType(IntEnum):
    """Numeric area-type IDs used in the region-cluster data."""
    METROPOLITAN_CENTER = 1
    HIGH_DENSITY_RESIDENTIAL = 2
    DENSE_MIXED_USE = 3
    RESIDENTIAL = 4
    INDUSTRIAL = 5
    URBANIZED_PERIPHERY = 6
    RURAL_INDUSTRIAL = 7
    RURAL_NO_INDUSTRIAL = 8


AREA_TYPE_LABELS: dict[int, str] = {
    AreaType.METROPOLITAN_CENTER:     "Metropolitan Center",
    AreaType.HIGH_DENSITY_RESIDENTIAL: "High-Density Residential Use",
    AreaType.DENSE_MIXED_USE:         "Dense Mixed Use",
    AreaType.RESIDENTIAL:             "Residential Use",
    AreaType.INDUSTRIAL:              "Industrial Use",
    AreaType.URBANIZED_PERIPHERY:     "Urbanized Periphery",
    AreaType.RURAL_INDUSTRIAL:        "Rural with Industrial Influence",
    AreaType.RURAL_NO_INDUSTRIAL:     "Rural without Industrial Influence",
}

# Backward-compatible alias.
CATEGORY_NAME_BY_ID: dict[int, str] = AREA_TYPE_LABELS
CATEGORY_ID_BY_NAME: dict[str, int] = {v: k for k, v in AREA_TYPE_LABELS.items()}

AREA_TYPE_GROUPS: dict[int, str] = {
    1: "Urban", 2: "Urban", 3: "Urban",
    4: "Suburban", 5: "Suburban", 6: "Suburban",
    7: "Rural", 8: "Rural",
}
GROUP_BY_ID: dict[int, str] = AREA_TYPE_GROUPS
GROUP_BY_NAME: dict[str, str] = {
    AREA_TYPE_LABELS[i]: g for i, g in AREA_TYPE_GROUPS.items()
}


# ====================================================================
# Colour palettes
# ====================================================================

CATEGORY_COLOR_DICT: dict[str, str] = {
    "Metropolitan Center": "#482878",
    "High-Density Residential Use": "#3f4989",
    "Dense Mixed Use": "#31688e",
    "Residential Use": "#26828e",
    "Industrial Use": "#1f9e89",
    "Urbanized Periphery": "#35b779",
    "Rural with Industrial Influence": "#6fce58",
    "Rural without Industrial Influence": "#b5de2b",
}

CATEGORY_COLOR_DICT_AGG: dict[str, str] = {
    "Urban": "#808080", "Suburban": "#1f78b4", "Rural": "#33a02c",
}


# ====================================================================
# Vehicle-size -> emission-class mapping
# ====================================================================

VEHICLE_SIZE_MAPPING: dict[str, str] = {
    "m":                "van [< 2t]",
    "l":                "van [> 2t]",
    "truck":            "truck [10-20 t] + trailer",
    "truck_light":      "truck [< 10t]",
    "supply_light_van": "van [> 2t]",
}

SUPPLY_TRUCK_CLASSES: frozenset[str] = frozenset({
    "truck [< 10t]",
    "truck [10-20 t] + trailer",
})


# ====================================================================
# Cost parameters
# ====================================================================

VEHICLE_TIME_COST_PER_SEC: Final[float] = 22.87 / 3600

COST_PARAMS_BY_SIZE: dict[str, tuple[int, float, float]] = {
    "size_l":           (230, 189.15, 0.386),
    "size_m":           (165, 171.78, 0.372),
    "supply_light_van": (230, 189.15, 0.386),
    "light":            (1000, 550.63, 0.48643),
    "default":          (2000, 618.55, 0.555126),
}

VEHICLE_CAPACITY_BY_SIZE: dict[str, int] = {
    "l": 230,
    "m": 165,
    "supply_light_van": 230,
    "truck_light": 1000,
    "truck": 2000,
}
"""Maps vehicle-size codes (as returned by :func:`~.utils.extract_veh_size`)
to parcel capacity.  Used by :mod:`.analysis` for load-factor and
status-matrix calculations."""


# ====================================================================
# Postal-code filter (Hannover area) – deduplicated
# ====================================================================

PLZ_LIST: list[str] = sorted({
    "30159", "30161", "30163", "30165", "30167", "30169",
    "30171", "30173", "30175", "30177", "30179", "30419",
    "30449", "30451", "30453", "30455", "30457", "30459",
    "30519", "30521", "30539", "30559", "30625", "30627",
    "30629", "30655", "30657", "30659", "30669", "31303",
})


# ====================================================================
# Emissions – drive factors (STREAM TTW CO2 g/km)
# ====================================================================

EMISSION_CO2_gpkm: dict[str, dict[str, tuple[float, float]]] = {
    "van [< 2t]": {"urban": (197, 213), "rural": (117, 126), "highway": (171, 185)},
    "van [> 2t]": {"urban": (276, 302), "rural": (170, 186), "highway": (250, 275)},
    "truck [< 10t]": {"urban": (419, 472), "rural": (281, 316), "highway": (253, 286)},
    "truck [10-20 t] + trailer": {"urban": (1023, 1387), "rural": (658, 892), "highway": (559, 757)},
}

ENERGY_MJ_PER_KM: dict[str, dict[str, tuple[float, float]]] = {
    "van [< 2t]": {"urban": (2.7, 2.9), "rural": (1.6, 1.7), "highway": (2.3, 2.5)},
    "van [> 2t]": {"urban": (3.7, 4.1), "rural": (2.3, 2.5), "highway": (3.3, 3.7)},
}

WTT_CO2E_G_PER_MJ: Final[float] = 24.0


# ====================================================================
# Emissions – CH4 and N2O (for CO2e basis)
# ====================================================================

GWP: dict[str, float] = {"CO2": 1.0, "CH4": 25.0, "N2O": 298.0}

_CH4_BASE: dict[str, dict[str, float]] = {
    "van [< 2t]":                {"urban": 0.0045, "rural": 0.0035, "highway": 0.0035},
    "van [> 2t]":                {"urban": 0.0055, "rural": 0.0045, "highway": 0.0045},
    "truck [< 10t]":             {"urban": 0.0080, "rural": 0.0060, "highway": 0.0060},
    "truck [10-20 t] + trailer": {"urban": 0.0100, "rural": 0.0080, "highway": 0.0080},
}

_N2O_BASE: dict[str, dict[str, float]] = {
    "van [< 2t]":                {"urban": 0.0190, "rural": 0.0140, "highway": 0.0160},
    "van [> 2t]":                {"urban": 0.0250, "rural": 0.0180, "highway": 0.0200},
    "truck [< 10t]":             {"urban": 0.0400, "rural": 0.0300, "highway": 0.0350},
    "truck [10-20 t] + trailer": {"urban": 0.0600, "rural": 0.0450, "highway": 0.0500},
}


def _build_minmax(
    base: dict[str, dict[str, float]], scale: float,
) -> dict[str, dict[str, tuple[float, float]]]:
    return {
        vc: {seg: (v * (1 - scale), v * (1 + scale)) for seg, v in segs.items()}
        for vc, segs in base.items()
    }


EMISSION_CH4_gpkm = _build_minmax(_CH4_BASE, 0.15)
EMISSION_N2O_gpkm = _build_minmax(_N2O_BASE, 0.15)


# ====================================================================
# Seasonal / temperature helpers (functions, NOT module-level constants)
# ====================================================================

MONTHLY_AVG_TEMP_C: dict[int, float] = {
    1: 0.0, 2: 1.0, 3: 5.0, 4: 9.0, 5: 14.0, 6: 17.0,
    7: 19.0, 8: 18.0, 9: 15.0, 10: 10.0, 11: 5.0, 12: 2.0,
}

MONTH_MULT: dict[int, float] = {
    1: 1.08, 2: 1.08, 3: 1.03, 4: 1.03, 5: 1.02, 6: 1.02,
    7: 1.02, 8: 1.02, 9: 1.03, 10: 1.03, 11: 1.08, 12: 1.08,
}


def get_ambient_temp() -> float:
    """Ambient temperature [degC] for the current :data:`SIM_DATE`."""
    return MONTHLY_AVG_TEMP_C[SIM_DATE.month]


def get_season_mult() -> float:
    """Seasonal emission multiplier for the current :data:`SIM_DATE`."""
    return MONTH_MULT[SIM_DATE.month]


# ====================================================================
# Idle & cold-start parameters
# ====================================================================

VEH_CLASS_PARAMS: dict[str, dict] = {
    "van [< 2t]": {
        "idle_g_per_sec_ttw": 0.80, "t_short": 120, "t_mid": 240, "t_long": 600,
        "curbside_mult": 1.10, "off_threshold": 0.30,
        "cold_start_base_co2_g": 8.0, "min_cold_dwell": 300,
    },
    "van [> 2t]": {
        "idle_g_per_sec_ttw": 0.90, "t_short": 120, "t_mid": 240, "t_long": 600,
        "curbside_mult": 1.10, "off_threshold": 0.30,
        "cold_start_base_co2_g": 10.0, "min_cold_dwell": 300,
    },
    "truck [< 10t]": {
        "idle_g_per_sec_ttw": 1.40, "t_short": 180, "t_mid": 540, "t_long": 900,
        "curbside_mult": 1.05, "off_threshold": 0.25,
        "cold_start_base_co2_g": 12.0, "min_cold_dwell": 600,
    },
    "truck [10-20 t] + trailer": {
        "idle_g_per_sec_ttw": 1.60, "t_short": 180, "t_mid": 540, "t_long": 900,
        "curbside_mult": 1.05, "off_threshold": 0.25,
        "cold_start_base_co2_g": 14.0, "min_cold_dwell": 600,
    },
}

DEFAULT_PARAMS: dict = {
    "idle_g_per_sec_ttw": 1.20, "t_short": 120, "t_mid": 360, "t_long": 600,
    "curbside_mult": 1.10, "off_threshold": 0.30,
    "cold_start_base_co2_g": 10.0, "min_cold_dwell": 300,
}

BASE_IDLE_CO_GPS: Final[float] = 0.005
BASE_IDLE_HC_GPS: Final[float] = 0.002
BASE_IDLE_NOX_GPS: Final[float] = 0.020

IDLE_MULT_BY_CLASS: dict[str, float] = {
    "van [< 2t]": 0.40, "van [> 2t]": 0.60,
    "truck [< 10t]": 1.00, "truck [10-20 t] + trailer": 1.20,
}


# ====================================================================
# EV model
# ====================================================================

GLOBAL_EV_TARGET: float = 0.21

FIXED_EV_SHARES: dict[str, float] = {
    "dhl": 0.4807, "dpd": 0.0347, "hermes": 0.1117, "fedex": 0.0,
}

MIN_EV_SHARES: dict[str, float] = {"amazon": 0.10, "gls": 0.02, "ups": 0.02}
FLEXIBLE_PROVIDERS: frozenset[str] = frozenset({"amazon", "gls", "ups"})

EV_CLASSES: frozenset[str] = frozenset({"van_e [< 2t]", "van_e [> 2t]"})

EV_ELEC_KWH_PER_KM: dict[str, dict[str, float]] = {
    "van_e [< 2t]": {"urban": 0.25, "rural": 0.18, "highway": 0.22},
    "van_e [> 2t]": {"urban": 0.30, "rural": 0.22, "highway": 0.27},
}

EV_WTW_G_PER_KWH: float = 0.0


# ====================================================================
# Low-utilisation filter threshold
# ====================================================================

LOW_UTIL_THRESHOLD: float = 0.05


# ====================================================================
# Per-run configuration (frozen dataclass)
# ====================================================================

@dataclass(frozen=True, slots=True)
class RunConfig:
    """Immutable per-run settings – the single source of truth.

    One ``RunConfig`` object carries **everything** needed for a
    pipeline run: file paths, scenario toggles, EV model parameters,
    cost settings, and emission options.

    **Simulation files**

    * ``event_file`` – path to ``output_events.xml.gz``.
    * ``carrier_file`` – path to ``output_carriers.xml.gz``.
    * ``vehicle_types_file`` – path to
      ``output_carriersVehicleTypes.xml.gz``.

    **Reference data paths**

    * ``network_path`` – MATSim ``output_network.xml.gz``.
    * ``regionclusters_path`` – pickle with region-cluster polygons.
    * ``networkplus_path`` – pickle with link → raumtyp mapping.
    * ``plz_areas_csv`` – CSV with postal-code area polygons.

    **Emissions & trucks**

    * ``without_supply_trucks`` – exclude supply trucks from emission
      accounting.
    * ``t_long_override`` – override the *t_long* idle threshold (sec).
    * ``emissions_basis`` – ``"CO2"`` or ``"CO2e"``.
    * ``use_wtw`` – include well-to-wheel energy in CO₂e mode.
    * ``idle_pollutants_on`` – compute NOx / HC / CO idle pollutants.

    **Low-utilisation filter**

    * ``low_util_threshold`` – vehicles below this load factor are
      dropped (default 5 %).

    **Vehicle costs**

    * ``vehicle_time_cost_per_sec`` – labour/time cost [€ / s].
    * ``vehicle_size_mapping`` – maps size codes (``"m"``, ``"l"``,
      ``"truck"``, …) to emission-class strings used by the emission
      model.  Override to add custom vehicle types.

    **EV model**

    * ``ev_target`` – fleet-wide EV share (0 … 1).
    * ``fixed_ev_shares`` – per-provider EV quotas that are always
      applied before any flexible distribution.
    * ``min_ev_shares`` – minimum EV shares for *flexible* providers.
    * ``flexible_providers`` – provider names that receive dynamic EV
      allocation after fixed quotas are satisfied.

    Example
    -------
    >>> cfg = RunConfig(
    ...     event_file="sim/output_events.xml.gz",
    ...     carrier_file="sim/output_carriers.xml.gz",
    ...     vehicle_types_file="sim/output_carriersVehicleTypes.xml.gz",
    ...     network_path="sim/output_network.xml.gz",
    ...     regionclusters_path="input/regionclusters.pkl",
    ...     networkplus_path="input/networkplus.pkl",
    ...     plz_areas_csv="input/plz_areas.csv",
    ...     ev_target=0.30,
    ... )
    >>> ref = ReferenceData.from_config(cfg)
    >>> results = process_single_run(cfg, ref)
    """

    # -- simulation files --------------------------------------------
    event_file: str | None = None
    carrier_file: str | None = None
    vehicle_types_file: str | None = None

    # -- reference data paths ----------------------------------------
    network_path: str | None = None
    regionclusters_path: str | None = None
    networkplus_path: str | None = None
    plz_areas_csv: str | None = None

    # -- emissions & trucks ------------------------------------------
    without_supply_trucks: bool = False
    t_long_override: int | None = None
    emissions_basis: str = "CO2"
    use_wtw: bool = False
    idle_pollutants_on: bool = True

    # -- low-utilisation filter --------------------------------------
    low_util_threshold: float = LOW_UTIL_THRESHOLD

    # -- vehicle types / costs ---------------------------------------
    vehicle_time_cost_per_sec: float = VEHICLE_TIME_COST_PER_SEC
    vehicle_size_mapping: dict[str, str] | None = None

    # -- EV model ----------------------------------------------------
    ev_target: float = GLOBAL_EV_TARGET
    fixed_ev_shares: dict[str, float] | None = None
    min_ev_shares: dict[str, float] | None = None
    flexible_providers: frozenset[str] | None = None

    # -- convenience helpers -----------------------------------------

    def get_vehicle_size_mapping(self) -> dict[str, str]:
        """Return the effective vehicle-size → emission-class map."""
        return self.vehicle_size_mapping or VEHICLE_SIZE_MAPPING

    def get_fixed_ev_shares(self) -> dict[str, float]:
        """Return the effective fixed EV share table."""
        return self.fixed_ev_shares if self.fixed_ev_shares is not None else FIXED_EV_SHARES

    def get_min_ev_shares(self) -> dict[str, float]:
        """Return the effective minimum EV share table."""
        return self.min_ev_shares if self.min_ev_shares is not None else MIN_EV_SHARES

    def get_flexible_providers(self) -> frozenset[str]:
        """Return the effective set of flexible providers."""
        return self.flexible_providers if self.flexible_providers is not None else FLEXIBLE_PROVIDERS


DEFAULT_RUN_CONFIG: RunConfig = RunConfig()

# Legacy module-level flags for backward compatibility.
WITHOUT_SUPPLY_TRUCKS: bool = False
T_LONG_OVERRIDE: int | None = None
EMISSIONS_BASIS: str = "CO2"
USE_WTW: bool = False
IDLE_POLLUTANTS_ON: bool = True
