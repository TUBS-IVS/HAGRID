package hagrid.core.simulation;

import hagrid.core.HagridPaths;
import hagrid.core.util.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static hagrid.core.simulation.OutputPathGuard.measure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OutputPathGuardTest {

    private static final String RUN_ID = "DRT_SHAREDUSE_13052025_x";
    private static final String DIR_SUFFIX = RUN_ID + "_iter250_jsprit100";

    @TempDir
    Path tmp;

    /** Absolute-looking dir whose GeoPackage path has exactly {@code target} characters. */
    private static String dirFor(String runId, String dirName, int target) {
        int fixed = 1 + runId.length() + OutputPathGuard.DRT_ZONAL_GPKG_SUFFIX.length();
        int dirLen = target - fixed;
        int prefixLen = dirLen - 1 - dirName.length();
        return "C:" + "p".repeat(prefixLen - 2) + "\\" + dirName;
    }

    /** The Windows branch explicitly, so these tests mean the same on every OS. */
    private static void enforceFor(String dir, boolean drt) {
        OutputPathGuard.enforce(dir, RUN_ID, 250, drt, true);
    }

    @Test void nativeFileAt251Passes() {
        String dir251 = dirFor(RUN_ID, DIR_SUFFIX, 251);
        assertThat(measure(dir251, RUN_ID, 250).nativeFileLength()).isEqualTo(251);
        assertThatCode(() -> enforceFor(dir251, true)).doesNotThrowAnyException();
    }

    @Test void nativeFileAt252Fails() {
        String dir252 = dirFor(RUN_ID, DIR_SUFFIX, 252);
        assertThat(measure(dir252, RUN_ID, 250).nativeFileLength()).isEqualTo(252);
        assertThatThrownBy(() -> enforceFor(dir252, true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("251").hasMessageContaining("252").hasMessageContaining("HAGRID_OUTPUT_ROOT")
                .hasMessageContaining("2.51");
    }

    @Test void nonDrtRunIgnoresNativeLimit() {
        assertThatCode(() -> enforceFor(dirFor(RUN_ID, DIR_SUFFIX, 252), false)).doesNotThrowAnyException();
    }

    /** Final review M2: off Windows the same violation is a WARN with the same numbers, not an abort. */
    @Test void nonWindowsOnlyWarnsWithTheSameNumbers() {
        String dir252 = dirFor(RUN_ID, DIR_SUFFIX, 252);
        try (LogCapture log = LogCapture.of(OutputPathGuard.class)) {
            assertThatCode(() -> OutputPathGuard.enforce(dir252, RUN_ID, 250, true, false))
                    .doesNotThrowAnyException();
            assertThat(log.warnings()).anyMatch(m -> m.contains("252") && m.contains("251")
                    && m.contains("not enforced on this OS"));
        }
    }

    @Test void longestProjectedLengthCoversIterFileAndDashboard() {
        String dir = dirFor(RUN_ID, DIR_SUFFIX, 200);
        int iters = (dir + "\\ITERS\\it.250\\" + RUN_ID + ".250" + OutputPathGuard.LONGEST_ITERS_SUFFIX).length();
        assertThat(measure(dir, RUN_ID, 250).longestProjectedLength()).isGreaterThanOrEqualTo(iters);
    }

    /**
     * The Sim-PC root length is unknown, so this does NOT use the Sim's literal path: the prefix is
     * synthetic and chosen so the evensplit variant lands on 251 (METHODS-LOG 2.51, last OK). The
     * test proves the formula reproduces the 2.51 pair: the same prefix with tag suffix _s1338
     * (6 chars, counted once in the directory name and once in the runId) gives 263 (FAIL).
     */
    @Test void measuredTagsFromMethodsLog() {
        String tag = "d1c_dep7_f140_chi600_evensplit";
        String runOk = "DRT_SHAREDUSE_13052025_" + tag;
        String dirOk = dirFor(runOk, runOk + "_iter250_jsprit100", 251);
        String prefix = dirOk.substring(0, dirOk.lastIndexOf('\\') + 1);
        assertThat(measure(dirOk, runOk, 250).nativeFileLength()).isEqualTo(251);

        String runBad = runOk + "_s1338";
        String dirBad = prefix + runBad + "_iter250_jsprit100";
        assertThat(measure(dirBad, runBad, 250).nativeFileLength()).isEqualTo(263);
        assertThatThrownBy(() -> OutputPathGuard.enforce(dirBad, runBad, 250, true, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("263");
    }

    @Test void runSimulationAbortsBeforeAnyWorkOnLongPath() throws Exception {
        assumeTrue(OutputPathGuard.isWindows(), "the abort is Windows-only (M2)");
        Path longRoot = tmp.resolve("r".repeat(220));
        String old = System.getProperty(HagridPaths.OUTPUT_ROOT_PROPERTY);
        System.setProperty(HagridPaths.OUTPUT_ROOT_PROPERTY, longRoot.toString());
        try {
            HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenarios(new String[]{
                    "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=opg"}).get(0);
            assertThat(cfg.isDrtScenario()).isTrue();
            assertThatThrownBy(() -> SimulationRunnerUtils.runSimulation(cfg))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("251").hasMessageContaining("HAGRID_OUTPUT_ROOT");
            assertThat(longRoot).doesNotExist();
            assertThat(cfg.getOutputDirectory()).doesNotExist();
        } finally {
            restore(old);
        }
    }

    // ------------------------------------------------------------------ launch level (I2)

    private static final String SHORT_SPEC = "concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,tag=opgok";
    private static final String LONG_SPEC = SHORT_SPEC + "_with_a_much_longer_tag_suffix";

    private static void restore(String old) {
        if (old == null) System.clearProperty(HagridPaths.OUTPUT_ROOT_PROPERTY);
        else System.setProperty(HagridPaths.OUTPUT_ROOT_PROPERTY, old);
    }

    private static int nativeLength(HAGRIDSimulationConfig cfg) {
        return measure(cfg.getOutputDirectoryAsString(), cfg.getRunId(), cfg.getMaxIterations()).nativeFileLength();
    }

    /**
     * Parses [short, long] under an output root padded so the SHORT spec lands exactly on the
     * limit (251, OK) and the long one above it. Leaves the property set; callers restore it.
     */
    private List<HAGRIDSimulationConfig> okAndTooLong() {
        System.setProperty(HagridPaths.OUTPUT_ROOT_PROPERTY, tmp.resolve("r").toString());
        int shortLen = nativeLength(SimulationRunnerUtils.parseScenario(SHORT_SPEC));
        int pad = OutputPathGuard.NATIVE_PATH_LIMIT - shortLen;
        assertThat(pad).isNotNegative();
        System.setProperty(HagridPaths.OUTPUT_ROOT_PROPERTY, tmp.resolve("r" + "r".repeat(pad)).toString());
        List<HAGRIDSimulationConfig> cfgs = SimulationRunnerUtils.parseScenarios(new String[]{SHORT_SPEC, LONG_SPEC});
        assertThat(nativeLength(cfgs.get(0))).isEqualTo(OutputPathGuard.NATIVE_PATH_LIMIT);
        assertThat(nativeLength(cfgs.get(1))).isGreaterThan(OutputPathGuard.NATIVE_PATH_LIMIT);
        return cfgs;
    }

    @Test void enforceAllNamesOnlyTheTooLongScenario() {
        String old = System.getProperty(HagridPaths.OUTPUT_ROOT_PROPERTY);
        try {
            List<HAGRIDSimulationConfig> cfgs = okAndTooLong();
            String shortRun = cfgs.get(0).getRunId();
            String longRun = cfgs.get(1).getRunId();
            assertThatThrownBy(() -> OutputPathGuard.enforceAll(cfgs, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1 scenario(s)").hasMessageContaining("scenario 2: ")
                    .hasMessageContaining(longRun + " would be").hasMessageNotContaining(shortRun + " would be")
                    .hasMessageContaining("HAGRID_OUTPUT_ROOT");
            try (LogCapture log = LogCapture.of(OutputPathGuard.class)) {
                assertThatCode(() -> OutputPathGuard.enforceAll(cfgs, false)).doesNotThrowAnyException();
                assertThat(log.warnings()).hasSize(1).allMatch(m -> m.contains(longRun));
            }
        } finally {
            restore(old);
        }
    }

    @Test void enforceAllListsEveryViolation() {
        String old = System.getProperty(HagridPaths.OUTPUT_ROOT_PROPERTY);
        try {
            System.setProperty(HagridPaths.OUTPUT_ROOT_PROPERTY, tmp.resolve("r".repeat(220)).toString());
            List<HAGRIDSimulationConfig> both = SimulationRunnerUtils.parseScenarios(new String[]{SHORT_SPEC, LONG_SPEC});
            assertThatThrownBy(() -> OutputPathGuard.enforceAll(both, true))
                    .hasMessageContaining("2 scenario(s)").hasMessageContaining("scenario 1: ")
                    .hasMessageContaining("scenario 2: ");
        } finally {
            restore(old);
        }
    }

    /** I2 through the launcher's entry point: validateAll aborts before any scenario starts. */
    @Test void validateAllAbortsOnTheTooLongScenarioBeforeAnyRun() {
        assumeTrue(OutputPathGuard.isWindows(), "the abort is Windows-only (M2)");
        String old = System.getProperty(HagridPaths.OUTPUT_ROOT_PROPERTY);
        try {
            List<HAGRIDSimulationConfig> cfgs = okAndTooLong();
            assertThatThrownBy(() -> SimulationRunnerUtils.validateAll(cfgs))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("native SQLite")
                    .hasMessageContaining(cfgs.get(1).getRunId() + " would be")
                    .hasMessageNotContaining(cfgs.get(0).getRunId() + " would be");
            assertThat(cfgs.get(0).getOutputDirectory()).doesNotExist();
            assertThat(cfgs.get(1).getOutputDirectory()).doesNotExist();
        } finally {
            restore(old);
        }
    }
}
