package hagrid.utils.routing;

import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarriersUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the shared {@link HAGRIDRouterUtils#recordUnassignedJobs} helper that persists the
 * jobs jsprit could not insert into any tour as carrier attributes. Both the Hannover legacy
 * {@code Router} and the Lausitz {@code LausitzFreightPreprocessor} route with an INFINITE fleet, so
 * an unassigned stop is never "out of vehicles" — it is a stop whose demand exceeds every (identical)
 * van's capacity, or that is infeasible under the 7h route-duration / 20:00 end window. Persisting the
 * parcel-level count makes that visible on the dashboard instead of a misleading zero.
 */
@DisplayName("HAGRIDRouterUtils — unassigned-job accounting")
class HAGRIDRouterUtilsTest {

    private static CarrierService svc(String id, int demand) {
        return CarrierService.Builder
                .newInstance(Id.create(id, CarrierService.class), Id.create("link", Link.class))
                .setCapacityDemand(demand)
                .build();
    }

    private static Carrier carrierWith(CarrierService... services) {
        Carrier c = CarriersUtils.createCarrier(Id.create("dhl", Carrier.class));
        for (CarrierService s : services) {
            CarriersUtils.addService(c, s);
        }
        return c;
    }

    @Test
    @DisplayName("sums capacityDemand of unassigned stops (parcels), not just the stop count")
    void sumsCapacityDemandOfUnassignedStops() {
        Carrier c = carrierWith(svc("s_ok", 2), svc("s_huge", 500));

        HAGRIDRouterUtils.recordUnassignedJobs(c, List.of("s_huge"));

        assertThat(c.getAttributes().getAttribute("unassignedJobs")).isEqualTo(1);
        assertThat(c.getAttributes().getAttribute("unassignedParcels")).isEqualTo(500);
        assertThat((String) c.getAttributes().getAttribute("unassignedJobsAsString")).contains("s_huge");
    }

    @Test
    @DisplayName("writes explicit zeros when nothing is unassigned (dashboard reads unconditionally)")
    void writesExplicitZeroWhenFullyAssigned() {
        Carrier c = carrierWith(svc("s_ok", 2));

        HAGRIDRouterUtils.recordUnassignedJobs(c, List.of());

        assertThat(c.getAttributes().getAttribute("unassignedJobs")).isEqualTo(0);
        assertThat(c.getAttributes().getAttribute("unassignedParcels")).isEqualTo(0);
        assertThat((String) c.getAttributes().getAttribute("unassignedJobsAsString")).isEqualTo("[]");
    }

    @Test
    @DisplayName("an unresolved job id is counted as a job but contributes 0 parcels (no :1 fallback)")
    void unresolvedIdContributesZeroParcels() {
        Carrier c = carrierWith(svc("s_ok", 2));

        HAGRIDRouterUtils.recordUnassignedJobs(c, List.of("ghost"));

        assertThat(c.getAttributes().getAttribute("unassignedJobs")).isEqualTo(1);
        assertThat(c.getAttributes().getAttribute("unassignedParcels")).isEqualTo(0);
    }

    /**
     * The seed override is a diagnostic control for jsprit's stochastic search (see
     * {@link HAGRIDRouterUtils#JSPRIT_SEED_PROPERTY}). The failure mode worth guarding is a
     * SILENT one: if the property were ignored or swallowed, a seed sweep would produce
     * identical runs and be read as "no search noise" — the opposite of the truth.
     */
    @Nested
    @DisplayName("jsprit seed override")
    class SeedOverride {

        private Jsprit.Builder builder() {
            VehicleRoutingProblem vrp = VehicleRoutingProblem.Builder.newInstance().build();
            return Jsprit.Builder.newInstance(vrp);
        }

        @AfterEach
        void clearProperty() {
            System.clearProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY);
        }

        @Test
        @DisplayName("unset property leaves the builder untouched (production stays on jsprit's default seed)")
        void unsetIsNoOp() {
            assertThatCode(() -> HAGRIDRouterUtils.applySeedOverride(builder()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("blank property is treated as unset, not as seed 0")
        void blankIsNoOp() {
            System.setProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY, "   ");

            assertThatCode(() -> HAGRIDRouterUtils.applySeedOverride(builder()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a numeric seed is accepted")
        void numericSeedApplies() {
            System.setProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY, " 1234 ");

            assertThatCode(() -> HAGRIDRouterUtils.applySeedOverride(builder()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a malformed seed fails loudly instead of silently falling back to the default")
        void malformedSeedThrows() {
            System.setProperty(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY, "abc");

            assertThatThrownBy(() -> HAGRIDRouterUtils.applySeedOverride(builder()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY)
                    .hasMessageContaining("abc");
        }
    }
}
