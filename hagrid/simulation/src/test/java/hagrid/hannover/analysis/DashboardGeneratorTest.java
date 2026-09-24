package hagrid.hannover.analysis;

import hagrid.hannover.analysis.CarrierXmlParser.ParsedCarrier;
import hagrid.hannover.analysis.DashboardGenerator.StemKm;
import hagrid.hannover.analysis.FreightEventHandler.LinkVisit;
import hagrid.hannover.analysis.FreightEventHandler.ServiceEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.network.NetworkUtils;
import org.matsim.vehicles.Vehicle;

import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("DashboardGenerator")
class DashboardGeneratorTest {

    private static ParsedCarrier carrier(String id, Map<String, String> attrs) {
        return new ParsedCarrier(id, "delivery", id, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), 0, attrs, Map.of(), Map.of());
    }

    /** a=1km, b=2km, c=3km, d=4km; anything else 0 (link absent from the network). */
    private static final ToDoubleFunction<String> LINK_KM = id -> switch (id) {
        case "a" -> 1.0;
        case "b" -> 2.0;
        case "c" -> 3.0;
        case "d" -> 4.0;
        default -> 0.0;
    };

    private static ServiceEvent start(double t) {
        return new ServiceEvent("veh", "stop", t, true);
    }

    private static ServiceEvent end(double t) {
        return new ServiceEvent("veh", "stop", t, false);
    }

    @Test
    @DisplayName("sumIntAttr sums an int carrier attribute, treating missing/garbage as 0")
    void sumsIntAttributeAcrossCarriers() {
        ParsedCarrier withUnassigned = carrier("dhl",
                Map.of("unassignedParcels", "7", "unassignedJobs", "2"));
        ParsedCarrier withoutAttr = carrier("hermes", Map.of());
        ParsedCarrier garbageAttr = carrier("dpd", Map.of("unassignedParcels", "n/a"));

        List<ParsedCarrier> carriers = List.of(withUnassigned, withoutAttr, garbageAttr);

        assertThat(DashboardGenerator.sumIntAttr(carriers, "unassignedParcels")).isEqualTo(7);
        assertThat(DashboardGenerator.sumIntAttr(carriers, "unassignedJobs")).isEqualTo(2);
    }

    @Test
    @DisplayName("eventStemKm splits driven links into access before the first stop and egress after the last")
    void splitsDrivenLinksIntoAccessAndEgress() {
        // depot -> a -> b -> [stop 250..400] -> c -> depot link d (tour-end activity)
        List<LinkVisit> driven = List.of(
                new LinkVisit("a", 100), new LinkVisit("b", 200),
                new LinkVisit("c", 500), new LinkVisit("d", 600));
        List<ServiceEvent> services = List.of(start(250), end(400));

        StemKm stem = DashboardGenerator.eventStemKm(driven, services, LINK_KM);

        assertThat(stem.accessKm()).isCloseTo(3.0, within(1e-9));   // a + b
        assertThat(stem.egressKm()).isCloseTo(7.0, within(1e-9));   // c + d
    }

    @Test
    @DisplayName("eventStemKm ignores the inter-stop legs between the first and the last service")
    void ignoresInterStopLegs() {
        // two stops: the b-leg between them is line-haul, not stem
        List<LinkVisit> driven = List.of(
                new LinkVisit("a", 100), new LinkVisit("b", 300), new LinkVisit("c", 900));
        List<ServiceEvent> services = List.of(
                start(200), end(250), start(400), end(450));

        StemKm stem = DashboardGenerator.eventStemKm(driven, services, LINK_KM);

        assertThat(stem.accessKm()).isCloseTo(1.0, within(1e-9));   // a only
        assertThat(stem.egressKm()).isCloseTo(3.0, within(1e-9));   // c only
    }

    @Test
    @DisplayName("eventStemKm counts a link left exactly at the first service start as access")
    void countsLinkLeftAtServiceStartAsAccess() {
        List<LinkVisit> driven = List.of(new LinkVisit("a", 200));
        List<ServiceEvent> services = List.of(start(200), end(300));

        StemKm stem = DashboardGenerator.eventStemKm(driven, services, LINK_KM);

        assertThat(stem.accessKm()).isCloseTo(1.0, within(1e-9));
        assertThat(stem.egressKm()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("eventStemKm returns zero for a vehicle that never serves a stop")
    void returnsZeroWithoutServiceEvents() {
        List<LinkVisit> driven = List.of(new LinkVisit("a", 100), new LinkVisit("b", 200));

        StemKm stem = DashboardGenerator.eventStemKm(driven, List.of(), LINK_KM);

        assertThat(stem.accessKm()).isCloseTo(0.0, within(1e-9));
        assertThat(stem.egressKm()).isCloseTo(0.0, within(1e-9));
    }

    // ── wiring: the same measurement, but taken off a real FreightEventHandler ──

    /** Chain of unit-less nodes; every link gets the length its id maps to in LINK_KM. */
    private static Network networkWith(String... linkIds) {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory f = net.getFactory();
        Node prev = f.createNode(Id.createNodeId("n0"), new Coord(0, 0));
        net.addNode(prev);
        for (int i = 0; i < linkIds.length; i++) {
            Node next = f.createNode(Id.createNodeId("n" + (i + 1)), new Coord(i + 1, 0));
            net.addNode(next);
            Link l = f.createLink(Id.createLinkId(linkIds[i]), prev, next);
            l.setLength(LINK_KM.applyAsDouble(linkIds[i]) * 1000.0);
            net.addLink(l);
            prev = next;
        }
        return net;
    }

    @Test
    @DisplayName("eventStemKmByVehicle measures access and egress off the executed events, not the plan")
    void measuresStemFromEventHandler() {
        String veh = "hannover_veh_cep_van_17";
        Id<Vehicle> vid = Id.create(veh, Vehicle.class);
        Id<Person> pid = Id.create(veh, Person.class);
        Network net = networkWith("a", "b", "c", "d");

        FreightEventHandler h = new FreightEventHandler();
        h.handleEvent(new ActivityEndEvent(100, pid, Id.createLinkId("a"), null, "start"));
        h.handleEvent(new LinkLeaveEvent(110, vid, Id.createLinkId("a")));      // access 1 km
        h.handleEvent(new LinkLeaveEvent(120, vid, Id.createLinkId("b")));      // access 2 km
        h.handleEvent(new ActivityStartEvent(130, pid, Id.createLinkId("s1"), null, "service"));
        h.handleEvent(new ActivityEndEvent(200, pid, Id.createLinkId("s1"), null, "service"));
        h.handleEvent(new LinkLeaveEvent(210, vid, Id.createLinkId("c")));      // between stops: neither
        h.handleEvent(new ActivityStartEvent(220, pid, Id.createLinkId("s2"), null, "service"));
        h.handleEvent(new ActivityEndEvent(300, pid, Id.createLinkId("s2"), null, "service"));
        h.handleEvent(new LinkLeaveEvent(310, vid, Id.createLinkId("d")));      // egress 4 km
        h.handleEvent(new ActivityStartEvent(320, pid, Id.createLinkId("a"), null, "end"));  // egress 1 km

        StemKm stem = DashboardGenerator.eventStemKmByVehicle(h, net).get(veh);

        assertThat(stem).isNotNull();
        assertThat(stem.accessKm()).isCloseTo(3.0, within(1e-9));
        assertThat(stem.egressKm()).isCloseTo(5.0, within(1e-9));
    }
}
