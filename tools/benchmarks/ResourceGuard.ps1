# PowerShell 7 / Windows. One owned process tree at a time; never inspect or kill foreign processes.
function Get-HagridAvailablePhysicalMemory {
 if (-not ('HagridResourceMemory' -as [type])) {
  Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class HagridResourceMemory {
 [StructLayout(LayoutKind.Sequential)] public struct Status {
  public uint length, load;
  public ulong totalPhysical, availablePhysical, totalPage, availablePage, totalVirtual, availableVirtual, extended;
 }
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool GlobalMemoryStatusEx(ref Status status);
 public static ulong Available() {
  Status s = new Status(); s.length=(uint)Marshal.SizeOf<Status>();
  if (!GlobalMemoryStatusEx(ref s)) throw new System.ComponentModel.Win32Exception();
  return s.availablePhysical;
 }
}
"@
 }
 return [HagridResourceMemory]::Available()
}

function Invoke-HagridGuardedProcess {
 [CmdletBinding()]
 param(
  [Parameter(Mandatory)][Diagnostics.ProcessStartInfo]$StartInfo,
  [ValidateRange(6,512)][int]$MinimumAvailableMemoryGiB=6,
  [long]$HeadroomBytes=512MB,
  [string]$StdoutPath,
  [string]$StderrPath,
  [ValidateRange(1,100)][double]$MaximumTotalCpuPercent=100,
  [ValidateRange(1,30)][int]$CpuPressureGraceSeconds=5,
  [scriptblock]$CpuSnapshotReader={ Get-HagridCpuSnapshot },
  # Dependency injection for low-memory/error controls; CLI runners always use the native reader.
  [scriptblock]$AvailableMemoryReader={ Get-HagridAvailablePhysicalMemory }
 )
 if ($HeadroomBytes -lt 0 -or $HeadroomBytes -gt 64GB) { throw 'Invalid own process headroom.' }
 $reserve=[long]$MinimumAvailableMemoryGiB * 1GB
 $result=[pscustomobject]@{ ProcessId=$null; ExitCode=$null; ResourceDeferred=$false; Reason=$null; Stdout=''; Stderr=''; MinimumAvailableBytes=$null; ObservedWorkingSetBytes=[long]0; MaximumObservedCpuPercent=$null }
 $lease=[Threading.Mutex]::new($false,'Local\HagridBoundedJavaWork')
 $acquired=$false; $started=$false; $child=$null; $out=$null; $err=$null
 try {
  try { $acquired=$lease.WaitOne(0) } catch [Threading.AbandonedMutexException] { $acquired=$true }
  if (!$acquired) {
   $result.ResourceDeferred=$true; $result.Reason='Deferred: another guarded HAGRID process is active.'
   return $result
  }
  $available=[long](& $AvailableMemoryReader)
  $result.MinimumAvailableBytes=$available
  if ($available -lt ($reserve+$HeadroomBytes)) {
   $result.ResourceDeferred=$true
   $result.Reason='Deferred: available memory below reserve plus own process headroom.'
   return $result
  }
  $cpuBefore=$null; $lastCpuMilliseconds=0; $overloadSince=$null
  if ($MaximumTotalCpuPercent -lt 100) { $cpuBefore=& $CpuSnapshotReader }
  $cpuClock=[Diagnostics.Stopwatch]::StartNew()
  $StartInfo.UseShellExecute=$false; $StartInfo.CreateNoWindow=$true
  $StartInfo.RedirectStandardOutput=$true; $StartInfo.RedirectStandardError=$true
  $child=[Diagnostics.Process]::new(); $child.StartInfo=$StartInfo
  $started=$child.Start()
  if (!$started) { throw 'Own guarded process failed to start.' }
  $result.ProcessId=$child.Id
  $out=$child.StandardOutput.ReadToEndAsync(); $err=$child.StandardError.ReadToEndAsync()
  if (!$child.HasExited) { $child.PriorityClass=[Diagnostics.ProcessPriorityClass]::Idle }
  while (!$child.WaitForExit(200)) {
   $available=[long](& $AvailableMemoryReader)
   $result.MinimumAvailableBytes=[Math]::Min($result.MinimumAvailableBytes,$available)
   if ($available -lt $reserve) {
    $result.ResourceDeferred=$true
    $result.Reason='Deferred: available memory below reserve during own process; stop own tree only.'
    break
   }
   $now=$cpuClock.ElapsedMilliseconds
   if ($MaximumTotalCpuPercent -lt 100 -and $now-$lastCpuMilliseconds -ge 1000) {
    $cpuAfter=& $CpuSnapshotReader
    $busy=Get-HagridCpuBusyPercent -Before $cpuBefore -After $cpuAfter
    $result.MaximumObservedCpuPercent=[Math]::Max([double]$result.MaximumObservedCpuPercent,$busy)
    if ($busy -gt $MaximumTotalCpuPercent) {
     if ($null -eq $overloadSince) { $overloadSince=$lastCpuMilliseconds }
     if ($now-$overloadSince -ge $CpuPressureGraceSeconds*1000) {
      $result.ResourceDeferred=$true
      $result.Reason='Deferred: sustained CPU pressure during own process; stop own tree only.'
      break
     }
    } else { $overloadSince=$null }
    $cpuBefore=$cpuAfter; $lastCpuMilliseconds=$now
   }
   $child.Refresh()
   if (!$child.HasExited) { $result.ObservedWorkingSetBytes=[Math]::Max($result.ObservedWorkingSetBytes,$child.WorkingSet64) }
  }
 } finally {
  try {
   if ($started) {
    if (!$child.HasExited) { $child.Kill($true); $child.WaitForExit() }
    $result.ExitCode=$child.ExitCode
    if ($null -ne $out) { $result.Stdout=$out.GetAwaiter().GetResult() }
    if ($null -ne $err) { $result.Stderr=$err.GetAwaiter().GetResult() }
   }
   if ($StdoutPath) { [IO.File]::WriteAllText([IO.Path]::GetFullPath($StdoutPath),$result.Stdout,[Text.UTF8Encoding]::new($false)) }
   if ($StderrPath) { [IO.File]::WriteAllText([IO.Path]::GetFullPath($StderrPath),$result.Stderr,[Text.UTF8Encoding]::new($false)) }
  } finally {
   if ($null -ne $child) { $child.Dispose() }
   if ($acquired) { $lease.ReleaseMutex() }
   $lease.Dispose()
  }
 }
 return $result
}

# GetSystemTimes includes idle time in kernel ticks:
# https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getsystemtimes
function Get-HagridCpuSnapshot {
 if (-not ('HagridResourceCpu' -as [type])) {
  Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class HagridResourceCpu {
 public sealed class Sample {
  public long IdleTicks, KernelTicks, UserTicks;
 }
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetSystemTimes(out long idle, out long kernel, out long user);
 public static Sample Read() {
  long idle, kernel, user;
  if (!GetSystemTimes(out idle,out kernel,out user)) throw new System.ComponentModel.Win32Exception();
  return new Sample { IdleTicks=idle, KernelTicks=kernel, UserTicks=user };
 }
}
"@
 }
 return [HagridResourceCpu]::Read()
}

function Get-HagridCpuBusyPercent {
 param([Parameter(Mandatory)]$Before,[Parameter(Mandatory)]$After)
 $total=([double]$After.KernelTicks-$Before.KernelTicks)+([double]$After.UserTicks-$Before.UserTicks)
 $idle=[double]$After.IdleTicks-$Before.IdleTicks
 if ($total -le 0 -or $idle -lt 0 -or $idle -gt $total) { throw 'Invalid native CPU observation interval.' }
 return 100.0*(1.0-$idle/$total)
}

function Select-HagridVerificationResources {
 param(
  [Parameter(Mandatory)][long]$AvailableMemoryBytes,
  [Parameter(Mandatory)][double]$MinimumIdleCpuPercent,
  [ValidateRange(1,65536)][int]$LogicalProcessors,
  [ValidateSet('Auto','Conservative')][string]$ResourceMode='Auto'
 )
 $profile=[pscustomobject]@{
  ResourceMode=$ResourceMode; ActiveProcessors=1; MavenHeapMiB=512; TestHeapMiB=384; HeadroomMiB=1536;
  ReserveGiB=6; MinimumAvailableBytes=$AvailableMemoryBytes; MinimumIdleCpuPercent=$MinimumIdleCpuPercent;
  LogicalProcessors=$LogicalProcessors; MaximumTotalCpuPercent=85; CpuPressureGraceSeconds=5; Eligible=$true; Reason=$null
 }
 if ([double]::IsNaN($MinimumIdleCpuPercent) -or [double]::IsInfinity($MinimumIdleCpuPercent) -or $MinimumIdleCpuPercent -lt 0 -or $MinimumIdleCpuPercent -gt 100) {
  $profile.Eligible=$false; $profile.Reason='Deferred: invalid CPU observation.'; return $profile
 }
 if ($MinimumIdleCpuPercent -lt 20) { $profile.Eligible=$false; $profile.Reason='Deferred: background CPU pressure.'; return $profile }
 # Budget a further 20% of all CPUs for rising background demand; keep at least half unassigned.
 $cpuRoom=[Math]::Min([Math]::Floor($LogicalProcessors/2.0),[Math]::Floor($LogicalProcessors*($MinimumIdleCpuPercent-20)/100.0))
 if ($ResourceMode -eq 'Auto') {
  if ($AvailableMemoryBytes -ge 16GB -and $MinimumIdleCpuPercent -ge 60 -and $cpuRoom -ge 4) {
   $profile.ActiveProcessors=4; $profile.MavenHeapMiB=1024; $profile.TestHeapMiB=1024; $profile.HeadroomMiB=3072
  } elseif ($AvailableMemoryBytes -ge 10GB -and $MinimumIdleCpuPercent -ge 40 -and $cpuRoom -ge 2) {
   $profile.ActiveProcessors=2; $profile.MavenHeapMiB=768; $profile.TestHeapMiB=768; $profile.HeadroomMiB=2560
  }
 }
 if ($AvailableMemoryBytes -lt (6GB+$profile.HeadroomMiB*1MB)) {
  $profile.Eligible=$false; $profile.Reason='Deferred: available memory below reserve plus selected own headroom.'
 }
 return $profile
}

function Get-HagridVerificationResources {
 param([ValidateSet('Auto','Conservative')][string]$ResourceMode='Auto')
 $minimumMemory=[long](Get-HagridAvailablePhysicalMemory)
 $logical=[Environment]::ProcessorCount
 # The native API covers only one processor group above 64 processors.
 if ($logical -gt 64) { return Select-HagridVerificationResources -AvailableMemoryBytes $minimumMemory -MinimumIdleCpuPercent ([double]::NaN) -LogicalProcessors $logical -ResourceMode $ResourceMode }
 $before=Get-HagridCpuSnapshot
 $minimumIdle=100.0
 for ($sample=0;$sample -lt 6;$sample++) {
  Start-Sleep -Milliseconds 500
  $after=Get-HagridCpuSnapshot
  $minimumIdle=[Math]::Min($minimumIdle,100.0-(Get-HagridCpuBusyPercent -Before $before -After $after))
  $minimumMemory=[Math]::Min($minimumMemory,[long](Get-HagridAvailablePhysicalMemory))
  $before=$after
 }
 return Select-HagridVerificationResources -AvailableMemoryBytes $minimumMemory -MinimumIdleCpuPercent $minimumIdle -LogicalProcessors $logical -ResourceMode $ResourceMode
}
