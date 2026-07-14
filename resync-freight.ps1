# resync-freight.ps1
# Rebuilds the HAGRID freight patch branch on a new upstream matsim-libs ref and
# repoints the submodule. Deliberate, on-demand - run this when (and only when)
# matsim.version is being bumped. See docs/superpowers/specs/2026-07-13-freight-fork-submodule-design.md
#
# Usage:
#   .\resync-freight.ps1 -UpstreamRef 2025.0 -NewBranch hagrid/2025.0

param(
    [Parameter(Mandatory)][string]$UpstreamRef,
    [Parameter(Mandatory)][string]$NewBranch,
    [string]$Fork = "https://github.com/TUBS-IVS/matsim-libs.git",
    [string]$OldBranch = ""  # default: current submodule branch from .gitmodules
)

$ErrorActionPreference = "Continue"
function Invoke-Git {
    git @args
    if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') failed (exit $LASTEXITCODE)" }
}

# No leading dash (would parse as a git option), then the usual ref charset.
$refPattern = '^[a-zA-Z0-9._/][a-zA-Z0-9._/-]*$'
if ($NewBranch -notmatch $refPattern -or $UpstreamRef -notmatch $refPattern) {
    throw "Invalid ref/branch name"
}
if (-not $OldBranch) {
    $OldBranch = git config -f .gitmodules submodule.external/matsim-libs.branch
    if ($LASTEXITCODE -ne 0) { throw "git config read of submodule branch from .gitmodules failed (exit $LASTEXITCODE)" }
    if (-not $OldBranch) { throw "Could not read current branch from .gitmodules" }
}
if ($OldBranch -notmatch $refPattern) {
    throw "Invalid old branch name: $OldBranch"
}

git config --global core.longpaths true
$work = "$env:USERPROFILE\ml-fork-work"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }

Write-Host "Cloning fork (blob-filtered)..."
Invoke-Git clone --no-checkout --filter=blob:none $Fork $work
Invoke-Git -C $work sparse-checkout set contribs/freight examples/scenarios/logistics-2regions
Write-Host "Fetching upstream ref '$UpstreamRef' and old patch branch '$OldBranch'..."
Invoke-Git -C $work remote add upstream https://github.com/matsim-org/matsim-libs.git
Invoke-Git -C $work fetch --depth=1 upstream $UpstreamRef
# Capture the upstream tip NOW: the next fetch overwrites FETCH_HEAD.
$upstreamSha = git -C $work rev-parse FETCH_HEAD
if ($LASTEXITCODE -ne 0 -or -not $upstreamSha) { throw "Could not resolve upstream ref '$UpstreamRef' after fetch (exit $LASTEXITCODE)" }
Invoke-Git -C $work fetch --depth=10 origin $OldBranch

# collect [HAGRID] patch commits from the old branch, oldest first
$logOutput = git -C $work log --reverse --format="%H %s" "origin/$OldBranch"
if ($LASTEXITCODE -ne 0) { throw "git log on origin/$OldBranch failed (exit $LASTEXITCODE)" }
$patches = $logOutput | Where-Object { $_ -match '\[HAGRID\]' }
if (-not $patches) { throw "No [HAGRID] commits found on $OldBranch - aborting" }
Write-Host "Patch commits to carry over:"
$patches | ForEach-Object { Write-Host "  $_" }

Invoke-Git -C $work checkout -b $NewBranch $upstreamSha
foreach ($p in $patches) {
    $sha = ($p -split ' ')[0]
    git -C $work cherry-pick $sha
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "CONFLICT cherry-picking '$p'." -ForegroundColor Red
        Write-Host "Resolve in $work, then: git cherry-pick --continue, re-run remaining picks,"
        Write-Host "push with: git -C $work push origin $NewBranch"
        Write-Host "NOTE: if the upstream ref now contains a fix natively (e.g. the fuel-attribute"
        Write-Host "compat patch after a core bump), DROP that commit with: git cherry-pick --skip"
        throw "cherry-pick conflict - manual resolution required"
    }
}

Invoke-Git -C $work push origin $NewBranch

Write-Host "Repointing submodule..."
Invoke-Git config -f .gitmodules submodule.external/matsim-libs.branch $NewBranch
try {
    Invoke-Git -C external/matsim-libs fetch origin $NewBranch
    Invoke-Git -C external/matsim-libs checkout FETCH_HEAD
    Invoke-Git -C external/matsim-libs sparse-checkout set contribs/freight examples/scenarios/logistics-2regions
} catch {
    Write-Host ""
    Write-Host "WARNING: .gitmodules was already rewritten to branch '$NewBranch', but the" -ForegroundColor Yellow
    Write-Host "submodule worktree update failed - the repo is in an inconsistent state." -ForegroundColor Yellow
    Write-Host "Either retry the submodule fetch/checkout manually, or restore with:" -ForegroundColor Yellow
    Write-Host "  git checkout -- .gitmodules" -ForegroundColor Yellow
    throw
}

Write-Host ""
Write-Host "Done. Now (manually, same commit):"
Write-Host "  1. bump <matsim.version> in pom.xml to match '$UpstreamRef'"
Write-Host "  2. git add .gitmodules external/matsim-libs pom.xml"
Write-Host "  3. mvn install  (full suite must be green)"
Write-Host "  4. commit: build: bump matsim to <version>, freight branch $NewBranch"
