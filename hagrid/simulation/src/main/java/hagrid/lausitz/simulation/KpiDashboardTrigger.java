package hagrid.lausitz.simulation;

import hagrid.core.HagridPaths;
import hagrid.core.simulation.HAGRIDSimulationConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Invokes the Python KPI-dashboard builder ({@code <repo>/analysis/lausitz/kpi/build_kpis.py})
 * for a finished run. Blocking by design (spec 3.4: the run is not "done" until the dashboard
 * exists or the build timed out) and failure-tolerant by design: a dashboard problem must never
 * kill or fail a multi-hour MATSim run.
 *
 * <p>Hardening 2026-11-02 (IVS100: 5 of 21 in-run builds lost to a native crash of the conda
 * Python in Expat, unnoticed because nothing logged the exit code): the exit code of a failed
 * build is logged, Python runs with {@code -X faulthandler} so a native crash prints where it
 * happened, the log reader is joined so the last lines are not lost, and a build that exits
 * non-zero is tried once more. A timeout or a build that cannot start is not retried.
 */
public final class KpiDashboardTrigger {

    private static final Logger LOG = LogManager.getLogger(KpiDashboardTrigger.class);
    static final long TIMEOUT_MINUTES = 30;
    /** One retry: the crash it targets is sporadic, a real script error fails twice in seconds. */
    static final int MAX_ATTEMPTS = 2;
    /** {@link #runOnce} result when the build hit its timeout. */
    static final int TIMED_OUT = Integer.MIN_VALUE;
    /** {@link #runOnce} result when the process could not be started. */
    static final int NOT_STARTED = Integer.MIN_VALUE + 1;
    private static final long READER_JOIN_MILLIS = 10_000;

    private KpiDashboardTrigger() {
    }

    static List<String> buildCommand(Path script, Path runDir) {
        return List.of("python", "-X", "faulthandler", "-u", script.toString(), "--run-dir", runDir.toString());
    }

    private static final Path SCRIPT_REL = Path.of("analysis", "lausitz", "kpi", "build_kpis.py");

    /** The KPI builder lives at repo level: {@code <repo>/analysis/lausitz/kpi/build_kpis.py}.
     *  The repo root is the nearest ancestor of the module that really holds that script (the
     *  script is its own marker), so the module can sit at any depth. Without a hit the historical
     *  guess -- TWO levels up, the {@code <repo>/hagrid/simulation} layout since 2026-09-21 -- is
     *  returned, so the caller's "script not found" message still names a sensible path. */
    static Path scriptFor(Path pipelineRoot) {
        Path start = pipelineRoot.toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(SCRIPT_REL);
            if (java.nio.file.Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return start.getParent().getParent().resolve(SCRIPT_REL);
    }

    public static boolean runProcess(List<String> command, Path workDir, long timeoutMinutes) {
        return runOnce(command, workDir, timeoutMinutes, line -> LOG.info("[kpi-dashboard] {}", line)) == 0;
    }

    /**
     * Runs the command once and returns its exit code, or {@link #TIMED_OUT} / {@link #NOT_STARTED}.
     * Every output line goes to {@code sink}; the reader is joined before returning, so the last
     * lines of a crashing build are logged too. A non-zero exit code is logged (with its hex form,
     * which is how Windows reports a native crash, e.g. 0xC0000005).
     */
    static int runOnce(List<String> command, Path workDir, long timeoutMinutes, Consumer<String> sink) {
        Thread reader = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            Process p = pb.start();
            reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sink.accept(line);
                    }
                } catch (IOException ignored) {
                    // stream closes when the process ends or is destroyed
                }
            }, "kpi-dashboard-log");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                joinQuietly(reader);
                LOG.warn("KPI dashboard build timed out after {} min", timeoutMinutes);
                return TIMED_OUT;
            }
            joinQuietly(reader);
            int code = p.exitValue();
            if (code != 0) {
                LOG.warn("KPI dashboard build exited with code {} (0x{})", code,
                        Integer.toHexString(code).toUpperCase(java.util.Locale.ROOT));
            }
            return code;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Could not start the KPI dashboard build: {}", e.toString());
            return NOT_STARTED;
        }
    }

    private static void joinQuietly(Thread reader) throws InterruptedException {
        reader.join(READER_JOIN_MILLIS);
    }

    /**
     * Runs {@code attempt} until it returns 0, at most {@code maxAttempts} times. Only a real
     * non-zero exit code is retried - a timeout already cost the full budget and a build that
     * cannot start will not start the second time either.
     *
     * @return {@code true} if an attempt returned 0
     */
    static boolean runWithRetry(IntSupplier attempt, int maxAttempts) {
        for (int i = 1; i <= maxAttempts; i++) {
            int code = attempt.getAsInt();
            if (code == 0) {
                if (i > 1) {
                    LOG.info("KPI dashboard build succeeded on attempt {}", i);
                }
                return true;
            }
            if (code == TIMED_OUT || code == NOT_STARTED) {
                return false;
            }
            if (i < maxAttempts) {
                LOG.warn("KPI dashboard build failed (exit {}) - retrying once", code);
            }
        }
        return false;
    }

    /** Never throws. No-op when the scenario option {@code kpiDashboard} is false. */
    public static void triggerSafely(HAGRIDSimulationConfig cfg) {
        try {
            if (!cfg.isKpiDashboardEnabled()) {
                return;
            }
            Path pipelineRoot = new HagridPaths().getPipelineRoot().toAbsolutePath().normalize();
            Path script = scriptFor(pipelineRoot);
            Path runDir = cfg.getOutputDirectory().toAbsolutePath().normalize();
            List<String> cmd = buildCommand(script, runDir);
            LOG.info("kpiDashboard=true -> building KPI dashboard: {}", String.join(" ", cmd));
            long t0 = System.currentTimeMillis();
            boolean ok = runWithRetry(() -> runOnce(cmd, pipelineRoot, TIMEOUT_MINUTES,
                    line -> LOG.info("[kpi-dashboard] {}", line)), MAX_ATTEMPTS);
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
                    + "<repo>/analysis/lausitz/kpi/build_kpis.py --run-dir {} ({})",
                    dir, e.toString());
        }
    }
}
