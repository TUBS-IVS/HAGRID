package hagrid.integrated.drt;

import hagrid.integrated.freight.LausitzFreightPreprocessor;
import hagrid.integrated.freight.LmdTestShapefiles;
import hagrid.integrated.modular.Modular;
import hagrid.integrated.modular.ModularDispatchModule;
import hagrid.integrated.modular.ModularFreightTour;
import hagrid.integrated.modular.ModularTourConverter;
import hagrid.integrated.modular.ModularVehicleTypes;
import hagrid.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.carriers.CarrierVehicleTypeWriter;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Control-arm proof of DRT_MODULAR (1d Task 12): {@code idleThreshold=1.0} makes the dispatch
 * gate {@code idleShare > idleThreshold} (STRICT) unsatisfiable — {@code idle.size()==fleetSize}
 * gives at best {@code 1.0 > 1.0}, which is false — so NO tour is ever dispatched and NO schedule
 * is ever spliced. This is the 1d analog of 1c's {@code chi->0} arm, but STRONGER (design §6): with
 * the gate never opening there is no splice and — unlike 1c — not even an extra population member,
 * so the passenger side must come out BYTE-IDENTICAL to a run with no modular module at all, not
 * merely "within noise".
 *
 * <p><b>Staging: once, not twice.</b> Every input file (raw network, raw plans, service-area
 * shapefile, depot csv, van type, LMD demand shapefile, the jsprit-routed carriers, and — via
 * {@link LausitzDrtPreprocessor#run} — the DRT-tagged network / clipped plans / fleet file) is
 * written to disk EXACTLY ONCE and then read by BOTH {@link DrtScenarioBuilder#build} calls below.
 * There is therefore no staging step left that could itself introduce a difference between the two
 * runs: the only thing that differs between run A and run B is whether
 * {@link ModularDispatchModule} is installed at all. (This also means the recipe is NOT sensitive
 * to whether {@code LausitzFreightPreprocessor.runModular}'s jsprit routing is itself deterministic —
 * it runs once, and its single output is fed to run A only.)</p>
 *
 * <p><b>Composition chosen: plain {@code installModules(Controler)} (1-arg), for BOTH runs</b> — the
 * same "plain baseline" composition {@code DrtBaselineEndToEndTest}/{@code MarriedBaselineEndToEndTest}
 * use, NOT {@code ModularEndToEndTest}'s 5-arg overload with {@link ReturnToDepotRebalancingModule}.
 * Deviation from the Task 10 recipe, recorded per the brief's instruction to say why: Task 10's three
 * deviations (300 m rebalancing cell, {@code returnStart=10:00}, five contention agents) all exist
 * to make the splicer/rebalancer COEXISTENCE property observable — machinery this test has no use
 * for and every additional moving part is a confound, not evidence, for a byte-identity proof. The
 * composed config's UNCONDITIONAL {@code RebalancingParams} (native to {@code DrtConfigComposer
 * .composeConfig}, 2000 m cell — see {@code DrtConfigComposer.java:50,86-110}) is left at its default:
 * with this fixture's network spanning 100..1000 the whole service area is ONE zone, so MinCostFlow
 * rebalancing already never has anything to move regardless, which is a virtue here, not a gap.</p>
 *
 * <p>Also NOT reused from Task 10: the five extra {@code person_c*} "contention" agents. Those exist
 * to give the D2 lockout a live insertion search to be tested against WHILE a vehicle is on freight;
 * with the gate never opening there is no freight excursion window for them to contend inside, so
 * they would be inert padding here. The stock {@link DrtE2eFixtures#buildDemand()} population (5
 * {@code person_*} drt-mode agents + 1 {@code pt} agent) is used as-is and already yields non-trivial
 * DRT legs and customer-stats content (verified below, not merely assumed).</p>
 */
@DisplayName("DRT_MODULAR control arm (idleThreshold=1.0, 1d Task 12)")
class ModularControlArmTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    private static final double AREA_SIZE = 2000.0;
    private static final int FLEET_SIZE = 4;
    /** 1d baseline seat count (the capsule swap trades these seats for cargo volume). */
    private static final int PAX_CAPACITY = 10;
    /** Same literal run id fed to BOTH runs (separate output directories) — see the class javadoc
     *  for why this is safe: {@code OutputDirectoryHierarchy} is a per-{@code Controler} instance
     *  keyed only by {@code config.controller().getOutputDirectory()}, which differs between A/B. */
    private static final String RUN_ID = "CONTROL_E2E";
    /** The control arm: {@code idleShare > idleThreshold} can be at most {@code 1.0 > 1.0} (all
     *  vehicles idle) which is false, so the gate never opens. Confirmed against
     *  {@code ModularTourDispatcher}'s literal condition, not merely the design doc. */
    private static final double IDLE_THRESHOLD_CONTROL = 1.0;

    @Test
    @DisplayName("theta=1.0: zero dispatches AND pax outputs byte-identical to a run without the module")
    void gateNeverOpensReproducesBaseline() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);

        // ================= STAGE ONCE (shared by both runs) =================
        Network rawNet = DrtE2eFixtures.buildGrid();
        Path rawNetFile = dir.resolve("raw_network.xml.gz");
        new NetworkWriter(rawNet).write(rawNetFile.toString());
        Path rawPlansFile = dir.resolve("raw_plans.xml.gz");
        PopulationUtils.writePopulation(DrtE2eFixtures.buildDemand(), rawPlansFile.toString());
        Path shpFile = dir.resolve("service-area.shp");
        DrtE2eFixtures.writeSquareShapefile(shpFile, AREA_SIZE);
        Path depotCsv = dir.resolve("depots.csv");
        Files.writeString(depotCsv, "provider;x;y\ndhl;500.0;500.0\n");

        // Freight side: van type (cost donor for the capsule) + tiny PANDA-like demand.
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

        // Offline jsprit half: ONE run, ONE routed-carriers file, consumed by run A only.
        Path carriersOut = dir.resolve("modular_carriers_routed.xml");
        LausitzFreightPreprocessor.runModular(demandShp.toString(), depotCsv.toString(),
                rawNetFile.toString(), typesFile.toString(), carriersOut.toString(),
                /*jspritIterations*/ 1, shpFile.toString(), Modular.DEFAULT_MAX_TOUR_DURATION_S);
        assertThat(carriersOut).exists();

        // DRT side: production preprocessor (drt-tagged net, person plans, fleet) — ONE set of
        // files, read by BOTH DrtScenarioBuilder.build calls below.
        Path drtNetFile = dir.resolve("drt_network.xml.gz");
        Path clippedPlans = dir.resolve("clipped_plans.xml.gz");
        Path fleetFile = dir.resolve("fleet.xml.gz");
        LausitzDrtPreprocessor.run(
                rawNetFile.toString(), rawPlansFile.toString(), shpFile.toString(),
                depotCsv.toString(), drtNetFile.toString(), clippedPlans.toString(),
                fleetFile.toString(), FLEET_SIZE, PAX_CAPACITY,
                /*serviceBegin*/ 0.0, /*serviceEnd*/ 86400.0);

        URL cfgUrl = getClass().getClassLoader().getResource("lausitz-native-like.config.xml");
        assertThat(cfgUrl)
                .as("test fixture lausitz-native-like.config.xml must be on the test classpath")
                .isNotNull();

        // Tours: read the routed carriers, convert against the car + DRT networks (Task 10 recipe).
        // Used by run A only — run B installs no modular module and never touches this list.
        Carriers routed = ModularTourConverter.read(carriersOut.toString(),
                ModularVehicleTypes.createCapsuleTypes(typesFile.toString()));
        Network carNet = LausitzFreightPreprocessor.carNetwork(
                NetworkUtils.readNetwork(rawNetFile.toString()));
        Network drtNet = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(NetworkUtils.readNetwork(drtNetFile.toString()))
                .filter(drtNet, Set.of(TransportMode.drt));
        List<ModularFreightTour> tours = ModularTourConverter.convert(routed, carNet, drtNet);

        // Ground truth: without this, "tours_dispatched == 0" would be vacuously true for a fixture
        // that produced no tours at all — proving nothing about the gate, only about an empty plan.
        int expectedTours = tours.size();
        int expectedParcels = tours.stream().mapToInt(ModularFreightTour::totalParcels).sum();
        assertThat(expectedTours).as("fixture must produce >=1 dispatchable tour").isGreaterThan(0);
        assertThat(expectedParcels).as("fixture must plan >=1 parcel").isGreaterThan(0);

        // ================= RUN A: idleThreshold=1.0 (gate never opens) =================
        Path matsimOutA = dir.resolve("matsimA");
        Scenario scenarioA = DrtScenarioBuilder.build(cfgUrl.toString(), drtNetFile.toString(),
                clippedPlans.toString(), shpFile.toString(), fleetFile.toString(),
                matsimOutA.toString(), RUN_ID, /*lastIteration*/ 1);
        DrtConfigGroup drtCfgA = MultiModeDrtConfigGroup.get(scenarioA.getConfig())
                .getModalElements().iterator().next();

        Controler controlerA = new Controler(scenarioA);
        DrtConfigComposer.installModules(controlerA);
        // LAST overriding module (SharedUseModule convention) — theta=1.0, the control arm.
        controlerA.addOverridingModule(
                new ModularDispatchModule(drtCfgA, tours, IDLE_THRESHOLD_CONTROL));
        controlerA.run();

        // ================= RUN B: no modular module at all (plain baseline composition) =========
        Path matsimOutB = dir.resolve("matsimB");
        Scenario scenarioB = DrtScenarioBuilder.build(cfgUrl.toString(), drtNetFile.toString(),
                clippedPlans.toString(), shpFile.toString(), fleetFile.toString(),
                matsimOutB.toString(), RUN_ID, /*lastIteration*/ 1);

        Controler controlerB = new Controler(scenarioB);
        DrtConfigComposer.installModules(controlerB);
        controlerB.run();

        // ================= (1) Run A dispatched NOTHING =================
        Path kpiCsvA = matsimOutA.resolve(RUN_ID + ".modular_tour_stats.csv");
        assertThat(kpiCsvA).as("KPI CSV must be written under the run-ID prefix").exists();
        Map<String, Double> statsA = readMetricCsv(kpiCsvA);

        // Not vacuous: tours_planned must be positive, else the zero-dispatch identities below
        // would be trivially satisfied by an empty plan rather than by the gate actually staying
        // shut on a real, non-empty tour list.
        assertThat(statsA.get("tours_planned")).as("tours_planned (ground truth: %s)", expectedTours)
                .isEqualTo((double) expectedTours).isGreaterThan(0.0);
        assertThat(statsA.get("parcels_planned")).as("parcels_planned (ground truth: %s)", expectedParcels)
                .isEqualTo((double) expectedParcels);

        assertThat(statsA.get("tours_dispatched")).as("tours_dispatched").isZero();
        assertThat(statsA.get("parcels_served")).as("parcels_served").isZero();
        assertThat(statsA.get("delta_parcels")).as("delta_parcels == parcels_planned (every parcel undelivered)")
                .isEqualTo(statsA.get("parcels_planned"));

        // Run B never installs the module at all: the KPI channel itself must not exist, not
        // merely report zeros — the stronger "no carriers" style guard from Task 10 (c).
        assertThat(matsimOutB.resolve(RUN_ID + ".modular_tour_stats.csv"))
                .as("run B installs no ModularDispatchModule - the KPI CSV must not exist at all")
                .doesNotExist();

        // ================= (2) bit-identical pax outputs =================
        Path legsA = matsimOutA.resolve(RUN_ID + ".output_drt_legs_drt.csv");
        Path legsB = matsimOutB.resolve(RUN_ID + ".output_drt_legs_drt.csv");
        Path custA = matsimOutA.resolve(RUN_ID + ".drt_customer_stats_drt.csv");
        Path custB = matsimOutB.resolve(RUN_ID + ".drt_customer_stats_drt.csv");

        // Non-vacuous: both files must actually carry data rows, not merely a header - an
        // empty-vs-empty (or headers-only) comparison would pass for a reason unrelated to the
        // property under test.
        assertNonTrivial(legsA, "output_drt_legs_drt.csv (run A)");
        assertNonTrivial(legsB, "output_drt_legs_drt.csv (run B)");
        assertNonTrivial(custA, "drt_customer_stats_drt.csv (run A)");
        assertNonTrivial(custB, "drt_customer_stats_drt.csv (run B)");

        assertThat(Files.mismatch(legsA, legsB))
                .as("output_drt_legs_drt.csv must be byte-identical between A (theta=1.0) and B (no module)")
                .isEqualTo(-1L);
        assertThat(Files.mismatch(custA, custB))
                .as("drt_customer_stats_drt.csv must be byte-identical between A (theta=1.0) and B (no module)")
                .isEqualTo(-1L);
    }

    // ------------------------------------------------------------------ helpers

    /** Reads a {@code metric;value} KPI CSV (the 1c extractor convention). */
    private static Map<String, Double> readMetricCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("metric;value");
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(";", 2);
            map.put(parts[0], Double.parseDouble(parts[1]));
        }
        return map;
    }

    /** Asserts a CSV has at least one data row beyond its header — guards against a vacuous
     *  byte-identity pass on two files that are trivially identical (both empty / headers-only). */
    private static void assertNonTrivial(Path csv, String label) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertThat(lines.size())
                .as(label + " must contain a header PLUS at least one data row (found %s lines)"
                        + " - an empty/headers-only file would make the byte-identity comparison"
                        + " vacuous", lines.size())
                .isGreaterThanOrEqualTo(2);
    }
}
