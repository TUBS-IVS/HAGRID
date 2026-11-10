package hagrid.core.routing.sections;
import java.util.*;
import org.matsim.api.core.v01.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
class ReverseOnlyBindingTest {
 static Carrier carrier(String id, String raw){var c=CarriersUtils.createCarrier(Id.create(id,Carrier.class)); var t=VehicleUtils.createVehicleType(Id.create(id+"v",VehicleType.class),"car"); t.getCapacity().setOther(10);c.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(CarrierVehicle.newInstance(Id.createVehicleId(id+"v"),Id.createLinkId("f"),t)).build());CarriersUtils.addService(c,CarrierService.Builder.newInstance(Id.create("same",CarrierService.class),Id.createLinkId(raw),1).build());return c;}
 static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
 @org.junit.jupiter.api.Test void preservesBinding(){
 var n=NetworkUtils.createNetwork();var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));NetworkUtils.createAndAddLink(n,Id.createLinkId("f"),a,b,100,10,1000,1);NetworkUtils.createAndAddLink(n,Id.createLinkId("r"),b,a,100,10,1000,1);
 var f=new PreparedHaltCatalog.Road("a","b",100,100,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(100,0)));var r=new PreparedHaltCatalog.Road("b","a",100,100,List.of(new PreparedHaltCatalog.Point(100,0),new PreparedHaltCatalog.Point(0,0)));
 var roads=Map.of("f",f,"r",r);var c1=new PreparedHaltCatalog("one",roads,List.of(new PreparedHaltCatalog.Job("same","f",List.of(new PreparedHaltCatalog.Approach("r",.75)),"HOME","one-stop")),"f");var c2=new PreparedHaltCatalog("two",roads,List.of(new PreparedHaltCatalog.Job("same","r",List.of(new PreparedHaltCatalog.Approach("f",.25)),"HOME","two-stop")),"f");
 var views=SplitRoadNetworkBuilder.buildUnion(n,List.of(c1,c2));var v1=views.get("one");var v2=views.get("two");check(v1.network()==v2.network(),"shared network");check(v1.jobApproaches().get("same").getFirst().originalLinkId().toString().equals("r"),"one reverse approach");check(v2.jobApproaches().get("same").getFirst().originalLinkId().toString().equals("f"),"two reverse approach");
 var catalog=SectionCatalogBuilder.build(carrier("one","f"),v1,50,10);check(catalog.sections().size()==1 && catalog.sections().getFirst().variants().size()==1,"one allowed direction");check(SectionCatalogBuilder.build(carrier("two","r"),v2,50,10).sections().size()==1,"second scoped binding");
 boolean rejected=false;try{SectionCatalogBuilder.build(carrier("one","r"),v1,50,10);}catch(IllegalArgumentException e){rejected=e.getMessage().contains("raw link mismatch");}check(rejected,"wrong original assigned link rejected");
 var bad=new PreparedHaltCatalog.Road("b","a",100,102,List.of(new PreparedHaltCatalog.Point(100,0),new PreparedHaltCatalog.Point(50,10),new PreparedHaltCatalog.Point(0,0)));var invalid=new PreparedHaltCatalog("bad",Map.of("f",f,"r",bad),List.of(new PreparedHaltCatalog.Job("same","f",List.of(new PreparedHaltCatalog.Approach("r",.75)),"HOME","x")),"f");rejected=false;try{SplitRoadNetworkBuilder.build(n,invalid);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"different geometries rejected");
 System.out.println("PASS reverse-only union bindings and mismatch checks");
 }
}
