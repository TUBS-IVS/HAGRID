# HAGRID Pipeline Configuration Options

This document describes all available configuration options for the HAGRID demand pipeline.

## Quick Start

```java
ScenarioConfig.builder()
    .concepts("basecase")
    .dates(LocalDate.of(2025, 5, 13))
    .filterRegions("Hannover")
    .vehicleSizes("m", "l")
    .vehicleSchedule(VehicleSchedule.SIMPLE_STAGGERED)
    .deliveryWindow(7, 14)
    .build();
```

---

## Scenario Selection

### `.concepts(String...)`
Scenario concepts to simulate.

| Value | Description |
|-------|-------------|
| `"basecase"` | Standard delivery scenario |
| `"batchHigh"` | High batching strategy |
| `"batchMedium"` | Medium batching strategy |
| `"batchModerate"` | Moderate batching strategy |

### `.dates(LocalDate...)`
Simulation dates. Multiple dates will run as separate scenarios.

### `.filterRegions(String)`
Geographic region filter (e.g., `"Hannover"`).

---

## Vehicle Configuration

### `.vehicleSizes(String...)`
Default vehicle sizes for all providers.

| Format | Example | Type-ID | Capacity | Template |
|--------|---------|---------|----------|----------|
| Alias | `"m"` | ct_cep_size_m | 165 | Original XML |
| Alias | `"l"` | ct_cep_size_l | 230 | Original XML |
| Alias | `"bike"` | ct_cep_bike | 23 | Original XML |
| capacity_type | `"60_m"` | ct_cep_60_m | 60 | Medium van |
| capacity_type | `"100_l"` | ct_cep_100_l | 100 | Large van |
| capacity_type | `"50_bike"` | ct_cep_50_bike | 50 | Cargo bike |
| Numeric only | `"80"` | ct_cep_80_m | 80 | Auto (m≤165, l>165) |

**Default:** `"m", "l"`

### `.providerVehicleSizes(String provider, String... sizes)`
Override vehicle sizes for a specific provider.

```java
.providerVehicleSizes("amazon", "l")
.providerVehicleSizes("dhl", "60_m", "l")
```

---

## Vehicle Dispatch Schedule

### `.vehicleSchedule(VehicleSchedule)`
When vehicles are dispatched (start times for routing).

| Preset | Dispatch Hours | Description |
|--------|----------------|-------------|
| `SIMPLE_STAGGERED` | 07:00, 14:00 | **Default** - Morning + afternoon |
| `EXTENDED` | 07:00, 11:00, 14:00 | Three shifts |
| `FULL_WINDOW` | Every hour in window | e.g., 7,8,9,10,11,12,13,14 |
| `EARLY_ONLY` | 1h before window | Single early dispatch |

### `.providerSchedule(String provider, VehicleSchedule)`
Override schedule for a specific provider.

```java
.providerSchedule("amazon", VehicleSchedule.FULL_WINDOW)
```

### `.providerDispatchHours(String provider, Integer... hours)`
Set completely custom dispatch hours (overrides schedule preset).

```java
.providerDispatchHours("dhl", 6, 10, 14, 18)
```

### `.providerTimeShift(String provider, int hours)`
Shift all dispatch hours for a provider. Positive = later, negative = earlier.

```java
.providerTimeShift("amazon", +1)  // Amazon starts 1h later
```

---

## Delivery Windows

### `.deliveryWindow(int startHour, int endHour)`
Default delivery window for all providers.

```java
.deliveryWindow(7, 14)  // 07:00 - 14:00
```

### `.deliveryWindow(String provider, int startHour, int endHour)`
Override delivery window for a specific provider.

```java
.deliveryWindow("amazon", 9, 17)
```

---

## Pipeline Options

### `.applyServiceSimplifier(boolean)`
Merge carrier services after generation.
**Default:** `false`

### `.runRouting(boolean)`
Run jsprit routing after demand generation.
**Default:** `false`

### `.enableCaching(boolean)`
Enable routing cache for faster repeated runs.
**Default:** `true`

---

## Complete Example

```java
ScenarioConfig.builder()
    // Scenarios
    .concepts("basecase", "batchHigh")
    .dates(LocalDate.of(2025, 5, 13), LocalDate.of(2025, 5, 14))
    .filterRegions("Hannover")
    
    // Vehicles: Custom 60-capacity based on medium van
    .vehicleSizes("60_m")
    .providerVehicleSizes("amazon", "l")
    
    // Dispatch: 7 and 14 by default, Amazon every hour
    .vehicleSchedule(VehicleSchedule.SIMPLE_STAGGERED)
    .providerSchedule("amazon", VehicleSchedule.FULL_WINDOW)
    .providerTimeShift("amazon", +1)
    
    // DHL with custom hours
    .providerDispatchHours("dhl", 6, 10, 14, 18)
    
    // Delivery windows
    .deliveryWindow(7, 14)
    .deliveryWindow("amazon", 9, 17)
    
    // Pipeline
    .applyServiceSimplifier(false)
    .runRouting(true)
    .enableCaching(true)
    
    .build();
```

---

## See Also

- `ScenarioConfig.java` - Configuration class with builder
- `VehicleSchedule` enum - Dispatch schedule presets
- `HAGRID_vehicleTypes2.0.xml` - Vehicle type definitions
