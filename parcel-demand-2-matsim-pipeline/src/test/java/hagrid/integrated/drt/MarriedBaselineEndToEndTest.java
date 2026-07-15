package hagrid.integrated.drt;

import hagrid.integrated.freight.FreightRunComposer;
import hagrid.integrated.freight.LausitzFreightPreprocessor;
import hagrid.integrated.freight.LmdTestShapefiles;
import hagrid.simulation.DrtScenarioBuilder;
import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.simulation.RunMetadataWriter;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.freight.carriers.*;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the MARRIED baseline: passenger DRT and offline-routed LMD carriers in
 * ONE Controler, run for lastIteration=1 so a real REPLANNING event fires. With the empty
 * default CarrierStrategyManager this run would crash entering iteration 1
 * ("No strategy found") - the KeepSelected manager from FreightRunComposer must survive it.
 */
@DisplayName("Married baseline end-to-end (DRT + LMD carriers, one Controler, replanning fires)")
class MarriedBaselineEndToEndTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("runsMarriedBaselineThroughOneReplanningIteration")
    void runsMarriedBaselineThroughOneReplanningIteration() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);

        // ---- shared raw fixtures (identical to DrtBaselineEndToEndTest) ----
        Network rawNet = DrtE2eFixtures.buildGrid();
        Path rawNetFile = dir.resolve("raw_network.xml.gz");
        new NetworkWriter(rawNet).write(rawNetFile.toString());
        Path rawPlansFile = dir.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(DrtE2eFixtures.buildDemand(), rawPlansFile.toString());
        Path shpFile = dir.resolve("service-area.shp");
        DrtE2eFixtures.writeSquareShapefile(shpFile, 2000.0);
        Path depotCsv = dir.resolve("depots.csv");
        Files.writeString(depotCsv, "provider;x;y\ndhl;500.0;500.0\n");

        // ---- LMD side: van type + tiny PANDA-like demand + PRODUCTION jsprit preprocessing ----
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        VehicleType van = VehicleUtils.createVehicleType(Id.create("ct_cep_size_m", VehicleType.class));
        van.getCapacity().setOther(165);
        van.setNetworkMode("car");
        van.getCostInformation().setCostsPerMeter(0.0004).setCostsPerSecond(0.0).setFixedCost(170.0);
        types.getVehicleTypes().put(van.getId(), van);
        Path typesFile = dir.resolve("vans.xml");
        new CarrierVehicleTypeWriter(types).write(typesFile.toString());

        Path demandShp = dir.resolve("demand.shp");
        LmdTestShapefiles.writeDemand(demandShp,
                new double[][]{{300, 200}, {800, 600}},
                new long[]{3, 2},    // dhl B2C parcels
                new long[]{1, 0},    // dhl B2B parcels
                new long[]{0, 0});   // hermes: none

        Path carriersOut = dir.resolve("lmd_carriers_routed.xml");
        LausitzFreightPreprocessor.run(demandShp.toString(), depotCsv.toString(),
                rawNetFile.toString(), typesFile.toString(), carriersOut.toString(),
                /*jspritIterations*/ 1, shpFile.toString());
        assertThat(Files.exists(carriersOut)).isTrue();

        // ---- DRT side: production preprocessor (drt-tagged net, person plans, fleet) ----
        Path drtNetFile = dir.resolve("drt_network.xml.gz");
        Path clippedPlans = dir.resolve("clipped_plans.xml.gz");
        Path fleetFile = dir.resolve("fleet.xml.gz");
        LausitzDrtPreprocessor.run(
                rawNetFile.toString(), rawPlansFile.toString(), shpFile.toString(),
                depotCsv.toString(), drtNetFile.toString(), clippedPlans.toString(),
                fleetFile.toString(), /*fleetSize*/ 4, /*capacity*/ 8,
                /*serviceBegin*/ 0.0, /*serviceEnd*/ 86400.0);

        URL cfgUrl = getClass().getClassLoader().getResource("lausitz-native-like.config.xml");
        assertThat(cfgUrl).isNotNull();
        Path matsimOut = dir.resolve("matsim");

        // ---- marriage: ONE scenario, ONE Controler; lastIteration=1 -> replanning fires ----
        Scenario scenario = DrtScenarioBuilder.build(
                cfgUrl.toString(), drtNetFile.toString(), clippedPlans.toString(),
                shpFile.toString(), fleetFile.toString(),
                matsimOut.toString(), "MARRIED_E2E", /*lastIteration*/ 1);
        FreightRunComposer.addCarriers(scenario, carriersOut.toString(), typesFile.toString());

        // Finding-2 guard: every link a routed carrier plan references must exist in the
        // married (drt-tagged) network, else the QSim cannot replay the tours.
        for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
            assertThat(carrier.getSelectedPlan()).isNotNull();
            for (ScheduledTour tour : carrier.getSelectedPlan().getScheduledTours()) {
                for (Tour.TourElement el : tour.getTour().getTourElements()) {
                    if (el instanceof Tour.Leg leg && leg.getRoute() instanceof NetworkRoute route) {
                        for (Id<Link> linkId : route.getLinkIds()) {
                            assertThat(scenario.getNetwork().getLinks())
                                    .as("carrier route link must exist in married network: " + linkId)
                                    .containsKey(linkId);
                        }
                    }
                }
            }
        }

        Controler controler = new Controler(scenario);
        DrtConfigComposer.installModules(controler);
        FreightRunComposer.installCarrierModules(controler, scenario);
        controler.run();

        // ---- both halves really ran ----
        try (var s = Files.walk(matsimOut)) {
            assertThat(s.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().contains("drt")))
                    .as("expected a drt_* output file (pax DRT ran)").isTrue();
        }
        try (var s = Files.walk(matsimOut)) {
            assertThat(s.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().endsWith("output_carriers.xml.gz")))
                    .as("expected output_carriers.xml.gz (CarrierModule ran)").isTrue();
        }

        // ---- run_metadata.json is emitted for the married run (1e Task 1) ----
        // This e2e builds the Controler directly (not via SimulationRunnerUtils.runSimulation),
        // so we invoke the same production writer the runner calls, against the real output dir.
        // Route HagridPaths at a temp root so config construction does not touch the project tree.
        System.setProperty("hagrid.pipeline.root", dir.toAbsolutePath().toString());
        HAGRIDSimulationConfig cfg;
        try {
            cfg = new HAGRIDSimulationConfig(
                    "DRT_BASELINE", LocalDate.of(2025, 5, 13),
                    /*maxIterations*/ 1, /*jspritIterations*/ 1,
                    false, 0.0, 0.0, "married_e2e",
                    StudyArea.LAUSITZ_HOYERSWERDA, /*fleetSize*/ 4,
                    /*drtWithFreight*/ true, /*kpiDashboard*/ false);
            RunMetadataWriter.write(cfg, matsimOut);
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
        Path metadataFile = matsimOut.resolve("run_metadata.json");
        assertThat(Files.exists(metadataFile))
                .as("run_metadata.json missing from MATSim output dir").isTrue();

        // ---- content assertions: this is the part that would have caught a format bug ----
        String json = Files.readString(metadataFile);
        for (String key : new String[]{
                "run_id", "run_dir_name", "scenario", "study_area", "operation_mode",
                "tag", "sim_date", "matsim_iterations", "jsprit_iterations",
                "fleet_size", "drt_with_freight", "created"}) {
            assertThat(json).as("missing key in run_metadata.json: " + key).contains("\"" + key + "\":");
        }
        assertThat(unquote(extractJsonValue(json, "operation_mode"))).isEqualTo("conventional");
        assertThat(unquote(extractJsonValue(json, "scenario"))).isEqualTo(cfg.getConcept().toUpperCase());
        assertThat(unquote(extractJsonValue(json, "study_area"))).isEqualTo(cfg.getStudyArea().name().toLowerCase());
        assertThat(extractJsonValue(json, "drt_with_freight")).isEqualTo(String.valueOf(cfg.isDrtWithFreight()));
        assertThat(extractJsonValue(json, "fleet_size")).isEqualTo(String.valueOf(cfg.getFleetSize()));
        // sim_date is deliberately ISO (yyyy-MM-dd) from getFormattedDate() - this locks the
        // shipped format intentionally; the value is not consumed downstream, ISO is correct.
        assertThat(unquote(extractJsonValue(json, "sim_date"))).isEqualTo(cfg.getFormattedDate());
    }

    /** Extracts the raw (still-JSON-encoded) value string for {@code key} from a flat run_metadata.json. */
    private static String extractJsonValue(String json, String key) {
        String needle = "\"" + key + "\": ";
        int start = json.indexOf(needle);
        assertThat(start).as("key not found in run_metadata.json: " + key).isGreaterThanOrEqualTo(0);
        start += needle.length();
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '\n' && json.charAt(end) != '\r') {
            end++;
        }
        return json.substring(start, end).trim();
    }

    /** Strips one layer of surrounding double quotes, if present. */
    private static String unquote(String raw) {
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }
}
