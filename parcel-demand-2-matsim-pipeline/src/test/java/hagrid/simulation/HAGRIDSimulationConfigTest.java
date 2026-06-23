package hagrid.simulation;

import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link HAGRIDSimulationConfig#validateInputFiles()}.
 */
@DisplayName("HAGRIDSimulationConfig — validateInputFiles")
class HAGRIDSimulationConfigTest {

    /**
     * For a DRT scenario the freight-input checks (config, vehicle types, car network,
     * bike network, change events, freight zone, delivery carriers, supply carriers) must
     * be SKIPPED. Only the 3 clipped-DRT files + the service-area shp + Lausitz base
     * config are required. This test creates stub files at only those 5 paths and asserts
     * that validateInputFiles() does NOT throw, even though the 8 freight files are absent.
     */
    @Test
    @DisplayName("validateSkipsFreightForDrt — freight files absent, DRT stubs present → no exception")
    void validateSkipsFreightForDrt(@TempDir Path tempDir) throws Exception {

        // Build a DRT HAGRIDSimulationConfig rooted at the temp dir so all paths are writable.
        // We override the pipeline root via the system property used by HagridPaths.detectPipelineRoot().
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig cfg = new HAGRIDSimulationConfig(
                    "DRT_BASELINE",
                    LocalDate.of(2025, 5, 13),
                    1,  // maxIterations
                    1,  // jspritIterations
                    false, 0.0, 0.0, "",
                    StudyArea.LAUSITZ_HOYERSWERDA,
                    20);

            // Create the 5 DRT-only required files as stubs.
            createStub(tempDir, cfg.getDrtNetworkClipped());
            createStub(tempDir, cfg.getPassengerPlansClipped());
            createStub(tempDir, cfg.getDrtFleetFile());
            createStub(tempDir, cfg.getDrtServiceAreaShapefile());
            createStub(tempDir, cfg.getLausitzBaseConfig());

            // No freight files exist — must not throw.
            assertThatCode(cfg::validateInputFiles)
                    .as("DRT validation must skip freight checks; only the 5 DRT stub files are present")
                    .doesNotThrowAnyException();

        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /** Creates the file (and any missing parent directories) as an empty stub. */
    private static void createStub(Path tempDir, String absolutePathString) throws Exception {
        // The path coming from HagridPaths may be absolute or relative to tempDir.
        Path p = Path.of(absolutePathString);
        if (!p.isAbsolute()) {
            p = tempDir.resolve(p);
        }
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            Files.writeString(p, "stub");
        }
    }
}
