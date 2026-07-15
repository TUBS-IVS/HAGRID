# Run-Dashboard v2 — Plan D: Maps, Network Geometry & Event-Derived Series — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Leaflet maps on both tabs (DRT: tours by occupancy, depots, rail stops, PU/DO heatmaps, per-vehicle selection; LMD: tours by provider, stop clusters, link heatmap), the network-dependent distributions (`drt_tour_distance`, `occ_km`), and the event-derived freight hourly series (`freight_parcels_h_<provider>`, `freight_active_vehicles_<provider>`, `freight_depot_departures/arrivals`) — closing every item Plan A/C deferred. Final gate: **browser usability on the real married250 run**, then the measured size becomes the asserted budget (spec §3.5).

**Architecture:** One richer single-pass events cache (non-exclusive DRT/freight membership — 1c/1d shared vehicles land in BOTH caches). New `freight_events.py` parses the freight cache once into structured events (service starts, depot times, link sequences) consumed by timeseries, maps and the parcels/h join (ported order-based zip from `DashboardGenerator.buildParcelsPerHourByProviderJson`). New `geometry.py` owns network parsing (used-links only, streaming, pyproj EPSG:25832→WGS84) + DRT path reconstruction (ported from `build_drt_dashboard.py`) + polyline simplification. New `maps.py` builds a compact `map_data.json`; new `render_maps.py` renders it with vendored Leaflet into the Plan-C `map_block` hooks — zero changes to the tab builders' signatures.

**Tech Stack:** Python 3.13, pandas, pyproj, geopandas+shapely (lazy imports, graceful skip), Leaflet 1.9.4 + leaflet.heat 0.2.0 + markercluster 1.5.3 (vendored inline; map tiles = only online dependency), pytest 9.x.

## Global Constraints

- Never edit the legacy dashboards. Port by copying: network parse + occupancy paths from `analysis/drt-headline/build_drt_dashboard.py:91-186`, rail fed/unfed from `:260-289` (FEED_RADIUS_M=600), N_SAMPLE=3000 lever from `build_dashboard.py:183-188`, Leaflet layer/JS patterns from `DashboardGenerator.java:2882-3124`, parcels-join from `:943-991`, depot detection from `FreightEventHandler.java:82-117`.
- **Non-exclusive caches (spec §3.2, confirmed):** a line/vehicle may belong to BOTH the DRT and the freight cache; inclusion tests are independent; never de-duplicate across caches.
- Coordinates: pyproj `Transformer.from_crs("EPSG:25832", "EPSG:4326", always_xy=True)`, rounded to **5 decimals**. Link geometry = straight from-node→to-node line (documented ~3 % low vs MATSim — tooltip note).
- Map data: embedded inline in the page AND written to `<run>/analysis/map_data.json` for debugging. Page renders offline except CARTO tiles (light: `light_all`, dark: `dark_all`, theme-switched via `matchMedia` like `JS_SETUP`).
- Leaflet must not use image-dependent defaults: `circleMarker`/`divIcon` only (no `L.marker` default icon, no `L.control.layers` — custom checkboxes), `preferCanvas: true`.
- Acceptance gate = **usability, not bytes** (spec §3.5): measure on married250, record the measured size as the asserted budget; if sluggish, levers in order: PU/DO downsampling (N_SAMPLE=3000), lazy per-vehicle polylines, then harder cuts.
- ASCII-only `print()`; graceful skips for every optional input (shp, rail schedule, depots CSV, missing events); `--no-events` omits maps/event sections entirely.
- No `.gitignore` changes; explicit `git add` lists (vendor files ARE committed — `chart.umd.min.js` precedent); branch `hendrik`; no master merge.
- Input paths (resolved relative to the run dir, each optional): network `<run>/<prefix>.output_network.xml.gz`; service area `<run>/../../hagrid-input/lausitz/drt/drt-service-area.shp`; depots `<run>/../../hagrid-input/lausitz/hubs/lmd-depots.csv` (header `provider;x;y`, `;`-sep, EPSG:25832); rail schedule `<run>/../../hagrid-output/<prefix>/<prefix>_rail-transitSchedule.xml.gz`; DRT legs `<run>/<prefix>.output_drt_legs_drt.csv`.

## File Structure

- Modify: `analysis/kpi/events_cache.py` (+ `tests/test_events_cache.py`) — Task 1
- Modify: `analysis/kpi/timeseries.py` (+ `tests/test_timeseries.py`) — Task 1
- Create: `analysis/kpi/freight_events.py` (+ `tests/test_freight_events.py`) — Tasks 2–3
- Create: `analysis/kpi/geometry.py` (+ `tests/test_geometry.py`) — Task 4
- Modify: `analysis/kpi/distributions.py`, `analysis/kpi/build_kpis.py` — Task 5
- Create: `analysis/kpi/maps.py` (+ `tests/test_maps.py`) — Tasks 6–7
- Create: `analysis/kpi/render_maps.py` (+ `tests/test_render_maps.py`); modify `render.py`, `build_kpis.py`; add `vendor/leaflet*` + `vendor/MarkerCluster*` — Task 8
- Fixtures: `tests/fixtures/mini_events/` — hand-authored tiny cache files + tiny network gz (Task 1/4)

---

## Task 1: Events cache v2 — richer freight cache, single pass, non-exclusive

**Files:**
- Modify: `analysis/kpi/events_cache.py`
- Modify: `analysis/kpi/timeseries.py` (service-stop counting must now FILTER the richer cache)
- Modify tests: `tests/test_events_cache.py`, `tests/test_timeseries.py`
- Create fixture: `tests/fixtures/mini_events/MINI.output_events.xml.gz`

**Interfaces:**
- `FREIGHT_SUFFIX = ".freight_events_filtered.txt"` (NEW name — old `.freight_service_starts.txt` caches are simply ignored; on old runs the richer cache rebuilds from events in the same single pass).
- `ensure_caches(run_dir, prefix)` — signature unchanged. DRT predicate unchanged (`"drt_" in line`). Freight predicate (module function, unit-testable):

```python
def _freight_wanted(line):
    if "freight" not in line:
        return False
    if 'type="entered link"' in line:
        return True
    return 'type="actstart"' in line or 'type="actend"' in line
```
Both predicates evaluated independently per line (two `if`s, no `elif`) — a 1c shared-vehicle line matching both lands in both caches.

- [ ] **Step 1: Create the fixture** — gzip of a hand-authored mini events XML containing (times chosen for later tasks): a freight vehicle `freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0` with `actend actType="start"` (t=28800), 2× `entered link` (links `l1`,`l2`, t=29000/29100), 2× `actstart actType="service"` (t=29400, 32400) + matching `actend actType="service"`, `actstart actType="end"` (t=36000); a DRT vehicle `drt_veh_1` with `entered link` + `PersonEntersVehicle`/`PersonLeavesVehicle` (person `p1`); and ONE shared line naming `drt_freight_shared_1` with `type="entered link"` (must land in BOTH caches). Write it with a one-off script; keep attribute layout identical to real MATSim events (`<event time="..." type="..." .../>`).
- [ ] **Step 2: Write failing tests** — `ensure_caches` on the fixture: freight cache contains the actend/actstart AND entered-link freight lines (5+ lines) and the shared line; DRT cache contains the drt lines AND the shared line; old-name cache absent. `test_timeseries.py`: `freight_service_stops` from the NEW cache counts ONLY `actstart`+`actType="service"` lines (== 2 for the fixture vehicle, not 7).
- [ ] **Step 3: Run, verify fail.**
- [ ] **Step 4: Implement** — predicate as above; in `timeseries.extract`'s freight block add the line filter `('type="actstart"' in line and 'actType="service"' in line)` before counting.
- [ ] **Step 5: Run, verify pass** (existing cache/timeseries tests updated to the new suffix where they reference it).
- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/events_cache.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/timeseries.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_events_cache.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_timeseries.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/mini_events
git commit -m "feat(kpi): single-pass v2 events caches, non-exclusive membership (v2 Plan D Task 1)"
```

---

## Task 2: `freight_events.py` — structured parse of the freight cache

**Files:**
- Create: `analysis/kpi/freight_events.py`
- Test: `analysis/kpi/tests/test_freight_events.py` (uses the Task-1 fixture cache via `ensure_caches`)

**Interfaces:**
- `@dataclass FreightEvents: service_starts: dict[str, list[float]]` (person/event_vehicle_id → sorted times), `depot_departures: dict[str, list[float]]` (actend actType="start"), `depot_arrivals: dict[str, list[float]]` (actstart actType="end"), `veh_links: dict[str, list[tuple[str, float]]]` (vehicle → [(link_id, time)] from entered-link).
- `parse_freight_cache(cache_path) -> FreightEvents` — regexes as in the legacy scripts: `time="([0-9.]+)"`, `person="([^"]+)"`, `\bvehicle="([^"]+)"`, `link="([^"]+)"`, `actType="([^"]+)"`. Activity events key on `person` (freight driver person id == event_vehicle_id, format `freight_<carrier>_veh_<veh>_<tour>`), link events on `vehicle`; both filtered to ids containing `freight`.

- [ ] **Step 1: Write failing tests** — parse the fixture cache: `service_starts["freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0"] == [29400.0, 32400.0]`; `depot_departures[...] == [28800.0]`; `depot_arrivals[...] == [36000.0]`; `veh_links[...] == [("l1", 29000.0), ("l2", 29100.0)]`.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** (single pass over the cache file; sort `service_starts` lists at the end).
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/freight_events.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_freight_events.py
git commit -m "feat(kpi): structured freight-events parse (v2 Plan D Task 2)"
```

---

## Task 3: Event-derived freight hourly series

**Files:**
- Modify: `analysis/kpi/freight_events.py` (series builders live here — `timeseries.py` keeps its signature)
- Modify: `analysis/kpi/build_kpis.py` (concatenate rows before `timeseries.write`)
- Modify test: `tests/test_freight_events.py`

**Interfaces:**
- `hourly_series(fev, carriers, excluded) -> list[dict]` — rows shaped exactly like `timeseries._ts` output (`{series, hour, value, unit}`), covering:
  - `freight_parcels_h_<provider>` (parcels/h) — port of `DashboardGenerator.buildParcelsPerHourByProviderJson` (order-based zip; complete port below).
  - `freight_depot_departures` / `freight_depot_arrivals` (vehicles/h) — hourly counts over all vehicles' depot times.
  - `freight_active_vehicles_<provider>` (vehicles) — 5-min sampling: vehicle active in `[first departure, last arrival)`; emitted with FRACTIONAL `hour` (t/3600, 1/12 steps) — document this in the module docstring; render treats hour as plain x.
- `carriers` = `carriers_parse.parse_carriers(...)` result; `excluded` = `extract_freight_provider.parse_run(...).excluded`. Provider via `freight_classify.provider_of`.

**Ported join (complete):**
```python
def parcels_per_hour_by_provider(fev, carriers, excluded):
    out = {}  # provider -> {hour: parcels}
    for c in carriers:
        prov = freight_classify.provider_of(c.carrier_id, c.attrs.get("provider"))
        demand = {sid: s.capacity_demand for sid, s in c.services.items()}
        for t in c.tours:
            vid = t.event_vehicle_id(c.carrier_id)
            if vid in excluded:
                continue
            stop_demands = [demand.get(sid, 1) for sid in t.service_ids]
            starts = fev.service_starts.get(vid, [])
            if not stop_demands or not starts:
                continue
            bins = out.setdefault(prov, {})
            if len(starts) == len(stop_demands):        # 1:1 zip by stop order
                for st, d in zip(starts, stop_demands):
                    h = min(24, int(st // 3600)); bins[h] = bins.get(h, 0) + d
            else:                                        # mismatch: spread, conserve total
                per = sum(stop_demands) / len(starts)
                for st in starts:
                    h = min(24, int(st // 3600)); bins[h] = bins.get(h, 0) + per
    return out
```

- [ ] **Step 1: Write failing tests** — using the Task-1 fixture cache + the `mini_lmd` carriers XML: dhl tour v0 has services s0(60)+s1(30) and exactly 2 start events (29400→h8, 32400→h9) → `freight_parcels_h_dhl` rows `(8, 60), (9, 30)`; a mismatch case (drop one start time from a stub `FreightEvents`) conserves the total (90 split evenly); excluded vehicle contributes nothing; `freight_depot_departures` has `(8, 1)`; active-vehicles rows span 8.0 → 10.0 in 1/12 steps with value 1.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** `hourly_series` (+ the two sub-builders) and wire in `build_kpis.build`: inside the events branch, after `ensure_caches`: `fev = freight_events.parse_freight_cache(frt_cache)`; reuse the provider `pf` (refactor the provider block to compute `pf = efp.parse_run(...)` once and pass into `efp.extract(..., pf=pf)` — add that optional param — and `extract_vehicles.extract(..., pf=pf)`); `ts_rows += freight_events.hourly_series(fev, pf.carriers, pf.excluded)` before `timeseries.write`.
- [ ] **Step 4: Run, verify pass** (full suite; render Task-C-8 charts 16–18 now light up automatically — no render change).
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/freight_events.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/extract_freight_provider.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_freight_events.py
git commit -m "feat(kpi): event-derived freight hourly series (parcels/provider, depots, active) (v2 Plan D Task 3)"
```

---

## Task 4: `geometry.py` — network reader, DRT paths, simplification

**Files:**
- Create: `analysis/kpi/geometry.py`
- Test: `analysis/kpi/tests/test_geometry.py`
- Fixture: `tests/fixtures/mini_events/MINI.output_network.xml.gz` (hand-authored: 3 nodes at EPSG:25832 coords near Hoyerswerda e.g. (864000, 5705000)…, links `l1` n1→n2, `l2` n2→n3, plus one unused link `l9`)

**Interfaces:**
- `reconstruct_drt_paths(drt_cache) -> (veh_path, used_links)` — port of `build_drt_dashboard.py:91-124`: `veh_path[v] = [(link_id, occupancy)]` from `entered link` (vehicle startswith `drt_`), occupancy from `PersonEntersVehicle`/`PersonLeavesVehicle` with driver excluded (`person != vehicle`).
- `freight_used_links(fev) -> set[str]` (from `FreightEvents.veh_links`).
- `load_link_geometry(network_gz, used_links) -> dict[link_id, LinkGeo]` — `@dataclass LinkGeo: flon, flat, tlon, tlat, length_m` — port of `:128-150`: streaming `ET.iterparse` + `el.clear()`, nodes first, links filtered to `used_links`, batch pyproj transform, round 5 decimals; `length_m = math.hypot` in projected coords.
- `drop_collinear(pts, eps=1e-6) -> pts` and `douglas_peucker(pts, tol) -> pts` (complete standard implementation; `pts` = [(lon, lat)]).
- `polyline_runs(path, link_geo) -> list[list[(lat, lon)]]` — consecutive links chain into one run while `to == next from`; break otherwise (replaces the Plotly `None`-separator idiom with Leaflet-ready lists).

- [ ] **Step 1: Create the network fixture** (one-off script, gzip).
- [ ] **Step 2: Write failing tests** — paths: fixture cache yields `drt_veh_1`'s links with occupancy 0 before `PersonEntersVehicle` and 1 after; geometry: `l1`/`l2` present with 5-decimal WGS84 coords in lon∈(14,15), lat∈(51,52), `l9` absent; `douglas_peucker([(0,0),(0.5,1e-9),(1,0)], 1e-6)` drops the middle point; `polyline_runs` chains l1+l2 into ONE run of 3 points.
- [ ] **Step 3: Run, verify fail.**
- [ ] **Step 4: Implement** (pyproj import at module top — it is a hard dep of this module; maps.py guards ImportError around importing geometry).
- [ ] **Step 5: Run, verify pass.**
- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/geometry.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_geometry.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/mini_events
git commit -m "feat(kpi): network geometry + DRT path reconstruction + simplification (v2 Plan D Task 4)"
```

---

## Task 5: Network distributions — `drt_tour_distance` + `occ_km`

**Files:**
- Modify: `analysis/kpi/distributions.py`, `analysis/kpi/build_kpis.py`
- Modify test: `tests/test_distributions.py`

**Interfaces:**
- `distributions.extract(run_dir, prefix, recon=None, veh_km=None, occ_km_shares=None)` — two new optional params: `veh_km: dict[veh, km]` → series `drt_tour_distance` (`bin_equal_width(values, 16)`, unit km); `occ_km_shares: dict[level, share]` → `occ_km` rows (bin_lo=bin_hi=level, unit share). When given, the Plan-A "deferred" print disappears.
- `build_kpis.build` computes them in the events branch: `veh_path, used = geometry.reconstruct_drt_paths(drt_cache)`; `link_geo = geometry.load_link_geometry(network, used | freight_used)` (network file `<prefix>.output_network.xml.gz`; skip all of this with an ASCII note when absent); `veh_km[v] = sum(length_m)/1000` per path; `dist_by_occ[level] += length_m` → shares. The same `veh_path`/`link_geo` objects are passed on to `maps.py` (Task 6) — computed ONCE.

- [ ] **Step 1: Write failing tests** — stub `veh_km={"v0": 100.0, "v1": 300.0}` → `drt_tour_distance` rows exist, total count 2; `occ_km_shares={0: 0.4, 1: 0.6}` → `occ_km` rows with values 0.4/0.6; both None → old behavior incl. the deferred-note only mentioning what is still absent.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** (+ build_kpis orchestration; occupancy-km ALSO renders automatically in the Plan-C occupancy chart — no render change).
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/distributions.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_distributions.py
git commit -m "feat(kpi): network-based drt_tour_distance + occ_km distributions (v2 Plan D Task 5)"
```

---

## Task 6: `maps.py` — DRT layers + `map_data.json`

**Files:**
- Create: `analysis/kpi/maps.py`
- Test: `analysis/kpi/tests/test_maps.py`

**Interfaces:**
- `build_map_data(run_dir, prefix, veh_path=None, link_geo=None, fev=None, carriers=None, excluded=None, n_sample=None) -> dict` and `write(map_data, out_file)` (JSON, `separators=(",", ":")`).
- Output dict (exact structure — `render_maps.py` and tests consume it):

```json
{
  "center": [lat, lon],
  "drt": {
    "service_area": [[[lat, lon], ...] per exterior ring],
    "depots": [{"name": "dhl", "lat": .., "lon": ..}],
    "rail_stops": [{"name": "..", "lat": .., "lon": .., "feeders": 0}],
    "vehicles": {"<veh>": {"segs": {"<occ_level>": [[[lat, lon], ...] per run]},
                            "stops": [{"lat": .., "lon": .., "t": 28800, "n": 1, "kind": "pu"}]}},
    "pu": [[lat, lon], ...], "do": [[lat, lon], ...],
    "cap": 8
  },
  "lmd": {
    "tours": [{"veh": "..", "provider": "dhl", "carrier": "dhl",
               "runs": [[[lat, lon], ...]]}],
    "stops": [{"lat": .., "lon": .., "provider": "dhl", "veh": "..", "t": 29400, "demand": 60}],
    "heat": [[lat, lon, w], ...],
    "depots": [{"name": "dhl", "lat": .., "lon": ..}]
  }
}
```

**DRT layer construction:**
- `vehicles`: per vehicle, group `veh_path` by occupancy level → `geometry.polyline_runs` → `drop_collinear` → `douglas_peucker(tol=1e-5)` per run.
- `pu`/`do`: from `<prefix>.output_drt_legs_drt.csv` (`fromX/fromY/toX/toY`, transform, round 5); if `n_sample` set, sample with `random_state=42` (the legacy lever).
- Per-vehicle `stops`: the same legs filtered by `vehicleId`, numbered by departure order (`n`), `kind` pu/do.
- `service_area`: geopandas read of the shp (lazy import; ImportError or missing file → key omitted + ASCII note); exterior rings transformed.
- `depots`: `lmd-depots.csv` (`;`-sep `provider;x;y`).
- `rail_stops`: port of the fed/unfed logic (routeProfile-referenced stopFacilities inside the service polygon, feeders = DRT dropoffs within 600 m, dedupe by rounded coord).
- `cap`: from kpis or default 8.

**LMD layers** are Task 7 (the `lmd` key is `{}` until then).

- [ ] **Step 1: Write failing tests** — fixture-driven (mini_events cache + network + a stub legs CSV written by the test): vehicles dict has `drt_veh_1` with occupancy-keyed runs; coords are 5-decimal WGS84; `n_sample=1` caps pu list at 1; missing shp/depots/rail → keys absent, no raise; `write` produces valid JSON.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/maps.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_maps.py
git commit -m "feat(kpi): DRT map layers + map_data.json (v2 Plan D Task 6)"
```

---

## Task 7: `maps.py` — LMD layers

**Files:**
- Modify: `analysis/kpi/maps.py`
- Modify test: `analysis/kpi/tests/test_maps.py`

**Construction (fills the `lmd` key):**
- `tours`: per `FreightEvents.veh_links` vehicle → `polyline_runs` + simplification; `provider`/`carrier` resolved by matching the event_vehicle_id against `carriers` tours (`t.event_vehicle_id(c.carrier_id)`); excluded vehicles omitted.
- `stops`: order-based join exactly like Task 3's `parcels_per_hour_by_provider` but emitting per-stop records — i-th service-start time+the vehicle's CURRENT link at that time (nearest preceding entered-link; fall back to skipping the stop when no link known) + i-th `capacityDemand`; coords = link midpoint `((flon+tlon)/2, (flat+tlat)/2)`.
- `heat`: link-enter counts per link over all freight vehicles → `[lat_mid, lon_mid, count]`.
- `depots`: same as DRT (shared).

- [ ] **Step 1: Write failing tests** — with mini_lmd carriers + fixture events: one tour for the dhl vehicle with provider "dhl"; stops carry `demand` 60/30 in order; heat contains `l1`/`l2` midpoints with weight 1; excluded vehicle (stub excluded set) omitted from tours.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/maps.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_maps.py
git commit -m "feat(kpi): LMD map layers (tours/stops/heat) (v2 Plan D Task 7)"
```

---

## Task 8: Vendor Leaflet + `render_maps.py` + page integration

**Files:**
- Create: `analysis/kpi/vendor/leaflet.js`, `vendor/leaflet.css`, `vendor/leaflet-heat.js`, `vendor/leaflet.markercluster.js`, `vendor/MarkerCluster.css`, `vendor/MarkerCluster.Default.css`
- Create: `analysis/kpi/render_maps.py`
- Modify: `analysis/kpi/render.py` (`render_page` gains optional `extra_head=""` for the Leaflet CSS+JS inline block), `analysis/kpi/build_kpis.py`
- Test: `analysis/kpi/tests/test_render_maps.py`

**Interfaces:**
- `render_maps.build_blocks(map_data, uid) -> {"drt": {"html","js"}, "lmd": {"html","js"}, "head": "<style>…</style><script>…</script>"}` — the blocks plug into the Plan-C `map_block` params unchanged; `head` = inlined vendor CSS+JS (read once) + `<script>const MAP_DATA_<uid>=…;</script>`.
- Tiles JS (theme-aware, the only online dependency):

```javascript
const TILE = DARK ? 'dark_all' : 'light_all';
L.tileLayer('https://{s}.basemaps.cartocdn.com/' + TILE + '/{z}/{x}/{y}{r}.png',
  {attribution: 'OpenStreetMap, CARTO', maxZoom: 19, subdomains: 'abcd'}).addTo(map);
```
- DRT map JS: `L.map(id, {preferCanvas: true})`; occupancy-colored polylines per vehicle (`occ 0` gray `#8a8f98`, `occ >= 1` a fixed 8-step ramp — reuse the Plan-C alpha ramp formula on `V('--seq')`); layer groups: `tours` (all vehicles), `depots` (circleMarkers), `rail` (circleMarkers, radius ∝ sqrt(feeders), gray when 0), `heatPu`/`heatDo` (`L.heatLayer(MAP_DATA.drt.pu, {radius: 14, blur: 18, maxZoom: 15})`); controls (plain HTML above the map div): vehicle `<select>` (option "Alle" + one per vehicle; change ⇒ clear tours group, add only the selection's polylines + numbered `divIcon` stop badges), checkboxes for depots/rail/heatPu/heatDo.
- LMD map JS: mode radio (Touren | Stopps | Heatmap): tours = polylines colored by provider (`PROVIDER_SLOTS` colors via `CAT`, other→gray) with popup veh/provider/carrier; stops = `L.markerClusterGroup({spiderfyOnMaxZoom: true, disableClusteringAtZoom: 18, maxClusterRadius: 40})` of circleMarkers with popup (provider, veh, HH:MM, demand); heat = `L.heatLayer(MAP_DATA.lmd.heat, {radius: 14, blur: 18})`; provider/carrier/vehicle `<select>` filters re-filter tours+stops.
- `build_kpis.build`: in the events branch, after the CSVs: `md = maps.build_map_data(...)`; `maps.write(md, out / "map_data.json")`; `blocks = render_maps.build_blocks(md, uid="m0")`; `render.render_run_page(data, title, maps=blocks)` (and `render_page(..., extra_head=blocks["head"])`). `--no-events` ⇒ `maps=None` (sections omitted — Plan-C behavior).

- [ ] **Step 1: Vendor the files** (PowerShell, then `git add` explicitly):
```powershell
$v = "parcel-demand-2-matsim-pipeline/analysis/kpi/vendor"
Invoke-WebRequest https://unpkg.com/leaflet@1.9.4/dist/leaflet.js -OutFile "$v/leaflet.js"
Invoke-WebRequest https://unpkg.com/leaflet@1.9.4/dist/leaflet.css -OutFile "$v/leaflet.css"
Invoke-WebRequest https://unpkg.com/leaflet.heat@0.2.0/dist/leaflet-heat.js -OutFile "$v/leaflet-heat.js"
Invoke-WebRequest https://unpkg.com/leaflet.markercluster@1.5.3/dist/leaflet.markercluster.js -OutFile "$v/leaflet.markercluster.js"
Invoke-WebRequest https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.css -OutFile "$v/MarkerCluster.css"
Invoke-WebRequest https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.Default.css -OutFile "$v/MarkerCluster.Default.css"
```
(Note: leaflet.css `url(images/...)` references stay dead — acceptable because no default markers/layers-control are used; add a CSS override hiding `.leaflet-control-attribution` image if any icon looks broken.)
- [ ] **Step 2: Write failing tests** — `build_blocks` on a minimal map_data dict: html contains `id="map_drt_m0"` + the vehicle select; js contains `L.map(` and `markerClusterGroup`; head inlines `leaflet` source (assert a known Leaflet string like `L.Map=`) and `MAP_DATA_m0`; page-level test: `build(...)` on the drtrun fixture with `no_events=True` still renders WITHOUT any Leaflet (regression: maps only with events).
- [ ] **Step 3: Run, verify fail.**
- [ ] **Step 4: Implement** (JS assembled as Python format-strings like `JS_SETUP`; keep ALL Leaflet JS inside the blocks so map-free pages carry zero Leaflet bytes).
- [ ] **Step 5: Run, verify pass** (full suite).
- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/vendor \
        parcel-demand-2-matsim-pipeline/analysis/kpi/render_maps.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/render.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render_maps.py
git commit -m "feat(kpi): vendored Leaflet map sections on both tabs (v2 Plan D Task 8)"
```

---

## Task 9: Usability spike on married250 — measure, then lock the budget

**Files:**
- Modify: `analysis/kpi/tests/test_render.py` (budget assert), possibly `analysis/kpi/maps.py` (levers)

- [ ] **Step 1: Full build** on the real run (events + network parsing — expect several minutes):
```bash
python -u build_kpis.py --run-dir ../../hagrid-matsim-output/DRT_BASELINE_13052025_married250_iter300_jsprit1000
```
- [ ] **Step 2: Measure & record** — file size of `kpi_dashboard.html` and `map_data.json`; open in a real browser and check the spec §3.5 gate: time-to-interactive within a few seconds; vehicle dropdown responsive with all 250 entries; heatmap toggles instant; both themes.
- [ ] **Step 3: Decide per spec §3.5:**
  - **Fast & responsive** → round the measured page size UP to the next MB and hard-code it as the new budget assert in `test_render.py` (replacing the 2 MB interim; ceiling stays ≤ 5 MB working assumption — if the measurement exceeds 5 MB but is fast, the MEASURED number wins and gets documented, per the confirmed "usability, not bytes" decision).
  - **Sluggish** → apply levers in order and re-measure after each: (a) `n_sample=3000` for PU/DO heat/points (wire the param through `build_kpis`); (b) lazy per-vehicle polylines: default view renders NO tour polylines (only heat/stops/depots), polylines injected from `MAP_DATA` only on dropdown selection; (c) only then harder cuts (drop a heat layer) — with user consultation.
- [ ] **Step 4: Run the full suite** — green with the final budget.
- [ ] **Step 5: Commit** with the measured numbers in the message:
```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/maps.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py
git commit -m "test(kpi): married250 usability gate passed - <X.X> MB page, budget asserted (v2 Plan D Task 9)"
```

---

## Task 10: Final validation vs BOTH legacy dashboards (spec §5)

**Files:** none new (verification evidence + SESSION_LOG material).

- [ ] **Step 1: Side-by-side number check** on married250 — v2 page vs `analysis/drt-headline/drt_dashboard.html` AND the legacy Java `HAGRID_Dashboard_*.html`. Minimum list: DRT rides, rejection rate, wait median/P95, veh-km, person-km, service ratios, occupancy shares, feeder trips; LMD per-provider parcels/km/cost for dhl+hermes, fleet utilization, tour histograms' shape, parcels/h-by-provider totals (Σ must equal delivered parcels — the legacy chart's validation), depot dep/arr counts, map spot checks (same tour shapes for 2 sampled vehicles, rail-stop feeder counts).
- [ ] **Step 2: Write the deviation list** (expected: missedParcels scaling v1 = raw ratio re-allocation; event-reconstructed geometry ~3 % low; link-volume map deliberately dropped; straight-line link rendering) — every UNEXPECTED deviation is a bug to fix before closing.
- [ ] **Step 3: Record sizes** (per-run page, comparison page) and paste the whole check into the final commit message / session log.
- [ ] **Step 4: Commit**
```bash
git commit --allow-empty -m "chore(kpi): v2 dashboard validated vs both legacy dashboards on married250 (v2 Plan D Task 10) - <summary>"
```

---

## Self-Review (against spec §3.1/§3.2/§3.3/§3.5/§5 + Plan A/C deferred lists)

- **Deferred from Plan A:** `drt_tour_distance` + `occ_km` (T5), stem%-VRP — hmm: stem% needs depot→first-stop routed distance; **explicitly re-deferred**: the VRP table footnote stays until a routed-distance source exists (documented here — the spec's table ships without stem%, matching the Plan-A note). Event-derived freight series (T3), map geometry (T4/6/7). ✔
- **§3.1 kpi_timeseries new series:** all four freight series (T3) + the two DRT series already done in Plan A. ✔
- **§3.2 events_cache single pass + non-exclusive:** T1 (predicates independent, shared-vehicle fixture line asserted in both caches). ✔
- **§3.2 maps.py layers:** DRT service area/depots/rail fed-unfed/occupancy polylines/numbered PU-DO/heatmaps (T6); LMD provider polylines/stop clusters+popups/link heatmap (T7); 5-decimal rounding + collinear + DP simplification (T4); no background network layer. ✔
- **§3.3 rendering:** map sections fill the Plan-C `map_block` hooks; vehicle dropdown, heat toggles, provider/carrier/vehicle filters (T8); vendored Leaflet 1.9.4 + heat + markercluster inline, tiles-only online (T8). ✔
- **§3.4** is Plan B. **§3.5 spike:** T9 implements measure-then-budget with the legacy levers. **§5 validation:** T10. ✔
- **Type consistency:** `FreightEvents` fields used identically in T2/T3/T7; `polyline_runs`/`LinkGeo` between T4 and T6/7; `map_data` dict schema between T6/7 and T8; `build_tab(..., map_block=...)` matches Plan C's signature. ✔
- **Graceful degradation:** every optional input skips with an ASCII note; `--no-events` = Plan-C page unchanged. ✔
