package hagrid.core.routing;

import com.graphhopper.jsprit.core.problem.Capacity;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.routing.sections.*;
import hagrid.core.simulation.scoring.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.events.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Consumer-level regression for a different carrier's two-way midpoint catalog. */
class OrdinarySplitTurnConsumerTest {
    private static final double TARIFF=7;

    @Test void nativeSearchIncludesActualDepotAndServiceReversals() {
        for(boolean split : List.of(false,true)) {
            var fixture=new Fixture(split);
            var types=new CarrierVehicleTypes();types.getVehicleTypes().put(fixture.vehicle.getType().getId(),fixture.vehicle.getType());
            var costs=NativeModalTransportCosts.create(fixture.network,types,"car");
            var result=new CarrierOptimizationService().optimizeInitial(fixture.carrier,costs,2,1000,TARIFF);
            assertThat(result.diagnostics()).isEmpty();
            assertThat(result.searchCost()).isCloseTo(result.finalCost(),within(1e-8));
            assertThat(result.finalCost()).isEqualTo(60+2*TARIFF);
            var free=new CarrierOptimizationService().optimizeInitial(fixture.carrier,costs,2,1000,0);
            assertThat(free.searchCost()).isCloseTo(free.finalCost(),within(1e-8));
            assertThat(free.finalCost()).isEqualTo(60);
        }
    }

    @Test void insertionCostSurvivesActualUnionSplitAndRemap() {
        var raw=new Fixture(false); var split=new Fixture(true);
        assertThat(raw.carrier.getId()).isEqualTo(split.carrier.getId());
        assertThat(split.carrier.getAttributes().getAttribute(SectionBindingCompanion.MARKER)).isNull();
        double before=raw.insertion(TARIFF); double after=split.insertion(TARIFF);
        assertThat(before).isEqualTo(TARIFF); assertThat(after).isEqualTo(before);
        assertThat(split.insertion(0)).isZero();
        var f1=split.network.getLinks().get(split.f.getFirst()); var f2=split.network.getLinks().get(split.f.getLast());
        var r1=split.network.getLinks().get(split.r.getFirst()); var r2=split.network.getLinks().get(split.r.getLast());
        assertThat(f1.getToNode().getId()).isNotEqualTo(r2.getFromNode().getId());
        assertThat(f1.getToNode().getId()).isNotEqualTo(r1.getToNode().getId());
        assertThat(new UTurnSoftConstraint(split.network,TARIFF).isUTurn(new Activity(f1.getId()),new Activity(f2.getId()))).isFalse();
        assertThat(split.network.getLinks().get(Id.createLinkId("untouched")).getAttributes().getAttribute("sectionOriginalFromNodeId")).isNull();
        assertThat(split.network.getLinks().get(Id.createLinkId("untouched")).getAttributes().getAttribute("sectionOriginalToNodeId")).isNull();
        assertThat(split.f.stream().map(split.network.getLinks()::get).mapToDouble(Link::getLength).sum()).isEqualTo(100);
        assertThat(split.r.stream().map(split.network.getLinks()::get).mapToDouble(Link::getLength).sum()).isEqualTo(100);
    }

    @Test void storedRoadReplayKeepsBothRealTurnCharges() {
        var raw=new Fixture(false); var split=new Fixture(true);
        var r0=raw.validate(0); var rp=raw.validate(TARIFF);
        var s0=split.validate(0); var sp=split.validate(TARIFF);
        assertThat(r0.violations()).isEmpty(); assertThat(rp.violations()).isEmpty();
        assertThat(s0.violations()).isEmpty(); assertThat(sp.violations()).isEmpty();
        assertThat(rp.finalCost()-r0.finalCost()).isEqualTo(2*TARIFF);
        assertThat(sp.finalCost()-s0.finalCost()).isEqualTo(2*TARIFF);
        assertThat(sp.finalCost()).isEqualTo(rp.finalCost());
        assertThat(s0.finalCost()).isEqualTo(r0.finalCost());
    }

    @Test void storedRoadReplayRejectsDisconnectedPrivateDirectionJump() {
        var split=new Fixture(true);
        var leg=(Tour.Leg)split.plan.getScheduledTours().iterator().next().getTour().getTourElements().getFirst();
        var route=(org.matsim.core.population.routes.NetworkRoute)leg.getRoute();
        route.setLinkIds(split.f.getLast(),List.of(),split.r.getLast());
        assertThat(split.validate(TARIFF).violations()).anyMatch(v->v.contains("Disconnected"));
    }

    @Test void actualLedgerKeepsDistanceTurnCountAndPositiveTariff() {
        var raw=new Fixture(false); var split=new Fixture(true);
        var rawExecution=raw.execution(); var splitExecution=split.execution();
        var rawFree=CarrierCostCalculator.calculate(raw.carrier,rawExecution,config(0),raw.network);
        var rawCharged=CarrierCostCalculator.calculate(raw.carrier,rawExecution,config(TARIFF),raw.network);
        var splitFree=CarrierCostCalculator.calculate(split.carrier,splitExecution,config(0),split.network);
        var splitCharged=CarrierCostCalculator.calculate(split.carrier,splitExecution,config(TARIFF),split.network);
        assertThat(rawCharged.costs().uTurnCount()).isEqualTo(2);
        assertThat(splitCharged.costs().uTurnCount()).isEqualTo(2);
        assertThat(rawCharged.costs().totalDistanceMeters()).isEqualTo(200);
        assertThat(splitCharged.costs().totalDistanceMeters()).isEqualTo(200);
        assertThat(rawFree.score()-rawCharged.score()).isEqualTo(2*TARIFF);
        assertThat(splitFree.score()-splitCharged.score()).isEqualTo(2*TARIFF);
        assertThat(splitCharged.score()).isEqualTo(rawCharged.score());
    }

    private static ResolvedHagridConfig config(double penalty) {
        return HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.Overrides.empty(),
                HagridConfigResolver.Overrides.of(Map.of("tourRules.uTurnPenaltyCost",Double.toString(penalty))),Map.of());
    }
    private static final class Fixture {
        final Network network; final List<Id<Link>> f,r; final Carrier carrier; final CarrierPlan plan;
        final CarrierVehicle vehicle; final CarrierService service;
        Fixture(boolean split) {
            var original=NetworkUtils.createNetwork();
            var a=NetworkUtils.createAndAddNode(original,Id.createNodeId("a"),new Coord(0,0));
            var b=NetworkUtils.createAndAddNode(original,Id.createNodeId("b"),new Coord(100,0));
            var c=NetworkUtils.createAndAddNode(original,Id.createNodeId("c"),new Coord(200,0));
            for(var link:List.of(NetworkUtils.createAndAddLink(original,Id.createLinkId("f"),a,b,100,10,1000,1),
                    NetworkUtils.createAndAddLink(original,Id.createLinkId("r"),b,a,100,10,1000,1),
                    NetworkUtils.createAndAddLink(original,Id.createLinkId("untouched"),b,c,100,10,1000,1))) link.setAllowedModes(Set.of("car"));
            var type=VehicleUtils.createVehicleType(Id.create("type",VehicleType.class),"car");
            type.setMaximumVelocity(10); type.getCapacity().setOther(10);
            type.getCostInformation().setFixedCost(0.0).setCostsPerMeter(.2).setCostsPerSecond(1.0);
            var source=CarriersUtils.createCarrier(Id.create("ordinary",Carrier.class)); CarriersUtils.setCarrierMode(source,"car");
            var originalVehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("vehicle"),Id.createLinkId("f"),type).setEarliestStart(0).setLatestEnd(1000).build();
            CarriersUtils.addCarrierVehicle(source,originalVehicle);
            CarriersUtils.addService(source,CarrierService.Builder.newInstance(Id.create("job",CarrierService.class),Id.createLinkId("r"),1)
                    .setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(0,1000)).build());
            if(split) {
                var rf=new PreparedHaltCatalog.Road("a","b",100,100,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(100,0)));
                var rr=new PreparedHaltCatalog.Road("b","a",100,100,List.of(new PreparedHaltCatalog.Point(100,0),new PreparedHaltCatalog.Point(0,0)));
                var halt=new PreparedHaltCatalog.Job("other-job","f",List.of(new PreparedHaltCatalog.Approach("f",.5),new PreparedHaltCatalog.Approach("r",.5)),"","");
                var catalog=new PreparedHaltCatalog("other-carrier",Map.of("f",rf,"r",rr),List.of(halt),"f");
                var roads=SplitRoadNetworkBuilder.buildUnion(original,List.of(catalog)).get("other-carrier");
                network=roads.network(); f=roads.children().get(Id.createLinkId("f")); r=roads.children().get(Id.createLinkId("r"));
                carrier=SectionInputRemapper.remap(source,roads);
            } else { network=original; f=List.of(Id.createLinkId("f")); r=List.of(Id.createLinkId("r")); carrier=source; }
            vehicle=carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
            service=carrier.getServices().get(Id.create("job",CarrierService.class));
            assertThat(vehicle.getLinkId()).isEqualTo(f.getLast()); assertThat(service.getServiceLinkId()).isEqualTo(r.getLast());
            var tour=Tour.Builder.newInstance(Id.create("tour",Tour.class)); tour.scheduleStart(f.getLast());
            tour.addLeg(tour.createLeg(RouteUtils.createLinkNetworkRouteImpl(f.getLast(),r.subList(0,r.size()-1),r.getLast()),0,10));
            tour.scheduleService(service);
            tour.addLeg(tour.createLeg(RouteUtils.createLinkNetworkRouteImpl(r.getLast(),f.subList(0,f.size()-1),f.getLast()),10,10));
            tour.scheduleEnd(f.getLast());
            plan=new CarrierPlan(List.of(ScheduledTour.newInstance(tour.build(),vehicle,0))); plan.setCarrier(carrier); carrier.addPlan(plan); carrier.setSelectedPlan(plan);
        }
        double insertion(double penalty) {
            return new UTurnSoftConstraint(network,penalty).getCosts(null,new Activity(f.getLast()),new Activity(r.getLast()),new Activity(Id.createLinkId("untouched")),0);
        }
        PlanValidator.Validation validate(double penalty) {
            var types=new CarrierVehicleTypes(); types.getVehicleTypes().put(vehicle.getType().getId(),vehicle.getType());
            return PlanValidator.validate(carrier,plan,NativeModalTransportCosts.create(network,types,"car"),config(penalty),1000,penalty);
        }
        CarrierExecutionLedger.CarrierExecution execution() {
            var scenario=ScenarioUtils.createScenario(ConfigUtils.createConfig()); var carriers=new Carriers(); carriers.addCarrier(carrier); scenario.addScenarioElement("carriers",carriers);
            var ledger=new CarrierExecutionLedger(scenario,config(TARIFF)); var actual=Id.createVehicleId("actual"); var driver=Id.createPersonId("driver"); var tour=Id.create("tour",Tour.class);
            ledger.handleEvent(new CarrierTourStartEvent(0,carrier.getId(),f.getLast(),actual,tour));
            ledger.handleEvent(new PersonEntersVehicleEvent(0,driver,actual));
            ledger.handleEvent(new PersonDepartureEvent(0,driver,f.getLast(),"car","car"));
            int i=1; for(var id:r) ledger.handleEvent(new LinkEnterEvent(i++,actual,id));
            ledger.handleEvent(new PersonArrivalEvent(10,driver,r.getLast(),"car"));
            ledger.handleEvent(new CarrierServiceStartEvent(10,carrier.getId(),service,actual));
            ledger.handleEvent(new CarrierServiceEndEvent(10,carrier.getId(),service,actual));
            ledger.handleEvent(new PersonDepartureEvent(10,driver,r.getLast(),"car","car"));
            i=11; for(var id:f) ledger.handleEvent(new LinkEnterEvent(i++,actual,id));
            ledger.handleEvent(new PersonArrivalEvent(20,driver,f.getLast(),"car"));
            ledger.handleEvent(new CarrierTourEndEvent(20,carrier.getId(),f.getLast(),actual,tour));
            var result=ledger.complete(carrier.getId()); assertThat(result.complete()).isTrue(); assertThat(result.violations()).isEmpty(); return result;
        }
    }
    private static final class Activity implements TourActivity {
        private final Location location;
        Activity(Id<Link> id){location=Location.newInstance(id.toString());}
        @Override public Location getLocation(){return location;}
        @Override public double getTheoreticalEarliestOperationStartTime(){return 0;}
        @Override public double getTheoreticalLatestOperationStartTime(){return Double.MAX_VALUE;}
        @Override public void setTheoreticalEarliestOperationStartTime(double v){}
        @Override public void setTheoreticalLatestOperationStartTime(double v){}
        @Override public double getOperationTime(){return 0;}
        @Override public double getArrTime(){return 0;}
        @Override public double getEndTime(){return 0;}
        @Override public void setArrTime(double v){}
        @Override public void setEndTime(double v){}
        @Override public Capacity getSize(){return Capacity.Builder.newInstance().build();}
        @Override public TourActivity duplicate(){return new Activity(Id.createLinkId(location.getId()));}
        @Override public String getName(){return "fixture";}
        @Override public int getIndex(){return 0;}
    }
}
