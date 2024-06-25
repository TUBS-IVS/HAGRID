package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import hagrid.HagridConfigGroup;
import hagrid.utils.demand.Hub;
import hagrid.utils.general.HAGRIDUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;
import org.matsim.freight.carriers.usecases.chessboard.CarrierTravelDisutilities;
import org.matsim.vehicles.VehicleType;

import java.util.*;

/**
 * The SupplyCarrierGenerator class is responsible for generating supply
 * carriers based on the previously generated carriers and their services.
 */
@Singleton
public class SupplyCarrierGenerator implements Runnable {

    // TODO Add Information to Config Group!

    private static final Logger LOGGER = LogManager.getLogger(SupplyCarrierGenerator.class);
    private static final int SUPPLY_VEH_CAP = 2000;
    private static final int DURATION_PER_STOP = 30 * 60;

    private static final List<Id<Link>> SUPPLY_LINK_IDS = Arrays.asList(
            Id.createLinkId("2847279"),
            Id.createLinkId("3029295"),
            Id.createLinkId("3821892-1136438-2142663-2143065-982322"),
            Id.createLinkId("2591669"));

    private static final Map<String, Id<Link>> SUPPLY_LINK_DIRECTIONS = new HashMap<>() {
        {
            put("south", Id.createLinkId("2847279"));
            put("north", Id.createLinkId("3821892-1136438-2142663-2143065-982322"));
            put("east", Id.createLinkId("3029295"));
            put("west", Id.createLinkId("2591669"));
        }
    };

    private static final Map<String, Double> SUPPLY_DIRECTION_PROBABILITIES = new HashMap<>() {
        {
            put("south", 0.25);
            put("north", 0.25);
            put("east", 0.25);
            put("west", 0.25);
        }
    };    

    Random rand = new Random();

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfigGroup hagridConfig;

    private CarrierVehicleFactory carrierVehicleFactory;
    private CarrierVehicleTypes vehicleTypes;
    private Network network;

    @Override
    public void run() {
        try {
            LOGGER.info("Starting supply carrier generation...");

            // Retrieve existing carriers from the scenario
            Carriers carriers = HAGRIDUtils.getScenarioElementAs("carriers", scenario);
            LOGGER.info("Retrieved {} carriers from the scenario.", carriers.getCarriers().size());

            // Retrieve hubs, vehicle types, and network from the scenario
            Map<Id<Hub>, Hub> hubs = HAGRIDUtils.getScenarioElementAs("hubList", scenario);
            vehicleTypes = HAGRIDUtils.getScenarioElementAs("carrierVehicleTypes", scenario);
            network = HAGRIDUtils.getScenarioElementAs("carFilteredNetwork", scenario);

            LOGGER.info("Retrieved {} hubs, {} vehicle types, and network from the scenario.", hubs.size(),
                    vehicleTypes.getVehicleTypes().size());

            // Create an instance of CarrierVehicleFactory with the retrieved vehicle types
            carrierVehicleFactory = new CarrierVehicleFactory(vehicleTypes);

            // Create supply carriers based on existing carriers
            Carriers supplyCarriers = createSupplyCarriersAndServices(carriers, hubs);

            // Split supply carriers into sub-carriers based on different starting points on
            // the map (North, East, South, West)
            // This step ensures that the supply services are distributed across multiple
            // starting points,
            // improving the efficiency and accuracy of the simulation by accounting for
            // geographical spread.
            // Each sub-carrier will have its own set of vehicles and services, and will
            // operate from a specific starting point.
            Carriers splitSupplyCarriers = splitSupplyCarriers(supplyCarriers);

            // Validate generated supply carriers
            validateSupplyCarriers(supplyCarriers, splitSupplyCarriers, hubs, hagridConfig.isWhiteLabel());

            new CarrierPlanWriter(supplyCarriers).write("phd/output/supply_carriers.xml");
            // HAGRIDUtils.convertDemandFromParcelsToShapeFile(supplyCarriers, "phd/output/supply_carriers.shp");
            new CarrierPlanWriter(splitSupplyCarriers).write("phd/output/split_supply_carriers.xml");
            // HAGRIDUtils.convertDemandFromParcelsToShapeFile(splitSupplyCarriers,
            //         "phd/output/split_supply_carriers.shp");

            LOGGER.info("Supply carrier generation completed successfully.");
        } catch (Exception e) {
            LOGGER.error("Error generating supply carriers", e);
        }
    }

    /**
     * Splits the supply carriers into sub-carriers for each direction (North, East,
     * South, West). This is determined by the placement of supply vehicles -> thats
     * why we have to create 4 Carriers for the links -> otherwise matsim will
     * always choose the clostest vehicle for supply -> a little bit hard coded, but
     * well... All other Scenarios should copy the baseline to make it comparable
     * 
     *
     * @param supplyCarriers The generated supply carriers.
     * @return A Carriers object containing the split supply carriers.
     */
    private Carriers splitSupplyCarriers(Carriers supplyCarriers) {

        LOGGER.info("Split Supply Services in different Starting Points on the Map");

        Carriers splitSupplyCarriers = new Carriers();

        // Iterate over the original supply carriers
        for (Carrier carrier : new ArrayList<>(supplyCarriers.getCarriers().values())) {
            // Skip the carrier "from_dhl_anderten"
            if (!carrier.getId().toString().contains("from_dhl_anderten")) {
                // Create sub-carriers for each direction (North, East, South, West)
                Carriers splitCarriers = createSubSupplyCarriers(carrier);

                // Add split carriers to the main supplyCarriers if they have services
                for (Carrier splitCarrier : splitCarriers.getCarriers().values()) {
                    if (!splitCarrier.getServices().isEmpty()) {
                        splitSupplyCarriers.addCarrier(splitCarrier);
                        LOGGER.info("Split Carrier ID = {}, Direction = {}, Demand = {}",
                                splitCarrier.getId(),
                                getDirectionFromCarrierId(splitCarrier.getId().toString()),
                                getNumberOfParcels(splitCarrier));
                    }
                }
            } else {
                splitSupplyCarriers.addCarrier(carrier);
            }
        }

        return splitSupplyCarriers;
    }

    /**
     * Extracts the direction from the carrier ID.
     *
     * @param carrierId The ID of the carrier.
     * @return The direction (North, East, South, West).
     */
    private String getDirectionFromCarrierId(String carrierId) {
        if (carrierId.endsWith("north")) {
            return "north";
        } else if (carrierId.endsWith("east")) {
            return "east";
        } else if (carrierId.endsWith("south")) {
            return "south";
        } else if (carrierId.endsWith("west")) {
            return "west";
        } else {
            return "Unknown";
        }
    }

    /**
     * Creates sub-supply carriers for each direction (North, East, South, West)
     * from the original carrier.
     *
     * @param originalCarrier The original supply carrier to be split.
     * @return A Carriers object containing the sub-supply carriers.
     */
    private Carriers createSubSupplyCarriers(Carrier originalCarrier) {
        Carriers splitCarriers = new Carriers();
        Map<String, Carrier> subCarriers = new HashMap<>();

        // Initialize sub-carriers for each direction
        for (Map.Entry<String, Id<Link>> entry : SUPPLY_LINK_DIRECTIONS.entrySet()) {
            String direction = entry.getKey();
            Id<Link> linkId = entry.getValue();

            // Create a new sub-carrier with the original carrier's ID and the direction
            Carrier subCarrier = CarriersUtils
                    .createCarrier(Id.create(originalCarrier.getId().toString() + "_" + direction, Carrier.class));

            // Copy attributes from the original carrier to the sub-carrier
            subCarrier.getAttributes().putAttribute("carrierType",
                    originalCarrier.getAttributes().getAttribute("carrierType"));
            subCarrier.getAttributes().putAttribute("provider",
                    originalCarrier.getAttributes().getAttribute("provider"));
            subCarrier.getAttributes().putAttribute("supplyLink", linkId.toString());
            subCarrier.getAttributes().putAttribute("hubId", originalCarrier.getAttributes().getAttribute("hubId"));

            // Set up vehicles for the sub-carrier based on the link ID
            setupCarrierSupplyVehicles(subCarrier, linkId);

            // Set fleet size to infinite for the sub-carrier
            subCarrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);

            // Add the sub-carrier to the splitCarriers and subCarriers map
            splitCarriers.addCarrier(subCarrier);
            subCarriers.put(direction, subCarrier);
        }

        // Assign each service from the original carrier to a sub-carrier
        for (CarrierService service : originalCarrier.getServices().values()) {
            // Get a random direction based on predefined probabilities
            String direction = getRandomDirectionBasedOnProbability();
            Carrier subCarrier = subCarriers.get(direction);

            // Add the service to the corresponding sub-carrier
            CarriersUtils.addService(subCarrier, service);
        }

        return splitCarriers;
    }

    /**
     * Gets a random direction (North, East, South, West) based on predefined
     * probabilities.
     *
     * @return The direction.
     */
    private String getRandomDirectionBasedOnProbability() {
        double randomValue = rand.nextDouble();
        double cumulativeProbability = 0.0;

        // Iterate over the supply direction probabilities
        for (Map.Entry<String, Double> entry : SUPPLY_DIRECTION_PROBABILITIES.entrySet()) {
            cumulativeProbability += entry.getValue();
            if (randomValue <= cumulativeProbability) {
                return entry.getKey();
            }
        }

        // Default to "South" if something goes wrong
        return "South";
    }

    /**
     * Creates supply carriers based on existing carriers, hubs, and vehicle types.
     *
     * @param carriers Existing carriers in the scenario.
     * @param hubs     Map of hubs to be supplied.
     * @return Generated supply carriers.
     */
    private Carriers createSupplyCarriersAndServices(Carriers carriers, Map<Id<Hub>, Hub> hubs) {
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
            createWhiteLabelSupplyCarriers(hubs, supplyCarriers);
        } else {
            // Create supply carriers for non-white label scenario
            createNonWhiteLabelSupplyCarriers(hubs, supplyCarriers);
        }

        return supplyCarriers;
    }

    /**
     * Creates supply carriers for the white label scenario.
     *
     * @param hubs           Map of hubs to be supplied.
     * @param supplyCarriers Container for the generated supply carriers.
     */
    private void createWhiteLabelSupplyCarriers(Map<Id<Hub>, Hub> hubs, Carriers supplyCarriers) {
        for (Hub hub : hubs.values()) {
            if (hub.getAssignedSupplyDemand() > 0) {
                Carrier supplyCarrier = createSupplyCarrier(hub, getRandomSupplyLinkID(), null, true);
                addSupplyCarrierServices(supplyCarrier, hub, 0, null, null, null);
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
     * @param supplyCarriers Container for the generated supply carriers.
     */
    private void createNonWhiteLabelSupplyCarriers(Map<Id<Hub>, Hub> hubs, Carriers supplyCarriers) {

        LOGGER.info("Creating Hub Supply Carrier");

        Hub andertenHub = hubs.get(Id.create("dhl_hannover_anderten", Hub.class));
        int supplyToAnderten = 0;

        Carrier supplyCarrierDhlFromAnderten = createSupplyCarrier(andertenHub, andertenHub.getLink(),
                "supply_from_dhl_anderten", false);
        supplyCarriers.addCarrier(supplyCarrierDhlFromAnderten);

        Carrier supplyCarrierDhlToAnderten = createSupplyCarrier(andertenHub, getRandomSupplyLinkID(),
                "supply_to_dhl_anderten", false);
        supplyCarriers.addCarrier(supplyCarrierDhlToAnderten);

        // Sort the hubs before processing
        List<Hub> sortedHubs = sortHubs(hubs);

        for (Hub hub : sortedHubs) {
            if (hub.getAssignedSupplyDemand() > 0) {
                if (!hub.getProvider().equalsIgnoreCase("dhl")) {
                    Carrier supplyCarrierForHub = createSupplyCarrier(hub, getRandomSupplyLinkID(), null, false);
                    addSupplyCarrierServices(supplyCarrierForHub, hub, 0, null, null, null);                    
                    supplyCarriers.addCarrier(supplyCarrierForHub);
                } else {
                    supplyToAnderten += hub.getAssignedSupplyDemand();
                    if (!hub.getId().toString().contains("anderten")) {
                        addSupplyCarrierServices(supplyCarrierDhlFromAnderten, hub, 0, null, null, null);
                    }

                }
            }

        }

        addSupplyCarrierServices(supplyCarrierDhlToAnderten, null, supplyToAnderten, "dhl_anderten", "dhl",
                andertenHub.getLink());

        // Log all created supply carriers
        for (Carrier carrier : supplyCarriers.getCarriers().values()) {
            LOGGER.info("Supply Carrier ID = {}, Assigned Demand = {}", carrier.getId(), getNumberOfParcels(carrier));
        }

    }

    /**
     * Sorts the hubs such that non-DHL hubs come first, followed by Deutsche Post
     * hubs (dp/dhl),
     * and finally DHL hubs.
     *
     * @param hubs The map of hubs to be sorted.
     * @return A sorted list of hubs.
     */
    private List<Hub> sortHubs(Map<Id<Hub>, Hub> hubs) {
        List<Hub> sortedHubs = new ArrayList<>(hubs.values());
        sortedHubs.sort((hub1, hub2) -> {
            boolean isHub1DHL = hub1.getProvider().equalsIgnoreCase("dhl");
            boolean isHub1DeutschePost = hub1.getProvider().equalsIgnoreCase("dp/dhl");
            boolean isHub2DHL = hub2.getProvider().equalsIgnoreCase("dhl");
            boolean isHub2DeutschePost = hub2.getProvider().equalsIgnoreCase("dp/dhl");

            if (!isHub1DHL && !isHub1DeutschePost && (isHub2DHL || isHub2DeutschePost)) {
                return -1;
            } else if ((isHub1DHL || isHub1DeutschePost) && !isHub2DHL && !isHub2DeutschePost) {
                return 1;
            } else if (isHub1DeutschePost && isHub2DHL) {
                return -1;
            } else if (isHub1DHL && isHub2DeutschePost) {
                return 1;
            } else {
                return 0;
            }
        });
        return sortedHubs;
    }

    /**
     * Creates a supply carrier for the given hub with an optional carrier ID.
     *
     * @param hub          The hub to be supplied.
     * @param linkId       The link ID where the carrier is located.
     * @param carrierId    Optional carrier ID. If null, it will be generated based
     *                     on the hub ID.
     * @param isWhiteLabel Whether the scenario is white label.
     * @return The created supply carrier.
     */
    private Carrier createSupplyCarrier(Hub hub, Id<Link> linkId, String carrierId, boolean isWhiteLabel) {
        String id = carrierId == null ? "supply_" + hub.getId().toString().replace("/", "_") : carrierId;
        Carrier supplyCarrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
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

        Id<Link> finalLinkId = supplyLink == null ? linkId : Id.createLinkId(supplyLink);
        supplyCarrier.getAttributes().putAttribute("carrierType", "supply");
        supplyCarrier.getAttributes().putAttribute("supplyLink", finalLinkId.toString());
        supplyCarrier.getAttributes().putAttribute("hubId", hub.getId().toString());
        supplyCarrier.getAttributes().putAttribute("provider", hub.getProvider());        

        setupCarrierSupplyVehicles(supplyCarrier, linkId);

        supplyCarrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);

        return supplyCarrier;
    }

    /**
     * Sets up the carrier vehicles for the given carrier.
     *
     * @param carrier The carrier to set up vehicles for.
     * @param linkId  The link ID where the vehicles are located.
     */
    private void setupCarrierSupplyVehicles(Carrier carrier, Id<Link> linkId) {
        CarrierVehicle carrierVehicleEarly = carrierVehicleFactory.createSupplyVehicle(carrier.getId().toString(),
                linkId, true);
        CarriersUtils.addCarrierVehicle(carrier, carrierVehicleEarly);
        CarrierVehicle carrierVehicleLate = carrierVehicleFactory.createSupplyVehicle(carrier.getId().toString(),
                linkId, false);
        CarriersUtils.addCarrierVehicle(carrier, carrierVehicleLate);
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
     */
    private void addSupplyCarrierServices(Carrier carrier, Hub hub, int amount, String idHub, String company,
            Id<Link> linkId) {
        int amountToHub = (hub == null) ? amount : hub.getAssignedSupplyDemand();
        String hubId = (hub == null) ? idHub : hub.getId().toString();
        String hubCompany = (hub == null) ? company : hub.getProvider();
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
            CarriersUtils.addSkill(service, "supply"); 
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
        CarriersUtils.addSkill(service, "supply"); 
        CarriersUtils.addService(carrier, service);
    }

    /**
     * Validates the generated supply carriers by dividing them into direct and
     * indirect supply and validating each.
     *
     * @param supplyCarriers      The generated supply carriers.
     * @param splitSupplyCarriers
     * @param hubs                The hubs to be validated.
     * @param isWhiteLabel        Indicates if the scenario is white label.
     * @throws SupplyCarrierValidationException if the validation fails.
     */
    private void validateSupplyCarriers(Carriers supplyCarriers, Carriers splitSupplyCarriers, Map<Id<Hub>, Hub> hubs,
            boolean isWhiteLabel)
            throws SupplyCarrierValidationException {

        if (isWhiteLabel) {
            // Placeholder for validating white label supply carriers
            validateWhiteLabelSupplyCarriers(supplyCarriers, hubs);
        } else {
            // Validate direct supply carriers
            validateDirectSupplyCarriers(supplyCarriers, hubs);

            // Validate indirect supply carriers
            validateIndirectSupplyCarriers(supplyCarriers, hubs);

            if (splitSupplyCarriers != null) {
                // Validate splitted supply carriers
                validateSplitSupplyCarriers(supplyCarriers, splitSupplyCarriers, hubs);
            }

        }

    }

    /**
     * Validates the split supply carriers by ensuring that the total demand of the
     * split carriers matches the original carrier.
     *
     * @param supplyCarriers      The generated supply carriers.
     * @param splitSupplyCarriers The split supply carriers.
     * @param hubs                The hubs to be validated.
     * @throws SupplyCarrierValidationException if the validation fails.
     */
    private void validateSplitSupplyCarriers(Carriers supplyCarriers, Carriers splitSupplyCarriers,
            Map<Id<Hub>, Hub> hubs) throws SupplyCarrierValidationException {
        for (Carrier originalCarrier : supplyCarriers.getCarriers().values()) {
            // Skip "from_anderten" carrier
            if (originalCarrier.getId().toString().contains("from_dhl_anderten")) {
                continue;
            }
            int originalDemand = getNumberOfParcels(originalCarrier);
            int splitDemand = splitSupplyCarriers.getCarriers().values().stream()
                    .filter(carrier -> carrier.getId().toString().startsWith(originalCarrier.getId().toString()))
                    .mapToInt(this::getNumberOfParcels)
                    .sum();

            if (originalDemand != splitDemand) {
                throw new SupplyCarrierValidationException(String.format(
                        "Validation failed: Total demand for split carriers of %s (%d) does not match original demand (%d)",
                        originalCarrier.getId(), splitDemand, originalDemand));
            }

            LOGGER.info(
                    "Validation successful: Total demand for split carriers of {} matches original demand. Original Demand: {}, Split Demand: {}",
                    originalCarrier.getId(), originalDemand, splitDemand);
        }
    }

    /**
     * Validates the direct supply carriers (non-DHL/Deutsche Post).
     *
     * @param supplyCarriers The generated supply carriers.
     * @param hubs           The hubs to be validated.
     * @throws SupplyCarrierValidationException if the validation fails.
     */
    private void validateDirectSupplyCarriers(Carriers supplyCarriers, Map<Id<Hub>, Hub> hubs)
            throws SupplyCarrierValidationException {
        LOGGER.info("Starting validation of direct supply carriers...");
        for (Hub hub : hubs.values()) {
            if (!hub.getProvider().contains("dhl")) {
                int assignedDemand = hub.getAssignedSupplyDemand();
                int carrierDemand = supplyCarriers.getCarriers().values().stream()
                        .filter(carrier -> carrier.getAttributes().getAttribute("hubId").equals(hub.getId().toString()))
                        .flatMap(carrier -> carrier.getServices().values().stream())
                        .mapToInt(CarrierService::getCapacityDemand)
                        .sum();

                if (assignedDemand != carrierDemand) {
                    String errorMessage = String.format(
                            "Validation failed: Assigned demand for hub %s (%d) does not match carrier demand (%d)",
                            hub.getId(), assignedDemand, carrierDemand);
                    LOGGER.error(errorMessage);
                    throw new SupplyCarrierValidationException(errorMessage);
                }

                LOGGER.info("Hub {}: Assigned Demand = {}, Carrier Demand = {}", hub.getId(), assignedDemand,
                        carrierDemand);
            }
        }
        LOGGER.info(
                "Validation successful: Total assigned direct supply demand matches total direct supply carrier demand.");
    }

    /**
     * Validates the indirect supply carriers (DHL).
     *
     * @param supplyCarriers The generated supply carriers.
     * @param hubs           The hubs to be validated.
     * @throws SupplyCarrierValidationException if the validation fails.
     */
    private void validateIndirectSupplyCarriers(Carriers supplyCarriers, Map<Id<Hub>, Hub> hubs)
            throws SupplyCarrierValidationException {
        Hub andertenHub = hubs.get(Id.create("dhl_hannover_anderten", Hub.class));

        // Calculate the total assigned demand for DHL hubs excluding Anderten
        int totalAssignedDemandDHLExcludingAnderten = hubs.values().stream()
                .filter(hub -> hub.getProvider().contentEquals("dhl") && !hub.equals(andertenHub))
                .mapToInt(Hub::getAssignedSupplyDemand)
                .sum();

        // Calculate the total assigned demand for the hub with provider "dhl"
        int totalAssignedDemandProviderDHL = hubs.values().stream()
                .filter(hub -> hub.getProvider().equals("dhl"))
                .mapToInt(Hub::getAssignedSupplyDemand)
                .sum();

        Carrier fromAndertenCarrier = supplyCarriers.getCarriers().values().stream()
                .filter(carrier -> carrier.getId().toString().equals("supply_from_dhl_anderten"))
                .findFirst().orElse(null);

        Carrier toAndertenCarrier = supplyCarriers.getCarriers().values().stream()
                .filter(carrier -> carrier.getId().toString().equals("supply_to_dhl_anderten"))
                .findFirst().orElse(null);

        int fromAndertenCarrierDemand = fromAndertenCarrier != null ? getNumberOfParcels(fromAndertenCarrier) : 0;
        int toAndertenCarrierDemand = toAndertenCarrier != null ? getNumberOfParcels(toAndertenCarrier) : 0;

        if (totalAssignedDemandProviderDHL != toAndertenCarrierDemand) {
            String errorMessage = String.format(
                    "Validation failed: Total DHL provider assigned supply demand (%d) does not match carrier demand to Anderten (%d)",
                    totalAssignedDemandProviderDHL, toAndertenCarrierDemand);
            throw new SupplyCarrierValidationException(errorMessage);
        }

        if (totalAssignedDemandDHLExcludingAnderten != fromAndertenCarrierDemand) {
            String errorMessage = String.format(
                    "Validation failed: Total DHL assigned supply demand excluding Anderten (%d) does not match carrier demand from Anderten (%d)",
                    totalAssignedDemandDHLExcludingAnderten, fromAndertenCarrierDemand);
            throw new SupplyCarrierValidationException(errorMessage);
        }

        LOGGER.info(
                "Validation successful: Total DHL assigned supply demand matches total carrier demand from and to Anderten.");
        LOGGER.info("Total DHL provider assigned supply demand: {}", totalAssignedDemandProviderDHL);
        LOGGER.info("Carrier demand to Anderten: {}", toAndertenCarrierDemand);
        LOGGER.info("Total DHL assigned supply demand excluding Anderten: {}", totalAssignedDemandDHLExcludingAnderten);
        LOGGER.info("Carrier demand from Anderten: {}", fromAndertenCarrierDemand);
    }

    /**
     * Placeholder for validating the white label supply carriers.
     *
     * @param supplyCarriers The generated supply carriers.
     * @param hubs           The hubs to be validated.
     * @throws SupplyCarrierValidationException if the validation fails.
     */
    private void validateWhiteLabelSupplyCarriers(Carriers supplyCarriers, Map<Id<Hub>, Hub> hubs)
            throws SupplyCarrierValidationException {
        // Placeholder for white label supply carrier validation logic
        LOGGER.info("White label supply carriers validation is not implemented yet.");
    }

    /**
     * Retrieves a random supply link ID from a predefined list.
     *
     * @return A random supply link ID.
     */
    private Id<Link> getRandomSupplyLinkID() {
        return Id.createLinkId(SUPPLY_LINK_IDS.get(rand.nextInt(SUPPLY_LINK_IDS.size())));
    }
}
