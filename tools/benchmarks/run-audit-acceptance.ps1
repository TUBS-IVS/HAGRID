param([Parameter(Mandatory)][string]$OutputDirectory,
 [ValidateSet('Auto','Conservative')][string]$ResourceMode='Conservative',
 [switch]$AllowUnavailableRealData)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'ResourceGuard.ps1')
. (Join-Path $PSScriptRoot 'CurrentBuildEvidence.ps1')
. (Join-Path $PSScriptRoot 'AcceptanceReportContract.ps1')
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw 'Use a new evidence directory; existing evidence is never overwritten.'}
New-Item -ItemType Directory -Path $output | Out-Null
$started=[DateTime]::UtcNow
$source=Get-HagridSourceTreeEvidence $repo
$guardSha=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'ResourceGuard.ps1')).Hash
$manifest=[ordered]@{status='PREPARED';startedUtc=$started.ToString('o');sourceTreeSha256=$source.Sha256;
 guardSha256=$guardSha;scope='Current HAGRID test sources; native upstream compatibility is a separate gate.';
 activeProcessors=1;mavenHeapMiB=512;testHeapMiB=384;reserveGiB=6;reason=$null}
function Save-Manifest {
 [IO.File]::WriteAllText((Join-Path $output 'acceptance.json'),($manifest|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
}
[IO.File]::WriteAllText((Join-Path $output 'source-files.txt'),($source.Files -join "`n"),[Text.UTF8Encoding]::new($false))
Save-Manifest
try {
 $resources=Get-HagridVerificationResources -ResourceMode $ResourceMode
 $manifest.resources=$resources;$manifest.resourceMode=$ResourceMode
 $manifest.allowUnavailableRealData=[bool]$AllowUnavailableRealData
 $manifest.activeProcessors=$resources.ActiveProcessors;$manifest.mavenHeapMiB=$resources.MavenHeapMiB;$manifest.testHeapMiB=$resources.TestHeapMiB
 if(!$resources.Eligible){$manifest.status='DEFERRED';$manifest.reason=$resources.Reason;Save-Manifest;Write-Output $resources.Reason;return}
 # Inventory current sources instead of assuming the historical 1466 tests or 175 roots.
 $tests=@(Get-ChildItem -LiteralPath (Join-Path $repo 'hagrid/simulation/src/test/java/hagrid') -Recurse -Filter '*.java' -File |
  Where-Object {[IO.File]::ReadAllText($_.FullName) -match '@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b'} |
  ForEach-Object {$_.BaseName} | Sort-Object -Unique)
 if($tests.Count -eq 0){throw 'No current HAGRID test sources inventoried.'}
 $selection=$tests -join ','
 if($selection.Length -gt 6500){throw 'Current test selector exceeds safe Windows command length; split verification before acceptance.'}
 $manifest.testSourceRoots=$tests.Count;$manifest.selection=$tests
 [IO.File]::WriteAllText((Join-Path $output 'test-sources.txt'),($tests -join "`n"),[Text.UTF8Encoding]::new($false))
 $log=Join-Path $output 'verify.log'
 & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'run-bounded-tests.ps1') -Tests $selection -Log $log -ResourceMode $ResourceMode -MavenPhase verify
 $exit=$LASTEXITCODE;$manifest.exitCode=$exit
 $run=Get-Content -LiteralPath ($log+'.resources.json') -Raw | ConvertFrom-Json
 $manifest.guardObservation=$run
 if($run.Run.ResourceDeferred -or ($null -eq $run.Run -and !$run.Eligible)) {
  $manifest.status='DEFERRED';$manifest.reason='Guard deferred or stopped this verification.';Save-Manifest;return
 }
 if($exit -ne 0 -or $run.Run.ExitCode -ne 0){throw 'Current guarded verification failed. Read verify.log; no verified-build evidence emitted.'}
 if((Get-HagridSourceTreeEvidence $repo).Sha256 -ne $source.Sha256){throw 'Source changed during verification.'}
 $jar=Join-Path $repo 'hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar'
 if(!(Test-Path -LiteralPath $jar) -or (Get-Item -LiteralPath $jar).LastWriteTimeUtc -lt $started){throw 'No freshly packaged current JAR.'}
 $packagedClasses=Get-HagridPackagedClassEvidence $repo $jar -DependencyJars (Get-HagridRuntimeDependencyJars $repo)
 $reports=@(Get-ChildItem -LiteralPath (Join-Path $repo 'hagrid/simulation/target/surefire-reports') -Filter 'TEST-hagrid*.xml' -File |
  Where-Object {$_.LastWriteTimeUtc -ge $started})
 if($reports.Count -lt $tests.Count){throw 'Missing fresh reports for current HAGRID test source inventory.'}
 $totals=@{tests=0;failures=0;errors=0;skipped=0};$reportedRoots=@();$skippedCases=@()
 foreach($report in $reports) {
  [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
  $reportedRoots+=($xml.testsuite.name -split '\.')[-1].Split('$')[0]
  foreach($name in @('tests','failures','errors','skipped')){$totals[$name]+=[int]$xml.testsuite.$name}
  foreach($case in $xml.testsuite.testcase) {
   $skipped=$case.SelectSingleNode('skipped')
   if($null -ne $skipped){$skippedCases+=@([pscustomobject]@{Class=$case.classname;Method=$case.name;Reason=($skipped.message+' '+$skipped.InnerText).Trim()})}
  }
 }
 if(@($tests|Where-Object {$_ -notin $reportedRoots}).Count -ne 0){throw 'Some selected current HAGRID test roots have no fresh report.'}
 $accepted=Confirm-HagridAcceptanceTotals -Totals $totals -SkippedCases $skippedCases -AllowUnavailableRealData:$AllowUnavailableRealData
 $manifest.scope=$accepted.Scope;$manifest.deferredChecks=$accepted.DeferredChecks
 if($null -eq $run.Selection){throw 'Actual bounded JVM resource selection is missing.'}
 $manifest.activeProcessors=$run.Selection.ActiveProcessors;$manifest.mavenHeapMiB=$run.Selection.MavenHeapMiB;$manifest.testHeapMiB=$run.Selection.TestHeapMiB
 $build=[ordered]@{status='VERIFIED';finishedUtc=[DateTime]::UtcNow.ToString('o');sourceTreeSha256=$source.Sha256;
  packagedClasses=$packagedClasses;jarSha256=(Get-FileHash -LiteralPath $jar).Hash;guardSha256=$guardSha;activeProcessors=$manifest.activeProcessors;testHeapMiB=$manifest.testHeapMiB;
  mavenHeapMiB=$manifest.mavenHeapMiB;testTotals=$totals;testSourceRoots=$tests.Count;scope=$manifest.scope;deferredChecks=$accepted.DeferredChecks;
  verifyLog=$log;verifyLogSha256=(Get-FileHash -LiteralPath $log).Hash}
 $manifest.status='VERIFIED';$manifest.build=$build;Save-Manifest
 $evidence=Join-Path $repo '.superpowers/sdd/2026-10-10-hagrid-audit-fixes-und-baustein-refactor/verification/verified-build.json'
 [IO.File]::WriteAllText($evidence,($build|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
 [IO.File]::WriteAllText((Join-Path $output 'verified-build.json'),($build|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
 Write-Output ('Current available HAGRID automated verification passed: '+$totals.tests+' invocations. Native compatibility, package replay and production gates remain separate.')
} catch {
 $manifest.status='FAILED';$manifest.reason=$_.Exception.Message;Save-Manifest;throw
}
