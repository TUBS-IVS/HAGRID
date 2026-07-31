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
