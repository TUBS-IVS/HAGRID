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
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;

import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Arrays;

/**
 * The CarrierGenerator class is responsible for converting sorted demand
 * into Carrier objects and validating the totals.
 */
@Singleton
public class CarrierGenerator implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(CarrierGenerator.class);
    private final Random random = new Random();

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfigGroup hagridConfig;

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
            final Map<String, ArrayList<Delivery>> deliveries = HAGRIDUtils.getScenarioElementAs("deliveries",
                    scenario);
            final Network subNetwork = HAGRIDUtils.getScenarioElementAs("parcelServiceNetwork", scenario);
            LOGGER.info("Scenario elements retrieved.");

            // Process the deliveries to create carriers
            final Carriers carriers = generateCarriersAndCarrierServices(deliveries, subNetwork);
            validateCarriers(carriers);

            LOGGER.info("Carrier generation completed.");
            // Convert carrier attributes to String
            HAGRIDUtils.convertCarrierAttributesToString(carriers);
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
     * @param deliveries Map containing the deliveries sorted by carrier ID.
     * @param subNetwork The network used for parcel services.
     * @return The generated carriers.
     */
    private Carriers generateCarriersAndCarrierServices(final Map<String, ArrayList<Delivery>> deliveries,
            final Network subNetwork) {
        final Carriers carriers = new Carriers();
        final Map<String, Double> deliveryRates = initializeDeliveryRate();

        deliveries.entrySet().stream().map(entry -> {
            final String carrierID = entry.getKey();
            final ArrayList<Delivery> carrierDeliveries = entry.getValue();

            final Carrier carrier = CarriersUtils.createCarrier(Id.create(carrierID, Carrier.class));

            carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);
            carrier.getAttributes().putAttribute("provider", carrierID.split("_")[0]);
            carrier.getAttributes().putAttribute("plz", carrierID.split("_")[1].substring(0, 5));
            carrier.getAttributes().putAttribute("missedParcels", 0);
            carrier.getAttributes().putAttribute("missedParcelsAsList", "");

            addCarrierServicesToCarriers(carrier, carrierDeliveries, subNetwork, deliveryRates);

            return carrier;
        }).forEach(carriers::addCarrier);

        LOGGER.info("Carriers generated: {}", carriers.getCarriers().size());
        scenario.addScenarioElement("carriers", carriers);

        return carriers;
    }

    /**
     * Adds services to the carriers based on the provided deliveries and delivery
     * rates.
     *
     * @param carrier           The carrier to which services are to be added.
     * @param carrierDeliveries The deliveries for the carrier.
     * @param subNetwork        The network used for parcel services.
     * @param deliveryRates     The delivery rates for each provider.
     */
    private void addCarrierServicesToCarriers(final Carrier carrier, final ArrayList<Delivery> carrierDeliveries,
            final Network subNetwork, final Map<String, Double> deliveryRates) {
        final String provider = (String) carrier.getAttributes().getAttribute("provider");
        if (!deliveryRates.containsKey(provider)) {
            throw new IllegalStateException("Delivery rate not available for provider: " + provider);
        }

        int totalServices = 0;
        int correctionFactor = 0;
        for (final Delivery carrierDelivery : carrierDeliveries) {
            double rate = deliveryRates.get(provider);
            if (carrierDelivery.getParcelType() == ParcelType.B2B) {
                rate = 100.0;
            }

            final int amount = carrierDelivery.getAmount();
            final int numberOfServices = (int) Math.ceil((double) amount / hagridConfig.getCepVehCap());
            final int cap = hagridConfig.getCepVehCap();
            final List<Double> weights = new ArrayList<>(carrierDelivery.getIndividualWeights());

            final Id<Link> linkId = NetworkUtils.getNearestLinkExactly(subNetwork, carrierDelivery.getCoordinate())
                    .getId();

            for (int j = 0; j < numberOfServices - 1; j++) {
                List<Double> serviceWeights = weights.subList(0, cap);
                weights.subList(0, cap).clear();
                addCarrierService(carrier, linkId, rate, cap, carrierDelivery, totalServices++, serviceWeights);
                correctionFactor++; // Increment correction factor for each split
            }

            final int remainingCapacity = amount - ((numberOfServices - 1) * cap);
            List<Double> serviceWeights = weights.subList(0, remainingCapacity);
            addCarrierService(carrier, linkId, rate, remainingCapacity, carrierDelivery, totalServices++, serviceWeights);
        }

        final HAGRIDSummary summary = (HAGRIDSummary) HAGRIDUtils.getScenarioElementAs("summary", scenario);
        summary.setCorrectionFactor(summary.getCorrectionFactor() + correctionFactor); // Increment correction factor in summary

    }

    /**
     * Adds a single service to a carrier and determines missed parcels based on the
     * delivery rate.
     *
     * @param carrier         The carrier to which the service is to be added.
     * @param linkId          The link ID where the service is located.
     * @param rate            The delivery rate for the service.
     * @param capacityDemand  The capacity demand of the service.
     * @param carrierDelivery The delivery information.
     * @param serviceNumber   The service number for unique identification.
     * @param weights         The weights of the parcels for this service.
     */
    private void addCarrierService(final Carrier carrier, final Id<Link> linkId, final double rate,
            final int capacityDemand,
            final Delivery carrierDelivery, final int serviceNumber, final List<Double> weights) {
        final double serviceDuration = Math.min((hagridConfig.getDurationPerParcel() * 60) * capacityDemand,
                hagridConfig.getMaxDurationPerStop() * 60);

        final double begin = hagridConfig.getDeliveryTimeWindowStart();
        final double end = hagridConfig.getDeliveryTimeWindowEnd();

        final String serviceId = String.format("service_%s_%s_%d", carrierDelivery.getParcelType(), carrier.getId(),
                serviceNumber);

        final CarrierService.Builder serviceBuilder = CarrierService.Builder.newInstance(
                Id.create(serviceId, CarrierService.class), linkId);

        serviceBuilder.setCapacityDemand(capacityDemand);
        serviceBuilder.setServiceDuration(serviceDuration);
        serviceBuilder.setServiceStartTimeWindow(TimeWindow.newInstance(begin, end));
        

        final CarrierService service = serviceBuilder.build();
        service.getAttributes().putAttribute("provider", carrier.getAttributes().getAttribute("provider"));
        service.getAttributes().putAttribute("coord", carrierDelivery.getCoordinate());
        service.getAttributes().putAttribute("type", carrierDelivery.getParcelType());
        service.getAttributes().putAttribute("weights", weights);

        if (rate < 100.0) {
            determineMissedParcels(carrier, service, rate);
        }

        CarriersUtils.addService(carrier, service);
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
        carrier.getAttributes().putAttribute("missedParcelsAsList", Arrays.toString(missedDeliveries.toArray()));

    }

    /**
     * Validates the carriers by comparing the generated carrier services with the
     * original delivery summary.
     *
     * @param carriers The carriers to be validated.
     */
    private void validateCarriers(final Carriers carriers) {

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

        final int correctionFactor = summary.getCorrectionFactor(); // Get the correction factor from the summary
        LOGGER.info("Correction factor for Delivery Size Adjustment: {}", correctionFactor);

        final StringBuilder validationErrors = new StringBuilder();

        if (totalServices != summary.getTotalDeliveries() + correctionFactor) {
            validationErrors.append(
                    String.format("Validation failed: Total deliveries do not match. Expected %d, but got %d.%n",
                            summary.getTotalDeliveries(), totalServices - correctionFactor));
        }

        if (totalParcels != summary.getTotalParcels()) {
            validationErrors
                    .append(String.format("Validation failed: Total parcels do not match. Expected %d, but got %d.%n",
                            summary.getTotalParcels(), totalParcels));
        }

        if (totalB2BServices != summary.getTotalB2BDeliveries() + correctionFactor) {
            validationErrors.append(
                    String.format("Validation failed: Total B2B deliveries do not match. Expected %d, but got %d.%n",
                            summary.getTotalB2BDeliveries(), totalB2BServices - correctionFactor));
        }

        if (totalB2BParcels != summary.getTotalB2BParcels()) {
            validationErrors.append(
                    String.format("Validation failed: Total B2B parcels do not match. Expected %d, but got %d.%n",
                            summary.getTotalB2BParcels(), totalB2BParcels));
        }

        // Map to store the sum of parcels for each provider
        final Map<String, List<Double>> providerParcelRates = new HashMap<>();

        carriers.getCarriers().values().forEach(carrier -> {
            final String provider = (String) carrier.getAttributes().getAttribute("provider");
            final double totalCarrierParcels = Double.valueOf(carrier.getServices().values().stream()
                    .mapToInt(CarrierService::getCapacityDemand)
                    .sum());
            final double missedCarrierParcels = Double
                    .valueOf((int) carrier.getAttributes().getAttribute("missedParcels"));

            if (totalCarrierParcels > 0) {
                double missedParcelRate = (double) missedCarrierParcels / totalCarrierParcels;
                providerParcelRates.computeIfAbsent(provider, k -> new ArrayList<>()).add(missedParcelRate);
            }
        });

        // Log the missed parcels for each provider and compare with the rate
        // The rates have been reduced by 2% to account for B2B shipments.
        // This calibration ensures that the reported values are approximately correct.
        // Calibrated Values are set as default Values
        // The original values can be found here:
        // https://de.statista.com/statistik/daten/studie/646146/umfrage/erfolgsquote-beim-ersten-zustellungsversuch-von-kep-diensten-in-deutschland/

        providerParcelRates.forEach((provider, rates) -> {
            double averageMissedRate = 1 - rates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            LOGGER.info("Missed parcels for provider {}: average rate {}%", provider, averageMissedRate * 100);

            double expectedRate = deliveryRates.get(provider);
            if ("amazon".equals(provider)) {
                // No adjustment for Amazon
            } else if ("ups".equals(provider) || "fedex".equals(provider) || "dpd".equals(provider)) {
                expectedRate += 3;
            } else {
                expectedRate += 2;
            }
            LOGGER.info("Expected rate for provider {}: {}%", provider, expectedRate );
        });

        if (validationErrors.length() > 0) {
            throw new IllegalStateException(validationErrors.toString());
        } else {
            LOGGER.info("Validation passed: All carrier services match the original deliveries summary.");
            LOGGER.info("Total deliveries match: {}.", totalServices - correctionFactor);
            LOGGER.info("Total parcels match: {}.", totalParcels);
            LOGGER.info("Total B2B deliveries match: {}.", totalB2BServices - correctionFactor);
            LOGGER.info("Total B2B parcels match: {}.", totalB2BParcels);
        }
    }
}
