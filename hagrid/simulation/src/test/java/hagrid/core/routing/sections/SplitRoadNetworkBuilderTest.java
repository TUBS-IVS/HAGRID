package hagrid.core.routing.sections;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SplitRoadNetworkBuilderTest {
 @Test void rejectsMissingInput() { assertThrows(IllegalArgumentException.class, () -> SplitRoadNetworkBuilder.build(null, null)); }
 @Test void splitPreservesLengthsAndPrivateDirectionsIncludingOriginMarker() {
  Network n=NetworkUtils.createNetwork(); var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0)); var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(5000,0));
  NetworkUtils.createAndAddLink(n,Id.createLinkId("f"),a,b,5000,10,1000,1); NetworkUtils.createAndAddLink(n,Id.createLinkId("r"),b,a,5000,10,1000,1);
  var f=new PreparedHaltCatalog.Road("a","b",5000,2*Math.hypot(2500,100),List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(2500,100),new PreparedHaltCatalog.Point(5000,0)));
  var r=new PreparedHaltCatalog.Road("b","a",5000,2*Math.hypot(2500,100),List.of(new PreparedHaltCatalog.Point(5000,0),new PreparedHaltCatalog.Point(2500,100),new PreparedHaltCatalog.Point(0,0)));
  var jobs=new ArrayList<PreparedHaltCatalog.Job>(); for(double x:List.of(0d,100d,180d,400d,900d)) jobs.add(new PreparedHaltCatalog.Job("j"+x,"f",List.of(new PreparedHaltCatalog.Approach("f",x/5000),new PreparedHaltCatalog.Approach("r",1-x/5000)),"", ""));
  var split=SplitRoadNetworkBuilder.build(n,new PreparedHaltCatalog("c",Map.of("f",f,"r",r),jobs,"f"));
  for(String id:List.of("f","r")) assertEquals(5000,split.children().get(Id.createLinkId(id)).stream().map(split.network().getLinks()::get).mapToDouble(Link::getLength).sum(),1e-8);
  var origin=split.jobApproaches().get("j0.0").getFirst().haltLinkId(); assertEquals(0,split.network().getLinks().get(origin).getLength());
  var f100=split.network().getLinks().get(split.jobApproaches().get("j100.0").getFirst().haltLinkId()); var f180=split.network().getLinks().get(split.jobApproaches().get("j180.0").getFirst().haltLinkId()); assertEquals(f100.getToNode(),f180.getFromNode()); assertEquals(80,f180.getLength(),1e-8);
  var reverse=split.network().getLinks().get(split.jobApproaches().get("j100.0").get(1).haltLinkId()); assertNotEquals(f100.getToNode(),reverse.getToNode());
 }
 @Test void splitDepotRetainsDownstreamEndpointAndAllIncomingDistance() {
  Network n=NetworkUtils.createNetwork(); var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0)); var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));
  var raw=NetworkUtils.createAndAddLink(n,Id.createLinkId("depot"),a,b,100,10,1000,1);
  var road=new PreparedHaltCatalog.Road("a","b",100,100,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(100,0)));
  var job=new PreparedHaltCatalog.Job("j","depot",List.of(new PreparedHaltCatalog.Approach("depot",.4)),"","");
  var result=SplitRoadNetworkBuilder.build(n,new PreparedHaltCatalog("c",Map.of("depot",road),List.of(job),"depot")); var links=result.children().get(raw.getId());
  assertEquals(raw.getToNode().getId(),result.network().getLinks().get(links.getLast()).getToNode().getId());
  assertEquals(100,links.stream().map(result.network().getLinks()::get).mapToDouble(Link::getLength).sum(),1e-9);
  assertEquals(result.network().getLinks().get(links.getFirst()).getToNode(),result.network().getLinks().get(links.getLast()).getFromNode());
 }

 @Test void rejectsInvalidLengthsAndGeometryWithJobIdentity() {
  Network n=NetworkUtils.createNetwork(); var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0)); var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0)); NetworkUtils.createAndAddLink(n,Id.createLinkId("f"),a,b,100,10,1000,1);
  var job=new PreparedHaltCatalog.Job("bad-job","f",List.of(new PreparedHaltCatalog.Approach("f",.5)),"","");
  var bad=new PreparedHaltCatalog.Road("a","b",101,100,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(100,0)));
  var exception=assertThrows(IllegalArgumentException.class,()->SplitRoadNetworkBuilder.build(n,new PreparedHaltCatalog("c",Map.of("f",bad),List.of(job),"f"))); assertTrue(exception.getMessage().contains("bad-job"));
  var badGeometry=new PreparedHaltCatalog.Road("a","b",100,200,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(100,0)));
  assertThrows(IllegalArgumentException.class,()->SplitRoadNetworkBuilder.build(n,new PreparedHaltCatalog("c",Map.of("f",badGeometry),List.of(job),"f")));
 }

 @Test void remapsPairRestrictionsAndRejectsLongerHistory() {
  Network n=NetworkUtils.createNetwork(); var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));var c=NetworkUtils.createAndAddNode(n,Id.createNodeId("c"),new Coord(200,0));var d=NetworkUtils.createAndAddNode(n,Id.createNodeId("d"),new Coord(300,0));
  var x=NetworkUtils.createAndAddLink(n,Id.createLinkId("x"),a,b,100,10,1000,1);NetworkUtils.createAndAddLink(n,Id.createLinkId("y"),b,c,100,10,1000,1);NetworkUtils.createAndAddLink(n,Id.createLinkId("z"),c,d,100,10,1000,1);
  var restriction=new org.matsim.core.network.turnRestrictions.DisallowedNextLinks();restriction.addDisallowedLinkSequence("car",List.of(Id.createLinkId("y")));NetworkUtils.setDisallowedNextLinks(x,restriction);
  var rx=new PreparedHaltCatalog.Road("a","b",100,100,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(100,0))); var ry=new PreparedHaltCatalog.Road("b","c",100,100,List.of(new PreparedHaltCatalog.Point(100,0),new PreparedHaltCatalog.Point(200,0)));
  var jobs=List.of(new PreparedHaltCatalog.Job("jx","x",List.of(new PreparedHaltCatalog.Approach("x",.5)),"",""),new PreparedHaltCatalog.Job("jy","y",List.of(new PreparedHaltCatalog.Approach("y",.5)),"","")); var catalog=new PreparedHaltCatalog("c",Map.of("x",rx,"y",ry),jobs,"x");
  var split=SplitRoadNetworkBuilder.build(n,catalog);var last=split.children().get(Id.createLinkId("x")).getLast(); var first=split.children().get(Id.createLinkId("y")).getFirst();assertTrue(NetworkUtils.getDisallowedNextLinks(split.network().getLinks().get(last)).getDisallowedLinkSequences("car").contains(List.of(first)));
  restriction.addDisallowedLinkSequence("car",List.of(Id.createLinkId("y"),Id.createLinkId("z")));assertThrows(IllegalArgumentException.class,()->SplitRoadNetworkBuilder.build(n,catalog));
 }

}
