package hagrid.core.simulation;

import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.simulation.scoring.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.events.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CarrierExecutionScoringTest {
    @Test void certifiedSectionTurnsDriveObservedCountAndTariff() {
        var f=new Fixture();var network=f.scenario.getNetwork();var origin=network.getLinks().get(f.depot);
        var reverse=network.getFactory().createLink(Id.createLinkId("reverse"),origin.getToNode(),origin.getFromNode());reverse.setLength(100);network.addLink(reverse);
        var parallel=network.getFactory().createLink(Id.createLinkId("parallel"),origin.getFromNode(),origin.getToNode());parallel.setLength(100);network.addLink(parallel);
        var builder=Tour.Builder.newInstance(Id.create("section",Tour.class));builder.scheduleStart(f.depot);
        var route=org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(f.depot,List.of(reverse.getId()),parallel.getId());
        builder.addLeg(builder.createLeg(route,0,10));builder.scheduleEnd(parallel.getId());
        var second=Tour.Builder.newInstance(Id.create("second",Tour.class));second.scheduleStart(f.depot);
        second.addLeg(second.createLeg(org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(f.depot,f.depot),20,10));second.scheduleEnd(f.depot);
        var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(builder.build(),f.vehicle,0),ScheduledTour.newInstance(second.build(),f.vehicle,20)));f.carrier.addPlan(plan);f.carrier.setSelectedPlan(plan);
        f.carrier.getAttributes().putAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER,"catalog");
        hagrid.core.routing.sections.SectionRoadCosts roads=new hagrid.core.routing.sections.SectionRoadCosts() {
            public TurnClassification classifyTransition(Id<org.matsim.api.core.v01.network.Link> a,Id<org.matsim.api.core.v01.network.Link> b) { return a.equals(f.depot)&&b.equals(reverse.getId())?TurnClassification.TURN:TurnClassification.NOT_TURN; }
            public hagrid.core.routing.sections.SectionLeg leg(Id<org.matsim.api.core.v01.network.Link> a,Id<org.matsim.api.core.v01.network.Link> b,VehicleType t) { throw new UnsupportedOperationException(); }
            public hagrid.core.routing.RoadLeg materialize(Id<org.matsim.api.core.v01.network.Link> a,Id<org.matsim.api.core.v01.network.Link> b,double x,Vehicle v) { throw new UnsupportedOperationException(); }
            public String fingerprint(){return "fixture";}
        };
        var evidence=hagrid.core.routing.SectionBindingCompanion.capture(f.carrier,roads,network);
        var tourId=Id.create("section",Tour.class);
        var secondId=Id.create("second",Tour.class);
        var execution=new CarrierExecutionLedger.CarrierExecution(List.of(new CarrierExecutionLedger.ExecutedTour(f.actual,tourId,0,10),new CarrierExecutionLedger.ExecutedTour(f.actual,secondId,20,30)),List.of(new CarrierExecutionLedger.ExecutedLeg(f.actual,tourId,0,10,List.of(reverse.getId(),parallel.getId()),f.depot,parallel.getId()),new CarrierExecutionLedger.ExecutedLeg(f.actual,secondId,20,30,List.of(),f.depot,f.depot)),List.of(),true,List.of());
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.of(Map.of("tourRules.uTurnPenaltyCost","7")),Map.of());
        assertThatThrownBy(()->CarrierCostCalculator.calculate(f.carrier,execution,config,network)).isInstanceOf(IllegalStateException.class);
        var result=CarrierCostCalculator.calculate(f.carrier,execution,config,network,evidence);
        assertThat(result.costs().uTurnCount()).isEqualTo(1);
        assertThat(result.score()).isEqualTo(-result.costs().totalCosts()-7);
        var unexpected=new CarrierExecutionLedger.CarrierExecution(execution.tours(),List.of(new CarrierExecutionLedger.ExecutedLeg(f.actual,tourId,0,10,List.of(parallel.getId()),f.depot,parallel.getId())),List.of(),true,List.of());
        assertThatThrownBy(()->CarrierCostCalculator.calculate(f.carrier,unexpected,config,network,evidence)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Uncertified");
    }
    @Test void markedCarrierWithoutVerifiedTurnEvidenceFailsInsteadOfUsingEndpoints() {
        var f=new Fixture();f.addTour("a",0);f.tour("a",0,11);
        f.carrier.getAttributes().putAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER,"fixture");
        assertThatThrownBy(()->CarrierCostCalculator.calculate(f.carrier,f.ledger.complete(f.carrier.getId()),f.config,f.scenario.getNetwork())).isInstanceOf(IllegalStateException.class).hasMessageContaining("section turn evidence");
    }
    @Test void markedFactoryRejectsLegacyAndMissingEvidenceWhileOrdinaryWorks() {
        var f=new Fixture();f.addTour("a",0);
        assertThat(new ScoringFunctions(f.scenario.getNetwork(),0).createScoringFunction(f.carrier)).isNotNull();
        assertThat(new ScoringFunctions(f.scenario.getNetwork(),f.config).createScoringFunction(f.carrier)).isNotNull();
        f.carrier.getAttributes().putAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER,"fixture");
        assertThatThrownBy(()->new ScoringFunctions(f.scenario.getNetwork(),0).createScoringFunction(f.carrier)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ScoringFunctions(f.scenario.getNetwork(),f.config).createScoringFunction(f.carrier)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ScoringFunctions(f.scenario.getNetwork(),f.config,f.ledger).createScoringFunction(f.carrier)).isInstanceOf(IllegalStateException.class);
    }
    @Test void ledgerPreservesCertifiedTurnAcrossServiceBoundary() {
        var f=new Fixture();var n=f.scenario.getNetwork();var home=n.getLinks().get(f.depot);
        var reverse=n.getFactory().createLink(Id.createLinkId("reverse"),home.getToNode(),home.getFromNode());reverse.setLength(100);n.addLink(reverse);
        var service=CarrierService.Builder.newInstance(Id.create("job",CarrierService.class),f.depot,1).setServiceDuration(2).setServiceStartingTimeWindow(TimeWindow.newInstance(0,100)).build();CarriersUtils.addService(f.carrier,service);
        var builder=Tour.Builder.newInstance(Id.create("a",Tour.class));builder.scheduleStart(f.depot);
        builder.addLeg(builder.createLeg(org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(f.depot,f.depot),0,1));builder.scheduleService(service);
        builder.addLeg(builder.createLeg(org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(f.depot,reverse.getId()),3,1));builder.scheduleEnd(reverse.getId());
        var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(builder.build(),f.vehicle,0)));f.carrier.addPlan(plan);f.carrier.setSelectedPlan(plan);f.carrier.getAttributes().putAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER,"fixture");
        hagrid.core.routing.sections.SectionRoadCosts roads=new hagrid.core.routing.sections.SectionRoadCosts() {
            public TurnClassification classifyTransition(Id<org.matsim.api.core.v01.network.Link> a,Id<org.matsim.api.core.v01.network.Link> b){return TurnClassification.TURN;}
            public hagrid.core.routing.sections.SectionLeg leg(Id<org.matsim.api.core.v01.network.Link> a,Id<org.matsim.api.core.v01.network.Link> b,VehicleType t){throw new UnsupportedOperationException();}
            public hagrid.core.routing.RoadLeg materialize(Id<org.matsim.api.core.v01.network.Link> a,Id<org.matsim.api.core.v01.network.Link> b,double d,Vehicle v){throw new UnsupportedOperationException();}
            public String fingerprint(){return "fixture";}
        };
        var evidence=hagrid.core.routing.SectionBindingCompanion.capture(f.carrier,roads,n);
        assertThat(new ScoringFunctions(n,f.config,f.ledger,Map.of("c",evidence)).createScoringFunction(f.carrier)).isNotNull();
        f.ledger.handleEvent(new CarrierTourStartEvent(0,f.carrier.getId(),f.depot,f.actual,Id.create("a",Tour.class)));f.ledger.handleEvent(new PersonEntersVehicleEvent(0,f.person,f.actual));
        f.ledger.handleEvent(new PersonDepartureEvent(0,f.person,f.depot,"car","car"));f.ledger.handleEvent(new PersonArrivalEvent(1,f.person,f.depot,"car"));
        f.ledger.handleEvent(new CarrierServiceStartEvent(1,f.carrier.getId(),service,f.actual));f.ledger.handleEvent(new CarrierServiceEndEvent(3,f.carrier.getId(),service,f.actual));
        f.ledger.handleEvent(new PersonDepartureEvent(3,f.person,f.depot,"car","car"));f.ledger.handleEvent(new LinkEnterEvent(4,f.actual,reverse.getId()));f.ledger.handleEvent(new PersonArrivalEvent(5,f.person,reverse.getId(),"car"));f.ledger.handleEvent(new CarrierTourEndEvent(5,f.carrier.getId(),reverse.getId(),f.actual,Id.create("a",Tour.class)));
        var execution=f.ledger.complete(f.carrier.getId());assertThat(execution.complete()).as(execution.violations().toString()).isTrue();
        assertThat(CarrierCostCalculator.calculate(f.carrier,execution,f.config,n,evidence).costs().uTurnCount()).isEqualTo(1);
        var noBoundary=new CarrierExecutionLedger.CarrierExecution(execution.tours(),List.of(new CarrierExecutionLedger.ExecutedLeg(f.actual,Id.create("a",Tour.class),0,1,List.of())),execution.activities(),true,List.of());
        assertThatThrownBy(()->CarrierCostCalculator.calculate(f.carrier,noBoundary,f.config,n,evidence)).isInstanceOf(IllegalStateException.class).hasMessageContaining("boundary");
        var plannedLeg=(Tour.Leg)plan.getScheduledTours().iterator().next().getTour().getTourElements().get(2);var savedRoute=plannedLeg.getRoute();plannedLeg.setRoute(org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(f.depot,f.depot));
        assertThatThrownBy(()->new ScoringFunctions(n,f.config,f.ledger,Map.of("c",evidence)).createScoringFunction(f.carrier)).isInstanceOf(IllegalStateException.class).hasMessageContaining("plan");plannedLeg.setRoute(savedRoute);
        hagrid.core.routing.sections.SectionRoadCosts unknown=new hagrid.core.routing.sections.SectionRoadCosts() {
            public hagrid.core.routing.sections.SectionLeg leg(Id<org.matsim.api.core.v01.network.Link> a,Id<org.matsim.api.core.v01.network.Link> b,VehicleType t){throw new UnsupportedOperationException();}
            public hagrid.core.routing.RoadLeg materialize(Id<org.matsim.api.core.v01.network.Link> a,Id<org.matsim.api.core.v01.network.Link> b,double d,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "unknown";}
        };
        assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.capture(f.carrier,unknown,n)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Uncertifiable");
        assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.install(f.scenario,Map.of("wrong",evidence))).isInstanceOf(IllegalStateException.class);
        hagrid.core.routing.SectionBindingCompanion.install(f.scenario,Map.of("c",evidence));
        assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.context(f.scenario).clear()).isInstanceOf(UnsupportedOperationException.class);
        f.carrier.getAttributes().putAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER,"changed");
        assertThatThrownBy(()->CarrierCostCalculator.calculate(f.carrier,execution,f.config,n,evidence)).isInstanceOf(IllegalStateException.class);
    }
    @Test void sectionFactoryHashesSharedNetworkOnceForTwoCompletedCarriers() {
        var f=new Fixture();var n=f.scenario.getNetwork();
        var builder=Tour.Builder.newInstance(Id.create("a",Tour.class));builder.scheduleStart(f.depot);builder.addLeg(builder.createLeg(org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(f.depot,f.depot),0,10));builder.scheduleEnd(f.depot);
        var tour=builder.build();var one=new CarrierPlan(List.of(ScheduledTour.newInstance(tour,f.vehicle,0)));f.carrier.addPlan(one);f.carrier.setSelectedPlan(one);f.carrier.getAttributes().putAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER,"fixture");
        var other=CarriersUtils.createCarrier(Id.create("other",Carrier.class));CarriersUtils.addCarrierVehicle(other,f.vehicle);other.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.FINITE);var two=new CarrierPlan(List.of(ScheduledTour.newInstance(tour,f.vehicle,0)));other.addPlan(two);other.setSelectedPlan(two);other.getAttributes().putAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER,"fixture");CarriersUtils.getCarriers(f.scenario).addCarrier(other);
        hagrid.core.routing.sections.SectionRoadCosts roads=new hagrid.core.routing.sections.SectionRoadCosts() {
            public hagrid.core.routing.sections.SectionLeg leg(Id<org.matsim.api.core.v01.network.Link> a,Id<org.matsim.api.core.v01.network.Link> b,VehicleType t){throw new UnsupportedOperationException();}
            public hagrid.core.routing.RoadLeg materialize(Id<org.matsim.api.core.v01.network.Link> a,Id<org.matsim.api.core.v01.network.Link> b,double d,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "fixture";}
        };
        var evidence=Map.of("c",hagrid.core.routing.SectionBindingCompanion.capture(f.carrier,roads,n),"other",hagrid.core.routing.SectionBindingCompanion.capture(other,roads,n));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var observed=(org.matsim.api.core.v01.network.Network)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.matsim.api.core.v01.network.Network.class},(proxy,method,args)->{if(method.getName().equals("getNodes"))count.incrementAndGet();try{return method.invoke(n,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});
        var scope=hagrid.core.routing.SectionBindingCompanion.captureScope(observed);assertThat(count.get()).isEqualTo(1);
        var scoped=Map.of("c",scope.capture(f.carrier,roads),"other",scope.capture(other,roads));assertThat(count.get()).isEqualTo(1);
        var binding=scope.bind(scoped);assertThat(count.get()).isEqualTo(2);
        assertThatThrownBy(()->binding.forCarrier(f.carrier,n)).isInstanceOf(IllegalStateException.class).hasMessageContaining("instance");
        var originalLength=n.getLinks().get(f.depot).getLength();n.getLinks().get(f.depot).setLength(originalLength+1);
        assertThatThrownBy(()->new ScoringFunctions(observed,f.config,f.ledger,evidence)).isInstanceOf(IllegalStateException.class).hasMessageContaining("network");
        var incomplete=new CarrierExecutionLedger.CarrierExecution(List.of(),List.of(),List.of(),false,List.of());
        assertThatThrownBy(()->CarrierCostCalculator.calculate(f.carrier,incomplete,f.config,observed,evidence.get("c"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("network");
        n.getLinks().get(f.depot).setLength(originalLength);count.set(0);
        new ScoringFunctions(observed,f.config,f.ledger,Map.of());assertThat(count.get()).isZero();
        var factory=new ScoringFunctions(observed,f.config,f.ledger,evidence);assertThat(count.get()).isEqualTo(1);
        var scorer1=factory.createScoringFunction(f.carrier);var scorer2=factory.createScoringFunction(other);assertThat(count.get()).isEqualTo(1);
        f.tour("a",0,10);f.ledger.handleEvent(new CarrierTourStartEvent(20,other.getId(),f.depot,f.actual,Id.create("a",Tour.class)));f.ledger.handleEvent(new PersonEntersVehicleEvent(20,f.person,f.actual));f.ledger.handleEvent(new PersonDepartureEvent(20,f.person,f.depot,"car","car"));f.ledger.handleEvent(new PersonArrivalEvent(30,f.person,f.depot,"car"));f.ledger.handleEvent(new CarrierTourEndEvent(30,other.getId(),f.depot,f.actual,Id.create("a",Tour.class)));
        scorer1.finish();scorer2.finish();assertThat(count.get()).isEqualTo(1);
    }
    @Test void separateShortToursDoNotCreateOvertime() {
        var f = new Fixture(); f.addTour("a",0); f.addTour("b",26000);
        f.tour("a",0,20); f.tour("b",26000,26020);
        var evaluation = CarrierCostCalculator.calculate(f.carrier,f.ledger.complete(f.carrier.getId()),f.config);
        assertThat(evaluation.costs().overtime()).isZero();
        assertThat(evaluation.costs().totalTravelTimeSeconds()).isEqualTo(40);
    }
    @Test void returnLegAndLastServiceCountTowardShift() {
        var f = new Fixture(); f.addTour("a",0); f.tour("a",0,25201);
        assertThat(CarrierCostCalculator.calculate(f.carrier,f.ledger.complete(f.carrier.getId()),f.config).costs().overtime()).isEqualTo(5);
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void shortGenericRouteStillCostsTime(int links) {
        var f = new Fixture(); f.addTour("a",0);
        f.ledger.handleEvent(new CarrierTourStartEvent(0,f.carrier.getId(),f.depot,f.actual,Id.create("a",Tour.class)));
        // Deliberately reversed order at time zero: no driver-name inference is allowed.
        f.ledger.handleEvent(new PersonDepartureEvent(0,f.person,f.depot,"car","car"));
        f.ledger.handleEvent(new PersonEntersVehicleEvent(0,f.person,f.actual));
        for (int i=0;i<links;i++) f.ledger.handleEvent(new LinkEnterEvent(i+1,f.actual,f.depot));
        f.ledger.handleEvent(new PersonArrivalEvent(11,f.person,f.depot,"car"));
        f.ledger.handleEvent(new CarrierTourEndEvent(11,f.carrier.getId(),f.depot,f.actual,Id.create("a",Tour.class)));
        var execution=f.ledger.complete(f.carrier.getId());
        assertThat(execution.complete()).isTrue();
        assertThat(execution.legs()).singleElement().satisfies(l -> {
            assertThat(l.arrivalSeconds()-l.departureSeconds()).isEqualTo(11); assertThat(l.enteredLinks()).hasSize(links);
        });
        assertThat(CarrierCostCalculator.calculate(f.carrier,execution,f.config,f.scenario.getNetwork()).costs().totalTravelTimeSeconds()).isEqualTo(11);
    }
    @Test void missingEndStuckAndResetCannotBecomeCheapCompletedResults() {
        var f = new Fixture(); f.addTour("a",0);
        f.ledger.handleEvent(new CarrierTourStartEvent(0,f.carrier.getId(),f.depot,f.actual,Id.create("a",Tour.class)));
        f.ledger.handleEvent(new PersonEntersVehicleEvent(0,f.person,f.actual));
        f.ledger.handleEvent(new PersonDepartureEvent(0,f.person,f.depot,"car","car"));
        f.ledger.handleEvent(new PersonStuckEvent(5,f.person,f.depot,"car"));
        var incomplete=f.ledger.complete(f.carrier.getId());
        assertThat(incomplete.complete()).isFalse(); assertThat(incomplete.violations()).anyMatch(v -> v.contains("stuck"));
        assertThatIllegalArgumentException().isThrownBy(() -> CarrierCostCalculator.calculate(f.carrier,incomplete,f.config));
        f.ledger.reset(1); f.tour("a",0,11);
        assertThat(f.ledger.complete(f.carrier.getId()).complete()).isTrue();
    }
    @Test void fixedCostsUseFiniteVehicleOnceAndInfiniteTourInstancesSeparately() {
        for (var fleet:CarrierCapabilities.FleetSize.values()) {
            var f=new Fixture(); f.carrier.getCarrierCapabilities().setFleetSize(fleet);
            f.addTour("a",0); f.addTour("b",30); f.tour("a",0,11); f.tour("b",30,41);
            var costs=CarrierCostCalculator.calculate(f.carrier,f.ledger.complete(f.carrier.getId()),f.config).costs();
            assertThat(costs.fixed()).isEqualTo(fleet==CarrierCapabilities.FleetSize.FINITE ? 100 : 200);
        }
    }
    @Test void repeatedNativeTourStartIsDiagnosed() {
        var f=new Fixture(); f.addTour("a",0);
        var event=new CarrierTourStartEvent(0,f.carrier.getId(),f.depot,f.actual,Id.create("a",Tour.class));
        f.ledger.handleEvent(event); f.ledger.handleEvent(event);
        assertThat(f.ledger.complete(f.carrier.getId()).violations()).anyMatch(v -> v.contains("Duplicate"));
    }
    @Test void finalServiceAndReturnBothContributeToTourShift() {
        var f=new Fixture();
        var service=CarrierService.Builder.newInstance(Id.create("long",CarrierService.class),f.depot,1)
                .setServiceDuration(25199).setServiceStartingTimeWindow(TimeWindow.newInstance(0,30000)).build();
        f.carrier.getServices().put(service.getId(),service);
        var b=Tour.Builder.newInstance(Id.create("a",Tour.class));b.scheduleStart(f.depot);
        b.addLeg(new Tour.Leg());b.scheduleService(service);b.addLeg(new Tour.Leg());b.scheduleEnd(f.depot);
        var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(b.build(),f.vehicle,0)));
        f.carrier.addPlan(plan);f.carrier.setSelectedPlan(plan);
        f.ledger.handleEvent(new CarrierTourStartEvent(0,f.carrier.getId(),f.depot,f.actual,Id.create("a",Tour.class)));
        f.ledger.handleEvent(new PersonEntersVehicleEvent(0,f.person,f.actual));
        f.ledger.handleEvent(new PersonDepartureEvent(0,f.person,f.depot,"car","car"));
        f.ledger.handleEvent(new PersonArrivalEvent(1,f.person,f.depot,"car"));
        f.ledger.handleEvent(new CarrierServiceStartEvent(1,f.carrier.getId(),service,f.actual));
        f.ledger.handleEvent(new CarrierServiceEndEvent(25200,f.carrier.getId(),service,f.actual));
        f.ledger.handleEvent(new PersonDepartureEvent(25200,f.person,f.depot,"car","car"));
        f.ledger.handleEvent(new PersonArrivalEvent(25201,f.person,f.depot,"car"));
        f.ledger.handleEvent(new CarrierTourEndEvent(25201,f.carrier.getId(),f.depot,f.actual,Id.create("a",Tour.class)));
        var execution=f.ledger.complete(f.carrier.getId());assertThat(execution.complete()).isTrue();
        var costs=CarrierCostCalculator.calculate(f.carrier,execution,f.config,f.scenario.getNetwork()).costs();
        assertThat(costs.overtime()).isEqualTo(5);assertThat(costs.totalTravelTimeSeconds()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void reverseTransitionAtLegBoundaryIsCountedOnce(int enteredLinks) {
        var f=new Fixture();f.addTour("a",0);var n=f.scenario.getNetwork();
        var origin=n.getLinks().get(f.depot);var end=Id.createLinkId("reverse");
        var reverse=n.getFactory().createLink(end,origin.getToNode(),origin.getFromNode());reverse.setLength(100);n.addLink(reverse);
        f.ledger.handleEvent(new CarrierTourStartEvent(0,f.carrier.getId(),f.depot,f.actual,Id.create("a",Tour.class)));
        f.ledger.handleEvent(new PersonEntersVehicleEvent(0,f.person,f.actual));
        f.ledger.handleEvent(new PersonDepartureEvent(0,f.person,f.depot,"car","car"));
        if(enteredLinks==1)f.ledger.handleEvent(new LinkEnterEvent(1,f.actual,end));
        f.ledger.handleEvent(new PersonArrivalEvent(11,f.person,end,"car"));
        f.ledger.handleEvent(new CarrierTourEndEvent(11,f.carrier.getId(),end,f.actual,Id.create("a",Tour.class)));
        var costs=CarrierCostCalculator.calculate(f.carrier,f.ledger.complete(f.carrier.getId()),f.config,n).costs();
        assertThat(costs.uTurnCount()).isEqualTo(1);
        assertThat(costs.totalDistanceMeters()).isEqualTo(100*enteredLinks);
    }
    @Test void interleavedDriversRetainTheirOwnTourClocks() {
        var f=new Fixture();f.addTour("a",0);
        var other=CarriersUtils.createCarrier(Id.create("other",Carrier.class));CarriersUtils.addCarrierVehicle(other,f.vehicle);
        var b=Tour.Builder.newInstance(Id.create("a",Tour.class));b.scheduleStart(f.depot);b.addLeg(new Tour.Leg());b.scheduleEnd(f.depot);
        var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(b.build(),f.vehicle,0)));other.addPlan(plan);other.setSelectedPlan(plan);
        ((Carriers)f.scenario.getScenarioElement("carriers")).addCarrier(other);
        var actual=Id.createVehicleId("other-actual");var driver=Id.createPersonId("other-driver");var tour=Id.create("a",Tour.class);
        f.ledger.handleEvent(new CarrierTourStartEvent(0,f.carrier.getId(),f.depot,f.actual,tour));
        f.ledger.handleEvent(new CarrierTourStartEvent(0,other.getId(),f.depot,actual,tour));
        f.ledger.handleEvent(new PersonEntersVehicleEvent(0,f.person,f.actual));
        f.ledger.handleEvent(new PersonEntersVehicleEvent(0,driver,actual));
        f.ledger.handleEvent(new PersonDepartureEvent(0,f.person,f.depot,"car","car"));
        f.ledger.handleEvent(new PersonDepartureEvent(0,driver,f.depot,"car","car"));
        f.ledger.handleEvent(new PersonArrivalEvent(11,f.person,f.depot,"car"));
        f.ledger.handleEvent(new CarrierTourEndEvent(11,f.carrier.getId(),f.depot,f.actual,tour));
        f.ledger.handleEvent(new PersonArrivalEvent(22,driver,f.depot,"car"));
        f.ledger.handleEvent(new CarrierTourEndEvent(22,other.getId(),f.depot,actual,tour));
        var one=f.ledger.complete(f.carrier.getId());var two=f.ledger.complete(other.getId());
        assertThat(one.complete()).isTrue();assertThat(two.complete()).isTrue();
        assertThat(one.legs()).singleElement().satisfies(l->assertThat(l.vehicleId()).isEqualTo(f.actual));
        assertThat(two.legs()).singleElement().satisfies(l->assertThat(l.vehicleId()).isEqualTo(actual));
        assertThat(CarrierCostCalculator.calculate(f.carrier,one,f.config).costs().totalTravelTimeSeconds()).isEqualTo(11);
        assertThat(CarrierCostCalculator.calculate(other,two,f.config).costs().totalTravelTimeSeconds()).isEqualTo(22);
    }
    private static final class Fixture {
        final Scenario scenario=ScenarioUtils.createScenario(ConfigUtils.createConfig());
        final ResolvedHagridConfig config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.empty(),Map.of());
        final Carrier carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));
        final Id<org.matsim.api.core.v01.network.Link> depot=Id.createLinkId("depot");
        final Id<Vehicle> actual=Id.createVehicleId("actual-not-template");
        final Id<org.matsim.api.core.v01.population.Person> person=Id.createPersonId("driver-without-freight-prefix");
        final CarrierVehicle vehicle;
        final CarrierExecutionLedger ledger;
        Fixture() {
            var n=scenario.getNetwork();var a=n.getFactory().createNode(Id.createNodeId("a"),new Coord(0,0));var b=n.getFactory().createNode(Id.createNodeId("b"),new Coord(100,0));
            n.addNode(a);n.addNode(b);var link=n.getFactory().createLink(depot,a,b);link.setLength(100);n.addLink(link);
            var type=VehicleUtils.createVehicleType(Id.create("t",VehicleType.class),"car");type.getCostInformation().setFixedCost(100.0).setCostsPerSecond(1.0).setCostsPerMeter(0.0);
            vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("template"),depot,type).build();CarriersUtils.addCarrierVehicle(carrier,vehicle);
            carrier.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.FINITE);
            var carriers=new Carriers();carriers.addCarrier(carrier);scenario.addScenarioElement("carriers",carriers);
            ledger=new CarrierExecutionLedger(scenario,config);
        }
        void addTour(String id,double departure) {
            var b=Tour.Builder.newInstance(Id.create(id,Tour.class));b.scheduleStart(depot);b.addLeg(new Tour.Leg());b.scheduleEnd(depot);
            var tours=new ArrayList<ScheduledTour>();if(carrier.getSelectedPlan()!=null)tours.addAll(carrier.getSelectedPlan().getScheduledTours());
            tours.add(ScheduledTour.newInstance(b.build(),vehicle,departure));var plan=new CarrierPlan(tours);carrier.addPlan(plan);carrier.setSelectedPlan(plan);
        }
        void tour(String id,double start,double end) {
            ledger.handleEvent(new CarrierTourStartEvent(start,carrier.getId(),depot,actual,Id.create(id,Tour.class)));
            ledger.handleEvent(new PersonEntersVehicleEvent(start,person,actual));
            ledger.handleEvent(new PersonDepartureEvent(start,person,depot,"car","car"));
            ledger.handleEvent(new PersonArrivalEvent(end,person,depot,"car"));
            ledger.handleEvent(new CarrierTourEndEvent(end,carrier.getId(),depot,actual,Id.create(id,Tour.class)));
        }
    }
}
