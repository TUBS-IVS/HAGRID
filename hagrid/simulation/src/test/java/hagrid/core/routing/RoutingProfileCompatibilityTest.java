package hagrid.core.routing;
import org.junit.jupiter.api.Test;
import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.*;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingProfileCompatibilityTest {
    @Test void constantLinkMeansAndMonotonicPureTimeProfilesPass() {
        try(var constant=TemporalRoutingContractTest.fixture((l,t,p,v)->60,.2);
            var increasing=TemporalRoutingContractTest.fixture((l,t,p,v)->t<100?30:60,0)) {
            assertTrue(RoutingProfileCompatibility.check(constant.context()).isEmpty());
            assertTrue(RoutingProfileCompatibility.check(increasing.context()).isEmpty());
        }
    }
    @Test void decreasingStepIsReportedWithModeLinkAndExactBoundaryBeforeGraphAllocation() {
        try(var f=TemporalRoutingContractTest.fixture((l,t,p,v)->t<100?60:30,0)) {
            var violations=RoutingProfileCompatibility.check(f.context());
            assertFalse(violations.isEmpty());assertEquals("car",violations.getFirst().mode());
            assertFalse(violations.getFirst().linkId().isEmpty());assertEquals(100,violations.getFirst().boundarySeconds());
            assertTrue(violations.getFirst().rule().contains("FIFO"));
            assertThrows(UnsupportedOperationException.class,()->violations.clear());
            assertThrows(IllegalArgumentException.class,f::costs);
            assertEquals(0,f.context().statistics().preparedGraphs());
        }
    }
    @Test void variableMixedObjectiveRemainsUnsupported() {
        try(var f=TemporalRoutingContractTest.fixture((l,t,p,v)->t<100?30:60,.2)) {
            assertTrue(RoutingProfileCompatibility.check(f.context()).stream().anyMatch(v->v.rule().contains("pure-time")));
            assertThrows(IllegalArgumentException.class,f::costs);
        }
    }
    @Test void variableZoneAndTimeQuantizationAreDiagnosedSeparately() {
        try(var f=TemporalRoutingContractTest.fixture((l,t,p,v)->t<100?30:60,0)) {
            for(var setting:Map.of("routing.odTimeQuantizationSeconds","1").entrySet()) {
                var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                        HagridConfigResolver.Overrides.of(Map.of(setting.getKey(),setting.getValue())),Map.of());
                var types=new CarrierVehicleTypes();var type=f.carrier().getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getType();
                types.getVehicleTypes().put(type.getId(),type);
                try(var context=RoutingContext.create(f.context().network(),types,f.context().snapshot(),config)) {
                    var problems=RoutingProfileCompatibility.check(context);
                    assertTrue(problems.stream().anyMatch(v->v.rule().contains(setting.getKey().contains("zone")?"Zone approximation":"quantization")));
                    assertThrows(IllegalArgumentException.class,()->new ModalTransportCosts(new RoutingRequest(f.carrier(),context,"car")));
                    assertEquals(0,context.statistics().preparedGraphs());
                }
            }
        }
    }
    @Test void nceSpeedRecoveryIsNotSilentlyFlattened() {
        var settings=new NetworkConfigGroup();settings.setTimeVariantNetwork(true);var network=NetworkUtils.createNetwork(settings);
        var a=NetworkUtils.createAndAddNode(network,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(network,Id.createNodeId("b"),new Coord(100,0));
        var link=NetworkUtils.createAndAddLink(network,Id.createLinkId("recovery"),a,b,100,10,1000,1);link.setAllowedModes(Set.of("car"));
        var change=new NetworkChangeEvent(100);change.addLink(link);
        change.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,20));
        NetworkUtils.setNetworkChangeEvents(network,List.of(change));
        var snapshot=TravelTimeSnapshot.capture(network,Map.of(),Map.of("car",(l,t,p,v)->l.getLength()/l.getFreespeed(t)),0,300,100);
        var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");
        type.setMaximumVelocity(100);type.getCostInformation().setFixedCost(0.0).setCostsPerMeter(0.0).setCostsPerSecond(1.0);
        var registry=new CarrierVehicleTypes();registry.getVehicleTypes().put(type.getId(),type);
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.of(Map.of()),Map.of());
        try(var context=RoutingContext.create(network,registry,snapshot,config)) {
            assertTrue(RoutingProfileCompatibility.check(context).stream().anyMatch(v->v.linkId().equals("recovery") && v.boundarySeconds()==100 && v.rule().contains("FIFO")));
        }
    }
}
