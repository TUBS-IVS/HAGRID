# Shipping/transit mechanism, weekly layer and annual demand — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delivery days follow from per-parcel shipping days and transit times with a stochastic weekly layer; the whole year is simulated once, stored compactly, exportable per day, and shown in an annual calendar dashboard.

**Architecture:** A pure temporal module (`shipping.py`: profiles, delivery calendar, landing days, expected convolution) and a draw module (`shipping_draws.py`: week/carrier factors, weekday Dirichlet, per-parcel shipping/transit/Saturday draws) produce delivered counts per (segment, carrier, day). `allocation.py` allocates each carrier's daily count to sites with the shared spatial Dirichlet shares. One pass over all days feeds the annual store (`annual.py`) and, for configured dates, the existing detail/MATSim consumers. `annual_dashboard.py` turns the store into an artifact page.

**Tech Stack:** Python ≥ 3.11, NumPy, pandas, geopandas, pyarrow, pytest; HTML/SVG/vanilla JS for the dashboard.

**Spec:** `docs/superpowers/specs/2026-09-25-hagrid-shipping-week-annual-design.md`

**Compactness ruling (user is token-constrained):** steps give exact interfaces, test cases and commands; full code only where the logic is subtle. Executor: native, in this session.

## Global Constraints

- No new dependencies; RNG via `named_rng` with new channel names only; `RNG_VERSION` unchanged.
- Without a `temporal` block (or `temporal.mode = "delivery_calendar"`) behaviour is byte-identical to today; the daily configs switch to `shipping_transit`.
- MATSim shapefile names, fields, row split (> 400) and manifest stay unchanged.
- Code comments, docstrings and report text in English.
- Defaults (spec §3.2/§3.3/§7): business shipping `[0.23, 0.21, 0.18, 0.16, 0.17, 0.05, 0.0]`; private shipping derived from `[0.16, 0.17, 0.19, 0.18, 0.15, 0.115, 0.0]` with kernel `[0.85, 0.13, 0.02]` and Sat:Sun 2:1; `saturday_delivery` 1.0 for all; `business_saturday_open` 0.2; `week_log_sd` 0.016, `week_ar` 0.5, `carrier_week_log_sd` 0.02, `weekday_concentration` 1000.
- Test command: `cd hagrid-demand && PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=src python -m pytest -q -p no:cacheprovider` (242 passing before this plan).

## Review Focus

1. Year boundary: shipments on Dec 29–31 must land in January of the same simulated year (cyclic), never be dropped — conservation test with Jan 1 holiday (Task 1).
2. Saturday roll into a Monday holiday (Easter Monday) must continue to Tuesday — test (Task 1).
3. A carrier/segment target of 0 (or a segment without sites) must yield zero deliveries without Dirichlet/multinomial errors — test (Task 2, Task 3).
4. A configured date that is a Sunday or holiday yields an empty day: direct export and `export-day` both return a ledger without file — test (Task 5).
5. Years with ISO week 53 (2026) and leap years (2028) must index week factors correctly — test (Task 2).

---

### Task 1: Temporal core (`shipping.py`)

**Files:** Create `hagrid-demand/src/hagrid_demand/baseline/shipping.py`, `hagrid-demand/src/hagrid_demand/baseline/data/temporal_inputs.json`; Test `hagrid-demand/tests/test_shipping.py`.

**Interfaces (Produces):**
- `load_temporal_inputs() -> dict` — JSON with `shipping_weekday_weights.business` (+ source), `delivery_target_private`, `transit_days_default`, `business_saturday_open`, `weekend_split`, provenance strings.
- `derive_shipping_profile(target_delivery, kernel, weekend_split=(2/3, 1/3)) -> np.ndarray` (7, Mon..Sun, sums to 1).
- `resolve_temporal(cfg: dict | None) -> dict | None` — `None` for legacy; else dict with numpy arrays `shipping[segment]` (7), `kernel(carrier) -> np.ndarray(3)`, `saturday(carrier) -> float`, `business_saturday_open`, `week_log_sd`, `week_ar`, `carrier_week_log_sd`, `weekday_concentration`. `"standard"` resolves to the JSON/derived profiles. Raises `ValueError` on negative/non-finite values, kernels not summing to 1 (tol 1e-9), profiles without support, unknown keys.
- `@dataclass(frozen=True) DeliveryCalendar(dates: pd.DatetimeIndex, delivery: np.ndarray, weekday: np.ndarray, next_delivery: np.ndarray, next_weekday_delivery: np.ndarray)` built by `delivery_calendar(year: int, holidays: Iterable[str]) -> DeliveryCalendar`; indices are cyclic within the year; delivery = Mon–Sat and not holiday; `next_weekday_delivery` = next Mon–Fri non-holiday.
- `landing(cal, k: int) -> np.ndarray` — index of the k-th delivery day strictly after each day.
- `expected_delivery(shipping_weights: np.ndarray, cal, kernel, accept: float) -> np.ndarray` — cyclic; Saturday landings keep `accept`, the rest moves to `next_weekday_delivery`.
- `shipping_weights(year, segment, weekly, temporal, calendar_cfg) -> np.ndarray` — `calendar_weights` with the shipping weekday profile and `holiday_factor = 0` at origin; normalised over the year.

Subtle code (landing and convolution):

```python
def landing(cal: DeliveryCalendar, k: int) -> np.ndarray:
    index = np.arange(len(cal.dates))
    for _ in range(k):
        index = cal.next_delivery[index]
    return index

def expected_delivery(shipping_weights, cal, kernel, accept):
    result = np.zeros(len(cal.dates))
    saturday = cal.weekday == 5
    for k, probability in enumerate(kernel, start=1):
        target = landing(cal, k)
        mass = shipping_weights * probability
        on_saturday = saturday[target]
        np.add.at(result, target[~on_saturday], mass[~on_saturday])
        np.add.at(result, target[on_saturday], accept * mass[on_saturday])
        np.add.at(result, cal.next_weekday_delivery[target[on_saturday]], (1 - accept) * mass[on_saturday])
    return result
```

- [ ] **Step 1: Write failing tests** in `tests/test_shipping.py`:
  - `test_derived_private_profile_reproduces_delivery_target` — `derive_shipping_profile([.16,.17,.19,.18,.15,.115,0], [.85,.13,.02])` rounded to 3 decimals equals `[0.178, 0.200, 0.185, 0.150, 0.113, 0.116, 0.058]`; convolving it on a holiday-free synthetic year gives weekday shares within 0.001 of the normalised target.
  - `test_landing_skips_sunday_and_holidays` — 2025: Fri 2025-05-16 k=1 → Sat 05-17; Sat 05-17 k=1 → Mon 05-19; Thu 2025-04-17 k=1 → Sat 04-19 (Good Friday closed); Sat 2025-04-19 k=1 → Tue 04-22 (Easter Monday closed).
  - `test_business_saturday_roll_skips_holiday` — expected delivery with `accept=0.2` moves 80 % of Saturday 2025-04-19 mass to Tuesday 04-22.
  - `test_expected_delivery_conserves_mass_across_year_end` — random positive weights over 2025 (Jan 1, Dec 25/26 holidays) keep their sum (rel. error < 1e-12) and put mass shipped on Dec 31 into January.
  - `test_resolve_temporal_defaults_and_errors` — `resolve_temporal(None) is None`; `{"mode": "shipping_transit"}` yields the defaults; kernel `[0.5, 0.4]` raises; negative weight raises; `{"mode": "x"}` raises.
- [ ] **Step 2: Run** `... pytest tests/test_shipping.py -q` → FAIL (module missing).
- [ ] **Step 3: Implement** `shipping.py` and `temporal_inputs.json` (business profile + provenance "LogIKTram 2023, Fig. 5, read off"; private target + provenance "notebook ParcelDemandScenarioGenerator"; kernel provenance "assumption"; Saturday open "KIT 2024").
- [ ] **Step 4: Run** the file → PASS; run the full suite → green.
- [ ] **Step 5: Commit** `feat: add shipping-day and transit calendar core`.

### Task 2: Stochastic weekly layer and delivered counts (`shipping_draws.py`)

**Files:** Create `hagrid-demand/src/hagrid_demand/baseline/shipping_draws.py`; Test `hagrid-demand/tests/test_shipping_draws.py`.

**Interfaces:**
- Consumes Task 1: `DeliveryCalendar`, `landing`, `resolve_temporal` output.
- Produces:
  - `week_index(cal) -> np.ndarray` (0-based running week number per day, ISO weeks incl. week 53; days before the first Monday belong to week 0).
  - `ar1_lognormal(n: int, sd: float, rho: float, rng) -> np.ndarray` (mean 1 per element: `exp(sd·z − sd²/2)`, stationary AR(1) z).
  - `expected_deliveries(targets: dict[tuple[str, str], float], shipping: dict[str, np.ndarray], cal, temporal) -> dict[tuple[str, str], np.ndarray]`.
  - `simulate_deliveries(targets, shipping, cal, temporal, *, seed: int, year: int, regime: str, outer_id: int = 0, inner_id: int = 0) -> dict[tuple[str, str], np.ndarray]` (int64 per day). Draw order per segment: week factor (channel `shipping-week-factor`), weekday Dirichlet per week over days with positive shipping weight (`shipping-weekday-split`), per carrier: carrier week factor (`shipping-carrier-week`), shipments (Poisson per day for `expected_annual`, one multinomial over the year for `fixed_annual`; `shipping-counts`), transit multinomial per day (`shipping-transit`), Saturday binomial (`shipping-saturday`). Zero targets return zero arrays without drawing.
- [ ] **Step 1: Write failing tests:**
  - `test_simulation_is_reproducible` — same seed → identical dict; different seed → different.
  - `test_simulation_conserves_expectation` — 200 seeds, target 50,000 per (segment, carrier), mean annual delivered within 0.5 % of target; `fixed_annual` conserves exactly.
  - `test_weekly_cv_matches_configured_sd` — Poisson noise negligible (target 5e7), sd 0.05, ρ 0: CV of weekly totals within [0.04, 0.06].
  - `test_business_saturday_share_follows_open_rate` — expected business deliveries on Saturdays ≈ 0.2 × Saturday landings (±1e-9) and realised share close (±0.5 pp).
  - `test_zero_target_yields_zero` — target 0 → all zeros, no exception.
  - `test_iso_week_53_and_leap_year` — 2026 (53 ISO weeks) and 2028 (366 days) run; `week_index` max equals the number of distinct weeks − 1.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** file + full suite → PASS/green.
- [ ] **Step 5: Commit** `feat: add stochastic weekly layer with per-parcel transit draws`.

### Task 3: Per-carrier daily allocation (`allocation.py`)

**Files:** Modify `hagrid-demand/src/hagrid_demand/baseline/allocation.py`; Test `hagrid-demand/tests/test_delivery_allocation.py`.

**Interfaces:**
- Consumes Task 2 dicts `delivered`, `expected` keyed `(segment, carrier)` with arrays over `cal.dates`.
- Produces:
  - `@dataclass SegmentDay(sites: pd.DataFrame, carriers: list[str], counts: np.ndarray, shares: np.ndarray, delivered: np.ndarray)` — `counts` shape (n_sites, n_carriers).
  - `draw_delivery_days(annual, profiles, delivered, dates: pd.DatetimeIndex, cfg, outer_id, inner_id, *, spatial_plan, coupling_id=None) -> Iterator[tuple[pd.Timestamp, dict[str, SegmentDay]]]` — every date of the year; spatial shares drawn with the existing channel `spatial-dirichlet`; each carrier `rng.multinomial(delivered[s, c][day], shares)` with channel `carrier-sites`.
  - `delivery_frame(date, segment_days, expected, cal_index: int, year, outer_id, inner_id) -> pd.DataFrame` — same columns as `generate_days` frames; `baseline_expected = annual_expected × carrier_share × expected[s, c][day] / A[s, c]`, `conditional_expected = delivered × shares`, `daily_count` = segment total.
- [ ] **Step 1: Write failing tests** (small synthetic annual table: 2 PLZ, 5 sites per segment, 2 carriers):
  - `test_carrier_totals_equal_delivered` — per day and carrier, `counts.sum(axis=0) == delivered`.
  - `test_frame_schema_matches_legacy` — `delivery_frame` columns equal those of a `generate_days` frame.
  - `test_zero_delivery_day_and_empty_segment` — a day with 0 delivered and a segment without sites produce no error and zero counts.
  - `test_draws_are_deterministic` — two runs identical.
- [ ] **Step 2–4:** Run → FAIL; implement; run file + full suite → green.
- [ ] **Step 5: Commit** `feat: allocate per-carrier daily deliveries to sites`.

### Task 4: Workflow and configuration

**Files:** Modify `hagrid-demand/src/hagrid_demand/baseline/config.py` (`_ALLOWED_KEYS` += `temporal`, `annual_store`), `hagrid-demand/src/hagrid_demand/baseline/workflow.py` (`_write_daily`), `hagrid-demand/src/hagrid_demand/baseline/dashboard.py` (report section), `hagrid-demand/configs/baseline-daily.json`, `hagrid-demand/README.md`; Test `hagrid-demand/tests/test_street_workflow.py` (new test).

**Behaviour:** `_write_daily` resolves `temporal`; legacy path unchanged. In `shipping_transit` mode it builds `DeliveryCalendar` per year, shipping weights per segment, targets `A[s, c]` from `projection.sites` × `projection.profiles`, `expected`/`delivered` dicts, then iterates `draw_delivery_days` once over all dates: selected `dates` become frames via `delivery_frame` and flow into the existing MATSim/aggregate consumers (a generator); every day goes to the annual writer if `annual_store` (Task 5). Writes `delivery_calendar.parquet` (date, segment, carrier, expected, delivered) and adds `temporal` metadata to `daily_status.json`. The report gets "## Weekday profile by carrier and segment" (expected and realised Mon…Sat shares per carrier and segment).

- [ ] **Step 1: Write failing test** `test_shipping_transit_daily_run` — street fixture run with `temporal.mode = shipping_transit`, two dates (Fri, Sat): `daily_status.json.temporal.mode == "shipping_transit"`; `delivery_calendar.parquet` conserves annual targets per (segment, carrier) within 1e-6 in `expected`; business share on the Saturday < business share on the Friday; report contains "Weekday profile by carrier and segment".
- [ ] **Step 2–4:** FAIL → implement → PASS; full suite green (legacy tests untouched).
- [ ] **Step 5: Commit** `feat: wire shipping-transit mode into the daily run`.

### Task 5: Annual store and on-demand day export

**Files:** Create `hagrid-demand/src/hagrid_demand/baseline/annual.py`; Modify `hagrid-demand/src/hagrid_demand/compatibility/matsim_export.py` (extract `stop_table_frame(grouped, stops, ledger, limit)` from `_stop_frame`), `hagrid-demand/src/hagrid_demand/cli.py` (`baseline export-day --run --date [--out]`), `workflow.py`; Test `hagrid-demand/tests/test_annual_store.py`.

**Interfaces:**
- `AnnualStoreWriter(output_dir: Path, stops: pd.DataFrame, site_stops: pd.DataFrame, carriers: list[str])` with `add_day(date, segment_days) -> dict` and `close(extra: dict) -> dict`. Writes `annual/stop_daily.parquet` (`day` int16 = day of year − 1, `stop` int32 = stop_index, columns `<short>_b2c`/`<short>_b2b` uint16 using `CARRIER_FIELDS` short names, only active stops; one row group per day, zstd), `annual/plz_daily.parquet` (date, plz, segment, carrier, parcels int32), `annual/days.parquet` (date, weekday, holiday, parcels, b2c, b2b, per-carrier totals, stops_active, sites_active, parcels_per_stop mean/median/p90/max) and `annual/annual_summary.json` (weekday profiles per carrier×segment, weekly and monthly totals, file sizes, runtime).
- `export_day(run_dir: Path, date: str, output_dir: Path | None = None, max_parcels_per_row: int = 400) -> dict` — reads the day's rows (pyarrow filter on `day`), maps store columns to Java/notebook fields, calls `stop_table_frame`, writes the shapefile via the existing naming; empty day → ledger without file.
- [ ] **Step 1: Write failing tests:**
  - `test_store_totals_match_days` — fixture run with `annual_store: true`: `stop_daily` sums per day equal `days.parquet` parcels; `plz_daily` sums equal too.
  - `test_export_day_equals_direct_export` — for a configured date the shapefile from `export_day` equals the directly exported one (same ids and count columns).
  - `test_export_day_empty_sunday` — a Sunday returns a ledger with `file is None`, direct export behaves the same.
- [ ] **Step 2–4:** FAIL → implement → PASS; full suite green.
- [ ] **Step 5: Commit** `feat: store the whole year compactly and export any day on demand`.

### Task 6: Annual dashboard

**Files:** Create `hagrid-demand/src/hagrid_demand/baseline/annual_dashboard.py` and `hagrid-demand/src/hagrid_demand/baseline/annual_dashboard.html`; CLI `baseline annual-dashboard --run --out`; Test `hagrid-demand/tests/test_annual_dashboard.py`.

**Data builder** `build_annual_dashboard_data(run_dir: Path) -> dict` with keys `meta` (run id, year, persons, firms, stops, carriers, holidays), `days` (column arrays from `days.parquet`), `plz` (codes, names, persons, firms, area), `plz_daily` (int array day × plz × 14, flattened), `profiles` (carrier market shares, B2B shares), `weekday` (expected/realised profiles), `geo` (PLZ GeoJSON, `coverage_simplify` 20 m, WGS84, 5 decimals), `temporal` (settings + provenance). `write_annual_dashboard(run_dir, out_html)` injects the JSON (escaped `</`) into the template.

**Page** (ui-ux-pro-max: data-dense dashboard; Fira Sans/Fira Code; validated carrier palette; light/dark tokens; 16 px gutters; ≥ 44 px touch targets for controls): year calendar heatmap (click day / week label / month button / "Year"; arrow keys; holidays outlined); KPI row; daily time series of the year stacked by carrier with the selection band; weekday profile per carrier and segment (grouped bars); carrier cards; PLZ choropleth with tooltip and profile panel; sortable PLZ and carrier tables; notes on assumptions. Selection persists in `localStorage` (try/catch).

- [ ] **Step 1: Write failing tests:** `test_dashboard_data_consistent` (sum of `plz_daily` per day equals `days.parcels`; 14 columns; geo has one feature per PLZ) and `test_dashboard_html_written` (placeholder replaced, no raw `</script` in the JSON).
- [ ] **Step 2–4:** FAIL → implement → PASS; `node -e` syntax check of the page script.
- [ ] **Step 5: Commit** `feat: annual calendar dashboard`.

### Task 7: Acceptance run and publication

- [ ] **Step 1:** Config `accept-year` (copy of `baseline-daily.json` with `annual_store: true`, the 8 known dates, `notebook_output_dir`) → run `python -m hagrid_demand baseline run --config <cfg> --run-id street-2025-year`; record runtime and store size (targets: < 15 min, ≤ 200 MB).
- [ ] **Step 2:** Check spec §8: expected private profile vs notebook (±0.1 pp), business Saturday 3.4 % (±0.2 pp), catch-up day after Easter Monday, notebook comparison present, MATSim files for the 8 dates.
- [ ] **Step 3:** Build the dashboard, look once (preview), fix, publish as artifact "Hannover Parcel Year".
- [ ] **Step 4:** README section "Shipping days, transit and the annual store" (commands `run`, `export-day`, `annual-dashboard`; defaults and their sources). Commit `docs: annual acceptance run and README`.
