package hagrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.matsim.api.core.v01.*;
import org.matsim.core.config.*;
import org.matsim.core.controler.*;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.freight.carriers.CarrierVehicleTypeReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.freight.carriers.controler.CarrierModule;
import hagrid.utils.simulation.RunUtils;

import java.util.*;

/**
 * This class sets up and runs the freight simulation using MATSim.
 * It loads configurations, networks, vehicle types, and carrier plans,
 * then initializes and runs the simulation.
 */
public class RunHAGRIDSimulation {

    // Logger for logging important information and debug data
    private static final Logger log = LogManager.getLogger(RunHAGRIDSimulation.class);

    // Base directory and file paths
    private static final String BASE_DIR = "phd/input/";

    private static final String CONFIG_DIR = BASE_DIR + "/sim-input";
    private static final String CONFIG_FILE = CONFIG_DIR + "/config.xml";
    private static final String FREIGHT_ZONE_PATH = CONFIG_DIR + "/RH_useful__zone.SHP";
    private static final String NETWORK_CAR_PATH = CONFIG_DIR + "/car_cargobike_network_zones_MH_V3.xml.gz";
    private static final String NETWORK_BIKE_PATH = CONFIG_DIR + "/cargobike_network_zones_MH_V3_clean.xml.gz";
    private static final String VEHICLE_TYPES_FILE = BASE_DIR + "/HAGRID_vehicleTypes2.0.xml";

    // Carrier vehicle types
    private static CarrierVehicleTypes vehicleTypes;

    /**
     * Main entry point for running the freight simulation.
     *
     * @param args Command-line arguments (not used)
     * @throws Exception If an error occurs during simulation setup or execution
     */
    public static void main(String[] args) throws Exception {
        log.info("Starting freight simulation.");

        // Read freight zone features from the shape file
        Collection<SimpleFeature> freightZoneFeatures = GeoFileReader.getAllFeatures(FREIGHT_ZONE_PATH);
        runSimulation("TestScnario", freightZoneFeatures);
        
    }

    /**
     * Runs the simulation for a given scenario.
     *
     * @param scenarioName        Name of the scenario
     * @param freightZoneFeatures Collection of freight zone features
     * @throws Exception If an error occurs during simulation setup or execution
     */
    private static void runSimulation(String scenarioName, Collection<SimpleFeature> freightZoneFeatures) throws Exception {
        log.info("Running simulation for scenario: {}", scenarioName);

        // Load the MATSim configuration file
        Config config = ConfigUtils.loadConfig(CONFIG_FILE);
        RunUtils.setConfigParameters(config, scenarioName, NETWORK_CAR_PATH, BASE_DIR);

        // Load and configure vehicle types
        vehicleTypes = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(vehicleTypes).readFile(VEHICLE_TYPES_FILE);
        RunUtils.addCustomVehicleTypes(vehicleTypes);

        // Create and configure the scenario
        Scenario scenario = ScenarioUtils.loadScenario(config);
        RunUtils.loadNetworks(scenario, NETWORK_BIKE_PATH);

        // Clean the network to ensure connectivity
        new NetworkCleaner().run(scenario.getNetwork());

        // Assign freight zones to network links
        RunUtils.assignZonesToNetworkLinks(scenario, freightZoneFeatures, CONFIG_DIR);

        // Configure freight settings
        FreightCarriersConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        freightConfigGroup.setCarriersVehicleTypesFile(VEHICLE_TYPES_FILE);

        // Merge carrier plans and load carriers
        RunUtils.mergeCarrierPlans(freightConfigGroup, scenarioName, BASE_DIR, vehicleTypes);
        CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);

        // Set up the MATSim controler with freight modules
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new CarrierModule());
        // controler.addOverridingModule(new FreightModule(scenario, true));

        // Log freight information for debugging and analysis
        RunUtils.logFreightInformation(scenario);

        // Execute the simulation
        // controler.run();
    }
}
