# Lausitz Input Data — Staging Guide

This document records the source paths and staging commands for the raw Lausitz/Hoyerswerda input
files consumed by the `parcel-demand-2-matsim-pipeline` HAGRID scenarios. All staged files are
**git-ignored** (covered by the `parcel-demand-2-matsim-pipeline/hagrid-input/**` rule in
`.gitignore`) and must be reproduced locally by running the copy commands below.

## Staged Files

| Destination (under `parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/`) | Source |
|---|---|
| `demand/hagrid_parcel_demand_2025-05-13_(Tuesday).shp` (+ `.dbf/.shx/.prj/.cpg`) | `~/Documents/GitHub/PANDA/output/lausitz/` (PANDA export) |
| `hubs/lmd-depots.csv` | Authored: 7 provisional per-LSP depot coords (EPSG:25832, derived from PANDA demand point spread); replace with finalized peripheral Gewerbegebiet/Autobahn locations before any headline LMD run |
| `vehicles/lmd-vehicle-types.xml` | Subset (vans `ct_cep_size_m`/`_l` only) of `sim-input/carrier/BASECASE_13052025_carrier_files/BASECASE_13052025_vehicle_types.xml` |
| `network/lausitz-network.xml.gz` | `~/Documents/GitHub/PANDA/DatenPaketmengen/Lausitz/network/lausitz-v2024.2-network-with-pt.xml.gz` |
| `population/lausitz-100pct.plans.xml.gz` | `~/Downloads/lausitz-v2024.2-100pct.plans-initial.xml.gz` |
| `drt/drt-service-area.shp` (+ `.dbf/.shx/.prj/.cpg`) | `~/Documents/GitHub/matsim-lausitz/input/drt-area/hoyerswerda-ruhland_Bhf-utm32N.*` |
| `config/lausitz-v2024.2-100pct.config.xml` | `~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-100pct.config.xml` |
| `transit/lausitz-transitSchedule.xml.gz` | `~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-transitSchedule.xml.gz` |
| `transit/lausitz-transitVehicles.xml.gz` | `~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-transitVehicles.xml.gz` |
| `vehicles/lausitz-vehicle-types.xml` | `~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-vehicle-types.xml` |

## Copy Commands (Git Bash)

```bash
HI="parcel-demand-2-matsim-pipeline/hagrid-input/lausitz"
mkdir -p "$HI"/{network,population,drt,config,demand,hubs,vehicles}
# PANDA parcel demand (LMD baseline)
cp ~/Documents/GitHub/PANDA/output/lausitz/hagrid_parcel_demand_2025-05-13_\(Tuesday\).{shp,dbf,shx,prj,cpg} "$HI/demand/"
# LMD depots CSV and van vehicle-types (tracked in git via force-add exception)
# lmd-depots.csv: PROVISIONAL coords — replace with finalized Gewerbegebiet/Autobahn depot locations
# lmd-vehicle-types.xml: subset of BASECASE_13052025_vehicle_types.xml (ct_cep_size_m / _l only)
cp ~/Documents/GitHub/PANDA/DatenPaketmengen/Lausitz/network/lausitz-v2024.2-network-with-pt.xml.gz "$HI/network/lausitz-network.xml.gz"
cp ~/Downloads/lausitz-v2024.2-100pct.plans-initial.xml.gz "$HI/population/lausitz-100pct.plans.xml.gz"
for e in shp dbf shx prj cpg; do cp ~/Documents/GitHub/matsim-lausitz/input/drt-area/hoyerswerda-ruhland_Bhf-utm32N.$e "$HI/drt/drt-service-area.$e"; done
cp ~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-100pct.config.xml "$HI/config/lausitz-v2024.2-100pct.config.xml"
mkdir -p "$HI"/{transit,vehicles}
cp ~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-transitSchedule.xml.gz "$HI/transit/lausitz-transitSchedule.xml.gz"
cp ~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-transitVehicles.xml.gz "$HI/transit/lausitz-transitVehicles.xml.gz"
cp ~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-vehicle-types.xml "$HI/vehicles/lausitz-vehicle-types.xml"
```

## Notes

- The `lausitzBaseConfig()` getter in `HagridPaths.java` resolves
  `hagrid-input/lausitz/config/lausitz-v2024.2-100pct.config.xml` under the study-area-scoped
  `inputBase`.
- All staged files are large binaries and must never be committed to the repository.
- The `.gitignore` rule `parcel-demand-2-matsim-pipeline/hagrid-input/**` covers all contents of
  this directory (with exceptions only for subdirectory structure and `.gitkeep` files).
