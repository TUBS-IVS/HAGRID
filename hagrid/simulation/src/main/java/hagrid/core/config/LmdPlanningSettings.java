package hagrid.core.config;

import hagrid.core.HagridConfig;
import java.util.*;

/** Immutable LMD wave and missed-delivery policy; its full encoding participates in cache identity. */
public record LmdPlanningSettings(Map<String,String> values, Map<String,Integer> deliveryRates) {
    public static final int DEFAULT_WAVE_VEHICLE_COPIES = 4;
    private static final Map<String,String> DEFAULTS = Map.ofEntries(
            Map.entry("lmd.dispatchHours","8,14"), Map.entry("lmd.waveVehicleCopies","4"),
            Map.entry("lmd.missedDeliverySeed","4711"), Map.entry("lmd.tourRetimeSeed","20260730"),
            Map.entry("lmd.dhlDailyBiasPercent","2.5"), Map.entry("lmd.defaultDailyBiasPercent","5"),
            Map.entry("lmd.fallbackDeliveryRatePercent","90"), Map.entry("lmd.b2bDeliveryRatePercent","99"),
            Map.entry("lmd.maximumEffectiveDeliveryRatePercent","98"));

    public LmdPlanningSettings {
        Map<String,String> merged=new TreeMap<>(DEFAULTS);
        values.forEach((key,value)-> {
            if(key.equals("run.seed")) { RandomSeedSettings.validate(key,value); merged.put(key,value); }
            else if(contains(key)) { validate(key,value); merged.put(key,value); }
            else if(key.equals("fleet.largeDispatchJitterMinutes") || key.equals("fleet.defaultDispatchJitterMinutes")) {
                VehicleDispatchSettings.validate(key,value); merged.put(key,value);
            } else throw new IllegalArgumentException("Unknown LMD planning key: "+key);
        });
        var dispatch=VehicleDispatchSettings.defaultsValues();
        merged.putIfAbsent("fleet.largeDispatchJitterMinutes",dispatch.get("fleet.largeDispatchJitterMinutes"));
        merged.putIfAbsent("fleet.defaultDispatchJitterMinutes",dispatch.get("fleet.defaultDispatchJitterMinutes"));
        values=Map.copyOf(merged);
        deliveryRates=Map.copyOf(deliveryRates);
        deliveryRates.forEach((key,rate)-> { if(rate<0 || rate>100) throw new IllegalArgumentException("Invalid LMD provider rate: "+key); });
    }
    public static Map<String,String> defaultsValues() { return DEFAULTS; }
    public static boolean contains(String key) { return DEFAULTS.containsKey(key); }
    public static void validate(String key,String value) {
        if(key.equals("lmd.dispatchHours")) { hours(value); return; }
        if(key.endsWith("Seed")) { Long.parseLong(value); return; }
        if(key.equals("lmd.waveVehicleCopies")) { if(Integer.parseInt(value)<1) throw new IllegalArgumentException(key+" must be positive"); return; }
        double number=Double.parseDouble(value);
        if(!contains(key)||!Double.isFinite(number)||number<0||number>100) throw new IllegalArgumentException(key+" must be in [0,100]");
    }
    public static LmdPlanningSettings defaults() { return new LmdPlanningSettings(Map.of(),new HagridConfig().providers().getAllDeliveryRates()); }
    public static LmdPlanningSettings from(ResolvedHagridConfig config) {
        Map<String,String> selected=new LinkedHashMap<>();
        config.effectiveValues().forEach((key,value)-> {
            if(contains(key)||key.equals("fleet.largeDispatchJitterMinutes")||key.equals("fleet.defaultDispatchJitterMinutes")) selected.put(key,value);
        });
        String master = Long.toString(config.randomSeeds().seed());
        selected.put("run.seed", master);
        selected.put("lmd.missedDeliverySeed", master);
        selected.put("lmd.tourRetimeSeed", master);
        return new LmdPlanningSettings(selected,config.demand().deliveryRates());
    }
    public List<Integer> dispatchHours() { return hours(values.get("lmd.dispatchHours")); }
    private static List<Integer> hours(String value) {
        var result=Arrays.stream(value.split(",",-1)).map(String::trim).map(Integer::parseInt).toList();
        if(result.isEmpty()||new HashSet<>(result).size()!=result.size()||result.stream().anyMatch(h->h<0||h>23)) {
            throw new IllegalArgumentException("LMD dispatch hours must be distinct integers in [0,23]");
        }
        return result;
    }
    public int waveVehicleCopies() { return Integer.parseInt(values.get("lmd.waveVehicleCopies")); }
    public long runSeed() { return Long.parseLong(values.getOrDefault("run.seed", Long.toString(RandomSeedSettings.DEFAULT_SEED))); }
    public long missedDeliverySeed() { return Long.parseLong(values.get("lmd.missedDeliverySeed")); }
    public long tourRetimeSeed() { return Long.parseLong(values.get("lmd.tourRetimeSeed")); }
    public double dailyBiasPercent(String provider) { return n(provider.equals("dhl")?"lmd.dhlDailyBiasPercent":"lmd.defaultDailyBiasPercent"); }
    public double baseDeliveryRate(String provider) { return deliveryRates.containsKey(provider)?deliveryRates.get(provider):n("lmd.fallbackDeliveryRatePercent"); }
    public double b2bDeliveryRate() { return n("lmd.b2bDeliveryRatePercent"); }
    public double maximumEffectiveDeliveryRate() { return n("lmd.maximumEffectiveDeliveryRatePercent"); }
    public double jitterMinutes(String vehicleTypeId) { return n(vehicleTypeId.toLowerCase(Locale.ROOT).endsWith("_l")?"fleet.largeDispatchJitterMinutes":"fleet.defaultDispatchJitterMinutes"); }
    private double n(String key) { return Double.parseDouble(values.get(key)); }
    public List<String> encoded() {
        Map<String,String> sorted=new TreeMap<>(values);
        deliveryRates.forEach((key,value)->sorted.put("providers.deliveryRates."+key,Integer.toString(value)));
        return sorted.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList();
    }
    public static LmdPlanningSettings decode(List<String> encoded) {
        Map<String,String> values=new LinkedHashMap<>(); Map<String,Integer> rates=new LinkedHashMap<>();
        for(String token:encoded) {
            String[] pair=token.split("=",2);
            if(pair.length!=2) throw new IllegalArgumentException("Invalid LMD planning encoding");
            if(pair[0].startsWith("providers.deliveryRates.")) rates.put(pair[0].substring("providers.deliveryRates.".length()),Integer.parseInt(pair[1]));
            else if(values.putIfAbsent(pair[0],pair[1])!=null) throw new IllegalArgumentException("Duplicate encoded LMD setting: "+pair[0]);
        }
        return new LmdPlanningSettings(values,rates);
    }
}
