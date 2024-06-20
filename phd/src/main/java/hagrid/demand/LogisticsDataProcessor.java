package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.matsim.freight.carriers.CarrierVehicleTypeReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;

import hagrid.HagridConfigGroup;
import hagrid.utils.GeoUtils;
import hagrid.utils.demand.Hub;
import hagrid.utils.general.Region;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * The LogisticsDataProcessor class is responsible for reading logistics data
 * from various files and storing it in the MATSim scenario.
 */
@Singleton
public class LogisticsDataProcessor implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(LogisticsDataProcessor.class);

    private static final String CARRIER_VEHICLE_TYPES = "carrierVehicleTypes";
    private static final String geoDataPath = "phd/input/geodata/Region Hannover.shp";

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfigGroup hagridConfig;

    /**
     * Runs the logistics data processing, reading data from specified files
     * and storing it in the scenario.
     */
    @Override
    public void run() {
        try {
            LOGGER.info("Reading logistics data...");

            // Read shipping point data from files in the folder
            LOGGER.info("Reading geodata of Hanover Region from folder: {}", geoDataPath);
            Collection<SimpleFeature> hanoverGeoData = GeoFileReader.getAllFeatures(geoDataPath);

            // Read hub data from file
            // Filter out all Hubs that are not located in the Hanover region, e.g. Nienburg
            LOGGER.info("Reading hub data from file: {}", hagridConfig.getHubDataPath());
            Map<Id<Hub>, Hub> hubList = readDataFromFile(hagridConfig.getHubDataPath(), DataType.HUB);
            hubList =  GeoUtils.filterHubsByRegions(hubList, hanoverGeoData,
                            EnumSet.complementOf(EnumSet.of(Region.ALL)));

            // Read parcel locker data from file
            LOGGER.info("Reading parcel locker data from file: {}", hagridConfig.getParcelLockerDataPath());
            Map<Id<Hub>, Hub> parcelLockerList = readDataFromFile(hagridConfig.getParcelLockerDataPath(),
                    DataType.PARCEL_LOCKER);

            // Read shipping point data from files in the folder
            LOGGER.info("Reading shipping point data from folder: {}", hagridConfig.getShippingPointDataPath());
            Map<Id<Hub>, Hub> shippingPointList = new HashMap<>();
            for (String provider : hagridConfig.getLocationProviders()) {
                shippingPointList.putAll(
                        readDataFromFile(hagridConfig.getShippingPointDataPath() + provider + "_paketnet_list.csv",
                                DataType.SHIPPING_POINT));
            }

            // Store data in scenario
            // Filter out all parcel lockers and shipping points that are not located in the in the config defined regions
            scenario.addScenarioElement("hubList", hubList);
            scenario.addScenarioElement("parcelLockerList",
                    GeoUtils.filterHubsByRegions(parcelLockerList, hanoverGeoData,
                            hagridConfig.getFilterRegions()));
            scenario.addScenarioElement("shippingPointList",
                    GeoUtils.filterHubsByRegions(shippingPointList, hanoverGeoData,
                            hagridConfig.getFilterRegions()));
            scenario.addScenarioElement("hanoverGeoData", hanoverGeoData);

            LOGGER.info("Loading carrier vehicle types...");
            CarrierVehicleTypes vehicleTypes = new CarrierVehicleTypes();
            new CarrierVehicleTypeReader(vehicleTypes).readFile(hagridConfig.getVehicleTypePath());
            scenario.addScenarioElement(CARRIER_VEHICLE_TYPES, vehicleTypes);

            LOGGER.info("Logistics data processing completed.");
        } catch (Exception e) {
            LOGGER.error("Error reading logistics data", e);
        }
    }

    /**
     * Reads logistics data from a file and returns it as a map of Hub objects.
     *
     * @param filename The path to the file.
     * @param dataType The type of data being read (HUB, SHIPPING_POINT, or
     *                 PARCEL_LOCKER).
     * @return A map of Hub objects.
     * @throws Exception If an error occurs while reading the file.
     */
    private Map<Id<Hub>, Hub> readDataFromFile(String filename, DataType dataType) throws Exception {
        Map<Id<Hub>, Hub> hubList = new HashMap<>();
        LOGGER.info("Reading data from file: {}", filename);

        File file = new File(filename);
        try (Scanner reader = new Scanner(file, StandardCharsets.UTF_8.name())) {
            reader.nextLine(); // Skip header line

            while (reader.hasNextLine()) {
                String data = reader.nextLine();
                String[] dataSplit = data.split(getDelimiter(dataType));

                Hub hub = parseHubData(dataSplit, dataType, filename);
                if (hub != null) {
                    hubList.put(hub.getId(), hub);
                }
            }
        }

        LOGGER.info("Read {} entries from file: {}", hubList.size(), filename);
        return hubList;
    }

    /**
     * Parses a line of data and returns a Hub object.
     *
     * @param dataSplit The line of data split into fields.
     * @param dataType  The type of data being parsed (HUB, SHIPPING_POINT, or
     *                  PARCEL_LOCKER).
     * @param source    The source file name.
     * @return A Hub object.
     */
    private Hub parseHubData(String[] dataSplit, DataType dataType, String source) {
        String company;
        Double x, y;
        Coord hubCoord;
        Id<Hub> id;
        Id<Link> linkId;
        Hub hub = null; // Initialize to null to handle cases where no hub is created

        switch (dataType) {
            case HUB:
                // Parsing hub data
                company = dataSplit[2].toLowerCase();
                if (hagridConfig.isWhiteLabel()) {
                    company = "wl";
                }
                String idAsString = dataSplit[2] + "_" + dataSplit[7];

                id = Id.create(cleanId(idAsString), Hub.class);
                x = Double.valueOf(dataSplit[0]);
                y = Double.valueOf(dataSplit[1]);

                hubCoord = GeoUtils.transformIfNeeded(new Coord(x, y));

                linkId = NetworkUtils.getNearestLinkExactly(scenario.getNetwork(), hubCoord).getId();
                hub = new Hub(id, company, hubCoord);
                hub.setType(dataSplit[4]);
                hub.setLink(linkId);
                hub.getAttributes().putAttribute("Street", dataSplit[5]);
                hub.getAttributes().putAttribute("ZIP Code", Integer.valueOf(dataSplit[6]));
                hub.getAttributes().putAttribute("Location", dataSplit[7]);

                if (hagridConfig.isWhiteLabel()) {
                    if ((idAsString.contains("dp")) || (idAsString.contains("dummy"))) {
                        hub.setCapacityLimit(hagridConfig.getHubLimitPost());
                    } else if (company.contains("dhl") || company.contains("wl")) {
                        hub.setCapacityLimit(hagridConfig.getHubLimitDHL());
                    }
                }
                break;

            case SHIPPING_POINT:
                // Parsing shipping point data
                id = Id.create(cleanId(dataSplit[0]), Hub.class);
                x = Double.valueOf(dataSplit[1]);
                y = Double.valueOf(dataSplit[2]);

                String fileName = source.substring(source.lastIndexOf('/') + 1);
                company = fileName.substring(0, fileName.indexOf('_')).toLowerCase();

                hubCoord = GeoUtils.transformIfNeeded(new Coord(x, y));
                linkId = NetworkUtils.getNearestLinkExactly(scenario.getNetwork(), hubCoord).getId();
                hub = new Hub(id, company, hubCoord);
                hub.setLink(linkId);
                break;

            case PARCEL_LOCKER:
                // Parsing parcel locker data
                if (dataSplit[6].contains("PACKSTATION")) {
                    id = Id.create(cleanId(dataSplit[7]), Hub.class);
                    x = Double.valueOf(dataSplit[9]);
                    y = Double.valueOf(dataSplit[8]);
                    company = "dhl";

                    hubCoord = GeoUtils.transformIfNeeded(new Coord(x, y));
                    linkId = NetworkUtils.getNearestLinkExactly(scenario.getNetwork(), hubCoord).getId();
                    hub = new Hub(id, company, hubCoord);
                    hub.setLink(linkId);
                    hub.setAddress(dataSplit[3] + dataSplit[4]);
                    hub.setType(dataSplit[6]);
                    hub.getAttributes().putAttribute("plz", Integer.valueOf(dataSplit[0]));
                    hub.getAttributes().putAttribute("city", dataSplit[1]);
                    hub.getAttributes().putAttribute("district", dataSplit[2]);
                }
                break;

            default:
                throw new IllegalArgumentException("Unknown DataType: " + dataType);
        }

        return hub;
    }

    /**
     * Cleans a string ID by replacing special characters and umlauts.
     *
     * @param id The original ID string.
     * @return The cleaned ID string.
     */
    private static String cleanId(String id) {
        return replaceUmlaute(
                id.toLowerCase()
                        .replace("ß", "ss")
                        .replace("'", "")
                        .replace(" ", "_")
                        .replace("&", "")
                        .replace("\"", "")
                        .replace("__", "_")
                        .toLowerCase());
    }

    /**
     * Replaces German umlauts in a string with their respective non-umlaut
     * versions.
     *
     * @param input The input string containing umlauts.
     * @return The string with umlauts replaced.
     */
    private static String replaceUmlaute(String input) {
        return input.replace("\u00fc", "ue")
                .replace("\u00f6", "oe")
                .replace("\u00e4", "ae")
                .replace("\u00df", "ss")
                .replaceAll("\u00dc(?=[a-z\u00e4\u00f6\u00fc\u00df ])", "Ue")
                .replaceAll("\u00d6(?=[a-z\u00e4\u00f6\u00fc\u00df ])", "Oe")
                .replaceAll("\u00c4(?=[a-z\u00e4\u00f6\u00fc\u00df ])", "Ae")
                .replace("\u00dc", "UE")
                .replace("\u00d6", "OE")
                .replace("\u00c4", "AE");
    }

    /**
     * Returns the delimiter used in the file based on the data type.
     *
     * @param dataType The type of data being read (HUB, SHIPPING_POINT, or
     *                 PARCEL_LOCKER).
     * @return The delimiter as a string.
     */
    private String getDelimiter(DataType dataType) {
        switch (dataType) {
            case HUB:
            case SHIPPING_POINT:
                return ";";
            case PARCEL_LOCKER:
                return ",";
            default:
                throw new IllegalArgumentException("Unknown DataType: " + dataType);
        }
    }

    /**
     * Enum representing the types of data that can be processed.
     */
    private enum DataType {
        HUB,
        SHIPPING_POINT,
        PARCEL_LOCKER
    }
}
