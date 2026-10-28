# -*- coding: utf-8 -*-
"""Robustness of the capacity/worktime transition point (EWGT26 Fig. 1e) to the two
classification thresholds. Re-reads the per-tour durH/parcels/cap of the v2-v4 sweep
boards, recomputes the inclusive shares for each threshold pair, refits the logistic
curves with the start values of build_paper_analysis.py and reports their intersection.
Control: the 7.0 h / 90 % row must reproduce kpi_v2_v3_v4.csv and its fits.

Run:  python -u transition_robustness.py      (~4 min, reads 114 dashboards)
"""
import csv
from pathlib import Path

import numpy as np
from scipy.optimize import brentq, curve_fit

import extract_sweep as ex

PAPER = Path(r"C:\Users\Hendrik Bimmermann\Documents\Dokumente\Research\Paper\0326_EWGT26\Analysis_v2_v3_v4")
SERIES = ("v2", "v3", "v4")
Z = np.log(7 / 3)  # 30-70 % response half-width in units of 1/|k|

RUNS = []
for series, cap, rep, path in ex.run_list():
    if series not in SERIES:
        continue
    vehs = [v for p in ex.slice_json_array(path.read_text(encoding="utf-8"), "SUMMARY")
            for v in p["vehDetails"]]
    RUNS.append({"series": series, "cap": cap,
                 "durH": np.array([v["durH"] for v in vehs]),
                 "parcels": np.array([v["parcels"] for v in vehs]),
                 "vcap": np.array([v["cap"] for v in vehs])})


def logistic(c, L, k, c0):
    return L / (1.0 + np.exp(-k * (c - c0)))


def fit(c, y):  # same start values as build_paper_analysis.initial_guess
    L = max(y.max() * 1.05, 1e-6)
    k = 0.05 if y[-1] >= y[0] else -0.05
    half = c[int(np.argmin(np.abs(y - L / 2.0)))]
    p, _ = curve_fit(logistic, c, y, p0=[L, k, float(half)], maxfev=200000)
    return p


def shares(h, frac, strict=True):
    """cap -> series -> (time %, capa %), inclusive shares, mean of ratios.
    strict=False counts parcels >= frac*cap; needed at 100 %, where '>' is empty by construction."""
    out = {}
    for r in RUNS:
        lim = frac * r["vcap"]
        capa = (r["parcels"] > lim) if strict else (r["parcels"] >= lim)
        out.setdefault(r["cap"], {})[r["series"]] = (100 * np.mean(r["durH"] > h), 100 * np.mean(capa))
    return out


def analyse(sh, series=SERIES):
    caps = np.array(sorted(sh), dtype=float)
    t = np.array([np.mean([sh[c][s][0] for s in series]) for c in sorted(sh)])
    k = np.array([np.mean([sh[c][s][1] for s in series]) for c in sorted(sh)])
    pt, pc = fit(caps, t), fit(caps, k)
    g = lambda c: logistic(c, *pt) - logistic(c, *pc)
    x_fit = brentq(g, 30, 400) if g(30) * g(400) < 0 else float("nan")
    d = t - k
    i = np.where(np.sign(d[:-1]) != np.sign(d[1:]))[0]
    x_raw = (caps[i[0]] + (caps[i[0] + 1] - caps[i[0]]) * (-d[i[0]]) / (d[i[0] + 1] - d[i[0]])
             if len(i) else float("nan"))
    zt = (pt[2] - Z / abs(pt[1]), pt[2] + Z / abs(pt[1]))
    zc = (pc[2] - Z / abs(pc[1]), pc[2] + Z / abs(pc[1]))
    return dict(x_fit=x_fit, x_raw=x_raw, pt=pt, pc=pc, zt=zt, zc=zc, t=t, k=k, caps=caps,
                y_at_x=logistic(x_fit, *pt) if x_fit == x_fit else float("nan"))


# --- positive control: 7.0 h / 90 % against the paper CSVs ---------------------
base = shares(7.0, 0.9)
a = analyse(base)
with open(PAPER / "kpi_v2_v3_v4.csv", encoding="utf-8") as f:
    rows = {int(r["cap"]): r for r in csv.DictReader(f)}
dt = max(abs(a["t"][i] - float(rows[int(c)]["share_worktime_pct_mean"])) for i, c in enumerate(a["caps"]))
dc = max(abs(a["k"][i] - float(rows[int(c)]["share_capa_pct_mean"])) for i, c in enumerate(a["caps"]))
print(f"CONTROL shares vs kpi csv: max|d| time={dt:.2e} pp  capa={dc:.2e} pp")
print(f"CONTROL fit time L={a['pt'][0]:.4f} k={a['pt'][1]:.6f} c0={a['pt'][2]:.4f}  (csv 69.676969 0.075554 162.563669)")
print(f"CONTROL fit capa L={a['pc'][0]:.4f} k={a['pc'][1]:.6f} c0={a['pc'][2]:.4f}  (csv 86.728152 -0.030105 171.70469)")

# --- scenarios -------------------------------------------------------------------
SCEN = [("6.5 h / 85 %", 6.5, 0.85, True), ("7.0 h / 90 % (paper)", 7.0, 0.90, True),
        ("7.5 h / 95 %", 7.5, 0.95, True), ("8.0 h / 100 % (>= cap)", 8.0, 1.00, False),
        ("8.0 h / 100 % (> cap, strict)", 8.0, 1.00, True)]
print("\nscenario                        x_fit  x_raw  share@x  per-series x_fit       time zone      capa zone      L_time L_capa")
for name, h, fr, strict in SCEN:
    sh = shares(h, fr, strict)
    try:
        a = analyse(sh)
        per = []
        for s in SERIES:
            try:
                per.append(analyse(sh, (s,))["x_fit"])
            except Exception:
                per.append(float("nan"))
        print(f"{name:30s} {a['x_fit']:6.1f} {a['x_raw']:6.1f} {a['y_at_x']:6.1f}   "
              f"{per[0]:6.1f}/{per[1]:6.1f}/{per[2]:6.1f}   {a['zt'][0]:5.0f}-{a['zt'][1]:4.0f}    {a['zc'][0]:5.0f}-{a['zc'][1]:4.0f}    "
              f"{a['pt'][0]:5.1f}  {a['pc'][0]:5.1f}")
    except Exception as e:
        print(f"{name:30s} FIT FAILED: {e}")

# --- one-at-a-time decomposition grid (x_fit) --------------------------------------
print("\nx_fit grid: rows = worktime threshold, cols = capacity threshold (100 % as >= cap)")
FR = [0.85, 0.90, 0.95, 1.00]
print("        " + "".join(f"{int(f*100):>8d}%" for f in FR))
for h in (6.5, 7.0, 7.5, 8.0):
    cells = []
    for fr in FR:
        try:
            cells.append(f"{analyse(shares(h, fr, fr < 1.0))['x_fit']:9.1f}")
        except Exception:
            cells.append("     fail")
    print(f"{h:5.1f} h " + "".join(cells))

# --- raw mean shares at a few caps for the four requested pairs ------------------------
print("\nmean shares (time / capa, %) at selected caps")
for name, h, fr, strict in SCEN[:4]:
    sh = shares(h, fr, strict)
    print(f"{name:24s} " + "  ".join(f"c{c}: {np.mean([sh[c][s][0] for s in SERIES]):4.1f}/{np.mean([sh[c][s][1] for s in SERIES]):4.1f}"
                                     for c in (30, 100, 150, 170, 200, 250, 400)))
