package hagrid.core.routing;

import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class RoutingContextTest {
    @Test
    void ownsFrozenNetworkGeometryAttributesAndVehicleProfiles() {
        var f = fixture();
        var context = context(f);
        String fingerprint = context.fingerprint();
        f.network.getLinks().get(Id.createLinkId("link")).setLength(500);
        f.network.getLinks().get(Id.createLinkId("link")).getAttributes().putAttribute("zone", 9);
        f.network.getNodes().get(Id.createNodeId("a")).setCoord(new Coord(900, 900));
        f.type.setMaximumVelocity(99);
        f.type.getCostInformation().setCostsPerSecond(9.0);
        var link = context.network().getLinks().get(Id.createLinkId("link"));
        assertThat(link.getLength()).isEqualTo(100);
        assertThat(link.getAttributes().getAttribute("zone")).isEqualTo(1);
        assertThat(link.getFromNode().getCoord().getX()).isEqualTo(0);
        assertThat(context.vehicleProfile(f.type.getId()).maximumVelocityMps()).isEqualTo(10);
        assertThat(context.vehicleProfile(f.type.getId()).costPerSecond()).isEqualTo(1);
        assertThat(context.fingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void publishedNetworkViewsRejectMutationAndReturnDetachedCoordinates() {
        var context = context(fixture());
        var network = context.network();
        var link = network.getLinks().get(Id.createLinkId("link"));
        assertThatThrownBy(() -> network.removeLink(link.getId())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> link.setLength(1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> network.getLinks().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> link.getAttributes().putAttribute("zone", 2)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> link.getFromNode().setCoord(new Coord(500,500))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(link.getFromNode().getCoord().getX()).isEqualTo(0);
    }

    @Test
    void preservesDetachedNetworkChangeEventsAndTimeDependentSpeeds() {
        var f = fixture();
        var link = f.network.getLinks().get(Id.createLinkId("link"));
        var event = new NetworkChangeEvent(100);
        event.addLink(link);
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 2));
        NetworkUtils.setNetworkChangeEvents(f.network, List.of(event));
        var context = context(f);
        var frozen = context.network().getLinks().get(link.getId());
        assertThat(frozen.getFreespeed(0)).isEqualTo(10);
        assertThat(frozen.getFreespeed(100)).isEqualTo(2);
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 5));
        var exported = NetworkUtils.getNetworkChangeEvents(context.network());
        assertThat(exported).hasSize(1);
        assertThat(exported.peek().getLinks().iterator().next()).isSameAs(frozen);
        exported.peek().setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 99));
        exported.clear();
        assertThat(NetworkUtils.getNetworkChangeEvents(context.network())).hasSize(1);
        assertThat(frozen.getFreespeed(100)).isEqualTo(2);
    }

    @Test
    void turnRestrictionAttributesAreDetachedOnCaptureAndRead() {
        var f = fixture();
        var restrictions = new DisallowedNextLinks();
        restrictions.addDisallowedLinkSequence("car", List.of(Id.createLinkId("next")));
        f.network.getLinks().get(Id.createLinkId("link")).getAttributes().putAttribute("disallowedNextLinks", restrictions);
        var context = context(f);
        restrictions.removeDisallowedLinkSequences("car");
        var attributes = context.network().getLinks().get(Id.createLinkId("link")).getAttributes();
        var copy = (DisallowedNextLinks) attributes.getAttribute("disallowedNextLinks");
        assertThat(copy.getDisallowedLinkSequences("car")).hasSize(1);
        copy.removeDisallowedLinkSequences("car");
        assertThat(((DisallowedNextLinks) attributes.getAttribute("disallowedNextLinks")).getDisallowedLinkSequences("car")).hasSize(1);
    }

    @Test
    void fingerprintIncludesNetworkVehicleCostsAndEffectiveRules() {
        var a = fixture();
        var b = fixture();
        assertThat(context(a).fingerprint()).isEqualTo(context(b).fingerprint());
        b.type.getCostInformation().setCostsPerSecond(2.0);
        assertThat(context(a).fingerprint()).isNotEqualTo(context(b).fingerprint());
        b = fixture();
        b.network.getLinks().get(Id.createLinkId("link")).setAllowedModes(Set.of("car"));
        assertThat(context(a).fingerprint()).isNotEqualTo(context(b).fingerprint());
    }

    @Test
    void rejectsMissingModeSourcesAndInconsistentTypeKeys() {
        var f = fixture();
        f.type.setNetworkMode("walk");
        var missingModeFixture = f;
        assertThatIllegalArgumentException().isThrownBy(() -> context(missingModeFixture)).withMessageContaining("walk");
        f = fixture();
        var invalid = new CarrierVehicleTypes();
        invalid.getVehicleTypes().put(Id.create("other", VehicleType.class), f.type);
        var network = f.network;
        var snapshot = snapshot(network);
        assertThatIllegalArgumentException().isThrownBy(() ->
                RoutingContext.create(network, invalid, snapshot, config())).withMessageContaining("type");
    }

    @Test
    void closeIsIdempotentAndOnlyInvalidatesOwnAccess() {
        var f = fixture();
        var snapshot = snapshot(f.network);
        var a = RoutingContext.create(f.network, f.types, snapshot, config());
        var b = RoutingContext.create(f.network, f.types, snapshot, config());
        String fingerprint = a.fingerprint();
        a.close();
        a.close();
        assertThatIllegalStateException().isThrownBy(a::network).withMessageContaining("closed");
        assertThatIllegalStateException().isThrownBy(a::snapshot).withMessageContaining("closed");
        assertThat(b.network().getLinks()).hasSize(1);
        assertThat(snapshot.forMode("car").getLinkTravelTime(f.network.getLinks().get(Id.createLinkId("link")), 0, null, null)).isEqualTo(10);
        assertThat(a.fingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void refusesNetworkWhoseLengthsChangedAfterTravelTimeCapture() {
        var f=fixture();
        var captured=snapshot(f.network);
        f.network.getLinks().get(Id.createLinkId("link")).setLength(200);
        assertThatIllegalArgumentException().isThrownBy(() ->
                RoutingContext.create(f.network,f.types,captured,config())).withMessageContaining("snapshot").withMessageContaining("link");
    }

    @Test
    void opaqueMutableAttributesCannotSilentlyLeakIntoTheContext() {
        var f=fixture();
        f.network.getLinks().get(Id.createLinkId("link")).getAttributes().putAttribute("mutable",new java.util.concurrent.atomic.AtomicInteger(1));
        assertThatIllegalArgumentException().isThrownBy(() -> context(f)).withMessageContaining("Unsupported mutable routing attribute");
    }
    @Test
    void refusesChangedTopologyWithTheSameCapturedLinkIdsAndLengths() {
        var f=fixture();
        var captured=snapshot(f.network);
        var previous=f.network.removeLink(Id.createLinkId("link"));
        var replacement=f.network.getFactory().createLink(previous.getId(),previous.getToNode(),previous.getFromNode());
        replacement.setLength(previous.getLength()); replacement.setFreespeed(previous.getFreespeed());
        replacement.setCapacity(previous.getCapacity()); replacement.setNumberOfLanes(previous.getNumberOfLanes());
        replacement.setAllowedModes(previous.getAllowedModes()); replacement.getAttributes().putAttribute("zone",1);
        f.network.addLink(replacement);
        assertThatIllegalArgumentException().isThrownBy(() ->
                RoutingContext.create(f.network,f.types,captured,config())).withMessageContaining("snapshot");
    }

    @Test
    void refusesChangedCoordinatesBetweenTravelTimeAndContextCapture() {
        var f=fixture();
        var captured=snapshot(f.network);
        f.network.getNodes().get(Id.createNodeId("b")).setCoord(new Coord(100,50));
        assertThatIllegalArgumentException().isThrownBy(() ->
                RoutingContext.create(f.network,f.types,captured,config())).withMessageContaining("snapshot");
    }

    @Test
    void refusesNewBackgroundChangeEventsAfterTravelTimeCapture() {
        var f=fixture();
        var captured=snapshot(f.network);
        var event=new NetworkChangeEvent(100);
        event.addLink(f.network.getLinks().get(Id.createLinkId("link")));
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS,2));
        NetworkUtils.setNetworkChangeEvents(f.network,List.of(event));
        assertThatIllegalArgumentException().isThrownBy(() ->
                RoutingContext.create(f.network,f.types,captured,config())).withMessageContaining("snapshot");
    }

    @Test
    void acceptsAnEquivalentDetachedGraphForTheSameSnapshot() {
        var f = fixture();
        var captured = snapshot(f.network);
        try (var context = RoutingContext.create(new RoutingNetworkSnapshot(f.network), f.types, captured, config())) {
            assertThat(context.network().getLinks()).hasSize(1);
        }
    }

    @Test
    void refusesChangedExistingEventPayloadAfterTravelTimeCapture() {
        var f = fixture();
        var event = new NetworkChangeEvent(100);
        event.addLink(f.network.getLinks().get(Id.createLinkId("link")));
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 2));
        NetworkUtils.setNetworkChangeEvents(f.network, List.of(event));
        var captured = snapshot(f.network);
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 5));
        assertThatIllegalArgumentException().isThrownBy(() ->
                RoutingContext.create(f.network, f.types, captured, config())).withMessageContaining("snapshot");
    }

    @Test
    void refusesChangedTurnRestrictionsAfterTravelTimeCapture() {
        var f = fixture();
        var captured = snapshot(f.network);
        var restrictions = new DisallowedNextLinks();
        restrictions.addDisallowedLinkSequence("car", List.of(Id.createLinkId("next")));
        NetworkUtils.setDisallowedNextLinks(f.network.getLinks().get(Id.createLinkId("link")), restrictions);
        assertThatIllegalArgumentException().isThrownBy(() ->
                RoutingContext.create(f.network, f.types, captured, config())).withMessageContaining("snapshot");
    }

    private static RoutingContext context(Fixture f) { return RoutingContext.create(f.network, f.types, snapshot(f.network), config()); }
    private static hagrid.core.config.ResolvedHagridConfig config() {
        return HagridConfigResolver.resolve(new HagridConfig(), HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of()), Map.of());
    }
    private static TravelTimeSnapshot snapshot(Network network) {
        return TravelTimeSnapshot.capture(network, Map.of(), Map.of("car", (l,t,p,v) -> l.getLength()/l.getFreespeed(t)), 25, 3600, 900);
    }
    private static Fixture fixture() {
        var config = ConfigUtils.createConfig();
        config.network().setTimeVariantNetwork(true);
        var network = NetworkUtils.createNetwork(config);
        var a = network.getFactory().createNode(Id.createNodeId("a"), new Coord(0,0));
        var b = network.getFactory().createNode(Id.createNodeId("b"), new Coord(100,0));
        network.addNode(a); network.addNode(b);
        var link = network.getFactory().createLink(Id.createLinkId("link"), a, b);
        link.setLength(100); link.setFreespeed(10); link.setNumberOfLanes(1); link.setCapacity(1000);
        link.setAllowedModes(Set.of("car","bike")); link.getAttributes().putAttribute("zone",1);
        network.addLink(link);
        var type = VehicleUtils.createVehicleType(Id.create("van", VehicleType.class), "car");
        type.setMaximumVelocity(10);
        type.getCostInformation().setFixedCost(1.0).setCostsPerSecond(1.0).setCostsPerMeter(0.0);
        var types = new CarrierVehicleTypes(); types.getVehicleTypes().put(type.getId(),type);
        return new Fixture(network,types,type);
    }
    private record Fixture(Network network, CarrierVehicleTypes types, VehicleType type) {}
}