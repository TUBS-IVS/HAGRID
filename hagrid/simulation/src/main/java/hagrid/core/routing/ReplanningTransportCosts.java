package hagrid.core.routing;

import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

/** Provider-owned lifecycle and exact routing diagnostics. */
public interface ReplanningTransportCosts extends VRPTransportCosts {
    /**
     * Called once per distinct provider at the batch boundary, before carrier work starts.
     * Live providers invalidate old travel-time values; frozen providers retain their captured context.
     * Individual carrier searches must not reset a shared provider.
     */
    void prepareForReplanning();
    /** Reports provider/context statistics without changing the travel-time policy. */
    void logSummary(String label);
}