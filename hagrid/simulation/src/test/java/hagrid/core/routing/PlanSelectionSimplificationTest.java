package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.freight.carriers.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanSelectionSimplificationTest {
    @ParameterizedTest @CsvSource({"78,101,true", "80,99,false"})
    void publishedWinnerUsesCommonCostsInsteadOfSearchScore(double departure,double cost,boolean keepOld) {
        try(var f=new OptimizationFixture()) {
            var job=job(f,9);var previous=f.plan(79,job);var candidate=f.plan(departure,job);
            var result=f.result(candidate,-1000);
            var selection=PlanSelection.choose(previous,result,f.carrier,f.request,f.config);
            assertEquals(100,selection.previous().orElseThrow().finalCost(),1e-6);
            assertEquals(cost,selection.candidate().finalCost(),1e-6);
            assertSame(keepOld?previous:candidate,selection.selected());
            var publication=CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,f.request,f.config);
            assertEquals(keepOld?100:99,publication.validation().finalCost(),1e-6);
            publication.requireCurrent(f.carrier,f.request,f.config);
        }
    }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void publicationDoesNotRepeatTheSameFullValidation(boolean oldWins,boolean certified) {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,100,500);
            var previous=oldWins?f.plan(80,job):null;var candidate=f.plan(0,job);
            var result=f.result(candidate,-1000);
            if(certified)result=result.withValidatedCandidate(ValidatedCarrierCandidate.capture(candidate,f.carrier,
                    f.request,f.config,25200,f.config.tourRules().uTurnPenaltyCost()));
            var selection=PlanSelection.choose(previous,result,f.carrier,f.request,f.config);
            long before=f.context.statistics().fullValidationCalls();
            var publication=CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,f.request,f.config);
            assertTrue(publication.validation().valid());
            assertEquals(oldWins||!certified?1:0,f.context.statistics().fullValidationCalls()-before);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void nonFifoReroutingCannotLoseTheCheaperStoredRoads(boolean legacy) {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);
            var second=CarrierService.Builder.newInstance(Id.create("b",CarrierService.class),Id.createLinkId("return"))
                    .setCapacityDemand(1).setServiceDuration(10).setServiceStartingTimeWindow(TimeWindow.newInstance(0,150)).build();
            CarriersUtils.addService(f.carrier,second);
            var previous=f.plan(0,job,second);var candidate=f.plan(1,job,second);
            var road=(NetworkRoute)((Tour.Leg)previous.getScheduledTours().iterator().next().getTour()
                    .getTourElements().getFirst()).getRoute();
            road.setLinkIds(Id.createLinkId("depot"),List.of(Id.createLinkId("detour-a"),Id.createLinkId("detour-b")),Id.createLinkId("stop"));
            var candidateElements=candidate.getScheduledTours().iterator().next().getTour().getTourElements();
            ((NetworkRoute)((Tour.Leg)candidateElements.getFirst()).getRoute()).setLinkIds(Id.createLinkId("depot"),
                    List.of(Id.createLinkId("detour-a"),Id.createLinkId("detour-b")),Id.createLinkId("stop"));
            ((NetworkRoute)((Tour.Leg)candidateElements.getLast()).getRoute()).setLinkIds(Id.createLinkId("return"),
                    List.of(Id.createLinkId("depot"),Id.createLinkId("road"),Id.createLinkId("stop"),Id.createLinkId("return")),Id.createLinkId("depot"));
            f.type.getCostInformation().setCostsPerSecond(0.0);
            var snapshot=TravelTimeSnapshot.capture(detourNetwork(),Map.of(),Map.of("car",
                    (org.matsim.core.router.util.TravelTime)(l,t,p,v)->l.getId().toString().equals("return")&&t<90?200:l.getLength()/10),25,2000,10);
            try(var context=RoutingContext.create(detourNetwork(),f.types,snapshot,f.config)) {
                var request=new RoutingRequest(f.carrier,context,"car");var costs=new ModalTransportCosts(request);
                var result=result(f,candidate,context);
                var selection=legacy?PlanSelection.choose(previous,result,f.carrier,costs,f.config,25200)
                        :PlanSelection.choose(previous,result,f.carrier,request,f.config);
                assertEquals(110,selection.previous().orElseThrow().finalCost(),1e-6);
                assertEquals(150,selection.candidate().finalCost(),1e-6);
                assertSame(previous,selection.selected());
                CarrierPlan published;
                if(legacy)published=CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,costs,f.config,25200);
                else {
                    var publication=CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,request,f.config);
                    publication.requireCurrent(f.carrier,request,f.config);published=publication.plan();
                    assertEquals(110,publication.validation().finalCost(),1e-6);
                }
                assertEquals(110,PlanValidator.validate(f.carrier,published,request,f.config).finalCost(),1e-6);
                var tour=published.getScheduledTours().iterator().next().getTour();
                assertEquals(80,((Tour.TourActivity)tour.getTourElements().get(1)).getExpectedArrival(),1e-6);
                assertEquals(100,((Tour.TourActivity)tour.getTourElements().get(3)).getExpectedArrival(),1e-6);
                assertEquals(110,((Tour.Leg)tour.getTourElements().getLast()).getExpectedDepartureTime(),1e-6);
                assertEquals(10,((Tour.Leg)tour.getTourElements().getLast()).getExpectedTransportTime(),1e-6);
                assertEquals(List.of(Id.createLinkId("detour-a"),Id.createLinkId("detour-b")),
                        ((NetworkRoute)((Tour.Leg)tour.getTourElements().getFirst()).getRoute()).getLinkIds());
                assertEquals(20,((Tour.TourActivity)previous.getScheduledTours().iterator().next().getTour()
                        .getTourElements().get(1)).getExpectedArrival(),1e-6);
            }
        }
    }

    @Test void profileChangeReevaluatesBothPlansAndRejectsTheOldProof() {
        try(var f=new OptimizationFixture()) {
            var job=job(f,9);var previous=f.plan(79,job);var candidate=f.plan(0,job);
            var proof=ValidatedCarrierCandidate.capture(candidate,f.carrier,f.request,f.config,25200,f.config.tourRules().uTurnPenaltyCost());
            var snapshot=TravelTimeSnapshot.capture(f.context.network(),Map.of(),Map.of("car",
                    (org.matsim.core.router.util.TravelTime)(l,t,p,v)->l.getLength()/5),25,2000,60);
            try(var context=RoutingContext.create(f.context.network(),f.types,snapshot,f.config)) {
                var request=new RoutingRequest(f.carrier,context,"car");
                var selection=PlanSelection.choose(previous,result(f,candidate,context).withValidatedCandidate(proof),f.carrier,request,f.config);
                assertTrue(selection.candidateProof().isEmpty());
                assertEquals(139,selection.previous().orElseThrow().finalCost(),1e-6);
                assertEquals(199,selection.candidate().finalCost(),1e-6);
                assertEquals(2,context.statistics().fullValidationCalls());
                assertSame(previous,selection.selected());
            }
        }
    }

    @Test void departureMutationAfterSelectionCannotPublishAMoreExpensiveCandidate() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,100,500);var candidate=f.plan(80,job);
            var proof=ValidatedCarrierCandidate.capture(candidate,f.carrier,f.request,f.config,25200,f.config.tourRules().uTurnPenaltyCost());
            var result=f.result(candidate,-1000).withValidatedCandidate(proof);
            var selection=PlanSelection.choose(null,result,f.carrier,f.request,f.config);
            var tour=candidate.getScheduledTours().iterator().next();
            candidate.getScheduledTours().clear();candidate.getScheduledTours().add(ScheduledTour.newInstance(tour.getTour(),tour.getVehicle(),0));
            assertThrows(IllegalStateException.class,()->CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,f.request,f.config));
        }
    }

    @Test void publicationCannotSwapTheProfileUsedForSelection() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);var candidate=f.plan(0,job);
            var result=f.result(candidate,-1000);
            var selection=PlanSelection.choose(null,result,f.carrier,f.request,f.config);
            var snapshot=TravelTimeSnapshot.capture(f.context.network(),Map.of(),Map.of("car",
                    (org.matsim.core.router.util.TravelTime)(l,t,p,v)->l.getLength()/10),25,2000,60);
            try(var context=RoutingContext.create(f.context.network(),f.types,snapshot,f.config)) {
                var request=new RoutingRequest(f.carrier,context,"car");
                assertThrows(IllegalArgumentException.class,()->CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,request,f.config));
            }
        }
    }

    @Test void legacyPublicationRetainsNativeInfiniteFleetVehicleIdentities() {
        try(var f=new OptimizationFixture()) {
            f.carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(f.vehicle)
                    .setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
            var job=f.service("a",1,0,500);var candidate=f.plan(0,job);
            var original=candidate.getScheduledTours().iterator().next();
            var generated=CarrierVehicle.Builder.newInstance(Id.createVehicleId("van-copy"),f.vehicle.getLinkId(),f.type)
                    .setEarliestStart(0).setLatestEnd(1000).build();
            candidate.getScheduledTours().clear();candidate.getScheduledTours().add(ScheduledTour.newInstance(original.getTour(),generated,0));
            var result=f.result(candidate,-1000);
            var selection=PlanSelection.choose(null,result,f.carrier,f.costs,f.config,25200);
            var published=CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,f.costs,f.config,25200);
            assertEquals("van-copy",published.getScheduledTours().iterator().next().getVehicle().getId().toString());
            assertTrue(PlanValidator.validate(f.carrier,published,f.costs,f.config,25200).valid());
        }
    }

    @ParameterizedTest @ValueSource(strings={"departure","window","vehicle","roadlinks"})
    void publicationProofRejectsSemanticMutations(String change) {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);var candidate=f.plan(0,job);
            var result=f.result(candidate,-1000).withValidatedCandidate(ValidatedCarrierCandidate.capture(candidate,
                    f.carrier,f.request,f.config,25200,f.config.tourRules().uTurnPenaltyCost()));
            var selection=PlanSelection.choose(null,result,f.carrier,f.request,f.config);
            var publication=CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,f.request,f.config);
            var plan=publication.plan();var scheduled=plan.getScheduledTours().iterator().next();
            switch(change) {
                case "departure" -> {
                    plan.getScheduledTours().clear();plan.getScheduledTours().add(ScheduledTour.newInstance(scheduled.getTour(),scheduled.getVehicle(),10));
                }
                case "window" -> f.service("a",1,0,100);
                case "vehicle" -> {
                    var changed=CarrierVehicle.Builder.newInstance(scheduled.getVehicle().getId(),f.vehicle.getLinkId(),f.type)
                            .setEarliestStart(0).setLatestEnd(999).build();
                    plan.getScheduledTours().clear();plan.getScheduledTours().add(ScheduledTour.newInstance(scheduled.getTour(),changed,0));
                }
                case "roadlinks" -> ((NetworkRoute)((Tour.Leg)scheduled.getTour().getTourElements().getFirst()).getRoute())
                        .setLinkIds(Id.createLinkId("depot"),List.of(Id.createLinkId("road"),Id.createLinkId("stop"),
                                Id.createLinkId("return"),Id.createLinkId("depot"),Id.createLinkId("road")),Id.createLinkId("stop"));
                default -> throw new AssertionError(change);
            }
            assertThrows(IllegalStateException.class,()->publication.requireCurrent(f.carrier,f.request,f.config));
        }
    }

    private static CarrierService job(OptimizationFixture f,double duration) {
        var job=CarrierService.Builder.newInstance(Id.create("a",CarrierService.class),Id.createLinkId("stop"))
                .setCapacityDemand(1).setServiceDuration(duration).setServiceStartingTimeWindow(TimeWindow.newInstance(100,500)).build();
        CarriersUtils.addService(f.carrier,job);return job;
    }
    private static CarrierOptimizationService.Result result(OptimizationFixture f,CarrierPlan plan,RoutingContext context) {
        return new CarrierOptimizationService.Result("test",plan,Set.of(),-1000,-1000,1,null,context.fingerprint(),List.of());
    }
    private static Network detourNetwork() {
        var network=NetworkUtils.createNetwork();
        for(int i=0;i<5;i++)network.addNode(network.getFactory().createNode(Id.createNodeId("n"+i),new Coord(i*100,0)));
        String[] ids={"depot","road","stop","return","detour-a","detour-b"};
        int[] from={0,1,2,3,1,4},to={1,2,3,0,4,2};
        for(int i=0;i<ids.length;i++) {
            var link=network.getFactory().createLink(Id.createLinkId(ids[i]),network.getNodes().get(Id.createNodeId("n"+from[i])),network.getNodes().get(Id.createNodeId("n"+to[i])));
            link.setLength(i<4?100:350);link.setFreespeed(10);link.setCapacity(1000);link.setNumberOfLanes(1);link.setAllowedModes(Set.of("car"));network.addLink(link);
        }
        return network;
    }
}
