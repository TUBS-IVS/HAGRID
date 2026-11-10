package hagrid.core.routing;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.TimeDependentNetwork;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.utils.objectattributes.attributable.Attributes;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Detached modal topology with original units and events restricted to retained links. */
public final class ModalRoutingNetwork {
    private ModalRoutingNetwork() {}

    public static Network forMode(Network source, String mode) {
        return forMode(source, mode, link -> true);
    }

    public static Network forMode(Network source, String mode, Predicate<Link> eligible) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(eligible, "eligible links");
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("Network requires an explicit mode");
        var settings = new NetworkConfigGroup();
        settings.setTimeVariantNetwork(source instanceof TimeDependentNetwork);
        Network result = NetworkUtils.createNetwork(settings);
        new TransportModeNetworkFilter(source).filter(result, Set.of(mode));
        result.setName(source.getName());
        result.setCapacityPeriod(source.getCapacityPeriod());
        result.setEffectiveCellSize(source.getEffectiveCellSize());
        result.setEffectiveLaneWidth(source.getEffectiveLaneWidth());
        copyAttributes(source.getAttributes(), result.getAttributes());
        for (var node : result.getNodes().values()) {
            node.setCoord(RoutingData.coord(node.getCoord()));
            copyAttributes(node.getAttributes(), node.getAttributes());
        }
        for (var link : new ArrayList<>(result.getLinks().values())) {
            if (!eligible.test(source.getLinks().get(link.getId()))) result.removeLink(link.getId());
            else copyAttributes(link.getAttributes(), link.getAttributes());
        }
        for (var node : new ArrayList<>(result.getNodes().values())) {
            if (node.getInLinks().isEmpty() && node.getOutLinks().isEmpty()) result.removeNode(node.getId());
        }
        if (source instanceof TimeDependentNetwork dynamic) {
            var events = new ArrayList<NetworkChangeEvent>();
            for (var event : dynamic.getNetworkChangeEvents()) {
                var mapped = event.getLinks().stream().map(link -> result.getLinks().get(link.getId()))
                        .filter(Objects::nonNull).toList();
                if (mapped.isEmpty()) continue;
                var copy = new NetworkChangeEvent(event.getStartTime());
                copy.setFreespeedChange(copyChange(event.getFreespeedChange()));
                copy.setFlowCapacityChange(copyChange(event.getFlowCapacityChange()));
                copy.setLanesChange(copyChange(event.getLanesChange()));
                mapped.forEach(copy::addLink);
                events.add(copy);
            }
            NetworkUtils.setNetworkChangeEvents(result, events);
        }
        return result;
    }

    private static void copyAttributes(Attributes source, Attributes target) {
        source.getAsMap().forEach((key, value) -> target.putAttribute(key, RoutingData.copy(value)));
    }

    private static NetworkChangeEvent.ChangeValue copyChange(NetworkChangeEvent.ChangeValue change) {
        return change == null ? null : new NetworkChangeEvent.ChangeValue(change.getType(), change.getValue());
    }
}
