package hagrid;

import jakarta.validation.constraints.Positive;
import org.geotools.xml.xsi.XSISimpleTypes.Boolean;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.freight.carriers.TimeWindow;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

/**
 * HagridConfigGroup class defines the configuration settings for the Hagrid module.
 */
public class HagridConfigGroup extends ReflectiveConfigGroup {

    public static final String GROUPNAME = "hagrid";

    // Path configurations
    static final String NETWORK_XML_PATH = "networkXmlPath";
    private static final String NETWORK_XML_PATH_DESC = "Path to the network XML file.";
    private String networkXmlPath = "phd/input/reduced_network.xml.gz";

    static final String FREIGHT_DEMAND_PATH = "freightDemandPath";
    private static final String FREIGHT_DEMAND_PATH_DESC = "Path to the freight demand shapefile.";
    private String freightDemandPath = "T:/bienzeisler/USEfUL-XT/matsim-hanover/01_MATSimModelCreator/vm-hochrechnung_matsim-punkte_epsg25832_mit_plz_v4_b2b.shp";

    static final String FREIGHT_VEHICLE_TYPES_PATH = "freightVehicleTypePath";
    private static final String FREIGHT_VEHICLE_TYPES_PATH_DESC = "Path to the freight vehicle type xml.";
    private String freightVehicleTypePath = "phd/input/HAGRID_vehicleTypes.xml";

    static final String HUB_DATA_PATH = "hubDataPath";
    private static final String HUB_DATA_PATH_DESC = "Path to the hub data file.";
    private String hubDataPath = "phd/input/hubs/KEP-hubs_v3.csv";

    static final String SHIPPING_POINT_DATA_PATH = "shippingPointDataPath";
    private static final String SHIPPING_POINT_DATA_PATH_DESC = "Path to the shipping point data file.";
    private String shippingPointDataPath = "phd/input/hubs/standorte_von_paket.net/";

    static final String PARCEL_LOCKER_DATA_PATH = "parcelLockerDataPath";
    private static final String PARCEL_LOCKER_DATA_PATH_DESC = "Path to the parcel locker data file.";
    private String parcelLockerDataPath = "phd/input/hubs/standorte_von_dhl.de.csv";

    // Providers
    static final String SHP_PROVIDERS = "shpProviders";
    private static final String SHP_PROVIDERS_DESC = "List of shapefile providers.";
    private List<String> shpProviders = List.of("dhl_tag", "hermes_tag", "ups_tag", "amazon_tag", "dpd_tag", "gls_tag",
            "fedex_tag");

    static final String LOCATION_PROVIDERS = "locationProviders";
    private static final String LOCATION_PROVIDERS_DESC = "List of location providers.";
    private List<String> locationProviders = List.of("dhl", "dpd", "gls", "hermes", "ups");

    // Link and speed configurations
    static final String MIN_LINK_LENGTH = "minLinkLength";
    private static final String MIN_LINK_LENGTH_DESC = "Minimum link length.";
    @Positive
    private double minLinkLength = 5.0;

    static final String MIN_FREE_SPEED = "minFreeSpeed";
    private static final String MIN_FREE_SPEED_DESC = "Minimum free speed in m/s.";
    @Positive
    private double minFreeSpeed = 2.777778;

    static final String FREE_SPEED_THRESHOLD = "freeSpeedThreshold";
    private static final String FREE_SPEED_THRESHOLD_DESC = "Free speed threshold in m/s.";
    @Positive
    private double freeSpeedThreshold = 17.0;

    // Vehicle capacities
    @Positive
    private int cepVehCap = 230;
    private static final String CEP_VEH_CAP_DESC = "Capacity of CEP vehicles.";

    @Positive
    private int supplyVehCap = 2000;
    private static final String SUPPLY_VEH_CAP_DESC = "Capacity of supply vehicles.";

    // Other configurations
    @Positive
    private int demandBorder = 600;
    private static final String DEMAND_BORDER_DESC = "Demand border threshold.";

    @Positive
    private int maxRouteDuration = 8 * 3600;
    private static final String MAX_ROUTE_DURATION_DESC = "Maximum route duration in seconds.";

    @Positive
    private int durationPerParcel = 2;
    private static final String DURATION_PER_PARCEL_DESC = "Duration per parcel in minutes.";

    @Positive
    private int maxDurationPerStop = 15;
    private static final String MAX_DURATION_PER_STOP_DESC = "Maximum duration per stop in minutes.";

    @Positive
    private int parcelLockerDemand = 25;
    private static final String PARCEL_LOCKER_DEMAND_DESC = "Parcel locker demand threshold.";

    @Positive
    private int parcelLockerDuration = 20;
    private static final String PARCEL_LOCKER_DURATION_DESC = "Time spent at parcel locker in minutes.";

    @Positive
    private int hubLimitDHL = 16000;
    private static final String HUB_LIMIT_DHL_DESC = "Hub limit for DHL.";

    @Positive
    private int hubLimitPost = 6000;
    private static final String HUB_LIMIT_POST_DESC = "Hub limit for Post.";

    @Positive
    private double maxDriverTime = 600.0;
    private static final String MAX_DRIVER_TIME_DESC = "Maximum driver operation time in minutes.";

    // Concept enumeration
    public enum Concept {
        BASELINE, WHITE_LABEL, UCC, COLLECTION_POINTS
    }

    static final String CONCEPT = "concept";
    private static final String CONCEPT_DESC = "Concept being simulated (baseline, white-label, ucc, collection points).";
    private Concept concept = Concept.BASELINE;

    // Algorithm file path
    static final String ALGORITHM_FILE = "algorithmFile";
    private static final String ALGORITHM_FILE_DESC = "Path to the vehicle routing algorithm file.";
    private String algorithmFile = "./res/freight/jsprit_algorithm.xml";

    // Delivery rates
    private int deliveryRateDhl = 0;
    private int deliveryRateGls = 0;
    private int deliveryRateHermes = 0;
    private int deliveryRateDpd = 0;
    private int deliveryRateUps = 0;
    private int deliveryRateAmazon = 0;
    private int deliveryRateFedex = 0;
    private int deliveryRateWl = 0;

    // Vehicle operation times
    private int startHourRegular = 7;
    private int endHourRegular = 14;
    private int startHourAmazon = 9;
    private int endHourAmazon = 17;

    // Delivery time window
    private TimeWindow deliveryTimeWindow = TimeWindow.newInstance(8 * 60 * 60, 20 * 60 * 60);

    public HagridConfigGroup() {
        super(GROUPNAME);
        setDefaultDeliveryRates();
    }

    private void setDefaultDeliveryRates() {
        switch (concept) {
            case WHITE_LABEL:
                deliveryRateWl = 94;
                shpProviders = List.of("wl_tag");
                break;
            case UCC:
                // Define UCC-specific defaults here
                break;
            case COLLECTION_POINTS:
                // Define collection points-specific defaults here
                break;
            case BASELINE:
            default:
                deliveryRateDhl = 94; //+2
                deliveryRateGls = 91; // +2
                deliveryRateHermes = 91; // +2
                deliveryRateDpd = 89; // +3
                deliveryRateUps = 89; // +3
                deliveryRateAmazon = 95;
                deliveryRateFedex = 89; // +3
                shpProviders = List.of("dhl_tag", "hermes_tag", "ups_tag", "amazon_tag", "dpd_tag", "gls_tag",
                        "fedex_tag");
                break;
        }
    }

    @StringGetter(NETWORK_XML_PATH)
    public String getNetworkXmlPath() {
        return networkXmlPath;
    }

    @StringSetter(NETWORK_XML_PATH)
    public void setNetworkXmlPath(String networkXmlPath) {
        this.networkXmlPath = networkXmlPath;
    }

    @StringGetter(FREIGHT_DEMAND_PATH)
    public String getFreightDemandPath() {
        return freightDemandPath;
    }

    @StringSetter(FREIGHT_DEMAND_PATH)
    public void setFreightDemandPath(String freightDemandPath) {
        this.freightDemandPath = freightDemandPath;
    }

    @StringGetter(FREIGHT_VEHICLE_TYPES_PATH)
    public String getVehicleTypePath() {
        return freightVehicleTypePath;
    }

    @StringSetter(FREIGHT_VEHICLE_TYPES_PATH)
    public void setVehicleTypePath(String freightVehicleTypePath) {
        this.freightVehicleTypePath = freightVehicleTypePath;
    }

    @StringGetter(HUB_DATA_PATH)
    public String getHubDataPath() {
        return hubDataPath;
    }

    @StringSetter(HUB_DATA_PATH)
    public void setHubDataPath(String hubDataPath) {
        this.hubDataPath = hubDataPath;
    }

    @StringGetter(SHIPPING_POINT_DATA_PATH)
    public String getShippingPointDataPath() {
        return shippingPointDataPath;
    }

    @StringSetter(SHIPPING_POINT_DATA_PATH)
    public void setShippingPointDataPath(String shippingPointDataPath) {
        this.shippingPointDataPath = shippingPointDataPath;
    }

    @StringGetter(PARCEL_LOCKER_DATA_PATH)
    public String getParcelLockerDataPath() {
        return parcelLockerDataPath;
    }

    @StringSetter(PARCEL_LOCKER_DATA_PATH)
    public void setParcelLockerDataPath(String parcelLockerDataPath) {
        this.parcelLockerDataPath = parcelLockerDataPath;
    }

    @StringGetter(SHP_PROVIDERS)
    public List<String> getShpProviders() {
        return shpProviders;
    }

    @StringSetter(SHP_PROVIDERS)
    public void setShpProviders(List<String> shpProviders) {
        this.shpProviders = shpProviders;
    }

    @StringGetter(LOCATION_PROVIDERS)
    public List<String> getLocationProviders() {
        return locationProviders;
    }

    @StringSetter(LOCATION_PROVIDERS)
    public void setLocationProviders(List<String> locationProviders) {
        this.locationProviders = locationProviders;
    }

    @StringGetter(CONCEPT)
    public String getConcept() {
        return concept.name().toLowerCase();
    }

    @StringSetter(CONCEPT)
    public void setConcept(String concept) {
        this.concept = Concept.valueOf(concept.toUpperCase());
        setDefaultDeliveryRates();
    }

    @StringGetter(ALGORITHM_FILE)
    public String getAlgorithmFile() {
        return algorithmFile;
    }

    @StringSetter(ALGORITHM_FILE)
    public void setAlgorithmFile(String algorithmFile) {
        this.algorithmFile = algorithmFile;
    }

    @StringGetter("deliveryRateDhl")
    public int getDeliveryRateDhl() {
        return deliveryRateDhl;
    }

    @StringSetter("deliveryRateDhl")
    public void setDeliveryRateDhl(int rate) {
        this.deliveryRateDhl = rate;
    }

    @StringGetter("deliveryRateGls")
    public int getDeliveryRateGls() {
        return deliveryRateGls;
    }

    @StringSetter("deliveryRateGls")
    public void setDeliveryRateGls(int rate) {
        this.deliveryRateGls = rate;
    }

    @StringGetter("deliveryRateHermes")
    public int getDeliveryRateHermes() {
        return deliveryRateHermes;
    }

    @StringSetter("deliveryRateHermes")
    public void setDeliveryRateHermes(int rate) {
        this.deliveryRateHermes = rate;
    }

    @StringGetter("deliveryRateDpd")
    public int getDeliveryRateDpd() {
        return deliveryRateDpd;
    }

    @StringSetter("deliveryRateDpd")
    public void setDeliveryRateDpd(int rate) {
        this.deliveryRateDpd = rate;
    }

    @StringGetter("deliveryRateUps")
    public int getDeliveryRateUps() {
        return deliveryRateUps;
    }

    @StringSetter("deliveryRateUps")
    public void setDeliveryRateUps(int rate) {
        this.deliveryRateUps = rate;
    }

    @StringGetter("deliveryRateAmazon")
    public int getDeliveryRateAmazon() {
        return deliveryRateAmazon;
    }

    @StringSetter("deliveryRateAmazon")
    public void setDeliveryRateAmazon(int rate) {
        this.deliveryRateAmazon = rate;
    }

    @StringGetter("deliveryRateFedex")
    public int getDeliveryRateFedex() {
        return deliveryRateFedex;
    }

    @StringSetter("deliveryRateFedex")
    public void setDeliveryRateFedex(int rate) {
        this.deliveryRateFedex = rate;
    }

    @StringGetter("deliveryRateWl")
    public int getDeliveryRateWl() {
        return deliveryRateWl;
    }

    @StringSetter("deliveryRateWl")
    public void setDeliveryRateWl(int rate) {
        this.deliveryRateWl = rate;
    }

    @StringGetter(MIN_LINK_LENGTH)
    public double getMinLinkLength() {
        return minLinkLength;
    }

    @StringSetter(MIN_LINK_LENGTH)
    public void setMinLinkLength(double minLinkLength) {
        this.minLinkLength = minLinkLength;
    }

    @StringGetter(MIN_FREE_SPEED)
    public double getMinFreeSpeed() {
        return minFreeSpeed;
    }

    @StringSetter(MIN_FREE_SPEED)
    public void setMinFreeSpeed(double minFreeSpeed) {
        this.minFreeSpeed = minFreeSpeed;
    }

    @StringGetter(FREE_SPEED_THRESHOLD)
    public double getFreeSpeedThreshold() {
        return freeSpeedThreshold;
    }

    @StringSetter(FREE_SPEED_THRESHOLD)
    public void setFreeSpeedThreshold(double freeSpeedThreshold) {
        this.freeSpeedThreshold = freeSpeedThreshold;
    }

    @StringGetter("hubLimitDHL")
    public int getHubLimitDHL() {
        return hubLimitDHL;
    }

    @StringSetter("hubLimitDHL")
    public void setHubLimitDHL(int hubLimitDHL) {
        this.hubLimitDHL = hubLimitDHL;
    }

    @StringGetter("hubLimitPost")
    public int getHubLimitPost() {
        return hubLimitPost;
    }

    @StringSetter("hubLimitPost")
    public void setHubLimitPost(int hubLimitPost) {
        this.hubLimitPost = hubLimitPost;
    }

    @StringGetter("cepVehCap")
    public int getCepVehCap() {
        return cepVehCap;
    }

    @StringSetter("cepVehCap")
    public void setCepVehCap(int cepVehCap) {
        this.cepVehCap = cepVehCap;
    }

    @StringGetter("supplyVehCap")
    public int getSupplyVehCap() {
        return supplyVehCap;
    }

    @StringSetter("supplyVehCap")
    public void setSupplyVehCap(int supplyVehCap) {
        this.supplyVehCap = supplyVehCap;
    }

    @StringGetter("demandBorder")
    public int getDemandBorder() {
        return demandBorder;
    }

    @StringSetter("demandBorder")
    public void setDemandBorder(int demandBorder) {
        this.demandBorder = demandBorder;
    }

    @StringGetter("maxRouteDuration")
    public int getMaxRouteDuration() {
        return maxRouteDuration;
    }

    @StringSetter("maxRouteDuration")
    public void setMaxRouteDuration(int maxRouteDuration) {
        this.maxRouteDuration = maxRouteDuration;
    }

    @StringGetter("durationPerParcel")
    public int getDurationPerParcel() {
        return durationPerParcel;
    }

    @StringSetter("durationPerParcel")
    public void setDurationPerParcel(int durationPerParcel) {
        this.durationPerParcel = durationPerParcel;
    }

    @StringGetter("maxDurationPerStop")
    public int getMaxDurationPerStop() {
        return maxDurationPerStop;
    }

    @StringSetter("maxDurationPerStop")
    public void setMaxDurationPerStop(int maxDurationPerStop) {
        this.maxDurationPerStop = maxDurationPerStop;
    }

    @StringGetter("parcelLockerDemand")
    public int getParcelLockerDemand() {
        return parcelLockerDemand;
    }

    @StringSetter("parcelLockerDemand")
    public void setParcelLockerDemand(int parcelLockerDemand) {
        this.parcelLockerDemand = parcelLockerDemand;
    }

    @StringGetter("parcelLockerDuration")
    public int getParcelLockerDuration() {
        return parcelLockerDuration;
    }

    @StringSetter("parcelLockerDuration")
    public void setParcelLockerDuration(int parcelLockerDuration) {
        this.parcelLockerDuration = parcelLockerDuration;
    }

    @StringGetter("maxDriverTime")
    public double getMaxDriverTime() {
        return maxDriverTime;
    }

    @StringSetter("maxDriverTime")
    public void setMaxDriverTime(double maxDriverTime) {
        this.maxDriverTime = maxDriverTime;
    }

    @StringGetter("startHourRegular")
    public int getStartHourRegular() {
        return startHourRegular;
    }

    @StringSetter("startHourRegular")
    public void setStartHourRegular(int startHourRegular) {
        this.startHourRegular = startHourRegular;
    }

    @StringGetter("endHourRegular")
    public int getEndHourRegular() {
        return endHourRegular;
    }

    @StringSetter("endHourRegular")
    public void setEndHourRegular(int endHourRegular) {
        this.endHourRegular = endHourRegular;
    }

    @StringGetter("startHourAmazon")
    public int getStartHourAmazon() {
        return startHourAmazon;
    }

    @StringSetter("startHourAmazon")
    public void setStartHourAmazon(int startHourAmazon) {
        this.startHourAmazon = startHourAmazon;
    }

    @StringGetter("endHourAmazon")
    public int getEndHourAmazon() {
        return endHourAmazon;
    }

    @StringSetter("endHourAmazon")
    public void setEndHourAmazon(int endHourAmazon) {
        this.endHourAmazon = endHourAmazon;
    }

    @StringGetter("deliveryTimeWindowStart")
    public double getDeliveryTimeWindowStart() {
        return deliveryTimeWindow.getStart();
    }

    @StringSetter("deliveryTimeWindowStart")
    public void setDeliveryTimeWindowStart(double start) {
        this.deliveryTimeWindow = TimeWindow.newInstance(start, this.deliveryTimeWindow.getEnd());
    }

    @StringGetter("deliveryTimeWindowEnd")
    public double getDeliveryTimeWindowEnd() {
        return deliveryTimeWindow.getEnd();
    }

    @StringSetter("deliveryTimeWindowEnd")
    public void setDeliveryTimeWindowEnd(double end) {
        this.deliveryTimeWindow = TimeWindow.newInstance(this.deliveryTimeWindow.getStart(), end);
    }

    public boolean isWhiteLabel() {
        return this.concept == Concept.WHITE_LABEL;
    }

    @Override
    public Map<String, String> getComments() {
        Map<String, String> map = super.getComments();
        map.put(NETWORK_XML_PATH, NETWORK_XML_PATH_DESC);
        map.put(FREIGHT_DEMAND_PATH, FREIGHT_DEMAND_PATH_DESC);
        map.put(FREIGHT_VEHICLE_TYPES_PATH, FREIGHT_VEHICLE_TYPES_PATH_DESC);
        map.put(HUB_DATA_PATH, HUB_DATA_PATH_DESC);
        map.put(SHIPPING_POINT_DATA_PATH, SHIPPING_POINT_DATA_PATH_DESC);
        map.put(PARCEL_LOCKER_DATA_PATH, PARCEL_LOCKER_DATA_PATH_DESC);
        map.put(SHP_PROVIDERS, SHP_PROVIDERS_DESC);
        map.put(LOCATION_PROVIDERS, LOCATION_PROVIDERS_DESC);
        map.put(CONCEPT, CONCEPT_DESC);
        map.put(ALGORITHM_FILE, ALGORITHM_FILE_DESC);
        map.put("cepVehCap", CEP_VEH_CAP_DESC);
        map.put("supplyVehCap", SUPPLY_VEH_CAP_DESC);
        map.put("demandBorder", DEMAND_BORDER_DESC);
        map.put("maxRouteDuration", MAX_ROUTE_DURATION_DESC);
        map.put("durationPerParcel", DURATION_PER_PARCEL_DESC);
        map.put("maxDurationPerStop", MAX_DURATION_PER_STOP_DESC);
        map.put("parcelLockerDemand", PARCEL_LOCKER_DEMAND_DESC);
        map.put("parcelLockerDuration", PARCEL_LOCKER_DURATION_DESC);
        map.put("hubLimitDHL", HUB_LIMIT_DHL_DESC);
        map.put("hubLimitPost", HUB_LIMIT_POST_DESC);
        map.put("maxDriverTime", MAX_DRIVER_TIME_DESC);
        map.put(MIN_LINK_LENGTH, MIN_LINK_LENGTH_DESC);
        map.put(MIN_FREE_SPEED, MIN_FREE_SPEED_DESC);
        map.put(FREE_SPEED_THRESHOLD, FREE_SPEED_THRESHOLD_DESC);
        map.put("deliveryTimeWindowStart", "Start time of the delivery time window.");
        map.put("deliveryTimeWindowEnd", "End time of the delivery time window.");
        return map;
    }
}
