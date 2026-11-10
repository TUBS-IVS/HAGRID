# Read-only package fidelity check: tests and the executable must use identical reactor bytecode.
function Get-HagridRuntimeDependencyJars([string]$Repository) {
 $path=Join-Path $Repository 'hagrid/simulation/target/runtime-classpath.txt'
 if(!(Test-Path -LiteralPath $path)){throw 'Resolved runtime classpath missing; rebuild the package.'}
 $paths=@([IO.File]::ReadAllText($path).Trim().Split([IO.Path]::PathSeparator,[StringSplitOptions]::RemoveEmptyEntries))
 if($paths.Count -eq 0){throw 'Resolved runtime classpath is empty.'}
 return $paths
}
function Get-HagridPackagedClassEvidence([string]$Repository, [string]$JarPath, [string[]]$DependencyJars=@()) {
 $expected=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
 $moduleCounts=[ordered]@{}
 foreach($module in @('hagrid/simulation','external/freight')) {
  $root=Join-Path $Repository ($module+'/target/classes')
  if(!(Test-Path -LiteralPath $root -PathType Container)){throw ('Compiled reactor classes missing: '+$module)}
  $files=@(Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.class')
  if($files.Count -eq 0){throw ('No compiled reactor classes: '+$module)}
  $moduleCounts[$module]=$files.Count
  foreach($file in $files) {
   $name=[IO.Path]::GetRelativePath($root,$file.FullName).Replace([IO.Path]::DirectorySeparatorChar,[char]'/')
   if($expected.ContainsKey($name)){throw ('Conflicting reactor class: '+$name)}
   $expected.Add($name,(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash)
  }
 }
 $matched=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
 $shared=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
 $archive=[IO.Compression.ZipFile]::OpenRead($JarPath)
 try {
  foreach($entry in $archive.Entries) {
   if(!$expected.ContainsKey($entry.FullName)){
    # Shared freight/jsprit namespaces: compiled backports must match the reactor; other classes must match declared runtime dependencies.
    if($entry.FullName.EndsWith('.class',[StringComparison]::Ordinal) -and
       ($entry.FullName.StartsWith('hagrid/',[StringComparison]::Ordinal) -or
        $entry.FullName.StartsWith('org/matsim/freight/',[StringComparison]::Ordinal) -or
        $entry.FullName.StartsWith('com/graphhopper/jsprit/',[StringComparison]::Ordinal))){
     $stream=$entry.Open()
     try {$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))} finally {$stream.Dispose()}
     if(!$shared.TryAdd($entry.FullName,$hash)){throw ('Duplicate packaged reactor class: '+$entry.FullName)}
    }
    continue
   }
   if(!$matched.Add($entry.FullName)){throw ('Duplicate packaged reactor class: '+$entry.FullName)}
   $stream=$entry.Open()
   try {$actual=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))} finally {$stream.Dispose()}
   if($actual -cne $expected[$entry.FullName]){throw ('Stale packaged reactor class: '+$entry.FullName)}
  }
 } finally {$archive.Dispose()}
 $approved=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
 foreach($dependency in $DependencyJars) {
  # Reactor artifacts must never authorize their own obsolete extra classes.
  if([IO.Path]::GetFileName($dependency) -in @('freight-1.0-SNAPSHOT.jar','hagrid-1.0-SNAPSHOT.jar')){continue}
  if(!(Test-Path -LiteralPath $dependency -PathType Leaf)){throw ('Runtime dependency missing: '+$dependency)}
  if($approved.Count -eq $shared.Count){continue}
  $zip=[IO.Compression.ZipFile]::OpenRead($dependency)
  try {foreach($name in $shared.Keys) {
   if($approved.Contains($name)){continue}
   $entry=$zip.GetEntry($name)
   if($null -eq $entry){continue}
   $stream=$entry.Open()
   try {$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))} finally {$stream.Dispose()}
   if($hash -ceq $shared[$name]){$approved.Add($name)|Out-Null}
  }} finally {$zip.Dispose()}
 }
 foreach($name in $shared.Keys){if(!$approved.Contains($name)){throw ('Unexpected packaged reactor class: '+$name)}}
 foreach($name in $expected.Keys){if(!$matched.Contains($name)){throw ('Missing packaged reactor class: '+$name)}}
 $names=[string[]]@($expected.Keys)
 [Array]::Sort($names,[StringComparer]::Ordinal)
 $lines=@($names|ForEach-Object {$_+' '+$expected[$_]})
 $sharedNames=[string[]]@($shared.Keys);[Array]::Sort($sharedNames,[StringComparer]::Ordinal)
 $lines+=@($sharedNames|ForEach-Object {'dependency '+$_+' '+$shared[$_]})
 $bytes=[Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
 [pscustomobject]@{Status='MATCHED';Classes=$expected.Count;Modules=$moduleCounts;SharedDependencyClasses=$sharedNames;
  ClassManifestSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))}
}
