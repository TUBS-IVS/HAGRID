param(
 [Parameter(Mandatory)][string[]]$CaseDirectories,
 [Parameter(Mandatory)][string]$OutputDirectory,
 [ValidateRange(1,3)][int]$Repeats=1,
 [string]$JavaHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot'
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'ResourceGuard.ps1')
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw 'Use a new output directory; no evidence is overwritten.'}
[IO.Directory]::CreateDirectory($output) | Out-Null
$jar=Join-Path $repo 'hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar'
$probe=Join-Path $PSScriptRoot 'ReplayMemoryProbe.java'
$agent=Join-Path $PSScriptRoot 'RetainedGraphAgent.java'
$encoding=[Text.UTF8Encoding]::new($false)
$manifest=[ordered]@{status='STARTED';jarSha256=(Get-FileHash -LiteralPath $jar).Hash;runnerSha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash;probeSha256=(Get-FileHash -LiteralPath $probe).Hash;agentSha256=(Get-FileHash -LiteralPath $agent).Hash;javaHome=$JavaHome;repeats=$Repeats;heapMiB=384;activeProcessors=1;reserveGiB=6;representativeCalibration=$false;deferredSelection=$null;cases=@();measurements=@();observations=@();interpretation='Fresh-JVM cold replay envelope includes input/context restoration, service, routing and validation. Sampled whole JVM includes runtime, shared state, garbage and profiling overhead; not exclusive carrier RAM. Returned-result reachable graph is measured after replay closed its context, not the full MATSim base or staged group. Caller allocation excludes native solver-worker threads. No forced GC, automatic estimator, production activation or speedup claim.'}
function Save-Manifest { [IO.File]::WriteAllText((Join-Path $output 'manifest.json'),($manifest | ConvertTo-Json -Depth 10),$encoding) }
function Input-Hashes([string]$directory,[string]$context) {
 $hashes=[ordered]@{}
 foreach($name in @('manifest.json','carrier.xml','types.xml')){$hashes[$name]=(Get-FileHash -LiteralPath (Join-Path $directory $name)).Hash}
 $hashes['context']=(Get-FileHash -LiteralPath $context).Hash
 return $hashes
}
function Read-BoundedCase([string]$directory) {
 $directory=[IO.Path]::GetFullPath($directory)
 foreach($name in @('manifest.json','carrier.xml','types.xml')){
  $file=Get-Item -LiteralPath (Join-Path $directory $name)
  if($file.PSIsContainer -or $file.Length -gt 1MB){throw 'Replay payload exceeds the small-profile bound.'}
 }
 $capture=Get-Content -LiteralPath (Join-Path $directory 'manifest.json') -Raw | ConvertFrom-Json
 if($capture.version -ne 'hagrid-carrier-replay-v1' -or $capture.contextFingerprint -notmatch '^[a-f0-9]{64}$' -or
    $capture.iterations -isnot [long] -and $capture.iterations -isnot [int]){throw 'Invalid captured replay manifest.'}
 if($capture.iterations -lt 1 -or $capture.iterations -gt 20){throw 'Captured iterations exceed the small-profile bound.'}
 $context=Join-Path (Join-Path ([IO.Directory]::GetParent($directory).FullName) '.contexts') ($capture.contextFingerprint+'.zip')
 if((Get-Item -LiteralPath $context).Length -gt 8MB){throw 'Captured context exceeds the small-profile bound.'}
 $hashes=Input-Hashes $directory $context
 if($hashes.context -ne $capture.contextDigest){throw 'Captured context checksum mismatch.'}
 foreach($name in @('carrier.xml','types.xml')){
  if($hashes[$name] -ne $capture.payloadDigests.$name){throw ('Captured payload checksum mismatch: '+$name)}
 }
 $archive=[IO.Compression.ZipFile]::OpenRead($context)
 try {
  if($archive.Entries.Count -ne 3 -or @($archive.Entries.FullName | Sort-Object) -join ',' -ne 'context.json,network.xml,snapshot.bin'){throw 'Unexpected context archive layout.'}
  if(($archive.Entries.Length | Measure-Object -Sum).Sum -gt 32MB){throw 'Expanded context exceeds the small-profile bound.'}
  $entry=$archive.GetEntry('context.json')
  if($entry.Length -gt 1MB){throw 'Context configuration exceeds the small-profile bound.'}
  $reader=[IO.StreamReader]::new($entry.Open())
  try {$metadata=$reader.ReadToEnd() | ConvertFrom-Json} finally {$reader.Dispose()}
  if($metadata.config.solverThreads -ne 1){throw 'Captured internal solver threads exceed the serial-profile bound.'}
  # Native adaptive policy overrides manifest.iterations; gate and certify that same effective ceiling.
  $effectiveIterations=$capture.iterations
  if($null -ne $metadata.config.adaptive){
   $policy=$metadata.config.adaptive
   foreach($value in @($policy.minimumIterations,$policy.maximumIterations,$policy.plateauIterations)){
    if(($value -isnot [long] -and $value -isnot [int]) -or $value -lt 1){throw 'Invalid captured adaptive iteration policy.'}
   }
   if($policy.minimumIterations -gt $policy.maximumIterations){throw 'Invalid captured adaptive iteration policy.'}
   $effectiveIterations=$policy.maximumIterations
  }
  if($effectiveIterations -gt 20){throw 'Effective captured iterations exceed the small-profile bound.'}
 } finally {$archive.Dispose()}
 $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Ignore;$settings.XmlResolver=$null;$settings.MaxCharactersInDocument=1MB
 $reader=[Xml.XmlReader]::Create((Join-Path $directory 'carrier.xml'),$settings)
 $jobs=0
 try {while($reader.Read()){if($reader.NodeType -eq [Xml.XmlNodeType]::Element -and $reader.LocalName -in @('service','shipment')){$jobs++}}}finally{$reader.Dispose()}
 if($jobs -lt 1 -or $jobs -gt 128){throw 'Captured jobs exceed the small-profile bound.'}
 return [pscustomobject]@{directory=$directory;context=$context;carrierId=$capture.carrierId;contextFingerprint=$capture.contextFingerprint;inputFingerprint=$capture.inputFingerprint;requestedIterations=$capture.iterations;effectiveIterations=$effectiveIterations;jobs=$jobs;hashes=$hashes}
}
function Assert-UnchangedInputs($case) {
 $now=Input-Hashes $case.directory $case.context
 foreach($key in $case.hashes.Keys){if($now[$key] -ne $case.hashes[$key]){throw ('Captured input changed during profiling: '+$key)}}
 if((Get-FileHash -LiteralPath $jar).Hash -ne $manifest.jarSha256){throw 'Package changed during profiling.'}
}
function Run-OwnJava([string]$name,[string]$executable,[string[]]$arguments) {
 $selection=Get-HagridVerificationResources -ResourceMode Conservative
 if(!$selection.Eligible){$manifest.deferredSelection=$selection;Save-Manifest;throw $selection.Reason}
 $info=[Diagnostics.ProcessStartInfo]::new((Join-Path $JavaHome ('bin/'+$executable)))
 $info.WorkingDirectory=$repo
 foreach($key in @('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS')){$info.Environment.Remove($key) | Out-Null}
 foreach($argument in $arguments){$info.ArgumentList.Add($argument)}
 $result=Invoke-HagridGuardedProcess -StartInfo $info -HeadroomBytes ($selection.HeadroomMiB*1MB) -MaximumTotalCpuPercent 85 -CpuPressureGraceSeconds 5 -StdoutPath (Join-Path $output ($name+'.log')) -StderrPath (Join-Path $output ($name+'.stderr.log'))
 $manifest.observations+=@{name=$name;selection=$selection;run=($result | Select-Object ExitCode,ResourceDeferred,Reason,MinimumAvailableBytes,MaximumObservedCpuPercent,ObservedWorkingSetBytes)}
 Save-Manifest
 if($result.ResourceDeferred){throw $result.Reason}
 if($result.ExitCode -ne 0){Get-Content (Join-Path $output ($name+'.stderr.log')) -Tail 10;throw ('Own bounded JVM failed: '+$name)}
 return $result.Stdout
}
Save-Manifest
try {
 if($CaseDirectories.Count -lt 1 -or $CaseDirectories.Count -gt 4){throw 'One to four captured replay cases required.'}
 $paths=@($CaseDirectories | ForEach-Object {[IO.Path]::GetFullPath($_)})
 if(@($paths | Sort-Object -Unique).Count -ne $paths.Count){throw 'Duplicate replay case directory.'}
 foreach($path in $paths){$manifest.cases+=Read-BoundedCase $path}
 Save-Manifest
 $classes=Join-Path $output 'classes';[IO.Directory]::CreateDirectory($classes) | Out-Null
 Run-OwnJava 'compile' 'javac.exe' @('-J-Xms32m','-J-Xmx256m','-J-XX:ActiveProcessorCount=1','-proc:none','-cp',$jar,'-d',$classes,$agent,$probe) | Out-Null
 $agentJar=Join-Path $output 'sizing-agent.jar'
 $archive=[IO.Compression.ZipArchive]::new([IO.File]::Open($agentJar,[IO.FileMode]::CreateNew),[IO.Compression.ZipArchiveMode]::Create)
 try {
  $entry=$archive.CreateEntry('META-INF/MANIFEST.MF');$writer=[IO.StreamWriter]::new($entry.Open(),$encoding)
  try {$writer.WriteLine('Manifest-Version: 1.0');$writer.WriteLine('Premain-Class: hagrid.core.routing.benchmark.RetainedGraphAgent');$writer.WriteLine()}finally{$writer.Dispose()}
  foreach($file in (Get-ChildItem -LiteralPath $classes -Recurse -File -Filter 'RetainedGraphAgent*.class')){
   $relative=[IO.Path]::GetRelativePath($classes,$file.FullName).Replace('\','/')
   $entry=$archive.CreateEntry($relative);$input=[IO.File]::OpenRead($file.FullName);$stream=$entry.Open()
   try {$input.CopyTo($stream)}finally{$stream.Dispose();$input.Dispose()}
  }
 } finally {$archive.Dispose()}
 $jvm=@('-Xms32m','-Xmx384m','-XX:ActiveProcessorCount=1',('-javaagent:'+$agentJar))
 foreach($package in @('java.lang','java.util','java.util.concurrent','java.util.concurrent.atomic','java.util.concurrent.locks')){$jvm+=('--add-opens=java.base/'+$package+'=ALL-UNNAMED')}
 for($repeat=1;$repeat -le $Repeats;$repeat++){
  for($index=0;$index -lt $manifest.cases.Count;$index++){
   $case=$manifest.cases[$index];Assert-UnchangedInputs $case
   $stdout=Run-OwnJava ('case'+$index+'-repeat'+$repeat) 'java.exe' ($jvm+@('-cp',($classes+';'+$jar),'hagrid.core.routing.benchmark.ReplayMemoryProbe',$case.directory))
   Assert-UnchangedInputs $case
   $rows=@($stdout -split '\r?\n' | Where-Object {$_.StartsWith('REPLAY_MEMORY_JSON ')})
   if($rows.Count -ne 1){throw 'Expected one complete replay observation.'}
   $row=$rows[0].Substring('REPLAY_MEMORY_JSON '.Length) | ConvertFrom-Json
   if($row.carrierId -ne $case.carrierId -or $row.contextFingerprint -ne $case.contextFingerprint -or $row.jobs -ne $case.jobs -or $row.activeProcessors -ne 1 -or $row.iterations -lt 1 -or $row.iterations -gt $case.effectiveIterations -or $row.wholeJvmSampledPeakBytes -le 0 -or $row.returnedResultGraphBytes -le 0){throw 'Replay observation certificate differs.'}
   $row | Add-Member -NotePropertyName requestedIterations -NotePropertyValue $case.requestedIterations
   $row | Add-Member -NotePropertyName effectiveIterations -NotePropertyValue $case.effectiveIterations
   $row | Add-Member -NotePropertyName caseIndex -NotePropertyValue $index
   $row | Add-Member -NotePropertyName repeat -NotePropertyValue $repeat
   $manifest.measurements+=$row;Save-Manifest
   Write-Output ('Measured certified case '+$index+', repeat '+$repeat+', carrier '+$row.carrierId)
  }
 }
 $manifest.measurements | Export-Csv -LiteralPath (Join-Path $output 'measurements.csv') -NoTypeInformation -Encoding utf8
 $manifest.status='MEASURED';Save-Manifest
} catch {$manifest.status='FAILED_OR_DEFERRED';$manifest.reason=$_.Exception.Message;Save-Manifest;throw}
