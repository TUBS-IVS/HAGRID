package hagrid;

import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link HagridPaths} — centralized path management.
 */
@DisplayName("HagridPaths")
class HagridPathsTest {

    private static final String RUN_ID = "BASECASE_13052025";

    private HagridPaths paths;

    @BeforeEach
    void setUp() {
        paths = new HagridPaths();
    }

    // =========================================================================
    // INITIALIZATION
    // =========================================================================

    @Nested
    @DisplayName("Initialization")
    class Initialization {

        @Test
        @DisplayName("new instance has no runId")
        void noRunIdInitially() {
            assertThat(paths.getRunId()).isNull();
        }

        @Test
        @DisplayName("runDir() throws before initializeRun")
        void runDirThrowsBeforeInit() {
            assertThatThrownBy(() -> paths.runDir())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("call initializeRun");
        }

        @Test
        @DisplayName("carrierDir() throws before initializeRun (cascading)")
        void carrierDirThrowsBeforeInit() {
            assertThatThrownBy(() -> paths.carrierDir())
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("initializeRun sets runId and runDir")
        void initializeRunSetsState() {
            paths.initializeRun(RUN_ID);

            assertThat(paths.getRunId()).isEqualTo(RUN_ID);
            assertThat(paths.runDir()).isNotNull();
            assertThat(paths.runDir().toString()).contains(RUN_ID);
        }
    }

    // =========================================================================
    // INPUT PATHS (no run init required)
    // =========================================================================

    @Nested
    @DisplayName("Input Paths")
    class InputPaths {

        @Test
        @DisplayName("input paths work without initializeRun")
        void inputPathsWithoutInit() {
            assertThatCode(() -> {
                paths.inputBase();
                paths.demandDir();
                paths.geodataDir();
                paths.hubsDir();
                paths.networkInputDir();
                paths.vehicleInputDir();
                paths.configDir();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("input base is under pipeline root")
        void inputBaseUnderRoot() {
            assertThat(paths.inputBase().toString()).endsWith("hagrid-input");
        }

        @Test
        @DisplayName("demandShapefile contains date and day-of-week")
        void demandShapefilePath() {
            String shp = paths.demandShapefile("TEST_RUN", "2025-05-13", "Tuesday");
            assertThat(shp)
                .contains("TEST_RUN")
                .contains("2025-05-13")
                .contains("Tuesday")
                .endsWith(".shp");
        }

        @Test
        @DisplayName("network file ends with .xml.gz")
        void networkFileExtension() {
            assertThat(paths.networkFile()).endsWith(".xml.gz");
        }

        @Test
        @DisplayName("vehicleTypesFile points to HAGRID_vehicleTypes2.0.xml")
        void vehicleTypesFile() {
            assertThat(paths.vehicleTypesFile()).contains("HAGRID_vehicleTypes2.0.xml");
        }
    }

    // =========================================================================
    // OUTPUT PATHS (run init required)
    // =========================================================================

    @Nested
    @DisplayName("Output Paths (with RunID prefix)")
    class OutputPaths {

        @BeforeEach
        void initRun() {
            paths.initializeRun(RUN_ID);
        }

        @Test
        @DisplayName("all carrier output files contain RunID prefix")
        void carrierFilesHaveRunIdPrefix() {
            assertThat(paths.deliveryCarriersUnrouted()).contains(RUN_ID + "_");
            assertThat(paths.deliveryCarriersMerged()).contains(RUN_ID + "_");
            assertThat(paths.deliveryCarriersRouted()).contains(RUN_ID + "_");
            assertThat(paths.supplyCarriersUnrouted()).contains(RUN_ID + "_");
            assertThat(paths.supplyCarriersSplitUnrouted()).contains(RUN_ID + "_");
            assertThat(paths.supplyCarriersRouted()).contains(RUN_ID + "_");
            assertThat(paths.carrierPlansCombined()).contains(RUN_ID + "_");
        }

        @Test
        @DisplayName("vehicle types output has RunID prefix")
        void vehicleTypesOutputPrefix() {
            assertThat(paths.vehicleTypesOutput()).contains(RUN_ID + "_vehicle_types.xml");
        }

        @Test
        @DisplayName("network outputs have RunID prefix")
        void networkOutputPrefix() {
            assertThat(paths.networkFiltered()).contains(RUN_ID + "_network_filtered");
        }

        @Test
        @DisplayName("network change events point to shared directory")
        void networkChangeEventsShared() {
            assertThat(paths.networkChangeEvents()).contains("shared");
            assertThat(paths.networkChangeEvents()).contains("network_change_events");
        }

        @Test
        @DisplayName("routing outputs have RunID prefix")
        void routingOutputPrefix() {
            assertThat(paths.routingMetrics()).contains(RUN_ID + "_routing_metrics.csv");
            assertThat(paths.routingStatus()).contains(RUN_ID + "_routing_status.csv");
        }

        @Test
        @DisplayName("scenario summary has RunID prefix")
        void summaryOutputPrefix() {
            assertThat(paths.scenarioSummary()).contains(RUN_ID + "_scenario_summary.txt");
        }

        @Test
        @DisplayName("output files are under hagrid-output/{RUN_ID}/")
        void outputFilesUnderRunDir() {
            String expected = "hagrid-output" + java.io.File.separator + RUN_ID;
            assertThat(paths.deliveryCarriersRouted()).contains(expected);
            assertThat(paths.supplyCarriersRouted()).contains(expected);
        }
    }

    // =========================================================================
    // MATSIM OUTPUT
    // =========================================================================

    @Nested
    @DisplayName("MATSim Output")
    class MatsimOutput {

        @Test
        @DisplayName("matsimRunDir throws before initializeRun")
        void matsimRunDirThrows() {
            assertThatThrownBy(() -> paths.matsimRunDir())
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("matsimRunDir is under hagrid-matsim-output/{RUN_ID}")
        void matsimRunDirPath() {
            paths.initializeRun(RUN_ID);
            assertThat(paths.matsimRunDir().toString())
                .contains("hagrid-matsim-output")
                .contains(RUN_ID);
        }

        @Test
        @DisplayName("matsimRunDir with iterations includes suffix")
        void matsimRunDirWithIterations() {
            paths.initializeRun(RUN_ID);
            Path dir = paths.matsimRunDir(10, 50);
            assertThat(dir.toString()).contains("_iter10_jsprit50");
        }
    }

    // =========================================================================
    // DIRECTORY CREATION
    // =========================================================================

    @Nested
    @DisplayName("Directory Creation")
    class DirectoryCreation {

        @Test
        @DisplayName("createOutputDirectories creates all subdirectories")
        void createsAllSubdirs(@TempDir Path tempDir) throws IOException {
            HagridPaths tempPaths = new HagridPaths(tempDir);
            tempPaths.initializeRun(RUN_ID);
            tempPaths.createOutputDirectories();

            assertThat(Files.isDirectory(tempPaths.carrierDir())).isTrue();
            assertThat(Files.isDirectory(tempPaths.vehicleOutputDir())).isTrue();
            assertThat(Files.isDirectory(tempPaths.networkOutputDir())).isTrue();
            assertThat(Files.isDirectory(tempPaths.routingDir())).isTrue();
            assertThat(Files.isDirectory(tempPaths.clusteringDir())).isTrue();
            assertThat(Files.isDirectory(tempPaths.summaryDir())).isTrue();
            assertThat(Files.isDirectory(tempPaths.cacheDir())).isTrue();
            assertThat(Files.isDirectory(tempPaths.logDir())).isTrue();
        }

        @Test
        @DisplayName("createOutputDirectories is idempotent")
        void idempotent(@TempDir Path tempDir) throws IOException {
            HagridPaths tempPaths = new HagridPaths(tempDir);
            tempPaths.initializeRun(RUN_ID);

            assertThatCode(() -> {
                tempPaths.createOutputDirectories();
                tempPaths.createOutputDirectories();  // Second call should not fail
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("createOutputDirectories throws before initializeRun")
        void throwsBeforeInit() {
            assertThatThrownBy(() -> paths.createOutputDirectories())
                .isInstanceOf(IllegalStateException.class);
        }
    }

    // =========================================================================
    // CUSTOM PIPELINE ROOT
    // =========================================================================

    @Nested
    @DisplayName("Custom Pipeline Root")
    class CustomPipelineRoot {

        @Test
        @DisplayName("custom root changes all base paths")
        void customRootChangesBasePaths(@TempDir Path tempDir) {
            HagridPaths custom = new HagridPaths(tempDir);

            assertThat(custom.getPipelineRoot()).isEqualTo(tempDir);
            assertThat(custom.inputBase().toString()).startsWith(tempDir.toString());
            assertThat(custom.matsimOutputBase().toString()).startsWith(tempDir.toString());
        }
    }

    // =========================================================================
    // TOSTRING
    // =========================================================================

    @Test
    @DisplayName("toString contains key path elements")
    void toStringFormat() {
        paths.initializeRun(RUN_ID);
        assertThat(paths.toString())
            .contains(RUN_ID)
            .contains("hagrid-input")
            .contains("hagrid-output")
            .contains("hagrid-matsim-output");
    }

    // =========================================================================
    // LAUSITZ DRT PATHS
    // =========================================================================

    @Nested
    @DisplayName("Lausitz DRT paths")
    class LausitzDrtPaths {

        @Test
        @DisplayName("input getters resolve under the lausitz input folder")
        void inputsScopedToLausitz(@TempDir Path tempDir) {
            HagridPaths p = new HagridPaths(tempDir, StudyArea.LAUSITZ_HOYERSWERDA);
            assertThat(p.drtServiceAreaShapefile()).contains("lausitz").endsWith("drt-service-area.shp");
            assertThat(p.lausitzNetworkRaw()).contains("lausitz").endsWith("lausitz-network.xml.gz");
            assertThat(p.passengerPlansRaw()).contains("lausitz").endsWith("lausitz-100pct.plans.xml.gz");
        }

        @Test
        @DisplayName("run-scoped DRT outputs embed the runId")
        void outputsCarryRunId(@TempDir Path tempDir) {
            HagridPaths p = new HagridPaths(tempDir, StudyArea.LAUSITZ_HOYERSWERDA);
            p.initializeRun("DRT_BASELINE_13052025");
            assertThat(p.drtNetworkClipped()).contains("DRT_BASELINE_13052025").endsWith("_drt_network.xml.gz");
            assertThat(p.passengerPlansClipped()).contains("DRT_BASELINE_13052025").endsWith("_drt_population.xml.gz");
            assertThat(p.drtFleetFile()).contains("DRT_BASELINE_13052025").endsWith("_drt_fleet.xml.gz");
        }
    }

    // =========================================================================
    // STUDY-AREA SCOPING
    // =========================================================================

    @Nested
    @DisplayName("Study-area scoping")
    class StudyAreaScoping {

        @Test
        @DisplayName("default constructor uses HANNOVER and legacy hagrid-input layout")
        void defaultIsHannoverLegacyLayout() {
            HagridPaths p = new HagridPaths();
            assertThat(p.getStudyArea()).isEqualTo(StudyArea.HANNOVER);
            assertThat(p.inputBase().toString()).endsWith("hagrid-input");
        }

        @Test
        @DisplayName("HANNOVER input base has no extra subfolder")
        void hannoverNoSubfolder(@TempDir Path tempDir) {
            HagridPaths p = new HagridPaths(tempDir, StudyArea.HANNOVER);
            assertThat(p.inputBase()).isEqualTo(tempDir.resolve("hagrid-input"));
        }

        @Test
        @DisplayName("LAUSITZ_HOYERSWERDA input base is scoped under hagrid-input/lausitz")
        void lausitzScoped(@TempDir Path tempDir) {
            HagridPaths p = new HagridPaths(tempDir, StudyArea.LAUSITZ_HOYERSWERDA);
            assertThat(p.getStudyArea()).isEqualTo(StudyArea.LAUSITZ_HOYERSWERDA);
            assertThat(p.inputBase()).isEqualTo(tempDir.resolve("hagrid-input").resolve("lausitz"));
            // network input file is scoped too
            assertThat(p.networkFile()).contains("lausitz");
        }

        @Test
        @DisplayName("output bases are NOT study-area-scoped (RUN_ID disambiguates outputs)")
        void outputsNotScoped(@TempDir Path tempDir) {
            HagridPaths p = new HagridPaths(tempDir, StudyArea.LAUSITZ_HOYERSWERDA);
            assertThat(p.matsimOutputBase()).isEqualTo(tempDir.resolve("hagrid-matsim-output"));
        }
    }
}
