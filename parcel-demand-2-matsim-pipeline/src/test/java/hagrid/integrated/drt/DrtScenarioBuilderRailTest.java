package hagrid.integrated.drt;

import hagrid.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DrtScenarioBuilder rail intermodal tagging")
class DrtScenarioBuilderRailTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("build() tags rail stops inside the service area as intermodal DRT access/egress")
    void tagsIntermodalStops() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        // Reuse the shared fixture helper for the raw network/plans/shp + rail schedule + preprocessing.
        RailScenarioFixture fx = RailScenarioFixture.stage(dir);

        Scenario scenario = DrtScenarioBuilder.build(
                fx.baseConfigUrl.toString(),
                fx.drtNetwork, fx.clippedPlans, fx.serviceAreaShp, fx.fleet,
                fx.railSchedule, fx.railVehicles, /*vehicleTypes*/ null,
                dir.resolve("matsim").toString(), "RAIL_TAG_TEST", 0);

        // at least one rail stop inside the service area got tagged
        long tagged = scenario.getTransitSchedule().getFacilities().values().stream()
                .filter(s -> "true".equals(s.getAttributes().getAttribute(
                        LausitzDrtConfigurator.DRT_ACCESS_ATTR)))
                .count();
        assertThat(tagged).as("rail stops inside the service area must be tagged for DRT access/egress")
                .isGreaterThan(0);
    }
}
