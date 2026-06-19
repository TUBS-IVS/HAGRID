package hagrid.integrated.drt;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;

import java.util.HashSet;
import java.util.Set;

/**
 * Prepares a DRT sub-network from a full network: keeps links whose both
 * endpoints lie inside the DRT service area, adds {@link TransportMode#drt} to
 * their allowed modes, and cleans the result so the drt sub-network stays
 * connected. Pure in-memory; callers handle file I/O.
 */
public final class DrtNetworkPreparer {

    private static final GeometryFactory GF = new GeometryFactory();

    private DrtNetworkPreparer() {}

    public static Network prepare(Network full, Geometry serviceArea) {
        Network out = NetworkUtils.createNetwork();
        NetworkFactory f = out.getFactory();

        for (Link link : full.getLinks().values()) {
            if (!contains(serviceArea, link.getFromNode().getCoord())
                    || !contains(serviceArea, link.getToNode().getCoord())) {
                continue;
            }
            Node from = copyNode(out, f, link.getFromNode());
            Node to = copyNode(out, f, link.getToNode());
            Link copy = f.createLink(link.getId(), from, to);
            copy.setLength(link.getLength());
            copy.setFreespeed(link.getFreespeed());
            copy.setCapacity(link.getCapacity());
            copy.setNumberOfLanes(link.getNumberOfLanes());
            Set<String> modes = new HashSet<>(link.getAllowedModes());
            modes.add(TransportMode.drt);
            copy.setAllowedModes(modes);
            NetworkUtils.setType(copy, NetworkUtils.getType(link));
            out.addLink(copy);
        }

        // Keep the drt sub-network strongly connected.
        new MultimodalNetworkCleaner(out).run(Set.of(TransportMode.drt));
        return out;
    }

    private static Node copyNode(Network out, NetworkFactory f, Node src) {
        Node existing = out.getNodes().get(src.getId());
        if (existing != null) {
            return existing;
        }
        Node n = f.createNode(src.getId(), src.getCoord());
        out.addNode(n);
        return n;
    }

    private static boolean contains(Geometry area, Coord c) {
        return area.contains(GF.createPoint(new Coordinate(c.getX(), c.getY())));
    }
}
