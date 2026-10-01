package hagrid.lausitz.freight;

import java.util.Locale;

/**
 * What the jsprit result cache did for one preprocessing run (spec 2026-10-01 section 7). Returned
 * by {@code LausitzFreightPreprocessor.run/runModular} and handed IN MEMORY to
 * {@code RunMetadataWriter}, so a run can never report the status of an older run's sidecar.
 *
 * @param key            full SHA-256 of the cache key, {@code null} when the cache was off/bypassed
 * @param sourceRun      run that computed the entry (hit, verified, mismatch), else {@code null}
 * @param computeSeconds jsprit time of this run, or for a hit the time the entry saved
 */
public record JspritCacheResult(Status status, Mode mode, String reason, String key, String variant,
                                String sourceRun, String sourceCreated, Double computeSeconds) {

    /** {@code BLOCKED}: an earlier mismatch blocked the whole cache (spec section 6.4); computed fresh. */
    public enum Status {
        HIT, MISS, VERIFIED, MISMATCH, BLOCKED, OFF, BYPASS;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Value of {@code -Dhagrid.jsprit.cache}. */
    public enum Mode {
        ON, OFF, VERIFY;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Unset or blank means {@link #ON}; anything unknown fails instead of silently meaning on. */
        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return ON;
            }
            for (Mode m : values()) {
                if (m.name().equalsIgnoreCase(raw.trim())) {
                    return m;
                }
            }
            throw new IllegalArgumentException("-D" + JspritPlanCache.MODE_PROPERTY + "=" + raw
                    + " is not one of on|off|verify");
        }
    }
}
