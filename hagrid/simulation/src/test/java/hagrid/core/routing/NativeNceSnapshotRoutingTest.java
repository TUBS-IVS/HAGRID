package hagrid.core.routing;

import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.network.*;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Protect the HAGRID boundary: real NCE -> observed means -> raw snapshot -> cost adapter. */
class NativeNceSnapshotRoutingTest {
    @Test void actualQueueRemainsOrderedWhileRawMeansAndHalfHourEstimateArePreserved() {
        var config=ConfigUtils.createConfig();config.network().setTimeVariantNetwork(true);
        config.global().setNumberOfThreads(1);config.qsim().setNumberOfThreads(1);
        config.qsim().setStartTime(80);config.qsim().setEndTime(300);
        config.qsim().setInflowCapacitySetting(org.matsim.core.config.groups.QSimConfigGroup.InflowCapacitySetting.INFLOW_FROM_FDIAG);
        var scenario=ScenarioUtils.createScenario(config);var network=scenario.getNetwork();
        link(network,"origin",0,1,10);var focus=link(network,"focus",1,2,100);
        link(network,"destination",2,3,10);link(network,"return",3,0,300);
        NetworkUtils.setNetworkChangeEvents(network,List.of(change(focus,100,5),change(focus,200,10)));
        var factory=scenario.getPopulation().getFactory();
        double[] departures={97,98,99,197,198,199,228};
        for(int i=0;i<departures.length;i++){
            var person=factory.createPerson(Id.createPersonId("p"+i));var plan=factory.createPlan();
            var home=factory.createActivityFromLinkId("home",Id.createLinkId("origin"));home.setEndTime(departures[i]);plan.addActivity(home);
            var leg=factory.createLeg("car");leg.setRoute(org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("origin"),List.of(Id.createLinkId("focus")),Id.createLinkId("destination")));
            plan.addLeg(leg);plan.addActivity(factory.createActivityFromLinkId("home",Id.createLinkId("destination")));person.addPlan(plan);scenario.getPopulation().addPerson(person);
        }
        var observed=new LinkedHashMap<String,double[]>();var stuck=new int[1];
        var events=EventsUtils.createEventsManager();
        events.addHandler(new BasicEventHandler(){public void handleEvent(Event e){
            if(e instanceof LinkEnterEvent x&&x.getLinkId().equals(focus.getId()))observed.put(x.getVehicleId().toString(),new double[]{x.getTime(),-1});
            if(e instanceof LinkLeaveEvent x&&x.getLinkId().equals(focus.getId()))observed.get(x.getVehicleId().toString())[1]=x.getTime();
            if(e instanceof PersonStuckEvent)stuck[0]++;
        }});
        var builder=new TravelTimeCalculator.Builder(network);builder.setTimeslice(100);builder.setMaxTime(300);
        var measured=builder.build();events.addHandler(measured);
        org.matsim.core.controler.PrepareForSimUtils.createDefaultPrepareForSim(scenario).run();events.initProcessing();
        new org.matsim.core.mobsim.qsim.QSimBuilder(config).useDefaults().build(scenario,events).run();events.finishProcessing();
        assertEquals(0,stuck[0]);assertEquals(7,observed.size());
        assertArrayEquals(new double[]{100,121},observed.get("p2"));
        assertArrayEquals(new double[]{199,220},observed.get("p4"));
        assertArrayEquals(new double[]{200,220},observed.get("p5"));
        assertArrayEquals(new double[]{229,240},observed.get("p6"));
        double previous=-1;for(var drive:observed.values()){assertTrue(drive[1]>=previous);previous=drive[1];}
        var snapshot=TravelTimeSnapshot.capture(network,Map.of("car",measured.getLinkTravelTimes()),Map.of(),24,300,100);
        assertEquals(21,snapshot.forMode("car").getLinkTravelTime(focus,199.9,null,null));
        assertEquals(15.5,snapshot.forMode("car").getLinkTravelTime(focus,200,null,null));
        var type=VehicleUtils.createVehicleType(Id.create("L",VehicleType.class),"car");type.setMaximumVelocity(38.888888888888886);
        type.getCostInformation().setFixedCost(189.15).setCostsPerMeter(.000386430173292559).setCostsPerSecond(0.0);
        var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
        var carrier=CarriersUtils.createCarrier(Id.create("carrier",Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");
        CarriersUtils.addCarrierVehicle(carrier,CarrierVehicle.newInstance(Id.createVehicleId("van"),Id.createLinkId("origin"),type));
        var resolved=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.of(Map.of("routing.zoneBasedCachingEnabled","false","routing.odTimeQuantizationSeconds","0")),Map.of());
        try(var context=RoutingContext.create(network,types,snapshot,resolved)){
            var costs=assertDoesNotThrow(()->new ModalTransportCosts(new RoutingRequest(carrier,context,"car")));
            assertEquals(11,costs.getTransportTime(com.graphhopper.jsprit.core.problem.Location.newInstance("origin"),
                    com.graphhopper.jsprit.core.problem.Location.newInstance("focus"),200,null,null),
                    "Public half-hour OD estimate uses its bin start and preserves the native snapshot separately");
            var vehicle=VehicleUtils.createVehicle(Id.createVehicleId("van"),type);
            assertEquals(15.5,costs.routeLeg(Id.createLinkId("origin"),focus.getId(),200,vehicle).travelTimeSeconds());
            assertEquals(100*.000386430173292559,costs.routeLeg(Id.createLinkId("origin"),focus.getId(),200,vehicle).monetaryCost(),1e-12);
        }
    }
    static Link link(Network n,String id,int a,int b,double length){
        for(int i:new int[]{a,b})if(!n.getNodes().containsKey(Id.createNodeId(i)))NetworkUtils.createAndAddNode(n,Id.createNodeId(i),new Coord(i*100,0));
        var l=NetworkUtils.createAndAddLink(n,Id.createLinkId(id),n.getNodes().get(Id.createNodeId(a)),n.getNodes().get(Id.createNodeId(b)),length,10,36000,1);l.setAllowedModes(Set.of("car"));return l;
    }
    static NetworkChangeEvent change(Link link,double time,double speed){var event=new NetworkChangeEvent(time);event.addLink(link);event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,speed));return event;}
}
