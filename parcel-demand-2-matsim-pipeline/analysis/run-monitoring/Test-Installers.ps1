# Tests for install_heartbeat_task.ps1 / install_resume_task.ps1 (review Finding I7:
# "the installers have none at all"). Neither installer's actual Install-*Task
# function is invoked here - that needs admin rights (Register-ScheduledTask) and a
# real config, both out of reach in an automated test. What CAN be tested without
# either, and is tested here:
#   1. Both files parse cleanly.
#   2. Every *-ScheduledTask* cmdlet either installer calls actually exists on this
#      PowerShell version, via the same Test-CmdletsAvailable helper the installers
#      themselves use. This one check would have caught the New-ScheduledTaskSettings
#      cmdlet-name bug (review Finding C1) in one line, before ever registering
#      anything.
#   3. The pure validation logic each installer factors out (Test-HeartbeatConfigForInstall,
#      Test-ResumeLogInvariant) against fixture objects, with no filesystem/network
#      dependency beyond what the functions themselves already require.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File Test-Installers.ps1
$ErrorActionPreference = 'Stop'

$script:Failures = 0
function Assert-Equal($Expected, $Actual, $Name) {
    if ($Expected -eq $Actual) { Write-Host "  PASS $Name" }
    else { Write-Host "  FAIL $Name : expected [$Expected] got [$Actual]"; $script:Failures++ }
}

$heartbeatInstaller = Join-Path $PSScriptRoot 'install_heartbeat_task.ps1'
$resumeInstaller    = Join-Path $PSScriptRoot 'install_resume_task.ps1'

Write-Host 'Parse-check'
foreach ($f in @($heartbeatInstaller, $resumeInstaller)) {
    $errs = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($f, [ref]$null, [ref]$errs)
    Assert-Equal 0 $errs.Count "$(Split-Path $f -Leaf) parses with zero errors"
}

# Dot-sourcing with NO -ToolsDir must not prompt or run anything: both installers'
# top-level param() is deliberately non-Mandatory for exactly this reason, and the
# real work is behind the "$MyInvocation.InvocationName -ne '.'" guard.
. $heartbeatInstaller

Write-Host ''
Write-Host 'Test-ContainsReplacePlaceholder, install_heartbeat_task.ps1 copy (review Finding I2 residual)'
# The original scan only inspected [string] values, so an ARRAY containing a
# REPLACE entry (e.g. resume-config's Tags) passed through unnoticed. Each
# installer duplicates its own copy of this helper (independent deployability,
# same convention as Write-LocalLog); tested here right after THIS file's
# dot-source, before install_resume_task.ps1's identical copy overwrites the
# function of the same name.
Assert-Equal $false (Test-ContainsReplacePlaceholder 'https://hc-ping.com/real-uuid') 'a real URL string is not flagged'
Assert-Equal $true  (Test-ContainsReplacePlaceholder 'https://hc-ping.com/REPLACE-WITH-ALIVE-UUID') 'a placeholder URL string is flagged'
Assert-Equal $true  (Test-ContainsReplacePlaceholder @('30v3', 'REPLACE-WITH-REAL-TAG-2')) 'an array containing a REPLACE entry is flagged (recurses into arrays, not just top-level strings)'
Assert-Equal $false (Test-ContainsReplacePlaceholder @('30v3', '40v3')) 'an array of real values is not flagged'

. $resumeInstaller

Write-Host ''
Write-Host 'Test-ContainsReplacePlaceholder, install_resume_task.ps1 copy (review Finding I2 residual)'
# Same checks again, now exercising resume's own (separately maintained, textually
# identical) copy of the function, which is the one active after this dot-source.
Assert-Equal $false (Test-ContainsReplacePlaceholder 'C:\real\app.jar') 'a real path string is not flagged'
Assert-Equal $true  (Test-ContainsReplacePlaceholder 'REPLACE-WITH-SHADED-JAR-PATH') 'a placeholder path string is flagged'
Assert-Equal $true  (Test-ContainsReplacePlaceholder @('30v3', 'REPLACE-WITH-REAL-TAG-2')) 'an array containing a REPLACE entry is flagged (recurses into arrays, not just top-level strings)'
Assert-Equal $false (Test-ContainsReplacePlaceholder @('30v3', '40v3', '50v3')) 'a full, real tag array is not flagged'

Write-Host ''
Write-Host 'Get-Command smoke test (review Finding I7): every *-ScheduledTask* cmdlet either installer calls must actually exist'
# This is the single check that would have caught C1 (New-ScheduledTaskSettings
# does not exist; the real cmdlet is New-ScheduledTaskSettingsSet) immediately,
# without needing admin rights or a real Register-ScheduledTask call.
$requiredCmdlets = @('New-ScheduledTaskAction','New-ScheduledTaskTrigger','New-ScheduledTaskSettingsSet',
                     'New-ScheduledTaskPrincipal','Register-ScheduledTask','Start-ScheduledTask')
$missing = Test-CmdletsAvailable $requiredCmdlets
Assert-Equal 0 $missing.Count ("all required ScheduledTask cmdlets exist on this machine" + $(if ($missing.Count -gt 0) { " (missing: $($missing -join ', '))" } else { '' }))
# Directly reproduces C1's root cause: the OLD (wrong) cmdlet name must be reported
# as missing by this same mechanism, proving the smoke test actually discriminates.
$oldBrokenName = Test-CmdletsAvailable @('New-ScheduledTaskSettings')
Assert-Equal 1 $oldBrokenName.Count 'the OLD, WRONG cmdlet name (New-ScheduledTaskSettings) is correctly reported as unavailable'

Write-Host ''
Write-Host 'Test-HeartbeatConfigForInstall (install_heartbeat_task.ps1)'
$tmp = Join-Path $env:TEMP ("installtest_" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

function New-ValidHcCfgHash($LogDir) {
    return [ordered]@{
        AliveUrl = 'https://hc-ping.com/aaaa'; ProgressUrl = 'https://hc-ping.com/bbbb'
        SweepFinishedUrl = 'https://hc-ping.com/cccc'; LogDir = $LogDir; LogPattern = 'stepB_*.log'
        StatePath = 'C:\tools\hb-state.json'; LocalLogPath = 'C:\tools\heartbeat.log'
    }
}
$hcLogDir = Join-Path $tmp 'logs'
New-Item -ItemType Directory -Path $hcLogDir -Force | Out-Null

$validHc = [PSCustomObject](New-ValidHcCfgHash $hcLogDir)
Assert-Equal 0 (Test-HeartbeatConfigForInstall $validHc).Count 'a fully-populated, narrow-pattern config has zero problems'

$placeholderHc = [PSCustomObject](New-ValidHcCfgHash $hcLogDir)
$placeholderHc.AliveUrl = 'https://hc-ping.com/REPLACE-WITH-ALIVE-UUID'
Assert-Equal $true ((Test-HeartbeatConfigForInstall $placeholderHc).Count -gt 0) 'an unfilled REPLACE placeholder in a URL is flagged'

$emptyUrlHc = [PSCustomObject](New-ValidHcCfgHash $hcLogDir)
$emptyUrlHc.ProgressUrl = ''
Assert-Equal $true ((Test-HeartbeatConfigForInstall $emptyUrlHc).Count -gt 0) 'an empty required URL is flagged'

# Review Finding I5 (residual): a blacklist of specific wide spellings ('*.log')
# missed equally wide patterns ('*', '*.*', '*log*', '*.log*'). The fixed check
# refuses ANY pattern matching more than one existing file in LogDir, regardless
# of its literal spelling - tested here with several different wide spellings,
# not just the one the original blacklist happened to name.
Set-Content -Path (Join-Path $hcLogDir 'hagrid.log') -Value 'x' -Encoding Ascii
Set-Content -Path (Join-Path $hcLogDir 'stepB_run1.log') -Value 'x' -Encoding Ascii
foreach ($widePattern in @('*.log', '*', '*.*')) {
    $wideHcAmbiguous = [PSCustomObject](New-ValidHcCfgHash $hcLogDir)
    $wideHcAmbiguous.LogPattern = $widePattern
    Assert-Equal $true ((Test-HeartbeatConfigForInstall $wideHcAmbiguous).Count -gt 0) "LogPattern='$widePattern' with 2+ matching files in LogDir is flagged as ambiguous (not a blacklist of spellings - the actual property protected is 'matches more than one file')"
}

$hcLogDirSingle = Join-Path $tmp 'logs_single'
New-Item -ItemType Directory -Path $hcLogDirSingle -Force | Out-Null
Set-Content -Path (Join-Path $hcLogDirSingle 'only_one.log') -Value 'x' -Encoding Ascii
foreach ($widePattern in @('*.log', '*', '*.*')) {
    $wideHcUnambiguous = [PSCustomObject](New-ValidHcCfgHash $hcLogDirSingle)
    $wideHcUnambiguous.LogPattern = $widePattern
    Assert-Equal 0 (Test-HeartbeatConfigForInstall $wideHcUnambiguous).Count "LogPattern='$widePattern' with exactly 1 matching file in LogDir is allowed through (single-match escape hatch)"
}

$hcLogDirEmpty = Join-Path $tmp 'logs_empty'
New-Item -ItemType Directory -Path $hcLogDirEmpty -Force | Out-Null
$wideHcNoMatches = [PSCustomObject](New-ValidHcCfgHash $hcLogDirEmpty)
$wideHcNoMatches.LogPattern = '*.log'
Assert-Equal 0 (Test-HeartbeatConfigForInstall $wideHcNoMatches).Count 'LogPattern with ZERO matching files (e.g. installing before the sweep has started) is allowed through - only ambiguity (more than one) is refused'

Write-Host ''
Write-Host 'Test-PathWritable (review Finding C3 residual: StatePath/LocalLogPath were never probed for writability)'
$writableTarget = Join-Path $tmp 'writable\hb-state.json'
Assert-Equal $true (Test-PathWritable $writableTarget) 'a path under a creatable directory is writable'
Assert-Equal $false (Test-PathWritable '') 'an empty path is not writable'
# A FILE sitting where the parent directory should be: nothing can be created
# underneath it - the same deterministic-failure technique used elsewhere in this
# test suite to simulate an unwritable location without needing to fill a real disk.
$blockerFile = Join-Path $tmp 'blocker_for_writable_test'
Set-Content -Path $blockerFile -Value 'not a directory' -Encoding Ascii
Assert-Equal $false (Test-PathWritable (Join-Path $blockerFile 'hb-state.json')) 'a path whose parent is actually a FILE is not writable'

Write-Host ''
Write-Host 'Test-ResumeLogInvariant (install_resume_task.ps1, review Finding C7)'
$hcCfgForInvariant = [PSCustomObject]@{ LogDir = 'C:\logs'; LogPattern = 'stepB_*.log' }

$matchingResumeCfg = [PSCustomObject]@{ ResumeLog = 'C:\logs\stepB_resume_run.log'; WorkDir = 'C:\work' }
Assert-Equal 0 (Test-ResumeLogInvariant $matchingResumeCfg $hcCfgForInvariant).Count 'ResumeLog inside LogDir and matching LogPattern: zero problems'

$wrongDirResumeCfg = [PSCustomObject]@{ ResumeLog = 'C:\somewhere-else\stepB_resume_run.log'; WorkDir = 'C:\work' }
$wrongDirProblems = Test-ResumeLogInvariant $wrongDirResumeCfg $hcCfgForInvariant
Assert-Equal $true ($wrongDirProblems.Count -gt 0) 'ResumeLog resolving OUTSIDE LogDir is flagged'
Assert-Equal $true (($wrongDirProblems -join ' ') -like '*does not resolve inside LogDir*') 'the directory-mismatch problem names the specific failure'

$wrongPatternResumeCfg = [PSCustomObject]@{ ResumeLog = 'C:\logs\hagrid_full.log'; WorkDir = 'C:\work' }
$wrongPatternProblems = Test-ResumeLogInvariant $wrongPatternResumeCfg $hcCfgForInvariant
Assert-Equal $true ($wrongPatternProblems.Count -gt 0) "ResumeLog's filename not matching LogPattern is flagged"
Assert-Equal $true (($wrongPatternProblems -join ' ') -like '*does not match LogPattern*') 'the pattern-mismatch problem names the specific failure'

# A RELATIVE ResumeLog must resolve against WorkDir before the comparison, not be
# compared as a raw string.
$relativeResumeCfg = [PSCustomObject]@{ ResumeLog = 'stepB_resume_run.log'; WorkDir = 'C:\logs' }
Assert-Equal 0 (Test-ResumeLogInvariant $relativeResumeCfg $hcCfgForInvariant).Count 'a RELATIVE ResumeLog resolves against WorkDir before comparison'

$hcCfgDefaultPattern = [PSCustomObject]@{ LogDir = 'C:\logs'; LogPattern = '' }
$anyLogResumeCfg = [PSCustomObject]@{ ResumeLog = 'C:\logs\whatever.log'; WorkDir = 'C:\work' }
Assert-Equal 0 (Test-ResumeLogInvariant $anyLogResumeCfg $hcCfgDefaultPattern).Count 'an empty LogPattern defaults to *.log, same as Read-HeartbeatConfig'

Write-Host ''
Write-Host 'Test-SystemVisiblePaths (session-scoped paths a SYSTEM task cannot resolve)'
# Found 2026-07-31 while probing the sim-PC over SSH: the dev-PC has T:, S: and X:
# mapped to \\ad.tu-bs.de\share\ivs\*, and a mapped drive letter belongs to ONE
# interactive logon session. A Scheduled Task running as SYSTEM has no such session,
# so every one of those letters is simply absent - Test-Path returns $false with no
# error. A config path on T: would therefore install green and then fail at the next
# boot, silently, which is the exact direction this whole feature exists to prevent.
# Pure function: the local-drive set is injected, so the same assertions run
# identically on the sim-PC (C: only) and the dev-PC (C: + three mappings).
$localDrives = @('C')

$visibleCfg = [PSCustomObject]@{
    WorkDir = 'C:\work'; ResumeLog = 'stepB_resume.log'; Jar = 'build\libs\app.jar'
    LocalLogPath = 'C:\tools\resume_sweep.log'
}
$visibleKeys = @('WorkDir','ResumeLog','Jar','LocalLogPath','NoSuchKey')
Assert-Equal 0 (Test-SystemVisiblePaths $visibleCfg $visibleKeys $localDrives).Count 'all-local config: zero problems (relative paths and absent keys are skipped, not flagged)'

foreach ($mapped in @('T:\hagrid\input', 't:\hagrid\input', 'S:\software\jdk\bin\java.exe')) {
    $mappedCfg = [PSCustomObject]@{ WorkDir = $mapped }
    $mappedProblems = Test-SystemVisiblePaths $mappedCfg @('WorkDir') $localDrives
    Assert-Equal $true ($mappedProblems.Count -gt 0) "a path on mapped drive '$mapped' is flagged (case-insensitive)"
    Assert-Equal $true (($mappedProblems -join ' ') -like '*WorkDir*') 'the problem names the offending config key'
}

$uncCfg = [PSCustomObject]@{ WorkDir = '\\ad.tu-bs.de\share\ivs\temp\hagrid' }
$uncProblems = Test-SystemVisiblePaths $uncCfg @('WorkDir') $localDrives
Assert-Equal $true ($uncProblems.Count -gt 0) 'a UNC path is flagged too - SYSTEM authenticates to a share as the MACHINE account, which has no rights on a user share'

$emptyCfg = [PSCustomObject]@{ WorkDir = ''; ResumeLog = $null }
Assert-Equal 0 (Test-SystemVisiblePaths $emptyCfg @('WorkDir','ResumeLog') $localDrives).Count 'empty and null values are skipped here - the REPLACE/emptiness checks own that failure mode'

$twoBadCfg = [PSCustomObject]@{ WorkDir = 'T:\a'; LocalLogPath = 'X:\b' }
Assert-Equal 2 (Test-SystemVisiblePaths $twoBadCfg @('WorkDir','LocalLogPath') $localDrives).Count 'every offending key is reported, not just the first'

# The single-element-array unroll lesson (review Finding C4): a one-problem result
# must still be an array, or the caller's $problems[0] yields the first CHARACTER of
# the message. .Count alone does NOT catch this - PowerShell gives scalars a .Count of 1.
$oneBad = Test-SystemVisiblePaths ([PSCustomObject]@{ WorkDir = 'T:\a' }) @('WorkDir') $localDrives
Assert-Equal $true ($oneBad -is [array]) 'a ONE-problem result is still an array (not unrolled to a bare string)'

Write-Host ''
Write-Host 'Get-LocalFixedDriveLetters (the impure half, checked against this actual machine)'
$actualLocal = Get-LocalFixedDriveLetters
Assert-Equal $true ($actualLocal -contains 'C') 'C: is reported as locally visible'
# Computed from this machine rather than hardcoded, so the assertion is meaningful on
# the sim-PC (no mappings at all) and the dev-PC (T:, S:, X:) alike.
$networkLetters = @(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=4' -ErrorAction SilentlyContinue |
    ForEach-Object { $_.DeviceID.TrimEnd(':').ToUpperInvariant() })
$leaked = @($actualLocal | Where-Object { $networkLetters -contains $_ })
Assert-Equal 0 $leaked.Count ("no mapped network drive leaks into the local set (this machine's mappings: $(if ($networkLetters.Count) { $networkLetters -join ',' } else { 'none' }))")

Remove-Item $tmp -Recurse -Force
if ($script:Failures -gt 0) { Write-Host "`n$($script:Failures) FAILURE(S)"; exit 1 }
Write-Host "`nAll installer tests passed"; exit 0
