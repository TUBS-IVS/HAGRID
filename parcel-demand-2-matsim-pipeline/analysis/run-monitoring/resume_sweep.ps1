# Boot-triggered sweep resume. The only intervention available while the user is
# away is a colleague pressing the power button, so everything else must be
# automatic and triggered by the boot itself.
# Spec: docs/superpowers/specs/2026-07-31-remote-crash-alerting-design.md
# Dot-sourceable: defining functions must have no side effects. ASCII output only.

function Test-TagComplete {
    param([string]$OutputRoot, [string]$RunIdPrefix, [string]$Tag, [string]$Suffix)
    # Measured 2026-07-31: a crashed run leaves its output directory behind (70v2:
    # 214 files, no dashboard) while a finished run has one (832 files, 1 dashboard).
    # Directory existence is therefore worthless; dashboard existence is the test.
    $dir = Join-Path $OutputRoot ("{0}_{1}{2}" -f $RunIdPrefix, $Tag, $Suffix)
    $analysis = Join-Path $dir 'analysis'
    if (-not (Test-Path -LiteralPath $analysis)) { return $false }
    $found = @(Get-ChildItem -LiteralPath $analysis -Filter 'HAGRID_Dashboard_*.html' -File -ErrorAction SilentlyContinue)
    return ($found.Count -gt 0)
}

function Select-RemainingTags {
    param([string]$OutputRoot, [string]$RunIdPrefix, [string[]]$Tags, [string]$Suffix)
    $remaining = @()
    foreach ($tag in $Tags) {
        if (-not (Test-TagComplete $OutputRoot $RunIdPrefix $tag $Suffix)) { $remaining += $tag }
    }
    # Unary comma suppresses PowerShell's array-unrolling-on-return: without it, a
    # single-element array collapses to a bare string on return, and Task 9's
    # $remaining[0] would then index the STRING (its first character) instead of
    # the array (the first tag), silently breaking the crash-directory cleanup.
    return ,$remaining
}

function Test-StepAComplete {
    param([string]$CarrierRoot, [string]$RunIdPrefix, [string[]]$Tags)
    # Step A is all-or-nothing on purpose: the tag list is a compiled constant, so a
    # partial re-run would need a source edit plus a rebuild - far too fragile for an
    # unattended boot script. Re-running all of Step A costs <=6 h and is deterministic.
    foreach ($tag in $Tags) {
        $file = Join-Path $CarrierRoot ("{0}_{1}\carriers\{0}_{1}_delivery_carriers_routed.xml" -f $RunIdPrefix, $tag)
        if (-not (Test-Path -LiteralPath $file)) { return $false }
    }
    return $true
}

function New-StepBBatch {
    param([string]$Path, [string[]]$Tags, [string]$JavaExe, [string]$Jar, [string]$WorkDir, [Parameter(Mandatory=$true)][string]$ArgTemplate)
    # ArgTemplate is REQUIRED (no default): the sweep config (dates, iteration
    # counts, delivery windows) has changed before and this script has a real
    # history of stale hardcoded values surviving a config change and quietly
    # producing wrong runs. A default here is exactly how that stale value would
    # creep back in on a boot-triggered relaunch nobody is watching.
    if ($ArgTemplate.IndexOf('{TAG}') -lt 0) { throw 'ArgTemplate must contain a {TAG} placeholder' }
    # WriteAllLines with explicit CRLF: Write/Edit strip CRLF and cmd then misparses.
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('@echo off')
    $lines.Add('setlocal')
    $lines.Add("cd /d `"$WorkDir`"")
    $lines.Add('echo ===== RESUME BATCH START %date% %time% =====')
    $index = 1
    foreach ($tag in $Tags) {
        $args = $ArgTemplate.Replace('{TAG}', $tag)
        $lines.Add("echo ===== RESUME $index/$($Tags.Count) tag=$tag %time% =====")
        $lines.Add("`"$JavaExe`" -Xmx124g -XX:+AlwaysPreTouch -cp `"$Jar`" hagrid.HAGRIDSimulationRunner $args")
        $lines.Add("echo RESUME${index}_EXIT=%ERRORLEVEL%")
        $index++
    }
    $lines.Add('echo ===== RESUME_DONE %date% %time% =====')
    $encoding = New-Object System.Text.ASCIIEncoding
    [IO.File]::WriteAllText($Path, (($lines -join "`r`n") + "`r`n"), $encoding)
}

function Test-LockFree {
    param([string]$LockPath)
    # Pure predicate: no system probe, so it is deterministically testable.
    return (-not (Test-Path -LiteralPath $LockPath))
}

function Test-CanLaunch {
    param([string]$LockPath)
    if (-not (Test-LockFree $LockPath)) { return $false }
    $java = @(Get-Process java -ErrorAction SilentlyContinue)
    if ($java.Count -gt 0) { return $false }
    return $true
}
