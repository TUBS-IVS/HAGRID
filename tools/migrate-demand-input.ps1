<#
.SYNOPSIS
  Kopiert die lokalen, git-ignorierten Eingaben des Nachfragemodells vom alten Ort
  (parcel-demand-estimation/{input,output}) in die neue Gliederung
  (hagrid/demand/input/hannover/{raw,osm,notebook-output}). Idempotent; vorhandene Ziele werden nicht ueberschrieben.
.NOTES
  Laeuft nach `git pull` einmal je Maschine. -Source zeigt auf einen anderen Checkout (z. B. den alten master-Ordner). git mv nimmt ignorierte Dateien nicht mit, deshalb dieses Skript.
  Die Konfigurationen in hagrid/demand/model/configs zeigen auf das neue Layout.
#>
param([string] $RepoRoot = (Split-Path $PSScriptRoot -Parent), [string] $Source = "", [switch] $Move)
$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$old = if ($Source) { (Resolve-Path -LiteralPath $Source).Path } else { Join-Path $RepoRoot 'parcel-demand-estimation' }
$new = Join-Path $RepoRoot 'hagrid\demand\input\hannover'
if (-not (Test-Path $old)) { Write-Host "Nichts zu migrieren: $old fehlt."; exit 0 }

function Rel([string] $p) { if ($p.StartsWith($RepoRoot)) { return $p.Substring($RepoRoot.Length + 1) } else { return $p } }

$pairs = @(
    [pscustomobject]@{ Src = (Join-Path $old 'input\osm'); Dst = (Join-Path $new 'osm') },
    [pscustomobject]@{ Src = (Join-Path $old 'input');     Dst = (Join-Path $new 'raw') },
    [pscustomobject]@{ Src = (Join-Path $old 'output');    Dst = (Join-Path $new 'notebook-output') }
)

New-Item -ItemType Directory -Force -Path $new | Out-Null
foreach ($p in $pairs) {
    if (-not (Test-Path $p.Src)) { Write-Host "uebersprungen (fehlt): $(Rel $p.Src)"; continue }
    New-Item -ItemType Directory -Force -Path $p.Dst | Out-Null
    $items = Get-ChildItem -LiteralPath $p.Src -Force | Where-Object { -not ($p.Src -like '*\input' -and $_.Name -eq 'osm') }
    foreach ($item in $items) {
        $target = Join-Path $p.Dst $item.Name
        if (Test-Path $target) { Write-Host "vorhanden, gelassen: $(Rel $target)"; continue }
        if ($Move) { Move-Item -LiteralPath $item.FullName -Destination $target } else { Copy-Item -LiteralPath $item.FullName -Destination $target -Recurse }
        Write-Host "$(if ($Move) { 'verschoben' } else { 'kopiert' }): $(Rel $item.FullName) -> $(Rel $target)"
    }
}
Write-Host "Fertig. Rohdaten: $(Rel (Join-Path $new 'raw')), OSM: $(Rel (Join-Path $new 'osm')), Notebook-Outputs: $(Rel (Join-Path $new 'notebook-output'))"
