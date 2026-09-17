package hagrid.simulation;

import hagrid.integrated.drt.LausitzDrtConfigurator;
import java.nio.file.Paths;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.core.config.Config;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.run.prepare.PrepareTransitSchedule;

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
     * <p>Supplies the three rail getters ({@code getRailScheduleFiltered},
     * {@code getRailTransitVehiclesFiltered}, {@code getLausitzVehicleTypes}) added in Task 2
     * plus the MATSim random seed (review F3), delegating to the seed-aware 12-arg
     * path-based overload.</p>
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
                cfg.getRailScheduleFiltered(),
                cfg.getRailTransitVehiclesFiltered(),
                cfg.getLausitzVehicleTypes(),
                cfg.getOutputDirectoryAsString(),
                cfg.getRunId(),
                cfg.getMaxIterations(),
                cfg.getSeed());
    }

    // ===================================================================
    // Path-based overloads — used directly in tests (temp-dir fixtures)
    // ===================================================================

    /**
     * Legacy DRT-only overload (no rail) — preserved for existing callers and tests.
     *
     * <p>Delegates to the 11-arg overload with {@code null} rail params so that
     * {@link DrtBaselineEndToEndTest} and any other existing caller continue to work
     * unchanged without modification.</p>
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
        return build(baseConfigPath, drtNetworkFile, plansFile, serviceAreaShp, fleetFile,
                /*railScheduleFile*/ null, /*railTransitVehiclesFile*/ null, /*vehicleTypesFile*/ null,
                outputDir, runId, lastIteration);
    }

    /**
     * Builds a DRT scenario with optional rail PT intermodality from explicit file paths.
     *
     * <p>Public so it can be driven directly by tests with temp-dir fixtures, without needing
     * a fully wired {@link HAGRIDSimulationConfig}. When {@code railScheduleFile} is non-null
     * and non-blank, rail stops inside the DRT service area are tagged as intermodal DRT
     * access/egress stops after {@code ScenarioUtils.loadScenario}. The DRT service-area
     * shapefile is reused as the intermodal area (single source of truth — plan decision 1).</p>
     *
     * @param baseConfigPath          native Lausitz base config (scoring/mode-choice source)
     * @param drtNetworkFile          network clipped to the DRT service area with drt mode added
     * @param plansFile               passenger plans clipped to the service area
     * @param serviceAreaShp          DRT service-area shapefile (also used as intermodal area)
     * @param fleetFile               generated DVRP fleet vehicles file
     * @param railScheduleFile        rail-filtered transit schedule (null → DRT-only, no tagging)
     * @param railTransitVehiclesFile rail-filtered transit vehicles (null → DRT-only)
     * @param vehicleTypesFile        native vehicle-types file (null → defaultVehicle fallback)
     * @param outputDir               MATSim output directory
     * @param runId                   run identifier
     * @param lastIteration           last MATSim iteration
     * @return the loaded MATSim scenario ready for a {@link org.matsim.core.controler.Controler}
     */
    public static Scenario build(String baseConfigPath, String drtNetworkFile, String plansFile,
                                 String serviceAreaShp, String fleetFile,
                                 String railScheduleFile, String railTransitVehiclesFile, String vehicleTypesFile,
                                 String outputDir, String runId, int lastIteration) {

        // 1. Compose the full-DVRP DRT config from the native Lausitz base config.
        Config config = LausitzDrtConfigurator.build(
                baseConfigPath, drtNetworkFile, plansFile, serviceAreaShp, fleetFile,
                railScheduleFile, railTransitVehiclesFile, vehicleTypesFile,
                outputDir, runId, lastIteration);

        return loadScenarioFromConfig(config, serviceAreaShp, railScheduleFile);
    }

    /**
     * Seed-aware variant of the 11-arg overload (review F3): additionally applies the MATSim
     * global random seed to the composed config, overriding whatever the base Lausitz config
     * pins (4711). This is the overload the {@link HAGRIDSimulationConfig} path delegates to,
     * so a {@code seed=} runner key genuinely reaches
     * {@code config.global().setRandomSeed(...)} on the DRT simulation path —
     * {@code AbstractController} resets {@code MatsimRandom} from it every iteration.
     *
     * @param seed MATSim global random seed; vary for error-band replicate runs
     */
    public static Scenario build(String baseConfigPath, String drtNetworkFile, String plansFile,
                                 String serviceAreaShp, String fleetFile,
                                 String railScheduleFile, String railTransitVehiclesFile, String vehicleTypesFile,
                                 String outputDir, String runId, int lastIteration, long seed) {

        Config config = LausitzDrtConfigurator.build(
                baseConfigPath, drtNetworkFile, plansFile, serviceAreaShp, fleetFile,
                railScheduleFile, railTransitVehiclesFile, vehicleTypesFile,
                outputDir, runId, lastIteration);
        config.global().setRandomSeed(seed);

        return loadScenarioFromConfig(config, serviceAreaShp, railScheduleFile);
    }

    /** Shared steps 2-4: two-step scenario load + optional intermodal rail-stop tagging. */
    private static Scenario loadScenarioFromConfig(Config config, String serviceAreaShp,
                                                   String railScheduleFile) {
        // 2. Two-step load: create scenario first so we can register the DrtRouteFactory
        //    BEFORE the plans XML is read. ScenarioUtils.loadScenario(config) gives no
        //    hook for this and would fail to deserialise DRT legs.
        Scenario scenario = ScenarioUtils.createScenario(config);
        scenario.getPopulation().getFactory().getRouteFactories()
                .setRouteFactory(DrtRoute.class, new DrtRouteFactory());

        // 3. Load network + plans (+ transit schedule when rail is on).
        ScenarioUtils.loadScenario(scenario);

        // 4. Tag rail stops reachable by DRT as intermodal access/egress. The intermodal-area IS
        //    the DRT service-area shapefile (single source of truth — plan decision 1), so every
        //    rail stop inside the DRT zone (incl. Hoyerswerda + Ruhland Bahnhof) becomes
        //    DRT-feedable, and expanding the service area later auto-expands intermodal coverage.
        if (railScheduleFile != null && !railScheduleFile.isBlank()) {
            String shp = Paths.get(serviceAreaShp).toAbsolutePath().normalize().toString();
            PrepareTransitSchedule.tagIntermodalStops(
                    scenario.getTransitSchedule(),
                    new ShpOptions(shp, null, null));
        }

        return scenario;
    }
}
