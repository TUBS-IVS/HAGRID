package hagrid.core.simulation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Aborts a DRT run BEFORE the simulation when its output path is too long for native SQLite
 * (METHODS-LOG §2.51): the DRT GeoPackage {@code <runId>.drt_waitStats_drt_zonal.gpkg} is written
 * at shutdown and fails at a path length of 252 or more, i.e. after the full compute time.
 * Lengths are measured on the absolute, normalized output directory string.
 */
final class OutputPathGuard {

    private static final Logger LOG = LogManager.getLogger(OutputPathGuard.class);

    static final int NATIVE_PATH_LIMIT = 251;
    static final int WINDOWS_PATH_WARN = 259;
    static final String DRT_ZONAL_GPKG_SUFFIX = ".drt_waitStats_drt_zonal.gpkg";
    static final String LONGEST_ITERS_SUFFIX = ".occupancy_time_profiles_StackedArea_drt.png";

    record Check(int nativeFileLength, int longestProjectedLength) {}

    private OutputPathGuard() {}

    static Check measure(String outputDir, String runId, int maxIterations) {
        int nativeLen = outputDir.length() + 1 + runId.length() + DRT_ZONAL_GPKG_SUFFIX.length();
        String itersFile = outputDir + "\\ITERS\\it." + maxIterations + "\\" + runId + "." + maxIterations
                + LONGEST_ITERS_SUFFIX;
        int sep = Math.max(outputDir.lastIndexOf('\\'), outputDir.lastIndexOf('/'));
        String folderName = outputDir.substring(sep + 1);
        String dashboard = outputDir + "\\analysis\\HAGRID_Dashboard_" + folderName + ".html";
        return new Check(nativeLen, Math.max(itersFile.length(), dashboard.length()));
    }

    /**
     * The native limit is a Windows path limit (MAX_PATH): only there does a violation abort; on
     * other systems the same numbers are logged as a WARN (final review M2).
     */
    static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    static void enforce(HAGRIDSimulationConfig cfg) {
        enforce(cfg.getOutputDirectoryAsString(), cfg.getRunId(), cfg.getMaxIterations(), cfg.isDrtScenario());
    }

    static void enforce(String outputDir, String runId, int maxIter, boolean drt) {
        enforce(outputDir, runId, maxIter, drt, isWindows());
    }

    /** Violation text for a DRT run whose GeoPackage path exceeds the native limit, else {@code null}. */
    static String violation(String outputDir, String runId, int maxIter, boolean drt) {
        Check c = measure(outputDir, runId, maxIter);
        if (!drt || c.nativeFileLength() <= NATIVE_PATH_LIMIT) {
            return null;
        }
        return "the DRT GeoPackage path of " + runId + " would be " + c.nativeFileLength() + " characters (limit "
                + NATIVE_PATH_LIMIT + "), output directory '" + outputDir + "' is " + outputDir.length()
                + " characters";
    }

    private static final String REMEDY = "The run would crash at shutdown after its full compute time. Shorten the "
            + "tag or set HAGRID_OUTPUT_ROOT (or -Dhagrid.output.root) to a shorter directory (METHODS-LOG 2.51).";

    /**
     * Launch-level check (final review I2): every DRT scenario of one launch is measured BEFORE the
     * first one starts, so scenario k cannot abort only after 1..k-1 have spent their compute.
     * All violations are reported in one exception (Windows) or one WARN each (elsewhere).
     */
    static void enforceAll(List<HAGRIDSimulationConfig> cfgs) {
        enforceAll(cfgs, isWindows());
    }

    static void enforceAll(List<HAGRIDSimulationConfig> cfgs, boolean windows) {
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < cfgs.size(); i++) {
            HAGRIDSimulationConfig cfg = cfgs.get(i);
            String v = violation(cfg.getOutputDirectoryAsString(), cfg.getRunId(), cfg.getMaxIterations(),
                    cfg.isDrtScenario());
            if (v != null) {
                violations.add("scenario " + (i + 1) + ": " + v);
            }
        }
        if (violations.isEmpty()) {
            return;
        }
        if (windows) {
            throw new IllegalStateException("Output path too long for native SQLite in " + violations.size()
                    + " scenario(s) of this launch: " + String.join("; ", violations) + ". " + REMEDY);
        }
        violations.forEach(v -> LOG.warn("Output path exceeds the Windows native SQLite limit (not enforced on "
                + "this OS): {}", v));
    }

    static void enforce(String outputDir, String runId, int maxIter, boolean drt, boolean windows) {
        Check c = measure(outputDir, runId, maxIter);
        if (drt && c.nativeFileLength() > NATIVE_PATH_LIMIT) {
            String msg = "Output path too long for native SQLite: the DRT GeoPackage path would be "
                    + c.nativeFileLength() + " characters (limit " + NATIVE_PATH_LIMIT + "), output directory '"
                    + outputDir + "' is " + outputDir.length() + " characters. " + REMEDY;
            if (windows) {
                throw new IllegalStateException(msg);
            }
            LOG.warn("{} (Windows limit, not enforced on this OS)", msg);
        }
        if (c.longestProjectedLength() > WINDOWS_PATH_WARN) {
            LOG.warn("Longest projected output path is {} characters (> {}): Windows tools without long-path "
                    + "support may fail on it. Consider a shorter HAGRID_OUTPUT_ROOT.",
                    c.longestProjectedLength(), WINDOWS_PATH_WARN);
        }
    }
}
