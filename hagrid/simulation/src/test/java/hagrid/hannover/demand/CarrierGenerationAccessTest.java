package hagrid.hannover.demand;

import hagrid.core.HagridConfig;
import hagrid.core.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CarrierGenerationAccessTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"bike", "cargobike", "car"})
    void wholeDeliveryGeneratorPublishesValidatedModalInputAndXml(String mode) throws Exception {
        var f = wholeRun(mode, false);
        f.generator.run();
        var carriers = (Carriers) f.scenario.getScenarioElement("carriers");
        assertThat(carriers).isNotNull();
        assertThat(carriers.getCarriers()).hasSize(1);
        var carrier = carriers.getCarriers().values().iterator().next();
        assertThat(CarriersUtils.getCarrierMode(carrier)).isEqualTo(mode);
        assertThat(carrier.getServices().values()).singleElement()
                .satisfies(s -> assertThat(s.getServiceLinkId()).isEqualTo(Id.createLinkId(mode.equals("car") ? "car-stop" : "stop")));
        var file = java.nio.file.Path.of(f.config.io().deliveryCarriersUnrouted());
        assertThat(file).exists();
        var types = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(types).readFile(f.config.io().vehicleTypesOutput());
        var restored = new Carriers();
        new CarrierPlanXmlReader(restored, types).readFile(file.toString());
        assertThat(CarriersUtils.getCarrierMode(restored.getCarriers().values().iterator().next())).isEqualTo(mode);
        assertThat(restored.getCarriers().values().iterator().next().getServices().values()).singleElement().satisfies(job -> {
            assertThat(job.getAttributes().getAttribute("stop_id")).isEqualTo("xml-stop");
            assertThat(job.getAttributes().getAttribute("str_idx")).isEqualTo("7");
            assertThat(job.getAttributes().getAttribute("section_id")).isEqualTo("7-0-1");
        });
    }

    @Test
    void importedSourceProvenanceSurvivesDeliveryServiceAndMerge() throws Exception {
        var f = fixture("car");
        var schema = new org.geotools.feature.simple.SimpleFeatureTypeBuilder();
        schema.setName("demand");
        schema.add("geometry", org.locationtech.jts.geom.Point.class);
        schema.add("id", Long.class); schema.add("postal_cod", String.class);
        schema.add("dhl_tag", Long.class); schema.add("dhl_type", Long.class);
        schema.add("stop_type", String.class); schema.add("stop_id", String.class);
        schema.add("str_idx", Long.class); schema.add("section_id", String.class);
        var feature = org.geotools.feature.simple.SimpleFeatureBuilder.build(schema.buildFeatureType(), new Object[]{
            new org.locationtech.jts.geom.GeometryFactory().createPoint(new org.locationtech.jts.geom.Coordinate(75,0)),
            1L,"12345",1L,1L,"home","stop-a",7L,"7-0-1"}, "source");
        var deliveryGenerator = new DeliveryGenerator(); set(deliveryGenerator,"hagridConfig",f.config);
        @SuppressWarnings("unchecked") var deliveries = (Map<String,ArrayList<Delivery>>) invoke(deliveryGenerator,
            "convertDemandFromShapeToParcels", new Class<?>[]{Map.class}, Map.of("dhl_12345_fixture",List.of(feature)));
        var c=carrier(f.generator);
        invoke(f.generator,"addCarrierServicesToCarriers",new Class<?>[]{Carrier.class,ArrayList.class,Map.class},
            c,deliveries.get("dhl_12345_fixture"),Map.of("dhl",94.0));
        assertThat(c.getServices().values()).hasSize(2).allSatisfy(job -> {
            assertThat(job.getAttributes().getAttribute("stop_id")).isEqualTo("stop-a");
            assertThat(job.getAttributes().getAttribute("str_idx").toString()).isEqualTo("7");
            assertThat(job.getAttributes().getAttribute("section_id")).isEqualTo("7-0-1");
        });
        var hub=hub("hub","car-home",25); assign(f.generator,c,Map.of(hub.getId(),hub));
        var carriers=new Carriers(); carriers.addCarrier(c);
        CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true);
        assertThat(c.getServices().values()).singleElement().satisfies(job -> {
            assertThat(job.getAttributes().getAttribute("stop_id")).isEqualTo("stop-a");
            assertThat(job.getAttributes().getAttribute("mergedMetadata").toString()).contains("stop-a","7-0-1");
        });
    }

    @Test
    void nonFullMergeKeepsPythonExportedPhysicalStopsOnLongLink() throws Exception {
        var f = fixture("car");
        for (var network : List.of(f.scenario.getNetwork(), f.carStops)) {
            var a=NetworkUtils.createAndAddNode(network,Id.createNodeId("long-a"),new Coord(550000,5800000));
            var b=NetworkUtils.createAndAddNode(network,Id.createNodeId("long-b"),new Coord(555000,5800000));
            NetworkUtils.createAndAddLink(network,Id.createLinkId("long-stop"),a,b,5000,10,1000,1).setAllowedModes(Set.of("car"));
        }
        var resource=getClass().getResource("/long-link-python/hagrid_parcel_demand_2025-05-13_(Tuesday).shp");
        assertThat(resource).isNotNull();
        var features=org.matsim.core.utils.gis.GeoFileReader.getAllFeatures(java.nio.file.Path.of(resource.toURI()).toString());
        assertThat(features).hasSize(4);
        var deliveryGenerator=new DeliveryGenerator(); set(deliveryGenerator,"hagridConfig",f.config);
        @SuppressWarnings("unchecked") var deliveries=(Map<String,ArrayList<Delivery>>) invoke(deliveryGenerator,
            "convertDemandFromShapeToParcels",new Class<?>[]{Map.class},Map.of("dhl_30519_fixture",List.copyOf(features)));
        assertThat(deliveries.get("dhl_30519_fixture")).hasSize(5);
        var c=carrier(f.generator); CarriersUtils.setCarrierMode(c,"car");
        invoke(f.generator,"addCarrierServicesToCarriers",new Class<?>[]{Carrier.class,ArrayList.class,Map.class},
            c,deliveries.get("dhl_30519_fixture"),Map.of("dhl",100.0));
        assertThat(c.getServices()).hasSize(5);
        assertThat(c.getServices().values()).allSatisfy(job -> assertThat(job.getServiceLinkId()).isEqualTo(Id.createLinkId("long-stop")));
        var carriers=new Carriers(); carriers.addCarrier(c); CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,false);
        assertThat(c.getServices()).hasSize(4);
        assertThat(c.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum()).isEqualTo(11);
        assertThat(c.getServices().values().stream().map(job -> job.getAttributes().getAttribute("stop_id"))).doesNotHaveDuplicates();
    }

    @Test
    void failedTotalsMustAbortBeforeScenarioRegistrationAndXmlOutput() throws Exception {
        var f = wholeRun("cargobike", true);
        assertThatIllegalStateException().isThrownBy(f.generator::run).withMessageContaining("generation");
        assertThat(f.scenario.getScenarioElement("carriers")).isNull();
        assertThat(java.nio.file.Path.of(f.config.io().deliveryCarriersUnrouted())).doesNotExist();
        assertThat(java.nio.file.Path.of(f.config.io().vehicleTypesOutput())).doesNotExist();
    }

    @Test
    void impossibleDepotMustAbortWholeGeneratorBeforeAnyCarrierOutput() throws Exception {
        var f = wholeRun("cargobike", false);
        island(f.scenario.getNetwork(), "island", "cargobike", 99);
        ((org.matsim.core.scenario.MutableScenario) f.scenario).removeScenarioElement("hubList");
        var hub = hub("unreachable", "island", 99);
        f.scenario.addScenarioElement("hubList", Map.of(hub.getId(), hub));
        assertThatIllegalStateException().isThrownBy(f.generator::run).withMessageContaining("generation");
        assertThat(f.scenario.getScenarioElement("carriers")).isNull();
        assertThat(hub.getAssignedSupplyDemand()).isZero();
        assertThat(java.nio.file.Path.of(f.config.io().deliveryCarriersUnrouted())).doesNotExist();
    }

    @Test
    void existingCarrierCollectionCannotBeReplacedByGeneration() throws Exception {
        var f = wholeRun("cargobike", false);
        var existing = new Carriers();
        var existingCarrier = CarriersUtils.createCarrier(Id.create("existing", Carrier.class));
        existing.addCarrier(existingCarrier);
        f.scenario.addScenarioElement("carriers", existing);
        assertThatIllegalStateException().isThrownBy(f.generator::run).withMessageContaining("generation");
        assertThat(f.scenario.getScenarioElement("carriers")).isSameAs(existing);
        assertThat(existing.getCarriers().values()).containsExactly(existingCarrier);
        var hubs = (Map<?, ?>) f.scenario.getScenarioElement("hubList");
        assertThat(((Hub) hubs.values().iterator().next()).getAssignedSupplyDemand()).isZero();
        assertThat(java.nio.file.Path.of(f.config.io().deliveryCarriersUnrouted())).doesNotExist();
    }

    private Fixture wholeRun(String mode, boolean wrongTotals) throws Exception {
        var f = fixture(mode);
        set(f.config, "hagridPaths", new hagrid.core.HagridPaths(directory));
        f.config.setSimulationDate(java.time.LocalDate.of(2025, 5, 13));
        f.config.routing().setCarrierMergeThreshold(1);
        var delivery = Delivery.builder().coordinate(new Coord(75, 0)).provider("dhl").amount(1)
                .parcelType(Delivery.ParcelType.B2C).postalCode("12345")
                .deliveryMode(Delivery.DeliveryMode.HOME).stopId("xml-stop").strIdx("7").sectionId("7-0-1").individualWeights(new ArrayList<>(List.of(1.0))).build();
        f.scenario.addScenarioElement("deliveries", Map.of("dhl_12345_fixture", new ArrayList<>(List.of(delivery))));
        var hub = hub("hub", "car-home", 25);
        f.scenario.addScenarioElement("hubList", Map.of(hub.getId(), hub));
        ((org.matsim.core.scenario.MutableScenario) f.scenario).removeScenarioElement("summary");
        f.scenario.addScenarioElement("summary", HAGRIDSummary.builder().totalDeliveries(wrongTotals ? 2 : 1).totalParcels(1).build());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(f.config.io().deliveryCarriersUnrouted()).getParent());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(f.config.io().vehicleTypesOutput()).getParent());
        return f;
    }
    @ParameterizedTest
    @ValueSource(strings = {"bike", "cargobike"})
    void generatedStopUsesExactModeExclusiveLinkInsteadOfNearestCarLink(String mode) throws Exception {
        var f = fixture(mode);
        var carrier = carrier(f.generator);
        var delivery = Delivery.builder().coordinate(new Coord(75, 0)).provider("dhl").amount(1)
                .parcelType(Delivery.ParcelType.B2C).postalCode("12345")
                .deliveryMode(Delivery.DeliveryMode.HOME).individualWeights(new ArrayList<>(List.of(1.0))).build();
        invoke(f.generator, "addCarrierServicesToCarriers",
                new Class<?>[]{Carrier.class, ArrayList.class, Map.class},
                carrier, new ArrayList<>(List.of(delivery)), Map.of("dhl", 94.0));
        assertThat(carrier.getServices().values()).singleElement()
                .satisfies(s -> assertThat(s.getServiceLinkId()).isEqualTo(Id.createLinkId("stop")));
        assertThat(f.scenario.getNetwork().getLinks().get(Id.createLinkId("stop")).getAllowedModes()).containsExactly(mode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bike", "cargobike"})
    void hubGetsLocalModalDepotWithoutChangingItsSharedSupplyLink(String mode) throws Exception {
        var f = fixture(mode);
        var carrier = withStop(f.generator);
        var hub = hub("hub", "car-home", 25);
        assign(f.generator, carrier, Map.of(hub.getId(), hub));
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles().values()).singleElement()
                .satisfies(v -> assertThat(v.getLinkId()).isEqualTo(Id.createLinkId("home")));
        assertThat(hub.getLink()).isEqualTo(Id.createLinkId("car-home"));
        assertThat(hub.getAssignedSupplyDemand()).isEqualTo(1);
        assertThat(CarriersUtils.getCarrierMode(carrier)).isEqualTo(mode);
    }

    @Test
    void closerDisconnectedHubIsSkippedInFavourOfReturningDepot() throws Exception {
        var f = fixture("cargobike");
        island(f.scenario.getNetwork(), "island", "cargobike", 99);
        var near = hub("near", "island", 99);
        var far = hub("far", "home", 0);
        var carrier = withStop(f.generator);
        assign(f.generator, carrier, Map.of(near.getId(), near, far.getId(), far));
        assertThat(carrier.getAttributes().getAttribute("hubId")).isEqualTo("far");
        assertThat(near.getAssignedSupplyDemand()).isZero();
        assertThat(far.getAssignedSupplyDemand()).isEqualTo(1);
    }

    @Test
    void missingReturningDepotCannotPublishVehiclesOrReserveHubCapacity() throws Exception {
        var f = fixture("cargobike");
        island(f.scenario.getNetwork(), "island", "cargobike", 99);
        var hub = hub("unreachable", "island", 99);
        var carrier = withStop(f.generator);
        assertThatIllegalArgumentException().isThrownBy(() -> assign(f.generator, carrier, Map.of(hub.getId(), hub)))
                .withMessageContaining("dhl_12345_fixture").withMessageContaining("reachable");
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles()).isEmpty();
        assertThat(hub.getAssignedSupplyDemand()).isZero();
        assertThat(carrier.getAttributes().getAttribute("hubId")).isNull();
    }

    @Test
    void mixedFleetFailsBeforeAnyVehicleOrHubReservation() throws Exception {
        var f = fixture("cargobike");
        f.config.vehicles().setActiveSizes(List.of("m", "bike"));
        var hub = hub("hub", "home", 0);
        var carrier = withStop(f.generator);
        assertThatIllegalArgumentException().isThrownBy(() -> assign(f.generator, carrier, Map.of(hub.getId(), hub)))
                .withMessageContaining("mixed");
        assertThat(hub.getAssignedSupplyDemand()).isZero();
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles()).isEmpty();
        assertThat(carrier.getAttributes().getAttribute("hubId")).isNull();
    }

    @Test
    void originalCarDepotAndFleetRemainUnchanged() throws Exception {
        var f = fixture("car");
        var hub = hub("hub", "car-home", 0);
        var carrier = carrier(f.generator);
        var service = CarrierService.Builder.newInstance(Id.create("s", CarrierService.class), Id.createLinkId("car-stop"), 1).build();
        service.getAttributes().putAttribute("coord", new Coord(75, 0));
        CarriersUtils.addService(carrier, service);
        assign(f.generator, carrier, Map.of(hub.getId(), hub));
        assertThat(carrier.getCarrierCapabilities().getCarrierVehicles().values()).singleElement()
                .satisfies(v -> assertThat(v.getLinkId()).isEqualTo(Id.createLinkId("car-home")));
        assertThat(CarriersUtils.getCarrierMode(carrier)).isEqualTo("car");
    }

    private static Fixture fixture(String mode) throws Exception {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        cycle(network, "car-", "car", 0);
        if (!mode.equals("car")) cycle(network, "", mode, 10);
        var carStops = NetworkUtils.createNetwork();
        cycle(carStops, "car-", "car", 0);
        scenario.addScenarioElement("parcelServiceNetwork", carStops);
        scenario.addScenarioElement("summary", HAGRIDSummary.builder().build());
        var config = new HagridConfig();
        config.vehicles().setActiveSizes(List.of(mode.equals("car") ? "m" : "bike"));
        config.vehicles().setDefaultDispatchHours(List.of(7));
        var types = new CarrierVehicleTypes();
        for (var entry : Map.of("ct_cep_size_m", "car", "ct_cep_bike", mode).entrySet()) {
            var type = VehicleUtils.createVehicleType(Id.create(entry.getKey(), VehicleType.class), entry.getValue());
            type.setMaximumVelocity(25);
            type.getCapacity().setOther(23);
            types.getVehicleTypes().put(type.getId(), type);
        }
        scenario.addScenarioElement("carrierVehicleTypes", types);
        var generator = new CarrierGenerator();
        set(generator, "hagridConfig", config);
        set(generator, "scenario", scenario);
        set(generator, "carrierVehicleFactory", new CarrierVehicleFactory(types));
        return new Fixture(generator, scenario, config, carStops);
    }

    private static void cycle(Network n, String prefix, String mode, double y) {
        var a = NetworkUtils.createAndAddNode(n, Id.createNodeId(prefix + "a"), new Coord(0, y));
        var b = NetworkUtils.createAndAddNode(n, Id.createNodeId(prefix + "b"), new Coord(50, y));
        var c = NetworkUtils.createAndAddNode(n, Id.createNodeId(prefix + "c"), new Coord(100, y));
        link(n, prefix + "home", a, b, mode);
        link(n, prefix + "stop", b, c, mode);
        var d = NetworkUtils.createAndAddNode(n, Id.createNodeId(prefix + "d"), new Coord(0, y + 20));
        link(n, prefix + "return", c, d, mode);
        link(n, prefix + "close", d, a, mode);
    }

    private static void island(Network n, String id, String mode, double x) {
        var a = NetworkUtils.createAndAddNode(n, Id.createNodeId(id + "a"), new Coord(x, 0));
        var b = NetworkUtils.createAndAddNode(n, Id.createNodeId(id + "b"), new Coord(x + 1, 0));
        link(n, id, a, b, mode);
        link(n, id + "return", b, a, mode);
    }

    private static void link(Network n, String id, Node from, Node to, String mode) {
        var link = NetworkUtils.createAndAddLink(n, Id.createLinkId(id), from, to, 50, 10, 1000, 1);
        link.setAllowedModes(Set.of(mode));
    }

    private static Hub hub(String id, String link, double x) {
        var hub = new Hub(Id.create(id, Hub.class), "dhl", new Coord(x, 0));
        hub.setLink(Id.createLinkId(link));
        return hub;
    }

    private static Carrier carrier(CarrierGenerator generator) throws Exception {
        var carrier = CarriersUtils.createCarrier(Id.create("dhl_12345_fixture", Carrier.class));
        invoke(generator, "setupCarrierAttributes", new Class<?>[]{Carrier.class, String.class}, carrier, carrier.getId().toString());
        return carrier;
    }

    private static Carrier withStop(CarrierGenerator generator) throws Exception {
        var carrier = carrier(generator);
        var service = CarrierService.Builder.newInstance(Id.create("s", CarrierService.class), Id.createLinkId("stop"), 1).build();
        service.getAttributes().putAttribute("coord", new Coord(75, 0));
        CarriersUtils.addService(carrier, service);
        return carrier;
    }

    private static void assign(CarrierGenerator generator, Carrier carrier, Map<Id<Hub>, Hub> hubs) throws Exception {
        invoke(generator, "addCarrierVehiclesToCarrier", new Class<?>[]{Carrier.class, Map.class}, carrier, hubs);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] signature, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private record Fixture(CarrierGenerator generator, Scenario scenario, HagridConfig config, Network carStops) {}
}
