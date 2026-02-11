"""
HAGRID Output Analysis – EV Fleet Model
=========================================

Compute how many vehicles per provider should be flagged as EV and
assign those flags to concrete vehicles (shortest tours first).

Uses the single canonical :func:`extract_provider` from *utils* and
delegates all config constants to *config*.
"""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

from hagrid_output_analysis.config import (
    EV_CLASSES,
    FIXED_EV_SHARES,
    FLEXIBLE_PROVIDERS,
    GLOBAL_EV_TARGET,
    MIN_EV_SHARES,
    VEHICLE_SIZE_MAPPING,
    RunConfig,
)
from hagrid_output_analysis.utils import extract_provider


# ====================================================================
# Internal helpers
# ====================================================================

def _is_van_row(row: pd.Series) -> bool:
    """Check whether a result row represents a van."""
    vs = str(row.get("veh_size", "")).lower()
    return vs in {"m", "l", "supply_light_van"}


def _ev_class_from_base(base_class: str) -> str:
    """Convert a base vehicle class to its EV counterpart."""
    return {
        "van [< 2t]": "van_e [< 2t]",
        "van [> 2t]": "van_e [> 2t]",
    }.get(base_class, base_class)


def _distance_series(df: pd.DataFrame) -> pd.Series:
    """Return the best-available distance series for sorting."""
    for col in ("tour_km", "Tour Duration"):
        if col in df.columns:
            return df[col].astype(float)
    return pd.Series(0.0, index=df.index)


# ====================================================================
# Phase 1 – compute EV target counts per provider
# ====================================================================

def compute_ev_counts(
    df: pd.DataFrame,
    cfg: RunConfig | None = None,
    *,
    global_target: float | None = None,
    vans_only: bool = True,
) -> pd.DataFrame:
    """Compute ``n_ev_target`` per provider.

    Parameters
    ----------
    df : DataFrame
        The enriched result table.
    cfg : RunConfig, optional
        When given, EV settings are read from the config object.
    global_target : float, optional
        **Deprecated** – kept for backward compatibility.  Use
        ``cfg.ev_target`` instead.
    vans_only : bool
        Only vans are eligible for EV conversion.

    Algorithm
    ---------
    1. Apply fixed shares, cap by van pool.
    2. Down-scale if fixed block overshoots global target.
    3. Satisfy minimum shares for flexible providers.
    4. Distribute remainder proportionally by free van capacity.
    5. Cap and ensure non-negativity.
    6. Final exactness pass.

    Returns DataFrame with columns:
        provider, n_vehicles, n_vans, n_ev_target
    """
    cfg = cfg or RunConfig()
    _ev_target = global_target if global_target is not None else cfg.ev_target
    _fixed = cfg.get_fixed_ev_shares()
    _mins = cfg.get_min_ev_shares()
    _flex = cfg.get_flexible_providers()

    tmp = df.copy()
    tmp["provider"] = tmp["vehicle_id"].apply(extract_provider)
    tmp["is_van"] = tmp.apply(_is_van_row, axis=1)

    fleet = tmp.groupby("provider")["vehicle_id"].count().rename("n_vehicles").reset_index()
    vans = tmp[tmp["is_van"]].groupby("provider")["vehicle_id"].count().rename("n_vans").reset_index()
    stats = fleet.merge(vans, on="provider", how="left").fillna({"n_vans": 0})
    stats["n_vehicles"] = stats["n_vehicles"].astype(int)
    stats["n_vans"] = stats["n_vans"].astype(int)

    total = int(stats["n_vehicles"].sum())
    target_total = int(round(_ev_target * total))

    stats["fixed_share"] = stats["provider"].map(lambda p: _fixed.get(p, np.nan))
    stats["is_flexible"] = stats["provider"].isin(_flex)
    stats["n_ev_target"] = 0
    assigned = 0

    # 1) Fixed block
    for idx, row in stats.iterrows():
        f = row["fixed_share"]
        if pd.notna(f):
            ne = int(round(float(f) * int(row["n_vehicles"])))
            if vans_only:
                ne = min(ne, int(row["n_vans"]))
            ne = max(0, ne)
            stats.at[idx, "n_ev_target"] = ne
            assigned += ne

    remaining = target_total - assigned

    # 2) Down-scale if overshoot
    if remaining < 0:
        _downscale_fixed(stats, target_total)
        assigned = int(stats["n_ev_target"].sum())
        remaining = target_total - assigned

    # 3) Minimal shares for flexible providers
    if remaining > 0:
        remaining = _apply_min_shares(stats, remaining, vans_only, _mins)

    # 4) Distribute rest by free van capacity
    if remaining > 0:
        _distribute_remaining(stats, target_total, remaining)

    # 5) Cap
    stats["n_ev_target"] = stats.apply(
        lambda r: int(max(0, min(int(r["n_ev_target"]), int(r["n_vans"])))),
        axis=1,
    )

    # 6) Exactness pass
    _exactness_pass(stats, target_total)

    return stats[["provider", "n_vehicles", "n_vans", "n_ev_target"]]


def _downscale_fixed(stats: pd.DataFrame, target_total: int) -> None:
    """Proportionally reduce fixed-share assignments to meet target."""
    fixed_mask = stats["fixed_share"].notna() & (stats["n_ev_target"] > 0)
    if not fixed_mask.any():
        return
    sub = stats.loc[fixed_mask, ["n_ev_target", "n_vans"]].copy()
    total_fixed = int(sub["n_ev_target"].sum())
    if total_fixed <= 0:
        return
    target_after = max(0, target_total)
    scaled = sub["n_ev_target"].astype(float) * (float(target_after) / float(total_fixed))
    floored = scaled.apply(np.floor).astype(int)
    rema = scaled - floored
    need = int(target_after - int(floored.sum()))
    if need > 0:
        for idx in rema.sort_values(ascending=False).index[:need]:
            floored.loc[idx] += 1
    floored = floored.clip(lower=0)
    floored = np.minimum(floored, sub["n_vans"])
    stats.loc[floored.index, "n_ev_target"] = floored.astype(int)


def _apply_min_shares(
    stats: pd.DataFrame, remaining: int, vans_only: bool,
    min_ev_shares: dict[str, float] | None = None,
) -> int:
    """Apply minimum EV shares for flexible providers."""
    _mins = min_ev_shares or MIN_EV_SHARES
    flex = stats[stats["is_flexible"]]
    for idx, row in flex.iterrows():
        p = row["provider"]
        n = int(row["n_vehicles"])
        n_vans = int(row["n_vans"])
        need = int(round(float(_mins.get(p, 0.0)) * n))
        if vans_only:
            need = min(need, n_vans)
        take = min(max(0, need), max(0, remaining))
        if take > 0:
            stats.at[idx, "n_ev_target"] += take
            remaining -= take
        if remaining <= 0:
            break
    return remaining


def _distribute_remaining(stats: pd.DataFrame, target_total: int, remaining: int) -> None:
    """Distribute leftover EV slots to flexible providers by free capacity."""
    if not stats["is_flexible"].any():
        return
    flex = stats[stats["is_flexible"]].copy()
    flex["free"] = (flex["n_vans"] - flex["n_ev_target"]).clip(lower=0).astype(int)
    cap = int(flex["free"].sum())
    if cap <= 0:
        return
    for idx, row in flex.iterrows():
        free = int(row["free"])
        if free <= 0:
            continue
        add = min(int(round(remaining * (free / cap))), free)
        if add > 0:
            stats.at[idx, "n_ev_target"] += add

    # Rounding fix
    diff = target_total - int(stats["n_ev_target"].sum())
    if diff != 0:
        for idx in stats[stats["is_flexible"]].index:
            free = int(stats.at[idx, "n_vans"] - stats.at[idx, "n_ev_target"])
            if diff > 0 and free > 0:
                stats.at[idx, "n_ev_target"] += 1
                diff -= 1
            elif diff < 0 and stats.at[idx, "n_ev_target"] > 0:
                stats.at[idx, "n_ev_target"] -= 1
                diff += 1
            if diff == 0:
                break


def _exactness_pass(stats: pd.DataFrame, target_total: int) -> None:
    """Ensure sum == target after all caps."""
    total_plan = int(stats["n_ev_target"].sum())
    if total_plan == target_total:
        return
    diff = target_total - total_plan
    preferred = list(stats.index[stats["is_flexible"]]) + list(stats.index)
    if diff > 0:
        for idx in preferred:
            head = int(stats.at[idx, "n_vans"] - stats.at[idx, "n_ev_target"])
            if head <= 0:
                continue
            take = min(head, diff)
            stats.at[idx, "n_ev_target"] += take
            diff -= take
            if diff == 0:
                break
    elif diff < 0:
        for idx in preferred:
            cur = int(stats.at[idx, "n_ev_target"])
            if cur <= 0:
                continue
            take = min(cur, -diff)
            stats.at[idx, "n_ev_target"] -= take
            diff += take
            if diff == 0:
                break

    if int(stats["n_ev_target"].sum()) != target_total:
        raise RuntimeError(
            f"EV planning mismatch: {int(stats['n_ev_target'].sum())} vs target {target_total}"
        )


# ====================================================================
# Phase 2 – assign EV flags to concrete vehicles
# ====================================================================

def assign_ev_flags(
    df: pd.DataFrame,
    ev_counts: pd.DataFrame,
) -> pd.DataFrame:
    """Flag the shortest-tour vans as EV within each provider.

    Adds columns ``provider``, ``is_van``, ``is_ev`` to *df*.
    """
    out = df.copy()
    out["provider"] = out["vehicle_id"].apply(extract_provider)
    out["is_van"] = out.apply(_is_van_row, axis=1)
    out["is_ev"] = 0

    dists = _distance_series(out)
    need = {r["provider"]: int(r["n_ev_target"]) for _, r in ev_counts.iterrows()}

    for prov, sub in out.groupby("provider"):
        n_ev = need.get(prov, 0)
        if n_ev <= 0:
            continue
        cand = sub[sub["is_van"]]
        if cand.empty:
            continue
        order = cand.index.values[np.argsort(dists.loc[cand.index].to_numpy())]
        out.loc[order[:n_ev], "is_ev"] = 1

    return out


# ====================================================================
# Phase 3 – build class maps
# ====================================================================

def build_effective_class_map(
    df: pd.DataFrame,
    cfg: RunConfig | None = None,
) -> dict[str, str]:
    """Map each vehicle_id to its calculation class (EV-aware)."""
    vsm = (cfg or RunConfig()).get_vehicle_size_mapping()
    eff: dict[str, str] = {}
    for _, row in df.iterrows():
        vid = row["vehicle_id"]
        base = vsm.get(str(row.get("veh_size", "")))
        if base is None:
            continue
        if int(row.get("is_ev", 0)) == 1 and base in {"van [< 2t]", "van [> 2t]"}:
            eff[vid] = _ev_class_from_base(base)
        else:
            eff[vid] = base
    return eff


def build_base_class_map(
    df: pd.DataFrame,
    cfg: RunConfig | None = None,
) -> dict[str, str]:
    """Map each vehicle_id to its base (non-EV) emission class."""
    vsm = (cfg or RunConfig()).get_vehicle_size_mapping()
    return {
        row["vehicle_id"]: vsm[row["veh_size"]]
        for _, row in df.iterrows()
        if row.get("veh_size") in vsm
    }


# ====================================================================
# Sanity report
# ====================================================================

def ev_sanity_report(
    result_df: pd.DataFrame,
    ev_plan: pd.DataFrame,
    cfg: RunConfig | None = None,
    *,
    global_target: float | None = None,
) -> None:
    """Print a human-readable EV assignment report."""
    cfg = cfg or RunConfig()
    _ev_target = global_target if global_target is not None else cfg.ev_target

    df = result_df.copy()
    if "provider" not in df.columns:
        df["provider"] = df["vehicle_id"].apply(extract_provider)

    assigned = (
        df.groupby("provider")["is_ev"]
        .agg(assigned_evs="sum", n_from_result="count")
        .reset_index()
    )
    merged = ev_plan.merge(assigned, on="provider", how="outer")
    for col in ("n_vehicles", "n_vans", "n_ev_target", "assigned_evs", "n_from_result"):
        if col in merged.columns:
            merged[col] = merged[col].fillna(0).astype(int)

    den = merged["n_vehicles"].where(merged["n_vehicles"] > 0, merged["n_from_result"]).replace(0, np.nan)
    merged["target_%"] = np.where(den.notna(), merged["n_ev_target"] / den * 100, 0)
    merged["assigned_%"] = np.where(den.notna(), merged["assigned_evs"] / den * 100, 0)

    total_veh = int(merged["n_vehicles"].sum()) or int(merged["n_from_result"].sum())
    total_ev = int(merged["assigned_evs"].sum())
    target_ev = int(round(_ev_target * total_veh))

    print(f"    Global target : {target_ev:,} EVs ({_ev_target*100:.1f}% of {total_veh:,})")
    print(f"    Assigned      : {total_ev:,} ({total_ev/total_veh*100 if total_veh else 0:.1f}%)")
    print(f"    {'Provider':<10s}  {'Veh':>5s}  {'Vans':>5s}  {'Target':>7s}  {'Assigned':>9s}")
    print(f"    {'─'*10}  {'─'*5}  {'─'*5}  {'─'*7}  {'─'*9}")
    for _, r in merged.sort_values("provider").iterrows():
        t_pct = f"{r['target_%']:4.1f}%"
        a_pct = f"{r['assigned_%']:4.1f}%"
        print(
            f"    {r['provider']:<10s}  {int(r['n_vehicles']):5d}  {int(r['n_vans']):5d}"
            f"  {int(r['n_ev_target']):4d} ({t_pct})  {int(r['assigned_evs']):4d} ({a_pct})"
        )
