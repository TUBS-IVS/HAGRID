"""Out-of-home delivery: B2C parcels routed to parcel lockers, pickup shops and shared boxes.

Points come from an OpenStreetMap snapshot (plus synthetic shops where OSM misses partner shops). Every carrier
delivers a share of its B2C parcels out of home; the share follows a bounded-sigmoid trend over the years and
varies per day. Buildings closer to a suitable point and apartment buildings are more likely to use it; points
have a daily capacity and overflow goes to the next point or back to the home address.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Sequence

import geopandas as gpd
import numpy as np
import pandas as pd

_DATA = Path(__file__).with_name("data") / "out_of_home.json"
_BRANDS = (("packstation", "DHL"), ("dhl", "DHL"), ("deutsche post", "DHL"), ("post", "DHL"), ("amazon", "Amazon"),
           ("hermes", "Hermes"), ("dpd", "DPD"), ("gls", "GLS"), ("ups", "UPS"), ("fedex", "FedEx/TNT"))
_OPEN = ("myflexbox", "paketbox", "parcellock", "parcel lock")


def load_out_of_home_inputs() -> dict:
    """Return the documented default shares, trend, capacities and synthetic-shop targets."""
    return json.loads(_DATA.read_text(encoding="utf-8"))


def point_carriers(tags: dict, shared: Sequence[str]) -> tuple[str, tuple[str, ...]] | None:
    """Kind (locker, shared_locker, shop) and carriers of an OSM pickup point; ``None`` for anything else."""
    amenity = str(tags.get("amenity", ""))
    if amenity == "post_depot" or (amenity not in {"parcel_locker", "post_office"} and not tags.get("post_office")):
        return None
    label = " ".join(str(tags.get(key, "")) for key in ("brand", "operator", "post_office:brand", "name")).lower()
    if amenity == "parcel_locker":
        if any(token in label for token in _OPEN) or not label.strip():
            return "shared_locker", tuple(sorted(shared))
        carriers = sorted({carrier for token, carrier in _BRANDS if token in label})
        return ("locker", tuple(carriers)) if carriers else ("shared_locker", tuple(sorted(shared)))
    carriers = sorted({carrier for token, carrier in _BRANDS if token in label})
    return ("shop", tuple(carriers)) if carriers else None


def points_from_elements(elements: list[dict], region, crs: str, shared: Sequence[str]) -> gpd.GeoDataFrame:
    """Pickup points of Overpass *elements* inside *region* (WGS84 polygon), projected to *crs*."""
    rows = []
    for element in elements:
        lat, lon = (element.get("lat"), element.get("lon")) if "lat" in element else \
            (element.get("center", {}).get("lat"), element.get("center", {}).get("lon"))
        mapped = point_carriers(element.get("tags", {}), shared)
        if lat is None or mapped is None:
            continue
        tags = element.get("tags", {})
        rows.append({"point_id": f"osm:{element['type'][0]}{element['id']}", "kind": mapped[0], "carriers": "|".join(mapped[1]),
                     "brand": tags.get("brand") or tags.get("post_office:brand") or tags.get("operator") or tags.get("name") or "",
                     "synthetic": False, "lon": float(lon), "lat": float(lat)})
    frame = pd.DataFrame(rows, columns=["point_id", "kind", "carriers", "brand", "synthetic", "lon", "lat"])
    points = gpd.GeoDataFrame(frame.drop(columns=["lon", "lat"]), geometry=gpd.points_from_xy(frame.lon, frame.lat), crs=4326)
    points = points.loc[points.within(region)].drop_duplicates("point_id")
    return points.to_crs(crs).reset_index(drop=True)


def synthesize_shops(points: gpd.GeoDataFrame, candidates: gpd.GeoDataFrame, weights: np.ndarray, targets: dict[str, int],
                     rng: np.random.Generator) -> gpd.GeoDataFrame:
    """Add synthetic shops at candidate POIs until every carrier reaches its target number of shops.

    Candidates are drawn without replacement with probability proportional to *weights* (population nearby);
    each POI hosts at most one synthetic carrier.
    """
    weights = np.asarray(weights, dtype=float)
    available = np.ones(len(candidates), dtype=bool)
    added = []
    shops = points.loc[points.kind.eq("shop")]
    for carrier in sorted(targets):
        have = int(shops.carriers.str.split("|").apply(lambda values: carrier in values).sum())
        missing = max(0, int(targets[carrier]) - have)
        pool = np.flatnonzero(available & (weights > 0))
        if not missing or not len(pool):
            continue
        chosen = rng.choice(pool, size=min(missing, len(pool)), replace=False, p=weights[pool] / weights[pool].sum())
        available[chosen] = False
        added.append(gpd.GeoDataFrame({"point_id": [f"syn:{carrier}:{index}" for index in range(len(chosen))], "kind": "shop",
                                       "carriers": carrier, "brand": "synthetic", "synthetic": True},
                                      geometry=candidates.geometry.iloc[np.sort(chosen)].to_numpy(), crs=candidates.crs))
    if not added:
        return points.reset_index(drop=True)
    return gpd.GeoDataFrame(pd.concat([points, *[frame.to_crs(points.crs) for frame in added]], ignore_index=True), crs=points.crs)


def out_of_home_share(year: int, carrier: str, inputs: dict) -> float:
    """Share of a carrier's B2C parcels delivered out of home in *year* (year table first, else the sigmoid trend)."""
    table = inputs.get("shares_by_year", {}).get(str(carrier), {})
    if str(year) in table:
        return float(table[str(year)])
    level = float(inputs.get("shares_2025", {}).get(str(carrier), 0.))
    trend = inputs["trend"]
    growth, midpoint, reference = float(trend["growth"]), float(trend["midpoint"]), float(trend["reference_year"])
    ceiling = level * (1. + np.exp(-growth * (reference - midpoint)))
    return float(ceiling / (1. + np.exp(-growth * (float(year) - midpoint))))


def calibrate_propensity(weights: np.ndarray, base: np.ndarray, target: float, cap: float) -> np.ndarray:
    """Propensities ``min(cap, λ·base)`` whose *weights*-weighted mean equals *target* (capped if unreachable)."""
    weights, base = np.asarray(weights, dtype=float), np.asarray(base, dtype=float)
    reach = base > 0
    total = weights.sum()
    if target <= 0 or total <= 0 or not reach.any():
        return np.zeros_like(base)
    if (weights[reach] * cap).sum() / total <= target:
        return np.where(reach, cap, 0.)
    low, high = 0., cap / base[reach].min()
    for _ in range(200):
        middle = .5 * (low + high)
        if (weights * np.minimum(cap, middle * base)).sum() / total < target:
            low = middle
        else:
            high = middle
    return np.minimum(cap, high * base)


def _thin(amounts: np.ndarray, remove: int, rng: np.random.Generator) -> np.ndarray:
    """Remove exactly *remove* parcels from *amounts*, each parcel equally likely (multivariate hypergeometric)."""
    if remove <= 0:
        return np.zeros_like(amounts)
    return rng.multivariate_hypergeometric(amounts, int(remove))


def divert(counts: np.ndarray, propensity: np.ndarray, factors: np.ndarray, primary: np.ndarray, secondary: np.ndarray,
           capacity: np.ndarray, rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray, int]:
    """Route one day's B2C parcels (sites × carriers) to pickup points.

    Each parcel goes out of home with ``propensity × day factor``; parcels go to the site's primary point for the
    carrier, overflow above a point's capacity moves to the secondary point and whatever still does not fit stays
    at home. Returns home counts (sites × carriers), point counts (points × carriers) and the parcels sent home
    because of full points.
    """
    counts = np.asarray(counts, dtype=np.int64)
    carriers = counts.shape[1]
    probability = np.clip(np.asarray(propensity, dtype=float) * np.asarray(factors, dtype=float)[None, :], 0., 1.)
    probability[np.asarray(primary) < 0] = 0.
    out = rng.binomial(counts, probability)
    pairs = np.flatnonzero(out.ravel() > 0)
    amount = out.ravel()[pairs].astype(np.int64)
    first = np.asarray(primary).ravel()[pairs]
    second = np.asarray(secondary).ravel()[pairs]
    carrier = pairs % carriers
    capacity = np.asarray(capacity, dtype=np.int64)
    load = np.bincount(first, weights=amount, minlength=len(capacity)).astype(np.int64)
    moved = np.zeros_like(amount)
    for point in np.flatnonzero(load > capacity):
        members = np.flatnonzero(first == point)
        moved[members] = _thin(amount[members], load[point] - capacity[point], rng)
    kept = amount - moved
    remaining = capacity - np.bincount(first, weights=kept, minlength=len(capacity)).astype(np.int64)
    onward = moved.copy()
    onward[second < 0] = 0
    home_back = moved - onward
    arriving = np.bincount(np.where(second >= 0, second, 0), weights=onward, minlength=len(capacity)).astype(np.int64)
    rejected = np.zeros_like(onward)
    for point in np.flatnonzero(arriving > remaining):
        members = np.flatnonzero((second == point) & (onward > 0))
        rejected[members] = _thin(onward[members], arriving[point] - max(int(remaining[point]), 0), rng)
    placed = onward - rejected
    points = np.zeros((len(capacity), carriers), dtype=np.int64)
    np.add.at(points, (first, carrier), kept)
    np.add.at(points, (np.where(second >= 0, second, 0), carrier), placed)
    home = counts.copy().ravel()
    np.subtract.at(home, pairs, kept + placed)
    return home.reshape(counts.shape), points, int((home_back + rejected).sum())
