# Kaltstart-Zuschlag + STAY-Phasen-Auswertung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Den Kaltstart-Zuschlag in den EMEP/EEA-Emissionskanal einrechnen und aus derselben DVRP-Task-Sequenz die DRT-Ladefensterfrage beantworten.

**Architecture:** Kaltstart-Koeffizienten kommen aus dem zweiten Sheet des Appendix-4-xlsx in eine committete CSV (gleiche Mechanik wie die Hot-Faktoren). Ein neuer Term in `emissions_emep.py` liefert den Zuschlag je Start; `extract_emissions.py` zählt die Starts — Fracht 1 je Tour, DRT/modular aus der Task-Sequenz nach der 60-min-Regel — und rechnet ihn in die bestehenden Totale ein. Dieselbe Sequenz trägt die neue Ladefenster-KPI. Reines Post-Processing, kein Sim-Rerun.

**Tech Stack:** Python 3 (pandas, openpyxl, csv), pytest; bestehende KPI-Pipeline `parcel-demand-2-matsim-pipeline/analysis/kpi/`.

**Spec:** [docs/superpowers/specs/2026-08-26-coldstart-stay-analysis-design.md](../specs/2026-08-26-coldstart-stay-analysis-design.md)

## Global Constraints

- **`src/hagrid_output_analysis/**` wird NICHT angefasst** (Kollegen-Paper-Freeze). Alles Neue lebt in `analysis/kpi/`, die eine Ausnahme ist die additive Erweiterung in `analysis/drt-headline/drt_service_time.py`.
- Windows/cp1252: **ASCII-only in allen `print()`**; Python immer `python -u`.
- KPI-Zeilen ausschliesslich via `common.row(kpi_group, kpi_name, value, unit, source)`; Gruppe exakt `"environment"`; Extractor bleibt run-agnostisch (kein `run_id` in den Rows).
- **`source`-Strings ASCII-only**, gleiche Konvention wie die bestehenden CSVs (`"2023 - Update 2025"` mit Bindestrich, kein Gedankenstrich).
- Faktor-Provenance in jeder Faktorzeile: `"EMEP/EEA air pollutant emission inventory guidebook 2023 - Update 2025, ch. 1.A.3.b.i-iv Appendix 4 (Oct 2025, COPERT 5.9.1)"`.
- Kaltstart-Definition: `"EPA (1994) via Reiter & Kockelman 2016, Transportation Research Part D 43, 123-132, doi:10.1016/j.trd.2015.12.012"`.
- Parameter (Spec E2/E4): Soak-Schwelle **60 min**, ta **10.0 °C**, `ltrip` **12.4 km**, Ladefenster-Sweep **20/40/60 min**.
- Xlsx-Quelle (gitignored, wird NICHT committet): `parcel-demand-2-matsim-pipeline/hagrid-input/emissions/1.A.3.b.i-iv Road Transport Appendix 4 Emission Factors Oct_2025.xlsx`, Sheet `COLD_EMISSIONS_PARAMETERS`.
- Tests laufen aus `parcel-demand-2-matsim-pipeline/analysis/kpi/`: `python -u -m pytest tests/<file> -v`.
- **Testbasis vor Beginn: 392 Tests** (gemessen 2026-08-26). Am Ende muss die Zahl gewachsen und die Suite grün sein.
- Git: Branch `hendrik`. Commit-Messages enden mit `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`.

### Physikalischer Kern (gilt für alle Tasks)

```
extra_je_start = cold_km * ef_hot(v) * bc(ltrip) * (Q(v, ta) - 1)

cold_km = beta(ltrip) * ltrip                        # = 3.4988 km bei ltrip=12.4, ta=10
beta    = 0.6474 - 0.02545*ltrip - (0.00974 - 0.000385*ltrip) * ta      # Tab. 3-39
bc      = a - b*ltrip  fuer CO/NOx/VOC, sonst 1.0                       # Tab. 3-46
Q       = a*v + b*ta + c, Boden 1.0, v geclampt auf [vmin, vmax]
```

**Wichtige Eigenschaft, auf der die Tests aufbauen:** der *relative* Zuschlag
`extra / hot = (cold_km / entity_km) * bc * (Q - 1)` ist **segmentunabhängig**, weil `ef_hot`
sich herauskürzt. Tests brauchen deshalb keine Segment-Fixture.

## File Structure

| Datei | Verantwortung |
|---|---|
| `analysis/kpi/emep_factor_extract.py` | **erweitert** — zweiter Sheet-Zweig `COLD_EMISSIONS_PARAMETERS` → `transform_cold()`; einmaliges xlsx→CSV-Tool |
| `analysis/kpi/data/emep_cold_factors.csv` | **neu** — committete Q-Koeffizienten, alle N1-Segmente, alle Ranges |
| `analysis/kpi/data/emep_supplement.csv` | **erweitert** — beta/bc-Koeffizienten, Soak-Schwelle, ta, ltrip, Ladefenster |
| `analysis/kpi/emissions_emep.py` | **erweitert** — `load_cold_factors()`, `cold_beta_km()`, `cold_bc()`, `cold_start_extra()` |
| `analysis/drt-headline/drt_service_time.py` | **additiv** — `per_veh[v]["task_seq"]` |
| `analysis/kpi/extract_emissions.py` | **erweitert** — Zählung, Verdrahtung in die Arme, `*_coldstart_share`-Zeilen, `drive_block_max_km_*` |
| `analysis/kpi/data/README.md` | **umgeschrieben** — Abschnitt „Kaltstart" von Bound auf Implementierung |
| `docs/METHODS-LOG.md`, `docs/BACKLOG.md`, `docs/BACKLOG-DONE.md` | Zahlen-Neustand |

---

### Task 1: Kaltstart-Faktor-Extraktion + committete CSV

**Files:**
- Modify: `analysis/kpi/emep_factor_extract.py`
- Create: `analysis/kpi/data/emep_cold_factors.csv` (vom Tool erzeugt, dann committet)
- Test: `analysis/kpi/tests/test_emissions_emep.py`

**Interfaces:**
- Produces: `transform_cold(df) -> pandas.DataFrame` mit Spalten exakt
  `powertrain,segment,pollutant,range,a,b,c,vmin,vmax,tmin,tmax,source`
- Produces: `data/emep_cold_factors.csv` — konsumiert von Task 3 `load_cold_factors()`.
- `powertrain` ist immer `"diesel"`: das Cold-Sheet führt für Euro 7 LCV **keine** BEV-Zeilen.
  Die Spalte bleibt trotzdem im Schema, damit sie zur Hot-CSV symmetrisch ist. Der BEV-Nullwert
  aus Spec E7 folgt damit aus den **Daten**, nicht aus einer Code-Regel.
- `pollutant` ∈ {`CO`,`NOx`,`VOC`,`EC`}. `PM Exhaust`, `CH4`, `SPN23` haben für Euro 7 **keine**
  Kaltstartparametrisierung — das ist Spec E8/L3 und muss sichtbar bleiben.

**Sheet-Fakten (verifiziert 2026-08-26 aus `hagrid-input/emissions/cold_head.txt` / `cold_euro7.txt`):**
Spalten `Category | Fuel | Segment | Euro Standard | Pollutant | Range | Month | Alpha | Beta | Gamma | Min Speed [km/h] | Max Speed [km/h] | Min Temperature [℃] | Max Temperature [℃]`.
**Es gibt keine `Technology`-Spalte** (anders als im Hot-Sheet). N1-II und N1-III tragen für
Kaltstart identische Werte.

- [ ] **Step 1: Failing Test schreiben** — an `tests/test_emissions_emep.py` anhängen:

```python
def _fake_cold_sheet():
    cols = ["Category", "Fuel", "Segment", "Euro Standard", "Pollutant",
            "Range", "Month", "Alpha", "Beta", "Gamma",
            "Min Speed [km/h]", "Max Speed [km/h]",
            "Min Temperature [℃]", "Max Temperature [℃]"]
    months = ["January", "February", "March"]
    rows = []
    for m in months:
        rows += [
            ["Light Commercial Vehicles", "Diesel", "N1-III", "Euro 7", "NOx",
             "RANGE 1", m, 0.0480610964982746, 0.0, 14.6608380769528, 5, 45, 0, 50],
            ["Light Commercial Vehicles", "Diesel", "N1-III", "Euro 7", "NOx",
             "RANGE 2", m, 0.151, -2.435, 14.019, 5, 45, -10, 0],
            ["Light Commercial Vehicles", "Diesel", "N1-II", "Euro 7", "EC",
             "RANGE 1", m, 0.0, -0.008, 1.34, 10, 130, -10, 30],
            # muss herausgefiltert werden: falsche Euro-Norm bzw. falsche Kategorie
            ["Light Commercial Vehicles", "Diesel", "N1-III", "Euro 5", "NOx",
             "RANGE 1", m, 9.9, 9.9, 9.9, 5, 45, 0, 50],
            ["Passenger Cars", "Petrol", "Mini", "Euro 7", "CO",
             "RANGE 1", m, 9.9, 9.9, 9.9, 5, 45, 0, 50],
        ]
    return pd.DataFrame(rows, columns=cols)


def test_transform_cold_filters_and_maps_columns():
    from emep_factor_extract import transform_cold
    out = transform_cold(_fake_cold_sheet())
    assert set(out["powertrain"]) == {"diesel"}
    assert set(out["segment"]) == {"N1-II", "N1-III"}
    assert set(out["pollutant"]) == {"NOx", "EC"}
    # Monate sind kollabiert: 3 Monate x 3 gueltige Zeilen -> 3 Zeilen
    assert len(out) == 3
    nox1 = out[(out["pollutant"] == "NOx") & (out["range"] == "RANGE 1")].iloc[0]
    assert nox1["a"] == pytest.approx(0.0480610964982746)
    assert nox1["b"] == pytest.approx(0.0)
    assert nox1["c"] == pytest.approx(14.6608380769528)
    assert nox1["vmin"] == 5 and nox1["vmax"] == 45
    assert nox1["tmin"] == 0 and nox1["tmax"] == 50
    assert "COPERT 5.9.1" in nox1["source"]
    assert "2023 - Update 2025" in nox1["source"]


def test_transform_cold_keeps_both_ranges():
    """RANGE 2 (t < 0) wird mit committet, obwohl bei ta = 10 nur RANGE 1
    ausgewertet wird -- eine spaetere Winter-Sensitivitaet soll eine
    Datenzeile sein, keine Code-Aenderung (Spec A / L4)."""
    from emep_factor_extract import transform_cold
    out = transform_cold(_fake_cold_sheet())
    assert set(out[out["pollutant"] == "NOx"]["range"]) == {"RANGE 1", "RANGE 2"}


def test_transform_cold_rejects_month_variance():
    """Die Monatsinvarianz ist der ungeprueftste Punkt des ganzen Pakets.
    Gilt sie nicht, MUSS die Extraktion abbrechen -- still Januar zu nehmen
    waere eine erfundene Zahl mit korrektem Aussehen."""
    from emep_factor_extract import transform_cold
    df = _fake_cold_sheet().copy()
    mask = ((df["Month"] == "February") & (df["Pollutant"] == "NOx")
            & (df["Range"] == "RANGE 1"))
    df.loc[mask, "Alpha"] = 0.999
    with pytest.raises(ValueError, match="month"):
        transform_cold(df)
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -k cold -v` (aus `analysis/kpi/`)
Expected: FAIL — `ImportError: cannot import name 'transform_cold'`

- [ ] **Step 3: `transform_cold` implementieren** — an `emep_factor_extract.py` anhängen:

```python
COLD_SHEET = "COLD_EMISSIONS_PARAMETERS"
COLD_POLLUTANTS = ("CO", "NOx", "VOC", "EC")
#: Fuer Euro 7 fuehrt das Sheet KEINE Kaltstartparametrisierung fuer diese
#: drei -- sie bleiben im Rechenkern sichtbar unparametrisiert statt still 0
#: (Spec E8/L3). Hier nur dokumentiert, gefiltert wird ueber COLD_POLLUTANTS.
#: NAME BEWUSST ANDERS als emissions_emep.COLD_UNPARAMETERISED: dort stehen
#: die AUSGABE-Schluessel ("PM_EXHAUST"), hier die xlsx-Schreibweisen
#: ("PM Exhaust"). Gleicher Name fuer zwei Schreibweisen waere eine Falle.
COLD_UNPARAMETERISED_XLSX = ("PM Exhaust", "CH4", "SPN23")
COLD_COLMAP = {"Segment": "segment", "Pollutant": "pollutant", "Range": "range",
               "Alpha": "a", "Beta": "b", "Gamma": "c",
               "Min Speed [km/h]": "vmin", "Max Speed [km/h]": "vmax",
               "Min Temperature [℃]": "tmin",
               "Max Temperature [℃]": "tmax"}
_COLD_KEY = ("segment", "pollutant", "range")
_COLD_VALS = ("a", "b", "c", "vmin", "vmax", "tmin", "tmax")


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
    lcv = df[(df["Category"] == "Light Commercial Vehicles")
             & (df["Fuel"] == "Diesel")
             & (df["Segment"].isin(SEGMENTS))
             & (df["Euro Standard"] == "Euro 7")
             & (df["Pollutant"].isin(COLD_POLLUTANTS))]
    if lcv.empty:
        raise ValueError("no Euro 7 Diesel LCV rows in " + COLD_SHEET)

    ren = lcv.rename(columns=COLD_COLMAP)[list(_COLD_KEY) + list(_COLD_VALS)]
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
```

- [ ] **Step 4: `main()` um den zweiten Sheet-Zweig erweitern**

Ersetze den Rumpf von `main()` in `emep_factor_extract.py`:

```python
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
```

- [ ] **Step 5: Test laufen lassen, PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS, inklusive der bestehenden Hot-Faktor-Tests (die `main()`-Änderung darf sie nicht brechen).

- [ ] **Step 6: Tool real ausführen und CSV erzeugen**

```bash
cd parcel-demand-2-matsim-pipeline/analysis/kpi
python -u emep_factor_extract.py "../../hagrid-input/emissions/1.A.3.b.i-iv Road Transport Appendix 4 Emission Factors Oct_2025.xlsx"
```

**Erwartung und Abbruchbedingung:** Läuft es durch, ist die Monatsinvarianz belegt. Bricht es mit
`month variance` ab, ist eine Spec-Annahme widerlegt — dann **nicht** heimlich einen Monat wählen,
sondern stoppen, die Varianz quantifizieren und den User fragen. Prüfe danach:

```bash
python -u -c "import pandas as pd; d=pd.read_csv('data/emep_cold_factors.csv'); print(d.to_string())"
```

Erwartete Zeilen: `NOx`/`CO`/`VOC` je RANGE 1 + RANGE 2 und `EC` RANGE 1, für die Segmente, die das
Sheet führt. Verifiziere gegen `hagrid-input/emissions/cold_euro7.txt`:
NOx RANGE 1 `a=0.0480610964982746, b=0, c=14.6608380769528, v[5,45], t[0,50]`.

- [ ] **Step 7: Commit**

```bash
git add analysis/kpi/emep_factor_extract.py analysis/kpi/data/emep_cold_factors.csv analysis/kpi/tests/test_emissions_emep.py
git commit -m "feat(emissions): extract EMEP cold-start coefficients into a committed CSV

Zweiter Sheet-Zweig fuer COLD_EMISSIONS_PARAMETERS. Die Monatsinvarianz
des Sheets wird asserted statt angenommen -- still Januar zu nehmen waere
eine erfundene Zahl mit korrektem Aussehen.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Supplement-Konstanten

**Files:**
- Modify: `analysis/kpi/data/emep_supplement.csv`
- Test: `analysis/kpi/tests/test_emissions_emep.py`

**Interfaces:**
- Produces: neue Keys in `fac["sup"]` (geladen vom bestehenden `load_factors()`, das die
  Supplement-CSV bereits als `{name: float(value)}` liest — **keine Loader-Änderung nötig**).

- [ ] **Step 1: Failing Test anhängen**

```python
def test_supplement_carries_coldstart_constants():
    from emissions_emep import load_factors
    sup = load_factors()["sup"]
    assert sup["coldstart_soak_min"] == 60.0
    assert sup["ambient_temp_c"] == 10.0
    assert sup["ltrip_km"] == 12.4
    for k in ("cold_beta_a0", "cold_beta_a1", "cold_beta_b0", "cold_beta_b1",
              "cold_bc_co_a", "cold_bc_co_b", "cold_bc_nox_a", "cold_bc_nox_b",
              "cold_bc_voc_a", "cold_bc_voc_b"):
        assert k in sup, k
    assert (sup["charge_window_min_low"], sup["charge_window_min_mid"],
            sup["charge_window_min_high"]) == (20.0, 40.0, 60.0)
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -k supplement -v`
Expected: FAIL — `KeyError: 'coldstart_soak_min'`

- [ ] **Step 3: Zeilen an `data/emep_supplement.csv` anhängen**

ASCII-only in der `source`-Spalte, Kommas innerhalb der Quelle erzwingen die Anführungszeichen:

```csv
coldstart_soak_min,60.0,min,"NOT a guidebook value: EPA (1994) defines a cold start as any start at least one hour after the end of the preceding trip, for catalyst-equipped vehicles. Our fleet is Euro 7 Diesel with DPF+SCR. Cited via Reiter & Kockelman 2016, Transportation Research Part D 43, 123-132, doi:10.1016/j.trd.2015.12.012"
ambient_temp_c,10.0,degC,"SETTING (user 2026-08-26): close to the German annual mean. Only RANGE 1 (t > 0) of emep_cold_factors.csv is valid at this value; a winter evaluation needs RANGE 2"
ltrip_km,12.4,km,"EMEP/EEA GB ch. 1.A.3.b.i-iv default trip length, middle of the calibrated band [8, 15] km. Only the COLD DISTANCE beta*ltrip is transferred, not beta itself -- our tours are ~99 km, where the beta formula is not evaluable (it turns negative). Cold distance over the valid band: 3.02 / 3.50 / 3.39 km at ltrip 8 / 12.4 / 15"
cold_beta_a0,0.6474,dimensionless,"EMEP/EEA GB ch. 1.A.3.b.i-iv Tab. 3-39, beta = a0 - a1*ltrip - (b0 - b1*ltrip)*ta"
cold_beta_a1,0.02545,1/km,"EMEP/EEA GB ch. 1.A.3.b.i-iv Tab. 3-39"
cold_beta_b0,0.00974,1/degC,"EMEP/EEA GB ch. 1.A.3.b.i-iv Tab. 3-39"
cold_beta_b1,0.000385,1/(km*degC),"EMEP/EEA GB ch. 1.A.3.b.i-iv Tab. 3-39"
cold_bc_co_a,0.2022,dimensionless,"EMEP/EEA GB ch. 1.A.3.b.i-iv Tab. 3-46, bc = a - b*ltrip (Euro 6+ beta reduction factor)"
cold_bc_co_b,0.0064,1/km,"EMEP/EEA GB ch. 1.A.3.b.i-iv Tab. 3-46"
cold_bc_nox_a,0.1719,dimensionless,"EMEP/EEA GB ch. 1.A.3.b.i-iv Tab. 3-46"
cold_bc_nox_b,0.0055,1/km,"EMEP/EEA GB ch. 1.A.3.b.i-iv Tab. 3-46"
cold_bc_voc_a,0.2398,dimensionless,"EMEP/EEA GB ch. 1.A.3.b.i-iv Tab. 3-46"
cold_bc_voc_b,0.0076,1/km,"EMEP/EEA GB ch. 1.A.3.b.i-iv Tab. 3-46"
charge_window_min_low,20.0,min,"SETTING (user 2026-08-26): a DRT vehicle can be re-dispatched at any moment, so a long STAY cannot be relied upon operationally. The SHORT windows are the ones there is any guarantee of -- hence a sweep, not the 60 min of the cold-start rule"
charge_window_min_mid,40.0,min,"SETTING (user 2026-08-26), see charge_window_min_low"
charge_window_min_high,60.0,min,"SETTING (user 2026-08-26), see charge_window_min_low. Numerically equal to coldstart_soak_min but justified differently -- do not merge the two constants"
```

- [ ] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add analysis/kpi/data/emep_supplement.csv analysis/kpi/tests/test_emissions_emep.py
git commit -m "feat(emissions): add cold-start and charge-window constants to the supplement

Soak-Schwelle 60 min ist belegt (EPA 1994 via Reiter & Kockelman 2016),
nicht gesetzt. Die Ladefenster-Schwellen sind bewusst eine EIGENE Groesse
-- 60 min faellt dort nur numerisch mit der Soak-Schwelle zusammen.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Rechenkern `cold_start_extra()`

**Files:**
- Modify: `analysis/kpi/emissions_emep.py`
- Test: `analysis/kpi/tests/test_emissions_emep.py`

**Interfaces:**
- Consumes: `data/emep_cold_factors.csv` (Task 1), Supplement-Keys (Task 2).
- Produces:
  - `load_cold_factors(data_dir=None) -> {segment: {pollutant: coef}}`, `coef` mit Keys
    `a,b,c,vmin,vmax,tmin,tmax`. Wird von `load_factors()` als `fac["cold"]` mitgeladen.
  - `cold_km(sup) -> float` — Kaltdistanz je Start.
  - `cold_start_extra(n_starts, km, v_kmh, powertrain, segment, fac) -> dict`
    mit **denselben Keys wie `vehicle_emissions()`**, als additiver Zuschlag. Konsumiert von
    Task 5.
  - `COLD_UNPARAMETERISED = ("PM_EXHAUST", "CH4", "SPN23")` — konsumiert von Task 7 für die
    Quellenstrings.

- [ ] **Step 1: Failing Tests anhängen** — die Erwartungswerte stammen aus
  `data/README.md` / METHODS-LOG §2.29, gerechnet am **2026-07-31**, also **vor** diesem Code.
  Das ist der Punkt: kein selbstgebauter Erwartungswert.

```python
#: Effektive mittlere Geschwindigkeiten der Bound-Rechnung, zurueckgerechnet
#: aus den dokumentierten Prozentsaetzen (2026-08-26). EIN v trifft NOx, CO
#: und VOC gleichzeitig auf < 0.02 pp, obwohl deren Q-Steigungen +0.048,
#: +0.161 und -0.286 sind -- das kann kein Zufall sein und belegt, dass es
#: dieselbe Formel ist.
BOUND_V_FREIGHT = 36.39
BOUND_V_DRT = 38.87
BOUND_KM_FREIGHT = 6252.1 / 63       # base10c: 63 Touren, 6252.1 km
BOUND_KM_DRT = 47953.0 / 120         # base10c: 120 Fahrzeugtage, 47953 km


def test_cold_km_matches_documented_transfer():
    """data/README.md: Kaltdistanz je Start = 3.50 km bei ltrip 12.4, ta 10."""
    from emissions_emep import load_factors, cold_km
    assert cold_km(load_factors()["sup"]) == pytest.approx(3.50, abs=0.01)


@pytest.mark.parametrize("pollutant,key,expected_pct", [
    ("NOx", "NOx", 5.63), ("CO", "CO", 13.94),
    ("VOC", "VOC", 3.61), ("EC", "ENERGY_MJ", 0.93)])
def test_freight_surcharge_reproduces_documented_bound(pollutant, key, expected_pct):
    """ANKER: reproduziert die unabhaengig gerechnete Bound aus METHODS-LOG
    2.29 (2026-07-31) bei einem Kaltstart je Tour, ta = 10 C.

    Der relative Zuschlag ist segmentunabhaengig -- ef_hot kuerzt sich
    zwischen Zaehler und Nenner -- deshalb genuegt ein beliebiges Segment.
    """
    from emissions_emep import load_factors, vehicle_emissions, cold_start_extra
    fac = load_factors()
    km, v = BOUND_KM_FREIGHT, BOUND_V_FREIGHT
    hot = vehicle_emissions(km, v, "diesel", "N1-III", fac)
    extra = cold_start_extra(1, km, v, "diesel", "N1-III", fac)
    assert 100.0 * extra[key] / hot[key] == pytest.approx(expected_pct, abs=0.05)


@pytest.mark.parametrize("key,expected_pct", [("NOx", 1.41), ("ENERGY_MJ", 0.23)])
def test_drt_surcharge_reproduces_documented_bound(key, expected_pct):
    from emissions_emep import load_factors, vehicle_emissions, cold_start_extra
    fac = load_factors()
    km, v = BOUND_KM_DRT, BOUND_V_DRT
    hot = vehicle_emissions(km, v, "diesel", "N1-III", fac)
    extra = cold_start_extra(1, km, v, "diesel", "N1-III", fac)
    assert 100.0 * extra[key] / hot[key] == pytest.approx(expected_pct, abs=0.05)


def test_surcharge_scales_linearly_with_starts():
    from emissions_emep import load_factors, cold_start_extra
    fac = load_factors()
    one = cold_start_extra(1, 100.0, 35.0, "diesel", "N1-III", fac)
    five = cold_start_extra(5, 100.0, 35.0, "diesel", "N1-III", fac)
    assert five["NOx"] == pytest.approx(5.0 * one["NOx"])


def test_speed_is_clamped_to_the_cold_curve_range():
    """Die Kaltkurven fuer CO/NOx/VOC gelten nur bis 45 km/h. Ohne Clamp
    extrapoliert die Formel stillschweigend -- Spec Risiko 1."""
    from emissions_emep import load_factors, cold_start_extra
    fac = load_factors()
    at_cap = cold_start_extra(1, 100.0, 45.0, "diesel", "N1-III", fac)
    above = cold_start_extra(1, 100.0, 80.0, "diesel", "N1-III", fac)
    assert above["NOx"] == pytest.approx(at_cap["NOx"])


def test_bev_has_no_cold_surcharge():
    """Spec E7/L1: EMEP fuehrt fuer BEV keine Kaltstartparametrisierung.
    Die Null kommt aus den DATEN, nicht aus einer Code-Regel."""
    from emissions_emep import load_factors, cold_start_extra
    fac = load_factors()
    extra = cold_start_extra(3, 100.0, 35.0, "bev", "N1-III", fac)
    assert all(v == 0.0 for v in extra.values())


def test_unparameterised_pollutants_stay_zero():
    """Spec E8/L3: PM Exhaust, CH4 und SPN23 haben fuer Euro 7 keine
    Kaltstartparametrisierung. Sie bleiben 0 -- als LUECKE, die Task 7 im
    Quellenstring benennt, nicht als Messwert."""
    from emissions_emep import load_factors, cold_start_extra, COLD_UNPARAMETERISED
    fac = load_factors()
    extra = cold_start_extra(1, 100.0, 35.0, "diesel", "N1-III", fac)
    for k in COLD_UNPARAMETERISED:
        assert extra[k] == 0.0, k
    assert extra["NOx"] > 0.0        # Positivkontrolle: die Null ist selektiv


def test_non_exhaust_carries_no_cold_surcharge():
    """Abrieb ist distanzbasiert und kennt keinen Kaltstart."""
    from emissions_emep import load_factors, cold_start_extra
    fac = load_factors()
    extra = cold_start_extra(1, 100.0, 35.0, "diesel", "N1-III", fac)
    assert extra["PM10_NONEXHAUST"] == 0.0
    assert extra["PM10_TYRE"] == 0.0
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -k cold -v`
Expected: FAIL — `ImportError: cannot import name 'cold_start_extra'`

- [ ] **Step 3: Implementieren** — an `emissions_emep.py` anhängen und `load_factors()` erweitern:

```python
_COLD_NUM = ("a", "b", "c", "vmin", "vmax", "tmin", "tmax")

#: Fuer Euro 7 fuehrt Appendix 4 fuer diese drei KEINE Kaltstartparametrisierung.
#: Ihr Zuschlag ist 0, und das ist eine LUECKE, keine Messung -- die
#: KPI-Quellenstrings muessen das sagen (extract_emissions._COLD_SRC).
COLD_UNPARAMETERISED = ("PM_EXHAUST", "CH4", "SPN23")

#: bc-Koeffizientennamen im Supplement, je Ausgabeschluessel.
_COLD_BC_KEYS = {"CO": "co", "NOx": "nox", "VOC": "voc"}


def load_cold_factors(data_dir=None):
    """-> {segment: {pollutant: coef}} fuer RANGE 1 (ta > 0).

    Nur RANGE 1 wird geladen: bei ambient_temp_c = 10 ist das der gueltige
    Bereich. RANGE 2/3 stehen mit in der CSV, damit eine Winter-Sensitivitaet
    eine Datenauswahl ist und keine Extraktion (Spec L4).
    """
    d = Path(data_dir) if data_dir else DATA_DIR
    cold = {}
    path = d / "emep_cold_factors.csv"
    if not path.exists():
        return cold
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["range"] != "RANGE 1":
                continue
            coef = {k: float(r[k]) for k in _COLD_NUM}
            cold.setdefault(r["segment"], {})[r["pollutant"]] = coef
    return cold


def cold_beta(sup):
    """Anteil kalt gefahrener Strecke an der GESAMTfahrleistung, Tab. 3-39."""
    lt, ta = sup["ltrip_km"], sup["ambient_temp_c"]
    return (sup["cold_beta_a0"] - sup["cold_beta_a1"] * lt
            - (sup["cold_beta_b0"] - sup["cold_beta_b1"] * lt) * ta)


def cold_km(sup):
    """Kaltdistanz je Start [km] -- die uebertragbare Groesse.

    `beta` selbst ist NICHT uebertragbar: es ist fuer ltrip in [8, 15] km
    kalibriert und wird bei unseren ~99-km-Touren negativ. Das Produkt
    beta(ltrip)*ltrip ist ueber das gueltige Band stabil (3.02 / 3.50 / 3.39
    km bei ltrip 8 / 12.4 / 15) und wird als AUSGEWIESENER Transfer benutzt
    -- siehe data/README.md.
    """
    return cold_beta(sup) * sup["ltrip_km"]


def cold_bc(pollutant_key, sup):
    """Euro-6+-Reduktionsfaktor auf den Kaltanteil, Tab. 3-46. 1.0 fuer
    Schadstoffe ohne eigene bc-Zeile (z. B. EC)."""
    k = _COLD_BC_KEYS.get(pollutant_key)
    if k is None:
        return 1.0
    return sup["cold_bc_" + k + "_a"] - sup["cold_bc_" + k + "_b"] * sup["ltrip_km"]


def cold_q(v_kmh, coef, ta):
    """Kalt/Warm-Verhaeltnis Q = a*v + b*ta + c, Boden 1.0.

    v wird auf den Gueltigkeitsbereich der KALTZEILE geclampt, nicht auf den
    der Hot-Kurve: CO/NOx/VOC sind hier nur bis 45 km/h parametrisiert,
    waehrend die Hot-Kurven bis 140 gehen. Ohne diesen eigenen Clamp
    extrapoliert die Formel stillschweigend.
    """
    v = min(max(float(v_kmh), coef["vmin"]), coef["vmax"])
    return max(1.0, coef["a"] * v + coef["b"] * ta + coef["c"])


def cold_start_extra(n_starts, km, v_kmh, powertrain, segment, fac):
    """Additiver Kaltstart-Zuschlag, gleiche Keys wie vehicle_emissions().

        extra = n * cold_km * ef_hot(v) * bc * (Q(v, ta) - 1)

    Das ist EMEP/EEA Gl. (10) in der Euro-6+-Fassung, umgestellt: beta*km
    ist die Kaltdistanz, und die wird je Start angesetzt statt als Anteil
    an der Gesamtfahrleistung (siehe cold_km()).

    BEV bekommt 0, weil das Cold-Sheet keine BEV-Zeilen fuehrt. ACHTUNG,
    das ist einseitig ZUGUNSTEN des BEV-Arms: real hat ein BEV sehr wohl
    einen Kaltverbrauch, dominiert von der Kabinenheizung. Ausgewiesene
    Limitation, siehe data/README.md.

    Abrieb (PM10_*) bekommt keinen Zuschlag -- er ist distanzbasiert.
    """
    sup = fac["sup"]
    out = {k: 0.0 for k in EXHAUST_KEYS}
    out.update({"ENERGY_MJ": 0.0, "CO2E_WTW": 0.0, "PM10_TYRE": 0.0,
                "PM10_BRAKE": 0.0, "PM10_ROAD": 0.0, "PM10_NONEXHAUST": 0.0})
    if powertrain != "diesel" or n_starts <= 0 or km <= 0:
        return out

    coefs = fac["diesel"][segment]
    cold = fac.get("cold", {}).get(segment, {})
    ta = sup["ambient_temp_c"]
    ckm = n_starts * cold_km(sup)

    for poll, key in _POLL_KEY.items():
        c = cold.get(poll)
        if c is None:                      # COLD_UNPARAMETERISED -> Luecke
            continue
        out[key] = ckm * ef(v_kmh, coefs[poll]) * cold_bc(key, sup) * (
            cold_q(v_kmh, c, ta) - 1.0)

    ec_cold = cold.get("EC")
    if ec_cold is not None:
        ec = ckm * ef(v_kmh, coefs["EC"]) * (cold_q(v_kmh, ec_cold, ta) - 1.0)
        out["ENERGY_MJ"] = ec
        out["CO2"] = ec * sup["ttw_co2_g_per_mj_diesel"]
        # N2O bekommt bewusst KEINEN Zuschlag: die Quelle gibt urban cold 9
        # gegen urban hot 11 mg/km, eine Kaltkorrektur wuerde N2O also SENKEN.
        # Wir setzen ohnehin den hoeheren Hot-Wert an -- das bleibt konservativ.
        out["CO2E_TTW"] = out["CO2"] + sup["gwp_ch4"] * out["CH4"]
        out["CO2E_WTW"] = (out["CO2E_TTW"]
                           + ec * sup["wtt_co2e_g_per_mj_diesel"])
    return out
```

Und in `load_factors()` direkt vor `return fac` einfügen:

```python
    fac["cold"] = load_cold_factors(d)
```

- [ ] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_emissions_emep.py -v`
Expected: PASS — insbesondere die vier `test_freight_surcharge_reproduces_documented_bound`-Fälle.

**Wenn der Anker NICHT trifft:** nicht die Toleranz aufweiten. Entweder ist die Formel falsch oder
die Konstanten sind es — beides ist zu finden, nicht zu übertünchen.

- [ ] **Step 5: Commit**

```bash
git add analysis/kpi/emissions_emep.py analysis/kpi/tests/test_emissions_emep.py
git commit -m "feat(emissions): cold-start surcharge in the Tier-3 core

Reproduziert die am 2026-07-31 unabhaengig gerechnete Bound (Fracht-NOx
5.63 %, CO 13.94 %, VOC 3.61 %, DRT-NOx 1.41 %) -- der Erwartungswert
stammt aus METHODS-LOG 2.29 und damit von vor diesem Code.

Eigener v-Clamp auf den Gueltigkeitsbereich der KALTZEILE (CO/NOx/VOC nur
bis 45 km/h, Hot-Kurven bis 140). BEV und die drei unparametrisierten
Schadstoffe bleiben 0, weil die QUELLE nichts hergibt.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Task-Sequenz durchreichen (`drt_service_time`)

**Files:**
- Modify: `analysis/drt-headline/drt_service_time.py:316-322` (Klassifikationsschleife) und die
  `per_veh[v]`-Konstruktion (~Z. 404)
- Test: `analysis/kpi/tests/test_extract_emissions.py`

**Interfaces:**
- Produces: `recon["per_veh"][veh]["task_seq"]` = nach `t0` sortierte Liste von
  `(t0, t1, bucket)`, `bucket` aus `COMPOSITION_KEYS` bzw. dem rohen Tasknamen
  (`_classify` lässt Unbekanntes stehen). Konsumiert von Task 5 und Task 6.

**Warum hier:** Die Schleife ab Z. 316 klassifiziert die gepufferten Tasks ohnehin schon gegen die
Freight-Fenster. Die klassifizierte Sequenz dort mitzuschreiben kostet keinen zweiten Durchlauf und
benutzt exakt dieselbe `_classify`-Entscheidung wie die Aggregate — zwei Wahrheiten über denselben
Task wären ein Defekt.

⚠️ Das Modul wird von anderen Extractoren genutzt. **Strikt additiv**: kein bestehender Key ändert
sich, keine Signatur ändert sich.

- [ ] **Step 1: Failing Test schreiben** — neue Datei
  `analysis/kpi/tests/test_task_seq.py`:

```python
# -*- coding: utf-8 -*-
"""Die Task-Sequenz-Durchreichung in drt_service_time (Task 4)."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "drt-headline"))


def _write_events(tmp_path):
    """Ein Fahrzeug: DRIVE, kurze STAY, DRIVE, lange STAY, MODULAR_FREIGHT_DRIVE."""
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<events>"]
    seq = [(0.0, 600.0, "DRIVE"),
           (600.0, 1200.0, "STAY"),
           (1200.0, 1800.0, "DRIVE"),
           (1800.0, 7200.0, "STAY"),
           (7200.0, 9000.0, "MODULAR_FREIGHT_DRIVE")]
    for t0, t1, tt in seq:
        lines.append('<event time="%.1f" type="dvrpTaskStarted" '
                     'dvrpVehicle="drt_v1" taskType="%s" />' % (t0, tt))
        lines.append('<event time="%.1f" type="dvrpTaskEnded" '
                     'dvrpVehicle="drt_v1" taskType="%s" />' % (t1, tt))
    lines.append("</events>")
    p = tmp_path / "drt_events.xml"
    p.write_text("\n".join(lines), encoding="utf-8")
    return str(p)


def test_reconstruct_exposes_sorted_task_seq(tmp_path):
    import drt_service_time as dst
    recon = dst.reconstruct(_write_events(tmp_path))
    seq = recon["per_veh"]["drt_v1"]["task_seq"]
    assert [b for (_, _, b) in seq] == [
        "DRIVE", "STAY", "DRIVE", "STAY", "FREIGHT_DRIVE"]
    assert [t0 for (t0, _, _) in seq] == sorted(t0 for (t0, _, _) in seq)
    assert seq[3][1] - seq[3][0] == pytest.approx(5400.0)


def test_task_seq_is_additive(tmp_path):
    """Die bestehenden Aggregate duerfen sich nicht veraendern."""
    import drt_service_time as dst
    recon = dst.reconstruct(_write_events(tmp_path))
    pv = recon["per_veh"]["drt_v1"]
    assert pv["stay_s"] == pytest.approx(600.0 + 5400.0)
    assert pv["drive_s"] == pytest.approx(600.0 + 600.0)
    assert pv["freight_drive_s"] == pytest.approx(1800.0)
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_task_seq.py -v`
Expected: FAIL — `KeyError: 'task_seq'`

- [ ] **Step 3: Implementieren**

In `drt_service_time.py` vor der Klassifikationsschleife (~Z. 314) ergänzen:

```python
    task_seq = {}            # vehicle -> [(t0, t1, bucket)], nach t0 sortiert
```

In der Schleife ab Z. 316, direkt nach `key = _classify(ttype, t0, windows)`:

```python
            task_seq.setdefault(v, []).append((t0, t1, key))
```

Nach der Schleife:

```python
    for v in task_seq:
        task_seq[v].sort(key=lambda r: r[0])
```

Und in der `per_veh[v] = {...}`-Konstruktion (~Z. 404) einen Key ergänzen:

```python
            # Additiv (Plan 2026-08-26 Task 4): die klassifizierte Task-Folge.
            # Traegt die Kaltstart-Zaehlung (60-min-Regel) UND die
            # Ladefenster-KPI. Bewusst dieselbe _classify-Entscheidung wie die
            # Aggregate darueber -- zwei Wahrheiten ueber denselben Task waeren
            # ein Defekt.
            "task_seq": task_seq.get(v, []),
```

- [ ] **Step 4: PASS bestätigen + Regression der geteilten Suite**

```bash
python -u -m pytest tests/test_task_seq.py -v
python -u -m pytest tests/ -q
```
Expected: neue Tests PASS, und die 392 bestehenden Tests unverändert grün.

- [ ] **Step 5: Commit**

```bash
git add analysis/drt-headline/drt_service_time.py analysis/kpi/tests/test_task_seq.py
git commit -m "feat(drt): expose the classified DVRP task sequence per vehicle

reconstruct() baute die Sequenz schon auf und verwarf sie nach der
Aggregation. Sie traegt jetzt die Kaltstart-Zaehlung und die
Ladefenster-KPI. Strikt additiv -- das Modul ist geteilt.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Kaltstart-Zählung und Verdrahtung in die Arme

**Files:**
- Modify: `analysis/kpi/extract_emissions.py`
- Test: `analysis/kpi/tests/test_extract_emissions.py`

**Interfaces:**
- Consumes: `cold_start_extra()` (Task 3), `per_veh[veh]["task_seq"]` (Task 4).
- Produces:
  - `cold_starts_by_regime(task_seq, soak_s) -> {"drt": int, "freight_modular": int}`
  - `_add_entity(..., n_cold=0)` — erweiterte Signatur, Default 0 hält jeden bestehenden
    Aufruf unverändert.
  - `arms[fleet] = (totals, detail)` unverändert; `detail`-Dicts tragen zusätzlich
    `n_cold` und je Schadstoff `cold_<EMIS_KEY>`. Konsumiert von Task 7.

**Zählregel (Spec C):** `n = 1` (Schichtbeginn) `+` jede STAY-Phase `>= soak_s`, auf die wieder ein
Fahrblock folgt. Das Regime ergibt sich aus dem **Tasknamen** des folgenden Fahrblocks —
`FREIGHT_DRIVE` → `freight_modular`, `DRIVE` → `drt`. Kein Fensterschnitt nötig.

- [ ] **Step 1: Failing Tests anhängen**

```python
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
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -k cold -v`
Expected: FAIL — `ImportError: cannot import name 'cold_starts_by_regime'`

- [ ] **Step 3: Implementieren**

In `extract_emissions.py` nach den Konstanten ergänzen:

```python
#: Fahr-Tasknamen aus drt_service_time._classify -> KPI-Flottenname. Ein
#: Kaltstart wird dem Regime des FOLGENDEN Fahrblocks zugerechnet (Spec E6):
#: nur so bleibt drt_* + freight_modular_* == total_* exakt.
_DRIVE_REGIME = {"DRIVE": "drt", "FREIGHT_DRIVE": "freight_modular"}
_SOAK_TASK = "STAY"


def cold_starts_by_regime(task_seq, soak_s):
    """Kaltstarts je Regime aus der klassifizierten Task-Folge.

    Zaehlregel: ein Start zu Schichtbeginn (der erste Fahrblock ueberhaupt)
    plus jede STAY-Phase >= soak_s, auf die wieder ein Fahrblock folgt. Eine
    STAY-Phase am Tagesende zaehlt nicht -- sie startet nichts.

    soak_s = 3600 nach EPA (1994): ein Start >= 1 h nach Ende der Vorfahrt
    ist kalt, weil der Katalysator dann abgekuehlt ist. Zitiert ueber
    Reiter & Kockelman 2016 -- belegt, nicht gesetzt.

    Nur STAY bricht einen Fahrblock: ein STOP ist ein Bedienhalt, in dem der
    Motor nach unserer Systemgrenze zwar aus ist, der Katalysator aber nicht
    auskuehlt.
    """
    counts = {"drt": 0, "freight_modular": 0}
    armed = True                       # Schichtbeginn zaehlt als kalt
    for (t0, t1, bucket) in task_seq:
        if bucket == _SOAK_TASK:
            if (t1 - t0) >= soak_s:
                armed = True
            continue
        regime = _DRIVE_REGIME.get(bucket)
        if regime is None:
            continue                   # STOP / RETOOLING brechen nichts
        if armed:
            counts[regime] += 1
            armed = False
    return counts
```

`_add_entity` erweitern (die Default-0 hält bestehende Aufrufe unverändert):

```python
def _add_entity(totals, detail, fleet, entity, vtype, segment, km, v_kmh, fac,
                n_cold=0):
    for pt in POWERTRAINS:
        out = em.vehicle_emissions(km, v_kmh, pt, segment, fac)
        cold = em.cold_start_extra(n_cold, km, v_kmh, pt, segment, fac)
        for k in EMIS_KEYS:
            out[k] += cold[k]          # Spec E1: eingerechnet, nicht daneben
            totals[pt][k] += out[k]
        d = {"fleet": fleet, "entity": entity, "vehicle_type": vtype,
             "segment": segment, "km": km, "v_kmh": v_kmh, "powertrain": pt,
             "n_cold": n_cold}
        d.update({k: out[k] for k in EMIS_KEYS})
        d.update({"cold_" + k: cold[k] for k in EMIS_KEYS})
        detail.append(d)
```

In `freight_arm` den `_add_entity`-Aufruf ergänzen um `n_cold=1` (Spec E5).

In `drt_arm` und `modular_freight_arm` die Zählung aus `recon` ziehen. `drt_arm` hat `recon`
bereits; `modular_freight_arm` bekommt einen neuen Parameter `cold_starts=None`
(`{veh: n}`, Default `None` → 0).

In `drt_arm`, vor dem `_add_entity`-Aufruf:

```python
        seq = per_veh.get(veh, {}).get("task_seq", [])
        n_cold = cold_starts_by_regime(
            seq, fac["sup"]["coldstart_soak_min"] * 60.0)["drt"]
```
und `n_cold=n_cold` an `_add_entity` durchreichen.

In `modular_freight_arm` analog mit `(cold_starts or {}).get(veh, 0)`.

In `extract()` die Zählung einmal bilden und an `modular_freight_arm` geben:

```python
        if windows:
            soak_s = fac["sup"]["coldstart_soak_min"] * 60.0
            per_veh = (recon or {}).get("per_veh", {})
            fm_cold = {v: cold_starts_by_regime(
                           per_veh.get(v, {}).get("task_seq", []),
                           soak_s)["freight_modular"]
                       for v in windows}
            arms["freight_modular"] = modular_freight_arm(
                veh_path, windows, link_len, fac, cold_starts=fm_cold)
```

- [ ] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add analysis/kpi/extract_emissions.py analysis/kpi/tests/test_extract_emissions.py
git commit -m "feat(emissions): count cold starts and fold the surcharge into the totals

Fracht 1 je Tour (die TSV hat keine Task-Folge); DRT und modularer Arm
nach der 60-min-Regel aus der Task-Sequenz. Jeder Start geht an das Regime
des FOLGENDEN Fahrblocks, damit der 1d-Regimesplit exakt bleibt.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Ladefenster-KPI `drive_block_max_km_*`

**Files:**
- Modify: `analysis/kpi/extract_emissions.py`
- Test: `analysis/kpi/tests/test_extract_emissions.py`

**Interfaces:**
- Consumes: `per_veh[veh]["task_seq"]` (Task 4), `veh_path` (4-Tupel mit Zeitstempel),
  `link_len`.
- Produces: `drive_block_rows(veh_path, per_veh, link_len, sup) -> [row(...)]` mit
  `drive_block_max_km_<20|40|60>` und `drive_block_exceed_<w>_<thr>`.

**Definition:** Ein Fahrblock ist die maximale zusammenhängende Zeitspanne zwischen zwei STAYs
`>= w`. Seine km sind die Summe der Link-Längen der Pfadeinträge, deren Zeitstempel hineinfällt.
Verglichen wird gegen `ev_range_km_low/mid/high`.

⚠️ **Das ist eine optimistische Schranke, keine Elektrifizierbarkeitsaussage** — sie unterstellt,
dass jede Standzeit `>= w` zum Laden genügt, ohne Ladeleistung zu modellieren. Der Quellenstring
muss das sagen.

> **Abweichung vom Spec, bewusst und hier festgehalten.** Spec §7 Kriterium 6 verlangt die Kennzahl
> „für DRT und den modularen Arm", also je Regime. Umgesetzt wird sie **je Fahrzeug über beide
> Regime hinweg**: ein Fahrblock, der als Pax-Fahrt beginnt und als Freight-Fahrt endet, ist
> energetisch **ein** Block, weil die Batterie kein Regime kennt. Eine regimegetrennte Variante
> würde denselben Block zweimal halbieren und beide Hälften zu kurz ausweisen — das wäre eine
> Kennzahl, die Elektrifizierbarkeit systematisch zu optimistisch darstellt. Kriterium 6 gilt damit
> als erfüllt, wenn die Zeilen auf 1d-Läufen erscheinen und beide Regime abdecken.

- [ ] **Step 1: Failing Tests anhängen**

```python
def test_drive_blocks_split_at_long_stays_only():
    from extract_emissions import drive_block_rows
    import emissions_emep as em
    sup = em.load_factors()["sup"]
    # v1: 3 Fahrten, dazwischen eine 30-min- und eine 90-min-STAY
    per_veh = {"v1": {"task_seq": [
        (0, 600, "DRIVE"), (600, 2400, "STAY"),        # 30 min
        (2400, 3000, "DRIVE"), (3000, 8400, "STAY"),   # 90 min
        (8400, 9000, "DRIVE")]}}
    # je Fahr-Task ein Link a 10 km
    veh_path = {"v1": [("L", 0, 0, 300.0), ("L", 0, 0, 2700.0),
                       ("L", 0, 0, 8700.0)]}
    link_len = {"L": 10000.0}
    rows = {r["kpi_name"]: r["value"]
            for r in drive_block_rows(veh_path, per_veh, link_len, sup)}
    # w=20 min: beide STAYs trennen -> 3 Bloecke a 10 km
    assert rows["drive_block_max_km_20"] == pytest.approx(10.0)
    # w=60 min: nur die 90-min-STAY trennt -> Bloecke 20 km und 10 km
    assert rows["drive_block_max_km_60"] == pytest.approx(20.0)


def test_drive_block_row_source_denies_an_electrification_verdict():
    from extract_emissions import drive_block_rows
    import emissions_emep as em
    sup = em.load_factors()["sup"]
    per_veh = {"v1": {"task_seq": [(0, 600, "DRIVE")]}}
    rows = drive_block_rows({"v1": [("L", 0, 0, 300.0)]}, per_veh,
                            {"L": 10000.0}, sup)
    assert rows, "expected at least one row"
    for r in rows:
        assert "optimistic" in r["source"].lower()
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -k drive_block -v`
Expected: FAIL — `ImportError: cannot import name 'drive_block_rows'`

- [ ] **Step 3: Implementieren** — an `extract_emissions.py` anhängen:

```python
CHARGE_WINDOWS = ("low", "mid", "high")    # -> sup["charge_window_min_<key>"]

_BLOCK_SRC = (
    "longest contiguous driving block between two STAY phases of at least "
    "<W> min, vs ev_range_km_* (emep_supplement.csv). This is an OPTIMISTIC "
    "bound, not an electrification verdict: it assumes every STAY of that "
    "length suffices to recharge, with no charging power, battery capacity "
    "or depot infrastructure modelled. Window lengths are a sweep because a "
    "DRT vehicle can be re-dispatched at any moment -- the SHORT windows are "
    "the ones that can be relied on. See METHODS-LOG")


def _blocks_from_seq(task_seq, split_s):
    """[(t0, t1)] der Fahrbloecke zwischen STAYs >= split_s."""
    blocks, cur = [], None
    for (t0, t1, bucket) in task_seq:
        if bucket == _SOAK_TASK and (t1 - t0) >= split_s:
            if cur is not None:
                blocks.append(cur)
                cur = None
            continue
        if bucket not in _DRIVE_REGIME and bucket != _SOAK_TASK:
            continue                   # STOP/RETOOLING gehoeren in den Block
        if bucket in _DRIVE_REGIME:
            cur = (cur[0], t1) if cur else (t0, t1)
    if cur is not None:
        blocks.append(cur)
    return blocks


def drive_block_rows(veh_path, per_veh, link_len, sup):
    """Ladefenster-KPI: laengster Fahrblock je Fenster-Schwelle.

    Loest die alte ev_range_exceed_drt_*-Zeile inhaltlich ab, ohne sie zu
    entfernen: dort ist der Nenner ein VOLLER Fahrzeugtag inklusive aller
    Standzeiten, hier ist er ein tatsaechlich zusammenhaengender Fahrblock.
    """
    rows = []
    for key in CHARGE_WINDOWS:
        w_min = sup["charge_window_min_" + key]
        split_s = w_min * 60.0
        src = _BLOCK_SRC.replace("<W>", str(int(w_min)))
        maxima = []
        for veh, path in (veh_path or {}).items():
            seq = per_veh.get(veh, {}).get("task_seq", [])
            if not seq:
                continue
            best = 0.0
            for (b0, b1) in _blocks_from_seq(seq, split_s):
                km = sum(link_len.get(e[0], 0.0) / 1000.0 for e in path
                         if len(e) > 3 and b0 <= e[3] <= b1)
                best = max(best, km)
            if best > 0:
                maxima.append(best)
        if not maxima:
            continue
        rows.append(row("environment", "drive_block_max_km_" + str(int(w_min)),
                        max(maxima), "km", src))
        for thr_key in EV_THRESHOLDS:
            thr = sup["ev_range_km_" + thr_key]
            share = sum(1 for m in maxima if m > thr) / len(maxima)
            rows.append(row("environment",
                            "drive_block_exceed_" + str(int(w_min)) + "_"
                            + str(int(thr)),
                            share, "share",
                            src + " [threshold=" + str(int(thr)) + " km]"))
    return rows
```

In `extract()` direkt nach `rows += _range_rows(...)` einfügen:

```python
    if "drt" in arms and link_len is not None:
        rows += drive_block_rows(veh_path,
                                 (recon or {}).get("per_veh", {}),
                                 link_len, fac["sup"])
```

- [ ] **Step 4: `_RANGE_SRC["drt"]` nachziehen** — der Verweis „nach einer Ladefenster-Analyse
  (BACKLOG)" stimmt nicht mehr:

```python
    "drt": ("per VEHICLE-DAY km vs ev_range_km_* (emep_supplement.csv). NOT "
            "an electrification verdict: a vehicle-day is not a continuous "
            "shift -- it contains STAY phases in which charging is possible. "
            "Use drive_block_max_km_* instead, which measures the longest "
            "CONTIGUOUS driving block"),
```

- [ ] **Step 5: PASS bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add analysis/kpi/extract_emissions.py analysis/kpi/tests/test_extract_emissions.py
git commit -m "feat(emissions): longest contiguous driving block as the charge-window KPI

Loest ev_range_exceed_drt_* inhaltlich ab: dort war der Nenner ein voller
Fahrzeugtag inklusive Standzeiten. Der Quellenstring sagt ausdruecklich,
dass die Kennzahl eine OPTIMISTISCHE Schranke ist und keine
Elektrifizierbarkeitsaussage.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: `*_coldstart_share`-Zeilen und Lücken-Kennzeichnung

**Files:**
- Modify: `analysis/kpi/extract_emissions.py`
- Test: `analysis/kpi/tests/test_extract_emissions.py`

**Interfaces:**
- Consumes: `detail`-Dicts mit `cold_<EMIS_KEY>` (Task 5), `em.COLD_UNPARAMETERISED` (Task 3).
- Produces: KPI-Zeilen `<fleet>_<metric>_coldstart_share` und `total_<metric>_coldstart_share`,
  Einheit `fraction`.

- [ ] **Step 1: Failing Tests anhängen**

```python
def test_coldstart_share_is_cold_over_total():
    from extract_emissions import coldstart_share_rows
    detail = [{"fleet": "freight", "powertrain": "diesel",
               "NOx": 100.0, "cold_NOx": 5.0,
               "PM_EXHAUST": 2.0, "cold_PM_EXHAUST": 0.0}]
    rows = {r["kpi_name"]: r["value"] for r in coldstart_share_rows(detail)}
    assert rows["freight_nox_coldstart_share"] == pytest.approx(0.05)
    assert rows["total_nox_coldstart_share"] == pytest.approx(0.05)


def test_unparameterised_share_row_names_the_gap():
    """Ein 0-Anteil bei PM darf nicht wie eine Messung aussehen."""
    from extract_emissions import coldstart_share_rows
    detail = [{"fleet": "freight", "powertrain": "diesel",
               "NOx": 100.0, "cold_NOx": 5.0,
               "PM_EXHAUST": 2.0, "cold_PM_EXHAUST": 0.0}]
    pm = [r for r in coldstart_share_rows(detail)
          if r["kpi_name"] == "freight_pm_exhaust_coldstart_share"][0]
    assert pm["value"] == 0.0
    assert "no Euro 7 cold parameterisation" in pm["source"]
    nox = [r for r in coldstart_share_rows(detail)
           if r["kpi_name"] == "freight_nox_coldstart_share"][0]
    assert "no Euro 7 cold parameterisation" not in nox["source"]


def test_bev_gets_no_share_rows():
    from extract_emissions import coldstart_share_rows
    detail = [{"fleet": "drt", "powertrain": "bev",
               "NOx": 0.0, "cold_NOx": 0.0}]
    assert coldstart_share_rows(detail) == []
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `python -u -m pytest tests/test_extract_emissions.py -k coldstart_share -v`
Expected: FAIL — `ImportError: cannot import name 'coldstart_share_rows'`

- [ ] **Step 3: Implementieren** — an `extract_emissions.py` anhängen:

```python
_COLD_SRC = (
    "cold-start surcharge FOLDED INTO the matching absolute row (share = "
    "cold / (hot + cold)). EMEP/EEA GB 2023 - Update 2025 ch. 1.A.3.b.i-iv "
    "eq. (10), Euro 6+ form; one cold start per tour (conventional freight, "
    "no task sequence in the TSV) or per STAY >= 60 min followed by driving "
    "(DRT / modular). Threshold: EPA (1994) via Reiter & Kockelman 2016, "
    "Transportation Research Part D 43, 123-132, "
    "doi:10.1016/j.trd.2015.12.012. ta=10 C")

_COLD_GAP_SRC = (
    " -- THIS ZERO IS A GAP, NOT A MEASUREMENT: Appendix 4 carries no "
    "Euro 7 cold parameterisation for this pollutant")


def coldstart_share_rows(detail):
    """Anteil des Kaltstarts an der jeweiligen Gesamtemission.

    Nur der Diesel-Arm: im BEV-Arm ist der Zaehler konstruktionsbedingt 0
    (das Cold-Sheet hat keine BEV-Zeilen), eine Anteilszeile waere dort eine
    Aussage ueber eine Konstante.
    """
    diesel = [d for d in detail if d["powertrain"] == "diesel"]
    if not diesel:
        return []
    gap = set(em.COLD_UNPARAMETERISED)
    fleets = sorted({d["fleet"] for d in diesel})
    rows = []
    for scope in fleets + ["total"]:
        part = diesel if scope == "total" else [
            d for d in diesel if d["fleet"] == scope]
        for metric, key, _unit, _f in _KPI_METRICS:
            tot = sum(d.get(key, 0.0) for d in part)
            cold = sum(d.get("cold_" + key, 0.0) for d in part)
            if tot <= 0:
                continue
            src = _COLD_SRC + (_COLD_GAP_SRC if key in gap else "")
            rows.append(row("environment",
                            scope + "_" + metric + "_coldstart_share",
                            cold / tot, "fraction", src))
    return rows
```

In `extract()` direkt nach `rows += _segment_share_rows(detail)` einfügen:

```python
    rows += coldstart_share_rows(detail)
```

- [ ] **Step 4: PASS bestätigen**

Run: `python -u -m pytest tests/ -q`
Expected: alle grün, Testzahl deutlich über 392.

- [ ] **Step 5: Commit**

```bash
git add analysis/kpi/extract_emissions.py analysis/kpi/tests/test_extract_emissions.py
git commit -m "feat(emissions): report the cold-start share per pollutant

Der Zuschlag steckt in den absoluten Zeilen (Spec E1); die Share-Zeile
macht ihn sichtbar. Ein 0-Anteil bei PM/CH4/SPN23 traegt im Quellenstring
den Hinweis, dass es eine LUECKE ist und keine Messung.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Realdaten-Gegenprobe und Zahlen-Neustand

**Files:**
- Modify: `analysis/kpi/data/README.md`, `docs/METHODS-LOG.md`, `docs/BACKLOG.md`,
  `docs/BACKLOG-DONE.md`

**Kein Code.** Diese Task belegt, dass die Implementierung an echten Läufen tut, was die Tests
behaupten, und zieht die Doku nach.

- [ ] **Step 1: Gegenprobe an mindestens zwei Läufen**

Einen konventionellen Lauf (Freight-Arm) und einen 1d-Lauf (modularer Arm) durch `build_kpis`
schicken. Aus `kpis_long.csv` ziehen und **hier eintragen**:

| Grösse | Erwartung | gemessen |
|---|---|---|
| `freight_nox_coldstart_share` | ~0,053–0,057 (1 Start je Tour) | |
| `drt_nox_coldstart_share` | **> 0,014** — mehr als 1 Start je Fahrzeugtag | |
| mittlere Kaltstarts je DRT-Fahrzeugtag | aus `kpi_emissions_vehicles.csv`, Spalte `n_cold` | |
| `drt_* + freight_modular_* == total_*` | exakt (1d-Lauf) | |
| `drive_block_max_km_20/40/60` | monoton steigend in w | |

⚠️ **Die DRT-Zahl ist der eigentliche Erkenntnisgewinn.** Kommt sie bei genau 1,0 Start je
Fahrzeugtag heraus, ist entweder die Zählung defekt oder die Fahrzeugtage enthalten keine langen
STAY-Phasen — beides ist zu klären, bevor die Zahl ins Paper geht. Die Bound-Rechnung schätzte, dass
5 Starts die DRT-Seite auf ~7 % NOx heben würden, also auf Frachtniveau.

⚠️ Ist `drive_block_max_km_20` **grösser** als `ev_range_km_low` (150 km) bei einem nennenswerten
Anteil der Fahrzeuge, ist die geometrische Schranke bindend — dann greift der Eskalationspfad aus
Spec §5 (energetisches Lademodell als eigener Backlog-Punkt).

- [ ] **Step 2: `data/README.md` Abschnitt „Kaltstart" umschreiben**

Der Abschnitt beginnt heute mit „Kaltstart ist NICHT modelliert." Das stimmt nicht mehr. Umbauen zu:
Formeln und Faktoren **bleiben** (sie sind das Rechenrezept), die Bound-Tabelle **bleibt** als
historische Herleitung mit Datum, aber der Kopf sagt jetzt, dass der Zuschlag implementiert ist,
mit welcher Zählregel und welcher Quelle. Die Limitations L1–L4 aus dem Spec **wörtlich**
übernehmen — insbesondere L1 (BEV ohne Kaltstart, einseitig zugunsten des BEV-Arms).

Ausserdem die Zeile in der Limitations-Liste ersetzen:
- alt: `Kaltstart nicht modelliert: Bound = 5,6 % (NOx), 0,9 % (CO2) je Tour`
- neu: Kaltstart ist modelliert; Restlimitation ist L2 (Fracht sieht nur einen Start je Tour).

- [ ] **Step 3: METHODS-LOG §2.29 umschreiben**

Überschrift heute: „Kaltstart: gerechnete Untergrenze, nicht geschätzte Limitation — NOx-Zahlen sind
zu niedrig". Neu fassen als: die Bound war korrekt und ist jetzt eingelöst; Zählregel und Quelle
nennen; die gemessenen Realdatenzahlen aus Step 1 eintragen; die vier Vorbehalte, die **bestehen
bleiben** (L1–L4), als solche stehen lassen.

Zusätzlich einen Verweis in §2.36–§2.38 setzen: die dortigen NOx-Zahlen stammen aus der Zeit vor dem
Zuschlag **und** aus der alten Depotlogik. Beide Gründe nennen, sonst liest sich der Vermerk wie ein
kleiner Nachtrag.

- [ ] **Step 4: Backlog aktualisieren**

- `[H]` „Kaltstart-Zuschlag implementieren" → nach BACKLOG-DONE, mit den gemessenen Zahlen.
- `[M]` „Ladefenster-Analyse für die DRT-Elektrifizierbarkeit" → nach BACKLOG-DONE, **sofern**
  Step 1 sie beantwortet. Wenn die Schranke bindend ist, stattdessen einen neuen Punkt
  „energetisches Lademodell" anlegen und den alten mit Verweis schliessen.
- Im `[H]`-Nachhaltigkeitsblock den Verweis auf diesen Plan ergänzen.
- **Keine ✅-Annotationen** — erledigte Punkte werden gelöscht und wandern nach BACKLOG-DONE.

- [ ] **Step 5: Volle Suite + Commit**

```bash
cd parcel-demand-2-matsim-pipeline/analysis/kpi
python -u -m pytest tests/ -q
cd ../../..
git add analysis/kpi/data/README.md docs/METHODS-LOG.md docs/BACKLOG.md docs/BACKLOG-DONE.md
git commit -m "docs(emissions): cold start is implemented -- retire the lower-bound caveat

Die NOx-Zahlen sind keine einseitige Untergrenze mehr. Was bleibt: der
BEV-Arm hat weiterhin keinen Kaltstart (einseitig zu SEINEN Gunsten), der
konventionelle Freight sieht nur einen Start je Tour, und PM/CH4/SPN23
haben fuer Euro 7 gar keine Kaltstartparametrisierung.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Bewusst NICHT in diesem Plan

- **Energetisches Lademodell** (Ladeleistung, Batteriekapazität, SoC). Nur falls Task 8 Step 1 die
  geometrische Schranke als bindend zeigt.
- **BEV-Kaltverbrauch** (Spec L1) — braucht eine Quelle ausserhalb EMEP.
- **Winter-Sensitivität** (RANGE 2, ta < 0). Die Koeffizienten werden in Task 1 mit committet, damit
  das später eine Datenauswahl ist und keine Extraktion.
- **Idle-/Leerlaufemissionen.**
- **`src/hagrid_output_analysis/**`** (Kollegen-Paper-Freeze).
