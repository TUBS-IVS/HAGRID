package hagrid.core.simulation;

import hagrid.core.util.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logging hygiene of the runner: the runtime line, the log-directory property and the
 * unknown-concept fallback. None of it touches simulation semantics.
 */
@DisplayName("SimulationRunnerUtils: logging hygiene")
class SimulationRunnerUtilsLoggingTest {

    private static final String PROP = "hagrid.log.dir";
    private String saved;

    @BeforeEach
    void saveProperty() { saved = System.getProperty(PROP); }

    @AfterEach
    void restoreProperty() {
        if (saved == null) System.clearProperty(PROP); else System.setProperty(PROP, saved);
    }

    // ---------------------------------------------------------------- runtime line

    @Test
    @DisplayName("the runtime line reads hh:mm:ss, not a literal Python format spec")
    void runtimeLineIsReadable() {
        try (LogCapture log = LogCapture.of(SimulationRunnerUtils.class)) {
            SimulationRunnerUtils.logDuration("run X", Duration.ofSeconds(3 * 3600 + 7 * 60 + 5));
            assertThat(log.infos()).contains("run X completed in 03:07:05");
        }
    }

    @Test
    @DisplayName("hours are not wrapped at 24: a 26-h run reads 26:01:00")
    void longRunKeepsItsHours() {
        try (LogCapture log = LogCapture.of(SimulationRunnerUtils.class)) {
            SimulationRunnerUtils.logDuration("run Y", Duration.ofHours(26).plusMinutes(1));
            assertThat(log.infos()).contains("run Y completed in 26:01:00");
        }
    }

    // ------------------------------------------------------------- log directory

    @Test
    @DisplayName("an explicit -Dhagrid.log.dir survives the per-run repointing")
    void explicitLogDirIsRespected(@TempDir Path tmp) {
        String explicit = tmp.resolve("explicit").toAbsolutePath().toString();
        System.setProperty(PROP, explicit);

        SimulationRunnerUtils.pointLogDirAt(tmp.resolve("run1").resolve("logs"));

        assertThat(System.getProperty(PROP)).isEqualTo(explicit);
    }

    @Test
    @DisplayName("without an explicit value every run still gets its own logs dir, run after run")
    void defaultFollowsEachRun(@TempDir Path tmp) {
        System.clearProperty(PROP);
        Path run1 = tmp.resolve("run1").resolve("logs");
        Path run2 = tmp.resolve("run2").resolve("logs");

        SimulationRunnerUtils.pointLogDirAt(run1);
        assertThat(System.getProperty(PROP)).isEqualTo(run1.toAbsolutePath().toString());
        assertThat(run1).isDirectory();

        // the second scenario of one JVM must not mistake run1's value for an explicit one
        SimulationRunnerUtils.pointLogDirAt(run2);
        assertThat(System.getProperty(PROP)).isEqualTo(run2.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("initLogging's own default is not mistaken for an explicit value")
    void initLoggingDefaultIsNotExplicit(@TempDir Path tmp) {
        System.clearProperty(PROP);
        SimulationRunnerUtils.initLogging();
        Path run = tmp.resolve("run").resolve("logs");

        SimulationRunnerUtils.pointLogDirAt(run);

        assertThat(System.getProperty(PROP)).isEqualTo(run.toAbsolutePath().toString());
    }

    // --------------------------------------------------------------- concept typo

    @Test
    @DisplayName("an unknown concept is warned about instead of silently counting as non-Lausitz")
    void unknownConceptWarns() {
        try (LogCapture log = LogCapture.of(SimulationRunnerUtils.class)) {
            try {
                SimulationRunnerUtils.parseScenario("concept=drt_basline,date=2025-05-13");
            } catch (RuntimeException ignored) {
                // a later check may still reject the spec; the warning is what is asserted
            }
            assertThat(log.warnings()).anyMatch(m -> m.contains("drt_basline"));
        }
    }

    @Test
    @DisplayName("a known concept raises no concept warning")
    void knownConceptIsQuiet() {
        try (LogCapture log = LogCapture.of(SimulationRunnerUtils.class)) {
            SimulationRunnerUtils.parseScenario("concept=basecase,date=2025-05-13");
            assertThat(log.warnings()).noneMatch(m -> m.contains("concept"));
        }
    }
}
