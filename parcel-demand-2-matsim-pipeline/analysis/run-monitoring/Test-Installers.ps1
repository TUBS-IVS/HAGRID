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
. $resumeInstaller

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

# Review Finding I5: the unsafe wide default must be rejected UNLESS it happens to
# be unambiguous (exactly one matching file) right now.
$wideHcAmbiguous = [PSCustomObject](New-ValidHcCfgHash $hcLogDir)
$wideHcAmbiguous.LogPattern = '*.log'
Set-Content -Path (Join-Path $hcLogDir 'hagrid.log') -Value 'x' -Encoding Ascii
Set-Content -Path (Join-Path $hcLogDir 'stepB_run1.log') -Value 'x' -Encoding Ascii
Assert-Equal $true ((Test-HeartbeatConfigForInstall $wideHcAmbiguous).Count -gt 0) "LogPattern='*.log' with 2 matching files in LogDir is flagged as ambiguous"

$hcLogDirSingle = Join-Path $tmp 'logs_single'
New-Item -ItemType Directory -Path $hcLogDirSingle -Force | Out-Null
Set-Content -Path (Join-Path $hcLogDirSingle 'only_one.log') -Value 'x' -Encoding Ascii
$wideHcUnambiguous = [PSCustomObject](New-ValidHcCfgHash $hcLogDirSingle)
$wideHcUnambiguous.LogPattern = '*.log'
Assert-Equal 0 (Test-HeartbeatConfigForInstall $wideHcUnambiguous).Count "LogPattern='*.log' with exactly 1 matching file in LogDir is allowed through"

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

Remove-Item $tmp -Recurse -Force
if ($script:Failures -gt 0) { Write-Host "`n$($script:Failures) FAILURE(S)"; exit 1 }
Write-Host "`nAll installer tests passed"; exit 0
