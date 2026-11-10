package hagrid.hannover.demand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import hagrid.hannover.pipeline.CarrierMergeLog;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CarrierMergeConservationTest {
    @TempDir Path directory;

    @Test void mergePreservesParcelsAndMissedMultiplicityWithoutReplacingTargetHub() {
        var target = carrier("A", "a", 200, 20);
        var source = carrier("B", "b", 10, 10);
        Object hub = new Object(); target.getAttributes().putAttribute("hub", hub);
        new CarrierMergeService().merge(target, source);
        assertThat(target.getAttributes().getAttribute("numberOfParcels")).isEqualTo(210);
        assertThat(target.getAttributes().getAttribute("missedParcels")).isEqualTo(30);
        assertThat((List<?>) target.getAttributes().getAttribute("missedParcelsAsList")).hasSize(30);
        assertThat((double) target.getAttributes().getAttribute("deliverySuccessRatePercent"))
                .isCloseTo(100.0 * 180 / 210, within(1e-9));
        assertThat(target.getAttributes().getAttribute("hub")).isSameAs(hub);
        assertThat(source.getServices()).isEmpty();
        assertThat(source.getAttributes().getAttribute("numberOfParcels")).isEqualTo(0);
    }

    @Test void duplicateIdIsRejectedBeforeAnyMutation() {
        var target = carrier("A", "same", 200, 20);
        var source = carrier("B", "same", 10, 10);
        var before = new LinkedHashMap<>(target.getAttributes().getAsMap());
        assertThatThrownBy(() -> new CarrierMergeService().merge(target, source))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate service ID");
        assertThat(target.getServices()).hasSize(1);
        assertThat(source.getServices()).hasSize(1);
        assertThat(target.getAttributes().getAsMap()).isEqualTo(before);
    }

    @Test void transferFailureRestoresBothCarriersIncludingTheirOriginalMetadataLists() {
        var normal = carrier("A", "a", 200, 20);
        var jobs = new LinkedHashMap<>(normal.getServices());
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        Map<Id<CarrierService>, CarrierService> rejecting = new LinkedHashMap<>(jobs) {
            @Override public CarrierService put(Id<CarrierService> key, CarrierService value) {
                if (key.toString().equals("b") && failOnce.getAndSet(false)) throw new IllegalStateException("deliberate transfer failure");
                return super.put(key, value);
            }
        };
        // Native Carrier is an interface: delegate all unchanged state, override only its mutable job registry.
        var target = (Carrier) java.lang.reflect.Proxy.newProxyInstance(Carrier.class.getClassLoader(), new Class<?>[]{Carrier.class},
                (proxy, method, args) -> method.getName().equals("getServices") ? rejecting : method.invoke(normal, args));
        var source = carrier("B", "b", 10, 10);
        Object missedBefore = target.getAttributes().getAttribute("missedParcelsAsList");
        var targetAttributes = new LinkedHashMap<>(target.getAttributes().getAsMap());
        var sourceAttributes = new LinkedHashMap<>(source.getAttributes().getAsMap());
        assertThatThrownBy(() -> new CarrierMergeService().merge(target, source))
                .isInstanceOf(IllegalStateException.class).hasMessage("deliberate transfer failure");
        assertThat(target.getServices()).isEqualTo(jobs);
        assertThat(source.getServices()).hasSize(1);
        assertThat(target.getAttributes().getAsMap()).isEqualTo(targetAttributes);
        assertThat(source.getAttributes().getAsMap()).isEqualTo(sourceAttributes);
        assertThat(target.getAttributes().getAttribute("missedParcelsAsList")).isSameAs(missedBefore);
    }

    @ParameterizedTest @ValueSource(strings={"initial-large", "initial-small", "dynamic-large", "dynamic-small"})
    void everyGeneratorMergeBranchRebuildsMetadata(String branch) throws Exception {
        var f = new DemandModalFixture(directory, "car");
        boolean large = branch.endsWith("large");
        var target = f.carrier("hermes_12345_target", "car", "car-home");
        var source = f.carrier("hermes_12345_source", "car", "car-home");
        add(target, "a", large ? 200 : 1, 1);
        add(source, "b", 1, 1);
        var all = new LinkedHashMap<Id<Carrier>, Carrier>(); all.put(target.getId(), target); all.put(source.getId(), source);
        var generator = new CarrierGenerator();
        DemandModalFixture.set(generator, "scenario", f.scenario); DemandModalFixture.set(generator, "hagridConfig", f.config);
        f.config.routing().setCarrierMergeThreshold(2);
        List<Id<Carrier>> initial = branch.startsWith("dynamic") ? List.of() : large ? List.of(source.getId()) : List.of(target.getId(), source.getId());
        generator.mergeCarriersWithInsufficientServices(all, initial, 2, new CarrierMergeLog());
        assertThat(all.values()).singleElement().satisfies(merged -> {
            assertThat(merged.getServices()).hasSize(2);
            assertThat(merged.getAttributes().getAttribute("numberOfParcels")).isEqualTo(large ? 201 : 2);
            assertThat(merged.getAttributes().getAttribute("missedParcels")).isEqualTo(2);
            assertThat((List<?>) merged.getAttributes().getAttribute("missedParcelsAsList")).hasSize(2);
        });
    }

    @Test void combinedServiceLimitPreventsSmallToLargeMerge() throws Exception {
        var f = new DemandModalFixture(directory, "car");
        var target = f.carrier("hermes_12345_target", "car", "car-home");
        var source = f.carrier("hermes_12345_source", "car", "car-home");
        add(target, "a", 100, 0); add(target, "b", 100, 0); add(source, "c", 1, 0);
        var all = new LinkedHashMap<Id<Carrier>, Carrier>(); all.put(target.getId(), target); all.put(source.getId(), source);
        var generator = new CarrierGenerator();
        DemandModalFixture.set(generator, "scenario", f.scenario); DemandModalFixture.set(generator, "hagridConfig", f.config);
        f.config.routing().setCarrierMergeThreshold(2);
        generator.mergeCarriersWithInsufficientServices(all, List.of(source.getId()), 2, new CarrierMergeLog());
        assertThat(all).hasSize(2); assertThat(target.getServices()).hasSize(2); assertThat(source.getServices()).hasSize(1);
    }

    @Test void nativeXmlRoundTripPreservesCountsAndMissedIdsWithDelimiters() throws Exception {
        var f = new DemandModalFixture(directory, "car");
        var target = f.carrier("hermes_12345_target", "car", "car-home");
        var source = f.carrier("hermes_12345_source", "car", "car-home");
        add(target, "a, custom", 200, 20); add(source, "b", 10, 10);
        new CarrierMergeService().merge(target, source);
        var carriers = new Carriers(); carriers.addCarrier(target);
        Path xml = directory.resolve("merged.xml"); new CarrierPlanWriter(carriers).write(xml.toString());
        var loaded = new Carriers(); new CarrierPlanXmlReader(loaded, f.types).readFile(xml.toString());
        var restored = loaded.getCarriers().get(target.getId());
        assertThat(restored.getAttributes().getAttribute("numberOfParcels")).isEqualTo(210);
        assertThat(restored.getAttributes().getAttribute("missedParcels")).isEqualTo(30);
        assertThat(CarrierMetadata.missedEntries(restored)).isEqualTo(CarrierMetadata.missedEntries(target));
    }

    private static Carrier carrier(String id, String service, int parcels, int missed) {
        var carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class)); add(carrier, service, parcels, missed); return carrier;
    }
    private static void add(Carrier carrier, String id, int parcels, int missed) {
        CarriersUtils.addService(carrier, DemandModalFixture.service(id, "car-stop", parcels, new org.matsim.api.core.v01.Coord(75, 0), 0, 86400));
        var entries = new ArrayList<Id<CarrierService>>();
        if (carrier.getAttributes().getAttribute("missedParcelsAsList") instanceof List<?> existing) {
            for (Object value : existing) entries.add((Id<CarrierService>) value);
        }
        entries.addAll(Collections.nCopies(missed, Id.create(id, CarrierService.class)));
        carrier.getAttributes().putAttribute("missedParcelsAsList", entries);
        carrier.getAttributes().putAttribute("missedParcels", entries.size());
        carrier.getAttributes().putAttribute("numberOfParcels", carrier.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum());
    }
}
