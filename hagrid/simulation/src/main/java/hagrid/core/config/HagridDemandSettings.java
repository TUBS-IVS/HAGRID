package hagrid.core.config;

import hagrid.core.HagridConfig;
import java.nio.file.Path;
import java.util.*;

/** Explicit demand/pipeline bindings into the existing typed HagridConfig sections. */
final class HagridDemandSettings {
    private HagridDemandSettings() {}
    private static final Set<String> KEYS=Set.of(
            "paths.network",
            "paths.freightDemand",
            "paths.vehicleTypes",
            "paths.hubData",
            "paths.shippingPoints",
            "paths.parcelLockers",
            "supply.vehicleCapacity",
            "supply.durationPerStopSeconds",
            "supply.baselineCarriersFile",
            "hubs.limitDHL",
            "hubs.limitPost",
            "hubs.parcelLockerDemand",
            "hubs.parcelLockerDurationMinutes",
            "hubs.fixedParcelLockerDemand", "hubs.loadLegacyLocations", "demand.filterRegions",
            "network.minLinkLengthMeters",
            "network.minFreeSpeedMps",
            "network.freeSpeedThresholdMps",
            "providers.shapefileProviders",
            "providers.locationProviders",
            "fleet.activeSizes",
            "fleet.defaultDispatchHours",
            "tourRules.maxRouteDurationSeconds",
            "tourRules.vehicleAvailabilityBufferSeconds",
            "tourRules.durationPerParcelSeconds",
            "tourRules.maxDurationPerStopSeconds",
            "tourRules.maxDriverTimeSeconds",
            "tourRules.uTurnPenaltyCost",
            "fleet.cargoBikeMaxSpeedKmh",
            "demand.demandBorder",
            "demand.dhlBorder",
            "demand.carrierMergeThreshold",
            "optimization.initialIterations",
            "routing.transportCacheEnabled",
            "routing.maximumCacheEntries",
            "routing.maximumConcurrentRouters",
            "routing.odTimeQuantizationSeconds",
            "resources.internalSolverThreads",
            "resources.maxActiveSolvers",
            "results.carrierPlanCacheEnabled",
            "results.carrierPlanCacheDirectory", "tourRules.deliveryWindowStartSeconds", "tourRules.deliveryWindowEndSeconds", "tourRules.vehicleDayEndSeconds");
    static boolean contains(String key) {
        return KEYS.contains(key) || key.startsWith("providers.deliveryRates.")
                || key.startsWith("fleet.providerSizes.") || key.startsWith("fleet.providerDispatchHours.")
                || key.startsWith("fleet.providerTimeShiftHours.") || key.startsWith("supply.linkDirections.")
                || key.startsWith("supply.directionProbabilities.");
    }
    static boolean pathValued(String key) {
        return key.startsWith("paths.") || key.equals("supply.baselineCarriersFile") || key.equals("results.carrierPlanCacheDirectory");
    }
    /** Demand fields used by immutable routing/planning projections and their metadata. */
    static Map<String,String> defaultsValues(HagridConfig c) {
        Map<String,String> result=new LinkedHashMap<>();
        result.put("paths.network",c.paths().getNetwork());
        result.put("demand.filterRegions",c.getFilterRegions().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")));
        result.put("hubs.loadLegacyLocations",Boolean.toString(c.hubs().isLoadLegacyLocations()));
        result.put("hubs.fixedParcelLockerDemand",Boolean.toString(c.hubs().isFixedParcelLockerDemand()));
        result.put("paths.vehicleTypes",c.paths().getVehicleTypes());
        result.put("providers.shapefileProviders",String.join(",",c.providers().getShapefileProviders()));
        result.put("providers.locationProviders",String.join(",",c.providers().getLocationProviders()));
        result.put("fleet.activeSizes",String.join(",",c.vehicles().getActiveSizes()));
        c.providers().getAllDeliveryRates().forEach((provider,rate)->result.put("providers.deliveryRates."+provider,rate.toString()));
        return result;
    }
    static void apply(HagridConfig c,Map<String,String> values,Path base) {
        values.forEach((key,value)->{
            switch(key) {
            case "paths.network" -> c.paths().setNetwork(path(value, key, base));
            case "paths.freightDemand" -> c.paths().setFreightDemand(path(value, key, base));
            case "paths.vehicleTypes" -> c.paths().setVehicleTypes(path(value, key, base));
            case "paths.hubData" -> c.paths().setHubData(path(value, key, base));
            case "paths.shippingPoints" -> c.paths().setShippingPoints(path(value, key, base));
            case "paths.parcelLockers" -> c.paths().setParcelLockers(path(value, key, base));
            case "supply.vehicleCapacity" -> c.supply().setVehicleCapacity(positiveInt(value, key, base));
            case "supply.durationPerStopSeconds" -> c.supply().setDurationPerStopSeconds(positiveInt(value, key, base));
            case "supply.baselineCarriersFile" -> c.supply().setBaselineCarriersFile(path(value, key, base));
            case "hubs.limitDHL" -> c.hubs().setLimitDHL(nonnegativeInt(value, key, base));
            case "hubs.limitPost" -> c.hubs().setLimitPost(nonnegativeInt(value, key, base));
            case "hubs.parcelLockerDemand" -> c.hubs().setParcelLockerDemand(nonnegativeInt(value, key, base));
            case "hubs.parcelLockerDurationMinutes" -> c.hubs().setParcelLockerDurationMinutes(positiveInt(value, key, base));
            case "hubs.fixedParcelLockerDemand" -> c.hubs().setFixedParcelLockerDemand(bool(value, key, base));
            case "hubs.loadLegacyLocations" -> c.hubs().setLoadLegacyLocations(bool(value,key,base));
            case "demand.filterRegions" -> c.setFilterRegionsAsString(text(value,key,base));
            case "network.minLinkLengthMeters" -> c.network().setMinLinkLengthMeters(nonnegative(value, key, base));
            case "network.minFreeSpeedMps" -> c.network().setMinFreeSpeedMps(positive(value, key, base));
            case "network.freeSpeedThresholdMps" -> c.network().setFreeSpeedThresholdMps(positive(value, key, base));
            case "providers.shapefileProviders" -> c.providers().setShapefileProviders(strings(value, key, base));
            case "providers.locationProviders" -> c.providers().setLocationProviders(strings(value, key, base));
            case "fleet.activeSizes" -> c.vehicles().setActiveSizes(strings(value, key, base));
            case "fleet.defaultDispatchHours" -> c.vehicles().setDefaultDispatchHours(hours(value, key, base));
            case "tourRules.maxRouteDurationSeconds" -> c.routing().setSolverMaxRouteDurationSeconds(positiveInt(value, key, base));
            case "tourRules.vehicleDayEndSeconds" -> c.routing().setVehicleDayEndSeconds(positive(value,key,base));
            case "tourRules.vehicleAvailabilityBufferSeconds" -> c.routing().setVehicleAvailabilityBufferSeconds(nonnegativeInt(value, key, base));
            case "tourRules.durationPerParcelSeconds" -> c.routing().setDurationPerParcelMinutes(minutes(value, key, base));
            case "tourRules.maxDurationPerStopSeconds" -> c.routing().setMaxDurationPerStopMinutes(minutes(value, key, base));
            case "tourRules.maxDriverTimeSeconds" -> c.routing().setMaxDriverTimeMinutes(minuteDouble(value, key, base));
            case "tourRules.uTurnPenaltyCost" -> c.routing().setUTurnPenaltyCost(nonnegative(value, key, base));
            case "fleet.cargoBikeMaxSpeedKmh" -> c.vehicles().setCargoBikeMaxSpeedKmh(positive(value, key, base));
            case "demand.demandBorder" -> c.routing().setDemandBorder(positiveInt(value, key, base));
            case "demand.dhlBorder" -> c.routing().setDhlBorder(positiveInt(value, key, base));
            case "demand.carrierMergeThreshold" -> c.routing().setCarrierMergeThreshold(positiveInt(value, key, base));
            case "optimization.initialIterations" -> c.routing().setJspritIterations(nonnegativeInt(value, key, base));
            case "routing.transportCacheEnabled" -> c.routing().setTransportCacheEnabled(bool(value, key, base));
            case "routing.maximumCacheEntries" -> c.routing().setMaximumCacheEntries(positiveInt(value, key, base));
            case "routing.maximumConcurrentRouters" -> c.routing().setMaximumConcurrentRouters(positiveInt(value, key, base));
            case "routing.odTimeQuantizationSeconds" -> c.routing().setOdTimeQuantizationSeconds(nonnegative(value, key, base));
            case "resources.internalSolverThreads" -> c.resources().setInternalSolverThreads(positiveInt(value, key, base));
            case "resources.maxActiveSolvers" -> c.resources().setMaxActiveSolvers(positiveInt(value, key, base));
            case "results.carrierPlanCacheEnabled" -> c.setCarrierRoutingCacheEnabled(bool(value, key, base));
            case "results.carrierPlanCacheDirectory" -> c.setCarrierRoutingCacheDir(path(value, key, base));
            default -> {
                if(key.startsWith("providers.deliveryRates.")) {
                    int rate=nonnegativeInt(value,key,base);
                    if(rate>100) throw new IllegalArgumentException(key+" must be between 0 and 100");
                    c.providers().setDeliveryRate(suffix(key),rate);
                } else if(key.startsWith("fleet.providerSizes.")) c.vehicles().setProviderSizes(suffix(key),strings(value,key,base));
                else if(key.startsWith("fleet.providerDispatchHours.")) c.vehicles().setDispatchHours(suffix(key),hours(value,key,base));
                else if(key.startsWith("fleet.providerTimeShiftHours.")) c.vehicles().setProviderTimeShift(suffix(key),Integer.parseInt(value));
                else if(key.startsWith("supply.linkDirections.")) c.supply().setLinkDirection(suffix(key),text(value,key,base));
                else if(key.startsWith("supply.directionProbabilities.")) {
                    double share=nonnegative(value,key,base);
                    if(share>1) throw new IllegalArgumentException(key+" must be between 0 and 1");
                    c.supply().setDirectionProbability(suffix(key),share);
                }
            }
            }
        });
        if(!c.hubs().isLoadLegacyLocations() && c.hubs().isFixedParcelLockerDemand())
            throw new IllegalArgumentException("hubs.fixedParcelLockerDemand requires hubs.loadLegacyLocations=true");
        var old=c.routing().getDeliveryTimeWindow();
        c.routing().setDeliveryWindowSeconds(Double.parseDouble(values.getOrDefault("tourRules.deliveryWindowStartSeconds",Double.toString(old.getStart()))),
                Double.parseDouble(values.getOrDefault("tourRules.deliveryWindowEndSeconds",Double.toString(old.getEnd()))));
    }
    private static String suffix(String key){String result=key.substring(key.lastIndexOf('.')+1);if(result.isBlank())throw new IllegalArgumentException("Missing name in "+key);return result;}
    private static int positiveInt(String v,String k,Path b){int n=Integer.parseInt(v);if(n<=0)throw new IllegalArgumentException(k+" must be positive");return n;}
    private static int nonnegativeInt(String v,String k,Path b){int n=Integer.parseInt(v);if(n<0)throw new IllegalArgumentException(k+" must be nonnegative");return n;}
    private static double nonnegative(String v,String k,Path b){double n=Double.parseDouble(v);if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException(k+" must be finite and nonnegative");return n;}
    private static double positive(String v,String k,Path b){double n=nonnegative(v,k,b);if(n==0)throw new IllegalArgumentException(k+" must be positive");return n;}
    private static int minutes(String v,String k,Path b){int seconds=positiveInt(v,k,b);if(seconds%60!=0)throw new IllegalArgumentException(k+" needs whole minutes for demand generation");return seconds/60;}
    private static double minuteDouble(String v,String k,Path b){return positive(v,k,b)/60;}
    private static String text(String v,String k,Path b){if(v.isBlank())throw new IllegalArgumentException(k+" must not be blank");return v;}
    private static boolean bool(String v,String k,Path b){if(!v.equals("true")&&!v.equals("false"))throw new IllegalArgumentException(k+" needs true or false");return Boolean.parseBoolean(v);}
    private static String path(String v,String k,Path b){return b.resolve(text(v,k,b)).normalize().toAbsolutePath().toString();}
    private static List<String> strings(String v,String k,Path b){return Arrays.stream(v.split(",",-1)).map(s->text(s.trim(),k,b)).toList();}
    private static List<Integer> hours(String v,String k,Path b){return strings(v,k,b).stream().map(Integer::parseInt).peek(n->{if(n<0||n>23)throw new IllegalArgumentException(k+" hours must be 0..23");}).toList();}
}
