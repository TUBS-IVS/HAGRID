package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.RebalancingTargetCalculator;

import java.util.Map;
import java.util.Set;
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
        var calc = new ReturnToDepotTargetCalculator(daytime, Set.of(depotZone), 80000.0, 100.0);
        ToDoubleFunction<Zone> f = calc.calculate(70000.0, Map.of());
        assertThat(f.applyAsDouble(otherZone)).isEqualTo(42.0);
    }

    @Test
    @DisplayName("targets depot zones in the return window")
    void returnWindowTargetsDepots() {
        RebalancingTargetCalculator daytime = (t, vbz) -> (z -> 42.0);
        var calc = new ReturnToDepotTargetCalculator(daytime, Set.of(depotZone), 80000.0, 100.0);
        ToDoubleFunction<Zone> f = calc.calculate(85000.0, Map.of());
        assertThat(f.applyAsDouble(depotZone)).isEqualTo(100.0);
        assertThat(f.applyAsDouble(otherZone)).isEqualTo(0.0);
    }
}
