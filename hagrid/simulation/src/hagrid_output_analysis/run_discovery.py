"""
HAGRID Output Analysis – Run Discovery & Vehicle-Type Resolution
=================================================================

Utilities for auto-discovering MATSim simulation output files and
building a vehicle-size → emission-class mapping by matching cost
parameters to the known HAGRID base vehicle types.

The HAGRID pipeline defines base vehicle types with specific cost
structures (``fixedCostsPerDay``, ``costsPerMeter``).  Scenario
variants (e.g. reduced-capacity "kappa" runs) reuse the **same cost
structure** as their base type but with a different capacity.  By
matching costs we reliably identify the base type and thus the
correct emission class – regardless of the actual capacity.

Usage
-----
>>> from hagrid_output_analysis.run_discovery import (
...     resolve_run, list_available_runs,
...     build_vehicle_size_mapping_from_xml,
... )
>>> info = resolve_run(Path("hagrid-matsim-output/BASECASE_13052025_80_iter50_jsprit10"))
>>> veh_map = build_vehicle_size_mapping_from_xml(info["vehicle_types_file"])
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from hagrid_output_analysis.config import VEHICLE_SIZE_MAPPING
from hagrid_output_analysis.parsers import parse_vehicle_types

logger = logging.getLogger(__name__)


# ====================================================================
# Reference cost signatures from HAGRID_vehicleTypes2.0.xml
# ====================================================================

#: Cost fingerprints of the base vehicle types.
#: ``(fixedCostsPerDay, costsPerMeter, emission_class)``
BASE_COST_SIGNATURES: dict[str, tuple[float, float, str]] = {
    "m":                (171.78, 3.71826707441386e-4, "van [< 2t]"),
    "l":                (189.15, 3.86430173292559e-4, "van [> 2t]"),
    "supply_light_van": (189.15, 3.86430173292559e-4, "van [> 2t]"),
    "truck_light":      (550.63, 4.86430173292559e-4, "truck [< 10t]"),
    "truck":            (618.55, 5.55126e-4,          "truck [10-20 t] + trailer"),
}

#: Maximum normalised cost distance to accept a match (5 %).
_MATCH_THRESHOLD: float = 0.05


# ====================================================================
# Run file discovery
# ====================================================================

def resolve_run(run_dir: str | Path) -> dict[str, str | None]:
    """Find events / carriers / vehicle-types / network files in a run dir.

    Parameters
    ----------
    run_dir : path
        A MATSim output directory (e.g.
        ``hagrid-matsim-output/BASECASE_13052025_80_iter50_jsprit10``).

    Returns
    -------
    dict
        Keys: ``prefix``, ``event_file``, ``carrier_file``,
        ``vehicle_types_file``, ``network_file``.
        File values are absolute path strings; ``None`` if not found.

    Raises
    ------
    FileNotFoundError
        If the directory has no events or carriers file.
    """
    run_dir = Path(run_dir)
    names = {f.name: f for f in run_dir.iterdir() if f.is_file()}

    ev = [n for n in names if n.endswith("output_events.xml.gz")]
    ca = [n for n in names if n.endswith("output_carriers.xml.gz")]
    vt = [n for n in names if n.endswith("output_carriersVehicleTypes.xml.gz")]
    nw = [n for n in names if n.endswith("output_network.xml.gz")]

    if not ev or not ca:
        raise FileNotFoundError(f"No events/carriers found in {run_dir}")

    prefix = ev[0].replace(".output_events.xml.gz", "")
    return {
        "prefix": prefix,
        "event_file": str(names[ev[0]]),
        "carrier_file": str(names[ca[0]]),
        "vehicle_types_file": str(names[vt[0]]) if vt else None,
        "network_file": str(names[nw[0]]) if nw else None,
    }


def list_available_runs(base_dir: str | Path) -> list[dict[str, Any]]:
    """List all valid simulation runs under *base_dir*.

    A directory counts as a valid run if it contains at least an
    ``output_events.xml.gz`` and an ``output_carriers.xml.gz``.

    Parameters
    ----------
    base_dir : path
        Root directory to scan (typically ``hagrid-matsim-output/``).

    Returns
    -------
    list of dict
        Each dict has ``dir_name`` (str) and ``path`` (Path).
    """
    base_dir = Path(base_dir)
    runs: list[dict[str, Any]] = []
    if not base_dir.is_dir():
        return runs

    for d in sorted(base_dir.iterdir()):
        if not d.is_dir() or d.name.startswith("."):
            continue
        has_events = any(
            f.name.endswith("output_events.xml.gz")
            for f in d.iterdir() if f.is_file()
        )
        has_carriers = any(
            f.name.endswith("output_carriers.xml.gz")
            for f in d.iterdir() if f.is_file()
        )
        if has_events and has_carriers:
            runs.append({"dir_name": d.name, "path": d})

    return runs


# ====================================================================
# Vehicle-size → emission-class mapping via cost matching
# ====================================================================

def _match_base_type(
    fixed_cost: float,
    cost_per_meter: float,
) -> tuple[str, str] | None:
    """Find the closest base vehicle type by cost distance.

    Returns ``(base_size_code, emission_class)`` or ``None`` if no
    match is within the threshold.
    """
    best: tuple[str, str] | None = None
    best_dist = float("inf")

    for base_code, (ref_fixed, ref_cpm, em_class) in BASE_COST_SIGNATURES.items():
        d = (
            abs(fixed_cost - ref_fixed) / max(ref_fixed, 1.0)
            + abs(cost_per_meter - ref_cpm) / max(ref_cpm, 1e-9)
        )
        if d < best_dist:
            best_dist = d
            best = (base_code, em_class)

    if best is not None and best_dist < _MATCH_THRESHOLD:
        return best
    return None


def build_vehicle_size_mapping_from_xml(
    vt_path: str | Path,
) -> dict[str, str]:
    """Build a vehicle-size → emission-class mapping from a vehicle types XML.

    Reads ``output_carriersVehicleTypes.xml.gz``, extracts each type's
    ``(fixedCostsPerDay, costsPerMeter)`` and matches it to the closest
    HAGRID base vehicle type.  This determines the emission class:

    =================  ==============  ==============================
    Base type costs    Size code       Emission class
    =================  ==============  ==============================
    size_m             ``m``           ``van [< 2t]``
    size_l             ``l``           ``van [> 2t]``
    truck_light        ``truck_light`` ``truck [< 10t]``
    truck_heavy        ``truck``       ``truck [10-20 t] + trailer``
    =================  ==============  ==============================

    The returned mapping starts with the default
    :data:`~.config.VEHICLE_SIZE_MAPPING` and adds entries for any
    non-standard size codes found in the output (e.g. ``"80"`` from a
    vehicle type ID like ``ct_cep_80_l``).

    Parameters
    ----------
    vt_path : path
        Path to ``output_carriersVehicleTypes.xml.gz``.

    Returns
    -------
    dict
        Maps size codes (``"m"``, ``"l"``, ``"80"``, …) to emission-
        class strings.
    """
    vtypes = parse_vehicle_types(str(vt_path))

    # Start with the known defaults
    mapping = dict(VEHICLE_SIZE_MAPPING)

    for tid, info in vtypes.items():
        tid_lower = tid.lower()

        # Skip bikes – no combustion emissions
        if "bike" in tid_lower:
            continue

        fixed = info["fixed_cost"]
        cpm = info["cost_per_km"] / 1000.0  # parse_vehicle_types returns €/km

        match = _match_base_type(fixed, cpm)
        if match is None:
            logger.warning(
                "Vehicle type '%s' (fixed=%.2f, cpm=%.6f) did not match "
                "any base type – skipping",
                tid, fixed, cpm,
            )
            continue

        _base_code, em_class = match

        # Register numeric size codes that appear in the type ID
        # e.g. "ct_cep_80_l" → register "80" → same class as "l"
        for part in tid_lower.replace("ct_", "").split("_"):
            if part.isdigit() and part not in mapping:
                mapping[part] = em_class
                logger.debug("Mapped size code '%s' → '%s' (from %s)", part, em_class, tid)

        # Register full suffix after known prefixes
        for prefix in ("ct_cep_", "ct_supply_", "ct_egrocery_"):
            if tid_lower.startswith(prefix):
                suffix = tid_lower[len(prefix):]
                if suffix and suffix not in mapping:
                    mapping[suffix] = em_class
                    logger.debug("Mapped suffix '%s' → '%s' (from %s)", suffix, em_class, tid)

    return mapping
