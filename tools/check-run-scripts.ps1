<#
.SYNOPSIS  Statische Pruefung aller Automatisierungsskripte (runs/**,
           analysis/common/run-monitoring/**, tools/**). Kein Skript wird ausgefuehrt.
.NOTES     Spec 2026-09-17-repo-restructure-design.md Abschnitt 6, Gate 5.
           Geprueft wird je Skript: keine alten Strings; jede Referenz auf ein anderes
           Skript (call x.bat, -File x.ps1, & 'x.bat', und jedes doppelt gequotete
           *.bat/*.ps1 in Argumentposition wie -StepBBat "x.bat") loest auf; -pl <x> hat
           ein pom.xml relativ zum cd-Ziel; JAR=<pfad> existiert; jede Main-Klasse liegt
           im Jar; bei -jar zeigt die Manifest-Main-Class auf einen vorhandenen Eintrag;
           ein reines Maven-Skript landet per cd auf der Repo-Wurzel (pom.xml UND
           external\); -Dhagrid.pipeline.root=<p> zeigt auf einen Baum mit input\README.md.
#>
param(
    [string] $RepoRoot = (Split-Path $PSScriptRoot -Parent),
    [string[]] $Scripts = @(
        (Join-Path (Split-Path $PSScriptRoot -Parent) 'runs'),
        (Join-Path (Split-Path $PSScriptRoot -Parent) 'analysis\common\run-monitoring'),
        (Join-Path (Split-Path $PSScriptRoot -Parent) 'tools')
    )
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$oldStrings = 'parcel-demand-2-matsim-pipeline', 'hagrid-input', 'hagrid\.integrated\.', 'hagrid\.HAGRID',
              'hagrid\.simulation\.', 'hagrid\.utils\.', 'hagrid\.Hagrid(Paths|Config)'
# Diese vier Werkzeuge FUEHREN die alten Namen als Daten mit: die Migration liest den alten
# Layoutpfad, und die beiden Selbsttests bauen ihre Fixtures daraus. Fuer sie ist ein alter
# String kein Befund; alle anderen Regeln gelten fuer sie unveraendert weiter.
$oldStringAllowlist = 'migrate-input-layout.ps1', 'Test-MigrateInputLayout.ps1',
                      'check-run-scripts.ps1', 'Test-CheckRunScripts.ps1'
# Von SimulationBatGenerator erzeugt und per .gitignore ignoriert: auf einem frischen
# Checkout gibt es die Datei nicht, eine Referenz darauf ist also statisch nicht pruefbar.
$generatedScripts = 'hagrid\run_hagrid_sim.bat'
$findings = New-Object System.Collections.Generic.List[string]
$jarCache = @{}
$mainCache = @{}
function JarEntries([string] $jar) {
    if (-not $jarCache.ContainsKey($jar)) {
        $z = [IO.Compression.ZipFile]::OpenRead($jar)
        $jarCache[$jar] = @($z.Entries | ForEach-Object { $_.FullName }); $z.Dispose()
    }
    return $jarCache[$jar]
}
function JarHas([string] $jar, [string] $entry) { return (JarEntries $jar) -contains $entry }
function JarMainClass([string] $jar) {
    if (-not $mainCache.ContainsKey($jar)) {
        $main = $null
        $z = [IO.Compression.ZipFile]::OpenRead($jar)
        $e = @($z.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' })[0]
        if ($e) {
            $sr = New-Object IO.StreamReader($e.Open())
            $txt = $sr.ReadToEnd(); $sr.Dispose()
            $txt = $txt -replace "`r`n ", '' -replace "`n ", ''   # Manifest-Faltung (72 Byte) aufloesen
            if ($txt -match '(?m)^Main-Class:\s*(\S+)') { $main = $Matches[1] }
        }
        $z.Dispose()
        $mainCache[$jar] = $main
    }
    return $mainCache[$jar]
}
# Kommentare fliegen NUR fuer die Referenz- und cd-Suche raus (ein rem-Beispiel ist kein
# Aufruf); die Suche nach alten Strings laeuft bewusst weiter ueber den ganzen Text.
# <# ... #> muss mit, seit tools/** im Pruefumfang liegt: die .SYNOPSIS-Bloecke dort
# nennen Beispielpfade wie & 'x.bat', die kein Aufruf sind.
function StripComments([string] $code) {
    $code = [regex]::Replace($code, '(?s)<#.*?#>', '')
    return (($code -split "`n") | Where-Object { $_ -notmatch '^\s*(rem\s|::|#)' }) -join "`n"
}
function RefsIn([string] $code) {
    $out = @()
    foreach ($m in [regex]::Matches($code, '(?im)^\s*call\s+"([^"]+\.bat)"')) { $out += $m.Groups[1].Value }
    foreach ($m in [regex]::Matches($code, '(?im)^\s*call\s+([^\s"]+\.bat)'))  { $out += $m.Groups[1].Value }
    foreach ($m in [regex]::Matches($code, '(?i)-File\s+"([^"]+\.ps1)"'))      { $out += $m.Groups[1].Value }
    foreach ($m in [regex]::Matches($code, '(?i)-File\s+([^\s"]+\.ps1)'))      { $out += $m.Groups[1].Value }
    foreach ($m in [regex]::Matches($code, "(?i)&\s+'([^']+\.(?:bat|ps1))'"))  { $out += $m.Groups[1].Value }
    # Argumentposition: -StepBBat "x.bat", -File "x.ps1", "..\x.bat" irgendwo auf der Zeile.
    # Bewusst nur DOPPELTE Anfuehrungszeichen: einfache umschliessen in PowerShell reihenweise
    # Fixture-Namen (Join-Path $tmp 'run_ok.bat'), die absichtlich nicht existieren.
    foreach ($m in [regex]::Matches($code, '"([^"\r\n]*\.(?:bat|ps1))"'))      { $out += $m.Groups[1].Value }
    return $out
}
# Ein erzeugtes Skript existiert vor dem ersten Generatorlauf nicht - kein Befund.
function IsGenerated([string] $full) {
    foreach ($g in $generatedScripts) {
        if ($full -eq [IO.Path]::GetFullPath((Join-Path $RepoRoot $g))) { return $true }
    }
    return $false
}
# $null = nicht statisch pruefbar (Variable im Pfad), kein Befund.
function ResolveRef([string] $raw, [string] $scriptDir, [string] $cwd) {
    $p = $raw.Trim()
    if ($p -match '%~dp0') { $p = $p -replace '%~dp0', ($scriptDir + '\') }
    if ($p -match '%[^%]+%') { return $null }
    if ($p -match '\$')      { return $null }
    if ([IO.Path]::IsPathRooted($p)) { return [IO.Path]::GetFullPath($p) }
    if ($p.StartsWith('.\') -or $p.StartsWith('./')) { $p = $p.Substring(2) }
    return [IO.Path]::GetFullPath((Join-Path $cwd $p))
}
$runsRoot = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'runs'))
$files = Get-ChildItem $Scripts -Recurse -File -Include *.bat, *.ps1
foreach ($f in $files) {
    $rel = $f.FullName.Substring($RepoRoot.Length + 1)
    $s = [IO.File]::ReadAllText($f.FullName)
    # Startskript = liegt unter runs/**. Nur dort beschreiben die Regeln zu -pl, JAR,
    # Main-Klasse, Manifest, Maven-Wurzel und pipeline.root etwas Pruefbares. Die
    # Werkzeuge unter analysis/common/run-monitoring/** und tools/** starten keinen Lauf;
    # fuer sie gelten die alten Strings und die Skriptreferenzen.
    $isRunScript = $f.FullName.StartsWith($runsRoot, [StringComparison]::OrdinalIgnoreCase)
    if ($f.Name -notin $oldStringAllowlist) {
        foreach ($o in $oldStrings) { if ($s -match $o) { $findings.Add("$rel : alter String '$o'") } }
    }

    # cd-Ziel: Batch "cd /d "%~dp0<rel>"" / absolut, PowerShell Set-Location oder
    # Start-Process -WorkingDirectory auf $root bzw. $repo; zuletzt ein -WorkDir "<abs>",
    # mit dem ein Wrapper das Arbeitsverzeichnis des aufgerufenen Skripts setzt.
    # Gelesen wird der kommentarfreie Text: ein auskommentiertes cd ist kein cd.
    $code = StripComments $s
    $cwd = $f.DirectoryName
    $hasCd = $true
    if ($code -match 'cd /d "%~dp0([^"]*)"') { $cwd = [IO.Path]::GetFullPath((Join-Path $f.DirectoryName $Matches[1])) }
    elseif ($code -match "cd /d `"([A-Za-z]:\\[^`"]+)`"") { $cwd = $Matches[1] }
    elseif (($code -match "(?:Set-Location|-WorkingDirectory)\s+\`$(?:root|repo)\b") -and ($code -match "\`$(?:root|repo)\s*=\s*'([^']+)'")) { $cwd = $Matches[1] }
    elseif ($code -match '-WorkDir\s+"([A-Za-z]:\\[^"]+)"') { $cwd = $Matches[1] }
    else { $hasCd = $false }

    # Referenzen auf andere Skripte
    foreach ($r in (RefsIn $code | Select-Object -Unique)) {
        $t = ResolveRef $r $f.DirectoryName $cwd
        if ($t -and -not (Test-Path $t) -and -not (IsGenerated $t)) { $findings.Add("$rel : Skriptreferenz '$r' zeigt ins Leere: $t") }
    }
    if (-not $isRunScript) { continue }

    # -pl gegen das cd-Ziel (ohne cd: gegen die Repo-Wurzel)
    $plBase = if ($hasCd) { $cwd } else { $RepoRoot }
    $pls = @()
    $pls += [regex]::Matches($s, '-pl\s+([A-Za-z0-9_./-]+)') | ForEach-Object { $_.Groups[1].Value }
    $pls += [regex]::Matches($s, "'-pl',\s*'([^']+)'") | ForEach-Object { $_.Groups[1].Value }
    foreach ($p in $pls | Where-Object { $_ } | Select-Object -Unique) {
        if (-not (Test-Path (Join-Path $plBase ($p + '/pom.xml')))) { $findings.Add("$rel : -pl $p hat kein pom.xml unter $plBase") }
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
    # -jar: das Manifest entscheidet, welche Klasse wirklich startet
    if ($jar -and ($s -match '-jar\s+"?%JAR%"?')) {
        $mc = JarMainClass $jar
        if (-not $mc) { $findings.Add("$rel : -jar, aber kein Main-Class im Manifest von $jar") }
        elseif (-not (JarHas $jar (($mc -replace '\.', '/') + '.class'))) { $findings.Add("$rel : Manifest-Main-Class $mc liegt nicht im Jar $jar") }
    }
    # Reines Maven-Skript: das cd-Ziel MUSS die Repo-Wurzel sein (pom.xml und external\)
    $hasMvn = ($s -match '(?m)^\s*(call\s+)?mvn\s') -or ($s -match '&\s+mvn\s') -or ($s -match "'mvn ")
    if ($hasMvn -and ($s -notmatch 'set\s+"?JAR=') -and ($s -notmatch '-Dhagrid\.pipeline\.root=')) {
        if (-not ((Test-Path (Join-Path $cwd 'pom.xml')) -and (Test-Path (Join-Path $cwd 'external')))) {
            $findings.Add("$rel : Maven-Skript, aber cd-Ziel '$cwd' ist nicht die Repo-Wurzel (pom.xml + external\)")
        }
    }
    foreach ($m in [regex]::Matches($s, '-Dhagrid\.pipeline\.root=([^\s"]+)')) {
        $root = [IO.Path]::GetFullPath((Join-Path $cwd $m.Groups[1].Value))
        if (-not (Test-Path (Join-Path $root 'input\README.md'))) { $findings.Add("$rel : pipeline.root $root ohne input\README.md") }
    }
}
"{0} Skripte geprueft, {1} Befunde" -f $files.Count, $findings.Count
$findings | ForEach-Object { "  $_" }
if ($findings.Count -gt 0) { exit 1 } else { exit 0 }
