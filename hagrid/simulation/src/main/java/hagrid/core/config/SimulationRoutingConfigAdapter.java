package hagrid.core.config;

import hagrid.core.HagridConfig;
import hagrid.core.simulation.HAGRIDSimulationConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Bridges the existing launch inputs without assigning their defaults CLI precedence. */
public final class SimulationRoutingConfigAdapter {
    private SimulationRoutingConfigAdapter() {}

    /** Existing simulation launches have no mutable demand configuration attached. */
    public static ResolvedHagridConfig resolve(HAGRIDSimulationConfig launch) {
        var defaults = new HagridConfig();
        defaults.setStudyArea(launch.getStudyArea());
        defaults.setScenario(concept(launch));
        defaults.setTag(launch.getTag());
        defaults.setSimulationDate(launch.getSimulationDate());
        launch.getConfigurationFile().ifPresent(file -> file.applyTo(defaults));
        return resolve(defaults, launch, HagridConfigResolver.Overrides.empty());
    }

    public static ResolvedHagridConfig resolve(HagridConfig demandDefaults, HAGRIDSimulationConfig launch,
                                               HagridConfigResolver.Overrides run) {
        var concept = concept(launch);
        var profile = concept.requiresLausitz()
                ? HagridConfigResolver.lausitz(concept == HagridConfig.Scenario.DRT_MODULAR
                        ? launch.getMaxTourDurationSeconds() : demandDefaults.routing().getSolverMaxRouteDurationSeconds())
                : new HagridConfigResolver.Overrides(HagridConfigResolver.hannoverTarget(demandDefaults).values(),
                        Optional.of(launch.getStudyArea()));
        if(concept.requiresLausitz()) {
            var nativeProfile=new LinkedHashMap<>(profile.values());
            nativeProfile.put("tourRules.deliveryWindowStartSeconds",Double.toString(RoutingDefaults.LAUSITZ_DELIVERY_WINDOW_START_SECONDS));
            nativeProfile.put("tourRules.deliveryWindowEndSeconds",Double.toString(RoutingDefaults.LAUSITZ_DELIVERY_WINDOW_END_SECONDS));
            profile=new HagridConfigResolver.Overrides(nativeProfile,profile.requiredStudyArea());
        }
        return resolve(demandDefaults, launch, profile, run);
    }

    public static ResolvedHagridConfig resolve(HagridConfig demandDefaults, HAGRIDSimulationConfig launch,
                                               HagridConfigResolver.Overrides profile,
                                               HagridConfigResolver.Overrides run) {
        if (demandDefaults.getStudyArea() != launch.getStudyArea()) {
            throw new IllegalArgumentException("Demand and simulation studyArea differ");
        }
        if (demandDefaults.getScenario() != concept(launch)) {
            throw new IllegalArgumentException("Demand and simulation concept differ");
        }
        if (!launch.getSimulationDate().equals(demandDefaults.getSimulationDate())
                || !launch.getTag().equals(demandDefaults.getTag())) {
            throw new IllegalArgumentException("Demand and simulation date/tag differ");
        }

        boolean conventionalReplanning = !concept(launch).requiresLausitz();
        Map<String, String> launchDefaults = new LinkedHashMap<>();
        launchDefaults.put("run.seed", Long.toString(launch.getSeed()));
        launchDefaults.put("tourRules.uTurnPenaltyCost", Double.toString(
                conventionalReplanning ? launch.getUTurnPenaltyCost() : 0));
        budgetKey(launch).ifPresent(key -> launchDefaults.put(key, Integer.toString(launch.getJspritIterations())));

        Map<String, String> runValues = new LinkedHashMap<>();
        Map<String, String> cliValues = new LinkedHashMap<>();
        var fileInputs=launch.getFileScenarioInputs();
        launch.getConfigurationFile().ifPresent(file->runValues.putAll(file.canonicalValues()));
        budgetKey(launch).ifPresent(key->copy(fileInputs,"jspritIter",runValues,key));
        if(conventionalReplanning) {
            copy(fileInputs,"zoneCaching",runValues,"routing.zoneBasedCachingEnabled");
            copy(fileInputs,"zoneThreshold",runValues,"routing.zoneBasedCachingThresholdMeters");
            copy(fileInputs,"uTurnPenalty",runValues,"tourRules.uTurnPenaltyCost");
        }
        if(concept(launch)==HagridConfig.Scenario.DRT_MODULAR)
            copy(fileInputs,"maxTourDuration",runValues,"tourRules.maxRouteDurationSeconds");
        copy(fileInputs,"seed",runValues,"run.seed");
        copy(fileInputs,"solverMaxRouteDuration",runValues,"tourRules.maxRouteDurationSeconds");
        if (launch.getExplicitScenarioInputs().isPresent()) {
            var rawExplicit = launch.getExplicitScenarioInputs().orElseThrow();
            var explicit = new LinkedHashMap<>(HagridConfigurationCatalog.launchValues(rawExplicit));
            // The old CLI-only modular flag was ignored in other concepts. Keep that compatibility;
            // the canonical common duration and file settings remain explicit common overrides.
            if (concept(launch) != HagridConfig.Scenario.DRT_MODULAR && rawExplicit.containsKey("maxTourDuration")
                    && !rawExplicit.containsKey("solverMaxRouteDuration")
                    && !rawExplicit.containsKey("tourRules.maxRouteDurationSeconds")
                    && !rawExplicit.containsKey("modular.maxTourDurationSeconds")) {
                explicit.remove("solverMaxRouteDuration");
            }
            rawExplicit.forEach((key,value)->{
                String canonical=HagridConfigurationCatalog.canonicalName(key);
                if(key.equals(canonical) && HagridConfigResolver.isCanonicalKey(canonical)) cliValues.put(canonical,value);
            });
            budgetKey(launch).ifPresent(key -> copy(explicit, "jspritIter", cliValues, key));
            if (conventionalReplanning) {
                copy(explicit, "zoneCaching", cliValues, "routing.zoneBasedCachingEnabled");
                copy(explicit, "zoneThreshold", cliValues, "routing.zoneBasedCachingThresholdMeters");
                copy(explicit, "uTurnPenalty", cliValues, "tourRules.uTurnPenaltyCost");
            }
            if (concept(launch) == HagridConfig.Scenario.DRT_MODULAR) {
                copy(explicit, "maxTourDuration", cliValues, "tourRules.maxRouteDurationSeconds");
            }
            copy(explicit, "seed", cliValues, "run.seed");
            copy(explicit, "solverMaxRouteDuration", cliValues, "tourRules.maxRouteDurationSeconds");
            copy(explicit, "solverThreads", cliValues, "resources.internalSolverThreads");
            copy(explicit, "maxActiveSolvers", cliValues, "resources.maxActiveSolvers");
            copy(explicit, "cargoBikeMaxSpeedKmh", cliValues, "fleet.cargoBikeMaxSpeedKmh");
            copy(explicit, "eventsEvery", cliValues, "results.eventsWriteInterval");
            copy(explicit, "plansEvery", cliValues, "results.plansWriteInterval");
            copy(explicit, "freightHistogramEvery", cliValues, "results.freightHistogramWriteInterval");
        } else {
            runValues.put("run.seed", Long.toString(launch.getSeed()));
            // Only effective constructor arguments are run inputs; ignored native-routing flags are not.
            if (conventionalReplanning) {
                runValues.putAll(launchDefaults);
            } else {
                budgetKey(launch).ifPresent(key -> runValues.put(key, launchDefaults.get(key)));
            }
        }
        runValues.putAll(run.values());
        return HagridConfigResolver.resolve(demandDefaults,
                HagridConfigResolver.Overrides.of(launchDefaults), profile,
                new HagridConfigResolver.Overrides(runValues, run.requiredStudyArea()), cliValues);
    }

    private static HagridConfig.Scenario concept(HAGRIDSimulationConfig launch) {
        return HagridConfig.Scenario.valueOf(launch.getConcept().toUpperCase(java.util.Locale.ROOT));
    }

    private static Optional<String> budgetKey(HAGRIDSimulationConfig launch) {
        var concept = concept(launch);
        if (!concept.requiresLausitz()) return Optional.of("optimization.replanningIterations");
        if (concept == HagridConfig.Scenario.LMD_BASELINE || concept == HagridConfig.Scenario.DRT_MODULAR
                || (concept == HagridConfig.Scenario.DRT_BASELINE && launch.isDrtWithFreight())) {
            return Optional.of("optimization.offlineIterations");
        }
        return Optional.empty();
    }

    private static void copy(Map<String, String> input, String oldKey, Map<String, String> output, String newKey) {
        if (input.containsKey(oldKey)) output.put(newKey, input.get(oldKey));
    }
}
