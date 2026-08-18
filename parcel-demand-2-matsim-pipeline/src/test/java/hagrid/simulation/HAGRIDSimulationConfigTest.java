package hagrid.simulation;

import hagrid.integrated.drt.DrtInputsFingerprint;
import hagrid.integrated.modular.Modular;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
     * Task 11 review Finding 2: DRT_MODULAR must require the LMD preprocessing trio (demand
     * shapefile, vehicle types, raw network) even when {@code drtWithFreight=false} — unlike
     * DRT_BASELINE (see {@link #validateSkipsFreightForDrt}), which skips them in that case. This
     * closes the coverage gap the Task 11 report itself flagged: {@code ModularEndToEndTest}
     * bypasses {@code validateInputFiles()} entirely, so nothing else in the suite exercises the
     * widened condition. Stubs the same 9 DRT files as {@link #validateSkipsFreightForDrt} plus
     * the fingerprint, but deliberately leaves the LMD demand shapefile absent — if the widened
     * condition ever regressed back to {@code isDrtWithFreight()} alone, this would wrongly NOT
     * throw.
     */
    @Test
    @DisplayName("modularRequiresLmdTrioRegardlessOfFreightFlag — DRT_MODULAR + freight=false still requires the LMD trio")
    void modularRequiresLmdTrioRegardlessOfFreightFlag(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig cfg = modularConfig(20);
            stubDrtInputs(tempDir, cfg);
            DrtInputsFingerprint.write(cfg, Path.of(cfg.getDrtInputsFingerprint()));
            // Deliberately NOT stubbed: getLmdDemandShapefile() / getLmdVehicleTypes() /
            // getLausitzNetworkRaw() — the LMD trio this test proves is still required.

            assertThatThrownBy(cfg::validateInputFiles)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LMD demand shapefile");
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /**
     * Task 6 review (depot-CSV pre-check): the modular-specific branch
     * ({@code isDrtWithFreight() || modular}) checks the LMD demand shapefile / vehicle types /
     * raw network trio and now ALSO checks the depot CSV there directly, mirroring the same
     * {@code requireFile}-style call used for the demand shapefile. Note this does not change
     * observable behaviour today: the depot CSV was (and still is) already required
     * unconditionally for every {@code isDrtScenario()} concept a few lines above the modular
     * branch, so DRT_MODULAR already could not pass without it. This test pins the modular
     * branch's OWN, now-independent requirement — defense-in-depth against a future edit to that
     * generic check silently dropping depot-CSV coverage for DRT_MODULAR specifically — by
     * stubbing every other required file (the 8 non-depot DRT stubs plus the LMD trio) and
     * deliberately leaving ONLY the depot CSV absent.
     */
    @Test
    @DisplayName("modularRequiresDepotCsv — DRT_MODULAR missing only the depot CSV still aborts naming it")
    void modularRequiresDepotCsv(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig cfg = modularConfig(20);
            createStub(tempDir, cfg.getDrtNetworkClipped());
            createStub(tempDir, cfg.getPassengerPlansClipped());
            createStub(tempDir, cfg.getDrtFleetFile());
            createStub(tempDir, cfg.getDrtServiceAreaShapefile());
            createStub(tempDir, cfg.getLausitzBaseConfig());
            createStub(tempDir, cfg.getLausitzTransitScheduleRaw());
            createStub(tempDir, cfg.getLausitzTransitVehiclesRaw());
            createStub(tempDir, cfg.getLausitzVehicleTypes());
            createStub(tempDir, cfg.getLmdDemandShapefile());
            createStub(tempDir, cfg.getLmdVehicleTypes());
            createStub(tempDir, cfg.getLausitzNetworkRaw());
            // Deliberately NOT stubbed: cfg.getLmdDepotCsv() — the one file this test proves is
            // still required.
            DrtInputsFingerprint.write(cfg, Path.of(cfg.getDrtInputsFingerprint()));

            assertThatThrownBy(cfg::validateInputFiles)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LMD depot CSV");
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

    /**
     * District-based depot assignment (spec 2026-08-17): every shorter constructor must default
     * {@code openDepots} to "every depot open" (empty list) and {@code maxJobsPerDistrict} to
     * 300, so existing callers that never mention either key keep the pre-task-7 behaviour.
     *
     * <p>The brief's snippet references {@code HAGRIDSimulationConfig.defaults()} /
     * {@code withDistrictSettings(...)}, neither of which exists in this codebase — adapted to
     * the constructor-based style every other test in this file already uses (e.g.
     * {@link #drtConfig(int)}).</p>
     */
    @Test
    @DisplayName("openDepotsDefaultsToAllAndMaxJobsToThreeHundred - shorter constructors default district keys")
    void openDepotsDefaultsToAllAndMaxJobsToThreeHundred() {
        HAGRIDSimulationConfig cfg = drtConfig(50);
        assertThat(cfg.getOpenDepots()).isEmpty();
        assertThat(cfg.getMaxJobsPerDistrict()).isEqualTo(300);
    }

    /**
     * The fullest constructor must reject a non-positive {@code maxJobsPerDistrict} next to the
     * existing {@code maxTourDurationSeconds &lt;= 0} check.
     */
    @Test
    @DisplayName("maxJobsPerDistrictMustBePositive - fullest constructor rejects maxJobsPerDistrict <= 0")
    void maxJobsPerDistrictMustBePositive() {
        assertThatThrownBy(() -> new HAGRIDSimulationConfig(
                "DRT_MODULAR", LocalDate.of(2025, 5, 13), 1, 1,
                false, 0.0, 0.0, "", StudyArea.LAUSITZ_HOYERSWERDA, 4,
                false, true, 600.0, false, 1337L,
                Modular.DEFAULT_IDLE_THRESHOLD, Modular.DEFAULT_MAX_TOUR_DURATION_S,
                List.of("hoy_sued"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxJobsPerDistrict");
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

    /**
     * DRT_MODULAR config with {@code drtWithFreight=false} at the temp root: 1d always needs the
     * LMD preprocessing trio regardless of the freight flag (it always runs the offline jsprit
     * preprocessing), unlike DRT_BASELINE above where {@code drtWithFreight=false} skips them.
     */
    private static HAGRIDSimulationConfig modularConfig(int fleetSize) {
        return new HAGRIDSimulationConfig(
                "DRT_MODULAR",
                LocalDate.of(2025, 5, 13),
                1,  // maxIterations
                1,  // jspritIterations
                false, 0.0, 0.0, "",
                StudyArea.LAUSITZ_HOYERSWERDA,
                fleetSize,
                false);  // drtWithFreight=false: must NOT skip the LMD trio for DRT_MODULAR
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
