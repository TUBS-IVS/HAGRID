package hagrid.simulation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds a complete MATSim {@link Scenario} for the HAGRID simulation.
 * <p>
 * Includes configuration setup, network loading, carrier merging, zone
 * assignment, and freight info logging.
 */
public class HAGRIDScenarioBuilder {
    /**
     * Ensures that all CarrierService objects have the correct ParcelType attribute (MIXED, not Mixed).
     */

    private static final Logger LOGGER = LogManager.getLogger(HAGRIDScenarioBuilder.class);

    /**
     * Builds and returns a fully configured MATSim scenario based on the provided
     * simulation config.
     *
     * @param simConfig    the HAGRID scenario configuration
     * @param zoneFeatures collection of freight zone features (used for spatial
     *                     link tagging & zone based routing with jsprit)
     * @return the initialized MATSim scenario
     * @throws Exception if any error occurs during setup (e.g., file reading)
     */
    public static Scenario build(HAGRIDSimulationConfig simConfig, Collection<SimpleFeature> zoneFeatures)
            throws Exception {
    LOGGER.info("==============================================");
    LOGGER.info("Building HAGRID scenario '{}'", simConfig.getRunId());
    LOGGER.info("==============================================");
    Instant buildStart = Instant.now();

    LOGGER.info("[1/8] Loading MATSim base config from {}", simConfig.getConfigPath());
        Config config = ConfigUtils.loadConfig(simConfig.getConfigPath().toAbsolutePath().toString());
    LOGGER.info("[2/8] Applying HAGRID simulation overrides");
        setupConfig(config, simConfig);

    LOGGER.info("[3/8] Loading carrier vehicle types from {}", simConfig.getVehicleTypePath());
        CarrierVehicleTypes types = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(types).readFile(simConfig.getVehicleTypePath().toAbsolutePath().toString());
    LOGGER.info("Loaded {} vehicle type definitions", types.getVehicleTypes().size());

    LOGGER.info("[4/8] Merging delivery and supply carriers into unified plans");
        mergeCarriers(types, simConfig);

    LOGGER.info("[5/8] Configuring freight carrier module");
        FreightCarriersConfigGroup freightConfig = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        freightConfig.setCarriersFile(simConfig.getMergedCarrierPath().toAbsolutePath().toString());
        freightConfig.setCarriersVehicleTypesFile(simConfig.getVehicleTypePath().toAbsolutePath().toString());

    LOGGER.info("[6/8] Loading MATSim scenario graph and population");
        Scenario scenario = ScenarioUtils.loadScenario(config);
    LOGGER.info("Scenario network contains {} links and {} nodes", scenario.getNetwork().getLinks().size(),
        scenario.getNetwork().getNodes().size());

        // Load and attach alternative networks for routing different modes
    LOGGER.info("[7/8] Loading modal networks for car and cargobike routing");
        NetworkConfigGroup netCfg = ConfigUtils.addOrGetModule(config, NetworkConfigGroup.class);
        Network carNet = org.matsim.core.network.NetworkUtils.createNetwork(netCfg);
        new MatsimNetworkReader(carNet).readFile(simConfig.getCarNetworkPath().toAbsolutePath().toString());
        scenario.addScenarioElement("carNetwork", carNet);
    LOGGER.info("Loaded car network with {} links", carNet.getLinks().size());

        Network bikeNet = org.matsim.core.network.NetworkUtils.createNetwork(netCfg);
        new MatsimNetworkReader(bikeNet).readFile(simConfig.getBikeNetworkPath().toAbsolutePath().toString());
        scenario.addScenarioElement("bikeNetwork", bikeNet);
    LOGGER.info("Loaded cargobike network with {} links", bikeNet.getLinks().size());

    LOGGER.info("[8/8] Assigning freight zones to network links");
        assignZones(scenario, zoneFeatures);

    LOGGER.info("Loading carriers into scenario according to freight configuration");
        CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);

        logFreightInfos(scenario);

    Duration totalDuration = Duration.between(buildStart, Instant.now());
    LOGGER.info("HAGRID scenario '{}' ready in {} ms", simConfig.getRunId(), totalDuration.toMillis());

        return scenario;
    }

    /**
     * Configures the MATSim config object using parameters from the simulation
     * config.
     *
     * @param config    the MATSim config to modify
     * @param simConfig the HAGRID simulation configuration
     */
    @SuppressWarnings("deprecation")
    private static void setupConfig(Config config, HAGRIDSimulationConfig simConfig) {

        config.global().setRandomSeed(1337);

        config.network().setInputFile(simConfig.getCarNetworkPath().toAbsolutePath().toString());
        config.network().setTimeVariantNetwork(true);
        config.network().setChangeEventsInputFile(simConfig.getNetworkChangeEventPath().toAbsolutePath().toString());
        config.network().setInputCRS("EPSG:25832");

        config.controller().setOutputDirectory(simConfig.getOutputDirectory().toAbsolutePath().toString());
        config.controller().setRunId(simConfig.getRunId());
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(simConfig.getMaxIterations());
        config.controller().setOverwriteFileSetting(
                org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);

        config.global().setCoordinateSystem("EPSG:25832");

        // Define transport modes
        Set<String> modes = new HashSet<>();
        modes.add("car");
        modes.add("cargobike");

        config.qsim().setMainModes(modes);
        config.qsim().setUsingTravelTimeCheckInTeleportation(true);
        config.qsim().setInflowCapacitySetting(
                org.matsim.core.config.groups.QSimConfigGroup.InflowCapacitySetting.INFLOW_FROM_FDIAG);

        config.travelTimeCalculator().setAnalyzedModes(modes);
        config.travelTimeCalculator().setSeparateModes(true);

        config.routing().setNetworkModes(modes);

        config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
        config.scoring().setFractionOfIterationsToStartScoreMSA(0.8);

        copyScoringMode(config, "car", "cargobike");

        if (simConfig.isDrtScenario()) {
            // Clipped DRT network + 100% population clipped to the service zone
            config.network().setInputFile(simConfig.getDrtNetworkClipped());
            config.plans().setInputFile(simConfig.getPassengerPlansClipped());
            // Compose native DRT params (full DVRP, service-area, DRT-only)
            hagrid.integrated.drt.DrtConfigComposer.composeConfig(
                    config, simConfig.getDrtServiceAreaShapefile(), simConfig.getDrtFleetFile());
        }
    }

    /**
     * Copies scoring parameters from one mode to another.
     *
     * @param config   the config object to modify
     * @param fromMode the source mode (e.g. "car")
     * @param toMode   the target mode (e.g. "cargobike")
     */
    private static void copyScoringMode(Config config, String fromMode, String toMode) {
        ModeParams from = config.scoring().getOrCreateModeParams(fromMode);
        ModeParams to = new ModeParams(toMode);

        to.setConstant(from.getConstant());
        to.setDailyMonetaryConstant(from.getDailyMonetaryConstant());
        to.setMarginalUtilityOfDistance(from.getMarginalUtilityOfDistance());
        to.setDailyUtilityConstant(from.getDailyUtilityConstant());
        to.setMonetaryDistanceRate(from.getMonetaryDistanceRate());

        config.scoring().addModeParams(to);
    }

    /**
     * Merges HAGRID delivery and supply carriers into a single carrier file.
     *
     * @param types     the vehicle types to apply
     * @param simConfig the simulation configuration with paths
     * @throws Exception if carrier files cannot be read or written
     */
    private static void mergeCarriers(CarrierVehicleTypes types, HAGRIDSimulationConfig simConfig) throws Exception {
        Instant start = Instant.now();
        // Fix legacy <attribute name="type">Mixed</attribute> in XMLs before reading
        // Not nice, but quick workaround until upstream MATSim issue is fixed

        LOGGER.info("Fixing mixed type attributes in XML files");
        XMLParcelTypeFixer.fixMixedTypeInFile(simConfig.getDeliveryCarrierPath().toString());
        XMLParcelTypeFixer.fixMixedTypeInFile(simConfig.getSupplyCarrierPath().toString());
        LOGGER.info("Finished fixing mixed type attributes in XML files");

        Carriers delivery = new Carriers();
        new CarrierPlanXmlReader(delivery, types).readFile(simConfig.getDeliveryCarrierPath().toString());
        fixServiceParcelTypeAttributes(delivery);
        LOGGER.info("Loaded {} delivery carriers", delivery.getCarriers().size());

        Carriers supply = new Carriers();
        new CarrierPlanXmlReader(supply, types).readFile(simConfig.getSupplyCarrierPath().toString());
        fixServiceParcelTypeAttributes(supply);
        LOGGER.info("Loaded {} supply carriers", supply.getCarriers().size());

        Carriers merged = new Carriers();
        delivery.getCarriers().values().forEach(merged::addCarrier);
        supply.getCarriers().values().forEach(merged::addCarrier);
        LOGGER.info("Merged carrier collection contains {} carriers", merged.getCarriers().size());

        // TODO: Temporary workaround: set routing parameters manually
        for (Carrier carrier : merged.getCarriers().values()) {
            CarriersUtils.setCarrierMode(carrier, "car");
            CarriersUtils.setJspritIterations(carrier, 40);
            CarriersUtils.setJspritComputationTime(carrier, 900.0);
        }
        // Quick Fix service parcel type attributes (e.g. "Mixed" -> "MIXED" -> Change of enum values)
        fixServiceParcelTypeAttributes(merged);

        // Write merged carriers to hagrid-output/{runId}/carriers/carrier_plans_combined.xml
        LOGGER.info("Writing merged carriers to {}", simConfig.getMergedCarrierPath());
        java.nio.file.Files.createDirectories(simConfig.getMergedCarrierPath().getParent());
        new CarrierPlanWriter(merged).write(simConfig.getMergedCarrierPath().toAbsolutePath().toString());
        Duration mergeDuration = Duration.between(start, Instant.now());
        LOGGER.info("Carrier merge completed in {} ms", mergeDuration.toMillis());
    }

    /**
     * Assigns freight zones to network links based on spatial intersection.
     *
     * @param scenario the scenario containing the network
     * @param features zone features with geometry and zone ID
     */
    private static void assignZones(Scenario scenario, Collection<SimpleFeature> features) {
        if (features == null || features.isEmpty()) {
            LOGGER.warn("No zone features provided. Skipping zone assignment.");
            return;
        }
        Instant start = Instant.now();
        int featureCount = features.size();
        int linkCount = scenario.getNetwork().getLinks().size();
        LOGGER.info("Assigning freight zones using {} zone feature(s) for {} network links", featureCount, linkCount);

        PreparedGeometryFactory prepFactory = new PreparedGeometryFactory();
        GeometryFactory geometryFactory = new GeometryFactory();
        STRtree spatialIndex = new STRtree(features.size());

        record ZoneGeometry(int zoneId, PreparedGeometry geometry) {}

        int indexedZones = 0;

        for (SimpleFeature feature : features) {
            Geometry geometry = (Geometry) feature.getAttribute(0);
            if (geometry == null || geometry.isEmpty()) {
                continue;
            }
            Number zoneNumber = (Number) feature.getAttribute("NO");
            if (zoneNumber == null) {
                continue;
            }
            long zoneIdLong = zoneNumber.longValue();
            if (zoneIdLong > Integer.MAX_VALUE) {
                LOGGER.warn("Zone id {} exceeds supported Integer range. Skipping feature {}", zoneIdLong,
                        feature.getID());
                continue;
            }
            int zoneId = (int) zoneIdLong;
            PreparedGeometry prepared = prepFactory.create(geometry);
            spatialIndex.insert(geometry.getEnvelopeInternal(), new ZoneGeometry(zoneId, prepared));
            indexedZones++;
        }

        spatialIndex.build();
        LOGGER.info("Spatial index prepared for {} unique zone geometries", indexedZones);

        int alreadyTagged = 0;
        int assignedCount = 0;
        for (Link link : scenario.getNetwork().getLinks().values()) {
            Object existingZone = link.getAttributes().getAttribute("zone");
            if (existingZone != null) {
                Integer normalized = normalizeZone(existingZone);
                if (normalized != null) {
                    link.getAttributes().putAttribute("zone", normalized);
                }
                alreadyTagged++;
                continue;
            }

            Coord[] coords = {
                    link.getCoord(),
                    link.getFromNode().getCoord(),
                    link.getToNode().getCoord()
            };

            boolean linkAssigned = false;
            for (Coord coord : coords) {
                if (coord == null) {
                    continue;
                }
                Point point = geometryFactory.createPoint(new org.locationtech.jts.geom.Coordinate(coord.getX(), coord.getY()));
                Envelope queryEnv = point.getEnvelopeInternal();
                @SuppressWarnings("unchecked")
                java.util.List<ZoneGeometry> candidates = spatialIndex.query(queryEnv);
                for (ZoneGeometry zoneGeometry : candidates) {
                    if (zoneGeometry.geometry().contains(point)) {
                        link.getAttributes().putAttribute("zone", zoneGeometry.zoneId());
                        assignedCount++;
                        linkAssigned = true;
                        break;
                    }
                }
                if (linkAssigned) {
                    break;
                }
            }
        }

        Duration elapsed = Duration.between(start, Instant.now());
        LOGGER.info("Zone assignment finished: {} links newly tagged, {} links already had zones ({} ms)",
                assignedCount, alreadyTagged, elapsed.toMillis());
    }

    private static Integer normalizeZone(Object value) {
        if (value instanceof Number number) {
            long longValue = number.longValue();
            if (longValue > Integer.MAX_VALUE) {
                LOGGER.warn("Zone id {} exceeds supported Integer range; ignoring.", longValue);
                return null;
            }
            return (int) longValue;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                long parsed = Long.parseLong(trimmed);
                if (parsed > Integer.MAX_VALUE) {
                    LOGGER.warn("Zone id {} exceeds supported Integer range; ignoring.", parsed);
                    return null;
                }
                return (int) parsed;
            } catch (NumberFormatException ex) {
                LOGGER.warn("Unable to parse zone attribute '{}' as integer", trimmed, ex);
                return null;
            }
        }
        return null;
    }

    /**
     * Logs summary statistics for all freight carriers in the scenario.
     *
     * @param scenario the initialized MATSim scenario
     */
    private static void logFreightInfos(Scenario scenario) {
        int servicesCep = 0, parcelsLastMileDelivery = 0;
        int servicesSupply = 0, parcelsSupply = 0;

        for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
            boolean isSupply = carrier.getId().toString().contains("supply");

            for (CarrierService service : carrier.getServices().values()) {
                if (isSupply) {
                    servicesSupply++;
                    parcelsSupply += service.getCapacityDemand();
                } else {
                    servicesCep++;
                    parcelsLastMileDelivery += service.getCapacityDemand();
                }
            }
        }

        LOGGER.info("Last Mile Delivery Services: {}, Parcels: {}", servicesCep, parcelsLastMileDelivery);
        LOGGER.info("Supply Services: {}, Parcels: {}", servicesSupply, parcelsSupply);
    }

    private static void fixServiceParcelTypeAttributes(Carriers carriers) {
        for (Carrier carrier : carriers.getCarriers().values()) {
            for (CarrierService service : carrier.getServices().values()) {
                Object attr = service.getAttributes().getAttribute("type");
                if (attr instanceof String && ((String) attr).equals("Mixed")) {
                    service.getAttributes().putAttribute("type", "MIXED");
                }
            }
        }
    }
}
