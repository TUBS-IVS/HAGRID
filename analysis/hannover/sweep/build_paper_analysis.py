# -*- coding: utf-8 -*-
"""Paper-facing KPI tables + regressions over the Hannover capacity sweep
replicates v2 / v3 / v4 -- three reseed draws on identical model code, so their
spread is the sweep's uncertainty estimate rather than a version comparison.

Reads sweep_data.json (written by extract_sweep.py) and emits into the EWGT26
paper folder:

  kpi_v2_v3_v4.csv                one row per capacity, KPI x series columns
  regressions_v2_v3_v4.csv        one row per fit: parameters, RMSE, R2
  utilization_distribution_v2.csv per-tour load factors at cap 40 / 170 / 300
  README.md                       conventions, units and the cost caveat

Run:  python -u build_paper_analysis.py
"""
import csv
import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
from scipy.optimize import curve_fit

from extract_sweep import V2_DIR, board, slice_json_array

HERE = Path(__file__).resolve().parent
OUT = Path(r"C:\Users\Hendrik Bimmermann\Documents\Dokumente\Research\Paper"
           r"\0326_EWGT26\Analysis_v2_v3_v4")

SERIES = ("v2", "v3", "v4")          # v1 is a different code version, deliberately out
MEAN = "mean"
ALL_SERIES = SERIES + (MEAN,)
BASE_CAP = 30                        # normalisation anchor: every series' own c=30 == 100 %
UTIL_DIST_CAPS = (40, 170, 300)      # user pick: capacity wall / crossover / slack regime
UTIL_DIST_SERIES = "v2"

# KPI columns of kpi_v2_v3_v4.csv, in order. `norm` marks the four normalised
# twins; utilisation and the two shares are carried in PERCENT because that is
# the unit every criterion in this study is stated in (>90 % util, c=30 = 100 %).
KPIS = [
    ("parcels_per_vehicle", "parcels/vehicle"),
    ("utilization_pct", "%"),
    ("cost_eur", "EUR"),
    ("fleet_size", "vehicles"),
    ("tour_h", "h"),
    ("tour_km", "km"),
    ("share_worktime_pct", "%"),
    ("share_capa_pct", "%"),
    ("cost_norm_pct", "% of c=30"),
    ("tour_h_norm_pct", "% of c=30"),
    ("tour_km_norm_pct", "% of c=30"),
    ("fleet_norm_pct", "% of c=30"),
]
NORM_OF = {"cost_norm_pct": "cost_eur", "tour_h_norm_pct": "tour_h",
           "tour_km_norm_pct": "tour_km", "fleet_norm_pct": "fleet_size"}


# --- model forms -------------------------------------------------------------
# Three-parameter exponential by user decision: fleet size, tour-h and tour-km
# all level off at a plateau (fleet ~660 vehicles), so a two-parameter a*exp(-bc)
# forced through zero misfits the whole upper half of the capacity range.

def f_linear(c, a, b):
    return a * c + b


def f_exp3(c, a, b, d):
    return a * np.exp(-b * c) + d


def f_logistic(c, L, k, c0):
    return L / (1.0 + np.exp(-k * (c - c0)))


def f_satexp(c, a, b, c0):
    return a * (1.0 - np.exp(-b * (c - c0)))


FORMS = {
    "linear":   (f_linear,   ("a", "b"),      "y = a*c + b"),
    "exp3":     (f_exp3,     ("a", "b", "d"), "y = a*exp(-b*c) + d"),
    "logistic": (f_logistic, ("L", "k", "c0"), "y = L / (1 + exp(-k*(c - c0)))"),
    "satexp":   (f_satexp,   ("a", "b", "c0"), "y = a*(1 - exp(-b*(c - c0)))"),
}

# KPI -> form. The four exponential KPIs are additionally fitted on their
# normalised twin (see the b-invariance note in the README).
KPI_FORM = {
    "utilization_pct": "linear",
    "cost_eur": "exp3", "fleet_size": "exp3", "tour_h": "exp3", "tour_km": "exp3",
    "cost_norm_pct": "exp3", "tour_h_norm_pct": "exp3",
    "tour_km_norm_pct": "exp3", "fleet_norm_pct": "exp3",
    "share_worktime_pct": "logistic", "share_capa_pct": "logistic",
    "parcels_per_vehicle": "satexp",
}


def initial_guess(form, c, y):
    """Starting values matter here: exp3 and logistic both diverge from a naive
    all-ones p0 on data spanning three orders of magnitude (cost 672k -> 141k)."""
    if form == "linear":
        return [(y[-1] - y[0]) / (c[-1] - c[0]), y[0]]
    if form == "exp3":
        return [y[0] - y[-1], 0.02, y[-1]]
    if form == "logistic":
        L = max(y.max() * 1.05, 1e-6)
        k = 0.05 if y[-1] >= y[0] else -0.05
        half = c[int(np.argmin(np.abs(y - L / 2.0)))]
        return [L, k, float(half)]
    if form == "satexp":
        return [y.max() * 1.05, 0.01, 0.0]
    raise ValueError(form)


def fit(form, c, y):
    fn, names, _ = FORMS[form]
    p0 = initial_guess(form, c, y)
    popt, _ = curve_fit(fn, c, y, p0=p0, maxfev=200000)
    resid = y - fn(c, *popt)
    rmse = float(np.sqrt(np.mean(resid ** 2)))
    ss_tot = float(np.sum((y - y.mean()) ** 2))
    r2 = 1.0 - float(np.sum(resid ** 2)) / ss_tot if ss_tot > 0 else float("nan")
    return dict(zip(names, (float(p) for p in popt))), rmse, r2


# --- KPI table ---------------------------------------------------------------

def build_kpi_table(data):
    """cap -> series -> {kpi: value}. Shares are INCLUSIVE (user decision): a
    tour over 7 h counts as worktime-limited whether or not it is also full,
    so the two shares overlap and may sum past 100 %."""
    runs = [r for r in data["runs"] if r["series"] in SERIES and r["replicate"] is None]
    caps = sorted({r["cap"] for r in runs})
    raw = {}
    for r in runs:
        k, li = r["kpis"], r["limits"]
        tours = li["total_tours"]
        raw[(r["series"], r["cap"])] = {
            "parcels_per_vehicle": k["parcels"] / k["vehicles"],
            "utilization_pct": k["utilization"] * 100.0,
            "cost_eur": k["cost_eur"],
            "fleet_size": float(k["vehicles"]),
            "tour_h": k["tour_h"],
            "tour_km": k["tour_km"],
            "share_worktime_pct": (li["worktime_only"] + li["both"]) / tours * 100.0,
            "share_capa_pct": (li["capa_only"] + li["both"]) / tours * 100.0,
        }

    for s in SERIES:
        for cap in caps:
            row = raw[(s, cap)]
            for norm_col, src in NORM_OF.items():
                row[norm_col] = row[src] / raw[(s, BASE_CAP)][src] * 100.0

    # The mean series: raw KPIs are averaged directly; the normalised twins are
    # the mean OF THE NORMALISED per-series values, so the mean row stays
    # consistent with the rows above it and is exactly 100 % at c=30.
    for cap in caps:
        raw[(MEAN, cap)] = {
            name: float(np.mean([raw[(s, cap)][name] for s in SERIES]))
            for name, _ in KPIS
        }
    return caps, raw


def write_kpi_csv(caps, raw):
    cols = ["cap"] + [f"{name}_{s}" for name, _ in KPIS for s in ALL_SERIES]
    path = OUT / "kpi_v2_v3_v4.csv"
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(cols)
        for cap in caps:
            w.writerow([cap] + [round(raw[(s, cap)][name], 4)
                                for name, _ in KPIS for s in ALL_SERIES])
    print(f"  {path.name}: {len(caps)} rows x {len(cols)} cols")
    return path


# --- regressions -------------------------------------------------------------

def write_regression_csv(caps, raw):
    c = np.array(caps, dtype=float)
    rows = []
    for name, unit in KPIS:
        form = KPI_FORM[name]
        variant = "normalised" if name in NORM_OF else "raw"
        for s in ALL_SERIES:
            y = np.array([raw[(s, cap)][name] for cap in caps], dtype=float)
            try:
                params, rmse, r2 = fit(form, c, y)
                status = "ok"
            except Exception as exc:                       # noqa: BLE001
                params, rmse, r2, status = {}, float("nan"), float("nan"), f"FAILED: {exc}"
                print(f"  WARN fit failed: {name} {s} ({form}) -- {exc}")
            rows.append({
                "kpi": name, "series": s, "variant": variant, "form": form,
                "equation": FORMS[form][2], "unit_of_y": unit,
                "n_points": len(caps),
                "a": params.get("a", ""), "b": params.get("b", ""),
                "d": params.get("d", ""), "L": params.get("L", ""),
                "k": params.get("k", ""), "c0": params.get("c0", ""),
                "rmse": rmse, "rmse_unit": unit, "r2": r2, "status": status,
            })

    cols = ["kpi", "series", "variant", "form", "equation", "unit_of_y", "n_points",
            "a", "b", "d", "L", "k", "c0", "rmse", "rmse_unit", "r2", "status"]
    path = OUT / "regressions_v2_v3_v4.csv"
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for r in rows:
            w.writerow({k: (round(v, 6) if isinstance(v, float) else v)
                        for k, v in r.items()})
    ok = sum(1 for r in rows if r["status"] == "ok")
    print(f"  {path.name}: {len(rows)} fits ({ok} ok, {len(rows) - ok} failed)")
    return path, rows


# --- per-tour utilisation distribution --------------------------------------

def write_util_distribution():
    """Every tour's load factor at three capacities, one column each, sorted
    descending so the columns read as load-duration curves (order is irrelevant
    for a histogram, so sorting serves both uses). Columns differ in length by
    construction -- the fleet shrinks as capacity grows -- and are padded."""
    cols = {}
    for cap in UTIL_DIST_CAPS:
        tag = f"{cap}{UTIL_DIST_SERIES}"
        text = (V2_DIR / board(tag)).read_text(encoding="utf-8")
        vehs = [v for p in slice_json_array(text, "SUMMARY") for v in p["vehDetails"]]
        # Cross-check the dashboard's own loadFactor against parcels/cap rather
        # than trusting one of the two: a silent disagreement here would put a
        # plausible-looking but wrong distribution into the paper.
        recomputed = np.array([v["parcels"] / v["cap"] * 100.0 for v in vehs])
        reported = np.array([v["loadFactor"] for v in vehs])
        gap = float(np.max(np.abs(recomputed - reported)))
        if gap > 0.15:
            raise ValueError(f"{tag}: loadFactor vs parcels/cap disagree by {gap:.3f} pp")
        print(f"  cap {cap:<4} ({tag}): {len(vehs)} tours, "
              f"util {recomputed.min():.1f}-{recomputed.max():.1f} %, "
              f"loadFactor cross-check max gap {gap:.3f} pp")
        cols[cap] = np.sort(recomputed)[::-1]

    height = max(len(v) for v in cols.values())
    path = OUT / "utilization_distribution_v2.csv"
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["rank"] + [f"util_pct_c{c}" for c in UTIL_DIST_CAPS])
        for i in range(height):
            w.writerow([i + 1] + [round(float(cols[c][i]), 2) if i < len(cols[c]) else ""
                                  for c in UTIL_DIST_CAPS])
    print(f"  {path.name}: {height} rows, "
          f"counts {{{', '.join(f'c{c}: {len(v)}' for c, v in cols.items())}}}")
    return path, {c: len(v) for c, v in cols.items()}


# --- README ------------------------------------------------------------------

def write_readme(caps, counts, rows):
    failed = [r for r in rows if r["status"] != "ok"]
    path = OUT / "README.md"
    path.write_text(f"""# Hannover capacity sweep -- v2 / v3 / v4 analysis

Generated {date.today().isoformat()} by `build_paper_analysis.py`
(HAGRID repo, `analysis/hannover/sweep/`).
Source: the per-run Java LMD dashboards, via `extract_sweep.py` -> `sweep_data.json`.

## What the three series are

v2, v3 and v4 are **reseed replicates on identical model code**, not model
versions: the run tag enters `runId.hashCode()`, which seeds the demand-layer
RNG and `CarrierVehicleFactory`. Their spread is therefore the sweep's
uncertainty estimate. v1 is a different code generation (before the
`CarrierServiceMerger` capacity split) and is deliberately **not** included.

Capacities {caps[0]}-{caps[-1]} step 10, {len(caps)} points per series, all three complete (38/38).

## Files

| File | Shape |
|---|---|
| `kpi_v2_v3_v4.csv` | one row per capacity, columns `<kpi>_<series>` for v2/v3/v4/mean |
| `regressions_v2_v3_v4.csv` | one row per fit ({len(rows)} fits), parameters + RMSE + R2 |
| `utilization_distribution_v2.csv` | per-tour load factors at c=40/170/300 (v2 only) |

## Conventions

- **Normalisation** (`*_norm_pct`): each series against **its own** c={BASE_CAP}, which is
  therefore exactly 100 %. The `mean` column is the mean of the normalised
  per-series values, so it is 100 % at c={BASE_CAP} too and stays consistent with the
  three series columns beside it.
- **Shares are inclusive/overlapping**: `share_worktime_pct` counts every tour
  with durH > 7.0 h, `share_capa_pct` every tour with parcels > 0.9*cap,
  independently of each other. Tours that are both are counted in both columns,
  so the two shares may sum past 100 %. (The four-class exclusive partition
  behind them lives in `sweep_kpis.csv` in the repo.)
- **Utilisation**: every run uses a single vehicle type, so total parcels /
  total capacity and the mean of the per-tour load factors are the same number.
- **Utilisation distribution**: sorted descending, columns of different length
  (the fleet shrinks with capacity), padded with blanks. Counts:
  {', '.join(f'c={c}: {n} tours' for c, n in counts.items())}.

## Regression forms

| KPI | Form |
|---|---|
| `utilization_pct` | linear `a*c + b` |
| `cost_eur`, `fleet_size`, `tour_h`, `tour_km` | `a*exp(-b*c) + d`, raw **and** normalised |
| `share_worktime_pct`, `share_capa_pct` | logistic `L/(1 + exp(-k*(c - c0)))` |
| `parcels_per_vehicle` | shifted saturating `a*(1 - exp(-b*(c - c0)))` |

RMSE is in the KPI's own unit (column `rmse_unit`); R2 is reported alongside.
{"All fits converged." if not failed else
 "FAILED FITS: " + ", ".join(f"{r['kpi']}/{r['series']}" for r in failed)}

### The normalised exponential fits are not independent information

Normalising divides a series by a constant (its c={BASE_CAP} value). For
`y = a*exp(-b*c) + d` that scales `a`, `d` and the RMSE by 1/y({BASE_CAP}) and leaves
**`b` mathematically unchanged**. The raw and normalised rows for the same KPI
and series therefore carry the same decay constant by construction -- read the
pair as one fit in two units, not as two findings.

## Cost caveat -- read before quoting any EUR figure

`cost_eur` is the **legacy dashboard cost** (user decision 2026-08-25):
`costFix + costDist + costOT`, verified on four boards. Overtime is included
(0.3 % of the total at c=30, 3.8 % at c=230, 2.3 % at c=400); `costAct` and
`costTimeWindowPenalty` are outside the sum, as the dashboard defines it.

**The known bias** (METHODS-LOG 2.33): about 88 % of that sum is a
`fixedCostsPerDay` of 189.15 EUR charged **per tour regardless of tour duration**,
while `costsPerSecond = 0` means nothing scales with time. The cost curve
therefore tracks the **tour count**, not the tour hours, which systematically
overprices the low-capacity end -- exactly the end this sweep exists to measure.
A two-term reconstruction (40 EUR/day vehicle + 21.31 EUR/h labour, level-anchored
at 7 h) puts c=30 about 52 % lower and c=280 about 1.7 % lower, cutting the
headline c=30 -> c=280 saving from -78.8 % to -56.7 %. That correction is
**decided but deliberately not applied here**.

**Unaffected by this**: the two share KPIs and the ~170 worktime/capacity
crossover. They test only `durH > 7.0` and `parcels > 0.9*cap` -- no euro enters.

A second, separate caveat: every run in this sweep was produced with jsprit
`BEST_INSERTION`, not the later `REGRET_INSERTION` fix, so absolute tour counts
carry that bias. It is constant across the sweep, so cross-capacity comparisons
hold; absolute levels should not be quoted as optimal.
""", encoding="utf-8")
    print(f"  {path.name}")
    return path


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    data = json.loads((HERE / "sweep_data.json").read_text(encoding="utf-8"))
    print(f"sweep_data.json extracted {data['extracted']}, {len(data['runs'])} runs")

    caps, raw = build_kpi_table(data)
    missing = [(s, c) for s in SERIES for c in caps if (s, c) not in raw]
    if missing:
        raise ValueError(f"incomplete series, missing {missing}")
    print(f"\nSeries {SERIES} complete over {len(caps)} capacities "
          f"{caps[0]}..{caps[-1]}\n")

    write_kpi_csv(caps, raw)
    _, rows = write_regression_csv(caps, raw)
    _, counts = write_util_distribution()
    write_readme(caps, counts, rows)
    print(f"\nOK -> {OUT}")


if __name__ == "__main__":
    sys.exit(main())
