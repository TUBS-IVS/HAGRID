package hagrid.core.simulation;

import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Applies existing simulation overrides and registers validated native carrier modes. */
final class FreightScenarioConfiguration {
    private FreightScenarioConfiguration() {}

    @SuppressWarnings("deprecation")
    static void apply(Config config, HAGRIDSimulationConfig simConfig) {

        // F3: runner-key seed (default 1337 — the value that was hard-coded here before the
        // key existed, so unchanged specs stay identical); vary for error-band replicates.
        config.global().setRandomSeed(simConfig.getSeed());

        config.network().setInputFile(simConfig.getCarNetworkPath().toAbsolutePath().toString());
        config.network().setTimeVariantNetwork(true);
        config.network().setChangeEventsInputFile(simConfig.getNetworkChangeEventPath().toAbsolutePath().toString());
        config.network().setInputCRS("EPSG:25832");

        config.controller().setOutputDirectory(simConfig.getOutputDirectory().toAbsolutePath().toString());
        config.controller().setRunId(simConfig.getRunId());
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(simConfig.getMaxIterations());
        var output = simConfig.getResolvedRoutingConfig().results();
        output.eventsWriteInterval().ifPresent(config.controller()::setWriteEventsInterval);
        output.plansWriteInterval().ifPresent(config.controller()::setWritePlansInterval);
        config.controller().setOverwriteFileSetting(
                org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);

        config.global().setCoordinateSystem("EPSG:25832");

        // Define transport modes
        Set<String> modes = new HashSet<>();
        modes.add("car");
        modes.add("cargobike");

        // NOTE: "drt" is intentionally NOT in mainModes. DRT agents are handled by
        // the DVRP PassengerEngine (installed by DvrpModule), not by qsim.mainModes.
        // "drt" is added to dvrp.networkModes by DrtConfigComposer for DRT scenarios.
        config.qsim().setMainModes(modes);
        config.qsim().setUsingTravelTimeCheckInTeleportation(true);
        config.qsim().setInflowCapacitySetting(
                org.matsim.core.config.groups.QSimConfigGroup.InflowCapacitySetting.INFLOW_FROM_FDIAG);

        config.travelTimeCalculator().setAnalyzedModes(modes);
        config.travelTimeCalculator().setSeparateModes(true);

        config.routing().setNetworkModes(modes);

        config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
        config.scoring().setFractionOfIterationsToStartScoreMSA(0.8);

        copyScoringMode(config, "car", "cargobike");

        if (simConfig.isDrtScenario()) {
            // Clipped DRT network + 100% population clipped to the service zone
            config.network().setInputFile(simConfig.getDrtNetworkClipped());
            config.plans().setInputFile(simConfig.getPassengerPlansClipped());
            // Compose native DRT params (full DVRP, service-area, DRT-only)
            hagrid.lausitz.drt.DrtConfigComposer.composeConfig(
                    config, simConfig.getDrtServiceAreaShapefile(), simConfig.getDrtFleetFile(),simConfig.getResolvedRoutingConfig().drt());
        }
    }

    private static void copyScoringMode(Config config, String fromMode, String toMode) {
        ModeParams from = config.scoring().getOrCreateModeParams(fromMode);
        ModeParams to = new ModeParams(toMode);

        to.setConstant(from.getConstant());
        to.setDailyMonetaryConstant(from.getDailyMonetaryConstant());
        to.setMarginalUtilityOfDistance(from.getMarginalUtilityOfDistance());
        to.setDailyUtilityConstant(from.getDailyUtilityConstant());
        to.setMonetaryDistanceRate(from.getMonetaryDistanceRate());

        config.scoring().addModeParams(to);
    }

    static void includeCarrierModes(Config config, Collection<String> carrierModes) {
        var mainModes = new HashSet<>(config.qsim().getMainModes());
        var networkModes = new HashSet<>(config.routing().getNetworkModes());
        var analyzedModes = new HashSet<>(config.travelTimeCalculator().getAnalyzedModes());
        for (String mode : carrierModes) {
            // DRT passengers retain their native DVRP engine rather than becoming QSim main-mode freight.
            if ("drt".equals(mode)) continue;
            mainModes.add(mode);
            networkModes.add(mode);
            analyzedModes.add(mode);
            if (!config.scoring().getModes().containsKey(mode)) copyScoringMode(config, "car", mode);
        }
        config.qsim().setMainModes(mainModes);
        config.routing().setNetworkModes(networkModes);
        config.travelTimeCalculator().setAnalyzedModes(analyzedModes);
    }
}
