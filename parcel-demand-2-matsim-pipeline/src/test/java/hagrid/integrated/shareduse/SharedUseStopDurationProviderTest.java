package hagrid.integrated.shareduse;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedUseStopDurationProviderTest {

    // parcel_dhl_1_B2C carries 3 parcels (dwell 360 s), parcel_dhl_2_B2C carries 25 parcels.
    private final SharedUseStopDurationProvider provider = new SharedUseStopDurationProvider(
            60.0,
            Map.of(Id.createPersonId("parcel_dhl_1_B2C"), 3,
                    Id.createPersonId("parcel_dhl_2_B2C"), 25),
            Map.of(Id.createPersonId("parcel_dhl_1_B2C"), 360.0));

    @Test
    void parcelPickupScalesWithParcelLoad() {
        // 3 parcels -> depotPickupSeconds(3) = min(30*3, 600) = 90 s
        assertEquals(SharedUse.depotPickupSeconds(3),
                provider.pickupDurationFor(Id.createPersonId("parcel_dhl_1_B2C")), 1e-9);
        assertEquals(90.0,
                provider.pickupDurationFor(Id.createPersonId("parcel_dhl_1_B2C")), 1e-9);
    }

    @Test
    void parcelPickupIsCappedForLargeLoads() {
        // 25 parcels -> depotPickupSeconds(25) = min(30*25=750, 600) = 600 s (capped)
        assertEquals(600.0,
                provider.pickupDurationFor(Id.createPersonId("parcel_dhl_2_B2C")), 1e-9);
    }

    @Test
    void parcelDropoffIsSegmentDwell() {
        assertEquals(360.0, provider.dropoffDurationFor(Id.createPersonId("parcel_dhl_1_B2C")), 1e-9);
    }

    @Test
    void paxKeepsNativeDurations() {
        assertEquals(60.0, provider.pickupDurationFor(Id.createPersonId("p42")), 1e-9);
        assertEquals(0.0, provider.dropoffDurationFor(Id.createPersonId("p42")), 1e-9);
    }
}
