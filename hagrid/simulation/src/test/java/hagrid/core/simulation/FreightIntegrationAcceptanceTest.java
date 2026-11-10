package hagrid.core.simulation;

import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import hagrid.core.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.matsim.freight.carriers.events.*;
import org.matsim.freight.carriers.events.eventhandler.*;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.*;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.network.*;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.CarrierModule;
import org.matsim.vehicles.VehicleUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Small combined native controller acceptance; carrier owners are not a new two-echelon LSP model. */
class FreightIntegrationAcceptanceTest {
    @TempDir Path output;

    @Test void singleBinBackgroundDrivesBothOwnersAndModesWithServicesShipmentsAndNetworkChanges() throws Exception {
        var resolved = HagridConfigResolver.resolve(new HagridConfig(), HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of(
                        "optimization.replanningStartIteration", "2",
                        "optimization.replanningIterations", "1",
                        "optimization.carriersPerGroup", "2")), Map.of());
        var config = ConfigUtils.createConfig();
        config.network().setTimeVariantNetwork(true);
        config.controller().setLastIteration(4);
        config.controller().setOutputDirectory(output.toString());
        config.controller().setDumpDataAtEnd(false);
        config.controller().setCreateGraphsInterval(0);
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(1000);
        FreightScenarioConfiguration.includeCarrierModes(config, Set.of("car", "cargobike"));
        config.travelTimeCalculator().setSeparateModes(true);
        // Explicit static boundary profile: one measured bin over this bounded run.
        config.travelTimeCalculator().setTraveltimeBinSize(1000);
        config.travelTimeCalculator().setMaxTime(1000);
        var scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork();
        for (int i = 0; i < 4; i++)
            network.addNode(network.getFactory().createNode(Id.createNodeId("n"+i), new Coord(i*100, 0)));
        link(network, "origin", 0, 1, Set.of("car", "cargobike"));
        link(network, "car-road", 1, 2, Set.of("car"));
        link(network, "bike-road", 1, 2, Set.of("cargobike"));
        link(network, "destination", 2, 3, Set.of("car", "cargobike"));
        link(network, "return", 3, 0, Set.of("car", "cargobike"));
        var change = new NetworkChangeEvent(50);
        for (String id : List.of("car-road", "bike-road"))
            change.addLink(network.getLinks().get(Id.createLinkId(id)));
        change.setFreespeedChange(new NetworkChangeEvent.ChangeValue(
                NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 2));
        NetworkUtils.addNetworkChangeEvent(network, change);
        var types = new CarrierVehicleTypes();
        var carriers = new Carriers();
        scenario.addScenarioElement("carrierVehicleTypes", types);
        scenario.addScenarioElement("carriers", carriers);
        scenario.addScenarioElement("carNetwork", network);
        scenario.addScenarioElement("bikeNetwork", network);

        for (String owner : List.of("owner-a", "owner-b")) {
            for (String mode : List.of("car", "cargobike")) {
                String id = owner+"-"+mode;
                var type = VehicleUtils.createVehicleType(
                        Id.create(id, org.matsim.vehicles.VehicleType.class), mode);
                type.setMaximumVelocity(mode.equals("car") ? 20 : 20/3.6);
                type.getCapacity().setOther(10);
                type.getCostInformation().setFixedCost(1.0);
                type.getCostInformation().setCostsPerMeter(0.0);
                type.getCostInformation().setCostsPerSecond(1.0);
                types.getVehicleTypes().put(type.getId(), type);
                var carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
                carrier.getAttributes().putAttribute("acceptanceOwner", owner);
                CarriersUtils.setCarrierMode(carrier, mode);
                var vehicle = CarrierVehicle.Builder.newInstance(
                        Id.createVehicleId(id), Id.createLinkId("origin"), type)
                        .setEarliestStart(100).setLatestEnd(900).build();
                CarriersUtils.addCarrierVehicle(carrier, vehicle);
                if (mode.equals("car")) {
                    CarriersUtils.addService(carrier, CarrierService.Builder.newInstance(
                            Id.create(id+"-service", CarrierService.class), Id.createLinkId("destination"))
                            .setCapacityDemand(1).setServiceDuration(1)
                            .setServiceStartingTimeWindow(TimeWindow.newInstance(100,800)).build());
                } else {
                    CarriersUtils.addShipment(carrier, CarrierShipment.Builder.newInstance(
                            Id.create(id+"-shipment", CarrierShipment.class),
                            Id.createLinkId("origin"), Id.createLinkId("destination"), 1)
                            .setPickupDuration(1).setDeliveryDuration(1)
                            .setPickupTimeWindow(TimeWindow.newInstance(100,800))
                            .setDeliveryTimeWindow(TimeWindow.newInstance(100,800)).build());
                }
                var costs = NativeModalTransportCosts.create(network, types, mode,
                        (l,t,p,v) -> l.getLength()/l.getFreespeed(t));
                var result = new CarrierOptimizationService().optimize(carrier, costs, resolved, 42, 1,
                        HAGRIDRouterUtils.AlgorithmPhase.INITIAL, 25200, "initial");
                assertThat(result.diagnostics()).isEmpty();
                assertThat(result.iterations()).isEqualTo(1);
                carrier.addPlan(result.plan());
                carrier.setSelectedPlan(result.plan());
                carriers.addCarrier(carrier);
            }
        }
        var driven = new Driven();
        var controller = new Controler(scenario);
        controller.addOverridingModule(new CarrierModule());
        controller.addOverridingModule(new HAGRIDSimulationModule(scenario, false, 4, resolved));
        controller.addOverridingModule(new AbstractModule() {
            public void install() {
                addEventHandlerBinding().toInstance(driven);
                addControlerListenerBinding().toInstance((BeforeMobsimListener) e -> {
                    long complete = carriers.getCarriers().values().stream().filter(c ->
                            Boolean.TRUE.equals(c.getAttributes().getAttribute("hadFirstReplanning"))).count();
                    if (e.getIteration()==2) assertThat(complete).isEqualTo(2);
                    if (e.getIteration()>=3) assertThat(complete).isEqualTo(4);
                });
            }
        });
        controller.run();
        assertDrivenEvidence(driven, carriers);
        var executionLedger=controller.getInjector().getInstance(hagrid.core.simulation.scoring.CarrierExecutionLedger.class);
        for(var carrier:carriers.getCarriers().values()) {
            var execution=executionLedger.complete(carrier.getId());assertThat(execution.complete()).isTrue();
            var evaluated=hagrid.core.simulation.scoring.CarrierCostCalculator.calculate(carrier,execution,resolved,scenario.getNetwork());
            assertThat(carrier.getSelectedPlan().getScore()).isEqualTo(evaluated.score());
            assertThat(carrier.getAttributes().getAttribute("costFix")).isEqualTo(evaluated.costs().fixed());
            assertThat(carrier.getAttributes().getAttribute("costTime")).isEqualTo(evaluated.costs().travelTime());
            assertThat(carrier.getAttributes().getAttribute("costDistance")).isEqualTo(evaluated.costs().distance());
            assertThat(carrier.getAttributes().getAttribute("costOvertime")).isEqualTo(evaluated.costs().overtime());
            assertThat(carrier.getAttributes().getAttribute("costTimeWindowPenalty")).isEqualTo(evaluated.costs().timeWindowPenalty());
        }
        assertThat(carriers.getCarriers().values()).allSatisfy(carrier -> {
            boolean firstGroup="owner-a".equals(carrier.getAttributes().getAttribute("acceptanceOwner"));
            assertThat(carrier.getAttributes().getAttribute("routingMeasurementIteration")).isEqualTo(firstGroup?1:2);
            assertThat(carrier.getAttributes().getAttribute("routingPlanningIteration")).isEqualTo(firstGroup?2:3);
            assertThat(carrier.getSelectedPlan().getScore()).isNotNull().isFinite();
            assertThat(carrier.getAttributes().getAttribute("costExecutionComplete")).isEqualTo(true);
            assertThat(carrier.getAttributes().getAttribute("costDefinitionVersion")).isEqualTo("hagrid-carrier-costs-v2-execution");
            assertThat((Double)carrier.getAttributes().getAttribute("costTime")).isPositive();
            assertThat((Double)carrier.getAttributes().getAttribute("totalTravelTimeSeconds")).isPositive();
            assertThat(carrier.getAttributes().getAttribute("validatedRoutingCost")).isInstanceOf(Double.class);
        });
        assertThat(carriers.getCarriers().values().stream()
                .map(c -> c.getAttributes().getAttribute("routingContextFingerprint")).distinct()).hasSize(2);
        var rows = Files.readAllLines(output.resolve("carrier-routing-outcomes.csv"));
        assertThat(rows).hasSize(5);
        var header = List.of(rows.getFirst().split(",",-1));
        for (var carrier : carriers.getCarriers().values()) {
            var row = rows.stream().filter(line -> line.startsWith(carrier.getId()+","))
                    .findFirst().orElseThrow().split(",",-1);
            assertThat(List.of(row)).contains("VALIDATED_FINAL");
            assertThat(row[header.indexOf("validationStatus")]).isEqualTo("VALIDATED_FINAL");
            assertThat(Double.parseDouble(row[header.indexOf("finalCost")]))
                    .isEqualTo((Double) carrier.getAttributes().getAttribute("validatedRoutingCost"));
            assertThat(Double.parseDouble(row[header.indexOf("matSimScore")])).isEqualTo(carrier.getSelectedPlan().getScore());
        }
        assertThat(header).hasSize(rows.get(1).split(",",-1).length);
    }

    private static void link(Network network,String id,int from,int to,Set<String> modes) {
        var link = network.getFactory().createLink(Id.createLinkId(id),
                network.getNodes().get(Id.createNodeId("n"+from)),
                network.getNodes().get(Id.createNodeId("n"+to)));
        link.setLength(100); link.setFreespeed(10); link.setCapacity(36000);
        link.setNumberOfLanes(1); link.setAllowedModes(modes); network.addLink(link);
    }
    private static void assertDrivenEvidence(Driven driven, Carriers carriers) {
        assertThat(driven.stuck).isZero();
        for (int iteration : List.of(2,3,4)) {
            var expectedIds=carriers.getCarriers().keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
            var observedIds=new HashSet<String>();
            driven.completions.keySet().stream().filter(key->key.iteration()==iteration).forEach(key->observedIds.add(key.carrier()));
            assertThat(observedIds).as("iteration %s complete carrier set",iteration).isEqualTo(expectedIds);
            for (var carrier : carriers.getCarriers().values()) {
                String mode = CarriersUtils.getCarrierMode(carrier), vehicleId = simulationVehicleId(carrier);
                var vehicle = new VehicleIteration(iteration, vehicleId);
                assertThat(driven.vehicleModes.getOrDefault(vehicle, Set.of()))
                        .as("iteration %s carrier %s mode", iteration, carrier.getId()).containsExactly(mode);
                String road = mode.equals("car") ? "car-road" : "bike-road";
                String forbiddenRoad = mode.equals("car") ? "bike-road" : "car-road";
                assertThat(driven.vehicleRoadTimes.getOrDefault(new Traversal(vehicle,road), List.of()))
                        .as("iteration %s carrier %s modal traversal", iteration, carrier.getId())
                        .isNotEmpty().allSatisfy(seconds -> assertThat(seconds).isGreaterThanOrEqualTo(49));
                assertThat(driven.vehicleRoadTimes.getOrDefault(new Traversal(vehicle,forbiddenRoad), List.of())).isEmpty();
                var ends = driven.completions.getOrDefault(
                        new CarrierIteration(iteration,carrier.getId().toString()), List.of());
                assertThat(ends).as("iteration %s carrier %s completed jobs", iteration, carrier.getId())
                        .hasSize(carrier.getServices().size()+2*carrier.getShipments().size())
                        .allSatisfy(e -> assertThat(e.getVehicleId().toString()).isEqualTo(vehicleId));
                for (var service : carrier.getServices().values()) {
                    var serviceEnds = ends.stream().filter(e -> e instanceof CarrierServiceEndEvent se
                            && se.getServiceId().equals(service.getId())).toList();
                    assertThat(serviceEnds).hasSize(1);
                    assertThat(serviceEnds.getFirst().getLinkId()).isEqualTo(service.getServiceLinkId());
                }
                for (var shipment : carrier.getShipments().values()) {
                    var pickups = ends.stream().filter(e -> e instanceof CarrierShipmentPickupEndEvent pe
                            && pe.getShipmentId().equals(shipment.getId())).toList();
                    var deliveries = ends.stream().filter(e -> e instanceof CarrierShipmentDeliveryEndEvent de
                            && de.getShipmentId().equals(shipment.getId())).toList();
                    assertThat(pickups).hasSize(1); assertThat(deliveries).hasSize(1);
                    assertThat(pickups.getFirst().getLinkId()).isEqualTo(shipment.getPickupLinkId());
                    assertThat(deliveries.getFirst().getLinkId()).isEqualTo(shipment.getDeliveryLinkId());
                    assertThat(pickups.getFirst().getTime()).isLessThan(deliveries.getFirst().getTime());
                }
            }
        }
    }

    enum MissingEvidence { CARRIER, ITERATION, SERVICE, DELIVERY, PICKUP_ORDER, WRONG_MODE }

    @ParameterizedTest @EnumSource(MissingEvidence.class)
    void aggregateEvidenceCannotHideMissingExecution(MissingEvidence fault) {
        var expected = new Carriers();
        for (String owner : List.of("owner-a", "owner-b")) {
            for (String mode : List.of("car", "cargobike")) {
                String id = owner+"-"+mode;
                var carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
                CarriersUtils.setCarrierMode(carrier, mode);
                if (mode.equals("car")) CarriersUtils.addService(carrier, CarrierService.Builder.newInstance(
                        Id.create(id+"-service", CarrierService.class), Id.createLinkId("destination")).build());
                else CarriersUtils.addShipment(carrier, CarrierShipment.Builder.newInstance(
                        Id.create(id+"-shipment", CarrierShipment.class), Id.createLinkId("origin"),
                        Id.createLinkId("destination"), 1).build());
                expected.addCarrier(carrier);
            }
        }
        var driven = new Driven();
        for (int iteration : List.of(2,3,4)) {
            driven.reset(iteration);
            for (var carrier : expected.getCarriers().values()) {
                String id = carrier.getId().toString(), mode = CarriersUtils.getCarrierMode(carrier);
                boolean carTarget = id.equals("owner-b-car");
                boolean bikeTarget = id.equals("owner-b-cargobike");
                if (carTarget && (fault==MissingEvidence.CARRIER ||
                        (iteration==4 && fault==MissingEvidence.ITERATION))) continue;
                var vehicle = Id.createVehicleId(simulationVehicleId(carrier));
                String road = mode.equals("car") ? "car-road" : "bike-road";
                driven.handleEvent(new VehicleEntersTrafficEvent(100, Id.createPersonId(vehicle.toString()),
                        Id.createLinkId("origin"), vehicle,
                        carTarget && iteration==4 && fault==MissingEvidence.WRONG_MODE ? "cargobike" : mode, 0));
                driven.handleEvent(new LinkEnterEvent(110, vehicle, Id.createLinkId(road)));
                driven.handleEvent(new LinkLeaveEvent(160, vehicle, Id.createLinkId(road)));
                for (var service : carrier.getServices().values())
                    if (!(carTarget && iteration==4 && fault==MissingEvidence.SERVICE))
                        driven.handleEvent(new CarrierServiceEndEvent(170, carrier.getId(), service, vehicle));
                for (var shipment : carrier.getShipments().values()) {
                    driven.handleEvent(new CarrierShipmentPickupEndEvent(
                            bikeTarget && iteration==4 && fault==MissingEvidence.PICKUP_ORDER ? 180 : 105,
                            carrier.getId(), shipment, vehicle));
                    if (!(bikeTarget && iteration==4 && fault==MissingEvidence.DELIVERY))
                        driven.handleEvent(new CarrierShipmentDeliveryEndEvent(170, carrier.getId(), shipment, vehicle));
                }
            }
        }
        assertThatThrownBy(() -> assertDrivenEvidence(driven, expected)).isInstanceOf(AssertionError.class);
    }

    private static String simulationVehicleId(Carrier carrier) {
        // This fixture has exactly one vehicle and one tour. Native CarrierAgent resets nextId=1 each iteration.
        return "freight_"+carrier.getId()+"_veh_"+carrier.getId()+"_1";
    }
    private record VehicleIteration(int iteration, String vehicle) {}
    private record Traversal(VehicleIteration vehicle, String link) {}
    private record CarrierIteration(int iteration, String carrier) {}

    static final class Driven implements LinkEnterEventHandler,LinkLeaveEventHandler,
            VehicleEntersTrafficEventHandler,PersonStuckEventHandler,CarrierServiceEndEventHandler,
            CarrierShipmentPickupEndEventHandler,CarrierShipmentDeliveryEndEventHandler {
        int iteration,stuck;
        final Map<VehicleIteration,Set<String>> vehicleModes = new HashMap<>();
        final Map<Traversal,Double> entries = new HashMap<>();
        final Map<Traversal,List<Double>> vehicleRoadTimes = new HashMap<>();
        final Map<CarrierIteration,List<AbstractCarrierEvent>> completions = new HashMap<>();
        public void reset(int iteration) { this.iteration=iteration; entries.clear(); }
        private VehicleIteration vehicle(String id) { return new VehicleIteration(iteration,id); }
        public void handleEvent(VehicleEntersTrafficEvent e) {
            vehicleModes.computeIfAbsent(vehicle(e.getVehicleId().toString()), k -> new HashSet<>()).add(e.getNetworkMode());
        }
        public void handleEvent(LinkEnterEvent e) {
            entries.put(new Traversal(vehicle(e.getVehicleId().toString()),e.getLinkId().toString()),e.getTime());
        }
        public void handleEvent(LinkLeaveEvent e) {
            var key = new Traversal(vehicle(e.getVehicleId().toString()),e.getLinkId().toString());
            var enter = entries.remove(key);
            if (enter!=null) {
                vehicleRoadTimes.computeIfAbsent(key,k -> new ArrayList<>()).add(e.getTime()-enter);
            }
        }
        private void complete(AbstractCarrierEvent e) {
            completions.computeIfAbsent(new CarrierIteration(iteration,e.getCarrierId().toString()),
                    k -> new ArrayList<>()).add(e);
        }
        public void handleEvent(CarrierServiceEndEvent e) { complete(e); }
        public void handleEvent(CarrierShipmentPickupEndEvent e) { complete(e); }
        public void handleEvent(CarrierShipmentDeliveryEndEvent e) { complete(e); }
        public void handleEvent(PersonStuckEvent e) { stuck++; }
    }
}
