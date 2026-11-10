param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet("TravelTimeSnapshotProbe","RoutingContextCaptureProbe","ModalTransportCostsProbe")][string]$ProbeClass = "TravelTimeSnapshotProbe",
    [bool]$TransportCacheEnabled = $true,
    [ValidateRange(6,512)][int]$MinimumAvailableMemoryGiB = 6,
    [string]$JavaHome = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot'
)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$ErrorActionPreference='Stop'

$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw 'Snapshot measurement already exists; preserve its raw results'}
[IO.Directory]::CreateDirectory($output) | Out-Null
. (Join-Path $PSScriptRoot 'ResourceGuard.ps1')
if ((Get-HagridAvailablePhysicalMemory) -lt ($MinimumAvailableMemoryGiB * 1GB + 512MB)) { throw 'Deferred: available memory below reserve plus own probe/compiler headroom' }
[Diagnostics.Process]::GetCurrentProcess().PriorityClass=[Diagnostics.ProcessPriorityClass]::Idle
[xml]$report=Get-Content -LiteralPath (Join-Path $repo 'hagrid/simulation/target/surefire-reports/TEST-hagrid.core.routing.HAGRIDRouterUtilsTest.xml') -Raw
$cp=@($report.testsuite.properties.property | Where-Object name -eq 'java.class.path')[0].value
if([string]::IsNullOrWhiteSpace($cp)){throw 'Fresh verified test classpath missing'}
# Probe compilation alone does not rebuild the integration classes.
# Refuse missing/stale product classes so changed sources cannot be measured as old binaries.
$required=@('TravelTimeSnapshot','RoutingData','RoutingNetworkIdentity')
if ($ProbeClass -ne 'TravelTimeSnapshotProbe') { $required+=@('RoutingNetworkSnapshot','RoutingContext') }
if ($ProbeClass -eq 'ModalTransportCostsProbe') { $required+=@('ModalTransportCosts','RoutingRequest') }
$productArtifacts=@{}
foreach ($name in $required) {
 $source=Join-Path $repo ("hagrid/simulation/src/main/java/hagrid/core/routing/"+$name+".java")
 $binary=Join-Path $repo ("hagrid/simulation/target/classes/hagrid/core/routing/"+$name+".class")
 if (!(Test-Path -LiteralPath $source) -or !(Test-Path -LiteralPath $binary)) { throw ("Fresh guarded product build required before probe: "+$name) }
 if ((Get-Item -LiteralPath $source).LastWriteTimeUtc -gt (Get-Item -LiteralPath $binary).LastWriteTimeUtc) { throw ("Product source is newer than binary; run guarded verification first: "+$name) }
 $binaryHashes=@{}
 $folder=Split-Path -Parent $binary
 $classesForSource=@(Get-ChildItem -LiteralPath $folder -Filter ($name+'*.class') | Where-Object { $_.Name -eq ($name+'.class') -or $_.Name.StartsWith($name+'$') })
 foreach ($classFile in $classesForSource) {
  if ((Get-Item -LiteralPath $source).LastWriteTimeUtc -gt $classFile.LastWriteTimeUtc) { throw ("Stale nested product class; run guarded verification first: "+$classFile.Name) }
  $binaryHashes[$classFile.Name]=(Get-FileHash -LiteralPath $classFile.FullName).Hash
 }
 $productArtifacts[$name]=@{source_sha256=(Get-FileHash -LiteralPath $source).Hash;binary_sha256=$binaryHashes}
}

$compileCp=(($cp -split ';') | Where-Object {
 $_ -match '[\\/]target[\\/]classes$' -or [IO.Path]::GetFileName($_) -match '^(matsim-[0-9][^-]*\.jar|jsprit-core-.*\.jar|log4j-api-.*\.jar|jfreechart-.*\.jar|guava-.*\.jar|fastutil-.*\.jar|jakarta\.inject-api-.*\.jar)$'
}) -join ';'
function JavaArg([string]$text){'"'+$text.Replace('\','/').Replace('"','\"')+'"'}
$classes=Join-Path $output 'classes'
[IO.Directory]::CreateDirectory($classes) | Out-Null

$probe=Join-Path $repo ("tools/benchmarks/"+$ProbeClass+".java")
$compileArgs=Join-Path $output 'compile.args'
[IO.File]::WriteAllLines($compileArgs,@('-proc:none','--release','21','-cp',(JavaArg $compileCp),'-d',(JavaArg $classes),(JavaArg $probe)),[Text.UTF8Encoding]::new($false))
$compileInfo=[Diagnostics.ProcessStartInfo]::new("$javaHome/bin/javac.exe")
$compileInfo.WorkingDirectory=$repo
foreach ($argument in @('-J-Xmx256m','-J-XX:ActiveProcessorCount=1',('@'+$compileArgs))) { $compileInfo.ArgumentList.Add($argument) }
$compiled=Invoke-HagridGuardedProcess -StartInfo $compileInfo -MinimumAvailableMemoryGiB $MinimumAvailableMemoryGiB -HeadroomBytes 512MB -MaximumTotalCpuPercent 85 -CpuPressureGraceSeconds 5 -StdoutPath (Join-Path $output 'compile.log') -StderrPath (Join-Path $output 'compile.stderr.log')
if ($compiled.ResourceDeferred) { throw $compiled.Reason }
if ($compiled.ExitCode -ne 0) { Get-Content (Join-Path $output 'compile.stderr.log') -Tail 20; throw 'Snapshot probe compile failed' }
$logging=Join-Path $output 'silent-log4j.xml'
[IO.File]::WriteAllText($logging,'<Configuration status="ERROR"><Loggers><Root level="error"/></Loggers></Configuration>')
$runArgs=Join-Path $output 'run.args'
[IO.File]::WriteAllLines($runArgs,@('-Xms32m','-Xmx128m','-XX:ActiveProcessorCount=1',('-Dhagrid.benchmark.cache.enabled='+$TransportCacheEnabled.ToString().ToLowerInvariant()),(JavaArg ("-Dlog4j.configurationFile="+([Uri]::new($logging)).AbsoluteUri)),'-cp',(JavaArg ($classes+';'+$cp)),("hagrid.core.routing."+$ProbeClass)),[Text.UTF8Encoding]::new($false))
$manifest=@{fixture='1000-links-2-modes-96-bins-one-variable-profile-v1';warmups=2;captures=3;java_home=$javaHome;heap_mb=128;active_processors=1;priority='Idle';minimum_available_memory_gib=$MinimumAvailableMemoryGiB;working_set_sampling_ms=200;measurement='snapshot/context capture only; no solver or quality comparison';probe_class=$ProbeClass;background='Valhalla has priority';commit=(& git -C $repo rev-parse HEAD);snapshot_sha256=(Get-FileHash (Join-Path $repo 'hagrid/simulation/src/main/java/hagrid/core/routing/TravelTimeSnapshot.java')).Hash;probe_sha256=(Get-FileHash $probe).Hash;status='RUNNING'}
$manifest.product_artifacts=$productArtifacts
if($ProbeClass -ne 'TravelTimeSnapshotProbe'){
 $manifest.context_source_sha256=(Get-FileHash (Join-Path $repo 'hagrid/simulation/src/main/java/hagrid/core/routing/RoutingContext.java')).Hash
 $manifest.context_binary_sha256=(Get-FileHash (Join-Path $repo 'hagrid/simulation/target/classes/hagrid/core/routing/RoutingContext.class')).Hash
}
if($ProbeClass -eq 'ModalTransportCostsProbe'){
 $manifest.fixture='6-links-2-modes-4-static-bins-modal-bounded-cache-10000-queries-v4'
 $manifest.measurement='static paired transport contract; warmed native OD cache and explicitly configurable bounded modal cache; no general solver speedup or quality comparison'
 $manifest.modal_cache_enabled=$TransportCacheEnabled
 $manifest.modal_source_sha256=(Get-FileHash (Join-Path $repo 'hagrid/simulation/src/main/java/hagrid/core/routing/ModalTransportCosts.java')).Hash
 $manifest.modal_binary_sha256=(Get-FileHash (Join-Path $repo 'hagrid/simulation/target/classes/hagrid/core/routing/ModalTransportCosts.class')).Hash
}
$manifest.snapshot_binary_sha256=(Get-FileHash (Join-Path $repo 'hagrid/simulation/target/classes/hagrid/core/routing/TravelTimeSnapshot.class')).Hash
$info=[Diagnostics.ProcessStartInfo]::new("$javaHome/bin/java.exe")
$info.UseShellExecute=$false
$info.CreateNoWindow=$true
$info.RedirectStandardOutput=$true
$info.RedirectStandardError=$true
$info.ArgumentList.Add('@'+$runArgs)
$manifest.own_headroom_bytes=512MB
$manifest.maximum_total_cpu_percent=85
$manifest.cpu_pressure_grace_seconds=5
$manifest.resource_guard_sha256=(Get-FileHash (Join-Path $PSScriptRoot 'ResourceGuard.ps1')).Hash
try {
 $run=Invoke-HagridGuardedProcess -StartInfo $info -MinimumAvailableMemoryGiB $MinimumAvailableMemoryGiB -HeadroomBytes 512MB -MaximumTotalCpuPercent 85 -CpuPressureGraceSeconds 5 -StdoutPath (Join-Path $output 'stdout.log') -StderrPath (Join-Path $output 'stderr.log')
 $manifest.minimum_observed_available_bytes=$run.MinimumAvailableBytes
 if ($run.ResourceDeferred) { throw $run.Reason }
 $stdout=$run.Stdout
 $csvLines=@($stdout -split "\r?\n" | Where-Object {$_ -match '^repeat,links,' -or $_ -match '^[1-3],[0-9]+,[0-9]+,[0-9]+,'})
 [IO.File]::WriteAllLines((Join-Path $output 'measurements.csv'),$csvLines,[Text.UTF8Encoding]::new($false))
 if ($run.ExitCode -ne 0) { throw "Own snapshot probe failed: $($run.Stderr)" }
 $manifest.status='COMPLETED'
 $manifest.observed_working_set_bytes=$run.ObservedWorkingSetBytes
 $manifest.working_set_interpretation='Largest observed sample, not guaranteed peak; zero means no sample.'
 $rows=Import-Csv (Join-Path $output 'measurements.csv')
 if ($rows.Count -ne 3) { throw 'Expected three retained measurements' }
 $rows | Select-Object repeat,car_time,bike_time,car_cost,bike_cost,null_bike_time,profiles,retained_values,numeric_payload_bytes,dense_numeric_reference_bytes,capture_ms,snapshot_capture_ms,context_build_ms,current_thread_allocated_bytes,process_heap_used_bytes | Format-Table
} catch {
 $manifest.status='FAILED_OR_DEFERRED'
 $manifest.reason=$_.Exception.Message
 throw
} finally {
 [IO.File]::WriteAllText((Join-Path $output 'manifest.json'),($manifest | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
}
