# -*- coding: utf-8 -*-
"""EMEP/EEA Tier-3 emission factor extraction + curve evaluation.

Plan: docs/superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md
"""
import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def _fake_sheet():
    cols = ["Category", "Fuel", "Segment", "Euro Standard", "Technology",
            "Pollutant", "Alpha", "Beta", "Gamma", "Delta", "Epsilon",
            "Zita", "Hta", "Reduction Factor [%]",
            "Min Speed [km/h]", "Max Speed [km/h]", "80",
            "EF [g/km] or ECF [MJ/km] or #/km or #/kWh or g/kWh"]
    rows = [
        ["Light Commercial Vehicles", "Diesel", "N1-III", "Euro 7", "DPF+SCR",
         "NOx", 8e-6, -0.001627, 0.09706498, 0.650205, 5.7e-5, -0.014462, 1.0,
         0.282175, 5, 140, 80, 0.09348927],
        ["Light Commercial Vehicles", "Diesel", "N1-III", "Euro 7", "DPF",
         "NOx", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0, 5, 140, 80, 9.9],
        ["Light Commercial Vehicles", "Battery electric", "N1-III", "Euro 7", None,
         "EC", 3.635688e6, 1.564812e8, 9.870604e9, 1.638783e9, -15905.987594,
         4.431187e6, 2.023218e7, 0.0, 5, 130, 80, 1.4],
        ["Buses", "Diesel", "Urban Buses Midi <=15 t", "Euro VII", None,
         "NOx", 1, 1, 1, 1, 1, 1, 1, 0, 1, 80, 80, 8.8],
    ]
    return pd.DataFrame(rows, columns=cols)


def test_transform_filters_and_maps_columns():
    from emep_factor_extract import transform
    out = transform(_fake_sheet())
    # LCV Euro 7: Diesel-DPF+SCR-Zeilen + BEV-EC; DPF-only und Bus fliegen raus
    assert set(out["powertrain"]) == {"diesel", "bev"}
    nox = out[(out["powertrain"] == "diesel") & (out["pollutant"] == "NOx")
              & (out["segment"] == "N1-III")]
    assert len(nox) == 1
    r = nox.iloc[0]
    assert r["rf"] == pytest.approx(0.282175)      # Bruchteil, NICHT /100
    assert r["vmin"] == 5 and r["vmax"] == 140
    assert r["ef_check_v"] == 80
    assert r["ef_check"] == pytest.approx(0.09348927)
    assert r["unit"] == "g/km"
    bev = out[out["powertrain"] == "bev"]
    assert list(bev["pollutant"]) == ["EC"]
    assert bev.iloc[0]["unit"] == "MJ/km"
    assert bev.iloc[0]["segment"] == "N1-III"
    assert (out["source"].str.contains("COPERT 5.9.1")).all()
    assert (out["source"].str.contains("2023 - Update 2025")).all()


def test_transform_keeps_all_three_n1_segments():
    """Rev. B: das Klassenmapping soll eine Datenzeile sein, keine
    Code-Aenderung -- also alle drei Segmente extrahieren."""
    from emep_factor_extract import transform
    df = _fake_sheet().copy()
    for seg in ("N1-I", "N1-II"):
        extra = df[df["Segment"] == "N1-III"].copy()
        extra["Segment"] = seg
        df = pd.concat([df, extra], ignore_index=True)
    out = transform(df)
    assert set(out["segment"]) == {"N1-I", "N1-II", "N1-III"}
    # je Segment die Diesel-NOx-Zeile genau einmal
    for seg in ("N1-I", "N1-II", "N1-III"):
        sel = out[(out["segment"] == seg) & (out["powertrain"] == "diesel")
                  & (out["pollutant"] == "NOx")]
        assert len(sel) == 1, seg
