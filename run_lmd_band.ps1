# Transferability demand band: LMD_BASELINE on both demand levels, sequentially.
#
# Background: PANDA/docs/transferability.md -- the Lausitz demand LEVEL sits ~22 %
# above the only external anchor (BIEK), the spatial pattern is validated. This
# driver produces the matched pair the band needs.
#
#   central = model level        7,271 parcels  (hagrid-input/.../demand/level_central)
#   low     = BIEK-anchored x0.819  5,956 parcels  (.../demand/level_low)
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

foreach ($lvl in @('central', 'low')) {
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
        "-Dexec.args=concept=LMD_BASELINE,date=2025-05-13,maxIter=0,jspritIter=100,tag=band_$lvl,writeDashboard=true"

    Write-Output "=== BAND LEVEL $lvl  EXIT=$LASTEXITCODE  END $(Get-Date -Format s) ==="
}

Write-Output "ALL_DONE $(Get-Date -Format s)"
