package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
import hagrid.utils.routing.HAGRIDRouterUtils;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.vehicles.VehicleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds a single {@link Carrier} for one LSP in the Lausitz LMD baseline. One {@link CarrierService}
 * per {@link Delivery} (capacity = parcel count, duration via the reused HAGRID formula
 * {@code min(durationPerParcel*60*count, maxDurationPerStop*60)}), and one {@link CarrierVehicle}
 * per van type anchored at the LSP depot link. Fleet size is INFINITE so jsprit decides tour count.
 *
 * <p>Also records a <strong>missed-delivery overlay</strong> (Fehlzustellung) on the carrier, mirroring
 * the Hannover legacy {@code CarrierGenerator}: per-provider delivery success rates (with a Gaussian
 * daily bias, B2B treated as ~99% reliable) are drawn per parcel; the missed service-ids are persisted
 * as the {@code missedParcelDeliveriesAsString} carrier attribute that {@code DashboardGenerator} reads
 * for the "Delivery Rate" KPI. This is purely a statistical overlay — it does NOT change routing or the
 * services that get driven (the vehicle still attempts every stop), exactly as in the legacy model.
 *
 * <p>{@link #build} is the LMD_BASELINE (and Hannover-parity) entry point: dispatch-wave vehicles with
 * jittered departures. {@link #buildSingleWindow} is the 1d DRT_MODULAR entry point: the capsule
 * "vehicles" are virtual placeholders for the DRT fleet, so it builds ONE un-jittered vehicle per van
 * type with an explicit operating window instead. Both share the same carrier-skeleton/service/overlay
 * core, so the missed-delivery overlay behaves identically either way.
 */
public final class LmdCarrierBuilder {

    /** Whole-day service time window for the delivery stops (08:00-20:00). */
    private static final double DAY_START = 8 * 3600;
    private static final double DAY_END = 20 * 3600;

    /** Absolute cap on any vehicle's operating end (Hannover parity: 21:00). */
    private static final double LATEST_VEHICLE_END = 21 * 3600;

    /**
     * Jittered vehicle copies per van type per dispatch wave. With {@code FleetSize.INFINITE}
     * jsprit clones a single template, so ONE jittered start would still stack every tour of a
     * wave on the same minute — several copies give jsprit distinct departure times to pick from,
     * spreading real tour starts (legacy Hannover creates one template per dispatch HOUR instead).
     */
    static final int VEHICLES_PER_TYPE_PER_WAVE = 4;

    /**
     * Default dispatch waves when no explicit list is supplied (mirrors HAGRID CEP
     * {@code VehicleSchedule.SIMPLE_STAGGERED}: morning + afternoon wave).
     */
    static final List<Integer> DEFAULT_DISPATCH_HOURS = List.of(8, 14);

    /**
     * Per-provider overall delivery success rates (percent), reused from the Hannover
     * {@code HagridConfig} defaults for comparability. B2B parcels override to {@link #B2B_DELIVERY_RATE}.
     */
    private static final Map<String, Double> DELIVERY_RATES = Map.of(
            "dhl", 94.0, "gls", 91.0, "hermes", 91.0, "dpd", 89.0,
            "ups", 89.0, "amazon", 93.0, "fedex", 89.0);
    private static final double DEFAULT_DELIVERY_RATE = 90.0;
    private static final double B2B_DELIVERY_RATE = 99.0;
    /** Effective B2C rate is capped here (100% is never realistic), matching the legacy clamp. */
    private static final double MAX_EFFECTIVE_RATE = 98.0;

    private LmdCarrierBuilder() {}

    /**
     * Builds a carrier with one vehicle per van type <em>per dispatch hour</em>,
     * staggering departures across the supplied {@code dispatchHours} (e.g. {@code [8, 14]}
     * creates a morning and an afternoon wave, matching the HAGRID CEP strategy).
     */
    public static Carrier build(String provider, List<Delivery> deliveries, Id<Link> depotLink,
                                Network network, VehicleType[] vanTypes,
                                int durationPerParcelMin, int maxDurationPerStopMin,
                                List<Integer> dispatchHours, Random random) {
        Carrier carrier = buildCore(provider, deliveries, network, durationPerParcelMin,
                maxDurationPerStopMin, random, TimeWindow.newInstance(DAY_START, DAY_END));

        List<Integer> hours = (dispatchHours == null || dispatchHours.isEmpty())
                ? DEFAULT_DISPATCH_HOURS : dispatchHours;
        for (int hour : hours) {
            for (VehicleType vanType : vanTypes) {
                for (int copy = 0; copy < VEHICLES_PER_TYPE_PER_WAVE; copy++) {
                    // Legacy-parity Gaussian departure jitter (CarrierVehicleFactory.getTimeShift):
                    // sigma 15 min for size m, 5 min for size l — depot departures spread realistically
                    // instead of all vans leaving hard on the hour.
                    double jitterSec = random.nextGaussian() * jitterSigmaMinutes(vanType) * 60.0;
                    double earliestStart = hour * 3600.0 + jitterSec;
                    // Hannover parity (CarrierVehicleFactory.calculateEndTime): the operating window
                    // is WAVE-RELATIVE — start + 7h route cap + 1h buffer, capped at 21:00. This is
                    // what gives the 14:00 wave a real role: an 08:00 van may only operate until
                    // ~16:00, so late-afternoon workload can only go to the afternoon wave.
                    double latestEnd = Math.min(
                            earliestStart + HAGRIDRouterUtils.MAXROUTEDURATION + 3600.0,
                            LATEST_VEHICLE_END);
                    String vehId = provider + "_" + vanType.getId().toString() + "_h" + hour + "_v" + copy;
                    CarrierVehicle vehicle = CarrierVehicle.Builder
                            .newInstance(Id.createVehicleId(vehId), depotLink, vanType)
                            .setEarliestStart(earliestStart)
                            .setLatestEnd(latestEnd)
                            .build();
                    CarriersUtils.addCarrierVehicle(carrier, vehicle);
                }
            }
        }

        return carrier;
    }

    /**
     * 1d single-window variant (plan C4 revised, user 2026-07-28): the capsule "vehicles" are
     * virtual (the DRT fleet executes the tours), so NO dispatch waves, NO departure jitter -
     * ONE vehicle per van type with an explicit operating window, services with the aligned
     * start time window (07:30-21:00 for 1d instead of the baseline's 08:00-20:00).
     */
    public static Carrier buildSingleWindow(String provider, List<Delivery> deliveries,
            Id<Link> depotLink, Network network, VehicleType[] vanTypes, int durationPerParcelMin,
            int maxDurationPerStopMin, Random random, double vehicleEarliestStart,
            double vehicleLatestEnd, double serviceTwStart, double serviceTwEnd) {
        Carrier carrier = buildCore(provider, deliveries, network, durationPerParcelMin,
                maxDurationPerStopMin, random, TimeWindow.newInstance(serviceTwStart, serviceTwEnd));
        for (VehicleType vanType : vanTypes) {
            CarrierVehicle vehicle = CarrierVehicle.Builder
                    .newInstance(Id.createVehicleId(provider + "_" + vanType.getId() + "_day_v0"),
                            depotLink, vanType)
                    .setEarliestStart(vehicleEarliestStart)
                    .setLatestEnd(vehicleLatestEnd)
                    .build();
            CarriersUtils.addCarrierVehicle(carrier, vehicle);
        }
        return carrier;
    }

    /**
     * Shared carrier-skeleton + service-building + missed-delivery-overlay body of {@link #build}
     * and {@link #buildSingleWindow}: creates the carrier ({@code FleetSize.INFINITE}), one
     * {@link CarrierService} per {@link Delivery} using the given {@code serviceTimeWindow}, and
     * draws the missed-delivery overlay attributes. Callers add their own vehicles afterwards.
     *
     * <p><strong>RNG draw order is part of this method's contract</strong> (byte-identity of the
     * legacy {@code build} output depends on it): the per-carrier daily bias is drawn first, then
     * exactly one per-parcel miss draw per delivery, in delivery order — unchanged from the
     * pre-extraction code.
     */
    private static Carrier buildCore(String provider, List<Delivery> deliveries, Network network,
            int durationPerParcelMin, int maxDurationPerStopMin, Random random,
            TimeWindow serviceTimeWindow) {
        Carrier carrier = CarriersUtils.createCarrier(Id.create(provider, Carrier.class));
        CarriersUtils.setCarrierMode(carrier, "car");
        carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);

        // Legacy-parity attribute read back by the analysis layer (provider colouring + per-LSP tables).
        carrier.getAttributes().putAttribute("provider", provider);

        // Per-provider base rate + a daily bias sampled once per carrier (mirrors CarrierGenerator).
        double baseRate = DELIVERY_RATES.getOrDefault(provider, DEFAULT_DELIVERY_RATE);
        double sigmaPercent = "dhl".equals(provider) ? 2.5 : 5.0;
        double dailyBias = random.nextGaussian() * sigmaPercent;

        List<Id<CarrierService>> missedParcels = new ArrayList<>();
        int totalParcels = 0;
        int n = 0;
        for (Delivery d : deliveries) {
            Link link = NetworkUtils.getNearestLinkExactly(network, d.getCoordinate());
            double duration = Math.min(
                    (durationPerParcelMin * 60.0) * d.getAmount(),
                    maxDurationPerStopMin * 60.0);
            Id<CarrierService> serviceId = Id.create(provider + "_" + n++, CarrierService.class);
            CarrierService service = CarrierService.Builder
                    .newInstance(serviceId, link.getId())
                    .setCapacityDemand(d.getAmount())
                    .setServiceDuration(duration)
                    .setServiceStartTimeWindow(serviceTimeWindow)
                    .build();
            CarriersUtils.addService(carrier, service);
            totalParcels += d.getAmount();

            // Cosmetic missed-delivery overlay: draw each parcel against the effective rate.
            double effectiveRate = d.getParcelType() == Delivery.ParcelType.B2B
                    ? B2B_DELIVERY_RATE
                    : Math.max(0.0, Math.min(MAX_EFFECTIVE_RATE, baseRate + dailyBias));
            for (int p = 0; p < d.getAmount(); p++) {
                if (random.nextDouble() * 100.0 > effectiveRate) {
                    missedParcels.add(serviceId);
                }
            }
        }

        // Attributes consumed by CarrierXmlParser/DashboardGenerator (Delivery Rate = (parcels-missed)/parcels).
        carrier.getAttributes().putAttribute("numberOfParcels", totalParcels);
        carrier.getAttributes().putAttribute("missedParcels", missedParcels.size());
        carrier.getAttributes().putAttribute("missedParcelsAsList", new ArrayList<>(missedParcels));
        carrier.getAttributes().putAttribute("missedParcelDeliveriesAsString", missedParcels.toString());

        return carrier;
    }

    /** Legacy Hannover jitter widths ({@code CarrierVehicleFactory.getTimeShift}), in minutes. */
    private static double jitterSigmaMinutes(VehicleType vanType) {
        String id = vanType.getId().toString().toLowerCase();
        return id.endsWith("_l") ? 5.0 : 15.0;
    }
}
