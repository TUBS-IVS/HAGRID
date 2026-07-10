# Run-Dashboard v2 — Plan A: Foundation & Data Extractors — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce the three new canonical data files (`kpis_provider.csv`, `kpi_iterations.csv`, `kpi_distributions.csv`) and the new tabular `kpi_timeseries` series, all from tabular/XML sources + the existing `reconstruct()`, wired into `build_kpis.py` — the data backbone every later plan (rendering, maps) consumes.

**Architecture:** Extend the flat, no-`__init__` `analysis/kpi/` package. New extractor modules follow the existing pattern (a pure `extract(...) -> rows` function + a `write(...)` function, like `timeseries.py`). Provider/vehicle-type classification is ported verbatim from the Java `FreightEventHandler.classifyVehicle` / `CarrierXmlParser.guessProvider`. Nothing that reads `output_network.xml.gz` is in scope — that is Plan D. The 1e long-CSV schema (`kpis_long.csv`) is **frozen and untouched**; all new data lands in new files.

**Tech Stack:** Python 3.13, pandas, stdlib `xml.etree.ElementTree` + `gzip` (streaming `iterparse`), pytest 9.x. ASCII-only `print()`, run with `python -u`.

## Global Constraints

- 1e long-CSV schema frozen: `run_id;study_area;scenario;operation_mode;kpi_group;kpi_name;value;unit;source` — never add columns; new data = new files only.
- `;`-delimited, dot decimals, UTF-8. Floats formatted `"{:.6g}"` (match `kpi_writer._fmt`).
- ASCII-only in every `print()` (cp1252 console crashes on non-ASCII). No emoji, no `≥`, no German chars in stdout.
- Never edit the legacy dashboards (`DashboardGenerator.java`, `drt-headline/*.py`). Port logic by copying, not importing.
- No `.gitignore` changes; explicit `git add` lists per commit. Branch `hendrik`; no master merge.
- Package has no `__init__.py`; modules import flat by name; tests bootstrap `sys.path` via `sys.path.insert(0, str(Path(__file__).resolve().parent.parent))` (copy from any existing test file).
- Tests run from `analysis/kpi/`: `python -u -m pytest tests/ -q` (or a single file/test with `-k`).
- Forward-compat: DRT/freight vehicle-ID role membership is **non-exclusive** (a vehicle ID may be both DRT and freight in 1c/1d). Classification functions must not assume exclusivity.

## File Structure

**New modules (all in `parcel-demand-2-matsim-pipeline/analysis/kpi/`):**
- `freight_classify.py` — pure classification functions (provider from carrier-id/attr, vehicle-type from vehicle-id). Imported by `extract_freight_provider.py` and (later) `timeseries.py`/`maps.py`.
- `carriers_parse.py` — streaming parser for `output_carriers.xml.gz` (attributes, services, vehicle capabilities, selected-plan tours) and `output_carriersVehicleTypes.xml.gz` (capacity, fixed cost, cost/m). Returns plain dataclasses; no aggregation.
- `extract_freight_provider.py` — provider + vehicle-type aggregation → `kpis_provider.csv` rows, including low-util exclusion + proportional cost re-allocation. Owns its `write(...)`.
- `extract_iterations.py` — convergence series → `kpi_iterations.csv`. Owns its `write(...)`.
- `distributions.py` — network-free histograms → `kpi_distributions.csv`. Owns its `write(...)`.

**Modified modules:**
- `timeseries.py` — add tabular series `drt_requests_submitted`, `drt_feeder_trips`.
- `extract_drt.py` — accept an optional pre-computed `recon` dict (avoid a second event pass).
- `build_kpis.py` — compute `reconstruct()` once, share it; call the new extractors; write the new CSVs; honor `--no-events` for the event-dependent distribution rows.

**New fixtures (under `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/`):**
- `mini_lmd/` — hand-authored tiny run exercising controllable classification/exclusion numbers (2 real providers + 1 supply carrier + 1 cargobike, 3 vehicle types, a low-util tour to exclude).
- `real_heads/` — verbatim heads of married250 files (schema-drift guard).

---

## Deferred to Plan D (documented here so no requirement is silently dropped)

These provider/distribution items need `output_network.xml.gz` geometry and are **explicitly out of Plan A**:
- `kpi_distributions.csv` series `series=drt_tour_distance` (per-vehicle DRT tour km) and the `occ_km` occupancy-decomposition row (km share by occupancy level). Plan A writes `occ_time` and `occ_segments` only.
- Provider VRP-table column "stem%" (depot→first-stop routed distance ratio).
- All map geometry and event-derived freight hourly series (`freight_parcels_h_<provider>`, `freight_active_vehicles_<provider>`, `freight_depot_departures/arrivals`).

Each deferred item must be emitted as a `log`/comment note where relevant, and listed in Plan D's scope. `distributions.py` must `print()` an ASCII note when it skips `drt_tour_distance`/`occ_km` (e.g. `"[distributions] drt_tour_distance/occ_km deferred to Plan D (needs network km)"`).

---

## Reference: ported logic (copy these — do not import from Java)

### Provider classification — port of `CarrierXmlParser.guessProvider` (`CarrierXmlParser.java:544-555`)
Provider is taken from the carrier `provider` attribute if present (married250 has it: `<attribute name="provider">dhl</attribute>`), else guessed from the carrier id (lowercased, first match wins): `amazon`→amazon, `dp/dhl`|`dp_dhl`→`dp/dhl`, `dhl`→dhl, `dpd`→dpd, `fedex`→fedex, `gls`→gls, `hermes`→hermes, `ups`→ups, else `other`. Empty provider normalizes to `other`. Carrier type: id contains `supply` (or `carrierType` attr == supply) → `supply`, else `delivery`.

### Vehicle-type classification — port of `FreightEventHandler.classifyVehicle` (`FreightEventHandler.java:128-142`)
First match wins on the vehicle/type id string:
1. contains `_Supply_Vehicle_` or `_veh_supply_` → if `supply_light_van`→`SUPPLY_VAN`; elif `light`→`TRUCK_LIGHT`; else `TRUCK`.
2. contains `_CEP_Vehicle_` or `_veh_cep_` or `_egrocery_van_` → `VAN`.
3. contains `ct_cep_size` → `VAN`.
4. contains `_cargoBike_` or `_cargobike_` → `CARGOBIKE`.
5. else → `None` (unclassified; dropped).

Type display labels (used later by rendering, keep as a dict here): `VAN`="CEP Van", `CARGOBIKE`="Cargobike", `TRUCK`="Truck (heavy)", `TRUCK_LIGHT`="Truck (light)", `SUPPLY_VAN`="Supply Van".

### Per-tour parcels & stops — port of `countTourParcels` (`DashboardGenerator.java:1918-1929`)
For each `<act type="service" serviceId=...>` in a tour: `parcels += service.capacityDemand` if the service resolves, else `+= 1`. `stops` = number of service acts in the tour.

### Low-util exclusion + proportional cost re-allocation — port of `precomputeExcludedVehicles` + KPI/cost math (`DashboardGenerator.java:153-184`, `296-310`, `733-749`)
- Threshold `LOW_UTIL_THRESHOLD = 0.05`.
- Only **delivery** carriers are considered (supply never excluded).
- Per tour: `parcels` (above), `cap = vehicle-type capacity` (`other=` on `<capacity>`), `lf = 0.0 if parcels==0 or cap<=0 else min(1.0, parcels/cap)`. If `lf < threshold`: exclude that tour's vehicle (`eventVehicleId`).
- Re-allocation of carrier **variable** costs (distance/time/overtime/activity/TW): scale by `ratio = nonExcludedTours / allTours`. **Fixed** cost = sum of `fixedCostsPerDay(type)` over surviving vehicles.
- `total = distance + time + fixed + overtime` (note: activity and TW penalty are components but NOT in total — `v[0]=v[1]+v[2]+v[3]+v[5]`).
- Missed parcels re-allocated the same way: `missed += round(carrier.missedParcels * ratio)`.
- Cost source: variable euro amounts read from carrier attributes (`costDistance`, `costTime`, `costActivity`, `costOvertime`, `costTimeWindowPenalty`); fixed from vehicle-type `fixedCostsPerDay`.

### `eventVehicleId` format — port of `ParsedTour.eventVehicleId` (`CarrierXmlParser.java:61-64`)
`"freight_" + carrierId + "_veh_" + vehicleId + "_" + tourId` (matches `TimeDistance_perVehicle.tsv` `vehicleId` column, e.g. `freight_amazon_veh_amazon_ct_cep_size_s_h8_v0_11`).

### Real married250 schema facts (verbatim, for `real_heads/` fixtures)
- `TimeDistance_perCarrier.tsv` header: `carrierId  nuOfTours  tourDurations[s]  tourDurations[h]  travelDistances[m]  travelDistances[km]  travelTimes[s]  travelTimes[h]  fixedCosts[EUR]  varCostsTime[EUR]  varCostsDist[EUR]  totalCosts[EUR]` (tab-separated).
- `TimeDistance_perVehicle.tsv` header includes: `vehicleId  carrierId  vehicleTypeId  tourId  tourDuration[s]  tourDuration[h]  travelDistance[m]  travelDistance[km]  travelTime[s]  travelTime[h]  costPerSecond[EUR/s]  costPerMeter[EUR/m]  fixedCosts[EUR]  varCostsTime[EUR]  varCostsDist[EUR]  totalCosts[EUR]`.
- `drt_customer_stats_drt.csv` (`;`): `runId;iteration;rides;rides_pax;groupSize_mean;wait_average;wait_max;wait_p95;wait_p75;wait_median;percentage_WT_below_10;percentage_WT_below_15;inVehicleTravelTime_mean;distance_m_mean;directDistance_m_mean;totalTravelTime_mean;fareAllReferences_mean;rejections;rejectionRate`. Multiple iteration rows (0..N).
- `modestats.csv` (`;`): `iteration;bike;car;drt;pt;ride;walk`. Multiple iteration rows.
- vehicle-types XML: `<vehicleType id="ct_cep_size_l"> ... <capacity ... other="230.0"> ... <costInformation fixedCostsPerDay="189.15" costsPerMeter="3.86430173292559E-4" .../>`.
- married250 has **no** `carrier_scores.txt` and **no** `run_metadata.json` (exercises graceful-skip + legacy dir-name fallback).

### New CSV schemas (from spec §3.1)
- `kpis_provider.csv`: `run_id;provider;kpi_name;value;unit;source`. Per-vehicle-type rows carry `provider = "type:<vehicleType>"`.
- `kpi_iterations.csv`: `run_id;series;iteration;value;unit`.
- `kpi_distributions.csv`: `run_id;series;bin_lo;bin_hi;value;unit`.

---

## Task 1: Provider & vehicle-type classification (`freight_classify.py`)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/kpi/freight_classify.py`
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_freight_classify.py`

**Interfaces:**
- Produces:
  - `guess_provider(carrier_id: str) -> str`
  - `provider_of(carrier_id: str, provider_attr: str | None) -> str` (attr wins, else guess, empty→`"other"`)
  - `carrier_type_of(carrier_id: str, carrier_type_attr: str | None) -> str` (`"supply"`/`"delivery"`)
  - `classify_vehicle(vehicle_or_type_id: str) -> str | None` (returns `"VAN"|"CARGOBIKE"|"TRUCK"|"TRUCK_LIGHT"|"SUPPLY_VAN"|None`)
  - `VEHICLE_TYPE_LABELS: dict[str, str]`

- [ ] **Step 1: Write the failing tests**

```python
# tests/test_freight_classify.py
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import freight_classify as fc


def test_guess_provider_keywords():
    assert fc.guess_provider("dhl") == "dhl"
    assert fc.guess_provider("amazon_lmd_2") == "amazon"
    assert fc.guess_provider("dp_dhl_hub") == "dp/dhl"      # dp_dhl before dhl
    assert fc.guess_provider("some_UPS_carrier") == "ups"
    assert fc.guess_provider("unknown_x") == "other"


def test_provider_attr_wins_and_empty_normalises():
    assert fc.provider_of("weird_id", "hermes") == "hermes"
    assert fc.provider_of("dpd_1", None) == "dpd"
    assert fc.provider_of("dpd_1", "") == "dpd"             # empty attr -> guess
    assert fc.provider_of("weird_id", None) == "other"


def test_carrier_type():
    assert fc.carrier_type_of("amazon_supply_1", None) == "supply"
    assert fc.carrier_type_of("dhl", None) == "delivery"
    assert fc.carrier_type_of("dhl", "supply") == "supply"  # attr forces supply


def test_classify_vehicle_first_match_wins():
    assert fc.classify_vehicle("x_Supply_Vehicle_supply_light_van_1") == "SUPPLY_VAN"
    assert fc.classify_vehicle("x_Supply_Vehicle_light_1") == "TRUCK_LIGHT"
    assert fc.classify_vehicle("x_Supply_Vehicle_1") == "TRUCK"
    assert fc.classify_vehicle("freight_dhl_veh_dhl_CEP_Vehicle_2") == "VAN"
    assert fc.classify_vehicle("dhl_ct_cep_size_s_h8_v0") == "VAN"       # married250 real id
    assert fc.classify_vehicle("x_cargoBike_7") == "CARGOBIKE"
    assert fc.classify_vehicle("pt_bus_42") is None


def test_labels_cover_all_types():
    for t in ("VAN", "CARGOBIKE", "TRUCK", "TRUCK_LIGHT", "SUPPLY_VAN"):
        assert t in fc.VEHICLE_TYPE_LABELS
```

- [ ] **Step 2: Run the tests, verify they fail** — `python -u -m pytest tests/test_freight_classify.py -q` → ModuleNotFoundError / AttributeError.

- [ ] **Step 3: Implement `freight_classify.py`** (direct port; complete code):

```python
# -*- coding: utf-8 -*-
"""Provider & vehicle-type classification, ported verbatim from the Java
DashboardGenerator/FreightEventHandler/CarrierXmlParser. Kept side-effect free
and network-free so extractors, timeseries and (later) maps share one truth."""

VEHICLE_TYPE_LABELS = {
    "VAN": "CEP Van", "CARGOBIKE": "Cargobike", "TRUCK": "Truck (heavy)",
    "TRUCK_LIGHT": "Truck (light)", "SUPPLY_VAN": "Supply Van",
}


def guess_provider(carrier_id):
    s = (carrier_id or "").lower()
    if "amazon" in s: return "amazon"
    if "dp/dhl" in s or "dp_dhl" in s: return "dp/dhl"
    if "dhl" in s: return "dhl"
    if "dpd" in s: return "dpd"
    if "fedex" in s: return "fedex"
    if "gls" in s: return "gls"
    if "hermes" in s: return "hermes"
    if "ups" in s: return "ups"
    return "other"


def provider_of(carrier_id, provider_attr):
    p = (provider_attr or "").strip()
    if not p:
        p = guess_provider(carrier_id)
    return p or "other"


def carrier_type_of(carrier_id, carrier_type_attr):
    if carrier_type_attr and carrier_type_attr.strip().lower() == "supply":
        return "supply"
    return "supply" if "supply" in (carrier_id or "").lower() else "delivery"


def classify_vehicle(vid):
    v = vid or ""
    if "_Supply_Vehicle_" in v or "_veh_supply_" in v:
        if "supply_light_van" in v: return "SUPPLY_VAN"
        if "light" in v: return "TRUCK_LIGHT"
        return "TRUCK"
    if "_CEP_Vehicle_" in v or "_veh_cep_" in v or "_egrocery_van_" in v:
        return "VAN"
    if "ct_cep_size" in v:
        return "VAN"
    if "_cargoBike_" in v or "_cargobike_" in v:
        return "CARGOBIKE"
    return None
```

- [ ] **Step 4: Run the tests, verify they pass.**

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/freight_classify.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_freight_classify.py
git commit -m "feat(kpi): port provider/vehicle-type classification (v2 Plan A Task 1)"
```

---

## Task 2: Carrier & vehicle-type XML parser (`carriers_parse.py`)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/kpi/carriers_parse.py`
- Create fixtures: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/mini_lmd/` (see below)
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_carriers_parse.py`

**Interfaces:**
- Produces:
  - `@dataclass ServiceDef: service_id: str; capacity_demand: int`
  - `@dataclass VehicleDef: vehicle_id: str; type_id: str`
  - `@dataclass TourDef: vehicle_id: str; tour_id: str; service_ids: list[str]` (`event_vehicle_id(carrier_id)` method → `"freight_"+carrier_id+"_veh_"+vehicle_id+"_"+tour_id`)
  - `@dataclass CarrierDef: carrier_id: str; attrs: dict[str,str]; services: dict[str,ServiceDef]; vehicles: dict[str,VehicleDef]; tours: list[TourDef]`
  - `@dataclass VehTypeDef: type_id: str; capacity: float; fixed_cost_per_day: float; costs_per_meter: float`
  - `parse_carriers(carriers_xml_gz: Path) -> list[CarrierDef]` (streaming `iterparse`, selected plan only)
  - `parse_vehicle_types(vtypes_xml_gz: Path) -> dict[str, VehTypeDef]`
  - Helper `attr_float(attrs, name, default=0.0) -> float`, `attr_int(attrs, name, default=0) -> int`

**Parsing notes:**
- MATSim XML uses a default namespace (`xmlns="http://www.matsim.org/files/dtd"`) on real runs but the mini fixture has none — use tag-suffix matching (`el.tag.endswith("carrier")`) exactly like existing `extract_freight._carrier_attrs`.
- Attributes: `{a.get("name"): (a.text or "").strip() for a in el.iter() if a.tag.endswith("attribute")}` — but scope to the carrier's own `<attributes>` block (service/vehicle-type attributes also use `<attribute>`; collect carrier attrs from the direct `attributes` child, not `el.iter()`, to avoid pulling service attrs). Walk children explicitly.
- Services: `<service id capacityDemand>` under `<services>`; `capacity_demand=int(float(capacityDemand or 1))`.
- Vehicles: `<vehicle id typeId>` under `capabilities/vehicles`.
- Tours: parse the **selected** `<plan selected="true">` (or the single `<plan>` if unmarked) → each `<tour vehicleId>` (tourId may be a `tourId` attr or index) → collect `serviceId` from `<act type="service" serviceId=...>`.
- `parse_vehicle_types`: `<vehicleType id>` → `<capacity other=>` (float), `<costInformation fixedCostsPerDay= costsPerMeter=>`.

- [ ] **Step 1: Create the `mini_lmd/` fixtures.** Hand-author these files so downstream tests have known numbers:

`tests/fixtures/mini_lmd/MINI.output_carriersVehicleTypes.xml.gz` (gzip of):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<vehicleDefinitions>
  <vehicleType id="ct_cep_size_s">
    <capacity other="100.0"/>
    <costInformation fixedCostsPerDay="150.0" costsPerMeter="0.0003" costsPerSecond="0.0"/>
  </vehicleType>
  <vehicleType id="cargoBike_t">
    <capacity other="30.0"/>
    <costInformation fixedCostsPerDay="20.0" costsPerMeter="0.0001" costsPerSecond="0.0"/>
  </vehicleType>
  <vehicleType id="supply_truck">
    <capacity other="350.0"/>
    <costInformation fixedCostsPerDay="400.0" costsPerMeter="0.0009" costsPerSecond="0.0"/>
  </vehicleType>
</vehicleDefinitions>
```

`tests/fixtures/mini_lmd/MINI.output_carriers.xml.gz` (gzip of) — dhl (delivery, 2 vehicles: one healthy tour, one 1-parcel tour that will be excluded at cap=100), hermes (delivery, cargobike), and a supply carrier:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<carriers>
  <carrier id="dhl">
    <attributes>
      <attribute name="provider" class="java.lang.String">dhl</attribute>
      <attribute name="numberOfParcels" class="java.lang.Integer">100</attribute>
      <attribute name="missedParcels" class="java.lang.Integer">10</attribute>
      <attribute name="unassignedParcels" class="java.lang.Integer">0</attribute>
      <attribute name="costDistance" class="java.lang.Double">200.0</attribute>
      <attribute name="costTime" class="java.lang.Double">0.0</attribute>
      <attribute name="costActivity" class="java.lang.Double">50.0</attribute>
      <attribute name="costOvertime" class="java.lang.Double">0.0</attribute>
      <attribute name="costTimeWindowPenalty" class="java.lang.Double">0.0</attribute>
    </attributes>
    <capabilities fleetSize="INFINITE">
      <vehicles>
        <vehicle id="dhl_ct_cep_size_s_h8_v0" typeId="ct_cep_size_s"/>
        <vehicle id="dhl_ct_cep_size_s_h8_v1" typeId="ct_cep_size_s"/>
      </vehicles>
    </capabilities>
    <services>
      <service id="s0" capacityDemand="60"/>
      <service id="s1" capacityDemand="30"/>
      <service id="s2" capacityDemand="1"/>
    </services>
    <plans>
      <plan selected="true">
        <tour vehicleId="dhl_ct_cep_size_s_h8_v0">
          <act type="start"/>
          <act type="service" serviceId="s0"/>
          <act type="service" serviceId="s1"/>
          <act type="end"/>
        </tour>
        <tour vehicleId="dhl_ct_cep_size_s_h8_v1">
          <act type="start"/>
          <act type="service" serviceId="s2"/>
          <act type="end"/>
        </tour>
      </plan>
    </plans>
  </carrier>
  <carrier id="hermes">
    <attributes>
      <attribute name="provider" class="java.lang.String">hermes</attribute>
      <attribute name="numberOfParcels" class="java.lang.Integer">25</attribute>
      <attribute name="missedParcels" class="java.lang.Integer">0</attribute>
      <attribute name="costDistance" class="java.lang.Double">40.0</attribute>
      <attribute name="costTime" class="java.lang.Double">0.0</attribute>
      <attribute name="costActivity" class="java.lang.Double">10.0</attribute>
      <attribute name="costOvertime" class="java.lang.Double">0.0</attribute>
      <attribute name="costTimeWindowPenalty" class="java.lang.Double">0.0</attribute>
    </attributes>
    <capabilities fleetSize="INFINITE">
      <vehicles><vehicle id="hermes_cargoBike_v0" typeId="cargoBike_t"/></vehicles>
    </capabilities>
    <services><service id="h0" capacityDemand="25"/></services>
    <plans>
      <plan selected="true">
        <tour vehicleId="hermes_cargoBike_v0">
          <act type="service" serviceId="h0"/>
        </tour>
      </plan>
    </plans>
  </carrier>
  <carrier id="amazon_supply">
    <attributes>
      <attribute name="provider" class="java.lang.String">amazon</attribute>
      <attribute name="carrierType" class="java.lang.String">supply</attribute>
      <attribute name="numberOfParcels" class="java.lang.Integer">500</attribute>
      <attribute name="missedParcels" class="java.lang.Integer">0</attribute>
      <attribute name="costDistance" class="java.lang.Double">900.0</attribute>
      <attribute name="costTime" class="java.lang.Double">0.0</attribute>
      <attribute name="costActivity" class="java.lang.Double">100.0</attribute>
      <attribute name="costOvertime" class="java.lang.Double">0.0</attribute>
      <attribute name="costTimeWindowPenalty" class="java.lang.Double">0.0</attribute>
    </attributes>
    <capabilities fleetSize="INFINITE">
      <vehicles><vehicle id="amazon_Supply_Vehicle_v0" typeId="supply_truck"/></vehicles>
    </capabilities>
    <services><service id="a0" capacityDemand="500"/></services>
    <plans>
      <plan selected="true">
        <tour vehicleId="amazon_Supply_Vehicle_v0">
          <act type="service" serviceId="a0"/>
        </tour>
      </plan>
    </plans>
  </carrier>
</carriers>
```
Create these with a one-off script (gzip-compress the strings). Use `tourId` = index when the `<tour>` has no `tourId` attribute (the mini fixture omits it — parser must default to the tour's positional index within the carrier as a string).

`tests/fixtures/mini_lmd/analysis/freight/TimeDistance_perCarrier.tsv` (tab-separated, matching real header):
```
carrierId	nuOfTours	tourDurations[s]	tourDurations[h]	travelDistances[m]	travelDistances[km]	travelTimes[s]	travelTimes[h]	fixedCosts[EUR]	varCostsTime[EUR]	varCostsDist[EUR]	totalCosts[EUR]
dhl	2	36000.0	10.0	100000.0	100.0	30000.0	8.333	300.0	0.0	30.0	330.0
hermes	1	7200.0	2.0	20000.0	20.0	6000.0	1.667	20.0	0.0	2.0	22.0
amazon_supply	1	18000.0	5.0	200000.0	200.0	15000.0	4.167	400.0	0.0	90.0	490.0
```

`tests/fixtures/mini_lmd/analysis/freight/TimeDistance_perVehicle.tsv`:
```
vehicleId	carrierId	vehicleTypeId	tourId	tourDuration[s]	tourDuration[h]	travelDistance[m]	travelDistance[km]	travelTime[s]	travelTime[h]	costPerSecond[EUR/s]	costPerMeter[EUR/m]	fixedCosts[EUR]	varCostsTime[EUR]	varCostsDist[EUR]	totalCosts[EUR]
freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0	dhl	ct_cep_size_s	0	18000.0	5.0	60000.0	60.0	18000.0	5.0	0.0	0.0003	150.0	0.0	18.0	168.0
freight_dhl_veh_dhl_ct_cep_size_s_h8_v1_1	dhl	ct_cep_size_s	1	18000.0	5.0	40000.0	40.0	12000.0	3.333	0.0	0.0003	150.0	0.0	12.0	162.0
freight_hermes_veh_hermes_cargoBike_v0_0	hermes	cargoBike_t	0	7200.0	2.0	20000.0	20.0	6000.0	1.667	0.0	0.0001	20.0	0.0	2.0	22.0
freight_amazon_supply_veh_amazon_Supply_Vehicle_v0_0	amazon_supply	supply_truck	0	18000.0	5.0	200000.0	200.0	15000.0	4.167	0.0	0.0009	400.0	0.0	90.0	490.0
```

`tests/fixtures/mini_lmd/analysis/freight/Load_perVehicle.tsv` (header-only, real-run shape):
```
vehicleId	vehicleTypeId	capacity	maxLoad	maxLoadPercentage	handledDemand	load state during tour
```

- [ ] **Step 2: Write the failing tests**

```python
# tests/test_carriers_parse.py
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import carriers_parse as cp

FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


def test_parse_vehicle_types():
    vt = cp.parse_vehicle_types(FIX / "MINI.output_carriersVehicleTypes.xml.gz")
    assert vt["ct_cep_size_s"].capacity == 100.0
    assert vt["ct_cep_size_s"].fixed_cost_per_day == 150.0
    assert vt["cargoBike_t"].capacity == 30.0


def test_parse_carriers_structure():
    cs = {c.carrier_id: c for c in cp.parse_carriers(FIX / "MINI.output_carriers.xml.gz")}
    assert set(cs) == {"dhl", "hermes", "amazon_supply"}
    dhl = cs["dhl"]
    assert dhl.attrs["provider"] == "dhl"
    assert cp.attr_int(dhl.attrs, "numberOfParcels") == 100
    assert dhl.services["s0"].capacity_demand == 60
    assert dhl.vehicles["dhl_ct_cep_size_s_h8_v0"].type_id == "ct_cep_size_s"
    assert len(dhl.tours) == 2
    t0 = dhl.tours[0]
    assert t0.vehicle_id == "dhl_ct_cep_size_s_h8_v0"
    assert t0.service_ids == ["s0", "s1"]
    assert t0.event_vehicle_id("dhl") == "freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0"


def test_carrier_attrs_not_polluted_by_service_attrs():
    # regression: carrier attrs must not accidentally include service-level names
    cs = {c.carrier_id: c for c in cp.parse_carriers(FIX / "MINI.output_carriers.xml.gz")}
    assert "capacityDemand" not in cs["dhl"].attrs
```

- [ ] **Step 3: Run tests, verify they fail.**

- [ ] **Step 4: Implement `carriers_parse.py`.** Stream with `ET.iterparse`; for each element whose tag ends with `carrier`, walk its direct children by tag suffix (`attributes`→carrier attrs; `capabilities`→`vehicles`→`vehicle`; `services`→`service`; `plans`→ selected `plan`→`tour`→`act`). Default `tour_id` to the positional index of the `<tour>` within the carrier when no `tourId` attribute exists. Call `el.clear()` after each carrier to bound memory. Provide `attr_float`/`attr_int` (reuse the `int(float(...))` guard from `extract_freight._int_attr`).

- [ ] **Step 5: Run tests, verify they pass.**

- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/carriers_parse.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_carriers_parse.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/mini_lmd
git commit -m "feat(kpi): stream-parse carriers + vehicle-types XML (v2 Plan A Task 2)"
```

---

## Task 3: Provider & vehicle-type aggregation + exclusion → `kpis_provider.csv` (`extract_freight_provider.py`)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/kpi/extract_freight_provider.py`
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_freight_provider.py`

**Interfaces:**
- Consumes: `carriers_parse.parse_carriers`, `parse_vehicle_types`, `freight_classify.*`.
- Produces:
  - `prow(provider, kpi_name, value, unit, source) -> dict`
  - `parse_run(run_dir, prefix) -> ParsedFreight` where `ParsedFreight` bundles `carriers: list[CarrierDef]`, `vtypes: dict[str,VehTypeDef]`, `excluded: set[str]` (event_vehicle_ids), plus per-vehicle records `list[VehRecord]` with fields `event_vehicle_id, carrier_id, provider, vtype, parcels, cap, load_factor, excluded: bool` — **reused by Plan C's VRP/summary tables and low-util notice.**
  - `extract(run_dir, prefix) -> list[dict]` (provider rows)
  - `write(rows, meta, out_file)` → `kpis_provider.csv`
  - `LOW_UTIL_THRESHOLD = 0.05`

**Row content per provider (delivery providers; from spec §3.1):** `parcels_total`, `parcels_missed` (re-allocated), `parcels_unassigned`, `delivery_rate`, `vehicles`, `tours`, `km`, `tour_hours`, `cost_fixed`, `cost_dist`, `cost_time`, `cost_total` (re-allocated), `avg_load_factor`, `stops`, `stops_per_h`, `stops_per_km`, `parcels_per_km`, `cost_per_parcel`, `excluded_vehicles`. Per-vehicle-type rows (`provider="type:<VT>"`): `distance_km`, `vehicles`, `load_factor`, `km_per_tour`, `stops_per_tour`, `capacity`, `fixed_cost_per_day`.

- [ ] **Step 1: Write the failing tests** (numbers derived from `mini_lmd`; compute expectations by hand and hard-code):

```python
# tests/test_extract_freight_provider.py
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import extract_freight_provider as efp

FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


def _by(rows, provider, name):
    for r in rows:
        if r["provider"] == provider and r["kpi_name"] == name:
            return r["value"]
    return None


def test_low_util_exclusion_marks_1parcel_tour():
    pf = efp.parse_run(FIX, "MINI")
    # dhl_..._v1 carries 1 parcel into a cap-100 van -> lf 0.01 < 0.05 -> excluded
    excl = pf.excluded
    assert "freight_dhl_veh_dhl_ct_cep_size_s_h8_v1_1" in excl
    assert "freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0" not in excl
    # supply vehicles are never excluded even at high load (only delivery considered)
    assert "freight_amazon_supply_veh_amazon_Supply_Vehicle_v0_0" not in excl


def test_provider_cost_reallocation():
    rows = efp.extract(FIX, "MINI")
    # dhl variable cost = costDistance 200 + costTime 0 + costOvertime 0 = 200
    # ratio = nonExcluded 1 / allTours 2 = 0.5 -> var 100; fixed = surviving veh v0 = 150
    # total = dist(100) + time(0) + fixed(150) + overtime(0) = 250
    assert _by(rows, "dhl", "cost_dist") == 100.0
    assert _by(rows, "dhl", "cost_fixed") == 150.0
    assert _by(rows, "dhl", "cost_total") == 250.0
    assert _by(rows, "dhl", "excluded_vehicles") == 1


def test_provider_parcels_and_type_rows():
    rows = efp.extract(FIX, "MINI")
    assert _by(rows, "dhl", "parcels_total") == 100
    # missed re-allocated: round(10 * 1/2) = 5
    assert _by(rows, "dhl", "parcels_missed") == 5
    assert _by(rows, "hermes", "vehicles") == 1
    # per-type row present for the CEP van
    assert _by(rows, "type:VAN", "vehicles") is not None
    assert _by(rows, "type:CARGOBIKE", "distance_km") == 20.0


def test_supply_provider_present_but_not_double_counted():
    rows = efp.extract(FIX, "MINI")
    # amazon is supply-only here; it still gets provider rows (supply carrier),
    # but its vehicles are never in the excluded set (asserted above)
    assert _by(rows, "amazon", "km") == 200.0


def test_write_schema(tmp_path):
    rows = efp.extract(FIX, "MINI")
    from run_meta import load_run_meta
    # mini has no run_metadata.json/dirname pattern -> build a stub meta
    class M: run_id = "MINI"
    out = tmp_path / "kpis_provider.csv"
    efp.write(rows, M, out)
    head = out.read_text(encoding="utf-8").splitlines()[0]
    assert head == "run_id;provider;kpi_name;value;unit;source"
```

- [ ] **Step 2: Run tests, verify they fail.**

- [ ] **Step 3: Implement `extract_freight_provider.py`.** Algorithm:
  1. `parse_carriers` + `parse_vehicle_types`.
  2. Build per-vehicle records: for each carrier, for each tour → `parcels = sum(services[sid].capacity_demand if resolves else 1)`, `stops = len(service_ids)`, `cap = vtypes[vehicles[vehicleId].type_id].capacity`, `lf = 0 if parcels==0 or cap<=0 else min(1, parcels/cap)`, `vtype = classify_vehicle(type_id or vehicle_id)`, `provider = provider_of(carrier_id, attrs["provider"])`.
  3. Exclusion: for **delivery** carriers only, `excluded.add(event_vehicle_id)` when `lf < LOW_UTIL_THRESHOLD`.
  4. Per-provider aggregation: group carriers by provider. Variable-cost ratio per carrier = `nonExcludedTours/allTours` (delivery), `1.0` for supply. `cost_dist = costDistance*ratio`, `cost_time = costTime*ratio`, `cost_fixed = sum fixedCostsPerDay(type) over surviving vehicles`, `cost_total = cost_dist + cost_time + cost_fixed + costOvertime*ratio`. `parcels_missed = round(missedParcels*ratio)`. `parcels_total = numberOfParcels`. `km`/`tours`/`tour_hours` from `TimeDistance_perCarrier.tsv` grouped by `carrierId→provider`. `avg_load_factor = mean lf over surviving delivery vehicles`. `stops = sum stops over surviving vehicles`; `stops_per_h = stops / tour_hours`; `stops_per_km = stops / km`; `parcels_per_km = (parcels_total - parcels_missed)/km`; `cost_per_parcel = cost_total / max(1, parcels_total - parcels_missed)`; `delivery_rate = (parcels_total - parcels_missed - unassigned)/parcels_total`.
  5. Per-vehicle-type rows: group surviving vehicles by `vtype`; `distance_km` from `TimeDistance_perVehicle.tsv` summed by matching `vehicleTypeId`→classify; `vehicles` count; `load_factor` mean lf; `km_per_tour`/`stops_per_tour` means; `capacity`/`fixed_cost_per_day` from vtypes.
  6. `source` field: name the origin ("TimeDistance_perCarrier"/"carrier attributes (re-allocated)"/"computed").
  Guard every division by zero. `print()` an ASCII summary line (`"[provider] N providers, M excluded delivery vehicles"`).

- [ ] **Step 4: Run tests, verify they pass.**

- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/extract_freight_provider.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_freight_provider.py
git commit -m "feat(kpi): per-provider/vehicle-type KPIs + low-util re-allocation (v2 Plan A Task 3)"
```

---

## Task 4: Convergence series → `kpi_iterations.csv` (`extract_iterations.py`)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/kpi/extract_iterations.py`
- Create fixtures: `tests/fixtures/mini_lmd/MINI.drt_customer_stats_drt.csv`, `MINI.modestats.csv`, `MINI.carrier_scores.txt` (3 iteration rows each).
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_iterations.py`

**Interfaces:**
- Produces: `extract(run_dir, prefix) -> list[dict]` (rows `{series, iteration, value, unit}`), `write(rows, meta, out_file)` → `kpi_iterations.csv`.
- Series: `drt_rides`, `drt_rejection_rate`, `wait_mean`, `wait_p95` (all rows of `drt_customer_stats`); `modal_share_<mode>` (all rows of `modestats`); `carrier_score_executed/worst/avg/best` (from `carrier_scores.txt`, tab-separated, header skipped, cols `iteration executed worst avg best`) — **graceful skip if the file is absent** (married250 has none).

- [ ] **Step 1: Create fixtures.**

`tests/fixtures/mini_lmd/MINI.drt_customer_stats_drt.csv`:
```
runId;iteration;rides;rides_pax;groupSize_mean;wait_average;wait_max;wait_p95;wait_p75;wait_median;percentage_WT_below_10;percentage_WT_below_15;inVehicleTravelTime_mean;distance_m_mean;directDistance_m_mean;totalTravelTime_mean;fareAllReferences_mean;rejections;rejectionRate
MINI;0;100;100;1;900;5000;2000;1200;600;60;70;600;8000;5000;1500;-0;10;0.09
MINI;1;200;200;1;500;3000;1300;700;300;70;84;900;7800;5100;1400;-0;5;0.02
MINI;2;220;220;1;480;2800;1200;690;310;72;85;950;7700;5000;1430;-0;4;0.018
```

`tests/fixtures/mini_lmd/MINI.modestats.csv`:
```
iteration;bike;car;drt;pt;ride;walk
0;0.19;0.40;0.0;0.01;0.16;0.24
1;0.18;0.38;0.011;0.012;0.163;0.247
2;0.181;0.364;0.0196;0.0121;0.167;0.255
```

`tests/fixtures/mini_lmd/MINI.carrier_scores.txt` (tab-separated):
```
iteration	executed	worst	avg	best
0	-100.0	-200.0	-150.0	-90.0
1	-80.0	-160.0	-120.0	-70.0
2	-60.0	-140.0	-100.0	-55.0
```

- [ ] **Step 2: Write failing tests**

```python
# tests/test_extract_iterations.py
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import extract_iterations as ei

FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


def _vals(rows, series):
    return [(r["iteration"], r["value"]) for r in rows if r["series"] == series]


def test_drt_convergence_all_rows():
    rows = ei.extract(FIX, "MINI")
    assert _vals(rows, "drt_rides") == [(0, 100), (1, 200), (2, 220)]
    assert dict(_vals(rows, "wait_p95"))[2] == 1200.0


def test_modal_shares_per_mode():
    rows = ei.extract(FIX, "MINI")
    assert dict(_vals(rows, "modal_share_drt"))[1] == 0.011


def test_carrier_scores_present():
    rows = ei.extract(FIX, "MINI")
    assert dict(_vals(rows, "carrier_score_best"))[2] == -55.0


def test_carrier_scores_absent_graceful(tmp_path):
    # copy only the DRT csv, omit carrier_scores.txt -> no carrier_score_* rows, no crash
    import shutil
    shutil.copy(FIX / "MINI.drt_customer_stats_drt.csv", tmp_path / "MINI.drt_customer_stats_drt.csv")
    shutil.copy(FIX / "MINI.modestats.csv", tmp_path / "MINI.modestats.csv")
    rows = ei.extract(tmp_path, "MINI")
    assert not any(r["series"].startswith("carrier_score_") for r in rows)
    assert any(r["series"] == "drt_rides" for r in rows)
```

- [ ] **Step 3: Run tests, verify they fail.**
- [ ] **Step 4: Implement `extract_iterations.py`** — read each source with existence guards; `drt_rejection_rate` computed from int cols `rejections/(rides+rejections)` per row (matches `extract_drt` convention). `write` header `run_id;series;iteration;value;unit`, floats `"{:.6g}"`.
- [ ] **Step 5: Run tests, verify they pass.**
- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/extract_iterations.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_iterations.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/mini_lmd
git commit -m "feat(kpi): convergence-over-iterations CSV (v2 Plan A Task 4)"
```

---

## Task 5: Network-free distributions → `kpi_distributions.csv` (`distributions.py`)

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/kpi/distributions.py`
- Create fixture: `tests/fixtures/mini_lmd/MINI.output_drt_legs_drt.csv` (wait times spanning ≥2 bins).
- Test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_distributions.py`

**Interfaces:**
- Produces: `extract(run_dir, prefix, recon=None) -> list[dict]` (rows `{series, bin_lo, bin_hi, value, unit}`), `write(rows, meta, out_file)` → `kpi_distributions.csv`.
- Series (network-free subset):
  - `drt_wait` — 60 s bins of `output_drt_legs.waitTime` (bin_lo/bin_hi in seconds; value = count).
  - `drt_tour_duration` — per-vehicle `active_s/3600` from `recon["per_veh"]`, 16 equal-width bins over observed range. Only if `recon` provided.
  - `occ_time` / `occ_segments` — from `recon["fleet"]["seg_time"]`/`["seg_count"]`; one row per occupancy level (`bin_lo=level`, `bin_hi=level`, value = share of total). Only if `recon` provided.
  - `lmd_tour_distance` — 10 km bins of `TimeDistance_perVehicle.tsv` `travelDistance[km]` (bin_lo/bin_hi in km).
  - `lmd_tour_duration` — 0.5 h bins of `TimeDistance_perVehicle.tsv` `tourDuration[h]`.
- **Deferred (print an ASCII note, do not emit):** `drt_tour_distance` (per-vehicle km) and `occ_km` — Plan D.

- [ ] **Step 1: Create `MINI.output_drt_legs_drt.csv`** (only columns used: `departureTime;waitTime`):
```
departureTime;waitTime
28800;30
28810;90
28900;150
29000;45
```
(bins: [0,60)=1, [60,120)=1, [120,180)=1, plus the 45 in [0,60) → [0,60)=2)

- [ ] **Step 2: Write failing tests**

```python
# tests/test_distributions.py
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import distributions as dist

FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


def test_wait_bins_60s():
    rows = dist.extract(FIX, "MINI", recon=None)
    w = {(r["bin_lo"], r["bin_hi"]): r["value"] for r in rows if r["series"] == "drt_wait"}
    assert w[(0, 60)] == 2
    assert w[(60, 120)] == 1
    assert w[(120, 180)] == 1


def test_lmd_distance_10km_bins():
    rows = dist.extract(FIX, "MINI", recon=None)
    d = [r for r in rows if r["series"] == "lmd_tour_distance"]
    assert d, "expected lmd_tour_distance rows from TimeDistance_perVehicle.tsv"
    # 4 vehicles: 60,40,20,200 km -> bins [20,30),[40,50),[60,70),[200,210)
    binned = {(r["bin_lo"], r["bin_hi"]): r["value"] for r in d}
    assert binned.get((60, 70)) == 1
    assert binned.get((200, 210)) == 1


def test_occupancy_rows_from_recon():
    recon = {"per_veh": {"v0": {"active_s": 3600.0}},
             "fleet": {"seg_time": {0: 100.0, 1: 300.0}, "seg_count": {0: 2, 1: 6}}}
    rows = dist.extract(FIX, "MINI", recon=recon)
    ot = {r["bin_lo"]: r["value"] for r in rows if r["series"] == "occ_time"}
    assert abs(ot[1] - 0.75) < 1e-9        # 300/(100+300)
    assert not any(r["series"] == "occ_km" for r in rows)   # deferred
```

- [ ] **Step 3: Run tests, verify they fail.**
- [ ] **Step 4: Implement `distributions.py`.** Fixed-width binning helper `bin_fixed(values, width) -> {(lo,hi): count}`; equal-width 16-bin helper for durations. Guard missing files. Emit the deferred-note `print()`.
- [ ] **Step 5: Run tests, verify they pass.**
- [ ] **Step 6: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/distributions.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_distributions.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/mini_lmd/MINI.output_drt_legs_drt.csv
git commit -m "feat(kpi): network-free distributions CSV (v2 Plan A Task 5)"
```

---

## Task 6: New tabular timeseries series (`timeseries.py`)

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/timeseries.py`
- Modify test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_timeseries.py`
- Fixture: add `MINI.output_trips.csv.gz` to `mini_lmd` (or reuse `drtrun`'s trips in the test).

**Interfaces:**
- `extract(run_dir, prefix, freight_cache=None)` — **unchanged signature**; add two new tabular series:
  - `drt_requests_submitted` (unit `requests/h`) — from `output_drt_legs.submissionTime // 3600` counts (existing `drt_rides` uses `departureTime`; submitted uses `submissionTime`; if `submissionTime` column absent, skip gracefully).
  - `drt_feeder_trips` (unit `trips/h`) — from `output_trips.csv.gz`: trips whose `modes` contains both `drt` and `pt`, bucketed by `dep_time // 3600` (fall back to whichever departure-time column exists: `dep_time`).

- [ ] **Step 1: Add failing assertions to `test_timeseries.py`** for the two new series against the existing `drtrun` fixture (it already has `output_drt_legs_drt.csv` with `submissionTime` and `output_trips.csv.gz`). If the fixture legs lack `submissionTime`, add that column to the fixture in this step.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** the two series in `timeseries.extract` (guard columns/files; ASCII only).
- [ ] **Step 4: Run, verify pass** (existing timeseries tests still green).
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/timeseries.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_timeseries.py
git commit -m "feat(kpi): drt_requests_submitted + drt_feeder_trips hourly series (v2 Plan A Task 6)"
```

---

## Task 7: Share one `reconstruct()` pass (`extract_drt.py` + `build_kpis.py`)

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/extract_drt.py`
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py`
- Modify test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_drt.py`

**Rationale:** `distributions` and `extract_drt` both need `reconstruct()`. Reconstructing twice = two passes over the ~95 MB DRT cache (minutes each). Compute once in `build_kpis`, pass to both.

**Interfaces:**
- `extract_drt.extract(run_dir, prefix, fleet_file=None, drt_events_cache=None, recon=None)` — new optional `recon`. If `recon is None and drt_events_cache is not None`, call `reconstruct(...)` as today (backward compatible). If `recon` given, use it and skip the internal call.

- [ ] **Step 1: Add a failing test** asserting `extract_drt.extract(..., recon=<stub fleet dict>)` produces `service_ratio_active` from the stub **without** touching any events file (pass a non-existent `drt_events_cache` path and a valid `recon` — it must not raise).
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** the `recon` param in `extract_drt`; in `build_kpis.build`, after `ensure_caches`, compute `recon = drt_service_time.reconstruct(str(drt_cache), str(fleet)) if drt_cache else None` once (import `drt_service_time` the same way `extract_drt` does), pass `recon=recon` to `extract_drt.extract` and to `distributions.extract`.
- [ ] **Step 4: Run, verify pass** (existing `test_extract_drt` still green).
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/extract_drt.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_extract_drt.py
git commit -m "refactor(kpi): compute reconstruct() once, share with distributions (v2 Plan A Task 7)"
```

---

## Task 8: Wire new extractors into `build_kpis.py` + write the new CSVs

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py`
- Modify test: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_build_kpis.py`

**Interfaces:**
- Consumes: `extract_iterations`, `distributions`, `extract_freight_provider`.
- `build(...)` now additionally writes `kpi_iterations.csv`, `kpi_distributions.csv`, and (when `has_freight`) `kpis_provider.csv`, into `out/`.
- `--no-events`: `distributions.extract(recon=None)` still emits `drt_wait` + `lmd_*` rows (skips occupancy/duration); `kpi_iterations.csv` and `kpis_provider.csv` never need events → always written when their sources exist.

- [ ] **Step 1: Extend `test_build_kpis.py`** — using the existing `drtrun` fixture (run with `no_events=True`), assert that after `build(...)`: `kpi_iterations.csv` exists with header `run_id;series;iteration;value;unit`; `kpis_provider.csv` exists (drtrun has freight) with header `run_id;provider;kpi_name;value;unit;source`; `kpi_distributions.csv` exists with header `run_id;series;bin_lo;bin_hi;value;unit` and contains `lmd_tour_distance` rows. Assert `kpis_long.csv` header is **unchanged** (schema-frozen regression).
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** the wiring in `build`:
```python
    # after the existing timeseries.write(...) block:
    import extract_iterations, distributions
    it_rows = extract_iterations.extract(run_dir, meta.prefix)
    extract_iterations.write(it_rows, meta, out / "kpi_iterations.csv")
    dist_rows = distributions.extract(run_dir, meta.prefix, recon=recon)
    distributions.write(dist_rows, meta, out / "kpi_distributions.csv")
    if has_freight:
        import extract_freight_provider as efp
        prov_rows = efp.extract(run_dir, meta.prefix)
        efp.write(prov_rows, meta, out / "kpis_provider.csv")
    print("v2 CSVs: iterations={} distributions={} provider={}".format(
        len(it_rows), len(dist_rows), len(prov_rows) if has_freight else 0))
```
(Place BEFORE the `import render` block so the render step can later consume them; render changes are Plan C — do not modify render here.)
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/build_kpis.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_build_kpis.py
git commit -m "feat(kpi): build_kpis writes provider/iterations/distributions CSVs (v2 Plan A Task 8)"
```

---

## Task 9: Real-married250 integration smoke + schema-drift guard

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_real_married250.py`
- Create fixtures: `tests/fixtures/real_heads/` — verbatim heads: full `TimeDistance_perCarrier.tsv` (7 rows), first ~2 carriers of `output_carriers.xml.gz` (decompressed to `.xml`), full `output_carriersVehicleTypes.xml.gz` (decompressed), 5-row `drt_customer_stats_drt.csv`, 5-row `modestats.csv`.

**Purpose:** Two layers — (a) a **schema-drift guard** that runs in CI/offline against small verbatim heads; (b) an **opt-in full run** against the on-disk married250, skipped when absent (`pytest.mark.skipif`).

**Interfaces:** Consumes all Plan A extractors.

- [ ] **Step 1: Build `real_heads/` fixtures** with a one-off script that reads the on-disk married250 files and writes the small heads listed above (decompress the XML heads so the fixture needs no matching `.gz` name — or gzip them and keep the `.output_carriers.xml.gz` naming so `parse_carriers` opens them directly; prefer keeping `.gz` names).
- [ ] **Step 2: Write tests**

```python
# tests/test_real_married250.py
import os, sys
from pathlib import Path
import pytest
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import extract_freight_provider as efp
import carriers_parse as cp

HEADS = Path(__file__).parent / "fixtures" / "real_heads"
REAL = Path(r"../../hagrid-matsim-output/DRT_BASELINE_13052025_married250_iter300_jsprit1000")


def test_real_carrier_header_schema():
    # verbatim head keeps our TSV column names honest against schema drift
    tsv = (HEADS / "analysis" / "freight" / "TimeDistance_perCarrier.tsv").read_text(encoding="utf-8")
    assert tsv.splitlines()[0].split("\t")[:2] == ["carrierId", "nuOfTours"]


def test_real_heads_provider_classification():
    carriers = cp.parse_carriers(HEADS / "DRT_BASELINE_13052025_married250.output_carriers.xml.gz")
    provs = {c.attrs.get("provider") for c in carriers}
    assert "dhl" in provs


@pytest.mark.skipif(not (Path(__file__).parent.parent / REAL).exists(),
                    reason="married250 run not on disk")
def test_full_married250_provider_runs():
    run = (Path(__file__).parent.parent / REAL).resolve()
    rows = efp.extract(run, "DRT_BASELINE_13052025_married250")
    provs = {r["provider"] for r in rows if not r["provider"].startswith("type:")}
    assert {"dhl", "amazon", "hermes", "dpd", "gls", "ups", "fedex"} <= provs
```

- [ ] **Step 3: Run** `python -u -m pytest tests/test_real_married250.py -q` from `analysis/kpi/`. The skip-guarded test runs only where married250 exists.
- [ ] **Step 4: Manual end-to-end check** (evidence for verification-before-completion): from `analysis/kpi/`, run
```bash
python -u build_kpis.py --run-dir ../../hagrid-matsim-output/DRT_BASELINE_13052025_married250_iter300_jsprit1000 --no-events
```
Confirm `kpis_provider.csv`, `kpi_iterations.csv`, `kpi_distributions.csv` appear in that run's `analysis/` with correct headers and non-empty provider rows. Record the observed provider count + a spot-checked cost vs the legacy `HAGRID_Dashboard_*.html` in the commit message. (`--no-events` keeps it fast; the occupancy rows are exercised in Plan D.)
- [ ] **Step 5: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_real_married250.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/fixtures/real_heads
git commit -m "test(kpi): married250 schema-drift guard + full-run smoke (v2 Plan A Task 9)"
```

---

## Self-Review (checked against spec §3.1/§3.2 for the data-layer scope)

- **`kpis_provider.csv`** (§3.1): parcels total/missed/unassigned, delivery rate, vehicles, tours, km, tour hours, cost fixed/dist/time/total, avg load factor, stops, stops/h, stops/km, parcels/km, cost/parcel, per-vehicle-type rows → Tasks 2–3. ✔ (stem% VRP column deferred to Plan D, documented.)
- **`kpi_iterations.csv`** (§3.1): rides, rejection rate, wait mean/p95, modal shares, carrier scores w/ graceful skip → Task 4. ✔
- **`kpi_distributions.csv`** (§3.1): DRT wait 60 s bins, DRT active tour duration, LMD distance 10 km / duration 0.5 h, occupancy time/segments → Task 5. DRT tour distance + occ_km deferred to Plan D, documented + printed. ✔
- **`kpi_timeseries` new series** (§3.1): `drt_feeder_trips`, `drt_requests_submitted` → Task 6. Event-derived freight series (`freight_parcels_h_*`, `freight_active_vehicles_*`, depot dep/arr) deferred to Plan D, documented. ✔
- **`extract_freight_provider.py`** (§3.2): classification port + carrier/vtype XML aggregation + low-util exclusion + re-allocation, `Load_perVehicle` header-only fallback (mini fixture is header-only) → Tasks 1–3. ✔
- **Architecture** (§3): long-CSV schema frozen (Task 8 regression); new files only; `build_comparison` untouched. ✔
- **Constraints** (§4): ASCII prints, no legacy edits, explicit git adds, no network reads. ✔
- **TDD** (§5): every task is test-first with real-head + mini fixtures; focused on logic-heavy modules per the confirmed decision. ✔
- **Type consistency:** `event_vehicle_id(carrier_id)`, `provider_of`, `classify_vehicle`, `parse_run(...).excluded`, `recon` dict keys (`per_veh`/`fleet`/`seg_time`/`seg_count`) match across Tasks 2/3/5/7. ✔

**Not in Plan A (later plans):** Java auto-trigger (B); DRT|LMD tab rendering, tiles, charts, LMD tables & low-util notice UI (C); network geometry, maps, spike, km-distributions, event-derived freight series (D).
