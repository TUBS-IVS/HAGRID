package hagrid.core.config;

import java.util.*;

/** HAGRID-owned DRT composition settings, in seconds/metres; native algorithms stay unchanged. */
public record DrtSettings(double stopDurationSeconds, double maxWaitTimeSeconds,
        double maxTravelTimeAlpha, double maxTravelTimeBetaSeconds, double zoneCellSizeMeters,
        int rebalancingIntervalSeconds, int demandEstimationPeriodSeconds,
        int requestRetryIntervalSeconds, double maxRequestAgeSeconds,
        double serviceBeginSeconds, double serviceEndSeconds, double returnWindowSeconds,
        double depotArrivalIntervalSeconds, double intermodalRadiusMeters, double intermodalSearchExtensionMeters,
        double rideScoringFactor, double teleportedPtSpeedKmh, double teleportedPtBeelineFactor,
        double ptFareDeflation, String fareZoneFile) {
    public DrtSettings {
        Objects.requireNonNull(fareZoneFile,"drt.fareZoneFile");
        Map<String,String> values=Map.ofEntries(
                Map.entry("drt.stopDurationSeconds",String.valueOf(stopDurationSeconds)),
                Map.entry("drt.maxWaitTimeSeconds",String.valueOf(maxWaitTimeSeconds)),
                Map.entry("drt.maxTravelTimeAlpha",String.valueOf(maxTravelTimeAlpha)),
                Map.entry("drt.maxTravelTimeBetaSeconds",String.valueOf(maxTravelTimeBetaSeconds)),
                Map.entry("drt.zoneCellSizeMeters",String.valueOf(zoneCellSizeMeters)),
                Map.entry("drt.rebalancingIntervalSeconds",String.valueOf(rebalancingIntervalSeconds)),
                Map.entry("drt.demandEstimationPeriodSeconds",String.valueOf(demandEstimationPeriodSeconds)),
                Map.entry("drt.requestRetryIntervalSeconds",String.valueOf(requestRetryIntervalSeconds)),
                Map.entry("drt.maxRequestAgeSeconds",String.valueOf(maxRequestAgeSeconds)),
                Map.entry("drt.serviceBeginSeconds",String.valueOf(serviceBeginSeconds)),
                Map.entry("drt.serviceEndSeconds",String.valueOf(serviceEndSeconds)),
                Map.entry("drt.returnWindowSeconds",String.valueOf(returnWindowSeconds)),
                Map.entry("drt.depotArrivalIntervalSeconds",String.valueOf(depotArrivalIntervalSeconds)),
                Map.entry("drt.intermodalRadiusMeters",String.valueOf(intermodalRadiusMeters)),
                Map.entry("drt.intermodalSearchExtensionMeters",String.valueOf(intermodalSearchExtensionMeters)),
                Map.entry("drt.rideScoringFactor",String.valueOf(rideScoringFactor)),
                Map.entry("drt.teleportedPtSpeedKmh",String.valueOf(teleportedPtSpeedKmh)),
                Map.entry("drt.teleportedPtBeelineFactor",String.valueOf(teleportedPtBeelineFactor)),
                Map.entry("drt.ptFareDeflation",String.valueOf(ptFareDeflation)),
                Map.entry("drt.fareZoneFile",String.valueOf(fareZoneFile)));
        values.forEach(DrtSettings::validate);
        if(serviceBeginSeconds>=serviceEndSeconds || returnWindowSeconds>serviceEndSeconds-serviceBeginSeconds)
            throw new IllegalArgumentException("DRT service window/return window is inconsistent");
        if(maxTravelTimeAlpha<1) throw new IllegalArgumentException("drt.maxTravelTimeAlpha must be at least 1");
    }
    private static final Map<String,String> DEFAULTS = Map.ofEntries(
        Map.entry("drt.stopDurationSeconds","60"), Map.entry("drt.maxWaitTimeSeconds","1200"),
        Map.entry("drt.maxTravelTimeAlpha","1.5"), Map.entry("drt.maxTravelTimeBetaSeconds","1200"),
        Map.entry("drt.zoneCellSizeMeters","2000"), Map.entry("drt.rebalancingIntervalSeconds","1800"),
        Map.entry("drt.demandEstimationPeriodSeconds","1800"), Map.entry("drt.requestRetryIntervalSeconds","300"),
        Map.entry("drt.maxRequestAgeSeconds","86400"), Map.entry("drt.serviceBeginSeconds","0"),
        Map.entry("drt.serviceEndSeconds","86400"), Map.entry("drt.returnWindowSeconds","5400"),
        Map.entry("drt.depotArrivalIntervalSeconds","1800"), Map.entry("drt.intermodalRadiusMeters","50000"),
        Map.entry("drt.intermodalSearchExtensionMeters","1000"), Map.entry("drt.rideScoringFactor","2"),
        Map.entry("drt.teleportedPtSpeedKmh","50"), Map.entry("drt.teleportedPtBeelineFactor","1.3"),
        Map.entry("drt.ptFareDeflation","1.16"),
        Map.entry("drt.fareZoneFile","./vvo_tarifzone20/v2.0_vvo_tarifzone20_hoyerswerda_utm32n.shp"));
    public static Map<String,String> defaultsValues() { return DEFAULTS; }
    public static boolean contains(String key) { return DEFAULTS.containsKey(key); }
    public static void validate(String key, String value) {
        if (!contains(key)) throw new IllegalArgumentException("Unknown DRT setting: " + key);
        if (key.equals("drt.fareZoneFile")) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " must not be blank");
            return;
        }
        double n=Double.parseDouble(value);
        boolean zeroAllowed = Set.of("drt.stopDurationSeconds","drt.maxWaitTimeSeconds", "drt.maxTravelTimeBetaSeconds",
                "drt.serviceBeginSeconds","drt.returnWindowSeconds").contains(key);
        if (!Double.isFinite(n) || n<0 || (!zeroAllowed && n==0))
            throw new IllegalArgumentException(key+" must be finite and "+(zeroAllowed?"nonnegative":"positive"));
        if (key.endsWith("IntervalSeconds") && !key.equals("drt.depotArrivalIntervalSeconds")
                || key.equals("drt.demandEstimationPeriodSeconds"))
            if(n != Math.rint(n) || n>Integer.MAX_VALUE) throw new IllegalArgumentException(key+" must be a positive integer");
    }
    public static DrtSettings defaults() { return from(Map.of()); }
    public static DrtSettings from(Map<String,String> input) {
        Map<String,String> v=new HashMap<>(DEFAULTS);
        input.forEach((k,x)->{if(contains(k)) {validate(k,x);v.put(k,x);}});
        DrtSettings result=new DrtSettings(n(v,"stopDurationSeconds"),n(v,"maxWaitTimeSeconds"),
                n(v,"maxTravelTimeAlpha"),n(v,"maxTravelTimeBetaSeconds"),n(v,"zoneCellSizeMeters"),
                (int)n(v,"rebalancingIntervalSeconds"),(int)n(v,"demandEstimationPeriodSeconds"),
                (int)n(v,"requestRetryIntervalSeconds"),n(v,"maxRequestAgeSeconds"),
                n(v,"serviceBeginSeconds"),n(v,"serviceEndSeconds"),n(v,"returnWindowSeconds"),
                n(v,"depotArrivalIntervalSeconds"),n(v,"intermodalRadiusMeters"),n(v,"intermodalSearchExtensionMeters"),
                n(v,"rideScoringFactor"),n(v,"teleportedPtSpeedKmh"),n(v,"teleportedPtBeelineFactor"),
                n(v,"ptFareDeflation"),v.get("drt.fareZoneFile"));
        return result;
    }
    private static double n(Map<String,String> v,String key){return Double.parseDouble(v.get("drt."+key));}
}
