"""Street anchor: DHL street observations -> B2C/B2B demand per street and building (spec 5.5-5.10)."""

from __future__ import annotations

from dataclasses import dataclass, fields

import numpy as np
import pandas as pd
from scipy.optimize import nnls

from hagrid_demand.common.rng import named_rng


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
