package hagrid.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records every carrier merge event during the "small → large" merging phase
 * in {@link hagrid.demand.CarrierGenerator}.
 * <p>
 * After merging completes, the log is stored as a scenario element
 * ({@code "carrierMergeLog"}) so that {@link ScenarioSummaryWriter} can
 * produce a human-readable report of what happened.
 *
 * @author HAGRID Team
 */
public final class CarrierMergeLog {

    /**
     * A single merge event: one small carrier absorbed into a target carrier.
     *
     * @param sourceCarrierId  ID of the carrier that was dissolved
     * @param targetCarrierId  ID of the carrier that absorbed the services
     * @param sourceServices   number of services the source carrier had
     * @param sourceParcels    total parcel demand (capacity) of the source
     * @param targetServicesBefore services the target had <b>before</b> the merge
     * @param targetServicesAfter  services the target had <b>after</b> the merge
     * @param targetParcelsBefore  parcel demand of the target <b>before</b>
     * @param targetParcelsAfter   parcel demand of the target <b>after</b>
     * @param mergeType        "Small→Large", "Small→Small", or "Small→Large (iterative)"
     * @param iteration        iteration number (0 = legacy pass, ≥1 = iterative pass)
     */
    public record MergeEntry(
            String sourceCarrierId,
            String targetCarrierId,
            int sourceServices,
            int sourceParcels,
            int targetServicesBefore,
            int targetServicesAfter,
            int targetParcelsBefore,
            int targetParcelsAfter,
            String mergeType,
            int iteration
    ) {}

    private final List<MergeEntry> entries = new ArrayList<>();
    private int carriersBeforeMerge;
    private int carriersAfterMerge;
    private int carriersBelowThreshold;
    private int mergeThreshold;

    public void addEntry(MergeEntry entry) {
        entries.add(entry);
    }

    public List<MergeEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public int getCarriersBeforeMerge() { return carriersBeforeMerge; }
    public void setCarriersBeforeMerge(int n) { this.carriersBeforeMerge = n; }

    public int getCarriersAfterMerge() { return carriersAfterMerge; }
    public void setCarriersAfterMerge(int n) { this.carriersAfterMerge = n; }

    public int getCarriersBelowThreshold() { return carriersBelowThreshold; }
    public void setCarriersBelowThreshold(int n) { this.carriersBelowThreshold = n; }

    public int getMergeThreshold() { return mergeThreshold; }
    public void setMergeThreshold(int t) { this.mergeThreshold = t; }
}
