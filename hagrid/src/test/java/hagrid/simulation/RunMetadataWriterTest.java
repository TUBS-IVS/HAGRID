package hagrid.simulation;

import hagrid.integrated.modular.Modular;
import hagrid.utils.general.StudyArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunMetadataWriterTest {

    @TempDir
    Path tmp;

    @Test
    void writesFlatJsonWithAllKeys() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", "DRT_BASELINE_13052025_married120");
        m.put("run_dir_name", "DRT_BASELINE_13052025_married120_iter150_jsprit100");
        m.put("scenario", "DRT_BASELINE");
        m.put("study_area", "lausitz_hoyerswerda");
        m.put("operation_mode", "conventional");
        m.put("tag", "married120");
        m.put("sim_date", "13052025");
        m.put("matsim_iterations", 150);
        m.put("jsprit_iterations", 100);
        m.put("fleet_size", 120);
        m.put("drt_with_freight", true);
        // I2/M5: sweep coordinates that are NOT part of the runId travel via the metadata
        m.put("chi_threshold", 600.0);
        m.put("no_parcels", false);
        // F3: the MATSim seed is a runner key; error-band assembly binds replicates via it
        m.put("matsim_seed", 1337L);
        m.put("created", "2026-07-06T12:00:00");

        Path file = RunMetadataWriter.writeMap(m, tmp);

        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        assertTrue(json.contains("\"run_id\": \"DRT_BASELINE_13052025_married120\""));
        assertTrue(json.contains("\"matsim_iterations\": 150"));
        assertTrue(json.contains("\"fleet_size\": 120"));
        assertTrue(json.contains("\"drt_with_freight\": true"));
        assertTrue(json.contains("\"chi_threshold\": 600.0"));
        assertTrue(json.contains("\"no_parcels\": false"));
        assertTrue(json.contains("\"matsim_seed\": 1337"));
        // valid enough JSON for Python's json.loads: braces + quoted keys
        assertTrue(json.trim().startsWith("{") && json.trim().endsWith("}"));
    }

    @Test
    void nullFleetSizeSerializesAsJsonNull() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", "LMD_BASELINE_13052025_x");
        m.put("fleet_size", null);
        Path file = RunMetadataWriter.writeMap(m, tmp);
        assertTrue(Files.readString(file).contains("\"fleet_size\": null"));
    }

    /**
     * Plan 2026-09-04, Task 6. NONE of these keys is part of the runId, so without them a finished
     * run directory cannot say which dispatch configuration produced it. That has already cost
     * time twice in this campaign: recovering an existing run's theta meant grepping console logs,
     * and one whole comparison rested on a .bat whose BASE variable set fleetSize twice. Asserted
     * against a REAL config object (not writeMap) so the getter each key reads is pinned too - a
     * key wired to the wrong getter would serialize perfectly and still describe the wrong run.
     */
    @Test
    void writesTheDispatchAndBudgetCoordinates() throws Exception {
        HAGRIDSimulationConfig cfg = new HAGRIDSimulationConfig(
                "drt_modular", LocalDate.of(2025, 5, 13), /*maxIterations*/ 3,
                /*jspritIterations*/ 1, false, 0.0, 1.0, "meta_coords",
                StudyArea.LAUSITZ_HOYERSWERDA, /*fleetSize*/ 130, /*drtWithFreight*/ true,
                /*kpiDashboard*/ false, /*chiThreshold*/ 600.0, /*noParcels*/ false,
                /*seed*/ 1337L, /*idleThreshold*/ 0.15, /*maxTourDurationSeconds*/ 12600,
                List.of(), /*maxJobsPerDistrict*/ 300, /*maxConcurrentFreight*/ 40,
                Modular.parseWindows("08:00-11:00,16:00-17:00"),
                Modular.BudgetMode.SELFREF, /*budgetSmoothing*/ 5, /*budgetHeadroom*/ 0.15);

        String json = Files.readString(RunMetadataWriter.write(cfg, tmp));

        assertTrue(json.contains("\"idle_threshold\": 0.15"), json);
        assertTrue(json.contains("\"max_tour_duration_s\": 12600"), json);
        assertTrue(json.contains("\"max_concurrent_freight\": 40"), json);
        assertTrue(json.contains("\"freight_windows\": \"08:00:00-11:00:00,16:00:00-17:00:00\""), json);
        assertTrue(json.contains("\"budget_mode\": \"selfref\""), json);
        assertTrue(json.contains("\"budget_smoothing\": 5"), json);
        assertTrue(json.contains("\"budget_headroom\": 0.15"), json);
    }

    /**
     * An EMPTY window list is "always open", which is a real configuration, not missing data --
     * hence a named default, mirroring open_depots' "all". An empty string would read as "the
     * writer had nothing to say", and those two are not the same claim.
     */
    @Test
    void emptyWindowListIsRecordedAsAlwaysOpenNotAsAnEmptyString() {
        assertEquals("always_open", RunMetadataWriter.formatWindows(List.of()));
        assertEquals("08:00:00-11:00:00",
                RunMetadataWriter.formatWindows(Modular.parseWindows("08:00-11:00")));
    }

    @Test
    void escapesQuotesAndBackslashes() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "we\"ird\\tag");
        Path file = RunMetadataWriter.writeMap(m, tmp);
        assertTrue(Files.readString(file).contains("\"tag\": \"we\\\"ird\\\\tag\""));
    }

    @Test
    void escapesControlCharsInStrings() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "line1\nline2\rtabbed\tend");
        Path file = RunMetadataWriter.writeMap(m, tmp);
        assertTrue(Files.readString(file).contains("\"tag\": \"line1\\nline2\\rtabbed\\tend\""));
    }
}
