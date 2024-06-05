package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import hagrid.HagridConfigGroup;
import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.Hub;
import hagrid.utils.demand.Delivery.ParcelType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;
import org.matsim.vehicles.VehicleType;

import java.util.Map;
import java.util.Map.Entry;
import java.util.ArrayList;
import java.util.HashMap;

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

            // Get scenario elements
            LOGGER.info("Getting scenario elements..");
            Map<String, ArrayList<Delivery>> deliveries = getScenarioElementAs("deliveries");
            Map<Id<Hub>, Hub> hubList = getScenarioElementAs("hubList");
            CarrierVehicleTypes vehicleTypes = getScenarioElementAs("carrierVehicleTypes");
            Network subNetwork = getScenarioElementAs("parcelServiceNetwork");

            LOGGER.info("Scenario elements retrieved.");

            // // Process the deliveries to create carriers
            Carriers carriers = generateCarriers(deliveries, hubList, subNetwork);

            assignHubsToCarriers(carriers, hubList); // Assign hubs to carriers
            addVehiclesToCarriers(carriers, vehicleTypes); // Add vehicles to carriers

            LOGGER.info("Carrier generation completed.");

        } catch (Exception e) {
            LOGGER.error("Error generating carriers", e);
        }
    }

    /**
     * Initializes the delivery rate map based on the providers listed in the
     * HagridConfigGroup.
     *
     * @return A map containing the delivery rates for each provider.
     */
    private Map<String, Double> initializeDeliveryRate() {
        Map<String, Double> deliveryRate = new HashMap<>();

        // Get the delivery rates from the HagridConfigGroup
        deliveryRate.put("dhl", (double) hagridConfig.getDeliveryRateDhl());
        deliveryRate.put("hermes", (double) hagridConfig.getDeliveryRateHermes());
        deliveryRate.put("ups", (double) hagridConfig.getDeliveryRateUps());
        deliveryRate.put("amazon", (double) hagridConfig.getDeliveryRateAmazon());
        deliveryRate.put("dpd", (double) hagridConfig.getDeliveryRateDpd());
        deliveryRate.put("gls", (double) hagridConfig.getDeliveryRateGls());
        deliveryRate.put("fedex", (double) hagridConfig.getDeliveryRateFedex());
        deliveryRate.put("wl", (double) hagridConfig.getDeliveryRateWl());

        return deliveryRate;
    }

    private void assignHubsToCarriers(Carriers carriers, Map<Id<Hub>, Hub> hubList) {
        // Hub closestHub = getClosestHub(entry, hubList, numberOfParcels);
        // carrier.getAttributes().putAttribute("hub", closestHub);
        // carrier.getAttributes().putAttribute("hubId", closestHub.getId().toString());
    }

    private Carriers generateCarriers(Map<String, ArrayList<Delivery>> deliveries, Map<Id<Hub>, Hub> hubList,
            Network subNetwork) {

        Carriers carriers = new Carriers();

        Map<String, Double> deliveryRates = initializeDeliveryRate();

        deliveries.entrySet().stream().map(entry -> {
            String carrierID = entry.getKey();
            ArrayList<Delivery> carrierDeliveries = entry.getValue();

            Carrier carrier = CarriersUtils.createCarrier(Id.create(carrierID, Carrier.class));

            carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);
            carrier.getAttributes().putAttribute("provider", carrierID.split("_")[0]);
            carrier.getAttributes().putAttribute("plz", carrierID.split("_")[1].substring(0, 5));
            carrier.getAttributes().putAttribute("missedParcels", 0);

            addCarrierServicesToCarriers(carrier, carrierDeliveries, subNetwork, deliveryRates);

            return carrier;
        }).forEach(carriers::addCarrier);

        LOGGER.info("Carriers generated: {}", carriers.getCarriers().size());
        scenario.addScenarioElement("carriers", carriers);

        return carriers;
    }

    private void addCarrierServicesToCarriers(Carrier carrier, ArrayList<Delivery> carrierDeliveries,
            Network subNetwork, Map<String, Double> deliveryRates) {

        String provider = (String) carrier.getAttributes().getAttribute("provider");

        if (!deliveryRates.containsKey(provider)) {
            throw new IllegalStateException("Delivery rate not available for provider: " + provider);
        }

        double rate = deliveryRates.get(provider);

        for (Delivery carrierDelivery : carrierDeliveries) {
            System.out.println(carrierDelivery);
            ParcelType parcelType = carrierDelivery.getParcelType();
            if (parcelType == ParcelType.B2B) {
                rate = 1.0;
            }
    
            int amount = carrierDelivery.getAmount();
            int numberOfServices = (int) Math.ceil(((double) amount) / ((double) hagridConfig.getCepVehCap()));
            int cap = hagridConfig.getCepVehCap();
    
            Id<Link> linkId = NetworkUtils.getNearestLinkExactly(subNetwork, carrierDelivery.getCoordinate()).getId();
    
            for (int j = 0; j < numberOfServices - 1; j++) {
                addCarrierService(carrier, linkId, rate, cap, carrierDelivery, j);
            }
    
            int remainingCapacity = amount - ((numberOfServices - 1) * cap);
            addCarrierService(carrier, linkId, rate, remainingCapacity, carrierDelivery, numberOfServices - 1);
        }
        LOGGER.info(provider + " carrier services added. Number of services: " + carrier.getServices().values().size());
    }

    private void addCarrierService(Carrier carrier, Id<Link> linkId, double rate, int capacityDemand, Delivery carrierDelivery, int serviceNumber) {
        double serviceDuration = Math.min((hagridConfig.getDurationPerParcel() * 60) * capacityDemand, hagridConfig.getMaxDurationPerStop() * 60);

        double begin = hagridConfig.getDeliveryTimeWindowStart();
        double end = hagridConfig.getDeliveryTimeWindowEnd();
    
        CarrierService.Builder serviceBuilder = CarrierService.Builder.newInstance(
                Id.create("service_" + carrierDelivery.getParcelType() + "_" + carrier.getId() + "_" + serviceNumber, CarrierService.class), linkId);
        serviceBuilder.setCapacityDemand(capacityDemand);
        serviceBuilder.setServiceDuration(serviceDuration);
        serviceBuilder.setServiceStartTimeWindow(TimeWindow.newInstance(begin, end));
    
        CarrierService service = serviceBuilder.build();
        service.getAttributes().putAttribute("provider", carrier.getAttributes().getAttribute("provider"));
        service.getAttributes().putAttribute("coord", carrierDelivery.getCoordinate());
        service.getAttributes().putAttribute("type", carrierDelivery.getParcelType());
    
        CarriersUtils.addService(carrier, service);
    }

    private void addVehiclesToCarriers(Carriers carriers, CarrierVehicleTypes vehicleTypes) {

        // int start = 7;
        // int end = 14;
        // if(carrierID.split("_")[0].toLowerCase().contains("amazon")) {
        // start = 9;
        // end = 17;
        // }

        // for (int startTime = start; startTime <= end; startTime++) {
        // CarrierVehicle carrierVehicle_CEP_size_m =
        // createCEPVehicle_m(closestHub.getLink(),
        // closestHub.getId().toString(), vehicleTypes, startTime);
        // CarrierVehicle carrierVehicle_CEP_size_xl =
        // createCEPVehicle_xl(closestHub.getLink(),
        // closestHub.getId().toString(), vehicleTypes, startTime);
        // CarrierUtils.addCarrierVehicle(carrier, carrierVehicle_CEP_size_m);
        // CarrierUtils.addCarrierVehicle(carrier, carrierVehicle_CEP_size_xl);
        // // CarrierVehicle carrierVehicle_CEP_late = createCEPVehicle(carrier.getId(),
        // closestHub.getLink(),
        // // closestHub.getId().toString(), vehicleTypes, 12);
        // // CarrierUtils.addCarrierVehicle(carrier, carrierVehicle_CEP_late);
        // }

    }

    private <T> T getScenarioElementAs(String elementName) {
        Object element = scenario.getScenarioElement(elementName);
        if (element == null) {
            throw new IllegalStateException(elementName + " data is missing in the scenario.");
        }
        return (T) element;
    }

}
