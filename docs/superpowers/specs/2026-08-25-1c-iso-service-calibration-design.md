# Design Spec: Iso-Service Calibration of the 1c Shared-Use Arm

**Date:** 2026-08-25
**Status:** Approved in conversation 2026-08-25; fleet fan launched the same day
**Scope:** 1c Shared-Use only, on the seven-depot district configuration. The Baseline stays
untouched (spec 2026-08-17, D2), and 1d is touched only by the consequence noted in §7.
**Parent:** [`2026-08-17-integrated-district-depot-assignment-design.md`](2026-08-17-integrated-district-depot-assignment-design.md)

---

## 1. Why this campaign exists

The district-depot sweep is done (three 1c arms on the dev-PC, 2026-08-22/23, χ-gate open). It
settled the depot question: **seven depots dominate on every axis**, which is the configuration this
campaign builds on.

| dep1 | dep3 | dep7 | |
|---|---|---|---|
| 10,826 s | 6,486 s | **3,965 s** | parcel wait, mean |
| 40,795 s | 34,496 s | **17,047 s** | parcel wait, p95 |
| 420 | 0 | **0** | parcels arriving after 21:00 |
| 7,847 | 7,814 | **7,973** | pax rides |
| 48.4 M | 46.5 M | **45.8 M** | DRT vehicle-km |
| 16 % | 15 % | **14 %** | empty-km share |

What it did **not** settle is whether 1c performs the task at all. The task is **6,052 parcels and
~9,000 pax rides**. `d1c_dep7` delivers the parcels but serves only **7,973** pax rides against the
Baseline's **9,076** (`b120rg`: fleet 120, seed 1337, 150 iterations, post-REGRET) — a shortfall of
**1,103 rides = 12.2 %** at the same fleet size.

The shortfall is **not** a service-quality effect. Pax wait mean is 701 s against the Baseline's
705 s, pax wait max 1,284 s against 1,414 s, 40 rejections. Quality for the passengers who *are*
served is unchanged; the fleet is bound in **volume** (`minShareIdleVehicles = 0` at dep7), and the
parcels push pax demand out through mode choice.

A headline comparison at equal fleet size therefore pits an arm that serves 12 % fewer passengers
against the Baseline. 1d already resolved this with a fleet fan (f125 → 8,691, f130 → **9,137** ≈
Baseline, f140 → 9,453). 1c has no such fan. This campaign supplies it.

---

## 2. Decisions

| # | Decision | Rationale |
|---|---|---|
| C1 | **Iso-service, not iso-fleet:** the 1c fleet grows until pax service is restored | User decision 2026-08-25. Makes the arm comparable with the Baseline *and* with 1d, which is already calibrated this way. The iso-fleet point is not lost — `d1c_dep7` **is** the f120 point |
| C2 | **Target: 9,076 pax rides** (`b120rg`), pax-filtered | The only Baseline run post-REGRET (2026-08-15). `basew21` (2026-07-31) predates the REGRET pin and is superseded on the freight side |
| C3 | **Adaptive grid, one arm at a time**, starting at f130 | f120 is known to be short, so a three-point fan would waste a run. If f130 already meets the target, f125 is next; if not, f140, then f150. Each arm is ~10 h on the dev-PC — buy only the points that discriminate |
| C4 | **Parcel side is a monitor, not a target** | The DRT-borne parcel count (5,946) cannot be improved by fleet size beyond noise, and the walk channel of §4 is geometric. If either moves in the fan, something else changed |
| C5 | **χ raster follows the fleet fan**, band 100–300 s, on the calibrated fleet | A χ threshold drawn on an under-sized fleet is calibrated against the wrong displacement. Instrument is the per-segment `SegmentDetour` minimum (METHODS-LOG §2.31) — the saturating counters cannot locate where χ binds |
| C6 | **No re-run for the 15 depot-link parcels** (§3) | They never become agents, so they consume nothing; the correction is bookkeeping and the simulation is byte-identical with or without it. User decision 2026-08-25 |
| C7 | **`ActiveProcessorCount` pinned to 12 on the sim-PC** | MATSim reports `numberOfThreads = 12` in all three groups (global, QSim, DRT insertion) for the dev runs, and the config's own comment derives the DRT value from the cores available to the JVM. The sim-PC has 16. Pinning removes a thread-count difference between the f120 point and its successors |

---

## 3. The 15 parcels at their own yard gate

`parcels_injected` is 6,052 at dep1 but 6,037 at dep3 and dep7. This is **not** demand drift: all
three arms read the same file (`fdac2435…`, written 2026-07-28) and the 890 delivery links they
share carry identical parcel counts, link for link.

Two segments sit on the very link that becomes a depot link once their yard opens:

| delivery link | parcels | depot link of | dep1 | dep3 | dep7 |
|---|---|---|---|---|---|
| `-255975710#2` | 12 | doergenhausen | delivered from hoy_sued | dropped | dropped |
| `-31023811#2` | 3 | wittichenau | delivered from hoy_sued | dropped | dropped |

`DefaultPassengerRequestValidator` rejects a DVRP request with `from == to`, so
[`ParcelAgentGenerator.java:91-95`](../../../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/ParcelAgentGenerator.java#L91-L95)
drops the stop before it becomes an agent. Both collisions already exist at three depots, which is
why dep3 and dep7 are identical.

Substantively the drop is wrong, not merely imprecise: a parcel addressed at the yard gate is the
*cheapest* case in reality (carried across on foot), and the model books it as an outright loss. The
correction is a counter, not a code path — see §5.

---

## 4. The walk channel: 1c has no non-delivery, it has a second delivery mode

The 91 parcels the KPI layer calls `parcels_never_submitted` **arrive**. They walk:

```
departure  person="parcel_hoy_sued_246"  legMode="walk"  computationalRoutingMode="drt"
travelled  distance="6747.2"  mode="walk"
actstart   actType="parcelDelivery"
```

One parcel walks 6.7 km. `output_drt_rejections_drt.csv` contains **zero** parcel rows, so the
channel is invisible in every DRT KPI — the DRT router declines these relations at routing time,
before insertion, and MATSim falls back to a walk leg.

**The channel is geometric, not capacitive.** All 8 walk-fallback delivery links from dep1 also walk
at dep7 (`only dep1: 0`); dep7 adds exactly two, one of them between adjacent links of the same
street at the doergenhausen yard (`-255975710#2 → -255975710#3`). **More vehicles will not move
these 91 parcels.** Expect ~91 to hold across the whole fan; a change signals something else moved.

Accounting for dep7, under the standing convention that 1c has no missed-delivery overlay:

| | parcels | |
|---|---|---|
| PANDA demand in the service area | 6,052 | |
| – dropped at their own yard gate (§3) | 15 | the only parcels that truly do not arrive |
| = injected as agents | 6,037 | on 955 parcel agents |
| carried by a DRT vehicle | 5,946 | 945 agents, 0 late |
| delivered on foot | 91 | 10 agents |
| **arriving** | **6,037** | **= 99.75 % of 6,052** |

**This retracts a figure in the parent spec.** Its §1.1 reports *"plus 338 undelivered parcels"* for
the old run `chid600w21`. Re-counted: **349 parcels on 241 walking agents** — not undelivered,
walked. The district-depot change cut this channel from 349 parcels to 91, which is a result nobody
has reported as one.

---

## 5. KPI-layer defect this exposes

`kpis_long.csv` for dep7 states, simultaneously:

```
parcels_never_submitted   91
parcels_undelivered        0
delivery_rate         0.9849
```

`undelivered = 0` and a 98.49 % delivery rate cannot both be true. What the field named
`delivery_rate` actually holds is `parcels_delivered / parcels_injected` — the **DRT-borne share**.
The fix is a naming and completeness fix in `extract_shareduse`, not a model change:

- `parcels_in_demand` = 6,052 (currently absent; `parcels_injected` silently hides the 15)
- `parcels_dropped_at_depot_link` = 15 (§3)
- `parcels_drt_borne` = 5,946 (today's `parcels_delivered`)
- `parcels_walked` = 91 (today's `parcels_never_submitted`, but recorded as arriving)
- `delivery_rate` = (5,946 + 91) / 6,052 = 0.99752
- `drt_borne_share` = 5,946 / 6,037 = 0.98492 — today's mislabelled `delivery_rate`

The parts must sum to the whole, and a test must assert it: `in_demand = dropped + drt_borne +
walked`. Applies to the existing dep1/dep3/dep7 runs by re-extraction; no re-run.

---

## 6. Machine and provenance

| | |
|---|---|
| Runs on | sim-PC (IVS2000), HEAD `0c29990`, branch `hendrik`, working tree clean |
| Demand input | synced dev → sim 2026-08-25, all five shapefile parts byte-identical (`dbf` SHA256 `FDAC2435EBC56D41`) |
| Mislabelled directory | sim's `level_central` held the *old* `BC86ECC5` state (METHODS-LOG §2.30 trap); renamed to `level_ctrsnap_central`, and a correct `level_central` created |
| JVM | `vmargs_lausitz.txt` — mirrors the dev argfile (`-Xmx48g`, ZGC, `ActiveProcessorCount=12`) rather than the Hannover `vmargs.txt` (`-Xmx124g` + `AlwaysPreTouch` on 127.8 GB, on a machine with five unexplained crashes and an open RAM suspicion) |
| Hannover v4 | 38/38 complete, all with `output_carriers` + `output_trips`; its working-tree diff is archived at `analysis/hannover-sweep/provenance/` and verified to apply to `019fd5f` before the tree was reset |

**Pairing claim to verify, not assume:** the first sim arm must reproduce the dev preprocessing
numbers for its configuration (6,037 parcels on 955 agents, seven named depot links) and must show
`numberOfThreads = 12` in its `output_config.xml`. Until both hold, sim arms are not paired with
`d1c_dep7`.

---

## 7. Consequence for 1d

If 1c is reported at its iso-service fleet, 1d must be too. 1d's headline run `d1d_dep7` uses
**fleet 150**; its iso-service point is **f130** (9,137 pax rides against the Baseline's 9,076).
The paper's 1d point is therefore f130, not the f150 run. No new 1d run is needed — f130 exists.

---

## 8. Limitations to carry into METHODS-LOG

1. The walk channel (§4): a silent, unlogged second delivery mode; geometric, fleet-invariant; and
   the retraction of the parent spec's "338 undelivered".
2. The yard-gate drop (§3): 15 parcels, stage-dependent, substantively the cheapest delivery case
   booked as a loss.
3. Iso-service comparison (§2, C1): growing the fleet buys pax service with vehicles, so every cost
   and emission figure for 1c is reported at a larger fleet than the Baseline's 120. This is a
   different comparison from iso-fleet, and both readings exist in the data.
4. The confounding of consolidation and integration (parent §2.40) is untouched by this campaign and
   still applies to every 1c-vs-Baseline number.
