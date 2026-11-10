package hagrid.core.config;

import java.util.Map;

/** One clock policy shared by dispatch, routed schedules, timing updates and freight KPIs. */
public record ModularSettings(double retoolingSeconds, double freightLookAheadSeconds,
        double chainBootstrapFactor, double deliveryEndSeconds, double maximumExcursionSeconds) {
    /** Compatibility projection; configured consumers derive the cap from the central tour rules. */
    public ModularSettings(double retoolingSeconds,double freightLookAheadSeconds,
            double chainBootstrapFactor,double deliveryEndSeconds) {
        this(retoolingSeconds,freightLookAheadSeconds,chainBootstrapFactor,deliveryEndSeconds,
                RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS);
    }
    public static final double DEFAULT_RETOOLING_SECONDS = 420;
    public static final double DEFAULT_FREIGHT_LOOK_AHEAD_SECONDS = 420;
    public static final double DEFAULT_CHAIN_BOOTSTRAP_FACTOR = 1.15;
    private static final Map<String, String> DEFAULTS = Map.of(
            "modular.retoolingSeconds", Double.toString(DEFAULT_RETOOLING_SECONDS),
            "modular.freightLookAheadSeconds", Double.toString(DEFAULT_FREIGHT_LOOK_AHEAD_SECONDS),
            "modular.chainBootstrapFactor", Double.toString(DEFAULT_CHAIN_BOOTSTRAP_FACTOR));

    public ModularSettings {
        if (!Double.isFinite(retoolingSeconds) || retoolingSeconds <= 0
                || !Double.isFinite(freightLookAheadSeconds) || freightLookAheadSeconds < 0
                || !Double.isFinite(chainBootstrapFactor) || chainBootstrapFactor < 1
                || !Double.isFinite(deliveryEndSeconds) || deliveryEndSeconds <= 0
                || !Double.isFinite(maximumExcursionSeconds) || maximumExcursionSeconds <= 0) {
            throw new IllegalArgumentException("Invalid modular timing/bootstrap policy");
        }
    }
    public static Map<String, String> defaultsValues() { return DEFAULTS; }
    public static boolean contains(String key) { return DEFAULTS.containsKey(key); }
    public static void validate(String key, String value) {
        double number=Double.parseDouble(value);
        double minimum=key.equals("modular.chainBootstrapFactor") ? 1 : 0;
        if (!contains(key) || !Double.isFinite(number) || number < minimum
                || key.equals("modular.retoolingSeconds") && number == 0) {
            throw new IllegalArgumentException(key + " is outside its finite allowed range");
        }
    }
    public static ModularSettings defaults() { return from(Map.of(), RoutingDefaults.LAUSITZ_DELIVERY_WINDOW_END_SECONDS); }
    public static ModularSettings from(Map<String, String> values, double deliveryEndSeconds) {
        return new ModularSettings(n(values,"modular.retoolingSeconds"), n(values,"modular.freightLookAheadSeconds"),
                n(values,"modular.chainBootstrapFactor"), deliveryEndSeconds,
                Math.min(Double.parseDouble(values.getOrDefault("tourRules.maxRouteDurationSeconds",
                        Integer.toString(RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS))),
                        Double.parseDouble(values.getOrDefault("tourRules.maxDriverTimeSeconds",
                        Integer.toString(RoutingDefaults.SOLVER_MAX_ROUTE_DURATION_SECONDS)))));
    }
    private static double n(Map<String,String> values,String key) { return Double.parseDouble(values.getOrDefault(key,DEFAULTS.get(key))); }
}
