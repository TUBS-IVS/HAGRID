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

function Invoke-ResumeSweep {
    param([Parameter(Mandatory=$true)][string]$ConfigPath, [switch]$DryRun)
    $cfg = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json

    $lock = $cfg.LockPath
    if (-not (Test-CanLaunch $lock)) {
        Write-Host 'resume: blocked (lock held or java already running) - nothing to do'
        return 0
    }

    $tags = @($cfg.Tags)
    if (-not (Test-StepAComplete $cfg.CarrierRoot $cfg.RunIdPrefix $tags)) {
        Write-Host 'resume: Step A incomplete - would re-run Step A in full'
        if (-not $DryRun) {
            Set-Content -LiteralPath $lock -Value 'stepA' -Encoding Ascii
            & $cfg.StepALauncher
            Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue
        }
        return 0
    }

    $remaining = Select-RemainingTags $cfg.OutputRoot $cfg.RunIdPrefix $tags $cfg.Suffix
    if ($remaining.Count -eq 0) {
        Write-Host 'resume: all tags complete - nothing to do'
        return 0
    }

    $first = $remaining[0]
    Write-Host ("resume: {0} tag(s) remaining, first incomplete = {1}" -f $remaining.Count, $first)

    if ($DryRun) {
        Write-Host 'resume: DRY RUN - no directory removed, no batch launched'
        Write-Host ("resume: would delete partial output for {0} and launch {1} tag(s)" -f $first, $remaining.Count)
        return 0
    }

    # Same runId reruns overwrite in place rather than starting clean, so the partial
    # directory of the first incomplete tag must go.
    $partial = Join-Path $cfg.OutputRoot ("{0}_{1}{2}" -f $cfg.RunIdPrefix, $first, $cfg.Suffix)
    if (Test-Path -LiteralPath $partial) {
        Remove-Item -LiteralPath $partial -Recurse -Force
        Write-Host "resume: removed partial $partial"
    }

    Set-Content -LiteralPath $lock -Value 'stepB' -Encoding Ascii
    $bat = $cfg.GeneratedBatPath
    New-StepBBatch $bat $remaining $cfg.JavaExe $cfg.Jar $cfg.WorkDir $cfg.ArgTemplate

    # start_detached.ps1 wraps cmd /c "cmd > log 2>&1" and sets CurrentDirectory; a
    # direct WMI call would put the redirect outside the cmd string and the run would
    # never start.
    & $cfg.StartDetached -Command $bat -LogFile $cfg.ResumeLog -WorkDir $cfg.WorkDir

    # Dedicated check per event: the name carries the meaning, because the docs do not
    # promise the body reaches the notification.
    if ($cfg.ResumedUrl) {
        $body = "$env:COMPUTERNAME resumed sweep after boot at tag $first ($($remaining.Count) remaining)"
        try {
            Invoke-WebRequest -Uri ($cfg.ResumedUrl.TrimEnd('/') + '/fail') -Method Post -Body $body -UseBasicParsing -TimeoutSec 20 | Out-Null
        } catch { }
    }
    return 0
}

if ($MyInvocation.InvocationName -ne '.' -and $args.Count -ge 1) {
    $dry = ($args -contains '-DryRun')
    exit (Invoke-ResumeSweep -ConfigPath $args[0] -DryRun:$dry)
}
