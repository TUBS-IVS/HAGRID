# Installs the heartbeat as a SYSTEM Scheduled Task. Idempotent: re-running
# re-deploys the script and replaces the task definition.
# Must run elevated. Run: powershell -NoProfile -ExecutionPolicy Bypass -File install_heartbeat_task.ps1 -ToolsDir C:\Users\<user>\hagrid-tools
#
# Dot-sourceable for testing (see Test-Installers.ps1): the top-level param() is
# intentionally NOT Mandatory (a Mandatory top-level param would interactively
# prompt when the file is dot-sourced with no arguments, which would hang an
# automated test run). Install-HeartbeatTask, invoked only from the guarded entry
# point at the bottom, enforces the real requirement.
param(
    [string]$ToolsDir,
    [string]$TaskName = 'HAGRID-Heartbeat',
    [int]$IntervalMinutes = 5
)

function Test-CmdletsAvailable {
    # Review Finding I7: a single Get-Command check like this would have caught,
    # in one line, the *-ScheduledTask* cmdlet-name bug this review found by
    # actually running the cmdlets (New-ScheduledTaskSettings does not exist; the
    # real cmdlet is New-ScheduledTaskSettingsSet) - BEFORE Register-ScheduledTask
    # ever ran, instead of discovering it after Copy-Item had already deployed the
    # script (a half-successful install).
    param([string[]]$Names)
    $missing = @()
    foreach ($n in $Names) {
        if (-not (Get-Command $n -ErrorAction SilentlyContinue)) { $missing += $n }
    }
    return ,$missing
}

function Test-ContainsReplacePlaceholder {
    # Duplicated from resume_sweep.ps1 (review Finding I2 residual): recurses into
    # array values too, not just top-level strings. hc-config.json has no array
    # keys today, but the check is written generically so a future one is covered
    # for free rather than by inertia.
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

function Test-HeartbeatConfigForInstall {
    # Pure validation (no network, no side effects) so it is directly unit-testable
    # against fixture objects. The actual reachability PING and the StatePath/
    # LocalLogPath writability PROBE happen separately, inside Install-HeartbeatTask,
    # since both need real I/O that cannot be unit-tested here.
    param($Cfg)
    $problems = @()
    foreach ($prop in $Cfg.PSObject.Properties) {
        if (Test-ContainsReplacePlaceholder $prop.Value) {
            $problems += "$($prop.Name) still contains an unfilled REPLACE placeholder"
        }
    }
    foreach ($urlKey in @('AliveUrl','ProgressUrl','SweepFinishedUrl')) {
        $val = $Cfg.$urlKey
        if ([string]::IsNullOrWhiteSpace($val)) { $problems += "$urlKey is empty" }
    }
    # Review Finding I5 (residual): a blacklist of specific wide spellings ('*.log')
    # missed equally wide patterns like '*', '*.*', '*log*', '*.log*'. The property
    # actually being protected is "this pattern identifies the sweep's driver log
    # unambiguously", so check THAT directly: refuse any pattern matching more than
    # one existing file in LogDir right now. Zero matches is allowed through (e.g.
    # installing before the sweep's first run has written anything yet) - only
    # AMBIGUITY (more than one candidate) is refused. Mirrors Read-HeartbeatConfig's
    # own empty-defaults-to-'*.log' behavior so validation reflects what will
    # actually run.
    $logPattern = [string]$Cfg.LogPattern
    if ([string]::IsNullOrWhiteSpace($logPattern)) { $logPattern = '*.log' }
    if (-not (Test-ContainsReplacePlaceholder $logPattern)) {
        $logDir = [string]$Cfg.LogDir
        $matchCount = 0
        if (Test-Path -LiteralPath $logDir) {
            $matchCount = @(Get-ChildItem -LiteralPath $logDir -Filter $logPattern -File -ErrorAction SilentlyContinue).Count
        }
        if ($matchCount -gt 1) {
            $problems += "LogPattern '$logPattern' matches $matchCount files in LogDir right now - too wide to reliably identify the sweep's driver log; narrow it, e.g. 'stepB_*.log' (a single match is fine; zero is fine too, e.g. before the sweep has started)"
        }
    }
    return ,$problems
}

function Test-PathWritable {
    # Review Finding C3 (residual): StatePath (and LocalLogPath) were never
    # validated for writability, only presence-of-a-string. An unwritable
    # StatePath (typo, wrong drive, permissions) makes Write-HeartbeatState fail on
    # EVERY cycle, which - after the C3 fix - makes Invoke-Heartbeat fail CLOSED
    # and skip the progress ping every cycle: from abroad that is indistinguishable
    # from a real stall, and the only available response (a colleague power-
    # cycling the machine) triggers auto-resume, which deletes the in-flight tag's
    # partial output. Probed here by actually writing a sibling file and reading it
    # back, not just presence-of-a-string, and before anything is registered.
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    try {
        $dir = Split-Path -Parent $Path
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force -ErrorAction Stop | Out-Null }
        $probe = "$Path.writetest"
        Set-Content -LiteralPath $probe -Value 'probe' -Encoding Ascii -ErrorAction Stop
        $readBack = (Get-Content -LiteralPath $probe -Raw -ErrorAction Stop).Trim()
        Remove-Item -LiteralPath $probe -Force -ErrorAction Stop
        return ($readBack -eq 'probe')
    } catch {
        return $false
    }
}

function Install-HeartbeatTask {
    param(
        [Parameter(Mandatory=$true)][string]$ToolsDir,
        [string]$TaskName = 'HAGRID-Heartbeat',
        [int]$IntervalMinutes = 5
    )
    $ErrorActionPreference = 'Stop'

    $requiredCmdlets = @('New-ScheduledTaskAction','New-ScheduledTaskTrigger',
                         'New-ScheduledTaskSettingsSet','New-ScheduledTaskPrincipal',
                         'Register-ScheduledTask','Start-ScheduledTask')
    $missingCmdlets = Test-CmdletsAvailable $requiredCmdlets
    if ($missingCmdlets.Count -gt 0) {
        throw "required ScheduledTask cmdlet(s) not available on this machine: $($missingCmdlets -join ', ')"
    }

    if (-not (Test-Path -LiteralPath $ToolsDir)) {
        New-Item -ItemType Directory -Path $ToolsDir -Force | Out-Null
        Write-Host "created $ToolsDir"
    }

    $configPath = Join-Path $ToolsDir 'hc-config.json'
    if (-not (Test-Path -LiteralPath $configPath)) {
        throw "missing $configPath - copy hc-config.template.json there and fill in the UUIDs first"
    }
    $hcCfg = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $problems = Test-HeartbeatConfigForInstall $hcCfg
    if ($problems.Count -gt 0) {
        foreach ($p in $problems) { Write-Host "CONFIG PROBLEM: $p" }
        throw "hc-config.json failed validation ($($problems.Count) problem(s)) - fix and re-run"
    }

    # Review Finding I6: install must not succeed green on a placeholder or dead
    # URL. Ping each real check once now, while a human is present to see the
    # failure, rather than finding out ten days into an absence.
    foreach ($urlKey in @('AliveUrl','ProgressUrl','SweepFinishedUrl')) {
        $url = $hcCfg.$urlKey
        try {
            $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20 -Method Get
            $code = [int]$resp.StatusCode
            if ($code -lt 200 -or $code -ge 300) { throw "HTTP $code" }
            Write-Host "verified $urlKey reachable (HTTP $code)"
        } catch {
            throw "hc-config.json: $urlKey ($url) is not reachable/valid: $($_.Exception.Message)"
        }
    }

    # Review Finding C3 (residual): probe StatePath and LocalLogPath for actual
    # writability (write + read back a sibling file) before registering anything -
    # not just that the config contains a non-empty string.
    foreach ($pathKey in @('StatePath','LocalLogPath')) {
        $p = [string]$hcCfg.$pathKey
        if (-not (Test-PathWritable $p)) {
            throw "hc-config.json: $pathKey ('$p') is not writable (probed by writing and reading back a sibling file) - fix the path or permissions before installing"
        }
        Write-Host "verified $pathKey is writable"
    }

    $target = Join-Path $ToolsDir 'heartbeat.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'heartbeat.ps1') -Destination $target -Force
    Write-Host "deployed $target"

    # Pin the interpreter to an absolute path (review Finding M2): SYSTEM has no
    # user PATH, so an unresolved "powershell.exe" relies on it being found via
    # the machine-wide PATH, which is not guaranteed the same way an explicit
    # absolute path is.
    $psExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $action = New-ScheduledTaskAction -Execute $psExe `
        -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$target`" `"$configPath`""

    $trigger = New-ScheduledTaskTrigger -AtStartup
    $trigger.Repetition = (New-ScheduledTaskTrigger -Once -At (Get-Date) `
        -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes)).Repetition
    # Review Finding C2: an explicit "-RepetitionDuration ([TimeSpan]::MaxValue)"
    # serializes to Duration:P99999999DT23H59M59S, which Register-ScheduledTask
    # rejects outright ("the task XML contains a value which is either incorrectly
    # formatted or out of range"). Evidence, stated precisely: Register-ScheduledTask
    # was ATTEMPTED with each shape side by side. The OLD shape fails this exact XML
    # validation; the FIXED shape passes validation and then fails on privileges
    # (the dev machine had no admin rights). So the XML question is settled, but a
    # SUCCESSFUL registration has never been observed - see RUNBOOK.md section 2 for
    # the checks that close that gap. Leaving Duration empty means "repeat
    # indefinitely" and passes validation.
    $trigger.Repetition.Duration = ''

    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    # Review Finding C1: the cmdlet is New-ScheduledTaskSettingsSet, not
    # New-ScheduledTaskSettings (which does not exist on this PowerShell version -
    # confirmed via Get-Command). The old name failed under
    # $ErrorActionPreference = 'Stop' AFTER Copy-Item had already deployed the
    # script, leaving a half-successful install.
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 10)

    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings -Force | Out-Null
    Write-Host "registered task $TaskName (every $IntervalMinutes min, at startup, as SYSTEM)"

    Start-ScheduledTask -TaskName $TaskName
    Write-Host 'started once now'

    # Review Finding I6: Start-ScheduledTask's success only means the START
    # request was accepted, not that the heartbeat actually ran. Read back the
    # local log to prove it did.
    Start-Sleep -Seconds 5
    $localLogPath = [string]$hcCfg.LocalLogPath
    if ($localLogPath -and (Test-Path -LiteralPath $localLogPath)) {
        $lastLine = Get-Content -LiteralPath $localLogPath -Tail 1
        # Review Finding C3 (residual): the install-time writability PROBE can pass
        # while the actual Task Scheduler service context still cannot write (a
        # different session/permissions than this installer's own session) - so
        # this readback must FAIL, not just warn, when the heartbeat's own first
        # real cycle reports stateSaved=False. Printing that line and calling it
        # success was itself part of the residual defect.
        if ($lastLine -like '*stateSaved=False*') {
            throw "heartbeat local log reports stateSaved=False on its first real run ($lastLine) - StatePath is not actually writable in the Task Scheduler's own context, even though the install-time probe passed. Fix StatePath before leaving this machine unattended: a state-write failure makes the progress check fail closed - i.e. FALSE stall alarms on a healthy machine, whose only available remedy from abroad (a colleague power-cycling it) triggers auto-resume and deletes the in-flight tag's partial output."
        }
        Write-Host "heartbeat local log confirms it ran: $lastLine"
    } else {
        Write-Host "WARNING: could not yet confirm the task actually ran - $localLogPath has no content yet. Check manually (Get-Content the local log, or Get-ScheduledTaskInfo)."
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    if ([string]::IsNullOrWhiteSpace($ToolsDir)) {
        Write-Host 'usage: install_heartbeat_task.ps1 -ToolsDir <path> [-TaskName <name>] [-IntervalMinutes <n>]'
        exit 2
    }
    Install-HeartbeatTask -ToolsDir $ToolsDir -TaskName $TaskName -IntervalMinutes $IntervalMinutes
}
