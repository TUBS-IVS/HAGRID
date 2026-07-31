# Installs the heartbeat as a SYSTEM Scheduled Task. Idempotent: re-running
# re-deploys the script and replaces the task definition.
# Must run elevated. Run: powershell -NoProfile -ExecutionPolicy Bypass -File install_heartbeat_task.ps1 -ToolsDir C:\Users\<user>\hagrid-tools
param(
    [Parameter(Mandatory=$true)][string]$ToolsDir,
    [string]$TaskName = 'HAGRID-Heartbeat',
    [int]$IntervalMinutes = 5
)
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ToolsDir)) {
    New-Item -ItemType Directory -Path $ToolsDir -Force | Out-Null
    Write-Host "created $ToolsDir"
}

$target = Join-Path $ToolsDir 'heartbeat.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'heartbeat.ps1') -Destination $target -Force
Write-Host "deployed $target"

$configPath = Join-Path $ToolsDir 'hc-config.json'
if (-not (Test-Path -LiteralPath $configPath)) {
    throw "missing $configPath - copy hc-config.template.json there and fill in the UUIDs first"
}

# SYSTEM has no user PATH and no mapped drives, so every path is absolute.
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$target`" `"$configPath`""

$trigger = New-ScheduledTaskTrigger -AtStartup
$trigger.Repetition = (New-ScheduledTaskTrigger -Once -At (Get-Date) `
    -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes) `
    -RepetitionDuration ([TimeSpan]::MaxValue)).Repetition

$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettings -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 10)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force | Out-Null
Write-Host "registered task $TaskName (every $IntervalMinutes min, at startup, as SYSTEM)"

Start-ScheduledTask -TaskName $TaskName
Write-Host 'started once now'
