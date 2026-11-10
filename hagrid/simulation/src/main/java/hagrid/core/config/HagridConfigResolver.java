package hagrid.core.config;

import hagrid.core.HagridConfig;
import hagrid.core.util.StudyArea;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static hagrid.core.config.ResolvedHagridConfig.Source;

/** Defaults -> explicit scenario profile -> explicit run values -> explicit CLI values. */
public final class HagridConfigResolver {
    private HagridConfigResolver() {}

    /** Sparse canonical input, never a second fully defaulted HagridConfig. */
    public record Overrides(Map<String, String> values, Optional<StudyArea> requiredStudyArea) {
        public Overrides {
            values = Map.copyOf(values);
            requiredStudyArea = Objects.requireNonNull(requiredStudyArea);
        }
        public static Overrides empty() { return of(Map.of()); }
        public static Overrides of(Map<String, String> values) { return new Overrides(values, Optional.empty()); }
    }

    private static final String ROUTE = "tourRules.maxRouteDurationSeconds";
    private static final String BIKE = "fleet.cargoBikeMaxSpeedKmh";
    private static final String WINDOW_START = "tourRules.deliveryWindowStartSeconds";
    private static final String WINDOW_END = "tourRules.deliveryWindowEndSeconds";
    private static final Set<String> POSITIVE_NUMBERS = Set.of(ROUTE, BIKE,
            "tourRules.maxDriverTimeSeconds", "tourRules.durationPerParcelSeconds",
            "tourRules.maxDurationPerStopSeconds", "tourRules.vehicleDayEndSeconds");
    private static final Set<String> NONNEGATIVE_NUMBERS = Set.of(WINDOW_START, WINDOW_END,
            "tourRules.vehicleAvailabilityBufferSeconds", "tourRules.uTurnPenaltyCost",
            "routing.zoneBasedCachingThresholdMeters", "routing.odTimeQuantizationSeconds",
            "scoring.activityCostPerSecond", "scoring.timeWindowPenaltyPerSecond", "scoring.overtimePenalty");
    private static final Set<String> POSITIVE_INTEGERS = Set.of("demand.demandBorder", "demand.dhlBorder",
            "demand.carrierMergeThreshold", "optimization.replanningIterations", "optimization.offlineIterations",
            "optimization.carriersPerGroup", "optimization.adaptiveMinimumIterations",
            "optimization.adaptiveMaximumIterations", "optimization.adaptivePlateauIterations", "results.eventsWriteInterval", "results.plansWriteInterval", "results.freightHistogramWriteInterval", "resources.internalSolverThreads", "resources.maxActiveSolvers", "routing.maximumCacheEntries", "routing.maximumConcurrentRouters");
    private static final Set<String> BOOLEANS = Set.of("routing.isUsingZones",
            "routing.zoneBasedCachingEnabled", "results.carrierPlanCacheEnabled", "routing.transportCacheEnabled", "optimization.warmStartEnabled", "optimization.adaptiveSearchEnabled");
    private static final String START_ITERATION = "optimization.replanningStartIteration";

    public static Overrides hannoverTarget() { return hannover(RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS); }
    public static Overrides hannoverTarget(HagridConfig defaults) {
        return hannover(defaults.routing().getSolverMaxRouteDurationSeconds());
    }
    /** Reproduces the legacy solver cap; legacy scoring differences are a separate consumer contract. */
    public static Overrides hannoverLegacySolver() { return hannover(RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS); }
    private static Overrides hannover(int duration) {
        return new Overrides(Map.of(ROUTE, Integer.toString(duration), START_ITERATION, Integer.toString(RoutingDefaults.REPLANNING_START_ITERATION),
                "optimization.carriersPerGroup", Integer.toString(RoutingDefaults.CARRIERS_PER_REPLANNING_GROUP)), Optional.of(StudyArea.HANNOVER));
    }
    public static Overrides lausitz(double explicitDurationSeconds) {
        validate(ROUTE, Double.toString(explicitDurationSeconds));
        return new Overrides(Map.of(ROUTE, Double.toString(explicitDurationSeconds)),
                Optional.of(StudyArea.LAUSITZ_HOYERSWERDA));
    }

    public static ResolvedHagridConfig resolve(HagridConfig defaults, Overrides profile,
                                               Overrides run, Map<String, String> cli) {
        return resolve(defaults, Overrides.empty(), profile, run, cli);
    }

    static ResolvedHagridConfig resolve(HagridConfig defaults, Overrides launchDefaults, Overrides profile,
                                        Overrides run, Map<String, String> cli) {
        Objects.requireNonNull(defaults, "defaults");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(run, "run");
        Map<String, String> values = new LinkedHashMap<>();
        var rules = defaults.routing();
        values.put(ROUTE, Integer.toString(rules.getSolverMaxRouteDurationSeconds()));
        values.put(BIKE, Double.toString(defaults.vehicles().getCargoBikeMaxSpeedKmh()));
        values.put("tourRules.maxDriverTimeSeconds", Double.toString(rules.getMaxDriverTimeMinutes() * 60));
        values.put("tourRules.durationPerParcelSeconds", Double.toString(rules.getDurationPerParcelMinutes() * 60.0));
        values.put("tourRules.maxDurationPerStopSeconds", Double.toString(rules.getMaxDurationPerStopMinutes() * 60.0));
        values.put(WINDOW_START, Double.toString(rules.getDeliveryTimeWindow().getStart()));
        values.put(WINDOW_END, Double.toString(rules.getDeliveryTimeWindow().getEnd()));
        values.put("tourRules.vehicleAvailabilityBufferSeconds", Integer.toString(rules.getVehicleAvailabilityBufferSeconds()));
        values.put("tourRules.vehicleDayEndSeconds", Double.toString(rules.getVehicleDayEndSeconds()));
        values.put("tourRules.uTurnPenaltyCost", Double.toString(rules.getUTurnPenaltyCost()));
        values.put("demand.demandBorder", Integer.toString(rules.getDemandBorder()));
        values.put("demand.dhlBorder", Integer.toString(rules.getDhlBorder()));
        values.put("demand.carrierMergeThreshold", Integer.toString(rules.getCarrierMergeThreshold()));
        values.put("optimization.initialIterations", Integer.toString(rules.getJspritIterations()));
        values.put("resources.internalSolverThreads", Integer.toString(defaults.resources().getInternalSolverThreads()));
        if (defaults.resources().getMaxActiveSolvers() != null)
            values.put("resources.maxActiveSolvers", defaults.resources().getMaxActiveSolvers().toString());
        values.put("routing.transportCacheEnabled", Boolean.toString(rules.isTransportCacheEnabled()));
        values.put("routing.maximumCacheEntries", Integer.toString(rules.getMaximumCacheEntries()));
        values.put("routing.maximumConcurrentRouters", Integer.toString(rules.getMaximumConcurrentRouters()));
        values.put("routing.odTimeQuantizationSeconds", Double.toString(rules.getOdTimeQuantizationSeconds()));
        values.put("results.carrierPlanCacheEnabled", Boolean.toString(defaults.isCarrierRoutingCacheEnabled()));
        values.put("results.freightHistogramWriteInterval", "1");

        values.putAll(HagridDemandSettings.defaultsValues(defaults));
        values.putAll(DrtSettings.defaultsValues());
        values.putAll(VehicleDispatchSettings.defaultsValues());
        values.putAll(SharedUseSettings.defaultsValues());
        values.putAll(ModularSettings.defaultsValues());
        values.putAll(LmdPlanningSettings.defaultsValues());
        values.put("scoring.activityCostPerSecond", "0.008");
        values.put("scoring.timeWindowPenaltyPerSecond", "5.0");
        values.put("scoring.overtimePenalty", "5.0");
        values.putAll(RandomSeedSettings.defaultsValues());
        values.putAll(defaults.getAdditionalConfigurationValues());
        Map<String, Source> sources = new LinkedHashMap<>();
        values.forEach((key, value) -> { validate(key, value); sources.put(key, Source.DEFAULTS); });
        apply(values, sources, launchDefaults, Source.DEFAULTS, defaults.getStudyArea());
        apply(values, sources, profile, Source.PROFILE, defaults.getStudyArea());
        apply(values, sources, run, Source.RUN, defaults.getStudyArea());
        apply(values, sources, Overrides.of(cli), Source.CLI, defaults.getStudyArea());
        if (number(values, WINDOW_START) >= number(values, WINDOW_END)) {
            throw new IllegalArgumentException("tourRules.deliveryWindow: start must be before end");
        }
        if (values.containsKey("optimization.offlineIterations") && values.containsKey("optimization.replanningIterations")) {
            throw new IllegalArgumentException("optimization.offlineIterations and optimization.replanningIterations are exclusive");
        }
        // Legacy seed names are output projections of the one master seed, not independent settings.
        for (String alias : java.util.List.of("lmd.missedDeliverySeed", "lmd.tourRetimeSeed", "sharedUse.parcelSeed")) {
            values.put(alias, values.get("run.seed"));
            sources.put(alias, sources.get("run.seed"));
        }
        SectionSettings.from(values);
        DrtSettings.from(values);
        VehicleDispatchSettings.from(values);
        var demandProjection=new HagridConfig();
        Map<String,String> projectedValues=new LinkedHashMap<>();
        HagridDemandSettings.defaultsValues(defaults).keySet().forEach(key->projectedValues.put(key,values.get(key)));
        values.forEach((key,value)->{if(key.startsWith("providers.deliveryRates.")) projectedValues.put(key,value);});
        HagridDemandSettings.apply(demandProjection,projectedValues,java.nio.file.Path.of("."));
        double speed = number(values, BIKE) / 3.6;
        if (speed <= 0) {
            throw new IllegalArgumentException(BIKE + ": speed is too small to represent in m/s");
        }
        return new ResolvedHagridConfig(
                new ResolvedHagridConfig.Run(Optional.ofNullable(defaults.getSimulationDate()),
                        Optional.ofNullable(defaults.getRunId()), defaults.getTag()),
                new ResolvedHagridConfig.Demand(integer(values, "demand.demandBorder"),
                        integer(values, "demand.dhlBorder"), integer(values, "demand.carrierMergeThreshold"),
                        demandProjection.providers().getShapefileProviders(), demandProjection.providers().getAllDeliveryRates()),
                new ResolvedHagridConfig.Fleet(values.get("paths.vehicleTypes"),
                        demandProjection.vehicles().getActiveSizes(), speed),
                new ResolvedHagridConfig.TourRules(number(values, ROUTE),
                        number(values, "tourRules.maxDriverTimeSeconds"), number(values, "tourRules.durationPerParcelSeconds"),
                        number(values, "tourRules.maxDurationPerStopSeconds"), number(values, WINDOW_START),
                        number(values, WINDOW_END), number(values, "tourRules.vehicleAvailabilityBufferSeconds"),
                        number(values, "tourRules.uTurnPenaltyCost"), number(values,"tourRules.vehicleDayEndSeconds")),
                new ResolvedHagridConfig.Routing(values.get("paths.network"),
                        Optional.ofNullable(values.get("routing.isUsingZones")).map(Boolean::valueOf),
                        false, 0,
                        Boolean.parseBoolean(values.get("routing.transportCacheEnabled")), integer(values, "routing.maximumCacheEntries"),
                        integer(values, "routing.maximumConcurrentRouters"), number(values, "routing.odTimeQuantizationSeconds")),
                new ResolvedHagridConfig.Optimization(integer(values, "optimization.initialIterations"),
                        optionalInteger(values, "optimization.offlineIterations"),
                        optionalInteger(values, "optimization.replanningIterations"),
                        optionalInteger(values, START_ITERATION), optionalInteger(values, "optimization.carriersPerGroup")),
                new ResolvedHagridConfig.Resources(integer(values, "resources.internalSolverThreads"),
                        optionalInteger(values, "resources.maxActiveSolvers")),
                new ResolvedHagridConfig.Results(Boolean.parseBoolean(values.get("results.carrierPlanCacheEnabled")),
                        values.containsKey("results.carrierPlanCacheDirectory") ? Optional.of(values.get("results.carrierPlanCacheDirectory"))
                                : defaults.getRunId() == null ? Optional.empty() : Optional.of(defaults.getCarrierRoutingCacheDir()),
                        optionalInteger(values, "results.eventsWriteInterval"), optionalInteger(values, "results.plansWriteInterval"),
                        integer(values, "results.freightHistogramWriteInterval")),
                new ResolvedHagridConfig.Scenario(defaults.getStudyArea(), defaults.getScenario()), sources, values);
    }

    private static void apply(Map<String, String> values, Map<String, Source> sources,
                              Overrides overrides, Source source, StudyArea area) {
        if (overrides.requiredStudyArea().isPresent() && overrides.requiredStudyArea().get() != area) {
            throw new IllegalArgumentException("Profile requires study area " + overrides.requiredStudyArea().get()
                    + ", got " + area);
        }
        Map<String,String> canonicalValues = new LinkedHashMap<>();
        overrides.values().forEach((inputKey, value) -> {
            String canonical = HagridConfigurationCatalog.canonicalName(inputKey);
            String previous = canonicalValues.putIfAbsent(canonical, value);
            if (previous != null && !previous.equals(value))
                throw new IllegalArgumentException("Conflicting aliases for " + canonical);
        });
        canonicalValues.forEach((key, value) -> {
            validate(key, value);
            values.put(key, value.trim());
            sources.put(key, source);
        });
    }

    public static boolean isCanonicalKey(String key) {
        return SectionSettings.contains(key)||POSITIVE_NUMBERS.contains(key)||NONNEGATIVE_NUMBERS.contains(key)||POSITIVE_INTEGERS.contains(key)
                ||RandomSeedSettings.contains(key)||BOOLEANS.contains(key)||HagridDemandSettings.contains(key)||START_ITERATION.equals(key)||DrtSettings.contains(key)||HagridPathSettings.contains(key)||VehicleDispatchSettings.contains(key)||SharedUseSettings.contains(key)||ModularSettings.contains(key)||LmdPlanningSettings.contains(key);
    }
    public static void validateCanonicalValue(String key,String value) { validate(key,value); }
    private static void validate(String key, String value) {
        try {
            if (Set.of("routing.zoneBasedCachingEnabled", "optimization.warmStartEnabled", "optimization.adaptiveSearchEnabled").contains(key))
                ResolvedHagridConfig.rejectRemoved(key, "true".equals(value.trim()));
            if(SectionSettings.contains(key)) { SectionSettings.validate(key,value); }
            else if(RandomSeedSettings.contains(key)) { RandomSeedSettings.validate(key,value); }
            else if(HagridPathSettings.contains(key)) { HagridPathSettings.resolve(value,java.nio.file.Path.of(".")); }
            else if(VehicleDispatchSettings.contains(key)) { VehicleDispatchSettings.validate(key,value); }
            else if(SharedUseSettings.contains(key)) { SharedUseSettings.validate(key,value); }
            else if(ModularSettings.contains(key)) { ModularSettings.validate(key,value); }
            else if(LmdPlanningSettings.contains(key)) { LmdPlanningSettings.validate(key,value); }
            else if(DrtSettings.contains(key)) { DrtSettings.validate(key,value); }
            else if (POSITIVE_NUMBERS.contains(key) || NONNEGATIVE_NUMBERS.contains(key)) {
                double parsed = Double.parseDouble(value.trim());
                if (!Double.isFinite(parsed) || parsed < 0 || (POSITIVE_NUMBERS.contains(key) && parsed == 0)) {
                    throw new IllegalArgumentException("requires a finite "
                            + (POSITIVE_NUMBERS.contains(key) ? "positive" : "nonnegative") + " value");
                }
            } else if (POSITIVE_INTEGERS.contains(key) || START_ITERATION.equals(key)) {
                int parsed = Integer.parseInt(value.trim());
                if (parsed < 0 || (POSITIVE_INTEGERS.contains(key) && parsed == 0)) {
                    throw new IllegalArgumentException("integer outside allowed range");
                }
            } else if (BOOLEANS.contains(key)) {
                if (!value.trim().equals("true") && !value.trim().equals("false")) {
                    throw new IllegalArgumentException("requires true or false");
                }
            } else if (HagridDemandSettings.contains(key)) {
                HagridDemandSettings.apply(new HagridConfig(),Map.of(key,value),java.nio.file.Path.of("."));
            } else {
                throw new IllegalArgumentException("unknown canonical setting");
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException(key + ": " + invalid.getMessage(), invalid);
        }
    }

    /** Validate legacy input at file capture, before any network can be loaded. */
    public static void validateRemovedOptimizationOptions(Map<String,String> values) {
        for (String key : java.util.List.of("routing.zoneBasedCachingEnabled", "optimization.warmStartEnabled", "optimization.adaptiveSearchEnabled"))
            if (values.containsKey(key)) validate(key, values.get(key));
    }

    private static double number(Map<String, String> values, String key) { return Double.parseDouble(values.get(key)); }
    private static int integer(Map<String, String> values, String key) { return Integer.parseInt(values.get(key)); }
    private static OptionalInt optionalInteger(Map<String, String> values, String key) {
        return values.containsKey(key) ? OptionalInt.of(integer(values, key)) : OptionalInt.empty();
    }
}
