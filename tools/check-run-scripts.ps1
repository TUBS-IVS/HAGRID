<#
.SYNOPSIS  Statische Pruefung aller Run-Skripte (runs/**). Kein Skript wird ausgefuehrt.
.NOTES     Spec 2026-09-17-repo-restructure-design.md Abschnitt 6, Gate 5.
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
    foreach ($c in $classes | Where-Object { $_ } | Select-Object -Unique) {
        $entry = ($c -replace '\.', '/') + '.class'
        $j = $jar; if (-not $j) { $j = Join-Path $RepoRoot 'hagrid\target\hagrid-1.0-SNAPSHOT.jar' }
        if (-not (Test-Path $j)) { $findings.Add("$rel : kein Jar zum Pruefen von $c"); continue }
        if (-not (JarHas $j $entry)) { $findings.Add("$rel : Klasse $c nicht im Jar") }
    }
    foreach ($m in [regex]::Matches($s, '-Dhagrid\.pipeline\.root=([^\s"]+)')) {
        $root = [IO.Path]::GetFullPath((Join-Path $cwd $m.Groups[1].Value))
        if (-not (Test-Path (Join-Path $root 'input\README.md'))) { $findings.Add("$rel : pipeline.root $root ohne input\README.md") }
    }
}
"{0} Skripte geprueft, {1} Befunde" -f $files.Count, $findings.Count
$findings | ForEach-Object { "  $_" }
if ($findings.Count -gt 0) { exit 1 } else { exit 0 }
