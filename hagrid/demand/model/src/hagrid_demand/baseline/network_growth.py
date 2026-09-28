"""Demand-driven growth of the pickup network over the simulated years (spec 2026-09-28, section 4.3).

The growth is a pre-pass before the daily loop, because the stop register is built once: the reference network (the
OSM snapshot plus the configured synthetic points) is grouped by kind and carriers, and in every simulated year after
the reference year each group's station count follows its out-of-home demand,

    N(y) = max(N(y-1), round(N(ref) * (D(y) / D(ref)) ** elasticity)),

with D the B2C parcels the group's carriers deliver out of home. Missing stations are placed at retail and
fuel-station POIs where B2C demand is high and the group's coverage thin. Reference stations come first and the
additions follow in year order, so every station keeps its stop index in all years. Pickup shops and other kinds
without ``kind_preferences`` do not grow; a single simulated year keeps the reference network.
"""

from __future__ import annotations

from typing import Iterable

import geopandas as gpd
import numpy as np
import pandas as pd

from hagrid_demand.common.rng import named_rng

from .out_of_home import out_of_home_share

Group = tuple[str, str]


def group_key(group: Group) -> str:
    """Status label of a network group: ``<kind>:<carriers>``."""
    return f"{group[0]}:{group[1]}"


def network_groups(points: gpd.GeoDataFrame) -> list[Group]:
    """Distinct (kind, carriers) groups of the reference network, sorted."""
    return sorted({(str(kind), str(carriers)) for kind, carriers in zip(points.kind, points.carriers)})


def growth_years(years: Iterable[int], cfg: dict) -> list[int]:
    """Years that grow the network: the simulated years after the reference year of an enabled multi-year run."""
    distinct = sorted({int(year) for year in years})
    if not cfg.get("enabled", False) or len(distinct) < 2:
        return []
    return [year for year in distinct if year > int(cfg["reference_year"])]


def carrier_ooh_demand(sites_year: pd.DataFrame, profiles_year: pd.DataFrame, year: int, inputs: dict) -> dict[str, float]:
    """Out-of-home B2C parcels per carrier in *year*: the private annual expectation of all sites x the carrier's
    private share x its out-of-home share (``out_of_home_share``)."""
    sites = sites_year.loc[sites_year.segment.astype(str).eq("private")]
    profiles = profiles_year.loc[profiles_year.segment.astype(str).eq("private")]
    if "year" in sites:
        sites = sites.loc[sites.year.eq(year)]
    if "year" in profiles:
        profiles = profiles.loc[profiles.year.eq(year)]
    total = float(pd.to_numeric(sites.annual_expected, errors="coerce").fillna(0.).sum())
    shares = profiles.groupby("carrier").share.sum()
    return {str(carrier): total * float(share) * out_of_home_share(int(year), str(carrier), inputs) for carrier, share in shares.items()}


def group_demand(carrier_demand: dict[str, float], groups: Iterable[Group]) -> dict[Group, float]:
    """Out-of-home demand of each group: the sum over the carriers it serves."""
    return {group: float(sum(float(carrier_demand.get(carrier, 0.)) for carrier in group[1].split("|"))) for group in groups}


def group_targets(reference_counts: dict[Group, int], demand_ref: dict[Group, float], demand_year: dict[Group, float],
                  previous: dict[Group, int], elasticity: float) -> dict[Group, int]:
    """Station count per group: ``max(previous, round(N_ref * (D_year / D_ref) ** elasticity))``; a group without
    reference demand keeps its previous count."""
    targets = {}
    for group, count in reference_counts.items():
        before = int(previous.get(group, count))
        base, now = float(demand_ref.get(group, 0.)), float(demand_year.get(group, 0.))
        if not (np.isfinite(base) and base > 0 and np.isfinite(now)):
            targets[group] = before
            continue
        targets[group] = max(before, int(round(float(count) * (max(now, 0.) / base) ** float(elasticity))))
    return targets


def poi_types(candidates: pd.DataFrame, types: dict) -> np.ndarray:
    """``<column>=<value>`` of each candidate POI: its first tag listed in *types* (``shop`` before ``amenity``)."""
    labels = np.full(len(candidates), None, dtype=object)
    for column, values in types.items():
        if column not in candidates:
            continue
        own = candidates[column].to_numpy(dtype=object)
        hit = pd.isna(labels) & pd.Series(own).isin(list(values)).to_numpy()
        labels[hit] = [f"{column}={value}" for value in own[hit]]
    return labels


def _preferred(candidates: pd.DataFrame, preferences: dict) -> np.ndarray:
    mask = np.zeros(len(candidates), dtype=bool)
    for column, values in (preferences or {}).items():
        if column in candidates:
            mask |= candidates[column].isin(list(values)).to_numpy()
    return mask


def _xy(frame: gpd.GeoDataFrame) -> np.ndarray:
    if not len(frame):
        return np.empty((0, 2))
    return np.column_stack([frame.geometry.x.to_numpy(float), frame.geometry.y.to_numpy(float)])


def demand_near(xy: np.ndarray, demand_xy: np.ndarray, demand: np.ndarray, radius: float) -> np.ndarray:
    """Demand of the sites within *radius* of each position (sites without coordinates are skipped)."""
    from scipy.spatial import cKDTree

    xy = np.asarray(xy, dtype=float).reshape(-1, 2)
    demand_xy = np.asarray(demand_xy, dtype=float).reshape(-1, 2)
    demand = np.asarray(demand, dtype=float).reshape(-1)
    valid = np.isfinite(demand_xy).all(axis=1) & np.isfinite(demand)
    if not len(xy) or not valid.any():
        return np.zeros(len(xy))
    values = demand[valid]
    neighbours = cKDTree(demand_xy[valid]).query_ball_point(xy, r=float(radius))
    return np.asarray([values[index].sum() for index in neighbours], dtype=float)


def candidate_weights(candidates: gpd.GeoDataFrame, existing_xy: np.ndarray, demand_xy: np.ndarray, demand: np.ndarray,
                      kind: str, cfg: dict, *, near: np.ndarray | None = None) -> np.ndarray:
    """Draw weight of each candidate POI for a new station of *kind*.

    B2C demand within ``demand_radius_m`` (or the precomputed *near*) x coverage gap ``1 - exp(-d / gap_scale_m)``
    (d = distance to the nearest station of the group, *existing_xy*) x kind preference (1 for the POI types in
    ``kind_preferences[kind]``, else ``off_preference_weight``); 0 closer than ``min_spacing_m`` to a station of the group.
    """
    from scipy.spatial import cKDTree

    xy = _xy(candidates)
    near = demand_near(xy, demand_xy, demand, cfg["demand_radius_m"]) if near is None else np.asarray(near, dtype=float)
    existing_xy = np.asarray(existing_xy, dtype=float).reshape(-1, 2)
    distance = cKDTree(existing_xy).query(xy)[0] if len(existing_xy) and len(xy) else np.full(len(xy), np.inf)
    gap = 1. - np.exp(-distance / float(cfg["gap_scale_m"]))
    preference = np.where(_preferred(candidates, cfg["kind_preferences"].get(kind, {})), 1., float(cfg["off_preference_weight"]))
    weights = near * gap * preference
    weights[distance < float(cfg["min_spacing_m"])] = 0.
    return weights


def grow_network(points: gpd.GeoDataFrame, additions: dict[Group, int], candidates: gpd.GeoDataFrame, demand_xy: np.ndarray,
                 demand: np.ndarray, year: int, cfg: dict, rng: np.random.Generator, *, used: np.ndarray | None = None,
                 labels: np.ndarray | None = None) -> tuple[gpd.GeoDataFrame, dict[Group, dict]]:
    """Append ``additions[group]`` synthetic stations per group for *year* at candidate POIs.

    Per group (sorted) the POIs are drawn one by one without replacement with probability ~ ``candidate_weights``;
    the weights (coverage gap to the group's stations before this year's draw) are computed once per group and year,
    only POIs closer than ``min_spacing_m`` to a station drawn earlier in the year drop out. Each POI hosts at most
    one new station: *used* marks the POIs taken earlier in the run and is updated in place. Returns the network and
    per group ``{"target_added", "added", "candidates", "shortfall"}``.
    """
    used = np.zeros(len(candidates), dtype=bool) if used is None else used
    labels = poi_types(candidates, cfg["candidate_types"]) if labels is None else labels
    xy = _xy(candidates)
    near = demand_near(xy, demand_xy, demand, cfg["demand_radius_m"])
    kinds, served = points.kind.astype(str).to_numpy(), points.carriers.astype(str).to_numpy()
    point_xy = _xy(points)
    added, status = [], {}
    for group in sorted(additions):
        kind, carriers = group
        wanted = max(0, int(additions[group]))
        weights = candidate_weights(candidates, point_xy[(kinds == kind) & (served == carriers)], demand_xy, demand, kind, cfg, near=near)
        weights[used] = 0.
        available = int((weights > 0).sum())
        chosen = []
        while len(chosen) < wanted:
            pool = np.flatnonzero(weights > 0)
            if not len(pool):
                break
            pick = int(rng.choice(pool, p=weights[pool] / weights[pool].sum()))
            chosen.append(pick)
            # the new station covers its surroundings for the rest of the year's draw (and itself)
            weights[np.hypot(xy[:, 0] - xy[pick, 0], xy[:, 1] - xy[pick, 1]) < float(cfg["min_spacing_m"])] = 0.
        chosen = np.sort(np.asarray(chosen, dtype=np.int64))
        used[chosen] = True
        take = len(chosen)
        status[group] = {"target_added": wanted, "added": int(take), "candidates": available, "shortfall": int(wanted - take)}
        if take:
            added.append(gpd.GeoDataFrame({"point_id": [f"syn:{kind}:{carriers}:{int(year)}:{index}" for index in range(take)],
                                           "kind": kind, "carriers": carriers, "brand": "synthetic", "synthetic": True,
                                           "context": "retail", "year_opened": int(year), "poi_type": labels[chosen],
                                           "compartments": np.nan},
                                          geometry=candidates.geometry.iloc[chosen].to_numpy(), crs=candidates.crs).to_crs(points.crs))
    if not added:
        return points.reset_index(drop=True), status
    return gpd.GeoDataFrame(pd.concat([points, *added], ignore_index=True), crs=points.crs), status


def capacity_inputs(points: gpd.GeoDataFrame, active: np.ndarray, sized_last: np.ndarray, reference: np.ndarray, cfg: dict,
                    grows: bool) -> tuple[gpd.GeoDataFrame, np.ndarray | None]:
    """The stations open in a year (*active* positions) and their ``previous_compartments`` for ``build_plan``.

    Without network growth every year is sized on its own (``None``). With growth untagged stations never shrink
    (*sized_last*: compartments after their latest planned year, 0 = not sized yet); with ``resize_existing = false``
    the *reference* stations keep their first size, fixed like an OSM capacity tag, while new stations still grow.
    """
    subset = points.iloc[active].reset_index(drop=True)
    if not grows:
        return subset, None
    previous = np.asarray(sized_last, dtype=np.int64)[active]
    if not cfg["resize_existing"]:
        fixed = np.asarray(reference, dtype=bool)[active] & (previous > 0)
        subset.loc[fixed, "compartments"] = previous[fixed].astype(float)
    return subset, previous


def plan_network(points: gpd.GeoDataFrame, years: Iterable[int], demand_by_year: dict[int, dict[str, float]],
                 candidates: gpd.GeoDataFrame | None, demand_xy_by_year: dict[int, np.ndarray],
                 demand_by_site_year: dict[int, np.ndarray], cfg: dict, seed: int) -> tuple[gpd.GeoDataFrame, dict]:
    """The pickup network of all simulated *years* and its status block.

    Reference stations keep their order with ``year_opened`` = the reference year (the first simulated year when that is
    earlier: the reference network then serves the earlier years unchanged) and ``poi_type`` None; the additions of each
    growth year follow in year order. Reference counts and demand come from the reference network and the reference
    year's demand (*demand_by_year* must hold it), never from a simulated year. Each growth year draws with
    ``named_rng(seed, year=year, channel="ooh-network-growth")``.
    """
    years = [int(year) for year in years]
    reference_year = int(cfg["reference_year"])
    network = points.copy().reset_index(drop=True)
    network["year_opened"] = int(min([reference_year, *years]))
    network["poi_type"] = None
    kinds, served = network.kind.astype(str).to_numpy(), network.carriers.astype(str).to_numpy()
    groups = [group for group in network_groups(network) if group[0] in cfg["kind_preferences"]]
    reference_counts = {group: int(((kinds == group[0]) & (served == group[1])).sum()) for group in groups}
    grow = growth_years(years, cfg) if groups else []
    status = {"enabled": bool(cfg["enabled"]), "reference_year": reference_year, "elasticity": float(cfg["elasticity"]),
              "resize_existing": bool(cfg["resize_existing"]), "growth_years": grow,
              "reference_points": {group_key(group): count for group, count in reference_counts.items()}, "years": {}}
    if not grow:
        return network, status
    if reference_year not in demand_by_year:
        raise ValueError(f"network growth needs the out-of-home demand of the reference year {reference_year}")
    demand_ref = group_demand(demand_by_year[reference_year], groups)
    status["reference_demand"] = {group_key(group): value for group, value in demand_ref.items()}
    status["candidate_pois"] = int(len(candidates))
    labels = poi_types(candidates, cfg["candidate_types"])
    used = np.zeros(len(candidates), dtype=bool)
    previous = dict(reference_counts)
    for year in grow:
        if year not in demand_by_year:
            raise ValueError(f"network growth needs the out-of-home demand of {year}")
        demand_now = group_demand(demand_by_year[year], groups)
        targets = group_targets(reference_counts, demand_ref, demand_now, previous, float(cfg["elasticity"]))
        kinds, served = network.kind.astype(str).to_numpy(), network.carriers.astype(str).to_numpy()
        have = {group: int(((kinds == group[0]) & (served == group[1])).sum()) for group in groups}
        network, grown = grow_network(network, {group: max(0, targets[group] - have[group]) for group in groups}, candidates,
                                      demand_xy_by_year[year], demand_by_site_year[year], year, cfg,
                                      named_rng(int(seed), year=year, channel="ooh-network-growth"), used=used, labels=labels)
        status["years"][str(year)] = {group_key(group): {"target": int(targets[group]), "added": grown[group]["added"],
                                                         "candidates": grown[group]["candidates"],
                                                         "shortfall": int(targets[group] - have[group] - grown[group]["added"]),
                                                         "demand": demand_now[group]} for group in groups}
        previous = targets
    return network, status
