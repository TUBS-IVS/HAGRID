package hagrid.core.config;

import java.util.Map;

/** Monetary/activity penalties; overtime always uses the same effective tour cap as jsprit. */
public record CarrierScoringSettings(double maxWorkDurationSeconds, double activityCostPerSecond,
                                    double timeWindowPenaltyPerSecond, double overtimePenalty) {
    public CarrierScoringSettings {
        if (!Double.isFinite(maxWorkDurationSeconds) || maxWorkDurationSeconds <= 0)
            throw new IllegalArgumentException("Scoring tour duration must be finite and positive");
        for (double cost : new double[]{activityCostPerSecond,timeWindowPenaltyPerSecond,overtimePenalty})
            if (!Double.isFinite(cost) || cost < 0) throw new IllegalArgumentException("Scoring costs must be finite and nonnegative");
    }
    public static CarrierScoringSettings defaults() { return from(Map.of(), RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS); }
    public static CarrierScoringSettings from(Map<String,String> values, double cap) {
        return new CarrierScoringSettings(cap,
                Double.parseDouble(values.getOrDefault("scoring.activityCostPerSecond","0.008")),
                Double.parseDouble(values.getOrDefault("scoring.timeWindowPenaltyPerSecond","5.0")),
                Double.parseDouble(values.getOrDefault("scoring.overtimePenalty","5.0")));
    }
}
