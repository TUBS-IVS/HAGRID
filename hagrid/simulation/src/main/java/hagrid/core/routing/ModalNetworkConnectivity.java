package hagrid.core.routing;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;

import java.util.*;

/**
 * Read-only directed connectivity for a fixed input graph. One lazily built
 * component array per requested mode is shared by all depot/job checks.
 * This is a necessary topology check, not a route-feasibility certificate:
 * turn restrictions, time profiles and vehicle/job constraints belong to the
 * independent final plan validator. Do not mutate the graph during its use.
 */
public final class ModalNetworkConnectivity {
    private final Network network;
    private List<Node> nodes;
    private final Map<Id<Node>, Integer> nodeIndices = new HashMap<>();
    private final Map<String, int[]> components = new HashMap<>();

    public ModalNetworkConnectivity(Network network) {
        this.network = Objects.requireNonNull(network, "network");

    }

    /** Build one lightweight depot view per carrier; services may use different depots. */
    public DepotAccess depots(Collection<Id<Link>> depotLinks, String mode) {
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("Explicit connectivity mode required");
        var checked = new HashSet<Id<Link>>();
        for (var id : depotLinks) {
            requireLink(id, mode);
            checked.add(id);
        }
        return new DepotAccess(Set.copyOf(checked), mode);
    }

    public final class DepotAccess {
        private final Set<Id<Link>> links;
        private final String mode;
        private Set<Integer> cyclicComponents;

        private DepotAccess(Set<Id<Link>> links, String mode) {
            this.links = links;
            this.mode = mode;
        }

        public boolean canServe(Id<Link> stop) {
            requireLink(stop, mode);
            // Native same-link legs do not require a round trip around the link.
            return links.contains(stop) || sharedComponent(stop, stop);
        }

        public boolean canServeShipment(Id<Link> pickup, Id<Link> delivery) {
            requireLink(pickup, mode);
            requireLink(delivery, mode);
            if (pickup.equals(delivery) && links.contains(pickup)) return true;
            return sharedComponent(pickup, delivery);
        }

        private boolean sharedComponent(Id<Link> first, Id<Link> second) {
            if (links.isEmpty()) return false;
            if (cyclicComponents == null) {
                var values = new HashSet<Integer>();
                for (var depot : links) {
                    int component = linkComponent(depot, mode);
                    if (component >= 0) values.add(component);
                }
                cyclicComponents = Set.copyOf(values);
            }
            int component = linkComponent(first, mode);
            return component >= 0 && cyclicComponents.contains(component)
                    && component == linkComponent(second, mode);
        }
    }

    private Link requireLink(Id<Link> id, String mode) {
        var link = network.getLinks().get(id);
        if (link == null || !link.getAllowedModes().contains(mode)) {
            throw new IllegalArgumentException("Connectivity link " + id + " must exist and allow " + mode);
        }
        return link;
    }

    private int linkComponent(Id<Link> id, String mode) {
        var link = requireLink(id, mode);
        var byNode = components.computeIfAbsent(mode, this::indexComponents);
        int from = byNode[nodeIndices.get(link.getFromNode().getId())];
        int to = byNode[nodeIndices.get(link.getToNode().getId())];
        return from == to ? from : -1;
    }

    /** Iterative Kosaraju: no network clones, recursive call stack or OD searches. */
    private int[] indexComponents(String mode) {
        if (nodes == null) {
            nodes = List.copyOf(network.getNodes().values());
            for (int i = 0; i < nodes.size(); i++) nodeIndices.put(nodes.get(i).getId(), i);
        }
        boolean[] visited = new boolean[nodes.size()];
        int[] finishOrder = new int[nodes.size()];
        int finished = 0;
        var search = new ArrayDeque<Visit>();
        for (int root = 0; root < nodes.size(); root++) {
            if (visited[root]) continue;
            visited[root] = true;
            search.push(visit(root));
            while (!search.isEmpty()) {
                var current = search.peek();
                if (!current.outgoing.hasNext()) {
                    finishOrder[finished++] = current.node;
                    search.pop();
                    continue;
                }
                var link = current.outgoing.next();
                if (!link.getAllowedModes().contains(mode)) continue;
                int next = nodeIndices.get(link.getToNode().getId());
                if (!visited[next]) {
                    visited[next] = true;
                    search.push(visit(next));
                }
            }
        }
        int[] result = new int[nodes.size()];
        Arrays.fill(result, -1);
        var pending = new ArrayDeque<Integer>();
        int component = 0;
        for (int i = finished - 1; i >= 0; i--) {
            int root = finishOrder[i];
            if (result[root] >= 0) continue;
            result[root] = component;
            pending.push(root);
            while (!pending.isEmpty()) {
                var node = nodes.get(pending.pop());
                for (var link : node.getInLinks().values()) {
                    if (!link.getAllowedModes().contains(mode)) continue;
                    int previous = nodeIndices.get(link.getFromNode().getId());
                    if (result[previous] < 0) {
                        result[previous] = component;
                        pending.push(previous);
                    }
                }
            }
            component++;
        }
        return result;
    }

    private Visit visit(int index) {
        return new Visit(index, nodes.get(index).getOutLinks().values().iterator());
    }

    private record Visit(int node, Iterator<? extends Link> outgoing) {}
}
