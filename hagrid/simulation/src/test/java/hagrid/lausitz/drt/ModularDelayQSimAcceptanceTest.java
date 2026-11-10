package hagrid.lausitz.drt;

import hagrid.lausitz.modular.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.controler.*;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.scenario.ScenarioUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runtime NCE after loading; one actual vehicle must finish while late diagnostics remain honest. */
class ModularDelayQSimAcceptanceTest {
    @TempDir Path output;
    @Test @Timeout(60) void unexpectedSlowdownIsObservedWithoutDroppingTheLoadedParcel() throws Exception {
        var staging=ModularE2eStaging.stage(output.resolve("inputs"));var original=staging.tours.getFirst();
        var config=LausitzDrtConfigurator.build(staging.cfgUrl.toString(),staging.drtNetFile.toString(),staging.clippedPlans.toString(),
                staging.shpFile.toString(),staging.fleetFile.toString(),output.resolve("run").toString(),"M_DL",0);
        config.network().setTimeVariantNetwork(true);config.qsim().setEndTime(10000);
        config.global().setNumberOfThreads(1);config.qsim().setNumberOfThreads(1);
        var scenario=ScenarioUtils.createScenario(config);
        scenario.getPopulation().getFactory().getRouteFactories().setRouteFactory(DrtRoute.class,new DrtRouteFactory());
        ScenarioUtils.loadScenario(scenario);scenario.getPopulation().getPersons().clear();
        // The staged four-node grid has l0..l3 and their reverses: the opposite edge is a real drive.
        int edge=Integer.parseInt(original.depotLink().toString().substring(1,2));
        var destination=Id.createLinkId("l"+((edge+2)%4));
        var stop=new ModularFreightTour.Stop(destination,10,1,2200,2300);
        var tour=new ModularFreightTour("delay",original.provider(),0,original.depotLink(),1500,0,4000,List.of(stop));
        var stats=new ModularPlanStats(1,0,0,1,Map.of(tour.tourId(),tour.depotLink()),Map.of(tour.tourId(),tour.provider()));
        var drt=MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();
        var controller=new Controler(scenario);DrtConfigComposer.installModules(controller);
        controller.addOverridingModule(new ModularDispatchModule(drt,List.of(tour),0,stats));
        var observations=new DelayObservations(scenario);
        controller.addOverridingModule(new AbstractModule(){public void install(){
            addEventHandlerBinding().toInstance(observations);addMobsimListenerBinding().toInstance(observations);
        }});
        controller.run();
        assertTrue(observations.injected,"Must apply a native within-day NCE after swap-out and drive start");
        assertEquals(1,observations.served.size());assertEquals(1,observations.stopStarts.size());
        assertTrue(observations.stopStarts.getFirst()>2300,"Must actually start late, not only predict lateness");
        assertFalse(observations.driveEnds.isEmpty());
        assertTrue(observations.stopStarts.getFirst()-observations.driveEnds.getFirst()<=2,
                "A missed window opening must not repeat the originally planned wait after delayed arrival");
        assertEquals(10,observations.served.getFirst()-observations.stopStarts.getFirst(),1e-9);
        assertEquals(1,observations.windowViolations);assertEquals(1,observations.chainViolations);
        assertEquals(1,observations.completed);assertEquals(1,observations.servedParcels);
    }
    private static final class DelayObservations implements BasicEventHandler,MobsimBeforeSimStepListener {
        final Scenario scenario;final List<Double> served=new ArrayList<>(),stopStarts=new ArrayList<>(),driveEnds=new ArrayList<>();
        boolean loaded,driveStarted,injected;int completed,servedParcels,windowViolations,chainViolations;
        DelayObservations(Scenario scenario){this.scenario=scenario;}
        @Override public void reset(int iteration){served.clear();stopStarts.clear();driveEnds.clear();loaded=driveStarted=injected=false;completed=servedParcels=windowViolations=chainViolations=0;}
        @Override public void handleEvent(Event event) {
            if(loaded&&"dvrpTaskEnded".equals(event.getEventType())&&"MODULAR_FREIGHT_DRIVE".equals(event.getAttributes().get("taskType")))
                driveEnds.add(event.getTime());
            if("dvrpTaskStarted".equals(event.getEventType())) {
                var type=event.getAttributes().get("taskType");
                if(loaded&&"MODULAR_FREIGHT_DRIVE".equals(type))driveStarted=true;
                if("MODULAR_FREIGHT_STOP".equals(type))stopStarts.add(event.getTime());
            }
            if(event instanceof ModularTourEvent e&&e.getTourId().equals("delay"))switch(e.getPhase()) {
                case SWAP_DONE -> loaded=true;
                case STOP_SERVED -> {served.add(e.getTime());servedParcels+=e.getParcels();}
                case COMPLETED -> completed++;
                case TIME_WINDOW_VIOLATION -> windowViolations++;
                case CHAIN_VIOLATION -> chainViolations++;
                default -> { }
            }
        }
        @Override public void notifyMobsimBeforeSimStep(@SuppressWarnings("rawtypes") MobsimBeforeSimStepEvent event) {
            if(!driveStarted||injected)return;
            var change=new NetworkChangeEvent(event.getSimulationTime()+1);
            change.addLinks(scenario.getNetwork().getLinks().values());
            change.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.FACTOR,.1));
            ((QSim)event.getQueueSimulation()).addNetworkChangeEvent(change);injected=true;
        }
    }
}