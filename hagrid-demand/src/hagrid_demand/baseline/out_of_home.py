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
        if key in {"shares_2025", "shares_by_year", "capacity_per_day", "national_shops"} and isinstance(value, dict):
            inputs[key] = {**inputs.get(key, {}), **value}
        elif key != "enabled":
            inputs[key] = value
    for carrier, share in inputs["shares_2025"].items():
        if not 0 <= float(share) < 1:
            raise ValueError(f"out_of_home.shares_2025.{carrier} must be a share in [0, 1)")
    inputs.setdefault("synthetic_shops", True)
    return inputs


def load_points(path: Path, crs) -> gpd.GeoDataFrame:
    """Pickup points written by ``baseline osm-parcel-points`` (point_id, kind, carriers, brand, synthetic, geometry)."""
    points = gpd.read_parquet(path)
    missing = {"point_id", "kind", "carriers"} - set(points.columns)
    if missing:
        raise ValueError(f"parcel points file lacks columns: {sorted(missing)}")
    points = points.to_crs(crs) if points.crs is not None else points.set_crs(crs)
    if "synthetic" not in points:
        points["synthetic"] = False
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


@dataclass
class OutOfHomePlan:
    """One year's routing of a segment's sites (in SegmentDay order) to pickup points."""

    points: gpd.GeoDataFrame
    carriers: list
    propensity: np.ndarray
    primary: np.ndarray
    secondary: np.ndarray
    capacity: np.ndarray
    factors: np.ndarray
    extended_sites: pd.DataFrame
    shares: dict


def build_plan(sites: pd.DataFrame, carriers: Sequence[str], site_xy: np.ndarray, population: np.ndarray,
               points: gpd.GeoDataFrame, inputs: dict, year: int, days: int, factor_rng) -> OutOfHomePlan:
    """Primary/secondary point, calibrated propensity and daily factors for every site and carrier."""
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
            # Recipients who choose out-of-home delivery mostly pick a locker; shops take the rest and the overflow.
            lockers = candidates[np.isin(kinds[candidates], ["locker", "shared_locker"])]
            shops = candidates[~np.isin(kinds[candidates], ["locker", "shared_locker"])]
            locker_d, locker_i = _nearest(point_xy, lockers, origin, 2)
            shop_d, shop_i = _nearest(point_xy, shops, origin, 2)
            use_locker = locker_d[:, 0] <= reach
            first_d = np.where(use_locker, locker_d[:, 0], shop_d[:, 0])
            first_i = np.where(use_locker, locker_i[:, 0], shop_i[:, 0])
            shop_near = shop_d[:, 0] <= reach
            second_d = np.where(use_locker, np.where(shop_near, shop_d[:, 0], locker_d[:, 1]), shop_d[:, 1])
            second_i = np.where(use_locker, np.where(shop_near, shop_i[:, 0], locker_i[:, 1]), shop_i[:, 1])
        else:
            both_d, both_i = _nearest(point_xy, candidates, origin, 2)
            first_d, first_i, second_d, second_i = both_d[:, 0], both_i[:, 0], both_d[:, 1], both_i[:, 1]
        first = valid & (first_d <= reach) & (first_i >= 0)
        primary[first, column] = first_i[first]
        second = valid & (second_d <= reach) & (second_i >= 0)
        secondary[second, column] = second_i[second]
        base = np.where(first, apartment * np.exp(-np.where(first, first_d, 0.) / float(inputs["decay_m"])), 0.)
        propensity[:, column] = calibrate_propensity(weights, base, shares[carrier], float(inputs["max_propensity"]))
    capacity_by_kind = inputs["capacity_per_day"]
    capacity = points.kind.map(capacity_by_kind).fillna(capacity_by_kind["shop"]).to_numpy(dtype=np.int64)
    factors = (np.column_stack([ar1_lognormal(days, float(inputs["day_log_sd"]), float(inputs["day_ar"]), factor_rng(carrier))
                                for carrier in carriers]) if width else np.ones((days, 0)))
    pseudo = pd.DataFrame({"year": year, "site_id": "ooh:" + points.point_id.astype(str), "plz": points.plz.astype(str),
                           "segment": "private", "annual_expected": 0., "allocation_status": "out_of_home"})
    extended = pd.concat([sites.reset_index(drop=True), pseudo.reindex(columns=sites.columns)], ignore_index=True)
    return OutOfHomePlan(points, list(carriers), propensity, primary, secondary, capacity, factors, extended, shares)


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
    home, points, overflow = divert(item.counts, plan.propensity, plan.factors[day], plan.primary, plan.secondary,
                                    plan.capacity, rng)
    extra = len(plan.points)
    carrier_shares = None if item.carrier_shares is None else np.vstack([item.carrier_shares, np.zeros((extra, points.shape[1]))])
    result = SegmentDay(plan.extended_sites, item.carriers, np.vstack([home, points]).astype(np.int64),
                        np.concatenate([np.asarray(item.shares, dtype=float), np.zeros(extra)]), item.delivered, carrier_shares)
    return result, points.sum(axis=0), overflow


def point_stops(points: gpd.GeoDataFrame, first_index: int) -> gpd.GeoDataFrame:
    """Stop rows for pickup points, numbered after the reference stops."""
    return gpd.GeoDataFrame({"stop_id": ("ooh:" + points.point_id.astype(str)).to_numpy(),
                             "stop_index": np.arange(len(points)) + int(first_index), "str_idx": -1, "section_id": "",
                             "plz": points.plz.astype(str).to_numpy(), "n_units": 0, "expected_daily": 0.,
                             "stop_type": points.kind.astype(str).to_numpy(), "point_id": points.point_id.astype(str).to_numpy(),
                             "carriers": points.carriers.astype(str).to_numpy(), "synthetic": points.synthetic.astype(bool).to_numpy()},
                            geometry=points.geometry.to_numpy(), crs=points.crs)


def fetch_osm_parcel_points(region_wgs84, crs, shared: Sequence[str], timeout: int = 240) -> gpd.GeoDataFrame:
    """Download parcel lockers, post offices and partner shops from the Overpass API for *region_wgs84*."""
    import urllib.parse
    import urllib.request

    west, south, east, north = region_wgs84.bounds
    box = f"{south},{west},{north},{east}"
    query = ("[out:json][timeout:%d];(nwr[\"amenity\"=\"parcel_locker\"](%s);nwr[\"amenity\"=\"post_office\"](%s);"
             "nwr[\"post_office\"](%s););out center tags;" % (timeout, box, box, box))
    request = urllib.request.Request("https://overpass-api.de/api/interpreter", data=urllib.parse.urlencode({"data": query}).encode(),
                                     headers={"User-Agent": "HAGRID-demand/1.0"})
    with urllib.request.urlopen(request, timeout=timeout + 60) as response:
        elements = json.load(response)["elements"]
    return points_from_elements(elements, region_wgs84, crs, shared)
