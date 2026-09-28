"""Read-only notebook probes and aggregate input profiling. Run from any directory.

Executes selected function definitions, never notebook top-level code or exports.
Writes only docs/demand-audit/verification.json. No personal records are exported.
"""
from __future__ import annotations

import ast
import datetime as dt
import hashlib
import json
import logging
from pathlib import Path
import sys
import warnings

import geopandas as gpd
import numpy as np
import pandas as pd
from shapely import from_wkt
from shapely.geometry import Point, box
from sklearn.linear_model import LinearRegression

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BASE = ROOT / "parcel-demand-estimation"
REPORT = {"scope": "isolated function probes and existing artifact profiles; no full notebook run", "probes": {}, "profiles": {}}
NS = {"np": np, "pd": pd, "gpd": gpd, "logging": logging}


def source(stem):
    nb = json.loads((BASE / (stem + ".ipynb")).read_text(encoding="utf-8"))
    return "\n".join("".join(c["source"]) for c in nb["cells"] if c["cell_type"] == "code")


def function(stem, name):
    node = next(n for n in ast.parse(source(stem)).body if isinstance(n, ast.FunctionDef) and n.name == name)
    exec(compile(ast.Module(body=[node], type_ignores=[]), stem, "exec"), NS)
    return NS[name]


def record(name, expected, actual):
    REPORT["probes"][name] = {"expected": expected, "actual": actual, "defect_reproduced": expected != actual}


def probes():
    n06 = "06_DistributeEstimationWeightsPerSegment"
    gen = "ParcelDemandScenarioGenerator"
    weights = function(n06, "assign_b2c_b2b_weights_with_total")
    buffers = gpd.GeoDataFrame(geometry=[box(0, 0, 10, 10)], crs=25832)
    outside = gpd.GeoDataFrame({"employees": [5]}, geometry=[Point(20, 20)], crs=25832)
    result = weights(buffers, outside, outside, pd.DataFrame(), alpha=1, beta=.1)
    record("empty_spatial_join_counts", [0, 0], [int(result.person_sum.iloc[0]), int(result.company_count.iloc[0])])

    overlap = gpd.GeoDataFrame(geometry=[box(0, 0, 10, 10), box(5, 0, 15, 10)], crs=25832)
    person = gpd.GeoDataFrame({"employees": [5]}, geometry=[Point(7, 5)], crs=25832)
    result = weights(overlap, person, person, pd.DataFrame(), alpha=1, beta=.1)
    record("one_person_overlapping_buffers", 1, int(result.person_sum.sum()))

    boost = function(n06, "apply_dhl_boost_to_weights")
    base = pd.DataFrame({"str_idx": [0, 0, 1, 1], "dhl_weight": [10., 10., 10., 10.], "b2b_weight": [1., 1., 1., 1.]})
    result = boost(base, min_tag=300)
    record("ignored_min_tag_300", 4., float(result.b2b_weight.sum()))

    b2b = pd.read_csv(BASE / "output/01_b2b_forecast_complete.csv")
    record("stage04_input_schema", [], sorted(set(["Jahr", "Ist_B2B", "Typ"]) - set(b2b.columns)))
    grid = pd.read_csv(BASE / "input/final_grid_250_region_results_update.csv")
    record("stage04_source_copy_has_normalized_ratio", True, "b2b_ratio_norm" in grid.columns)

    first = pd.date_range(start="2025-01-04", periods=52, freq="W-MON")[0].date()
    record("iso_week1_2025", str(dt.date.fromisocalendar(2025, 1, 1)), str(first))
    record("weekday_share_sum", 1., round(sum([.16, .17, .19, .18, .15, .115]), 6))
    record("carrier_rounding_one_parcel", 1, sum(round(1 * s) for s in [.5, .5]))

    transfer = function(gen, "pre_adjust_b2b_distribution")
    frame = pd.DataFrame({"A_B2B": [1, 1, 0], "A_B2C": [1, 1, 2], "b2b_target": [.2, .2, .6]})
    result = transfer(frame, ["A"], np.random.default_rng(42))
    record("b2b_transfer_conservation", 2, int(result.A_B2B.sum()))

    split = function(gen, "split_b2b_b2c_to_target")
    frame = pd.DataFrame({"n": [10]})
    split(frame, "n", 1.08, "B2B", "B2C")
    record("b2b_scaled_share_nonnegative", True, bool((frame.B2C >= 0).all()))

    fill = function(gen, "final_adjust_b2b_distribution")
    frame = pd.DataFrame({"sum_total": [0], "total_coun": [10], "target": [.5], "shares": [{"A": 1.}], "A_B2B": [0], "A_B2C": [0]})
    result = fill(frame, "target", "shares", 1.)
    record("final_fill_conserves_total", 0, int(result.A_B2B.sum() + result.A_B2C.sum()))

    ci = function("02_EstimateGlobalGermanParcelVolumens", "compute_linear_ci")
    x = np.arange(10.).reshape(-1, 1)
    model = LinearRegression().fit(x, 2 * x.ravel() + 3)
    _, lo, hi = ci(model, x)
    record("perfect_linear_fit_ci_halfwidth", 0., round(float((hi[5] - lo[5]) / 2), 6))


def profiles():
    p = REPORT["profiles"]
    grid = pd.read_csv(BASE / "input/final_grid_250_region_results_update.csv")
    p["grid"] = {"rows": len(grid), "unique_cells": int(grid.cell_id.nunique()), "zero_volume": int((grid.total_coun == 0).sum()), "zero_volume_with_people_or_companies": int(((grid.total_coun == 0) & ((grid.person_cou > 0) | (grid.company_co > 0))).sum()), "b2b_unique_count": int(grid.b2b_ratio.nunique()), "b2b_min_max": [float(grid.b2b_ratio.min()), float(grid.b2b_ratio.max())], "negative_b2b_positive_volume": int(((grid.b2b_ratio < 0) & (grid.total_coun > 0)).sum()), "legacy_people_in_zero_volume_cells": float(grid.loc[grid.total_coun == 0, "person_cou"].sum()), "legacy_companies_in_zero_volume_cells": float(grid.loc[grid.total_coun == 0, "company_co"].sum()), "fit_counts": grid.fit.value_counts().to_dict()}
    companies = gpd.read_file(BASE / "input/companies_Total_reduced.shp", columns=["id", "employees", "branch", "type"])
    emp = pd.to_numeric(companies.employees, errors="coerce")
    p["companies"] = {"rows": len(companies), "unique_ids": int(companies.id.nunique()), "crs": str(companies.crs), "branch_categories": int(companies.branch.nunique()), "branch_missing": int(companies.branch.isna().sum()), "employees_missing": int(emp.isna().sum()), "employees_negative": int((emp < 0).sum()), "employees_zero": int((emp == 0).sum()), "employees_sum": float(emp.sum())}
    persons = pd.read_csv(BASE / "input/persons_total.csv", usecols=["id", "Household", "Building"], dtype=str)
    p["persons_csv"] = {"rows": len(persons), "unique_person_ids": int(persons.id.nunique()), "unique_households": int(persons.Household.nunique()), "unique_buildings": int(persons.Building.nunique()), "missing_households": int(persons.Household.isna().sum()), "missing_buildings": int(persons.Building.isna().sum())}

    for folder in ["parcel-demand-estimation", "parcel-demand-estimation-batch"]:
        output = ROOT / folder / "output"
        weekly = pd.read_csv(output / "03_yearly_weekly_parcel_forecast.csv", parse_dates=["Date"])
        annual = pd.read_csv(output / "02_parcel_volumen_estimation_complete.csv")
        samples = pd.read_csv(output / "06_street_samples_with_weights.csv")
        cells = pd.read_csv(output / "05_ga_corrected_b2b_with_marked_adjust_gdf.csv")
        points = gpd.GeoDataFrame(samples[["sample_idx"]], geometry=from_wkt(samples.point_geom.to_numpy()), crs=25832)
        areas = gpd.GeoDataFrame(cells[["cell_id", "total_coun"]], geometry=from_wkt(cells.geometry.to_numpy()), crs=25832)
        joined = gpd.sjoin(points, areas, how="left", predicate="intersects")
        missing_cells = areas.loc[~areas.cell_id.isin(joined.cell_id.dropna())]
        ratio = weekly.groupby("Year").linear_Prognose.mean() / annual.set_index("year").linear
        p[folder] = {"samples": len(samples), "cells": len(cells), "unassigned_samples": int(joined.index_right.isna().sum()), "extra_join_rows": len(joined) - len(samples), "cells_without_sample": len(missing_cells), "legacy_weight_in_cells_without_sample": float(missing_cells.total_coun.sum()), "all_legacy_cell_weight": float(cells.total_coun.sum()), "weekly_to_annual_mean_ratio_min": float(ratio.min()), "weekly_to_annual_mean_ratio_max": float(ratio.max()), "years_without_iso_week1": [int(y) for y, g in weekly.groupby("Year") if 1 not in g.Date.dt.isocalendar().week.values], "estimated_years_marked_observed": annual.loc[(annual.year >= 2024) & (annual.year <= 2028), ["year", "type"]].to_dict("records")}
        iso = weekly.Date.dt.isocalendar()
        p[folder]["rows_with_wrong_iso_year"] = int((iso.year != weekly.Year).sum())
        p[folder]["years_without_own_iso_week1"] = [int(y) for y, group in weekly.groupby("Year") if not ((group.Date.dt.isocalendar().week == 1) & (group.Date.dt.isocalendar().year == y)).any()]

    manifest = []
    for folder in ["parcel-demand-estimation", "parcel-demand-estimation-batch"]:
        for file in sorted((ROOT / folder).glob("*.ipynb")):
            nb = json.loads(file.read_text(encoding="utf-8"))
            code = "\n".join("".join(c["source"]) for c in nb["cells"] if c["cell_type"] == "code")
            manifest.append({"notebook": file.relative_to(ROOT).as_posix(), "sha256_file": hashlib.sha256(file.read_bytes()).hexdigest(), "sha256_code": hashlib.sha256(code.encode()).hexdigest(), "code_cells": sum(c["cell_type"] == "code" for c in nb["cells"])})
    REPORT["notebooks"] = manifest
    REPORT["runtime"] = {"python": sys.version.split()[0], "pandas": pd.__version__, "numpy": np.__version__, "geopandas": gpd.__version__}


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", FutureWarning)
        probes()
        profiles()
    (HERE / "verification.json").write_text(json.dumps(REPORT, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in REPORT.items() if k != "notebooks"}, ensure_ascii=False, indent=2))
