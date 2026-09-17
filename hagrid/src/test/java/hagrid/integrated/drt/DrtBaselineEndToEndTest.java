package hagrid.integrated.drt;

import hagrid.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end runtime proof for the passenger-only DRT_BASELINE pipeline.
 *
 * <p>Unlike {@link DrtBaselineIntegrationTest} (which builds its config inline, from scratch,
 * and overrides the operational scheme to {@code door2door}), this test boots a real
 * one-iteration MATSim DRT run through the <em>production</em> code path:</p>
 * <ol>
 *   <li>{@link LausitzDrtPreprocessor#run} produces the drt-augmented network, the
 *       person-only clipped plans, and the DVRP fleet from tiny in-code fixtures + a
 *       real (hand-rolled) service-area shapefile;</li>
 *   <li>{@link DrtScenarioBuilder#build} composes the config via
 *       {@link LausitzDrtConfigurator#build} (native-like base config: PT + counts +
 *       a remote {@code vehicles} ref present, so the strip/redirect/vehicles-hardening
 *       path is exercised), composes full-DVRP DRT via {@link DrtConfigComposer}, and
 *       performs the two-step create + register-DrtRouteFactory + load;</li>
 *   <li>a {@link Controler} runs one iteration with {@link DrtConfigComposer#installModules}.</li>
 * </ol>
 *
 * <p>The assertion (a {@code drt_*} output file exists after {@code controler.run()} with
 * {@code lastIteration=0}) reflects a genuine simulation, not a stub. The operational scheme
 * stays {@code serviceAreaBased} (the production default), so the run reads the real
 * service-area shapefile via the DRT module — proving the whole passenger-only path works.</p>
 */
@DisplayName("DRT_BASELINE end-to-end run (production path)")
class DrtBaselineEndToEndTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    /** Service-area square: 0..2000. All fixture car nodes are inside (100..1000). */
    private static final double AREA_SIZE = 2000.0;

    @Test
    @DisplayName("runsDrtBaselineOneIteration — full production path produces a drt_* output file")
    void runsDrtBaselineOneIteration() throws Exception {
        // Absolute output dir: the base config is loaded from a classpath URL, so MATSim sets
        // its context to target/test-classes and would resolve RELATIVE network/plans paths
        // against that context (not the CWD the writers used) — mismatching the written files.
        // Absolute paths sidestep the context resolution entirely.
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);

        // ---- raw fixtures: full network, full population, service-area shapefile ----
        Network rawNet = DrtE2eFixtures.buildGrid();
        Path rawNetFile = dir.resolve("raw_network.xml.gz");
        new NetworkWriter(rawNet).write(rawNetFile.toString());

        Path rawPlansFile = dir.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(DrtE2eFixtures.buildDemand(), rawPlansFile.toString());

        Path shpFile = dir.resolve("service-area.shp");
        DrtE2eFixtures.writeSquareShapefile(shpFile, AREA_SIZE);

        // ---- run-scoped DRT input files via the production preprocessor ----
        Path drtNetFile = dir.resolve("drt_network.xml.gz");
        Path clippedPlans = dir.resolve("clipped_plans.xml.gz");
        Path fleetFile = dir.resolve("fleet.xml.gz");
        Path depotCsv = dir.resolve("depots.csv");
        Files.writeString(depotCsv, "provider;x;y\ndhl;500.0;500.0\n");
        LausitzDrtPreprocessor.run(
                rawNetFile.toString(),
                rawPlansFile.toString(),
                shpFile.toString(),
                depotCsv.toString(),
                drtNetFile.toString(),
                clippedPlans.toString(),
                fleetFile.toString(),
                /*fleetSize*/ 4, /*capacity*/ 8, /*serviceBegin*/ 0.0, /*serviceEnd*/ 86400.0);

        // ---- base config: portable native-like fixture (PT/counts/vehicles present) ----
        URL cfgUrl = getClass().getClassLoader().getResource("lausitz-native-like.config.xml");
        assertThat(cfgUrl)
                .as("test fixture lausitz-native-like.config.xml must be on the test classpath")
                .isNotNull();

        Path matsimOut = dir.resolve("matsim");

        // ---- build the scenario through the production builder (configurator + composer) ----
        Scenario scenario = DrtScenarioBuilder.build(
                cfgUrl.toString(),
                drtNetFile.toString(),
                clippedPlans.toString(),
                shpFile.toString(),          // real shapefile: serviceAreaBased reads it at run time
                fleetFile.toString(),
                matsimOut.toString(),
                "DRT_BASELINE_E2E",
                /*lastIteration*/ 0);

        // Sanity: plans + drt-mode links + fleet were wired in before the run.
        assertThat(scenario.getPopulation().getPersons())
                .as("clipped person population must be non-empty").isNotEmpty();
        assertThat(scenario.getNetwork().getLinks().values().stream()
                .anyMatch(l -> l.getAllowedModes().contains(TransportMode.drt)))
                .as("network must carry drt-mode links").isTrue();

        // ---- run one real iteration through the production install path ----
        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);
        controler.run();

        // ---- assert a genuine drt_* output file was produced ----
        assertThat(Files.isDirectory(matsimOut))
                .as("MATSim output directory must exist after the run").isTrue();
        try (var stream = Files.walk(matsimOut)) {
            assertThat(stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().contains("drt")))
                    .as("expected at least one drt_* output file produced by a real DRT iteration")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("returnToDepotModuleBootsViaProductionWiring — 5-arg installModules resolves ReturnToDepotRebalancingModule modal bindings (ZoneSystem + ZonalDemandEstimator) without Guice error")
    void returnToDepotModuleBootsViaProductionWiring() throws Exception {
        // Use a separate output sub-directory so both tests can coexist under the same @RegisterExtension.
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath().resolve("rtd");
        Files.createDirectories(dir);

        // ---- raw fixtures (same as runsDrtBaselineOneIteration) ----
        Network rawNet = DrtE2eFixtures.buildGrid();
        Path rawNetFile = dir.resolve("raw_network.xml.gz");
        new NetworkWriter(rawNet).write(rawNetFile.toString());

        Path rawPlansFile = dir.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(DrtE2eFixtures.buildDemand(), rawPlansFile.toString());

        Path shpFile = dir.resolve("service-area.shp");
        DrtE2eFixtures.writeSquareShapefile(shpFile, AREA_SIZE);

        // ---- depot CSV (same single depot at 500,500 inside the service area) ----
        Path depotCsv = dir.resolve("depots.csv");
        Files.writeString(depotCsv, "provider;x;y\ndhl;500.0;500.0\n");

        // ---- production preprocessor ----
        Path drtNetFile = dir.resolve("drt_network.xml.gz");
        Path clippedPlans = dir.resolve("clipped_plans.xml.gz");
        Path fleetFile = dir.resolve("fleet.xml.gz");
        LausitzDrtPreprocessor.run(
                rawNetFile.toString(),
                rawPlansFile.toString(),
                shpFile.toString(),
                depotCsv.toString(),
                drtNetFile.toString(),
                clippedPlans.toString(),
                fleetFile.toString(),
                /*fleetSize*/ 4, /*capacity*/ 8, /*serviceBegin*/ 0.0, /*serviceEnd*/ 86400.0);

        // ---- base config ----
        URL cfgUrl = getClass().getClassLoader().getResource("lausitz-native-like.config.xml");
        assertThat(cfgUrl)
                .as("test fixture lausitz-native-like.config.xml must be on the test classpath")
                .isNotNull();

        Path matsimOut = dir.resolve("matsim");

        // ---- build scenario (same production builder) ----
        Scenario scenario = DrtScenarioBuilder.build(
                cfgUrl.toString(),
                drtNetFile.toString(),
                clippedPlans.toString(),
                shpFile.toString(),
                fleetFile.toString(),
                matsimOut.toString(),
                "DRT_BASELINE_RTD_E2E",
                /*lastIteration*/ 0);

        assertThat(scenario.getPopulation().getPersons())
                .as("clipped person population must be non-empty").isNotEmpty();
        assertThat(scenario.getNetwork().getLinks().values().stream()
                .anyMatch(l -> l.getAllowedModes().contains(TransportMode.drt)))
                .as("network must carry drt-mode links").isTrue();

        // ---- read depot coords via production DrtDepotReader ----
        List<Coord> depotCoords = DrtDepotReader.readCoords(depotCsv);
        assertThat(depotCoords).as("depot CSV must yield at least one coord").isNotEmpty();

        // ---- 5-arg installModules: this wires ReturnToDepotRebalancingModule;
        //      eager-singleton provider calls getModal(ZoneSystem) + getModal(ZonalDemandEstimator)
        //      at injector creation — a Guice ProvisionException/CreationException here means
        //      the modal binding is broken. ----
        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(
                controler,
                depotCoords,
                /*returnStart*/ 81000.0,
                /*targetPerDepotZone*/ 4.0,
                /*demandEstimationPeriod*/ 1800.0);
        controler.run();

        // ---- assert genuine drt_* output (and implicitly: no Guice error on boot) ----
        assertThat(Files.isDirectory(matsimOut))
                .as("MATSim output directory must exist after the return-to-depot run").isTrue();
        try (var stream = Files.walk(matsimOut)) {
            assertThat(stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().contains("drt")))
                    .as("expected at least one drt_* output file from the return-to-depot run")
                    .isTrue();
        }
    }

}
