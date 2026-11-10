package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.VehicleUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class NativeConstantProfileCacheTest {
    @Test
    void seededTightWindowsWithOneMillisecondSlackPreserveFeasibilityCostAndRoute() {
        var f=fixture(false);
        var canonical=NativeModalTransportCosts.create(f.network,f.types,"car");
        var reference=NativeModalTransportCosts.create(f.network,f.types,"car",(l,t,p,v)->l.getLength()/Math.min(v.getType().getMaximumVelocity(),l.getFreespeed(t)));
        double departure=27001.125;
        for(String type:List.of("van","slow"))
            for(String[] pair:List.of(new String[]{"f0","f2"},new String[]{"f2","f0"})) {
                var van=VehicleImpl.Builder.newInstance(type).setType(VehicleTypeImpl.Builder.newInstance(type)
                    .setMaxVelocity(type.equals("van")?11.1:7.3).setCostPerDistance(type.equals("van")?.1:.2)
                    .setCostPerTransportTime(.02).build()).setStartLocation(loc(pair[0])).setReturnToDepot(false)
                    .setEarliestStart(departure).setLatestArrival(departure+1000).build();
                // A real but small feasibility margin: no rounding/tolerance changes in production.
                double deadline=departure+Math.min(canonical.getTransportTime(loc(pair[0]),loc(pair[1]),departure,null,van),
                    reference.getTransportTime(loc(pair[0]),loc(pair[1]),departure,null,van))+.001;
                for(long seed:new long[]{42,4711,194535502}) {
                    var expected=tightSolution(reference,van,pair[1],departure,deadline,seed);
                    var actual=tightSolution(canonical,van,pair[1],departure,deadline,seed);
                    assertThat(expected.getUnassignedJobs()).isEmpty();assertThat(actual.getUnassignedJobs()).isEmpty();
                    assertThat(actual.getCost()).isEqualTo(expected.getCost());
                    assertThat(actual.getRoutes()).hasSize(1);assertThat(expected.getRoutes()).hasSize(1);
                    var actualRoute=actual.getRoutes().iterator().next();var expectedRoute=expected.getRoutes().iterator().next();
                    assertThat(actualRoute.getVehicle().getId()).isEqualTo(expectedRoute.getVehicle().getId());
                    assertThat(actualRoute.getActivities().stream().map(a->a.getLocation().getId()).toList())
                        .containsExactlyElementsOf(expectedRoute.getActivities().stream().map(a->a.getLocation().getId()).toList());
                    assertThat(actualRoute.getActivities().get(0).getArrTime()).isLessThanOrEqualTo(deadline);
                }
            }
    }
    private static com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution tightSolution(
            VRPTransportCosts costs,VehicleImpl van,String destination,double departure,double deadline,long seed) {
        var job=com.graphhopper.jsprit.core.problem.job.Service.Builder.newInstance("tight").setLocation(loc(destination))
            .setTimeWindow(com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow.newInstance(departure,deadline)).build();
        var problem=com.graphhopper.jsprit.core.problem.VehicleRoutingProblem.Builder.newInstance()
            .setFleetSize(com.graphhopper.jsprit.core.problem.VehicleRoutingProblem.FleetSize.FINITE)
            .setRoutingCost(costs).addVehicle(van).addJob(job).build();
        var algorithm=com.graphhopper.jsprit.core.algorithm.box.Jsprit.Builder.newInstance(problem)
            .setRandom(new java.util.Random(seed)).buildAlgorithm();
        algorithm.setMaxIterations(10);
        return com.graphhopper.jsprit.core.util.Solutions.bestOf(algorithm.searchSolutions());
    }
    @Test
    void certifiedFreeFlowDoesNotRecomputeIdenticalRoadsForEachTimeBin() throws Exception {
        var f=fixture(false);var costs=NativeModalTransportCosts.create(f.network,f.types,"car");
        for(double time:new double[]{0,1800,7200,54000,108000}) {
            costs.getTransportTime(loc("f0"),loc("f2"),time,null,vehicle("van",11.1));
            costs.getTransportCost(loc("f0"),loc("f2"),time,null,vehicle("van",11.1));
        }
        assertThat(calculations(costs)).as("one canonical constant-profile query, independent of departure bin").isEqualTo(1);
    }
    @Test
    void constantProfileMatchesUncertifiedReferenceAcrossTypesDirectionsAndBackwardQueries() {
        var f=fixture(false);
        var compact=NativeModalTransportCosts.create(f.network,f.types,"car");
        var reference=NativeModalTransportCosts.create(f.network,f.types,"car",(l,t,p,v)->l.getLength()/Math.min(v.getType().getMaximumVelocity(),l.getFreespeed(t)));
        for(var van:List.of(vehicle("van",11.1),vehicle("slow",7.3)))
            for(String[] pair:List.of(new String[]{"f0","f2"},new String[]{"f2","f0"}))
                for(double time:new double[]{0,1801,27000,54000,108000}) {
                    var a=loc(pair[0]);var b=loc(pair[1]);
                    assertThat(compact.getTransportCost(a,b,time,null,van)).isEqualTo(reference.getTransportCost(a,b,time,null,van));
                    assertThat(compact.getDistance(a,b,time,van)).isEqualTo(reference.getDistance(a,b,time,van));
                    assertThat(compact.getTransportTime(a,b,time,null,van)).isCloseTo(reference.getTransportTime(a,b,time,null,van),within(1e-8));
                    assertThat(compact.getBackwardTransportTime(a,b,time,null,van)).isCloseTo(reference.getBackwardTransportTime(a,b,time,null,van),within(1e-8));
                    assertThat(compact.getBackwardTransportCost(a,b,time,null,van)).isEqualTo(reference.getBackwardTransportCost(a,b,time,null,van));
                }
    }
    @Test
    void customTimeDependentCallbackKeepsItsDepartureBins() throws Exception {
        var f=fixture(false);
        var costs=NativeModalTransportCosts.create(f.network,f.types,"car",(l,t,p,v)->l.getLength()/(t>=1800?5:10));
        var van=vehicle("van",11.1);
        double early=costs.getTransportTime(loc("f0"),loc("f2"),0,null,van);
        double late=costs.getTransportTime(loc("f0"),loc("f2"),1800,null,van);
        assertThat(late).isCloseTo(early*2,within(1e-9));assertThat(calculations(costs)).isEqualTo(2);
    }
    @Test
    void networkEventsKeepDistinctTimeDependentResults() throws Exception {
        var f=fixture(true);var event=new NetworkChangeEvent(1800);
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,5));
        event.addLink(f.network.getLinks().get(Id.createLinkId("f1")));NetworkUtils.setNetworkChangeEvents(f.network,List.of(event));
        var costs=NativeModalTransportCosts.create(f.network,f.types,"car");var van=vehicle("van",11.1);
        double early=costs.getTransportTime(loc("f0"),loc("f2"),0,null,van);
        double late=costs.getTransportTime(loc("f0"),loc("f2"),1800,null,van);
        assertThat(late).isGreaterThan(early);assertThat(calculations(costs)).isEqualTo(2);
    }
    private static long calculations(VRPTransportCosts costs) throws Exception {
        var field=costs.getClass().getDeclaredField("delegate");field.setAccessible(true);
        return ((NetworkBasedTransportCosts)field.get(costs)).ttMemorizedCounter.getCounter();
    }
    private static Location loc(String id){return Location.newInstance(id);}
    private static VehicleImpl vehicle(String id,double speed){return VehicleImpl.Builder.newInstance(id)
        .setType(VehicleTypeImpl.Builder.newInstance(id).setMaxVelocity(speed).build()).setStartLocation(loc("f0")).build();}
    private static Fixture fixture(boolean dynamic) {
        var cfg=new NetworkConfigGroup();cfg.setTimeVariantNetwork(dynamic);var net=NetworkUtils.createNetwork(cfg);
        var nodes=new org.matsim.api.core.v01.network.Node[4];
        for(int i=0;i<4;i++)nodes[i]=NetworkUtils.createAndAddNode(net,Id.createNodeId(i),new Coord(i*90,0));
        double[] lengths={100.3,120.7,90.2};
        for(int i=0;i<3;i++) {
            NetworkUtils.createAndAddLink(net,Id.createLinkId("f"+i),nodes[i],nodes[i+1],lengths[i],13.3,1000,1);
            NetworkUtils.createAndAddLink(net,Id.createLinkId("r"+i),nodes[i+1],nodes[i],lengths[i],13.3,1000,1);
        }
        var types=new CarrierVehicleTypes();
        for(String id:List.of("van","slow")) {
            var t=VehicleUtils.createVehicleType(Id.create(id,org.matsim.vehicles.VehicleType.class),"car");
            t.setMaximumVelocity(id.equals("van")?11.1:7.3);
            t.getCostInformation().setCostsPerMeter(id.equals("van")?.1:.2).setCostsPerSecond(.02).setFixedCost(1.0);
            types.getVehicleTypes().put(t.getId(),t);
        }
        return new Fixture(net,types);
    }
    private record Fixture(org.matsim.api.core.v01.network.Network network,CarrierVehicleTypes types){}
}
