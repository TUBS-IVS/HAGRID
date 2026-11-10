package hagrid.core.routing.sections;
import java.util.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
public record SplitRoadNetwork(Network network, Map<Id<Link>, List<Id<Link>>> children, Map<String, List<HaltApproach>> jobApproaches, String fingerprint, List<PreparedHaltCatalog.TurnEvidence> turnEvidence) {
 private static final class FrozenChildren extends AbstractMap<Id<Link>,List<Id<Link>>> {
  private final Map<Id<Link>,List<Id<Link>>> values;
  FrozenChildren(Map<Id<Link>,List<Id<Link>>> source) {var copy=new LinkedHashMap<Id<Link>,List<Id<Link>>>();source.forEach((k,v)->copy.put(k,List.copyOf(v)));values=Collections.unmodifiableMap(copy);}
  @Override public Set<Entry<Id<Link>,List<Id<Link>>>> entrySet(){return values.entrySet();}
  @Override public List<Id<Link>> get(Object key){return values.get(key);}
 }
 public SplitRoadNetwork(Network network,Map<Id<Link>,List<Id<Link>>> children,Map<String,List<HaltApproach>> jobApproaches,String fingerprint) {this(network,children,jobApproaches,fingerprint,List.of());}
 public SplitRoadNetwork { turnEvidence=List.copyOf(turnEvidence); if(!(children instanceof FrozenChildren))children=new FrozenChildren(children); var j = new LinkedHashMap<String,List<HaltApproach>>(); jobApproaches.forEach((k,v)->j.put(k,List.copyOf(v))); jobApproaches=Collections.unmodifiableMap(j); }
}

