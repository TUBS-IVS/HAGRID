package hagrid.core.routing;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import java.util.*;
/** Independent full-sequence check, including the origin and destination transitions. */
public final class TurnRestrictionValidator {
    private TurnRestrictionValidator() {}
    public static List<String> validate(Network network,String mode,List<Id<Link>> sequence) {
        var errors=new ArrayList<String>();
        for(int i=0;i<sequence.size();i++) {
            var link=network.getLinks().get(sequence.get(i));
            if(link==null||!link.getAllowedModes().contains(mode)){errors.add("Missing/mode-forbidden road link " + sequence.get(i));continue;}
            var restrictions=NetworkUtils.getDisallowedNextLinks(link);
            if(restrictions==null)continue;
            for(var forbidden:restrictions.getDisallowedLinkSequences(mode)) {
                if(sequence.size()-i-1>=forbidden.size() && sequence.subList(i+1,i+1+forbidden.size()).equals(forbidden))
                    errors.add("Turn restriction on " + link.getId() + " forbids " + forbidden);
            }
        }
        return List.copyOf(errors);
    }
    static boolean hasRestrictions(Network network,String mode) {
        return network.getLinks().values().stream().anyMatch(l -> {
            var restrictions=NetworkUtils.getDisallowedNextLinks(l);
            return restrictions!=null&&!restrictions.getDisallowedLinkSequences(mode).isEmpty();
        });
    }
}
