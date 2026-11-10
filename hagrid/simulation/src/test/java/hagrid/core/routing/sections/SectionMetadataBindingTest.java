package hagrid.core.routing.sections;
import hagrid.core.routing.RoadLeg;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import static org.assertj.core.api.Assertions.*;
class SectionMetadataBindingTest {
 @Test void freshCallsResolveReplacedAndMissingServicesAndPreserveBoundaryGuard() {
  var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));var id=Id.create("job",CarrierService.class);
  CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(id,Id.createLinkId("a")).setServiceDuration(1).build());
  var section=new DeliverySection("s",List.of("job"),0,List.of(new SectionVariant(-7,List.of("job"),List.of(Id.createLinkId("a")))));
  var catalog=new SectionCatalog(carrier,List.of(section),"binding");var type=VehicleUtils.createVehicleType(Id.create("t",VehicleType.class),"car");type.getCapacity().setOther(1);type.getCostInformation().setCostsPerMeter(0.0);type.getCostInformation().setCostsPerSecond(0.0);
  var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(100).build();
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link> a,Id<Link> b,VehicleType t){return new SectionLeg(0,0,0);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double d,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "binding";}};
  var current=new SectionRouteEvaluator(catalog,roads,100,100,0);var stage1=new SectionRouteEvaluatorStage1(catalog,roads,100,100,0);
  assertThat(current.evaluate(List.of("s"),vehicle,0)).isEqualTo(stage1.evaluate(List.of("s"),vehicle,0));
  catalog.originalCarrier().getServices().put(id,CarrierService.Builder.newInstance(id,Id.createLinkId("a")).setServiceDuration(9).build());
  assertThat(current.evaluate(List.of("s"),vehicle,0)).isEqualTo(stage1.evaluate(List.of("s"),vehicle,0));
  assertThat(current.evaluate(List.of("s"),vehicle,0).endTime()).isEqualTo(9);
  assertThatThrownBy(catalog::requireUnchanged).isInstanceOf(IllegalStateException.class);
  catalog.originalCarrier().getServices().remove(id);
  assertThatThrownBy(()->current.evaluate(List.of("s"),vehicle,0)).isInstanceOf(IllegalArgumentException.class).hasMessage("Missing service job");
 }
}

