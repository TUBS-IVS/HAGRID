param([Parameter(Mandatory)][string]$OutputDirectory,
 [int[]]$Seeds=@(42,43,44,45,46),[ValidateRange(1,3)][int]$Repeats=3)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'ResourceGuard.ps1')
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw 'Use a new directory; original evidence is preserved.'}
if(!$Seeds.Count -or $Seeds.Count -gt 5 -or @($Seeds|Select-Object -Unique).Count -ne $Seeds.Count){throw 'One to five distinct signed-int seeds required.'}
$claim=$output+'.claim.'+[Guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory($claim)|Out-Null
try{[IO.Directory]::Move($claim,$output)}finally{if([IO.Directory]::Exists($claim)){[IO.Directory]::Delete($claim)}}
$javaHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot'
$repository='C:/Users/bienzeisler/.m2/repository'
$cores=@{'1.8'=(Join-Path $repository 'com/graphhopper/jsprit-core/1.8/jsprit-core-1.8.jar');
 '2.0'=(Join-Path $repository 'com/graphhopper/jsprit-core/2.0.0/jsprit-core-2.0.0.jar')}
$dependencies=@(
 (Join-Path $repository 'org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar'),
 (Join-Path $repository 'org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar'),
 (Join-Path $repository 'net/sf/trove4j/trove4j/3.0.3/trove4j-3.0.3.jar'))
$source=Join-Path $PSScriptRoot 'JspritCoreVersionProbe.java'
$classes=Join-Path $output 'classes'
[IO.Directory]::CreateDirectory($classes)|Out-Null
$inputFiles=@($source,$PSCommandPath,(Join-Path $PSScriptRoot 'ResourceGuard.ps1'))+@($cores.Values)+$dependencies
$digests=@{}
foreach($file in $inputFiles){$digests[$file]=(Get-FileHash -LiteralPath $file).Hash}
$manifest=[ordered]@{status='STARTED';seeds=$Seeds;repeats=$Repeats;inputDigests=$digests;cores=$cores;
 activeProcessors=1;heapMiB=384;reserveGiB=6;actualIterations=10;jobs=16;observations=@();
 interpretation='Core-only static Manhattan matrix, finite2vans/cap8, same16servicejobs per root and common fixed100+transport-time objective. Same common dependency binaries; only core JAR varies. No MATSim/HAGRID classes in classpath. No premature termination; actual10iterations required. Two warmups then one measured search per fresh JVM. Measures search only, excludes fixture/build/setup/validation/JVM start. No MATSim/network integration or production speed/quality claim.'}
function Save-Manifest{[IO.File]::WriteAllText((Join-Path $output 'manifest.json'),($manifest|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))}
function Run-Own([string]$name,[string]$program,[string[]]$arguments){
 $resources=Get-HagridVerificationResources -ResourceMode Conservative
 if(!$resources.Eligible){$manifest.deferredSelection=$resources;throw $resources.Reason}
 $start=[Diagnostics.ProcessStartInfo]::new((Join-Path $javaHome ('bin/'+$program)))
 $start.WorkingDirectory=$repo
 foreach($key in @('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS')){$start.Environment.Remove($key)|Out-Null}
 foreach($arg in $arguments){$start.ArgumentList.Add($arg)}
 $result=Invoke-HagridGuardedProcess -StartInfo $start -HeadroomBytes ($resources.HeadroomMiB*1MB) -MaximumTotalCpuPercent 85 -CpuPressureGraceSeconds 5 -StdoutPath (Join-Path $output ($name+'.log')) -StderrPath (Join-Path $output ($name+'.stderr.log'))
 $manifest.observations+=@{name=$name;selection=$resources;run=($result|Select-Object ExitCode,ResourceDeferred,Reason,MinimumAvailableBytes,MaximumObservedCpuPercent)}
 Save-Manifest
 if($result.ResourceDeferred -or $result.ExitCode -ne 0){throw ('Own native process failed/deferred: '+$name)}
 return $result.Stdout
}
Save-Manifest
try{
 $compileCp=($cores['1.8']+';'+($dependencies -join ';'))
 Run-Own 'compile' 'javac.exe' @('-J-Xmx256m','-J-XX:ActiveProcessorCount=1','-proc:none','-cp',$compileCp,'-d',$classes,$source)|Out-Null
 $rows=@('version,repeat,marker,seed,actualIterations,jobs,served,violations,vehicles,searchCost,finalCost,searchNanos,callerAllocatedBytes,loadedCore,solution')
 foreach($seed in $Seeds){for($repeat=0;$repeat -lt $Repeats;$repeat++){
  $order=@('1.8','2.0');if(($seed+$repeat)%2){$order=@('2.0','1.8')}
  foreach($version in $order){
   $core=$cores[$version];$cp=$classes+';'+$core+';'+($dependencies -join ';')
   $name=$version+'-'+$seed+'-'+$repeat
   $stdout=Run-Own $name 'java.exe' @('-Xms32m','-Xmx384m','-XX:ActiveProcessorCount=1','-cp',$cp,'hagrid.core.routing.benchmark.JspritCoreVersionProbe',$seed.ToString(),$core)
   $found=@($stdout -split '\r?\n'|Where-Object{$_.StartsWith('CORE_VERSION,')})
   if($found.Count -ne 1){throw 'Missing or duplicate native version observation'}
   $rows+=($version+','+$repeat+','+$found[0])
   [IO.File]::WriteAllLines((Join-Path $output 'measurements.csv'),$rows,[Text.UTF8Encoding]::new($false))
   Write-Output ('Recorded core '+$name)
  }
 }}
 foreach($file in $inputFiles){if((Get-FileHash -LiteralPath $file).Hash -ne $digests[$file]){throw 'Input binary/source changed during comparison'}}
 $manifest.status='MEASURED';Save-Manifest
}catch{$manifest.status='FAILED_OR_DEFERRED';$manifest.reason=$_.Exception.Message;Save-Manifest;throw}
