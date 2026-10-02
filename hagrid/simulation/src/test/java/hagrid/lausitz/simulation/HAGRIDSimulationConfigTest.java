package hagrid.lausitz.simulation;

import hagrid.lausitz.drt.DrtInputsFingerprint;
import hagrid.lausitz.modular.Modular;
import hagrid.core.simulation.HAGRIDSimulationConfig;
import hagrid.core.util.StudyArea;
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

    /**
     * Review 2026-10-02 #2: MATSim deletes an existing output directory at startup, and the runId
     * is only CONCEPT_date[_tag], so a spec differing from a FINISHED run only in seed, fleetSize
     * or theta used to destroy it with nothing more than a log warning. A finished run is the
     * directory holding run_metadata.json, which is written after controler.run() returns.
     */
    @Test
    @DisplayName("completed run in the output directory → abort, naming overwrite=true")
    void refusesToReplaceACompletedRun(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig cfg = preparedDrtConfig(tempDir);
            Files.createDirectories(cfg.getOutputDirectory());
            Files.writeString(cfg.getOutputDirectory().resolve(RunMetadataWriter.FILE_NAME), "{}");

            assertThatThrownBy(cfg::validateInputFiles)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("COMPLETED run")
                    .hasMessageContaining("overwrite=true");
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    @Test
    @DisplayName("overwrite=true replaces a completed run deliberately")
    void overwriteFlagAllowsReplacingACompletedRun(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig cfg = preparedDrtConfig(tempDir);
            Files.createDirectories(cfg.getOutputDirectory());
            Files.writeString(cfg.getOutputDirectory().resolve(RunMetadataWriter.FILE_NAME), "{}");
            cfg.setOverwriteCompletedRun(true);

            assertThatCode(cfg::validateInputFiles).doesNotThrowAnyException();
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /** A crashed run never wrote run_metadata.json: restarting it must need no flag. */
    @Test
    @DisplayName("crashed run (output dir without run_metadata.json) restarts without a flag")
    void crashedRunRestartsWithoutAFlag(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig cfg = preparedDrtConfig(tempDir);
            Files.createDirectories(cfg.getOutputDirectory().resolve("ITERS"));

            assertThatCode(cfg::validateInputFiles).doesNotThrowAnyException();
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /**
     * Review 2026-10-02 #3: the 1c preprocessor builds the parcel agents from the districts of the
     * OPEN depots, so openDepots is baked into the prepared plans. It was not in the fingerprint:
     * same tag, other depots, no re-prepare, and the run used the old parcel agents while its
     * metadata named the new depots.
     */
    @Test
    @DisplayName("1c: openDepots drift between prepare and run → abort naming it")
    void detectsOpenDepotsDriftForSharedUse(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig prepared = sharedUseConfig(List.of("hoy_sued"));
            stubSharedUseInputs(tempDir, prepared);
            DrtInputsFingerprint.write(prepared, Path.of(prepared.getDrtInputsFingerprint()));

            HAGRIDSimulationConfig run = sharedUseConfig(List.of("hoy_sued", "lauta"));

            assertThatThrownBy(run::validateInputFiles)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("openDepots")
                    .hasMessageContaining("prepared=hoy_sued");
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /** The district builder filters in depot-CSV order, so the order given must not matter. */
    @Test
    @DisplayName("1c: openDepots in another order is not drift")
    void openDepotsOrderIsNotDrift(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig prepared = sharedUseConfig(List.of("lauta", "hoy_sued"));
            stubSharedUseInputs(tempDir, prepared);
            DrtInputsFingerprint.write(prepared, Path.of(prepared.getDrtInputsFingerprint()));

            assertThatCode(sharedUseConfig(List.of("hoy_sued", "lauta"))::validateInputFiles)
                    .doesNotThrowAnyException();
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /**
     * Review 2026-10-02 #4: the parcel counts live in the demand shapefile's .dbf, but only the
     * .shp's size and mtime were fingerprinted. Same-size content change, so a size check alone
     * would miss it too.
     */
    @Test
    @DisplayName("1c: changed parcel counts in the demand .dbf → abort naming the demand source")
    void detectsDemandDbfChange(@TempDir Path tempDir) throws Exception {
        System.setProperty("hagrid.pipeline.root", tempDir.toAbsolutePath().toString());
        try {
            HAGRIDSimulationConfig cfg = sharedUseConfig(List.of());
            stubSharedUseInputs(tempDir, cfg);
            DrtInputsFingerprint.write(cfg, Path.of(cfg.getDrtInputsFingerprint()));

            Files.writeString(dbfOf(cfg.getLmdDemandShapefile()), "dhl_tag=9");   // was dhl_tag=4

            assertThatThrownBy(cfg::validateInputFiles)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("source.lmdDemandShp");
        } finally {
            System.clearProperty("hagrid.pipeline.root");
        }
    }

    /** DRT_SHAREDUSE with parcels, given open depots, at the temp root. */
    private static HAGRIDSimulationConfig sharedUseConfig(List<String> openDepots) {
        return new HAGRIDSimulationConfig(
                "DRT_SHAREDUSE", LocalDate.of(2025, 5, 13), 1, 1,
                false, 0.0, 0.0, "fp", StudyArea.LAUSITZ_HOYERSWERDA, 20,
                false, true, 600.0, false, 1337L,
                Modular.DEFAULT_IDLE_THRESHOLD, Modular.DEFAULT_MAX_TOUR_DURATION_S,
                openDepots, 300);
    }

    /** The passenger-only stubs plus a demand shapefile family (.shp + .dbf). */
    private static void stubSharedUseInputs(Path tempDir, HAGRIDSimulationConfig cfg) throws Exception {
        stubDrtInputs(tempDir, cfg);
        createStub(tempDir, cfg.getLmdDemandShapefile());
        Files.writeString(dbfOf(cfg.getLmdDemandShapefile()), "dhl_tag=4");
    }

    private static Path dbfOf(String shp) {
        return Path.of(shp.substring(0, shp.length() - ".shp".length()) + ".dbf");
    }

    /** Passenger-only DRT config with every input stubbed and fingerprinted, i.e. valid. */
    private static HAGRIDSimulationConfig preparedDrtConfig(Path tempDir) throws Exception {
        HAGRIDSimulationConfig cfg = drtConfig(20);
        stubDrtInputs(tempDir, cfg);
        DrtInputsFingerprint.write(cfg, Path.of(cfg.getDrtInputsFingerprint()));
        return cfg;
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
