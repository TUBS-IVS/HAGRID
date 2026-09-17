# -*- coding: utf-8 -*-
# analysis/kpi/emep_factor_extract.py
"""One-time extraction of the Tier-3 hot emission factor coefficients from
the EMEP/EEA guidebook 2023 (Update 2025) Appendix 4 xlsx (Oct 2025,
COPERT 5.9.1) into the committed data/emep_hot_factors.csv.

Scope: LCV (N1) Euro 7, Diesel technology DPF+SCR plus the Battery
electric EC curve, for ALL THREE N1 segments. The segment -> vehicle-type
assignment itself lives in extract_emissions.SEGMENT_BY_TYPE / the
capacity rule, NOT here -- so re-mapping a class is a data/config change,
not an extraction re-run.

Load is deliberately NOT a dimension: the guidebook restricts the load
correction to heavy-duty vehicles (default 50 % load factor, ch.
1.A.3.b.i-iv p. 62 f.); LCV rows carry no Load/Road Slope column at all
and no documented reference load state. See the plan's Global Constraints.

NOTE the xlsx gotcha: column 'Reduction Factor [%]' holds FRACTIONS
(0.282175 = 28.2 %), not percent values. Copied through unchanged.

Usage:
    python -u emep_factor_extract.py "C:/Users/.../Appendix4.xlsx"
"""
import sys
from pathlib import Path

import pandas as pd

SOURCE = ("EMEP/EEA air pollutant emission inventory guidebook "
          "2023 - Update 2025, ch. 1.A.3.b.i-iv Appendix 4 "
          "(Oct 2025, COPERT 5.9.1)")
SEGMENTS = ("N1-I", "N1-II", "N1-III")
UNIT_BY_POLL = {"EC": "MJ/km", "SPN23": "#/km"}   # default: g/km
COLMAP = {"Segment": "segment", "Pollutant": "pollutant",
          "Alpha": "alpha", "Beta": "beta",
          "Gamma": "gamma", "Delta": "delta", "Epsilon": "epsilon",
          "Zita": "zita", "Hta": "hta", "Reduction Factor [%]": "rf",
          "Min Speed [km/h]": "vmin", "Max Speed [km/h]": "vmax"}
EF_COL = "EF [g/km] or ECF [MJ/km] or #/km or #/kWh or g/kWh"


def transform(df):
    """Filter the HOT_EMISSIONS_PARAMETERS sheet to the two factor sets,
    all three N1 segments, and map to the committed CSV schema."""
    lcv = df[(df["Category"] == "Light Commercial Vehicles")
             & (df["Segment"].isin(SEGMENTS))
             & (df["Euro Standard"] == "Euro 7")]
    diesel = lcv[(lcv["Fuel"] == "Diesel") & (lcv["Technology"] == "DPF+SCR")]
    bev = lcv[lcv["Fuel"] == "Battery electric"]

    out = []
    for powertrain, part in (("diesel", diesel), ("bev", bev)):
        for _, r in part.iterrows():
            row = {new: r[old] for old, new in COLMAP.items()}
            row["rf"] = 0.0 if pd.isna(row["rf"]) else float(row["rf"])
            row["powertrain"] = powertrain
            row["ef_check_v"] = float(r["80"])
            row["ef_check"] = float(r[EF_COL])
            row["unit"] = UNIT_BY_POLL.get(row["pollutant"], "g/km")
            row["source"] = SOURCE
            out.append(row)
    cols = ["powertrain", "segment", "pollutant", "alpha", "beta", "gamma",
            "delta", "epsilon", "zita", "hta", "rf", "vmin", "vmax",
            "ef_check_v", "ef_check", "unit", "source"]
    return pd.DataFrame(out)[cols]


COLD_SHEET = "COLD_EMISSIONS_PARAMETERS"
COLD_POLLUTANTS = ("CO", "NOx", "VOC", "EC")
#: Fuer Euro 7 fuehrt das Sheet KEINE Kaltstartparametrisierung fuer diese
#: drei -- sie bleiben im Rechenkern sichtbar unparametrisiert statt still 0
#: (Spec E8/L3). Hier nur dokumentiert, gefiltert wird ueber COLD_POLLUTANTS.
#: NAME BEWUSST ANDERS als emissions_emep.COLD_UNPARAMETERISED: dort stehen
#: die AUSGABE-Schluessel ("PM_EXHAUST"), hier die xlsx-Schreibweisen
#: ("PM Exhaust"). Gleicher Name fuer zwei Schreibweisen waere eine Falle.
COLD_UNPARAMETERISED_XLSX = ("PM Exhaust", "CH4", "SPN23")
COLD_COLMAP_FIXED = {"Segment": "segment", "Pollutant": "pollutant",
                      "Range": "range", "Alpha": "a", "Beta": "b",
                      "Gamma": "c", "Min Speed [km/h]": "vmin",
                      "Max Speed [km/h]": "vmax"}
_COLD_KEY = ("segment", "pollutant", "range")
_COLD_VALS = ("a", "b", "c", "vmin", "vmax", "tmin", "tmax")


def _resolve_cold_temp_columns(columns):
    """The sheet's temperature headers contain U+2103 (DEGREE CELSIUS,
    'Min Temperature [℃]' / 'Max Temperature [℃]'), which is not safe to
    reproduce byte-for-byte across encodings/editors. Resolve by prefix
    instead of exact string match."""
    tmin_col = None
    tmax_col = None
    for col in columns:
        if col.startswith("Min Temperature"):
            tmin_col = col
        elif col.startswith("Max Temperature"):
            tmax_col = col
    if tmin_col is None or tmax_col is None:
        raise ValueError(
            "could not resolve Min/Max Temperature columns in "
            + COLD_SHEET + "; available columns: " + str(list(columns)))
    return tmin_col, tmax_col


def transform_cold(df):
    """Filter the COLD_EMISSIONS_PARAMETERS sheet to Euro 7 Diesel LCV and
    map to the committed CSV schema.

    The sheet resolves parameters per MONTH. For Euro 7 LCV the values look
    month-invariant, but that was never verified against the file -- so it is
    ASSERTED here and the collapse to one row per (segment, pollutant, range)
    only happens if it holds. Silently taking January would produce a number
    that looks measured and is not.

    No Technology column exists in this sheet (unlike HOT_EMISSIONS_PARAMETERS),
    and there are no Battery electric rows: the BEV cold surcharge is zero
    because the SOURCE has nothing, not because the code decided so.
    """
    tmin_col, tmax_col = _resolve_cold_temp_columns(df.columns)
    colmap = dict(COLD_COLMAP_FIXED)
    colmap[tmin_col] = "tmin"
    colmap[tmax_col] = "tmax"

    lcv = df[(df["Category"] == "Light Commercial Vehicles")
             & (df["Fuel"] == "Diesel")
             & (df["Segment"].isin(SEGMENTS))
             & (df["Euro Standard"] == "Euro 7")
             & (df["Pollutant"].isin(COLD_POLLUTANTS))]
    if lcv.empty:
        raise ValueError("no Euro 7 Diesel LCV rows in " + COLD_SHEET)

    ren = lcv.rename(columns=colmap)[list(_COLD_KEY) + list(_COLD_VALS)]
    grouped = ren.groupby(list(_COLD_KEY), sort=False)
    for key, part in grouped:
        uniq = part[list(_COLD_VALS)].drop_duplicates()
        if len(uniq) != 1:
            raise ValueError(
                "month variance in " + COLD_SHEET + " for " + str(key)
                + ": " + str(len(uniq)) + " distinct parameter sets across "
                "months. The collapse to one row is not valid here -- pick a "
                "month explicitly and document it.")

    out = grouped.first().reset_index()
    out["powertrain"] = "diesel"
    out["source"] = SOURCE
    cols = ["powertrain", "segment", "pollutant", "range", "a", "b", "c",
            "vmin", "vmax", "tmin", "tmax", "source"]
    return out[cols]


def main(xlsx_path):
    data = Path(__file__).resolve().parent / "data"
    data.mkdir(parents=True, exist_ok=True)

    hot = transform(pd.read_excel(xlsx_path,
                                  sheet_name="HOT_EMISSIONS_PARAMETERS"))
    hot.to_csv(data / "emep_hot_factors.csv", index=False)
    print("wrote emep_hot_factors.csv (" + str(len(hot)) + " rows)")

    cold = transform_cold(pd.read_excel(xlsx_path, sheet_name=COLD_SHEET))
    cold.to_csv(data / "emep_cold_factors.csv", index=False)
    print("wrote emep_cold_factors.csv (" + str(len(cold)) + " rows)")


if __name__ == "__main__":
    main(sys.argv[1])
