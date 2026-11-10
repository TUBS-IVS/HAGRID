$ErrorActionPreference='Stop'
$guardPath=Join-Path $PSScriptRoot 'ResourceGuard.ps1'
if (!(Test-Path -LiteralPath $guardPath)) { throw 'FAIL: tests and compilation need continuous memory supervision; shared guarded runner is missing.' }
. $guardPath
if ((Get-HagridAvailablePhysicalMemory) -lt 6GB) { throw 'Deferred: safety regression checks require the real 6 GiB reserve.' }
$taskRoot=Join-Path ([IO.Path]::GetTempPath()) ('hagrid-resource-guard-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($taskRoot) | Out-Null
$pwsh=(Get-Process -Id $PID).Path
$checks=0
function Assert-Guard([bool]$condition,[string]$message) {
 if (!$condition) { throw ('FAIL: '+$message) }; $script:checks++
}
function Child-Info([string]$scriptPath) {
 $info=[Diagnostics.ProcessStartInfo]::new($pwsh)
 $info.ArgumentList.Add('-NoProfile'); $info.ArgumentList.Add('-File'); $info.ArgumentList.Add($scriptPath)
 return $info
}
$quick=Join-Path $taskRoot 'quick.ps1'
[IO.File]::WriteAllText($quick,"Write-Output 'guard-child-completed'; exit 0")
$result=Invoke-HagridGuardedProcess -StartInfo (Child-Info $quick) -HeadroomBytes 512MB -AvailableMemoryReader { 6GB+511MB }
Assert-Guard $result.ResourceDeferred 'no start without reserve plus own headroom'
Assert-Guard ($null -eq $result.ProcessId) 'denied admission creates no child'
$result=Invoke-HagridGuardedProcess -StartInfo (Child-Info $quick) -HeadroomBytes 512MB -AvailableMemoryReader { 6GB+512MB }
Assert-Guard (!$result.ResourceDeferred -and $result.ExitCode -eq 0 -and $result.Stdout -match 'guard-child-completed') 'exact admission threshold permits a small successful child'
$failed=Join-Path $taskRoot 'failed.ps1'
[IO.File]::WriteAllText($failed,"[Console]::Error.WriteLine('expected-fixture-error'); exit 9")
$result=Invoke-HagridGuardedProcess -StartInfo (Child-Info $failed) -AvailableMemoryReader { 8GB }
Assert-Guard ($result.ExitCode -eq 9 -and $result.Stderr -match 'expected-fixture-error' -and !$result.ResourceDeferred) 'child failure and stderr remain distinguishable from resource deferral'
$sleeper=Join-Path $taskRoot 'sleeper.ps1'
[IO.File]::WriteAllText($sleeper,'Start-Sleep -Seconds 20')
$outsideInfo=Child-Info $sleeper
$outsideInfo.UseShellExecute=$false; $outsideInfo.CreateNoWindow=$true
$outside=[Diagnostics.Process]::Start($outsideInfo)
$marker=Join-Path $taskRoot 'owned-grandchild.pid'
$parentScript=Join-Path $taskRoot 'parent.ps1'
$parentCode=@'
param([string]$Pwsh,[string]$Sleeper,[string]$Marker)
$info=[Diagnostics.ProcessStartInfo]::new($Pwsh)
$info.UseShellExecute=$false; $info.CreateNoWindow=$true
$info.ArgumentList.Add('-NoProfile'); $info.ArgumentList.Add('-File'); $info.ArgumentList.Add($Sleeper)
$child=[Diagnostics.Process]::Start($info)
[Console]::Out.WriteLine('owned-parent-started')
[Console]::Out.Flush()
[IO.File]::WriteAllText($Marker,$child.Id.ToString())
Start-Sleep -Seconds 20
'@
[IO.File]::WriteAllText($parentScript,$parentCode)
$pressureInfo=Child-Info $parentScript
$pressureInfo.ArgumentList.Add($pwsh); $pressureInfo.ArgumentList.Add($sleeper); $pressureInfo.ArgumentList.Add($marker)
$memoryReader={ if (Test-Path -LiteralPath $marker) { return 6GB-1 }; return 8GB }.GetNewClosure()
try {
 $result=Invoke-HagridGuardedProcess -StartInfo $pressureInfo -AvailableMemoryReader $memoryReader
 Assert-Guard ($result.ResourceDeferred -and $result.Reason -match 'below' -and $result.Stdout -match 'owned-parent-started') 'pressure during execution stops the owned child and preserves output'
 $grandchildId=[int](Get-Content -LiteralPath $marker)
 Assert-Guard ($null -eq (Get-Process -Id $grandchildId -ErrorAction SilentlyContinue)) 'owned descendant terminates with owned parent'
 Assert-Guard (!$outside.HasExited) 'process outside guarded child tree is untouched'
 $ownPidMarker=Join-Path $taskRoot 'observation-error.pid'
 $observedSleeper=Join-Path $taskRoot 'observed-sleeper.ps1'
 [IO.File]::WriteAllText($observedSleeper,'param([string]$Marker); [IO.File]::WriteAllText($Marker,$PID.ToString()); Start-Sleep -Seconds 20')
 $observedInfo=Child-Info $observedSleeper; $observedInfo.ArgumentList.Add($ownPidMarker)
 $unavailableReader={ if (Test-Path -LiteralPath $ownPidMarker) { throw 'fixture: memory read unavailable' }; return 8GB }.GetNewClosure()
 $caught=$null
 try { Invoke-HagridGuardedProcess -StartInfo $observedInfo -AvailableMemoryReader $unavailableReader | Out-Null } catch { $caught=$_ }
 Assert-Guard ($null -ne $caught -and $caught.Exception.Message -match 'memory read unavailable') 'failed memory observation fails closed'
 Assert-Guard ($null -eq (Get-Process -Id ([int](Get-Content -LiteralPath $ownPidMarker)) -ErrorAction SilentlyContinue)) 'memory observation error also terminates the owned process'
 Assert-Guard (!$outside.HasExited) 'observation failure leaves outside process untouched'
 $caught=$null
 try { Invoke-HagridGuardedProcess -StartInfo (Child-Info $quick) -MinimumAvailableMemoryGiB 5 | Out-Null } catch { $caught=$_ }
 Assert-Guard ($null -ne $caught) 'reserve cannot be configured below 6 GiB'
} finally {
 if (!$outside.HasExited) { $outside.Kill($true); $outside.WaitForExit(5000) | Out-Null }; $outside.Dispose()
}
$contenderScript=Join-Path $taskRoot 'contender.ps1'
[IO.File]::WriteAllText($contenderScript,'param([string]$Guard,[string]$Pwsh,[string]$Quick); . $Guard; $info=[Diagnostics.ProcessStartInfo]::new($Pwsh); $info.ArgumentList.Add("-NoProfile"); $info.ArgumentList.Add("-File"); $info.ArgumentList.Add($Quick); $result=Invoke-HagridGuardedProcess -StartInfo $info; if ($result.ResourceDeferred -and $null -eq $result.ProcessId) { Write-Output "guard-contender-deferred" } else { exit 11 }')
$contenderInfo=Child-Info $contenderScript
$contenderInfo.ArgumentList.Add($guardPath); $contenderInfo.ArgumentList.Add($pwsh); $contenderInfo.ArgumentList.Add($quick)
$contenderInfo.UseShellExecute=$false; $contenderInfo.CreateNoWindow=$true; $contenderInfo.RedirectStandardOutput=$true
$serialLease=[Threading.Mutex]::new($false,'Local\HagridBoundedJavaWork')
$ownedLease=$serialLease.WaitOne(0)
$contender=$null
try {
 Assert-Guard $ownedLease 'serial execution lease can be obtained after all completed runs'
 $contender=[Diagnostics.Process]::Start($contenderInfo)
 $contenderOutput=$contender.StandardOutput.ReadToEndAsync()
 Assert-Guard ($contender.WaitForExit(5000)) 'competing caller defers promptly'
 Assert-Guard ($contender.ExitCode -eq 0 -and $contenderOutput.GetAwaiter().GetResult() -match 'guard-contender-deferred') 'another caller cannot launch a parallel guarded process'
} finally {
 if ($null -ne $contender) { if (!$contender.HasExited) { $contender.Kill($true); $contender.WaitForExit(5000) | Out-Null }; $contender.Dispose() }
 if ($ownedLease) { $serialLease.ReleaseMutex() }; $serialLease.Dispose()
}
Write-Output ("PASS: {0} resource-guard assertions; fixtures {1}" -f $checks,$taskRoot)
