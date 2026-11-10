package hagrid.core.config;

import java.util.*;

/** Names shared by the file loader, launch parser and reference configuration. */
public final class HagridConfigurationCatalog {
    private HagridConfigurationCatalog() {}
    public static final Map<String,String> LAUNCH_KEYS=Map.ofEntries(
        Map.entry("run.concept","concept"), Map.entry("run.date","date"), Map.entry("run.tag","tag"),
        Map.entry("run.studyArea","studyArea"), Map.entry("run.seed","seed"),
        Map.entry("simulation.maxIterations","maxIter"), Map.entry("simulation.jspritIterations","jspritIter"),
        Map.entry("simulation.overwriteCompletedRun","overwrite"), Map.entry("results.writeDashboard","writeDashboard"),
        Map.entry("results.kpiDashboard","kpiDashboard"), Map.entry("results.eventsWriteInterval","eventsEvery"),
        Map.entry("results.plansWriteInterval","plansEvery"), Map.entry("results.freightHistogramWriteInterval","freightHistogramEvery"),
        Map.entry("fleet.cargoBikeMaxSpeedKmh","cargoBikeMaxSpeedKmh"), Map.entry("resources.internalSolverThreads","solverThreads"),
        Map.entry("resources.maxActiveSolvers","maxActiveSolvers"),
        Map.entry("tourRules.maxRouteDurationSeconds","solverMaxRouteDuration"),
        Map.entry("tourRules.uTurnPenaltyCost","uTurnPenalty"),
        Map.entry("drt.fleetSize","fleetSize"), Map.entry("freight.enabled","freight"),
        Map.entry("sharedUse.chiThresholdSeconds","chiThreshold"), Map.entry("sharedUse.noParcels","noParcels"),
        Map.entry("modular.idleThreshold","idleThreshold"), Map.entry("modular.maxTourDurationSeconds","maxTourDuration"),
        Map.entry("modular.openDepots","openDepots"), Map.entry("modular.maxJobsPerDistrict","maxJobsPerDistrict"),
        Map.entry("modular.maxConcurrentFreight","maxConcurrentFreight"), Map.entry("modular.freightWindows","freightWindows"),
        Map.entry("modular.budgetMode","budgetMode"), Map.entry("modular.budgetSmoothing","budgetSmoothing"),
        Map.entry("modular.budgetHeadroom","budgetHeadroom"), Map.entry("modular.budgetUrgencyLeadSeconds","budgetUrgencyLeadS"));
    /** Legacy modular caps are aliases of the common duration, resolved within each source. */
    public static String canonicalName(String key) {
        if (Set.of("lmd.missedDeliverySeed", "lmd.tourRetimeSeed", "sharedUse.parcelSeed").contains(key))
            return "run.seed";
        if (key.equals("maxTourDuration") || key.equals("modular.maxTourDurationSeconds"))
            return "tourRules.maxRouteDurationSeconds";
        if (key.equals("zoneCaching")) return "routing.zoneBasedCachingEnabled";
        if (key.equals("zoneThreshold")) return "routing.zoneBasedCachingThresholdMeters";
        return LAUNCH_KEYS.entrySet().stream().filter(e->e.getValue().equals(key)).map(Map.Entry::getKey).findFirst().orElse(key);
    }
    public static String launchName(String key) { return key.equals("routing.zoneBasedCachingEnabled") ? "zoneCaching" : key.equals("routing.zoneBasedCachingThresholdMeters") ? "zoneThreshold" : LAUNCH_KEYS.getOrDefault(key,key); }
    public static Map<String,String> launchValues(Map<String,String> values) {
        Map<String,String> result=new LinkedHashMap<>();
        values.forEach((key,value)->{
            String name=launchName(canonicalName(key));
            String previous=result.putIfAbsent(name,value);
            if(previous!=null && !previous.equals(value)) throw new IllegalArgumentException("Conflicting aliases for "+canonicalName(key)+(name.equals("solverMaxRouteDuration")?" (route duration)":""));
        });
        return Map.copyOf(result);
    }
    /** A bare CLI token continues only a setting whose own value is a list. */
    public static boolean listValued(String key) {
        String canonical=canonicalName(key);
        return Set.of("modular.openDepots", "modular.freightWindows", "providers.shapefileProviders",
                "providers.locationProviders", "demand.filterRegions", "fleet.activeSizes", "fleet.defaultDispatchHours",
                "lmd.dispatchHours").contains(canonical)
                || canonical.startsWith("fleet.providerSizes.") || canonical.startsWith("fleet.providerDispatchHours.");
    }
    public static boolean known(String key) {
        return LAUNCH_KEYS.containsKey(canonicalName(key)) || HagridConfigResolver.isCanonicalKey(key)
                || HagridDemandSettings.contains(key);
    }
}
