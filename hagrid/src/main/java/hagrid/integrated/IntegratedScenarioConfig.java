package hagrid.integrated;

import java.util.List;
import java.util.OptionalDouble;

/**
 * The autonomy "operation mode" switch (design spec section 4.4) for the integrated DRT-freight
 * scenarios. Its four coupled effects — labour cost, delivery dwell factor, max-speed cap,
 * motorway exclusion — are exposed via the {@code effective*} helpers so callers never branch on
 * the mode themselves. Defaults are grounded in spec sections 4.4 / 6.1 / 6.3, which remain the
 * authoritative source for the reasoning behind each value.
 *
 * <p><b>NOT WIRED — reads as configuration but reaches no run.</b> Nothing outside this class's own
 * test references it. The autonomy switch is deliberately deferred (user decision 2026-07-30, see
 * docs/BACKLOG.md); the autonomy follow-up plan is this class's designated integration point. Do
 * not read a run's numbers as if any value here applied — {@code RunMetadataWriter} hardcodes
 * {@code operation_mode = "conventional"}.</p>
 *
 * <p><b>Scope is the autonomy switch only (thinned 2026-07-30).</b> Seven concept parameters that
 * used to sit here were a second, unmaintained source for values that already live elsewhere and
 * were removed to stop them diverging. Do not re-add them — the established path (design D8, applied
 * by both 1c and 1d) is {@code HAGRIDSimulationConfig} for CLI-tunable parameters plus a per-scenario
 * constants class for fixed ones:</p>
 * <ul>
 *   <li>{@code retoolingTimeSeconds}, {@code freightLookAheadSeconds}, {@code idleThreshold}
 *       → {@code Modular.RETOOLING_S} / {@code FREIGHT_LOOKAHEAD_S} / {@code DEFAULT_IDLE_THRESHOLD}</li>
 *   <li>{@code idleThreshold}, {@code fleetSize} → {@code HAGRIDSimulationConfig} (+ CLI)</li>
 *   <li>{@code vehicleTimeCostPerHour} → {@code analysis/kpi/economics.py}</li>
 *   <li>{@code depotCount} → not a count at all; {@code DepotNetwork} takes the depot list itself</li>
 *   <li>{@code b2cLockerShare} → structurally 0: {@code ParcelAgentGenerator} passes an empty locker
 *       list, lockers are Phase 2 (METHODS-LOG section 2.10)</li>
 * </ul>
 *
 * <p>{@link #getCargoLabourCostPerHour()} is the one remaining value mirrored elsewhere
 * ({@code economics.py} {@code LABOUR_EUR_PER_H}); it stays because
 * {@link #effectiveLabourCostPerHour()} is one of the four section-4.4 effects. Wiring the switch
 * means reconciling the two — the cost function is being rebuilt anyway (METHODS-LOG section 2.6).</p>
 */
public final class IntegratedScenarioConfig {

    /** Conventional = human aboard (driver/attendant); Autonomous = driverless (spec section 4.4). */
    public enum OperationMode { CONVENTIONAL, AUTONOMOUS }

    private final OperationMode operationMode;
    private final double cargoLabourCostPerHour;   // EUR/h, applied when CONVENTIONAL
    private final double deliveryDwellFactorAutonomous; // >1.0, robot is slower at the door
    private final double autonomousMaxSpeedKmh;     // vehicle maximumVelocity cap when AUTONOMOUS
    private final List<String> excludedRoadTypes;   // road classes barred when AUTONOMOUS

    private IntegratedScenarioConfig(Builder b) {
        this.operationMode = b.operationMode;
        this.cargoLabourCostPerHour = b.cargoLabourCostPerHour;
        this.deliveryDwellFactorAutonomous = b.deliveryDwellFactorAutonomous;
        this.autonomousMaxSpeedKmh = b.autonomousMaxSpeedKmh;
        this.excludedRoadTypes = List.copyOf(b.excludedRoadTypes);
    }

    // --- raw getters ---
    public OperationMode getOperationMode() { return operationMode; }
    public double getCargoLabourCostPerHour() { return cargoLabourCostPerHour; }
    public double getDeliveryDwellFactorAutonomous() { return deliveryDwellFactorAutonomous; }
    public double getAutonomousMaxSpeedKmh() { return autonomousMaxSpeedKmh; }
    public List<String> getExcludedRoadTypes() { return excludedRoadTypes; }

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
        private double deliveryDwellFactorAutonomous = 1.5; // provisional; calibration lever
        private double autonomousMaxSpeedKmh = 30.0;     // U-Shift floor; sensitivity to 50
        private List<String> excludedRoadTypes = List.of("motorway", "motorway_link");

        public Builder operationMode(OperationMode v) { this.operationMode = v; return this; }
        public Builder cargoLabourCostPerHour(double v) { this.cargoLabourCostPerHour = v; return this; }
        public Builder deliveryDwellFactorAutonomous(double v) { this.deliveryDwellFactorAutonomous = v; return this; }
        public Builder autonomousMaxSpeedKmh(double v) { this.autonomousMaxSpeedKmh = v; return this; }
        public Builder excludedRoadTypes(List<String> v) { this.excludedRoadTypes = List.copyOf(v); return this; }

        public IntegratedScenarioConfig build() {
            require(cargoLabourCostPerHour >= 0.0, "cargoLabourCostPerHour must be >= 0");
            require(deliveryDwellFactorAutonomous >= 1.0, "deliveryDwellFactorAutonomous must be >= 1.0");
            require(autonomousMaxSpeedKmh > 0.0, "autonomousMaxSpeedKmh must be > 0");
            return new IntegratedScenarioConfig(this);
        }

        private static void require(boolean ok, String msg) {
            if (!ok) throw new IllegalArgumentException(msg);
        }
    }
}
