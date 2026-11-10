package hagrid.lausitz.freight;


import hagrid.lausitz.DeliveryDistrictBuilder;
import hagrid.lausitz.DepotNetwork;
import hagrid.lausitz.drt.DrtDepotReader;
import hagrid.lausitz.modular.Modular;
import hagrid.lausitz.modular.ModularVehicleTypes;
import hagrid.core.util.GeoUtils;
import hagrid.core.util.Delivery;
import hagrid.core.routing.HAGRIDRouterUtils;
import hagrid.core.routing.CarrierBatchExecutor;
import hagrid.core.routing.CarrierModeResolver;
import hagrid.core.routing.CarrierOptimizationService;
import hagrid.core.routing.NativeModalTransportCosts;
import hagrid.core.routing.PlanValidator;
import hagrid.core.simulation.CarrierInputValidator;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToLongFunction;
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
import org.matsim.vehicles.VehicleType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        run(demandShp, depotCsv, networkFile, vehicleTypesFile, carriersOut, jspritIterations,
                serviceAreaShp, null);
    }

    /**
     * Same as {@link #run(String, String, String, String, String, int, String)} with the jsprit
     * result cache ({@link JspritPlanCache}, spec 2026-10-01): steps 1-4 always run, steps 5-6
     * (jsprit routing and writing) are skipped when this machine already computed exactly this
     * result. {@code cacheDir == null} turns the cache off.
     *
     * @return what the cache did, for {@code run_metadata.json}
     */
    public static JspritCacheResult run(String demandShp, String depotCsv, String networkFile,
                                        String vehicleTypesFile, String carriersOut, int jspritIterations,
                                        String serviceAreaShp, Path cacheDir) {
        return run(demandShp,depotCsv,networkFile,vehicleTypesFile,carriersOut,jspritIterations,
                serviceAreaShp,cacheDir,hagrid.core.config.ResolvedHagridConfig.Resources.defaults());
    }

    public static JspritCacheResult run(String demandShp, String depotCsv, String networkFile,
                                        String vehicleTypesFile, String carriersOut, int jspritIterations,
                                        String serviceAreaShp, Path cacheDir,
                                        hagrid.core.config.ResolvedHagridConfig.Resources resources) {
        return run(demandShp,depotCsv,networkFile,vehicleTypesFile,carriersOut,jspritIterations,
                serviceAreaShp,cacheDir,resources,hagrid.core.config.RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS);
    }

    public static JspritCacheResult run(String demandShp, String depotCsv, String networkFile,
                                        String vehicleTypesFile, String carriersOut, int jspritIterations,
                                        String serviceAreaShp, Path cacheDir,
                                        hagrid.core.config.ResolvedHagridConfig.Resources resources,
                                        int solverMaxRouteDurationSeconds) {
        return runConfigured(demandShp,depotCsv,networkFile,vehicleTypesFile,carriersOut,jspritIterations,
                serviceAreaShp,cacheDir,resources,LmdTourRules.defaults(LmdPreprocessInputs.Variant.BASELINE,solverMaxRouteDurationSeconds));
    }
    public static JspritCacheResult runConfigured(String demandShp,String depotCsv,String networkFile,String vehicleTypesFile,
            String carriersOut,int jspritIterations,String serviceAreaShp,Path cacheDir,hagrid.core.config.ResolvedHagridConfig config) {
        return runConfigured(demandShp,depotCsv,networkFile,vehicleTypesFile,carriersOut,jspritIterations,
                serviceAreaShp,cacheDir,config.resources(),LmdTourRules.from(config));
    }
    private static JspritCacheResult runConfigured(String demandShp,String depotCsv,String networkFile,String vehicleTypesFile,
            String carriersOut,int jspritIterations,String serviceAreaShp,Path cacheDir,
            hagrid.core.config.ResolvedHagridConfig.Resources resources,LmdTourRules rules) {
        JspritPlanCache cache = JspritPlanCache.fromSystem(cacheDir);   // a typo in -Dhagrid.jsprit.cache fails here
        LmdPreprocessInputs in = LmdPreprocessInputs.baseline(demandShp, depotCsv, networkFile,
                vehicleTypesFile, jspritIterations, serviceAreaShp).withResources(resources)
                .withTourRules(rules);
        // hashed BEFORE step 1 reads anything; produce() hashes again and refuses the cache on a change
        JspritCacheKey keyBeforeReading = cache.keyBeforeReading(in);

        // 1. network — route only on the connected car sub-network. The raw Lausitz network is a
        //    network-with-pt; leaving pt_*/pt_regio_* links in traps depot/service snapping and makes
        //    jsprit grind for hours on failing routes (see carNetwork()).
        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(in.network().toString());
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Network network = carNetwork(scenario.getNetwork());
        ((MutableScenario) scenario).setNetwork(network);

        // 2. van vehicle types
        CarrierVehicleTypes vehicleTypes = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(vehicleTypes).readFile(in.vehicleTypes().toString());
        VehicleType[] vans = vehicleTypes.getVehicleTypes().values().toArray(new VehicleType[0]);
        if (vans.length == 0) {
            throw new IllegalStateException("No van vehicle types loaded from " + in.vehicleTypes());
        }

        // 3. demand -> per-LSP deliveries ; depots -> per-LSP link
        Map<String, List<Delivery>> byProvider = LmdDemandReader.group(LmdDemandReader.read(in.demandShp().toString()));
        if (in.serviceAreaShp() != null) {
            byProvider = clipToServiceArea(byProvider, in.serviceAreaShp().toString());
        }
        Map<String, Id<Link>> depots = LmdDepotLoader.load(in.depotCsv().toString(), network);

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
            Random missedRng = new Random(in.tourRules().planning().missedDeliverySeed() + provider.hashCode());
            Carrier carrier = LmdCarrierBuilder.build(provider, e.getValue(), depot, network, vans,
                    in.tourRules().durationPerParcelMinutes(), in.tourRules().maxDurationPerStopMinutes(), in.tourRules().planning().dispatchHours(), missedRng, in.routeDurationSeconds(),
                    in.tourRules().vehicleAvailabilityBufferSeconds(),in.tourRules().vehicleDayEndSeconds(),
                    in.tourRules().deliveryWindowStartSeconds(),in.tourRules().deliveryWindowEndSeconds(), in.tourRules().planning());
            CarriersUtils.setJspritIterations(carrier, Math.max(1, in.jspritIterations()));
            carriers.addCarrier(carrier);
        }

        // 5.+6. route each carrier offline with HAGRID's custom jsprit algorithm (see routeWithDurationCap),
        //    stagger the INFINITE-fleet clone tours onto individual per-tour departures (LmdTourRetimer;
        //    root cause 2026-07-30: all clones of a template share one jittered second) and write the
        //    routed carriers - or, on a cache hit, read the cached file at this very point and copy it.
        try {
            return cache.produce(in, keyBeforeReading, Path.of(carriersOut),
                    cached -> LmdCachedPlanValidator.replay(cached, carriers, vehicleTypes, network, in.routeDurationSeconds(), true,in.tourRules().vehicleAvailabilityBufferSeconds(),in.tourRules().vehicleDayEndSeconds()),
                    target -> {
                        routeWithRules(carriers, network, vehicleTypes, in.jspritIterations(),
                                in.tourRules(),
                                c -> new Random(in.tourRules().planning().tourRetimeSeed() + c.getId().toString().hashCode()), in.resources());
                        writeRouted(carriers, target, "LMD carriers");
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("LMD preprocessing failed for " + carriersOut, e);
        }
    }

    /**
     * Backwards-compatible entry point: all depots stay open and districts are capped at the
     * default 300 jobs (INTEGRATED (1c/1d) default, spec 2026-08-17). See
     * {@link #runModular(String, String, String, String, String, int, String, int, List, int)}.
     */
    public static void runModular(String demandShp, String depotCsv, String networkFile,
                                  String vanTypesFile, String carriersOut, int jspritIterations,
                                  String serviceAreaShp, int maxTourDurationSeconds) {
        runModular(demandShp, depotCsv, networkFile, vanTypesFile, carriersOut, jspritIterations,
                serviceAreaShp, maxTourDurationSeconds, null, 300);
    }

    /**
     * DRT_MODULAR preprocessing (1d): identical demand/clip pipeline as the LMD baseline, but the
     * demand is pooled into delivery DISTRICTS (one district per open depot's catchment, split at
     * {@code maxJobsPerDistrict} — see {@link DeliveryDistrictBuilder}) instead of one carrier per
     * LSP, so parcels are picked up at the depot nearest their delivery segment rather than at
     * their own provider's depot (spec 2026-08-17, D2/D7). Every carrier still gets ONE vehicle
     * type - the 216-parcel U-Shift cargo capsule -, the jsprit route-duration cap is the Modular
     * explicit tour cap (common default: seven hours), and
     * there are NO dispatch waves (plan C4 revised): one un-jittered vehicle template per carrier
     * with the full delivery-day window 07:30-21:00, service-start TWs aligned to the same
     * interval.
     *
     * @param openDepots        site names (see {@link DrtDepotReader#readBySite}) that stay open;
     *                          {@code null}/empty opens every depot in {@code depotCsv}
     * @param maxJobsPerDistrict job ceiling per district before a catchment is split
     */
    public static void runModular(String demandShp, String depotCsv, String networkFile,
                                  String vanTypesFile, String carriersOut, int jspritIterations,
                                  String serviceAreaShp, int maxTourDurationSeconds,
                                  List<String> openDepots, int maxJobsPerDistrict) {
        runModular(demandShp, depotCsv, networkFile, vanTypesFile, carriersOut, jspritIterations,
                serviceAreaShp, maxTourDurationSeconds, openDepots, maxJobsPerDistrict, null);
    }

    /**
     * Same as {@link #runModular(String, String, String, String, String, int, String, int, List, int)}
     * with the jsprit result cache ({@link JspritPlanCache}); {@code cacheDir == null} turns it off.
     *
     * @return what the cache did, for {@code run_metadata.json}
     */
    public static JspritCacheResult runModular(String demandShp, String depotCsv, String networkFile,
                                               String vanTypesFile, String carriersOut, int jspritIterations,
                                               String serviceAreaShp, int maxTourDurationSeconds,
                                               List<String> openDepots, int maxJobsPerDistrict, Path cacheDir) {
        return runModular(demandShp,depotCsv,networkFile,vanTypesFile,carriersOut,jspritIterations,
                serviceAreaShp,maxTourDurationSeconds,openDepots,maxJobsPerDistrict,cacheDir,
                hagrid.core.config.ResolvedHagridConfig.Resources.defaults());
    }

    public static JspritCacheResult runModular(String demandShp, String depotCsv, String networkFile,
                                               String vanTypesFile, String carriersOut, int jspritIterations,
                                               String serviceAreaShp, int maxTourDurationSeconds,
                                               List<String> openDepots, int maxJobsPerDistrict, Path cacheDir,
                                               hagrid.core.config.ResolvedHagridConfig.Resources resources) {
        return runModularConfigured(demandShp,depotCsv,networkFile,vanTypesFile,carriersOut,jspritIterations,serviceAreaShp,
                maxTourDurationSeconds,openDepots,maxJobsPerDistrict,cacheDir,resources,
                LmdTourRules.defaults(LmdPreprocessInputs.Variant.MODULAR,maxTourDurationSeconds));
    }
    public static JspritCacheResult runModularConfigured(String demandShp,String depotCsv,String networkFile,String vanTypesFile,
            String carriersOut,int jspritIterations,String serviceAreaShp,List<String> openDepots,int maxJobsPerDistrict,
            Path cacheDir,hagrid.core.config.ResolvedHagridConfig config) {
        var rules=LmdTourRules.from(config);
        return runModularConfigured(demandShp,depotCsv,networkFile,vanTypesFile,carriersOut,jspritIterations,serviceAreaShp,
                rules.maxRouteDurationSeconds(),openDepots,maxJobsPerDistrict,cacheDir,config.resources(),rules);
    }
    private static JspritCacheResult runModularConfigured(String demandShp,String depotCsv,String networkFile,String vanTypesFile,
            String carriersOut,int jspritIterations,String serviceAreaShp,int maxTourDurationSeconds,List<String> openDepots,
            int maxJobsPerDistrict,Path cacheDir,hagrid.core.config.ResolvedHagridConfig.Resources resources,LmdTourRules rules) {
        JspritPlanCache cache = JspritPlanCache.fromSystem(cacheDir);
        LmdPreprocessInputs in = LmdPreprocessInputs.modular(demandShp, depotCsv, networkFile, vanTypesFile,
                jspritIterations, serviceAreaShp, maxTourDurationSeconds, openDepots, maxJobsPerDistrict).withResources(resources).withTourRules(rules);
        JspritCacheKey keyBeforeReading = cache.keyBeforeReading(in);   // before step 1, see run()

        // 1. network — same car sub-network derivation as run() (see carNetwork()).
        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(in.network().toString());
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Network network = carNetwork(scenario.getNetwork());
        ((MutableScenario) scenario).setNetwork(network);

        // 2. capsule vehicle type — the ONLY delta of substance vs. run()'s van types.
        CarrierVehicleTypes capsuleTypes = ModularVehicleTypes.createCapsuleTypes(in.vehicleTypes().toString());
        VehicleType[] capsuleArr = capsuleTypes.getVehicleTypes().values().toArray(new VehicleType[0]);

        // 3. demand -> pooled stops in districts anchored at the nearest OPEN depot
        Map<String, List<Delivery>> byProvider = LmdDemandReader.group(LmdDemandReader.read(in.demandShp().toString()));
        if (in.serviceAreaShp() != null) {
            byProvider = clipToServiceArea(byProvider, in.serviceAreaShp().toString());
        }
        List<Delivery> all = byProvider.values().stream().flatMap(List::stream).toList();
        Map<String, Coord> depotCoords = DrtDepotReader.readBySite(in.depotCsv());
        List<DepotNetwork.Depot> open =
                DeliveryDistrictBuilder.selectOpenDepots(depotCoords, in.openDepots());
        List<DeliveryDistrictBuilder.District> districts =
                DeliveryDistrictBuilder.build(all, open, in.maxJobsPerDistrict());

        // 4. one carrier per DISTRICT (not per LSP), anchored at the district's depot
        Carriers carriers = new Carriers();
        for (DeliveryDistrictBuilder.District district : districts) {
            Link depotLink = NetworkUtils.getNearestLinkExactly(network, district.depot().coord());
            Random missedRng = new Random(in.tourRules().planning().missedDeliverySeed() + district.id().hashCode());
            Carrier carrier = LmdCarrierBuilder.buildDistrict(district.id(), district.stops(),
                    depotLink.getId(), network, capsuleArr,
                    in.tourRules().durationPerParcelMinutes(), in.tourRules().maxDurationPerStopMinutes(), missedRng,
                    in.tourRules().deliveryWindowStartSeconds(), in.tourRules().vehicleDayEndSeconds(),
                    in.tourRules().deliveryWindowStartSeconds(), in.tourRules().deliveryWindowEndSeconds(), in.tourRules().planning());
            CarriersUtils.setJspritIterations(carrier, Math.max(1, in.jspritIterations()));
            carriers.addCarrier(carrier);
        }

        // 5.+6. route each carrier offline, capped at the Modular tour duration (not the 7h shift),
        //    and write - or, on a cache hit, read the cached file at this point and copy it.
        try {
            return cache.produce(in, keyBeforeReading, Path.of(carriersOut),
                    cached -> LmdCachedPlanValidator.replay(cached, carriers, capsuleTypes, network, in.maxTourDurationSeconds(), false,in.tourRules().vehicleAvailabilityBufferSeconds(),in.tourRules().vehicleDayEndSeconds()),
                    target -> {
                        routeWithRules(carriers, network, capsuleTypes, in.jspritIterations(),
                                in.tourRules(), null, in.resources());
                        writeRouted(carriers, target, "modular carriers");
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Modular LMD preprocessing failed for " + carriersOut, e);
        }
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

    /**
     * Geometry-level clip (no file I/O) — see {@link #clipToServiceArea(Map, String)}. Public so
     * both INTEGRATED arms (1c {@code ParcelAgentGenerator}/{@code LausitzDrtPreprocessor} and 1d
     * {@code runModular}) clip identically before districting (spec 2026-08-17 D9).
     */
    public static Map<String, List<Delivery>> clipToArea(Map<String, List<Delivery>> byProvider, Geometry area) {
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
     * ({@link HAGRIDRouterUtils#configureAlgorithm}), mirroring {@code hagrid.hannover.demand.Router#routeCarrier}.
     *
     * <p>This is the decisive difference from the stock {@code CarriersUtils.runJsprit}: the custom
     * algorithm installs the {@link hagrid.core.routing.MaxRouteDurationConstraint} (~7h hard cap on route
     * duration, {@code Priority.CRITICAL}) plus the driver-time time-window constraint. Without it the
     * only limit on a tour is the vehicle's operating window (08:00-20:00), so jsprit packs delivery
     * tours to ~12.5h — the unrealistic "12h shift" seen in the dashboard. With the cap, tours cluster
     * around the Hannover ~7.5-8h shift and jsprit adds vehicles (INFINITE fleet) instead of overlong tours.
     */
    static void routeWithDurationCap(Carriers carriers, Network network,
                                     CarrierVehicleTypes vehicleTypes, int jspritIterations) {
        routeWithDurationCap(carriers, network, vehicleTypes, jspritIterations,
                hagrid.core.config.RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS);
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
        routeWithDurationCap(carriers,network,vehicleTypes,jspritIterations,maxRouteDurationSeconds,tourRetimeRng,
                hagrid.core.config.ResolvedHagridConfig.Resources.defaults());
    }

    static void routeWithDurationCap(Carriers carriers, Network network, CarrierVehicleTypes vehicleTypes,
                                     int jspritIterations, int maxRouteDurationSeconds,
                                     Function<Carrier, Random> tourRetimeRng,
                                     hagrid.core.config.ResolvedHagridConfig.Resources resources) {
        routeWithRules(carriers,network,vehicleTypes,jspritIterations,
                LmdTourRules.defaults(LmdPreprocessInputs.Variant.BASELINE,maxRouteDurationSeconds),tourRetimeRng,resources);
    }
    static void routeWithRules(Carriers carriers,Network network,CarrierVehicleTypes vehicleTypes,int jspritIterations,
            LmdTourRules rules,Function<Carrier,Random> tourRetimeRng,hagrid.core.config.ResolvedHagridConfig.Resources resources) {
        int maxRouteDurationSeconds=rules.maxRouteDurationSeconds();
        try (var batches = new CarrierBatchExecutor()) {
            prepareInitialGroup(carriers, network, vehicleTypes, jspritIterations,
                    maxRouteDurationSeconds, tourRetimeRng, batches, carrier -> 0L, resources,rules);
        }
    }

    /** Explicit resource policy supplied by the run owner; this overload does not close its executor. */
    static void routeWithDurationCap(Carriers carriers, Network network, CarrierVehicleTypes vehicleTypes,
                                     int jspritIterations, int maxRouteDurationSeconds,
                                     Function<Carrier, Random> tourRetimeRng,
                                     CarrierBatchExecutor batches, ToLongFunction<Carrier> reservedBytes) {
        routeWithDurationCap(carriers,network,vehicleTypes,jspritIterations,maxRouteDurationSeconds,tourRetimeRng,
                batches,reservedBytes,hagrid.core.config.ResolvedHagridConfig.Resources.defaults());
    }

    static void routeWithDurationCap(Carriers carriers, Network network, CarrierVehicleTypes vehicleTypes,
                                     int jspritIterations, int maxRouteDurationSeconds,
                                     Function<Carrier, Random> tourRetimeRng, CarrierBatchExecutor batches,
                                     ToLongFunction<Carrier> reservedBytes,
                                     hagrid.core.config.ResolvedHagridConfig.Resources resources) {
        prepareInitialGroup(carriers, network, vehicleTypes, jspritIterations,
                maxRouteDurationSeconds, tourRetimeRng, batches, reservedBytes, resources);
    }

    private static void prepareInitialGroup(
            Carriers carriers, Network network, CarrierVehicleTypes vehicleTypes, int jspritIterations,
            int maxRouteDurationSeconds, Function<Carrier, Random> tourRetimeRng,
            CarrierBatchExecutor batches, ToLongFunction<Carrier> reservedBytes,
            hagrid.core.config.ResolvedHagridConfig.Resources resources) {
        prepareInitialGroup(carriers,network,vehicleTypes,jspritIterations,maxRouteDurationSeconds,tourRetimeRng,
                batches,reservedBytes,resources,LmdTourRules.defaults(LmdPreprocessInputs.Variant.BASELINE,maxRouteDurationSeconds));
    }
    private static void prepareInitialGroup(Carriers carriers,Network network,CarrierVehicleTypes vehicleTypes,int jspritIterations,
            int maxRouteDurationSeconds,Function<Carrier,Random> tourRetimeRng,CarrierBatchExecutor batches,
            ToLongFunction<Carrier> reservedBytes,hagrid.core.config.ResolvedHagridConfig.Resources resources,LmdTourRules rules) {
        var selected = List.copyOf(selectCarriersToRoute(carriers));
        CarrierInputValidator.validate(new Carriers(selected), vehicleTypes, network);
        var metadata = new LinkedHashMap<String, Carrier>();
        var descriptors = new ArrayList<CarrierBatchExecutor.Descriptor>();
        long seed = HAGRIDRouterUtils.resolveSolverSeed(rules.planning().runSeed());
        for (var carrier : selected) {
            String id = carrier.getId().toString();
            metadata.put(id, carrier);
            descriptors.add(new CarrierBatchExecutor.Descriptor(id, carrier.getServices().size(),
                    carrier.getShipments().size(), carrier.getCarrierCapabilities().getVehicleTypes().size(),
                    seed, reservedBytes.applyAsLong(carrier)));
        }
        var providers = new ConcurrentHashMap<String, VRPTransportCosts>();
        var prepared = new ArrayList<PreparedInitialPlan>();
        int iterations = Math.max(1, jspritIterations);
        batches.runStaged(resources.maxActiveSolvers().orElse(Integer.MAX_VALUE), descriptors, descriptor -> {
            var carrier = metadata.get(descriptor.carrierId());
            String mode = CarrierModeResolver.resolve(carrier).orElseThrow();
            var costs = providers.computeIfAbsent(mode,
                    key -> NativeModalTransportCosts.create(network, vehicleTypes, key));
            return prepareInitialPlan(carrier, costs, iterations, maxRouteDurationSeconds, tourRetimeRng, resources,rules);
        }, prepared::add, () -> publishInitialGroup(prepared));
    }

    private static PreparedInitialPlan prepareInitialPlan(Carrier carrier, VRPTransportCosts costs,
            int iterations, int maxRouteDurationSeconds, Function<Carrier, Random> tourRetimeRng,
            hagrid.core.config.ResolvedHagridConfig.Resources resources,LmdTourRules rules) {
        var result = new CarrierOptimizationService().optimizeInitial(
                carrier, costs, iterations, maxRouteDurationSeconds, rules.uTurnPenaltyCost(), resources, HAGRIDRouterUtils.resolveSolverSeed(rules.planning().runSeed()));
        if (!result.diagnostics().isEmpty()) {
            throw new IllegalStateException("Invalid initial solution for " + carrier.getId() + ": " + result.diagnostics());
        }
        CarrierPlan plan = result.plan();
        Carrier worker = plan.getCarrier();
        if (tourRetimeRng != null) {
            LmdTourRetimer.retime(worker, plan, tourRetimeRng.apply(carrier), maxRouteDurationSeconds,
                    rules.vehicleAvailabilityBufferSeconds(),rules.vehicleDayEndSeconds(), rules.planning());
            hagrid.core.routing.CarrierRoadRouter.routePlan(plan, costs);
        }
        var validation = PlanValidator.validate(worker, plan, costs, null, maxRouteDurationSeconds);
        if (!validation.valid()) {
            throw new IllegalStateException("Invalid final departure for " + carrier.getId() + ": " + validation.violations());
        }
        return new PreparedInitialPlan(carrier, plan, result.iterations());
    }

    private static void publishInitialGroup(List<PreparedInitialPlan> prepared) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Initial Lausitz group interrupted before publication");
        }
        // Only the caller publishes, after every candidate and departure adjustment passed.
        for (var value : prepared) {
            for (ScheduledTour tour : value.plan().getScheduledTours()) {
                var vehicle = tour.getVehicle();
                if (!value.carrier().getCarrierCapabilities().getCarrierVehicles().containsKey(vehicle.getId())) {
                    CarriersUtils.addCarrierVehicle(value.carrier(), vehicle);
                }
            }
            value.carrier().addPlan(value.plan());
            value.carrier().setSelectedPlan(value.plan());
            CarriersUtils.setJspritIterations(value.carrier(), value.iterations());
            HAGRIDRouterUtils.recordUnassignedJobs(value.carrier(), List.of());
        }
    }
    private record PreparedInitialPlan(Carrier carrier,CarrierPlan plan,int iterations) {}
    /** Step 6: write the routed carriers, creating the run's carriers/ directory first. */
    private static void writeRouted(Carriers carriers, Path target, String what) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create output directory for " + what + ": " + target, e);
        }
        CarriersUtils.writeCarriers(carriers, target.toString());
    }

    public static void main(String[] args) {
        if (args.length < 6) {
            throw new IllegalArgumentException(
                    "Usage: demandShp depotCsv networkFile vehicleTypesFile carriersOut jspritIterations");
        }
        run(args[0], args[1], args[2], args[3], args[4], Integer.parseInt(args[5]));
    }
}
