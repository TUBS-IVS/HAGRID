# Outbound heartbeat for unattended sweep runs.
# Spec: docs/superpowers/specs/2026-07-31-remote-crash-alerting-design.md
#
# Pings an "alive" check unconditionally, a "progress" check when the newest batch
# log has advanced, and uses an "event" check as a general push channel.
# Dot-sourceable: defining functions must have no side effects.
# ASCII output only - the console codepage is cp1252.

$script:CompletionMarkers = @('batch done', 'BATCH DONE', '_EXIT=')

function Get-NewestLogState {
    param([Parameter(Mandatory=$true)][string]$LogDir)
    if (-not (Test-Path -LiteralPath $LogDir)) { return $null }
    $newest = Get-ChildItem -LiteralPath $LogDir -Filter '*.log' -File -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $newest) { return $null }
    return @{
        Path           = $newest.FullName
        LastWriteTicks = [long]$newest.LastWriteTimeUtc.Ticks
        Length         = [long]$newest.Length
    }
}

function Test-ProgressAdvanced {
    param($Previous, $Current)
    # No current observation means the log vanished - that is not progress.
    if ($null -eq $Current)  { return $false }
    # First ever observation counts as progress so the check starts green.
    if ($null -eq $Previous) { return $true }
    # A new batch writes a new file; the previous one stops growing.
    if ($Previous.Path -ne $Current.Path) { return $true }
    if ([long]$Current.LastWriteTicks -gt [long]$Previous.LastWriteTicks) { return $true }
    if ([long]$Current.Length -gt [long]$Previous.Length) { return $true }
    return $false
}

function Test-BatchComplete {
    param([string[]]$TailLines)
    if ($null -eq $TailLines -or $TailLines.Count -eq 0) { return $false }
    foreach ($line in $TailLines) {
        foreach ($marker in $script:CompletionMarkers) {
            if ($line -like "*$marker*") { return $true }
        }
    }
    return $false
}

function Read-HeartbeatState {
    param([Parameter(Mandatory=$true)][string]$Path)
    $empty = @{ LastLogPath=$null; LastWriteTicks=0L; LastLength=0L;
                CompletionAnnounced=$false; EventPendingRearm=$false }
    if (-not (Test-Path -LiteralPath $Path)) { return $empty }
    try {
        $parsed = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop | ConvertFrom-Json
    } catch {
        return $empty   # corrupt state must not stop the heartbeat
    }
    return @{
        LastLogPath         = $parsed.LastLogPath
        LastWriteTicks      = [long]$parsed.LastWriteTicks
        LastLength          = [long]$parsed.LastLength
        CompletionAnnounced = [bool]$parsed.CompletionAnnounced
        EventPendingRearm   = [bool]$parsed.EventPendingRearm
    }
}

function Write-HeartbeatState {
    param([Parameter(Mandatory=$true)][string]$Path, [Parameter(Mandatory=$true)]$State)
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    ($State | ConvertTo-Json -Compress) | Set-Content -LiteralPath $Path -Encoding Ascii
}
