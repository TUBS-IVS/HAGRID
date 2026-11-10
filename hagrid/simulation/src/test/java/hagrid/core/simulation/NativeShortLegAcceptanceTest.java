package hagrid.core.simulation;

import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.routing.*;
import hagrid.core.simulation.scoring.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.CarrierModule;
import org.matsim.vehicles.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual QSim counterpart to synthetic0/1-LinkEnter event tests. */
class NativeShortLegAcceptanceTest {
    @TempDir Path output;
    @ParameterizedTest @ValueSource(ints={0,1,3})
    void nativeShortLegTimeMatchesObservedExecution(int outboundEnteredLinks) {
        var resolved=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("optimization.replanningStartIteration","2","optimization.replanningIterations","1")),Map.of());
        var config=ConfigUtils.createConfig();config.controller().setLastIteration(0);
        config.controller().setOutputDirectory(output.resolve("case-"+outboundEnteredLinks).toString());
        config.global().setNumberOfThreads(1);config.qsim().setNumberOfThreads(1);config.qsim().setEndTime(400);
        config.qsim().setMainModes(List.of("car"));config.routing().setNetworkModes(List.of("car"));
        var scenario=ScenarioUtils.createScenario(config);var network=scenario.getNetwork();
        for(int i=0;i<4;i++)NetworkUtils.createAndAddNode(network,Id.createNodeId(i),new Coord(i*100,0));
        for(int i=0;i<4;i++) {
            var link=NetworkUtils.createAndAddLink(network,Id.createLinkId("l"+i),network.getNodes().get(Id.createNodeId(i)),
                    network.getNodes().get(Id.createNodeId((i+1)%4)),100,10,36000,1);link.setAllowedModes(Set.of("car"));
        }
        if(outboundEnteredLinks==3) {
            var turnNode=NetworkUtils.createAndAddNode(network,Id.createNodeId(4),new Coord(100,100));
            var node=network.getNodes().get(Id.createNodeId(1));
            var outward=NetworkUtils.createAndAddLink(network,Id.createLinkId("x"),node,turnNode,100,10,36000,1);
            var inward=NetworkUtils.createAndAddLink(network,Id.createLinkId("y"),turnNode,node,100,10,36000,1);
            outward.setAllowedModes(Set.of("car"));inward.setAllowedModes(Set.of("car"));
            var forbidden=new org.matsim.core.network.turnRestrictions.DisallowedNextLinks();
            forbidden.addDisallowedLinkSequence("car",List.of(Id.createLinkId("l1")));
            NetworkUtils.setDisallowedNextLinks(network.getLinks().get(Id.createLinkId("l0")),forbidden);
        }
        var types=new CarrierVehicleTypes();var carriers=new Carriers();
        scenario.addScenarioElement("carrierVehicleTypes",types);scenario.addScenarioElement("carriers",carriers);
        scenario.addScenarioElement("carNetwork",network);scenario.addScenarioElement("bikeNetwork",network);
        var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");type.setMaximumVelocity(10);
        type.getCapacity().setOther(10);type.getCostInformation().setFixedCost(0.0).setCostsPerMeter(0.0).setCostsPerSecond(1.0);
        types.getVehicleTypes().put(type.getId(),type);
        var carrier=CarriersUtils.createCarrier(Id.create("short",Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");
        var depot=Id.createLinkId("l0");var target=Id.createLinkId("l"+(outboundEnteredLinks==3?1:outboundEnteredLinks));
        var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("template"),depot,type).setEarliestStart(20).setLatestEnd(350).build();
        CarriersUtils.addCarrierVehicle(carrier,vehicle);carrier.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.FINITE);
        var service=CarrierService.Builder.newInstance(Id.create("service",CarrierService.class),target,1)
                .setServiceDuration(5).setServiceStartingTimeWindow(TimeWindow.newInstance(20,300)).build();
        CarriersUtils.addService(carrier,service);
        var b=Tour.Builder.newInstance(Id.create("short-tour",Tour.class));b.scheduleStart(depot);b.addLeg(new Tour.Leg());
        b.scheduleService(service);b.addLeg(new Tour.Leg());b.scheduleEnd(depot);
        var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(b.build(),vehicle,20)));plan.setCarrier(carrier);
        CarrierRoadRouter.routePlan(plan,NativeModalTransportCosts.create(network,types,"car"));
        carrier.addPlan(plan);carrier.setSelectedPlan(plan);carriers.addCarrier(carrier);
        var controller=new Controler(scenario);controller.addOverridingModule(new CarrierModule());
        controller.addOverridingModule(new HAGRIDSimulationModule(scenario,false,0,resolved));controller.run();
        var ledger=controller.getInjector().getInstance(CarrierExecutionLedger.class);
        var execution=ledger.complete(carrier.getId());assertTrue(execution.complete(),execution.violations().toString());
        assertEquals(2,execution.legs().size());assertEquals(1,execution.activities().size());
        assertEquals(outboundEnteredLinks,execution.legs().getFirst().enteredLinks().size());
        // CarrierDriverAgent uses GenericRoute for at most one LinkEnter; no assumption from driver names.
        if(outboundEnteredLinks>=1)assertTrue(execution.legs().getFirst().arrivalSeconds()>execution.legs().getFirst().departureSeconds());
        if(outboundEnteredLinks==3)assertEquals(List.of(Id.createLinkId("x"),Id.createLinkId("y"),Id.createLinkId("l1")),execution.legs().getFirst().enteredLinks());
        double observed=execution.legs().stream().mapToDouble(l->l.arrivalSeconds()-l.departureSeconds()).sum();
        var calculated=CarrierCostCalculator.calculate(carrier,execution,resolved,network);
        assertEquals(observed,calculated.costs().travelTime(),1e-9);
        assertEquals(observed,(Double)carrier.getAttributes().getAttribute("costTime"),1e-9);
        assertEquals(calculated.score(),carrier.getSelectedPlan().getScore(),1e-9);
    }
}
