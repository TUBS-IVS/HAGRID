# Transferability demand band: LMD_BASELINE on every demand level, sequentially.
#
# Background: PANDA/docs/transferability.md. The band used to be ONE-SIDED, because the
# transferred model sat ~22 % above the only external anchor (BIEK 2020) for reasons
# unknown: central = model, low = model pulled onto the anchor (x0.819).
#
# B7/B8 found the cause -- the OSM residential floor-area proxy billed untyped
# buildings (barns, outbuildings) as single-family homes, which hits a sparsely-tagged
# region far harder than Hannover. Since PANDA derives residential floor area from the
# Zensus 2022 building stock, the transferred level lands ON the anchor (+0.9 %). The
# band is therefore no longer a bias correction but a SYMMETRIC sensitivity around a
# central level that is now anchored:
#
#   low     = x0.90   5,461 parcels  (hagrid-input/.../demand/level_low)
#   central = x1.00   6,058 parcels  (.../demand/level_central)
#   high    = x1.10   6,642 parcels  (.../demand/level_high)
#
# +/-10 % covers the anchor's own imprecision (itself a modelled, not measured,
# quantity) plus the blind model error (bootstrap CI 7.5-13 % wMAPE). The two-sided
# band also makes the demand elasticity two-sided; the previous pair could only
# measure the response downwards.
#
# Superseded inputs are archived, never overwritten, because committed results point at
# them: level_osm_* are the OSM-proxy levels behind the band result in 14e1c9c, and
# level_ctrsnap_* are the Zensus levels behind the bandz_* results -- they still placed
# residual demand by centroid snap (fixed 2026-07-28, PANDA distribution.py). The counts
# above are the post-fix exports; the bandz_* numbers on record came from level_ctrsnap_*.
# Re-running the band on the fixed inputs is a separate decision: the fix moves 12.6 % of
# the parcels spatially at an unchanged level, and the km-based KPIs sit at or below the
# measured solver noise floor anyway (seed test, 2026-07-28).
#
# The run TAG carries the level so output directories preserve which input produced them.
#
# HagridPaths.lmdDemandShapefile() is hard-wired to one path, so the active level
# is copied into place before each run. The level is carried in the run TAG, so
# the output directory name preserves which input produced it.
#
# Runs detached; laptop sleep WILL kill it (see feedback-drt-runs-operational).

$ErrorActionPreference = 'Continue'
$root = 'c:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID'
Set-Location $root

$env:MAVEN_OPTS = '-Xmx16g -Xms4g -Dhagrid.log.dir=hagrid-output/logs --add-opens java.base/java.lang=ALL-UNNAMED'
$demand = Join-Path $root 'parcel-demand-2-matsim-pipeline\hagrid-input\lausitz\demand'

foreach ($lvl in @('central', 'low', 'high')) {
    Write-Output "=== BAND LEVEL $lvl  START $(Get-Date -Format s) ==="

    $src = Join-Path $demand "level_$lvl"
    if (-not (Test-Path $src)) { Write-Output "MISSING $src -- abort"; exit 2 }
    Get-ChildItem $src -File | Copy-Item -Destination $demand -Force

    # Verify by HASH, not by size: .dbf records are fixed-width, so both levels have the
    # same byte length (same row count, same schema) and size proves nothing about content.
    $dbf = 'hagrid_parcel_demand_2025-05-13_(Tuesday).dbf'
    $hStaged = (Get-FileHash (Join-Path $demand $dbf) -Algorithm SHA256).Hash
    $hWanted = (Get-FileHash (Join-Path $src $dbf) -Algorithm SHA256).Hash
    if ($hStaged -ne $hWanted) {
        Write-Output "STAGING MISMATCH for level_$lvl -- staged $($hStaged.Substring(0,16)) != $($hWanted.Substring(0,16)) -- abort"
        exit 3
    }
    Write-Output "active demand verified: level_$lvl sha256=$($hStaged.Substring(0,16))"

    & mvn -pl parcel-demand-2-matsim-pipeline exec:java `
        "-Dexec.mainClass=hagrid.HAGRIDSimulationRunner" `
        "-Dexec.args=concept=LMD_BASELINE,date=2025-05-13,maxIter=0,jspritIter=100,tag=bandz_$lvl,writeDashboard=true"

    Write-Output "=== BAND LEVEL $lvl  EXIT=$LASTEXITCODE  END $(Get-Date -Format s) ==="
}

Write-Output "ALL_DONE $(Get-Date -Format s)"
