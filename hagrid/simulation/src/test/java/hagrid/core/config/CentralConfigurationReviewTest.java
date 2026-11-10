package hagrid.core.config;

import hagrid.core.simulation.SimulationRunnerUtils;
import hagrid.lausitz.drt.DrtInputsFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class CentralConfigurationReviewTest {
    @TempDir Path temp;
    private Path file(String content) throws Exception { return Files.writeString(temp.resolve("hagrid.properties"),content); }
    private String modular() { return "concept=drt_modular,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=review"; }
    @Test void omittedModularCapUsesTheSameSevenHourRule() {
        var cfg=SimulationRunnerUtils.parseScenario(modular());
        assertThat(cfg.getMaxTourDurationSeconds()).isEqualTo(25200);
        assertThat(cfg.getResolvedRoutingConfig().tourRules().maxRouteDurationSeconds()).isEqualTo(25200);
        assertThat(cfg.getResolvedRoutingConfig().scoring().maxWorkDurationSeconds()).isEqualTo(25200);
    }
    @Test void explicitCommonCliCapOverridesLowerPriorityModularFileAlias() throws Exception {
        Path f=file("modular.maxTourDurationSeconds=12600\n");
        var cfg=SimulationRunnerUtils.parseScenario(modular()+",config="+f.toString().replace('\\','/')+",solverMaxRouteDuration=14400");
        assertThat(cfg.getResolvedRoutingConfig().tourRules().maxRouteDurationSeconds()).isEqualTo(14400);
    }
    @Test void explicitModularCliAliasOverridesLowerPriorityCommonFileCap() throws Exception {
        Path f=file("tourRules.maxRouteDurationSeconds=14400\n");
        var cfg=SimulationRunnerUtils.parseScenario(modular()+",config="+f.toString().replace('\\','/')+",maxTourDuration=12600");
        assertThat(cfg.getResolvedRoutingConfig().tourRules().maxRouteDurationSeconds()).isEqualTo(12600);
    }
    @Test void acceptedCanonicalDemandCliActuallyChangesProviderRates() {
        var cfg=SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,providers.deliveryRates.dhl=0");
        assertThat(cfg.getResolvedRoutingConfig().demand().deliveryRates()).containsEntry("dhl",0);
    }
    @Test void canonicalListCliReachesTheActualFleet() {
        var cfg=SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,fleet.activeSizes=m,bike");
        assertThat(cfg.getResolvedRoutingConfig().fleet().activeSizes()).containsExactly("m","bike");
    }
    @Test void canonicalJspritCliOverridesTheFileSearchBudget() throws Exception {
        Path f=file("simulation.jspritIterations=5\n");
        var cfg=SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,config="+f.toString().replace('\\','/')+",simulation.jspritIterations=8");
        assertThat(cfg.getJspritIterations()).isEqualTo(8);
        assertThat(cfg.getResolvedRoutingConfig().optimization().replanningIterations().orElseThrow()).isEqualTo(8);
    }
    @Test void baselineSeatsDoNotValidateAnInactiveParcelSubmissionWindow() {
        var cfg=SimulationRunnerUtils.parseScenario("concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=review,tourRules.deliveryWindowStartSeconds=39600");
        assertThat(DrtInputsFingerprint.expectedCapacity(cfg)).isEqualTo(10);
    }
    @Test void invalidUtf8IsRejectedBeforeAnyConfigurationIsApplied() throws Exception {
        Path f=temp.resolve("bad.properties");
        Files.write(f,new byte[]{'r','u','n','.','t','a','g','=',(byte)0xff,'\n'});
        assertThatThrownBy(()->HagridConfigFile.load(f)).isInstanceOf(java.io.UncheckedIOException.class);
    }
    @Test void baselineFiveSeatsDoNotDependOnUnusedSharedSeats() throws Exception {
        var cfg=SimulationRunnerUtils.parseScenario("concept=drt_baseline,date=2025-05-13,tag=review,drt.baseSeats=5");
        assertThat(DrtInputsFingerprint.expectedCapacity(cfg)).isEqualTo(5);
        Path fingerprint=temp.resolve("fingerprint.properties");
        DrtInputsFingerprint.write(cfg,fingerprint);
        assertThat(DrtInputsFingerprint.mismatches(cfg,fingerprint)).isEmpty();
    }
    @Test void relativeSupplyInputUsesTheConfigurationDirectory() throws Exception {
        var cfg=new hagrid.core.HagridConfig();
        cfg.loadConfiguration(file("supply.baselineCarriersFile=data/supply.xml\n"));
        assertThat(Path.of(cfg.supply().getBaselineCarriersFile())).isEqualTo(temp.resolve("data/supply.xml"));
    }
    @Test void directFileLoadingRejectsInvalidLaunchNumbers() throws Exception {
        Path f=file("simulation.maxIterations=oops\n");
        assertThatThrownBy(()->new hagrid.core.HagridConfig().loadConfiguration(f)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void removedAdaptivePolicyIsRejectedWhenTheFileIsCaptured() throws Exception {
        Path f=file("optimization.adaptiveSearchEnabled=true\n");
        assertThatThrownBy(()->HagridConfigFile.load(f)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("optimization.adaptiveSearchEnabled").hasMessageContaining("removed");
    }
    @Test void activeSharedWindowIsValidatedWhenTheSnapshotIsResolved() {
        assertThatThrownBy(()->SimulationRunnerUtils.parseScenario("concept=drt_shareduse,date=2025-05-13,tag=review,tourRules.deliveryWindowStartSeconds=39600").getResolvedRoutingConfig())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("submission");
    }
    @Test void invalidPipelineConfigurationCreatesNoOutputOrCacheDirectories() throws Exception {
        Path f=file("simulation.maxIterations=oops\n");
        Path pipeline=temp.resolve("pipeline");
        var scenario=hagrid.hannover.pipeline.ScenarioConfig.builder().configurationFile(f).build();
        assertThatThrownBy(()->new hagrid.hannover.pipeline.ScenarioRunner(scenario,pipeline)
                .runSingleScenario("basecase",java.time.LocalDate.of(2025,5,13))).isInstanceOf(IllegalArgumentException.class);
        assertThat(pipeline).doesNotExist();
    }
}
