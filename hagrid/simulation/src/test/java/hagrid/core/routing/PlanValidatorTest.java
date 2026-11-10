package hagrid.core.routing;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.freight.carriers.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class PlanValidatorTest {
    @Test void infiniteTemplateToursAreDifferentVehiclesAndEachPaysItsFixedCost(){try(var f=new OptimizationFixture()){
        f.carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(f.vehicle)
                .setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
        var a=f.service("a",1,0,500);var b=f.service("b",1,0,500);
        var plan=new CarrierPlan(List.of(f.plan(0,a).getScheduledTours().iterator().next(),
                f.plan(0,b).getScheduledTours().iterator().next()));
        var result=PlanValidator.validate(f.carrier,plan,f.request,f.config);
        assertThat(result.violations()).isEmpty();
        assertThat(result.finalCost()).isEqualTo(200.0);
    }}
    @Test void finiteVehicleCannotDriveTwoOverlappingTours(){try(var f=new OptimizationFixture()){
        var a=f.service("a",1,0,500);var b=f.service("b",1,0,500);
        var plan=new CarrierPlan(List.of(f.plan(0,a).getScheduledTours().iterator().next(),
                f.plan(0,b).getScheduledTours().iterator().next()));
        assertThat(PlanValidator.validate(f.carrier,plan,f.request,f.config).violations())
                .anyMatch(message->message.contains("Overlapping"));
    }}
    @Test void partialCheapPlanCannotReplaceCompletePlan(){try(var f=new OptimizationFixture()){
        var a=f.service("a",1,0,500);var b=f.service("b",1,0,500);
        var previous=f.plan(0,a,b);var partial=f.plan(0,a);f.carrier.addPlan(previous);f.carrier.setSelectedPlan(previous);
        var selected=PlanSelection.choose(previous,f.result(partial,-1000),f.carrier,f.request,f.config);
        assertThat(selected.selected()).isSameAs(previous);assertThat(selected.completed()).isFalse();
        assertThat(selected.candidate().valid()).isFalse();assertThat(f.carrier.getSelectedPlan()).isSameAs(previous);
    }}
    @Test void validCheaperPreviousPlanWins(){try(var f=new OptimizationFixture()){
        var a=f.service("a",1,100,500);var previous=f.plan(80,a);var candidate=f.plan(0,a);
        var selection=PlanSelection.choose(previous,f.result(candidate,-1000),f.carrier,f.request,f.config);
        assertThat(selection.selected()).isSameAs(previous);assertThat(selection.completed()).isTrue();
        assertThat(selection.previous().orElseThrow().finalCost()).isLessThan(selection.candidate().finalCost());
    }}
    @Test void departureShiftRechecksTimeWindows(){try(var f=new OptimizationFixture()){
        var a=f.service("a",1,0,30);var shifted=f.plan(100,a);
        assertThat(PlanValidator.validate(f.carrier,shifted,f.request,f.config).violations()).anyMatch(s->s.contains("window"));
    }}
    @Test void duplicatesAndCapacityOverflowAreRejected(){try(var f=new OptimizationFixture()){
        var a=f.service("a",6,0,500);var b=f.service("b",6,0,500);
        assertThat(PlanValidator.validate(f.carrier,f.plan(0,a,b),f.request,f.config).valid()).isFalse();
        assertThat(PlanValidator.validate(f.carrier,f.plan(0,a,a),f.request,f.config).violations()).anyMatch(s->s.contains("Duplicate"));
    }}
    @Test void actualStoredRoadRouteIsCheckedInsteadOfFindingANewShortestRoute(){try(var f=new OptimizationFixture()){
        var a=f.service("a",1,0,500);var plan=f.plan(0,a);
        var leg=(Tour.Leg)plan.getScheduledTours().iterator().next().getTour().getTourElements().getFirst();
        var route=(NetworkRoute)leg.getRoute();route.setLinkIds(route.getStartLinkId(),List.of(Id.createLinkId("return")),route.getEndLinkId());
        assertThat(PlanValidator.validate(f.carrier,plan,f.request,f.config).violations()).anyMatch(s->s.contains("Disconnected"));
    }}
    @Test void requiredSkillsCannotBeOmitted(){try(var f=new OptimizationFixture()){
        var a=f.service("a",1,0,500);CarriersUtils.setSkills(a,Set.of("cold"));
        assertThat(PlanValidator.validate(f.carrier,f.plan(0,a),f.request,f.config).violations()).anyMatch(s->s.contains("skill"));
    }}
    @Test void changedCandidateVehicleProfileIsRejected(){try(var f=new OptimizationFixture()){
        var a=f.service("a",1,0,500);var original=f.plan(0,a).getScheduledTours().iterator().next();
        var type=org.matsim.vehicles.VehicleUtils.createVehicleType(f.type.getId(),"car");type.setMaximumVelocity(100);type.getCapacity().setOther(10);
        type.getCostInformation().setFixedCost(10.0).setCostsPerMeter(0.1).setCostsPerSecond(1.0);
        var spoof=CarrierVehicle.Builder.newInstance(f.vehicle.getId(),f.vehicle.getLinkId(),type).setEarliestStart(0).setLatestEnd(1000).build();
        var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(original.getTour(),spoof,0)));
        assertThat(PlanValidator.validate(f.carrier,plan,f.request,f.config).violations()).anyMatch(s->s.contains("profile"));
    }}
    @Test void shipmentPickupMustPrecedeDelivery() throws Exception {try(var f=new OptimizationFixture()){
        var shipment=CarrierShipment.Builder.newInstance(Id.create("shipment",CarrierShipment.class),Id.createLinkId("road"),Id.createLinkId("stop"),2)
                .setPickupDuration(1).setDeliveryDuration(1).build();CarriersUtils.addShipment(f.carrier,shipment);
        var b=Tour.Builder.newInstance(Id.create("shipment",Tour.class));b.scheduleStart(f.vehicle.getLinkId());
        b.addLeg(new Tour.Leg());b.schedulePickup(shipment);b.addLeg(new Tour.Leg());b.scheduleDelivery(shipment);b.addLeg(new Tour.Leg());b.scheduleEnd(f.vehicle.getLinkId());
        var tour=b.build();var field=Tour.class.getDeclaredField("tourElements");field.setAccessible(true);
        @SuppressWarnings("unchecked") var elements=(List<Tour.TourElement>)field.get(tour);
        Collections.swap(elements,1,3);
        var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(tour,f.vehicle,0)));
        org.matsim.freight.carriers.jsprit.NetworkRouter.routePlan(plan,f.costs);
        assertThat(PlanValidator.validate(f.carrier,plan,f.request,f.config).violations()).anyMatch(s->s.contains("before pickup"));
    }}
}