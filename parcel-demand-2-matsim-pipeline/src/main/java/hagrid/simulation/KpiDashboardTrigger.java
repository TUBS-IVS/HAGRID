package hagrid.simulation;

import hagrid.HagridPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Invokes the Python KPI-dashboard builder ({@code analysis/kpi/build_kpis.py}) for a finished
 * run. Blocking by design (spec 3.4: the run is not "done" until the dashboard exists or the
 * build timed out) and failure-tolerant by design: a dashboard problem must never kill or fail
 * a multi-hour MATSim run.
 */
public final class KpiDashboardTrigger {

    private static final Logger LOG = LogManager.getLogger(KpiDashboardTrigger.class);
    static final long TIMEOUT_MINUTES = 30;

    private KpiDashboardTrigger() {
    }

    static List<String> buildCommand(Path script, Path runDir) {
        return List.of("python", "-u", script.toString(), "--run-dir", runDir.toString());
    }

    static boolean runProcess(List<String> command, Path workDir, long timeoutMinutes) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            Process p = pb.start();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        LOG.info("[kpi-dashboard] {}", line);
                    }
                } catch (IOException ignored) {
                    // stream closes when the process ends or is destroyed
                }
            }, "kpi-dashboard-log");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                LOG.warn("KPI dashboard build timed out after {} min", timeoutMinutes);
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Could not start the KPI dashboard build: {}", e.toString());
            return false;
        }
    }

    /** Never throws. No-op when the scenario option {@code kpiDashboard} is false. */
    public static void triggerSafely(HAGRIDSimulationConfig cfg) {
        try {
            if (!cfg.isKpiDashboardEnabled()) {
                return;
            }
            Path pipelineRoot = new HagridPaths().getPipelineRoot().toAbsolutePath().normalize();
            Path script = pipelineRoot.resolve("analysis").resolve("kpi").resolve("build_kpis.py");
            Path runDir = cfg.getOutputDirectory().toAbsolutePath().normalize();
            List<String> cmd = buildCommand(script, runDir);
            LOG.info("kpiDashboard=true -> building KPI dashboard: {}", String.join(" ", cmd));
            long t0 = System.currentTimeMillis();
            boolean ok = runProcess(cmd, pipelineRoot, TIMEOUT_MINUTES);
            if (ok) {
                LOG.info("KPI dashboard written to {} ({} s)", runDir.resolve("analysis"),
                        (System.currentTimeMillis() - t0) / 1000);
            } else {
                LOG.warn("KPI dashboard build FAILED - run it manually:\n  python -u {} --run-dir {}",
                        script, runDir);
            }
        } catch (Exception e) {
            String dir;
            try {
                dir = cfg.getOutputDirectoryAsString();
            } catch (Exception ignored) {
                dir = "<unknown>";
            }
            LOG.warn("KPI dashboard trigger failed - run manually: python -u "
                    + "<pipeline>/analysis/kpi/build_kpis.py --run-dir {} ({})",
                    dir, e.toString());
        }
    }
}
