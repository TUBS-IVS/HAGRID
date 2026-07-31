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
$argTemplate = 'concept=basecase,date=2025-05-13,tag={TAG},maxIter=150,jspritIter=1000,writeDashboard=true'
New-StepBBatch $bat @('70v2','90v2') 'C:\jdk\bin\java.exe' 'C:\repo\app.jar' 'C:\repo\pipeline' $argTemplate
$bytes = [IO.File]::ReadAllBytes($bat)
$text  = [Text.Encoding]::ASCII.GetString($bytes)
$cr = ($text.ToCharArray() | Where-Object { $_ -eq "`r" }).Count
$lf = ($text.ToCharArray() | Where-Object { $_ -eq "`n" }).Count
Assert-Equal $true ($cr -gt 0) 'has CR characters'
Assert-Equal $cr $lf 'CR count equals LF count (proper CRLF, cmd-safe)'
Assert-Equal $true ($text -like '*tag=70v2*') 'first tag present'
Assert-Equal $true ($text -like '*tag=90v2*') 'second tag present'
Assert-Equal $true ($text.IndexOf('tag=70v2') -lt $text.IndexOf('tag=90v2')) 'tags in ascending order'
Assert-Equal $true ($text -like '*RESUME_DONE*') 'completion marker present for the heartbeat to see'
# The template's values must actually reach the generated batch, once per tag - not
# a hand-typed literal that can silently drift from the real sweep config.
$maxIterCount = ([regex]::Matches($text, 'maxIter=150')).Count
Assert-Equal 2 $maxIterCount 'ArgTemplate values (maxIter=150) reach the batch once per tag'
Assert-Equal $false ($text -like '*{TAG}*') '{TAG} placeholder is fully substituted, none left literal'
# ASCIIEncoding.GetBytes() silently replaces any non-ASCII source character with
# '?' BEFORE the byte array exists, so counting bytes above 127 can never observe
# an encoding slip - that assertion cannot fail no matter what. A literal '?' in
# the decoded text is the only observable symptom of a mangled character.
Assert-Equal $false ($text.Contains('?')) 'no non-ASCII source characters were silently replaced with ?'

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

Write-Host ''
Write-Host 'Invoke-ResumeSweep -DryRun (main loop)'
# A previous task in this plan shipped two critical wiring bugs precisely because its
# main loop had no test while every leaf function was green in isolation. -DryRun makes
# decisions and logs them without deleting or launching anything, so it is exercised
# here end-to-end against a real fixture tree and a real JSON config - the only way to
# catch a bug in the WIRING between the already-tested leaf functions.
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

function New-InvokeConfig($Root, $LockPath, $Tags, $CarrierRoot, $OutputRoot, $Prefix, $Suffix, $StepALauncher) {
    # StepALauncher and StartDetached deliberately point at files that do NOT exist.
    # Invoke-ResumeSweep only invokes either one outside of -DryRun; if a future edit
    # to the main loop accidentally invoked them under -DryRun too, "& <missing path>"
    # throws a terminating error under this file's $ErrorActionPreference = 'Stop' and
    # the test fails loudly instead of silently launching something real.
    $cfg = [ordered]@{
        LockPath         = $LockPath
        Tags             = $Tags
        CarrierRoot      = $CarrierRoot
        RunIdPrefix      = $Prefix
        OutputRoot       = $OutputRoot
        Suffix           = $Suffix
        StepALauncher    = $StepALauncher
        ArgTemplate      = 'concept=basecase,date=2025-05-13,tag={TAG},maxIter=150,jspritIter=1000,writeDashboard=true'
        JavaExe          = (Join-Path $Root 'nonexistent_java.exe')
        Jar              = (Join-Path $Root 'nonexistent_app.jar')
        WorkDir          = $Root
        GeneratedBatPath = (Join-Path $Root 'run_resume.bat')
        StartDetached    = (Join-Path $Root 'nonexistent_start_detached.ps1')
        ResumeLog        = (Join-Path $Root 'resume_sweep.log')
        ResumedUrl       = ''
    }
    $configPath = Join-Path $Root 'resume-config.json'
    ($cfg | ConvertTo-Json) | Set-Content -Path $configPath -Encoding Ascii
    return $configPath
}

# Test-CanLaunch (called internally by Invoke-ResumeSweep) also probes for a running
# java.exe on the machine, same as the Test-LockFree section above notes. Scenarios
# A-F below need control over that probe: B-E need to get PAST it (a real java.exe was
# observed running on this dev box during development, which would otherwise wrongly
# block every one of them for a reason unrelated to the code under test), and F needs
# to force it ON to prove the refusal path is actually exercised somewhere.
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
$configA = New-InvokeConfig $rootA $lockA @('60v2') $carrierA $outA $prefix $suffix (Join-Path $rootA 'nonexistent_stepA.bat')

$outputA = (Invoke-ResumeSweep -ConfigPath $configA -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputA -like '*blocked*') 'lock held: reports blocked'
Assert-Equal $true  (Test-Path -LiteralPath $lockA) 'lock held: lock file still present (untouched, not recreated)'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootA 'run_resume.bat')) 'lock held: no batch file written'
Assert-Equal $true  (Test-Path -LiteralPath $dirA) 'lock held: existing output directory untouched'
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
$configB = New-InvokeConfig $rootB $lockB @('60v2','70v2') $carrierB $outB $prefix $suffix (Join-Path $rootB 'nonexistent_stepA.bat')

$outputB = (Invoke-ResumeSweep -ConfigPath $configB -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputB -like '*Step A incomplete*') 'Step A incomplete: reports it would re-run Step A in full'
Assert-Equal $false ($outputB -like '*remaining*') 'Step A incomplete: does NOT proceed to Step B tag selection'
Assert-Equal $false (Test-Path -LiteralPath $lockB) 'Step A incomplete dry run: no lock file created'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootB 'run_resume.bat')) 'Step A incomplete dry run: no batch file written'
Assert-Equal $true  (Test-Path -LiteralPath $dirCompleteB) 'Step A incomplete dry run: unrelated finished-run directory NOT deleted'
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
$configC = New-InvokeConfig $rootC $lockC $tagsC $carrierC $outC $prefix $suffix (Join-Path $rootC 'nonexistent_stepA.bat')

$outputC = (Invoke-ResumeSweep -ConfigPath $configC -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputC -like '*2 tag(s) remaining*') 'multi-remaining: reports the correct remaining count (2)'
Assert-Equal $true  ($outputC -like '*first incomplete = 70v2*') 'multi-remaining: names the FIRST incomplete tag (70v2, not 90v2)'
Assert-Equal $false (Test-Path -LiteralPath $lockC) 'multi-remaining dry run: no lock file created'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootC 'run_resume.bat')) 'multi-remaining dry run: no batch file written'
Assert-Equal $true  (Test-Path -LiteralPath $dir70C) 'multi-remaining dry run: first crash-fragment directory NOT deleted'
Assert-Equal $true  (Test-Path -LiteralPath $dir90C) 'multi-remaining dry run: second crash-fragment directory untouched'
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
$configD = New-InvokeConfig $rootD $lockD $tagsD $carrierD $outD $prefix $suffix (Join-Path $rootD 'nonexistent_stepA.bat')

$outputD = (Invoke-ResumeSweep -ConfigPath $configD -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputD -like '*1 tag(s) remaining*') 'single-remaining: reports count 1'
# If the bug were reintroduced, $first would be the STRING "70v2"[0] = '7', and the
# message would read "first incomplete = 7" instead of "first incomplete = 70v2".
Assert-Equal $true  ($outputD -like '*first incomplete = 70v2*') 'single-remaining: names the full tag "70v2", not its first character'
Assert-Equal $false (Test-Path -LiteralPath $lockD) 'single-remaining dry run: no lock file created'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootD 'run_resume.bat')) 'single-remaining dry run: no batch file written'
Assert-Equal $true  (Test-Path -LiteralPath $dir70D) 'single-remaining dry run: the one crash-fragment directory NOT deleted'
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
$configE = New-InvokeConfig $rootE $lockE $tagsE $carrierE $outE $prefix $suffix (Join-Path $rootE 'nonexistent_stepA.bat')

$outputE = (Invoke-ResumeSweep -ConfigPath $configE -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputE -like '*all tags complete*') 'all complete: reports nothing to do'
Assert-Equal $false (Test-Path -LiteralPath $lockE) 'all complete dry run: no lock file created'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootE 'run_resume.bat')) 'all complete dry run: no batch file written'
# Both tags' own finished-run directories, not a stand-in: if the code ever deleted
# "the first tag" unconditionally instead of only an incomplete one, this is what
# would catch it - proof by execution rather than by there being nothing to delete.
Assert-Equal $true  (Test-Path -LiteralPath $dirsE[0]) 'all complete dry run: first finished-run directory NOT deleted'
Assert-Equal $true  (Test-Path -LiteralPath $dirsE[1]) 'all complete dry run: second finished-run directory NOT deleted'
Remove-Item $rootE -Recurse -Force

# --- Scenario F (Important, Finding 1): java.exe already running, NO lock file ---
# resume_sweep.ps1:80's "Get-Process java" guard is arguably the highest-consequence
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
$configF = New-InvokeConfig $rootF $lockF @('60v2') $carrierF $outF $prefix $suffix (Join-Path $rootF 'nonexistent_stepA.bat')

$outputF = (Invoke-ResumeSweep -ConfigPath $configF -DryRun 6>&1 | Out-String)
Assert-Equal $true  ($outputF -like '*blocked*') 'java running, no lock present: reports blocked (refusal is from the java probe, not the lock)'
Assert-Equal $false (Test-Path -LiteralPath $lockF) 'java running, no lock present: still no lock file created'
Assert-Equal $false (Test-Path -LiteralPath (Join-Path $rootF 'run_resume.bat')) 'java running, no lock present: no batch file written'
Assert-Equal $true  (Test-Path -LiteralPath $dirF) 'java running, no lock present: existing output directory untouched'
Remove-Item $rootF -Recurse -Force
$script:MockJavaRunning = $false
}

Remove-Item $tmp -Recurse -Force
if ($script:Failures -gt 0) { Write-Host "`n$($script:Failures) FAILURE(S)"; exit 1 }
Write-Host "`nAll resume tests passed"; exit 0
