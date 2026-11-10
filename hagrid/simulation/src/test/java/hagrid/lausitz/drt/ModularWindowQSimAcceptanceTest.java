package hagrid.lausitz.drt;

import hagrid.lausitz.modular.*;
import hagrid.lausitz.simulation.DrtScenarioBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.controler.*;
import org.matsim.core.events.handler.BasicEventHandler;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** One tour, one bounded stop, no passenger population; actual native DVRP stay execution. */
class ModularWindowQSimAcceptanceTest {
    @TempDir Path output;
    @Test void realVehicleWaitsUntilWindowAndThenCompletesExcursion() throws Exception {
        var staging=ModularE2eStaging.stage(output.resolve("inputs"));
        var original=staging.tours.getFirst();
        var stop=new ModularFreightTour.Stop(original.stops().getFirst().link(),10,1,3000,3500);
        var tour=new ModularFreightTour("window",original.provider(),0,original.depotLink(),1500,0,4000,List.of(stop));
        var stats=new ModularPlanStats(1,0,0,1,Map.of(tour.tourId(),tour.depotLink()),Map.of(tour.tourId(),tour.provider()));
        var scenario=DrtScenarioBuilder.build(staging.cfgUrl.toString(),staging.drtNetFile.toString(),staging.clippedPlans.toString(),
                staging.shpFile.toString(),staging.fleetFile.toString(),output.resolve("run").toString(),"M_WT",0);
        scenario.getPopulation().getPersons().clear();scenario.getConfig().qsim().setEndTime(4500);
        // This test certifies native wait/service events. The unrelated native sharing PNG writer leaks file streams.
        scenario.getConfig().controller().setCreateGraphsInterval(0);
        scenario.getConfig().global().setNumberOfThreads(1);scenario.getConfig().qsim().setNumberOfThreads(1);
        var drt=MultiModeDrtConfigGroup.get(scenario.getConfig()).getModalElements().iterator().next();
        var controller=new Controler(scenario);DrtConfigComposer.installModules(controller);
        controller.addOverridingModule(new ModularDispatchModule(drt,List.of(tour),0,stats));
        var observations=new Observations();
        controller.addOverridingModule(new AbstractModule(){public void install(){addEventHandlerBinding().toInstance(observations);}});
        controller.run();
        assertEquals(1,observations.served.size());assertEquals(1,observations.completed);
        double serviceStart=observations.served.getFirst()-10;
        assertTrue(serviceStart>=3000,observations.served.toString());assertTrue(serviceStart<=3500,observations.served.toString());
        assertTrue(observations.waitStarts.stream().anyMatch(t->t<3000),"Must execute a positive native wait before service.");
        assertEquals(0,observations.windowViolations);assertEquals(0,observations.chainViolations);
    }
    private static final class Observations implements BasicEventHandler {
        final List<Double> waitStarts=new ArrayList<>(),served=new ArrayList<>();
        int completed,windowViolations,chainViolations;
        @Override public void reset(int iteration){waitStarts.clear();served.clear();completed=windowViolations=chainViolations=0;}
        @Override public void handleEvent(Event event) {
            if("dvrpTaskStarted".equals(event.getEventType()) && "MODULAR_FREIGHT_WAIT".equals(event.getAttributes().get("taskType")))waitStarts.add(event.getTime());
            if(event instanceof ModularTourEvent e && e.getTourId().equals("window")) {
                switch(e.getPhase()) {
                    case STOP_SERVED -> served.add(e.getTime());
                    case COMPLETED -> completed++;
                    case TIME_WINDOW_VIOLATION -> windowViolations++;
                    case CHAIN_VIOLATION -> chainViolations++;
                    default -> { }
                }
            }
        }
    }
}
