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
 * <p>Requests are keyed by request id (stable across retries - retries never re-emit a
 * submission event); per-parcel LOAD ({@link SharedUse#LOAD_ATTRIBUTE}) and CHANNEL
 * ({@link SharedUse#CHANNEL_ATTRIBUTE}) are resolved ONCE from the injected
 * {@link Population} at construction, since parcel-persons are static for the run.</p>
 */
public final class SharedUseKpiHandler implements
        PassengerRequestSubmittedEventHandler,
        PassengerRequestRejectedEventHandler,
        PassengerDroppedOffEventHandler,
        ShutdownListener {

    static final String FILE_NAME = "shareduse_channel_stats.csv";

    private final Map<Id<Person>, Integer> loadByPerson = new LinkedHashMap<>();
    private final Map<Id<Person>, String> channelByPerson = new LinkedHashMap<>();

    /** requestId -> the one parcel-person id carried on its submission (parcel requests are single-person). */
    private final Map<Id<Request>, Id<Person>> personByRequest = new LinkedHashMap<>();
    private final Map<Id<Request>, Double> submittedAt = new LinkedHashMap<>();
    private final Map<Id<Request>, Double> deliveredAt = new LinkedHashMap<>();
    private final Set<Id<Request>> rejectedFinal = new LinkedHashSet<>();

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
                });
        this.outputCsv = Path.of(controlerIO.getOutputFilename(FILE_NAME));
    }

    // ---- events ---------------------------------------------------------------------

    @Override
    public void handleEvent(PassengerRequestSubmittedEvent event) {
        Id<Person> parcelPersonId = firstParcelPerson(event.getPersonIds());
        if (parcelPersonId == null) {
            return; // pax request - ignored entirely (D10(b))
        }
        personByRequest.putIfAbsent(event.getRequestId(), parcelPersonId);
        submittedAt.putIfAbsent(event.getRequestId(), event.getTime());
    }

    @Override
    public void handleEvent(PassengerRequestRejectedEvent event) {
        if (!submittedAt.containsKey(event.getRequestId())) {
            return; // not a tracked parcel request
        }
        rejectedFinal.add(event.getRequestId());
    }

    @Override
    public void handleEvent(PassengerDroppedOffEvent event) {
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
        int doorSegments = 0;
        int lockerSegments = 0;
        double delaySumS = 0.0;

        for (Map.Entry<Id<Request>, Double> e : submittedAt.entrySet()) {
            Id<Request> requestId = e.getKey();
            Id<Person> personId = personByRequest.get(requestId);
            int load = loadByPerson.getOrDefault(personId, 0);
            parcelsSubmitted += load;

            if ("LOCKER".equals(channelByPerson.get(personId))) {
                lockerSegments++;
            } else {
                doorSegments++; // DOOR, or an unattributed fallback -> door-default
            }

            Double deliveredTime = deliveredAt.get(requestId);
            if (deliveredTime != null) {
                parcelsDelivered += load;
                delaySumS += deliveredTime - e.getValue();
            }
        }

        int parcelsUndelivered = parcelsSubmitted - parcelsDelivered;
        double undeliveredRate = parcelsSubmitted > 0 ? (double) parcelsUndelivered / parcelsSubmitted : 0.0;
        double shareChannelDoor = segmentsSubmitted > 0 ? (double) doorSegments / segmentsSubmitted : 0.0;
        double shareChannelLocker = segmentsSubmitted > 0 ? (double) lockerSegments / segmentsSubmitted : 0.0;
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
