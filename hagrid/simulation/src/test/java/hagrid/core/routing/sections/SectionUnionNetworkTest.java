package hagrid.core.routing.sections;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.core.network.NetworkUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionUnionNetworkTest {
 @Test void twoCarriersWithSameJobIdShareAllCutsAndKeepScopedBindings() {
  var n=NetworkUtils.createNetwork();
  var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));
  var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));
  NetworkUtils.createAndAddLink(n,Id.createLinkId("f"),a,b,100,10,1000,1);
  var road=new PreparedHaltCatalog.Road("a","b",100,100,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(100,0)));
  var one=new PreparedHaltCatalog("one",Map.of("f",road),List.of(new PreparedHaltCatalog.Job("same","f",List.of(new PreparedHaltCatalog.Approach("f",.2)),"A","x")),"f");
  var two=new PreparedHaltCatalog("two",Map.of("f",road),List.of(new PreparedHaltCatalog.Job("same","f",List.of(new PreparedHaltCatalog.Approach("f",.7)),"B","y")),"f");
  var views=SplitRoadNetworkBuilder.buildUnion(n,List.of(one,two));
  assertThat(views.keySet()).containsExactlyInAnyOrder("one","two");
  assertThat(views.get("one").network()).isSameAs(views.get("two").network());
  assertThat(views.get("one").children()).isSameAs(views.get("two").children());
  assertThatThrownBy(()->views.get("one").children().clear()).isInstanceOf(UnsupportedOperationException.class);
  assertThat(views.get("one").children().get(Id.createLinkId("f"))).hasSize(3);
  assertThat(views.get("one").jobApproaches().get("same").getFirst().haltLinkId()).isNotEqualTo(views.get("two").jobApproaches().get("same").getFirst().haltLinkId());
 }
}

