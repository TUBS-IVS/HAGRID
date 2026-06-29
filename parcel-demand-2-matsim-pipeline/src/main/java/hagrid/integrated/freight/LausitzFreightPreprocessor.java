package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
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
import org.matsim.vehicles.VehicleType;

import java.util.List;
import java.util.Map;

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
            Carrier carrier = LmdCarrierBuilder.build(provider, e.getValue(), depot, network, vans,
                    DURATION_PER_PARCEL_MIN, MAX_DURATION_PER_STOP_MIN);
            CarriersUtils.setJspritIterations(carrier, Math.max(1, jspritIterations));
            carriers.addCarrier(carrier);
        }

        // 5. load freight config + carriers into the scenario, then route offline with jsprit
        // NOTE: the local hagrid:freight jar's getCarrierVehicleTypes() throws if the element is
        // not already registered (unlike the contrib jar which auto-creates). We must register
        // the vehicle types ourselves first via addScenarioElement.
        ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        CarriersUtils.addOrGetCarriers(scenario).getCarriers().putAll(carriers.getCarriers());
        scenario.addScenarioElement("carrierVehicleTypes", vehicleTypes);
        // vehicleTypes is already populated — no need to iterate and put again
        try {
            CarriersUtils.runJsprit(scenario); // throws ExecutionException + InterruptedException (confirmed in spike)
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("jsprit routing failed for LMD carriers", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("jsprit routing interrupted for LMD carriers", e);
        }

        // 6. write the routed carriers (ensure the parent directory exists first)
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(carriersOut).getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create output directory for LMD carriers: " + carriersOut, e);
        }
        CarriersUtils.writeCarriers(CarriersUtils.getCarriers(scenario), carriersOut);
    }

    public static void main(String[] args) {
        if (args.length < 6) {
            throw new IllegalArgumentException(
                    "Usage: demandShp depotCsv networkFile vehicleTypesFile carriersOut jspritIterations");
        }
        run(args[0], args[1], args[2], args[3], args[4], Integer.parseInt(args[5]));
    }
}
