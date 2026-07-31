# Dependency-free test harness. Only Pester 3.4.0 is available on these machines
# and modern Pester cannot be assumed, so assertions are hand-rolled.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File Test-Heartbeat.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'heartbeat.ps1')

$script:Failures = 0
function Assert-Equal($Expected, $Actual, $Name) {
    if ($Expected -eq $Actual) { Write-Host "  PASS $Name" }
    else { Write-Host "  FAIL $Name : expected [$Expected] got [$Actual]"; $script:Failures++ }
}

Write-Host 'Test-ProgressAdvanced'
$base = @{ Path = 'a.log'; LastWriteTicks = 1000L; Length = 50L }
Assert-Equal $false (Test-ProgressAdvanced $base $base) 'identical state is not progress'
Assert-Equal $true  (Test-ProgressAdvanced $base @{ Path='a.log'; LastWriteTicks=2000L; Length=50L }) 'newer mtime is progress'
Assert-Equal $true  (Test-ProgressAdvanced $base @{ Path='a.log'; LastWriteTicks=1000L; Length=90L }) 'grown length is progress'
# A new batch writes a NEW log file; the old one stops growing. Treat a path change as progress.
Assert-Equal $true  (Test-ProgressAdvanced $base @{ Path='b.log'; LastWriteTicks=1000L; Length=10L }) 'different log file is progress'
Assert-Equal $true  (Test-ProgressAdvanced $null $base) 'first ever observation is progress'
Assert-Equal $false (Test-ProgressAdvanced $base $null) 'losing the log is not progress'

Write-Host 'Test-BatchComplete'
Assert-Equal $true  (Test-BatchComplete @('=== weekend batch done 27.07.2026 ===')) 'batch done marker'
# Per-scenario/per-step exit markers appear mid-batch and must NOT count as completion.
Assert-Equal $false (Test-BatchComplete @('SCEN1_EXIT=0')) 'scenario 1 exit marker is not batch completion'
Assert-Equal $false (Test-BatchComplete @('SCEN10_EXIT=0')) 'scenario 10 exit marker is still not batch completion'
Assert-Equal $false (Test-BatchComplete @('STEP1A_EXIT=0')) 'intermediate step exit marker is not batch completion'
Assert-Equal $true  (Test-BatchComplete @('=== STEP B weekend batch done 27.07.2026 ===')) 'step B batch done marker'
Assert-Equal $true  (Test-BatchComplete @('===== RESUME_DONE 31.07.2026 =====')) 'resume done sentinel'
Assert-Equal $true  (Test-BatchComplete @('===== NIGHTBC_DONE 31.07.2026 =====')) 'nightbc done sentinel'
Assert-Equal $false (Test-BatchComplete @('2026-07-24 21:36:50 INFO  QSim:552 - SIMULATION AT 13:00:00')) 'ordinary log line'
Assert-Equal $false (Test-BatchComplete @()) 'empty tail'

Write-Host 'Get-NewestLogState'
$tmp = Join-Path $env:TEMP ("hbtest_" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
Assert-Equal $null (Get-NewestLogState $tmp) 'empty dir yields null'
Set-Content -Path (Join-Path $tmp 'old.log') -Value 'x' -Encoding Ascii
Start-Sleep -Milliseconds 1100
Set-Content -Path (Join-Path $tmp 'new.log') -Value 'yy' -Encoding Ascii
$state = Get-NewestLogState $tmp
Assert-Equal 'new.log' (Split-Path $state.Path -Leaf) 'picks the newest log'
Set-Content -Path (Join-Path $tmp 'ignored.txt') -Value 'zzz' -Encoding Ascii
Assert-Equal 'new.log' (Split-Path (Get-NewestLogState $tmp).Path -Leaf) 'ignores non-log files'

Write-Host 'State round-trip'
$statePath = Join-Path $tmp 'state.json'
$empty = Read-HeartbeatState $statePath
Assert-Equal $null $empty.LastLogPath 'absent state file yields empty defaults'
Write-HeartbeatState $statePath @{ LastLogPath='a.log'; LastWriteTicks=1234L; LastLength=7L; CompletionAnnounced=$true; EventPendingRearm=$false } | Out-Null
$loaded = Read-HeartbeatState $statePath
Assert-Equal 'a.log' $loaded.LastLogPath 'path round-trips'
Assert-Equal 1234 $loaded.LastWriteTicks 'ticks round-trip'
Assert-Equal $true $loaded.CompletionAnnounced 'bool round-trips'

Write-Host 'Read-HeartbeatConfig'
$cfgPath = Join-Path $tmp 'cfg.json'
@'
{
  "AliveUrl":         "https://hc-ping.com/aaaa-1111",
  "ProgressUrl":      "https://hc-ping.com/bbbb-2222",
  "SweepFinishedUrl": "https://hc-ping.com/cccc-3333",
  "LogDir":      "C:\\logs",
  "StatePath":   "C:\\tools\\hb-state.json",
  "LocalLogPath":"C:\\tools\\heartbeat.log"
}
'@ | Set-Content -LiteralPath $cfgPath -Encoding Ascii
$cfg = Read-HeartbeatConfig $cfgPath
Assert-Equal 'https://hc-ping.com/aaaa-1111' $cfg.AliveUrl 'alive url parsed'
Assert-Equal 'C:\logs' $cfg.LogDir 'log dir parsed'
Assert-Equal '*.log' $cfg.LogPattern 'absent LogPattern defaults to *.log'

Write-Host 'Get-NewestLogState honours a scoping pattern'
# Guards the CLASS of bug the Task 3 review found: an unscoped watch can latch
# completion on a FOREIGN batch's final sentinel and blind stall detection for
# the rest of the sweep, with every check still showing green.
$scoped = Join-Path $tmp 'scoped'
New-Item -ItemType Directory -Path $scoped | Out-Null
Set-Content -Path (Join-Path $scoped 'stepB_mine.log') -Value 'mine' -Encoding Ascii
Start-Sleep -Milliseconds 1100
Set-Content -Path (Join-Path $scoped 'nightbc.console.log') -Value 'foreign' -Encoding Ascii
Assert-Equal 'nightbc.console.log' (Split-Path (Get-NewestLogState $scoped).Path -Leaf) 'unscoped picks the newest, foreign log'
Assert-Equal 'stepB_mine.log' (Split-Path (Get-NewestLogState $scoped 'stepB_*.log').Path -Leaf) 'pattern excludes the foreign log'
Assert-Equal $null (Get-NewestLogState $scoped 'nomatch_*.log') 'pattern matching nothing yields null'

$threw = $false
try { Read-HeartbeatConfig (Join-Path $tmp 'missing.json') } catch { $threw = $true }
Assert-Equal $true $threw 'missing config throws (only fatal condition)'

Write-Host 'Send-HealthcheckPing is failure-tolerant'
# Unroutable host: must return $false and must NOT throw - a network blip may not
# kill the heartbeat, that is what the grace window is for.
Assert-Equal $false (Send-HealthcheckPing 'https://127.0.0.1:9' '' '') 'unreachable endpoint returns false, no throw'
Assert-Equal $false (Send-HealthcheckPing '' '' '') 'empty url returns false'

Write-Host 'Invoke-Heartbeat integration'
# Drives the main loop across several synthetic cycles. The three URLs point at
# an unroutable address so Send-HealthcheckPing always returns $false and no
# real network call happens - assertions are on the STATE FILE and LOCAL LOG,
# which is what the main loop actually writes, not on ping success. This is the
# level Task 4's review found two Critical wiring bugs at (state-key mismatch
# and a completion flag that never resets), which no leaf-function unit test
# could see.
$hbRoot     = Join-Path $tmp 'hb'
New-Item -ItemType Directory -Path $hbRoot | Out-Null
$hbLogDir   = Join-Path $hbRoot 'logs'
New-Item -ItemType Directory -Path $hbLogDir | Out-Null
$hbStatePath = Join-Path $hbRoot 'state.json'
$hbLocalLog  = Join-Path $hbRoot 'heartbeat.log'
$hbCfgPath   = Join-Path $hbRoot 'cfg.json'
$hbCfgObj = [ordered]@{
    AliveUrl         = 'https://127.0.0.1:9/alive'
    ProgressUrl      = 'https://127.0.0.1:9/progress'
    SweepFinishedUrl = 'https://127.0.0.1:9/finished'
    LogDir           = $hbLogDir
    LogPattern       = '*.log'
    StatePath        = $hbStatePath
    LocalLogPath     = $hbLocalLog
}
($hbCfgObj | ConvertTo-Json) | Set-Content -LiteralPath $hbCfgPath -Encoding Ascii

# Cycle 1: fresh log. State should record its path/ticks/length.
$run1 = Join-Path $hbLogDir 'stepB_run1.log'
Set-Content -Path $run1 -Value 'start' -Encoding Ascii
# Resolve to the actual filesystem FullName (not the raw Join-Path string): on
# this machine $env:TEMP itself expands to an 8.3 short name, while .NET's
# FullName (what Get-NewestLogState records) returns the long form. Comparing
# against the resolved name avoids a spurious mismatch unrelated to any finding.
$run1Full = (Get-Item -LiteralPath $run1).FullName
Invoke-Heartbeat $hbCfgPath | Out-Null
$st1 = Get-Content -LiteralPath $hbStatePath -Raw | ConvertFrom-Json
Assert-Equal $run1Full $st1.LastLogPath 'cycle1: state records the fresh log path'
$len1 = [long](Get-Item $run1).Length
Assert-Equal $len1 ([long]$st1.LastLength) 'cycle1: state records the log length'

# Cycle 2: log UNCHANGED. This is the assertion that catches Finding 1: with the
# $previous.Length / $state.LastLength key mismatch, [long]$Previous.Length was
# always 0 and the length check fired unconditionally, so "advanced" never went
# quiet even though nothing changed.
Start-Sleep -Milliseconds 200
Invoke-Heartbeat $hbCfgPath | Out-Null
$line2 = Get-Content -LiteralPath $hbLocalLog -Tail 1
Assert-Equal $true ($line2 -like '*advanced=False*') 'cycle2: unchanged log yields advanced=False (guards the Length/LastLength key mismatch)'

# Cycle 3: log grows. Progress must go green again.
Add-Content -Path $run1 -Value 'more' -Encoding Ascii
Start-Sleep -Milliseconds 200
Invoke-Heartbeat $hbCfgPath | Out-Null
$line3 = Get-Content -LiteralPath $hbLocalLog -Tail 1
Assert-Equal $true ($line3 -like '*advanced=True*') 'cycle3: appended log yields advanced=True'

# Simulate a prior REAL batch completion (network worked back then): hand-set the
# flags the way a successful cycle would have left them, still pointed at run1.
$stateForReset = Read-HeartbeatState $hbStatePath
$stateForReset.CompletionAnnounced = $true
$stateForReset.EventPendingRearm   = $true
Write-HeartbeatState $hbStatePath $stateForReset | Out-Null

# Batch change: a second, NEWER log file appears (matches the pattern). This is
# the assertion that catches Finding 2: CompletionAnnounced must reset to false
# for the new batch, or its own completion would never be announced.
Start-Sleep -Milliseconds 1100
$run2 = Join-Path $hbLogDir 'stepB_run2.log'
Set-Content -Path $run2 -Value 'batch b start' -Encoding Ascii
$run2Full = (Get-Item -LiteralPath $run2).FullName
Invoke-Heartbeat $hbCfgPath | Out-Null
$st4 = Get-Content -LiteralPath $hbStatePath -Raw | ConvertFrom-Json
Assert-Equal $run2Full $st4.LastLogPath 'batch change: state now tracks the new (newest) log'
Assert-Equal $false ([bool]$st4.CompletionAnnounced) 'batch change: CompletionAnnounced resets to false on new log path (guards Finding 2)'
Assert-Equal $false ([bool]$st4.EventPendingRearm) 'batch change: EventPendingRearm resets to false on new log path'

# Completion marker present, but the ping cannot succeed (unroutable URL): per
# the FIXED semantics (Finding 3), CompletionAnnounced must stay false so the
# announcement retries on a later cycle instead of being marked done-when-it-wasn't.
Add-Content -Path $run2 -Value '===== RESUME_DONE 31.07.2026 =====' -Encoding Ascii
Start-Sleep -Milliseconds 200
Invoke-Heartbeat $hbCfgPath | Out-Null
$st5 = Get-Content -LiteralPath $hbStatePath -Raw | ConvertFrom-Json
Assert-Equal $false ([bool]$st5.CompletionAnnounced) 'completion marker present but ping cannot succeed: CompletionAnnounced stays false (Finding 3)'
$tailLines5 = Get-Content -LiteralPath $hbLocalLog -Tail 2
Assert-Equal $true (($tailLines5 -join ' ') -like '*announce FAILED*') 'failed announce is logged for visibility, not silently swallowed (Finding 3)'

Write-Host ''
Write-Host 'Write-HeartbeatState fails closed (review Finding C3)'
# A failed state write must not let the progress ping go green forever: with the
# OLD code, Set-Content had no -ErrorAction Stop and the script set no
# $ErrorActionPreference, so the failure was silently swallowed, Invoke-Heartbeat
# still returned 0, and next cycle Read-HeartbeatState would return empty
# defaults -> $previous = $null -> "first ever observation counts as progress" ->
# the progress ping fires every cycle forever regardless of whether the
# simulation is actually still running. Realistic trigger: C: fills during a
# multi-day sweep, which usually kills the run too.
#
# Send-HealthcheckPing is shadowed (same technique as, and same lexical
# isolation lesson learned from, the Get-Process shadow in
# Test-ResumeSweep.ps1's review fix round) to RECORD which URLs were pinged
# without touching the network, because the existing unroutable-URL trick
# cannot distinguish "the progress ping was skipped" from "the progress ping
# was attempted and failed" - both return $false either way.
& {
    $script:PingedUrls = New-Object System.Collections.Generic.List[string]
    function Send-HealthcheckPing {
        param([string]$BaseUrl, [string]$Suffix = '', [string]$Body = '')
        if (-not [string]::IsNullOrWhiteSpace($BaseUrl)) { $script:PingedUrls.Add($BaseUrl) }
        return $false
    }

    $c3Root = Join-Path $tmp 'c3'
    New-Item -ItemType Directory -Path $c3Root -Force | Out-Null
    $c3LogDir = Join-Path $c3Root 'logs'
    New-Item -ItemType Directory -Path $c3LogDir -Force | Out-Null
    Set-Content -Path (Join-Path $c3LogDir 'stepB_run.log') -Value 'x' -Encoding Ascii

    # A FILE sitting where the state directory should be: Set-Content underneath it
    # cannot succeed, deterministically reproducing a "cannot persist state"
    # failure without needing to actually fill a disk.
    $c3Blocker = Join-Path $c3Root 'blocker'
    Set-Content -Path $c3Blocker -Value 'not a directory' -Encoding Ascii
    $c3StatePath = Join-Path $c3Blocker 'state.json'
    $c3LocalLog = Join-Path $c3Root 'heartbeat.log'

    $c3CfgPath = Join-Path $c3Root 'cfg.json'
    $c3CfgObj = [ordered]@{
        AliveUrl         = 'https://127.0.0.1:9/alive'
        ProgressUrl      = 'https://127.0.0.1:9/progress'
        SweepFinishedUrl = 'https://127.0.0.1:9/finished'
        LogDir           = $c3LogDir
        LogPattern       = '*.log'
        StatePath        = $c3StatePath
        LocalLogPath     = $c3LocalLog
    }
    ($c3CfgObj | ConvertTo-Json) | Set-Content -Path $c3CfgPath -Encoding Ascii

    Invoke-Heartbeat $c3CfgPath | Out-Null

    Assert-Equal $true  ($script:PingedUrls -contains 'https://127.0.0.1:9/alive') 'C3: alive ping still fires even when state cannot be persisted (liveness is unconditional)'
    Assert-Equal $false ($script:PingedUrls -contains 'https://127.0.0.1:9/progress') 'C3: progress ping is SKIPPED when state persistence fails (fail closed, not green-while-dead)'
    Assert-Equal $true  ((Get-Content -LiteralPath $c3LocalLog -Raw) -like '*could not persist*') 'C3: the persistence failure is logged loudly, not swallowed'
}

Remove-Item $tmp -Recurse -Force
if ($script:Failures -gt 0) { Write-Host "`n$($script:Failures) FAILURE(S)"; exit 1 }
Write-Host "`nAll heartbeat tests passed"; exit 0
