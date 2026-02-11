"""
HAGRID Output Analysis – Utility Helpers
=========================================

Small, dependency-light helpers used across the package.

All provider-extraction logic is consolidated here as the **single
canonical implementation** to avoid the previous three-way duplication.
"""

from __future__ import annotations

from hagrid_output_analysis.config import (
    AREA_TYPE_GROUPS,
    AREA_TYPE_LABELS,
    GROUP_BY_NAME,
    PLZ_LIST,
    VEHICLE_SIZE_MAPPING,
)

# ====================================================================
# Known logistics providers
# ====================================================================

_KNOWN_PROVIDERS: tuple[str, ...] = (
    "dhl", "amazon", "ups", "gls", "dpd", "fedex", "hermes",
)


# ====================================================================
# Provider extraction (single canonical implementation)
# ====================================================================

def extract_provider(identifier: str) -> str:
    """Derive the logistics provider from a carrier/vehicle ID.

    Tries two strategies:

    1. Check whether any known provider name is a substring.
    2. Fall back to splitting on ``"_"`` and taking the second token.

    Returns ``"unknown"`` when detection fails.
    """
    low = str(identifier).lower()
    for name in _KNOWN_PROVIDERS:
        if name in low:
            return name
    if "wl" in low:
        return "white-label"
    # Fallback: ``freight_<provider>_...``
    parts = low.split("_")
    if len(parts) >= 2:
        return parts[1]
    return "unknown"


# ====================================================================
# Runtime formatting
# ====================================================================

def format_runtime(duration: float) -> str:
    """Human-friendly string for a duration in seconds.

    Examples
    --------
    >>> format_runtime(0.123)
    '123.00 ms'
    >>> format_runtime(42.5)
    '42.50 s'
    >>> format_runtime(125.3)
    '2 min 5.30 s'
    """
    if duration < 1:
        return f"{duration * 1000:.2f} ms"
    if duration < 60:
        return f"{duration:.2f} s"
    minutes = int(duration // 60)
    seconds = duration % 60
    return f"{minutes} min {seconds:.2f} s"


# ====================================================================
# Area-type helpers
# ====================================================================

def area_type_name(raumtyp: int | str) -> str:
    """Full English label for a numeric *raumtyp* (1-8)."""
    try:
        return AREA_TYPE_LABELS.get(int(str(raumtyp).strip()), "unknown")
    except (TypeError, ValueError):
        return "unknown"


def area_type_group(value: int | str) -> str:
    """Aggregated group (Urban / Suburban / Rural)."""
    try:
        rid = int(str(value).strip())
        if rid in AREA_TYPE_GROUPS:
            return AREA_TYPE_GROUPS[rid]
    except (TypeError, ValueError):
        pass
    return GROUP_BY_NAME.get(str(value).strip(), "unknown")


# ====================================================================
# Vehicle-ID parsing helpers
# ====================================================================

def extract_veh_size(vehicle_id: str) -> str:
    """Extract the vehicle-size code (``m``, ``l``, ...) from the ID."""
    try:
        parts = vehicle_id.split("size_")
        if len(parts) > 1:
            return parts[1].split("_")[0]
    except (AttributeError, IndexError):
        pass
    return "unknown"


def veh_size_to_class(size_code: str) -> str | None:
    """Map a size code to the emission-class key, or ``None``."""
    return VEHICLE_SIZE_MAPPING.get(str(size_code))


def extract_main_area_type(raumsplit: dict[int, int]) -> int:
    """Return the area-type ID with the highest service count."""
    if not raumsplit:
        return 0
    return max(raumsplit, key=raumsplit.get)  # type: ignore[arg-type]


def is_in_hannover(vehicle_id: str) -> bool:
    """Check whether any Hannover PLZ appears in *vehicle_id*."""
    return any(plz in vehicle_id for plz in PLZ_LIST)


# ====================================================================
# Vehicle classification from ID
# ====================================================================

def classify_vehicle(vehicle_id: str) -> str:
    """Return ``'van'``, ``'truck'``, ``'bike'``, or ``'unknown'``."""
    vid = str(vehicle_id)
    if "_Supply_Vehicle_" in vid or "_veh_supply_" in vid:
        return "truck"
    if any(kw in vid for kw in ("_CEP_Vehicle_", "_veh_cep_", "_egrocery_van_")):
        return "van"
    if "_cargoBike_" in vid or "_cargobike_" in vid:
        return "bike"
    return "unknown"


def is_supply_vehicle(vehicle_id: str) -> bool:
    """Check whether *vehicle_id* belongs to a supply vehicle."""
    return "_supply_" in str(vehicle_id).lower()
