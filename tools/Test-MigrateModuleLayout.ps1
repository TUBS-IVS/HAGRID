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
function Inv($p) { return @(cmd /c "dir /s /b /a-d `"$p`" 2>nul" | Where-Object { $_ -and ($_ -notlike '*\.gitkeep') -and ($_ -notlike '*\migrate-module-layout-*.log') }).Count }

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
Assert ((((Get-Content $logs[0].FullName) | Where-Object { $_ -like 'BEFORE input*' }) -join '') -match 'src files=[1-9]') 'Inventar zaehlt Dateien, nicht nur Bytes (robocopy-Zusammenfassung sprachunabhaengig gelesen)'

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
Assert ((Get-ChildItem $new -Force -Recurse -File).Count -eq 0)   'am neuen Ort bleibt nichts zurueck'
Assert ((Get-ChildItem "$old\logs" -Filter 'migrate-module-layout-*-reverse.log').Count -eq 1) 'Reverse-Protokoll liegt auf der Zielseite (hagrid\logs)'

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
