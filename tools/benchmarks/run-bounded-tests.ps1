param(
 [Parameter(Mandatory=$true)][string]$Tests,
 [Parameter(Mandatory=$true)][string]$Log,
 [ValidateSet('Auto','Conservative')][string]$ResourceMode='Auto',
 [ValidateSet('test','verify')][string]$MavenPhase='test'
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repo 'tools/benchmarks/ResourceGuard.ps1')
$logPath=[IO.Path]::GetFullPath($Log)
$resources=Get-HagridVerificationResources -ResourceMode $ResourceMode
Write-Output ('Available memory: {0:N2} GiB, minimum idle CPU {1:N1}%; own JVM processors {2}, heaps {3}/{4} MiB; reserve 6 GiB' -f ($resources.MinimumAvailableBytes/1GB),$resources.MinimumIdleCpuPercent,$resources.ActiveProcessors,$resources.MavenHeapMiB,$resources.TestHeapMiB)
if (!$resources.Eligible) {
 [IO.File]::WriteAllText(($logPath+'.resources.json'),($resources | ConvertTo-Json))
 throw $resources.Reason
}
if ($Tests -notmatch '^[A-Za-z0-9_.,$#*]+$') { throw 'Unsupported test selector; only class/method patterns are accepted.' }
$javaHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot'
$maven='C:/Users/bienzeisler/.m2/wrapper/dists/apache-maven-3.9.8/8fbe43aa8cab8504a064f61871f5c160/bin/mvn.cmd'
if (!(Test-Path -LiteralPath $maven)) { throw 'Existing Maven launcher missing.' }
[Diagnostics.Process]::GetCurrentProcess().PriorityClass=[Diagnostics.ProcessPriorityClass]::Idle
$info=[Diagnostics.ProcessStartInfo]::new($env:ComSpec)
$info.WorkingDirectory=$repo
$info.Environment['JAVA_HOME']=$javaHome
$info.Environment['PATH']=$javaHome+'/bin;'+$env:PATH
$info.Environment['MAVEN_OPTS']=('-Xms64m -Xmx{0}m -XX:ActiveProcessorCount={1}' -f $resources.MavenHeapMiB,$resources.ActiveProcessors)
$testJvm=('-Xms64m -Xmx{0}m -XX:ActiveProcessorCount={1}' -f $resources.TestHeapMiB,$resources.ActiveProcessors)
$info.Arguments='/d /s /c ""'+$maven+'" -pl hagrid/simulation -am '+$MavenPhase+' "-Dtest='+$Tests+'" -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false "-DargLine='+$testJvm+'""'
$result=Invoke-HagridGuardedProcess -StartInfo $info -HeadroomBytes ($resources.HeadroomMiB*1MB) -MaximumTotalCpuPercent $resources.MaximumTotalCpuPercent -CpuPressureGraceSeconds $resources.CpuPressureGraceSeconds -StdoutPath $logPath -StderrPath ($logPath+'.stderr.log')
$metadata=[pscustomobject]@{Selection=$resources; Run=($result | Select-Object ProcessId,ExitCode,ResourceDeferred,Reason,MinimumAvailableBytes,MaximumObservedCpuPercent,ObservedWorkingSetBytes); SamplingMilliseconds=200; CpuSamplingMilliseconds=1000; WorkingSetInterpretation='Launcher/root sample only; not summed JVM/tree RAM.'}
[IO.File]::WriteAllText(($logPath+'.resources.json'),($metadata | ConvertTo-Json -Depth 4))
if ($result.Stderr) { [IO.File]::AppendAllText($logPath,[Environment]::NewLine+$result.Stderr) }
Get-Content -LiteralPath $logPath -Tail 30
if ($result.ResourceDeferred) { throw $result.Reason }
exit $result.ExitCode
