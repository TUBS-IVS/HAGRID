# Remote Crash Alerting for Unattended Sweep Runs — Design

**Date:** 2026-07-31
**Status:** Approved by user (design conversation 2026-07-31)
**Goal:** During a ~10-day absence (2026-07-31 → ~2026-08-10), two machines run long Hannover
capacity sweeps unattended. If a machine or its run dies, the researcher must receive a push
message on a phone — with no VPN, no SSH tunnel, no running Claude session, and no second
machine required to stay alive for the alarm to work.

**Scope of this spec:** the alerting mechanism (Part A) and, as a separately switchable second
building block, boot-triggered auto-resume (Part B). Part A can ship without Part B.
**Not in scope:** the sweep run plans themselves (v3 on the sim-PC, v2 continuation on the
dev-PC) — those are launched separately on the user's signal.

---

## 1. Why the existing approach cannot work for 10 days

Every previous listener polled the sim-PC *from* the dev-PC over SSH through the TU-BS VPN.
That design has three failure paths that have nothing to do with the machine being watched, and
all three have already fired:

| Date | Event | Consequence |
|---|---|---|
| 2026-07-26 | TU-BS VPN 24 h re-login lapsed overnight | 11.3 h blackout, 136 consecutive missed probes |
| 2026-07-27 | Dev-PC forced Windows Update reboot (04:54) | both background tasks died |
| 2026-07-27 | Dev-PC halted at Dell BIOS F1 prompt | ~2.5 h down until a key was pressed |

The decisive problem is not fragility but **ambiguity**: a poll-based listener cannot distinguish
"the watched machine is dead" from "my own path to it is dead". Silence looks identical to health.
Over a 10-day absence the VPN alone would be expired for most of the window, because it demands
an interactive password every 24 h.

**Therefore the direction is inverted: the watched machine sends outward, and a cloud service
alarms on silence.** A dead machine cannot ping — so a power-off becomes the one failure mode
this design detects with certainty, which is exactly the failure mode under suspicion.

## 2. Verified preconditions (measured 2026-07-31, not assumed)

| Fact | Value | How verified |
|---|---|---|
| Sim-PC outbound HTTPS | works, HTTP 200 from `hc-ping.com` | `Invoke-WebRequest` executed on the sim-PC via `ssh sim` |
| Sim-PC uptime | 16 days (last boot 2026-07-15 04:37, the WU reboot) | `Win32_OperatingSystem.LastBootUpTime` |
| Sim-PC Windows Update | paused until 2026-08-19 — covers the whole absence | recorded 2026-07-15, see `sim-pc-ivs2000` memory |
| Dev-PC hardware | 63.5 GB RAM, 14 logical CPUs, 686 GB free, chassis type 10 = **notebook** | `Win32_ComputerSystem` / `Win32_Processor` |
| Dev-PC Windows Update | **NOT paused** — caused the 2026-07-27 reboot, reboot window 02:00–08:00 | `PauseUpdatesExpiryTime` empty |
| Dev-PC Hannover inputs | present and complete (`hagrid-input/` incl. `demand/BASECASE_13052025`) | directory listing |
| Dev-PC Hannover runs | none yet (no `BASECASE_*` output dirs) | directory listing |
| healthchecks.io free tier | 20 checks, 100 log entries/check, no credit card | pricing page |
| healthchecks.io channels | Signal, ntfy, Telegram, email, webhooks all available; **only** SMS / WhatsApp / phone-call are credit-metered and thus paid | integration list |

**PSU status for the record:** the suspected marginal PSU did **not** reproduce. The machine
survived ~65 h continuous load over the weekend of 2026-07-24/27 and is now at 16 days uptime.
The only in-run failure was an isolated JVM `EXCEPTION_ACCESS_VIOLATION` under ZGC (70v2). This
alerting is therefore **insurance against an unproven fault**, not a response to an active one —
which is the right posture, because the observed failures came from Windows Update and from the
dev-PC, not from the sim-PC's power supply.

## 3. Architecture — outbound dead-man's switch

```
  Sim-PC ──HTTPS──┐
                  ├──> healthchecks.io ──> Signal + Email ──> phone
  Dev-PC ──HTTPS──┘        (alarms on silence)
```

Three checks per machine, six of the twenty free slots:

| Check | Period / Grace | Alarm latency | Detects |
|---|---|---|---|
| `<pc>-alive` | 5 min / 15 min | ≤20 min | power-off, reboot, network loss, OS hang |
| `<pc>-progress` | 30 min / see §6 | ≤~2 h | JVM crash with the machine still alive, hung run, stalled batch |
| `<pc>-event` | manual (no schedule) | immediate | arbitrary push messages, good and bad |

### 3.1 Why `progress` is not redundant with `alive`

On 2026-07-25 the 70v2 run died of a JVM access violation at ~iteration 37. The machine stayed
up, the batch moved on to the next tag, and a reachability check would have stayed green
throughout. `progress` is the only check that sees this class of failure. It is also what
reports normal completion of the sweep.

### 3.2 `event` as a general push channel

healthchecks.io notifies on *failure*, not on success. A `POST` to a check's `/fail` endpoint
with a text body therefore delivers an arbitrary message to Signal and email. This is used
deliberately, not as a workaround, for: Step A finished, Step B started, a per-run non-zero exit
code, auto-resume triggered after boot, sweep complete. Body limit is generous (kilobytes); one
line is enough.

### 3.3 Why the dev-PC is monitored even though its death is acceptable

The user accepts losing the dev-PC arm. Monitoring it anyway costs two check slots and buys the
**only available discriminator for the systematic false positive of this design**: if both
`alive` checks fail simultaneously, the cause is the network or the site, not either machine. If
one fails alone, that machine is genuinely down. Without the second machine, a campus network
outage is indistinguishable from a hardware death.

## 4. Component A1 — heartbeat script (per machine)

`hagrid-tools\heartbeat.ps1`, outside the git repo (see §4.2). Invoked every 5 minutes by a
Scheduled Task. Steps:

1. **Always** ping `<pc>-alive`. Unconditional — it must not depend on any run being active.
2. Compute the progress signal: the maximum `LastWriteTime` over `hagrid-output\logs\*.log`
   (few files, cheap — a recursive scan of `hagrid-matsim-output\**` would be ~30 000 files and
   is deliberately avoided). If it advanced since the previous heartbeat, ping `<pc>-progress`.
   The previous value is persisted in a small state file next to the script.
3. If the newest log contains a batch-completion marker (`weekend batch done` / `SCEN*_EXIT=`
   pattern), keep pinging `progress` unconditionally from then on, so that a legitimately
   finished sweep does not decay into a false alarm — and send a one-time `event` push
   announcing completion. The "already announced" flag lives in the state file.
4. Never throw. A failed `curl` is logged locally and ignored — a transient network error must
   not kill the heartbeat. The grace window is what absorbs it.

A useful property falls out of step 1: **the heartbeat monitors itself.** If the script or its
Scheduled Task dies while the machine lives, `alive` stops being pinged and alarms within ~20 min.
There is no separate watchdog-watching-the-watchdog problem — the only way to lose the alarm
silently is for healthchecks.io itself to fail (§10.1).

### 4.1 Scheduled Task definition

Trigger *At startup*, repeat every 5 minutes indefinitely, run as `SYSTEM`, highest privileges,
"run whether user is logged on or not". Consequences that matter:

- **No autologon is required.** MATSim needs no interactive desktop.
- The task survives every reboot by itself, which is the property the previous Claude-session-
  bound and Cygwin-bound listeners lacked.
- `SYSTEM` has a different environment: no user `PATH`, no mapped drives (notably **not** `T:`).
  All paths in the script must be absolute. This is the same constraint that already applies to
  the generated launcher bats.

### 4.2 Ping URLs are secrets — and this repo is public

A healthchecks ping URL is a capability URL: anyone holding it can forge heartbeats or fire
alarms. **The HAGRID repo is public.** The UUIDs therefore must not be committed.

Decision: the heartbeat script and its config live in `C:\Users\<user>\hagrid-tools\`, outside
the repo — the same location already used for `start_detached.ps1` and `redo70_waiter.ps1`. The
config is `hagrid-tools\hc-config.json` holding the three UUIDs for that machine. Only a
sanitised template with placeholder UUIDs is committed, under
`parcel-demand-2-matsim-pipeline/tools/heartbeat/`.

## 5. Component A2 — notification channels

Configured on healthchecks.io, not on either PC. Nothing is installed on the sim-PC; it only
makes outbound `curl` calls. This decouples the channel choice entirely from physical access.

- **Signal** — primary. Available on the free tier; requires one-time number verification during
  setup. To be confirmed working during setup, not assumed.
- **Email** — secondary, redundant. Chosen because the user will check mail regularly anyway.
  Note for the record: the user's original doubt about email was that the PC would need TU SMTP
  credentials. It does not — the cloud service sends the mail, the PC only pings.

Both channels are attached to all six checks.

## 6. Open measurement — the `progress` grace window

The stall threshold must be derived, not guessed, or it will produce false alarms. The quiet
candidate is the jsprit routing phase, which writes little while it runs.

**Method:** parse the timestamped lines of the existing `hagrid-output/logs/stepB_weekend_batch.log`
(43 MB, ten complete real runs) and take the distribution of gaps between consecutive lines. Set
the grace to a clear margin above the observed maximum legitimate gap. Provisional value 90–120
min pending that measurement; the measured number replaces it during implementation.

## 7. Component B — boot-triggered auto-resume (separately switchable)

Available intervention while the user is away is exactly one action: **a colleague pressing the
power button, on workdays only.** No scripts, no logins. Everything else must therefore be
automatic, triggered by the boot itself.

`hagrid-tools\resume_sweep.ps1`, Scheduled Task, trigger *At startup* with a ~2 min delay, run as
`SYSTEM`:

1. **Idempotency guard:** a lock file plus a check that no `java.exe` is running. Prevents a
   double start if the user or a colleague also intervenes. This guard is required, not
   optional — a double launch would corrupt output directories.
2. **Determine progress by dashboard existence, not directory existence.** Measured 2026-07-31:

   | Tag | Dashboards | Files | Size |
   |---|---|---|---|
   | 60v2 | 1 | 832 | 343 MB |
   | **70v2 (crash fragment)** | **0** | **214** | **18 MB** |
   | 80v2 | 1 | 832 | 323 MB |
   | 150v2 | 1 | 832 | 298 MB |

   A crashed run leaves its output directory behind, so directory existence means nothing. The
   completeness test is `analysis\HAGRID_Dashboard_*.html`; the file count gives an independent
   second discriminator. This is the same file-existence-driven rule that let the hardened
   listener survive the 11.3 h VPN blackout on 2026-07-26.
3. **Delete the partial output directory** of the first incomplete tag before relaunching — same
   `runId` reruns overwrite in place rather than starting clean.
4. **Relaunch the remaining tags** sequentially via `start_detached.ps1` with `-WorkDir` set to
   the pipeline directory, using the JDK 21.0.8 launcher bat, not the generated
   `run_hagrid_sim.bat` (which hardcodes the stale `jdk-21.0.3.9`).
5. **Push an `event`**: "resumed at capacity X after boot".

### 7.1 Two-phase resume for the v3 sweep

The v3 sim-PC sweep needs Step A (38 carrier sets, ~6 h) before Step B. The two phases need
different resume rules:

- **Step B incomplete** → resume per tag as above.
- **Step A incomplete** → re-run Step A **in full**. It is deterministic and idempotent, and the
  38 v3 tags are already compiled into the jar, so no source edit and no rebuild are needed. A
  partial-tag resume would require editing the compiled `SCENARIOS` constant and rebuilding,
  which is far too fragile for an unattended boot script. Cost of the blunt approach is ≤6 h,
  which is the right trade.

### 7.2 Weekend gap — accepted, with the one real mitigation named

No colleague is available at weekends. A hard death on Friday evening costs ~2.5 days. The only
mitigation that removes the human from the loop is the BIOS setting **"AC Power Recovery =
Power On"**, which makes the machine restart itself after a power loss. It cannot be set
remotely. If the user is physically at the sim-PC before departure, this is the single highest-
leverage action available, because it converts the dominant suspected failure mode into a
self-healing one.

## 8. Pre-departure hardening (separate from A and B)

These address the failures that actually occurred, not hypothetical ones:

1. **Pause Windows Update on the dev-PC.** Currently unpaused; it forced the 2026-07-27 reboot
   and its reboot window is 02:00–08:00, i.e. it will recur within the absence.
2. **Disable sleep/standby/disk-timeout on the dev-PC on AC** (`powercfg /change ... 0`). It is a
   notebook, and laptop sleep is a known run-killer. The sim-PC is already set this way.
3. **Sim-PC Windows Update pause already covers the window** (until 2026-08-19). No action.
4. Note for the dev-PC: after a reboot it may halt at the Dell "incompatible battery / 15W
   charger" F1 prompt, which no remote mechanism can clear. Auto-resume on the dev-PC is
   therefore inherently less reliable than on the sim-PC — consistent with the user's decision
   that dev-PC loss is acceptable.

## 9. Failure matrix

| Failure | `alive` | `progress` | Notification | Recovery |
|---|---|---|---|---|
| PSU power-off | fails | fails | ≤20 min | colleague power button → auto-resume (workdays) |
| Windows Update reboot | brief fail | recovers | possibly brief, then `event` "resumed" | fully automatic |
| JVM crash, machine alive (70v2 class) | green | fails | ≤~2 h | batch continues to next tag by itself |
| Batch hangs | green | fails | ≤~2 h | colleague reboot → auto-resume |
| Sweep finished normally | green | green | `event` "sweep complete" | none needed |
| Campus network outage | **both** fail | both fail | ≤20 min, false positive | self-healing; identifiable via the both-fail signature |
| healthchecks.io itself down | no alarm | no alarm | **silent** | user can pull the dashboard from the phone (§10) |
| Dev-PC dies | dev fails | dev fails | ≤20 min | accepted; optional colleague reboot |

## 10. Accepted limits

1. **A silent failure of healthchecks.io is possible.** The mitigation is not a second service —
   that doubles setup for little gain — but that the dashboard is *pullable*: green/red visible
   from the phone at any time. Absence of an alarm is therefore verifiable rather than trusted.
2. **Network outages produce false positives.** Inherent to any outbound design; the 15 min grace
   absorbs blips and the two-machine signature identifies the rest.
3. **Neither sweep will finish within the absence.** v3 is 38 runs à ~7 h plus ~6 h Step A ≈ 11.3
   days. The dev-PC arm is 26 runs (70v2 plus 160–400 step 10; 150v2 is already complete) on 63.5
   GB and 14 threads versus the sim-PC's 128 GB, so per-run time is expected above 7 h. Ordering
   should therefore put the *informative* capacities early. This is a run-planning consequence,
   recorded here because the alerting design must not be mistaken for a completion guarantee.
4. **The dev-PC RAM headroom is unverified.** The sweep ran with `-Xmx124g` and ~106 GB peak at
   capacity 60. High capacities use far fewer vehicles (~650–680 versus 3 204), so lower memory
   is plausible — but plausible is not measured. A ~4 min smoke run (`maxIter=1, jsprit=10`) at
   the highest planned capacity, with the peak read off, is required before committing the arm.
   The user has approved this smoke test; it runs on their signal.

## 11. Verification before departure

An untested alarm is not an alarm. Each path is provoked deliberately and the arrival of the
message on the phone confirmed — not merely the sending:

1. `alive` — stop the heartbeat task on one machine, confirm the alarm arrives within ~20 min,
   restart it, confirm recovery notification.
2. `progress` — point the state file at a log that is not advancing (or freeze the watched path)
   and confirm the alarm after the grace window.
3. `event` — fire a manual `/fail` with a test body, confirm the text arrives in Signal and mail.
4. Reboot survival — reboot one machine and confirm the heartbeat resumes with no login.
5. Part B only: with Part B armed, confirm on a reboot that auto-resume selects the correct first
   incomplete tag (dry-run mode first: log the decision without launching).
6. Confirm both channels independently. A single channel verified twice is one channel.

## 12. Out of scope

- The v3 and v2 sweep run plans and their launch (separate, on the user's signal).
- Any change to model code, KPIs or dashboards.
- Remote *intervention* capability beyond a power-button press. Deliberately excluded: the user
  is fully AFK and colleagues are explicitly limited to rebooting.
- Replacing the SSH-based collection of finished dashboards. Collection can resume when the user
  returns; this spec is about being *told*, not about pulling results.
