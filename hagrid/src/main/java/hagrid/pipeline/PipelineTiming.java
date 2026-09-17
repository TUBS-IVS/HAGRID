package hagrid.pipeline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records wall-clock execution times for each pipeline module.
 * <p>
 * Created by {@link PipelineExecutor} and stored in the MATSim
 * {@code Scenario} as {@code "pipelineTiming"} so the summary writer
 * can produce a runtime breakdown.
 *
 * @author HAGRID Team
 */
public final class PipelineTiming {

    private final Map<String, Long> moduleDurationsMs = new LinkedHashMap<>();
    private long pipelineStartMs;
    private long pipelineEndMs;

    /** Call before the first module starts. */
    public void startPipeline() {
        this.pipelineStartMs = System.currentTimeMillis();
    }

    /** Call after the last module finishes. */
    public void endPipeline() {
        this.pipelineEndMs = System.currentTimeMillis();
    }

    /**
     * Records the duration of a named module.
     *
     * @param moduleName human-readable module label
     * @param durationMs wall-clock milliseconds
     */
    public void recordModule(String moduleName, long durationMs) {
        moduleDurationsMs.put(moduleName, durationMs);
    }

    /** Total pipeline wall-clock time in milliseconds. */
    public long getTotalMs() {
        return pipelineEndMs - pipelineStartMs;
    }

    /** Ordered map: module name → duration in milliseconds. */
    public Map<String, Long> getModuleDurations() {
        return Collections.unmodifiableMap(moduleDurationsMs);
    }

    /** Format milliseconds as "Xm Ys" or "Xs" for display. */
    public static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        if (totalSec >= 60) {
            long min = totalSec / 60;
            long sec = totalSec % 60;
            return String.format("%dm %02ds", min, sec);
        }
        return String.format("%ds", totalSec);
    }
}
