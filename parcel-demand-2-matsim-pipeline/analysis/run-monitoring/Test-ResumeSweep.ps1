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

Write-Host 'Test-StepAComplete'
$carriers = Join-Path $tmp 'hagrid-output'
New-Item -ItemType Directory -Path (Join-Path $carriers "${prefix}_60v2\carriers") -Force | Out-Null
Set-Content -Path (Join-Path $carriers "${prefix}_60v2\carriers\${prefix}_60v2_delivery_carriers_routed.xml") -Value '<carriers/>' -Encoding Ascii
Assert-Equal $true  (Test-StepAComplete $carriers $prefix @('60v2')) 'carrier file present'
Assert-Equal $false (Test-StepAComplete $carriers $prefix @('60v2','70v2')) 'one missing carrier set fails the whole phase'

Write-Host 'New-StepBBatch writes CRLF ASCII'
$bat = Join-Path $tmp 'run_resume.bat'
New-StepBBatch $bat @('70v2','90v2') 'C:\jdk\bin\java.exe' 'C:\repo\app.jar' 'C:\repo\pipeline'
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
$nonAscii = ($bytes | Where-Object { $_ -gt 127 }).Count
Assert-Equal 0 $nonAscii 'no non-ASCII bytes'

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

Remove-Item $tmp -Recurse -Force
if ($script:Failures -gt 0) { Write-Host "`n$($script:Failures) FAILURE(S)"; exit 1 }
Write-Host "`nAll resume tests passed"; exit 0
