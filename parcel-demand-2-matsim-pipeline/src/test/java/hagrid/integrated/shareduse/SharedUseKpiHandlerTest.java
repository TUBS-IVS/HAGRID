package hagrid.integrated.shareduse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestRejectedEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestSubmittedEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PopulationUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Covers 1c Task 7: {@link SharedUseKpiHandler} tracks submitted / delivered / finally-rejected
 * PARCEL requests via the native DVRP passenger events (pax requests are ignored entirely,
 * D10(b)) and writes the {@code metric;value} CSV at shutdown. {@code segments_pending_eod} is
 * never fed by an event — it is derived at write time as
 * {@code submitted - delivered - delivered_late - rejected_final} (M3 conservation, extended by
 * the I1/F4 delivered-late split). Plus the review-mandated honesty upgrades: injected counts
 * (C2/F5), the delivered-late split (I1/F4), the right-censored
 * {@code mean_time_to_delivery_s} that is OMITTED when nothing was delivered (C1), and the
 * per-iteration series file (F8/M9).
 */
@DisplayName("SharedUseKpiHandler")
class SharedUseKpiHandlerTest {

    @RegisterExtension
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    @DisplayName("spec fixture: loads 3+2, one dropoff after 1800s, one still pending, one pax ignored entirely")
    void tracksSegmentsAndWritesCsv() throws Exception {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Person parcelA = person(population, "parcel_dhl_1_B2C", 3, "DOOR");
        Person parcelB = person(population, "parcel_dhl_2_B2C", 2, "DOOR");
        Person pax = population.getFactory().createPerson(Id.createPersonId("pax_1"));
        population.addPerson(pax);

        SharedUseKpiHandler handler = new SharedUseKpiHandler(population, controlerIO());

        Id<Request> reqA = Id.create("reqA", Request.class);
        Id<Request> reqB = Id.create("reqB", Request.class);
        Id<Request> reqPax = Id.create("reqPax", Request.class);

        handler.handleEvent(submitted(1000.0, reqA, parcelA.getId()));
        handler.handleEvent(submitted(1000.0, reqB, parcelB.getId()));
        handler.handleEvent(submitted(1000.0, reqPax, pax.getId()));   // pax - must be ignored

        handler.handleEvent(droppedOff(2800.0, reqA, parcelA.getId()));   // time-to-delivery = 1800s
        handler.handleEvent(droppedOff(1100.0, reqPax, pax.getId()));    // pax dropoff - must be ignored
        // reqB: no dropoff, no reject -> still pending at EOD

        Path csv = Path.of(utils.getOutputDirectory()).resolve("out.csv");
        handler.writeCsv(csv);
        Map<String, String> m = readCsv(csv);

        assertThat(m.get("segments_injected")).as("pax_1 must not count as injected").isEqualTo("2");
        assertThat(m.get("segments_submitted")).isEqualTo("2");
        assertThat(m.get("segments_never_submitted")).isEqualTo("0");
        assertThat(m.get("segments_delivered")).isEqualTo("1");
        assertThat(m.get("segments_delivered_late")).isEqualTo("0");
        assertThat(m.get("segments_rejected_final")).isEqualTo("0");
        assertThat(m.get("segments_pending_eod")).isEqualTo("1");
        assertThat(m.get("parcels_injected")).isEqualTo("5");
        assertThat(m.get("parcels_submitted")).isEqualTo("5");
        assertThat(m.get("parcels_never_submitted")).isEqualTo("0");
        assertThat(m.get("parcels_delivered")).isEqualTo("3");
        assertThat(m.get("parcels_delivered_late")).isEqualTo("0");
        assertThat(m.get("parcels_undelivered")).isEqualTo("2");
        assertThat(Double.parseDouble(m.get("undelivered_rate"))).isEqualTo(0.4);
        assertThat(Double.parseDouble(m.get("delivery_rate_total"))).isEqualTo(0.6);
        assertThat(Double.parseDouble(m.get("share_channel_door"))).isEqualTo(1.0);
        assertThat(Double.parseDouble(m.get("share_channel_locker"))).isEqualTo(0.0);
        assertThat(Double.parseDouble(m.get("mean_time_to_delivery_s"))).isEqualTo(1800.0);
    }

    @Test
    @DisplayName("M3 conservation: delivered + delivered_late + rejected_final + pending_eod == submitted, with a real reject event")
    void conservationHoldsWithRejectedAndPending() throws Exception {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Person delivered = person(population, "parcel_dhl_1_B2C", 4, "DOOR");
        Person rejected = person(population, "parcel_dhl_2_B2C", 1, "DOOR");
        Person pending = person(population, "parcel_dhl_3_B2C", 6, "DOOR");

        SharedUseKpiHandler handler = new SharedUseKpiHandler(population, controlerIO());

        Id<Request> reqDelivered = Id.create("reqDelivered", Request.class);
        Id<Request> reqRejected = Id.create("reqRejected", Request.class);
        Id<Request> reqPending = Id.create("reqPending", Request.class);

        handler.handleEvent(submitted(500.0, reqDelivered, delivered.getId()));
        handler.handleEvent(submitted(500.0, reqRejected, rejected.getId()));
        handler.handleEvent(submitted(500.0, reqPending, pending.getId()));

        handler.handleEvent(droppedOff(900.0, reqDelivered, delivered.getId()));
        handler.handleEvent(new PassengerRequestRejectedEvent(
                86000.0, "drt", reqRejected, List.of(rejected.getId()), "no_feasible_insertion"));
        // reqPending: no dropoff, no reject -> pending_eod

        Path csv = Path.of(utils.getOutputDirectory()).resolve("out.csv");
        handler.writeCsv(csv);
        Map<String, String> m = readCsv(csv);

        int submitted = Integer.parseInt(m.get("segments_submitted"));
        int deliveredCount = Integer.parseInt(m.get("segments_delivered"));
        int deliveredLate = Integer.parseInt(m.get("segments_delivered_late"));
        int rejectedFinal = Integer.parseInt(m.get("segments_rejected_final"));
        int pendingEod = Integer.parseInt(m.get("segments_pending_eod"));

        assertThat(submitted).isEqualTo(3);
        assertThat(deliveredCount).isEqualTo(1);
        assertThat(deliveredLate).isEqualTo(0);
        assertThat(rejectedFinal).isEqualTo(1);
        assertThat(pendingEod).isEqualTo(1);
        assertThat(deliveredCount + deliveredLate + rejectedFinal + pendingEod)
                .as("M3 conservation identity (extended by the delivered-late split)")
                .isEqualTo(submitted);
    }

    @Test
    @DisplayName("C1: reset(iteration) clears per-request state so metrics reflect ONLY the final iteration")
    void resetClearsPerRequestStateBetweenIterations() throws Exception {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Person parcelA = person(population, "parcel_dhl_1_B2C", 5, "DOOR");
        Person parcelB = person(population, "parcel_dhl_2_B2C", 3, "LOCKER");

        SharedUseKpiHandler handler = new SharedUseKpiHandler(population, controlerIO());

        // DRT request ids restart at drt_0 each iteration (the QSim + its passenger-id counter are
        // rebuilt per iteration), so the SAME id is reused across the reset boundary. Without a
        // reset, iteration-0 state would leak (putIfAbsent keeps the stale parcelA submit + the
        // iter-0 dropoff), corrupting the final-iteration-only CSV written at shutdown.
        Id<Request> reqId = Id.create("drt_0", Request.class);

        // iteration 0: parcelA submitted AND delivered (time-to-delivery 1000s).
        handler.handleEvent(submitted(1000.0, reqId, parcelA.getId()));
        handler.handleEvent(droppedOff(2000.0, reqId, parcelA.getId()));

        handler.reset(1); // QSim rebuilt for iteration 1

        // iteration 1 (final): SAME request id, DIFFERENT parcel person, never delivered.
        handler.handleEvent(submitted(3000.0, reqId, parcelB.getId()));

        Path csv = Path.of(utils.getOutputDirectory()).resolve("out.csv");
        handler.writeCsv(csv);
        Map<String, String> m = readCsv(csv);

        assertThat(m.get("segments_submitted")).isEqualTo("1");
        assertThat(m.get("segments_delivered")).as("iter-0 dropoff must NOT leak past reset").isEqualTo("0");
        assertThat(m.get("parcels_submitted")).as("parcelB load (3) only, not parcelA (5)").isEqualTo("3");
        assertThat(Double.parseDouble(m.get("share_channel_locker")))
                .as("final request resolves to parcelB (LOCKER) - personByRequest was reset").isEqualTo(1.0);
        // C1: nothing delivered in the final iteration -> the right-censored delay metric must be
        // OMITTED entirely, never written as a stale 1000s from iter 0 nor as a 0.0 pseudo-result.
        assertThat(m).as("no delivery in the final iteration - line must be absent")
                .doesNotContainKey("mean_time_to_delivery_s");
    }

    @Test
    @DisplayName("I1: undelivered split into window_expired (chi cost) vs pending_open by windowEnd vs last event time")
    void undeliveredSplitByWindowEndVsLastEventTime() throws Exception {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        // last simulated (event) time below is 50000; windows straddle it.
        Person deliveredP = personWithWindow(population, "parcel_dhl_1_B2C", 2, "DOOR", 60000.0); // dropoff 50000 <= 60000 -> in-window
        Person expiredP = personWithWindow(population, "parcel_dhl_2_B2C", 1, "DOOR", 45000.0);  // <= 50000 -> expired
        Person openP = personWithWindow(population, "parcel_dhl_3_B2C", 1, "DOOR", 70000.0);      // >  50000 -> still open

        SharedUseKpiHandler handler = new SharedUseKpiHandler(population, controlerIO());

        handler.handleEvent(submitted(1000.0, Id.create("d", Request.class), deliveredP.getId()));
        handler.handleEvent(submitted(1000.0, Id.create("e", Request.class), expiredP.getId()));
        handler.handleEvent(submitted(1000.0, Id.create("o", Request.class), openP.getId()));
        handler.handleEvent(droppedOff(50000.0, Id.create("d", Request.class), deliveredP.getId())); // sets last-event-time

        Path csv = Path.of(utils.getOutputDirectory()).resolve("out.csv");
        handler.writeCsv(csv);
        Map<String, String> m = readCsv(csv);

        assertThat(m.get("segments_submitted")).isEqualTo("3");
        assertThat(m.get("segments_delivered")).isEqualTo("1");
        assertThat(m.get("segments_delivered_late")).isEqualTo("0");
        assertThat(m.get("segments_rejected_final")).isEqualTo("0");
        assertThat(m.get("segments_window_expired")).as("chi-starved past its own deadline").isEqualTo("1");
        assertThat(m.get("segments_pending_open")).as("sim ended before the deadline").isEqualTo("1");
        assertThat(m.get("segments_pending_eod")).as("window_expired + pending_open").isEqualTo("2");

        int submitted = Integer.parseInt(m.get("segments_submitted"));
        int delivered = Integer.parseInt(m.get("segments_delivered"));
        int deliveredLate = Integer.parseInt(m.get("segments_delivered_late"));
        int rejectedFinal = Integer.parseInt(m.get("segments_rejected_final"));
        int windowExpired = Integer.parseInt(m.get("segments_window_expired"));
        int pendingOpen = Integer.parseInt(m.get("segments_pending_open"));
        assertThat(delivered + deliveredLate + rejectedFinal + windowExpired + pendingOpen)
                .as("honest decomposition still conserves to submitted").isEqualTo(submitted);
    }

    @Test
    @DisplayName("I1/F4: dropoff AFTER the window end counts as delivered_late, NOT delivered (delta = in-window only)")
    void dropoffAfterWindowEndCountsAsDeliveredLate() throws Exception {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        // Window closes at 40000; the queue accepted the request in time, but the physical
        // dropoff happens at 45000 - the old handler counted this as delivered-within-window.
        Person lateP = personWithWindow(population, "parcel_dhl_1_B2C", 4, "DOOR", 40000.0);

        SharedUseKpiHandler handler = new SharedUseKpiHandler(population, controlerIO());

        Id<Request> req = Id.create("reqLate", Request.class);
        handler.handleEvent(submitted(39000.0, req, lateP.getId()));
        handler.handleEvent(droppedOff(45000.0, req, lateP.getId()));

        Path csv = Path.of(utils.getOutputDirectory()).resolve("out.csv");
        handler.writeCsv(csv);
        Map<String, String> m = readCsv(csv);

        assertThat(m.get("segments_delivered")).isEqualTo("0");
        assertThat(m.get("segments_delivered_late")).isEqualTo("1");
        assertThat(m.get("parcels_delivered")).isEqualTo("0");
        assertThat(m.get("parcels_delivered_late")).isEqualTo("4");
        // delta counts the late delivery as NOT within-window ...
        assertThat(m.get("parcels_undelivered")).isEqualTo("4");
        assertThat(Double.parseDouble(m.get("undelivered_rate"))).isEqualTo(1.0);
        // ... it is neither window_expired nor pending (it WAS physically dropped off) ...
        assertThat(m.get("segments_window_expired")).isEqualTo("0");
        assertThat(m.get("segments_pending_open")).isEqualTo("0");
        assertThat(m.get("segments_pending_eod")).isEqualTo("0");
        // ... and the in-window-only delay metric must be omitted, not fed by the late dropoff.
        assertThat(m).doesNotContainKey("mean_time_to_delivery_s");
    }

    @Test
    @DisplayName("C2/F5: injected counts come from the population snapshot; walk-fallback segments show up as never_submitted")
    void injectedCountsExposeNeverSubmittedSegments() throws Exception {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Person deliveredP = person(population, "parcel_dhl_1_B2C", 3, "DOOR");
        Person pendingP = person(population, "parcel_dhl_2_B2C", 2, "DOOR");
        // Injected but NEVER submitting: the router downgraded its drt leg to a walk fallback,
        // so no DVRP submission event ever fires for it (review C2/F5 - it used to vanish
        // from every KPI).
        person(population, "parcel_dhl_3_B2C", 4, "DOOR");

        SharedUseKpiHandler handler = new SharedUseKpiHandler(population, controlerIO());

        handler.handleEvent(submitted(1000.0, Id.create("d", Request.class), deliveredP.getId()));
        handler.handleEvent(submitted(1000.0, Id.create("p", Request.class), pendingP.getId()));
        handler.handleEvent(droppedOff(2000.0, Id.create("d", Request.class), deliveredP.getId()));

        Path csv = Path.of(utils.getOutputDirectory()).resolve("out.csv");
        handler.writeCsv(csv);
        Map<String, String> m = readCsv(csv);

        assertThat(m.get("segments_injected")).isEqualTo("3");
        assertThat(m.get("segments_submitted")).isEqualTo("2");
        assertThat(m.get("segments_never_submitted")).isEqualTo("1");
        assertThat(m.get("parcels_injected")).isEqualTo("9");
        assertThat(m.get("parcels_submitted")).isEqualTo("5");
        assertThat(m.get("parcels_never_submitted")).isEqualTo("4");
        // undelivered_rate keeps its submitted denominator (2 of 5 parcels undelivered) ...
        assertThat(Double.parseDouble(m.get("undelivered_rate"))).isCloseTo(0.4, within(1e-9));
        // ... while delivery_rate_total is the only rate the walk fallback cannot shrink: 3 of 9.
        assertThat(Double.parseDouble(m.get("delivery_rate_total")))
                .isCloseTo(3.0 / 9.0, within(1e-9));
    }

    @Test
    @DisplayName("F8/M9: per-iteration append captures each iteration's totals across a 2-iteration reset cycle")
    void iterationsFileAppendsAcrossResetCycle() throws Exception {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Person parcelA = person(population, "parcel_dhl_1_B2C", 5, "DOOR");
        Person parcelB = person(population, "parcel_dhl_2_B2C", 3, "LOCKER");

        SharedUseKpiHandler handler = new SharedUseKpiHandler(population, controlerIO());
        Path iterations = Path.of(utils.getOutputDirectory()).resolve("iterations.csv");

        Id<Request> reqId = Id.create("drt_0", Request.class);

        // iteration 0: parcelA submitted AND delivered in-window.
        handler.handleEvent(submitted(1000.0, reqId, parcelA.getId()));
        handler.handleEvent(droppedOff(2000.0, reqId, parcelA.getId()));
        handler.appendIterationRow(0, iterations);   // IterationEnds fires BEFORE the next reset

        handler.reset(1); // QSim rebuilt for iteration 1 - per-request state cleared

        // iteration 1: SAME request id, parcelB submitted, never delivered (window still open).
        handler.handleEvent(submitted(3000.0, reqId, parcelB.getId()));
        handler.appendIterationRow(1, iterations);

        List<String> lines = Files.readAllLines(iterations, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo(
                "iteration;segments_submitted;segments_delivered;segments_delivered_late;"
                        + "segments_window_expired;segments_pending_open;parcels_submitted;"
                        + "parcels_delivered;parcels_delivered_late;parcels_undelivered");
        assertThat(lines.get(1)).as("iteration 0: parcelA (load 5) delivered in-window")
                .isEqualTo("0;1;1;0;0;0;5;5;0;0");
        assertThat(lines.get(2)).as("iteration 1: parcelB (load 3) pending_open, no iter-0 leakage")
                .isEqualTo("1;1;0;0;0;1;3;0;0;3");
    }

    @Test
    @DisplayName("channel shares split between DOOR and LOCKER segments")
    void channelSharesSplitBetweenDoorAndLocker() throws Exception {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Person door = person(population, "parcel_dhl_1_B2B", 1, "DOOR");
        Person locker = person(population, "parcel_dhl_2_B2C", 1, "LOCKER");

        SharedUseKpiHandler handler = new SharedUseKpiHandler(population, controlerIO());

        handler.handleEvent(submitted(0.0, Id.create("reqDoor", Request.class), door.getId()));
        handler.handleEvent(submitted(0.0, Id.create("reqLocker", Request.class), locker.getId()));

        Path csv = Path.of(utils.getOutputDirectory()).resolve("out.csv");
        handler.writeCsv(csv);
        Map<String, String> m = readCsv(csv);

        assertThat(Double.parseDouble(m.get("share_channel_door"))).isEqualTo(0.5);
        assertThat(Double.parseDouble(m.get("share_channel_locker"))).isEqualTo(0.5);
    }

    @Test
    @DisplayName("channel shares are LOAD-weighted, not segment-weighted (door load=5, locker load=1)")
    void channelSharesAreLoadWeightedNotSegmentWeighted() throws Exception {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Person door = person(population, "parcel_dhl_1_B2B", 5, "DOOR");
        Person locker = person(population, "parcel_dhl_2_B2C", 1, "LOCKER");

        SharedUseKpiHandler handler = new SharedUseKpiHandler(population, controlerIO());

        handler.handleEvent(submitted(0.0, Id.create("reqDoor", Request.class), door.getId()));
        handler.handleEvent(submitted(0.0, Id.create("reqLocker", Request.class), locker.getId()));

        Path csv = Path.of(utils.getOutputDirectory()).resolve("out.csv");
        handler.writeCsv(csv);
        Map<String, String> m = readCsv(csv);

        // Segment-weighting (one vote per submitted segment) would wrongly give 0.5 / 0.5 here -
        // load-weighting must reflect the 5:1 parcel-count split instead.
        assertThat(Double.parseDouble(m.get("share_channel_door"))).isCloseTo(5.0 / 6.0, within(1e-9));
        assertThat(Double.parseDouble(m.get("share_channel_locker"))).isCloseTo(1.0 / 6.0, within(1e-9));
    }

    // -------------------------------------------------------------------------

    /**
     * A parcel-person with the FULL attribute set ParcelAgentGenerator writes. The window-end
     * default matters: the handler now builds its snapshots via {@link ParcelAttributes}, which
     * refuses a parcel-person missing any of them rather than defaulting (a missing load used to
     * count as 0 parcels, a missing channel as DOOR). Tests that care about a specific deadline
     * use {@link #personWithWindow}; everyone else gets the B2C default, so an undelivered
     * segment lands in pending_open unless the test drives the clock past 20:00.
     */
    private static Person person(Population population, String id, int load, String channel) {
        Person p = population.getFactory().createPerson(Id.createPersonId(id));
        p.getAttributes().putAttribute(SharedUse.LOAD_ATTRIBUTE, load);
        p.getAttributes().putAttribute(SharedUse.CHANNEL_ATTRIBUTE, channel);
        p.getAttributes().putAttribute(SharedUse.WINDOW_END_ATTRIBUTE, SharedUse.B2C_WINDOW_END_S);
        p.getAttributes().putAttribute(SharedUse.DWELL_ATTRIBUTE, SharedUse.segmentDwellSeconds(load));
        population.addPerson(p);
        return p;
    }

    private static Person personWithWindow(Population population, String id, int load, String channel,
                                           double windowEndSeconds) {
        Person p = person(population, id, load, channel);
        p.getAttributes().putAttribute(SharedUse.WINDOW_END_ATTRIBUTE, windowEndSeconds);
        return p;
    }

    private OutputDirectoryHierarchy controlerIO() throws Exception {
        Path dir = Path.of(utils.getOutputDirectory()).toAbsolutePath();
        Files.createDirectories(dir);
        return new OutputDirectoryHierarchy(dir.toString(),
                OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles,
                ControllerConfigGroup.CompressionType.gzip);
    }

    private static PassengerRequestSubmittedEvent submitted(double time, Id<Request> reqId, Id<Person> personId) {
        return new PassengerRequestSubmittedEvent(time, "drt", reqId, List.of(personId),
                Id.createLinkId("fromLink"), Id.createLinkId("toLink"), null, null);
    }

    private static PassengerDroppedOffEvent droppedOff(double time, Id<Request> reqId, Id<Person> personId) {
        return new PassengerDroppedOffEvent(time, "drt", reqId, personId, Id.create("veh1", DvrpVehicle.class));
    }

    private static Map<String, String> readCsv(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("metric;value");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(";", 2);
            map.put(parts[0], parts[1]);
        }
        return map;
    }
}
