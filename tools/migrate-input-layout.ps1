<#
.SYNOPSIS
  Zieht die lokalen, git-ignorierten Inputs und Outputs vom alten Modulordner
  (parcel-demand-2-matsim-pipeline/hagrid-input/...) in die neue Gliederung
  (hagrid/input/{common,hannover,lausitz}). Idempotent; bricht bei Kollision ab.
.NOTES
  Laeuft nach `git pull` auf jeder Maschine einmal (Spec 2026-09-17-repo-restructure-design.md #8).
  git mv nimmt ignorierte Dateien nicht mit, deshalb dieses Skript.
#>
param([string] $RepoRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'

$old = Join-Path $RepoRoot 'parcel-demand-2-matsim-pipeline'
$new = Join-Path $RepoRoot 'hagrid'
$in  = Join-Path $new 'input'
if (-not (Test-Path $new)) { throw "Zielmodul fehlt: $new (erst git pull / checkout)" }

# Getrackte .gitkeep-Skelette zaehlen nicht als Inhalt: nach `git pull` hat jeder Zielordner eines,
# und sie muessen liegen bleiben (sonst fehlen auf der Maschine getrackte Dateien).
function Has-RealFiles([string] $path) {
    if (-not (Test-Path $path)) { return $false }
    return @(Get-ChildItem $path -Force -Recurse -File | Where-Object { $_.Name -ne '.gitkeep' }).Count -gt 0
}
function Rel([string] $p) { return $p.Substring($RepoRoot.Length + 1) }

# ---------- Phase 0: Plan aufstellen (nichts wird bewegt) ----------
# Paare, in Ausfuehrungsreihenfolge. Die Input-Paare zeigen auf den Ort NACH Phase A
# (hagrid/hagrid-input/...), geprueft werden sie an ihrem jetzigen Ort.
$hiNow = if (Test-Path (Join-Path $new 'hagrid-input')) { Join-Path $new 'hagrid-input' } else { Join-Path $old 'hagrid-input' }
$hiAfter = Join-Path $new 'hagrid-input'

$phaseA = @()
foreach ($d in 'hagrid-input', 'hagrid-output', 'hagrid-matsim-output', 'routerCache', 'logs', 'target') {
    $phaseA += [pscustomobject]@{ Src = (Join-Path $old $d); Dst = (Join-Path $new $d) }
}
$inputMap = [ordered]@{ 'emissions' = 'common\emissions'; 'lausitz' = 'lausitz' }
foreach ($d in 'config', 'demand', 'geodata', 'hubs', 'network', 'vehicles') { $inputMap[$d] = "hannover\$d" }
$phaseB = @()
foreach ($k in $inputMap.Keys) {
    $phaseB += [pscustomobject]@{ Src = (Join-Path $hiAfter $k); Now = (Join-Path $hiNow $k); Dst = (Join-Path $in $inputMap[$k]) }
}

# ---------- Phase 1: Preflight - alle Kollisionen sammeln, bei einer einzigen abbrechen ----------
$collisions = @()
foreach ($p in $phaseA) { if ((Has-RealFiles $p.Src) -and (Has-RealFiles $p.Dst)) { $collisions += "{0}  <->  {1}" -f (Rel $p.Src), (Rel $p.Dst) } }
foreach ($p in $phaseB) { if ((Has-RealFiles $p.Now) -and (Has-RealFiles $p.Dst)) { $collisions += "{0}  <->  {1}" -f (Rel $p.Now), (Rel $p.Dst) } }
if (Test-Path $hiNow) {
    $known = @($inputMap.Keys)
    $stray = Get-ChildItem $hiNow -Force | Where-Object { $_.Name -notin $known -and $_.Name -ne '.gitkeep' }
    if ($stray) { $collisions += "unbekannte Eintraege unter $(Rel $hiNow): $($stray.Name -join ', ')" }
}
if ($collisions.Count -gt 0) {
    throw ("Migration NICHT gestartet. Zuerst von Hand klaeren:`n  " + ($collisions -join "`n  "))
}

# ---------- Phase 2: Verschieben, in bestehende Skelettordner hineinmischen ----------
function Merge-Into([string] $src, [string] $dst) {
    if (-not (Test-Path $src)) { return }
    if (-not (Test-Path $dst)) {
        New-Item -ItemType Directory -Force (Split-Path $dst -Parent) | Out-Null
        Move-Item -LiteralPath $src -Destination $dst
        Write-Host ("  {0} -> {1}" -f (Rel $src), (Rel $dst)); return
    }
    foreach ($child in Get-ChildItem $src -Force) {
        $target = Join-Path $dst $child.Name
        if ($child.PSIsContainer) { Merge-Into $child.FullName $target }
        elseif ($child.Name -eq '.gitkeep' -and (Test-Path $target)) { Remove-Item -LiteralPath $child.FullName }
        else { Move-Item -LiteralPath $child.FullName -Destination $target }   # Preflight garantiert: kein echtes Ziel
    }
    Remove-Item -LiteralPath $src -Force
    Write-Host ("  {0} => {1} (gemischt)" -f (Rel $src), (Rel $dst))
}
foreach ($p in $phaseA) { Merge-Into $p.Src $p.Dst }
foreach ($p in $phaseB) { Merge-Into $p.Src $p.Dst }
if ((Test-Path $hiAfter) -and -not (Has-RealFiles $hiAfter)) { Remove-Item -Recurse -Force $hiAfter }
if ((Test-Path $old) -and -not (Has-RealFiles $old)) { Remove-Item -Recurse -Force $old }
elseif (Test-Path $old) { Write-Warning "Im alten Modulordner liegen noch Dateien: $(Rel $old) - von Hand sichten." }

if (-not (Test-Path (Join-Path $in 'README.md'))) {
    Write-Warning "Root-Marker $in\README.md fehlt: ist der Checkout auf dem Umbau-Stand?"
}
Write-Host 'migrate-input-layout: fertig'
