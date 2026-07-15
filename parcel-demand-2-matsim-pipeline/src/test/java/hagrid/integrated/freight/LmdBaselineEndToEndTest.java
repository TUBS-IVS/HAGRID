package hagrid.integrated.freight;

import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.simulation.KpiDashboardTrigger;
import hagrid.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LMD baseline end-to-end (real staged data)")
class LmdBaselineEndToEndTest {

    @Test
    @DisplayName("a maxIter=0 LMD_BASELINE run boots, routes carriers, and produces freight output")
    void bootsOnRealData() throws Exception {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                "concept=LMD_BASELINE,date=2025-05-13,maxIter=0,jspritIter=1,tag=SMOKE");

        // Skip cleanly when the real Lausitz inputs are not staged on this machine.
        Assumptions.assumeTrue(Files.exists(Path.of(cfg.getLmdDemandShapefile())),
                "PANDA demand not staged — skipping e2e");
        Assumptions.assumeTrue(Files.exists(Path.of(cfg.getLmdDepotCsv())),
                "LMD depot CSV not staged — skipping e2e");

        cfg.validateInputFiles();
        SimulationRunnerUtils.runSimulation(cfg);

        // routed carrier XML + a MATSim freight output dir were produced
        assertThat(Files.exists(Path.of(cfg.getLmdCarriersRouted()))).isTrue();
        assertThat(Files.exists(cfg.getOutputDirectory())).isTrue();

        // run_metadata.json is emitted by the real runSimulation() glue (1e Task 1 review fix C):
        // this is the one e2e test that drives the production SimulationRunnerUtils.runSimulation
        // call site directly, so it covers the writeRunMetadataSafely() wiring itself.
        assertThat(Files.exists(cfg.getOutputDirectory().resolve(
                hagrid.simulation.RunMetadataWriter.FILE_NAME)))
                .as("run_metadata.json missing from LMD baseline MATSim output dir").isTrue();

        // v2 Plan B: the auto-trigger must have produced the Python KPI dashboard.
        // Skip (not fail) on machines without python on PATH — the trigger itself is
        // failure-tolerant, so only the assertion needs the guard.
        boolean pythonAvailable = KpiDashboardTrigger.runProcess(
                List.of("python", "--version"), null, 1);
        Assumptions.assumeTrue(pythonAvailable, "python not on PATH");
        assertThat(Files.exists(
                cfg.getOutputDirectory().resolve("analysis").resolve("kpi_dashboard.html")))
                .as("kpiDashboard=true (default) must produce analysis/kpi_dashboard.html")
                .isTrue();
    }
}
