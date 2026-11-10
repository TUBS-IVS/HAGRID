package hagrid.core.routing;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;

import java.util.Objects;

/** Validates the authoritative routing topology once at input boundaries, without repairing it. */
public final class RoutingNetworkValidator {
    private RoutingNetworkValidator() {}

    public static void validate(Network network) {
        Objects.requireNonNull(network, "network");
        for (var entry : network.getNodes().entrySet()) {
            var node = entry.getValue();
            if (node == null || !Objects.equals(entry.getKey(), node.getId())) {
                throw invalid("node " + entry.getKey(), "registry key");
            }
            var coord = node.getCoord();
            if (coord == null || !Double.isFinite(coord.getX()) || !Double.isFinite(coord.getY())
                    || (coord.hasZ() && !Double.isFinite(coord.getZ()))) {
                throw invalid("node " + node.getId(), "coordinates");
            }
            checkAdjacency(network, node, true);
            checkAdjacency(network, node, false);
        }
        for (var entry : network.getLinks().entrySet()) {
            var link = entry.getValue();
            if (link == null || !Objects.equals(entry.getKey(), link.getId())) {
                throw invalid("link " + entry.getKey(), "registry key");
            }
            String entity = "link " + link.getId();
            requireScalar(entity, "length", link.getLength(), false);
            requireScalar(entity, "freespeed", link.getFreespeed(), true);
            requireScalar(entity, "capacity", link.getCapacity(), false);
            requireScalar(entity, "lanes", link.getNumberOfLanes(), false);
            if (link.getFromNode() == null || link.getToNode() == null
                    || network.getNodes().get(link.getFromNode().getId()) != link.getFromNode()
                    || network.getNodes().get(link.getToNode().getId()) != link.getToNode()) {
                throw invalid(entity, "endpoints must belong to the input network");
            }
            if (link.getFromNode().getOutLinks().get(link.getId()) != link
                    || link.getToNode().getInLinks().get(link.getId()) != link) {
                throw invalid(entity, "adjacency disagrees with registered endpoints");
            }
            if (link.getAllowedModes() == null || link.getAllowedModes().stream()
                    .anyMatch(mode -> mode == null || mode.isBlank())) {
                throw invalid(entity, "allowed modes must have nonblank names");
            }
        }
    }

    private static void checkAdjacency(Network network, Node node, boolean outgoing) {
        var links = outgoing ? node.getOutLinks() : node.getInLinks();
        for (var entry : links.entrySet()) {
            Link link = entry.getValue();
            if (link == null || !Objects.equals(entry.getKey(), link.getId())
                    || network.getLinks().get(entry.getKey()) != link
                    || (outgoing ? link.getFromNode() : link.getToNode()) != node) {
                throw invalid("node " + node.getId() + " link " + entry.getKey(), "adjacency");
            }
        }
    }

    private static void requireScalar(String entity, String field, double value, boolean positive) {
        if (!Double.isFinite(value) || (positive ? value <= 0 : value < 0)) {
            throw invalid(entity, field + " must be finite and " + (positive ? "positive" : "nonnegative"));
        }
    }

    private static IllegalArgumentException invalid(String entity, String reason) {
        return new IllegalArgumentException("Invalid routing network " + entity + ": " + reason);
    }
}
