package hagrid.core.simulation;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.*;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.network.*;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import static org.junit.jupiter.api.Assertions.*;

class CompactNetworkChangeEventsQSimTest {
    @TempDir Path output;
    @Test void congestedDayWithSpeedCapacityAndLaneChangesKeepsExactVehicleTrace() {
        var nativeTrace=run(false);var compactTrace=run(true);
        assertEquals(nativeTrace,compactTrace);
        assertFalse(nativeTrace.isEmpty());
    }
    private List<String> run(boolean compact) {
        var config=ConfigUtils.createConfig();config.network().setTimeVariantNetwork(true);
        config.global().setRandomSeed(42);config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1);config.eventsManager().setNumberOfThreads(1);
        config.qsim().setStartTime(0);config.qsim().setEndTime(7200);config.qsim().setRemoveStuckVehicles(false);
        config.controller().setLastIteration(0);config.controller().setCreateGraphsInterval(0);
        config.controller().setWriteEventsInterval(0);config.controller().setDumpDataAtEnd(false);
        config.controller().setOutputDirectory(output.resolve(Boolean.toString(compact)).toString());
        config.scoring().addActivityParams(new org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams("home").setTypicalDuration(3600));
        config.scoring().addActivityParams(new org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams("work").setTypicalDuration(3600));
        var scenario=ScenarioUtils.createScenario(config);var n=scenario.getNetwork();
        for(int i=0;i<5;i++)NetworkUtils.createAndAddNode(n,Id.createNodeId("n"+i),new Coord(100*i,0));
        for(int i=0;i<4;i++)NetworkUtils.createAndAddLink(n,Id.createLinkId("l"+i),n.getNodes().get(Id.createNodeId("n"+i)),n.getNodes().get(Id.createNodeId("n"+(i+1))),100,10,360,1);
        NetworkUtils.createAndAddLink(n,Id.createLinkId("return"),n.getNodes().get(Id.createNodeId("n4")),n.getNodes().get(Id.createNodeId("n0")),400,10,360,1);
        var events=new ArrayList<NetworkChangeEvent>();
        for(int t:new int[]{100,400,700,900})for(int link:new int[]{1,2}) {
            var e=new NetworkChangeEvent(t);e.addLink(n.getLinks().get(Id.createLinkId("l"+link)));
            e.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,6));
            e.setFlowCapacityChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,t<400?.05:.1));
            e.setLanesChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,t<700?1:2));
            events.add(e);
        }
        NetworkUtils.setNetworkChangeEvents(n,events);
        if(compact)assertEquals(4,CompactNetworkChangeEvents.compact(n).outputEvents());
        var p=scenario.getPopulation();var f=p.getFactory();
        for(int i=0;i<120;i++) {
            var person=f.createPerson(Id.createPersonId("p"+i));var plan=f.createPlan();
            var home=f.createActivityFromLinkId("home",Id.createLinkId("l0"));home.setEndTime(0);plan.addActivity(home);
            var leg=f.createLeg("car");leg.setRoute(RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("l0"),List.of(Id.createLinkId("l1"),Id.createLinkId("l2")),Id.createLinkId("l3")));plan.addLeg(leg);
            plan.addActivity(f.createActivityFromLinkId("work",Id.createLinkId("l3")));person.addPlan(plan);p.addPerson(person);
        }
        var trace=new ArrayList<String>();var arrivals=new int[1];var delayed=new boolean[1];
        BasicEventHandler recorder=new BasicEventHandler(){
            public void handleEvent(Event e){
                if(e instanceof PersonStuckEvent)fail("Vehicle became stuck");
                if(e instanceof PersonArrivalEvent){arrivals[0]++;if(e.getTime()>500)delayed[0]=true;}
                if(e instanceof LinkEnterEvent||e instanceof LinkLeaveEvent||e instanceof PersonArrivalEvent||e instanceof VehicleEntersTrafficEvent)
                    trace.add(new TreeMap<>(e.getAttributes()).toString());
            }
        };
        var controller=new Controler(scenario);controller.addOverridingModule(new AbstractModule(){
            public void install(){addEventHandlerBinding().toInstance(recorder);}
        });controller.run();
        assertEquals(120,arrivals[0]);assertTrue(delayed[0],"Fixture must actually queue vehicles");return trace;
    }
}
