# HAGRID2MATSim Pipeline - Setup Tutorial

This tutorial explains how to set up the HAGRID2MATSim Pipeline on a new machine and which data files need to be placed where.

---

## 📁 Folder Structure

Ensure the following folder structure exists in the `parcel-demand-2-matsim-pipeline/` directory:

```
parcel-demand-2-matsim-pipeline/
│
├── input/
│   ├── config.xml
│   ├── HAGRID_vehicleTypes2.0.xml
│   │
│   ├── demand/
│   │   ├── basecase_12052025/
│   │   ├── basecase_13052025/
│   │   ├── batchModerate_13052025/
│   │   └── ...
│   │
│   ├── geodata/
│   │   └── Region Hannover.*
│   │
│   └── hubs/
│       ├── KEP-hubs_v3.csv
│       ├── standorte_von_dhl.de.csv
│       └── standorte_von_paket.net/
│           ├── dhl_paketnet_list.csv
│           ├── dpd_paketnet_list.csv
│           ├── gls_paketnet_list.csv
│           ├── hermes_paketnet_list.csv
│           └── ups_paketnet_list.csv
│
├── sim-input/
│   ├── network/
│   │   └── car_network_filtered_V2.xml.gz
│   ├── algorithm.xml
│   └── algo_V2.xml
│
├── output/                 ← created automatically
├── routerCache/            ← created automatically
└── src/                    ← Java source code
```

---

## 📦 Required Files in Detail

### 1. Configuration Files (`input/`)

| File | Description | Format |
|------|-------------|--------|
| `config.xml` | MATSim main configuration | XML |
| `HAGRID_vehicleTypes2.0.xml` | Vehicle type definitions (capacity, costs, etc.) | XML |

> ⚠️ **Important:** These files are already included in the repository and usually do not need to be modified.

---

### 2. Parcel Demand Data (`input/demand/`)

The parcel demand is provided as a **Shapefile**. Each scenario (concept + date) requires its own subfolder.

#### Folder Naming Convention
```
<concept>_<date in DDMMYYYY format>/
```

**Examples:**
- `basecase_13052025/` → Base case for May 13, 2025
- `batchHigh_14052025/` → BatchHigh scenario for May 14, 2025

#### Shapefile Naming Convention Inside Folder
```
hagrid_parcel_demand_<YYYY-MM-DD>_(<Weekday>).*
```

**Example for `basecase_13052025/`:**
```
basecase_13052025/
├── hagrid_parcel_demand_2025-05-13_(Tuesday).cpg
├── hagrid_parcel_demand_2025-05-13_(Tuesday).dbf
├── hagrid_parcel_demand_2025-05-13_(Tuesday).prj
├── hagrid_parcel_demand_2025-05-13_(Tuesday).shp
└── hagrid_parcel_demand_2025-05-13_(Tuesday).shx
```

#### Available Concepts
| Concept | Description |
|---------|-------------|
| `basecase` | Base case (Status Quo) |
| `batchModerate` | Moderate batching |
| `batchMedium` | Medium batching |
| `batchHigh` | High batching |
| `batchFull` | Maximum batching |

> 💡 **Tip:** These shapefiles are generated using the notebooks in the `parcel-demand-estimation/` folder.

---

### 3. Geodata for Region Filter (`input/geodata/`)

| File | Description |
|------|-------------|
| `Region Hannover.shp` | Shapefile of the study region |
| `Region Hannover.dbf` | Attribute table |
| `Region Hannover.prj` | Projection (EPSG:25832) |
| `Region Hannover.shx` | Shapefile index |

These files define the geographic area for which the simulation is performed.

---

### 4. Hub and Location Data (`input/hubs/`)

#### CEP Hubs (Distribution Centers)
| File | Description |
|------|-------------|
| `KEP-hubs_v3.csv` | Locations of all CEP distribution centers (DHL, Hermes, DPD, etc.) |

**Expected CSV Columns:**
- Provider
- Name
- Coordinates (X, Y in EPSG:25832)
- Capacity

#### Parcel Lockers/Parcel Shops
| File | Description |
|------|-------------|
| `standorte_von_dhl.de.csv` | DHL Packstations and parcel shops |

#### Shipping Points per Provider (`standorte_von_paket.net/`)
| File | Provider |
|------|----------|
| `dhl_paketnet_list.csv` | DHL |
| `dpd_paketnet_list.csv` | DPD |
| `gls_paketnet_list.csv` | GLS |
| `hermes_paketnet_list.csv` | Hermes |
| `ups_paketnet_list.csv` | UPS |

---

### 5. Road Network (`sim-input/network/`)

| File | Description |
|------|-------------|
| `car_network_filtered_V2.xml.gz` | MATSim network (compressed) |

> ⚠️ **Important:** The network must be in coordinate system **EPSG:25832**!

---

## ⚙️ Configuration in Java Code

Open the file `src/main/java/hagrid/HAGRID2MATSimPipelineRunner.java` and adjust the configuration block (around lines 47-72):

```java
PipelineConfig config = PipelineConfig.builder()
        // Select desired concepts
        .concepts(List.of(
                // "batchHigh",
                // "batchMedium",
                // "batchModerate",
                "basecase"))
        
        // Select simulation dates
        .dates(List.of(
                LocalDate.of(2025, 5, 13)
                // LocalDate.of(2025, 5, 14),
                // LocalDate.of(2025, 5, 15)
        ))
        
        // Enable service simplifier? (reduces computation time)
        .applyServiceSimplifier(false)
        
        // Vehicle sizes: "m" (medium), "l" (large)
        .cepVehicleSizes(List.of("m", "l"))
        
        // Provider-specific settings
        .providerVehicleSizes("amazon", List.of("l"))
        .deliveryWindow("default", 7, 14)      // Default: 7am-2pm
        .deliveryWindow("amazon", 9, 17)       // Amazon: 9am-5pm
        
        // Filter by region
        .filterRegions("Hannover")
        .build();
```

---

## 🚀 Running the Pipeline

### Prerequisites
- Java 17+ (recommended: Java 21)
- Maven 3.8+
- Minimum 16 GB RAM (recommended: 32 GB)

### Execution

**Option 1: Via Maven**
```bash
cd parcel-demand-2-matsim-pipeline
mvn clean compile exec:java -Dexec.mainClass="hagrid.HAGRID2MATSimPipelineRunner"
```

**Option 2: Via Batch File (Windows)**
```bash
run_hagrid_sim.bat
```

---

## 📤 Output Files

After successful execution, you will find the results in:

```
parcel-demand-2-matsim-pipeline/output/
├── <CONCEPT>_<DATE>_delivery_carriers.xml         ← Delivery carriers
├── <CONCEPT>_<DATE>_delivery_carriers_merged_services.xml
├── <CONCEPT>_<DATE>_supply_carriers.xml           ← Supply carriers
├── <CONCEPT>_<DATE>_split_supply_carriers.xml
└── <CONCEPT>_<DATE>_types.xml                     ← Vehicle types
```

Logs are saved in:
```
parcel-demand-2-matsim-pipeline/output/logs/<RUN_ID>_<TIMESTAMP>/runner.log
```

---

## 📋 Pre-Start Checklist

- [ ] `input/config.xml` present
- [ ] `input/HAGRID_vehicleTypes2.0.xml` present
- [ ] Demand shapefile in correct folder (`input/demand/<concept>_<date>/`)
- [ ] Network file present (`sim-input/network/car_network_filtered_V2.xml.gz`)
- [ ] Hub data present (`input/hubs/KEP-hubs_v3.csv`)
- [ ] Shipping point data present (`input/hubs/standorte_von_paket.net/*.csv`)
- [ ] Geodata for region present (`input/geodata/Region Hannover.*`)
- [ ] Java 17+ installed
- [ ] Maven 3.8+ installed
- [ ] Concept and date configured in Runner

---

