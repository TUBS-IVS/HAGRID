# Design Spec: District-Based Depot Assignment for the Integrated Scenarios

**Date:** 2026-08-17
**Status:** Draft — awaiting user review
**Scope:** Shared-Use (1c) and Modular (1d) only. The LMD/DRT **Baseline is explicitly out of
scope** and keeps one depot per provider.
**Parent:** [`2026-06-17-lausitz-drt-freight-integration-design.md`](2026-06-17-lausitz-drt-freight-integration-design.md)
(§3.3 Operator Model, §"Depots / hubs")

---

## 1. Motivation

Today every parcel is picked up at **its own provider's depot** in all three arms — Baseline
([`LausitzFreightPreprocessor.java:146`](../../../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java#L146)),
1d ([`:210`](../../../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java#L210)),
1c ([`ParcelAgentGenerator.java:80-90`](../../../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/ParcelAgentGenerator.java#L80-L90),
marked `M4(b)`). `DepotNetwork.nearestDepot()` exists but is dead in production — it only serves as
a fallback for providers without a tagged depot, and all seven have one.

This contradicts two decisions of the parent spec: §"Depots / hubs" prescribes *"Shared-Use: parcel
pickup origin (parcels assigned to **nearest depot**)"*, and §3.3 prescribes that in the integrated
arms *"provider identity is operationally irrelevant"* with carriers as pure demand-representation
units. `M4(b)` chose provider binding for comparability with the Baseline van arm — a defensible
choice whose cost is now measured.

### 1.1 Measured cost of the current binding

All figures below are measured, not estimated. Sources: run `DRT_BASELINE_13052025_basew21_iter150_jsprit100`
(`output_carriers.xml.gz`, tour legs re-summed from link lengths; reconstruction total 3,750.3 km vs
KPI `freight_vehicle_km` 3,724.74 km = +0.7 %), run `DRT_MODULAR_13052025_f150t015_iter150_jsprit100`
(`modular_tour_stats.csv`), run `DRT_SHAREDUSE_13052025_chid600w21_iter150_jsprit100`
(`output_drt_legs_drt.csv`), and the clipped PANDA demand (6,052 parcels / 892 segments in the
service area).

**Mean depot distance per parcel** (straight line, parcel-weighted): own provider depot
**6.63 km**, freely chosen nearest depot **1.92 km**. Only 13.1 % of parcels already have their own
depot as the nearest one. Worst affected: gls 10.49 km, amazon 9.74 km.

**Baseline vs 1d tour structure:**

| | Baseline `basew21` | 1d `f150t015` |
|---|---|---|
| Tours | 52 | 89 |
| Depot / deadhead km | 530.8 (14.2 %) | **1,187.9 (26.7 %)** |
| Stop-to-stop km | 3,219.5 | 3,259.2 |
| Freight km total | 3,750.3 | **4,447.2 (+18.6 %)** |

1d's entire penalty against the Baseline is depot deadhead: of +696.9 km, **+657.1 km is deadhead**
and only +39.7 km is delivery driving. This is the mechanism behind "1d performs worse than the
Baseline".

**1c:** 2,886 parcel legs at a mean **9.03 km** direct network distance, 34,259 driven person-km —
**30 % of the whole fleet's transport output**. Unlike the tour-based arms, 1c amortises the depot
leg over nothing: each parcel bundle pays its own trip from a distant provider yard. 1c does not
show this as extra vehicle-km (the fleet is capped at 120 vehicles and drove 806 km *less* than the
Baseline) but as **displacement**: DRT modal share 4.78 % vs 5.95 %, plus 338 undelivered parcels.

**Stop duplication:** the 892 demand segments are served by **3.37 providers on average**; 40
segments are visited by all seven. Pooling collapses 3,123 services into 892 physical stops.

---

## 2. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **Dissolve provider identity operationally** in 1c/1d: one stop per segment, provider kept as attribute | Parent spec §3.3; removes 2,231 duplicate stops |
| D2 | **Baseline untouched** — one depot per provider, no re-run | User decision 2026-08-17. Consequence in §9 |
| D3 | **Depot count 1 / 3 / 7 as a sensitivity sweep**, not a fixed setting | Depot density is a genuine planning variable; marginal benefit is strongly decreasing (§4.3) |
| D4 | **15-min stop-duration cap stays unchanged** | Consistency with all prior runs and Hannover parity. Known distortion, declared in §9 |
| D5 | **Districts derived from depot catchments**, not free clustering | Guarantees the depot is its district's nearest loading point (§4) |
| D6 | Partition size governed by `maxJobsPerDistrict` (default **300**), not by a fixed district count | 300 = Hannover's measured p95 jobs/carrier, an empirically working size |
| D7 | Depots and districts are named by **site**, not by LSP (`hoy_sued`, `wittichenau`, …) | In a single-operator scenario the yard on Str. D 2 is a location, not Hermes' yard. Structurally prevents the analysis layer from reading a district id as a provider name (§6.5) |
| D8 | **Level 2 applies to 1d only**; 1c always uses whole catchments | 1c has no jsprit — each request enters DVRP insertion individually and sub-districts share their catchment's depot, so a split changes nothing except the person ids (§6.2) |
| D9 | **Both arms clip to the service area before districting** | Makes the segment→depot mapping provably identical across 1c and 1d (§8) |
| D10 | Sweep stage 1 runs with the **χ-gate open** (`chiThreshold=999999`) | With depot legs cut to ~2.6 km, the added vehicle time per parcel falls below the 600 s threshold, so χ=600 would be near-inert and the stage would measure a half-binding acceptance rule on top of the depot effect. The χ raster follows on the winning stage, in a lower band (§7)  **CORRECTION 2026-08-22:** this row originally named `chiThreshold=-1`. That token does the OPPOSITE: `ChiGateInsertionCostCalculator:169` treats chi < 0 as HARD-CLOSED (no parcel ever boards), pinned by the test "chi=-1 (hard-closed) rejects every parcel, even at zero time loss". The intent stated in this very cell (an inert gate) is implemented by a large POSITIVE threshold. Caught before the 1c arms were launched. |

---

## 3. Why partitioning is needed at all — and what actually binds

jsprit scales with the number of **jobs** (`CarrierService`), not parcels; parcel counts only enter
`capacityDemand` and dwell time. Job counts measured from `output_carriers.xml.gz`:

| Configuration | Carriers | Jobs total | Largest carrier | Bottleneck (jobs², carriers run in parallel) |
|---|---|---|---|---|
| Hannover `200v2` | 231 | 32,911 | 393 | 154k |
| Lausitz today | 7 | 3,123 | 875 | **766k** |
| Lausitz pooled, 1 carrier | 1 | 892 | 892 | **796k** |

Hannover carries ten times the total load yet its bottleneck carrier is five times cheaper, because
carriers are optimised in parallel and the largest one sets wall-clock. This is why 6k-parcel
Lausitz feels as slow as 231 Hannover carriers.

Two consequences: pooling alone buys **no** runtime (892 ≈ 875 jobs), and the partition target is a
**job ceiling**, not a parcel budget. Hannover's distribution (median 144, p95 271, max 393) gives
the empirical calibration for D6.

The runtime gain lands in **preprocessing**, not in total run time — the Baseline run spent 4.6 h on
150 MATSim iterations, against which jsprit is small. The benefit is faster iteration on the freight
model, not shorter overnight runs.

---

## 4. District construction

Two levels, in this order. The order is what guarantees the property the free-clustering approach
could not provide: **the depot is by construction the nearest loading point for every segment in its
catchment**, including for depots outside the service-area polygon.

### 4.1 Level 1 — depot catchment (Voronoi over the open depots)

Each demand segment is assigned to the nearest **open** depot (Euclidean, EPSG:25832). This defines
*where the vehicle loads*, and it is the only level 1c uses (D8). Catchments are deliberately **not**
balanced — see §4.4.

The assignment is computed once in preprocessing and is **unique and deterministic**: a parcel has
exactly one pickup site, fixed before the simulation starts. A DVRP request has a single origin, so
no vehicle can collect that parcel elsewhere even if stock would physically be there. Ties resolve
to the first depot in CSV order (the comparison is strictly `<`). Consequence for interpretation in
§9.

### 4.2 Level 2 — sub-districts only where needed (1d only)

A catchment with more than `maxJobsPerDistrict` jobs is split into `ceil(n / maxJobsPerDistrict)`
sub-districts. Sub-districts share their catchment's depot; they are an optimisation partition, not
a second yard. Because `maxJobsPerDistrict` is a ceiling and not an equal-share constraint, the 21:1
imbalance of the raw catchments is harmless for runtime.

1c passes `Integer.MAX_VALUE` (D8), so its districts are exactly the catchments. Level 2 never
touches the depot of a stop — this is what makes the two arms' assignments identical (§8).

### 4.3 Naming (D7)

Districts and depots carry **site names**. The depot CSV gains a `site` column; the `provider`
column stays untouched so the Baseline path keeps working unchanged.

| provider (CSV, unchanged) | site | address |
|---|---|---|
| dhl | `wittichenau` | Gewerbepark 1, Wittichenau-Brischko |
| amazon | `lauta` | Straße B 1, Lauta |
| hermes | `hoy_sued` | Str. D 2, Hoyerswerda |
| dpd | `hoy_nord` | Am Speicher 7, Hoyerswerda |
| gls | `spreetal` | Werkstraße 5, Spreetal |
| ups | `elsterheide` | Geierswalder Str. 13, Elsterheide-Bergen |
| fedex | `doergenhausen` | Dresdener Str. 99, Hoyerswerda-Dörgenhausen |

### 4.4 Resulting partition per sweep stage (computed on the clipped demand)

| Open depots | Mean depot distance / parcel | Catchments → districts (1d) | Largest optimisation | Bottleneck |
|---|---|---|---|---|
| 1 — `hoy_sued` | 3.82 km | 1 → **3** | 297 | 88k |
| 3 — `wittichenau, hoy_sued, doergenhausen` | 2.46 km | 3 → **5** (two split) | 200 | 40k |
| 7 — all | 1.92 km | 7 → **7** (no split needed) | 252 | 64k |
| *today, for reference* | *6.63 km* | *7 provider carriers* | *875* | *766k* |

Depot subsets are the distance-optimal subset for each size. All three stages stay below Hannover's
p95 and are 9–19× faster than today's bottleneck. The 1-depot stage is the "single yard with
sub-carriers" variant: three districts, one shared yard. In 1c every stage yields exactly one
district per open depot (D8).

**Load is strongly unbalanced, by design.** Nearest-depot assignment minimises distance, not
utilisation. At seven depots the catchments range from `spreetal` 89 parcels (1.5 %) to `hoy_nord`
1,886 (31 %) — a factor of 21; at three depots `hoy_sued` carries 3,299 (55 %). This is accepted
(user decision 2026-08-17: the absolute volumes are small). Two consequences are reported rather
than fixed: the per-district load KPI (§6.5) and the §9 note that the seven-depot stage is
effectively a five-depot stage.

**Depots outside the polygon:** measured against the current `drt-service-area.shp`, `lauta` and
`spreetal` lie outside, `elsterheide` inside (see §10 — project notes record three outside depots).
Outside depots keep their catchment and remain its nearest loading point; nothing special is needed.

---

## 5. Architecture

### 5.1 New component

`hagrid.integrated.DeliveryDistrictBuilder` — the single place where pooling, districting and depot
binding happen.

```java
/** One physical stop: all parcels of all providers at one demand segment. */
public record PooledStop(Coord coord, int totalParcels, List<Delivery> parts) { }

/** One district: the jsprit optimisation unit, anchored at one depot. */
public record District(String id, Coord depotCoord, List<PooledStop> stops) { }

public static List<District> build(Collection<Delivery> clippedDeliveries,
                                   List<DepotNetwork.Depot> openDepots,
                                   int maxJobsPerDistrict);
```

`PooledStop.parts` retains the original `Delivery` objects, so provider and B2B/B2C information
survives for the delivery-rate overlay and for KPI attribution. **`Delivery` itself is not
modified** — it is shared with the Hannover pipeline.

`DepotNetwork` gains no new behaviour; its existing `nearestDepot()` becomes a real production
caller for the first time (level 1).

### 5.2 One stop per segment, not per segment × type

B2B/B2C are pooled into the same stop. Splitting them would produce 999 jobs instead of 892 and let
the 15-min cap apply twice at the 107 segments that carry both — partially undoing the pooling at
exactly the densest points. Type stays available via `PooledStop.parts`.

### 5.3 Configuration

Two new runner config keys, parsed in the same pattern as `fleetSize` / `chiThreshold`:

| Key | Default | Meaning |
|---|---|---|
| `openDepots` | `all` | `all`, or a comma-separated **site** list naming the open yards (e.g. `wittichenau,hoy_sued,doergenhausen`, or `hoy_sued` for the single-yard stage) |
| `maxJobsPerDistrict` | `300` | Level-2 split threshold. **1d only** — 1c ignores it (D8) |

Both are ignored by the Baseline scenario. `run_metadata.json` must record them so the KPI layer can
label sweep stages. An unknown site name is a hard error: a typo would silently shrink the depot
network and move every KPI.

---

## 6. Changes per arm

### 6.1 1d Modular

`LausitzFreightPreprocessor.runModular` replaces its per-provider loop with `DeliveryDistrictBuilder`:
one carrier per **district**, `depotLink` = the district's depot snapped to the network,
`LmdCarrierBuilder.buildSingleWindow` receives a district id instead of a provider name and one
`CarrierService` per `PooledStop` (`capacityDemand` = pooled parcel count, duration from the
unchanged `min(2 min × n, 15 min)` formula).

### 6.2 1c Shared-Use

`ParcelAgentGenerator.generate` replaces the `byProvider` iteration with the district list: one
parcel-person per `PooledStop` (still split by `SharedUse.PARCEL_SLOTS = 20`), origin activity at the
district depot. The `M4(b)` provider-binding block and its warn-once fallback are removed. The
`parcel_` id prefix relied on by the pax-only KPI filter is unchanged; the rest of the id scheme for
multi-provider stops is an open question (§10).

The builder is called with `Integer.MAX_VALUE` (D8) and on the **already clipped** delivery set
(D9). Effect on the request count: 3,128 → **957** (−69 %), because the 20-slot split only adds 65
requests — 844 segments fit in one request, 48 need two or more, the largest carries 81 parcels.
Depot stops fall in the same proportion (3,128 → 957); total depot loading time is unchanged at
50.4 h, since `min(30 s × parcels, 600 s)` is linear below its cap and 20 slots × 30 s hits it
exactly. The gain is the number of stops, not the loading time.

### 6.3 Delivery-rate overlay (1d; 1c builds no carriers)

`LmdCarrierBuilder.buildCore` currently draws one daily bias per **carrier** and looks up
`DELIVERY_RATES.getOrDefault(provider, 90.0)`. With a district as the carrier the lookup falls
through to the 90 % default instead of the per-provider rates (dhl 94 … dpd/ups/fedex 89). The run
`f150t015` confirms the overlay is live in 1d (`parcels_missed_overlay = 387`).

**Where this bites is not the delivery rate.** Since 2026-08-10 `delivery_rate` is *gross* in all
three arms — "delivered / demand, overlay NOT subtracted" (METHODS-LOG), which is why `f150t015`
reports `delivery_rate = 1.0`. The overlay feeds `parcels_handled`, which stays deliberately net and
is the denominator of `economics.freight_cost_per_parcel`. Falling through to the 90 % default would
drop `parcels_handled` from 5,665 to ~5,447 and raise the €/parcel figure by roughly 4 % — a silent
shift with no change in the concept. `delivery_rate_net_overlay` and `parcels_per_vehicle_km` move
with it.

Fix: draw the per-provider bias once per provider per carrier, then draw each parcel against its own
provider's rate via `PooledStop.parts`. The new `buildDistrict` method carries this; `buildCore` and
its RNG draw-order contract stay untouched, protected by the guard in §8.

### 6.4 New reporting (not new behaviour)

- **`peak_concurrent_swaps` per depot** (1d). Today only a global figure exists (`modular_tour_stats.csv`,
  6 in `f150t015`). There is no capacity limit at the swap point and none is introduced — but with a
  single open yard every swap concentrates there, so the number that quantifies the idealisation has
  to be visible per site. See §9.
- **Load per district**: parcels and segments per district, so the 89-parcel yards appear in the
  results rather than in a footnote.

### 6.5 Provider attribution in the analysis layer

Site names (D7) already prevent the failure mode where `freight_classify.py` reads a district id as
a provider name. What they cannot do is preserve the per-provider split, since a district mixes all
seven. The district carrier therefore persists a `parcelsByProvider` attribute (`dhl=120;gls=45`,
first-appearance order) from which `extract_freight_provider.py` re-derives per-provider parcel rows.

### 6.6 Explicitly unchanged

`LausitzFreightPreprocessor.run`, `LmdCarrierBuilder.build`, dispatch waves, jitter, `LmdTourRetimer`,
vehicle types, the 7 h `MaxRouteDuration`, the delivery day 07:30–21:00, and the stop-duration
formula including its 15-min cap.

---

## 7. Sweep matrix

**Stage 1 — depot density, χ-gate open** (`chiThreshold=999999`, D10):

| Stage | 1c | 1d | Purpose |
|---|---|---|---|
| `openDepots=hoy_sued` | ✓ | ✓ | single-yard consolidation |
| `openDepots=wittichenau,hoy_sued,doergenhausen` | ✓ | ✓ | parent spec's "2–3 depots" |
| `openDepots=all` | ✓ | ✓ | existing infrastructure retained |
| **Control:** `openDepots=hoy_sued`, `maxJobsPerDistrict=99999` | — | ✓ | 1 carrier / 892 jobs, unpartitioned |
| **Control replicate:** same, different `seed` | — | ✓ | separates a small partition effect from run noise |

**Eight runs.** One run per depot stage suffices — the expected effects (deadhead −12…−19 %, freight
km roughly halved, in 1c a block of 30 % of the fleet's transport output) are far above replanning
noise, and jsprit runs on a fixed seed. The control is the exception: if partitioning turns out to
cost only a few percent, a single point cannot separate that from noise, hence its replicate.

The control **must use the single-depot configuration** — with all seven depots open, level 1
already yields seven catchments and raising the split threshold changes nothing. It is compared
against the `openDepots=hoy_sued` stage, holding the depot configuration fixed so only the partition
varies. Expect roughly today's freight preprocessing cost (892 jobs ≈ DHL's current 875).

**Stage 2 — χ raster on the winning depot stage** (1c). The band shifts downward: with depot legs at
~2.6 km instead of 9.0 km the added vehicle time per parcel drops from roughly 1,080 s to ~310 s, so
the interesting region is around 100–300 s rather than 600 s. Instrument is the per-segment
`SegmentDetour` minimum (METHODS-LOG 2.31) — the saturating counters cannot answer where χ binds.
Scope and values are set once stage 1 is read.

The Baseline is not re-run (D2).

---

## 8. Testing

- **Baseline regression (blocking):** `LausitzFreightPreprocessor.run` output must stay
  **byte-identical** to the current build for the same seed — asserted on the generated carriers XML,
  because the RNG draw order in `buildCore` is a documented contract. Determinism is given: jsprit
  runs on the fixed seed 4711 and `HAGRIDRouterUtils` guarantees bit-identical production runs.
- **1c/1d consistency (blocking):** `build(deliveries, open, 300)` and
  `build(deliveries, open, Integer.MAX_VALUE)` must map **every segment to the same depot**. Level 2
  partitions, it never reassigns — this is what makes the two arms comparable, and it is asserted
  rather than assumed. Both arms additionally clip before districting (D9), so their input sets are
  identical.
- `DeliveryDistrictBuilder`: pooling sums amounts and preserves every original `Delivery`;
  every stop's assigned depot is the nearest open depot to that stop; no district exceeds
  `maxJobsPerDistrict`; a catchment below the threshold is not split; single-depot config yields one
  catchment; determinism across repeated builds; an unknown site name in `openDepots` throws.
- Overlay: with a district carrier spanning several providers, per-parcel rates match the provider
  table, and a single-provider district reproduces the current per-provider outcome.
- 1c: one parcel-person per pooled stop (plus slot splits), origin link equals the district depot,
  no `parcel_` id-prefix regression.
- e2e: one short 1c and one short 1d run per depot stage boot and produce `run_metadata.json`
  carrying `openDepots` / `maxJobsPerDistrict`.

---

## 9. Limitations to record in METHODS-LOG

1. **Stop-duration cap (D4).** Pooling makes the 15-min cap bind far more often: 29 % of segments
   (68 % of parcels) exceed the 7.5-parcel threshold, the largest segment carries 81 parcels. Total
   stop time falls from 188.7 h to 129.4 h purely through the cap — without a cap it would be 201.7 h
   in *both* worlds, i.e. pooling-invariant. The integrated arms therefore receive **59 h** of stop
   time that stems from the formula, not from the concept. Accepted for consistency with prior runs.
2. **Confounded headline comparison (D2).** With the Baseline provider-separated and the integrated
   arms consolidated, the headline difference contains **consolidation and integration together**.
   The parent spec's planned "consolidated-operator Baseline variant" (districts, dedicated vans, no
   integration) remains the way to decompose them; it is not part of this work.
3. **All prior 1c/1d plausibility and calibration runs are superseded** by the depot change
   (user-accepted 2026-08-17). This includes every χ statement for 1c; the θ range [0.1–0.3] for 1d
   is unaffected by the depot question.
4. **Inbound sorting is not modelled.** Nothing transports a parcel to its district depot. Assigning
   a parcel to the nearest yard silently assumes the line-haul already delivers it there, i.e.
   district-level pre-sorting upstream. Plausible for a hub-and-spoke single operator, but it is a
   new assumption, it is not free in reality, and the Baseline does not receive it.
   `lmd-depots-regional-reference.csv` holds the line-haul variant for a later study.
5. **One pickup site per parcel, no stock pooling.** The assignment is fixed in preprocessing and a
   DVRP request has a single origin, so no vehicle can collect a parcel from another yard even when
   stock would be there. The measured improvement is therefore a **lower bound**; true stock pooling
   would do better. The restriction costs most at seven depots and nothing at one.
6. **Under nearest-depot assignment the seven-depot stage is effectively a five-depot stage**:
   `spreetal` (89 parcels) and `elsterheide` (137) together carry 3.8 % of the volume — yards nobody
   would operate. Balanced assignment would fix that but costs +81 % depot distance (1.92 → 3.49 km),
   landing near the single-yard value of 3.82 km; measured, not simulated (§10).
7. **No swap capacity at the yard.** Concurrent capsule swaps are unlimited by construction. At one
   open depot every swap concentrates on one site — roughly a dozen simultaneous swaps in a dispatch
   wave, i.e. a dozen swap bays assumed. Reported per depot (§6.4), not constrained.

---

## 10. Open questions / to verify

- **Balanced assignment as a follow-up.** Deliberately dropped here (user 2026-08-17: "weglassen,
  aber spannend"): equal-volume districts are a capacitated transport problem, not an `argmin`, and
  the headline number is already available without simulating it (+81 % depot distance at seven
  depots, §9.6). If it is picked up later, the cheap route is to solve the segment→depot mapping once
  in Python and feed it as a CSV — a file reader in Java instead of a heuristic, matching the pattern
  by which depots already arrive.
- **Depot-inside-polygon count.** Measured against the current `drt-service-area.shp`: `lauta` and
  `spreetal` outside, `elsterheide` inside (5 in / 2 out). Project notes record "4 inside / 3
  outside" with `elsterheide` (ups) as an outside depot. Harmless for the construction, but the
  figure may appear in the thesis text.
- **Person-id scheme in 1c** for multi-provider pooled stops — needs a convention that keeps the
  `parcel_` prefix contract and stays readable in dashboards.
- ~~**KPI attribution per provider** after pooling~~ — **resolved in the implementation plan
  (Task 9):** the district carrier persists a `parcelsByProvider` attribute, `kpis_provider.csv`
  labels district rows `district:<id>` (mirroring the existing `type:<VT>` convention) and emits
  per-provider parcel rows parsed from that attribute. Without it a district would silently be
  reported as the LSP whose name its id happens to carry.
- Whether the control run (§7) justifies keeping `maxJobsPerDistrict` at 300 or a different ceiling.
- **Deviation from §4.2 (Task 3): level-2 splitting uses a deterministic median-strip split, not
  `SameSizeKMeans`.** `SameSizeKMeans` runs through an ELKI database pipeline initialised with
  `RandomUniformGenerated` (see `DemandProcessor.sortCarrierDemandSameSizeKMeans`), which is
  neither cheap to call for a plain point list nor deterministic without threading a seed through
  ELKI. Level 2 only needs to cut a catchment into `k` compact, equally-sized pieces, so
  `DeliveryDistrictBuilder.splitEvenly` sorts along the longer bounding-box axis and cuts into
  contiguous near-equal blocks instead — same guarantee (equal-size, compact), reproducible by
  construction. Noted in the Task 3 commit message (`9c1bd71`) but not previously recorded here.
