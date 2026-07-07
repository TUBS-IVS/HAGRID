# 1e — Canonical KPI CSV + Combined Performant Dashboard — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every HAGRID run emits a canonical long-format KPI CSV (`run_id, study_area, scenario, operation_mode, kpi_group, kpi_name, value, unit, source`) plus a lean (< 1 MB) combined pax+freight HTML dashboard into its own run directory, and a multi-run comparison dashboard (Baseline vs. Shared-Use vs. Modular, < 3 MB) is generated from those CSVs — so 1c/1d emit comparable rows from day one.

**Architecture:** Java contributes exactly one new artifact — a machine-readable `run_metadata.json` written by the simulation runner (only Java knows scenario/study-area/fleet at run time). Everything else is a new Python package `analysis/kpi/` that aggregates the *authoritative* MATSim analysis outputs (DRT stats CSVs, CarriersAnalysis TSVs, carrier attributes; events only for service-time KPIs) into `kpis_long.csv` / `kpis_wide.csv` / `kpi_timeseries.csv`, then renders dashboards **from those CSVs only** — which guarantees the canonical CSV is sufficient for cross-scenario comparison. No maps, no per-vehicle geometry, no inline Plotly (that is what makes today's DRT dashboard 26 MB).

**Tech Stack:** Java 17 (metadata writer only, no new deps, hand-rolled flat JSON), Python 3 + pandas (aggregation), Chart.js 4.4.4 vendored inline (~205 KB, same lib the Java dashboard already uses via CDN), pytest for the Python side, JUnit for the Java side.

## Decisions (user can veto)

- **D1 — KPI aggregation lives in Python post-processing, not in a Java `IntegratedKPIHandler`** (adapts spec §5.3/§7). Rationale: the authoritative KPI sources are MATSim's own analysis CSVs (`drt_vehicle_stats` is authoritative; event reconstruction runs ~3 % low — see memory `reference_drt_analysis_data`), the dashboards already live in Python, and duplicating extraction in Java doubles maintenance. 1c/1d in-sim KPIs (χ-acceptance, undelivered δ, capsule swaps) enter via *additional output files* their event handlers write into the run dir + one new extractor module each (see "Interface contract for 1c/1d").
- **D2 — Schema = spec's 8 columns + a 9th `source` column** (which file/method produced the value), stable from day one. New KPIs add rows, never columns.
- **D3 — CSV conventions:** `;` delimiter, dot decimals, UTF-8 — matches the existing HAGRID (`RoutingStatistics`) and MATSim DRT CSV conventions. German comma formatting stays presentation-only.
- **D4 — Dashboard tech:** self-contained HTML with **vendored Chart.js** (works offline), rendered from the KPI/timeseries CSVs only. Explicitly OUT: maps, per-vehicle tour geometry, per-event data, inline plotly.min.js. Detail diagnosis (tour maps, heatmaps) stays in the two legacy dashboards until their later migration ("mittelfristig ablösen" — the data layer built here is what they will migrate onto).
- **D5 — Landing places:** per-run artifacts in `hagrid-matsim-output/<run>/analysis/` (user requirement); comparison HTML in `hagrid-matsim-output/comparison/`.
- **D6 — `operation_mode` = `"conventional"`** for everything that exists today; 1c/1d thread the autonomy switch through `HAGRIDSimulationConfig` into the metadata writer.
- **D7 — Performance budgets are acceptance criteria:** per-run dashboard **< 1 MB**, comparison with 4 runs **< 3 MB**, no network required except the optional map-free CDN-less page must render offline.

## Global Constraints

- Long-CSV column order verbatim: `run_id;study_area;scenario;operation_mode;kpi_group;kpi_name;value;unit;source`.
- `kpi_group` ∈ {`system`, `passenger`, `freight`, `economic`, `channel`} (spec §7 groups, lowercase).
- Cost KPIs from the placeholder model carry `_placeholder` in `kpi_name` (memory: COST KPI = PLACEHOLDER, must be refined before headline evaluation).
- Windows console: **ASCII-only `print()`** (cp1252 crashes on non-ASCII — memory `feedback_windows_terminal`), run scripts with `python -u`.
- Never edit the legacy dashboards (`DashboardGenerator.java`, `build_drt_dashboard.py`, `build_dashboard.py`, `build_vehicle_tours.py`) in this plan.
- No `.gitignore` changes; `git add` explicit file lists only.
- Branch `hendrik`; never merge to master without asking.
- Charts follow the dataviz rules baked into Task 9/10: no dual axes, no pies (part-to-whole = horizontal stacked bar), categorical hues in fixed slot order (never cycled), legend for ≥ 2 series, sequential single-hue for magnitudes, hover tooltips on (Chart.js default), text in ink colors never series colors.
- Existing runs must stay analyzable: every reader has a **legacy fallback** (dir-name parsing) because finished runs (married120 …) have no `run_metadata.json`.

## File Structure

```
src/main/java/hagrid/simulation/RunMetadataWriter.java     ← NEW (Task 1)
src/main/java/hagrid/simulation/SimulationRunnerUtils.java ← MODIFY (1 call, Task 1)
src/test/java/hagrid/simulation/RunMetadataWriterTest.java ← NEW (Task 1)

parcel-demand-2-matsim-pipeline/analysis/kpi/              ← NEW package (Tasks 2-10)
├── common.py            ← row() helper + shared constants
├── run_meta.py          ← run_metadata.json loader + legacy dir-name fallback
├── events_cache.py      ← single-pass events → drt-lines + freight-service-starts caches
├── extract_drt.py       ← pax/system/channel rows from DRT stats CSVs (+ optional events)
├── extract_freight.py   ← freight rows from CarriersAnalysis TSVs + carrier attributes
├── economics.py         ← placeholder cost model rows (Rudolph 20/5 split)
├── kpi_writer.py        ← kpis_long.csv + kpis_wide.csv
├── timeseries.py        ← kpi_timeseries.csv (hourly series)
├── render.py            ← HTML rendering shared by per-run + comparison dashboards
├── build_kpis.py        ← CLI: one run dir → CSVs + per-run dashboard
├── build_comparison.py  ← CLI: N run dirs → comparison dashboard
├── vendor/chart.umd.min.js  ← vendored Chart.js 4.4.4 (Task 9)
└── tests/               ← pytest (fixtures = verbatim heads of real married120 outputs)
```

All Python files start with `# -*- coding: utf-8 -*-`. Scripts are run from `analysis/kpi/` (imports are same-dir; `drt-headline/` is added to `sys.path` for `drt_service_time`).

## Interface contract for 1c/1d (why 1e comes first)

1c (Shared-Use) and 1d (Modular) plug in WITHOUT touching the schema:
- Their run configs thread `operation_mode` (conventional/autonomous) into `HAGRIDSimulationConfig`; `RunMetadataWriter` then emits it (today it hardcodes `"conventional"`).
- Their in-sim event handlers write scenario-specific outputs into the run dir (e.g. `shareduse_channel_stats.csv` with door/Packstation/undelivered counts; `modular_swap_stats.csv` with swaps/completed tours).
- Each contributes one extractor module (`extract_shareduse.py`, `extract_modular.py`) returning `row(...)` dicts, registered in the `EXTRACTORS` dispatch inside `build_kpis.py`. New KPIs = new rows (e.g. `channel/undelivered_rate`, `freight/tour_completion_rate`), zero schema change.
- Comparison colors: scenario→categorical-slot map in `render.py` (`DRT_BASELINE`→1, `DRT_SHAREDUSE`→2, `DRT_MODULAR`→3, `LMD_BASELINE`→4) — color follows the scenario entity, never the run count.

---

### Task 1: `RunMetadataWriter` (Java)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/RunMetadataWriter.java`
- Modify: `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java` (one call at the end of `runSimulation`)
- Test: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/RunMetadataWriterTest.java`

**Interfaces:**
- Consumes: `HAGRIDSimulationConfig` getters (`getRunId()`, `getConcept()`, `getTag()`, `getFormattedDate()`, `getMaxIterations()`, `getJspritIterations()`, `getStudyArea()`, `getFleetSize()`, `isDrtScenario()`, `isDrtWithFreight()`, `getOutputDirectory()` — all exist today).
- Produces: `run_metadata.json` in the MATSim output dir; `RunMetadataWriter.write(HAGRIDSimulationConfig cfg, Path targetDir)` and the JSON keys listed below (Task 2's Python loader consumes them verbatim): `run_id, run_dir_name, scenario, study_area, operation_mode, tag, sim_date, matsim_iterations, jsprit_iterations, fleet_size, drt_with_freight, created`.

- [ ] **Step 1: Write the failing test**

```java
package hagrid.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunMetadataWriterTest {

    @TempDir
    Path tmp;

    @Test
    void writesFlatJsonWithAllKeys() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", "DRT_BASELINE_13052025_married120");
        m.put("run_dir_name", "DRT_BASELINE_13052025_married120_iter150_jsprit100");
        m.put("scenario", "DRT_BASELINE");
        m.put("study_area", "lausitz_hoyerswerda");
        m.put("operation_mode", "conventional");
        m.put("tag", "married120");
        m.put("sim_date", "13052025");
        m.put("matsim_iterations", 150);
        m.put("jsprit_iterations", 100);
        m.put("fleet_size", 120);
        m.put("drt_with_freight", true);
        m.put("created", "2026-07-06T12:00:00");

        Path file = RunMetadataWriter.writeMap(m, tmp);

        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        assertTrue(json.contains("\"run_id\": \"DRT_BASELINE_13052025_married120\""));
        assertTrue(json.contains("\"matsim_iterations\": 150"));
        assertTrue(json.contains("\"fleet_size\": 120"));
        assertTrue(json.contains("\"drt_with_freight\": true"));
        // valid enough JSON for Python's json.loads: braces + quoted keys
        assertTrue(json.trim().startsWith("{") && json.trim().endsWith("}"));
    }

    @Test
    void nullFleetSizeSerializesAsJsonNull() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", "LMD_BASELINE_13052025_x");
        m.put("fleet_size", null);
        Path file = RunMetadataWriter.writeMap(m, tmp);
        assertTrue(Files.readString(file).contains("\"fleet_size\": null"));
    }

    @Test
    void escapesQuotesAndBackslashes() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "we\"ird\\tag");
        Path file = RunMetadataWriter.writeMap(m, tmp);
        assertTrue(Files.readString(file).contains("\"tag\": \"we\\\"ird\\\\tag\""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `parcel-demand-2-matsim-pipeline/`): `mvn -q test -Dtest=RunMetadataWriterTest`
Expected: COMPILATION FAILURE — `RunMetadataWriter` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package hagrid.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a machine-readable {@code run_metadata.json} into the MATSim output
 * directory so downstream analysis (analysis/kpi) can bind
 * run_id / study_area / scenario / operation_mode without parsing directory names.
 */
public final class RunMetadataWriter {

    public static final String FILE_NAME = "run_metadata.json";

    private RunMetadataWriter() {
    }

    /** Collects metadata from the run config and writes it into {@code targetDir}. */
    public static Path write(HAGRIDSimulationConfig cfg, Path targetDir) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", cfg.getRunId());
        m.put("run_dir_name", targetDir.getFileName().toString());
        m.put("scenario", cfg.getConcept().toUpperCase());
        m.put("study_area", cfg.getStudyArea().name().toLowerCase());
        m.put("operation_mode", "conventional"); // 1c/1d thread the autonomy switch through here
        m.put("tag", cfg.getTag() == null ? "" : cfg.getTag());
        m.put("sim_date", cfg.getFormattedDate());
        m.put("matsim_iterations", cfg.getMaxIterations());
        m.put("jsprit_iterations", cfg.getJspritIterations());
        m.put("fleet_size", cfg.isDrtScenario() ? cfg.getFleetSize() : null);
        m.put("drt_with_freight", cfg.isDrtWithFreight());
        m.put("created", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return writeMap(m, targetDir);
    }

    /** Serialization layer, unit-tested without a full config object. */
    static Path writeMap(Map<String, Object> m, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path file = targetDir.resolve(FILE_NAME);
        Files.writeString(file, toJson(m), StandardCharsets.UTF_8);
        return file;
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            sb.append("  \"").append(e.getKey()).append("\": ").append(jsonValue(e.getValue()));
            if (++i < m.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        return sb.append("}\n").toString();
    }

    private static String jsonValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return '"' + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=RunMetadataWriterTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Wire into the runner**

In `SimulationRunnerUtils.runSimulation(HAGRIDSimulationConfig cfg)`: locate the point where the MATSim `Controler` run has completed (end of the method, after the run finishes, before returning/logging the end). Add:

```java
try {
    RunMetadataWriter.write(cfg, cfg.getOutputDirectory());
    LOG.info("run_metadata.json written to {}", cfg.getOutputDirectory());
} catch (IOException e) {
    LOG.warn("Could not write run_metadata.json (analysis falls back to dir-name parsing)", e);
}
```

`cfg.getOutputDirectory()` is the MATSim output dir (`hagrid-matsim-output/<runId>_iter<N>_jsprit<M>/`) — verify by reading the getter's Javadoc in `HAGRIDSimulationConfig.java:393`; if it points elsewhere, use the same path expression the dashboard generator uses (`SimulationRunnerUtils.generateDashboard`, `SimulationRunnerUtils.java:348-395`). The write must be failure-tolerant (a metadata bug must never kill an 8-hour run) — hence the catch-and-warn.

- [ ] **Step 6: Extend the existing married e2e test**

Find it: `Glob src/test/java/**/*.java` for the married-branch e2e added in commit `3dcae92` (asserts a DRT+freight run boots). Add one assertion after its run completes:

```java
assertTrue(java.nio.file.Files.exists(outputDir.resolve("run_metadata.json")),
        "run_metadata.json missing from MATSim output dir");
```

(`outputDir` = whatever Path the e2e already uses for output assertions.)

- [ ] **Step 7: Run the full suite**

Run: `mvn -q test`
Expected: all green (~271 + 3 new).

- [ ] **Step 8: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/RunMetadataWriter.java \
        parcel-demand-2-matsim-pipeline/src/main/java/hagrid/simulation/SimulationRunnerUtils.java \
        parcel-demand-2-matsim-pipeline/src/test/java/hagrid/simulation/RunMetadataWriterTest.java \
        <e2e test file>
git commit -m "feat(analysis): RunMetadataWriter emits run_metadata.json per run (1e Task 1)"
```

---

### Task 2: Python scaffold — `common.py`, `run_meta.py`, pytest

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/kpi/common.py`
- Create: `parcel-demand-2-matsim-pipeline/analysis/kpi/run_meta.py`
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_run_meta.py`

**Interfaces:**
- Produces: `common.row(kpi_group, kpi_name, value, unit, source) -> dict` (used by every extractor); `run_meta.RunMeta` dataclass with fields `run_id, run_dir_name, scenario, study_area, operation_mode, tag, matsim_iterations, jsprit_iterations, fleet_size, prefix`; `run_meta.load_run_meta(run_dir: Path) -> RunMeta`.
- `prefix` = the MATSim file prefix inside the run dir (equals `run_id`).

- [ ] **Step 1: Write the failing tests**

`analysis/kpi/tests/test_run_meta.py`:

```python
# -*- coding: utf-8 -*-
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from run_meta import load_run_meta, parse_legacy_dir_name


def test_parse_legacy_tagged():
    m = parse_legacy_dir_name("DRT_BASELINE_13052025_married120_iter150_jsprit100")
    assert m.run_id == "DRT_BASELINE_13052025_married120"
    assert m.scenario == "DRT_BASELINE"
    assert m.tag == "married120"
    assert m.matsim_iterations == 150
    assert m.jsprit_iterations == 100
    assert m.study_area == "lausitz_hoyerswerda"
    assert m.operation_mode == "conventional"
    assert m.fleet_size is None
    assert m.prefix == "DRT_BASELINE_13052025_married120"


def test_parse_legacy_untagged():
    m = parse_legacy_dir_name("DRT_BASELINE_13052025_iter1_jsprit100")
    assert m.run_id == "DRT_BASELINE_13052025"
    assert m.tag == ""


def test_parse_legacy_multiword_tag_and_lmd():
    m = parse_legacy_dir_name("LMD_BASELINE_13052025_localdepots_stagger_c100_iter0_jsprit100")
    assert m.scenario == "LMD_BASELINE"
    assert m.tag == "localdepots_stagger_c100"
    assert m.study_area == "lausitz_hoyerswerda"


def test_parse_legacy_hannover():
    m = parse_legacy_dir_name("BASECASE_13052025_V1_iter150_jsprit10000")
    assert m.study_area == "hannover"


def test_json_takes_precedence(tmp_path):
    d = tmp_path / "DRT_BASELINE_13052025_married120_iter150_jsprit100"
    d.mkdir()
    (d / "run_metadata.json").write_text(json.dumps({
        "run_id": "DRT_BASELINE_13052025_married120",
        "run_dir_name": d.name,
        "scenario": "DRT_BASELINE",
        "study_area": "lausitz_hoyerswerda",
        "operation_mode": "autonomous",
        "tag": "married120",
        "sim_date": "13052025",
        "matsim_iterations": 150,
        "jsprit_iterations": 100,
        "fleet_size": 120,
        "drt_with_freight": True,
        "created": "2026-07-06T12:00:00",
    }), encoding="utf-8")
    m = load_run_meta(d)
    assert m.operation_mode == "autonomous"     # only the JSON knows this
    assert m.fleet_size == 120
```

- [ ] **Step 2: Run to verify failure**

Run (from `analysis/kpi/`): `python -u -m pytest tests/test_run_meta.py -q` (if pytest is missing: `pip install pytest`)
Expected: FAIL — `ModuleNotFoundError: No module named 'run_meta'`.

- [ ] **Step 3: Implement**

`analysis/kpi/common.py`:

```python
# -*- coding: utf-8 -*-
"""Shared helpers for the canonical KPI pipeline (1e)."""

KPI_GROUPS = ("system", "passenger", "freight", "economic", "channel")


def row(kpi_group, kpi_name, value, unit, source):
    """One KPI observation. run_id/study_area/scenario/operation_mode are
    added by the writer from RunMeta — extractors stay run-agnostic."""
    assert kpi_group in KPI_GROUPS, kpi_group
    return {"kpi_group": kpi_group, "kpi_name": kpi_name,
            "value": value, "unit": unit, "source": source}
```

`analysis/kpi/run_meta.py`:

```python
# -*- coding: utf-8 -*-
"""Run metadata: read run_metadata.json (RunMetadataWriter) or fall back to
parsing the legacy hagrid-matsim-output directory name."""
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

_LEGACY = re.compile(
    r"^(?P<concept>[A-Z0-9_]+?)_(?P<date>\d{8})"
    r"(?:_(?P<tag>.+?))?_iter(?P<it>\d+)_jsprit(?P<js>\d+)$")


@dataclass
class RunMeta:
    run_id: str
    run_dir_name: str
    scenario: str
    study_area: str
    operation_mode: str
    tag: str
    matsim_iterations: int
    jsprit_iterations: int
    fleet_size: Optional[int]
    prefix: str


def load_run_meta(run_dir):
    run_dir = Path(run_dir)
    meta_file = run_dir / "run_metadata.json"
    if meta_file.exists():
        j = json.loads(meta_file.read_text(encoding="utf-8"))
        return RunMeta(
            run_id=j["run_id"], run_dir_name=j["run_dir_name"],
            scenario=j["scenario"], study_area=j["study_area"],
            operation_mode=j["operation_mode"], tag=j.get("tag", ""),
            matsim_iterations=int(j["matsim_iterations"]),
            jsprit_iterations=int(j["jsprit_iterations"]),
            fleet_size=j.get("fleet_size"), prefix=j["run_id"])
    return parse_legacy_dir_name(run_dir.name)


def parse_legacy_dir_name(name):
    m = _LEGACY.match(name)
    if not m:
        raise ValueError("cannot parse run dir name: " + name)
    concept, date, tag = m.group("concept"), m.group("date"), m.group("tag") or ""
    run_id = concept + "_" + date + ("_" + tag if tag else "")
    study = "lausitz_hoyerswerda" if concept.startswith(("DRT_", "LMD_")) else "hannover"
    return RunMeta(run_id=run_id, run_dir_name=name, scenario=concept,
                   study_area=study, operation_mode="conventional", tag=tag,
                   matsim_iterations=int(m.group("it")),
                   jsprit_iterations=int(m.group("js")),
                   fleet_size=None, prefix=run_id)
```

- [ ] **Step 4: Run tests**

Run: `python -u -m pytest tests/test_run_meta.py -q`
Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/common.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/run_meta.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_run_meta.py
git commit -m "feat(kpi): run-metadata loader with legacy dir-name fallback (1e Task 2)"
```

---

### Task 3: `events_cache.py` — one events pass, two caches

**Files:**
- Create: `analysis/kpi/events_cache.py`
- Test: `analysis/kpi/tests/test_events_cache.py`

**Interfaces:**
- Consumes: `<run>/<prefix>.output_events.xml.gz`.
- Produces: `ensure_caches(run_dir: Path, prefix: str) -> (drt_cache: Path, freight_cache: Path)`. The drt cache reuses the EXACT name and filter (`"drt_" in line`) of `build_drt_dashboard.py`'s existing cache (`<prefix>.drt_events_filtered.txt`) so both tools share it; the freight cache (`<prefix>.freight_service_starts.txt`) holds `actstart`/`actType="service"` lines of freight agents (mirrors `FreightEventHandler`'s service-start counting).

- [ ] **Step 1: Write the failing test**

```python
# -*- coding: utf-8 -*-
import gzip
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from events_cache import ensure_caches

EVENTS = """<?xml version="1.0" encoding="utf-8"?>
<events version="1.0">
<event time="100.0" type="dvrpTaskStarted" dvrpVehicle="drt_1" taskType="STAY"/>
<event time="200.0" type="PersonEntersVehicle" person="p1" vehicle="drt_1"/>
<event time="300.0" type="actstart" person="freight_dhl_veh_1" actType="service" link="l1"/>
<event time="400.0" type="actend" person="freight_dhl_veh_1" actType="service" link="l1"/>
<event time="500.0" type="actstart" person="p2" actType="home" link="l2"/>
</events>
"""


def _make_run(tmp_path):
    prefix = "DRT_BASELINE_13052025_test"
    run = tmp_path / (prefix + "_iter1_jsprit1")
    run.mkdir()
    with gzip.open(run / (prefix + ".output_events.xml.gz"), "wt", encoding="utf-8") as f:
        f.write(EVENTS)
    return run, prefix


def test_builds_both_caches(tmp_path):
    run, prefix = _make_run(tmp_path)
    drt, frt = ensure_caches(run, prefix)
    drt_lines = drt.read_text(encoding="utf-8").splitlines()
    frt_lines = frt.read_text(encoding="utf-8").splitlines()
    assert len(drt_lines) == 2                       # the two drt_ lines
    assert all("drt_" in l for l in drt_lines)
    assert len(frt_lines) == 1                       # only freight service actstart
    assert 'actType="service"' in frt_lines[0] and "freight" in frt_lines[0]


def test_reuses_existing_caches(tmp_path):
    run, prefix = _make_run(tmp_path)
    drt, frt = ensure_caches(run, prefix)
    stamp = "SENTINEL\n"
    drt.write_text(stamp, encoding="utf-8")          # simulate pre-existing cache
    drt2, frt2 = ensure_caches(run, prefix)
    assert drt2.read_text(encoding="utf-8") == stamp  # untouched when both exist
```

- [ ] **Step 2: Run to verify failure**

Run: `python -u -m pytest tests/test_events_cache.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'events_cache'`.

- [ ] **Step 3: Implement**

```python
# -*- coding: utf-8 -*-
"""Single pass over output_events.xml.gz producing two per-run line caches:
- <prefix>.drt_events_filtered.txt   (same name + filter as build_drt_dashboard.py)
- <prefix>.freight_service_starts.txt (freight 'service' actstart lines)
If either cache is missing, BOTH are rebuilt in one pass (~1-2 min on a 90 MB
events file; the drt rebuild is byte-identical, so sharing with the legacy
dashboard stays safe)."""
from pathlib import Path
import gzip

DRT_SUFFIX = ".drt_events_filtered.txt"
FREIGHT_SUFFIX = ".freight_service_starts.txt"


def ensure_caches(run_dir, prefix):
    run_dir = Path(run_dir)
    drt = run_dir / (prefix + DRT_SUFFIX)
    frt = run_dir / (prefix + FREIGHT_SUFFIX)
    if drt.exists() and frt.exists():
        return drt, frt
    events = run_dir / (prefix + ".output_events.xml.gz")
    if not events.exists():
        raise FileNotFoundError(str(events))
    with gzip.open(events, "rt", encoding="utf-8") as f, \
            open(drt, "w", encoding="utf-8") as fd, \
            open(frt, "w", encoding="utf-8") as ff:
        for line in f:
            if "drt_" in line:
                fd.write(line)
            if 'type="actstart"' in line and 'actType="service"' in line and "freight" in line:
                ff.write(line)
    return drt, frt
```

- [ ] **Step 4: Run tests**

Run: `python -u -m pytest tests/test_events_cache.py -q`
Expected: 2 passed.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/events_cache.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_events_cache.py
git commit -m "feat(kpi): single-pass events caches (drt + freight service starts) (1e Task 3)"
```

---

### Task 4: `extract_drt.py` — passenger/system/channel rows

**Files:**
- Create: `analysis/kpi/extract_drt.py`
- Test: `analysis/kpi/tests/test_extract_drt.py` + fixture dir `tests/fixtures/drtrun/`

**Interfaces:**
- Consumes: `common.row`; the run dir's `<prefix>.drt_customer_stats_drt.csv`, `.drt_vehicle_stats_drt.csv`, `.drt_sharing_metrics_drt.csv`, `.modestats.csv`, `.output_trips.csv.gz`; optional drt events cache + DVRP fleet file; `drt_service_time.reconstruct` (from `analysis/drt-headline/`, added to `sys.path`).
- Produces: `extract(run_dir, prefix, fleet_file=None, drt_events_cache=None) -> list[dict]` of row dicts.
- Column names verified against the real married120 outputs on 2026-07-06 (headers below are verbatim).

- [ ] **Step 1: Create fixtures — verbatim copies of the real married120 rows**

`tests/fixtures/drtrun/` with prefix `DRT_TEST`; write these EXACT contents:

`DRT_TEST.drt_customer_stats_drt.csv`:
```
runId;iteration;rides;rides_pax;groupSize_mean;wait_average;wait_max;wait_p95;wait_p75;wait_median;percentage_WT_below_10;percentage_WT_below_15;inVehicleTravelTime_mean;distance_m_mean;directDistance_m_mean;totalTravelTime_mean;fareAllReferences_mean;rejections;rejectionRate
DRT_TEST;150;9171;9171;1;698.58;1322;1173;1036;753;38.94;62.31;1218.85;10967.72;7595.93;1917.43;-0;24;0
```

`DRT_TEST.drt_vehicle_stats_drt.csv`:
```
runId;iteration;vehicles;totalServiceDuration;totalDistance;totalEmptyDistance;emptyRatio;totalPassengerDistanceTraveled;averageDrivenDistance;averageEmptyDistance;averagePassengerDistanceTraveled;d_p/d_t;l_det;minShareIdleVehicles;minCountIdleVehicles
DRT_TEST;150;120;10368000;48885489.8;5723917.05;0.12;100584927.64;407379.08;47699.31;838207.73;2.06;0.7;0.07;8.16
```

`DRT_TEST.drt_sharing_metrics_drt.csv`:
```
runId;iteration;poolingRate;sharingFactor;nPooled;nTotal
DRT_TEST;150;0.9809181114382292;2.581682033654114;8996.0;9171.0
```

`DRT_TEST.modestats.csv`:
```
iteration;bike;car;drt;pt;ride;walk
150;0.06619917207769732;0.4506430934943028;0.06071660967201539;0.005614088524000056;0.22704176992620692;0.18978526630577747
```

`DRT_TEST.output_trips.csv.gz` — build in a tiny helper script or the test setup: gzip a CSV with header `trip_id;main_mode;modes` and 6 rows: 3 with `modes="walk-drt-walk"` main_mode `drt`, 1 with `modes="walk-drt-walk-pt-walk"` main_mode `pt`, 2 with `modes="car"` main_mode `car`. (Real trips files have many more columns; the extractor must select columns by NAME, so the slim fixture is valid.)

- [ ] **Step 2: Write the failing test**

```python
# -*- coding: utf-8 -*-
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from extract_drt import extract

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def _by_name(rows):
    return {r["kpi_name"]: r for r in rows}


def test_extract_headline_kpis():
    rows = extract(FIX, "DRT_TEST")
    k = _by_name(rows)
    assert k["drt_rides"]["value"] == 9171
    assert k["drt_rejections"]["value"] == 24
    # integer-based rate, NOT the rounded rejectionRate column
    assert k["drt_rejection_rate"]["value"] == pytest.approx(24 / (9171 + 24))
    assert k["wait_median"]["value"] == 753.0
    assert k["wait_below_15min"]["value"] == pytest.approx(0.6231)
    assert k["detour_factor"]["value"] == pytest.approx(10967.72 / 7595.93)
    assert k["drt_vehicles"]["value"] == 120
    assert k["drt_vehicle_km"]["value"] == pytest.approx(48885.4898)
    assert k["drt_empty_ratio"]["value"] == pytest.approx(0.12)
    assert k["pooling_rate"]["value"] == pytest.approx(0.98092, abs=1e-4)
    assert k["modal_share_drt"]["value"] == pytest.approx(0.0607166, abs=1e-6)
    # feeder: 1 of 4 drt trips contains pt; 1 pt main-mode trip is drt-fed
    assert k["drt_feeder_trips"]["value"] == 1
    assert k["drt_feeder_share"]["value"] == pytest.approx(0.25)
    assert k["rail_trips_drt_fed_share"]["value"] == pytest.approx(1.0)
    # every row well-formed
    for r in rows:
        assert set(r) == {"kpi_group", "kpi_name", "value", "unit", "source"}


def test_no_events_no_service_rows():
    rows = extract(FIX, "DRT_TEST")
    assert "service_ratio_shift" not in _by_name(rows)
```

Note: the fixture has 4 drt trips? No — 3 pure drt + 1 drt+pt = **4** trips whose `modes` contain `drt`; adjust the fixture in Step 1 accordingly (3 × `walk-drt-walk`, 1 × `walk-drt-walk-pt-walk`, 2 × `car`) so `drt_feeder_share == 0.25`.

- [ ] **Step 3: Run to verify failure**

Run: `python -u -m pytest tests/test_extract_drt.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'extract_drt'`.

- [ ] **Step 4: Implement**

```python
# -*- coding: utf-8 -*-
"""Passenger/system/channel KPI rows from MATSim's DRT analysis CSVs
(authoritative; event reconstruction is ~3% low) plus optional event-based
service-time KPIs via drt_service_time.reconstruct."""
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "drt-headline"))
import drt_service_time  # noqa: E402

from common import row  # noqa: E402


def extract(run_dir, prefix, fleet_file=None, drt_events_cache=None):
    run_dir = Path(run_dir)

    def p(suffix):
        return run_dir / (prefix + suffix)

    rows = []

    cs = pd.read_csv(p(".drt_customer_stats_drt.csv"), sep=";").iloc[-1]
    rides, rejections = int(cs["rides"]), int(cs["rejections"])
    rows += [
        row("passenger", "drt_rides", rides, "trips", "drt_customer_stats"),
        row("passenger", "drt_rejections", rejections, "requests", "drt_customer_stats"),
        row("passenger", "drt_rejection_rate",
            rejections / max(1, rides + rejections), "share", "computed(int cols)"),
        row("passenger", "wait_mean", float(cs["wait_average"]), "s", "drt_customer_stats"),
        row("passenger", "wait_median", float(cs["wait_median"]), "s", "drt_customer_stats"),
        row("passenger", "wait_p95", float(cs["wait_p95"]), "s", "drt_customer_stats"),
        row("passenger", "wait_below_10min",
            float(cs["percentage_WT_below_10"]) / 100.0, "share", "drt_customer_stats"),
        row("passenger", "wait_below_15min",
            float(cs["percentage_WT_below_15"]) / 100.0, "share", "drt_customer_stats"),
        row("passenger", "in_vehicle_time_mean",
            float(cs["inVehicleTravelTime_mean"]), "s", "drt_customer_stats"),
        row("passenger", "detour_factor",
            float(cs["distance_m_mean"]) / float(cs["directDistance_m_mean"]),
            "ratio", "computed"),
    ]

    vs = pd.read_csv(p(".drt_vehicle_stats_drt.csv"), sep=";").iloc[-1]
    rows += [
        row("system", "drt_vehicles", int(vs["vehicles"]), "vehicles", "drt_vehicle_stats"),
        row("system", "drt_vehicle_km", float(vs["totalDistance"]) / 1000.0, "km", "drt_vehicle_stats"),
        row("system", "drt_empty_ratio", float(vs["emptyRatio"]), "share", "drt_vehicle_stats"),
        row("passenger", "drt_passenger_km",
            float(vs["totalPassengerDistanceTraveled"]) / 1000.0, "km", "drt_vehicle_stats"),
        row("system", "drt_dp_over_dt", float(vs["d_p/d_t"]), "ratio", "drt_vehicle_stats"),
    ]

    sh = pd.read_csv(p(".drt_sharing_metrics_drt.csv"), sep=";").iloc[-1]
    rows += [
        row("passenger", "pooling_rate", float(sh["poolingRate"]), "share", "drt_sharing_metrics"),
        row("passenger", "sharing_factor", float(sh["sharingFactor"]), "ratio", "drt_sharing_metrics"),
    ]

    ms = pd.read_csv(p(".modestats.csv"), sep=";").iloc[-1]
    for mode in [c for c in ms.index if c != "iteration"]:
        rows.append(row("system", "modal_share_" + mode, float(ms[mode]), "share", "modestats"))

    trips = pd.read_csv(p(".output_trips.csv.gz"), sep=";")
    drt_trips = trips[trips["modes"].str.contains("drt", na=False)]
    feeder = drt_trips["modes"].str.contains("pt", na=False)
    pt_trips = trips[trips["main_mode"] == "pt"]
    rows += [
        row("channel", "drt_feeder_trips", int(feeder.sum()), "trips", "output_trips"),
        row("channel", "drt_feeder_share",
            float(feeder.mean()) if len(drt_trips) else 0.0, "share", "computed"),
        row("channel", "rail_trips_drt_fed_share",
            (int(feeder.sum()) / len(pt_trips)) if len(pt_trips) else 0.0, "share", "computed"),
    ]

    if drt_events_cache is not None:
        r = drt_service_time.reconstruct(
            str(drt_events_cache), str(fleet_file) if fleet_file else None)
        fl = r["fleet"]
        seg_t = fl["seg_time"]
        tot_t = sum(seg_t.values())
        rows += [
            row("system", "service_ratio_active", fl["ratio_active"], "share", "events"),
            row("system", "service_ratio_shift", fl["ratio_shift"], "share", "events"),
            row("system", "fleet_utilisation_by_time", fl["util_by_time"], "share", "events"),
            row("system", "fleet_shift_hours", fl["sum_shift_s"] / 3600.0, "h", "events/fleet file"),
            row("passenger", "mean_pax_aboard",
                (sum(lv * s for lv, s in seg_t.items()) / tot_t) if tot_t else 0.0,
                "pax", "events"),
        ]
    return rows
```

- [ ] **Step 5: Run tests**

Run: `python -u -m pytest tests/test_extract_drt.py -q`
Expected: 2 passed.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/extract_drt.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_drt.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/drtrun/
git commit -m "feat(kpi): DRT passenger/system/channel extractor (1e Task 4)"
```

---

### Task 5: `extract_freight.py` — freight rows

**Files:**
- Create: `analysis/kpi/extract_freight.py`
- Test: `analysis/kpi/tests/test_extract_freight.py` + fixtures under `tests/fixtures/drtrun/analysis/freight/` and a small `DRT_TEST.output_carriers.xml.gz`

**Interfaces:**
- Consumes: `<run>/analysis/freight/TimeDistance_perCarrier.tsv`, `Load_perVehicle.tsv` (MATSim CarriersAnalysis, tab-separated); `<prefix>.output_carriers.xml.gz` carrier attributes `numberOfParcels`, `missedParcels`, `unassignedParcels`, `unassignedJobs` (attribute names verbatim from `LmdCarrierBuilder.java:123-126` and `DashboardGenerator.java:360-361`).
- Produces: `extract(run_dir, prefix) -> list[dict]`.
- NOTE: the Java dashboard scales `missedParcels` by a non-exclusive-tour ratio (`DashboardGenerator.java:355`); v1 here uses raw sums — values can differ slightly from the legacy LMD dashboard. Documented, acceptable.

- [ ] **Step 1: Create fixtures**

`tests/fixtures/drtrun/analysis/freight/TimeDistance_perCarrier.tsv` (verbatim real header, one real + one synthetic row):
```
carrierId	nuOfTours	tourDurations[s]	tourDurations[h]	travelDistances[m]	travelDistances[km]	travelTimes[s]	travelTimes[h]	fixedCosts[EUR]	varCostsTime[EUR]	varCostsDist[EUR]	totalCosts[EUR]
dhl	25	573730.0	159.36944444444444	3047687.000000002	3047.6870000000017	296685.0	82.4125	4103.4299999999985	0.0	1113.0607007804679	5216.490700780467
ups	10	200000.0	55.55555555555556	1000000.0	1000.0	100000.0	27.77777777777778	1000.0	0.0	400.0	1400.0
```

`tests/fixtures/drtrun/analysis/freight/Load_perVehicle.tsv`:
```
vehicleId	vehicleTypeId	capacity	maxLoad	maxLoadPercentage	handledDemand	load state during tour
freight_dhl_veh_1	ct_cep_size_m	165	120	72.7	120	[...]
freight_dhl_veh_2	ct_cep_size_s	100	90	90.0	90	[...]
freight_ups_veh_1	ct_cep_size_l	230	200	86.9	200	[...]
```

`DRT_TEST.output_carriers.xml.gz` — gzip this XML (test setup or checked-in binary):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<carriers>
  <carrier id="dhl_1">
    <attributes>
      <attribute name="numberOfParcels" class="java.lang.Integer">300</attribute>
      <attribute name="missedParcels" class="java.lang.Integer">10</attribute>
      <attribute name="unassignedParcels" class="java.lang.Integer">5</attribute>
    </attributes>
  </carrier>
  <carrier id="ups_1">
    <attributes>
      <attribute name="numberOfParcels" class="java.lang.Integer">200</attribute>
      <attribute name="missedParcels" class="java.lang.Integer">0</attribute>
    </attributes>
  </carrier>
</carriers>
```

- [ ] **Step 2: Write the failing test**

```python
# -*- coding: utf-8 -*-
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from extract_freight import extract

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def test_extract_freight_kpis():
    k = {r["kpi_name"]: r for r in extract(FIX, "DRT_TEST")}
    assert k["carriers"]["value"] == 2
    assert k["freight_tours"]["value"] == 35
    assert k["freight_vehicle_km"]["value"] == pytest.approx(4047.687, abs=1e-3)
    assert k["freight_total_costs"]["value"] == pytest.approx(6616.4907, abs=1e-3)
    assert k["freight_vehicles"]["value"] == 3
    assert k["parcels_handled"]["value"] == 410
    assert k["avg_max_load"]["value"] == pytest.approx((72.7 + 90.0 + 86.9) / 300.0)
    assert k["parcels_total"]["value"] == 500
    assert k["parcels_missed"]["value"] == 10
    assert k["parcels_unassigned"]["value"] == 5
    assert k["delivery_rate"]["value"] == pytest.approx(485 / 500)
    assert k["parcels_per_vehicle_km"]["value"] == pytest.approx(485 / 4047.687, abs=1e-6)
```

- [ ] **Step 3: Run to verify failure**

Run: `python -u -m pytest tests/test_extract_freight.py -q`
Expected: FAIL (module missing).

- [ ] **Step 4: Implement**

```python
# -*- coding: utf-8 -*-
"""Freight KPI rows from MATSim CarriersAnalysis TSVs + carrier attributes.
Carrier attribute names are verbatim from LmdCarrierBuilder (numberOfParcels,
missedParcels) and the unassigned-jobs tracking (unassignedParcels/-Jobs)."""
import gzip
import xml.etree.ElementTree as ET
from pathlib import Path

import pandas as pd

from common import row


def _carrier_attrs(carriers_xml_gz):
    out = {}
    with gzip.open(carriers_xml_gz, "rb") as f:
        for _, el in ET.iterparse(f):
            if el.tag.endswith("carrier"):
                attrs = {a.get("name"): (a.text or "").strip()
                         for a in el.iter() if a.tag.endswith("attribute")}
                out[el.get("id")] = attrs
                el.clear()
    return out


def _int_attr(attrs, name):
    v = attrs.get(name)
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return 0


def extract(run_dir, prefix):
    run_dir = Path(run_dir)
    rows = []

    fr = run_dir / "analysis" / "freight"
    td = pd.read_csv(fr / "TimeDistance_perCarrier.tsv", sep="\t")
    km = float(td["travelDistances[km]"].sum())
    rows += [
        row("freight", "carriers", len(td), "carriers", "TimeDistance_perCarrier"),
        row("freight", "freight_tours", int(td["nuOfTours"].sum()), "tours", "TimeDistance_perCarrier"),
        row("freight", "freight_vehicle_km", km, "km", "TimeDistance_perCarrier"),
        row("freight", "freight_tour_hours",
            float(td["tourDurations[h]"].sum()), "h", "TimeDistance_perCarrier"),
        row("economic", "freight_fixed_costs",
            float(td["fixedCosts[EUR]"].sum()), "EUR", "TimeDistance_perCarrier"),
        row("economic", "freight_var_costs_dist",
            float(td["varCostsDist[EUR]"].sum()), "EUR", "TimeDistance_perCarrier"),
        row("economic", "freight_var_costs_time",
            float(td["varCostsTime[EUR]"].sum()), "EUR", "TimeDistance_perCarrier"),
        row("economic", "freight_total_costs",
            float(td["totalCosts[EUR]"].sum()), "EUR", "TimeDistance_perCarrier"),
    ]

    lv = pd.read_csv(fr / "Load_perVehicle.tsv", sep="\t")
    rows += [
        row("freight", "freight_vehicles", len(lv), "vehicles", "Load_perVehicle"),
        row("freight", "avg_max_load",
            float(lv["maxLoadPercentage"].mean()) / 100.0, "share", "Load_perVehicle"),
        row("freight", "parcels_handled", int(lv["handledDemand"].sum()), "parcels", "Load_perVehicle"),
    ]

    attrs = _carrier_attrs(run_dir / (prefix + ".output_carriers.xml.gz"))
    total = sum(_int_attr(a, "numberOfParcels") for a in attrs.values())
    missed = sum(_int_attr(a, "missedParcels") for a in attrs.values())
    unassigned = sum(_int_attr(a, "unassignedParcels") for a in attrs.values())
    delivered = total - missed - unassigned
    rows += [
        row("freight", "parcels_total", total, "parcels", "carrier attributes"),
        row("freight", "parcels_missed", missed, "parcels", "carrier attributes"),
        row("freight", "parcels_unassigned", unassigned, "parcels", "carrier attributes"),
        row("freight", "delivery_rate",
            (delivered / total) if total else 1.0, "share", "computed"),
        row("freight", "parcels_per_vehicle_km",
            (delivered / km) if km else 0.0, "parcels/km", "computed"),
    ]
    return rows
```

- [ ] **Step 5: Run tests**

Run: `python -u -m pytest tests/test_extract_freight.py -q`
Expected: 1 passed.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/extract_freight.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_freight.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/drtrun/analysis/
git commit -m "feat(kpi): freight extractor from CarriersAnalysis + carrier attrs (1e Task 5)"
```

(If the carriers gz fixture is generated in test setup instead of checked in, adjust the `git add` list.)

---

### Task 6: `economics.py` + `kpi_writer.py`

**Files:**
- Create: `analysis/kpi/economics.py`, `analysis/kpi/kpi_writer.py`
- Test: `analysis/kpi/tests/test_economics_writer.py`

**Interfaces:**
- Consumes: the row lists from Tasks 4/5 (`fleet_shift_hours` row if events ran; `freight_total_costs`, `parcels_handled` etc.).
- Produces: `economics.extract(all_rows, fleet_size=None) -> list[dict]`; `kpi_writer.write_long(rows, meta, out_file)`, `kpi_writer.write_wide(rows, meta, out_file)`; module constant `kpi_writer.COLUMNS`.

- [ ] **Step 1: Write the failing test**

```python
# -*- coding: utf-8 -*-
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from common import row
from economics import extract as econ
from kpi_writer import write_long, write_wide, COLUMNS
from run_meta import parse_legacy_dir_name


def _base_rows():
    return [
        row("system", "fleet_shift_hours", 2880.0, "h", "events/fleet file"),
        row("passenger", "drt_rides", 9171, "trips", "drt_customer_stats"),
        row("economic", "freight_total_costs", 13605.0, "EUR", "TimeDistance_perCarrier"),
        row("freight", "parcels_handled", 6381, "parcels", "Load_perVehicle"),
    ]


def test_placeholder_cost_model():
    k = {r["kpi_name"]: r for r in econ(_base_rows())}
    # 2880 shift hours x (20+5) EUR/h
    assert k["drt_cost_bottom_up_placeholder"]["value"] == pytest.approx(2880 * 25.0)
    assert k["drt_cost_per_ride_placeholder"]["value"] == pytest.approx(2880 * 25.0 / 9171)
    assert k["drt_labour_share_placeholder"]["value"] == pytest.approx(20.0 / 25.0)
    assert k["freight_cost_per_parcel"]["value"] == pytest.approx(13605.0 / 6381)
    assert all(r["kpi_group"] == "economic" for r in econ(_base_rows()))


def test_fleet_size_fallback_when_no_events():
    rows = [r for r in _base_rows() if r["kpi_name"] != "fleet_shift_hours"]
    k = {r["kpi_name"]: r for r in econ(rows, fleet_size=120)}
    # fallback: 120 vehicles x 24 h shift
    assert k["drt_cost_bottom_up_placeholder"]["value"] == pytest.approx(120 * 24 * 25.0)


def test_write_long_and_wide(tmp_path):
    meta = parse_legacy_dir_name("DRT_BASELINE_13052025_married120_iter150_jsprit100")
    rows = _base_rows()
    long_f, wide_f = tmp_path / "kpis_long.csv", tmp_path / "kpis_wide.csv"
    write_long(rows, meta, long_f)
    write_wide(rows, meta, wide_f)

    lines = long_f.read_text(encoding="utf-8").splitlines()
    assert lines[0] == ";".join(COLUMNS)
    assert lines[1].startswith(
        "DRT_BASELINE_13052025_married120;lausitz_hoyerswerda;DRT_BASELINE;conventional;")
    assert any(";drt_rides;9171;trips;" in l for l in lines)

    wlines = wide_f.read_text(encoding="utf-8").splitlines()
    assert wlines[0].startswith("run_id;study_area;scenario;operation_mode;")
    assert "passenger.drt_rides" in wlines[0]
    assert len(wlines) == 2
```

- [ ] **Step 2: Run to verify failure**

Run: `python -u -m pytest tests/test_economics_writer.py -q`
Expected: FAIL (modules missing).

- [ ] **Step 3: Implement**

`analysis/kpi/economics.py`:

```python
# -*- coding: utf-8 -*-
"""PLACEHOLDER economic KPIs — bottom-up 25 EUR/veh-shift-h split into
labour 20 / vehicle 5 (Rudolph LMD breakdown, ~80/20). Must be refined before
the headline evaluation; every placeholder KPI carries _placeholder in its name."""
from common import row

LABOUR_EUR_PER_H = 20.0
VEHICLE_EUR_PER_H = 5.0


def _get(rows, name):
    for r in rows:
        if r["kpi_name"] == name:
            return r["value"]
    return None


def extract(all_rows, fleet_size=None):
    rows = []
    shift_h = _get(all_rows, "fleet_shift_hours")
    if shift_h is None and fleet_size:
        shift_h = fleet_size * 24.0  # DVRP shift 0..86400 per vehicle
    if shift_h:
        labour = shift_h * LABOUR_EUR_PER_H
        total = shift_h * (LABOUR_EUR_PER_H + VEHICLE_EUR_PER_H)
        rows.append(row("economic", "drt_cost_bottom_up_placeholder",
                        total, "EUR", "placeholder 25 EUR/veh-shift-h"))
        rides = _get(all_rows, "drt_rides")
        if rides:
            rows.append(row("economic", "drt_cost_per_ride_placeholder",
                            total / rides, "EUR/trip", "computed"))
        rows.append(row("economic", "drt_labour_share_placeholder",
                        labour / total, "share", "placeholder Rudolph 80/20"))
    fc = _get(all_rows, "freight_total_costs")
    parcels = _get(all_rows, "parcels_handled")
    if fc is not None and parcels:
        rows.append(row("economic", "freight_cost_per_parcel",
                        fc / parcels, "EUR/parcel", "computed"))
    return rows
```

`analysis/kpi/kpi_writer.py`:

```python
# -*- coding: utf-8 -*-
"""Canonical KPI CSVs. Long format is the spec schema + source; wide format is
one row per run with kpi_group.kpi_name columns. Conventions: ';', dot
decimals, UTF-8 (matches RoutingStatistics + MATSim DRT CSVs)."""
import csv

COLUMNS = ["run_id", "study_area", "scenario", "operation_mode",
           "kpi_group", "kpi_name", "value", "unit", "source"]


def _fmt(v):
    if isinstance(v, float):
        return "{:.6g}".format(v)
    return str(v)


def write_long(rows, meta, out_file):
    with open(out_file, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(COLUMNS)
        for r in rows:
            w.writerow([meta.run_id, meta.study_area, meta.scenario,
                        meta.operation_mode, r["kpi_group"], r["kpi_name"],
                        _fmt(r["value"]), r["unit"], r["source"]])


def write_wide(rows, meta, out_file):
    names = [r["kpi_group"] + "." + r["kpi_name"] for r in rows]
    with open(out_file, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["run_id", "study_area", "scenario", "operation_mode"] + names)
        w.writerow([meta.run_id, meta.study_area, meta.scenario, meta.operation_mode]
                   + [_fmt(r["value"]) for r in rows])
```

- [ ] **Step 4: Run tests**

Run: `python -u -m pytest tests/test_economics_writer.py -q`
Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/economics.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/kpi_writer.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_economics_writer.py
git commit -m "feat(kpi): placeholder economics + canonical long/wide CSV writers (1e Task 6)"
```

---

### Task 7: `timeseries.py` — hourly series CSV

**Files:**
- Create: `analysis/kpi/timeseries.py`
- Test: `analysis/kpi/tests/test_timeseries.py` (+ small fixture CSVs in `tests/fixtures/drtrun/`)

**Interfaces:**
- Consumes: `<prefix>.output_drt_legs_drt.csv` (`;`-separated, columns `departureTime`, `waitTime` — header verified), `<prefix>.output_drt_rejections_drt.csv` (columns `time`, `cause` — header verified), optional freight cache from Task 3 (lines with `time="..."`).
- Produces: `extract(run_dir, prefix, freight_cache=None) -> list[dict]` with keys `series, hour, value, unit`; `write(series, meta, out_file)` → `kpi_timeseries.csv` with header `run_id;series;hour;value;unit`. Series names: `drt_rides`, `drt_wait_mean`, `drt_rejections`, `freight_service_stops`.
- Deferred (documented, not built): parcels-per-hour-by-provider needs the event↔carrier-plan join the Java dashboard does; scenario-level `freight_service_stops` is the v1 comparison series.

- [ ] **Step 1: Create fixtures**

`tests/fixtures/drtrun/DRT_TEST.output_drt_legs_drt.csv` (verbatim real header + 4 rows):
```
submissionTime;departureTime;personId;requestId;vehicleId;fromLinkId;fromX;fromY;toLinkId;toX;toY;waitTime;arrivalTime;inVehicleTravelTime;travelDistance_m;directTravelDistance_m;fareForLeg;earliestDepartureTime;latestDepartureTime;latestArrivalTime
25000;25200;p1;drt_1;drt_veh_1;l1;0;0;l2;1;1;300;26000;800;5000;4000;2.6;25000;26100;27000
25500;25900;p2;drt_2;drt_veh_1;l1;0;0;l2;1;1;400;26800;900;5200;4100;2.6;25500;26600;27500
29000;29100;p3;drt_3;drt_veh_2;l3;0;0;l4;1;1;100;29900;800;4900;4000;2.6;29000;30100;31000
36100;36400;p4;drt_4;drt_veh_2;l3;0;0;l4;1;1;300;37200;800;4900;4000;2.6;36100;37300;38200
```

`tests/fixtures/drtrun/DRT_TEST.output_drt_rejections_drt.csv`:
```
time;personIds;requestId;fromLinkId;toLinkId;fromX;fromY;toX;toY;cause
35928.0;617791;drt_2427;40636709;-168559764#0;866504.9;5711414.1899999995;860236.65;5710191.35;no_insertion_found
36100.0;617792;drt_2428;40636709;-168559764#0;866504.9;5711414.19;860236.65;5710191.35;no_insertion_found
```

- [ ] **Step 2: Write the failing test**

```python
# -*- coding: utf-8 -*-
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from timeseries import extract, write
from run_meta import parse_legacy_dir_name

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def _series(rows, name):
    return {r["hour"]: r["value"] for r in rows if r["series"] == name}


def test_hourly_series():
    rows = extract(FIX, "DRT_TEST")
    rides = _series(rows, "drt_rides")
    # departures 25200/25900 -> hour 7, 29100 -> hour 8, 36400 -> hour 10
    assert rides == {7: 2, 8: 1, 10: 1}
    waits = _series(rows, "drt_wait_mean")
    assert waits[7] == pytest.approx(350.0)
    rej = _series(rows, "drt_rejections")
    assert rej == {9: 1, 10: 1}   # 35928->9, 36100->10


def test_freight_cache_series(tmp_path):
    cache = tmp_path / "f.txt"
    cache.write_text(
        '<event time="30000.0" type="actstart" person="freight_dhl_veh_1" actType="service"/>\n'
        '<event time="30100.0" type="actstart" person="freight_dhl_veh_1" actType="service"/>\n'
        '<event time="40000.0" type="actstart" person="freight_ups_veh_1" actType="service"/>\n',
        encoding="utf-8")
    rows = extract(FIX, "DRT_TEST", freight_cache=cache)
    stops = _series(rows, "freight_service_stops")
    assert stops == {8: 2, 11: 1}


def test_write(tmp_path):
    meta = parse_legacy_dir_name("DRT_BASELINE_13052025_married120_iter150_jsprit100")
    out = tmp_path / "kpi_timeseries.csv"
    write(extract(FIX, "DRT_TEST"), meta, out)
    lines = out.read_text(encoding="utf-8").splitlines()
    assert lines[0] == "run_id;series;hour;value;unit"
    assert lines[1].startswith("DRT_BASELINE_13052025_married120;")
```

- [ ] **Step 3: Run to verify failure**

Run: `python -u -m pytest tests/test_timeseries.py -q`
Expected: FAIL (module missing).

- [ ] **Step 4: Implement**

```python
# -*- coding: utf-8 -*-
"""Tidy hourly time series per run -> kpi_timeseries.csv
(run_id;series;hour;value;unit). Sources: drt legs CSV (rides, mean wait),
drt rejections CSV, freight service-start cache (stops)."""
import csv
import re
from pathlib import Path

import pandas as pd

RE_TIME = re.compile(r'time="([^"]+)"')


def _ts(series, hour, value, unit):
    return {"series": series, "hour": int(hour), "value": value, "unit": unit}


def extract(run_dir, prefix, freight_cache=None):
    run_dir = Path(run_dir)
    rows = []

    legs_f = run_dir / (prefix + ".output_drt_legs_drt.csv")
    if legs_f.exists():
        legs = pd.read_csv(legs_f, sep=";")
        legs["hour"] = (legs["departureTime"] // 3600).astype(int)
        g = legs.groupby("hour")
        for h, n in g.size().items():
            rows.append(_ts("drt_rides", h, int(n), "trips/h"))
        for h, wm in g["waitTime"].mean().items():
            rows.append(_ts("drt_wait_mean", h, float(wm), "s"))

    rej_f = run_dir / (prefix + ".output_drt_rejections_drt.csv")
    if rej_f.exists():
        rej = pd.read_csv(rej_f, sep=";")
        if len(rej):
            for h, n in (rej["time"] // 3600).astype(int).value_counts().sort_index().items():
                rows.append(_ts("drt_rejections", h, int(n), "requests/h"))

    if freight_cache is not None and Path(freight_cache).exists():
        counts = {}
        with open(freight_cache, "r", encoding="utf-8") as f:
            for line in f:
                m = RE_TIME.search(line)
                if m:
                    h = int(float(m.group(1)) // 3600)
                    counts[h] = counts.get(h, 0) + 1
        for h in sorted(counts):
            rows.append(_ts("freight_service_stops", h, counts[h], "stops/h"))
    return rows


def write(series_rows, meta, out_file):
    with open(out_file, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["run_id", "series", "hour", "value", "unit"])
        for r in series_rows:
            v = r["value"]
            w.writerow([meta.run_id, r["series"], r["hour"],
                        "{:.6g}".format(v) if isinstance(v, float) else v, r["unit"]])
```

- [ ] **Step 5: Run tests**

Run: `python -u -m pytest tests/test_timeseries.py -q`
Expected: 3 passed.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/timeseries.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_timeseries.py \
        "parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/drtrun/DRT_TEST.output_drt_legs_drt.csv" \
        "parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/drtrun/DRT_TEST.output_drt_rejections_drt.csv"
git commit -m "feat(kpi): tidy hourly timeseries CSV (1e Task 7)"
```

---

### Task 8: `build_kpis.py` CLI + real-run acceptance

**Files:**
- Create: `analysis/kpi/build_kpis.py`
- Test: `analysis/kpi/tests/test_build_kpis.py` (fixture-run end-to-end)

**Interfaces:**
- Consumes: everything from Tasks 2–7.
- Produces: CLI `python -u build_kpis.py --run-dir <dir> [--no-events] [--fleet-file <path>] [--out-dir <dir>]` writing `kpis_long.csv`, `kpis_wide.csv`, `kpi_timeseries.csv` into `<run>/analysis/` (default). Function `build(run_dir, no_events=False, fleet_file=None, out_dir=None) -> Path` (returns out dir) so Task 9/10 and tests can call it in-process. `EXTRACTORS` dispatch is where 1c/1d register their extractor modules.

- [ ] **Step 1: Write the failing test**

```python
# -*- coding: utf-8 -*-
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build_kpis import build

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def test_build_writes_all_csvs(tmp_path):
    out = build(FIX, no_events=True, out_dir=tmp_path)
    assert (out / "kpis_long.csv").exists()
    assert (out / "kpis_wide.csv").exists()
    assert (out / "kpi_timeseries.csv").exists()
    long_txt = (out / "kpis_long.csv").read_text(encoding="utf-8")
    # drt + freight + economics all present in one canonical file
    assert ";passenger;drt_rides;9171;" in long_txt
    assert ";freight;parcels_total;500;" in long_txt
    assert ";economic;freight_cost_per_parcel;" in long_txt
```

NOTE: the fixture dir name `drtrun` is not a parseable run-dir name — give the fixture dir a `run_metadata.json` (copy the one from Task 2's test, `run_id`/`prefix` = `DRT_TEST`) OR rename the fixture dir to `DRT_TEST_13052025_iter1_jsprit1`… simplest: add `run_metadata.json` with `"run_id": "DRT_TEST"` etc. to `tests/fixtures/drtrun/` in this step (then Task 4/5/7 tests stay untouched).

- [ ] **Step 2: Run to verify failure**

Run: `python -u -m pytest tests/test_build_kpis.py -q`
Expected: FAIL (module missing).

- [ ] **Step 3: Implement**

```python
# -*- coding: utf-8 -*-
"""Build the canonical KPI CSVs (+ dashboard, Task 9) for ONE run directory.

Usage (from analysis/kpi/):
    python -u build_kpis.py --run-dir ../../hagrid-matsim-output/DRT_BASELINE_13052025_married120_iter150_jsprit100
"""
import argparse
from pathlib import Path

import economics
import extract_drt
import extract_freight
import kpi_writer
import timeseries
from events_cache import ensure_caches
from run_meta import load_run_meta

# 1c/1d register their scenario-specific extractors here:
# each entry: (predicate(run_dir, meta) -> bool, extract(run_dir, prefix) -> rows)
EXTRACTORS = []


def _default_fleet_file(run_dir, meta):
    # <module-root>/hagrid-output/<run_id>/<run_id>_drt_fleet.xml.gz
    cand = run_dir.parent.parent / "hagrid-output" / meta.run_id / (meta.run_id + "_drt_fleet.xml.gz")
    return cand if cand.exists() else None


def build(run_dir, no_events=False, fleet_file=None, out_dir=None):
    run_dir = Path(run_dir)
    meta = load_run_meta(run_dir)
    out = Path(out_dir) if out_dir else run_dir / "analysis"
    out.mkdir(parents=True, exist_ok=True)

    is_drt = (run_dir / (meta.prefix + ".drt_customer_stats_drt.csv")).exists()
    has_freight = (run_dir / "analysis" / "freight" / "TimeDistance_perCarrier.tsv").exists()

    drt_cache = frt_cache = None
    if not no_events and (run_dir / (meta.prefix + ".output_events.xml.gz")).exists():
        drt_cache, frt_cache = ensure_caches(run_dir, meta.prefix)

    fleet = Path(fleet_file) if fleet_file else _default_fleet_file(run_dir, meta)

    rows = []
    if is_drt:
        rows += extract_drt.extract(run_dir, meta.prefix, fleet_file=fleet,
                                    drt_events_cache=drt_cache if is_drt else None)
    if has_freight:
        rows += extract_freight.extract(run_dir, meta.prefix)
    for predicate, extract_fn in EXTRACTORS:
        if predicate(run_dir, meta):
            rows += extract_fn(run_dir, meta.prefix)
    rows += economics.extract(rows, fleet_size=meta.fleet_size)

    kpi_writer.write_long(rows, meta, out / "kpis_long.csv")
    kpi_writer.write_wide(rows, meta, out / "kpis_wide.csv")
    ts = timeseries.extract(run_dir, meta.prefix, freight_cache=frt_cache)
    timeseries.write(ts, meta, out / "kpi_timeseries.csv")

    print("KPI CSVs written to " + str(out) + " (" + str(len(rows)) + " KPIs, "
          + str(len(ts)) + " timeseries points)")
    return out


def main():
    ap = argparse.ArgumentParser(description="Canonical KPI CSVs for one HAGRID run")
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--no-events", action="store_true",
                    help="skip event-based KPIs (service time, freight stops/h)")
    ap.add_argument("--fleet-file", default=None)
    ap.add_argument("--out-dir", default=None)
    a = ap.parse_args()
    build(a.run_dir, no_events=a.no_events, fleet_file=a.fleet_file, out_dir=a.out_dir)


if __name__ == "__main__":
    main()
```

(Also add the `run_metadata.json` fixture file described in Step 1.)

- [ ] **Step 4: Run tests, then the whole suite**

Run: `python -u -m pytest tests/ -q`
Expected: all passed.

- [ ] **Step 5: ACCEPTANCE on real runs**

From `analysis/kpi/`:

```bash
python -u build_kpis.py --run-dir "../../hagrid-matsim-output/DRT_BASELINE_13052025_married120_iter150_jsprit100"
python -u build_kpis.py --run-dir "../../hagrid-matsim-output/LMD_BASELINE_13052025_localdepots_stagger_iter0_jsprit100" --no-events
```

Expected: both exit 0. Verify against known headline numbers (memory-confirmed):
- married120 `kpis_long.csv` contains `;passenger;drt_rides;9171;`, `;passenger;drt_rejections;24;`, `modal_share_drt;0.0607…`, freight rows (7 carriers, 67 vehicles), `service_ratio_shift` present (events ran; first execution builds the freight cache — a one-pass scan of the 93 MB events file, expect 1–3 min).
- LMD run: freight rows only, no passenger rows.

If a column name mismatches on the real file (e.g. trips CSV), fix the extractor and add the real header line to the fixture — never adjust the assertion to whatever the code produced.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/drtrun/run_metadata.json
git commit -m "feat(kpi): build_kpis CLI - canonical CSVs per run dir (1e Task 8)"
```

---

### Task 9: `render.py` + per-run dashboard

**Files:**
- Create: `analysis/kpi/render.py`, `analysis/kpi/vendor/chart.umd.min.js`
- Modify: `analysis/kpi/build_kpis.py` (render call at the end of `build`)
- Test: `analysis/kpi/tests/test_render.py`

**Interfaces:**
- Consumes: the three CSVs written by Task 8 (the dashboard reads ONLY those — this is the architectural guarantee that the canonical CSV suffices).
- Produces: `render.render_run_page(kpis: pd.DataFrame, ts: pd.DataFrame, title: str) -> str` (full HTML), `render.load_run_csvs(analysis_dir: Path) -> (kpis, ts)`; `<run>/analysis/kpi_dashboard.html`.
- Design rules (dataviz, binding): KPI row = stat tiles (not one-bar charts); part-to-whole = horizontal stacked bar (NO pie); magnitudes = sequential blue; distinct series = categorical slots in fixed order; no dual axes (two stacked panels instead); legend for ≥ 2 series; Chart.js hover tooltips stay on; all text in ink colors; light+dark via `prefers-color-scheme`.

- [ ] **Step 1: Vendor Chart.js**

```bash
mkdir -p vendor
curl -sSL -o vendor/chart.umd.min.js https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js
ls -la vendor/chart.umd.min.js   # expect ~200 KB
```

- [ ] **Step 2: Write the failing test**

```python
# -*- coding: utf-8 -*-
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build_kpis import build
from render import load_run_csvs, render_run_page

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def test_render_run_page(tmp_path):
    out = build(FIX, no_events=True, out_dir=tmp_path)
    kpis, ts = load_run_csvs(out)
    html = render_run_page(kpis, ts, title="DRT_TEST")
    assert "<canvas" in html
    assert "chart.umd.min.js" not in html          # inlined, not referenced
    assert "Chart(" in html or "new Chart" in html
    assert "9171" in html                          # rides tile
    assert "prefers-color-scheme" in html          # dark mode present
    assert len(html.encode("utf-8")) < 1_000_000   # performance budget


def test_build_writes_dashboard(tmp_path):
    out = build(FIX, no_events=True, out_dir=tmp_path)
    assert (out / "kpi_dashboard.html").exists()
```

- [ ] **Step 3: Run to verify failure**

Run: `python -u -m pytest tests/test_render.py -q`
Expected: FAIL (module missing).

- [ ] **Step 4: Implement `render.py`**

```python
# -*- coding: utf-8 -*-
"""Lean self-contained HTML dashboard rendered EXCLUSIVELY from the canonical
KPI CSVs (kpis_long.csv + kpi_timeseries.csv). No maps, no per-vehicle
geometry, no plotly — Chart.js 4 (vendored, ~205 KB) is the only script.

Palette = validated dataviz reference palette (categorical slots fixed order;
sequential blue; ink/grid tokens), light + dark via prefers-color-scheme."""
import json
from pathlib import Path

import pandas as pd

VENDOR = Path(__file__).parent / "vendor" / "chart.umd.min.js"

# categorical slots (fixed order, never cycled) — light / dark steps
CAT_LIGHT = ["#2a78d6", "#1baf7a", "#eda100", "#008300",
             "#4a3aa7", "#e34948", "#e87ba4", "#eb6834"]
CAT_DARK = ["#3987e5", "#199e70", "#c98500", "#008300",
            "#9085e9", "#e66767", "#d55181", "#d95926"]
SEQ_LIGHT, SEQ_DARK = "#2a78d6", "#3987e5"   # sequential blue (magnitude charts)

# fixed mode->slot assignment (color follows the entity)
MODE_SLOTS = {"car": 0, "ride": 1, "walk": 2, "bike": 3, "drt": 4, "pt": 5}
# fixed scenario->slot assignment for the comparison view (1c/1d extend here)
SCENARIO_SLOTS = {"DRT_BASELINE": 0, "DRT_SHAREDUSE": 1, "DRT_MODULAR": 2, "LMD_BASELINE": 3}

CSS = """
:root { color-scheme: light dark; }
body { margin:0; font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
       background: var(--page); color: var(--ink); }
.viz-root {
  --page:#f9f9f7; --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e;
  --muted:#898781; --grid:#e1e0d9; --axis:#c3c2b7;
  --border:rgba(11,11,11,0.10); --seq:#2a78d6;
}
@media (prefers-color-scheme: dark) { .viz-root {
  --page:#0d0d0d; --surface:#1a1a19; --ink:#ffffff; --ink2:#c3c2b7;
  --muted:#898781; --grid:#2c2c2a; --axis:#383835;
  --border:rgba(255,255,255,0.10); --seq:#3987e5;
}}
.wrap { max-width: 1240px; margin: 0 auto; padding: 24px; }
h1 { font-size: 20px; } h2 { font-size: 15px; color: var(--ink2); margin: 28px 0 10px; }
.tiles { display:grid; grid-template-columns: repeat(auto-fill,minmax(170px,1fr)); gap:10px; }
.tile { background:var(--surface); border:1px solid var(--border); border-radius:10px; padding:12px 14px; }
.tile .v { font-size:26px; font-weight:600; }
.tile .l { font-size:12px; color:var(--ink2); margin-top:2px; }
.tile .s { font-size:11px; color:var(--muted); margin-top:2px; }
.grid2 { display:grid; grid-template-columns:repeat(auto-fit,minmax(420px,1fr)); gap:14px; }
.panel { background:var(--surface); border:1px solid var(--border); border-radius:10px; padding:14px; }
.panel h3 { margin:0 0 8px; font-size:13px; color:var(--ink2); font-weight:600; }
table.kpis { border-collapse:collapse; width:100%; font-size:12.5px; }
table.kpis td, table.kpis th { padding:4px 8px; border-bottom:1px solid var(--grid);
  text-align:left; font-variant-numeric: tabular-nums; }
table.kpis th { color:var(--muted); font-weight:600; }
.tablewrap { overflow-x:auto; }
"""

JS_SETUP = """
const css = getComputedStyle(document.querySelector('.viz-root'));
const V = n => css.getPropertyValue(n).trim();
const DARK = matchMedia('(prefers-color-scheme: dark)').matches;
const CAT = DARK ? %s : %s;
Chart.defaults.font.family = 'system-ui, -apple-system, "Segoe UI", sans-serif';
Chart.defaults.color = V('--ink2');
Chart.defaults.borderColor = V('--grid');
Chart.defaults.plugins.legend.labels.boxWidth = 10;
function mk(id, cfg) { new Chart(document.getElementById(id), cfg); }
"""


def load_run_csvs(analysis_dir):
    analysis_dir = Path(analysis_dir)
    kpis = pd.read_csv(analysis_dir / "kpis_long.csv", sep=";")
    ts_f = analysis_dir / "kpi_timeseries.csv"
    ts = pd.read_csv(ts_f, sep=";") if ts_f.exists() else pd.DataFrame(
        columns=["run_id", "series", "hour", "value", "unit"])
    return kpis, ts


def _kpi(kpis, name, default=None):
    m = kpis[kpis["kpi_name"] == name]
    return float(m.iloc[0]["value"]) if len(m) else default


def _tile(value, label, sub=""):
    return ('<div class="tile"><div class="v">' + value + '</div><div class="l">'
            + label + '</div><div class="s">' + sub + '</div></div>')


def _fmt_pct(v, digits=1):
    return ("{:." + str(digits) + "f}").format(v * 100).replace(".", ",") + " %"


def _fmt_de(v, digits=0):
    s = ("{:,." + str(digits) + "f}").format(v)
    return s.replace(",", "X").replace(".", ",").replace("X", ".")


def _panel(title, canvas_id, height=210):
    return ('<div class="panel"><h3>' + title + '</h3>'
            '<div style="height:' + str(height) + 'px"><canvas id="' + canvas_id
            + '"></canvas></div></div>')


def _series(ts, name):
    m = ts[ts["series"] == name].sort_values("hour")
    return list(m["hour"].astype(int)), list(m["value"].astype(float))


def render_run_sections(kpis, ts, uid):
    """Tiles + charts + table for ONE run. uid makes canvas ids unique so the
    comparison page can embed several runs."""
    html, js = [], []

    tiles = []
    v = _kpi(kpis, "modal_share_drt")
    if v is not None:
        tiles.append(_tile(_fmt_pct(v), "DRT-Modal-Share", "modestats, letzte Iteration"))
    v = _kpi(kpis, "drt_rides")
    if v is not None:
        tiles.append(_tile(_fmt_de(v), "DRT-Fahrten", "bediente Requests"))
    v = _kpi(kpis, "wait_median")
    if v is not None:
        tiles.append(_tile(_fmt_de(v / 60.0, 1) + " min", "Wartezeit (Median)",
                           "P95: " + _fmt_de((_kpi(kpis, "wait_p95") or 0) / 60.0, 1) + " min"))
    v = _kpi(kpis, "drt_rejection_rate")
    if v is not None:
        tiles.append(_tile(_fmt_pct(v, 2), "Ablehnungsquote", "aus Integer-Spalten"))
    v = _kpi(kpis, "drt_vehicles")
    if v is not None:
        tiles.append(_tile(_fmt_de(v), "DRT-Flotte", "Fahrzeuge"))
    v = _kpi(kpis, "service_ratio_shift")
    if v is not None:
        tiles.append(_tile(_fmt_pct(v), "Service-Zeit (Schicht)", "Zeit mit Pax / Schichtzeit"))
    v = _kpi(kpis, "parcels_total")
    if v is not None:
        tiles.append(_tile(_fmt_de(v), "Pakete", "gesamt"))
    v = _kpi(kpis, "delivery_rate")
    if v is not None:
        tiles.append(_tile(_fmt_pct(v), "Zustellquote", "ohne missed/unassigned"))
    v = _kpi(kpis, "freight_vehicles")
    if v is not None:
        tiles.append(_tile(_fmt_de(v), "Lieferfahrzeuge",
                           "Auslastung " + _fmt_pct(_kpi(kpis, "avg_max_load") or 0)))
    v = _kpi(kpis, "freight_total_costs")
    if v is not None:
        tiles.append(_tile(_fmt_de(v) + " EUR", "Freight-Kosten (jsprit)",
                           _fmt_de(_kpi(kpis, "freight_cost_per_parcel") or 0, 2) + " EUR/Paket"))
    html.append('<div class="tiles">' + "".join(tiles) + "</div>")

    charts = []

    # modal split: part-to-whole -> ONE horizontal stacked bar, fixed mode slots
    modes = kpis[kpis["kpi_name"].str.startswith("modal_share_")]
    if len(modes):
        labels, data, colors = [], [], []
        for _, r in modes.iterrows():
            mode = r["kpi_name"].replace("modal_share_", "")
            labels.append(mode)
            data.append(round(float(r["value"]) * 100, 2))
            colors.append(MODE_SLOTS.get(mode, 6))
        charts.append(("Modal Split [%]", "c_modal_" + uid, {
            "type": "bar",
            "data": {"labels": ["Modal Split"],
                     "datasets": [{"label": l, "data": [d], "stack": "s",
                                   "categoryPercentage": 0.5,
                                   "__slot": c} for l, d, c in zip(labels, data, colors)]},
            "options": {"indexAxis": "y", "responsive": True, "maintainAspectRatio": False,
                        "scales": {"x": {"stacked": True, "max": 100,
                                         "grid": {"display": False}},
                                   "y": {"stacked": True, "display": False}}},
        }, 120))

    hrs, rides = _series(ts, "drt_rides")
    if hrs:
        charts.append(("DRT-Fahrten je Stunde", "c_rides_" + uid, {
            "type": "bar",
            "data": {"labels": hrs, "datasets": [{
                "label": "Fahrten/h", "data": rides, "__seq": True,
                "borderRadius": 4, "maxBarThickness": 18}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}},
        }, 210))

    hrs, wm = _series(ts, "drt_wait_mean")
    if hrs:
        charts.append(("Mittlere Wartezeit je Stunde [s]", "c_wait_" + uid, {
            "type": "line",
            "data": {"labels": hrs, "datasets": [{
                "label": "Wartezeit [s]", "data": wm, "__seq": True,
                "borderWidth": 2, "pointRadius": 0, "tension": 0.25}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}},
        }, 210))

    hrs, rej = _series(ts, "drt_rejections")
    if hrs:
        charts.append(("Abgelehnte Requests je Stunde", "c_rej_" + uid, {
            "type": "bar",
            "data": {"labels": hrs, "datasets": [{
                "label": "Rejections/h", "data": rej, "__seq": True,
                "borderRadius": 4, "maxBarThickness": 18}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}},
        }, 180))

    hrs, stops = _series(ts, "freight_service_stops")
    if hrs:
        charts.append(("Freight-Servicestopps je Stunde", "c_frt_" + uid, {
            "type": "bar",
            "data": {"labels": hrs, "datasets": [{
                "label": "Stopps/h", "data": stops, "__slot": 1,
                "borderRadius": 4, "maxBarThickness": 18}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}},
        }, 180))

    html.append('<div class="grid2">'
                + "".join(_panel(t, cid, h) for t, cid, _cfg, h in charts)
                + "</div>")

    # full KPI table (grouped, tabular-nums) — the "table view" accessibility fallback
    rows_html = []
    for grp in ["passenger", "system", "freight", "economic", "channel"]:
        for _, r in kpis[kpis["kpi_group"] == grp].iterrows():
            rows_html.append("<tr><td>" + grp + "</td><td>" + str(r["kpi_name"])
                             + "</td><td>" + str(r["value"]) + "</td><td>"
                             + str(r["unit"]) + "</td><td>" + str(r["source"]) + "</td></tr>")
    html.append('<h2>Alle KPIs</h2><div class="panel tablewrap"><table class="kpis">'
                '<tr><th>Gruppe</th><th>KPI</th><th>Wert</th><th>Einheit</th><th>Quelle</th></tr>'
                + "".join(rows_html) + "</table></div>")

    for _, cid, cfg, _ in charts:
        js.append("mk(" + json.dumps(cid) + ", resolveColors(" + json.dumps(cfg) + "));")
    return "".join(html), "\n".join(js)


JS_RESOLVE = """
function resolveColors(cfg) {
  for (const ds of cfg.data.datasets) {
    if (ds.__seq) { ds.backgroundColor = V('--seq'); ds.borderColor = V('--seq'); }
    else if (ds.__slot !== undefined) {
      ds.backgroundColor = CAT[ds.__slot %% CAT.length];
      ds.borderColor = ds.backgroundColor;
    }
    delete ds.__seq; delete ds.__slot;
  }
  return cfg;
}
"""


def render_page(title, body_html, body_js):
    vendor = VENDOR.read_text(encoding="utf-8")
    return ("<!-- generated by analysis/kpi -->\n<meta charset='utf-8'>"
            "<title>" + title + "</title><style>" + CSS + "</style>"
            '<div class="viz-root"><div class="wrap"><h1>' + title + "</h1>"
            + body_html + "</div></div>"
            "<script>" + vendor + "</script>"
            "<script>" + (JS_SETUP % (json.dumps(CAT_DARK), json.dumps(CAT_LIGHT)))
            + (JS_RESOLVE % ()) + body_js + "</script>")


def render_run_page(kpis, ts, title):
    body, js = render_run_sections(kpis, ts, uid="r0")
    return render_page(title, body, js)
```

Implementation notes for the executor:
- `JS_RESOLVE % ()` — the string contains `%%` for the modulo; keep the `%`-formatting consistent (or switch both setup strings to `.replace()` if `%` formatting fights the JS `%` operator — the test only cares about output correctness).
- The chart list uses `__slot`/`__seq` marker keys resolved client-side so light/dark both work from ONE embedded config.
- Modal-split stacked datasets: Chart.js wants one dataset per mode for a stacked single-category bar — that is what the code builds; legend shows mode names (≥ 2 series → legend on, per dataviz).

- [ ] **Step 5: Wire into `build_kpis.build`**

At the end of `build(...)` (after the timeseries write), add:

```python
    import render
    kpis_df, ts_df = render.load_run_csvs(out)
    html = render.render_run_page(kpis_df, ts_df, title=meta.run_id)
    (out / "kpi_dashboard.html").write_text(html, encoding="utf-8")
    print("dashboard: " + str(out / "kpi_dashboard.html"))
```

- [ ] **Step 6: Run tests**

Run: `python -u -m pytest tests/test_render.py -q` then `python -u -m pytest tests/ -q`
Expected: all passed (including the < 1 MB budget assertion).

- [ ] **Step 7: Regenerate the real married120 dashboard and LOOK at it**

```bash
python -u build_kpis.py --run-dir "../../hagrid-matsim-output/DRT_BASELINE_13052025_married120_iter150_jsprit100"
ls -la "../../hagrid-matsim-output/DRT_BASELINE_13052025_married120_iter150_jsprit100/analysis/kpi_dashboard.html"
```

Expected: file < 1 MB. Open it in a browser (`start <path>` from PowerShell): tiles render, charts render in light AND dark (toggle OS theme or emulate), no label collisions, no horizontal page scroll. Fix visual defects before committing.

- [ ] **Step 8: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/render.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/vendor/chart.umd.min.js \
        parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render.py
git commit -m "feat(kpi): lean per-run pax+freight dashboard from canonical CSVs (1e Task 9)"
```

---

### Task 10: `build_comparison.py` — cross-scenario dashboard

**Files:**
- Create: `analysis/kpi/build_comparison.py`
- Modify: `analysis/kpi/render.py` (add `render_comparison_page`)
- Test: `analysis/kpi/tests/test_comparison.py`

**Interfaces:**
- Consumes: N run dirs' `analysis/kpis_long.csv` + `kpi_timeseries.csv` (runs Task 8 output; if missing for a run dir, call `build_kpis.build` first — the CLI does this automatically with `--build-missing`).
- Produces: CLI `python -u build_comparison.py --runs <dirA> <dirB> ... [--out <file>] [--build-missing] [--no-events]`; default out `hagrid-matsim-output/comparison/comparison_<tag1>_vs_<tag2>....html`. Page layout: tab nav `[Vergleich | <run1> | <run2> | ...]` — the Vergleich tab holds grouped horizontal bars (headline KPIs; bar color = scenario slot) + timeseries overlays (one line per run, ≤ 4 direct-labeled) + a full KPI table with runs as columns; each run tab embeds that run's sections via `render_run_sections` (this is the "vereint + Vergleich in einem Dashboard" requirement — data is KPI-level, so N runs stay far under budget).

- [ ] **Step 1: Write the failing test**

```python
# -*- coding: utf-8 -*-
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build_comparison import build_comparison
from build_kpis import build

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def _fake_run(tmp_path, name):
    """Copy the fixture, build its CSVs (file prefix stays DRT_TEST), then
    rebrand the run_id in metadata + CSVs so the comparison sees two distinct runs."""
    d = tmp_path / name
    shutil.copytree(FIX, d)
    build(d, no_events=True)                       # writes analysis/*.csv as DRT_TEST
    rid = name.rsplit("_iter", 1)[0]               # e.g. DRT_TEST_A
    meta_f = d / "run_metadata.json"
    meta_f.write_text(meta_f.read_text(encoding="utf-8")
                      .replace('"DRT_TEST"', '"' + rid + '"'), encoding="utf-8")
    for f in ("kpis_long.csv", "kpis_wide.csv", "kpi_timeseries.csv"):
        p = d / "analysis" / f
        p.write_text(p.read_text(encoding="utf-8").replace("DRT_TEST", rid),
                     encoding="utf-8")
    return d


def test_comparison_two_runs(tmp_path):
    # two pseudo-runs from the same fixture (KPI values identical, ids differ)
    a = _fake_run(tmp_path, "DRT_TEST_A_iter1_jsprit1")
    b = _fake_run(tmp_path, "DRT_TEST_B_iter1_jsprit1")
    out = tmp_path / "cmp.html"
    build_comparison([a, b], out_file=out)         # CSVs exist -> no rebuild
    html = out.read_text(encoding="utf-8")
    assert "Vergleich" in html
    assert "DRT_TEST_A" in html and "DRT_TEST_B" in html
    assert len(html.encode("utf-8")) < 3_000_000   # comparison budget
```

(Sequencing matters: `build()` runs BEFORE the rebrand because it resolves MATSim files via `RunMeta.prefix` = `DRT_TEST`; afterwards only metadata/CSVs are patched. The comparison labels come from `meta.tag or meta.run_id`, so the rebranded run_ids appear as distinct labels — ensure the Task 8 fixture `run_metadata.json` has `"tag": ""`.)

- [ ] **Step 2: Run to verify failure**

Run: `python -u -m pytest tests/test_comparison.py -q`
Expected: FAIL (module missing).

- [ ] **Step 3: Implement**

`render.py` — add:

```python
HEADLINE_KPIS = [
    ("modal_share_drt", "DRT-Modal-Share", 100.0, "%"),
    ("drt_rides", "DRT-Fahrten", 1.0, ""),
    ("wait_median", "Wartezeit Median [s]", 1.0, "s"),
    ("drt_rejection_rate", "Ablehnungsquote", 100.0, "%"),
    ("service_ratio_shift", "Service-Zeit (Schicht)", 100.0, "%"),
    ("drt_vehicle_km", "DRT-Fahrzeug-km", 1.0, "km"),
    ("delivery_rate", "Zustellquote", 100.0, "%"),
    ("freight_vehicle_km", "Freight-km", 1.0, "km"),
    ("freight_cost_per_parcel", "Kosten je Paket [EUR]", 1.0, "EUR"),
]

TAB_CSS = """
.tabbar { display:flex; gap:6px; margin:14px 0; flex-wrap:wrap; }
.tabbar button { border:1px solid var(--border); background:var(--surface);
  color:var(--ink2); border-radius:8px; padding:6px 12px; cursor:pointer; font-size:13px; }
.tabbar button.on { color:var(--ink); font-weight:600; border-color:var(--axis); }
.tab { display:none; } .tab.on { display:block; }
"""

TAB_JS = """
function showTab(i) {
  document.querySelectorAll('.tab').forEach((t, j) => t.classList.toggle('on', i === j));
  document.querySelectorAll('.tabbar button').forEach((b, j) => b.classList.toggle('on', i === j));
}
"""


def _scenario_slot(scenario, fallback_index):
    return SCENARIO_SLOTS.get(scenario, 4 + (fallback_index % 4))


def render_comparison_page(runs, title):
    """runs: list of dicts {label, scenario, kpis (DataFrame), ts (DataFrame)}."""
    charts, js = [], []

    # headline grouped horizontal bars: one chart per KPI, one bar per run
    for idx, (name, label, scale, unit) in enumerate(HEADLINE_KPIS):
        labels, values, slots = [], [], []
        for i, r in enumerate(runs):
            v = _kpi(r["kpis"], name)
            if v is None:
                continue
            labels.append(r["label"])
            values.append(round(v * scale, 3))
            slots.append(_scenario_slot(r["scenario"], i))
        if not values:
            continue
        cid = "cmp_" + str(idx)
        charts.append(_panel(label + ((" [" + unit + "]") if unit and unit != "%" else
                                      (" [%]" if unit == "%" else "")), cid,
                             height=60 + 34 * len(values)))
        js.append("mk(" + json.dumps(cid) + ", resolveColors(" + json.dumps({
            "type": "bar",
            "data": {"labels": labels, "datasets": [{
                "label": label, "data": values,
                "__slots": slots, "borderRadius": 4, "maxBarThickness": 22}]},
            "options": {"indexAxis": "y", "responsive": True,
                        "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}},
        }) + "));")

    # timeseries overlays: one line per run (color = scenario slot)
    for sname, slabel in [("drt_rides", "DRT-Fahrten je Stunde"),
                          ("drt_wait_mean", "Mittlere Wartezeit je Stunde [s]"),
                          ("freight_service_stops", "Freight-Stopps je Stunde")]:
        datasets = []
        for i, r in enumerate(runs):
            hrs, vals = _series(r["ts"], sname)
            if hrs:
                datasets.append({"label": r["label"],
                                 "data": [{"x": h, "y": v} for h, v in zip(hrs, vals)],
                                 "__slot": _scenario_slot(r["scenario"], i),
                                 "borderWidth": 2, "pointRadius": 0, "tension": 0.25})
        if datasets:
            cid = "cmpts_" + sname
            charts.append(_panel(slabel, cid, height=230))
            js.append("mk(" + json.dumps(cid) + ", resolveColors(" + json.dumps({
                "type": "line", "data": {"datasets": datasets},
                "options": {"responsive": True, "maintainAspectRatio": False,
                            "parsing": False,
                            "scales": {"x": {"type": "linear", "min": 0, "max": 30}}},
            }) + "));")

    # full comparison table: KPI rows, runs as columns
    all_names = []
    for r in runs:
        for _, k in r["kpis"].iterrows():
            key = (k["kpi_group"], k["kpi_name"], k["unit"])
            if key not in all_names:
                all_names.append(key)
    header = "<tr><th>Gruppe</th><th>KPI</th><th>Einheit</th>" + "".join(
        "<th>" + r["label"] + "</th>" for r in runs) + "</tr>"
    body_rows = []
    for grp, name, unit in all_names:
        cells = ""
        for r in runs:
            m = r["kpis"][(r["kpis"]["kpi_name"] == name) & (r["kpis"]["kpi_group"] == grp)]
            cells += "<td>" + (str(m.iloc[0]["value"]) if len(m) else "-") + "</td>"
        body_rows.append("<tr><td>" + grp + "</td><td>" + name + "</td><td>"
                         + str(unit) + "</td>" + cells + "</tr>")
    table = ('<h2>Alle KPIs im Vergleich</h2><div class="panel tablewrap">'
             '<table class="kpis">' + header + "".join(body_rows) + "</table></div>")

    cmp_tab = '<div class="grid2">' + "".join(charts) + "</div>" + table

    # per-run tabs reuse the single-run sections
    tabs_html = ['<div class="tab on">' + cmp_tab + "</div>"]
    run_js = []
    for i, r in enumerate(runs):
        body, sec_js = render_run_sections(r["kpis"], r["ts"], uid="run" + str(i))
        tabs_html.append('<div class="tab">' + body + "</div>")
        run_js.append(sec_js)
    tabbar = ('<div class="tabbar"><button class="on" onclick="showTab(0)">Vergleich</button>'
              + "".join('<button onclick="showTab(' + str(i + 1) + ')">' + r["label"]
                        + "</button>" for i, r in enumerate(runs)) + "</div>")

    # __slots (per-bar colors) needs a tiny resolver extension:
    body_js = TAB_JS + "\n" + "\n".join(js) + "\n" + "\n".join(run_js)
    return render_page(title, tabbar + "".join(tabs_html), body_js)
```

Executor notes:
- Append `TAB_CSS` to the `CSS` constant in this task (`CSS = CSS + TAB_CSS` at module level after both are defined, or concatenate in the literal) — `render_page`'s signature stays unchanged.
- Extend `resolveColors` to handle `__slots` (array → `backgroundColor` array): three lines analogous to `__slot`.
- Chart.js per-run tabs: charts inside `display:none` tabs need `resize` on first show — simplest fix: create ALL charts after load (they size once), and call `window.dispatchEvent(new Event('resize'))` inside `showTab`.

`build_comparison.py`:

```python
# -*- coding: utf-8 -*-
"""Cross-scenario comparison dashboard from N runs' canonical KPI CSVs.

Usage (from analysis/kpi/):
    python -u build_comparison.py --runs <runDirA> <runDirB> [--out <file>] [--build-missing] [--no-events]
"""
import argparse
from pathlib import Path

import build_kpis
import render
from run_meta import load_run_meta


def build_comparison(run_dirs, out_file=None, build_missing=False, no_events=False):
    runs = []
    for d in run_dirs:
        d = Path(d)
        meta = load_run_meta(d)
        analysis = d / "analysis"
        if not (analysis / "kpis_long.csv").exists():
            if not build_missing:
                raise FileNotFoundError(str(analysis / "kpis_long.csv")
                                        + " (run build_kpis.py first or pass --build-missing)")
            build_kpis.build(d, no_events=no_events)
        kpis, ts = render.load_run_csvs(analysis)
        label = meta.tag if meta.tag else meta.run_id
        runs.append({"label": label, "scenario": meta.scenario, "kpis": kpis, "ts": ts})

    if out_file is None:
        cmp_dir = Path(run_dirs[0]).parent / "comparison"
        cmp_dir.mkdir(exist_ok=True)
        out_file = cmp_dir / ("comparison_" + "_vs_".join(r["label"] for r in runs) + ".html")
    html = render.render_comparison_page(runs, title="Szenario-Vergleich: "
                                         + ", ".join(r["label"] for r in runs))
    Path(out_file).write_text(html, encoding="utf-8")
    print("comparison dashboard: " + str(out_file))
    return Path(out_file)


def main():
    ap = argparse.ArgumentParser(description="Cross-scenario KPI comparison dashboard")
    ap.add_argument("--runs", nargs="+", required=True)
    ap.add_argument("--out", default=None)
    ap.add_argument("--build-missing", action="store_true")
    ap.add_argument("--no-events", action="store_true")
    a = ap.parse_args()
    build_comparison(a.runs, out_file=a.out, build_missing=a.build_missing,
                     no_events=a.no_events)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run tests**

Run: `python -u -m pytest tests/ -q`
Expected: all passed.

- [ ] **Step 5: ACCEPTANCE — real multi-run comparison + budgets + eyeball**

```bash
python -u build_comparison.py --build-missing --runs \
  "../../hagrid-matsim-output/DRT_BASELINE_13052025_married120_iter150_jsprit100" \
  "../../hagrid-matsim-output/DRT_BASELINE_13052025_fleet120_depot_railpt_iter150_jsprit100" \
  "../../hagrid-matsim-output/DRT_BASELINE_13052025_fleet80_depot_railpt_iter150_jsprit100" \
  "../../hagrid-matsim-output/LMD_BASELINE_13052025_localdepots_stagger_iter0_jsprit100"
```

Expected: exit 0; output HTML in `hagrid-matsim-output/comparison/`; size **< 3 MB**; opens instantly in the browser; married120 vs fleet120 rows show the known deltas (rides 9171 vs 8968, wait median 753 vs 770); tabs switch; charts render in light + dark; the LMD run appears with freight KPIs only (dashes for pax rows in the table). Screenshot/eyeball for label collisions.

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/build_comparison.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/render.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_comparison.py
git commit -m "feat(kpi): cross-scenario comparison dashboard with per-run tabs (1e Task 10)"
```

---

## Self-Review (run after writing, fix inline)

1. **Spec coverage:** §7 long-format CSV ✅ (Task 6/8, exact columns + D2 source); wide variant ✅ (Task 6); lands in run dir ✅ (D5 — spec said `hagrid-output/{RUN_ID}/`, user redirected to `hagrid-matsim-output/<run>/analysis/`); KPI groups System/Passenger/Freight/Economic/Channel ✅ (all five emitted; Channel gets its 1c KPIs via the extractor contract); labour-share KPI ✅ (placeholder-flagged); dashboard ✅ (Tasks 9/10, user's combined+comparison requirement with performance budget). NOT covered by design: Java `IntegratedKPIHandler` (D1, documented deviation); parcels/h-by-provider timeseries (deferred, documented in Task 7).
2. **Placeholder scan:** no TBDs; two executor-note blocks in Tasks 9/10 explain `%`-format/`__slots` mechanics next to complete code — acceptable (they instruct HOW, code is given).
3. **Type consistency:** `row()` dict keys, `RunMeta` fields, `COLUMNS`, suffix constants, `build()`/`build_comparison()` signatures cross-checked across tasks ✅. `render_run_sections(kpis, ts, uid)` used identically in Tasks 9 and 10 ✅. Fixture prefix `DRT_TEST` consistent across Tasks 4/5/7/8 ✅.

## Execution notes

- Tasks 1 is independent of 2–10; Tasks 4–7 depend on 2 (and 3 for events); 8 on 4–7; 9 on 8; 10 on 9.
- Real-run acceptance steps (8/9/10) touch the married120 run dir — read-only except for new files in `analysis/` and the freight events cache; nothing existing is overwritten (the drt events cache is rebuilt byte-identically only if the freight cache is missing).
- After this plan lands, the pending items from memory remain: repoint or retire the legacy dashboards later ("mittelfristig ablösen"), refine the placeholder cost model before the headline evaluation.
