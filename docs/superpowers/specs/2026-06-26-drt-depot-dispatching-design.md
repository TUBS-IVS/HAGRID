# DRT Depot-Dispatching — Design (Lausitz / Hoyerswerda)

- **Date:** 2026-06-26
- **Branch:** `hendrik`
- **Status:** Approved (design); → writing-plans
- **Scope owner:** Hendrik Bimmermann (TU Braunschweig)

## 1. Motivation

The current DRT fleet is placed **round-robin over the sorted links of the DRT
sub-network** (`DrtFleetGenerator`), with no depot concept. This is an
unfavourable simplification for **scenario comparison** and **vehicle-km**:
vehicles begin the day scattered across arbitrary in-area links rather than at
real operating bases, so morning deployment distances and daily tour lengths are
not meaningful, and the baseline cannot be compared cleanly against the
shared-use / modular DRT scenarios.

This change introduces **real depot dispatching**: vehicles start the day at the
7 shared depots (the same bases used by the LMD freight scenarios), rebalance
toward observed demand during the day (idle vehicles may stay put), and return
toward their depot at service end. KPI detail (driver-hour pricing etc.) is
deferred — this design fixes the *spatial/operational* structure.

## 2. Goals / Non-Goals

**Goals**
- Vehicles spawn at the 7 shared depots, evenly split.
- Out-of-area depots snap to the nearest in-area DRT link (negligible here; see §4).
- Rebalancing ON, targeted at **demand zones** (not depots); idle vehicles in
  balanced zones stay put.
- Vehicles return toward their depot at service end (faithfully where cheap,
  KPI-accounted for the residual).
- All new parameters defaulted so the PoC runs with no new CLI arguments.

**Non-Goals (YAGNI)**
- Forced per-vehicle end-of-service relocation task (documented future upgrade, §7).
- Per-depot capacity / charging / shift scheduling.
- Driver-hour / time cost as an actual scoring term (KPI accounting handled later).
- Extending the DRT sub-network with out-of-area depot access corridors.

## 3. Locked decisions

1. **Reuse the 7 shared LMD depots** as DRT bases (`hagrid-input/lausitz/hubs/lmd-depots.csv`).
   For DRT only the 7 coordinates matter; LSP identity is irrelevant.
2. **Even split** of the fleet across depots: vehicle `i → depot[i % nDepots]`.
3. **Rebalancing ON**, target = demand-based (previous-iteration zonal DRT demand);
   "stay put" emerges naturally (balanced zones keep their vehicles).
4. **Return-to-depot at service end** via the rebalancing engine (time-switched
   target), with a **KPI post-processing fallback** for vehicles still at a last
   stop at sim end. Forced relocation task deferred.
5. **Zone system:** MATSim built-in `SquareGridZoneSystem` (~2 km cell) — no
   shapefile authoring; demand is learned from previous iterations. Swappable for
   a GIS/cluster shapefile later.

## 4. Depot set (final)

EPSG:25832 (UTM 32N). UPS was relocated from Lohsa (4.22 km outside the service
area — would have distorted spawn/vehicle-km) to **PEWO Energietechnik,
Geierswalder Str. 13, 02979 Elsterheide-Bergen** (OSM `building/industrial`,
Gewerbegebiet Neuwiese-Bergen), which lies **inside** the service area.

| LSP | x | y | in service area? | dist to polygon |
|-----|----------|------------|------------------|-----------------|
| dhl    | 866341.8 | 5705764.6 | yes | 0.00 km |
| amazon | 855395.1 | 5712299.2 | no  | 0.09 km (trivial snap) |
| hermes | 867545.1 | 5710992.6 | yes | 0.00 km |
| dpd    | 865819.9 | 5713911.5 | yes | 0.00 km |
| gls    | 870590.7 | 5719256.4 | no  | 0.23 km (trivial snap) |
| ups    | 861516.0 | 5715307.7 | yes | 0.00 km (relocated) |
| fedex  | 861667.8 | 5709617.9 | yes | 0.00 km |

The `lmd-depots.csv` (active input) and `lmd-depots-addresses.csv` (companion)
already reflect this. The LMD freight scenario keeps using the same 7 depots; the
far-east UPS/Lohsa location is no longer needed for any scenario.

## 5. Architecture / components

Each unit is isolated, with a single purpose and a clear interface.

**(a) Depot source.** A small reader returns the 7 depot `Coord`s from
`lmd-depots.csv` (`provider;x;y`, EPSG:25832). Wired via a config key with default
= that CSV. (Parsing is trivial; no dependency on the freight `LmdDepotLoader`,
which snaps to *car* links and validates provider names.)

**(b) Depot snapping → DRT sub-network** (`DrtFleetGenerator`). New signature
takes the depot `Coord`s + the existing DRT sub-network. Each coord → nearest DRT
link via `NetworkUtils.getNearestLinkExactly`. Because the sub-network contains
only in-area links (both endpoints inside the polygon, per `DrtNetworkPreparer`),
amazon/gls snap negligibly (≤0.23 km) and all 7 yield valid in-area start links.

**(c) Even fleet split** (`DrtFleetGenerator`). Vehicle `i → depotLink[i % 7]`;
depots differ by at most one vehicle. `serviceBegin/End` unchanged (0–86400 s
default, configurable).

**(d) Rebalancing toward demand** (`DrtConfigComposer`). Add `RebalancingParams`
(MinCostFlow) + a `SquareGridZoneSystem` (cell ~2000 m) to the `DrtConfigGroup`,
and install the rebalancing binding. Target calculator = demand-based
(previous-iteration zonal DRT demand). Effect: idle vehicles in oversupplied
zones move toward demand; balanced zones keep their vehicles → "Rebalancing an,
Stehenbleiben erlaubt".

**(e) Return-to-depot** (new `RebalancingTargetCalculator` + Python KPI step).
Time-switched target: during the day → demand-based; in the final window
(default last 5400 s before `serviceEnd`) → depot-zone targets, so idle vehicles
drive home over the network and the return-km are counted. KPI fallback: vehicles
still at a last stop at sim end get their final-position→depot network distance
added to vehicle-km in the dashboard post-processing.

## 6. Data flow

```
lmd-depots.csv ──▶ LausitzDrtPreprocessor
                       │  (reads 7 coords)
                       ├─▶ DrtNetworkPreparer ─▶ DRT sub-network (existing)
                       └─▶ DrtFleetGenerator(coords, subnet)
                               │  snap each coord → nearest DRT link
                               │  even split  i → depot[i % 7]
                               └─▶ fleet XML
DrtConfigComposer ─▶ DrtConfigGroup + RebalancingParams + SquareGrid zones
Controler ─▶ DvrpModule + MultiModeDrtModule + rebalancing binding
                               (time-switched target calculator)
post-run ─▶ dashboard: add final-position→depot return-km fallback
```

## 7. New parameters (all defaulted — PoC runs without new CLI args)

| Param | Default | Purpose |
|-------|---------|---------|
| drt depot file | `hagrid-input/lausitz/hubs/lmd-depots.csv` | depot coords source |
| zone cell size | 2000 m | SquareGrid rebalancing zones |
| rebalancing interval | 1800 s | how often rebalancing runs |
| return-window length | 5400 s before `serviceEnd` | when target switches to depots |

## 8. Testing

- **Unit (`DrtFleetGenerator`):** exactly N vehicles produced; every start link is
  in the DRT sub-network; even split across the 7 depots (max diff 1); a depot
  coord outside the sub-network snaps to a valid in-area link.
- **Unit (`DrtConfigComposer`):** `RebalancingParams` + zone system present with
  expected values (cell size, interval); rebalancing binding installed.
- **Smoke / e2e:** a short DRT run completes; rebalancing relocations occur;
  vehicles start at depot links; return-window relocations appear near service end.

## 9. Files likely touched

- `hagrid/integrated/drt/DrtFleetGenerator.java` — depot coords + snap + even split.
- `hagrid/integrated/drt/LausitzDrtPreprocessor.java` — read depot CSV, pass coords.
- `hagrid/integrated/drt/DrtConfigComposer.java` — rebalancing + zone system + binding.
- `hagrid/integrated/drt/` — new `RebalancingTargetCalculator` (time-switched).
- `hagrid/...SimulationConfig` — new defaulted config keys.
- DRT dashboard / analysis (Python) — return-km KPI fallback.

## 10. Future upgrades (out of scope now)

- **Forced end-of-service relocation task** for guaranteed per-vehicle return
  (intercept the DVRP schedule when a vehicle goes idle after a cutoff, append a
  drive-to-depot + final stay). ~150–250 LOC + non-trivial schedule-state
  debugging; marginal fidelity gain over §5(e) for a PoC, hence deferred.
- Demand/cluster-shapefile zone system instead of square grid.
- Per-depot capacities, driver shifts, and driver-hour cost as a scoring term.
