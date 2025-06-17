# Parcel Demand Scenario Generator for Hannover (2014–2050)

This repository provides a scientific tool to project and allocate daily parcel demand across the Hannover region from **2014 to 2050**.  
By integrating multiple data sources (national and local) and applying different modeling approaches, we derive **realistic carrier-level and B2B/B2C parcel shares** at **street-segment granularity (~50 m intervals)**.

The tool generates synthetic, yet empirically grounded, **daily parcel delivery datasets** for every single day between 2014 and 2050.  
While projections are technically available for the full time range, the results are considered **most reliable up to approximately 2030**, assuming a moderately stable market evolution without major disruptive events.

In addition to demand estimation, the repository also supports the **integration of parcel flows into the agent-based simulation framework MATSim**.  
A dedicated workflow is under development to:
- convert demand datasets into MATSim-compatible formats
- generate routed delivery plans for simulation
- evaluate delivery traffic patterns under varying network and policy conditions

> 🛠️ This MATSim integration is currently a work in progress and will be documented in the corresponding subfolders (`phd/`, `phd-sim/phd/`) as it evolves.

These scenario datasets enable users to:

- Analyze **future parcel traffic patterns** on a fine spatial and temporal scale  
- Evaluate and test **innovative delivery concepts and urban logistics infrastructure**  
- Feed parcel flows into **agent-based or GIS-based simulation models**  
- Support **strategic planning, policy evaluation, and scenario design** for last-mile delivery

In essence, this tool offers a flexible, data-driven foundation to study the future of parcel logistics in an urban context under various assumptions and development paths.

## Table of Contents

1. [Overview & Key Features](#1-overview--key-features)  
2. [Repository Structure](#2-repository-structure)  
3. [Data Sources](#3-data-sources)  
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
├── parcel-analysis/
│   └── Jupyter notebooks and scripts for analyzing simulation outputs and performance indicators.

├── parcel-demand-2-matsim-pipeline/
│   ├── HAGRIDSimulationRunner.java
│   ├── HAGRID2MATSimPipelineRunner.java
│   └── Java-based MATSim integration for running parcel delivery simulations and converting external demand data.

├── parcel-demand-estimation/
│   ├── 00_EstimateGlobalGermanParcelMarketShares.ipynb
│   ├── 01_EstimateGlobalGermanB2BShares.ipynb
│   ├── 02_EstimateGlobalGermanParcelVolumes.ipynb
│   ├── 03_EstimateWeeklyParcelDistribution.ipynb
│   ├── 04_EstimateLocalB2BDistribution.ipynb
│   ├── 05_EstimateLocalMarketShares.ipynb
│   ├── 06_DistributeEstimationWeightsPerSegment.ipynb
│   ├── ParcelDemandScenarioGenerator.ipynb
│   └── output/
│       └── parcel_demand_2050-04-09_(Samstag).csv

├── requirements.txt  
└── README.md
```

- The folder `parcel-demand-estimation/` contains all notebooks used to estimate and allocate parcel demand from national trends down to street segment level.
- The folder `parcel-demand-2-matsim-pipeline/` is used to prepare the demand in MATSim-compatible formats, generate routing input, and run full simulation scenarios.
- Documentation of the MATSim integration and postprocessing pipeline will be added step-by-step in the corresponding subfolders.


- **Notebooks 00–06**: Each focuses on one part of the pipeline (global shares, B2B ratio, volumes, weekly distribution, local adaptations, and segment-level weighting).  
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


  ## 4. Installation

Clone this repository:

```
git clone https://github.com/YourUserName/ParcelDemandScenarioGenerator.git
```

Navigate into the project folder:

```
cd ParcelDemandScenarioGenerator
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








