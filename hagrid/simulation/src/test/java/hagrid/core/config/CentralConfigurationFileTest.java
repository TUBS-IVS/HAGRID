package hagrid.core.config;

import hagrid.core.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class CentralConfigurationFileTest {
    @TempDir Path temp;
    private Path file(String body) throws Exception {
        return Files.writeString(temp.resolve("hagrid.properties"),body);
    }
    private hagrid.core.simulation.HAGRIDSimulationConfig launch(Path file,String cli) {
        return SimulationRunnerUtils.parseScenario("config="+file.toString().replace('\\','/')+cli);
    }
    @Test void sectionOptionsValidateInFileAndRespectCliPrecedence() throws Exception {
        Path valid=file("run.concept=basecase\nrun.date=2025-05-13\nrouting.sectionMaxSpanMeters=100\nrouting.haltCatalogFile=halts.json\nrouting.sectionFastRegret=true\nresources.internalSolverThreads=2\n");
        var resolved=launch(valid,",routing.sectionFastRegret=false,solverThreads=1").getResolvedRoutingConfig();
        assertThat(resolved.sections().fastRegret()).isFalse();assertThat(resolved.resources().internalSolverThreads()).isEqualTo(1);
        assertThat(resolved.provenance()).containsEntry("routing.sectionFastRegret",ResolvedHagridConfig.Source.CLI).containsEntry("resources.internalSolverThreads",ResolvedHagridConfig.Source.CLI);
        assertThatThrownBy(()->launch(file("run.concept=basecase\nrun.date=2025-05-13\nrouting.sectionFastRegret=yes\n"),"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->launch(file("run.concept=basecase\nrun.date=2025-05-13\nrouting.sectionFastRegret=true\n"),"").getResolvedRoutingConfig()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oneFileConfiguresRunTourBufferRoutingAndDrt() throws Exception {
        var cfg=launch(file("run.concept=basecase\nrun.date=2025-05-13\ntourRules.maxRouteDurationSeconds=14400\n"
                +"tourRules.vehicleAvailabilityBufferSeconds=600\nrouting.maximumCacheEntries=77\n"
                +"drt.stopDurationSeconds=90\nscoring.overtimePenalty=9\n"),"");
        var r=cfg.getResolvedRoutingConfig();
        assertThat(r.tourRules().maxRouteDurationSeconds()).isEqualTo(14400);
        assertThat(r.tourRules().vehicleAvailabilityBufferSeconds()).isEqualTo(600);
        assertThat(r.routing().maximumCacheEntries()).isEqualTo(77);
        assertThat(r.effectiveValues()).containsEntry("drt.stopDurationSeconds","90");
        assertThat(r.scoring().maxWorkDurationSeconds()).isEqualTo(14400);
        assertThat(r.scoring().overtimePenalty()).isEqualTo(9);
        assertThat(r.provenance()).containsEntry("tourRules.maxRouteDurationSeconds",ResolvedHagridConfig.Source.RUN);
    }
    @Test void explicitCliOverridesFileWithoutRewritingIt() throws Exception {
        Path f=file("run.concept=basecase\nrun.date=2025-05-13\ntourRules.maxRouteDurationSeconds=14400\n");
        String original=Files.readString(f);
        var r=launch(f,",solverMaxRouteDuration=10800").getResolvedRoutingConfig();
        assertThat(r.tourRules().maxRouteDurationSeconds()).isEqualTo(10800);
        assertThat(r.provenance()).containsEntry("tourRules.maxRouteDurationSeconds",ResolvedHagridConfig.Source.CLI);
        assertThat(Files.readString(f)).isEqualTo(original);
    }
    @Test void unknownFileSettingFailsInsteadOfUsingDefaults() throws Exception {
        Path f=file("run.concept=basecase\nrun.date=2025-05-13\ndrt.stopDuratonSeconds=90\n");
        assertThatThrownBy(()->launch(f,"")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stopDuratonSeconds");
    }
    @Test void duplicateFileSettingIsRejected() throws Exception {
        Path f=file("run.concept=basecase\nrun.concept=white_label\nrun.date=2025-05-13\n");
        assertThatThrownBy(()->launch(f,"")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("run.concept");
    }
    @Test void conflictingAliasesInOneFileAreRejected() throws Exception {
        Path f=file("run.concept=basecase\nrun.date=2025-05-13\ntourRules.maxRouteDurationSeconds=14400\nsolverMaxRouteDuration=18000\n");
        assertThatThrownBy(()->launch(f,"")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxRouteDuration");
    }
    @Test void fileIsCapturedOnceBeforePublication() throws Exception {
        Path f=file("run.concept=basecase\nrun.date=2025-05-13\ntourRules.maxRouteDurationSeconds=14400\n");
        var cfg=launch(f,"");
        Files.writeString(f,"invalid later replacement");
        assertThat(cfg.getResolvedRoutingConfig().tourRules().maxRouteDurationSeconds()).isEqualTo(14400);
    }
    @Test void canonicalCliOptionsWorkWithoutAFile() {
        var cfg=SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13,tourRules.maxRouteDurationSeconds=10800,tourRules.vehicleAvailabilityBufferSeconds=900");
        assertThat(cfg.getResolvedRoutingConfig().tourRules().maxRouteDurationSeconds()).isEqualTo(10800);
        assertThat(cfg.getResolvedRoutingConfig().tourRules().vehicleAvailabilityBufferSeconds()).isEqualTo(900);
    }
    @Test void fileConfiguresExistingModularAndSharedUseOptions() throws Exception {
        var cfg=launch(file("run.concept=drt_modular\nrun.date=2025-05-13\nrun.tag=central\ndrt.fleetSize=12\n"
                +"modular.idleThreshold=0.25\nmodular.maxConcurrentFreight=3\nmodular.budgetMode=selfref\n"
                +"modular.openDepots=wittichenau,hoy_sued\nmodular.freightWindows=07:00-13:00,16:45-18:30\n"),"");
        assertThat(cfg.getFleetSize()).isEqualTo(12);
        assertThat(cfg.getIdleThreshold()).isEqualTo(0.25);
        assertThat(cfg.getMaxConcurrentFreight()).isEqualTo(3);
        assertThat(cfg.getOpenDepots()).containsExactly("wittichenau","hoy_sued");
        assertThat(cfg.getFreightWindows()).hasSize(2);
    }
}
