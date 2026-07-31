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


def main(xlsx_path):
    df = pd.read_excel(xlsx_path, sheet_name="HOT_EMISSIONS_PARAMETERS")
    out = transform(df)
    dest = Path(__file__).resolve().parent / "data" / "emep_hot_factors.csv"
    dest.parent.mkdir(parents=True, exist_ok=True)
    out.to_csv(dest, index=False)
    print("wrote " + str(dest) + " (" + str(len(out)) + " rows)")


if __name__ == "__main__":
    main(sys.argv[1])
