$ErrorActionPreference = 'Stop'
$check = Join-Path $PSScriptRoot 'check-run-scripts.ps1'
$fails = 0
function Assert($cond, $msg) { if ($cond) { Write-Host "  ok   $msg" } else { Write-Host "  FAIL $msg"; $script:fails++ } }
function Write-Script($path, $text) { [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false)) }

$tmp = Join-Path $env:TEMP ("crs-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force "$tmp\hagrid\target", "$tmp\hagrid\input", "$tmp\external", "$tmp\runs\lausitz" | Out-Null
Set-Content "$tmp\pom.xml" '<project/>'          # Repo-Wurzel: pom.xml + external\ (Tiefenpruefung)
Set-Content "$tmp\hagrid\pom.xml" '<project/>'
Set-Content "$tmp\hagrid\input\README.md" 'marker'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$runner = 'hagrid/core/simulation/HAGRIDSimulationRunner.class'
function New-Jar($path, $entries, $mainClass) {
    $zip = [IO.Compression.ZipFile]::Open($path, 'Create')
    if ($mainClass) {
        $m = $zip.CreateEntry('META-INF/MANIFEST.MF')
        $w = New-Object IO.StreamWriter($m.Open())
        $w.Write("Manifest-Version: 1.0`r`nMain-Class: $mainClass`r`n`r`n"); $w.Dispose()
    }
    foreach ($e in $entries) { $zip.CreateEntry($e).Open().Dispose() }
    $zip.Dispose()
}
New-Jar "$tmp\hagrid\target\hagrid-1.0-SNAPSHOT.jar" @($runner) $null
New-Jar "$tmp\hagrid\target\ok.jar"                  @($runner) 'hagrid.core.simulation.HAGRIDSimulationRunner'
New-Jar "$tmp\hagrid\target\badmanifest.jar"         @($runner) 'hagrid.core.simulation.NichtImJar'

$good = "@echo off`r`ncd /d `"%~dp0..\..\hagrid`"`r`nset `"JAR=target\hagrid-1.0-SNAPSHOT.jar`"`r`njava -Dhagrid.pipeline.root=. -cp `"%JAR%`" hagrid.core.simulation.HAGRIDSimulationRunner concept=x`r`n"
$bad  = $good -replace 'hagrid\.core\.simulation\.HAGRIDSimulationRunner', 'hagrid.HAGRIDSimulationRunner'
Write-Script "$tmp\runs\lausitz\good.bat" $good

Write-Host 'Fall 1: korrektes Skript'
& $check -RepoRoot $tmp -Scripts "$tmp\runs"; Assert ($LASTEXITCODE -eq 0) 'Exit 0 bei korrektem Skript'

Write-Host 'Fall 2: alte Main-Klasse'
Write-Script "$tmp\runs\lausitz\bad.bat" $bad
& $check -RepoRoot $tmp -Scripts "$tmp\runs"; Assert ($LASTEXITCODE -ne 0) 'Exit 1 bei Klasse, die nicht im Jar liegt'
Remove-Item "$tmp\runs\lausitz\bad.bat"

Write-Host 'Fall 3: mvn -pl auf ein Modul ohne pom.xml (kein alter String im Skript)'
Write-Script "$tmp\runs\lausitz\pl.bat" "@echo off`r`ncd /d `"%~dp0..\..`"`r`nmvn -pl nosuchmodule exec:java -Dexec.mainClass=hagrid.core.simulation.HAGRIDSimulationRunner`r`n"
$out = & $check -RepoRoot $tmp -Scripts "$tmp\runs"
Assert (($LASTEXITCODE -ne 0) -and (($out -join "`n") -match 'nosuchmodule')) 'Exit 1 nur wegen -pl auf fehlendes Modul'
Remove-Item "$tmp\runs\lausitz\pl.bat"

Write-Host 'Fall 4: Wrapper ruft nach dem cd ins Modul ein Geschwisterskript'
Write-Script "$tmp\runs\lausitz\wrap.bat" "@echo off`r`ncd /d `"%~dp0..\..\hagrid`"`r`ncall run_missing_chain.bat`r`n"
$out = & $check -RepoRoot $tmp -Scripts "$tmp\runs"
Assert (($LASTEXITCODE -ne 0) -and (($out -join "`n") -match 'run_missing_chain')) 'Exit 1 bei unauffindbarer Skriptreferenz'
Remove-Item "$tmp\runs\lausitz\wrap.bat"

Write-Host 'Fall 5: -jar mit Manifest'
Write-Script "$tmp\runs\lausitz\jarok.bat" "@echo off`r`ncd /d `"%~dp0..\..\hagrid`"`r`nset `"JAR=target\ok.jar`"`r`njava -Dhagrid.pipeline.root=. -jar `"%JAR%`" concept=x`r`n"
& $check -RepoRoot $tmp -Scripts "$tmp\runs"; Assert ($LASTEXITCODE -eq 0) 'Exit 0, wenn die Manifest-Main-Class im Jar liegt'
Remove-Item "$tmp\runs\lausitz\jarok.bat"

Write-Host 'Fall 6: -jar, aber die Manifest-Main-Class liegt nicht im Jar'
Write-Script "$tmp\runs\lausitz\jarbad.bat" "@echo off`r`ncd /d `"%~dp0..\..\hagrid`"`r`nset `"JAR=target\badmanifest.jar`"`r`njava -Dhagrid.pipeline.root=. -jar `"%JAR%`" concept=x`r`n"
$out = & $check -RepoRoot $tmp -Scripts "$tmp\runs"
Assert (($LASTEXITCODE -ne 0) -and (($out -join "`n") -match 'NichtImJar')) 'Exit 1, wenn die Manifest-Main-Class fehlt'
Remove-Item "$tmp\runs\lausitz\jarbad.bat"

# Der Chainer bekommt das Step-B-Batch nur als Argumentwert: kein "call", kein "-File".
# Genau diese Stelle war in run_chain_v2dev.bat monatelang blind.
Write-Host 'Fall 7: fehlendes Batch in Argumentposition (-StepBBat <name>.bat)'
Write-Script "$tmp\runs\lausitz\argref.ps1" "cmd.exe /c chain -StepBBat `"run_missing.bat`"`r`n"
$out = & $check -RepoRoot $tmp -Scripts "$tmp\runs"
Assert (($LASTEXITCODE -ne 0) -and (($out -join "`n") -match 'run_missing\.bat')) 'Exit 1 bei Skriptreferenz in Argumentposition'
Remove-Item "$tmp\runs\lausitz\argref.ps1"

# Diskriminierend: gleicher Inhalt, zwei Dateinamen - nur der allowlistete ist kein Befund.
Write-Host 'Fall 8: alter String als DATEN in einem tools-Skript (Allowlist)'
New-Item -ItemType Directory -Force "$tmp\tools" | Out-Null
$asData = "`$old = 'parcel-demand-2-matsim-pipeline\hagrid-input'`r`n"
Write-Script "$tmp\tools\migrate-input-layout.ps1" $asData
Write-Script "$tmp\tools\other-tool.ps1" $asData
$out = & $check -RepoRoot $tmp -Scripts "$tmp\runs", "$tmp\tools"
$txt = $out -join "`n"
Assert (($LASTEXITCODE -ne 0) -and ($txt -match 'other-tool\.ps1') -and ($txt -notmatch 'migrate-input-layout')) `
       'Allowlist deckt nur den genannten Dateinamen: other-tool.ps1 ist ein Befund, migrate-input-layout.ps1 nicht'

Remove-Item -Recurse -Force $tmp
if ($fails -gt 0) { exit 1 } else { Write-Host 'alle Pruefungen bestanden'; exit 0 }
