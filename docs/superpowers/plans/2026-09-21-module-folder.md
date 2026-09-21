# Modul nach `hagrid/simulation/` (Repo-Umbau Teil 3) — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Das Maven-Modul zieht von `hagrid/` nach `hagrid/simulation/`, sodass `hagrid/` nur noch `demand/` und `simulation/` enthält; Simulationslogik und fachliche Ergebnisse bleiben unverändert.

**Architecture:** Reiner Pfadwurzel-Umzug: `git mv` der getrackten Modulinhalte, ein Literal in `HagridPaths`, eine Ebene mehr in `KpiDashboardTrigger`, POM-Modulpfad und `relativePath`, byte-transparente Rewrites der Startskripte, Ignore-Regeln und Docs. Die ignorierten lokalen Daten (149 GB Läufe, 314 MB Inputs) bewegt je Maschine ein wiederanlauffähiges Migrationsskript. Gearbeitet wird in einem Git-Worktree; der Haupt-Checkout auf dem Dev ist danach die erste echte Migrationsmaschine.

**Tech Stack:** Java 21 / Maven 3.9.8 (`hagrid-parent` → Module `external/freight`, `hagrid`), PowerShell 5.1, Python 3.13 (pytest), Git-Worktree, Windows 11.

**Spec:** `docs/superpowers/specs/2026-09-21-module-folder-design.md` (Review-Fassung, committet als `ec66531`).

## Global Constraints

- **Arbeitsort ist der Worktree `C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID-r3`** auf Branch `restructure-3` ab `hendrik` (Task 0 legt ihn an). Der Haupt-Checkout `…\GitHub\HAGRID` wird in Task 0 bis 5 **nur gelesen** (Inputs kopieren, Vergleichshashes); geschrieben wird dort erst in Task 6 nach dem Fast-Forward. Kein Push, kein Master-Merge. Subagenten arbeiten strikt nacheinander.
- **JDK 21**: `JAVA_HOME = C:\Program Files\Java\jdk-21.0.10`; `java` im PATH ist 25 — jeder Java-Aufruf in diesem Plan nutzt `$env:JAVA_HOME\bin\java.exe` bzw. die Skripte lösen `JAVA_HOME` selbst auf.
- **Maven** immer von der Repo-Wurzel des Worktrees: `mvn -q test -pl :hagrid -am -Dsurefire.failIfNoSpecifiedTests=false` (Selektor ist ab Task 1 die `artifactId`; ohne `-am` hängt Maven an der Remote-Auflösung des freight-SNAPSHOT). Vollbau: `mvn -q clean install`.
- **Skript-Rewrites byte-transparent**: `.bat`/`.ps1`/`.txt`/`.json` werden mit Python als Latin-1 gelesen und geschrieben (`io.open(p, encoding='latin-1', newline='')`), CRLF bleibt erhalten. `.bat` nie mit Edit/Write anfassen. Neue `.bat` nur per PowerShell `[IO.File]::WriteAllText($p, $s, [Text.UTF8Encoding]::new($false))` mit `\r\n`. `tools/*.ps1` ASCII-only, ohne BOM (PowerShell 5.1 liest BOM-lose Dateien als cp1252; ein Gedankenstrich bricht den Parser).
- **Unverändert**: Output-Ordnernamen `hagrid-output/`, `hagrid-matsim-output/`; `artifactId hagrid`; Jar `target/hagrid-1.0-SNAPSHOT.jar`; Property `hagrid.pipeline.root`; Marker `input/README.md`; Konzept- und Paketnamen.
- **Verschiebungen per `git mv`**, in eigenem Commit vor den inhaltlichen Änderungen, soweit der Zwischenstand baubar bleibt (Task 1 trennt in 1a Umzug, 1b Anpassungen; `git show --stat` zeigt `rename … (100%)` für Java-Dateien).
- **Auf Sim, IVS100 und Lausitz-VM passiert in diesem Plan nichts.** Ausrollen ist Spec §8, nach Task 6.
- **Fremde Arbeit**: Die drei Docs mit uncommitteten Änderungen der Emissions-Session (`docs/BACKLOG.md`, `docs/METHODS-LOG.md`, `docs/PAPER-RUNS.md`) liegen im Haupt-Checkout und werden dort nicht angefasst. Im Worktree stehen sie auf dem committeten Stand.

## Befunde beim Planen (Abweichungen von der Spec, gemessen 2026-09-21)

1. **Ein Skript fehlte in Spec §3:** `runs/lausitz/queue_chi_detour_rerun.ps1:49` bildet `Join-Path $repo 'hagrid'`. Gefunden durch das repo-weite Gate-Muster (c). Kommt in die Rewrite-Tabelle (Task 2).
2. **Das Gate-Muster (a) der Spec findet die häufigste Form nicht.** `hagrid[\\/](?!…)` verlangt einen Trenner nach `hagrid`; die 33 `cd /d "%~dp0..\..\hagrid"`-Zeilen enden aber auf `hagrid"`. Das Gate bekommt ein zweites String-Muster `[\\/]hagrid["']` (heute 34 Treffer in `runs`, `tools`, `analysis`) und ein Muster für den alten Selektor `-pl\s+hagrid\b` (heute 9 Zeilen in 5 Dateien).
3. **`T:\hagrid\input` in `Test-Installers.ps1:188` ist ein synthetischer Fixture-String** für den Laufwerksmapping-Test, kein Pfad. Bleibt unverändert, steht auf der Gate-Allowlist.
4. **`decide_theta.py` schreibt `chosen_theta.txt` neben sich selbst** (`OUT = Path(__file__).resolve().parent`, Zeile 22/95). Der Umzug nach `runs/lausitz/` ist damit konsistent, wenn das Bat `%~dp0` für beide Dateien nutzt.
5. **`probe.ps1` kennt keinen P1-only-Modus** (`-HashOnly` überspringt alle Läufe, sonst laufen P1+P2). P1 wird von Hand gestartet, danach `-HashOnly`; verglichen werden die **7** `hagrid-output\…`-Zeilen aus `after\hashes.txt` (94 Zeilen gesamt).
6. **Pfadlänge (Spec §5.5) bestätigt:** Repo-Wurzel 51 + `hagrid/` + 202 = 262 Zeichen heute (`CRASHED_20260909_…/ITERS/it.100/…occupancy_time_profiles_StackedArea_drt.png`); `LongPathsEnabled` auf dem Dev nicht gesetzt. Worktree-Wurzel `HAGRID-r3` ist 3 Zeichen länger.
7. **Bestehende Migrationslogik prüft Kollisionen je Paar** (`migrate-input-layout.ps1:47-49`), nicht je Datei; ein Wiederanlauf nach Teilabbruch würde abbrechen. Beide Skripte teilen sich künftig `tools/Migrate-Common.ps1` mit dateiweiser Kollisionsprüfung.
8. **`devlog/log4j2.xml` ist unreferenziert** und unterscheidet sich von `logging/log4j2_runlocal.xml`; `log4j2_dev.xml` (55 Zeilen Unterschied) wird von `vmargs_dev.txt:13` gebraucht. Nur letzteres zieht um.

Diese Punkte werden in Task 5 als §12 an die Spec angehängt.

## Dateistruktur nach dem Umbau (Kurzreferenz)

```
HAGRID/
├── pom.xml                         <module>external/freight</module>, <module>hagrid/simulation</module>
├── hagrid/
│   ├── demand/{estimation,estimation-batch}/      unverändert
│   └── simulation/
│       ├── pom.xml                 relativePath ../../pom.xml, artifactId hagrid
│       ├── src/{main,test}/java/hagrid/{core,hannover,lausitz}/…
│       ├── input/{README.md (Marker), common, hannover, lausitz}/
│       ├── hagrid-output/  hagrid-matsim-output/  routerCache/  logs/  target/   (lokal)
│       ├── logging/{log4j2_runlocal.xml, log4j2_dev.xml}
│       └── vmargs.txt  vmargs_dev.txt  analysis_vmargs.txt
├── runs/lausitz/{decide_theta.py, chosen_theta.txt, …}   (aus hagrid/devlog/)
├── runs/lausitz/campaigns/run_r3smoke.bat                 (neu, Task 4)
├── docs/legacy/hagrid/{PIPELINE_DOCUMENTATION.md, SETUP_TUTORIAL.md}
└── tools/{Migrate-Common.ps1, migrate-input-layout.ps1, migrate-module-layout.ps1,
           Test-MigrateInputLayout.ps1, Test-MigrateModuleLayout.ps1,
           check-run-scripts.ps1, Test-CheckRunScripts.ps1}
```

Gelöscht (getrackt): `hagrid/pom_backup.xml`, `hagrid/devlog/log4j2.xml`. Geparkt (lokal, Task 6): `hagrid/{bin,test,output,sim-input,sim-output,.pytest_cache}` → `%USERPROFILE%\hagrid-parked-inputs\legacy-phd\`.

---

### Task 0: Worktree, Inputs, Messungen

**Files:**
- Create: `C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID-r3` (Worktree), `%USERPROFILE%\hagrid-restructure-evidence\r3\{pathlen.ps1, PathProbe.java, pathlen.txt, baseline-gate.txt}`
- Modify: nichts im Repo (Task 0 committet nicht)

**Interfaces:**
- Produces: Worktree mit gebautem Jar `hagrid/target/hagrid-1.0-SNAPSHOT.jar` (altes Layout) und ignorierter Kopie `hagrid/input/` (314 MB); Messtabelle im Ledger; Baseline-Zählung der Gate-Muster.

- [ ] **Step 1: Worktree anlegen und Inputs spiegeln**

```powershell
Set-Location 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID'
git worktree add -b restructure-3 '..\HAGRID-r3' hendrik
Set-Location '..\HAGRID-r3'
git status --short              # erwartet: leer
git submodule update --init external/matsim-libs
git -C external/matsim-libs sparse-checkout set contribs/freight examples/scenarios/logistics-2regions
# Ignorierte Inputs kopieren (nur input/, keine Outputs): 314 MB
robocopy '..\HAGRID\hagrid\input' '.\hagrid\input' /E /NFL /NDL /NJH /NP /R:1 /W:1
git status --short              # erwartet: weiterhin leer (alles ignoriert)
```

Erwartet: `robocopy` meldet Exit-Code 1 oder 3 (kopiert), `git status` leer.

- [ ] **Step 2: Bauen und Suite auf dem unveränderten Baum**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
mvn -q clean install 2>&1 | Tee-Object "$env:USERPROFILE\hagrid-restructure-evidence\r3\step0-build.log" | Select-Object -Last 5
Test-Path .\hagrid\target\hagrid-1.0-SNAPSHOT.jar   # True
```

Erwartet: Exit 0; in den Surefire-Reports `hagrid` 657 Tests, 0 Fehler (`Select-String -Path hagrid\target\surefire-reports\*.txt -Pattern 'Tests run:' | Measure-Object`).

- [ ] **Step 3: Pfadlängen messen und Dateisystemtest (Spec §5.5)**

`%USERPROFILE%\hagrid-restructure-evidence\r3\PathProbe.java` (Single-File-Programm, JEP 330):

```java
import java.nio.file.*;
public class PathProbe {
    public static void main(String[] a) throws Exception {
        Path p = Paths.get(a[0]);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "java-ok");
        String s = Files.readString(p);
        Files.delete(p);
        System.out.println("JAVA " + p.toString().length() + " " + s);
    }
}
```

`%USERPROFILE%\hagrid-restructure-evidence\r3\pathlen.ps1`:

```powershell
param([string] $RepoRoot)
$ErrorActionPreference = 'Continue'
$out = Join-Path $env:USERPROFILE 'hagrid-restructure-evidence\r3\pathlen.txt'
$lines = @()
$root = (Resolve-Path $RepoRoot).Path
$lines += "root=$root len=$($root.Length)"
# laengster vorhandener Pfad relativ zum Modul (dir /s /b vertraegt lange Pfade, Get-ChildItem in PS 5.1 nicht)
$module = Join-Path $root 'hagrid'
$longest = (cmd /c "dir /s /b `"$module\hagrid-matsim-output`"" 2>$null | Sort-Object Length -Descending | Select-Object -First 1)
$lines += "longest_existing=$($longest.Length) $longest"
$lines += "projected_after_move=$($longest.Length + 11)"
$reg = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem' -ErrorAction SilentlyContinue).LongPathsEnabled
$lines += "LongPathsEnabled=$reg"
# Testpfad mit exakt max(273, projected) Zeichen unter dem KUENFTIGEN Modulordner
$target = [Math]::Max(273, $longest.Length + 11)
$base = Join-Path $root 'hagrid\simulation\hagrid-matsim-output\r3pathprobe'
$name = 'x' * ($target - $base.Length - 1)
$probe = Join-Path $base $name
$lines += "probe_len=$($probe.Length)"
try { New-Item -ItemType Directory -Force (Split-Path $probe) | Out-Null; Set-Content -LiteralPath $probe 'ps'; $r = Get-Content -LiteralPath $probe; Remove-Item -LiteralPath $probe; $lines += "POWERSHELL ok $r" } catch { $lines += "POWERSHELL FAIL $($_.Exception.Message)" }
$py = "import sys,os; p=sys.argv[1]; os.makedirs(os.path.dirname(p), exist_ok=True); open(p,'w').write('py'); print('PYTHON ok', open(p).read()); os.remove(p)"
$lines += (& python -c $py $probe 2>&1 | Out-String).Trim()
$lines += (& "$env:JAVA_HOME\bin\java.exe" (Join-Path $env:USERPROFILE 'hagrid-restructure-evidence\r3\PathProbe.java') $probe 2>&1 | Out-String).Trim()
Remove-Item -Recurse -Force (Join-Path $root 'hagrid\simulation') -ErrorAction SilentlyContinue
$lines | Set-Content $out -Encoding ascii
$lines
```

Ausführen gegen den **Haupt-Checkout** (dort liegen die 149 GB; der Worktree hat keine Läufe):

```powershell
powershell -NoProfile -File "$env:USERPROFILE\hagrid-restructure-evidence\r3\pathlen.ps1" -RepoRoot 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID'
```

Erwartet: vier Zeilen `POWERSHELL …`, `PYTHON …`, `JAVA …` mit `ok` oder `FAIL`. Alle drei Ergebnisse gehen wörtlich in den Ledger (Tabelle: Werkzeug, Pfadlänge, Ergebnis). Ein `FAIL` ist **kein** Abbruch dieses Plans (der Dev ist die einzige Maschine im Plan, und Java schreibt die Läufe), aber ein Blocker für das Ausrollen auf eine Maschine mit demselben Befund, bis `LongPathsEnabled` dort gesetzt ist (Spec §5.5). Der Ordner `hagrid\simulation` im Haupt-Checkout wird vom Skript wieder entfernt; `git -C ..\HAGRID status --short` muss danach unverändert sein.

- [ ] **Step 4: Baseline der Gate-Muster (Positivkontrolle für Task 3)**

```powershell
Set-Location 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID-r3'
$ex = @(':!docs/superpowers', ':!docs/METHODS-LOG.md', ':!docs/BACKLOG-DONE.md', ':!analysis/hannover/sweep/provenance')
$o = @()
$o += '--- a1'; $o += git grep -cIP 'hagrid[\\/](?!simulation[\\/]|demand[\\/]|core[\\/]|hannover[\\/]|lausitz[\\/]|\{|2025)' -- . $ex
$o += '--- a2'; $o += git grep -cIE "[\\/]hagrid[`"']" -- . $ex
$o += '--- b';  $o += git grep -nIP 'Paths?\.(of|get)\(\s*"hagrid"\s*(,\s*"(?!simulation")|\))' -- . $ex
$o += '--- c';  $o += git grep -nIP "Join-Path\s+[^\r\n]*'hagrid'" -- . $ex
$o += '--- d';  $o += git grep -nIP -- '-pl\s+hagrid\b' -- . $ex
$o | Set-Content "$env:USERPROFILE\hagrid-restructure-evidence\r3\baseline-gate.txt" -Encoding utf8
$o | Measure-Object -Line
```

Die Ausnahmen `core|hannover|lausitz|\{` im Muster a1 lassen Paketpfade (`src/main/java/hagrid/core/…`) und den README-Baum (`hagrid/{core,…}`) durch; alles andere hinter `hagrid/` ist ein alter Modulpfad. Erwartet (Stand `e9d81d1`): a1 trifft u. a. `.gitignore:18`, `README.md:9`, `docs/BACKLOG.md:12`, `docs/DATA-LAUSITZ.md:5`; a2 trifft 34 Dateien; b trifft `HagridPathsTest.java` (2); c trifft `queue_chi_detour_rerun.ps1`, `migrate-input-layout.ps1`, `Test-MigrateInputLayout.ps1`; d trifft 5 Dateien / 9 Zeilen. Zahlen in den Ledger. Jedes Muster **muss** heute treffen, sonst taugt es in Task 3 nicht als Gate.

- [ ] **Step 5: Ledger-Eintrag**

Tabelle im Ledger: Worktree-Pfad und HEAD, Suite-Zahlen, Pfadlängen-Zeilen, Gate-Baseline. Kein Commit.

---

### Task 1: Umzug, POM, Java, Tests

**Files:**
- Move (git mv, Worktree): `hagrid/{pom.xml, src, input, hagrid-output, hagrid-matsim-output, vmargs.txt, vmargs_dev.txt, analysis_vmargs.txt, logging}` → `hagrid/simulation/…`; `hagrid/devlog/log4j2_dev.xml` → `hagrid/simulation/logging/log4j2_dev.xml`; `hagrid/devlog/{decide_theta.py, chosen_theta.txt}` → `runs/lausitz/`; `hagrid/{PIPELINE_DOCUMENTATION.md, SETUP_TUTORIAL.md}` → `docs/legacy/hagrid/`
- Delete: `hagrid/pom_backup.xml`, `hagrid/devlog/log4j2.xml`
- Modify: `pom.xml:15`, `hagrid/simulation/pom.xml:12`, `hagrid/simulation/vmargs_dev.txt:13`, `hagrid/simulation/src/main/java/hagrid/core/HagridPaths.java:58`, `…/lausitz/simulation/KpiDashboardTrigger.java:34-38`
- Test: `hagrid/simulation/src/test/java/hagrid/core/HagridPathsTest.java:395-417`, `…/lausitz/simulation/KpiDashboardTriggerTest.java:57-67`

**Interfaces:**
- Produces: Modulpfad `hagrid/simulation`, Maven-Selektor `:hagrid`, `HagridPaths.detectPipelineRoot(Path)` liefert in Fall 3/4 `Path.of("hagrid","simulation")`, `KpiDashboardTrigger.scriptFor(root)` = `root.toAbsolutePath().normalize().getParent().getParent().resolve("analysis/lausitz/kpi/build_kpis.py")`.

- [ ] **Step 1: Umzug (Commit 1a)**

```powershell
Set-Location 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID-r3'
New-Item -ItemType Directory -Force hagrid\simulation, docs\legacy\hagrid | Out-Null
foreach ($e in 'pom.xml','src','input','hagrid-output','hagrid-matsim-output','vmargs.txt','vmargs_dev.txt','analysis_vmargs.txt','logging') { git mv "hagrid/$e" "hagrid/simulation/$e" }
git mv hagrid/devlog/log4j2_dev.xml hagrid/simulation/logging/log4j2_dev.xml
git mv hagrid/devlog/decide_theta.py runs/lausitz/decide_theta.py
git mv hagrid/devlog/chosen_theta.txt runs/lausitz/chosen_theta.txt
git mv hagrid/PIPELINE_DOCUMENTATION.md docs/legacy/hagrid/PIPELINE_DOCUMENTATION.md
git mv hagrid/SETUP_TUTORIAL.md docs/legacy/hagrid/SETUP_TUTORIAL.md
git rm -q hagrid/pom_backup.xml hagrid/devlog/log4j2.xml
git status --short | Where-Object { $_ -notmatch '^R ' -and $_ -notmatch '^D ' }   # erwartet: leer
# Hinweis: `git mv hagrid/input` nimmt die in Task 0 kopierten, ignorierten Inputs auf der Platte mit
# (Rename des Ordners). Der Maschinenzustand "Inputs noch unter hagrid/" wird in Task 2 Step 8 bewusst
# hergestellt, damit das Migrationsskript einmal echte Daten bewegt.
Get-ChildItem hagrid | Select-Object Name                                          # erwartet: demand, simulation (+ evtl. leeres devlog)
if (Test-Path hagrid\devlog) { Remove-Item hagrid\devlog -Recurse -Force }
(git ls-files | Measure-Object -Line).Lines                                        # 699 - 2 = 697
git commit -q -m "chore(restructure): module moved to hagrid/simulation, devlog helpers to runs/lausitz, legacy module docs to docs/legacy/hagrid (pure git mv; pom_backup.xml and unreferenced devlog/log4j2.xml removed)"
```

Erwartet: 697 getrackte Dateien; `git show --stat HEAD | Select-String 'rename' | Measure-Object` zählt die Umbenennungen (≈ 290), alle `(100%)`.

- [ ] **Step 2: Fehlschlagende Tests schreiben (Root-Erkennung, scriptFor)**

`HagridPathsTest.java`, Klasse `RootMarkerDetection` (ab Zeile ~380): beide Erwartungen und die Fixture ändern.

```java
        @Test
        @DisplayName("marker under hagrid/simulation -> module root is 'hagrid/simulation', and the marker is really there")
        void markerInSubfolderMeansHagridSimulation(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("hagrid").resolve("simulation").resolve("input"));
            Files.createFile(tempDir.resolve("hagrid").resolve("simulation").resolve("input").resolve("README.md"));

            Path detected = HagridPaths.detectPipelineRoot(tempDir);
            assertThat(detected).isEqualTo(Path.of("hagrid", "simulation"));
            assertThat(Files.exists(tempDir.resolve(detected).resolve("input").resolve("README.md"))).isTrue();
        }

        @Test
        @DisplayName("marker directly under hagrid/ (pre-2026-09-21 layout) is NOT the module root any more")
        void oldLayoutIsNotDetected(@TempDir Path tempDir) throws IOException {
            Files.createDirectories(tempDir.resolve("hagrid").resolve("input"));
            Files.createFile(tempDir.resolve("hagrid").resolve("input").resolve("README.md"));

            Path detected = HagridPaths.detectPipelineRoot(tempDir);
            assertThat(detected).isEqualTo(Path.of("hagrid", "simulation"));
            assertThat(Files.exists(tempDir.resolve(detected).resolve("input").resolve("README.md"))).isFalse();
        }

        @Test
        @DisplayName("no marker anywhere -> falls back to 'hagrid/simulation' (same path, nothing found)")
        void noMarkerFallsBack(@TempDir Path tempDir) {
            Path detected = HagridPaths.detectPipelineRoot(tempDir);
            assertThat(detected).isEqualTo(Path.of("hagrid", "simulation"));
            assertThat(Files.exists(tempDir.resolve(detected).resolve("input").resolve("README.md"))).isFalse();
        }
```

Der bisherige Test `markerInSubfolderMeansHagrid` wird durch `markerInSubfolderMeansHagridSimulation` ersetzt (nicht beide behalten). `markerInCwdMeansHere` bleibt unverändert.

`KpiDashboardTriggerTest.java`: den Test `scriptForResolvesRepoLevelAnalysis` ersetzen durch drei Tests.

```java
    @Test
    @DisplayName("scriptFor: absolute module root two levels below the repo -> <repo>/analysis/lausitz/kpi/build_kpis.py")
    void scriptForAbsoluteRoot(@org.junit.jupiter.api.io.TempDir Path repo) {
        Path module = repo.resolve("hagrid").resolve("simulation");
        Path expected = repo.resolve("analysis").resolve("lausitz").resolve("kpi").resolve("build_kpis.py");
        assertThat(KpiDashboardTrigger.scriptFor(module)).isEqualTo(expected);
    }

    @Test
    @DisplayName("scriptFor: relative root 'hagrid/simulation' resolves against the CWD (IDE case)")
    void scriptForRelativeRootFromRepo() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        assertThat(KpiDashboardTrigger.scriptFor(Path.of("hagrid", "simulation")))
                .isEqualTo(cwd.resolve("analysis").resolve("lausitz").resolve("kpi").resolve("build_kpis.py"));
    }

    @Test
    @DisplayName("scriptFor: relative root '.' (the bat case, CWD = module dir) climbs two levels")
    void scriptForDotRootFromModule() {
        // Unter Surefire ist das CWD der Modulordner hagrid/simulation; zwei Ebenen hoeher liegt die Repo-Wurzel.
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path expected = cwd.getParent().getParent().resolve("analysis").resolve("lausitz").resolve("kpi").resolve("build_kpis.py");
        assertThat(KpiDashboardTrigger.scriptFor(Path.of("."))).isEqualTo(expected);
        assertThat(java.nio.file.Files.exists(expected)).as("the real build_kpis.py is where scriptFor points").isTrue();
    }
```

- [ ] **Step 3: POM anpassen, damit die Tests überhaupt laufen**

`pom.xml:15`: `<module>hagrid</module>` → `<module>hagrid/simulation</module>`.
`hagrid/simulation/pom.xml:12`: `<relativePath>../pom.xml</relativePath>` → `<relativePath>../../pom.xml</relativePath>`.
`hagrid/simulation/vmargs_dev.txt:13`: `-Dlog4j2.configurationFile=devlog/log4j2_dev.xml` → `-Dlog4j2.configurationFile=logging/log4j2_dev.xml` (byte-transparent, Latin-1).

```powershell
mvn -q -pl :hagrid -am validate; $LASTEXITCODE     # 0 = der artifactId-Selektor greift (Spec §5.1)
```

- [ ] **Step 4: Tests laufen lassen, Rot sehen**

```powershell
mvn -q test -pl :hagrid -am -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=HagridPathsTest,KpiDashboardTriggerTest"
```

Erwartet: FAIL — `markerInSubfolderMeansHagridSimulation`, `oldLayoutIsNotDetected`, `noMarkerFallsBack` (erwarten `hagrid\simulation`, bekommen `hagrid`), `scriptForAbsoluteRoot`, `scriptForDotRootFromModule` (eine Ebene zu wenig). `scriptForRelativeRootFromRepo` ist mit dem alten Code ebenfalls rot (liefert `<cwd>/hagrid/analysis/…`).

- [ ] **Step 5: Implementieren**

`HagridPaths.java:58`:

```java
    private static final String PIPELINE_ROOT = "hagrid";
```
→
```java
    /** Modulordner relativ zur Repo-Wurzel (seit 2026-09-21: hagrid/simulation). */
    private static final Path PIPELINE_ROOT = Paths.get("hagrid", "simulation");
```

und in `detectPipelineRoot(Path cwd)` (Zeile ~163): `Path sub = Paths.get(PIPELINE_ROOT);` → `Path sub = PIPELINE_ROOT;`. Die `LOGGER.warn(... PIPELINE_ROOT)`-Zeile bleibt (Path formatiert sich selbst). Javadoc-Zeilen 125-127 (`CWD/PIPELINE_ROOT/input/README.md`) sinngemäß auf `hagrid/simulation` anpassen.

`KpiDashboardTrigger.java:34-38`:

```java
    /** The KPI builder lives at repo level: {@code <repo>/analysis/lausitz/kpi/build_kpis.py}.
     *  Since 2026-09-21 the module is {@code <repo>/hagrid/simulation}, i.e. TWO levels below the repo root. */
    static Path scriptFor(Path pipelineRoot) {
        Path repoRoot = pipelineRoot.toAbsolutePath().normalize().getParent().getParent();
        return repoRoot.resolve("analysis").resolve("lausitz").resolve("kpi").resolve("build_kpis.py");
    }
```

- [ ] **Step 6: Tests grün, Mutationsprobe, Suite**

```powershell
mvn -q test -pl :hagrid -am -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=HagridPathsTest,KpiDashboardTriggerTest"   # grün
# Mutationsprobe: getParent().getParent() -> getParent() zurueckdrehen, Tests muessen ROT werden, dann restaurieren
mvn -q test -pl :hagrid -am -Dsurefire.failIfNoSpecifiedTests=false   # ganze Modulsuite
```

Erwartet: Suite grün mit 657 − 1 (ersetzter Marker-Test) + 1 (`oldLayoutIsNotDetected`) − 1 (ersetzter scriptFor-Test) + 3 = **660** Tests. Zahl im Report festhalten.

- [ ] **Step 7: Commit 1b**

```powershell
git add pom.xml hagrid/simulation/pom.xml hagrid/simulation/vmargs_dev.txt hagrid/simulation/src/main/java/hagrid/core/HagridPaths.java hagrid/simulation/src/main/java/hagrid/lausitz/simulation/KpiDashboardTrigger.java hagrid/simulation/src/test/java/hagrid/core/HagridPathsTest.java hagrid/simulation/src/test/java/hagrid/lausitz/simulation/KpiDashboardTriggerTest.java
git commit -q -m "feat(restructure): module path hagrid/simulation in POM, HagridPaths fallback and KpiDashboardTrigger (two levels to repo root); log4j dev config under logging/"
```

---

### Task 2: Startskripte, Prüfskript, Migrationsskripte

**Files:**
- Modify (byte-transparent): 39 Skripte unter `runs/**` laut Rewrite-Tabelle, dazu `runs/lausitz/queue_chi_detour_rerun.ps1:49`, `runs/lausitz/run_weekend_chain.bat:27,29`
- Modify: `tools/check-run-scripts.ps1`, `tools/Test-CheckRunScripts.ps1`, `tools/migrate-input-layout.ps1`, `tools/Test-MigrateInputLayout.ps1`
- Create: `tools/Migrate-Common.ps1`, `tools/migrate-module-layout.ps1`, `tools/Test-MigrateModuleLayout.ps1`

**Interfaces:**
- Consumes: Jar `hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar` (Task 1, Step 6 hat gebaut; sonst `mvn -q -DskipTests -pl :hagrid -am package`).
- Produces: `tools/migrate-module-layout.ps1 [-RepoRoot <p>] [-Reverse]`; `tools/Migrate-Common.ps1` mit `Has-RealFiles`, `Find-Collisions`, `Merge-Into`, `Get-Inventory`; `check-run-scripts.ps1` akzeptiert `-pl :hagrid` und meldet alte Modulpfade.

- [ ] **Step 1: Rewrite-Skript für `runs/**` schreiben und ausführen**

`%USERPROFILE%\hagrid-restructure-evidence\r3\rewrite-runs.py` (außerhalb des Repos, nur Werkzeug):

```python
import io, re, sys, pathlib
root = pathlib.Path(sys.argv[1])            # Worktree-Wurzel
# (Regex, Ersatz, erwartete Trefferzahl ueber alle Dateien) -- geschlossene Tabelle, Spec §3 + Befund 1
table = [
    (r'cd /d "%~dp0\.\.\\\.\.\\\.\.\\hagrid"',                        r'cd /d "%~dp0..\\..\\..\\hagrid\\simulation"', 22),
    (r'cd /d "%~dp0\.\.\\\.\.\\hagrid"',                               r'cd /d "%~dp0..\\..\\hagrid\\simulation"', 8),
    (r'cd /d "C:\\Users\\Hendrik Bimmermann\\Documents\\GitHub\\HAGRID\\hagrid"',
                                                                        r'cd /d "C:\\Users\\Hendrik Bimmermann\\Documents\\GitHub\\HAGRID\\hagrid\\simulation"', 3),
    (r'\\HAGRID\\hagrid\\hagrid-output\\',                              r'\\HAGRID\\hagrid\\simulation\\hagrid-output\\', 2),
    (r'-WorkDir "C:\\Users\\Hendrik Bimmermann\\Documents\\GitHub\\HAGRID\\hagrid"',
                                                                        r'-WorkDir "C:\\Users\\Hendrik Bimmermann\\Documents\\GitHub\\HAGRID\\hagrid\\simulation"', 1),
    (r'%~dp0\.\.\\\.\.\\hagrid\\run_hagrid_sim\.bat',                  r'%~dp0..\\..\\hagrid\\simulation\\run_hagrid_sim.bat', 2),
    (r'not found in %~dp0\.\.\\\.\.\\hagrid ',                          r'not found in %~dp0..\\..\\hagrid\\simulation ', 1),
    (r'mvn -pl hagrid ',                                                r'mvn -pl :hagrid ', 9),
    (r"Join-Path \$root 'hagrid\\input\\lausitz\\demand'",             r"Join-Path $root 'hagrid\\simulation\\input\\lausitz\\demand'", 1),
    (r'\(hagrid/input/lausitz/demand/level_low\)',                      r'(hagrid/simulation/input/lausitz/demand/level_low)', 1),
    (r"Join-Path \$repo 'hagrid'",                                      r"Join-Path $repo 'hagrid\\simulation'", 1),
    (r'"%PY%" -u devlog\\decide_theta\.py',                            r'"%PY%" -u "%~dp0decide_theta.py"', 1),
    (r'\("devlog\\chosen_theta\.txt"\)',                                r'("%~dp0chosen_theta.txt")', 1),
]
counts = [0] * len(table)
for p in sorted(root.joinpath('runs').rglob('*')):
    if p.suffix.lower() not in ('.bat', '.ps1'): continue
    s = io.open(p, encoding='latin-1', newline='').read(); t = s
    for i, (pat, rep, _) in enumerate(table):
        t, n = re.subn(pat, rep, t); counts[i] += n
    if t != s:
        io.open(p, 'w', encoding='latin-1', newline='').write(t); print('rewrote', p.relative_to(root))
bad = [(table[i][0], counts[i], table[i][2]) for i in range(len(table)) if counts[i] != table[i][2]]
print('counts', counts)
if bad: print('COUNT MISMATCH', bad); sys.exit(1)
print('ok')
```

Die Trefferzahlen (22, 8, 3, 2, 1, 2, 1, 9, 1, 1, 1, 1, 1) sind Erwartungen aus der Messung vom 21.09.; weicht eine ab, bricht das Skript ab und der Implementierer misst nach (`git grep -n`), statt die Zahl anzupassen. Hinweis: `\HAGRID\hagrid\hagrid-output\` trifft 1× in `run_chain_v2dev.bat` (`-StepALog`; `-StepBLog` ist relativ und bleibt) und 1× in `queue_chi_detour_wrap.bat`.

```powershell
python -u "$env:USERPROFILE\hagrid-restructure-evidence\r3\rewrite-runs.py" 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID-r3'
git diff --stat | Select-Object -Last 1     # erwartet: 41 files changed (39 + queue_chi_detour_rerun.ps1 + run_weekend_chain.bat sind in den 39 enthalten -> Zahl aus git diff berichten)
git diff | Select-String '^\+' | Select-String 'hagrid' | Measure-Object     # Stichprobe lesen
```

- [ ] **Step 2: `tools/Migrate-Common.ps1` schreiben**

```powershell
# Gemeinsame Bausteine der beiden Migrationsskripte. ASCII only (PowerShell 5.1 liest BOM-lose Dateien als cp1252).
# Dot-source:  . (Join-Path $PSScriptRoot 'Migrate-Common.ps1')
# Kein Set-StrictMode: die Datei wird in den Aufrufer dot-sourced und darf dessen Regeln nicht aendern.

# Getrackte .gitkeep-Skelette zaehlen nicht als Inhalt: nach `git pull` hat jeder Zielordner eines.
function Has-RealFiles([string] $path) {
    if (-not (Test-Path -LiteralPath $path)) { return $false }
    if (-not (Get-Item -LiteralPath $path).PSIsContainer) { return $true }
    $n = (cmd /c "dir /s /b /a-d `"$path`"" 2>$null | Where-Object { $_ -and ($_ -notlike '*\.gitkeep') } | Measure-Object).Count
    return $n -gt 0
}

# Kollision = dieselbe DATEI (relativ) auf beiden Seiten, .gitkeep ausgenommen. Verzeichnisse auf beiden
# Seiten sind keine Kollision - das ist der Normalfall eines Wiederanlaufs nach Teilabbruch.
function Find-Collisions([string] $src, [string] $dst) {
    $out = @()
    if (-not (Test-Path -LiteralPath $src) -or -not (Test-Path -LiteralPath $dst)) { return $out }
    $files = cmd /c "dir /s /b /a-d `"$src`"" 2>$null | Where-Object { $_ -and ($_ -notlike '*\.gitkeep') }
    foreach ($f in $files) {
        $rel = $f.Substring($src.Length).TrimStart('\')
        if (Test-Path -LiteralPath (Join-Path $dst $rel)) { $out += $rel }
    }
    return $out
}

# Verschiebt src nach dst. Existiert dst nicht: ein Rename der ganzen Ebene. Existiert dst (Skelett oder
# Teilstand): Abstieg um EINE Ebene je Rekursion; Laufordner unter hagrid-matsim-output werden als Ganzes
# umbenannt und nie durchlaufen (lange Pfade bleiben unangetastet).
function Merge-Into([string] $src, [string] $dst, [System.Collections.Generic.List[string]] $log) {
    if (-not (Test-Path -LiteralPath $src)) { return }
    if (-not (Test-Path -LiteralPath $dst)) {
        New-Item -ItemType Directory -Force (Split-Path $dst -Parent) | Out-Null
        Move-Item -LiteralPath $src -Destination $dst
        $log.Add("MOVE  $src -> $dst"); return
    }
    foreach ($child in Get-ChildItem -LiteralPath $src -Force) {
        $target = Join-Path $dst $child.Name
        if ($child.PSIsContainer) { Merge-Into $child.FullName $target $log }
        elseif ($child.Name -eq '.gitkeep' -and (Test-Path -LiteralPath $target)) { Remove-Item -LiteralPath $child.FullName }
        elseif (Test-Path -LiteralPath $target) { throw "Zieldatei existiert (Preflight verfehlt): $target" }
        else { Move-Item -LiteralPath $child.FullName -Destination $target; $log.Add("MOVE  $($child.FullName) -> $target") }
    }
    if (-not (Get-ChildItem -LiteralPath $src -Force)) { Remove-Item -LiteralPath $src -Force; $log.Add("RMDIR $src") }
}

# Inventar ueber robocopy /L (vertraegt Pfade > 260 Zeichen, was Get-ChildItem in PS 5.1 nicht tut).
function Get-Inventory([string] $path) {
    if (-not (Test-Path -LiteralPath $path)) { return [pscustomobject]@{ Files = 0; Dirs = 0; Bytes = 0 } }
    $tmp = Join-Path $env:TEMP ("inv-" + [guid]::NewGuid().ToString('N'))
    $txt = & robocopy $path $tmp /L /E /BYTES /NFL /NDL /NJH /NP /R:0 /W:0 2>&1 | Out-String
    $files = 0; $dirs = 0; $bytes = 0
    if ($txt -match '(?m)^\s*Dirs\s*:\s*(\d+)')  { $dirs  = [int64]$Matches[1] }
    if ($txt -match '(?m)^\s*Files\s*:\s*(\d+)') { $files = [int64]$Matches[1] }
    if ($txt -match '(?m)^\s*Bytes\s*:\s*(\d+)') { $bytes = [int64]$Matches[1] }
    return [pscustomobject]@{ Files = $files; Dirs = $dirs; Bytes = $bytes }
}

function Get-LargestFiles([string] $path, [int] $n = 5) {
    if (-not (Test-Path -LiteralPath $path)) { return @() }
    $files = cmd /c "dir /s /b /a-d `"$path`"" 2>$null | Where-Object { $_ }
    return $files | ForEach-Object { try { $i = [IO.FileInfo]::new("\\?\" + $_); [pscustomobject]@{ Path = $_; Bytes = $i.Length; LastWrite = $i.LastWriteTimeUtc } } catch { } } |
        Sort-Object Bytes -Descending | Select-Object -First $n
}
```

- [ ] **Step 3: `tools/migrate-module-layout.ps1` schreiben**

```powershell
<#
.SYNOPSIS
  Zieht die git-ignorierten Inhalte des Moduls von hagrid/ nach hagrid/simulation/ (Repo-Umbau Teil 3,
  Spec 2026-09-21-module-folder-design.md #5.3). Idempotent, wiederanlauffaehig, -Reverse dreht um.
.NOTES
  Reihenfolge je Maschine: git pull -> migrate-input-layout.ps1 -> migrate-module-layout.ps1 -> mvn -q clean install
  Build-Artefakte (target/, run_hagrid_sim.bat, build*.log) werden NICHT migriert, sondern am Quellort geloescht.
#>
param([string] $RepoRoot = (Split-Path $PSScriptRoot -Parent), [switch] $Reverse)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Migrate-Common.ps1')

$old = Join-Path $RepoRoot 'hagrid'
$new = Join-Path $RepoRoot 'hagrid\simulation'
if (-not $Reverse -and -not (Test-Path -LiteralPath (Join-Path $new 'pom.xml'))) { throw "Zielmodul fehlt: $new\pom.xml (erst git pull / checkout auf den Umbau-Stand)" }
# -Reverse laeuft NACH `git checkout <alter Commit>`: git hat die getrackten Dateien dann schon zurueckgelegt,
# hier wandern nur noch die ignorierten Daten in das von git wiederhergestellte Skelett.
$names = 'input', 'hagrid-output', 'hagrid-matsim-output', 'routerCache', 'logs'
$derived = 'target', 'run_hagrid_sim.bat', 'build.log', 'build-package.log'
$from = $old; $to = $new
if ($Reverse) { $from = $new; $to = $old }
function Rel([string] $p) { return $p.Substring($RepoRoot.Length + 1) }

$logDir = Join-Path $new 'logs'
New-Item -ItemType Directory -Force $logDir | Out-Null
$logFile = Join-Path $logDir ("migrate-module-layout-{0:yyyyMMdd-HHmmss}{1}.log" -f (Get-Date), $(if ($Reverse) { '-reverse' } else { '' }))
$log = New-Object System.Collections.Generic.List[string]
$log.Add("migrate-module-layout  RepoRoot=$RepoRoot  Reverse=$Reverse  Start=$(Get-Date -Format s)")

# ---------- Phase 0: Inventar vorher ----------
$before = @{}
foreach ($n in $names) {
    $a = Get-Inventory (Join-Path $from $n); $b = Get-Inventory (Join-Path $to $n)
    $before[$n] = [pscustomobject]@{ Files = $a.Files + $b.Files; Dirs = $a.Dirs + $b.Dirs; Bytes = $a.Bytes + $b.Bytes }
    $log.Add(("BEFORE {0,-22} src files={1} bytes={2} | dst files={3} bytes={4}" -f $n, $a.Files, $a.Bytes, $b.Files, $b.Bytes))
    foreach ($f in (Get-LargestFiles (Join-Path $from $n))) { $log.Add(("  largest {0} {1} {2:u}" -f $f.Bytes, $f.Path, $f.LastWrite)) }
}

# ---------- Phase 1: Preflight, dateiweise ----------
$collisions = @()
foreach ($n in $names) { foreach ($c in (Find-Collisions (Join-Path $from $n) (Join-Path $to $n))) { $collisions += "$n\$c" } }
if ($collisions.Count -gt 0) {
    $log.Add("ABORT collisions=" + $collisions.Count); $log | Set-Content $logFile -Encoding ascii
    throw ("Migration NICHT gestartet, gleichnamige Dateien auf beiden Seiten (von Hand klaeren):`n  " + ($collisions -join "`n  ") + "`nProtokoll: $logFile")
}

# ---------- Phase 2: Verschieben ----------
foreach ($n in $names) { Merge-Into (Join-Path $from $n) (Join-Path $to $n) $log }
if (-not $Reverse) {
    foreach ($d in $derived) {
        $p = Join-Path $old $d
        if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Recurse -Force; $log.Add("DELETE derived $p") }
    }
}

# ---------- Phase 3: Inventar nachher, Summen vergleichen ----------
$ok = $true
foreach ($n in $names) {
    $a = Get-Inventory (Join-Path $from $n); $b = Get-Inventory (Join-Path $to $n)
    $sumF = $a.Files + $b.Files; $sumB = $a.Bytes + $b.Bytes
    $same = ($sumF -eq $before[$n].Files) -and ($sumB -eq $before[$n].Bytes)
    if (-not $same) { $ok = $false }
    $log.Add(("AFTER  {0,-22} src files={1} bytes={2} | dst files={3} bytes={4} | sums {5}" -f $n, $a.Files, $a.Bytes, $b.Files, $b.Bytes, $(if ($same) { 'EQUAL' } else { 'DIFFER' })))
    foreach ($f in (Get-LargestFiles (Join-Path $to $n))) { $log.Add(("  largest {0} {1} {2:u}" -f $f.Bytes, $f.Path, $f.LastWrite)) }
}
$log.Add("End=$(Get-Date -Format s) result=" + $(if ($ok) { 'OK' } else { 'SUMS DIFFER' }))
$log | Set-Content $logFile -Encoding ascii
Write-Host "migrate-module-layout: fertig, Protokoll $logFile"
if (-not $ok) { throw "Summen vorher/nachher weichen ab - Protokoll lesen: $logFile" }
```

- [ ] **Step 4: `tools/Test-MigrateModuleLayout.ps1` schreiben und laufen lassen**

```powershell
# Selbsttest fuer migrate-module-layout.ps1. Faelle: frisch, idempotent, Wiederanlauf nach Teilabbruch,
# Dateikollision, -Reverse, abgeleitete Artefakte, Summenprotokoll, langer Pfad (nur mit JAVA_HOME).
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'migrate-module-layout.ps1'
$fails = 0
function Assert($cond, $msg) { if ($cond) { Write-Host "  ok   $msg" } else { Write-Host "  FAIL $msg"; $script:fails++ } }
function New-Fixture {
    # Maschine direkt nach `git pull`: hagrid/simulation/ mit getracktem Skelett, ignorierte Daten noch unter hagrid/.
    $tmp = Join-Path $env:TEMP ("mml-" + [guid]::NewGuid().ToString('N'))
    $old = Join-Path $tmp 'hagrid'; $new = Join-Path $tmp 'hagrid\simulation'
    foreach ($d in 'input\common\emissions','input\hannover\config','input\lausitz\drt','hagrid-output','hagrid-matsim-output') {
        New-Item -ItemType Directory -Force (Join-Path $new $d) | Out-Null; Set-Content (Join-Path $new "$d\.gitkeep") ''
    }
    Set-Content (Join-Path $new 'input\README.md') 'marker'; Set-Content (Join-Path $new 'pom.xml') '<project/>'
    foreach ($d in 'input\common\emissions','input\hannover\config','input\lausitz\drt') {
        New-Item -ItemType Directory -Force (Join-Path $old $d) | Out-Null; Set-Content (Join-Path $old "$d\probe.txt") $d
    }
    New-Item -ItemType Directory -Force (Join-Path $old 'hagrid-output\RUN1'), (Join-Path $old 'hagrid-matsim-output\RUN1\ITERS\it.0'), (Join-Path $old 'routerCache'), (Join-Path $old 'logs'), (Join-Path $old 'target\classes') | Out-Null
    Set-Content (Join-Path $old 'hagrid-output\RUN1\x.csv') 'x'; Set-Content (Join-Path $old 'hagrid-matsim-output\RUN1\ITERS\it.0\e.xml') 'e'
    Set-Content (Join-Path $old 'routerCache\c.bin') 'c'; Set-Content (Join-Path $old 'logs\old.log') 'l'
    Set-Content (Join-Path $old 'target\classes\A.class') 'a'; Set-Content (Join-Path $old 'run_hagrid_sim.bat') '@echo off'
    return @{ Tmp = $tmp; Old = $old; New = $new }
}
function Inv($p) { return @(cmd /c "dir /s /b /a-d `"$p`"" 2>$null | Where-Object { $_ -and ($_ -notlike '*\.gitkeep') -and ($_ -notlike '*\migrate-module-layout-*.log') }).Count }

$fx = New-Fixture; $tmp = $fx.Tmp; $old = $fx.Old; $new = $fx.New
$nBefore = Inv $old
Write-Host 'Fall 1: frische Migration in das Skelett'
& $script -RepoRoot $tmp
Assert (Test-Path "$new\input\hannover\config\probe.txt")   'input zieht ins Skelett'
Assert (Test-Path "$new\input\hannover\config\.gitkeep")    'getracktes .gitkeep bleibt liegen'
Assert (Test-Path "$new\hagrid-output\RUN1\x.csv")           'hagrid-output zieht'
Assert (Test-Path "$new\hagrid-matsim-output\RUN1\ITERS\it.0\e.xml") 'Laufordner als Ganzes'
Assert (Test-Path "$new\routerCache\c.bin")                  'routerCache zieht'
Assert (Test-Path "$new\logs\old.log")                       'logs zieht'
Assert (-not (Test-Path "$old\target"))                      'target/ wird geloescht, nicht migriert'
Assert (-not (Test-Path "$old\run_hagrid_sim.bat"))          'generiertes Bat wird geloescht'
Assert ((Get-ChildItem $old -Force | Where-Object { $_.Name -ne 'simulation' }).Count -eq 0) 'unter hagrid/ bleibt nur simulation/'
$logs = Get-ChildItem "$new\logs" -Filter 'migrate-module-layout-*.log'
Assert ($logs.Count -eq 1)                                  'ein Protokoll geschrieben'
Assert ((Get-Content $logs[0].FullName -Raw) -match 'result=OK') 'Protokoll meldet OK (Summen gleich)'
Assert (((Get-Content $logs[0].FullName) | Where-Object { $_ -like 'AFTER*' -and $_ -like '*EQUAL' }).Count -eq 5) 'fuenf Paare mit gleichen Summen'

Write-Host 'Fall 2: zweiter Lauf ist ein No-op'
$snap = Inv $new
& $script -RepoRoot $tmp
Assert ((Inv $new) -eq $snap)                               'nichts veraendert'

Write-Host 'Fall 3: Wiederanlauf nach Teilabbruch'
$fx = New-Fixture; $tmp = $fx.Tmp; $old = $fx.Old; $new = $fx.New
# Teilabbruch simulieren: input/common ist schon drueben, der Rest nicht
Move-Item "$old\input\common\emissions\probe.txt" "$new\input\common\emissions\probe.txt"
& $script -RepoRoot $tmp
Assert (Test-Path "$new\input\common\emissions\probe.txt")   'bereits verschobene Datei bleibt'
Assert (Test-Path "$new\input\lausitz\drt\probe.txt")        'Rest wird nachgezogen'
Assert (-not (Test-Path "$old\input"))                       'Quelle ist leer und weg'

Write-Host 'Fall 4: echte Dateikollision bricht VOR dem ersten Move ab'
$fx = New-Fixture; $tmp = $fx.Tmp; $old = $fx.Old; $new = $fx.New
New-Item -ItemType Directory -Force "$new\hagrid-output\RUN1" | Out-Null
Set-Content "$new\hagrid-output\RUN1\x.csv" 'anders'
$threw = $false
try { & $script -RepoRoot $tmp } catch { $threw = $true; $msg = $_.Exception.Message }
Assert $threw                                               'Abbruch'
Assert ($msg -like '*hagrid-output\RUN1\x.csv*')            'Kollision wird benannt'
Assert (Test-Path "$old\input\hannover\config\probe.txt")   'nichts wurde verschoben'
Assert ((Get-Content "$new\hagrid-output\RUN1\x.csv") -eq 'anders') 'Zieldatei unangetastet'

Write-Host 'Fall 5: -Reverse nach git checkout <alt> stellt den alten Ort her'
$fx = New-Fixture; $tmp = $fx.Tmp; $old = $fx.Old; $new = $fx.New
& $script -RepoRoot $tmp
# git checkout <alter Commit> simulieren: getrackte Dateien verschwinden am neuen Ort und erscheinen als Skelett am alten
Remove-Item "$new\pom.xml", "$new\input\README.md"; Get-ChildItem $new -Recurse -Force -Filter '.gitkeep' | Remove-Item
foreach ($d in 'input\common\emissions','input\hannover\config','input\lausitz\drt','hagrid-output','hagrid-matsim-output') { New-Item -ItemType Directory -Force (Join-Path $old $d) | Out-Null; Set-Content (Join-Path $old "$d\.gitkeep") '' }
Set-Content (Join-Path $old 'input\README.md') 'marker'; Set-Content (Join-Path $old 'pom.xml') '<project/>'
& $script -RepoRoot $tmp -Reverse
Assert (Test-Path "$old\input\hannover\config\probe.txt")   'input zurueck, ins Skelett gemischt'
Assert (Test-Path "$old\input\hannover\config\.gitkeep")    'Skelett bleibt'
Assert (Test-Path "$old\hagrid-matsim-output\RUN1\ITERS\it.0\e.xml") 'Laufordner zurueck'
Assert (-not (Test-Path "$old\target"))                     'Reverse stellt keine Build-Artefakte her'
Assert (((Get-ChildItem $new -Force -Recurse -File | Where-Object { $_.Name -notlike 'migrate-module-layout-*.log' }).Count) -eq 0) 'am neuen Ort bleiben nur Protokolle'

Write-Host 'Fall 6: langer Pfad (> 260) in einem Laufordner ueberlebt den Umzug (nur mit JAVA_HOME)'
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $fx = New-Fixture; $tmp = $fx.Tmp; $old = $fx.Old; $new = $fx.New
    $deep = Join-Path $old ('hagrid-matsim-output\RUN_LONG\ITERS\it.0\' + ('y' * 230) + '.txt')
    $src = Join-Path $env:TEMP 'MmlLong.java'
    Set-Content $src 'import java.nio.file.*; public class MmlLong { public static void main(String[] a) throws Exception { Path p = Paths.get(a[0]); if (a.length > 1) { System.out.println(Files.readString(p)); } else { Files.createDirectories(p.getParent()); Files.writeString(p, "deep"); } } }'
    & "$env:JAVA_HOME\bin\java.exe" $src $deep
    Assert ($deep.Length -gt 260)                           "Testpfad hat $($deep.Length) Zeichen"
    & $script -RepoRoot $tmp
    $moved = $deep.Replace("$old\hagrid-matsim-output", "$new\hagrid-matsim-output")
    $read = & "$env:JAVA_HOME\bin\java.exe" $src $moved read
    Assert ($read -eq 'deep')                               'Java liest die Datei am neuen Ort'
} else { Write-Host '  skip JAVA_HOME nicht gesetzt' }

Get-ChildItem $env:TEMP -Directory -Filter 'mml-*' | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
if ($fails -gt 0) { Write-Host "$fails Pruefungen fehlgeschlagen"; exit 1 } else { Write-Host 'alle Pruefungen bestanden'; exit 0 }
```

```powershell
powershell -NoProfile -File tools\Test-MigrateModuleLayout.ps1    # erwartet: alle Pruefungen bestanden (27 ok mit JAVA_HOME, 25 ohne)
```

- [ ] **Step 5: `migrate-input-layout.ps1` auf das gemeinsame Modul und das Endziel heben**

Änderungen in `tools/migrate-input-layout.ps1`:
- nach `$ErrorActionPreference = 'Stop'`: `. (Join-Path $PSScriptRoot 'Migrate-Common.ps1')`; die lokalen Definitionen von `Has-RealFiles` und `Merge-Into` entfallen (Zeilen 20-24 und 58-71). Aufrufe `Merge-Into $p.Src $p.Dst` → `Merge-Into $p.Src $p.Dst $log` mit `$log = New-Object System.Collections.Generic.List[string]` vor Phase 2 und `$log | ForEach-Object { Write-Host "  $_" }` am Ende.
- Zeile 14: `$new = Join-Path $RepoRoot 'hagrid'` → `$new = Join-Path $RepoRoot 'hagrid\simulation'`; Kopfkommentar Zeile 4-5 entsprechend (`hagrid/simulation/input/{common,hannover,lausitz}`).
- Zeile 33: `'hagrid-input', 'hagrid-output', 'hagrid-matsim-output', 'routerCache', 'logs', 'target'` → ohne `'target'`.
- Preflight Zeilen 47-48: statt `Has-RealFiles` beidseitig → `Find-Collisions`:

```powershell
foreach ($p in $phaseA) { foreach ($c in (Find-Collisions $p.Src $p.Dst)) { $collisions += "{0}\{1}" -f (Rel $p.Src), $c } }
foreach ($p in $phaseB) { foreach ($c in (Find-Collisions $p.Now $p.Dst)) { $collisions += "{0}\{1}" -f (Rel $p.Now), $c } }
```

`tools/Test-MigrateInputLayout.ps1`: Fixture `$new = Join-Path $tmp 'hagrid\simulation'` (Zeile 14) und alle `$new\…`-Assertions folgen automatisch; Fall 3/4 (Kollision) legen jetzt dieselbe **Datei** (`probe.txt`) auf beiden Seiten an, nicht nur den Ordner; neuer Fall 5 „Wiederanlauf“: nach einem vollständigen Lauf eine Datei zurück nach `$old\hagrid-input\config\` legen, erneut laufen lassen, erwartet: Datei kommt nach, kein Abbruch.

```powershell
powershell -NoProfile -File tools\Test-MigrateInputLayout.ps1     # erwartet: alle Pruefungen bestanden (19 + neue Assertions; Zahl berichten)
```

- [ ] **Step 6: `check-run-scripts.ps1` nachziehen**

- Zeile 23-24 `$oldStrings`: drei Muster ergänzen (als einfach-quotierte PowerShell-Strings, ASCII):
  `'hagrid[\\/](?!simulation|demand|core|hannover|lausitz|\{|2025)'`, `'[\\/]hagrid["'']'`, `'-pl\s+hagrid\b'`.
- Zeile 26-27 `$oldStringAllowlist`: `'migrate-input-layout.ps1', 'migrate-module-layout.ps1', 'Migrate-Common.ps1', 'Test-MigrateInputLayout.ps1', 'Test-MigrateModuleLayout.ps1', 'check-run-scripts.ps1', 'Test-CheckRunScripts.ps1', 'resync-freight.ps1', 'Test-Installers.ps1'` (`resync-freight` traegt den Fork-Branchnamen `hagrid/2025.0`, `Test-Installers` den Fixture-String `T:\hagrid\input`; je ein Kommentar).
- Zeile 32: `$generatedScripts = 'hagrid\simulation\run_hagrid_sim.bat'`.
- Zeile 152: `Join-Path $RepoRoot 'hagrid\simulation\target\hagrid-1.0-SNAPSHOT.jar'`.
- `-pl`-Regel (Zeilen ~131-137): Regex `'-pl\s+(:?[A-Za-z0-9_./-]+)'`; Auswertung:

```powershell
    foreach ($p in $pls | Where-Object { $_ } | Select-Object -Unique) {
        if ($p.StartsWith(':')) {
            # artifactId-Selektor: in den Modulen der Repo-Wurzel-POM nach <artifactId> suchen
            $want = $p.Substring(1); $found = $false
            $rootPom = Join-Path $plBase 'pom.xml'
            if (Test-Path $rootPom) {
                foreach ($m in [regex]::Matches((Get-Content $rootPom -Raw), '<module>([^<]+)</module>')) {
                    $mp = Join-Path $plBase ($m.Groups[1].Value + '/pom.xml')
                    if ((Test-Path $mp) -and ((Get-Content $mp -Raw) -match "<artifactId>\s*$([regex]::Escape($want))\s*</artifactId>")) { $found = $true }
                }
            }
            if (-not $found) { $findings.Add("$rel : -pl $p - kein Modul mit artifactId '$want' unter $plBase") }
        } elseif (-not (Test-Path (Join-Path $plBase ($p + '/pom.xml')))) { $findings.Add("$rel : -pl $p hat kein pom.xml unter $plBase") }
    }
```

- [ ] **Step 7: `Test-CheckRunScripts.ps1` nachziehen und erweitern**

Fixture (Zeilen 8-12): `"$tmp\hagrid\simulation\target", "$tmp\hagrid\simulation\input"`; `Set-Content "$tmp\pom.xml" '<project><modules><module>hagrid/simulation</module></modules></project>'`; `Set-Content "$tmp\hagrid\simulation\pom.xml" '<project><artifactId>hagrid</artifactId></project>'`; Marker unter `hagrid\simulation\input\README.md`; alle `$tmp\hagrid\target\…`-Jar-Pfade → `$tmp\hagrid\simulation\target\…`; `$good` mit `cd /d "%~dp0..\..\hagrid\simulation"`; Fall 4-6 ebenso. Neue Fälle vor der Schlusszeile:

```powershell
Write-Host 'Fall 8: -pl :hagrid (artifactId-Selektor) ist gueltig'
Write-Script "$tmp\runs\lausitz\plid.bat" "@echo off`r`ncd /d `"%~dp0..\..`"`r`nmvn -pl :hagrid exec:java -Dexec.mainClass=hagrid.core.simulation.HAGRIDSimulationRunner`r`n"
& $check -RepoRoot $tmp -Scripts "$tmp\runs"; Assert ($LASTEXITCODE -eq 0) 'Exit 0 bei -pl :hagrid'
Remove-Item "$tmp\runs\lausitz\plid.bat"

Write-Host 'Fall 9: alter Modulpfad (cd ..\..\hagrid ohne \simulation) ist ein Befund'
Write-Script "$tmp\runs\lausitz\oldcd.bat" "@echo off`r`ncd /d `"%~dp0..\..\hagrid`"`r`nset `"JAR=target\hagrid-1.0-SNAPSHOT.jar`"`r`n"
$out = & $check -RepoRoot $tmp -Scripts "$tmp\runs"
Assert (($LASTEXITCODE -ne 0) -and (($out -join "`n") -match 'alter String')) 'Exit 1 mit alter-String-Befund'
Remove-Item "$tmp\runs\lausitz\oldcd.bat"

Write-Host 'Fall 10: alter Selektor -pl hagrid ist ein Befund'
Write-Script "$tmp\runs\lausitz\plold.bat" "@echo off`r`ncd /d `"%~dp0..\..`"`r`nmvn -pl hagrid exec:java -Dexec.mainClass=hagrid.core.simulation.HAGRIDSimulationRunner`r`n"
$out = & $check -RepoRoot $tmp -Scripts "$tmp\runs"
Assert (($LASTEXITCODE -ne 0) -and (($out -join "`n") -match '-pl')) 'Exit 1 bei -pl hagrid'
Remove-Item "$tmp\runs\lausitz\plold.bat"
```

```powershell
powershell -NoProfile -File tools\Test-CheckRunScripts.ps1        # erwartet: alle Pruefungen bestanden (11 ok: 8 + 3)
```

- [ ] **Step 8: Migration im Worktree wirklich ausführen, dann statischer Check mit Positivkontrolle**

Die in Task 0 kopierten Inputs sind mit `git mv hagrid/input` (Task 1) schon nach `hagrid/simulation/input/` gewandert. Um die Lage einer Maschine nach `git pull` herzustellen (getracktes Skelett am neuen Ort, ignorierte Daten am alten), werden die ignorierten Dateien einmal zurückgelegt; das Migrationsskript bewegt dann 314 MB echte Daten in ein echtes Skelett:

```powershell
robocopy hagrid\simulation\input hagrid\input /E /MOVE /XF README.md .gitkeep lmd-vehicle-types.xml /NFL /NDL /NJH /NP | Out-Null
git status --short | Where-Object { $_ -notmatch '^[MA]  (runs|tools)/' }   # erwartet: leer (nur ignorierte Dateien wurden bewegt)
powershell -NoProfile -File tools\migrate-module-layout.ps1
Get-ChildItem hagrid | Select-Object Name                                       # demand, simulation
Get-ChildItem hagrid\simulation\logs -Filter 'migrate-module-layout-*.log' | Get-Content | Select-String 'AFTER|result'   # 5x EQUAL, result=OK
Test-Path hagrid\simulation\input\lausitz\config                               # True (Inputs sind drueben)
mvn -q -DskipTests -pl :hagrid -am package                                     # Jar am neuen Ort
powershell -NoProfile -File tools\check-run-scripts.ps1                        # erwartet: "<N> Skripte geprueft, 0 Befunde", N >= 57 (N berichten)
```

Positivkontrolle: in `runs/lausitz/run_base_f140_dev.bat` Zeile 3 zeitweise auf `cd /d "%~dp0..\..\hagrid"` zurücksetzen (Latin-1-Rewrite), Check → genau ein Befund (`alter String`) für diese Datei, danach `git checkout -- runs/lausitz/run_base_f140_dev.bat`.

Hinweis: das Migrationsprotokoll liegt unter `hagrid/simulation/logs/` und ist schon durch die generische Regel `*.log` ignoriert.

- [ ] **Step 9: Commit**

```powershell
git add runs tools
git status --short | Where-Object { $_ -notmatch '^[MA]  (runs|tools)/' }        # erwartet: nur der untracked Log unter hagrid/simulation/logs
git commit -q -m "chore(restructure): run scripts and tools point at hagrid/simulation; -pl :hagrid; restartable migrate-module-layout with shared Migrate-Common; checker learns artifactId selector and stale module paths"
```

---

### Task 3: `.gitignore`, Python, Docs, repo-weites Gate

**Files:**
- Modify: `.gitignore` (18 Zeilen), `analysis/lausitz/kpi/build_kpis.py:5`, `analysis/lausitz/kpi/tests/test_real_married250.py:21`, `analysis/lausitz/kpi/data/README.md:7,109`, `analysis/common/run-monitoring/hc-config.template.json:6`, `analysis/common/run-monitoring/resume-config.template.json:8-10`, `README.md`, `docs/DATA-LAUSITZ.md`, `docs/BACKLOG.md`, `hagrid/simulation/input/README.md:1`

- [ ] **Step 1: `.gitignore`**

```python
import io, re
p = '.gitignore'
s = io.open(p, encoding='utf-8', newline='').read()
pat = re.compile(r'^(!?)hagrid/(?!demand/|simulation/)', re.M)
s, n = pat.subn(r'\1hagrid/simulation/', s)
assert n == 18, n
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('gitignore ok', n)
```

Prüfen: `git check-ignore -v hagrid/simulation/input/lausitz/config/x.xml hagrid/simulation/hagrid-matsim-output/RUN/x hagrid/simulation/logs/a.log hagrid/simulation/run_hagrid_sim.bat hagrid/simulation/test/output/x` → fünf Treffer; `git check-ignore hagrid/simulation/input/README.md` → kein Treffer (Marker bleibt getrackt); `git status --short` zeigt den Migrationslog aus Task 2 nicht mehr.

- [ ] **Step 2: Python und Templates**

- `build_kpis.py:5`: `../../../hagrid/hagrid-matsim-output/…` → `../../../hagrid/simulation/hagrid-matsim-output/…`.
- `test_real_married250.py:21`: `/ "hagrid" / "hagrid-matsim-output"` → `/ "hagrid" / "simulation" / "hagrid-matsim-output"`.
- `kpi/data/README.md:7,109`: `hagrid/input/common/emissions/` → `hagrid/simulation/input/common/emissions/` (2×).
- Templates (Latin-1 byte-transparent): `\\hagrid\\hagrid-output\\logs` → `\\hagrid\\simulation\\hagrid-output\\logs`; `\\hagrid\\hagrid-matsim-output` → `\\hagrid\\simulation\\hagrid-matsim-output`; `\\hagrid\\hagrid-output` → `\\hagrid\\simulation\\hagrid-output`; `"WorkDir": "…\\HAGRID\\hagrid"` → `…\\HAGRID\\hagrid\\simulation"`.

```powershell
python -m pytest analysis\lausitz\kpi\tests -q 2>&1 | Select-Object -Last 2     # erwartet: 489 passed (test_real_married250 skippt ohne den echten Lauf wie bisher)
```

- [ ] **Step 3: Docs**

`README.md`:
- Zeile 9: `hagrid/src/test/java/…` → `hagrid/simulation/src/test/java/…`.
- Baum Zeilen 46-51 ersetzen durch:

```
├── hagrid/
│   ├── demand/{estimation,estimation-batch}/   Jupyter notebooks: Hannover parcel-demand estimation
│   └── simulation/            the single Maven module (packages hagrid.core / hannover / lausitz)
│       ├── src/main/java/hagrid/{core,hannover,lausitz}/…
│       ├── src/test/java/hagrid/{core,hannover,lausitz}/…
│       ├── input/{common,hannover,lausitz}/   git-ignored, see hagrid/simulation/input/README.md
│       ├── hagrid-output/
│       └── hagrid-matsim-output/
```

- Zeile 71: „`hagrid/` is the only Maven module …“ → „`hagrid/` is an umbrella folder: `hagrid/demand/` holds the demand-estimation notebooks, `hagrid/simulation/` is the only Maven module. Java sources …; inputs live under `hagrid/simulation/input/` (git-ignored).“
- Zeile 82: `this is not `hagrid/input/`` → `this is not `hagrid/simulation/input/``.
- Zeile 131 (Inputs-Absatz): Pfade auf `hagrid/simulation/input/…`; Rollout-Satz erweitern: „Checkouts created before 2026-09-21 must run `tools/migrate-input-layout.ps1` (layout before 2026-09-17, no-op otherwise) and `tools/migrate-module-layout.ps1` once, then build with `mvn -q clean install`. `clean` is mandatory: the module directory changed and a stale `target/` would let `shade` pack both layouts.“ Zusatz: „Both scripts write a protocol with file counts and byte sums before and after. Rollback order: `git checkout <previous commit>` first (git moves the tracked skeleton back), then `tools/migrate-module-layout.ps1 -Reverse` (moves the ignored data back into it), then `mvn -q clean install`.“
- Zeile 140: `under `hagrid/`` → `under `hagrid/simulation/``.
- Legacy-Docs: unter dem Baum ein Satz „`docs/legacy/hagrid/` keeps the pre-restructure module documentation unchanged, for reference only.“

`docs/DATA-LAUSITZ.md`: die sechs Fundstellen (`hagrid/input/**`, `hagrid/input/lausitz/`, `HI="hagrid/input/lausitz"`, `hagrid/input/lausitz/config/…`, `.gitignore`-Regel) → `hagrid/simulation/input/…`.

`hagrid/simulation/input/README.md:1`: `# hagrid/input — …` → `# hagrid/simulation/input — …`.

`docs/BACKLOG.md` (Worktree = committeter Stand): Zeile 482 `hagrid/input Bootstrap` → `hagrid/simulation/input Bootstrap`; Zeile 503 `hagrid/input/hannover/demand/` → `hagrid/simulation/input/hannover/demand/`; Zeile 505-506 (`SimulationBatGenerator` … `%~dp0..\..\hagrid`-cd-Form … `hagrid/run_hagrid_sim.bat`) → `%~dp0..\..\hagrid\simulation` und `hagrid/simulation/run_hagrid_sim.bat`; Zeile 508 `hagrid/input/` → `hagrid/simulation/input/`; Zeile 514 `hagrid/src/main/java/…DashboardGenerator.java.bak` → `hagrid/simulation/src/main/java/…`; Zeile 520 `hagrid/PIPELINE_DOCUMENTATION.md` und `hagrid/SETUP_TUTORIAL.md` → `docs/legacy/hagrid/…`. Zeile 489 (`hagrid/2025.0`, Fork-Branch) bleibt.

Legacy-Docs Link-Check (Spec §4): `Select-String -Path docs\legacy\hagrid\*.md -Pattern '\]\([^)#]'` → 0 Treffer (gemessen am 21.09.); Ergebnis in den Report.

- [ ] **Step 4: Repo-weites Gate (Spec §6.6) mit denselben fünf Mustern wie Task 0 Step 4**

```powershell
$ex = @(':!docs/superpowers', ':!docs/METHODS-LOG.md', ':!docs/BACKLOG-DONE.md', ':!analysis/hannover/sweep/provenance', ':!tools/Migrate-Common.ps1', ':!tools/migrate-*.ps1', ':!tools/Test-Migrate*.ps1', ':!tools/check-run-scripts.ps1', ':!tools/Test-CheckRunScripts.ps1', ':!analysis/common/run-monitoring/Test-Installers.ps1')
git grep -nIP 'hagrid[\\/](?!simulation[\\/]|demand[\\/]|core[\\/]|hannover[\\/]|lausitz[\\/]|\{|2025)' -- . $ex
git grep -nIE "[\\/]hagrid[`"']" -- . $ex
git grep -nIP 'Paths?\.(of|get)\(\s*"hagrid"\s*(,\s*"(?!simulation")|\))' -- . $ex
git grep -nIP "Join-Path\s+[^\r\n]*'hagrid'" -- . $ex
git grep -nIP -- '-pl\s+hagrid\b' -- . $ex
```

Erwartet: alle fünf leer. Jeder Treffer ist ein Befund und wird behoben oder mit Begründung in den Report aufgenommen. Die Allowlist (`$ex`) ist die aus Spec §6.6 plus `Test-Installers.ps1` (Befund 3) — jeder Eintrag steht mit einem Satz Begründung im Report.

- [ ] **Step 5: Commit**

```powershell
git add .gitignore analysis README.md docs/DATA-LAUSITZ.md docs/BACKLOG.md hagrid/simulation/input/README.md
git commit -q -m "docs(restructure): ignore rules, KPI paths, monitoring templates, README, DATA-LAUSITZ and backlog follow the module move"
```

---

### Task 4: Belege — Suite, P1, Smoke-Test

**Files:**
- Create: `runs/lausitz/campaigns/run_r3smoke.bat`
- Evidence (außerhalb): `%USERPROFILE%\hagrid-restructure-evidence\r3\{suite.log, after-part3\, p1-diff.txt, smoke\}`

- [ ] **Step 1: Suite aus geleertem `target/`**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
mvn -q clean install 2>&1 | Tee-Object "$env:USERPROFILE\hagrid-restructure-evidence\r3\suite.log" | Select-Object -Last 3
Select-String -Path hagrid\simulation\target\surefire-reports\*.txt -Pattern 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)' | ForEach-Object { $_.Matches[0].Groups[1..4].Value } | Measure-Object -Sum
```

Erwartet: Exit 0; `hagrid` 660/0/0 (Task 1 Step 6), `external/freight` 272/0/0/9.

- [ ] **Step 2: P1-Probe und Hashvergleich (Befund 5)**

```powershell
Set-Location 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID-r3\hagrid\simulation'
Get-ChildItem hagrid-output, hagrid-matsim-output -Directory -Filter '*rsprobe*' -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
$sc = 'concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=2,jspritIter=10,freight=true,tag=rsprobe,kpiDashboard=false'
& "$env:JAVA_HOME\bin\java.exe" '@vmargs_dev.txt' '-Dhagrid.pipeline.root=.' -cp target\hagrid-1.0-SNAPSHOT.jar hagrid.lausitz.drt.PrepareLausitzDrtInputs $sc; $LASTEXITCODE   # 0
$ev = Join-Path $env:USERPROFILE 'hagrid-restructure-evidence'
powershell -NoProfile -File "$ev\probe.ps1" -Module (Get-Location).Path -Jar target\hagrid-1.0-SNAPSHOT.jar -Out "$ev\r3\after-part3" -PrepareClass hagrid.lausitz.drt.PrepareLausitzDrtInputs -HashOnly
$a = Get-Content "$ev\after\hashes.txt" | Where-Object { $_ -like 'hagrid-output\*' }
$b = Get-Content "$ev\r3\after-part3\hashes.txt" | Where-Object { $_ -like 'hagrid-output\*' }
"after: $($a.Count)  after-part3: $($b.Count)"
Compare-Object $a $b | Tee-Object "$ev\r3\p1-diff.txt"
```

Erwartet: 7 gegen 7 Zeilen; Differenz höchstens die Zeile `…drt_inputs.properties`. Deren Inhalt unterscheidet sich erwartbar in den `#`-Zeitstempelzeilen und im Wert der Pipeline-Wurzel (Worktree `HAGRID-r3\hagrid\simulation` statt `HAGRID\hagrid`); nach Entfernen der `#`-Zeilen und Ersetzen beider Wurzelpfade durch `<root>` muss `Compare-Object` leer sein. Alles andere identisch. Der Lauf schreibt nur in `hagrid-output/` des Worktrees.

- [ ] **Step 3: Smoke-Skript anlegen (Spec §6.4)**

`runs/lausitz/campaigns/run_r3smoke.bat`, per PowerShell mit `WriteAllText` (CRLF, kein BOM):

```
@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\..\..\hagrid\simulation"
set "JAVA_EXE="
if defined HAGRID_JAVA_EXE if exist "%HAGRID_JAVA_EXE%" set "JAVA_EXE=%HAGRID_JAVA_EXE%"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%J in (java.exe) do set "JAVA_EXE=%%~$PATH:J"
if not defined JAVA_EXE ( echo No java.exe found & exit /b 1 )
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "JAR=target\hagrid-1.0-SNAPSHOT.jar"
if not exist "%JAR%" ( echo JAR not found %JAR% & exit /b 1 )
if not exist "hagrid-matsim-output\logs" mkdir "hagrid-matsim-output\logs"
rem ====================================================================================
rem END-TO-END SMOKE fuer den Modulumzug nach hagrid/simulation (Spec 2026-09-21 #6.4).
rem Passenger-only DRT_BASELINE, EINE Iteration, kein jsprit, kein LMD. Prueft Input-
rem aufloesung, log4j-Dateiappender, Iterationsausgabe, run_metadata.json und den
rem KPI-Dashboard-Trigger ueber zwei Ordnerebenen. Keine zitierfaehigen Zahlen.
rem ====================================================================================
set "TAG=r3smoke"
set "SC=concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=80,maxIter=1,freight=false,tag=%TAG%"
echo [%DATE% %TIME%] SMOKE START :: %SC%
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -cp "%JAR%" hagrid.lausitz.drt.PrepareLausitzDrtInputs "%SC%" >> hagrid-matsim-output\logs\%TAG%_prepare.log 2>&1
if errorlevel 1 ( echo SMOKE PREPARE FAILED & exit /b 2 )
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SC% >> hagrid-matsim-output\logs\%TAG%_console.log 2>&1
set "RC=%ERRORLEVEL%"
echo [%DATE% %TIME%] SMOKE END exit %RC%
endlocal & exit /b %RC%
```

Danach `powershell -NoProfile -File tools\check-run-scripts.ps1` → weiterhin 0 Befunde (das neue Skript wird mitgeprüft). Weist die Szenario-Parserei den Schlüssel `freight` für `drt_baseline` zurück (Konsolenlog lesen), wird er weggelassen und das im Report vermerkt; `run_drt_baseline.bat` kommt ohne ihn aus.

- [ ] **Step 4: Smoke-Test ausführen und prüfen**

```powershell
Set-Location 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID-r3'
$t0 = Get-Date
cmd /c runs\lausitz\campaigns\run_r3smoke.bat; "exit=$LASTEXITCODE  dauer=$((Get-Date) - $t0)"
$m = 'hagrid\simulation\hagrid-matsim-output'
$run = Get-ChildItem $m -Directory | Where-Object { $_.Name -like '*_r3smoke_*' } | Select-Object -First 1
"run dir: $($run.FullName)"
Test-Path (Join-Path $run.FullName 'ITERS\it.0')                                  # True: Iterationsausgabe
Test-Path (Join-Path $run.FullName 'run_metadata.json')                           # True
Test-Path (Join-Path $run.FullName 'analysis\kpis_long.csv')                      # True: Dashboard-Trigger lief (Task 1 scriptFor)
Get-ChildItem (Join-Path $run.FullName 'analysis') -Filter '*.html' | Measure-Object   # >= 1
(Get-Item "$m\logs\r3smoke_console.log").Length -gt 0                              # True
Select-String -Path "$m\logs\r3smoke_console.log" -Pattern 'build_kpis|KpiDashboard' | Select-Object -First 3
# log4j: der Datei-Appender aus logging/log4j2_dev.xml schreibt nach hagrid.log.dir
Get-ChildItem "$m\logs" -Filter '*.log' | Where-Object { $_.LastWriteTime -gt $t0 } | Select-Object Name, Length
Select-String -Path "$m\logs\r3smoke_console.log" -Pattern 'No logging configuration|ERROR StatusLogger' | Measure-Object   # 0
```

Erwartet: Exit 0, alle `True`, mindestens ein Dashboard-HTML, keine log4j-Statusfehler. Laufzeit und die Ausgabeliste gehen in den Report; Budget 60 min — dauert es länger, wird der Lauf nicht abgebrochen, aber die Laufzeit als Befund festgehalten (Spec-Erwartung: unter 30 min). Aufräumen: `Remove-Item -Recurse -Force` des `*_r3smoke_*`-Ordners und des `hagrid-output\DRT_BASELINE_13052025_r3smoke`-Ordners **nach** Kopie der `analysis\`-Ausgaben und der drei Logs nach `%USERPROFILE%\hagrid-restructure-evidence\r3\smoke\`.

- [ ] **Step 5: Commit**

```powershell
git add runs/lausitz/campaigns/run_r3smoke.bat
git commit -q -m "test(restructure): end-to-end smoke launcher for the module move (drt_baseline f80, one iteration, no freight)"
```

---

### Task 5: METHODS-LOG, Spec §12, Backlog, README-Rollout

**Files:**
- Modify: `docs/METHODS-LOG.md` (neuer Abschnitt), `docs/superpowers/specs/2026-09-21-module-folder-design.md` (§12), `docs/BACKLOG.md` (Folgepunkte)

- [ ] **Step 1: Nummer bestimmen (Spec §7)**

```powershell
git grep -oP '^### 2\.\d+' HEAD -- docs/METHODS-LOG.md | Sort-Object { [int]($_ -split '\.')[-1] } | Select-Object -Last 1
git -C 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID' diff -U0 -- docs/METHODS-LOG.md | Select-String '^\+### 2\.'
git -C 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID' stash list
```

Höchste Nummer aus committetem Stand, uncommittetem Haupt-Checkout und etwaigen Stashes + 1 = neue Nummer (Stand 21.09.: §2.73 committet, keine höhere im Arbeitsbaum → §2.74; **beim Schreiben neu messen**).

- [ ] **Step 2: METHODS-LOG-Eintrag** (≤ 25 Zeilen, direkt nach §2.73 im Abschnitt 2)

Inhalt: Modul jetzt `hagrid/simulation/`, Grund (Dachordner mit `demand/`), was sich **nicht** geändert hat (Klassen, Jar, Outputs, Property), Belege mit Zahlen aus Task 4 (Suite 659+272, P1 7/7 mit erklärter Zeitstempelzeile, Smoke-Laufzeit und geprüfte Artefakte, Migrationsprotokoll aus Task 2 Step 8 mit fünf gleichen Summen), die Pfadlängen-Messung aus Task 0 (262 heute, 273 danach, `LongPathsEnabled` und die drei Werkzeugergebnisse), und die Ausroll-Regel (Reihenfolge, `clean`, `-Reverse`). Keine Prosa über Absichten.

- [ ] **Step 3: Spec §12 „Planungs- und Ausführungsbefunde“**

Die acht Befunde aus dem Kopf dieses Plans, plus die in Task 0-4 gemessenen Zahlen (Pfadlängen-Ergebnisse, Smoke-Laufzeit, N des Skript-Checks), je ein bis zwei Zeilen.

- [ ] **Step 4: Backlog** (je ≤ 3 Zeilen, `_(added 2026-09-21)_`)

- `[S]` Markerbasierte Repo-Wurzel-Erkennung statt `getParent().getParent()` in `KpiDashboardTrigger` (Spec §5.2).
- `[M]` `LongPathsEnabled` auf Sim und Lausitz-VM setzen (Admin) und auf IVS100 (kein Admin) die Werkzeugkette gegen den längsten Pfad prüfen; Pfade > 260 existieren seit dem `CRASHED_…`-Lauf ohnehin.
- `[S]` Geparkte Altlasten `legacy-phd/` (≈2,7 GB) nach Karenz löschen (Entscheidung C).
- `[S]` `runs/lausitz/campaigns/run_r3smoke.bat` nach dem Ausrollen auf allen Maschinen entfernen oder als Dauer-Smoke behalten.

- [ ] **Step 5: Commit**

```powershell
git add docs/METHODS-LOG.md docs/superpowers/specs/2026-09-21-module-folder-design.md docs/BACKLOG.md
git commit -q -m "docs(restructure): METHODS-LOG entry for the module move (evidence: suite, P1, smoke, migration protocol, path lengths), spec findings, backlog follow-ups"
```

---

### Task 6: Fast-Forward und Migration des Dev-Hauptcheckouts (Controller, nach der Gesamt-Review)

Kein Subagent. Läuft erst, wenn die Gesamt-Review über `hendrik..restructure-3` sauber ist und **kein Lauf** auf dem Dev aktiv ist (`Get-Process java` leer bzw. keine MATSim-JVM).

- [ ] **Step 1: Rückkehrpunkt festhalten (Spec §8)** — im Ledger: `git -C <main> rev-parse HEAD`, `git -C <main> status --short` (erwartet: die drei fremden Docs + ungetrackte Plandateien), `Get-Process java`, Pfadlängen-Zeilen aus Task 0.

- [ ] **Step 2: Fast-Forward**

```powershell
Set-Location 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID'
# Die drei fremd geaenderten Docs blockieren einen FF, der dieselben Dateien anfasst (BACKLOG, METHODS-LOG in Task 3/5):
git stash push -m 'r3-ff: fremde Docs (Emissions-Session)' -- docs/BACKLOG.md docs/METHODS-LOG.md docs/PAPER-RUNS.md
$stash = git rev-parse 'stash@{0}'; "stash=$stash"      # Hash notieren (Ledger)
git merge --ff-only restructure-3
git stash apply $stash                                   # gezielt DIESEN Stash; bei Konflikt: loesen, `git add`, `git restore --staged`, nie committen
Select-String -Path docs/BACKLOG.md, docs/METHODS-LOG.md, docs/PAPER-RUNS.md -Pattern '^<<<<<<<|^>>>>>>>' | Measure-Object   # 0
git stash drop $stash
git status --short                            # M bei den drei Docs (fremd, uncommittet), sonst nichts; ignorierte Altdaten liegen noch unter hagrid/
Get-ChildItem hagrid | Select-Object Name     # demand, simulation, und die Altordner: bin, test, output, sim-input, sim-output, logs, routerCache, hagrid-output, hagrid-matsim-output, input, target, .pytest_cache, devlog(leer)
```

- [ ] **Step 3: Altlasten parken (Entscheidung C), dann migrieren**

```powershell
$park = Join-Path $env:USERPROFILE 'hagrid-parked-inputs\legacy-phd'
New-Item -ItemType Directory -Force $park | Out-Null
foreach ($d in 'bin','test','output','sim-input','sim-output','.pytest_cache') { if (Test-Path "hagrid\$d") { robocopy "hagrid\$d" "$park\$d" /E /MOVE /NFL /NDL /NJH /NP | Out-Null; if (Test-Path "hagrid\$d") { Remove-Item "hagrid\$d" -Recurse -Force } } }
"Geparkt am $(Get-Date -Format s) aus HAGRID\hagrid\ (Repo-Umbau Teil 3, Spec 2026-09-21 #4, Entscheidung C). Loeschung nach Karenz, siehe BACKLOG." | Set-Content "$park\README-parked.txt"
Remove-Item hagrid\build.log, hagrid\build-package.log -ErrorAction SilentlyContinue
if (Test-Path hagrid\devlog) { Remove-Item hagrid\devlog -Recurse -Force }
powershell -NoProfile -File tools\migrate-input-layout.ps1     # No-op erwartet ("fertig", nichts verschoben)
powershell -NoProfile -File tools\migrate-module-layout.ps1    # 149 GB + 7 GB + 314 MB: Renames erster Ebene
Get-ChildItem hagrid | Select-Object Name                       # demand, simulation
Get-ChildItem hagrid\simulation\logs -Filter 'migrate-module-layout-*.log' | Sort-Object LastWriteTime | Select-Object -Last 1 | Get-Content | Select-String 'BEFORE|AFTER|result'
```

Erwartet: fünf `EQUAL`, `result=OK`. Die Summenzeilen gehen wörtlich in den Ledger und in die Memory-Notiz.

- [ ] **Step 4: Bauen und Kurzbeleg**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
mvn -q clean install; $LASTEXITCODE                              # 0
powershell -NoProfile -File tools\check-run-scripts.ps1          # 0 Befunde
Set-Location hagrid\simulation
& "$env:JAVA_HOME\bin\java.exe" '@vmargs_dev.txt' '-Dhagrid.pipeline.root=.' -cp target\hagrid-1.0-SNAPSHOT.jar hagrid.lausitz.drt.PrepareLausitzDrtInputs 'concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=2,jspritIter=10,freight=true,tag=rsprobe,kpiDashboard=false'
```

P1-Hashes wie Task 4 Step 2 gegen `after\hashes.txt` (7 Zeilen).

- [ ] **Step 5: Worktree und Branch aufräumen**

```powershell
Set-Location 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID'
git worktree remove '..\HAGRID-r3'      # verweigert bei ungetrackten Dateien (kopierte Inputs sind ignoriert, kein Hindernis; Reste sichten)
git worktree prune
git branch -d restructure-3
```

Push nur auf Zuruf. Danach Spec §8 für Sim, IVS100, VM — außerhalb dieses Plans.

---

## Selbstprüfung des Plans (durchgeführt beim Schreiben)

- **Spec-Abdeckung:** §1/§2 → Task 1; §3 Tabelle → Task 1 (POM, Java, Tests), Task 2 (Skripte, Tools, Monitoring-Templates in Task 3), Task 3 (`.gitignore`, Python, Docs); §4 → Task 1 (Umzüge, Löschungen), Task 6 (Parken); §5.1 → Task 1 Step 3 + Task 2 Step 1/6; §5.2 → Task 1 Step 2/5; §5.3 → Task 2 Step 2-5 und Step 8; §5.4 → Global Constraints; §5.5 → Task 0 Step 3, Task 2 Fall 6; §5.6 → Task 0 Step 1, Task 6 Step 5; §6.1-6.6 → Task 4 Step 1-4, Task 2 Step 8, Task 3 Step 4; §7 → Task 5 Step 1, Task 6 Step 2; §8 → Task 6; §10 A-C → Task 1 Step 1 (A, B), Task 6 Step 3 (C).
- **Platzhalter:** keine; jede Änderung nennt Datei und Zeile oder liefert den Code.
- **Konsistenz:** `PIPELINE_ROOT` ist ab Task 1 ein `Path`; `scriptFor` mit zwei `getParent()`; Migrationsskript-Name `migrate-module-layout.ps1` und Parameter `-RepoRoot`/`-Reverse` überall gleich; `Merge-Into` hat ab Task 2 drei Parameter (auch in `migrate-input-layout.ps1`); Testzahlen 660 (Task 1) → Task 4/5; Gate-Muster in Task 0 Step 4 und Task 3 Step 4 identisch.
