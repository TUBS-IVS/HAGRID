$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ResourceGuard.ps1')
if ((Get-HagridAvailablePhysicalMemory) -lt 6GB) { throw 'Deferred: resource policy checks require the real reserve.' }
if (!(Get-Command Select-HagridVerificationResources -ErrorAction SilentlyContinue)) { throw 'FAIL: adaptive own CPU/heap allocation with a fixed 6 GiB reserve is missing.' }
$checks=0
function Assert-Policy([bool]$ok,[string]$why) { if (!$ok) { throw ('FAIL: '+$why) }; $script:checks++ }
$p=Select-HagridVerificationResources -AvailableMemoryBytes (7.5GB) -MinimumIdleCpuPercent 100 -LogicalProcessors 32
Assert-Policy ($p.Eligible -and $p.ActiveProcessors -eq 1 -and $p.MavenHeapMiB -eq 512 -and $p.TestHeapMiB -eq 384) 'small reserve-margin tier remains conservative'
$p=Select-HagridVerificationResources -AvailableMemoryBytes (7.5GB-1) -MinimumIdleCpuPercent 100 -LogicalProcessors 32
Assert-Policy (!$p.Eligible) 'own memory headroom is additional to the fixed reserve'
$p=Select-HagridVerificationResources -AvailableMemoryBytes 10GB -MinimumIdleCpuPercent 70 -LogicalProcessors 4
Assert-Policy ($p.Eligible -and $p.ActiveProcessors -eq 2 -and $p.TestHeapMiB -eq 768 -and $p.HeadroomMiB -eq 2560) 'two-core tier needs both memory and CPU headroom'
$p=Select-HagridVerificationResources -AvailableMemoryBytes (10GB-1) -MinimumIdleCpuPercent 100 -LogicalProcessors 32
Assert-Policy ($p.ActiveProcessors -eq 1) 'RAM below medium threshold prevents scale up'
$p=Select-HagridVerificationResources -AvailableMemoryBytes 16GB -MinimumIdleCpuPercent 60 -LogicalProcessors 12
Assert-Policy ($p.ActiveProcessors -eq 4 -and $p.TestHeapMiB -eq 1024 -and $p.HeadroomMiB -eq 3072) 'four-core tier uses capped heaps and leaves at least half of logical CPUs'
$p=Select-HagridVerificationResources -AvailableMemoryBytes 16GB -MinimumIdleCpuPercent 60 -LogicalProcessors 8
Assert-Policy ($p.ActiveProcessors -eq 2) 'CPU allocation also leaves twenty percent of the machine idle under full own use'
$p=Select-HagridVerificationResources -AvailableMemoryBytes 64GB -MinimumIdleCpuPercent 100 -LogicalProcessors 64
Assert-Policy ($p.ActiveProcessors -eq 4 -and $p.MavenHeapMiB -eq 1024) 'surplus resources never produce an unbounded own allocation'
$p=Select-HagridVerificationResources -AvailableMemoryBytes 64GB -MinimumIdleCpuPercent 59 -LogicalProcessors 16
Assert-Policy ($p.ActiveProcessors -eq 2) 'high memory alone is insufficient for four cores'
$p=Select-HagridVerificationResources -AvailableMemoryBytes 64GB -MinimumIdleCpuPercent 100 -LogicalProcessors 2
Assert-Policy ($p.ActiveProcessors -eq 1) 'small machines cannot surrender all CPUs'
$p=Select-HagridVerificationResources -AvailableMemoryBytes 64GB -MinimumIdleCpuPercent 19 -LogicalProcessors 32
Assert-Policy (!$p.Eligible -and $p.Reason -match 'CPU') 'saturated background work defers even when RAM is abundant'
$p=Select-HagridVerificationResources -AvailableMemoryBytes 64GB -MinimumIdleCpuPercent ([double]::NaN) -LogicalProcessors 32
Assert-Policy (!$p.Eligible) 'invalid CPU observation never authorizes a scale up'

$p=Select-HagridVerificationResources -AvailableMemoryBytes 64GB -MinimumIdleCpuPercent 100 -LogicalProcessors 64 -ResourceMode Conservative
Assert-Policy ($p.ActiveProcessors -eq 1 -and $p.TestHeapMiB -eq 384) 'fixed comparison mode preserves the original CPU and heap limits'
$a=[pscustomobject]@{ IdleTicks=100; KernelTicks=200; UserTicks=100 }
$b=[pscustomobject]@{ IdleTicks=160; KernelTicks=280; UserTicks=120 }
Assert-Policy ([Math]::Abs((Get-HagridCpuBusyPercent -Before $a -After $b)-40) -lt 0.0001) 'Windows kernel counter includes idle time'
$taskRoot=Join-Path ([IO.Path]::GetTempPath()) ('hagrid-cpu-pressure-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($taskRoot) | Out-Null
$pwsh=(Get-Process -Id $PID).Path
$sleeper=Join-Path $taskRoot 'sleeper.ps1'
[IO.File]::WriteAllText($sleeper,'Start-Sleep -Seconds 20')
$info=[Diagnostics.ProcessStartInfo]::new($pwsh)
$info.ArgumentList.Add('-NoProfile'); $info.ArgumentList.Add('-File'); $info.ArgumentList.Add($sleeper)
$state=@{ticks=0}
$busyReader={ $state.ticks+=100; [pscustomobject]@{IdleTicks=0;KernelTicks=$state.ticks;UserTicks=0} }.GetNewClosure()
$result=Invoke-HagridGuardedProcess -StartInfo $info -AvailableMemoryReader { 8GB } -MaximumTotalCpuPercent 85 -CpuPressureGraceSeconds 1 -CpuSnapshotReader $busyReader
Assert-Policy ($result.ResourceDeferred -and $result.Reason -match 'CPU') 'sustained CPU pressure ends only owned work'
Assert-Policy ($null -eq (Get-Process -Id $result.ProcessId -ErrorAction SilentlyContinue)) 'CPU pressure leaves no owned child running'
$quick=Join-Path $taskRoot 'cpu-spike.ps1'
[IO.File]::WriteAllText($quick,"Start-Sleep -Milliseconds 2700; Write-Output 'spike-survived'")
$spikeInfo=[Diagnostics.ProcessStartInfo]::new($pwsh)
$spikeInfo.ArgumentList.Add('-NoProfile'); $spikeInfo.ArgumentList.Add('-File'); $spikeInfo.ArgumentList.Add($quick)
$spikeState=@{count=0;idle=0}
$spikeReader={ $spikeState.count++; if ($spikeState.count -ge 3) { $spikeState.idle+=100 }; [pscustomobject]@{IdleTicks=$spikeState.idle;KernelTicks=$spikeState.count*100;UserTicks=0} }.GetNewClosure()
$result=Invoke-HagridGuardedProcess -StartInfo $spikeInfo -AvailableMemoryReader { 8GB } -MaximumTotalCpuPercent 85 -CpuPressureGraceSeconds 2 -CpuSnapshotReader $spikeReader
Assert-Policy (!$result.ResourceDeferred -and $result.ExitCode -eq 0 -and $result.Stdout -match 'spike-survived') 'a short CPU spike that clears does not cancel the test'
$failureMarker=Join-Path $taskRoot 'cpu-read-error.pid'
$failureScript=Join-Path $taskRoot 'cpu-read-error.ps1'
[IO.File]::WriteAllText($failureScript,'param([string]$Marker); [IO.File]::WriteAllText($Marker,$PID.ToString()); Start-Sleep -Seconds 20')
$failureInfo=[Diagnostics.ProcessStartInfo]::new($pwsh)
$failureInfo.ArgumentList.Add('-NoProfile'); $failureInfo.ArgumentList.Add('-File'); $failureInfo.ArgumentList.Add($failureScript); $failureInfo.ArgumentList.Add($failureMarker)
$readState=@{count=0}
$failureReader={ $readState.count++; if ($readState.count -gt 1) { throw 'fixture: CPU observation unavailable' }; [pscustomobject]@{IdleTicks=0;KernelTicks=100;UserTicks=0} }.GetNewClosure()
$caught=$null
try { Invoke-HagridGuardedProcess -StartInfo $failureInfo -AvailableMemoryReader { 8GB } -MaximumTotalCpuPercent 85 -CpuSnapshotReader $failureReader | Out-Null } catch { $caught=$_ }
Assert-Policy ($null -ne $caught -and $caught.Exception.Message -match 'CPU observation unavailable') 'CPU read errors fail closed'
Assert-Policy ($null -eq (Get-Process -Id ([int](Get-Content -LiteralPath $failureMarker)) -ErrorAction SilentlyContinue)) 'CPU observation error terminates the owned child'
Write-Output ("PASS: {0} adaptive resource assertions; fixtures {1}" -f $checks,$taskRoot)
