# Installs the boot-triggered resume as a SYSTEM Scheduled Task.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File install_resume_task.ps1 -ToolsDir C:\Users\<user>\hagrid-tools
param(
    [Parameter(Mandatory=$true)][string]$ToolsDir,
    [string]$TaskName = 'HAGRID-Resume',
    [int]$DelayMinutes = 2
)
$ErrorActionPreference = 'Stop'

$target = Join-Path $ToolsDir 'resume_sweep.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'resume_sweep.ps1') -Destination $target -Force
Write-Host "deployed $target"

$configPath = Join-Path $ToolsDir 'resume-config.json'
if (-not (Test-Path -LiteralPath $configPath)) {
    throw "missing $configPath - see README for the required keys"
}

$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$target`" `"$configPath`""
$trigger = New-ScheduledTaskTrigger -AtStartup
$trigger.Delay = "PT${DelayMinutes}M"
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettings -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force | Out-Null
Write-Host "registered task $TaskName (at startup +${DelayMinutes}min, as SYSTEM)"
Write-Host 'NOT started now - it fires on the next boot. Verify with -DryRun first.'
