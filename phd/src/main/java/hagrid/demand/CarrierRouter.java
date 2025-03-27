package hagrid.demand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.CarrierPlanWriter;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import hagrid.HagridConfigGroup;
import hagrid.utils.general.HAGRIDUtils;
import hagrid.utils.routing.HAGRIDRouterUtils;
import hagrid.utils.routing.ThreadingType;
import hagrid.utils.routing.ZoneBasedTransportCosts;

/**
 * The CarrierRouter class is responsible for routing both delivery and supply
 * carriers.
 * It retrieves the necessary elements from the scenario and performs the
 * routing using the specified threading type.
 */
@Singleton
public class CarrierRouter implements Runnable {

    private ThreadingType threadingType;

    private static final Logger LOGGER = LogManager.getLogger(CarrierRouter.class);

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfigGroup hagridConfig;

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

            ZoneBasedTransportCosts.Builder zoneBuilder = ZoneBasedTransportCosts.Builder.newInstance(
                    carFilteredNetwork,
                    vehicleTypes.getVehicleTypes().values());
            zoneBuilder.setTimeSliceWidth(1800);
            final ZoneBasedTransportCosts zoneBasedCosts = zoneBuilder.build();

            // Initialize the router with the specified threading type
            Router router = new Router(threadingType);            

            // Route delivery carriers
            router.routeCarriers(carriers, zoneBasedCosts, carFilteredNetwork, "delivery");

            // Route supply carriers
            router.routeCarriers(supplyCarriers, netBasedCosts, carFilteredNetwork, "supply");

            // // Write the routed plans to XML files
            new CarrierPlanWriter(carriers).write("phd/output/delivery_carriers_routed.xml");
            new CarrierPlanWriter(supplyCarriers).write("phd/output/supply_carriers_routed.xml");

            LOGGER.info("Routing process for carriers completed successfully.");
        } catch (Exception e) {
            LOGGER.error("Error routing carriers", e);
        }
    }
}
