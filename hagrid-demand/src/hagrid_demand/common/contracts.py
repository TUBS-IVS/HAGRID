"""Small numerical and identifier contracts used by demand stages."""

from __future__ import annotations

import math

from dataclasses import dataclass

import pandas as pd


ATOL = 1e-8
RTOL = 1e-10


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
