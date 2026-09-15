# Task 2 report — source-backed national baseline series

## Result

Task 2 adds package-contained constants, pure national-series reconstruction, and an isolated source-preparation path. It does not execute the historical notebooks, consume their CSV exports, invoke DEAP, start the foundation orchestrator, or download data.

`packaged_series_inputs()` resolves only these installed resources through `importlib.resources.files("hagrid_demand").joinpath("baseline/data")`:

- `market_inputs.json`
- `b2b_inputs.json`
- `volume_inputs.json`
- `provider_priors.json`

Every output row carries its source, notebook-cell reference, status, and unit. The constants distinguish 2024–2028 parcel values as `legacy_estimate`; `observed_only` excludes them from fitting, while `legacy_assumptions` includes them deliberately.

## Source formulas and cells

| Series | Source snapshot / cells | Reconstruction |
|---|---|---|
| Carrier market share | `00_EstimateGlobalGermanParcelMarketShares.py`, cells 2, 5, 6 | Back-calculate 2016 from 2022 and the 2016–2022 change; interpolate 2016–2022; apply the source's damped post-2022 increment `annual_change / sqrt(delta + 1) * 2.25`; fit the Amazon logistic points (2017, 2020, 2022); normalize providers each year to one. |
| B2B | `01_EstimateGlobalGermanB2BShares.py`, cells 2, 3, 5 | Preserve BIEK anchors, linearly interpolate missing 2010–2011, and fit `0.20 + L/(1 + exp(-k * ((year - 2009) - x0)))`. The 2009-relative coordinate is retained. |
| Parcel volume | `02_EstimateGlobalGermanParcelVolumens.py`, cells 2, 3 | Rebuild linear, logistic, and exponential candidates. `observed_only` uses documented observations through 2023. Candidate failures return explicit failure status and no substitute curve. |
| Week profile | `03_EstimateWeekyParcelDistribution.py`, cell 2 | Read `Tabelle1`; use numeric weeks 1–52; divide 2019, 2020, and 2021 by their own means; average the relative rows; normalize that profile to mean one. Week 53 is excluded for later calendar handling. |
| Provider priors | `05_EstimateLocalMarketShares.py`, cell 8 | Preserve the old carrier-specific B2B intervals as named opt-in assumptions. Defaults are `[0, 1]`, each scale is positive, and all priors are labelled `assumption`. The DEAP fit/export path is intentionally absent. |

## Comparison with historical exports

No `00_markedshare_with_amazon*.csv`, `01_b2b_forecast_complete.csv`, `02_parcel_volumen_estimation_complete.csv`, or `03_yearly_weekly_parcel_forecast.csv` exists in the checked-out repository or the supplied read-only source directory, so a numeric file-to-file diff was not possible.

The source-formula comparison found these intentional differences from the historical export workflow:

- 2024–2028 volume values remain visible as `legacy_estimate`, but default fitting excludes them. The historical notebook fitted them as if they were input values.
- Outputs classify B2B anchors, interpolation, forecasts, and assumptions explicitly rather than using the notebook's display labels.
- The weekly profile restricts raw input to exactly 52 unique numeric weeks and renormalizes after averaging. This makes the later week-53 calendar rule explicit.
- The same function produces each fit candidate and its exported series; the historical notebook had separate fit and export paths.

The reconstructed B2B 2024 bounded-sigmoid value is `0.2209639502`, independently calculated from the source-cell formula and now asserted by test.

## RED / GREEN record

1. Added `tests/test_baseline_series.py`; `python -B -m pytest tests/test_baseline_series.py -q` initially failed with missing `hagrid_demand.baseline.series` and `hagrid_demand.baseline.sources` modules (5 failures).
2. Implemented package-resource loading, series reconstruction, raw weekly preparation, and manifest-verified foundation reading; the focused test suite passed (6 tests).
3. Added a failure-mode test for a volume fit with one observed point. It failed because the logistic fitter raised an unhandled support error. Added minimum-support checks and explicit `fit_failed` status; focused tests passed (7 tests).
4. Added an independent B2B forecast assertion. It failed at `0.2`, exposing that the new fit had lost notebook 01's 2009-relative year coordinate. Restored the coordinate; focused tests passed (7 tests).

## Validation

- `python -B -m pytest tests/test_baseline_series.py -q` — 7 passed.
- `python -B -m pytest -q` — 88 passed. Two pre-existing `pyproj`/NumPy deprecation warnings arise in `tests/test_logistics_osm.py`.
- Built and installed a wheel into an external temporary directory, then imported the installed package outside the repository. Package-resource JSON loading and the 2021 B2B/volume anchors passed.
- Audited the read-only `Parcels19_20_21_inter.xlsx`: 52 rows, minimum factor `0.7917076773460529`, mean `0.9999999999999998`, SHA-256 `5f1bf2d4dddda8de815bae648fe3b4a15ac12c84da0d2d03e554eec3469e2bb6`.
- `git diff --check` passed.

## Files

- `hagrid-demand/src/hagrid_demand/baseline/sources.py`
- `hagrid-demand/src/hagrid_demand/baseline/series.py`
- `hagrid-demand/src/hagrid_demand/baseline/data/market_inputs.json`
- `hagrid-demand/src/hagrid_demand/baseline/data/b2b_inputs.json`
- `hagrid-demand/src/hagrid_demand/baseline/data/volume_inputs.json`
- `hagrid-demand/src/hagrid_demand/baseline/data/provider_priors.json`
- `hagrid-demand/tests/test_baseline_series.py`
- `hagrid-demand/pyproject.toml`

## Self-review and concerns

- Reviewed the data dependency path: package JSON is loaded through package resources; source preparation reads only the declared workbook or a manifest-verified foundation path; no repository-relative output is consumed.
- `read_foundation` intentionally rejects legacy foundation runs that lack `artifact_manifest.json` with artifact SHA-256 values and declared schemas. A later foundation-producing task must write that manifest before foundation-mode baseline runs can use those artifacts.
- Historical export CSVs were unavailable, so the comparison records formula-level and classification-level differences rather than a numeric export diff.
