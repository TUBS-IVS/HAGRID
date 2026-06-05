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

if (Test-Path $TempDir) {
    Write-Host "Removing leftover temp dir..."
    Remove-Item -Recurse -Force $TempDir
}

Write-Host "Cloning freight sources from matsim-libs ($Tag)..."
git clone --no-checkout --depth=1 --filter=blob:none --branch $Tag `
    https://github.com/matsim-org/matsim-libs.git $TempDir

Push-Location $TempDir
git sparse-checkout set contribs/freight/src
git checkout
Pop-Location

Write-Host "Replacing freight/src ..."
Remove-Item -Recurse -Force freight\src
Copy-Item -Recurse "$TempDir\contribs\freight\src" freight\src

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
git status freight/src --short

Write-Host ""
Write-Host "Review changes: git diff freight/src"
Write-Host "Commit when ready: git add freight/src && git commit -m 'sync: freight from matsim-libs $Tag'"
