package hagrid.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a machine-readable {@code run_metadata.json} into the MATSim output
 * directory so downstream analysis (analysis/kpi) can bind
 * run_id / study_area / scenario / operation_mode without parsing directory names.
 */
public final class RunMetadataWriter {

    public static final String FILE_NAME = "run_metadata.json";

    private RunMetadataWriter() {
    }

    /** Collects metadata from the run config and writes it into {@code targetDir}. */
    public static Path write(HAGRIDSimulationConfig cfg, Path targetDir) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", cfg.getRunId());
        m.put("run_dir_name", targetDir.getFileName().toString());
        m.put("scenario", cfg.getConcept().toUpperCase());
        m.put("study_area", cfg.getStudyArea().name().toLowerCase());
        m.put("operation_mode", "conventional"); // 1c/1d thread the autonomy switch through here
        m.put("tag", cfg.getTag() == null ? "" : cfg.getTag());
        m.put("sim_date", cfg.getFormattedDate());
        m.put("matsim_iterations", cfg.getMaxIterations());
        m.put("jsprit_iterations", cfg.getJspritIterations());
        m.put("fleet_size", cfg.isDrtScenario() ? cfg.getFleetSize() : null);
        m.put("drt_with_freight", cfg.isDrtWithFreight());
        // I2/M5: chiThreshold/noParcels define the DRT_SHAREDUSE sweep point but are NOT part
        // of the runId — persisting them here is the only machine-readable binding between a
        // finished run directory and its sweep coordinates (harmless defaults otherwise).
        m.put("chi_threshold", cfg.getChiThreshold());
        m.put("no_parcels", cfg.isNoParcels());
        m.put("created", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return writeMap(m, targetDir);
    }

    /** Serialization layer, unit-tested without a full config object. */
    static Path writeMap(Map<String, Object> m, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path file = targetDir.resolve(FILE_NAME);
        Files.writeString(file, toJson(m), StandardCharsets.UTF_8);
        return file;
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            sb.append("  \"").append(e.getKey()).append("\": ").append(jsonValue(e.getValue()));
            if (++i < m.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        return sb.append("}\n").toString();
    }

    private static String jsonValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return '"' + v.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + '"';
    }
}
