# HAGRID — Parcel demand and integrated freight/DRT simulation with MATSim

HAGRID bündelt zwei Studien auf einem gemeinsamen Kern:

- **Hannover** — Paketnachfrage (2014–2050) auf Straßenabschnittsebene und Last-Mile-Delivery-Simulation mit jsprit + MATSim, inkl. Kapazitäts-Sweep (`analysis/hannover/sweep`).
- **Lausitz (Hoyerswerda)** — integrierte Personen- und Paketbedienung mit DRT: Baseline, Cargo Hitching (1c, `drt_shareduse`) und Kapseltausch (1d, `drt_modular`), KPI-Dashboard v2 (`analysis/lausitz/kpi`). Studiendokumentation in `docs/` (`DATA-LAUSITZ.md`, `PAPER-RUNS.md`, `METHODS-LOG.md`).
- **Kern** — Geo-, Nachfrage- und Routing-Werkzeuge, Root-Erkennung, Simulationsverdrahtung (`hagrid.core`).

Die Trennung ist als Import-Regel festgeschrieben: `hagrid/src/test/java/hagrid/core/ArchitectureRulesTest.java`.

For Hannover, this means projecting and allocating daily parcel demand across the region from **2014 to 2050** at **street-segment granularity (~50 m intervals)**, deriving **realistic carrier-level and B2B/B2C parcel shares**, generating synthetic yet empirically grounded **daily parcel delivery datasets**, and simulating last-mile delivery with jsprit and MATSim — enabling analysis of future parcel traffic patterns, evaluation of delivery concepts and urban logistics infrastructure, and policy-relevant scenario design. While projections are technically available for the full 2014–2050 range, results are considered **most reliable up to approximately 2030**, assuming a moderately stable market evolution without major disruptive events. For Lausitz, this means the integrated person- and parcel-service DRT simulation described above.

## Table of Contents

1. [Overview & Key Features](#1-overview--key-features)  
2. [Repository Structure](#2-repository-structure)  
3. [Data Sources](#3-data-sources)  
- [Setup](#setup) (freight submodule / clone & bootstrap)  
4. [Installation](#4-installation)  
5. [Notebook Workflow](#5-notebook-workflow)  
6. [Supported Output Formats](#6-supported-output-formats)  
7. [Example Output](#7-example-parcel_demand_2050-04-09_samstagcsv)  
8. [Limitations & Assumptions](#8-limitations--assumptions)  
9. [License](#9-license)  
10. [Contributing](#10-contributing)  
11. [Contact](#11-contact)


## 1. Overview & Key Features

- **Time Horizon**: 2014–2050 for the Hannover region  
- **Granularity**: Splits the region’s street network into ~50 m segments  
- **Carrier-Level**: Disaggregates volumes among DHL, Hermes, UPS, DPD, GLS, FedEx/TNT, and (optionally) Amazon Logistics  
- **B2B/B2C Segmentation**: Uses a declining B2B share model (bounded sigmoid) to reflect long-term trends  
- **Weekly/Daily Distributions**: Incorporates realistic seasonality patterns (weekdays/weekends)  
- **Flexible Growth Models**: Linear, exponential, and logistic volume projections  
- **Multiple Output Formats**: CSV, Shapefile, GeoPackage, GeoJSON (for GIS or simulation frameworks)

## 2. Repository Structure

```
HAGRID/
├── README.md
├── pom.xml                    Parent-POM; Module: external/freight + hagrid
├── hagrid/                    ein Maven-Modul (Pakete hagrid.core / hannover / lausitz)
│   ├── src/main/java/hagrid/{core,hannover,lausitz}/…
│   ├── src/test/java/hagrid/{core,hannover,lausitz}/…
│   ├── input/{common,hannover,lausitz}/   git-ignoriert, siehe hagrid/input/README.md
│   ├── hagrid-output/
│   └── hagrid-matsim-output/
├── analysis/
│   ├── common/run-monitoring/
│   ├── hannover/{sweep,legacy-figures}/
│   └── lausitz/{kpi,drt-headline,paper-figures,lmd}/
├── notebooks/
│   ├── demand-estimation/
│   ├── demand-estimation-batch/
│   └── hannover-analysis/
├── runs/
│   ├── hannover/           run_analysis.bat, run_hagrid_sim*.bat, run_step*.bat, run_chain_v2dev.bat
│   └── lausitz/            track_sweep.ps1, alle übrigen .bat/.ps1; Einmalskripte unter campaigns/
├── external/
│   ├── matsim-libs/        Submodul (Fork), Pfad unverändert
│   ├── freight/            POM-Shim
│   └── libs/                matsim-lausitz-Jar
├── tools/                  resync-freight.ps1, setup_hagrid_io.bat, migrate-input-layout.ps1, check-run-scripts.ps1
├── requirements.txt
└── docs/
```

- `hagrid/` ist das einzige Maven-Modul; Java-Quellen sind entlang der drei Wurzelpakete `hagrid.core`, `hagrid.hannover`, `hagrid.lausitz` sortiert, Inputs liegen unter `hagrid/input/` (git-ignoriert).
- `analysis/` enthält die Python-Auswertungen, getrennt nach `common` (studienübergreifend, z. B. Run-Monitoring), `hannover` und `lausitz`.
- `runs/` enthält die Windows-Startskripte, getrennt nach Studie; jedes Skript wechselt selbst in den richtigen Ordner.
- `notebooks/` enthält die Jupyter-Notebooks zur Nachfrageschätzung (Hannover).
- `external/` bündelt Fremdcode: den `matsim-libs`-Fork als Submodul, den `freight`-POM-Shim und die `libs`-Jars.
- `tools/` enthält Hilfsskripte für Setup, Migration und statische Prüfungen, die kein Studien-spezifischer Run sind.
- `docs/` enthält die lebende Projektdokumentation (Backlog, Methods-Log, Studiendaten, Obsidian-Export) sowie die Superpowers-Specs/Pläne.

- **Notebooks 00–06** (unter `notebooks/demand-estimation/`): Each focuses on one part of the pipeline (global shares, B2B ratio, volumes, weekly distribution, local adaptations, and segment-level weighting).  
- **ParcelDemandScenarioGenerator.ipynb**: The final assembly that produces daily, segment-level demand.  
- **input/**: Stores input data (e.g., shapefiles, CSVs, geospatial layers).  
- **output/**: Default directory for exported results (CSV, SHP, GeoPackage, or GeoJSON).


## 3. Data Sources

- **Pitney Bowes Parcel Shipping Index (2023)**  
  Provides recent market shares (e.g., for 2022) and time-series changes since ~2016.

- **BIEK KEP Studies (2009–2023)**  
  Contains data on the composition of B2B vs. B2C shipments in Germany.

- **Statista & BIEK for Historical Parcel Volumes (2000–2013 / 2014–2028)**  
  Used to establish baseline annual parcel counts, including possible outlier handling (e.g. COVID effects).

- **Swiss Weekly Parcel Data (2019–2021)**  
  Used as a proxy for weekly fluctuations and seasonal delivery patterns in the absence of German data.  
  → Source: Gottschalk, F. & Lehmann, A. (2023). *Covid-19 and Swiss Post: Volume Developments and the Economic Value of Postal Service, in the Pandemic and Beyond.* In P. L. Parcu, T. J. Brennan & V. Glass (Eds.), *The Postal and Delivery Contribution in Hard Times* (pp. 207–222). Springer. https://doi.org/10.1007/978-3-031-11413-7_14

- **Local Geodata (Hannover)**  
  - Street network shapefiles or MATSim network  
  - Population and company locations derived from the MATSim Hanover model  
    → Source: Bienzeisler, L., Lelke, T., Wage, O., Thiel, F., & Friedrich, B. (2020). *Development of an Agent-Based Transport Model for the City of Hanover Using Empirical Mobility Data and Data Fusion.* Transportation Research Procedia, 47, 99–106. https://doi.org/10.1016/j.trpro.2020.03.073. Extended these datasets from follow-up developments of the MATSim Hanover Region model  
  - Carrier-specific parcel demand data (e.g., DHL datasets – not publicly available)


## Setup

The freight module sources live in a git submodule (`external/matsim-libs`, a patched
fork of matsim-libs — see `docs/superpowers/specs/2026-07-13-freight-fork-submodule-design.md`).

**Fresh clone:**

    git config --global core.longpaths true   # Windows only, required once
    git clone --recurse-submodules https://github.com/TUBS-IVS/HAGRID.git
    cd HAGRID
    git -C external/matsim-libs sparse-checkout set contribs/freight examples/scenarios/logistics-2regions   # optional, trims ~1 GB of unrelated contribs
    mvn install

**Existing clone (after pulling this change):**

    git submodule update --init external/matsim-libs
    git -C external/matsim-libs sparse-checkout set contribs/freight examples/scenarios/logistics-2regions

**Bumping the MATSim/freight version:** see `tools/resync-freight.ps1` (header comment).

**Inputs:** `hagrid/input/` ist git-ignoriert; Aufbau und Herkunft in `hagrid/input/README.md`.
Checkouts von vor dem 2026-09-17 einmal `tools/migrate-input-layout.ps1` ausführen.

**Runs:** alle Startskripte liegen unter `runs/hannover/` und `runs/lausitz/`; sie wechseln selbst
in den richtigen Ordner. `tools/check-run-scripts.ps1` prüft sie statisch gegen das gebaute Jar.
`runs/hannover/run_hagrid_sim.bat` ist die Campaign-Referenzkopie; zur Laufzeit erzeugt
`SimulationBatGenerator` die tatsächlich ausgeführte Kopie unter `hagrid/`.

**IDE stale-build gotcha:** if Eclipse or VS Code's Java tooling has compiled a broken
workspace (e.g. mid-refactor), stale `.class` stubs left behind in `target/classes` can
shadow the fresh build output and produce confusing failures. `mvn clean` clears them out.

  ## 4. Installation

Clone this repository:

```
git clone --recurse-submodules https://github.com/TUBS-IVS/HAGRID.git
```

See the `## Setup` section above for the full bootstrap (submodule sparse-checkout, Windows longpaths).

Navigate into the project folder:

```
cd HAGRID/notebooks/demand-estimation
```

Install the required Python dependencies:

```
pip install -r requirements.txt
```

> 💡 It is recommended to execute the notebooks in sequential order:  
> `00_` → `06_`, followed by `ParcelDemandScenarioGenerator.ipynb`.

## 5. Notebook Workflow

Each notebook builds on the results of the previous ones. The general workflow moves from national-level parcel data to localized, street-level demand estimations for each day and carrier.

---

## 6. Supported Output Formats

The final daily scenario files can be exported to various GIS-compatible formats:

- **CSV** (`.csv`) – with geometries in WKT (Well-Known Text) format
- **Shapefile** (`.shp`)
- **GeoPackage** (`.gpkg`)
- **GeoJSON** (`.geojson`)

## 7. Example: `parcel_demand_2050-04-09_(Samstag).csv`

Each row represents a single ~50 m street segment on a given date. All parcel volumes for that segment—split by carrier and by B2B/B2C type—are provided in the same row. Geometries are included.

**Example columns:**

| Column        | Description                                                                 |
|---------------|-----------------------------------------------------------------------------|
| `fid`         | Internal feature ID                                                         |
| `str_idx`     | Index of the original street name (useful for grouping)                     |
| `name`        | Name of the street segment                                                  |
| `plz`         | Postal code                                                                 |
| `cell_id`     | ID of the corresponding grid cell                                           |
| `total_sim`   | Total number of parcels on the segment on the given date                   |
| `DHL_b2b` – `DHL_b2c` | Carrier-specific parcel volumes (B2B/B2C), e.g., DHL, Hermes, UPS, etc. |
| `geometry`    | WKT (Well-Known Text) representation of the segment geometry                |
| `date`        | Simulation date (e.g., `2050-04-09`)                                        |

**Note:**  
- Carrier columns follow the format: `<CarrierAbbreviation>_b2b` and `<CarrierAbbreviation>_b2c`  
  Examples: `DHL_b2c`, `Her_b2b`, `Ama_b2c`, `FXT_b2b`, `UPS_b2c`  
- The file can be directly loaded into GIS tools such as QGIS or ArcGIS.

## 8. Limitations & Assumptions

- **No Dynamic Urban Development:**  
  The model creates synthetic daily demand scenarios using randomized weights per day.  
  It does not represent actual urban growth, infrastructure changes, or long-term city development between 2014 and 2050.

- **Carrier Landscape Stability:**  
  The model assumes a consistent set of parcel carriers throughout the projection period. Emerging or disappearing carriers are not explicitly modeled.

- **Synthetic Localization:**  
  Local B2B/B2C distributions and carrier shares are calibrated using available references and spatial proxies, but not based on complete ground-truth data.

- **Weekly and Seasonal Patterns:**  
  Weekly parcel fluctuations are based on Swiss data (2019–2021) and include the effects of COVID-19.  
  While German holidays are not explicitly modeled, the annual structure is dominated by end-of-year peaks (e.g., Black Friday and Christmas), which are strongly aligned across countries.  
  Therefore, the Swiss pattern is considered transferable to the German context.

- **Parcel Growth Modeling:**  
  Long-term volume growth follows fitted curves (e.g., logistic) that flatten toward 2050 to avoid unrealistic extrapolation. COVID-related outliers are smoothed.

- **Data Availability:**  
  Some internal data (e.g., DHL reference volumes) are not publicly available and must be substituted or removed in open use.

## 9. License

This project is licensed under the **Creative Commons Attribution 4.0 International (CC BY 4.0)** license.

You are free to:
- **Share** — copy and redistribute the material in any medium or format
- **Adapt** — remix, transform, and build upon the material for any purpose, even commercially.

Under the following terms:
- **Attribution** — You must give appropriate credit, provide a link to the license, and indicate if changes were made.

Full license text:  
[https://creativecommons.org/licenses/by/4.0/](https://creativecommons.org/licenses/by/4.0/)


## 10. Contributing

Contributions are welcome and encouraged!

If you would like to suggest improvements, report issues, or add new features:

1. Fork the repository
2. Create a new branch
3. Make your changes and commit them
4. Open a Pull Request with a clear description

For larger changes or questions, feel free to open an Issue beforehand to discuss your ideas.

---

## 11. Contact

**Maintainer:** Dr.Ing Lasse Bienzeisler
**Institution:** Technische Universität Braunschweig, Institut für Verkehr und Stadtbauwesen  
**Email:** l.bienzeisler@tu-braunschweig.de  
**Website:** [https://www.tu-braunschweig.de/ivs](https://www.tu-braunschweig.de/ivs)

---

## Thank You!

We hope this project helps researchers, logistics planners, and data scientists better understand and simulate parcel flows in the Hannover region.  
If you find this tool useful, feel free to **star the repository** ⭐ and share it with others interested in urban logistics and long-term scenario planning.

> 🚧 *Note: This repository is still under active development.*  
> Notebooks, data structures, and results may evolve over time as we refine methods and extend the projection logic.  
> We are currently reorganizing the imports, cleaning up modules, and working toward a unified structure across all notebooks.  
> Feedback is always welcome!

Stay tuned – more documentation, examples, and validation will follow soon.








