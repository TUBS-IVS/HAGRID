# Gemeinsame Bausteine der beiden Migrationsskripte. ASCII only (PowerShell 5.1 liest BOM-lose Dateien als cp1252).
# Dot-source:  . (Join-Path $PSScriptRoot 'Migrate-Common.ps1')
# Kein Set-StrictMode: die Datei wird in den Aufrufer dot-sourced und darf dessen Regeln nicht aendern.

# Getrackte .gitkeep-Skelette zaehlen nicht als Inhalt: nach `git pull` hat jeder Zielordner eines.
function Has-RealFiles([string] $path) {
    if (-not (Test-Path -LiteralPath $path)) { return $false }
    if (-not (Get-Item -LiteralPath $path).PSIsContainer) { return $true }
    $n = (cmd /c "dir /s /b /a-d `"$path`" 2>nul" | Where-Object { $_ -and ($_ -notlike '*\.gitkeep') } | Measure-Object).Count
    return $n -gt 0
}

# Kollision = dieselbe DATEI (relativ) auf beiden Seiten, .gitkeep ausgenommen. Verzeichnisse auf beiden
# Seiten sind keine Kollision - das ist der Normalfall eines Wiederanlaufs nach Teilabbruch.
function Find-Collisions([string] $src, [string] $dst) {
    $out = @()
    if (-not (Test-Path -LiteralPath $src) -or -not (Test-Path -LiteralPath $dst)) { return $out }
    $files = cmd /c "dir /s /b /a-d `"$src`" 2>nul" | Where-Object { $_ -and ($_ -notlike '*\.gitkeep') }
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
# Die Zusammenfassung wird POSITIONELL gelesen, nicht ueber die Beschriftung: auf einem deutschen
# Windows heissen die Zeilen 'Verzeich.:' und 'Dateien:', nur 'Bytes:' ist zufaellig gleich. Ein
# Muster auf 'Dirs'/'Files' liefert dort stumm 0 und der Summenvergleich haengt allein an den Bytes.
# Die drei Zahlenzeilen sind die einzigen mit sechs reinen Ganzzahlen, in der Reihenfolge Dirs/Files/Bytes.
function Get-Inventory([string] $path) {
    if (-not (Test-Path -LiteralPath $path)) { return [pscustomobject]@{ Files = 0; Dirs = 0; Bytes = 0 } }
    $tmp = Join-Path $env:TEMP ("inv-" + [guid]::NewGuid().ToString('N'))
    $txt = & robocopy $path $tmp /L /E /BYTES /NFL /NDL /NJH /NP /R:0 /W:0 2>&1 | Out-String
    $rows = [regex]::Matches($txt, '(?m)^[^\r\n:]+:\s+(\d+)\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s*$')
    if ($rows.Count -lt 3) { throw "robocopy-Zusammenfassung nicht lesbar fuer $path - Inventar waere stumm 0:`n$txt" }
    $n = $rows.Count
    return [pscustomobject]@{ Dirs  = [int64]$rows[$n - 3].Groups[1].Value
                              Files = [int64]$rows[$n - 2].Groups[1].Value
                              Bytes = [int64]$rows[$n - 1].Groups[1].Value }
}

function Get-LargestFiles([string] $path, [int] $n = 5) {
    if (-not (Test-Path -LiteralPath $path)) { return @() }
    $files = cmd /c "dir /s /b /a-d `"$path`" 2>nul" | Where-Object { $_ }
    return $files | ForEach-Object { try { $i = [IO.FileInfo]::new("\\?\" + $_); [pscustomobject]@{ Path = $_; Bytes = $i.Length; LastWrite = $i.LastWriteTimeUtc } } catch { } } |
        Sort-Object Bytes -Descending | Select-Object -First $n
}
