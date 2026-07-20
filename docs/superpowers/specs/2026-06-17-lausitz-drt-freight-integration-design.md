# Design Spec: DRT-Freight Integration for the Lausitz / Hoyerswerda Scenario

**Date:** 2026-06-17
**Status:** Draft — awaiting user review
**Predecessor:** [`2026-06-05-drt-freight-integration-design.md`](2026-06-05-drt-freight-integration-design.md)
(Neustadt am Rübenberge / matsim-hannover). This spec re-targets that concept to the
**100 % matsim-lausitz scenario** with **Hoyerswerda** as the study area and supersedes the
predecessor for the Lausitz study. Inherited content (KPI framework, assumptions catalogue,
literature) is reused and adapted; this document is self-contained for the decisions that changed.

---

## 1. Overview & Research Motivation

This document specifies the design for comparing three concepts of integrated passenger-and-freight
transport against a status-quo baseline, all in **one identical simulation setup** (same network,
same passenger demand, same parcel demand, same study area, 100 % sample).

| Scenario | Vehicle logic | Operator | Basis |
|---|---|---|---|
| **Baseline** | Dedicated DRT fleet + separate dedicated last-mile delivery (LMD) | **Multi-LSP** (status quo: DHL, UPS, … each own carriers/fleet) | Current system |
| **Shared-Use** (Scenario 1) | Minibuses carrying passengers **and** parcels **simultaneously**; split 2D capacity (seats + parcel slots) | **Einheitsunternehmen** (single operator) | Paper 2 / Paper 4 (cargo hitching) |
| **Modular** (Scenario 2) | Driveboard swaps between a passenger capsule and a cargo capsule at a depot — **one capsule at a time** (either/or) | **Einheitsunternehmen** (single operator) | Paper 1 (U-Shift / DLR) |

**Research question:** Can integrated freight-passenger transport replace both conventional parcel
delivery and conventional public transport (DRT) in a rural / peri-urban area such as Hoyerswerda,
and at what cost relative to dedicated systems?

**Operator dimension (deliberate analytical thread):** The integrated scenarios assume a single
unified operator ("Einheitsunternehmen"), because simultaneous passenger/freight operation across
competing LSP fleets is operationally infeasible. The Baseline, by contrast, models the **status-quo
multi-LSP reality** as a first pass ("what would actually happen today, and what changes if…").
This means the headline comparison conflates **two** effects: operational **consolidation**
(many LSPs → one operator) and vehicle-concept **integration** (coupling passengers and freight).
A **consolidated-operator Baseline variant** (single operator, still dedicated fleets) is kept as a
planned sensitivity to **decompose** these two effects — letting us argue that integration affects
not only vehicle concepts but also operations and infrastructure. The exact decomposition logic is
to be sharpened (see §11).

**Development path:** **Step C (hybrid)** → **Step B (fully dynamic)**. Phase 1 implements Step C and
delivers the complete 3-way comparison; Step B is veneer added once the pipeline and KPIs are
validated. See §9.

---

## 2. Study Area

**Hoyerswerda (Lausitz).** The study area is the **native DRT service zone already defined in
matsim-lausitz** (`LausitzDrtScenario`). This maximises comparability with the official scenario and
minimises setup.

| Parameter | Value / Decision |
|---|---|
| Base scenario | matsim-lausitz, **100 % sample** |
| Service area | Native Lausitz DRT zone for Hoyerswerda — **parameterised**, expandable |
| Sample | **100 % of the clipped population — non-negotiable** (see rationale) |
| Compute lever | **Study-area size only** (not sampling) |
| Depots / hubs | **2–3 parameterised depots** in/around Hoyerswerda (see below) |

**Why 100 % (no subsampling):** A population subsample would **structurally distort the LMD routing**.
Parcel stop density per street drives jsprit tour geometry; a 25 % sample yields ~¼ of the stops and
therefore a fundamentally different (and unrealistic) tour structure that cannot simply be scaled.
Freight realism requires the full demand. Computational load is therefore controlled **exclusively
via the size of the study area**, which is why the area must stay modest and is kept parameterised
so it can be tightened or widened.

**Parameterised study area:** The service-area shape must be a configurable input (shapefile /
geometry), **not hardcoded**, so the area can be expanded or modified without code changes. Geography
is selected via an explicit `StudyArea` dimension so Hannover and Lausitz coexist — see §5.5.

**Depots / hubs:** A single central pickup point in Hoyerswerda is logically clean but routing-
inefficient. Therefore the design uses a **small parameterised set of depots (default 2–3)** as a mix
of realism and routing efficiency. Depots serve as:
- **Shared-Use:** parcel pickup origin (parcels assigned to nearest depot)
- **Modular:** capsule-swap points
Single-depot and multi-depot configurations are sensitivity stages. Placement (real logistics sites
vs. routing-optimised distribution) is decided during calibration.

---

## 3. Population & Demand

### 3.1 Passenger Demand
Source: **matsim-lausitz 100 % plans**, clipped to the service zone. Agents with trips in the zone
use the **native Lausitz DRT** (mode, scoring, fares, rebalancing **unchanged**). No own passenger
generation — the native DRT setup is reused as-is to preserve comparability.

### 3.2 Parcel Demand (External Input Contract)
Parcel demand for Hoyerswerda is produced **outside this project** by the team's parcel-demand
estimation tool (the "Paketmengen" model; transferable Zensus-100m + OSM approach). This project
**consumes** its output — it does not generate or transfer the demand.

**Input contract** — per delivery point:

| Field | Description |
|---|---|
| Location | Coordinate / address (in the network CRS) |
| Type | **B2B / B2C** flag (drives delivery channel — see §4.2) |
| Count | Parcels at this point |
| Time window | Delivery TW (start/end) |
| Carrier | Provider tag (used only in the Multi-LSP Baseline; ignored under the single operator) |

HAGRID's existing `DemandProcessor` / `DeliveryGenerator` / `CarrierGenerator` ingest this file.

### 3.3 Operator Model & Carrier Representation
- **Baseline (Multi-LSP):** HAGRID's default — **one carrier per provider per zone**, each with its
  own dedicated vehicle fleet. Status-quo operational reality.
- **Integrated (Shared-Use, Modular) — Einheitsunternehmen:** the classical multi-LSP landscape is
  **dissolved into a single operator**. Carriers serve only as **demand-representation units**
  (what to deliver, where, when), aggregated **per Ortschaft**, and own **no** vehicles — the shared
  fleet handles all delivery. Provider identity (DHL vs. UPS) is operationally irrelevant here.
- **Planned variant (Option 1, sensitivity):** a **consolidated-operator Baseline** (single operator,
  still dedicated/un-integrated fleets) to isolate the consolidation effect from the integration
  effect.

---

## 4. Scenarios (Step C)

### 4.1 Baseline
- **Passengers:** native Lausitz DRT fleet, no coordination with freight.
- **Freight:** classical multi-LSP LMD — HAGRID produces jsprit tours per carrier; MATSim executes
  the fixed `CarrierPlan`s with dedicated freight vehicles (HAGRID's existing freight execution).
- **Two independent fleets.** Establishes the status-quo reference.

### 4.2 Shared-Use (Scenario 1) — Cargo Hitching, passenger-primary, online
Single operator, shared minibus fleet with **split 2D capacity** `(seatsOccupied, parcelsOnboard)`.

**Default operation:** conventional **minibus with a driver** who also performs the deliveries
(labour on, normal dwell). An **autonomous operation mode** (driver wage removed, robot-actuated
delivery → stretched dwell) is available as an orthogonal switch — see §4.4.

**Operational logic:**
1. HAGRID aggregates parcels to **street segments (between intersections)** — the same demand-
   aggregation / dwell computation it uses today, but **without jsprit tour optimisation** — and
   extracts **one delivery request per segment**. Each request is: **pickup at nearest depot →
   delivery via its channel** (below), with a **stretched dwell** that represents serving all parcels
   along the segment plus the in-segment travel (HAGRID's existing delivery-time model), and the
   segment's time window. The **online-insertion unit is therefore the segment stop, not the
   individual parcel** — this keeps HAGRID's delivery realism and cuts the number of insertion requests.
2. **Passengers** are inserted online as they arrive (native DRT insertion).
3. **Parcels** are inserted online into the same fleet (cargo hitching), subject to the 2D capacity.
   **Step C acceptance:** a **static rule** — accept the parcel insertion if its marginal insertion
   cost `χ < χ_threshold` (conventional unit-cost reference), else leave pending.
4. Parcels not delivered by end of day → logged as **undelivered** (no fallback fleet; the undelivered
   rate δ is a key KPI).

**Delivery channel logic (watertight, practice-near — replaces any naïve "drop at virtual stop"):**

| Parcel | Channel (Phase 1) | Stop duration |
|---|---|---|
| **B2B** | **Door-to-door, always** (direct delivery; signature/volume/time-critical) | ~2 min |
| **B2C** | **Packstation / Filiale first** — consolidated drop at nearest of N fixed locker/shop locations; recipient collects | ~30–60 s per locker batch |
| **B2C** | **Door-to-door fallback** — when no suitable locker (e.g. none in range, or configured door share) | ~2 min |

- **Channel shares** are **configurable parameters AND result KPIs** (and sensitivity levers) — they
  make the routing-efficiency trade-off explicit.
- **Ride-and-Collect (Mitnahme)** — parcel carried by its recipient agent when that agent makes a
  matching DRT trip (home ≈ delivery address) — is the "truest" cargo-hitching variant but requires
  parcel↔agent linkage and yields only partial, hard-to-control coverage. **Deferred to Phase 2.**
- There is **no 200 m virtual-stop grid for parcels**; the only fixed parcel points are the locker/
  Filiale locations (a handful). Passenger DRT stop behaviour follows the native Lausitz config.

### 4.3 Modular (Scenario 2) — U-Shift capsule swap, passenger-priority, hybrid
Single operator, shared fleet, **one capsule at a time** (passenger capsule **or** cargo capsule).
The operation mode is the §4.4 switch: in **fully-autonomous** operation the capsules carry **no driver**
(wage removed, speed cap + motorway exclusion apply — §6.3); in **accompanied** operation a human is
aboard and the vehicle drives conventionally (full speed, motorways, labour on). How the **last metres
of the parcel are actuated** is an explicit variant, because that is where labour cost and service
quality diverge:

| Variant | Delivery actuation | Labour | Dwell | Capacity | Phase |
|---|---|---|---|---|---|
| **Opt 2 — accompanied** | Door-to-door, **delivery attendant on board** | on | ×1 | full (216) | 1 |
| **Opt 3 — robot D2D** | Door-to-door via **delivery robot** | off | **× robotFactor** (longer) | full (216) | 1 |
| **Opt 1 — mobile Packstation** | Vehicle **positions as a rolling locker**; recipient collects (no D2D routing) | off | Packstation-style | **reduced** | 2 |

Opt 2 and Opt 3 are **the same code path** (offline jsprit tours, door-to-door) differing only in the
**§4.4 operation mode** — Opt 2 = accompanied (labour on, human dwell, **full speed, motorways permitted**),
Opt 3 = fully autonomous (labour off, robot dwell, **speed cap, motorway exclusion**) — so Phase 1 ships
**both** and they **bracket** the answer on all four axes: Opt 2 = "today's German regulation, what does
it cost?" (labour-heavy, time-light, full mobility), Opt 3 = "full autonomy, what is achievable?"
(labour-free, but longer dwell + speed cap + no motorways → more vehicle-hours / larger fleet). The truth sits between the two bounds, so no single future has to be
assumed. **Opt 1 is a different service pole** (collect-yourself instead of deliver-to-door); it reuses
the Shared-Use Packstation channel resolver but needs reduced capacity and reload cycles → **Phase 2**.

**Operational logic (Step C, Opt 2 / Opt 3):**
1. HAGRID's jsprit plans **freight tours offline** (as today) → `CarrierPlan`s.
2. `FreightTourRequestCreator` converts each plan into a `FreightTourRequest` with
   `submissionTime = plannedTourStart − (travelToDepot + retoolingTime)` — the vehicle must reach the
   swap point **and** swap before the planned start, so the look-ahead covers **both**, not the swap
   alone (§6.1).
3. The dispatch logic handles **passengers first**; a freight request is dispatched only if
   `idleVehicleShare > idleThreshold` (default 0.50, configurable).
4. Dispatched freight → vehicle **drives to depot** (a **routed network leg**, endogenous, *not* part
   of `retoolingTime`), **swaps capsule** (depot Activity, duration `retoolingTime` = **pure swap
   only**, default 7 min), executes the tour (door-to-door, dwell = HAGRID base × `deliveryDwellFactor`),
   returns (routed leg), swaps back. So each freight dispatch incurs **2 × pure swap + the routed
   to-depot / from-depot legs**.
5. Rejected freight requests are **not replanned** (logged as failed) — Step C limitation.

> **Opt 1 (Phase 2) note:** model reload **when the locker is empty** (capacity-limited), *not* a depot
> return after every positioning — otherwise deadhead dominates. One positioning serves many parcels at
> once; capacity is reduced vs. the 216-package capsule (lockers cost volume).

### 4.4 Operation mode (autonomy) — orthogonal switch (calibration lever)

Whether a delivering vehicle is **conventional (driver/attendant present)** or **autonomous
(driver wage removed, robot-actuated last metres → stretched dwell, capped top speed)** is modelled as
**orthogonal parameters that apply to both integrated scenarios**, so the concept stays consistent and
the realistic operating point can be explored during calibration *before* the headline evaluation:

| Parameter | Conventional | Autonomous |
|---|---|---|
| `cargoLabour` (delivery labour cost) | on (€/h) | **off** |
| `deliveryDwellFactor` (per-stop dwell multiplier) | 1.0 (human) | **> 1.0** (robot, slower) |
| `autonomousMaxSpeed` (vehicle **max-speed cap** `maximumVelocity`; mean speed is **emergent**) | network / road limits | **30 km/h** (default; sensitivity → 50 km/h, §6.1) |
| `autonomousExcludedRoadTypes` (**network access**, not speed) | full network | **no motorways** (mode-restricted links — see note below) |
| Passenger-side driver wage (native DRT rate) | included | **removed** |

The autonomy state is a **switch for both scenarios**, and the four effects above (labour, dwell, speed
cap, motorway access) are all consequences of it — **speed cap and motorway exclusion apply *only* in
autonomous operation**; with a human aboard the vehicle drives conventionally (full speed, motorways
permitted).

- **Shared-Use:** default = conventional (driver-minibus, the driver delivers; §4.2). Switching to
  autonomous removes **both** the passenger-driving and the parcel-delivery labour at once (one driver
  did both), applies the robot dwell, **and** activates the speed cap + motorway exclusion.
- **Modular:** **switchable too** — **Opt 2 = accompanied** (a Begleiter/Paketbote is aboard → full
  speed, motorways permitted, labour on, human dwell) vs. **Opt 3 = fully autonomous** (no human → speed
  cap, motorway exclusion, labour off, robot dwell). The "AV by definition" ideal is Opt 3; Opt 2 is the
  regulatory-realistic mode that **trades the autonomy advantage for legality** — incl. a Begleiter even
  in the **passenger** capsule, so pax-side labour returns too.

**Motorway exclusion (network access ≠ speed cap).** Autonomous vehicles are barred from motorways
(Autobahn). This is **not** achieved by the speed cap — a capped vehicle would merely crawl on a motorway
link and the router might still pick it. It is enforced the correct MATSim way: the autonomous DRT fleet
uses a **dedicated network mode whose allowed links exclude `motorway` / `motorway_link`**
(`autonomousExcludedRoadTypes`, optionally `trunk`), so DVRP/DRT routing never traverses them. Implemented
as a network-preprocessing step (HAGRID already filters the network) and cleaned with
`MultimodalNetworkCleaner` to keep the restricted sub-network connected. **To verify:** the zone and all
depots must stay reachable without motorways, else routing fails (§11). Speed cap and network access are
**two orthogonal autonomy effects** (how fast on allowed links vs. which links are allowed).

**Why this is not a free lunch (the honest trade-off):** autonomy lowers labour cost but **raises
vehicle-hours per task** — *primarily* through the longer robot door-dwell (Opt 3), and only
*secondarily* through the speed cap, which bites solely on links whose road limit exceeds the U-Shift
max (≥ 30 km/h ≈ the conventional rural cruising speed, so the cap is **not** the dominant effect). The
same demand therefore needs a **larger fleet and yields worse passenger waiting times**. Labour saving
and productivity loss pull in opposite directions; finding where the net lands is exactly what the
calibration sweep is for, and why Opt 2 / Opt 3 are reported as a **bracket**, not a single point.

---

## 5. Architecture

### 5.1 HAGRID stays the frontend; matsim-lausitz is a dependency

HAGRID remains the **control surface and orchestrator** across the whole workflow; matsim-lausitz is
consumed as a **dependency** (network, plans, native DRT config). The native DRT configuration is
**reused/composed, not reimplemented** — avoiding the comparability risk of hand-porting it.

```
HAGRID  (frontend + orchestration)
  1. Set parameters (scenario, fleet, depots, channel shares, thresholds, study-area shape, …)
  2. Preprocessing: input files  ->  jsprit CarrierPlans (Baseline + Modular)
                                 ->  parcel-request list (Shared-Use)
  3. Trigger the MATSim run  ──►  composes native Lausitz DRT config
                                  + freight/carriers
                                  + new integrated dispatch module
                                  + IntegratedKPIHandler
  4. Generate analysis dashboard  (HAGRIDAnalysisRunner / DashboardGenerator, extended)
```

> **Feasibility (build-tested 2026-06-18):**
> - **DRT/DVRP** (`org.matsim.contrib:drt`, `:dvrp`) resolve cleanly at HAGRID's current MATSim
>   `2025.0-2025w13` — ✅ no issue.
> - **matsim-lausitz via JitPack ❌ does NOT work out of the box.** `2.0` is not pre-built; JitPack
>   builds on demand but **fails** because it defaults to **JDK 8** while the project's plugins need
>   Java 11+ (`git-commit-id-maven-plugin` → "class file version 55.0 … up to 52.0"). Not fixable from
>   HAGRID (it's matsim-lausitz's repo + JitPack's JDK default).
> - **Native DRT setup confirmed:** run class `org.matsim.run.RunLausitzDrtScenario`, options
>   `DrtAndIntermodalityOptions`, service area under `input/drt-area`.
>
> **Consumption path (decide in planning):** (A) **build matsim-lausitz locally + `mvn install`** to the
> local repo, depend on its real coordinates [fastest to unblock]; (B) **fork + add `jitpack.yml`
> (`jdk: openjdk21`)**, consume the fork via JitPack [reproducible, low-maintenance]; (C) team Nexus /
> GitHub-Packages deploy; (D) **reuse only its config + `drt-area` data** and assemble the DRT config in
> HAGRID via the standard drt/dvrp API (data-not-code dependency).
>
> ⚠️ **Then: MATSim version alignment** — matsim-lausitz targets MATSim **2025.0-PR3552** (parent
> `org.matsim:matsim-all`); HAGRID targets **2025.0-2025w13** (`org.matsim:matsim`). Whichever path,
> align `matsim.version` and confirm the freight code still compiles.

### 5.2 Backward compatibility
The integrated module is **fully optional**. All existing HAGRID scenarios (`BASECASE`, `WHITE_LABEL`,
`UCC`, batch variants, …) run exactly as today. The new module loads only when a `DRT_*` scenario is
active. New Maven dependencies: `org.matsim.contrib:drt`, `org.matsim.contrib:dvrp`, and matsim-lausitz.

### 5.3 New package structure (in HAGRID)

```
parcel-demand-2-matsim-pipeline/src/main/java/hagrid/
└── integrated/                          ← NEW (only loaded for DRT_* scenarios)
    ├── IntegratedScenarioConfig.java     ← params: zone shape, fleet, depots, channel shares, thresholds, operation mode (cargoLabour, deliveryDwellFactor)
    ├── DepotNetwork.java                  ← 2–3 parameterised depots, nearest-depot assignment
    ├── modular/
    │   ├── FreightTourRequest.java        ← wraps a CarrierPlan tour as a request
    │   ├── FreightTourRequestCreator.java ← CarrierPlan → FreightTourRequest (submissionTime)
    │   ├── ModularDispatchLogic.java      ← passenger-first + idle-threshold gate
    │   └── CapsuleSwapActivity.java       ← depot visit + swap (retoolingTime)
    ├── shareduse/
    │   ├── SplitCapacityVehicle.java      ← tracks (seatsOccupied, parcelsOnboard)
    │   ├── ParcelRequest.java             ← depot pickup → delivery, + time window
    │   ├── DeliveryChannelResolver.java   ← B2B→Door ; B2C→{Packstation→Door}
    │   └── SharedUseDispatchLogic.java    ← online insertion, static acceptance (Step C)
    └── analysis/
        ├── IntegratedKPIHandler.java      ← collects freight + passenger events
        └── IntegratedKPIReport.java       ← combined CSV / dashboard input
```

### 5.4 Scenario enum
```java
public enum Scenario {
    BASECASE, WHITE_LABEL, UCC, COLLECTION_POINTS,
    BATCHMODERATE, BATCHMEDIUM, BATCHHIGH, BATCHFULL,
    DRT_BASELINE,    // ← new (multi-LSP freight + native DRT)
    DRT_SHAREDUSE,   // ← new (cargo hitching, 2D capacity)
    DRT_MODULAR      // ← new (capsule swap)
}
```

| Scenario | Freight handling | Passenger handling | Coupling |
|---|---|---|---|
| `DRT_BASELINE` | Multi-LSP jsprit plans, dedicated fleet, fixed execution | Native DRT, separate fleet | None |
| `DRT_SHAREDUSE` | Parcel-request list (no jsprit); single operator | Shared fleet, online | `SharedUseDispatchLogic`: 2D capacity, online parcel insertion, channel delivery |
| `DRT_MODULAR` | jsprit plans → FreightTourRequests; single operator | Shared fleet, online | `ModularDispatchLogic`: idle-threshold gate, capsule swap at depot |

### 5.5 Geography (`StudyArea` dimension) & pipeline I/O

**Today geography is hardcoded, not scenario-driven.** The `Scenario` enum (`HagridConfig`) is a
delivery-*concept* selector (BASECASE, WHITE_LABEL, …) and is **geography-agnostic**. "Hannover" is
baked in via (a) the hardcoded input paths in `HagridPaths` (`car_network_filtered_V2`,
`RH_useful__zone`, `KEP-hubs_v3`, `sim-config.xml`, …) and (b) the `Region` enum (sub-municipalities of
Region Hannover; `filterRegions` filters *within* that dataset). Neustadt was cheap precisely because it
shares Hannover's geography and inputs.

**New: an explicit `StudyArea` dimension, orthogonal to `Scenario`:**
- `StudyArea.HANNOVER` (**default → existing behaviour preserved**) and `StudyArea.LAUSITZ_HOYERSWERDA` (new).
- `HagridPaths` is refactored to resolve inputs under a **study-area-scoped input root**
  (e.g. `hagrid-input/<area>/…`). The Hannover input set stays untouched; the Lausitz set lives in
  parallel → **Hannover and Lausitz are simultaneously available, nothing is overwritten.**
- The `Region` enum stays **Hannover-only**; Lausitz uses a **shape-based service-area clip**
  (Hoyerswerda geometry), not the `Region` filter.
- The DRT_* scenarios **require** `StudyArea.LAUSITZ_HOYERSWERDA`; this coupling is validated at runtime,
  not hardcoded into the `Scenario` enum.

**A genuinely new input dimension — passengers & DRT.** HAGRID is currently **freight-only**. The DRT
scenarios add, to the MATSim input HAGRID composes, a **population (matsim-lausitz plans, clipped)** and
the **native DRT config** — parts of the pipeline that do not exist today.

**Pipeline I/O — what stays / changes (DRT scenarios only; non-DRT runs are unchanged):**

| Aspect | Today (Hannover) | New (Lausitz / DRT) |
|---|---|---|
| Network | `car_network_filtered_V2` | Lausitz network, clipped to service zone |
| Geography clip | `Region` enum filter | **shape-based service-area clip** (Hoyerswerda) |
| Passengers | — (freight only) | **NEW: matsim-lausitz plans** (clipped) |
| DRT config | — | **NEW: native Lausitz DRT config composed** |
| Parcel demand | Hannover demand shapefile | external tool output for Hoyerswerda (input contract, §3.2) |
| Freight output | CarrierPlans | Baseline/Modular: CarrierPlans; **Shared-Use: parcel-request list** |
| `shared/` inputs | Hannover `sim-config.xml`, zones | study-area-scoped variant (Lausitz config) |
| New outputs | freight KPIs | **integrated KPI report (passengers + freight)** |

**Reused unchanged:** the Demand→Carrier→jsprit→CarrierPlan preprocessing path, network filtering,
vehicle types, summary/dashboard. The output layout stays RUN_ID-based (`hagrid-output/{RUN_ID}/`,
`hagrid-matsim-output/{RUN_ID}/`); RUN_ID encodes the DRT scenario (e.g. `DRT_SHAREDUSE_…`).

---

## 6. Assumptions Catalogue

Inherited from the Neustadt spec and adapted to Lausitz/Hoyerswerda; all are parameterised defaults
with a sensitivity plan.

### 6.1 Vehicles & operations
| Assumption | Value | Source / note |
|---|---|---|
| Modular cargo capsule | 216 packages | Paper 1 / DLR U-Shift |
| Modular passenger capsule | 8 seats | DLR U-Shift (to confirm) |
| Base DRT vehicle (Baseline + Shared-Use) | **10 seats** | rev. 2026-07-20; standard/Baseline vehicle (was native 8 → re-baseline pending) |
| Shared-Use capacity | **8 seats + ~20 parcels (2D)** | rev. 2026-07-20: same base vehicle with **2 seats (back bench) repurposed → cargo** — the 2-seat loss is the physical cost of hitching (was 10+20). Parcel-count unit consistent with HAGRID |
| Retooling time (capsule swap) | 7 min | Own assumption; **pure swap only** (drive to/from the swap point is a separate routed leg); sensitivity 2–15 min |
| Idle-fleet threshold (Modular) | 0.50 | Paper 1 starting point; calibrate for Hoyerswerda |
| Freight submission look-ahead | **travelToDepot + retoolingTime** | must cover the approach leg **and** the swap, not the swap alone; sensitivity 3–15 min |
| Delivery dwell factor (robot / autonomous) | 1.0 conv. / > 1.0 robot | own assumption; calibration lever (§4.4) |
| Modular mobile-Packstation capacity (Opt 1) | reduced vs. 216 | lockers cost volume; Phase 2 |
| Vehicle cruising speed (conventional) | 30 km/h (rural); consider 35 km/h | Paper 2 / Paper 3 |
| Autonomous **max** speed (`autonomousMaxSpeed` = vehicle `maximumVelocity`) | **30 km/h default**, sensitivity **→ 50 km/h** (perspective; no further U-Shift source) | own assumption (U-Shift floor); **max-speed cap, not a mean — effective speed emerges from the network**; sensitivity lever (§4.4); **autonomous mode only** (not accompanied) |
| Autonomous road-class access (`autonomousExcludedRoadTypes`) | exclude `motorway` + `motorway_link` (optionally `trunk`) | AVs barred from the Autobahn; enforced via **mode-restricted network links, not the speed cap** (§4.4); **autonomous mode only** (not accompanied) |
| Fleet | fully electric | range ~200 km ≫ expected ~80–150 km/day → not binding; monitor |
| Operating hours | align with native DRT / S-Bahn hours | to confirm |

### 6.2 Stop durations
| Event | Duration | Note |
|---|---|---|
| Passenger boarding/alighting | 20 s + 5 s/pax (alt. 14 s flat) | Paper 2 / Paper 3 |
| B2B / B2C door-to-door delivery | ~2 min/stop | HAGRID `durationPerParcelMinutes` |
| B2C Packstation/Filiale drop | ~30–60 s per batch | Paper 3 |
| Capsule swap (Modular) | 7 min | own assumption |

### 6.3 Cost rates (initial; calibrate later)

**Labour is a separate, switchable component** (not baked into the vehicle-hour), so the autonomy
switch (§4.4) can turn it off per vehicle/capsule and the Shared-Use ↔ Modular cost contrast stays
explicit. The split is **anchored on the Rudolph LMD cost breakdown** (Ref. R), where the **driver is
~80 % of the per-parcel cost** across delivery configs — i.e. labour, not the vehicle, dominates.

| Component | Rate | Source |
|---|---|---|
| DRT vehicle time — **technical only** (energy, capital, maintenance; **no wage**) | ~5 €/h (≈ 20 % of the 25 €/h gross) | Rudolph share (Ref. R); calibrate |
| Driver / delivery labour — **switchable** (off when autonomous) | ~20 €/h (≈ 80 % of the 25 €/h gross) | Rudolph driver share (Ref. R); BVWP |
| DRT vehicle distance | 0.30 €/km (electric) | Paper 2 |
| Conventional freight reference | 35 €/h + 0.20 €/km | BVWP |
| Passenger fare / native DRT operating cost | native Lausitz DRT (driver wage **removed** for autonomous capsules) | reuse scenario, adjust for autonomy |

> The ~80 / 20 labour / vehicle split is an **order-of-magnitude anchor** from Rudolph's per-parcel
> breakdown (no DOI → treat as working/unpublished; per-parcel → per-hour is a valid proxy because both
> driver and vehicle accrue with tour duration). It gives the autonomy switch a defensible default and
> remains a **calibration parameter**, not a fixed claim.

### 6.4 Delivery channels (Shared-Use)
B2B → door-to-door (always). B2C → Packstation/Filiale-first → door-to-door fallback. Channel shares
are config parameters and KPIs. Ride-and-Collect deferred to Phase 2. The **operation mode**
(conventional vs. autonomous: `cargoLabour` / `deliveryDwellFactor`, §4.4) is orthogonal to the channel.

---

## 7. KPI Framework

Computed by `IntegratedKPIHandler`, exported per scenario.

| Group | KPIs |
|---|---|
| **System** | Total fleet size; vehicle-km; empty/deadhead vehicle-km; utilisation %; CO₂-eq (km × emission factor) |
| **Passenger** | Acceptance rate φ; mean & P95 waiting time; mean in-vehicle time; travel-time stretch σ |
| **Freight** | **Delivery rate δ (1 − undelivered)**; tour completion rate (Modular); mean delivery delay; parcels per vehicle-km |
| **Economic** | Unit cost per parcel; unit cost per ride; combined unit cost; **labour share of unit cost** (makes the autonomy effect explicit, comparable to Rudolph's breakdown) |
| **Channel (new, Shared-Use)** | Share delivered via door / Packstation; **undelivered rate** |

P95 passenger waiting time is the fleet-sizing criterion (≤ 7 min target).

**KPI CSV export (canonical, for cross-tool scenario comparison).** `IntegratedKPIReport` writes a
stable, tidy **long-format** CSV per run so scenarios diff cleanly across apps/scripts:

```
run_id, study_area, scenario, operation_mode, kpi_group, kpi_name, value, unit
```

One row per (scenario × KPI). Column names and units are stable across runs; new KPIs add rows, not
columns. A **wide variant** (one row per scenario, KPIs as columns) is emitted alongside for quick
spreadsheet pivots. Both land in `hagrid-output/{RUN_ID}/` next to the dashboard input.

---

## 8. Sustainability Derivative (separate study)

> A derivative study evaluates the three systems against the **Planetary Boundaries** concept
> (framing tbd), with **CO₂-eq as the simulation-based main indicator** and **noise and land use as
> qualitatively discussed secondary indicators** (not simulation-based scoring).

**Optional extensions (tbd — strike on review if unwanted):**
- **Extend CO₂-eq** to well-to-wheel **+ embodied emissions (vehicle production)**: because the
  scenarios differ mainly in **fleet size**, embodied emissions make the "fewer vehicles" benefit of
  integration visible in the climate dimension (operational EV CO₂ alone is similar across scenarios).
- **Bridge noise & land use** qualitatively from simulation KPIs: fleet size + depot/Packstation
  footprint + reduced curbside delivery-van presence.
- **Modal-shift side effect:** if DRT displaces private-car trips, additional CO₂/land savings beyond
  logistics — relevant to the Planetary-Boundaries narrative.

---

## 9. Implementation Phases

**Phase 1 — Step C (hybrid):**
1. Add `drt` + `dvrp` deps and the matsim-lausitz dependency; verify dependency vs. vendoring (§5.1).
2. Add the `StudyArea` dimension + refactor `HagridPaths` to a study-area-scoped input root
   (default `HANNOVER` preserves all existing runs); add the Lausitz input set (network, clipped
   matsim-lausitz plans, service-area shape, native DRT config) (§5.5).
3. `IntegratedScenarioConfig` + `DepotNetwork` (parameterised zone + 2–3 depots) — including the
   **operation-mode switches** `cargoLabour` + `deliveryDwellFactor` (§4.4), shared by both scenarios.
4. HAGRID preprocessing: **segment-aggregated** parcel-request list (Shared-Use) and FreightTourRequest
   path (Modular).
5. `DeliveryChannelResolver` (B2B door; B2C Packstation→door).
6. `SplitCapacityVehicle` + `SharedUseDispatchLogic` (online insertion of **segment stops**, static
   acceptance; conventional **or** autonomous via §4.4).
7. `FreightTourRequestCreator` + `ModularDispatchLogic` + `CapsuleSwapActivity` — door-to-door **Opt 2
   (attendant) and Opt 3 (robot)** from one path via `cargoLabour` / `deliveryDwellFactor`; `retoolingTime`
   = pure swap, look-ahead = approach + swap.
8. `IntegratedKPIHandler` + **canonical KPI CSV (long + wide, §7)** + dashboard extension.
9. Wire `DRT_BASELINE / DRT_SHAREDUSE / DRT_MODULAR` into HAGRID's runner (compose native DRT config).
10. Calibrate: fleet size (P95 ≤ 7 min), idle threshold, retooling time, channel shares, **operation
    mode (labour/dwell) — find the realistic operating point before the headline evaluation**.
11. Run the 3-way comparison; produce the KPI report/dashboard.

**Phase 2 — Step B + extensions:**
- Online re-optimisation of rejected freight tours; dynamic idle threshold.
- **Modular Opt 1 — mobile Packstation** (rolling locker: positioning instead of D2D routing, reduced
  capacity, reload-when-empty) reusing the Packstation channel resolver.
- **Ride-and-Collect (Mitnahme)** for Shared-Use.
- **Consolidated-operator Baseline variant** (decompose consolidation vs. integration effect).
- Sensitivities: retooling time, idle threshold, fleet size, depot count (1 / 2–3 / multi),
  channel shares, freight look-ahead, door-to-door vs. locker for B2C, **operation mode (labour/dwell)**.

---

## 10. Testing

- **Unit:** `DeliveryChannelResolver` (B2B→door, B2C cascade), `ModularDispatchLogic` (idle-threshold
  gate), `SplitCapacityVehicle` (2D capacity bookkeeping), `FreightTourRequestCreator` (submissionTime).
- **Integration smoke test:** a tiny scenario (few agents, few parcels, 1–2 depots) that exercises the
  full composition (native DRT + freight + dispatch module + KPI handler) and asserts it runs and
  produces a report. Mirrors HAGRID's existing test style.

---

## 11. Open Questions / To Verify

1. **How to consume matsim-lausitz** (build-tested 2026-06-18): JitPack ❌ fails (builds with JDK 8,
   project needs 11+). Options: (A) local build + `mvn install`; (B) fork + `jitpack.yml` (jdk 21);
   (C) team Nexus; (D) reuse only its config + `drt-area` data via the standard drt/dvrp API. **Then
   align MATSim version** (lausitz `2025.0-PR3552` vs HAGRID `2025.0-2025w13`). See §5.1.
2. **Native DRT zone extent** for Hoyerswerda — confirmed it exists (`input/drt-area` +
   `RunLausitzDrtScenario`); confirm exact geometry/extent when wiring; keep parameterised.
3. **Depot count & placement** (default 2–3; real sites vs. routing-optimised) — calibration decision.
4. **Operator-effect decomposition logic** — exact comparison design to separate consolidation from
   integration (consolidated-operator Baseline variant); to be sharpened with the user.
5. **Modular passenger capsule capacity** (8 seats) — confirm against DLR U-Shift documentation.
6. **Operating hours** — align with native DRT / S-Bahn service hours.
7. **Sustainability framing** — Planetary-Boundaries operationalisation (derivative study).
8. **Freight look-ahead** = approach leg + swap (not the bare 7 min) — confirm the routed approach-time
   estimate used at submission (§4.3, §6.1).
9. **Robot delivery dwell factor & labour split** (§4.4, §6.3) — provisional; pin down in the first
   calibration round (the operating point that yields realistic results).
10. **Autonomous max speed** (§4.4, §6.1) — vehicle max-speed cap (30 km/h default, sensitivity → 50 km/h;
    mean speed is emergent, not an input); its effect on fleet size / passenger waiting; sensitivity lever
    (always active for Modular, switchable for Shared-Use).
11. **Autonomous motorway exclusion** (§4.4, §6.1) — confirm the motorway link tagging in the
    matsim-lausitz network (road-type attribute) and that the zone + depots stay connected once
    motorway / `trunk` links are removed from the AV mode (`MultimodalNetworkCleaner`).

---

## 12. Literature

1. **Paper 1** — DRT + Freight (Berlin / MATSim / U-Shift): offline jsprit + DRT request channel,
   passenger-priority dispatch, idle-fleet threshold, 216-package cargo capsule.
2. **Paper 2** — DRT-P2 (NetLogo, rural): simultaneous passenger + freight, pre-trip parcel insertion,
   split-capacity vehicle, cost-threshold acceptance, KPI set.
3. **Paper 3** — IDRT (AnyLogic, Sarstedt, rural Hannover/Hildesheim): regional vehicle parameters
   (9 pax, 22 pkg, 35 km/h, 30 s unloading, 14 s boarding), rebalancing, fleet sizing reference.
4. **Paper 4** — Cargo Hitching (SimMobility, Singapore): variant taxonomy (SHR / SHR+IDL); our
   Shared-Use corresponds to SHR+IDL (idle vehicles also serve parcels).
- **Ref. R** — Rudolph, *Last-mile delivery cost comparison* (Van / Utility / Tricycle / Bicycle, DC vs.
  UCC): per-parcel cost split into driver / vehicle / fuel / UCC; **driver ≈ 80 %** of per-parcel cost
  across configs. Published **without DOI** — cite as working/unpublished; used here as an
  order-of-magnitude anchor for the labour / vehicle cost split (§6.3).
