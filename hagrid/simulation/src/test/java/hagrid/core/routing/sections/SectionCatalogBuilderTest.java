package hagrid.core.routing.sections;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SectionCatalogBuilderTest {
 @Test void rejectsInvalidSpan() { assertThrows(IllegalArgumentException.class, () -> SectionCatalogBuilder.build(null, null, 0, 160)); }
 @Test void contiguousSpanAndCapacityKeepAtomicJobs() {
  Network n=NetworkUtils.createNetwork(); var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0)); var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(1000,0)); NetworkUtils.createAndAddLink(n,Id.createLinkId("f"),a,b,1000,10,1000,1);
  var road=new PreparedHaltCatalog.Road("a","b",1000,1000,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(1000,0)));
  var jobs=new ArrayList<PreparedHaltCatalog.Job>(); var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));
  var type=VehicleUtils.createVehicleType(Id.create("v",VehicleType.class),"car"); type.getCapacity().setOther(160); carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(CarrierVehicle.newInstance(Id.createVehicleId("v"),Id.createLinkId("f"),type)).build());
  double[] positions={10,190,210,400,400,600,600}; int[] demands={10,10,10,90,80,1,1};
  for(int i=0;i<positions.length;i++){String id="j"+i; jobs.add(new PreparedHaltCatalog.Job(id,"f",List.of(new PreparedHaltCatalog.Approach("f",positions[i]/1000)),"", "")); CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(id,CarrierService.class),Id.createLinkId("f"),demands[i]).build());}
  var roads=SplitRoadNetworkBuilder.build(n,new PreparedHaltCatalog("c",Map.of("f",road),jobs,"f")); var catalog=SectionCatalogBuilder.build(carrier,roads,200,160);
  var wrong=SplitRoadNetworkBuilder.build(n,new PreparedHaltCatalog("other",Map.of("f",road),jobs,"f")); assertThrows(IllegalArgumentException.class,()->SectionCatalogBuilder.build(carrier,wrong,200,160));
  assertEquals(List.of("j0","j1","j2"),catalog.sections().getFirst().memberIds());
  assertTrue(catalog.sections().stream().noneMatch(s->s.memberIds().containsAll(List.of("j3","j4"))));
  assertTrue(catalog.sections().stream().anyMatch(s->s.memberIds().containsAll(List.of("j5","j6"))));
  assertEquals(7,catalog.sections().stream().mapToInt(s->s.memberIds().size()).sum()); assertEquals(202,catalog.sections().stream().mapToInt(DeliverySection::demand).sum());
  carrier.getAttributes().putAttribute("array",new int[]{1,2});
  var arrayCatalog=SectionCatalogBuilder.build(carrier,roads,200,160);
  carrier.getAttributes().putAttribute("array",new int[]{1,2});
  var sameArrayCatalog=SectionCatalogBuilder.build(carrier,roads,200,160);
  assertAll(
   () -> assertEquals(arrayCatalog.fingerprint(),sameArrayCatalog.fingerprint()),
   () -> { ((int[])arrayCatalog.originalCarrier().getAttributes().getAttribute("array"))[0]=99; assertThrows(IllegalStateException.class,arrayCatalog::requireUnchanged); });
  var original=carrier.getServices().get(Id.create("j0",CarrierService.class)); original.getAttributes().putAttribute("source", "changed"); assertNull(catalog.originalCarrier().getServices().get(original.getId()).getAttributes().getAttribute("source"));
  catalog.originalCarrier().getServices().get(original.getId()).getAttributes().putAttribute("source","tampered");assertThrows(IllegalStateException.class,catalog::requireUnchanged);
  var changedCatalog=SectionCatalogBuilder.build(carrier,roads,200,160);assertNotEquals(catalog.fingerprint(),changedCatalog.fingerprint());

 }
 @Test void twoWayVariantReversesMembersAndOversizeRemainsSingleton() {
  Network n=NetworkUtils.createNetwork(); var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0)); var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(1000,0)); NetworkUtils.createAndAddLink(n,Id.createLinkId("f"),a,b,1000,10,1000,1); NetworkUtils.createAndAddLink(n,Id.createLinkId("r"),b,a,1000,10,1000,1);
  var f=new PreparedHaltCatalog.Road("a","b",1000,1000,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(1000,0))); var r=new PreparedHaltCatalog.Road("b","a",1000,1000,List.of(new PreparedHaltCatalog.Point(1000,0),new PreparedHaltCatalog.Point(0,0)));
  var jobs=new ArrayList<PreparedHaltCatalog.Job>(); var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class)); var type=VehicleUtils.createVehicleType(Id.create("v",VehicleType.class),"car");type.getCapacity().setOther(160);carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(CarrierVehicle.newInstance(Id.createVehicleId("v"),Id.createLinkId("f"),type)).build());
  int[] demands={20,30,200}; for(int i=0;i<3;i++){String id="j"+i; double fraction=.1+i*.01;jobs.add(new PreparedHaltCatalog.Job(id,"f",List.of(new PreparedHaltCatalog.Approach("f",fraction),new PreparedHaltCatalog.Approach("r",1-fraction)),"", ""));CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(id,CarrierService.class),Id.createLinkId("f"),demands[i]).build());}
  var roads=SplitRoadNetworkBuilder.build(n,new PreparedHaltCatalog("c",Map.of("f",f,"r",r),jobs,"f")); var sections=SectionCatalogBuilder.build(carrier,roads,200,160).sections();
  assertEquals(List.of("j0","j1"),sections.getFirst().variants().getFirst().orderedJobIds()); assertEquals(List.of("j1","j0"),sections.getFirst().variants().get(1).orderedJobIds());assertEquals(List.of("j2"),sections.get(1).memberIds());assertEquals(200,sections.get(1).demand());
 }

 @Test void spanUsesCurveLengthRatherThanNetworkTravelLength() {
  Network n=NetworkUtils.createNetwork(); var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0)); var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(1000,0));NetworkUtils.createAndAddLink(n,Id.createLinkId("f"),a,b,1000,10,1000,1);
  double curve=2*Math.hypot(500,400);var road=new PreparedHaltCatalog.Road("a","b",1000,curve,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(500,400),new PreparedHaltCatalog.Point(1000,0)));
  var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));var type=VehicleUtils.createVehicleType(Id.create("v",VehicleType.class),"car");type.getCapacity().setOther(160);carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(CarrierVehicle.newInstance(Id.createVehicleId("v"),Id.createLinkId("f"),type)).build());var jobs=new ArrayList<PreparedHaltCatalog.Job>();
  for(int i=0;i<2;i++){String id="j"+i; jobs.add(new PreparedHaltCatalog.Job(id,"f",List.of(new PreparedHaltCatalog.Approach("f",.01+.18*i)),"",""));CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(id,CarrierService.class),Id.createLinkId("f"),1).build());}
  var roads=SplitRoadNetworkBuilder.build(n,new PreparedHaltCatalog("c",Map.of("f",road),jobs,"f"));assertEquals(2,SectionCatalogBuilder.build(carrier,roads,200,160).sections().size());
 }

}
