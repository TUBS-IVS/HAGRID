package hagrid.core.routing;

import hagrid.core.routing.sections.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Separate identity and sealing tests; consumer regression is in OrdinarySplitTurnConsumerTest. */
class OriginalRoadReverseTest {
    private static Link link(Network network,String id,String from,String to) {
        var f=network.getNodes().get(Id.createNodeId(from)); var t=network.getNodes().get(Id.createNodeId(to));
        var l=network.getFactory().createLink(Id.createLinkId(id),f,t); network.addLink(l); return l;
    }
    private static Network network() {
        var n=NetworkUtils.createNetwork(); for(String id:List.of("a","b","x","y")) n.addNode(n.getFactory().createNode(Id.createNodeId(id),new Coord(0,0))); return n;
    }
    private static void identity(Link l,String lineage,String from,String to) {
        l.getAttributes().putAttribute("sectionOriginalLinkId",lineage);
        l.getAttributes().putAttribute("sectionOriginalFromNodeId",from);
        l.getAttributes().putAttribute("sectionOriginalToNodeId",to);
    }
    @Test void partialBlankMissingLineageAndInconsistentLineageAreRejected() {
        var n=network(); var f=link(n,"f","a","x"); var next=link(n,"next","x","b");
        f.getAttributes().putAttribute("sectionOriginalFromNodeId","a");
        assertThatIllegalArgumentException().isThrownBy(()->OriginalRoadReverse.activityPair(f,next));
        f.getAttributes().putAttribute("sectionOriginalToNodeId","b");
        assertThatIllegalArgumentException().isThrownBy(()->OriginalRoadReverse.activityPair(f,next));
        f.getAttributes().putAttribute("sectionOriginalLinkId","");
        assertThatIllegalArgumentException().isThrownBy(()->OriginalRoadReverse.activityPair(f,next));
        f.getAttributes().putAttribute("sectionOriginalLinkId",123);
        assertThatIllegalArgumentException().isThrownBy(()->OriginalRoadReverse.activityPair(f,next));
        identity(f,"lineage","a","b"); identity(next,"lineage","a","y");
        assertThatIllegalArgumentException().isThrownBy(()->OriginalRoadReverse.activityPair(f,next));
        identity(next,"lineage","a","b");
        assertThat(OriginalRoadReverse.activityPair(f,next)).isFalse();
    }
    @Test void unsplitFallbackAndAdjacentRequirementAreDistinct() {
        var n=network(); var f=link(n,"f","a","b"); var r=link(n,"r","b","a");
        assertThat(OriginalRoadReverse.activityPair(f,r)).isTrue();
        assertThat(OriginalRoadReverse.roadTransition(f,r)).isTrue();
        var f2=link(n,"f2","x","b"); var r2=link(n,"r2","y","a");
        identity(f2,"f","a","b"); identity(r2,"r","b","a");
        assertThat(OriginalRoadReverse.activityPair(f2,r2)).isTrue();
        assertThat(OriginalRoadReverse.roadTransition(f2,r2)).isFalse();
    }
    @Test void fingerprintBindsEachSemanticAttribute() {
        var n=network(); var l=link(n,"child","a","x"); identity(l,"f","a","b");
        var original=SectionBindingCompanion.networkFingerprint(n);
        for(String key:List.of("sectionOriginalLinkId","sectionOriginalFromNodeId","sectionOriginalToNodeId")) {
            var prior=l.getAttributes().getAttribute(key); l.getAttributes().putAttribute(key,"changed");
            assertThat(SectionBindingCompanion.networkFingerprint(n)).as(key).isNotEqualTo(original);
            l.getAttributes().putAttribute(key,prior);
            assertThat(SectionBindingCompanion.networkFingerprint(n)).isEqualTo(original);
        }
    }
    @Test void endpointOnlyBindingDoesNotPersistOriginalEndpointAttributes() {
        var n=NetworkUtils.createNetwork(); var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0)); var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));
        var f=NetworkUtils.createAndAddLink(n,Id.createLinkId("f"),a,b,100,10,1000,1); f.setAllowedModes(Set.of("car"));
        var road=new PreparedHaltCatalog.Road("a","b",100,100,List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(100,0)));
        var job=new PreparedHaltCatalog.Job("j","f",List.of(new PreparedHaltCatalog.Approach("f",1)),"","");
        var split=SplitRoadNetworkBuilder.build(n,new PreparedHaltCatalog("other",Map.of("f",road),List.of(job),"f"));
        assertThat(split.children().get(f.getId())).hasSize(1);
        var child=split.network().getLinks().get(split.children().get(f.getId()).getFirst());
        assertThat(child.getAttributes().getAttribute("sectionOriginalFromNodeId")).isNull();
        assertThat(child.getAttributes().getAttribute("sectionOriginalToNodeId")).isNull();
    }
}
