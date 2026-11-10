package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LinkRoutingContractTest {
    @Test void forbiddenAdjacentLinksAreNotAZeroPath() {
        var f=new Fixture(false);f.restrict("o",List.of("d"),"car");
        assertThatExceptionOfType(NoRouteException.class).isThrownBy(() -> f.route("o","d","car"));
    }
    @Test void forbiddenDestinationTransitionUsesTheActualAlternative() {
        var f=new Fixture(true);f.restrict("o",List.of("d"),"car");
        var leg=f.route("o","d","car");
        assertThat(leg.route().getLinkIds()).containsExactly(Id.createLinkId("x"),Id.createLinkId("y"));
        assertThat(leg.travelTimeSeconds()).isEqualTo(30);
        assertThat(leg.distanceMeters()).isEqualTo(300);
        assertThat(leg.monetaryCost()).isEqualTo(60);
        assertThat(f.route("o","d","bike").travelTimeSeconds()).isEqualTo(10);
    }
    @Test void fullMultiLinkRestrictionsApplyAtTheDestination() {
        var f=new Fixture(true);f.restrict("o",List.of("x","y","d"),"car");f.restrict("o",List.of("d"),"car");
        // The second loop breaks the prohibited prefix: it is a legal longer alternative.
        assertThat(f.route("o","d","car").route().getLinkIds()).containsExactly(
                Id.createLinkId("x"),Id.createLinkId("y"),Id.createLinkId("x"),Id.createLinkId("y"));
        assertThat(TurnRestrictionValidator.validate(f.network,"car",List.of(Id.createLinkId("o"),Id.createLinkId("x"),Id.createLinkId("y"),Id.createLinkId("d"))))
                .anyMatch(v -> v.contains("restriction"));
    }
    @Test void equalLinksCostZeroAndReturnedRoutesAreDetached() {
        var f=new Fixture(true);var zero=f.route("o","o","car");
        assertThat(zero.travelTimeSeconds()).isZero();assertThat(zero.distanceMeters()).isZero();
        var route=RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("o"),Id.createLinkId("d"));
        route.setLinkIds(Id.createLinkId("o"),List.of(Id.createLinkId("x"),Id.createLinkId("y")),Id.createLinkId("d"));
        var leg=new RoadLeg(route,30,300,60);route.setLinkIds(Id.createLinkId("o"),List.of(),Id.createLinkId("d"));
        assertThat(leg.route().getLinkIds()).hasSize(2);
        var exposed=leg.route();exposed.setLinkIds(Id.createLinkId("o"),List.of(),Id.createLinkId("d"));
        assertThat(leg.route().getLinkIds()).hasSize(2);
    }
    @Test void unrestrictedAdjacentLinksCountDestinationExactlyOnce() {
        var f=new Fixture(true);var leg=f.route("o","d","car");
        assertThat(leg.route().getLinkIds()).isEmpty();
        assertThat(leg.travelTimeSeconds()).isEqualTo(10);assertThat(leg.distanceMeters()).isEqualTo(100);
        assertThat(leg.monetaryCost()).isEqualTo(20);
    }
    static final class Fixture {
        final Network network=NetworkUtils.createNetwork();
        Fixture(boolean detour) {
            for(String id:List.of("a","b","c","z"))NetworkUtils.createAndAddNode(network,Id.createNodeId(id),new Coord(id.charAt(0),0));
            link("o","a","b");link("d","b","c");if(detour){link("x","b","z");link("y","z","b");}
        }
        void link(String id,String a,String b) {
            var link=NetworkUtils.createAndAddLink(network,Id.createLinkId(id),network.getNodes().get(Id.createNodeId(a)),network.getNodes().get(Id.createNodeId(b)),100,10,1000,1);
            link.setAllowedModes(Set.of("car","bike"));
        }
        void restrict(String on,List<String> next,String mode) {
            var link=network.getLinks().get(Id.createLinkId(on));var d=NetworkUtils.getDisallowedNextLinks(link);
            if(d==null){d=new DisallowedNextLinks();NetworkUtils.setDisallowedNextLinks(link,d);}
            d.addDisallowedLinkSequence(mode,next.stream().map(Id::createLinkId).toList());
        }
        RoadLeg route(String from,String to,String mode) {
            var type=VehicleUtils.createVehicleType(Id.create("v-"+mode,VehicleType.class),mode);
            type.getCostInformation().setCostsPerMeter(.1).setCostsPerSecond(1.0);
            var v=VehicleUtils.createVehicle(Id.createVehicleId("v"),type);
            return new RestrictedLinkPathFinder(network,mode,(l,t,p,vehicle) -> l.getLength()/10)
                    .route(Id.createLinkId(from),Id.createLinkId(to),0,v);
        }
    }
}
