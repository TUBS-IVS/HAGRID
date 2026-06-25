package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
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

import java.util.List;

/**
 * Builds a single {@link Carrier} for one LSP in the Lausitz LMD baseline. One {@link CarrierService}
 * per {@link Delivery} (capacity = parcel count, duration via the reused HAGRID formula
 * {@code min(durationPerParcel*60*count, maxDurationPerStop*60)}), and one {@link CarrierVehicle}
 * per van type anchored at the LSP depot link. Fleet size is INFINITE so jsprit decides tour count.
 */
public final class LmdCarrierBuilder {

    /** Whole-day operating window for the delivery vehicles + services (08:00-20:00). */
    private static final double DAY_START = 8 * 3600;
    private static final double DAY_END = 20 * 3600;

    private LmdCarrierBuilder() {}

    public static Carrier build(String provider, List<Delivery> deliveries, Id<Link> depotLink,
                                Network network, VehicleType[] vanTypes,
                                int durationPerParcelMin, int maxDurationPerStopMin) {
        Carrier carrier = CarriersUtils.createCarrier(Id.create(provider, Carrier.class));
        CarriersUtils.setCarrierMode(carrier, "car");
        carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);

        int n = 0;
        for (Delivery d : deliveries) {
            Link link = NetworkUtils.getNearestLinkExactly(network, d.getCoordinate());
            double duration = Math.min(
                    (durationPerParcelMin * 60.0) * d.getAmount(),
                    maxDurationPerStopMin * 60.0);
            CarrierService service = CarrierService.Builder
                    .newInstance(Id.create(provider + "_" + n++, CarrierService.class), link.getId())
                    .setCapacityDemand(d.getAmount())
                    .setServiceDuration(duration)
                    .setServiceStartTimeWindow(TimeWindow.newInstance(DAY_START, DAY_END))
                    .build();
            CarriersUtils.addService(carrier, service);
        }

        for (VehicleType vanType : vanTypes) {
            CarrierVehicle vehicle = CarrierVehicle.Builder
                    .newInstance(Id.createVehicleId(provider + "_" + vanType.getId().toString()),
                            depotLink, vanType)
                    .setEarliestStart(DAY_START)
                    .setLatestEnd(DAY_END)
                    .build();
            CarriersUtils.addCarrierVehicle(carrier, vehicle);
        }

        return carrier;
    }
}
