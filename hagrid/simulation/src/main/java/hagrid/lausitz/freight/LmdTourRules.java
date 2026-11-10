package hagrid.lausitz.freight;

import hagrid.core.HagridConfig;
import hagrid.core.config.LmdPlanningSettings;
import hagrid.core.config.ResolvedHagridConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Native preprocessing projection. Timing, dispatch and delivery policy all enter the cache key. */
public record LmdTourRules(int maxRouteDurationSeconds, int durationPerParcelMinutes, int maxDurationPerStopMinutes,
        double deliveryWindowStartSeconds, double deliveryWindowEndSeconds,
        double vehicleAvailabilityBufferSeconds, double vehicleDayEndSeconds, double uTurnPenaltyCost,
        LmdPlanningSettings planning) {
    public LmdTourRules {
        Objects.requireNonNull(planning,"planning");
        if(maxRouteDurationSeconds<=0 || durationPerParcelMinutes<=0 || maxDurationPerStopMinutes<=0)
            throw new IllegalArgumentException("LMD duration/handling values must be positive");
        for(double value:new double[]{deliveryWindowStartSeconds, deliveryWindowEndSeconds,
                vehicleAvailabilityBufferSeconds, vehicleDayEndSeconds, uTurnPenaltyCost}) {
            if(!Double.isFinite(value)||value<0) throw new IllegalArgumentException("LMD rules must be finite and nonnegative");
        }
        if(deliveryWindowStartSeconds>=deliveryWindowEndSeconds || deliveryWindowEndSeconds>vehicleDayEndSeconds)
            throw new IllegalArgumentException("LMD delivery window must fit the vehicle day");
    }
    public LmdTourRules(int cap,int parcelMinutes,int stopMinutes,double start,double end,double buffer,double dayEnd,double uTurn) {
        this(cap,parcelMinutes,stopMinutes,start,end,buffer,dayEnd,uTurn,LmdPlanningSettings.defaults());
    }
    public static LmdTourRules from(ResolvedHagridConfig config) {
        var rules=config.tourRules();
        return new LmdTourRules(integer(rules.maxRouteDurationSeconds(),"route duration"), minutes(rules.durationPerParcelSeconds()),
                minutes(rules.maxDurationPerStopSeconds()), rules.deliveryWindowStartSeconds(), rules.deliveryWindowEndSeconds(),
                rules.vehicleAvailabilityBufferSeconds(), rules.vehicleDayEndSeconds(), rules.uTurnPenaltyCost(), LmdPlanningSettings.from(config));
    }
    public static LmdTourRules defaults(LmdPreprocessInputs.Variant variant,int cap) {
        var rules=new HagridConfig().routing();
        return new LmdTourRules(cap,rules.getDurationPerParcelMinutes(),rules.getMaxDurationPerStopMinutes(),
                hagrid.lausitz.DeliveryDay.START_S,hagrid.lausitz.DeliveryDay.END_S,
                rules.getVehicleAvailabilityBufferSeconds(),rules.getVehicleDayEndSeconds(),0);
    }
    public List<String> encoded() {
        var values=new ArrayList<>(List.of(Integer.toString(maxRouteDurationSeconds),Integer.toString(durationPerParcelMinutes),
                Integer.toString(maxDurationPerStopMinutes),Double.toString(deliveryWindowStartSeconds),Double.toString(deliveryWindowEndSeconds),
                Double.toString(vehicleAvailabilityBufferSeconds),Double.toString(vehicleDayEndSeconds),Double.toString(uTurnPenaltyCost)));
        values.addAll(planning.encoded());
        return List.copyOf(values);
    }
    static LmdTourRules decode(List<String> values,int cap) {
        if(values.size()<8) throw new IllegalArgumentException("Invalid encoded LMD tour rules");
        return new LmdTourRules(cap,Integer.parseInt(values.get(1)),Integer.parseInt(values.get(2)),Double.parseDouble(values.get(3)),
                Double.parseDouble(values.get(4)),Double.parseDouble(values.get(5)),Double.parseDouble(values.get(6)),Double.parseDouble(values.get(7)),
                values.size()==8?LmdPlanningSettings.defaults():LmdPlanningSettings.decode(values.subList(8,values.size())));
    }
    private static int integer(double value,String name) {
        if(!Double.isFinite(value)||value<=0||value!=Math.rint(value)||value>Integer.MAX_VALUE)
            throw new IllegalArgumentException("LMD "+name+" must be a positive integer");
        return(int)value;
    }
    private static int minutes(double value) {
        int seconds=integer(value,"service seconds");
        if(seconds%60!=0) throw new IllegalArgumentException("LMD service durations need whole minutes");
        return seconds/60;
    }
}
