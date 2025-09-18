package hagrid.simulation;

import hagrid.utils.GeoUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
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
import org.matsim.core.utils.misc.Counter;
import org.matsim.freight.carriers.*;

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
        Config config = ConfigUtils.loadConfig(simConfig.getConfigPath().toString());
        setupConfig(config, simConfig);

        CarrierVehicleTypes types = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(types).readFile(simConfig.getVehicleTypePath().toString());

        mergeCarriers(types, simConfig);

        FreightCarriersConfigGroup freightConfig = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        freightConfig.setCarriersFile(simConfig.getMergedCarrierPath().toString());
        freightConfig.setCarriersVehicleTypesFile(simConfig.getVehicleTypePath().toString());

        Scenario scenario = ScenarioUtils.loadScenario(config);

        // Load and attach alternative networks for routing different modes
        NetworkConfigGroup netCfg = ConfigUtils.addOrGetModule(config, NetworkConfigGroup.class);
        Network carNet = org.matsim.core.network.NetworkUtils.createNetwork(netCfg);
        new MatsimNetworkReader(carNet).readFile(simConfig.getCarNetworkPath().toString());
        scenario.addScenarioElement("carNetwork", carNet);

        Network bikeNet = org.matsim.core.network.NetworkUtils.createNetwork(netCfg);
        new MatsimNetworkReader(bikeNet).readFile(simConfig.getBikeNetworkPath().toString());
        scenario.addScenarioElement("bikeNetwork", bikeNet);

        assignZones(scenario, zoneFeatures);

        CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);

        logFreightInfos(scenario);

        return scenario;
    }

    /**
     * Configures the MATSim config object using parameters from the simulation
     * config.
     *
     * @param config    the MATSim config to modify
     * @param simConfig the HAGRID simulation configuration
     */
    private static void setupConfig(Config config, HAGRIDSimulationConfig simConfig) {

        config.global().setRandomSeed(1337);

        config.network().setInputFile(simConfig.getCarNetworkPath().toString());
        config.network().setTimeVariantNetwork(true);
        config.network().setChangeEventsInputFile(simConfig.getNetworkChangeEventPath().toString());
        config.network().setInputCRS("EPSG:25832");

        config.controller().setOutputDirectory(simConfig.getOutputDirectory().toString());
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
    // Fix legacy <attribute name="type">Mixed</attribute> in XMLs before reading
    // Not nice, but quick workaround until upstream MATSim issue is fixed

    LOGGER.info("Fixing mixed type attributes in XML files");
    XMLParcelTypeFixer.fixMixedTypeInFile(simConfig.getDeliveryCarrierPath().toString());
    XMLParcelTypeFixer.fixMixedTypeInFile(simConfig.getSupplyCarrierPath().toString());
    LOGGER.info("Finished fixing mixed type attributes in XML files");

    Carriers delivery = new Carriers();
    new CarrierPlanXmlReader(delivery, types).readFile(simConfig.getDeliveryCarrierPath().toString());
    fixServiceParcelTypeAttributes(delivery);

    Carriers supply = new Carriers();
    new CarrierPlanXmlReader(supply, types).readFile(simConfig.getSupplyCarrierPath().toString());
    fixServiceParcelTypeAttributes(supply);

        Carriers merged = new Carriers();
        delivery.getCarriers().values().forEach(merged::addCarrier);
        supply.getCarriers().values().forEach(merged::addCarrier);

        // TODO: Temporary workaround: set routing parameters manually
        for (Carrier carrier : merged.getCarriers().values()) {
            CarriersUtils.setCarrierMode(carrier, "car");
            CarriersUtils.setJspritIterations(carrier, 40);
            CarriersUtils.setJspritComputationTime(carrier, 900.0);
        }
        // Quick Fix service parcel type attributes (e.g. "Mixed" -> "MIXED" -> Change of enum values)
        fixServiceParcelTypeAttributes(merged);

        // Write merged carriers to sim-input/carrier/<runId>_carrier_files/carrierPlans_total.xml
        String runId = simConfig.getRunId();
        String baseDir = System.getProperty("user.dir");
        java.nio.file.Path mergedOut = java.nio.file.Path.of(
            baseDir,
            "sim-input",
            "carrier",
            runId + "_carrier_files",
            "carrierPlans_total.xml"
        );
        LOGGER.info("Writing merged carriers to {}", mergedOut);
        // java.nio.file.Files.createDirectories(mergedOut.getParent());
        new CarrierPlanWriter(merged).write(mergedOut.toString());
    }

    /**
     * Assigns freight zones to network links based on spatial intersection.
     *
     * @param scenario the scenario containing the network
     * @param features zone features with geometry and zone ID
     */
    private static void assignZones(Scenario scenario, Collection<SimpleFeature> features) {
        Counter counter = new Counter("Zone Assignments");

        for (Link link : scenario.getNetwork().getLinks().values()) {
            if (link.getAttributes().getAttribute("zone") != null) {
                continue;
            }

            Coord[] coords = {
                    link.getCoord(),
                    link.getFromNode().getCoord(),
                    link.getToNode().getCoord()
            };

            for (Coord coord : coords) {
                for (SimpleFeature feat : features) {
                    Geometry geo = (Geometry) feat.getAttribute(0);
                    if (GeoUtils.isCoordIntersectingShape(geo, coord)) {
                        link.getAttributes().putAttribute("zone", (int) feat.getAttribute("NO"));
                        counter.incCounter();
                        break;
                    }
                }
            }
        }
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
