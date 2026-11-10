package hagrid.core.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkChangeEventsWriter;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class ScenarioCarrierLoadingTest {
    @TempDir Path directory;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void builderCompactsNativeEventsAfterAnyRequiredModalMerge(boolean cargo) throws Exception {
        var input = inputs(cargo, cargo, false);
        var n = network(Set.of("car"));
        var events = new java.util.ArrayList<NetworkChangeEvent>();
        for (String id : List.of("depot", "stop")) {
            var event = new NetworkChangeEvent(100);
            event.addLink(n.getLinks().get(Id.createLinkId(id)));
            event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 2));
            events.add(event);
        }
        new NetworkChangeEventsWriter().write(input.getNetworkChangeEventPath().toString(), events);
        var scenario = HAGRIDScenarioBuilder.build(input, List.of());
        assertThat(NetworkUtils.getNetworkChangeEvents(scenario.getNetwork())).hasSize(1);
        for (String id : List.of("depot", "stop"))
            assertThat(scenario.getNetwork().getLinks().get(Id.createLinkId(id)).getFreespeed(100)).isEqualTo(2);
        assertThat(scenario.getConfig().network().getChangeEventsInputFile()).isEqualTo(input.getNetworkChangeEventPath().toAbsolutePath().toString());
    }

    @Test
    void commonInputNeedsNoBikeFileAndPreservesTypesPlansAndMeasurements() throws Exception {
        var input = inputs(true, false, false);
        assertThat(input.getBikeNetworkPath()).doesNotExist();
        var scenario = HAGRIDScenarioBuilder.build(input, List.of());
        var carriers = CarriersUtils.getCarriers(scenario);
        assertThat(carriers.getCarriers()).hasSize(2);
        var bike = carriers.getCarriers().get(Id.create("delivery", Carrier.class));
        var type = CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().get(Id.create("cargo-type", VehicleType.class));
        assertThat(CarriersUtils.getCarrierMode(bike)).isEqualTo("cargobike");
        assertThat(CarriersUtils.getJspritIterations(bike)).isEqualTo(7);
        assertThat(CarriersUtils.getJspritComputationTime(bike)).isEqualTo(12.5);
        assertThat(bike.getAttributes().getAttribute("history")).isEqualTo("measured");
        assertThat(bike.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getType()).isSameAs(type);
        assertThat(bike.getSelectedPlan().getScore()).isEqualTo(-123);
        assertThat(bike.getSelectedPlan().getScheduledTours()).hasSize(1);
        assertThat(bike.getServices()).hasSize(1);
        assertThat(scenario.getScenarioElement("carNetwork")).isSameAs(scenario.getNetwork());
        assertThat(scenario.getScenarioElement("bikeNetwork")).isSameAs(scenario.getNetwork());
        assertThat(input.getMergedCarrierPath()).exists();
    }


    @Test
    void legacyParcelTypeSpellingIsNormalizedAfterReadingAndMerging() throws Exception {
        var input = inputs(false, false, false);
        var types = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(types).readFile(input.getVehicleTypePath().toString());
        var delivery = new Carriers();
        new CarrierPlanXmlReader(delivery, types).readFile(input.getDeliveryCarrierPath().toString());
        delivery.getCarriers().values().forEach(carrier ->
                carrier.getServices().values().forEach(service -> service.getAttributes().putAttribute("type", "Mixed")));
        new CarrierPlanWriter(delivery).write(input.getDeliveryCarrierPath().toString());
        var scenario = HAGRIDScenarioBuilder.build(input, List.of());
        var merged = CarriersUtils.getCarriers(scenario).getCarriers().get(Id.create("delivery", Carrier.class));
        assertThat(merged.getServices().values()).isNotEmpty().allSatisfy(service ->
                assertThat(service.getAttributes().getAttribute("type")).isEqualTo("MIXED"));
        assertThat(Files.readString(input.getMergedCarrierPath())).contains("MIXED");
    }

    @Test
    void splitBikeInputBecomesSimulationNetworkAndKeepsRealEventFile() throws Exception {
        var input = inputs(true, true, false);
        var scenario = HAGRIDScenarioBuilder.build(input, List.of());
        var network = scenario.getNetwork();
        assertThat(network.getLinks()).containsKey(Id.createLinkId("bike-only"));
        assertThat(network.getLinks().get(Id.createLinkId("depot")).getAllowedModes())
                .containsExactlyInAnyOrder("car", "cargobike");
        assertThat(NetworkUtils.getNetworkChangeEvents(network)).hasSize(1);
        assertThat(network.getLinks().get(Id.createLinkId("stop")).getFreespeed(100)).isEqualTo(2);
        assertThat(network.getLinks().get(Id.createLinkId("bike-only")).getFreespeed(100)).isEqualTo(10);
        assertThat(NetworkUtils.getNetworkChangeEvents(network).peek().getLinks().iterator().next())
                .isSameAs(network.getLinks().get(Id.createLinkId("stop")));
        assertThat(scenario.getScenarioElement("bikeNetwork")).isSameAs(network);
        assertThat(CarriersUtils.getCarrierMode(CarriersUtils.getCarriers(scenario)
                .getCarriers().get(Id.create("delivery", Carrier.class)))).isEqualTo("cargobike");
    }

    @Test
    void invalidLocationDoesNotPublishAMergedCarrierArtifact() throws Exception {
        var input = inputs(false, false, true);
        assertThatIllegalArgumentException().isThrownBy(() -> HAGRIDScenarioBuilder.build(input, List.of()))
                .withMessageContaining("service").withMessageContaining("missing");
        assertThat(input.getMergedCarrierPath()).doesNotExist();
    }

    @Test
    void duplicateCarrierAcrossDeliveryAndSupplyFilesFailsWithoutAnArtifact() throws Exception {
        var input = inputs(false, false, false);
        Files.copy(input.getDeliveryCarrierPath(), input.getSupplyCarrierPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThatIllegalArgumentException().isThrownBy(() -> HAGRIDScenarioBuilder.build(input, List.of()))
                .withMessageContaining("Duplicate carrier ID").withMessageContaining("delivery");
        assertThat(input.getMergedCarrierPath()).doesNotExist();
    }

    @Test
    void exactBikeModeIsConfiguredForNetworkRoutingSimulationAndMeasuredTimes() throws Exception {
        var input = inputs(true, false, false);
        Files.writeString(input.getVehicleTypePath(), Files.readString(input.getVehicleTypePath()).replace("cargobike", "bike"));
        Files.writeString(input.getCarNetworkPath(), Files.readString(input.getCarNetworkPath()).replace("cargobike", "bike"));
        var scenario = HAGRIDScenarioBuilder.build(input, List.of());
        assertThat(CarriersUtils.getCarrierMode(CarriersUtils.getCarriers(scenario)
                .getCarriers().get(Id.create("delivery", Carrier.class)))).isEqualTo("bike");
        assertThat(scenario.getConfig().qsim().getMainModes()).contains("bike");
        assertThat(scenario.getConfig().routing().getNetworkModes()).contains("bike");
        assertThat(scenario.getConfig().travelTimeCalculator().getAnalyzedModes()).contains("bike");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void launchPreflightDoesNotRequireAnUnusedBikeNetwork(boolean cargo) throws Exception {
        var input = inputs(cargo, false, false);
        Files.writeString(input.getFreightZonePath(), "existence-only preflight fixture");
        assertThat(input.getBikeNetworkPath()).doesNotExist();
        assertThatCode(input::validateInputFiles).doesNotThrowAnyException();
    }

    @Test
    void launchPreflightStillRejectsMissingRequiredCarNetwork() throws Exception {
        var input = inputs(false, false, false);
        Files.writeString(input.getFreightZonePath(), "existence-only preflight fixture");
        Files.delete(input.getCarNetworkPath());
        assertThatIllegalStateException().isThrownBy(input::validateInputFiles)
                .withMessageContaining("Car network").withMessageContaining("network.xml");
    }

    @Test
    void registeredRuntimeBikeUsesConfigured18KmhCapAndLeavesCarSpeedUntouched() throws Exception {
        var input = inputs(true, false, false);
        input.captureExplicitScenarioInputs(java.util.Map.of("cargoBikeMaxSpeedKmh", "18"));
        var scenario = HAGRIDScenarioBuilder.build(input, List.of());
        var types = CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes();
        var bike = types.get(Id.create("cargo-type", VehicleType.class));
        var car = types.get(Id.create("car-type", VehicleType.class));
        assertThat(bike.getMaximumVelocity()).isEqualTo(5);
        assertThat(car.getMaximumVelocity()).isEqualTo(10);
        assertThat(CarriersUtils.getCarriers(scenario).getCarriers().get(Id.create("delivery", Carrier.class))
                .getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getType()).isSameAs(bike);
    }

    @Test
    void registeredRuntimeBikeUsesTheDefault20KmhCap() throws Exception {
        var input = inputs(true, false, false);
        var scenario = HAGRIDScenarioBuilder.build(input, List.of());
        assertThat(CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes()
                .get(Id.create("cargo-type", VehicleType.class)).getMaximumVelocity()).isEqualTo(20.0 / 3.6);
    }

    @Test
    void registeredRuntimeBikePreservesASlowerXmlMaximum() throws Exception {
        var input = inputs(true, false, false);
        input.captureExplicitScenarioInputs(java.util.Map.of("cargoBikeMaxSpeedKmh", "18"));
        var types = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(types).readFile(input.getVehicleTypePath().toString());
        types.getVehicleTypes().get(Id.create("cargo-type", VehicleType.class)).setMaximumVelocity(3);
        new CarrierVehicleTypeWriter(types).write(input.getVehicleTypePath().toString());
        var scenario = HAGRIDScenarioBuilder.build(input, List.of());
        assertThat(CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes()
                .get(Id.create("cargo-type", VehicleType.class)).getMaximumVelocity()).isEqualTo(3);
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"modern", "legacy"})
    void duplicateVehicleTypesFailBeforeCarrierFilesOrScenarioPublication(String format) throws Exception {
        var input = inputs(false, false, false);
        if (format.equals("modern")) {
            Files.writeString(input.getVehicleTypePath(), Files.readString(input.getVehicleTypePath()).replace("id=\"cargo-type\"", "id=\"car-type\""));
        } else {
            Files.writeString(input.getVehicleTypePath(), legacyTypes(true));
        }
        assertThatThrownBy(() -> HAGRIDScenarioBuilder.build(input, List.of()))
                .hasMessageContaining("car-type").hasMessageContaining("type");
        assertThat(input.getMergedCarrierPath()).doesNotExist();
    }

    @Test
    void validLegacyCarTypesKeepNativeCompatibilityAndMetadata() throws Exception {
        var input = inputs(false, false, false);
        Files.writeString(input.getVehicleTypePath(), legacyTypes(false));
        var scenario = HAGRIDScenarioBuilder.build(input, List.of());
        var type = CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().get(Id.create("car-type", VehicleType.class));
        assertThat(type.getNetworkMode()).isEqualTo("car");
        assertThat(type.getCapacity().getOther()).isEqualTo(10);
        assertThat(type.getMaximumVelocity()).isEqualTo(20);
        assertThat(type.getCostInformation().getCostsPerMeter()).isEqualTo(0.01);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shipmentSupplyPlanSurvivesRealXmlBuilderAndMergedOutput(boolean cargo) throws Exception {
        var input = inputs(cargo, false, false);
        var types = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(types).readFile(input.getVehicleTypePath().toString());
        var type = types.getVehicleTypes().get(Id.create(cargo ? "cargo-type" : "car-type", VehicleType.class));
        var supply = CarriersUtils.createCarrier(Id.create("shipment-supply", Carrier.class));
        var vehicle = CarrierVehicle.Builder.newInstance(Id.createVehicleId("shipment-vehicle"),
                Id.createLinkId("depot"), type).setEarliestStart(0).setLatestEnd(50000).build();
        CarriersUtils.addCarrierVehicle(supply, vehicle);
        var shipment = CarrierShipment.Builder.newInstance(Id.create("supply-job", CarrierShipment.class),
                Id.createLinkId("depot"), Id.createLinkId("stop"), 2)
                .setPickupDuration(11).setDeliveryDuration(17)
                .setPickupTimeWindow(TimeWindow.newInstance(3000,4000))
                .setDeliveryTimeWindow(TimeWindow.newInstance(3000,5000)).build();
        CarriersUtils.addShipment(supply, shipment);
        var tour = Tour.Builder.newInstance(Id.create("supply-tour", Tour.class));
        tour.scheduleStart(vehicle.getLinkId());
        tour.addLeg(new Tour.Leg()); tour.schedulePickup(shipment);
        tour.addLeg(new Tour.Leg()); tour.scheduleDelivery(shipment);
        tour.addLeg(new Tour.Leg()); tour.scheduleEnd(vehicle.getLinkId());
        var plan = new CarrierPlan(List.of(ScheduledTour.newInstance(tour.build(), vehicle, 3600)));
        plan.setScore(-234.0); supply.addPlan(plan); supply.setSelectedPlan(plan);
        CarriersUtils.setJspritIterations(supply,7);
        CarriersUtils.setJspritComputationTime(supply,12.5);
        new CarrierPlanWriter(new Carriers(List.of(supply))).write(input.getSupplyCarrierPath().toString());

        var scenario = HAGRIDScenarioBuilder.build(input, List.of());
        var loaded = CarriersUtils.getCarriers(scenario).getCarriers().get(supply.getId());
        assertThat(loaded.getServices()).isEmpty();
        assertThat(CarriersUtils.getCarrierMode(loaded)).isEqualTo(cargo ? "cargobike" : "car");
        assertThat(CarriersUtils.getJspritIterations(loaded)).isEqualTo(7);
        assertThat(CarriersUtils.getJspritComputationTime(loaded)).isEqualTo(12.5);
        assertThat(loaded.getSelectedPlan().getScore()).isEqualTo(-234);
        assertThat(loaded.getSelectedPlan().getScheduledTours()).hasSize(1);
        var loadedTour = loaded.getSelectedPlan().getScheduledTours().iterator().next();
        assertThat(loadedTour.getDeparture()).isEqualTo(3600);
        assertThat(loadedTour.getVehicle().getType()).isSameAs(
                CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().get(type.getId()));
        assertShipmentPlan(loaded);

        var merged = new Carriers();
        new CarrierPlanXmlReader(merged,CarriersUtils.getCarrierVehicleTypes(scenario))
                .readFile(input.getMergedCarrierPath().toString());
        assertShipmentPlan(merged.getCarriers().get(supply.getId()));
    }

    private static void assertShipmentPlan(Carrier carrier) {
        assertThat(carrier.getShipments()).hasSize(1);
        var job = carrier.getShipments().get(Id.create("supply-job",CarrierShipment.class));
        assertThat(job.getCapacityDemand()).isEqualTo(2);
        assertThat(job.getPickupDuration()).isEqualTo(11);
        assertThat(job.getDeliveryDuration()).isEqualTo(17);
        assertThat(job.getPickupTimeWindow().getStart()).isEqualTo(3000);
        assertThat(job.getPickupTimeWindow().getEnd()).isEqualTo(4000);
        assertThat(job.getDeliveryTimeWindow().getStart()).isEqualTo(3000);
        assertThat(job.getDeliveryTimeWindow().getEnd()).isEqualTo(5000);
        var activities = carrier.getSelectedPlan().getScheduledTours().iterator().next().getTour()
                .getTourElements().stream().filter(Tour.ShipmentBasedActivity.class::isInstance)
                .map(Tour.ShipmentBasedActivity.class::cast).toList();
        assertThat(activities).hasSize(2);
        assertThat(activities.get(0)).isInstanceOf(Tour.Pickup.class);
        assertThat(activities.get(1)).isInstanceOf(Tour.Delivery.class);
        assertThat(activities).allSatisfy(activity -> assertThat(activity.getShipment()).isSameAs(job));
        assertThat(job.getPickupLinkId()).isEqualTo(Id.createLinkId("depot"));
        assertThat(job.getDeliveryLinkId()).isEqualTo(Id.createLinkId("stop"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void sectionBuilderRejectsFutureEventsBeforeResolvingRawOrSplitLinks(boolean split,boolean compressed) throws Exception {
        var input = sectionInputs(split);input.compressedChanges=compressed;
        var raw=network(Set.of("car"));
        var event=new NetworkChangeEvent(10000);event.addLink(raw.getLinks().get(Id.createLinkId("stop")));
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.FACTOR,.5));
        new NetworkChangeEventsWriter().write(input.getNetworkChangeEventPath().toString(),List.of(event));
        assertThatThrownBy(()->HAGRIDScenarioBuilder.build(input,List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Section").hasMessageContaining("network change events");
        assertThat(input.getMergedCarrierPath()).doesNotExist();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void sectionBuilderAcceptsAnEmptyEventsFile(boolean split,boolean compressed) throws Exception {
        var input=sectionInputs(split);input.compressedChanges=compressed;
        new NetworkChangeEventsWriter().write(input.getNetworkChangeEventPath().toString(),List.of());
        var scenario=HAGRIDScenarioBuilder.build(input,List.of());
        assertThat(CarriersUtils.getCarriers(scenario).getCarriers()).containsKey(Id.create("delivery",Carrier.class));
        assertThat(NetworkUtils.getNetworkChangeEvents(scenario.getNetwork())).isEmpty();
    }

    @Test void sectionBuilderKeepsFractionalDepartureWhenWritingMergedCarriers() throws Exception {
        var input=sectionInputs(false,true);
        new NetworkChangeEventsWriter().write(input.getNetworkChangeEventPath().toString(),List.of());
        HAGRIDScenarioBuilder.build(input,List.of());
        var types=new CarrierVehicleTypes();new CarrierVehicleTypeReader(types).readFile(input.getVehicleTypePath().toString());
        var merged=new Carriers();new CarrierPlanXmlReader(merged,types).readFile(input.getMergedCarrierPath().toString());
        assertThat(merged.getCarriers().get(Id.create("delivery",Carrier.class)).getSelectedPlan().getScheduledTours().iterator().next().getDeparture()).isEqualTo(3600.5);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"capacity", "speed", "mode", "skills", "fixed", "distance", "driving", "waiting", "service", "fleet", "pcu", "length", "flow"})
    void sectionCompanionRejectsChangedUsedVehicleProfile(String field) throws Exception {
        var input=sectionInputs(false);
        var types=new CarrierVehicleTypes();new CarrierVehicleTypeReader(types).readFile(input.getVehicleTypePath().toString());
        var carriers=new Carriers();new CarrierPlanXmlReader(carriers,types).readFile(input.getDeliveryCarrierPath().toString());
        var companion=Path.of(input.getDeliveryCarrierPath()+".section-bindings.json");
        hagrid.core.routing.SectionBindingCompanion.verify(companion,carriers);
        var carrier=carriers.getCarriers().get(Id.create("delivery",Carrier.class));
        var type=carrier.getSelectedPlan().getScheduledTours().iterator().next().getVehicle().getType();
        var cost=type.getCostInformation();
        switch(field) {
            case "capacity" -> type.getCapacity().setOther(type.getCapacity().getOther()+1);
            case "speed" -> type.setMaximumVelocity(type.getMaximumVelocity()+1);
            case "mode" -> type.setNetworkMode("bike");
            case "skills" -> CarriersUtils.setSkills(type,Set.of("cold"));
            case "fixed" -> cost.setFixedCost(cost.getFixedCosts()+1);
            case "distance" -> cost.setCostsPerMeter(cost.getCostsPerMeter()+1);
            case "driving" -> cost.setCostsPerSecond(cost.getCostsPerSecond()+1);
            case "waiting" -> VehicleUtils.setCostsPerSecondWaiting(cost,2.0);
            case "service" -> VehicleUtils.setCostsPerSecondInService(cost,2.0);
            case "fleet" -> carrier.getCarrierCapabilities().setFleetSize(carrier.getCarrierCapabilities().getFleetSize()==CarrierCapabilities.FleetSize.FINITE ? CarrierCapabilities.FleetSize.INFINITE : CarrierCapabilities.FleetSize.FINITE);
            case "pcu" -> type.setPcuEquivalents(type.getPcuEquivalents()+1);
            case "length" -> type.setLength(type.getLength()+1);
            case "flow" -> type.setFlowEfficiencyFactor(type.getFlowEfficiencyFactor()+1);
            default -> throw new AssertionError(field);
        }
        assertThatThrownBy(()->hagrid.core.routing.SectionBindingCompanion.verify(companion,carriers))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("execution plan");
    }

    @Test void sectionBuilderRejectsExternalTypeChangeBeforePublication() throws Exception {
        var input=sectionInputs(false);
        new NetworkChangeEventsWriter().write(input.getNetworkChangeEventPath().toString(),List.of());
        var types=new CarrierVehicleTypes();new CarrierVehicleTypeReader(types).readFile(input.getVehicleTypePath().toString());
        types.getVehicleTypes().get(Id.create("car-type",VehicleType.class)).getCapacity().setOther(11);
        new CarrierVehicleTypeWriter(types).write(input.getVehicleTypePath().toString());
        assertThatThrownBy(()->HAGRIDScenarioBuilder.build(input,List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("execution plan");
        assertThat(input.getMergedCarrierPath()).doesNotExist();
    }

    @Test void sectionFingerprintUsesEffectiveBikeSpeedAfterNormalization() throws Exception {
        var input=sectionInputs(false);
        var types=new CarrierVehicleTypes();new CarrierVehicleTypeReader(types).readFile(input.getVehicleTypePath().toString());
        var carriers=new Carriers();new CarrierPlanXmlReader(carriers,types).readFile(input.getDeliveryCarrierPath().toString());
        var carrier=carriers.getCarriers().get(Id.create("delivery",Carrier.class));
        var type=carrier.getSelectedPlan().getScheduledTours().iterator().next().getVehicle().getType();
        type.setNetworkMode("cargobike");type.setMaximumVelocity(10);
        hagrid.core.routing.CarrierFleetPreparation.applySpeedLimits(types,5);
        String saved=hagrid.core.routing.SectionBindingCompanion.executionFingerprint(carrier);
        type.setMaximumVelocity(20);hagrid.core.routing.CarrierFleetPreparation.applySpeedLimits(types,5);
        assertThat(hagrid.core.routing.SectionBindingCompanion.executionFingerprint(carrier)).isEqualTo(saved);
        type.setMaximumVelocity(20);hagrid.core.routing.CarrierFleetPreparation.applySpeedLimits(types,4);
        assertThat(hagrid.core.routing.SectionBindingCompanion.executionFingerprint(carrier)).isNotEqualTo(saved);
    }

    private Launch sectionInputs(boolean split) throws Exception {return sectionInputs(split,false);}
    private Launch sectionInputs(boolean split,boolean fractional) throws Exception {
        var launch=inputs(false,false,false);launch.sectionRun=true;
        var original=network(Set.of("car"));Network n=original;String stop="stop";
        if(split) {
            var road=new hagrid.core.routing.sections.PreparedHaltCatalog.Road("b","c",100,100,List.of(
                    new hagrid.core.routing.sections.PreparedHaltCatalog.Point(500100,5800000),
                    new hagrid.core.routing.sections.PreparedHaltCatalog.Point(500200,5800000)));
            var job=new hagrid.core.routing.sections.PreparedHaltCatalog.Job("delivery-service","stop",List.of(new hagrid.core.routing.sections.PreparedHaltCatalog.Approach("stop",.5)),"","");
            var catalog=new hagrid.core.routing.sections.PreparedHaltCatalog("delivery",java.util.Map.of("stop",road),List.of(job),"depot");
            var result=hagrid.core.routing.sections.SplitRoadNetworkBuilder.build(original,catalog);n=result.network();stop=result.jobApproaches().get("delivery-service").getFirst().haltLinkId().toString();
        }
        new NetworkWriter(n).write(launch.getCarNetworkPath().toString());
        var types=new CarrierVehicleTypes();new CarrierVehicleTypeReader(types).readFile(launch.getVehicleTypePath().toString());
        var type=types.getVehicleTypes().get(Id.create("car-type",VehicleType.class));
        var delivery=carrier("delivery",type,stop);CarriersUtils.setCarrierMode(delivery,"car");
        if(fractional){var prior=delivery.getSelectedPlan().getScheduledTours().iterator().next();var replacement=new CarrierPlan(List.of(ScheduledTour.newInstance(prior.getTour().duplicate(),prior.getVehicle(),3600.5)));delivery.getPlans().clear();delivery.addPlan(replacement);delivery.setSelectedPlan(replacement);}
        hagrid.core.routing.CarrierRoadRouter.routePlan(delivery.getSelectedPlan(),hagrid.core.routing.NativeModalTransportCosts.create(n,types,"car"));
        delivery.getAttributes().putAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER,"fixture-catalog");
        new CarrierPlanWriter(new Carriers(List.of(delivery))).write(launch.getDeliveryCarrierPath().toString());
        if(fractional){String xml=Files.readString(launch.getDeliveryCarrierPath());assertThat(xml).contains("end_time=\"01:00:00\"");Files.writeString(launch.getDeliveryCarrierPath(),xml.replace("end_time=\"01:00:00\"","end_time=\"3600.5\""));}
        new CarrierPlanWriter(new Carriers(List.of(carrier("supply",type,stop)))).write(launch.getSupplyCarrierPath().toString());
        var service=delivery.getServices().values().iterator().next();
        var originalService=java.util.Map.of("demand",1,"duration",10.0,"windowStart",0.0,"windowEnd",50000.0);
        var doc=new java.util.LinkedHashMap<String,Object>();doc.put("catalogFingerprint","fixture-catalog");
        doc.put("executionFingerprint",hagrid.core.routing.SectionBindingCompanion.executionFingerprint(delivery));
        doc.put("executionNetworkFingerprint",hagrid.core.routing.SectionBindingCompanion.networkFingerprint(n));
        var fixtureRoads=new hagrid.core.routing.sections.SectionRoadCosts() {
            public TurnClassification classifyTransition(Id<org.matsim.api.core.v01.network.Link> from,Id<org.matsim.api.core.v01.network.Link> to) { return TurnClassification.NOT_TURN; }
            public hagrid.core.routing.sections.SectionLeg leg(Id<org.matsim.api.core.v01.network.Link> from,Id<org.matsim.api.core.v01.network.Link> to,org.matsim.vehicles.VehicleType type) { throw new UnsupportedOperationException(); }
            public hagrid.core.routing.RoadLeg materialize(Id<org.matsim.api.core.v01.network.Link> from,Id<org.matsim.api.core.v01.network.Link> to,double departure,org.matsim.vehicles.Vehicle vehicle) { throw new UnsupportedOperationException(); }
            public String fingerprint() { return "fixture"; }
        };
        doc.put("turnTransitions",hagrid.core.routing.SectionBindingCompanion.capture(delivery,fixtureRoads,n).records());
        doc.put("bindings",java.util.Map.of(service.getId().toString(),stop));doc.put("allowed",java.util.Map.of(service.getId().toString(),List.of(stop)));
        doc.put("canonical",java.util.Map.of("services",java.util.Map.of(service.getId().toString(),originalService)));
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(Path.of(launch.getDeliveryCarrierPath()+".section-bindings.json").toFile(),java.util.Map.of("delivery",hagrid.core.routing.SectionBindingCompanion.seal(doc)));
        return launch;
    }

    private static String legacyTypes(boolean duplicate) {
        String type = "<vehicleType id=\"car-type\"><capacity>10</capacity><maxVelocity>20</maxVelocity>"
                + "<costInformation fix=\"1\" perMeter=\"0.01\" perSecond=\"1\"/></vehicleType>";
        return "<?xml version=\"1.0\"?><vehicleTypes>" + type + (duplicate ? type : "") + "</vehicleTypes>";
    }

    private Launch inputs(boolean cargo, boolean split, boolean invalid) {
        var launch = new Launch(directory);
        new ConfigWriter(ConfigUtils.createConfig()).write(launch.getConfigPath().toString());
        Network network = network(cargo && !split ? Set.of("car", "cargobike") : Set.of("car"));
        new NetworkWriter(network).write(launch.getCarNetworkPath().toString());
        if (split) {
            Network bike = network(Set.of("cargobike"));
            var link = NetworkUtils.createAndAddLink(bike, Id.createLinkId("bike-only"),
                    bike.getNodes().get(Id.createNodeId("a")), bike.getNodes().get(Id.createNodeId("c")),
                    200, 10, 1000, 1);
            link.setAllowedModes(Set.of("cargobike"));
            new NetworkWriter(bike).write(launch.getBikeNetworkPath().toString());
        }
        var event = new NetworkChangeEvent(100);
        event.addLink(network.getLinks().get(Id.createLinkId("stop")));
        event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(
                NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 2));
        new NetworkChangeEventsWriter().write(launch.getNetworkChangeEventPath().toString(), List.of(event));
        var car = type("car-type", "car");
        var bike = type("cargo-type", "cargobike");
        var types = new CarrierVehicleTypes();
        types.getVehicleTypes().put(car.getId(), car);
        types.getVehicleTypes().put(bike.getId(), bike);
        new CarrierVehicleTypeWriter(types).write(launch.getVehicleTypePath().toString());
        var delivery = carrier("delivery", cargo ? bike : car, invalid ? "missing" : split ? "bike-only" : "stop");
        // Missing carrierMode must be inferred from the actual type, not overwritten with car.
        CarriersUtils.setJspritIterations(delivery, 7);
        CarriersUtils.setJspritComputationTime(delivery, 12.5);
        delivery.getAttributes().putAttribute("history", "measured");
        new CarrierPlanWriter(new Carriers(List.of(delivery))).write(launch.getDeliveryCarrierPath().toString());
        new CarrierPlanWriter(new Carriers(List.of(carrier("supply", car, "stop"))))
                .write(launch.getSupplyCarrierPath().toString());
        return launch;
    }

    private static Carrier carrier(String id, VehicleType type, String location) {
        var carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
        var vehicle = CarrierVehicle.Builder.newInstance(Id.createVehicleId(id + "-vehicle"), Id.createLinkId("depot"), type)
                .setEarliestStart(0).setLatestEnd(50000).build();
        CarriersUtils.addCarrierVehicle(carrier, vehicle);
        var service = CarrierService.Builder.newInstance(Id.create(id + "-service", CarrierService.class),
                Id.createLinkId(location), 1).setServiceDuration(10)
                .setServiceStartingTimeWindow(TimeWindow.newInstance(0, 50000)).build();
        CarriersUtils.addService(carrier, service);
        var tour = Tour.Builder.newInstance(Id.create(id + "-tour", Tour.class));
        tour.scheduleStart(Id.createLinkId("depot"));
        tour.addLeg(new Tour.Leg()); tour.scheduleService(service); tour.addLeg(new Tour.Leg());
        tour.scheduleEnd(Id.createLinkId("depot"));
        var plan = new CarrierPlan(List.of(ScheduledTour.newInstance(tour.build(), vehicle, 3600)));
        plan.setScore(-123.0);
        carrier.addPlan(plan); carrier.setSelectedPlan(plan);
        return carrier;
    }

    private static VehicleType type(String id, String mode) {
        var type = VehicleUtils.createVehicleType(Id.create(id, VehicleType.class), mode);
        type.setMaximumVelocity(10);
        type.getCapacity().setOther(10);
        type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(0.01).setCostsPerSecond(1.0);
        return type;
    }

    private static Network network(Set<String> modes) {
        var network = NetworkUtils.createNetwork();
        var a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(500000, 5800000));
        var b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(500100, 5800000));
        var c = NetworkUtils.createAndAddNode(network, Id.createNodeId("c"), new Coord(500200, 5800000));
        List.of(NetworkUtils.createAndAddLink(network, Id.createLinkId("depot"), a, b, 100, 10, 1000, 1),
                NetworkUtils.createAndAddLink(network, Id.createLinkId("stop"), b, c, 100, 10, 1000, 1),
                NetworkUtils.createAndAddLink(network, Id.createLinkId("back"), c, a, 200, 10, 1000, 1))
                .forEach(link -> link.setAllowedModes(modes));
        return network;
    }

    private static final class Launch extends HAGRIDSimulationConfig {
        private final Path root;
        private boolean sectionRun;
        private boolean compressedChanges;
        @Override public int getMaxIterations(){return sectionRun?0:super.getMaxIterations();}
        Launch(Path root) {
            super("basecase", LocalDate.of(2025, 5, 13), 1, 2, false, 1500, 0, "fixture");
            this.root = root;
        }
        @Override public Path getFreightZonePath() { return root.resolve("zones.shp"); }
        @Override public Path getConfigPath() { return root.resolve("config.xml"); }
        @Override public Path getVehicleTypePath() { return root.resolve("types.xml"); }
        @Override public Path getCarNetworkPath() { return root.resolve("network.xml"); }
        @Override public Path getBikeNetworkPath() { return root.resolve("bike.xml"); }
        @Override public Path getNetworkChangeEventPath() { return root.resolve(compressedChanges?"changes.xml.gz":"changes.xml"); }
        @Override public Path getDeliveryCarrierPath() { return root.resolve("delivery.xml"); }
        @Override public Path getSupplyCarrierPath() { return root.resolve("supply.xml"); }
        @Override public Path getMergedCarrierPath() { return root.resolve("output/combined.xml"); }
        @Override public Path getOutputDirectory() { return root.resolve("simulation"); }
    }
}
