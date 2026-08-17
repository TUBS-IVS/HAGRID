package hagrid.integrated.drt;

import hagrid.integrated.freight.LausitzFreightPreprocessor;
import hagrid.integrated.freight.LmdTestShapefiles;
import hagrid.integrated.modular.Modular;
import hagrid.integrated.modular.ModularFreightTour;
import hagrid.integrated.modular.ModularPlanStats;
import hagrid.integrated.modular.ModularTourConverter;
import hagrid.integrated.modular.ModularVehicleTypes;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.carriers.CarrierVehicleTypeWriter;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared file-staging recipe for the DRT_MODULAR end-to-end test suite ({@link ModularEndToEndTest},
 * {@link ModularControlArmTest}). Extracted (Task 12 review, Minor 1) because both tests' validity
 * depends on staging the IDENTICAL raw network, demand, service-area shapefile, depot, van type,
 * jsprit-routed carriers and converted tour list — two independently maintained copies of this ~70
 * line recipe were a drift risk (a change to one but not the other would silently make the tests
 * stage different fixtures), not merely duplication.
 *
 * <p>Deliberately narrow: this stages FILES and the converted {@link ModularFreightTour} list only —
 * everything up to (but not including) {@code DrtScenarioBuilder.build}. Scenario/Controler
 * construction, config mutation (e.g. rebalancing cell size), and population additions differ per
 * test/arm and stay in each test's own method.</p>
 */
final class ModularE2eStaging {

    /** Service-area square: 0..2000. All fixture nodes are inside (100..1000). */
    static final double AREA_SIZE = 2000.0;
    static final int FLEET_SIZE = 4;
    /** 1d baseline seat count (the capsule swap trades these seats for cargo volume). */
    static final int PAX_CAPACITY = 10;

    final Path shpFile;
    final Path drtNetFile;
    final Path clippedPlans;
    final Path fleetFile;
    final URL cfgUrl;
    final List<ModularFreightTour> tours;
    /** Task 1: plan-time accounting, computed the same way {@code SimulationRunnerUtils}' modular
     *  branch does (right after {@code convert}), so both e2e tests can pass it straight into
     *  {@code ModularDispatchModule}'s new ctor param. */
    final ModularPlanStats stats;

    private ModularE2eStaging(Path shpFile, Path drtNetFile, Path clippedPlans, Path fleetFile,
                               URL cfgUrl, List<ModularFreightTour> tours, ModularPlanStats stats) {
        this.shpFile = shpFile;
        this.drtNetFile = drtNetFile;
        this.clippedPlans = clippedPlans;
        this.fleetFile = fleetFile;
        this.cfgUrl = cfgUrl;
        this.tours = tours;
        this.stats = stats;
    }

    /**
     * Stages every raw input file exactly once under {@code dir} — raw network, raw plans,
     * service-area shapefile, depot csv, van type, LMD demand shapefile, the jsprit-routed carriers
     * (via {@link LausitzFreightPreprocessor#runModular}), and — via
     * {@link LausitzDrtPreprocessor#run} — the DRT-tagged network / clipped plans / fleet file —
     * then converts the routed carriers into dispatchable {@link ModularFreightTour}s against the
     * car + DRT networks. Returns the handles callers need to build their own {@code Scenario}s
     * from; callers that need multiple {@code Scenario}s (e.g. a byte-identity comparison across
     * two {@code Controler} runs) call {@code DrtScenarioBuilder.build} once per run against the
     * SAME returned file paths, so there is no staging step left that could itself introduce a
     * difference between runs.
     */
    static ModularE2eStaging stage(Path dir) throws Exception {
        Files.createDirectories(dir);

        // ---- shared raw fixtures (identical to MarriedBaselineEndToEndTest) ----
        Network rawNet = DrtE2eFixtures.buildGrid();
        Path rawNetFile = dir.resolve("raw_network.xml.gz");
        new NetworkWriter(rawNet).write(rawNetFile.toString());
        Path rawPlansFile = dir.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(DrtE2eFixtures.buildDemand(), rawPlansFile.toString());
        Path shpFile = dir.resolve("service-area.shp");
        DrtE2eFixtures.writeSquareShapefile(shpFile, AREA_SIZE);
        Path depotCsv = dir.resolve("depots.csv");
        Files.writeString(depotCsv, "provider;x;y;site\ndhl;500.0;500.0;wittichenau\n");

        // ---- freight side: van type (cost donor for the capsule) + tiny PANDA-like demand ----
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

        // ---- offline jsprit half: runModular (capsule type, 3.5 h cap, no waves) ----
        Path carriersOut = dir.resolve("modular_carriers_routed.xml");
        LausitzFreightPreprocessor.runModular(demandShp.toString(), depotCsv.toString(),
                rawNetFile.toString(), typesFile.toString(), carriersOut.toString(),
                /*jspritIterations*/ 1, shpFile.toString(), Modular.DEFAULT_MAX_TOUR_DURATION_S);
        assertThat(carriersOut).exists();

        // ---- DRT side: production preprocessor (drt-tagged net, person plans, fleet) ----
        Path drtNetFile = dir.resolve("drt_network.xml.gz");
        Path clippedPlans = dir.resolve("clipped_plans.xml.gz");
        Path fleetFile = dir.resolve("fleet.xml.gz");
        LausitzDrtPreprocessor.run(
                rawNetFile.toString(), rawPlansFile.toString(), shpFile.toString(),
                depotCsv.toString(), drtNetFile.toString(), clippedPlans.toString(),
                fleetFile.toString(), FLEET_SIZE, PAX_CAPACITY,
                /*serviceBegin*/ 0.0, /*serviceEnd*/ 86400.0);

        URL cfgUrl = ModularE2eStaging.class.getClassLoader()
                .getResource("lausitz-native-like.config.xml");
        assertThat(cfgUrl)
                .as("test fixture lausitz-native-like.config.xml must be on the test classpath")
                .isNotNull();

        // ---- tours: read the routed carriers, convert against the car + DRT networks ----
        Carriers routed = ModularTourConverter.read(carriersOut.toString(),
                ModularVehicleTypes.createCapsuleTypes(typesFile.toString()));
        Network carNet = LausitzFreightPreprocessor.carNetwork(
                NetworkUtils.readNetwork(rawNetFile.toString()));
        // Exactly how DvrpGlobalRoutingNetworkProvider builds the modal DVRP network the fleet's
        // Link references come from (TransportModeNetworkFilter on the dvrp networkModes, NO
        // cleaning) - so the tour link ids the splicer resolves are the injected network's own.
        Network drtNet = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(NetworkUtils.readNetwork(drtNetFile.toString()))
                .filter(drtNet, Set.of(TransportMode.drt));
        List<ModularFreightTour> tours = ModularTourConverter.convert(routed, carNet, drtNet);
        // Task 1: same call SimulationRunnerUtils' modular branch makes right after convert().
        ModularPlanStats stats = ModularTourConverter.planStats(routed, tours);

        return new ModularE2eStaging(shpFile, drtNetFile, clippedPlans, fleetFile, cfgUrl, tours, stats);
    }
}
