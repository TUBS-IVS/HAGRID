# Design: Maven-Modul nach `hagrid/simulation/` (Repo-Umbau, Teil 3)

**Datum:** 2026-09-21 (Review-Fassung, Punkte aus dem User-Review vom selben Tag eingearbeitet, siehe §11) · **Branch:** `hendrik` · **Vorgänger:** [2026-09-17-repo-restructure-design.md](2026-09-17-repo-restructure-design.md) (Teil 1: Pakete/Ordner) und Commit `e9d81d1` (Teil 2: Notebooks nach `analysis/hannover/notebooks/` und `hagrid/demand/`).

## 1. Ziel

`hagrid/` wird ein reiner Dachordner mit zwei Kindern:

```
hagrid/
├── demand/        Jupyter-Notebooks der Nachfrageschätzung (seit e9d81d1, unverändert)
└── simulation/    das Maven-Modul: pom.xml, src/, input/, hagrid-output/, hagrid-matsim-output/,
                   vmargs*.txt, logging/, target/ (lokal, wird neu gebaut), run_hagrid_sim.bat (generiert, lokal)
```

Mit Ausnahme des bereits bestehenden `hagrid/demand/` wandern die bisherigen Modulinhalte nach `hagrid/simulation/` oder an den Ort, an den sie inhaltlich gehören (§4).

**Neutralitätsanspruch, präzise:** Der Umbau ist hinsichtlich der Simulationslogik und der erzeugten fachlichen Ergebnisse verhaltensneutral (gleiche Klassen, gleiche Jar-Datei `target/hagrid-1.0-SNAPSHOT.jar`, gleiche Ausgaben). Änderungen betreffen ausschließlich Repository-Struktur, Pfadauflösung und lokale Artefaktablage; lokale Daten, Logs, Hilfsskripte und zwei Alt-Dokumente werden verschoben oder gelöscht (§4).

**Warum „simulation“ und nicht „matsim“:** Das Modul enthält neben MATSim auch jsprit, die Geo-Werkzeuge, die Java-Nachfrageaufbereitung (`hagrid.hannover.pipeline`) und die Legacy-Java-Dashboards. `simulation` deckt das ab, `matsim` nicht. Die Namensspannung zu `hagrid/demand` löst das README in einem Satz: `demand` = Schätzung in Notebooks, `simulation` = alles Ausführbare in Java.

## 2. Nicht-Ziele

- Keine Paketumbenennung, kein Klassenumzug, keine Änderung an `artifactId`, Jar-Namen oder `Main-Class`.
- Keine Änderung der Output-Ordnernamen `hagrid-output/` und `hagrid-matsim-output/`. Die Python-KPI-Schicht hängt an der Geschwisterkonvention `run_dir.parent.parent / "hagrid-output"`; sie bleibt gültig, weil beide Ordner gemeinsam wandern.
- Kein Aufräumen der toten `phd/sim-input/...`-Literale in `NetworkChangeEventFilterer` und `NetworkFilterAndReducer` (unbenutzte Konstanten, Backlog).
- Keine inhaltliche Überarbeitung von `PIPELINE_DOCUMENTATION.md` / `SETUP_TUTORIAL.md` (eigener Backlog-Punkt).
- Keine markerbasierte Repo-Wurzel-Erkennung in `KpiDashboardTrigger` (Backlog, §5.2).

## 3. Was auf den Modulpfad zeigt (gemessen am Stand `e9d81d1`)

| Bereich | Fundstellen | Änderung |
|---|---|---|
| Parent-POM `pom.xml:15` | `<module>hagrid</module>` | `<module>hagrid/simulation</module>` |
| `hagrid/pom.xml:12` | `<relativePath>../pom.xml</relativePath>` | `../../pom.xml` |
| `HagridPaths.java:58` | `PIPELINE_ROOT = "hagrid"` (Fall 3 = IDE-Fall, cwd = Repo-Wurzel; Fall 4 = Fallback) | `Paths.get("hagrid", "simulation")`; Marker `input/README.md` bleibt |
| `HagridPathsTest.java:401,415` | `isEqualTo(Path.of("hagrid"))` und Fixture `tempDir/hagrid/input/README.md` | `Path.of("hagrid","simulation")`, Fixture entsprechend |
| `KpiDashboardTrigger.scriptFor` | `pipelineRoot.getParent()` = Repo-Wurzel | `getParent().getParent()`; Tests siehe §5.2 |
| Startskripte `runs/**` | 22× `cd /d "%~dp0..\..\..\hagrid"`, 8× `cd /d "%~dp0..\..\hagrid"`, 3× absolut `…\HAGRID\hagrid"`, `run_chain_v2dev.bat` (`-WorkDir`, zwei Log-Pfade), `queue_chi_detour_rerun`-Wrapper (ein Log-Pfad), `run_lmd_band.ps1` (`hagrid\input\lausitz\demand`), `run_hagrid_sim_terminal.bat` (zwei Verweise auf das generierte Bat) | Suffix `\simulation` anhängen. 5× `mvn -pl hagrid` → `mvn -pl :hagrid` (§5.1). **Unverändert:** `set "JAR=target\hagrid-1.0-SNAPSHOT.jar"` (28×, cwd-relativ), `@vmargs*.txt`, `-Dhagrid.pipeline.root=.`, `hagrid-matsim-output\logs\…` (cwd-relativ), `cd /d "%~dp0..\.."` |
| `.gitignore` | 18 Zeilen mit Präfix `hagrid/` (Zeilen 48–70, 111, 140) | Präfix `hagrid/simulation/`; die `hagrid/demand/…`-Zeilen bleiben |
| `tools/check-run-scripts.ps1` | Default-Jar `hagrid\target\…` (Z. 152), `$generatedScripts = 'hagrid\run_hagrid_sim.bat'` (Z. 32), `-pl`-Regel | beide Pfade auf `hagrid\simulation\…`; `-pl :hagrid` als gültige Form; neue Alt-String-Regel für liegengebliebene Modulpfade (§6.5) |
| `tools/Test-CheckRunScripts.ps1` | 8 Fixture-Pfade | nachziehen, plus je ein Fall für `-pl :hagrid` und die neue Alt-String-Regel |
| `tools/migrate-input-layout.ps1` + Selbsttest | `$new = Join-Path $RepoRoot 'hagrid'`; Phase-A-Liste enthält `target` | `'hagrid\simulation'`; `target` aus der Liste (§5.3) |
| Run-Monitoring | `hc-config.template.json:6`, `resume-config.template.json:8-9` (Absolutpfade mit `\hagrid\`), `Test-Installers.ps1:188` (`T:\hagrid\input`, Laufwerksmapping-Test) | `\hagrid\simulation\`; der `T:`-Fall wird in Task 0 geprüft, nicht angenommen |
| Python | `analysis/lausitz/kpi/build_kpis.py:5` (Docstring-Beispiel), `tests/test_real_married250.py:21` (`REAL`-Pfad, ein `..` mehr), `kpi/data/README.md:7,109` | Pfade nachziehen |
| Docs | `README.md` (Baum + ~7 Zeilen), `docs/DATA-LAUSITZ.md` (6), `docs/BACKLOG.md` (8), `hagrid/input/README.md:1` | nachziehen; METHODS-LOG bekommt einen kurzen Eintrag (Nummer beim Schreiben ermitteln, §7) |
| Außerhalb des Repos | `%USERPROFILE%\hagrid-tools\chain_stepB.ps1` erhält `-WorkDir` aus `run_chain_v2dev.bat`; `%USERPROFILE%\hagrid-restructure-evidence\probe.ps1` hat `-Module` als Parameter | über das Skript-Rewrite abgedeckt bzw. Parameter |

Nicht betroffen, weil cwd-relativ zum Modul: `ArchitectureRulesTest.MAIN`, `SimulationRunnerUtils` (`hagrid-matsim-output/logs`), `HAGRID.java` (`hagrid-output/logs`), `SimulationBatGenerator` (schreibt `run_hagrid_sim.bat` in `pipelineRoot`, also künftig nach `hagrid/simulation/`), Surefire (cwd = Modulordner), `${maven.multiModuleProjectDirectory}` für `external/libs`. `.vscode/launch.json` und `tasks.json` nennen den Modulpfad nicht. Diese Liste ist der **Rewrite-Scope**; das abschließende Gate ist repo-weit (§6.5).

## 4. Was NICHT nach `hagrid/simulation/` gehört

| Heute in `hagrid/` | Befund (gemessen) | Ziel |
|---|---|---|
| `devlog/decide_theta.py`, `devlog/chosen_theta.txt` | Kampagnenhelfer, nur von `runs/lausitz/run_weekend_chain.bat:27,29` benutzt | `runs/lausitz/`; das Bat ruft `"%~dp0decide_theta.py"` statt `devlog\decide_theta.py`. Task 0 misst, wohin `decide_theta.py` seine `chosen_theta.txt` schreibt (cwd oder Skriptordner); Bat und Skript müssen denselben Ort meinen |
| `devlog/log4j2_dev.xml` | referenziert von `vmargs_dev.txt:13`; unterscheidet sich in 55 Zeilen von `logging/log4j2_runlocal.xml` | → `logging/log4j2_dev.xml`; `vmargs_dev.txt:13` → `logging/log4j2_dev.xml` |
| `devlog/log4j2.xml` | von nichts referenziert (repo-weit gemessen), unterscheidet sich von `log4j2_runlocal.xml` | löschen (bleibt in der Git-Historie). **Nicht** über `log4j2_runlocal.xml` schreiben, das würde die getrackte, dokumentierte Konfiguration ersetzen |
| `PIPELINE_DOCUMENTATION.md`, `SETUP_TUTORIAL.md` | veraltete Modul-Docs mit Stale-Banner; **0 Markdown-Links, keine Bilder** (gemessen) | `docs/legacy/hagrid/` unverändert (**Entscheidung A**); Link-Check im Plan ist damit ein Einzeiler, wird trotzdem ausgeführt |
| `pom_backup.xml` | Alt-POM mit `artifactId phd`, unbenutzt, getrackt (also in der Historie) | löschen (**Entscheidung B**) |
| `hagrid-matsim-analysis/` | leer seit `e9d81d1` | entfällt |
| `bin/` (790 KB, Eclipse-Ausgabe), `test/output/` (198 MB), `output/` (56 MB), `sim-input/` (2,3 GB), `sim-output/` (58 MB), `.pytest_cache/` | ignorierte Altlasten aus der `phd`-Zeit; nichts Aktives liest sie | nach `%USERPROFILE%\hagrid-parked-inputs\legacy-phd\` parken (**Entscheidung C**), Löschung separat nach Karenz |
| `routerCache/` (62 MB), `logs/` (74 MB) | von Läufen erzeugt, per `.gitignore` erwartet | mitnehmen nach `hagrid/simulation/` |
| `target/` (242 MB), `run_hagrid_sim.bat` (generiert) | Build-Artefakt bzw. von `SimulationBatGenerator` neu erzeugt | **nicht migrieren** (§5.3); am Quellort löschen |
| `build.log`, `build-package.log` | lokale Build-Protokolle, ignoriert | löschen |

## 5. Entwurfsentscheidungen

### 5.1 Maven-Selektor wird `-pl :hagrid`

Heute sind Modulpfad und `artifactId` beide `hagrid`; ein `-pl hagrid` vor dem Umbau beweist daher nichts über die Auflösung danach. Die fünf Skripte, die von der Repo-Wurzel aus `mvn -pl hagrid exec:java` starten, werden auf `-pl :hagrid` umgestellt (eindeutig die `artifactId`, stabil gegen weitere Verzeichnisänderungen). `check-run-scripts.ps1` akzeptiert die `:`-Form. Nachweis **nach** dem POM-Umzug: `mvn -q -pl :hagrid -am validate` und die Suite mit `-pl :hagrid -am`. Eigene Maven-Muster in Notizen und Docs ziehen nach.

### 5.2 Root-Erkennung

`detectPipelineRoot` behält seine vier Fälle. Fall 2 (Marker im cwd, der Bat-Fall) ist pfadunabhängig. Fall 3 (cwd = Repo-Wurzel, IDE-Fall) und Fall 4 (Fallback) liefern statt `hagrid` künftig `hagrid/simulation`. Der Property-Name `hagrid.pipeline.root` bleibt unangetastet.

`KpiDashboardTrigger.scriptFor` koppelt sich mit `getParent().getParent()` hart an zwei Ebenen. Für diesen Umbau akzeptiert, abgesichert durch Tests: (a) absoluter `pipelineRoot` `<tmp>/hagrid/simulation` → `<tmp>/analysis/lausitz/kpi/build_kpis.py`; (b) relativer `pipelineRoot` `.` mit cwd = Modulordner (der Bat-Fall) → zwei Ebenen über dem cwd; (c) der bestehende Test mit `Path.of("hagrid","simulation")`. Die Fälle „cwd = Repo-Wurzel“ und „`-Dhagrid.pipeline.root=.`“ sind Fälle von `detectPipelineRoot` und dort bereits getestet. Eine markerbasierte Repo-Wurzel-Erkennung kommt ins Backlog.

### 5.3 Zwei Migrationsskripte, keine Build-Artefakte

`tools/migrate-input-layout.ps1` behandelt weiter den Sprung vom Layout vor dem 17.09. (`parcel-demand-2-matsim-pipeline/hagrid-input/…`), nur mit Endziel `hagrid/simulation/` und **ohne** `target` in der Phase-A-Liste. Neu kommt `tools/migrate-module-layout.ps1` für Maschinen, die den ersten Umbau schon haben. Es bewegt `hagrid/{input, hagrid-output, hagrid-matsim-output, routerCache, logs}` nach `hagrid/simulation/` und löscht am Quellort `target/`, `run_hagrid_sim.bat`, `build*.log` (alles wird neu erzeugt). Reihenfolge auf jeder Maschine: `git pull` → `migrate-input-layout.ps1` (No-op, wenn schon geschehen) → `migrate-module-layout.ps1` → `mvn -q clean install`.

**Wiederanlauf ist eine Anforderung, kein Nebeneffekt.** Nach `git pull` existieren die Zielordner als `.gitkeep`-Skelette, also ist der Umzug kein einzelner Verzeichnis-Rename, sondern je Paar eine Folge von Renames der **ersten Ebene** (`Merge-Into` steigt nur dort ab, wo das Ziel schon existiert; Laufordner unter `hagrid-matsim-output/` werden als Ganzes umbenannt, nie durchlaufen). Daraus folgt:

- Preflight auf **Eintragsebene**: Kollision ist nur ein gleichnamiger Eintrag mit echtem Inhalt auf beiden Seiten. Der Zustand „Quelle halb leer, Ziel halb voll“ nach einem Abbruch ist keine Kollision, sondern der Normalfall eines Wiederanlaufs. (Das heutige `migrate-input-layout.ps1` prüft je Paar und würde einen Wiederanlauf fälschlich abbrechen; es wird auf dieselbe Eintragslogik gehoben.)
- Protokoll vor und nach dem Lauf: je Paar Anzahl Dateien, Anzahl Verzeichnisse, Gesamtgröße, geschrieben nach `hagrid/simulation/logs/migrate-module-layout-<Zeitstempel>.log`. Erwartung: Summe vorher = Summe nachher. Keine Hashes über 149 GB; Stichprobe: die fünf größten Dateien werden vorher und nachher mit Größe und `LastWriteTime` gelistet.
- Recovery ist die Umkehrtabelle: dieselben Paare rückwärts, dasselbe Skript mit `-Reverse`. Wird im Selbsttest mit einem simulierten Abbruch (Skript nach dem zweiten Paar unterbrochen, dann erneut gestartet, dann `-Reverse`) geprüft.
- Selbsttest `tools/Test-MigrateModuleLayout.ps1`: frisch, idempotent (zweiter Lauf No-op), Wiederanlauf nach Teilabbruch, echte Kollision → Abbruch mit Nennung, `-Reverse`.

### 5.4 Skript-Rewrites

Wie beim ersten Umbau: `.bat`/`.ps1`/`.txt`/`.json` byte-transparent (Latin-1 rein und raus, CRLF erhalten), `tools/*.ps1` ASCII-only ohne BOM, kein Edit/Write-Tool auf `.bat`. Rewrite-Scope ist die geschlossene Tabelle in §3; das Gate ist repo-weit (§6.5).

### 5.5 Windows-Pfadlängen: ein bestehendes Problem, das um elf Zeichen wächst

Gemessen in Task 0 (Dev, `task-0-report.md`): Repo-Wurzel 51 Zeichen, längster vorhandener Pfad unter `hagrid/hagrid-matsim-output/` **261 Zeichen** insgesamt (`CRASHED_20260909_it170_DRT_MODULAR_…/ITERS/it.123/…occupancy_time_profiles_StackedArea_drt.png`), **also heute schon über der klassischen Grenze**; nach dem Umbau **272** Zeichen. `LongPathsEnabled` ist auf dem Dev **nicht** gesetzt (Wert 0). Dateisystemtest bei der 273-Zeichen-Probe (ein Zeichen über dem realen Zielpfad): Java (`Files.writeString`/`readString`) schreibt und liest fehlerfrei; PowerShell 5.1 (`Set-Content`/`Get-Content`) wirft keinen Fehler, liefert beim Rücklesen aber leeren Inhalt zurück (Ergebnis unbewiesen); Python 3.13 (`open(p,'w')`) scheitert mit `FileNotFoundError`. `core.longpaths=true` betrifft nur Git.

Konsequenzen für den Plan (Task 0, je Zielmaschine):

1. Messen: absolute Repo-Wurzel + längster relativer Pfad unter `hagrid-matsim-output/` + 11.
2. `LongPathsEnabled` per `reg query` protokollieren.
3. Realer Dateisystemtest am längsten erwarteten Zielpfad: Verzeichnis anlegen, Datei schreiben, lesen, löschen, jeweils mit PowerShell 5.1 (das Migrationswerkzeug), Python (die KPI-Schicht) und Java (`Files.write`). Ergebnisse in die Ledger-Tabelle.
4. Für die Migration selbst reicht, dass `Move-Item` auf der ersten Ebene arbeitet (kurze Pfade); der Selbsttest enthält einen Laufordner mit einem Dateipfad > 260 Zeichen, den Java anlegt, und prüft, dass `Move-Item` des Laufordners gelingt und die Datei danach per Java lesbar ist.

Ein Befund > 260 mit einem fehlgeschlagenen Test in Schritt 3 ist ein Blocker für die betroffene Maschine, bis `LongPathsEnabled` gesetzt ist (Admin nötig, auf IVS100 nicht möglich, siehe Notiz zur Maschine) oder die Werkzeugkette den Pfad nachweislich nicht anfasst.

### 5.6 Worktree statt Stash

Die Arbeit läuft in einem eigenen Git-Worktree `..\HAGRID-r3` (Geschwisterordner, +3 Zeichen Pfad; `.worktrees/` im Repo würde +25 Zeichen kosten und §5.5 verschärfen) auf Branch `restructure-3` von `hendrik`. Damit entfällt das Stash-Verfahren für die fremden Docs-Änderungen vollständig, und die drei Docs bleiben im Haupt-Checkout unberührt. Preis: der Worktree enthält keine ignorierten Daten; für P1 und den Smoke-Test wird `hagrid/input/` (314 MB) per `robocopy` in den Worktree gespiegelt, `target/` wird dort neu gebaut. Gewinn: nach dem Fast-Forward ist der Haupt-Checkout auf dem Dev die **erste echte Maschine**, auf der `migrate-module-layout.ps1` gegen 149 GB läuft, mit dem Protokoll aus §5.3 als Beleg, bevor Sim, IVS100 und VM folgen.

Subagenten arbeiten weiterhin strikt nacheinander; nie zwei Implementierer im selben Arbeitsbaum.

## 6. Verhaltensneutralität: Belege

1. **Suite grün** aus geleertem `target/`: `mvn -q clean install` an der Repo-Wurzel, erwartet `hagrid` 657/0/0 und `external/freight` 272/0/0 (9 skip) wie am 18.09., plus die neuen Tests aus §5.2; dazu `mvn -q test -pl :hagrid -am` als Nachweis für §5.1.
2. **Statischer Skript-Check**: `tools/check-run-scripts.ps1` → `N Skripte geprueft, 0 Befunde` (N ≥ 57, N berichten), mit Positivkontrolle: ein Skript zeitweise auf den alten Pfad zurückgesetzt → genau ein Befund, dann restauriert. Selbsttests grün: `Test-CheckRunScripts` (8 + 2 neue Fälle), `Test-MigrateInputLayout` (19 + Wiederanlauf-Fall), `Test-MigrateModuleLayout` (neu, §5.3).
3. **P1-Probe** (`PrepareLausitzDrtInputs`, Minuten): `probe.ps1 -Module hagrid\simulation -Jar target\hagrid-1.0-SNAPSHOT.jar -Out after-part3 -PrepareClass hagrid.lausitz.drt.PrepareLausitzDrtInputs -HashOnly` nach eigenem Lauf, Hashes gegen `after\hashes.txt` vom 18.09. Erwartung: identisch bis auf die bekannte Properties-Zeitstempelzeile. Vergleichbar sind **6 P1-Zeilen** (die siebte entsteht erst im P2-Lauf).
4. **End-to-End-Smoke-Test** (neu): ein echter MATSim-Lauf über ein Startskript aus `runs/lausitz/`, `concept=drt_baseline, fleetSize=80, maxIter=1, freight=false, tag=r3smoke`, auf dem Dev. Geprüft werden: Config- und Inputauflösung (Lauf startet), log4j (Datei-Appender schreibt, Logdatei unter `hagrid-matsim-output/logs/` nicht leer), Iterationsausgabe (`hagrid-matsim-output/<run>/ITERS/it.0/`), `run_metadata.json`, und der Dashboard-Trigger (`KpiDashboardTrigger` startet `build_kpis.py` über zwei Ebenen, Dashboard-HTML existiert). Laufzeit wird in Task 0 gemessen (Erwartung: unter 30 min, weil ohne jsprit und LMD-Kostenmatrix). `routerCache/` wird von diesem Lauf nicht berührt; das deckt der Golden-Test der Fracht-Kette in der Suite ab.
5. **Kein P2.** Die 3,5-h-Probe wird nicht wiederholt; der Smoke-Test ersetzt sie für die Laufzeitpfade, die Suite und P1 für die Ergebnisse.
6. **Rewrite-Gate, repo-weit** (§6.5): Über **alle getrackten Dateien**, fünf Muster, die als Datei in `tools/gate-old-module-path.patterns` liegen (eine PCRE je Zeile; `git grep -f` kennt keine Kommentarzeilen, die Erklärung steht deshalb in `tools/gate-old-module-path.md`): (a1) `hagrid[\\/](?!simulation\b|demand\b|core\b|hannover\b|lausitz\b|\{|2025)` für String-Pfade (Wortgrenzen im Lookahead, damit `hagrid/simulation` am Tokenende kein Treffer ist; `2025` für die MATSim-Versionen), (a2) `[\\/]hagrid["']` für die von (a1) nicht gefangene End-of-Path-Form `..\hagrid"`, (b) `Paths?\.(of|get)\(\s*"hagrid"\s*(,\s*"(?!simulation")|\))` für strukturierte Java-Pfade, (c) `Join-Path\s+[^\r\n]*'hagrid'` für PowerShell, (d) `-pl\s+hagrid\b` für den alten Maven-Selektor. Aufruf aus Git Bash: `git grep -nIP -f tools/gate-old-module-path.patterns -- . <Allowlist>`, Allowlist `:!docs/superpowers :!docs/METHODS-LOG.md :!docs/BACKLOG-DONE.md :!docs/legacy :!.superpowers :!analysis/hannover/sweep/provenance :!tools/Migrate-Common.ps1 :!tools/migrate-*.ps1 :!tools/Test-Migrate*.ps1 :!tools/check-run-scripts.ps1 :!tools/Test-CheckRunScripts.ps1 :!tools/gate-old-module-path.* :!analysis/common/run-monitoring/Test-Installers.ps1` — Begründung je Eintrag: Migrations- und Prüfskripte samt Selbsttests und die Gate-Dateien selbst (enthalten die Muster bzw. den alten Pfad als Datum), historische Specs/Pläne unter `docs/superpowers/`, `analysis/hannover/sweep/provenance/*.patch`, METHODS-LOG/BACKLOG-DONE als Historie, `docs/legacy/` (eingefrorener Inhalt), `.superpowers/` (getrackte historische SDD-Berichte), `Test-Installers.ps1` (Fixture-String `T:\hagrid\input`, §12.3). §6.6 ist das repo-weite Grep-Gate; die Laufzeitregel in `tools/check-run-scripts.ps1` nutzt absichtlich `(?-i)hagrid[\\/](?!simulation|demand|core|hannover|lausitz|\{|2025)` ohne `\b`, weil Skriptzeilen nach `hagrid\simulation` immer ein Anführungszeichen oder einen Trenner tragen. Die absoluten Dev-Pfade in `run_chain_v2dev.bat`, `queue_chi_detour_wrap.bat` und `run_nightbc_wrap.bat` bleiben per Beschluss stehen (§12.14): das alte Muster (d) `\\HAGRID\\hagrid\b` meldete sie als erwartetes Residuum, das neue (d) fängt sie nicht mehr — Absolutpfade prüft seitdem allein `tools/check-run-scripts.ps1`. Positivkontrolle: bei `2dd4618` müssen die Muster treffen, mit dieser Allowlist gemessen a2 34 Dateien, b 2, c 1, d 5. Jeder Treffer außerhalb der Allowlist ist ein Befund.

## 7. Arbeitsbaum-Hygiene

Durch den Worktree (§5.6) bleiben `docs/BACKLOG.md`, `docs/METHODS-LOG.md` und `docs/PAPER-RUNS.md` im Haupt-Checkout mit den fremden, uncommitteten Änderungen der Emissions-Session unberührt. Eigene Doc-Änderungen entstehen im Worktree gegen den committeten Stand. Die METHODS-LOG-Nummer wird beim Schreiben aus der committeten Datei **und** dem Haupt-Checkout (`git -C <main> diff docs/METHODS-LOG.md`) ermittelt; die letzte Kollision kostete `03d6988`. Beim Fast-Forward ist ein Konflikt in diesen drei Dateien möglich, wenn die Emissions-Session bis dahin committet hat: dann normaler Merge-Konflikt, gelöst im Haupt-Checkout, nie mit `-X ours/theirs`.

## 8. Ausrollen: Rückkehrpunkt je Maschine

Nur zwischen Läufen, je Maschine (Dev-Hauptcheckout zuerst, dann Sim, IVS100, Lausitz-VM). Vor dem Rollout wird je Maschine im Ledger festgehalten: aktueller Commit (`git rev-parse HEAD`), laufende oder pausierte Läufe (keine!), Ergebnis von §5.5 Schritt 1–3, Preflight-Ausgabe des Migrationsskripts. Dann: `git pull` → `tools/migrate-input-layout.ps1` → `tools/migrate-module-layout.ps1` (Protokolldatei prüfen: Summen gleich) → `mvn -q clean install` → P1-Probe. Rückweg in dieser Reihenfolge: erst `git checkout <alter Commit>` (git legt das getrackte Skelett zurück), dann `migrate-module-layout.ps1 -Reverse` (die ignorierten Daten folgen in dieses Skelett), dann `mvn -q clean install`. Ein Git-Rollback allein stellt die lokalen Daten nicht zurück; das ist der Grund für `-Reverse`. IVS100 fährt laut Runplan vom 18.09. den Seed-Fächer: dort erst nach dem laufenden Arm. Der erste Umbau ist auf Sim, IVS100 und VM noch nicht ausgerollt; mit diesem Spec migrieren sie einmal statt zweimal.

## 9. Aufwand und Plan-Skizze

Etwa ein bis eineinhalb Arbeitstage subagentenbasiert (Smoke-Test, Wiederanlauf-Selbsttest und Pfadlängen-Messung kommen dazu), sechs Tasks: (0) Fakten messen: `decide_theta`-Schreibort, `T:`-Mapping, Pfadlängen und Dateisystemtest §5.5, Smoke-Laufzeit, Worktree + `robocopy` der Inputs; (1) `git mv` + POM + Java + Tests; (2) Skripte + Prüfskript + beide Migrationsskripte + Selbsttests; (3) `.gitignore` + Python + Docs + Entscheidungen A–C; (4) Belege §6 inkl. Smoke; (5) METHODS-LOG, Ausroll-Rezept, Backlog. Jeder Task mit eigenem Review, am Ende Gesamt-Review über den Branch, dann Fast-Forward nach `hendrik`, dann Migration des Dev-Hauptcheckouts als erster Rollout. Push nur auf Zuruf.

## 10. Entscheidungen (Empfehlung des Users vom 21.09., so übernommen)

- **A** Alte Modul-Docs nach `docs/legacy/hagrid/`, Banner bleibt, Link-Check läuft (0 Links gemessen).
- **B** `pom_backup.xml` löschen; die Datei ist getrackt, die Historie hält sie.
- **C** Altlasten `bin/`, `test/`, `output/`, `sim-input/`, `sim-output/` (≈2,7 GB) parken unter `%USERPROFILE%\hagrid-parked-inputs\legacy-phd\`; Löschung separat nach Karenz.

## 11. Review-Befunde und ihre Behandlung (21.09.)

Übernommen: `-pl :hagrid` statt Messung im alten Layout (§5.1); Pfadlängen mit realem Dateisystemtest je Maschine und ohne die Formel „kein Abbruch“, verschärft durch die Messung 262 > 260 heute (§5.5); End-to-End-Smoke-Test (§6.4); `target/` und generiertes Bat nicht migrieren (§5.3); Wiederanlauf, Protokoll, `-Reverse` und Eintragsebenen-Preflight als Anforderungen mit Selbsttest (§5.3); repo-weites Gate mit vier Mustern und Allowlist (§6.6); zwei `scriptFor`-Tests (§5.2); Legacy-Docs nach `docs/legacy/hagrid/` (§4); Worktree statt Stash (§5.6, §7); Rückkehrpunkt je Maschine (§8); beide Formulierungspräzisierungen (§1).

Abweichend übernommen: Das vorgeschlagene Logging-Mapping `devlog/log4j2.xml → logging/log4j2_runlocal.xml` hätte die getrackte, dokumentierte Datei überschrieben (beide unterscheiden sich, gemessen). `devlog/log4j2.xml` ist von nichts referenziert und wird gelöscht; nur `log4j2_dev.xml` zieht um (§4).

Nicht übernommen: Tests für „cwd = Repo-Wurzel“ und „Property gesetzt“ am `KpiDashboardTrigger`, weil das Fälle von `detectPipelineRoot` sind und dort schon getestet werden; markerbasierte Repo-Wurzel-Erkennung als Backlog statt Teil dieses Umbaus (§2). Der Hinweis auf kaputtes Markdown betraf den Export; die Datei ist sauberes Markdown.

## 12. Planungs- und Ausführungsbefunde (2026-09-24/25)

Befunde aus Planung und Ausführung von Task 0–4 (Worktree `HAGRID-r3`, Commit `73a8324`), numeriert 1–8 (Planungsstand), 9–16 (bei der Ausführung aufgetreten) und 17 (aus dem Gesamt-Review):

1. `runs/lausitz/queue_chi_detour_rerun.ps1:49` (`Join-Path $repo 'hagrid'`) fehlte in Spec §3; gefunden durch Gate-Muster (c).
2. Gate-Muster (a) erkannte die Form `..\hagrid"` am Pfadende nicht; das Gate hat jetzt fünf Muster (a1, a2, b, c, d, §6.6).
3. `T:\hagrid\input` in `Test-Installers.ps1` ist ein Fixture-String (allowlisted).
4. `decide_theta.py` schreibt `chosen_theta.txt` neben sich selbst → beide zusammen nach `runs/lausitz/` verschoben.
5. `probe.ps1` hat keinen P1-only-Modus → P1 von Hand + `-HashOnly`; P1 liefert **6** vergleichbare Zeilen, nicht 7 (§6.3 korrigiert).
6. Pfadlängen: heute **261**, nach dem Umbau **272** Zeichen; `LongPathsEnabled=0` auf dem Dev; Dateisystem-Probe bei 273 Zeichen (§5.5 korrigiert).
7. Die alte Migrations-Preflight prüfte je Paar statt je Datei → beide Skripte teilen sich jetzt `tools/Migrate-Common.ps1` mit dateiweiser `Find-Collisions`.
8. `devlog/log4j2.xml` war unreferenziert → gelöscht; `log4j2_dev.xml` → `logging/`.
9. Die `.gitignore`-Überarbeitung musste von Task 3 nach Commit 1a (Task 1) vorgezogen werden: sonst werden nach der Umbenennung 314 MB ignorierter Inputs sichtbar.
10. Windows PowerShell 5.1 verstümmelt native Exe-Argumente mit eingebettetem `"` → die Gate-Greps laufen aus Git Bash (zwei Muster meldeten unter PowerShell fälschlich 0 Treffer).
11. Drei Fehler im wörtlichen PowerShell des Plans, beim Ausführen gefunden: die neuen alten-String-Muster waren case-insensitive und trafen den Repo-Ordner `HAGRID` (behoben mit `(?-i)`); `cmd /c dir … 2>$null` wirft unter `Stop` bei leerem Baum (`2>nul` innerhalb von cmd nötig); `Get-Inventory` parste englische robocopy-Labels und lieferte auf deutschem Windows still 0 zurück (behoben durch Positionsparsing + Wurf + diskriminierenden Assert; Step 8 erneut gelaufen).
12. `decide_theta.py` berechnete seine Laufwurzel relativ zum eigenen Ordner (`OUT.parent`) — nach dem Umzug zeigte das auf ein nicht existierendes `runs/hagrid-matsim-output`, und die getrackte veraltete `chosen_theta.txt` hätte den Fehlschlag maskiert. Behoben: Wurzel = `OUT.parents[1]/hagrid/simulation/hagrid-matsim-output`; die Wochenendkette löscht die veraltete Datei vor der Entscheidung.
13. Der Selbsttest-Fall des Prüfskripts für `-pl :hagrid` war nicht diskriminierend (die alte Regex parste `:hagrid` nie); ein Negativfall `-pl :nosuchartifact` wurde ergänzt und mutationsgeprüft.
14. Die beiden Hannover-Stepskripte (`run_stepA_v2dev.bat`, `run_stepB_v2dev_batch.bat`) wechseln jetzt per `%~dp0` statt absolutem Dev-Pfad (sonst ist das Gate aus einem Worktree heraus unerreichbar; gleiches Verzeichnis auf dem Dev, portabel sonst). `run_chain_v2dev.bat` behält absolute Pfade für den externen Chainer.
15. Gate-Muster a1 nutzt Wortgrenzen im Lookahead (`simulation\b|demand\b|…|\{|2025`), damit `hagrid/simulation` am Tokenende kein Treffer ist; die Allowlist enthält zusätzlich `docs/legacy/` (eingefrorener Inhalt), `.superpowers/` (getrackte historische SDD-Berichte) und die Gate-Dateien selbst. Verbleibende a1-Treffer bei `e5cf0cb` sind Dachordner- bzw. Legacy-Referenzen und ein Test-DisplayName über das ALTE Layout — erklärt in `task-3-report.md`.
16. Das veraltete `hagrid/test/` (MATSim-Testausgabe vom Task-0-Build am alten Pfad) bleibt ungetrackt und ungestaged im Worktree; es verschwindet mit dem Worktree.
17. Das Bash-Werkzeug eines Agenten halbiert Backslashes in inline getippten Mustern (`[\\/]` kommt als `[\/]` an) → die Muster liegen jetzt als Datei vor (`tools/gate-old-module-path.patterns`, Aufruf mit `git grep -nIP -f …`); die Task-3-Tabelle hatte deshalb die Backslash-Treffer nicht gesehen, darunter `hagrid/simulation/analysis_vmargs.txt` (toter Fremdrechner-Classpath, gelöscht in der Fix-Welle).
18. Task 6 Step 4 (Dev-Rollout): `runs/lausitz/queue_chi_detour_rerun.ps1` trug den alten Selektor in PowerShell-Array-Form (`'-pl', 'hagrid'`, Zeilen 122/126). Weder das Gate-Muster d noch die Prüfskript-Regel `-pl\s+hagrid\b` sahen die Array-Form, und die pom-Existenzregel des Prüfskripts lief im Worktree gegen den absoluten Dev-Pfad `$repo` des Skripts — dort lag bis zum Fast-Forward noch das alte `hagrid/pom.xml`, deshalb „61 Skripte, 0 Befunde“. Erst der Checker-Lauf im migrierten Haupt-Checkout meldete es. Behoben im Rollout-Commit: `:hagrid` im Skript, Muster `-pl(\s+|',\s*')hagrid\b` in Prüfskript und Gate-Datei, Selbsttest Fall 13 (Array-Form als alter String, diskriminierend gegen die pom-Regel). Lehre: ein Prüfskript, das gegen absolute Pfade eines anderen Checkouts auflöst, prüft das falsche Repo.

Gemessene Zahlen im Überblick: Pfadlängen 261 → 272 Zeichen (Probe bei 273, `LongPathsEnabled=0`); Smoke-Laufzeit 14 min 15 s (`run_r3smoke.bat`, drt_baseline f80 maxIter=1 freight=false); Skript-Check **61** Skripte geprüft, 0 Befunde (43 runs + 9 run-monitoring + 9 tools).
