# Run Monitoring — outbound crash alerting

Spec: `docs/superpowers/specs/2026-07-31-remote-crash-alerting-design.md`

Each sweep machine pings healthchecks.io every 5 min; the service alarms on silence
and pushes to the phone. No VPN, no SSH, no Claude session involved.

- `measure_log_gaps.py` — derives the progress grace window from a real batch log.
  Test: `python -m pytest test_measure_log_gaps.py`.

  **Measurements (2026-07-31), three independent samples from the sim-PC, each
  identified by pipeline phase:**
  - **Step B (simulation), `stepB_weekend_batch.log`, 10 complete runs:** largest
    legitimate quiet gap **81 s** (3 out-of-order/interleaved lines dropped by the
    parser's guard — confirmed live on this log, not just theoretical).
  - **Step A (jsprit construction across 38 scenario tags), `stepA_weekend.log`:**
    largest legitimate quiet gap **38 s** (0 lines dropped).
  - **`hagrid.log` (single run, 2026-07-29): not measurable with the current parser.**
    This file uses a different log4j pattern — milliseconds plus a thread tag
    (`2026-07-29 22:33:46.737 [main] INFO ...`) — instead of the seconds-only,
    no-thread-tag format the regex targets (`2026-07-24 21:36:50 INFO ...`). The
    tool matched 0 timestamped lines; that is a format mismatch, not evidence of a
    zero-gap run, and this sample contributes no data point to the decision.

  **The original spec value of 90-120 min rested on a false premise.** It assumed
  the jsprit construction phase (Step A) would be the long quiet stretch to guard
  against. It logs continuously instead, and is in fact *quieter* in gap terms
  than Step B (38 s vs. 81 s) — there is no long silent jsprit phase in either
  real sample.

  **Decision (evidence-driven, asymmetric by machine):**
  - **sim-PC:** progress period 15 min / grace 30 min (alarm at ~45 min of silence).
  - **dev-PC:** progress period 30 min / grace 60 min (alarm at ~90 min of silence).

  The sim-PC value comes directly from the 81 s Step B measurement (its own
  hardware, its own workload). The dev-PC is deliberately given double the
  margin instead of reusing the sim-PC number as-is: it has never run a Hannover
  scenario and has 63.5 GB RAM against the sim-PC's 128 GB, so a tighter heap
  makes longer GC pauses plausible even though none have been measured there.
  Tighten the dev-PC value once it has produced its own batch log to measure.

  `main()`'s printed recommendation formula is `max(600, 20x observed max)`
  (10-minute floor, 20x safety margin) — evidence-driven rather than the
  previous `max(3600, 2x)`, which recommended 3600 s regardless of the data
  (both real samples measure far below even a 2x margin over 3600 s, so the old
  floor carried no information from what the tool had just parsed, and it also
  silently disagreed with the sim-PC's actual deployed config of 1800 s with no
  explanation of which one governed). Printed recommendations from the amended
  formula: **1620 s (27 min)** on `stepB_weekend_batch.log`, **760 s (13 min)** on
  `stepA_weekend.log` — both informative relative to the observed data, unlike
  the old floor-dominated output. The grace values actually deployed (30 min /
  60 min above) build in extra margin beyond the tool's raw suggestion
  deliberately, per the asymmetric-by-machine reasoning above.
- `heartbeat.ps1` — the heartbeat. Test: `powershell -File Test-Heartbeat.ps1`.
- `install_heartbeat_task.ps1` — creates the SYSTEM Scheduled Task (idempotent).
  Validates `hc-config.json` (rejects unfilled `REPLACE` placeholders anywhere,
  including inside array values, and an ambiguous `LogPattern` — any pattern
  matching more than one existing file in `LogDir`, not a blacklist of specific
  wide spellings), probes `StatePath` and `LocalLogPath` for actual writability
  (writes and reads back a sibling file, not just "the string is non-empty"),
  and pings all three check URLs once — all before registering anything. After
  starting the task it reads back the local log to confirm it actually ran, and
  **fails** (not just warns) if that first real cycle itself reports
  `stateSaved=False` — a passing install-time probe does not guarantee the Task
  Scheduler service context can write the same path.
- `hc-config.template.json` — copy to `<toolsdir>\hc-config.json` and fill in the UUIDs.
  **The real config never enters git; this repo is public and ping URLs are capability URLs.**
  `LogPattern`'s shipped value is a `REPLACE-...` placeholder, not `*.log`: real log
  directories hold `hagrid.log`, dozens of rotated `hagrid-<date>-N.log(.gz)`, and
  `*.console.log` from unrelated batches, so "newest `.log`" is not reliably the
  sweep's driver log. `install_heartbeat_task.ps1` refuses to install with any
  `LogPattern` (`*.log`, `*`, `*.*`, or otherwise) that currently matches more than
  one file in `LogDir`; zero or exactly one match is allowed through.

  | Key | Meaning |
  |---|---|
  | `AliveUrl` / `ProgressUrl` / `SweepFinishedUrl` | healthchecks.io ping URLs. Pinged once at install time; install fails on an unfilled `REPLACE` placeholder, an empty value, or a non-2xx/unreachable response. |
  | `LogDir` | Absolute path to the directory the heartbeat watches for the newest matching log file. **If Part B (auto-resume) is installed, `resume-config.json`'s `ResumeLog` must resolve into this SAME directory** — checked only by `install_resume_task.ps1`, only at install time. Changing `LogDir` after Part B is installed requires re-running `install_resume_task.ps1` to re-validate the pair; nothing re-checks it automatically afterward. |
  | `LogPattern` | Wildcard filter within `LogDir`. Empty defaults to `*.log` (`Read-HeartbeatConfig`). **Same cross-check as `LogDir`**: if Part B is installed, `resume-config.json`'s `ResumeLog` filename must match this pattern, verified only by `install_resume_task.ps1` at install time — re-run it after changing `LogPattern` here. |
  | `StatePath` | Absolute path to the heartbeat's own JSON state file. Probed for actual writability at install time (write-then-read-back a sibling file, not just non-empty-string). |
  | `LocalLogPath` | Absolute path to the heartbeat's own local log. Also probed for writability at install time; the post-start readback reads this file to confirm the task actually ran. |
- `resume_sweep.ps1` / `install_resume_task.ps1` — Part B, boot-triggered auto-resume.
  Test: `powershell -File Test-ResumeSweep.ps1`. `install_resume_task.ps1` is
  tested (cmdlet-existence smoke test plus its pure validation logic) by
  `powershell -File Test-Installers.ps1`, which also covers `install_heartbeat_task.ps1`.
  Neither installer's actual `Register-ScheduledTask` call is exercised by any
  automated test - that needs admin rights and a real config.
- `resume-config.template.json` — copy to `<toolsdir>\resume-config.json` and fill in
  the real values below. **Never committed; this repo is public.**

  **`resume-config.json`** — required by `resume_sweep.ps1` / `install_resume_task.ps1`,
  never committed (this repo is public; a ping URL is a capability URL). Copy is
  machine-local, e.g. `<toolsdir>\resume-config.json`. All paths must be absolute:
  the script runs as SYSTEM, which has no user PATH and no mapped drives. Placeholder
  values only below - never fill in real paths or ping UUIDs here.

  | Key | Meaning |
  |---|---|
  | `LockPath` | Absolute path to the lock file. Presence blocks a launch (`Test-CanLaunch`) UNLESS the lock has gone stale (see `LockStaleHours`); the script also refuses to launch onto a running `java.exe` even with no lock - that is the real double-launch guard, the lock only needs to cover the seconds between launch and `java.exe` appearing. |
  | `LockStaleHours` | Optional, default `12`. A lock older than this many hours is treated as abandoned (e.g. a colleague rebooted after a SECOND crash) and bypassed rather than blocking auto-resume for the rest of the absence. |
  | `Tags` | The full scenario tag list for this sweep, e.g. `["30v3", "40v3", "50v3"]`. **Must list the real tag set of the sweep actually running** - Step A is checked/re-run for all of them together, and Step B resumes whichever remain incomplete. |
  | `RunIdPrefix` | The run-id prefix shared by every tag's output directory, e.g. `"BASECASE_13052025"`. |
  | `Suffix` | The trailing part of each tag's output directory name, e.g. `"_iter150_jsprit1000"`. Directory pattern is `<RunIdPrefix>_<tag><Suffix>`. |
  | `OutputRoot` | Absolute path to the directory holding one output folder per tag (used to detect completion and to delete EVERY remaining tag's partial output before relaunch, not just the first). |
  | `CarrierRoot` | Absolute path to the directory holding each tag's routed delivery-carrier file (used to detect whether Step A finished). |
  | `WorkDir` | Absolute path Step B's generated batch `cd /d`s into before running; also the working directory `Push-Location`'d to before invoking `StepALauncher`, since a SYSTEM task's default CWD is `C:\Windows\System32`. |
  | `JavaExe` | Absolute path to the JDK's `java.exe`. Pin the exact version actually installed - a mismatched hardcoded version elsewhere in the pipeline has caused launch failures before. Checked with `Test-Path` before anything is deleted or launched. |
  | `JvmArgs` | JVM flags (e.g. heap size), no default on purpose - same reasoning as `ArgTemplate`. The sim-PC (128 GB) and dev-PC (63.5 GB) need DIFFERENT flags; `-XX:+AlwaysPreTouch` commits the whole heap at startup and is wrong for a smaller machine. |
  | `Jar` | Absolute path to the actual shaded jar. Resolve this against the repo before installing; never leave a placeholder in the real config. Checked with `Test-Path` before anything is deleted or launched. |
  | `ArgTemplate` | Step B's CLI arguments with a literal `{TAG}` placeholder, e.g. `"concept=basecase,date=2025-05-13,tag={TAG},maxIter=150,jspritIter=1000,writeDashboard=true"`. **Must be kept in sync with the sweep actually running** - a stale value here (old date, old iteration count) would relaunch with the wrong parameters, unattended, with nobody watching. |
  | `GeneratedBatPath` | Absolute path where the resume script writes the batch file it generates for Step B. Tracks each tag's own exit code and emits `RESUME_DONE` only if every tag exited 0, otherwise a distinct `RESUME_FAILED` sentinel (not in the heartbeat's completion-marker list) - so a resumed run that fails fast cannot look like a successful "sweep finished" to the heartbeat. |
  | `ResumeLog` | Path (relative to `WorkDir`, or absolute) for the resumed run's log output. **Must resolve INSIDE `hc-config.json`'s `LogDir` and match its `LogPattern`** - `install_resume_task.ps1` reads both configs and refuses to install otherwise, naming both values. This is the resumed run's only continuous artefact in a predictable place (its log4j output goes to its own run directory, not `LogDir`); if the heartbeat cannot find this file, progress goes permanently quiet (a false alarm nobody can act on) or latches on a stale finished log. **This is checked only by `install_resume_task.ps1`, only at install time** - if `ResumeLog` changes here, or `LogDir`/`LogPattern` change in `hc-config.json`, re-run `install_resume_task.ps1` to re-validate the pair; nothing re-checks it automatically afterward. |
  | `StartDetached` | Absolute path to `start_detached.ps1` (or equivalent), used to launch Step B detached. A direct WMI call is not equivalent - it puts the output redirect outside the cmd string and the run never starts. Checked with `Test-Path` before anything is deleted or launched. |
  | `StepALauncher` | Absolute path to the script/batch that re-runs Step A in full when it is found incomplete. Checked with `Test-Path` before anything is deleted or launched. After it finishes, the SAME invocation proceeds into Step B tag selection rather than returning - a boot landing during Step A must not leave the machine idle for the rest of a ten-day absence once Step A itself finishes. |
  | `LocalLogPath` | Absolute path for `resume_sweep.ps1`'s own local log (mirrors `heartbeat.ps1`'s `Write-LocalLog`). Every branch of the main loop writes to it - under SYSTEM, `Write-Host` goes nowhere, so this file is the only record, ten days later, of whether the boot script ran and what it decided. |
  | `ResumedUrl` | healthchecks.io ping URL for the `*-resumed-after-boot` check. Optional: if empty, no ping is sent. |

## Every configured path must be on a local disk

Both installers refuse to register anything if a configured path sits on a drive
letter that is not a local fixed disk, or on a UNC share (`Test-SystemVisiblePaths`).
A mapped network drive belongs to **one interactive logon session**; a Scheduled
Task running as `SYSTEM` has no such session, so the letter simply does not exist
for it and `Test-Path` returns `$false` with no error. The dev-PC maps `T:`, `S:`
and `X:` to `\\ad.tu-bs.de\share\ivs\*`, so this is a live hazard there, not a
theoretical one: a `LogDir` on `T:` would install perfectly green and then leave
the heartbeat unable to find any log, i.e. `progress` alarming forever on a
healthy machine. UNC paths are refused too, because `SYSTEM` authenticates to a
share as the *machine* account, which has no rights on a user share.

The same blind spot exists in an SSH session, which is what surfaced this: over
`ssh sim`, `Get-PSDrive` lists `C:` only. That makes SSH a useful rehearsal for
what the task will see.

## Known limitations (accepted, not fixed - the fixes are disproportionate to the risk)

- **`LockStaleHours = 0` is treated as unset, not "never stale".** `Invoke-ResumeSweep`
  reads it with `if ($cfg.LockStaleHours) { ... }`, and `0` is falsy in PowerShell, so
  it silently falls back to the 12h default instead of disabling staleness. There is
  no way to configure "a lock never goes stale" via this key. If that behavior is ever
  actually needed, use a very large number of hours instead of `0`.
- **A lock file with a future mtime (clock skew) blocks resume until wall-clock time
  catches up to it.** `Test-LockStale` computes `age = now - LastWriteTime`; if
  `LastWriteTime` is in the future (e.g. a misconfigured system clock, or a VM
  snapshot restore), `age` is negative and the lock is never considered stale until
  real time passes that timestamp. Keep the machine's clock correct.
- **The `java.exe` probe in `Test-CanLaunch` matches ANY process named `java`, not
  specifically the sweep's own.** An IDE's language server, a Gradle daemon, or any
  other JVM left running on the dev-PC will block auto-resume from ever launching.
  The refusal is not an error - it exits 0 and is visible only in the line
  `blocked: lock held (not stale) or java.exe already running` in `resume_sweep.log`,
  which nobody is watching from abroad. **Kill any stray `java.exe` processes before
  leaving a machine unattended with Part B installed.**
