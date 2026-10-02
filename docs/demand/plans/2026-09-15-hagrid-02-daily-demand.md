# HAGRID Daily Demand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Terra-Implementierer und unabhängige Terra-Reviews sind gewählt.

**Goal:** Aus der geprüften Referenz täglich schwankende Standort-/Anbieternachfrage mit Kalender, Legacy-Exporten und automatischem Dashboard erzeugen.

**Architecture:** Reine Projektions-/Kalenderfunktionen liefern Intensitäten. Ein separater Generator zieht Mengen und Orte; IO aggregiert nach vollständigen Pfadkennungen. Der Baseline-Lauf ist danach ohne Experiment-Fit ausführbar.

**Tech Stack:** Vorhandener Python/NumPy/Pandas/SciPy/GeoPandas-Stack, pytest; keine zusätzliche Sensitivitätsbibliothek.

**Spec:** [Fachlicher Entwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/specs/2026-09-15-hagrid-baseline-design.md). Voraussetzung: [Plan 01](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-01-baseline-core.md) mit bestandenen Reviews.

## Global Constraints

- Python >=3.11; JSON-Konfiguration; Referenzjahr 2021; Zieljahre >=2021.
- Keine neuen OSM-Abfragen, externen Datendownloads oder MATSim-Ausführung als Baseline-Abhängigkeit.
- Rohdaten/historische Runs bleiben unverändert. Baseline importiert keine experimentellen Module.
- Erwartete Mengen `atol=1e-8, rtol=1e-10`; Counts exakt; Restmengen einmal enthalten.
- Pfade relativ zu `C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand`; Kommandos dort ausführen. Alle globalen Vorgaben der Spec gelten.

## Dateien und gemeinsame Verträge

Create `baseline/{projection,calendar,allocation,spatial,diagnostics,outputs}.py`, `compatibility/{__init__,legacy_exports}.py`, `baseline/templates/dashboard.html`, `tests/test_baseline_{projection,calendar,allocation,spatial,compatibility,dashboard}.py`. Modify `baseline/workflow.py`, `baseline/dashboard.py`, `baseline/config.py`, `pyproject.toml` Package-Data und Configs.

`annual` hat pro `(site_id,segment,year)` die Spalten `plz,annual_expected,share,allocation_status`; `profiles` ist year/segment/carrier/share. `site_detail` hat date/outer_id/inner_id/site_id/plz/segment/carrier/allocation_status/baseline_expected/conditional_expected/count. `daily_aggregates` hat dieselben Felder ohne site_id und wird danach gruppiert; Details werden nur auf ausdrückliche Pfadauswahl gespeichert. Ein `year_path` enthält sämtliche Kalenderdatumswerte eines Jahres. `scope` bezieht sich immer auf das Referenzartefakt, nicht auf die aktuell sichtbaren Kartenobjekte.

In `common/contracts.py` werden die Container `AnnualProjection(sites: DataFrame,profiles: DataFrame,postal: DataFrame,checks: dict)` und `SpatialPlan(mode: str,calibration: dict,target_fingerprints: dict,parameter_fingerprint: str,status: str)` als Dataclasses ergänzt. `AnnualProjection.postal` hat year/plz/segment/annual_expected sowie nicht additive Metadaten memory_weight/regional_level_mode/growth_factor/b2b_share. SpatialPlan-Kalibrierungen und Zielhashes sind nach `(year,segment)` indiziert. Die Container schreiben keine Dateien.

### Task 5: Jahresentwicklung und normierter Kalender

**Interfaces:** `project_annual(reference: dict, series: dict, years: list[int], cfg: dict)->AnnualProjection`; `calendar_weights(year: int,segment: str,weekly: DataFrame|None,cfg: dict)->DataFrame`. Plan 02 verwendet `memory.fixed=1`; Plan 03 ergänzt die übrigen Modi innerhalb derselben Projektionsfunktion. Sites, Profile und PLZ-Aggregate werden gemeinsam zurückgegeben und separat gehasht.

- [ ] Tests für einmaliges Wachstum, normierte Jahresmenge und ISO-Grenzen schreiben:

```python
def test_calendar_leap_year_and_holidays():
    from hagrid_demand.baseline.calendar import calendar_weights
    cfg = {'weekday_weights': {'private': [1]*7}, 'monthly_weights': [1]*12,
           'holiday_dates': ['2024-01-01'], 'holiday_factor': 0}
    c = calendar_weights(2024, 'private', None, cfg)
    assert len(c) == 366
    assert abs(c.calendar_weight.sum()-1) < 1e-12
    assert c.set_index('date').loc['2024-01-01','calendar_weight'] == 0
```

- [ ] `python -B -m pytest tests/test_baseline_projection.py tests/test_baseline_calendar.py -q` rot ausführen. Referenzfixture aus Plan 01 laden; zukünftigen LSP-Anteil ändern und unveränderte Regionalmenge prüfen. B2B darf Segmentmengen, aber nicht den regionalen Gesamtwert verändern.
- [ ] Kalenderlogik aus der neutral geprüften `temporal.factors` übernehmen; Profil direkt als Tabelle statt experimenteller Config-/Outputdatei übergeben. Bei gleichzeitig vollem Wochenprofil und nicht neutralen Monatsgewichten Fehler. Wochentagsgewichte positiv/nicht negativ prüfen, Jahresgewichte einmal normieren. Woche 53 erhält den Mittelwert aus 52 und 1.

```python
raw = season * weekday * holiday
if not np.isfinite(raw).all() or (raw < 0).any() or raw.sum() <= 0:
    raise ValueError('Invalid calendar support')
weights = raw / raw.sum()
```

- [ ] Jahresgesamtmenge `reference['regional_annual']*V_y/V_2021`, danach Segmentanteile und normierte historische Standortanteile anwenden. Profile für jedes Zieljahr über `reconcile_carriers` aus Plan 01 abstimmen. Nie zukünftigen LSP-Anteil nochmals als Divisor verwenden. `external_annual_series` direkt in Paketen/Jahr verwenden und zweiten Wachstumskanal ablehnen.
- [ ] Green tests, Quellen-/Einheitenreview durch unabhängigen Terra-Agenten, gezielter Commit eigener Dateien.

### Task 6: Mengenregime und räumliche Dirichlet-Variation

**Interfaces:** `annual_day_counts(annual_total: float,b2b: float,calendars: dict,shocks: dict,seed: int,outer_id: int,inner_id: int,year: int,regime: str)->DataFrame`; `spatial_dirichlet(weights: ndarray,plz: ndarray,site_ids: ndarray,between: float,within: float,rng: Generator)->ndarray`; `generate_days(annual: DataFrame,profiles: DataFrame,calendar: DataFrame,cfg: dict,outer_id: int,inner_id: int,*,spatial_plan: SpatialPlan,cache_dir: Path,coupling_id: str|None=None)->Iterator[DataFrame]`. Dirichlet erzeugt einen SpatialPlan ohne Kalibrierungsfelder, aber mit passenden Ziel-/Parameterhashes. Ganzjahrescounts werden über `resolve_stage` aus Plan 01 gecacht. Es entstehen Standortdetail-Chunks, keine automatische Speicherung aller Details.

- [ ] Tests schreiben und rot ausführen: gesamte Jahrescountsumme; gleicher Tag allein/zusammen; Eingangssortierung; aktive/Nullstandorte; Count-Anbieterbilanz; getrennt messbare Variation zwischen/innerhalb von PLZ.

```python
def test_dirichlet_support_and_balance():
    import numpy as np
    from hagrid_demand.baseline.allocation import spatial_dirichlet
    p = spatial_dirichlet(np.array([.6,.4,0.]), np.array(['01','02','02']),
                          np.array(['a','b','c']), 100, 50, np.random.default_rng(42))
    assert np.isclose(p.sum(),1)
    assert p[2] == 0
```

- [ ] `python -B -m pytest tests/test_baseline_allocation.py -q` starten. Größtes-Restverfahren für Jahressegmentrundung implementieren, Tie-Break nach fester Segmentreihenfolge. `fixed_annual`: vollständige Jahresgewichte inklusive Tagesfaktoren normieren, Counts je Segment einmal über alle Tage multinomial ziehen, Jahresartefakt speichern, erst danach Datumauswahl filtern. `expected_annual`: je Datum unabhängiger Poisson-Count bedingt auf die gemeinsam/segmentspezifisch gezogenen Faktoren.

```python
if regime == 'fixed_annual':
    day_prob = calendar_weight * shock
    day_prob /= day_prob.sum()
    counts = year_rng.multinomial(integer_segment_total, day_prob)
else:
    counts = np.array([day_rng[d].poisson(segment_annual*calendar_weight[j]*shock[j])
                       for j,d in enumerate(dates)])
```

- [ ] PLZ-Anteile nach positivem Gewicht aggregieren, zuerst PLZ-Dirichlet ziehen und dann Standort-Dirichlet innerhalb jeder PLZ; Gewichte vor Ziehung normieren, Nullgruppen überspringen. Parameter bedeuten Konzentration und sind nicht als Prozentstreuung beschriftet. Ganze Tagesmenge multinomial auf diese Anteile ziehen, danach pro Standort auf Anbieter. Anbieterprofil wird nicht täglich auf alte PLZ-Zielwerte zurückgesetzt.
- [ ] Intern nach `(plz,site_id)` sortieren und Ergebnis invers in Eingangsreihenfolge zurückgeben. `PLZ_share ~ Dirichlet(between*normalized_PLZ_weights)`; lokal `share ~ Dirichlet(within*normalized_local_weights)`; Standortanteil ist deren Produkt. Fester-Seed-Test mit 4096 unabhängigen Ziehungen, Gewichten `[.6,.2,.2,0]`, PLZ `['01','01','02','03']` prüft mittlere Standort- UND PLZ-Anteile gegen die Sollwerte mit Toleranz `5*sample_sd/sqrt(4096)+1e-4`; Null-PLZ bleibt exakt null. Dies prüft zusätzlich zur Bilanz die richtige mittlere Verteilung.
- [ ] Kanonische Sortierung und `named_rng` verwenden; Datum, Jahresanker, Segment, Outer/Inner und Kanal trennen. Tagesfaktoren mit `exp(sd*z-.5*sd**2)` zentrieren. Gemeinsamer Faktor wird tatsächlich von beiden Segmenten geteilt. Prozessstreuung und Standortanteile sind getrennte Configfelder.
- [ ] Outputstatus enthält Mengenregime, ganze Kalenderjahre, gerundete Jahresmenge und `selected_dates_are_filter_only`. Ein unabhängiger Terra-Agent prüft Zufalls- und Mengenverträge; vollständige Demand-Suite nach Integration, gezielter Commit.
- [ ] Ein zusätzlicher End-to-End-Test verwendet stark ungleiche Tagesfaktoren und prüft nach sämtlichen räumlichen und Carrierziehungen die exakt gespeicherte Jahressegmentmenge; ein Datumausschnitt muss exakt dem gefilterten Ganzjahresresultat entsprechen.

### Task 7: Korrelierte Orte mit geprüfter Mittelwerterhaltung

**Interfaces:** `calibrate_spatial(target: ndarray,xy: ndarray,site_ids: ndarray,parameters: dict,design: dict)->dict`; Resultat `log_base,diagnostics,fingerprint,status`; `spatial_field(date: str,site_ids: ndarray,xy: ndarray,parameters: dict,seed_keys: dict)->ndarray`; `spatial_diagnostics(draws: Iterator[ndarray],target: ndarray,plz: ndarray)->dict`.

`resolve_spatial_plan(reference: dict,projection: AnnualProjection,cfg: dict,outer_id: int,cache_dir: Path)->SpatialPlan` prüft pro Jahr/Segment/Zielverteilung einen vorhandenen Kalibrierungscache oder ruft `calibrate_spatial` auf und publiziert nach Holdoutprüfung. Bei Dirichlet reicht ein Ziel-/Parameterhash. `generate_days` vergleicht die erhaltenen Hashes mit seinen jährlichen Eingaben; fehlende oder unpassende Kalibrierung und `mean_preservation_unresolved` verhindern den erfolgreichen Tages-/Analyselauf.

- [ ] Tests zunächst auf fünf Standorten mit ungleichen Gewichten schreiben: Mittelwerte der alten bloßen Exponentialnormierung können vom Ziel abweichen; neue Korrektur muss Spec-Toleranzen auf anderen Seeds erfüllen; Parameteränderung/Zielblend ändert den Kalibrierungsfingerprint; sigma=0 ergibt Ziel exakt; unlokalisierte Masse erhält keine erfundenen Koordinaten.
- [ ] `python -B -m pytest tests/test_baseline_spatial.py -q` rot ausführen. Koordinatenbasis aus dem Prototyp als reine neue Funktion übernehmen, feste Basisparameter/versionierte Seeds verwenden. Stationäre Gauß-Koeffizienten starten mit Varianz eins; zeitliche Aktualisierung `z_next=rho*z+sqrt(1-rho**2)*innovation`. Zustand nicht täglich ab Start neu aufrollen, sondern geprüfte Jahres-/Block-Checkpoints fortschreiben.
- [ ] Mittelwertkalibrierung auf positiv unterstützter lokalisierter Teilmenge implementieren; vorgegebenes Restgewicht bleibt separat. Ausgang `log_base=log(target)`; gleiche gespeicherte Kalibrierungsfelder pro Iteration, gedämpfte multiplikative Korrektur:

```python
for iteration in range(max_iterations):
    mean = np.mean([softmax(log_base + sigma*z) for z in calibration_fields], axis=0)
    log_base += .5*(np.log(target) - np.log(mean))
    log_base -= log_base.max()
```

- [ ] Positive Unterstützung vorher filtern; bei leerer lokalisierter Menge Feld überspringen. Unabhängige Prüffelder und Monte-Carlo-Standardfehler ausweisen, Cache auf exakte Zielgewichte/Koordinaten/Parameter/Design beschränken. Toleranzüberschreitung liefert `mean_preservation_unresolved` und keine Baseline-Freigabe; Budget-/Iterationsende ist kein Bestehen.
- [ ] Prüfungen auf Nachbarschaft beidseits einer PLZ-Grenze und Zeitpersistenz ergänzen. Ein Terra-Methodenreview kontrolliert Mittelwertsemantik und ein separates Codereview Caches, Performance und Tests. Danach Variante in `generate_days` anbinden; Dirichlet bleibt erste Referenzvariante.

### Task 8: Legacy-Verträge, vollständiger Run und automatisches Dashboard

**Verbindliche UI-Ergänzung:** Es gibt einen gemeinsamen Dashboard-Einstieg pro Output-Arbeitsbereich mit Run-Auswahl und Stage-Navigation gemäß Spec 5a. Alle vorhandenen Baseline-/Analyseansichten werden darin zusammengeführt; kein neues HTML-Dashboard pro Stage oder Analyse.

**Interfaces:** `make_legacy_contract(grid_input: Path,sample_reference: Path,out: Path)->Path`; `export_legacy(series: dict,reference: dict,projection: AnnualProjection,contract: Path,years: list[int],output: Path,schema_version: int)->dict` mit files/source_hashes/mapped_mass/residual_mass/field_semantics; `write_daily_aggregates(chunks: Iterator[DataFrame],output: Path,detail_draws: set[tuple[int,int]])->dict`; `write_detail_draws(chunk: DataFrame,selected_draws: set[tuple[int,int]],output: Path)->None`; `render_baseline(run: Path)->Path` aus Plan 01 erweitern. Der Aggregatwriter ruft den Detailwriter innerhalb derselben Chunk-Schleife auf; Iteratoren werden nicht doppelt konsumiert. `unallocated` verweist auf die schon enthaltenen nicht lokalisierten Zeilen bzw. ein getrenntes Adapter-Restkonto.

- [ ] Tests zuerst: echte alte IDs/Geometrien bleiben erhalten; mehrdeutige/fehlende Zuordnung wird Restkonto; gemappte plus Restmengen ergeben einmal den Eingang; Tages-/Anbietercounts sind integer; Rekonstruktion des Dashboards verändert keine Nachfrageartefakte.

```python
def test_actual_legacy_reader(legacy_export_fixture):
    import ast
    import geopandas as gpd
    path = legacy_export_fixture/'05_ga_corrected_b2b_with_marked_adjust_gdf.csv'
    frame = gpd.read_file(path, GEOM_POSSIBLE_NAMES='geometry', KEEP_GEOM_COLUMNS='NO')
    shares = frame.market_shares_2021.map(ast.literal_eval)
    assert all(abs(sum(x.values())-1)<1e-6 for x in shares)
    assert frame.cell_id.is_unique
```

- [ ] `python -B -m pytest tests/test_baseline_compatibility.py tests/test_baseline_dashboard.py -q` rot ausführen. `legacy_export_fixture` in `tests/baseline_fixtures.py` ergänzt einen gehashten Vertrag mit zwei Rastergeometrien und drei echten Referenz-Samples; die Fixture exportiert über die neue Funktion, nicht über handgeschriebene Wunsch-CSV.
- [ ] Zusätzlich Verbraucher-Semantik prüfen: eine PLZ mit zwei Zellen und `total_coun=3,1`, lokaler LSP-Anteil 0,5 und LSP-Referenz 20 muss bei Zeitfaktor 1 den Gesamtmarkt 40 und deterministische Zellgewichte 30/10 ergeben. `cell_id`/`postal_cod` und Dictionary-Konvention werden über die tatsächlichen Reader und die aus dem Notebook isolierte deterministische Gewichtungsformel geprüft; stochastische oder fehlerhafte Notebook-Top-Level-Schritte werden nicht ausgeführt. Das belegt Semantik zusätzlich zur CSV-Lesbarkeit.
- [ ] Vertrag aus statischen alten Raster-/Samplefeldern bilden. Quelle und ursprüngliche Reihenfolge/IDs sichern. Alte Mengenfelder nicht als neue Nachfrage übernehmen. Standort-zu-Raster/Sample-Zuordnung explizit und eindeutig auswerten; kein doppeltes Zählen bei Intersects. `total_coun` beschreibt den neu hergeleiteten Referenz-Tagesgesamtwert je Zelle; jährliche B2B-/Carrierfelder sind entsprechende gewichtete Anteile, nicht alte Werte. Bei Nullmenge Profil als uninformativ kennzeichnen; ein lesbares Referenzprofil kann mit Gewicht null exportiert werden.
- [ ] 06-Gewichte aus zugeordneten privaten/gewerblichen Referenzpotenzialen bilden; Namen sind kompatibel, Semantik im Adaptervertrag festhalten. Dateinamen/Spalten gegen `plan_verification_20260915.json` prüfen. Jahresabhängige Standortänderungen werden im neuen Tagesgenerator berechnet; ein einzelner statischer 06-Export behauptet keine exakte Reproduktion jeder zukünftigen alten Notebook-Rechnung.
- [ ] Diagramme/Karten offline aus gespeicherten Artefakten: Jahres-/Wochen-/Monatsmengen, Datumswechsel, B2B/Anbieter, absolute Mengen und normierte PLZ-Anteile, Differenzkarte mit festen Skalen, aktive Standorte, Restmengen und Datenjahr. Nullmengen bei Anteilsberechnung kennzeichnen statt dividieren. Neue Templates als Package-Data aufnehmen; unsichere Feldnamen/Quelltexte HTML/JSON-sicher einbetten.
- [ ] `baseline/dashboard.py` um `build_report_data(run: Path)->dict`, `register_report(run: Path,dashboard_root: Path)->dict` und `render_catalog(dashboard_root: Path)->Path` ergänzen. `render_baseline` orchestriert diese Funktionen und gibt den gemeinsamen `index.html`-Pfad zurück. Katalogschema: `schema_version,runs[{run_id,kind,baseline_fingerprint,report_data_path,report_data_hash,stage_status}]`; Registrierung per eindeutigem Run-Schlüssel, keine Duplikate beim Resume. Für alte Berichte `baseline/report_adapters.py:adapt_existing_report(run: Path)->dict` mit expliziter Schemaerkennung und unsupported-Status erstellen. Dessen Herkunftsverweise bewahren die historischen Datenstände.
- [ ] Ein gemeinsamer Schreib-Lock schützt Katalog-Read/Modify/Write und anschließendes atomisches HTML-Publishing. Test mit zwei abgeschlossenen Analyse-Runs muss beide Registrierungen erhalten. Run-Fingerprints und Nachfrage-Parquets dürfen sich beim Rendern nicht ändern. Defekte/fehlende Analyseartefakte erzeugen eine Stage-Fehlermeldung im selben Dashboard.
- [ ] Tests für zwei Referenz-Runs und zugehörige Analyse-Runs: eine einzige neue `dashboard/index.html`, richtige Zuordnung nach Baseline-Fingerprint, kein zusätzliches `temporal.html`/`carriers.html`, Deep-Link öffnet die richtige Stage, gemeinsame Filter bleiben beim Umschalten erhalten, noch nicht berechnete Stage zeigt `not_run` statt leere Erfolgszahlen. Navigations-Smoke im Browser durch sämtliche vorhandenen Stages und zurück durchführen; die URL bleibt beim selben Einstieg, nur ihr Fragment ändert sich.
- [ ] `output_scope=daily` im Workflow aktivieren; bei aktiviertem Legacy-Export ist fehlender Vertrag ein Fehler. Dashboard entsteht automatisch bei jedem erfolgreichen Run, Fehlerlauf bekommt Diagnose. End-to-End-Fixture mit Rohdaten, Wiederanlauf und geändertem Input prüfen; anschließend einen begrenzten echten Lauf für sieben Tage durchführen und Auswertungen visuell kontrollieren. Kein MATSim starten.
- [ ] `python -B -m pytest -q -p no:cacheprovider` ausführen; unabhängiger Terra-Review auf Spec und danach Code/Kompatibilität. Runstatus/Abnahmeliste dokumentieren, eigene Dateien committen, Plan 03 anschließen.
