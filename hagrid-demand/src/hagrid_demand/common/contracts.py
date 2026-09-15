"""Small numerical and identifier contracts used by demand stages."""

from __future__ import annotations

import math


ATOL = 1e-8
RTOL = 1e-10


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
