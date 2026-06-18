package hagrid.integrated;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Parameters for the integrated DRT-freight scenarios (Shared-Use, Modular).
 *
 * <p>Defaults are taken from the design spec
 * (docs/superpowers/specs/2026-06-17-lausitz-drt-freight-integration-design.md). Many are
 * calibration levers. The autonomy "operation mode" (spec section 4.4) is an orthogonal switch
 * whose four coupled effects — labour cost, delivery dwell factor, max-speed cap, motorway
 * exclusion — are exposed via the {@code effective*} helpers so callers never branch on the mode.</p>
 */
public final class IntegratedScenarioConfig {

    /** Conventional = human aboard (driver/attendant); Autonomous = driverless (spec section 4.4). */
    public enum OperationMode { CONVENTIONAL, AUTONOMOUS }

    private final OperationMode operationMode;
    private final double cargoLabourCostPerHour;   // EUR/h, applied when CONVENTIONAL
    private final double vehicleTimeCostPerHour;    // EUR/h, technical (energy/capital/maintenance)
    private final double deliveryDwellFactorAutonomous; // >1.0, robot is slower at the door
    private final double autonomousMaxSpeedKmh;     // vehicle maximumVelocity cap when AUTONOMOUS
    private final List<String> excludedRoadTypes;   // road classes barred when AUTONOMOUS
    private final double retoolingTimeSeconds;      // Modular capsule swap (pure swap only)
    private final double freightLookAheadSeconds;   // Modular submission look-ahead base
    private final double idleThreshold;             // Modular passenger-first dispatch gate [0,1]
    private final int depotCount;                   // parameterised depots (pickup / swap)
    private final double b2cLockerShare;            // Shared-Use B2C share routed to Packstation [0,1]
    private final int fleetSize;                    // calibration lever

    private IntegratedScenarioConfig(Builder b) {
        this.operationMode = b.operationMode;
        this.cargoLabourCostPerHour = b.cargoLabourCostPerHour;
        this.vehicleTimeCostPerHour = b.vehicleTimeCostPerHour;
        this.deliveryDwellFactorAutonomous = b.deliveryDwellFactorAutonomous;
        this.autonomousMaxSpeedKmh = b.autonomousMaxSpeedKmh;
        this.excludedRoadTypes = List.copyOf(b.excludedRoadTypes);
        this.retoolingTimeSeconds = b.retoolingTimeSeconds;
        this.freightLookAheadSeconds = b.freightLookAheadSeconds;
        this.idleThreshold = b.idleThreshold;
        this.depotCount = b.depotCount;
        this.b2cLockerShare = b.b2cLockerShare;
        this.fleetSize = b.fleetSize;
    }

    // --- raw getters ---
    public OperationMode getOperationMode() { return operationMode; }
    public double getCargoLabourCostPerHour() { return cargoLabourCostPerHour; }
    public double getVehicleTimeCostPerHour() { return vehicleTimeCostPerHour; }
    public double getDeliveryDwellFactorAutonomous() { return deliveryDwellFactorAutonomous; }
    public double getAutonomousMaxSpeedKmh() { return autonomousMaxSpeedKmh; }
    public List<String> getExcludedRoadTypes() { return excludedRoadTypes; }
    public double getRetoolingTimeSeconds() { return retoolingTimeSeconds; }
    public double getFreightLookAheadSeconds() { return freightLookAheadSeconds; }
    public double getIdleThreshold() { return idleThreshold; }
    public int getDepotCount() { return depotCount; }
    public double getB2cLockerShare() { return b2cLockerShare; }
    public int getFleetSize() { return fleetSize; }

    // --- operation-mode helpers (spec section 4.4) ---
    public boolean isAutonomous() { return operationMode == OperationMode.AUTONOMOUS; }

    /** Delivery labour EUR/h: zero when autonomous, else the configured rate. */
    public double effectiveLabourCostPerHour() {
        return isAutonomous() ? 0.0 : cargoLabourCostPerHour;
    }

    /** Per-stop dwell multiplier: stretched (robot) when autonomous, else 1.0. */
    public double effectiveDeliveryDwellFactor() {
        return isAutonomous() ? deliveryDwellFactorAutonomous : 1.0;
    }

    /** Vehicle max speed cap in m/s when autonomous; empty = follow network/road limits. */
    public OptionalDouble effectiveMaxSpeedMps() {
        return isAutonomous() ? OptionalDouble.of(autonomousMaxSpeedKmh / 3.6) : OptionalDouble.empty();
    }

    /** Road classes barred from routing when autonomous; empty when conventional. */
    public List<String> effectiveExcludedRoadTypes() {
        return isAutonomous() ? excludedRoadTypes : List.of();
    }

    public static Builder builder() { return new Builder(); }

    /** Mutable builder with spec-grounded defaults and validation on build(). */
    public static final class Builder {
        private OperationMode operationMode = OperationMode.CONVENTIONAL;
        private double cargoLabourCostPerHour = 20.0;   // ~80% of 25 EUR/h gross (Rudolph anchor)
        private double vehicleTimeCostPerHour = 5.0;     // ~20% technical
        private double deliveryDwellFactorAutonomous = 1.5; // provisional; calibration lever
        private double autonomousMaxSpeedKmh = 30.0;     // U-Shift floor; sensitivity to 50
        private List<String> excludedRoadTypes = List.of("motorway", "motorway_link");
        private double retoolingTimeSeconds = 420.0;     // 7 min pure swap
        private double freightLookAheadSeconds = 420.0;  // base; effective = approach + swap
        private double idleThreshold = 0.50;             // Paper 1 starting point
        private int depotCount = 3;                       // 2-3 parameterised depots
        private double b2cLockerShare = 0.7;             // provisional; calibration lever
        private int fleetSize = 50;                       // calibration lever (P95 <= 7 min)

        public Builder operationMode(OperationMode v) { this.operationMode = v; return this; }
        public Builder cargoLabourCostPerHour(double v) { this.cargoLabourCostPerHour = v; return this; }
        public Builder vehicleTimeCostPerHour(double v) { this.vehicleTimeCostPerHour = v; return this; }
        public Builder deliveryDwellFactorAutonomous(double v) { this.deliveryDwellFactorAutonomous = v; return this; }
        public Builder autonomousMaxSpeedKmh(double v) { this.autonomousMaxSpeedKmh = v; return this; }
        public Builder excludedRoadTypes(List<String> v) { this.excludedRoadTypes = v; return this; }
        public Builder retoolingTimeSeconds(double v) { this.retoolingTimeSeconds = v; return this; }
        public Builder freightLookAheadSeconds(double v) { this.freightLookAheadSeconds = v; return this; }
        public Builder idleThreshold(double v) { this.idleThreshold = v; return this; }
        public Builder depotCount(int v) { this.depotCount = v; return this; }
        public Builder b2cLockerShare(double v) { this.b2cLockerShare = v; return this; }
        public Builder fleetSize(int v) { this.fleetSize = v; return this; }

        public IntegratedScenarioConfig build() {
            require(idleThreshold >= 0.0 && idleThreshold <= 1.0, "idleThreshold must be in [0,1]");
            require(b2cLockerShare >= 0.0 && b2cLockerShare <= 1.0, "b2cLockerShare must be in [0,1]");
            require(depotCount >= 1, "depotCount must be >= 1");
            require(fleetSize >= 1, "fleetSize must be >= 1");
            require(retoolingTimeSeconds >= 0.0, "retoolingTimeSeconds must be >= 0");
            require(deliveryDwellFactorAutonomous >= 1.0, "deliveryDwellFactorAutonomous must be >= 1.0");
            require(autonomousMaxSpeedKmh > 0.0, "autonomousMaxSpeedKmh must be > 0");
            return new IntegratedScenarioConfig(this);
        }

        private static void require(boolean ok, String msg) {
            if (!ok) throw new IllegalArgumentException(msg);
        }
    }
}
