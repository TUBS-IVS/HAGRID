# Remote Crash Alerting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Both sweep machines ping healthchecks.io every 5 minutes; the service alarms on silence and pushes to the user's phone, so a crash during a ~10-day absence is noticed within ~20 minutes instead of at return.

**Architecture:** Outbound dead-man's switch. A PowerShell heartbeat script runs as a SYSTEM Scheduled Task (trigger *At startup*, repeat every 5 min) on the sim-PC and the dev-PC. It unconditionally pings an `alive` check, conditionally pings a `progress` check when the newest batch log has advanced, and reports discrete events by POSTing to a dedicated per-event check's `/fail` endpoint — the meaning lives in the check *name*, because the docs do not promise that an attached body reaches the notification. Part B adds a boot-triggered auto-resume so a colleague pressing the power button is sufficient recovery.

**Tech Stack:** Windows PowerShell 5.1 (heartbeat + resume + installers), Python 3.13 + pytest (log-gap measurement), healthchecks.io free tier, Windows Task Scheduler.

**Spec:** `docs/superpowers/specs/2026-07-31-remote-crash-alerting-design.md`

## Global Constraints

- **Windows PowerShell 5.1**, German locale, on both machines. No PS 7 syntax: no `??`, no `?.`, no ternary, no `&&`/`||` chaining, no `-AsHashtable`.
- **Test framework: none.** Only Pester **3.4.0** is present (Windows-bundled); its assertion syntax differs from modern Pester and installing Pester 5 needs PSGallery plus admin on two machines. Unattended monitoring infrastructure must not carry that dependency. PowerShell tests therefore use a **dependency-free assertion harness** in each `Test-*.ps1` that counts failures and `exit 1`s. Python tests use pytest, matching `analysis/hannover-sweep/`.
- **ASCII only** in all script output. The German console codepage is cp1252 and non-ASCII in output corrupts logs.
- **Never write `.bat` files with Edit/Write** — it strips CRLF and cmd misparses. Use `[IO.File]::WriteAllText` (or `WriteAllLines`) with explicit `` `r`n `` line endings and ASCII encoding. Applies to the generated Step B batch in Task 8.
- **Ping URLs are secrets and this repo is public.** UUIDs live only in `<toolsdir>\hc-config.json`, outside the repo. Only `hc-config.template.json` with placeholder UUIDs is committed. No UUID may appear in any committed file, commit message, or test fixture.
- **Scheduled Tasks run as SYSTEM**: no user `PATH`, no mapped drives (notably no `T:`). Every path in every script must be absolute.
- **Log timestamp format** (measured 2026-07-31): `2026-07-24 21:36:50 INFO  QSim:552 - ...`, i.e. `^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(INFO|WARN|ERROR|DEBUG)`. Seconds resolution, no milliseconds. JVM unified-logging lines (`[0.004s][info][nmt]`) carry no wall clock and must be skipped, not parsed as gaps.

### Machine paths

| | Sim-PC | Dev-PC |
|---|---|---|
| Repo | `C:\Users\Simrechner\Documents\GitHub\HAGRID` | `C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID` |
| Pipeline | `<repo>\parcel-demand-2-matsim-pipeline` | same relative |
| Log dir | `<pipeline>\hagrid-output\logs` | same relative |
| MATSim output | `<pipeline>\hagrid-matsim-output` | same relative |
| Tools dir (deployed, outside repo) | `C:\Users\Simrechner\hagrid-tools` | `C:\Users\Hendrik Bimmermann\hagrid-tools` (create) |
| Access | `ssh sim` | local |

### File structure

All source lives in one new directory, following the `analysis/hannover-sweep/` convention:

`parcel-demand-2-matsim-pipeline/analysis/run-monitoring/`

| File | Responsibility |
|---|---|
| `README.md` | What each file is, how to install, how to verify, spec link |
| `measure_log_gaps.py` | Derive the `progress` grace window from a real batch log |
| `test_measure_log_gaps.py` | pytest fixture test for the gap parser |
| `heartbeat.ps1` | Heartbeat: pure decision functions + `Invoke-Heartbeat` main |
| `Test-Heartbeat.ps1` | Dependency-free tests for the heartbeat's pure functions |
| `hc-config.template.json` | Placeholder config, committed |
| `install_heartbeat_task.ps1` | Creates the SYSTEM Scheduled Task, idempotently |
| `resume_sweep.ps1` | Part B: completion detection, tag selection, batch generation, launch |
| `Test-ResumeSweep.ps1` | Dependency-free tests for the resume decision functions |
| `install_resume_task.ps1` | Part B: creates the At-startup Scheduled Task |

Deployment copies `heartbeat.ps1` and `resume_sweep.ps1` into the machine's tools dir, so the running copy is insulated from repo working-tree changes during run prep (the sim-PC does `git checkout` of individual files between sweeps). Git remains the single source of truth; re-running the installer re-deploys.

---

### Task 1: Close the dev-PC battery sleep path

Verified 2026-07-31: AC standby and hibernate are already `0x0` (never), but the **DC profile still standbys after 1 h** (`0xe10`). This matters because the dev-PC's 2026-07-27 incident was a USB-C power-delivery glitch — an unnoticed AC loss drops the machine to battery, and the Windows standby timer keys off user inactivity, not CPU load, so a running MATSim does not hold it awake.

**Files:** none (system configuration only)

**Interfaces:**
- Consumes: nothing
- Produces: nothing (no code artifact; independent of all other tasks)

- [ ] **Step 1: Record the current DC values**

```powershell
$raw = powercfg /query SCHEME_CURRENT SUB_SLEEP | Out-String
$raw -split "`r?`n" | Where-Object { $_ -match 'Energieeinstellung:|Gleichstromeinstellung' } | ForEach-Object { $_.Trim() }
```

Expected: `Deaktivierung nach` DC = `0x00000e10`, `Ruhezustand nach` DC = `0x7fffffff`.

- [ ] **Step 2: Set both DC timeouts to never**

```powershell
powercfg /change standby-timeout-dc 0
powercfg /change hibernate-timeout-dc 0
powercfg /change disk-timeout-dc 0
```

- [ ] **Step 3: Verify the change took**

```powershell
$raw = powercfg /query SCHEME_CURRENT SUB_SLEEP | Out-String
$raw -split "`r?`n" | Where-Object { $_ -match 'Gleichstromeinstellung' } | ForEach-Object { $_.Trim() }
```

Expected: `Index der aktuellen Gleichstromeinstellung: 0x00000000` for the standby entry.

- [ ] **Step 4: No commit** — system configuration, nothing in the repo changed. Record the result in the task notes for the final memory update (Task 10).

---

### Task 2: Measure the `progress` grace window

The stall threshold must be derived from a real run, not guessed. The quiet candidate is the jsprit routing phase. Guessing here produces a false alarm in the middle of the night.

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/measure_log_gaps.py`
- Test: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/test_measure_log_gaps.py`

**Interfaces:**
- Consumes: nothing
- Produces: `parse_gaps(lines) -> list[tuple[str, int]]` returning `(timestamp_iso, gap_seconds)` pairs, and `summarise(gaps) -> dict` with keys `count`, `max_gap_s`, `p999_gap_s`, `max_gap_at`. Task 4 uses the reported `max_gap_s` to choose the grace value.

- [ ] **Step 1: Fetch the real log to a local working copy**

The 43 MB log with ten complete runs lives on the sim-PC.

```bash
scp sim:'C:/Users/Simrechner/Documents/GitHub/HAGRID/parcel-demand-2-matsim-pipeline/hagrid-output/logs/stepB_weekend_batch.log' "C:/Users/HENDRI~1/AppData/Local/Temp/claude/c--Users-Hendrik-Bimmermann-Documents-GitHub-HAGRID/56e4c2c4-48ea-4b8c-89ae-65f52a6800a8/scratchpad/stepB_weekend_batch.log"
```

Expected: ~43 MB transferred. The log is not committed (too large, and it is run output).

- [ ] **Step 2: Write the failing test**

Create `test_measure_log_gaps.py`:

```python
# -*- coding: utf-8 -*-
"""Fixture test for the batch-log gap parser (pytest)."""
import measure_log_gaps as m

# Real format, measured 2026-07-31. Includes a JVM unified-logging line with no
# wall clock (must be skipped) and a deliberate 3600 s quiet gap.
FIXTURE = [
    "=== STEP B weekend batch start 24.07.2026 13:54:34,70 ===",
    "[0.004s][info][nmt] NMT initialized: summary",
    "2026-07-24 21:36:50 INFO  QSim:552 - SIMULATION AT 13:00:00",
    "2026-07-24 21:36:51 INFO  AbstractQNetsimEngine:356 - SIMULATION AT 14:00:00",
    "[12.5s][info][gc] GC(3) Pause Young",
    "2026-07-24 22:36:51 INFO  Router:755 - jsprit done",
    "2026-07-24 22:36:58 WARN  DashboardGenerator:91 - placeholder",
]


def test_parse_gaps_skips_untimestamped_lines():
    gaps = m.parse_gaps(FIXTURE)
    # 4 timestamped lines -> 3 gaps. The two JVM lines and the batch header are skipped.
    assert [g[1] for g in gaps] == [1, 3600, 7]


def test_summarise_reports_max_and_location():
    s = m.summarise(m.parse_gaps(FIXTURE))
    assert s["count"] == 3
    assert s["max_gap_s"] == 3600
    assert s["max_gap_at"] == "2026-07-24 22:36:51"


def test_parse_gaps_empty_input():
    assert m.parse_gaps([]) == []
    assert m.summarise([]) == {"count": 0, "max_gap_s": 0, "p999_gap_s": 0, "max_gap_at": None}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd parcel-demand-2-matsim-pipeline/analysis/run-monitoring && python -m pytest test_measure_log_gaps.py -v`
Expected: FAIL, `ModuleNotFoundError: No module named 'measure_log_gaps'`.

- [ ] **Step 4: Write the implementation**

Create `measure_log_gaps.py`:

```python
# -*- coding: utf-8 -*-
"""Derive the progress-check grace window from a real MATSim batch log.

The heartbeat treats "newest log file advanced" as proof of progress. To set the
grace window we need the largest LEGITIMATE quiet period in a healthy run - the
jsprit routing phase is the quiet candidate. Anything above that margin is a stall.

Usage:  python measure_log_gaps.py <path-to-batch-log>
"""
import re
import sys
from datetime import datetime

# Measured 2026-07-31 against stepB_weekend_batch.log (10 complete runs).
# JVM unified-logging lines ("[0.004s][info][nmt] ...") carry no wall clock and
# are skipped rather than parsed.
TS_RE = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(?:INFO|WARN|ERROR|DEBUG)")
TS_FMT = "%Y-%m-%d %H:%M:%S"


def parse_gaps(lines):
    """Return [(timestamp_str, gap_seconds), ...] between consecutive timestamped lines."""
    gaps = []
    prev = None
    for line in lines:
        match = TS_RE.match(line)
        if not match:
            continue
        stamp = match.group(1)
        current = datetime.strptime(stamp, TS_FMT)
        if prev is not None:
            delta = int((current - prev).total_seconds())
            if delta >= 0:  # guard against clock steps / interleaved streams
                gaps.append((stamp, delta))
        prev = current
    return gaps


def summarise(gaps):
    """Aggregate gap statistics. p999 excludes single freak values from the decision."""
    if not gaps:
        return {"count": 0, "max_gap_s": 0, "p999_gap_s": 0, "max_gap_at": None}
    values = sorted(g[1] for g in gaps)
    worst = max(gaps, key=lambda g: g[1])
    idx = min(len(values) - 1, int(len(values) * 0.999))
    return {
        "count": len(gaps),
        "max_gap_s": worst[1],
        "p999_gap_s": values[idx],
        "max_gap_at": worst[0],
    }


def main(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        stats = summarise(parse_gaps(handle))
    print("timestamped lines compared : %d" % stats["count"])
    print("largest quiet gap          : %d s (%.1f min) at %s"
          % (stats["max_gap_s"], stats["max_gap_s"] / 60.0, stats["max_gap_at"]))
    print("p99.9 quiet gap            : %d s (%.1f min)"
          % (stats["p999_gap_s"], stats["p999_gap_s"] / 60.0))
    recommended = max(600, int(stats["max_gap_s"] * 20))
    print("RECOMMENDED progress grace : %d s (%.0f min) = max(10 min, 20x observed max)"
          % (recommended, recommended / 60.0))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: python measure_log_gaps.py <path-to-batch-log>")
        sys.exit(2)
    main(sys.argv[1])
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `python -m pytest test_measure_log_gaps.py -v`
Expected: 3 passed.

- [ ] **Step 6: Run it against the real 43 MB log and record the number**

```bash
python measure_log_gaps.py "C:/Users/HENDRI~1/AppData/Local/Temp/claude/c--Users-Hendrik-Bimmermann-Documents-GitHub-HAGRID/56e4c2c4-48ea-4b8c-89ae-65f52a6800a8/scratchpad/stepB_weekend_batch.log"
```

Expected: a `RECOMMENDED progress grace` line. **Write the actual number into the README in Step 7 and use it in Task 4.** Do not proceed with a guessed value. If the observed maximum exceeds 60 min, say so explicitly rather than silently accepting a large grace — a very long legitimate quiet period is itself a finding worth reporting to the user, because it caps how fast a stall can ever be detected.

- [ ] **Step 7: Write the README with the measured value**

Create `README.md`:

```markdown
# Run Monitoring — outbound crash alerting

Spec: `docs/superpowers/specs/2026-07-31-remote-crash-alerting-design.md`

Each sweep machine pings healthchecks.io every 5 min; the service alarms on silence
and pushes to the phone. No VPN, no SSH, no Claude session involved.

- `measure_log_gaps.py` — derives the progress grace window from a real batch log.
  Test: `python -m pytest test_measure_log_gaps.py`.
  **Measured 2026-07-31 against `stepB_weekend_batch.log` (10 runs): largest legitimate
  quiet gap <FILL FROM STEP 6> s; grace set to <FILL FROM STEP 6> s.**
- `heartbeat.ps1` — the heartbeat. Test: `powershell -File Test-Heartbeat.ps1`.
- `install_heartbeat_task.ps1` — creates the SYSTEM Scheduled Task (idempotent).
- `hc-config.template.json` — copy to `<toolsdir>\hc-config.json` and fill in the UUIDs.
  **The real config never enters git; this repo is public and ping URLs are capability URLs.**
- `resume_sweep.ps1` / `install_resume_task.ps1` — Part B, boot-triggered auto-resume.
  Test: `powershell -File Test-ResumeSweep.ps1`.
```

Replace both `<FILL FROM STEP 6>` markers with the measured numbers before committing.

- [ ] **Step 8: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/run-monitoring/measure_log_gaps.py parcel-demand-2-matsim-pipeline/analysis/run-monitoring/test_measure_log_gaps.py parcel-demand-2-matsim-pipeline/analysis/run-monitoring/README.md
git commit -m "feat(monitoring): derive the progress grace window from a real batch log

Measures the largest legitimate quiet gap across ten complete runs instead of
guessing a stall threshold. The jsprit routing phase is the quiet candidate; a
guessed value would have produced a false alarm mid-run."
```

---

### Task 3: Heartbeat decision logic (pure functions, TDD)

The heartbeat's decisions are testable without network or Task Scheduler. Isolating them is what makes this verifiable at all.

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/heartbeat.ps1`
- Test: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/Test-Heartbeat.ps1`

**Interfaces:**
- Consumes: the grace value measured in Task 2 (documentation only; not referenced in code)
- Produces, for Task 4 and Task 5:
  - `Get-NewestLogState([string]$LogDir)` -> hashtable `@{Path=<string>; LastWriteTicks=<long>; Length=<long>}`, or `$null` when the directory holds no `*.log`
  - `Test-ProgressAdvanced($Previous, $Current)` -> `[bool]`
  - `Test-BatchComplete([string[]]$TailLines)` -> `[bool]`
  - `Read-HeartbeatState([string]$Path)` -> hashtable, empty defaults when absent
  - `Write-HeartbeatState([string]$Path, $State)` -> void
  - `Invoke-Heartbeat([string]$ConfigPath)` -> `[int]` exit code, always 0 unless the config is unreadable

- [ ] **Step 1: Write the failing test**

Create `Test-Heartbeat.ps1`:

```powershell
# Dependency-free test harness. Only Pester 3.4.0 is available on these machines
# and modern Pester cannot be assumed, so assertions are hand-rolled.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File Test-Heartbeat.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'heartbeat.ps1')

$script:Failures = 0
function Assert-Equal($Expected, $Actual, $Name) {
    if ($Expected -eq $Actual) { Write-Host "  PASS $Name" }
    else { Write-Host "  FAIL $Name : expected [$Expected] got [$Actual]"; $script:Failures++ }
}

Write-Host 'Test-ProgressAdvanced'
$base = @{ Path = 'a.log'; LastWriteTicks = 1000L; Length = 50L }
Assert-Equal $false (Test-ProgressAdvanced $base $base) 'identical state is not progress'
Assert-Equal $true  (Test-ProgressAdvanced $base @{ Path='a.log'; LastWriteTicks=2000L; Length=50L }) 'newer mtime is progress'
Assert-Equal $true  (Test-ProgressAdvanced $base @{ Path='a.log'; LastWriteTicks=1000L; Length=90L }) 'grown length is progress'
# A new batch writes a NEW log file; the old one stops growing. Treat a path change as progress.
Assert-Equal $true  (Test-ProgressAdvanced $base @{ Path='b.log'; LastWriteTicks=1000L; Length=10L }) 'different log file is progress'
Assert-Equal $true  (Test-ProgressAdvanced $null $base) 'first ever observation is progress'
Assert-Equal $false (Test-ProgressAdvanced $base $null) 'losing the log is not progress'

Write-Host 'Test-BatchComplete'
Assert-Equal $true  (Test-BatchComplete @('=== weekend batch done 27.07.2026 ===')) 'batch done marker'
Assert-Equal $true  (Test-BatchComplete @('SCEN10_EXIT=0')) 'scenario exit marker'
Assert-Equal $true  (Test-BatchComplete @('noise','REDO70_EXIT=1','noise')) 'exit marker anywhere in tail'
Assert-Equal $false (Test-BatchComplete @('2026-07-24 21:36:50 INFO  QSim:552 - SIMULATION AT 13:00:00')) 'ordinary log line'
Assert-Equal $false (Test-BatchComplete @()) 'empty tail'

Write-Host 'Get-NewestLogState'
$tmp = Join-Path $env:TEMP ("hbtest_" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
Assert-Equal $null (Get-NewestLogState $tmp) 'empty dir yields null'
Set-Content -Path (Join-Path $tmp 'old.log') -Value 'x' -Encoding Ascii
Start-Sleep -Milliseconds 1100
Set-Content -Path (Join-Path $tmp 'new.log') -Value 'yy' -Encoding Ascii
$state = Get-NewestLogState $tmp
Assert-Equal 'new.log' (Split-Path $state.Path -Leaf) 'picks the newest log'
Set-Content -Path (Join-Path $tmp 'ignored.txt') -Value 'zzz' -Encoding Ascii
Assert-Equal 'new.log' (Split-Path (Get-NewestLogState $tmp).Path -Leaf) 'ignores non-log files'

Write-Host 'State round-trip'
$statePath = Join-Path $tmp 'state.json'
$empty = Read-HeartbeatState $statePath
Assert-Equal $null $empty.LastLogPath 'absent state file yields empty defaults'
Write-HeartbeatState $statePath @{ LastLogPath='a.log'; LastWriteTicks=1234L; LastLength=7L; CompletionAnnounced=$true; EventPendingRearm=$false }
$loaded = Read-HeartbeatState $statePath
Assert-Equal 'a.log' $loaded.LastLogPath 'path round-trips'
Assert-Equal 1234 $loaded.LastWriteTicks 'ticks round-trip'
Assert-Equal $true $loaded.CompletionAnnounced 'bool round-trips'

Remove-Item $tmp -Recurse -Force
if ($script:Failures -gt 0) { Write-Host "`n$($script:Failures) FAILURE(S)"; exit 1 }
Write-Host "`nAll heartbeat tests passed"; exit 0
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd parcel-demand-2-matsim-pipeline/analysis/run-monitoring && powershell -NoProfile -ExecutionPolicy Bypass -File Test-Heartbeat.ps1`
Expected: FAIL — `heartbeat.ps1` does not exist, dot-sourcing throws.

- [ ] **Step 3: Write the implementation**

Create `heartbeat.ps1`:

```powershell
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File Test-Heartbeat.ps1`
Expected: all PASS, `All heartbeat tests passed`, exit 0.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/run-monitoring/heartbeat.ps1 parcel-demand-2-matsim-pipeline/analysis/run-monitoring/Test-Heartbeat.ps1
git commit -m "feat(monitoring): heartbeat decision logic with dependency-free tests

Pure functions for progress detection, completion-marker detection and state
persistence, so the heartbeat's decisions are testable without network or Task
Scheduler. Hand-rolled assertions because only Pester 3.4.0 is present and
unattended infrastructure should not depend on installing Pester 5."
```

---

### Task 4: Heartbeat transport, main loop and config

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/heartbeat.ps1` (append transport + main)
- Modify: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/Test-Heartbeat.ps1` (append config tests)
- Create: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/hc-config.template.json`

**Interfaces:**
- Consumes: `Get-NewestLogState`, `Test-ProgressAdvanced`, `Test-BatchComplete`, `Read-HeartbeatState`, `Write-HeartbeatState` from Task 3; the grace number from Task 2
- Produces: `Send-HealthcheckPing([string]$BaseUrl, [string]$Suffix, [string]$Body)` -> `[bool]`; `Read-HeartbeatConfig([string]$Path)` -> hashtable with keys `AliveUrl`, `ProgressUrl`, `SweepFinishedUrl`, `LogDir`, `StatePath`, `LocalLogPath`; `Invoke-Heartbeat([string]$ConfigPath)` -> `[int]`. Task 5 invokes `Invoke-Heartbeat`.

- [ ] **Step 1: Write the failing test (append to `Test-Heartbeat.ps1`, before the final failure check)**

```powershell
Write-Host 'Read-HeartbeatConfig'
$cfgPath = Join-Path $tmp 'cfg.json'
@'
{
  "AliveUrl":         "https://hc-ping.com/aaaa-1111",
  "ProgressUrl":      "https://hc-ping.com/bbbb-2222",
  "SweepFinishedUrl": "https://hc-ping.com/cccc-3333",
  "LogDir":      "C:\\logs",
  "StatePath":   "C:\\tools\\hb-state.json",
  "LocalLogPath":"C:\\tools\\heartbeat.log"
}
'@ | Set-Content -LiteralPath $cfgPath -Encoding Ascii
$cfg = Read-HeartbeatConfig $cfgPath
Assert-Equal 'https://hc-ping.com/aaaa-1111' $cfg.AliveUrl 'alive url parsed'
Assert-Equal 'C:\logs' $cfg.LogDir 'log dir parsed'
$threw = $false
try { Read-HeartbeatConfig (Join-Path $tmp 'missing.json') } catch { $threw = $true }
Assert-Equal $true $threw 'missing config throws (only fatal condition)'

Write-Host 'Send-HealthcheckPing is failure-tolerant'
# Unroutable host: must return $false and must NOT throw - a network blip may not
# kill the heartbeat, that is what the grace window is for.
Assert-Equal $false (Send-HealthcheckPing 'https://127.0.0.1:9' '' '') 'unreachable endpoint returns false, no throw'
Assert-Equal $false (Send-HealthcheckPing '' '' '') 'empty url returns false'
```

Note the ordering constraint: this block must sit **above** the `Remove-Item $tmp` line, because it uses `$tmp`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File Test-Heartbeat.ps1`
Expected: FAIL — `Read-HeartbeatConfig` / `Send-HealthcheckPing` not recognized.

- [ ] **Step 3: Write the implementation (append to `heartbeat.ps1`)**

```powershell
function Read-HeartbeatConfig {
    param([Parameter(Mandatory=$true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "heartbeat config not found: $Path"
    }
    $parsed = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop | ConvertFrom-Json
    return @{
        AliveUrl     = [string]$parsed.AliveUrl
        ProgressUrl  = [string]$parsed.ProgressUrl
        SweepFinishedUrl = [string]$parsed.SweepFinishedUrl
        LogDir       = [string]$parsed.LogDir
        StatePath    = [string]$parsed.StatePath
        LocalLogPath = [string]$parsed.LocalLogPath
    }
}

function Send-HealthcheckPing {
    param([string]$BaseUrl, [string]$Suffix = '', [string]$Body = '')
    if ([string]::IsNullOrWhiteSpace($BaseUrl)) { return $false }
    $url = $BaseUrl.TrimEnd('/') + $Suffix
    try {
        if ([string]::IsNullOrEmpty($Body)) {
            Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20 -Method Get | Out-Null
        } else {
            Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20 -Method Post -Body $Body | Out-Null
        }
        return $true
    } catch {
        return $false   # never throw: a blip must not kill the heartbeat
    }
}

function Write-LocalLog {
    param([string]$Path, [string]$Message)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    try { Add-Content -LiteralPath $Path -Value "$stamp $Message" -Encoding Ascii } catch { }
}

function Invoke-Heartbeat {
    param([Parameter(Mandatory=$true)][string]$ConfigPath)
    $cfg = Read-HeartbeatConfig $ConfigPath          # only fatal condition
    $state = Read-HeartbeatState $cfg.StatePath

    # 1. Unconditional liveness. Also self-monitoring: if this script or its task
    #    dies while the machine lives, the alive check stops and alarms.
    $aliveOk = Send-HealthcheckPing $cfg.AliveUrl '' ''

    # 2. Progress.
    $current = Get-NewestLogState $cfg.LogDir
    $previous = $null
    if ($state.LastLogPath) {
        $previous = @{ Path = $state.LastLogPath
                       LastWriteTicks = $state.LastWriteTicks
                       LastLength = $state.LastLength }
    }
    $advanced = Test-ProgressAdvanced $previous $current

    # 3. Completion. A finished sweep must not decay into a false stall alarm, so
    #    from the completion marker onward progress is pinged unconditionally.
    $tail = @()
    if ($current) {
        try { $tail = Get-Content -LiteralPath $current.Path -Tail 40 -ErrorAction Stop } catch { $tail = @() }
    }
    $complete = Test-BatchComplete $tail

    if ($advanced -or $complete) {
        Send-HealthcheckPing $cfg.ProgressUrl '' '' | Out-Null
    }

    # The meaning lives in the CHECK NAME (sweep-finished), not in the body: the docs
    # do not state that an attached body reaches the notification. The body is sent
    # anyway because it is useful in the check's Events list.
    if ($complete -and -not $state.CompletionAnnounced) {
        $name = if ($current) { Split-Path $current.Path -Leaf } else { 'unknown' }
        Send-HealthcheckPing $cfg.SweepFinishedUrl '/fail' "$env:COMPUTERNAME sweep batch finished ($name)" | Out-Null
        $state.CompletionAnnounced = $true
        $state.EventPendingRearm   = $true
        Write-LocalLog $cfg.LocalLogPath 'event: batch completion announced'
    } elseif ($state.EventPendingRearm) {
        # Re-arm on the NEXT cycle, not immediately, so the down-notification is
        # never racing an up-notification.
        Send-HealthcheckPing $cfg.SweepFinishedUrl '' '' | Out-Null
        $state.EventPendingRearm = $false
        Write-LocalLog $cfg.LocalLogPath 'sweep-finished check re-armed'
    }

    if ($current) {
        $state.LastLogPath    = $current.Path
        $state.LastWriteTicks = $current.LastWriteTicks
        $state.LastLength     = $current.Length
    }
    Write-HeartbeatState $cfg.StatePath $state

    Write-LocalLog $cfg.LocalLogPath ("alive=$aliveOk advanced=$advanced complete=$complete")
    return 0
}

# Entry point when invoked as a script (not dot-sourced by the tests).
if ($MyInvocation.InvocationName -ne '.' -and $args.Count -ge 1) {
    exit (Invoke-Heartbeat $args[0])
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File Test-Heartbeat.ps1`
Expected: all PASS, exit 0. The unreachable-endpoint assertion may take a few seconds.

- [ ] **Step 5: Create the config template**

Create `hc-config.template.json`. Placeholder UUIDs only — the real file never enters git.

```json
{
  "_comment": "Copy to <toolsdir>\\hc-config.json and fill in the real UUIDs. NEVER commit the filled copy: this repo is public and a ping URL is a capability URL.",
  "AliveUrl": "https://hc-ping.com/REPLACE-WITH-ALIVE-UUID",
  "ProgressUrl": "https://hc-ping.com/REPLACE-WITH-PROGRESS-UUID",
  "SweepFinishedUrl": "https://hc-ping.com/REPLACE-WITH-SWEEP-FINISHED-UUID",
  "LogDir": "C:\\Users\\REPLACE\\Documents\\GitHub\\HAGRID\\parcel-demand-2-matsim-pipeline\\hagrid-output\\logs",
  "StatePath": "C:\\Users\\REPLACE\\hagrid-tools\\hb-state.json",
  "LocalLogPath": "C:\\Users\\REPLACE\\hagrid-tools\\heartbeat.log"
}
```

- [ ] **Step 6: Verify no real UUID is about to be committed**

```bash
git diff --cached; git status --short
```
Then explicitly grep the staged content:
```bash
git diff --cached | grep -iE "hc-ping\.com/[0-9a-f]{8}-" && echo "ABORT: real UUID staged" || echo "clean: no real UUIDs staged"
```
Expected: `clean: no real UUIDs staged`.

- [ ] **Step 7: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/run-monitoring/heartbeat.ps1 parcel-demand-2-matsim-pipeline/analysis/run-monitoring/Test-Heartbeat.ps1 parcel-demand-2-matsim-pipeline/analysis/run-monitoring/hc-config.template.json
git commit -m "feat(monitoring): heartbeat transport, main loop and config template

Ping transport never throws - a network blip must not kill the heartbeat, that
is what the grace window is for. A finished sweep pings progress unconditionally
so completion does not decay into a false stall alarm, and the event check is
re-armed on the next cycle rather than immediately to avoid racing the
down-notification. Only placeholder UUIDs are committed; the repo is public."
```

---

### Task 5: Create the healthchecks.io checks and install the task on the dev-PC

The dev-PC is the safe place to shake this out: its loss is already accepted, and it is local so iteration is fast.

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/install_heartbeat_task.ps1`
- Create (not committed): `C:\Users\Hendrik Bimmermann\hagrid-tools\hc-config.json`

**Interfaces:**
- Consumes: `Invoke-Heartbeat` from Task 4; the grace value from Task 2
- Produces: an installed Scheduled Task named `HAGRID-Heartbeat`; a deployed `<toolsdir>\heartbeat.ps1`. Task 6 uses the same installer on the sim-PC.

- [ ] **Step 1: User creates the eight checks in the healthchecks.io UI**

This step is the user's; it needs their account. Exact settings — eight checks, "Simple" schedule:

| Name | Period | Grace | Alarm after | Purpose |
|---|---|---|---|---|
| `sim-alive` | 5 min | 15 min | ~20 min | liveness |
| `sim-progress` | **15 min** | **30 min** | **~45 min** | run is advancing |
| `sim-sweep-finished` | 30 days | 1 day | immediate | batch completed normally |
| `sim-resumed-after-boot` | 30 days | 1 day | immediate | Part B fired |
| `dev-alive` | 5 min | 15 min | ~20 min | liveness |
| `dev-progress` | **30 min** | **60 min** | **~90 min** | run is advancing |
| `dev-sweep-finished` | 30 days | 1 day | immediate | batch completed normally |
| `dev-resumed-after-boot` | 30 days | 1 day | immediate | Part B fired |

**Why the two machines differ (user decision 2026-07-31, from the Task 2 measurement):** across 293,511 timestamped lines spanning ten complete runs and ~70 h, the largest legitimate quiet gap on the **sim-PC** was **81 s** (Step A: 38 s; only 22 gaps exceeded 60 s at all). The sim-PC threshold is therefore set from evidence measured on that exact machine, giving a ~22× margin. The **dev-PC has never run a Hannover scenario**, has 63.5 GB against the sim-PC's 128 GB and thus a tighter heap where longer GC pauses are plausible but unmeasured — so its threshold is deliberately twice as generous. Because these are separate checks, the asymmetry is free. Tighten `dev-progress` to match once the dev-PC has produced its first Hannover batch log.

This replaces the spec's original provisional 90–120 min, which rested on the assumption that the jsprit phase is quiet. It is not — it logs continuously.

**Why one check per event instead of one generic `event` check:** healthchecks.io logs an attached POST body in the check's Events list, but the documentation nowhere states the body is included in the outgoing notification (verified 2026-07-31). Message text in the body would therefore have rested on undocumented behaviour. Encoding the meaning in the **check name** puts it in the notification subject, which is guaranteed. The body is still sent — useful when pulling the dashboard — but nothing depends on it.

The two event checks per machine use a long period so they never alarm on their own; they fire only when a script POSTs to `/fail`. If the UI offers a per-integration down/up toggle, disabling "up" for these four suppresses the recovery message that follows re-arming; the docs describe no such toggle and the noise is one to three messages across the whole absence, so this is cosmetic. Attach email to all eight; attach Signal too if set up.

Collect the eight ping URLs. **They go straight into the config files, never into git, never into a commit message, never into chat if avoidable.**

- [ ] **Step 2: Write the installer**

Create `install_heartbeat_task.ps1`:

```powershell
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
```

`-AllowStartIfOnBatteries` and `-DontStopIfGoingOnBatteries` matter on the dev-PC notebook: without them the task silently stops the moment the machine drops to battery, which is exactly the scenario Task 1 addresses.

- [ ] **Step 3: Create the real config on the dev-PC**

```powershell
$tools = 'C:\Users\Hendrik Bimmermann\hagrid-tools'
New-Item -ItemType Directory -Path $tools -Force | Out-Null
Copy-Item 'parcel-demand-2-matsim-pipeline\analysis\run-monitoring\hc-config.template.json' (Join-Path $tools 'hc-config.json')
```

Then edit `C:\Users\Hendrik Bimmermann\hagrid-tools\hc-config.json`: insert the three `dev-*` URLs and set

- `LogDir` = `C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\parcel-demand-2-matsim-pipeline\hagrid-output\logs`
- `StatePath` = `C:\Users\Hendrik Bimmermann\hagrid-tools\hb-state.json`
- `LocalLogPath` = `C:\Users\Hendrik Bimmermann\hagrid-tools\heartbeat.log`

- [ ] **Step 4: Dry-run the heartbeat by hand before involving Task Scheduler**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "parcel-demand-2-matsim-pipeline\analysis\run-monitoring\heartbeat.ps1" "C:\Users\Hendrik Bimmermann\hagrid-tools\hc-config.json"
Get-Content 'C:\Users\Hendrik Bimmermann\hagrid-tools\heartbeat.log' -Tail 3
```

Expected: a log line with `alive=True`. Confirm `dev-alive` has turned green in the healthchecks UI.

- [ ] **Step 5: Install the task (elevated)**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "parcel-demand-2-matsim-pipeline\analysis\run-monitoring\install_heartbeat_task.ps1" -ToolsDir 'C:\Users\Hendrik Bimmermann\hagrid-tools'
```

Expected: `registered task HAGRID-Heartbeat`, `started once now`.

- [ ] **Step 6: Verify the task actually runs as SYSTEM and keeps running**

```powershell
Get-ScheduledTask -TaskName 'HAGRID-Heartbeat' | Select-Object State, @{n='User';e={$_.Principal.UserId}} | Format-List
Get-ScheduledTaskInfo -TaskName 'HAGRID-Heartbeat' | Select-Object LastRunTime, LastTaskResult, NextRunTime | Format-List
```

Expected: `State=Ready`, `User=SYSTEM`, `LastTaskResult=0`. Then wait past one interval and confirm the local log grew by a second line — proving the repetition works, not just the manual start.

- [ ] **Step 7: Commit the installer**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/run-monitoring/install_heartbeat_task.ps1
git commit -m "feat(monitoring): idempotent SYSTEM Scheduled Task installer

Runs as SYSTEM with an At-startup trigger plus 5-minute repetition, so the
heartbeat survives reboots with no login - the property the previous
Claude-session-bound and Cygwin-bound listeners lacked. Battery flags are set
because the dev-PC is a notebook and a task that stops on battery would go
silent in exactly the scenario worth catching."
```

---

### Task 6: Provoke every alarm path and confirm arrival on the phone

An untested alarm is not an alarm. Each path is provoked deliberately, and **arrival on the phone** is what counts — not the fact that something was sent.

**Files:** none (verification only)

**Interfaces:**
- Consumes: the installed dev-PC task from Task 5
- Produces: a verified alerting chain; findings recorded for Task 10

- [ ] **Step 1: Verify the event push end-to-end**

```powershell
$cfg = Get-Content 'C:\Users\Hendrik Bimmermann\hagrid-tools\hc-config.json' -Raw | ConvertFrom-Json
Invoke-WebRequest -Uri ($cfg.SweepFinishedUrl.TrimEnd('/') + '/fail') -Method Post -UseBasicParsing -TimeoutSec 20 -Body "TEST from $env:COMPUTERNAME - alerting chain check" | Select-Object StatusCode
```

Expected: `200`. **Confirm the message arrives on the phone** and that the check *name* (`dev-sweep-finished`) is recognisable in it — that is what carries the meaning. Note separately whether the body text also appears: the docs do not promise it, so if it does, record that as a bonus rather than relying on it. Then re-arm: `Invoke-WebRequest -Uri $cfg.SweepFinishedUrl -UseBasicParsing`.

- [ ] **Step 2: Verify the `alive` alarm**

```powershell
Stop-ScheduledTask -TaskName 'HAGRID-Heartbeat' -ErrorAction SilentlyContinue
Disable-ScheduledTask -TaskName 'HAGRID-Heartbeat'
```

Wait past period + grace (5 + 15 = ~20 min). Expected: `dev-alive` goes down, alarm arrives on the phone. Then:

```powershell
Enable-ScheduledTask -TaskName 'HAGRID-Heartbeat'
Start-ScheduledTask -TaskName 'HAGRID-Heartbeat'
```

Expected: recovery notification, check back to green.

- [ ] **Step 3: Verify the `progress` alarm without waiting hours**

The real grace is long (Task 2). Waiting it out is not practical, so temporarily shorten it: in the healthchecks UI set `dev-progress` grace to **5 min**. Then make the log stall — point the config at a directory whose newest `.log` does not change:

```powershell
$tools = 'C:\Users\Hendrik Bimmermann\hagrid-tools'
$stall = Join-Path $tools 'stalltest'
New-Item -ItemType Directory -Path $stall -Force | Out-Null
Set-Content (Join-Path $stall 'frozen.log') -Value 'no progress here' -Encoding Ascii
# temporarily repoint LogDir
$c = Get-Content (Join-Path $tools 'hc-config.json') -Raw | ConvertFrom-Json
$c.LogDir = $stall
($c | ConvertTo-Json) | Set-Content (Join-Path $tools 'hc-config.json') -Encoding Ascii
Remove-Item (Join-Path $tools 'hb-state.json') -ErrorAction SilentlyContinue
```

Run the heartbeat twice, a minute apart. The first run records the frozen log (first observation counts as progress); the second must not ping progress. Expected after ~10 min: `dev-progress` goes down, alarm on the phone.

- [ ] **Step 4: Restore the real configuration**

```powershell
$tools = 'C:\Users\Hendrik Bimmermann\hagrid-tools'
$c = Get-Content (Join-Path $tools 'hc-config.json') -Raw | ConvertFrom-Json
$c.LogDir = 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\parcel-demand-2-matsim-pipeline\hagrid-output\logs'
($c | ConvertTo-Json) | Set-Content (Join-Path $tools 'hc-config.json') -Encoding Ascii
Remove-Item (Join-Path $tools 'hb-state.json') -ErrorAction SilentlyContinue
Remove-Item (Join-Path $tools 'stalltest') -Recurse -Force
Start-ScheduledTask -TaskName 'HAGRID-Heartbeat'
```

**Reset the `dev-progress` grace in the UI to the Task 2 value.** Forgetting this leaves a permanently false-alarming check — confirm the UI shows the real number before moving on.

- [ ] **Step 5: Verify reboot survival**

Reboot the dev-PC. After it comes back — **without logging into anything** — confirm from the healthchecks UI that `dev-alive` keeps ticking, and that `Get-ScheduledTaskInfo -TaskName 'HAGRID-Heartbeat'` shows a `LastRunTime` after the boot.

- [ ] **Step 6: Confirm each notification channel independently**

If both email and Signal are configured, confirm a test message arrived over **each** channel. A single channel verified twice is one channel.

- [ ] **Step 7: No commit** — verification only. Record outcomes for Task 10.

---

### Task 7: Deploy and verify on the sim-PC

The sim-PC is the machine that actually matters. It is remote, and a Maven test run may be in progress — the heartbeat must not disturb it.

**Files:** none new (deploys Task 4/5 artifacts)

**Interfaces:**
- Consumes: `install_heartbeat_task.ps1`, `heartbeat.ps1`, verified behaviour from Task 6
- Produces: an installed `HAGRID-Heartbeat` task on the sim-PC

- [ ] **Step 1: Push the branch and pull on the sim-PC**

```bash
git push origin hendrik
ssh sim "git -C 'C:/Users/Simrechner/Documents/GitHub/HAGRID' pull"
```

Expected: fast-forward. If the sim-PC working tree is dirty from run prep, do not discard anything — pull only, and report any conflict rather than resolving it unilaterally.

- [ ] **Step 2: Create the sim-PC config**

Use the base64 `-EncodedCommand` pattern for anything with inner quotes; plain `ssh sim '...'` eats them (see `reference_sim_pc` memory). Write `C:\Users\Simrechner\hagrid-tools\hc-config.json` with the three `sim-*` URLs and:

- `LogDir` = `C:\Users\Simrechner\Documents\GitHub\HAGRID\parcel-demand-2-matsim-pipeline\hagrid-output\logs`
- `StatePath` = `C:\Users\Simrechner\hagrid-tools\hb-state.json`
- `LocalLogPath` = `C:\Users\Simrechner\hagrid-tools\heartbeat.log`

- [ ] **Step 3: Dry-run by hand**

Run `heartbeat.ps1` once with that config over SSH, then tail the local log.
Expected: `alive=True`; `sim-alive` green in the UI. Note that `advanced` will likely be `True` on the first observation by design.

- [ ] **Step 4: Install the task**

Run `install_heartbeat_task.ps1 -ToolsDir 'C:\Users\Simrechner\hagrid-tools'` over SSH, elevated.
Expected: `registered task HAGRID-Heartbeat`, `started once now`.

- [ ] **Step 5: Confirm the running Maven job was not disturbed**

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select-Object ProcessId, CreationDate
```

Expected: the pre-existing java PID is still present with its original creation time. The 1d test run must be untouched — the heartbeat only reads files and makes outbound HTTPS calls.

- [ ] **Step 6: Verify repetition and the SYSTEM principal remotely**

```powershell
Get-ScheduledTask -TaskName 'HAGRID-Heartbeat' | Select-Object State, @{n='User';e={$_.Principal.UserId}}
Get-ScheduledTaskInfo -TaskName 'HAGRID-Heartbeat' | Select-Object LastRunTime, LastTaskResult
```

Expected: `Ready`, `SYSTEM`, `LastTaskResult=0`. Wait past one interval and confirm a second line in the local log.

- [ ] **Step 7: Fire one `event` push from the sim-PC and confirm it reaches the phone**

This proves the whole chain from the machine that matters, not just from the dev-PC. Re-arm afterwards.

- [ ] **Step 8: No commit** — deployment only.

---

### Task 8: Part B — resume decision logic (TDD)

Optional and separately switchable. Only needed if a colleague's power-button press should be sufficient recovery.

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/resume_sweep.ps1`
- Test: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/Test-ResumeSweep.ps1`

**Interfaces:**
- Consumes: nothing from earlier tasks (independent module)
- Produces:
  - `Test-TagComplete([string]$OutputRoot, [string]$RunIdPrefix, [string]$Tag, [string]$Suffix)` -> `[bool]`
  - `Select-RemainingTags([string]$OutputRoot, [string]$RunIdPrefix, [string[]]$Tags, [string]$Suffix)` -> `[string[]]`
  - `Test-StepAComplete([string]$CarrierRoot, [string]$RunIdPrefix, [string[]]$Tags)` -> `[bool]`
  - `New-StepBBatch([string]$Path, [string[]]$Tags, [string]$JavaExe, [string]$Jar, [string]$WorkDir)` -> void, writes CRLF ASCII
  - `Test-LockFree([string]$LockPath)` -> `[bool]` — pure, no system probe
  - `Test-CanLaunch([string]$LockPath)` -> `[bool]` — `Test-LockFree` AND no running `java.exe`

- [ ] **Step 1: Write the failing test**

Create `Test-ResumeSweep.ps1`:

```powershell
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File Test-ResumeSweep.ps1`
Expected: FAIL — `resume_sweep.ps1` does not exist.

- [ ] **Step 3: Write the implementation**

Create `resume_sweep.ps1`:

```powershell
# Boot-triggered sweep resume. The only intervention available while the user is
# away is a colleague pressing the power button, so everything else must be
# automatic and triggered by the boot itself.
# Spec: docs/superpowers/specs/2026-07-31-remote-crash-alerting-design.md
# Dot-sourceable: defining functions must have no side effects. ASCII output only.

function Test-TagComplete {
    param([string]$OutputRoot, [string]$RunIdPrefix, [string]$Tag, [string]$Suffix)
    # Measured 2026-07-31: a crashed run leaves its output directory behind (70v2:
    # 214 files, no dashboard) while a finished run has one (832 files, 1 dashboard).
    # Directory existence is therefore worthless; dashboard existence is the test.
    $dir = Join-Path $OutputRoot ("{0}_{1}{2}" -f $RunIdPrefix, $Tag, $Suffix)
    $analysis = Join-Path $dir 'analysis'
    if (-not (Test-Path -LiteralPath $analysis)) { return $false }
    $found = @(Get-ChildItem -LiteralPath $analysis -Filter 'HAGRID_Dashboard_*.html' -File -ErrorAction SilentlyContinue)
    return ($found.Count -gt 0)
}

function Select-RemainingTags {
    param([string]$OutputRoot, [string]$RunIdPrefix, [string[]]$Tags, [string]$Suffix)
    $remaining = @()
    foreach ($tag in $Tags) {
        if (-not (Test-TagComplete $OutputRoot $RunIdPrefix $tag $Suffix)) { $remaining += $tag }
    }
    return $remaining
}

function Test-StepAComplete {
    param([string]$CarrierRoot, [string]$RunIdPrefix, [string[]]$Tags)
    # Step A is all-or-nothing on purpose: the tag list is a compiled constant, so a
    # partial re-run would need a source edit plus a rebuild - far too fragile for an
    # unattended boot script. Re-running all of Step A costs <=6 h and is deterministic.
    foreach ($tag in $Tags) {
        $file = Join-Path $CarrierRoot ("{0}_{1}\carriers\{0}_{1}_delivery_carriers_routed.xml" -f $RunIdPrefix, $tag)
        if (-not (Test-Path -LiteralPath $file)) { return $false }
    }
    return $true
}

function New-StepBBatch {
    param([string]$Path, [string[]]$Tags, [string]$JavaExe, [string]$Jar, [string]$WorkDir)
    # WriteAllLines with explicit CRLF: Write/Edit strip CRLF and cmd then misparses.
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('@echo off')
    $lines.Add('setlocal')
    $lines.Add("cd /d `"$WorkDir`"")
    $lines.Add('echo ===== RESUME BATCH START %date% %time% =====')
    $index = 1
    foreach ($tag in $Tags) {
        $lines.Add("echo ===== RESUME $index/$($Tags.Count) tag=$tag %time% =====")
        $lines.Add("`"$JavaExe`" -Xmx124g -XX:+AlwaysPreTouch -cp `"$Jar`" hagrid.HAGRIDSimulationRunner concept=basecase,date=2025-05-13,tag=$tag,maxIter=150,jspritIter=1000,writeDashboard=true")
        $lines.Add("echo RESUME${index}_EXIT=%ERRORLEVEL%")
        $index++
    }
    $lines.Add('echo ===== RESUME_DONE %date% %time% =====')
    $encoding = New-Object System.Text.ASCIIEncoding
    [IO.File]::WriteAllText($Path, (($lines -join "`r`n") + "`r`n"), $encoding)
}

function Test-LockFree {
    param([string]$LockPath)
    # Pure predicate: no system probe, so it is deterministically testable.
    return (-not (Test-Path -LiteralPath $LockPath))
}

function Test-CanLaunch {
    param([string]$LockPath)
    if (-not (Test-LockFree $LockPath)) { return $false }
    $java = @(Get-Process java -ErrorAction SilentlyContinue)
    if ($java.Count -gt 0) { return $false }
    return $true
}
```

The split matters: `Test-CanLaunch` probes the live process table, so asserting it returns `$true` would fail on any machine that happens to be running a simulation — which both of these machines routinely are. The lock predicate is the part that can be wrong in a way tests catch, so it is isolated and tested directly.

- [ ] **Step 4: Run the test to verify it passes**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File Test-ResumeSweep.ps1`
Expected: all PASS, exit 0.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/run-monitoring/resume_sweep.ps1 parcel-demand-2-matsim-pipeline/analysis/run-monitoring/Test-ResumeSweep.ps1
git commit -m "feat(monitoring): boot-triggered resume decision logic

Completeness is decided by dashboard existence, not directory existence: the
70v2 crash left a directory with 214 files and no dashboard while finished runs
have 832 files and one. Step A resume is deliberately all-or-nothing because the
tag list is a compiled constant. The generated batch is written with explicit
CRLF via WriteAllText - Write/Edit strip it and cmd then misparses."
```

---

### Task 9: Part B — resume main, installer and dry-run verification

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/resume_sweep.ps1` (append main)
- Create: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/install_resume_task.ps1`

**Interfaces:**
- Consumes: all functions from Task 8; `Send-HealthcheckPing` behaviour from Task 4 (re-implemented locally rather than dot-sourcing `heartbeat.ps1`, so the two scripts stay independently deployable)
- Produces: `Invoke-ResumeSweep([string]$ConfigPath, [switch]$DryRun)` -> `[int]`; a Scheduled Task `HAGRID-Resume`

- [ ] **Step 1: Append the main entry point to `resume_sweep.ps1`**

```powershell
function Invoke-ResumeSweep {
    param([Parameter(Mandatory=$true)][string]$ConfigPath, [switch]$DryRun)
    $cfg = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json

    $lock = $cfg.LockPath
    if (-not (Test-CanLaunch $lock)) {
        Write-Host 'resume: blocked (lock held or java already running) - nothing to do'
        return 0
    }

    $tags = @($cfg.Tags)
    if (-not (Test-StepAComplete $cfg.CarrierRoot $cfg.RunIdPrefix $tags)) {
        Write-Host 'resume: Step A incomplete - would re-run Step A in full'
        if (-not $DryRun) {
            Set-Content -LiteralPath $lock -Value 'stepA' -Encoding Ascii
            & $cfg.StepALauncher
            Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue
        }
        return 0
    }

    $remaining = Select-RemainingTags $cfg.OutputRoot $cfg.RunIdPrefix $tags $cfg.Suffix
    if ($remaining.Count -eq 0) {
        Write-Host 'resume: all tags complete - nothing to do'
        return 0
    }

    $first = $remaining[0]
    Write-Host ("resume: {0} tag(s) remaining, first incomplete = {1}" -f $remaining.Count, $first)

    if ($DryRun) {
        Write-Host 'resume: DRY RUN - no directory removed, no batch launched'
        Write-Host ("resume: would delete partial output for {0} and launch {1} tag(s)" -f $first, $remaining.Count)
        return 0
    }

    # Same runId reruns overwrite in place rather than starting clean, so the partial
    # directory of the first incomplete tag must go.
    $partial = Join-Path $cfg.OutputRoot ("{0}_{1}{2}" -f $cfg.RunIdPrefix, $first, $cfg.Suffix)
    if (Test-Path -LiteralPath $partial) {
        Remove-Item -LiteralPath $partial -Recurse -Force
        Write-Host "resume: removed partial $partial"
    }

    Set-Content -LiteralPath $lock -Value 'stepB' -Encoding Ascii
    $bat = $cfg.GeneratedBatPath
    New-StepBBatch $bat $remaining $cfg.JavaExe $cfg.Jar $cfg.WorkDir

    # start_detached.ps1 wraps cmd /c "cmd > log 2>&1" and sets CurrentDirectory; a
    # direct WMI call would put the redirect outside the cmd string and the run would
    # never start.
    & $cfg.StartDetached -Command $bat -LogFile $cfg.ResumeLog -WorkDir $cfg.WorkDir

    # Dedicated check per event: the name carries the meaning, because the docs do not
    # promise the body reaches the notification.
    if ($cfg.ResumedUrl) {
        $body = "$env:COMPUTERNAME resumed sweep after boot at tag $first ($($remaining.Count) remaining)"
        try {
            Invoke-WebRequest -Uri ($cfg.ResumedUrl.TrimEnd('/') + '/fail') -Method Post -Body $body -UseBasicParsing -TimeoutSec 20 | Out-Null
        } catch { }
    }
    return 0
}

if ($MyInvocation.InvocationName -ne '.' -and $args.Count -ge 1) {
    $dry = ($args -contains '-DryRun')
    exit (Invoke-ResumeSweep -ConfigPath $args[0] -DryRun:$dry)
}
```

- [ ] **Step 2: Write the installer**

Create `install_resume_task.ps1`:

```powershell
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
```

`-ExecutionTimeLimit ([TimeSpan]::Zero)` means no limit: a resumed sweep runs for days.

**Known limit, accepted rather than engineered around:** the resume script runs once per boot and then exits, so it cannot re-arm its own check later. After the first auto-resume, `<pc>-resumed-after-boot` stays down, and a *second* resume within the same absence would not produce a new notification. This is acceptable because the `alive` check alarms on **every** crash regardless — that is the signal that matters — and the resumed-check is only the confirmation that recovery started. The user can re-arm it with one click from the dashboard. Do not add boot-time tracking to the heartbeat to close this; the complexity is not worth a second-order convenience.

- [ ] **Step 3: Create `resume-config.json` on the sim-PC (not committed)**

Required keys, with the sim-PC values for the v3 sweep:

```json
{
  "RunIdPrefix": "BASECASE_13052025",
  "Suffix": "_iter150_jsprit1000",
  "Tags": ["30v3", "40v3", "50v3"],
  "OutputRoot": "C:\\Users\\Simrechner\\Documents\\GitHub\\HAGRID\\parcel-demand-2-matsim-pipeline\\hagrid-matsim-output",
  "CarrierRoot": "C:\\Users\\Simrechner\\Documents\\GitHub\\HAGRID\\parcel-demand-2-matsim-pipeline\\hagrid-output",
  "WorkDir": "C:\\Users\\Simrechner\\Documents\\GitHub\\HAGRID\\parcel-demand-2-matsim-pipeline",
  "JavaExe": "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.8.9-hotspot\\bin\\java.exe",
  "Jar": "REPLACE-WITH-SHADED-JAR-PATH",
  "GeneratedBatPath": "C:\\Users\\Simrechner\\hagrid-tools\\run_resume.bat",
  "ResumeLog": "hagrid-output\\logs\\resume_sweep.log",
  "StartDetached": "C:\\Users\\Simrechner\\hagrid-tools\\start_detached.ps1",
  "StepALauncher": "C:\\Users\\Simrechner\\hagrid-tools\\run_stepA_v3.bat",
  "LockPath": "C:\\Users\\Simrechner\\hagrid-tools\\resume.lock",
  "ResumedUrl": "https://hc-ping.com/REPLACE-WITH-SIM-RESUMED-AFTER-BOOT-UUID"
}
```

**`Tags` must list the real v3 tag set** once the sweep is defined, and `Jar` must be the actual shaded-jar path — resolve both against the repo before installing, do not leave the placeholder. The JDK path is pinned to 21.0.8.9 because the generated `run_hagrid_sim.bat` hardcodes the stale 21.0.3.9 and fails.

- [ ] **Step 4: Dry-run and confirm the decision is right**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\Simrechner\hagrid-tools\resume_sweep.ps1" "C:\Users\Simrechner\hagrid-tools\resume-config.json" -DryRun
```

Expected: it names the first incomplete tag and states it would delete nothing and launch nothing. **Verify the named tag matches what a human inspection of the output directories says.** A dry run that picks the wrong tag would, when armed, delete a good run's output.

- [ ] **Step 5: Confirm the dry run touched nothing**

```powershell
Test-Path 'C:\Users\Simrechner\hagrid-tools\resume.lock'
Get-ChildItem 'C:\Users\Simrechner\Documents\GitHub\HAGRID\parcel-demand-2-matsim-pipeline\hagrid-matsim-output' -Directory | Measure-Object | Select-Object Count
```

Expected: no lock file; the directory count unchanged from Task 7.

- [ ] **Step 6: Install the task — only once the sweep is actually running**

Arming resume before the sweep exists is pointless and risky. Install after the v3 sweep is launched, then verify:

```powershell
Get-ScheduledTask -TaskName 'HAGRID-Resume' | Select-Object State, @{n='User';e={$_.Principal.UserId}}
```

Expected: `Ready`, `SYSTEM`.

- [ ] **Step 7: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/run-monitoring/resume_sweep.ps1 parcel-demand-2-matsim-pipeline/analysis/run-monitoring/install_resume_task.ps1
git commit -m "feat(monitoring): boot-triggered resume main and installer

Fires on the next boot as SYSTEM with no execution time limit, so a colleague
pressing the power button is sufficient recovery. Launches via start_detached.ps1
because a direct WMI call puts the redirect outside the cmd string and the run
never starts. Dry-run mode logs the tag decision without deleting or launching."
```

---

### Task 10: Document the operating picture and update memory

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/analysis/run-monitoring/README.md`
- Modify: memory files under the user's memory directory

**Interfaces:**
- Consumes: verification outcomes from Tasks 6, 7, 9
- Produces: a runbook the user can act on from a phone, and updated memory

- [ ] **Step 1: Append the operating section to the README**

Include, with the real measured values filled in: what each of the eight checks means when it alarms; the both-alive-failed signature meaning a network outage rather than a hardware death; the colleague instruction (power button only, workdays only, weekend gap ~2.5 days); how to disarm (`Disable-ScheduledTask`); and the note that a silent failure of healthchecks.io is possible so the dashboard should be pulled occasionally.

- [ ] **Step 2: Write a forwardable colleague note**

Short, non-technical, no jargon: which machine, where it stands, that pressing the power button is the entire request, and that nothing else should be touched. This is what the user forwards when an alarm arrives.

- [ ] **Step 3: Update memory**

Update `reference_sim_pc.md` and `project_hannover_capacity_sensitivity.md` with: the alerting is live, which task names exist on which machine, where the configs live, the measured grace value, the dev-PC WU pause to 2026-09-03 and the battery-sleep fix, and the corrected completeness rule (dashboard not directory). Add a new `reference` memory for the alerting setup itself if it does not fit the existing files.

- [ ] **Step 4: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/run-monitoring/README.md
git commit -m "docs(monitoring): operating picture, colleague note and measured values"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §3 architecture, three checks per machine | 5 (creation), 4 (implementation) |
| §3.1 progress not redundant | 3, 4 (implementation), 6 Step 3 (verification) |
| §3.2 event as push channel | 4, 6 Step 1 |
| §3.3 dev-PC as network-outage discriminator | 5 (both machines' checks), 10 Step 1 (documented) |
| §4 heartbeat script | 3, 4 |
| §4.1 Scheduled Task as SYSTEM, At startup | 5 Step 2, 6 Step 5 (reboot survival) |
| §4.2 secrets outside a public repo | Global Constraints, 4 Step 5/6, 5 Step 3 |
| §5 notification channels | 5 Step 1, 6 Step 6 |
| §6 measured grace window | 2 |
| §7 auto-resume | 8, 9 |
| §7.1 two-phase resume | 8 (`Test-StepAComplete`), 9 Step 1 |
| §7.2 weekend gap | 10 Steps 1–2 (documented, not solvable in code) |
| §8.1 dev-PC WU pause | already done by the user, verified 2026-07-31 |
| §8.2 dev-PC sleep | 1 |
| §9 failure matrix | 6 (each row provoked where provocable) |
| §10 accepted limits | 10 Step 1 |
| §11 verification before departure | 6, 7 Step 7, 9 Step 4 |

No gaps.

**Placeholder scan:** Three intentional fill-ins remain, each with a named source and an explicit instruction not to guess: the grace value (Task 2 Step 6 measures it, Task 2 Step 7 and Task 5 Step 1 consume it), the `Tags` array and `Jar` path in `resume-config.json` (Task 9 Step 3, resolved against the actual sweep definition). These are values that cannot exist before the measurement and the sweep exist. No "add error handling"-class placeholders.

**Type consistency:** `Get-NewestLogState` returns `Path`/`LastWriteTicks`/`Length`; `Test-ProgressAdvanced` consumes exactly those three keys; the state file persists them as `LastLogPath`/`LastWriteTicks`/`LastLength` and `Invoke-Heartbeat` maps between the two shapes explicitly. `Test-TagComplete` and `Select-RemainingTags` share the `(OutputRoot, RunIdPrefix, Tag(s), Suffix)` parameter order. `Send-HealthcheckPing($BaseUrl, $Suffix, $Body)` is called with all three positionally throughout. `Invoke-ResumeSweep` deliberately re-implements the ping inline instead of dot-sourcing `heartbeat.ps1`, so the two deployed scripts have no cross-dependency.

## Open deviation from the spec — needs a decision

The spec (§4.2) says the heartbeat script itself lives outside the repo. This plan instead keeps the **source** in the repo (it contains no secrets, so it gains version history and review) and has the installer **deploy a copy** to the tools directory, which is what actually runs. Only `hc-config.json` never enters git.

Net effect matches the spec's security intent — no secret in a public repo — while adding version control and insulating the running copy from repo working-tree changes during run prep. Recorded here rather than absorbed silently.
