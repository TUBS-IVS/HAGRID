# Boot-triggered sweep resume. The only intervention available while the user is
# away is a colleague pressing the power button, so everything else must be
# automatic and triggered by the boot itself.
# Spec: docs/superpowers/specs/2026-07-31-remote-crash-alerting-design.md
# Dot-sourceable: defining functions must have no side effects. ASCII output only.
#
# Fails LOUD, not quiet (review Finding I2): a config typo or a missing config
# must not exit 0 having silently done nothing - on an unattended boot script
# that is indistinguishable from "nothing needed doing", and nobody is watching
# to tell the difference.
$ErrorActionPreference = 'Stop'

function Write-LocalLog {
    # Re-implemented locally rather than dot-sourcing heartbeat.ps1, so the two
    # scripts stay independently deployable (same reasoning as the fire-and-forget
    # ResumedUrl ping below). Every branch of Invoke-ResumeSweep calls this
    # (review Finding I1): under SYSTEM, Write-Host goes nowhere, so this local
    # file is the only record, ten days later, of whether the boot script ran
    # and what it decided.
    param([string]$Path, [string]$Message)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    try { Add-Content -LiteralPath $Path -Value "$stamp $Message" -Encoding Ascii } catch { }
}

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
    param(
        [string]$Path,
        [string[]]$Tags,
        [string]$JavaExe,
        [Parameter(Mandatory=$true)][string]$JvmArgs,
        [string]$Jar,
        [string]$WorkDir,
        [Parameter(Mandatory=$true)][string]$ArgTemplate
    )
    # ArgTemplate is REQUIRED (no default): the sweep config (dates, iteration
    # counts, delivery windows) has changed before and this script has a real
    # history of stale hardcoded values surviving a config change and quietly
    # producing wrong runs. A default here is exactly how that stale value would
    # creep back in on a boot-triggered relaunch nobody is watching.
    if ($ArgTemplate.IndexOf('{TAG}') -lt 0) { throw 'ArgTemplate must contain a {TAG} placeholder' }
    # JvmArgs is REQUIRED too (review Finding C6): it used to be hardcoded as
    # "-Xmx124g -XX:+AlwaysPreTouch", which is correct for the 128 GB sim-PC and
    # WRONG for the 63.5 GB dev-PC - AlwaysPreTouch commits the entire heap at
    # startup, so a resumed run there either fails to start or thrashes. Same
    # reasoning as ArgTemplate: no default, so a per-machine value must be
    # deliberately configured rather than silently inherited from whichever
    # machine the hardcoded value happened to be written for.
    if ([string]::IsNullOrWhiteSpace($JvmArgs)) { throw 'JvmArgs must not be empty' }
    # WriteAllLines with explicit CRLF: Write/Edit strip CRLF and cmd then misparses.
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('@echo off')
    $lines.Add('setlocal')
    $lines.Add("cd /d `"$WorkDir`"")
    $lines.Add('echo ===== RESUME BATCH START %date% %time% =====')
    # Tracks whether ANY tag failed (review Finding C4): the original batch always
    # emitted RESUME_DONE regardless of exit codes, so a resumed run with e.g. a
    # stale jar path would fail every tag in seconds, print RESUME_DONE anyway, and
    # the heartbeat would then push "sweep finished" and ping progress
    # unconditionally forever - an all-green dashboard while the machine idles for
    # the rest of the absence. Each tag's exit code is captured into its OWN
    # variable (not read back from %ERRORLEVEL% after the following echo, which
    # would read echo's OWN exit code instead of the java process's).
    $lines.Add('set RESUME_ANY_FAILED=0')
    $index = 1
    foreach ($tag in $Tags) {
        # Named $batchArgs, not $args: the automatic $args variable is read by this
        # same file's entry point ~150 lines below, and reusing the name here (even
        # though it is harmless - PowerShell gives every function invocation its own
        # fresh $args, and this function is always called with all parameters bound
        # by name/position, so the local $args starts empty and the overwrite is
        # invisible outside this function) would leave the next reader wondering.
        $batchArgs = $ArgTemplate.Replace('{TAG}', $tag)
        $lines.Add("echo ===== RESUME $index/$($Tags.Count) tag=$tag %time% =====")
        $lines.Add("`"$JavaExe`" $JvmArgs -cp `"$Jar`" hagrid.HAGRIDSimulationRunner $batchArgs")
        $lines.Add("set RESUME${index}_EXITCODE=%ERRORLEVEL%")
        $lines.Add("echo RESUME${index}_EXIT=%RESUME${index}_EXITCODE%")
        $lines.Add("if not `"%RESUME${index}_EXITCODE%`"==`"0`" set RESUME_ANY_FAILED=1")
        $index++
    }
    # Distinct sentinel on failure, deliberately NOT in heartbeat.ps1's
    # $script:CompletionMarkers list: RESUME_DONE must mean "every tag exited 0",
    # not merely "the batch reached its last line".
    $lines.Add('if "%RESUME_ANY_FAILED%"=="0" (echo ===== RESUME_DONE %date% %time% =====) else (echo ===== RESUME_FAILED %date% %time% =====)')
    $encoding = New-Object System.Text.ASCIIEncoding
    [IO.File]::WriteAllText($Path, (($lines -join "`r`n") + "`r`n"), $encoding)
}

function Test-LockFree {
    param([string]$LockPath)
    # Pure predicate: no system probe, so it is deterministically testable.
    return (-not (Test-Path -LiteralPath $LockPath))
}

function Test-LockStale {
    # Review Finding C5: the Step B path writes the lock and never removes it, so
    # auto-resume worked exactly ONCE for the whole absence - every later boot
    # (e.g. after a SECOND crash on day 5) hit the lock, printed "blocked" to a
    # stdout SYSTEM discards, and exited 0, silently, for the rest of the absence.
    # The java.exe probe in Test-CanLaunch is the REAL double-launch guard; the
    # lock only needs to cover the seconds between launch and java appearing, so
    # treating an old lock as abandoned (no staleness bypass while java is still
    # actually running - see Test-CanLaunch) is safe.
    param([string]$LockPath, [double]$StaleHours = 0)
    if (-not (Test-Path -LiteralPath $LockPath)) { return $false }
    if ($StaleHours -le 0) { return $false }
    $age = (Get-Date) - (Get-Item -LiteralPath $LockPath).LastWriteTime
    return ($age.TotalHours -ge $StaleHours)
}

function Test-CanLaunch {
    # $StaleHours defaults to 0, which reproduces the ORIGINAL, pre-review
    # behaviour exactly (any lock file, however old, blocks) - existing callers
    # that pass only $LockPath are unaffected. Invoke-ResumeSweep passes the
    # configured staleness explicitly.
    param([string]$LockPath, [double]$StaleHours = 0)
    if ((-not (Test-LockFree $LockPath)) -and (-not (Test-LockStale $LockPath $StaleHours))) {
        return $false
    }
    $java = @(Get-Process java -ErrorAction SilentlyContinue)
    if ($java.Count -gt 0) { return $false }
    return $true
}

function Test-ContainsReplacePlaceholder {
    # Review Finding I2 residual (PARTIAL in the final review): the original
    # REPLACE scan only inspected [string] values, so an ARRAY like
    # Tags: ["REPLACE-WITH-REAL-TAG-1", ...] passed unchanged. Consequence: a
    # resume-config.json left with placeholder tags installed green, and at the
    # next boot triggered a full ~6h Step A re-run followed by a Step B launch
    # that fails every tag (RESUME_FAILED at least reports it honestly rather
    # than lying, but a week of compute is still gone). This recurses into any
    # enumerable (array) value, checking each element, not just top-level strings.
    param($Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [string]) { return ($Value -like '*REPLACE*') }
    if ($Value -is [System.Collections.IEnumerable]) {
        foreach ($item in $Value) {
            if (Test-ContainsReplacePlaceholder $item) { return $true }
        }
        return $false
    }
    return $false
}

function Test-ResumeConfigValid {
    # Review Finding I2: a config typo or a half-filled config must fail loudly
    # (exit 2), not silently exit 0 having done nothing - which is indistinguishable
    # from "nothing needed doing" on an unattended boot script nobody is watching.
    # Pure function (no filesystem access beyond reading $Cfg's own properties) so
    # it is directly unit-testable against fixture objects.
    param($Cfg)
    $problems = @()
    if ($null -eq $Cfg) {
        $problems += 'config is null (missing or unparseable file)'
        return ,$problems
    }
    $requiredKeys = @('LockPath','Tags','CarrierRoot','RunIdPrefix','OutputRoot','Suffix',
                       'StepALauncher','ArgTemplate','JvmArgs','JavaExe','Jar','WorkDir',
                       'GeneratedBatPath','StartDetached','ResumeLog')
    foreach ($key in $requiredKeys) {
        $val = $Cfg.$key
        if ($null -eq $val -or ($val -is [string] -and [string]::IsNullOrWhiteSpace($val))) {
            $problems += "missing or empty required key: $key"
            continue
        }
        if (Test-ContainsReplacePlaceholder $val) {
            $problems += "key '$key' still contains an unfilled REPLACE placeholder"
        }
    }
    if (@($Cfg.Tags).Count -eq 0) { $problems += 'Tags is empty - nothing to resume' }
    return ,$problems
}

function Invoke-ResumeSweep {
    param([Parameter(Mandatory=$true)][string]$ConfigPath, [switch]$DryRun)

    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        Write-Host "resume: config not found: $ConfigPath"
        return 2
    }
    $cfg = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json

    $configProblems = Test-ResumeConfigValid $cfg
    if ($configProblems.Count -gt 0) {
        foreach ($p in $configProblems) { Write-Host "resume: CONFIG INVALID - $p" }
        Write-LocalLog $cfg.LocalLogPath ("config invalid: " + ($configProblems -join '; '))
        return 2
    }

    # Test-Path on every executable BEFORE writing the lock and BEFORE deleting any
    # directory (review Finding I2): a bad path here must be caught while there is
    # still nothing to clean up, not discovered after a partial output directory
    # has already been removed. Runs in BOTH modes (a pure read, no mutation) so a
    # -DryRun preview surfaces a bad Jar/JavaExe path too, instead of only the real
    # run finding out.
    foreach ($pathKey in @('JavaExe','Jar','StartDetached','StepALauncher')) {
        $p = $cfg.$pathKey
        if (-not (Test-Path -LiteralPath $p)) {
            Write-Host "resume: CONFIG INVALID - $pathKey does not exist: $p"
            Write-LocalLog $cfg.LocalLogPath "config invalid: $pathKey does not exist: $p"
            return 2
        }
    }

    $lock = $cfg.LockPath
    $staleHours = 12.0
    if ($cfg.LockStaleHours) {
        try { $staleHours = [double]$cfg.LockStaleHours } catch { }
    }
    if ((Test-Path -LiteralPath $lock) -and (Test-LockStale $lock $staleHours)) {
        Write-Host "resume: existing lock is stale (age >= $staleHours h) - treating the prior run as abandoned"
        Write-LocalLog $cfg.LocalLogPath "stale lock ignored (age >= $staleHours h): $lock"
    }
    if (-not (Test-CanLaunch $lock $staleHours)) {
        Write-Host 'resume: blocked (lock held or java already running) - nothing to do'
        Write-LocalLog $cfg.LocalLogPath 'blocked: lock held (not stale) or java.exe already running'
        return 0
    }

    $tags = @($cfg.Tags)
    if (-not (Test-StepAComplete $cfg.CarrierRoot $cfg.RunIdPrefix $tags)) {
        Write-Host 'resume: Step A incomplete - would re-run Step A in full'
        Write-LocalLog $cfg.LocalLogPath 'Step A incomplete - re-running in full'
        if ($DryRun) {
            Write-Host 'resume: DRY RUN - Step A would run now; this preview stops here (Step A does not touch OutputRoot, so it cannot change tag completion)'
            return 0
        }
        Set-Content -LiteralPath $lock -Value 'stepA' -Encoding Ascii
        # Pass the working directory explicitly (review Finding I4): a SYSTEM
        # task's CWD is C:\Windows\System32, not the repo, so a boot landing during
        # Step A previously risked an immediate relative-path failure inside
        # StepALauncher. Push-Location/Pop-Location is wrapped in try/finally so the
        # location is restored even if the launcher throws.
        Push-Location -LiteralPath $cfg.WorkDir
        try {
            & $cfg.StepALauncher
        } finally {
            Pop-Location
        }
        Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue
        Write-Host 'resume: Step A finished - proceeding to Step B tag selection in the same invocation'
        Write-LocalLog $cfg.LocalLogPath 'Step A finished - proceeding to Step B selection'
        # Deliberately falls through to Step B selection below instead of
        # returning (review Finding I4): a boot that lands during Step A must not
        # leave the machine idle for the rest of a ten-day absence once Step A
        # itself finishes six hours later.
    }

    $remaining = Select-RemainingTags $cfg.OutputRoot $cfg.RunIdPrefix $tags $cfg.Suffix
    if ($remaining.Count -eq 0) {
        Write-Host 'resume: all tags complete - nothing to do'
        Write-LocalLog $cfg.LocalLogPath 'all tags complete - nothing to do'
        return 0
    }

    $first = $remaining[0]
    Write-Host ("resume: {0} tag(s) remaining, first incomplete = {1}" -f $remaining.Count, $first)
    Write-LocalLog $cfg.LocalLogPath ("{0} tag(s) remaining: {1}" -f $remaining.Count, ($remaining -join ', '))

    if ($DryRun) {
        Write-Host 'resume: DRY RUN - no directory removed, no batch launched'
        Write-Host ("resume: would delete partial output for all {0} remaining tag(s) ({1}) and launch them" -f $remaining.Count, ($remaining -join ', '))
        return 0
    }

    # Delete the partial output directory of EVERY remaining tag, not just the
    # first (review Finding I3): the real 70v2 failure was "the batch moved on to
    # the next tag", so several tags can carry partials at once, and relaunching
    # over leftover partials produces mixed-vintage output because a rerun with
    # the same runId writes into the existing directory rather than starting clean.
    foreach ($tag in $remaining) {
        $partial = Join-Path $cfg.OutputRoot ("{0}_{1}{2}" -f $cfg.RunIdPrefix, $tag, $cfg.Suffix)
        if (Test-Path -LiteralPath $partial) {
            Remove-Item -LiteralPath $partial -Recurse -Force
            Write-Host "resume: removed partial $partial"
            Write-LocalLog $cfg.LocalLogPath "removed partial output: $partial"
        }
    }

    Set-Content -LiteralPath $lock -Value 'stepB' -Encoding Ascii
    $bat = $cfg.GeneratedBatPath
    New-StepBBatch -Path $bat -Tags $remaining -JavaExe $cfg.JavaExe -JvmArgs $cfg.JvmArgs `
        -Jar $cfg.Jar -WorkDir $cfg.WorkDir -ArgTemplate $cfg.ArgTemplate
    Write-LocalLog $cfg.LocalLogPath "generated Step B batch: $bat (tags: $($remaining -join ', '))"

    # start_detached.ps1 wraps cmd /c "cmd > log 2>&1" and sets CurrentDirectory; a
    # direct WMI call would put the redirect outside the cmd string and the run would
    # never start.
    & $cfg.StartDetached -Command $bat -LogFile $cfg.ResumeLog -WorkDir $cfg.WorkDir
    Write-LocalLog $cfg.LocalLogPath "launched Step B via $($cfg.StartDetached)"

    # Dedicated check per event: the name carries the meaning, because the docs do not
    # promise the body reaches the notification.
    if ($cfg.ResumedUrl) {
        $body = "$env:COMPUTERNAME resumed sweep after boot at tag $first ($($remaining.Count) remaining)"
        try {
            Invoke-WebRequest -Uri ($cfg.ResumedUrl.TrimEnd('/') + '/fail') -Method Post -Body $body -UseBasicParsing -TimeoutSec 20 | Out-Null
        } catch {
            # Fire-and-forget by design (review Finding M5): a dropped ping here must
            # not stop the resume itself. Logged rather than fully silent, since the
            # local log is the only trace after ten unattended days.
            Write-LocalLog $cfg.LocalLogPath "ResumedUrl ping failed (non-fatal): $($_.Exception.Message)"
        }
    }
    return 0
}

if ($MyInvocation.InvocationName -ne '.') {
    if ($args.Count -ge 1) {
        $dry = ($args -contains '-DryRun')
        exit (Invoke-ResumeSweep -ConfigPath $args[0] -DryRun:$dry)
    } else {
        # Without this, a misconfigured Task Scheduler action (missing the
        # config-path argument) exits with an unspecified code and looks like a
        # successful no-op run instead of a visible misconfiguration - matches
        # heartbeat.ps1's existing usage/exit-2 pattern (review Finding I2).
        Write-Host 'usage: resume_sweep.ps1 <path-to-resume-config.json> [-DryRun]'
        exit 2
    }
}
