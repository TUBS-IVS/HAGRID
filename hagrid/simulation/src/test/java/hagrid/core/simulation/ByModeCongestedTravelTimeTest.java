package hagrid.core.simulation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.vehicles.VehicleUtils;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ByModeCongestedTravelTimeTest {
    @Test
    void forbiddenLinkFailsWithoutReadingMeasuredTimes() {
        var link=link("bike");
        var calls=new AtomicInteger();
        var times=new ByModeCongestedTravelTime("car",(l,t,p,v) -> { calls.incrementAndGet(); return 1; });
        assertThatIllegalArgumentException().isThrownBy(() -> times.getLinkTravelTime(link,0,null,null))
                .withMessageContaining("car").withMessageContaining("link");
        assertThat(calls.get()).isZero();
    }
    @Test
    void explicitConflictingVehicleModeFails() {
        var type=VehicleUtils.createVehicleType(Id.create("bike",org.matsim.vehicles.VehicleType.class),"bike");
        var vehicle=VehicleUtils.createVehicle(Id.createVehicleId("bike"),type);
        var times=new ByModeCongestedTravelTime("car",(l,t,p,v) -> 1);
        assertThatIllegalArgumentException().isThrownBy(() -> times.getLinkTravelTime(link("car"),0,null,vehicle))
                .withMessageContaining("bike").withMessageContaining("car");
    }
    @Test
    void allowedModeDelegatesActualTimeAndVehicle() {
        var type=VehicleUtils.createVehicleType(Id.create("van",org.matsim.vehicles.VehicleType.class),"car");
        var vehicle=VehicleUtils.createVehicle(Id.createVehicleId("van"),type);
        var link=link("car");
        var times=new ByModeCongestedTravelTime("car",(l,t,p,v) -> {
            assertThat(l).isSameAs(link); assertThat(t).isEqualTo(123); assertThat(v).isSameAs(vehicle); return 9;
        });
        assertThat(times.getLinkTravelTime(link,123,null,vehicle)).isEqualTo(9);
    }
    private static Link link(String mode) {
        var network=NetworkUtils.createNetwork();
        var a=NetworkUtils.createAndAddNode(network,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(network,Id.createNodeId("b"),new Coord(100,0));
        var link=NetworkUtils.createAndAddLink(network,Id.createLinkId("link"),a,b,100,10,1000,1);
        link.setAllowedModes(Set.of(mode)); return link;
    }
}