# LMD Spike Findings — 2026-06-25

## Purpose
De-risk two unknowns before writing production LMD-baseline code:
1. PANDA demand shapefile reads through MATSim's `GeoFileReader` and yields sane per-provider counts.
2. Freight/jsprit API signatures are confirmed from the PR3552 jar.

---

## 1. PANDA Demand Shapefile Read

**Source file:** `~/Documents/GitHub/PANDA/output/lausitz/hagrid_parcel_demand_2025-05-13_(Tuesday).shp`  
**Staged to:** `parcel-demand-2-matsim-pipeline/hagrid-input/lausitz/demand/`  
**Test:** `SpikePandaReadTest.readsPandaDemandAndCountsProviders` — **PASS** (3.9 s)

### Feature count
1 056 features (grid cells)

### Per-provider B2B / B2C totals

| Provider | B2B   | B2C   | Total |
|----------|------:|------:|------:|
| amazon   |     4 | 1 239 | 1 243 |
| dhl      |   206 | 2 561 | 2 767 |
| dpd      |    63 |   457 |   520 |
| fedex    |   243 |    51 |   294 |
| gls      |    23 |   423 |   446 |
| hermes   |    16 |   682 |   698 |
| ups      |   305 |   108 |   413 |
| **GRAND** | **860** | **5 521** | **6 381** |

Cross-check: PANDA reported ~6 350 — actual 6 381. Sane.

### Notes on attribute mapping
- B2C per provider: attribute `<provider>_tag` (truncated to 10 chars via `safe10()`)
- B2B per provider: attribute `<provider>_type` (fallback `<provider>_typ` if null)
- `setServiceStartingTimeWindow` in the brief does NOT exist; the real method is `setServiceStartTimeWindow(TimeWindow)` — see Section 2.

---

## 2. Freight / jsprit API Signatures

**Jar used:** `~/.m2/repository/org/matsim/contrib/freight/2025.0-PR3552/freight-2025.0-PR3552.jar`

### CarriersUtils (verbatim javap output, filtered)

```
public static org.matsim.freight.carriers.Carrier createCarrier(org.matsim.api.core.v01.Id<org.matsim.freight.carriers.Carrier>);
public static void addCarrierVehicle(org.matsim.freight.carriers.Carrier, org.matsim.freight.carriers.CarrierVehicle);
public static void addService(org.matsim.freight.carriers.Carrier, org.matsim.freight.carriers.CarrierService);
public static void setJspritIterations(org.matsim.freight.carriers.Carrier, int);
public static void runJsprit(org.matsim.api.core.v01.Scenario) throws java.util.concurrent.ExecutionException, java.lang.InterruptedException;
public static void runJsprit(org.matsim.api.core.v01.Scenario, org.matsim.freight.carriers.CarriersUtils$CarrierSelectionForSolution) throws java.util.concurrent.ExecutionException, java.lang.InterruptedException;
public static void loadCarriersAccordingToFreightConfig(org.matsim.api.core.v01.Scenario);
public static void writeCarriers(org.matsim.freight.carriers.Carriers, java.lang.String);
public static org.matsim.freight.carriers.CarrierVehicle getCarrierVehicle(org.matsim.freight.carriers.Carrier, org.matsim.api.core.v01.Id<org.matsim.vehicles.Vehicle>);
public static org.matsim.freight.carriers.Carriers addOrGetCarriers(org.matsim.api.core.v01.Scenario);
public static org.matsim.freight.carriers.Carriers getCarriers(org.matsim.api.core.v01.Scenario);
public static org.matsim.freight.carriers.CarrierVehicleTypes getCarrierVehicleTypes(org.matsim.api.core.v01.Scenario);
```

### CarrierService.Builder (full class)

```
public static org.matsim.freight.carriers.CarrierService$Builder newInstance(org.matsim.api.core.v01.Id<org.matsim.freight.carriers.CarrierService>, org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link>);
public org.matsim.freight.carriers.CarrierService$Builder setName(java.lang.String);
public org.matsim.freight.carriers.CarrierService$Builder setServiceDuration(double);
public org.matsim.freight.carriers.CarrierService$Builder setServiceStartTimeWindow(org.matsim.freight.carriers.TimeWindow);
public org.matsim.freight.carriers.CarrierService build();
public org.matsim.freight.carriers.CarrierService$Builder setCapacityDemand(int);
```

**NOTE for Tasks 7-8:** The method is `setServiceStartTimeWindow(TimeWindow)`, NOT `setServiceStartingTimeWindow`. The brief had an incorrect name.

### CarrierService (getter)

```
public double getServiceDuration();
public org.matsim.freight.carriers.TimeWindow getServiceStartTimeWindow();
```

### CarrierVehicle.Builder (full class)

```
public static org.matsim.freight.carriers.CarrierVehicle$Builder newInstance(org.matsim.api.core.v01.Id<org.matsim.vehicles.Vehicle>, org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link>, org.matsim.vehicles.VehicleType);
public org.matsim.freight.carriers.CarrierVehicle$Builder setEarliestStart(double);
public org.matsim.freight.carriers.CarrierVehicle$Builder setLatestEnd(double);
public org.matsim.freight.carriers.CarrierVehicle build();
```

---

## Summary for downstream tasks

| What Tasks 7-8 will call | Exact signature |
|--------------------------|----------------|
| Run jsprit optimisation  | `CarriersUtils.runJsprit(Scenario)` |
| Attach carriers to scenario | `CarriersUtils.addOrGetCarriers(Scenario)` |
| Get carriers from scenario | `CarriersUtils.getCarriers(Scenario)` |
| Get vehicle types from scenario | `CarriersUtils.getCarrierVehicleTypes(Scenario)` |
| Set jsprit iterations | `CarriersUtils.setJspritIterations(Carrier, int)` |
| Create carrier | `CarriersUtils.createCarrier(Id<Carrier>)` |
| Add service to carrier | `CarriersUtils.addService(Carrier, CarrierService)` |
| Add vehicle to carrier | `CarriersUtils.addCarrierVehicle(Carrier, CarrierVehicle)` |
| Build service | `CarrierService.Builder.newInstance(Id<CarrierService>, Id<Link>)` |
| Service time window | `CarrierService.Builder.setServiceStartTimeWindow(TimeWindow)` |
| Service capacity | `CarrierService.Builder.setCapacityDemand(int)` |
| Build vehicle | `CarrierVehicle.Builder.newInstance(Id<Vehicle>, Id<Link>, VehicleType)` |
