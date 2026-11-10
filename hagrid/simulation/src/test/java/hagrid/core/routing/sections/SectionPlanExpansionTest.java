package hagrid.core.routing.sections;
import hagrid.core.routing.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionPlanExpansionTest {
 @Test void realNativeSolutionExpandsOriginalJobAndStoredRoads() {
  var carrier=CarriersUtils.createCarrier(Id.create("native",Carrier.class));
  var service=CarrierService.Builder.newInstance(Id.create("j",CarrierService.class),Id.createLinkId("raw"),1).setServiceDuration(7).setServiceStartingTimeWindow(TimeWindow.newInstance(100,200)).build();
  service.getAttributes().putAttribute("source", "original");CarriersUtils.addService(carrier,service);
  var type=VehicleUtils.createVehicleType(Id.create("type",VehicleType.class),"car");type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(0.0);type.getCostInformation().setCostsPerSecond(0.0);type.getCostInformation().setFixedCost(5.0);
  var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("vehicle"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(300).build();
  carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(v).setFleetSize(CarrierCapabilities.FleetSize.FINITE).build());
  var net=NetworkUtils.createNetwork();var n1=NetworkUtils.createAndAddNode(net,Id.createNodeId("n1"),new Coord(0,0));var n2=NetworkUtils.createAndAddNode(net,Id.createNodeId("n2"),new Coord(10,0));
  NetworkUtils.createAndAddLink(net,Id.createLinkId("d"),n1,n2,10,1,100,1);NetworkUtils.createAndAddLink(net,Id.createLinkId("halt"),n2,n1,10,1,100,1);
  var variant=new SectionVariant(0,List.of("j"),List.of(Id.createLinkId("halt")));var catalog=new SectionCatalog(carrier,List.of(new DeliverySection("s",List.of("j"),1,List.of(variant))),"fixture");
  SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){return new SectionLeg(10,10,0);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double departure,Vehicle vehicle){var route=RouteUtils.createLinkNetworkRouteImpl(a,b);route.setVehicleId(vehicle.getId());route.setDistance(10);route.setTravelTime(10);return new RoadLeg(route,10,10,0);}public String fingerprint(){return "fixture";}};
  var result=new CarrierOptimizationService().searchSections(catalog,net,roads,300,300,0,4711,new SectionSearchPolicy(2,.25,.4));
  var expanded=SectionPlanExpander.expand(result,catalog,roads);
  assertThat(expanded.bindings()).containsEntry("j",Id.createLinkId("halt"));assertThat(expanded.carrier().getServices().get(service.getId()).getServiceDuration()).isEqualTo(7);
  var elements=expanded.plan().getScheduledTours().iterator().next().getTour().getTourElements();
  assertThat(elements.stream().filter(e->e instanceof Tour.ServiceActivity)).hasSize(1);
  assertThat(elements.stream().filter(e->e instanceof Tour.Leg).map(e->((org.matsim.core.population.routes.NetworkRoute)((Tour.Leg)e).getRoute()).getVehicleId())).containsOnlyNulls();
  assertThat(SectionBindingValidator.validatePlan(catalog,expanded.carrier(),expanded.plan(),expanded.bindings())).isEmpty();
 }
}
