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
if ($fails -gt 0) { exit 1 } else { Write-Host 'alle Pruefungen bestanden'; exit 0 }
