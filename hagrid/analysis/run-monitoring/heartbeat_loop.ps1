# Runs heartbeat.ps1 on a fixed interval from a single long-lived process.
#
# WHY THIS EXISTS: the designed deployment is a SYSTEM Scheduled Task with an
# At-startup trigger plus repetition. That needs admin rights, and this account
# does not have them - Register-ScheduledTask returns 0x80070005 "Zugriff
# verweigert" even for a task registered under the current user only (verified
# 2026-08-01). Launched detached via start_detached.ps1, this loop delivers the
# same alerting signal without any privilege.
#
# WHAT IT GIVES UP vs the Scheduled Task:
#   - It does NOT survive a reboot. That is acceptable, and arguably correct: if
#     the machine reboots, this process dies, the alive check goes silent, and
#     you get exactly the alarm you wanted. What you lose is automatic recovery,
#     not detection.
#   - Task Scheduler would restart a crashed action; nothing restarts this loop.
#     Mitigated by catching per-cycle errors so only a catastrophic failure of
#     the process itself can end it - and that, again, raises the alarm.
param(
    [Parameter(Mandatory)][string]$ConfigPath,
    [Parameter(Mandatory)][string]$HeartbeatScript,
    [Parameter(Mandatory)][string]$LoopLog,
    [Parameter(Mandatory)][string]$LockPath,
    [int]$IntervalSeconds = 300
)

function Write-LoopLog([string]$Message) {
    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  ' + $Message
    try {
        $dir = Split-Path -Parent $LoopLog
        if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Add-Content -LiteralPath $LoopLog -Value $line -Encoding Ascii
    } catch { }
}

# Single-instance guard. Two loops would double the ping rate, which is harmless
# for the alive check but would mask a stall: the second process's progress ping
# could keep the check green while the first observes no advance.
if (Test-Path -LiteralPath $LockPath) {
    $holder = (Get-Content -LiteralPath $LockPath -Raw -ErrorAction SilentlyContinue).Trim()
    $alive = $false
    if ($holder -match '^\d+$') { $alive = $null -ne (Get-Process -Id ([int]$holder) -ErrorAction SilentlyContinue) }
    if ($alive) {
        Write-LoopLog "another heartbeat loop is already running (PID $holder) - exiting"
        exit 3
    }
    Write-LoopLog "stale lock from PID $holder (process gone) - taking over"
}
Set-Content -LiteralPath $LockPath -Value $PID -Encoding Ascii

if (-not (Test-Path -LiteralPath $HeartbeatScript)) {
    Write-LoopLog "FATAL: heartbeat script not found at $HeartbeatScript"
    exit 1
}
if (-not (Test-Path -LiteralPath $ConfigPath)) {
    Write-LoopLog "FATAL: config not found at $ConfigPath"
    exit 1
}

Write-LoopLog "heartbeat loop started (PID $PID), interval ${IntervalSeconds}s, config '$ConfigPath'"

$cycle = 0
while ($true) {
    $cycle++
    try {
        # Invoked as a child process rather than dot-sourced: one bad cycle then
        # cannot corrupt this process's state, and heartbeat.ps1 keeps its own
        # "exit code is the result" contract exactly as the Scheduled Task uses it.
        $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        & $ps -NoProfile -ExecutionPolicy Bypass -File $HeartbeatScript $ConfigPath | Out-Null
        $rc = $LASTEXITCODE
        if ($rc -ne 0) { Write-LoopLog "cycle $cycle : heartbeat.ps1 exited $rc" }
    } catch {
        # Never let one cycle end the loop - the whole point is to keep pinging.
        Write-LoopLog "cycle $cycle : ERROR $($_.Exception.Message)"
    }
    Start-Sleep -Seconds $IntervalSeconds
}
