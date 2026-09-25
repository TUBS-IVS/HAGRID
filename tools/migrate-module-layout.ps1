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
# Aufloesen, bevor irgendetwas daraus gebaut wird: ein relativer -RepoRoot (z.B. '.') macht sonst
# jeden Join-Path relativ zum aktuellen Verzeichnis und die Protokollpfade unlesbar.
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
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

# Protokoll immer auf der ZIEL-Seite: vorwaerts hagrid\simulation\logs, rueckwaerts hagrid\logs. Das logs-Paar
# wird in dieses bereits existierende Verzeichnis gemischt, also liegt der Protokollpfad am Ende noch dort.
$logDir = Join-Path $to 'logs'
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

# Phase 2 und 3 unter finally: bricht etwas mitten im Umzug ab, liegt das Protokoll mit dem
# BEFORE-Inventar und jeder MOVE-Zeile trotzdem auf der Platte - ohne das waere der Zwischenstand blind.
$ok = $true
try {
    # ---------- Phase 2: Verschieben ----------
    foreach ($n in $names) { Merge-Into (Join-Path $from $n) (Join-Path $to $n) $log }
    if (-not $Reverse) {
        foreach ($d in $derived) {
            $p = Join-Path $old $d
            if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Recurse -Force; $log.Add("DELETE derived $p") }
        }
    }

    # ---------- Phase 3: Inventar nachher, Summen vergleichen ----------
    foreach ($n in $names) {
        $a = Get-Inventory (Join-Path $from $n); $b = Get-Inventory (Join-Path $to $n)
        $sumF = $a.Files + $b.Files; $sumB = $a.Bytes + $b.Bytes
        $same = ($sumF -eq $before[$n].Files) -and ($sumB -eq $before[$n].Bytes)
        if (-not $same) { $ok = $false }
        $log.Add(("AFTER  {0,-22} src files={1} bytes={2} | dst files={3} bytes={4} | sums {5}" -f $n, $a.Files, $a.Bytes, $b.Files, $b.Bytes, $(if ($same) { 'EQUAL' } else { 'DIFFER' })))
        foreach ($f in (Get-LargestFiles (Join-Path $to $n))) { $log.Add(("  largest {0} {1} {2:u}" -f $f.Bytes, $f.Path, $f.LastWrite)) }
    }

    # ---------- Phase 4: Was unter hagrid\ sonst noch liegt, gehoert nicht zur Migration ----------
    if (-not $Reverse) {
        $rest = @(Get-ChildItem -LiteralPath $old -Force | Where-Object { $_.Name -ne 'simulation' -and $_.Name -ne 'demand' } | ForEach-Object { $_.Name })
        if ($rest.Count -gt 0) {
            $msg = "Unter hagrid\ liegen noch: " + ($rest -join ', ') + " - nicht Teil der Migration, von Hand sichten (Spec 2026-09-21 #4)"
            Write-Warning $msg
            $log.Add("LEFTOVER $msg")
        }
    }
    $log.Add("End=$(Get-Date -Format s) result=" + $(if ($ok) { 'OK' } else { 'SUMS DIFFER' }))
} finally {
    $log | Set-Content $logFile -Encoding ascii
}
Write-Host "migrate-module-layout: fertig, Protokoll $logFile"
if (-not $ok) { throw "Summen vorher/nachher weichen ab - Protokoll lesen: $logFile" }
