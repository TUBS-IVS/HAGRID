# HAGRID Straßen-Anker, OSM-Gebäude und Stopps – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Baseline verankert die Nachfrage auf DHL-Straßen, verteilt sie auf OSM-Gebäude und bündelt sie zu stabilen, adaptiven Stopps für den MATSim-Export.

**Architecture:** Eine neue Stage `buildings` ordnet Personen und Firmen OSM-Gebäuden, DHL-Straßen und 50-m-Abschnitten zu. Die Referenzstage erhält einen Straßenmodus (`anchor.mode = street`), der Pegelkorrektur, DHL-Zerlegung, DHL-fixe Anbieterabstimmung und die Verteilung auf Gebäude rechnet und dasselbe Referenzartefakt-Schema wie bisher liefert, ergänzt um Straßen-, Stopp- und Ankerartefakte. Projektion, Tagesgenerator und Export konsumieren diese Artefakte; der bestehende PLZ-Modus bleibt für alte Configs erhalten.

**Tech Stack:** Python ≥ 3.11, pandas, geopandas, shapely 2 (vektorisierte Linearreferenzierung), pyogrio/GDAL-OSM-Treiber, scipy (`nnls`), pytest.

**Spec:** `docs/superpowers/specs/2026-09-24-hagrid-street-anchor-buildings-design.md`

## Global Constraints

- Paketwurzel: `hagrid-demand/`; alle Pfade unten relativ dazu. Tests: `python -B -m pytest -q -p no:cacheprovider` (Laufzeit ~3 min).
- CRS aller räumlichen Artefakte: EPSG:25832; Zensuszellen aus EPSG:3035 als `100mN{floor(y/100)}E{floor(x/100)}`.
- DHL-Schwelle: Beobachtungen mit Wert > 1000 sind ausgeschlossen (bestehender Meilensteinvertrag `dhl_exclude_above: 1000`).
- Pegelkorrektur-Standard: `min_persons=30`, `min_streets=20`, `upper=2.0`, `lower=0.5`.
- Rückfall-Schwelle: Strukturerwartung ≥ 5 DHL-Pakete/Tag bei DHL = 0.
- Gebäude: Ausschlusstypen `garage, garages, roof, hut, shed, carport, greenhouse, barn, farm_auxiliary, parking, construction, ruins, bunker, toilets, transformer_tower`; Personen bis 65 m, Firmenkandidaten bis 100 m in derselben Zensuszelle, Firmen-Rückfall bis 250 m.
- Straßenzuordnung: Name+PLZ (bis 500 m), sonst nächste DHL-Straße bis 100 m.
- Stopps: `walking_radius_m=40`, `own_stop_parcels_per_day=15`, `max_parcels_per_row=400`, `section_length_m=50`.
- DHL-B2B: q_DHL(y) = q_DHL(2021) · b(y)/b(2021); übrige Anbieter per `reconcile_carriers` in den Notebook-05-Grenzen; unzulässig → `ValueError`.
- Firmengewicht-Standard: `business_potential.model = company_locations`.
- Keine zusätzliche Infrastruktur-Härtung (Locks, Caches, Überlauf-Sonderfälle) ohne konkreten Fehler; höchstens zwei Review-Runden je Aufgabe.
- Jede Aufgabe endet mit einer fachlichen Plausibilitätsprüfung der Ergebnisse (Nutzerauftrag: „immer schauen, dass die Ergebnisse passen“).

## Review Focus

1. Gebäude mit Einwohnern und Firmen zugleich (gemischte Nutzung) – beide Segmente müssen am selben Gebäude/Stopp erscheinen und im Export in einer Zeile summiert werden.
2. DHL-Straßen mit mehreren Teilstücken (MultiLineString) und gleichnamige Straßen in derselben PLZ – Zuordnung und Projektion müssen das nächstgelegene Teilstück nehmen.
3. Straßen mit DHL > 0 ohne zugeordnete Gebäude und Gebäude ohne DHL-Straße – Menge darf nicht verloren gehen (synthetische Punkte bzw. Rückfall) und muss im Bericht erscheinen.
4. Tagesmengen über 400 an einem Stopp – Export teilt in mehrere Zeilen am selben Punkt mit eindeutiger `id`, Summen bleiben gleich.
5. Alte Configs ohne `osm_buildings` – laufen unverändert im PLZ-Modus (alle bestehenden Tests grün).

---

## File Structure

| Datei | Verantwortung |
|---|---|
| `src/hagrid_demand/baseline/osm.py` (neu) | Tag-Parsing, Zuschnitt eines Geofabrik-PBF auf die Region |
| `src/hagrid_demand/baseline/buildings.py` (neu) | Gebäude laden, Personen/Firmen zuordnen, Adressen, DHL-Straßen, Teilstücke, Abschnitte, Seite |
| `src/hagrid_demand/baseline/anchor.py` (neu) | Straßentabelle, Pegelkorrektur, DHL-Raten, Zerlegung, Holdout, `solve_street_reference` |
| `src/hagrid_demand/baseline/stops.py` (neu) | adaptive Stopps (S3) |
| `src/hagrid_demand/baseline/projection.py` | DHL-fixe Anbieterabstimmung je Jahr |
| `src/hagrid_demand/compatibility/matsim_export.py` | Export je Stopp, Teilzeilen, `id`, `str_idx`, `section_id`, Ledger |
| `src/hagrid_demand/baseline/workflow.py` | Stage `buildings`, Straßenmodus der Referenz, Artefakte, Tageslauf-Verdrahtung |
| `src/hagrid_demand/baseline/config.py` | neue Config-Schlüssel |
| `src/hagrid_demand/baseline/dashboard.py` | Ankerabschnitt in Bericht und `report.md` |
| `src/hagrid_demand/cli.py` | `baseline osm-clip` |
| `configs/baseline-reference.json`, `configs/baseline-daily.json` | Straßenmodus mit OSM-Pfaden |
| `tests/street_fixtures.py` (neu) | kleine Gebäude/Straßen/Personen/Firmen-Fixture inkl. OSM-Parquet |
| `tests/test_osm_clip.py`, `tests/test_buildings.py`, `tests/test_anchor.py`, `tests/test_stops.py`, `tests/test_street_workflow.py` (neu) | Tests |

---

### Task 0: Bestehenden Stand verifizieren und committen

**Files:**
- Modify: `src/hagrid_demand/baseline/workflow.py` (`_business_employee_weight`)
- Modify: `configs/baseline-daily.json`, `README.md`
- Test: `tests/test_baseline_workflow.py`

**Interfaces:** Produces: `_business_employee_weight(config) -> float | None` mit Standard `company_locations` (→ `None`).

- [ ] **Step 1: Failing test** – in `tests/test_baseline_workflow.py` ergänzen:

```python
def test_default_business_potential_is_one_unit_per_company(fixture_config):
    from hagrid_demand.baseline.workflow import _business_employee_weight

    assert _business_employee_weight({}) is None
    assert _business_employee_weight({"business_potential": {"model": "company_plus_employees"}}) == 0.1
```

- [ ] **Step 2:** `python -B -m pytest -q -p no:cacheprovider tests/test_baseline_workflow.py -k business_potential` → FAIL (Default liefert 0.1).
- [ ] **Step 3: Implementation** – in `workflow.py`:

```python
def _business_employee_weight(config: dict) -> float | None:
    spec = config.get("business_potential", {"model": "company_locations"})
    if not isinstance(spec, dict) or spec.get("model") not in {"company_plus_employees", "company_locations"}:
        raise ValueError("business_potential.model must be company_plus_employees or company_locations")
    return float(spec.get("employee_weight", 0.1)) if spec["model"] == "company_plus_employees" else None
```

In `configs/baseline-daily.json` `"business_potential": {"model": "company_locations"}` setzen; README-Zeile „Gewerbegewicht“ auf „1 je Firma (DHL-Straßencheck: Beschäftigte ohne Erklärungskraft); `company_plus_employees` optional“ ändern.
- [ ] **Step 4:** Gesamte Testsuite → PASS.
- [ ] **Step 5: Commit** (Kalender, NB05-Grenzen, Amazon-Normierung, MATSim-Export, Tagesconfig, Firmengewicht):

```bash
git add hagrid-demand/README.md hagrid-demand/configs/baseline-daily.json hagrid-demand/src hagrid-demand/tests
git commit -m "feat: wire calendar, notebook B2B bounds and MATSim day export"
```

---

### Task 1: OSM-Zuschnitt als Paketfunktion und CLI

**Files:**
- Create: `src/hagrid_demand/baseline/osm.py`
- Modify: `src/hagrid_demand/cli.py`
- Test: `tests/test_osm_clip.py`

**Interfaces:**
- Produces: `parse_other_tags(value) -> dict[str, str]`; `expand_tags(frame) -> DataFrame` (Spalten `addr_street`, `addr_housenumber`, `addr_postcode`, `building_levels`, `building_use`, `shop`, `office`, `amenity`, `craft`, `industrial`, `healthcare`); `clip_osm_region(pbf: Path, plz_csv: Path, out_dir: Path, buffer_m: float = 250.) -> dict`; Konstanten `BUILDINGS_FILE = "osm_buildings_region_hannover_2021.parquet"`, `POINTS_FILE = "osm_address_poi_region_hannover_2021.parquet"`, `MANIFEST_FILE = "osm_region_hannover_2021_manifest.json"`.

- [ ] **Step 1: Failing test** `tests/test_osm_clip.py`:

```python
import json

import geopandas as gpd
import pandas as pd
from shapely.geometry import box


OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
 <node id="1" lat="52.3700" lon="9.7300"/><node id="2" lat="52.3700" lon="9.7302"/>
 <node id="3" lat="52.3702" lon="9.7302"/><node id="4" lat="52.3702" lon="9.7300"/>
 <node id="11" lat="53.0000" lon="9.7300"/><node id="12" lat="53.0000" lon="9.7302"/>
 <node id="13" lat="53.0002" lon="9.7302"/><node id="14" lat="53.0002" lon="9.7300"/>
 <node id="5" lat="52.3701" lon="9.7301"><tag k="addr:street" v="Teststraße"/><tag k="addr:housenumber" v="1"/><tag k="shop" v="bakery"/></node>
 <way id="10"><nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/><tag k="building" v="retail"/><tag k="addr:street" v="Teststraße"/><tag k="addr:housenumber" v="1"/></way>
 <way id="20"><nd ref="11"/><nd ref="12"/><nd ref="13"/><nd ref="14"/><nd ref="11"/><tag k="building" v="house"/></way>
</osm>"""


def test_clip_keeps_region_buildings_with_tags_and_writes_manifest(tmp_path):
    from hagrid_demand.baseline.osm import BUILDINGS_FILE, MANIFEST_FILE, POINTS_FILE, clip_osm_region

    pbf = tmp_path / "tiny.osm"
    pbf.write_text(OSM, encoding="utf-8")
    region = gpd.GeoSeries([box(9.72, 52.36, 9.74, 52.38)], crs=4326).to_crs(25832)
    pd.DataFrame({"postal_cod": ["30159"], "geometry": [region.iloc[0].wkt]}).to_csv(tmp_path / "plz.csv", index=False)

    manifest = clip_osm_region(pbf, tmp_path / "plz.csv", tmp_path / "out", buffer_m=0)

    buildings = gpd.read_parquet(tmp_path / "out" / BUILDINGS_FILE)
    points = gpd.read_parquet(tmp_path / "out" / POINTS_FILE)
    assert buildings.osm_way_id.tolist() == ["10"]
    assert buildings.loc[0, "addr_street"] == "Teststraße" and buildings.loc[0, "building"] == "retail"
    assert buildings.crs.to_epsg() == 25832
    assert points.loc[0, "shop"] == "bakery" and points.loc[0, "addr_housenumber"] == "1"
    assert manifest["files"][BUILDINGS_FILE] == 1
    assert json.loads((tmp_path / "out" / MANIFEST_FILE).read_text(encoding="utf-8"))["license"].startswith("ODbL")


def test_parse_other_tags_handles_escaped_quotes():
    from hagrid_demand.baseline.osm import parse_other_tags

    assert parse_other_tags('"a"=>"1","name"=>"x \\"y\\""') == {"a": "1", "name": 'x \\"y\\"'}
    assert parse_other_tags(None) == {}
```

- [ ] **Step 2:** `python -B -m pytest -q -p no:cacheprovider tests/test_osm_clip.py` → FAIL (ModuleNotFoundError).
- [ ] **Step 3: Implementation** `src/hagrid_demand/baseline/osm.py`:

```python
"""Clip a Geofabrik OSM extract to the HAGRID study region (buildings and address/POI points)."""

from __future__ import annotations

import hashlib
import json
import re
import time
from pathlib import Path

import geopandas as gpd
import pandas as pd
import pyogrio
from shapely import wkt


BUILDINGS_FILE = "osm_buildings_region_hannover_2021.parquet"
POINTS_FILE = "osm_address_poi_region_hannover_2021.parquet"
MANIFEST_FILE = "osm_region_hannover_2021_manifest.json"
TAG_KEYS = ("addr:street", "addr:housenumber", "addr:postcode", "building:levels", "building:use",
            "shop", "office", "amenity", "craft", "industrial", "healthcare")
_PAIR = re.compile(r'"((?:[^"\\]|\\.)*)"=>"((?:[^"\\]|\\.)*)"')


def parse_other_tags(value) -> dict[str, str]:
    if not isinstance(value, str):
        return {}
    return dict(_PAIR.findall(value))


def expand_tags(frame: pd.DataFrame) -> pd.DataFrame:
    frame = frame.copy()
    tags = frame["other_tags"].map(parse_other_tags) if "other_tags" in frame else pd.Series([{}] * len(frame), index=frame.index)
    for key in TAG_KEYS:
        values = tags.map(lambda item, key=key: item.get(key))
        if key in frame.columns:
            values = frame[key].where(frame[key].notna(), values)
        frame[key.replace(":", "_")] = values
    return frame


def study_region(plz_csv: Path, buffer_m: float):
    plz = pd.read_csv(plz_csv)
    plz = gpd.GeoDataFrame(plz, geometry=plz.geometry.map(wkt.loads), crs="EPSG:25832")
    return plz.union_all().buffer(buffer_m), len(plz)


def clip_osm_region(pbf: Path, plz_csv: Path, out_dir: Path, buffer_m: float = 250.) -> dict:
    started = time.time()
    pbf, out_dir = Path(pbf), Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    region, plz_count = study_region(Path(plz_csv), buffer_m)
    bbox = tuple(gpd.GeoSeries([region], crs=25832).to_crs(4326).total_bounds)
    pyogrio.set_gdal_config_options({"OSM_MAX_TMPFILE_SIZE": "4000", "OSM_USE_CUSTOM_INDEXING": "YES"})
    buildings = pyogrio.read_dataframe(pbf, layer="multipolygons", bbox=bbox, where="building IS NOT NULL")
    buildings = expand_tags(buildings).to_crs(25832)
    buildings = buildings[buildings.intersects(region)].copy()
    buildings["area_m2"] = buildings.area
    keep = ["osm_id", "osm_way_id", "building", "name", "landuse", *[key.replace(":", "_") for key in TAG_KEYS], "area_m2", "geometry"]
    buildings = buildings[[column for column in keep if column in buildings.columns]].reset_index(drop=True)
    buildings.to_parquet(out_dir / BUILDINGS_FILE, index=False)
    points = expand_tags(pyogrio.read_dataframe(pbf, layer="points", bbox=bbox)).to_crs(25832)
    relevant = points["addr_housenumber"].notna() | points[["shop", "office", "amenity", "craft"]].notna().any(axis=1)
    points = points[relevant & points.intersects(region)]
    keep_points = ["osm_id", "name", *[key.replace(":", "_") for key in TAG_KEYS], "geometry"]
    points = points[[column for column in keep_points if column in points.columns]].reset_index(drop=True)
    points.to_parquet(out_dir / POINTS_FILE, index=False)
    manifest = {
        "source": str(pbf.name), "source_md5": hashlib.md5(pbf.read_bytes()).hexdigest(),
        "license": "ODbL 1.0, (c) OpenStreetMap contributors",
        "region": f"union of {plz_count} postal areas buffered by {buffer_m} m, EPSG:25832",
        "files": {BUILDINGS_FILE: int(len(buildings)), POINTS_FILE: int(len(points))},
        "building_types": {str(k): int(v) for k, v in buildings.building.value_counts().head(25).items()},
        "share_buildings_with_address": round(float(buildings.addr_housenumber.notna().mean()) if len(buildings) else 0., 4),
        "runtime_s": round(time.time() - started, 1),
    }
    (out_dir / MANIFEST_FILE).write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return manifest
```

In `cli.py` einen Unterbefehl `osm-clip` im `baseline`-Parser ergänzen (Argumente `--pbf`, `--plz`, `--out`, `--buffer-m`, Default 250) und im Dispatch vor den `run`-Zweig:

```python
            if args.baseline_command == "osm-clip":
                from .baseline.osm import clip_osm_region
                print(json.dumps(clip_osm_region(args.pbf, args.plz, args.out, args.buffer_m), indent=2, ensure_ascii=False))
                return
```

- [ ] **Step 4:** Test → PASS. Plausibilität: Befehl auf echtem PBF ausführen und Manifest mit dem Scratch-Ergebnis vergleichen (253.918 Gebäude, 111.423 Punkte).
- [ ] **Step 5: Commit** `feat: clip Geofabrik OSM extract to the study region`.

---

### Task 2: Gebäude laden, Personen und Firmen zuordnen

**Files:**
- Create: `src/hagrid_demand/baseline/buildings.py`
- Create: `tests/street_fixtures.py`
- Test: `tests/test_buildings.py`

**Interfaces:**
- Produces: `EXCLUDED_TYPES: tuple[str, ...]`; `load_buildings(frame: gpd.GeoDataFrame, exclude_types=EXCLUDED_TYPES) -> gpd.GeoDataFrame` (Spalten `building_key`, `building_type`, `area_m2`, `addr_street`, `addr_housenumber`, `poi`, `geometry`); `zensus_cell(points: gpd.GeoSeries) -> pd.Series`; `assign_private(sites, buildings, max_distance_m=65.) -> DataFrame[site_id, building_key, stage]`; `assign_firms(sites, buildings, residents: pd.Series, seed: int, candidate_distance_m=100., fallback_distance_m=250.) -> DataFrame[site_id, building_key, stage, fit]`.
- `stage` ∈ {`within`, `nearest`, `point`} (privat) bzw. {`cell`, `fallback`, `point`} (Firmen); Punktschlüssel `pt:<site_id>`.

- [ ] **Step 1: Fixture** `tests/street_fixtures.py`:

```python
"""Tiny street/building world: two DHL streets, four buildings, persons and firms (EPSG:25832)."""

from __future__ import annotations

import geopandas as gpd
import pandas as pd
from shapely.geometry import LineString, Point, box

CRS = "EPSG:25832"
X0, Y0 = 550_000.0, 5_800_000.0


def osm_buildings() -> gpd.GeoDataFrame:
    rows = [
        {"osm_way_id": "1", "osm_id": None, "building": "house", "addr_street": "Aweg", "addr_housenumber": "1", "shop": None,
         "geometry": box(X0 + 10, Y0 + 5, X0 + 20, Y0 + 15)},
        {"osm_way_id": "2", "osm_id": None, "building": "apartments", "addr_street": "Aweg", "addr_housenumber": "3", "shop": None,
         "geometry": box(X0 + 60, Y0 + 5, X0 + 75, Y0 + 20)},
        {"osm_way_id": "3", "osm_id": None, "building": "retail", "addr_street": None, "addr_housenumber": None, "shop": "bakery",
         "geometry": box(X0 + 110, Y0 - 25, X0 + 130, Y0 - 5)},
        {"osm_way_id": "4", "osm_id": None, "building": "garage", "addr_street": None, "addr_housenumber": None, "shop": None,
         "geometry": box(X0 + 30, Y0 + 5, X0 + 34, Y0 + 9)},
        {"osm_way_id": "5", "osm_id": None, "building": "industrial", "addr_street": "Bstraße", "addr_housenumber": "9", "shop": None,
         "geometry": box(X0 + 400, Y0 + 5, X0 + 450, Y0 + 40)},
    ]
    return gpd.GeoDataFrame(rows, geometry="geometry", crs=CRS)


def sites() -> gpd.GeoDataFrame:
    rows = [
        {"site_id": "res:a", "segment": "private", "population": 2, "employees": None, "branch": None, "plz": "01000", "geometry": Point(X0 + 15, Y0 + 10)},
        {"site_id": "res:b", "segment": "private", "population": 6, "employees": None, "branch": None, "plz": "01000", "geometry": Point(X0 + 70, Y0 + 12)},
        {"site_id": "res:c", "segment": "private", "population": 1, "employees": None, "branch": None, "plz": "01000", "geometry": Point(X0 + 900, Y0 + 900)},
        {"site_id": "biz:x", "segment": "business", "population": 0, "employees": 5, "branch": "G", "plz": "01000", "geometry": Point(X0 + 118, Y0 - 30)},
        {"site_id": "biz:y", "segment": "business", "population": 0, "employees": 40, "branch": "C", "plz": "01000", "geometry": Point(X0 + 420, Y0 + 45)},
    ]
    return gpd.GeoDataFrame(rows, geometry="geometry", crs=CRS)


def streets() -> gpd.GeoDataFrame:
    return gpd.GeoDataFrame({
        "sid": [0, 1], "plz": ["01000", "01000"], "street": ["Aweg", "Bstraße"], "value": [4.0, 3.0],
        "geometry": [LineString([(X0, Y0), (X0 + 200, Y0)]), LineString([(X0 + 380, Y0), (X0 + 500, Y0)])],
    }, crs=CRS)


def postal() -> gpd.GeoDataFrame:
    return gpd.GeoDataFrame({"plz": ["01000"], "geometry": [box(X0 - 100, Y0 - 100, X0 + 1000, Y0 + 1000)]}, crs=CRS)
```

- [ ] **Step 2: Failing tests** `tests/test_buildings.py`:

```python
import numpy as np
import pandas as pd

import street_fixtures as fx


def test_load_buildings_drops_irrelevant_types_and_marks_pois():
    from hagrid_demand.baseline.buildings import load_buildings

    b = load_buildings(fx.osm_buildings())
    assert b.building_key.tolist() == ["osm:w1", "osm:w2", "osm:w3", "osm:w5"]
    assert b.set_index("building_key").loc["osm:w3", "poi"]
    assert (b.area_m2 > 0).all()


def test_zensus_cell_matches_laea_100m_grid():
    import geopandas as gpd
    from shapely.geometry import Point
    from hagrid_demand.baseline.buildings import zensus_cell

    cell = zensus_cell(gpd.GeoSeries([Point(4305350, 3251050)], crs=3035))
    assert cell.tolist() == ["100mN32510E43053"]


def test_private_sites_go_to_containing_or_nearest_building_or_stay_points():
    from hagrid_demand.baseline.buildings import assign_private, load_buildings

    result = assign_private(fx.sites(), load_buildings(fx.osm_buildings())).set_index("site_id")
    assert result.loc["res:a", ["building_key", "stage"]].tolist() == ["osm:w1", "within"]
    assert result.loc["res:b", "building_key"] == "osm:w2"
    assert result.loc["res:c", ["building_key", "stage"]].tolist() == ["pt:res:c", "point"]


def test_firms_prefer_fitting_buildings_in_their_cell_and_are_reproducible():
    from hagrid_demand.baseline.buildings import assign_firms, load_buildings

    buildings = load_buildings(fx.osm_buildings())
    residents = pd.Series({"osm:w1": 2, "osm:w2": 6})
    first = assign_firms(fx.sites(), buildings, residents, seed=7).set_index("site_id")
    second = assign_firms(fx.sites(), buildings, residents, seed=7).set_index("site_id")
    assert first.equals(second)
    assert first.loc["biz:x", "building_key"] in {"osm:w3", "osm:w2", "osm:w1"}
    assert first.loc["biz:y", ["building_key", "stage"]].tolist() == ["osm:w5", "cell"]
    assert set(first.stage) <= {"cell", "fallback", "point"}
```

Hinweis zu `biz:x`: Die Zensuszelle entscheidet über Kandidaten; der Test prüft nur Reproduzierbarkeit und zulässige Menge. `biz:y` liegt 5 m neben der Industriehalle in derselben Zelle.

- [ ] **Step 3:** Tests → FAIL (ModuleNotFoundError).
- [ ] **Step 4: Implementation** `src/hagrid_demand/baseline/buildings.py` (Teil 1):

```python
"""Demand locations on OSM buildings: persons, firms, addresses, DHL streets and street sections."""

from __future__ import annotations

import re

import geopandas as gpd
import numpy as np
import pandas as pd
import shapely
from shapely.geometry import box

from hagrid_demand.common.rng import named_rng


EXCLUDED_TYPES = ("garage", "garages", "roof", "hut", "shed", "carport", "greenhouse", "barn", "farm_auxiliary", "parking",
                  "construction", "ruins", "bunker", "toilets", "transformer_tower")
RESIDENTIAL_TYPES = {"residential", "house", "apartments", "detached", "semidetached_house", "terrace", "dormitory", "bungalow"}
_BRANCH_GROUP = {"G": "retail", "I": "retail", "C": "industry", "F": "industry", "H": "industry",
                 **{code: "services" for code in "JKLMN"}, **{code: "public" for code in "OPQ"}}
_FIT_TYPES = {
    "retail": {"retail", "commercial", "supermarket", "kiosk", "hotel"},
    "industry": {"industrial", "warehouse", "manufacture", "hangar", "transportation"},
    "services": {"office", "commercial"},
    "public": {"public", "school", "university", "hospital", "civic", "government", "kindergarten"},
    "other": {"commercial", "office", "industrial"},
}
_RESIDENTIAL_FACTOR = {"retail": .5, "industry": .1, "services": .5, "public": .3, "other": .5}
_OTHER_FACTOR = .3


def load_buildings(frame: gpd.GeoDataFrame, exclude_types=EXCLUDED_TYPES) -> gpd.GeoDataFrame:
    """Relevant OSM buildings with a stable key (``osm:w<way>`` or ``osm:r<relation>``)."""
    way = frame["osm_way_id"] if "osm_way_id" in frame else pd.Series(None, index=frame.index)
    rel = frame["osm_id"] if "osm_id" in frame else pd.Series(None, index=frame.index)
    key = np.where(way.notna(), "osm:w" + way.astype(str), "osm:r" + rel.astype(str))
    poi_columns = [column for column in ("shop", "office", "amenity", "craft") if column in frame]
    result = gpd.GeoDataFrame({
        "building_key": key, "building_type": frame["building"].astype(str).to_numpy(),
        "addr_street": frame.get("addr_street", pd.Series(None, index=frame.index)).to_numpy(),
        "addr_housenumber": frame.get("addr_housenumber", pd.Series(None, index=frame.index)).to_numpy(),
        "poi": frame[poi_columns].notna().any(axis=1).to_numpy() if poi_columns else False,
    }, geometry=frame.geometry.to_numpy(), crs=frame.crs)
    result = result[~result.building_type.isin(exclude_types) & result.geometry.notna() & ~result.geometry.is_empty]
    result = result.drop_duplicates("building_key").reset_index(drop=True)
    result["area_m2"] = result.geometry.area
    return result


def zensus_cell(points: gpd.GeoSeries) -> pd.Series:
    laea = points.to_crs(3035)
    x = np.floor(laea.x.to_numpy() / 100).astype("int64")
    y = np.floor(laea.y.to_numpy() / 100).astype("int64")
    return pd.Series([f"100mN{b}E{a}" for a, b in zip(x, y)], index=points.index)


def _cell_polygons(cells: pd.Series, crs) -> gpd.GeoDataFrame:
    unique = pd.Series(cells.unique())
    parsed = unique.str.extract(r"100mN(?P<y>\d+)E(?P<x>\d+)").astype("int64")
    shapes = [box(x * 100, y * 100, x * 100 + 100, y * 100 + 100) for x, y in zip(parsed.x, parsed.y)]
    return gpd.GeoDataFrame({"cell": unique}, geometry=shapes, crs=3035).to_crs(crs)


def assign_private(sites: gpd.GeoDataFrame, buildings: gpd.GeoDataFrame, max_distance_m: float = 65.) -> pd.DataFrame:
    private = sites.loc[sites.segment.eq("private"), ["site_id", "geometry"]]
    joined = gpd.sjoin(private, buildings[["building_key", "geometry"]], predicate="within", how="left")
    joined = joined.sort_values(["site_id", "building_key"]).drop_duplicates("site_id")
    result = pd.DataFrame({"site_id": joined.site_id.to_numpy(), "building_key": joined.building_key.to_numpy()})
    result["stage"] = np.where(result.building_key.notna(), "within", None)
    missing = result.building_key.isna()
    if missing.any():
        near = gpd.sjoin_nearest(private[private.site_id.isin(result.loc[missing, "site_id"])],
                                 buildings[["building_key", "geometry"]], max_distance=max_distance_m,
                                 how="left", distance_col="distance_m")
        near = near.sort_values(["site_id", "distance_m", "building_key"]).drop_duplicates("site_id").set_index("site_id")
        result.loc[missing, "building_key"] = result.loc[missing, "site_id"].map(near.building_key).to_numpy()
        result.loc[missing & result.building_key.notna(), "stage"] = "nearest"
    still = result.building_key.isna()
    result.loc[still, "building_key"] = "pt:" + result.loc[still, "site_id"]
    result.loc[still, "stage"] = "point"
    return result


def _fit(group: str, building_type: str, poi: bool, residents: float) -> float:
    if poi or building_type in _FIT_TYPES[group]:
        return 1.
    if building_type in RESIDENTIAL_TYPES or (building_type == "yes" and residents > 0):
        return _RESIDENTIAL_FACTOR[group]
    return _OTHER_FACTOR


def assign_firms(sites: gpd.GeoDataFrame, buildings: gpd.GeoDataFrame, residents: pd.Series, seed: int,
                 candidate_distance_m: float = 100., fallback_distance_m: float = 250.) -> pd.DataFrame:
    firms = sites.loc[sites.segment.eq("business"), ["site_id", "branch", "geometry"]].reset_index(drop=True)
    firms["cell"] = zensus_cell(firms.geometry).to_numpy()
    firms["group"] = firms.branch.map(_BRANCH_GROUP).fillna("other")
    cells = _cell_polygons(firms.cell, buildings.crs)
    in_cell = gpd.sjoin(buildings[["building_key", "building_type", "poi", "area_m2", "geometry"]], cells, predicate="intersects")
    pairs = firms[["site_id", "cell", "group", "geometry"]].merge(
        pd.DataFrame(in_cell.drop(columns="geometry").assign(bgeom=in_cell.geometry.to_numpy())), on="cell")
    pairs["distance_m"] = shapely.distance(shapely.points(np.c_[pairs.geometry.x, pairs.geometry.y]) if False else
                                           gpd.GeoSeries(pairs.geometry).to_numpy(), pairs.bgeom.to_numpy())
    pairs = pairs[pairs.distance_m <= candidate_distance_m].copy()
    pairs["residents"] = pairs.building_key.map(residents).fillna(0.)
    pairs["fit"] = [_fit(g, t, p, r) for g, t, p, r in zip(pairs.group, pairs.building_type, pairs.poi, pairs.residents)]
    pairs["weight"] = pairs.area_m2 * pairs.fit
    chosen = {}
    for site_id, group in pairs.sort_values(["site_id", "building_key"]).groupby("site_id", sort=True):
        weights = group.weight.to_numpy(float)
        if weights.sum() <= 0:
            continue
        index = named_rng(int(seed), firm=str(site_id), channel="firm-building").choice(len(group), p=weights / weights.sum())
        chosen[site_id] = (group.building_key.iloc[index], "cell", float(group.fit.iloc[index]))
    result = pd.DataFrame({"site_id": firms.site_id})
    result["building_key"] = result.site_id.map(lambda s: chosen.get(s, (None,))[0])
    result["stage"] = result.site_id.map(lambda s: chosen.get(s, (None, None))[1])
    result["fit"] = result.site_id.map(lambda s: chosen.get(s, (None, None, np.nan))[2])
    missing = result.building_key.isna()
    if missing.any():
        near = gpd.sjoin_nearest(firms[firms.site_id.isin(result.loc[missing, "site_id"])][["site_id", "geometry"]],
                                 buildings[["building_key", "geometry"]], max_distance=fallback_distance_m,
                                 how="left", distance_col="distance_m")
        near = near.sort_values(["site_id", "distance_m", "building_key"]).drop_duplicates("site_id").set_index("site_id")
        result.loc[missing, "building_key"] = result.loc[missing, "site_id"].map(near.building_key).to_numpy()
        result.loc[missing & result.building_key.notna(), "stage"] = "fallback"
    still = result.building_key.isna()
    result.loc[still, "building_key"] = "pt:" + result.loc[still, "site_id"]
    result.loc[still, "stage"] = "point"
    return result
```

(Bei der Umsetzung die Distanzzeile vereinfachen: `pairs["distance_m"] = shapely.distance(pairs.geometry.to_numpy(), pairs.bgeom.to_numpy())` – `pairs.geometry` ist nach dem Merge eine Objektspalte.)

- [ ] **Step 5:** Tests → PASS. Plausibilität auf echten Daten (Scratch-Aufruf): Anteil Personen `within` ≈ 93 %, Firmen-Stufen `cell`/`fallback`/`point` ausgeben; Firmen je Gebäude-Verteilung prüfen (keine Halle mit > 200 Firmen ohne Grund).
- [ ] **Step 6: Commit** `feat: assign persons and firms to OSM buildings`.

---

### Task 3: Adressen, DHL-Straßen, Teilstücke, Abschnitte und Seiten

**Files:**
- Modify: `src/hagrid_demand/baseline/buildings.py`
- Test: `tests/test_buildings.py`

**Interfaces:**
- Produces: `normalize_street(value) -> str | None`; `street_parts(streets) -> gpd.GeoDataFrame[sid, part, length_m, geometry]`; `match_streets(points: gpd.GeoDataFrame[building_key, plz, street_norm, geometry], streets: gpd.GeoDataFrame[sid, plz, street_norm, geometry], max_distance_m=100., name_max_distance_m=500.) -> DataFrame[building_key, sid, match_stage, distance_m]` (`sid=-1`, `match_stage="none"` ohne Treffer); `project_on_streets(points: gpd.GeoDataFrame[building_key, sid, geometry], parts, section_length_m=50.) -> DataFrame[building_key, part, position_m, side, section_id, axis_x, axis_y]`.

- [ ] **Step 1: Failing tests** (anhängen):

```python
def test_normalize_street_unifies_common_spellings():
    from hagrid_demand.baseline.buildings import normalize_street

    assert normalize_street("Hans-Böckler-Straße") == normalize_street("Hans Böckler Strasse") == "hans böckler strasse"
    assert normalize_street("Alexanderstr.") == "alexanderstrasse"
    assert normalize_street("  ") is None and normalize_street(None) is None


def test_match_streets_prefers_name_then_nearest_and_projects_side_and_section():
    import geopandas as gpd
    from shapely.geometry import Point
    from hagrid_demand.baseline.buildings import match_streets, normalize_street, project_on_streets, street_parts

    streets = fx.streets().assign(street_norm=lambda f: f.street.map(normalize_street))
    points = gpd.GeoDataFrame({"building_key": ["n", "near", "far"], "plz": ["01000"] * 3,
                               "street_norm": ["bstrasse", None, None]},
                              geometry=[Point(fx.X0 + 10, fx.Y0 + 10), Point(fx.X0 + 120, fx.Y0 - 15), Point(fx.X0 + 900, fx.Y0 + 900)],
                              crs=fx.CRS)
    matched = match_streets(points, streets).set_index("building_key")
    assert matched.loc["n", ["sid", "match_stage"]].tolist() == [1, "name"]
    assert matched.loc["near", ["sid", "match_stage"]].tolist() == [0, "nearest"]
    assert matched.loc["far", ["sid", "match_stage"]].tolist() == [-1, "none"]

    located = points.assign(sid=points.building_key.map(matched.sid)).query("sid >= 0")
    projected = project_on_streets(located, street_parts(streets)).set_index("building_key")
    assert projected.loc["near", "side"] == "right" and projected.loc["near", "section_id"] == "0-0-2"
    assert abs(projected.loc["near", "position_m"] - 120) < 1e-6
    assert projected.loc["n", "position_m"] == 0.0  # projection clamps to the start of Bstraße


def test_projection_uses_the_nearest_part_of_a_multilinestring_street():
    import geopandas as gpd
    from shapely.geometry import MultiLineString, Point
    from hagrid_demand.baseline.buildings import project_on_streets, street_parts

    street = gpd.GeoDataFrame({"sid": [5]}, geometry=[MultiLineString([[(0, 0), (100, 0)], [(0, 500), (100, 500)]])], crs=fx.CRS)
    point = gpd.GeoDataFrame({"building_key": ["k"], "sid": [5]}, geometry=[Point(40, 510)], crs=fx.CRS)
    projected = project_on_streets(point, street_parts(street)).iloc[0]
    assert projected.part == 1 and abs(projected.position_m - 40) < 1e-9 and projected.side == "left"
```

- [ ] **Step 2:** Tests → FAIL.
- [ ] **Step 3: Implementation** (an `buildings.py` anhängen):

```python
def normalize_street(value) -> str | None:
    if value is None or (isinstance(value, float) and np.isnan(value)):
        return None
    text = str(value).strip().lower().replace("ß", "ss")
    if not text:
        return None
    text = re.sub(r"str\.(?=\s|$)", "strasse", text)
    text = re.sub(r"[\s\-]+", " ", text).strip()
    return text or None


def street_parts(streets: gpd.GeoDataFrame) -> gpd.GeoDataFrame:
    parts = streets[["sid", "geometry"]].explode(index_parts=True).reset_index(level=1).rename(columns={"level_1": "part"})
    parts = parts.reset_index(drop=True)
    parts["part"] = parts.part.astype("int64")
    parts["length_m"] = parts.geometry.length
    return gpd.GeoDataFrame(parts, geometry="geometry", crs=streets.crs)


def match_streets(points: gpd.GeoDataFrame, streets: gpd.GeoDataFrame, max_distance_m: float = 100.,
                  name_max_distance_m: float = 500.) -> pd.DataFrame:
    frame = pd.DataFrame({"building_key": points.building_key.to_numpy(), "plz": points.plz.astype(str).to_numpy(),
                          "street_norm": points.street_norm.to_numpy(), "pgeom": points.geometry.to_numpy()})
    candidates = frame.dropna(subset=["street_norm"]).merge(
        pd.DataFrame({"sid": streets.sid.to_numpy(), "plz": streets.plz.astype(str).to_numpy(),
                      "street_norm": streets.street_norm.to_numpy(), "sgeom": streets.geometry.to_numpy()}),
        on=["plz", "street_norm"])
    candidates["distance_m"] = shapely.distance(candidates.pgeom.to_numpy(), candidates.sgeom.to_numpy())
    named = (candidates[candidates.distance_m <= name_max_distance_m]
             .sort_values(["building_key", "distance_m", "sid"]).drop_duplicates("building_key").set_index("building_key"))
    result = frame[["building_key"]].copy()
    result["sid"] = result.building_key.map(named.sid)
    result["distance_m"] = result.building_key.map(named.distance_m)
    result["match_stage"] = np.where(result.sid.notna(), "name", None)
    missing = result.sid.isna()
    if missing.any():
        near = gpd.sjoin_nearest(points[points.building_key.isin(result.loc[missing, "building_key"])][["building_key", "geometry"]],
                                 streets[["sid", "geometry"]], max_distance=max_distance_m, how="left", distance_col="distance_m")
        near = near.sort_values(["building_key", "distance_m", "sid"]).drop_duplicates("building_key").set_index("building_key")
        result.loc[missing, "sid"] = result.loc[missing, "building_key"].map(near.sid).to_numpy()
        result.loc[missing, "distance_m"] = result.loc[missing, "building_key"].map(near.distance_m).to_numpy()
        result.loc[missing & result.sid.notna(), "match_stage"] = "nearest"
    none = result.sid.isna()
    result.loc[none, "match_stage"] = "none"
    result["sid"] = result.sid.fillna(-1).astype("int64")
    return result


def project_on_streets(points: gpd.GeoDataFrame, parts: gpd.GeoDataFrame, section_length_m: float = 50.) -> pd.DataFrame:
    frame = pd.DataFrame({"building_key": points.building_key.to_numpy(), "sid": points.sid.to_numpy(),
                          "pgeom": points.geometry.to_numpy()})
    pairs = frame.merge(pd.DataFrame({"sid": parts.sid.to_numpy(), "part": parts.part.to_numpy(),
                                      "length_m": parts.length_m.to_numpy(), "lgeom": parts.geometry.to_numpy()}), on="sid")
    pairs["distance"] = shapely.distance(pairs.pgeom.to_numpy(), pairs.lgeom.to_numpy())
    best = pairs.sort_values(["building_key", "distance", "part"]).drop_duplicates("building_key").reset_index(drop=True)
    lines, pts = best.lgeom.to_numpy(), best.pgeom.to_numpy()
    position = shapely.line_locate_point(lines, pts)
    axis = shapely.line_interpolate_point(lines, position)
    before = shapely.line_interpolate_point(lines, np.maximum(position - 1., 0.))
    after = shapely.line_interpolate_point(lines, np.minimum(position + 1., best.length_m.to_numpy()))
    tx, ty = shapely.get_x(after) - shapely.get_x(before), shapely.get_y(after) - shapely.get_y(before)
    vx, vy = shapely.get_x(pts) - shapely.get_x(axis), shapely.get_y(pts) - shapely.get_y(axis)
    cross = tx * vy - ty * vx
    section = np.floor(position / section_length_m).astype("int64")
    return pd.DataFrame({
        "building_key": best.building_key, "part": best.part.astype("int64"), "position_m": position,
        "side": np.where(cross > 0, "left", "right"),
        "section_id": [f"{s}-{p}-{k}" for s, p, k in zip(best.sid, best.part, section)],
        "axis_x": shapely.get_x(axis), "axis_y": shapely.get_y(axis),
    })
```

- [ ] **Step 4:** Tests → PASS. Plausibilität echt: Anteil `name`/`nearest`/`none` ausgeben (Ziel: `none` < 3 %); Stichprobe von 20 Namens-Treffern mit Distanz > 200 m ansehen.
- [ ] **Step 5: Commit** `feat: match buildings to DHL streets, sections and sides`.

---

### Task 4: Stage `buildings` im Workflow

**Files:**
- Modify: `src/hagrid_demand/baseline/buildings.py` (Orchestrierung `build_buildings`)
- Modify: `src/hagrid_demand/baseline/workflow.py`, `src/hagrid_demand/baseline/config.py`
- Modify: `tests/street_fixtures.py` (Workflow-Fixture)
- Test: `tests/test_street_workflow.py`

**Interfaces:**
- Produces: `build_buildings(sites, osm_buildings, osm_points, streets, postal, cfg: dict, seed: int) -> tuple[gpd.GeoDataFrame, pd.DataFrame, dict]` mit Gebäudetabelle (Spalten `building_key, footprint, building_type, area_m2, plz, population, companies, employees, street_norm, sid, match_stage, distance_m, part, position_m, side, section_id, axis_x, axis_y, geometry`), Zuordnungstabelle `site_buildings` (`site_id, segment, building_key, stage`) und Bericht (Stufenanteile).
- Config: `osm_buildings`, `osm_points` (Pfade), `buildings` (dict), `anchor` (dict, `mode`), `stops` (dict); `_anchor_mode(config) -> "street" | "postal"` (Standard `street`, wenn `osm_buildings` gesetzt).
- Workflow-Artefakte: `<run>/buildings/buildings.parquet`, `site_buildings.parquet`, `buildings_report.json`.

- [ ] **Step 1: Fixture** – `tests/street_fixtures.py` um `write_street_fixture(root: Path) -> Path` ergänzen: ruft `baseline_fixtures.write_fixture(root)`, schreibt `inputs/osm_buildings.parquet` mit Gebäuden, die die Fixture-Personen (Punkte (10,10), (110,10)) und Firmen ((20,20), (120,20)) enthalten, sowie ein leeres `inputs/osm_points.parquet`, und ergänzt die Config um `"osm_buildings": "inputs/osm_buildings.parquet", "osm_points": "inputs/osm_points.parquet"`:

```python
def write_street_fixture(root):
    import json
    from pathlib import Path
    from baseline_fixtures import CRS as FIXTURE_CRS, write_fixture

    config_path = write_fixture(Path(root))
    inputs = Path(root) / "inputs"
    gpd.GeoDataFrame({
        "osm_way_id": ["1", "2", "3", "4"], "osm_id": [None] * 4,
        "building": ["house", "house", "retail", "office"],
        "addr_street": ["Alpha", "Gamma", "Beta", "Delta"], "addr_housenumber": ["1", "2", "3", "4"], "shop": [None] * 4,
    }, geometry=[box(8, 8, 12, 12), box(108, 8, 112, 12), box(18, 18, 22, 22), box(118, 18, 122, 22)], crs=FIXTURE_CRS
    ).to_parquet(inputs / "osm_buildings.parquet", index=False)
    gpd.GeoDataFrame({"osm_id": [], "addr_street": [], "addr_housenumber": [], "shop": []}, geometry=[], crs=FIXTURE_CRS
    ).to_parquet(inputs / "osm_points.parquet", index=False)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config.update({"osm_buildings": "inputs/osm_buildings.parquet", "osm_points": "inputs/osm_points.parquet"})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    return config_path
```

- [ ] **Step 2: Failing test** `tests/test_street_workflow.py`:

```python
import json

import geopandas as gpd
import pandas as pd
import pytest

from street_fixtures import write_street_fixture


def test_buildings_stage_assigns_every_person_and_firm_once(tmp_path):
    from hagrid_demand.baseline.workflow import run_baseline

    run = run_baseline(write_street_fixture(tmp_path), "street-reference")

    buildings = gpd.read_parquet(run / "buildings" / "buildings.parquet")
    mapping = pd.read_parquet(run / "buildings" / "site_buildings.parquet")
    assert buildings.population.sum() == 4 and buildings.companies.sum() == 2
    assert mapping.site_id.is_unique and set(mapping.stage) <= {"within", "nearest", "point", "cell", "fallback"}
    assert (buildings.sid >= 0).all() and set(buildings.match_stage) <= {"name", "nearest"}
    report = json.loads((run / "buildings" / "buildings_report.json").read_text(encoding="utf-8"))
    assert report["persons_in_buildings_share"] == pytest.approx(1.0)
```

- [ ] **Step 3:** Test → FAIL.
- [ ] **Step 4: Implementation**
  - `build_buildings` in `buildings.py`:

```python
def build_buildings(sites, osm_buildings, osm_points, streets, postal, cfg: dict, seed: int):
    cfg = cfg or {}
    buildings = load_buildings(osm_buildings, tuple(cfg.get("exclude_types", EXCLUDED_TYPES)))
    if len(osm_points):
        poi_points = osm_points[osm_points[[c for c in ("shop", "office", "amenity", "craft") if c in osm_points]].notna().any(axis=1)]
        hit = gpd.sjoin(poi_points[["geometry"]], buildings[["building_key", "geometry"]], predicate="within")
        buildings.loc[buildings.building_key.isin(hit.building_key), "poi"] = True
        address_points = osm_points[osm_points.addr_housenumber.notna()]
        inside = gpd.sjoin(address_points[["addr_street", "geometry"]], buildings[["building_key", "geometry"]], predicate="within")
        inside = inside.dropna(subset=["addr_street"]).sort_index().drop_duplicates("building_key").set_index("building_key")
        fill = buildings.addr_street.isna()
        buildings.loc[fill, "addr_street"] = buildings.loc[fill, "building_key"].map(inside.addr_street)
    private = assign_private(sites, buildings, float(cfg.get("residential_max_distance_m", 65.)))
    population = sites.set_index("site_id").population
    residents = private.assign(p=private.site_id.map(population)).groupby("building_key").p.sum()
    firms = assign_firms(sites, buildings, residents, seed, float(cfg.get("firm_candidate_distance_m", 100.)),
                         float(cfg.get("firm_fallback_distance_m", 250.)))
    mapping = pd.concat([private.assign(segment="private"), firms.drop(columns="fit").assign(segment="business")], ignore_index=True)
    by_site = sites.set_index("site_id")
    mapping["population"] = mapping.site_id.map(by_site.population).fillna(0.)
    mapping["employees"] = mapping.site_id.map(by_site.employees).fillna(0.)
    demand = mapping.groupby("building_key").agg(population=("population", "sum"),
                                                  companies=("segment", lambda s: int((s == "business").sum())),
                                                  employees=("employees", "sum"))
    located = buildings[buildings.building_key.isin(demand.index)].copy()
    located["footprint"] = True
    points = mapping[mapping.building_key.str.startswith("pt:")].drop_duplicates("building_key")
    point_rows = gpd.GeoDataFrame({"building_key": points.building_key.to_numpy(), "building_type": "point",
                                   "addr_street": None, "addr_housenumber": None, "poi": False, "area_m2": 0.,
                                   "footprint": False},
                                  geometry=points.site_id.map(by_site.geometry).to_numpy(), crs=sites.crs)
    table = gpd.GeoDataFrame(pd.concat([located, point_rows], ignore_index=True), geometry="geometry", crs=sites.crs)
    table = table.join(demand, on="building_key")
    table["geometry"] = table.geometry.representative_point()
    with_plz = gpd.sjoin(table[["building_key", "geometry"]], postal[["plz", "geometry"]], predicate="within", how="left")
    table["plz"] = table.building_key.map(with_plz.drop_duplicates("building_key").set_index("building_key").plz)
    site_plz = mapping.assign(plz=mapping.site_id.map(by_site.plz)).drop_duplicates("building_key").set_index("building_key").plz
    table["plz"] = table.plz.fillna(table.building_key.map(site_plz)).astype(str)
    table["street_norm"] = table.addr_street.map(normalize_street)
    lines = streets.assign(street_norm=streets.street.map(normalize_street))
    matched = match_streets(table[["building_key", "plz", "street_norm", "geometry"]], lines,
                            float(cfg.get("match_distance_m", 100.)), float(cfg.get("name_max_distance_m", 500.)))
    table = table.merge(matched, on="building_key", how="left")
    parts = street_parts(lines)
    projected = project_on_streets(table.loc[table.sid >= 0, ["building_key", "sid", "geometry"]], parts,
                                   float(cfg.get("section_length_m", 50.)))
    table = gpd.GeoDataFrame(table.merge(projected, on="building_key", how="left"), geometry="geometry", crs=sites.crs)
    total_persons = float(sites.loc[sites.segment.eq("private"), "population"].sum())
    report = {
        "buildings": int(len(table)), "footprint_share": float(table.footprint.mean()),
        "persons_in_buildings_share": float(mapping.loc[mapping.segment.eq("private") & ~mapping.building_key.str.startswith("pt:"), "population"].sum() / total_persons) if total_persons else 0.,
        "private_stages": private.stage.value_counts().to_dict(), "firm_stages": firms.stage.value_counts().to_dict(),
        "street_match_stages": table.match_stage.value_counts().to_dict(),
    }
    columns = ["building_key", "footprint", "building_type", "area_m2", "plz", "population", "companies", "employees",
               "street_norm", "sid", "match_stage", "distance_m", "part", "position_m", "side", "section_id", "axis_x", "axis_y", "geometry"]
    return table[columns], mapping[["site_id", "segment", "building_key", "stage"]], report
```

  - `config.py`: `"osm_buildings", "osm_points", "buildings", "anchor", "stops"` zu `_ALLOWED_KEYS`; `"osm_buildings", "osm_points"` zu `_PATH_KEYS`.
  - `workflow.py`:

```python
def _anchor_mode(config: dict) -> str:
    mode = (config.get("anchor") or {}).get("mode", "street" if config.get("osm_buildings") else "postal")
    if mode not in {"street", "postal"}:
        raise ValueError("anchor.mode must be street or postal")
    if mode == "street" and not (config.get("osm_buildings") and config.get("osm_points")):
        raise ValueError("anchor.mode=street requires osm_buildings and osm_points")
    return mode


def _dhl_streets(source: Path) -> gpd.GeoDataFrame:
    frame = gpd.read_parquet(source / "dhl_observations.parquet")
    frame = frame[frame.geometry_usable.astype(bool)]
    return gpd.GeoDataFrame({"sid": frame.source_row.astype("int64").to_numpy(), "plz": frame.plz.astype(str).to_numpy(),
                             "street": frame.street.astype(str).to_numpy(), "value": frame.value.astype(float).to_numpy()},
                            geometry=frame.geometry.to_numpy(), crs=frame.crs)


def _write_buildings(config: dict, source: Path, output: Path) -> None:
    from .buildings import build_buildings

    sites = gpd.read_parquet(source / "sites.parquet")
    table, mapping, report = build_buildings(
        sites, gpd.read_parquet(config["osm_buildings"]), gpd.read_parquet(config["osm_points"]),
        _dhl_streets(source), gpd.read_parquet(source / "postal_support.parquet"),
        config.get("buildings", {}), int(config["seed"]))
    table.to_parquet(output / "buildings.parquet", index=False)
    mapping.to_parquet(output / "site_buildings.parquet", index=False)
    _json(output / "buildings_report.json", report)
```

  In `run_baseline` nach der `potentials`-Stage (nur wenn `_anchor_mode(config) == "street"`):

```python
        if _anchor_mode(config) == "street":
            buildings_dependencies = {"sources": run / "sources", "osm_buildings": Path(config["osm_buildings"]),
                                      "osm_points": Path(config["osm_points"])}
            buildings_snapshot = dependency_snapshot(buildings_dependencies)
            buildings_fingerprint = stage_key("buildings", buildings_dependencies, config,
                                              {"workflow": Path(__file__), "buildings": Path(__file__).with_name("buildings.py")},
                                              dependency_snapshot=buildings_snapshot)
            resolve_stage(run, "buildings", buildings_fingerprint, cache_root=cache_root, dependencies=buildings_dependencies,
                          build=lambda output: _write_buildings(config, run / "sources", output),
                          validate=lambda output: _validate(output, ["buildings.parquet", "site_buildings.parquet", "buildings_report.json"]),
                          dependency_snapshot=buildings_snapshot)
            state["completed_stages"].append("buildings")
```

- [ ] **Step 5:** Test → PASS; Gesamtsuite → PASS (Postal-Modus unverändert).
- [ ] **Step 6: Commit** `feat: add buildings stage for OSM demand locations`.

---

### Task 5: Straßentabelle, Pegelkorrektur, DHL-Raten, Zerlegung, Holdout

**Files:**
- Create: `src/hagrid_demand/baseline/anchor.py`
- Test: `tests/test_anchor.py`

**Interfaces:**
- Produces: `AnchorConfig` (Felder `min_persons=30., min_streets=20, upper=2., lower=.5, gap_threshold=5., exclude_above=1000., section_length_m=50.`, `AnchorConfig.from_mapping(dict | None)`); `street_table(buildings, streets, cfg) -> DataFrame[sid, plz, street, value, persons, companies, buildings, excluded]`; `level_correction(table, cfg) -> DataFrame[plz, residential_streets, rate, median_rate, factor_raw, factor, applied]`; `apply_correction(table, corrections) -> DataFrame (+factor, dhl_corrected)`; `fit_dhl_rates(table) -> {"person": float, "company": float}`; `decompose(table, rates, cfg) -> DataFrame (+expected_private, expected_business, dhl_private, dhl_business, anchor_status)`; `observed_b2b_share(decomposed) -> float`; `structure_holdout(table, seed: int, folds=5) -> dict`.
- `anchor_status` ∈ {`observed`, `observed_unstructured`, `gap`, `excluded`, `zero`}.

- [ ] **Step 1: Failing tests** `tests/test_anchor.py`:

```python
import numpy as np
import pandas as pd
import pytest


def _table():
    rows = []
    for plz, rate in (("A", .06), ("B", .06), ("C", .156)):
        for index in range(25):
            persons = 100 + index
            rows.append({"sid": len(rows), "plz": plz, "street": f"s{index}", "value": rate * persons,
                         "persons": persons, "companies": 0, "buildings": 3, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "gewerbe", "value": 0.06 * 10 + 0.4 * 20,
                 "persons": 10, "companies": 20, "buildings": 4, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "luecke", "value": 0., "persons": 200, "companies": 0,
                 "buildings": 5, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "leer", "value": 7., "persons": 0, "companies": 0,
                 "buildings": 0, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "gross", "value": 5000., "persons": 5, "companies": 1,
                 "buildings": 1, "excluded": True})
    return pd.DataFrame(rows)


def test_level_correction_scales_only_extreme_postal_levels():
    from hagrid_demand.baseline.anchor import AnchorConfig, apply_correction, level_correction

    corrections = level_correction(_table(), AnchorConfig()).set_index("plz")
    assert corrections.loc["C", "applied"] and corrections.loc["C", "factor"] == pytest.approx(2.6)
    assert not corrections.loc["A", "applied"] and corrections.loc["A", "factor"] == 1.
    corrected = apply_correction(_table(), corrections.reset_index())
    assert corrected.loc[corrected.plz.eq("C"), "dhl_corrected"].sum() == pytest.approx(
        _table().loc[lambda t: t.plz.eq("C"), "value"].sum() / 2.6)


def test_rates_decomposition_statuses_and_b2b_share():
    from hagrid_demand.baseline.anchor import (AnchorConfig, apply_correction, decompose, fit_dhl_rates,
                                               level_correction, observed_b2b_share)

    cfg = AnchorConfig()
    table = apply_correction(_table(), level_correction(_table(), cfg))
    rates = fit_dhl_rates(table)
    assert rates["person"] == pytest.approx(.06, rel=1e-6) and rates["company"] == pytest.approx(.4, rel=1e-6)
    parts = decompose(table, rates, cfg).set_index("street")
    assert parts.loc["gewerbe", "dhl_business"] == pytest.approx(8.) and parts.loc["gewerbe", "dhl_private"] == pytest.approx(.6)
    assert parts.loc["luecke", "anchor_status"] == "gap" and parts.loc["luecke", "dhl_private"] == pytest.approx(12.)
    assert parts.loc["leer", "anchor_status"] == "observed_unstructured"
    assert parts.loc["gross", "anchor_status"] == "excluded"
    observed = parts[parts.anchor_status.isin(["observed", "observed_unstructured"])]
    assert (observed.dhl_private + observed.dhl_business).sum() == pytest.approx(observed.dhl_corrected.sum())
    assert 0 < observed_b2b_share(parts.reset_index()) < 1


def test_structure_holdout_reports_street_and_postal_errors():
    from hagrid_demand.baseline.anchor import AnchorConfig, apply_correction, level_correction, structure_holdout

    table = apply_correction(_table(), level_correction(_table(), AnchorConfig()))
    result = structure_holdout(table, seed=1, folds=3)
    assert set(result) == {"M0_persons", "M1_persons_companies"}
    assert result["M1_persons_companies"]["street_wmape"] <= result["M0_persons"]["street_wmape"] + 1e-9
```

- [ ] **Step 2:** Tests → FAIL.
- [ ] **Step 3: Implementation** `src/hagrid_demand/baseline/anchor.py` (Teil 1):

```python
"""Street anchor: DHL street observations -> B2C/B2B demand per street and building (spec 5.5-5.10)."""

from __future__ import annotations

from dataclasses import dataclass, fields

import numpy as np
import pandas as pd
from scipy.optimize import nnls

from hagrid_demand.common.rng import named_rng


@dataclass(frozen=True)
class AnchorConfig:
    min_persons: float = 30.
    min_streets: int = 20
    upper: float = 2.
    lower: float = .5
    gap_threshold: float = 5.
    exclude_above: float = 1000.
    section_length_m: float = 50.

    @classmethod
    def from_mapping(cls, value: dict | None) -> "AnchorConfig":
        value = dict(value or {})
        correction = value.pop("level_correction", {}) or {}
        known = {field.name for field in fields(cls)}
        merged = {key: val for key, val in {**value, **correction}.items() if key in known}
        return cls(**merged)


def street_table(buildings: pd.DataFrame, streets: pd.DataFrame, cfg: AnchorConfig) -> pd.DataFrame:
    assigned = buildings[buildings.sid >= 0]
    structure = assigned.groupby("sid").agg(persons=("population", "sum"), companies=("companies", "sum"),
                                            buildings=("building_key", "size"))
    table = streets[["sid", "plz", "street", "value"]].set_index("sid").join(structure)
    table[["persons", "companies", "buildings"]] = table[["persons", "companies", "buildings"]].fillna(0.)
    table["excluded"] = table.value > cfg.exclude_above
    return table.reset_index()


def level_correction(table: pd.DataFrame, cfg: AnchorConfig) -> pd.DataFrame:
    observed = table[~table.excluded]
    residential = observed[(observed.persons >= cfg.min_persons) & (observed.companies == 0)]
    per = residential.groupby("plz").agg(residential_streets=("sid", "size"), dhl=("value", "sum"), persons=("persons", "sum"))
    per["rate"] = per.dhl / per.persons
    median = float(per.rate.median())
    per["median_rate"] = median
    per["factor_raw"] = per.rate / median
    apply = (per.residential_streets >= cfg.min_streets) & ((per.factor_raw >= cfg.upper) | (per.factor_raw <= cfg.lower))
    per["factor"] = np.where(apply, per.factor_raw, 1.)
    per["applied"] = apply
    return per.reset_index()[["plz", "residential_streets", "rate", "median_rate", "factor_raw", "factor", "applied"]]


def apply_correction(table: pd.DataFrame, corrections: pd.DataFrame) -> pd.DataFrame:
    result = table.merge(corrections[["plz", "factor"]], on="plz", how="left")
    result["factor"] = result.factor.fillna(1.)
    result["dhl_corrected"] = result.value / result.factor
    return result


def fit_dhl_rates(table: pd.DataFrame) -> dict:
    observed = table[~table.excluded]
    coefficients, _ = nnls(observed[["persons", "companies"]].to_numpy(float), observed.dhl_corrected.to_numpy(float))
    if not (coefficients > 0).all():
        raise ValueError(f"DHL street rates must be positive for persons and companies: {coefficients.tolist()}")
    return {"person": float(coefficients[0]), "company": float(coefficients[1])}


def decompose(table: pd.DataFrame, rates: dict, cfg: AnchorConfig) -> pd.DataFrame:
    t = table.copy()
    t["expected_private"] = rates["person"] * t.persons
    t["expected_business"] = rates["company"] * t.companies
    expected = t.expected_private + t.expected_business
    positive = t.dhl_corrected > 0
    t["anchor_status"] = np.select(
        [t.excluded, positive & (expected > 0), positive, ~positive & (expected >= cfg.gap_threshold)],
        ["excluded", "observed", "observed_unstructured", "gap"], default="zero")
    structured = t.anchor_status.eq("observed")
    share = np.divide(t.expected_business, expected, out=np.zeros(len(t)), where=expected > 0)
    t["dhl_business"] = np.where(structured, t.dhl_corrected * share, 0.)
    regional = float(t.loc[structured, "dhl_business"].sum() / t.loc[structured, "dhl_corrected"].sum())
    unstructured = t.anchor_status.eq("observed_unstructured")
    t.loc[unstructured, "dhl_business"] = t.loc[unstructured, "dhl_corrected"] * regional
    t["dhl_private"] = np.where(structured | unstructured, t.dhl_corrected - t.dhl_business, 0.)
    structural = t.anchor_status.isin(["gap", "excluded"])
    t.loc[structural, "dhl_private"] = t.loc[structural, "expected_private"]
    t.loc[structural, "dhl_business"] = t.loc[structural, "expected_business"]
    return t


def observed_b2b_share(decomposed: pd.DataFrame) -> float:
    observed = decomposed[decomposed.anchor_status.isin(["observed", "observed_unstructured"])]
    return float(observed.dhl_business.sum() / (observed.dhl_private + observed.dhl_business).sum())


def _wmape(actual, predicted) -> float:
    return float(np.abs(predicted - actual).sum() / np.abs(actual).sum())


def structure_holdout(table: pd.DataFrame, seed: int, folds: int = 5) -> dict:
    observed = table[~table.excluded].reset_index(drop=True)
    plz = np.array(sorted(observed.plz.astype(str).unique()))
    order = named_rng(int(seed), channel="structure-holdout").permutation(len(plz))
    fold_of = {plz[index]: position % folds for position, index in enumerate(order)}
    fold = observed.plz.astype(str).map(fold_of).to_numpy()
    y = observed.dhl_corrected.to_numpy(float)
    result = {}
    for name, columns in (("M0_persons", ["persons"]), ("M1_persons_companies", ["persons", "companies"])):
        X = observed[columns].to_numpy(float)
        predicted = np.zeros(len(y))
        for k in range(folds):
            train, test = fold != k, fold == k
            if test.any():
                coefficients, _ = nnls(X[train], y[train])
                predicted[test] = X[test] @ coefficients
        postal = pd.DataFrame({"plz": observed.plz, "y": y, "p": predicted}).groupby("plz").sum()
        result[name] = {"street_wmape": _wmape(y, predicted), "postal_wmape": _wmape(postal.y.to_numpy(), postal.p.to_numpy()),
                        "folds": folds}
    return result
```

- [ ] **Step 4:** Tests → PASS. Plausibilität echt (Scratch mit Gebäudetabelle aus Task 4): Korrektur nur für 30855 (Faktor ≈ 2,6), Raten ≈ 0,05/Person und 0,3–0,5/Firma, q_DHL 20–28 %, Holdout-PLZ-wMAPE M1 < M0.
- [ ] **Step 5: Commit** `feat: add DHL street decomposition with level correction`.

---

### Task 6: `solve_street_reference`

**Files:**
- Modify: `src/hagrid_demand/baseline/anchor.py`
- Test: `tests/test_anchor.py`

**Interfaces:**
- Consumes: Task 5; `reference.reconcile_carriers(m, q, b, lower, upper, scale) -> dict` (`q`, `conditional` 2×C, `diagnostics`).
- Produces: `solve_street_reference(buildings: gpd.GeoDataFrame, streets: gpd.GeoDataFrame, profiles: dict, b: float, operating_days: int, cfg: dict | None, *, seed: int, scope_plz: list[str]) -> dict` mit Schlüsseln wie `reference.solve_reference` (`sites`, `postal`, `carriers`, `regional_annual`, `checks`, `reconciliation`, `source_quality`, `implied_rates`) plus `anchor` (dict), `streets` (DataFrame), `units` (GeoDataFrame: Gebäude plus synthetische Punkte, Spalten wie Gebäudetabelle), `geometry` (GeoDataFrame `site_id, geometry`). `sites` hat exakt die Spalten `site_id, plz, segment, population, employees, branch, weight, historical_share, structural_share, reference_annual, allocation_status`; `site_id` = `building_key`.
- `checks` enthält `scope_ledger`, `b2b_target`, `b2b_achieved`, `b2b_residual`, `k=None`, `k_status="not_applicable"`, `log_k=None`, `source_quality`, `allocation_balance`, `dhl_carrier`, `dhl_market_share`, `dhl_retained_mean`, `observed_identity`.

- [ ] **Step 1: Failing test** (anhängen):

```python
def test_street_reference_hits_b2b_target_and_dhl_identity_and_keeps_every_parcel():
    import geopandas as gpd
    import street_fixtures as fx
    from shapely.geometry import Point
    from hagrid_demand.baseline.anchor import solve_street_reference

    buildings = gpd.GeoDataFrame({
        "building_key": ["h1", "h2", "f1", "off"], "footprint": [True] * 4, "building_type": ["house"] * 3 + ["point"],
        "area_m2": [100.] * 4, "plz": ["01000"] * 4, "population": [60, 40, 0, 5], "companies": [1, 0, 3, 0],
        "employees": [0, 0, 30, 0], "street_norm": [None] * 4, "sid": [0, 0, 1, -1], "match_stage": ["nearest"] * 3 + ["none"],
        "distance_m": [5.] * 3 + [None], "part": [0, 0, 0, None], "position_m": [10., 70., 30., None],
        "side": ["left"] * 3 + [None], "section_id": ["0-0-0", "0-0-1", "1-0-0", None],
        "axis_x": [0.] * 4, "axis_y": [0.] * 4},
        geometry=[Point(fx.X0 + 10, fx.Y0 + 10), Point(fx.X0 + 70, fx.Y0 + 10), Point(fx.X0 + 410, fx.Y0 + 10),
                  Point(fx.X0 + 900, fx.Y0 + 900)], crs=fx.CRS)
    streets = fx.streets().assign(value=[6., 1.3])
    profiles = {"m": [.42, .58], "q_prior": [.3, .2], "lower": [0., 0.], "upper": [1., 1.], "scale": [1., 1.],
                "carriers": ["DHL", "Other"]}
    solved = solve_street_reference(buildings, streets, profiles, b=.23, operating_days=300, cfg={"min_streets": 99},
                                    seed=1, scope_plz=["01000"])

    checks = solved["checks"]
    assert checks["observed_identity"]["b2b_residual"] == pytest.approx(0., abs=1e-9)
    assert checks["observed_identity"]["total_residual"] == pytest.approx(0., abs=1e-9)
    sites = solved["sites"]
    assert sites.columns.tolist() == ["site_id", "plz", "segment", "population", "employees", "branch", "weight",
                                      "historical_share", "structural_share", "reference_annual", "allocation_status"]
    assert sites.groupby("segment").historical_share.sum().to_dict() == pytest.approx({"business": 1., "private": 1.})
    assert sites.loc[sites.site_id.eq("off"), "reference_annual"].sum() > 0  # structural fallback, not lost
    assert set(sites.loc[sites.site_id.eq("h1"), "segment"]) == {"private", "business"}  # mixed-use building
    assert solved["regional_annual"] == pytest.approx(sites.reference_annual.sum())
    q = solved["carriers"].set_index("carrier").q_adjusted
    assert q["DHL"] == pytest.approx(solved["anchor"]["q_dhl"])
```

- [ ] **Step 2:** Test → FAIL.
- [ ] **Step 3: Implementation** (an `anchor.py` anhängen):

```python
import geopandas as gpd
import shapely

from .reference import reconcile_carriers


def _dhl_index(carriers: list[str]) -> int:
    matches = [index for index, label in enumerate(carriers) if str(label).strip().casefold() == "dhl"]
    if len(matches) != 1:
        raise ValueError("profiles must contain exactly one DHL carrier label")
    return matches[0]


def _reconcile_with_fixed_dhl(profiles: dict, b: float, q_dhl: float) -> tuple[dict, dict]:
    carriers = list(profiles["carriers"])
    index = _dhl_index(carriers)
    m = np.asarray(profiles.get("m", profiles.get("market")), float)
    prior = np.asarray(profiles.get("q", profiles.get("q_prior")), float)
    lower = np.asarray(profiles["lower"], float).copy()
    upper = np.asarray(profiles["upper"], float).copy()
    scale = np.asarray(profiles["scale"], float)
    lower[index] = upper[index] = q_dhl
    prior = np.clip(prior, lower, upper)
    result = reconcile_carriers(m, prior, b, lower, upper, scale)
    return result, {"m": m, "q_prior": prior, "lower": lower, "upper": upper, "scale": scale, "carriers": carriers, "dhl_index": index}


def _synthetic_units(t: pd.DataFrame, streets: gpd.GeoDataFrame, section_length_m: float) -> gpd.GeoDataFrame:
    rows = []
    geometry = streets.set_index("sid").geometry
    for row in t[t.anchor_status.eq("observed_unstructured")].itertuples():
        line = geometry[row.sid]
        count = max(1, int(np.ceil(line.length / section_length_m)))
        for k in range(count):
            position = (k + .5) * line.length / count
            rows.append({"building_key": f"syn:{row.sid}:{k}", "footprint": False, "building_type": "synthetic", "area_m2": 0.,
                         "plz": str(row.plz), "population": 0., "companies": 0., "employees": 0., "street_norm": None,
                         "sid": int(row.sid), "match_stage": "synthetic", "distance_m": 0., "part": None,
                         "position_m": position, "side": "right", "section_id": f"{row.sid}-s-{int(position // section_length_m)}",
                         "axis_x": np.nan, "axis_y": np.nan, "geometry": line.interpolate(position),
                         "private_daily_share": 1. / count, "business_daily_share": 1. / count})
    columns = ["building_key", "footprint", "building_type", "area_m2", "plz", "population", "companies", "employees",
               "street_norm", "sid", "match_stage", "distance_m", "part", "position_m", "side", "section_id", "axis_x", "axis_y",
               "geometry", "private_daily_share", "business_daily_share"]
    return gpd.GeoDataFrame(rows, columns=columns, geometry="geometry", crs=streets.crs)


def solve_street_reference(buildings, streets, profiles: dict, b: float, operating_days: int, cfg: dict | None, *,
                           seed: int, scope_plz: list[str]) -> dict:
    config = AnchorConfig.from_mapping(cfg)
    scope = set(str(item) for item in scope_plz)
    in_scope = streets[streets.plz.astype(str).isin(scope)]
    table = street_table(buildings, in_scope, config)
    corrections = level_correction(table, config)
    table = apply_correction(table, corrections)
    rates = fit_dhl_rates(table)
    t = decompose(table, rates, config)
    q_dhl = observed_b2b_share(t)
    reconciliation, inputs = _reconcile_with_fixed_dhl(profiles, b, q_dhl)
    conditional = np.asarray(reconciliation["conditional"], float)
    index = inputs["dhl_index"]
    p_private, p_business = float(conditional[0, index]), float(conditional[1, index])
    t["private_daily"] = t.dhl_private / p_private
    t["business_daily"] = t.dhl_business / p_business

    units = buildings.copy()
    street_values = t.set_index("sid")
    persons = units.sid.map(street_values.persons)
    companies = units.sid.map(street_values.companies)
    units["private_daily"] = np.where(persons > 0, units.population / persons * units.sid.map(street_values.private_daily), 0.)
    units["business_daily"] = np.where(companies > 0, units.companies / companies * units.sid.map(street_values.business_daily), 0.)
    off_street = units.sid.lt(0) | units.sid.map(street_values.anchor_status).isna()
    units.loc[off_street, "private_daily"] = rates["person"] * units.loc[off_street, "population"] / p_private
    units.loc[off_street, "business_daily"] = rates["company"] * units.loc[off_street, "companies"] / p_business
    units["anchor_status"] = np.where(off_street, "structural_no_street", units.sid.map(street_values.anchor_status))
    synthetic = _synthetic_units(t, streets, config.section_length_m)
    if len(synthetic):
        synthetic["private_daily"] = synthetic.private_daily_share * synthetic.sid.map(street_values.private_daily)
        synthetic["business_daily"] = synthetic.business_daily_share * synthetic.sid.map(street_values.business_daily)
        synthetic["anchor_status"] = "observed_unstructured"
        units = gpd.GeoDataFrame(pd.concat([units, synthetic.drop(columns=["private_daily_share", "business_daily_share"])],
                                           ignore_index=True), geometry="geometry", crs=buildings.crs)

    rows = []
    for segment, daily, weight in (("private", "private_daily", "population"), ("business", "business_daily", "companies")):
        part = units[(units[daily] > 0) | (units[weight] > 0)]
        rows.append(pd.DataFrame({
            "site_id": part.building_key.to_numpy(), "plz": part.plz.astype(str).to_numpy(), "segment": segment,
            "population": part.population.to_numpy(float) if segment == "private" else 0.,
            "employees": part.employees.to_numpy(float) if segment == "business" else np.nan, "branch": None,
            "weight": part[weight].to_numpy(float), "reference_annual": part[daily].to_numpy(float) * operating_days,
            "allocation_status": "located"}))
    sites = pd.concat(rows, ignore_index=True)
    for share, source in (("historical_share", "reference_annual"), ("structural_share", "weight")):
        totals = sites.groupby("segment")[source].transform("sum")
        sites[share] = np.divide(sites[source], totals, out=np.zeros(len(sites)), where=totals > 0)
    sites = sites[["site_id", "plz", "segment", "population", "employees", "branch", "weight", "historical_share",
                   "structural_share", "reference_annual", "allocation_status"]]

    observed = t.anchor_status.isin(["observed", "observed_unstructured"])
    m_dhl = (1 - b) * p_private + b * p_business
    observed_total = float((t.loc[observed, "private_daily"] + t.loc[observed, "business_daily"]).sum())
    observed_b2b = float(t.loc[observed, "business_daily"].sum() / observed_total)
    identity = {"total_residual": observed_total - float(t.loc[observed, "dhl_corrected"].sum()) / m_dhl,
                "b2b_residual": observed_b2b - b, "observed_daily": observed_total}
    if abs(identity["total_residual"]) > 1e-6 * max(observed_total, 1.) or abs(identity["b2b_residual"]) > 1e-9:
        raise ValueError(f"street anchor identity failed: {identity}")
    street_units = units[~units.anchor_status.eq("structural_no_street")]
    allocated = street_units.groupby("sid")[["private_daily", "business_daily"]].sum()
    expected = t.set_index("sid").loc[allocated.index, ["private_daily", "business_daily"]]
    allocation_error = float(np.abs(allocated.to_numpy() - expected.to_numpy()).max()) if len(allocated) else 0.
    total_daily = float(units.private_daily.sum() + units.business_daily.sum())
    regional_annual = float(sites.reference_annual.sum())

    postal_sites = sites.groupby(["plz", "segment"]).reference_annual.sum().unstack(fill_value=0.)
    postal = pd.DataFrame({"plz": postal_sites.index.astype(str)})
    postal["dhl_retained_mean"] = postal.plz.map(t[~t.excluded].groupby("plz").value.sum()).fillna(0.).to_numpy()
    postal["private_annual"] = postal_sites.get("private", 0.).to_numpy()
    postal["business_annual"] = postal_sites.get("business", 0.).to_numpy()
    postal["reference_annual"] = postal.private_annual + postal.business_annual
    postal["b2b_share"] = np.divide(postal.business_annual, postal.reference_annual, out=np.zeros(len(postal)),
                                    where=postal.reference_annual > 0)
    dhl_daily = postal.plz.map(t[observed].groupby("plz").dhl_corrected.sum()).fillna(0.)
    postal["dhl_share"] = np.divide(dhl_daily * operating_days, postal.reference_annual, out=np.zeros(len(postal)),
                                    where=postal.reference_annual > 0)
    postal = postal[["plz", "dhl_retained_mean", "reference_annual", "private_annual", "business_annual", "b2b_share", "dhl_share"]]

    carriers = inputs["carriers"]
    q = np.asarray(reconciliation["q"], float)
    market = inputs["m"] / inputs["m"].sum()
    carriers_frame = pd.DataFrame({"year": 2021, "carrier": carriers, "market_share": market, "q_prior": inputs["q_prior"],
                                   "q_scale": inputs["scale"], "lower": inputs["lower"], "upper": inputs["upper"], "q_adjusted": q,
                                   "private_share": conditional[0], "business_share": conditional[1],
                                   "share": (1 - b) * conditional[0] + b * conditional[1]})
    excluded = t[t.excluded]
    scope_ledger = {"rule": f"exclude complete in-scope observation when value > {config.exclude_above:g}",
                    "threshold": config.exclude_above, "excluded_rows": int(len(excluded)),
                    "excluded_volume": float(excluded.value.sum()), "retained_rows": int((~t.excluded).sum()),
                    "retained_volume": float(t.loc[~t.excluded, "value"].sum()),
                    "out_of_scope_rows": int(len(streets) - len(in_scope)),
                    "out_of_scope_volume": float(streets.loc[~streets.plz.astype(str).isin(scope), "value"].sum())}
    status_volume = units.groupby("anchor_status")[["private_daily", "business_daily"]].sum()
    anchor = {
        "rates_dhl_per_day": rates, "q_dhl": q_dhl, "p_dhl_private": p_private, "p_dhl_business": p_business,
        "corrections": json_records(corrections), "street_status_counts": t.anchor_status.value_counts().to_dict(),
        "daily_by_status": {status: float(values.sum()) for status, values in status_volume.iterrows()},
        "total_daily": total_daily, "observed_identity": identity, "allocation_max_error": allocation_error,
        "holdout": structure_holdout(table, seed=seed), "synthetic_units": int(len(synthetic)),
    }
    source_quality = {"unknown_plz_sites": [], "unknown_plz_weight": 0., "known_plz_outside_anchor_sites": [],
                      "known_plz_outside_anchor_weight": 0.,
                      "structural_no_street_units": int(units.anchor_status.eq("structural_no_street").sum())}
    checks = {"scope_ledger": scope_ledger, "b2b_target": float(b), "b2b_achieved": observed_b2b,
              "b2b_residual": observed_b2b - b, "k": None, "k_status": "not_applicable", "log_k": None,
              "dhl_carrier": carriers[index], "dhl_carrier_index": index, "dhl_market_share": float(market[index]),
              "dhl_retained_mean": scope_ledger["retained_volume"], "observed_identity": identity,
              "allocation_balance": {"street_max_error": allocation_error,
                                     "regional_error": regional_annual - total_daily * operating_days},
              "regional_annual_balance": regional_annual - total_daily * operating_days}
    persons_total = float(units.population.sum())
    companies_total = float(units.companies.sum())
    implied_rates = {"persons_packages_per_operating_day": float(units.private_daily.sum() / persons_total) if persons_total else None,
                     "company_locations_packages_per_operating_day": float(units.business_daily.sum() / companies_total) if companies_total else None,
                     "semantics": "Aggregated model rates, not causal individual ordering rates."}
    reconciliation_payload = {
        "market": [{"carrier": c, "market_share": float(v)} for c, v in zip(carriers, market)],
        "providers": [{"carrier": c, "q_prior": float(p), "q_scale": float(s), "lower": float(lo), "upper": float(hi)}
                      for c, p, s, lo, hi in zip(carriers, inputs["q_prior"], inputs["scale"], inputs["lower"], inputs["upper"])],
        "adjusted_q": [{"carrier": c, "q_adjusted": float(v)} for c, v in zip(carriers, q)],
        "conditional": [{"segment": seg, "carrier": c, "share": float(conditional[row, col])}
                        for row, seg in enumerate(("private", "business")) for col, c in enumerate(carriers)],
        "diagnostics": reconciliation["diagnostics"], "dhl_fixed_q": q_dhl, "reference_balance": None,
    }
    geometry = gpd.GeoDataFrame({"site_id": units.building_key.to_numpy()}, geometry=units.geometry.to_numpy(), crs=units.crs)
    return {"sites": sites, "postal": postal, "carriers": carriers_frame, "regional_annual": regional_annual, "checks": checks,
            "reconciliation": reconciliation_payload, "source_quality": source_quality, "implied_rates": implied_rates,
            "anchor": anchor, "streets": t, "units": units, "geometry": geometry}


def json_records(frame: pd.DataFrame) -> list[dict]:
    return [{key: (value.item() if hasattr(value, "item") else value) for key, value in row.items()}
            for row in frame.to_dict(orient="records")]
```

- [ ] **Step 4:** Test → PASS.
- [ ] **Step 5: Commit** `feat: solve the street-anchored 2021 reference`.

---

### Task 7: Straßenmodus in Referenz, Projektion mit DHL-fixem q

**Files:**
- Modify: `src/hagrid_demand/baseline/projection.py`, `src/hagrid_demand/baseline/workflow.py`
- Test: `tests/test_baseline_projection.py`, `tests/test_street_workflow.py`

**Interfaces:**
- Consumes: `solve_street_reference` (Task 6).
- Produces: `projection._profile(series, year, dhl_fixed: dict | None = None)`; `project_annual(..., cfg)` liest `cfg.get("dhl_b2b")` = `{"q_2021": float, "b_2021": float}`; Referenzartefakte im Straßenmodus zusätzlich `reference_anchor.json`, `reference_streets.parquet`, `reference_units.parquet` (Gebäude + synthetische Punkte mit Projektion).

- [ ] **Step 1: Failing tests**
  - `tests/test_baseline_projection.py`:

```python
def test_projection_fixes_dhl_b2b_proportional_to_the_national_trend():
    from hagrid_demand.baseline.projection import _profile
    from hagrid_demand.baseline.series import build_series
    from hagrid_demand.baseline.sources import packaged_series_inputs

    series = build_series(packaged_series_inputs(), [2021, 2030], volume_fit_policy="observed_only")
    b = series["b2b"].set_index("year").share
    profile, target = _profile(series, 2030, dhl_fixed={"q_2021": .24, "b_2021": float(b[2021])})
    dhl = profile[(profile.carrier == "DHL")].q.iloc[0]
    assert dhl == pytest.approx(.24 * b[2030] / b[2021])
    shares = profile.pivot(index="carrier", columns="segment", values="share")
    market = series["market"].query("year == 2030").set_index("carrier").market_share
    assert ((1 - target) * shares.private + target * shares.business).reindex(market.index).to_numpy() == pytest.approx(market.to_numpy())
```

  - `tests/test_street_workflow.py`:

```python
def test_street_reference_and_daily_run_use_buildings_and_fixed_dhl(tmp_path):
    from hagrid_demand.baseline.workflow import run_baseline

    config_path = write_street_fixture(tmp_path)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config.update({"output_scope": "daily", "years": [2021, 2025], "dates": ["2025-05-13"],
                   "anchor": {"mode": "street", "min_streets": 99}})
    config_path.write_text(json.dumps(config), encoding="utf-8")
    run = run_baseline(config_path, "street-daily")

    anchor = json.loads((run / "reference_anchor.json").read_text(encoding="utf-8"))
    assert 0 < anchor["q_dhl"] < 1
    sites = pd.read_parquet(run / "reference_sites.parquet")
    assert sites.site_id.str.startswith(("osm:", "pt:", "syn:")).all()
    profiles = pd.read_parquet(run / "carrier_profiles.parquet")
    dhl = profiles[(profiles.carrier == "DHL") & (profiles.year == 2025)].q.iloc[0]
    assert dhl == pytest.approx(anchor["q_dhl"] * anchor["b2b_by_year"]["2025"] / anchor["b2b_by_year"]["2021"])
```

- [ ] **Step 2:** Tests → FAIL.
- [ ] **Step 3: Implementation**
  - `projection._profile`: Signatur `(series, year, dhl_fixed=None)`; nach `providers = providers.reindex(market.index)`:

```python
    lower, upper = providers.lower.to_numpy(float).copy(), providers.upper.to_numpy(float).copy()
    prior = providers.q_prior.to_numpy(float).copy()
    if dhl_fixed is not None:
        index = [i for i, label in enumerate(market.index) if str(label).casefold() == "dhl"][0]
        q_dhl = float(dhl_fixed["q_2021"]) * target / float(dhl_fixed["b_2021"])
        lower[index] = upper[index] = q_dhl
        prior = np.clip(prior, lower, upper)
    result = reconcile_carriers(values, prior, target, lower, upper, providers.q_scale.to_numpy(float))
```

    `project_annual` ruft `_profile(series, year, cfg.get("dhl_b2b"))`.
  - `workflow._write_reference(config, source, series_dir, potentials_dir, output, buildings_dir=None)`: im Straßenmodus

```python
    if _anchor_mode(config) == "street":
        from .anchor import solve_street_reference
        solved = solve_street_reference(gpd.read_parquet(buildings_dir / "buildings.parquet"), _dhl_streets(source), profiles, b2b,
                                        config["reference_operating_days"], config.get("anchor"), seed=int(config["seed"]),
                                        scope_plz=postal_scope.plz.astype(str).tolist())
        geometry = solved["geometry"]
        b2b_series = pd.read_parquet(series_dir / "b2b.parquet").set_index("year").share
        solved["anchor"]["b2b_by_year"] = {str(int(y)): float(v) for y, v in b2b_series.items()}
        _json(output / "reference_anchor.json", solved["anchor"])
        solved["streets"].to_parquet(output / "reference_streets.parquet", index=False)
        solved["units"].to_parquet(output / "reference_units.parquet", index=False)
    else:
        ...  # bestehender PLZ-Pfad, geometry aus source_sites wie bisher
```

    Die übrigen Schreibvorgänge (`reference_postal`, `reference_sites`, `reference_geometry`, Profile, Checks) bleiben gemeinsam. Der Referenzstage-Aufruf übergibt `buildings_dir=run / "buildings"`; Abhängigkeiten und Implementierungsdateien der Referenzstage im Straßenmodus um `run / "buildings"`, `anchor.py`, `buildings.py` ergänzen; validate/copy_public um die drei neuen Dateien ergänzen (nur Straßenmodus). `_frozen_reference_artifacts` nimmt `reference_anchor.json` und `reference_units.parquet` in Gruppe `anchor` auf, wenn vorhanden.
  - `_series` für alle Jahre: `_write_series` baut bereits Referenzjahr + Config-Jahre; `b2b_by_year` enthält daher 2021 und alle Zieljahre.
  - `_write_daily`: `projection_cfg = {"memory": {"fixed": 1}, "regional_level": config["regional_level"]}`; wenn `reference_anchor.json` existiert: `projection_cfg["dhl_b2b"] = {"q_2021": anchor["q_dhl"], "b_2021": anchor["b2b_by_year"]["2021"]}`.
- [ ] **Step 4:** Tests → PASS; Gesamtsuite → PASS.
- [ ] **Step 5: Commit** `feat: run the reference in street mode and fix DHL B2B over years`.

---

### Task 8: Adaptive Stopps (S3)

**Files:**
- Create: `src/hagrid_demand/baseline/stops.py`
- Modify: `src/hagrid_demand/baseline/workflow.py` (Artefakte `reference_stops.parquet`, `reference_site_stops.parquet`)
- Test: `tests/test_stops.py`

**Interfaces:**
- Consumes: `buildings.street_parts`, Einheiten-Tabelle (`reference_units.parquet`), `reference_sites.parquet`.
- Produces: `build_stops(units: gpd.GeoDataFrame, expected_daily: pd.Series, streets: gpd.GeoDataFrame, walking_radius_m=40., own_stop_parcels_per_day=15., section_length_m=50.) -> tuple[gpd.GeoDataFrame, pd.DataFrame]`; Stopps mit Spalten `stop_id, stop_index, str_idx, part, side, section_id, plz, n_units, expected_daily, geometry`; Zuordnung `building_key, stop_id`.

- [ ] **Step 1: Failing test** `tests/test_stops.py`:

```python
import geopandas as gpd
import pandas as pd
import pytest
from shapely.geometry import Point

import street_fixtures as fx


def _units():
    keys = ["a", "b", "c", "d", "big", "off"]
    return gpd.GeoDataFrame({
        "building_key": keys, "plz": ["01000"] * 6, "sid": [0, 0, 0, 0, 0, -1], "part": [0, 0, 0, 0, 0, None],
        "side": ["left", "left", "left", "right", "left", None], "position_m": [10., 50., 95., 12., 60., None]},
        geometry=[Point(fx.X0 + p, fx.Y0 + 5) for p in (10, 50, 95, 12, 60)] + [Point(fx.X0 + 900, fx.Y0 + 900)], crs=fx.CRS)


def test_stops_group_same_side_within_walking_distance_and_isolate_large_recipients():
    from hagrid_demand.baseline.stops import build_stops

    expected = pd.Series({"a": 1., "b": 2., "c": 1., "d": 1., "big": 30., "off": 1.})
    stops, mapping = build_stops(_units(), expected, fx.streets())
    by_unit = mapping.set_index("building_key").stop_id
    assert by_unit["a"] == by_unit["b"] != by_unit["c"]          # 10 m and 50 m within 80 m span; 95 m starts a new stop
    assert by_unit["d"] not in {by_unit["a"], by_unit["c"]}         # other side of the street
    assert (mapping.stop_id == by_unit["big"]).sum() == 1           # large recipient has its own stop
    stop = stops.set_index("stop_id").loc[by_unit["a"]]
    assert stop.geometry.y == pytest.approx(fx.Y0) and stop.geometry.x == pytest.approx(fx.X0 + (10 + 2 * 50) / 3)
    assert stops.set_index("stop_id").loc[by_unit["off"], "str_idx"] == -1
    assert stops.stop_id.is_unique and stops.stop_index.is_unique
    first, _ = build_stops(_units(), expected, fx.streets())
    assert first.stop_id.tolist() == stops.stop_id.tolist()          # stable ids
```

- [ ] **Step 2:** Test → FAIL.
- [ ] **Step 3: Implementation** `src/hagrid_demand/baseline/stops.py`:

```python
"""Adaptive delivery stops (spec 5.12): group buildings per street side within a walking span."""

from __future__ import annotations

import hashlib

import geopandas as gpd
import numpy as np
import pandas as pd

from .buildings import street_parts


def _stop_id(*parts) -> str:
    return "stp:" + hashlib.sha1("|".join(str(part) for part in parts).encode("utf-8")).hexdigest()[:16]


def build_stops(units: gpd.GeoDataFrame, expected_daily: pd.Series, streets: gpd.GeoDataFrame,
                walking_radius_m: float = 40., own_stop_parcels_per_day: float = 15.,
                section_length_m: float = 50.) -> tuple[gpd.GeoDataFrame, pd.DataFrame]:
    frame = units.copy()
    frame["expected_daily"] = frame.building_key.map(expected_daily).fillna(0.).to_numpy(float)
    parts = {(int(row.sid), int(row.part)): row.geometry for row in street_parts(streets).itertuples()}
    line_of = streets.set_index("sid").geometry
    stops, mapping = [], []

    def emit(members: pd.DataFrame, sid: int, part, side: str) -> None:
        weights = members.expected_daily.to_numpy(float)
        weights = weights if weights.sum() > 0 else np.ones(len(members))
        position = float(np.average(members.position_m.to_numpy(float), weights=weights))
        line = parts.get((sid, int(part))) if part is not None and not pd.isna(part) else line_of[sid]
        point = line.interpolate(position)
        stop_id = _stop_id(sid, part, side, members.building_key.iloc[0])
        plz = members.groupby("plz").expected_daily.sum().idxmax() if len(members) else None
        stops.append({"stop_id": stop_id, "str_idx": sid, "part": part, "side": side,
                      "section_id": f"{sid}-{part}-{int(position // section_length_m)}", "plz": str(plz),
                      "n_units": int(len(members)), "expected_daily": float(members.expected_daily.sum()), "geometry": point})
        mapping.extend({"building_key": key, "stop_id": stop_id} for key in members.building_key)

    on_street = frame[frame.sid >= 0].copy()
    on_street["part_key"] = on_street.part.fillna(-1).astype("int64")
    on_street = on_street.sort_values(["sid", "part_key", "side", "position_m", "building_key"], kind="stable")
    for (sid, part_key, side), group in on_street.groupby(["sid", "part_key", "side"], sort=True):
        part = None if part_key < 0 else int(part_key)
        cluster, start = [], None
        for index, row in group.iterrows():
            if row.expected_daily >= own_stop_parcels_per_day:
                emit(group.loc[[index]], int(sid), part, side)
                continue
            if cluster and row.position_m - start > 2 * walking_radius_m:
                emit(group.loc[cluster], int(sid), part, side)
                cluster = []
            if not cluster:
                start = row.position_m
            cluster.append(index)
        if cluster:
            emit(group.loc[cluster], int(sid), part, side)
    for row in frame[frame.sid < 0].sort_values("building_key").itertuples():
        stop_id = _stop_id("off", row.building_key)
        stops.append({"stop_id": stop_id, "str_idx": -1, "part": None, "side": None, "section_id": None, "plz": str(row.plz),
                      "n_units": 1, "expected_daily": float(row.expected_daily), "geometry": row.geometry})
        mapping.append({"building_key": row.building_key, "stop_id": stop_id})
    result = gpd.GeoDataFrame(stops, geometry="geometry", crs=units.crs).sort_values("stop_id").reset_index(drop=True)
    result.insert(1, "stop_index", np.arange(len(result), dtype="int64"))
    return result, pd.DataFrame(mapping)
```

  Workflow (Straßenmodus, `_write_reference`): nach dem Lösen

```python
        from .stops import build_stops
        stop_cfg = config.get("stops", {})
        expected = solved["sites"].groupby("site_id").reference_annual.sum() / config["reference_operating_days"]
        stops, site_stops = build_stops(solved["units"], expected, _dhl_streets(source),
                                        float(stop_cfg.get("walking_radius_m", 40.)), float(stop_cfg.get("own_stop_parcels_per_day", 15.)),
                                        float(stop_cfg.get("section_length_m", 50.)))
        stops.to_parquet(output / "reference_stops.parquet", index=False)
        site_stops.rename(columns={"building_key": "site_id"}).to_parquet(output / "reference_site_stops.parquet", index=False)
```

  Validate/copy_public und `_frozen_reference_artifacts` (Gruppe `anchor`) um beide Dateien ergänzen.
- [ ] **Step 4:** Test → PASS; Workflow-Test aus Task 7 prüft zusätzlich `reference_site_stops.parquet` deckt alle `reference_sites.site_id` ab (Assertion ergänzen). Plausibilität echt: Stopps gesamt, Einheiten je Stopp (Median, p90), Anteil eigener Stopps, erwartete Pakete je Stopp.
- [ ] **Step 5: Commit** `feat: build adaptive delivery stops`.

---

### Task 9: MATSim-Export je Stopp

**Files:**
- Modify: `src/hagrid_demand/compatibility/matsim_export.py`, `src/hagrid_demand/baseline/workflow.py`
- Test: `tests/test_matsim_export.py`

**Interfaces:**
- Produces: `write_matsim_day(chunk, geometry, output_dir, stops: dict | None = None, max_parcels_per_row: int = 400) -> dict`; `stops = {"site_stops": DataFrame[site_id, stop_id], "stops": GeoDataFrame[stop_id, stop_index, str_idx, section_id, plz, geometry]}`; `with_matsim_export(chunks, geometry, output_dir, ledgers, stops=None, max_parcels_per_row=400)`. Ledger zusätzlich `stops_active`, `rows`, `parcels_per_stop` (`mean`, `median`, `p90`, `max`), `sites_active`.
- Zusatzspalten im Stoppmodus: `id` (int64 = `stop_index * 100 + teil`), `stop_id`, `str_idx` (int64), `section_id`.

- [ ] **Step 1: Failing test** (anhängen):

```python
def test_matsim_day_by_stop_splits_rows_above_the_limit_and_keeps_totals(tmp_path):
    from hagrid_demand.compatibility.matsim_export import write_matsim_day

    chunk = pd.DataFrame({"date": pd.Timestamp("2025-05-13"), "site_id": ["h1", "h2", "f1", "f1"], "plz": ["1"] * 4,
                          "segment": ["private", "private", "business", "business"],
                          "carrier": ["DHL", "DHL", "UPS", "DHL"], "count": [3, 4, 900, 5]})
    geometry = gpd.GeoDataFrame({"site_id": ["h1", "h2", "f1"]}, geometry=[Point(1, 1), Point(2, 2), Point(9, 9)], crs="EPSG:25832")
    stops = {"site_stops": pd.DataFrame({"site_id": ["h1", "h2", "f1"], "stop_id": ["s1", "s1", "s2"]}),
             "stops": gpd.GeoDataFrame({"stop_id": ["s1", "s2"], "stop_index": [0, 1], "str_idx": [7, 8],
                                        "section_id": ["7-0-0", "8-0-1"], "plz": ["1", "1"]},
                                       geometry=[Point(1.5, 0), Point(9, 0)], crs="EPSG:25832")}
    ledger = write_matsim_day(chunk, geometry, tmp_path, stops=stops, max_parcels_per_row=400)

    frame = gpd.read_file(tmp_path / ledger["file"])
    assert frame.total.sum() == 912 and frame.groupby("stop_id").total.sum().to_dict() == {"s1": 7, "s2": 905}
    assert (frame[["ups_type", "dhl_type", "dhl_tag"]] <= 400).all().all()
    assert frame.id.is_unique and set(frame.loc[frame.stop_id.eq("s2"), "id"]) == {100, 101, 102}
    assert frame.loc[frame.stop_id.eq("s1"), "dhl_tag"].tolist() == [7]
    assert ledger["stops_active"] == 2 and ledger["rows"] == 4
```

- [ ] **Step 2:** Test → FAIL.
- [ ] **Step 3: Implementation** – in `write_matsim_day` nach dem Pivot (`table`, Index = `site_id`):

```python
    if stops is not None:
        stop_of = stops["site_stops"].set_index("site_id").stop_id
        missing = sorted(set(table.index) - set(stop_of.index))
        if missing:
            raise ValueError(f"sites without stop mapping: {missing[:5]}")
        table = table.drop(columns="plz").groupby(table.index.map(stop_of)).sum(numeric_only=True)
        info = stops["stops"].set_index("stop_id")
        table["plz"] = info.plz.reindex(table.index).astype(str).to_numpy()
        table = _split_rows(table, max_parcels_per_row)
        points = info.geometry.reindex(table.stop_id)
        frame = pd.DataFrame({"id": (info.stop_index.reindex(table.stop_id).to_numpy() * 100 + table.row_part.to_numpy()).astype(np.int64),
                              "stop_id": table.stop_id.to_numpy(), "str_idx": info.str_idx.reindex(table.stop_id).to_numpy().astype(np.int64),
                              "section_id": info.section_id.reindex(table.stop_id).fillna("").astype(str).to_numpy(),
                              "postal_cod": table.plz.to_numpy(), "date": ledger["date"]})
        # continue with count columns and GeoDataFrame(points) exactly as in the site path
```

  Hilfsfunktion:

```python
def _split_rows(table: pd.DataFrame, limit: int) -> pd.DataFrame:
    counts = [column for column in _count_columns() if column not in {"total", "total_sim", "wl_tag"}]
    rows = []
    for stop_id, row in table.iterrows():
        pieces = max(1, int(np.ceil(row[counts].max() / limit)))
        for part in range(pieces):
            split = {column: int(row[column]) // pieces + (1 if part < int(row[column]) % pieces else 0) for column in counts}
            rows.append({"stop_id": stop_id, "row_part": part, "plz": row.plz, **split})
    result = pd.DataFrame(rows)
    tags = [f"{provider}_tag" for provider, _ in CARRIER_FIELDS.values()]
    types = [_safe10(f"{provider}_type") for provider, _ in CARRIER_FIELDS.values()]
    result["total"] = result[tags].sum(axis=1) + result[types].sum(axis=1)
    result["total_sim"] = result.total
    result["wl_tag"] = result.total
    return result
```

  Ledger ergänzen: `stops_active = int(table.stop_id.nunique())`, `rows = int(len(result))`, `parcels_per_stop` aus der Summe je `stop_id`, `sites_active = int(active.site_id.nunique())`. Ohne `stops` bleibt der bisherige Pfad (ein Punkt je Standort, `id` = laufender Index) erhalten.
  `workflow._write_daily`: wenn `run / "reference_stops.parquet"` existiert, `stops = {"site_stops": pd.read_parquet(run/"reference_site_stops.parquet"), "stops": gpd.read_parquet(run/"reference_stops.parquet")}` an `with_matsim_export` übergeben; `max_parcels_per_row` aus `config["stops"]`.
- [ ] **Step 4:** Tests → PASS (alte Export-Tests unverändert grün).
- [ ] **Step 5: Commit** `feat: export MATSim demand per stop with split rows`.

---

### Task 10: Bericht – Anker, Plausibilität, Holdout

**Files:**
- Modify: `src/hagrid_demand/baseline/dashboard.py`
- Test: `tests/test_street_workflow.py`

**Interfaces:**
- Produces: `report_data["views"]["anchor"]` = Inhalt von `reference_anchor.json` plus `plausibility` (je PLZ: Pakete je Einwohner und Jahr, B2B-Anteil) und `buildings` (Inhalt `buildings/buildings_report.json`), wenn vorhanden; `report.md`-Abschnitt „Straßen-Anker“ mit q_DHL, Raten, korrigierten PLZ, Statusmengen, Holdout-Tabelle und © OpenStreetMap-Hinweis.

- [ ] **Step 1: Failing test** (anhängen an `test_street_reference_and_daily_run_use_buildings_and_fixed_dhl`):

```python
    report = json.loads((run / "report_data.json").read_text(encoding="utf-8"))
    assert report["views"]["anchor"]["q_dhl"] == pytest.approx(anchor["q_dhl"])
    assert "plausibility" in report["views"]["anchor"]
    markdown = (run / "report.md").read_text(encoding="utf-8")
    assert "Straßen-Anker" in markdown and "OpenStreetMap" in markdown
```

- [ ] **Step 2:** Test → FAIL.
- [ ] **Step 3: Implementation** – in `build_report_data` nach `report = {...}`:

```python
    anchor_path = reference_dir / "reference_anchor.json"
    if anchor_path.is_file():
        anchor = json.loads(anchor_path.read_text(encoding="utf-8"))
        sites = pd.read_parquet(reference_dir / "reference_sites.parquet")
        persons = sites[sites.segment.eq("private")].groupby("plz").population.sum()
        annual = sites.groupby(["plz", "segment"]).reference_annual.sum().unstack(fill_value=0.)
        plausibility = pd.DataFrame({"plz": annual.index.astype(str),
                                     "parcels_per_person_year": (annual.sum(axis=1) / persons.reindex(annual.index)).to_numpy(),
                                     "b2b_share": (annual.get("business", 0.) / annual.sum(axis=1)).to_numpy()})
        anchor["plausibility"] = json.loads(plausibility.to_json(orient="records"))
        buildings_report = reference_dir / "buildings" / "buildings_report.json"
        if buildings_report.is_file():
            anchor["buildings"] = json.loads(buildings_report.read_text(encoding="utf-8"))
        report["views"]["anchor"] = anchor
```

  In `_report_markdown` einen Abschnitt anhängen, wenn `report["views"].get("anchor")` existiert:

```python
    anchor = report.get("views", {}).get("anchor")
    if anchor:
        corrected = [row for row in anchor["corrections"] if row["applied"]]
        holdout = anchor["holdout"]
        text += ("\n## Straßen-Anker\n\n"
                 f"DHL-B2B-Anteil aus Straßendaten: {anchor['q_dhl']:.3f}. DHL-Raten je Tag: "
                 f"{anchor['rates_dhl_per_day']['person']:.4f} je Einwohner, {anchor['rates_dhl_per_day']['company']:.3f} je Firma.\n\n"
                 "Pegelkorrektur: " + (", ".join(f"{row['plz']} (Faktor {row['factor']:.2f})" for row in corrected) or "keine") + ".\n\n"
                 "Holdout (PLZ-wMAPE): " + ", ".join(f"{name} {values['postal_wmape']:.1%}" for name, values in holdout.items()) + ".\n\n"
                 "Gebäude: © OpenStreetMap contributors (ODbL), Stand 01.01.2021.\n")
```

  (`_report_markdown` so umstellen, dass der bisherige Rückgabewert in `text` steht und am Ende zurückgegeben wird.)
- [ ] **Step 4:** Tests → PASS; Gesamtsuite → PASS.
- [ ] **Step 5: Commit** `feat: report street anchor, plausibility and holdout`.

---

### Task 11: Echter Lauf, Plausibilitätsprüfung, Configs und README

**Files:**
- Modify: `configs/baseline-reference.json`, `configs/baseline-daily.json`, `README.md`

- [ ] **Step 1:** Configs um Straßenmodus ergänzen:

```json
  "osm_buildings": "../../parcel-demand-estimation/input/osm/osm_buildings_region_hannover_2021.parquet",
  "osm_points": "../../parcel-demand-estimation/input/osm/osm_address_poi_region_hannover_2021.parquet",
  "anchor": {"mode": "street", "level_correction": {"min_persons": 30, "min_streets": 20, "upper": 2.0, "lower": 0.5}, "gap_threshold": 5.0},
  "buildings": {"residential_max_distance_m": 65, "firm_candidate_distance_m": 100, "firm_fallback_distance_m": 250, "match_distance_m": 100},
  "stops": {"walking_radius_m": 40, "own_stop_parcels_per_day": 15, "max_parcels_per_row": 400, "section_length_m": 50},
  "reference_operating_days": "calendar"
```

- [ ] **Step 2:** Echten Lauf mit absoluten Pfaden (Scratch-Config) für die acht Tage 09.–17.05.2025 ausführen.
- [ ] **Step 3: Plausibilitätsprüfung (Pflicht, Ergebnisse im Chat berichten):**
  - Anker: q_DHL 0,20–0,28; Korrektur nur 30855; Status-Mengen; Anteil `structural_*` < 5 % der Tagesmenge.
  - Gebäude: Personen in Gebäuden ≥ 90 %; Firmen `point` < 5 %; Straßenzuordnung `none` < 3 %.
  - Niveau: Pakete je Person und Jahr je PLZ (Median ~40–60, keine PLZ > 3 × Median ohne Erklärung); B2B-Anteil je PLZ plausibel (Innenstadt hoch).
  - Tage: Tagessummen vs. Notebook (Abweichung < 10 %), PLZ-Korrelation > 0,95 außer 30855 (erwartet niedriger wegen Korrektur).
  - Stopps: Stopps je Tag, Pakete je Stopp (Median 2–6), keine Exportzeile > 400, Stopps mit > 100 Paketen/Tag einzeln ansehen.
  - Holdout: PLZ-wMAPE M1 vs. M0.
  - Auffälligkeiten beheben oder begründet dokumentieren, bevor weitergegangen wird.
- [ ] **Step 4:** README-Abschnitt „Tageslauf und MATSim-Export“ um Straßen-Anker, OSM-Gebäude, Stopps, `baseline osm-clip` und Kennzahlen des echten Laufs ergänzen.
- [ ] **Step 5:** Gesamtsuite → PASS; Commit `docs: street anchor configs and acceptance run`.
- [ ] **Step 6:** Abschließendes Whole-Branch-Review (ein Reviewer, fachliche Richtigkeit und Tests), Befunde beheben.
