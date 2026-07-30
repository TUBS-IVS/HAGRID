package hagrid.integrated.freight;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;

import hagrid.integrated.modular.Modular;
import hagrid.integrated.modular.ModularVehicleTypes;
import hagrid.utils.GeoUtils;
import hagrid.utils.demand.Delivery;
import hagrid.utils.routing.HAGRIDRouterUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
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
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.vehicles.VehicleType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    private static final Logger LOG = LogManager.getLogger(LausitzFreightPreprocessor.class);

    /** Reused for the service-area point-in-polygon clip (mirrors {@code DrtNetworkPreparer}). */
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    /** HAGRID defaults (minutes), reused for comparability with the Hannover LMD. */
    private static final int DURATION_PER_PARCEL_MIN = 2;
    private static final int MAX_DURATION_PER_STOP_MIN = 15;

    /** Base seed for the per-provider missed-delivery RNG (deterministic, reproducible across runs). */
    private static final long MISSED_DELIVERY_SEED = 4711L;

    /**
     * Base seed for the per-provider tour-retime RNG ({@link LmdTourRetimer}). Separate from
     * {@link #MISSED_DELIVERY_SEED} so the retime draws never shift the missed-delivery overlay.
     */
    private static final long TOUR_RETIME_SEED = 20260730L;

    /**
     * Dispatch waves: mirrors HAGRID CEP {@code VehicleSchedule.SIMPLE_STAGGERED}.
     * One vehicle per van type is created at each hour so jsprit can assign morning
     * and afternoon demand to the appropriate wave instead of all starting at 08:00.
     */
    private static final List<Integer> DISPATCH_HOURS = List.of(8, 14);

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

    /** Backwards-compatible entry point: no service-area clip (delivers every PANDA segment). */
    public static void run(String demandShp, String depotCsv, String networkFile,
                           String vehicleTypesFile, String carriersOut, int jspritIterations) {
        run(demandShp, depotCsv, networkFile, vehicleTypesFile, carriersOut, jspritIterations, null);
    }

    /**
     * Routes the LMD carriers, optionally clipping the parcel demand to a service-area shapefile.
     *
     * <p>When {@code serviceAreaShp} is non-blank, deliveries whose drop point lies OUTSIDE the area
     * are dropped before routing, so the LMD delivery footprint matches the DRT service area exactly
     * (single source of truth — same shapefile both concepts use). Without it LMD delivers to every
     * PANDA segment incl. outliers (e.g. Ruhland) that the clipped DRT network cannot reach, which
     * makes the two baselines cover different geography. Pass {@code null}/blank to keep the legacy
     * "deliver everywhere" behaviour (e.g. standalone freight runs / tests).
     */
    public static void run(String demandShp, String depotCsv, String networkFile,
                           String vehicleTypesFile, String carriersOut, int jspritIterations,
                           String serviceAreaShp) {
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
        if (serviceAreaShp != null && !serviceAreaShp.isBlank()) {
            byProvider = clipToServiceArea(byProvider, serviceAreaShp);
        }
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
                    DURATION_PER_PARCEL_MIN, MAX_DURATION_PER_STOP_MIN, DISPATCH_HOURS, missedRng);
            CarriersUtils.setJspritIterations(carrier, Math.max(1, jspritIterations));
            carriers.addCarrier(carrier);
        }

        // 5. route each carrier offline with HAGRID's custom jsprit algorithm (see routeWithDurationCap),
        //    then stagger the INFINITE-fleet clone tours onto individual per-tour departures
        //    (LmdTourRetimer; root cause 2026-07-30: all clones of a template share one jittered second)
        routeWithDurationCap(carriers, network, vehicleTypes, jspritIterations,
                HAGRIDRouterUtils.MAXROUTEDURATION,
                c -> new Random(TOUR_RETIME_SEED + c.getId().toString().hashCode()));

        // 6. write the routed carriers (ensure the parent directory exists first)
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(carriersOut).getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create output directory for LMD carriers: " + carriersOut, e);
        }
        CarriersUtils.writeCarriers(carriers, carriersOut);
    }

    /**
     * DRT_MODULAR preprocessing (1d): identical demand/depot/clip pipeline as the LMD baseline,
     * but every carrier gets ONE vehicle type - the 216-parcel U-Shift cargo capsule -, the
     * jsprit route-duration cap is the Modular tour cap (design D5: 12600s default, 25200s
     * control arm) instead of the 7h driver shift, and there are NO dispatch waves (plan C4
     * revised): one un-jittered vehicle template per carrier with the full delivery-day window
     * 07:30-21:00, service-start TWs aligned to the same interval.
     */
    public static void runModular(String demandShp, String depotCsv, String networkFile,
                                  String vanTypesFile, String carriersOut, int jspritIterations,
                                  String serviceAreaShp, int maxTourDurationSeconds) {
        // 1. network — same car sub-network derivation as run() (see carNetwork()).
        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(networkFile);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Network network = carNetwork(scenario.getNetwork());
        ((MutableScenario) scenario).setNetwork(network);

        // 2. capsule vehicle type — the ONLY delta of substance vs. run()'s van types.
        CarrierVehicleTypes capsuleTypes = ModularVehicleTypes.createCapsuleTypes(vanTypesFile);
        VehicleType[] capsuleArr = capsuleTypes.getVehicleTypes().values().toArray(new VehicleType[0]);

        // 3. demand -> per-LSP deliveries ; depots -> per-LSP link
        Map<String, List<Delivery>> byProvider = LmdDemandReader.group(LmdDemandReader.read(demandShp));
        if (serviceAreaShp != null && !serviceAreaShp.isBlank()) {
            byProvider = clipToServiceArea(byProvider, serviceAreaShp);
        }
        Map<String, Id<Link>> depots = LmdDepotLoader.load(depotCsv, network);

        // 4. one carrier per demanded LSP, anchored at its depot; single un-jittered vehicle
        //    template per carrier spanning the full delivery day (no dispatch waves, see
        //    LmdCarrierBuilder#buildSingleWindow).
        Carriers carriers = new Carriers();
        for (Map.Entry<String, List<Delivery>> e : byProvider.entrySet()) {
            String provider = e.getKey();
            Id<Link> depot = depots.get(provider);
            if (depot == null) {
                throw new IllegalStateException("No depot for provider with demand: " + provider);
            }
            Random missedRng = new Random(MISSED_DELIVERY_SEED + provider.hashCode());
            Carrier carrier = LmdCarrierBuilder.buildSingleWindow(provider, e.getValue(), depot,
                    network, capsuleArr, DURATION_PER_PARCEL_MIN, MAX_DURATION_PER_STOP_MIN,
                    missedRng,
                    Modular.DELIVERY_DAY_START_S, Modular.DELIVERY_DAY_END_S,
                    Modular.DELIVERY_DAY_START_S, Modular.DELIVERY_DAY_END_S);
            CarriersUtils.setJspritIterations(carrier, Math.max(1, jspritIterations));
            carriers.addCarrier(carrier);
        }

        // 5. route each carrier offline, capped at the Modular tour duration (not the 7h shift)
        routeWithDurationCap(carriers, network, capsuleTypes, jspritIterations, maxTourDurationSeconds);

        // 6. write the routed carriers (ensure the parent directory exists first)
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(carriersOut).getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create output directory for modular carriers: "
                    + carriersOut, e);
        }
        CarriersUtils.writeCarriers(carriers, carriersOut);
    }

    /**
     * Keeps only deliveries whose drop point lies inside the service-area geometry, mirroring
     * {@code PopulationClipper} / {@code DrtNetworkPreparer} so the LMD delivery footprint matches the
     * DRT service area exactly. Providers left with no in-area demand are dropped so no empty carrier
     * is built downstream.
     */
    static Map<String, List<Delivery>> clipToServiceArea(Map<String, List<Delivery>> byProvider,
                                                         String serviceAreaShp) {
        Geometry area = GeoUtils.getBoundaryGeometry(GeoFileReader.getAllFeatures(serviceAreaShp));
        return clipToArea(byProvider, area);
    }

    /** Geometry-level clip (no file I/O) — see {@link #clipToServiceArea(Map, String)}. */
    static Map<String, List<Delivery>> clipToArea(Map<String, List<Delivery>> byProvider, Geometry area) {
        Map<String, List<Delivery>> clipped = new LinkedHashMap<>();
        int before = 0;
        int after = 0;
        for (Map.Entry<String, List<Delivery>> e : byProvider.entrySet()) {
            List<Delivery> kept = new ArrayList<>();
            for (Delivery d : e.getValue()) {
                before++;
                Coord c = d.getCoordinate();
                if (area.contains(GEOMETRY_FACTORY.createPoint(new Coordinate(c.getX(), c.getY())))) {
                    kept.add(d);
                    after++;
                }
            }
            if (!kept.isEmpty()) {
                clipped.put(e.getKey(), kept);
            }
        }
        LOG.info("LMD demand clipped to service area: {} -> {} deliveries ({} dropped outside area).",
                before, after, before - after);
        return clipped;
    }

    /**
     * The carriers {@link #routeWithDurationCap} actually routes: all of them by default, or the one
     * selected by {@link HAGRIDRouterUtils#JSPRIT_ONLY_CARRIER_PROPERTY} (exact id or
     * {@code largest}) when that diagnostic switch is set. See the property's javadoc for why a
     * single-carrier run is a valid probe and why its output must not feed a comparison.
     *
     * @throws IllegalArgumentException if the switch is set but matches no carrier — routing nothing
     *                                  would silently produce an empty carrier file
     */
    static Collection<Carrier> selectCarriersToRoute(Carriers carriers) {
        String raw = System.getProperty(HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return carriers.getCarriers().values();
        }
        String wanted = raw.trim();
        // Materialise before mutating the container below - getCarriers().values() is a live view.
        List<Carrier> all = List.copyOf(carriers.getCarriers().values());

        Carrier picked;
        if (HAGRIDRouterUtils.ONLY_CARRIER_LARGEST.equalsIgnoreCase(wanted)) {
            // Tie-break on the id so "largest" is deterministic even with equal service counts.
            picked = all.stream()
                    .max(Comparator.comparingInt((Carrier c) -> c.getServices().size())
                            .thenComparing(c -> c.getId().toString()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY + "=" + wanted
                                    + " but there are no carriers to choose from"));
        } else {
            picked = all.stream()
                    .filter(c -> c.getId().toString().equals(wanted))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY + "=" + wanted
                                    + " matches no carrier. Available: "
                                    + all.stream().map(c -> c.getId().toString()).sorted().toList()));
        }

        // Drop the others from the container as well. writeCarriers() persists whatever is in it, so
        // leaving 6 plan-less carriers next to 1 routed one would (a) read downstream as a complete
        // run and (b) hand the CarrierModule carriers without a selected plan. One carrier instead of
        // seven makes the incompleteness structurally obvious.
        for (Carrier c : all) {
            if (!c.getId().equals(picked.getId())) {
                carriers.getCarriers().remove(c.getId());
            }
        }
        LOG.warn("DIAGNOSTIC {}={}: routing ONLY carrier '{}' ({} services) and DROPPING {} other "
                        + "carrier(s) from the run. The resulting carrier file is INCOMPLETE - it must "
                        + "not feed a scenario comparison or any reported KPI.",
                HAGRIDRouterUtils.JSPRIT_ONLY_CARRIER_PROPERTY, wanted, picked.getId(),
                picked.getServices().size(), all.size() - 1);
        return List.of(picked);
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
        routeWithDurationCap(carriers, network, vehicleTypes, jspritIterations,
                HAGRIDRouterUtils.MAXROUTEDURATION);
    }

    /**
     * Same as {@link #routeWithDurationCap(Carriers, Network, CarrierVehicleTypes, int)} but with an
     * explicit route-duration cap (seconds), e.g. the 1d DRT_MODULAR path's shorter 12600s (3.5h)
     * capsule-swap tour cap instead of the 25200s (7h) driver-shift default. No tour retiming
     * (modular + legacy callers keep their departures exactly as jsprit scheduled them).
     */
    static void routeWithDurationCap(Carriers carriers, Network network, CarrierVehicleTypes vehicleTypes,
                                     int jspritIterations, int maxRouteDurationSeconds) {
        routeWithDurationCap(carriers, network, vehicleTypes, jspritIterations, maxRouteDurationSeconds,
                null);
    }

    /**
     * Same as {@link #routeWithDurationCap(Carriers, Network, CarrierVehicleTypes, int, int)} plus an
     * optional per-carrier RNG factory for {@link LmdTourRetimer}: when non-null, every routed plan's
     * wave-template clone tours are staggered onto individual per-tour departures and the plan is
     * re-routed so leg times stay consistent with the shifted departures. LMD_BASELINE {@code run()}
     * passes the factory; the modular path deliberately does not (no waves there by design C4).
     */
    static void routeWithDurationCap(Carriers carriers, Network network, CarrierVehicleTypes vehicleTypes,
                                     int jspritIterations, int maxRouteDurationSeconds,
                                     java.util.function.Function<Carrier, Random> tourRetimeRng) {
        NetworkBasedTransportCosts netBasedCosts = NetworkBasedTransportCosts.Builder
                .newInstance(network, vehicleTypes.getVehicleTypes().values())
                .setTimeSliceWidth(1800)
                .build();
        int iters = Math.max(1, jspritIterations);
        for (Carrier carrier : selectCarriersToRoute(carriers)) {
            int serviceCount = carrier.getServices().size();
            VehicleRoutingProblem vrp = HAGRIDRouterUtils.createRoutingProblem(carrier, network, netBasedCosts);
            VehicleRoutingAlgorithm algorithm = HAGRIDRouterUtils.configureAlgorithm(
                    vrp, serviceCount, iters, null, 0.0, maxRouteDurationSeconds);
            VehicleRoutingProblemSolution solution = Solutions.bestOf(algorithm.searchSolutions());
            recordUnassignedJobs(carrier, solution);
            CarrierPlan plan = MatsimJspritFactory.createPlan(solution);
            NetworkRouter.routePlan(plan, netBasedCosts);
            if (tourRetimeRng != null) {
                LmdTourRetimer.retime(carrier, plan, tourRetimeRng.apply(carrier), maxRouteDurationSeconds);
                NetworkRouter.routePlan(plan, netBasedCosts);
            }
            carrier.addPlan(plan);
            carrier.setSelectedPlan(plan);
        }
    }

    /**
     * Persists the jobs the best jsprit solution could NOT insert (e.g. infeasible under the 7h
     * {@code MaxRouteDurationConstraint}, the 20:00 vehicle end, or a stop demand exceeding every van
     * capacity) as carrier attributes, so they surface as a dashboard KPI instead of silently
     * disappearing from the tours while {@code numberOfParcels} still counts them as attempted.
     * Mirrors the legacy {@code Router} which logs {@code getUnassignedJobs()} per iteration.
     */
    private static void recordUnassignedJobs(Carrier carrier, VehicleRoutingProblemSolution solution) {
        HAGRIDRouterUtils.recordUnassignedJobs(carrier,
                solution.getUnassignedJobs().stream()
                        .map(com.graphhopper.jsprit.core.problem.job.Job::getId)
                        .toList());
    }

    public static void main(String[] args) {
        if (args.length < 6) {
            throw new IllegalArgumentException(
                    "Usage: demandShp depotCsv networkFile vehicleTypesFile carriersOut jspritIterations");
        }
        run(args[0], args[1], args[2], args[3], args[4], Integer.parseInt(args[5]));
    }
}
