package hagrid.integrated.shareduse;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * A parcel missing from the snapshot must NOT be priced as the cheapest possible request.
     * The old fallbacks were "1 parcel" -> 30 s depot pickup (instead of up to 600 s) and
     * segmentDwellSeconds(1) -> 120 s door dwell (instead of up to 900 s), so the insertion
     * search under-priced it and every dwell-derived KPI came out optimistic — silently.
     * ParcelAttributes validates the population at install time, so reaching this means the
     * snapshot-is-complete invariant broke.
     */
    @Test
    void unknownParcelPersonThrowsInsteadOfBeingPricedAsOneParcel() {
        Id<Person> unknown = Id.createPersonId("parcel_dhl_99_B2C");

        IllegalStateException pickup = assertThrows(IllegalStateException.class,
                () -> provider.pickupDurationFor(unknown));
        assertTrue(pickup.getMessage().contains("parcel_dhl_99_B2C"), pickup.getMessage());
        assertTrue(pickup.getMessage().contains("load"), pickup.getMessage());

        // parcel_dhl_2_B2C has a load but no dwell entry — the dropoff snapshot is incomplete.
        IllegalStateException dropoff = assertThrows(IllegalStateException.class,
                () -> provider.dropoffDurationFor(Id.createPersonId("parcel_dhl_2_B2C")));
        assertTrue(dropoff.getMessage().contains("dwell"), dropoff.getMessage());
    }
}
