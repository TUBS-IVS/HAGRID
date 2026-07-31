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


def test_ef_reproduces_appendix_check_column_for_every_row():
    import emissions_emep as em
    fac = em.load_factors()
    checked = 0
    for pt in ("diesel", "bev"):
        for seg, polls in fac[pt].items():
            for poll, c in polls.items():
                got = em.ef(c["ef_check_v"], c)
                # rel=1e-4: validiert Formelstruktur + RF-als-Bruchteil (ein
                # Prozent-Fehler laege ~39 % daneben); letzte Stellen der
                # xlsx-Kontrollspalte koennen rundungsbedingt abweichen.
                assert got == pytest.approx(c["ef_check"], rel=1e-4), (pt, seg, poll)
                checked += 1
    assert checked == 24            # 3 Segmente x (7 Diesel + 1 BEV)


def test_ef_clamps_speed_to_curve_range():
    import emissions_emep as em
    fac = em.load_factors()
    c = fac["diesel"]["N1-III"]["NOx"]        # vmin=5, vmax=140
    assert em.ef(1.0, c) == em.ef(5.0, c)
    assert em.ef(200.0, c) == em.ef(140.0, c)
    assert em.ef(80.0, c) > 0


def test_segment_ordering_at_tour_speed():
    """Rev. B: die Segmentdifferenzierung ist der Kern des Plans -- die
    Kernaussagen werden hier festgenagelt, damit ein Faktor-Reimport, der
    sie kippt, laut scheitert. Werte verifiziert 2026-07-31 bei 30 km/h
    (Tourmittelgeschwindigkeit der Laeufe: 36 km/h)."""
    import emissions_emep as em
    fac = em.load_factors()
    ec = {s: em.ef(30.0, fac["diesel"][s]["EC"])
          for s in ("N1-I", "N1-II", "N1-III")}
    assert ec["N1-I"] == pytest.approx(2.157, rel=2e-3)
    assert ec["N1-II"] == pytest.approx(2.183, rel=2e-3)
    assert ec["N1-III"] == pytest.approx(3.123, rel=2e-3)
    # der einzige relevante Energie-Bruch liegt zwischen II und III (~43 %)
    assert ec["N1-III"] / ec["N1-II"] == pytest.approx(1.43, rel=0.02)
    # NOx: II und III sind identisch, der Bruch liegt zwischen I und II
    nox = {s: em.ef(30.0, fac["diesel"][s]["NOx"])
           for s in ("N1-I", "N1-II", "N1-III")}
    assert nox["N1-II"] == pytest.approx(nox["N1-III"])
    assert nox["N1-II"] / nox["N1-I"] == pytest.approx(1.67, rel=0.02)
    # PM exhaust: DPF -> keine Segmentdifferenzierung
    pm = {s: em.ef(30.0, fac["diesel"][s]["PM Exhaust"])
          for s in ("N1-I", "N1-II", "N1-III")}
    assert pm["N1-I"] == pytest.approx(pm["N1-III"])
