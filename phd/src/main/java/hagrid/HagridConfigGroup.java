package hagrid;

import jakarta.validation.constraints.Positive;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

public class HagridConfigGroup extends ReflectiveConfigGroup {

    public static final String GROUPNAME = "hagrid";

    static final String NETWORK_XML_PATH = "networkXmlPath";
    private static final String NETWORK_XML_PATH_DESC = "Path to the network XML file.";
    private String networkXmlPath = "x:/projekte/03_abgeschlossen/USEfUL XT/02_Bearbeitung/01_Modell/23032022_Hannover_kalibriertes_Basismodell/Basismodell_10/multimodalNetwork.xml";

    static final String FREIGHT_DEMAND_PATH = "freightDemandPath";
    private static final String FREIGHT_DEMAND_PATH_DESC = "Path to the freight demand shapefile.";
    private String freightDemandPath = "vm-hochrechnung_matsim-punkte_epsg25832_mit_plz.shp";

    static final String SHP_PROVIDERS = "shpProviders";
    private static final String SHP_PROVIDERS_DESC = "List of shapefile providers.";
    private List<String> shpProviders = List.of("dhl_tag", "hermes_tag", "ups_tag", "amazon_tag", "dpd_tag", "gls_tag",
            "fedex_tag");

    static final String LOCATION_PROVIDERS = "locationProviders";
    private static final String LOCATION_PROVIDERS_DESC = "List of location providers.";
    private List<String> locationProviders = List.of("dhl", "dpd", "gls", "hermes", "ups");

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


    @Positive
    private int cepVehCap = 230;
    private static final String CEP_VEH_CAP_DESC = "Capacity of CEP vehicles.";

    @Positive
    private int supplyVehCap = 2000;
    private static final String SUPPLY_VEH_CAP_DESC = "Capacity of supply vehicles.";

    @Positive
    private int demandBorder = 1000;
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
    private int packstationDemand = 25;
    private static final String PACKSTATION_DEMAND_DESC = "Packstation demand threshold.";

    @Positive
    private int packstationTime = 20;
    private static final String PACKSTATION_TIME_DESC = "Time spent at packstation in minutes.";

    @Positive
    private int hubLimitDHL = 16000;
    private static final String HUB_LIMIT_DHL_DESC = "Hub limit for DHL.";

    @Positive
    private int hubLimitPost = 6000;
    private static final String HUB_LIMIT_POST_DESC = "Hub limit for Post.";

    @Positive
    private double maxDriverTime = 600.0;
    private static final String MAX_DRIVER_TIME_DESC = "Maximum driver operation time in minutes.";

    public enum Concept {
        BASELINE, WHITE_LABEL, UCC, COLLECTION_POINTS
    }

    static final String CONCEPT = "concept";
    private static final String CONCEPT_DESC = "Concept being simulated (baseline, white-label, ucc, collection points).";
    private Concept concept = Concept.BASELINE;

    static final String ALGORITHM_FILE = "algorithmFile";
    private static final String ALGORITHM_FILE_DESC = "Path to the vehicle routing algorithm file.";
    private String algorithmFile = "./res/freight/jsprit_algorithm.xml";

    private int deliveryRateDhl = 96;
    private int deliveryRateGls = 93;
    private int deliveryRateHermes = 93;
    private int deliveryRateDpd = 92;
    private int deliveryRateUps = 92;
    private int deliveryRateAmazon = 95;
    private int deliveryRateFedex = 92;
    private int deliveryRateWl = 94;

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
                deliveryRateDhl = 96;
                deliveryRateGls = 93;
                deliveryRateHermes = 93;
                deliveryRateDpd = 92;
                deliveryRateUps = 92;
                deliveryRateAmazon = 95;
                deliveryRateFedex = 92;
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

    // Getters and setters for new fields
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

    @Override
    public Map<String, String> getComments() {
        Map<String, String> map = super.getComments();
        map.put(NETWORK_XML_PATH, NETWORK_XML_PATH_DESC);
        map.put(FREIGHT_DEMAND_PATH, FREIGHT_DEMAND_PATH_DESC);
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
        map.put("packstationDemand", PACKSTATION_DEMAND_DESC);
        map.put("packstationTime", PACKSTATION_TIME_DESC);
        map.put("hubLimitDHL", HUB_LIMIT_DHL_DESC);
        map.put("hubLimitPost", HUB_LIMIT_POST_DESC);
        map.put("maxDriverTime", MAX_DRIVER_TIME_DESC);
        map.put(MIN_LINK_LENGTH, MIN_LINK_LENGTH_DESC);
        map.put(MIN_FREE_SPEED, MIN_FREE_SPEED_DESC);
        map.put(FREE_SPEED_THRESHOLD, FREE_SPEED_THRESHOLD_DESC);
        return map;
    }
}
