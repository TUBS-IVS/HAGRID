param([Parameter(Mandatory)][string]$InputDirectory,[switch]$CompareBuilds)
$ErrorActionPreference='Stop'
$culture=[Globalization.CultureInfo]::InvariantCulture
function Number($value) { [double]::Parse([string]$value,$culture) }
function Median($values) {
 $sorted=@($values | Sort-Object)
 if(!$sorted.Count){throw 'Empty sample'}
 $middle=[int][Math]::Floor($sorted.Count/2)
 if($sorted.Count%2){return $sorted[$middle]}
 return ($sorted[$middle-1]+$sorted[$middle])/2
}
$directory=[IO.Path]::GetFullPath($InputDirectory)
$manifest=Get-Content -LiteralPath (Join-Path $directory 'manifest.json') -Raw | ConvertFrom-Json
if($manifest.status -ne 'MEASURED'){throw 'A completed comparison is required'}
if(@($manifest.seeds).Count -lt 5 -or $manifest.warmupGroups -ne 2){
 throw 'Expected five or more paired seeds and two warmup groups'
}
if($CompareBuilds){
 if(@($manifest.builds).Count -ne 2 -or @($manifest.builds.sha256 | Select-Object -Unique).Count -ne 2 -or @($manifest.zoneVariants).Count -ne 1 -or @($manifest.zoneVariants)[0] -ne 'true'){
  throw 'Expected two distinct package builds with the same enabled zone policy'
 }
 $axis='build';$variants=@('reference','current')
} else {
 if(@($manifest.zoneVariants).Count -ne 2 -or @($manifest.builds).Count -gt 1){throw 'Expected exact/zone policies in one build'}
 $axis='zones';$variants=@('false','true')
}
$rows=@(Import-Csv -LiteralPath (Join-Path $directory 'measurements.csv'))
$hasGraphs=$rows.Count -gt 0 -and $rows[0].PSObject.Properties.Name -contains 'sharedContextGraphBytes'
if($hasGraphs -and $rows[0].PSObject.Properties.Name -notcontains 'canonicalInputsAndPublishedPlansGraphBytes'){throw 'Incomplete reachable-graph columns'}
$expected=@($manifest.seeds).Count*@($manifest.jobCounts).Count*2*2*$manifest.groupCount
if($rows.Count -ne $expected){throw 'Incomplete comparison rows'}
$pairs=@()
foreach($kind in @('services','shipments')) {foreach($jobs in $manifest.jobCounts) {foreach($seed in $manifest.seeds) {
 $values=@{}
 foreach($variant in $variants) {
  $all=@($rows | Where-Object {$_.kind -eq $kind -and [int]$_.jobs -eq $jobs -and [long]$_.seed -eq $seed -and $_.($axis) -eq $variant})
  if($all.Count -ne $manifest.groupCount -or @($all.group | Select-Object -Unique).Count -ne $manifest.groupCount){throw 'Missing or duplicate group'}
  foreach($r in $all){
   if([int]$r.iterations -ne 10 -or [int]$r.served -ne $jobs*4 -or [int]$r.carriers -ne 4){throw 'Unmatched budget/completeness'}
   if($hasGraphs){
    foreach($field in @('sharedContextGraphBytes','canonicalInputsAndPublishedPlansGraphBytes')){
     $n=Number $r.$field
     if(![double]::IsFinite($n) -or $n -le 0){throw ('Invalid '+$field)}
    }
   }
   foreach($field in @('totalSeconds','finalCost','preparationSeconds','searchSeconds','finalRoutingSeconds','validationSeconds')){
    $n=Number $r.$field
    if(![double]::IsFinite($n) -or $n -lt 0){throw ('Invalid '+$field)}
   }
  }
  $measured=@($all | Where-Object {[int]$_.group -ge $manifest.warmupGroups})
  $values[$variant]=[ordered]@{
   totalSeconds=Median @($measured | ForEach-Object {Number $_.totalSeconds})
   finalCost=Median @($measured | ForEach-Object {Number $_.finalCost})
   searchSeconds=Median @($measured | ForEach-Object {Number $_.searchSeconds})
   allocatedBytes=Median @($measured | ForEach-Object {Number $_.solverThreadAllocatedBytes})
   vehicles=Median @($measured | ForEach-Object {Number $_.vehicles})
   maximumSampledWholeJvmHeapBytes=($all | ForEach-Object {Number $_.wholeJvmSampledPeakBytes} | Measure-Object -Maximum).Maximum
   medianSharedContextGraphBytes=$(if($hasGraphs){Median @($measured | ForEach-Object {Number $_.sharedContextGraphBytes})}else{$null})
   medianCanonicalInputsAndPublishedPlansGraphBytes=$(if($hasGraphs){Median @($measured | ForEach-Object {Number $_.canonicalInputsAndPublishedPlansGraphBytes})}else{$null})
   finalCacheEntries=[long]($all | Sort-Object {[int]$_.group} | Select-Object -Last 1).cacheEntries
  }
 }
 $baseline=$values[$variants[0]];$candidate=$values[$variants[1]]
 if($baseline.totalSeconds -le 0 -or $baseline.finalCost -le 0){throw 'Undefined paired ratio'}
 if($CompareBuilds){
  foreach($index in 0..($manifest.groupCount-1)){
   $before=@($rows | Where-Object {$_.kind -eq $kind -and [int]$_.jobs -eq $jobs -and [long]$_.seed -eq $seed -and $_.build -eq 'reference' -and [int]$_.group -eq $index})[0]
   $after=@($rows | Where-Object {$_.kind -eq $kind -and [int]$_.jobs -eq $jobs -and [long]$_.seed -eq $seed -and $_.build -eq 'current' -and [int]$_.group -eq $index})[0]
   if((Number $before.finalCost) -ne (Number $after.finalCost) -or [int]$before.vehicles -ne [int]$after.vehicles){
    throw 'Pure structural refactor changed validated final costs or vehicles'
   }
  }
 }
 $pairs+= [pscustomobject]@{kind=$kind;jobs=$jobs;seed=$seed;baseline=$baseline;candidate=$candidate;
  timeRatio=$candidate.totalSeconds/$baseline.totalSeconds;
  costRatio=$candidate.finalCost/$baseline.finalCost;
  allocationRatio=$candidate.allocatedBytes/$baseline.allocatedBytes}
}}}
$cases=@()
foreach($group in ($pairs | Group-Object kind,jobs)){
 $items=@($group.Group)
 $cases += [pscustomobject]@{
  kind=$items[0].kind;jobs=$items[0].jobs;seeds=$items.Count
  medianBaselineSeconds=Median @($items | ForEach-Object {$_.baseline.totalSeconds})
  medianCandidateSeconds=Median @($items | ForEach-Object {$_.candidate.totalSeconds})
  medianPairedTimeRatio=Median @($items.timeRatio)
  minimumPairedTimeRatio=($items.timeRatio | Measure-Object -Minimum).Minimum
  maximumPairedTimeRatio=($items.timeRatio | Measure-Object -Maximum).Maximum
  medianPairedCostRatio=Median @($items.costRatio)
  minimumPairedCostRatio=($items.costRatio | Measure-Object -Minimum).Minimum
  maximumPairedCostRatio=($items.costRatio | Measure-Object -Maximum).Maximum
  slowerCandidateSeeds=@($items | Where-Object {$_.timeRatio -gt 1}).Count
  worseCandidateCostSeeds=@($items | Where-Object {$_.costRatio -gt 1}).Count
  medianBaselineSharedContextGraphBytes=$(if($hasGraphs){Median @($items | ForEach-Object {$_.baseline.medianSharedContextGraphBytes})}else{$null})
  medianCandidateSharedContextGraphBytes=$(if($hasGraphs){Median @($items | ForEach-Object {$_.candidate.medianSharedContextGraphBytes})}else{$null})
  medianPairedSharedContextGraphRatio=$(if($hasGraphs){Median @($items | ForEach-Object {$_.candidate.medianSharedContextGraphBytes/$_.baseline.medianSharedContextGraphBytes})}else{$null})
  medianPairedAllocationRatio=Median @($items.allocationRatio)
 }
}
$result=[ordered]@{comparisonAxis=$axis;variants=$variants;builds=$manifest.builds;jarSha256=$manifest.jarSha256;probeSha256=$manifest.probeSha256;
 interpretation='Per-seed medians of three measured groups after two warmup groups; then paired ratios over seeds. Lower time/cost ratios are better. Fixed small grid, profiling overhead and background load; no production extrapolation.';
 cases=$cases;pairs=$pairs}
[IO.File]::WriteAllText((Join-Path $directory 'paired-summary.json'),($result | ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))
$cases | Format-Table kind,jobs,seeds,medianBaselineSeconds,medianCandidateSeconds,medianPairedTimeRatio,medianPairedCostRatio,slowerCandidateSeeds,worseCandidateCostSeeds