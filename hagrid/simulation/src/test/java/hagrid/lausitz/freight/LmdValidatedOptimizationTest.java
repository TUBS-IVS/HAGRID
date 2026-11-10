package hagrid.lausitz.freight;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LmdValidatedOptimizationTest {
    @Test void aLaterImpossibleCarrierCannotPublishAnEarlierPlan() {
        var f = fixture();
        var good = carrier("a-good", f, 1, 5000);
        var bad = carrier("z-bad", f, 11, 5000);
        var carriers = new Carriers(List.of(good, bad));
        assertThatThrownBy(() -> LausitzFreightPreprocessor.routeWithDurationCap(carriers, f.network, f.types, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(good.getSelectedPlan()).isNull();
        assertThat(bad.getSelectedPlan()).isNull();
    }
    @Test void lateDepartureMutationMustBeCheckedBeforePublishingOrAddingVehicles() {
        var f = fixture();
        var carrier = carrier("wave", f, 1, 1000);
        var carriers = new Carriers(List.of(carrier));
        var fleet = Set.copyOf(carrier.getCarrierCapabilities().getCarrierVehicles().keySet());
        assertThatThrownBy(() -> LausitzFreightPreprocessor.routeWithDurationCap(carriers, f.network, f.types, 1, 25200,
                c -> new Random(1) { @Override public double nextGaussian() { return 1000; } }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(carrier.getSelectedPlan()).isNull();
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles().keySet()).containsExactlyInAnyOrderElementsOf(fleet);
    }
    @Test void aValidInitialPlanPreservesItsBudgetAndSharedTypeMetadata() {
        var f = fixture();
        var carrier = carrier("good", f, 1, 5000);
        CarriersUtils.setSkills(f.type, Set.of("skill"));
        var originalAttributes = f.type.getAttributes().getAsMap().toString();
        LausitzFreightPreprocessor.routeWithDurationCap(new Carriers(List.of(carrier)), f.network, f.types, 2);
        assertThat(carrier.getSelectedPlan().getScheduledTours()).hasSize(1);
        assertThat(CarriersUtils.getJspritIterations(carrier)).isEqualTo(2);
        assertThat(f.type.getAttributes().getAsMap().toString()).isEqualTo(originalAttributes);
    }
    @Test void anImpossibleGroupLeavesCanonicalMetadataUntouched() {
        var f = fixture();
        var good = carrier("a-good", f, 1, 5000);
        var bad = carrier("z-bad", f, 11, 5000);
        bad.getAttributes().putAttribute("unassignedJobs", 99);
        var goodAttributes = Map.copyOf(good.getAttributes().getAsMap());
        var badAttributes = Map.copyOf(bad.getAttributes().getAsMap());
        assertThatThrownBy(() -> LausitzFreightPreprocessor.routeWithDurationCap(
                new Carriers(List.of(good, bad)), f.network, f.types, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(good.getAttributes().getAsMap()).isEqualTo(goodAttributes);
        assertThat(bad.getAttributes().getAsMap()).isEqualTo(badAttributes);
        assertThat(good.getPlans()).isEmpty();
        assertThat(bad.getPlans()).isEmpty();
    }
    @Test void oversizeAdmissionBuildsNoWorkerAndPublishesNoPlan() {
        var f = fixture();
        var carrier = carrier("oversize", f, 1, 5000);
        try (var batches = new hagrid.core.routing.CarrierBatchExecutor(2, 100, java.time.Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> LausitzFreightPreprocessor.routeWithDurationCap(
                    new Carriers(List.of(carrier)), f.network, f.types, 1, 25200, null, batches, c -> 101))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(batches.createdWorkers()).isZero();
            assertThat(carrier.getSelectedPlan()).isNull();
            assertThat(carrier.getAttributes().getAttribute("jspritIterations")).isNull();
        }
    }
    @Test void nativeParallelGroupMatchesSerialFinalCostsAndLeavesNoReservations() {
        var f = fixture();
        var serialA = carrier("a", f, 1, 5000);
        var serialB = carrier("b", f, 1, 5000);
        LausitzFreightPreprocessor.routeWithDurationCap(new Carriers(List.of(serialA, serialB)), f.network, f.types, 1);
        var parallelA = carrier("a", f, 1, 5000);
        var parallelB = carrier("b", f, 1, 5000);
        try (var batches = new hagrid.core.routing.CarrierBatchExecutor(2, 100, java.time.Duration.ofSeconds(2))) {
            LausitzFreightPreprocessor.routeWithDurationCap(new Carriers(List.of(parallelA, parallelB)),
                    f.network, f.types, 1, 25200, null, batches, c -> 50);
            assertThat(batches.createdWorkers()).isEqualTo(2);
            assertThat(batches.reservedBytes()).isZero();
            var costs = hagrid.core.routing.NativeModalTransportCosts.create(f.network, f.types, "car");
            for (var pair : List.of(new Carrier[]{serialA, parallelA}, new Carrier[]{serialB, parallelB})) {
                var before = hagrid.core.routing.PlanValidator.validate(pair[0], pair[0].getSelectedPlan(), costs, null, 25200);
                var after = hagrid.core.routing.PlanValidator.validate(pair[1], pair[1].getSelectedPlan(), costs, null, 25200);
                assertThat(after.valid()).isTrue();
                assertThat(after.finalCost()).isEqualTo(before.finalCost());
                assertThat(pair[1].getSelectedPlan().getScheduledTours()).hasSize(1);
            }
        }
    }
    @Test void configuredInitialResourceCapLimitsTheProvidedPoolWithoutChangingTheOneIterationBudget() {
        var f=fixture();var a=carrier("a",f,1,5000);var b=carrier("b",f,1,5000);
        try(var batches=new hagrid.core.routing.CarrierBatchExecutor(2,100,java.time.Duration.ofSeconds(2))) {
            var resources=new hagrid.core.config.ResolvedHagridConfig.Resources(2,OptionalInt.of(1));
            LausitzFreightPreprocessor.routeWithDurationCap(new Carriers(List.of(a,b)),f.network,f.types,1,25200,
                    null,batches,c->50,resources);
            assertThat(batches.createdWorkers()).isEqualTo(1);assertThat(batches.reservedBytes()).isZero();
            assertThat(CarriersUtils.getJspritIterations(a)).isEqualTo(1);
            assertThat(CarriersUtils.getJspritIterations(b)).isEqualTo(1);
            assertThat(a.getSelectedPlan()).isNotNull();assertThat(b.getSelectedPlan()).isNotNull();
        }
    }
    private record Fixture(Network network, CarrierVehicleTypes types, VehicleType type) {}
    private static Fixture fixture() {
        var n = NetworkUtils.createNetwork();
        var a = NetworkUtils.createAndAddNode(n, Id.createNodeId("a"), new Coord(0,0));
        var b = NetworkUtils.createAndAddNode(n, Id.createNodeId("b"), new Coord(100,0));
        var c = NetworkUtils.createAndAddNode(n, Id.createNodeId("c"), new Coord(200,0));
        for(var edge : new org.matsim.api.core.v01.network.Node[][]{{a,b},{b,c},{c,a}}) {
            var l = NetworkUtils.createAndAddLink(n, Id.createLinkId(edge[0].getId()+"-"+edge[1].getId()),
                    edge[0],edge[1],100,10,1000,1);
            l.setAllowedModes(Set.of("car"));
        }
        var type = VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");
        type.setMaximumVelocity(10); type.getCapacity().setOther(10);
        type.getCostInformation().setFixedCost(10.0).setCostsPerMeter(0.1).setCostsPerSecond(1.0);
        var types = new CarrierVehicleTypes(); types.getVehicleTypes().put(type.getId(),type);
        return new Fixture(n,types,type);
    }
    private static Carrier carrier(String id,Fixture f,int demand,double latestJobStart) {
        var c = CarriersUtils.createCarrier(Id.create(id,Carrier.class));
        CarriersUtils.setCarrierMode(c,"car");
        var v = CarrierVehicle.Builder.newInstance(Id.createVehicleId(id+"_h0_v0"),Id.createLinkId("a-b"),f.type)
                .setEarliestStart(0).setLatestEnd(5000).build();
        c.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance().addVehicle(v).setFleetSize(CarrierCapabilities.FleetSize.INFINITE).build());
        CarriersUtils.addService(c,CarrierService.Builder.newInstance(Id.create(id+"-job",CarrierService.class),Id.createLinkId("b-c"))
                .setCapacityDemand(demand).setServiceDuration(10).setServiceStartingTimeWindow(TimeWindow.newInstance(0,latestJobStart)).build());
        return c;
    }
}
