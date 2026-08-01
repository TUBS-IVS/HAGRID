# Chains Step B after Step A finishes, so a machine does not sit idle between the
# two phases. Without this, Step A completing at ~02:00 would leave the machine
# doing nothing for the rest of a ten-day absence.
#
# Launch this DETACHED (via start_detached.ps1) right after launching Step A.
# It polls Step A's console log, and when that log shows BOTH the batch's final
# marker AND a zero exit, it launches Step B - also detached - and exits.
#
# Deliberately does NOT launch when Step A failed: a Step B batch on incomplete
# carrier sets would burn days producing runs that have to be thrown away.
param(
    [Parameter(Mandatory)][string]$StepALog,
    [Parameter(Mandatory)][string]$DonePattern,
    [Parameter(Mandatory)][string]$StepBBat,
    [Parameter(Mandatory)][string]$StepBLog,
    [Parameter(Mandatory)][string]$WorkDir,
    [Parameter(Mandatory)][string]$StartDetached,
    [Parameter(Mandatory)][string]$OwnLog,
    [string]$SuccessPattern = 'STEPA_EXIT=0',
    [int]$PollSeconds = 120,
    [int]$MaxHours = 24
)

function Write-ChainLog([string]$Message) {
    # Detached process: the console goes nowhere, so this file is the only record
    # of what the chainer decided and when.
    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  ' + $Message
    try {
        $dir = Split-Path -Parent $OwnLog
        if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Add-Content -LiteralPath $OwnLog -Value $line -Encoding Ascii
    } catch { }
}

function Read-LogText([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try { return (Get-Content -LiteralPath $Path -Raw -ErrorAction Stop) } catch { return '' }
}

Write-ChainLog "chainer started: watching '$StepALog' for '$DonePattern' + '$SuccessPattern'; will launch '$StepBBat'"

# STALE-LOG GUARD. This replaces an earlier "is a Step A java.exe still running"
# check, which was dropped for two reasons: the done marker is echoed by the batch
# file AFTER java has exited and its exit code was captured, so it already proves
# the exit; and the process check was machine-global, so any other Step A on the
# box - or, on the dev-PC, a stale match - could stall the chainer indefinitely
# while protecting nothing.
#
# What actually needs guarding is the opposite mistake: pointing the chainer at a
# log from a Step A that finished EARLIER. Then the marker is present on the first
# poll and Step B would launch against whatever carrier sets happen to be on disk.
# So require a TRANSITION: the marker must be absent when we start.
$initial = Read-LogText $StepALog
if ($null -ne $initial -and $initial -like "*$DonePattern*") {
    Write-ChainLog "'$DonePattern' is ALREADY present in '$StepALog' at startup - this is a stale log from an earlier Step A. REFUSING to launch Step B; start the chainer before Step A, or point it at the current log."
    Write-ChainLog 'CHAIN_REFUSED_STALE'
    exit 4
}

$deadline = (Get-Date).AddHours($MaxHours)
while ((Get-Date) -lt $deadline) {

    $text = Read-LogText $StepALog
    if ($null -ne $text) {

        if ($text -like "*$DonePattern*") {
            if ($text -like "*$SuccessPattern*") {
                Write-ChainLog 'Step A complete and successful - launching Step B'
                try {
                    $result = & $StartDetached -Command $StepBBat -LogFile $StepBLog -WorkDir $WorkDir
                    Write-ChainLog "launcher said: $result"
                    Write-ChainLog 'CHAIN_DONE'
                    exit 0
                } catch {
                    Write-ChainLog "FAILED to launch Step B: $($_.Exception.Message)"
                    Write-ChainLog 'CHAIN_FAILED'
                    exit 1
                }
            } else {
                # Step A ended without a zero exit. Stop, loudly, in the log - do not
                # start a multi-day Step B on carrier sets that may be incomplete.
                Write-ChainLog "Step A finished but '$SuccessPattern' is NOT in its log - REFUSING to launch Step B"
                Write-ChainLog 'CHAIN_REFUSED'
                exit 2
            }
        }
    } else {
        Write-ChainLog "Step A log not present yet: $StepALog"
    }

    Start-Sleep -Seconds $PollSeconds
}

Write-ChainLog "timed out after $MaxHours h without seeing '$DonePattern' - Step B NOT launched"
Write-ChainLog 'CHAIN_TIMEOUT'
exit 3
