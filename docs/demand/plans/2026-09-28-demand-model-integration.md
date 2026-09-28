# Integration des Nachfragemodells in die neue HAGRID-Struktur

> Ausführung: nativ in der Sitzung (superpowers:executing-plans), Branch `hendrik`, Worktree `.worktrees/hendrik-demand`.

**Ziel:** Das Python-Nachfragemodell (`codex/hagrid-baseline`: Paket `hagrid-demand/`, Doku, zwei Java-Änderungen) liegt
auf `hendrik` im neuen Layout, alle Tests laufen am neuen Ort, die Notebooks sind archiviert, Eingaben und Läufe haben
einen git-ignorierten Platz, und ein Run-Skript erzeugt den MATSim-Input dort, wo `hagrid/simulation` ihn liest.

**Entscheidungen (28.09.2026):** Integration auf `hendrik` (nicht direkt `main`); Notebooks nach
`hagrid/demand/archive/notebooks/`; Eingaben unter `hagrid/demand/input/hannover/`. Push nach `origin/hendrik` nur nach
ausdrücklicher Freigabe.

## Ziel-Layout

```
hagrid/demand/model/            hagrid_demand (pyproject, configs, tests, README)      <- hagrid-demand/
hagrid/demand/input/hannover/   git-ignoriert: Rohdaten, OSM-Parquet, Notebook-Outputs  <- parcel-demand-estimation/input
hagrid/demand/runs/             git-ignoriert: Läufe, Jahresspeicher, Dashboards
hagrid/demand/archive/notebooks/{estimation,estimation-batch}/
hagrid/demand/README.md
hagrid/simulation/src/main/java/hagrid/hannover/demand/DeliveryGenerator.java  (stop_type -> Zustellmodus)
hagrid/simulation/src/main/java/hagrid/core/HagridConfig.java                  (fixedParcelLockerDemand)
docs/demand/{specs,plans,demand-audit}/
runs/hannover/run_demand_year.bat
tools/migrate-demand-input.ps1
.github/workflows/python-tests.yml
```

## Tasks

1. **Historie zusammenführen.** `git merge master` (6 Doku-Commits) und `git merge codex/hagrid-baseline` in den
   Worktree; Konflikte in README/Doku zugunsten `hendrik` + unserer Abschnitte lösen. Java-Konflikte: Änderungen aus
   `parcel-demand-2-matsim-pipeline` verwerfen und in Task 2 neu anwenden. Gate: `git status` sauber, Baum enthält
   `hagrid-demand/`, `hagrid/simulation/`, keine `parcel-demand-2-matsim-pipeline/` mehr.
2. **Java portieren.** `deliveryModeOf` (locker/shared_locker/counter/shop -> PARCEL_LOCKER_EXISTING) und
   `HubConfig.fixedParcelLockerDemand=false` in `hagrid/simulation`; Tests `DeliveryModeOfTest`, `HagridConfigTest`.
   Gate: `mvn -q -pl :hagrid test` grün.
3. **Umzug Python.** `git mv hagrid-demand hagrid/demand/model`; Notebooks nach `hagrid/demand/archive/notebooks/`;
   `docs/superpowers/{specs,plans}` unserer Features und `docs/demand-audit` nach `docs/demand/`; `.gitignore` für
   `hagrid/demand/{input,runs}/`; Pfade in `configs/*.json` (`input_dir`, `osm_*`, `weekly_source`,
   `notebook_output_dir`, `output_dir`, `cache_root`), README-Befehle, Test-Fixtures (`tests/*fixtures.py`).
   Gate: `pytest` im neuen Ort grün (Suite läuft aus `hagrid/demand/model`).
4. **Eingaben und Läufe.** `tools/migrate-demand-input.ps1` kopiert `parcel-demand-estimation/input` ->
   `hagrid/demand/input/hannover/` und Notebook-Outputs; `runs/hannover/run_demand_year.bat` (Jahreslauf, `export-day`,
   Kopie des MATSim-Exports nach `hagrid/simulation/input/hannover/demand/<run-id>/`). Gate: Acht-Tage-Lauf mit
   `configs/baseline-daily.json` aus dem neuen Layout, Shapefiles im Java-Input-Ordner.
5. **Doku und CI.** Wurzel-README: Abschnitt „Nachfragemodell“ mit Verweis auf `hagrid/demand/README.md`; Baum im
   README-Abschnitt 2 ergänzen; `.github/workflows/python-tests.yml` (Python 3.12, `pip install -e .[test]`, pytest).
   Gate: Workflow-Datei validiert (yaml), README-Links vorhanden.
6. **Abschluss.** Volle Python-Suite + Maven-Tests, `git log` sauber, Freigabe für `git push origin hendrik` einholen.

## Review-Fokus

Pfadauflösung relativ zur Konfigurationsdatei nach dem Umzug; `HagridPaths`-Erwartung an den Demand-Ordner;
`.gitignore`, damit keine Läufe oder Rohdaten getrackt werden; Windows-Pfade in `.bat`; Tests, die feste Pfade nutzen.
