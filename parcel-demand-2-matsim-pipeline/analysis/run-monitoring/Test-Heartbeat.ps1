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
Assert-Equal $true  (Test-BatchComplete @('SCEN10_EXIT=0')) 'scenario exit marker'
Assert-Equal $true  (Test-BatchComplete @('noise','REDO70_EXIT=1','noise')) 'exit marker anywhere in tail'
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
Write-HeartbeatState $statePath @{ LastLogPath='a.log'; LastWriteTicks=1234L; LastLength=7L; CompletionAnnounced=$true; EventPendingRearm=$false }
$loaded = Read-HeartbeatState $statePath
Assert-Equal 'a.log' $loaded.LastLogPath 'path round-trips'
Assert-Equal 1234 $loaded.LastWriteTicks 'ticks round-trip'
Assert-Equal $true $loaded.CompletionAnnounced 'bool round-trips'

Remove-Item $tmp -Recurse -Force
if ($script:Failures -gt 0) { Write-Host "`n$($script:Failures) FAILURE(S)"; exit 1 }
Write-Host "`nAll heartbeat tests passed"; exit 0
