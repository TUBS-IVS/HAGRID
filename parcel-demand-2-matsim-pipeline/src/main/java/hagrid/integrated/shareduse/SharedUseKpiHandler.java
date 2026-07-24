package hagrid.integrated.shareduse;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEventHandler;
import org.matsim.contrib.dvrp.passenger.PassengerRequestRejectedEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestRejectedEventHandler;
import org.matsim.contrib.dvrp.passenger.PassengerRequestSubmittedEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestSubmittedEventHandler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * delta / channel KPI event handler (1c Task 7). Tracks submitted / delivered /
 * finally-rejected PARCEL requests via the native DVRP passenger events and writes
 * {@code shareduse_channel_stats.csv} at shutdown. Pax requests are ignored entirely
 * (D10(b)): the classifier is the SAME {@link SharedUse#isParcelPerson} predicate used
 * by the chi-gate ({@link ChiGateInsertionCostCalculator}) and the retry queue
 * ({@link ParcelOnlyRetryQueue}).
 *
 * <p><b>Conservation identity (M3):</b> {@code segments_delivered + segments_rejected_final
 * + segments_pending_eod == segments_submitted}. {@code segments_pending_eod} is DERIVED
 * at shutdown (submitted - delivered - rejected_final) - no event fires for a request
 * still pending at day end, nor for one silently dropped past its own delivery window on
 * the retrieval path ({@link ParcelOnlyRetryQueue#getRequestsToRetryNow}), so it can never
 * be tallied directly from events the way {@code segments_rejected_final} is.</p>
 *
 * <p><b>Honest δ decomposition (I1):</b> a χ-starved parcel is NEVER terminally rejected -
 * it retries until its own delivery window closes, then drops silently on the retrieval path,
 * so {@code segments_rejected_final} captures only genuine hard rejects (which are ~always 0
 * for parcels). The real "χ cost" signal therefore lives in {@code segments_pending_eod},
 * which is split at shutdown by each parcel's {@link SharedUse#WINDOW_END_ATTRIBUTE} against
 * the last simulated (event) time: {@code segments_window_expired} (windowEnd &le; last-event-time
 * = χ-starved past its deadline) vs {@code segments_pending_open} (windowEnd &gt; last-event-time
 * = the sim ended before the deadline). These two sum back to {@code segments_pending_eod}, so
 * {@code delivered + rejected_final + window_expired + pending_open == submitted} still holds.</p>
 *
 * <p>Requests are keyed by request id (stable across retries - retries never re-emit a
 * submission event); per-parcel LOAD ({@link SharedUse#LOAD_ATTRIBUTE}), CHANNEL
 * ({@link SharedUse#CHANNEL_ATTRIBUTE}) and delivery-window end
 * ({@link SharedUse#WINDOW_END_ATTRIBUTE}) are resolved ONCE from the injected
 * {@link Population} at construction, since parcel-persons are static for the run.</p>
 *
 * <p><b>Per-iteration reset (C1):</b> DRT request ids restart at {@code drt_0} each iteration
 * (the QSim and its passenger-id counter are rebuilt per iteration), so on a {@code maxIter>1}
 * run the ids collide across iterations. {@link #reset(int)} therefore clears the per-request
 * event state between iterations (mirroring stock DRT analysis handlers), so the CSV written at
 * shutdown reflects ONLY the final iteration. The static population snapshots
 * (load/channel/window-end) are NOT cleared - they are run-invariant.</p>
 */
public final class SharedUseKpiHandler implements
        PassengerRequestSubmittedEventHandler,
        PassengerRequestRejectedEventHandler,
        PassengerDroppedOffEventHandler,
        ShutdownListener {

    static final String FILE_NAME = "shareduse_channel_stats.csv";

    // Static population snapshots (run-invariant - NOT cleared on reset).
    private final Map<Id<Person>, Integer> loadByPerson = new LinkedHashMap<>();
    private final Map<Id<Person>, String> channelByPerson = new LinkedHashMap<>();
    private final Map<Id<Person>, Double> windowEndByPerson = new LinkedHashMap<>();

    // Per-request event state (CLEARED on reset - see reset(int)).
    /** requestId -> the one parcel-person id carried on its submission (parcel requests are single-person). */
    private final Map<Id<Request>, Id<Person>> personByRequest = new LinkedHashMap<>();
    private final Map<Id<Request>, Double> submittedAt = new LinkedHashMap<>();
    private final Map<Id<Request>, Double> deliveredAt = new LinkedHashMap<>();
    private final Set<Id<Request>> rejectedFinal = new LinkedHashSet<>();
    /** Latest time seen across ALL handled events (pax + parcel) = proxy for the sim end / EOD. */
    private double lastEventTime = 0.0;

    private final Path outputCsv;

    @Inject
    public SharedUseKpiHandler(Population population, OutputDirectoryHierarchy controlerIO) {
        population.getPersons().values().stream()
                .filter(p -> SharedUse.isParcelPerson(p.getId().toString()))
                .forEach(p -> {
                    Object load = p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE);
                    if (load instanceof Number n) {
                        loadByPerson.put(p.getId(), n.intValue());
                    }
                    Object channel = p.getAttributes().getAttribute(SharedUse.CHANNEL_ATTRIBUTE);
                    if (channel != null) {
                        channelByPerson.put(p.getId(), channel.toString());
                    }
                    Object windowEnd = p.getAttributes().getAttribute(SharedUse.WINDOW_END_ATTRIBUTE);
                    if (windowEnd instanceof Number n) {
                        windowEndByPerson.put(p.getId(), n.doubleValue());
                    }
                });
        this.outputCsv = Path.of(controlerIO.getOutputFilename(FILE_NAME));
    }

    /**
     * Clears the per-request event state between iterations (C1). DRT request ids restart at
     * {@code drt_0} each iteration, so without this the ids collide across iterations and the
     * shutdown CSV becomes a corrupted cross-iteration aggregate; resetting guarantees the CSV
     * reflects ONLY the final iteration. Mirrors how stock DRT analysis handlers reset. The
     * static population snapshots (load/channel/window-end) are intentionally NOT cleared.
     */
    @Override
    public void reset(int iteration) {
        personByRequest.clear();
        submittedAt.clear();
        deliveredAt.clear();
        rejectedFinal.clear();
        lastEventTime = 0.0;
    }

    // ---- events ---------------------------------------------------------------------

    @Override
    public void handleEvent(PassengerRequestSubmittedEvent event) {
        lastEventTime = Math.max(lastEventTime, event.getTime()); // advance EOD proxy on ALL events
        Id<Person> parcelPersonId = firstParcelPerson(event.getPersonIds());
        if (parcelPersonId == null) {
            return; // pax request - ignored entirely (D10(b))
        }
        personByRequest.putIfAbsent(event.getRequestId(), parcelPersonId);
        submittedAt.putIfAbsent(event.getRequestId(), event.getTime());
    }

    @Override
    public void handleEvent(PassengerRequestRejectedEvent event) {
        lastEventTime = Math.max(lastEventTime, event.getTime());
        if (!submittedAt.containsKey(event.getRequestId())) {
            return; // not a tracked parcel request
        }
        rejectedFinal.add(event.getRequestId());
    }

    @Override
    public void handleEvent(PassengerDroppedOffEvent event) {
        lastEventTime = Math.max(lastEventTime, event.getTime());
        if (!SharedUse.isParcelPerson(event.getPersonId().toString())) {
            return; // pax dropoff - ignored entirely
        }
        if (!submittedAt.containsKey(event.getRequestId())) {
            return; // defensive: dropoff for a request we never saw submitted
        }
        deliveredAt.put(event.getRequestId(), event.getTime());
    }

    private static Id<Person> firstParcelPerson(List<Id<Person>> personIds) {
        for (Id<Person> id : personIds) {
            if (SharedUse.isParcelPerson(id.toString())) {
                return id;
            }
        }
        return null;
    }

    // ---- shutdown ---------------------------------------------------------------------

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        writeCsv(outputCsv);
    }

    /** Package-visible so the unit test can drive it without a real Controler shutdown. */
    void writeCsv(Path path) {
        int segmentsSubmitted = submittedAt.size();
        int segmentsDelivered = deliveredAt.size();
        int segmentsRejectedFinal = rejectedFinal.size();
        int segmentsPendingEod = segmentsSubmitted - segmentsDelivered - segmentsRejectedFinal;

        int parcelsSubmitted = 0;
        int parcelsDelivered = 0;
        int doorLoad = 0;
        int lockerLoad = 0;
        double delaySumS = 0.0;

        // Honest δ decomposition (I1): split the UNDELIVERED set (submitted - delivered -
        // rejected_final) by each parcel's delivery-window end against the last simulated time.
        // χ-caused undelivered manifest as window_expired (NOT rejected_final): a χ-starved
        // parcel retries until its window closes, then drops silently - it is never hard-rejected.
        int segmentsWindowExpired = 0;
        int segmentsPendingOpen = 0;

        for (Map.Entry<Id<Request>, Double> e : submittedAt.entrySet()) {
            Id<Request> requestId = e.getKey();
            Id<Person> personId = personByRequest.get(requestId);
            int load = loadByPerson.getOrDefault(personId, 0);
            parcelsSubmitted += load;

            if ("LOCKER".equals(channelByPerson.get(personId))) {
                lockerLoad += load;
            } else {
                doorLoad += load; // DOOR, or an unattributed fallback -> door-default
            }

            Double deliveredTime = deliveredAt.get(requestId);
            if (deliveredTime != null) {
                parcelsDelivered += load;
                delaySumS += deliveredTime - e.getValue();
            } else if (!rejectedFinal.contains(requestId)) {
                // Undelivered and not a hard reject -> classify by its own delivery window.
                Double windowEnd = windowEndByPerson.get(personId);
                if (windowEnd != null && windowEnd <= lastEventTime) {
                    segmentsWindowExpired++; // deadline passed within the sim = the real χ-cost bucket
                } else {
                    segmentsPendingOpen++;   // sim ended before the deadline (or window unknown)
                }
            }
        }

        int parcelsUndelivered = parcelsSubmitted - parcelsDelivered;
        double undeliveredRate = parcelsSubmitted > 0 ? (double) parcelsUndelivered / parcelsSubmitted : 0.0;
        // LOAD-weighted (fraction of parcel COUNT, not fraction of segments) - consistent with
        // every other parcel-facing metric in this file (parcels_submitted/delivered/undelivered_rate).
        double shareChannelDoor = parcelsSubmitted > 0 ? (double) doorLoad / parcelsSubmitted : 0.0;
        double shareChannelLocker = parcelsSubmitted > 0 ? (double) lockerLoad / parcelsSubmitted : 0.0;
        double meanDelaySeconds = segmentsDelivered > 0 ? delaySumS / segmentsDelivered : 0.0;

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                w.write("metric;value");
                w.newLine();
                writeMetric(w, "segments_submitted", segmentsSubmitted);
                writeMetric(w, "segments_delivered", segmentsDelivered);
                writeMetric(w, "segments_rejected_final", segmentsRejectedFinal);
                writeMetric(w, "segments_window_expired", segmentsWindowExpired);
                writeMetric(w, "segments_pending_open", segmentsPendingOpen);
                writeMetric(w, "segments_pending_eod", segmentsPendingEod);
                writeMetric(w, "parcels_submitted", parcelsSubmitted);
                writeMetric(w, "parcels_delivered", parcelsDelivered);
                writeMetric(w, "parcels_undelivered", parcelsUndelivered);
                writeMetric(w, "undelivered_rate", undeliveredRate);
                writeMetric(w, "share_channel_door", shareChannelDoor);
                writeMetric(w, "share_channel_locker", shareChannelLocker);
                writeMetric(w, "mean_delivery_delay_s", meanDelaySeconds);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + path, e);
        }
    }

    private static void writeMetric(BufferedWriter w, String name, int value) throws IOException {
        w.write(name + ";" + value);
        w.newLine();
    }

    private static void writeMetric(BufferedWriter w, String name, double value) throws IOException {
        w.write(name + ";" + value);
        w.newLine();
    }
}
