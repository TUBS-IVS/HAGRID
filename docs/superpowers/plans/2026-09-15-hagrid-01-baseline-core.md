# HAGRID Baseline Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Der Nutzer hat Terra-Subagenten mit unabhängigen Reviews gewählt; keine erneute Auswahlfrage stellen.

**Goal:** Originale HAGRID-Marktideen aus vorhandenen Quellen als deterministische, bilanzierte Python-Referenz bereitstellen und den Experimentbestand abgrenzen.

**Architecture:** Neutrale Datenaufbereitung bleibt wiederverwendbar. Neue reine Fachfunktionen berechnen Markt-/B2B-Reihen, Potenziale und DHL-Referenz; alte freie Fits bleiben über Kompatibilitätswege verfügbar. Dieser Teil liefert einen ausführbaren Referenzlauf; der vollständige Tageslauf folgt in Plan 02.

**Tech Stack:** Python >=3.11, NumPy, Pandas, SciPy, GeoPandas/Shapely, PyArrow; `openpyxl>=3.1,<4` als deklarierter Leser der vorhandenen XLSX-Quelle; pytest.

**Spec:** [Fachlicher Entwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/specs/2026-09-15-hagrid-baseline-design.md).

## Global Constraints

- Referenzjahr 2021; vollständige DHL-Beobachtungen >1000 ausschließen, 1000 behalten.
- Keine neuen OSM-Abfragen, externen Datendownloads oder MATSim-Ausführung als Baseline-Abhängigkeit.
- Personen-, Firmen- und Nachfragerohdaten sowie historische Runs werden nicht überschrieben oder verschoben.
- Baseline importiert keine experimentellen Module. JSON-Konfiguration; EPSG:25832 für Standortgeometrie.
- Erwartete Mengen: `atol=1e-8, rtol=1e-10`; Counts exakt. Alle weiteren globalen Vorgaben der Spec gelten.
- Alle Dateipfade unten beziehen sich auf `C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand`, sofern nicht ausdrücklich Repo-Root angegeben. Shell-Kommandos laufen dort.

## Schnittstellen und Zuständigkeiten

Neue Dateien: `common/{__init__,contracts,provenance,rng,cache}.py`; `baseline/{__init__,config,sources,series,potentials,reference,workflow,dashboard}.py`; `baseline/data/{market_inputs,b2b_inputs,volume_inputs,provider_priors}.json`; `configs/baseline.json`, `configs/baseline.fixture.json`; `tests/test_baseline_{contracts,series,reference,workflow}.py`, `tests/baseline_fixtures.py`.

`sites` hat `site_id,plz,segment,population,employees,branch,allocation_status` und optionale Geometrie. `potentials` hat eindeutige `(site_id,segment)`, `plz,weight,allocation_status`. `series` ist ein Dict mit `market` (year/carrier/market_share), `b2b` (year/share), `volume` (year/value/status/curve), `weekly` (week/weight) und `providers` (year/carrier/q_prior/q_scale/lower/upper). Alle Anteile sind Bruchteile, Mengen sind Pakete/Jahr.

`reference` ist ein Dict mit `sites` (site_id/plz/segment/population/employees/branch/weight/reference_annual/historical_share/structural_share/allocation_status), `geometry` (site_id/geometry), `postal` (eine Zeile je plz, dhl_retained_mean/reference_annual/private_annual/business_annual/b2b_share/dhl_share), `carriers` (year/segment/carrier/share), `regional_annual` (float) und `checks` (JSON-fähiges Dict). Sites und Geometrie werden als `reference_sites.parquet`/`reference_geometry.parquet` gehasht gespeichert. Beobachtete DHL-Menge ist nicht auf mehrere Segmentzeilen dupliziert. Fachfunktionen schreiben keine Dateien. Der Workflow besitzt sämtliche IO. `allocation_status=located|unlocated`; letzteres ist eine explizite Nachfrage-Restkategorie mit fachlich belegter PLZ, kein normaler Punkt. Unbekannte PLZ ist nur Qualitätsinventar.

### Task 1: Neutrale Verträge, stabile Zufallsnamen und abgesicherte CLI-Grenze

**Files:** oben genannte `common/`, `baseline/config.py`, `tests/test_baseline_contracts.py`; Modify `src/hagrid_demand/cli.py`, `pyproject.toml`.

**Interfaces:** `canonical_digest(value: dict)->str`; `named_rng(seed: int, **keys)->numpy.random.Generator`; `assert_balance(actual,expected)->None`; `load_baseline_config(path: Path)->dict`; `stage_key(name: str, dependencies: dict, config: dict, code_hashes: dict)->str`; `publish_stage(path: Path, write: Callable, validate: Callable)->None`. Der Writer erhält ein temporäres Geschwisterverzeichnis; bei Erfolg wird es atomar publiziert, bei Fehler nie als gültiger Cache markiert.

Zusätzliche Orchestrierungsschnittstelle: `resolve_stage(run_dir: Path,stage_name: str,fingerprint: str,*,cache_root: Path,dependencies: dict,build: Callable[[Path],None],validate: Callable[[Path],None])->Path`. Sie prüft `stage_manifest.json`, konsumierte Abhängigkeiten, Schema-/RNG-Versionen und alle Artefakthashes, bevor ein vorhandenes Ergebnis wiederverwendet wird. Andernfalls baut sie über `publish_stage` und publiziert erst nach Validierung den Abschlussstatus. Cachepfad ist `<cache_root>/<stage_name>/<fingerprint>/`; der Standard für `cache_root` ist `<output_dir>/.stage-cache`. Run-Manifeste referenzieren diesen Ort, benannte finale Run-Artefakte werden verifiziert kopiert. Gleichzeitige Writer dürfen einen gültigen Eintrag nicht überschreiben. Same-Run-Resume, Append-Design und Cross-Run-Cache bleiben gemäß Spec unterschiedliche Vorgänge.

- [ ] Failing tests für wiederholbare Schlüssel, Kanaltrennung, Bilanzfehler und Lazy-Imports schreiben:

```python
def test_named_rng_and_balance():
    from hagrid_demand.common.rng import named_rng
    from hagrid_demand.common.contracts import assert_balance
    import numpy as np
    import pytest
    a = named_rng(42, date='2030-01-02', channel='count').integers(0, 1000, 20)
    b = named_rng(42, channel='count', date='2030-01-02').integers(0, 1000, 20)
    assert np.array_equal(a, b)
    with pytest.raises(ValueError):
        assert_balance(99, 100)
```

- [ ] `python -B -m pytest tests/test_baseline_contracts.py -q` ausführen; fehlende Schnittstellen als erwarteten Fehler bestätigen.
- [ ] Kanonische JSON-Kodierung mit `sort_keys=True,separators=(',',':'),allow_nan=False`; SHA-256 in little-endian uint32 als SeedSequence-Entropie einsetzen. Zeilenpositionen/Python-hash sind verboten. Relative Konfigurationspfade gegen Config-Verzeichnis auflösen. Unbekannte Keys, doppelte Datumsangaben, nicht endliche Werte und Ausgabepfade innerhalb der Inputs ablehnen.

```python
payload = json.dumps({'rng_version': 1, 'seed': seed, **keys},
                     sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False)
entropy = np.frombuffer(hashlib.sha256(payload.encode()).digest(), dtype='<u4')
rng = np.random.Generator(np.random.PCG64(np.random.SeedSequence(entropy)))
```

- [ ] Rekursive Code-/HTML-/JSON-Ressourcenhashes und SHP-Begleitdateien berücksichtigen; Timestamps aus semantischen Hashes ausschließen. Cachetests: geänderter Input, importierte Datei, Schema oder Seed erzeugt neuen Schlüssel; abgebrochener Writer publiziert nichts.
- [ ] Vor JSON-Kodierung Stringkeys/-werte rekursiv Unicode-NFC-normalisieren; danach kollidierende Keys ablehnen. Exakte NumPy/SciPy-Versionen im semantischen Cachevertrag speichern. Ein gespeicherter Stageabschluss ohne gültige Artefakthashes ist nicht wiederverwendbar.
- [ ] CLI-Imports von `workflow`/`spatial`/Modell-Dashboard in alte Befehlszweige verschieben. Baseline-Unterparser vorbereiten, aber keinen vorhandenen Befehl umdeuten. Subprozess-Test prüft, dass Baseline-Hilfe kein `hagrid_demand.model` und kein sklearn importiert.
- [ ] Tests erneut ausführen; ein anderer Terra-Agent prüft Verträge und Importgrenze. Nur eigene Task-Dateien gezielt stagen/committen; fremde staged Änderungen aus dem Commit ausschließen.

### Task 2: Ursprüngliche Reihen frisch berechnen statt alte Modelloutputs voraussetzen

**Files:** `baseline/sources.py`, `baseline/series.py`, vier `baseline/data/*.json`, `tests/test_baseline_series.py`, `pyproject.toml`.

**Interfaces:** `build_series(inputs: dict, years: list[int], *, volume_fit_policy: str)->dict`; `read_foundation(path: Path)->dict`; `prepare_sources(config: dict, output: Path)->dict`. Letzteres baut im exklusiven Rohmodus aus den bestehenden neutralen Readern/Join-Regeln Tabellen auf und prüft im exklusiven Fundamentmodus die vorhandenen Tabellen anhand tatsächlicher Artefakthashes und Schemas. Die Baseline besitzt den Run-Zustand; der alte `pipeline.run_foundation`-Workflow wird nicht verschachtelt gestartet. JSON-Konstanten enthalten Quelle, Notebook-/Zellbezug, Status und Einheit je Wert.

- [ ] Tests zuerst schreiben: tatsächliche B2B-Stützstelle 2021=0,23; 2021-Volumen=4,51e9; 2024–2028 nie `observed`; Marktanteile je Jahr nicht negativ und Summe eins; 52 eindeutige Wochenwerte, positive Mittelwerte, fehlende Quelldatei stoppt frischen Lauf.

```python
def test_source_classification():
    from hagrid_demand.baseline.series import build_series
    from hagrid_demand.baseline.sources import packaged_series_inputs
    s = build_series(packaged_series_inputs(), list(range(2021, 2031)),
                     volume_fit_policy='observed_only')
    assert s['b2b'].set_index('year').loc[2021, 'share'] == .23
    v = s['volume']
    assert not v.loc[v.year.between(2024, 2028), 'status'].eq('observed').any()
```

- [ ] `python -B -m pytest tests/test_baseline_series.py -q` ausführen und fehlende Implementierung bestätigen. `packaged_series_inputs()->dict` liest ausschließlich die vier paketierten Konstantendateien; XLSX-Pfad wird vom Quellenworkflow ergänzt.
- [ ] Konstanten und reine Rechenabschnitte aus den verifizierten Notebook-Snapshots 00,01,02 im Repo-Verzeichnis `docs/demand-audit/notebook-sources/` übertragen. Keine Notebook-Ausführung, Plots, DEAP-Optimierung oder bereits optimierten 05-Outputs als Voraussetzung. Marktprojektion und Amazon-Ergänzung aus 00 beibehalten; B2B-Interpolation und begrenzte Sigmoidkurve aus 01; lineare/logistische/exponentielle Wachstumskandidaten aus 02. Fitting verwendet bei `observed_only` ausschließlich historisch ausgewiesene Stützstellen; bestehende Schätzwerte bleiben ein separat wählbarer `legacy_assumptions`-Pfad. Fehlgeschlagene Fits erhalten Fehlerstatus statt beliebiger Ersatzkurve.
- [ ] Anbieter-Anfangsprofile aus den deklarierten 05-Priors übertragen; Standardgrenzen `[0,1]` mit positiven Änderbarkeitsskalen und deutlich als Annahmen bezeichneten B2B-Präferenzen. Engere alte Grenzen sind opt-in und müssen je Jahr machbar sein. Der fehlerhafte Fit-/Export-Doppelweg wird nicht portiert.
- [ ] Paketierte JSONs über `importlib.resources.files('hagrid_demand').joinpath('baseline/data').joinpath(filename)` lesen, wobei filename einer der vier oben benannten Konstanten-Dateinamen ist. `pyproject.toml` Package-Data enthält zusätzlich exakt `baseline/data/*.json`, `experimental/*.html` und für Plan 02 `baseline/templates/*.html`; Wheel-Smoke muss ohne Repo-relative Datenkonstanten laufen und alle verschobenen HTML-Assets finden.
- [ ] Vorhandene Datei `Parcels19_20_21_inter.xlsx`, Blatt `Tabelle1`, aus 03 lesen; Originaljahre separat normalisieren, Kalenderwochen 1–52 numerisch auslesen, deren relative Reihen mitteln und auf Mittelwert eins normieren. Woche 53 wird später im Kalender definiert. XLSX-Quellenhash mitführen und `openpyxl>=3.1,<4` deklarieren.

```python
relative = weekly[['2019', '2020', '2021']].div(
    weekly[['2019', '2020', '2021']].mean(axis=0), axis=1)
profile = relative.mean(axis=1)
profile = profile / profile.mean()
```

- [ ] Neu erzeugte 00–03-Reihen gegen bisherige Exporte vergleichen; Abweichungen durch Beobachtungsfilter, Jahreskennzeichnung oder Normierung im Prüfbericht benennen. Fitting und Export verwenden dieselbe Vorhersagefunktion. Tests plus unabhängiges Quellen-/Formelreview bestehen lassen und Task-Dateien gezielt committen.

### Task 3: Potenziale, gemeinsame B2B-/Anbieterrechnung und DHL-Anker

**Files:** `baseline/potentials.py`, `baseline/reference.py`, `tests/test_baseline_reference.py`.

**Interfaces:** `build_potentials(sites: DataFrame, power: float=1.0, branch_multipliers: dict|None=None)->DataFrame`; `reconcile_carriers(m: ndarray,q: ndarray,b: float,lower: ndarray,upper: ndarray,scale: ndarray)->dict` mit `q` und `conditional` (2×C, private zuerst); `solve_reference(potentials: DataFrame,dhl: DataFrame,profiles: dict,b: float,operating_days: int)->dict`. `dhl` enthält `observation_id,plz,value,value_status`; Scopefilter und Ledger gehen der Summierung voraus.

- [ ] Kleine analytische Gegenbeispiele schreiben und rot ausführen:

```python
def test_reconciliation_and_infeasible_bounds():
    import numpy as np
    import pytest
    from hagrid_demand.baseline.reference import reconcile_carriers
    m = np.array([.6, .4]); prior = np.array([.2, .8])
    result = reconcile_carriers(m, prior, .3, np.zeros(2), np.ones(2), np.ones(2))
    assert np.isclose(m @ result['q'], .3)
    assert np.allclose(result['conditional'].sum(axis=1), 1)
    with pytest.raises(ValueError):
        reconcile_carriers(m, prior, .1, np.full(2,.5), np.ones(2), np.ones(2))
```

- [ ] `python -B -m pytest tests/test_baseline_reference.py -q` ausführen. Weitere Fälle: 1000/1000,0001; gemischte und reine PLZ; konstantes B2B-Ziel; fehlender Vorzeichenwechsel; Null-DHL-Profil bei positiver Beobachtung; Nullregion; unlokalisierte, aber eindeutig zugeordnete Quellenobjekte.
- [ ] Zunächst Potenziale ohne räumliche Puffer summieren. Personen/Betriebe einmal zählen; bekannte PLZ bei fehlender Geometrie behalten, unbekannte PLZ inventarisieren. `scope.filter_dhl` oder dessen neutrale Extraktion für Schwellenregel verwenden; fehlende/negative Werte separat ablehnen.
- [ ] Quadratische Profilabstimmung mit SciPy SLSQP und linearer Gleichheitsbedingung implementieren; Resultat unabhängig von Solver-Erfolgsmeldung auf Grenzen und Bilanz prüfen. Danach `eta`-Wurzel gemäß Spec mit Brent lösen, keine freie Optimierung je Standort. Die Kernrechnung lautet:

```python
local_b = k * business / (private + k * business)
dhl_share = (1-local_b)*profiles['conditional'][0, 0] + local_b*profiles['conditional'][1, 0]
postal_total = dhl_values / dhl_share
residual = (postal_total @ local_b) / postal_total.sum() - target_b
```

- [ ] Innerhalb jeder PLZ/Segment nach Potenzial verteilen; einmal mit Referenzbetriebstagen multiplizieren; H und S getrennt je Segment normieren. Bei leerem Segment leere Unterstützung explizit darstellen. Nachweis: über alle PLZ entspricht rekonstruierte DHL-Menge der behaltenen Beobachtung; regionale Jahresmenge ist bei konsistenten Profilen `sum(DHL)/m_DHL*operating_days`.
- [ ] Ein unabhängiger Terra-Reviewer prüft Mathematik/Scope und ein weiterer Review-Durchgang die Testgegenbeispiele, Nullunterstützung und Fehlerdiagnosen. Keine neue Genauigkeitszahl gegen dieselben Anker ausgeben. Bestehende Demand-Testsuite nach gemeinsamem Codeeingriff ausführen; eigene Dateien gezielt committen.

### Task 4: Referenzlauf, Berichte, Experimentmigration und Abschluss dieses Teilplans

**Files:** `baseline/workflow.py`, `baseline/dashboard.py`, Configs, `tests/baseline_fixtures.py`, `tests/test_baseline_workflow.py`, CLI, `README.md`; experimentelle Modulgruppe und deren alte Import-Wrapper.

**Interfaces:** `run_baseline(config_path: Path, run_id: str, resume: bool=False)->Path`; `render_baseline(run: Path)->Path`. Der Renderer liest `dashboard_root` aus der aufgelösten Run-Konfiguration, schreibt `report_data.json` und aktualisiert den gemeinsamen Einstieg `<dashboard_root>/index.html`, dessen Pfad er zurückgibt. Standard: `<output_dir>/dashboard`. Config `output_scope='reference'` produziert nur die deterministische Referenz und `status='complete_reference'`; nach Plan 02 unterstützt `'daily'` den vollständigen Tageslauf. Bis dahin darf `'daily'` keinen erfolgreichen Abschluss melden.

- [ ] Fixture mit zwei PLZ, zwei Privatstandorten, zwei Betrieben und vier DHL-Beobachtungen erstellen. `tests/baseline_fixtures.py:write_fixture(root: Path)->Path` schreibt lokale CSV/SHP/XLSX-Minimalquellen, Geometrien und Config; Schema anhand der vorhandenen `data.py`-Reader. `fixture_config`-pytest-Fixture ruft diese Funktion auf.
- [ ] Exakte Fixture-Quellen: Personen-CSV `id,Building,Household,geometry` mit vier Personen in zwei Gebäuden; Firmen-SHP `id,employees,branch,type` mit zwei Punkten und Mitarbeitern 10/100; DHL-SHP `plz,name,tagesschni` mit vier Linien und Werten 10/20/30/40; PLZ-CSV `postal_cod,geometry` mit zwei nicht überlappenden Polygonen. GeoPandas schreibt SHP samt Nebenfiles. Bei Rohmodus mit gemeinsamem Foundation-Reader zusätzlich Hermes-CSV `PLZ;2019;2020;2021` mit je einer Zeile pro PLZ als Inventarquelle, nicht Niveauanker. XLSX-Blatt `Tabelle1` hat vier Spalten, eine erste zu überspringende Datenzeile und danach Wochen 1–52 mit positiven Werten für 2019/2020/2021. Config enthält `source_mode='raw'`, Quellenpfade/-IDs entsprechend dem vorhandenen Foundation-Vertrag, alle CRS=`EPSG:25832`, `reference_year=2021`, `reference_operating_days=313`, `output_scope='reference'`, `output_dir`, `weekly_source` und deaktivierten Legacy-Export. `invalid_employees` beim Fundamentexport erhalten oder aus gespeicherten Beschäftigtenwerten erneut ableiten.
- [ ] Workflow-Test schreiben, rot ausführen, dann Implementierung:

```python
def test_reference_run_and_resume(fixture_config):
    from hagrid_demand.baseline.workflow import run_baseline
    import json
    run = run_baseline(fixture_config, 'reference-fixture')
    assert json.loads((run/'run.json').read_text())['status'] == 'complete_reference'
    assert (run/'reference_postal.parquet').exists()
    assert (run/'report_data.json').exists()
    assert (run.parent/'dashboard'/'index.html').exists()
    assert run_baseline(fixture_config, 'reference-fixture', resume=True) == run
```

- [ ] Quellen → Reihen → Potenziale → Referenz → Bericht als Stages orchestrieren; Artefakte/Checks atomar veröffentlichen. Baseline-CLI `run/report` anbinden. Bericht enthält 2021, Betriebs-Tagesmittel-Annahme, ausgeschlossene Mengen, B2B-Anpassung und Restpotenziale. Gleichnamiger Run ohne Resume und geänderte Quellen beim Resume sind Fehler.
- [ ] Experimentmigration separat innerhalb dieses Tasks durchführen: `model`, `workflow`, `forecast`, `carriers`, `model_search`, `logit_experiment`, `topdown`, `landuse`, `person_features`, `diagnostics`, `street_reconstruct`, `reconstruct`, `logistics_osm`, `size_experiment`, `households`, `continuation_report` und `spatial` nach `experimental/` übernehmen; ihre Importabhängigkeiten per `rg '^from |^import '` vollständig erfassen. Assets mit `Path(__file__).with_name(...)` mitführen. Alte Pfade als Weiterleitungen erhalten, einschließlich `__main__`-Verhalten bei ausführbaren Modulen. Neutrale Reader/Geometrieprüfung dürfen vorerst an alten neutralen Pfaden bleiben. Alle wirklich verschobenen Dateien im Migrationsmanifest einzeln aufführen, nicht rekursiv löschen.
- [ ] Wegen untracked `hagrid-demand/` zuerst inventarisieren und Datei-Hashes sichern; eine neue Git-Worktree enthält untracked Dateien nicht automatisch. Bei späterer isolierter Ausführung genau den benötigten Paket-/Dokustand aufnehmen und verifizieren, keine unbekannten Notebooks/Java-/Emissionsänderungen mitnehmen. Dieser Schritt ist kein Freibrief für `git add .`.
- [ ] `python -B -m pytest -q -p no:cacheprovider` sowie CLI-Smokes alter Befehle ausführen. Der kleine Rohdaten-Referenzlauf muss funktionieren, sein Dashboard offline geöffnet und auf sichtbare Zahlen geprüft werden. Fachreview und separates Codereview abschließen. Ergebnis als Referenzmeilenstein dokumentieren, danach Plan 02 ausführen.
