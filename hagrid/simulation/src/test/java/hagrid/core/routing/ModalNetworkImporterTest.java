package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class ModalNetworkImporterTest {
    @TempDir Path directory;

    @Test
    void sharedLinkUnionsModesWithoutInventingAliases() {
        var car = network("shared", Set.of("car"));
        var bike = network("shared", Set.of("bike"));
        var merged = ModalNetworkImporter.merge(List.of(car, bike), Map.of());
        assertThat(merged.getLinks()).hasSize(1);
        assertThat(merged.getLinks().get(Id.createLinkId("shared")).getAllowedModes()).containsExactlyInAnyOrder("car", "bike");
        assertThat(car.getLinks().get(Id.createLinkId("shared")).getAllowedModes()).containsExactly("car");
    }

    @Test
    void preservesCarAndBikeExclusiveLinks() {
        var merged = ModalNetworkImporter.merge(List.of(network("car-only", Set.of("car")),
                network("bike-only", Set.of("bike"))), Map.of("bike", "cargobike"));
        assertThat(merged.getLinks()).hasSize(2);
        assertThat(merged.getLinks().get(Id.createLinkId("car-only")).getAllowedModes()).containsExactly("car");
        assertThat(merged.getLinks().get(Id.createLinkId("bike-only")).getAllowedModes()).containsExactly("cargobike");
    }

    @Test
    void conflictingSharedIdFails() {
        var car = network("shared", Set.of("car"));
        var bike = network("shared", Set.of("bike"));
        var original = bike.removeLink(Id.createLinkId("shared"));
        var reversed = bike.getFactory().createLink(original.getId(), original.getToNode(), original.getFromNode());
        reversed.setLength(100); reversed.setFreespeed(10); reversed.setCapacity(1000); reversed.setNumberOfLanes(1);
        reversed.setAllowedModes(Set.of("bike"));
        bike.addLink(reversed);
        assertThatIllegalArgumentException().isThrownBy(() ->
                ModalNetworkImporter.merge(List.of(car, bike), Map.of())).withMessageContaining("shared").withMessageContaining("direction");
    }

    @Test
    void conflictingNodeCoordinatesAndSharedSpeedsFailRatherThanOverwrite() {
        var car = network("shared", Set.of("car"));
        var bike = network("shared", Set.of("bike"));
        bike.getNodes().get(Id.createNodeId("b")).setCoord(new Coord(100, 1));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ModalNetworkImporter.merge(List.of(car, bike), Map.of())).withMessageContaining("coordinates");
        bike.getNodes().get(Id.createNodeId("b")).setCoord(new Coord(100, 0));
        bike.getLinks().get(Id.createLinkId("shared")).setFreespeed(5);
        assertThatIllegalArgumentException().isThrownBy(() ->
                ModalNetworkImporter.merge(List.of(car, bike), Map.of())).withMessageContaining("freespeed");
    }

    @Test
    void normalizesExplicitAliasInTurnRestrictionsAndDetachesAttributes() {
        var bike = network("bike-only", Set.of("bike"));
        var link = bike.getLinks().get(Id.createLinkId("bike-only"));
        NetworkUtils.addDisallowedNextLinks(link, "bike", List.of(Id.createLinkId("bike-only")));
        link.getAttributes().putAttribute("metadata", new java.util.ArrayList<>(List.of("original")));
        var merged = ModalNetworkImporter.merge(List.of(bike), Map.of("bike", "cargobike"));
        var target = merged.getLinks().get(link.getId());
        assertThat(NetworkUtils.getDisallowedNextLinks(target).getDisallowedLinkSequences("cargobike")).hasSize(1);
        assertThat(NetworkUtils.getDisallowedNextLinks(target).getDisallowedLinkSequences("bike")).isEmpty();
        link.getAttributes().putAttribute("metadata", List.of("changed"));
        assertThat(target.getAttributes().getAttribute("metadata")).isEqualTo(List.of("original"));
    }

    @Test
    void conflictingSharedAttributesFail() {
        var car = network("shared", Set.of("car"));
        var bike = network("shared", Set.of("bike"));
        car.getLinks().get(Id.createLinkId("shared")).getAttributes().putAttribute("zone", 1);
        bike.getLinks().get(Id.createLinkId("shared")).getAttributes().putAttribute("zone", 2);
        assertThatIllegalArgumentException().isThrownBy(() ->
                ModalNetworkImporter.merge(List.of(car, bike), Map.of())).withMessageContaining("zone");
    }

    @Test
    void preservesDetachedChangeEventsAndAppliesIdenticalImportedEventOnce() {
        var car = network("shared", Set.of("car"));
        var bike = network("shared", Set.of("bike"));
        var original = event(car, 2);
        event(bike, 2);
        var merged = ModalNetworkImporter.merge(List.of(car, bike), Map.of());
        assertThat(NetworkUtils.getNetworkChangeEvents(merged)).hasSize(1);
        var target = merged.getLinks().get(Id.createLinkId("shared"));
        assertThat(target.getFreespeed(0)).isEqualTo(10);
        assertThat(target.getFreespeed(100)).isEqualTo(2);
        original.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 9));
        assertThat(target.getFreespeed(100)).isEqualTo(2);
        assertThat(NetworkUtils.getNetworkChangeEvents(merged).peek().getLinks().iterator().next()).isSameAs(target);
    }

    @Test
    void conflictingOverlappingEventsFail() {
        var car = network("shared", Set.of("car"));
        var bike = network("shared", Set.of("bike"));
        event(car, 2);
        event(bike, 3);
        assertThatIllegalArgumentException().isThrownBy(() ->
                ModalNetworkImporter.merge(List.of(car, bike), Map.of())).withMessageContaining("overlapping").withMessageContaining("shared");
    }

    @Test
    void carOnlyNeedsNoBikeFile() {
        Path car = directory.resolve("car.xml");
        new NetworkWriter(network("car-only", Set.of("car"))).write(car.toString());
        var merged = ModalNetworkImporter.load(car, Optional.empty(), Map.of());
        assertThat(merged.getLinks()).hasSize(1);
        assertThat(merged.getLinks().get(Id.createLinkId("car-only")).getAllowedModes()).containsExactly("car");
    }

    @Test
    void aliasChainsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ModalNetworkImporter.merge(List.of(network("bike-only", Set.of("bike"))),
                        Map.of("bike", "cargobike", "cargobike", "cycle")))
                .withMessageContaining("directly");
    }

    @Test
    void repeatedIdenticalEventsWithinOneInputAreRejected() {
        var source = network("shared", Set.of("car"));
        var original = event(source, 2);
        NetworkUtils.setNetworkChangeEvents(source, List.of(original, original));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ModalNetworkImporter.merge(List.of(source), Map.of()))
                .withMessageContaining("within one input");
    }

    @Test
    void partiallyOverlappingFullEventLinkSetsAreNotSilentlyDeduplicated() {
        var car = network("shared", Set.of("car"));
        var bike = network("shared", Set.of("bike"));
        for (var entry : Map.of(car, "car-extra", bike, "bike-extra").entrySet()) {
            var source = entry.getKey();
            var shared = source.getLinks().get(Id.createLinkId("shared"));
            var extra = source.getFactory().createLink(Id.createLinkId(entry.getValue()),
                    shared.getFromNode(), shared.getToNode());
            extra.setLength(100); extra.setFreespeed(10); extra.setCapacity(1000);
            extra.setNumberOfLanes(1); extra.setAllowedModes(shared.getAllowedModes());
            source.addLink(extra);
            var change = new NetworkChangeEvent(100);
            change.addLink(shared); change.addLink(extra);
            change.setFreespeedChange(new NetworkChangeEvent.ChangeValue(
                    NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 2));
            NetworkUtils.setNetworkChangeEvents(source, List.of(change));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                ModalNetworkImporter.merge(List.of(car, bike), Map.of()))
                .withMessageContaining("overlapping").withMessageContaining("shared");
    }

    @Test
    void eventWithMissingTargetLinkCannotProduceAUsableMergedNetwork() {
        var source = network("shared", Set.of("car"));
        var reference = source.getLinks().get(Id.createLinkId("shared"));
        var missing = source.getFactory().createLink(Id.createLinkId("missing"),
                reference.getFromNode(), reference.getToNode());
        var change = new NetworkChangeEvent(100);
        change.addLink(missing);
        change.setFreespeedChange(new NetworkChangeEvent.ChangeValue(
                NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 2));
        NetworkUtils.setNetworkChangeEvents(source, List.of(change));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ModalNetworkImporter.merge(List.of(source), Map.of()))
                .withMessageContaining("missing");
    }

    @Test
    void compatibleModalTurnRestrictionsBothSurviveASharedLinkMerge() {
        var car = network("shared", Set.of("car"));
        var bike = network("shared", Set.of("bike"));
        NetworkUtils.addDisallowedNextLinks(car.getLinks().get(Id.createLinkId("shared")),
                "car", List.of(Id.createLinkId("shared")));
        NetworkUtils.addDisallowedNextLinks(bike.getLinks().get(Id.createLinkId("shared")),
                "bike", List.of(Id.createLinkId("shared")));
        var merged = ModalNetworkImporter.merge(List.of(car, bike), Map.of("bike", "cargobike"));
        var restrictions = NetworkUtils.getDisallowedNextLinks(merged.getLinks().get(Id.createLinkId("shared")));
        assertThat(restrictions.getDisallowedLinkSequences("car")).hasSize(1);
        assertThat(restrictions.getDisallowedLinkSequences("cargobike")).hasSize(1);
        assertThat(restrictions.getDisallowedLinkSequences("bike")).isEmpty();
    }

    private static Network network(String linkId, Set<String> modes) {
        var settings = new NetworkConfigGroup();
        settings.setTimeVariantNetwork(true);
        var network = NetworkUtils.createNetwork(settings);
        var a = network.getFactory().createNode(Id.createNodeId("a"), new Coord(0, 0));
        var b = network.getFactory().createNode(Id.createNodeId("b"), new Coord(100, 0));
        network.addNode(a); network.addNode(b);
        var link = network.getFactory().createLink(Id.createLinkId(linkId), a, b);
        link.setLength(100); link.setFreespeed(10); link.setCapacity(1000); link.setNumberOfLanes(1);
        link.setAllowedModes(modes);
        network.addLink(link);
        return network;
    }

    private static NetworkChangeEvent event(Network network, double speed) {
        var event = new NetworkChangeEvent(100);
        event.addLink(network.getLinks().get(Id.createLinkId("shared")));
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, speed));
        NetworkUtils.setNetworkChangeEvents(network, List.of(event));
        return event;
    }
}

