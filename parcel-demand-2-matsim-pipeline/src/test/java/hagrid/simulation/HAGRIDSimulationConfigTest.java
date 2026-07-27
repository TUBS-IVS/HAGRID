package hagrid.simulation;

import hagrid.integrated.drt.DrtInputsFingerprint;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link HAGRIDSimulationConfig#validateInputFiles()}.
 */
@DisplayName("HAGRIDSimulationConfig — validateInputFiles")
class HAGRIDSimulationConfigTest {

    /**
     * For a passenger-only DRT run ({@code drtWithFreight=false}) the freight-input checks
     * (config, vehicle types, car network, bike network, change events, freight zone, delivery
     * carriers, supply carriers) AND the married-only LMD preprocessing inputs (demand shapefile,
     * LMD vehicle types, raw Lausitz network) must be SKIPPED. Only the 3 clipped-DRT files + the
     * service-area shp + Lausitz base config + depot CSV are required. This test creates stub
     * files at only those 9 paths and asserts that validateInputFiles() does NOT throw, even
     * though the freight files are absent.
     */
    @Test
    @DisplayName("validateSkipsFreightForDrt — freight files absent, DRT stubs present, freight=false → no exception")
    void validateSkipsFreightForDrt(@TempDir Path tempDir) throws Exception {

        // Build a passenger-only DRT HAGRIDSimulationConfig rooted at the temp dir so all paths
        // are writable. We override the pipeline root via the system property used by
        // HagridPaths.detectPipelineRoot().
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig cfg = drtConfig(20);
            stubDrtInputs(tempDir, cfg);
            // Prepared inputs carry a fingerprint of the config they were built from; the
            // preprocessor writes it, so the happy path has to supply it too.
            DrtInputsFingerprint.write(cfg, Path.of(cfg.getDrtInputsFingerprint()));

            // No freight files exist — must not throw.
            assertThatCode(cfg::validateInputFiles)
                    .as("DRT validation must skip freight checks; only the 9 DRT stub files are present")
                    .doesNotThrowAnyException();

        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /**
     * Inputs present but never fingerprinted (prepared before this guard existed). Existence
     * alone must NOT be accepted — otherwise the run silently uses artifacts whose provenance
     * is unknown.
     */
    @Test
    @DisplayName("missing fingerprint → abort with a re-prepare instruction")
    void rejectsPreparedInputsWithoutFingerprint(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig cfg = drtConfig(20);
            stubDrtInputs(tempDir, cfg);

            assertThatThrownBy(cfg::validateInputFiles)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fingerprint missing")
                    .hasMessageContaining("PrepareLausitzDrtInputs");
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /**
     * The regression this guard exists for: fleetSize is NOT part of the run id, so inputs
     * prepared for a 20-vehicle fleet sit at exactly the paths a 50-vehicle run reads. Before
     * the fingerprint, that run started happily on the 20-vehicle fleet file while logging
     * "fleet 50" — and the depot parking capacity was still derived from 50.
     */
    @Test
    @DisplayName("fleetSize drift between prepare and run → abort naming the mismatch")
    void detectsFleetSizeDrift(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig prepared = drtConfig(20);
            stubDrtInputs(tempDir, prepared);
            DrtInputsFingerprint.write(prepared, Path.of(prepared.getDrtInputsFingerprint()));

            HAGRIDSimulationConfig run = drtConfig(50);   // same concept/date/tag -> same paths

            assertThatThrownBy(run::validateInputFiles)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fleetSize")
                    .hasMessageContaining("prepared=20")
                    .hasMessageContaining("run wants=50");
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /**
     * A re-staged raw input (same path, new content) must invalidate the derived artifacts —
     * otherwise the run keeps simulating yesterday's network/population.
     */
    @Test
    @DisplayName("re-staged raw input → abort naming the stale source")
    void detectsRawInputChange(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig cfg = drtConfig(20);
            stubDrtInputs(tempDir, cfg);
            DrtInputsFingerprint.write(cfg, Path.of(cfg.getDrtInputsFingerprint()));

            // Re-stage the depot CSV with different content (size changes -> fingerprint changes).
            Files.writeString(Path.of(cfg.getLmdDepotCsv()), "provider;x;y\ndhl;1;2\n");

            assertThatThrownBy(cfg::validateInputFiles)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("source.depotCsv");
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /** Passenger-only DRT config at the temp root; only fleetSize varies across tests. */
    private static HAGRIDSimulationConfig drtConfig(int fleetSize) {
        return new HAGRIDSimulationConfig(
                "DRT_BASELINE",
                LocalDate.of(2025, 5, 13),
                1,  // maxIterations
                1,  // jspritIterations
                false, 0.0, 0.0, "",
                StudyArea.LAUSITZ_HOYERSWERDA,
                fleetSize,
                false);  // drtWithFreight=false: passenger-only DRT run
    }

    /** The 9 files a passenger-only DRT run requires (5 DRT-specific + 3 rail PT + 1 depot CSV). */
    private static void stubDrtInputs(Path tempDir, HAGRIDSimulationConfig cfg) throws Exception {
        createStub(tempDir, cfg.getDrtNetworkClipped());
        createStub(tempDir, cfg.getPassengerPlansClipped());
        createStub(tempDir, cfg.getDrtFleetFile());
        createStub(tempDir, cfg.getDrtServiceAreaShapefile());
        createStub(tempDir, cfg.getLausitzBaseConfig());
        createStub(tempDir, cfg.getLmdDepotCsv());
        createStub(tempDir, cfg.getLausitzTransitScheduleRaw());
        createStub(tempDir, cfg.getLausitzTransitVehiclesRaw());
        createStub(tempDir, cfg.getLausitzVehicleTypes());
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
