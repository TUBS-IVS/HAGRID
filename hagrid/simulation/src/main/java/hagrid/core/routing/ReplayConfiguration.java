package hagrid.core.routing;

import hagrid.core.config.ResolvedHagridConfig;
import java.time.LocalDate;
import java.util.*;
import static hagrid.core.config.ResolvedHagridConfig.*;

/** Private replay representation. Nullable scalars preserve absent options without resolving new defaults. */
record ReplayConfiguration(String date, String runId, String tag, Demand demand, Fleet fleet,
        TourRules tourRules, Scenario scenario, String networkFile, Boolean legacyZones,
        boolean zones, double threshold, boolean cache, int cacheEntries, int routers, double quantization,
        int initialIterations, Integer offlineIterations, Integer replanningIterations, Integer startIteration,
        Integer groupSize, boolean warmStart, AdaptiveSearch adaptive, int solverThreads, Integer activeSolvers,
        boolean planCache, String cacheDirectory, Integer eventsInterval, Integer plansInterval, Integer histogramInterval,
        Map<String, Source> provenance, Map<String, String> effectiveValues) {
    static ReplayConfiguration of(ResolvedHagridConfig c) {
        var r=c.routing(); var o=c.optimization();
        return new ReplayConfiguration(c.run().date().map(LocalDate::toString).orElse(null),
                c.run().runId().orElse(null), c.run().tag(), c.demand(), c.fleet(), c.tourRules(), c.scenario(),
                r.networkFile(), r.legacyZoneProviderEnabled().orElse(null), r.zoneBasedCachingEnabled(),
                r.zoneBasedCachingThresholdMeters(), r.transportCacheEnabled(), r.maximumCacheEntries(),
                r.maximumConcurrentRouters(), r.odTimeQuantizationSeconds(), o.initialIterations(),
                number(o.offlineIterations()), number(o.replanningIterations()), number(o.replanningStartIteration()),
                number(o.carriersPerGroup()), o.warmStartEnabled(), o.adaptiveSearch().orElse(null),
                c.resources().internalSolverThreads(), number(c.resources().maxActiveSolvers()),
                c.results().carrierPlanCacheEnabled(), c.results().carrierPlanCacheDirectory().orElse(null),
                number(c.results().eventsWriteInterval()), number(c.results().plansWriteInterval()),
                c.results().freightHistogramWriteInterval(),
                c.provenance(), c.effectiveValues());
    }
    /** Old DTO fields are read for exact V1 identity; activated experiments are rejected, never translated. */
    ResolvedHagridConfig restore() {
        ResolvedHagridConfig.rejectRemoved("routing.zoneBasedCachingEnabled",zones);
        ResolvedHagridConfig.rejectRemoved("optimization.warmStartEnabled",warmStart);
        ResolvedHagridConfig.rejectRemoved("optimization.adaptiveSearchEnabled",adaptive!=null);
        return new ResolvedHagridConfig(new Run(Optional.ofNullable(date).map(LocalDate::parse),
                Optional.ofNullable(runId), tag), demand, fleet, tourRules,
                new Routing(networkFile, Optional.ofNullable(legacyZones), zones, threshold, cache, cacheEntries, routers, quantization),
                new Optimization(initialIterations, option(offlineIterations), option(replanningIterations),
                        option(startIteration), option(groupSize), warmStart, Optional.ofNullable(adaptive)),
                new Resources(solverThreads, option(activeSolvers)),
                new Results(planCache, Optional.ofNullable(cacheDirectory), option(eventsInterval), option(plansInterval),
                        histogramInterval == null ? 1 : histogramInterval), scenario, provenance, effectiveValues);
    }
    private static Integer number(OptionalInt n) { return n.isPresent() ? n.getAsInt() : null; }
    private static OptionalInt option(Integer n) { return n==null ? OptionalInt.empty() : OptionalInt.of(n); }
}