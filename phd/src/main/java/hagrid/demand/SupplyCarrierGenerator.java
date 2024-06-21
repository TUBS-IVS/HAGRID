package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import hagrid.HagridConfigGroup;
import hagrid.utils.demand.Hub;
import hagrid.utils.general.HAGRIDUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;
import org.matsim.vehicles.VehicleType;

import java.util.*;

/**
 * The SupplyCarrierGenerator class is responsible for generating supply
 * carriers based on the previously generated carriers and their services.
 */
@Singleton
public class SupplyCarrierGenerator implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(SupplyCarrierGenerator.class);
    private static final int SUPPLY_VEH_CAP = 2000;
    private static final int DURATION_PER_STOP = 30 * 60;

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfigGroup hagridConfig;

    private CarrierVehicleFactory carrierVehicleFactory;

    @Override
    public void run() {
        try {
            LOGGER.info("Starting supply carrier generation...");

            // Retrieve existing carriers from the scenario
            Carriers carriers = HAGRIDUtils.getScenarioElementAs("carriers", scenario);
            LOGGER.info("Retrieved {} carriers from the scenario.", carriers.getCarriers().size());

            // Retrieve hubs and vehicle types from the scenario
            Map<Id<Hub>, Hub> hubs = HAGRIDUtils.getScenarioElementAs("hubList", scenario);
            CarrierVehicleTypes vehicleTypes = HAGRIDUtils.getScenarioElementAs("carrierVehicleTypes", scenario);
            LOGGER.info("Retrieved {} hubs and {} vehicle types from the scenario.", hubs.size(),
                    vehicleTypes.getVehicleTypes().size());

            // Create an instance of CarrierVehicleFactory with the retrieved vehicle types
            carrierVehicleFactory = new CarrierVehicleFactory(vehicleTypes);

            // Create supply carriers based on existing carriers
            Carriers supplyCarriers = createSupplyCarriers(carriers, hubs, vehicleTypes, scenario.getNetwork());

            // Validate generated supply carriers
            validateSupplyCarriers(supplyCarriers, hubs, hagridConfig.isWhiteLabel());
            LOGGER.info("Supply carrier generation completed successfully.");
        } catch (Exception e) {
            LOGGER.error("Error generating supply carriers", e);
        }
    }

    /**
     * Creates supply carriers based on existing carriers, hubs, and vehicle types.
     *
     * @param carriers     Existing carriers in the scenario.
     * @param hubs         Map of hubs to be supplied.
     * @param vehicleTypes Available vehicle types.
     * @param network      The transport network.
     * @return Generated supply carriers.
     */
    private Carriers createSupplyCarriers(Carriers carriers, Map<Id<Hub>, Hub> hubs, CarrierVehicleTypes vehicleTypes,
            Network network) {
        Carriers supplyCarriers = new Carriers();

        // Aggregate the parcel demand for each hub
        for (Carrier existingCarrier : carriers.getCarriers().values()) {
            Hub correspondingHub = (Hub) existingCarrier.getAttributes().getAttribute("hub");
            int numberOfParcels = getNumberOfParcels(existingCarrier);
            correspondingHub.increaseAssignedSupplyDemand(numberOfParcels);
            LOGGER.debug("Added {} parcels to hub {}", numberOfParcels, correspondingHub.getId());
        }

        if (hagridConfig.isWhiteLabel()) {
            // Create supply carriers for white label scenario
            createWhiteLabelSupplyCarriers(hubs, vehicleTypes, supplyCarriers, network);
        } else {
            // Create supply carriers for non-white label scenario
            createNonWhiteLabelSupplyCarriers(hubs, vehicleTypes, supplyCarriers, network);
        }

        return supplyCarriers;
    }

    /**
     * Creates supply carriers for the white label scenario.
     *
     * @param hubs           Map of hubs to be supplied.
     * @param vehicleTypes   Available vehicle types.
     * @param supplyCarriers Container for the generated supply carriers.
     * @param network        The transport network.
     */
    private void createWhiteLabelSupplyCarriers(Map<Id<Hub>, Hub> hubs, CarrierVehicleTypes vehicleTypes,
            Carriers supplyCarriers, Network network) {
        for (Hub hub : hubs.values()) {
            if (hub.getAssignedSupplyDemand() > 0) {
                Carrier supplyCarrier = createSupplyCarrier(hub, vehicleTypes, network, true);
                supplyCarriers.addCarrier(supplyCarrier);
                LOGGER.info("Created supply carrier for hub {} with {} parcels", hub.getId(),
                        getNumberOfParcels(supplyCarrier));
            }
        }
    }

    /**
     * Creates supply carriers for the non-white label scenario.
     *
     * @param hubs           Map of hubs to be supplied.
     * @param vehicleTypes   Available vehicle types.
     * @param supplyCarriers Container for the generated supply carriers.
     * @param network        The transport network.
     */
    private void createNonWhiteLabelSupplyCarriers(Map<Id<Hub>, Hub> hubs, CarrierVehicleTypes vehicleTypes,
            Carriers supplyCarriers, Network network) {
        Hub andertenHub = hubs.get(Id.create("dhl_hannover_anderten", Hub.class));
        int supplyToAnderten = 0;

        Carrier supplyCarrierDhl = createAndAddCarrier("supply_from_dhl_anderten", andertenHub, supplyCarriers);
        LOGGER.info("Created supply carrier from hub {} with {} parcels", andertenHub.getId(),
                getNumberOfParcels(supplyCarrierDhl));

        for (Hub hub : hubs.values()) {
            if (hub.getAssignedSupplyDemand() > 0) {
                if (!hub.getCompany().contains("dhl")) {
                    Carrier supplyCarrier = createSupplyCarrier(hub, vehicleTypes, network, false);
                    supplyCarriers.addCarrier(supplyCarrier);
                    LOGGER.info("Created supply carrier for hub {} with {} parcels", hub.getId(),
                            getNumberOfParcels(supplyCarrier));
                } else {
                    supplyToAnderten += hub.getAssignedSupplyDemand();
                    if (!hub.getId().toString().contains("anderten")) {
                        addSupplyCarrierServices(supplyCarrierDhl, hub, 0, null, null, null, network);
                        LOGGER.debug("Added supply carrier services for DHL hub {} with {} parcels", hub.getId(),
                                getNumberOfParcels(supplyCarrierDhl));
                    }
                }
            }
        }

        Carrier supplyCarrierToAnderten = createAndAddCarrier("supply_to_dhl_anderten", andertenHub, supplyCarriers);
        setupCarrierVehicles(supplyCarrierToAnderten, getRandomSupplyLinkID());
        addSupplyCarrierServices(supplyCarrierToAnderten, null, supplyToAnderten, "dhl_anderten", "dhl",
                andertenHub.getLink(), network);
        LOGGER.info("Created final supply carrier for delivery to DHL Anderten hub with {} parcels and {} services",
                getNumberOfParcels(supplyCarrierToAnderten), supplyCarrierToAnderten.getServices().size());
    }

    /**
     * Creates and adds a carrier for the specified hub.
     *
     * @param carrierId      The ID of the carrier.
     * @param hub            The hub to be supplied.
     * @param supplyCarriers Container for the generated supply carriers.
     * @return The created carrier.
     */
    private Carrier createAndAddCarrier(String carrierId, Hub hub, Carriers supplyCarriers) {
        Carrier carrier = CarriersUtils.createCarrier(Id.create(carrierId, Carrier.class));
        setupCarrierVehicles(carrier, hub.getLink());
        carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);
        supplyCarriers.addCarrier(carrier);
        return carrier;
    }

    /**
     * Sets up the carrier vehicles for the given carrier.
     *
     * @param carrier The carrier to set up vehicles for.
     * @param linkId  The link ID where the vehicles are located.
     */
    private void setupCarrierVehicles(Carrier carrier, Id<Link> linkId) {
        CarrierVehicle carrierVehicleEarly = carrierVehicleFactory.createSupplyVehicle(carrier.getId().toString(),
                linkId, true);
        CarriersUtils.addCarrierVehicle(carrier, carrierVehicleEarly);
        CarrierVehicle carrierVehicleLate = carrierVehicleFactory.createSupplyVehicle(carrier.getId().toString(),
                linkId, false);
        CarriersUtils.addCarrierVehicle(carrier, carrierVehicleLate);
    }

    /**
     * Creates a supply carrier for the given hub.
     *
     * @param hub          The hub to be supplied.
     * @param vehicleTypes Available vehicle types.
     * @param network      The transport network.
     * @param isWhiteLabel Whether the scenario is white label.
     * @return The created supply carrier.
     */
    private Carrier createSupplyCarrier(Hub hub, CarrierVehicleTypes vehicleTypes, Network network,
            boolean isWhiteLabel) {
        Carrier supplyCarrier = CarriersUtils
                .createCarrier(Id.create("supply_" + hub.getId().toString().replace("/", "_"), Carrier.class));
        String supplyLink = null;

        if (isWhiteLabel) {
            Carriers supplyCarriersBaseCase = new Carriers();
            new CarrierPlanXmlReader(supplyCarriersBaseCase, vehicleTypes).readFile("carrierPlans_Supply.xml");

            for (Carrier carrierBaseCase : supplyCarriersBaseCase.getCarriers().values()) {
                if (carrierBaseCase.getId().toString().contains("supply_" + hub.getId().toString().replace("/", "_"))) {
                    supplyLink = (String) carrierBaseCase.getAttributes().getAttribute("supplyLink");
                    LOGGER.info("Found supply Link for: " + supplyCarrier.getId() + " -> " + supplyLink);
                    break;
                }
            }
        }

        Id<Link> linkId = supplyLink == null ? getRandomSupplyLinkID() : Id.createLinkId(supplyLink);
        supplyCarrier.getAttributes().putAttribute("supplyLink", linkId.toString());

        setupCarrierVehicles(supplyCarrier, linkId);

        supplyCarrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);
        addSupplyCarrierServices(supplyCarrier, hub, 0, null, null, null, network);

        return supplyCarrier;
    }

    /**
     * Retrieves the number of parcels for the given carrier.
     *
     * @param carrier The carrier.
     * @return The number of parcels.
     */
    private int getNumberOfParcels(Carrier carrier) {
        return carrier.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum();
    }

    /**
     * Adds supply carrier services to the given carrier.
     *
     * @param carrier The carrier.
     * @param hub     The hub to be supplied.
     * @param amount  The amount of parcels.
     * @param idHub   The hub ID.
     * @param company The company.
     * @param linkId  The link ID.
     * @param network The transport network.
     */
    private void addSupplyCarrierServices(Carrier carrier, Hub hub, int amount, String idHub, String company,
            Id<Link> linkId, Network network) {
        int amountToHub = (hub == null) ? amount : hub.getAssignedSupplyDemand();
        String hubId = (hub == null) ? idHub : hub.getId().toString();
        String hubCompany = (hub == null) ? company : hub.getCompany();
        Id<Link> hubLink = (hub == null) ? linkId : hub.getLink();

        int numberOfServices = (int) Math.ceil(((double) amountToHub) / SUPPLY_VEH_CAP);

        for (int i = 0; i < numberOfServices - 1; i++) {
            CarrierService.Builder serviceBuilder = CarrierService.Builder
                    .newInstance(Id.create(hubId.replace("/", "_") + "_" + i, CarrierService.class), hubLink);
            serviceBuilder.setCapacityDemand(SUPPLY_VEH_CAP);
            serviceBuilder.setServiceDuration(DURATION_PER_STOP);
            serviceBuilder.setServiceStartTimeWindow(TimeWindow.newInstance(0, 24 * 3600));
            CarrierService service = serviceBuilder.build();
            service.getAttributes().putAttribute("provider", hubCompany);
            service.getAttributes().putAttribute("type", "supply");
            service.getAttributes().putAttribute("coord", network.getLinks().get(hubLink).getCoord());
            CarriersUtils.addService(carrier, service);
        }

        CarrierService.Builder serviceBuilder = CarrierService.Builder.newInstance(
                Id.create(hubId.replace("/", "_") + "_" + (numberOfServices - 1), CarrierService.class), hubLink);
        serviceBuilder.setCapacityDemand(amountToHub - ((numberOfServices - 1) * SUPPLY_VEH_CAP));
        serviceBuilder.setServiceDuration(DURATION_PER_STOP);
        serviceBuilder.setServiceStartTimeWindow(TimeWindow.newInstance(0, 24 * 3600));
        CarrierService service = serviceBuilder.build();
        service.getAttributes().putAttribute("provider", hubCompany);
        service.getAttributes().putAttribute("type", "supply");
        service.getAttributes().putAttribute("coord", network.getLinks().get(hubLink).getCoord());
        CarriersUtils.addService(carrier, service);
    }

    /**
     * Validates the generated supply carriers by comparing the assigned supply
     * demand of the hubs with the total demand.
     *
     * @param supplyCarriers The generated supply carriers.
     * @param hubs           The hubs to be validated.
     * @param isWhiteLabel   Indicates if the scenario is white label.
     * @throws SupplyCarrierValidationException if the validation fails.
     */
    private void validateSupplyCarriers(Carriers supplyCarriers, Map<Id<Hub>, Hub> hubs, boolean isWhiteLabel)
            throws SupplyCarrierValidationException {
        int totalAssignedDemand = hubs.values().stream().mapToInt(Hub::getAssignedSupplyDemand).sum();
        int totalCarrierDemand = supplyCarriers.getCarriers().values().stream().mapToInt(this::getNumberOfParcels)
                .sum();

        if (!isWhiteLabel) {
            // Adjust total assigned demand for Anderten in non-white label scenarios
            Hub andertenHub = hubs.get(Id.create("dhl_hannover_anderten", Hub.class));
            int dhlOtherDemand = 0;

            // Calculate the total assigned demand for all DHL hubs except Anderten
            for (Hub hub : hubs.values()) {
                if (hub.getCompany().contains("dhl") && !hub.equals(andertenHub)) {
                    dhlOtherDemand += hub.getAssignedSupplyDemand();
                }
            }

            // Adjust the total assigned demand to exclude other DHL hubs
            if (andertenHub != null) {
                totalAssignedDemand = totalAssignedDemand - dhlOtherDemand;
            }
        }

        // Log the assigned supply demand for each hub and corresponding carrier demand
        for (Hub hub : hubs.values()) {
            int assignedDemand = hub.getAssignedSupplyDemand();
            int carrierDemand = 0;

            for (Carrier carrier : supplyCarriers.getCarriers().values()) {
                for (CarrierService service : carrier.getServices().values()) {
                    if (service.getLocationLinkId().equals(hub.getLink())) {
                        carrierDemand += service.getCapacityDemand();
                    }
                }
            }

            LOGGER.info("Hub {}: Assigned Demand = {}, Carrier Demand = {}", hub.getId(), assignedDemand,
                    carrierDemand);
        }

        if (totalAssignedDemand != totalCarrierDemand) {
            String errorMessage = String.format(
                    "Validation failed: Total assigned supply demand (%d) does not match total carrier demand (%d)",
                    totalAssignedDemand, totalCarrierDemand);
            LOGGER.error(errorMessage);
            throw new SupplyCarrierValidationException(errorMessage);
        } else {
            LOGGER.info("Validation successful: Total assigned supply demand matches total carrier demand.");
        }
    }

    /**
     * Custom exception for supply carrier validation errors.
     */
    public static class SupplyCarrierValidationException extends Exception {
        public SupplyCarrierValidationException(String message) {
            super(message);
        }
    }

    /**
     * Retrieves a random supply link ID from a predefined list.
     *
     * @return A random supply link ID.
     */
    private Id<Link> getRandomSupplyLinkID() {
        List<String> linkIds = Arrays.asList("2847279", "3029295", "3821892-1136438-2142663-2143065-982322", "2591669");
        Random rand = new Random();
        return Id.createLinkId(linkIds.get(rand.nextInt(linkIds.size())));
    }
}
