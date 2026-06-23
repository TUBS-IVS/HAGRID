# Lausitz Rail-PT + DRT-Intermodalität — Design Spec

**Date:** 2026-06-23 · **Status:** Design (to be turned into an implementation plan) · **Branch:** `hendrik`

**Supersedes a locked decision:** This spec **revises** the earlier "DRT-only, NO PT intermodality" decision from `docs/superpowers/specs/2026-06-17-lausitz-drt-freight-integration-design.md`. Rationale below. It builds on the completed **1b-prep** (passenger-only DRT_BASELINE, plan `docs/superpowers/plans/2026-06-23-lausitz-drt-baseline-prep.md`, commits `8415c00..d518ada`).

## Decision (user, 2026-06-23)

Keep **regional rail** in the simulation **across all three scenarios** (Baseline, Shared-Use, Modular) so DRT can act as a methodologically clean **feeder** to rail; **remove bus** so DRT is a genuine **substitute** for it. Effort + the scope revision are explicitly accepted.

**Why this is sound (and not a detour):** matsim-lausitz was *built* for DRT↔rail intermodality — `org.matsim.run.DrtAndIntermodalityOptions` + `PrepareTransitSchedule.tagIntermodalStops` + purpose-built `input/intermodal-area/pt-intermodal-areas-ruhland*.shp`. Bahnhof **Ruhland** (the rail stop `"Ruhland"`) sits in the clipped DRT service area and is a real regional source/sink; teleporting its rail trips (1b-prep's boot hack) throws that away. So we re-enable native machinery, we don't invent.

## Inspected facts (2026-06-23, against the real `lausitz-v2024.2-transitSchedule.xml.gz`)

- `<transportMode>` is tagged **per TransitRoute** → rail/bus/tram cleanly separable: **rail 1.201, bus 3.864, tram 39** routes. (Plan files carry NO mode distinction — unrouted `pt` legs — so filtering happens on the SCHEDULE, not the population.)
- Ruhland present: a `"Ruhland"` rail stop (the Bahnhof) distinct from the `"Ruhland Busbahnhof"`/`"Ruhland, …"` bus stops.
- Population (verified, full file): legs = car 3.61M / ride 618k / bike 468k / walk 458k / **pt 330k** / **drt 0**. pt is the umbrella mode = rail+bus combined; DRT is purely our addition.

## Scope

**In:**
1. Stage + rail-filter the transit schedule; re-enable rail PT in the run config.
2. DRT as intermodal access/egress to rail (native `DrtAndIntermodalityOptions` + a Ruhland intermodal-area shp).
3. Apply the rail layer to **all three scenarios** (common transit/rail config; freight integration still differs per scenario).
4. Undo the 1b-prep PT-strip pieces that conflict (teleported-pt, pt-removed-from-mode-choice, transit-off) — for the **rail** layer only.

**Out (this spec):** bus + tram (filtered out); freight/parcel LMD (= Project H / 1c onward, unchanged); the headline multi-iteration calibration.

**Explicit future option (user-requested, NOT in the first runnable version):** **inbound rail commuters.** The population is clipped by *home-in-area*, so travelers whose home is outside but who arrive at Ruhland/Hoyerswerda by rail are absent → the rail feeder is captured for *outbound/intra-zone* but not *inbound*. Planned as a follow-up (e.g. a cordon population or retaining rail-relevant out-of-area agents), to be designed after the rail baseline runs. The user flagged their absence as unsatisfactory; it is a tracked option, not dropped.

## Design

### A. Schedule preprocessing (new step)
- Stage `lausitz-v2024.2-transitSchedule.xml.gz` + `lausitz-v2024.2-transitVehicles.xml.gz` under `hagrid-input/lausitz/transit/` (currently remote SVN refs; ~1.4 MB schedule). Add `HagridPaths` getters.
- **Filter the schedule to `rail` routes only** (drop `bus` + `tram`; tram revisited only if Cottbus enters scope). Drop the now-unreferenced bus/tram TransitLines + their transit vehicles; keep rail lines, their routes, and the stops they serve. Verify the filtered schedule is self-consistent (no dangling stop/vehicle refs).
- *(Open: confirm the exact route→mode field used by the gtfs2matsim schedule and whether stop facilities need pruning or can stay as a superset.)*

### B. Config: re-enable rail PT (revise the 1b-prep `LausitzDrtConfigurator` strip)
- `transit.useTransit = true`; point `transitScheduleFile` → rail-filtered schedule, `transit.vehiclesFile` → rail transit vehicles (instead of nulling them).
- **Remove the teleported-pt boot fix** (the beeline `pt` TeleportedModeParams) — rail pt is now routed for real by SwissRailRaptor.
- **Re-add `pt` as a choice mode** in `subtourModeChoice.modes` (so agents can choose rail). Bus-only ODs can't be routed on a rail-only schedule → SwissRailRaptor fallback makes them unattractive → mode choice migrates them to **drt/car** (the intended substitution).
- Install/enable **SwissRailRaptor** (`SwissRailRaptorConfigGroup`) — the native PT router.
- Keep the other 1b-prep strips that remain correct: `counts` nulled (remote file), `simwrapper` removed.
- **`vehiclesSource`:** with transit on, rail vehicles come from the transit-vehicles file; passenger-mode vehicles still need handling (1b-prep set `defaultVehicle` after nulling the SVN `vehicles` ref). Re-confirm a consistent source now that rail vehicles are present (likely keep `defaultVehicle` for car + transit vehicles separate). To verify at implementation.

### C. DRT intermodality (native reuse)
- Use `DrtAndIntermodalityOptions`-style intermodal access/egress: add `drt` as a PT access/egress mode (`SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet`), gated to stops tagged `allowDrtAccessEgress=true`.
- Tag stops via `PrepareTransitSchedule.tagIntermodalStops(schedule, ShpOptions(intermodalAreaShp))` using a **Ruhland intermodal-area shp** from the clone (`pt-intermodal-areas-ruhland*.shp`). **Open:** pick the variant whose rail stations match the DRT service area — must cover **Ruhland AND Hoyerswerda** Bahnhof (check which variant includes Hoyerswerda; candidates: `ruhland`, `ruhland-spremberg`, `regional-solution`). Stage the chosen shp under `hagrid-input/lausitz/`.
- DRT stays **full-DVRP** (locked) and `serviceAreaBased`; intermodality lets in-zone DRT feed the rail stops.

### D. Cross-scenario consistency
- The rail+intermodality config is **common** to Baseline / Shared-Use / Modular (apples-to-apples). It lands in the shared DRT config path (the `LausitzDrtConfigurator`/composition), not per-scenario. Freight/parcel integration (1c/1d) layers on top unchanged.

## Affected components (anticipated; firmed up in the plan)
- `LausitzDrtConfigurator` — the central change (revert PT-strip for rail, add SwissRailRaptor + intermodal access/egress).
- New schedule-filter preprocessing (own class) + `LausitzDrtPreprocessor`/`PrepareLausitzDrtInputs` wiring (produce the rail-filtered schedule + tagged stops per run, or once as a staged artifact).
- `HagridPaths` — transit schedule / transit-vehicles / intermodal-area getters.
- `DATA-LAUSITZ.md` — schedule/vehicles/intermodal-area staging.
- Tests: rail-only filter (counts by mode), config asserts transit-on + pt-choice-mode + drt intermodal access/egress + no teleported-pt; e2e run with a rail leg routed + a DRT-access-to-rail trip.

## Risks / open items
- **`vspDefaultsCheckingLevel=abort` with PT on** may surface new VSP-defaults violations (intermodal/transit params) — enumerate at first run.
- **Compute:** PT + SwissRailRaptor + intermodal routing adds cost on top of full-DVRP DRT; the population clip stays the main bound.
- **Intermodal-area variant** must cover Hoyerswerda + Ruhland stations (confirm).
- **tram (39)** default-dropped; revisit if Cottbus enters scope.
- **Inbound commuters** = explicit follow-up after the rail baseline runs (see Scope).
- Schedule/vehicles are large + remote → stage once, git-ignored (per DATA-LAUSITZ.md).

## Next step
Turn this into a TDD implementation plan (writing-plans), starting with a spike: stage + rail-filter the schedule, confirm a rail leg routes and a DRT-access-to-Ruhland trip works end-to-end through the production path, then productionize across the config + all three scenarios.
