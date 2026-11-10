package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.matsim.freight.carriers.*;
import org.matsim.core.population.routes.NetworkRoute;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidatedCandidateReuseTest {
    @Test void detachedJobMutationInvalidatesPlanEvidenceAndJspritScoreSurvivesCopy() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);var plan=f.plan(0,job);plan.setJspritScore(123.0);
            var proof=ValidatedCarrierCandidate.capture(plan,f.carrier,f.request,f.config,25200,0);
            var detached=proof.materialize(f.carrier);assertEquals(123.0,detached.getJspritScore());
            assertTrue(proof.matchesPlan(detached));
            var service=((Tour.ServiceActivity)detached.getScheduledTours().iterator().next().getTour().getTourElements().get(1)).getService();
            CarriersUtils.setSkills(service,Set.of("changed-after-check"));
            assertFalse(proof.matchesPlan(detached));assertTrue(CarriersUtils.getSkills(job).isEmpty());
        }
    }
    @Test void checkedProofRejectsInputChangedAfterValidation() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);var plan=f.plan(0,job);
            String input=CarrierInputFingerprint.of(f.carrier);
            var validation=PlanValidator.validate(f.carrier,plan,f.costs,f.config,25200,0);
            f.service("a",1,0,100);
            assertThrows(IllegalStateException.class,()->ValidatedCarrierCandidate.checked(plan,f.carrier,f.request,f.config,25200,0,validation,input));
        }
    }
    @Test void changedRoadVehicleCannotBypassPublicationBinding() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);var plan=f.plan(0,job);
            var proof=ValidatedCarrierCandidate.capture(plan,f.carrier,f.request,f.config,25200,f.config.tourRules().uTurnPenaltyCost());
            var result=f.result(plan,123).withValidatedCandidate(proof);
            var selection=PlanSelection.choose(null,result,f.carrier,f.request,f.config);
            var publication=CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,f.request,f.config);
            publication.requireCurrent(f.carrier,f.request,f.config);
            var leg=(Tour.Leg)publication.plan().getScheduledTours().iterator().next().getTour().getTourElements().get(0);
            ((NetworkRoute)leg.getRoute()).setVehicleId(org.matsim.api.core.v01.Id.createVehicleId("foreign"));
            assertThrows(IllegalStateException.class,()->publication.requireCurrent(f.carrier,f.request,f.config));
        }
    }
    @Test void realSearchPublishesWithoutAdditionalRoutingOrFullValidation() {
        try(var f=new OptimizationFixture()) {
            f.service("a",1,0,500);
            var result=new CarrierOptimizationService().optimize(f.carrier,f.request,f.config,42,1);
            assertTrue(result.validatedCandidate().isPresent());assertTrue(result.diagnostics().isEmpty());
            var before=f.context.statistics();assertTrue(before.roadRoutingCalls()>0);assertEquals(1,before.fullValidationCalls());
            var selection=PlanSelection.choose(null,result,f.carrier,f.request,f.config);
            var publication=CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,f.request,f.config);
            publication.requireCurrent(f.carrier,f.request,f.config);
            assertEquals(before.roadRoutingCalls(),f.context.statistics().roadRoutingCalls());
            assertEquals(before.fullValidationCalls(),f.context.statistics().fullValidationCalls());
        }
    }
    @Test void validPreviousWinnerOnTieStillGetsFreshPublicationRouting() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);var previous=f.plan(0,job);var candidate=f.plan(0,job);
            var proof=ValidatedCarrierCandidate.capture(candidate,f.carrier,f.request,f.config,25200,f.config.tourRules().uTurnPenaltyCost());
            var result=f.result(candidate,proof.validation().finalCost()).withValidatedCandidate(proof);
            var selection=PlanSelection.choose(previous,result,f.carrier,f.request,f.config);assertSame(previous,selection.selected());
            long before=f.context.statistics().roadRoutingCalls();
            var publication=CarrierOptimizationService.prepareForPublication(f.carrier,selection,result,f.request,f.config);
            assertTrue(f.context.statistics().roadRoutingCalls()>before);assertNotSame(previous,publication.plan());
            publication.requireCurrent(f.carrier,f.request,f.config);
        }
    }
    @Test void changedMeasurementAndProfileContextCannotReuseCandidate() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);var plan=f.plan(0,job);
            var proof=ValidatedCarrierCandidate.capture(plan,f.carrier,f.request,f.config,25200,0);
            var changed=TravelTimeSnapshot.capture(f.context.network(),Map.of(),Map.of("car",(l,t,p,v)->l.getLength()/5),25,2000,60);
            try(var context=RoutingContext.create(f.context.network(),f.types,changed,f.config)) {
                assertFalse(proof.matches(f.carrier,new RoutingRequest(f.carrier,context,"car"),f.config,25200,0));
            }
        }
    }
    @Test void unchangedCandidateRoutesAndValidatesOnce() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);var plan=f.plan(0,job);
            CarrierRoadRouter.routePlan(plan,f.costs);
            var proof=ValidatedCarrierCandidate.capture(plan,f.carrier,f.request,f.config,25200,f.config.tourRules().uTurnPenaltyCost());
            var before=f.context.statistics();
            var result=f.result(plan,123).withValidatedCandidate(proof);
            var selected=PlanSelection.choose(null,result,f.carrier,f.request,f.config);
            var published=CarrierOptimizationService.prepareForPublication(f.carrier,selected,result,f.request,f.config);
            var after=f.context.statistics();
            assertEquals(before.roadRoutingCalls(),after.roadRoutingCalls());
            assertEquals(before.fullValidationCalls(),after.fullValidationCalls());
            assertTrue(published.validation().valid());
            assertNotSame(plan,published.plan());
            assertEquals(CarrierPlanFingerprint.of(plan),CarrierPlanFingerprint.of(published.plan()));
        }
    }
    @Test void copiesDoNotShareRoadsJobsOrTariffs() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);var plan=f.plan(0,job);
            var proof=ValidatedCarrierCandidate.capture(plan,f.carrier,f.request,f.config,25200,f.config.tourRules().uTurnPenaltyCost());
            var one=proof.materialize(f.carrier);String before=CarrierPlanFingerprint.of(one);
            var tour=one.getScheduledTours().iterator().next();
            ((Tour.Leg)tour.getTour().getTourElements().get(0)).setExpectedTransportTime(999);
            tour.getVehicle().getType().getCostInformation().setCostsPerSecond(99.0);
            CarriersUtils.setSkills(((Tour.ServiceActivity)tour.getTour().getTourElements().get(1)).getService(),Set.of("new"));
            assertEquals(before,CarrierPlanFingerprint.of(proof.materialize(f.carrier)));
            assertEquals(1,f.type.getCostInformation().getCostsPerSecond());
            assertTrue(CarriersUtils.getSkills(job).isEmpty());
        }
    }
    @Test void actualLimitsJobsContextAndRouteMutationsInvalidateEvidence() {
        try(var f=new OptimizationFixture()) {
            var job=f.service("a",1,0,500);var plan=f.plan(0,job);
            var proof=ValidatedCarrierCandidate.capture(plan,f.carrier,f.request,f.config,25200,0);
            assertTrue(proof.matches(f.carrier,f.request,f.config,25200,0));
            assertFalse(proof.matches(f.carrier,f.request,f.config,25201,0));
            assertFalse(proof.matches(f.carrier,f.request,f.config,25200,1));
            var leg=(Tour.Leg)plan.getScheduledTours().iterator().next().getTour().getTourElements().get(0);
            ((NetworkRoute)leg.getRoute()).setDistance(999);
            assertFalse(proof.matchesPlan(plan));
            f.service("a",1,0,100);
            assertFalse(proof.matches(f.carrier,f.request,f.config,25200,0));
            assertThrows(IllegalArgumentException.class,()->proof.materialize(f.carrier));
        }
    }
}
