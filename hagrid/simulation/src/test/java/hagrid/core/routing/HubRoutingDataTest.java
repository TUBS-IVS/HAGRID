package hagrid.core.routing;

import hagrid.core.util.Hub;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import static org.assertj.core.api.Assertions.*;

class HubRoutingDataTest {
 private Hub hub(){
  var h=new Hub(Id.create("depot",Hub.class),"dhl",new Coord(10,20,30));h.setLink(Id.createLinkId("depot"));
  h.setAddress("street");h.setType("depot");h.setCapacityLimit(10);h.setAssignedSupplyDemand(4);h.setProviderWLSupplyDemand("dhl",3);
  h.getAttributes().putAttribute("nested",new ArrayList<>(List.of("first")));return h;
 }
 @Test void normalHubCopyRetainsDemandAndOwnsCoordinates(){
  var original=hub();var copy=new Hub(original);
  assertThat(copy.getCoord()).isNotSameAs(original.getCoord());
  assertThat(copy.getWLSupplyDemandByProvider()).containsEntry("dhl",3).isNotSameAs(original.getWLSupplyDemandByProvider());
 }
 @Test void routingCopyIsIndependentAndKeepsTheSameStructuredFingerprint(){
  var original=hub();var copy=(Hub)RoutingData.copy(original);
  assertThat(copy).isNotSameAs(original);assertThat(copy.getCoord()).isNotSameAs(original.getCoord());
  assertThat(RoutingData.hashValues(copy)).isEqualTo(RoutingData.hashValues(original));
  copy.setProvider("other");copy.setAssignedSupplyDemand(8);copy.setProviderWLSupplyDemand("dhl",9);
  copy.getAttributes().putAttribute("new","value");
  assertThat(original.getProvider()).isEqualTo("dhl");assertThat(original.getAssignedSupplyDemand()).isEqualTo(4);
  assertThat(original.getWLSupplyDemandByProvider()).containsEntry("dhl",3);
  assertThat(original.getAttributes().getAttribute("new")).isNull();
  @SuppressWarnings("unchecked") var nested=(List<String>)original.getAttributes().getAttribute("nested");nested.add("later");
  assertThat((List<?>)copy.getAttributes().getAttribute("nested")).hasSize(1);
 }
 @Test void allMutableHubStateAffectsTheFingerprint(){
  var original=hub();String first=RoutingData.hashValues(original);
  original.setAssignedSupplyDemand(5);assertThat(RoutingData.hashValues(original)).isNotEqualTo(first);
  String demand=RoutingData.hashValues(original);original.setCapacityLimit(2);assertThat(RoutingData.hashValues(original)).isNotEqualTo(demand);
  String capacity=RoutingData.hashValues(original);original.setProviderWLSupplyDemand("ups",4);assertThat(RoutingData.hashValues(original)).isNotEqualTo(capacity);
  String provider=RoutingData.hashValues(original);original.getAttributes().putAttribute("new","value");assertThat(RoutingData.hashValues(original)).isNotEqualTo(provider);
 }
 @Test void actualHubMetadataWorksThroughInitialSearchAndFullValidation(){try(var f=new OptimizationFixture()){
  f.service("a",1,0,500);f.carrier.getAttributes().putAttribute("hub",hub());
  var result=new CarrierOptimizationService().optimizeInitial(f.carrier,f.costs,0,25200,0);
  assertThat(result.iterations()).isZero();assertThat(result.diagnostics()).isEmpty();assertThat(result.unassignedJobIds()).isEmpty();
  assertThat(PlanValidator.validate(f.carrier,result.plan(),f.request,f.config).valid()).isTrue();
 }}
 @Test void unsupportedMetadataFailsBeforeAnyTransportWork(){try(var f=new OptimizationFixture()){
  f.service("a",1,0,500);f.carrier.getAttributes().putAttribute("opaque",new StringBuilder("unsupported"));
  var calls=new AtomicInteger();var costs=(VRPTransportCosts)Proxy.newProxyInstance(VRPTransportCosts.class.getClassLoader(),new Class<?>[]{VRPTransportCosts.class},(proxy,method,args)->{calls.incrementAndGet();return method.invoke(f.costs,args);});
  assertThatThrownBy(()->new CarrierOptimizationService().optimizeInitial(f.carrier,costs,0,25200,0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported mutable routing attribute");
  assertThat(calls).as("input metadata must be checked before constructing the transport problem").hasValue(0);
 }}
}