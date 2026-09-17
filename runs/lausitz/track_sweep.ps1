# Live tracker for the DRT fleet-sizing sweep + 150-iter run.
# Run:  right-click -> "Run with PowerShell"
#   or: powershell -ExecutionPolicy Bypass -File track_sweep.ps1
# Refreshes every 20s. Ctrl+C to stop (does NOT stop the runs).

$SP  = "C:\Users\HENDRI~1\AppData\Local\Temp\claude\c--Users-Hendrik-Bimmermann-Documents-GitHub-HAGRID\be7718d5-1ee3-4f20-a576-40ade005ff20\scratchpad\fleet-sweep"

while ($true) {
    Clear-Host
    Write-Host "=== DRT Fleet-Sweep Tracker   $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ===`n"

    $swlog = Join-Path $SP 'sweep.log'
    if (Test-Path $swlog) {
        Write-Host "--- sweep.log (milestones) ---"
        Get-Content $swlog | ForEach-Object { Write-Host "  $_" }
    } else {
        Write-Host "  (sweep.log not found yet)"
    }

    Write-Host "`n--- per-run iteration progress ---"
    $sims = Get-ChildItem (Join-Path $SP '*.sim.log') -ErrorAction SilentlyContinue | Sort-Object LastWriteTime
    if ($sims) {
        foreach ($s in $sims) {
            $last = Select-String -Path $s.FullName -Pattern 'ITERATION \d+ (BEGINS|ENDS)' -ErrorAction SilentlyContinue | Select-Object -Last 1
            $iter = if ($last) { $last.Matches.Value } else { '(loading scenario...)' }
            $age  = [int]((Get-Date) - $s.LastWriteTime).TotalSeconds
            Write-Host ("  {0,-34} {1,-22} (updated {2}s ago)" -f $s.BaseName, $iter, $age)
        }
    } else {
        Write-Host "  (no sim logs yet)"
    }

    $res = Join-Path $SP 'RESULTS.md'
    if (Test-Path $res) {
        Write-Host "`n=== BATCH COMPLETE -- RESULTS.md ===" -ForegroundColor Green
        Get-Content $res | ForEach-Object { Write-Host "  $_" }
    }

    $j = Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.WorkingSet64 -gt 1GB } | Select-Object -First 1
    $jvm = if ($j) { "sim JVM live ($([int]($j.WorkingSet64/1MB)) MB)" } else { "no sim JVM running" }
    Write-Host ("`n[{0}]   refresh 20s | Ctrl+C to close tracker (runs keep going)" -f $jvm)

    Start-Sleep -Seconds 20
}
