# hagrid/demand — Parcel Demand for Region Hannover

The demand side of HAGRID. From the LSP street volumes of 2021, population, firms and OSM buildings, the model builds the
parcel demand of every day of 2025–2035 per stop and carrier, as MATSim input for `hagrid/simulation`. The method, the
scenarios and the results are summarised in the [project README](../../README.md#6-hannover-demand-model-20252035).

| Folder | Content | Versioned |
|---|---|---|
| `model/` | Python package `hagrid_demand`: street anchor, buildings, stops, shipping day and transit time, pickup points, land use, annual store, dashboards and tests. Start with [`model/README.md`](model/README.md). | yes |
| `input/hannover/` | Raw data (`raw/`), OSM extracts (`osm/`) and notebook outputs (`notebook-output/`) | no (git-ignored) |
| `runs/` | Runs: reference, daily files, annual store and `annual_dashboard.html`; the decade runs `decade-*` and `decade_dashboard.html` | no (git-ignored) |
| `archive/notebooks/` | The notebook chain 00–06 and the generators that provided the national series and profiles; superseded by the model | yes |

## Quick Start

Install and test the package in `hagrid/demand/model`:

```powershell
cd hagrid/demand/model
python -m pip install -e ".[test]"
python -m pytest -q
```

Then run the model from the repository root:

```powershell
tools\migrate-demand-input.ps1                  # once: move local inputs into the current layout
runs\hannover\run_demand_year.bat demand-2025   # one year plus MATSim demand in hagrid/simulation/input/hannover/demand/demand-2025/
runs\hannover\run_demand_decade.bat             # 2025–2035 in the volume scenarios plus the decade dashboard
```

[`model/README.md`](model/README.md) describes the further commands (`export-day`, `annual-dashboard`, `decade-dashboard`,
`osm-parcel-points`, `osm-transit`, `osm-boundaries`) and every model assumption with its source. Specifications and
implementation plans are in [`docs/demand/`](../../docs/demand/).

## Multi-Year Projection

`years` can hold several years. The configurations `model/configs/decade-{trend,saettigung,boom}.json` simulate 2025–2035
with different growth of the national parcel volume, chained at the 2025 level, and a pickup network that grows with the
demand (new parcel lockers, boxes and counters at supermarkets, fuel stations and kiosks). Every run writes an annual
dashboard per year, and all scenarios together feed one decade dashboard. Details and assumptions:
[`model/README.md`](model/README.md#multi-year-projection-20252035).

With `land_use`, persons and firms are redistributed as well. Persons follow the official population forecast 2025–2035
per forecast district, the age mix ages with a cohort effect on the online propensity, and firms grow by industry.
Development areas (Kronsberg-Süd, Wasserstadt Limmer and others) and new firms become sites of their own.
`decade-trend-innen.json` and `decade-trend-suburban.json` run the trend volume with the infill and the suburban variant.
Details: [`model/README.md`](model/README.md#land-use-dynamics-20252035).

## Data Flow to MATSim

`baseline run` writes `hagrid_parcel_demand_<date>_(<weekday>).shp` for every configured day, with one row per stop
(`stop_type` `home`, `locker`, `shared_locker`, `counter` or `shop`) and the parcels per carrier and segment.
`run_demand_year.bat` copies the files to `hagrid/simulation/input/hannover/demand/<run-id>/`, where
`HagridPaths.demandDir(runId)` expects them. `export-day` writes any other day of the year from the annual store.
