# HAGRID Pipeline — Complete Documentation

> **HAGRID** — **H**annover **A**gent-based **G**oods and f**R**eight **I**ntegrated **D**emand  
> A MATSim-based last-mile delivery simulation framework for the Hannover metropolitan region.

This document provides a comprehensive reference for the entire HAGRID pipeline: from configuring parcel delivery scenarios and generating MATSim freight input to running simulations, producing analysis dashboards, and interpreting results.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [End-to-End Workflow](#2-end-to-end-workflow)
3. [Pipeline Runner Configuration](#3-pipeline-runner-configuration)
   - 3.1 [Scenario Selection](#31-scenario-selection)
   - 3.2 [Version Tags](#32-version-tags)
   - 3.3 [Vehicle Configuration](#33-vehicle-configuration)
   - 3.4 [Dispatch Scheduling](#34-dispatch-scheduling)
   - 3.5 [Delivery Time Windows vs. Dispatch Windows](#35-delivery-time-windows-vs-dispatch-windows)
   - 3.6 [Provider-Specific Overrides](#36-provider-specific-overrides)
   - 3.7 [Pipeline Execution Options](#37-pipeline-execution-options)
4. [Running the Pipeline](#4-running-the-pipeline)
5. [Standalone Simulation Runner (CLI)](#5-standalone-simulation-runner-cli)
6. [Analysis Dashboard Generator (CLI)](#6-analysis-dashboard-generator-cli)
7. [Directory Structure & Output Files](#7-directory-structure--output-files)
8. [Vehicle Type System (In-Depth)](#8-vehicle-type-system-in-depth)
9. [Dispatch Schedule System (In-Depth)](#9-dispatch-schedule-system-in-depth)
10. [Scenario Concepts](#10-scenario-concepts)
11. [Complete Configuration Reference](#11-complete-configuration-reference)
12. [Prerequisites](#12-prerequisites)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Architecture Overview

The HAGRID system consists of three major stages that can be run together or independently:

```
┌──────────────────────────────────────────────────────────────────────┐
│  STAGE 1: Demand Pipeline (HAGRID2MATSimPipelineRunner)             │
│  ───────────────────────────────────────────────────────             │
│  Reads: parcel demand shapefiles, hub locations, network            │
│  Produces: MATSim carrier + vehicle XML files for simulation        │
│                                                                      │
│  Steps:                                                              │
│   1. Load parcel demand from shapefiles                              │
│   2. Assign parcels to carrier depots (hub allocation)               │
│   3. Create delivery + supply carriers with vehicle fleets           │
│   4. (Optional) Merge overlapping services (service simplifier)      │
│   5. (Optional) Route carriers with jsprit VRP solver                │
│   6. Write scenario summary                                         │
├──────────────────────────────────────────────────────────────────────┤
│  STAGE 2: MATSim Simulation (HAGRIDSimulationRunner)                │
│  ───────────────────────────────────────────────────────             │
│  Reads: carrier XMLs, vehicle types, network from Stage 1           │
│  Produces: MATSim output (events, plans, network)                   │
│                                                                      │
│  Steps:                                                              │
│   1. Load freight zones from shapefile                               │
│   2. Build MATSim scenario (carriers, network, vehicles)             │
│   3. Configure jsprit VRP within MATSim (iterations, constraints)    │
│   4. Run MATSim simulation (N iterations)                            │
│   5. Output events, carriers, network as compressed XML              │
├──────────────────────────────────────────────────────────────────────┤
│  STAGE 3: Analysis Dashboard (HAGRIDAnalysisRunner)                 │
│  ───────────────────────────────────────────────────────             │
│  Reads: MATSim output events, carriers, network                     │
│  Produces: single-file interactive HTML dashboard                    │
│                                                                      │
│  Dashboard sections:                                                 │
│   • KPI strip (fleet size, parcels, costs, distances)                │
│   • Interactive tour map (Leaflet.js, colour by provider/carrier)    │
│   • Vehicle utilisation & load factor charts                         │
│   • Tour timing distributions (departure / arrival / duration)       │
│   • Distance histograms                                              │
│   • Provider & vehicle-type breakdown (Chart.js)                     │
│   • Cost analysis with per-vehicle detail tables                     │
│   • Network traffic heatmap                                          │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. End-to-End Workflow

### Quick-Start (All Three Stages)

```
1. Configure scenarios     →  Edit HAGRID2MATSimPipelineRunner.java
2. Run demand pipeline     →  mvn compile exec:java  (or run_hagrid_sim.bat)
3. Run MATSim simulation   →  java hagrid.HAGRIDSimulationRunner "concept=basecase,date=2025-05-13,jspritIter=10000"
4. Generate dashboard      →  java hagrid.HAGRIDAnalysisRunner "concept=basecase,date=2025-05-13,jspritIter=10000"
   (or use run_analysis.bat)
```

### Data Flow

```
parcel-demand-estimation/          ← Python notebooks generate demand shapefiles
        │
        ▼
hagrid-input/demand/{runId}/       ← Shapefiles per concept+date
        │
        ▼
STAGE 1: Pipeline Runner           ← Generates carrier XML + vehicle types
        │
        ▼
hagrid-output/{RUN_ID}/            ← Carrier files, vehicle types, network
        │
        ▼
STAGE 2: MATSim Simulation         ← Runs agent-based freight simulation
        │
        ▼
hagrid-matsim-output/{RUN_ID}_iter{N}_jsprit{M}/
        │
        ▼
STAGE 3: Analysis Dashboard         ← Reads events + carriers → HTML
        │
        ▼
.../analysis/{RUN_ID}_dashboard.html  ← Interactive dashboard
```

---

## 3. Pipeline Runner Configuration

All scenario configuration is done in **`HAGRID2MATSimPipelineRunner.java`** using a fluent builder API. The runner file has two sections:

1. **Global Settings** — constants that apply to all scenarios
2. **Scenario Definitions** — one or more `scenario(...)` calls

### 3.1 Scenario Selection

#### Concepts

```java
.concepts("basecase")                          // single concept
.concepts("basecase", "batchHigh")             // multiple concepts
```

Each concept corresponds to a different logistics strategy. See [Section 10](#10-scenario-concepts) for available concepts.

#### Dates

```java
.dates(LocalDate.of(2025, 5, 13))                              // single date
.dates(LocalDate.of(2025, 5, 13), LocalDate.of(2025, 5, 14))   // multiple dates
```

When multiple concepts AND multiple dates are specified, the pipeline runs the **Cartesian product** — every concept × every date.

#### Region Filter

```java
.filterRegions("Hannover")
```

Filters demand data to parcels within the specified geographic region.

### 3.2 Version Tags

Tags allow you to create **multiple versions** of the same concept+date combination with different configurations (e.g., different vehicle sizes, schedules, or iterations).

```java
// Without tag → runId = "BASECASE_13052025"
scenario("basecase", LocalDate.of(2025, 5, 13), "m", "l")

// With tag "V1" → runId = "BASECASE_13052025_V1"
scenario("basecase", "V1", LocalDate.of(2025, 5, 13), "100_l")

// With tag "bikes" → runId = "BASECASE_13052025_bikes"
scenario("basecase", "bikes", LocalDate.of(2025, 5, 13), "bike")
```

**Important:** The tag only affects the **output directory** name. The **demand input** is always looked up by concept+date only (without tag), since the underlying parcel demand is identical for all versions of the same concept and date.

```
hagrid-input/demand/basecase_13052025/          ← shared demand (no tag in path)
hagrid-output/BASECASE_13052025/                ← output without tag
hagrid-output/BASECASE_13052025_V1/             ← output with tag "V1"
hagrid-output/BASECASE_13052025_bikes/          ← output with tag "bikes"
```

### 3.3 Vehicle Configuration

#### Built-in Vehicle Types (Aliases)

| Alias | Type ID | Capacity (parcels) | Description |
|-------|---------|-------------------:|-------------|
| `"m"` | `ct_cep_size_m` | 165 | Medium CEP van — standard delivery vehicle |
| `"l"` | `ct_cep_size_l` | 230 | Large CEP van — high-capacity delivery vehicle |
| `"bike"` | `ct_cep_bike` | 23 | Cargo bike — for inner-city / low-emission delivery |

These aliases use the **original costs, speed, and parameters** defined in `HAGRID_vehicleTypes2.0.xml`.

#### Custom Capacity Vehicles

You can create vehicles with **custom capacities** while inheriting all other properties (speed, fixed costs, per-km costs, per-second costs) from a base type:

| Format | Example | Type ID | Capacity | Base Template |
|--------|---------|---------|----------:|---------------|
| `"capacity_type"` | `"60_m"` | `ct_cep_60_m` | 60 | Medium van (costs, speed from `m`) |
| `"capacity_type"` | `"100_l"` | `ct_cep_100_l` | 100 | Large van (costs, speed from `l`) |
| `"capacity_type"` | `"50_bike"` | `ct_cep_50_bike` | 50 | Cargo bike (costs, speed from `bike`) |
| `"capacity_type"` | `"80_l"` | `ct_cep_80_l` | 80 | Large van (costs, speed from `l`) |
| Numeric only | `"80"` | `ct_cep_80_m` | 80 | Auto: `m` if ≤165, `l` if >165 |
| Numeric only | `"200"` | `ct_cep_200_l` | 200 | Auto: `l` because 200 > 165 |

**Usage:**

```java
// Standard: medium + large vans
.vehicleSizes("m", "l")

// Custom 60-capacity medium vans only
.vehicleSizes("60_m")

// Mix of standard and custom
.vehicleSizes("m", "100_l")

// Cargo bikes
.vehicleSizes("bike")

// Just specify a number — auto-selects base type
.vehicleSizes("80")
```

**Default:** `"m", "l"` (both medium and large vans with original capacities).

#### How It Works

When a custom capacity string is provided (e.g., `"60_m"`):
1. The system looks up the base template type (e.g., `ct_cep_size_m`)
2. Creates a **new vehicle type** `ct_cep_60_m` by cloning all properties
3. **Overrides only the capacity** to the specified value (60)
4. All costs (fixed cost, cost per km, cost per second) and speed limits remain from the original template

This means a `"60_m"` vehicle has the same operational costs as a standard medium van, but carries fewer parcels — useful for testing how fleet composition affects delivery efficiency.

### 3.4 Dispatch Scheduling

The dispatch schedule determines **when vehicles start their delivery tours**. This is controlled by two interacting settings:

1. **Vehicle Schedule** — defines the *candidate* dispatch hours
2. **Dispatch Window** — defines the *allowed* time range

#### Vehicle Schedule Presets

| Preset | Candidate Hours | Description |
|--------|---------------:|-------------|
| `SIMPLE_STAGGERED` | 7, 14 | **Default.** Two shifts: morning (07:00) and afternoon (14:00). Models a typical two-wave delivery pattern. |
| `EXTENDED` | 7, 11, 14 | Three shifts: early morning, midday, and afternoon. Allows finer temporal distribution of tours. |
| `FULL_WINDOW` | Every hour in window | One dispatch per hour within the entire window. E.g., window 7–14 → dispatches at 7, 8, 9, 10, 11, 12, 13, 14. Maximises fleet utilisation flexibility. |
| `EARLY_ONLY` | startHour − 1 | Single early dispatch one hour before the window starts. E.g., window 8–14 → dispatch at 07:00 only. |

#### How Schedule + Window Interact

The dispatch window **filters** the schedule's candidate hours. Only hours that fall **within** the window are kept:

```
Effective dispatch hours = candidate hours WHERE hour >= windowStart AND hour <= windowEnd
```

**If no candidate hours fall within the window**, the system uses a **fallback**: the window's start hour becomes the sole dispatch time.

#### Examples

| Schedule | Window | Candidate Hours | Filter Result | Effective Dispatches |
|----------|--------|---------------:|:-------------|:---------------------|
| `SIMPLE_STAGGERED` | 7–14 | 7, 14 | both in range | **7, 14** |
| `SIMPLE_STAGGERED` | 8–14 | 7, 14 | 7 < 8 → dropped | **14** (morning wave lost!) |
| `SIMPLE_STAGGERED` | 7–12 | 7, 14 | 14 > 12 → dropped | **7** (afternoon wave lost!) |
| `SIMPLE_STAGGERED` | 10–12 | 7, 14 | both out of range | **10** (fallback to start) |
| `EXTENDED` | 7–14 | 7, 11, 14 | all in range | **7, 11, 14** |
| `EXTENDED` | 9–14 | 7, 11, 14 | 7 < 9 → dropped | **11, 14** |
| `FULL_WINDOW` | 8–12 | 8,9,10,11,12 | all generated in range | **8, 9, 10, 11, 12** |
| `FULL_WINDOW` | 7–14 | 7–14 (all hours) | all in range | **7, 8, 9, 10, 11, 12, 13, 14** |
| `EARLY_ONLY` | 8–14 | 7 (= 8−1) | 7 is result | **7** |
| `EARLY_ONLY` | 7–14 | 6 (= 7−1) | 6 is result | **6** |

> **Key takeaway:** If you use `SIMPLE_STAGGERED` but set your dispatch window to start at 8, you will **lose the 07:00 morning wave**. The window acts as a hard constraint on when vehicles can depart.

#### Usage

```java
// Global schedule for all providers
.vehicleSchedule(VehicleSchedule.SIMPLE_STAGGERED)

// Global dispatch window (when vehicles can start tours)
.dispatchWindow(7, 14)
```

### 3.5 Delivery Time Windows vs. Dispatch Windows

These are **two separate concepts** that serve different purposes:

| Setting | Purpose | Default | Effect |
|---------|---------|---------|--------|
| **Dispatch Window** `.dispatchWindow(start, end)` | When vehicles can **start their tours** (leave the depot) | 7–14 | Controls fleet scheduling. Filters schedule candidate hours. |
| **Delivery Time Window** `.deliveryTimeWindow(start, end)` | When deliveries **should arrive** at customers | 8–20 | Used for **scoring penalties** in MATSim. Deliveries outside this window incur a time-window penalty. |

```java
.dispatchWindow(7, 14)              // Vehicles can depart between 07:00 and 14:00
.deliveryTimeWindow(8, 20)          // Deliveries should arrive between 08:00 and 20:00
```

**Typical setup:** Dispatch starts at 07:00 so first deliveries begin around 08:00 (after travel to first stop). The delivery window extends to 20:00 to allow afternoon tours to complete.

### 3.6 Provider-Specific Overrides

Every setting can be customised per delivery provider. Provider names are **case-insensitive** (e.g., `"Amazon"`, `"amazon"`, `"AMAZON"` all refer to the same provider).

#### Per-Provider Vehicle Sizes

```java
// Amazon uses only large vans
.providerVehicleSizes("amazon", "l")

// DHL uses custom 60-capacity medium vans + large vans
.providerVehicleSizes("dhl", "60_m", "l")
```

#### Per-Provider Schedule

```java
// Amazon dispatches every hour (more flexible scheduling)
.providerSchedule("amazon", VehicleSchedule.FULL_WINDOW)
```

#### Per-Provider Dispatch Window

```java
// Amazon operates a later dispatch window
.dispatchWindow("amazon", 9, 17)
```

#### Per-Provider Time Shift

Shifts **all dispatch hours** for a specific provider. Useful for modelling providers that consistently start operations later or earlier than others:

```java
.providerTimeShift("Amazon", +1)   // Amazon starts 1 hour later than schedule says
.providerTimeShift("DHL", -1)      // DHL starts 1 hour earlier
```

**How time shifts work with schedules:**

| Base Schedule (SIMPLE_STAGGERED) | Time Shift | Effective Hours |
|---------------------------------:|:----------:|:----------------|
| 7, 14 | +1 | **8, 15** |
| 7, 14 | -1 | **6, 13** |
| 7, 14 | +2 | **9, 16** |

Hours are clamped to [0, 23] — a shift cannot produce negative hours or hours > 23.

#### Per-Provider Custom Dispatch Hours

For complete control, bypass the schedule system entirely:

```java
// DHL dispatches at exactly these hours (ignores schedule preset entirely)
.providerDispatchHours("dhl", 6, 10, 14, 18)
```

> **Note:** Custom dispatch hours **override** the schedule preset for that provider. Time shifts are still applied on top.

#### Priority Resolution Order

When computing the effective dispatch hours for a provider:

```
1. Custom dispatch hours (if set)  →  apply time shift  →  done
2. Otherwise: schedule preset + dispatch window  →  filter  →  apply time shift  →  done
```

### 3.7 Pipeline Execution Options

```java
// Merge overlapping carrier services to reduce complexity
.applyServiceSimplifier(true)         // default: false

// Run jsprit VRP routing after demand generation
.runRouting(true)                     // default: false

// Number of jsprit iterations for VRP optimisation
// Higher = better routes but slower. Use 1 for quick tests, 20-50+ for production.
.jspritIterations(1)                  // default: 1

// Cache routing results for faster repeated runs
.enableCaching(false)                 // default: true
```

---

## 4. Running the Pipeline

### Option 1: Via Maven

```bash
cd parcel-demand-2-matsim-pipeline
mvn clean compile exec:java -Dexec.mainClass="hagrid.HAGRID2MATSimPipelineRunner"
```

### Option 2: Via Batch File (Windows)

```bash
run_hagrid_sim.bat
```

### Option 3: Via IDE (IntelliJ / VS Code)

Run `HAGRID2MATSimPipelineRunner.main()` directly. Make sure the working directory is set to the repository root.

### What Happens During Execution

```
╔══════════════════════════════════════════════════════════════╗
║  HAGRID — MATSim Freight Input Generator                     ║
╠══════════════════════════════════════════════════════════════╣
║  Scenario 1/1: BASECASE_13052025                             ║
║  ├─ Concept: basecase                                        ║
║  ├─ Date: 2025-05-13 (Tuesday)                               ║
║  ├─ Vehicles: [m, l]                                         ║
║  ├─ Region: Hannover                                         ║
║  └─ Schedule: SIMPLE_STAGGERED [07:00, 14:00]                ║
║                                                              ║
║  [1/6] Loading parcel demand...                              ║
║  [2/6] Allocating parcels to carrier depots...               ║
║  [3/6] Creating delivery carriers...                         ║
║  [4/6] Creating supply carriers...                           ║
║  [5/6] Merging carrier services...                           ║
║  [6/6] Routing carriers (jsprit, 1 iteration)...             ║
║                                                              ║
║  ✓ Scenario complete: BASECASE_13052025                      ║
║  Output: hagrid-output/BASECASE_13052025/                    ║
╚══════════════════════════════════════════════════════════════╝
```

---

## 5. Standalone Simulation Runner (CLI)

After the pipeline has generated carrier files, you can run the MATSim simulation independently:

```bash
java hagrid.HAGRIDSimulationRunner "concept=basecase,date=2025-05-13,jspritIter=10000"
```

### CLI Parameters

| Key | Required | Default | Description |
|-----|:--------:|:-------:|-------------|
| `concept` | **Yes** | — | Scenario concept name (e.g., `basecase`) |
| `date` | **Yes** | — | Simulation date in `yyyy-MM-dd` format |
| `tag` | No | *(empty)* | Version tag (e.g., `V1`) — appended to run ID |
| `maxIter` | No | `150` | Number of MATSim simulation iterations |
| `jspritIter` | No | `100` | Number of jsprit VRP iterations |
| `zoneCaching` | No | `false` | Enable zone-based routing cache |
| `zoneThreshold` | No | `1500` | Zone caching threshold in metres (when enabled) |
| `uTurnPenalty` | No | `1.0` | Score penalty cost per U-turn |
| `writeDashboard` | No | `false` | Auto-generate analysis dashboard after simulation |

### Examples

```bash
# Basic run
java hagrid.HAGRIDSimulationRunner "concept=basecase,date=2025-05-13,jspritIter=10000"

# With version tag
java hagrid.HAGRIDSimulationRunner "concept=basecase,date=2025-05-13,tag=V1,jspritIter=10000"

# With dashboard auto-generation
java hagrid.HAGRIDSimulationRunner "concept=basecase,date=2025-05-13,jspritIter=10000,writeDashboard=true"

# Multiple scenarios in one run
java hagrid.HAGRIDSimulationRunner \
  "concept=basecase,date=2025-05-13,jspritIter=10000" \
  "concept=batchHigh,date=2025-05-13,jspritIter=10000"
```

---

## 6. Analysis Dashboard Generator (CLI)

Generate a standalone HTML dashboard from a completed simulation:

```bash
java hagrid.HAGRIDAnalysisRunner "concept=basecase,date=2025-05-13,jspritIter=10000"
```

### CLI Parameters

| Key | Required | Default | Description |
|-----|:--------:|:-------:|-------------|
| `concept` | **Yes** | — | Scenario concept name |
| `date` | **Yes** | — | Simulation date in `yyyy-MM-dd` format |
| `tag` | No | *(empty)* | Version tag to match the correct run |
| `maxIter` | No | `150` | MATSim iterations (must match simulation) |
| `jspritIter` | No | `100` | jsprit iterations (must match simulation) |

### Output

The dashboard is written to:

```
hagrid-matsim-output/{RUN_ID}_iter{N}_jsprit{M}/analysis/{RUN_ID}_dashboard.html
```

Open this file in any modern web browser. No server required — everything is self-contained.

### Dashboard Features

| Section | Description |
|---------|-------------|
| **KPI Strip** | Fleet size, total parcels, average utilisation, total costs, total distance |
| **Tour Map** | Interactive Leaflet.js map with all delivery routes. Colour by provider or individual carrier. Click tours for stop details. |
| **Utilisation Analysis** | Vehicle load factor distribution, capacity utilisation by provider and vehicle type |
| **Tour Timing** | Departure time, arrival time, and tour duration histograms |
| **Distance Analysis** | Tour length distribution (histogram + box-style) |
| **Provider Breakdown** | Parcels, vehicles, and costs per provider (bar + pie charts) |
| **Cost Analysis** | Total costs by provider. Expandable per-vehicle detail table with fixed costs, distance costs, km, €/km, delivery rate |
| **Traffic Heatmap** | Network-level visualisation of delivery, supply, and combined traffic volumes |

---

## 7. Directory Structure & Output Files

```
parcel-demand-2-matsim-pipeline/
│
├── hagrid-input/                              ← All pipeline inputs
│   ├── config/
│   │   └── config.xml                         MATSim base configuration
│   ├── demand/
│   │   ├── basecase_13052025/                 Demand shapefiles (concept+date)
│   │   ├── batchHigh_14052025/
│   │   └── ...
│   ├── geodata/
│   │   └── Region Hannover.*                  Study area shapefile
│   ├── hubs/
│   │   ├── KEP-hubs_v3.csv                   Distribution center locations
│   │   ├── standorte_von_dhl.de.csv           DHL Packstations
│   │   └── standorte_von_paket.net/           Shipping points per provider
│   ├── network/
│   │   └── car_network_filtered_V2.xml.gz     MATSim road network (EPSG:25832)
│   └── vehicles/
│       └── HAGRID_vehicleTypes2.0.xml         Vehicle type definitions
│
├── hagrid-output/                             ← Pipeline results
│   ├── shared/                                Shared simulation inputs (all runs)
│   │   ├── sim-config.xml
│   │   ├── cargobike_network.xml.gz
│   │   ├── network_change_events.xml.gz
│   │   └── zones/
│   │
│   └── BASECASE_13052025/                     ← Run-specific results
│       ├── carriers/
│       │   ├── BASECASE_13052025_delivery_carriers_unrouted.xml
│       │   ├── BASECASE_13052025_delivery_carriers_merged.xml
│       │   ├── BASECASE_13052025_delivery_carriers_routed.xml
│       │   ├── BASECASE_13052025_supply_carriers_unrouted.xml
│       │   └── BASECASE_13052025_supply_carriers_routed.xml
│       ├── vehicles/
│       │   └── BASECASE_13052025_vehicle_types.xml
│       ├── network/
│       │   └── BASECASE_13052025_network_filtered.xml.gz
│       ├── routing/
│       │   ├── BASECASE_13052025_routing_metrics.csv
│       │   └── BASECASE_13052025_routing_status.csv
│       ├── summary/
│       │   └── BASECASE_13052025_scenario_summary.txt
│       ├── cache/                             Routing cache files
│       └── logs/                              Run-specific logs
│
└── hagrid-matsim-output/                      ← MATSim simulation results
    └── BASECASE_13052025_iter150_jsprit10000/
        ├── ITERS/                             MATSim iteration outputs
        ├── BASECASE_13052025.output_events.xml.gz
        ├── BASECASE_13052025.output_carriers.xml.gz
        ├── BASECASE_13052025.output_network.xml.gz
        └── analysis/
            └── BASECASE_13052025_dashboard.html    ← Interactive dashboard
```

### Run ID Format

```
{CONCEPT}_{ddMMyyyy}           Without tag:  BASECASE_13052025
{CONCEPT}_{ddMMyyyy}_{TAG}     With tag:     BASECASE_13052025_V1
```

---

## 8. Vehicle Type System (In-Depth)

### Standard Aliases

The three built-in aliases map directly to vehicle types defined in `HAGRID_vehicleTypes2.0.xml`:

| Alias | MATSim Type ID | Capacity | Fixed Cost | Cost/km | Cost/sec | Max Speed |
|-------|---------------|--------:|----------:|--------:|--------:|----------:|
| `"m"` | `ct_cep_size_m` | 165 | from XML | from XML | from XML | from XML |
| `"l"` | `ct_cep_size_l` | 230 | from XML | from XML | from XML | from XML |
| `"bike"` | `ct_cep_bike` | 23 | from XML | from XML | from XML | from XML |

### Custom Capacity Format: `"capacity_type"`

The format `"<number>_<type>"` creates a new vehicle type with:
- **Capacity** = the specified number
- **All other attributes** (costs, speed) = cloned from the base type

```
"60_m"    →  ct_cep_60_m     capacity=60,   costs from ct_cep_size_m
"100_l"   →  ct_cep_100_l    capacity=100,  costs from ct_cep_size_l
"50_bike"  →  ct_cep_50_bike  capacity=50,   costs from ct_cep_bike
"80_l"    →  ct_cep_80_l     capacity=80,   costs from ct_cep_size_l
```

### Numeric-Only Format: `"capacity"`

If only a number is specified, the base type is **auto-selected**:
- Capacity ≤ 165 → uses **medium van** (`m`) as template
- Capacity > 165 → uses **large van** (`l`) as template

```
"80"   →  ct_cep_80_m    (80 ≤ 165 → medium template)
"200"  →  ct_cep_200_l   (200 > 165 → large template)
```

### Why Custom Capacities?

Custom capacities allow you to study fleet composition effects:
- **What if vehicles could only carry 60 parcels?** → More vehicles needed, more tours, different cost structure
- **What if we use 100-parcel large vans?** → Somewhere between M and L capacity
- **What if cargo bikes had more capacity (50)?** → Test future e-cargo-bike designs

All while keeping realistic cost structures from the base vehicle types.

---

## 9. Dispatch Schedule System (In-Depth)

### Conceptual Model

The dispatch system models **when vehicle fleets leave their depot** to start delivery tours. It uses a three-layer approach:

```
Layer 1: Schedule Preset     → Defines candidate departure times
Layer 2: Dispatch Window     → Filters candidates to allowed range
Layer 3: Time Shift          → Adjusts final hours per provider
```

### Schedule Presets

#### `SIMPLE_STAGGERED` (Default)

```
Candidates: [7, 14]
```

Models a classic **two-wave** delivery pattern:
- **Morning wave (07:00):** Vehicles load overnight-sorted parcels and depart for first deliveries
- **Afternoon wave (14:00):** Vehicles reload with parcels sorted during the day

This is the most common pattern for traditional CEP providers (DHL, DPD, GLS, etc.).

#### `EXTENDED`

```
Candidates: [7, 11, 14]
```

Models a **three-wave** pattern with an additional midday departure:
- **07:00:** Morning departure
- **11:00:** Midday reload and re-dispatch
- **14:00:** Afternoon departure

Useful for providers with higher shipment volumes that require more frequent dispatching.

#### `FULL_WINDOW`

```
Candidates: [every hour from windowStart to windowEnd]
Example with window 7-14: [7, 8, 9, 10, 11, 12, 13, 14]
```

Models **continuous dispatching** throughout the operational window. Each hour, a new batch of vehicles can depart. This represents:
- Highly flexible operations (e.g., Amazon-style continuous dispatching)
- Maximum utilisation of the dispatch window
- Best suited for high-volume providers

#### `EARLY_ONLY`

```
Candidates: [windowStart - 1]
Example with window 8-14: [7]
```

Models a **single early-bird departure** before the dispatch window opens. Useful for:
- Supply/linehaul vehicles that need to pre-position inventory
- Providers wanting deliveries to start right when the delivery window opens

### Interaction Diagram

```
                    Schedule: SIMPLE_STAGGERED
                    Candidates: [7, 14]
                           │
                           ▼
               ┌─── Dispatch Window: 7-14 ───┐
               │   Filter: 7 ≥ 7 ✓  14 ≤ 14 ✓│
               │   Result: [7, 14]            │
               └──────────────────────────────┘
                           │
                           ▼
               ┌─── Time Shift: +1 (Amazon) ──┐
               │   Result: [8, 15]             │
               └───────────────────────────────┘
                           │
                           ▼
               Final dispatch hours for Amazon: [8, 15]
```

### Per-Provider Configuration Summary

```java
// All providers: two-wave dispatch between 7-14
.vehicleSchedule(VehicleSchedule.SIMPLE_STAGGERED)
.dispatchWindow(7, 14)

// Amazon: shifted +1 hour → [8, 15]
.providerTimeShift("Amazon", +1)

// DHL: custom hours (bypasses schedule entirely)
.providerDispatchHours("dhl", 6, 10, 14, 18)

// Hermes: three-wave schedule
.providerSchedule("hermes", VehicleSchedule.EXTENDED)

// GLS: later dispatch window
.dispatchWindow("gls", 9, 17)
```

---

## 10. Scenario Concepts

| Concept | Enum | Description | Providers |
|---------|------|-------------|-----------|
| `basecase` | `BASECASE` | Status quo — current delivery patterns with all major providers operating independently | DHL, GLS, Hermes, DPD, UPS, Amazon, FedEx |
| `white_label` | `WHITE_LABEL` | Single consolidated provider ("wl") replaces all providers — models full delivery consolidation | wl (94% delivery rate) |
| `ucc` | `UCC` | Urban Consolidation Center — shared last-mile delivery hub | All providers |
| `collection_points` | `COLLECTION_POINTS` | Emphasises parcel lockers and pickup points over home delivery | All providers |
| `batchModerate` | `BATCHMODERATE` | Moderate batching — slight delay to group parcels more efficiently | All providers |
| `batchMedium` | `BATCHMEDIUM` | Medium batching — parcels held for 1-2 days to optimise routes | All providers |
| `batchHigh` | `BATCHHIGH` | High batching — significant parcel holding for maximum route efficiency | All providers |
| `batchFull` | `BATCHFULL` | Maximum batching — all parcels batched to theoretical optimum | All providers |

### Default Provider Delivery Rates (Basecase)

| Provider | Delivery Rate (%) |
|----------|------------------:|
| DHL | 94% |
| Amazon | 93% |
| GLS | 91% |
| Hermes | 91% |
| DPD | 89% |
| UPS | 89% |
| FedEx | 89% |

The delivery rate represents the probability that a parcel is successfully delivered on the first attempt. Undelivered parcels require re-delivery or alternative handling.

---

## 11. Complete Configuration Reference

### Full Builder API

```java
ScenarioConfig config = ScenarioConfig.builder()

    // ── Scenario Selection ──────────────────────────
    .concepts("basecase")                          // Required. One or more concept names.
    .dates(LocalDate.of(2025, 5, 13))              // Required. One or more simulation dates.
    .tag("V1")                                     // Optional. Version tag for run ID.
    .filterRegions("Hannover")                     // Region filter. Default: "Hannover"

    // ── Vehicle Configuration ───────────────────────
    .vehicleSizes("m", "l")                        // Default vehicle types. Default: ["m", "l"]
    .providerVehicleSizes("amazon", "l")           // Override for specific provider.

    // ── Dispatch Scheduling ─────────────────────────
    .vehicleSchedule(VehicleSchedule.SIMPLE_STAGGERED)  // Default schedule. Default: SIMPLE_STAGGERED
    .dispatchWindow(7, 14)                         // When vehicles can depart. Default: 7-14
    .dispatchWindow("amazon", 9, 17)               // Provider-specific dispatch window.
    .providerSchedule("hermes", VehicleSchedule.EXTENDED)  // Provider-specific schedule.
    .providerDispatchHours("dhl", 6, 10, 14, 18)         // Custom hours (overrides schedule).
    .providerTimeShift("Amazon", +1)               // Shift dispatch hours. Default: 0

    // ── Delivery Time Window (scoring) ──────────────
    .deliveryTimeWindow(8, 20)                     // Penalty window for late/early delivery.
                                                   // Default: 8-20

    // ── Pipeline Execution ──────────────────────────
    .applyServiceSimplifier(true)                  // Merge overlapping services. Default: false
    .runRouting(true)                              // Run jsprit routing. Default: false
    .jspritIterations(1)                           // VRP optimisation iterations. Default: 1
    .enableCaching(false)                          // Cache routing results. Default: true

    .build();
```

### Runner File Template

```java
public final class HAGRID2MATSimPipelineRunner {

    // ═══════════════════════════════════════════════════
    // GLOBAL SETTINGS
    // ═══════════════════════════════════════════════════

    private static final String REGION = "Hannover";
    private static final VehicleSchedule SCHEDULE = VehicleSchedule.SIMPLE_STAGGERED;
    private static final int DISPATCH_START = 7;
    private static final int DISPATCH_END = 14;
    private static final int DELIVERY_TW_START = 8;
    private static final int DELIVERY_TW_END = 20;
    private static final boolean RUN_ROUTING = true;
    private static final boolean ENABLE_CACHING = false;
    private static final int JSPRIT_ITERATIONS = 1;

    // ═══════════════════════════════════════════════════
    // SCENARIOS
    // ═══════════════════════════════════════════════════

    private static final ScenarioConfig[] SCENARIOS = {
        // Standard basecase
        scenario("basecase", LocalDate.of(2025, 5, 13), "m", "l"),

        // Same concept+date but with custom vehicles (separate output)
        // scenario("basecase", "V1", LocalDate.of(2025, 5, 13), "100_l"),
    };

    public static void main(String[] args) {
        HAGRID.run(SCENARIOS);
    }

    // Helper methods create ScenarioConfig with the global settings above
}
```

---

## 12. Prerequisites

| Requirement | Minimum | Recommended |
|------------|---------|-------------|
| Java | 17+ | 21 (Eclipse Adoptium) |
| Maven | 3.8+ | 3.9+ |
| RAM | 16 GB | 32 GB |
| Disk Space | 5 GB | 20 GB (with MATSim output) |

### Required Input Data

- [ ] `hagrid-input/config/config.xml` — MATSim base configuration
- [ ] `hagrid-input/vehicles/HAGRID_vehicleTypes2.0.xml` — Vehicle type definitions
- [ ] `hagrid-input/demand/{concept}_{ddMMyyyy}/` — Parcel demand shapefiles
- [ ] `hagrid-input/network/car_network_filtered_V2.xml.gz` — Road network (EPSG:25832)
- [ ] `hagrid-input/hubs/KEP-hubs_v3.csv` — Distribution center locations
- [ ] `hagrid-input/hubs/standorte_von_paket.net/*.csv` — Shipping points per provider
- [ ] `hagrid-input/geodata/Region Hannover.*` — Study area shapefile

### Demand Shapefile Convention

Folder: `hagrid-input/demand/{concept}_{ddMMyyyy}/`

Files inside:
```
hagrid_parcel_demand_{yyyy-MM-dd}_({Weekday}).shp
hagrid_parcel_demand_{yyyy-MM-dd}_({Weekday}).dbf
hagrid_parcel_demand_{yyyy-MM-dd}_({Weekday}).prj
hagrid_parcel_demand_{yyyy-MM-dd}_({Weekday}).shx
hagrid_parcel_demand_{yyyy-MM-dd}_({Weekday}).cpg
```

Example for `basecase_13052025/`:
```
hagrid_parcel_demand_2025-05-13_(Tuesday).shp
```

---

## 13. Troubleshooting

### Common Issues

| Problem | Cause | Solution |
|---------|-------|----------|
| "Demand shapefile not found" | Missing or misnamed demand folder | Check folder name matches `{concept}_{ddMMyyyy}` exactly |
| "No dispatch hours computed" | Dispatch window doesn't overlap with schedule | Widen the window or change schedule preset |
| Out of memory | Large network + many carriers | Increase `-Xmx` (e.g., `-Xmx16g` or `-Xmx24g`) |
| Empty carrier files | No parcels in region | Verify `filterRegions` matches demand data extent |
| Dashboard won't load | Browser security blocking local file | Use a modern browser (Chrome, Firefox, Edge) |

### JVM Memory Settings

For large simulations, set JVM arguments:

```bash
java -Xmx16g -Xms4g hagrid.HAGRIDSimulationRunner "concept=basecase,date=2025-05-13"
```

Or edit `vmargs.txt`:
```
-Xmx16g
-Xms4g
--add-opens java.base/java.lang=ALL-UNNAMED
```

---

*Last updated: February 2026*
