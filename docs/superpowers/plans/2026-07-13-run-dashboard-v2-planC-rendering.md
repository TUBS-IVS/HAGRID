# Run-Dashboard v2 — Plan C: Rendering (Tabs, Tiles, Charts, Tables) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The per-run dashboard becomes a tabbed **DRT | LMD** page in the 1e design system with the full legacy tile/chart/table scope (spec §3.3), consuming ONLY the canonical CSVs (`kpis_long`, `kpi_timeseries`, `kpis_provider`, `kpi_iterations`, `kpi_distributions` + one new `kpi_vehicles.csv`). Maps and event-derived freight hourly series are Plan D — every chart/section skips gracefully when its series is absent, so Plan D only adds data.

**Architecture:** `render.py` stays the core (palette, CSS, page skeleton, tab machinery, shared helpers) and gains a `RunData` loader + small JS extensions (tile tooltips, vline plugin, toggle helper, provider colors). Two NEW modules own the tab content: `render_drt.py` and `render_lmd.py`, each exposing `build_tab(data, uid, compact=False, map_block=None) -> (html, js)` — `map_block` is the Plan-D insertion point (None ⇒ section omitted), `compact=True` is the comparison-page per-run-tab mode (tiles + core charts, no distributions/convergence/tables/scatter/maps). Four small data-layer completions (all network-free, tabular sources only) fill the tile gaps.

**Tech Stack:** Python 3.13, pandas, Chart.js 4 (already vendored inline), pytest 9.x. ASCII-only `print()`, `python -u`.

## Global Constraints

- 1e long-CSV schema (`kpis_long.csv`) frozen — new data lands in the existing v2 CSVs or the new `kpi_vehicles.csv`, never in new `kpis_long` columns.
- Never edit the legacy dashboards (`DashboardGenerator.java`, `analysis/drt-headline/*.py`).
- Dataviz rules: no dual axes, no pies/donuts/radar/polar; fixed categorical slots (color follows the entity); sequential single-hue for magnitudes; legend only at ≥ 2 series; German number formatting presentation-only; light + dark theme (existing CSS-token system).
- Provider colors: fixed provider→slot map, stable across runs, charts and (later) map: `PROVIDER_SLOTS = {"dhl": 0, "amazon": 1, "hermes": 2, "dpd": 3, "gls": 4, "ups": 5, "fedex": 6, "dp/dhl": 7}`, everything else (incl. `other`) → neutral gray `#8a8f98`.
- Chart color markers stay client-side (`__seq` / `__slot` / `__slots` + `resolveColors`) — never hardcode hex in chart configs (exceptions: the neutral gray for `other`, occupancy ramp).
- Budgets (test-asserted): per-run page (still map-free after Plan C) **< 2 MB** interim; comparison **< 3 MB**. Plan D re-measures and sets the final ≤ 5 MB number.
- ASCII-only `print()`; package flat, no `__init__.py`; tests from `analysis/kpi/`: `python -u -m pytest tests/ -q`.
- No `.gitignore` changes; explicit `git add` lists; branch `hendrik`; no master merge.
- Existing tests must stay green — `test_render.py` asserts (e.g. the `"9171"` rides tile, `"prefers-color-scheme"`) must survive; only its size budget line changes (1 MB → 2 MB interim).

## File Structure

- Modify: `analysis/kpi/extract_drt.py` — 7 missing tile-level KPIs (Task 1)
- Modify: `analysis/kpi/carriers_parse.py` — selected-plan score (Task 2)
- Modify: `analysis/kpi/extract_freight_provider.py` — per-provider `travel_hours`+`score`, `provider="all"` rows (Task 2)
- Modify: `analysis/kpi/distributions.py` — `lmd_carrier_score` series (Task 2)
- Create: `analysis/kpi/extract_vehicles.py` — per-vehicle wide CSV `kpi_vehicles.csv` (Task 3)
- Modify: `analysis/kpi/render.py` — RunData loader, JS/CSS extensions, tabbed `render_run_page` (Task 4)
- Create: `analysis/kpi/render_drt.py` (Tasks 5–6)
- Create: `analysis/kpi/render_lmd.py` (Tasks 7–9)
- Modify: `analysis/kpi/build_kpis.py`, `analysis/kpi/build_comparison.py` (Tasks 3, 10)
- Tests: extend `tests/test_extract_drt.py`, `test_extract_freight_provider.py`, `test_distributions.py`, `test_render.py`, `test_comparison.py`, `test_build_kpis.py`; create `tests/test_extract_vehicles.py`, `tests/test_render_drt.py`, `tests/test_render_lmd.py`

## New CSV: `kpi_vehicles.csv` (per-vehicle, wide, `;`-delimited)

Header (exact): `run_id;role;vehicle_id;provider;vehicle_type;distance_km;duration_h;travel_h;parcels;stops;load_factor;excluded;occupied_h;active_h;shift_h;ratio_active`

- `role=freight` rows: from `extract_freight_provider.parse_run(...).vehrecords` joined with `TimeDistance_perVehicle.tsv` on `event_vehicle_id` (`distance_km` = `travelDistance[km]`, `duration_h` = `tourDuration[h]`, `travel_h` = `travelTime[h]`); `excluded` = 0/1; drt columns empty.
- `role=drt` rows (only when `recon` available): `vehicle_id` = recon `per_veh` key, `occupied_h/active_h/shift_h` = `occupied_s/active_s/shift_s / 3600`, `ratio_active` = `ratio_active`; provider = `drt`, vehicle_type = `DRT`; freight columns empty.
- Missing values = empty string. Floats `"{:.6g}"`.
- Powers: LMD operational scatters, provider-summary drilldown table, DRT per-vehicle service-time bar (spec §3.3 DRT-7, LMD-8/9). Justified by spec §3: "new data lands in additional CSV files with their own schemas".

## Fixed tile sets (exact `kpi_name` sources)

**DRT tab — 22 tiles** (label → kpis_long `kpi_name` [+ sub-line source]; `(T1)` = added by Task 1):
1. DRT-Modal-Anteil → `modal_share_drt`
2. DRT-Fahrten → `drt_rides` [sub: `drt_passengers` (T1) Pax]
3. Wartezeit (Median) → `wait_median` [sub: `wait_p95`]
4. Wartezeit (Ø) → `wait_mean` [sub: `wait_below_15min` "< 15 min"]
5. Ablehnungsquote → `drt_rejection_rate` [sub: `drt_rejections` abs.]
6. Fahrzeuge → `drt_vehicles`
7. Fahrzeug-km → `drt_vehicle_km` [sub: `drt_empty_ratio` Leeranteil]
8. Personen-km → `drt_passenger_km`
9. Service-Zeit (aktiv) → `service_ratio_active`
10. Service-Zeit (Schicht) → `service_ratio_shift`
11. Auslastung (Fahrten) → `fleet_utilisation_by_trips` (T1)
12. Auslastung (Zeit) → `fleet_utilisation_by_time`
13. Ø Pax an Bord → `mean_pax_aboard`
14. Umwegfaktor → `detour_factor`
15. Ø Fahrtlänge → `drt_trip_distance_mean` (T1)
16. Tourdauer gesamt → `drt_tour_hours_total` (T1)
17. Fahrzeit gesamt → `drt_drive_hours_total` (T1)
18. Wartedauer gesamt → `drt_wait_hours_total` (T1)
19. Servicedauer gesamt → `drt_service_hours_total` (T1)
20. Feeder-Fahrten → `drt_feeder_trips` [sub: `drt_feeder_share`]
21. Kosten (Platzhalter) → `drt_cost_bottom_up_placeholder` [sub: `drt_cost_per_ride_placeholder` €/Fahrt + Currie/Fournier-Benchmark]
22. Pooling-Quote → `pooling_rate` [sub: `sharing_factor`]

Events-only tiles (9–13, 16–19) render only when their kpi row exists (no_events runs simply show fewer tiles). Every tile gets a `tip=` hover text; for tile 21 copy the benchmark value + tooltip wording **verbatim** from the legacy `analysis/drt-headline/build_drt_dashboard.py` (search "Currie") — do not invent numbers.

**LMD tab — 20 tiles** (source: kpis_long `freight`/`economic` rows, `kpis_provider.csv` per-provider/type:/all rows; `(T2)` = added by Task 2):
1. Aktive Fahrzeuge → long `freight_vehicles`
2. Carrier → long `carriers` [sub: `all;carriers_delivery` / `all;carriers_supply` (T2)]
3. CEP-Vans → provider `type:VAN;vehicles`
4. Cargobikes → provider `type:CARGOBIKE;vehicles`
5. Supply-Fahrzeuge → sum `type:TRUCK|TRUCK_LIGHT|SUPPLY_VAN;vehicles`
6. Pakete → long `parcels_total` [sub: `parcels_handled`]
7. Verpasste Pakete → long `parcels_missed` [sub: rate = missed/total]
8. Unassigned → long `parcels_unassigned`
9. Zustellquote → long `delivery_rate`
10. Stopps → `all;stops` (T2) [sub: stops/h = stops / long `freight_tour_hours`]
11. Ø Auslastung → `all;avg_load_factor` (T2)
12. Distanz gesamt → long `freight_vehicle_km`
13. Ø Tourlänge → long `freight_vehicle_km` / `freight_tours`
14. Ø Geschwindigkeit → long `freight_vehicle_km` / `all;travel_hours` (T2)
15. Tourstunden → long `freight_tour_hours`
16. Fahranteil → `all;travel_hours` / long `freight_tour_hours`
17. Kosten gesamt → long `freight_total_costs` [sub: economic `freight_cost_per_parcel` €/Paket]
18. Fixkosten → sum provider `cost_fixed`
19. Touren → long `freight_tours` [sub: Touren/Fahrzeug]
20. Low-Util ausgeschlossen → sum provider `excluded_vehicles`

---

## Task 1: DRT tile-gap KPIs (`extract_drt.py`)

**Files:**
- Modify: `analysis/kpi/extract_drt.py`
- Modify test: `analysis/kpi/tests/test_extract_drt.py`

**Interfaces:**
- Produces new `kpis_long` rows: `passenger/drt_passengers` (pax, customer-stats last row `rides_pax`), `passenger/drt_trip_distance_mean` (km, last row `distance_m_mean/1000`), and — recon-gated, next to the existing `service_ratio_*` block — `system/fleet_utilisation_by_trips` (share, `recon["fleet"]["util_by_trips"]`), `system/drt_tour_hours_total` (h, `fleet["tour_s"]/3600`), `system/drt_drive_hours_total` (h, `drive_s/3600`), `system/drt_wait_hours_total` (h, `waiting_s/3600`), `system/drt_service_hours_total` (h, `stop_s/3600`).

- [ ] **Step 1: Add failing assertions to `test_extract_drt.py`** — extend the existing recon-stub test: stub `recon["fleet"]` gains `{"util_by_trips": 0.25, "tour_s": 360000.0, "drive_s": 200000.0, "waiting_s": 100000.0, "stop_s": 60000.0}`; assert rows `fleet_utilisation_by_trips == 0.25`, `drt_tour_hours_total == 100.0`, `drt_drive_hours_total` ≈ 55.5556, `drt_wait_hours_total` ≈ 27.7778, `drt_service_hours_total` ≈ 16.6667. Add a no-recon test asserting `drt_passengers` and `drt_trip_distance_mean` come from the drtrun fixture's customer-stats last row (compute the expected values by reading the fixture CSV once, hard-code them).
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** — 2 rows in the tabular block (guard: columns exist), 5 rows in the existing `if recon:` block (guard: keys exist via `.get`). Use `common.row(...)` exactly like the neighboring rows.
- [ ] **Step 4: Run, verify pass** (whole `test_extract_drt.py` green).
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/extract_drt.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_drt.py
git commit -m "feat(kpi): DRT tile-gap KPIs (util-by-trips, totals, trip length) (v2 Plan C Task 1)"
```

---

## Task 2: Provider data completions (score, travel hours, all-rows, score distribution)

**Files:**
- Modify: `analysis/kpi/carriers_parse.py` + `tests/test_carriers_parse.py`
- Modify: `analysis/kpi/extract_freight_provider.py` + `tests/test_extract_freight_provider.py`
- Modify: `analysis/kpi/distributions.py` + `tests/test_distributions.py`
- Modify fixtures: regenerate `tests/fixtures/mini_lmd/MINI.output_carriers.xml.gz` with plan `score` attributes

**Interfaces:**
- `carriers_parse.CarrierDef` gains `selected_plan_score: float | None` (from `<plan selected="true" score="...">`; None when absent).
- `extract_freight_provider.extract` additionally emits: per-provider `travel_hours` (h, from `TimeDistance_perCarrier.tsv` `travelTimes[h]` summed per provider) and `score` (score, sum of `selected_plan_score` over the provider's carriers, skip None); plus `provider="all"` rows: `carriers_delivery`, `carriers_supply` (counts by `carrier_type_of`), `stops` (sum over surviving delivery vehicles), `avg_load_factor` (mean lf over surviving delivery vehicles), `travel_hours` (sum over ALL carriers).
- `distributions.extract` additionally emits series `lmd_carrier_score` — `bin_equal_width(scores, 12)` over all carriers' `selected_plan_score` (skip None), unit `score`.

- [ ] **Step 1: Regenerate the mini fixture** with a one-off script (same gzip approach as Plan A Task 2): add `score="-100.0"` to dhl's selected plan, `score="-40.0"` to hermes', `score="-500.0"` to amazon_supply's — everything else byte-identical.
- [ ] **Step 2: Write the failing tests.**

```python
# append to tests/test_carriers_parse.py
def test_selected_plan_score():
    cs = {c.carrier_id: c for c in cp.parse_carriers(FIX / "MINI.output_carriers.xml.gz")}
    assert cs["dhl"].selected_plan_score == -100.0
    assert cs["hermes"].selected_plan_score == -40.0
```

```python
# append to tests/test_extract_freight_provider.py
def test_travel_hours_and_score_per_provider():
    rows = efp.extract(FIX, "MINI")
    assert _by(rows, "dhl", "travel_hours") == 8.333
    assert _by(rows, "dhl", "score") == -100.0

def test_all_rows():
    rows = efp.extract(FIX, "MINI")
    assert _by(rows, "all", "carriers_delivery") == 2
    assert _by(rows, "all", "carriers_supply") == 1
    # surviving delivery vehicles: dhl v0 (2 stops, lf 0.9) + hermes v0 (1 stop, lf 25/30)
    assert _by(rows, "all", "stops") == 3
    assert abs(_by(rows, "all", "avg_load_factor") - (0.9 + 25/30) / 2) < 1e-6
    assert abs(_by(rows, "all", "travel_hours") - (8.333 + 1.667 + 4.167)) < 1e-6
```

```python
# append to tests/test_distributions.py
def test_lmd_carrier_score_bins():
    rows = dist.extract(FIX, "MINI", recon=None)
    sc = [r for r in rows if r["series"] == "lmd_carrier_score"]
    assert sc, "expected lmd_carrier_score rows"
    assert sum(r["value"] for r in sc) == 3          # 3 carriers with a score
    assert min(r["bin_lo"] for r in sc) <= -500.0
```

- [ ] **Step 3: Run, verify fail.**
- [ ] **Step 4: Implement.** `carriers_parse`: read the `score` attr where the selected `<plan>` is already located (float or None). `extract_freight_provider`: travel hours from the already-loaded `TimeDistance_perCarrier` frame (`travelTimes[h]` column) grouped like `km`; score summed per provider; the five `all` rows computed from `ParsedFreight.vehrecords`/carriers and appended with `prow("all", ...)`. `distributions`: `import carriers_parse`, parse the carriers XML (guard: file exists), collect scores, `bin_equal_width(scores, 12)`.
- [ ] **Step 5: Run, verify pass** (all three test files + `test_real_married250.py` — the schema-drift heads have no score attr → None path exercised).
- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/carriers_parse.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/extract_freight_provider.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/distributions.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_carriers_parse.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_freight_provider.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_distributions.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/mini_lmd/MINI.output_carriers.xml.gz
git commit -m "feat(kpi): provider score/travel-hours/all-rows + carrier-score distribution (v2 Plan C Task 2)"
```

---

## Task 3: Per-vehicle CSV (`extract_vehicles.py` → `kpi_vehicles.csv`)

**Files:**
- Create: `analysis/kpi/extract_vehicles.py`
- Modify: `analysis/kpi/build_kpis.py` (wiring, next to the provider block)
- Test: `analysis/kpi/tests/test_extract_vehicles.py`; extend `tests/test_build_kpis.py` (header assert)

**Interfaces:**
- `extract(run_dir, prefix, recon=None, pf=None) -> list[dict]` — dict keys exactly the CSV columns minus `run_id`; `pf` = optional pre-computed `ParsedFreight` (else calls `extract_freight_provider.parse_run` itself; missing carriers XML → freight rows skipped with an ASCII note).
- `write(rows, meta, out_file)` — header exactly as specified in "New CSV" above; empty string for missing values.

- [ ] **Step 1: Write the failing tests**

```python
# tests/test_extract_vehicles.py
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import extract_vehicles as ev

FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


def test_freight_rows_join_tsv():
    rows = ev.extract(FIX, "MINI")
    frt = {r["vehicle_id"]: r for r in rows if r["role"] == "freight"}
    v1 = frt["freight_dhl_veh_dhl_ct_cep_size_s_h8_v1_1"]
    assert v1["distance_km"] == 40.0 and v1["excluded"] == 1
    v0 = frt["freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0"]
    assert v0["provider"] == "dhl" and v0["parcels"] == 90 and v0["stops"] == 2
    assert v0["travel_h"] == 5.0 and v0["excluded"] == 0


def test_drt_rows_from_recon():
    recon = {"per_veh": {"drt_1": {"occupied_s": 1800.0, "active_s": 3600.0,
                                   "shift_s": 7200.0, "ratio_active": 0.5}},
             "fleet": {}}
    rows = ev.extract(FIX, "MINI", recon=recon)
    drt = [r for r in rows if r["role"] == "drt"]
    assert drt[0]["vehicle_id"] == "drt_1" and drt[0]["occupied_h"] == 0.5
    assert drt[0]["ratio_active"] == 0.5 and drt[0]["provider"] == "drt"


def test_write_header(tmp_path):
    class M: run_id = "MINI"
    out = tmp_path / "kpi_vehicles.csv"
    ev.write(ev.extract(FIX, "MINI"), M, out)
    head = out.read_text(encoding="utf-8").splitlines()[0]
    assert head == ("run_id;role;vehicle_id;provider;vehicle_type;distance_km;duration_h;"
                    "travel_h;parcels;stops;load_factor;excluded;occupied_h;active_h;"
                    "shift_h;ratio_active")
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement `extract_vehicles.py`** — freight: `pf = pf or efp.parse_run(run_dir, prefix)`; index `TimeDistance_perVehicle.tsv` by `vehicleId`; per `VehRecord` build the row (`vehicle_type` = the classified slug, fall back to `type_id`); drt: iterate `recon["per_veh"]`. `write` mirrors `extract_freight_provider.write` (COLUMNS list, `_fmt`, empty string for None).
- [ ] **Step 4: Wire into `build_kpis.build`** inside the existing freight try/except (reuse the `pf` if you refactor `efp.extract` to accept one — otherwise call `ev.extract(run_dir, meta.prefix, recon=recon)` standalone) writing `out / "kpi_vehicles.csv"`; also call it when only DRT data exists (recon without freight). Extend `test_build_kpis.py` with the header assert for `kpi_vehicles.csv`.
- [ ] **Step 5: Run, verify pass** (incl. full `tests/` sweep).
- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/extract_vehicles.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_vehicles.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_build_kpis.py
git commit -m "feat(kpi): per-vehicle kpi_vehicles.csv (freight + drt) (v2 Plan C Task 3)"
```

---

## Task 4: Render core — RunData, tooltips, JS extensions, DRT|LMD scaffold

**Files:**
- Modify: `analysis/kpi/render.py`
- Test: extend `analysis/kpi/tests/test_render.py`

**Interfaces (consumed by Tasks 5–10):**
- `@dataclass RunData: kpis, ts, provider, iterations, distributions, vehicles` (all pandas DataFrames, empty-with-columns when the CSV is missing).
- `load_run_data(analysis_dir) -> RunData` (reads the 6 CSVs; `kpis_long`/`kpi_timeseries` exactly as `load_run_csvs` does today).
- `_tile(value, label, sub="", tip="")` — `tip` becomes a `title="..."` attribute (HTML-escape `"` as `&quot;`).
- `PROVIDER_SLOTS` dict + `OTHER_COLOR = "#8a8f98"` constants; `provider_slot(name) -> int | None` (None ⇒ gray).
- JS additions (inside `JS_SETUP`/new blocks): `const OTHER = '#8a8f98';`; `resolveColors` extended — `__slots` entries may be `null` → `OTHER`; new marker `__ramp: [level, cap]` → level 0 ⇒ `OTHER`, level ≥ 1 ⇒ sequential color at alpha `0.25 + 0.75*level/cap` (build via an `alphaSeq(a)` helper that converts `V('--seq')` hex to rgba).
- `VLINE_JS` — inline Chart.js plugin `vlines` (registered once) drawing dashed vertical marker lines: per chart `options.plugins.vlines.lines = [{"x": <fractional category index>, "label": "Median"}]`:

```javascript
const vlinePlugin = { id: 'vlines',
  afterDraw(chart, args, opts) {
    if (!opts || !opts.lines) return;
    const {ctx, chartArea, scales} = chart; const x = scales.x;
    for (const ln of opts.lines) {
      const px = x.getPixelForValue(ln.x);
      if (px < chartArea.left || px > chartArea.right) continue;
      ctx.save(); ctx.strokeStyle = V('--axis'); ctx.setLineDash([4, 3]);
      ctx.beginPath(); ctx.moveTo(px, chartArea.top); ctx.lineTo(px, chartArea.bottom); ctx.stroke();
      ctx.fillStyle = V('--ink2'); ctx.font = '11px system-ui';
      ctx.fillText(ln.label, px + 4, chartArea.top + 10); ctx.restore();
    }
  }};
Chart.register(vlinePlugin);
```
- `TOGGLE_JS` — `mkToggle(btnId, canvasId, cfgA, cfgB, labelA, labelB)`: creates the chart with `resolveColors(cfgA)`, button click swaps `data`+`options` between the two resolved configs and the button text.
- `DRILL_JS` + CSS — `function toggleVeh(key){document.querySelectorAll('tr[data-drill="'+key+'"]').forEach(r=>r.classList.toggle('show'));}` with `tr.vehrow{display:none} tr.vehrow.show{display:table-row}`.
- `chart_js(cid, cfg) -> str` helper = `"mk(" + json.dumps(cid) + ", resolveColors(" + json.dumps(cfg) + "));"` (extracted from the current inline loop).
- `render_run_page(data: RunData, title, maps=None) -> str` — NEW signature. Tab presence: DRT tab iff `data.kpis` has `kpi_group == "passenger"` rows; LMD tab iff `data.provider` non-empty or `kpi_group == "freight"` rows exist. Builds the `.tabbar` (labels "DRT"/"LMD", reusing `showTab`) + `.tab` divs from `render_drt.build_tab` / `render_lmd.build_tab` (lazy imports to avoid cycles), `maps` = optional `{"drt": block, "lmd": block}` passed through as `map_block`. If neither tab has data: render an empty page with the KPI table only.
- The old `render_run_sections` stays until Task 10 removes its last caller, then delete it.
- `render_kpi_table(kpis)` — the existing all-KPIs table extracted into a named function (both tabs' pages append it below the tabs, unchanged content).

- [ ] **Step 1: Write failing tests** — extend `test_render.py`:

```python
def test_load_run_data_missing_files_graceful(tmp_path):
    d = render.load_run_data(tmp_path)          # no CSVs at all
    assert d.provider.empty and d.vehicles.empty and d.iterations.empty

def test_tile_tooltip():
    html = render._tile("5", "X", tip='a "quoted" tip')
    assert 'title="a &quot;quoted&quot; tip"' in html

def test_run_page_has_drt_tab_and_plugins():
    # after build(...) on the drtrun fixture (no_events), load_run_data + render_run_page
    ...
    assert ">DRT<" in html and "showTab" in html
    assert "vlinePlugin" in html and "mkToggle" in html
    assert "9171" in html            # rides tile still present (regression)
```
(Fill the `...` with the existing `test_render_run_page` build-fixture boilerplate — copy it.)

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** everything in the Interfaces block. `render_run_page` keeps returning the full page via `render_page(title, tabbar + tabs + table, TAB_JS + VLINE_JS + TOGGLE_JS + DRILL_JS + joined_js)`. Until Tasks 5/7 exist, guard the lazy imports with try/except ImportError falling back to `render_run_sections` (removed again in Task 10) — OR implement Task 4 with minimal stub `build_tab`s that Tasks 5–9 flesh out; choose the try/except fallback so this task is independently green.
- [ ] **Step 4: Run, verify pass** (`test_render.py` fully green, size budget line updated to `< 2_000_000`).
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/render.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render.py
git commit -m "feat(kpi): render core - RunData, tooltips, vline/toggle/drill JS, DRT|LMD scaffold (v2 Plan C Task 4)"
```

---

## Task 5: DRT tab — tiles (`render_drt.py`)

**Files:**
- Create: `analysis/kpi/render_drt.py`
- Test: `analysis/kpi/tests/test_render_drt.py`

**Interfaces:**
- `build_tab(data, uid, compact=False, map_block=None) -> (html, js)`. This task delivers the module skeleton + `_tiles(data) -> str` (the 22-tile set from the table above); charts come in Task 6 (`build_tab` already concatenates `_tiles` + a charts placeholder list that Task 6 fills + `map_block` insertion after the tiles).
- Helpers imported from `render`: `_kpi, _tile, _fmt_de, _fmt_pct, _panel, chart_js`.

- [ ] **Step 1: Write failing tests**

```python
# tests/test_render_drt.py
import sys
from pathlib import Path
import pandas as pd
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import render, render_drt


def _data(rows):
    kpis = pd.DataFrame(rows)
    empty = pd.DataFrame()
    return render.RunData(kpis=kpis, ts=pd.DataFrame(columns=["series", "hour", "value"]),
                          provider=empty, iterations=empty, distributions=empty, vehicles=empty)


def test_tiles_full_set():
    rows = [{"kpi_group": "passenger", "kpi_name": n, "value": v, "unit": "", "source": ""}
            for n, v in [("drt_rides", 9171), ("wait_median", 600), ("wait_p95", 1173),
                         ("detour_factor", 1.43), ("pooling_rate", 0.25)]]
    rows.append({"kpi_group": "system", "kpi_name": "drt_vehicles", "value": 250,
                 "unit": "vehicles", "source": ""})
    html, js = render_drt.build_tab(_data(rows), uid="drt")
    assert "9.171" in html            # German-formatted rides tile
    assert "Umwegfaktor" in html and "1,43" in html
    assert 'title="' in html          # tooltips present


def test_events_only_tiles_skip_gracefully():
    html, js = render_drt.build_tab(_data([{"kpi_group": "passenger", "kpi_name": "drt_rides",
                                            "value": 10, "unit": "", "source": ""}]), uid="drt")
    assert "Service-Zeit" not in html   # no service_ratio_* rows -> tiles absent


def test_map_block_inserted():
    html, js = render_drt.build_tab(_data([{"kpi_group": "passenger", "kpi_name": "drt_rides",
                                            "value": 10, "unit": "", "source": ""}]),
                                    uid="drt", map_block={"html": "<div id='MAPX'></div>", "js": "//mapjs"})
    assert "MAPX" in html and "//mapjs" in js
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** `_tiles(data)`: one `_tile(...)` call per table row above, each wrapped in `if _kpi(...) is not None`. Formats: shares → `_fmt_pct`, waits → min via `/60`, km/h totals → `_fmt_de(v, 1)`, counts → `_fmt_de(v)`. Tooltips: one German sentence each stating definition + source (e.g. Auslastung (Zeit): "Zeitgewichtetes Mittel der Besetzung ueber Segmente konstanter Belegung, aus Event-Rekonstruktion"). `build_tab` returns `('<div class="tiles">'+tiles+'</div>' + map_html + charts_html, charts_js + map_js)`.
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/render_drt.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render_drt.py
git commit -m "feat(kpi): DRT tab tiles (22-tile legacy set) (v2 Plan C Task 5)"
```

---

## Task 6: DRT tab — charts (daily profile, distributions, occupancy, convergence, per-vehicle)

**Files:**
- Modify: `analysis/kpi/render_drt.py`
- Modify test: `analysis/kpi/tests/test_render_drt.py`

**Chart list (exact; each = `(title, canvas_id_prefix, source, type/markers)`); every chart is skipped when its source series/rows are absent:**

*Tagesverlauf* (from `data.ts`):
1. "DRT-Fahrten je Stunde (bedient)" `c_rides` — bar, `drt_rides`, `__seq`
2. "DRT-Anfragen je Stunde (eingereicht)" `c_subm` — line, `drt_requests_submitted`, `__seq` (separate chart — no dual axis)
3. "Ablehnungen je Stunde" `c_rej` — bar, `drt_rejections`, `__seq`
4. "Mittlere Wartezeit je Stunde [min]" `c_wait` — line, `drt_wait_mean` (values/60), `__seq`
5. "Feeder-Fahrten je Stunde" `c_feed` — bar with **toggle** (mkToggle): cfgA absolute `drt_feeder_trips`, cfgB share = feeder/rides per hour (0 when rides 0); button label "Absolut/Anteil"

*Verteilungen* (from `data.distributions`):
6. "Wartezeit-Verteilung [min]" `c_wdist` — bar over `drt_wait` bins (labels `"lo-hi"` in min), `__seq`, `vlines` at Median/Ø/P95 (x = kpis_long `wait_median|wait_mean|wait_p95` seconds/60 − 0.5, only markers whose kpi exists)
7. "Aktive Tourdauer je Fahrzeug [h]" `c_tdur` — bar over `drt_tour_duration` bins, `__seq`
8. (Plan D adds `drt_tour_distance` — render it here already with a guard so Plan D needs no render change: "Tourdistanz je Fahrzeug [km]" `c_tdist`)

*Besetzung & Modal Split:*
9. "Besetzungs-Dekomposition" `c_occ` — horizontal 100%-stacked (`indexAxis:'y'`, `stacked` both axes): rows = whichever of `occ_segments`/`occ_time`/`occ_km` exist in distributions (labels "Segmente"/"Zeit"/"Distanz"), one dataset per occupancy level with `__ramp: [level, max_level]`, values = shares
10. "Modal Split" `c_modal` — keep the existing stacked-bar builder (port from `render_run_sections`, `__slot` = MODE_SLOTS)

*Konvergenz* (from `data.iterations`):
11. "Fahrten über Iterationen" `c_it_rides` — line `drt_rides`, `__seq`
12. "Ablehnungsquote über Iterationen" `c_it_rej` — line `drt_rejection_rate`, `__seq`
13. "Wartezeit über Iterationen [min]" `c_it_wait` — two lines `wait_mean`+`wait_p95` (/60), `__slot` 0/1, legend on
14. "Modal Shares über Iterationen" `c_it_modal` — one line per `modal_share_<mode>` series, `__slot` = MODE_SLOTS

*Service-Zeit Detail* (from `data.vehicles`, role=drt):
15. "Besetzte Zeit je Fahrzeug [h]" `c_veh` — bar, vehicles sorted by `occupied_h` desc, labels = vehicle ids, `__seq`, height 260

**Section layout:** `<h2>` per group (Tagesverlauf / Verteilungen / Besetzung & Modal Split / Konvergenz / Service-Zeit Detail), charts in the existing `.grid2` panel grid. `compact=True` renders ONLY charts 1–4 + 10.

- [ ] **Step 1: Write failing tests** — synthetic `RunData` with: ts containing `drt_rides`+`drt_feeder_trips`; distributions containing `drt_wait` + `occ_time` rows; iterations containing `drt_rides` + `modal_share_drt`; vehicles with 2 drt rows. Assert: `"mkToggle"` in js (feeder toggle), `"vlines"` in js (wait markers), `"__ramp"` in js (occupancy), `"c_it_rides_drt"` canvas in html, per-vehicle chart labels contain the vehicle id, and `compact=True` yields NO `c_it_` canvases and NO `c_wdist`.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** — helpers inside `render_drt.py`: `_ts_chart(ts, series, title, cid, kind, transform=None)`, `_dist_chart(dist, series, title, cid, unit_div=1)`, `_iter_chart(it, series_list, title, cid, slots=None)`. Feeder toggle emits `mkToggle(...)` JS instead of `mk(...)`. Occupancy: pivot distributions rows by series/level, one dataset per level across the up-to-3 label rows.
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/render_drt.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render_drt.py
git commit -m "feat(kpi): DRT tab charts (profile, distributions, occupancy, convergence, per-vehicle) (v2 Plan C Task 6)"
```

---

## Task 7: LMD tab — tiles (`render_lmd.py`)

**Files:**
- Create: `analysis/kpi/render_lmd.py`
- Test: `analysis/kpi/tests/test_render_lmd.py`

**Interfaces:**
- `build_tab(data, uid, compact=False, map_block=None) -> (html, js)` (same contract as `render_drt.build_tab`).
- `_pv(provider_df) -> DataFrame` — provider pivot: rows = real providers (exclude `all` and `type:*`), columns = kpi_name, values. `_types(provider_df)`, `_all(provider_df, kpi)` accessors. Consumed by Tasks 8–9.

- [ ] **Step 1: Write failing tests** — synthetic RunData: kpis with freight rows (`freight_vehicles` 67, `parcels_total` 6372, `freight_vehicle_km` 5000, `freight_tours` 80, `freight_tour_hours` 500, `freight_total_costs` 12000, `carriers` 7), provider DataFrame with 2 providers + `type:VAN;vehicles=40` + `all;carriers_delivery=6`, `all;carriers_supply=1`, `all;stops=900`, `all;avg_load_factor=0.81`, `all;travel_hours=300`. Assert: "Aktive Fahrzeuge" + "67", "CEP-Vans" + "40", derived tiles ("Ø Tourlänge" = 5000/80 → "62,5", "Ø Geschwindigkeit" = 5000/300 → "16,7"), delivery/supply sub-line, tooltips present.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** the 20-tile set per the fixed table (guards: every tile only when its source exists; derived tiles guard div-by-zero). German tooltips (state derivation for computed tiles, e.g. Ø Geschwindigkeit: "Gesamtdistanz / reine Fahrzeit (ohne Servicezeiten)").
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/render_lmd.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render_lmd.py
git commit -m "feat(kpi): LMD tab tiles (20-tile legacy set) (v2 Plan C Task 7)"
```

---

## Task 8: LMD tab — charts (provider, vehicle-type, tour structure, hourly, scoring, scatter)

**Files:**
- Modify: `analysis/kpi/render_lmd.py`
- Modify test: `analysis/kpi/tests/test_render_lmd.py`

**Chart list (all skipped when source absent):**

*Provider-Analytik* (from `_pv`, providers sorted by `parcels_total` desc, bar colors `__slots` = `[provider_slot(p) ...]`, gray for other):
1. "Pakete je Provider" `c_p_parcels` — bar `parcels_total`
2. "Fahrzeuge je Provider" `c_p_veh` — bar `vehicles`
3. "Auslastung je Provider" `c_p_util` — bar `avg_load_factor` (%)
4. "Kosten je Provider (Komponenten)" `c_p_cost` — stacked bar, datasets `cost_fixed`/`cost_dist`/`cost_time` with `__slot` 0/1/2, legend on
5. "Stopps je Stunde je Provider" `c_p_stoph` — bar `stops_per_h`
6. "Pakete je km je Provider" `c_p_pkm` — bar `parcels_per_km`
7. "Zeitaufteilung Fahren vs. Service" `c_p_time` — horizontal 100%-stacked per provider: travel = `travel_hours`, service = `tour_hours - travel_hours` (clamp ≥ 0), datasets `__slot` 0/1
8. "Ø Tourdistanz je Provider [km]" `c_p_tourkm` — bar `km/tours`
9. "Ø Stopps je Tour je Provider" `c_p_stops` — bar `stops/tours`

*Fahrzeugtyp-Analytik* (from `type:` rows, labels via `freight_classify.VEHICLE_TYPE_LABELS` — import it):
10. "Distanz je Fahrzeugtyp [km]" `c_t_km` — bar `distance_km`, `__seq`
11. "Auslastung je Fahrzeugtyp" `c_t_lf` — bar `load_factor` (%), `__seq`
12. "km je Tour / Stopps je Tour je Typ" — TWO bars `c_t_kmt`, `c_t_st`, `__seq`

*Tour-Struktur* (from distributions): 13. "Tourdistanz-Verteilung [km]" `c_d_km` — `lmd_tour_distance` bins; 14. "Tourdauer-Verteilung [h]" `c_d_h` — `lmd_tour_duration` bins; both `__seq`.

*Tagesverlauf* (from ts; Plan-D series render as soon as they exist): 15. "Service-Starts je Stunde" `c_h_stops` — `freight_service_stops`; 16. "Pakete je Stunde je Provider" `c_h_parcels` — stacked bar over all `freight_parcels_h_<provider>` series (`__slots` provider colors); 17. "Aktive Fahrzeuge (5-min)" `c_h_active` — lines per `freight_active_vehicles_<provider>`; 18. "Depot-Abfahrten/-Ankünfte" `c_h_depot` — two lines `freight_depot_departures`/`freight_depot_arrivals`, `__slot` 0/1.

*Scoring* (iterations + distributions + provider): 19. "Carrier-Scores über Iterationen" `c_s_it` — lines `carrier_score_executed/worst/avg/best`, `__slot` 0–3, legend; 20. "Score-Verteilung (letzte Iteration)" `c_s_dist` — bar `lmd_carrier_score` bins, `__seq`; 21. "Score je Provider" `c_s_prov` — bar provider `score`, `__slots`.

*Operational Scatter* (from `data.vehicles` role=freight, excluded==0): 22. "Auslastung vs. Tourdistanz" `c_sc1` — scatter, one dataset per provider `{x: load_factor*100, y: distance_km}`, `__slot`=provider_slot; 23. "Pakete vs. Tourdauer" `c_sc2` — scatter `{x: parcels, y: duration_h}` per provider.

`compact=True` renders only charts 1, 2, 4, 13, 14.

- [ ] **Step 1: Write failing tests** — extend the synthetic RunData with distributions (`lmd_tour_distance`), iterations (`carrier_score_best`), vehicles (3 freight rows, 2 providers), ts (`freight_service_stops`). Assert: stacked cost chart datasets count == 3; scatter js contains `"scatter"` and provider labels; charts 16–18 absent (series missing) without error; compact mode has no scatter/scoring canvases.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** with three chart-builder helpers (`_prov_bar(pv, kpi, title, cid, pct=False)`, `_stack100(rows_labels, datasets, title, cid)`, `_scatter(vehicles, xcol, ycol, title, cid, xlabel, ylabel)`) so each chart is one call from the numbered list.
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/render_lmd.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render_lmd.py
git commit -m "feat(kpi): LMD tab charts (provider/type/hourly/scoring/scatter) (v2 Plan C Task 8)"
```

---

## Task 9: LMD tab — tables (VRP efficiency, provider summary + drilldown, low-util notice)

**Files:**
- Modify: `analysis/kpi/render_lmd.py`
- Modify test: `analysis/kpi/tests/test_render_lmd.py`

**Content:**
1. **VRP-Effizienz je Provider** — columns: Provider | Touren | km | km/Tour | Stopps/h | Stopps/km | Pakete/km | €/Paket (from `_pv`; German-formatted; footnote row "stem% folgt in Plan D").
2. **Provider-Übersicht mit Fahrzeug-Drilldown** — per provider a clickable row (`onclick="toggleVeh('p<i>')"`, ▸ marker): Provider | Fahrzeuge | Pakete | verpasst | Kosten | Ø Auslastung; beneath it hidden `tr.vehrow` rows (`data-drill="p<i>"`) from `data.vehicles` (role=freight, matching provider): Fahrzeug-ID (shortened: strip `freight_<carrier>_veh_` prefix) | Typ | km | h | Pakete | Stopps | LF % | „ausgeschlossen"-Flag.
3. **Low-Util-Hinweis** — a `.panel` note (only when `sum(excluded_vehicles) > 0`): "N Fahrzeuge mit Auslastung < 5 % ausgeschlossen; variable Kosten und verpasste Pakete wurden proportional reallokiert." with the per-provider counts inline.

- [ ] **Step 1: Write failing tests** — assert: VRP table has one row per provider + the header cells; drilldown rows carry `class="vehrow"` + `data-drill=`; notice text contains "reallokiert" and appears only when excluded>0.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** (reuse the existing `.kpis`/`.tablewrap` table CSS; drilldown JS/CSS already in render core from Task 4). Tables excluded in `compact=True`.
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/render_lmd.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render_lmd.py
git commit -m "feat(kpi): LMD tables - VRP efficiency, provider drilldown, low-util notice (v2 Plan C Task 9)"
```

---

## Task 10: Wiring — build_kpis render call, comparison per-run tabs, budgets, cleanup

**Files:**
- Modify: `analysis/kpi/build_kpis.py`, `analysis/kpi/build_comparison.py`, `analysis/kpi/render.py`
- Modify tests: `tests/test_render.py`, `tests/test_comparison.py`

- [ ] **Step 1: Write failing tests** — `test_comparison.py`: assert a per-run tab now contains an LMD provider chart canvas (`c_p_parcels_run0`) and NO distribution canvas (`c_wdist` absent) and NO drilldown rows; budget `< 3_000_000` unchanged. `test_render.py`: assert the built single-run page (drtrun fixture) contains both the DRT tabbar button and the KPI table, budget `< 2_000_000`.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.** `build_kpis.build`: replace `load_run_csvs`+`render_run_page(kpis_df, ts_df, ...)` with `data = render.load_run_data(out)` + `render.render_run_page(data, title=meta.run_id)`. `build_comparison.build_comparison`: per run `load_run_data(run analysis dir)`; `render_comparison_page(runs, title)` — each per-run tab = `render_drt.build_tab(data, uid, compact=True)` + `render_lmd.build_tab(data, uid, compact=True)` concatenated (only tabs whose data exists), comparison tab 0 unchanged. Remove the Task-4 try/except fallback and delete `render_run_sections` (its content now fully lives in the tab builders).
- [ ] **Step 4: Run the FULL suite** `python -u -m pytest tests/ -q` — everything green.
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/build_comparison.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/render.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_comparison.py
git commit -m "feat(kpi): tabbed run page wired into build + comparison compact tabs (v2 Plan C Task 10)"
```

---

## Task 11: Real married250 render + verification

**Files:** none new (verification evidence).

- [ ] **Step 1: Full build with events** (long — events parsing takes minutes): from `analysis/kpi/`:
```bash
python -u build_kpis.py --run-dir ../../hagrid-matsim-output/DRT_BASELINE_13052025_married250_iter300_jsprit1000
```
- [ ] **Step 2: Open `analysis/kpi_dashboard.html` in a browser (light + dark).** Checklist: both tabs present; DRT ~22 tiles incl. service-time/utilization values; occupancy chart shows time+segments rows; convergence charts have 300 iterations; LMD provider charts show 7 providers in stable colors; drilldown opens; hourly section shows only `freight_service_stops` (rest is Plan D); no console errors.
- [ ] **Step 3: Spot-check ≥ 6 numbers vs the legacy dashboards** (3 vs `drt_dashboard.html`: rides, wait P95, veh-km; 3 vs `HAGRID_Dashboard_*.html`: dhl parcels, dhl cost total, fleet utilization). Record the comparison in the commit message; deviations must match the documented ones (missedParcels scaling, events ~3 % low).
- [ ] **Step 4: Record file size** (expect well under 2 MB) in the commit message.
- [ ] **Step 5: Commit** (SESSION-note only if any fix was needed; else `git commit --allow-empty -m "chore(kpi): married250 v2 render verified vs legacy (v2 Plan C Task 11) - <numbers>"`).

---

## Self-Review (against spec §3.3/§4)

- **U1 tabs:** DRT|LMD one page, only tabs with data → Task 4. ✔
- **DRT sections 1–7:** tiles (T5), map hook (T5, filled by Plan D), daily profile incl. submitted-as-second-chart + feeder toggle (T6), distributions with markers (T6), occupancy 100%-stacked + modal stacked bar (T6), convergence (T6), per-vehicle service time (T6 via kpi_vehicles). ✔
- **LMD sections 1–9:** tiles (T7), map hook (T7), provider analytics incl. cost components + time split (T8), vehicle-type bars — no polar/radar (T8), tour histograms (T8), hourly incl. graceful-skip for Plan-D series (T8), scoring incl. final-iteration distribution + per-provider stacked-alternative bar (T2+T8), scatters (T8), three tables incl. drilldown + low-util notice with reallocation wording (T9). ✔
- **U4 full LMD depth** — every legacy chart family mapped; dropped-by-design items (link-volume) stay dropped. ✔
- **U6 design translation:** donuts→stacked bars, dual-axis→two charts, radar→bars; all color via slots/seq/ramp markers. ✔
- **Comparison** (§3.3): tab 0 untouched, per-run tabs = extended tiles + core charts, no maps/distributions, < 3 MB (T10). ✔
- **Constraints §4:** legacy untouched; frozen long-CSV untouched (new per-vehicle data = NEW file); ASCII prints; budgets asserted; graceful skips (`carrier_scores.txt` absent → no scoring convergence chart — guard exists in extract, render skips empty). ✔
- **Type consistency:** `RunData` fields (kpis/ts/provider/iterations/distributions/vehicles) used identically in Tasks 4–10; `build_tab(data, uid, compact, map_block)` identical in render_drt/render_lmd; `provider_slot`/`PROVIDER_SLOTS`/`OTHER_COLOR` defined once in render.py; kpi_vehicles column names match between Task 3 write and Task 6/8/9 consumers. ✔
- **Deferred, documented:** stem% VRP column, occ_km/drt_tour_distance data, freight hourly series data, maps → Plan D (render guards already in place). ✔
