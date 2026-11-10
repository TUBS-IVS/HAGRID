package hagrid.core.routing;

import java.util.*;

/** Explains the supported captured-time contract before preparing any graph or worker. */
public final class RoutingProfileCompatibility {
    public record Violation(String mode,String linkId,double boundarySeconds,String rule) {}
    private RoutingProfileCompatibility() {}
    public static List<Violation> check(RoutingContext context) {
        try(var lease=context.acquireQuery()) {
            var violations=new ArrayList<Violation>();var snapshot=context.snapshot();var settings=context.config().routing();
            for(String mode:new TreeSet<>(snapshot.modes())) {
                if(snapshot.isConstant(mode))continue;
                var profiles=context.vehicleProfiles().values().stream().filter(p->mode.equals(p.mode())).toList();
                boolean distanceOnly=!profiles.isEmpty()&&profiles.stream().allMatch(RoutingProfileCompatibility::distanceOnly);
                if(!distanceOnly)violations.addAll(snapshot.fifoViolations(mode));
                if(!distanceOnly&&settings.odTimeQuantizationSeconds()>0)violations.add(new Violation(mode,"",0,
                        "OD time quantization of variable profiles requires an independently proved FIFO contract"));
                for(var profile:context.vehicleProfiles().values().stream().sorted(Comparator.comparing(p->p.typeId().toString())).toList())
                    if(mode.equals(profile.mode()) && !distanceOnly(profile) && (profile.costPerMeter()!=0 || profile.costPerSecond()<=0))
                        violations.add(new Violation(mode,"",0,"Variable travel-time routing requires a proved monotonic pure-time objective for type "+profile.typeId()));
            }
            return List.copyOf(violations);
        }
    }
    static boolean distanceOnly(RoutingContext.VehicleProfile profile) {
        return profile.costPerMeter()>0&&profile.costPerSecond()==0;
    }
    static void requireSupported(RoutingContext context) {
        var violations=check(context);
        if(!violations.isEmpty()) {
            var first=violations.getFirst();
            throw new IllegalArgumentException(first.rule()+": mode "+first.mode()+", link "+first.linkId()+
                    ", boundary "+first.boundarySeconds()+" s ("+violations.size()+" profile violations)");
        }
    }
}
