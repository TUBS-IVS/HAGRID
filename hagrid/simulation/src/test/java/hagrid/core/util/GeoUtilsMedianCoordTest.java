package hagrid.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarriersUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GeoUtils.getMedianCoordOfStoredServiceCoords")
class GeoUtilsMedianCoordTest {

    private static Carrier carrierWith(String id, Coord... serviceCoords) {
        Carrier carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
        for (int i = 0; i < serviceCoords.length; i++) {
            CarrierService s = CarrierService.Builder.newInstance(
                    Id.create(id + "_s" + i, CarrierService.class), Id.createLinkId("l" + i)).build();
            if (serviceCoords[i] != null) {
                s.getAttributes().putAttribute("coord", serviceCoords[i]);
            }
            CarriersUtils.addService(carrier, s);
        }
        return carrier;
    }

    @Test
    @DisplayName("services that carry coords give their median, without a warning")
    void medianOfStoredCoords() {
        Carrier c = carrierWith("c1", new Coord(0, 0), new Coord(10, 20), new Coord(30, 40));
        try (LogCapture log = LogCapture.of(GeoUtils.class)) {
            assertThat(GeoUtils.getMedianCoordOfStoredServiceCoords(c)).isEqualTo(new Coord(10, 20));
            assertThat(log.warnings()).isEmpty();
        }
    }

    @Test
    @DisplayName("services WITHOUT a coord attribute still give (0,0), but that is warned, not silent")
    void missingCoordAttributesWarn() {
        Carrier c = carrierWith("c2", (Coord) null, (Coord) null);
        try (LogCapture log = LogCapture.of(GeoUtils.class)) {
            // behaviour unchanged: the Hannover merge logic keeps receiving (0,0)
            assertThat(GeoUtils.getMedianCoordOfStoredServiceCoords(c)).isEqualTo(new Coord(0, 0));
            assertThat(log.warnings()).singleElement().satisfies(m ->
                    assertThat(m).contains("c2").contains("coord"));
        }
    }
}
