# Repo-Umbau: Kern / Hannover / Lausitz sichtbar trennen

**Datum:** 2026-09-17 · **Branch:** `restructure` ab `hendrik`, Rückführung per Fast-Forward auf `hendrik`, kein Master-Merge · **Status:** Design abgenommen, Plan folgt

## 1. Ziel

Das Repo soll beim Öffnen zeigen, was gemeinsam ist, was zur Hannover-Studie (Paketnachfrage + LMD-Freight-Simulation, Kapazitäts-Sweep) und was zur Lausitz-Studie (Hoyerswerda, integrierte DRT + Fracht, 1c/1d) gehört. Zielgruppe sind Externe, die den Code lesen, und wir selbst beim Navigieren.

Der Umbau ist ein **reiner Verschiebe-Refactor**: ein Maven-Modul bleibt ein Maven-Modul, kein heute bestehender Import wird verboten (die Regel in §4.1 friert nur den Ist-Zustand ein), keine Klasse wird zerlegt, kein Lauf-Ergebnis ändert sich. Alles, was Design wäre, steht in §10 als Folgepunkt.

### Nicht-Ziele

- Keine Aufteilung in drei Maven-Module (Option C aus dem Brainstorming, verworfen: mehr als gewollt).
- Keine getrennten Git-Repositories (der Kern müsste als Artefakt veröffentlicht werden; JitPack ist tot, GitHub Packages braucht ein PAT).
- Keine Zerlegung von `HAGRIDSimulationConfig` / `SimulationRunnerUtils`, keine Aufteilung von `HagridPaths` in Studien-Accessoren.
- Keine Umbenennung des Konzeptnamens `drt_shareduse` und seiner Dateinamen (User-Entscheidung 2026-09-17: bleibt, auch das Package heißt weiter `shareduse`).
- Keine Umbenennung der Output-Wurzeln `hagrid-output/` und `hagrid-matsim-output/`.
- Kein Eingriff in den freight-Fork und kein DRT/DVRP-Fork (kommt danach, §10).
- Kein inhaltliches Aufräumen der Run-Skripte; nur die drei mechanischen Ersetzungen (§5.4).

## 2. Ausgangslage (gemessen 2026-09-17)

### 2.1 Kopplung im Java-Code

Karte per Import-Analyse aller Pakete unter `hagrid.**` (main). Ergebnis: der Schnitt ist fast überall schon sauber.

| Paket heute | Einordnung | Begründung |
|---|---|---|
| `hagrid` (Wurzel) | gemischt | `HagridPaths` (Root-Erkennung + Hannover- und Lausitz-Accessoren), `HagridConfig` (Scenario-Enum mit `DRT_*`/`LMD_BASELINE`), `HAGRID2MATSimPipelineRunner`/`ScenarioBuilder` (Hannover) |
| `hagrid.analysis` | Hannover | Legacy-Java-Dashboard (`DashboardGenerator`, `CarrierXmlParser`, `FreightEventHandler`) |
| `hagrid.demand` | Hannover | Guice-Nachfragekette, `dhl_hannover_anderten`, `Region Hannover.shp`; null Lausitz-Bezüge |
| `hagrid.integrated.**` | Lausitz | importiert **nichts** aus `demand` oder `pipeline` |
| `hagrid.pipeline` | Hannover + Mechanik | `ScenarioConfig`/`ScenarioRunner`/`PipelineExecutor` treiben nur `demand`; `CacheConfig`, `PipelineTiming`, `PipelineLogger`, `RoutingStatistics` sind studienfrei |
| `hagrid.simulation` | **der Knoten** | `HAGRIDSimulationConfig` (~165 Lausitz-Treffer, importiert `integrated.modular.Modular`), `SimulationRunnerUtils` (~127), `DrtScenarioBuilder` (rein Lausitz), `RunMetadataWriter`, `KpiDashboardTrigger`; Rest studienfrei |
| `hagrid.utils.*` | Kern | Geo, `Delivery`/`Hub`, Netzwerk, jsprit-Routing; Ausnahmen `Region` (22 Hannover-Kommunen) und `SimulationBatGenerator` |

Zyklen heute: `simulation` ↔ `integrated` (drei Import-Brücken plus `HagridPaths.drtInputsFingerprint()` mit voll qualifiziertem Namen) und `utils.routing` ↔ `simulation` über vier jsprit-Constraint-Klassen. Beide Zyklen sind im einen Modul erlaubt; der Umbau löst nur den zweiten auf, weil das reines Verschieben ist.

### 2.2 Fremdcode

- **freight**: Fork-Submodul `external/matsim-libs` (`TUBS-IVS/matsim-libs`, Branch `hagrid/2025.0-PR3552`, Gitlink = Fork-Spitze `39ad2ccb`). Inhalt: MATSim-Tag `2025.0` + 4 `[HAGRID]`-Commits. Davon ist **einer funktional** (`3b5a493`: Guards, `informEndCalc`, Node-Argumente in `NetworkBasedTransportCosts`, per `NetworkBasedTransportCostsGuardTest` gepinnt), einer Paritäts-Löschung (`76a1638`, −2529 Zeilen `controler`), und **zwei sind obsolet** (`c44fe15`, `39ad2cc`: Fuel-Attribut inline; Core 2025.0 hat `VehicleUtils.getFuelConsumptionLitersPerMeter` wieder, per `javap` gegen das Jar geprüft). `freight/` selbst ist ein POM-Shim ohne eigenen Quelltext. Branch-Name seit dem Core-Bump irreführend.
- **DRT/DVRP**: Release-Artefakte `org.matsim.contrib:drt`/`dvrp` 2025.0, kein Fork, keine überschatteten Klassen (kein `package org.` unter `src`). HAGRID hängt sich über die offizielle Guice-Modal-API ein, in genau zwei Modulen (`SharedUseModule`, `ModularDispatchModule`) und drei implementierenden Klassen (`ChiGateInsertionCostCalculator`, `ParcelOnlyRetryQueue`, `ModularOptimizer`). 24 main-Dateien importieren drt/dvrp.
- **matsim-lausitz 2.0**: lokales Maven-Repo `libs/` mit `.sha1`; genau zwei Verwender (`LausitzDrtPreprocessor` → `PrepareNetwork`, `DrtScenarioBuilder` → `PrepareTransitSchedule`).

### 2.3 Sonstiges

- Repo-Wurzel: 17 PNGs, 4 Dashboard-HTMLs, 10 Logs, `plots/`, `Pictures/`, alle ungetrackt; eine verirrte `org/matsim/freight/carriers/CarriersUtils.class` von 2024-11.
- README trägt den Titel „Parcel Demand Scenario Generator for Hannover“ und erwähnt Lausitz nicht. Das ist für Externe die eigentliche Irreführung.
- CI: nur `stale.yml`, kein Build.
- `hagrid-input/` ist bis auf `.gitkeep`-Skelette ignoriert (≈315 MB lokal: Hannover ≈137 MB, `lausitz/` ≈162 MB, `emissions/` 16 MB geteilt).
- Pfad-Strings außerhalb `docs/` (getrackte Dateien): `parcel-demand-2-matsim-pipeline` in 33 bat / 6 md / 2 xml / 2 ps1 / 2 java; `hagrid-matsim-output` in 28 bat / 8 java / 7 py; `hagrid-input` in 5 java / 2 py / 2 xml / 1 bat / 1 ps1; `analysis/kpi` in 10 py / 4 java / 1 css; `hagrid.integrated` in 130 java / 25 bat / 4 py / 1 ps1.

## 3. Zielstruktur, oberste Ebene

```
HAGRID/
├─ README.md               neu: drei Teile (Kern, Hannover, Lausitz), Wegweiser, Setup
├─ pom.xml                 Parent; Module: external/freight + hagrid
├─ hagrid/                 EIN Maven-Modul (heute parcel-demand-2-matsim-pipeline)
│  ├─ src/main/java/hagrid/{core,hannover,lausitz}/…
│  ├─ src/test/java/hagrid/{core,hannover,lausitz}/…
│  ├─ src/test/resources/  unverändert
│  ├─ input/{common,hannover,lausitz}/   heute hagrid-input/, bleibt gitignored
│  ├─ hagrid-output/       unverändert benannt
│  └─ hagrid-matsim-output/ unverändert benannt
├─ analysis/
│  ├─ common/run-monitoring/
│  ├─ hannover/{sweep,legacy-figures}/
│  └─ lausitz/{kpi,drt-headline,paper-figures,lmd}/
├─ notebooks/{demand-estimation,demand-estimation-batch,hannover-analysis}/
├─ runs/
│  ├─ hannover/            run_analysis.bat, track_sweep.ps1, run_hagrid_sim*.bat, run_step*.bat, run_chain_v2dev.bat
│  └─ lausitz/             alle übrigen .bat/.ps1; Einmalskripte unter campaigns/
├─ external/
│  ├─ matsim-libs/         Submodul, Pfad unverändert
│  ├─ freight/             POM-Shim (heute /freight)
│  ├─ dvrp/, drt/          reserviert für den späteren Fork (§10)
│  └─ libs/                matsim-lausitz-Jar (heute /libs)
├─ tools/                  resync-freight.ps1, setup_hagrid_io.bat, migrate-input-layout.ps1
└─ docs/                   unverändert
```

Entscheidungen des Users dazu (2026-09-17): Modulordner **wird** umbenannt (`hagrid/`); Python-Analysen **kommen** aus dem Modul heraus auf die oberste Ebene.

## 4. Java-Paketkarte

Ein Modul, drei Wurzelpakete. Jede Datei per `git mv`; im Verschiebe-Commit ändern sich nur `package`- und `import`-Zeilen.

| Neu | Kommt aus |
|---|---|
| `hagrid.core` | `HagridPaths`, `HagridConfig`, `HAGRID`, `HagridModule` |
| `hagrid.core.util` | `utils.GeoUtils`; `utils.demand.*` (`Delivery`, `Hub`, `SameSizeKMeans`, `WeightGenerator`); `utils.network.*`; `utils.general.*` ohne `Region`, `SimulationBatGenerator`; `integrated.PopulationClipper` |
| `hagrid.core.routing` | `utils.routing.*` plus aus `simulation`: `MaxRouteDurationConstraint`, `OpenRouteStateVerifier`, `RouteRealStartTimeMemorizer`, `TimeWindowConstraintWithDriverTime` |
| `hagrid.core.pipeline` | `CacheConfig`, `PipelineTiming`, `PipelineLogger`, `RoutingStatistics` |
| `hagrid.core.simulation` | `HAGRIDSimulationRunner`, `HAGRIDSimulationConfig`, `SimulationRunnerUtils`, `HAGRIDScenarioBuilder`, `HAGRIDSimulationModule`, `ByModeCongestedTravelTime`, `CarrierVehicleReRouter`, `ReplanningStrategies`, `ScoringFunctions`, `XMLParcelTypeFixer` |
| `hagrid.hannover` | `HAGRID2MATSimPipelineRunner`, `ScenarioBuilder`, `HAGRIDAnalysisRunner` |
| `hagrid.hannover.demand` | `demand.*` |
| `hagrid.hannover.pipeline` | `ScenarioConfig`, `ScenarioRunner`, `PipelineExecutor` |
| `hagrid.hannover.analysis` | `DashboardGenerator`, `CarrierXmlParser`, `FreightEventHandler` |
| `hagrid.hannover.util` | `Region`, `SimulationBatGenerator` |
| `hagrid.lausitz` | `integrated.*` ohne `PopulationClipper` (`DeliveryDay`, `DepotNetwork`, `IntegratedScenarioConfig`, `DeliveryDistrictBuilder`) |
| `hagrid.lausitz.drt` / `.freight` / `.modular` / `.shareduse` | `integrated.drt` / `.freight` / `.modular` / `.shareduse` eins zu eins |
| `hagrid.lausitz.simulation` | `DrtScenarioBuilder`, `KpiDashboardTrigger`, `RunMetadataWriter` |

Tests spiegeln die Karte. `HagridPathsTest`, `HagridConfigTest`, `ScenarioEnumTest` bleiben unter `core` (sie testen die Schaltzentrale). Die `simulation`-Tests werden nach dem Konzept sortiert, das sie parsen (`ParseScenario{Lmd,SharedUse,Budget,DrtFreight,Seed,OpenDepots}Test`, `DrtScenarioBuilderTest`, `RunMetadataWriterTest`, `HAGRIDSimulationConfigTest`, `ScenarioParsingTest` → `lausitz`; `GenerateDashboardGuardTest`, `ParseScenarioKpiDashboardTest` → `core.simulation`, weil sie den Verzweiger prüfen). `hagrid.freight.NetworkBasedTransportCostsGuardTest` → `core.routing`. Testressourcen bleiben, wo sie sind.

Bekannte Abhängigkeit, die stehen bleibt: `core.simulation` importiert `hannover.analysis.FreightEventHandler`/`DashboardGenerator` (4 Imports) und die drei Lausitz-Brücken. Das ist der Schaltzentralen-Fall aus §4.1.

### 4.1 Die Import-Regel

1. `hagrid.hannover.**` importiert nie aus `hagrid.lausitz.**`, und umgekehrt.
2. Aus `hagrid.core.**` dürfen **nur** diese Klassen in `hannover`/`lausitz` hineinzeigen: `HagridPaths`, `HagridConfig`, `HAGRIDSimulationRunner`, `HAGRIDSimulationConfig`, `SimulationRunnerUtils`.

Festgeschrieben als JUnit-Test `hagrid.core.ArchitectureRulesTest` (Quelltext-Scan der `import`-Zeilen unter `src/main/java`, keine neue Abhängigkeit). Die Allowlist steht im Test; jede Erweiterung ist eine bewusste Entscheidung im Diff. Der Test ist zugleich die Zehn-Zeilen-Architekturbeschreibung für Externe.

## 5. Daten, Analysen, Skripte, Fremdcode

### 5.1 Inputs

```
hagrid/input/
├─ README.md      getrackt (Force-Add): Herkunft der Daten, Bootstrap-Hinweis, dient als Root-Marker
├─ common/emissions/
├─ hannover/{config,demand,geodata,hubs,network,vehicles}/
└─ lausitz/{config,demand,drt,hubs,network,population,transit,vehicles}/
```

- `HagridPaths`: Root-Marker `hagrid-input/config/config.xml` → `input/README.md`; Fallback-Literal `parcel-demand-2-matsim-pipeline` → `hagrid`; `-Dhagrid.pipeline.root` behält seine Bedeutung (zeigt auf `hagrid/`). `inputBase = input/ + StudyArea.folder()`.
- `StudyArea.folder()`: `HANNOVER` → `hannover` (heute leer), `LAUSITZ_HOYERSWERDA` → `lausitz`. Der Emissions-Pfad zeigt auf `input/common/emissions`.
- `.gitignore`: Regeln von `parcel-demand-2-matsim-pipeline/hagrid-input/**` auf `hagrid/input/**` umschreiben, `.gitkeep`-Skelette nachziehen, `input/README.md` per Negation freigeben.

### 5.2 Outputs

`hagrid-output/` und `hagrid-matsim-output/` ziehen unverändert benannt nach `hagrid/`. Begründung: 28 bat + 8 java + 7 py, Laufordner auf vier Maschinen, und in einem Clone sind die Ordner leer.

### 5.3 Analysen

| Ziel | Quelle | Nachzuziehen |
|---|---|---|
| `analysis/lausitz/kpi/` | `…/analysis/kpi/` | Aufruf in `KpiDashboardTrigger` (4 Java-Fundstellen für `analysis/kpi`), `sys.path`-Einträge, `data/`- und `vendor/`-Relativpfade, pytest-Aufruf, 1 CSS-Datei mit dem Pfad (im Plan prüfen) |
| `analysis/lausitz/drt-headline/` | `…/analysis/drt-headline/` | `build_kpis.py` erreicht es per `sys.path.insert` |
| `analysis/lausitz/paper-figures/` | `…/analysis/paper-figures/` | `emissions-services/pilot_spatial_km.py` zeigt per `sys.path` auf `kpi` |
| `analysis/lausitz/lmd/` | `…/analysis/lmd/` | nichts (nur ein HTML) |
| `analysis/hannover/sweep/` | `…/analysis/hannover-sweep/` | importiert nichts von außen; liest die Java-Dashboard-HTMLs |
| `analysis/hannover/legacy-figures/` | lose PNGs/HTMLs aus der Wurzel (ungetrackt) | lokal verschieben, Ignore-Regel |
| `analysis/common/run-monitoring/` | `…/analysis/run-monitoring/` | Pfade in den PS1-Watchern |

Der JSON-Vertrag `RunMetadataWriter` → `run_meta.py` bleibt inhaltlich unverändert; nur wenn das JSON Klassennamen trägt, ändert sich der Wert mechanisch (im Plan prüfen).

### 5.4 Runs

- `runs/hannover/`: `run_analysis.bat`, `track_sweep.ps1`, `run_hagrid_sim.bat`, `run_hagrid_sim_terminal.bat`, `run_stepA_v2dev*.bat`, `run_stepB_v2dev*.bat`, `run_chain_v2dev.bat`.
- `runs/lausitz/`: `run_drt_baseline.bat`, `run_lmd_baseline.bat`, `run_lmd_band.ps1`, `run_nightbc*.bat`, `queue_chi_detour_*`, `run_base_f140_dev.bat`, `run_convbase_chain.bat`, `run_depot1c_chain*.bat`, `run_weekend_chain.bat`; unter `campaigns/` die 1d-Einmalskripte (`run_1d_f120_s3337`, `run_bud*`, `run_cap1d_*`, `run_conv1d_chain`, `run_depot1d_chain`, `run_dur*`, `run_f150t015_dev`, `run_fleet1d_*`, `run_match1d_chain`, `run_seedfan1d`, `run_theta1d_chain`, `run_tourdur_chain`, `run_w1117` samt `_wrap`-Varianten).
- Genau drei mechanische Ersetzungen je Skript: `-pl parcel-demand-2-matsim-pipeline` → `-pl hagrid`; Jar-Pfad `parcel-demand-2-matsim-pipeline/target/…` → `hagrid/target/…`; Main-Klassennamen (`hagrid.HAGRIDSimulationRunner` → `hagrid.core.simulation.HAGRIDSimulationRunner`, `hagrid.integrated.drt.PrepareLausitzDrtInputs` → `hagrid.lausitz.drt.PrepareLausitzDrtInputs`, `hagrid.HAGRID2MATSimPipelineRunner` → `hagrid.hannover.HAGRID2MATSimPipelineRunner`, `hagrid.HAGRIDAnalysisRunner` → `hagrid.hannover.HAGRIDAnalysisRunner`, `hagrid.integrated.freight.LausitzFreightPreprocessor` → `hagrid.lausitz.freight.LausitzFreightPreprocessor`). Relative `cd`- und `-Dhagrid.pipeline.root`-Angaben werden an den neuen Ort angepasst.
- `.bat` ausschließlich per PowerShell `WriteAllLines` mit CRLF schreiben, nie per Edit/Write.
- Die zwei heute ungetrackten Skripte im Modul (`run_1d_f120_s3337.bat`, `run_base_f140_dev.bat`) werden mit umgezogen und mit getrackt.

### 5.5 Notebooks

Reiner Umzug: `parcel-demand-estimation/` → `notebooks/demand-estimation/`, `parcel-demand-estimation-batch/` → `notebooks/demand-estimation-batch/`, `parcel-analysis/` → `notebooks/hannover-analysis/`, jeweils samt `input/`/`output/`. Notebook-interne Pfade werden **nicht** angefasst (Notebooks sind Archiv; README nennt das).

### 5.6 Fremdcode

- `git mv freight external/freight`; `git mv libs external/libs`.
- Parent-POM: `<module>external/freight</module>`, `<module>hagrid</module>`; Repo-URL `file:///${maven.multiModuleProjectDirectory}/external/libs`.
- `external/freight/pom.xml`: `sourceDirectory`/`testSourceDirectory`/`testResources`/`workingDirectory` von `${project.basedir}/../external/matsim-libs/…` auf `${project.basedir}/../matsim-libs/…`; Enforcer-Dateien bleiben auf `${project.parent.basedir}/external/matsim-libs/…` (kein `..`, Enforcer 3.5.0 vergleicht kanonisch).
- Der Sparse-Checkout-Befehl bleibt **wörtlich identisch** in README, Enforcer-Meldung und `tools/resync-freight.ps1` (Invariante aus der Fork-Spec 2026-07-13).
- `.gitmodules`: unverändert.
- `tools/`: `resync-freight.ps1`, `setup_hagrid_io.bat`, neu `migrate-input-layout.ps1` (§8).

### 5.7 README und Docs

- README: neuer Titel („HAGRID – Parcel demand and integrated freight/DRT simulation with MATSim“ o. ä.), Abschnitt „Repository Structure“ mit dem Baum aus §3 und einem Absatz je Teil, Setup unverändert, Lausitz-Abschnitt mit Verweis auf `docs/DATA-LAUSITZ.md`, `docs/PAPER-RUNS.md`, `docs/METHODS-LOG.md`.
- `docs/**`: Ordnernamen mechanisch ersetzen (`parcel-demand-2-matsim-pipeline/` → `hagrid/`, `analysis/kpi` → `analysis/lausitz/kpi`, `hagrid-input/` → `input/`, Paketpfade `hagrid/integrated/` → `hagrid/lausitz/` usw.). Zeilennummern in `file:line`-Verweisen werden **nicht** repariert (sie sind ohnehin zu etwa zwei Dritteln veraltet, BACKLOG-Befund 2026-08-18).

## 6. Migrationsreihenfolge

Jeder Schritt ist ein oder zwei Commits auf `restructure` und für sich baubar.

| # | Schritt | Gate |
|---|---|---|
| 1 | Wurzel aufräumen (ungetrackte PNGs/HTMLs → `analysis/hannover/legacy-figures/`, Logs löschen, `org/…/CarriersUtils.class` löschen), `.gitignore` erweitern | `git status` zeigt nur beabsichtigte Änderungen |
| 2 | `external/`: freight-Shim + `libs/` umziehen, drei POMs anpassen | `mvn -q install -pl external/freight -am` grün (272 Tests) |
| 3 | Modul → `hagrid/`, Inputs → `input/{common,hannover,lausitz}`, Root-Marker, `StudyArea.folder()`, `.gitignore` | Pipeline-Tests grün; `HagridPathsTest` auf neue Pfade angepasst |
| 4a | Java-Pakete verschieben (nur `git mv` + `package`/`import`) | alle Tests grün; `git log --follow` auf `HAGRIDRouterUtils.java`, `SharedUseModule.java`, `DashboardGenerator.java` zeigt die alte Historie |
| 4b | `ArchitectureRulesTest` | grün mit exakt der Allowlist aus §4.1; Mutationsprobe: ein verbotener Import lässt ihn rot werden |
| 5 | `analysis/`, `notebooks/`, `runs/`, `tools/` umziehen; Pfade in Skripten, Python, `KpiDashboardTrigger` ersetzen | KPI-Pytests grün; jedes `.bat`/`.ps1` einmal bis zum Java-Start ausgeführt (Klasse gefunden, Root erkannt) |
| 6 | README, `docs/**`-Pfade | `git grep parcel-demand-2-matsim-pipeline` liefert nur noch Historie in METHODS-LOG/BACKLOG-DONE |
| 7 | BACKLOG-Folgepunkte (§10), METHODS-LOG-Eintrag „Umbau verhaltensneutral“ mit den Belegen aus §7 | – |

Danach: Nachweis §7, dann Fast-Forward `hendrik` ← `restructure`. Push nur auf Zuruf.

## 7. Nachweis der Verhaltensneutralität

Grüne Tests belegen keine Verhaltensneutralität (Lehre aus fünf Verdrahtungsfehlern bei grüner Suite). Drei Belege, alle auf dem Dev-PC, alter Baum = `hendrik` vor dem Merge in einem zweiten Worktree:

1. **Lausitz-Eingabekette:** `PrepareLausitzDrtInputs` auf altem und neuem Baum, `DrtInputsFingerprint` identisch.
2. **Lausitz-Simulation:** `drt_baseline`, 2 Iterationen, gleicher Seed, alt gegen neu; `drt_vehicle_stats*.csv` und Carrier-Pläne byteweise gleich (Determinismus ist maschinenübergreifend bewiesen, also muss es auf einer Maschine exakt stimmen). ≈20 min.
3. **Hannover-Kette:** Schritt-A-Skript bis zum jsprit-Ergebnis, SHA-256 der Carrier-XML alt gegen neu.

Hashes und Kommandos kommen in den METHODS-LOG-Eintrag (§6, Schritt 7).

## 8. Ausrollen auf Sim, IVS100, Lausitz-VM

- Nur zwischen Läufen (MATSim hat keinen Wiederaufsetzpunkt).
- Für den Sim gilt die Pull-Sperre, solange Hannover-v4 nicht durch ist; vor dem Pull prüfen, nicht annehmen.
- Ablauf je Maschine: `git pull` → `git submodule update` (Gitlink unverändert, also ohne Netzlast) → `tools/migrate-input-layout.ps1` (schiebt `parcel-demand-2-matsim-pipeline/hagrid-input/*` idempotent nach `hagrid/input/{common,hannover,lausitz}`; bricht ab, wenn Ziel und Quelle beide belegt sind) → Outputs bleiben liegen (`git mv` des Ordners nimmt ignorierte Inhalte nicht mit; das Skript verschiebt auch `hagrid-output/` und `hagrid-matsim-output/` ins Modul) → `mvn -q install` → ein Fingerprint-Lauf wie §7.1 gegen den lokal notierten Wert.
- Auf IVS100 ohne Admin und mit `cmd.exe` als Standard-Shell: das Skript explizit über `powershell -File` starten.

## 9. Risiken

| Risiko | Einschätzung | Umgang |
|---|---|---|
| Rename-Erkennung von Git kippt bei kleinen Dateien, Historie reißt | mittel | Verschieben und Umbenennen strikt trennen (4a nur Pfad + zwei Zeilen); Stichproben mit `--follow` im Gate |
| Skript-Ersetzung trifft eine Stelle nicht (33 bat) | hoch, aber billig | Gate 5 startet jedes Skript bis zum Java-Start; `git grep` auf die alten Strings |
| Root-Erkennung auf einer Maschine schlägt fehl, weil `input/README.md` dort fehlt (ignorierter Ordner, Force-Add) | mittel | Datei ist getrackt, kommt per Pull; Migrationsskript prüft ihre Existenz |
| Parallele Session committet auf `hendrik`, während `restructure` läuft | bekannt | eigener Branch; vor dem Fast-Forward `git merge-tree` prüfen, bei Konflikt rebase statt merge |
| `docs/`-Ersetzung verfälscht Zitate von Laufordnern | niedrig | nur Ordnernamen des Repos ersetzen, keine Output-Pfade (`hagrid-matsim-output` bleibt ohnehin) |
| KPI-Dashboard-Trigger findet `build_kpis.py` nicht mehr | mittel | Gate 5 + ein Dispatch-Test existiert bereits (Board-Rework 2026-08-28) |

## 10. Folgepunkte (BACKLOG, nicht Teil dieses Umbaus)

1. **Fork-Hygiene + DRT/DVRP-Fork (User-Wunsch 2026-09-17):** neuer Fork-Branch `hagrid/2025.0` = Tag 2025.0 + `76a1638` + `3b5a493`, die zwei Fuel-Commits entfallen; Sparse-Checkout um `contribs/dvrp contribs/drt` erweitern; zwei POM-Shims `external/dvrp`, `external/drt` nach dem freight-Muster (dvrp hängt upstream an `common`, `ev`, `otfvis`; drt an `dvrp`, `common`, `otfvis`; die bleiben Release-Artefakte); Gitlink umhängen; alter Branch bleibt stehen (Shallow-Clones). Nach dem Umbau, weil Build-Änderung mit eigenem Risiko und weil der Fingerprint-Nachweis aus §7 dann als Kontrolle bereitsteht.
2. `HagridPaths` in Kern-Root-Erkennung + `HannoverPaths` + `LausitzPaths` aufteilen.
3. `HAGRIDSimulationConfig` / `SimulationRunnerUtils` in Kern-Basis + Studien-Teile zerlegen; danach schrumpft die Allowlist in §4.1 auf `HagridConfig` + `HAGRIDSimulationRunner`.
4. Konzeptname `drt_shareduse` → `drt_cargohitching` nur mit Alias in beide Richtungen (Python-Mapper, Laufordner, KPI-Dateinamen, METHODS-LOG-Zitate).
5. Offener Race-Fix `NetworkBasedTransportCosts:514` (`HashMap` → `ConcurrentHashMap`) im Fork: Verhaltensänderung, eigener Punkt.
6. Input-Bootstrap (Download-on-first-run mit Checksummen) aus dem Plan von 2026-07-13, Schritt 3.
