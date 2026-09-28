# CO2 Emissions Deep-Dive Notebook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Run-All-capable emissions notebook for Baseline, Moderate, High, and Full Consolidation that produces a validated CO2 paper analysis plus comprehensive temporal, provider, vehicle, road-type, and spatial deep dives.

**Architecture:** The visible workflow lives in one new journal notebook. A focused support module owns deterministic run discovery, pickle compatibility, unit normalization, streaming aggregation, caching, KPI calculation, decomposition, and spatial helpers so those behaviors can be tested without executing a multi-million-row notebook. The notebook consumes named aggregate tables from the support module, creates exact-width journal figures and CSV exports, and is verified through a lightweight IPython clean-kernel runner because `nbclient` and the `jupyter` command are unavailable.

**Tech Stack:** Python 3.13, pandas 2.3, NumPy 2.3, Matplotlib 3.10, seaborn 0.13, GeoPandas 1.1, Shapely 2.1, PyArrow 21, MATSim Python tools, IPython 9.3, nbformat 5.10, pytest 9, PyMuPDF 1.27.

## Global Constraints

- Create `specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb`; do not modify `result-analysis-batch-mobiltTUM-paper-emissions.ipynb`.
- Use exactly `Baseline`, `Moderate Consolidation`, `High Consolidation`, and `Full Consolidation` in that order.
- Exclude `batchmedium` from every analytical output.
- Require Monday 12 May 2025 through Saturday 17 May 2025 for every scenario.
- Treat CO2 as the primary outcome and normalize internal calculations to grams.
- Detect other pollutants automatically, but include them only in a clearly labeled optional section when consistently available.
- Never concatenate all 24 raw 15-minute files; process one file at a time and cache compact aggregates.
- Use ratio-of-sums for network-wide intensity metrics.
- Use exact 7.48-inch PDF width for all core paper figures.
- Preserve the established scenario palette: Baseline red, Moderate blue, High green, Full purple.
- Optional analyses skip with an explicit message; core analyses fail early with the missing scenario, date, field, or path.
- A clean-kernel Run All must work without hidden state or prior execution of the source notebook.

## File Structure

- Create `specialIssue-journal-split/emissions_deep_dive_support.py`: testable data discovery, loading, normalization, aggregation, caching, KPI, decomposition, and spatial helpers.
- Create `specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb`: narrative workflow, data audit, paper figures, exploratory figures, exports, and result sentences.
- Create `specialIssue-journal-split/run_emissions_notebook.py`: non-interactive clean-kernel notebook executor using nbformat and IPython.
- Create `specialIssue-journal-split/tests/test_emissions_deep_dive_support.py`: synthetic unit and integration tests for the support module.
- Create at runtime `cache/emissions_deep_dive/`: derived Parquet/JSON caches only.
- Create at runtime `journal_emissions_*.pdf`, `journal_emissions_*.png`, and `journal_emissions_*.csv` in the analysis working directory, matching the existing journal artifact convention.

---

### Task 1: Canonical Scenario and Run Discovery

**Files:**
- Create: `specialIssue-journal-split/emissions_deep_dive_support.py`
- Create: `specialIssue-journal-split/tests/test_emissions_deep_dive_support.py`

**Interfaces:**
- Produces: `ScenarioSpec`, `canonical_scenario()`, `extract_run_date()`, `discover_emission_files()`, and `validate_file_inventory()`.
- Consumes: filesystem roots and filename metadata only; it must not unpickle data.

- [ ] **Step 1: Write failing tests for scenario normalization and dates**

```python
from datetime import date

import pytest

from emissions_deep_dive_support import canonical_scenario, extract_run_date


@pytest.mark.parametrize(
    ("run_name", "expected"),
    [
        ("basecase_12052025_iter150_jsprit100", "basecase"),
        ("batchmoderate_13052025_iter150_jsprit100", "batchmoderate"),
        ("batchhigh_14052025_iter150_jsprit100", "batchhigh"),
        ("BATCHFULL_15052025_mobilTUM_iter150_jsprit100", "batchfull"),
        ("batchmedium_15052025_iter150_jsprit100", None),
    ],
)
def test_canonical_scenario(run_name, expected):
    assert canonical_scenario(run_name) == expected


def test_extract_run_date_ignores_mobiltum_token():
    assert extract_run_date(
        "BATCHFULL_17052025_mobilTUM_iter150_jsprit100"
    ) == date(2025, 5, 17)
```

- [ ] **Step 2: Run the tests and verify the intended failure**

Run:

```powershell
$env:PYTHONPATH="specialIssue-journal-split"
pytest specialIssue-journal-split/tests/test_emissions_deep_dive_support.py -q
```

Expected: collection fails because `emissions_deep_dive_support` does not yet exist.

- [ ] **Step 3: Implement the scenario contract and deterministic discovery**

Add these public definitions:

```python
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
import re

SCENARIO_ORDER = ("basecase", "batchmoderate", "batchhigh", "batchfull")
SCENARIO_LABELS = {
    "basecase": "Baseline",
    "batchmoderate": "Moderate Consolidation",
    "batchhigh": "High Consolidation",
    "batchfull": "Full Consolidation",
}
REQUIRED_DATES = tuple(date(2025, 5, d) for d in range(12, 18))


@dataclass(frozen=True)
class ScenarioSpec:
    key: str
    label: str
    order: int


def canonical_scenario(run_name: str) -> str | None:
    token = str(run_name).split("_", 1)[0].lower()
    return token if token in SCENARIO_ORDER else None


def extract_run_date(run_name: str) -> date | None:
    match = re.search(r"(?:^|_)(\d{8})(?:_|$)", str(run_name))
    return datetime.strptime(match.group(1), "%d%m%Y").date() if match else None
```

Implement `discover_emission_files(roots: list[Path]) -> pandas.DataFrame` with columns `scenario`, `scenario_label`, `date`, `weekday`, `kind`, `path`, `size`, `mtime_ns`, `selected`, and `selection_reason`. `kind` is `vehicle` for `*_emissions_result.pkl` and `link_15min` for `*_emissions_15min_long.pkl`. Deduplicate by scenario, date, and kind using root priority followed by exact filename matching; retain rejected candidates in the inventory with `selected=False`.

Implement `validate_file_inventory(inventory: pandas.DataFrame) -> None` to require one selected file per scenario, required date, and kind. Its `RuntimeError` must enumerate missing or duplicated keys and must reject any selected `batchmedium` file.

- [ ] **Step 4: Add synthetic duplicate-selection tests**

```python
def test_discovery_selects_one_file_per_scenario_date_kind(tmp_path):
    preferred = tmp_path / "preferred"
    fallback = tmp_path / "fallback"
    preferred.mkdir()
    fallback.mkdir()
    name = "basecase_12052025_iter150_jsprit100_emissions_result.pkl"
    (preferred / name).write_bytes(b"preferred")
    (fallback / name).write_bytes(b"fallback")

    inventory = discover_emission_files([preferred, fallback])
    selected = inventory[inventory["selected"]]
    assert len(selected) == 1
    assert selected.iloc[0]["path"] == preferred / name


def test_inventory_reports_missing_full_day():
    inventory = complete_synthetic_inventory().query(
        "not (scenario == 'batchfull' and weekday == 'Sat' and kind == 'link_15min')"
    )
    with pytest.raises(RuntimeError, match="batchfull.*2025-05-17.*link_15min"):
        validate_file_inventory(inventory)
```

Define `complete_synthetic_inventory()` in the test file to generate all 48 expected scenario-date-kind rows.

- [ ] **Step 5: Run the focused tests**

Run the same pytest command. Expected: all Task 1 tests pass.

- [ ] **Step 6: Commit Task 1**

```powershell
git add -f specialIssue-journal-split/emissions_deep_dive_support.py specialIssue-journal-split/tests/test_emissions_deep_dive_support.py
git commit -m "feat: add canonical emissions run discovery"
```

---

### Task 2: Vehicle-Level Loading and Unit Normalization

**Files:**
- Modify: `specialIssue-journal-split/emissions_deep_dive_support.py`
- Modify: `specialIssue-journal-split/tests/test_emissions_deep_dive_support.py`

**Interfaces:**
- Consumes: selected `vehicle` paths from `discover_emission_files()`.
- Produces: `load_vehicle_emissions()`, `normalize_emission_value()`, `build_vehicle_table()`, and `detect_optional_pollutants()`.

- [ ] **Step 1: Write tests for mixed emissions representations**

```python
from emissions_deep_dive_support import (
    detect_optional_pollutants,
    normalize_emission_value,
)


def test_normalize_emission_value_supports_dict_and_scalar():
    assert normalize_emission_value({"drive": 80, "idle": 15, "cold": 5}) == {
        "drive_g": 80.0,
        "idle_g": 15.0,
        "cold_g": 5.0,
        "total_g": 100.0,
    }
    assert normalize_emission_value(42.5)["total_g"] == 42.5


def test_optional_pollutants_require_consistent_presence():
    frame = pd.DataFrame(
        {
            "scenario": ["basecase", "batchmoderate", "batchhigh", "batchfull"],
            "emissions": [
                {"NOx": 1.0}, {"NOx": 0.9}, {"NOx": 0.8}, {"NOx": 0.7}
            ],
        }
    )
    assert detect_optional_pollutants(frame) == ["NOx"]
```

- [ ] **Step 2: Verify the new tests fail**

Run the focused pytest command. Expected: imports fail for the new functions.

- [ ] **Step 3: Add pickle compatibility and normalized vehicle schema**

Use the existing `Service`, `Vehicle`, `Carrier`, and `Plan` classes from `hagrid_output_analysis.models`. Add a `CompatibleUnpickler` that maps the four legacy `__main__` class names to those exact current classes. The inspected legacy notebook defines no additional pickle classes, so any other unresolved `__main__` class raises an explicit `pickle.UnpicklingError`. Do not copy the old notebook class definitions.

Implement:

```python
def load_vehicle_emissions(path: Path) -> pd.DataFrame:
    """Load one vehicle emissions pickle and return a defensive DataFrame copy."""


def normalize_emission_value(value: object) -> dict[str, float]:
    """Return drive_g, idle_g, cold_g, and total_g in grams."""


def build_vehicle_table(inventory: pd.DataFrame) -> pd.DataFrame:
    """Concatenate only the 24 selected vehicle files into a canonical table."""
```

The canonical table must include `scenario`, `scenario_label`, `date`, `weekday`, `run_name`, `vehicle_id`, `provider`, `is_ev`, `deliveries`, `tour_km`, `duration_hours`, `vehicle_load_factor`, `drive_g`, `idle_g`, `cold_g`, and `total_g`. Derive fields from documented fallback candidates and raise an error when a core field cannot be derived.

- [ ] **Step 4: Add ratio and unit tests for the canonical table**

```python
def test_build_vehicle_table_normalizes_units_and_fields(monkeypatch, synthetic_inventory):
    monkeypatch.setattr(
        support,
        "load_vehicle_emissions",
        lambda path: synthetic_vehicle_frame(path),
    )
    result = build_vehicle_table(synthetic_inventory.query("kind == 'vehicle'"))
    assert set(result["scenario"].unique()) == set(SCENARIO_ORDER)
    assert result["total_g"].sum() == pytest.approx(
        result[["drive_g", "idle_g", "cold_g"]].sum().sum()
    )
    assert not result[["deliveries", "tour_km", "total_g"]].isna().any().any()
```

- [ ] **Step 5: Run tests and commit**

Expected: Task 1 and Task 2 tests pass.

```powershell
git add -f specialIssue-journal-split/emissions_deep_dive_support.py specialIssue-journal-split/tests/test_emissions_deep_dive_support.py
git commit -m "feat: normalize vehicle emissions inputs"
```

---

### Task 3: Streaming Link-Time Aggregation and Cache

**Files:**
- Modify: `specialIssue-journal-split/emissions_deep_dive_support.py`
- Modify: `specialIssue-journal-split/tests/test_emissions_deep_dive_support.py`

**Interfaces:**
- Consumes: selected `link_15min` paths and an optional link metadata table.
- Produces: `source_fingerprint()`, `aggregate_link_file()`, `build_link_aggregates()`, and compact Parquet/JSON cache files.

- [ ] **Step 1: Write failing tests for unit validation and cache invalidation**

```python
def test_aggregate_link_file_checks_unit_equivalence(tmp_path):
    frame = pd.DataFrame(
        {
            "link_id": ["a", "a", "b"],
            "interval_15min": ["08:00", "08:15", "08:00"],
            "emissions_g": [1000.0, 500.0, 250.0],
            "emissions_kg": [1.0, 0.5, 0.25],
            "emissions_t": [0.001, 0.0005, 0.00025],
            "area_type_id": [1, 1, 7],
        }
    )
    path = tmp_path / "sample.pkl"
    frame.to_pickle(path)
    result = aggregate_link_file(path)
    assert result.daily_total_g == pytest.approx(1750.0)
    assert result.interval_totals["emissions_g"].sum() == pytest.approx(1750.0)


def test_cache_fingerprint_changes_when_source_changes(tmp_path):
    source = tmp_path / "source.pkl"
    source.write_bytes(b"one")
    first = source_fingerprint(source)
    source.write_bytes(b"different-size")
    second = source_fingerprint(source)
    assert first != second
```

- [ ] **Step 2: Run the tests and verify failure**

Expected: the streaming aggregation interfaces are missing.

- [ ] **Step 3: Implement per-file aggregation without global concatenation**

Define:

```python
@dataclass
class LinkFileAggregate:
    daily_total_g: float
    interval_totals: pd.DataFrame
    link_totals: pd.DataFrame
    area_totals: pd.DataFrame


def aggregate_link_file(path: Path) -> LinkFileAggregate:
    frame = pd.read_pickle(path)
    # Validate g == kg*1000 == t*1_000_000 within 1e-6 relative tolerance.
    # Group immediately and release the raw frame before loading the next file.


def build_link_aggregates(
    inventory: pd.DataFrame,
    cache_dir: Path,
    force_rebuild: bool = False,
) -> dict[str, pd.DataFrame]:
    """Return daily, interval, link-week, and area aggregates."""
```

Write compact outputs as Parquet and a manifest as JSON. The manifest records all selected path fingerprints plus `cache_schema_version = 1`.

- [ ] **Step 4: Test cached and uncached equivalence**

```python
def test_build_link_aggregates_reuses_valid_cache(monkeypatch, tmp_path):
    calls = []
    monkeypatch.setattr(
        support,
        "aggregate_link_file",
        lambda path: calls.append(path) or synthetic_link_aggregate(path),
    )
    first = build_link_aggregates(synthetic_inventory, tmp_path / "cache")
    second = build_link_aggregates(synthetic_inventory, tmp_path / "cache")
    assert len(calls) == 24
    pd.testing.assert_frame_equal(first["daily"], second["daily"])
```

- [ ] **Step 5: Run tests and commit**

```powershell
git add -f specialIssue-journal-split/emissions_deep_dive_support.py specialIssue-journal-split/tests/test_emissions_deep_dive_support.py
git commit -m "feat: stream and cache link emissions"
```

---

### Task 4: KPI Tables and Exact Distance-Intensity Decomposition

**Files:**
- Modify: `specialIssue-journal-split/emissions_deep_dive_support.py`
- Modify: `specialIssue-journal-split/tests/test_emissions_deep_dive_support.py`

**Interfaces:**
- Consumes: canonical vehicle table and compact link aggregates.
- Produces: `weekly_kpis()`, `daily_kpis()`, `lsp_kpis()`, `area_kpis()`, `temporal_metrics()`, and `distance_intensity_decomposition()`.

- [ ] **Step 1: Write failing ratio-of-sums and decomposition tests**

```python
def test_weekly_kpis_use_ratio_of_sums():
    frame = pd.DataFrame(
        {
            "scenario": ["basecase", "basecase"],
            "total_g": [100.0, 900.0],
            "tour_km": [1.0, 9.0],
            "deliveries": [100, 100],
            "vehicle_id": ["a", "b"],
        }
    )
    result = weekly_kpis(frame).iloc[0]
    assert result["g_per_km"] == pytest.approx(100.0)
    assert result["g_per_parcel"] == pytest.approx(5.0)


def test_symmetric_decomposition_is_exact():
    result = distance_intensity_decomposition(
        baseline_distance=100.0,
        baseline_intensity=10.0,
        scenario_distance=80.0,
        scenario_intensity=8.0,
    )
    assert result.distance_effect_g + result.intensity_effect_g == pytest.approx(-360.0)
    assert result.total_change_g == pytest.approx(-360.0)
```

- [ ] **Step 2: Implement the KPI interfaces**

Use this exact symmetric decomposition:

```python
distance_effect = (scenario_distance - baseline_distance) * (
    scenario_intensity + baseline_intensity
) / 2
intensity_effect = (scenario_intensity - baseline_intensity) * (
    scenario_distance + baseline_distance
) / 2
```

`weekly_kpis()` returns one ordered row per scenario with `total_g`, `total_t`, `deliveries`, `distance_km`, `tours`, `active_vehicles`, `g_per_parcel`, `g_per_km`, `kg_per_tour`, `kg_per_vehicle`, `saving_g`, and `saving_pct`.

`temporal_metrics()` returns peak interval, peak value, mean active-interval value, peak-to-average ratio, coefficient of variation, and a normalized concentration index for each scenario.

- [ ] **Step 3: Add tests for Baseline reference and negative savings**

```python
def test_savings_are_relative_to_baseline_and_allow_increases():
    table = weekly_kpis(synthetic_four_scenario_vehicle_table())
    baseline = table.set_index("scenario").loc["basecase"]
    assert baseline["saving_g"] == pytest.approx(0.0)
    assert baseline["saving_pct"] == pytest.approx(0.0)
    assert table.set_index("scenario").loc["batchmoderate", "saving_pct"] < 0
```

- [ ] **Step 4: Run tests and commit**

```powershell
git add -f specialIssue-journal-split/emissions_deep_dive_support.py specialIssue-journal-split/tests/test_emissions_deep_dive_support.py
git commit -m "feat: add emissions KPI and mechanism tables"
```

---

### Task 5: Spatial Grid, Road Types, and Hotspot Transitions

**Files:**
- Modify: `specialIssue-journal-split/emissions_deep_dive_support.py`
- Modify: `specialIssue-journal-split/tests/test_emissions_deep_dive_support.py`

**Interfaces:**
- Consumes: weekly link totals, network geometries, and area metadata.
- Produces: `build_hex_grid()`, `allocate_links_to_hexes()`, `classify_road_type()`, `build_spatial_deltas()`, `hotspot_metrics()`, and `hotspot_transitions()`.

- [ ] **Step 1: Write geometry allocation and hotspot tests**

```python
from shapely.geometry import LineString, box


def test_link_to_hex_allocation_conserves_emissions():
    links = gpd.GeoDataFrame(
        {"link_id": ["a"], "emissions_g": [100.0]},
        geometry=[LineString([(0, 0), (2, 0)])],
        crs="EPSG:25832",
    )
    hexes = gpd.GeoDataFrame(
        {"hex_id": [0, 1]},
        geometry=[box(0, -1, 1, 1), box(1, -1, 2, 1)],
        crs=links.crs,
    )
    allocated = allocate_links_to_hexes(links, hexes)
    assert allocated["emissions_g"].sum() == pytest.approx(100.0)
    assert sorted(allocated["emissions_g"]) == pytest.approx([50.0, 50.0])


def test_hotspot_transition_categories():
    frame = pd.DataFrame(
        {
            "hex_id": [1, 2, 3, 4],
            "baseline_g": [100, 90, 10, 5],
            "scenario_g": [95, 20, 80, 4],
        }
    )
    result = hotspot_transitions(frame, quantile=0.5)
    assert set(result["transition"]) == {
        "persistent", "mitigated", "emerging", "low-low"
    }
```

- [ ] **Step 2: Implement spatial helpers with conservation checks**

Use the source notebook's flat-topped grid concept. Generate link-hex candidates with `geopandas.sjoin(..., predicate="intersects")`, calculate each candidate's exact intersection length with Shapely, and allocate the link value by the candidate length divided by the link's total intersected length. Every allocation returns `allocated_total_g`, `source_total_g`, and `relative_error`; fail when relative error exceeds `1e-6`.

Define road types separately from spatial area types:

```python
def classify_road_type(freespeed_mps: float, lanes: float) -> str:
    speed_kmh = float(freespeed_mps) * 3.6
    if speed_kmh <= 50:
        return "Urban road"
    if speed_kmh > 50 and float(lanes) >= 2:
        return "High-capacity road"
    return "Rural road"
```

- [ ] **Step 3: Run tests and commit**

```powershell
git add -f specialIssue-journal-split/emissions_deep_dive_support.py specialIssue-journal-split/tests/test_emissions_deep_dive_support.py
git commit -m "feat: add spatial emissions and hotspot analysis"
```

---

### Task 6: Notebook Scaffold, Audit, and Data Exports

**Files:**
- Create: `specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb`
- Create: `specialIssue-journal-split/run_emissions_notebook.py`

**Interfaces:**
- Consumes: all support-module interfaces from Tasks 1-5.
- Produces: named notebook tables `file_inventory`, `vehicle_emissions`, `weekly_emissions`, `daily_emissions`, `interval_emissions`, `lsp_emissions`, `area_emissions`, `road_emissions`, `hex_emissions`, and `hotspot_summary`.

- [ ] **Step 1: Create a failing notebook structure test**

Add to the test file:

```python
def test_notebook_has_expected_sections_and_no_medium_scenario():
    nb = nbformat.read(
        "specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb",
        as_version=4,
    )
    source = "\n".join("".join(cell.source) for cell in nb.cells)
    for heading in (
        "# CO2 Emissions and Spatial Impacts",
        "## Data Audit",
        "## Core Paper Analysis",
        "## Temporal Deep Dive",
        "## Provider and Vehicle Heterogeneity",
        "## Spatial Deep Dive",
        "## Exploratory Diagnostics",
        "## Exports and Result Sentences",
    ):
        assert heading in source
    assert "Medium Consolidation" not in source
```

- [ ] **Step 2: Create the notebook with explicit ordered cells**

Create Markdown and code cells in this order:

1. purpose, data basis, and output contract
2. imports and journal style
3. path discovery and configuration
4. file inventory and validation
5. vehicle loading and audit
6. streaming link aggregation and cache status
7. unit reconciliation and optional pollutant report
8. named KPI tables
9. core figures
10. exploratory figures
11. CSV exports
12. result-sentence generation
13. final validation summary

The configuration cell contains:

```python
SCENARIO_COLORS = {
    "Baseline": "#E41A1C",
    "Moderate Consolidation": "#377EB8",
    "High Consolidation": "#4DAF4A",
    "Full Consolidation": "#984EA3",
}
ELSEVIER_WIDTH = 7.48
HEX_SIZE_M = 2000.0
HOTSPOT_QUANTILE = 0.90
FORCE_REBUILD_CACHE = False
CACHE_DIR = Path("cache/emissions_deep_dive")
```

- [ ] **Step 3: Implement the non-interactive notebook runner**

```python
import argparse
import json
import os
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from IPython.core.interactiveshell import InteractiveShell


def execute_notebook(path: Path) -> None:
    notebook = json.loads(path.read_text(encoding="utf-8"))
    shell = InteractiveShell.instance()
    plt.show = lambda *args, **kwargs: None
    for index, cell in enumerate(notebook["cells"]):
        if cell["cell_type"] != "code":
            continue
        result = shell.run_cell("".join(cell["source"]), store_history=False)
        error = result.error_before_exec or result.error_in_exec
        if error:
            raise RuntimeError(f"Cell {index} failed") from error


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("notebook", type=Path)
    args = parser.parse_args()
    os.chdir(Path(__file__).resolve().parents[1])
    execute_notebook(args.notebook)
```

- [ ] **Step 4: Run structure tests and a data-audit-only smoke mode**

The notebook configuration honors `EMISSIONS_SMOKE_ONLY=1` by stopping after KPI tables and before maps. Run:

```powershell
$env:PYTHONPATH="specialIssue-journal-split"
pytest specialIssue-journal-split/tests/test_emissions_deep_dive_support.py -q
$env:EMISSIONS_SMOKE_ONLY="1"
python specialIssue-journal-split/run_emissions_notebook.py specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb
Remove-Item Env:EMISSIONS_SMOKE_ONLY
```

Expected: tests pass and the runner prints four scenarios with six selected dates each.

- [ ] **Step 5: Commit Task 6**

```powershell
git add -f specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb specialIssue-journal-split/run_emissions_notebook.py specialIssue-journal-split/tests/test_emissions_deep_dive_support.py
git commit -m "feat: scaffold emissions deep-dive notebook"
```

---

### Task 7: Core Paper Figures

**Files:**
- Modify: `specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb`
- Modify: `specialIssue-journal-split/tests/test_emissions_deep_dive_support.py`

**Interfaces:**
- Consumes: named KPI and spatial tables created in Task 6.
- Produces: eight core PDFs/PNGs and matching source CSVs.

- [ ] **Step 1: Add a figure helper cell**

The notebook defines:

```python
def save_journal_figure(fig, stem: str) -> None:
    fig.set_size_inches(ELSEVIER_WIDTH, fig.get_size_inches()[1], forward=True)
    fig.savefig(f"{stem}.pdf", dpi=300)
    fig.savefig(f"{stem}.png", dpi=220)
    plt.show()
    plt.close(fig)
```

Do not use `bbox_inches="tight"` because it changes physical PDF width.

- [ ] **Step 2: Implement the weekly overview and decomposition figures**

Create:

- `journal_emissions_01_weekly_overview.pdf`: a 2x2 grid showing total tonnes, kg per parcel, g/km, and savings versus Baseline.
- `journal_emissions_02_distance_intensity_decomposition.pdf`: stacked distance and intensity contributions with a black diamond/line for total change.

Export the plotted tables as `journal_emissions_01_weekly_overview_source.csv` and `journal_emissions_02_distance_intensity_decomposition_source.csv`.

- [ ] **Step 3: Implement chronology and temporal-profile figures**

Create:

- `journal_emissions_03_daily_chronology.pdf`: daily batch/non-batch segments and cumulative scenario totals.
- `journal_emissions_04_temporal_profiles.pdf`: scenario small multiples plus a weekday by 15-minute deviation heatmap.

Use common axes where comparisons require them and annotate peak intervals without a supertitle.

- [ ] **Step 4: Implement provider, area, spatial, and hotspot figures**

Create:

- `journal_emissions_05_lsp_heterogeneity.pdf`: provider savings and absolute contribution to network-wide savings.
- `journal_emissions_06_area_type_effects.pdf`: contribution bars plus Urban/Suburban/Rural percentage matrix.
- `journal_emissions_07_spatial_hex_maps.pdf`: Baseline absolute map and three shared-scale delta maps.
- `journal_emissions_08_hotspot_concentration.pdf`: Lorenz/concentration panel plus hotspot transitions.

Every panel label uses `a)`, `b)`, and so on in bold at the same font size as the existing cost and fleet figures.

- [ ] **Step 5: Add figure existence and geometry tests**

```python
import fitz


CORE_PDFS = [f"journal_emissions_{i:02d}_{name}.pdf" for i, name in CORE_NAMES]


def test_core_pdf_widths_after_execution():
    for filename in CORE_PDFS:
        doc = fitz.open(filename)
        assert doc.page_count == 1
        assert doc[0].rect.width / 72 == pytest.approx(7.48, abs=0.002)
```

Mark this test `@pytest.mark.artifact` so the fast synthetic suite can run independently.

- [ ] **Step 6: Run smoke execution and commit**

Run smoke mode first, then a normal execution using the existing cache. Expected: all eight core PDFs and CSV sources are created.

```powershell
git add -f specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb specialIssue-journal-split/tests/test_emissions_deep_dive_support.py
git commit -m "feat: add paper-ready emissions figures"
```

---

### Task 8: Exploratory Deep-Dive Figures and Optional Pollutants

**Files:**
- Modify: `specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb`

**Interfaces:**
- Consumes: vehicle, LSP, interval, area, road, link, hex, hotspot, and optional-pollutant tables.
- Produces: stable `journal_emissions_extra_*.pdf`, PNG, and CSV artifacts.

- [ ] **Step 1: Add vehicle and provider exploratory figures**

Create:

- `journal_emissions_extra_01_vehicle_phase_space.pdf`: distance versus total CO2, colored by scenario and faceted or shaped by LSP.
- `journal_emissions_extra_02_lsp_waterfall.pdf`: ordered LSP contributions to total savings.
- `journal_emissions_extra_03_outlier_diagnostics.pdf`: robust standardized vehicle and LSP-day outliers.
- `journal_emissions_extra_04_correlation_matrix.pdf`: Spearman correlations among emissions, distance, utilization, deliveries, and duration.

Use log axes only where zero handling is explicit and visible in the caption cell.

- [ ] **Step 2: Add temporal exploratory figures**

Create:

- `journal_emissions_extra_05_temporal_ridges.pdf`: scenario 15-minute profiles by weekday.
- `journal_emissions_extra_06_peak_shift.pdf`: peak timing and peak-to-average changes.

If ridgeline density estimation obscures absolute magnitude, use aligned small multiples instead and record that choice in the Markdown interpretation cell.

- [ ] **Step 3: Add spatial and efficiency exploratory figures**

Create:

- `journal_emissions_extra_07_spatial_lorenz.pdf`
- `journal_emissions_extra_08_road_type_decomposition.pdf`
- `journal_emissions_extra_09_hotspot_persistence.pdf`
- `journal_emissions_extra_10_abatement_efficiency.pdf`
- `journal_emissions_extra_11_scenario_scorecard.pdf`

The abatement figure reports saved CO2 per avoided kilometer and per avoided tour. It must display undefined ratios as missing rather than infinite when the denominator is zero or changes sign.

- [ ] **Step 4: Add guarded optional-pollutant analysis**

```python
if optional_pollutants:
    optional_pollutant_summary = build_optional_pollutant_summary(
        vehicle_emissions, optional_pollutants
    )
    # Create journal_emissions_extra_12_optional_pollutants.pdf
else:
    optional_pollutant_summary = pd.DataFrame()
    print("No consistently separated non-CO2 pollutants were found; optional analysis skipped.")
```

- [ ] **Step 5: Execute with cache and commit**

Expected: all applicable extra figures render, and optional pollutants skip cleanly for the currently inspected data.

```powershell
git add -f specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb
git commit -m "feat: add exploratory emissions analyses"
```

---

### Task 9: Full Run-All, Reconciliation, and Visual PDF QA

**Files:**
- Modify: `specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb`
- Modify: `specialIssue-journal-split/run_emissions_notebook.py`
- Modify: `specialIssue-journal-split/tests/test_emissions_deep_dive_support.py`

**Interfaces:**
- Consumes: the complete notebook and all source data.
- Produces: verified notebook, figures, CSVs, cache manifest, and final audit report.

- [ ] **Step 1: Run the full synthetic test suite**

```powershell
$env:PYTHONPATH="specialIssue-journal-split"
pytest specialIssue-journal-split/tests/test_emissions_deep_dive_support.py -q -m "not artifact"
```

Expected: zero failures.

- [ ] **Step 2: Remove derived cache and execute from a clean kernel**

Resolve and verify that `cache/emissions_deep_dive` is inside the workspace before removal. Then run:

```powershell
Remove-Item -LiteralPath "cache/emissions_deep_dive" -Recurse -Force
python specialIssue-journal-split/run_emissions_notebook.py specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb
```

Expected: exit code 0, 24 selected vehicle files, 24 selected link-time files, and all paper/exploratory outputs created.

- [ ] **Step 3: Execute a second time and verify cache reuse**

Run the same command again. Expected: link aggregates are reported as cache hits and all aggregate CSV hashes match the first run.

- [ ] **Step 4: Run artifact and scenario-contract tests**

```powershell
$env:PYTHONPATH="specialIssue-journal-split"
pytest specialIssue-journal-split/tests/test_emissions_deep_dive_support.py -q -m artifact
```

Add assertions that every exported scenario table contains the four labels in canonical order and contains neither `batchmedium` nor `Medium Consolidation`.

- [ ] **Step 5: Render every PDF for visual inspection**

Use PyMuPDF to render into `tmp/pdfs/emissions/`:

```python
from pathlib import Path
import fitz

out_dir = Path("tmp/pdfs/emissions")
out_dir.mkdir(parents=True, exist_ok=True)
for path in sorted(Path.cwd().glob("journal_emissions_*.pdf")):
    doc = fitz.open(path)
    pix = doc[0].get_pixmap(matrix=fitz.Matrix(2.2, 2.2), alpha=False)
    pix.save(out_dir / f"{path.stem}.png")
```

Inspect each rendered image for clipped labels, overlapping legends, unreadable annotations, inconsistent axes, misleading scales, and excessive whitespace. Fix and re-render any defective figure.

- [ ] **Step 6: Verify numerical reconciliation**

The final audit cell must assert:

```python
assert tuple(weekly_emissions["scenario"].unique()) == SCENARIO_ORDER
assert not file_inventory.query("selected and scenario == 'batchmedium'").shape[0]
assert set(file_inventory.query("selected")["weekday"]) == {
    "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"
}
assert spatial_allocation_audit["relative_error"].abs().max() <= 1e-6
```

For vehicle-versus-link weekly totals, calculate and print the relative difference by scenario and require `relative_difference <= 0.02`. The 2-percent tolerance covers numeric aggregation and allocation differences while still detecting mismatched run selection or emission phases. A larger difference fails the final audit and must be resolved in the loader or aggregation logic; it must not be waived in the notebook.

- [ ] **Step 7: Clean temporary renders and commit the verified notebook**

Delete only `tmp/pdfs/emissions` after confirming it resolves inside the workspace. Commit only the new notebook, support module, runner, and tests:

```powershell
git add -f specialIssue-journal-split/07_co2_emissions_and_spatial_impacts.ipynb specialIssue-journal-split/emissions_deep_dive_support.py specialIssue-journal-split/run_emissions_notebook.py specialIssue-journal-split/tests/test_emissions_deep_dive_support.py
git commit -m "feat: complete CO2 emissions deep-dive analysis"
```

Do not stage pre-existing modified notebooks or files outside this exact list.
