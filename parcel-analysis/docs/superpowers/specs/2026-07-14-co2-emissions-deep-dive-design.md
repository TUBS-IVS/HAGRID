# CO2 Emissions Deep-Dive Notebook Design

Date: 2026-07-14

## Objective

Create a new, self-contained analysis notebook at
`specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb`.
It will reuse validated methods from
`result-analysis-batch-mobiltTUM-paper-emissions.ipynb` while replacing its
obsolete scenario logic and fragmented execution order. The new notebook must
support a clean `Run All`, provide paper-ready core results, and retain a
separate exploratory layer for deeper temporal, provider, vehicle, road-type,
and spatial analyses.

The existing source notebook remains unchanged and serves only as a reference.

## Scenario Contract

The notebook uses exactly four scenarios in this order:

1. `basecase` -> `Baseline`
2. `batchmoderate` -> `Moderate Consolidation`
3. `batchhigh` -> `High Consolidation`
4. `batchfull` or `BATCHFULL` -> `Full Consolidation`

`batchmedium` is excluded explicitly. Scenario detection is case-insensitive
and based on the first run-name token. The Full Consolidation filenames may
contain an additional `mobilTUM` token; this token must not affect scenario or
date recognition.

Each scenario must resolve to the six modeled dates from Monday, 12 May 2025,
through Saturday, 17 May 2025. The data audit fails with an actionable error if
a required scenario-day combination is missing or ambiguous.

## Data Discovery and Selection

The historical emissions data are distributed across multiple directories.
In particular, the legacy `emissions_fix` directory contains the older
scenarios but not Full Consolidation, while Full Consolidation is available in
the newer processed-data tree. The notebook therefore discovers files below a
prioritized list of candidate processed-data roots rather than assuming a
single hard-coded directory.

For each scenario-day combination, the loader selects one canonical file of
each required kind:

- vehicle/tour emissions: `*_emissions_result.pkl`
- link-time emissions: `*_emissions_15min_long.pkl`

Selection is deterministic. Exact canonical scenario and date matches are
preferred, duplicate paths are reported, and the chosen path is recorded in a
file inventory. The loader never silently combines duplicate copies of the
same run.

Legacy pickle classes required by the vehicle/tour objects are defined before
unpickling. The implementation reuses only the minimum compatible class
definitions needed for loading and does not execute unrelated cells from the
source notebook.

## Memory and Cache Strategy

A single 15-minute file contains roughly seven million rows, so all 24 files
must not be concatenated in memory. The notebook processes them one file at a
time and writes compact derived tables to an emissions cache. Cached products
include:

- scenario-day totals
- weekday by 15-minute profiles
- link-week totals
- link-week differences from Baseline
- area-type and road-type aggregates
- hexagon-week totals and scenario differences

Each cache entry stores a fingerprint derived from source path, size, and
modification time. It is reused only when all source fingerprints and analysis
parameters match. A configuration flag allows a forced rebuild.

The cache contains derived aggregates only. It does not duplicate the raw
multi-million-row inputs.

## Unit and Data Quality Contract

The primary outcome is operational CO2 emissions. The notebook normalizes raw
fields to grams internally and exposes kilograms and tonnes only for display.
It checks consistency among `emissions_g`, `emissions_kg`, and `emissions_t`
where multiple unit columns exist.

The opening audit reports:

- selected and rejected files per scenario and date
- row counts and source timestamps
- missing, duplicate, negative, non-finite, and all-zero values
- available emissions-related columns and dictionary keys
- agreement between vehicle/tour and link-based weekly totals
- parcel, distance, tour, vehicle, and provider coverage

Separate pollutant fields or dictionary keys such as NOx, PM, CO, or HC are
detected automatically. If present consistently across all four scenarios,
they receive a clearly labeled exploratory summary. They are not mixed into
the CO2 core analysis. If they are absent, the notebook prints that finding and
skips the optional section without failing.

## Analysis Architecture

The notebook is organized around five questions.

### 1. How much?

Compute weekly and daily:

- total CO2 emissions
- absolute and percentage savings versus Baseline
- CO2 per delivered parcel
- CO2 per driven kilometer
- CO2 per tour
- CO2 per active vehicle

All intensity metrics use ratio-of-sums aggregation. Means of precomputed row
ratios are not used for network-wide results.

### 2. Why?

Explain the change in total emissions through the identity
`emissions = distance * emissions intensity`. Use a symmetric additive
decomposition so the distance and intensity contributions sum exactly to the
total scenario change without depending on calculation order.

Where the vehicle/tour emission dictionaries provide compatible components,
also report driving, idling, and cold-start contributions. EV and ICE vehicle
results are separated when the EV flag is available, so changes in fleet mix
are not confused with routing effects.

### 3. When?

Analyze:

- weekday totals and cumulative weekly emissions
- batch-day and non-batch-day contributions
- weekday deviations from Baseline
- weekday by 15-minute emission profiles
- peak interval, peak-to-average ratio, coefficient of variation, and temporal
  concentration
- scenario changes in the timing and magnitude of daily peaks

### 4. Who?

Analyze provider and vehicle heterogeneity:

- absolute and relative savings by LSP
- LSP contributions to network-wide savings
- CO2 per parcel, kilometer, and tour by LSP
- vehicle-level distance-emissions relationships
- outlying LSP-day and vehicle observations
- association of emissions with distance, utilization, deliveries, and tour
  duration

### 5. Where?

Analyze:

- Urban, Suburban, and Rural aggregates
- the eight detailed spatial types where available
- road-type contributions using a separate road classification
- link-level and hexagon-level weekly emissions
- absolute and relative differences from Baseline
- hotspot concentration, persistence, disappearance, and emergence

Area type and road type remain separate concepts and are never presented as
interchangeable classifications.

## Core Paper Figures

All core figures use an exact 7.48-inch width, vector PDF output, matching PNG
previews, the journal scenario palette, bold panel labels, concise English
labels, and no unnecessary supertitle.

Planned core outputs:

1. `journal_emissions_01_weekly_overview.pdf`
   - total CO2, CO2 per parcel, g/km, and percentage saving
2. `journal_emissions_02_distance_intensity_decomposition.pdf`
   - exact distance and intensity contributions to the emissions change
3. `journal_emissions_03_daily_chronology.pdf`
   - daily batch/non-batch emissions and cumulative weekly totals
4. `journal_emissions_04_temporal_profiles.pdf`
   - weekday by 15-minute profiles and temporal concentration
5. `journal_emissions_05_lsp_heterogeneity.pdf`
   - provider savings and contributions to network-wide savings
6. `journal_emissions_06_area_type_effects.pdf`
   - absolute contributions and relative Urban/Suburban/Rural changes
7. `journal_emissions_07_spatial_hex_maps.pdf`
   - Baseline plus Moderate, High, and Full differences on a shared grid
8. `journal_emissions_08_hotspot_concentration.pdf`
   - concentration and persistence of high-emission locations

## Exploratory Figures

The exploratory section adds analyses when the required fields pass the data
audit:

- vehicle and LSP emissions-distance phase space
- Lorenz curves and concentration indices for links and hexagons
- ridgeline or small-multiple temporal profiles
- LSP waterfall of absolute CO2-saving contributions
- spatial mechanism matrix by area type
- road-type decomposition
- hotspot transition matrix
- saved CO2 per avoided kilometer and per avoided tour
- vehicle, LSP-day, and spatial outlier diagnostics
- correlation matrix for emissions, distance, utilization, deliveries, and
  duration
- compact scenario scorecard
- optional summaries of additional pollutants if consistently available

Exploratory figures use stable descriptive filenames beginning with
`journal_emissions_extra_` and are visually separated from the core paper
outputs.

## Spatial Method

Link emissions are allocated to a shared flat-topped hexagon grid by
intersection length. All scenarios use the same grid, extent, and Baseline
reference. The Baseline panel shows absolute weekly emissions. Scenario panels
show differences from Baseline with one symmetric diverging scale. Near-zero
differences may be made transparent using one documented threshold applied
identically across scenarios.

Hotspots are defined from the Baseline distribution using an explicit upper
quantile. Their transitions are classified as persistent, mitigated,
disappeared, or newly emerging under each consolidation scenario.

## Tables and Text Outputs

The notebook exports compact CSV tables for:

- file inventory and audit results
- weekly scenario KPIs
- daily and 15-minute profiles
- decomposition results
- LSP results
- area-type and detailed-area results
- road-type results
- hotspot metrics

It also prints concise result sentences containing the verified scenario
values and percentage changes. These sentences are drafting aids, not
automatically inserted into the manuscript.

## Error Handling

Errors must identify the missing scenario, date, field, or source path. Optional
analyses skip with an explicit message when their inputs are unavailable. Core
analyses fail early rather than producing partial or silently incomparable
figures.

Plotting cells consume named aggregate tables created earlier in the notebook.
They do not depend on hidden execution state or variables created by unrelated
diagnostic cells.

## Verification and Acceptance Criteria

The notebook is complete only when all of the following hold:

- notebook JSON is valid
- a clean-kernel `Run All` completes
- exactly four canonical scenarios are present
- each scenario contains Monday through Saturday
- `batchmedium` is absent from analytical outputs
- unit checks and required reconciliation checks pass or report a documented
  explainable difference
- ratio metrics use consistent denominators
- cached and uncached runs produce identical aggregate results
- every core PDF has one page and an exact width of 7.48 inches
- rendered PDFs show no clipped labels, overlapping legends, or unreadable
  annotations
- exported tables reproduce the values plotted in the figures

