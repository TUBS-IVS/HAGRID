# Design Spec: DRT-Freight Integration for HAGRID
**Date:** 2026-06-05
**Status:** Draft — awaiting user review

---

## 1. Overview & Research Motivation

This document specifies the design for extending HAGRID with two new vehicle concepts that combine
freight and passenger transport. The goal is a three-way KPI comparison:

| Scenario | Vehicle Logic | Basis |
|---|---|---|
| **Baseline** | Dedicated freight fleet (current HAGRID) + dedicated DRT fleet (separate) | Current system |
| **Modular** | Vehicles swap between passenger capsule and cargo capsule at a central depot | Paper 1 (U-Shift/DLR) |
| **Dual-Use** | Vehicles carry passengers and parcels simultaneously in a single trip | Paper 2 (DRT-P2) |

**Research question:** Can integrated freight-passenger transport replace both conventional parcel
delivery and conventional ÖPNV in a rural/peri-urban area, and at what cost relative to dedicated
systems?

**Development path:** C (hybrid, time-window-based freight priority) → B (fully dynamic, online
re-optimization). The spec covers the full target state; implementation begins with step C.

---

## 2. Study Area

**PLZ 31535 Neustadt am Rübenberge**

| Parameter | Value |
|---|---|
| Total population | ~45,000 (town: 20k, surrounding area: 25k) |
| Area | ~340 km² |
| Character | Small central town + dispersed rural settlements |
| Rail connection | S-Bahn Hannover (line S2) |
| MATSim sample | 10% → ~4,500 agents |

**Why this area:**
- ÖPNV coverage is weak outside the town core → DRT as replacement is credible
- S-Bahn station provides a natural, defensible hub location for parcel transfer
- Asymmetric commuter flows (morning inbound to Hannover, evening outbound) match Paper 2's
  m:1 / 1:m trip modelling
- Small enough for rapid simulation iteration

**Hub / Depot Location:**
A single synthetic depot at **Neustadt (Rbge) S-Bahn station** is used for Phase 1.
- Modular: capsule swap point
- Dual-Use: parcel transfer point (parcels arrive here from Hannover LSP network)
- Multi-depot extension is explicitly planned as a sensitivity analysis (Phase 2+)

---

## 3. Population & Demand

### 3.1 Passenger Demand
Source: **MATSim Hannover Scenario** (TU Berlin, `matsim-scenarios/matsim-hannover`), clipped to
PLZ 31535 bounding box.
- 10% population sample
- Modes available: car, walk, (existing PT as background) + new DRT mode
- DRT trip-generation: agents with origin/destination in service area generate DRT requests
  at plan departure time (standard MATSim DRT mechanism)

Fallback: synthetic population via **PopulationSim** if Hannover scenario coverage for Neustadt
is insufficient.

### 3.2 Freight Demand
Neustadt am Rübenberge is already covered by the HAGRID demand pipeline — `Region.NEUSTADT`
exists in the `Region` enum and `filterRegions("Neustadt")` is already supported by
`ScenarioConfig`. No synthetic generation is needed; existing real CEP data is used directly.

**Demand filter:**
```java
// HAGRID2MATSimPipelineRunner.java
private static final String REGION = "Neustadt";  // instead of "Hannover"
```

**Carrier agents in DRT scenarios:**
In the classical pipeline, HAGRID creates one carrier per provider (DHL, GLS, etc.) per zone,
each with its own vehicle fleet. In DRT scenarios (Modular, Dual-Use), carriers serve only as
**demand representation units** — they define what needs to be delivered and where, but own no
vehicles. The shared DRT vehicle fleet handles all physical delivery. Provider identity (DHL vs.
GLS) is operationally irrelevant in integrated scenarios; carriers are aggregated per Ortschaft
(~8–12 for Neustadt).

**Service capacities:** Real HAGRID distribution is retained — no artificial 1–12 cap. Actual
parcel counts per service stop reflect real CEP data including higher-volume commercial addresses.

**Temporal distribution:** Existing HAGRID dispatch windows (07:00–14:00) and delivery time
windows (08:00–20:00) are used as starting point; aligned with Paper 1's recommendation to
distribute services across the day proportionally to population activity patterns.

---

## 4. Scenarios

### 4.1 Baseline
- **Freight:** existing HAGRID pipeline unchanged — jsprit plans tours, MATSim executes fixed plans
- **Passengers:** dedicated DRT fleet, no coordination with freight vehicles
- **Fleet sizing:** DRT fleet sized to 95th percentile passenger waiting time ≤ 7 min
- Purpose: establishes the KPI reference for dedicated (non-integrated) systems

### 4.2 Modular (U-Shift concept)
Based on Paper 1 (Combining DRT and freight, Berlin study).

**Vehicle:**
- Drive unit (driveboard): autonomous, electric
- Cargo capsule: 216 packages capacity (3 Euro pallets × volume factor 0.6, Paper 1/DLR)
- Passenger capsule: 8 seats (U-Shift specification, DLR documentation)
- **One capsule at a time** — never simultaneous freight + passengers

**Operational logic (Step C — hybrid):**
1. jsprit plans freight tours offline (pre-simulation, as today)
2. `FreightTourRequestCreator` converts each `CarrierPlan` into a `FreightTourRequest`
   with `submissionTime = plannedTourStart − retoolingTime`
3. DRT optimizer receives requests via a separate freight request channel
4. **Priority rule:** all passenger requests are handled first; freight requests are only
   dispatched if `idleVehicleShare > idleThreshold` (default: 0.50, configurable)
5. Freight request dispatched → vehicle drives to depot, swaps capsule (Activity, duration = retoolingTime),
   executes tour stops, returns to depot, swaps back
6. Rejected freight requests are **not replanned** in Step C (logged as failed tours)

**Step B extension (future):** online re-optimization of rejected tours; dynamic idle threshold.

**Depot activity:**
Capsule swap is modelled as a MATSim `FreightActivity` at the depot link:
- Duration: `retoolingTime` = 7 min (see assumptions)
- Required before AND after each freight tour

### 4.3 Dual-Use
Based on Paper 2 (DRT-P2, NetLogo study) — adapted for MATSim and urban/rural Hannover context.

**Vehicle:**
- 10 passenger seats + **20 parcels** cargo capacity (adapted from Paper 2's 2 m³; parcel-count
  unit is consistent with HAGRID's existing capacity model and simpler to reason about)
- Carries passengers and parcels simultaneously in the same trip

**Operational logic:**
1. HAGRID generates parcel demand (delivery addresses + parcel counts) — **no jsprit tours**
2. DRT optimizer extended with split capacity tracking: `(seatsOccupied, parcelsOnboard)`
3. Passenger requests: inserted dynamically as they arrive (standard DRT insertion optimizer)
4. Parcel insertion: executed **immediately before each new trip begins** (batch step):
   - For each pending parcel request: evaluate insertion cost `χ`
   - Accept if `χ < χ_conventional` (conventional delivery unit cost via VRP approximation)
   - Reject otherwise → parcel is logged as **undelivered in this simulation day** (no fallback
     fleet; undelivered rate δ is a key KPI that captures this penalty)
5. Parcels have **delivery time windows** (unlike Paper 2 assumption — we retain HAGRID TW logic)

**Virtual stops:**
200 m average spacing over Neustadt service area. Passengers walk up to 400 m to nearest stop.
Rationale: rural context makes fixed stops appropriate; improves route efficiency vs. door-to-door.

---

## 5. Architecture

### 5.1 Backward Compatibility

**The DRT extension is fully optional.** All existing HAGRID scenarios (`BASECASE`, `WHITE_LABEL`,
`UCC`, etc.) run exactly as today — no changes to demand generation, jsprit routing, or MATSim
execution. The DRT module is only loaded when a `DRT_*` scenario enum value is active. The
`HAGRID2MATSimPipelineRunner` simply sets `filterRegions("Neustadt")` and selects a DRT scenario
concept to activate the new path.

### 5.2 New Package Structure

```
parcel-demand-2-matsim-pipeline/src/main/java/hagrid/
├── demand/           ← unchanged
├── simulation/       ← HAGRIDScenarioBuilder extended (DRT module added when DRT_* active)
├── pipeline/         ← unchanged
└── drtfreight/                         ← NEW (only loaded for DRT_* scenarios)
    ├── ModularConfig.java              ← vehicle + depot parameters (retooling time, capsule caps)
    ├── DualUseConfig.java              ← split-capacity parameters (seats, parcel capacity)
    ├── IntegratedScenarioConfig.java   ← top-level config wrapping both
    ├── modular/
    │   ├── FreightTourRequest.java         ← wraps CarrierPlan as DRT request
    │   ├── FreightTourRequestCreator.java  ← CarrierPlan → FreightTourRequest
    │   ├── ModularDispatchLogic.java       ← extends DRT optimizer (idle threshold logic)
    │   └── CapsuleSwapActivityHandler.java ← models depot visit + swap as MATSim Activity
    ├── dualuse/
    │   ├── SplitCapacityVehicle.java       ← tracks (seatsOccupied, parcelsOnboard)
    │   ├── ParcelInsertionOptimizer.java   ← pre-trip parcel batch insertion
    │   ├── ConventionalDeliveryCostEstimator.java  ← χ_conventional via VRP approx
    │   └── DualUseDispatchLogic.java       ← extends DRT optimizer with split capacity
    ├── demand/
    │   └── VirtualStopNetwork.java         ← 200m stop grid over service area (Dual-Use)
    └── analysis/
        ├── IntegratedKPIHandler.java       ← collects both freight + passenger events
        └── IntegratedKPIReport.java        ← writes combined CSV / HTML report
```

Note: no `SyntheticFreightDemandGenerator` — real HAGRID demand filtered by `Region.NEUSTADT`.

### 5.3 New Maven Dependencies (pom.xml)

```xml
<dependency>
    <groupId>org.matsim.contrib</groupId>
    <artifactId>drt</artifactId>
    <version>${matsim.version}</version>
</dependency>
<dependency>
    <groupId>org.matsim.contrib</groupId>
    <artifactId>dvrp</artifactId>
    <version>${matsim.version}</version>
</dependency>
```

### 5.4 Data Flow

```
HAGRID Pre-Processing:
  Existing pipeline, Region.NEUSTADT filter active
    → CarrierAgents (aggregated per Ortschaft, ~8–12, no own vehicles in DRT scenarios)
    → jsprit (Modular + Baseline) → CarrierPlan XMLs
    → parcel demand list extracted from carrier services (Dual-Use only, no jsprit)

MATSim Simulation:
  HAGRIDScenarioBuilder (extended)
    + load MATSim Hannover population (clipped to PLZ 31535)
    + configure DRT module (fleet, service area, rebalancing)
    + register VirtualStopNetwork
    + load carrier plans (Baseline + Modular)
    ↓
  QSim:
    Baseline:   CarrierDriverAgents execute fixed plans
                DRT fleet serves passengers independently
    Modular:    FreightTourRequestCreator feeds requests to ModularDispatchLogic
                ModularDispatchLogic: passenger-first + idle-threshold dispatch
                Vehicles execute CapsuleSwapActivity at depot
    Dual-Use:   DualUseDispatchLogic: real-time passenger insertion
                Pre-trip: ParcelInsertionOptimizer batch-inserts parcels
    ↓
  IntegratedKPIHandler (all scenarios)
    ↓
  HAGRIDAnalysisRunner → IntegratedKPIReport
```

### 5.5 New HagridConfig Scenario Entries

```java
public enum Scenario {
    BASECASE, WHITE_LABEL, UCC, COLLECTION_POINTS,
    BATCHMODERATE, BATCHMEDIUM, BATCHHIGH, BATCHFULL,
    DRT_BASELINE,   // ← new
    DRT_MODULAR,    // ← new
    DRT_DUALUSE     // ← new
}
```

**What each DRT scenario activates:**

| Scenario | Freight fleet | Passenger fleet | Coupling |
|---|---|---|---|
| `DRT_BASELINE` | Dedicated carrier vehicles, jsprit plans, fixed MATSim execution (unchanged) | Separate DRT fleet, no freight interaction | None — two independent fleets |
| `DRT_MODULAR` | No dedicated vehicles; jsprit plans converted to `FreightTourRequest` objects | Shared DRT fleet handles both | `ModularDispatchLogic`: idle-threshold gate, capsule swap at depot |
| `DRT_DUALUSE` | No dedicated vehicles; carrier services extracted as parcel demand list | Shared DRT fleet with split capacity | `DualUseDispatchLogic`: simultaneous pax + parcel, pre-trip insertion |

The `HAGRIDScenarioBuilder` checks `hagridConfig.getScenario()` and only instantiates the
`drtfreight` module bindings when a `DRT_*` value is present.

---

## 6. Assumptions Catalogue

### 6.1 Adopted from Literature

| Assumption | Source | Rationale |
|---|---|---|
| Passenger priority > freight (dispatch order) | Paper 1+2 | Configurable via `idleThreshold` parameter |
| Offline jsprit tour planning (Step C) | Paper 1 | Justified by complexity; explicit Step C limitation |
| 95th percentile waiting time ≤ 7 min for fleet sizing | Paper 1 | Matches user intent; standard DRT benchmark |
| No replanning of rejected freight tours (Step C) | Paper 1 | Acceptable simplification for initial study |
| Freight submission look-ahead: 7 min | Paper 1 | Starting value; sensitivity analysis planned |
| Cargo capsule: 216 packages | Paper 1 / DLR | Well-documented U-Shift specification |
| 10 pax + 20 parcels Dual-Use capacity | Paper 2 (adapted) | Rural van context; parcel-count unit consistent with HAGRID |
| Virtual stops, 200 m spacing, max walk 400 m | Paper 2 | Rural context; retained for Dual-Use |
| No deliveries 00:00–08:00 | Paper 1 | Realistic operational constraint |
| Parcel temporal distribution aligned with population activities | Paper 1 | Prevents unrealistic tour clustering |

### 6.2 Adapted from Literature

| Assumption | Paper value | Our value | Reason for change |
|---|---|---|---|
| Retooling time (capsule swap) | Not quantified | **7 min** (param) | Video evidence ~30–60 s swap + overhead; sensitivity analysis over 2–15 min |
| Idle fleet threshold | 50% (Berlin) | **50% starting point**, calibrate for Neustadt | Berlin ≠ rural Hannover; different demand density |
| All parcels pre-collected at single terminal | Paper 2 | **At S-Bahn station depot** (single, then multi-depot SA) | No LSP depot on-site; S-Bahn station is defensible proxy |
| Dual-Use: no parcel time windows | Paper 2 | **Parcel TW retained** | HAGRID already models TW; removing them weakens freight realism |
| Virtual stops: door-to-door alternative | Paper 2 (rural) | **Virtual stops for Dual-Use; door-to-door option for sensitivity** | Rural context → virtual stops appropriate |

### 6.3 Rejected from Literature

| Assumption | Source | Why rejected |
|---|---|---|
| Carriers own no fleet in integrated scenarios | Paper 1 | In Modular + Dual-Use, carrier agents use the shared DRT vehicle fleet (no dedicated freight vehicles). Baseline retains its own dedicated freight fleet. The Paper 1 framing ("hiring DRT") is reframed as shared-fleet integration. |
| Virtual stops for Modular scenario | Paper 2 | Not applicable — Modular uses full DRT routing (no stop infrastructure needed) |
| Single terminal = MRT station as only parcel origin | Paper 2 | Multi-depot extension planned from the start (parameterised depot list) |

### 6.4 Stop Durations

The two vehicle concepts require different stop duration assumptions because they use
fundamentally different delivery modes (see assumption 6.3 / delivery type):

**Shared (both concepts):**

| Event | Duration | Source |
|---|---|---|
| Passenger boarding/alighting | 20 s base + 5 s/pax | Paper 2 / Harmann et al. (2025) |
| — Alternative value | 14 s flat | Paper 3 (industry partner); use for sensitivity |
| Terminal repositioning idle | 180 s | Paper 2 |

**Modular only** (jsprit-planned tour, classic CEP door-to-door delivery):

| Event | Duration | Source |
|---|---|---|
| Parcel delivery per stop | 2 min | HAGRID existing (`durationPerParcelMinutes`) |
| Capsule swap at depot | 7 min | Own assumption; sensitivity analysis 2–15 min |

**Dual-Use only** (delivery at virtual stop, not door-to-door):

| Event | Duration | Source |
|---|---|---|
| Parcel unloading at virtual stop | 30 s | Paper 3 (industry partner) |

The gap between 30 s and 2 min is substantial and must be explicitly justified in the paper:
Modular performs traditional door-to-door CEP delivery; Dual-Use performs stop-based drop-off
(comparable to a parcel locker or kerbside handover). Whether this is operationally realistic
and acceptable for recipients is an open research question to address in the discussion.

### 6.5 Cost Rates (Initial Estimates)

Starting points; detailed calibration deferred to implementation phase.

| Component | Rate | Source |
|---|---|---|
| DRT vehicle time cost | 25 €/h | Paper 2 / BVWP |
| DRT vehicle distance cost | 0.30 €/km | Paper 2 (electric) |
| Conventional freight reference | 35 €/h + 0.20 €/km | BVWP freight study |
| Passenger fare | 1.00 € + 0.20 €/km | Paper 1 (Berlin, adapted) |

These rates feed into: unit cost per parcel, unit cost per ride, and the parcel acceptance
threshold `χ_conventional` in the Dual-Use insertion optimizer.

### 6.6 Electric Vehicle Range

All DRT vehicles are assumed fully electric.

- Assumed daily range: ≥ 200 km (current commercial EV vans, e.g. Mercedes eSprinter)
- Expected daily distance per vehicle in Neustadt: ~80–150 km (rural low-density area)
- **Assessment:** range not expected to be a binding constraint; monitor in simulation output
- Charging: overnight at depot; mid-day opportunity charging at S-Bahn station optional

### 6.7 Delivery Mode (Open Assumption — requires decision)

**This assumption has direct consequences for both simulation design and real-world plausibility.**

| Concept | Delivery mode assumed | Stop duration |
|---|---|---|
| Modular | Door-to-door (classic CEP, jsprit-planned tour) | 2 min/stop |
| Dual-Use | Virtual stop drop-off (kerbside / parcel locker handover) | 30 s/stop |

The Dual-Use stop-based model is consistent with Papers 2 and 3, but raises recipient acceptance
questions: are residents willing to walk up to 400 m to retrieve parcels? This is a research
question to be addressed in the discussion section, not a modelling blocker. Both the operational
logic and the KPI interpretation depend on this assumption being clearly stated.

### 6.8 Open / Needs Validation

| Parameter | Current assumption | Validation source needed |
|---|---|---|
| Passenger capsule capacity (Modular) | 8 seats | DLR U-Shift technical documentation |
| Detour Acceptance Threshold (DAT) | 15 min (Paper 2 default) | Calibrate against rural mobility surveys |
| Operating hours DRT | 06:00–22:00 | Align with S-Bahn operating hours |
| Vehicle cruising speed | 30 km/h (Paper 2) | Appropriate for rural roads? Consider 35 km/h (Paper 3) |

---

## 7. KPI Framework

All KPIs computed by `IntegratedKPIHandler` and exported per scenario.

### 7.1 System-Level KPIs

| KPI | Unit | Description |
|---|---|---|
| Total fleet size | vehicles | DRT fleet + freight fleet |
| Total vehicle-km | km/day | All vehicle movements |
| Empty vehicle-km | km/day | Repositioning + deadhead |
| Vehicle utilisation | % | Time busy / total operational time |
| CO₂ equivalent | kg/day | Via vehicle-km × emission factor |

### 7.2 Passenger KPIs

| KPI | Unit | Description |
|---|---|---|
| Acceptance rate φ | % | Served requests / total requests |
| Mean waiting time | min | Request → pickup |
| 95th percentile waiting time | min | Fleet sizing criterion |
| Mean in-vehicle time | min | |
| Travel time stretch σ | ratio | actual / direct travel time |

### 7.3 Freight KPIs

| KPI | Unit | Description |
|---|---|---|
| Delivery rate δ | % | Parcels delivered / total parcels |
| Tour completion rate | % | Completed freight tours / planned |
| Mean delivery delay | min | Actual vs. planned delivery time |
| Parcels per vehicle-km | parcels/km | Freight efficiency |

### 7.4 Economic KPIs

| KPI | Unit | Description |
|---|---|---|
| Unit cost per parcel | €/parcel | Operator cost / delivered parcels |
| Unit cost per ride | €/ride | Operator cost / served passengers |
| Combined unit cost | €/(parcel+ride) | Joint cost efficiency |

Cost model: time-based (€/h, labour or capital equivalent for autonomous) + distance-based (€/km,
energy). Based on Paper 1 / BVWP cost rates.

---

## 8. Implementation Phases

### Phase 1 — Step C (Hybrid, time-window-based)
1. Add `matsim-contrib:drt` and `dvrp` dependencies
2. Implement `VirtualStopNetwork` (Dual-Use stop grid, 200 m spacing)
3. Implement `FreightTourRequestCreator` (Modular pipeline)
4. Implement `ModularDispatchLogic` (passenger-first + idle threshold)
5. Implement `CapsuleSwapActivityHandler`
6. Implement `SplitCapacityVehicle` + `DualUseDispatchLogic`
7. Implement `ParcelInsertionOptimizer` with `ConventionalDeliveryCostEstimator`
8. Implement `IntegratedKPIHandler` + `IntegratedKPIReport`
9. Extend `HAGRIDScenarioBuilder` + `HagridConfig` for new scenario types
10. Calibrate: fleet size (95th percentile), idle threshold, retooling time
11. Run 3-way comparison, produce KPI report

### Phase 2 — Step B (Fully dynamic) + Sensitivity Analyses
- Online re-optimization of rejected freight tours
- Multi-depot configuration (additional swap points → SA)
- Retooling time sensitivity (2 / 5 / 7 / 10 / 15 min)
- Idle fleet threshold sensitivity (30% / 40% / 50% / 60%)
- Fleet size sensitivity
- Freight look-ahead sensitivity (3 / 7 / 15 min)

---

## 9. Literature

1. **Paper 1** — DRT + Freight, Berlin/MATSim/U-Shift study. Key contributions: offline jsprit
   planning + DRT request channel, passenger-priority dispatch logic, idle-fleet threshold,
   U-Shift vehicle parameters (216 pkg cargo capsule).

2. **Paper 2** — DRT-P2, NetLogo study, rural context. Key contributions: simultaneous
   passenger + freight concept, pre-trip parcel batch insertion, split-capacity vehicle model,
   cost-threshold parcel acceptance, virtual stop infrastructure, comprehensive KPI set.

3. **Paper 3** — IDRT AnyLogic simulation, Sarstedt (rural Hannover/Hildesheim region, ~30 km
   from study area). Key contributions: regional benchmark for vehicle parameters (9 pax, 22 pkg,
   35 km/h, 30 s unloading, 14 s boarding), rebalancing strategy for idle vehicles, VRPPD +
   CVRP via Google OR Tools, fleet sizing reference (18 vehicles for comparable rural area).

4. **Paper 4** — Cargo Hitching, SimMobility, Singapore 2030. Key contributions: conceptual
   validation of simultaneous passenger + freight MOD; operational variant taxonomy (SHR: parcels
   only co-loaded with existing passengers; SHR+IDL: also idle vehicles serve parcels). Our
   Dual-Use design corresponds to SHR+IDL. The more restrictive SHR variant — where freight is
   only added to rides already carrying passengers — is not pursued here (idle-time parcel
   transport is an explicit design goal), but is a relevant extension if the concept is later
   applied to an urban Hannover scenario where ÖPNV competition makes pure idle-routing less
   attractive. Parameter values not transferable (urban Singapore, different scale).
