# -*- coding: utf-8 -*-
# analysis/kpi/emissions_emep.py
"""EMEP/EEA Tier-3 emission factor evaluation for the Lausitz KPI stack.

Deliberately independent of src/hagrid_output_analysis/emissions.py (that
module backs a colleague's published paper and is frozen; user decision
2026-07-28). NOTE the methodological difference: that module uses STREAM
(empty, full) factor pairs interpolated by load_pct, this one uses EMEP/EEA
speed curves with the mass effect carried by the N1 SEGMENT and no load
dimension (guidebook restricts load correction to HDV). The two are
independent sources and must NOT be blended -- see the plan's Global
Constraints. Factor data lives in data/*.csv with full provenance columns.

Curve form (COPERT v5, validated against the Appendix-4 EF(v=80) check
column in test_emissions_emep.py):
    EF(v) = (alpha*v^2 + beta*v + gamma + delta/v)
            / (epsilon*v^2 + zita*v + hta) * (1 - rf)
with v clamped to [vmin, vmax]. rf is a FRACTION (xlsx header says '[%]'
but stores 0.282175 for 28.2 %).
"""
import csv
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent / "data"

_NUM = ("alpha", "beta", "gamma", "delta", "epsilon", "zita", "hta",
        "rf", "vmin", "vmax", "ef_check_v", "ef_check")


def load_factors(data_dir=None):
    """-> {"diesel": {segment: {pollutant: coef}}, "bev": {...}, "sup": {}}"""
    d = Path(data_dir) if data_dir else DATA_DIR
    fac = {"diesel": {}, "bev": {}, "sup": {}}
    with open(d / "emep_hot_factors.csv", newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            coef = {k: float(r[k]) for k in _NUM}
            coef["unit"] = r["unit"]
            fac[r["powertrain"]].setdefault(r["segment"], {})[r["pollutant"]] = coef
    sup_file = d / "emep_supplement.csv"
    if sup_file.exists():
        with open(sup_file, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                fac["sup"][r["name"]] = float(r["value"])
    return fac


def ef(v_kmh, coef):
    """Tier-3 hot emission factor at mean travelling speed v [km/h]."""
    v = min(max(float(v_kmh), coef["vmin"]), coef["vmax"])
    num = coef["alpha"] * v * v + coef["beta"] * v + coef["gamma"] + coef["delta"] / v
    den = coef["epsilon"] * v * v + coef["zita"] * v + coef["hta"]
    return (num / den) * (1.0 - coef["rf"])
