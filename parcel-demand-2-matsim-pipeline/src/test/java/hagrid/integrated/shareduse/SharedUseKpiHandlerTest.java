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
 * {@code submitted - delivered - rejected_final} (M3 conservation).
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

        handler.handleEvent(droppedOff(2800.0, reqA, parcelA.getId()));   // delay = 1800s
        handler.handleEvent(droppedOff(1100.0, reqPax, pax.getId()));    // pax dropoff - must be ignored
        // reqB: no dropoff, no reject -> still pending at EOD

        Path csv = Path.of(utils.getOutputDirectory()).resolve("out.csv");
        handler.writeCsv(csv);
        Map<String, String> m = readCsv(csv);

        assertThat(m.get("segments_submitted")).isEqualTo("2");
        assertThat(m.get("segments_delivered")).isEqualTo("1");
        assertThat(m.get("segments_rejected_final")).isEqualTo("0");
        assertThat(m.get("segments_pending_eod")).isEqualTo("1");
        assertThat(m.get("parcels_submitted")).isEqualTo("5");
        assertThat(m.get("parcels_delivered")).isEqualTo("3");
        assertThat(m.get("parcels_undelivered")).isEqualTo("2");
        assertThat(Double.parseDouble(m.get("undelivered_rate"))).isEqualTo(0.4);
        assertThat(Double.parseDouble(m.get("share_channel_door"))).isEqualTo(1.0);
        assertThat(Double.parseDouble(m.get("share_channel_locker"))).isEqualTo(0.0);
        assertThat(Double.parseDouble(m.get("mean_delivery_delay_s"))).isEqualTo(1800.0);
    }

    @Test
    @DisplayName("M3 conservation: delivered + rejected_final + pending_eod == submitted, with a real reject event")
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
        int rejectedFinal = Integer.parseInt(m.get("segments_rejected_final"));
        int pendingEod = Integer.parseInt(m.get("segments_pending_eod"));

        assertThat(submitted).isEqualTo(3);
        assertThat(deliveredCount).isEqualTo(1);
        assertThat(rejectedFinal).isEqualTo(1);
        assertThat(pendingEod).isEqualTo(1);
        assertThat(deliveredCount + rejectedFinal + pendingEod)
                .as("M3 conservation identity").isEqualTo(submitted);
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

        // iteration 0: parcelA submitted AND delivered (delay 1000s).
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
        assertThat(Double.parseDouble(m.get("mean_delivery_delay_s")))
                .as("no delivery in the final iteration - not the stale 1000s from iter 0").isEqualTo(0.0);
    }

    @Test
    @DisplayName("I1: undelivered split into window_expired (chi cost) vs pending_open by windowEnd vs last event time")
    void undeliveredSplitByWindowEndVsLastEventTime() throws Exception {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        // last simulated (event) time below is 50000; windows straddle it.
        Person deliveredP = personWithWindow(population, "parcel_dhl_1_B2C", 2, "DOOR", 40000.0);
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
        assertThat(m.get("segments_rejected_final")).isEqualTo("0");
        assertThat(m.get("segments_window_expired")).as("chi-starved past its own deadline").isEqualTo("1");
        assertThat(m.get("segments_pending_open")).as("sim ended before the deadline").isEqualTo("1");
        assertThat(m.get("segments_pending_eod")).as("window_expired + pending_open").isEqualTo("2");

        int submitted = Integer.parseInt(m.get("segments_submitted"));
        int delivered = Integer.parseInt(m.get("segments_delivered"));
        int rejectedFinal = Integer.parseInt(m.get("segments_rejected_final"));
        int windowExpired = Integer.parseInt(m.get("segments_window_expired"));
        int pendingOpen = Integer.parseInt(m.get("segments_pending_open"));
        assertThat(delivered + rejectedFinal + windowExpired + pendingOpen)
                .as("honest decomposition still conserves to submitted").isEqualTo(submitted);
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

    private static Person person(Population population, String id, int load, String channel) {
        Person p = population.getFactory().createPerson(Id.createPersonId(id));
        p.getAttributes().putAttribute(SharedUse.LOAD_ATTRIBUTE, load);
        p.getAttributes().putAttribute(SharedUse.CHANNEL_ATTRIBUTE, channel);
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
