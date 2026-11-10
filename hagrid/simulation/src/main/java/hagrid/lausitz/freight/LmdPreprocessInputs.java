package hagrid.lausitz.freight;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything one LMD preprocessing computation depends on besides code and JVM.
 *
 * <p>{@link LausitzFreightPreprocessor} reads its inputs ONLY from this record, and
 * {@link JspritCacheKey} derives the cache key from ALL of its components by reflection. A new
 * preprocessing input therefore has to become a component and enters the key automatically. The
 * output path is deliberately not a component: where a result is written never changes what it is.
 *
 * <p>Component types are limited to what {@link JspritCacheKey} can encode: {@link Path} (hashed by
 * content, a {@code .shp} as its whole sidecar family), {@link Variant}, {@code int}/{@link Integer}
 * and {@code List<String>}. {@code null} means "not applicable to this variant".
 */
public record LmdPreprocessInputs(
        Variant variant,
        Path demandShp,
        Path depotCsv,
        Path network,
        Path vehicleTypes,
        Path serviceAreaShp,
        int jspritIterations,
        Integer maxTourDurationSeconds,
        List<String> openDepots,
        Integer maxJobsPerDistrict,
        int internalSolverThreads,
        Integer maxActiveSolvers,
        List<String> tourRuleValues) {

    /** Which preprocessing produced the carriers: LMD baseline (vans) or 1d modular (capsules). */
    public enum Variant { BASELINE, MODULAR }

    public LmdPreprocessInputs {
        openDepots = openDepots == null ? null : List.copyOf(openDepots);
        tourRuleValues=tourRuleValues==null?null:List.copyOf(tourRuleValues);
        if (internalSolverThreads < 1 || (maxActiveSolvers != null && maxActiveSolvers < 1))
            throw new IllegalArgumentException("Solver resource limits must be positive");
    }

    public LmdPreprocessInputs(Variant variant,Path demandShp,Path depotCsv,Path network,Path vehicleTypes,
            Path serviceAreaShp,int jspritIterations,Integer maxTourDurationSeconds,List<String> openDepots,
            Integer maxJobsPerDistrict,int internalSolverThreads,Integer maxActiveSolvers) {
        this(variant,demandShp,depotCsv,network,vehicleTypes,serviceAreaShp,jspritIterations,maxTourDurationSeconds,
                openDepots,maxJobsPerDistrict,internalSolverThreads,maxActiveSolvers,null);
    }
    public LmdTourRules tourRules() {
        return tourRuleValues==null?LmdTourRules.defaults(variant,routeDurationSeconds()):LmdTourRules.decode(tourRuleValues,routeDurationSeconds());
    }
    public LmdPreprocessInputs withTourRules(hagrid.core.config.ResolvedHagridConfig config) {return withTourRules(LmdTourRules.from(config));}
    public LmdPreprocessInputs withTourRules(LmdTourRules rules) {
        return new LmdPreprocessInputs(variant,demandShp,depotCsv,network,vehicleTypes,serviceAreaShp,jspritIterations,
                rules.maxRouteDurationSeconds(),openDepots,maxJobsPerDistrict,internalSolverThreads,maxActiveSolvers,rules.encoded());
    }
    /** Compatibility inputs preserve the existing one-thread solver and caller-owned admission limit. */
    public LmdPreprocessInputs(Variant variant, Path demandShp, Path depotCsv, Path network, Path vehicleTypes,
            Path serviceAreaShp, int jspritIterations, Integer maxTourDurationSeconds,
            List<String> openDepots, Integer maxJobsPerDistrict) {
        this(variant,demandShp,depotCsv,network,vehicleTypes,serviceAreaShp,jspritIterations,
                maxTourDurationSeconds,openDepots,maxJobsPerDistrict,1,null);
    }

    public LmdPreprocessInputs withResources(hagrid.core.config.ResolvedHagridConfig.Resources resources) {
        java.util.Objects.requireNonNull(resources);
        return new LmdPreprocessInputs(variant,demandShp,depotCsv,network,vehicleTypes,serviceAreaShp,
                jspritIterations,maxTourDurationSeconds,openDepots,maxJobsPerDistrict,
                resources.internalSolverThreads(),resources.maxActiveSolvers().isPresent()
                        ? resources.maxActiveSolvers().getAsInt() : null,tourRuleValues);
    }

    /** Same effective cap for solver, wave windows, retiming and cached-plan verification. */
    public int routeDurationSeconds() {
        return maxTourDurationSeconds == null
                ? hagrid.core.config.RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS : maxTourDurationSeconds;
    }

    public LmdPreprocessInputs withRouteDurationSeconds(int seconds) {
        if (seconds <= 0) throw new IllegalArgumentException("Solver route duration must be positive");
        Integer explicit = variant == Variant.BASELINE
                && seconds == hagrid.core.config.RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS ? null : seconds;
        return new LmdPreprocessInputs(variant,demandShp,depotCsv,network,vehicleTypes,serviceAreaShp,
                jspritIterations,explicit,openDepots,maxJobsPerDistrict,internalSolverThreads,maxActiveSolvers,
                tourRuleValues==null?null:LmdTourRules.decode(tourRuleValues,seconds).encoded());
    }

    public hagrid.core.config.ResolvedHagridConfig.Resources resources() {
        return new hagrid.core.config.ResolvedHagridConfig.Resources(internalSolverThreads,
                maxActiveSolvers == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(maxActiveSolvers));
    }
    public static LmdPreprocessInputs baseline(String demandShp, String depotCsv, String networkFile,
                                               String vehicleTypesFile, int jspritIterations,
                                               String serviceAreaShp) {
        return new LmdPreprocessInputs(Variant.BASELINE, Path.of(demandShp), Path.of(depotCsv),
                Path.of(networkFile), Path.of(vehicleTypesFile), optionalPath(serviceAreaShp),
                jspritIterations, null, null, null);
    }

    /**
     * {@code openDepots} {@code null} and empty both mean "all depots" in
     * {@code DeliveryDistrictBuilder.selectOpenDepots}, so both normalise to an empty list - the
     * same behaviour must give the same key.
     */
    public static LmdPreprocessInputs modular(String demandShp, String depotCsv, String networkFile,
                                              String vanTypesFile, int jspritIterations,
                                              String serviceAreaShp, int maxTourDurationSeconds,
                                              List<String> openDepots, int maxJobsPerDistrict) {
        return new LmdPreprocessInputs(Variant.MODULAR, Path.of(demandShp), Path.of(depotCsv),
                Path.of(networkFile), Path.of(vanTypesFile), optionalPath(serviceAreaShp),
                jspritIterations, maxTourDurationSeconds,
                openDepots == null ? List.of() : openDepots, maxJobsPerDistrict);
    }

    private static Path optionalPath(String path) {
        return path == null || path.isBlank() ? null : Path.of(path);
    }
}
