package hagrid.core.routing;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.TimeDependentNetwork;

import java.io.*;
import java.security.*;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Structured graph identity; retains a digest rather than another graph copy. */
final class RoutingNetworkIdentity {
    private RoutingNetworkIdentity() {}

    static String fingerprint(Network network) {
        Objects.requireNonNull(network, "network");
        return RoutingData.hash(out -> {
            RoutingData.write(out, "hagrid-routing-network-v2");
            RoutingData.write(out, Arrays.asList(network.getName(), network.getCapacityPeriod(),
                    network.getEffectiveCellSize(), network.getEffectiveLaneWidth(),
                    network.getAttributes().getAsMap()));
            out.writeInt(network.getNodes().size());
            for (var node : network.getNodes().values().stream()
                    .sorted(Comparator.comparing(n -> n.getId().toString())).toList()) {
                RoutingData.write(out, List.of(node.getId(), node.getCoord(), node.getAttributes().getAsMap()));
            }
            out.writeInt(network.getLinks().size());
            for (var link : network.getLinks().values().stream()
                    .sorted(Comparator.comparing(l -> l.getId().toString())).toList()) {
                RoutingData.write(out, List.of(link.getId(), link.getFromNode().getId(), link.getToNode().getId(),
                        link.getLength(), link.getFreespeed(), link.getCapacity(), link.getNumberOfLanes(),
                        link.getAllowedModes(), link.getAttributes().getAsMap()));
            }
            // Queue iteration is not chronological and equal-time ordering is unspecified.
            // Canonicalize complete event payloads, preserving duplicate event occurrences.
            var eventHashes = network instanceof TimeDependentNetwork dynamic
                    ? dynamic.getNetworkChangeEvents().stream().map(new EventDigester())
                        .sorted().toList()
                    : List.<String>of();
            RoutingData.write(out, eventHashes);
        });
    }

    /** Same canonical bytes, one bounded buffer and one child digest per graph, not per event. */
    private static final class EventDigester implements Function<NetworkChangeEvent,String> {
        private final MessageDigest digest;
        private final DataOutputStream out;
        EventDigester() {
            try {digest=MessageDigest.getInstance("SHA-256");}
            catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}
            out=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest),8192));
        }
        public String apply(NetworkChangeEvent event) {
            try {
                digest.reset();
                RoutingData.write(out,event.getStartTime());
                RoutingData.write(out,event.getLinks().stream().map(Link::getId).sorted(Comparator.comparing(Object::toString)).toList());
                RoutingData.write(out,change(event.getFreespeedChange()));
                RoutingData.write(out,change(event.getFlowCapacityChange()));
                RoutingData.write(out,change(event.getLanesChange()));
                out.flush();return HexFormat.of().formatHex(digest.digest());
            }catch(IOException e){throw new IllegalStateException("Cannot fingerprint network change event",e);}
        }
    }
    static String eventFingerprint(NetworkChangeEvent event) {
        return RoutingData.hashValues(event.getStartTime(),
                event.getLinks().stream().map(Link::getId)
                        .sorted(Comparator.comparing(Object::toString)).toList(),
                change(event.getFreespeedChange()), change(event.getFlowCapacityChange()),
                change(event.getLanesChange()));
    }

    private static Object change(NetworkChangeEvent.ChangeValue value) {
        return value == null ? null : List.of(value.getType(), value.getValue());
    }
}

