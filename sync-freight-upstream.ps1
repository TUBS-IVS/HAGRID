# sync-freight-upstream.ps1
# Pulls the latest freight sources from matsim-org/matsim-libs.
# Usage:
#   .\sync-freight-upstream.ps1                        # pulls from main branch
#   .\sync-freight-upstream.ps1 -Tag matsim-2025.0-2025w13  # pulls specific release tag

param(
    [string]$Tag = "main"
)

$ErrorActionPreference = "Stop"
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
    Invoke-Git sparse-checkout set contribs/freight/src
    Invoke-Git checkout
} finally {
    Pop-Location
}

# Verify that the expected source tree exists in the clone before touching freight\src
if (-not (Test-Path "$TempDir\contribs\freight\src")) {
    Remove-Item -Recurse -Force $TempDir
    throw "contribs/freight/src not found in upstream at tag '$Tag' - aborting"
}

# Copy to a staging directory first so that freight\src is never removed unless
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
Move-Item $StagingDir freight\src

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
Invoke-Git status freight/src --short

Write-Host ""
Write-Host "Review changes: git diff freight/src"
Write-Host "Commit when ready: git add freight/src && git commit -m 'sync: freight from matsim-libs $Tag'"
