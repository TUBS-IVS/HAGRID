package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import hagrid.HagridConfigGroup;
import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.Hub;
import hagrid.utils.demand.Delivery.ParcelType;
import hagrid.utils.general.HAGRIDSummary;
import hagrid.utils.general.HAGRIDUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ConcurrentModificationException;

/**
 * The CarrierGenerator class is responsible for converting sorted demand
 * into Carrier objects and validating the totals.
 */
@Singleton
public class CarrierGenerator implements Runnable {

        private static final Logger LOGGER = LogManager.getLogger(CarrierGenerator.class);
        private final static Random random = new Random();

        @Inject
        private Scenario scenario;

        @Inject
        private HagridConfigGroup hagridConfig;

        private CarrierVehicleFactory carrierVehicleFactory;

        /**
         * Executes the carrier generation process. This method retrieves the necessary
         * scenario elements, generates carriers and their services, and validates
         * the generated carriers against the original delivery summary.
         */
        @Override
        public void run() {
                try {
                        LOGGER.info("Generating carriers from sorted deliveries and parcels...");

                        // Get scenario elements
                        LOGGER.info("Getting scenario elements...");
                        final Map<String, ArrayList<Delivery>> deliveries = HAGRIDUtils.getScenarioElementAs(
                                        "deliveries",
                                        scenario);
                        final Network subNetwork = HAGRIDUtils.getScenarioElementAs("parcelServiceNetwork", scenario);
                        final CarrierVehicleTypes vehicleTypes = HAGRIDUtils.getScenarioElementAs("carrierVehicleTypes",
                                        scenario);
                        final Map<Id<Hub>, Hub> hubList = HAGRIDUtils.getScenarioElementAs("hubList", scenario);
                        LOGGER.info("Scenario elements retrieved.");

                        // Create an instance of CarrierVehicleFactory with the retrieved vehicle types
                        carrierVehicleFactory = new CarrierVehicleFactory(vehicleTypes);

                        // Process the deliveries to create carriers
                        final Carriers carriers = generateCarriersAndCarrierServices(deliveries, subNetwork,
                                        vehicleTypes, hubList);

                        // Validate the generated carriers and supply demand
                        validateCarriers(carriers);
                        validateSupplyDemand(carriers, hubList);

                        // Check and log attributes of all carriers
                        HAGRIDUtils.checkAndLogCarrierAttributes(carriers);

                        LOGGER.info("Carrier generation completed.");

                        new CarrierPlanWriter(carriers).write("phd/output/carriers.xml");

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
                final Map<String, Double> deliveryRate = new HashMap<>();

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

        /**
         * Generates carriers and their services based on the provided deliveries and
         * network.
         *
         * @param deliveries   Map containing the deliveries sorted by carrier ID.
         * @param subNetwork   The network used for parcel services.
         * @param hubList
         * @param vehicleTypes
         * @return The generated carriers.
         */
        private Carriers generateCarriersAndCarrierServices(final Map<String, ArrayList<Delivery>> deliveries,
                        final Network subNetwork, CarrierVehicleTypes vehicleTypes, Map<Id<Hub>, Hub> hubList) {
                final Carriers carriers = new Carriers();
                final Map<String, Double> deliveryRates = initializeDeliveryRate();

                deliveries.entrySet().stream().map(entry -> {
                        final String carrierID = entry.getKey();
                        final ArrayList<Delivery> carrierDeliveries = entry.getValue();

                        final Carrier carrier = CarriersUtils.createCarrier(Id.create(carrierID, Carrier.class));

                        setupCarrierAttributes(carrier, carrierID);

                        try {
                                addCarrierServicesToCarriers(carrier, carrierDeliveries, subNetwork, deliveryRates);
                                addCarrierVehiclesToCarrier(carrier, hubList);
                        } catch (ServiceCreationException e) {
                                LOGGER.error(carrierID + ": Error creating carrier services", e);
                        }

                        return carrier;
                }).forEach(carriers::addCarrier);

                logAndValidateInsufficientCarrier(carriers);

                LOGGER.info("Carriers generated: {}", carriers.getCarriers().size());

                scenario.addScenarioElement("carriers", carriers);

                return carriers;
        }

        /**
         * Logs information about carriers, including the ones with the most and least
         * services,
         * and removes carriers with fewer than 5 services. -> Problem with Hannover
         * Shape -> there are some deliveries not corretly removed
         * since the shape is not 100% correct - or at least does not fit to the zip
         * code areas
         * -> not a solid solution, but at least a working on i hope TODO adjust Shape
         * Files that the are inline with the zip code areas
         * -> lol not working, there are actualy more problems then I thought^^ ->
         * 2024-06-20 18:49:56 INFO CarrierGenerator:196 - Number of carriers removed
         * with fewer than 5 services: 24
         * -> need to check
         * -> fixed using postal codes -> keepe
         *
         * @param carriers The Carriers object containing all carriers.
         * @throws RuntimeException if any carriers have fewer than 5 services.
         */
        private void logAndValidateInsufficientCarrier(Carriers carriers) {
                // Find the carrier with the most services
                Optional<Carrier> carrierWithMostServices = carriers.getCarriers().values().stream()
                                .max(Comparator.comparingInt(carrier -> carrier.getServices().size()));

                // Find the carrier with the least services
                Optional<Carrier> carrierWithLeastServices = carriers.getCarriers().values().stream()
                                .min(Comparator.comparingInt(carrier -> carrier.getServices().size()));

                // Log the carrier with the most services
                carrierWithMostServices
                                .ifPresent(carrier -> LOGGER.info("Carrier with the most services: {} with {} services",
                                                carrier.getId(), carrier.getServices().size()));

                // Log the carrier with the least services
                carrierWithLeastServices.ifPresent(
                                carrier -> LOGGER.info("Carrier with the least services: {} with {} services",
                                                carrier.getId(), carrier.getServices().size()));

                // Collect IDs of carriers with fewer than 5 services
                List<Id<Carrier>> insufficientServiceCarrierIds = carriers.getCarriers().values().stream()
                                .filter(carrier -> carrier.getServices().size() < 5)
                                .map(Carrier::getId)
                                .collect(Collectors.toList());

                // Log the carriers with insufficient services
                LOGGER.info("Number of carriers with fewer than 5 services: {}", insufficientServiceCarrierIds.size());
                LOGGER.info("Carriers with insufficient services: {}", insufficientServiceCarrierIds.toString());

                if (!insufficientServiceCarrierIds.isEmpty()) {
                        throw new RuntimeException("There are carriers with fewer than 5 services. IDs: "
                                        + insufficientServiceCarrierIds.toString());
                }

                int remainingSize = carriers.getCarriers().size();
                LOGGER.info("Number of remaining carriers: {}", remainingSize);
        }

        /**
         * Sets up the attributes for a given carrier based on its ID.
         *
         * This method initializes the fleet size to infinite and sets several
         * attributes
         * including the provider, postal code, missed parcels count, and a list to
         * track
         * missed parcels.
         *
         * @param carrier   The carrier whose attributes are to be set.
         * @param carrierID The ID of the carrier, used to extract and set specific
         *                  attributes.
         */
        private void setupCarrierAttributes(Carrier carrier, String carrierID) {
                // Set the fleet size of the carrier to infinite
                carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);

                // Extract and set the provider attribute from the carrier ID
                carrier.getAttributes().putAttribute("provider", carrierID.split("_")[0]);

                // Extract and set the postal code (first 5 digits) attribute from the carrier
                // ID
                carrier.getAttributes().putAttribute("plz", carrierID.split("_")[1].substring(0, 5));

                // Initialize the missed parcels count to 0
                carrier.getAttributes().putAttribute("missedParcels", 0);

                // Initialize the missed parcels list to null
                carrier.getAttributes().putAttribute("missedParcelsAsList", null);
        }

        /**
         * Adds services to the carriers based on the provided deliveries and delivery
         * rates.
         *
         * This method takes a carrier and its deliveries, determines the number of
         * services needed
         * based on the delivery rates and vehicle capacity, and adds the corresponding
         * services
         * to the carrier. It also calculates and updates the total weight of parcels
         * and the correction
         * factor in the summary.
         *
         * @param carrier           The carrier to which services are to be added.
         * @param carrierDeliveries The deliveries for the carrier.
         * @param subNetwork        The network used for parcel services.
         * @param deliveryRates     The delivery rates for each provider.
         * @throws ServiceCreationException if a service cannot be created.
         */
        private void addCarrierServicesToCarriers(final Carrier carrier, final ArrayList<Delivery> carrierDeliveries,
                        final Network subNetwork, final Map<String, Double> deliveryRates)
                        throws ServiceCreationException {

                // Retrieve the provider attribute from the carrier
                final String provider = (String) carrier.getAttributes().getAttribute("provider");

                // Check if the delivery rate for the provider is available
                if (!deliveryRates.containsKey(provider)) {
                        throw new IllegalStateException("Delivery rate not available for provider: " + provider);
                }

                int totalServices = 0;
                int correctionFactor = 0;
                double totalWeightForCarrier = 0.0;
                List<CarrierService> createdServices = new ArrayList<>();

                // Iterate through each delivery associated with the carrier
                for (final Delivery carrierDelivery : carrierDeliveries) {
                        double rate = deliveryRates.get(provider);

                        // Set the delivery rate to 100% for B2B parcel types
                        if (carrierDelivery.getParcelType() == ParcelType.B2B) {
                                rate = 100.0;
                        }

                        final int amount = carrierDelivery.getAmount();
                        final int numberOfServices = (int) Math.ceil((double) amount / hagridConfig.getCepVehCap());
                        final int cap = hagridConfig.getCepVehCap();
                        final List<Double> weights = new ArrayList<>(carrierDelivery.getIndividualWeights());

                        // Calculate the total weight of parcels for the carrier
                        totalWeightForCarrier += weights.stream().mapToDouble(Double::doubleValue).sum();
                        LOGGER.debug("Processing carrier delivery: " + carrierDelivery);
                        LOGGER.debug("Initial weights: " + weights);

                        // Find the nearest link in the sub-network for the delivery location
                        final Id<Link> linkId = NetworkUtils
                                        .getNearestLinkExactly(subNetwork, carrierDelivery.getCoordinate())
                                        .getId();

                        // Create and add services for the carrier delivery
                        for (int j = 0; j < numberOfServices - 1; j++) {
                                List<Double> serviceWeights = new ArrayList<>(weights.subList(0, cap));
                                LOGGER.debug("Service " + totalServices + ": Weights for this service: "
                                                + serviceWeights);
                                weights.subList(0, cap).clear();
                                LOGGER.debug("Remaining weights after clearing: " + weights);
                                CarrierService service = addAndGetCarrierService(carrier, linkId, rate, cap,
                                                carrierDelivery,
                                                totalServices++, serviceWeights);
                                createdServices.add(service);
                                correctionFactor++; // Increment correction factor for each split
                                LOGGER.debug("Incremented correction factor. New value: {}", correctionFactor);
                        }

                        // Handle the last segment which might be smaller than the cap
                        final int remainingCapacity = amount - ((numberOfServices - 1) * cap);
                        if (remainingCapacity > 0) {
                                List<Double> serviceWeights = new ArrayList<>(weights.subList(0, remainingCapacity));
                                LOGGER.debug("Service " + totalServices + ": Weights for this service: "
                                                + serviceWeights);
                                CarrierService service = addAndGetCarrierService(carrier, linkId, rate,
                                                remainingCapacity, carrierDelivery,
                                                totalServices++, serviceWeights);
                                createdServices.add(service);
                        } else {
                                LOGGER.warn("Remaining capacity is zero for delivery: " + carrierDelivery);
                        }
                }

                // Validate the created carrier services against the original deliveries
                validateCarrierServices(carrierDeliveries, createdServices);

                // Update the correction factor in the summary
                final HAGRIDSummary summary = (HAGRIDSummary) HAGRIDUtils.getScenarioElementAs("summary", scenario);
                summary.setCorrectionFactor(summary.getCorrectionFactor() + correctionFactor);

                LOGGER.debug("Total weight for carrier {}: {}", carrier.getId(), totalWeightForCarrier);
        }

        /**
         * Adds a single service to a carrier and determines missed parcels based on the
         * delivery rate.
         *
         * This method creates and adds a service to a given carrier, setting various
         * attributes
         * and determining missed parcels if the delivery rate is less than 100%. The
         * service
         * duration, start time window, and other attributes are configured based on the
         * provided
         * parameters and the carrier's delivery information.
         *
         * @param carrier         The carrier to which the service is to be added.
         * @param linkId          The link ID where the service is located.
         * @param rate            The delivery rate for the service.
         * @param capacityDemand  The capacity demand of the service.
         * @param carrierDelivery The delivery information.
         * @param serviceNumber   The service number for unique identification.
         * @param weights         The weights of the parcels for this service.
         * @return The created CarrierService.
         * @throws ServiceCreationException if the service could not be created.
         */
        private CarrierService addAndGetCarrierService(final Carrier carrier, final Id<Link> linkId, final double rate,
                        final int capacityDemand, final Delivery carrierDelivery,
                        final int serviceNumber, final List<Double> weights) throws ServiceCreationException {

                List<Double> weightsCopy = null;
                try {
                        final double serviceDuration = Math.min(
                                        (hagridConfig.getDurationPerParcel() * 60) * capacityDemand,
                                        hagridConfig.getMaxDurationPerStop() * 60);

                        final double begin = hagridConfig.getDeliveryTimeWindowStart();
                        final double end = hagridConfig.getDeliveryTimeWindowEnd();

                        final String serviceId = String.format("service_%s_%s_%d", carrierDelivery.getParcelType(),
                                        carrier.getId(), serviceNumber);

                        final CarrierService.Builder serviceBuilder = CarrierService.Builder.newInstance(
                                        Id.create(serviceId, CarrierService.class), linkId);

                        serviceBuilder.setCapacityDemand(capacityDemand);
                        serviceBuilder.setServiceDuration(serviceDuration);
                        serviceBuilder.setServiceStartTimeWindow(TimeWindow.newInstance(begin, end));

                        final CarrierService service = serviceBuilder.build();
                        service.getAttributes().putAttribute("provider",
                                        carrier.getAttributes().getAttribute("provider"));
                        service.getAttributes().putAttribute("coord", carrierDelivery.getCoordinate());
                        service.getAttributes().putAttribute("type", carrierDelivery.getParcelType());
                        service.getAttributes().putAttribute("mode", carrierDelivery.getDeliveryMode());
                        service.getAttributes().putAttribute("postalcode", carrierDelivery.getPostalCode());

                        // Handle case where weights is null
                        if (weights == null) {
                                throw new IllegalArgumentException("Weights list is null");
                        }

                        // Create a copy of the weights list to avoid ConcurrentModificationException
                        weightsCopy = new ArrayList<>(weights);

                        // Convert the list of Doubles to a single String
                        String weightsString = weightsCopy.stream()
                                        .map(String::valueOf)
                                        .collect(Collectors.joining(";"));

                        service.getAttributes().putAttribute("weights", weightsString);

                        if (rate < 100.0) {
                                determineMissedParcels(carrier, service, rate);
                        }

                        CarriersUtils.addService(carrier, service);

                        return service;

                } catch (IllegalArgumentException e) {
                        // Log all necessary information for debugging
                        String weightsStr = (weightsCopy != null) ? weightsCopy.toString()
                                        : ((weights != null) ? weights.toString() : "null");
                        LOGGER.error("Failed to add service. Carrier: " + carrier.getId() +
                                        ", LinkId: " + linkId + ", Rate: " + rate +
                                        ", CapacityDemand: " + capacityDemand +
                                        ", CarrierDelivery: " + carrierDelivery +
                                        ", ServiceNumber: " + serviceNumber +
                                        ", Weights: " + weightsStr, e);
                        throw new ServiceCreationException("Failed to add service due to illegal argument.", e);
                } catch (ConcurrentModificationException e) {
                        // Log and handle concurrent modification
                        String weightsStr = (weightsCopy != null) ? weightsCopy.toString()
                                        : ((weights != null) ? weights.toString() : "null");
                        LOGGER.error("Concurrent modification detected. Carrier: " + carrier.getId() +
                                        ", LinkId: " + linkId + ", Rate: " + rate +
                                        ", CapacityDemand: " + capacityDemand +
                                        ", CarrierDelivery: " + carrierDelivery +
                                        ", ServiceNumber: " + serviceNumber +
                                        ", Weights: " + weightsStr, e);
                        throw new ServiceCreationException("Concurrent modification detected while adding service.", e);
                }
        }

        /**
         * Determines missed parcels based on the delivery rate and updates the
         * carrier's attributes accordingly.
         *
         * @param carrier The carrier for which missed parcels are being determined.
         * @param service The service being added.
         * @param rate    The delivery rate for the service.
         */
        private void determineMissedParcels(final Carrier carrier, final CarrierService service, final double rate) {

                final int amount = service.getCapacityDemand();
                int missed = 0;
                final ArrayList<Id<CarrierService>> missedDeliveries = new ArrayList<>();

                for (int a = 0; a < amount; a++) {
                        final double randomNumber = random.nextDouble() * 100;

                        if (randomNumber > rate) {
                                missedDeliveries.add(service.getId());
                                missed++;
                        }
                }

                final int currentMissed = (int) carrier.getAttributes().getAttribute("missedParcels");
                final int newMissed = currentMissed + missed;

                carrier.getAttributes().putAttribute("missedParcels", newMissed);

                // Get the current list of missed parcels and append the new missed deliveries
                ArrayList<Id<CarrierService>> currentMissedList = (ArrayList<Id<CarrierService>>) carrier
                                .getAttributes()
                                .getAttribute("missedParcelsAsList");

                if (currentMissedList == null) {
                        currentMissedList = new ArrayList<>();
                }

                currentMissedList.addAll(missedDeliveries);

                carrier.getAttributes().putAttribute("missedParcelsAsList", currentMissedList);
        }

        /**
         * Adds vehicles to a carrier based on the carrier ID and closest hub.
         *
         * This method determines the closest hub for a carrier, sets the relevant
         * attributes, and adds vehicles to the carrier with appropriate start times
         * based on the provider.
         *
         * @param carrier               The carrier to which vehicles will be added.
         * @param carrierVehicleFactory The types of vehicles available for assignment.
         * @param hubs                  A map of hubs used to find the closest hub.
         */
        private void addCarrierVehiclesToCarrier(final Carrier carrier,
                        final Map<Id<Hub>, Hub> hubs) {

                // Find the closest hub for the carrier based on its ID and number of parcels
                Hub closestHub = getClosestHub(carrier, hubs);

                // Set hub attributes for the carrier
                carrier.getAttributes().putAttribute("hub", closestHub);
                carrier.getAttributes().putAttribute("hubId", closestHub.getId().toString());

                // Retrieve start and end times from configuration
                int start = hagridConfig
                                .getDeliveryStartTime((String) carrier.getAttributes().getAttribute("provider"));
                int end = hagridConfig.getDeliveryEndTime((String) carrier.getAttributes().getAttribute("provider"));
                int maxRouteDuration = hagridConfig.getMaxRouteDuration();

                // Add vehicles to the carrier with start times from the determined range
                for (int startTime = start; startTime <= end; startTime++) {
                        CarrierVehicle carrierVehicleSizeM = carrierVehicleFactory.createCEPVehicle(
                                        closestHub.getLink(),
                                        closestHub.getId().toString(), startTime, maxRouteDuration, "m");
                        CarrierVehicle carrierVehicleSizeL = carrierVehicleFactory.createCEPVehicle(
                                        closestHub.getLink(),
                                        closestHub.getId().toString(), startTime, maxRouteDuration, "l");
                        CarriersUtils.addCarrierVehicle(carrier, carrierVehicleSizeM);
                        CarriersUtils.addCarrierVehicle(carrier, carrierVehicleSizeL);
                }

                // Log the creation of the carrier and its services
                LOGGER.debug("Created Carrier {} with a number of {} services", carrier.getId(),
                                carrier.getServices().values().size());

        }

        /**
         * Finds the closest hub with enough capacity for the given number of parcels.
         *
         * @param carrier The carrier with services to be assigned to a hub.
         * @param hubList A map of hub IDs to Hub objects.
         * @return The closest Hub with sufficient capacity.
         */
        private static Hub getClosestHub(Carrier carrier, Map<Id<Hub>, Hub> hubList) {

                // Create a GeometryFactory for point creation
                GeometryFactory geometryFactory = new GeometryFactory();

                // Determine the number of parcels for the carrier
                int numberOfParcels = carrier.getServices().values().stream()
                                .mapToInt(CarrierService::getCapacityDemand)
                                .sum();
                carrier.getAttributes().putAttribute("numberOfParcels", numberOfParcels);

                // Extract the provider from the carrier's attributes
                String provider = (String) carrier.getAttributes().getAttribute("provider");

                // Find the closest hub
                Optional<Hub> closestHub = carrier.getServices().values().stream()
                                .flatMap(service -> {
                                        Coord serviceCoord = (Coord) service.getAttributes().getAttribute("coord");
                                        Point servicePoint = geometryFactory
                                                        .createPoint(new Coordinate(serviceCoord.getX(),
                                                                        serviceCoord.getY()));
                                        return hubList.values().stream()
                                                        .filter(hub -> hub.getCompany().contains(provider)
                                                                        && hub.hasCapacity(numberOfParcels))
                                                        .map(hub -> new Object[] { hub, servicePoint, hub.getCoord() });
                                })
                                .min(Comparator.comparingDouble(hubServiceCoord -> NetworkUtils.getEuclideanDistance(
                                                ((Point) hubServiceCoord[1]).getX(),
                                                ((Point) hubServiceCoord[1]).getY(),
                                                ((Coord) hubServiceCoord[2]).getX(),
                                                ((Coord) hubServiceCoord[2]).getY())))
                                .map(hubServiceCoord -> (Hub) hubServiceCoord[0]);

                // Throw an exception if no suitable hub is found
                if (!closestHub.isPresent()) {
                        throw new IllegalStateException("No suitable hub found for provider: " + provider);
                }

                Hub foundHub = closestHub.get();
                // Increase the assigned supply demand for the closest hub
                foundHub.increaseAssignedSupplyDemand(numberOfParcels);

                // Log the found hub
                LOGGER.debug("Found hub: {} for provider: {}", closestHub.get().getId(), provider);
                return foundHub;
        }

        /**
         * Validates the carriers by comparing the generated carrier services with the
         * original delivery summary.
         *
         * @param carriers The carriers to be validated.
         */
        private void validateCarriers(final Carriers carriers) {

                LOGGER.info("Validating carrier missed deliveries...");

                try {
                        validateMissedParcelDeliveries(carriers);
                } catch (ServiceCreationException e) {
                        LOGGER.error("Error validating missed deliveries", e);
                }
                LOGGER.info("Validation of carrier missed deliveries completed.");

                LOGGER.info("Validating carrier services...");

                final HAGRIDSummary summary = (HAGRIDSummary) HAGRIDUtils.getScenarioElementAs("summary", scenario);
                final Map<String, Double> deliveryRates = initializeDeliveryRate(); // Initialize delivery rates

                final int totalServices = carriers.getCarriers().values().stream()
                                .mapToInt(carrier -> carrier.getServices().size())
                                .sum();

                final int totalParcels = carriers.getCarriers().values().stream()
                                .flatMap(carrier -> carrier.getServices().values().stream())
                                .mapToInt(CarrierService::getCapacityDemand)
                                .sum();

                final int totalB2BServices = (int) carriers.getCarriers().values().stream()
                                .flatMap(carrier -> carrier.getServices().values().stream())
                                .filter(service -> ParcelType.B2B.toString()
                                                .equals(service.getAttributes().getAttribute("type").toString()))
                                .count();

                final int totalB2BParcels = carriers.getCarriers().values().stream()
                                .flatMap(carrier -> carrier.getServices().values().stream())
                                .filter(service -> ParcelType.B2B.toString()
                                                .equals(service.getAttributes().getAttribute("type").toString()))
                                .mapToInt(CarrierService::getCapacityDemand)
                                .sum();

                final int correctionFactor = summary.getCorrectionFactor(); // Get the correction factor from the
                                                                            // summary
                LOGGER.info(String.format("Correction factor for Delivery Size Adjustment: %d", correctionFactor));

                final StringBuilder validationErrors = new StringBuilder();

                if (totalServices != summary.getTotalDeliveries() + correctionFactor) {
                        validationErrors.append(
                                        String.format("Validation failed: Total deliveries do not match. Expected %d, but got %d.%n",
                                                        summary.getTotalDeliveries(),
                                                        totalServices - correctionFactor));
                }

                if (totalParcels != summary.getTotalParcels()) {
                        validationErrors.append(
                                        String.format("Validation failed: Total parcels do not match. Expected %d, but got %d.%n",
                                                        summary.getTotalParcels(), totalParcels));
                }

                if (totalB2BServices != summary.getTotalB2BDeliveries() + correctionFactor) {
                        validationErrors.append(
                                        String.format("Validation failed: Total B2B deliveries do not match. Expected %d, but got %d.%n",
                                                        summary.getTotalB2BDeliveries(),
                                                        totalB2BServices - correctionFactor));
                }

                if (totalB2BParcels != summary.getTotalB2BParcels()) {
                        validationErrors.append(
                                        String.format("Validation failed: Total B2B parcels do not match. Expected %d, but got %d.%n",
                                                        summary.getTotalB2BParcels(), totalB2BParcels));
                }

                // Validate capacityDemand matches the number of weights
                carriers.getCarriers().values().forEach(carrier -> {
                        final double[] totalServiceWeight = { 0.0 }; // Use an array to hold the weight

                        carrier.getServices().values().forEach(service -> {
                                String weightsString = (String) service.getAttributes().getAttribute("weights");
                                List<Double> weights = parseWeights(weightsString);
                                totalServiceWeight[0] += weights.stream().mapToDouble(Double::doubleValue).sum();
                                if (weights.size() != service.getCapacityDemand()) {
                                        validationErrors.append(
                                                        String.format("Validation failed: Service %s has capacityDemand %d but %d weights.%n",
                                                                        service.getId(), service.getCapacityDemand(),
                                                                        weights.size()));
                                }
                        });

                        LOGGER.debug("Total service weight for carrier {}: {}", carrier.getId(), totalServiceWeight[0]);
                });

                // Map to store the sum of parcels for each provider
                final Map<String, List<Double>> providerParcelRates = new HashMap<>();

                carriers.getCarriers().values().forEach(carrier -> {
                        final String provider = (String) carrier.getAttributes().getAttribute("provider");
                        final double totalCarrierParcels = carrier.getServices().values().stream()
                                        .mapToInt(CarrierService::getCapacityDemand)
                                        .sum();

                        // Handle the type conversion properly
                        final Object missedParcelsObj = carrier.getAttributes().getAttribute("missedParcels");
                        final double missedCarrierParcels;
                        if (missedParcelsObj instanceof Integer) {
                                missedCarrierParcels = ((Integer) missedParcelsObj).doubleValue();
                        } else if (missedParcelsObj instanceof Double) {
                                missedCarrierParcels = (Double) missedParcelsObj;
                        } else {
                                missedCarrierParcels = 0.0;
                        }

                        if (totalCarrierParcels > 0) {
                                double missedParcelRate = missedCarrierParcels / totalCarrierParcels;
                                providerParcelRates.computeIfAbsent(provider, k -> new ArrayList<>())
                                                .add(missedParcelRate);
                        }
                });

                // Log the missed parcels for each provider and compare with the rate
                providerParcelRates.forEach((provider, rates) -> {
                        double averageMissedRate = 1
                                        - rates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                        LOGGER.info(String.format("Missed parcels for provider %s: average rate %.2f%%", provider,
                                        averageMissedRate * 100));

                        double expectedRate = deliveryRates.get(provider);
                        if ("amazon".equals(provider)) {
                                // No adjustment for Amazon
                        } else if ("ups".equals(provider) || "fedex".equals(provider) || "dpd".equals(provider)) {
                                expectedRate += 3;
                        } else {
                                expectedRate += 2;
                        }
                        LOGGER.info(String.format("Expected rate for provider %s: %.2f%%", provider, expectedRate));
                });

                // Log the overall weight statistics and their distribution by provider
                StringBuilder logBuilder = new StringBuilder();
                logBuilder.append("=== Delivery Statistics by Provider ===\n");

                long totalServiceCount = 0;
                long totalB2BServiceCount = 0;
                long totalParcelCount = 0;
                long totalB2BParcelCount = 0;
                double totalWeight = 0.0;
                double totalB2BWeight = 0.0;

                Map<String, List<CarrierService>> servicesByProvider = carriers.getCarriers().values().stream()
                                .flatMap(carrier -> carrier.getServices().values().stream())
                                .collect(Collectors.groupingBy(
                                                service -> (String) service.getAttributes().getAttribute("provider")));

                for (Map.Entry<String, List<CarrierService>> entry : servicesByProvider.entrySet()) {
                        String provider = entry.getKey();
                        List<CarrierService> services = entry.getValue();

                        long providerServiceCount = services.size();
                        long providerB2BServiceCount = services.stream()
                                        .filter(service -> ParcelType.B2B.toString()
                                                        .equals(service.getAttributes().getAttribute("type")
                                                                        .toString()))
                                        .count();
                        long providerParcelCount = services.stream()
                                        .mapToLong(CarrierService::getCapacityDemand)
                                        .sum();
                        long providerB2BParcelCount = services.stream()
                                        .filter(service -> ParcelType.B2B.toString()
                                                        .equals(service.getAttributes().getAttribute("type")
                                                                        .toString()))
                                        .mapToLong(CarrierService::getCapacityDemand)
                                        .sum();

                        double providerTotalWeight = services.stream()
                                        .flatMapToDouble(service -> parseWeights(
                                                        (String) service.getAttributes().getAttribute("weights"))
                                                        .stream()
                                                        .mapToDouble(Double::doubleValue))
                                        .sum();
                        double providerB2BWeight = services.stream()
                                        .filter(service -> ParcelType.B2B.toString()
                                                        .equals(service.getAttributes().getAttribute("type")
                                                                        .toString()))
                                        .flatMapToDouble(service -> parseWeights(
                                                        (String) service.getAttributes().getAttribute("weights"))
                                                        .stream()
                                                        .mapToDouble(Double::doubleValue))
                                        .sum();
                        double providerAverageWeight = providerParcelCount == 0 ? 0
                                        : providerTotalWeight / providerParcelCount;
                        double providerAverageB2BWeight = providerB2BParcelCount == 0 ? 0
                                        : providerB2BWeight / providerB2BParcelCount;

                        totalServiceCount += providerServiceCount;
                        totalB2BServiceCount += providerB2BServiceCount;
                        totalParcelCount += providerParcelCount;
                        totalB2BParcelCount += providerB2BParcelCount;
                        totalWeight += providerTotalWeight;
                        totalB2BWeight += providerB2BWeight;

                        logBuilder.append(String.format(
                                        "Provider: %s\n  Total Services     : %,d\n  B2B Services       : %,d\n  Total Parcels      : %,d\n  B2B Parcels        : %,d\n  B2B Service Ratio  : %.2f%%\n  B2B Parcel Ratio   : %.2f%%\n  Total Weight       : %.2f\n  Total B2B Weight   : %.2f\n  Average Weight     : %.2f\n  Average B2B Weight : %.2f\n\n",
                                        provider, providerServiceCount, providerB2BServiceCount, providerParcelCount,
                                        providerB2BParcelCount,
                                        (double) providerB2BServiceCount / providerServiceCount * 100,
                                        (double) providerB2BParcelCount / providerParcelCount * 100,
                                        providerTotalWeight, providerB2BWeight, providerAverageWeight,
                                        providerAverageB2BWeight));
                }

                double totalAverageWeight = totalParcelCount == 0 ? 0 : totalWeight / totalParcelCount;
                double totalAverageB2BWeight = totalB2BParcelCount == 0 ? 0 : totalB2BWeight / totalB2BParcelCount;

                logBuilder.append("=== Total Statistics ===\n");
                logBuilder.append(String.format(
                                "Total Services     : %,d\nTotal B2B Services : %,d\nTotal Parcels      : %,d\nTotal B2B Parcels  : %,d\nTotal Weight       : %.2f\nTotal B2B Weight   : %.2f\nAverage Weight     : %.2f\nAverage B2B Weight : %.2f\n",
                                totalServiceCount, totalB2BServiceCount, totalParcelCount, totalB2BParcelCount,
                                totalWeight, totalB2BWeight, totalAverageWeight, totalAverageB2BWeight));

                LOGGER.info(logBuilder.toString());

                if (validationErrors.length() > 0) {
                        throw new IllegalStateException(validationErrors.toString());
                } else {
                        LOGGER.info("Validation passed: All carrier services match the original deliveries summary.");
                        LOGGER.info(String.format("Total deliveries match: %d.", totalServices - correctionFactor));
                        LOGGER.info(String.format("Total parcels match: %d.", totalParcels));
                        LOGGER.info(String.format("Total B2B deliveries match: %d.",
                                        totalB2BServices - correctionFactor));
                        LOGGER.info(String.format("Total B2B parcels match: %d.", totalB2BParcels));
                }

                LOGGER.info("Validation of carrier completed.");
        }

        /**
         * Validates that the assigned supply demand to all hubs matches the total
         * parcels.
         *
         * @param carriers A map of carrier IDs to Carrier objects.
         * @param hubs     A map of hub IDs to Hub objects.
         * @throws IllegalStateException If the validation fails.
         */
        private void validateSupplyDemand(Carriers carriers, Map<Id<Hub>, Hub> hubs) {
                // Log the start of the validation process
                LOGGER.info("Starting validation of supply demand...");

                // Calculate the total parcels across all carriers
                final int totalParcels = carriers.getCarriers().values().stream()
                                .flatMap(carrier -> carrier.getServices().values().stream())
                                .mapToInt(CarrierService::getCapacityDemand)
                                .sum();

                // Calculate the total assigned supply demand across all hubs
                final int totalAssignedSupplyDemand = hubs.values().stream()
                                .mapToInt(Hub::getAssignedSupplyDemand)
                                .sum();

                // Log the assigned supply demand for each hub
                hubs.forEach((hubId, hub) -> {
                        int assignedSupplyDemand = hub.getAssignedSupplyDemand();
                        LOGGER.info("Hub ID: {}, Assigned Supply Demand: {}", hubId, assignedSupplyDemand);
                });

                // Log the validation result
                LOGGER.info("Total Parcels: {}, Total Assigned Supply Demand: {}", totalParcels,
                                totalAssignedSupplyDemand);

                // Throw an exception if the total assigned supply demand does not match the
                // total parcels
                if (totalParcels != totalAssignedSupplyDemand) {
                        throw new IllegalStateException("Validation failed: Total parcels (" + totalParcels +
                                        ") do not match total assigned supply demand (" + totalAssignedSupplyDemand
                                        + ").");
                }

                // Log successful validation
                LOGGER.info("Validation successful: Total parcels match total assigned supply demand.");
        }

        /**
         * Validates that the created services match the original deliveries.
         *
         * @param carrierDeliveries The original deliveries.
         * @param createdServices   The created carrier services.
         * @throws ServiceCreationException if validation fails.
         */
        private void validateCarrierServices(final ArrayList<Delivery> carrierDeliveries,
                        final List<CarrierService> createdServices) throws ServiceCreationException {
                Map<String, Double> originalWeightsMap = carrierDeliveries.stream()
                                .collect(Collectors.toMap(
                                                d -> String.format("%s_%s", d.getParcelType(), d.getCoordinate()),
                                                d -> d.getIndividualWeights().stream().mapToDouble(Double::doubleValue)
                                                                .sum(),
                                                Double::sum));

                Map<String, Double> createdWeightsMap = createdServices.stream()
                                .collect(Collectors.toMap(
                                                s -> String.format("%s_%s", s.getAttributes().getAttribute("type"),
                                                                s.getAttributes().getAttribute("coord")),
                                                s -> Arrays.stream(((String) s.getAttributes().getAttribute("weights"))
                                                                .split(";"))
                                                                .mapToDouble(Double::parseDouble).sum(),
                                                Double::sum));

                for (Map.Entry<String, Double> entry : originalWeightsMap.entrySet()) {
                        String key = entry.getKey();
                        double originalWeight = entry.getValue();
                        double createdWeight = createdWeightsMap.getOrDefault(key, 0.0);

                        double epsilon = 1e-9; // Epsilon for floating point comparison

                        if (Math.abs(originalWeight - createdWeight) > epsilon) {
                                throw new ServiceCreationException("Validation failed for key: " + key +
                                                ". Original weight: " + originalWeight + ", Created weight: "
                                                + createdWeight, null);
                        }
                }

                LOGGER.debug("Validation passed for all deliveries and created services.");
        }

        /**
         * Validates that the missed deliveries match the expected missed deliveries for
         * each carrier.
         *
         * @param carriers The list of carriers to validate.
         * @throws ServiceCreationException if validation fails.
         */
        private void validateMissedParcelDeliveries(Carriers carriers) throws ServiceCreationException {
                for (Carrier carrier : carriers.getCarriers().values()) {
                        int expectedMissedDeliveries = (int) carrier.getAttributes().getAttribute("missedParcels");
                        List<Id<CarrierService>> missedDeliveries = (List<Id<CarrierService>>) carrier.getAttributes()
                                        .getAttribute("missedParcelsAsList");

                        if (missedDeliveries.size() != expectedMissedDeliveries) {
                                throw new ServiceCreationException("Validation failed for carrier: " + carrier.getId() +
                                                ". Expected missed parcel deliveries: " + expectedMissedDeliveries +
                                                ", Actual missed parcel deliveries: " + missedDeliveries.size(), null);
                        }
                }

                LOGGER.info("Validation passed for all carriers and their missed parcel deliveries.");
        }

        /**
         * Parses a semicolon-separated string of weights into a list of Double values.
         *
         * This method takes a string representation of weights, where individual
         * weights
         * are separated by semicolons, and converts it into a list of Double objects.
         * If
         * the input string is null or empty, the method returns an empty list.
         *
         * @param weightsString A string containing weights separated by semicolons.
         * @return A list of Double objects representing the weights.
         */
        private List<Double> parseWeights(String weightsString) {
                // Check if the input string is null or empty
                if (weightsString == null || weightsString.isEmpty()) {
                        // Return an empty list if the input string is null or empty
                        return new ArrayList<>();
                }

                // Split the input string by semicolons, convert each segment to a Double,
                // and collect the results into a list
                return Arrays.stream(weightsString.split(";"))
                                .map(Double::valueOf)
                                .collect(Collectors.toList());
        }
}
