# EMEP/EEA Tier-3 Emissions (Lausitz KPI-Stack) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Distanz- und geschwindigkeitsbasierte Tank-to-Wheel/Well-to-Wheel-Emissions-KPIs (CO₂e-Kern + NOx/PM/CO/VOC/CH4/SPN23 + Energie) für DRT- und LMD-Flotte aller Lausitz-Szenarien, aus EMEP/EEA-Guidebook-2025-Tier-3-Faktoren, als reines Post-Processing im bestehenden `analysis/kpi/`-Stack — inklusive BEV-Faktorsatz-Arm und EV-Reichweiten-Gate.

**Architecture:** Ein einmaliges Extraktions-Tool zieht die COPERT-5.9.1-Koeffizienten aus dem Appendix-4-xlsx in eine committete, zitierfähige CSV. Ein neues Modul `emissions_emep.py` evaluiert die Tier-3-Kurven `EF(v) = (αv²+βv+γ+δ/v)/(εv²+ζv+η)·(1−RF)` und rechnet daraus pro Fahrzeug/Tour den vollen Schadstoffvektor (Diesel- UND BEV-Arm in einem Durchlauf). `extract_emissions.py` speist das als neue KPI-Gruppe `environment` in die bestehende `build_kpis.py`-Pipeline ein — Freight aus `TimeDistance_perVehicle.tsv` (Distanz+Fahrzeit je Tour), DRT aus Event-Pfadrekonstruktion (echte Link-Längen) + `reconstruct()`-DRIVE-Zeiten. Kein MATSim-Re-Run, keine Java-Änderung.

**Tech Stack:** Python 3 (pandas, openpyxl), pytest; bestehende KPI-Pipeline `parcel-demand-2-matsim-pipeline/analysis/kpi/`.

## Global Constraints

- **`src/hagrid_output_analysis/**` (insb. `emissions.py`, `config.py`) wird NICHT angefasst** — Kollegen-Paper-Freeze bis mind. 2026-08-11 (User 2026-07-28). Alles Neue lebt in `analysis/kpi/`.
- Windows/cp1252: **ASCII-only in allen `print()`**; Python immer `python -u`.
- KPI-Konventionen: Zeilen via `common.row(kpi_group, kpi_name, value, unit, source)`; Extractor bleibt run-agnostisch (kein run_id in den Rows); neue Gruppe heißt exakt `"environment"`.
- Faktor-Provenance in jeder Quellenangabe: „EMEP/EEA Guidebook 2025, App. 4 (Okt 2025, COPERT 5.9.1)".
- **Gotcha:** Spalte `Reduction Factor [%]` im xlsx enthält **Bruchteile, nicht Prozent** (NOx Euro 7 = 0.282175 ≙ 28,2 %). Formel: `EF = (α·v² + β·v + γ + δ/v) / (ε·v² + ζ·v + η) · (1 − RF)`, v geclampt auf [Min Speed, Max Speed]. Validiert gegen die mitgelieferte EF(v=80)-Kontrollspalte (EC N1-III Diesel Euro 7 DPF+SCR: 2.7322 MJ/km).
- Klassenmapping (User-entschieden 2026-07-28): **beide Flotten N1-III Diesel Euro 7, Technologie DPF+SCR**; BEV-Arm: N1-III Battery electric Euro 7 (nur EC-Kurve).
- Xlsx-Quelle: `parcel-demand-2-matsim-pipeline/hagrid-input/emissions/1.A.3.b.i-iv Road Transport Appendix 4 Emission Factors Oct_2025.xlsx` (2026-07-28 aus `~/Downloads` dorthin verlegt; Provenance-Tabelle: `hagrid-input/emissions/SOURCES.md`) — wird NICHT committet (Größe, wie der übrige `hagrid-input`-Inhalt); committet werden die extrahierten CSVs + `data/README.md` mit URL/Version/Download-Datum.
- Tests laufen aus `parcel-demand-2-matsim-pipeline/analysis/kpi/`: `python -u -m pytest tests/<file> -v`.
- Git: auf Branch `hendrik` committen; Commit-Messages enden mit `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## File Structure

- Create: `analysis/kpi/emep_factor_extract.py` — einmaliges xlsx→CSV-Tool (committet für Reproduzierbarkeit)
- Create: `analysis/kpi/data/emep_hot_factors.csv` — Tier-3-Koeffizienten (7 Diesel-Zeilen + 1 BEV-EC-Zeile)
- Create: `analysis/kpi/data/emep_supplement.csv` — Energiekette/GWP/N2O/Non-Exhaust/BEV-Korrekturen/EV-Reichweite, je mit Quelle
- Create: `analysis/kpi/data/README.md` — Quellendokumentation (zitierfähig, Paper-Referenz)
- Create: `analysis/kpi/emissions_emep.py` — Faktor-Loader, `ef(v)`, `vehicle_emissions()`, `non_exhaust_pm10()`
- Create: `analysis/kpi/extract_emissions.py` — Freight-Arm, DRT-Arm, EV-Range-Check, Detail-CSV-Writer
- Modify: `analysis/kpi/common.py` — `KPI_GROUPS` + `"environment"`
- Modify: `analysis/kpi/build_kpis.py` — Geometrie-Block vorziehen + Extractor-Aufruf
- Modify: `analysis/kpi/render.py:305` — Gruppe in der KPI-Tabellen-Ansicht
- Test: `analysis/kpi/tests/test_emissions_emep.py`, `analysis/kpi/tests/test_extract_emissions.py`

---

### Task 1: Faktor-Extraktions-Tool + committete Hot-Factor-CSV

**Files:**
- Create: `analysis/kpi/emep_factor_extract.py`
- Create: `analysis/kpi/data/emep_hot_factors.csv` (vom Tool erzeugt, dann committet)
- Test: `analysis/kpi/tests/test_emissions_emep.py`

**Interfaces:**
- Produces: `data/emep_hot_factors.csv` mit Spalten (exakt): `powertrain,pollutant,alpha,beta,gamma,delta,epsilon,zita,hta,rf,vmin,vmax,ef_check_v,ef_check,unit,source` — konsumiert von Task 2 `load_factors()`.
- `powertrain` ∈ {`diesel`,`bev`}; `pollutant` ∈ {`CO`,`NOx`,`VOC`,`PM Exhaust`,`EC`,`CH4`,`SPN23`} (diesel) bzw. {`EC`} (bev). `unit`: `g/km` außer `EC`→`MJ/km`, `SPN23`→`#/km`. `rf` als Bruchteil.

- [ ] **Step 1: Failing Test schreiben** — Transform-Funktion wird gegen einen In-Memory-DataFrame getestet (kein xlsx im Test nötig):

```python
# tests/test_emissions_emep.py
import pandas as pd
import pytest

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
    # nur N1-III Euro 7: Diesel-DPF+SCR-Zeilen + BEV-EC; DPF-only und Bus fliegen raus
    assert set(out["powertrain"]) == {"diesel", "bev"}
    nox = out[(out["powertrain"] == "diesel") & (out["pollutant"] == "NOx")]
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
    assert (out["source"].str.contains("COPERT 5.9.1")).all()
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v` (aus `analysis/kpi/`)
Expected: FAIL — `ModuleNotFoundError: emep_factor_extract`

- [ ] **Step 3: Tool implementieren**

```python
# -*- coding: utf-8 -*-
# analysis/kpi/emep_factor_extract.py
"""One-time extraction of the Tier-3 hot emission factor coefficients from
the EMEP/EEA Guidebook 2025 Appendix 4 xlsx (Oct 2025, COPERT 5.9.1) into
the committed data/emep_hot_factors.csv.

Fleet mapping (user decision 2026-07-28): BOTH fleets (DRT minibuses, LMD
vans ct_cep_size_s/m/l) = LCV N1-III Diesel Euro 7, technology DPF+SCR;
BEV arm = N1-III Battery electric (EC curve only).

NOTE the xlsx gotcha: column 'Reduction Factor [%]' holds FRACTIONS
(0.282175 = 28.2 %), not percent values. Copied through unchanged.

Usage:
    python -u emep_factor_extract.py "C:/Users/.../Appendix4.xlsx"
"""
import sys
from pathlib import Path

import pandas as pd

SOURCE = ("EMEP/EEA Guidebook 2025, ch. 1.A.3.b.i-iv Appendix 4 "
          "(Oct 2025, COPERT 5.9.1)")
UNIT_BY_POLL = {"EC": "MJ/km", "SPN23": "#/km"}   # default: g/km
COLMAP = {"Pollutant": "pollutant", "Alpha": "alpha", "Beta": "beta",
          "Gamma": "gamma", "Delta": "delta", "Epsilon": "epsilon",
          "Zita": "zita", "Hta": "hta", "Reduction Factor [%]": "rf",
          "Min Speed [km/h]": "vmin", "Max Speed [km/h]": "vmax"}
EF_COL = "EF [g/km] or ECF [MJ/km] or #/km or #/kWh or g/kWh"


def transform(df):
    """Filter the HOT_EMISSIONS_PARAMETERS sheet to the two factor sets and
    map to the committed CSV schema. Returns a DataFrame."""
    lcv = df[(df["Category"] == "Light Commercial Vehicles")
             & (df["Segment"] == "N1-III")
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
    cols = ["powertrain", "pollutant", "alpha", "beta", "gamma", "delta",
            "epsilon", "zita", "hta", "rf", "vmin", "vmax",
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
```

- [ ] **Step 4: Test laufen lassen, PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS

- [ ] **Step 5: Tool einmal real ausführen und CSV erzeugen**

Run (aus `analysis/kpi/`):
`python -u emep_factor_extract.py "C:/Users/Hendrik Bimmermann/Downloads/1.A.3.b.i-iv Road Transport Appendix 4 Emission Factors Oct_2025.xlsx"`
Expected: `wrote .../data/emep_hot_factors.csv (8 rows)` — 7 Diesel-Zeilen (CO, NOx, VOC, PM Exhaust, EC, CH4, SPN23) + 1 BEV-EC-Zeile. CSV öffnen und Stichprobe prüfen: Diesel-EC `ef_check` ≈ 2.7322, NOx `rf` ≈ 0.282175.

- [ ] **Step 6: Commit**

```bash
git add analysis/kpi/emep_factor_extract.py analysis/kpi/data/emep_hot_factors.csv analysis/kpi/tests/test_emissions_emep.py
git commit -m "feat(emissions): EMEP/EEA App.4 Tier-3 factor extraction (N1-III Euro 7 DPF+SCR + BEV)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Faktor-Loader + Tier-3-Kurvenauswertung `ef(v)`

**Files:**
- Create: `analysis/kpi/emissions_emep.py`
- Test: `analysis/kpi/tests/test_emissions_emep.py` (erweitern)

**Interfaces:**
- Consumes: `data/emep_hot_factors.csv` (Task-1-Schema).
- Produces: `emissions_emep.load_factors(data_dir=None) -> dict` mit Struktur `{"diesel": {pollutant: coef}, "bev": {"EC": coef}, "sup": {}}` (`sup` füllt Task 3; `coef` = dict mit float-Keys `alpha,beta,gamma,delta,epsilon,zita,hta,rf,vmin,vmax,ef_check_v,ef_check` + str `unit`). — `emissions_emep.ef(v_kmh, coef) -> float`.

- [ ] **Step 1: Failing Tests anhängen** — der Regressionstest validiert JEDE committete Zeile gegen die mitgelieferte EF(80)-Kontrollspalte:

```python
# an tests/test_emissions_emep.py anhängen
def test_ef_reproduces_appendix_check_column_for_every_row():
    import emissions_emep as em
    fac = em.load_factors()
    checked = 0
    for pt in ("diesel", "bev"):
        for poll, c in fac[pt].items():
            got = em.ef(c["ef_check_v"], c)
            # rel=1e-4: validiert Formelstruktur + RF-als-Bruchteil (ein
            # Prozent-Fehler laege ~39 % daneben); letzte Stellen der
            # xlsx-Kontrollspalte koennen rundungsbedingt abweichen.
            assert got == pytest.approx(c["ef_check"], rel=1e-4), (pt, poll)
            checked += 1
    assert checked == 8

def test_ef_clamps_speed_to_curve_range():
    import emissions_emep as em
    fac = em.load_factors()
    c = fac["diesel"]["NOx"]           # vmin=5, vmax=140
    assert em.ef(1.0, c) == em.ef(5.0, c)
    assert em.ef(200.0, c) == em.ef(140.0, c)
    assert em.ef(80.0, c) > 0
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: FAIL — `ModuleNotFoundError: emissions_emep`

- [ ] **Step 3: Modul implementieren**

```python
# -*- coding: utf-8 -*-
# analysis/kpi/emissions_emep.py
"""EMEP/EEA Tier-3 emission factor evaluation for the Lausitz KPI stack.

Deliberately independent of src/hagrid_output_analysis/emissions.py (that
module backs a colleague's published paper and is frozen; user decision
2026-07-28). Factor data lives in data/*.csv with full provenance columns.

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
    d = Path(data_dir) if data_dir else DATA_DIR
    fac = {"diesel": {}, "bev": {}, "sup": {}}
    with open(d / "emep_hot_factors.csv", newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            coef = {k: float(r[k]) for k in _NUM}
            coef["unit"] = r["unit"]
            fac[r["powertrain"]][r["pollutant"]] = coef
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
```

- [ ] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS (alle 8 Zeilen reproduzieren die Kontrollspalte; falls eine Zeile mit rel=1e-6 scheitert, prüfen ob das Tool volle Zellpräzision exportiert hat — `to_csv` ohne `float_format` behält volle Präzision, das ist gewollt)

- [ ] **Step 5: Commit**

```bash
git add analysis/kpi/emissions_emep.py analysis/kpi/tests/test_emissions_emep.py
git commit -m "feat(emissions): Tier-3 curve evaluation validated against Appendix-4 check column

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Supplement-Konstanten (Energiekette, GWP, N2O, Non-Exhaust, BEV, EV-Range)

**Files:**
- Create: `analysis/kpi/data/emep_supplement.csv`
- Create: `analysis/kpi/data/README.md`
- Test: `analysis/kpi/tests/test_emissions_emep.py` (erweitern)

**Interfaces:**
- Produces: `emep_supplement.csv` mit Spalten `name,value,unit,source` und exakt diesen `name`-Keys (von Task 4 konsumiert): `ttw_co2_g_per_mj_diesel`, `wtt_co2e_g_per_mj_diesel`, `grid_co2e_g_per_mj`, `gwp_ch4`, `gwp_n2o`, `n2o_g_per_km_diesel_lcv`, `tsp_tyre_g_per_km_lcv`, `tsp_brake_g_per_km_lcv`, `tsp_road_g_per_km_lcv`, `pm10_frac_tyre`, `pm10_frac_brake`, `pm10_frac_road`, `bev_tyre_mult`, `bev_brake_mult`, `ev_range_km`.

- [ ] **Step 1: Kandidatenwerte gegen die Originalquellen verifizieren.** Die Werte unten sind fachlich begründete Kandidaten; VOR dem Commit jeden gegen die genannte Quelle prüfen und bei Abweichung korrigieren (Quelle gewinnt immer):
  - Non-Exhaust-Basen + PM10-Anteile + Speed-Korrekturen: EMEP/EEA Guidebook, Kapitel **1.A.3.b.vi-vii** (Tyre/brake/road wear), PDF frei auf https://www.eea.europa.eu/en/analysis/publications/emep-eea-guidebook-2025 — Tier-2-Tabellen für LCV.
  - N2O für Diesel-LCV Euro 6/7: Kapitel **1.A.3.b.i-iv** Hauptteil, N2O/NH3-Tabellen (Tier 2, g/km hot).
  - WTT Diesel: JEC WTW v5 bzw. GLEC Framework v3; Netz-Intensität: UBA „CO2-Emissionsfaktor Strommix" (aktuellster Jahreswert).
  - GWP100: IPCC AR6 (CH4 fossil 29.8, N2O 273).

- [ ] **Step 2: Failing Test anhängen**

```python
# an tests/test_emissions_emep.py anhängen
def test_supplement_present_and_plausible():
    import emissions_emep as em
    sup = em.load_factors()["sup"]
    required = ["ttw_co2_g_per_mj_diesel", "wtt_co2e_g_per_mj_diesel",
                "grid_co2e_g_per_mj", "gwp_ch4", "gwp_n2o",
                "n2o_g_per_km_diesel_lcv",
                "tsp_tyre_g_per_km_lcv", "tsp_brake_g_per_km_lcv",
                "tsp_road_g_per_km_lcv", "pm10_frac_tyre",
                "pm10_frac_brake", "pm10_frac_road",
                "bev_tyre_mult", "bev_brake_mult", "ev_range_km"]
    for k in required:
        assert k in sup, k
    assert 65 < sup["ttw_co2_g_per_mj_diesel"] < 80      # ~74 g CO2/MJ Diesel TTW
    assert 10 < sup["wtt_co2e_g_per_mj_diesel"] < 30
    assert 0 < sup["n2o_g_per_km_diesel_lcv"] < 0.1
    assert 25 < sup["gwp_ch4"] < 35 and 250 < sup["gwp_n2o"] < 300
    assert 0 < sup["pm10_frac_brake"] <= 1.0
    assert sup["bev_brake_mult"] < 1.0 < sup["bev_tyre_mult"]
    assert 150 <= sup["ev_range_km"] <= 400
```

- [ ] **Step 3: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py::test_supplement_present_and_plausible -v`
Expected: FAIL — `emep_supplement.csv` fehlt (leeres `sup`)

- [ ] **Step 4: CSV schreiben** (Werte ggf. per Step-1-Verifikation korrigiert; `source` dann entsprechend präzisieren):

```csv
name,value,unit,source
ttw_co2_g_per_mj_diesel,74.2,g CO2/MJ,"3.169 kg CO2/kg / 42.7 MJ/kg Diesel; EMEP/EEA GB 1.A.3.b.i-iv fuel-based CO2"
wtt_co2e_g_per_mj_diesel,18.9,g CO2e/MJ,"JEC WTW v5 diesel B7 upstream"
grid_co2e_g_per_mj,105.6,g CO2e/MJ,"UBA Strommix DE ~380 g CO2e/kWh (Sensitivitaetsparameter, im Paper variieren)"
gwp_ch4,29.8,kg CO2e/kg,"IPCC AR6 GWP100 fossil methane"
gwp_n2o,273.0,kg CO2e/kg,"IPCC AR6 GWP100"
n2o_g_per_km_diesel_lcv,0.010,g/km,"EMEP/EEA GB 1.A.3.b.i-iv Tier-2 N2O hot, diesel LCV Euro 6+ (VERIFY step 1)"
tsp_tyre_g_per_km_lcv,0.0169,g/km,"EMEP/EEA GB 1.A.3.b.vi Tier-2 tyre wear TSP, LCV"
tsp_brake_g_per_km_lcv,0.0117,g/km,"EMEP/EEA GB 1.A.3.b.vi Tier-2 brake wear TSP, LCV"
tsp_road_g_per_km_lcv,0.0150,g/km,"EMEP/EEA GB 1.A.3.b.vii Tier-2 road surface wear TSP, LCV"
pm10_frac_tyre,0.60,fraction,"EMEP/EEA GB 1.A.3.b.vi mass fraction PM10 of TSP, tyre"
pm10_frac_brake,0.98,fraction,"EMEP/EEA GB 1.A.3.b.vi mass fraction PM10 of TSP, brake"
pm10_frac_road,0.50,fraction,"EMEP/EEA GB 1.A.3.b.vii mass fraction PM10 of TSP, road"
bev_tyre_mult,1.15,factor,"assumption: BEV mass penalty on tyre wear (lit. range 1.1-1.2); sensitivity param"
bev_brake_mult,0.50,factor,"assumption: regenerative braking (lit. range 0.3-0.7); sensitivity param"
ev_range_km,250.0,km,"conservative real-world winter range, e-LCV class (assumption; gate threshold)"
```

Dazu `data/README.md` (zitierfähige Quellendoku):

```markdown
# Emissionsfaktor-Quellen (Lausitz-Emissions-KPIs)

## emep_hot_factors.csv
Extrahiert aus: EMEP/EEA air pollutant emission inventory guidebook 2025,
Kapitel 1.A.3.b.i-iv "Road transport", **Appendix 4** (Version Okt 2025,
verlinkt auf COPERT v5.9.1). Download: 2026-07-28 von
https://www.eea.europa.eu/en/analysis/publications/emep-eea-guidebook-2025
Extraktion: `emep_factor_extract.py` (Filter: LCV N1-III, Euro 7,
Diesel DPF+SCR bzw. Battery electric). Formel und EF(80)-Validierung:
siehe `emissions_emep.py` + `tests/test_emissions_emep.py`.
ACHTUNG: Spalte "Reduction Factor [%]" der Quelle enthaelt Bruchteile.

## emep_supplement.csv
Je Zeile Quelle in der `source`-Spalte. Euro-7-Faktoren sind aus
Grenzwerten PROJIZIERT (Norm greift fuer LCV ab ~2026/27) - im Paper
kennzeichnen. Klassenmapping-Entscheidung (2026-07-28): beide Flotten
(DRT-Minibus Sprinter-Klasse, LMD-Vans ct_cep_size_s/m/l) = N1-III;
`ct_cep_size_l` (230 Pakete, 6 m) ist Grauzone zum leichten Lkw ->
optionale Sensitivitaet HDT "Rigid <=7.5 t" (nicht implementiert).
```

- [ ] **Step 5: PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS (alle Tests, auch die aus Task 1/2)

- [ ] **Step 6: Commit**

```bash
git add analysis/kpi/data/emep_supplement.csv analysis/kpi/data/README.md analysis/kpi/tests/test_emissions_emep.py
git commit -m "feat(emissions): supplement constants (energy chain, GWP, N2O, non-exhaust, BEV, EV range) with provenance

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Rechenkern `vehicle_emissions()` + `non_exhaust_pm10()`

**Files:**
- Modify: `analysis/kpi/emissions_emep.py`
- Test: `analysis/kpi/tests/test_emissions_emep.py` (erweitern)

**Interfaces:**
- Produces (von Task 5/6 konsumiert): `vehicle_emissions(km, v_kmh, powertrain, fac) -> dict[str, float]` mit exakt den Keys `CO, NOx, VOC, PM_EXHAUST, CH4, SPN23, N2O, CO2, CO2E_TTW, CO2E_WTW, ENERGY_MJ, PM10_TYRE, PM10_BRAKE, PM10_ROAD, PM10_NONEXHAUST` — Massen in Gramm, `ENERGY_MJ` in MJ, `SPN23` in Partikelanzahl. `powertrain` ∈ {`"diesel"`,`"bev"`}; `fac` = Rückgabe von `load_factors()`.
- Produces: `non_exhaust_pm10(km, v_kmh, powertrain, sup) -> (tyre_g, brake_g, road_g)`.

- [ ] **Step 1: Failing Tests anhängen**

```python
# an tests/test_emissions_emep.py anhängen
def test_vehicle_emissions_diesel_matches_manual_calc():
    import emissions_emep as em
    fac = em.load_factors()
    out = em.vehicle_emissions(100.0, 80.0, "diesel", fac)
    ec = 100.0 * em.ef(80.0, fac["diesel"]["EC"])          # MJ
    sup = fac["sup"]
    assert out["ENERGY_MJ"] == pytest.approx(ec)
    assert out["CO2"] == pytest.approx(ec * sup["ttw_co2_g_per_mj_diesel"])
    assert out["NOx"] == pytest.approx(100.0 * em.ef(80.0, fac["diesel"]["NOx"]))
    assert out["N2O"] == pytest.approx(100.0 * sup["n2o_g_per_km_diesel_lcv"])
    assert out["CO2E_TTW"] == pytest.approx(
        out["CO2"] + sup["gwp_ch4"] * out["CH4"] + sup["gwp_n2o"] * out["N2O"])
    assert out["CO2E_WTW"] == pytest.approx(
        out["CO2E_TTW"] + ec * sup["wtt_co2e_g_per_mj_diesel"])
    assert out["PM10_NONEXHAUST"] == pytest.approx(
        out["PM10_TYRE"] + out["PM10_BRAKE"] + out["PM10_ROAD"])

def test_vehicle_emissions_bev_zero_exhaust_grid_wtw():
    import emissions_emep as em
    fac = em.load_factors()
    out = em.vehicle_emissions(100.0, 80.0, "bev", fac)
    for k in ("CO", "NOx", "VOC", "PM_EXHAUST", "CH4", "SPN23", "N2O",
              "CO2", "CO2E_TTW"):
        assert out[k] == 0.0, k
    ec = 100.0 * em.ef(80.0, fac["bev"]["EC"])
    assert out["ENERGY_MJ"] == pytest.approx(ec)
    assert out["CO2E_WTW"] == pytest.approx(ec * fac["sup"]["grid_co2e_g_per_mj"])
    # BEV: mehr Reifen-, weniger Bremsabrieb als Diesel
    d = em.vehicle_emissions(100.0, 80.0, "diesel", fac)
    assert out["PM10_TYRE"] > d["PM10_TYRE"]
    assert out["PM10_BRAKE"] < d["PM10_BRAKE"]

def test_non_exhaust_speed_correction_piecewise():
    import emissions_emep as em
    sup = em.load_factors()["sup"]
    # Tyre-Korrektur: konstant unter 40, fallend 40..90, konstant ueber 90
    t30, _, _ = em.non_exhaust_pm10(1.0, 30.0, "diesel", sup)
    t39, _, _ = em.non_exhaust_pm10(1.0, 39.9, "diesel", sup)
    t60, _, _ = em.non_exhaust_pm10(1.0, 60.0, "diesel", sup)
    t95, _, _ = em.non_exhaust_pm10(1.0, 95.0, "diesel", sup)
    t120, _, _ = em.non_exhaust_pm10(1.0, 120.0, "diesel", sup)
    assert t30 == pytest.approx(t39, rel=1e-3)
    assert t39 > t60 > t95
    assert t95 == pytest.approx(t120)
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: FAIL — `vehicle_emissions` nicht definiert

- [ ] **Step 3: Implementieren** (an `emissions_emep.py` anhängen):

```python
EXHAUST_KEYS = ("CO", "NOx", "VOC", "PM_EXHAUST", "CH4", "SPN23", "N2O",
                "CO2", "CO2E_TTW")
# xlsx pollutant name -> output key
_POLL_KEY = {"CO": "CO", "NOx": "NOx", "VOC": "VOC",
             "PM Exhaust": "PM_EXHAUST", "CH4": "CH4", "SPN23": "SPN23"}


def _tyre_speed_corr(v):
    """EMEP/EEA GB 1.A.3.b.vi Tier-2 tyre wear speed correction."""
    if v < 40.0:
        return 1.39
    if v <= 90.0:
        return -0.00974 * v + 1.78
    return 0.902


def _brake_speed_corr(v):
    """EMEP/EEA GB 1.A.3.b.vi Tier-2 brake wear speed correction."""
    if v < 40.0:
        return 1.67
    if v <= 95.0:
        return -0.027 * v + 2.75
    return 0.185


def non_exhaust_pm10(km, v_kmh, powertrain, sup):
    """PM10 [g] from tyre / brake / road-surface wear over km at mean speed
    v. BEV multipliers (mass penalty on tyre, regeneration on brake) are
    declared assumptions in emep_supplement.csv, not guidebook values."""
    tyre_mult = sup["bev_tyre_mult"] if powertrain == "bev" else 1.0
    brake_mult = sup["bev_brake_mult"] if powertrain == "bev" else 1.0
    tyre = km * sup["tsp_tyre_g_per_km_lcv"] * sup["pm10_frac_tyre"] \
        * _tyre_speed_corr(v_kmh) * tyre_mult
    brake = km * sup["tsp_brake_g_per_km_lcv"] * sup["pm10_frac_brake"] \
        * _brake_speed_corr(v_kmh) * brake_mult
    road = km * sup["tsp_road_g_per_km_lcv"] * sup["pm10_frac_road"]
    return tyre, brake, road


def vehicle_emissions(km, v_kmh, powertrain, fac):
    """Full pollutant vector [g; ENERGY_MJ in MJ; SPN23 in #] for `km`
    driven at mean travelling speed `v_kmh`. Idle and cold-start are NOT
    modelled (documented limitation: engine-off at service stops assumed;
    cold-start bounded in the plan's docs task)."""
    sup = fac["sup"]
    out = {}
    if powertrain == "diesel":
        for poll, key in _POLL_KEY.items():
            out[key] = km * ef(v_kmh, fac["diesel"][poll])
        ec = km * ef(v_kmh, fac["diesel"]["EC"])
        out["ENERGY_MJ"] = ec
        out["CO2"] = ec * sup["ttw_co2_g_per_mj_diesel"]
        out["N2O"] = km * sup["n2o_g_per_km_diesel_lcv"]
        out["CO2E_TTW"] = (out["CO2"] + sup["gwp_ch4"] * out["CH4"]
                           + sup["gwp_n2o"] * out["N2O"])
        out["CO2E_WTW"] = out["CO2E_TTW"] + ec * sup["wtt_co2e_g_per_mj_diesel"]
    elif powertrain == "bev":
        out = {k: 0.0 for k in EXHAUST_KEYS}
        ec = km * ef(v_kmh, fac["bev"]["EC"])
        out["ENERGY_MJ"] = ec
        out["CO2E_WTW"] = ec * sup["grid_co2e_g_per_mj"]
    else:
        raise ValueError("unknown powertrain: " + str(powertrain))
    tyre, brake, road = non_exhaust_pm10(km, v_kmh, powertrain, sup)
    out["PM10_TYRE"], out["PM10_BRAKE"], out["PM10_ROAD"] = tyre, brake, road
    out["PM10_NONEXHAUST"] = tyre + brake + road
    return out
```

- [ ] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add analysis/kpi/emissions_emep.py analysis/kpi/tests/test_emissions_emep.py
git commit -m "feat(emissions): vehicle_emissions core (diesel+BEV arms, WTW, non-exhaust PM10)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Extractor — Freight-Arm (Touren aus TimeDistance_perVehicle.tsv)

**Files:**
- Create: `analysis/kpi/extract_emissions.py`
- Test: `analysis/kpi/tests/test_extract_emissions.py`

**Interfaces:**
- Consumes: `emissions_emep.load_factors()`, `vehicle_emissions()` (Task 4); `common.row`; TSV `<run>/analysis/freight/TimeDistance_perVehicle.tsv` mit Spalten `vehicleId, carrierId, vehicleTypeId, tourId, travelDistance[km], travelTime[s]` (real vorhanden, verifiziert an married120).
- Produces: `extract_emissions.freight_arm(run_dir, fac) -> (totals, detail)` — `totals[powertrain][key] = float` (Summen über alle Touren, Keys wie `vehicle_emissions`), `detail` = Liste von dicts `{fleet:"freight", entity: vehicleId, vehicle_type, km, v_kmh, powertrain, <alle Emissions-Keys>}`. Später von Task 7 (`extract`) konsumiert.

- [ ] **Step 1: Failing Test schreiben** (Fixture-TSV wird per tmp_path erzeugt):

```python
# tests/test_extract_emissions.py
import pandas as pd
import pytest

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
    # Tour 1: 120 km bei 120/4=30 km/h; Tour 2: 60 km bei 60/2=30 km/h
    exp = em.vehicle_emissions(120.0, 30.0, "diesel", fac)
    exp2 = em.vehicle_emissions(60.0, 30.0, "diesel", fac)
    assert totals["diesel"]["CO2E_WTW"] == pytest.approx(
        exp["CO2E_WTW"] + exp2["CO2E_WTW"])
    assert totals["bev"]["CO2E_WTW"] > 0
    assert len(detail) == 4                      # 2 Touren x 2 Antriebe
    d0 = [d for d in detail if d["powertrain"] == "diesel"][0]
    assert d0["fleet"] == "freight" and d0["v_kmh"] == pytest.approx(30.0)
    assert d0["vehicle_type"] == "ct_cep_size_m"

def test_freight_arm_missing_tsv_returns_none(tmp_path):
    import emissions_emep as em
    import extract_emissions as ee
    assert ee.freight_arm(tmp_path, em.load_factors()) is None
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: FAIL — `ModuleNotFoundError: extract_emissions`

- [ ] **Step 3: Implementieren**

```python
# -*- coding: utf-8 -*-
# analysis/kpi/extract_emissions.py
"""Emission KPI rows (kpi_group="environment") for the Lausitz runs.

Tier-3 method: per freight TOUR / per DRT VEHICLE, evaluate the EMEP/EEA
speed curves at the entity's mean travelling speed (distance / driving
time, service dwell excluded -- engine-off assumption at stops), multiply
by its km. Both powertrain arms (diesel primary + BEV variant) are always
computed from the same runs -- electrification is a factor swap, no re-run.

Fleet mapping (user 2026-07-28): every vehicle of both fleets = N1-III
Euro 7 (see data/README.md); vehicle types outside VEHICLE_CLASS_MAP are
skipped with an ASCII warning so a future new type fails loudly, not
silently wrong.
"""
import csv
from pathlib import Path

import pandas as pd

import emissions_emep as em
from common import row

# vehicleTypeId (freight TSV) -> factor class; DRT vehicles are matched by
# id prefix "drt_" instead (single homogeneous minibus fleet).
VEHICLE_CLASS_MAP = {"ct_cep_size_s": "N1-III", "ct_cep_size_m": "N1-III",
                     "ct_cep_size_l": "N1-III"}
POWERTRAINS = ("diesel", "bev")

# output keys of emissions_emep.vehicle_emissions
EMIS_KEYS = ("CO", "NOx", "VOC", "PM_EXHAUST", "CH4", "SPN23", "N2O", "CO2",
             "CO2E_TTW", "CO2E_WTW", "ENERGY_MJ", "PM10_TYRE", "PM10_BRAKE",
             "PM10_ROAD", "PM10_NONEXHAUST")


def _zero_totals():
    return {pt: {k: 0.0 for k in EMIS_KEYS} for pt in POWERTRAINS}


def _add_entity(totals, detail, fleet, entity, vtype, km, v_kmh, fac):
    for pt in POWERTRAINS:
        out = em.vehicle_emissions(km, v_kmh, pt, fac)
        for k in EMIS_KEYS:
            totals[pt][k] += out[k]
        d = {"fleet": fleet, "entity": entity, "vehicle_type": vtype,
             "km": km, "v_kmh": v_kmh, "powertrain": pt}
        d.update({k: out[k] for k in EMIS_KEYS})
        detail.append(d)


def freight_arm(run_dir, fac):
    """Per-tour emissions from the CarriersAnalysis TSV. Returns
    (totals, detail) or None if the TSV is absent (pax-only run)."""
    tsv = Path(run_dir) / "analysis" / "freight" / "TimeDistance_perVehicle.tsv"
    if not tsv.exists():
        return None
    td = pd.read_csv(tsv, sep="\t")
    totals, detail = _zero_totals(), []
    for _, r in td.iterrows():
        vtype = str(r["vehicleTypeId"])
        if vtype not in VEHICLE_CLASS_MAP:
            print("[emissions] unmapped freight vehicle type skipped: "
                  + vtype)  # ASCII only
            continue
        km = float(r["travelDistance[km]"])
        tt_h = float(r["travelTime[s]"]) / 3600.0
        if km <= 0 or tt_h <= 0:
            continue
        _add_entity(totals, detail, "freight", str(r["vehicleId"]), vtype,
                    km, km / tt_h, fac)
    return totals, detail
```

- [ ] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add analysis/kpi/extract_emissions.py analysis/kpi/tests/test_extract_emissions.py
git commit -m "feat(emissions): freight arm - per-tour Tier-3 emissions from TimeDistance_perVehicle

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Extractor — DRT-Arm (echte Link-Längen + DRIVE-Zeiten)

**Files:**
- Modify: `analysis/kpi/extract_emissions.py`
- Test: `analysis/kpi/tests/test_extract_emissions.py` (erweitern)

**Interfaces:**
- Consumes: `veh_path` von `geometry.reconstruct_drt_paths(drt_cache)` (dict `vehicle_id -> [(link_id, occ), ...]`); `recon["per_veh"][veh]["drive_s"]` von `drt_service_time.reconstruct()`; MATSim-Netzwerk `*.output_network.xml.gz` (Link-Attribut `length` in Metern — NICHT die Euklid-Näherung aus `geometry.load_link_geometry`, die ist ~3 % zu kurz).
- Produces: `load_link_lengths(network_gz, used_links) -> dict[str, float]` (Meter); `drt_arm(veh_path, recon, link_len, fac) -> (totals, detail)` — Struktur wie `freight_arm`, `fleet="drt"`, `entity=vehicle_id`, `vehicle_type="drt_minibus"`.

- [ ] **Step 1: Failing Tests anhängen**

```python
# an tests/test_extract_emissions.py anhängen
import gzip

NETWORK_XML = """<?xml version="1.0" encoding="utf-8"?>
<network>
<nodes>
<node id="n1" x="0" y="0"/><node id="n2" x="1000" y="0"/>
</nodes>
<links>
<link id="l1" from="n1" to="n2" length="1200.0" freespeed="13.9" capacity="600" permlanes="1"/>
<link id="l2" from="n2" to="n1" length="800.0" freespeed="13.9" capacity="600" permlanes="1"/>
</links>
</network>"""

def _network(tmp_path):
    p = tmp_path / "test.output_network.xml.gz"
    with gzip.open(p, "wt", encoding="utf-8") as f:
        f.write(NETWORK_XML)
    return p

def test_load_link_lengths_reads_length_attribute(tmp_path):
    import extract_emissions as ee
    ll = ee.load_link_lengths(_network(tmp_path), {"l1", "l2"})
    assert ll == {"l1": 1200.0, "l2": 800.0}   # Attribut, nicht Euklid (1000/1000)

def test_drt_arm_uses_true_lengths_and_drive_time(tmp_path):
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    veh_path = {"drt_v1": [("l1", 0), ("l2", 1), ("l1", 0)]}   # 3.2 km
    recon = {"per_veh": {"drt_v1": {"drive_s": 320.0}}}        # 36 km/h
    ll = ee.load_link_lengths(_network(tmp_path), {"l1", "l2"})
    totals, detail = ee.drt_arm(veh_path, recon, ll, fac)
    exp = em.vehicle_emissions(3.2, 36.0, "diesel", fac)
    assert totals["diesel"]["NOx"] == pytest.approx(exp["NOx"])
    d = [x for x in detail if x["powertrain"] == "diesel"][0]
    assert d["fleet"] == "drt" and d["km"] == pytest.approx(3.2)
    assert d["v_kmh"] == pytest.approx(36.0)

def test_drt_arm_skips_vehicle_without_drive_time():
    import emissions_emep as em
    import extract_emissions as ee
    totals, detail = ee.drt_arm({"drt_v1": [("l1", 0)]},
                                {"per_veh": {}}, {"l1": 500.0},
                                em.load_factors())
    assert detail == [] and totals["diesel"]["CO2E_WTW"] == 0.0
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: FAIL — `load_link_lengths` nicht definiert

- [ ] **Step 3: Implementieren** (an `extract_emissions.py` anhängen; Imports `gzip` + `xml.etree.ElementTree as ET` oben ergänzen):

```python
def load_link_lengths(network_gz, used_links):
    """Stream the gzipped MATSim network and return {link_id: length_m}
    restricted to used_links, from the `length` ATTRIBUTE (true routed
    length; geometry.load_link_geometry's node-euclid is ~3 % short and
    must not be used for emissions)."""
    out = {}
    with gzip.open(network_gz, "rt", encoding="utf-8") as f:
        for _, el in ET.iterparse(f, events=("end",)):
            if el.tag == "link":
                lid = el.get("id")
                if lid in used_links:
                    out[lid] = float(el.get("length"))
                el.clear()
    return out


def drt_arm(veh_path, recon, link_len, fac):
    """Per-vehicle emissions for the DRT fleet: km = sum of true link
    lengths along the reconstructed path, mean speed = km / DRIVE task
    time from drt_service_time.reconstruct(). Vehicles without DRIVE time
    are skipped (never moved -> nothing to emit)."""
    per_veh = recon.get("per_veh", {}) if recon else {}
    totals, detail = _zero_totals(), []
    for veh, path in veh_path.items():
        drive_s = per_veh.get(veh, {}).get("drive_s", 0.0)
        km = sum(link_len.get(lid, 0.0) for lid, _occ in path) / 1000.0
        if drive_s <= 0 or km <= 0:
            continue
        _add_entity(totals, detail, "drt", veh, "drt_minibus",
                    km, km / (drive_s / 3600.0), fac)
    return totals, detail
```

- [ ] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add analysis/kpi/extract_emissions.py analysis/kpi/tests/test_extract_emissions.py
git commit -m "feat(emissions): DRT arm - true link lengths + DRIVE-time mean speeds

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: KPI-Rows, EV-Reichweiten-Gate und Detail-CSV (`extract()` + `write_detail()`)

**Files:**
- Modify: `analysis/kpi/extract_emissions.py`
- Test: `analysis/kpi/tests/test_extract_emissions.py` (erweitern)

**Interfaces:**
- Consumes: `freight_arm`/`drt_arm` (Tasks 5/6); `common.row`.
- Produces (von Task 8 konsumiert): `extract(run_dir, prefix, recon=None, veh_path=None, network_gz=None) -> (rows, detail)` — `rows` = Liste von `common.row(...)`-Dicts (Gruppe `environment`), `detail` = Detail-Liste beider Arme. `write_detail(detail, meta, path)` schreibt `kpi_emissions_vehicles.csv` (Semikolon-getrennt wie `kpi_writer`, mit `run_id`-Spalte aus `meta.run_id`).
- KPI-Namensschema: `{fleet}_{metric}{arm}` mit fleet ∈ {`drt`,`freight`,`total`}, arm ∈ {``,`_bev`}: `*_co2e_wtw` [kg], `*_co2e_ttw` [kg], `*_co2` [kg, nur diesel], `*_nox` [g], `*_pm_exhaust` [g], `*_pm10_nonexhaust` [g], `*_energy_final` [MJ]. EV-Gate: `ev_range_max_km_drt`, `ev_range_p95_km_drt`, `ev_range_max_km_freight_tour`, `ev_range_p95_km_freight_tour` [km] und `ev_range_exceed_drt`, `ev_range_exceed_freight` [share, Anteil Entities über `ev_range_km`].

- [ ] **Step 1: Failing Tests anhängen**

```python
# an tests/test_extract_emissions.py anhängen
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
    assert "drt_co2e_wtw" not in by                     # kein DRT-Input uebergeben
    # EV-Gate: Touren sind 120/60 km, Default-Range 250 -> exceed share 0
    assert by["ev_range_max_km_freight_tour"]["value"] == pytest.approx(120.0)
    assert by["ev_range_exceed_freight"]["value"] == 0.0
    assert "EMEP/EEA" in by["freight_co2e_wtw"]["source"]

def test_extract_flags_range_exceedance(tmp_path, monkeypatch):
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    fac["sup"]["ev_range_km"] = 100.0                   # Tour 1 (120 km) reisst
    monkeypatch.setattr(em, "load_factors", lambda data_dir=None: fac)
    rows, _ = ee.extract(_run_dir(tmp_path), "test")
    by = _rows_by_name(rows)
    assert by["ev_range_exceed_freight"]["value"] == pytest.approx(0.5)

def test_write_detail(tmp_path):
    import extract_emissions as ee

    class Meta:
        run_id = "RUN1"
    rows, detail = ee.extract(_run_dir(tmp_path), "test")
    out = tmp_path / "kpi_emissions_vehicles.csv"
    ee.write_detail(detail, Meta(), out)
    txt = out.read_text(encoding="utf-8")
    assert txt.splitlines()[0].startswith("run_id;fleet;entity;vehicle_type;km;v_kmh;powertrain")
    assert "RUN1" in txt and "freight_dhl_veh_a_1" in txt
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: FAIL — `extract` nicht definiert

- [ ] **Step 3: Implementieren** (an `extract_emissions.py` anhängen):

```python
SRC = "EMEP/EEA GB 2025 App.4 Tier-3 (Okt 2025, COPERT 5.9.1), N1-III Euro 7"

# (kpi_metric, emis_key, unit, to_unit_factor)
_KPI_METRICS = [("co2e_wtw", "CO2E_WTW", "kg", 1e-3),
                ("co2e_ttw", "CO2E_TTW", "kg", 1e-3),
                ("co2", "CO2", "kg", 1e-3),
                ("nox", "NOx", "g", 1.0),
                ("pm_exhaust", "PM_EXHAUST", "g", 1.0),
                ("pm10_nonexhaust", "PM10_NONEXHAUST", "g", 1.0),
                ("energy_final", "ENERGY_MJ", "MJ", 1.0)]
_BEV_SKIP = {"co2e_ttw", "co2"}          # im BEV-Arm konstruktionsbedingt 0


def _percentile(sorted_vals, q):
    if not sorted_vals:
        return 0.0
    i = min(len(sorted_vals) - 1, int(round(q * (len(sorted_vals) - 1))))
    return sorted_vals[i]


def _range_rows(detail, sup):
    """EV range gate (user 2026-07-28): per DRT vehicle-day / per freight
    tour km against the conservative real-world range. If exceed share is
    > 0, the pure-postprocessing BEV arm is NOT defensible for that fleet
    -> escalation path in BACKLOG (idle-window charging analysis first)."""
    rows = []
    for fleet, label in (("drt", "drt"), ("freight", "freight_tour")):
        kms = sorted(d["km"] for d in detail
                     if d["fleet"] == fleet and d["powertrain"] == "diesel")
        if not kms:
            continue
        exceed = sum(1 for k in kms if k > sup["ev_range_km"]) / len(kms)
        src = ("per-entity km vs ev_range_km=" + str(sup["ev_range_km"])
               + " (emep_supplement.csv)")
        rows += [row("environment", "ev_range_max_km_" + label,
                     max(kms), "km", src),
                 row("environment", "ev_range_p95_km_" + label,
                     _percentile(kms, 0.95), "km", src),
                 row("environment", "ev_range_exceed_" + fleet,
                     exceed, "share", src)]
    return rows


def extract(run_dir, prefix, recon=None, veh_path=None, network_gz=None):
    """Emission KPI rows + per-entity detail for one run. Freight arm from
    the CarriersAnalysis TSV; DRT arm only when veh_path/recon/network are
    supplied by build_kpis (they are reused, never recomputed here)."""
    fac = em.load_factors()
    arms = {}
    fr = freight_arm(run_dir, fac)
    if fr is not None:
        arms["freight"] = fr
    if veh_path and recon is not None and network_gz is not None:
        used = {lid for path in veh_path.values() for lid, _ in path}
        link_len = load_link_lengths(network_gz, used)
        arms["drt"] = drt_arm(veh_path, recon, link_len, fac)

    rows, detail = [], []
    grand = _zero_totals()
    for fleet, (totals, det) in arms.items():
        detail += det
        for pt in POWERTRAINS:
            for k in EMIS_KEYS:
                grand[pt][k] += totals[pt][k]
            sfx = "_bev" if pt == "bev" else ""
            for metric, key, unit, f in _KPI_METRICS:
                if pt == "bev" and metric in _BEV_SKIP:
                    continue
                rows.append(row("environment", fleet + "_" + metric + sfx,
                                totals[pt][key] * f, unit, SRC))
    # total_* immer emittieren (= Summe der ABGEDECKTEN Flotten; bei
    # Freight-only-Runs also == freight_*) -- Vergleichbarkeit ueber Runs.
    if arms:
        for pt in POWERTRAINS:
            sfx = "_bev" if pt == "bev" else ""
            for metric, key, unit, f in _KPI_METRICS:
                if pt == "bev" and metric in _BEV_SKIP:
                    continue
                rows.append(row("environment", "total_" + metric + sfx,
                                grand[pt][key] * f, unit, SRC))
    rows += _range_rows(detail, fac["sup"])
    return rows, detail


def write_detail(detail, meta, path):
    """kpi_emissions_vehicles.csv -- one row per entity x powertrain,
    ';'-separated like the other kpi_* CSVs."""
    header = ["run_id", "fleet", "entity", "vehicle_type", "km", "v_kmh",
              "powertrain"] + list(EMIS_KEYS)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(header)
        for d in detail:
            w.writerow([meta.run_id] + [d[k] for k in header[1:]])
```

- [ ] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add analysis/kpi/extract_emissions.py analysis/kpi/tests/test_extract_emissions.py
git commit -m "feat(emissions): environment KPI rows, EV range gate, per-vehicle detail CSV

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Integration in build_kpis + KPI-Gruppe + Tabellen-Rendering

**Files:**
- Modify: `analysis/kpi/common.py` (Zeile 9: `KPI_GROUPS`)
- Modify: `analysis/kpi/build_kpis.py` (Geometrie-Block vorziehen + Aufruf)
- Modify: `analysis/kpi/render.py` (Zeile ~305: Gruppenliste der KPI-Tabelle)
- Test: bestehende Suite + Realdaten-Smoke

- [ ] **Step 1: `common.py` erweitern**

```python
# alt:
KPI_GROUPS = ("system", "passenger", "freight", "economic", "channel", "meta")
# neu:
KPI_GROUPS = ("system", "passenger", "freight", "economic", "channel",
              "environment", "meta")
```

- [ ] **Step 2: `render.py` Gruppenliste ergänzen** — in `render_kpi_table` (Z. ~305):

```python
# alt:
    for grp in ["passenger", "system", "freight", "economic", "channel"]:
# neu:
    for grp in ["passenger", "system", "freight", "economic", "channel",
                "environment"]:
```

- [ ] **Step 3: `build_kpis.py` umbauen.** Der Geometrie-Block (`veh_path`/`link_geo`/`veh_km`/`occ_km_shares`, aktuell NACH `kpi_writer.write_long`) wird VOR die `rows`-Finalisierung gezogen, damit die Emissions-Rows in `kpis_long/wide.csv` landen. Konkret: den kompletten Block ab `import geometry` bis `elif drt_cache is not None: print(...)` unverändert nach oben verschieben — direkt VOR `pax_only.apply_overrides(rows)`. Danach (immer noch vor `pax_only.apply_overrides`) einfügen:

```python
    # Emissions (EMEP/EEA Tier-3, group "environment") -- reuses recon +
    # veh_path from above; freight arm reads its own TSV. Post-processing
    # only, both powertrain arms. Graceful: any failure skips emissions
    # rather than killing the KPI build.
    import extract_emissions
    emis_detail = None
    try:
        emis_rows, emis_detail = extract_emissions.extract(
            run_dir, meta.prefix, recon=recon, veh_path=veh_path,
            network_gz=network if network.exists() else None)
        rows += emis_rows
    except Exception as e:
        print("[build] emissions skipped: " + str(e))  # ASCII only
```

und nach dem `kpi_writer.write_wide(...)`-Aufruf:

```python
    if emis_detail:
        extract_emissions.write_detail(emis_detail, meta,
                                       out / "kpi_emissions_vehicles.csv")
```

Achtung Reihenfolge-Detail: `network = run_dir / (meta.prefix + ".output_network.xml.gz")` zieht mit dem Geometrie-Block nach oben; die spätere Verwendung durch `maps` (Task-6-Kommentar im Code) bleibt funktionsfähig, weil `veh_path`/`link_geo` weiterhin vor dem maps-Abschnitt existieren. `distributions.extract(...)` (konsumiert `veh_km`/`occ_km_shares`) bleibt an seiner Stelle — die Variablen existieren nach dem Vorziehen früher, das ist unschädlich.

- [ ] **Step 4: Volle Test-Suite laufen lassen**

Run: `python -u -m pytest tests/ -v` (aus `analysis/kpi/`)
Expected: PASS — insbesondere `test_build_kpis.py`, `test_render.py` und `test_real_married250.py` bleiben grün (der Geometrie-Block ist nur verschoben, nicht verändert; `environment` ist eine additive Gruppe).

- [ ] **Step 5: Realdaten-Smoke gegen einen vorhandenen Run**

Run (aus `analysis/kpi/`, Run-Dir ggf. anpassen — ein `bandz_*`- oder married-Run mit `analysis/freight/`):
`python -u build_kpis.py --run-dir ../../hagrid-matsim-output/DRT_BASELINE_13052025_married120_iter150_jsprit100`
Danach: `grep "environment" <run-dir>/analysis/kpis_long.csv | head -30`
Expected: `freight_*`- (und bei DRT-Events auch `drt_*`-/`total_*`-) Zeilen mit plausiblen Größenordnungen — Sanity: `freight_co2e_wtw` grob 0,2–0,4 kg/km × `freight_vehicle_km`; `ev_range_*`-Zeilen vorhanden. `kpi_emissions_vehicles.csv` existiert und hat 2×(Touren+DRT-Fahrzeuge) Zeilen.

- [ ] **Step 6: Commit**

```bash
git add analysis/kpi/common.py analysis/kpi/build_kpis.py analysis/kpi/render.py
git commit -m "feat(emissions): wire environment KPI group into build_kpis + table rendering

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: Kaltstart-Bound, Limitations-Doku, Backlog

**Files:**
- Modify: `analysis/kpi/data/README.md`
- Modify: `docs/BACKLOG.md`

- [ ] **Step 1: Kaltstart quantitativ bounden.** Aus dem Appendix-4-Sheet `COLD_EMISSIONS_PARAMETERS` (bzw. Guidebook-Kapitel Tier-2-Kaltstart) für Diesel-LCV die Kaltstart-Mehremission je Start abschätzen (Ansatz: `beta`-Anteil kalter Distanz ~ erste 5–10 km bei ~10 °C, je 1 Start pro Tour/Fahrzeug-Tag) und gegen die Task-8-Smoke-Zahlen je Schadstoff ins Verhältnis setzen. **Entscheidungsregel:** Anteil < 5 % für alle berichteten Schadstoffe → dokumentierte Limitation, fertig; Anteil ≥ 5 % (realistisch am ehesten NOx) → neuen Backlog-Punkt „Kaltstart-Zuschlag implementieren" unter dem Nachhaltigkeits-`[H]` anlegen (Tier-2-Zuschlag pro Tour, ~0,5 d).

- [ ] **Step 2: Limitations-Abschnitt in `data/README.md` anhängen** (konkrete Zahlen aus Step 1 einsetzen):

```markdown
## Limitations (Paper-Rohtext)
- Tier-3-Kurven auf Trip-/Tour-Mittelgeschwindigkeit angewandt (COPERT-
  Intention), nicht auf Link-Ebene; Stop&Go-Differenzierung unterhalb der
  Kurvenaufloesung entfaellt (laendlicher Raum: unkritisch fuer Deltas).
- Kaltstart nicht modelliert: Bound = <X> % (NOx), <Y> % (CO2) je Tour
  (COLD_EMISSIONS_PARAMETERS, 1 Start/Tour, 10 C) -- siehe Step-1-Rechnung.
- Idle an Servicestopps: Engine-off-Annahme (Auslieferung/Boarding).
- Euro-7-Faktoren aus Grenzwerten projiziert (Norm ab ~2026/27).
- km-Kanal traegt jsprit-Heuristik-Rauschen (~6.5 % Boden, Seed-Messung
  2026-07-28) -> Paper-Zahlen als Mittel + Min/Max ueber >=10 Runs.
- BEV-Arm: Elektrifizierung nur auf Emissionsebene (keine Reichweiten-/
  Ladezeitrestriktion in der Sim); Gate = ev_range_*-KPIs je Run.
- Netzintensitaet Strom + BEV-Abrieb-Multiplikatoren sind ausgewiesene
  Sensitivitaetsparameter (emep_supplement.csv).
```

- [ ] **Step 3: Backlog aktualisieren** — im `[H]` Nachhaltigkeits-Block: Verweis auf diesen Plan ergänzen (`→ [Plan](superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md)`), die „Restliche Arbeitsschritte" als in-Plan-überführt markieren, Kaltstart-Ergebnis aus Step 1 eintragen. Offen bleiben dort: SOS-/PB-Layer (eigenes späteres Paket), Multi-Seed-Aggregation über ≥10 Runs (Reporting-Werkzeug, erst bei Paper-Auswertung), optionale Sensitivitäten (Midi-Bus für DRT, Rigid ≤7,5 t für `_l`).

- [ ] **Step 4: Commit**

```bash
git add analysis/kpi/data/README.md docs/BACKLOG.md
git commit -m "docs(emissions): cold-start bound, limitations, backlog cross-ref

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Bewusst NICHT in diesem Plan (Abgrenzung)

- **SOS-/Planetary-Boundaries-Layer** — eigenes Paket nach User-Detailklärung (Allokations-/Downscaling-Entscheidung; Backlog `[H]`).
- **Multi-Seed-Aggregation (Mittel/Min/Max über ≥10 Runs)** — Reporting-Schritt zur Paper-Auswertung; die per-Run-KPIs aus diesem Plan sind die Inputs dafür.
- **Kaltstart-Implementierung** — nur falls Task-9-Bound ≥ 5 % (dann eigener Backlog-Punkt).
- **Sensitivitäts-Faktorsätze** (Urban Bus Midi ≤15 t für DRT; HDT Rigid ≤7,5 t für `ct_cep_size_l`) — datenseitig vorbereitet (Tool-Filter erweitern), aber nicht verdrahtet.
- **Dashboard-Karten/-Kacheln für Emissionen** — die KPI-Tabellen-Ansicht (Task 8) reicht fürs Paper; hübsche Environment-Kacheln sind Dashboard-v2-Folgearbeit.
- **`hagrid_output_analysis/`** — bleibt komplett unangetastet (Kollegen-Freeze).
