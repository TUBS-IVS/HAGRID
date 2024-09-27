package hagrid;

import org.apache.logging.log4j.core.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.core.utils.misc.Counter;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controler.CarrierModule;
import hagrid.simulation.HAGRIDSimulationModule;
import hagrid.utils.GeoUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class RunHAGRIDSimulation {

    private static final Logger log = (Logger) org.apache.logging.log4j.LogManager.getLogger(RunHAGRIDSimulation.class);
    private static final String INPUT_DIR = "C:/Users/bienzeisler/HAGRID/HAGRID/phd/sim-input/";
    private static final String OUTPUT_DIR = "phd/sim-output/";
    private static final String NETWORK_XML_PATH = "network/car_network_filtered.xml.gz";
    private static final String FREIGHT_ZONE_PATH = "network/RH_useful__zone.shp";
    private static final String DELIVERY_CARRIERS_FILE = INPUT_DIR + "carrier/delivery_carriers_routed.xml";
    private static final String SUPPLY_CARRIERS_FILE = INPUT_DIR + "carrier/supply_carriers_routed.xml";

    private static CarrierVehicleTypes types;

    public static void main(String[] args) throws Exception {
        log.info("Start freight simulation.");

        Collection<SimpleFeature> freightZoneFeatures = GeoFileReader.getAllFeatures(INPUT_DIR + FREIGHT_ZONE_PATH);

        runSimulation(freightZoneFeatures);
    }

    private static void runSimulation(Collection<SimpleFeature> freightZoneFeatures) throws Exception {
        log.info("Running Base Case Simulation");

        // Config config = new Config();
        // config.addCoreModules();
        Config config = ConfigUtils.loadConfig(INPUT_DIR + "sim-config.xml");

        RoutingConfigGroup routeConfigGroup = config.routing();

        config.controller().setLinkToLinkRoutingEnabled(false);

        setConfigParameters(config);

        types = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(types).readFile(INPUT_DIR + "carrier/HAGRID_vehicleTypes2.0.xml");
        // // addGroceryVehicleTypes(types); // This line is commented out because we
        // are assuming the vehicle types are already set.
        // new CarrierVehicleTypeWriter(types).write(INPUT_DIR +
        // "/vehicleTypesBasecase.xml");

        String outputDir = OUTPUT_DIR + "basecase/";
        config.controller().setOutputDirectory(outputDir);
        config.global().setCoordinateSystem("EPSG:25832");

        // Set<String> modes = new HashSet<>(config.qsim().getMainModes());
        // modes.add("cargobike");
        // config.qsim().setMainModes(modes);

        // ScoringConfigGroup.ModeParams carParams =
        // config.scoring().getOrCreateModeParams("car");
        // ScoringConfigGroup.ModeParams cargoBikeParams = new
        // ScoringConfigGroup.ModeParams("cargobike");
        // cargoBikeParams.setConstant(carParams.getConstant());
        // cargoBikeParams.setDailyMonetaryConstant(carParams.getDailyMonetaryConstant());
        // cargoBikeParams.setMarginalUtilityOfDistance(carParams.getMarginalUtilityOfDistance());
        // cargoBikeParams.setDailyUtilityConstant(carParams.getDailyUtilityConstant());
        // cargoBikeParams.setMonetaryDistanceRate(carParams.getMonetaryDistanceRate());
        // config.scoring().addModeParams(cargoBikeParams);

        // config.routing().setNetworkModes(modes);

        Scenario scenario = ScenarioUtils.loadScenario(config);
        Set<String> carMode = new HashSet<>();
        carMode.add("car");

        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(INPUT_DIR + NETWORK_XML_PATH);
        scenario.addScenarioElement("network", network);

        addZonesToNetwork(scenario, freightZoneFeatures);

        FreightCarriersConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule(config,
                FreightCarriersConfigGroup.class);
        freightConfigGroup.setTimeWindowHandling(FreightCarriersConfigGroup.TimeWindowHandling.enforceBeginnings);
        freightConfigGroup.setTravelTimeSliceWidth(1800);
        freightConfigGroup.setCarriersVehicleTypesFile(INPUT_DIR + "carrier/HAGRID_vehicleTypes2.0.xml");

        freightConfigGroup.setCarriersFile(INPUT_DIR + "carrier/carrierPlans_total.xml");

        mergeCarriers(scenario);

        CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);

        Controler controller = new Controler(scenario);
        controller.addOverridingModule(new CarrierModule());
        controller.addOverridingModule(new HAGRIDSimulationModule(scenario, true));

        logFreightInfos(scenario);

        routeConfigGroup.printModeRoutingParams();

        controller.run();
    }

    private static void setConfigParameters(Config config) {
        config.network().setInputFile(NETWORK_XML_PATH);
        config.network().setInputCRS("EPSG:25832");
        config.network().setTimeVariantNetwork(true);

        Set<String> analyzedModes = new HashSet<>();
        analyzedModes.add("car");
        // analyzedModes.add("cargobike");
        config.travelTimeCalculator().setAnalyzedModes(analyzedModes);
        config.travelTimeCalculator().setSeparateModes(true);

        config.controller().setLastIteration(150);
        config.controller().setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles);

        config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
        config.scoring().setFractionOfIterationsToStartScoreMSA(0.8);

        config.qsim().setUsingTravelTimeCheckInTeleportation(true);
    }

    // The vehicle type creation methods are commented out because we assume the
    // vehicle types are already defined in the file.
    /*
     * private static void addGroceryVehicleTypes(CarrierVehicleTypes vehicleTypes)
     * {
     * VehicleType eGroceryVehicleType = createEgroceryVehicleType();
     * vehicleTypes.getVehicleTypes().put(eGroceryVehicleType.getId(),
     * eGroceryVehicleType);
     * 
     * VehicleType eGroceryCargoBikeType = createEgroceryCargobikeType();
     * vehicleTypes.getVehicleTypes().put(eGroceryCargoBikeType.getId(),
     * eGroceryCargoBikeType);
     * 
     * VehicleType cepCargoBikeType = createCargobikeType();
     * vehicleTypes.getVehicleTypes().put(cepCargoBikeType.getId(),
     * cepCargoBikeType);
     * }
     * 
     * private static VehicleType createCargobikeType() {
     * VehicleType typeBuilder = VehicleUtils.getFactory()
     * .createVehicleType(Id.create("ct_cep_bike", VehicleType.class));
     * typeBuilder.setNetworkMode("cargobike");
     * typeBuilder.getCapacity().setOther(23);
     * typeBuilder.getCostInformation().setFixedCost(137.39);
     * typeBuilder.getCostInformation().setCostsPerMeter(0);
     * typeBuilder.getCostInformation().setCostsPerSecond(0.00000001);
     * VehicleUtils.setCostsPerSecondWaiting(typeBuilder.getCostInformation(),
     * 0.001);
     * VehicleUtils.setCostsPerSecondInService(typeBuilder.getCostInformation(), 0);
     * typeBuilder.setMaximumVelocity(15 / 3.6);
     * typeBuilder.setPcuEquivalents(0.5);
     * 
     * return typeBuilder;
     * }
     * 
     * private static VehicleType createEgroceryVehicleType() {
     * VehicleType typeBuilder = VehicleUtils.getFactory()
     * .createVehicleType(Id.create("ct_egrocery_van", VehicleType.class));
     * typeBuilder.getCapacity().setOther(18);
     * typeBuilder.getCostInformation().setFixedCost(189.59);
     * typeBuilder.getCostInformation().setCostsPerMeter(0.123 / 1000);
     * typeBuilder.getCostInformation().setCostsPerSecond(0.00000001);
     * typeBuilder.setPcuEquivalents(1.5);
     * typeBuilder.setFlowEfficiencyFactor(0.9);
     * VehicleUtils.setCostsPerSecondWaiting(typeBuilder.getCostInformation(),
     * 0.001);
     * VehicleUtils.setCostsPerSecondInService(typeBuilder.getCostInformation(), 0);
     * 
     * return typeBuilder;
     * }
     * 
     * private static VehicleType createEgroceryCargobikeType() {
     * VehicleType typeBuilder = VehicleUtils.getFactory()
     * .createVehicleType(Id.create("ct_egrocery_bike", VehicleType.class));
     * typeBuilder.setNetworkMode("cargobike");
     * typeBuilder.getCapacity().setOther(5);
     * typeBuilder.getCostInformation().setFixedCost(137.39);
     * typeBuilder.getCostInformation().setCostsPerMeter(0);
     * typeBuilder.getCostInformation().setCostsPerSecond(0.00000001);
     * VehicleUtils.setCostsPerSecondWaiting(typeBuilder.getCostInformation(),
     * 0.001);
     * VehicleUtils.setCostsPerSecondInService(typeBuilder.getCostInformation(), 0);
     * typeBuilder.setMaximumVelocity(15 / 3.6);
     * typeBuilder.setPcuEquivalents(0.5);
     * 
     * return typeBuilder;
     * }
     */

    private static void mergeCarriers(Scenario scenario) throws Exception {
        Carriers carriersDelivery = new Carriers();
        new CarrierPlanXmlReader(carriersDelivery, types).readFile(DELIVERY_CARRIERS_FILE);

        Carriers carriersSupply = new Carriers();
        new CarrierPlanXmlReader(carriersSupply, types).readFile(SUPPLY_CARRIERS_FILE);

        Carriers carriersTotal = new Carriers();
        carriersDelivery.getCarriers().values().forEach(carriersTotal::addCarrier);
        carriersSupply.getCarriers().values().forEach(carriersTotal::addCarrier);

        new CarrierPlanWriter(carriersTotal).write(INPUT_DIR + "carrier/carrierPlans_total.xml");
    }

    private static void logFreightInfos(Scenario scenario) {
        int servicesCep = 0;
        int parcelsCep = 0;
        int servicesSupply = 0;
        int parcelsSupply = 0;

        for (Carrier c : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
            if (c.getId().toString().contains("supply")) {
                servicesSupply += c.getServices().size();
                for (CarrierService s : c.getServices().values()) {
                    parcelsSupply += s.getCapacityDemand();
                }
            } else {
                servicesCep += c.getServices().size();
                for (CarrierService s : c.getServices().values()) {
                    parcelsCep += s.getCapacityDemand();
                }
            }
        }

        log.info("Number of loaded Services: " + servicesCep);
        log.info("Number of loaded Parcels: " + parcelsCep);
        log.info("Number of loaded Supply Services: " + servicesSupply);
        log.info("Number of loaded Supply Deliveries: " + parcelsSupply);
    }

    private static void addZonesToNetwork(Scenario scenario, Collection<SimpleFeature> freightZoneFeatures) {
        Counter counter = new Counter("#Added Zones to Links ");

        for (Link link : scenario.getNetwork().getLinks().values()) {
            if (link.getAttributes().getAttribute("zone") != null)
                continue;

            Coord linkCoord = link.getCoord();

            for (SimpleFeature feat : freightZoneFeatures) {
                Geometry geo = (Geometry) feat.getAttribute(0);
                if (GeoUtils.isCoordIntersectingShape(geo, linkCoord)) {
                    int no = (int) feat.getAttribute("NO");
                    link.getAttributes().putAttribute("zone", no);
                    counter.incCounter();
                    break;
                }
                linkCoord = link.getToNode().getCoord();
                if (GeoUtils.isCoordIntersectingShape(geo, linkCoord)) {
                    int no = (int) feat.getAttribute("NO");
                    link.getAttributes().putAttribute("zone", no);
                    counter.incCounter();
                    break;
                }
                linkCoord = link.getFromNode().getCoord();
                if (GeoUtils.isCoordIntersectingShape(geo, linkCoord)) {
                    int no = (int) feat.getAttribute("NO");
                    link.getAttributes().putAttribute("zone", no);
                    counter.incCounter();
                    break;
                }
            }
        }
        String outputDir = OUTPUT_DIR;
        new NetworkWriter(scenario.getNetwork()).write(outputDir + "car_network_zones.xml.gz");
    }
}
