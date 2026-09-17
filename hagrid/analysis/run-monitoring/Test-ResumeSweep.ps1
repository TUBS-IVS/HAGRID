# Dependency-free test harness (see Global Constraints: Pester 3.4.0 only).
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File Test-ResumeSweep.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'resume_sweep.ps1')

$script:Failures = 0
function Assert-Equal($Expected, $Actual, $Name) {
    if ($Expected -eq $Actual) { Write-Host "  PASS $Name" }
    else { Write-Host "  FAIL $Name : expected [$Expected] got [$Actual]"; $script:Failures++ }
}

$tmp = Join-Path $env:TEMP ("resumetest_" + [Guid]::NewGuid().ToString('N'))
$out = Join-Path $tmp 'hagrid-matsim-output'
New-Item -ItemType Directory -Path $out -Force | Out-Null

$prefix = 'BASECASE_13052025'
$suffix = '_iter150_jsprit1000'
$argTemplate = 'concept=basecase,date=2025-05-13,tag={TAG},maxIter=150,jspritIter=1000,writeDashboard=true'

# Mirror the real 2026-07-31 measurement: a finished run has a dashboard, a crashed
# run leaves its directory behind with none. Directory existence proves nothing.
function New-FakeRun($tag, $withDashboard) {
    $dir = Join-Path $out "${prefix}_${tag}${suffix}"
    New-Item -ItemType Directory -Path (Join-Path $dir 'analysis') -Force | Out-Null
    if ($withDashboard) {
        Set-Content -Path (Join-Path $dir "analysis\HAGRID_Dashboard_${prefix}_${tag}${suffix}.html") -Value '<html/>' -Encoding Ascii
    }
}
New-FakeRun '60v2' $true
New-FakeRun '70v2' $false      # the real crash fragment: dir present, no dashboard
New-FakeRun '80v2' $true

Write-Host 'Test-TagComplete'
Assert-Equal $true  (Test-TagComplete $out $prefix '60v2' $suffix) 'dashboard present means complete'
Assert-Equal $false (Test-TagComplete $out $prefix '70v2' $suffix) 'crash fragment is NOT complete'
Assert-Equal $false (Test-TagComplete $out $prefix '999v2' $suffix) 'absent run is not complete'

Write-Host 'Select-RemainingTags'
$remaining = Select-RemainingTags $out $prefix @('60v2','70v2','80v2','90v2') $suffix
Assert-Equal '70v2,90v2' ($remaining -join ',') 'returns incomplete tags in order'
$none = Select-RemainingTags $out $prefix @('60v2','80v2') $suffix
Assert-Equal 0 $none.Count 'all complete yields empty'
# Exactly ONE tag remaining is the normal state near the end of every sweep, and it is
# the case PowerShell's array-unrolling-on-return silently breaks: a bare "return
# $remaining" collapses a single-element array to a scalar STRING, so Task 9's
# $remaining[0] would index the string's first CHARACTER ('7') instead of the tag
# ('70v2'), and the crash directory would never get deleted before relaunch.
$one = Select-RemainingTags $out $prefix @('60v2','70v2','80v2') $suffix
Assert-Equal $true ($one -is [array]) 'single remaining tag is still an array, not an unrolled scalar'
Assert-Equal 1 $one.Count 'single remaining tag: count is 1'
Assert-Equal '70v2' $one[0] 'single remaining tag: element 0 is the tag, not its first character'

Write-Host 'Test-StepAComplete'
$carriers = Join-Path $tmp 'hagrid-output'
New-Item -ItemType Directory -Path (Join-Path $carriers "${prefix}_60v2\carriers") -Force | Out-Null
Set-Content -Path (Join-Path $carriers "${prefix}_60v2\carriers\${prefix}_60v2_delivery_carriers_routed.xml") -Value '<carriers/>' -Encoding Ascii
Assert-Equal $true  (Test-StepAComplete $carriers $prefix @('60v2')) 'carrier file present'
Assert-Equal $false (Test-StepAComplete $carriers $prefix @('60v2','70v2')) 'one missing carrier set fails the whole phase'

Write-Host 'New-StepBBatch writes CRLF ASCII'
$bat = Join-Path $tmp 'run_resume.bat'
# ArgTemplate is a REQUIRED parameter carrying the full, non-hardcoded sweep config:
# a default would be exactly how a stale config value (old date, old jsprit
# iteration count) creeps back into a boot-triggered relaunch nobody is watching.
New-StepBBatch -Path $bat -Tags @('70v2','90v2') -JavaExe 'C:\jdk\bin\java.exe' -JvmArgs '-Xmx1g' `
    -Jar 'C:\repo\app.jar' -WorkDir 'C:\repo\pipeline' -ArgTemplate $argTemplate
$bytes = [IO.File]::ReadAllBytes($bat)
$text  = [Text.Encoding]::ASCII.GetString($bytes)
$cr = ($text.ToCharArray() | Where-Object { $_ -eq "`r" }).Count
$lf = ($text.ToCharArray() | Where-Object { $_ -eq "`n" }).Count
Assert-Equal $true ($cr -gt 0) 'has CR characters'
Assert-Equal $cr $lf 'CR count equals LF count (proper CRLF, cmd-safe)'
Assert-Equal $true ($text -like '*tag=70v2*') 'first tag present'
Assert-Equal $true ($text -like '*tag=90v2*') 'second tag present'
Assert-Equal $true ($text.IndexOf('tag=70v2') -lt $text.IndexOf('tag=90v2')) 'tags in ascending order'
Assert-Equal $true ($text -like '*RESUME_DONE*') 'completion marker text present in the generated batch'
# The template's values must actually reach the generated batch, once per tag - not
# a hand-typed literal that can silently drift from the real sweep config.
$maxIterCount = ([regex]::Matches($text, 'maxIter=150')).Count
Assert-Equal 2 $maxIterCount 'ArgTemplate values (maxIter=150) reach the batch once per tag'
Assert-Equal $false ($text -like '*{TAG}*') '{TAG} placeholder is fully substituted, none left literal'
Assert-Equal $true ($text -like '*-Xmx1g*') 'JvmArgs value reaches the generated batch'
$threwNoJvmArgs = $false
try { New-StepBBatch -Path $bat -Tags @('70v2') -JavaExe 'x' -JvmArgs '' -Jar 'x' -WorkDir 'x' -ArgTemplate $argTemplate }
catch { $threwNoJvmArgs = $true }
Assert-Equal $true $threwNoJvmArgs 'empty JvmArgs throws (review Finding C6: no hardcoded/default heap flags)'
# ASCIIEncoding.GetBytes() silently replaces any non-ASCII source character with
# '?' BEFORE the byte array exists, so counting bytes above 127 can never observe
# an encoding slip - that assertion cannot fail no matter what. A literal '?' in
# the decoded text is the only observable symptom of a mangled character.
Assert-Equal $false ($text.Contains('?')) 'no non-ASCII source characters were silently replaced with ?'

Write-Host 'New-StepBBatch: RESUME_DONE vs RESUME_FAILED are determined at RUNTIME, not just present in the source text (review Finding C4)'
# The ORIGINAL batch always emitted RESUME_DONE regardless of any tag's exit code,
# so a resumed run with e.g. a stale jar path would fail every tag in seconds,
# print RESUME_DONE anyway, and the heartbeat would then push "sweep finished" AND
# ping progress unconditionally forever - an all-green dashboard while the machine
# idles for the rest of the absence. Both branches of the fixed if/else live on the
# SAME source line, so a static text-only assertion (checking the string
# "RESUME_DONE" is present) cannot tell fixed from broken - it must actually be
# EXECUTED under both a successful and a failing "java" to prove the branching.
# Real, compiled .exe stand-ins, NOT .bat files: a .bat invoked from inside another
# .bat via a bare quoted path (no "call") transfers control and never returns to the
# calling script - a batch-specific quirk that a real .exe (like the real java.exe)
# does not have. Using a .bat here would have made this test fail for a reason
# having nothing to do with the RESUME_DONE/RESUME_FAILED logic under test.
$fakeJavaOk = Join-Path $tmp 'fake_java_ok.exe'
Add-Type -OutputType ConsoleApplication -Language CSharp -OutputAssembly $fakeJavaOk `
    -TypeDefinition 'public class FakeJavaOk { public static int Main(string[] a) { return 0; } }'
$fakeJavaFail = Join-Path $tmp 'fake_java_fail.exe'
Add-Type -OutputType ConsoleApplication -Language CSharp -OutputAssembly $fakeJavaFail `
    -TypeDefinition 'public class FakeJavaFail { public static int Main(string[] a) { return 1; } }'

$batOk = Join-Path $tmp 'run_ok.bat'
New-StepBBatch -Path $batOk -Tags @('70v2','90v2') -JavaExe $fakeJavaOk -JvmArgs '-Xmx1g' -Jar 'C:\repo\app.jar' -WorkDir $tmp -ArgTemplate $argTemplate
$outOk = (& cmd.exe /c "`"$batOk`"") -join "`n"
Assert-Equal $true  ($outOk -like '*RESUME_DONE*')   'all tags exit 0: RESUME_DONE is actually printed at runtime'
Assert-Equal $false ($outOk -like '*RESUME_FAILED*') 'all tags exit 0: RESUME_FAILED is NOT printed'

$batFail = Join-Path $tmp 'run_fail.bat'
New-StepBBatch -Path $batFail -Tags @('70v2','90v2') -JavaExe $fakeJavaFail -JvmArgs '-Xmx1g' -Jar 'C:\repo\app.jar' -WorkDir $tmp -ArgTemplate $argTemplate
$outFail = (& cmd.exe /c "`"$batFail`"") -join "`n"
Assert-Equal $true  ($outFail -like '*RESUME_FAILED*') 'a failing tag: RESUME_FAILED is actually printed at runtime'
Assert-Equal $false ($outFail -like '*RESUME_DONE*')   'a failing tag: RESUME_DONE is NOT printed (would have falsely announced success)'
# RESUME_FAILED must not accidentally satisfy heartbeat.ps1's completion-marker
# substring match for RESUME_DONE.
Assert-Equal $false ('===== RESUME_FAILED 31.07.2026 =====' -like '*RESUME_DONE*') 'RESUME_FAILED text does not accidentally contain the RESUME_DONE substring'

Write-Host 'Test-LockFree'
# Deliberately tests the PURE lock predicate, not Test-CanLaunch: the latter also
# probes for a running java.exe, so on a machine that happens to be running a sim it
# would return $false and the test would fail for reasons unrelated to the code.
$lock = Join-Path $tmp 'resume.lock'
Assert-Equal $true  (Test-LockFree $lock) 'no lock means free'
Set-Content -Path $lock -Value 'held' -Encoding Ascii
Assert-Equal $false (Test-LockFree $lock) 'existing lock blocks launch'
# Test-CanLaunch must be false whenever the lock is held, regardless of java state.
Assert-Equal $false (Test-CanLaunch $lock) 'held lock blocks Test-CanLaunch too'

Write-Host 'Test-LockStale (review Finding C5)'
$staleLock = Join-Path $tmp 'stale.lock'
Set-Content -LiteralPath $staleLock -Value 'x' -Encoding Ascii
Assert-Equal $false (Test-LockStale $staleLock 12) 'a freshly-written lock is not stale'
Assert-Equal $false (Test-LockStale $staleLock 0)  'StaleHours <= 0 disables staleness (reproduces the pre-review, always-blocks behavior)'
(Get-Item -LiteralPath $staleLock).LastWriteTime = (Get-Date).AddHours(-13)
Assert-Equal $true  (Test-LockStale $staleLock 12) 'a lock older than the configured threshold is stale'
Assert-Equal $false (Test-LockStale $staleLock 24) 'the SAME lock is NOT stale against a longer threshold'
Assert-Equal $false (Test-LockStale (Join-Path $tmp 'no_such.lock') 12) 'a nonexistent lock file is not "stale" - Test-LockFree already covers absence'

Write-Host 'Test-ContainsReplacePlaceholder (review Finding I2 residual: the scan must recurse into arrays)'
Assert-Equal $false (Test-ContainsReplacePlaceholder 'C:\real\path.jar') 'an ordinary string is not flagged'
Assert-Equal $true  (Test-ContainsReplacePlaceholder 'C:\REPLACE-WITH-JAR-PATH.jar') 'a string containing REPLACE is flagged'
Assert-Equal $false (Test-ContainsReplacePlaceholder @('30v3','40v3','50v3')) 'an array of real tags is not flagged'
Assert-Equal $true  (Test-ContainsReplacePlaceholder @('30v3','REPLACE-WITH-REAL-TAG-2')) 'an array containing EVEN ONE REPLACE entry is flagged, not just top-level strings'
Assert-Equal $false (Test-ContainsReplacePlaceholder $null) 'null is not flagged'
Assert-Equal $false (Test-ContainsReplacePlaceholder 12)   'a non-string, non-enumerable value (e.g. a number) is not flagged'

Write-Host 'Test-ResumeConfigValid (review Finding I2)'
function New-ValidResumeCfgHash {
    return [ordered]@{
        LockPath='L'; Tags=@('a'); CarrierRoot='C'; RunIdPrefix='P'; OutputRoot='O'; Suffix='S'
        StepALauncher='SA'; ArgTemplate='x{TAG}'; JvmArgs='-Xmx1g'; JavaExe='J'; Jar='Jar'
        WorkDir='W'; GeneratedBatPath='G'; StartDetached='SD'; ResumeLog='R'
    }
}
Assert-Equal 0 (Test-ResumeConfigValid ([PSCustomObject](New-ValidResumeCfgHash))).Count 'a fully-populated config has zero problems'

$missingHash = New-ValidResumeCfgHash
$missingHash.Remove('Jar')
$missingProblems = Test-ResumeConfigValid ([PSCustomObject]$missingHash)
Assert-Equal $true ($missingProblems.Count -gt 0) 'a missing required key is flagged'
Assert-Equal $true (($missingProblems -join ' ') -like '*Jar*') 'the missing key is NAMED in the problem message'

$placeholderHash = New-ValidResumeCfgHash
$placeholderHash.Jar = 'C:\REPLACE-WITH-JAR-PATH.jar'
Assert-Equal $true ((Test-ResumeConfigValid ([PSCustomObject]$placeholderHash)).Count -gt 0) 'an unfilled REPLACE placeholder is flagged'

$emptyTagsHash = New-ValidResumeCfgHash
$emptyTagsHash.Tags = @()
Assert-Equal $true ((Test-ResumeConfigValid ([PSCustomObject]$emptyTagsHash)).Count -gt 0) 'an empty Tags array is flagged'

# Review Finding I2 residual: a config left with placeholder tags used to install
# green (the old scan only checked top-level strings) and would burn a full ~6h
# Step A re-run plus a Step B launch that fails every tag at the next boot.
$placeholderTagsHash = New-ValidResumeCfgHash
$placeholderTagsHash.Tags = @('30v3', 'REPLACE-WITH-REAL-TAG-2')
$placeholderTagsProblems = Test-ResumeConfigValid ([PSCustomObject]$placeholderTagsHash)
Assert-Equal $true ($placeholderTagsProblems.Count -gt 0) 'a Tags array containing a REPLACE entry is rejected, not just a top-level string REPLACE'
Assert-Equal $true (($placeholderTagsProblems -join ' ') -like '*Tags*') 'the rejected key is named as Tags'

Assert-Equal $true ((Test-ResumeConfigValid $null).Count -gt 0) 'a null config (unparseable/missing file) is flagged, not silently accepted'

Write-Host ''
Write-Host 'Invoke-ResumeSweep (main loop, both -DryRun and real execution)'
# A previous task in this plan shipped two critical wiring bugs precisely because its
# main loop had no test while every leaf function was green in isolation; a later
# review found the SAME class of gap (java-running refusal branch untested, and the
# entire real/non-dry-run execution path untested). -DryRun makes decisions and logs
# them without deleting or launching anything and is exercised end-to-end against
# real fixture trees and real JSON configs; a real (non-DryRun) scenario is included
# too (review Finding I7), using a stub launcher that records its own invocation
# instead of launching anything real.
#
# Capture mechanism note: Invoke-ResumeSweep reports exclusively via Write-Host. On
# PowerShell 5.1, Write-Host writes to the Information stream (6), which "6>&1 | Out-String"
# redirects into the success stream and flattens to text - confirmed working on this
# PS 5.1 (5.1.26100.8875) before relying on it below. A plain "$x = Invoke-ResumeSweep ..."
# would NOT have captured it, since Write-Host bypasses the success/output stream.
#
# Known gotcha (recorded from an earlier task): $env:TEMP can resolve to an 8.3 short
# path while .FullName returns the long form. Every fixture root below is resolved
# through .FullName immediately after creation so the paths written into each JSON
# config match what Test-Path and Get-ChildItem see later.

function New-InvokeFixtureRoot {
    $raw = Join-Path $env:TEMP ("resumeinvoke_" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $raw -Force | Out-Null
    return (Get-Item -LiteralPath $raw).FullName
}

function New-InvokeCarrier($CarrierRoot, $Prefix, $Tag) {
    $dir = Join-Path $CarrierRoot ("{0}_{1}\carriers" -f $Prefix, $Tag)
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    Set-Content -Path (Join-Path $dir ("{0}_{1}_delivery_carriers_routed.xml" -f $Prefix, $Tag)) -Value '<carriers/>' -Encoding Ascii
}

function New-InvokeRun($OutputRoot, $Prefix, $Tag, $Suffix, $WithDashboard) {
    # Mirrors New-FakeRun above: a finished run has a dashboard, a crashed one has the
    # directory but no dashboard. This is the real 2026-07-31 crash signature.
    $dir = Join-Path $OutputRoot ("{0}_{1}{2}" -f $Prefix, $Tag, $Suffix)
    New-Item -ItemType Directory -Path (Join-Path $dir 'analysis') -Force | Out-Null
    if ($WithDashboard) {
        Set-Content -Path (Join-Path $dir ("analysis\HAGRID_Dashboard_{0}_{1}{2}.html" -f $Prefix, $Tag, $Suffix)) -Value '<html/>' -Encoding Ascii
    }
    return $dir
}

function New-InvokeStubFile($Path) {
    # Only needs to EXIST (review Finding I2: Invoke-ResumeSweep now Test-Path's
    # JavaExe/Jar up front, in both modes). Content is irrelevant - neither is ever
    # executed directly; their paths are only ever embedded as text into the
    # generated batch.
    Set-Content -LiteralPath $Path -Value 'stub' -Encoding Ascii
}

function New-InvokeStubLauncher($Path, $MarkerPath) {
    # A real, existing, do-nothing PowerShell script that records that it was
    # invoked (and with what) by appending to $MarkerPath. Required now that
    # JavaExe/Jar/StartDetached/StepALauncher must all Test-Path true up front
    # (review Finding I2) even in -DryRun, which retires the old "points at a
    # nonexistent path, so invoking it throws" trap. This is the direct
    # replacement: if a DryRun scenario ever mistakenly invoked it, the marker
    # file's appearance (asserted absent in every DryRun scenario below) is the
    # detectable proof, and in the one real-execution scenario, the marker's
    # CONTENT proves the stub received the expected arguments.
    $line1 = 'param([string]$Command, [string]$LogFile, [string]$WorkDir)'
    $line2 = 'Add-Content -LiteralPath ' + "'$MarkerPath'" + ' -Value ("invoked: Command=$Command LogFile=$LogFile WorkDir=$WorkDir")'
    Set-Content -LiteralPath $Path -Value @($line1, $line2) -Encoding Ascii
}

function New-StepACompleterStub($Path, $CarrierRoot, $Prefix, $TagsToComplete) {
    # Stands in for a REAL StepALauncher that actually finishes Step A: writes
    # each tag's routed-carrier file, using the same convention Test-StepAComplete
    # checks. Used only by the real-execution scenario (review Finding I7), to
    # prove Invoke-ResumeSweep correctly CHAINS into Step B tag selection
    # afterward in the SAME invocation (review Finding I4) rather than returning.
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('param()')
    foreach ($tag in $TagsToComplete) {
        $dir = Join-Path $CarrierRoot ("{0}_{1}\carriers" -f $Prefix, $tag)
        $file = Join-Path $dir ("{0}_{1}_delivery_carriers_routed.xml" -f $Prefix, $tag)
        $lines.Add("New-Item -ItemType Directory -Path '$dir' -Force | Out-Null")
        $lines.Add("Set-Content -LiteralPath '$file' -Value '<carriers/>' -Encoding Ascii")
    }
    Set-Content -LiteralPath $Path -Value $lines -Encoding Ascii
}

function New-InvokeConfig($Root, $LockPath, $Tags, $CarrierRoot, $OutputRoot, $Prefix, $Suffix, $StepALauncherOverride) {
    $marker = Join-Path $Root 'invoked.marker'
    $javaExe = Join-Path $Root 'stub_java.exe'
    $jar     = Join-Path $Root 'stub_app.jar'
    New-InvokeStubFile $javaExe
    New-InvokeStubFile $jar
    $stepALauncher = $StepALauncherOverride
    if (-not $stepALauncher) {
        $stepALauncher = Join-Path $Root 'stub_stepA.ps1'
        New-InvokeStubLauncher $stepALauncher $marker
    }
    $startDetached = Join-Path $Root 'stub_start_detached.ps1'
    New-InvokeStubLauncher $startDetached $marker

    $cfg = [ordered]@{
        LockPath         = $LockPath
        Tags             = $Tags
        CarrierRoot      = $CarrierRoot
        RunIdPrefix      = $Prefix
        OutputRoot       = $OutputRoot
        Suffix           = $Suffix
        StepALauncher    = $stepALauncher
        ArgTemplate      = $argTemplate
        JvmArgs          = '-Xmx1g'
        JavaExe          = $javaExe
        Jar              = $jar
        WorkDir          = $Root
        GeneratedBatPath = (Join-Path $Root 'run_resume.bat')
        StartDetached    = $startDetached
        ResumeLog        = (Join-Path $Root 'resume_sweep.log')
        ResumedUrl       = ''
        LocalLogPath     = (Join-Path $Root 'resume_local.log')
    }
    $configPath = Join-Path $Root 'resume-config.json'
    ($cfg | ConvertTo-Json) | Set-Content -Path $configPath -Encoding Ascii
    return [PSCustomObject]@{ ConfigPath = $configPath; MarkerPath = $marker; StepALauncher = $stepALauncher; StartDetached = $startDetached }
}

# Test-CanLaunch (called internally by Invoke-ResumeSweep) also probes for a running
# java.exe on the machine, same as the Test-LockFree section above notes. Scenarios
# A-H below need control over that probe: B-E/G/H need to get PAST it (a real
# java.exe was observed running on this dev box during development, which would
# otherwise wrongly block every one of them for a reason unrelated to the code
# under test), and F needs to force it ON to prove the refusal path is actually
# exercised somewhere.
#
# Shadowing the Get-Process cmdlet with a same-named function is a standard mocking
# technique in PowerShell: function name resolution wins over a cmdlet of the same
# name in the calling scope chain, so Test-CanLaunch's internal
# "Get-Process java -ErrorAction SilentlyContinue" call resolves to this fake instead,
# with no change needed to resume_sweep.ps1 itself.
#
# DANGER ZONE, LEXICALLY BOUNDED: everything that must see the fake lives inside this
# "& { ... }" script block, not at top-level script scope. A function defined inside a
# scriptblock is local to that scriptblock's lifetime and disappears when it exits -
# including on an exception - so there is no manual Remove-Item cleanup to forget and
# no risk of a bare throw (this file deliberately invokes several nonexistent paths
# under $ErrorActionPreference = 'Stop') leaking the shadow into the rest of the file.
# $script:Failures still accumulates correctly across this boundary: Assert-Equal
# already increments it via the $script: scope modifier, which resolves to the file's
# top-level scope regardless of how many script-block scopes sit in between.
& {
    $script:MockJavaRunning = $false
    function Get-Process {
        [CmdletBinding()] param($Name)
        if ($script:MockJavaRunning) { return @(New-Object psobject) }
        return @()
    }

# --- Test-CanLaunch with a stale lock and no java running (review Finding C5) ---
$staleForCanLaunch = Join-Path $tmp 'stale_canlaunch.lock'
Set-Content -LiteralPath $staleForCanLaunch -Value 'x' -Encoding Ascii
(Get-Item -LiteralPath $staleForCanLaunch).LastWriteTime = (Get-Date).AddHours(-13)
Assert-Equal $true  (Test-CanLaunch $staleForCanLaunch 12) 'a stale lock, no java running: Test-CanLaunch allows launch (java.exe is the real double-launch guard)'
Assert-Equal $false (Test-CanLaunch $staleForCanLaunch 24) 'the SAME lock against a longer staleness threshold: still blocked (not yet stale enough)'

# --- Scenario A: lock held -> blocked, nothing evaluated, nothing touched ---
$rootA = New-InvokeFixtureRoot
$lockA = Join-Path $rootA 'resume.lock'
Set-Content -Path $lockA -Value 'held' -Encoding Ascii
$carrierA = Join-Path $rootA 'hagrid-output'
$outA = Join-Path $rootA 'hagrid-matsim-output'
New-Item -ItemType Directory -Path $carrierA -Force | Out-Null
New-Item -ItemType Directory -Path $outA -Force | Out-Null
New-InvokeCarrier $carrierA $prefix '60v2'
$dirA = New-InvokeRun $outA $prefix '60v2' $suffix $true
$fixtureA = New-InvokeConfig $rootA $lockA @('60v2') $carrierA $outA $prefix $suffix $null

$outputA = (Invoke-ResumeSweep -ConfigPath $fixtureA.ConfigPath -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputA -like '*blocked*') 'lock held: reports blocked'
Assert-Equal $true  (Test-Path -LiteralPath $lockA) 'lock held: lock file still present (untouched, not recreated)'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootA 'run_resume.bat')) 'lock held: no batch file written'
Assert-Equal $true  (Test-Path -LiteralPath $dirA) 'lock held: existing output directory untouched'
Assert-Equal $false (Test-Path -LiteralPath $fixtureA.MarkerPath) 'lock held: neither StepALauncher nor StartDetached was ever invoked'
Remove-Item $rootA -Recurse -Force

# --- Scenario B: Step A incomplete -> full re-run reported, Step B tag selection skipped ---
$rootB = New-InvokeFixtureRoot
$lockB = Join-Path $rootB 'resume.lock'
$carrierB = Join-Path $rootB 'hagrid-output'
$outB = Join-Path $rootB 'hagrid-matsim-output'
New-Item -ItemType Directory -Path $carrierB -Force | Out-Null
New-Item -ItemType Directory -Path $outB -Force | Out-Null
New-InvokeCarrier $carrierB $prefix '60v2'   # 70v2's carrier file is deliberately absent
# An unrelated, already-finished run's output directory (same OutputRoot, a tag not
# even in this scenario's Tags list) - stands in for real leftover output from a
# previous sweep. Its survival proves the "nothing was deleted" claim by execution,
# not by the absence of anything to delete.
$dirCompleteB = New-InvokeRun $outB $prefix '80v2' $suffix $true
$fixtureB = New-InvokeConfig $rootB $lockB @('60v2','70v2') $carrierB $outB $prefix $suffix $null

$outputB = (Invoke-ResumeSweep -ConfigPath $fixtureB.ConfigPath -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputB -like '*Step A incomplete*') 'Step A incomplete: reports it would re-run Step A in full'
Assert-Equal $false ($outputB -like '*remaining*') 'Step A incomplete: does NOT proceed to Step B tag selection'
Assert-Equal $false (Test-Path -LiteralPath $lockB) 'Step A incomplete dry run: no lock file created'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootB 'run_resume.bat')) 'Step A incomplete dry run: no batch file written'
Assert-Equal $true  (Test-Path -LiteralPath $dirCompleteB) 'Step A incomplete dry run: unrelated finished-run directory NOT deleted'
Assert-Equal $false (Test-Path -LiteralPath $fixtureB.MarkerPath) 'Step A incomplete dry run: StepALauncher was NOT actually invoked'
Remove-Item $rootB -Recurse -Force

# --- Scenario C: Step A complete, several tags remaining -> names the FIRST incomplete one ---
$rootC = New-InvokeFixtureRoot
$lockC = Join-Path $rootC 'resume.lock'
$carrierC = Join-Path $rootC 'hagrid-output'
$outC = Join-Path $rootC 'hagrid-matsim-output'
New-Item -ItemType Directory -Path $carrierC -Force | Out-Null
New-Item -ItemType Directory -Path $outC -Force | Out-Null
$tagsC = @('60v2','70v2','80v2','90v2')
foreach ($t in $tagsC) { New-InvokeCarrier $carrierC $prefix $t }
New-InvokeRun $outC $prefix '60v2' $suffix $true  | Out-Null
$dir70C = New-InvokeRun $outC $prefix '70v2' $suffix $false   # crash fragment: dir, no dashboard
New-InvokeRun $outC $prefix '80v2' $suffix $true  | Out-Null
$dir90C = New-InvokeRun $outC $prefix '90v2' $suffix $false   # crash fragment: dir, no dashboard
$fixtureC = New-InvokeConfig $rootC $lockC $tagsC $carrierC $outC $prefix $suffix $null

$outputC = (Invoke-ResumeSweep -ConfigPath $fixtureC.ConfigPath -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputC -like '*2 tag(s) remaining*') 'multi-remaining: reports the correct remaining count (2)'
Assert-Equal $true  ($outputC -like '*first incomplete = 70v2*') 'multi-remaining: names the FIRST incomplete tag (70v2, not 90v2)'
Assert-Equal $false (Test-Path -LiteralPath $lockC) 'multi-remaining dry run: no lock file created'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootC 'run_resume.bat')) 'multi-remaining dry run: no batch file written'
Assert-Equal $true  (Test-Path -LiteralPath $dir70C) 'multi-remaining dry run: first crash-fragment directory NOT deleted'
Assert-Equal $true  (Test-Path -LiteralPath $dir90C) 'multi-remaining dry run: second crash-fragment directory untouched'
Assert-Equal $false (Test-Path -LiteralPath $fixtureC.MarkerPath) 'multi-remaining dry run: StartDetached was NOT actually invoked'
Remove-Item $rootC -Recurse -Force

# --- Scenario D: exactly ONE tag remaining -> guards the $remaining[0] call site in the ---
# --- main loop against the single-element-array-unrolls-to-a-scalar-string bug that was ---
# --- already fixed once inside Select-RemainingTags itself. ---
$rootD = New-InvokeFixtureRoot
$lockD = Join-Path $rootD 'resume.lock'
$carrierD = Join-Path $rootD 'hagrid-output'
$outD = Join-Path $rootD 'hagrid-matsim-output'
New-Item -ItemType Directory -Path $carrierD -Force | Out-Null
New-Item -ItemType Directory -Path $outD -Force | Out-Null
$tagsD = @('60v2','70v2','80v2')
foreach ($t in $tagsD) { New-InvokeCarrier $carrierD $prefix $t }
New-InvokeRun $outD $prefix '60v2' $suffix $true  | Out-Null
$dir70D = New-InvokeRun $outD $prefix '70v2' $suffix $false   # the ONLY incomplete tag
New-InvokeRun $outD $prefix '80v2' $suffix $true  | Out-Null
$fixtureD = New-InvokeConfig $rootD $lockD $tagsD $carrierD $outD $prefix $suffix $null

$outputD = (Invoke-ResumeSweep -ConfigPath $fixtureD.ConfigPath -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputD -like '*1 tag(s) remaining*') 'single-remaining: reports count 1'
# If the bug were reintroduced, $first would be the STRING "70v2"[0] = '7', and the
# message would read "first incomplete = 7" instead of "first incomplete = 70v2".
Assert-Equal $true  ($outputD -like '*first incomplete = 70v2*') 'single-remaining: names the full tag "70v2", not its first character'
Assert-Equal $false (Test-Path -LiteralPath $lockD) 'single-remaining dry run: no lock file created'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootD 'run_resume.bat')) 'single-remaining dry run: no batch file written'
Assert-Equal $true  (Test-Path -LiteralPath $dir70D) 'single-remaining dry run: the one crash-fragment directory NOT deleted'
Assert-Equal $false (Test-Path -LiteralPath $fixtureD.MarkerPath) 'single-remaining dry run: StartDetached was NOT actually invoked'
Remove-Item $rootD -Recurse -Force

# --- Scenario E: all tags complete -> nothing to do ---
$rootE = New-InvokeFixtureRoot
$lockE = Join-Path $rootE 'resume.lock'
$carrierE = Join-Path $rootE 'hagrid-output'
$outE = Join-Path $rootE 'hagrid-matsim-output'
New-Item -ItemType Directory -Path $carrierE -Force | Out-Null
New-Item -ItemType Directory -Path $outE -Force | Out-Null
$tagsE = @('60v2','70v2')
foreach ($t in $tagsE) { New-InvokeCarrier $carrierE $prefix $t }
$dirsE = @()
foreach ($t in $tagsE) { $dirsE += ,(New-InvokeRun $outE $prefix $t $suffix $true) }
$fixtureE = New-InvokeConfig $rootE $lockE $tagsE $carrierE $outE $prefix $suffix $null

$outputE = (Invoke-ResumeSweep -ConfigPath $fixtureE.ConfigPath -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputE -like '*all tags complete*') 'all complete: reports nothing to do'
Assert-Equal $false (Test-Path -LiteralPath $lockE) 'all complete dry run: no lock file created'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootE 'run_resume.bat')) 'all complete dry run: no batch file written'
# Both tags' own finished-run directories, not a stand-in: if the code ever deleted
# "the first tag" unconditionally instead of only an incomplete one, this is what
# would catch it - proof by execution rather than by there being nothing to delete.
Assert-Equal $true  (Test-Path -LiteralPath $dirsE[0]) 'all complete dry run: first finished-run directory NOT deleted'
Assert-Equal $true  (Test-Path -LiteralPath $dirsE[1]) 'all complete dry run: second finished-run directory NOT deleted'
Assert-Equal $false (Test-Path -LiteralPath $fixtureE.MarkerPath) 'all complete dry run: nothing was ever invoked'
Remove-Item $rootE -Recurse -Force

# --- Scenario F: java.exe already running, NO lock file ---
# resume_sweep.ps1's "Get-Process java" guard is arguably the highest-consequence
# line in this feature - it is what stops a boot-resume from double-launching onto an
# already-running sweep and corrupting its output directories, unattended, with nobody
# watching. Scenario A's "blocked" case proves nothing about this line: it is blocked
# by the LOCK file before Test-CanLaunch ever reaches the java probe. This scenario
# forces the java probe to report a process and leaves the lock ABSENT, so a "blocked"
# result here can only come from the java check - proving the refusal path is real.
$script:MockJavaRunning = $true
$rootF = New-InvokeFixtureRoot
$lockF = Join-Path $rootF 'resume.lock'   # deliberately never created
$carrierF = Join-Path $rootF 'hagrid-output'
$outF = Join-Path $rootF 'hagrid-matsim-output'
New-Item -ItemType Directory -Path $carrierF -Force | Out-Null
New-Item -ItemType Directory -Path $outF -Force | Out-Null
New-InvokeCarrier $carrierF $prefix '60v2'
$dirF = New-InvokeRun $outF $prefix '60v2' $suffix $true
$fixtureF = New-InvokeConfig $rootF $lockF @('60v2') $carrierF $outF $prefix $suffix $null

$outputF = (Invoke-ResumeSweep -ConfigPath $fixtureF.ConfigPath -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputF -like '*blocked*') 'java running, no lock present: reports blocked (refusal is from the java probe, not the lock)'
Assert-Equal $false (Test-Path -LiteralPath $lockF) 'java running, no lock present: still no lock file created'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootF 'run_resume.bat')) 'java running, no lock present: no batch file written'
Assert-Equal $true  (Test-Path -LiteralPath $dirF) 'java running, no lock present: existing output directory untouched'
Assert-Equal $false (Test-Path -LiteralPath $fixtureF.MarkerPath) 'java running, no lock present: nothing was ever invoked'
Remove-Item $rootF -Recurse -Force
$script:MockJavaRunning = $false

# --- Scenario G (review Finding I7): the REAL, non-dry-run path end to end. ---
# Step A starts INCOMPLETE (no carrier files at all) and StepALauncher is a stub
# that actually completes it (review Finding I4: proves the function chains into
# Step B selection in the SAME invocation instead of returning). Two tags are left
# as crash fragments and one is already complete (review Finding I3: ALL remaining
# tags' partials must be deleted, not just the first). StartDetached is a stub
# that records its own invocation instead of launching anything real.
$rootG = New-InvokeFixtureRoot
$lockG = Join-Path $rootG 'resume.lock'
$carrierG = Join-Path $rootG 'hagrid-output'
$outG = Join-Path $rootG 'hagrid-matsim-output'
New-Item -ItemType Directory -Path $carrierG -Force | Out-Null
New-Item -ItemType Directory -Path $outG -Force | Out-Null
$tagsG = @('60v2','70v2','80v2')
# Deliberately NO carrier files yet - Step A starts incomplete.
$dir60G = New-InvokeRun $outG $prefix '60v2' $suffix $true    # already finished; must survive untouched
$dir70G = New-InvokeRun $outG $prefix '70v2' $suffix $false   # crash fragment - must be deleted
$dir80G = New-InvokeRun $outG $prefix '80v2' $suffix $false   # crash fragment - must be deleted
$stepACompleterG = Join-Path $rootG 'stepA_completer.ps1'
New-StepACompleterStub $stepACompleterG $carrierG $prefix $tagsG
$fixtureG = New-InvokeConfig $rootG $lockG $tagsG $carrierG $outG $prefix $suffix $stepACompleterG

Invoke-ResumeSweep -ConfigPath $fixtureG.ConfigPath 6>&1 | Out-Null

Assert-Equal $true (Test-Path -LiteralPath (Join-Path $carrierG "${prefix}_60v2\carriers\${prefix}_60v2_delivery_carriers_routed.xml")) 'real run: Step A stub actually ran and created carrier files (chained, review Finding I4)'
Assert-Equal $true (Test-Path -LiteralPath (Join-Path $carrierG "${prefix}_80v2\carriers\${prefix}_80v2_delivery_carriers_routed.xml")) 'real run: Step A stub completed ALL tags, not just one'
Assert-Equal $true  (Test-Path -LiteralPath $lockG) 'real run: the lock exists at the end (Step B leaves it in place for the detached run - java.exe is the ongoing guard)'
Assert-Equal 'stepB' (Get-Content -LiteralPath $lockG -Raw).Trim() 'real run: the final lock content is stepB (created-then-released for Step A, then re-created for the launched Step B)'
Assert-Equal $false (Test-Path -LiteralPath $dir70G) 'real run: crash-fragment partial for 70v2 WAS deleted (review Finding I3)'
Assert-Equal $false (Test-Path -LiteralPath $dir80G) 'real run: crash-fragment partial for 80v2 WAS ALSO deleted, not just the first remaining tag (review Finding I3)'
Assert-Equal $true  (Test-Path -LiteralPath $dir60G) 'real run: the already-finished 60v2 directory is untouched'
$genBatG = Join-Path $rootG 'run_resume.bat'
Assert-Equal $true (Test-Path -LiteralPath $genBatG) 'real run: the Step B batch was actually generated'
$genBatTextG = Get-Content -LiteralPath $genBatG -Raw
Assert-Equal $true  ($genBatTextG -like '*tag=70v2*') 'real run: generated batch includes the first remaining tag'
Assert-Equal $true  ($genBatTextG -like '*tag=80v2*') 'real run: generated batch includes the second remaining tag'
Assert-Equal $false ($genBatTextG -like '*tag=60v2*') 'real run: generated batch does NOT include the already-finished tag'
Assert-Equal $true (Test-Path -LiteralPath $fixtureG.MarkerPath) 'real run: StartDetached WAS actually invoked'
$markerTextG = Get-Content -LiteralPath $fixtureG.MarkerPath -Raw
Assert-Equal $true ($markerTextG -like "*Command=$genBatG*")            'real run: the stub received the expected -Command (the generated batch path)'
Assert-Equal $true ($markerTextG -like "*LogFile=$(Join-Path $rootG 'resume_sweep.log')*") 'real run: the stub received the expected -LogFile'
Assert-Equal $true ($markerTextG -like "*WorkDir=$rootG*")              'real run: the stub received the expected -WorkDir'
$localLogG = Get-Content -LiteralPath (Join-Path $rootG 'resume_local.log') -Raw
Assert-Equal $true ($localLogG -like '*Step A finished*')     'real run: local log records Step A finishing (review Finding I1)'
Assert-Equal $true ($localLogG -like '*removed partial output*70v2*') 'real run: local log records the 70v2 deletion'
Assert-Equal $true ($localLogG -like '*removed partial output*80v2*') 'real run: local log records the 80v2 deletion'
Assert-Equal $true ($localLogG -like '*launched Step B*')     'real run: local log records the Step B launch'
Remove-Item $rootG -Recurse -Force

# --- Scenario H (review Finding C5): a STALE lock, no java running, all tags ---
# --- complete - proves the main loop bypasses an abandoned lock instead of ---
# --- staying blocked for the rest of the ten-day absence after a second crash. ---
$rootH = New-InvokeFixtureRoot
$lockH = Join-Path $rootH 'resume.lock'
Set-Content -LiteralPath $lockH -Value 'stepB' -Encoding Ascii
(Get-Item -LiteralPath $lockH).LastWriteTime = (Get-Date).AddHours(-13)   # older than the 12h default
$carrierH = Join-Path $rootH 'hagrid-output'
$outH = Join-Path $rootH 'hagrid-matsim-output'
New-Item -ItemType Directory -Path $carrierH -Force | Out-Null
New-Item -ItemType Directory -Path $outH -Force | Out-Null
$tagsH = @('60v2','70v2')
foreach ($t in $tagsH) { New-InvokeCarrier $carrierH $prefix $t }
foreach ($t in $tagsH) { New-InvokeRun $outH $prefix $t $suffix $true | Out-Null }
$fixtureH = New-InvokeConfig $rootH $lockH $tagsH $carrierH $outH $prefix $suffix $null

$outputH = (Invoke-ResumeSweep -ConfigPath $fixtureH.ConfigPath -DryRun 6>&1 | Out-String)
Assert-Equal $false ($outputH -like '*resume: blocked*') 'stale lock, no java: NOT reported as blocked (java.exe is the real guard, not lock age)'
Assert-Equal $true  ($outputH -like '*stale*')            'stale lock, no java: the staleness bypass is explicitly logged'
Assert-Equal $true  ($outputH -like '*all tags complete*') 'stale lock, no java: evaluation proceeds normally past the (bypassed) lock'
Assert-Equal $true  (Test-Path -LiteralPath $lockH)        'stale lock dry run: the stale lock file itself is left untouched (DryRun never mutates)'
Remove-Item $rootH -Recurse -Force

# --- Wiring-level config validation (review Finding I2): Invoke-ResumeSweep must ---
# --- itself refuse a broken config, not just the leaf function in isolation. ---
$rootI = New-InvokeFixtureRoot
$badCfgHash = New-ValidResumeCfgHash
$badCfgHash.Remove('Jar')
$badCfgPath = Join-Path $rootI 'resume-config.json'
($badCfgHash | ConvertTo-Json) | Set-Content -Path $badCfgPath -Encoding Ascii
$rcBad = Invoke-ResumeSweep -ConfigPath $badCfgPath -DryRun
Assert-Equal 2 $rcBad 'a config missing a required key: Invoke-ResumeSweep returns exit code 2, not 0-having-done-nothing'
$outputBad = (Invoke-ResumeSweep -ConfigPath $badCfgPath -DryRun 6>&1 | Out-String)
Assert-Equal $true ($outputBad -like '*CONFIG INVALID*') 'a config missing a required key: reports CONFIG INVALID'

$rcMissingFile = Invoke-ResumeSweep -ConfigPath (Join-Path $rootI 'does_not_exist.json') -DryRun
Assert-Equal 2 $rcMissingFile 'a missing config FILE: Invoke-ResumeSweep returns exit code 2'
Remove-Item $rootI -Recurse -Force
}

Remove-Item $tmp -Recurse -Force
if ($script:Failures -gt 0) { Write-Host "`n$($script:Failures) FAILURE(S)"; exit 1 }
Write-Host "`nAll resume tests passed"; exit 0
