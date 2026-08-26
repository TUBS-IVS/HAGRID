# -*- coding: utf-8 -*-
"""Emission KPI extractor: freight arm, modular freight arm, DRT arm,
mass allocation, KPI rows.

Plan: docs/superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md
"""
import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

TSV_COLS = ["vehicleId", "carrierId", "vehicleTypeId", "tourId",
            "tourDuration[s]", "tourDuration[h]", "travelDistance[m]",
            "travelDistance[km]", "travelTime[s]", "travelTime[h]",
            "costPerSecond[EUR/s]", "costPerMeter[EUR/m]",
            "fixedCosts[EUR]", "varCostsTime[EUR]", "varCostsDist[EUR]",
            "totalCosts[EUR]"]


def _run_dir(tmp_path):
    fr = tmp_path / "analysis" / "freight"
    fr.mkdir(parents=True)
    rows = [
        ["freight_dhl_veh_a_1", "dhl", "ct_cep_size_m", 1, 25000, 6.9,
         120000.0, 120.0, 14400, 4.0, 0, 3.7e-4, 171.78, 0, 44.4, 216.2],
        ["freight_ups_veh_b_2", "ups", "ct_cep_size_l", 2, 20000, 5.6,
         60000.0, 60.0, 7200, 2.0, 0, 3.9e-4, 189.15, 0, 23.2, 212.4],
    ]
    pd.DataFrame(rows, columns=TSV_COLS).to_csv(
        fr / "TimeDistance_perVehicle.tsv", sep="\t", index=False)
    return tmp_path


def test_freight_arm_sums_tours_at_tour_mean_speed(tmp_path):
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    totals, detail = ee.freight_arm(_run_dir(tmp_path), fac)
    # Tour 1: 120 km bei 120/4=30 km/h (size_m -> N1-III)
    # Tour 2:  60 km bei  60/2=30 km/h (size_l -> N1-III)
    exp = em.vehicle_emissions(120.0, 30.0, "diesel", "N1-III", fac)
    exp2 = em.vehicle_emissions(60.0, 30.0, "diesel", "N1-III", fac)
    # Task 5: freight_arm bucht 1 Kaltstart je Tour (Spec E5, keine
    # Task-Sequenz in der TSV) -- der Zuschlag steckt in totals (Spec E1).
    cold1 = em.cold_start_extra(1, 120.0, 30.0, "diesel", "N1-III", fac)
    cold2 = em.cold_start_extra(1, 60.0, 30.0, "diesel", "N1-III", fac)
    assert totals["diesel"]["CO2E_WTW"] == pytest.approx(
        exp["CO2E_WTW"] + exp2["CO2E_WTW"]
        + cold1["CO2E_WTW"] + cold2["CO2E_WTW"])
    assert totals["bev"]["CO2E_WTW"] > 0
    assert len(detail) == 4                      # 2 Touren x 2 Antriebe
    d0 = [d for d in detail if d["powertrain"] == "diesel"][0]
    assert d0["fleet"] == "freight" and d0["v_kmh"] == pytest.approx(30.0)
    assert d0["vehicle_type"] == "ct_cep_size_m"
    assert d0["segment"] == "N1-III"


def test_freight_arm_size_s_gets_the_lighter_segment(tmp_path):
    """Rev. B Kernverhalten: size_s ist N1-II, nicht N1-III. In base10c
    liegen 93 % der Freight-km auf size_s -- eine Einheitsklasse N1-III
    wuerde die Baseline-Energie um ~39 % ueberschaetzen."""
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    fr = tmp_path / "analysis" / "freight"
    fr.mkdir(parents=True)
    rows = [["dhl_s", "dhl", "ct_cep_size_s", 1, 25000, 6.9,
             100000.0, 100.0, 12000, 3.33, 0, 3.6e-4, 154.41, 0, 36.0, 190.4]]
    pd.DataFrame(rows, columns=TSV_COLS).to_csv(
        fr / "TimeDistance_perVehicle.tsv", sep="\t", index=False)
    _, detail = ee.freight_arm(tmp_path, fac)
    d = [x for x in detail if x["powertrain"] == "diesel"][0]
    assert d["segment"] == "N1-II"
    exp = em.vehicle_emissions(100.0, 30.0, "diesel", "N1-II", fac)
    # Task 5: 1 Kaltstart je Tour (Spec E5), eingerechnet in ENERGY_MJ.
    cold = em.cold_start_extra(1, 100.0, 30.0, "diesel", "N1-II", fac)
    assert d["ENERGY_MJ"] == pytest.approx(exp["ENERGY_MJ"] + cold["ENERGY_MJ"])
    # und deutlich unter der N1-III-Rechnung
    n3 = em.vehicle_emissions(100.0, 30.0, "diesel", "N1-III", fac)
    assert d["ENERGY_MJ"] < 0.75 * n3["ENERGY_MJ"]


def test_segment_for_type_covers_generated_sweep_types():
    """CarrierVehicleFactory erzeugt ct_cep_<cap>_<tpl> zur Laufzeit; die
    Regel muss die abdecken, sonst faellt der Hannover-Sweep still auf
    null Emissionen."""
    import extract_emissions as ee
    assert ee.segment_for_type("ct_cep_size_s") == "N1-II"
    assert ee.segment_for_type("ct_cep_size_m") == "N1-III"
    assert ee.segment_for_type("ct_cep_size_l") == "N1-III"
    assert ee.segment_for_type("ct_cep_60_m", capacity=60.0) == "N1-II"
    assert ee.segment_for_type("ct_cep_300_l", capacity=300.0) == "N1-III"
    assert ee.segment_for_type("ct_cep_120_m", capacity=120.0) == "N1-II"
    assert ee.segment_for_type("ct_cep_121_m", capacity=121.0) == "N1-III"


def test_segment_for_type_raises_on_unknown_without_capacity():
    """Lieber laut scheitern als still als N1-III bepreisen."""
    import extract_emissions as ee
    with pytest.raises(ValueError):
        ee.segment_for_type("ct_cargobike_xl")


def test_freight_arm_missing_tsv_returns_none(tmp_path):
    import emissions_emep as em
    import extract_emissions as ee
    assert ee.freight_arm(tmp_path, em.load_factors()) is None


# --- Task 5b: modular freight arm ----------------------------------------

NETWORK_XML = """<?xml version="1.0" encoding="utf-8"?>
<network>
<nodes>
<node id="n1" x="0" y="0"/><node id="n2" x="1000" y="0"/><node id="n3" x="1800" y="0"/>
</nodes>
<links>
<link id="l1" from="n1" to="n2" length="1200.0" freespeed="13.9" capacity="600" permlanes="1"/>
<link id="l2" from="n2" to="n3" length="800.0" freespeed="13.9" capacity="600" permlanes="1"/>
</links>
</network>
"""


def _network(tmp_path):
    import gzip
    p = tmp_path / "TEST.output_network.xml.gz"
    with gzip.open(p, "wt", encoding="utf-8") as f:
        f.write(NETWORK_XML)
    return p


def test_load_link_lengths_uses_the_network_length_attribute(tmp_path):
    """Die Netzwerk-Laenge, NICHT die Euklid-Naeherung aus
    geometry.load_link_geometry -- die ist ~3 % zu kurz (l1: 1200 m
    Attribut vs. 1000 m Luftlinie)."""
    import extract_emissions as ee
    ll = ee.load_link_lengths(_network(tmp_path), {"l1", "l2"})
    assert ll == {"l1": pytest.approx(1200.0), "l2": pytest.approx(800.0)}


def test_freight_windows_parsed_from_dvrp_task_events(tmp_path):
    """Reale Event-Typnamen sind dvrpTaskStarted/dvrpTaskEnded (verifiziert
    an m1d050), NICHT 'task started'/'task ended' wie im Plan skizziert -
    und sie stehen im drt-Cache, nicht im freight-Cache (der ist in allen
    1d-Laeufen 0 Bytes)."""
    import extract_emissions as ee
    ev = tmp_path / "TEST.drt_events_filtered.txt"
    ev.write_text(
        '<event time="3600.0" type="dvrpTaskStarted" person="drt_1" '
        'link="l1" dvrpVehicle="drt_1" taskType="MODULAR_FREIGHT_DRIVE" '
        'taskIndex="7" dvrpMode="drt"  />\n'
        '<event time="7200.0" type="dvrpTaskEnded" person="drt_1" '
        'link="l2" dvrpVehicle="drt_1" taskType="MODULAR_FREIGHT_DRIVE" '
        'taskIndex="7" dvrpMode="drt"  />\n'
        '<event time="8000.0" type="dvrpTaskStarted" person="drt_1" '
        'dvrpVehicle="drt_1" taskType="STAY" dvrpMode="drt"  />\n'
        '<event time="9000.0" type="dvrpTaskEnded" person="drt_1" '
        'dvrpVehicle="drt_1" taskType="STAY" dvrpMode="drt"  />\n',
        encoding="utf-8")
    assert ee.freight_windows(ev) == {"drt_1": [(3600.0, 7200.0)]}


def test_freight_windows_ignores_plain_drive_and_stop_tasks(tmp_path):
    """DRIVE/STOP sind Pax-Tasks; nur MODULAR_FREIGHT_* ist Fracht. Ohne
    diese Trennung waere der ganze DRT-Betrieb als Fracht gebucht."""
    import extract_emissions as ee
    ev = tmp_path / "TEST.drt_events_filtered.txt"
    ev.write_text(
        '<event time="100.0" type="dvrpTaskStarted" dvrpVehicle="drt_1" '
        'taskType="DRIVE" dvrpMode="drt"  />\n'
        '<event time="300.0" type="dvrpTaskEnded" dvrpVehicle="drt_1" '
        'taskType="DRIVE" dvrpMode="drt"  />\n'
        '<event time="300.0" type="dvrpTaskStarted" dvrpVehicle="drt_1" '
        'taskType="STOP" dvrpMode="drt"  />\n',
        encoding="utf-8")
    assert ee.freight_windows(ev) == {}


def test_modular_freight_arm_splits_km_by_window(tmp_path):
    """Nur die km INNERHALB der Freight-Fenster zaehlen als Fracht; der
    Rest bleibt Pax. Das ist der Distanzsplit, den drt_vehicle_km nicht
    hat (METHODS-LOG 2.14, 'not corrected')."""
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    # 4-Tupel (link, occ_pax, occ_parcels, t): l1 vor dem Fenster, l2+l1 drin
    veh_path_ts = {"drt_1": [("l1", 0, 0, 100.0), ("l2", 0, 0, 4000.0),
                             ("l1", 0, 0, 5000.0)]}
    windows = {"drt_1": [(3600.0, 7200.0)]}
    ll = ee.load_link_lengths(_network(tmp_path), {"l1", "l2"})
    totals, detail = ee.modular_freight_arm(veh_path_ts, windows, ll, fac)
    d = [x for x in detail if x["powertrain"] == "diesel"][0]
    assert d["km"] == pytest.approx(2.0)          # l2 + l1 = 800 + 1200 m
    assert d["fleet"] == "freight_modular"
    assert d["vehicle_type"] == "drt_modular"
    assert d["segment"] == "N1-III"
    # Geschwindigkeit = Freight-km / Summe der Fensterdauern (3600 s = 1 h)
    assert d["v_kmh"] == pytest.approx(2.0)
    assert totals["diesel"]["CO2E_WTW"] > 0


def test_modular_freight_arm_empty_without_windows():
    import emissions_emep as em
    import extract_emissions as ee
    totals, detail = ee.modular_freight_arm(
        {"drt_1": [("l1", 0, 0, 100.0)]}, {}, {"l1": 500.0},
        em.load_factors())
    assert detail == [] and totals["diesel"]["CO2E_WTW"] == 0.0


def test_modular_freight_arm_skips_links_missing_from_the_network():
    """Ein Link ohne Geometrie darf nicht als 0 km durchlaufen und die
    Geschwindigkeit verzerren -- er wird uebersprungen."""
    import emissions_emep as em
    import extract_emissions as ee
    veh = {"drt_1": [("ghost", 0, 0, 4000.0), ("l1", 0, 0, 4100.0)]}
    _, detail = ee.modular_freight_arm(veh, {"drt_1": [(3600.0, 7200.0)]},
                                       {"l1": 1000.0}, em.load_factors())
    d = [x for x in detail if x["powertrain"] == "diesel"][0]
    assert d["km"] == pytest.approx(1.0)


# --- Task 6: DRT arm ------------------------------------------------------

def test_drt_arm_uses_true_lengths_and_drive_time(tmp_path):
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    veh_path = {"drt_v1": [("l1", 0, 0, 10.0), ("l2", 1, 0, 20.0),
                           ("l1", 0, 0, 30.0)]}          # 1200+800+1200 = 3.2 km
    recon = {"per_veh": {"drt_v1": {"drive_s": 320.0}}}   # 36 km/h
    ll = ee.load_link_lengths(_network(tmp_path), {"l1", "l2"})
    totals, detail = ee.drt_arm(veh_path, recon, ll, fac)
    exp = em.vehicle_emissions(3.2, 36.0, "diesel", "N1-III", fac)
    assert totals["diesel"]["NOx"] == pytest.approx(exp["NOx"])
    d = [x for x in detail if x["powertrain"] == "diesel"][0]
    assert d["fleet"] == "drt" and d["km"] == pytest.approx(3.2)
    assert d["v_kmh"] == pytest.approx(36.0)
    assert d["segment"] == "N1-III"


def test_drt_arm_skips_vehicle_without_drive_time():
    import emissions_emep as em
    import extract_emissions as ee
    totals, detail = ee.drt_arm({"drt_v1": [("l1", 0, 0, 1.0)]},
                                {"per_veh": {}}, {"l1": 500.0},
                                em.load_factors())
    assert detail == [] and totals["diesel"]["CO2E_WTW"] == 0.0


def test_drt_arm_excludes_freight_km_so_the_arms_do_not_double_count():
    """Der 1d-Regimesplit ist RESTFREI (METHODS-LOG 1.4): jeder Fahrzeug-km
    gehoert genau einer Seite. Ohne exclude_windows zaehlt drt_arm die
    Freight-km als Pax-km UND modular_freight_arm zaehlt sie erneut --
    total_* waere dann um die Freight-km zu hoch.

    Die Zeitseite ist schon getrennt: drt_service_time.reconstruct legt
    taskType DRIVE nach `drive_s` und MODULAR_FREIGHT_DRIVE nach
    `freight_drive_s` (drt_service_time.py:411/414). Nur die Distanzseite
    fehlte."""
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    # l1 im Pax-Betrieb (t=100), l2 im Freight-Fenster (t=4000)
    veh_path = {"drt_1": [("l1", 0, 0, 100.0), ("l2", 0, 0, 4000.0)]}
    windows = {"drt_1": [(3600.0, 7200.0)]}
    ll = {"l1": 1200.0, "l2": 800.0}
    recon = {"per_veh": {"drt_1": {"drive_s": 120.0}}}

    naive, _ = ee.drt_arm(veh_path, recon, ll, fac)
    split, det_split = ee.drt_arm(veh_path, recon, ll, fac,
                                  exclude_windows=windows)
    _, det_fr = ee.modular_freight_arm(veh_path, windows, ll, fac)

    d_split = [x for x in det_split if x["powertrain"] == "diesel"][0]
    d_fr = [x for x in det_fr if x["powertrain"] == "diesel"][0]
    assert d_split["km"] == pytest.approx(1.2)           # nur der Pax-Link
    assert d_fr["km"] == pytest.approx(0.8)              # nur der Freight-Link
    # restfrei: die beiden Seiten summieren auf die gesamte Fahrleistung
    assert d_split["km"] + d_fr["km"] == pytest.approx(2.0)
    # und der naive Aufruf haette die Freight-km mitgezaehlt
    assert naive["diesel"]["ENERGY_MJ"] > split["diesel"]["ENERGY_MJ"]


def test_drt_arm_all_km_inside_freight_windows_yields_no_pax_entity():
    """Ein Fahrzeug, das ausschliesslich Fracht gefahren hat, darf keine
    Pax-Zeile erzeugen (km = 0 nach Ausschluss)."""
    import emissions_emep as em
    import extract_emissions as ee
    veh_path = {"drt_1": [("l1", 0, 0, 4000.0)]}
    _, detail = ee.drt_arm(veh_path, {"per_veh": {"drt_1": {"drive_s": 60.0}}},
                           {"l1": 1000.0}, em.load_factors(),
                           exclude_windows={"drt_1": [(3600.0, 7200.0)]})
    assert detail == []


# --- Task 5c: mass-based allocation + specific intensities ----------------

def _sup():
    # kg_per_parcel: Annahme 1.65 (METHODS-LOG 2.26); kg_per_passenger: Setzung
    return {"kg_per_parcel": 1.65, "kg_per_passenger": 80.0,
            "slots_per_seat_equiv": 2.5}


def test_mass_split_uses_kg_km_shares():
    import extract_emissions as ee
    # 20 Pakete (33.0 kg) gegen 1 Fahrgast (80 kg)
    sp, spc = ee.mass_split(1, 20, _sup())
    assert spc == pytest.approx(33.0 / 113.0)
    assert sp + spc == pytest.approx(1.0)
    # nichts an Bord -> keine Basis, Aufrufer muss umlegen
    assert ee.mass_split(0, 0, _sup()) == (0.0, 0.0)


def test_mass_split_is_load_driven_first_mass_second():
    """METHODS-LOG 2.26: die Beladung treibt den Frachtanteil (~45 Pp von
    10 auf 99 Pakete), die Massenannahme im plausiblen Band 1.3-2.5 kg
    zweitrangig (~16 Pp). Der Test haelt beide Groessenordnungen fest,
    damit die Sensitivitaet nicht der falschen Groesse zugeschrieben wird."""
    import extract_emissions as ee
    sup = _sup()
    lo = ee.mass_split(1.6, 10, sup)[1]
    hi = ee.mass_split(1.6, 99, sup)[1]
    assert lo == pytest.approx(0.114, abs=0.005)
    assert hi == pytest.approx(0.561, abs=0.005)
    band = (ee.mass_split(1.6, 50, dict(sup, kg_per_parcel=2.50))[1]
            - ee.mass_split(1.6, 50, dict(sup, kg_per_parcel=1.30))[1])
    assert band == pytest.approx(0.157, abs=0.01)
    assert (hi - lo) > 2.5 * band       # Beladung dominiert die Massenannahme


def test_slot_basis_needs_no_external_mass():
    """Die Slot-Basis ist szenariodefiniert (20 Paketslots / 8 Sitze) und
    haengt an KEINER Massenannahme -- deshalb ist sie die Pflicht-Begleitung
    (METHODS-LOG 2.26)."""
    import extract_emissions as ee
    sup = _sup()
    a = ee.slot_split(1.6, 50, sup)[1]
    b = ee.slot_split(1.6, 50, dict(sup, kg_per_parcel=2.50))[1]
    assert a == pytest.approx(b)        # invariant gegen die Massenkonstante
    # 50 Paketslots gegen 1.6 Sitze x 2.5 = 4 Slot-Aequivalente
    assert a == pytest.approx(50.0 / 54.0)


def test_mass_split_rejects_median_style_understatement():
    """Waechter gegen den Median-Griff: 0.6950 kg (Amaral-Median) statt des
    Mittels wuerde die Paketmasse um 58 % unterschaetzen. Der Loader lehnt
    Werte unterhalb des plausiblen Bandes (1.3 kg) ab."""
    import extract_emissions as ee
    with pytest.raises(ValueError):
        ee.mass_split(1.6, 50, dict(_sup(), kg_per_parcel=0.6950))


def test_empty_legs_allocated_proportionally():
    """GLEC-Konvention: Leerfahrten tragen keine kg*km-Basis und werden
    ueber die geladenen kg*km des Fahrzeugtages verteilt -- die Summe der
    zugerechneten Emissionen muss die Gesamtemission treffen."""
    import extract_emissions as ee
    # ein Link geladen, ein Link leer
    path = [("l1", 1, 20, 100.0), ("l2", 0, 0, 200.0)]
    link_len = {"l1": 1000.0, "l2": 1000.0}
    alloc = ee.allocate_vehicle_by_mass(path, link_len, 100.0, _sup())
    assert alloc["pax"] + alloc["parcels"] == pytest.approx(100.0)
    # der Leer-Link erbt die Anteile des geladenen
    assert alloc["parcels"] / 100.0 == pytest.approx(33.0 / 113.0)


def test_allocation_is_link_weighted_not_a_vehicle_average():
    """kg*km heisst: ein langer Link mit Fracht wiegt mehr als ein kurzer.
    Sonst waere es eine ungewichtete Mittelung ueber Links."""
    import extract_emissions as ee
    long_parcel = [("l1", 0, 20, 1.0), ("l2", 1, 0, 2.0)]
    link_len = {"l1": 9000.0, "l2": 1000.0}
    alloc = ee.allocate_vehicle_by_mass(long_parcel, link_len, 100.0, _sup())
    # 20 Pakete x 1.65 kg x 9 km = 297 kg*km gegen 80 kg x 1 km = 80 kg*km
    assert alloc["parcels"] == pytest.approx(100.0 * 297.0 / 377.0)


def test_allocation_all_empty_yields_zero_split():
    """Ein Fahrzeug, das nie beladen war, hat keine Aufteilungsbasis -- die
    Emission darf dann nicht willkuerlich verteilt werden."""
    import extract_emissions as ee
    alloc = ee.allocate_vehicle_by_mass([("l1", 0, 0, 1.0)], {"l1": 1000.0},
                                        100.0, _sup())
    assert alloc == {"pax": 0.0, "parcels": 0.0}


def test_specific_intensity_rows():
    import extract_emissions as ee
    rows = ee.intensity_rows(alloc={"pax": 300.0, "parcels": 700.0},
                             n_pax=150, n_parcels=1400, sup=_sup())
    by = {r["kpi_name"]: r for r in rows}
    assert by["co2e_wtw_per_pax"]["value"] == pytest.approx(2.0)
    assert by["co2e_wtw_per_parcel"]["value"] == pytest.approx(0.5)
    assert by["co2e_wtw_per_parcel"]["unit"] == "kg"
    assert "Amaral" in by["co2e_wtw_per_parcel"]["source"]
    # beide Konstanten und ihr Status muessen in der Provenance stehen
    assert "kg_per_passenger" in by["co2e_wtw_per_parcel"]["source"]
    # Berichtsregel METHODS-LOG 2.26: der Anteil steht IMMER neben der
    # Intensitaet, und die massenfreie Slot-Variante daneben
    assert by["alloc_share_parcels_mass"]["value"] == pytest.approx(0.7)
    assert "alloc_share_parcels_slots" in by


def test_intensity_rows_skip_division_by_zero():
    """Ohne bediente Menge gibt es keine Intensitaet -- die Zeile faellt
    weg, statt inf oder 0 zu behaupten."""
    import extract_emissions as ee
    rows = ee.intensity_rows({"pax": 300.0, "parcels": 0.0}, 150, 0, _sup())
    names = {r["kpi_name"] for r in rows}
    assert "co2e_wtw_per_pax" in names
    assert "co2e_wtw_per_parcel" not in names


# --- Task 7: KPI rows, EV range sweep, detail CSV -------------------------

def _rows_by_name(rows):
    return {r["kpi_name"]: r for r in rows}


def test_extract_freight_only_run(tmp_path):
    import extract_emissions as ee
    rows, detail = ee.extract(_run_dir(tmp_path), "test")
    by = _rows_by_name(rows)
    assert all(r["kpi_group"] == "environment" for r in rows)
    assert "freight_co2e_wtw" in by and "freight_co2e_wtw_bev" in by
    assert "total_co2e_wtw" in by
    assert by["freight_co2e_wtw"]["unit"] == "kg"
    assert by["freight_nox"]["unit"] == "g"
    # Rev. B: Sweep-Rows statt Einzel-Gate, plus Mix-Transparenz
    for thr in (150, 200, 250):
        assert "ev_range_exceed_freight_" + str(thr) in by
    assert "ev_range_exceed_freight" not in by      # kein Einzelwert mehr
    assert by["segment_km_share_n1_iii"]["value"] == pytest.approx(1.0)
    assert by["segment_km_share_n1_ii"]["value"] == pytest.approx(0.0)
    assert "2023 - Update 2025" in by["freight_co2e_wtw"]["source"]
    assert "drt_co2e_wtw" not in by                     # kein DRT-Input uebergeben
    # EV-Sweep: Touren sind 120/60 km -> bei 150/200/250 km alle 0
    assert by["ev_range_max_km_freight_tour"]["value"] == pytest.approx(120.0)
    for thr in (150, 200, 250):
        assert by["ev_range_exceed_freight_" + str(thr)]["value"] == 0.0
    assert "EMEP/EEA" in by["freight_co2e_wtw"]["source"]
    # BEV-Arm hat konstruktionsbedingt kein TTW-CO2
    assert "freight_co2e_ttw_bev" not in by and "freight_co2_bev" not in by


def test_extract_sweep_resolves_at_the_low_threshold(tmp_path, monkeypatch):
    """Die untere Schwelle muss greifen koennen -- genau das leistet der
    250-km-Einzelwert aus Rev. A nicht (0 % in allen realen Laeufen)."""
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    fac["sup"]["ev_range_km_low"] = 100.0        # Tour 1 (120 km) reisst
    monkeypatch.setattr(em, "load_factors", lambda data_dir=None: fac)
    rows, _ = ee.extract(_run_dir(tmp_path), "test")
    by = _rows_by_name(rows)
    assert by["ev_range_exceed_freight_100"]["value"] == pytest.approx(0.5)
    assert by["ev_range_exceed_freight_250"]["value"] == 0.0


def test_extract_mixed_fleet_reports_segment_shares(tmp_path):
    """base10c-Situation im Kleinen: gemischte Flotte -> die km-Anteile je
    Segment muessen im KPI-Kanal auftauchen, sonst ist ein CO2-Delta nicht
    in Mix- und Fahrleistungsanteil zerlegbar."""
    import extract_emissions as ee
    fr = tmp_path / "analysis" / "freight"
    fr.mkdir(parents=True)
    rows_in = [
        ["a", "dhl", "ct_cep_size_s", 1, 0, 0, 90000.0, 90.0, 10800, 3.0,
         0, 3.6e-4, 154.41, 0, 32.4, 186.8],
        ["b", "dhl", "ct_cep_size_m", 2, 0, 0, 30000.0, 30.0, 3600, 1.0,
         0, 3.7e-4, 171.78, 0, 11.2, 183.0],
    ]
    pd.DataFrame(rows_in, columns=TSV_COLS).to_csv(
        fr / "TimeDistance_perVehicle.tsv", sep="\t", index=False)
    rows, _ = ee.extract(tmp_path, "test")
    by = _rows_by_name(rows)
    assert by["segment_km_share_n1_ii"]["value"] == pytest.approx(0.75)
    assert by["segment_km_share_n1_iii"]["value"] == pytest.approx(0.25)


def test_extract_emits_intensities_only_when_counts_are_supplied(tmp_path):
    """Die Aufteilungs-Rows brauchen bediente Mengen, die der Extractor
    nicht selbst kennt (build_kpis liefert sie). Ohne Mengen darf keine
    Intensitaet erscheinen -- lieber gar keine Zahl als eine mit falschem
    Nenner."""
    import extract_emissions as ee
    ll = ee.load_link_lengths(_network(tmp_path), {"l1", "l2"})
    veh_path = {"drt_1": [("l1", 1, 20, 10.0), ("l2", 1, 20, 20.0)]}
    recon = {"per_veh": {"drt_1": {"drive_s": 200.0}}}
    common = dict(recon=recon, veh_path=veh_path,
                  network_gz=_network(tmp_path))
    rows_no, _ = ee.extract(tmp_path, "test", **common)
    assert "co2e_wtw_per_parcel" not in _rows_by_name(rows_no)
    rows_yes, _ = ee.extract(tmp_path, "test", n_pax=40, n_parcels=500,
                             **common)
    by = _rows_by_name(rows_yes)
    assert by["co2e_wtw_per_parcel"]["unit"] == "kg"
    assert "alloc_share_parcels_mass" in by
    assert "alloc_share_parcels_slots" in by
    # die Zurechnung darf die allokationsfreie Summe nicht verletzen
    tot = by["total_co2e_wtw"]["value"]
    parts = (by["co2e_wtw_per_pax"]["value"] * 40
             + by["co2e_wtw_per_parcel"]["value"] * 500)
    assert parts == pytest.approx(tot, rel=1e-9)
    assert ll["l1"] == 1200.0        # Fixture-Anker


def test_write_detail(tmp_path):
    import extract_emissions as ee

    class Meta:
        run_id = "RUN1"
    rows, detail = ee.extract(_run_dir(tmp_path), "test")
    out = tmp_path / "kpi_emissions_vehicles.csv"
    ee.write_detail(detail, Meta(), out)
    txt = out.read_text(encoding="utf-8")
    assert txt.splitlines()[0].startswith(
        "run_id;fleet;entity;vehicle_type;segment;km;v_kmh;powertrain")
    assert "RUN1" in txt and "freight_dhl_veh_a_1" in txt


def test_intensity_rows_need_a_parcel_km_basis(tmp_path):
    """Die Massenzurechnung ist eine 1c-Konstruktion: dort fahren Pakete als
    PERSONEN mit (PARCEL_PERSON_PREFIX) und tauchen deshalb in occ_parcels
    auf. Im 1d-Arm reisen sie als Kapsel, occ_parcels ist ueberall 0 -- eine
    dann emittierte alloc_share_parcels_mass = 0 wuerde behaupten, die
    Fracht dieses Laufs sei emissionsfrei. Ohne kg*km-Basis auf der
    Paketseite darf keine Zurechnungszeile entstehen; die Regimeaufteilung
    (freight_modular_* vs. drt_*) ist dort die richtige Zerlegung."""
    import extract_emissions as ee
    veh_path = {"drt_1": [("l1", 1, 0, 10.0), ("l2", 1, 0, 20.0)]}
    recon = {"per_veh": {"drt_1": {"drive_s": 200.0}}}
    rows, _ = ee.extract(tmp_path, "test", recon=recon, veh_path=veh_path,
                         network_gz=_network(tmp_path),
                         n_pax=40, n_parcels=500)
    by = _rows_by_name(rows)
    assert "drt_co2e_wtw" in by                  # der Arm selbst rechnet weiter
    assert "co2e_wtw_per_parcel" not in by
    assert "co2e_wtw_per_pax" not in by
    assert "alloc_share_parcels_mass" not in by
    assert "alloc_share_parcels_slots" not in by


def test_ev_range_rows_carry_their_entity_definition(tmp_path):
    """Die drei Flotten sehen im CSV vergleichbar aus (ev_range_exceed_<fleet>
    _150) und sind es nicht: auf base10c reisst Freight die 150 km bei 3,2 %
    der TOUREN, DRT bei 96,7 % der FAHRZEUGTAGE. Wer das nebeneinander liest,
    schliesst faelschlich "DRT ist nicht elektrifizierbar". Der Vorbehalt muss
    in der Zeile stehen, nicht in einem Dokument -- wer kpis_long.csv liest,
    sieht das Dokument nie."""
    import extract_emissions as ee
    veh_path = {"drt_1": [("l1", 1, 0, 10.0), ("l2", 1, 0, 20.0)]}
    recon = {"per_veh": {"drt_1": {"drive_s": 200.0}}}
    rows, _ = ee.extract(_run_dir(tmp_path), "test", recon=recon,
                         veh_path=veh_path, network_gz=_network(tmp_path))
    by = _rows_by_name(rows)
    drt_src = by["ev_range_exceed_drt_150"]["source"]
    fr_src = by["ev_range_exceed_freight_150"]["source"]
    assert "VEHICLE-DAY" in drt_src and "charging" in drt_src
    assert "NOT an electrification verdict" in drt_src
    assert "TOUR" in fr_src and "continuous shift" in fr_src
    assert drt_src != fr_src


def test_segment_share_is_not_diluted_by_the_drt_fleet(tmp_path):
    """Der Fahrzeugmix ist nur auf der FRACHTSEITE ein Ergebnis; das
    DRT-Segment ist die feste M2->N1-III-Ersetzung. Auf base10c wog der
    DRT-Arm die Vans etwa 4:1 aus, sodass n1_ii = 0,107 herauskam -- fuer
    denselben LMD-Plan, der auf dem frachtreinen bandz_central 0,926 liest.
    Zwei unvergleichbare Zahlen fuer einen identischen Mix sind schlechter
    als keine, also darf ein hinzukommender DRT-Arm den Anteil NICHT
    veraendern."""
    import extract_emissions as ee
    fr = tmp_path / "analysis" / "freight"
    fr.mkdir(parents=True)
    pd.DataFrame([
        ["a", "dhl", "ct_cep_size_s", 1, 0, 0, 90000.0, 90.0, 10800, 3.0,
         0, 3.6e-4, 154.41, 0, 32.4, 186.8],
        ["b", "dhl", "ct_cep_size_m", 2, 0, 0, 30000.0, 30.0, 3600, 1.0,
         0, 3.7e-4, 171.78, 0, 11.2, 183.0],
    ], columns=TSV_COLS).to_csv(fr / "TimeDistance_perVehicle.tsv",
                                sep="\t", index=False)
    freight_only, _ = ee.extract(tmp_path, "test")
    # derselbe Lauf plus ein DRT-Arm, dessen km die Vans deutlich uebersteigen
    veh_path = {"drt_1": [("l1", 1, 0, 10.0), ("l2", 1, 0, 20.0)]}
    with_drt, _ = ee.extract(tmp_path, "test",
                             recon={"per_veh": {"drt_1": {"drive_s": 200.0}}},
                             veh_path=veh_path, network_gz=_network(tmp_path))
    a, b = _rows_by_name(freight_only), _rows_by_name(with_drt)
    assert "drt_co2e_wtw" in b                       # der Arm ist wirklich da
    assert b["segment_km_share_n1_ii"]["value"] == pytest.approx(0.75)
    assert (b["segment_km_share_n1_ii"]["value"]
            == pytest.approx(a["segment_km_share_n1_ii"]["value"]))
    src = b["segment_km_share_n1_ii"]["source"]
    assert "CONVENTIONAL VAN" in src and "DRT and modular are excluded" in src


def test_segment_share_absent_on_a_pax_only_run(tmp_path):
    """Ohne Frachtflotte gibt es keinen Mix zu berichten -- eine Zeile mit
    n1_iii = 1,0 aus der festen DRT-Ersetzung waere eine Scheinaussage."""
    import extract_emissions as ee
    veh_path = {"drt_1": [("l1", 1, 0, 10.0), ("l2", 1, 0, 20.0)]}
    rows, _ = ee.extract(tmp_path, "test",
                         recon={"per_veh": {"drt_1": {"drive_s": 200.0}}},
                         veh_path=veh_path, network_gz=_network(tmp_path))
    by = _rows_by_name(rows)
    assert "drt_co2e_wtw" in by
    assert "segment_km_share_n1_ii" not in by
    assert "segment_km_share_n1_iii" not in by


# --- Task 5: cold-start counting + wiring ----------------------------------

SOAK_S = 3600.0


def test_cold_starts_counts_shift_start_plus_long_stays():
    from extract_emissions import cold_starts_by_regime
    seq = [(0, 600, "DRIVE"),
           (600, 1200, "STAY"),          # 10 min -> zu kurz
           (1200, 1800, "DRIVE"),
           (1800, 7200, "STAY"),         # 90 min -> Kaltstart
           (7200, 9000, "DRIVE")]
    assert cold_starts_by_regime(seq, SOAK_S) == {"drt": 2, "freight_modular": 0}


def test_cold_starts_attributes_to_the_following_regime():
    """Spec E6: der Start geht an das Regime des FOLGENDEN Fahrblocks --
    nur so bleibt drt_* + freight_modular_* == total_* exakt."""
    from extract_emissions import cold_starts_by_regime
    seq = [(0, 600, "FREIGHT_DRIVE"),
           (600, 7200, "STAY"),
           (7200, 9000, "DRIVE")]
    assert cold_starts_by_regime(seq, SOAK_S) == {"drt": 1, "freight_modular": 1}


def test_trailing_stay_without_a_following_drive_does_not_count():
    from extract_emissions import cold_starts_by_regime
    seq = [(0, 600, "DRIVE"), (600, 30000, "STAY")]
    assert cold_starts_by_regime(seq, SOAK_S) == {"drt": 1, "freight_modular": 0}


def test_stay_exactly_at_the_threshold_counts():
    """>= , nicht > . Die Grenze wird einmal festgelegt und getestet."""
    from extract_emissions import cold_starts_by_regime
    seq = [(0, 600, "DRIVE"), (600, 600 + SOAK_S, "STAY"),
           (600 + SOAK_S, 9000, "DRIVE")]
    assert cold_starts_by_regime(seq, SOAK_S)["drt"] == 2


def test_vehicle_that_never_drives_yields_no_cold_start():
    from extract_emissions import cold_starts_by_regime
    assert cold_starts_by_regime([(0, 30000, "STAY")], SOAK_S) == {
        "drt": 0, "freight_modular": 0}


def test_stop_between_two_drives_does_not_break_the_block():
    """Ein Bedienhalt ist keine Auskuehlphase -- nur STAY zaehlt."""
    from extract_emissions import cold_starts_by_regime
    seq = [(0, 600, "DRIVE"), (600, 5000, "STOP"), (5000, 6000, "DRIVE")]
    assert cold_starts_by_regime(seq, SOAK_S)["drt"] == 1


def test_freight_arm_books_one_cold_start_per_tour(tmp_path):
    """Spec E5: die TSV hat keine Task-Sequenz, also 1 je Tour."""
    import extract_emissions as ee
    import emissions_emep as em
    run = tmp_path / "run"
    (run / "analysis" / "freight").mkdir(parents=True)
    (run / "analysis" / "freight" / "TimeDistance_perVehicle.tsv").write_text(
        "vehicleId\tvehicleTypeId\ttravelDistance[km]\ttravelTime[s]\n"
        "v1\tct_cep_size_m\t99.24\t9817\n", encoding="utf-8")
    totals, detail = ee.freight_arm(str(run), em.load_factors())
    diesel = [d for d in detail if d["powertrain"] == "diesel"][0]
    assert diesel["n_cold"] == 1
    assert diesel["cold_NOx"] > 0.0
    # Der Zuschlag steckt im Total (Spec E1), nicht daneben.
    assert totals["diesel"]["NOx"] == pytest.approx(
        diesel["NOx"], rel=1e-9)


# --- Task 5 review (Important): the arms must select the CORRECT regime key,
# not merely a nonzero one -- a swapped ["drt"] <-> ["freight_modular"]
# selection would still pass every prior test (n_cold was always 0 or the
# same on both sides in the fixtures used so far). Both fixtures below give
# the two regimes DIFFERENT nonzero counts so the numbers cannot be confused.

def test_drt_arm_wires_the_drt_cold_start_count_not_the_freight_one():
    """drt_arm reads cold_starts_by_regime(...)["drt"]. A real STAY >= soak
    followed by a driving block must land there -- and the fixture also
    contains a FREIGHT_DRIVE cold start with a DIFFERENT count, so a swap to
    ["freight_modular"] inside drt_arm would produce 1, not 2, here."""
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    # Schichtbeginn FREIGHT_DRIVE (-> freight_modular=1), dann zwei separate
    # >=60-min-STAYs vor je einem DRIVE (-> drt=2). drt und freight_modular
    # sind absichtlich UNGLEICH.
    seq = [(0, 600, "FREIGHT_DRIVE"),
           (600, 4200, "STAY"),          # 60 min -> Kaltstart
           (4200, 4800, "DRIVE"),
           (4800, 8400, "STAY"),         # 60 min -> Kaltstart
           (8400, 9000, "DRIVE")]
    veh_path = {"drt_1": [("l1", 0, 0, 10.0), ("l2", 1, 0, 20.0)]}
    recon = {"per_veh": {"drt_1": {"drive_s": 1200.0, "task_seq": seq}}}
    ll = {"l1": 1200.0, "l2": 800.0}
    _, detail = ee.drt_arm(veh_path, recon, ll, fac)
    d = [x for x in detail if x["powertrain"] == "diesel"][0]
    assert d["n_cold"] == 2                  # die DRT-Zahl aus der Mischung
    assert d["n_cold"] != 1                  # NICHT die freight_modular-Zahl


def test_extract_wires_freight_modular_cold_starts_to_the_correct_regime(tmp_path):
    """extract() baut fm_cold aus cold_starts_by_regime(...)["freight_modular"]
    und drt_arm zieht ["drt"] aus derselben task_seq. Dieselbe Mischung wie
    oben (drt=2, freight_modular=1) macht BEIDE Selektionsstellen
    vertauschungssicher: waehlt eine der beiden den falschen Key, landet
    die jeweils andere Zahl im Detail."""
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    seq = [(0, 600, "FREIGHT_DRIVE"),
           (600, 4200, "STAY"),
           (4200, 4800, "DRIVE"),
           (4800, 8400, "STAY"),
           (8400, 9000, "DRIVE")]
    # l2 liegt im Freight-Fenster (t=300), l1 liegt in der Pax-Fahrt (t=4500)
    veh_path = {"drt_1": [("l2", 0, 0, 300.0), ("l1", 0, 0, 4500.0)]}
    recon = {"per_veh": {"drt_1": {"drive_s": 1200.0, "task_seq": seq}}}
    ev = tmp_path / "TEST.drt_events_filtered.txt"
    ev.write_text(
        '<event time="0.0" type="dvrpTaskStarted" dvrpVehicle="drt_1" '
        'taskType="MODULAR_FREIGHT_DRIVE" dvrpMode="drt"  />\n'
        '<event time="600.0" type="dvrpTaskEnded" dvrpVehicle="drt_1" '
        'taskType="MODULAR_FREIGHT_DRIVE" dvrpMode="drt"  />\n',
        encoding="utf-8")
    rows, detail = ee.extract(tmp_path, "test", recon=recon,
                              veh_path=veh_path,
                              network_gz=_network(tmp_path),
                              drt_task_events=ev)
    d_drt = [x for x in detail
             if x["fleet"] == "drt" and x["powertrain"] == "diesel"][0]
    d_fm = [x for x in detail
            if x["fleet"] == "freight_modular" and x["powertrain"] == "diesel"][0]
    assert d_drt["n_cold"] == 2               # nicht die freight_modular-Zahl (1)
    assert d_fm["n_cold"] == 1                # nicht die drt-Zahl (2)
