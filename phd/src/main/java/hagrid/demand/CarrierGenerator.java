package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import hagrid.HagridConfigGroup;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;

import java.util.Map;
import java.util.ArrayList;

/**
 * The CarrierGenerator class is responsible for converting sorted demand
 * into Carrier objects and validating the totals.
 */
@Singleton
public class CarrierGenerator implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(CarrierGenerator.class);

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfigGroup hagridConfig;

    @Override
    public void run() {
        try {
            LOGGER.info("Generating carriers from sorted deliveries and parcels...");            

            ScenarioData scenarioData = getScenarioData();


            // // Process the deliveries to create carriers
            // Map<String, Carrier> carriers = processSortedDemand(deliveries, hubList, vehicleTypes);
            // scenario.addScenarioElement("carriers", carriers);

            LOGGER.info("Carrier generation completed.");

        } catch (Exception e) {
            LOGGER.error("Error generating carriers", e);
        }
    }




    private Map<String, Carrier> processSortedDemand(Map<String, ArrayList<Delivery>> sortedDemand, Map<Id<Hub>, Hub> hubList, Map<String, VehicleType> vehicleTypes) {
        Carriers carriers = new Carriers();

        return null;

    }



    private ScenarioData getScenarioData() {
        Map<String, ArrayList<Delivery>> deliveries = (Map<String, ArrayList<Delivery>>) scenario.getScenarioElement("deliveries");
        Map<Id<Hub>, Hub> hubList = (Map<Id<Hub>, Hub>) scenario.getScenarioElement("hubList");
        CarrierVehicleTypes vehicleTypes= (CarrierVehicleTypes) scenario.getScenarioElement("carrierVehicleTypes");
        Network subNetwork = (Network) scenario.getScenarioElement("subNetwork");
        Map<Id<Hub>, Hub> shopList = (Map<Id<Hub>, Hub>) scenario.getScenarioElement("shopList");

        if (deliveries == null) {
            throw new IllegalStateException("Sorted demand data is missing in the scenario.");
        }
        if (hubList == null) {
            throw new IllegalStateException("Hub list data is missing in the scenario.");
        }
        if (vehicleTypes == null) {
            throw new IllegalStateException("Vehicle types data is missing in the scenario.");
        }
        if (subNetwork == null) {
            throw new IllegalStateException("Sub-network data is missing in the scenario.");
        }
        if (shopList == null) {
            throw new IllegalStateException("Shop list data is missing in the scenario.");
        }

        return new ScenarioData(deliveries, hubList, vehicleTypes, subNetwork, shopList);
    }


    private static class ScenarioData {
        Map<String, ArrayList<Delivery>> deliveries;
        Map<Id<Hub>, Hub> hubList;
        CarrierVehicleTypes vehicleTypes;
        Network subNetwork;
        Map<Id<Hub>, Hub> shopList;

        public ScenarioData(Map<String, ArrayList<Delivery>> deliveries, Map<Id<Hub>, Hub> hubList, CarrierVehicleTypes vehicleTypes, Network subNetwork, Map<Id<Hub>, Hub> shopList) {
            this.deliveries = deliveries;
            this.hubList = hubList;
            this.vehicleTypes = vehicleTypes;
            this.subNetwork = subNetwork;
            this.shopList = shopList;
        }
    }

}
