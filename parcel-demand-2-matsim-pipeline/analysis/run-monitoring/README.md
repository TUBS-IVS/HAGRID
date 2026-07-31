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
- `hc-config.template.json` — copy to `<toolsdir>\hc-config.json` and fill in the UUIDs.
  **The real config never enters git; this repo is public and ping URLs are capability URLs.**
- `resume_sweep.ps1` / `install_resume_task.ps1` — Part B, boot-triggered auto-resume.
  Test: `powershell -File Test-ResumeSweep.ps1`.

  **`resume-config.json`** — required by `resume_sweep.ps1` / `install_resume_task.ps1`,
  never committed (this repo is public; a ping URL is a capability URL). Copy is
  machine-local, e.g. `<toolsdir>\resume-config.json`. All paths must be absolute:
  the script runs as SYSTEM, which has no user PATH and no mapped drives. Placeholder
  values only below - never fill in real paths or ping UUIDs here.

  | Key | Meaning |
  |---|---|
  | `LockPath` | Absolute path to the lock file. Presence blocks a launch (`Test-CanLaunch`); the script also refuses to launch onto a running `java.exe` even with no lock. |
  | `Tags` | The full scenario tag list for this sweep, e.g. `["30v3", "40v3", "50v3"]`. **Must list the real tag set of the sweep actually running** - Step A is checked/re-run for all of them together, and Step B resumes whichever remain incomplete. |
  | `RunIdPrefix` | The run-id prefix shared by every tag's output directory, e.g. `"BASECASE_13052025"`. |
  | `Suffix` | The trailing part of each tag's output directory name, e.g. `"_iter150_jsprit1000"`. Directory pattern is `<RunIdPrefix>_<tag><Suffix>`. |
  | `OutputRoot` | Absolute path to the directory holding one output folder per tag (used to detect completion and to delete a crashed tag's partial output before relaunch). |
  | `CarrierRoot` | Absolute path to the directory holding each tag's routed delivery-carrier file (used to detect whether Step A finished). |
  | `WorkDir` | Absolute path Step B's generated batch `cd /d`s into before running. |
  | `JavaExe` | Absolute path to the JDK's `java.exe`. Pin the exact version actually installed - a mismatched hardcoded version elsewhere in the pipeline has caused launch failures before. |
  | `Jar` | Absolute path to the actual shaded jar. Resolve this against the repo before installing; never leave a placeholder in the real config. |
  | `ArgTemplate` | Step B's CLI arguments with a literal `{TAG}` placeholder, e.g. `"concept=basecase,date=2025-05-13,tag={TAG},maxIter=150,jspritIter=1000,writeDashboard=true"`. **Must be kept in sync with the sweep actually running** - a stale value here (old date, old iteration count) would relaunch with the wrong parameters, unattended, with nobody watching. |
  | `GeneratedBatPath` | Absolute path where the resume script writes the batch file it generates for Step B. |
  | `ResumeLog` | Path (relative to `WorkDir`, or absolute) for the resumed run's log output. |
  | `StartDetached` | Absolute path to `start_detached.ps1` (or equivalent), used to launch Step B detached. A direct WMI call is not equivalent - it puts the output redirect outside the cmd string and the run never starts. |
  | `StepALauncher` | Absolute path to the script/batch that re-runs Step A in full when it is found incomplete. |
  | `ResumedUrl` | healthchecks.io ping URL for the `*-resumed-after-boot` check. Optional: if empty, no ping is sent. |
