package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.RebalancingTargetCalculator;

import java.util.Map;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReturnToDepotTargetCalculator")
class ReturnToDepotTargetCalculatorTest {

    /** Identity-based stub (no Mockito in this repo). */
    private static final class StubZone implements Zone {
        private final String id;
        StubZone(String id) { this.id = id; }
        public Id<Zone> getId() { return Id.create(id, Zone.class); }
        public Coord getCoord() { return new Coord(0, 0); }
        public Coord getCentroid() { return new Coord(0, 0); }
        public String getType() { return "stub"; }
        public org.locationtech.jts.geom.prep.PreparedPolygon getPreparedGeometry() { return null; }
        public org.matsim.utils.objectattributes.attributable.Attributes getAttributes() { return null; }
    }

    private final Zone depotZone = new StubZone("depot");
    private final Zone otherZone = new StubZone("other");

    @Test
    @DisplayName("delegates to the daytime calculator before the return window")
    void daytimeDelegates() {
        ToDoubleFunction<Zone> sentinel = z -> 42.0;
        RebalancingTargetCalculator daytime = (t, vbz) -> sentinel;
        var calc = new ReturnToDepotTargetCalculator(daytime, Map.of(depotZone, 12.0), 80000.0);
        ToDoubleFunction<Zone> f = calc.calculate(70000.0, Map.of());
        assertThat(f.applyAsDouble(otherZone)).isEqualTo(42.0);
    }

    @Test
    @DisplayName("return window: each depot zone pulls exactly its CAPACITY, non-depot zones 0")
    void returnWindowTargetsDepotCapacities() {
        // Capacity semantics: a depot zone absorbs at most its capacity, so MinCostFlow fills the
        // nearest depot first and overflows to the next once full — instead of one uncapped pull
        // (the old fleet-size-per-zone target) that legally parks the whole fleet at one depot.
        Zone smallDepot = new StubZone("small");
        RebalancingTargetCalculator daytime = (t, vbz) -> (z -> 42.0);
        var calc = new ReturnToDepotTargetCalculator(daytime,
                Map.of(depotZone, 12.0, smallDepot, 3.0), 80000.0);
        ToDoubleFunction<Zone> f = calc.calculate(85000.0, Map.of());
        assertThat(f.applyAsDouble(depotZone)).isEqualTo(12.0);
        assertThat(f.applyAsDouble(smallDepot)).isEqualTo(3.0);
        assertThat(f.applyAsDouble(otherZone)).isEqualTo(0.0);
    }
}
