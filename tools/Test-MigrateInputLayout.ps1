# Selbsttest fuer migrate-input-layout.ps1: baut ein Fixture im alten Layout, migriert, prueft
# Ziel-Layout, Idempotenz (zweiter Lauf aendert nichts) und Kollisionsabbruch.
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'migrate-input-layout.ps1'
$fails = 0
function Assert($cond, $msg) { if ($cond) { Write-Host "  ok   $msg" } else { Write-Host "  FAIL $msg"; $script:fails++ } }

function New-Fixture {
    # Zustand einer Maschine direkt nach `git pull`: hagrid/ existiert mit getracktem Skelett
    # (.gitkeep in jedem Input-/Output-Ordner, input/README.md); die echten, ignorierten Daten
    # liegen noch im alten Modulordner.
    $tmp = Join-Path $env:TEMP ("mil-" + [guid]::NewGuid().ToString('N'))
    $old = Join-Path $tmp 'parcel-demand-2-matsim-pipeline'
    $new = Join-Path $tmp 'hagrid\simulation'
    foreach ($d in 'input\common\emissions','input\hannover\config','input\hannover\demand','input\hannover\geodata','input\hannover\hubs','input\hannover\network','input\hannover\vehicles','input\lausitz\config','input\lausitz\drt','input\lausitz\network','hagrid-output','hagrid-matsim-output') {
        New-Item -ItemType Directory -Force (Join-Path $new $d) | Out-Null
        Set-Content (Join-Path $new "$d\.gitkeep") ''
    }
    Set-Content (Join-Path $new 'input\README.md') 'marker'
    foreach ($d in 'config','demand','geodata','hubs','network','vehicles','emissions','lausitz\config','lausitz\drt') {
        New-Item -ItemType Directory -Force (Join-Path $old "hagrid-input\$d") | Out-Null
        Set-Content (Join-Path $old "hagrid-input\$d\probe.txt") $d
    }
    Set-Content (Join-Path $old 'hagrid-input\lausitz\SOURCES.md') 'src'
    New-Item -ItemType Directory -Force (Join-Path $old 'hagrid-output\RUN1'), (Join-Path $old 'hagrid-matsim-output\RUN1'), (Join-Path $old 'routerCache') | Out-Null
    Set-Content (Join-Path $old 'hagrid-output\RUN1\x.csv') 'x'
    return @{ Tmp = $tmp; Old = $old; New = $new }
}

$fx = New-Fixture; $tmp = $fx.Tmp; $old = $fx.Old; $new = $fx.New
Write-Host "Fall 1: Migration in ein Skelett aus getrackten .gitkeep-Dateien"
& $script -RepoRoot $tmp
Assert (Test-Path "$new\input\common\emissions\probe.txt")       'emissions -> input/common'
Assert (Test-Path "$new\input\hannover\config\probe.txt")        'config -> input/hannover'
Assert (Test-Path "$new\input\hannover\vehicles\probe.txt")      'vehicles -> input/hannover'
Assert (Test-Path "$new\input\lausitz\drt\probe.txt")            'lausitz/drt -> input/lausitz'
Assert (Test-Path "$new\input\lausitz\SOURCES.md")               'Dateien direkt unter lausitz/ kommen mit'
Assert (Test-Path "$new\input\hannover\config\.gitkeep")         'getracktes .gitkeep bleibt liegen (sonst dirty tree auf jeder Maschine)'
Assert (Test-Path "$new\hagrid-output\.gitkeep")                 'Output-Skelett bleibt'
Assert (Test-Path "$new\hagrid-output\RUN1\x.csv")               'hagrid-output zieht mit'
Assert (Test-Path "$new\hagrid-matsim-output\RUN1")              'hagrid-matsim-output zieht mit'
Assert (Test-Path "$new\routerCache")                            'routerCache zieht mit'
Assert (-not (Test-Path "$new\hagrid-input"))                    'kein hagrid-input mehr im Modul'
Assert (-not (Test-Path $old))                                   'alter Modulordner ist weg (war leer)'

Write-Host "Fall 2: zweiter Lauf ist ein No-op"
$before = (Get-ChildItem $new -Recurse -File -Force | ForEach-Object { $_.FullName + '|' + $_.LastWriteTimeUtc.Ticks }) -join "`n"
& $script -RepoRoot $tmp
$after = (Get-ChildItem $new -Recurse -File -Force | ForEach-Object { $_.FullName + '|' + $_.LastWriteTimeUtc.Ticks }) -join "`n"
Assert ($before -eq $after) 'zweiter Lauf aendert keine Datei'
Remove-Item -Recurse -Force $tmp

$fx = New-Fixture; $tmp = $fx.Tmp; $old = $fx.Old; $new = $fx.New
Write-Host "Fall 3: fruehe Kollision (Hannover-config) bricht ab, bevor irgendetwas verschoben wurde"
Set-Content (Join-Path $new 'input\hannover\config\probe.txt') 'y'
$threw = $false
try { & $script -RepoRoot $tmp } catch { $threw = $true }
Assert $threw 'Quelle und Ziel beide belegt -> Fehler'
Assert (Test-Path "$old\hagrid-output\RUN1\x.csv")               'Preflight: hagrid-output liegt noch am alten Ort'
Assert (Test-Path "$old\hagrid-input\config\probe.txt")          'Preflight: Inputs liegen noch am alten Ort'
Remove-Item -Recurse -Force $tmp

$fx = New-Fixture; $tmp = $fx.Tmp; $old = $fx.Old; $new = $fx.New
Write-Host "Fall 4: spaete Kollision (letztes Lausitz-Paar) bricht ebenfalls VOR dem ersten Move ab"
Set-Content (Join-Path $new 'input\lausitz\drt\probe.txt') 'z'
$threw = $false
try { & $script -RepoRoot $tmp } catch { $threw = $true }
Assert $threw 'spaete Kollision -> Fehler'
Assert (Test-Path "$old\hagrid-output\RUN1\x.csv")               'Preflight: auch hier nichts verschoben (Outputs)'
Assert (Test-Path "$old\hagrid-input\emissions\probe.txt")       'Preflight: auch hier nichts verschoben (Inputs)'
Remove-Item -Recurse -Force $tmp

$fx = New-Fixture; $tmp = $fx.Tmp; $old = $fx.Old; $new = $fx.New
Write-Host "Fall 5: Wiederanlauf - eine nachtraeglich am alten Ort aufgetauchte Datei kommt nach"
& $script -RepoRoot $tmp
New-Item -ItemType Directory -Force (Join-Path $old 'hagrid-input\config') | Out-Null
Set-Content (Join-Path $old 'hagrid-input\config\late.txt') 'late'
$threw = $false
try { & $script -RepoRoot $tmp } catch { $threw = $true }
Assert (-not $threw)                                             'Wiederanlauf bricht nicht ab: ein Ordner auf beiden Seiten ist keine Kollision'
Assert (Test-Path "$new\input\hannover\config\late.txt")         'nachgereichte Datei kommt am Ziel an'
Assert (Test-Path "$new\input\hannover\config\probe.txt")        'die bereits migrierte Datei bleibt unangetastet liegen'
Assert (-not (Test-Path $old))                                   'alter Modulordner ist wieder weg'
Remove-Item -Recurse -Force $tmp
if ($fails -gt 0) { Write-Host "$fails Pruefungen fehlgeschlagen"; exit 1 } else { Write-Host 'alle Pruefungen bestanden'; exit 0 }
