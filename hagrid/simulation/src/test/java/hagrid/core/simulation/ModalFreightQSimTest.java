package hagrid.core.simulation;

import hagrid.core.routing.NativeModalTransportCosts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.*;
import org.matsim.core.network.*;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.CarrierModule;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.vehicles.VehicleUtils;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real, bounded freight-agent QSim runs, using HAGRID's mode registration and scoring/replanning module. */
class ModalFreightQSimTest {
    @TempDir Path output;
    @Test void actualMixedNetworkKeepsCargoBikeModeAndConfigured18KmhLimit() { run(10,false,0,20); }
    @Test void slowerRoadWinsOverBikeVehicleLimit() { run(2,false,0,50); }
    @Test void networkChangeEventsSlowActualVehiclesAfterTheChange() { run(10,true,150,50); }

    @Test void default20KmhLimitAlsoReachesTheRealSimulation() { run(10,false,0,18,20,false); }
    @Test void aFollowingCarWaitsForTheBikeOnASharedFifoRoad() { run(10,false,0,20,18,true); }

    private void run(double roadSpeed,boolean change,double departure,double minimumBikeSeconds) {
        run(roadSpeed,change,departure,minimumBikeSeconds,18,false);
    }
    private void run(double roadSpeed,boolean change,double departure,double minimumBikeSeconds,double bikeSpeedKmh,boolean shared) {
        var config=ConfigUtils.createConfig();
        config.network().setTimeVariantNetwork(change);
        config.controller().setLastIteration(0);config.controller().setOutputDirectory(output.toString());
        config.controller().setCreateGraphsInterval(0);config.controller().setDumpDataAtEnd(false);
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
        config.global().setNumberOfThreads(1);config.qsim().setNumberOfThreads(1);
        config.qsim().setStartTime(0);config.qsim().setEndTime(1000);
        FreightScenarioConfiguration.includeCarrierModes(config,Set.of("car","cargobike"));
        config.travelTimeCalculator().setSeparateModes(true);
        var scenario=ScenarioUtils.createScenario(config);var n=scenario.getNetwork();
        for(int i=0;i<4;i++)n.addNode(n.getFactory().createNode(Id.createNodeId("n"+i),new Coord(i*100,0)));
        link(n,"origin",0,1,Set.of("car","cargobike"),10);
        link(n,"car-road",1,2,Set.of("car"),shared?0.1:10);
        link(n,"bike-road",1,2,shared?Set.of("car","cargobike"):Set.of("cargobike"),roadSpeed);
        link(n,"destination",2,3,Set.of("car","cargobike"),10);
        link(n,"return",3,0,Set.of("car","cargobike"),10);
        if(change){
            var event=new NetworkChangeEvent(100);
            event.addLink(n.getLinks().get(Id.createLinkId("bike-road")));
            event.addLink(n.getLinks().get(Id.createLinkId("car-road")));
            event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,2));
            NetworkUtils.addNetworkChangeEvent(n,event);
        }
        scenario.addScenarioElement("carrierVehicleTypes",new CarrierVehicleTypes());
        var types=CarriersUtils.getCarrierVehicleTypes(scenario);var carriers=CarriersUtils.getOrCreateCarriers(scenario);
        for(String mode:List.of("car","cargobike")){
            var type=VehicleUtils.createVehicleType(Id.create(mode,org.matsim.vehicles.VehicleType.class),mode);
            type.setMaximumVelocity(mode.equals("car")?20:bikeSpeedKmh/3.6);type.getCapacity().setOther(10);
            type.getCostInformation().setFixedCost(1.0);type.getCostInformation().setCostsPerMeter(0.01);type.getCostInformation().setCostsPerSecond(0.01);
            types.getVehicleTypes().put(type.getId(),type);
            var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId(mode),Id.createLinkId("origin"),type)
                    .setEarliestStart(0).setLatestEnd(1000).build();
            var carrier=CarriersUtils.createCarrier(Id.create(mode,Carrier.class));
            CarriersUtils.addCarrierVehicle(carrier,vehicle);CarriersUtils.setCarrierMode(carrier,mode);
            var service=CarrierService.Builder.newInstance(Id.create(mode+"-job",CarrierService.class),Id.createLinkId("destination"))
                    .setCapacityDemand(1).setServiceDuration(1).build();CarriersUtils.addService(carrier,service);
            var tour=Tour.Builder.newInstance(Id.create(mode+"-tour",Tour.class));
            tour.scheduleStart(vehicle.getLinkId());tour.addLeg(new Tour.Leg());tour.scheduleService(service);
            tour.addLeg(new Tour.Leg());tour.scheduleEnd(vehicle.getLinkId());
            var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(tour.build(),vehicle,departure+(shared&&mode.equals("car")?8:0))));
            plan.setCarrier(carrier);carrier.addPlan(plan);carrier.setSelectedPlan(plan);carriers.addCarrier(carrier);
            var costs=NativeModalTransportCosts.create(n,types,mode,(l,t,p,v)->l.getLength()/l.getFreespeed(t));
            NetworkRouter.routePlan(plan,costs);
        }
        scenario.addScenarioElement("carNetwork",n);scenario.addScenarioElement("bikeNetwork",n);
        var recorder=new Recorder();var controler=new Controler(scenario);
        controler.addOverridingModule(new CarrierModule());
        controler.addOverridingModule(new HAGRIDSimulationModule(scenario,false,0,1,false,0,0));
        controler.addOverridingModule(new AbstractModule(){public void install(){addEventHandlerBinding().toInstance(recorder);}});
        controler.run();
        assertThat(recorder.modes.values()).contains("car","cargobike");
        assertThat(recorder.stuck).isZero();
        assertThat(recorder.modeDurations.get("bike-road/cargobike")).isNotEmpty().allSatisfy(dt->assertThat(dt).isGreaterThanOrEqualTo(minimumBikeSeconds-1));
        if (shared) {
            assertThat(recorder.roadModes.get("bike-road")).containsExactlyInAnyOrder("car","cargobike");
            assertThat(recorder.modeDurations.get("bike-road/car")).isNotEmpty()
                    .allSatisfy(dt->assertThat(dt).isGreaterThan(10));
        } else {
            assertThat(recorder.roadModes.get("bike-road")).containsOnly("cargobike");
            assertThat(recorder.roadModes.get("car-road")).containsOnly("car");
        }
        if(change)assertThat(recorder.durations.get("car-road")).allSatisfy(dt->assertThat(dt).isGreaterThanOrEqualTo(49));
    }
    private static void link(Network n,String id,int from,int to,Set<String> modes,double speed){
        var link=n.getFactory().createLink(Id.createLinkId(id),n.getNodes().get(Id.createNodeId("n"+from)),n.getNodes().get(Id.createNodeId("n"+to)));
        link.setLength(100);link.setFreespeed(speed);link.setCapacity(36000);link.setNumberOfLanes(1);link.setAllowedModes(modes);n.addLink(link);
    }
    static final class Recorder implements LinkEnterEventHandler,LinkLeaveEventHandler,VehicleEntersTrafficEventHandler,PersonStuckEventHandler {
        final Map<String,String> modes=new HashMap<>();final Map<String,Double> entries=new HashMap<>();
        final Map<String,List<Double>> durations=new HashMap<>();final Map<String,List<Double>> modeDurations=new HashMap<>();final Map<String,Set<String>> roadModes=new HashMap<>();int stuck;
        public void handleEvent(VehicleEntersTrafficEvent e){modes.put(e.getVehicleId().toString(),e.getNetworkMode());}
        public void handleEvent(LinkEnterEvent e){entries.put(e.getVehicleId()+"/"+e.getLinkId(),e.getTime());}
        public void handleEvent(LinkLeaveEvent e){
            var key=e.getVehicleId()+"/"+e.getLinkId();var entered=entries.remove(key);if(entered==null)return;
            durations.computeIfAbsent(e.getLinkId().toString(),k->new ArrayList<>()).add(e.getTime()-entered);
            roadModes.computeIfAbsent(e.getLinkId().toString(),k->new HashSet<>()).add(modes.get(e.getVehicleId().toString()));
            modeDurations.computeIfAbsent(e.getLinkId()+"/"+modes.get(e.getVehicleId().toString()),k->new ArrayList<>()).add(e.getTime()-entered);
        }
        public void handleEvent(PersonStuckEvent e){stuck++;}
    }
}