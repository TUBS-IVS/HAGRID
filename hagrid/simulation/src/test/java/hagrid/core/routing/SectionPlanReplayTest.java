package hagrid.core.routing;
import hagrid.core.routing.sections.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.vehicles.*;
import static org.assertj.core.api.Assertions.*;
class SectionPlanReplayTest {
 @Test void lineageClassifierControlsTurnTariffAndUnknownIsRejected() {try(var f=new OptimizationFixture()) {
  var job=f.service("a",1,0,500);var plan=f.plan(0,job);
  SectionRoadCosts roads=new SectionRoadCosts(){public TurnClassification classifyTransition(Id<Link> a,Id<Link> b){return a.toString().equals("road")&&b.toString().equals("stop")?TurnClassification.TURN:TurnClassification.NOT_TURN;}public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){throw new UnsupportedOperationException();}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "x";}};
  var ordinary=PlanValidator.validate(f.carrier,plan,f.costs,f.config,1000,1);
  var section=PlanValidator.validateSections(f.carrier,plan,f.costs,f.config,1000,1,roads);
  assertThat(section.valid()).isTrue();assertThat(section.finalCost()).isEqualTo(ordinary.finalCost()+1);
  SectionRoadCosts unknown=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){throw new UnsupportedOperationException();}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "x";}};
  assertThat(PlanValidator.validateSections(f.carrier,plan,f.costs,f.config,1000,1,unknown).valid()).isFalse();
 }}
}
