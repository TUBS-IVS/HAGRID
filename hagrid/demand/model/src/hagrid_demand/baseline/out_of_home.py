"""Out-of-home delivery: B2C parcels routed to parcel lockers, pickup shops and shared boxes.

Points come from an OpenStreetMap snapshot (plus synthetic shops where OSM misses partner shops). Every carrier
delivers a share of its B2C parcels out of home; the share follows a bounded-sigmoid trend over the years and
varies per day. Buildings closer to a suitable point and apartment buildings are more likely to use it; points
have a daily capacity and overflow goes to the next point or back to the home address.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

import geopandas as gpd
import numpy as np
import pandas as pd

_DATA = Path(__file__).with_name("data") / "out_of_home.json"
_BRANDS = (("packstation", "DHL"), ("dhl", "DHL"), ("deutsche post", "DHL"), ("post", "DHL"), ("amazon", "Amazon"),
           ("hermes", "Hermes"), ("dpd", "DPD"), ("gls", "GLS"), ("ups", "UPS"), ("fedex", "FedEx/TNT"))
_OPEN = ("myflexbox", "paketbox", "parcellock", "parcel lock")
# Open lockers that also take DHL parcels: DeinFach (DHL subsidiary) and carrier-neutral inboxx boxes.
_OPEN_ALL = ("dein fach", "deinfach", "inboxx")
_OVERPASS = ("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
             "https://overpass.private.coffee/api/interpreter")


def load_out_of_home_inputs() -> dict:
    """Return the documented default shares, trend, capacities and synthetic-shop targets."""
    return json.loads(_DATA.read_text(encoding="utf-8"))


def point_carriers(tags: dict, shared: Sequence[str]) -> tuple[str, tuple[str, ...]] | None:
    """Kind (locker, shared_locker, shop) and carriers of an OSM pickup point; ``None`` for anything else."""
    amenity = str(tags.get("amenity", ""))
    label = " ".join(str(tags.get(key, "")) for key in ("brand", "operator", "post_office:brand", "name")).lower()
    if tags.get("shop") == "outpost":
        # Pickup outposts: Amazon Hub Lockers are sometimes tagged this way; retailer outposts are not parcel carriers.
        carriers = sorted({carrier for token, carrier in _BRANDS if token in label})
        return (("locker" if "locker" in label else "shop"), tuple(carriers)) if carriers else None
    if amenity == "post_depot" or (amenity not in {"parcel_locker", "post_office"} and not tags.get("post_office")):
        return None
    if amenity == "parcel_locker":
        if any(token in label for token in _OPEN_ALL):
            return "shared_locker", tuple(sorted(set(shared) | {"DHL"}))
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
        capacity = pd.to_numeric(str(tags.get("capacity", "")).split(";")[0], errors="coerce")
        rows.append({"point_id": f"osm:{element['type'][0]}{element['id']}", "kind": mapped[0], "carriers": "|".join(mapped[1]),
                     "brand": tags.get("brand") or tags.get("post_office:brand") or tags.get("operator") or tags.get("name") or "",
                     "synthetic": False, "compartments": float(capacity) if capacity == capacity and capacity > 0 else np.nan,
                     "lon": float(lon), "lat": float(lat)})
    frame = pd.DataFrame(rows, columns=["point_id", "kind", "carriers", "brand", "synthetic", "compartments", "lon", "lat"])
    points = gpd.GeoDataFrame(frame.drop(columns=["lon", "lat"]), geometry=gpd.points_from_xy(frame.lon, frame.lat), crs=4326)
    points = points.loc[points.within(region)].drop_duplicates("point_id")
    return points.to_crs(crs).reset_index(drop=True)


def synthesize_shops(points: gpd.GeoDataFrame, candidates: gpd.GeoDataFrame, weights: np.ndarray, targets: dict[str, int],
                     rng: np.random.Generator, *, kind: str = "shop", shared_carriers: Sequence[str] | None = None) -> gpd.GeoDataFrame:
    """Add synthetic points of *kind* at candidate POIs until every carrier reaches its target count of that kind.

    Candidates are drawn without replacement with probability proportional to *weights* (population nearby);
    each POI hosts at most one synthetic point per call. The target key ``shared`` adds boxes open to all
    *shared_carriers* (kind ``shared_locker``).
    """
    weights = np.asarray(weights, dtype=float)
    available = np.ones(len(candidates), dtype=bool)
    added = []
    existing = points.loc[points.kind.eq(kind)]
    for carrier in sorted(targets):
        served = "|".join(sorted(shared_carriers or ())) if carrier == "shared" else carrier
        have = int(existing.carriers.eq(served).sum()) if carrier == "shared" else \
            int(existing.carriers.str.split("|").apply(lambda values: carrier in values).sum())
        missing = max(0, int(targets[carrier]) - have)
        pool = np.flatnonzero(available & (weights > 0))
        if not missing or not len(pool):
            continue
        chosen = rng.choice(pool, size=min(missing, len(pool)), replace=False, p=weights[pool] / weights[pool].sum())
        available[chosen] = False
        added.append(gpd.GeoDataFrame({"point_id": [f"syn:{kind}:{carrier}:{index}" for index in range(len(chosen))], "kind": kind,
                                       "carriers": served, "brand": "synthetic", "synthetic": True, "compartments": np.nan},
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
    at home. Returns home counts (sites × carriers), point counts (points × carriers), the parcels sent home
    because of full points and the parcels turned away per point.
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
    rejected_by_point = (np.bincount(first, weights=moved, minlength=len(capacity))
                         + np.bincount(np.where(second >= 0, second, 0), weights=rejected, minlength=len(capacity))).astype(np.int64)
    return home.reshape(counts.shape), points, int((home_back + rejected).sum()), rejected_by_point


def resolve_out_of_home(cfg: dict | None) -> dict | None:
    """Defaults from ``data/out_of_home.json`` merged with the config block; ``None`` when disabled."""
    if cfg is None or cfg is False:
        return None
    if not isinstance(cfg, dict):
        raise ValueError("out_of_home must be a mapping")
    if cfg.get("enabled", True) is False:
        return None
    inputs = load_out_of_home_inputs()
    for key, value in cfg.items():
        if key in {"shares_2025", "shares_by_year", "compartments", "pickup_profile", "pickup_profile_by_carrier", "pickup_context_factor",
                   "national_shops", "synthetic_lockers", "synthetic_counters"} and isinstance(value, dict):
            inputs[key] = {**inputs.get(key, {}), **value}
        elif key not in {"enabled", "network_growth"}:
            inputs[key] = value
    inputs["network_growth"] = resolve_network_growth(inputs.get("network_growth"), cfg.get("network_growth"))
    for carrier, share in inputs["shares_2025"].items():
        if not 0 <= float(share) < 1:
            raise ValueError(f"out_of_home.shares_2025.{carrier} must be a share in [0, 1)")
    inputs.setdefault("synthetic_shops", True)
    kinds = inputs.setdefault("kinds", ["locker", "shared_locker", "counter"])
    if not isinstance(kinds, list) or not set(kinds) <= {"locker", "shared_locker", "counter", "shop"}:
        raise ValueError("out_of_home.kinds must list locker, shared_locker, counter and/or shop")
    return inputs


_GROWTH_KEYS = {"enabled", "reference_year", "elasticity", "resize_existing", "demand_radius_m", "gap_scale_m", "min_spacing_m",
                "off_preference_weight", "candidate_types", "kind_preferences"}


def _growth_number(block: dict, key: str, *, positive: bool, upper: float | None = None) -> float:
    value = block[key]
    valid = not isinstance(value, bool) and isinstance(value, (int, float)) and np.isfinite(value)
    if not valid or (value <= 0 if positive else value < 0) or (upper is not None and value > upper):
        bound = "a positive number" if positive else "a nonnegative number"
        raise ValueError(f"out_of_home.network_growth.{key} must be {bound}" + (f" of at most {upper:g}" if upper is not None else ""))
    return float(value)


def _poi_tags(value, label: str) -> dict:
    """Validate a mapping of OSM tag columns to value lists, e.g. ``{"shop": ["kiosk"], "amenity": ["fuel"]}``."""
    if not isinstance(value, dict) or not all(isinstance(column, str) and isinstance(values, list)
                                              and all(isinstance(item, str) for item in values) for column, values in value.items()):
        raise ValueError(f"out_of_home.network_growth.{label} must map OSM tag columns to lists of values")
    return {column: list(values) for column, values in value.items()}


def resolve_network_growth(defaults: dict | None, block=None) -> dict:
    """Network-growth settings: the packaged defaults merged with the run's ``out_of_home.network_growth`` block
    (``candidate_types`` and ``kind_preferences`` one level deep), validated."""
    merged = json.loads(json.dumps(defaults or {}))
    if block is not None:
        if not isinstance(block, dict):
            raise ValueError("out_of_home.network_growth must be a mapping")
        if unknown := sorted(set(block) - _GROWTH_KEYS):
            raise ValueError(f"out_of_home.network_growth has unknown keys: {unknown}")
        for key, value in json.loads(json.dumps(block)).items():
            nested = key in {"candidate_types", "kind_preferences"} and isinstance(value, dict) and isinstance(merged.get(key), dict)
            merged[key] = {**merged[key], **value} if nested else value
    if missing := sorted(_GROWTH_KEYS - set(merged)):
        raise ValueError(f"out_of_home.network_growth lacks {missing}")
    for key in ("enabled", "resize_existing"):
        if not isinstance(merged[key], bool):
            raise ValueError(f"out_of_home.network_growth.{key} must be true or false")
    if type(merged["reference_year"]) is not int or merged["reference_year"] < 2021:
        raise ValueError("out_of_home.network_growth.reference_year must be an integer year from 2021")
    merged["elasticity"] = _growth_number(merged, "elasticity", positive=False)
    for key in ("demand_radius_m", "gap_scale_m", "min_spacing_m"):
        merged[key] = _growth_number(merged, key, positive=True)
    merged["off_preference_weight"] = _growth_number(merged, "off_preference_weight", positive=False, upper=1.)
    merged["candidate_types"] = _poi_tags(merged["candidate_types"], "candidate_types")
    preferences = merged["kind_preferences"]
    if not isinstance(preferences, dict) or not set(preferences) <= {"locker", "shared_locker", "counter", "shop"}:
        raise ValueError("out_of_home.network_growth.kind_preferences must map locker, shared_locker, counter and/or shop to POI tags")
    merged["kind_preferences"] = {kind: _poi_tags(tags, f"kind_preferences.{kind}") for kind, tags in preferences.items()}
    return merged


def load_points(path: Path, crs) -> gpd.GeoDataFrame:
    """Pickup points written by ``baseline osm-parcel-points`` (point_id, kind, carriers, brand, synthetic, geometry)."""
    points = gpd.read_parquet(path)
    missing = {"point_id", "kind", "carriers"} - set(points.columns)
    if missing:
        raise ValueError(f"parcel points file lacks columns: {sorted(missing)}")
    points = points.to_crs(crs) if points.crs is not None else points.set_crs(crs)
    if "synthetic" not in points:
        points["synthetic"] = False
    if "compartments" not in points:
        points["compartments"] = np.nan
    if "context" not in points:
        points["context"] = "other"
    return points.reset_index(drop=True)


def synthetic_candidates(pois: gpd.GeoDataFrame, types: dict) -> gpd.GeoDataFrame:
    """Retail POIs that can host a pickup shop (kiosks, supermarkets, fuel stations, ...)."""
    mask = np.zeros(len(pois), dtype=bool)
    for column, values in types.items():
        if column in pois:
            mask |= pois[column].isin(values).to_numpy()
    return pois.loc[mask].reset_index(drop=True)


def population_near(points: gpd.GeoDataFrame, units: gpd.GeoDataFrame, radius: float) -> np.ndarray:
    """Residents within *radius* of each point."""
    from scipy.spatial import cKDTree

    if points.empty or units.empty:
        return np.zeros(len(points))
    centroids = units.geometry.centroid
    tree = cKDTree(np.column_stack([centroids.x, centroids.y]))
    population = units.population.fillna(0.).to_numpy(float)
    neighbours = tree.query_ball_point(np.column_stack([points.geometry.x, points.geometry.y]), r=radius)
    return np.asarray([population[index].sum() for index in neighbours], dtype=float)


def shop_targets(inputs: dict, region_population: float) -> dict[str, int]:
    """Expected number of pickup shops per carrier from the national networks scaled by population."""
    share = region_population / float(inputs["germany_population"])
    return {carrier: int(round(float(count) * share)) for carrier, count in inputs["national_shops"].items()}


def context_profile(base: np.ndarray, factor: float) -> np.ndarray:
    """Pickup profile of a station context: the first-day share stays, the mean class number is scaled by *factor*
    (Hovi et al. 2023: mean pickup time 30.5 h at grocery stores, 34.5 h at transport hubs, 31.6 h overall) by
    moving mass between the second and the last class."""
    base = np.asarray(base, dtype=float)
    base = base / base.sum()
    classes = np.arange(1, len(base) + 1)
    if len(base) < 3 or abs(float(factor) - 1.) < 1e-12:
        return base.copy()
    target = float((base * classes).sum()) * float(factor)
    tail = base[1] + base[-1]
    middle = float((base[2:-1] * classes[2:-1]).sum())
    last = (target - base[0] - middle - 2. * tail) / (len(base) - 2)
    last = float(np.clip(last, 0., tail))
    profile = base.copy()
    profile[1], profile[-1] = tail - last, last
    return profile


def point_context(points: gpd.GeoDataFrame, transit: gpd.GeoDataFrame, retail: gpd.GeoDataFrame, *, transit_m: float,
                  retail_m: float) -> np.ndarray:
    """Station context from the OSM surroundings: ``transit`` near a station or bus terminal, ``retail`` at a shop,
    else ``other``."""
    from scipy.spatial import cKDTree

    xy = np.column_stack([points.geometry.x, points.geometry.y])
    context = np.full(len(points), "other", dtype=object)
    for label, features, radius in (("retail", retail, retail_m), ("transit", transit, transit_m)):
        if features is None or features.empty:
            continue
        distance, _ = cKDTree(np.column_stack([features.geometry.x, features.geometry.y])).query(xy)
        context[distance <= float(radius)] = label
    return context


class LockerQueue:
    """Compartments of pickup points occupied until the recipients collect their parcels.

    ``pending[p, k]`` parcels in point *p* will be collected *k* days after the current day; a parcel collected
    on day *t* frees its compartment for the deliveries of day *t + 1*.
    """

    def __init__(self, compartments: np.ndarray, profiles: np.ndarray):
        self.compartments = np.asarray(compartments, dtype=np.int64)
        self.profiles = np.asarray(profiles, dtype=float)
        self.pending = np.zeros((len(self.compartments), self.profiles.shape[1]), dtype=np.int64)
        self.day = 0

    def _advance(self, day: int) -> None:
        shift = int(day) - self.day
        if shift > 0:
            self.pending = np.hstack([self.pending[:, shift:], np.zeros((len(self.compartments), min(shift, self.pending.shape[1])), dtype=np.int64)])
            self.day = int(day)

    def free(self, day: int) -> np.ndarray:
        """Free compartments at delivery time of *day* (parcels still inside, including today's pickups, block)."""
        self._advance(day)
        return np.maximum(self.compartments - self.pending.sum(axis=1), 0)

    def store(self, counts: np.ndarray, rng: np.random.Generator) -> None:
        """Put today's delivered parcels into the compartments and draw their pickup days."""
        counts = np.asarray(counts, dtype=np.int64)
        for point in np.flatnonzero(counts > 0):
            self.pending[point] += rng.multinomial(int(counts[point]), self.profiles[point])

    def occupancy(self) -> np.ndarray:
        """Parcels inside after today's pickups (occupied compartments tomorrow morning)."""
        return self.pending[:, 1:].sum(axis=1) if self.pending.shape[1] > 1 else np.zeros(len(self.compartments), dtype=np.int64)

    def carry_from(self, previous: "LockerQueue", gap_days: int, prefix: int) -> None:
        """Take over the parcels still inside the first *prefix* points of *previous* (the stations that already existed),
        *gap_days* after its last day, so the compartments do not empty at the turn of the year."""
        previous._advance(previous.day + int(gap_days))
        width = min(self.pending.shape[1], previous.pending.shape[1])
        prefix = min(int(prefix), len(self.compartments), len(previous.compartments))
        self.pending[:prefix, :width] = previous.pending[:prefix, :width]


@dataclass
class OutOfHomePlan:
    """One year's routing of a segment's sites (in SegmentDay order) to pickup points."""

    points: gpd.GeoDataFrame
    carriers: list
    propensity: np.ndarray
    primary: np.ndarray
    secondary: np.ndarray
    queue: LockerQueue
    factors: np.ndarray
    extended_sites: pd.DataFrame
    shares: dict


def build_plan(sites: pd.DataFrame, carriers: Sequence[str], site_xy: np.ndarray, population: np.ndarray,
               points: gpd.GeoDataFrame, inputs: dict, year: int, days: int, factor_rng,
               carrier_shares: dict | None = None, previous_compartments: np.ndarray | None = None) -> OutOfHomePlan:
    """Primary/secondary point, calibrated propensity and daily factors for every site and carrier.

    Each site picks its primary point among the ``choice_k`` nearest lockers/boxes/counters within reach
    (probability ~ exp(-distance / ``choice_decay_m``)); the nearest other one is its fallback. Points without
    an OSM capacity tag are sized to the daily demand they attract (``compartments_by_demand``); with
    *previous_compartments* (one per point, 0 = not sized before) they never get smaller than last year.
    """
    from scipy.spatial import cKDTree

    from .shipping_draws import ar1_lognormal

    count, width = len(sites), len(carriers)
    point_xy = np.column_stack([points.geometry.x, points.geometry.y])
    served = points.carriers.astype(str).str.split("|")
    kinds = points.kind.astype(str).to_numpy()
    apartment = np.where(np.asarray(population, dtype=float) >= float(inputs["apartment_min_population"]),
                         float(inputs["apartment_factor"]), float(inputs["house_factor"]))
    weights = sites.annual_expected.to_numpy(float)
    valid = np.isfinite(site_xy).all(axis=1)
    propensity = np.zeros((count, width))
    primary = -np.ones((count, width), dtype=np.int64)
    secondary = -np.ones((count, width), dtype=np.int64)
    shares = {}
    for column, carrier in enumerate(carriers):
        shares[carrier] = out_of_home_share(year, carrier, inputs)
        candidates = np.flatnonzero(served.apply(lambda values, carrier=carrier: carrier in values).to_numpy())
        if not len(candidates) or shares[carrier] <= 0 or not valid.any():
            continue
        origin = np.where(valid[:, None], site_xy, 0.)
        reach = float(inputs["reach_m"])
        if inputs.get("prefer_lockers", True):
            # Recipients who choose out-of-home delivery pick a locker, box or staffed counter among the nearest
            # ones (closer = likelier); pickup shops take the rest and the overflow.
            chosen_kinds = ["locker", "shared_locker", "counter"]
            lockers = candidates[np.isin(kinds[candidates], chosen_kinds)]
            shops = candidates[~np.isin(kinds[candidates], chosen_kinds)]
            k = max(1, int(inputs.get("choice_k", 1)))
            locker_d, locker_i = _nearest(point_xy, lockers, origin, k)
            shop_d, shop_i = _nearest(point_xy, shops, origin, 2)
            pick = _choose(locker_d, reach, float(inputs.get("choice_decay_m", 300.)), factor_rng(f"choice:{carrier}"))
            rows_ = np.arange(count)
            chosen_d, chosen_i = locker_d[rows_, pick], locker_i[rows_, pick]
            use_locker = chosen_d <= reach
            # fallback: the nearest other locker within reach, else the nearest shop
            if locker_d.shape[1] > 1:
                other = np.where(pick == 0, 1, 0)
                other_d, other_i = locker_d[rows_, other], locker_i[rows_, other]
            else:
                other_d, other_i = np.full(count, np.inf), np.full(count, -1)
            other_ok = (other_d <= reach) & (other_i >= 0)
            first_d = np.where(use_locker, chosen_d, shop_d[:, 0])
            first_i = np.where(use_locker, chosen_i, shop_i[:, 0])
            shop_near = shop_d[:, 0] <= reach
            second_d = np.where(use_locker, np.where(other_ok, other_d, np.where(shop_near, shop_d[:, 0], np.inf)), shop_d[:, 1])
            second_i = np.where(use_locker, np.where(other_ok, other_i, np.where(shop_near, shop_i[:, 0], -1)), shop_i[:, 1])
        else:
            both_d, both_i = _nearest(point_xy, candidates, origin, 2)
            first_d, first_i, second_d, second_i = both_d[:, 0], both_i[:, 0], both_d[:, 1], both_i[:, 1]
        first = valid & (first_d <= reach) & (first_i >= 0)
        primary[first, column] = first_i[first]
        second = valid & (second_d <= reach) & (second_i >= 0)
        secondary[second, column] = second_i[second]
        base = np.where(first, apartment * np.exp(-np.where(first, first_d, 0.) / float(inputs["decay_m"])), 0.)
        propensity[:, column] = calibrate_propensity(weights, base, shares[carrier], float(inputs["max_propensity"]))
    # expected daily demand per point (delivery days) for sizing stations without an OSM capacity tag
    delivery_days = float(inputs.get("delivery_days_per_year", 303))
    shares_of = {carrier: float((carrier_shares or {}).get(carrier, 1. / max(1, width))) for carrier in carriers}
    demand = np.zeros(len(points))
    for column, carrier in enumerate(carriers):
        target_points = primary[:, column]
        hit = target_points >= 0
        np.add.at(demand, target_points[hit], propensity[hit, column] * weights[hit] * shares_of[carrier] / delivery_days)
    compartments = size_compartments(points, demand, inputs["compartments"], inputs.get("compartments_by_demand"))
    if previous_compartments is not None:
        compartments = keep_compartments(points, compartments, previous_compartments, inputs.get("compartments_by_demand"))
    queue = locker_queue(points, inputs, factor_rng("pickup-profiles"), compartments)
    factors = (np.column_stack([ar1_lognormal(days, float(inputs["day_log_sd"]), float(inputs["day_ar"]), factor_rng(carrier))
                                for carrier in carriers]) if width else np.ones((days, 0)))
    pseudo = pd.DataFrame({"year": year, "site_id": "ooh:" + points.point_id.astype(str), "plz": points.plz.astype(str),
                           "segment": "private", "annual_expected": 0., "allocation_status": "out_of_home"})
    extended = pd.concat([sites.reset_index(drop=True), pseudo.reindex(columns=sites.columns)], ignore_index=True)
    return OutOfHomePlan(points, list(carriers), propensity, primary, secondary, queue, factors, extended, shares)


def size_compartments(points: gpd.GeoDataFrame, demand_per_day: np.ndarray, defaults: dict, rule: dict | None) -> np.ndarray:
    """Compartments per point: the OSM capacity tag where mapped; otherwise sized to the expected daily demand
    (``days_factor`` x ``safety`` x demand, rounded up to ``step``, within ``min``..``max``) when *rule* is given,
    else the default of the point's kind. Operators size stations to demand: DHL's modular Packstation grows from
    76 to 390 compartments where needed."""
    default = points.kind.map(defaults).fillna(defaults["locker"]).astype(float).to_numpy()
    own = (pd.to_numeric(points["compartments"], errors="coerce").to_numpy(float) if "compartments" in points
           else np.full(len(points), np.nan))
    if rule:
        step = float(rule.get("step", 10))
        sized = np.ceil(float(rule["days_factor"]) * float(rule["safety"]) * np.asarray(demand_per_day, dtype=float) / step) * step
        minimum = float(rule["min"]) if not isinstance(rule["min"], dict) else             points.kind.map(rule["min"]).fillna(min(rule["min"].values())).astype(float).to_numpy()
        default = np.clip(sized, np.maximum(minimum, float(rule.get("floor", 0))), float(rule["max"]))
    return np.where(np.isfinite(own) & (own > 0), own, default).round().astype(np.int64)


def keep_compartments(points: gpd.GeoDataFrame, sized: np.ndarray, previous: np.ndarray, rule: dict | None = None) -> np.ndarray:
    """Compartments that never shrink: points without an OSM capacity tag keep last year's size (*previous*, 0 = not
    sized before) when this year's demand asks for less, at most the largest module (``rule["max"]``); tagged points
    stay as mapped."""
    sized = np.asarray(sized, dtype=np.int64)
    previous = np.asarray(previous, dtype=np.int64)
    if previous.shape != sized.shape:
        raise ValueError("previous_compartments must hold one value per point")
    own = (pd.to_numeric(points["compartments"], errors="coerce").to_numpy(float) if "compartments" in points
           else np.full(len(points), np.nan))
    untagged = ~(np.isfinite(own) & (own > 0))
    grown = np.minimum(np.maximum(sized, previous), float(rule["max"]) if rule else np.inf)
    return np.where(untagged, grown, sized).round().astype(np.int64)


def point_compartments(points: gpd.GeoDataFrame, inputs: dict) -> np.ndarray:
    """Compartments per point: the OSM capacity tag where mapped, else the default of the point's kind."""
    default = points.kind.map(inputs["compartments"]).fillna(inputs["compartments"]["locker"]).astype(float)
    own = pd.to_numeric(points.get("compartments"), errors="coerce") if "compartments" in points else pd.Series(np.nan, index=points.index)
    return own.fillna(default).round().astype(np.int64).to_numpy()


def locker_queue(points: gpd.GeoDataFrame, inputs: dict, rng: np.random.Generator,
                 compartments: np.ndarray | None = None) -> LockerQueue:
    """Queue of all points with their compartments (given, else by kind/OSM tag) and context pickup profiles."""
    profiles_by_kind = {kind: np.asarray(values, dtype=float) for kind, values in inputs["pickup_profile"].items()}
    by_carrier = {carrier: np.asarray(values, dtype=float) for carrier, values in inputs.get("pickup_profile_by_carrier", {}).items()}
    horizon = max([len(values) for values in profiles_by_kind.values()] + [len(values) for values in by_carrier.values()] + [1])
    profiles = np.zeros((len(points), horizon))
    factors = inputs.get("pickup_context_factor", {})
    contexts = points.context.astype(str).to_numpy() if "context" in points else np.full(len(points), "other")
    for position, (kind, carriers, context) in enumerate(zip(points.kind.astype(str), points.carriers.astype(str), contexts)):
        base = by_carrier.get(carriers, profiles_by_kind.get(kind, profiles_by_kind["locker"]))
        profile = context_profile(base, float(factors.get(context, 1.)))
        profiles[position, :len(profile)] = profile
    return LockerQueue(point_compartments(points, inputs) if compartments is None else np.asarray(compartments, dtype=np.int64), profiles)


def _choose(distance: np.ndarray, reach: float, decay: float, rng: np.random.Generator) -> np.ndarray:
    """Column of the chosen point per row: among the reachable candidates with probability ~ exp(-d / decay)."""
    weight = np.where(np.isfinite(distance) & (distance <= reach), np.exp(-np.minimum(distance, 1e6) / decay), 0.)
    total = weight.sum(axis=1, keepdims=True)
    probability = np.where(total > 0, weight / np.where(total > 0, total, 1.), 0.)
    cumulative = np.cumsum(probability, axis=1)
    draw = rng.random(len(distance))[:, None]
    pick = (cumulative < draw).sum(axis=1)
    return np.minimum(pick, distance.shape[1] - 1)


def _nearest(point_xy: np.ndarray, group: np.ndarray, origin: np.ndarray, k: int) -> tuple[np.ndarray, np.ndarray]:
    """Distances and global indices of the *k* nearest points of *group* (inf / -1 where there are fewer)."""
    from scipy.spatial import cKDTree

    distance = np.full((len(origin), k), np.inf)
    index = -np.ones((len(origin), k), dtype=np.int64)
    if len(group):
        found = min(k, len(group))
        near_d, near_i = cKDTree(point_xy[group]).query(origin, k=found)
        distance[:, :found] = near_d.reshape(len(origin), found)
        index[:, :found] = group[near_i.reshape(len(origin), found)]
    return distance, index


def apply_plan(item, plan: OutOfHomePlan, day: int, rng: np.random.Generator):
    """Route one day's B2C SegmentDay through *plan*; points become extra rows of the returned SegmentDay."""
    from .allocation import SegmentDay

    if list(item.carriers) != plan.carriers:
        raise ValueError("out-of-home plan and segment day list different carriers")
    free = plan.queue.free(day)
    home, points, overflow, turned_away = divert(item.counts, plan.propensity, plan.factors[day], plan.primary, plan.secondary, free, rng)
    stored = points.sum(axis=1)
    plan.queue.store(stored, rng)
    # occupied = afternoon peak after the delivery, before the evening pickups (compartments - free + stored)
    occupancy = pd.DataFrame({"stop_index": np.arange(len(plan.points)), "compartments": plan.queue.compartments,
                              "occupied": plan.queue.compartments - free + stored, "stored": stored, "rejected": turned_away,
                              "occupied_next_morning": plan.queue.occupancy()})
    extra = len(plan.points)
    carrier_shares = None if item.carrier_shares is None else np.vstack([item.carrier_shares, np.zeros((extra, points.shape[1]))])
    result = SegmentDay(plan.extended_sites, item.carriers, np.vstack([home, points]).astype(np.int64),
                        np.concatenate([np.asarray(item.shares, dtype=float), np.zeros(extra)]), item.delivered, carrier_shares)
    return result, points.sum(axis=0), overflow, occupancy


def point_stops(points: gpd.GeoDataFrame, first_index: int) -> gpd.GeoDataFrame:
    """Stop rows for pickup points, numbered after the reference stops."""
    return gpd.GeoDataFrame({"stop_id": ("ooh:" + points.point_id.astype(str)).to_numpy(),
                             "stop_index": np.arange(len(points)) + int(first_index), "str_idx": -1, "section_id": "",
                             "plz": points.plz.astype(str).to_numpy(), "n_units": 0, "expected_daily": 0.,
                             "stop_type": points.kind.astype(str).to_numpy(), "point_id": points.point_id.astype(str).to_numpy(),
                             "carriers": points.carriers.astype(str).to_numpy(), "synthetic": points.synthetic.astype(bool).to_numpy(),
                             "context": (points.context.astype(str).to_numpy() if "context" in points else np.full(len(points), "other")),
                             "brand": (points.brand.astype(str).to_numpy() if "brand" in points else np.full(len(points), ""))},
                            geometry=points.geometry.to_numpy(), crs=points.crs)


def _overpass(query: str, timeout: int) -> list[dict]:
    import urllib.parse
    import urllib.request

    errors = []
    for endpoint in _OVERPASS:
        request = urllib.request.Request(endpoint, data=urllib.parse.urlencode({"data": query}).encode(),
                                         headers={"User-Agent": "HAGRID-demand/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=timeout + 60) as response:
                return json.load(response)["elements"]
        except (OSError, ValueError) as exc:  # busy or unreachable instance: try the next mirror
            errors.append(f"{endpoint}: {exc}")
    raise RuntimeError("no Overpass instance answered: " + "; ".join(errors))


RETAIL_CONTEXT = {"shop": ["supermarket", "convenience", "kiosk", "department_store", "mall", "chemist", "variety_store"],
                  "amenity": ["fuel"]}


def fetch_osm_parcel_points(region_wgs84, crs, shared: Sequence[str], timeout: int = 180,
                            retail_pois: gpd.GeoDataFrame | None = None) -> gpd.GeoDataFrame:
    """Download parcel lockers, post offices, partner shops and pickup outposts from Overpass for *region_wgs84*.

    One small query per tag keeps each request below the public instances' gateway limits. The station context
    uses *retail_pois* (a local POI extract) when given, else Overpass; a failed context query leaves the context
    ``other`` with a warning instead of failing the download.
    """
    west, south, east, north = region_wgs84.bounds
    box = f"{south},{west},{north},{east}"
    elements, seen = [], set()
    for selector in ('["amenity"="parcel_locker"]', '["amenity"="post_office"]', '["post_office"]', '["shop"="outpost"]'):
        for element in _overpass(f"[out:json][timeout:{timeout}];nwr{selector}({box});out center tags;", timeout):
            key = (element["type"], element["id"])
            if key not in seen:
                seen.add(key)
                elements.append(element)
    points = points_from_elements(elements, region_wgs84, crs, shared)
    inputs = load_out_of_home_inputs()
    import warnings

    def features(query: str, label: str) -> gpd.GeoDataFrame:
        try:
            return _feature_points(_overpass(query, timeout), crs)
        except RuntimeError as exc:
            warnings.warn(f"{label} context not available from Overpass, treated as absent: {exc}", stacklevel=2)
            return gpd.GeoDataFrame({"kind": []}, geometry=gpd.GeoSeries([], crs=crs), crs=crs)

    transit = features('[out:json][timeout:%d];(nwr["railway"~"^(station|halt|tram_stop)$"](%s);nwr["amenity"="bus_station"](%s);'
                       'nwr["public_transport"="station"](%s););out center;' % (timeout, box, box, box), "transit")
    if retail_pois is not None:
        retail = synthetic_candidates(retail_pois.to_crs(crs), RETAIL_CONTEXT)
    else:
        retail = features('[out:json][timeout:%d];(nwr["shop"~"^(%s)$"](%s);nwr["amenity"="fuel"](%s););out center;'
                          % (timeout, "|".join(RETAIL_CONTEXT["shop"]), box, box), "retail")
    points["context"] = point_context(points, transit, retail, transit_m=float(inputs["context_transit_m"]),
                                      retail_m=float(inputs["context_retail_m"]))
    return points


def add_context(points: gpd.GeoDataFrame, transit: gpd.GeoDataFrame | None, retail_pois: gpd.GeoDataFrame | None,
                inputs: dict) -> gpd.GeoDataFrame:
    """Station context of *points* from local station and POI extracts (no download)."""
    points = points.copy()
    crs = points.crs
    retail = synthetic_candidates(retail_pois.to_crs(crs), RETAIL_CONTEXT) if retail_pois is not None else None
    stations = transit.to_crs(crs) if transit is not None else None
    points["context"] = point_context(points, stations, retail, transit_m=float(inputs["context_transit_m"]),
                                      retail_m=float(inputs["context_retail_m"]))
    return points


def _feature_points(elements: list[dict], crs) -> gpd.GeoDataFrame:
    """Point geometries (node position or way/relation centre) of Overpass elements, projected to *crs*."""
    coords = [(el["lon"], el["lat"]) if "lat" in el else (el.get("center", {}).get("lon"), el.get("center", {}).get("lat"))
              for el in elements]
    coords = [(lon, lat) for lon, lat in coords if lon is not None and lat is not None]
    frame = gpd.GeoDataFrame({"kind": ["feature"] * len(coords)},
                             geometry=gpd.points_from_xy([c[0] for c in coords], [c[1] for c in coords]), crs=4326)
    return frame.to_crs(crs)
