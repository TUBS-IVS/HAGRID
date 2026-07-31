# Run Monitoring — outbound crash alerting

Spec: `docs/superpowers/specs/2026-07-31-remote-crash-alerting-design.md`

Each sweep machine pings healthchecks.io every 5 min; the service alarms on silence
and pushes to the phone. No VPN, no SSH, no Claude session involved.

- `measure_log_gaps.py` — derives the progress grace window from a real batch log.
  Test: `python -m pytest test_measure_log_gaps.py`.
  **Measured 2026-07-31 against three independent samples from the sim-PC, each
  identified by pipeline phase:**
  - **Step B (simulation), `stepB_weekend_batch.log`, 10 complete runs:** largest
    legitimate quiet gap **81 s**.
  - **Step A (jsprit construction across 38 scenario tags), `stepA_weekend.log`:**
    largest legitimate quiet gap **38 s**.
  - **`hagrid.log` (single run, 2026-07-29): not measurable with the current parser.**
    This file uses a different log4j pattern — milliseconds plus a thread tag
    (`2026-07-29 22:33:46.737 [main] INFO ...`) — instead of the seconds-only,
    no-thread-tag format the regex targets (`2026-07-24 21:36:50 INFO ...`). The
    tool matched 0 timestamped lines; that is a format mismatch, not evidence of a
    zero-gap run, and this sample contributes no data point to the grace decision.

  **Grace recommendation (unchanged): 3600 s.** Both real samples (Step A: 38 s,
  Step B: 81 s) are far below the 3600 s floor in `main()`'s
  `max(3600, 2x observed max)` formula, so the 60-minute grace is floor-driven,
  not evidence-driven, for both phases of the pipeline.
- `heartbeat.ps1` — the heartbeat. Test: `powershell -File Test-Heartbeat.ps1`.
- `install_heartbeat_task.ps1` — creates the SYSTEM Scheduled Task (idempotent).
- `hc-config.template.json` — copy to `<toolsdir>\hc-config.json` and fill in the UUIDs.
  **The real config never enters git; this repo is public and ping URLs are capability URLs.**
- `resume_sweep.ps1` / `install_resume_task.ps1` — Part B, boot-triggered auto-resume.
  Test: `powershell -File Test-ResumeSweep.ps1`.
