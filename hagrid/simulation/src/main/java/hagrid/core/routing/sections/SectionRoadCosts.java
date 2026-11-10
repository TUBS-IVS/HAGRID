package hagrid.core.routing.sections;
import hagrid.core.routing.RoadLeg;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.vehicles.*;
public interface SectionRoadCosts {
 enum TurnClassification { TURN, NOT_TURN, UNKNOWN }
 /** NETWORK_REVERSAL charges the upper bound for an unresolved reversal without claiming physical proof. */
 enum TurnPolicy {
  STRICT, NETWORK_REVERSAL;
  public boolean charges(TurnClassification classification) {
   java.util.Objects.requireNonNull(classification,"Missing turn classification");
   if(classification==TurnClassification.UNKNOWN && this==STRICT)throw new IllegalStateException("Uncertifiable road transition under STRICT policy");
   return classification!=TurnClassification.NOT_TURN;
  }
 }
 default TurnPolicy turnPolicy() { return TurnPolicy.STRICT; }
 default boolean chargesTransition(Id<Link> from,Id<Link> to) { return turnPolicy().charges(classifyTransition(from,to)); }
 default TurnClassification classifyTransition(Id<Link> from,Id<Link> to) { return TurnClassification.UNKNOWN; }
 SectionLeg leg(Id<Link> from, Id<Link> to, VehicleType type);
 RoadLeg materialize(Id<Link> from, Id<Link> to, double departure, Vehicle vehicle);
 default Id<Link> resolveOriginalLink(Id<Link> original) { return original; }
 String fingerprint();
}

