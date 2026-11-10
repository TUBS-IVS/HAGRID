package hagrid.core.simulation;

import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.*;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real controller output policy: reduce intermediate I/O without losing final artifacts. */
class FreightOutputIntervalsTest {
    @TempDir Path output;

    @Test void unspecifiedOutputIntervalsPreserveNativeXmlSettings() {
        var launch=SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13");
        var config=ConfigUtils.createConfig();
        config.controller().setWriteEventsInterval(7); config.controller().setWritePlansInterval(9);
        FreightScenarioConfiguration.apply(config,launch);
        assertThat(config.controller().getWriteEventsInterval()).isEqualTo(7);
        assertThat(config.controller().getWritePlansInterval()).isEqualTo(9);
    }

    @Test void explicitLaunchIntervalsReachTheActualScenarioConfiguration() {
        var launch=SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,eventsEvery=2,plansEvery=4,freightHistogramEvery=3");
        var config=ConfigUtils.createConfig();
        FreightScenarioConfiguration.apply(config,launch);
        assertThat(config.controller().getWriteEventsInterval()).isEqualTo(2);
        assertThat(config.controller().getWritePlansInterval()).isEqualTo(4);
        assertThat(launch.getResolvedRoutingConfig().effectiveValues())
                .containsEntry("results.freightHistogramWriteInterval","3");
    }

    @Test void canonicalRunOverridesHaveTheSameOutputPolicy() {
        var resolved=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("results.eventsWriteInterval","2",
                        "results.plansWriteInterval","4","results.freightHistogramWriteInterval","3")),Map.of());
        assertThat(resolved.effectiveValues()).containsEntry("results.eventsWriteInterval","2")
                .containsEntry("results.plansWriteInterval","4").containsEntry("results.freightHistogramWriteInterval","3");
    }

    @Test void zeroNegativeOrOverflowingIntervalsAreRejectedBeforeLaunchingMatsim() {
        for(String alias:List.of("eventsEvery","plansEvery","freightHistogramEvery")) {
            for(String invalid:List.of("0","-1","2147483648"))
                assertThatIllegalArgumentException().isThrownBy(() -> SimulationRunnerUtils.parseScenario(
                        "concept=basecase,date=2025-05-13,"+alias+"="+invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new ResolvedHagridConfig.Results(false,
                Optional.empty(),OptionalInt.of(0),OptionalInt.empty(),1));
    }
    @Test void nativeQsimKeepsFinalEventsPlansAndHistogramsOffTheRegularInterval() throws Exception {
        try(var f=new ReplanningCoordinatorTest.Fixture(1)) {
            // Hannover's distance tariff supports the native travel-time changes between measured bins.
            f.types.getVehicleTypes().values().forEach(type -> type.getCostInformation().setCostsPerSecond(0.0));
            var launch=SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,maxIter=3,jspritIter=1,zoneCaching=false,eventsEvery=2,plansEvery=2,freightHistogramEvery=2");
            var demand=new HagridConfig();demand.setStudyArea(launch.getStudyArea());
            demand.setConcept(launch.getConcept());demand.setTag(launch.getTag());demand.setSimulationDate(launch.getSimulationDate());
            var resolved=SimulationRoutingConfigAdapter.resolve(demand,launch,HagridConfigResolver.Overrides.of(
                    Map.of("optimization.replanningStartIteration","2","optimization.carriersPerGroup","1")));
            var config=ConfigUtils.createConfig();FreightScenarioConfiguration.apply(config,launch);
            config.controller().setOutputDirectory(output.toString());config.controller().setRunId(null);
            config.controller().setCreateGraphsInterval(0);config.controller().setWriteEventsUntilIteration(0);
            config.controller().setWritePlansUntilIteration(0);
            config.qsim().setStartTime(0);config.qsim().setEndTime(500);
            config.qsim().setNumberOfThreads(1);config.global().setNumberOfThreads(1);
            var scenario=ScenarioUtils.createScenario(config);
            org.matsim.core.network.NetworkUtils.setNetworkChangeEvents(scenario.getNetwork(),List.of());
            f.network.getNodes().values().forEach(scenario.getNetwork()::addNode);
            f.network.getLinks().values().forEach(scenario.getNetwork()::addLink);
            scenario.addScenarioElement("carriers",f.carriers);scenario.addScenarioElement("carrierVehicleTypes",f.types);
            scenario.addScenarioElement("carNetwork",scenario.getNetwork());scenario.addScenarioElement("bikeNetwork",scenario.getNetwork());
            var costs=NativeModalTransportCosts.create(scenario.getNetwork(),f.types,"car");
            for(var carrier:f.carriers.getCarriers().values()) {
                var result=new CarrierOptimizationService().optimize(carrier,costs,resolved,42,1,
                        HAGRIDRouterUtils.AlgorithmPhase.INITIAL,25200,"output-fixture");
                assertThat(result.diagnostics()).isEmpty();carrier.addPlan(result.plan());carrier.setSelectedPlan(result.plan());
            }
            var controller=new Controler(scenario);
            controller.addOverridingModule(new org.matsim.freight.carriers.controller.CarrierModule());
            controller.addOverridingModule(new HAGRIDSimulationModule(scenario,false,3,resolved));
            controller.run();
            assertThat(output.resolve("ITERS/it.1/1.events.xml.gz")).doesNotExist();
            assertThat(output.resolve("ITERS/it.2/2.events.xml.gz")).exists();
            assertThat(output.resolve("ITERS/it.3/3.events.xml.gz")).exists();
            assertThat(output.resolve("ITERS/it.1/1.plans.xml.gz")).doesNotExist();
            assertThat(output.resolve("ITERS/it.2/2.plans.xml.gz")).exists();
            assertThat(output.resolve("output_plans.xml.gz")).exists();
            for(String histogram:List.of("freight","withoutFreight")) {
                assertThat(output.resolve("ITERS/it.1/1.legHistogram_"+histogram+".png")).doesNotExist();
                assertThat(output.resolve("ITERS/it.2/2.legHistogram_"+histogram+".png")).exists();
                assertThat(output.resolve("ITERS/it.3/3.legHistogram_"+histogram+".png")).exists();
            }
            assertThat(output.resolve("carrier-routing-outcomes.csv")).exists();
        }
    }
}
