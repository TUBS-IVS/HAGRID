package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleUtils;
import hagrid.core.simulation.CarrierVehicleReRouter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ZoneBasedTransportCostsFactoryTest {
    private record Fixture(Scenario scenario, Carriers carriers, org.matsim.vehicles.VehicleType type) {}

    private Fixture fixture(String mode) {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Network n = scenario.getNetwork();
        Node a = NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));
        Node b = NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));
        Node c = NetworkUtils.createAndAddNode(n,Id.createNodeId("c"),new Coord(200,0));
        Node d = NetworkUtils.createAndAddNode(n,Id.createNodeId("d"),new Coord(300,0));
        Node e = NetworkUtils.createAndAddNode(n,Id.createNodeId("e"),new Coord(150,100));
        link(n,"origin",a,b,"car"); link(n,"destination",c,d,"car");
        link(n,"car1",b,e,"car"); link(n,"car2",e,c,"car");
        link(n,"bike-shortcut",b,c,"bike");
        var type = VehicleUtils.createVehicleType(Id.create("van", org.matsim.vehicles.VehicleType.class));
        type.setNetworkMode(mode);
        type.getCostInformation().setCostsPerMeter(0.0); type.getCostInformation().setCostsPerSecond(1.0);
        Carrier carrier = CarriersUtils.createCarrier(Id.create("carrier",Carrier.class));
        carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(CarrierVehicle.newInstance(
                Id.createVehicleId("van"),Id.createLinkId("origin"),type)).build());
        return new Fixture(scenario,new Carriers(List.of(carrier)),type);
    }

    private static void link(Network n,String id,Node a,Node b,String mode) {
        Link link = NetworkUtils.createAndAddLink(n,Id.createLinkId(id),a,b,100,10,1000,1);
        link.setAllowedModes(Set.of(mode)); link.getAttributes().putAttribute("zone",1);
    }

    private ZoneBasedTransportCostsFactory factory(Fixture f) {
        return new ZoneBasedTransportCostsFactory(f.scenario,f.carriers,Map.of("car",(l,t,p,v)->1.0),
                f.scenario.getConfig(),false,0);
    }

    @Test
    void missingVehicleModeGetsCarCostsBeforeModesAreCollected() {
        var f = fixture(null);
        assertNotNull(factory(f).createVRPTransportCostsWithModeCongestedTravelTime().get("car"));
    }

    @Test
    void carRoutingCannotUseABikeOnlyShortcutAndKeepsZoneAttributes() {
        var f = fixture("car");
        var costs = (ZoneBasedTransportCosts)factory(f).createVRPTransportCostsWithModeCongestedTravelTime().get("car");
        var vehicle = VehicleImpl.Builder.newInstance("van").setType(VehicleTypeImpl.Builder.newInstance("van").build())
                .setStartLocation(Location.newInstance("origin")).build();
        assertEquals(3, costs.getTransportTime(Location.newInstance("origin"),Location.newInstance("destination"),0,null,vehicle));
        assertFalse(costs.getNetwork().getLinks().containsKey(Id.createLinkId("bike-shortcut")));
        assertEquals(1,costs.getNetwork().getLinks().get(Id.createLinkId("origin")).getAttributes().getAttribute("zone"));
    }

    @Test
    void replanningUsesUpdatedTravelTimesInsteadOfPreviousIterationCache() {
        var f = fixture("car");
        var seconds = new AtomicInteger(10);
        var costs = ZoneBasedTransportCosts.Builder.newInstance(f.scenario.getNetwork(),List.of(f.type))
                .setTravelTime((l,t,p,v)->seconds.get()).build();
        var vehicle = VehicleImpl.Builder.newInstance("van").setType(VehicleTypeImpl.Builder.newInstance("van").build())
                .setStartLocation(Location.newInstance("origin")).build();
        var from = Location.newInstance("origin"); var to = Location.newInstance("destination");
        double previous = costs.getTransportTime(from,to,0,null,vehicle);
        seconds.set(20);
        var strategy = new CarrierVehicleReRouter(f.scenario.getNetwork(),f.scenario.getNetwork(),new CarrierVehicleTypes(),
                (l,t,p,v)->seconds.get(),true,Map.of("car",costs),30).createStrategy();
        strategy.init(()->25);
        assertEquals(previous * 2,costs.getTransportTime(from,to,0,null,vehicle));
    }
}
