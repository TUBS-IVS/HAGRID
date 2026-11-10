$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../CurrentBuildEvidence.ps1')
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('hagrid-build-evidence-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $fixture 'hagrid/simulation/src/main/java'),(Join-Path $fixture 'hagrid/simulation/target'),(Join-Path $fixture 'tools/benchmarks') -Force|Out-Null
New-Item -ItemType Directory -Path (Join-Path $fixture 'external/freight'),(Join-Path $fixture 'external/matsim-libs/contribs/freight/src/main/java'),(Join-Path $fixture 'external/matsim-libs/contribs/freight/test/input'),(Join-Path $fixture 'external/matsim-libs/examples/scenarios/logistics-2regions') -Force|Out-Null
function Write-Fixture($relative,$contents){[IO.File]::WriteAllText((Join-Path $fixture $relative),$contents,[Text.UTF8Encoding]::new($false))}
function Expect-Rejection([scriptblock]$action) {
 $rejected=$false;try{& $action|Out-Null}catch{$rejected=$true};if(!$rejected){throw 'Expected stale/unverified evidence rejection.'}
}
Write-Fixture 'hagrid/simulation/src/main/java/Test.java' 'class Test {}'
Write-Fixture 'tools/benchmarks/ResourceGuard.ps1' '# fixture guard'
Write-Fixture 'hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar' 'fixture bytes only, never executed'
$evidence=Join-Path $fixture 'build.json'
Expect-Rejection {Get-HagridVerifiedBuild $fixture $evidence}
Write-Fixture 'external/freight/pom.xml' '<project />'
Write-Fixture 'external/matsim-libs/contribs/freight/src/main/java/Native.java' 'class Native {}'
Write-Fixture 'external/matsim-libs/contribs/freight/test/input/freight.csv' 'native input'
Write-Fixture 'external/matsim-libs/examples/scenarios/logistics-2regions/network.xml' '<network />'
$source=Get-HagridSourceTreeEvidence $fixture
$build=@{status='VERIFIED';sourceTreeSha256=$source.Sha256;
 jarSha256=(Get-FileHash (Join-Path $fixture 'hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar')).Hash;
 guardSha256=(Get-FileHash (Join-Path $fixture 'tools/benchmarks/ResourceGuard.ps1')).Hash}
Write-Fixture 'build.json' ($build|ConvertTo-Json)
if((Get-HagridVerifiedBuild $fixture $evidence).status -ne 'VERIFIED'){throw 'Matching fixture must be accepted.'}
Write-Fixture 'hagrid/simulation/src/main/java/Test.java' 'class Changed {}'
Expect-Rejection {Get-HagridVerifiedBuild $fixture $evidence}
Write-Fixture 'hagrid/simulation/src/main/java/Test.java' 'class Test {}'
Write-Fixture 'hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar' 'stale different bytes'
Expect-Rejection {Get-HagridVerifiedBuild $fixture $evidence}
Write-Fixture 'hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar' 'fixture bytes only, never executed'
Write-Fixture 'tools/benchmarks/ResourceGuard.ps1' '# changed guard'
Expect-Rejection {Get-HagridVerifiedBuild $fixture $evidence}
Write-Fixture 'tools/benchmarks/ResourceGuard.ps1' '# fixture guard'
foreach($relative in @('external/freight/pom.xml','external/matsim-libs/contribs/freight/src/main/java/Native.java',
        'external/matsim-libs/contribs/freight/test/input/freight.csv','external/matsim-libs/examples/scenarios/logistics-2regions/network.xml')) {
 $original=[IO.File]::ReadAllText((Join-Path $fixture $relative));Write-Fixture $relative ($original+' changed')
 Expect-Rejection {Get-HagridVerifiedBuild $fixture $evidence};Write-Fixture $relative $original
}
$build.status='DEFERRED';Write-Fixture 'build.json' ($build|ConvertTo-Json)
Expect-Rejection {Get-HagridVerifiedBuild $fixture $evidence}
Write-Output 'Build evidence: one matching case and nine missing/stale/unverified rejections passed.'
# Keep the uniquely named tiny fixture as test evidence. No recursive deletion of computed paths.
