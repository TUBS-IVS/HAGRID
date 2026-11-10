package hagrid.core.config;

import java.util.Map;

/** Captured capacity and handling policy for both preprocessing and the live Shared-Use module. */
public record SharedUseSettings(int baseSeats, int seats, int parcelSlots,
        double depotLoadPerParcelSeconds, double maxPickupDurationSeconds, double submissionStartSeconds,
        double submissionEndSeconds, double deliveryEndSeconds, double durationPerParcelSeconds,
        double maxDurationPerStopSeconds, long parcelSeed) {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("drt.baseSeats", "10"), Map.entry("sharedUse.seats", "8"), Map.entry("sharedUse.parcelSlots", "20"),
            Map.entry("sharedUse.depotLoadPerParcelSeconds", "30"), Map.entry("sharedUse.maxPickupDurationSeconds", "600"),
            Map.entry("sharedUse.submitUntilSeconds", "36000"), Map.entry("sharedUse.parcelSeed", "4711"));

    public SharedUseSettings {
        if (baseSeats < 1 || seats < 1 || seats > baseSeats || parcelSlots < 1) {
            throw new IllegalArgumentException("Shared-Use seats/parcel capacity must be positive; seats cannot exceed the base fleet");
        }
        for (double value : new double[]{depotLoadPerParcelSeconds, maxPickupDurationSeconds, submissionStartSeconds,
                submissionEndSeconds, deliveryEndSeconds, durationPerParcelSeconds, maxDurationPerStopSeconds}) {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Shared-Use values must be finite and nonnegative");
        }
        if (submissionStartSeconds >= submissionEndSeconds || submissionEndSeconds > deliveryEndSeconds
                || durationPerParcelSeconds <= 0 || maxDurationPerStopSeconds <= 0) {
            throw new IllegalArgumentException("Shared-Use submission must fit the delivery window and handling must be positive");
        }
    }
    public static Map<String, String> defaultsValues() { return DEFAULTS; }
    public static boolean contains(String key) { return DEFAULTS.containsKey(key); }
    public static void validate(String key, String value) {
        if (!contains(key)) throw new IllegalArgumentException("Unknown Shared-Use key: " + key);
        if (key.equals("sharedUse.parcelSeed")) { Long.parseLong(value); return; }
        if (key.equals("drt.baseSeats") || key.equals("sharedUse.seats") || key.equals("sharedUse.parcelSlots")) {
            if (Integer.parseInt(value) < 1) throw new IllegalArgumentException(key + " must be a positive integer");
        } else {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 0) throw new IllegalArgumentException(key + " must be finite and nonnegative");
        }
    }
    public static SharedUseSettings from(Map<String, String> values, ResolvedHagridConfig.TourRules rules) {
        return create(values, rules.deliveryWindowStartSeconds(), rules.deliveryWindowEndSeconds(),
                rules.durationPerParcelSeconds(), rules.maxDurationPerStopSeconds());
    }
    public static SharedUseSettings defaults() { return create(Map.of(), 27000, 75600, 120, 900); }
    private static SharedUseSettings create(Map<String, String> values, double start, double end, double parcel, double stop) {
        return new SharedUseSettings(i(values, "drt.baseSeats"), i(values, "sharedUse.seats"), i(values, "sharedUse.parcelSlots"),
                n(values, "sharedUse.depotLoadPerParcelSeconds"), n(values, "sharedUse.maxPickupDurationSeconds"), start,
                n(values, "sharedUse.submitUntilSeconds"), end, parcel, stop,
                Long.parseLong(values.getOrDefault("run.seed", values.getOrDefault("sharedUse.parcelSeed", DEFAULTS.get("sharedUse.parcelSeed")))));
    }
    private static int i(Map<String, String> values, String key) { return Integer.parseInt(values.getOrDefault(key, DEFAULTS.get(key))); }
    private static double n(Map<String, String> values, String key) { return Double.parseDouble(values.getOrDefault(key, DEFAULTS.get(key))); }
    public double segmentDwellSeconds(int parcels) { return Math.min(durationPerParcelSeconds * parcels, maxDurationPerStopSeconds); }
    public double depotPickupSeconds(int parcels) { return Math.min(depotLoadPerParcelSeconds * parcels, maxPickupDurationSeconds); }
}
