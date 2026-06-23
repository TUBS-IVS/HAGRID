package hagrid.simulation;

import hagrid.integrated.drt.LausitzDrtConfigurator;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.core.config.Config;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Builds a passenger-only DRT {@link Scenario} for use in {@link SimulationRunnerUtils#runSimulation}.
 *
 * <p>The freight pipeline ({@link HAGRIDScenarioBuilder}) is entirely bypassed. Instead
 * this class delegates to {@link LausitzDrtConfigurator#build} for config composition, then
 * follows the proven two-step load pattern:
 * <ol>
 *   <li>{@code ScenarioUtils.createScenario(config)} — allocates the scenario without reading files;</li>
 *   <li>register {@link DrtRoute}/{@link DrtRouteFactory} on the population route factories
 *       (required so MATSim can deserialise DRT legs from the plans XML); and</li>
 *   <li>{@code ScenarioUtils.loadScenario(scenario)} — reads network + plans files.</li>
 * </ol>
 *
 * <p>The public {@link #build(HAGRIDSimulationConfig)} overload maps a fully initialised
 * {@link HAGRIDSimulationConfig} to the path-based package-private overload. The path-based
 * overload is package-private so it can be driven directly by tests with temp-dir fixtures,
 * without needing a fully wired {@link HAGRIDSimulationConfig}.
 */
public final class DrtScenarioBuilder {

    private DrtScenarioBuilder() {}

    // ===================================================================
    // Public API — called from SimulationRunnerUtils
    // ===================================================================

    /**
     * Builds a passenger-only DRT scenario from a fully initialised simulation config.
     *
     * @param cfg a {@link HAGRIDSimulationConfig} for which {@link HAGRIDSimulationConfig#isDrtScenario()}
     *            returns {@code true}
     * @return the loaded MATSim scenario
     */
    public static Scenario build(HAGRIDSimulationConfig cfg) {
        return build(
                cfg.getLausitzBaseConfig(),
                cfg.getDrtNetworkClipped(),
                cfg.getPassengerPlansClipped(),
                cfg.getDrtServiceAreaShapefile(),
                cfg.getDrtFleetFile(),
                cfg.getOutputDirectoryAsString(),
                cfg.getRunId(),
                cfg.getMaxIterations());
    }

    // ===================================================================
    // Path-based overload — used directly in tests (temp-dir fixtures)
    // ===================================================================

    /**
     * Builds a passenger-only DRT scenario from explicit file paths.
     *
     * <p>Public so it can be driven directly by tests with temp-dir fixtures (e.g. the
     * end-to-end run test in {@code hagrid.integrated.drt}), without needing a fully wired
     * {@link HAGRIDSimulationConfig}. This is the exact path the public
     * {@link #build(HAGRIDSimulationConfig)} overload delegates to.</p>
     *
     * @param baseConfigPath  native Lausitz base config (scoring/mode-choice source)
     * @param drtNetworkFile  network clipped to the DRT service area with drt mode added
     * @param plansFile       passenger plans clipped to the service area
     * @param serviceAreaShp  DRT service-area shapefile
     * @param fleetFile       generated DVRP fleet vehicles file
     * @param outputDir       MATSim output directory
     * @param runId           run identifier
     * @param lastIteration   last MATSim iteration
     * @return the loaded MATSim scenario ready for a {@link org.matsim.core.controler.Controler}
     */
    public static Scenario build(String baseConfigPath, String drtNetworkFile, String plansFile,
                          String serviceAreaShp, String fleetFile,
                          String outputDir, String runId, int lastIteration) {

        // 1. Compose the full-DVRP DRT config from the native Lausitz base config.
        Config config = LausitzDrtConfigurator.build(
                baseConfigPath, drtNetworkFile, plansFile,
                serviceAreaShp, fleetFile, outputDir, runId, lastIteration);

        // 2. Two-step load: create scenario first so we can register the DrtRouteFactory
        //    BEFORE the plans XML is read. ScenarioUtils.loadScenario(config) gives no
        //    hook for this and would fail to deserialise DRT legs.
        Scenario scenario = ScenarioUtils.createScenario(config);
        scenario.getPopulation().getFactory().getRouteFactories()
                .setRouteFactory(DrtRoute.class, new DrtRouteFactory());

        // 3. Load network + plans.
        ScenarioUtils.loadScenario(scenario);

        return scenario;
    }
}
