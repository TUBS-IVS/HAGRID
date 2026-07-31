# EMEP/EEA Tier-3 Emissions (Lausitz KPI-Stack) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Revision 2026-07-31 (Rev. B).** Substanzielle Überarbeitung nach Datenprüfung. Geändert gegenüber Rev. A:
> 1. **Segmentdifferenzierung statt Einheitsklasse.** Nicht mehr „alle Fahrzeuge N1-III", sondern `ct_cep_size_s` → N1-II, `_m`/`_l` → N1-III. Grund: die Van-Flotte ist real gemischt und der Mix ist jsprit-Ergebnis, keine Konstante (base10c: 93 % der km auf `size_s`; `localdepots_stagger`: 100 % `size_m`). Eine Einheitsklasse N1-III überschätzt die Baseline-Freight-Energie um ~39 %.
> 2. **Keine Zuladungsskalierung.** Geprüft und verworfen — methodenkonform, siehe Global Constraints. Ersetzt die in der Diskussion erwogene STREAM-Multiplikator-Variante (Quellenmischung, kein definierter Nullpunkt).
> 3. **Zitat korrigiert:** „EMEP/EEA Guidebook **2023 – Update 2025**", nicht „2025".
> 4. **EV-Gate als Schwellen-Sweep** (150/200/250 km) statt Einzel-Pass/Fail — bei 250 km ist die Überschreitung in allen 12 geprüften Läufen 0 %, das Gate wäre wirkungslos.
> 5. **Dritter Datenpfad (Task 5b)** für den modularen Arm: 1d/1c haben kein `analysis/freight/`, die Fracht fährt in DRT-Fahrzeugen.

**Goal:** Distanz- und geschwindigkeitsbasierte Tank-to-Wheel/Well-to-Wheel-Emissions-KPIs (CO₂e-Kern + NOx/PM/CO/VOC/CH4/SPN23 + Energie) für DRT- und LMD-Flotte aller Lausitz-Szenarien, aus Tier-3-Faktoren des EMEP/EEA-Guidebook 2023 (Update 2025), als reines Post-Processing im bestehenden `analysis/kpi/`-Stack — segmentdifferenziert (N1-II/N1-III), inklusive BEV-Faktorsatz-Arm und EV-Reichweiten-Sweep.

**Architecture:** Ein einmaliges Extraktions-Tool zieht die COPERT-5.9.1-Koeffizienten aus dem Appendix-4-xlsx in eine committete, zitierfähige CSV — **alle drei N1-Segmente**, damit die Klassenzuordnung eine Datenzeile und keine Code-Änderung ist. Ein neues Modul `emissions_emep.py` evaluiert die Tier-3-Kurven `EF(v) = (αv²+βv+γ+δ/v)/(εv²+ζv+η)·(1−RF)` und rechnet daraus pro Fahrzeug/Tour den vollen Schadstoffvektor (Diesel- UND BEV-Arm in einem Durchlauf). `extract_emissions.py` speist das als neue KPI-Gruppe `environment` in die bestehende `build_kpis.py`-Pipeline ein — über **drei** Datenpfade: konventioneller Freight aus `TimeDistance_perVehicle.tsv` (Distanz+Fahrzeit je Tour), modularer Freight aus `MODULAR_FREIGHT_DRIVE`-Taskfenstern ∩ rekonstruiertem Link-Pfad, DRT-Pax aus Event-Pfadrekonstruktion (echte Link-Längen) + `reconstruct()`-DRIVE-Zeiten. Kein MATSim-Re-Run, keine Java-Änderung.

**Tech Stack:** Python 3 (pandas, openpyxl), pytest; bestehende KPI-Pipeline `parcel-demand-2-matsim-pipeline/analysis/kpi/`.

## Global Constraints

- **`src/hagrid_output_analysis/**` (insb. `emissions.py`, `config.py`) wird NICHT angefasst** — Kollegen-Paper-Freeze bis mind. 2026-08-11 (User 2026-07-28). Alles Neue lebt in `analysis/kpi/`.
- Windows/cp1252: **ASCII-only in allen `print()`**; Python immer `python -u`.
- KPI-Konventionen: Zeilen via `common.row(kpi_group, kpi_name, value, unit, source)`; Extractor bleibt run-agnostisch (kein run_id in den Rows); neue Gruppe heißt exakt `"environment"`.
- Faktor-Provenance in jeder Quellenangabe: „EMEP/EEA air pollutant emission inventory guidebook **2023 – Update 2025**, ch. 1.A.3.b.i-iv App. 4 (Okt 2025, COPERT 5.9.1)". **Nicht** „Guidebook 2025" — die Kopfzeile jeder Kapitelseite lautet „guidebook 2023 – Update 2025". Diese Zeichenkette landet in der `source`-Spalte jeder Faktorzeile und damit in jeder Paper-Tabelle.
- **Gotcha:** Spalte `Reduction Factor [%]` im xlsx enthält **Bruchteile, nicht Prozent** (NOx Euro 7 = 0.282175 ≙ 28,2 %). Formel: `EF = (α·v² + β·v + γ + δ/v) / (ε·v² + ζ·v + η) · (1 − RF)`, v geclampt auf [Min Speed, Max Speed]. Validiert gegen die mitgelieferte EF(v=80)-Kontrollspalte (EC N1-III Diesel Euro 7 DPF+SCR: 2.7322 MJ/km).
- Klassenmapping: siehe eigener Abschnitt unten (Rev. B).
- Xlsx-Quelle: `parcel-demand-2-matsim-pipeline/hagrid-input/emissions/1.A.3.b.i-iv Road Transport Appendix 4 Emission Factors Oct_2025.xlsx` (2026-07-28 aus `~/Downloads` dorthin verlegt; Provenance-Tabelle: `hagrid-input/emissions/SOURCES.md`) — wird NICHT committet (Größe, wie der übrige `hagrid-input`-Inhalt); committet werden die extrahierten CSVs + `data/README.md` mit URL/Version/Download-Datum.
- Tests laufen aus `parcel-demand-2-matsim-pipeline/analysis/kpi/`: `python -u -m pytest tests/<file> -v`.
- Git: auf Branch `hendrik` committen; Commit-Messages enden mit `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

### Klassenmapping (Rev. B, 2026-07-31)

Alle Fahrzeuge bleiben **LCV (N1) Diesel Euro 7, Technologie DPF+SCR**; BEV-Arm: dasselbe Segment mit `Fuel = Battery electric` (nur EC-Kurve). Variiert wird ausschließlich das **Segment**, also die methodeneigene Massenklasse:

| Fahrzeugtyp | Kapa | Länge | angesetzte Bezugsmasse | Segment |
|---|---|---|---|---|
| `ct_cep_size_s` | 100 | 4,0 m | ~1700 kg | **N1-II** |
| `ct_cep_size_m` | 165 | 5,0 m | ~2000 kg | **N1-III** |
| `ct_cep_size_l` | 230 | 6,0 m | ~2400 kg | **N1-III** |
| generierte `ct_cep_<cap>_<tpl>` (Hannover-Sweep) | var. | — | Kapazitätsregel | Kapa ≤ 120 → N1-II, sonst N1-III |
| DRT-Fahrzeug (`drt_*`, `capacity="10"`) | — | — | Sprinter-Tourer-Klasse | **N1-III** (Kategoriensubstitution, s.u.) |

Die Bezugsmasse ist **je Typ eine ausgewiesene Annahme** — sie ist die Größe, über die die EU-Typgenehmigung die N1-Segmente definiert (≤1305 / ≤1760 / >1760 kg); das Segment folgt daraus mechanisch. Damit steht pro Fahrzeugtyp *eine* nachvollziehbare Annahme statt einer freihändigen Segmentwahl.

**Achtung Zitatkette:** diese Massengrenzen stehen **nicht** im Guidebook-Kapitel (kein Treffer für „1305", „1760", „reference mass" im PDF); dort ist nur N1 als Ganzes definiert („vehicles used for the carriage of goods and having a maximum weight not exceeding 3.5 tonnes", Tab. 2-1). Die Segmentgrenzen sind aus der EU-Typgenehmigung zu zitieren, nicht aus dem Guidebook.

**Warum keine Zuladungsskalierung** (geprüft 2026-07-31, verworfen): Das Guidebook beschränkt die Lastkorrektur explizit auf schwere Nutzfahrzeuge — S. 62 f., Abschnitt „Emission corrections": *„road gradient and vehicle load. Corrections need to be made to **heavy-duty vehicle** emissions […] Also, by default, a factor of 50 % is considered for a load of heavy-duty vehicles."* Für LCV ist Zuladung **kein Methodenparameter** (0 von 1087 LCV-Zeilen im xlsx tragen `Load` oder `Road Slope`), und ein Referenz-Ladezustand ist für LCV nicht dokumentiert — es gibt also keinen Nullpunkt, an dem ein Lastmultiplikator ansetzen könnte. EMEP legt den Masseeffekt bei leichten Fahrzeugen in das **Segment**, nicht in die Last. Die Segmentdifferenzierung oben ist damit die methodenkonforme Abbildung genau dieses Effekts (43 % zwischen N1-II und N1-III bei 30 km/h); der nicht modellierte Lasteffekt wird als Limitation geführt und über die HDV-Parametrisierung gebounded (≤13 % für einen 7,5-Tonner, ~5 % für unsere Vans). Eine Quellenmischung (EMEP-Niveau × STREAM-Lastverhältnis) ist ausdrücklich **nicht** zulässig — sie hätte weder einen definierten Nullpunkt noch eine haltbare Invarianzannahme über zwei Methoden hinweg.

**Kategoriensubstitution M2 → N1-III** (zu benennende Annahme): Das DRT-Fahrzeug hat `capacity="10"`, ist also nach Guidebook-Definition M2 („more than eight seats in addition to the driver's seat […] not exceeding 5 tonnes", Tab. 2-1). Die beiden nominell passenden Alternativen sind schlechter — EC bei 30 km/h: PC Large-SUV-Executive Euro 7 Diesel 2,545 MJ/km (zu leicht), **LCV N1-III 3,123**, Buses Urban Midi ≤15 t Euro VI bei Guidebook-Default-Last 50 % ≈ 9,1 MJ/km (Faktor ~3 zu hoch). N1-III ist die technisch richtige Entsprechung (ein 10-sitziger Sprinter Tourer *ist* ein N1-III-Sprinter mit Sitzen). Im Paper mit diesen drei Zahlen ausschreiben, nicht stillschweigend annehmen.

**Wo das Mapping lebt:** in der committeten `analysis/kpi/`-Ebene (Segmentspalte in der CSV + Zuordnungsregel in `extract_emissions.py`), **nicht** als `engineInformation`-Attribut in `lmd-vehicle-types.xml` — `hagrid-input/**` ist gitignored und soll es bleiben; ein Mapping in einer nicht committeten Datei wäre nicht reproduzierbar.

## File Structure

- Create: `analysis/kpi/emep_factor_extract.py` — einmaliges xlsx→CSV-Tool (committet für Reproduzierbarkeit)
- Create: `analysis/kpi/data/emep_hot_factors.csv` — Tier-3-Koeffizienten für **alle drei N1-Segmente** (3 × 7 Diesel-Zeilen + 3 BEV-EC-Zeilen = 24)
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
- Produces: `data/emep_hot_factors.csv` mit Spalten (exakt): `powertrain,segment,pollutant,alpha,beta,gamma,delta,epsilon,zita,hta,rf,vmin,vmax,ef_check_v,ef_check,unit,source` — konsumiert von Task 2 `load_factors()`.
- `powertrain` ∈ {`diesel`,`bev`}; **`segment` ∈ {`N1-I`,`N1-II`,`N1-III`}**; `pollutant` ∈ {`CO`,`NOx`,`VOC`,`PM Exhaust`,`EC`,`CH4`,`SPN23`} (diesel) bzw. {`EC`} (bev). `unit`: `g/km` außer `EC`→`MJ/km`, `SPN23`→`#/km`. `rf` als Bruchteil.
- **Alle drei Segmente werden extrahiert**, auch das aktuell nicht zugeordnete N1-I. Grund: die Klassenzuordnung soll eine Datenzeile sein, keine Code-Änderung — und N1-I ist der Sensitivitäts-Nachbar von N1-II (Energie praktisch identisch: 2,157 vs. 2,183 MJ/km @30 km/h, NOx aber Faktor 1,67 auseinander).

- [x] **Step 1: Failing Test schreiben** — Transform-Funktion wird gegen einen In-Memory-DataFrame getestet (kein xlsx im Test nötig):

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
```

- [x] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v` (aus `analysis/kpi/`)
Expected: FAIL — `ModuleNotFoundError: emep_factor_extract`

- [x] **Step 3: Tool implementieren**

```python
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
```

- [x] **Step 4: Test laufen lassen, PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS

- [x] **Step 5: Tool einmal real ausführen und CSV erzeugen**

Run (aus `analysis/kpi/`):
`python -u emep_factor_extract.py "../../hagrid-input/emissions/1.A.3.b.i-iv Road Transport Appendix 4 Emission Factors Oct_2025.xlsx"`
Expected: `wrote .../data/emep_hot_factors.csv (24 rows)` — 3 Segmente × 7 Diesel-Zeilen (CO, NOx, VOC, PM Exhaust, EC, CH4, SPN23) + 3 BEV-EC-Zeilen. CSV öffnen und Stichprobe prüfen (verifizierte Werte, 2026-07-31):

| Segment | Diesel EC `ef_check` (v=80) | Diesel NOx | BEV EC |
|---|---|---|---|
| N1-I | 1.729636 | 0.038473 | 0.569087 |
| N1-II | 1.910160 | 0.093489 | 0.842321 |
| N1-III | **2.732177** | 0.093489 | 1.169166 |

Ebenso: `PM Exhaust` = 0.000142 in allen drei Segmenten (DPF → keine Segmentdifferenzierung).

> **Befund beim Realdurchlauf (2026-07-31), korrigiert gegen die Plan-Erwartung:** der NOx-`rf` ist **nicht** in allen drei Segmenten 0.282175 — N1-I trägt **0.92**, N1-II/III 0.282175. Das ist kein Extraktionsfehler: die mitgelieferte EF(80)-Kontrollspalte (N1-I NOx 0.038473) ist mit genau diesem `rf` konsistent, Task 2 prüft das gegen `ef_check`. Konsequenz für die Sensitivitätsaussage in den Interfaces oben: der NOx-Abstand N1-I ↔ N1-II (Faktor 1,67 bei 30 km/h) entsteht **überwiegend aus dem Reduktionsfaktor**, nicht aus der Rohkurve. Wer N1-I je als Segment zuordnet, ordnet damit auch eine andere Nachbehandlungsannahme zu — im Paper zu benennen, falls N1-I benutzt wird (aktuell wird es nicht, s. Klassenmapping).

Weiter aufgefallen und unkritisch: `vmin`/`vmax` variieren **je Pollutant und Segment** (N1-I meist 10–130; N1-II/III 5–110 für CO/VOC/SPN23, 5–140 NOx, 5–100 PM, 5–130 EC). Das Clamping in `ef(v)` ist deshalb korrekt pro Koeffizientensatz und nicht global.

- [x] **Step 6: Commit**

```bash
git add analysis/kpi/emep_factor_extract.py analysis/kpi/data/emep_hot_factors.csv analysis/kpi/tests/test_emissions_emep.py
git commit -m "feat(emissions): EMEP/EEA App.4 Tier-3 factor extraction (N1-I/II/III Euro 7 DPF+SCR + BEV)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Faktor-Loader + Tier-3-Kurvenauswertung `ef(v)`

**Files:**
- Create: `analysis/kpi/emissions_emep.py`
- Test: `analysis/kpi/tests/test_emissions_emep.py` (erweitern)

**Interfaces:**
- Consumes: `data/emep_hot_factors.csv` (Task-1-Schema).
- Produces: `emissions_emep.load_factors(data_dir=None) -> dict` mit Struktur `{"diesel": {segment: {pollutant: coef}}, "bev": {segment: {"EC": coef}}, "sup": {}}` — **eine Ebene tiefer als Rev. A** (`segment` ∈ {`N1-I`,`N1-II`,`N1-III`}). `sup` füllt Task 3; `coef` = dict mit float-Keys `alpha,beta,gamma,delta,epsilon,zita,hta,rf,vmin,vmax,ef_check_v,ef_check` + str `unit`. — `emissions_emep.ef(v_kmh, coef) -> float` unverändert.

- [x] **Step 1: Failing Tests anhängen** — der Regressionstest validiert JEDE committete Zeile gegen die mitgelieferte EF(80)-Kontrollspalte:

```python
# an tests/test_emissions_emep.py anhängen
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
```

- [x] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: FAIL — `ModuleNotFoundError: emissions_emep`

- [x] **Step 3: Modul implementieren**

```python
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
```

- [x] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS (alle 24 Zeilen reproduzieren die Kontrollspalte; falls eine Zeile mit rel=1e-6 scheitert, prüfen ob das Tool volle Zellpräzision exportiert hat — `to_csv` ohne `float_format` behält volle Präzision, das ist gewollt)

- [x] **Step 5: Commit**

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
- Produces: `emep_supplement.csv` mit Spalten `name,value,unit,source` und exakt diesen `name`-Keys (von Task 4 konsumiert): `ttw_co2_g_per_mj_diesel`, `wtt_co2e_g_per_mj_diesel`, `grid_co2e_g_per_mj`, `gwp_ch4`, `gwp_n2o`, `n2o_g_per_km_diesel_lcv`, **je Segment** `tsp_tyre_g_per_km_n1_{i,ii,iii}`, `tsp_brake_g_per_km_n1_{i,ii,iii}`, `tsp_road_g_per_km_n1_{i,ii,iii}`, `pm10_frac_tyre`, `pm10_frac_brake`, `pm10_frac_road`, `bev_tyre_mult`, `bev_brake_mult`, `bev_road_mult`, **`ev_range_km_low`, `ev_range_km_mid`, `ev_range_km_high`**.
- **Rev. B2 (Step-1-Verifikation):** die Non-Exhaust-Basen sind **segmentauflösend** (statt je einem `*_lcv`-Key nun drei je Abriebquelle). Die Gruppierung der Quelle — Tab. 3-4/3-8 fassen N1-II und N1-III zusammen, Tab. 3-6 trennt alle drei — wird **als Daten ausgeschrieben** (identische Werte für ii/iii), nicht in Code-Verzweigungen versteckt; die `source`-Spalte benennt die Gruppierung. Segment→Key ist damit `segment.lower().replace("-", "_")`.
- **Rev. B:** `ev_range_km` (Einzelwert) ist durch **drei Schwellen** ersetzt. Begründung (Messung 2026-07-31 über 12 Läufe mit Freight-Touren): bei 250 km ist die Überschreitung in **jedem** Lauf 0 %, das Gate wäre wirkungslos. Längste Tour überhaupt 183,3 km; base10c: max 158,8, p95 139,3. Trennschärfe liegt allein bei ~150 km (dort 0–13,4 % je Lauf). Ein einzelner Wert würde ein Nullresultat produzieren, das nach Absicherung aussieht.

- [x] **Step 1: Kandidatenwerte gegen die Originalquellen verifiziert (2026-07-31).** Kapitel **1.A.3.b.vi-vii** war laut `hagrid-input/emissions/SOURCES.md` noch nicht heruntergeladen — nachgeholt (`1.A.3.b.vi-vii Road tyre and brake wear 2025.pdf`, 38 S., von der EEA-Kapitelseite; SOURCES.md nachziehen). Ergebnis: **vier Werte korrigiert, eine Plan-Entscheidung retrahiert.** „Quelle gewinnt" hat hier real gegriffen:

  | Größe | Kandidat | **verifiziert** | Quelle |
  |---|---|---|---|
  | `ttw_co2_g_per_mj_diesel` | 74.2 (Handrechnung mit 42.7) | **74.22** | 3.169 kg CO2/kg Diesel ÷ **42.695** MJ/kg — beide aus dem Guidebook (CO2-je-kg-Fuel-Tabelle bzw. Tab. **3-28** „Default calorific and density values"). Damit ist der Wert quellenintern hergeleitet, nicht angenähert. B7 (Marktkraftstoff) ergäbe 3.144/42.32 ≈ 74.3 — Unterschied unter 0,1 %, deshalb der reine Fossilwert. |
  | `n2o_g_per_km_diesel_lcv` | 0.010 (geschätzt) | **0.011** | Tab. **3-68** (Kap. 1.A.3.b.i-iv), „Diesel passenger cars and LCVs", Euro 7: urban cold 9 / **urban hot 11** / rural 4 / highway 4 mg/km. Es gibt keine Segmentauflösung. Gewählt: urban hot = konservativ (höchster Warmwert). Rural 4 mg/km ist die Untergrenze für die Sensitivität. |
  | Bremsabrieb-Basis | 0.0117 „LCV" | **N1-I 0.0117 / N1-II 0.0155 / N1-III 0.0211** | Tab. **3-6**. Der Kandidat war der **N1-I**-Wert — für unsere Flotte (N1-II/III) also 32 % bzw. 80 % zu niedrig. |
  | Straßenabrieb-Basis | 0.0150 „LCV" | **N1-I 0.0150 / N1-II+III 0.0210** | Tab. **3-8**. Ebenfalls der N1-I-Wert erwischt, +40 % für unsere Flotte. |
  | Reifenabrieb-Basis | 0.0169 „LCV" | **N1-I 0.0107 / N1-II+III 0.0169** ✓ | Tab. **3-4**. Kandidat war richtig und ist der N1-II/III-Wert. |
  | PM10-Anteile | 0.60 / 0.98 / 0.50 | **0.600 / 0.980 / 0.50** ✓ | Tab. **3-5** (Reifen), **3-7** (Bremse), **3-9** (Straße). |
  | Speed-Korrekturen | wie im Plan-Code | ✓ **wörtlich bestätigt** | Gl. (5): `S_T = 1.39` (V<40), `−0.00974·V+1.78` (40–90), `0.902` (V>90), normiert auf 80 km/h. Gl. (8): `S_B = 1.67` (V<40), `−0.0270·V+2.75` (40–95), `0.185` (V>95), normiert auf 65 km/h. Straßenabrieb ist **geschwindigkeitsunabhängig** (Gl. 9). |
  | `bev_tyre_mult` / `bev_brake_mult` | 1.15 / 0.50 (Annahmen) | **1.0841 / 0.2113** (+ neu `bev_road_mult` **1.1267**) | Nicht mehr Annahme: das Guidebook gibt für **Pkw** ICE- und BEV-Zeilen je Segment und erlaubt damit, das methodeneigene Verhältnis zu bilden — Medium-Pkw ICE→BEV: Reifen 0.0116/0.0107, Bremse 0.0030/0.0142, Straße 0.0169/0.0150. Physikalische Basis im Text: PMP-Reibbremsanteil **0.17** für Elektro, kombiniert mit der höheren WLTP-Fahrzeugmasse. Der bisher angenommene Bremswert 0.50 lag **Faktor 2,4 zu hoch**. |

  **Retrahiert: „Non-Exhaust ist für LCV nicht segmentauflösbar."** Das war die Begründung dafür, `non_exhaust_pm10` ohne `segment`-Argument zu bauen (Task 4). Es ist falsch — Tab. 3-4/3-6/3-8 lösen LCV **nach N1-Segment** auf, die Bremse sogar dreifach. Konsequenz: `segment` wird Pflichtargument, und die im Plan als „bewusste Asymmetrie" beschriebene Stelle entfällt. Innerhalb unserer Flotte wirkt es real: N1-II→N1-III hebt den Bremsabrieb um **36 %**.

  **Nebenbefund, der die Lastentscheidung zusätzlich stützt:** auch im Non-Exhaust-Kapitel ist die Lastkorrektur **HDV-only** — `LCF_T = 1.41 + 1.38·LF` (Gl. 4) und `LCF_B = 1 + 0.79·LF` (Gl. 7) gelten explizit nur für Trucks, Busse und Reisebusse. Für LCV gibt es auch hier keinen Lastparameter. Zweite unabhängige Belegstelle für die Begründung in den Global Constraints.

  **Nicht guidebook-basiert und weiterhin extern zu zitieren:** `wtt_co2e_g_per_mj_diesel` (JEC WTW v5), `grid_co2e_g_per_mj` (UBA-Strommix), `gwp_ch4`/`gwp_n2o` (IPCC AR6 GWP100: CH4 fossil 29.8, N2O 273). Diese vier sind Systemgrenzen-Parameter, nicht Guidebook-Größen (METHODS-LOG §1.4).

- [x] **Step 2: Failing Test anhängen**

```python
# an tests/test_emissions_emep.py anhängen
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
```

- [x] **Step 3: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py::test_supplement_present_and_plausible -v`
Expected: FAIL — `emep_supplement.csv` fehlt (leeres `sup`)

- [x] **Step 4: CSV schreiben** (Werte ggf. per Step-1-Verifikation korrigiert; `source` dann entsprechend präzisieren):

```csv
name,value,unit,source
ttw_co2_g_per_mj_diesel,74.22,g CO2/MJ,"derived inside the source: 3.169 kg CO2/kg diesel / 42.695 MJ/kg (GB ch. 1.A.3.b.i-iv, CO2-per-kg-fuel table + Tab. 3-28)"
wtt_co2e_g_per_mj_diesel,18.9,g CO2e/MJ,"NOT a guidebook value: JEC WTW v5 diesel B7 upstream"
grid_co2e_g_per_mj,105.6,g CO2e/MJ,"NOT a guidebook value: UBA Strommix DE ~380 g CO2e/kWh (Sensitivitaetsparameter)"
gwp_ch4,29.8,kg CO2e/kg,"NOT a guidebook value: IPCC AR6 GWP100 fossil methane"
gwp_n2o,273.0,kg CO2e/kg,"NOT a guidebook value: IPCC AR6 GWP100"
n2o_g_per_km_diesel_lcv,0.011,g/km,"GB ch. 1.A.3.b.i-iv Tab. 3-68, diesel PC+LCV Euro 7: urban hot 11 mg/km (konservativ; rural 4 = Sensitivitaetsuntergrenze)"
tsp_tyre_g_per_km_n1_i,0.0107,g/km,"GB ch. 1.A.3.b.vi Tab. 3-4, LCV (N1-I)"
tsp_tyre_g_per_km_n1_ii,0.0169,g/km,"GB ch. 1.A.3.b.vi Tab. 3-4, LCV (N1-II, III) -- Quelle gruppiert II und III"
tsp_tyre_g_per_km_n1_iii,0.0169,g/km,"GB ch. 1.A.3.b.vi Tab. 3-4, LCV (N1-II, III) -- Quelle gruppiert II und III"
tsp_brake_g_per_km_n1_i,0.0117,g/km,"GB ch. 1.A.3.b.vi Tab. 3-6, LCV (N1-I)"
tsp_brake_g_per_km_n1_ii,0.0155,g/km,"GB ch. 1.A.3.b.vi Tab. 3-6, LCV (N1-II) -- Bremstabelle trennt alle drei Segmente"
tsp_brake_g_per_km_n1_iii,0.0211,g/km,"GB ch. 1.A.3.b.vi Tab. 3-6, LCV (N1-III) -- Bremstabelle trennt alle drei Segmente"
tsp_road_g_per_km_n1_i,0.0150,g/km,"GB ch. 1.A.3.b.vii Tab. 3-8, LCV (N1-I), Qualitaetscode C-D"
tsp_road_g_per_km_n1_ii,0.0210,g/km,"GB ch. 1.A.3.b.vii Tab. 3-8, LCV (N1-II, III), Qualitaetscode C-D"
tsp_road_g_per_km_n1_iii,0.0210,g/km,"GB ch. 1.A.3.b.vii Tab. 3-8, LCV (N1-II, III), Qualitaetscode C-D"
pm10_frac_tyre,0.600,fraction,"GB ch. 1.A.3.b.vi Tab. 3-5 mass fraction PM10 of TSP, tyre"
pm10_frac_brake,0.980,fraction,"GB ch. 1.A.3.b.vi Tab. 3-7 mass fraction PM10 of TSP, brake"
pm10_frac_road,0.50,fraction,"GB ch. 1.A.3.b.vii Tab. 3-9 mass fraction PM10 of TSP, road"
bev_tyre_mult,1.0841,factor,"guidebook-internes ICE->BEV-Verhaeltnis Medium-Pkw: Tab. 3-4 0.0116/0.0107 (deklarierter Kategorientransfer, keine freie Annahme)"
bev_brake_mult,0.2113,factor,"guidebook-internes ICE->BEV-Verhaeltnis Medium-Pkw: Tab. 3-6 0.0030/0.0142; physikalische Basis im Quelltext: PMP-Reibbremsanteil 0.17 fuer Elektro"
bev_road_mult,1.1267,factor,"guidebook-internes ICE->BEV-Verhaeltnis Medium-Pkw: Tab. 3-8 0.0169/0.0150 (Masseeffekt auf Strassenabrieb)"
kg_per_parcel,1.65,kg,"ASSUMPTION, deliberately rounded (user 2026-07-31). Order of magnitude supported by three sources: Amaral et al. 2026 TR-E Tab.1 mean 1.6478 kg (only measured mean; BRAZILIAN data, declared transfer); Rajendran & Harper 2021 TRIP 1-350 lbs, >50 % under 5 lbs (no mean given); Mohri et al. 0.5-5 kg. MEAN not median - total on-board mass = n x mean; Amaral median 0.6950 would understate by 58 %. Plausible band on the mean 1.3-2.5 kg (~16 pp on the allocation share at 50 parcels)"
kg_per_passenger,80.0,kg,"SETTING, not a source: common road-transport convention, excl. luggage. Only the ratio to kg_per_parcel drives the allocation, so this weighs as much as the sourced parcel mass. Pure post-processing: changing it needs no sim rerun and leaves total_* unchanged"
slots_per_seat_equiv,2.5,slots/seat,"scenario-defined capacity equivalence for the alternative allocation basis: 20 parcel slots / 8 seats (1c vehicle); sensitivity companion to the mass basis"
ev_range_km_low,150.0,km,"pessimistic real-world winter range, e-LCV (sweep threshold; discriminating: 0-13.4% tour exceedance across the 12 freight runs, 2026-07-31)"
ev_range_km_mid,200.0,km,"mid real-world range, e-LCV (sweep threshold)"
ev_range_km_high,250.0,km,"optimistic real-world range, e-LCV (sweep threshold; 0% exceedance in all runs -- kept as the upper anchor, NOT as a pass/fail gate)"
```

Dazu `data/README.md` (zitierfähige Quellendoku):

```markdown
# Emissionsfaktor-Quellen (Lausitz-Emissions-KPIs)

## emep_hot_factors.csv
Extrahiert aus: EMEP/EEA air pollutant emission inventory guidebook
**2023 - Update 2025**, Kapitel 1.A.3.b.i-iv "Road transport",
**Appendix 4** (Version Okt 2025, verlinkt auf COPERT v5.9.1).
Download: 2026-07-28 von
https://www.eea.europa.eu/en/analysis/publications/emep-eea-guidebook-2025
(Zitat-Hinweis: die Kopfzeile des Kapitels lautet "guidebook 2023 -
Update 2025" - NICHT "guidebook 2025".)
Extraktion: `emep_factor_extract.py` (Filter: LCV, Segmente N1-I/II/III,
Euro 7, Diesel DPF+SCR bzw. Battery electric). Formel und
EF(80)-Validierung: siehe `emissions_emep.py` +
`tests/test_emissions_emep.py`.
ACHTUNG: Spalte "Reduction Factor [%]" der Quelle enthaelt Bruchteile.

## Klassenmapping (Rev. B, 2026-07-31)
Alle Fahrzeuge sind LCV (N1) Diesel Euro 7 DPF+SCR bzw. Battery electric.
Differenziert wird nur das Segment, also die methodeneigene Massenklasse:

| Typ              | Kapa | angesetzte Bezugsmasse | Segment |
|------------------|------|------------------------|---------|
| ct_cep_size_s    | 100  | ~1700 kg               | N1-II   |
| ct_cep_size_m    | 165  | ~2000 kg               | N1-III  |
| ct_cep_size_l    | 230  | ~2400 kg               | N1-III  |
| ct_cep_<cap>_<t> | var. | Kapazitaetsregel       | <=120 -> N1-II, sonst N1-III |
| drt_* (cap 10)   | -    | Sprinter-Tourer-Klasse | N1-III (M2-Substitution) |

Die Bezugsmasse ist je Typ eine ausgewiesene ANNAHME; die N1-Segmente sind
ueber die Bezugsmasse definiert (<=1305 / <=1760 / >1760 kg, EU-Typ-
genehmigung - NICHT im Guidebook-Kapitel, dort ist nur N1 als Ganzes
definiert). Segmentwirkung bei 30 km/h: N1-II 2,183 vs. N1-III 3,123 MJ/km
(+43 %); NOx N1-I 0,054 vs. N1-II/III 0,090 g/km; PM exhaust identisch.

ZULADUNG ist bewusst NICHT modelliert - das Guidebook beschraenkt die
Lastkorrektur auf schwere Nutzfahrzeuge (Default-Lastfaktor 50 %, Kap.
1.A.3.b.i-iv S. 62 f.); LCV-Zeilen tragen keine Load-/Slope-Spalte und
keinen dokumentierten Referenz-Ladezustand. Der Masseeffekt liegt bei
EMEP im Segment. Bound des nicht modellierten Lasteffekts: <=13 % (HDV-
Parametrisierung Rigid <=7,5 t, 0->100 % Last bei 30 km/h), ~5 % fuer
unsere Vans. Faktoren aus anderen Quellen (z. B. STREAM-Lastverhaeltnisse
in src/hagrid_output_analysis/config.py) duerfen hier NICHT eingemischt
werden - keine gemeinsame Referenzbasis.

Kategoriensubstitution M2 -> N1-III fuer die DRT-Flotte (cap 10, also M2
nach Guidebook Tab. 2-1) ist eine benannte Annahme. Alternativen bei
30 km/h: PC Large-SUV-Exec 2,545 / LCV N1-III 3,123 / Buses Urban Midi
<=15 t (Default-Last 50 %) ~9,1 MJ/km.

## emep_supplement.csv
Je Zeile Quelle in der `source`-Spalte; Zeilen, die NICHT aus dem
Guidebook stammen, beginnen mit "NOT a guidebook value" (WTT-Diesel,
Strommix, GWP100). Euro-7-Faktoren sind aus Grenzwerten PROJIZIERT (Norm
greift fuer LCV ab ~2026/27) - im Paper kennzeichnen.
`ev_range_km_{low,mid,high}` sind Sweep-Schwellen, kein Pass/Fail-Gate:
bei 250 km ist die Ueberschreitung in allen 12 geprueften Laeufen 0 %
(laengste Tour 183 km), Trennschaerfe nur bei ~150 km.

## Non-Exhaust (Kap. 1.A.3.b.vi-vii, verifiziert 2026-07-31)
Quelle: `1.A.3.b.vi-vii Road tyre and brake wear 2025.pdf` (38 S., von der
EEA-Kapitelseite, s. hagrid-input/emissions/SOURCES.md).

TSP-Basen [g/km] je N1-Segment - die Quelle loest LCV SEHR WOHL nach
Segment auf (die urspruengliche Plan-Annahme des Gegenteils ist retrahiert):

| Abriebquelle | N1-I | N1-II | N1-III | Tabelle |
|--------------|------|-------|--------|---------|
| Reifen | 0.0107 | 0.0169 | 0.0169 | 3-4 (II+III gruppiert) |
| Bremse | 0.0117 | 0.0155 | 0.0211 | 3-6 (alle drei getrennt) |
| Strasse | 0.0150 | 0.0210 | 0.0210 | 3-8 (II+III gruppiert) |

PM10-Anteil des TSP: Reifen 0.600 (Tab. 3-5), Bremse 0.980 (Tab. 3-7),
Strasse 0.50 (Tab. 3-9).

Geschwindigkeitskorrekturen (mittlere TRIP-Geschwindigkeit, nicht
Konstantfahrt):
  Gl. (5) Reifen: 1.39 fuer V<40; -0.00974*V+1.78 fuer 40<=V<=90; 0.902
    fuer V>90. Normiert auf 80 km/h.
  Gl. (8) Bremse: 1.67 fuer V<40; -0.0270*V+2.75 fuer 40<=V<=95; 0.185
    fuer V>95. Normiert auf 65 km/h.
  Strassenabrieb: KEINE Geschwindigkeitsabhaengigkeit (Gl. 9).
Bei unseren ~30-36 km/h greifen also beide Plateauwerte, d. h. Bremsabrieb
x1.67 und Reifenabrieb x1.39 gegenueber der Normierungsgeschwindigkeit.

BEV: die Quelle hat keine BEV-Zeile fuer LCV, aber ICE- und BEV-Zeilen je
Pkw-Segment. Verwendet wird das guidebook-interne Verhaeltnis des
Medium-Pkw (Reifen 1.0841, Bremse 0.2113, Strasse 1.1267) als deklarierter
Kategorientransfer. Physikalische Basis im Quelltext: PMP-Reibbremsanteil
0.17 fuer Elektro, kombiniert mit hoeherer WLTP-Fahrzeugmasse.

LASTKORREKTUR ist auch hier HDV-only: LCF_T = 1.41 + 1.38*LF (Gl. 4) und
LCF_B = 1 + 0.79*LF (Gl. 7) gelten explizit nur fuer Trucks, Busse und
Reisebusse. Fuer LCV existiert kein Lastparameter - zweite unabhaengige
Belegstelle fuer die Lastentscheidung des Plans.

Vorbehalt der Quelle selbst, im Paper zu nennen: die PM10/PM2.5-Werte der
Reifentabelle sind laut Guidebook UEBERSCHAETZT (neuere Messungen finden
den PM10-Anteil am Gesamtreifenabrieb deutlich unter 3 %); eine Revision
war zum Redaktionsstand nicht moeglich. Der Strassenabrieb ist mit
Qualitaetscode C-D als "highly uncertain" gekennzeichnet.
```

- [x] **Step 5: PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS (alle Tests, auch die aus Task 1/2)

- [x] **Step 6: Commit**

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
- Produces (von Task 5/5b/6 konsumiert): `vehicle_emissions(km, v_kmh, powertrain, segment, fac) -> dict[str, float]` mit exakt den Keys `CO, NOx, VOC, PM_EXHAUST, CH4, SPN23, N2O, CO2, CO2E_TTW, CO2E_WTW, ENERGY_MJ, PM10_TYRE, PM10_BRAKE, PM10_ROAD, PM10_NONEXHAUST` — Massen in Gramm, `ENERGY_MJ` in MJ, `SPN23` in Partikelanzahl. `powertrain` ∈ {`"diesel"`,`"bev"`}; **`segment` ∈ {`"N1-I"`,`"N1-II"`,`"N1-III"`}** (Rev. B, neues Pflichtargument); `fac` = Rückgabe von `load_factors()`.
- Produces: `non_exhaust_pm10(km, v_kmh, powertrain, segment, sup) -> (tyre_g, brake_g, road_g)`.
  > **Korrigiert gegenüber Rev. B (Step-1-Verifikation 2026-07-31):** hier stand „kein `segment`-Argument, die Non-Exhaust-Basen sind für LCV nicht segmentiert — bewusste Asymmetrie". Das ist widerlegt: Tab. 3-4/3-8 gruppieren N1-II+III, Tab. 3-6 trennt alle drei Segmente. `segment` ist Pflichtargument, die Asymmetrie entfällt. Segment→Key: `segment.lower().replace("-", "_")`.

- [x] **Step 1: Failing Tests anhängen**

```python
# an tests/test_emissions_emep.py anhängen
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
    # BEV: mehr Reifen-, weniger Bremsabrieb als Diesel
    d = em.vehicle_emissions(100.0, 80.0, "diesel", "N1-III", fac)
    assert out["PM10_TYRE"] > d["PM10_TYRE"]
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
```

- [x] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: FAIL — `vehicle_emissions` nicht definiert

- [x] **Step 3: Implementieren** (an `emissions_emep.py` anhängen):

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


def _seg_key(prefix, segment):
    """'tsp_brake_g_per_km_' + 'N1-II' -> 'tsp_brake_g_per_km_n1_ii'."""
    return prefix + segment.lower().replace("-", "_")


def non_exhaust_pm10(km, v_kmh, powertrain, segment, sup):
    """PM10 [g] from tyre / brake / road-surface wear over km at mean speed
    v, for an N1 `segment`.

    Segment-resolved because the source is: ch. 1.A.3.b.vi-vii gives TSP
    bases per N1 segment -- Tab. 3-4 (tyre) and Tab. 3-8 (road) group
    N1-II and N1-III into one row, Tab. 3-6 (brake) separates all three.
    That grouping is written out as data (identical values for ii/iii) so
    the source structure stays visible in emep_supplement.csv.

    Speed corrections are the guidebook's own eq. (5) / (8) on MEAN TRIP
    speed; road-surface wear has no speed dependence (eq. 9).

    BEV multipliers are the guidebook's own ICE->BEV ratios for the medium
    passenger car (the source has no BEV row for LCV) -- a declared
    category transfer, not a free assumption. See data/README.md.

    Raises KeyError on an unknown segment: a vehicle type without a
    mapping must fail loudly rather than be silently priced as N1-III.
    """
    bev = powertrain == "bev"
    tyre = (km * sup[_seg_key("tsp_tyre_g_per_km_", segment)]
            * sup["pm10_frac_tyre"] * _tyre_speed_corr(v_kmh)
            * (sup["bev_tyre_mult"] if bev else 1.0))
    brake = (km * sup[_seg_key("tsp_brake_g_per_km_", segment)]
             * sup["pm10_frac_brake"] * _brake_speed_corr(v_kmh)
             * (sup["bev_brake_mult"] if bev else 1.0))
    road = (km * sup[_seg_key("tsp_road_g_per_km_", segment)]
            * sup["pm10_frac_road"]
            * (sup["bev_road_mult"] if bev else 1.0))
    return tyre, brake, road


def vehicle_emissions(km, v_kmh, powertrain, segment, fac):
    """Full pollutant vector [g; ENERGY_MJ in MJ; SPN23 in #] for `km`
    driven at mean travelling speed `v_kmh` by a vehicle of N1 `segment`.

    The segment IS the mass channel: EMEP/EEA resolves vehicle mass for
    light vehicles through the N1 segment and provides no load dimension
    for LCV (guidebook ch. 1.A.3.b.i-iv p. 62 f. restricts the load
    correction to HDV). There is deliberately no load/payload argument --
    see the plan's Global Constraints. Idle and cold-start are NOT modelled
    (documented limitation: engine-off at service stops assumed;
    cold-start bounded in the plan's docs task).

    Raises KeyError on an unknown segment -- a new vehicle type must fail
    loudly rather than be silently priced as N1-III.
    """
    sup = fac["sup"]
    out = {}
    if powertrain == "diesel":
        coefs = fac["diesel"][segment]
        for poll, key in _POLL_KEY.items():
            out[key] = km * ef(v_kmh, coefs[poll])
        ec = km * ef(v_kmh, coefs["EC"])
        out["ENERGY_MJ"] = ec
        out["CO2"] = ec * sup["ttw_co2_g_per_mj_diesel"]
        out["N2O"] = km * sup["n2o_g_per_km_diesel_lcv"]
        out["CO2E_TTW"] = (out["CO2"] + sup["gwp_ch4"] * out["CH4"]
                           + sup["gwp_n2o"] * out["N2O"])
        out["CO2E_WTW"] = out["CO2E_TTW"] + ec * sup["wtt_co2e_g_per_mj_diesel"]
    elif powertrain == "bev":
        ec = km * ef(v_kmh, fac["bev"][segment]["EC"])
        out = {k: 0.0 for k in EXHAUST_KEYS}
        out["ENERGY_MJ"] = ec
        out["CO2E_WTW"] = ec * sup["grid_co2e_g_per_mj"]
    else:
        raise ValueError("unknown powertrain: " + str(powertrain))
    tyre, brake, road = non_exhaust_pm10(km, v_kmh, powertrain, segment, sup)
    out["PM10_TYRE"], out["PM10_BRAKE"], out["PM10_ROAD"] = tyre, brake, road
    out["PM10_NONEXHAUST"] = tyre + brake + road
    return out
```

- [x] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS

- [x] **Step 5: Commit**

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
- Consumes: `emissions_emep.load_factors()`, `vehicle_emissions()` (Task 4); `common.row`; TSV `<run>/analysis/freight/TimeDistance_perVehicle.tsv` mit Spalten `vehicleId, carrierId, vehicleTypeId, tourId, travelDistance[km], travelTime[s]`.
- **Verfügbarkeit (verifiziert 2026-07-31):** die TSV existiert in den LMD_BASELINE-Läufen und in den DRT_BASELINE-Läufen mit `freight=true` — u. a. im window-vereinheitlichten **`base10c`** (63 Touren, 6252 km, v̄ 36,4 km/h). Sie existiert **nicht** in den DRT_MODULAR-(1d-) und DRT_SHAREDUSE-(1c-)Läufen; dafür Task 5b.
- Produces: `extract_emissions.freight_arm(run_dir, fac) -> (totals, detail)` — `totals[powertrain][key] = float` (Summen über alle Touren, Keys wie `vehicle_emissions`), `detail` = Liste von dicts `{fleet:"freight", entity: vehicleId, vehicle_type, segment, km, v_kmh, powertrain, <alle Emissions-Keys>}`. Später von Task 7 (`extract`) konsumiert.
- Produces: `segment_for_type(type_id, capacity=None) -> str` — die Zuordnungsregel aus den Global Constraints, **eine Funktion statt eines Exact-ID-Dicts**. Rev.-A-Grund für die Änderung: [`CarrierVehicleFactory.java:204-210`](../../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/demand/CarrierVehicleFactory.java#L204-L210) erzeugt Fahrzeugtypen zur Laufzeit (`ct_cep_<cap>_m` für Kapa ≤165, `ct_cep_<cap>_l` darüber). Ein Exact-ID-Dict hätte den gesamten Hannover-Kapazitätssweep in den „unmapped → skipped"-Zweig geschickt, also stillschweigend auf null Emissionen.

- [x] **Step 1: Failing Test schreiben** (Fixture-TSV wird per tmp_path erzeugt):

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
    # Tour 1: 120 km bei 120/4=30 km/h (size_m -> N1-III)
    # Tour 2:  60 km bei  60/2=30 km/h (size_l -> N1-III)
    exp = em.vehicle_emissions(120.0, 30.0, "diesel", "N1-III", fac)
    exp2 = em.vehicle_emissions(60.0, 30.0, "diesel", "N1-III", fac)
    assert totals["diesel"]["CO2E_WTW"] == pytest.approx(
        exp["CO2E_WTW"] + exp2["CO2E_WTW"])
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
    assert d["ENERGY_MJ"] == pytest.approx(exp["ENERGY_MJ"])
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
```

- [x] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: FAIL — `ModuleNotFoundError: extract_emissions`

- [x] **Step 3: Implementieren**

```python
# -*- coding: utf-8 -*-
# analysis/kpi/extract_emissions.py
"""Emission KPI rows (kpi_group="environment") for the Lausitz runs.

Tier-3 method: per freight TOUR / per DRT VEHICLE, evaluate the EMEP/EEA
speed curves at the entity's mean travelling speed (distance / driving
time, service dwell excluded -- engine-off assumption at stops), multiply
by its km. Both powertrain arms (diesel primary + BEV variant) are always
computed from the same runs -- electrification is a factor swap, no re-run.

Fleet mapping (Rev. B, 2026-07-31): all vehicles are LCV (N1) Euro 7; only
the SEGMENT varies, because that is where EMEP/EEA carries the vehicle-mass
effect for light vehicles (no load dimension exists for LCV). See
segment_for_type() and data/README.md. An unmappable type raises rather
than being silently priced as N1-III.
"""
import csv
from pathlib import Path

import pandas as pd

import emissions_emep as em
from common import row

# Explicit reference-mass assumption per named van type -> N1 segment.
# The named types are what the LMD carriers use; CarrierVehicleFactory
# additionally creates ct_cep_<cap>_<tpl> at runtime (see CAP_SEGMENT_LIMIT).
SEGMENT_BY_TYPE = {"ct_cep_size_s": "N1-II",     # ~1700 kg reference mass
                   "ct_cep_size_m": "N1-III",    # ~2000 kg
                   "ct_cep_size_l": "N1-III"}    # ~2400 kg
CAP_SEGMENT_LIMIT = 120.0      # parcels; <= -> N1-II, > -> N1-III
DRT_SEGMENT = "N1-III"         # M2 (cap 10) substituted by N1-III, see docs
POWERTRAINS = ("diesel", "bev")


def segment_for_type(type_id, capacity=None):
    """N1 segment for a carrier vehicleTypeId.

    Named types resolve from SEGMENT_BY_TYPE. Runtime-generated sweep types
    (ct_cep_<cap>_<tpl>, CarrierVehicleFactory.java:204-210) resolve from
    `capacity` against CAP_SEGMENT_LIMIT -- the boundary sits between the
    two named capacities 100 (N1-II) and 165 (N1-III).

    Raises ValueError when neither applies: a new vehicle type must fail
    loudly, not inherit N1-III by accident.
    """
    tid = str(type_id)
    if tid in SEGMENT_BY_TYPE:
        return SEGMENT_BY_TYPE[tid]
    if capacity is not None and tid.startswith("ct_cep_"):
        return "N1-II" if float(capacity) <= CAP_SEGMENT_LIMIT else "N1-III"
    raise ValueError("no N1 segment mapping for vehicle type '" + tid
                     + "' (capacity=" + str(capacity) + "); extend "
                     "SEGMENT_BY_TYPE in extract_emissions.py")

# output keys of emissions_emep.vehicle_emissions
EMIS_KEYS = ("CO", "NOx", "VOC", "PM_EXHAUST", "CH4", "SPN23", "N2O", "CO2",
             "CO2E_TTW", "CO2E_WTW", "ENERGY_MJ", "PM10_TYRE", "PM10_BRAKE",
             "PM10_ROAD", "PM10_NONEXHAUST")


def _zero_totals():
    return {pt: {k: 0.0 for k in EMIS_KEYS} for pt in POWERTRAINS}


def _add_entity(totals, detail, fleet, entity, vtype, segment, km, v_kmh, fac):
    for pt in POWERTRAINS:
        out = em.vehicle_emissions(km, v_kmh, pt, segment, fac)
        for k in EMIS_KEYS:
            totals[pt][k] += out[k]
        d = {"fleet": fleet, "entity": entity, "vehicle_type": vtype,
             "segment": segment, "km": km, "v_kmh": v_kmh, "powertrain": pt}
        d.update({k: out[k] for k in EMIS_KEYS})
        detail.append(d)


def _capacities(run_dir):
    """{typeId: capacity} from the run's carrier vehicle types, for the
    capacity rule on generated sweep types. Empty dict if unavailable --
    the named types resolve without it."""
    try:
        import carriers_parse
    except ImportError:
        return {}
    for name in ("*output_carriersVehicleTypes.xml.gz",
                 "*carriersVehicleTypes.xml*"):
        for p in Path(run_dir).glob(name):
            try:
                vt = carriers_parse.parse_vehicle_types(p)
                return {k: v.capacity for k, v in vt.items()}
            except Exception as e:
                print("[emissions] vehicle types unreadable: " + str(e))
    return {}


def freight_arm(run_dir, fac):
    """Per-tour emissions from the CarriersAnalysis TSV (conventional LMD
    arm). Returns (totals, detail) or None if the TSV is absent -- that is
    the normal case for pax-only runs AND for the modular/shared-use arms,
    which are handled by modular_freight_arm()."""
    tsv = Path(run_dir) / "analysis" / "freight" / "TimeDistance_perVehicle.tsv"
    if not tsv.exists():
        return None
    td = pd.read_csv(tsv, sep="\t")
    caps = _capacities(run_dir)
    totals, detail = _zero_totals(), []
    for _, r in td.iterrows():
        vtype = str(r["vehicleTypeId"])
        km = float(r["travelDistance[km]"])
        tt_h = float(r["travelTime[s]"]) / 3600.0
        if km <= 0 or tt_h <= 0:
            continue
        segment = segment_for_type(vtype, caps.get(vtype))   # raises if unknown
        _add_entity(totals, detail, "freight", str(r["vehicleId"]), vtype,
                    segment, km, km / tt_h, fac)
    return totals, detail
```

**Hinweis zu `_capabilities`/`carriers_parse`:** die Funktionsnamen in `carriers_parse.py` vor der Implementierung verifizieren ([`test_carriers_parse.py`](../../parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_carriers_parse.py) zeigt `vt["ct_cep_size_s"].capacity == 100.0`, der Loader-Name kann abweichen). Fällt die Kapazitätsauflösung aus, resolven die drei benannten Typen weiterhin — nur der Hannover-Sweep bräuchte sie.

- [x] **Step 4: PASS bestätigt** — 5/5 grün.

- [x] **Step 5: Gegenprobe am Realdatenlauf (2026-07-31).** Der lokal vorliegende Lauf, dessen Kennzahlen der Plan unter „base10c" führt, ist **`LMD_BASELINE_13052025_bandz_central_iter0_jsprit100`** — der Extractor reproduziert dessen Zahlen exakt: **63 Touren, 6252,1 km**, 93 % der km auf N1-II (`size_s`), v̄ 34,1–39,9 km/h. Ebenso reproduziert: `localdepots_stagger` = **100 % N1-III**. Damit ist die Endogenität des Mixes (§2.7) an zwei Läufen belegt und nicht nur behauptet.

  **Die Kernrechtfertigung der Segmentdifferenzierung hält am Realdatenlauf:** eine Einheitsklasse N1-III überschätzt die Freight-Energie um **+38,6 %** (13.128 → 18.202 MJ) — der Plan sagte „~39 %". Gegenrichtung: eine Einheitsklasse N1-II läge nur −3,1 % daneben, weil der Mix ohnehin überwiegend `size_s` ist. Das ist die eigentliche Aussage: **die Einheitsklasse wäre nicht ungenau, sondern systematisch in eine Richtung falsch.**

  **Neuer Nebeneffekt, erst durch die Task-3-Korrektur messbar:** die Segmentdifferenzierung wirkt auch auf der PM-Seite mit **+16,8 %** (316,6 → 369,7 g PM10-Abrieb). Solange `non_exhaust_pm10` segmentblind war, wäre dieser Unterschied strukturell **0 %** gewesen — die Retraktion hat also nicht nur die Buchführung korrigiert, sondern einen realen Effekt sichtbar gemacht.

  Alle drei geprüften Läufe: BEV liegt bei 34–35 % des Diesel-CO₂e-WTW; alle Tourmittelgeschwindigkeiten liegen unter 40 km/h, also im Plateau beider Abrieb-Speedkorrekturen.

- [x] **Step 6: Commit**

```bash
git add analysis/kpi/extract_emissions.py analysis/kpi/tests/test_extract_emissions.py
git commit -m "feat(emissions): freight arm - per-tour Tier-3 emissions from TimeDistance_perVehicle

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5b: Extractor — modularer Freight-Arm (1d/1c: Fracht im DRT-Fahrzeug) **[NEU, Rev. B]**

**Warum dieser Task existiert** (verifiziert 2026-07-31): In 1d und 1c fährt die Fracht in DRT-Fahrzeugen, es gibt **kein** `analysis/freight/`-Verzeichnis. Task 5 läuft dort ins `None`. Die Fahrleistung ist aber vorhanden: `m1d010` stellt 5894 von 6020 Paketen zu, 125 von 127 Touren, 4002,6 km Service + 1614,0 km Deadhead, 362,8 Freight-Fahrzeugstunden. Ohne diesen Task hätte der einzige methodisch valide Arm (1d) keine Emissionszahl.

**Zusätzlicher Grund — der Zurechnungs-Blocker:** die `modular_contaminated_kpis`-Zeile der 1d-Läufe führt `drt_vehicle_km` explizit unter **„not corrected"**. Zeit ist getrennt (`drt_freight_drive_hours_total`), Distanz nicht. Emissionen sind km × Faktor, also muss der Distanzsplit hier entstehen.

**Files:**
- Modify: `analysis/kpi/extract_emissions.py`
- Test: `analysis/kpi/tests/test_extract_emissions.py` (erweitern)

**Interfaces:**
- Consumes: `veh_path` (dict `vehicle_id -> [(link_id, occ), ...]`) und `link_len` wie Task 6; zusätzlich **Freight-Taskfenster** je Fahrzeug als Liste `[(t_start, t_end), ...]` aus den `MODULAR_FREIGHT_DRIVE`-Task-Events ([`Modular.java:38`](../../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/modular/Modular.java#L38): `DrtTaskType("MODULAR_FREIGHT_DRIVE", DRIVE)`).
- Produces: `freight_windows(events_file) -> dict[str, list[tuple[float, float]]]`.
- Produces: `modular_freight_arm(veh_path_ts, windows, link_len, fac) -> (totals, detail)` — `fleet="freight_modular"`, `vehicle_type="drt_modular"`, `segment=DRT_SEGMENT`.
- **Voraussetzung, die vor der Implementierung zu prüfen ist:** `veh_path` trägt derzeit `(link_id, occ)` ohne Zeitstempel. Für den Fensterschnitt braucht es `(link_id, occ, t)`. Erst prüfen, ob `geometry.reconstruct_drt_paths` den Zeitstempel schon mitführt oder ob er durchgeschleift werden muss — **das ist der einzige potenziell invasive Eingriff des Plans in bestehenden Code** und der Grund, diesen Task nach Task 6 zu implementieren, nicht davor.

- [x] **Step 1: Vorabprüfung erledigt (2026-07-31). Ergebnis: Zeitstempel fehlten, und der Plan-Ansatz „Tupel erweitern" wird NICHT umgesetzt.**

  Geprüft: `reconstruct_drt_paths` führt keine Zeit. Die Konsumenten entpacken aber **strikt 2-Tupel** an vier Stellen — [`build_kpis.py:138`](../../parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py#L138) (`for lid, occ in path`), [`maps._build_vehicles:73`](../../parcel-demand-2-matsim-pipeline/analysis/kpi/maps.py#L73), [`geometry.polyline_runs:201`](../../parcel-demand-2-matsim-pipeline/analysis/kpi/geometry.py#L201) — plus **drei Testdateien mit exakten Tupel-Assertions** (`test_geometry`, `test_maps`, `test_build_kpis`). Eine Arity-Änderung hätte also Karten- und Distanzcode angefasst, dessen einziger Nutzen beim neuen Emissionsmodul liegt.

  **Stattdessen umgesetzt:** neue Funktion `geometry.reconstruct_drt_paths_detailed(cache) -> (veh_path, used_links)` mit **4-Tupeln `(link_id, occ_pax, occ_parcels, t)`**; `reconstruct_drt_paths` wird zur dünnen **Projektion** darauf (`occ = occ_pax + occ_parcels`, exakt die Altsemantik, da der Altzähler nie unterschied). Damit:
  - kein Konsument und kein bestehender Test geändert — **45 bestehende Tests laufen unverändert grün**, das ist der Identitätsbeweis;
  - weiterhin **ein** Event-Pass (`build_kpis` kann später die Detailvariante einmal rufen und projizieren);
  - Task 5c bekommt die Pakt/Pax-Trennung aus derselben Funktion, statt sie separat nachzurüsten.

  Ein Test hält die Projektionsgleichheit explizit fest (`test_plain_variant_is_exactly_the_sum_projection`), damit die beiden Pfade nicht auseinanderlaufen.

- [x] **Step 1b: Zwei Plan-Fehler, die das echte Datenformat aufdeckt — beide hätten STILL NULL geliefert.**

  1. **Falsche Datei.** Der Plan sagt, `freight_windows` parse `*.freight_events_filtered.txt`, „in allen 1d-Läufen vorhanden". Gemessen: die Datei ist in **allen drei lokalen 1d-Läufen 0 Bytes** (`ctrl1d`, `m1d050`, `poc1d`). Die DVRP-Task-Events stehen im **drt**-Cache (`*.drt_events_filtered.txt`) — dort sind sie vollständig, weil `dvrpVehicle="drt_…"` den `drt_`-Filter passiert (m1d050: 34.067 Task-Start-Events im Cache, davon 6 `MODULAR_FREIGHT_DRIVE`).
  2. **Falsche Event-Typnamen.** Der Plan-Test schreibt `type="task started"` / `"task ended"`. Real heißt es **`dvrpTaskStarted`** / **`dvrpTaskEnded`**. Der Plan-Test hätte also gegen einen falschen Parser bestanden — genau die Fixture-Lüge, gegen die Task 5 den Realdatenabgleich vorsieht.

  Reales Event (m1d050): `<event time="73330.0" type="dvrpTaskStarted" person="drt_100" link="713626289#1" dvrpVehicle="drt_100" taskType="MODULAR_FREIGHT_DRIVE" taskIndex="319" dvrpMode="drt"/>`

  Konsequenz für die Signatur: `veh_path_ts` sind die **4-Tupel** der Detailvariante, nicht die im Plan skizzierten 3-Tupel — eine dritte Tupelform hätte niemandem genützt.

- [x] **Step 2: Failing Tests anhängen**

```python
# an tests/test_extract_emissions.py anhängen
def test_freight_windows_parsed_from_task_events(tmp_path):
    import extract_emissions as ee
    ev = tmp_path / "freight_events_filtered.txt"
    ev.write_text(
        '<event time="3600.0" type="task started" '
        'dvrpVehicle="drt_1" taskType="MODULAR_FREIGHT_DRIVE"/>\n'
        '<event time="7200.0" type="task ended" '
        'dvrpVehicle="drt_1" taskType="MODULAR_FREIGHT_DRIVE"/>\n'
        '<event time="8000.0" type="task started" '
        'dvrpVehicle="drt_1" taskType="STAY"/>\n',
        encoding="utf-8")
    w = ee.freight_windows(ev)
    assert w == {"drt_1": [(3600.0, 7200.0)]}

def test_modular_freight_arm_splits_km_by_window(tmp_path):
    """Nur die km INNERHALB der Freight-Fenster zaehlen als Fracht; der
    Rest bleibt Pax. Das ist der Distanzsplit, den drt_vehicle_km nicht
    hat (METHODS-LOG 2.14)."""
    import emissions_emep as em
    import extract_emissions as ee
    fac = em.load_factors()
    # (link, occ, t): l1 vor dem Fenster, l2+l1 drin
    veh_path_ts = {"drt_1": [("l1", 0, 100.0), ("l2", 0, 4000.0),
                             ("l1", 0, 5000.0)]}
    windows = {"drt_1": [(3600.0, 7200.0)]}
    ll = ee.load_link_lengths(_network(tmp_path), {"l1", "l2"})  # 1200/800 m
    totals, detail = ee.modular_freight_arm(veh_path_ts, windows, ll, fac)
    d = [x for x in detail if x["powertrain"] == "diesel"][0]
    assert d["km"] == pytest.approx(2.0)          # l2 + l1 = 800 + 1200 m
    assert d["fleet"] == "freight_modular"
    assert d["segment"] == "N1-III"
    assert totals["diesel"]["CO2E_WTW"] > 0

def test_modular_freight_arm_empty_without_windows():
    import emissions_emep as em
    import extract_emissions as ee
    totals, detail = ee.modular_freight_arm(
        {"drt_1": [("l1", 0, 100.0)]}, {}, {"l1": 500.0}, em.load_factors())
    assert detail == [] and totals["diesel"]["CO2E_WTW"] == 0.0
```

- [x] **Step 3: Fehlschlag bestätigen** — `python -u -m pytest tests/test_extract_emissions.py -v`, erwartet `AttributeError: freight_windows`.

- [x] **Step 4: Implementieren.** `freight_windows` parst die `*.freight_events_filtered.txt` (in allen 1d-Läufen vorhanden) auf `taskType="MODULAR_FREIGHT_DRIVE"`-Start/Ende je `dvrpVehicle`. `modular_freight_arm` summiert die Link-Längen der Pfadeinträge, deren Zeitstempel in ein Fenster fällt, und nimmt als Geschwindigkeit `km / Σ Fensterdauer`. **Sanity-Anker gegen `modular_tour_stats.csv`:** die rekonstruierten Freight-km müssen in der Größenordnung von `service_km_planned + deadhead_km_planned` liegen (m1d010: 5616,5 km). Weicht es um >10 % ab, ist der Fensterschnitt falsch — dann laut abbrechen, nicht runden.

- [x] **Step 5: PASS + Realdaten-Gegenprobe (2026-07-31) — meterexakt.** Gegenprobe lokal an **`m1d050`** gefahren (der einzige lokal verfügbare 1d-Lauf mit tatsächlich disponierter Tour; `m1d010`/`m1d040` liegen auf dem Sim-PC):

  | Größe | rekonstruiert | `modular_tour_stats.csv` |
  |---|---|---|
  | Freight-km | **13,495 km** | `service_km_planned` 6,53166 + `deadhead_km_planned` 6,96289 = **13,49455 km** |

  Abweichung **< 1 m**, nicht die erlaubten 10 %. Der Fensterschnitt trifft also genau die geplante Tour — 1 Fahrzeug (`drt_100`), 3 Fenster (76 s / 538 s / 478 s). Laufzeit auf dem 76-MB-Cache: Fenster 0,2 s, Pfadrekonstruktion 1,2 s, Link-Längen 6,2 s.

  **Definitionsentscheidung, die die Gegenprobe sichtbar macht:** die Geschwindigkeit ist **km / Σ Fensterdauer** = 13,495 / 0,3033 h = **44,5 km/h**. Die Alternative `freight_vehicle_hours` (0,57 h, inkl. `MODULAR_FREIGHT_STOP` und Retooling) ergäbe 23,7 km/h — Faktor 1,9 auf der Geschwindigkeit und damit ein anderer Punkt auf der Tier-3-Kurve. Gewählt ist die **Fahrzeit ohne Standzeit**, konsistent mit dem konventionellen Arm (`travelTime[s]` ist dort ebenfalls fahrzeitbereinigt) und mit der Engine-off-Annahme an Stopps.

  **Nebenbefund — ZURÜCKGEZOGEN, bevor er ins Ergebniskapitel kommt (2026-07-31).** Zuerst notiert war: „44,5 km/h liegt über 40 km/h, also außerhalb des Abrieb-Plateaus der Van-Touren; der modulare Leg ist schneller, weil überwiegend Deadhead — die Abrieb-Speedkorrektur ist zwischen den Armen nicht identisch." Das ist **auf n=1 gebaut** und die Begründung ist falsch.

  Nachgemessene Zerlegung (mittlere Reisegeschwindigkeit = längengewichteter Freispeed × Realisierungsgrad):

  | Flotte | km | Ist-Geschw. | längengew. Freispeed | Realisierungsgrad |
  |---|---|---|---|---|
  | 1d Freight-Leg (**n=1 Tour**) | 13,5 | 44,5 | **56,5** | **0,79** |
  | 1d DRT-Pax | 47.146 | 37,5 | 43,5 | 0,86 |
  | Konventionelle Van-Touren | 6.252 | 36,7 | 41,8 | 0,88 |

  Der Unterschied kommt aus der **Straßenklasse** (+35 % Freispeed), **nicht** aus der Fahrdynamik — die wirkt sogar dagegen (0,79 vs. 0,88, der Freight-Leg realisiert *weniger* seiner Nominalgeschwindigkeit).

  **Und die eigentliche Entwarnung:** die drei Fenster mappen exakt auf die Tourabschnitte — Fenster 2 = 6,532 km = `service_km_planned` (6,53166), Fenster 1+3 = 6,963 km = `deadhead_km_planned` (6,96289). Auch der **Service**-Abschnitt fährt mit 56,2 km/h Freispeed. Der Grund: `m1d050` stellt **genau ein Paket** zu, der Service-Leg ist also eine einzelne lange Anfahrt statt einer dichten Zustellrunde. Bei realistischer Beladung (`m1d010`: 5894 Pakete / 125 Touren, `max_parcels_per_tour` 99) sind die Zwischenstopp-Legs kurze Wohngebiets-Hops; erwartete Richtung: Geschwindigkeit **auf oder unter** Van-Niveau, damit zurück ins Plateau.

  **NEEDS-CHECK ERLEDIGT (Sim-PC, ganzer 1d-Sweep, 2026-07-31).** Vorhersage bestätigt, Befund umgekehrt: **es gibt keinen Armunterschied.**

  | Lauf | Touren | Pakete | Freight-km | Abw. vs. geplant | v_Freight | Freispeed | Realis. | Pax-km | v_Pax |
  |---|---|---|---|---|---|---|---|---|---|
  | `m1d010` | 125 | 5894 | 5616,5 | **+0,00 %** | **37,1** | 42,4 | 0,87 | 41.473 | 37,7 |
  | `m1d020` | 56 | 2261 | 2517,7 | **−0,00 %** | **36,8** | 41,6 | 0,89 | 46.179 | 37,6 |
  | `m1d030` | 22 | 551 | 704,9 | **+0,00 %** | **36,8** | 41,8 | 0,88 | 48.068 | 37,6 |
  | `m1d040` | 2 | 9 | 21,9 | +0,00 % | 40,8 | 49,2 | 0,83 | 48.012 | 37,6 |
  | `m1d050` | 1 | 1 | 13,5 | +0,00 % | 44,5 | 56,5 | 0,79 | 47.146 | 37,5 |
  | `ctrl1d` | 0 | 0 | 0 | — | — | — | — | 48.824 | 37,6 |

  (`m1d015` lief zum Messzeitpunkt noch, Iteration 82/150.)

  **Sobald echte Beladung vorliegt (≥22 Touren), konvergiert die Freight-Geschwindigkeit auf 36,8–37,1 km/h** — praktisch identisch mit dem Pax-Betrieb (37,5–37,7) und mit den konventionellen Van-Touren (36,7). Der Freispeed konvergiert mit: 42,4 km/h, also sogar **leicht unter** dem Pax-Wert (43,8) — die Fracht fährt nicht auf schnelleren Straßen, sondern minimal langsameren. Die 44,5 km/h aus `m1d050` waren reines Kleinstichproben-Artefakt, und das Artefakt skaliert sauber mit der Dünnbesetzung (1 Tour 44,5 → 2 Touren 40,8 → 22 Touren 36,8).

  **Folge für Task 4/9:** alle drei Flotten liegen unter 40 km/h, also **im Plateau beider Abrieb-Speedkorrekturen** (Reifen 1,39 / Bremse 1,67). Die Korrekturen sind zwischen den Armen damit **identisch** — es gibt nichts zu erklären und keinen Unterschied zu berichten. Der Realisierungsgrad ist ebenfalls gleich (0,87–0,89 Freight vs. 0,86 Pax).

  **Und die Fensterrekonstruktion ist jetzt über drei Größenordnungen validiert:** die Abweichung gegen `service_km_planned + deadhead_km_planned` ist in **allen fünf** fertigen Läufen ±0,00 %, von 13,5 km bis 5616,5 km. Der im Plan gesetzte 10-%-Toleranzanker war um Größenordnungen zu locker.

- [x] **Step 6: Commit**

```bash
git add analysis/kpi/extract_emissions.py analysis/kpi/tests/test_extract_emissions.py
git commit -m "feat(emissions): modular freight arm - km split by MODULAR_FREIGHT_DRIVE task windows

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

> **Zurechnung — entschieden 2026-07-31** (METHODS-LOG §1.4): **1d = dieser Regimesplit**, restfrei und innerhalb eines Laufs gemessen. Die zwischenzeitlich erwogene inkrementelle Variante gegen `ctrl1d` ist **verworfen** (METHODS-LOG §3.9: Differenz liegt im Rauschband und trägt das falsche Vorzeichen, weil die Fracht Pax verdrängt statt km zu addieren). **1c** bekommt statt des Fensteransatzes die massenbasierte Aufteilung aus Task 5c. Allokationsfreie Systemsumme (`total_*`) wird in beiden Armen immer mitberichtet.

---

### Task 5c: Massenbasierte Zurechnung + spezifische Intensitäten (1c) **[NEU, Rev. B]**

**Entscheidung, die dieser Task umsetzt** (User 2026-07-31, METHODS-LOG §1.4/§2.24): allokationsfreie Systemsumme als Boden, dazu Aufteilung nach **Masse an Bord je Link** für CO₂e je Paket und je Fahrgast. Konvention **kg·km** (Masse × Linklänge), analog tkm-Allokation in EN 16258 / GLEC — dieselbe Norm, die für die WTT-Kette schon zitiert wird. Leerfahrten haben keine kg·km-Basis und werden proportional zu den kg·km-Anteilen des Fahrzeugtages verteilt (GLEC-Konvention).

**Warum nicht marginal:** siehe METHODS-LOG §2.24 Option M — Java-Eingriff + 1c-Rerun, und Grenzkosten summieren sich nicht zum Ganzen. Die Massenbasis ist direkt gemessen und summiert konstruktionsgemäß auf.

**Files:**
- Modify: `analysis/kpi/geometry.py` — `occ` in Pax- und Paketzähler trennen
- Modify: `analysis/kpi/extract_emissions.py`
- Modify: `analysis/kpi/data/emep_supplement.csv` — Massenkonstanten
- Test: `analysis/kpi/tests/test_extract_emissions.py`, `tests/test_geometry.py`

**Interfaces:**
- `geometry.reconstruct_drt_paths` liefert Pfadeinträge mit **getrennten Zählern**: `(link_id, occ_pax, occ_parcels, t)`. Trennung über `person_id.startswith("parcel_")` (Java-Konstante `SharedUse.PARCEL_PERSON_PREFIX`).
- Produces: `mass_split(n_pax, n_parcels, sup) -> (share_pax, share_parcels)` und `allocate_vehicle_by_mass(veh_path, link_len, veh_co2e, sup) -> {"pax": …, "parcels": …}` sowie `intensity_rows(alloc, n_pax, n_parcels) -> rows` mit den KPI-Zeilen `co2e_wtw_per_parcel` [kg/Paket], `co2e_wtw_per_pax` [kg/Fahrgast], `alloc_share_parcels_mass`, `alloc_share_parcels_slots` [share].
- Neue Supplement-Keys: `kg_per_parcel`, `kg_per_passenger`, `slots_per_seat_equiv`.

**Massenkonstanten (2026-07-31): `kg_per_parcel = 1.65`, eine Konstante, absichtlich gerundet.** Größenordnung durch drei Quellen gestützt:

| Quelle | Aussage |
|---|---|
| **Amaral et al. (2026):** *Empirical analysis of e-commerce delivery operations: from parcels to tours.* Transportation Research Part E, Tab. 1 | Mittel **1,6478 kg** — der einzige gemessene Mittelwert, liefert den Punktwert |
| **Rajendran & Harper (2021):** *Simulation-based algorithm for determining best package delivery alternatives under three criteria: Time, cost and sustainability.* Transportation Research Interdisciplinary Perspectives (TRIP) | 1–350 lbs, >50 % unter 5 lbs; **kein Mittelwert angegeben** |
| **Mohri, Nassir, Lavieri & Thompson (2024):** *Modeling package delivery acceptance in Crowdshipping systems by Public Transportation Passengers: A latent class approach.* Travel Behaviour and Society **35**, 100716 | Paketmassen **0,5–5 kg** |

**Kein B2C/B2B-Split** — der Kontrast (1,5922 vs. 1,8160) macht 3,2 Pp am Aufteilungsanteil aus und liegt innerhalb der Unsicherheit der Annahme selbst; differenzieren wäre Scheingenauigkeit. `kg_per_passenger = 80` bleibt eine **Setzung** ohne Quelle. Details und Vorbehalte: METHODS-LOG §2.26.

Zwei statistische Festlegungen, die im Code als Kommentar mitmüssen:
- **Mittelwert, nicht Median.** Gebraucht wird die Gesamtmasse an Bord, `Σ Gewichte = n × Mittelwert`; der Mittelwert ist dafür der unverzerrte Schätzer. Die Verteilung ist stark rechtsschief (Amaral-Median 0,6950 kg = 42 % des Mittels) — wer „robustheitshalber" den Median nimmt, unterschätzt die Paketmasse um 58 %.
- **Q1/Q3 aus Tab. 1 sind kein Sensitivitätsband für den Mittelwert** (das ist die Streuung einzelner Sendungen). Plausibles Band auf den Mittelwert: **1,3–2,5 kg**.

- [x] **Step 1: Vorabprüfungen (kein Code).** Zwei Dinge klären und Ergebnis hier notieren:
  1. **Weisen die bestehenden 1c-Occupancy-KPIs die Paket-Kontamination aus?** `occ` zählt in 1c Pakete mit (METHODS-LOG §2.26). Falls nicht ausgewiesen, ist das ein **eigener Befund für den 1c-Arm** (`occ_km`, `occ_segments`, `occ_time`, Occupancy-Karte) und gehört zuerst dort dokumentiert — nicht als Nebenprodukt der Emissionsrechnung.
  2. **Konsumenten von `veh_path` prüfen.** Die Tupelerweiterung berührt `veh_km`, `occ_km_shares`, `maps` und Task 5b/6. Indexzugriff statt Entpackung ist die Regel (Task 6 ist schon so umgestellt).

- [x] **Step 2: Failing Tests schreiben**

```python
# an tests/test_extract_emissions.py anhängen
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

def test_specific_intensity_rows(tmp_path):
    import extract_emissions as ee
    rows = ee.intensity_rows(alloc={"pax": 300.0, "parcels": 700.0},
                             n_pax=150, n_parcels=1400)
    by = {r["kpi_name"]: r for r in rows}
    assert by["co2e_wtw_per_pax"]["value"] == pytest.approx(2.0)
    assert by["co2e_wtw_per_parcel"]["value"] == pytest.approx(0.5)
    assert by["co2e_wtw_per_parcel"]["unit"] == "kg"
    assert "Amaral" in by["co2e_wtw_per_parcel"]["source"]
    # beide Konstanten und ihr Status muessen in der Provenance stehen
    assert "kg_per_passenger" in by["co2e_wtw_per_parcel"]["source"]
```

- [x] **Step 3: Fehlschlag bestätigen** — `python -u -m pytest tests/test_extract_emissions.py -v`.

- [x] **Step 4: Implementieren.** `mass_split(n_pax, n_parcels, sup)` bildet die kg·km-Anteile und **validiert die Konstante beim Laden** (`kg_per_parcel` unterhalb des plausiblen Bandes, also < 1,3 kg ⇒ `ValueError` mit Hinweis auf den Median-Fehler); `allocate_vehicle_by_mass` läuft den Fahrzeugpfad ab, summiert geladene kg·km je Seite und legt die Emission der Leer-Links proportional um; `intensity_rows` teilt durch bediente Mengen. Zusätzlich **beide** Aufteilungsvarianten emittieren: `alloc_share_parcels_mass` und `alloc_share_parcels_slots` (Kapazitätsbasis 8 Sitze / 20 Paketslots — szenariodefiniert, deshalb die Pflicht-Sensitivität gegen die belegte kg-Zahl, METHODS-LOG §2.26). Die `source`-Spalte der Intensitäts-Rows nennt **beide** Konstanten und ihren Status: „kg_per_parcel=1.65 (assumption; Amaral et al. 2026 TR-E Tab.1 + Rajendran & Harper 2021 + Mohri et al., none German - declared transfer); kg_per_passenger=80 is a setting".

- [x] **Step 5: PASS + Gegenprobe.** Zwei Invarianten am Realdatenlauf prüfen: (a) zugerechnete Summe == `total_co2e_wtw` bis auf Rundung; (b) `alloc_share_parcels_mass` und `_slots` als Paar berichten — divergieren sie um mehr als ~10 Prozentpunkte, ist das ein Paper-relevanter Befund und keine Rundung.

- [x] **Step 6: Commit**

```bash
git add analysis/kpi/geometry.py analysis/kpi/extract_emissions.py analysis/kpi/data/emep_supplement.csv analysis/kpi/tests/
git commit -m "feat(emissions): mass-based freight/pax allocation (kg*km, GLEC convention) + specific intensities

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Extractor — DRT-Arm (echte Link-Längen + DRIVE-Zeiten)

**Files:**
- Modify: `analysis/kpi/extract_emissions.py`
- Test: `analysis/kpi/tests/test_extract_emissions.py` (erweitern)

**Interfaces:**
- Consumes: `veh_path` von `geometry.reconstruct_drt_paths(drt_cache)` (dict `vehicle_id -> [(link_id, occ), ...]`); `recon["per_veh"][veh]["drive_s"]` von `drt_service_time.reconstruct()`; MATSim-Netzwerk `*.output_network.xml.gz` (Link-Attribut `length` in Metern — NICHT die Euklid-Näherung aus `geometry.load_link_geometry`, die ist ~3 % zu kurz).
- Produces: `load_link_lengths(network_gz, used_links) -> dict[str, float]` (Meter; **schon mit Task 5b implementiert**, der Plan führte sie doppelt); `drt_arm(veh_path, recon, link_len, fac, exclude_windows=None) -> (totals, detail)` — Struktur wie `freight_arm`, `fleet="drt"`, `entity=vehicle_id`, `vehicle_type="drt_minibus"`, `segment=DRT_SEGMENT` (`"N1-III"`, Kategoriensubstitution M2 → N1-III, siehe Global Constraints).

> **Doppelzählung behoben statt dokumentiert (2026-07-31).** Der Plan gab `drt_arm` ohne Ausschlussmenge und notierte die Überlappung mit dem modularen Freight-Arm in Task 7 als „KNOWN GAP". Das war der Stand vor der Zurechnungsentscheidung; die steht inzwischen (METHODS-LOG §1.4: 1d = **restfreier** Regimesplit). Deshalb neuer Parameter `exclude_windows`: Link-Einträge innerhalb eines `MODULAR_FREIGHT_DRIVE`-Fensters fallen aus dem Pax-Arm heraus.
>
> Die **Zeit**seite trennte ohnehin schon — `drt_service_time.reconstruct` bucht `taskType="DRIVE"` nach `drive_s` und `MODULAR_FREIGHT_DRIVE` nach `freight_drive_s` ([drt_service_time.py:411/414](../../parcel-demand-2-matsim-pipeline/analysis/drt-headline/drt_service_time.py#L411-L414)). Nur die Distanzseite fehlte, also genau die Größe, aus der Emissionen entstehen.
>
> **Verifiziert an m1d050:** Pax-Arm 47.146,35 km + Freight-Arm 13,49 km = 47.159,85 km = Gesamtpfad, **Residuum −0,0000 km**. Ohne den Ausschluss lägen die Freight-km doppelt in `total_*`. In `m1d050` wären das nur 2,61 von 12.710 kg CO₂e (0,02 %), weil dort eine einzige Tour lief — bei realistischer Beladung (`m1d010`: 5616,5 Freight-km gegen ~47.000 DRT-km) wären es **rund 12 %**. Also material, nicht kosmetisch. Ein Test hält die Restfreiheit fest (`test_drt_arm_excludes_freight_km_so_the_arms_do_not_double_count`).

- [x] **Step 1: Failing Tests anhängen**

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
    exp = em.vehicle_emissions(3.2, 36.0, "diesel", "N1-III", fac)
    assert totals["diesel"]["NOx"] == pytest.approx(exp["NOx"])
    d = [x for x in detail if x["powertrain"] == "diesel"][0]
    assert d["fleet"] == "drt" and d["km"] == pytest.approx(3.2)
    assert d["v_kmh"] == pytest.approx(36.0)
    assert d["segment"] == "N1-III"

def test_drt_arm_skips_vehicle_without_drive_time():
    import emissions_emep as em
    import extract_emissions as ee
    totals, detail = ee.drt_arm({"drt_v1": [("l1", 0)]},
                                {"per_veh": {}}, {"l1": 500.0},
                                em.load_factors())
    assert detail == [] and totals["diesel"]["CO2E_WTW"] == 0.0
```

- [x] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: FAIL — `load_link_lengths` nicht definiert

- [x] **Step 3: Implementieren** (an `extract_emissions.py` anhängen; Imports `gzip` + `xml.etree.ElementTree as ET` oben ergänzen):

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
    are skipped (never moved -> nothing to emit).

    Segment is DRT_SEGMENT ("N1-III"): the vehicle has capacity 10, i.e.
    M2 by the guidebook's own definition, and is substituted by N1-III --
    a declared assumption, see data/README.md for the bracketing figures.
    """
    per_veh = recon.get("per_veh", {}) if recon else {}
    totals, detail = _zero_totals(), []
    for veh, path in veh_path.items():
        drive_s = per_veh.get(veh, {}).get("drive_s", 0.0)
        km = sum(link_len.get(p[0], 0.0) for p in path) / 1000.0
        if drive_s <= 0 or km <= 0:
            continue
        _add_entity(totals, detail, "drt", veh, "drt_minibus", DRT_SEGMENT,
                    km, km / (drive_s / 3600.0), fac)
    return totals, detail
```

**Hinweis:** `sum(... for p in path)` statt `for lid, _occ in path` — Task 5b erweitert die Pfadeinträge ggf. auf `(link_id, occ, t)`; Indexzugriff bleibt dann gültig.

- [x] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: PASS

- [x] **Step 5: Commit**

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
- KPI-Namensschema: `{fleet}_{metric}{arm}` mit fleet ∈ {`drt`,`freight`,`freight_modular`,`total`}, arm ∈ {``,`_bev`}: `*_co2e_wtw` [kg], `*_co2e_ttw` [kg], `*_co2` [kg, nur diesel], `*_nox` [g], `*_pm_exhaust` [g], `*_pm10_nonexhaust` [g], `*_energy_final` [MJ].
- **EV-Reichweiten-Sweep (Rev. B):** `ev_range_max_km_<label>`, `ev_range_p95_km_<label>` [km] wie bisher, aber die Überschreitungsanteile **je Schwelle**: `ev_range_exceed_<fleet>_150`, `_200`, `_250` [share]. Statt eines Pass/Fail-Werts also eine Kurve über drei Stützstellen.
- **Zusätzliche Transparenz-Rows:** `segment_km_share_n1_ii`, `segment_km_share_n1_iii` [share] — km-Anteil je Segment über alle abgedeckten Flotten. Grund: der Fahrzeugmix ist jsprit-Ergebnis und schwankt zwischen Läufen erheblich (base10c 93 % `size_s`; `localdepots_stagger` 100 % `size_m`). Ohne diese Rows ist im Paper nicht rekonstruierbar, wie viel eines CO₂-Deltas aus dem Mix statt aus der Fahrleistung kommt — genau die Verwechslung, die die Segmentdifferenzierung überhaupt nötig macht.

- [x] **Step 1: Failing Tests anhängen**

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
```

- [x] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: FAIL — `extract` nicht definiert

- [x] **Step 3: Implementieren** (an `extract_emissions.py` anhängen):

```python
SRC = ("EMEP/EEA GB 2023 - Update 2025, App.4 Tier-3 (Okt 2025, "
       "COPERT 5.9.1), LCV Euro 7 DPF+SCR, segment per vehicle type")
EV_THRESHOLDS = ("low", "mid", "high")     # -> sup["ev_range_km_<key>"]

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
    """EV range SWEEP (Rev. B): per DRT vehicle-day / per freight tour km
    against three thresholds instead of one gate.

    Rationale (measured 2026-07-31 across the 12 runs that have freight
    tours): at 250 km the exceedance is 0 % in EVERY run -- the longest
    tour anywhere is 183.3 km -- so a single 250 km gate reports a null
    result that looks like a check. The discriminating band is ~150 km
    (0-13.4 % depending on the run). Reporting the curve keeps the (real)
    finding "freight electrification is not range-constrained here"
    falsifiable instead of vacuous.
    """
    rows = []
    for fleet, label in (("drt", "drt"), ("freight", "freight_tour"),
                         ("freight_modular", "freight_modular")):
        kms = sorted(d["km"] for d in detail
                     if d["fleet"] == fleet and d["powertrain"] == "diesel")
        if not kms:
            continue
        src = "per-entity km vs ev_range_km_* (emep_supplement.csv)"
        rows += [row("environment", "ev_range_max_km_" + label,
                     max(kms), "km", src),
                 row("environment", "ev_range_p95_km_" + label,
                     _percentile(kms, 0.95), "km", src)]
        for key in EV_THRESHOLDS:
            thr = sup["ev_range_km_" + key]
            exceed = sum(1 for k in kms if k > thr) / len(kms)
            rows.append(row("environment",
                            "ev_range_exceed_" + fleet + "_" + str(int(thr)),
                            exceed, "share",
                            src + " [threshold=" + str(int(thr)) + " km]"))
    return rows


def _segment_share_rows(detail):
    """km share per N1 segment -- makes the (endogenous, jsprit-chosen)
    vehicle mix auditable, so a CO2 delta can be attributed to routing vs.
    to a shifted fleet mix. See the plan's Task-7 interfaces."""
    diesel = [d for d in detail if d["powertrain"] == "diesel"]
    total = sum(d["km"] for d in diesel)
    if total <= 0:
        return []
    rows = []
    for seg, suffix in (("N1-II", "n1_ii"), ("N1-III", "n1_iii")):
        km = sum(d["km"] for d in diesel if d["segment"] == seg)
        rows.append(row("environment", "segment_km_share_" + suffix,
                        km / total, "share",
                        "sum km per N1 segment / total km (diesel arm)"))
    return rows


def extract(run_dir, prefix, recon=None, veh_path=None, network_gz=None,
            freight_events=None):
    """Emission KPI rows + per-entity detail for one run.

    Three arms, each optional depending on what the run produced:
      - "freight"         conventional LMD tours (CarriersAnalysis TSV)
      - "freight_modular" 1d: freight km inside MODULAR_FREIGHT_DRIVE windows
      - "drt"             DRT vehicle km (pax; contaminated in 1d, see below)

    DRT arm only when veh_path/recon/network are supplied by build_kpis
    (they are reused, never recomputed here).

    GAP GESCHLOSSEN (2026-07-31, war: "KNOWN GAP METHODS-LOG 2.14"). Der
    Plan notierte hier, der "drt"-Arm ueberlappe im 1d-Fall die modularen
    Freight-km und `total_*` sei deshalb die einzige belastbare Groesse.
    Das war der Stand VOR der Zurechnungsentscheidung. Die steht inzwischen
    (METHODS-LOG 1.4: 1d = restfreier Regimesplit), also wird die
    Doppelzaehlung behoben statt dokumentiert: die Freight-Fenster werden
    zuerst bestimmt und dann an drt_arm als `exclude_windows` gegeben.
    Die Zeitseite trennte ohnehin schon (`drive_s` vs `freight_drive_s`,
    drt_service_time.py:411/414); nur die Distanzseite fehlte.
    """
    fac = em.load_factors()
    arms = {}
    fr = freight_arm(run_dir, fac)
    if fr is not None:
        arms["freight"] = fr
    link_len = None
    windows = {}
    if veh_path and recon is not None and network_gz is not None:
        used = {p[0] for path in veh_path.values() for p in path}
        link_len = load_link_lengths(network_gz, used)
        # Fenster VOR dem DRT-Arm bestimmen: sie sind dessen Ausschlussmenge.
        if freight_events is not None:
            windows = freight_windows(freight_events)
        arms["drt"] = drt_arm(veh_path, recon, link_len, fac,
                              exclude_windows=windows)
        if windows:
            arms["freight_modular"] = modular_freight_arm(
                veh_path, windows, link_len, fac)

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
    rows += _segment_share_rows(detail)
    return rows, detail


def write_detail(detail, meta, path):
    """kpi_emissions_vehicles.csv -- one row per entity x powertrain,
    ';'-separated like the other kpi_* CSVs."""
    header = ["run_id", "fleet", "entity", "vehicle_type", "segment", "km",
              "v_kmh", "powertrain"] + list(EMIS_KEYS)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(header)
        for d in detail:
            w.writerow([meta.run_id] + [d[k] for k in header[1:]])
```

- [x] **Step 4: PASS bestätigt** — 29/29, plus Realdaten-Durchlauf über beide Armkonstellationen.

  **A) Konventioneller Baseline-Lauf** (`bandz_central`, nur Freight-Arm): 1243,8 kg CO₂e-WTW / 435,4 kg BEV; NOx 542,6 g; Abrieb-PM10 316,6 g; 13.128 MJ; Segmentanteile 92,6 % N1-II / 7,4 % N1-III; 31 KPI-Rows, 126 Detailzeilen.

  **B) 1d-Lauf** (`m1d050`, DRT + modularer Freight): `drt_co2e_wtw` 12.706,058 + `freight_modular_co2e_wtw` 3,520 = `total_co2e_wtw` **12.709,578 kg — auf die Stelle restfrei**, der Regimesplit hält also auch durch die KPI-Schicht.

  **Der EV-Sweep trennt scharf, und in die erwartete Richtung** — der Rev.-B-Umbau war berechtigt: längste Freight-Tour 158,8 km, p95 139,4 km, Überschreitung **3,2 % bei 150 km** und **0 % bei 250 km**. Ein Einzelgate bei 250 km hätte hier genau nichts gezeigt.

  **ACHTUNG, asymmetrischer Befund — nicht überinterpretieren:** auf der DRT-Seite liegt die Überschreitung bei 150 km bei **95,8 %** (längster Fahrzeugtag 527 km, Mittel ~393 km/Fahrzeug). Das ist **kein** „DRT ist nicht elektrifizierbar": ein Freight-*Tour* ist eine zusammenhängende Schicht, ein DRT-*Fahrzeugtag* enthält lange `STAY`-Phasen, in denen geladen werden könnte. Die beiden Zahlen sind also **nicht dieselbe Größe** und dürfen nicht nebeneinander als Vergleich stehen. Sauber wäre eine Ladefenster-Analyse (längster Fahrblock zwischen zwei ausreichend langen STAY-Phasen) — **nicht in diesem Plan**, gehört als eigener Punkt in den Backlog. Bis dahin: die DRT-Reichweiten-Rows nur als *Fahrleistung je Fahrzeugtag* interpretieren, nicht als Reichweitenaussage.

- [x] **Step 5: Commit**

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

- [x] **Step 1: `common.py` erweitern** — bereits in Task 5c erledigt (die `row()`-Assertion in `common.py` erzwang die Gruppe, sobald die ersten `environment`-Zeilen entstanden). Kein Nachtrag nötig.

- [x] **Step 2: `render.py` Gruppenliste ergänzen — ENTFÄLLT.** Der Plan beschreibt einen Stand, den es nicht mehr gibt: `render.py` zählt die Gruppen nicht mehr in einem Literal auf, sondern **leitet sie aus `common.KPI_GROUPS` ab** (`table_groups()`, Z. 327). Genau dieser Umbau war die Reaktion darauf, dass die hardcodierte Liste einmal die komplette `modular`-Gruppe verschluckt hatte. `environment` erscheint damit ohne jede Änderung in der Tabelle, und der bestehende Test `test_table_groups_cover_every_canonical_kpi_group` beweist die Eigenschaft (nicht nur den Einzelfall). Verifiziert zusätzlich end-to-end: `assert "freight_co2e_wtw" in html`.

**ABWEICHUNGEN in Step 3 (2026-07-31, alle aus den Realdaten-Smokes von Step 5 heraus):**

1. **Reihenfolge:** der Block sitzt **nach** `pax_only.apply_overrides(rows)`, nicht davor. Grund: die 1c-Zurechnungszeilen brauchen die Nenner `drt_rides`/`parcels_delivered`, und `drt_rides` ist erst *nach* dem Override der paketfreie Wert. Davor wäre die Intensität je Pax mit der kontaminierten Fahrtenzahl gerechnet worden.
2. **Einmal-Rekonstruktion statt zweimal:** `build_kpis` ruft jetzt `reconstruct_drt_paths_detailed()` (4-Tupel) und projiziert mit dem neuen `geometry.project_paths()` auf die 2-Tupel für Karten/`veh_km`/`occ_km`. Der Extractor braucht Zeitstempel (1d-Fensterschnitt) und den Paket/Pax-Split (1c-Zurechnung); ein zweiter Durchlauf über den ~95-MB-Cache wäre reine Verschwendung. Die Projektionsregel liegt in **einer** Funktion, weil sie nun zwei Aufrufer hat.
3. **`freight_events=` → `drt_task_events=`, und der Plan-Snippet war falsch.** Der Snippet übergibt `*.freight_events_filtered.txt`. Der ist auf **jedem** 1d-Lauf 0 Byte groß; die `MODULAR_FREIGHT_DRIVE`-Tasks sind DVRP-Tasks auf `drt_*`-Fahrzeugen und liegen im **DRT**-Cache. Der Fehler wirft nichts: die `freight_modular_*`-Zeilen verschwinden lautlos und ihre km werden als Pax-km verbucht. Gemessen auf `m1d050`: `drt_co2e_wtw` 12708,7 statt 12706,1 + 2,6 Fracht. Derselbe Datei-Verwechsler war schon in Task 5b passiert — der Parameter heißt deshalb jetzt nach der Datei, die er will, und ein e2e-Test (`test_modular_emissions_split_freight_from_pax_km_without_residue`) prüft den **km-Split**, nicht die Zeilenpräsenz. Mutationsprobe: mit dem Freight-Cache verdrahtet schlägt er mit `KeyError: 'freight_modular'` fehl.
4. **`emissions_skipped`-Meta-Zeile** statt nur `print` (die Rev.-B-Anmerkung unten verlangte den Exception-Typ; das reicht nicht). Ein `print` scrollt weg, und die CSV eines Laufs ohne Umwelt-KPIs sähe aus wie die eines Laufs, für den es keine gibt. Die Zeile trägt `type(e).__name__ + ": " + str(e)` und erscheint damit auch im „Hinweise"-Block des Dashboards — dieselbe Konvention wie `run_meta_degraded`.

- [x] **Step 3: `build_kpis.py` umbauen.** Der Geometrie-Block (`veh_path`/`link_geo`/`veh_km`/`occ_km_shares`, aktuell NACH `kpi_writer.write_long`) wird VOR die `rows`-Finalisierung gezogen, damit die Emissions-Rows in `kpis_long/wide.csv` landen. Konkret: den kompletten Block ab `import geometry` bis `elif drt_cache is not None: print(...)` unverändert nach oben verschieben — direkt VOR `pax_only.apply_overrides(rows)`. Danach (immer noch vor `pax_only.apply_overrides`) einfügen:

```python
    # Emissions (EMEP/EEA Tier-3, group "environment") -- reuses recon +
    # veh_path from above; freight arm reads its own TSV. Post-processing
    # only, both powertrain arms. Graceful: any failure skips emissions
    # rather than killing the KPI build.
    import extract_emissions
    emis_detail = None
    try:
        fr_ev = run_dir / (meta.prefix + ".freight_events_filtered.txt")
        emis_rows, emis_detail = extract_emissions.extract(
            run_dir, meta.prefix, recon=recon, veh_path=veh_path,
            network_gz=network if network.exists() else None,
            freight_events=fr_ev if fr_ev.exists() else None)
        rows += emis_rows
    except Exception as e:
        print("[build] emissions skipped: " + str(e))  # ASCII only
```

**Rev.-B-Anmerkung zum `except Exception`:** dieser Catch-all darf **nicht** den `ValueError` aus `segment_for_type()` verschlucken — ein unbekannter Fahrzeugtyp würde dann als „emissions skipped" durchrutschen und der Run hätte stillschweigend keine Umwelt-KPIs. Der `print` muss deshalb den Typ der Exception mitschreiben (`type(e).__name__`), und Step 5 unten prüft explizit, dass `environment`-Zeilen **vorhanden** sind, nicht nur dass der Build durchläuft.

und nach dem `kpi_writer.write_wide(...)`-Aufruf:

```python
    if emis_detail:
        extract_emissions.write_detail(emis_detail, meta,
                                       out / "kpi_emissions_vehicles.csv")
```

Achtung Reihenfolge-Detail: `network = run_dir / (meta.prefix + ".output_network.xml.gz")` zieht mit dem Geometrie-Block nach oben; die spätere Verwendung durch `maps` (Task-6-Kommentar im Code) bleibt funktionsfähig, weil `veh_path`/`link_geo` weiterhin vor dem maps-Abschnitt existieren. `distributions.extract(...)` (konsumiert `veh_km`/`occ_km_shares`) bleibt an seiner Stelle — die Variablen existieren nach dem Vorziehen früher, das ist unschädlich.

- [x] **Step 4: Volle Test-Suite laufen lassen** — **356 passed** (347 vor Task 8, +9 neue: 3x `build_kpis` inkl. Fehlschlag-Meta-Zeile, 1x `geometry.project_paths`-Identitaet, 1x 1d-e2e-km-Split, 4x `extract_emissions` fuer die drei unten gefundenen Defekte). Der Geometrie-Block ist nur verschoben, `environment` additiv.

Run: `python -u -m pytest tests/ -v` (aus `analysis/kpi/`)
Expected: PASS — insbesondere `test_build_kpis.py`, `test_render.py` und `test_real_married250.py` bleiben grün (der Geometrie-Block ist nur verschoben, nicht verändert; `environment` ist eine additive Gruppe).

- [x] **Step 5: Realdaten-Smoke — VIER Läufe statt zwei, und sie haben zwei echte Defekte gefunden**

Der Plan sah `base10c` + `m1d010` vor. Gelaufen sind vier, weil jede Armkombination einen eigenen Datenpfad trifft und drei davon lokal verfügbar waren (`base10c`s `analysis/freight/` fehlte lokal und wurde vom Sim-PC nachgeholt — die Provider-Gegenprobe meldet keine unmatched Carrier, die TSVs passen also zum lokalen Lauf):

| Lauf | Arme | `total_co2e_wtw` [kg] | Rest | `segment_km_share_n1_ii` |
|---|---|---|---|---|
| `bandz_central` | freight | 1243,76 (BEV 435,44) | — | 0,925937 |
| `base10c` | freight + drt | 14163,9 | nur CSV-Rundung | 0,925937 |
| `m1d050` | drt + freight_modular | 12709,6 (12706,1 + 3,52) | nur CSV-Rundung | *(abwesend)* |
| `chid600w21` | drt (1c) | 12726,7 | — | *(abwesend)* |

Gegenprobe zu den Task-7-Erwartungen, jetzt durch die KPI-Schicht statt per Direktaufruf: `bandz_central` reproduziert **jede** Zahl (1243,761 / 435,435 / 542,570 g NOx / 316,610 g PM10 / 13128,405 MJ, 126 Detailzeilen = 63 Fahrzeuge x 2 Antriebe). `base10c` liefert die im Plan erwarteten 63 Touren / **6252,1 km** / 0,926 — identisch zu `bandz_central`, weil beide denselben deterministischen jsprit-Plan fahren. `m1d050`: `ev_range_max_km_freight_modular` = **13,4946 km**, exakt die in Task 5b validierten 13,5 km. `chid600w21`: **20,20 g CO2e/Paket** (Masse) bei Massenanteil **0,90 %** vs. Slotanteil **23,64 %** — die Zahlen aus METHODS-LOG 2.26, jetzt aus dem regulären Build.

**Defekt 1 — falscher Event-Cache (siehe Abweichung 3 oben).** Ohne den `m1d050`-Smoke wäre der 1d-Arm dauerhaft leer geblieben, ohne eine einzige Fehlermeldung.

**Defekt 2 — `segment_km_share_*` war über ALLE Flotten gerechnet und damit unbrauchbar.** Auf `base10c` wiegt der DRT-Arm die Vans 47953 : 6252 km aus, also kam `n1_ii` = **0,107** heraus — für denselben LMD-Plan, der auf `bandz_central` **0,926** liest. Zwei unvergleichbare Zahlen für einen identischen Fahrzeugmix. Ursache: DRT und `freight_modular` tragen beide die feste Ersetzung `DRT_SEGMENT = N1-III`, ihr Anteil ist konstruktionsbedingt 1,0 und damit keine Aussage. Der Anteil wird jetzt nur über die konventionelle Van-Flotte gebildet (`_MIX_FLEETS = ("freight",)`) und **fehlt** auf Pax-only- und 1d-Läufen, statt dort eine Scheinaussage zu emittieren. Damit ist der KPI wieder das, wofür er gedacht war: Mixverschiebung von Fahrleistungsänderung trennen.

**Zwei weitere Korrekturen aus denselben Läufen (keine Bugs, aber irreführende Ausgaben):**

- **`ev_range_exceed_drt_*` trug dieselbe Quellenangabe wie die Freight-Zeilen.** Auf `base10c` steht 3,2 % (Freight, je **Tour**) neben 96,7 % (DRT, je **Fahrzeugtag**) — wer das nebeneinander liest, schließt „DRT ist nicht elektrifizierbar". Eine Tour ist eine zusammenhängende Schicht, ein Fahrzeugtag enthält lange STAY-Phasen, in denen geladen werden kann. Jede Flotte hat jetzt ihre eigene Provenance (`_RANGE_SRC`), die sagt, was **eine** Einheit ist und was der Anteil nicht heißt. Der Vorbehalt muss in der Zeile stehen: wer `kpis_long.csv` liest, sieht kein Dokument. Die Ladefenster-Analyse geht in den Backlog (Task 9).
- **Zurechnungszeilen ohne Paket-kg·km-Basis.** Im 1d-Arm fahren Pakete als Kapsel, nicht als Paket-*Personen*, also ist `occ_parcels` überall 0 und `alloc_share_parcels_mass` wäre 0 — die Behauptung, die Fracht dieses Laufs sei emissionsfrei. Ohne Basis entsteht jetzt keine Zeile; `_served_quantities` gated zusätzlich auf `has_shareduse_stats`, sodass Aufrufer- und Datenseite unabhängig prüfen.

- [x] **Step 6: Commit** — `87523e1`. Abweichend vom Snippet: `common.py` war schon in Task 5c committet und `render.py` blieb unberührt (Step 2 entfiel); dafür kamen `extract_emissions.py`, `geometry.py` und vier Test-Dateien dazu.

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

- [x] **Step 1: Kaltstart quantitativ bounden — ERGEBNIS: die Regel greift, ≥ 5 %.**

Gerechnet, nicht geschätzt: EMEP/EEA Gl. (10) in der Euro-6+-Fassung (β Tab. 3-39 · bc Tab. 3-46 · Q aus `COLD_EMISSIONS_PARAMETERS`, Euro 7 Diesel LCV, N1-II == N1-III), ein Kaltstart je Tour bzw. Fahrzeugtag. Auf `base10c`: **Fracht NOx +5,63 %** (ta = 10 °C; +6,62 % bei 0 °C), Fracht CO +13,94 %, Fracht VOC +3,61 %, **Fracht Energie/CO₂ +0,93 %**, DRT NOx +1,41 %, DRT Energie/CO₂ +0,23 %.

**Ein Transfer musste ausgewiesen werden, und er ist der einzige Freiheitsgrad:** β ist ein Anteil an der Gesamtfahrleistung, kalibriert für `ltrip` ∈ [8, 15] km. Unsere Touren sind ~99 km lang — dort wird die Formel **negativ**, ist also nicht auswertbar. Übertragbar ist die Kaltdistanz je Start β(ltrip)·ltrip, stabil über das gültige Band (3,02 / 3,50 / 3,39 km bei ltrip 8 / 12,4 / 15 km). Die Endzahl hängt daran nur schwach (NOx 5,99 / 5,63 / 4,71 %), die Aussage ist also kein ltrip-Artefakt. Das gehört ins Paper, nicht in eine Fußnote.

**Zwei Punkte, die der Plan nicht vorgesehen hatte:**
- **PM-Auspuff hat für Euro 7 überhaupt keine Kaltstart-Parametrisierung** im Sheet (Euro 5+ nutzt laut Kapitel eine eigene Gleichung mit absolutem Kaltfaktor). Für unsere Bilanz irrelevant — 0,89 g Auspuff-PM gegen 316,6 g Abrieb —, aber es ist eine Lücke und keine Null.
- **Die DRT-Zahl ist „je Kaltstart" und skaliert linear.** Ein Fahrzeugtag enthält lange STAY-Phasen; ob der Motor darin auskühlt, ist ohne Thermomodell nicht entscheidbar. Bei 5 Starts läge DRT bei ~7 % NOx, also auf Frachtniveau. Als Empfindlichkeit berichtet, nicht als Befund.

**Entscheidung nach der Plan-Regel:** ≥ 5 % für NOx → Backlog-Punkt „Kaltstart-Zuschlag implementieren" (`[H]`, ~0,5 d) angelegt. Bis dahin sind alle NOx-Zahlen des Papers eine **Untergrenze**; die Abweichung ist einseitig und trifft den BEV-Arm nicht (dessen Vorteil wäre größer). CO₂/Energie liegen unter dem jsprit-Rauschboden, die Kernaussagen hängen nicht daran. Vollständige Herleitung: METHODS-LOG §2.29, Rechenrezept: `analysis/kpi/data/README.md`.

<details><summary>Ursprüngliche Step-1-Formulierung</summary>

**Step 1: Kaltstart quantitativ bounden.** Aus dem Appendix-4-Sheet `COLD_EMISSIONS_PARAMETERS` (bzw. Guidebook-Kapitel Tier-2-Kaltstart) für Diesel-LCV die Kaltstart-Mehremission je Start abschätzen (Ansatz: `beta`-Anteil kalter Distanz ~ erste 5–10 km bei ~10 °C, je 1 Start pro Tour/Fahrzeug-Tag) und gegen die Task-8-Smoke-Zahlen je Schadstoff ins Verhältnis setzen. **Entscheidungsregel:** Anteil < 5 % für alle berichteten Schadstoffe → dokumentierte Limitation, fertig; Anteil ≥ 5 % (realistisch am ehesten NOx) → neuen Backlog-Punkt „Kaltstart-Zuschlag implementieren" unter dem Nachhaltigkeits-`[H]` anlegen (Tier-2-Zuschlag pro Tour, ~0,5 d).

</details>

- [x] **Step 2: Limitations-Abschnitt in `data/README.md` angehängt** — plus ein eigener Abschnitt „Kaltstart" mit Formeln, Tabelle und ltrip-Sensitivität (der Plan sah nur eine Zeile mit `<X>`/`<Y>` vor; eine Zahl ohne ihr Rechenrezept ist im Paper nicht verteidigbar). Gegenüber der Vorlage zusätzlich aufgenommen: der Geltungsbereich von `segment_km_share_*` (nur konventionelle Van-Flotte, siehe Task-8-Defekt 2), die Nicht-Vergleichbarkeit der DRT- und Freight-Reichweitenzeilen, die Restfreiheit des 1d-Regimesplits (`drt_* + freight_modular_* == total_*`, exakt), und der Gate auf die Paket-kg·km-Basis der 1c-Zurechnung. Der Block unten ist die Vorlage, nicht der Endstand — maßgeblich ist `data/README.md`.

<details><summary>Vorlage Step 2</summary>


```markdown
## Limitations (Paper-Rohtext)
- ZULADUNG ist nicht modelliert, und das ist methodenkonform: EMEP/EEA
  loest Last nur fuer schwere Nutzfahrzeuge auf (Default-Lastfaktor 50 %,
  Kap. 1.A.3.b.i-iv S. 62 f.); fuer LCV ist Last kein Methodenparameter
  und kein Referenz-Ladezustand dokumentiert. Der Fahrzeugmasse-Effekt
  wird ueber die Segmentklasse N1-II/N1-III abgebildet -- dort legt EMEP
  ihn hin. BOUND des nicht modellierten Lasteffekts, aus der HDV-
  Parametrisierung (Rigid <=7,5 t, 30 km/h, 0 % Steigung: 4,387 leer ->
  4,954 MJ/km voll): <=13 % fuer einen 7,5-Tonner, ~5 % fuer unsere Vans
  bei ~575 kg Zuladung auf ~2100 kg Leergewicht. Kleiner als der
  Segmenteffekt (43 %).
- Bezugsmasse je Fahrzeugtyp ist eine ausgewiesene Annahme (~1700 /
  ~2000 / ~2400 kg fuer size_s/m/l); die N1-Segmentgrenzen (<=1305 /
  <=1760 / >1760 kg) stammen aus der EU-Typgenehmigung, NICHT aus dem
  Guidebook-Kapitel.
- Kategoriensubstitution M2 -> N1-III fuer die DRT-Flotte (capacity 10,
  also M2 nach Guidebook Tab. 2-1). Einordnung bei 30 km/h: PC
  Large-SUV-Exec 2,545 / N1-III 3,123 / Buses Urban Midi <=15 t bei
  Default-Last 9,1 MJ/km -- N1-III ist die technische Entsprechung.
- Fahrzeugmix ist ENDOGEN (jsprit waehlt bei FleetSize.INFINITE frei aus
  den angebotenen Van-Typen, LmdCarrierBuilder). Er schwankt erheblich
  zwischen Laeufen (base10c 92,6 % der km auf size_s; localdepots_stagger
  100 % size_m). CO2-Deltas zwischen Szenarien sind daher immer zusammen
  mit segment_km_share_* zu lesen -- sonst ist Mixverschiebung nicht von
  Fahrleistungsaenderung zu trennen. Nebenwirkung: die Kosten von
  ct_cep_size_s sind selbst interpoliert (lmd-vehicle-types.xml), sie
  beeinflussen die Fahrzeugwahl und damit indirekt das CO2-Ergebnis.
- Tier-3-Kurven auf Trip-/Tour-Mittelgeschwindigkeit angewandt (COPERT-
  Intention), nicht auf Link-Ebene; Stop&Go-Differenzierung unterhalb der
  Kurvenaufloesung entfaellt (laendlicher Raum: unkritisch fuer Deltas).
- Kaltstart nicht modelliert: Bound = 5,6 % (NOx), 0,9 % (CO2) je Tour
  (COLD_EMISSIONS_PARAMETERS, 1 Start/Tour, 10 C) -- siehe Step-1-Rechnung.
  [Platzhalter nachtraeglich gefuellt, damit hier keine offene Stelle
  stehenbleibt; maßgeblich bleibt data/README.md.]
- Idle an Servicestopps: Engine-off-Annahme (Auslieferung/Boarding).
- Euro-7-Faktoren aus Grenzwerten projiziert (Norm ab ~2026/27).
- km-Kanal traegt jsprit-Heuristik-Rauschen (~6.5 % Boden, Seed-Messung
  2026-07-28) -> Paper-Zahlen als Mittel + Min/Max ueber >=10 Runs. Der
  Lasteffekt-Bound (~5 %) liegt UNTER diesem Rauschboden.
- 1d-Zurechnung: `drt_vehicle_km` traegt keinen Freight/Pax-Kanal
  (METHODS-LOG 2.14, "not corrected"). Der modulare Arm liefert einen
  regimebasierten Split ueber die MODULAR_FREIGHT_DRIVE-Fenster; die
  Aufteilung der Emissionen eines gemeinsam genutzten Fahrzeugs auf
  Freight und Pax bleibt eine Buchungsentscheidung. `total_*` ist die
  allokationsfreie Groesse. Pax-Zuladung waere ohnehin ~1,2 %
  (mean_pax_aboard 1,6 -> ~128 kg), also unter dem Rauschboden.
- BEV-Arm: Elektrifizierung nur auf Emissionsebene (keine Reichweiten-/
  Ladezeitrestriktion in der Sim); Reichweite als SWEEP ueber 150/200/
  250 km, nicht als Pass/Fail. Befund ueber die 12 Laeufe mit Freight-
  Touren: laengste Tour 183 km, bei 250 km 0 % Ueberschreitung in jedem
  Lauf, bei 150 km 0-13,4 %. Freight-Elektrifizierung ist hier nicht
  reichweitenbegrenzt.
- Netzintensitaet Strom ist ein ausgewiesener Sensitivitaetsparameter
  (emep_supplement.csv). Die BEV-Abrieb-Multiplikatoren sind KEINE freie
  Annahme mehr, sondern die guidebook-eigenen ICE->BEV-Verhaeltnisse des
  Medium-Pkw (Reifen 1,0841 / Bremse 0,2113 / Strasse 1,1267) - ein
  deklarierter Kategorientransfer, weil die Quelle keine BEV-Zeile fuer
  LCV hat.
- Non-Exhaust-Abrieb IST segmentdifferenziert (Kap. 1.A.3.b.vi-vii loest
  LCV nach N1-Segment auf; Reifen/Strasse gruppieren N1-II+III, die Bremse
  trennt alle drei). Die frueher hier notierte "bewusste Asymmetrie zur
  Auspuffseite" war eine Annahme ueber ein damals nicht vorliegendes
  Kapitel und ist zurueckgezogen.
- REIFEN-PM10 IST EINE OBERGRENZE, KEINE SCHAETZUNG. Das Guidebook
  kennzeichnet die eigenen Reifenwerte als ueberschaetzt: der
  luftgetragene PM10-Anteil liege nach neueren Messungen "well below 3 %"
  statt der angesetzten 60 % (Saladin et al. 2024; Huber et al. 2024;
  Giechaskiel et al. 2024a), eine Revision der Tabelle sei zum
  Redaktionsstand nicht moeglich. Wir rechnen methodentreu mit dem
  dokumentierten Wert 0,600 UND berichten den Vorbehalt: Reifen-PM10
  14,1 mg/km (24 % des Abriebs) bei N1-III/30 km/h waere bei 3 % nur
  ~0,7 mg/km, der Gesamtabrieb fiele von 59,1 auf 45,7 mg/km (-23 %). Die
  Abweichung ist EINSEITIG (wahrer Wert darunter, nie darueber) und trifft
  Diesel und BEV gleich, die Richtungsaussagen bleiben also gueltig - der
  BEV-Vorteil wuerde sogar groesser, weil sein Nachteil genau am
  ueberschaetzten Term haengt.
- KEIN Wert dieses Kapitels hat Qualitaetscode A: Reifen- und Bremsbasen
  sind Code B, der Strassenabrieb C-D ("highly uncertain") bei 10,5 mg/km
  = 18 % des Abriebs. Das ist der Rahmen aller PM-Aussagen des Papers.
- Non-Exhaust dominiert die PM-Bilanz: Auspuff-PM eines Euro-7-Diesels mit
  DPF ist 0,142 mg/km, der Abrieb 59,1 mg/km - Faktor ~416. Und die
  Elektrifizierung halbiert ihn nicht: BEV behaelt 34,4 mg/km = 58 %, weil
  Reifen und Strasse mit der Fahrzeugmasse STEIGEN und nur die Bremse
  faellt.
```

</details>

- [x] **Step 3: Backlog aktualisiert** — im `[H]` Nachhaltigkeits-Block: Verweis auf diesen Plan ergänzen (`→ [Plan](superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md)`), die „Restliche Arbeitsschritte" als in-Plan-überführt markieren, Kaltstart-Ergebnis aus Step 1 eintragen. Offen bleiben dort: SOS-/PB-Layer (eigenes späteres Paket), Multi-Seed-Aggregation über ≥10 Runs (Reporting-Werkzeug, erst bei Paper-Auswertung), optionale Sensitivitäten (Midi-Bus für DRT, Rigid ≤7,5 t für `_l`).

**Tatsächlich eingetragen — zwei neue Punkte statt einem:**
1. `[H]` **Kaltstart-Zuschlag implementieren** (~0,5 d), wie die Regel es verlangt.
2. `[M]` **Ladefenster-Analyse für die DRT-Elektrifizierbarkeit** (~0,5–1 d). Nicht im Plan vorgesehen, aber die Alternative wäre, eine unbeantwortbare Frage als beantwortet stehen zu lassen: `ev_range_exceed_drt_*` misst Fahrzeug**tage**, nicht Schichten. Die belastbare Größe ist der längste Fahrblock zwischen zwei ausreichend langen STAY-Phasen; die DVRP-Task-Events liefern beides schon.

Außerdem ist das **EV-Reichweiten-Gate** von „zu prüfen" auf „Frachtseite beantwortet, DRT-Seite offen" umgeschrieben — mit der Begründung, warum aus dem Einzel-Gate ein Sweep wurde (das 250-km-Gate liefert in *jedem* Lauf 0 %, also ein Nullresultat, das wie eine Prüfung aussieht).

- [x] **Step 4: Commit**

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
- **Sensitivitäts-Faktorsätze** (Urban Bus Midi ≤15 t für DRT; HDT Rigid ≤7,5 t für `ct_cep_size_l`) — datenseitig vorbereitet (Tool-Filter erweitern), aber nicht verdrahtet. Falls die HDT-Variante später kommt: der Guidebook-**Default ist 50 % Last**, nicht 0 % — die Vergleichszeile ist `Load = 0.5` (4,666 MJ/km bei 30 km/h, 0 % Steigung), nicht 4,387.
- **Zuladungsbasierte Skalierung der Emissionsfaktoren** — geprüft 2026-07-31 und verworfen, siehe Global Constraints. **Nicht zu verwechseln mit Task 5c:** dort wird Masse zur *Zurechnung* einer berechneten Emission auf Fracht und Pax benutzt (eine Buchungsfrage), nicht zur *Skalierung des Emissionsfaktors* (eine Frage an die Faktorquelle, die EMEP für LCV nicht beantwortet). Die beiden dürfen im Paper nicht vermischt werden. Nicht als „später nachziehen" im Backlog führen, sondern als methodische Entscheidung im METHODS-LOG: EMEP bietet für LCV keine Lastdimension, und eine Mischung mit STREAM-Verhältnissen (wie in `src/hagrid_output_analysis/config.py`) hat keinen definierten Nullpunkt. Wer das ändern will, muss die **gesamte** Rechnung auf STREAM umstellen — dann fallen Geschwindigkeitskurve, BEV-Arm und der SPN23/CH4/N2O-Vektor weg.
- **Ladeprofil-Rekonstruktion** — entfällt mit der Zuladungsentscheidung. (Nebenbefund für den Fall, dass es je gebraucht wird: `analysis/freight/Load_perVehicle.tsv` ist in **jedem** geprüften Lauf nur Header — MATSims CarriersAnalysis schreibt die Spalte `load state during tour` nicht.)
- **Dashboard-Karten/-Kacheln für Emissionen** — die KPI-Tabellen-Ansicht (Task 8) reicht fürs Paper; hübsche Environment-Kacheln sind Dashboard-v2-Folgearbeit.
- **`hagrid_output_analysis/`** — bleibt komplett unangetastet (Kollegen-Freeze).
