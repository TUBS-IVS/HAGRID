# Repo-Umbau Kern / Hannover / Lausitz — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Das HAGRID-Repo so umbauen, dass Kern, Hannover-Studie und Lausitz-Studie als Ordner und Java-Pakete sichtbar getrennt sind, ohne dass sich ein Laufergebnis ändert.

**Architecture:** Ein Maven-Modul (`parcel-demand-2-matsim-pipeline` → `hagrid/`) mit drei Wurzelpaketen `hagrid.core`, `hagrid.hannover`, `hagrid.lausitz`; Python-Analysen, Notebooks, Run-Skripte und Fremdcode ziehen in eigene Top-Level-Ordner. Jeder Schritt ist ein Commit auf dem Branch `restructure`, für sich baubar. Verhaltensneutralität wird durch drei Proben vor und nach dem Umbau belegt (Hash-Vergleich), nicht durch grüne Tests allein.

**Tech Stack:** Java 21, Maven 3.9 (Parent-POM + Modul), JUnit 5 + AssertJ, PowerShell 5.1 (Skripte, `.bat`-Schreiben), Python 3 + pytest (`analysis/lausitz/kpi/tests`), Git mit Rename-Erkennung.

**Spec:** `docs/superpowers/specs/2026-09-17-repo-restructure-design.md` (Design abgenommen 2026-09-17, Review-Fixes `ec984a1`).

## Global Constraints

- Arbeitsort ist der Haupt-Checkout `C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID`, **kein** Worktree: die ignorierten Inputs (≈315 MB) liegen nur dort. Branch `restructure` ab `hendrik`; Push nur auf Zuruf; kein Master-Merge.
- Alle Verschiebungen per `git mv`. Verschieben und inhaltliches Ändern gehören in getrennte Commits, damit `git log --follow` die Historie behält.
- `.bat`-Dateien nie mit Edit/Write anfassen; nur per PowerShell mit `[IO.File]::WriteAllText($p, $s, [Text.UTF8Encoding]::new($false))` schreiben (CRLF bleibt erhalten, kein BOM). Java-/XML-/PS1-Dateien ebenfalls ohne BOM.
- Der Sparse-Checkout-Befehl `git -C external/matsim-libs sparse-checkout set contribs/freight examples/scenarios/logistics-2regions` bleibt in README, Enforcer-Meldung und `tools/resync-freight.ps1` wörtlich identisch.
- Output-Wurzeln `hagrid-output/`, `hagrid-matsim-output/`, Konzeptname `drt_shareduse`, Package `shareduse`: **unverändert**.
- Maven-Tests immer als `mvn -q test -pl <modul> -am -Dsurefire.failIfNoSpecifiedTests=false` (ohne `-am` hängt Maven an der Remote-Auflösung des freight-SNAPSHOT).
- Keine Zerlegung von `HAGRIDSimulationConfig`/`SimulationRunnerUtils`/`HagridPaths`; fehlende Imports nach dem Paketumzug werden **nur** ergänzt, nichts wird umgeschrieben.
- Auf anderen Maschinen (Sim, IVS100, Lausitz-VM) passiert in diesem Plan **nichts**; Ausrollen ist Spec §8 und kommt nach dem Fast-Forward, zwischen Läufen.

## Abweichungen von der Spec (beim Planen gemessen, 2026-09-17)

1. **`HAGRID.java` und `HagridModule.java` gehen nach `hagrid.hannover`, nicht `hagrid.core`.** `HAGRID` importiert `pipeline.ScenarioConfig`/`ScenarioRunner`, `HagridModule` fünf `demand.*`-Klassen. Beide sind Hannover-Einstiege.
2. **Kein `hagrid.core.pipeline`.** `CacheConfig`, `PipelineTiming`, `PipelineLogger`, `RoutingStatistics` haben außerhalb von `pipeline`/`demand` **null** Verwender. Das ganze Paket `hagrid.pipeline` (11 Dateien inkl. `CarrierMergeLog`, `PipelineStatistics`, `ScenarioSummaryWriter`, `package-info`) wird `hagrid.hannover.pipeline`.
3. **Allowlist der Architekturregel ist gemessen anders als in Spec §4.1.** Kern-Klassen mit Referenzen in Studienpakete: `HagridPaths` (FQN `integrated.drt.DrtInputsFingerprint`), `HAGRIDScenarioBuilder` (FQN `integrated.drt.DrtConfigComposer`), `HAGRIDSimulationConfig` (Import `Modular` + FQN `DrtInputsFingerprint`), `SimulationRunnerUtils` (Imports `analysis.*` + ~35 FQN-Stellen nach `integrated.*`). `HagridConfig` und `HAGRIDSimulationRunner` referenzieren **nichts** aus den Studienpaketen und stehen daher nicht auf der Liste. Allowlist = `{HagridPaths, HAGRIDScenarioBuilder, HAGRIDSimulationConfig, SimulationRunnerUtils}`.
4. **`analysis/paper-figures/` ist komplett ungetrackt** (`.gitignore` Zeile 141). Der Umzug ist lokal; seine `PIPELINE = HERE.parent.parent.parent`-Konstanten werden lokal korrigiert, kein Commit.
5. **Die Python-Pfadarithmetik der KPI-Analyse bleibt gültig.** `build_kpis.py`, `extract_modular.py`, `extract_shareduse.py` rechnen `run_dir.parent.parent / "hagrid-output"`, also relativ zum Modul, und die Outputs bleiben im Modul. Nur `maps.py` (zwei `hagrid-input`-Zeilen) und `KpiDashboardTrigger` ändern sich.
6. **Artefakt-ID wird `hagrid`.** Das Jar heißt danach `hagrid-1.0-SNAPSHOT.jar`; das erlaubt `-pl hagrid` und einen einheitlichen `%JAR%`-Pfad. `SimulationBatGenerator.java:156` erzeugt den Jar-Namen und zieht nach.

Diese sechs Punkte werden in Task 8 als §11 an die Spec angehängt.

---

## Dateistruktur nach dem Umbau (Kurzreferenz für alle Tasks)

```
HAGRID/
├─ pom.xml                          Module: external/freight, hagrid
├─ hagrid/                          (git mv parcel-demand-2-matsim-pipeline hagrid)
│  ├─ pom.xml                       artifactId hagrid, shade mainClass hagrid.core.simulation.HAGRIDSimulationRunner
│  ├─ src/main/java/hagrid/{core,hannover,lausitz}/…
│  ├─ src/test/java/hagrid/{core,hannover,lausitz}/…
│  ├─ input/README.md               Root-Marker (Force-Add)
│  ├─ input/{common,hannover,lausitz}/…   ignoriert, .gitkeep-Skelett
│  ├─ hagrid-output/, hagrid-matsim-output/   unverändert
│  └─ vmargs.txt, vmargs_dev.txt, analysis_vmargs.txt   bleiben (CWD der Skripte ist hagrid/)
├─ analysis/{common/run-monitoring, hannover/{sweep,legacy-figures}, lausitz/{kpi,drt-headline,paper-figures,lmd}}
├─ notebooks/{demand-estimation,demand-estimation-batch,hannover-analysis}
├─ runs/{hannover,lausitz,lausitz/campaigns}
├─ external/{matsim-libs,freight,libs}
├─ tools/{resync-freight.ps1,setup_hagrid_io.bat,migrate-input-layout.ps1,check-run-scripts.ps1,Test-MigrateInputLayout.ps1,Test-CheckRunScripts.ps1}
└─ docs/
```

Evidenz-Ordner außerhalb des Repos (überlebt Sessions): `%USERPROFILE%\hagrid-restructure-evidence\{before,after}\`.

---

### Task 0: Branch anlegen und Vorher-Proben auf dem unveränderten Baum

**Files:**
- Create (außerhalb Repo): `%USERPROFILE%\hagrid-restructure-evidence\before\*`
- Create: `tools/` existiert noch nicht; das Probenskript lebt bis Task 6 im Evidenz-Ordner als `probe.ps1` und wird dort ausgeführt.

**Interfaces:**
- Produces: `probe.ps1 -Module <pfad-zum-modul> -Jar <jar> -Out <ordner>` schreibt `hashes.txt` (eine Zeile `<datei-relativ>  <sha256>`) und kopiert die Probenläufe. Task 8 ruft dasselbe Skript für `after` auf und vergleicht.

- [ ] **Step 1: Arbeitsbaum prüfen und Branch anlegen**

```powershell
cd "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
git status --short
```
Erwartet: nur die drei fremden Docs-Änderungen (`BACKLOG.md`, `METHODS-LOG.md`, `PAPER-RUNS.md`) und die bekannten ungetrackten Dateien. Falls andere Änderungen erscheinen: anhalten, User fragen.

```powershell
git stash push -m "restructure: fremde Docs-Änderungen geparkt" -- docs/BACKLOG.md docs/METHODS-LOG.md docs/PAPER-RUNS.md
git checkout -b restructure hendrik
git log --oneline -1
```
Erwartet: HEAD = `ec984a1` (oder neuer, falls die Parallel-Session committet hat; dann Hash notieren).

- [ ] **Step 2: Jar frisch bauen (Jar-Vintage ist eine bekannte Falle)**

```powershell
mvn -q -DskipTests install
Get-Item parcel-demand-2-matsim-pipeline\target\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar | Select-Object Name, LastWriteTime
```
Erwartet: `BUILD SUCCESS`, Jar-Zeitstempel = jetzt.

- [ ] **Step 3: Probenskript schreiben**

Datei `%USERPROFILE%\hagrid-restructure-evidence\probe.ps1`:

```powershell
param(
    [Parameter(Mandatory)][string] $Module,   # Modulordner (alt: parcel-demand-2-matsim-pipeline, neu: hagrid)
    [Parameter(Mandatory)][string] $Jar,      # relativ zu $Module
    [Parameter(Mandatory)][string] $Out,      # Zielordner before/ oder after/
    [Parameter(Mandatory)][string] $PrepareClass,  # alt hagrid.integrated.drt.PrepareLausitzDrtInputs, neu hagrid.lausitz.drt.PrepareLausitzDrtInputs
    [switch] $SkipHannover
)
$ErrorActionPreference = 'Stop'
Set-Location $Module
New-Item -ItemType Directory -Force $Out | Out-Null
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { $java = 'java' }

$scL = 'concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=2,jspritIter=10,freight=true,tag=rsprobe,kpiDashboard=false'
$scH = 'concept=basecase,date=2025-05-13,maxIter=1,jspritIter=10,tag=rsprobe,writeDashboard=false'

# Alte Probenläufe entfernen, damit copyIfMissing/Überspringen nichts verfälscht
Get-ChildItem hagrid-output, hagrid-matsim-output -Directory -Filter '*rsprobe*' -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force

# P1: Lausitz-Eingabekette
& $java '@vmargs_dev.txt' '-Dhagrid.pipeline.root=.' -cp $Jar $PrepareClass $scL
if ($LASTEXITCODE -ne 0) { throw "P1 prepare failed ($LASTEXITCODE)" }
# P2: Lausitz-Simulation, 2 Iterationen
& $java '@vmargs_dev.txt' '-Dhagrid.pipeline.root=.' -jar $Jar $scL
if ($LASTEXITCODE -ne 0) { throw "P2 sim failed ($LASTEXITCODE)" }
# P3: Hannover, nur wenn die geteilten Inputs lokal liegen
$hannoverOk = (Test-Path 'hagrid-output\shared\sim-config.xml') -and -not $SkipHannover
if ($hannoverOk) {
    & $java '@vmargs.txt' '-Dhagrid.pipeline.root=.' -jar $Jar $scH
    if ($LASTEXITCODE -ne 0) { throw "P3 hannover failed ($LASTEXITCODE)" }
} else {
    "P3 SKIPPED: hagrid-output\shared\sim-config.xml fehlt oder -SkipHannover" | Set-Content (Join-Path $Out 'P3-skipped.txt')
}

# Ergebnisse einsammeln und hashen. gz-Dateien werden entpackt gehasht (gzip-Header trägt keinen
# fachlichen Inhalt); die Fingerprint-Properties werden mit normalisiertem Input-Pfad verglichen.
function Get-Sha256Stream([IO.Stream] $s) {
    $h = [Security.Cryptography.SHA256]::Create()
    return ([BitConverter]::ToString($h.ComputeHash($s)) -replace '-', '').ToLower()
}
$lines = @()
$dirs = Get-ChildItem hagrid-output, hagrid-matsim-output -Directory -Filter '*rsprobe*'
foreach ($d in $dirs) {
    $dest = Join-Path $Out $d.Name
    Copy-Item $d.FullName $dest -Recurse -Force
    # Ausgeschlossen: Logs, Stopwatch/Timing (Laufzeit), output_config*.xml (absolute Input-Pfade),
    # run_meta.json (Zeitstempel + Pipeline-Root). Alles Fachliche bleibt drin.
    Get-ChildItem $d.FullName -Recurse -File | Where-Object {
        $_.Extension -in '.gz', '.csv', '.xml', '.properties' -and $_.Name -notmatch 'logfile|\.log$|warnings|stopwatch|scorestats|timing|config|run_meta'
    } | ForEach-Object {
        $rel = $_.FullName.Substring((Get-Location).Path.Length + 1)
        if ($_.Extension -eq '.gz') {
            $fs = [IO.File]::OpenRead($_.FullName)
            $gz = New-Object IO.Compression.GZipStream($fs, [IO.Compression.CompressionMode]::Decompress)
            $sha = Get-Sha256Stream $gz; $gz.Dispose(); $fs.Dispose()
        } elseif ($_.Name -like '*drt_inputs.properties') {
            $txt = (Get-Content $_.FullName -Raw) -replace 'hagrid-input[\\/]lausitz', 'input/lausitz' -replace 'parcel-demand-2-matsim-pipeline', 'hagrid' -replace '\\', '/'
            $ms = New-Object IO.MemoryStream(,[Text.Encoding]::UTF8.GetBytes($txt))
            $sha = Get-Sha256Stream $ms
        } else {
            $fs = [IO.File]::OpenRead($_.FullName); $sha = Get-Sha256Stream $fs; $fs.Dispose()
        }
        $lines += "{0}  {1}" -f $rel, $sha
    }
}
$lines | Sort-Object | Set-Content (Join-Path $Out 'hashes.txt') -Encoding ascii
"{0} Dateien gehasht" -f $lines.Count
```

- [ ] **Step 4: Vorher-Proben laufen lassen (≈15–30 min)**

```powershell
$ev = "$env:USERPROFILE\hagrid-restructure-evidence"
& "$ev\probe.ps1" -Module "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\parcel-demand-2-matsim-pipeline" `
    -Jar 'target\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar' -Out "$ev\before" `
    -PrepareClass 'hagrid.integrated.drt.PrepareLausitzDrtInputs'
Get-Content "$ev\before\hashes.txt" | Measure-Object -Line
Get-Content "$ev\before\hashes.txt" | Select-String 'drt_inputs.properties|drt_fleet|vehicle_stats|carriers' | Select-Object -First 8
```
Erwartet: mindestens die fünf `*_drt_*.xml.gz` aus dem Prepare-Schritt, `*_drt_inputs.properties`, `drt_vehicle_stats*.csv` und eine Carrier-Datei sind gehasht. Falls `P3-skipped.txt` existiert: das ist in Ordnung, wird in Task 8 dokumentiert.

- [ ] **Step 5: Probenläufe aus dem Repo entfernen (sie liegen jetzt in before/)**

```powershell
cd "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\parcel-demand-2-matsim-pipeline"
Get-ChildItem hagrid-output, hagrid-matsim-output -Directory -Filter '*rsprobe*' | Remove-Item -Recurse -Force
git status --short
```
Erwartet: Arbeitsbaum wie nach Step 1 (Proben-Outputs sind ignoriert, tauchen nicht auf).

---

### Task 1: Wurzel aufräumen und `.gitignore` für die neue Struktur

**Files:**
- Modify: `.gitignore` (Zeilen 33–80, 108–141)
- Delete (lokal, ungetrackt): Wurzel-PNGs, Dashboard-HTMLs, `*.log`, `org/`, `plots/`, `Pictures/`
- Create: `analysis/hannover/legacy-figures/.gitkeep`

- [ ] **Step 1: Ungetrackte Wurzel-Artefakte sichten**

```powershell
cd "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
git status --short --ignored -- . | Where-Object { $_ -match '^!! (Analyse_|HAGRID_Dashboard_|kneedle|plots/|Pictures/|org/|.*\.log$)' }
```
Erwartet: 17 PNG, 4 HTML, `kneedle_breakpoint_analysis.png`, `plots/`, `Pictures/`, `org/`, 10 Logs. Nichts davon ist getrackt (`git ls-files <name>` leer).

- [ ] **Step 2: Bilder nach legacy-figures verschieben, Logs und Klassendatei löschen**

```powershell
New-Item -ItemType Directory -Force analysis\hannover\legacy-figures | Out-Null
Move-Item Analyse_*.png, HAGRID_Dashboard_*.html, kneedle_breakpoint_analysis.png analysis\hannover\legacy-figures\
if (Test-Path plots)    { Move-Item plots    analysis\hannover\legacy-figures\plots }
if (Test-Path Pictures) { Move-Item Pictures analysis\hannover\legacy-figures\Pictures }
Remove-Item *.log, sync-freight.log -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force org
New-Item -ItemType File analysis\hannover\legacy-figures\.gitkeep | Out-Null
```

- [ ] **Step 3: Versehentlich getrackte Artefakte aus dem Index nehmen**

`*.log` ist ignoriert, `*.log.0`/`*.log.1` nicht: zwei GC-Logs sind getrackt. Dazu eine Backup-Kopie eines Batch-Skripts.

```powershell
git rm -q --cached parcel-demand-2-matsim-pipeline/logs/jvm_gc.log.0 parcel-demand-2-matsim-pipeline/logs/jvm_gc.log.1
git rm -q parcel-demand-2-matsim-pipeline/run_stepB_v2dev_batch.bat.bak_before_abort
```

- [ ] **Step 4: `.gitignore` — nur die Regeln, die zu diesem Task gehören**

Die `.gitignore` wird **je Task mit dem jeweiligen Umzug** angepasst (Task 2, 3b, 5a), sonst wären zwischen zwei Tasks tausende ignorierte Dateien plötzlich sichtbar. Hier:

```
30:    *.log                                       → darunter neue Zeile: *.log.[0-9]*
45:    parcel-analysis-batch                       → löschen (Ordner existiert nicht)
129-134: Root-level generated analysis artifacts   → # Legacy Hannover figures (moved from the repo root 2026-09-17; regenerated, not versioned)
                                                     analysis/hannover/legacy-figures/*
                                                     !analysis/hannover/legacy-figures/.gitkeep
```

- [ ] **Step 5: Prüfen, dass die Regeln greifen**

```powershell
git check-ignore -v analysis/hannover/legacy-figures/Analyse_Tours.png
git status --short
```
Erwartet: erste Zeile nennt die neue Regel; `git status` zeigt `.gitignore` (M), die drei Löschungen (D) und `analysis/hannover/legacy-figures/.gitkeep` (??), sonst nichts Neues.

- [ ] **Step 6: Commit**

```powershell
git add .gitignore analysis/hannover/legacy-figures/.gitkeep
git commit -m "chore(restructure): clear the repo root of stray figures, logs and a tracked GC log"
```

---

### Task 2: Fremdcode unter `external/` zusammenziehen

**Files:**
- Move: `freight/` → `external/freight/`; `libs/` → `external/libs/`
- Modify: `pom.xml` (Zeilen 14, 41), `external/freight/pom.xml` (Zeilen 133–134, 140, 154, 168–169)
- Modify: `README.md` (Zeile 114–115 nur Pfadnennung)

- [ ] **Step 1: Verschieben**

```powershell
cd "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
git mv freight external/freight
git mv libs external/libs
git status --short | Select-Object -First 6
```
Erwartet: Renames `R  freight/pom.xml -> external/freight/pom.xml`, `R  libs/... -> external/libs/...` (4 Dateien).

- [ ] **Step 2: Parent-POM**

`pom.xml` Zeile 14: `<module>freight</module>` → `<module>external/freight</module>`.
`pom.xml` Zeile 41: `<url>file:///${maven.multiModuleProjectDirectory}/libs</url>` → `<url>file:///${maven.multiModuleProjectDirectory}/external/libs</url>`.
Kommentar darüber (Zeile 38–40): `libs/` → `external/libs/`.

- [ ] **Step 3: freight-POM**

`external/freight/pom.xml`:
- Zeile 133: `${project.basedir}/../external/matsim-libs/contribs/freight/src/main/java` → `${project.basedir}/../matsim-libs/contribs/freight/src/main/java`
- Zeile 134: analog `…/src/test/java`
- Zeile 140: `${project.basedir}/../external/matsim-libs/examples/scenarios/logistics-2regions` → `${project.basedir}/../matsim-libs/examples/scenarios/logistics-2regions`
- Zeile 154: `${project.basedir}/../external/matsim-libs/contribs/freight` → `${project.basedir}/../matsim-libs/contribs/freight`
- Zeilen 168–169 (Enforcer, `${project.parent.basedir}/external/matsim-libs/…`): **unverändert** (kein `..`, kanonischer Vergleich).
- Zeile 172 (Enforcer-Meldung): unverändert, enthält den wörtlichen Sparse-Checkout-Befehl.

- [ ] **Step 4: freight-Modul bauen und testen**

```powershell
mvn -q install -pl external/freight -am
```
Erwartet: `BUILD SUCCESS`, Surefire-Zusammenfassung `Tests run: 272, Failures: 0, Errors: 0`. Falls der Enforcer „freight submodule missing“ meldet: Pfad in Zeile 168–169 prüfen, nicht die Enforcer-Regel entfernen.

- [ ] **Step 5: README-Pfadnennung und `.gitignore`**

`README.md` Zeile 129: ``**Bumping the MATSim/freight version:** see `resync-freight.ps1` (header comment).`` → ``see `tools/resync-freight.ps1` `` (das Skript zieht in Task 5b um; der Verweis wird hier vorgezogen, damit Task 6 das README nur noch einmal anfasst).

`.gitignore` Zeilen 108–109: `freight/output/`, `freight/test/output/` → `external/freight/output/`, `external/freight/test/output/`.

- [ ] **Step 6: Commit**

```powershell
git add pom.xml external README.md .gitignore
git commit -m "chore(restructure): gather third-party build glue under external/ (freight shim, matsim-lausitz repo)"
```

---

### Task 3a: `tools/migrate-input-layout.ps1` mit Selbsttest

**Files:**
- Create: `tools/migrate-input-layout.ps1`
- Create: `tools/Test-MigrateInputLayout.ps1`

**Interfaces:**
- Produces: `migrate-input-layout.ps1 [-RepoRoot <pfad>]` (Default: Elternordner von `tools/`). Idempotent. Exit 0 bei Erfolg, wirft bei Kollision (Quelle und Ziel beide nicht leer). Task 3b und Spec §8 rufen es auf.

- [ ] **Step 1: Selbsttest schreiben (rot, weil das Skript fehlt)**

`tools/Test-MigrateInputLayout.ps1`:

```powershell
# Selbsttest für migrate-input-layout.ps1: baut ein Fixture im alten Layout, migriert, prüft
# Ziel-Layout, Idempotenz (zweiter Lauf ändert nichts) und Kollisionsabbruch.
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'migrate-input-layout.ps1'
$fails = 0
function Assert($cond, $msg) { if ($cond) { Write-Host "  ok   $msg" } else { Write-Host "  FAIL $msg"; $script:fails++ } }

$tmp = Join-Path $env:TEMP ("mil-" + [guid]::NewGuid().ToString('N'))
$old = Join-Path $tmp 'parcel-demand-2-matsim-pipeline'
$new = Join-Path $tmp 'hagrid'
New-Item -ItemType Directory -Force $new | Out-Null            # der frische Pull hat hagrid/ schon angelegt
foreach ($d in 'config','demand','geodata','hubs','network','vehicles','emissions','lausitz\config','lausitz\drt') {
    New-Item -ItemType Directory -Force (Join-Path $old "hagrid-input\$d") | Out-Null
    Set-Content (Join-Path $old "hagrid-input\$d\probe.txt") $d
}
New-Item -ItemType Directory -Force (Join-Path $old 'hagrid-output\RUN1'), (Join-Path $old 'hagrid-matsim-output\RUN1'), (Join-Path $old 'routerCache') | Out-Null
Set-Content (Join-Path $old 'hagrid-output\RUN1\x.csv') 'x'

Write-Host "Fall 1: Migration aus altem Modulordner"
& $script -RepoRoot $tmp
Assert (Test-Path "$new\input\common\emissions\probe.txt")       'emissions -> input/common'
Assert (Test-Path "$new\input\hannover\config\probe.txt")        'config -> input/hannover'
Assert (Test-Path "$new\input\hannover\vehicles\probe.txt")      'vehicles -> input/hannover'
Assert (Test-Path "$new\input\lausitz\drt\probe.txt")            'lausitz/drt -> input/lausitz'
Assert (Test-Path "$new\hagrid-output\RUN1\x.csv")               'hagrid-output zieht mit'
Assert (Test-Path "$new\hagrid-matsim-output\RUN1")              'hagrid-matsim-output zieht mit'
Assert (Test-Path "$new\routerCache")                            'routerCache zieht mit'
Assert (-not (Test-Path "$new\hagrid-input"))                    'kein hagrid-input mehr im Modul'
Assert (-not (Test-Path $old))                                   'alter Modulordner ist weg (war leer)'

Write-Host "Fall 2: zweiter Lauf ist ein No-op"
$before = (Get-ChildItem $new -Recurse -File | ForEach-Object { $_.FullName + '|' + $_.LastWriteTimeUtc.Ticks }) -join "`n"
& $script -RepoRoot $tmp
$after = (Get-ChildItem $new -Recurse -File | ForEach-Object { $_.FullName + '|' + $_.LastWriteTimeUtc.Ticks }) -join "`n"
Assert ($before -eq $after) 'zweiter Lauf ändert keine Datei'

Write-Host "Fall 3: Kollision bricht ab"
New-Item -ItemType Directory -Force (Join-Path $old 'hagrid-input\config') | Out-Null
Set-Content (Join-Path $old 'hagrid-input\config\other.txt') 'y'
$threw = $false
try { & $script -RepoRoot $tmp } catch { $threw = $true }
Assert $threw 'Quelle und Ziel beide belegt -> Fehler'
Assert (-not (Test-Path "$new\input\hannover\config\other.txt")) 'bei Kollision wird nichts verschoben'

Remove-Item -Recurse -Force $tmp
if ($fails -gt 0) { Write-Host "$fails Prüfungen fehlgeschlagen"; exit 1 } else { Write-Host 'alle Prüfungen bestanden'; exit 0 }
```

- [ ] **Step 2: Selbsttest laufen lassen, erwartet rot**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Test-MigrateInputLayout.ps1
```
Erwartet: Abbruch beim ersten `& $script` (Datei fehlt), Exit ≠ 0.

- [ ] **Step 3: Skript schreiben**

`tools/migrate-input-layout.ps1`:

```powershell
<#
.SYNOPSIS
  Zieht die lokalen, git-ignorierten Inputs und Outputs vom alten Modulordner
  (parcel-demand-2-matsim-pipeline/hagrid-input/...) in die neue Gliederung
  (hagrid/input/{common,hannover,lausitz}). Idempotent; bricht bei Kollision ab.
.NOTES
  Läuft nach `git pull` auf jeder Maschine einmal (Spec 2026-09-17-repo-restructure-design.md §8).
  git mv nimmt ignorierte Dateien nicht mit, deshalb dieses Skript.
#>
param([string] $RepoRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'

$old = Join-Path $RepoRoot 'parcel-demand-2-matsim-pipeline'
$new = Join-Path $RepoRoot 'hagrid'
if (-not (Test-Path $new)) { throw "Zielmodul fehlt: $new (erst git pull / checkout)" }

function Move-Tree([string] $src, [string] $dst) {
    if (-not (Test-Path $src)) { return }
    if (Test-Path $dst) {
        $srcHas = @(Get-ChildItem $src -Force -Recurse -File).Count -gt 0
        $dstHas = @(Get-ChildItem $dst -Force -Recurse -File).Count -gt 0
        if ($srcHas -and $dstHas) { throw "Kollision: '$src' und '$dst' sind beide belegt. Von Hand zusammenführen." }
        if (-not $srcHas) { Remove-Item -Recurse -Force $src; return }
        Remove-Item -Recurse -Force $dst
    }
    New-Item -ItemType Directory -Force (Split-Path $dst -Parent) | Out-Null
    Move-Item -LiteralPath $src -Destination $dst
    Write-Host ("  {0} -> {1}" -f $src.Substring($RepoRoot.Length + 1), $dst.Substring($RepoRoot.Length + 1))
}

# 1) Ignorierte Reste aus dem alten Modulordner ins neue Modul
foreach ($d in 'hagrid-input', 'hagrid-output', 'hagrid-matsim-output', 'routerCache', 'logs', 'target') {
    Move-Tree (Join-Path $old $d) (Join-Path $new $d)
}

# 2) hagrid-input -> input/{common,hannover,lausitz}
$hi = Join-Path $new 'hagrid-input'
$in = Join-Path $new 'input'
Move-Tree (Join-Path $hi 'emissions') (Join-Path $in 'common\emissions')
foreach ($d in 'config', 'demand', 'geodata', 'hubs', 'network', 'vehicles') {
    Move-Tree (Join-Path $hi $d) (Join-Path $in "hannover\$d")
}
if (Test-Path (Join-Path $hi 'lausitz')) {
    foreach ($sub in Get-ChildItem (Join-Path $hi 'lausitz') -Directory) {
        Move-Tree $sub.FullName (Join-Path $in "lausitz\$($sub.Name)")
    }
    foreach ($f in Get-ChildItem (Join-Path $hi 'lausitz') -File -Force) {   # z.B. SOURCES.md, .gitkeep
        Move-Item -LiteralPath $f.FullName -Destination (Join-Path $in 'lausitz') -Force
    }
}
# Übrig gebliebene Dateien direkt unter hagrid-input (README, .gitkeep) sind Skelett: entfernen
if (Test-Path $hi) {
    $left = Get-ChildItem $hi -Recurse -File -Force | Where-Object { $_.Name -ne '.gitkeep' }
    if ($left) { throw "Unerwartete Dateien unter $hi : $($left.FullName -join ', ')" }
    Remove-Item -Recurse -Force $hi
}

# 3) Leeren alten Modulordner entfernen
if ((Test-Path $old) -and -not @(Get-ChildItem $old -Force -Recurse -File).Count) {
    Remove-Item -Recurse -Force $old
}

if (-not (Test-Path (Join-Path $in 'README.md'))) {
    Write-Warning "Root-Marker $in\README.md fehlt: ist der Checkout auf dem Umbau-Stand?"
}
Write-Host 'migrate-input-layout: fertig'
```

- [ ] **Step 4: Selbsttest grün**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Test-MigrateInputLayout.ps1
```
Erwartet: 12× `ok`, `alle Prüfungen bestanden`, Exit 0.

- [ ] **Step 5: Commit**

```powershell
git add tools/migrate-input-layout.ps1 tools/Test-MigrateInputLayout.ps1
git commit -m "chore(restructure): input-layout migration script with self-test (idempotent, aborts on collision)"
```

---

### Task 3b: Modul → `hagrid/`, Inputs → `input/{common,hannover,lausitz}`, Root-Marker

**Files:**
- Move: `parcel-demand-2-matsim-pipeline/` → `hagrid/` (`git mv`, nimmt auf Windows die ignorierten Inhalte mit, weil das Verzeichnis als Ganzes umbenannt wird)
- Modify: `pom.xml` Zeile 15; `hagrid/pom.xml` Zeilen 15, 17
- Create: `hagrid/input/README.md`; `.gitkeep` unter `hagrid/input/{common/emissions,hannover/*,lausitz/*}`
- Modify: `hagrid/src/main/java/hagrid/HagridPaths.java` (Zeilen 55, 97–100, 118–120, 140, sowie Javadoc 23–47)
- Modify: `hagrid/src/main/java/hagrid/utils/general/StudyArea.java` (Zeilen 7–8, 14, 25)
- Modify: `hagrid/src/main/java/hagrid/pipeline/ScenarioRunner.java` Zeile 90
- Modify: `hagrid/src/main/java/hagrid/utils/general/SimulationBatGenerator.java` Zeile 156
- Test: `hagrid/src/test/java/hagrid/HagridPathsTest.java` (Zeilen 96, 296, 338–357), `hagrid/src/test/java/hagrid/utils/general/StudyAreaTest.java` (Zeile 14)

**Interfaces:**
- Produces: `HagridPaths` löst den Root über `-Dhagrid.pipeline.root`, sonst Marker `input/README.md` im CWD, sonst `CWD/hagrid/input/README.md`, sonst Literal `hagrid`. `inputBase = <root>/input/<StudyArea.folder()>`. `StudyArea.HANNOVER.folder() == "hannover"`.

- [ ] **Step 1: Modul umbenennen und Inputs lokal migrieren**

```powershell
cd "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
git mv parcel-demand-2-matsim-pipeline hagrid
Test-Path hagrid\hagrid-input\lausitz\network   # ignorierte Inhalte sind mitgekommen?
powershell -NoProfile -ExecutionPolicy Bypass -File tools\migrate-input-layout.ps1
Get-ChildItem hagrid\input -Directory | Select-Object Name
Get-ChildItem hagrid\input\hannover, hagrid\input\lausitz, hagrid\input\common -Directory | Select-Object FullName
```
Erwartet: `True`; danach genau `common`, `hannover`, `lausitz`; darunter die 1 + 6 + 8 Unterordner aus Spec §5.1. Zweiter Aufruf des Skripts: nur „fertig“, keine Verschiebezeile.

- [ ] **Step 2: Skelett und Marker**

```powershell
foreach ($d in 'common\emissions','hannover\config','hannover\demand','hannover\geodata','hannover\hubs','hannover\network','hannover\vehicles','lausitz\config','lausitz\demand','lausitz\drt','lausitz\hubs','lausitz\network','lausitz\population','lausitz\transit','lausitz\vehicles') {
    New-Item -ItemType File -Force "hagrid\input\$d\.gitkeep" | Out-Null
}
git ls-files hagrid | Select-String 'hagrid-input/.*\.gitkeep' | ForEach-Object { git rm -q --cached $_.Line }   # alte Skelett-Einträge aus dem Index
```

`hagrid/input/README.md` (getrackt, dient `HagridPaths` als Root-Marker):

```markdown
# hagrid/input — Eingabedaten (nicht versioniert)

Diese Datei ist getrackt und dient dem Java-Code als Root-Marker (`HagridPaths`);
alle Unterordner sind git-ignoriert und werden lokal befüllt.

| Ordner | Studie | Herkunft |
|---|---|---|
| `common/emissions/` | beide | EMEP/EEA-Faktoren, siehe `SOURCES.md` dort und `analysis/lausitz/kpi/data/README.md` |
| `hannover/{config,demand,geodata,hubs,network,vehicles}/` | Hannover | Paketnachfrage 2025-05-13, Region-Hannover-Shapes, KEP-Hubs, MATSim-Netz; Aufbau siehe `tools/setup_hagrid_io.bat` |
| `lausitz/{config,demand,drt,hubs,network,population,transit,vehicles}/` | Lausitz | matsim-lausitz v2024.2 (öffentlich) + HAGRID-Nachfrage; Details in `docs/DATA-LAUSITZ.md` |

Migration von einem Checkout vor dem 2026-09-17: `tools/migrate-input-layout.ps1`.
```

```powershell
git add -f hagrid/input/README.md
git add hagrid/input
```

- [ ] **Step 3: POMs und `.gitignore`**

`pom.xml` Zeile 15: `<module>parcel-demand-2-matsim-pipeline</module>` → `<module>hagrid</module>`.
`hagrid/pom.xml` Zeile 15: `<artifactId>parcel-demand-2-matsim-pipeline</artifactId>` → `<artifactId>hagrid</artifactId>`; Zeile 17 `<name>` ebenso `hagrid`.

`.gitignore` (Zeilennummern wie im Original; nach Task 1 um eine Zeile verschoben):
```
49-56: parcel-demand-2-matsim-pipeline/{sim-input,input,routerCache,logs*,logs,sim-output,output}
                                            → hagrid/{sim-input,routerCache,logs*,logs,sim-output,output}
                                              (die Zeile ".../input" ENTFÄLLT — hagrid/input ist jetzt der echte Input-Ordner)
58-61: hagrid-input-Block                   → # input: ignore contents but keep folder structure via .gitkeep; README.md is the root marker
                                              hagrid/input/**
                                              !hagrid/input/**/
                                              !hagrid/input/**/.gitkeep
                                              !hagrid/input/README.md
63-71: hagrid-output / hagrid-matsim-output → Präfix parcel-demand-2-matsim-pipeline/ → hagrid/ (6 Zeilen)
112:   parcel-demand-2-matsim-pipeline/test/output/ → hagrid/test/output/
119:   parcel-demand-2-matsim-pipeline/analysis/**/*.html → hagrid/analysis/**/*.html   (zieht in Task 5a weiter)
124:   !parcel-demand-2-matsim-pipeline/analysis/hannover-sweep/board/index.html → !hagrid/analysis/hannover-sweep/board/index.html
127:   parcel-demand-2-matsim-pipeline/analysis/hannover-sweep/board/shot_*.png → hagrid/analysis/hannover-sweep/board/shot_*.png
141:   parcel-demand-2-matsim-pipeline/analysis/paper-figures/ → hagrid/analysis/paper-figures/
```
Kontrolle direkt danach: `git status --short | Measure-Object -Line` darf nicht um tausende Zeilen wachsen (dann greift eine Regel nicht).

- [ ] **Step 4: Tests zuerst anpassen (rot)**

`HagridPathsTest.java`:
- Zeile 96: `.endsWith("hagrid-input")` → `.endsWith(Path.of("input", "hannover").toString())`
- Zeile 296: `.contains("hagrid-input")` → `.contains("input")`
- Zeile 338: DisplayName `"default constructor uses HANNOVER and input/hannover layout"`; Zeile 342: `.endsWith("hagrid-input")` → `.endsWith(Path.of("input", "hannover").toString())`
- Zeilen 345–350 (`hannoverNoSubfolder`): DisplayName `"HANNOVER input base is input/hannover"`; Assertion `isEqualTo(tempDir.resolve("input").resolve("hannover"))`
- Zeile 353: DisplayName `"LAUSITZ_HOYERSWERDA input base is scoped under input/lausitz"`; Zeile 357: `tempDir.resolve("input").resolve("lausitz")`

`StudyAreaTest.java` Zeile 14: `assertThat(StudyArea.HANNOVER.folder()).isEmpty();` → `.isEqualTo("hannover");`

```powershell
mvn -q test -pl hagrid -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='HagridPathsTest,StudyAreaTest'
```
Erwartet: FAIL (5 + 1 Assertions).

- [ ] **Step 5: Produktionscode**

`StudyArea.java`: Zeile 14 `HANNOVER(""),` → `HANNOVER("hannover"),`; Javadoc Zeilen 7–8 und 25: „Input subfolder under `input/`“ (der Satz mit „empty string“ entfällt).

`HagridPaths.java`:
```java
// Zeile 55
private static final String PIPELINE_ROOT = "hagrid";
// neu direkt darunter
/** Tracked file that marks the module root; every input subfolder is git-ignored. */
private static final Path ROOT_MARKER = Paths.get("input", "README.md");

// Konstruktor, Zeilen 97–100 ersetzen durch
Path input = pipelineRoot.resolve("input");
this.inputBase = input.resolve(studyArea.folder());

// detectPipelineRoot(), Zeile 140
Path marker = ROOT_MARKER;
```
Javadoc Zeilen 23–47 und 113–128: `hagrid-input/` → `input/<hannover|lausitz>/`, Marker-Erwähnung `hagrid-input/config/config.xml` → `input/README.md`, `parcel-demand-2-matsim-pipeline` → `hagrid`. Kommentar Zeile 454 („all files now live in hagrid-input/“) → `input/`.

`ScenarioRunner.java` Zeile 90:
```java
this.configXmlPath = pipelineRoot.resolve("input").resolve("hannover").resolve("config").resolve("config.xml");
```

`SimulationBatGenerator.java` Zeile 156: `target\\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar` → `target\\hagrid-1.0-SNAPSHOT.jar`.

`FreightRunComposer.java` Zeile 36 (Kommentar): `hagrid-input/lausitz/config/` → `input/lausitz/config/`.

- [ ] **Step 6: Tests grün, dann volle Suite**

```powershell
mvn -q test -pl hagrid -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='HagridPathsTest,StudyAreaTest'
mvn -q install
```
Erwartet: beide grün; volle Suite `BUILD SUCCESS`; das Jar heißt `hagrid/target/hagrid-1.0-SNAPSHOT.jar`.

- [ ] **Step 7: Root-Erkennung real prüfen**

Ohne `-Dhagrid.pipeline.root`, damit die Marker-Erkennung selbst getestet wird (CWD = Modul, Fall 2 in `detectPipelineRoot`):

```powershell
cd hagrid
java '@vmargs_dev.txt' -cp target\hagrid-1.0-SNAPSHOT.jar hagrid.integrated.drt.PrepareLausitzDrtInputs 'concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=2,jspritIter=10,freight=true,tag=rootprobe' > $env:TEMP\rootprobe.log 2>&1
"exit=$LASTEXITCODE"; Get-ChildItem hagrid-output -Directory -Filter '*rootprobe*' | Select-Object Name
Select-String -Path $env:TEMP\rootprobe.log -Pattern 'NoSuchFile|hagrid-input|falling back' | Select-Object -First 3
cd ..
```
Erwartet: `exit=0`, ein Ordner `DRT_BASELINE_13052025_rootprobe…` mit fünf `*_drt_*.xml.gz`, und die letzte Zeile ist leer (kein Fallback, kein alter Pfad). Den Probelauf-Ordner danach löschen. Dasselbe einmal aus der Repo-Wurzel (Fall 3, `CWD/hagrid/input/README.md`): `cd ..; java … -cp hagrid\target\hagrid-1.0-SNAPSHOT.jar …` — erwartet ebenfalls `exit=0`.

- [ ] **Step 8: Commit (Verschiebung und Anpassung getrennt)**

```powershell
git add -A hagrid pom.xml
git commit -m "chore(restructure): rename the pipeline module to hagrid/ and split input/ into common, hannover, lausitz"
git log --oneline -1 --stat | Select-String 'rename' | Measure-Object -Line
```
Erwartet: mehrere hundert `rename … (100%)`-Zeilen; die geänderten Java-Dateien erscheinen als `rename … (9x%)`.

Hinweis: `git mv` eines Verzeichnisses plus Inhaltsänderung im selben Commit ist hier unvermeidbar (das Modul kompiliert sonst nicht). Die Rename-Ähnlichkeit bleibt > 90 %, `--follow` funktioniert; Stichprobe: `git log --follow --oneline hagrid/src/main/java/hagrid/HagridPaths.java | Measure-Object -Line` liefert > 1.

---

### Task 4a: Java-Pakete verschieben

**Files:**
- Move: alle Dateien unter `hagrid/src/main/java/hagrid/**` und `hagrid/src/test/java/hagrid/**` nach der Karte unten
- Modify: `package`-, `import`-Zeilen und voll qualifizierte Referenzen in `*.java`; `hagrid/pom.xml` Zeile 402 (`mainClass`); `hagrid/src/main/resources/log4j2.xml` (Logger `hagrid` bleibt, keine Änderung nötig)

**Interfaces:**
- Produces: Main-Klassen `hagrid.core.simulation.HAGRIDSimulationRunner`, `hagrid.hannover.HAGRID2MATSimPipelineRunner`, `hagrid.hannover.HAGRIDAnalysisRunner`, `hagrid.lausitz.drt.PrepareLausitzDrtInputs`, `hagrid.lausitz.freight.LausitzFreightPreprocessor`. Task 4b, 5b und 6 verwenden genau diese Namen.

**Verschiebekarte (main und test spiegelbildlich; Ausnahmen explizit):**

| Alt (`hagrid/…`) | Neu (`hagrid/…`) |
|---|---|
| `HagridPaths`, `HagridConfig`, `HAGRIDSimulationRunner` | `core/` bzw. `core/simulation/HAGRIDSimulationRunner` |
| `HAGRID`, `HagridModule`, `HAGRID2MATSimPipelineRunner`, `HAGRIDAnalysisRunner`, `ScenarioBuilder` | `hannover/` |
| `utils/GeoUtils`, `utils/demand/*`, `utils/network/*`, `utils/general/*` außer `Region`, `SimulationBatGenerator` | `core/util/` |
| `utils/general/Region`, `utils/general/SimulationBatGenerator` | `hannover/util/` |
| `utils/routing/*` + `simulation/{MaxRouteDurationConstraint,OpenRouteStateVerifier,RouteRealStartTimeMemorizer,TimeWindowConstraintWithDriverTime}` | `core/routing/` |
| `simulation/*` (Rest) außer `DrtScenarioBuilder`, `KpiDashboardTrigger`, `RunMetadataWriter` | `core/simulation/` |
| `simulation/{DrtScenarioBuilder,KpiDashboardTrigger,RunMetadataWriter}` | `lausitz/simulation/` |
| `pipeline/*` (alle 11) | `hannover/pipeline/` |
| `demand/*` | `hannover/demand/` |
| `analysis/*` | `hannover/analysis/` |
| `integrated/*` außer `PopulationClipper` | `lausitz/` |
| `integrated/PopulationClipper` | `core/util/` |
| `integrated/{drt,freight,modular,shareduse}/*` | `lausitz/{drt,freight,modular,shareduse}/` |
| Tests: `hagrid/{HagridConfigTest,HagridPathsTest,ScenarioEnumTest}` | `core/` |
| Tests: `freight/NetworkBasedTransportCostsGuardTest`, `utils/routing/*Test` | `core/routing/` |
| Tests: `utils/general/StudyAreaTest`, `integrated/PopulationClipperTest` | `core/util/` |
| Tests: `simulation/{DrtScenarioBuilderTest,RunMetadataWriterTest,HAGRIDSimulationConfigTest,ScenarioParsingTest,ParseScenario{Lmd,SharedUse,Budget,DrtFreight,Seed,OpenDepots,FreightWindows}Test,KpiDashboardTriggerTest}` | `lausitz/simulation/` |
| Tests: `simulation/{GenerateDashboardGuardTest,ParseScenarioKpiDashboardTest}` | `core/simulation/` |
| Tests: `pipeline/*Test`, `demand/*Test`, `analysis/*Test` | `hannover/pipeline/`, `hannover/demand/`, `hannover/analysis/` |
| Tests: `integrated/*Test` (Rest), `integrated/{drt,freight,modular,shareduse}/*` | `lausitz/`, `lausitz/{…}/` |

- [ ] **Step 1: Verschiebeskript im Scratch-Ordner anlegen und ausführen**

`%TEMP%\restructure-mv.ps1` (nicht committen):

```powershell
$ErrorActionPreference = 'Stop'
Set-Location "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\hagrid"
function Mv($from, $to) {
    $dir = Split-Path $to -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    git mv $from $to
    if ($LASTEXITCODE -ne 0) { throw "git mv $from $to" }
}
function MvDir($from, $to, [string[]] $except = @()) {
    foreach ($f in Get-ChildItem $from -File -Filter *.java) {
        if ($except -contains $f.Name) { continue }
        Mv $f.FullName (Join-Path $to $f.Name)
    }
}
foreach ($root in 'src/main/java/hagrid', 'src/test/java/hagrid') {
    $r = Resolve-Path $root
    # Wurzelklassen
    foreach ($n in 'HagridPaths','HagridConfig','HagridPathsTest','HagridConfigTest','ScenarioEnumTest') { if (Test-Path "$r/$n.java") { Mv "$r/$n.java" "$r/core/$n.java" } }
    if (Test-Path "$r/HAGRIDSimulationRunner.java") { Mv "$r/HAGRIDSimulationRunner.java" "$r/core/simulation/HAGRIDSimulationRunner.java" }
    foreach ($n in 'HAGRID','HagridModule','HAGRID2MATSimPipelineRunner','HAGRIDAnalysisRunner','ScenarioBuilder') { if (Test-Path "$r/$n.java") { Mv "$r/$n.java" "$r/hannover/$n.java" } }
    # utils
    if (Test-Path "$r/utils/GeoUtils.java") { Mv "$r/utils/GeoUtils.java" "$r/core/util/GeoUtils.java" }
    foreach ($d in 'demand','network') { if (Test-Path "$r/utils/$d") { MvDir "$r/utils/$d" "$r/core/util" } }
    if (Test-Path "$r/utils/general") {
        MvDir "$r/utils/general" "$r/core/util" -except 'Region.java','SimulationBatGenerator.java'
        foreach ($n in 'Region','SimulationBatGenerator') { if (Test-Path "$r/utils/general/$n.java") { Mv "$r/utils/general/$n.java" "$r/hannover/util/$n.java" } }
    }
    if (Test-Path "$r/utils/routing") { MvDir "$r/utils/routing" "$r/core/routing" }
    if (Test-Path "$r/freight") { MvDir "$r/freight" "$r/core/routing" }
    # simulation
    if (Test-Path "$r/simulation") {
        $toRouting = 'MaxRouteDurationConstraint.java','OpenRouteStateVerifier.java','RouteRealStartTimeMemorizer.java','TimeWindowConstraintWithDriverTime.java'
        $toLausitz = 'DrtScenarioBuilder.java','KpiDashboardTrigger.java','RunMetadataWriter.java',
                     'DrtScenarioBuilderTest.java','RunMetadataWriterTest.java','HAGRIDSimulationConfigTest.java','ScenarioParsingTest.java','KpiDashboardTriggerTest.java',
                     'ParseScenarioLmdTest.java','ParseScenarioSharedUseTest.java','ParseScenarioBudgetTest.java','ParseScenarioDrtFreightTest.java','ParseScenarioSeedTest.java','ParseScenarioOpenDepotsTest.java','ParseScenarioFreightWindowsTest.java'
        foreach ($f in Get-ChildItem "$r/simulation" -File -Filter *.java) {
            if ($toRouting -contains $f.Name)      { Mv $f.FullName "$r/core/routing/$($f.Name)" }
            elseif ($toLausitz -contains $f.Name)  { Mv $f.FullName "$r/lausitz/simulation/$($f.Name)" }
            else                                   { Mv $f.FullName "$r/core/simulation/$($f.Name)" }
        }
    }
    # hannover
    foreach ($d in 'pipeline','demand','analysis') { if (Test-Path "$r/$d") { MvDir "$r/$d" "$r/hannover/$d" } }
    # lausitz
    if (Test-Path "$r/integrated") {
        MvDir "$r/integrated" "$r/lausitz" -except 'PopulationClipper.java','PopulationClipperTest.java'
        foreach ($n in 'PopulationClipper','PopulationClipperTest') { if (Test-Path "$r/integrated/$n.java") { Mv "$r/integrated/$n.java" "$r/core/util/$n.java" } }
        foreach ($d in 'drt','freight','modular','shareduse') { if (Test-Path "$r/integrated/$d") { MvDir "$r/integrated/$d" "$r/lausitz/$d" } }
    }
}
# Leere Altordner entfernen
Get-ChildItem src -Recurse -Directory | Where-Object { -not (Get-ChildItem $_.FullName -Recurse -File) } | Sort-Object FullName -Descending | Remove-Item -Force
git status --short | Measure-Object -Line
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File $env:TEMP\restructure-mv.ps1
Get-ChildItem hagrid\src\main\java\hagrid -Directory | Select-Object Name
```
Erwartet: ~240 Renames; genau `core`, `hannover`, `lausitz` unter `src/main/java/hagrid`; kein `utils/`, `simulation/`, `integrated/`, `pipeline/`, `demand/`, `analysis/` mehr.

- [ ] **Step 2: Paketnamen umschreiben (package, import, FQN, Strings) — geordnet, mit Wortgrenze**

`%TEMP%\restructure-rewrite.ps1`:

```powershell
$ErrorActionPreference = 'Stop'
Set-Location "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
# Reihenfolge: spezifisch vor generisch. Lookahead verhindert, dass "hagrid.HAGRID" auch "hagrid.HAGRIDSimulationRunner" trifft.
$map = [ordered]@{
  'hagrid\.integrated\.PopulationClipper'                 = 'hagrid.core.util.PopulationClipper'
  'hagrid\.integrated\.'                                  = 'hagrid.lausitz.'
  'hagrid\.integrated(?![A-Za-z0-9_.])'                   = 'hagrid.lausitz'
  'hagrid\.utils\.general\.Region(?![A-Za-z0-9_])'        = 'hagrid.hannover.util.Region'
  'hagrid\.utils\.general\.SimulationBatGenerator'        = 'hagrid.hannover.util.SimulationBatGenerator'
  'hagrid\.utils\.general\.'                              = 'hagrid.core.util.'
  'hagrid\.utils\.general(?![A-Za-z0-9_.])'               = 'hagrid.core.util'
  'hagrid\.utils\.demand\.'                               = 'hagrid.core.util.'
  'hagrid\.utils\.demand(?![A-Za-z0-9_.])'                = 'hagrid.core.util'
  'hagrid\.utils\.network\.'                              = 'hagrid.core.util.'
  'hagrid\.utils\.network(?![A-Za-z0-9_.])'               = 'hagrid.core.util'
  'hagrid\.utils\.routing\.'                              = 'hagrid.core.routing.'
  'hagrid\.utils\.routing(?![A-Za-z0-9_.])'               = 'hagrid.core.routing'
  'hagrid\.utils\.GeoUtils'                               = 'hagrid.core.util.GeoUtils'
  'hagrid\.utils(?![A-Za-z0-9_.])'                        = 'hagrid.core.util'
  'hagrid\.freight(?![A-Za-z0-9_])'                       = 'hagrid.core.routing'
  'hagrid\.simulation\.(DrtScenarioBuilder|KpiDashboardTrigger|RunMetadataWriter)(?![A-Za-z0-9_])' = 'hagrid.lausitz.simulation.$1'
  'hagrid\.simulation\.(MaxRouteDurationConstraint|OpenRouteStateVerifier|RouteRealStartTimeMemorizer|TimeWindowConstraintWithDriverTime)(?![A-Za-z0-9_])' = 'hagrid.core.routing.$1'
  'hagrid\.simulation\.'                                  = 'hagrid.core.simulation.'
  'hagrid\.simulation(?![A-Za-z0-9_.])'                   = 'hagrid.core.simulation'
  'hagrid\.analysis\.'                                    = 'hagrid.hannover.analysis.'
  'hagrid\.analysis(?![A-Za-z0-9_.])'                     = 'hagrid.hannover.analysis'
  'hagrid\.demand\.'                                      = 'hagrid.hannover.demand.'
  'hagrid\.demand(?![A-Za-z0-9_.])'                       = 'hagrid.hannover.demand'
  'hagrid\.pipeline\.'                                    = 'hagrid.hannover.pipeline.'
  'hagrid\.pipeline(?![A-Za-z0-9_.])'                     = 'hagrid.hannover.pipeline'
  'hagrid\.HAGRIDSimulationRunner(?![A-Za-z0-9_])'        = 'hagrid.core.simulation.HAGRIDSimulationRunner'
  'hagrid\.(HAGRID2MATSimPipelineRunner|HAGRIDAnalysisRunner|ScenarioBuilder|HagridModule)(?![A-Za-z0-9_])' = 'hagrid.hannover.$1'
  'hagrid\.HAGRID(?![A-Za-z0-9_])'                        = 'hagrid.hannover.HAGRID'
  'hagrid\.(HagridPaths|HagridConfig)(?![A-Za-z0-9_])'    = 'hagrid.core.$1'
}
# package-Zeilen der verschobenen Dateien folgen dem Ordner, nicht der Map:
$javaFiles = Get-ChildItem hagrid\src -Recurse -Filter *.java
foreach ($f in $javaFiles) {
    $rel = $f.DirectoryName -replace '.*[\\/]java[\\/]', '' -replace '[\\/]', '.'
    $s = [IO.File]::ReadAllText($f.FullName)
    $s = [regex]::Replace($s, '(?m)^package\s+hagrid[A-Za-z0-9_.]*\s*;', "package $rel;")
    foreach ($k in $map.Keys) { $s = [regex]::Replace($s, $k, $map[$k]) }
    [IO.File]::WriteAllText($f.FullName, $s, [Text.UTF8Encoding]::new($false))
}
# POM: shade mainClass
$pom = 'hagrid\pom.xml'
$s = [IO.File]::ReadAllText($pom)
$s = $s -replace '<mainClass>hagrid\.HAGRIDSimulationRunner</mainClass>', '<mainClass>hagrid.core.simulation.HAGRIDSimulationRunner</mainClass>'
[IO.File]::WriteAllText($pom, $s, [Text.UTF8Encoding]::new($false))
"rewrite done: $($javaFiles.Count) java files"
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File $env:TEMP\restructure-rewrite.ps1
git grep -n -E 'hagrid\.(integrated|utils|demand|pipeline|analysis|simulation|freight)\.|hagrid\.HAGRID|hagrid\.Hagrid(Paths|Config)' -- 'hagrid/src' 'hagrid/pom.xml'
```
Erwartet: leer.

- [ ] **Step 3: Kompilieren; fehlende Imports ergänzen (gleiches Paket → jetzt getrennte Pakete)**

```powershell
mvn -q -DskipTests -pl hagrid -am compile test-compile 2>&1 | Select-String 'ERROR.*\.java' | Select-Object -First 40
```
Erwartet: Fehler der Art `cannot find symbol … HagridConfig` in Klassen, die ihren Nachbarn bisher ohne Import sahen. Bekannte Fälle: `HagridPaths`↔`HagridConfig` (bleiben zusammen in `core`, also keiner), `SimulationRunnerUtils` → `DrtScenarioBuilder`, `RunMetadataWriter`, `KpiDashboardTrigger` (jetzt `hagrid.lausitz.simulation`), `HAGRIDRouterUtils`-Umfeld → die vier Constraint-Klassen (jetzt im selben Paket `core.routing`, also keiner), `HAGRIDUtils`/`HAGRIDSummary` → `Region` (jetzt `hagrid.hannover.util.Region`), `HAGRID` → `HagridModule` (beide `hannover`, keiner), `HAGRIDSimulationRunner` → `HagridPaths`/`HagridConfig` (jetzt `hagrid.core`). Für jeden Fehler **genau eine** `import`-Zeile ergänzen, alphabetisch einsortiert. Nichts anderes ändern. Wiederholen bis:

```powershell
mvn -q -DskipTests -pl hagrid -am compile test-compile
```
Erwartet: kein Output (Erfolg).

- [ ] **Step 4: Volle Suite**

```powershell
mvn -q install
```
Erwartet: `BUILD SUCCESS`; Testanzahl Pipeline unverändert gegenüber Task 3b (Vergleich der Surefire-Zusammenfassung `Tests run:`), 0 Failures.

- [ ] **Step 5: Historie prüfen**

```powershell
foreach ($f in 'hagrid/src/main/java/hagrid/core/routing/HAGRIDRouterUtils.java','hagrid/src/main/java/hagrid/lausitz/shareduse/SharedUseModule.java','hagrid/src/main/java/hagrid/hannover/analysis/DashboardGenerator.java') { "$f : $((git log --follow --oneline -- $f | Measure-Object -Line).Lines) commits" }
```
Erwartet: jeweils deutlich > 1 (die alte Historie hängt dran).

- [ ] **Step 6: Commit**

```powershell
git add -A hagrid
git commit -m "refactor(restructure): move Java sources into hagrid.core / hagrid.hannover / hagrid.lausitz (paths, package and import lines only)"
```

---

### Task 4b: `ArchitectureRulesTest`

**Files:**
- Create: `hagrid/src/test/java/hagrid/core/ArchitectureRulesTest.java`

**Interfaces:**
- Consumes: Paketlayout aus Task 4a. Läuft mit Surefire-CWD = `hagrid/` (Standard).

- [ ] **Step 1: Test schreiben**

```java
package hagrid.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Architekturregel des Repos in zehn Zeilen (Spec 2026-09-17-repo-restructure-design.md §4.1):
 * <ol>
 *   <li>{@code hagrid.hannover} und {@code hagrid.lausitz} referenzieren einander nie.</li>
 *   <li>Aus {@code hagrid.core} zeigen nur die Schaltzentralen-Klassen in die Studienpakete.</li>
 * </ol>
 * Gescannt wird der gesamte Quelltext ohne Kommentare, nicht nur {@code import}-Zeilen,
 * damit voll qualifizierte Referenzen (z. B. in {@code HagridPaths}) mitzählen.
 */
@DisplayName("Architecture rules: core / hannover / lausitz")
class ArchitectureRulesTest {

    private static final Path MAIN = Path.of("src", "main", "java", "hagrid");
    private static final Set<String> CORE_SWITCHBOARD = Set.of(
            "HagridPaths", "HAGRIDScenarioBuilder", "HAGRIDSimulationConfig", "SimulationRunnerUtils");
    private static final Pattern COMMENTS = Pattern.compile("//.*?$|/\\*.*?\\*/", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Map<String, Pattern> STUDY_TOKENS = Map.of(
            "hannover", Pattern.compile("hagrid\\.hannover\\."),
            "lausitz", Pattern.compile("hagrid\\.lausitz\\."));

    private static Stream<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList().stream();
        }
    }

    private static String rootOf(Path file) {
        return MAIN.relativize(file).getName(0).toString();
    }

    private static String codeWithoutComments(Path file) throws IOException {
        return COMMENTS.matcher(Files.readString(file)).replaceAll("");
    }

    @Test
    @DisplayName("every main source lives under core, hannover or lausitz")
    void onlyThreeRoots() throws IOException {
        List<Path> strays = mainSources().filter(p -> !Set.of("core", "hannover", "lausitz").contains(rootOf(p))).toList();
        assertThat(strays).isEmpty();
    }

    @Test
    @DisplayName("hannover and lausitz never reference each other")
    void studiesAreIndependent() throws IOException {
        List<String> violations = mainSources()
                .filter(p -> Set.of("hannover", "lausitz").contains(rootOf(p)))
                .filter(p -> {
                    String other = rootOf(p).equals("hannover") ? "lausitz" : "hannover";
                    try { return STUDY_TOKENS.get(other).matcher(codeWithoutComments(p)).find(); }
                    catch (IOException e) { throw new RuntimeException(e); }
                })
                .map(Path::toString).toList();
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("only the switchboard classes in core reference a study package")
    void coreReachesStudiesOnlyViaSwitchboard() throws IOException {
        List<String> violations = mainSources()
                .filter(p -> rootOf(p).equals("core"))
                .filter(p -> !CORE_SWITCHBOARD.contains(p.getFileName().toString().replace(".java", "")))
                .filter(p -> {
                    try {
                        String code = codeWithoutComments(p);
                        return STUDY_TOKENS.values().stream().anyMatch(t -> t.matcher(code).find());
                    } catch (IOException e) { throw new RuntimeException(e); }
                })
                .map(Path::toString).toList();
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("the switchboard allowlist is not padded: every listed class really reaches a study package")
    void switchboardListIsTight() throws IOException {
        for (String name : CORE_SWITCHBOARD) {
            Path p = mainSources().filter(f -> f.getFileName().toString().equals(name + ".java")).findFirst().orElseThrow();
            String code = codeWithoutComments(p);
            assertThat(STUDY_TOKENS.values().stream().anyMatch(t -> t.matcher(code).find()))
                    .as(name + " is on the allowlist but references no study package — remove it from the list")
                    .isTrue();
        }
    }
}
```

- [ ] **Step 2: Laufen lassen**

```powershell
mvn -q test -pl hagrid -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ArchitectureRulesTest
```
Erwartet: 4/4 grün. Falls `coreReachesStudiesOnlyViaSwitchboard` rot ist: die gemeldete Datei ansehen. Ist es eine echte Referenz, gehört die Datei entweder nicht nach `core` (Verschiebekarte korrigieren, Task 4a-Commit per `--amend` ergänzen) oder auf die Allowlist (nur mit Begründung im Testkommentar und Notiz für Spec §11).

- [ ] **Step 3: Mutationsprobe (der Test muss etwas sehen können)**

In `hagrid/src/main/java/hagrid/core/HagridConfig.java` vorübergehend eine Zeile einfügen, z. B. nach der Klassendeklaration:
```java
    private static final String MUTATION_PROBE = hagrid.lausitz.drt.DrtInputsFingerprint.FILE_SUFFIX;
```
```powershell
mvn -q test -pl hagrid -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ArchitectureRulesTest
```
Erwartet: `coreReachesStudiesOnlyViaSwitchboard` **rot** mit `HagridConfig.java` in der Liste. Danach die Zeile entfernen, Test wieder grün.

- [ ] **Step 4: Ergänzender grep über Ressourcen und Tests (Spec §4.1, letzter Satz)**

```powershell
git grep -n -E 'hagrid\.(hannover|lausitz)\.' -- 'hagrid/src/main/resources' 'hagrid/src/test/resources'
git grep -l -E 'hagrid\.lausitz\.' -- 'hagrid/src/test/java/hagrid/hannover' ; git grep -l -E 'hagrid\.hannover\.' -- 'hagrid/src/test/java/hagrid/lausitz'
```
Erwartet: alle drei leer.

- [ ] **Step 5: Commit**

```powershell
git add hagrid/src/test/java/hagrid/core/ArchitectureRulesTest.java
git commit -m "test(restructure): pin the core/hannover/lausitz import rule with a source-token scan"
```

---

### Task 5a: Analysen und Notebooks umziehen, KPI-Trigger nachziehen

**Files:**
- Move: `hagrid/analysis/kpi` → `analysis/lausitz/kpi`; `hagrid/analysis/drt-headline` → `analysis/lausitz/drt-headline`; `hagrid/analysis/lmd` → `analysis/lausitz/lmd`; `hagrid/analysis/hannover-sweep` → `analysis/hannover/sweep`; `hagrid/analysis/run-monitoring` → `analysis/common/run-monitoring`; lokal `hagrid/analysis/paper-figures` → `analysis/lausitz/paper-figures`
- Move: `parcel-demand-estimation` → `notebooks/demand-estimation`; `parcel-demand-estimation-batch` → `notebooks/demand-estimation-batch`; `parcel-analysis` → `notebooks/hannover-analysis`
- Modify: `hagrid/src/main/java/hagrid/lausitz/simulation/KpiDashboardTrigger.java` (Zeilen 16, 75–76, 97); Test `KpiDashboardTriggerTest.java`
- Modify: `analysis/lausitz/kpi/maps.py` (Zeilen 266, 298); `analysis/lausitz/kpi/data/README.md` (Zeilen 7, 109); `analysis/hannover/sweep/provenance/README.md` (Zeile 25); `analysis/hannover/sweep/build_paper_analysis.py` (Zeile 266, Docstring); `analysis/common/run-monitoring/{hc-config,resume-config}.template.json`

**Interfaces:**
- Produces: `KpiDashboardTrigger.scriptFor(Path pipelineRoot)` → `<pipelineRoot>/../analysis/lausitz/kpi/build_kpis.py` (absolut, normalisiert).

- [ ] **Step 1: Test für den neuen Skriptpfad (rot)**

In `KpiDashboardTriggerTest.java` ergänzen:
```java
    @Test
    @DisplayName("scriptFor resolves build_kpis.py next to the module, under analysis/lausitz/kpi")
    void scriptForResolvesRepoLevelAnalysis() {
        Path script = KpiDashboardTrigger.scriptFor(Path.of("C:", "repo", "hagrid"));
        assertThat(script.normalize()).isEqualTo(Path.of("C:", "repo", "analysis", "lausitz", "kpi", "build_kpis.py"));
    }
```
```powershell
mvn -q test -pl hagrid -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=KpiDashboardTriggerTest
```
Erwartet: Compile-Fehler `scriptFor`.

- [ ] **Step 2: Trigger anpassen**

`KpiDashboardTrigger.java`:
```java
    /** The KPI builder lives at repo level since 2026-09-17: {@code <repo>/analysis/lausitz/kpi/build_kpis.py}. */
    static Path scriptFor(Path pipelineRoot) {
        Path repoRoot = pipelineRoot.toAbsolutePath().normalize().getParent();
        return repoRoot.resolve("analysis").resolve("lausitz").resolve("kpi").resolve("build_kpis.py");
    }
```
Zeile 76: `Path script = pipelineRoot.resolve("analysis").resolve("kpi").resolve("build_kpis.py");` → `Path script = scriptFor(pipelineRoot);`
Zeile 16 (Javadoc) und Zeile 97 (Warnmeldung `<pipeline>/analysis/kpi/build_kpis.py`) → `<repo>/analysis/lausitz/kpi/build_kpis.py`.
`RunMetadataWriter.java` Zeilen 19, 30 (Javadoc `analysis/kpi`) → `analysis/lausitz/kpi`. `IntegratedScenarioConfig.java` Zeile 28 ebenso.

```powershell
mvn -q test -pl hagrid -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='KpiDashboardTriggerTest,ParseScenarioKpiDashboardTest,GenerateDashboardGuardTest'
```
Erwartet: grün.

- [ ] **Step 3: Referenzwert der KPI-Pytests vor dem Umzug**

```powershell
python -m pytest hagrid\analysis\kpi\tests -q 2>&1 | Select-Object -Last 1
```
Zahl der bestandenen Tests notieren (Step 6 vergleicht dagegen).

- [ ] **Step 4: Ordner verschieben**

```powershell
cd "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
New-Item -ItemType Directory -Force analysis\lausitz, analysis\hannover, analysis\common, notebooks | Out-Null
git mv hagrid/analysis/kpi            analysis/lausitz/kpi
git mv hagrid/analysis/drt-headline   analysis/lausitz/drt-headline
git mv hagrid/analysis/lmd            analysis/lausitz/lmd
git mv hagrid/analysis/hannover-sweep analysis/hannover/sweep
git mv hagrid/analysis/run-monitoring analysis/common/run-monitoring
Move-Item hagrid\analysis\paper-figures analysis\lausitz\paper-figures     # ungetrackt, lokal
Get-ChildItem hagrid\analysis -Force | Select-Object Name                  # sollte leer sein bis auf __pycache__
Remove-Item -Recurse -Force hagrid\analysis
git mv parcel-demand-estimation       notebooks/demand-estimation
git mv parcel-demand-estimation-batch notebooks/demand-estimation-batch
git mv parcel-analysis                notebooks/hannover-analysis
```

- [ ] **Step 5: Python-Pfade und `.gitignore`**

`.gitignore` (Zeilennummern des Originals):
```
33-37: parcel-demand-estimation/…              → notebooks/demand-estimation/…   (5 Zeilen, Präfix tauschen)
40-43: parcel-demand-estimation-batch/**       → notebooks/demand-estimation-batch/**   (4 Zeilen)
47:    parcel-analysis/input                   → notebooks/hannover-analysis/input
73-80: parcel-analysis/…                       → notebooks/hannover-analysis/…   (6 Zeilen)
119:   hagrid/analysis/**/*.html               → analysis/**/*.html
124:   !hagrid/analysis/hannover-sweep/board/index.html → !analysis/hannover/sweep/board/index.html
127:   hagrid/analysis/hannover-sweep/board/shot_*.png  → analysis/hannover/sweep/board/shot_*.png
141:   hagrid/analysis/paper-figures/          → analysis/lausitz/paper-figures/
```
Kontrolle: `git status --short | Measure-Object -Line` bleibt in der Größenordnung der Renames; `git check-ignore -q analysis/lausitz/paper-figures/fig2_spatial_complementarity.py` ist wahr.

`analysis/lausitz/kpi/maps.py` Zeile 266: `/ "hagrid-input" / "lausitz" / "drt" /` → `/ "input" / "lausitz" / "drt" /`; Zeile 298: `/ "hagrid-input" / "lausitz" / "hubs" /` → `/ "input" / "lausitz" / "hubs" /`.
`analysis/lausitz/kpi/data/README.md` Zeile 7: `parcel-demand-2-matsim-pipeline/hagrid-input/emissions/` → `hagrid/input/common/emissions/`; Zeile 109: `hagrid-input/emissions/SOURCES.md` → `hagrid/input/common/emissions/SOURCES.md`.
`analysis/lausitz/kpi/build_kpis.py` Zeile 5 (Usage-Kommentar): `../../hagrid-matsim-output/…` → `../../../hagrid/hagrid-matsim-output/…`; `build_comparison.py` Zeile 4 analog `(from analysis/lausitz/kpi/)`.
`analysis/hannover/sweep/provenance/README.md` Zeile 25: `parcel-demand-2-matsim-pipeline/analysis/hannover-sweep/provenance/` → `analysis/hannover/sweep/provenance/`.
`analysis/hannover/sweep/build_paper_analysis.py` Zeile 266: `parcel-demand-2-matsim-pipeline/analysis/hannover-sweep/` → `analysis/hannover/sweep/`.
`analysis/common/run-monitoring/hc-config.template.json` Zeile 6 und `resume-config.template.json` Zeilen 8–10: `\\parcel-demand-2-matsim-pipeline` → `\\hagrid`.
Lokal (nicht committet): in `analysis/lausitz/paper-figures/emissions-services/{charging_feasibility,per_unit_emissions,pilot_spatial_km,spatial_emissions}.py` `PIPELINE = HERE.parent.parent.parent` → `REPO = HERE.parent.parent.parent.parent` mit den Folgezeilen `REPO / "analysis" / "lausitz" / "kpi"` bzw. `REPO / "hagrid" / …`; `grid_nox_sensitivity.py` Zeile 79 bleibt (`HERE.parent.parent / "kpi"` stimmt weiter); `fig2_spatial_complementarity.py`/`fig_abstract_spatial_complementarity.py` `"..", "..", ".."` → vier Ebenen; `spatial_correlation_stats.py` und `emissions-sos/scripts/zones.py` tragen absolute Repo-Pfade, unverändert.
Lokal: `analysis/hannover/sweep/board/node_modules` enthält pnpm-Shims mit absoluten Pfaden → bei nächster Nutzung `pnpm install` im `board/`-Ordner (ignoriert, kein Commit).

- [ ] **Step 6: KPI-Pytests**

```powershell
python -m pytest analysis\lausitz\kpi\tests -q 2>&1 | Select-Object -Last 1
```
Erwartet: dieselbe Zahl bestandener Tests wie in Step 3.

- [ ] **Step 7: Commit**

Ein Commit für Umzug und die ~20 Pfadzeilen: die geänderten Dateien sind groß genug, dass die Rename-Erkennung bei > 90 % bleibt (Kontrolle im `--stat`).

```powershell
git add -A analysis notebooks hagrid .gitignore
git commit -m "chore(restructure): move Python analyses to analysis/{common,hannover,lausitz}, notebooks to notebooks/; KPI trigger and maps.py follow"
git show --stat --format= HEAD | Select-String 'maps.py|KpiDashboardTrigger' 
```
Erwartet: beide Zeilen als `rename … (9x%)`.

---

### Task 5b: Run-Skripte nach `runs/`, Werkzeuge nach `tools/`, statischer Skripttest

**Files:**
- Move: Wurzel-Skripte und `hagrid/*.bat` nach `runs/hannover/`, `runs/lausitz/`, `runs/lausitz/campaigns/` (Liste in Spec §5.4); `resync-freight.ps1` → `tools/`; `hagrid/setup_hagrid_io.bat` → `tools/`
- Modify: jedes verschobene Skript (drei mechanische Ersetzungen + `cd`)
- Modify: `tools/resync-freight.ps1` (nach `param(...)` eine `Set-Location`)
- Create: `tools/check-run-scripts.ps1`, `tools/Test-CheckRunScripts.ps1`

**Interfaces:**
- Produces: `check-run-scripts.ps1 [-RepoRoot <pfad>] [-Scripts <ordner>]` prüft je Skript: `-pl <x>` → `<repo>/<x>/pom.xml` existiert; `JAR=<pfad>` → Datei relativ zum `cd`-Ziel existiert; Main-Klasse (nach `-cp "%JAR%"` oder `-Dexec.mainClass=`) → Eintrag `<klasse>.class` im Jar; `-Dhagrid.pipeline.root=<p>` → `<cd-Ziel>/<p>/input/README.md` existiert; kein Vorkommen der alten Strings. Exit 1 bei einem Befund.

- [ ] **Step 1: Skripttest-Selbsttest schreiben (rot)**

`tools/Test-CheckRunScripts.ps1`:
```powershell
$ErrorActionPreference = 'Stop'
$check = Join-Path $PSScriptRoot 'check-run-scripts.ps1'
$fails = 0
function Assert($cond, $msg) { if ($cond) { Write-Host "  ok   $msg" } else { Write-Host "  FAIL $msg"; $script:fails++ } }

$tmp = Join-Path $env:TEMP ("crs-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force "$tmp\hagrid\target", "$tmp\hagrid\input", "$tmp\runs\lausitz" | Out-Null
Set-Content "$tmp\hagrid\pom.xml" '<project/>'
Set-Content "$tmp\hagrid\input\README.md" 'marker'
# Mini-Jar mit genau einer Klasse
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = "$tmp\hagrid\target\hagrid-1.0-SNAPSHOT.jar"
$zip = [IO.Compression.ZipFile]::Open($jar, 'Create')
$e = $zip.CreateEntry('hagrid/core/simulation/HAGRIDSimulationRunner.class'); $e.Open().Dispose(); $zip.Dispose()

$good = "@echo off`r`ncd /d `"%~dp0..\..\hagrid`"`r`nset `"JAR=target\hagrid-1.0-SNAPSHOT.jar`"`r`njava -Dhagrid.pipeline.root=. -cp `"%JAR%`" hagrid.core.simulation.HAGRIDSimulationRunner concept=x`r`n"
$bad  = $good -replace 'hagrid\.core\.simulation\.HAGRIDSimulationRunner', 'hagrid.HAGRIDSimulationRunner'
[IO.File]::WriteAllText("$tmp\runs\lausitz\good.bat", $good, [Text.UTF8Encoding]::new($false))

Write-Host 'Fall 1: korrektes Skript'
& $check -RepoRoot $tmp -Scripts "$tmp\runs"; Assert ($LASTEXITCODE -eq 0) 'Exit 0 bei korrektem Skript'

Write-Host 'Fall 2: alte Main-Klasse'
[IO.File]::WriteAllText("$tmp\runs\lausitz\bad.bat", $bad, [Text.UTF8Encoding]::new($false))
& $check -RepoRoot $tmp -Scripts "$tmp\runs"; Assert ($LASTEXITCODE -ne 0) 'Exit 1 bei Klasse, die nicht im Jar liegt'
Remove-Item "$tmp\runs\lausitz\bad.bat"

Write-Host 'Fall 3: mvn -pl auf altes Modul'
[IO.File]::WriteAllText("$tmp\runs\lausitz\pl.bat", "mvn -pl parcel-demand-2-matsim-pipeline exec:java -Dexec.mainClass=hagrid.core.simulation.HAGRIDSimulationRunner`r`n", [Text.UTF8Encoding]::new($false))
& $check -RepoRoot $tmp -Scripts "$tmp\runs"; Assert ($LASTEXITCODE -ne 0) 'Exit 1 bei -pl auf fehlendes Modul'

Remove-Item -Recurse -Force $tmp
if ($fails -gt 0) { exit 1 } else { Write-Host 'alle Prüfungen bestanden'; exit 0 }
```
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Test-CheckRunScripts.ps1
```
Erwartet: Abbruch, `check-run-scripts.ps1` fehlt.

- [ ] **Step 2: `tools/check-run-scripts.ps1` schreiben**

```powershell
<#
.SYNOPSIS  Statische Prüfung aller Run-Skripte (runs/**). Kein Skript wird ausgeführt.
.NOTES     Spec 2026-09-17-repo-restructure-design.md §6 Gate 5.
#>
param(
    [string] $RepoRoot = (Split-Path $PSScriptRoot -Parent),
    [string] $Scripts  = (Join-Path (Split-Path $PSScriptRoot -Parent) 'runs')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$oldStrings = 'parcel-demand-2-matsim-pipeline', 'hagrid-input', 'hagrid\.integrated\.', 'hagrid\.HAGRID(?![A-Za-z])', 'hagrid\.simulation\.', 'hagrid\.utils\.'
$findings = New-Object System.Collections.Generic.List[string]
$jarCache = @{}
function JarHas([string] $jar, [string] $entry) {
    if (-not $jarCache.ContainsKey($jar)) {
        $z = [IO.Compression.ZipFile]::OpenRead($jar)
        $jarCache[$jar] = @($z.Entries | ForEach-Object { $_.FullName }); $z.Dispose()
    }
    return $jarCache[$jar] -contains $entry
}
$files = Get-ChildItem $Scripts -Recurse -File -Include *.bat, *.ps1
foreach ($f in $files) {
    $rel = $f.FullName.Substring($RepoRoot.Length + 1)
    $s = [IO.File]::ReadAllText($f.FullName)
    foreach ($o in $oldStrings) { if ($s -match $o) { $findings.Add("$rel : alter String '$o'") } }

    # cd-Ziel: Batch "cd /d "%~dp0<rel>"" oder PowerShell "Set-Location <abs>" / "$module = Join-Path $repo 'hagrid'"
    $cwd = $f.DirectoryName
    if ($s -match 'cd /d "%~dp0([^"]*)"') { $cwd = [IO.Path]::GetFullPath((Join-Path $f.DirectoryName $Matches[1])) }
    elseif ($s -match "cd /d `"([A-Za-z]:\\[^`"]+)`"") { $cwd = $Matches[1] }
    elseif ($s -match "Set-Location\s+\`$?root\b" -and $s -match "\`$root\s*=\s*'([^']+)'") { $cwd = $Matches[1] }

    foreach ($m in [regex]::Matches($s, '-pl\s+([A-Za-z0-9_./-]+)')) {
        if (-not (Test-Path (Join-Path $RepoRoot ($m.Groups[1].Value + '/pom.xml')))) { $findings.Add("$rel : -pl $($m.Groups[1].Value) hat kein pom.xml") }
    }
    $jar = $null
    if ($s -match 'set\s+"?JAR=([^"\r\n]+)"?') {
        $jar = [IO.Path]::GetFullPath((Join-Path $cwd $Matches[1]))
        if (-not (Test-Path $jar)) { $findings.Add("$rel : JAR fehlt: $jar"); $jar = $null }
    }
    $classes = @()
    $classes += [regex]::Matches($s, '-cp\s+"?%JAR%"?\s+([A-Za-z_][A-Za-z0-9_.]*)') | ForEach-Object { $_.Groups[1].Value }
    $classes += [regex]::Matches($s, '-Dexec\.mainClass="?([A-Za-z_][A-Za-z0-9_.]*)"?') | ForEach-Object { $_.Groups[1].Value }
    $classes += [regex]::Matches($s, "\`$(prepareClass|runClass)\s*=\s*'([A-Za-z_][A-Za-z0-9_.]*)'") | ForEach-Object { $_.Groups[2].Value }
    foreach ($c in $classes | Select-Object -Unique) {
        $entry = ($c -replace '\.', '/') + '.class'
        $j = $jar; if (-not $j) { $j = Join-Path $RepoRoot 'hagrid\target\hagrid-1.0-SNAPSHOT.jar' }
        if (-not (Test-Path $j)) { $findings.Add("$rel : kein Jar zum Prüfen von $c"); continue }
        if (-not (JarHas $j $entry)) { $findings.Add("$rel : Klasse $c nicht im Jar") }
    }
    foreach ($m in [regex]::Matches($s, '-Dhagrid\.pipeline\.root=([^\s"]+)')) {
        $root = [IO.Path]::GetFullPath((Join-Path $cwd $m.Groups[1].Value))
        if (-not (Test-Path (Join-Path $root 'input\README.md'))) { $findings.Add("$rel : pipeline.root $root ohne input\README.md") }
    }
}
"{0} Skripte geprüft, {1} Befunde" -f $files.Count, $findings.Count
$findings | ForEach-Object { "  $_" }
if ($findings.Count -gt 0) { exit 1 } else { exit 0 }
```
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Test-CheckRunScripts.ps1
```
Erwartet: 3× `ok`, Exit 0.

- [ ] **Step 3: Skripte verschieben**

```powershell
cd "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
New-Item -ItemType Directory -Force runs\hannover, runs\lausitz\campaigns, tools | Out-Null
# Hannover
git mv run_analysis.bat runs/hannover/; git mv track_sweep.ps1 runs/hannover/
foreach ($n in 'run_hagrid_sim.bat','run_hagrid_sim_terminal.bat','run_stepA_v2dev.bat','run_stepB_v2dev_batch.bat','run_chain_v2dev.bat') { git mv "hagrid/$n" "runs/hannover/$n" }
# Lausitz, Wurzel
foreach ($n in 'run_drt_baseline.bat','run_lmd_baseline.bat','run_lmd_band.ps1','run_nightbc.bat','run_nightbc_wrap.bat','queue_chi_detour_rerun.ps1','queue_chi_detour_wrap.bat') { git mv $n "runs/lausitz/$n" }
# Lausitz, Modul (Kampagnen-Einmalskripte nach campaigns/)
$campaign = 'run_1d_f120_s3337.bat','run_bud.bat','run_bud2.bat','run_bud3.bat','run_bud4.bat','run_cap1d_chain.bat','run_cap1d_retry.bat','run_conv1d_chain.bat','run_conv1d_wrap.bat','run_dur20_A.bat','run_dur20_B.bat','run_dur40.bat','run_dur70_chain.bat','run_f150t015_dev.bat','run_fleet1d_b_chain.bat','run_fleet1d_chain.bat','run_fleet1d_wrap.bat','run_match1d_chain.bat','run_seedfan1d.bat','run_theta1d_chain.bat','run_tourdur_chain.bat','run_w1117.bat'
$lausitz  = 'run_base_f140_dev.bat','run_convbase_chain.bat','run_depot1c_chain.bat','run_depot1c_wrap.bat','run_depot1d_chain.bat','run_weekend_chain.bat'
foreach ($n in $campaign) { if (git ls-files --error-unmatch "hagrid/$n" 2>$null) { git mv "hagrid/$n" "runs/lausitz/campaigns/$n" } else { Move-Item "hagrid\$n" "runs\lausitz\campaigns\$n"; git add "runs/lausitz/campaigns/$n" } }
foreach ($n in $lausitz)  { if (git ls-files --error-unmatch "hagrid/$n" 2>$null) { git mv "hagrid/$n" "runs/lausitz/$n" }           else { Move-Item "hagrid\$n" "runs\lausitz\$n";           git add "runs/lausitz/$n" } }
# Werkzeuge
git mv resync-freight.ps1 tools/; git mv hagrid/setup_hagrid_io.bat tools/
Get-ChildItem hagrid\*.bat, hagrid\*.ps1     # erwartet: leer (vmargs*.txt bleiben absichtlich im Modul)
```

- [ ] **Step 4: Die drei mechanischen Ersetzungen plus `cd` per Skript**

`%TEMP%\restructure-scripts.ps1`:
```powershell
$ErrorActionPreference = 'Stop'
$repo = "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
$classMap = [ordered]@{
  'hagrid\.integrated\.drt\.PrepareLausitzDrtInputs'        = 'hagrid.lausitz.drt.PrepareLausitzDrtInputs'
  'hagrid\.integrated\.freight\.LausitzFreightPreprocessor' = 'hagrid.lausitz.freight.LausitzFreightPreprocessor'
  'hagrid\.HAGRIDSimulationRunner(?![A-Za-z])'              = 'hagrid.core.simulation.HAGRIDSimulationRunner'
  'hagrid\.HAGRID2MATSimPipelineRunner'                     = 'hagrid.hannover.HAGRID2MATSimPipelineRunner'
  'hagrid\.HAGRIDAnalysisRunner'                            = 'hagrid.hannover.HAGRIDAnalysisRunner'
  'hagrid\.integrated\.modular'                             = 'hagrid.lausitz.modular'     # nur in rem-Kommentaren
}
foreach ($f in Get-ChildItem "$repo\runs" -Recurse -File -Include *.bat,*.ps1) {
    $s = [IO.File]::ReadAllText($f.FullName)
    $depth = ($f.FullName.Substring($repo.Length + 1) -split '[\\/]').Count - 1   # runs\lausitz\x.bat -> 2, campaigns -> 3
    $up = ('..\' * $depth).TrimEnd('\')
    # (1) Maven-Modul
    $s = $s -replace '-pl parcel-demand-2-matsim-pipeline', '-pl hagrid'
    # (2) Jar-Pfad
    $s = $s -replace 'parcel-demand-2-matsim-pipeline-1\.0-SNAPSHOT\.jar', 'hagrid-1.0-SNAPSHOT.jar'
    # (3) Main-Klassen
    foreach ($k in $classMap.Keys) { $s = [regex]::Replace($s, $k, $classMap[$k]) }
    # cd-Ziel: Wurzelskripte wollten die Repo-Wurzel, Modulskripte das Modul
    if ($s -match '(?m)^mvn |^call mvn |& mvn ') { $s = $s -replace 'cd /d "%~dp0"', ('cd /d "%~dp0' + $up + '"') }
    else                                            { $s = $s -replace 'cd /d "%~dp0"', ('cd /d "%~dp0' + $up + '\hagrid"') }
    # absolute Pfade (Wrapper, PS1)
    $s = $s -replace 'GitHub\\HAGRID\\parcel-demand-2-matsim-pipeline', 'GitHub\HAGRID\hagrid'
    $s = $s -replace "Join-Path \`$repo 'parcel-demand-2-matsim-pipeline'", "Join-Path `$repo 'hagrid'"
    $s = $s -replace "'parcel-demand-2-matsim-pipeline\\hagrid-input\\lausitz\\demand'", "'hagrid\input\lausitz\demand'"
    $s = $s -replace 'GitHub\\HAGRID\\run_nightbc\.bat', 'GitHub\HAGRID\runs\lausitz\run_nightbc.bat'
    $s = $s -replace 'GitHub\\HAGRID\\queue_chi_detour_rerun\.ps1', 'GitHub\HAGRID\runs\lausitz\queue_chi_detour_rerun.ps1'
    [IO.File]::WriteAllText($f.FullName, $s, [Text.UTF8Encoding]::new($false))
}
# tools/setup_hagrid_io.bat: Modulname + Zielordner
$p = "$repo\tools\setup_hagrid_io.bat"; $s = [IO.File]::ReadAllText($p)
$s = $s -replace 'set PIPELINE=parcel-demand-2-matsim-pipeline', 'set PIPELINE=hagrid'
$s = $s -replace '\\hagrid-input\\(config|demand|geodata|hubs|network|vehicles)', '\input\hannover\$1'
$s = $s -replace '\\hagrid-input\\', '\input\'
[IO.File]::WriteAllText($p, $s, [Text.UTF8Encoding]::new($false))
# vmargs-Dateien im Modul tragen den Modulnamen (z. B. Log-Pfade); bleiben liegen, nur der String ändert sich
foreach ($v in Get-ChildItem "$repo\hagrid\*vmargs*.txt") {
    $s = [IO.File]::ReadAllText($v.FullName) -replace 'parcel-demand-2-matsim-pipeline', 'hagrid'
    [IO.File]::WriteAllText($v.FullName, $s, [Text.UTF8Encoding]::new($false))
}
'scripts rewritten'
```
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File $env:TEMP\restructure-scripts.ps1
git grep -n -E 'parcel-demand-2-matsim-pipeline|hagrid-input|hagrid\.integrated\.|hagrid\.HAGRID[A-Za-z]*(?!\.)' -- 'runs/**' 'tools/setup_hagrid_io.bat'
```
Erwartet: leer. Stichprobe von Hand: `runs\lausitz\campaigns\run_depot1d_chain.bat` beginnt mit `cd /d "%~dp0..\..\..\hagrid"`, `runs\hannover\run_analysis.bat` mit `cd /d "%~dp0..\.."`, `runs\lausitz\run_lmd_band.ps1` hat `Join-Path $root 'hagrid\input\lausitz\demand'`.

- [ ] **Step 5: `tools/resync-freight.ps1` arbeitsfähig aus `tools/`**

Nach dem `param(...)`-Block (nach Zeile 14) einfügen:
```powershell
# Das Skript liegt seit 2026-09-17 in tools/, arbeitet aber mit repo-relativen Pfaden (.gitmodules, external/matsim-libs).
Set-Location (Split-Path $PSScriptRoot -Parent)
```
Kopfkommentar Zeile 7: `.\resync-freight.ps1` → `.\tools\resync-freight.ps1`. Sonst nichts (der Sparse-Checkout-Befehl in Zeilen 42 und 81 bleibt wörtlich).

- [ ] **Step 6: Statischer Skripttest gegen das echte Repo**

```powershell
mvn -q -DskipTests -pl hagrid -am package    # frisches Jar mit den neuen Klassennamen
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-run-scripts.ps1
```
Erwartet: `NN Skripte geprüft, 0 Befunde`, Exit 0. Jeder Befund ist ein echter Fehler im jeweiligen Skript; von Hand beheben (per PowerShell schreiben), erneut prüfen.

- [ ] **Step 7: Commits**

Die Skripte sind klein; Umzug und die drei Ersetzungen gehen in einen Commit (Rename-Erkennung ist hier zweitrangig, die Historie der Kampagnenskripte steht in METHODS-LOG/PAPER-RUNS). Der Skripttest bekommt einen eigenen Commit.

```powershell
git add -A runs tools hagrid
git reset -q tools/check-run-scripts.ps1 tools/Test-CheckRunScripts.ps1
git commit -m "chore(restructure): move run scripts to runs/{hannover,lausitz} and helper scripts to tools/; module, jar and main-class references follow"
git add tools/check-run-scripts.ps1 tools/Test-CheckRunScripts.ps1
git commit -m "chore(restructure): static run-script check (module, jar, main class, root marker) with self-test"
```

---

### Task 6: README und lebende Docs

**Files:**
- Modify: `README.md` (Titel Zeile 1, Abschnitt 2 Zeilen 52–90, Setup Zeilen 112–130, Abschnitt 4 Zeilen 132–160)
- Modify: `docs/BACKLOG.md` (12 Treffer), `docs/DATA-LAUSITZ.md` (6), `docs/CHECKLIST-emissions.md` (2), `docs/obsidian/HAGRID-Projektzusammenfassung.md` (2), `analysis/common/run-monitoring/RUNBOOK.md` (Zeile 62)

- [ ] **Step 1: README**

Zeile 1: `# Parcel Demand Scenario Generator for Hannover (2014–2050)` → `# HAGRID — Parcel demand and integrated freight/DRT simulation with MATSim`.

Direkt unter dem Titel einen Absatz einfügen:
```markdown
HAGRID bündelt zwei Studien auf einem gemeinsamen Kern:

- **Hannover** — Paketnachfrage (2014–2050) auf Straßenabschnittsebene und Last-Mile-Delivery-Simulation mit jsprit + MATSim, inkl. Kapazitäts-Sweep (`analysis/hannover/sweep`).
- **Lausitz (Hoyerswerda)** — integrierte Personen- und Paketbedienung mit DRT: Baseline, Cargo Hitching (1c, `drt_shareduse`) und Kapseltausch (1d, `drt_modular`), KPI-Dashboard v2 (`analysis/lausitz/kpi`). Studiendokumentation in `docs/` (`DATA-LAUSITZ.md`, `PAPER-RUNS.md`, `METHODS-LOG.md`).
- **Kern** — Geo-, Nachfrage- und Routing-Werkzeuge, Root-Erkennung, Simulationsverdrahtung (`hagrid.core`).

Die Trennung ist als Import-Regel festgeschrieben: `hagrid/src/test/java/hagrid/core/ArchitectureRulesTest.java`.
```

Abschnitt 2 (Zeilen 52–90) komplett ersetzen durch den Baum aus Spec §3 (ohne die Kommentare „heute …“) plus je einen Satz zu `hagrid/`, `analysis/`, `runs/`, `notebooks/`, `external/`, `tools/`, `docs/`. Die vier Aufzählungspunkte zu Notebooks 00–06 bleiben, unter `notebooks/demand-estimation/`.

Setup (Zeilen 112–130): unverändert bis auf `see tools/resync-freight.ps1` (bereits in Task 2). Neu dahinter:
```markdown
**Inputs:** `hagrid/input/` ist git-ignoriert; Aufbau und Herkunft in `hagrid/input/README.md`.
Checkouts von vor dem 2026-09-17 einmal `tools/migrate-input-layout.ps1` ausführen.

**Runs:** alle Startskripte liegen unter `runs/hannover/` und `runs/lausitz/`; sie wechseln selbst
in den richtigen Ordner. `tools/check-run-scripts.ps1` prüft sie statisch gegen das gebaute Jar.
```
Abschnitt 4 „Installation“ (Zeile 132 ff.): `cd ParcelDemandScenarioGenerator` → `cd HAGRID/notebooks/demand-estimation`.

- [ ] **Step 2: Lebende Docs mechanisch nachziehen — jede Fundstelle einzeln ansehen**

```powershell
git grep -n -E 'parcel-demand-2-matsim-pipeline|hagrid-input|analysis/kpi|analysis/hannover-sweep|hagrid/integrated|hagrid\.integrated' -- docs/BACKLOG.md docs/DATA-LAUSITZ.md docs/CHECKLIST-emissions.md docs/obsidian/HAGRID-Projektzusammenfassung.md analysis/common/run-monitoring/RUNBOOK.md
```
Ersetzungsregeln (nur Repo-Pfade, keine Laufordner, keine Zitate von Dateinamen aus `hagrid-matsim-output`):
- `parcel-demand-2-matsim-pipeline/analysis/kpi` → `analysis/lausitz/kpi`; `…/analysis/hannover-sweep` → `analysis/hannover/sweep`; `…/analysis/run-monitoring` → `analysis/common/run-monitoring`
- `parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/` → `hagrid/input/lausitz/`; `…/hagrid-input/emissions/` → `hagrid/input/common/emissions/`; `…/hagrid-input/<hannover-ordner>/` → `hagrid/input/hannover/<ordner>/`
- `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/` → `hagrid/src/main/java/hagrid/lausitz/`; `…/hagrid/simulation/` → `hagrid/src/main/java/hagrid/core/simulation/` (bei `DrtScenarioBuilder`, `KpiDashboardTrigger`, `RunMetadataWriter`: `lausitz/simulation/`); `…/hagrid/utils/routing/` → `…/core/routing/`
- übriges `parcel-demand-2-matsim-pipeline/` → `hagrid/`
- Zeilennummern in `file:line` bleiben, wie sie sind.

`METHODS-LOG.md`, `BACKLOG-DONE.md`, `PAPER-RUNS.md` und alle Specs/Pläne mit Datum vor 2026-09-17: **nicht anfassen** (historisch).

- [ ] **Step 3: Gate 6**

```powershell
git grep -l 'parcel-demand-2-matsim-pipeline' | Where-Object { $_ -notmatch '^docs/superpowers/(specs|plans)/2026-0[1-8]|^docs/superpowers/(specs|plans)/2026-09-0|^docs/(METHODS-LOG|BACKLOG-DONE|PAPER-RUNS)\.md$|^tools/migrate-input-layout\.ps1$|^analysis/hannover/sweep/provenance/v4-sim-working-tree\.patch$' }
```
Erwartet: genau `docs/superpowers/specs/2026-09-17-repo-restructure-design.md` und `docs/superpowers/plans/2026-09-17-repo-restructure.md` (beschreiben den Umbau selbst). Alles andere ist ein Befund. Erlaubt und im Commit-Text genannt: Migrationsskript, Specs/Pläne vor dem 17.09., die drei historischen Logs, der Provenance-Patch des Hannover-Sweeps (ein Git-Patch gegen den alten Baum, bleibt byteweise wie er ist).

- [ ] **Step 4: Commit**

```powershell
git add README.md docs/BACKLOG.md docs/DATA-LAUSITZ.md docs/CHECKLIST-emissions.md docs/obsidian analysis/common/run-monitoring/RUNBOOK.md
git commit -m "docs(restructure): README for the three-part layout; living docs point at the new paths"
```

---

### Task 7: Nachher-Proben, Vergleich, Vollprüfung

**Files:**
- Create (außerhalb Repo): `%USERPROFILE%\hagrid-restructure-evidence\after\*`, `…\diff.txt`

- [ ] **Step 1: Frischer Build, volle Suite**

```powershell
cd "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
mvn -q clean install
python -m pytest analysis\lausitz\kpi\tests -q
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-run-scripts.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Test-MigrateInputLayout.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools\Test-CheckRunScripts.ps1
```
Erwartet: alles grün, 0 Befunde.

- [ ] **Step 2: Nachher-Proben**

```powershell
$ev = "$env:USERPROFILE\hagrid-restructure-evidence"
& "$ev\probe.ps1" -Module "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\hagrid" `
    -Jar 'target\hagrid-1.0-SNAPSHOT.jar' -Out "$ev\after" `
    -PrepareClass 'hagrid.lausitz.drt.PrepareLausitzDrtInputs'
```
(Wenn `before\P3-skipped.txt` existiert, zusätzlich `-SkipHannover`, damit beide Seiten dieselben Proben enthalten.)

- [ ] **Step 3: Vergleich**

```powershell
$b = Get-Content "$ev\before\hashes.txt"; $a = Get-Content "$ev\after\hashes.txt"
Compare-Object $b $a | Tee-Object "$ev\diff.txt"
"before: $($b.Count) Zeilen, after: $($a.Count) Zeilen, Differenzen: $((Compare-Object $b $a | Measure-Object).Count)"
```
Erwartet: **0 Differenzen** bei gleicher Zeilenzahl. Jede Differenz ist ein Stopp: Datei ansehen, Ursache benennen. Erlaubt ist **keine** Differenz außer einer, die nachweislich nur einen Repo-Pfad im Klartext enthält (z. B. ein `run_meta.json` mit `pipelineRoot`); die wird dann in der METHODS-LOG-Notiz einzeln benannt. Zahlen, Fahrpläne, Carrier-Pläne, Events müssen bitidentisch sein.

- [ ] **Step 4: Probenläufe aus dem Repo entfernen**

```powershell
Get-ChildItem hagrid\hagrid-output, hagrid\hagrid-matsim-output -Directory -Filter '*rsprobe*' | Remove-Item -Recurse -Force
```

---

### Task 8: Backlog, METHODS-LOG, Spec §11, Rückführung

**Files:**
- Modify: `docs/BACKLOG.md` (sechs Folgepunkte aus Spec §10), `docs/METHODS-LOG.md` (neuer Eintrag), `docs/superpowers/specs/2026-09-17-repo-restructure-design.md` (§11)

- [ ] **Step 1: Arbeitsbaum sauber?**

```powershell
git status --short
git stash list | Select-String 'restructure: fremde'
```
Erwartet: keine Änderungen an `docs/`; der Stash aus Task 0 existiert noch. Er wird **erst in Step 5 nach dem Fast-Forward** zurückgeholt, damit die fremden Hunks nicht in die Restructure-Commits rutschen.

- [ ] **Step 2: BACKLOG-Folgepunkte** (jeweils ≤ 4 Zeilen, keine Erzählung; Regel aus feedback-backlog)

```markdown
- **`[M]` Fork-Hygiene + DRT/DVRP-Fork** — neuer Fork-Branch `hagrid/2025.0` = Tag 2025.0 + `76a1638` + `3b5a493` (die zwei Fuel-Compat-Commits `c44fe15`, `39ad2cc` sind seit Core 2025.0 obsolet, `VehicleUtils.getFuelConsumptionLitersPerMeter` existiert wieder); Sparse-Checkout um `contribs/dvrp contribs/drt` erweitern; POM-Shims `external/dvrp`, `external/drt` nach freight-Muster (dvrp braucht `common`, `ev`, `otfvis` als Release-Artefakte); Gitlink umhängen, alter Branch bleibt (Shallow-Clones). Kontrolle: Proben aus `%USERPROFILE%\hagrid-restructure-evidence` erneut laufen lassen. → Spec 2026-09-17 §10.1
- **`[S]` `HagridPaths` aufteilen** in Root-Erkennung (`core`) + `HannoverPaths` + `LausitzPaths`; danach fällt `HagridPaths` von der Allowlist in `ArchitectureRulesTest`.
- **`[L]` `HAGRIDSimulationConfig` / `SimulationRunnerUtils` / `HAGRIDScenarioBuilder` zerlegen** in Kern-Basis + Studienteile; Allowlist schrumpft auf leer.
- **`[S]` Konzeptname `drt_shareduse` → `drt_cargohitching`** nur mit Alias in beide Richtungen (`run_meta.py`, Laufordner, KPI-Dateinamen, METHODS-LOG-Zitate). User-Entscheidung 2026-09-17: vorerst nicht.
- **`[S]` Ausrollen des Umbaus auf Sim, IVS100, Lausitz-VM** zwischen Läufen: `git pull` → `tools/migrate-input-layout.ps1` → `mvn -q install` → P1-Probe gegen `before\hashes.txt`. Sim: Pull-Sperre (Hannover-v4) vorher prüfen.
```
Der bestehende Punkt „Race in unserem matsim-Fork“ (Zeile 476) und der Input-Bootstrap-Punkt (Zeile 504) bleiben, wie sie sind.

- [ ] **Step 3: METHODS-LOG-Eintrag** (neue Nummer in der laufenden §2.x-Zählung; vorher die letzte Nummer im File nachsehen)

```markdown
### §2.NN Repo-Umbau 2026-09-17 ist verhaltensneutral (Belege)

Umbau nach Spec `docs/superpowers/specs/2026-09-17-repo-restructure-design.md`: Modul `parcel-demand-2-matsim-pipeline` → `hagrid/`, Pakete `hagrid.core/hannover/lausitz`, Inputs `input/{common,hannover,lausitz}`, Analysen/Runs/Notebooks/Fremdcode in eigene Wurzelordner. Keine Zerlegung von Klassen, Output-Wurzeln und Konzeptnamen unverändert.

Belege (Dev-PC, Jar jeweils frisch gebaut, Hashes in `%USERPROFILE%\hagrid-restructure-evidence\{before,after}\hashes.txt`):
- P1 `PrepareLausitzDrtInputs` (`drt_baseline`, f120): fünf `*_drt_*.xml.gz` entpackt sha256-gleich; `*_drt_inputs.properties` nach Pfadnormalisierung gleich.
- P2 `drt_baseline`, 2 Iterationen, jsprit 10: `drt_vehicle_stats*.csv`, Carrier-Pläne, `output_events.xml.gz` (entpackt) sha256-gleich. <N> Dateien verglichen, 0 Differenzen.
- P3 Hannover `basecase`, 1 Iteration: <durchgeführt: Carrier-XML sha256-gleich | nicht durchgeführt: geteilte Hannover-Inputs liegen nicht auf dem Dev-PC; Nachholen auf dem Sim nach dem Ausrollen>.
- Statisch: Pipeline <N> + freight <N> Java-Tests grün (Zahlen aus der Surefire-Zusammenfassung von Task 7 Step 1), KPI-Pytests <N> grün, `ArchitectureRulesTest` mit Mutationsprobe (eine verbotene Referenz ⇒ rot), `check-run-scripts.ps1` 0 Befunde über <N> Skripte.

Einschränkung: Läufe von vor dem Umbau tragen Pfade des alten Layouts in `run_meta.json`/Logs; die KPI-Analyse liest sie weiter, weil sie relativ zum Laufordner rechnet (`run_dir.parent.parent`).
```
Platzhalter `<N>`/`<…>` mit den gemessenen Werten aus Task 7 füllen, bevor committet wird.

- [ ] **Step 4: Spec §11 anhängen**

An `docs/superpowers/specs/2026-09-17-repo-restructure-design.md` anfügen:
```markdown
## 11. Planungsbefunde (2026-09-17, beim Schreiben des Plans gemessen)

1. `HAGRID`, `HagridModule` → `hagrid.hannover` (importieren `demand.*`/`pipeline.*`), nicht `core`.
2. Kein `hagrid.core.pipeline`: die Mechanik-Klassen haben nur Hannover-Verwender; ganz `hagrid.pipeline` → `hagrid.hannover.pipeline`.
3. Allowlist §4.1 gemessen: `{HagridPaths, HAGRIDScenarioBuilder, HAGRIDSimulationConfig, SimulationRunnerUtils}`. `HagridConfig` und `HAGRIDSimulationRunner` referenzieren keine Studienpakete; `HAGRIDScenarioBuilder` hat eine voll qualifizierte Referenz auf `DrtConfigComposer`.
4. `analysis/paper-figures/` ist ungetrackt (`.gitignore`), Umzug lokal.
5. KPI-Python rechnet relativ zum Laufordner; nur `maps.py` (2 Zeilen) und `KpiDashboardTrigger` ändern sich.
6. Artefakt-ID `hagrid` ⇒ Jar `hagrid-1.0-SNAPSHOT.jar`; `SimulationBatGenerator` zieht nach.
```

- [ ] **Step 5: Commit und Rückführung**

```powershell
git add docs/BACKLOG.md docs/METHODS-LOG.md docs/superpowers/specs/2026-09-17-repo-restructure-design.md
git commit -m "docs(restructure): backlog follow-ups, neutrality evidence in METHODS-LOG, spec addendum with planning findings"
git fetch origin
git merge-tree --write-tree --name-only hendrik restructure
```
Erwartet: keine Konfliktdateien. Dann:
```powershell
git checkout hendrik
git merge --ff-only restructure
git log --oneline -12
git stash pop          # die in Task 0 geparkten fremden Docs-Änderungen zurück in den Arbeitsbaum, unversioniert
git status --short
```
Erwartet nach dem Pop: `BACKLOG.md`, `METHODS-LOG.md`, `PAPER-RUNS.md` modifiziert, keine Konfliktmarker. Bei Konflikt (die andere Session und Task 8 haben denselben Abschnitt berührt): beide Seiten behalten, Marker entfernen, **nicht** committen; das gehört der anderen Session.

Falls `hendrik` inzwischen weitergewandert ist (Parallel-Session): `git checkout restructure; git rebase hendrik`, Suite erneut (`mvn -q install`), dann Fast-Forward. **Kein Push** ohne Zuruf.

---

## Self-Review gegen die Spec

- §3 Zielstruktur: Task 1 (Wurzel, legacy-figures), 2 (`external/`), 3b (`hagrid/`, `input/`), 5a (`analysis/`, `notebooks/`), 5b (`runs/`, `tools/`) ✔
- §4 Paketkarte + §4.1 Regel: Task 4a (mit den Abweichungen 1–3 oben), 4b ✔
- §5.1 Inputs/Marker/`StudyArea`: Task 3a, 3b ✔ · §5.2 Outputs unverändert: nirgends angefasst ✔ · §5.3 Analysen: 5a ✔ · §5.4 Runs (drei Ersetzungen, CRLF-Regel, ungetrackte Skripte mitnehmen): 5b ✔ · §5.5 Notebooks: 5a ✔ · §5.6 Fremdcode + wörtlicher Sparse-Checkout-Befehl: 2, 5b Step 5 ✔ · §5.7 README/Docs: 6 ✔
- §6 Gates: 1 (`git status`), 2 (freight 272), 3 (Migrationsskript idempotent, Pfadtests), 4a (grep leer, `--follow`), 4b (Mutationsprobe, Ressourcen-grep), 5 (statischer Skripttest, Pytests), 6 (Allowlist-grep) ✔
- §7 drei Proben vorher/nachher: Task 0 + 7, P3 mit expliziter Skip-Regel ✔
- §8 Ausrollen: bewusst nicht in diesem Plan, als BACKLOG-Punkt in Task 8 ✔
- §10 Folgepunkte: Task 8 Step 2 ✔
- Platzhalter: nur die `<N>`-Werte in Task 8 Step 3, die aus Task 7 gemessen werden — kein „TBD“.
- Namenskonsistenz: `migrate-input-layout.ps1`, `check-run-scripts.ps1`, `probe.ps1`, `ArchitectureRulesTest`, `scriptFor`, `hagrid-1.0-SNAPSHOT.jar`, `hagrid.core.simulation.HAGRIDSimulationRunner` sind in allen Tasks gleich geschrieben.
