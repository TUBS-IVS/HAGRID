package hagrid.lausitz.freight;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LmdCacheReplayValidationTest {
    @TempDir Path directory;
    private final Network network = NetworkUtils.createNetwork();
    private final CarrierVehicleTypes types = new CarrierVehicleTypes();
    private final VehicleType type = VehicleUtils.createVehicleType(Id.create("van", VehicleType.class), "car");
    LmdCacheReplayValidationTest() {
        for (int i = 0; i < 3; i++) network.addNode(network.getFactory().createNode(Id.createNodeId("n"+i), new Coord(i*100,0)));
        for (int i = 0; i < 3; i++) {
            var link = network.getFactory().createLink(Id.createLinkId("l"+i), network.getNodes().get(Id.createNodeId("n"+i)),
                    network.getNodes().get(Id.createNodeId("n"+((i+1)%3))));
            link.setLength(100); link.setFreespeed(10); link.setCapacity(1000); link.setNumberOfLanes(1);
            link.setAllowedModes(Set.of("car")); network.addLink(link);
        }
        type.setMaximumVelocity(10); type.getCapacity().setOther(10);
        type.getCostInformation().setFixedCost(10.0).setCostsPerMeter(0.1).setCostsPerSecond(1.0);
        types.getVehicleTypes().put(type.getId(), type);
    }
    private Carrier carrier() {
        var carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
        CarriersUtils.setCarrierMode(carrier, "car");
        carrier.getAttributes().putAttribute("provider", "original-provider");
        var missed = new ArrayList<>(List.of(Id.create("job", CarrierService.class), Id.create("job", CarrierService.class)));
        carrier.getAttributes().putAttribute("missedParcelsAsList", missed);
        carrier.getAttributes().putAttribute("missedParcelDeliveriesAsString", missed.toString());
        carrier.getAttributes().putAttribute("missedParcels", missed.size());
        var vehicle = CarrierVehicle.Builder.newInstance(Id.createVehicleId("carrier_h8_v0"), Id.createLinkId("l0"), type)
                .setEarliestStart(28800.25).setLatestEnd(60000.75).build();
        carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(vehicle)
                .setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
        CarriersUtils.addService(carrier, CarrierService.Builder.newInstance(Id.create("job", CarrierService.class), Id.createLinkId("l1"))
                .setCapacityDemand(1).setServiceDuration(10).setServiceStartingTimeWindow(TimeWindow.newInstance(0,75600)).build());
        return carrier;
    }
    private Path routed(boolean retime) {
        var solved = new Carriers(List.of(carrier()));
        LausitzFreightPreprocessor.routeWithDurationCap(solved, network, types, 1, 25200, retime ? c -> new Random(42) : null);
        Path file = directory.resolve("cached.xml");
        new CarrierPlanWriter(solved).write(file.toString());
        return file;
    }
    private void validate(Path file, boolean waves) throws Exception {
        LmdCachedPlanValidator.replay(file, new Carriers(List.of(carrier())), types, network, 25200, waves);
    }
    @Test void regularNativeRoundedAvailabilityIsAcceptedWithoutChangingCanonicalInput() throws Exception {
        var canonical = carrier(); Path file = routed(false);
        var attributes = new HashMap<>(canonical.getAttributes().getAsMap());
        LmdCachedPlanValidator.replay(file, new Carriers(List.of(canonical)), types, network, 25200, false);
        assertThat(canonical.getSelectedPlan()).isNull();
        assertThat(canonical.getAttributes().getAsMap()).isEqualTo(attributes);
        assertThat(canonical.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getEarliestStartTime()).isEqualTo(28800.25);
    }
    @Test void approvedBaselineWaveVehicleIsRevalidated() throws Exception {
        validate(routed(true), true);
    }
    @Test void baselineWaveVehicleIsRejectedInNonRetimedRegime() {
        Path file = routed(true);
        assertThatThrownBy(() -> validate(file, false)).hasMessageContaining("vehicle");
    }
    @Test void changedDemandIsRejectedEvenWhenPlanRemainsSyntacticallyReadable() throws Exception {
        Path file = routed(false);
        String xml = Files.readString(file);
        assertThat(xml).contains("capacityDemand=\"1\"");
        Files.writeString(file, xml.replace("capacityDemand=\"1\"", "capacityDemand=\"2\""));
        assertThatThrownBy(() -> validate(file, false)).hasMessageContaining("jobs");
    }
    @Test void disconnectedStoredRoadIsRejected() throws Exception {
        Path file = routed(false);
        String xml = Files.readString(file);
        assertThat(xml).contains("<route>l2</route>");
        Files.writeString(file, xml.replace("<route>l2</route>", "<route>l1</route>"));
        assertThatThrownBy(() -> validate(file, false)).hasMessageContaining("road");
    }
    @Test void omittedCarrierIsNotAValidWholeFileHit() throws Exception {
        Path file = directory.resolve("empty.xml");
        new CarrierPlanWriter(new Carriers()).write(file.toString());
        assertThatThrownBy(() -> validate(file, false)).hasMessageContaining("carrier");
    }
    @Test void changedCarrierMetadataIsRejectedBeforeCopyingAHit() throws Exception {
        Path file = routed(false);
        String xml = Files.readString(file);
        assertThat(xml).contains("original-provider");
        Files.writeString(file, xml.replace("original-provider", "wrong-provider"));
        assertThatThrownBy(() -> validate(file, false)).hasMessageContaining("metadata");
    }
    @Test void changedPersistedMissedListIsRejected() throws Exception {
        Path file = routed(false);
        String xml = Files.readString(file);
        assertThat(xml).contains("[job, job]");
        Files.writeString(file, xml.replace("[job, job]", "[job, other]"));
        assertThatThrownBy(() -> validate(file, false)).hasMessageContaining("metadata");
    }
    @Test void incompleteCanonicalMissedListCertificateIsNotAccepted() throws Exception {
        Path file = routed(false);
        var canonical = carrier();
        canonical.getAttributes().putAttribute("missedParcels", 1);
        assertThatThrownBy(() -> LmdCachedPlanValidator.replay(file, new Carriers(List.of(canonical)),
                types, network, 25200, false)).hasMessageContaining("metadata");
    }    @Test void parseErrorIsDiagnosedAsUnavailableCacheInput() throws Exception {
        Path file = directory.resolve("invalid.xml"); Files.writeString(file, "not XML");
        assertThatThrownBy(() -> validate(file, false)).isInstanceOf(java.io.IOException.class);
    }
}