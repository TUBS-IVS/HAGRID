"""Street anchor: DHL street observations -> B2C/B2B demand per street and building (spec 5.5-5.10)."""

from __future__ import annotations

from dataclasses import dataclass, fields

import geopandas as gpd
import numpy as np
import pandas as pd
from scipy.optimize import nnls

from hagrid_demand.common.rng import named_rng

from .reference import reconcile_carriers


@dataclass(frozen=True)
class AnchorConfig:
    min_persons: float = 30.
    min_streets: int = 20
    upper: float = 2.
    lower: float = .5
    gap_threshold: float = 5.
    exclude_above: float = 1000.
    section_length_m: float = 50.

    @classmethod
    def from_mapping(cls, value: dict | None) -> "AnchorConfig":
        value = dict(value or {})
        correction = value.pop("level_correction", {}) or {}
        known = {field.name for field in fields(cls)}
        return cls(**{key: val for key, val in {**value, **correction}.items() if key in known})


def street_table(buildings: pd.DataFrame, streets: pd.DataFrame, cfg: AnchorConfig) -> pd.DataFrame:
    """One row per DHL street with the persons, firms and buildings assigned to it."""
    assigned = buildings[buildings.sid >= 0]
    structure = assigned.groupby("sid").agg(persons=("population", "sum"), companies=("companies", "sum"),
                                            buildings=("building_key", "size"))
    table = streets[["sid", "plz", "street", "value"]].set_index("sid").join(structure)
    table[["persons", "companies", "buildings"]] = table[["persons", "companies", "buildings"]].fillna(0.)
    table["excluded"] = table.value > cfg.exclude_above
    return table.reset_index()


def level_correction(table: pd.DataFrame, cfg: AnchorConfig) -> pd.DataFrame:
    """Variant D: postal DHL level from residential streets; extreme postal areas are rescaled."""
    observed = table[~table.excluded]
    residential = observed[(observed.persons >= cfg.min_persons) & (observed.companies == 0)]
    per = residential.groupby("plz").agg(residential_streets=("sid", "size"), dhl=("value", "sum"), persons=("persons", "sum"))
    per["rate"] = per.dhl / per.persons
    median = float(per.rate.median())
    per["median_rate"] = median
    per["factor_raw"] = per.rate / median
    apply = (per.residential_streets >= cfg.min_streets) & ((per.factor_raw >= cfg.upper) | (per.factor_raw <= cfg.lower))
    per["factor"] = np.where(apply, per.factor_raw, 1.)
    per["applied"] = apply
    return per.reset_index()[["plz", "residential_streets", "rate", "median_rate", "factor_raw", "factor", "applied"]]


def apply_correction(table: pd.DataFrame, corrections: pd.DataFrame) -> pd.DataFrame:
    result = table.merge(corrections[["plz", "factor"]], on="plz", how="left")
    result["factor"] = result.factor.fillna(1.)
    result["dhl_corrected"] = result.value / result.factor
    return result


def fit_dhl_rates(table: pd.DataFrame) -> dict:
    """Regional DHL parcels per person and per firm and day (non-negative, no intercept, positive streets)."""
    observed = table[~table.excluded & (table.dhl_corrected > 0)]
    coefficients, _ = nnls(observed[["persons", "companies"]].to_numpy(float), observed.dhl_corrected.to_numpy(float))
    if not (coefficients > 0).all():
        raise ValueError(f"DHL street rates must be positive for persons and companies: {coefficients.tolist()}")
    return {"person": float(coefficients[0]), "company": float(coefficients[1])}


def decompose(table: pd.DataFrame, rates: dict, cfg: AnchorConfig) -> pd.DataFrame:
    """Split each DHL street into B2C/B2B in proportion to its structural expectation (spec 5.6, 5.9)."""
    t = table.copy()
    t["expected_private"] = rates["person"] * t.persons
    t["expected_business"] = rates["company"] * t.companies
    expected = t.expected_private + t.expected_business
    positive = t.dhl_corrected > 0
    t["anchor_status"] = np.select(
        [t.excluded, positive & (expected > 0), positive, ~positive & (expected >= cfg.gap_threshold)],
        ["excluded", "observed", "observed_unstructured", "gap"], default="zero")
    structured = t.anchor_status.eq("observed")
    share = np.divide(t.expected_business, expected, out=np.zeros(len(t)), where=expected > 0)
    t["dhl_business"] = np.where(structured, t.dhl_corrected * share, 0.)
    regional = float(t.loc[structured, "dhl_business"].sum() / t.loc[structured, "dhl_corrected"].sum())
    unstructured = t.anchor_status.eq("observed_unstructured")
    t.loc[unstructured, "dhl_business"] = t.loc[unstructured, "dhl_corrected"] * regional
    t["dhl_private"] = np.where(structured | unstructured, t.dhl_corrected - t.dhl_business, 0.)
    structural = t.anchor_status.isin(["gap", "excluded"])
    t.loc[structural, "dhl_private"] = t.loc[structural, "expected_private"]
    t.loc[structural, "dhl_business"] = t.loc[structural, "expected_business"]
    return t


def observed_b2b_share(decomposed: pd.DataFrame) -> float:
    """q_DHL: business share of the observed DHL volume."""
    observed = decomposed[decomposed.anchor_status.isin(["observed", "observed_unstructured"])]
    return float(observed.dhl_business.sum() / (observed.dhl_private + observed.dhl_business).sum())


def _wmape(actual, predicted) -> float:
    return float(np.abs(predicted - actual).sum() / np.abs(actual).sum())


def structure_holdout(table: pd.DataFrame, seed: int, folds: int = 5) -> dict:
    """Criterion A: spatial k-fold holdout by PLZ for the structural street model."""
    observed = table[~table.excluded & (table.dhl_corrected > 0)].reset_index(drop=True)
    plz = np.array(sorted(observed.plz.astype(str).unique()))
    order = named_rng(int(seed), channel="structure-holdout").permutation(len(plz))
    fold_of = {plz[index]: position % folds for position, index in enumerate(order)}
    fold = observed.plz.astype(str).map(fold_of).to_numpy()
    y = observed.dhl_corrected.to_numpy(float)
    result = {}
    for name, columns in (("M0_persons", ["persons"]), ("M1_persons_companies", ["persons", "companies"])):
        X = observed[columns].to_numpy(float)
        predicted = np.zeros(len(y))
        for k in range(folds):
            train, test = fold != k, fold == k
            if test.any():
                coefficients, _ = nnls(X[train], y[train])
                predicted[test] = X[test] @ coefficients
        postal = pd.DataFrame({"plz": observed.plz, "y": y, "p": predicted}).groupby("plz").sum()
        result[name] = {"street_wmape": _wmape(y, predicted), "postal_wmape": _wmape(postal.y.to_numpy(), postal.p.to_numpy()),
                        "folds": folds}
    return result


def json_records(frame: pd.DataFrame) -> list[dict]:
    return [{key: (value.item() if hasattr(value, "item") else value) for key, value in row.items()}
            for row in frame.to_dict(orient="records")]


def _dhl_index(carriers: list[str]) -> int:
    matches = [index for index, label in enumerate(carriers) if str(label).strip().casefold() == "dhl"]
    if len(matches) != 1:
        raise ValueError("profiles must contain exactly one DHL carrier label")
    return matches[0]


def _reconcile_with_fixed_dhl(profiles: dict, b: float, q_dhl: float) -> tuple[dict, dict]:
    carriers = list(profiles["carriers"])
    index = _dhl_index(carriers)
    m = np.asarray(profiles.get("m", profiles.get("market")), float)
    prior = np.asarray(profiles.get("q", profiles.get("q_prior")), float)
    lower = np.asarray(profiles["lower"], float).copy()
    upper = np.asarray(profiles["upper"], float).copy()
    scale = np.asarray(profiles["scale"], float)
    lower[index] = upper[index] = q_dhl
    prior = np.clip(prior, lower, upper)
    result = reconcile_carriers(m, prior, b, lower, upper, scale)
    return result, {"m": m, "q_prior": prior, "lower": lower, "upper": upper, "scale": scale,
                    "carriers": carriers, "dhl_index": index}


_UNIT_COLUMNS = ["building_key", "footprint", "building_type", "area_m2", "plz", "population", "companies", "employees",
                 "street_norm", "sid", "match_stage", "distance_m", "part", "position_m", "side", "section_id", "axis_x",
                 "axis_y", "geometry"]


def _synthetic_units(t: pd.DataFrame, streets: gpd.GeoDataFrame, section_length_m: float) -> gpd.GeoDataFrame:
    """One point per 50 m section of DHL streets that carry volume but no assigned building (spec 5.6)."""
    rows = []
    geometry = streets.set_index("sid").geometry
    for row in t[t.anchor_status.eq("observed_unstructured")].itertuples():
        line = geometry[row.sid]
        count = max(1, int(np.ceil(line.length / section_length_m)))
        for k in range(count):
            position = (k + .5) * line.length / count
            rows.append({"building_key": f"syn:{row.sid}:{k}", "footprint": False, "building_type": "synthetic",
                         "area_m2": 0., "plz": str(row.plz), "population": 0., "companies": 0., "employees": 0.,
                         "street_norm": None, "sid": int(row.sid), "match_stage": "synthetic", "distance_m": 0.,
                         "part": None, "position_m": position, "side": "right",
                         "section_id": f"{row.sid}-s-{int(position // section_length_m)}", "axis_x": np.nan,
                         "axis_y": np.nan, "geometry": line.interpolate(position), "share": 1. / count})
    return gpd.GeoDataFrame(rows, columns=[*_UNIT_COLUMNS, "share"], geometry="geometry", crs=streets.crs)


def solve_street_reference(buildings: gpd.GeoDataFrame, streets: gpd.GeoDataFrame, profiles: dict, b: float,
                           operating_days: int, cfg: dict | None, *, seed: int, scope_plz: list[str]) -> dict:
    """Street-anchored 2021 reference with the same artefact contract as ``reference.solve_reference``."""
    config = AnchorConfig.from_mapping(cfg)
    scope = {str(item) for item in scope_plz}
    in_scope = streets[streets.plz.astype(str).isin(scope)]
    table = street_table(buildings, in_scope, config)
    corrections = level_correction(table, config)
    table = apply_correction(table, corrections)
    rates = fit_dhl_rates(table)
    t = decompose(table, rates, config)
    q_dhl = observed_b2b_share(t)
    reconciliation, inputs = _reconcile_with_fixed_dhl(profiles, b, q_dhl)
    conditional = np.asarray(reconciliation["conditional"], float)
    index = inputs["dhl_index"]
    p_private, p_business = float(conditional[0, index]), float(conditional[1, index])
    t["private_daily"] = t.dhl_private / p_private
    t["business_daily"] = t.dhl_business / p_business

    units = buildings[_UNIT_COLUMNS].copy()
    street_values = t.set_index("sid")
    persons = units.sid.map(street_values.persons)
    companies = units.sid.map(street_values.companies)
    units["private_daily"] = np.where(persons > 0, units.population / persons * units.sid.map(street_values.private_daily), 0.)
    units["business_daily"] = np.where(companies > 0, units.companies / companies * units.sid.map(street_values.business_daily), 0.)
    status = units.sid.map(street_values.anchor_status)
    off_street = units.sid.lt(0) | status.isna()
    units.loc[off_street, "private_daily"] = rates["person"] * units.loc[off_street, "population"] / p_private
    units.loc[off_street, "business_daily"] = rates["company"] * units.loc[off_street, "companies"] / p_business
    units["anchor_status"] = np.where(off_street, "structural_no_street", status)
    synthetic = _synthetic_units(t, streets, config.section_length_m)
    if len(synthetic):
        synthetic["private_daily"] = synthetic.share * synthetic.sid.map(street_values.private_daily)
        synthetic["business_daily"] = synthetic.share * synthetic.sid.map(street_values.business_daily)
        synthetic["anchor_status"] = "observed_unstructured"
        units = gpd.GeoDataFrame(pd.concat([units, synthetic.drop(columns="share")], ignore_index=True),
                                 geometry="geometry", crs=buildings.crs)

    rows = []
    for segment, daily, weight in (("private", "private_daily", "population"), ("business", "business_daily", "companies")):
        part = units[(units[daily] > 0) | (units[weight] > 0)]
        rows.append(pd.DataFrame({
            "site_id": part.building_key.to_numpy(), "plz": part.plz.astype(str).to_numpy(), "segment": segment,
            "population": part.population.to_numpy(float) if segment == "private" else 0.,
            "employees": part.employees.to_numpy(float) if segment == "business" else np.nan, "branch": None,
            "weight": part[weight].to_numpy(float), "reference_annual": part[daily].to_numpy(float) * operating_days,
            "allocation_status": "located"}))
    sites = pd.concat(rows, ignore_index=True)
    for share, source in (("historical_share", "reference_annual"), ("structural_share", "weight")):
        totals = sites.groupby("segment")[source].transform("sum")
        sites[share] = np.divide(sites[source], totals, out=np.zeros(len(sites)), where=totals > 0)
    sites = sites[["site_id", "plz", "segment", "population", "employees", "branch", "weight", "historical_share",
                   "structural_share", "reference_annual", "allocation_status"]]

    observed = t.anchor_status.isin(["observed", "observed_unstructured"])
    m_dhl = (1 - b) * p_private + b * p_business
    observed_total = float((t.loc[observed, "private_daily"] + t.loc[observed, "business_daily"]).sum())
    observed_b2b = float(t.loc[observed, "business_daily"].sum() / observed_total)
    identity = {"total_residual": observed_total - float(t.loc[observed, "dhl_corrected"].sum()) / m_dhl,
                "b2b_residual": observed_b2b - b, "observed_daily": observed_total}
    if abs(identity["total_residual"]) > 1e-6 * max(observed_total, 1.) or abs(identity["b2b_residual"]) > 1e-9:
        raise ValueError(f"street anchor identity failed: {identity}")
    street_units = units[~units.anchor_status.eq("structural_no_street")]
    allocated = street_units.groupby("sid")[["private_daily", "business_daily"]].sum()
    expected = t.set_index("sid").loc[allocated.index, ["private_daily", "business_daily"]]
    allocation_error = float(np.abs(allocated.to_numpy() - expected.to_numpy()).max()) if len(allocated) else 0.
    total_daily = float(units.private_daily.sum() + units.business_daily.sum())
    regional_annual = float(sites.reference_annual.sum())

    postal_sites = (sites.groupby(["plz", "segment"]).reference_annual.sum().unstack(fill_value=0.)
                    .reindex(columns=["private", "business"], fill_value=0.))
    postal = pd.DataFrame({"plz": postal_sites.index.astype(str)})
    postal["dhl_retained_mean"] = postal.plz.map(t[~t.excluded].groupby("plz").value.sum()).fillna(0.).to_numpy()
    postal["private_annual"] = postal_sites.private.to_numpy()
    postal["business_annual"] = postal_sites.business.to_numpy()
    postal["reference_annual"] = postal.private_annual + postal.business_annual
    postal["b2b_share"] = np.divide(postal.business_annual, postal.reference_annual, out=np.zeros(len(postal)),
                                    where=postal.reference_annual > 0)
    dhl_daily = postal.plz.map(t[observed].groupby("plz").dhl_corrected.sum()).fillna(0.).to_numpy()
    postal["dhl_share"] = np.divide(dhl_daily * operating_days, postal.reference_annual.to_numpy(),
                                    out=np.zeros(len(postal)), where=postal.reference_annual.to_numpy() > 0)
    postal = postal[["plz", "dhl_retained_mean", "reference_annual", "private_annual", "business_annual", "b2b_share", "dhl_share"]]

    carriers = inputs["carriers"]
    q = np.asarray(reconciliation["q"], float)
    market = inputs["m"] / inputs["m"].sum()
    carriers_frame = pd.DataFrame({"year": 2021, "carrier": carriers, "market_share": market, "q_prior": inputs["q_prior"],
                                   "q_scale": inputs["scale"], "lower": inputs["lower"], "upper": inputs["upper"],
                                   "q_adjusted": q, "private_share": conditional[0], "business_share": conditional[1],
                                   "share": (1 - b) * conditional[0] + b * conditional[1]})
    excluded = t[t.excluded]
    scope_ledger = {"rule": f"exclude complete in-scope observation when value > {config.exclude_above:g}",
                    "threshold": config.exclude_above, "excluded_rows": int(len(excluded)),
                    "excluded_volume": float(excluded.value.sum()), "retained_rows": int((~t.excluded).sum()),
                    "retained_volume": float(t.loc[~t.excluded, "value"].sum()),
                    "out_of_scope_rows": int(len(streets) - len(in_scope)),
                    "out_of_scope_volume": float(streets.loc[~streets.plz.astype(str).isin(scope), "value"].sum())}
    status_volume = units.groupby("anchor_status")[["private_daily", "business_daily"]].sum()
    anchor = {
        "rates_dhl_per_day": rates, "q_dhl": q_dhl, "p_dhl_private": p_private, "p_dhl_business": p_business,
        "corrections": json_records(corrections),
        "street_status_counts": {str(k): int(v) for k, v in t.anchor_status.value_counts().items()},
        "daily_by_status": {str(k): float(v.sum()) for k, v in status_volume.iterrows()},
        "total_daily": total_daily, "observed_identity": identity, "allocation_max_error": allocation_error,
        "holdout": structure_holdout(table, seed=seed), "synthetic_units": int(len(synthetic)),
    }
    source_quality = {"unknown_plz_sites": [], "unknown_plz_weight": 0., "known_plz_outside_anchor_sites": [],
                      "known_plz_outside_anchor_weight": 0.,
                      "structural_no_street_units": int(units.anchor_status.eq("structural_no_street").sum())}
    checks = {"scope_ledger": scope_ledger, "b2b_target": float(b), "b2b_achieved": observed_b2b,
              "b2b_residual": observed_b2b - b, "k": None, "k_status": "not_applicable", "log_k": None,
              "dhl_carrier": carriers[index], "dhl_carrier_index": index, "dhl_market_share": float(market[index]),
              "dhl_retained_mean": scope_ledger["retained_volume"], "observed_identity": identity,
              "allocation_balance": {"street_max_error": allocation_error,
                                     "regional_error": regional_annual - total_daily * operating_days},
              "regional_annual_balance": regional_annual - total_daily * operating_days}
    persons_total = float(units.population.sum())
    companies_total = float(units.companies.sum())
    implied_rates = {
        "persons_packages_per_operating_day": float(units.private_daily.sum() / persons_total) if persons_total else None,
        "company_locations_packages_per_operating_day": float(units.business_daily.sum() / companies_total) if companies_total else None,
        "semantics": "Aggregated model rates, not causal individual ordering rates."}
    reconciliation_payload = {
        "market": [{"carrier": c, "market_share": float(v)} for c, v in zip(carriers, market)],
        "providers": [{"carrier": c, "q_prior": float(p), "q_scale": float(s), "lower": float(lo), "upper": float(hi)}
                      for c, p, s, lo, hi in zip(carriers, inputs["q_prior"], inputs["scale"], inputs["lower"], inputs["upper"])],
        "adjusted_q": [{"carrier": c, "q_adjusted": float(v)} for c, v in zip(carriers, q)],
        "conditional": [{"segment": segment, "carrier": c, "share": float(conditional[row, col])}
                        for row, segment in enumerate(("private", "business")) for col, c in enumerate(carriers)],
        "diagnostics": reconciliation["diagnostics"], "dhl_fixed_q": q_dhl, "reference_balance": None,
    }
    geometry = gpd.GeoDataFrame({"site_id": units.building_key.to_numpy()}, geometry=units.geometry.to_numpy(), crs=units.crs)
    geometry = geometry[geometry.site_id.isin(sites.site_id)].reset_index(drop=True)
    return {"sites": sites, "postal": postal, "carriers": carriers_frame, "regional_annual": regional_annual,
            "checks": checks, "reconciliation": reconciliation_payload, "source_quality": source_quality,
            "implied_rates": implied_rates, "anchor": anchor, "streets": t, "units": units, "geometry": geometry}
