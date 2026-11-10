. (Join-Path $PSScriptRoot 'PackagedClassEvidence.ps1')
# Read-only identity helpers; callers write evidence only after current guarded verify succeeds.
function Get-HagridSourceTreeEvidence([string]$Repository) {
 $paths=[Collections.Generic.List[string]]::new()
 $directories=@('config','tools/benchmarks','.mvn','external/freight/src','external/matsim-libs/contribs/freight/src','external/matsim-libs/contribs/freight/test/input','external/matsim-libs/examples/scenarios/logistics-2regions')+@(Get-ChildItem -LiteralPath (Join-Path $Repository 'hagrid') -Directory | ForEach-Object {'hagrid/'+$_.Name+'/src'})
 foreach($directory in $directories) {
  $root=Join-Path $Repository $directory
  if(Test-Path -LiteralPath $root) {
   foreach($file in Get-ChildItem -LiteralPath $root -Recurse -File) {
    $relative=[IO.Path]::GetRelativePath($Repository,$file.FullName).Replace('\','/')
    if($relative -like '*/results/*' -or $relative -like '*/__pycache__/*'){continue}
    if($directory -ne 'tools/benchmarks' -or $file.Extension -in @('.java','.xml','.properties','.ps1','.py')){$paths.Add($relative)}
   }
  }
 }
 $nativePom=Join-Path $Repository 'external/freight/pom.xml'
 if(Test-Path -LiteralPath $nativePom){$paths.Add('external/freight/pom.xml')}
 foreach($file in Get-ChildItem -LiteralPath $Repository -Filter 'pom.xml' -File){$paths.Add($file.Name)}
 foreach($file in Get-ChildItem -LiteralPath (Join-Path $Repository 'hagrid') -Recurse -Filter 'pom.xml' -File){$paths.Add([IO.Path]::GetRelativePath($Repository,$file.FullName).Replace('\','/'))}
 $lines=@($paths | Sort-Object -Unique | ForEach-Object {$_+' '+(Get-FileHash -LiteralPath (Join-Path $Repository $_) -Algorithm SHA256).Hash})
 $bytes=[Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
 [pscustomobject]@{Sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes));Files=$lines}
}
function Get-HagridVerifiedBuild([string]$Repository,[string]$EvidenceFile) {
 if(!(Test-Path -LiteralPath $EvidenceFile)){throw 'Current verified-build evidence missing; run protected acceptance/verify first.'}
 $build=Get-Content -LiteralPath $EvidenceFile -Raw | ConvertFrom-Json
 $jar=Join-Path $Repository 'hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar'
 $source=Get-HagridSourceTreeEvidence $Repository
 $guard=Join-Path $Repository 'tools/benchmarks/ResourceGuard.ps1'
 if($build.status -ne 'VERIFIED' -or $build.sourceTreeSha256 -ne $source.Sha256 -or
    $build.jarSha256 -ne (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -or
    $build.guardSha256 -ne (Get-FileHash -LiteralPath $guard -Algorithm SHA256).Hash) {
  throw 'Build evidence does not match current source, JAR and guard; no benchmark of a stale package.'
 }
 $classes=Get-HagridPackagedClassEvidence $Repository $jar -DependencyJars (Get-HagridRuntimeDependencyJars $Repository)
 if($null -eq $build.packagedClasses -or $build.packagedClasses.Status -ne 'MATCHED' -or
    $build.packagedClasses.ClassManifestSha256 -cne $classes.ClassManifestSha256) {
  throw 'Build evidence lacks matching packaged reactor bytecode; run protected acceptance again.'
 }
 return $build
}
