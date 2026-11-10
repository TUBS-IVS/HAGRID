package hagrid.core.routing;

import org.matsim.api.core.v01.network.Network;
import java.util.Objects;

/** Controller-scoped graph reuse; mutable source contents are checked before and after capture. */
public final class RoutingGraphCapture implements AutoCloseable {
    private RoutingNetworkSnapshot graph;
    private boolean closed;
    public synchronized Network capture(Network source) {
        if(closed)throw new IllegalStateException("Routing graph capture is closed");
        Objects.requireNonNull(source);
        if(graph==null||!graph.matchesSource(source))
            graph=source instanceof RoutingNetworkSnapshot frozen?frozen:new RoutingNetworkSnapshot(source);
        return graph;
    }
    public synchronized void requireUnchanged(Network source,Network captured) {
        if(closed)throw new IllegalStateException("Routing graph capture is closed");
        if(!(captured instanceof RoutingNetworkSnapshot frozen)||!frozen.matchesSource(source))
            throw new IllegalArgumentException("Network changed during travel-time snapshot capture");
    }
    public synchronized void close(){closed=true;graph=null;}
}
