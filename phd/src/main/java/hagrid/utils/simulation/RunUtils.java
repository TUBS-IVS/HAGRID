package hagrid.utils.simulation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;

import org.matsim.core.config.*;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.network.*;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.utils.misc.Counter;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlanWriter;
import org.matsim.freight.carriers.CarrierPlanXmlReader;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.vehicles.*;

import hagrid.utils.GeoUtils;

import java.util.*;

/**
 * Utility class containing helper methods for setting up and running the freight simulation.
 */
public class RunUtils {

    // Logger for logging important information and debug data
    private static final Logger log = LogManager.getLogger(RunUtils.class);

    /**
     * Sets the configuration parameters specific to the scenario.
     *
     * @param config          MATSim configuration object
     * @param scenarioName    Name of the scenario
     * @param networkCarPath  Path to the car network file
     * @param baseDir         Base directory for output files
     */
    public static void setConfigParameters(Config config, String scenarioName, String networkCarPath, String baseDir) {
        // Set network input file and parameters
        config.network().setInputFile(networkCarPath);
        config.network().setInputCRS("EPSG:25832");
        config.network().setTimeVariantNetwork(true);

        // Define analyzed modes for travel time calculation
        Set<String> analyzedModes = new HashSet<>(Arrays.asList("car", "cargobike"));
        config.travelTimeCalculator().setAnalyzedModes(analyzedModes);
        config.travelTimeCalculator().setSeparateModes(true);

        // Controler settings
        config.controller().setLastIteration(50);
        config.controller().setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles);
        config.controller().setOutputDirectory(baseDir + "/" + scenarioName + "/02_Simulated/");
        config.global().setCoordinateSystem("EPSG:25832");

        // Define main modes for the simulation
        Set<String> mainModes = new HashSet<>(config.qsim().getMainModes());
        mainModes.add("cargobike");
        config.qsim().setMainModes(mainModes);

        // Configure scoring parameters for "cargobike" based on "car" parameters
        ModeParams carParams = config.scoring().getOrCreateModeParams("car");
        ModeParams cargoBikeParams = config.scoring().getOrCreateModeParams("cargobike");


        cargoBikeParams.setConstant(carParams.getConstant());
        cargoBikeParams.setDailyMonetaryConstant(carParams.getDailyMonetaryConstant());
        cargoBikeParams.setMarginalUtilityOfDistance(carParams.getMarginalUtilityOfDistance());
        cargoBikeParams.setDailyUtilityConstant(carParams.getDailyUtilityConstant());
        cargoBikeParams.setMonetaryDistanceRate(carParams.getMonetaryDistanceRate());
        config.scoring().addModeParams(cargoBikeParams);

        // Set network modes for route calculation
        config.routing().setNetworkModes(mainModes);
    }

    /**
     * Adds custom vehicle types to the simulation.
     *
     * @param vehicleTypes Carrier vehicle types collection
     */
    public static void addCustomVehicleTypes(CarrierVehicleTypes vehicleTypes) {
        // Add cargo bike vehicle type
        VehicleType cargoBikeType = createVehicleType(
                "ct_cep_bike", "cargobike", 23, 137.39, 0, 0.00000001, 15 / 3.6, 0.5);
        vehicleTypes.getVehicleTypes().put(cargoBikeType.getId(), cargoBikeType);

        // Add e-grocery van vehicle type
        VehicleType eGroceryVanType = createVehicleType(
                "ct_egrocery_van", "car", 18, 189.59, 0.123 / 1000, 0.00000001, 0.0, 1.5);
        vehicleTypes.getVehicleTypes().put(eGroceryVanType.getId(), eGroceryVanType);

        // Add e-grocery bike vehicle type
        VehicleType eGroceryBikeType = createVehicleType(
                "ct_egrocery_bike", "cargobike", 5, 137.39, 0, 0.00000001, 15 / 3.6, 0.5);
        vehicleTypes.getVehicleTypes().put(eGroceryBikeType.getId(), eGroceryBikeType);
    }

    /**
     * Creates a vehicle type with specified parameters.
     *
     * @param id            Vehicle type ID
     * @param mode          Network mode (e.g., "car", "cargobike")
     * @param capacity      Vehicle capacity
     * @param fixedCost     Fixed cost of using the vehicle
     * @param distanceCost  Cost per meter traveled
     * @param timeCost      Cost per second
     * @param maxVelocity   Maximum velocity (m/s)
     * @param pcu           Passenger car unit equivalent
     * @return Configured vehicle type
     */
    public static VehicleType createVehicleType(String id, String mode, int capacity, double fixedCost,
                                                double distanceCost, double timeCost, double maxVelocity, double pcu) {
        VehicleType type = VehicleUtils.getFactory().createVehicleType(Id.create(id, VehicleType.class));
        type.setNetworkMode(mode);
        type.getCapacity().setOther(capacity);
        type.getCostInformation().setFixedCost(fixedCost);
        type.getCostInformation().setCostsPerMeter(distanceCost);
        type.getCostInformation().setCostsPerSecond(timeCost);
        VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(), 0.001);
        VehicleUtils.setCostsPerSecondInService(type.getCostInformation(), 0.0);
        type.setMaximumVelocity(maxVelocity);
        type.setPcuEquivalents(pcu);
        return type;
    }

    /**
     * Loads the networks for different modes and adds them to the scenario.
     *
     * @param scenario        MATSim scenario object
     * @param networkBikePath Path to the bike network file
     */
    public static void loadNetworks(Scenario scenario, String networkBikePath) {
        // Load bike network
        Network bikeNetwork = NetworkUtils.createNetwork();
        new MatsimNetworkReader(bikeNetwork).readFile(networkBikePath);
        scenario.addScenarioElement("bikeNetwork", bikeNetwork);

        // Load car network
        Network carNetwork = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(scenario.getNetwork()).filter(carNetwork, Collections.singleton("car"));
        scenario.addScenarioElement("carNetwork", carNetwork);
    }

    /**
     * Merges carrier plans from different sources and writes the combined plan to a file.
     *
     * @param freightConfigGroup Freight configuration group
     * @param scenarioName       Name of the scenario
     * @param baseDir            Base directory for input and output files
     * @param vehicleTypes       Carrier vehicle types
     * @throws Exception If an error occurs during carrier plan merging
     */
    public static void mergeCarrierPlans(FreightCarriersConfigGroup freightConfigGroup, String scenarioName,
                                         String baseDir, CarrierVehicleTypes vehicleTypes) throws Exception {
        // Load CEP carrier plans
        Carriers carriersCep = new Carriers();
        String cepFilePath = String.format("%s/%s/01_Routed/%s_carrierplans_cep_routed.xml",
                baseDir, scenarioName, scenarioName);
        new CarrierPlanXmlReader(carriersCep, vehicleTypes).readFile(cepFilePath);
        if (carriersCep.getCarriers().isEmpty()) {
            throw new Exception("No CEP carriers found in the input file.");
        }

        // Load supply carrier plans
        Carriers carriersSupply = new Carriers();
        String supplyFilePath = String.format("%s/%s/01_Routed/%s_carrierplans_supply_routed.xml",
                baseDir, scenarioName, scenarioName);
        new CarrierPlanXmlReader(carriersSupply, vehicleTypes).readFile(supplyFilePath);
        if (carriersSupply.getCarriers().isEmpty()) {
            throw new Exception("No supply carriers found in the input file.");
        }

        // Merge carriers
        Carriers mergedCarriers = new Carriers();
        carriersCep.getCarriers().values().forEach(mergedCarriers::addCarrier);
        carriersSupply.getCarriers().values().forEach(mergedCarriers::addCarrier);

        // Write merged carrier plans to file
        String outputFilePath = String.format("%s/%s/02_Simulated/carrierPlans_%s_total.xml",
                baseDir, scenarioName, scenarioName);
        new CarrierPlanWriter(mergedCarriers).write(outputFilePath);
        freightConfigGroup.setCarriersFile(outputFilePath);
    }

    /**
     * Logs information about the loaded freight carriers for analysis.
     *
     * @param scenario MATSim scenario object
     */
    public static void logFreightInformation(Scenario scenario) {
        int servicesCep = 0;
        int parcelsCep = 0;
        int servicesSupply = 0;
        int parcelsSupply = 0;

        for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
            if (carrier.getId().toString().contains("supply")) {
                servicesSupply += carrier.getServices().size();
                parcelsSupply += carrier.getServices().values().stream()
                        .mapToInt(CarrierService::getCapacityDemand).sum();
            } else {
                servicesCep += carrier.getServices().size();
                parcelsCep += carrier.getServices().values().stream()
                        .mapToInt(CarrierService::getCapacityDemand).sum();
            }
        }

        log.info("Number of CEP Services loaded: {}", servicesCep);
        log.info("Number of CEP Parcels loaded: {}", parcelsCep);
        log.info("Number of Supply Services loaded: {}", servicesSupply);
        log.info("Number of Supply Parcels loaded: {}", parcelsSupply);
    }

    /**
     * Assigns freight zones to network links based on their geographical location.
     *
     * @param scenario            MATSim scenario object
     * @param freightZoneFeatures Collection of freight zone features
     * @param configDir           Directory for configuration files
     */
    public static void assignZonesToNetworkLinks(Scenario scenario, Collection<SimpleFeature> freightZoneFeatures,
                                                 String configDir) {
        Counter counter = new Counter("Links assigned to zones: ");

        for (Link link : scenario.getNetwork().getLinks().values()) {
            if (link.getAttributes().getAttribute("zone") != null) {
                continue;  // Skip if zone is already assigned
            }

            // Check if link coordinates intersect with any freight zone
            if (assignZoneToLink(link, link.getCoord(), freightZoneFeatures, counter)) continue;
            if (assignZoneToLink(link, link.getToNode().getCoord(), freightZoneFeatures, counter)) continue;
            assignZoneToLink(link, link.getFromNode().getCoord(), freightZoneFeatures, counter);
        }

        // Write the updated network with zones assigned
        String outputNetworkPath = configDir + "/car_network_zones.xml.gz";
        new NetworkWriter(scenario.getNetwork()).write(outputNetworkPath);
    }

    /**
     * Assigns a zone to a link if the coordinate intersects with a freight zone.
     *
     * @param link                Network link
     * @param coord               Coordinate to check
     * @param freightZoneFeatures Collection of freight zone features
     * @param counter             Counter for logging
     * @return True if a zone was assigned, false otherwise
     */
    private static boolean assignZoneToLink(Link link, Coord coord, Collection<SimpleFeature> freightZoneFeatures, Counter counter) {
        for (SimpleFeature feature : freightZoneFeatures) {
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            if (GeoUtils.isCoordIntersectingShape(geometry, coord)) {
                int zoneNumber = (int) feature.getAttribute("NO");
                link.getAttributes().putAttribute("zone", zoneNumber);
                counter.incCounter();
                return true;
            }
        }
        return false;
    }
}

