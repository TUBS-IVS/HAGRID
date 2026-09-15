"""Grid-free structural potentials and deliberately separated comparison helpers."""
from __future__ import annotations

from collections import defaultdict, deque
import numpy as np
import pandas as pd
from scipy.optimize import nnls


_HISTORICAL_Q75 = (8.1078087809025286e-07, .15205915924489793, .89862556511587188)
_SEGMENTS = {"private": "private", "household": "private", "person": "private",
             "business": "business", "company": "business", "firm": "business"}


def _segment(value: object) -> str:
    try:
        return _SEGMENTS[str(value).lower()]
    except KeyError as exc:
        raise ValueError("segment must identify private or business demand") from exc


def build_potentials(sites: pd.DataFrame, power: float = 1.0,
                     branch_multipliers: dict | None = None) -> pd.DataFrame:
    """Return one grid-free demand potential for each supplied demand location.

    The canonical default is people for private locations and one unit per company
    location for business locations.  Passing branch multipliers explicitly selects
    the employee/branch candidate; it is never silently used as the default.
    """
    required = {"site_id", "plz", "segment", "population", "employees", "branch"}
    missing = required.difference(sites.columns)
    if missing:
        raise ValueError(f"sites missing required columns: {sorted(missing)}")
    if not np.isfinite(power) or power <= 0:
        raise ValueError("power must be finite and positive")
    result = sites.copy()
    result["segment"] = result["segment"].map(_segment)
    if result.duplicated(["site_id", "segment"]).any():
        raise ValueError("site_id and segment must be unique")
    if result.site_id.isna().any():
        raise ValueError("site_id is required")
    if "allocation_status" not in result:
        result["allocation_status"] = "located"
    else:
        result["allocation_status"] = result["allocation_status"].fillna("located")
    private = result.segment.eq("private")
    population = pd.to_numeric(result.population, errors="coerce")
    if population[private].isna().any() or (population[private] < 0).any():
        raise ValueError("private locations require finite nonnegative population")
    result["weight"] = 0.0
    result.loc[private, "weight"] = population.loc[private].astype(float)
    business = ~private
    if branch_multipliers is None:
        result.loc[business, "weight"] = 1.0
        result["potential_model"] = "company_locations"
    else:
        if not isinstance(branch_multipliers, dict):
            raise ValueError("branch_multipliers must be a mapping")
        multipliers = pd.Series(result.branch.map(branch_multipliers).fillna(1.0), index=result.index, dtype=float)
        if not np.isfinite(multipliers).all() or (multipliers <= 0).any():
            raise ValueError("branch multipliers must be finite and positive")
        employees = pd.to_numeric(result.employees, errors="coerce")
        if employees[business].isna().any() or (employees[business] < 0).any():
            bad = result.loc[business & (employees.isna() | employees.lt(0)), "site_id"].tolist()
            raise ValueError(f"employee candidate has invalid employees for sites: {bad}")
        result.loc[business, "weight"] = (
            multipliers.loc[business] * employees.loc[business].pow(power)
        )
        result["potential_model"] = "employee_branch_candidate"
    if not np.isfinite(result.weight).all() or (result.weight < 0).any():
        raise ValueError("potentials must be finite and nonnegative")
    return result


def historical_quantreg_predict(persons, companies):
    """Exact saved q=.75 comparison model; it is neither a mean nor a Lasso fit."""
    persons_array = np.asarray(persons, dtype=float)
    companies_array = np.asarray(companies, dtype=float)
    if not np.isfinite(persons_array).all() or not np.isfinite(companies_array).all():
        raise ValueError("historical_q75 inputs must be finite")
    intercept, persons_rate, companies_rate = _HISTORICAL_Q75
    return intercept + persons_rate * persons_array + companies_rate * companies_array


def _support_columns(support: pd.DataFrame, target: str | None = None) -> tuple[str, str, str]:
    if not isinstance(support, pd.DataFrame):
        raise TypeError("support must be a DataFrame")
    persons = "persons" if "persons" in support else "population"
    companies = "companies" if "companies" in support else "company_locations"
    if target is None:
        target = next((name for name in ("target", "dhl", "value") if name in support), None)
    if persons not in support or companies not in support or target not in support:
        raise ValueError("support requires persons, companies, and target columns")
    return persons, companies, target


def fit_nonnegative_mean(support: pd.DataFrame) -> dict:
    """Fit a no-intercept nonnegative mean model, labelled only as DHL response."""
    persons, companies, target = _support_columns(support)
    x = support[[persons, companies]].to_numpy(float)
    y = support[target].to_numpy(float)
    if len(x) < 2 or not np.isfinite(x).all() or not np.isfinite(y).all() or (y < 0).any():
        raise ValueError("support must contain finite nonnegative target values")
    if np.linalg.matrix_rank(x) < 2 or not np.any(x):
        raise ValueError("support has zero or rank-deficient structural features")
    coefficients, residual_norm = nnls(x, y)
    return {
        "model": "nonnegative_mean",
        "coefficient_semantics": "DHL-response rates",
        "rates": {"persons": float(coefficients[0]), "companies": float(coefficients[1])},
        "predicted": x @ coefficients,
        "residual_norm": float(residual_norm),
    }


def _metrics(actual: np.ndarray, predicted: np.ndarray) -> dict:
    error = predicted - actual
    denominator = float(np.abs(actual).sum())
    return {
        "mae": float(np.abs(error).mean()),
        "wmape": None if denominator == 0 else float(np.abs(error).sum() / denominator),
        "bias": float(error.mean()),
    }


def compare_structure_models(support: pd.DataFrame, group_col: str = "plz", folds=5) -> dict:
    """Compare structural candidates on complete spatial holdouts only.

    All feature coding is fitted in a training fold.  Results do not declare a
    winner when no evaluable held-out group exists.
    """
    persons, companies, target = _support_columns(support)
    if group_col not in support:
        raise ValueError(f"support missing grouping column: {group_col}")
    frame = support.reset_index(drop=True).copy()
    groups = pd.Index(frame[group_col].dropna().unique()).sort_values()
    if isinstance(folds, int):
        if folds < 2:
            raise ValueError("folds must be at least two")
        chunks = [set(chunk) for chunk in np.array_split(groups.to_numpy(), min(folds, len(groups))) if len(chunk)]
        fold_pairs = [(set(groups).difference(test), test) for test in chunks if set(groups).difference(test)]
    else:
        fold_pairs = [(set(train), set(test)) for train, test in folds]
    outcomes: dict[str, list[dict]] = defaultdict(list)
    stability: list[dict] = []
    employee_stability: list[dict] = []

    def employee_design(frame: pd.DataFrame, branches: list[str]) -> np.ndarray:
        employees = pd.to_numeric(frame["employees"], errors="coerce").to_numpy(float)
        if not np.isfinite(employees).all() or (employees < 0).any():
            raise ValueError("employee candidate requires finite nonnegative employees")
        values = [frame[persons].to_numpy(float)]
        labels = frame.get("branch", pd.Series("unclassified", index=frame.index)).fillna("unclassified").astype(str)
        values.extend(np.where(labels.eq(branch), employees, 0.) for branch in branches)
        return np.column_stack(values)

    for train_groups, test_groups in fold_pairs:
        train = frame.loc[frame[group_col].isin(train_groups)]
        test = frame.loc[frame[group_col].isin(test_groups)]
        if train.empty or test.empty:
            continue
        actual = test[target].to_numpy(float)
        q75 = historical_quantreg_predict(test[persons], test[companies])
        outcomes["historical_q75"].append(_metrics(actual, q75))
        fit = fit_nonnegative_mean(train.rename(columns={persons: "persons", companies: "companies", target: "target"}))
        mean_prediction = test[[persons, companies]].to_numpy(float) @ np.array(
            [fit["rates"]["persons"], fit["rates"]["companies"]]
        )
        outcomes["nonnegative_mean"].append(_metrics(actual, mean_prediction))
        stability.append(fit["rates"])
        if "employees" in frame:
            branches = sorted(train.get("branch", pd.Series("unclassified", index=train.index)).fillna("unclassified").astype(str).unique())
            x_train, x_test = employee_design(train, branches), employee_design(test, branches)
            y_train = train[target].to_numpy(float)
            if np.linalg.matrix_rank(x_train) == x_train.shape[1]:
                coefficients, _ = nnls(x_train, y_train)
                outcomes["employee_branch_candidate"].append(_metrics(actual, x_test @ coefficients))
                employee_stability.append(dict(zip(["persons"] + [f"employees:{branch}" for branch in branches], coefficients)))
    models = {}
    for name, rows in outcomes.items():
        models[name] = {
            "holdouts": len(rows),
            "metrics": {key: (None if not rows else float(np.mean([row[key] for row in rows if row[key] is not None]))
                        if any(row[key] is not None for row in rows) else None)
                        for key in ("mae", "wmape", "bias")},
        }
    if stability:
        values = pd.DataFrame(stability)
        models["nonnegative_mean"]["coefficient_stability"] = values.std(ddof=0).to_dict()
    if employee_stability and "employee_branch_candidate" in models:
        values = pd.DataFrame(employee_stability).fillna(0.)
        models["employee_branch_candidate"]["coefficient_semantics"] = "DHL-response rates"
        models["employee_branch_candidate"]["coefficient_stability"] = values.std(ddof=0).to_dict()
    return {"group_col": group_col, "models": models, "wmape_zero_actual": None,
            "selection": "not_demonstrated" if not outcomes else "comparison_only"}


def build_contiguous_groups(units: pd.DataFrame, edges: pd.DataFrame, min_persons, min_companies,
                            max_units) -> pd.DataFrame:
    """Create deterministic topology-only calibration groups from whole units."""
    required = {"unit_id", "plz", "persons", "companies"}
    if missing := required.difference(units.columns):
        raise ValueError(f"units missing required columns: {sorted(missing)}")
    if not units.unit_id.is_unique or max_units < 1 or min_persons < 0 or min_companies < 0:
        raise ValueError("units must be unique and grouping limits valid")
    left, right = (("left", "right") if {"left", "right"}.issubset(edges.columns)
                   else ("source", "target") if {"source", "target"}.issubset(edges.columns) else (None, None))
    if left is None:
        raise ValueError("edges require left/right or source/target columns")
    index = units.set_index("unit_id")
    adjacency = {unit: set() for unit in index.index}
    for a, b in edges[[left, right]].itertuples(index=False):
        if a not in adjacency or b not in adjacency:
            raise ValueError("edges may only reference known units")
        if index.at[a, "plz"] == index.at[b, "plz"]:
            adjacency[a].add(b); adjacency[b].add(a)
    seen, records = set(), []
    for seed in sorted(adjacency, key=str):
        if seed in seen:
            continue
        component, queue = [], deque([seed]); seen.add(seed)
        while queue:
            unit = queue.popleft(); component.append(unit)
            for neighbour in sorted(adjacency[unit], key=str):
                if neighbour not in seen:
                    seen.add(neighbour); queue.append(neighbour)
        component = sorted(component, key=str)
        total_persons = float(index.loc[component, "persons"].sum())
        total_companies = float(index.loc[component, "companies"].sum())
        status = "resolved" if (len(component) <= max_units and total_persons >= min_persons
                                  and total_companies >= min_companies) else (
                                      "unresolved_max_units" if len(component) > max_units else "unresolved_structural_support")
        group_id = f"{index.at[component[0], 'plz']}:{component[0]}"
        records.extend({"unit_id": unit, "group_id": group_id, "group_status": status}
                       for unit in component)
    return units.merge(pd.DataFrame(records), on="unit_id", how="left", validate="one_to_one")
