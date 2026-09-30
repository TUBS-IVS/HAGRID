# HAGRID Demand Model (`hagrid_demand`)

The Python package `hagrid_demand` implements the demand side of HAGRID: the core stages of the
[master plan](../../../docs/demand/demand-audit/MASTERPLAN.md), from the data foundation through the joint demand and
carrier estimation to the calendar, spatio-temporal variation, future paths and the delivery and GIS exports. The main
workflow is the `baseline` command. It calibrates the reference year 2021 and simulates every day of 2025–2035 for Region
Hannover. **The calibration is preliminary and depends on documented assumptions.**

- [Joint model: description, inputs, outputs and check limits](MODEL_WORKFLOW.md) (in German)
- [Verified foundation run and results](VALIDATION.md) (in German)
- Specifications and implementation plans: [`docs/demand/`](../../../docs/demand/)

## Quick Start

From this directory, with Python 3.11 or newer:

```powershell
python -m pip install -e ".[test]"
python -m pytest -q
python -m hagrid_demand baseline run --config configs/baseline-daily.json --run-id daily-2025-05
```

Relative input and output paths resolve against the configuration file, not against the working directory. A run with an
existing name needs `--resume`, which rejects changed configurations or input sources. The launch scripts
`runs\hannover\run_demand_year.bat` and `runs\hannover\run_demand_decade.bat` in the repository root wrap the year and the
decade runs.

## The `baseline` Workflow

### Reference Year 2021

With `output_scope: "reference"`, the baseline command builds only the documented reference for 2021. It reads local raw
sources, writes checked intermediate artefacts into a content-addressed cache and copies the artefacts it used into the run.
The configuration needs `source_mode: "raw"`, the foundation-compatible sources H01/H02/H03/H04/H06, `weekly_source`, CRS
settings, `reference_year`, `reference_operating_days` and `output_scope: "reference"`.

```powershell
python -m hagrid_demand baseline run --config configs/baseline-reference.json --run-id reference-2021
python -m hagrid_demand baseline report --run-dir runs/reference-2021
```

All reference runs update the same offline entry page `<output_dir>/dashboard/index.html`; the run-specific volumes, scope
balances and B2B adjustments stay in `<run>/report_data.json`. `output_scope: "daily"` adds the daily run described below.
Earlier model, OSM and spatial experiments live in `hagrid_demand.experimental`; their old import paths remain as
forwards, so existing commands and scripts keep working.

### Street Anchor, OSM Buildings and Stops

With `osm_buildings` and `osm_points`, the reference runs in street mode (`anchor.mode: street`, specification
[`2026-09-24-hagrid-street-anchor-buildings-design.md`](../../../docs/demand/specs/2026-09-24-hagrid-street-anchor-buildings-design.md)):

1. **Buildings** (`<run>/buildings/`): person building points go to OSM buildings (state of 1 January 2021), firms to
   matching buildings within their 100 m census cell (area × industry fit). Every building gets its street from the LSP data (street name
   and postcode, else the nearest street within 100 m, then within 250 m), its 50 m section and its street side.
2. **Anchor** (`reference_anchor.json`, `reference_streets.parquet`): a level correction per postcode via residential
   streets (only for postcodes with an extreme LSP level; 2021: 30855), LSP rates per resident and per firm, the split of
   every LSP street into B2C and B2B, the B2B share of the LSP from these data and the extrapolation to all carriers. Streets with
   LSP volume but no building get synthetic points; buildings without a street and LSP gaps get the structural model.
3. **Stops** (`reference_stops.parquet`): buildings on the same street side within 2 × 40 m form one stop, large receivers
   (≥ 15 parcels a day) a stop of their own. The MATSim export writes one row per stop (`id`, `stop_id`, `str_idx`,
   `section_id`) and splits rows above 400 parcels. With `notebook_output_dir`, the run compares every day with the file of
   the same name from the notebook generator (volume, B2B, postcode correlation; `matsim_export.json` and `report.md`).

The OSM files are built once from the Geofabrik extract (© OpenStreetMap contributors, ODbL):

```powershell
python -m hagrid_demand baseline osm-clip --pbf ../input/hannover/osm/niedersachsen-210101.osm.pbf --plz ../input/hannover/raw/plz_region_hannover.csv --out ../input/hannover/osm
```

Acceptance run of 24 September 2026 (the eight days of the notebook generator, 6 min): q_LSP 0.254; regional volume 2021
of 60.3 million parcels on 306 delivery days; 99.5 % of the persons in buildings; 98 % of the volume directly from LSP
streets, 0.6 % structural fallback; a median of 51 parcels per resident and year; daily volumes 4–5 % below the notebook
(level correction of 30855); postcode correlation without 30855 0.97–0.98; 44,000–51,000 stops per day with a median of
2–3 parcels.

### Daily Run and MATSim Export

```powershell
python -m hagrid_demand baseline run --config configs/baseline-daily.json --run-id daily-2025-05
```

The example configuration computes the same eight days as `ParcelDemandScenarioGenerator` (9–10 May and 12–17 May 2025).
The annual volume is distributed once over the whole year; `dates` only selects which days are written.

- **Year:** total volume × national volume series V(y)/V(2021), B2B target and market shares per year (notebooks 00–02).
- **Week:** weekly profile from `Parcels19_20_21_inter.xlsx` (notebook 03), `calendar.weekly_profile: "source"`.
- **Weekday:** by default the shipping-day and transit-time mechanism (next section). Without `temporal`, the notebook
  distribution applies directly: Mon .16, Tue .17, Wed .19, Thu .18, Fri .15, Sat .115, Sun 0 (`calendar.weekday_weights`).
- **Public holidays:** statutory holidays of Lower Saxony (`calendar.holiday_region`, `holiday_dates`); without `temporal`
  with `holiday_factor` 0.
- **Operating days:** `reference_operating_days: "calendar"` counts the delivery days of the reference year with the same
  calendar (2021: 306).
- **B2B per carrier:** bounds, start values and scaling as in notebook 05 (`data/provider_priors.json`); the national B2B
  target is met exactly every year without leaving the bounds.
- **Business weight:** 1 per firm; in the LSP street check the number of employees explains nothing.
  `business_potential.model: company_plus_employees` (1 + 0.1 × employees, as in notebook 06) remains optional.
- **Spatial daily variation:** Dirichlet between postcodes (`between`, notebook 50,000) and within postcodes
  (`within_per_site` × number of sites).

With `matsim_export: true` (the default of the daily run), `<run>/matsim/` holds one
`hagrid_parcel_demand_<date>_(<weekday>).shp` per delivery day in the format that `DemandProcessor` of the MATSim pipeline
reads: points per stop (EPSG:25832), `postal_cod`, `<carrier>_tag` = B2C and `<carrier>_type` = B2B (DBF names have at
most 10 characters, e.g. `amazon_typ`), `total`/`wl_tag` = sum, plus the notebook aliases `dhl_b2c`, `ups_b2b` and so on.
Days without delivery (Sundays, public holidays) produce no file. `matsim_export.json` holds the daily balance. For MATSim,
copy the folder to `hagrid/simulation/input/hannover/demand/<run-id>/`.

### Shipping Days, Transit Time and Events

With `temporal.mode: shipping_transit` (the default in `configs/baseline-daily.json`, specification
[`2026-09-25-hagrid-shipping-week-annual-design.md`](../../../docs/demand/specs/2026-09-25-hagrid-shipping-week-annual-design.md)),
the daily course follows from shipping day and transit time instead of a fixed delivery profile:

1. **Shipping day:** seasonal factor of the calendar week × shipping profile per weekday (`data/temporal_inputs.json`).
   Business senders follow the LogIKTram pickups: Mon .23, Tue .21, Wed .18, Thu .16, Fri .17, Sat .05. Private senders are
   derived back from the notebook delivery profile (`derive_shipping_profile`, Sat:Sun = 2:1): Mon .178, Tue .200,
   Wed .185, Thu .150, Fri .113, Sat .116, Sun .058. Nothing ships on public holidays; these orders ship over the next three
   shipping days (`holiday_spread_days`, 1 = everything on the next day), so the catch-up wave spreads over several days.
   What would ship on 24–26 December is pulled forward into the five days before (`christmas_pull_forward_days`, 0 = off),
   so gifts arrive before Christmas and only transit remainders follow. Five days match the Swiss weekly values of
   notebook 03 best (weeks 49–52: 1.46/1.55/1.47/1.13 against 1.49/1.57/1.36/1.11; 14 days move too much from week 52 into
   weeks 50 and 51, 3 days create a peak on Christmas Eve).
2. **Transit time:** E+1/E+2/E+3 = 0.85/0.13/0.02 delivery days (Mon–Sat without public holidays, never Sunday), per
   carrier via `transit_days`.
3. **Events and half delivery days:** Prime Day (Amazon, orders ×2.0), Black Week (Black Friday to Cyber Monday, all
   carriers ×1.35, so that the peak reaches 0.89 of the Christmas peak as at DHL in 2025) and Singles' Day (arrival
   18–21 November, DHL/Hermes/GLS/DPD ×1.15) raise the B2C orders of these carriers on these shipping days; every carrier
   keeps its annual volume (`events`, default `data/events.json` with dates and sources). Christmas Eve and New Year's Eve
   deliver only half (`half_delivery_days`); the rest arrives on the next delivery day, for firms on the next working day.
4. **Saturday:** all carriers deliver on Saturdays (`saturday_delivery: 1.0`; data on differences are missing). Only 20 %
   of firms accept parcels on Saturdays (`business_saturday_open`); the rest arrives on the next working day.
5. **Randomness per parcel:** a weekly factor per segment (AR(1), log-SD 0.016, ρ 0.5), a weekly factor per carrier
   (log-SD 0.02), a daily market-share shock per carrier (`carrier_day_log_sd` 0.03, normalised over the carriers every day
   so the mean daily volume stays the same), a Dirichlet split over the weekdays (κ 1000), and transit time and Saturday
   acceptance per parcel. The carriers thus deviate from one another from day to day and from week to week while the
   expected course stays the same. The annual volume per carrier is preserved.
6. **Space:** carrier strongholds emerge from the B2B/B2C mix. B2B parcels go to firm sites and B2C parcels to homes, each
   with the carrier shares of its segment. Where there is much business, UPS and FedEx are strong, in residential areas
   Amazon and Hermes; the anchor LSP follows its measured street volumes. Frequent receivers (`spatial.site_frailty_cv` 0.5: a gamma
   factor per site and year, normalised per street and postcode so the street anchor and the postcode volumes hold) are
   large on many days. Optionally, `spatial.carrier_plz_log_sd` draws additional random strongholds per carrier and postcode
   (default 0; IPF keeps the postcode and carrier volumes, but the anchor LSP then moves as well). Both are assumptions without data
   and apply only in shipping mode.

### Parcel Lockers, Parcel Shops and Shared Boxes

With `out_of_home` (the default in `configs/baseline-daily.json`, specification
[`2026-09-25-hagrid-out-of-home-design.md`](../../../docs/demand/specs/2026-09-25-hagrid-out-of-home-design.md)),
every carrier delivers part of its B2C parcels to pickup points instead of the door:

1. **Points:** the OSM state via Overpass (`baseline osm-parcel-points --plz <plz.csv> --out <parquet>`, configuration key
   `osm_parcel_points`). Stations come once, offline, from the Geofabrik extract with
   `baseline osm-transit --pbf … --plz … --out …`; the station context then follows without network access with
   `--points <existing file> --transit <stations> --pois <POI file>`. In 2026 the region has 229 parcel machines (DHL
   Packstation, Amazon Locker/Hub), 14 shared boxes (Myflexbox and others, usable for Hermes, DPD, GLS and UPS) and
   192 branches and shops. Missing partner shops of DHL, Hermes, DPD, GLS and UPS are added up to the extrapolated network
   density at kiosks, supermarkets, bakeries, drugstores and fuel stations (`synthetic_shops`, weighted by the residents
   within 500 m).
2. **Lockers and counters:** parcels go to parcel machines, shared boxes and staffed pickup counters (`kinds`, default
   `locker`, `shared_locker`, `counter`); parcel shops stay in the points file and follow with the modelling of failed
   deliveries. The locker infrastructure is complete, including machines without parcels on a given day. Shared boxes
   (Myflexbox, Paketbox) serve Hermes, DPD, GLS, UPS and FedEx; DeinFach and inboxx also DHL. Amazon Lockers and
   Packstations belong to their carrier. Amazon counters (fuel stations, supermarkets, kiosks, department stores) are
   missing in OSM; `synthetic_counters` places them at such POIs (daily configuration: 15 for Amazon, each taking
   80 parcels). Amazon parcels delivered by DHL may go to Packstations; the model counts them as DHL parcels within DHL's
   Packstation share.
3. **Share per carrier and year:** a bounded sigmoid as in the notebooks, fitted to DHL (3 % in 2019, 5 % in 2021, 10 % in
   2025). In 2025, DHL delivers 10 %, Amazon 5 % and Hermes, DPD, GLS, UPS and FedEx 2 % each of their B2C parcels to lockers
   (`shares_2025`, or `shares_by_year` as a table; the small networks limit the shares further). The share per carrier
   varies daily (log-SD 0.10, AR(1) ρ 0.6). `synthetic_lockers` and `synthetic_shared_lockers` add scenario lockers at kiosks,
   supermarkets and fuel stations (not set in the daily configuration).
4. **Who picks up:** a probability per building and carrier that falls with the distance to the nearest suitable point (up
   to 1.5 km, decay over 600 m) and is higher in multi-family houses, scaled per year so that the share holds.
5. **Compartments and pickup:** every locker has compartments, from the OSM tag `capacity` or sized by demand
   (`compartments_by_demand`: 1.6 × 1.25 × expected daily demand, rounded to 10, 48 to 390 compartments). DHL's modular
   Packstation grows from 76 to up to 390 compartments; shared boxes have 40, counters 80. Recipients choose among the three
   nearest points in reach (`choice_k`, probability ~ exp(−distance/300 m)); if the chosen point is full, the parcel goes to
   the next one. Every stored parcel draws its pickup class once (`pickup_profile`, reference profile `id` after Sailer,
   Klein & Steinhardt 2026, table 2): 60 % leave the compartment on the day of delivery, 20 % on the next day and 20 % on
   the second day, including removal by the operator at the end of the deadline. This is a literature-based model
   assumption, not a local measurement (Hovi et al. 2023: about 60 % within one day; Morganti et al. 2014: 70 % within 24 h;
   discussion in [`Paketstationen_Abholzeiten_Literatur_und_Modellannahmen.md`](../../../docs/demand/demand-audit/Paketstationen_Abholzeiten_Literatur_und_Modellannahmen.md),
   German). A compartment stays occupied until it is released and is free again on the next day; pickups also happen on
   Sundays and public holidays. The OSM context of a station shifts the dwell time (`pickup_context_factor`, after the mean
   pickup times in Hovi et al. 2023: supermarket 30.5 h, transport hub 34.5 h, overall 31.6 h): `retail` (a shop within
   75 m) 60/25.6/14.4 %, `transit` (station, stop, tram stop or bus station within 150 m) 60/5.3/34.7 %, otherwise
   60/20/20 %; the context comes with the points download from OSM. A parcel that does not fit goes to the second-nearest
   point, otherwise to the door. `annual/locker_occupancy.parquet` holds occupancy (afternoon peak), stored parcels and
   rejections per locker and day; the dashboard shows the fill level and the full locker days.
6. **Output:** pickup points are stops of their own in the annual store (`annual/out_of_home_points.parquet`) and in the
   MATSim export (field `stop_type`: `home`, `locker`, `shared_locker`, `counter`, `shop`); `days.parquet` counts
   `out_of_home`. The MATSim pipeline sets the delivery mode `PARCEL_LOCKER_EXISTING` for these stops and no longer adds the
   fixed extra demand of 25 parcels per Packstation (`hubs.fixedParcelLockerDemand`, default `false`), so nothing counts
   twice.

Acceptance run 2025 (lockers, boxes and counters; literature profile 60/20/20 with station context; compartments by demand;
choice among the three nearest points): 5.9 % of the B2C parcels go to pickup points (DHL 9.9 % of its 10 % target; Amazon
4.9 % of 5 % via 22 mapped lockers and 15 synthetic counters; Hermes, DPD, GLS and UPS 1.9–2.0 % each of 2 % via the
14 shared boxes; FedEx 0.7 %). Compartments: Packstation median 76 (90 % quantile 130, four stations with ≥ 200, 18,400 in
total), shared boxes and counters 120. Mean afternoon fill level 55 %, 5 % of the station delivery days full, 112,000
parcels rejected (59,000 of them to the door); a Packstation takes a median of 24 parcels per delivery day, an Amazon Locker
32, a counter 56. Events: Amazon B2C on 9–12 July ×1.8–1.9, the Black Week peak (Tue 2 December) at 0.90 of the Christmas
peak (Mon 22 December, 420,000 parcels); Christmas Eve and New Year's Eve deliver half. The annual shares per carrier and
segment stay exact.

### Annual Store and Annual Dashboard

With `annual_store: true`, the run computes every day of the year and keeps an annual store (`<run>/annual/`) instead of
365 shapefiles: `stop_daily.parquet` (date, stop and 14 count columns `<carrier>_b2c`/`_b2b`), `plz_daily.parquet`,
`days.parquet` (daily totals and stop figures) and `annual_summary.json` (weeks, months, weekday profiles). `dates` still
sets which days are written directly as MATSim files. `export-day` writes any other day from the store, in the same format
and identical to the direct export for configured days:

```powershell
python -m hagrid_demand baseline export-day --run runs/<run-id> --date 2025-06-03
python -m hagrid_demand baseline annual-dashboard --run runs/<run-id> --out runs/<run-id>/year.html
```

Every run with an annual store also writes `<run>/annual_dashboard.html`, "Hannover Parcel Year": one HTML file, readable
offline, light and dark, mobile, with a calendar to pick days and weeks, key figures, a time series per carrier, the
weekday profile, a postcode map and a postcode table. `annual-dashboard` rebuilds it, for example for another year
(`--year`); `--artifact` omits doctype and head for hosts that add them.

Acceptance run of 25 September 2026 (2025, all 365 days, the eight notebook days directly as MATSim files): 16.5 min
without cache (sources, buildings and reference 6 min, daily stage for the year 7.4 min, export with notebook comparison
2.6 min), annual store 97.6 MB. 58.32 million parcels on 303 delivery days; the annual volume per segment and carrier is
preserved exactly. Private delivery profile in the 37 weeks without a public holiday (also in the week before): Mon 16.45,
Tue 17.63, Wed 19.72, Thu 18.69, Fri 15.57, Sat 11.94 % (notebook 16.58/17.62/19.69/18.65/15.54/11.92); business on
Saturdays 3.39 %. Catch-up wave after public holidays (with `holiday_spread_days` 3, against the same weekday two weeks
before and after): Easter Tue–Fri +12/+60/+43/+32 %, Whitsun Wed–Fri +29/+36/+25 %, 1 May and Ascension on Saturday and
Monday +25 to +46 %, 3 and 31 October over three days +22 to +32 %. Week 20: 1.12 million parcels; against the notebook
generator −5 to −15 % on working days and −24 % on Saturdays (firms hardly accept parcels on Saturdays: B2B share 6–7 %
instead of 22 %). `export-day` is identical for the directly exported days.

With the daily market-share shocks and frequent receivers: daily DHL share of private parcels ± 0.86 pp instead of
± 0.61 pp (business ± 0.97 instead of ± 0.59), annual volume per stop against its expected value CV 27 % instead of 4 %;
annual volumes, weekday profiles, the store (96 MB) and the notebook comparison stay practically the same. Carrier shares
per postcode from the mix: UPS 7–19 %, Amazon 8–21 %, Hermes 5–11 %, DHL 42–45 % (B2B share of the postcode 9–68 %).
Christmas 2025 against the same weekday in November: 15–19 December +9 to +34 %, 20–24 December +47 to +86 %, afterwards
only transit remainders (27 December 0.37×, 29–31 December 0.64–0.77×); no peak at New Year (3 January 0.92×). The peak days
of the year are Mon 22 December (423,000 parcels), Wed 24 December and Tue 23 December, then the Wednesday after Easter
(374,000). Weekly course against the Swiss weekly values of notebook 03: correlation 0.94, 44 of 51 full weeks within the
notebook's confidence band; only public-holiday weeks (dip) and the weeks after them (catch-up wave), which the Swiss mean
of 2019–2021 does not contain, lie outside. Over the year, the market shares, the shares per segment, the B2B quotas per
carrier and the B2B share (21.54 %) meet the computed values exactly (at most 0.5 parcels of rounding); 90 % of the
postcode volumes lie within −0.7 to +0.4 % of their expected value, and only the smallest postcodes 30521 and 30669
(1,400–7,500 parcels a year) scatter down to −6 %.

### Multi-Year Projection 2025–2035

Specification: [`2026-09-28-decade-projection-design.md`](../../../docs/demand/specs/2026-09-28-decade-projection-design.md),
plan: [`2026-09-29-decade-projection.md`](../../../docs/demand/plans/2026-09-29-decade-projection.md).

`years` may hold several years. Calendar, shipping days, market shares, B2B share, out-of-home shares and pickup plans are
built per year, and the annual store keeps all years. The run configurations for 2025–2035:

| Configuration | `volume_scenario` | National 2035 (bn) | Factor 2035/2025 | Exported days |
|---|---|---|---|---|
| `configs/decade-trend.json` | none (linear fit of the observed series, the default path) | 5.57 | 1.27 (≈ 2.5 %/a) | eight comparison days per year |
| `configs/decade-saettigung.json` | `legacy_assumptions` / `logistic` | 5.26 | 1.20 (≈ 1.9 %/a) | 2030 and 2035 |
| `configs/decade-boom.json` | `legacy_assumptions` / `exponential` | 6.45 | 1.48 (≈ 4.0 %/a) | 2030 and 2035 |
| `configs/decade-trend-innen.json`, `configs/decade-trend-suburban.json` | as trend, with the land-use variants infill and suburban | 5.57 | 1.27 | 2030 and 2035 |

`configs/baseline-daily.json` writes the annual store (`annual_store: true`, like the accepted year runs). The year run then
simulates every day, the pickup points fill up from January, and a single exported day differs minimally from a run without
annual store (2025-05-09: seven stops).

**Volume scenarios.** `series.py::apply_volume_scenario` chains one of the three fitted curves of the notebook volume series
(`linear`, `logistic`, `exponential`, each under `observed_only` or `legacy_assumptions`) at the level of the `chain_year`:
`V(y) = V_base(2025) · C(y) / C(2025)` for `y > 2025`. All scenarios thus share the accepted 2025 level and differ only in
their slope; without the block, every run stays bit-identical. The comparison days are Friday and Saturday of ISO week 19
and Monday to Saturday of ISO week 20. A day on a public holiday (Ascension, Whit Monday) moves to the same weekday one week
later (`comparison_days.py`, checked in `tests/test_decade_configs.py`).

**Growing pickup network** (`network_growth.py`, defaults in `data/out_of_home.json::network_growth`). Before the day loop,
a pre-pass adds the new points of every year after the reference year, so the stop register is complete:

- Network groups are the `(kind, carriers)` pairs of the reference network (DHL Packstations, Amazon Lockers, shared boxes,
  Amazon counters). Target per group: `N(y) = max(N(y−1), round(N(2025) · (D(y)/D(2025))^0.6))`, where `D` is the expected
  out-of-home demand of the group (B2C annual volume × carrier share × out-of-home share of the year). The rest of the
  demand growth goes into larger stations: compartments are resized every year, never reduced, up to 390.
- Candidates are retail and fuel POIs from OSM (`candidate_types`). Weight = B2C demand within 500 m × coverage gap
  `1 − exp(−d/600 m)` to the nearest point of the same group × preference by kind (Packstation: supermarket, fuel station,
  convenience store; counter: kiosk, convenience store and similar; shared box: supermarket, fuel station; otherwise factor
  0.25). Minimum distance 150 m, drawn without replacement and deterministically (`named_rng(..., channel="ooh-network-growth")`).
- New points carry `year_opened`, `poi_type` (e.g. `shop=supermarket`), `synthetic = true` and the context `retail`; only
  points opened by the day's year take parcels and appear in the MATSim export.
- Result for Region Hannover, trend run: DHL Packstations 207 → 477 (2025 → 2035), 330 new sites in total, no shortage of
  candidates; the pre-pass takes about 5 s.

**Outputs per run:** `out_of_home_network.parquet` (one row per point and active year: compartments, postcode, coordinates,
opening year, POI type; `out_of_home_points.parquet` instead lists all points with the compartments of the first simulated
year), `out_of_home_inputs.json` (the resolved out-of-home inputs of the run, which the dashboards read back),
`annual/point_daily.parquet` in the annual store (the pickup-point rows per day and carrier, small enough to read whole),
`daily_status.json` → `temporal.out_of_home.network_growth` (target, additions, candidates and shortfall per year and
group), `annual_dashboard.html` (last year) and `annual_dashboard_<year>.html` for every year.

**Decade dashboard.** `python -m hagrid_demand baseline decade-dashboard --run trend=<run> --run saettigung=<run>
--run boom=<run> --out decade_dashboard.html` (`decade_dashboard.py`, template `templates/decade_dashboard.html`,
standalone without a CDN) shows the volume fan, carrier and channel shifts, a postcode map with a year slider, growth
hotspots, network growth and utilisation, the calendar carpet and the assumptions. The first `--run` is the base scenario
and must be complete; missing years of the other scenarios are marked. District shapes and site lists that several
scenarios share are stored once, and the builder reads the site projection one run at a time.

**Storage.** The annual store (`stop_daily`, `plz_daily`, `point_daily`, `locker_occupancy`) and the detail files are
written day by day as Parquet row groups and never collected in memory. Per year only one routing plan is held in memory,
and its compartment queue keeps its parcels across New Year. Published stages and the public copies (`daily/`, `annual/`,
`matsim/`) are hard links to the stage cache, so a decade run takes about 7 GB on disk instead of 20. New points are chosen
with one fixed random number per POI and network group (exponential-race sampling), so the scenarios share one ranking and
differ only in how far down it they go.

**Run.** `runs\hannover\run_demand_decade.bat [trend saettigung boom trend-innen trend-suburban]` runs the scenarios one
after another (about an hour each, about 0.5 GB of details per year plus about 45 MB per exported day) and then writes
`hagrid\demand\runs\decade_dashboard.html`.

### Land-Use Dynamics 2025–2035

Specification: [`2026-09-29-land-use-dynamics-design.md`](../../../docs/demand/specs/2026-09-29-land-use-dynamics-design.md),
plan: [`2026-09-29-land-use-dynamics.md`](../../../docs/demand/plans/2026-09-29-land-use-dynamics.md).
Without a `land_use` block every run stays unchanged. With the block, the volume of the scenario is redistributed over the
sites every year according to where people live, how much they order and where firms grow. The regional and segment totals
do not change, and the base year 2025 stays bit-identical to a run without land use.

**Sources** (`src/hagrid_demand/baseline/data/land_use.json`):

| Component | Source |
|---|---|
| Persons per district | Landeshauptstadt und Region Hannover, *Bevölkerungsprognose 2025 bis 2035* (February 2026), table 7 (30 forecast districts of the city) and table 8 (20 towns and municipalities), population on 31 December 2024 and 31 December 2034. 4.1 Buchholz and 4.2 Roderbruch both lie in the quarter Groß-Buchholz and form one model unit, so there are 49 units. |
| Development areas | The same forecast: Kronsberg (Bemerode, +3,675 persons), Wasserstadt Limmer (+1,384), Langenhagen-Mitte, Godshorn and Kaltenweide, Berenbostel and Garbsen-Mitte, Seelze-Süd. Residents per area and timing are assumptions. The locations come from OSM (Kronsberg-Süd as a land-use polygon, the others as centre and radius). |
| Online propensity | Destatis, ICT survey 2025, online shopping in the last 12 months: 16–24 years 84 %, 25–44 years 91 %, 45–64 years 80 %, 65–74 years 61 %; 75+ not surveyed (assumption 40 %). |
| Firms | Assumption after Region Hannover, *Trends und Fakten* 2025 (employees subject to social insurance 2014–2024 +1.5 %/a, recently +0.5 %): Q +1.5 %/a, J/M/N/H +1.0 %/a, G ±0, C −0.5 %/a, otherwise +0.5 %/a. |
| Areas | OSM administrative boundaries (`admin_level` 8 and 10), built with `baseline osm-boundaries` from the Geofabrik PBF of 2021. |

**Calculation** (`land_use.py`), base year `base_year = 2025`:

- **District index:** linear between 2024 and 2034, then at the mean annual rate. The variants `innenentwicklung` (infill)
  and `suburbanisierung` (suburban) shift the annual growth of the city and the surrounding towns by ±0.1 percentage points
  and are rescaled to the regional total of the forecast every year.
- **Existing stock:** `E_d(y) = max(0, P_d · Index_d(y) − R_d(y)) / P_d`. `P_d` are the model persons of the district and
  `R_d(y)` the residents of the development areas who have moved in, in model persons (forecast residents × model/forecast
  ratio in the base year). Values below 0 are set to 0 and reported under `clamped` in the status.
- **Age and propensity:** the age distribution per district (synthetic population) ages by one year per year. The
  propensity is `p_y(a) = max(p(a), p(a − s))` with `s = cohort_shift · (y − 2025)`, default 0.7: a cohort keeps the
  propensity of its younger self, and young adults keep ordering like young adults. The propensity index of a district is
  its mean propensity per person relative to 2025.
- **Site factor:** home site = `E_d` × propensity index of the district. Firm site = employee-weighted branch growth of its
  firms: for a growing branch `1 + (1 − new_firm_share) · ((1 + r)^(y − 2025) − 1)`, for a shrinking branch the full decline
  `(1 + r)^(y − 2025)`, because no new firms open there. `project_annual` weights `historical_share` with the factor per site
  and segment and normalises per segment; rows with factor 0 are absent in that year.
- **New sites:** development areas get a 50 m grid inside the area (at least 5 points) and open with a linear ramp.
  `new_firm_share` (default 0.3) of the employment growth per branch opens as new firms of the branch's mean size in OSM
  commercial and industrial areas. The areas are drawn in proportion to their size and without replacement within a year:
  every area gets at most one new firm until all have one (`named_rng(..., channel="land-use-firms")`). Every new site has
  its own stop. The stop indices start at 1,000,000, behind the pickup points, and the run stops if the two ranges would
  meet. Every development area forms its own street group for the clustering, and so does every new firm.
- **Checks on load:** development areas start after the base year, no simulated year lies before it, and the area names
  are unique, also as slugs in the site IDs.

**Outputs per run:**

- `land_use_districts.parquet`: index, propensity index, model persons, employees and forecast index per year and district
- `land_use_site_districts.parquet`: the district of every site as the model assigns it (the basis of the dashboard decomposition)
- `land_use_ages.parquet` (persons and online propensity per 5-year age band and year) and `land_use_developments.parquet`
  (model residents per development area and year)
- `land_use_factors.parquet`
- `land_use_sites.parquet` and `land_use_stops.parquet` (the new sites)
- `land_use_district_shapes.parquet` (simplified district boundaries)
- `daily_status.json` → `land_use` (variant, parameters, clamped districts, new sites per year)

**Configuration:**

```json
"osm_boundaries": "../../input/hannover/osm/osm_boundaries_region_hannover_2021.parquet",
"land_use": {"enabled": true, "variant": "prognose", "cohort_shift": 0.7, "new_firm_share": 0.3}
```

Further keys: `base_year`, `grid_m`, `persons` and `landuse` (file names relative to `input_dir`), `developments`,
`firm_rates` and `districts` (each `"standard"` or a list of your own). The boundary file is built once:

```powershell
python -m hagrid_demand baseline osm-boundaries --pbf ../input/hannover/osm/niedersachsen-210101.osm.pbf --plz ../input/hannover/raw/plz_region_hannover.csv --out ../input/hannover/osm/osm_boundaries_region_hannover_2021.parquet
```

The decade dashboard shows land use in two sections. **Structural change** holds a district map (persons, propensity,
employees), the comparison of model and forecast, the propensity curve by age and the table of development areas.
**Change over time** shows a hexagon map of the expected demand (800 m hexagons) with the views growth against the region,
change, density and land-use effect. It adds the growth from year to year, the city share per scenario, the decomposition
of every district change into more parcels overall and redistribution by land use, and the first year of every milestone.

The decade runs `decade-{trend,saettigung,boom}` use the variant `prognose`. `decade-trend-innen` and
`decade-trend-suburban` run the trend volume with the two variants; they export MATSim days for 2030 and 2035 only.

## Other Commands

### Joint Model (`run` and `predict`)

The joint model of September 2026 combines calibration, carrier split, calendar, spatio-temporal variation, future
projection, delivery points and exports ([description](MODEL_WORKFLOW.md), in German). It uses the foundation run named in
its configuration, and every run writes a new run directory. From the repository root:

```powershell
python -m hagrid_demand run --config hagrid/demand/model/configs/model.json
python -m hagrid_demand predict --config hagrid/demand/model/configs/model.json --model-run hagrid/demand/runs/<joint-model-run>
```

`predict` applies a frozen model version without new training; the site snapshot and the reference year must match the
frozen model.

### Data Foundation (`foundation`)

```powershell
python -m hagrid_demand foundation --config configs/hannover.json
```

After installation, `hagrid-demand foundation --config configs/hannover.json` works as well. An optional
`--run-id my-run` names the run; existing runs are never overwritten. The stages:

1. `ingest`: SHA-256 of the consumed files including shapefile components; inventory of further local files.
2. `build_sites`: private building units and single business sites; the population stock is preserved.
3. `audit_observations`: LSP streets and Hermes postcode/year values in their original semantics, with quality flags.
4. `link_candidates`: unique postcode membership and the nearest LSP line within a configurable distance.
5. `report`: volume balances, assignment status and open prerequisites for the calibration.
6. `dashboard`: a standalone HTML dashboard with postcode map, filters, tables and automatically computed findings.

Results are in `runs/<run-id>/`; `dashboard.html`, `report.md` and `summary.json` are the entry points. Site and
observation tables are written as Parquet/GeoParquet. They contain local source identifiers, so the whole run directory is
excluded from git. Raw data are only read. [The verified run](VALIDATION.md) (in German) passed seven tests and kept the population
balance with 280,572 sites.

Every successful foundation run writes `dashboard.html` and `dashboard_data.json`. The HTML file opens directly in the
browser, needs no server and loads no external services. It shows aggregated data, no individual site or recipient
identifiers:

- Filters: all sites, private buildings, business sites.
- Postcode map: open count and rate and the affected residents or employees; a click filters the table.
- Sortable postcode table and industry overview.
- Distance distribution with exactly one value per site, also with several candidates.
- Source overview, household gaps, repeated street keys and limits of interpretation.

An existing run can be evaluated again without a new data import:

```powershell
python -m hagrid_demand dashboard --run-dir runs/<run-id>
```

The command updates only the two dashboard artefacts; the original run and fit status stays. Creation time and renderer
hashes are stored separately in `dashboard_data.json`, so older foundation runs can be evaluated with a newer dashboard
without rewriting their original code version. [The evaluation of the first dashboard run](DASHBOARD_ANALYSIS.md)
(in German) describes the most striking differences and the checks derived from them.

The fit must not use the candidates as confirmed assignments. Without addresses, proximity and postcode are only
indications. Equidistant lines, boundary points, missing geometries and contradictory building IDs stay visible. Repeated
LSP street keys are neither summed nor deduplicated automatically. No parcel volumes are distributed to sites and no
network links are invented. A run can be technically complete and still report `calibration_ready: false`; this version
always does, because the definition of `tagesschni`, the Hermes units, the temporal origin and the actual assignment must
be confirmed before a calibration. The distance of 100 m and the building tolerance of 5 m are configurable working
assumptions, not empirically optimised parameters. Geometry and identity errors of unclear meaning are reported, and no
duplicates are removed silently. Several firms at the same point stay separate units; shared physical building or entrance
IDs are added only with a reliable building or address reference.

### Street Reference and Historical OSM Audit

These commands work without MATSim and each write their own dashboard:

```powershell
python -m hagrid_demand street-reference --model-run hagrid/demand/runs/kep-reference-20260910 --output hagrid/demand/runs/my-street-reference
python -m hagrid_demand logistics-audit --foundation hagrid/demand/runs/hannover-foundation-dashboard-20260909 --output hagrid/demand/runs/my-osm-audit --regional
```

The street reconstruction keeps every cleaned LSP observation of 2021 individually. Unique but spatially unconfirmed
candidates provide model weights. Volumes that cannot be assigned stay separate in `unallocated_street_demand.parquet` with
their original street geometry. `street_checks.csv` and `postal_checks.csv` each check the site volume plus the open volume.
Exact sums are a data binding here, not an independent measure of predictive quality. Other carriers keep their model
values.

The OSM audit records the map state of 31 December 2021 and the current server state separately. Successful partial
queries are cached with query and content hashes; after an interrupted download, repeat the same command with the same
output folder. Larger failed queries are split spatially, and these query tiles are not demand cells. Gaps in historical
OSM are no proof that buildings were absent, and map objects must not be equated with businesses. `--snapshots current` or
`--snapshots 2021` limits a resumption to the chosen data state. A complete server response does not mean that the real
building stock is completely mapped.

A separate exploratory comparison tests historical OSM features under the existing outer and inner folds:

```powershell
python -m hagrid_demand.model_search --config hagrid/demand/model/configs/local-carriers.json --persons hagrid/demand/input/hannover/raw/persons_total.csv --logistics-run hagrid/demand/runs/my-osm-audit --output hagrid/demand/runs/my-logistics-comparison
```

The comparison needs a complete regional OSM audit. Missing postcodes are not filled with zero features, and there is
neither a manual Langenhagen constant nor any new exclusion of target values. `--logistics-snapshot current` explicitly
runs a retrospective comparison with current map features; the dashboard marks it as a temporally mismatched proxy attempt
for the LSP data of 2021, and no model release is derived from it. The default stays `2021`.

The continuation of 14 September 2026 produced a street anchor with 85,113 assigned and 826 open volume units, a current
regional OSM audit and four additional logistics candidates; the complete historical query stayed blocked by server errors.
Its entry page is `runs/continuation-20260914/dashboard.html` with the method report `report.md` next to it (local runs,
git-ignored). This street reference is not connected to the daily and future simulation.

### Spatio-Temporal Method Comparison (`spatial-demo`)

`spatial-demo` compares a fixed distribution with a spatio-temporally varying one at **identical daily totals per
segment**. It works directly on the site coordinates and uses postcodes only for the evaluation. It needs an existing
foundation run. From the repository root:

```powershell
python -m hagrid_demand spatial-demo --config hagrid/demand/model/configs/spatial-demo.json
```

The example configuration uses seven days, seed 42 and illustrative volumes of 180,000 private and 40,000 business parcels
per day. These are **not estimated Hannover volumes**. The private base weights are population, the business base weights
employees; the weights are not yet calibrated intensities. Parameters and input paths resolve against the configuration
file. For every date and segment the command writes site Parquet files with fixed and changed expected values and integer
daily volumes; `comparison.json` holds postcode aggregates and diagnostics, and `dashboard.html` switches between date,
recipient segment and expectation or daily draw.

The spatial field uses a finite Fourier approximation of a smooth Gaussian covariance kernel. The coefficients follow a
stationarily initialised AR(1) process: `length_scale_m` sets the spatial range, `log_sigma` the strength and
`temporal_rho` the temporal persistence. The factors multiply the base weights and are normalised per segment, so the
spatial shares change but the given total does not. The spatial kernel is a candidate; street barriers and shared shocks
by industry are not yet represented. Calendar, variation of the total volume, carrier assignment and future growth are
left out of this controlled comparison. The example demonstrates volume balances and reproducibility, not empirical demand
quality. Because the random streams are bound to dates, extended or shifted time windows give identical overlapping days as
long as the site stock, the parameters, the seed and the time anchor stay the same.

The first comparison run (`runs/spatial-comparison-20260909/dashboard.html`, local) passed 13 tests, and all 14 day and
segment files keep the given integer volumes. The spatially redistributed expectation is 8.33–11.37 % for private and
12.06–24.66 % for business demand in this example; these values depend directly on the assumed parameters.

## Tests

```powershell
python -m pytest -q
```

The suite covers the stock balances, contradictory buildings, missing IDs, spatial ambiguity, postcode boundaries and
null or missing values of the data foundation, and the reference, daily, shipping, out-of-home, annual-store, decade,
land-use and dashboard stages of the `baseline` workflow. Real data are checked with complete runs, such as the acceptance
runs above. The `baseline` workflow caches every stage by content and continues an interrupted run with `--resume`; a
failed `foundation` run keeps `run.json` with its stage status and starts from scratch on the next attempt.
