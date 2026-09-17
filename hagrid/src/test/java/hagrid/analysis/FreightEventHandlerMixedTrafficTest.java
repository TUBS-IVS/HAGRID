package hagrid.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.population.Person;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FreightEventHandler ignores non-freight agents in married (mixed-traffic) events")
class FreightEventHandlerMixedTrafficTest {

    @Test
    @DisplayName("a 'service' activity of a NON-freight person is not counted")
    void nonFreightServiceActivityIgnored() {
        FreightEventHandler handler = new FreightEventHandler();

        // freight driver -> counted
        handler.handleEvent(new ActivityStartEvent(9 * 3600.0,
                Id.create("freight_dhl_veh_1_driver", Person.class),
                Id.createLinkId("l1"), null, "service", new Coord(0, 0)));
        // DRT/pax-side agent emitting a same-named activity -> must be ignored
        handler.handleEvent(new ActivityStartEvent(9 * 3600.0,
                Id.create("drt_taxi_7", Person.class),
                Id.createLinkId("l1"), null, "service", new Coord(0, 0)));

        assertThat(handler.getServiceEvents())
                .as("only the freight driver's service activity may be recorded")
                .containsOnlyKeys("freight_dhl_veh_1_driver");
    }
}
