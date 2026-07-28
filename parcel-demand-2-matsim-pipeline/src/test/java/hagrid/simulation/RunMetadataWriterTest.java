package hagrid.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
