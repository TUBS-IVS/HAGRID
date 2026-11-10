package hagrid.core.simulation;
import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.*;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Native controller order: capture previous observations, finish/validate groups, reset handlers, drive QSim. */
class ReplanningLifecycleIntegrationTest {
    @TempDir Path output;
    @Test void eachCompletedPreviousProfileIsPublishedBeforeResetAndBothGroupsAreActuallyDriven() throws Exception {
        try(var f=new ReplanningCoordinatorTest.Fixture(3)){
            var resolved=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                    HagridConfigResolver.Overrides.of(Map.of("optimization.replanningStartIteration","2","optimization.replanningIterations","1","optimization.carriersPerGroup","2")),Map.of());
            var config=ConfigUtils.createConfig();config.controller().setLastIteration(4);config.controller().setOutputDirectory(output.toString());
            config.controller().setDumpDataAtEnd(false);config.controller().setCreateGraphsInterval(0);
            config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
            config.qsim().setStartTime(0);config.qsim().setEndTime(500);config.qsim().setNumberOfThreads(1);config.global().setNumberOfThreads(1);
            var scenario=ScenarioUtils.createScenario(config);org.matsim.core.network.NetworkUtils.setNetworkChangeEvents(scenario.getNetwork(),List.of());
            f.network.getNodes().values().forEach(scenario.getNetwork()::addNode);f.network.getLinks().values().forEach(scenario.getNetwork()::addLink);
            scenario.addScenarioElement("carriers",f.carriers);scenario.addScenarioElement("carrierVehicleTypes",f.types);
            scenario.addScenarioElement("carNetwork",scenario.getNetwork());scenario.addScenarioElement("bikeNetwork",scenario.getNetwork());
            var initial=NativeModalTransportCosts.create(scenario.getNetwork(),f.types,"car");
            for(var c:f.carriers.getCarriers().values()){
                var result=new CarrierOptimizationService().optimize(c,initial,resolved,42,1,HAGRIDRouterUtils.AlgorithmPhase.INITIAL,25200,"initial");
                assertThat(result.diagnostics()).isEmpty();c.addPlan(result.plan());c.setSelectedPlan(result.plan());
            }
            var times=new ResettingTimes(f.carriers);var drives=new Driven();var controller=new Controler(scenario);
            var seen=new HashMap<Carrier,CarrierPlan>();var expectedTimes=new HashMap<Carrier,Double>();
            controller.addOverridingModule(new org.matsim.freight.carriers.controller.CarrierModule());
            controller.addOverridingModule(new HAGRIDSimulationModule(scenario,false,4,resolved));
            controller.addOverridingModule(new AbstractModule(){public void install(){
                addTravelTimeBinding("car").toInstance(times);addEventHandlerBinding().toInstance(times);addEventHandlerBinding().toInstance(drives);
                addControlerListenerBinding().toInstance((BeforeMobsimListener)e->{
                    if(e.getIteration()==2)assertThat(completed(f.carriers)).isEqualTo(2);
                    if(e.getIteration()>=3)assertThat(completed(f.carriers)).isEqualTo(3);
                    for(var carrier:f.carriers.getCarriers().values()){
                        if(!Boolean.TRUE.equals(carrier.getAttributes().getAttribute("hadFirstReplanning")))continue;
                        var selected=carrier.getSelectedPlan();
                        if(seen.put(carrier,selected)!=selected)expectedTimes.put(carrier,10.0*e.getIteration());
                        var first=(Tour.Leg)selected.getScheduledTours().iterator().next().getTour().getTourElements().getFirst();
                        assertThat(first.getExpectedTransportTime()).isEqualTo(expectedTimes.get(carrier));
                    }
                });
            }});
            controller.run();
            assertThat(times.publishedBeforeReset).isTrue();
            assertThat(f.carriers.getCarriers().values()).allSatisfy(c->{
                boolean last=c.getId().toString().equals("lsp-02");
                assertThat(c.getAttributes().getAttribute("routingMeasurementIteration")).isEqualTo(last?2:1);
                assertThat(c.getAttributes().getAttribute("validatedRoutingCost")).isInstanceOf(Double.class);
                var first=(Tour.Leg)c.getSelectedPlan().getScheduledTours().iterator().next().getTour().getTourElements().getFirst();
                assertThat(first.getExpectedTransportTime()).isEqualTo(expectedTimes.get(c));
            });
            assertThat(f.carriers.getCarriers().values().stream().map(c->c.getAttributes().getAttribute("routingContextFingerprint")).distinct()).hasSize(2);
            assertThat(drives.iterations).contains(2,3,4);assertThat(drives.stuck).isZero();
            var rows=java.nio.file.Files.readAllLines(output.resolve("carrier-routing-outcomes.csv"));
            assertThat(rows).hasSize(4);
            for(var carrier:f.carriers.getCarriers().values()){
                var row=rows.stream().filter(line->line.startsWith(carrier.getId()+",")).findFirst().orElseThrow().split(",",-1);
                assertThat(row[14]).isEqualTo("VALIDATED_FINAL");
                assertThat(Double.parseDouble(row[21])).isEqualTo((Double)carrier.getAttributes().getAttribute("validatedRoutingCost"));
                assertThat(carrier.getSelectedPlan().getScore()).isNotNull();
                assertThat(Double.parseDouble(row[22])).isEqualTo(carrier.getSelectedPlan().getScore());
            }
        }
    }
    @Test void anUncompletedMeasurementCannotBeInventedFromTheRequestedReplanningIteration(){
        try(var f=new ReplanningCoordinatorTest.Fixture(1)){
            var config=ConfigUtils.createConfig();config.controller().setLastIteration(26);
            var scenario=ScenarioUtils.createScenario(config);
            f.network.getNodes().values().forEach(scenario.getNetwork()::addNode);
            f.network.getLinks().values().forEach(scenario.getNetwork()::addLink);
            scenario.addScenarioElement("carriers",f.carriers);
            var listener=new ValidatedCarrierReplanning(scenario,config,f.config,Map.of("car",(l,t,p,v)->10));
            var carrier=f.carriers.getCarriers().values().iterator().next();var original=carrier.getSelectedPlan();
            try {
                assertThatThrownBy(()->listener.startReplanning(25))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("completed");
                assertThat(carrier.getSelectedPlan()).isSameAs(original);
                assertThat(carrier.getAttributes().getAttribute("routingMeasurementIteration")).isNull();
            } finally {listener.notifyShutdown(new org.matsim.core.controler.events.ShutdownEvent(null,true,25));}
        }
    }
    @Test void aResetMeasurementCannotBePublishedAsTheCompletedPreviousIteration(){
        try(var f=new ReplanningCoordinatorTest.Fixture(1)){
            var config=ConfigUtils.createConfig();config.controller().setLastIteration(26);
            var scenario=ScenarioUtils.createScenario(config);
            f.network.getNodes().values().forEach(scenario.getNetwork()::addNode);
            f.network.getLinks().values().forEach(scenario.getNetwork()::addLink);
            scenario.addScenarioElement("carriers",f.carriers);
            Object listener=new ValidatedCarrierReplanning(scenario,config,f.config,Map.of("car",(l,t,p,v)->10));
            // Deliver the real controller lifecycle, including the reset barrier; baseline lacks these listeners.
            if(listener instanceof BeforeMobsimListener started)
                started.notifyBeforeMobsim(new org.matsim.core.controler.events.BeforeMobsimEvent(null,24,false));
            if(listener instanceof org.matsim.core.controler.listener.IterationEndsListener completed)
                completed.notifyIterationEnds(new org.matsim.core.controler.events.IterationEndsEvent(null,24,false));
            if(listener instanceof BeforeMobsimListener reset)
                reset.notifyBeforeMobsim(new org.matsim.core.controler.events.BeforeMobsimEvent(null,25,false));
            try {
                assertThatThrownBy(()->((ValidatedCarrierReplanning)listener).startReplanning(25))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("completed");
            } finally {((ValidatedCarrierReplanning)listener).notifyShutdown(new org.matsim.core.controler.events.ShutdownEvent(null,true,25));}
        }
    }
    private static long completed(Carriers carriers){return carriers.getCarriers().values().stream().filter(c->Boolean.TRUE.equals(c.getAttributes().getAttribute("hadFirstReplanning"))).count();}
    static final class ResettingTimes implements TravelTime,org.matsim.core.events.handler.BasicEventHandler {
        double seconds=10;final Carriers carriers;boolean publishedBeforeReset;
        ResettingTimes(Carriers carriers){this.carriers=carriers;}
        public double getLinkTravelTime(org.matsim.api.core.v01.network.Link l,double t,org.matsim.api.core.v01.population.Person p,org.matsim.vehicles.Vehicle v){return seconds;}
        public void handleEvent(org.matsim.api.core.v01.events.Event event) {}
        public void reset(int iteration){if(iteration==2)publishedBeforeReset=completed(carriers)==2;seconds=10*(iteration+1);}
    }
    static final class Driven implements LinkEnterEventHandler,PersonStuckEventHandler {
        int iteration;int stuck;final Set<Integer> iterations=new HashSet<>();
        public void reset(int iteration){this.iteration=iteration;}
        public void handleEvent(LinkEnterEvent event){iterations.add(iteration);}
        public void handleEvent(PersonStuckEvent event){stuck++;}
    }
}
