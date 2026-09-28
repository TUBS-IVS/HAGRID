"""Versioned named random streams."""

from __future__ import annotations

import hashlib

import numpy as np

from .provenance import canonical_json


RNG_VERSION = 1


def named_rng(seed: int, **keys) -> np.random.Generator:
    """Create a PCG64 stream identified only by its stable named context."""
    if isinstance(seed, bool) or not isinstance(seed, (int, np.integer)):
        raise ValueError("seed must be an integer")
    if "seed" in keys or "rng_version" in keys:
        raise ValueError("seed and rng_version are reserved RNG keys")
    payload = canonical_json({"rng_version": RNG_VERSION, "seed": int(seed), **keys})
    entropy = np.frombuffer(hashlib.sha256(payload.encode("utf-8")).digest(), dtype="<u4")
    return np.random.Generator(np.random.PCG64(np.random.SeedSequence(entropy)))
