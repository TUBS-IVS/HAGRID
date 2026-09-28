# HAGRID: shipping-day/transit mechanism, stochastic weekly layer and annual demand

Status: design for approval · 2026-09-25 · branch `codex/hagrid-baseline`

## 1. Goal

Replace the fixed delivery-day weekday profile (one profile for all carriers and segments) by a
mechanism in which every parcel gets a **shipping day** and a **transit time**, so that the delivery
day follows from them. A stochastic weekly layer at parcel level makes the weekly course vary from
week to week and from carrier to carrier. The model computes the **whole year** efficiently, stores it
compactly (no shapefile per day), and an **annual dashboard** lets the user pick days, weeks or months
from a calendar.

Decisions taken with the user:

- Mechanism now (no intermediate fixed profile), per-parcel randomness, differences per carrier.
- One documented **standard shipping profile per segment** (assumption, see §3.2).
- **Saturday delivery for all carriers** (no carrier-specific information available).
- Full-year computation; shapefiles only on demand; annual demand in the dashboard with a calendar.

Out of scope: recipient heterogeneity/memory at stop level, B2B multi-parcel shipments, correlated
spatial fields, pickups (outbound) as a separate flow, calibration against depot data.

## 2. Current state (for reference)

`calendar_weights` builds one normalised delivery-day weight per segment (ISO-week season × weekday
weight × holiday factor). Daily segment counts are Poisson (regime `expected_annual`) or multinomial
(`fixed_annual`), optionally with log-normal day shocks. Each day is allocated to sites (Dirichlet
between/within PLZ, multinomial) and then split into carriers with fixed segment shares. Therefore all
carriers share the weekday pattern of their segment, and both segments use the same profile.

## 3. Temporal model

### 3.1 Mechanism

For year `y`, segment `s ∈ {private, business}`, carrier `c` and calendar day `t`:

1. **Expected shipments** `λ[s,c,t] = A[s,c] · π_s(t)`, where `A[s,c]` is the annual expected
   delivery volume (sum of site targets × carrier share) and `π_s` is the normalised **shipping
   calendar**: season(ISO week) × shipping weekday weight × origin holiday factor (no shipping on public
   holidays; `holiday_region` NI is used for origin and destination).
2. **Transit**: each parcel draws an offset `k ∈ {1, 2, 3}` delivery days from the carrier kernel `τ_c`.
   The landing day is the k-th **delivery day** after `t` (Mon–Sat, not a public holiday).
3. **Saturday rule**: a parcel landing on a Saturday is delivered with probability
   `saturday_delivery[c]` (default 1.0 for all carriers) and, for business recipients, additionally
   `business_saturday_open` (default 0.2, KIT 2024: about one firm in five receives goods on
   Saturdays); otherwise it rolls to the next delivery day (normally Monday).
4. **Year wrap**: the convolution is cyclic within the year (shipments at the end of December land in
   early January of the same simulated year). Annual totals per segment and carrier are therefore
   conserved in expectation (exactly under `fixed_annual`).

Consequences that emerge without extra parameters: the Tuesday–Thursday delivery peak, low business
volume on Saturdays, catch-up peaks after public holidays (e.g. Tuesday after Easter Monday), and
carrier weekday profiles that differ through the carrier's B2B share.

The **expected delivery calendar** (deterministic convolution of `π_s` with the expected kernel and the
Saturday rule) is computed once per segment/carrier; it replaces `calendar_weight` in
`baseline_expected` and in all diagnostics.

### 3.2 Standard shipping profiles (assumptions, Mon…Sun)

| Segment | Mon | Tue | Wed | Thu | Fri | Sat | Sun | Source |
|---|---|---|---|---|---|---|---|---|
| business | 23 | 21 | 18 | 16 | 17 | 5 | 0 | LogIKTram 2023 (outbound firm parcels, read from figure) |
| private | 17.8 | 20.0 | 18.5 | 15.0 | 11.3 | 11.6 | 5.8 | derived: reproduces the notebook delivery profile 16/17/19/18/15/11.5 under the default kernel; Sat:Sun = 2:1 assumed (fulfilment centres ship at weekends) |

Resulting expected delivery profiles without holidays: private 16.6/17.6/19.7/18.7/15.5/11.9 %
(= notebook profile), business 20.3/20.5/20.9/18.5/16.4/3.4 %. The derivation is part of the code
(`derive_shipping_profile(target_delivery, kernel, weekend_split)`) and its numbers are stored with
provenance in `baseline/data/temporal_inputs.json`.

Default transit kernel for all carriers: E+1 85 %, E+2 13 %, E+3 2 % (assumption; to be replaced by the
BNetzA transit-time study value when available). Carrier-specific kernels are configurable.

### 3.3 Stochastic weekly layer

All draws use `named_rng` with dedicated channels, so results are reproducible and independent of
chunking.

| Level | Model | Default | Evidence |
|---|---|---|---|
| Week volume per segment | log-normal factor with AR(1) across ISO weeks, mean 1 | sd 0.016, ρ 0.5 | sd from the irregular component of the national weekly series 2019; ρ assumed |
| Week volume per carrier | independent log-normal factor, mean 1 | sd 0.02 | assumption |
| Weekday split per week and segment | Dirichlet(κ · p_week) around the week's shipping weights (holidays excluded) | κ = 1000 | assumption |
| Day shock (existing) | log-normal common/segment factors | 0 | unchanged |
| Parcel | shipping day, transit offset and Saturday rule drawn per parcel (multinomial/binomial on counts, equivalent to independent parcels) | – | – |

Shipment counts per week, segment and carrier are Poisson (`expected_annual`) with mean
`Σ_week λ · week factor · carrier factor`, then split to days with the week's Dirichlet shares; under
`fixed_annual` the annual total is multinomially distributed over (week, day) with the same factors.
Mean preservation: every factor has expectation 1; tests verify annual conservation.

### 3.4 Spatial allocation per carrier

Per delivery day and segment the existing spatial Dirichlet shares are drawn once and shared by all
carriers; each carrier's delivered count is then allocated with its own multinomial. This keeps the
spatial dispersion of today and makes carrier totals per day exact. The output frame schema of
`generate_days` stays unchanged (`baseline_expected` uses the expected delivery calendar,
`conditional_expected` the drawn shares).

The legacy delivery-calendar mode stays available (`temporal.mode = "delivery_calendar"`) to reproduce
old runs; the new mode `shipping_transit` becomes the default in the daily configs.

## 4. Annual computation and storage

`annual_store: true` simulates every day of the configured year(s) in one pass and writes:

| File (per run, `annual/`) | Content | Size (Region Hannover) |
|---|---|---|
| `plz_daily.parquet` | date, plz, segment, carrier, parcels (int32) | ≈ 0.3 M rows, few MB |
| `stop_daily.parquet` | day_of_year (int16), stop index (int32), 14 count columns `<carrier>_<segment>` (uint16), only active stop-days | ≈ 15 M rows, target ≤ 200 MB (zstd) |
| `days.parquet` | per date: totals per segment/carrier, active stops, parcels per stop (mean/median/p90), holiday/weekday flags, week factors | 365 rows |
| `annual_summary.json` | weekday profiles per carrier and segment, weekly and monthly totals, notebook-comparable totals | small |

Efficiency: the year loop keeps everything in NumPy arrays (no per-day site×carrier DataFrames),
aggregates sites to stops and PLZ with `np.bincount`, and appends one row group per day with
`pyarrow.ParquetWriter`. Target runtime for the full year < 15 minutes on the development machine.

MATSim shapefiles are written only for `dates` listed in the config (unchanged behaviour and format),
and on demand for any stored day:
`python -m hagrid_demand baseline export-day --run <run_dir> --date YYYY-MM-DD` (reads `stop_daily`,
reuses the existing writer, row split > 400 and the manifest).

## 5. Report

`report.md` gains a section "Weekday profile by carrier and segment" (share Mon…Sat per carrier and
segment, realised vs expected) and, for annual runs, weekly totals and the holiday catch-up days.

## 6. Annual dashboard

Built from the annual store by `hagrid_demand.baseline.annual_dashboard` (data builder + HTML
template in the package) and published as a claude.ai artifact.

- **Style** (ui-ux-pro-max design system): data-dense dashboard, light and dark; Fira Sans for UI and
  body text, Fira Code for figures and codes; carrier colours are the CVD-validated palette of the
  current dashboard (Amazon orange, Hermes aqua, DHL yellow, UPS magenta, GLS green, FedEx/TNT violet,
  DPD red; blue reserved for volumes).
- **Calendar as primary navigation**: a year heatmap (weeks × weekdays, colour = parcels per day,
  public holidays marked). Click a day → day; click a week label → ISO week; month buttons → month;
  "Year" → whole year. Keyboard navigation (arrow keys, Enter), previous/next buttons, visible focus;
  the selection is kept in `localStorage` and a bare `#` anchor.
- **Views for the selected period**: KPI row (parcels, per day, B2B share, stops per day, parcels per
  stop, per 1,000 residents); daily time series of the year with the selection highlighted (stacked by
  carrier, switchable to lines); weekday profile per carrier and segment (grouped bars); carrier cards;
  PLZ choropleth with tooltip and profile panel; sortable tables as accessible alternatives.
- **Data packaging**: PLZ × day × carrier × segment and the day table are embedded (≈ 2 MB). A 500 m
  grid per ISO week is optional and, if included, published as separate weekly JSON files loaded on
  demand.

## 7. Configuration

```json
"temporal": {
  "mode": "shipping_transit",
  "shipping_weekday_weights": {"private": "standard", "business": "standard"},
  "transit_days": {"default": [0.85, 0.13, 0.02]},
  "saturday_delivery": {"default": 1.0},
  "business_saturday_open": 0.2,
  "week_log_sd": 0.016, "week_ar": 0.5,
  "carrier_week_log_sd": 0.02,
  "weekday_concentration": 1000
},
"annual_store": true
```

`"standard"` resolves to §3.2; explicit seven-value lists override it. Validation rejects negative or
non-finite values, kernels that do not sum to 1, and profiles without positive support.

## 8. Acceptance criteria

1. Expected delivery calendars conserve annual totals per segment and carrier (relative error < 1e-9).
2. Without holidays the expected private delivery profile equals the notebook profile (±0.1 pp); the
   business Saturday share is 3.4 % (±0.2 pp).
3. The day after a public holiday has a higher expected volume than the same weekday without holiday.
4. Same seed → identical results; different seeds → week-to-week CV of weekly totals ≈ configured sd
   (± sampling error) in a Monte Carlo test.
5. Full-year acceptance run (2025): runtime and store size within the targets; MATSim export for the
   known 8 dates unchanged in format; notebook comparison still produced.
6. Annual dashboard renders the whole year, selections update all views, light/dark and 375 px width
   work, no console errors.

## 9. Testing

Unit tests for profile derivation, kernel/landing-day logic (weekends, holidays, Saturday rule),
expected convolution and conservation, stochastic factors (mean 1, AR structure), per-carrier
allocation, annual writer and `export-day` round trip (shapefile equals the direct export for the same
date), config validation. Existing tests keep passing in legacy mode.
