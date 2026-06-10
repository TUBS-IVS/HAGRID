# Debug-Tab: Building-Weighted Parcel Distribution PoC

**Date:** 2026-06-10  
**Status:** Approved  
**Scope:** Proof of concept — two hardcoded PLZs (30890, 30159)

---

## Goal

Compare the existing linear street-length-based parcel distribution against a building-footprint-weighted distribution on the 100m INSPIRE grid, to validate the new approach and eliminate artifacts (e.g., forest path near Barsinghausen receiving parcels in PLZ 30890).

---

## Architecture

```
build_debug_cache.py  (one-time preprocessing, ~2–5 min)
│
├── osmnx: load building polygons for PLZ 30890 + 30159
├── Per 100m grid cell within PLZ:
│   ├── Intersect cell polygon with building polygons
│   ├── cell_weight = Σ (footprint_area_m² × levels_factor)
│   │     levels_factor per building type:
│   │       house / detached / terraced  → building:levels (default 1)
│   │       apartments / residential     → building:levels (default 3)
│   │       commercial / retail          → 0.5
│   │       warehouse / industrial       → 0  (excluded)
│   │       unknown / other              → 1
│   ├── b2b_weight  = Σ commercial/retail footprint × 0.5
│   ├── b2c_weight  = Σ residential footprint × levels_factor
│   └── b2b_anteil  = b2b_weight / (b2b_weight + b2c_weight)  [NaN if no buildings]
│
├── Normalize within PLZ: weight_norm = cell_weight / Σ_PLZ(cell_weight)
├── neue_methode  = PLZ_total_real_tagesschnitt × weight_norm
├── alte_methode  = existing real_tagesschnitt column (unchanged)
└── differenz     = neue_methode − alte_methode
│
└── Output → .spatial_cache/debug_buildings.parquet
    Columns: GITTER_ID_100m, PLZ, alte_methode, neue_methode,
             differenz, b2b_anteil, lat, lon

app.py  (minimal additions)
├── Startup: load debug_buildings.parquet if present (silent skip if absent)
├── New Tab 3: "Debug-Ansicht" added to tab row
├── Tab content:
│   ├── PLZ selector: radio or small dropdown [30890 | 30159]
│   ├── Mode radio: [Alte Methode | Neue Methode | Differenz | B2B-Anteil]
│   └── scatter_map on 100m grid (same style as existing Raster-Ansicht)
└── New callback: filter debug_df by PLZ, render selected mode
```

---

## Visualization

| Mode | Column | Colorscale |
|---|---|---|
| Alte Methode | `alte_methode` | `YlOrRd` |
| Neue Methode | `neue_methode` | `YlOrRd` |
| Differenz | `differenz` | `_SCALE_DEV_GRID` (blue=neue<alte, red=neue>alte) |
| B2B-Anteil | `b2b_anteil` | Sequential blue→red (0%=B2C, 100%=B2B), gray for NaN |

---

## Weighting Formula

```
cell_weight = Σ_buildings_in_cell (footprint_area_m² × levels_factor)

b2b_anteil  = b2b_weight / (b2b_weight + b2c_weight)
            = NaN  if (b2b_weight + b2c_weight) == 0
```

This approximates gross floor area as a proxy for parcel demand density.  
`building:levels` defaults: apartments → 3, all others → 1.

---

## Scope Constraints

- **Two hardcoded PLZs only:** 30890 (artifact test), 30159 (density/height test)
- **OSM only:** No ALKIS in this PoC; ALKIS+OSM combination deferred to production version
- **Pipeline unchanged:** `data_loader.py` and `estimator.py` are not modified
- **No parameter tuning:** Weighting factors (0.5 for commercial, default levels) are hardcoded constants — no sliders
- **B2B-Anteil is structural only:** Derived from building types, not calibrated against real B2B data

---

## Files Changed

| File | Change |
|---|---|
| `build_debug_cache.py` | New file |
| `.spatial_cache/debug_buildings.parquet` | Generated artifact (gitignored) |
| `app.py` | Add Tab 3 + one new callback + load debug parquet at startup |

No other files are modified.

---

## Success Criteria

1. PLZ 30890: Forest path cells receive weight ≈ 0 in neue Methode (no buildings in buffer)
2. PLZ 30159: Multi-story apartment cells receive higher weight than single-family areas
3. Differenz view clearly shows redistribution pattern
4. B2B-Anteil view highlights commercial streets vs. residential streets
5. Existing PLZ-Ansicht and Raster-Ansicht tabs are unaffected
