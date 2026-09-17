# Outbound heartbeat for unattended sweep runs.
# Spec: docs/superpowers/specs/2026-07-31-remote-crash-alerting-design.md
#
# Pings an "alive" check unconditionally, a "progress" check when the newest batch
# log has advanced, and uses an "event" check as a general push channel.
# Dot-sourceable: defining functions must have no side effects.
# ASCII output only - the console codepage is cp1252.

# Final-only sentinels. A per-scenario or per-step exit marker such as
# 'SCEN1_EXIT=0' or 'STEP1A_EXIT=0' appears mid-batch (e.g. after run 1 of 10)
# and must NOT count as batch completion - only a final sentinel does. Do not
# add '_EXIT=' (or any '*_EXIT=*' pattern) back to this list: the real
# stepB_weekend_batch.log writes 'SCEN1_EXIT=0' about 7 hours into a ~70 hour
# batch, which would make Test-BatchComplete return true almost immediately
# and silently disable stall detection for the remaining ~90% of the run.
$script:CompletionMarkers = @('batch done', 'RESUME_DONE', 'NIGHTBC_DONE')

function Get-NewestLogState {
    param([Parameter(Mandatory=$true)][string]$LogDir, [string]$Pattern = '*.log')
    if (-not (Test-Path -LiteralPath $LogDir)) { return $null }
    if ([string]::IsNullOrWhiteSpace($Pattern)) { $Pattern = '*.log' }
    $newest = Get-ChildItem -LiteralPath $LogDir -Filter $Pattern -File -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $newest) { return $null }
    return @{
        Path           = $newest.FullName
        LastWriteTicks = [long]$newest.LastWriteTimeUtc.Ticks
        Length         = [long]$newest.Length
    }
}

function Test-ProgressAdvanced {
    param($Previous, $Current)
    # No current observation means the log vanished - that is not progress.
    if ($null -eq $Current)  { return $false }
    # First ever observation counts as progress so the check starts green.
    if ($null -eq $Previous) { return $true }
    # A new batch writes a new file; the previous one stops growing.
    if ($Previous.Path -ne $Current.Path) { return $true }
    if ([long]$Current.LastWriteTicks -gt [long]$Previous.LastWriteTicks) { return $true }
    if ([long]$Current.Length -gt [long]$Previous.Length) { return $true }
    return $false
}

function Test-BatchComplete {
    param([string[]]$TailLines)
    if ($null -eq $TailLines -or $TailLines.Count -eq 0) { return $false }
    foreach ($line in $TailLines) {
        foreach ($marker in $script:CompletionMarkers) {
            if ($line -like "*$marker*") { return $true }
        }
    }
    return $false
}

function Read-HeartbeatState {
    param([Parameter(Mandatory=$true)][string]$Path)
    $empty = @{ LastLogPath=$null; LastWriteTicks=0L; LastLength=0L;
                CompletionAnnounced=$false; EventPendingRearm=$false }
    if (-not (Test-Path -LiteralPath $Path)) { return $empty }
    try {
        $parsed = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop | ConvertFrom-Json
    } catch {
        return $empty   # corrupt state must not stop the heartbeat
    }
    return @{
        LastLogPath         = $parsed.LastLogPath
        LastWriteTicks      = [long]$parsed.LastWriteTicks
        LastLength          = [long]$parsed.LastLength
        CompletionAnnounced = [bool]$parsed.CompletionAnnounced
        EventPendingRearm   = [bool]$parsed.EventPendingRearm
    }
}

function Write-HeartbeatState {
    param([Parameter(Mandatory=$true)][string]$Path, [Parameter(Mandatory=$true)]$State)
    # Returns $true/$false rather than throwing: the caller must fail CLOSED on a
    # write failure (skip the progress ping), not crash the whole heartbeat cycle
    # (the alive ping must still go out). Realistic trigger: C: fills during a
    # multi-day sweep - which also kills the run - so this failure and a real
    # crash tend to arrive together, which is exactly when a silently-swallowed
    # write failure would be most dangerous.
    try {
        $dir = Split-Path -Parent $Path
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force -ErrorAction Stop | Out-Null }
        ($State | ConvertTo-Json -Compress) | Set-Content -LiteralPath $Path -Encoding Ascii -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

function Read-HeartbeatConfig {
    param([Parameter(Mandatory=$true)][string]$Path)
    # Unlike Read-HeartbeatState, this THROWS on a missing/unreadable config
    # rather than tolerating it. That is deliberate, not an oversight: a
    # heartbeat that cannot read its config cannot ping anything, so the
    # "alive" check simply goes quiet and alarms - a loud, correct failure.
    # Do not "fix" this into silent tolerance (e.g. returning defaults); that
    # would turn a loud failure into a silent one, which is the exact class
    # of bug this whole feature exists to prevent.
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "heartbeat config not found: $Path"
    }
    $parsed = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop | ConvertFrom-Json
    return @{
        AliveUrl     = [string]$parsed.AliveUrl
        ProgressUrl  = [string]$parsed.ProgressUrl
        SweepFinishedUrl = [string]$parsed.SweepFinishedUrl
        LogDir       = [string]$parsed.LogDir
        # Scope the watch to one batch's logs. See the note above this task: an
        # unscoped watch can latch completion on a FOREIGN batch's final sentinel.
        LogPattern   = if ([string]::IsNullOrWhiteSpace($parsed.LogPattern)) { '*.log' } else { [string]$parsed.LogPattern }
        StatePath    = [string]$parsed.StatePath
        LocalLogPath = [string]$parsed.LocalLogPath
    }
}

function Send-HealthcheckPing {
    param([string]$BaseUrl, [string]$Suffix = '', [string]$Body = '')
    if ([string]::IsNullOrWhiteSpace($BaseUrl)) { return $false }
    $url = $BaseUrl.TrimEnd('/') + $Suffix
    try {
        if ([string]::IsNullOrEmpty($Body)) {
            Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20 -Method Get | Out-Null
        } else {
            Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20 -Method Post -Body $Body | Out-Null
        }
        return $true
    } catch {
        return $false   # never throw: a blip must not kill the heartbeat
    }
}

function Write-LocalLog {
    param([string]$Path, [string]$Message)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    try { Add-Content -LiteralPath $Path -Value "$stamp $Message" -Encoding Ascii } catch { }
}

function Invoke-Heartbeat {
    param([Parameter(Mandatory=$true)][string]$ConfigPath)
    $cfg = Read-HeartbeatConfig $ConfigPath          # only fatal condition
    $state = Read-HeartbeatState $cfg.StatePath

    # 1. Unconditional liveness. Also self-monitoring: if this script or its task
    #    dies while the machine lives, the alive check stops and alarms.
    $aliveOk = Send-HealthcheckPing $cfg.AliveUrl '' ''

    # 2. Progress.
    $current = Get-NewestLogState $cfg.LogDir $cfg.LogPattern
    $previous = $null
    if ($state.LastLogPath) {
        $previous = @{ Path = $state.LastLogPath
                       LastWriteTicks = $state.LastWriteTicks
                       Length = $state.LastLength }
    }
    $advanced = Test-ProgressAdvanced $previous $current

    # A new batch (new log file matching the pattern) must not inherit the
    # previous batch's completion state: otherwise batch B's own final
    # sentinel would never be announced because CompletionAnnounced already
    # latched true from batch A and never gets reset. Part B's auto-resume
    # starting a fresh batch after a crash depends on this reset.
    if ($current -and $state.LastLogPath -and $current.Path -ne $state.LastLogPath) {
        $state.CompletionAnnounced = $false
        $state.EventPendingRearm   = $false
    }

    # 3. Completion. A finished sweep must not decay into a false stall alarm, so
    #    from the completion marker onward progress is pinged unconditionally.
    $tail = @()
    if ($current) {
        try { $tail = Get-Content -LiteralPath $current.Path -Tail 40 -ErrorAction Stop } catch { $tail = @() }
    }
    $complete = Test-BatchComplete $tail

    if ($current) {
        $state.LastLogPath    = $current.Path
        $state.LastWriteTicks = $current.LastWriteTicks
        $state.LastLength     = $current.Length
    }

    # The meaning lives in the CHECK NAME (sweep-finished), not in the body: the docs
    # do not state that an attached body reaches the notification. The body is sent
    # anyway because it is useful in the check's Events list.
    #
    # Both branches below only flip their state flag when the ping actually
    # succeeded. A dropped announcement or a dropped re-arm must retry on the
    # next cycle rather than being marked done-when-it-wasn't: a single
    # network blip on exactly the one cycle that fires the one-shot announce
    # must not permanently lose that notification.
    if ($complete -and -not $state.CompletionAnnounced) {
        $name = if ($current) { Split-Path $current.Path -Leaf } else { 'unknown' }
        $sent = Send-HealthcheckPing $cfg.SweepFinishedUrl '/fail' "$env:COMPUTERNAME sweep batch finished ($name)"
        if ($sent) {
            $state.CompletionAnnounced = $true
            $state.EventPendingRearm   = $true
            Write-LocalLog $cfg.LocalLogPath 'event: batch completion announced'
        } else {
            Write-LocalLog $cfg.LocalLogPath 'event: batch completion announce FAILED, will retry next cycle'
        }
    } elseif ($state.EventPendingRearm) {
        # Re-arm on the NEXT cycle, not immediately, so the down-notification is
        # never racing an up-notification.
        $rearmed = Send-HealthcheckPing $cfg.SweepFinishedUrl '' ''
        if ($rearmed) {
            $state.EventPendingRearm = $false
            Write-LocalLog $cfg.LocalLogPath 'sweep-finished check re-armed'
        } else {
            Write-LocalLog $cfg.LocalLogPath 'sweep-finished re-arm FAILED, will retry next cycle'
        }
    }

    # Persist LAST (after the completion-announce/re-arm mutations above), and gate
    # the progress ping on the persistence actually succeeding. If
    # Write-HeartbeatState silently failed, the NEXT cycle's Read-HeartbeatState
    # would return empty defaults, $previous would be $null, and "first ever
    # observation counts as progress" would make Test-ProgressAdvanced return
    # $true forever - pinging progress every cycle regardless of whether the
    # simulation is still running. Realistic trigger: C: fills during a multi-day
    # sweep, which typically kills the run too, so this failure and a real crash
    # tend to arrive together - exactly when a silently swallowed write failure
    # would be most dangerous. Fail CLOSED instead: no persisted state this cycle
    # means no progress claim this cycle, logged loudly since the local log is the
    # only trace left after ten unattended days.
    $stateSaved = Write-HeartbeatState $cfg.StatePath $state
    if ($stateSaved) {
        if ($advanced -or $complete) {
            Send-HealthcheckPing $cfg.ProgressUrl '' '' | Out-Null
        }
    } else {
        Write-LocalLog $cfg.LocalLogPath 'ERROR: could not persist heartbeat state - skipping progress ping this cycle (fail closed)'
    }

    Write-LocalLog $cfg.LocalLogPath ("alive=$aliveOk advanced=$advanced complete=$complete stateSaved=$stateSaved")
    return 0
}

# Entry point when invoked as a script (not dot-sourced by the tests).
if ($MyInvocation.InvocationName -ne '.') {
    if ($args.Count -ge 1) {
        exit (Invoke-Heartbeat $args[0])
    } else {
        # Without this, a misconfigured Task Scheduler action (missing the
        # config-path argument) exits with an unspecified code and looks like
        # a successful no-op run instead of a visible misconfiguration.
        Write-Host 'usage: heartbeat.ps1 <path-to-hc-config.json>'
        exit 2
    }
}
