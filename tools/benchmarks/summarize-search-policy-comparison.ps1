param([Parameter(Mandatory)][string]$InputDirectory)
$ErrorActionPreference='Stop'
$culture=[Globalization.CultureInfo]::InvariantCulture
function Number($value){[double]::Parse([string]$value,$culture)}
function Median($values){$a=@($values|Sort-Object);if(!$a.Count){throw 'Empty sample'};$m=[int][Math]::Floor($a.Count/2);if($a.Count%2){$a[$m]}else{($a[$m-1]+$a[$m])/2}}
$directory=[IO.Path]::GetFullPath($InputDirectory)
$manifest=Get-Content -LiteralPath (Join-Path $directory 'manifest.json') -Raw|ConvertFrom-Json
if($manifest.status -ne 'MEASURED' -or @($manifest.seeds).Count -lt 5 -or
 @($manifest.seeds|Select-Object -Unique).Count -ne @($manifest.seeds).Count -or
 $manifest.groupCount -ne 5 -or $manifest.warmupGroups -ne 2 -or $manifest.carriersPerGroup -ne 4 -or
 $manifest.referenceIterations -ne 10 -or $manifest.adaptiveMinimum -ne 5 -or $manifest.adaptiveMaximum -ne 10 -or
 $manifest.adaptivePlateau -ne 3 -or @($manifest.policies).Count -ne 3 -or
 (@($manifest.policies|Sort-Object) -join ',') -ne 'adaptive,fixed,warm'){throw 'Expected completed five-seed fixed/warm/adaptive comparison'}
$summaryPath=Join-Path $directory 'paired-summary.json'
if(Test-Path -LiteralPath $summaryPath){throw 'Existing summary is preserved; use a new evidence directory.'}
$seedProtocol=$manifest.seedProtocol
if($null -eq $seedProtocol){$seedProtocol='legacy-overlapping-v1'}
if($seedProtocol -notin @('legacy-overlapping-v1','paired-distinct-v2')){throw 'Unknown seed protocol.'}
if($seedProtocol -eq 'paired-distinct-v2' -and $manifest.seedProtocolSha256 -notmatch '^[a-fA-F0-9]{64}$'){throw 'Missing seed protocol source certificate.'}
$carrierSeeds=[Collections.Generic.HashSet[long]]::new()
foreach($rootSeed in $manifest.seeds){
 $root=[long]$rootSeed
 if($root -lt [int]::MinValue -or $root -gt [int]::MaxValue){throw 'Expected signed-int root seeds.'}
 foreach($carrierIndex in 0..3){
  $caseSeed=if($seedProtocol -eq 'paired-distinct-v2'){8L*$root+2L*$carrierIndex}else{$root+$carrierIndex}
  $carrierSeeds.Add($caseSeed)|Out-Null
 }
}
$rows=@(Import-Csv -LiteralPath (Join-Path $directory 'measurements.csv'))
if($rows.Count -ne @($manifest.seeds).Count*2*3*5*4){throw 'Incomplete comparison rows'}
$unique=[Collections.Generic.HashSet[string]]::new()
foreach($r in $rows){
 if($r.marker -ne 'SEARCH' -or $r.kind -notin @('services','shipments') -or $r.policy -notin $manifest.policies -or
 [int]$r.jobs -ne $manifest.jobs -or [long]$r.seed -notin $manifest.seeds -or
 [int]$r.group -lt 0 -or [int]$r.group -ge 5 -or [int]$r.served -ne $manifest.jobs -or
 $r.carrier -notmatch ('^g'+$r.group+'c[0-3]$') -or [int]$r.vehicles -ne 1){throw 'Invalid benchmark shape/completeness'}
 if($seedProtocol -eq 'paired-distinct-v2'){
  if($r.caseSeed -notmatch '^-?[0-9]+$' -or $r.previousSeed -notmatch '^-?[0-9]+$'){throw 'Missing seed protocol row.'}
  $expectedSeed=8L*[long]$r.seed+2L*[int]::Parse($r.carrier.Substring($r.carrier.Length-1))
  if([long]$r.caseSeed -ne $expectedSeed -or [long]$r.previousSeed -ne $expectedSeed+1L){throw 'Carrier/reference seed certificate differs.'}
 }
 $key=$r.kind+':'+$r.seed+':'+$r.group+':'+$r.carrier+':'+$r.policy
 if(!$unique.Add($key)){throw 'Duplicate benchmark row'}
 if([int]$r.iterations -gt 10 -or [int]$r.iterations -lt $(if($r.policy -eq 'adaptive'){5}else{10})){throw 'Unmatched actual search budget'}
 if(($r.policy -eq 'warm' -and $r.warmStatus -ne 'ACCEPTED') -or ($r.policy -ne 'warm' -and $r.warmStatus -ne 'DISABLED')){throw 'Warm search acceptance differs'}
 foreach($field in @('previousCost','candidateCost','selectedCost','preparationSeconds','searchSeconds','finalRoutingSeconds','validationSeconds','optimizationSeconds','selectionSeconds','pipelineSeconds','solverThreadAllocatedBytes')){
  $n=Number $r.$field;if(![double]::IsFinite($n) -or $n -lt 0){throw ('Invalid '+$field)}
 }
 foreach($field in @('previousCost','candidateCost','selectedCost')){if((Number $r.$field) -le 0){throw 'Expected positive fixture costs'}}
 if((Number $r.previousCost) -le 0 -or (Number $r.pipelineSeconds) -le 0 -or (Number $r.selectedCost) -gt (Number $r.previousCost)+1e-6){throw 'Invalid or degraded published quality'}
 if([Math]::Abs((Number $r.pipelineSeconds)-(Number $r.optimizationSeconds)-(Number $r.selectionSeconds)) -gt 1e-7){throw 'Pipeline phases disagree'}
 foreach($field in @('inputHash','previousHash')){if($r.$field -notmatch '^[a-f0-9]{64}$'){throw ('Invalid '+$field)}}
}
foreach($group in ($rows|Group-Object kind,seed,group,carrier)){
 $items=@($group.Group)
 if($items.Count -ne 3 -or @($items.inputHash|Select-Object -Unique).Count -ne 1 -or
 @($items.previousHash|Select-Object -Unique).Count -ne 1 -or @($items.previousCost|Select-Object -Unique).Count -ne 1){throw 'Unmatched canonical inputs or previous-plan certificate'}
}
$pairs=@()
foreach($kind in @('services','shipments')){foreach($seed in $manifest.seeds){
 $values=@{}
 foreach($policy in @('fixed','warm','adaptive')){
  $groups=@()
  foreach($index in 2..4){
   $g=@($rows|Where-Object{$_.kind -eq $kind -and [long]$_.seed -eq $seed -and $_.policy -eq $policy -and [int]$_.group -eq $index})
   if($g.Count -ne 4){throw 'Missing measured carrier group'}
   $v=[ordered]@{}
   foreach($field in @('candidateCost','selectedCost','optimizationSeconds','searchSeconds','preparationSeconds','selectionSeconds','pipelineSeconds','solverThreadAllocatedBytes','iterations')){
    $v[$field]=($g|ForEach-Object{Number $_.$field}|Measure-Object -Sum).Sum
   }
   $groups+= [pscustomobject]$v
  }
  $medians=[ordered]@{}
  foreach($field in @('candidateCost','selectedCost','optimizationSeconds','searchSeconds','preparationSeconds','selectionSeconds','pipelineSeconds','solverThreadAllocatedBytes','iterations')){
   $medians[$field]=Median @($groups|ForEach-Object{$_.$field})
  }
  $values[$policy]=[pscustomobject]$medians
 }
 foreach($policy in @('warm','adaptive')){
  $b=$values.fixed;$c=$values[$policy]
  $pairs+= [pscustomobject]@{kind=$kind;seed=$seed;policy=$policy;baseline=$b;candidate=$c;
   timeRatio=$c.pipelineSeconds/$b.pipelineSeconds;candidateCostRatio=$c.candidateCost/$b.candidateCost;
   selectedCostRatio=$c.selectedCost/$b.selectedCost;allocationRatio=$c.solverThreadAllocatedBytes/$b.solverThreadAllocatedBytes;
   iterationRatio=$c.iterations/$b.iterations}
 }
}}
$cases=@()
foreach($group in ($pairs|Group-Object kind,policy)){
 $g=@($group.Group)
 $carrierRatios=@()
 foreach($r in ($rows|Where-Object{$_.kind -eq $g[0].kind -and $_.policy -eq $g[0].policy -and [int]$_.group -ge 2})){
  $reference=@($rows|Where-Object{$_.kind -eq $r.kind -and $_.policy -eq 'fixed' -and $_.seed -eq $r.seed -and $_.carrier -eq $r.carrier})[0]
  $carrierRatios+= [pscustomobject]@{selected=(Number $r.selectedCost)/(Number $reference.selectedCost);candidate=(Number $r.candidateCost)/(Number $reference.candidateCost)}
 }
 $cases+= [pscustomobject]@{kind=$g[0].kind;policy=$g[0].policy;seeds=$g.Count;
  measuredCarrierPairs=$carrierRatios.Count;worstSelectedCarrierCostRatio=($carrierRatios.selected|Measure-Object -Maximum).Maximum;
  worseSelectedCarrierPairs=@($carrierRatios|Where-Object{$_.selected -gt 1+1e-9}).Count;
  betterSelectedCarrierPairs=@($carrierRatios|Where-Object{$_.selected -lt 1-1e-9}).Count;
  worseCandidateCarrierPairs=@($carrierRatios|Where-Object{$_.candidate -gt 1+1e-9}).Count;
  baselineSeconds=Median @($g|ForEach-Object{$_.baseline.pipelineSeconds});candidateSeconds=Median @($g|ForEach-Object{$_.candidate.pipelineSeconds});
  medianPairedTimeRatio=Median @($g.timeRatio);minimumPairedTimeRatio=($g.timeRatio|Measure-Object -Minimum).Minimum;maximumPairedTimeRatio=($g.timeRatio|Measure-Object -Maximum).Maximum;
  medianCandidateCostRatio=Median @($g.candidateCostRatio);medianSelectedCostRatio=Median @($g.selectedCostRatio);
  worstSelectedCostRatio=($g.selectedCostRatio|Measure-Object -Maximum).Maximum;
  medianAllocationRatio=Median @($g.allocationRatio);medianIterationRatio=Median @($g.iterationRatio);
  slowerSeeds=@($g|Where-Object{$_.timeRatio -gt 1}).Count;worseSelectedCostSeeds=@($g|Where-Object{$_.selectedCostRatio -gt 1+1e-9}).Count}
}
$result=[ordered]@{seedProtocol=$seedProtocol;uniqueCarrierSeedsPerKind=$carrierSeeds.Count;jarSha256=$manifest.jarSha256;probeSha256=$manifest.probeSha256;seeds=$manifest.seeds;jobs=$manifest.jobs;
 interpretation='Per-seed median sums of four carriers over three groups after two warmups; paired ratios then median over seeds. Candidate and independently selected published quality are separate. Adaptive variant changes actual search work and is not an equal-iteration speedup. Small exact-cache graph; all production defaults unchanged. Distinct seeds do not establish statistical independence; groups repeat inputs. Legacy overlapping seed domains are disclosed separately.';cases=$cases;pairs=$pairs}
. (Join-Path $PSScriptRoot 'BenchmarkArtifacts.ps1')
Publish-HagridBenchmarkJson -Path $summaryPath -Value $result
$cases|Format-Table kind,policy,seeds,medianPairedTimeRatio,medianSelectedCostRatio,medianIterationRatio,slowerSeeds,worseSelectedCostSeeds