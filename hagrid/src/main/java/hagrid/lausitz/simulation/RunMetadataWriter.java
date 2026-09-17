package hagrid.lausitz.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import hagrid.core.simulation.HAGRIDSimulationConfig;
import hagrid.lausitz.modular.Modular;

/**
 * Writes a machine-readable {@code run_metadata.json} into the MATSim output
 * directory so downstream analysis (analysis/kpi) can bind
 * run_id / study_area / scenario / operation_mode without parsing directory names.
 *
 * <p><b>Scope rule for this file: everything that defines the run but is NOT in the runId.</b>
 * The runId carries concept, date, tag, iteration and jsprit counts; every other knob a sweep
 * moves - {@code chiThreshold}, the seed, {@code openDepots}, and (plan 2026-09-04, Task 6) the
 * whole DRT_MODULAR dispatch block {@code idleThreshold} / {@code maxTourDuration} /
 * {@code maxConcurrentFreight} / {@code freightWindows} / the three budget keys - is otherwise
 * recoverable only by grepping a console log, which is how a comparison in this campaign came to
 * rest on a {@code .bat} that set {@code fleetSize} twice. A run directory must describe itself.
 *
 * <p><b>Field names and types are a published contract</b> read by {@code analysis/kpi/run_meta.py}
 * (and through it {@code build_kpis.py}). That reader takes optional fields with {@code .get()},
 * so ADDING keys is safe and old metadata files keep loading; renaming or retyping one is not.
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
        // F3: the MATSim seed is a runner key (error-band replicates differ ONLY in it and
        // the tag) — persisting it lets sweep/error-band assembly bind replicates to seeds.
        m.put("matsim_seed", cfg.getSeed());
        // District-based depot assignment (spec 2026-08-17): openDepots/maxJobsPerDistrict define
        // the INTEGRATED-arm (1c/1d) sweep point but, like chiThreshold above, are NOT part of the
        // runId — persisting them here lets the KPI layer label sweep stages.
        m.put("open_depots", cfg.getOpenDepots().isEmpty() ? "all" : String.join(",", cfg.getOpenDepots()));
        m.put("max_jobs_per_district", cfg.getMaxJobsPerDistrict());
        // DRT_MODULAR dispatch coordinates (plan 2026-09-04, Task 6). Like chiThreshold and
        // openDepots above, NONE of these is part of the runId, so without this block a finished
        // run directory cannot say which sweep point produced it. That gap has already cost time
        // twice in this campaign: the only way to recover an existing run's theta was to grep the
        // console log, and one whole comparison rested on a .bat whose BASE variable set fleetSize
        // twice. Written for every scenario (harmless defaults elsewhere), same as chi_threshold.
        m.put("idle_threshold", cfg.getIdleThreshold());
        m.put("max_tour_duration_s", cfg.getMaxTourDurationSeconds());
        m.put("max_concurrent_freight", cfg.getMaxConcurrentFreight());
        // "always_open" rather than "" for an empty window list, mirroring open_depots' "all":
        // an empty string reads as "the writer had nothing", a named default reads as the
        // configuration it actually is.
        m.put("freight_windows", formatWindows(cfg.getFreightWindows()));
        m.put("budget_mode", cfg.getBudgetMode().name().toLowerCase(Locale.ROOT));
        m.put("budget_smoothing", cfg.getBudgetSmoothing());
        m.put("budget_headroom", cfg.getBudgetHeadroom());
        m.put("budget_urgency_lead_s", cfg.getBudgetUrgencyLeadS());
        m.put("created", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return writeMap(m, targetDir);
    }

    /**
     * Renders dispatch windows as {@code "HH:MM:SS-HH:MM:SS[,...]"}, or {@code "always_open"} for
     * an empty list. Seconds are kept even when they are zero: the windows are parsed from
     * {@code HH:MM} but bounded by the expiry envelope in seconds, and a rendering that silently
     * dropped a non-zero seconds field would misreport the very boundary
     * {@code Modular.parseWindows}' javadoc warns about.
     */
    static String formatWindows(List<Modular.DispatchWindow> windows) {
        if (windows.isEmpty()) {
            return "always_open";
        }
        return windows.stream()
                .map(w -> hhmmss(w.startS()) + "-" + hhmmss(w.endS()))
                .collect(Collectors.joining(","));
    }

    private static String hhmmss(double seconds) {
        long s = Math.round(seconds);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
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
