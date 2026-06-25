# Lausitz Dedicated LMD (Baseline) — Design Spec

**Date:** 2026-06-25 · **Status:** Design (to be turned into an implementation plan) · **Branch:** `hendrik`

**Context:** This is the **dedicated last-mile-delivery (LMD)** half of the research *Baseline* scenario (multi-LSP DRT + separate dedicated LMD) for the Lausitz/Hoyerswerda study. It consumes the **PANDA** parcel demand (`hagrid_parcel_demand_*.shp`, segment-aggregated, per-provider, clipped to the DRT service area) and runs it through HAGRID's existing **jsprit / `CarrierModule`** freight pipeline, **Lausitz-scoped**. It builds on the completed DRT work (1a/1b/1b-prep + rail-PT) but is an **independent MATSim freight run** (see decision 1). Predecessor design context: `docs/superpowers/specs/2026-06-17-lausitz-drt-freight-integration-design.md`.

## Decisions (user, 2026-06-25)

1. **Separate runs for this PoC.** The dedicated LMD runs as its own self-contained MATSim freight run; the passenger DRT (`DRT_BASELINE`) stays a separate run. KPIs are summed afterward (1e). A **combined run** (parcels + passengers in one Controler, shared network/congestion — needed natively by the integrated Shared-Use/Modular concepts) is **planned as a later phase** ("Verheiratung"), not this slice. Rationale: the freight pipeline is *already* a self-contained run; in rural Hoyerswerda the van↔DRT congestion interaction is negligible, so additive separation is methodologically close and far cheaper.

2. **7 synthetic depots, one per LSP**, sited peripherally with good road/Autobahn connectivity (industrial-estate style), modelled as **local delivery bases** — the regional line-haul stays upstream/outside the model. Provider-bound assignment (each LSP delivers from *its own* depot, not the nearest foreign one). Exact coordinates are finalized at data-staging; the (likely real) regional depots are recorded below as the documented fallback.

3. **Approach 1 — thin Lausitz freight entry, core reused, Hannover path gated.** Reuse the jsprit/Carrier core (service generation, vehicle factory, jsprit invocation, `CarrierModule`, dashboard); drive it from a new Lausitz-scoped preprocessor that skips the Hannover-specific steps. Gated so the Hannover/freight path stays byte-for-byte untouched.

4. **Scenario identity = new `LMD_BASELINE`** (non-DRT, Lausitz-bound). It pairs by name with `DRT_BASELINE`. `StudyArea = LAUSITZ_HOYERSWERDA` is derived + validated from the scenario (exactly as the `DRT_*` family already forces `DRT⇒LAUSITZ`). The `StudyArea` dimension (1a) is kept for path-scoping + Hannover legacy; the existing `DRT_*` enum values and their pipeline are structurally unchanged. As a deliberate, user-approved unification, `parseScenario` now derives + validates `StudyArea=LAUSITZ_HOYERSWERDA` for **all** Lausitz-bound scenarios (the `DRT_*` family AND `LMD_BASELINE`) when `studyArea` is omitted; passing `studyArea=HANNOVER` for any of them still throws. Chosen over folding geography into a cross-product enum (`BASECASE_LAU_LMD`, …), which would rename the proven `DRT_*`/Hannover scenarios, break existing commands/runIds, and reverse 1a's orthogonality.

5. **B2C door-vs-Packstation is a non-question for the sim.** The PANDA demand is segment-aggregated (no real addresses, no Packstation concept); every segment representative point is simply a delivery stop carrying all its parcels (B2C + B2B). The Hannover `DeliveryGenerator`'s parcel-locker service generation is therefore **off** in the Lausitz path. Bonus: Baseline and integrated scenarios consume the identical segment demand, so there is no Packstation/integration effect conflation. *(If Shared-Use later wants a "Packstation-first" lever, that needs separately staged locker locations as a HAGRID input — a 1c decision, not here.)*

6. **Defaults locked:** cargobikes **off** (vans only, 165/230 parcels — cargobikes don't fit the dispersed rural area); service-time + jsprit parameters **reuse Hannover defaults** (`durationPerParcel` 2 min, cap `maxDurationPerStop` 15 min — rural mainly changes inter-stop *drive* time, which comes from the network, not the per-stop *service* time); **no region filter** (trust PANDA's clip instead of re-filtering through `Region Hannover.shp`); **supply/inbound carriers off**; **white-label off** (multi-LSP status quo).

## Existing pipeline facts (mapped this session)

The conventional LMD machinery already exists and is **geography-agnostic at its core**; only data + hardcoded paths/filters cling to Hannover.

- Flow: `DemandProcessor` → `DeliveryGenerator` → `CarrierGenerator` → **jsprit (offline VRP)** → carrier XML → MATSim `CarrierModule` → `FreightEventHandler` → `DashboardGenerator` (HTML). Freight routing is **offline preprocessing**; MATSim executes the fixed jsprit tours.
- Demand shapefile schema (read by `DemandProcessor`): per-provider `<provider>_tag` (B2C count) + `<provider>_type`/`_typ` (B2B count) for {dhl, amazon, dpd, fedex, gls, hermes, ups} (+ wl), plus `postal_cod`, ids, etc. **PANDA already writes exactly this schema.**
- Carrier structure (Hannover): one carrier per (provider × postal-code) group, KMeans-split when > 600 deliveries, each carrier bound to the **closest hub** from a shared pool.
- Service-duration model: `min(durationPerParcel × count, maxDurationPerStop)`. Vehicle types: van `_m` 165 / `_l` 230 / cargobike 23 parcels; per-km / per-hour / fixed cost from the vehicle-types XML.
- Hannover-wired: `KEP-hubs_v3.csv`, `Region Hannover.shp`, `car_network_filtered_V2`, zone `RH_useful__zone.shp`, supply-chain link IDs (N/S/E/W), vehicle-types XML.
- `StudyArea` enum (1a): `HANNOVER` (legacy `hagrid-input/`) vs `LAUSITZ_HOYERSWERDA` (`hagrid-input/lausitz/`); `HagridPaths` already study-area-scoped (used by the DRT side). The freight processors are **not yet** study-area-aware.

## Scope

**In:**
1. New `Scenario.LMD_BASELINE` (non-DRT, derives/validates `StudyArea=LAUSITZ`).
2. A Lausitz-scoped freight preprocessor (`hagrid.integrated.freight`) that builds one carrier per LSP from the PANDA demand, anchored at that LSP's depot, via the reused jsprit/Carrier core.
3. Provider-bound depot model from a Lausitz 7-depot hub CSV; vans-only fleet.
4. Wiring: `HagridPaths` getters (Lausitz hub CSV + carrier XML output), `HAGRIDScenarioBuilder`/runner branch on `LMD_BASELINE`, validation.
5. Reuse the existing `FreightEventHandler`/dashboard output for the PoC sanity check.

**Out (this spec):**
- The **combined** DRT+LMD run (Verheiratung) — later phase.
- **1c** Shared-Use, **1d** Modular, **1e** canonical KPI CSV + cross-scenario dashboard.
- Packstation/locker delivery lever (needs separately staged locker data).
- Supply/line-haul modelling (depots are local bases; line-haul is upstream).
- Consolidated-operator Baseline variant (Phase 2).

## Design

### A. Scenario + gating
- Add `Scenario.LMD_BASELINE`. **`isDrt()` is derived from the enum value** (`HagridConfig.java:75-77`), never set manually; `LMD_BASELINE` stays **out** of that set → `isDrt() == false` automatically (correct — it is freight, not DRT).
- **Lausitz binding:** the current Lausitz-forcing rides on `isDrt()` (`setStudyArea`, `HagridConfig.java:834`), which therefore does **not** fire for the non-DRT `LMD_BASELINE`. Add a small dedicated predicate (e.g. `requiresLausitz() = isDrt() || this == LMD_BASELINE`) and use it in `setStudyArea`/`parseScenario` so `LMD_BASELINE ⇒ StudyArea.LAUSITZ_HOYERSWERDA` is derived + validated. Hannover concepts + `DRT_*` untouched.
- In `HAGRIDScenarioBuilder`/runner, the freight branch (`!isDrtScenario()`) sub-branches: `LMD_BASELINE` → new Lausitz freight preprocessor; all other (Hannover) concepts → existing processors. Hannover path stays byte-for-byte identical.

### B. Lausitz freight preprocessor (`hagrid.integrated.freight`)
- **`LausitzFreightPreprocessor`** (analogous to `LausitzDrtPreprocessor`): orchestrates demand read → per-LSP grouping → carrier build at the LSP depot → jsprit → carrier XML.
- **`LausitzDepotLoader`**: reads the provider-tagged 7-depot hub CSV (existing hub CSV format, company field per row); snaps each depot to the nearest car link.
- **`LausitzCarrierBuilder`**: builds **one carrier per LSP** and invokes the reused `CarrierGenerator` service-generation + `CarrierVehicleFactory` (vans only) + jsprit. Replaces closest-hub assignment with **provider-bound** depot assignment; no PLZ-KMeans / locker / supply / white-label.
- A **spike first** (see Testing) confirms how isolatable the `CarrierGenerator` per-carrier methods are (today they consume an upstream-populated `carrierDemand` map); fallback is to lift only the needed helpers.

### C. Data flow
```
PANDA hagrid_parcel_demand_*.shp   (schema matches; clipped to service area)
        │  no region filter (trust the PANDA clip)
        ▼
one carrier per LSP {dhl, amazon, hermes, dpd, gls, ups, fedex}
        │  depot = that LSP's synthetic peripheral depot (provider-bound)
        ▼
one service per segment stop (all parcels delivered; no Packstation)
        ▼
jsprit VRP (vans 165/230), offline  →  carrier XML
        ▼
MATSim CarrierModule  →  FreightEventHandler  →  existing dashboard (PoC)
```

### D. Depot model
- 7 synthetic depots, one per LSP, peripheral / good Autobahn connectivity, modelled as local delivery bases. Provider-bound assignment. Hub CSV in the existing format (company-tagged rows).
- Exact coordinates finalized at data-staging (candidate siting near the A13 / service-area edge, optionally in the direction of each LSP's real regional hub). The (likely real) regional depots — recorded for the record / as an alternative input — are:

  | LSP | Real depot (reference / fallback) |
  |---|---|
  | Amazon | Am Mart 9, 01561 Lampertswalde |
  | DHL | Bergener Ring 2, 01458 Ottendorf-Okrilla |
  | DPD | OT Groß Beuchow, Robinienweg, 03222 Lübbenau/Spreewald |
  | FedEx | Zellsteig 6, 01683 Nossen |
  | GLS | An d. Autobahn 9, 03048 Cottbus |
  | Hermes | Nikolaus-Otto-Straße 3, 02625 Bautzen |
  | UPS | Neuteichnitzer Str. 66, 02625 Bautzen-Neuteichnitz |

  These real locations sit 40–80 km out (regional Paketzentren); using them as actual tour origins would let approach-leg line-hauls dominate veh-km. The synthetic local bases avoid that while staying swappable.

### E. Network + paths
- Reuse the already-staged Lausitz car network (the DRT side stages the matsim-lausitz network). Vans route on the car network; depots and demand snap to car-accessible links (PANDA already restricted demand to car links).
- `HagridPaths` (study-area-scoped): Lausitz hub CSV getter + run-scoped carrier-XML output getter. PANDA demand copied into `hagrid-input/lausitz/demand/{...}/`.

## Affected components (anticipated; firmed up in the plan)
- `HagridConfig` — new `Scenario.LMD_BASELINE` + `isDrt()` stays false + study-area derivation/validation in `parseScenario`.
- `hagrid.integrated.freight.{LausitzFreightPreprocessor, LausitzDepotLoader, LausitzCarrierBuilder}` (new).
- `HagridPaths` — Lausitz hub CSV + carrier-XML output getters.
- `HAGRIDScenarioBuilder` / `SimulationRunnerUtils` — `LMD_BASELINE` branch (reuse `CarrierModule` + dashboard); Hannover gated.
- `CarrierGenerator` / `CarrierVehicleFactory` — reused; possibly minimal extraction of per-carrier helpers if the spike shows tight coupling.
- `DATA-LAUSITZ.md` — staging rows for the Lausitz hub CSV (+ confirm demand staging).
- Tests: per-LSP grouping, provider-bound depot assignment, vans-only fleet, locker-off; real-data smoke test.

## Risks / open items
- **`CarrierGenerator` reusability:** per-carrier methods today consume an upstream `carrierDemand` map → spike-verify isolated invocation; fallback = lift needed helpers (toward Approach 3 only where necessary).
- **jsprit runtime** with one carrier per LSP (a few hundred–low-thousand segment stops each): jsprit iteration cap + the existing zone-based routing cache; KMeans-within-LSP purely as a tractability fallback (not semantics) — `log()` if applied.
- **Demand schema edge cases:** `_type` vs `_typ` B2B suffix + 10-char DBF truncation → the spike reads the PANDA shp through `DemandProcessor` and asserts parcel counts.
- **Depots on-network:** peripheral coordinates must snap to a car link and stay reachable.
- **KPI association (1e):** the separate `LMD_BASELINE` run + the `DRT_BASELINE` run map to the same research "Baseline" via `study_area + scenario + operation_mode` in the canonical CSV.

## Next step
Turn this into a TDD implementation plan (writing-plans), **spike first**: (1) read the PANDA demand through the existing reader and confirm counts; (2) confirm a single LSP's carrier builds + jsprit produces tours from a synthetic depot end-to-end through the production path; then productionize the preprocessor, the `LMD_BASELINE` scenario + gating, and the real-data smoke test.
