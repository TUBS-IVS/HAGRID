# Lausitz Input Data — Staging Guide

This document records the source paths and staging commands for the raw Lausitz/Hoyerswerda input
files consumed by the `parcel-demand-2-matsim-pipeline` HAGRID scenarios. All staged files are
**git-ignored** (covered by the `parcel-demand-2-matsim-pipeline/hagrid-input/**` rule in
`.gitignore`) and must be reproduced locally by running the copy commands below.

## Staged Files

| Destination (under `parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/`) | Source |
|---|---|
| `network/lausitz-network.xml.gz` | `~/Documents/GitHub/PANDA/DatenPaketmengen/Lausitz/network/lausitz-v2024.2-network-with-pt.xml.gz` |
| `population/lausitz-100pct.plans.xml.gz` | `~/Downloads/lausitz-v2024.2-100pct.plans-initial.xml.gz` |
| `drt/drt-service-area.shp` (+ `.dbf/.shx/.prj/.cpg`) | `~/Documents/GitHub/matsim-lausitz/input/drt-area/hoyerswerda-ruhland_Bhf-utm32N.*` |
| `config/lausitz-v2024.2-100pct.config.xml` | `~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-100pct.config.xml` |

## Copy Commands (Git Bash)

```bash
HI="parcel-demand-2-matsim-pipeline/hagrid-input/lausitz"
mkdir -p "$HI"/{network,population,drt,config}
cp ~/Documents/GitHub/PANDA/DatenPaketmengen/Lausitz/network/lausitz-v2024.2-network-with-pt.xml.gz "$HI/network/lausitz-network.xml.gz"
cp ~/Downloads/lausitz-v2024.2-100pct.plans-initial.xml.gz "$HI/population/lausitz-100pct.plans.xml.gz"
for e in shp dbf shx prj cpg; do cp ~/Documents/GitHub/matsim-lausitz/input/drt-area/hoyerswerda-ruhland_Bhf-utm32N.$e "$HI/drt/drt-service-area.$e"; done
cp ~/Documents/GitHub/matsim-lausitz/input/v2024.2/lausitz-v2024.2-100pct.config.xml "$HI/config/lausitz-v2024.2-100pct.config.xml"
```

## Notes

- The `lausitzBaseConfig()` getter in `HagridPaths.java` resolves
  `hagrid-input/lausitz/config/lausitz-v2024.2-100pct.config.xml` under the study-area-scoped
  `inputBase`.
- All staged files are large binaries and must never be committed to the repository.
- The `.gitignore` rule `parcel-demand-2-matsim-pipeline/hagrid-input/**` covers all contents of
  this directory (with exceptions only for subdirectory structure and `.gitkeep` files).
