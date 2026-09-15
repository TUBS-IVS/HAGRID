"""Small numerical and identifier contracts used by demand stages."""

from __future__ import annotations

import math

from dataclasses import dataclass

import pandas as pd

from hagrid_demand.common.provenance import canonical_digest


ATOL = 1e-8
RTOL = 1e-10


def verified_scope_id(postal_codes) -> str:
    """Hash the normalized verified postal support consumed by a reference run."""
    try:
        values = [str(value).strip() for value in postal_codes]
    except TypeError as exc:
        raise ValueError("verified postal support must be iterable") from exc
    if not values or any(not value for value in values):
        raise ValueError("verified postal support must contain non-empty postal codes")
    return canonical_digest({"verified_postal_support": sorted(set(values))})


@dataclass(frozen=True)
class AnnualProjection:
    """Canonical annual handoff for the daily-demand stages."""

    sites: pd.DataFrame
    profiles: pd.DataFrame
    postal: pd.DataFrame
    checks: dict

    @property
    def hashes(self) -> dict[str, str]:
        return self.checks["hashes"]

    @property
    def carrier_profiles(self) -> pd.DataFrame:
        return self.profiles

    @property
    def artifact_hashes(self) -> dict[str, str]:
        return self.hashes

    @property
    def site_hash(self) -> str:
        return self.hashes["sites"]

    @property
    def carrier_profile_hash(self) -> str:
        return self.hashes["profiles"]

    @property
    def postal_hash(self) -> str:
        return self.hashes["postal"]


@dataclass(frozen=True)
class SpatialPlan:
    """Verified spatial allocation contract consumed by daily generation.

    Dirichlet plans deliberately contain no calibration output.  The target and
    parameter fingerprints still prevent a plan made for another annual
    distribution from being reused accidentally.
    """

    mode: str
    target_fingerprints: dict[str, str]
    parameter_fingerprint: str
    status: str


def assert_balance(actual, expected) -> None:
    """Raise when an expected quantity violates the documented balance tolerance."""
    actual = float(actual)
    expected = float(expected)
    if not (math.isfinite(actual) and math.isfinite(expected)):
        raise ValueError("Balance values must be finite")
    error = abs(actual - expected)
    tolerance = ATOL + RTOL * abs(expected)
    if error > tolerance:
        raise ValueError(
            f"Balance failed: actual={actual!r}, expected={expected!r}, "
            f"absolute_error={error!r}, tolerance={tolerance!r}"
        )
