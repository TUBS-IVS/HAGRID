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


def test_supplement_present_and_plausible():
    import emissions_emep as em
    sup = em.load_factors()["sup"]
    required = ["ttw_co2_g_per_mj_diesel", "wtt_co2e_g_per_mj_diesel",
                "grid_co2e_g_per_mj", "gwp_ch4", "gwp_n2o",
                "n2o_g_per_km_diesel_lcv",
                "pm10_frac_tyre", "pm10_frac_brake", "pm10_frac_road",
                "bev_tyre_mult", "bev_brake_mult", "bev_road_mult",
                "ev_range_km_low", "ev_range_km_mid", "ev_range_km_high"]
    for src in ("tyre", "brake", "road"):
        required += ["tsp_%s_g_per_km_n1_%s" % (src, s)
                     for s in ("i", "ii", "iii")]
    for k in required:
        assert k in sup, k
    assert 65 < sup["ttw_co2_g_per_mj_diesel"] < 80      # ~74 g CO2/MJ Diesel TTW
    assert 10 < sup["wtt_co2e_g_per_mj_diesel"] < 30
    assert 0 < sup["n2o_g_per_km_diesel_lcv"] < 0.1
    assert 25 < sup["gwp_ch4"] < 35 and 250 < sup["gwp_n2o"] < 300
    assert 0 < sup["pm10_frac_brake"] <= 1.0
    assert sup["bev_brake_mult"] < 1.0 < sup["bev_tyre_mult"]
    assert sup["bev_road_mult"] > 1.0
    assert (sup["ev_range_km_low"] < sup["ev_range_km_mid"]
            < sup["ev_range_km_high"])
    # die untere Schwelle muss unter der laengsten gemessenen Tour (183 km)
    # liegen, sonst ist der Sweep in jedem Lauf trivial 0 (Messung 2026-07-31)
    assert sup["ev_range_km_low"] < 183.0


def test_nonexhaust_bases_are_segment_resolved_as_the_source_has_them():
    """Step-1-Verifikation 2026-07-31: die urspruengliche Plan-Annahme
    'Non-Exhaust ist fuer LCV nicht segmentiert' ist FALSCH. Tab. 3-4/3-8
    gruppieren N1-II+III, Tab. 3-6 trennt alle drei. Der Test haelt genau
    diese Struktur fest -- er schlaegt an, wenn jemand die Werte wieder auf
    einen LCV-Einheitswert zusammenzieht ODER die N1-I-Zeile erwischt
    (das war der Originalfehler: 0.0117 bzw. 0.0150 sind N1-I-Werte)."""
    import emissions_emep as em
    sup = em.load_factors()["sup"]
    # Reifen und Strasse: Quelle gruppiert II und III
    assert sup["tsp_tyre_g_per_km_n1_ii"] == sup["tsp_tyre_g_per_km_n1_iii"]
    assert sup["tsp_road_g_per_km_n1_ii"] == sup["tsp_road_g_per_km_n1_iii"]
    # Bremse: Quelle trennt alle drei, streng steigend
    b = [sup["tsp_brake_g_per_km_n1_" + s] for s in ("i", "ii", "iii")]
    assert b[0] < b[1] < b[2]
    assert b[2] / b[1] == pytest.approx(1.361, rel=0.01)   # II -> III: +36 %
    # und die N1-I-Werte duerfen nicht die unserer Flotte sein
    assert sup["tsp_brake_g_per_km_n1_i"] == pytest.approx(0.0117)
    assert sup["tsp_brake_g_per_km_n1_ii"] == pytest.approx(0.0155)
    assert sup["tsp_road_g_per_km_n1_i"] == pytest.approx(0.0150)
    assert sup["tsp_road_g_per_km_n1_ii"] == pytest.approx(0.0210)


def test_vehicle_emissions_diesel_matches_manual_calc():
    import emissions_emep as em
    fac = em.load_factors()
    out = em.vehicle_emissions(100.0, 80.0, "diesel", "N1-III", fac)
    ec = 100.0 * em.ef(80.0, fac["diesel"]["N1-III"]["EC"])          # MJ
    sup = fac["sup"]
    assert out["ENERGY_MJ"] == pytest.approx(ec)
    assert out["CO2"] == pytest.approx(ec * sup["ttw_co2_g_per_mj_diesel"])
    assert out["NOx"] == pytest.approx(
        100.0 * em.ef(80.0, fac["diesel"]["N1-III"]["NOx"]))
    assert out["N2O"] == pytest.approx(100.0 * sup["n2o_g_per_km_diesel_lcv"])
    assert out["CO2E_TTW"] == pytest.approx(
        out["CO2"] + sup["gwp_ch4"] * out["CH4"] + sup["gwp_n2o"] * out["N2O"])
    assert out["CO2E_WTW"] == pytest.approx(
        out["CO2E_TTW"] + ec * sup["wtt_co2e_g_per_mj_diesel"])
    assert out["PM10_NONEXHAUST"] == pytest.approx(
        out["PM10_TYRE"] + out["PM10_BRAKE"] + out["PM10_ROAD"])


def test_vehicle_emissions_segment_acts_on_energy_and_on_brake_wear():
    """Rev. B2: das Segment wirkt auf BEIDEN Seiten -- Energie/Auspuff ueber
    die Tier-3-Kurven, Abrieb ueber die segmentierten TSP-Basen. Genau je
    Abriebquelle unterschiedlich: Reifen und Strasse gruppiert die Quelle
    fuer N1-II+III (also gleich), die Bremse trennt sie (+36 %)."""
    import emissions_emep as em
    fac = em.load_factors()
    s = em.vehicle_emissions(100.0, 30.0, "diesel", "N1-II", fac)
    l = em.vehicle_emissions(100.0, 30.0, "diesel", "N1-III", fac)
    assert l["ENERGY_MJ"] / s["ENERGY_MJ"] == pytest.approx(1.43, rel=0.02)
    assert l["CO2"] > s["CO2"]
    assert l["NOx"] == pytest.approx(s["NOx"])        # II und III gleich
    assert l["PM10_TYRE"] == pytest.approx(s["PM10_TYRE"])
    assert l["PM10_ROAD"] == pytest.approx(s["PM10_ROAD"])
    assert l["PM10_BRAKE"] / s["PM10_BRAKE"] == pytest.approx(1.361, rel=0.01)
    # N1-I liegt bei ALLEN drei Abriebquellen darunter
    xs = em.vehicle_emissions(100.0, 30.0, "diesel", "N1-I", fac)
    for k in ("PM10_TYRE", "PM10_BRAKE", "PM10_ROAD"):
        assert xs[k] < s[k], k


def test_vehicle_emissions_rejects_unknown_segment():
    import emissions_emep as em
    with pytest.raises(KeyError):
        em.vehicle_emissions(10.0, 30.0, "diesel", "N2-XL",
                             em.load_factors())


def test_vehicle_emissions_bev_zero_exhaust_grid_wtw():
    import emissions_emep as em
    fac = em.load_factors()
    out = em.vehicle_emissions(100.0, 80.0, "bev", "N1-III", fac)
    for k in ("CO", "NOx", "VOC", "PM_EXHAUST", "CH4", "SPN23", "N2O",
              "CO2", "CO2E_TTW"):
        assert out[k] == 0.0, k
    ec = 100.0 * em.ef(80.0, fac["bev"]["N1-III"]["EC"])
    assert out["ENERGY_MJ"] == pytest.approx(ec)
    assert out["CO2E_WTW"] == pytest.approx(ec * fac["sup"]["grid_co2e_g_per_mj"])
    # BEV: mehr Reifen- und Strassen-, weniger Bremsabrieb als Diesel
    d = em.vehicle_emissions(100.0, 80.0, "diesel", "N1-III", fac)
    assert out["PM10_TYRE"] > d["PM10_TYRE"]
    assert out["PM10_ROAD"] > d["PM10_ROAD"]
    assert out["PM10_BRAKE"] < d["PM10_BRAKE"]


def test_non_exhaust_speed_correction_piecewise():
    import emissions_emep as em
    sup = em.load_factors()["sup"]
    # Tyre-Korrektur: konstant unter 40, fallend 40..90, konstant ueber 90
    def tyre(v):
        return em.non_exhaust_pm10(1.0, v, "diesel", "N1-III", sup)[0]
    assert tyre(30.0) == pytest.approx(tyre(39.9), rel=1e-3)
    assert tyre(39.9) > tyre(60.0) > tyre(95.0)
    assert tyre(95.0) == pytest.approx(tyre(120.0))
    # Strassenabrieb ist laut Gl. 9 geschwindigkeitsUNabhaengig
    def road(v):
        return em.non_exhaust_pm10(1.0, v, "diesel", "N1-III", sup)[2]
    assert road(15.0) == pytest.approx(road(120.0))
    # Plateauwerte gegen die Quelle: Reifen 1.39 bzw. Bremse 1.67 unter
    # 40 km/h -- genau der Bereich, in dem unsere Touren fahren
    t, b, _ = em.non_exhaust_pm10(1.0, 30.0, "diesel", "N1-III", sup)
    assert t == pytest.approx(0.0169 * 0.600 * 1.39)
    assert b == pytest.approx(0.0211 * 0.980 * 1.67)
