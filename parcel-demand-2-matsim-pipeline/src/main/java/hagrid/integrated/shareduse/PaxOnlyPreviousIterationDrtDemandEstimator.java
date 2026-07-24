package hagrid.integrated.shareduse;

import com.google.common.base.Preconditions;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.drt.optimizer.rebalancing.demandestimator.PreviousIterationDrtDemandEstimator;
import org.matsim.contrib.drt.optimizer.rebalancing.demandestimator.ZonalDemandEstimator;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * PASSENGER-only demand estimator for DRT rebalancing (1c M7). A faithful re-implementation of
 * the stock {@link PreviousIterationDrtDemandEstimator} (which is {@code final}, so it cannot be
 * subclassed/filtered) that buckets the previous iteration's {@code drt} departures per rebalancing
 * zone and time bin, with ONE difference: it SKIPS departures by {@code parcel_} persons
 * ({@link SharedUse#isParcelPerson}).
 *
 * <p><b>Why (C2):</b> parcel-persons depart on a {@code drt} leg at the depot (before insertion),
 * so the stock estimator counts ~6k phantom parcel departures as rebalancing demand each iteration,
 * contaminating idle-vehicle relocation and confounding the pax integration-cost / χ=0 validation.
 * Rebalancing must see passenger demand only, so this estimator is bound (in {@link SharedUseModule},
 * added last so it overrides the stock binding) as BOTH the modal {@link ZonalDemandEstimator} and a
 * {@code PersonDepartureEvent} handler + {@code IterationEndsListener}.</p>
 *
 * <p>The zonal bucketing, time binning and previous/current double-buffering are identical to the
 * stock estimator, so the {@link ZonalDemandEstimator#getExpectedDemand} contract (incl. the
 * {@code estimationPeriod == timeBinSize} precondition) is preserved verbatim.</p>
 */
public final class PaxOnlyPreviousIterationDrtDemandEstimator
        implements ZonalDemandEstimator, PersonDepartureEventHandler, IterationEndsListener {

    private final ZoneSystem zonalSystem;
    private final String mode;
    private final int timeBinSize;

    private Map<Integer, Map<Zone, Integer>> currentIterationDepartures = new HashMap<>();
    private Map<Integer, Map<Zone, Integer>> previousIterationDepartures = new HashMap<>();

    // Diagnostics / test observability (accumulated over the run, NOT reset per iteration):
    // how many drt departures were accepted (pax) vs skipped because they were parcel phantoms.
    private int acceptedDepartures = 0;
    private int parcelDeparturesSkipped = 0;

    public PaxOnlyPreviousIterationDrtDemandEstimator(ZoneSystem zonalSystem, String mode,
                                                      int demandEstimationPeriod) {
        this.zonalSystem = zonalSystem;
        this.mode = mode;
        this.timeBinSize = demandEstimationPeriod;
    }

    @Override
    public void handleEvent(PersonDepartureEvent event) {
        if (!event.getLegMode().equals(mode)) {
            return;
        }
        if (SharedUse.isParcelPerson(event.getPersonId().toString())) {
            parcelDeparturesSkipped++; // M7: parcel phantom departure - excluded from rebalancing demand
            return;
        }
        acceptedDepartures++;
        zonalSystem.getZoneForLinkId(event.getLinkId()).ifPresent(zone -> {
            int timeBin = getBinForTime(event.getTime());
            currentIterationDepartures
                    .computeIfAbsent(timeBin, v -> new HashMap<>())
                    .merge(zone, 1, Integer::sum);
        });
    }

    @Override
    public ToDoubleFunction<Zone> getExpectedDemand(double fromTime, double estimationPeriod) {
        Preconditions.checkArgument(estimationPeriod == timeBinSize); // matches stock: no per-call flexibility
        int timeBin = getBinForTime(fromTime);
        Map<Zone, Integer> expectedDemandForTimeBin = previousIterationDepartures.getOrDefault(timeBin, Map.of());
        return zone -> expectedDemandForTimeBin.getOrDefault(zone, 0);
    }

    private int getBinForTime(double time) {
        return (int) (time / timeBinSize);
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        previousIterationDepartures = currentIterationDepartures;
        currentIterationDepartures = new HashMap<>();
    }

    @Override
    public void reset(int iteration) {
        // Mirror the stock estimator: the double-buffer swap happens in notifyIterationEnds, so the
        // per-iteration event maps are managed there; nothing to clear on the events-side reset.
    }

    /** Test hook: number of {@code drt} departures counted as passenger demand so far. */
    int acceptedDepartures() {
        return acceptedDepartures;
    }

    /** Test hook: number of {@code drt} departures skipped because they were parcel phantoms. */
    int parcelDeparturesSkipped() {
        return parcelDeparturesSkipped;
    }
}
