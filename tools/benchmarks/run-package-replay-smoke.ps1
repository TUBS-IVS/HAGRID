param(
 [Parameter(Mandatory)][string]$OutputDirectory,
 [string]$JavaHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot',
 [string]$VerifiedBuildManifest
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'ResourceGuard.ps1')
. (Join-Path $PSScriptRoot 'CurrentBuildEvidence.ps1')
if(!$VerifiedBuildManifest){$VerifiedBuildManifest=Join-Path $repo '.superpowers/sdd/2026-10-10-hagrid-audit-fixes-und-baustein-refactor/verification/verified-build.json'}
$output=[IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'Use a new output directory; no previous evidence is overwritten.' }
[IO.Directory]::CreateDirectory($output) | Out-Null
$jar=Join-Path $repo 'hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar'
$source=Join-Path $PSScriptRoot 'PackageReplaySmoke.java'
$classes=Join-Path $output 'classes'
[IO.Directory]::CreateDirectory($classes) | Out-Null
$manifest=[ordered]@{ status='STARTED'; jar=$jar; jarSha256=(Get-FileHash -LiteralPath $jar).Hash; fixtureSha256=(Get-FileHash -LiteralPath $source).Hash; maximumHeapMiB=384; activeProcessors=1; reserveGiB=6; observations=@() }
function Save-Manifest {
 [IO.File]::WriteAllText((Join-Path $output 'manifest.json'),($manifest | ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
}
function Run-OwnJava([string]$name,[string]$executable,[string[]]$arguments) {
 $resources=Get-HagridVerificationResources -ResourceMode Conservative
 if (!$resources.Eligible) { throw $resources.Reason }
 $info=[Diagnostics.ProcessStartInfo]::new((Join-Path $JavaHome ('bin/'+$executable)))
 $info.WorkingDirectory=$repo
 # This launcher controls only its own JVMs, independently of inherited tool options.
 $info.Environment.Remove('JAVA_TOOL_OPTIONS') | Out-Null
 $info.Environment.Remove('_JAVA_OPTIONS') | Out-Null
 $info.Environment.Remove('JDK_JAVA_OPTIONS') | Out-Null
 foreach ($arg in $arguments) { $info.ArgumentList.Add($arg) }
 $result=Invoke-HagridGuardedProcess -StartInfo $info -HeadroomBytes ($resources.HeadroomMiB*1MB) -MaximumTotalCpuPercent 85 -CpuPressureGraceSeconds 5 -StdoutPath (Join-Path $output ($name+'.log')) -StderrPath (Join-Path $output ($name+'.stderr.log'))
 $manifest.observations+=@{ name=$name; selection=$resources; run=($result | Select-Object ExitCode,ResourceDeferred,Reason,MinimumAvailableBytes,MaximumObservedCpuPercent,ObservedWorkingSetBytes); memoryMeaning='Owned direct JVM working set, includes shared context/runtime; not exclusive carrier heap.' }
 Save-Manifest
 if ($result.ResourceDeferred) { throw $result.Reason }
 if ($result.ExitCode -ne 0) { Get-Content (Join-Path $output ($name+'.stderr.log')) -Tail 12; throw ('Own Java failed: '+$name) }
 return $result.Stdout
}
Save-Manifest
try {
 $resources=Get-HagridVerificationResources -ResourceMode Conservative
 if(!$resources.Eligible){$manifest.status='DEFERRED';$manifest.reason=$resources.Reason;Save-Manifest;Write-Output $resources.Reason;return}
 $verified=Get-HagridVerifiedBuild $repo $VerifiedBuildManifest
 if($verified.jarSha256 -ne $manifest.jarSha256){throw 'Replay JAR changed before compilation.'}
 $manifest.sourceTreeSha256=$verified.sourceTreeSha256;$manifest.verifiedBuild=$verified
 Save-Manifest
 Run-OwnJava 'compile' 'javac.exe' @('-J-Xms32m','-J-Xmx256m','-J-XX:ActiveProcessorCount=1','-proc:none','-cp',$jar,'-d',$classes,$source) | Out-Null
 $jvm=@('-Xms32m','-Xmx384m','-XX:ActiveProcessorCount=1')
 $export=Run-OwnJava 'export' 'java.exe' ($jvm+@('-cp',($classes+';'+$jar),'hagrid.core.routing.benchmark.PackageReplaySmoke',(Join-Path $output 'cases')))
 foreach ($name in @('services','shipments')) {
  $replay=Run-OwnJava $name 'java.exe' ($jvm+@('-cp',$jar,'hagrid.core.routing.CarrierReplay',(Join-Path $output ('cases/'+$name))))
  $expected=[regex]::Match($export,('exported='+$name+' context=([a-f0-9]+) finalCost=([^\r\n]+)'))
  $actual=[regex]::Match($replay,('carrier='+$name+' context=([a-f0-9]+) iterations=2 finalCost=([^\s]+) unassigned=\[\] diagnostics=\[\]'))
  if (!$expected.Success -or !$actual.Success -or $expected.Groups[1].Value -ne $actual.Groups[1].Value -or $expected.Groups[2].Value -ne $actual.Groups[2].Value) { throw ('Packaged replay certificate differs: '+$name) }
  Write-Output $actual.Value
 }
 $contexts=@(Get-ChildItem -LiteralPath (Join-Path $output 'cases/.contexts') -File)
 if ($contexts.Count -ne 1) { throw 'Shared context was duplicated.' }
 $after=Get-HagridVerifiedBuild $repo $VerifiedBuildManifest
 if($after.sourceTreeSha256 -ne $verified.sourceTreeSha256 -or $after.jarSha256 -ne $verified.jarSha256){throw 'Source/JAR changed during package replay.'}
 $manifest.status='PASSED';Save-Manifest
} catch { $manifest.status='FAILED_OR_DEFERRED';$manifest.reason=$_.Exception.Message;Save-Manifest;throw }