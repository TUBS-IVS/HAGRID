# Landnutzungsdynamik 2025–2035 — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Personen und Firmen verteilen sich über 2025–2035 nach amtlicher Bevölkerungsprognose, Alterung
(mit Kohorteneffekt) und Branchenraten neu, mit Neubau- und Gewerbegebieten als neuen Standorten, und das
Dekaden-Dashboard zeigt den Strukturwandel.

**Architecture:** Reine Faktorfunktionen in einem neuen Modul `land_use.py` (Bezirke, Bevölkerungsindex,
Altershistogramme, Neigung, Firmen, neue Standorte). Die Tagesstufe baut daraus einen Landnutzungsplan (Faktoren je
Standort und Jahr, zusätzliche Standorte, zusätzliche Stopps), `project_annual` gewichtet `historical_share` mit den
Faktoren, und alle Stop-Konsumenten lesen ein gemeinsames Stop-Register (Referenz + Landnutzung + Abholpunkte).

**Tech Stack:** Python 3.13, pandas, geopandas, shapely, pyogrio (OSM-PBF), numpy, pytest; Vanilla-JS-Template.

**Spec:** `docs/demand/specs/2026-09-29-land-use-dynamics-design.md`

## Global Constraints

- Ohne `land_use`-Block (oder mit `enabled: false`) bleibt jeder Lauf bitidentisch; die Suite (384 Tests) bleibt grün.
- Bezugsjahr `base_year = 2025`: alle Faktoren 2025 = 1, neue Standorte öffnen frühestens 2026 → 2025 bleibt bitidentisch.
- Regions- und Segmentsummen bleiben beim Volumenszenario (`assert_balance` wie bisher).
- Code-Kommentare Englisch, Doku Deutsch; Zufall nur über `named_rng(seed, ..., channel=...)`.
- Tests: `cd hagrid/demand/model && PYTHONPATH=src python -m pytest -q tests/<datei> -p no:cacheprovider`.
- Commits enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; kein Push ohne Freigabe.

## Review Focus

1. Neubaueinwohner übersteigen die Bezirksprognose (Garbsen: Prognose −801, Neubau 800) → Bestand schrumpft stärker, bei < 0 auf 0 gekappt und im Status gemeldet — Task 2 (`test_existing_factor_clamps_and_reports`).
2. Standorte außerhalb aller Bezirkspolygone (Randgebäude im Puffer) → nächster Bezirk statt Fehler — Task 2 (`test_assign_districts_uses_nearest_for_outside_points`).
3. Einwohnerannahmen in echten Personen gegen Modellpersonen (synthetisch 1,15 Mio. statt 1,20 Mio.) → Umrechnung mit dem Bezirksverhältnis — Task 2 (`test_development_residents_are_scaled_to_model_persons`).
4. Neue Standorte in der Gebäudehäufung (`site_frailty` nach Straße|PLZ) → eigene Gruppe je Gebiet, nicht alle in einer — Task 4 (`test_site_groups_for_land_use_sites_are_per_area`).
5. `export_day` vor und nach dem Eröffnungsjahr, Abholpunkt-Indizes hinter den Landnutzungsstopps → keine Verschiebung zwischen Jahren — Task 4 (`test_export_day_includes_land_use_stops_and_points`).

---

### Task 1: Daten, Grenzen, Konfiguration

**Files:**
- Create: `src/hagrid_demand/baseline/data/land_use.json`
- Modify: `src/hagrid_demand/baseline/osm.py` (neu `extract_boundaries`), `src/hagrid_demand/cli.py` (`osm-boundaries`),
  `src/hagrid_demand/baseline/config.py` (`_ALLOWED_KEYS` += `land_use`, `osm_boundaries`; `_PATH_KEYS` += `osm_boundaries`)
- Create: `src/hagrid_demand/baseline/land_use.py` (hier nur `load_land_use_inputs`, `resolve_land_use`)
- Test: `tests/test_land_use.py`, `tests/test_osm_clip.py` (Grenzextraktion mit Mini-PBF-Ersatz über Monkeypatch von `pyogrio.read_dataframe`)

**Interfaces:**
- Produces: `load_land_use_inputs() -> dict`; `resolve_land_use(cfg: dict | None) -> dict | None` (None bei fehlendem Block
  oder `enabled: false`; merged Defaults; ValueError bei `cohort_shift ∉ [0,1]`, `new_firm_share ∉ [0,1]`, `grid_m ≤ 0`,
  unbekannter `variant`, Entwicklungsgebiet ohne Geometrie); `extract_boundaries(pbf, plz_csv, out, buffer_m=250.) -> Path`
  (Spalten `osm_id, name, admin_level, geometry`, EPSG:25832, nur `admin_level` 8 und 10, auf die Region geschnitten).
- `land_use.json`-Schema:
  - `source` (Titel, Herausgeber, Datum, Tabellen 7/8, Seiten 49–50),
  - `districts`: 50 Einträge `{"id": "1.1", "name": "Hannover-Mitte", "kind": "city", "pop_2024": 19448, "pop_2034": 19976, "stadtteile": ["Mitte", "Calenberger Neustadt"]}`
    bzw. `{"id": "31", "name": "Barsinghausen", "kind": "umland", "pop_2024": 35697, "pop_2034": 35938, "municipality": "Barsinghausen"}`.
    Die Werte stammen aus der Prognose (Tabelle 7: LHH-Bezirke, Tabelle 8: Umland; bereits ausgelesen in
    `scratchpad/decade/bevprog.txt`, Seite 49/50). Stadtteilzuordnung aus dem Tabellenanhang; 4.1 Buchholz und 4.2 Roderbruch
    liegen beide im Stadtteil Groß-Buchholz und werden zur Modelleinheit `"4.1+4.2" Buchholz/Roderbruch` zusammengelegt
    (Summe der Werte); 4.3 Kleefeld = Kleefeld + Heideviertel; 5.1 Misburg = Misburg-Nord + Misburg-Süd; 7.0 Südstadt/Bult = Südstadt + Bult;
    10.2 Linden-Mitte/Süd = Linden-Mitte + Linden-Süd; 13.1 Vinnhorst = Vinnhorst + Brink-Hafen.
  - `propensity_curve`: `[{"from": 0, "to": 15, "p": 0.0}, {"from": 16, "to": 24, "p": 0.84}, {"from": 25, "to": 44, "p": 0.91}, {"from": 45, "to": 64, "p": 0.80}, {"from": 65, "to": 74, "p": 0.61}, {"from": 75, "to": 120, "p": 0.40}]`
    mit Quelle (Destatis IKT 2025; 75+ Annahme).
  - `firm_rates`: `{"Q": 0.015, "J": 0.01, "M": 0.01, "N": 0.01, "H": 0.01, "G": 0.0, "C": -0.005, "default": 0.005}` mit Quelle.
  - `developments`: 7 Gebiete `{"name", "district_id", "residents", "start_year": 2026, "ramp_years": 4, "geometry": {...}}`:
    Kronsberg-Süd (6.2, 3000, `{"osm_landuse_name": "Kronsberg-Süd"}`), Wasserstadt Limmer (10.3, 1200),
    Seelze-Süd (44, 700), Langenhagen-Mitte (39, 400), Godshorn (39, 400), Kaltenweide (39, 400),
    Garbsen Berenbostel/Mitte (34, 800). Geometrie für alle außer Kronsberg-Süd per `{"center": [x, y], "radius_m": 400}`
    in EPSG:25832 — Schritt 4 bestimmt die Zentren.
  - `variants`: `{"prognose": {"city": 0.0, "umland": 0.0}, "innenentwicklung": {"city": 0.001, "umland": -0.001}, "suburbanisierung": {"city": -0.001, "umland": 0.001}}`.
  - `defaults`: `{"enabled": true, "base_year": 2025, "variant": "prognose", "cohort_shift": 0.7, "new_firm_share": 0.3, "grid_m": 50, "persons": null}`
    (`persons` = Pfad zur Personendatei; `null` = `<input_dir>/persons_total.csv`).

- [ ] **Schritt 1: Tests (RED)** — `test_land_use_inputs_cover_the_forecast` (49 Modelleinheiten; Summe `pop_2024` = 1.203.486,
  Summe `pop_2034` = 1.208.912; Umland 645.435 → 642.077; jede Stadteinheit hat ≥ 1 Stadtteil),
  `test_resolve_land_use_merges_and_validates` (Defaults, Overrides, alle ValueError-Fälle, `enabled: false` → None),
  `test_extract_boundaries_keeps_levels_8_and_10` (Monkeypatch liefert drei Polygone mit `admin_level` 6/8/10 → zwei bleiben),
  `test_config_accepts_land_use_and_boundaries` (Config mit beiden Schlüsseln lädt, Pfad wird aufgelöst).
- [ ] **Schritt 2:** Tests laufen lassen → FAIL (Modul/Schlüssel fehlen).
- [ ] **Schritt 3:** `land_use.json` schreiben (Werte aus Tabelle 7/8), `resolve_land_use`, `extract_boundaries` (Muster
  `extract_transit_stations`: Layer `multipolygons`, `boundary == "administrative"`, `admin_level` aus Spalte oder `other_tags`),
  CLI `osm-boundaries --pbf --plz --out [--buffer-m]`, Config-Schlüssel.
- [ ] **Schritt 4: Zentren der Entwicklungsgebiete** — einmalig im PBF (`niedersachsen-210101.osm.pbf`, Layer `points` und
  `multipolygons`) nach den Namen suchen (`Wasserstadt Limmer`, `Seelze-Süd`/`Seelze Süd`, `Godshorn`, `Kaltenweide`,
  `Langenhagen`, `Berenbostel`, `Garbsen-Mitte`/`Garbsen Mitte`; bei Orten `place=*`, sonst `landuse=*`), Zentroid in EPSG:25832
  übernehmen. Fallback (kein Treffer): Näherung WGS84 → UTM32N aus diesen Koordinaten: Wasserstadt Limmer 52.380 N / 9.686 E,
  Seelze-Süd 52.388 N / 9.598 E, Langenhagen-Mitte 52.441 N / 9.740 E, Godshorn 52.438 N / 9.700 E, Kaltenweide 52.492 N / 9.770 E,
  Garbsen Berenbostel/Mitte 52.425 N / 9.600 E; im JSON als `"approximate": true` markieren.
- [ ] **Schritt 5:** `python -m hagrid_demand baseline osm-boundaries --pbf ../input/hannover/osm/niedersachsen-210101.osm.pbf --plz ../input/hannover/raw/plz_region_hannover.csv --out ../input/hannover/osm/osm_boundaries_region_hannover_2021.parquet`
  (aus `hagrid/demand/model`, `PYTHONPATH=src`); prüfen: 21 Gemeinden (`admin_level 8`), ≥ 50 Stadtteile (`admin_level 10`).
- [ ] **Schritt 6:** Tests GREEN, Suite grün, Commit `feat(land-use): forecast inputs, administrative boundaries, config`.

---

### Task 2: Faktorfunktionen

**Files:** Modify `src/hagrid_demand/baseline/land_use.py`; Test `tests/test_land_use.py`

**Interfaces:**
- Consumes: `load_land_use_inputs()`, Grenzdatei aus Task 1.
- Produces (alle rein, ohne I/O):
  - `districts(boundaries: gpd.GeoDataFrame, inputs: dict) -> gpd.GeoDataFrame[district_id, name, kind, geometry]` (Umland: Gemeinde
    `admin_level 8` mit Namensgleichheit ohne Präfix „Stadt“/„Gemeinde“; Stadt: Vereinigung der Stadtteile `admin_level 10` innerhalb
    der Gemeinde Hannover; fehlender Stadtteil → ValueError mit Namen).
  - `assign_districts(xy: np.ndarray, districts) -> np.ndarray[object]` (Punkt-in-Polygon; außerhalb → nächstes Polygon).
  - `district_population(inputs, years, base_year, variant) -> pd.DataFrame[year, district_id, kind, population_index]`.
  - `aged_histograms(persons: pd.DataFrame[district_id, age, persons], years, base_year, population_index) -> pd.DataFrame[year, district_id, age, persons]`.
  - `propensity(ages: np.ndarray, year: int, base_year: int, curve: list[dict], cohort_shift: float) -> np.ndarray`.
  - `propensity_index(histograms, curve, cohort_shift, base_year) -> pd.DataFrame[year, district_id, propensity_index]`.
  - `existing_factor(model_persons: pd.Series, population_index: pd.DataFrame, development_residents: pd.DataFrame) -> tuple[pd.DataFrame[year, district_id, existing_factor], list[dict]]`
    (zweiter Wert: Warnungen für gekappte Bezirke).
  - `firm_factor(branch: pd.Series, years, base_year, rates, new_firm_share) -> pd.DataFrame[year, site_id, factor]`.
  - `development_residents(developments, years, ratio: pd.Series) -> pd.DataFrame[year, name, district_id, residents_model]`
    (Hochlauf linear über `ramp_years` ab `start_year`, `ratio` = Modellpersonen / Prognosebevölkerung im Bezugsjahr je Bezirk).

Formeln (verbindlich):

```python
# district_population: linear between 2024 and 2034, 2035+ with the mean annual rate, then relative to base_year
rate = (pop_2034 / pop_2024) ** 0.1 - 1
value(y) = pop_2024 + (pop_2034 - pop_2024) * (y - 2024) / 10          # 2024 <= y <= 2034
value(y) = pop_2034 * (1 + rate) ** (y - 2034)                         # y > 2034
value(y) *= (1 + offset[kind]) ** (y - base_year)                       # variant offsets
value(y) *= region_forecast(y) / sum_d value_d(y)                       # variants keep the regional forecast total
population_index(y) = value(y) / value(base_year)

# propensity with cohort shift s(y) = cohort_shift * (y - base_year): p_y(a) = curve(a - s(y))
# aged_histograms: N_d(a, y) = N_d(a - (y - base_year), base_year) for a >= y - base_year, capped at age 100;
# the youngest (y - base_year) ages take the base_year shares of those ages; then scaled to sum = N_d(base) * population_index_d(y)
propensity_index_d(y) = (sum_a N p_y / sum_a N)(y) / (sum_a N p_base / sum_a N)(base_year)

# existing stock of district d: E_d(y) = max(0, P_d * population_index_d(y) - R_d(y)) / P_d
# firms: 1 + (1 - new_firm_share) * ((1 + r_branch) ** (y - base_year) - 1)
```

- [ ] **Schritt 1: Tests (RED)** in `tests/test_land_use.py`:
  - `test_district_population_hits_the_forecast_and_base_year` (2024/2034 exakt über `value`, Index 2025 = 1, 2035 = 2034 × (1+rate)).
  - `test_variants_shift_city_and_umland_but_keep_the_region` (innen: Stadtindex 2035 > prognose, Umland <; Regionssumme gleich).
  - `test_aged_histograms_shift_one_year_per_year` (Person 60 im Jahr 2025 → Alter 65 in 2030; Summe = Bezirksindex × Basis).
  - `test_propensity_cohort_shift_limits` (`cohort_shift=0`: p(70, 2035) = 0,61; `=1`: p(70, 2035) = p(60) = 0,80).
  - `test_propensity_index_is_one_in_base_year_and_falls_with_ageing` (alternder Bezirk ohne Kohorteneffekt < 1).
  - `test_existing_factor_clamps_and_reports` (R > Wachstum → E < Index; R > P → 0 + Warnung).
  - `test_development_residents_are_scaled_to_model_persons` (ratio 0,95 → 3000 → 2850 bei vollem Hochlauf; 2026 = 1/4).
  - `test_firm_factor_compounds_by_branch` (Q 10 Jahre, `new_firm_share` 0,3 → 1 + 0,7 · (1,015¹⁰ − 1); unbekannt → `default`).
  - `test_assign_districts_uses_nearest_for_outside_points`, `test_districts_unites_stadtteile_and_rejects_missing_names`.
- [ ] **Schritt 2:** RED bestätigen. **Schritt 3:** implementieren. **Schritt 4:** GREEN, Suite grün.
- [ ] **Schritt 5:** Commit `feat(land-use): district, ageing, propensity and firm factors`.

---

### Task 3: Neue Standorte

**Files:** Modify `src/hagrid_demand/baseline/land_use.py`; Test `tests/test_land_use.py`

**Interfaces:**
- Consumes: Task 2 (`development_residents`, `firm_factor`), `load_land_use_inputs`.
- Produces:
  - `development_geometry(area: dict, landuse: gpd.GeoDataFrame) -> shapely Polygon` (`osm_landuse_name` → Vereinigung der Polygone
    mit exakt diesem Namen; sonst Kreis `center`/`radius_m`; kein Treffer → ValueError).
  - `development_sites(areas, landuse, residents: pd.DataFrame, share_per_person: pd.Series, grid_m) -> gpd.GeoDataFrame[site_id, segment,
    plz, district_id, area, year_opened, population, historical_share, allocation_status, geometry]`
    (Rasterpunkte im Polygon, mindestens 5; `site_id = f"lu:res:{slug(area)}:{i}"`; `population` = volle Modell-Einwohner / Punkte;
    `historical_share` = `population` × `share_per_person[district]`; `year_opened = start_year`; `allocation_status = "located"`;
    PLZ per Punkt-in-PLZ-Polygon).
  - `new_firms(business: pd.DataFrame[site_id, branch, employees, historical_share, district_id], years, base_year, rates, new_firm_share,
    parcels: gpd.GeoDataFrame, rng_for_year) -> gpd.GeoDataFrame[... gleiche Spalten wie oben, segment "business", branch, employees]`
    (je Jahr und Branche: neue Beschäftigte = `new_firm_share` · Zuwachs gegenüber dem Vorjahr; Betriebe = round(neu / mittlere Betriebsgröße
    der Branche), Rest verfällt; Polygon-Ziehung ∝ Fläche aus `fclass ∈ {commercial, industrial}`; Punkt = `polygon.representative_point()`
    verschoben um eine gleichverteilte Rasterposition innerhalb; `site_id = f"lu:biz:{branch}:{year}:{i}"`;
    `historical_share` = Beschäftigte × mittlerer Anteil je Beschäftigtem der Branche).
  - `land_use_stops(sites: gpd.GeoDataFrame, first_index: int) -> tuple[gpd.GeoDataFrame, pd.DataFrame]`: ein Stopp je neuem Standort
    (`stop_id = site_id`, `stop_index` ab `first_index` in Reihenfolge `year_opened`, `site_id`; `str_idx = -(1 + area_code)` je
    Entwicklungsgebiet bzw. `-(1000 + i)` je Betrieb; `section_id = ""`, `part`/`side` wie Referenz leer, `n_units = 1`,
    `expected_daily = 0`) und die Tabelle `site_id → stop_id`.
  - `site_factors(sites: pd.DataFrame[site_id, segment, district_id, branch, year_opened, area], years, base_year, existing, propensity,
    firms, residents) -> pd.DataFrame[year, site_id, factor]`: Bestand privat = `existing_factor × propensity_index` des Bezirks;
    Bestand gewerblich = `firm_factor`; Entwicklungsstandort = Hochlaufanteil(y) × `propensity_index`; neuer Betrieb = 1 ab
    `year_opened`, sonst 0; im Bezugsjahr alle Bestandsfaktoren exakt 1.
- [ ] **Schritt 1: Tests (RED):** `test_site_factors_combine_stock_propensity_and_openings`, `test_development_geometry_prefers_osm_name`, `test_development_sites_fill_the_polygon_and_split_residents`
  (Summe `population` = Modell-Einwohner; alle Punkte im Polygon; Anteile ∝ Einwohner), `test_new_firms_follow_growth_and_are_deterministic`
  (gleicher Seed → gleiche Punkte; Summe Beschäftigte ≈ `new_firm_share` · Zuwachs ± eine Betriebsgröße; Punkte in Gewerbepolygonen),
  `test_land_use_stops_are_contiguous_and_grouped_per_area`.
- [ ] **Schritt 2–4:** RED → implementieren → GREEN, Suite grün.
- [ ] **Schritt 5:** Commit `feat(land-use): development sites, new firms and their stops`.

---

### Task 4: Projektion und Tagesstufe

**Files:**
- Modify: `src/hagrid_demand/baseline/projection.py` (`project_annual(..., site_factors=None)`),
  `src/hagrid_demand/baseline/workflow.py` (Landnutzungsplan, gemeinsames Stop-Register, Ausgaben, Hash),
  `src/hagrid_demand/baseline/annual.py` (`export_day` hängt `land_use_stops.parquet` an)
- Test: `tests/test_baseline_projection.py`, `tests/test_baseline_workflow.py`, `tests/test_annual_store.py`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `project_annual(reference, series, years, cfg, site_factors: pd.DataFrame | None = None)`: `site_factors[year, site_id, factor]`;
    je Jahr `weight = historical_share × factor` (fehlender Faktor = 1, neue Standorte vor Eröffnung = 0), Normierung je Segment auf
    `weight`, Bilanzen wie bisher. Ohne `site_factors` Code-Pfad unverändert.
  - `workflow._land_use_plan(config, run, output, reference) -> LandUsePlan | None` (Dataclass: `sites` für `reference["sites"]`,
    `factors`, `stops`, `site_stops`, `status`, `districts`) — schreibt `land_use_districts.parquet`, `land_use_factors.parquet`,
    `land_use_sites.parquet`, `land_use_stops.parquet`, `land_use_site_stops.parquet`, `land_use_district_shapes.parquet`
    (Geometrie auf 25 m vereinfacht) nach `output`. Spalten von `land_use_districts.parquet`: `year, district_id, name, kind,
    population_index, propensity_index, persons_model, employees_model, forecast_index` (`forecast_index` = Prognose ohne Variante).
  - `workflow._stop_register(run, output) -> tuple[gpd.GeoDataFrame, pd.DataFrame]`: `reference_stops` (+ `land_use_stops`) und
    `reference_site_stops` (+ `land_use_site_stops`), `stop_type = "home"`. Ersetzt jedes direkte Lesen von
    `reference_stops.parquet`/`reference_site_stops.parquet` in der Tagesstufe (Straßengruppen, OOH-Register, Jahresspeicher,
    MATSim-Export). Abholpunkte beginnen dadurch hinter den Landnutzungsstopps.
  - `workflow._site_population(run, output) -> pd.Series` (Referenz + neue Standorte) für `population_of`.
- Ablauf in `_write_daily`: `reference, projection_cfg = _projection_inputs(...)` → `plan = _land_use_plan(...)`; wenn nicht None:
  `reference["sites"] = concat(reference["sites"], plan.sites[reference-Spalten])`, `projection = project_annual(..., site_factors=plan.factors)`;
  `_project_years` (unsimulierte Referenzjahre der Netzplanung) reicht dieselben Faktoren durch. Status `daily_status.json["land_use"]`
  (Variante, Parameter, Bezirke mit Index 2035, gekappte Bezirke, Zahl neuer Standorte je Jahr). `_daily_code()` += `land_use.py`,
  `data/land_use.json`; Abhängigkeiten der Daily-Stufe += `osm_boundaries`, Personendatei.
- Jahresspeicher: `land_use_stops.parquet` wird wie `out_of_home_points.parquet` nach `annual/` kopiert (Hardlink); `export_day` hängt
  die Landnutzungsstopps vor den Abholpunkten an (alle Jahre; Stopps ohne Sendungen erscheinen ohnehin nicht).
- [ ] **Schritt 1: Tests (RED):**
  - `test_project_annual_with_factors_keeps_balances` (Faktoren ≠ 1 → Segmentsummen exakt; Faktor 1 überall → identisch zum Pfad ohne Faktoren;
    neuer Standort mit Faktor 0 → `annual_expected` 0).
  - Workflow (Testregion aus `street_fixtures`, Jahre 2025/2026, kleine Grenzdatei als Fixture, ein Entwicklungsgebiet mit `center`, `new_firm_share` 0,5):
    `test_land_use_run_writes_its_registers`, `test_land_use_base_year_equals_the_run_without_land_use` (2025: `stop_daily`, `plz_daily`, MATSim-Tage
    bitgleich), `test_land_use_sites_take_parcels_from_their_opening_year`, `test_site_groups_for_land_use_sites_are_per_area`,
    `test_export_day_includes_land_use_stops_and_points` (Tag 2026 enthält Landnutzungs- und Abholpunktstopps, `stop_index` eindeutig,
    Abholpunkte hinter den Landnutzungsstopps).
- [ ] **Schritt 2–4:** RED → implementieren → GREEN, Suite grün (inkl. aller bisherigen Workflow-Tests unverändert).
- [ ] **Schritt 5:** Commit `feat(land-use): weight the projection and extend the stop register`.

---

### Task 5: Configs, Grenzdatei, Doku

**Files:** Modify `configs/decade-{trend,saettigung,boom}.json`; Create `configs/decade-trend-innen.json`,
`configs/decade-trend-suburban.json`; Modify `hagrid/demand/model/README.md`, `hagrid/demand/README.md`,
`runs/hannover/run_demand_decade.bat` (Szenarioliste darf `trend-innen trend-suburban` enthalten);
Test `tests/test_decade_configs.py`.

- [ ] **Schritt 1:** Test (RED) `test_decade_configs_enable_land_use` (alle fünf Configs laden; `land_use.variant` passend; `osm_boundaries`
  zeigt auf `../../input/hannover/osm/osm_boundaries_region_hannover_2021.parquet`; Varianten-Configs ohne `volume_scenario`,
  `dates` nur 2030/2035).
- [ ] **Schritt 2:** Configs ergänzen (`"land_use": {"enabled": true, "variant": "prognose"}` bzw. `innenentwicklung`/`suburbanisierung`,
  `"osm_boundaries": ...`), Varianten-Configs aus `decade-trend.json` ableiten.
- [ ] **Schritt 3:** README-Abschnitt „Landnutzungsdynamik“ (Quellen mit Tabellenangaben, Formeln, Parameter, Varianten, Ausgaben,
  Befehl `osm-boundaries`), Verweis in `hagrid/demand/README.md`.
- [ ] **Schritt 4:** GREEN, Commit `feat(land-use): decade configs with land use and variant runs; docs`.

---

### Task 6: Dekaden-Dashboard „Strukturwandel“

**Files:** Modify `src/hagrid_demand/baseline/decade_dashboard.py`, `src/hagrid_demand/baseline/templates/decade_dashboard.html`;
Test `tests/test_decade_dashboard.py`, `tests/decade_fixtures.py` (Fixture schreibt kleine `land_use_*`-Dateien).

**Interfaces:**
- Consumes: je Lauf `land_use_districts.parquet`, `land_use_district_shapes.parquet`, `land_use_sites.parquet`, `daily_status.json["land_use"]`,
  `data/land_use.json` (Prognosewerte zum Vergleich).
- Produces: Payload-Schlüssel `structure[scenario]` = `{"districts": {ids, names, kinds, shapes (wie PLZ-Shapes), population_index[year][i],
  propensity_index[year][i], employees_index[year][i], forecast_change[i]}, "sites": {ids, kind, area, year_opened, lon, lat, population|employees},
  "age": {years: [2025, 2035], histogram, propensity}, "developments": [{name, district, residents[year], parcels_per_day[year]}], "meta": {...}}`
  oder `null` ohne Landnutzungsdateien.
- Template: Abschnitt `id="structure"` zwischen `#hotspots` und `#network` mit Bezirkskarte (Modi Personen/Neigung/Beschäftigte, Jahresregler
  des Kopfbereichs, neue Standorte ab Eröffnungsjahr), Balken Modell-gegen-Prognose 2025→2035 je Bezirk, Neigungskurve 2025/2035,
  Altersaufbau Region 2025/2035, Tabelle der Neubaugebiete; Methodentexte aus `D.structure[s].meta`.
- [ ] **Schritt 1: Tests (RED):** `test_structure_payload_from_land_use_files`, `test_structure_payload_is_null_without_land_use`,
  `test_template_has_structure_section_in_order` (Reihenfolge `…hotspots, structure, network…`).
- [ ] **Schritt 2–4:** RED → implementieren → GREEN, Suite grün.
- [ ] **Schritt 5:** Commit `feat(dashboard): structural change section`.

---

### Task 7: Läufe und Abnahme

- [ ] **Schritt 1:** `runs\hannover\run_demand_decade.bat trend saettigung boom trend-innen trend-suburban` (vorher alte `decade-*`-Läufe
  umbenennen/löschen; sequentiell, ≈ 30 min je Lauf).
- [ ] **Schritt 2: Abnahme** (Skript in `scratchpad/decade/check_land_use.py`): je Bezirk Modellpersonen-Index 2034 gegen Prognose
  (Abweichung ≤ 0,1 Prozentpunkte bei `prognose`), 2025 bitgleich zu den Läufen ohne Landnutzung (`stop_daily` 2025, `plz_daily` 2025),
  Jahressummen exakt, neue Standorte ab Eröffnungsjahr mit Sendungen, Abholnetz wächst am Kronsberg/Wasserstadt.
- [ ] **Schritt 3:** Dekaden-Dashboard neu bauen (alle fünf Läufe), Artefakt „Hannover Parcel Decade“ als neue Version veröffentlichen.
- [ ] **Schritt 4:** Gesamtsuite, finaler Review (frischer Reviewer auf dem stärksten Modell), Fixes, Bericht; Push nur nach Freigabe.
