# HAGRID Data Age and Uncertainty Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Terra-Implementierer und unabhängige Terra-Reviews sind gewählt.

**Goal:** Steuerbaren räumlichen LSP-Einfluss, verschachtelte Monte-Carlo-Läufe und globale Sensitivitätsanalyse samt aussagekräftigem Run-Dashboard ergänzen.

**Architecture:** Analysen laden eine eingefrorene Referenz mit geprüftem Fingerprint. Äußere Parameterpfade verändern Zukunftsannahmen, innere Ziehungen die Tage; gespeicherte Experimentdesigns steuern Reproduzierbarkeit. Kalender, Allokation und Reporting aus Plan 02 werden über reine Schnittstellen wiederverwendet.

**Tech Stack:** Python >=3.11, NumPy, Pandas, SciPy `stats.qmc`; pytest; vorhandene Geometrie-/Parquet-Bibliotheken.

**Spec:** [Fachlicher Entwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/specs/2026-09-15-hagrid-baseline-design.md). Voraussetzungen: [Plan 01](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-01-baseline-core.md), [Plan 02](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-02-daily-demand.md).

## Global Constraints

- Keine neuen OSM-Abfragen, externen Datendownloads oder MATSim-Ausführung als Baseline-Abhängigkeit.
- Referenzjahr 2021; JSON-Konfiguration; Erwartungsbilanz `atol=1e-8, rtol=1e-10`, Counts exakt.
- Gewichtsabnahme verändert räumliche Erwartungswerte, nicht zusätzlich das Regionalniveau oder die Rauschparameter.
- Monte Carlo liefert annahmebedingte Simulationsintervalle; keine aus LSP-Tagesmitteln kalibrierte tägliche Variabilität behaupten.
- Gleiche äußere Gewichte, getrennte inner/outer-Kennungen, keine still entfernten unzulässigen Ziehungen.
- Pfade relativ zu `C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand`; Kommandos dort. Alle globalen Vorgaben der Spec gelten.

## Dateien und APIs

Create `baseline/{memory,parameters,ensemble,sensitivity}.py`, `tests/test_baseline_{memory,parameters,ensemble,sensitivity}.py`, `configs/baseline-memory-{fixed,slow,fast}.json`, `configs/baseline-monte-carlo.json`, `configs/baseline-sensitivity.json`. Modify `baseline/projection.py`, `baseline/config.py`, `baseline/diagnostics.py`, `baseline/dashboard.py`, Template und CLI.

`analysis_config` enthält `baseline_run,baseline_fingerprint,scenario_id,dates,seed,parameters,outer_draws,inner_draws,output_dir,detail_draws`; Parameterdefinitionen besitzen `name,distribution,low,high,central,unit,source_status,target`. Die erste Version unterstützt Uniform und begrenztes Lognormal mit explizitem Quantiltransform; Nullbreite bedeutet fester Parameter und wird im Sensitivitätsdesign ausgelassen. Alle Bereiche müssen endlich sein. Verteilungstransformationen schreiben physische UND latente Werte ins Design.

Das Herkunftsfeld `source_status` verwendet `assumed|estimated`. Gespeicherte Designmetadaten enthalten `rng_version=1,schema_version=1,analysis_reference_stage='reference',baseline_fingerprint,design_block_size`. `baseline_fingerprint` ist der im Referenzmanifest gespeicherte gemeinsame Hash der semantischen Referenzartefakte einschließlich struktureller Ausgangsmerkmale, Geometrie, Reihen und Scope. Zentraler Prozesslauf und äußerer Pfad benutzen getrennte RNG-Kontexte; `coupling_id` gilt ausschließlich für inneren Vergleichszufall.

### Task 9: Konfigurierbare räumliche Erinnerung an 2021

**Interfaces:** `memory_weight(year: int,spec: dict)->float`; `blend_shares(historical: ndarray,structural: ndarray,weight: float)->ndarray`; `future_structural_shares(reference_sites: DataFrame,year: int,power: float,updates: DataFrame|None)->DataFrame`. Die beiden Vektoren pro Segment haben exakt denselben Index; keine implizite Schnittmenge bilden.

- [ ] Endpunkt-/Halbwerttests schreiben:

```python
def test_memory_endpoints_and_half_life():
    import numpy as np
    from hagrid_demand.baseline.memory import memory_weight, blend_shares
    cfg = {'mode':'half_life','initial_weight':1.,'floor':0.,'half_life_years':10.}
    assert memory_weight(2021,cfg) == 1
    assert memory_weight(2031,cfg) == .5
    h=np.array([.8,.2]); s=np.array([.3,.7])
    assert np.allclose(blend_shares(h,s,0),s)
    assert np.allclose(blend_shares(h,s,1),h)
    assert np.allclose(blend_shares(h,s,.5),[.55,.45])
```

- [ ] `python -B -m pytest tests/test_baseline_memory.py -q` rot ausführen. Zusätzliche Tests: floor, ungültige Jahre/Gewichte, fehlendes yearly-Jahr, vertauschter Standortindex, leeres positives Segment, fremde Bestands-ID. `fixed` nutzt `value`, `yearly` nutzt `values` mit Jahresstrings; `half_life` nutzt oben genannte Felder.
- [ ] Formel aus Spec implementieren, Inputs streng prüfen, kein nachträgliches Rückskalieren auf LSP-PLZ-Anteile:

```python
w = floor + (initial-floor)*2**(-(year-2021)/half_life_years)
p = w*historical + (1-w)*structural
```

- [ ] In `project_annual` pro Segment einsetzen; Regionalmenge unverändert lassen. Unsichere Firmenexponenten in Analysen verändern S, nicht die eingefrorene historische Referenz H. Endpunktmengen, Gewicht und vor/nach-Anteile im Jahresartefakt speichern. Drei Szenario-Configs aus derselben Referenz erstellen: fixed1, half_life20, half_life5; keine Auswahl als empirisch beste Variante ausgeben.
- [ ] Gegenprobe: nur Halbwertszeit ändern → gleiche Regional-/Segment-/Anbietergesamtmengen; nur zukünftigen LSP-Anteil ändern → gleicher Gesamtmarkt; w=0 entfernt H räumlich, nicht den Herkunftshinweis des Niveaus. Methodenreview und separates Codereview durch Terra, danach gezielter Commit.

### Task 10: Gespeicherte Parameterpfade und Monte-Carlo-Runner

**Interfaces:** `draw_parameters(specs: list[dict],n: int,seed: int,block_id: int)->DataFrame`; `apply_parameters(central: dict,row: Series,years: list[int])->dict`; `run_ensemble(config_path: Path,run_id: str,resume: bool=False,extend_design: int=0)->Path`. Jeder Designblock besitzt eindeutige `outer_id,block_id`, latente Koordinaten und transformierte Werte.

- [ ] Tests für stabile Designs, kohärentes Wachstum, unveränderte Referenz und Fehlerfälle schreiben:

```python
def test_growth_path_is_not_redrawn_per_year():
    from hagrid_demand.baseline.parameters import growth_multiplier
    import math
    assert growth_multiplier(2021,.01) == 1
    assert math.isclose(growth_multiplier(2031,.01), math.exp(.1))
    assert math.isclose(growth_multiplier(2041,.01), math.exp(.2))
```

- [ ] `growth_multiplier(year: int,log_delta: float)->float` als benannte Hilfsfunktion definieren. `python -B -m pytest tests/test_baseline_parameters.py tests/test_baseline_ensemble.py -q` rot ausführen. LHS über `scipy.stats.qmc.LatinHypercube` und dokumentierten Seed erzeugen; gespeicherte Punkte beim Resume lesen, nicht neu ziehen. Korrelationen roher Marktanteile durch Transformation, nicht sieben unabhängige Prozentziehungen.
- [ ] Erste aktivierbare Parameter: `memory_half_life` Uniform[5,20], `annual_log_growth_delta` Uniform[-.01,.01], `business_power` Uniform[.5,1]. Ihre Werte sind Beispiele/Annahmen. Erweiterbare explizite Targets: `level_log_multiplier`, `b2b_logit_delta`, `carrier_logit_delta.<carrier>`, `q_logit_delta.<carrier>`, `common_day_log_sd`, `spatial_between.<segment>`, `spatial_within.<segment>`, `spatial_log_sigma.<segment>`, `spatial_rho.<segment>`. Unbekannte/inaktive Targets ablehnen. Für begrenzte Lognormal-Parameter sind `log_mean,log_sd,low,high` erforderlich.
- [ ] B2B-/Carrier-/q-Zukunftsabweichungen als pro Pfad feste Logitkoeffizienten mal `(year-2021)/10` anwenden; im Referenzjahr keine zusätzliche Zukunftsabweichung. Softmax nur auf positiv unterstützten Carriern, q-/b-Randfälle explizit; anschließend jährliche `reconcile_carriers`-Prüfung. Niveauunsicherheit ist ein eigener Kanal; `external_annual_series` verbietet den nationalen Wachstumskanal. Zufällige Tagesstreuungsparameter liegen außen, tatsächliche Tagesfelder innen.
- [ ] Referenzfingerprint vor dem Runner prüfen; Design atomar speichern; je äußeren Pfad Projektions-/Kalender-/Profilparameter bilden, anschließend `generate_days(...outer_id,inner_id)` verwenden. Nur Aggregate und angeforderte Detaildraws schreiben, abgeschlossene Pfade atomar markieren. Parallelisierung nach äußeren Pfaden mit begrenzter Workerzahl, serial und parallel müssen semantisch identisch sein.
- [ ] Den Vertrag aus Plan 02 vollständig verwenden; keine eigene alternative Simulationsformel im Ensemble-Runner:

```python
cache_dir = Path(path_cfg['cache_root'])
projection = project_annual(reference, series, years, path_cfg)
spatial_plan = resolve_spatial_plan(reference, projection, path_cfg, outer_id, cache_dir)
chunks = generate_days(projection.sites, projection.profiles, calendar, path_cfg,
                       outer_id, inner_id, spatial_plan=spatial_plan,
                       cache_dir=cache_dir, coupling_id=coupling_id)
write_daily_aggregates(chunks, output, detail_draws)
```

  `calendar` wird aus `calendar_weights` für sämtliche Jahre/Segmente aufgebaut; der Generator prüft das vollständige Jahrescount-Artefakt für `fixed_annual`. Eine geänderte Zielverteilung benötigt ihren eigenen SpatialPlan. Unlokalisierte Masse erhält keine erfundenen Feldkoordinaten.
- [ ] Resume lädt nur passende vollständige Pfade. `--extend-design N` hängt einen neuen Designblock an; frühere äußere IDs/Ergebnisse bleiben gleich. Ungültige Ziehung stoppt finale Statistik und erzeugt `invalid_infeasible_design`; keine Ersatzziehung. Test mit absichtlich unvereinbaren Grenzen muss genau dieses Verhalten belegen.
- [ ] `design_block_size` aus dem ersten Lauf festhalten; N muss ein positives Vielfaches sein. Erweiterungen erzeugen entsprechend mehrere unabhängig randomisierte gleich große LHS-Blöcke. Bei unzulässigem N erfolgt ein Fehler vor jedem Schreiben. Gleiche Blockgröße ermöglicht die spätere blockweise Monte-Carlo-Fehleranalyse.
- [ ] Terra-Review auf Pfadsemantik und danach Code/RNG/Resume; kleine Fixture mit 4×3 Ziehungen seriell/parallel/unterbrochen ausführen, relevante Tests und Gesamtsuite bestehen lassen, Task-Dateien committen.

### Task 11: Getrennte Intervalle, Konvergenz und Dashboard

**Interfaces:** `summarize_uncertainty(outer_means: DataFrame,central_process: DataFrame,combined: DataFrame)->DataFrame`; `convergence_report(previous: DataFrame,current: DataFrame,central: DataFrame,history: list[dict])->dict`. Eindeutige Spalte `interval_kind=parameter|process|combined`; Kennzahlen für nicht analytische bedingte Mittelwerte enthalten MCSE.

- [ ] Tests gegen Quantilsummen und ungleiche Outer-Gewichte schreiben:

```python
def test_period_quantile_uses_complete_paths():
    import pandas as pd
    # Beide Pfade ergeben 100 pro Woche; Tagesmediane dürfen nicht beliebig addiert werden.
    f=pd.DataFrame({'outer_id':[0,0,1,1], 'inner_id':[0]*4,
                    'date':['2030-01-01','2030-01-02']*2,'count':[0,100,100,0]})
    totals=f.groupby(['outer_id','inner_id'])['count'].sum()
    assert totals.quantile(.9) == 100
```

- [ ] Den Gegenfall zusätzlich über die tatsächliche `summarize_uncertainty`-API prüfen: zwei Tages-P90 von je 90 dürfen keinen Wochen-P90 180 erzeugen. `python -B -m pytest tests/test_baseline_ensemble.py tests/test_baseline_dashboard.py -q` rot ausführen.
- [ ] Bedingte Erwartungsmengen analytisch aus Jahres-/Kalenderanteilen berichten, soweit ihre Erhaltung bewiesen ist. `fixed_annual` und nichtlineare Aktivitäts-/Streuungskennzahlen benötigen innere Mittelwerte samt Schätzfehler. Prozessquantile stammen aus zentralen Parametern, kombinierte Quantile aus vollständigen gleich gewichteten Outer-Pfaden. Jede Periode zunächst innerhalb Outer/Inner aggregieren; Szenarien separat halten.
- [ ] Quantilvergleich bei deterministischer Erweiterung der Designblöcke implementieren. MCSE der äußeren LHS-Stichprobe aus unabhängig randomisierten Blöcken gleicher Größe beurteilen: ab acht Blöcken 200 näherungsweise Blockbootstrap-Wiederholungen mit kompletten Blöcken einschließlich aller inneren Pfade. Abhängige LHS-Zeilen und Tageszeilen nicht als IID-Beobachtungen bootstrappen. Bei weniger Blöcken `outer_quantile_mcse='unavailable'` ausgeben. Spec-Toleranzen, ausreichende MCSE und zwei aufeinanderfolgende Erweiterungen prüfen; ungeprüfte/instabile Ergebnisse sichtbar kennzeichnen. Inner- und Outer-Präzision separat untersuchen.
- [ ] Kombinierte-Intervall-Gegenbeispiel ergänzen: Outer 0 mit drei abgeschlossenen inneren Ziehungen und Outer 1 mit nur zwei muss vor finaler Quantilbildung einen unvollständigen Status erzeugen; keine Gewichtung nach zufälliger Zahl fertiger Tage/Pfade.
- [ ] Dashboard ergänzt Gewichtsverlauf, H-/S-Beiträge, Szenariovergleich, drei Intervallarten, Parameterbereiche, Anzahl vollständiger Ziehungen und Konvergenzstatus. Karten für Erwartungswertänderung und tägliche Variation unterscheiden. Annahmenintervalle nicht „95 % sicher“ nennen. `report` darf nur Renderprodukte erneuern.
- [ ] Monte Carlo und Sensitivität als Stage-Ansichten im gemeinsamen Dashboard aus Plan 02 registrieren, keine eigenen HTML-Einstiege erstellen. Parent-Zuordnung ausschließlich über geprüften `baseline_fingerprint`; mehrere Analysen derselben Referenz sind innerhalb der Stage auswählbar. Beide Runner rufen nach Abschluss denselben `render_baseline`-Adapter auf und melden `dashboard/index.html#run=<reference-id>&stage=<stage>` samt auswählbarer Analyse-ID. Tests prüfen korrekte Zuordnung, Status, idempotentes Resume und konkurrierende Registrierungen; eine Analyse mit anderer Referenz darf keine bestehenden Ansichten ersetzen.
- [ ] Pilot 100×10 für eine beschränkte Datumsauswahl mit Dirichlet erst nach Laufzeit-/Speichertest starten. Regional-/PLZ-Aggregate streamen, keine vollständigen Standortjahre pro Draw materialisieren. Fach-/Codereview durch getrennte Terra-Agenten, Tests und Artefaktkontrolle, gezielter Commit.

### Task 12: Morris-Screening und gemeinsame Abschlussprüfung

**Interfaces:** `morris_design(names: list[str],trajectories: int,levels: int,seed: int)->DataFrame`; `elementary_effects(design: DataFrame,results: DataFrame)->DataFrame`; `run_sensitivity(config_path: Path,run_id: str,resume: bool=False)->Path`. Design hat `trajectory_id,step,point_id,changed_factor,delta` plus latente Koordinaten; Resultate `point_id,metric,value,mcse,status`.

- [ ] Tests schreiben: bei `f(x)=2*x0+3*x1` liefert mu_star genau 2 und 3, sigma null; gleiche Seeds identische Punkte; `R*(K+1)` Punkte; genau ein Faktor ändert sich pro Schritt; ungültiger Punkt verhindert Rangliste.
- [ ] `python -B -m pytest tests/test_baseline_sensitivity.py -q` rot ausführen. K-Faktornamen sortieren; bei vier Gitterstufen Startkoordinate zufällig aus `{0,1/3}`, zufälliges Vorzeichen, negative Schritte starten um 2/3 höher; Dimensionen in zufälliger Permutation durchlaufen:

```python
delta = 2/3
sign = rng.choice([-1.,1.], size=k)
x = rng.integers(0,2,size=k)/3 + np.where(sign < 0,delta,0)
points = [x.copy()]
for j in rng.permutation(k):
    x = x.copy(); x[j] += sign[j]*delta
    points.append(x)
```

- [ ] Physische Parameter über dieselben Transformationen wie MC erzeugen. Elementare Effekte `(Y_next-Y_prev)/signed_delta` im normalisierten Eingaberaum berechnen; mu/mu_star/sigma und Einheiten ausgeben. 20 Trajektorien als Startdesign; Kategorien nicht als Zahlenrangfolge screenen. Unveränderten vollständigen Punktmatrix-Hash speichern.
- [ ] Deterministische Jahreskennzahlen ohne innere Simulation auswerten. Für Tageskennzahlen Kopplung pro Trajektorie verwenden: gleiche innere Seeds für benachbarte Punkte, Parametervector bleibt separat. MCSE und zusätzliche unabhängige Seed-Batches prüfen; Varianzanteile werden von Morris nicht behauptet.
- [ ] `baseline sensitivity` und dessen Resume anbinden; unvollständige oder unzulässige Trajektorie gibt keine finale Rangliste. Änderungswunsch an Grenzen ist eine neue Designversion/Run-ID. Optionales Sobol bleibt im Ideenbestand bis die führenden Parameter feststehen; keine zusätzliche Bibliothek nur dafür einführen.
- [ ] Abschluss: ein Terra-Agent prüft Anforderungen aus allen drei Plänen, ein anderer führt Codereview der integrierten Änderungen durch. `python -B -m pytest -q -p no:cacheprovider`, CLI-Smokes, frischer kleiner Rohdatenlauf, Simulation, Sensitivität, Resume und Offline-Dashboard prüfen. Erst danach größeren regionalen Vergleich freigeben. Abschlussbericht unterscheidet implementierte Funktionen, bestandene technische Kriterien und weiterhin angenommene empirische Parameter.
