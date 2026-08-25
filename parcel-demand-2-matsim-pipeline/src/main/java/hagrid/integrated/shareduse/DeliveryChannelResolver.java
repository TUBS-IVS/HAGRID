package hagrid.integrated.shareduse;

import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.Delivery.ParcelType;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.List;

/**
 * Delivery-channel logic (spec §4.2): B2B door-to-door always; B2C Packstation/
 * Filiale-first with door fallback. Phase 1 runs with an EMPTY locker list
 * (user decision 2026-07-06), so the LOCKER channel is structurally 0 in every
 * run — no result may be read as "including a locker share".
 * <p>
 * Activating it needs a CODE change at the call site, not just data: the sole
 * caller {@code ParcelAgentGenerator.generate} hardcodes
 * {@code new DeliveryChannelResolver(List.of(), 500.0)}, so staging a locations
 * file alone changes nothing. Locker itself is deliberately Phase 2
 * (METHODS-LOG §2.10/§4.5).
 */
public final class DeliveryChannelResolver {

    public enum Channel { DOOR, LOCKER }

    private final List<Coord> lockerLocations;
    private final double maxLockerDistanceMeters;

    public DeliveryChannelResolver(List<Coord> lockerLocations, double maxLockerDistanceMeters) {
        this.lockerLocations = List.copyOf(lockerLocations);
        this.maxLockerDistanceMeters = maxLockerDistanceMeters;
    }

    public Channel resolve(Delivery delivery) {
        if (delivery.getParcelType() == ParcelType.B2B) {
            return Channel.DOOR;
        }
        return lockerLocations.stream()
                .anyMatch(l -> CoordUtils.calcEuclideanDistance(l, delivery.getCoordinate())
                        <= maxLockerDistanceMeters)
                ? Channel.LOCKER : Channel.DOOR;
    }
}
