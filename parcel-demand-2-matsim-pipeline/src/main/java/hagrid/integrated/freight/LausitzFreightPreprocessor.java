package hagrid.integrated.freight;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;

import hagrid.utils.demand.Delivery;
import hagrid.utils.routing.HAGRIDRouterUtils;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.vehicles.VehicleType;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Lausitz dedicated-LMD preprocessor: PANDA demand + per-LSP synthetic depots -> one carrier per LSP
 * -> offline jsprit routing -> routed carrier XML. Reuses the MATSim freight API + van vehicle-types
 * data + the HAGRID service-duration model; deliberately bypasses the Hannover Guice pipeline
 * (region filter / PLZ split / lockers / supply / white-label).
 */
public final class LausitzFreightPreprocessor {

    /** HAGRID defaults (minutes), reused for comparability with the Hannover LMD. */
    private static final int DURATION_PER_PARCEL_MIN = 2;
    private static final int MAX_DURATION_PER_STOP_MIN = 15;

    /** Base seed for the per-provider missed-delivery RNG (deterministic, reproducible across runs). */
    private static final long MISSED_DELIVERY_SEED = 4711L;

    private LausitzFreightPreprocessor() {}

    /**
     * Derives the routable car sub-network from a (possibly network-with-pt) full network:
     * keeps only links allowing {@link TransportMode#car}, then runs {@link NetworkCleaner}
     * so only the largest strongly-connected car component survives.
     *
     * <p>Without this, depots and delivery points snap (via {@code getNearestLinkExactly}) onto
     * {@code pt_*}/{@code pt_regio_*} PT-only links, and every jsprit routing to/from them runs a
     * full failing SpeedyALT search → thousands of {@code "no route found"} warnings and a multi-hour
     * grind at higher jsprit iteration counts. Mirrors what {@code LausitzDrtPreprocessor} does for
     * the {@code drt} mode.
     */
    public static Network carNetwork(Network full) {
        Network car = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(full).filter(car, java.util.Set.of(TransportMode.car));
        new NetworkCleaner().run(car);
        return car;
    }

    public static void run(String demandShp, String depotCsv, String networkFile,
                           String vehicleTypesFile, String carriersOut, int jspritIterations) {
        // 1. network — route only on the connected car sub-network. The raw Lausitz network is a
        //    network-with-pt; leaving pt_*/pt_regio_* links in traps depot/service snapping and makes
        //    jsprit grind for hours on failing routes (see carNetwork()).
        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(networkFile);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Network network = carNetwork(scenario.getNetwork());
        ((MutableScenario) scenario).setNetwork(network);

        // 2. van vehicle types
        CarrierVehicleTypes vehicleTypes = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(vehicleTypes).readFile(vehicleTypesFile);
        VehicleType[] vans = vehicleTypes.getVehicleTypes().values().toArray(new VehicleType[0]);
        if (vans.length == 0) {
            throw new IllegalStateException("No van vehicle types loaded from " + vehicleTypesFile);
        }

        // 3. demand -> per-LSP deliveries ; depots -> per-LSP link
        Map<String, List<Delivery>> byProvider = LmdDemandReader.group(LmdDemandReader.read(demandShp));
        Map<String, Id<Link>> depots = LmdDepotLoader.load(depotCsv, network);

        // 4. one carrier per demanded LSP, anchored at its depot
        Carriers carriers = new Carriers();
        for (Map.Entry<String, List<Delivery>> e : byProvider.entrySet()) {
            String provider = e.getKey();
            Id<Link> depot = depots.get(provider);
            if (depot == null) {
                throw new IllegalStateException("No depot for provider with demand: " + provider);
            }
            // Per-provider deterministic RNG so the missed-delivery overlay is reproducible
            // independent of the (unordered) byProvider iteration order.
            Random missedRng = new Random(MISSED_DELIVERY_SEED + provider.hashCode());
            Carrier carrier = LmdCarrierBuilder.build(provider, e.getValue(), depot, network, vans,
                    DURATION_PER_PARCEL_MIN, MAX_DURATION_PER_STOP_MIN, missedRng);
            CarriersUtils.setJspritIterations(carrier, Math.max(1, jspritIterations));
            carriers.addCarrier(carrier);
        }

        // 5. route each carrier offline with HAGRID's custom jsprit algorithm (see routeWithDurationCap)
        routeWithDurationCap(carriers, network, vehicleTypes, jspritIterations);

        // 6. write the routed carriers (ensure the parent directory exists first)
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(carriersOut).getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create output directory for LMD carriers: " + carriersOut, e);
        }
        CarriersUtils.writeCarriers(carriers, carriersOut);
    }

    /**
     * Routes every carrier offline with HAGRID's custom jsprit algorithm
     * ({@link HAGRIDRouterUtils#configureAlgorithm}), mirroring {@code hagrid.demand.Router#routeCarrier}.
     *
     * <p>This is the decisive difference from the stock {@code CarriersUtils.runJsprit}: the custom
     * algorithm installs the {@link hagrid.simulation.MaxRouteDurationConstraint} (~7h hard cap on route
     * duration, {@code Priority.CRITICAL}) plus the driver-time time-window constraint. Without it the
     * only limit on a tour is the vehicle's operating window (08:00-20:00), so jsprit packs delivery
     * tours to ~12.5h — the unrealistic "12h shift" seen in the dashboard. With the cap, tours cluster
     * around the Hannover ~7.5-8h shift and jsprit adds vehicles (INFINITE fleet) instead of overlong tours.
     */
    static void routeWithDurationCap(Carriers carriers, Network network,
                                     CarrierVehicleTypes vehicleTypes, int jspritIterations) {
        NetworkBasedTransportCosts netBasedCosts = NetworkBasedTransportCosts.Builder
                .newInstance(network, vehicleTypes.getVehicleTypes().values())
                .setTimeSliceWidth(1800)
                .build();
        int iters = Math.max(1, jspritIterations);
        for (Carrier carrier : carriers.getCarriers().values()) {
            int serviceCount = carrier.getServices().size();
            VehicleRoutingProblem vrp = HAGRIDRouterUtils.createRoutingProblem(carrier, network, netBasedCosts);
            VehicleRoutingAlgorithm algorithm = HAGRIDRouterUtils.configureAlgorithm(vrp, serviceCount, iters);
            VehicleRoutingProblemSolution solution = Solutions.bestOf(algorithm.searchSolutions());
            CarrierPlan plan = MatsimJspritFactory.createPlan(solution);
            NetworkRouter.routePlan(plan, netBasedCosts);
            carrier.addPlan(plan);
            carrier.setSelectedPlan(plan);
        }
    }

    public static void main(String[] args) {
        if (args.length < 6) {
            throw new IllegalArgumentException(
                    "Usage: demandShp depotCsv networkFile vehicleTypesFile carriersOut jspritIterations");
        }
        run(args[0], args[1], args[2], args[3], args[4], Integer.parseInt(args[5]));
    }
}
