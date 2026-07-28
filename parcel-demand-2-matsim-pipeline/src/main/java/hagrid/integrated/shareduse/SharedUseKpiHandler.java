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
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.ShutdownListener;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * <p><b>δ definition (I1/F4 — the paper's undelivered rate):</b> the M5 delivery window is
 * queue-enforced only ({@link ParcelOnlyRetryQueue}), so a parcel ACCEPTED shortly before its
 * window end can physically be dropped off AFTER it. Such a segment is counted as
 * {@code segments_delivered_late} / {@code parcels_delivered_late}, NOT as delivered:
 * {@code segments_delivered} / {@code parcels_delivered} mean delivered WITHIN the window
 * (dropoff time &le; {@link SharedUse#WINDOW_END_ATTRIBUTE}). Consequently
 * {@code undelivered_rate = (parcels_submitted - parcels_delivered) / parcels_submitted}
 * counts late deliveries as NOT within-window; readers can reconstruct
 * total-physically-delivered as {@code parcels_delivered + parcels_delivered_late}.</p>
 *
 * <p><b>Conservation identity (M3, extended):</b> {@code segments_delivered
 * + segments_delivered_late + segments_rejected_final + segments_window_expired
 * + segments_pending_open == segments_submitted}. {@code segments_pending_eod} is DERIVED
 * at write time (submitted - delivered - delivered_late - rejected_final
 * = window_expired + pending_open) - no event fires for a request still pending at day end,
 * nor for one silently dropped past its own delivery window on the retrieval path
 * ({@link ParcelOnlyRetryQueue#getRequestsToRetryNow}), so it can never be tallied directly
 * from events the way {@code segments_rejected_final} is.</p>
 *
 * <p><b>Honest δ decomposition (I1):</b> a χ-starved parcel is NEVER terminally rejected -
 * it retries until its own delivery window closes, then drops silently on the retrieval path,
 * so {@code segments_rejected_final} captures only genuine hard rejects (which are ~always 0
 * for parcels). The real "χ cost" signal therefore lives in the undelivered remainder,
 * which is split at write time by each parcel's {@link SharedUse#WINDOW_END_ATTRIBUTE} against
 * the last simulated (event) time: {@code segments_window_expired} (windowEnd &le; last-event-time
 * = χ-starved past its deadline) vs {@code segments_pending_open} (windowEnd &gt; last-event-time
 * = the sim ended before the deadline).</p>
 *
 * <p><b>Injected vs submitted (C2/F5):</b> {@code segments_injected} / {@code parcels_injected}
 * count the FULL parcel subpopulation (the population snapshot), not just the segments that
 * ever produced a submission event: a parcel-person whose drt leg the router downgrades to a
 * walk fallback (no drt route found from its depot/delivery link) never submits a DVRP request
 * and previously vanished from every KPI. {@code segments_never_submitted} /
 * {@code parcels_never_submitted} (injected - submitted) make that loss visible, and
 * {@code delivery_rate_total = parcels_delivered / parcels_injected} is the only rate whose
 * denominator cannot be shrunk by that fallback.</p>
 *
 * <p><b>{@code mean_time_to_delivery_s} (C1 — right-censored):</b> dropoff minus submission,
 * averaged over IN-WINDOW deliveries only. It is right-censored: as the χ-gate strangles
 * service, only the easiest (fastest) segments still get delivered, so the mean IMPROVES while
 * the service collapses — it must always be read alongside {@code undelivered_rate}. When
 * nothing was delivered in-window the line is OMITTED entirely (never 0.0 as a pseudo-result;
 * the χ=0 probe used to emit 0.0 with 100% undelivered).</p>
 *
 * <p><b>Per-iteration series (F8/M9):</b> at each iteration end (BEFORE {@link #reset(int)}
 * clears the state for the next iteration) one line is appended to
 * {@code shareduse_channel_stats_iterations.csv}, so the δ-convergence over iterations can be
 * checked without re-running the simulation. The shutdown CSV still reflects ONLY the final
 * iteration.</p>
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
        IterationEndsListener,
        ShutdownListener {

    static final String FILE_NAME = "shareduse_channel_stats.csv";
    static final String ITERATIONS_FILE_NAME = "shareduse_channel_stats_iterations.csv";
    static final String ITERATIONS_HEADER = "iteration;segments_submitted;segments_delivered;"
            + "segments_delivered_late;segments_window_expired;segments_pending_open;"
            + "parcels_submitted;parcels_delivered;parcels_delivered_late;parcels_undelivered";

    // Static population snapshots (run-invariant - NOT cleared on reset). Built STRICTLY:
    // see ParcelAttributes for why a missing attribute aborts instead of defaulting.
    private final Map<Id<Person>, Integer> loadByPerson;
    private final Map<Id<Person>, String> channelByPerson;
    private final Map<Id<Person>, Double> windowEndByPerson;
    /** Σ loads over the full parcel subpopulation = parcels_injected (run-invariant). */
    private final int parcelsInjected;

    // Per-request event state (CLEARED on reset - see reset(int)).
    /** requestId -> the one parcel-person id carried on its submission (parcel requests are single-person). */
    private final Map<Id<Request>, Id<Person>> personByRequest = new LinkedHashMap<>();
    private final Map<Id<Request>, Double> submittedAt = new LinkedHashMap<>();
    private final Map<Id<Request>, Double> deliveredAt = new LinkedHashMap<>();
    private final Set<Id<Request>> rejectedFinal = new LinkedHashSet<>();
    /** Latest time seen across ALL handled events (pax + parcel) = proxy for the sim end / EOD. */
    private double lastEventTime = 0.0;

    private final Path outputCsv;
    private final Path outputIterationsCsv;

    @Inject
    public SharedUseKpiHandler(Population population, OutputDirectoryHierarchy controlerIO) {
        // STRICT snapshots (ParcelAttributes): previously a parcel-person without a LOAD
        // attribute silently contributed 0 parcels to parcels_submitted/_delivered, and an
        // unattributed CHANNEL landed in the DOOR bucket - both produce a plausible CSV that
        // simply undercounts. Validating here aborts at controler startup instead.
        this.loadByPerson = ParcelAttributes.loads(population);
        this.channelByPerson = ParcelAttributes.channels(population);
        this.windowEndByPerson = ParcelAttributes.windowEnds(population);
        int injectedSum = 0;
        for (int load : loadByPerson.values()) {
            injectedSum += load;
        }
        this.parcelsInjected = injectedSum;
        this.outputCsv = Path.of(controlerIO.getOutputFilename(FILE_NAME));
        this.outputIterationsCsv = Path.of(controlerIO.getOutputFilename(ITERATIONS_FILE_NAME));
    }

    /**
     * Clears the per-request event state between iterations (C1). DRT request ids restart at
     * {@code drt_0} each iteration, so without this the ids collide across iterations and the
     * shutdown CSV becomes a corrupted cross-iteration aggregate; resetting guarantees the CSV
     * reflects ONLY the final iteration. Mirrors how stock DRT analysis handlers reset. The
     * static population snapshots (load/channel/window-end) are intentionally NOT cleared.
     * The per-iteration line for the iteration just finished has already been appended by
     * {@link #notifyIterationEnds(IterationEndsEvent)}, which fires BEFORE this clear.
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

    // ---- iteration end / shutdown -----------------------------------------------------

    /**
     * F8/M9: appends this iteration's channel totals to
     * {@code shareduse_channel_stats_iterations.csv}. IterationEnds fires BEFORE the events
     * manager calls {@link #reset(int)} for the NEXT iteration, so the state captured here is
     * exactly this iteration's.
     */
    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        appendIterationRow(event.getIteration(), outputIterationsCsv);
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        writeCsv(outputCsv);
    }

    /** Package-visible so the unit test can drive it without a real Controler iteration. */
    void appendIterationRow(int iteration, Path path) {
        Totals t = computeTotals();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean writeHeader = !Files.exists(path);
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (writeHeader) {
                    w.write(ITERATIONS_HEADER);
                    w.newLine();
                }
                // Same locale-safe formatting idiom as writeCsv: plain string concat.
                w.write(iteration + ";" + t.segmentsSubmitted + ";" + t.segmentsDelivered
                        + ";" + t.segmentsDeliveredLate + ";" + t.segmentsWindowExpired
                        + ";" + t.segmentsPendingOpen + ";" + t.parcelsSubmitted
                        + ";" + t.parcelsDelivered + ";" + t.parcelsDeliveredLate
                        + ";" + (t.parcelsSubmitted - t.parcelsDelivered));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not append to " + path, e);
        }
    }

    /** Package-visible so the unit test can drive it without a real Controler shutdown. */
    void writeCsv(Path path) {
        Totals t = computeTotals();

        int segmentsInjected = loadByPerson.size();
        // C2/F5: injected - submitted = parcel-persons whose drt leg the router downgraded to a
        // walk fallback (no drt route found), so they never emitted a submission event at all.
        int segmentsNeverSubmitted = segmentsInjected - t.segmentsSubmitted;
        int parcelsNeverSubmitted = parcelsInjected - t.parcelsSubmitted;
        int segmentsPendingEod = t.segmentsSubmitted - t.segmentsDelivered
                - t.segmentsDeliveredLate - t.segmentsRejectedFinal;

        // δ (I1/F4): delivered means IN-WINDOW; late deliveries count as NOT within-window.
        int parcelsUndelivered = t.parcelsSubmitted - t.parcelsDelivered;
        double undeliveredRate = t.parcelsSubmitted > 0
                ? (double) parcelsUndelivered / t.parcelsSubmitted : 0.0;
        double deliveryRateTotal = parcelsInjected > 0
                ? (double) t.parcelsDelivered / parcelsInjected : 0.0;
        // LOAD-weighted (fraction of parcel COUNT, not fraction of segments) - consistent with
        // every other parcel-facing metric in this file (parcels_submitted/delivered/undelivered_rate).
        double shareChannelDoor = t.parcelsSubmitted > 0
                ? (double) t.doorLoad / t.parcelsSubmitted : 0.0;
        double shareChannelLocker = t.parcelsSubmitted > 0
                ? (double) t.lockerLoad / t.parcelsSubmitted : 0.0;

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                w.write("metric;value");
                w.newLine();
                writeMetric(w, "segments_injected", segmentsInjected);
                writeMetric(w, "segments_submitted", t.segmentsSubmitted);
                writeMetric(w, "segments_never_submitted", segmentsNeverSubmitted);
                writeMetric(w, "segments_delivered", t.segmentsDelivered);
                writeMetric(w, "segments_delivered_late", t.segmentsDeliveredLate);
                writeMetric(w, "segments_rejected_final", t.segmentsRejectedFinal);
                writeMetric(w, "segments_window_expired", t.segmentsWindowExpired);
                writeMetric(w, "segments_pending_open", t.segmentsPendingOpen);
                writeMetric(w, "segments_pending_eod", segmentsPendingEod);
                writeMetric(w, "parcels_injected", parcelsInjected);
                writeMetric(w, "parcels_submitted", t.parcelsSubmitted);
                writeMetric(w, "parcels_never_submitted", parcelsNeverSubmitted);
                writeMetric(w, "parcels_delivered", t.parcelsDelivered);
                writeMetric(w, "parcels_delivered_late", t.parcelsDeliveredLate);
                writeMetric(w, "parcels_undelivered", parcelsUndelivered);
                writeMetric(w, "undelivered_rate", undeliveredRate);
                writeMetric(w, "delivery_rate_total", deliveryRateTotal);
                writeMetric(w, "share_channel_door", shareChannelDoor);
                writeMetric(w, "share_channel_locker", shareChannelLocker);
                // C1: OMITTED entirely when nothing was delivered in-window - a 0.0 here is a
                // pseudo-result (the chi=0 probe emitted 0.0 alongside 100% undelivered).
                if (t.segmentsDelivered > 0) {
                    writeMetric(w, "mean_time_to_delivery_s",
                            t.timeToDeliverySumS / t.segmentsDelivered);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + path, e);
        }
    }

    /**
     * One pass over the tracked requests, classifying each submitted segment into exactly one
     * of: delivered (in-window), delivered_late, rejected_final, window_expired, pending_open -
     * so the extended M3 conservation identity holds by construction.
     */
    private Totals computeTotals() {
        Totals t = new Totals();
        t.segmentsSubmitted = submittedAt.size();
        t.segmentsRejectedFinal = rejectedFinal.size();

        for (Map.Entry<Id<Request>, Double> e : submittedAt.entrySet()) {
            Id<Request> requestId = e.getKey();
            Id<Person> personId = personByRequest.get(requestId);
            // Strict: the constructor validated every parcel-person, and personByRequest is
            // written together with submittedAt, so both lookups are total. A miss would mean
            // a parcel request whose person is unknown here - counting it as 0 parcels / DOOR
            // (the old behaviour) would quietly deflate every parcel KPI below.
            Integer loadOrNull = loadByPerson.get(personId);
            String channel = channelByPerson.get(personId);
            if (loadOrNull == null || channel == null) {
                throw new IllegalStateException("parcel request " + requestId + " maps to person "
                        + personId + ", which is not in the population snapshot - cannot attribute"
                        + " its parcels or delivery channel.");
            }
            int load = loadOrNull;
            t.parcelsSubmitted += load;

            if (DeliveryChannelResolver.Channel.LOCKER.name().equals(channel)) {
                t.lockerLoad += load;
            } else {
                t.doorLoad += load;   // the only remaining channel (ParcelAttributes validates the set)
            }

            double windowEnd = windowEndByPerson.get(personId);
            Double deliveredTime = deliveredAt.get(requestId);
            if (deliveredTime != null) {
                // I1/F4: the M5 window is queue-enforced only, so a request accepted shortly
                // before its window end can physically drop off after it - that is a LATE
                // delivery, not a within-window one (see the class javadoc's delta definition).
                if (deliveredTime <= windowEnd) {
                    t.segmentsDelivered++;
                    t.parcelsDelivered += load;
                    t.timeToDeliverySumS += deliveredTime - e.getValue();
                } else {
                    t.segmentsDeliveredLate++;
                    t.parcelsDeliveredLate += load;
                }
            } else if (!rejectedFinal.contains(requestId)) {
                // Undelivered and not a hard reject -> classify by its own delivery window.
                // windowEndByPerson is total over the same validated person set as the two
                // lookups above, so there is no "window unknown" bucket any more: the split is
                // purely deadline-passed vs sim-ended-first.
                if (windowEnd <= lastEventTime) {
                    t.segmentsWindowExpired++; // deadline passed within the sim = the real χ-cost bucket
                } else {
                    t.segmentsPendingOpen++;   // sim ended before the deadline
                }
            }
        }
        return t;
    }

    /** Mutable aggregate of one {@link #computeTotals()} pass (shutdown CSV + iteration rows). */
    private static final class Totals {
        int segmentsSubmitted;
        int segmentsDelivered;
        int segmentsDeliveredLate;
        int segmentsRejectedFinal;
        int segmentsWindowExpired;
        int segmentsPendingOpen;
        int parcelsSubmitted;
        int parcelsDelivered;
        int parcelsDeliveredLate;
        int doorLoad;
        int lockerLoad;
        double timeToDeliverySumS;
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
