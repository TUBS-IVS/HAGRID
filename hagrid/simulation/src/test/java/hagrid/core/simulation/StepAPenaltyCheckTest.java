package hagrid.core.simulation;

import hagrid.core.routing.HAGRIDRouterUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlanWriter;
import org.matsim.freight.carriers.CarrierPlanXmlReader;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;

import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step A records its U-turn penalty on every routed carrier; Step B compares it with its own runner
 * value and warns (never aborts) on a mismatch.
 */
@DisplayName("Step-A U-turn penalty check")
class StepAPenaltyCheckTest {

    private static final VehicleType VAN = VehicleUtils.createVehicleType(Id.create("van", VehicleType.class));

    @TempDir
    Path tmp;

    private static Carriers carriers(Double stepAValue) {
        Carriers carriers = new Carriers();
        for (String id : new String[] { "c1", "c2" }) {
            Carrier c = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
            CarriersUtils.addCarrierVehicle(c, CarrierVehicle.Builder
                    .newInstance(Id.createVehicleId("v_" + id), Id.create("l", Link.class), VAN)
                    .setEarliestStart(0).setLatestEnd(86400).build());
            if (stepAValue != null) {
                HAGRIDRouterUtils.recordUTurnPenalty(c, stepAValue);
            }
            carriers.addCarrier(c);
        }
        return carriers;
    }

    @Test
    @DisplayName("mismatch produces a warning naming both values")
    void mismatchProducesWarning() {
        assertThat(HAGRIDScenarioBuilder.checkStepAPenalty(carriers(1.0), 2.0))
                .hasValueSatisfying(m -> assertThat(m).contains("1.0").contains("2.0"));
    }

    @Test
    @DisplayName("match produces nothing")
    void matchProducesNothing() {
        assertThat(HAGRIDScenarioBuilder.checkStepAPenalty(carriers(1.0), 1.0)).isEmpty();
    }

    @Test
    @DisplayName("carriers without the attribute (old files) give an INFO text only")
    void missingStepAAttributeLogsInfoOnly() {
        assertThat(HAGRIDScenarioBuilder.checkStepAPenalty(carriers(null), 1.0))
                .hasValueSatisfying(m -> assertThat(m).startsWith("INFO"));
    }

    @Test
    @DisplayName("one deviating carrier among matching ones is enough for a warning")
    void singleDeviatingCarrierWarns() {
        Carriers cs = carriers(1.0);
        HAGRIDRouterUtils.recordUTurnPenalty(cs.getCarriers().get(Id.create("c2", Carrier.class)), 3.0);
        assertThat(HAGRIDScenarioBuilder.checkStepAPenalty(cs, 1.0))
                .hasValueSatisfying(m -> assertThat(m).doesNotStartWith("INFO").contains("3.0"));
    }

    @Test
    @DisplayName("an unparsable attribute value warns instead of aborting the run")
    void unparsableStepAAttributeWarnsInsteadOfThrowing() {
        Carriers cs = carriers(1.0);
        cs.getCarriers().get(Id.create("c2", Carrier.class)).getAttributes()
                .putAttribute(HAGRIDRouterUtils.UTURN_PENALTY_ATTR, "abc");
        assertThat(HAGRIDScenarioBuilder.checkStepAPenalty(cs, 1.0))
                .hasValueSatisfying(m -> assertThat(m).doesNotStartWith("INFO").contains("unparsable value 'abc'"));
    }

    @Test
    @DisplayName("the attribute survives the carrier XML round trip as a Double")
    void stepAPenaltySurvivesXmlRoundTrip() {
        Path file = tmp.resolve("carriers.xml");
        new CarrierPlanWriter(carriers(1.0)).write(file.toString());

        Carriers back = new Carriers();
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(VAN.getId(), VAN);
        new CarrierPlanXmlReader(back, types)
                .readFile(file.toString());

        assertThat(back.getCarriers()).hasSize(2);
        for (Carrier c : back.getCarriers().values()) {
            assertThat(c.getAttributes().getAttribute(HAGRIDRouterUtils.UTURN_PENALTY_ATTR)).isEqualTo(1.0);
        }
        assertThat(HAGRIDScenarioBuilder.checkStepAPenalty(back, 1.0)).isEmpty();
    }
}
