package hagrid.demand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
 

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
 
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
 
import org.matsim.freight.carriers.CarrierPlanWriter;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;

import com.google.inject.Inject;
 

import hagrid.HagridConfig;
import hagrid.utils.general.HAGRIDUtils;
import hagrid.utils.routing.ThreadingType;
import hagrid.utils.routing.ZoneBasedTransportCosts;
import hagrid.utils.routing.CarrierRoutingMetrics;
import hagrid.utils.routing.CarrierRoutingStatusLogger;
import java.util.Collection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The CarrierRouter class is responsible for routing both delivery and supply
 * carriers.
 * It retrieves the necessary elements from the scenario and performs the
 * routing using the specified threading type.
 */
// @Singleton
public class CarrierRouter implements Runnable {

    private ThreadingType threadingType;

    private static final Logger LOGGER = LogManager.getLogger(CarrierRouter.class);

    // Shared, lazily-initialized ZoneBasedTransportCosts to avoid rebuilding per run
    private static volatile ZoneBasedTransportCosts SHARED_ZONE_COSTS;

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfig hagridConfig;

    public void setThreadingType(ThreadingType threadingType) {
        this.threadingType = threadingType;
    }

    /**
     * Executes the routing process for both delivery and supply carriers.
     * It retrieves the necessary elements from the scenario and performs the
     * routing using the specified threading type.
     */
    @Override
    public void run() {
        try {
            LOGGER.info("Starting routing process for carriers with threading type: {}.", threadingType);

            // Retrieve existing delivery carriers from the scenario
            Carriers carriers = HAGRIDUtils.getScenarioElementAs("carriers", scenario);
            LOGGER.info("Retrieved {} delivery carriers from the scenario.", carriers.getCarriers().size());

            // Retrieve existing supply carriers from the scenario
            Carriers supplyCarriers = HAGRIDUtils.getScenarioElementAs("supply", scenario);
            LOGGER.info("Retrieved {} supply carriers from the scenario.", supplyCarriers.getCarriers().size());

            // Retrieve car filtered network from the scenario
            Network carFilteredNetwork = HAGRIDUtils.getScenarioElementAs("carFilteredNetwork", scenario);
            LOGGER.info("Retrieved {} links from the carFilteredNetwork.", carFilteredNetwork.getLinks().size());

            // Retrieve car filtered network from the scenario
            CarrierVehicleTypes vehicleTypes = HAGRIDUtils.getScenarioElementAs("carrierVehicleTypes", scenario);
            LOGGER.info("Retrieved {} carrier vehicles types.", vehicleTypes.getVehicleTypes().size());

            // Set up routing costs

            LOGGER.info("Set up routing costs: NetworkBasedTransportCosts and ZoneBasedTransportCosts.");
            // Routing
            NetworkBasedTransportCosts.Builder netBuilder = NetworkBasedTransportCosts.Builder.newInstance(
                    carFilteredNetwork,
                    vehicleTypes.getVehicleTypes().values());
            netBuilder.setTimeSliceWidth(1800);
            final NetworkBasedTransportCosts netBasedCosts = netBuilder.build();

            final ZoneBasedTransportCosts zoneBasedCosts = getOrCreateZoneCosts(carFilteredNetwork, vehicleTypes);

            // Initialize the router with the specified threading type and live status CSV logger
            Path statusCsv = Path.of(hagridConfig.io().routingStatus());
            CarrierRoutingStatusLogger statusLogger = new CarrierRoutingStatusLogger(statusCsv);
            Router router = new Router(threadingType, statusLogger, hagridConfig, vehicleTypes);

            // Route delivery carriers
            // router.routeCarriers(carriers, zoneBasedCosts, carFilteredNetwork, "delivery");
            router.routeCarriers(carriers, netBasedCosts, carFilteredNetwork, "delivery");
            // For testing purposes, limit the number of carriers to route
            // Carriers firstThreeCarriers = new Carriers();
            // carriers.getCarriers().entrySet().stream()
            //     .limit(3)
            //     .forEach(entry -> firstThreeCarriers.getCarriers().put(entry.getKey(), entry.getValue()));

            // router.routeCarriers(firstThreeCarriers, zoneBasedCosts, carFilteredNetwork, "delivery");

            // Route supply carriers
            router.routeCarriers(supplyCarriers, netBasedCosts, carFilteredNetwork, "supply");

            // // Write the routed plans to XML files
            Files.createDirectories(hagridConfig.io().carrierDir());

            new CarrierPlanWriter(carriers).write(hagridConfig.io().deliveryCarriersRouted());
            LOGGER.info("Written: {}", hagridConfig.io().deliveryCarriersRouted());
            new CarrierPlanWriter(supplyCarriers).write(hagridConfig.io().supplyCarriersRouted());
            LOGGER.info("Written: {}", hagridConfig.io().supplyCarriersRouted());

            // Export per-carrier routing metrics CSV (summary at the end)
            writeMetricsCsv(router.getMetrics(), Path.of(hagridConfig.io().routingMetrics()));

            LOGGER.info("Routing process for carriers completed successfully.");
        } catch (Exception e) {
            LOGGER.error("Error routing carriers", e);
        }
    }

    private static ZoneBasedTransportCosts getOrCreateZoneCosts(Network network, CarrierVehicleTypes vehicleTypes) {
        ZoneBasedTransportCosts local = SHARED_ZONE_COSTS;
        if (local == null) {
            synchronized (CarrierRouter.class) {
                local = SHARED_ZONE_COSTS;
                if (local == null) {
                    LOGGER.info("[INIT] Building shared ZoneBasedTransportCosts instance (timeSliceWidth=1800)");
                    ZoneBasedTransportCosts.Builder zoneBuilder = ZoneBasedTransportCosts.Builder.newInstance(
                            network,
                            vehicleTypes.getVehicleTypes().values());
                    zoneBuilder.setTimeSliceWidth(1800);
                    local = zoneBuilder.build();
                    SHARED_ZONE_COSTS = local;
                }
            }
        }
        return local;
    }

    private void writeMetricsCsv(Collection<CarrierRoutingMetrics> metrics, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent());
        // Header
        String header = String.join(",",
                "carrierId","provider","services","shipments","totalCapacityDemand","b2bParcels","b2cParcels",
                "sizeClass","threadingType","carrierType","jspritSeconds","routeSeconds","totalSeconds") + System.lineSeparator();
        Files.writeString(csvPath, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        // Rows
        for (CarrierRoutingMetrics m : metrics) {
            String row = String.join(",",
                    escape(m.carrierId),
                    escape(m.provider),
                    Integer.toString(m.services),
                    Integer.toString(m.shipments),
                    Integer.toString(m.totalCapacityDemand),
                    Integer.toString(m.b2bParcels),
                    Integer.toString(m.b2cParcels),
                    m.sizeClass,
                    m.threadingType,
                    m.carrierType,
                    String.format(java.util.Locale.ROOT, "%.3f", m.jspritSeconds),
                    String.format(java.util.Locale.ROOT, "%.3f", m.routeSeconds),
                    String.format(java.util.Locale.ROOT, "%.3f", m.totalSeconds)
            ) + System.lineSeparator();
            Files.writeString(csvPath, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        LOGGER.info("Wrote carrier routing metrics CSV: {} ({} rows)", csvPath.toAbsolutePath(), metrics.size());
    }

    private static String escape(String v) {
        if (v == null) return "";
        String needsQuote = ",\n\r\"";
        boolean quote = v.chars().anyMatch(c -> needsQuote.indexOf(c) >= 0);
        String s = v.replace("\"", "\"\"");
        return quote ? ("\"" + s + "\"") : s;
    }
}
