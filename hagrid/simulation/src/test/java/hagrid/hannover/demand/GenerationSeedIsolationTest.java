package hagrid.hannover.demand;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import hagrid.core.HagridConfig;
import hagrid.core.HagridPaths;
import hagrid.core.config.HagridConfigResolver;
import hagrid.core.config.RandomSeedSettings;
import hagrid.core.util.Delivery;
import hagrid.core.util.RunRandomStreams;
import hagrid.core.util.WeightGenerator;
import hagrid.core.util.Hub;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** Exercises generation consumers: a tag or another live generator must not alter inputs. */
class GenerationSeedIsolationTest {
    @TempDir Path directory;

    @Test void sameSeedsDifferentTagPreserveGeneratedParcelData() throws Exception {
        var before = config("before");
        var after = config("after");
        assertThat(before.getRunId()).isNotEqualTo(after.getRunId());
        assertThat(generateParcels(after)).containsExactlyElementsOf(generateParcels(before));
    }

    @Test void sameSeedsDifferentTagPreserveGeneratedCarrierJobsMissesAndDepartures() throws Exception {
        var before = generateCarrier(config("carrier-before"));
        var after = generateCarrier(config("carrier-after"));
        assertThat(after).isEqualTo(before);
    }

    @Test void anotherWeightGeneratorCannotAdvanceTheFirstInstancesStream() {
        var streams = new RunRandomStreams(new RandomSeedSettings(42));
        var expected = new WeightGenerator(streams.forKey("demand.weight", "parcel-A"));
        double[] reference = weights(expected);
        var observed = new WeightGenerator(streams.forKey("demand.weight", "parcel-A"));
        var unrelated = new WeightGenerator(streams.forKey("demand.weight", "parcel-B"));
        double[] interleaved = new double[reference.length];
        for (int i = 0; i < interleaved.length; i++) {
            interleaved[i] = observed.generateWeight(i % 2 == 0);
            weights(unrelated);
        }
        assertThat(interleaved).containsExactly(reference);
    }

    @Test void anotherVehicleFactoryCannotAdvanceTheFirstInstancesDispatchStream() throws Exception {
        var config = config("dispatch");
        var resolved = HagridConfigResolver.resolve(config, HagridConfigResolver.Overrides.empty(),
                HagridConfigResolver.Overrides.empty(), Map.of());
        var streams = new RunRandomStreams(new RandomSeedSettings(42));
        var expected = new CarrierVehicleFactory(types(), resolved.fleet(), config,
                streams.forKey("dispatch.departure", "carrier-A"));
        double[] reference = departures(expected);
        var observed = new CarrierVehicleFactory(types(), resolved.fleet(), config,
                streams.forKey("dispatch.departure", "carrier-A"));
        var unrelated = new CarrierVehicleFactory(types(), resolved.fleet(), config,
                streams.forKey("dispatch.departure", "carrier-B"));
        double[] interleaved = new double[reference.length];
        for (int i = 0; i < interleaved.length; i++) {
            interleaved[i] = observed.createCEPVehicle(Id.createLinkId("home"), "depot", 7, 25200, "m").getEarliestStartTime();
            departures(unrelated);
        }
        assertThat(interleaved).containsExactly(reference);
    }

    private HagridConfig config(String tag) throws Exception {
        var config = new HagridConfig();
        var paths = HagridConfig.class.getDeclaredField("hagridPaths");
        paths.setAccessible(true);
        paths.set(config, new HagridPaths(directory));
        config.setTag(tag);
        config.setSimulationDate(LocalDate.of(2025, 5, 13));
        return config;
    }

    @SuppressWarnings("unchecked")
    private static List<ParcelInput> generateParcels(HagridConfig config) {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        scenario.addScenarioElement("carrierDemand", Map.of("dhl_12345_fixture", List.of(feature())));
        scenario.addScenarioElement("parcelLockerList", Map.of());
        var injector = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                bind(Scenario.class).toInstance(scenario);
                bind(HagridConfig.class).toInstance(config);
            }
        });
        injector.getInstance(DeliveryGenerator.class).run();
        var result = (Map<String, ArrayList<Delivery>>) scenario.getScenarioElement("deliveries");
        assertThat(result).as("real generator must publish both parcel types").isNotNull();
        assertThat(result.get("dhl_12345_fixture")).hasSize(2);
        return result.get("dhl_12345_fixture").stream().map(d -> new ParcelInput(d.getId(), d.getProvider(),
                d.getPostalCode(), d.getAmount(), d.getParcelType(), d.getCoordinate().getX(),
                d.getCoordinate().getY(), List.copyOf(d.getIndividualWeights()))).toList();
    }

    private static SimpleFeature feature() {
        var type = new SimpleFeatureTypeBuilder();
        type.setName("seed-regression");
        type.add("geometry", Point.class);
        type.add("id", Long.class);
        type.add("postal_cod", String.class);
        type.add("dhl_tag", Long.class);
        type.add("dhl_type", Long.class);
        type.add("stop_type", String.class);
        return SimpleFeatureBuilder.build(type.buildFeatureType(), new Object[]{
                new GeometryFactory().createPoint(new Coordinate(75, 0)), 1L, "12345", 30L, 10L, "home"}, "stop-1");
    }

    private static CarrierVehicleTypes types() {
        var types = new CarrierVehicleTypes();
        var type = VehicleUtils.createVehicleType(Id.create("ct_cep_size_m", VehicleType.class), "car");
        type.setMaximumVelocity(25);
        type.getCapacity().setOther(100);
        types.getVehicleTypes().put(type.getId(), type);
        return types;
    }

    @SuppressWarnings("unchecked")
    private static CarrierInput generateCarrier(HagridConfig config) throws Exception {
        config.vehicles().setActiveSizes(List.of("m"));
        config.vehicles().setDefaultDispatchHours(List.of(7));
        config.routing().setCarrierMergeThreshold(1);
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        var a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0, 0));
        var b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(50, 0));
        var c = NetworkUtils.createAndAddNode(network, Id.createNodeId("c"), new Coord(100, 0));
        NetworkUtils.createAndAddLink(network, Id.createLinkId("home"), a, b, 50, 10, 1000, 1).setAllowedModes(Set.of("car"));
        NetworkUtils.createAndAddLink(network, Id.createLinkId("stop"), b, c, 50, 10, 1000, 1).setAllowedModes(Set.of("car"));
        NetworkUtils.createAndAddLink(network, Id.createLinkId("return"), c, a, 100, 10, 1000, 1).setAllowedModes(Set.of("car"));
        scenario.addScenarioElement("parcelServiceNetwork", network);
        scenario.addScenarioElement("carrierVehicleTypes", types());
        scenario.addScenarioElement("carrierDemand", Map.of("dhl_12345_fixture", List.of(feature())));
        scenario.addScenarioElement("parcelLockerList", Map.of());
        var hub = new Hub(Id.create("hub", Hub.class), "dhl", new Coord(25, 0));
        hub.setLink(Id.createLinkId("home"));
        scenario.addScenarioElement("hubList", Map.of(hub.getId(), hub));
        var injector = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                bind(Scenario.class).toInstance(scenario);
                bind(HagridConfig.class).toInstance(config);
            }
        });
        Files.createDirectories(Path.of(config.io().deliveryCarriersUnrouted()).getParent());
        Files.createDirectories(Path.of(config.io().vehicleTypesOutput()).getParent());
        injector.getInstance(DeliveryGenerator.class).run();
        assertThat(scenario.getScenarioElement("deliveries")).isNotNull();
        injector.getInstance(CarrierGenerator.class).run();
        var carriers = (Carriers) scenario.getScenarioElement("carriers");
        assertThat(carriers).isNotNull();
        assertThat(carriers.getCarriers()).hasSize(1);
        var carrier = carriers.getCarriers().values().iterator().next();
        assertThat(carrier.getServices().values().stream().mapToInt(s -> s.getCapacityDemand()).sum()).isEqualTo(40);
        var jobs = carrier.getServices().values().stream().map(s -> s.getId() + "/" + s.getServiceLinkId()
                + "/" + s.getCapacityDemand() + "/" + s.getServiceDuration() + "/" + s.getServiceStartTimeWindow().getStart()
                + "/" + s.getServiceStartTimeWindow().getEnd()).sorted().toList();
        var rawMissed = (List<?>) carrier.getAttributes().getAttribute("missedParcelsAsList");
        var missed = rawMissed == null ? List.<String>of() : rawMissed.stream().map(Object::toString).sorted().toList();
        assertThat((Number) carrier.getAttributes().getAttribute("missedParcels"))
                .extracting(Number::intValue).isEqualTo(missed.size());
        var vehicles = carrier.getCarrierCapabilities().getCarrierVehicles().values().stream()
                .map(v -> v.getId() + "/" + v.getLinkId() + "/" + v.getType().getId()
                        + "/" + v.getEarliestStartTime() + "/" + v.getLatestEndTime()).sorted().toList();
        assertThat(jobs).isNotEmpty();
        assertThat(vehicles).isNotEmpty();
        return new CarrierInput(jobs, missed, vehicles);
    }

    private static double[] weights(WeightGenerator generator) {
        double[] result = new double[12];
        for (int i = 0; i < result.length; i++) result[i] = generator.generateWeight(i % 2 == 0);
        return result;
    }

    private static double[] departures(CarrierVehicleFactory factory) {
        double[] result = new double[12];
        for (int i = 0; i < result.length; i++) result[i] = factory.createCEPVehicle(
                Id.createLinkId("home"), "depot", 7, 25200, "m").getEarliestStartTime();
        return result;
    }

    private record ParcelInput(String id, String provider, String postalCode, int amount,
                               Delivery.ParcelType type, double x, double y, List<Double> weights) {}
    private record CarrierInput(List<String> jobs, List<String> missedMultiset, List<String> vehicles) {}
}
