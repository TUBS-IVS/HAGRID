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

function Test-HeartbeatConfigForInstall {
    # Pure validation (no network, no side effects) so it is directly unit-testable
    # against fixture objects. The actual reachability PING happens separately,
    # inside Install-HeartbeatTask, since a real network call cannot be unit-tested.
    param($Cfg)
    $problems = @()
    foreach ($prop in $Cfg.PSObject.Properties) {
        if ($prop.Value -is [string] -and $prop.Value -like '*REPLACE*') {
            $problems += "$($prop.Name) still contains an unfilled REPLACE placeholder: $($prop.Value)"
        }
    }
    foreach ($urlKey in @('AliveUrl','ProgressUrl','SweepFinishedUrl')) {
        $val = $Cfg.$urlKey
        if ([string]::IsNullOrWhiteSpace($val)) { $problems += "$urlKey is empty" }
    }
    # Review Finding I5: the shipped default LogPattern '*.log' is unsafe - real log
    # directories hold hagrid.log, dozens of rotated hagrid-<date>-N.log(.gz), and
    # *.console.log from unrelated batches, so "newest .log" is not reliably the
    # sweep's driver log. Only let it through if it happens to be unambiguous
    # RIGHT NOW (exactly one match in LogDir); the template ships a REPLACE
    # placeholder instead of '*.log' specifically so a wide pattern cannot ship
    # silently even by inertia.
    $logPattern = [string]$Cfg.LogPattern
    if ($logPattern -eq '*.log') {
        $logDir = [string]$Cfg.LogDir
        $matchCount = 0
        if (Test-Path -LiteralPath $logDir) {
            $matchCount = @(Get-ChildItem -LiteralPath $logDir -Filter '*.log' -File -ErrorAction SilentlyContinue).Count
        }
        if ($matchCount -ne 1) {
            $problems += "LogPattern is the unsafe wide default '*.log' and LogDir currently has $matchCount matching file(s) (need exactly 1 to be unambiguous) - narrow LogPattern, e.g. 'stepB_*.log'"
        }
    }
    return ,$problems
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
    # formatted or out of range") - confirmed by actually registering a throwaway
    # task with each shape side by side; the OLD shape fails this exact XML
    # validation and the FIXED shape passes it. Leaving Duration empty means
    # "repeat indefinitely" and passes validation.
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
