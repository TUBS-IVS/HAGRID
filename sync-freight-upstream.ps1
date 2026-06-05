# sync-freight-upstream.ps1
# Pulls the latest freight sources from matsim-org/matsim-libs.
# Usage:
#   .\sync-freight-upstream.ps1                        # pulls from main branch
#   .\sync-freight-upstream.ps1 -Tag matsim-2025.0-2025w13  # pulls specific release tag

param(
    [string]$Tag = "main"
)

# "Continue" so native commands writing to stderr don't throw NativeCommandError.
# Invoke-Git checks $LASTEXITCODE explicitly for git failures.
$ErrorActionPreference = "Continue"
$TempDir = "_matsim-sync-tmp"
$StagingDir = "_freight-staging"

# Tag validation - reject anything that looks suspicious
if ($Tag -notmatch '^[a-zA-Z0-9._/-]+$') {
    throw "Invalid tag name: $Tag"
}

# Helper: run git and abort the script if the command fails
function Invoke-Git {
    git @args
    if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') failed (exit $LASTEXITCODE)" }
}

if (Test-Path $TempDir) {
    Write-Host "Removing leftover temp dir..."
    Remove-Item -Recurse -Force $TempDir
}

Write-Host "Cloning freight sources from matsim-libs ($Tag)..."
Invoke-Git clone --no-checkout --depth=1 --filter=blob:none --branch $Tag `
    https://github.com/matsim-org/matsim-libs.git $TempDir

# Run sparse-checkout inside the cloned repo; always restore location
Push-Location $TempDir
try {
    Invoke-Git sparse-checkout set contribs/freight/src contribs/freight/test/input
    Invoke-Git checkout
} finally {
    Pop-Location
}

# Verify that the expected source tree exists in the clone before touching freight\src
if (-not (Test-Path "$TempDir\contribs\freight\src")) {
    Remove-Item -Recurse -Force $TempDir
    throw "contribs/freight/src not found in upstream at tag '$Tag' - aborting"
}

# Copy src to a staging directory first so that freight\src is never removed unless
# the copy succeeds and contains a plausible number of Java files.
if (Test-Path $StagingDir) { Remove-Item -Recurse -Force $StagingDir }
Copy-Item -Recurse "$TempDir\contribs\freight\src" $StagingDir

$javaCount = (Get-ChildItem $StagingDir -Recurse -Filter "*.java" | Measure-Object).Count
if ($javaCount -lt 100) {
    Remove-Item -Recurse -Force $StagingDir
    throw "Staging dir has only $javaCount .java files - aborting to protect freight\src"
}

Write-Host "Replacing freight/src ($javaCount .java files staged)..."
Remove-Item -Recurse -Force freight\src
try {
    Move-Item $StagingDir freight\src
} catch {
    # Restore staging dir to its original location so freight\src is not lost
    if (Test-Path $StagingDir) { Move-Item $StagingDir freight\src }
    throw "Failed to move staging dir to freight\src: $_"
}

# Sync test/input fixtures if present in upstream
if (Test-Path "$TempDir\contribs\freight\test\input") {
    Write-Host "Syncing test/input fixtures..."
    if (Test-Path "freight\test\input") { Remove-Item -Recurse -Force "freight\test\input" }
    if (-not (Test-Path "freight\test")) { New-Item -ItemType Directory "freight\test" | Out-Null }
    Copy-Item -Recurse "$TempDir\contribs\freight\test\input" "freight\test\input"
} else {
    Write-Host "Note: no test/input directory found in upstream at tag '$Tag' - skipping."
}

# Remove non-Java files that sneak in from upstream
$nonJavaFiles = @(
    "freight\src\test\java\org\matsim\freight\logistics\Doxyfile",
    "freight\src\test\java\org\matsim\freight\logistics\doxyfilter.sh"
)
foreach ($f in $nonJavaFiles) {
    if (Test-Path $f) { Remove-Item -Force $f }
}

Remove-Item -Recurse -Force $TempDir

Write-Host ""
Write-Host "Sync complete. Changed files:"
Invoke-Git status freight/src freight/test/input --short

Write-Host ""
Write-Host "IMPORTANT: HAGRID-specific API fixes in these files were overwritten by the sync:"
Write-Host "  freight/src/main/java/org/matsim/freight/carriers/jsprit/NetworkBasedTransportCosts.java"
Write-Host "  freight/src/main/java/org/matsim/freight/carriers/controller/CarrierTimeAndSpaceTourRouter.java"
Write-Host "  freight/src/main/java/org/matsim/freight/carriers/usecases/chessboard/PassengerScenarioCreator.java"
Write-Host "Restore them from git before committing:"
Write-Host "  git restore freight/src/main/java/org/matsim/freight/carriers/jsprit/NetworkBasedTransportCosts.java"
Write-Host "  git restore freight/src/main/java/org/matsim/freight/carriers/controller/CarrierTimeAndSpaceTourRouter.java"
Write-Host "  git restore freight/src/main/java/org/matsim/freight/carriers/usecases/chessboard/PassengerScenarioCreator.java"
Write-Host ""
Write-Host "Review upstream changes: git diff freight/src freight/test/input"
Write-Host "After restoring fixes, commit: git add freight/src freight/test/input && git commit -m 'sync: freight from matsim-libs $Tag'"
