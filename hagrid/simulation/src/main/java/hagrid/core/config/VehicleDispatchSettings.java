package hagrid.core.config;

import java.util.Map;

/** Dispatch windows and Gaussian spreads. CEP spreads use minutes; supply spreads use seconds. */
public record VehicleDispatchSettings(double largeJitterMinutes, double defaultJitterMinutes,
        double earlyStartSeconds, double earlyEndSeconds, double lateStartSeconds, double lateEndSeconds,
        double earlyJitterSeconds, double lateJitterSeconds) {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("fleet.largeDispatchJitterMinutes", "5"),
            Map.entry("fleet.defaultDispatchJitterMinutes", "15"),
            Map.entry("supply.earlyStartSeconds", "21600"), Map.entry("supply.earlyEndSeconds", "27900"),
            Map.entry("supply.lateStartSeconds", "72000"), Map.entry("supply.lateEndSeconds", "86400"),
            Map.entry("supply.earlyDispatchJitterSeconds", "10"), Map.entry("supply.lateDispatchJitterSeconds", "20"));

    public VehicleDispatchSettings {
        for (double value : new double[]{largeJitterMinutes, defaultJitterMinutes, earlyStartSeconds,
                earlyEndSeconds, lateStartSeconds, lateEndSeconds, earlyJitterSeconds, lateJitterSeconds}) {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Dispatch values must be finite and nonnegative");
        }
        if (earlyStartSeconds >= earlyEndSeconds || lateStartSeconds >= lateEndSeconds
                || earlyEndSeconds > Integer.MAX_VALUE || lateEndSeconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Supply start must be before end in the supported native clock range");
        }
    }
    public static boolean contains(String key) { return DEFAULTS.containsKey(key); }
    public static Map<String, String> defaultsValues() { return DEFAULTS; }
    public static VehicleDispatchSettings defaults() { return from(Map.of()); }
    public static void validate(String key, String value) {
        if (!contains(key)) throw new IllegalArgumentException("Unknown dispatch key: " + key);
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed) || parsed < 0) throw new IllegalArgumentException(key + " must be finite and nonnegative");
    }
    public static VehicleDispatchSettings from(Map<String, String> values) {
        return new VehicleDispatchSettings(n(values, "fleet.largeDispatchJitterMinutes"), n(values, "fleet.defaultDispatchJitterMinutes"),
                n(values, "supply.earlyStartSeconds"), n(values, "supply.earlyEndSeconds"),
                n(values, "supply.lateStartSeconds"), n(values, "supply.lateEndSeconds"),
                n(values, "supply.earlyDispatchJitterSeconds"), n(values, "supply.lateDispatchJitterSeconds"));
    }
    private static double n(Map<String, String> values, String key) {
        return Double.parseDouble(values.getOrDefault(key, DEFAULTS.get(key)));
    }
}
