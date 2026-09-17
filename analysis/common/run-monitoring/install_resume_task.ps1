# Installs the boot-triggered resume as a SYSTEM Scheduled Task.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File install_resume_task.ps1 -ToolsDir C:\Users\<user>\hagrid-tools
#
# Dot-sourceable for testing (see Test-Installers.ps1): the top-level param() is
# intentionally NOT Mandatory, same reasoning as install_heartbeat_task.ps1.
param(
    [string]$ToolsDir,
    [string]$TaskName = 'HAGRID-Resume',
    [int]$DelayMinutes = 2
)

function Test-CmdletsAvailable {
    # See install_heartbeat_task.ps1 for the full rationale (review Finding I7) -
    # duplicated here rather than dot-sourced, so the two installers stay
    # independently deployable, matching this codebase's existing convention
    # (e.g. Write-LocalLog is duplicated between heartbeat.ps1 and resume_sweep.ps1
    # for the same reason).
    param([string[]]$Names)
    $missing = @()
    foreach ($n in $Names) {
        if (-not (Get-Command $n -ErrorAction SilentlyContinue)) { $missing += $n }
    }
    return ,$missing
}

function Test-ContainsReplacePlaceholder {
    # Duplicated from resume_sweep.ps1 (review Finding I2 residual): the REPLACE
    # scan must recurse into array values too, e.g. Tags: ["REPLACE-WITH-REAL-TAG-1",
    # ...] - a top-level-strings-only scan let a config with placeholder tags
    # install green, and at the next boot it would burn a full ~6h Step A re-run
    # plus a Step B launch that fails every tag.
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

function Get-LocalFixedDriveLetters {
    # The impure half of the session-scoped-path check, split out so
    # Test-SystemVisiblePaths below stays a pure, directly unit-testable function.
    # Win32_LogicalDisk DriveType 3 is "Local Disk"; 4 is "Network Drive", i.e. a
    # letter mapped inside ONE interactive logon session. Only local disks exist for
    # a task running as SYSTEM.
    $letters = @()
    foreach ($d in (Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' -ErrorAction SilentlyContinue)) {
        $letters += $d.DeviceID.TrimEnd(':').ToUpperInvariant()
    }
    return ,$letters
}

function Test-SystemVisiblePaths {
    # Found 2026-07-31 by probing the sim-PC over SSH, which has no interactive
    # session either and so exposed the same blind spot: the dev-PC maps T:, S: and
    # X: to \\ad.tu-bs.de\share\ivs\*, and those letters do not exist for SYSTEM.
    # Test-Path on such a path returns $false with NO error, so a config pointing at
    # T: installs perfectly green and then fails at the next boot in silence - the
    # precise failure direction this feature exists to eliminate. Refuse at install
    # time, while a human is present to fix it.
    param($Config, [string[]]$Keys, [string[]]$LocalDriveLetters)
    $problems = @()
    foreach ($key in $Keys) {
        $raw = [string]$Config.$key
        if ([string]::IsNullOrWhiteSpace($raw)) { continue }   # emptiness is the REPLACE/required-key checks' job
        if ($raw.StartsWith('\\')) {
            $problems += "$key ('$raw') is a UNC path - a SYSTEM task authenticates to a share as the MACHINE account, which has no rights on a user share. Copy what is needed onto a local disk."
            continue
        }
        if ($raw -match '^([A-Za-z]):') {
            $letter = $Matches[1].ToUpperInvariant()
            if ($LocalDriveLetters -notcontains $letter) {
                $problems += "$key ('$raw') is on drive ${letter}:, which is not a local disk on this machine. A mapped network drive belongs to one interactive logon session and is INVISIBLE to a SYSTEM Scheduled Task - this would install green and then fail silently at boot. Use a local path (or the UNC form only if the machine account really has access)."
            }
        }
    }
    return ,$problems
}

function Test-ResumeLogInvariant {
    # Review Finding C7 (the subtlest finding in the review): a resumed run writes
    # its log4j output under its own run-output directory, NOT into the
    # heartbeat's LogDir - its only continuous artefact in a predictable place is
    # the console redirect ResumeLog, configured in a DIFFERENT file (resume-config
    # vs hc-config) with no cross-check. If the two disagree, progress either goes
    # permanently quiet on a healthy machine (a false alarm nobody can act on) or
    # latches on a stale finished log. This makes the machine enforce the
    # invariant the human configured, rather than inventing a name for either
    # value. Pure function (no filesystem access beyond the two objects' own
    # properties) so it is directly unit-testable.
    param($ResumeCfg, $HcCfg)
    $problems = @()
    $logDir = [string]$HcCfg.LogDir
    $logPattern = [string]$HcCfg.LogPattern
    if ([string]::IsNullOrWhiteSpace($logPattern)) { $logPattern = '*.log' }

    $resumeLogRaw = [string]$ResumeCfg.ResumeLog
    $workDir = [string]$ResumeCfg.WorkDir
    if ([string]::IsNullOrWhiteSpace($resumeLogRaw)) {
        $problems += 'ResumeLog is empty'
        return ,$problems
    }
    $resumeLogFull = if ([System.IO.Path]::IsPathRooted($resumeLogRaw)) { $resumeLogRaw } else { Join-Path $workDir $resumeLogRaw }
    $resumeLogDir  = Split-Path -Parent $resumeLogFull
    $resumeLogName = Split-Path -Leaf $resumeLogFull

    # Get-NewestLogState (heartbeat.ps1) does not recurse, so "inside LogDir" means
    # the SAME directory, not merely a descendant of it.
    $normLogDir       = [System.IO.Path]::GetFullPath($logDir).TrimEnd('\')
    $normResumeLogDir = [System.IO.Path]::GetFullPath($resumeLogDir).TrimEnd('\')

    if ($normLogDir -ne $normResumeLogDir) {
        $problems += "ResumeLog ('$resumeLogFull') does not resolve inside LogDir ('$logDir') - after an auto-resume, progress would go permanently quiet (the heartbeat never scans this directory) or latch on a stale finished log"
    }
    if ($resumeLogName -notlike $logPattern) {
        $problems += "ResumeLog's filename ('$resumeLogName') does not match LogPattern ('$logPattern') - the heartbeat would never pick this file up as the newest log"
    }
    return ,$problems
}

function Install-ResumeTask {
    param(
        [Parameter(Mandatory=$true)][string]$ToolsDir,
        [string]$TaskName = 'HAGRID-Resume',
        [int]$DelayMinutes = 2
    )
    $ErrorActionPreference = 'Stop'

    $requiredCmdlets = @('New-ScheduledTaskAction','New-ScheduledTaskTrigger',
                         'New-ScheduledTaskSettingsSet','New-ScheduledTaskPrincipal','Register-ScheduledTask')
    $missingCmdlets = Test-CmdletsAvailable $requiredCmdlets
    if ($missingCmdlets.Count -gt 0) {
        throw "required ScheduledTask cmdlet(s) not available on this machine: $($missingCmdlets -join ', ')"
    }

    # Review Finding M1: create ToolsDir like install_heartbeat_task.ps1 already does.
    if (-not (Test-Path -LiteralPath $ToolsDir)) {
        New-Item -ItemType Directory -Path $ToolsDir -Force | Out-Null
        Write-Host "created $ToolsDir"
    }

    $configPath = Join-Path $ToolsDir 'resume-config.json'
    if (-not (Test-Path -LiteralPath $configPath)) {
        throw "missing $configPath - copy resume-config.template.json there and fill in the real values (see README for the required keys)"
    }
    $resumeCfg = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json

    foreach ($prop in $resumeCfg.PSObject.Properties) {
        if (Test-ContainsReplacePlaceholder $prop.Value) {
            throw "resume-config.json: $($prop.Name) still contains an unfilled REPLACE placeholder"
        }
    }
    if (@($resumeCfg.Tags).Count -eq 0) {
        throw 'resume-config.json: Tags is empty - nothing to resume'
    }

    # Every key below is a path the SYSTEM-run resume must resolve at boot.
    $pathKeys = @('LockPath','OutputRoot','CarrierRoot','WorkDir','JavaExe','Jar',
                  'GeneratedBatPath','ResumeLog','StartDetached','StepALauncher','LocalLogPath')
    $localDrives = Get-LocalFixedDriveLetters
    $sessionScoped = Test-SystemVisiblePaths $resumeCfg $pathKeys $localDrives
    if ($sessionScoped.Count -gt 0) {
        foreach ($p in $sessionScoped) { Write-Host "CONFIG PROBLEM: $p" }
        throw "resume-config.json points at $($sessionScoped.Count) path(s) a SYSTEM task cannot see (local disks here: $($localDrives -join ', '))"
    }
    Write-Host "verified: every configured path is on a local disk ($($localDrives -join ', '))"

    # Review Finding C7: read BOTH configs and refuse to install if the invariant
    # is violated, naming both values.
    $hcConfigPath = Join-Path $ToolsDir 'hc-config.json'
    if (-not (Test-Path -LiteralPath $hcConfigPath)) {
        throw "cannot verify the ResumeLog/LogDir invariant: $hcConfigPath not found - install and configure the heartbeat first"
    }
    $hcCfg = Get-Content -LiteralPath $hcConfigPath -Raw | ConvertFrom-Json
    $invariantProblems = Test-ResumeLogInvariant $resumeCfg $hcCfg
    if ($invariantProblems.Count -gt 0) {
        foreach ($p in $invariantProblems) { Write-Host "CONFIG PROBLEM: $p" }
        throw "resume-config.json / hc-config.json failed the ResumeLog/LogDir invariant check ($($invariantProblems.Count) problem(s)) - fix ResumeLog or LogDir/LogPattern so they agree"
    }
    Write-Host 'verified: ResumeLog resolves inside LogDir and matches LogPattern'

    $target = Join-Path $ToolsDir 'resume_sweep.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'resume_sweep.ps1') -Destination $target -Force
    Write-Host "deployed $target"

    $psExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'   # review Finding M2
    $action = New-ScheduledTaskAction -Execute $psExe `
        -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$target`" `"$configPath`""
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $trigger.Delay = "PT${DelayMinutes}M"
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    # Review Finding C1: New-ScheduledTaskSettingsSet, not New-ScheduledTaskSettings
    # (which does not exist - see install_heartbeat_task.ps1 for the full story).
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero)

    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings -Force | Out-Null
    Write-Host "registered task $TaskName (at startup +${DelayMinutes}min, as SYSTEM)"
    # Review Finding 4 (documentation): the INSTALLER has no -DryRun switch - that
    # belongs to resume_sweep.ps1 itself. Name the actual command, with its real
    # config argument, so an operator can copy-paste it rather than guess.
    Write-Host "NOT started now - it fires on the next boot. Verify first: powershell -NoProfile -ExecutionPolicy Bypass -File `"$target`" `"$configPath`" -DryRun"
}

if ($MyInvocation.InvocationName -ne '.') {
    if ([string]::IsNullOrWhiteSpace($ToolsDir)) {
        Write-Host 'usage: install_resume_task.ps1 -ToolsDir <path> [-TaskName <name>] [-DelayMinutes <n>]'
        exit 2
    }
    Install-ResumeTask -ToolsDir $ToolsDir -TaskName $TaskName -DelayMinutes $DelayMinutes
}
