# Run-Dashboard v2 — Plan D (Visual Polish, Round 1: render-layer)

Origin: user visual re-check of the married250 dashboard (2026-07-16), 11 items.
Split by depth: **9 render-layer items = this plan (Tasks D1–D3)**; 2 data-layer
items (#2 occ_km network-km reconstruction, #7 delivered-parcels-per-hour
demand-join) = Round 2, tackled right after in-session (own plan section / tasks).

Branch: hendrik. Baseline (pre-D1 BASE): 4bfccb2 (Plan C Task 12).
Execution: subagent-driven, per-task LOCAL commits (NOT pushed/merged unless asked).

## Global Constraints (reviewer attention lens — copy verbatim into reviewer prompts)

- **Render-layer only.** No extractor/data-schema changes in D1–D3. The CSVs are
  unchanged; only render.py / render_drt.py / render_lmd.py + their tests move.
- **Pie/Donut is ALLOWED for exactly two charts** — DRT Modal Split and LMD
  Flottenzusammensetzung (S/M/L) — per explicit user decision 2026-07-16 ("beide als
  Ring/Donut"), overriding the dataviz no-pie rule. Everywhere else the no-pie rule
  still stands. This is a plan-mandated override; do NOT flag these two donuts as a
  no-pie violation.
- **Donuts must still obey the rest of dataviz:** legend always present (≥2 series);
  fixed categorical color slots (color follows entity — MODE_SLOTS for modal, the
  size ramp for fleet); a 2px surface gap between segments (borderColor = surface,
  borderWidth 2); hover tooltip with value + %; a center total label; direct segment
  labels where they fit. Doughnut (with hole) not full pie, so the hole carries the total.
- **One axis — no dual-axis.** The combined DRT requests chart plots served
  ("bediente Abfahrten", bar) and submitted ("Anfragen", line) on ONE shared y-axis;
  both are requests/h, same scale. Never a second y-scale.
- **Size colors (S/M/L)** = one distinct sequential hue ramp (teal family), light→dark
  by size, perceptually distinct from ALL 8 provider CAT slots AND from `--seq` blue.
  The SAME three colors are used by the vtype bars (charts 10–12) and the fleet donut.
- **All `%` in embedded JS must be written `%%`** — JS_SETUP and JS_RESOLVE are
  applied through Python `%`-formatting (see render.py:289-290). A bare `%` corrupts
  the page (Plan C Task 8 regression).
- German number formatting preserved (`_fmt_de`/`_fmt_pct`); light + dark theme both
  styled (no automatic flip — dark steps from the same ramps).
- Budgets unchanged: run page < 2 MB (currently 0.28 MB), comparison < 3 MB.
- TDD: failing tests first; full `python -u -m pytest tests/ -q` green before commit.
- No non-ASCII in `print()` (Windows cp1252). Per-task local commit on hendrik.

## Tasks

### Task D1 — render.py core: full-width layout, vline legibility, shared donut helper, size ramp
Files: analysis/kpi/render.py (+ tests/test_render.py)
- **#11 full width:** `.wrap { max-width: 1240px }` → use near-full 16:9 width like
  legacy LMD, e.g. `max-width: min(1680px, 96vw)` (centered, keep padding). The grid2
  auto-fit already spreads once the wrap is wider.
- **#6 vline legibility:** the vlinePlugin Median/Ø/P95 labels are hard to read (drawn
  in faint `--ink2`, can overlap). Draw each label in high-contrast `--ink` on a small
  filled rounded chip (surface fill + subtle border), and stagger the three vertical
  offsets so labels never overlap. Keep the dashed line in `--axis`, keep id `'vlines'`.
- **shared `_donut(...)` helper:** Chart.js `type:"doughnut"`; legend bottom, always on;
  `borderColor` = surface + `borderWidth: 2` (segment gap); hover tooltip value + %;
  center total via a small afterDraw plugin (registered like vlinePlugin) OR title;
  colors via a marker (`__slots` for modal MODE_SLOTS, the size marker for fleet).
  Direct segment-% labels where they fit. Returns a cfg consumed by `chart_js`.
- **size ramp:** add `--size` CSS var (light + dark) in a hue distinct from `--seq`
  and all 8 CAT provider hues (teal family); JS `alphaSize(a)` mirroring `alphaSeq`;
  extend `resolveColors` with a size marker (e.g. `__sizes:[idx,...]`) → distinct
  shades ordered by index. Expose whatever render_lmd (D3) needs so the vtype bars and
  the fleet donut share identical size colors. Remember `%`→`%%` in all new JS.
- Tests: donut helper emits `type:"doughnut"` + bottom legend + borderWidth 2; size
  marker resolves to ≥3 distinct colors, none equal to CAT[0]/`--seq`; wrap max-width
  changed off 1240; vline chip/stagger present. Full suite green.

### Task D2 — render_drt.py: 0–23 hours, modal donut, combined requests chart, wording
Files: analysis/kpi/render_drt.py (+ tests/test_render_drt.py)
- **#1 0–23:** every Tagesverlauf chart (rides, requests, rejections, wait, feeder)
  spans hours 0..23; missing hours filled with 0; x-axis fixed to 24 labels. Add a
  helper `_hours_0_23(ts, series)` → (labels 0..23, 0-filled values); apply to all
  hourly charts incl. the feeder toggle.
- **#3 modal donut:** replace `_modal_chart` (horizontal stacked bar) with the core
  `_donut`; MODE_SLOTS colors via `__slots`; readable German mode labels; center total.
  Still present in compact mode.
- **#4 + #5 combined requests chart:** merge `drt_rides` (served) + `drt_requests_submitted`
  (submitted) into ONE chart on ONE y-axis (requests/h): served = **bar** labelled
  "bediente Abfahrten", submitted = **line** labelled "Anfragen". Title "Anfragen &
  bediente Abfahrten je Stunde" (legacy wording). Drop the standalone submitted chart;
  "eingereicht" must be gone. Legend on (2 series). Rejections + wait stay (but 0–23).
- Tests: combined chart has one bar + one line dataset sharing a single y scale (no 2nd
  yAxisID); "bediente Abfahrten"/"Anfragen" present, "eingereicht" absent; modal chart
  is `type:"doughnut"`; every hourly chart has 24 labels 0..23. Full suite green.

### Task D3 — render_lmd.py: fleet donut, S/M/L colors, provider overview moved up
Files: analysis/kpi/render_lmd.py (+ tests/test_render_lmd.py)
- **#8 fleet donut:** new chart from `_vtypes` (vehicle counts per ct_cep_size_s/m/l) →
  core `_donut`, size colors, center total = total vehicles, labels "S (Kap. 100)" etc.
  Place at the top of the Fahrzeugtyp-Analytik section ("auf einen Blick").
- **#9 S/M/L colors:** vtype bars (charts 10–12) switch from `__seq` (currently
  `#2a78d6` = provider dhl blue) to the D1 size ramp — 3 distinct shades ordered by
  size, distinct hue from providers. Fleet donut uses the SAME colors (consistency).
- **#10 provider overview up:** the Task-9 "Provider-Übersicht mit Fahrzeug-Drilldown"
  table (and the VRP-Effizienz table) currently render dead-last, after all 23 charts.
  Move the provider tables up — directly after the provider tiles / Provider-Analytik
  charts, before the Fahrzeugtyp-Analytik section — so the provider overview is near
  the top. (Charts already order provider before vehicle-type; the *tables* are what's
  buried.) Keep tiles first. Drilldown JS/CSS + toggleVeh keys unchanged.
- Tests: fleet donut `type:"doughnut"` with 3 size segments; vtype bars use the size
  marker, not `__seq`; fleet-donut colors == vtype-bar size colors; the Provider-Übersicht
  table appears in html BEFORE the Fahrzeugtyp-Analytik section; tiles still read broad
  `type:` rows (unaffected). Full suite green.

## Round 2 (data-layer, after D1–D3, own briefs)
- **#2 occ_km:** port legacy `dist_by_occ` (build_drt_dashboard.py) — fleet driven km per
  occupancy level, needs network km per segment. Emits the `occ_km` distribution series
  `_occ_chart` already renders. Needs the reconstruct()/network path Plan A deferred.
- **#7 delivered parcels/h:** join freight service actstart events (events_cache, time
  only) to carrier-plan service demand → parcels-per-hour series feeding the existing
  `_hourly_provider_stack("freight_parcels_h_", …)`.
