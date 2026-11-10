package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.vehicles.VehicleUtils;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class TravelTimeSnapshotTest {

    @Test
    void refusesNetworkMutationDuringSampling() {
        var network = network();
        TravelTime mutating = (l, t, p, v) -> {
            l.getFromNode().setCoord(new Coord(900, 900));
            return 10;
        };
        assertThatIllegalArgumentException().isThrownBy(() ->
                TravelTimeSnapshot.capture(network, Map.of("car", mutating), Map.of(), 1, 3600, 900))
                .withMessageContaining("changed during").withMessageContaining("snapshot");
    }

    @Test
    void fingerprintChangesWhenGeometryChangesEvenWithIdenticalTravelTimes() {
        var first = network();
        var second = network();
        link(second).getFromNode().setCoord(new Coord(900, 900));
        TravelTime constant = (l, t, p, v) -> 10;
        var a = TravelTimeSnapshot.capture(first, Map.of("car", constant), Map.of(), 1, 3600, 900);
        var b = TravelTimeSnapshot.capture(second, Map.of("car", constant), Map.of(), 1, 3600, 900);
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }


    @Test
    void survivesCalculatorResetAndNetworkMutation() {
        var network = network();
        var observed = network.getLinks().get(Id.createLinkId("observed"));
        var unobserved = network.getLinks().get(Id.createLinkId("unobserved"));
        var builder = new TravelTimeCalculator.Builder(network);
        builder.setTimeslice(900);
        builder.setMaxTime(3600);
        var calculator = builder.build();
        var van = Id.createVehicleId("van");
        calculator.handleEvent(new LinkEnterEvent(0, van, observed.getId()));
        calculator.handleEvent(new LinkLeaveEvent(30, van, observed.getId()));
        TravelTime measured = calculator.getLinkTravelTimes();
        TravelTime fallback = (link, time, person, vehicle) -> link.getLength() / link.getFreespeed(time);
        var snapshot = TravelTimeSnapshot.capture(network, Map.of("car", measured), Map.of("car", fallback), 25, 3600, 900);
        double before = snapshot.forMode("car").getLinkTravelTime(observed, 60, null, null);
        double unobservedBefore = snapshot.forMode("car").getLinkTravelTime(unobserved, 60, null, null);
        String fingerprint = snapshot.fingerprint();
        assertThat(before).isEqualTo(30);
        assertThat(unobservedBefore).isEqualTo(10);
        calculator.reset(26);
        observed.setFreespeed(2);
        unobserved.setLength(200);
        unobserved.setFreespeed(2);
        assertThat(snapshot.forMode("car").getLinkTravelTime(observed, 60, null, null)).isEqualTo(before);
        assertThat(snapshot.forMode("car").getLinkTravelTime(unobserved, 60, null, null)).isEqualTo(unobservedBefore);
        assertThat(measured.getLinkTravelTime(unobserved, 60, null, null)).isEqualTo(100);
        assertThat(snapshot.fingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void explicitMissingObservationUsesCapturedFallback() {
        var network = network();
        var fallbackSeconds = new java.util.concurrent.atomic.AtomicInteger(12);
        TravelTime measured = (link, time, person, vehicle) -> Double.NaN;
        TravelTime fallback = (link, time, person, vehicle) -> fallbackSeconds.get();
        var snapshot = TravelTimeSnapshot.capture(network, Map.of("car", measured), Map.of("car", fallback), 1, 3600, 900);
        fallbackSeconds.set(99);
        assertThat(snapshot.forMode("car").getLinkTravelTime(link(network), 0, null, null)).isEqualTo(12);
    }

    @Test
    void vehicleCapUsesFrozenLengthAndAllowsSlowerObservedTravel() {
        var network = network();
        var link = link(network);
        TravelTime measured = (edge, time, person, vehicle) -> time < 900 ? 10 : 40;
        var snapshot = TravelTimeSnapshot.capture(network, Map.of("car", measured), Map.of(), 1, 3600, 900);
        var type = VehicleUtils.createVehicleType(Id.create("slow", org.matsim.vehicles.VehicleType.class));
        type.setNetworkMode("car");
        type.setMaximumVelocity(5);
        var vehicle = VehicleUtils.createVehicle(Id.createVehicleId("slow"), type);
        link.setLength(1000);
        assertThat(snapshot.forMode("car").getLinkTravelTime(link, 0, null, vehicle)).isEqualTo(20);
        assertThat(snapshot.forMode("car").getLinkTravelTime(link, 900, null, vehicle)).isEqualTo(40);
    }

    @Test
    void modesAndBinBoundariesHaveIndependentCapturedValues() {
        var network = network();
        var snapshot = TravelTimeSnapshot.capture(network,
                Map.of("car", (link, time, p, v) -> time < 900 ? 10 : 30),
                Map.of("bike", (link, time, p, v) -> 20), 1, 1800, 900);
        assertThat(snapshot.forMode("car").getLinkTravelTime(link(network), 899, null, null)).isEqualTo(10);
        assertThat(snapshot.forMode("car").getLinkTravelTime(link(network), 900, null, null)).isEqualTo(30);
        assertThat(snapshot.forMode("bike").getLinkTravelTime(link(network), 900, null, null)).isEqualTo(20);
        assertThatIllegalArgumentException().isThrownBy(() -> snapshot.forMode("walk")).withMessageContaining("walk");
    }

    @ParameterizedTest @ValueSource(ints = {0, -1})
    void invalidBinWidthsAreRejected(int bins) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                TravelTimeSnapshot.capture(network(), Map.of(), Map.of("car", (l,t,p,v) -> 10), 1, 3600, bins))
                .withMessageContaining("bin");
    }

    @ParameterizedTest @ValueSource(doubles = {-1, 3600, Double.NaN, Double.POSITIVE_INFINITY})
    void queryOutsideCapturedRangeFailsClearly(double time) {
        var network = network();
        var snapshot = TravelTimeSnapshot.capture(network, Map.of(), Map.of("car", (l,t,p,v) -> 10), 1, 3600, 900);
        assertThatIllegalArgumentException().isThrownBy(() ->
                snapshot.forMode("car").getLinkTravelTime(link(network), time, null, null)).withMessageContaining("range");
    }

    @Test
    void fingerprintsAreDeterministicAndIncludeResolutionAndObservationIteration() {
        var network = network();
        Map<String, TravelTime> fallback = Map.of("car", (l,t,p,v) -> 10);
        var a = TravelTimeSnapshot.capture(network, Map.of(), fallback, 1, 3600, 900);
        var b = TravelTimeSnapshot.capture(network, Map.of(), fallback, 1, 3600, 900);
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        assertThat(a.fingerprint()).isNotEqualTo(TravelTimeSnapshot.capture(network, Map.of(), fallback, 2, 3600, 900).fingerprint());
        assertThat(a.fingerprint()).isNotEqualTo(TravelTimeSnapshot.capture(network, Map.of(), fallback, 1, 3600, 450).fingerprint());
        assertThat(a.fingerprint()).isNotEqualTo(TravelTimeSnapshot.capture(network, Map.of(), fallback, 1, 7200, 900).fingerprint());
    }

    @Test
    void constantProfilesDoNotRetainOneValuePerTimeBinOrVehicle() {
        var network = network();
        var snapshot = TravelTimeSnapshot.capture(network, Map.of(), Map.of("car", (l,t,p,v) -> 10), 1, 86400, 900);
        assertThat(snapshot.storageStats().profileCount()).isEqualTo(2);
        assertThat(snapshot.storageStats().retainedValueCount()).isEqualTo(2);
        assertThat(snapshot.storageStats().numericPayloadBytes()).isEqualTo(40);
    }

    @Test
    void invalidMeasuredTimesAreNotSilentlyReplacedWithFreeSpeed() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                TravelTimeSnapshot.capture(network(), Map.of("car", (l,t,p,v) -> -5),
                        Map.of("car", (l,t,p,v) -> 10), 1, 3600, 900)).withMessageContaining("travel time");
    }

    @Test
    void capturedAccessNeverCallsMutableSourcesAgainAndRejectsUncapturedLinks() {
        var network = network();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var snapshot = TravelTimeSnapshot.capture(network, Map.of(),
                Map.of("car", (l,t,p,v) -> { calls.incrementAndGet(); return 10; }), 1, 3600, 900);
        int capturedCalls = calls.get();
        snapshot.forMode("car").getLinkTravelTime(link(network), 5, null, null);
        snapshot.forMode("car").getLinkTravelTime(link(network), 950, null, null);
        assertThat(calls.get()).isEqualTo(capturedCalls);
        var foreign = network.getFactory().createLink(Id.createLinkId("foreign"),
                link(network).getFromNode(), link(network).getToNode());
        assertThatIllegalArgumentException().isThrownBy(() ->
                snapshot.forMode("car").getLinkTravelTime(foreign, 5, null, null)).withMessageContaining("foreign");
    }

    @Test
    void modeRestrictedLinksAndConflictingVehiclesFailInsteadOfReceivingFakeTravelTimes() {
        var network = network();
        var link = link(network);
        link.setAllowedModes(Set.of("bike"));
        var snapshot = TravelTimeSnapshot.capture(network, Map.of(),
                Map.of("car", (l,t,p,v) -> 10, "bike", (l,t,p,v) -> 20), 1, 3600, 900);
        assertThatIllegalArgumentException().isThrownBy(() ->
                snapshot.forMode("car").getLinkTravelTime(link, 0, null, null)).withMessageContaining("car");
        var type = VehicleUtils.createVehicleType(Id.create("van", org.matsim.vehicles.VehicleType.class));
        type.setNetworkMode("car");
        var vehicle = VehicleUtils.createVehicle(Id.createVehicleId("van"), type);
        assertThatIllegalArgumentException().isThrownBy(() ->
                snapshot.forMode("bike").getLinkTravelTime(link, 0, null, vehicle)).withMessageContaining("disagrees");
    }
    @Test
    void equalNetworksWithDifferentLinkInsertionOrdersHaveTheSameSnapshotIdentity() {
        var first = network();
        var second = network();
        var observed = second.removeLink(Id.createLinkId("observed"));
        second.addLink(observed);
        TravelTime measured = (l,t,p,v) -> l.getId().equals(Id.createLinkId("observed")) ? 30 : 10;
        var a = TravelTimeSnapshot.capture(first, Map.of("car", measured), Map.of(), 1, 3600, 900);
        var b = TravelTimeSnapshot.capture(second, Map.of("car", measured), Map.of(), 1, 3600, 900);
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    @Test
    void actualCalculatorBinDecreaseIsRetainedAndDiagnosedBeforeFifoRouting() {
        var network = network();
        var observed = link(network);
        var calculator = new TravelTimeCalculator.Builder(network);
        calculator.setTimeslice(60); calculator.setMaxTime(180);
        var times = calculator.build();
        var vehicle = Id.createVehicleId("observed-van");
        times.handleEvent(new LinkEnterEvent(0, vehicle, observed.getId()));
        times.handleEvent(new LinkLeaveEvent(30, vehicle, observed.getId()));
        var snapshot = TravelTimeSnapshot.capture(network,
                Map.of("car", times.getLinkTravelTimes()), Map.of(), 1, 180, 60);
        assertThat(snapshot.forMode("car").getLinkTravelTime(observed, 0, null, null)).isEqualTo(30);
        assertThat(snapshot.forMode("car").getLinkTravelTime(observed, 60, null, null)).isEqualTo(10);
        assertThatIllegalArgumentException().isThrownBy(() -> snapshot.validateFifo("car"))
                .withMessageContaining("Non-FIFO").withMessageContaining("boundary 60.0 s").withMessageContaining("mode car").withMessageContaining("link observed");
    }

    private static Link link(Network network) { return network.getLinks().get(Id.createLinkId("observed")); }

    private static Network network() {
        var network = NetworkUtils.createNetwork();
        var a = network.getFactory().createNode(Id.createNodeId("a"), new Coord(0,0));
        var b = network.getFactory().createNode(Id.createNodeId("b"), new Coord(100,0));
        network.addNode(a);
        network.addNode(b);
        for (String id : new String[]{"observed", "unobserved"}) {
            var link = network.getFactory().createLink(Id.createLinkId(id), a, b);
            link.setLength(100);
            link.setFreespeed(10);
            link.setCapacity(1000);
            link.setNumberOfLanes(1);
            link.setAllowedModes(Set.of("car", "bike"));
            network.addLink(link);
        }
        return network;
    }

    @Test
    void mixedProfileLengthsKeepAllValuesAfterFurtherCaptureAndArchiveRoundTrip() throws Exception {
        var network = network();
        var template = link(network);
        for (int i=0;i<80;i++) {
            var edge=network.getFactory().createLink(Id.createLinkId("profile-"+i),template.getFromNode(),template.getToNode());
            edge.setLength(100);edge.setFreespeed(10);edge.setAllowedModes(Set.of("car","bike"));
            network.addLink(edge);
        }
        // Unequal profile lengths and archive ordering must not change slot-to-link association.
        TravelTime measured=(edge,time,p,v)-> {
            String id=edge.getId().toString();
            int period=id.startsWith("profile-")?Integer.parseInt(id.substring(8))%11:0;
            return period==0?17:5+((int)time/period)%19;
        };
        var snapshot=TravelTimeSnapshot.capture(network,Map.of("car",measured),
                Map.of("bike",(l,t,p,v)->31),3,5001,1);
        var bytes=new java.io.ByteArrayOutputStream();
        snapshot.write(new java.io.DataOutputStream(bytes));
        var restored=TravelTimeSnapshot.read(new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray())),network);
        var again=new java.io.ByteArrayOutputStream();
        restored.write(new java.io.DataOutputStream(again));
        assertThat(again.toByteArray()).isEqualTo(bytes.toByteArray());
        assertThat(restored.fingerprint()).isEqualTo(snapshot.fingerprint());
        assertThat(restored.storageStats()).isEqualTo(snapshot.storageStats());
        assertThat(restored.isConstant("bike")).isTrue();
        assertThat(restored.isConstant("car")).isFalse();
        var indexed=restored.new IndexedMode("car");
        for(var edge:network.getLinks().values()) {
            var from=Id.createLinkId(edge.getId().toString().equals("observed")?"unobserved":"observed");
            var path=indexed.new TimedPath(java.util.List.of(),from,edge.getId());
            for(int bin=0;bin<5001;bin++) {
                double expected=measured.getLinkTravelTime(edge,bin,null,null);
                org.junit.jupiter.api.Assertions.assertEquals(expected,snapshot.forMode("car").getLinkTravelTime(edge,bin+.5,null,null));
                org.junit.jupiter.api.Assertions.assertEquals(expected,restored.forMode("car").getLinkTravelTime(edge,bin+.5,null,null));
                org.junit.jupiter.api.Assertions.assertEquals(31,restored.forMode("bike").getLinkTravelTime(edge,bin+.5,null,null));
                org.junit.jupiter.api.Assertions.assertEquals(expected,path.travelSeconds(bin+.5,Double.POSITIVE_INFINITY));
            }
        }
    }

}
