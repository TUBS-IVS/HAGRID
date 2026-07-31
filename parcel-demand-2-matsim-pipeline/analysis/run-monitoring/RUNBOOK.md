# Operator Runbook — Remote Crash Alerting for Unattended Sweep Runs

Spec: `docs/superpowers/specs/2026-07-31-remote-crash-alerting-design.md`
Config keys, script list, and the three accepted limitations: `README.md` in this
directory. This runbook does not repeat either — it covers what to click, what to
run, what to prove before leaving, and what to do when the phone buzzes.

**Situation this runbook is written for:** the researcher leaves for ~10 days with
no VPN and no remote access. Two Windows machines (sim-PC, dev-PC) each run a
multi-day MATSim sweep unattended. healthchecks.io alarms on silence and pushes to
the researcher's phone. The only intervention available while away is a colleague
pressing the power button once — on workdays only, no login, no scripts.

---

## 1. Setup checklist

Work through this in order, once per machine (sim-PC, then dev-PC, or vice versa —
each machine's block is independent, but within a machine the order matters).

### 1.1 healthchecks.io — create the eight checks

Create all eight checks up front, with these exact names and exact period/grace
settings:

| Check name | Period | Grace |
|---|---|---|
| `sim-alive` | 5 min | 15 min |
| `dev-alive` | 5 min | 15 min |
| `sim-progress` | 15 min | 30 min |
| `dev-progress` | 30 min | 60 min |
| `sim-sweep-finished` | 30 days | 1 day |
| `dev-sweep-finished` | 30 days | 1 day |
| `sim-resumed-after-boot` | 30 days | 1 day |
| `dev-resumed-after-boot` | 30 days | 1 day |

**The `sim-progress` / `dev-progress` thresholds differ on purpose — do not make
them match.** The 15/30 pair for `sim-progress` comes from a real measurement: the
largest legitimate quiet gap in 43 MB / ~70 h of real Step B log on the sim-PC was
81 seconds, so 30 minutes of grace is a roughly 22x margin on that machine's own
hardware and workload. The dev-PC has never run this scenario at all, and it has
63.5 GB RAM against the sim-PC's 128 GB — a tighter heap, so longer GC pauses are
plausible there but nothing has actually been measured. `dev-progress` is
deliberately given double the margin (30/60 instead of 15/30) to cover that
unknown. Tighten the dev-PC value once it has produced its own batch log to
measure from — do not carry the sim-PC's 15/30 over by assumption.

### 1.2 Notification channels

Attach the channel(s) you intend to use — email at minimum, Signal recommended as
a second, louder channel — to **all eight** checks. Do this before the per-machine
steps below; every check must already have its channel(s) attached when the
per-machine ping tests run in §2, or those tests won't prove anything.

### 1.3 Per machine: config files

1. Create `C:\Users\<user>\hagrid-tools\` on the machine if it does not exist yet.
2. Copy `hc-config.template.json` to `hagrid-tools\hc-config.json`. Fill in:
   - `AliveUrl`, `ProgressUrl`, `SweepFinishedUrl` — this machine's three ping
     URLs from `<pc>-alive`, `<pc>-progress`, `<pc>-sweep-finished`.
   - `LogDir` — the directory the heartbeat watches (e.g. the pipeline's
     `hagrid-output\logs`).
   - `LogPattern` — narrow enough to match exactly one file, e.g. `stepB_*.log`.
     The installer refuses `*.log`, `*`, or any other pattern that currently
     matches more than one file in `LogDir`.
   - `StatePath`, `LocalLogPath` — absolute paths for the heartbeat's own state
     file and local log.
3. **Never commit the filled `hc-config.json`, paste a ping URL into chat, or put
   one in a commit message.** A healthchecks.io ping URL is a capability URL —
   anyone holding it can forge heartbeats or fire alarms — and the HAGRID repo is
   public. Only the sanitised `hc-config.template.json` (placeholder UUIDs) is
   version-controlled. The filled `hc-config.json` lives only in
   `hagrid-tools\` on that machine.

### 1.4 Install the heartbeat FIRST

Elevated PowerShell:

```
powershell -NoProfile -ExecutionPolicy Bypass -File install_heartbeat_task.ps1 -ToolsDir C:\Users\<user>\hagrid-tools
```

This validates `hc-config.json` (rejects placeholders, rejects an ambiguous
`LogPattern`), probes `StatePath`/`LocalLogPath` for real writability, pings all
three URLs once, deploys `heartbeat.ps1` into `hagrid-tools\`, registers the
`HAGRID-Heartbeat` Scheduled Task (At-startup trigger, repeats every 5 minutes,
runs as SYSTEM), starts it once immediately, and reads back the local log to
confirm the first real cycle actually ran and actually persisted state. Read the
console output — it fails loudly (throws) rather than installing something silent
and broken; do not proceed past a thrown error.

### 1.5 Install the resume task SECOND, only if this machine gets Part B

1. Copy `resume-config.template.json` to `hagrid-tools\resume-config.json` and
   fill in every key (see README for what each one means): `LockPath`,
   `LockStaleHours`, `Tags` (the real tag list for this sweep), `RunIdPrefix`,
   `Suffix`, `OutputRoot`, `CarrierRoot`, `WorkDir`, `JavaExe`, `JvmArgs` (no
   default — sim-PC and dev-PC need different heap flags), `Jar`, `ArgTemplate`
   (no default — must match the sweep actually running), `GeneratedBatPath`,
   `ResumeLog` (must resolve **inside** `hc-config.json`'s `LogDir` and match its
   `LogPattern`), `StartDetached`, `StepALauncher`, `LocalLogPath`, `ResumedUrl`
   (this machine's `<pc>-resumed-after-boot` ping URL).
2. Never commit the filled `resume-config.json` — same capability-URL and
   public-repo reasoning as `hc-config.json`.
3. Run:

```
powershell -NoProfile -ExecutionPolicy Bypass -File install_resume_task.ps1 -ToolsDir C:\Users\<user>\hagrid-tools
```

**Why this must come second, not first or in parallel:** `install_resume_task.ps1`
reads `hc-config.json` and checks that `resume-config.json`'s `ResumeLog` actually
resolves inside `hc-config.json`'s `LogDir` and matches its `LogPattern` — this is
the *only* place that cross-check runs, and it runs only at install time. If
`hc-config.json` does not exist yet, `install_resume_task.ps1` refuses outright
("install and configure the heartbeat first"). If you ever change `LogDir` or
`LogPattern` in `hc-config.json`, or `ResumeLog` in `resume-config.json`, after
this point, nothing re-checks the pair automatically — re-run
`install_resume_task.ps1` to re-validate.

This registers `HAGRID-Resume` (At-startup trigger with a ~2 minute delay, runs as
SYSTEM) but does **not** start it immediately — it only fires on the next boot.

### 1.6 Repeat for the second machine

Steps 1.3–1.5 with that machine's own ping URLs and its own paths. Do not reuse
one machine's `hc-config.json` or `resume-config.json` on the other.

---

## 2. Verification that cannot be skipped

The automated test suite (`Test-Installers.ps1`) deliberately stops short of
calling either installer's real `Register-ScheduledTask`, so it only proves the
scripts parse, the cmdlet names exist, and the pure validation logic is correct.
Two things beyond that have now been confirmed on the real sim-PC (2026-07-31,
over SSH):

- **`Register-ScheduledTask` succeeds** with this code's exact trigger shape, as
  `SYSTEM` / `Highest`, and `Get-ScheduledTask` reads back `Repetition.Interval`
  `PT5M` with an **empty** `Repetition.Duration`. §2.1 below is therefore a
  confirmation, not a first attempt.
- **The SSH session is not UAC-filtered.** `IVS2000\Simrechner` is in
  Administrators, and a Windows OpenSSH session for such an account carries a
  full admin token — `IsInRole(Administrator)` returned `True`. So the whole of
  section 1 can be done remotely; physical access is not required to install.

Everything else below is still being proven for the first time. §2.2 (does the
repetition re-fire) and §2.4 (does the At-startup trigger fire at a real boot)
are the two that no amount of reading, testing, or registering can substitute.

### 2.0 Commissioning over SSH only

If you have no physical access, all of section 1 and §2.1–§2.3 work over
`ssh sim`. Two things behave differently and both bite silently:

- **Mapped network drives do not exist in an SSH session** — `Get-PSDrive` on the
  sim-PC over SSH lists `C:` only. This is the *same* blind spot a `SYSTEM`
  Scheduled Task has, which makes SSH a useful rehearsal: if a path works over
  SSH it will generally work for the task. Both installers now refuse any config
  path on a non-local drive letter or a UNC share for exactly this reason. On the
  dev-PC this is a live hazard — `T:`, `S:` and `X:` are mapped to
  `\\ad.tu-bs.de\share\ivs\*` and none of them exist for `SYSTEM`. Keep every
  configured path on `C:`.
- **Quote your remote commands via base64.** A local `$var` inside
  `ssh sim "..."` is expanded by the *local* shell. Use
  `powershell -NoProfile -EncodedCommand <base64-of-UTF16LE-script>`.

The one step SSH cannot make safe is §2.4: `Restart-Computer` works remotely, but
if the machine does not come back, nobody can press its power button until a
workday. Do that reboot while you still have days of slack, not on departure day.
Note also that **Wake-on-LAN is not a substitute** — it is armed on the sim-PC
(`WakeOnMagicPacket=Enabled`, MAC `D8-5E-D3-01-CF-9A`), but the sim-PC is on
`134.169.42.0/24` while the dev-PC is on a different subnet, and a PSU failure
removes the standby rail the NIC needs anyway.

### 2.1 The repetition trigger is actually every 5 minutes, not once

```
(Get-ScheduledTask -TaskName 'HAGRID-Heartbeat').Triggers.Repetition
```

Must show `Interval` = `PT5M` and an **empty** `Duration`. An empty `Duration`
means "repeat indefinitely"; anything else (or an error) means the task will stop
repeating after some finite window — exactly the kind of task that looks fine on
day one and silently stops on day four.

### 2.2 The repetition actually repeats

Checking the trigger definition only proves what was registered, not that Task
Scheduler is honoring it. **Wait at least 12 minutes**, then re-read the local
heartbeat log (`LocalLogPath` from `hc-config.json`, e.g. `heartbeat.log`). **Two
or more new lines is the only proof that it fires repeatedly** rather than once at
startup and never again. One new line is not enough — that's consistent with a
single startup firing.

### 2.3 State actually persists

Read the **first** line the heartbeat ever wrote to its local log. It must read
`stateSaved=True`. If it reads `stateSaved=False`, `StatePath` is not writable in
the Task Scheduler service's own context (even though the installer's own
writability probe passed) — the heartbeat cannot persist state, `Test-ProgressAdvanced`
will keep treating every cycle as a first observation, and the progress check will
alarm forever on a perfectly healthy machine. `install_heartbeat_task.ps1` is
supposed to catch this itself and throw rather than report success — if you ever
see a `stateSaved=False` line survive past install, treat it as a stop-ship
finding, not a warning to note and move past.

### 2.4 A real reboot proves the At-startup trigger

This is the mechanism the colleague's power-button press relies on completely — if
the At-startup trigger doesn't fire the Scheduled Task after a real power cycle,
nobody pressing a power button from workdays-only availability will bring the
machine back into a monitored or resumed state.

Do this reboot **either before the sweep has started, or with a `resume-config.json`
whose `Tags` are all already complete** (every tag has a dashboard under
`OutputRoot`) — otherwise a real reboot mid-sweep will trigger Part B's actual
delete-and-relaunch logic against an in-progress run.

After the reboot, `resume_sweep.log` (the `LocalLogPath` in `resume-config.json`)
must hold a fresh decision line — `all tags complete - nothing to do`, or a
tag-selection line, written with a timestamp after the reboot. No new line, or a
stale line from before the reboot, means the At-startup trigger did not fire and
Part B is not actually armed, regardless of what `Register-ScheduledTask` reported
at install time.

### 2.5 Provoke the `alive` alarm

Disable the heartbeat task on one machine:

```
Disable-ScheduledTask -TaskName 'HAGRID-Heartbeat'
```

Confirm the alarm arrives on the phone within ~20 minutes (5 min period + 15 min
grace). Then re-enable it and confirm a recovery notification:

```
Enable-ScheduledTask -TaskName 'HAGRID-Heartbeat'
```

### 2.6 Provoke the `progress` alarm — and put the grace back

Temporarily shorten that check's grace window in the healthchecks.io UI, and
point `hc-config.json`'s `LogDir` at a directory whose newest matching log does
not change (e.g. a folder with one static test file, or the real `LogDir` while
the sweep is not yet writing to it). Confirm the alarm fires once the shortened
grace elapses.

**Then restore the real grace window in the UI (30 min for `sim-progress`, 60 min
for `dev-progress`) and point `LogDir`/`LogPattern` back at the real, live sweep
log before leaving.** Forgetting either half of this leaves a check that will
either never see real progress again or will alarm on every legitimate gap once
the sweep resumes writing normally — a permanently false-alarming check on a
healthy machine, which is worse than no check at all because it teaches you to
ignore the phone.

### 2.7 Provoke one event push

Fire one manual `/fail` POST against one of the event checks (`sweep-finished` or
`resumed-after-boot`), and confirm the check **name** is recognisable in the
notification that arrives. Do not expect the message **body** to necessarily
appear — the healthchecks.io docs do not promise that an attached request body
reaches the outgoing notification, only that it is logged in the check's Events
list. The design deliberately puts the meaning in the check name for exactly this
reason; the body is a bonus, not something to depend on.

### 2.8 Confirm each notification channel separately

If both email and Signal are attached, provoke at least one alarm (any of §2.5–2.7
will do) with **each** channel confirmed independently as the one that delivered
it. A single channel verified twice — e.g. two alarms that both happened to land
in email while Signal was never actually checked — is one channel confirmed, not
two, regardless of how many test pushes were run.

---

## 3. What each alarm means and what to do

| Check that fired | Likely cause | Action |
|---|---|---|
| `sim-alive` **or** `dev-alive` alone (the other machine's `alive` still green) | That machine is genuinely down: power-off, reboot, network loss, OS hang | Text/message the colleague (§4) to press the power button once, on a workday. If Part B is installed and tags remain, auto-resume should pick up automatically ~2 min after boot — verify later via `resumed-after-boot` or the dashboard. |
| **Both** `sim-alive` and `dev-alive` fail at once | The campus network or healthchecks.io itself, not either machine's hardware — a single machine failing does not take the other one's independent outbound ping down with it | **Wait rather than escalate.** This is the two-machine discriminator the design exists to provide: a shared network/site outage looks identical to two simultaneous hardware deaths only if you ignore that both fired together. |
| `sim-progress` **or** `dev-progress` fails while that machine's `alive` is still green | The simulation died while the machine lives — JVM crash, hung run, stalled batch. This is exactly the class of failure a reachability check cannot see: the OS is fine, only the workload is dead. | If the crash was a single-tag JVM failure (like the 70v2 access violation), the batch may already have moved on to the next tag by itself — check whether `progress` later recovers on its own before assuming action is needed. If it does not recover, and Part B is installed, a colleague reboot triggers auto-resume. If Part B is not installed on that machine, the run is simply stalled until you return. |
| `sim-sweep-finished` / `dev-sweep-finished` | The batch reached its final completion marker normally | No action. This is good news delivered as an alarm because healthchecks.io only notifies on failure. |
| `sim-resumed-after-boot` / `dev-resumed-after-boot` | Auto-resume fired after a reboot and relaunched the remaining tags | No action needed for the resume itself. Note that this check does not re-arm itself after firing once (the resume script runs once per boot and exits) — a second crash-and-reboot later in the absence will not push a second notification on this specific check, though `alive` will still alarm on the crash itself, which is the load-bearing signal. |
| No alarm at all, for a long stretch | Could be genuine health — or a silent failure of healthchecks.io itself | See §5: pull the dashboard from the phone occasionally rather than trusting silence alone. |

---

## 4. The colleague message

Non-technical, forwardable as-is, no login and no scripts implied or requested —
only a single power-button press. Pick the version matching whichever machine's
`alive` check fired.

**If the sim-PC alarms:**

```
Hallo,

mir ist gerade eine Alarm-Meldung von meinem Simulationsrechner (dem "Sim-PC" im
Institut, Kürzel IVS2000) aufs Handy gekommen - der Rechner scheint ausgefallen
zu sein.

Waer es moeglich, kurz vorbeizuschauen und EINMAL den Power-Knopf zu druecken?
Mehr braucht es nicht - bitte nicht einloggen, keine Programme oder Skripte
starten, sonst nichts anfassen. Der Rechner macht den Rest automatisch.

Ich bin gerade nicht erreichbar (kein Zugriff von unterwegs) - deshalb diese
Bitte per Nachricht. Vielen, vielen Dank dir!

Viele Gruesse
Hendrik
```

**If the dev-PC alarms:**

```
Hallo,

mir ist gerade eine Alarm-Meldung von meinem zweiten Rechner (dem "Dev-PC", dem
Notebook im Buero/Institut) aufs Handy gekommen - der Rechner scheint
ausgefallen zu sein.

Waer es moeglich, kurz vorbeizuschauen und EINMAL den Power-Knopf zu druecken?
Mehr braucht es nicht - bitte nicht einloggen, keine Programme oder Skripte
starten, sonst nichts anfassen. Der Rechner macht den Rest automatisch.

Ich bin gerade nicht erreichbar (kein Zugriff von unterwegs) - deshalb diese
Bitte per Nachricht. Vielen, vielen Dank dir!

Viele Gruesse
Hendrik
```

(Both versions are ASCII-only on purpose, matching this codebase's convention for
anything that might be read or pasted in an environment with a non-UTF-8 console
codepage.)

---

## 5. Limits to accept

- **No colleague is available at weekends.** A hard failure on Friday evening
  costs up to ~2.5 days before anyone can press the power button. This is not
  fixed by this design and is not fixable remotely; it is accepted.
- **healthchecks.io itself could fail silently.** The mitigation is not a second
  monitoring service — that doubles the setup for little gain — but that the
  dashboard is *pullable*: pull it up on the phone occasionally during the trip
  rather than treating the absence of an alarm as proof of health. Silence is
  consistent with both "everything is fine" and "the alarm service is down."
- **BIOS "AC Power Recovery = Power On"** is the one setting that removes the
  human from the loop entirely — the machine restarts itself after a power loss
  with no colleague and no power-button press required. It converts the
  Friday-evening weekend gap above into a self-healing event instead of a ~2.5
  day one. It **cannot be set remotely** — if there is still an opportunity to be
  physically at the sim-PC before departure, setting this is the single
  highest-leverage action available.
