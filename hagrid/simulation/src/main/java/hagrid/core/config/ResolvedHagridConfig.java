package hagrid.core.config;

import hagrid.core.HagridConfig;
import hagrid.core.util.StudyArea;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Objects;

/** Immutable configuration foundation. Consumer migration is deliberately incremental. */
public record ResolvedHagridConfig(
        Run run, Demand demand, Fleet fleet, TourRules tourRules, Routing routing,
        Optimization optimization, Resources resources, Results results, Scenario scenario,
        Map<String, Source> provenance, Map<String, String> effectiveValues) {

    public ResolvedHagridConfig {
        provenance = Map.copyOf(provenance);
        effectiveValues = Map.copyOf(effectiveValues);
        if (scenario.concept() == HagridConfig.Scenario.DRT_SHAREDUSE) {
            SharedUseSettings.from(effectiveValues,tourRules);
        }
    }

    public SectionSettings sections() { return SectionSettings.from(effectiveValues); }

    public RandomSeedSettings randomSeeds() { return RandomSeedSettings.from(effectiveValues); }

    public DrtSettings drt() { return DrtSettings.from(effectiveValues); }
    public VehicleDispatchSettings dispatch() { return VehicleDispatchSettings.from(effectiveValues); }
    public SharedUseSettings sharedUse() { return SharedUseSettings.from(effectiveValues, tourRules); }
    public ModularSettings modular() { return ModularSettings.from(effectiveValues, tourRules.deliveryWindowEndSeconds()); }

    public CarrierScoringSettings scoring() { return CarrierScoringSettings.from(effectiveValues, tourRules.maxRouteDurationSeconds()); }

    public enum Source { DEFAULTS, PROFILE, RUN, CLI }
    public record Run(Optional<LocalDate> date, Optional<String> runId, String tag) {}
    public record Scenario(StudyArea studyArea, HagridConfig.Scenario concept) {}

    public record Demand(int demandBorder, int dhlBorder, int carrierMergeThreshold,
                         List<String> providers, Map<String, Integer> deliveryRates) {
        public Demand {
            providers = List.copyOf(providers);
            deliveryRates = Map.copyOf(deliveryRates);
        }
    }

    /** Vehicle XML remains authoritative for types; bike speed has an explicit configurable default. */
    public record Fleet(String vehicleTypesFile, List<String> activeSizes, double cargoBikeMaxSpeedMps) {
        public Fleet { activeSizes = List.copyOf(activeSizes); }
    }

    public record TourRules(double maxRouteDurationSeconds, double maxDriverTimeSeconds,
                            double durationPerParcelSeconds, double maxDurationPerStopSeconds,
                            double deliveryWindowStartSeconds, double deliveryWindowEndSeconds,
                            double vehicleAvailabilityBufferSeconds, double uTurnPenaltyCost, double vehicleDayEndSeconds) {
        public TourRules(double cap,double driver,double parcel,double stop,double start,double end,double buffer,double uTurn) {
            this(cap,driver,parcel,stop,start,end,buffer,uTurn,RoutingDefaults.VEHICLE_DAY_END_SECONDS);
        }
    }

    /** Neutral zone components preserve the V1 archive fingerprint shape; routing is always exact. */
    public record Routing(String networkFile, Optional<Boolean> legacyZoneProviderEnabled,
                          boolean zoneBasedCachingEnabled, double zoneBasedCachingThresholdMeters,
                          boolean transportCacheEnabled, int maximumCacheEntries, int maximumConcurrentRouters, double odTimeQuantizationSeconds) {
        public Routing {
            rejectRemoved("routing.zoneBasedCachingEnabled", zoneBasedCachingEnabled);
        }
    }

    /** Fixed budgets only. Neutral legacy components preserve the V1 archive fingerprint shape. */
    public record Optimization(int initialIterations, OptionalInt offlineIterations, OptionalInt replanningIterations,
                               OptionalInt replanningStartIteration, OptionalInt carriersPerGroup, boolean warmStartEnabled,
                               Optional<AdaptiveSearch> adaptiveSearch) {
        public Optimization {
            rejectRemoved("optimization.warmStartEnabled", warmStartEnabled);
            rejectRemoved("optimization.adaptiveSearchEnabled", adaptiveSearch.isPresent());
        }
        public Optimization(int initialIterations, OptionalInt offlineIterations, OptionalInt replanningIterations,
                            OptionalInt replanningStartIteration, OptionalInt carriersPerGroup) {
            this(initialIterations, offlineIterations, replanningIterations, replanningStartIteration, carriersPerGroup, false, Optional.empty());
        }
    }
    /** Legacy archive DTO only; an adaptive object is rejected by Optimization. */
    public record AdaptiveSearch(int minimumIterations, int maximumIterations, int plateauIterations) {
        public AdaptiveSearch {
            if (minimumIterations < 1 || maximumIterations < minimumIterations || plateauIterations < 1)
                throw new IllegalArgumentException("Invalid adaptive Minimum/Maximum/Plateau iterations");
        }
    }
    public static void rejectRemoved(String option, boolean enabled) {
        if (enabled) throw new IllegalArgumentException(option + " was removed; remove the enabled option and use exact routing with fixed iteration budgets. Historical experiments require their archived package.");
    }
    public record Resources(int internalSolverThreads, OptionalInt maxActiveSolvers) {
        public Resources {
            Objects.requireNonNull(maxActiveSolvers);
            if (internalSolverThreads < 1 || (maxActiveSolvers.isPresent() && maxActiveSolvers.getAsInt() < 1))
                throw new IllegalArgumentException("Solver resource limits must be positive");
        }
        public static Resources defaults() { return new Resources(1, OptionalInt.empty()); }
    }
    /** Absent native intervals keep XML settings; positive intervals preserve final event output. */
    public record Results(boolean carrierPlanCacheEnabled, Optional<String> carrierPlanCacheDirectory,
                          OptionalInt eventsWriteInterval, OptionalInt plansWriteInterval,
                          int freightHistogramWriteInterval) {
        public Results(boolean cache, Optional<String> directory) {
            this(cache, directory, OptionalInt.empty(), OptionalInt.empty(), 1);
        }
        public Results {
            java.util.Objects.requireNonNull(carrierPlanCacheDirectory);
            java.util.Objects.requireNonNull(eventsWriteInterval);
            java.util.Objects.requireNonNull(plansWriteInterval);
            if ((eventsWriteInterval.isPresent() && eventsWriteInterval.getAsInt() < 1)
                    || (plansWriteInterval.isPresent() && plansWriteInterval.getAsInt() < 1)
                    || freightHistogramWriteInterval < 1)
                throw new IllegalArgumentException("Output intervals must be positive to retain final artifacts");
        }
    }
}
