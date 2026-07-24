package hagrid.integrated.shareduse;

import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.Delivery.ParcelType;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeliveryChannelResolverTest {

    private static Delivery delivery(ParcelType type) {
        return Delivery.builder().id("d1").coordinate(new Coord(0, 0))
                .provider("dhl").amount(3).parcelType(type).build();
    }

    @Test
    void b2bIsAlwaysDoor() {
        var resolver = new DeliveryChannelResolver(List.of(new Coord(10, 10)), 500.0);
        assertEquals(DeliveryChannelResolver.Channel.DOOR, resolver.resolve(delivery(ParcelType.B2B)));
    }

    @Test
    void b2cWithoutLockersFallsBackToDoor() {
        var resolver = new DeliveryChannelResolver(List.of(), 500.0);   // Phase 1: empty
        assertEquals(DeliveryChannelResolver.Channel.DOOR, resolver.resolve(delivery(ParcelType.B2C)));
    }

    @Test
    void b2cWithLockerInRangeIsLocker() {
        var resolver = new DeliveryChannelResolver(List.of(new Coord(100, 0)), 500.0);
        assertEquals(DeliveryChannelResolver.Channel.LOCKER, resolver.resolve(delivery(ParcelType.B2C)));
    }

    @Test
    void b2cWithLockerOutOfRangeIsDoor() {
        var resolver = new DeliveryChannelResolver(List.of(new Coord(10_000, 0)), 500.0);
        assertEquals(DeliveryChannelResolver.Channel.DOOR, resolver.resolve(delivery(ParcelType.B2C)));
    }
}
