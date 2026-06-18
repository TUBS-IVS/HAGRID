package hagrid;

import hagrid.utils.general.Region;
import hagrid.utils.general.StudyArea;
import org.matsim.freight.carriers.TimeWindow;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HAGRID Configuration - Clean, structured configuration for parcel demand simulation.
 * 
 * <p>This class replaces the verbose MATSim ReflectiveConfigGroup with a straightforward
 * POJO approach. Configuration is organized into logical sections for better readability.</p>
 * 
 * <h2>Configuration Sections:</h2>
 * <ul>
 *   <li>{@link InputPaths} - All file paths for input data</li>
 *   <li>{@link ProviderConfig} - CEP provider settings and delivery rates</li>
 *   <li>{@link VehicleConfig} - Vehicle types, capacities, and dispatch settings</li>
 *   <li>{@link RoutingConfig} - Timing, durations, and routing constraints</li>
 *   <li>{@link SupplyConfig} - Long-haul/supply vehicle settings</li>
 *   <li>{@link HubConfig} - Hub limits and parcel locker settings</li>
 *   <li>{@link NetworkConfig} - Network filtering parameters</li>
 * </ul>
 * 
 * @author HAGRID Team
 */
public class HagridConfig {

    // =========================================================================
    // SCENARIO SETTINGS
    // =========================================================================
    
    private Scenario scenario = Scenario.BASECASE;
    private LocalDate simulationDate;
    private String tag = "";
    private String runId;
    private Set<Region> filterRegions = new LinkedHashSet<>(Set.of(Region.ALL));
    private StudyArea studyArea = StudyArea.HANNOVER;

    // =========================================================================
    // CONFIGURATION SECTIONS
    // =========================================================================

    private HagridPaths hagridPaths = new HagridPaths(StudyArea.HANNOVER);
    private final InputPaths inputPaths = new InputPaths();
    private final ProviderConfig providers = new ProviderConfig();
    private final VehicleConfig vehicles = new VehicleConfig();
    private final RoutingConfig routing = new RoutingConfig();
    private final SupplyConfig supply = new SupplyConfig();
    private final HubConfig hubs = new HubConfig();
    private final NetworkConfig network = new NetworkConfig();

    // =========================================================================
    // SCENARIO ENUM
    // =========================================================================
    
    public enum Scenario {
        BASECASE,
        WHITE_LABEL,
        UCC,
        COLLECTION_POINTS,
        BATCHMODERATE,
        BATCHMEDIUM,
        BATCHHIGH,
        BATCHFULL
    }

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================
    
    public HagridConfig() {
        initializeDefaults();
    }

    private void initializeDefaults() {
        providers.initializeDefaultRates(scenario);
        vehicles.initializeDefaultDispatchHours();
        deriveInputPaths();
    }

    /** (Re)derive all input file paths from the current {@link HagridPaths}. */
    private void deriveInputPaths() {
        inputPaths.setNetwork(hagridPaths.networkFile());
        inputPaths.setVehicleTypes(hagridPaths.vehicleTypesFile());
        inputPaths.setHubData(hagridPaths.hubDataFile());
        inputPaths.setShippingPoints(hagridPaths.shippingPointsDir());
        inputPaths.setParcelLockers(hagridPaths.parcelLockersFile());
    }

    // =========================================================================
    // INPUT PATHS SECTION
    // =========================================================================
    
    /**
     * All input file paths for the simulation.
     */
    public static class InputPaths {
        // Defaults are overridden by initializeDefaults() from HagridPaths.
        // Empty strings here → never used with the hardcoded prefix.
        private String network = "";
        private String freightDemand = "";
        private String vehicleTypes = "";
        private String hubData = "";
        private String shippingPoints = "";
        private String parcelLockers = "";

        public String getNetwork() { return network; }
        public void setNetwork(String network) { this.network = network; }

        public String getFreightDemand() { return freightDemand; }
        public void setFreightDemand(String freightDemand) { this.freightDemand = freightDemand; }

        public String getVehicleTypes() { return vehicleTypes; }
        public void setVehicleTypes(String vehicleTypes) { this.vehicleTypes = vehicleTypes; }

        public String getHubData() { return hubData; }
        public void setHubData(String hubData) { this.hubData = hubData; }

        public String getShippingPoints() { return shippingPoints; }
        public void setShippingPoints(String shippingPoints) { this.shippingPoints = shippingPoints; }

        public String getParcelLockers() { return parcelLockers; }
        public void setParcelLockers(String parcelLockers) { this.parcelLockers = parcelLockers; }
    }

    // =========================================================================
    // PROVIDER CONFIGURATION SECTION
    // =========================================================================
    
    /**
     * CEP provider configuration including delivery rates and provider lists.
     */
    public static class ProviderConfig {
        
        // Provider lists for data sources
        private List<String> shapefileProviders = new ArrayList<>(List.of(
            "dhl_tag", "hermes_tag", "ups_tag", "amazon_tag", "dpd_tag", "gls_tag", "fedex_tag"
        ));
        private List<String> locationProviders = new ArrayList<>(List.of(
            "dhl", "dpd", "gls", "hermes", "ups"
        ));

        // Delivery success rates per provider (percentage)
        private final Map<String, Integer> deliveryRates = new LinkedHashMap<>();

        public ProviderConfig() {
            // Initialize with BASECASE defaults
            initializeDefaultRates(Scenario.BASECASE);
        }

        void initializeDefaultRates(Scenario scenario) {
            deliveryRates.clear();
            switch (scenario) {
                case WHITE_LABEL:
                    deliveryRates.put("wl", 94);
                    shapefileProviders = List.of("wl_tag");
                    break;
                case BASECASE:
                default:
                    deliveryRates.put("dhl", 94);
                    deliveryRates.put("gls", 91);
                    deliveryRates.put("hermes", 91);
                    deliveryRates.put("dpd", 89);
                    deliveryRates.put("ups", 89);
                    deliveryRates.put("amazon", 93);
                    deliveryRates.put("fedex", 89);
                    break;
            }
        }

        public int getDeliveryRate(String provider) {
            return deliveryRates.getOrDefault(provider.toLowerCase(), 90);
        }

        public void setDeliveryRate(String provider, int rate) {
            deliveryRates.put(provider.toLowerCase(), rate);
        }

        public Map<String, Integer> getAllDeliveryRates() {
            return Collections.unmodifiableMap(deliveryRates);
        }

        public List<String> getShapefileProviders() { return shapefileProviders; }
        public void setShapefileProviders(List<String> providers) { this.shapefileProviders = new ArrayList<>(providers); }

        public List<String> getLocationProviders() { return locationProviders; }
        public void setLocationProviders(List<String> providers) { this.locationProviders = new ArrayList<>(providers); }
    }

    // =========================================================================
    // VEHICLE CONFIGURATION SECTION
    // =========================================================================
    
    /**
     * Vehicle configuration for CEP (last-mile delivery) vehicles.
     */
    public static class VehicleConfig {
        
        // Known vehicle capacities by size alias
        private static final Map<String, Integer> CAPACITY_BY_SIZE = Map.of(
            "s", 80,      // Small vehicle
            "m", 165,     // Medium CEP vehicle
            "l", 230,     // Large CEP vehicle
            "bike", 23    // Cargo bike
        );

        // Active vehicle sizes for the simulation
        private List<String> activeSizes = new ArrayList<>(List.of("m", "l"));
        
        // Provider-specific vehicle sizes (optional override)
        private final Map<String, List<String>> providerSizes = new LinkedHashMap<>();
        
        // Dispatch hours: when vehicles start their tours
        private final Map<String, List<Integer>> dispatchHours = new LinkedHashMap<>();
        
        // Provider time shifts (e.g., Amazon starts 1 hour later)
        private final Map<String, Integer> providerTimeShifts = new LinkedHashMap<>();

        void initializeDefaultDispatchHours() {
            dispatchHours.put("default", List.of(7, 14));  // Two waves: morning and afternoon
        }

        // --- Vehicle Sizes ---

        public List<String> getActiveSizes() {
            return Collections.unmodifiableList(activeSizes);
        }

        public void setActiveSizes(List<String> sizes) {
            this.activeSizes = new ArrayList<>(sizes);
        }

        public List<String> getSizesForProvider(String provider) {
            if (provider == null) return getActiveSizes();
            List<String> sizes = providerSizes.get(provider.toLowerCase());
            return sizes != null ? sizes : getActiveSizes();
        }

        public void setProviderSizes(String provider, List<String> sizes) {
            if (sizes == null || sizes.isEmpty()) {
                providerSizes.remove(provider.toLowerCase());
            } else {
                providerSizes.put(provider.toLowerCase(), new ArrayList<>(sizes));
            }
        }

        // --- Capacities (computed from sizes) ---

        public int getCapacity(String size) {
            String normalized = size.trim().toLowerCase();
            
            // Check known aliases
            if (CAPACITY_BY_SIZE.containsKey(normalized)) {
                return CAPACITY_BY_SIZE.get(normalized);
            }
            
            // Check "capacity_type" format (e.g., "60_m")
            if (normalized.contains("_")) {
                String[] parts = normalized.split("_", 2);
                try {
                    return Integer.parseInt(parts[0]);
                } catch (NumberFormatException ignored) {}
            }
            
            // Try pure numeric
            try {
                return Integer.parseInt(normalized);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Unknown vehicle size: " + size);
            }
        }

        public int getMinCapacity() {
            return activeSizes.stream()
                .mapToInt(this::getCapacity)
                .min()
                .orElseThrow(() -> new IllegalStateException("No vehicle sizes configured"));
        }

        public int getMaxCapacity() {
            return activeSizes.stream()
                .mapToInt(this::getCapacity)
                .max()
                .orElseThrow(() -> new IllegalStateException("No vehicle sizes configured"));
        }

        // --- Dispatch Hours ---

        public List<Integer> getDispatchHours(String provider) {
            if (provider != null) {
                List<Integer> hours = dispatchHours.get(provider.toLowerCase());
                if (hours != null && !hours.isEmpty()) {
                    return applyTimeShift(hours, provider);
                }
            }
            List<Integer> defaultHours = dispatchHours.getOrDefault("default", List.of(7, 14));
            return provider != null ? applyTimeShift(defaultHours, provider) : defaultHours;
        }

        public void setDispatchHours(String provider, List<Integer> hours) {
            dispatchHours.put(provider.toLowerCase(), new ArrayList<>(hours));
        }

        public void setDefaultDispatchHours(List<Integer> hours) {
            setDispatchHours("default", hours);
        }

        /**
         * Clears all provider-specific dispatch hours, keeping only the default.
         * Called between scenarios to prevent state leaking.
         */
        public void clearDispatchHours() {
            dispatchHours.clear();
            initializeDefaultDispatchHours();
        }

        /**
         * Clears all provider-specific vehicle size overrides.
         * Called between scenarios to prevent state leaking.
         */
        public void clearProviderSizes() {
            providerSizes.clear();
        }

        // --- Time Shifts ---

        public void setProviderTimeShift(String provider, int hoursShift) {
            providerTimeShifts.put(provider.toLowerCase(), hoursShift);
        }

        public int getProviderTimeShift(String provider) {
            return providerTimeShifts.getOrDefault(provider.toLowerCase(), 0);
        }

        private List<Integer> applyTimeShift(List<Integer> hours, String provider) {
            int shift = getProviderTimeShift(provider);
            if (shift == 0) return hours;
            return hours.stream()
                .map(h -> Math.min(23, Math.max(0, h + shift)))
                .toList();
        }
    }

    // =========================================================================
    // ROUTING CONFIGURATION SECTION
    // =========================================================================
    
    /**
     * Routing and timing parameters for tour planning.
     */
    public static class RoutingConfig {
        
        // Time constraints
        private int maxRouteDurationSeconds = 27000;  // 7.5 hours
        private double maxDriverTimeMinutes = 600.0;   // 10 hours
        
        // Service durations
        private int durationPerParcelMinutes = 2;
        private int maxDurationPerStopMinutes = 15;
        
        // Delivery time window — the REAL TW for MATSim scoring penalties.
        // Services (deliveries) outside this window incur a TW penalty.
        // Configured via ScenarioConfig.deliveryTimeWindow(start, end).
        private int deliveryWindowStartHour = 8;
        private int deliveryWindowEndHour = 20;
        
        // Demand splitting thresholds
        private int demandBorder = 600;  // Max deliveries per carrier for KMeans clustering
        private int dhlBorder = 450;     // DHL-specific border  for filtering input SHP File -> >500 = ASSUME TRUCK DELIVERY
        
        // JSprit optimization
        private int jspritIterations = 1;  // 1 for initial model, 20-50 for production

        // Carrier merge threshold — fixed limit for merging small carriers into neighbors.
        // Carriers with total parcel demand below this threshold get merged.
        // Fixed value (not vehicle-size-dependent) so scenarios remain comparable.
        private int carrierMergeThreshold = 75;

        // --- Getters/Setters ---

        public int getMaxRouteDurationSeconds() { return maxRouteDurationSeconds; }
        public void setMaxRouteDurationSeconds(int seconds) { this.maxRouteDurationSeconds = seconds; }

        public double getMaxDriverTimeMinutes() { return maxDriverTimeMinutes; }
        public void setMaxDriverTimeMinutes(double minutes) { this.maxDriverTimeMinutes = minutes; }

        public int getDurationPerParcelMinutes() { return durationPerParcelMinutes; }
        public void setDurationPerParcelMinutes(int minutes) { this.durationPerParcelMinutes = minutes; }

        public int getMaxDurationPerStopMinutes() { return maxDurationPerStopMinutes; }
        public void setMaxDurationPerStopMinutes(int minutes) { this.maxDurationPerStopMinutes = minutes; }

        public TimeWindow getDeliveryTimeWindow() {
            return TimeWindow.newInstance(deliveryWindowStartHour * 3600, deliveryWindowEndHour * 3600);
        }

        public void setDeliveryWindow(int startHour, int endHour) {
            this.deliveryWindowStartHour = startHour;
            this.deliveryWindowEndHour = endHour;
        }

        public int getDemandBorder() { return demandBorder; }
        public void setDemandBorder(int border) { this.demandBorder = border; }

        public int getDhlBorder() { return dhlBorder; }
        public void setDhlBorder(int border) { this.dhlBorder = border; }
        
        /** JSprit iterations for routing optimization. 1 = quick, 20-50 = production quality */
        public int getJspritIterations() { return jspritIterations; }
        public void setJspritIterations(int iterations) { this.jspritIterations = Math.max(1, iterations); }

        /** Fixed carrier merge threshold (parcels). Carriers below this get merged into neighbors. */
        public int getCarrierMergeThreshold() { return carrierMergeThreshold; }
        public void setCarrierMergeThreshold(int threshold) { this.carrierMergeThreshold = Math.max(1, threshold); }

        // Soft SCORE penalty for reverse-link U-turns.
        // In JSprit/MATSim cost units (not real seconds or euros). 0 = disabled.
        private double uTurnPenaltyCost = 1.0;

        /**
         * Soft penalty for U-turns during JSprit route optimization and MATSim scoring.
         * A U-turn is detected when consecutive stops are on reverse links (A→B then B→A).
         * Each U-turn subtracts this value from the utility score.
         * This is a SCORE penalty (cost units), NOT a real-time or monetary cost.
         * Set to 0.0 to disable.
         */
        public double getUTurnPenaltyCost() { return uTurnPenaltyCost; }
        public void setUTurnPenaltyCost(double cost) { this.uTurnPenaltyCost = Math.max(0.0, cost); }
    }

    // =========================================================================
    // SUPPLY (LONG-HAUL) CONFIGURATION SECTION
    // =========================================================================
    
    /**
     * Configuration for supply/long-haul vehicles (hub-to-hub transport).
     */
    public static class SupplyConfig {
        
        private int vehicleCapacity = 2000;
        private int durationPerStopSeconds = 1800;  // 30 minutes

        // Link directions for geographical splitting
        private final Map<String, String> linkDirections = new LinkedHashMap<>() {{
            put("south", "2847279");
            put("north", "3821892-1136438-2142663-2143065-982322");
            put("east", "3029295");
            put("west", "2591669");
        }};

        // Direction probabilities for random assignment
        private final Map<String, Double> directionProbabilities = new LinkedHashMap<>() {{
            put("south", 0.25);
            put("north", 0.25);
            put("east", 0.25);
            put("west", 0.25);
        }};

        // --- Getters/Setters ---

        public int getVehicleCapacity() { return vehicleCapacity; }
        public void setVehicleCapacity(int capacity) { this.vehicleCapacity = capacity; }

        public int getDurationPerStopSeconds() { return durationPerStopSeconds; }
        public void setDurationPerStopSeconds(int seconds) { this.durationPerStopSeconds = seconds; }

        /**
         * Minimum demand for splitting a supply carrier into 4 directions.
         * Carriers below this threshold are kept as single carrier.
         * @return vehicleCapacity / 2 (carrier should be at least half full)
         */
        public int getMinSplitDemand() {
            return vehicleCapacity / 2;
        }

        public Map<String, String> getLinkDirections() {
            return Collections.unmodifiableMap(linkDirections);
        }

        public void setLinkDirection(String direction, String linkId) {
            linkDirections.put(direction.toLowerCase(), linkId);
        }

        public Map<String, Double> getDirectionProbabilities() {
            return Collections.unmodifiableMap(directionProbabilities);
        }

        public void setDirectionProbability(String direction, double probability) {
            directionProbabilities.put(direction.toLowerCase(), probability);
        }
    }

    // =========================================================================
    // HUB CONFIGURATION SECTION
    // =========================================================================
    
    /**
     * Hub and parcel locker configuration.
     */
    public static class HubConfig {
        
        private int limitDHL = 16000;
        private int limitPost = 6000;
        
        // Parcel locker settings
        private int parcelLockerDemand = 25;
        private int parcelLockerDurationMinutes = 20;

        public int getLimitDHL() { return limitDHL; }
        public void setLimitDHL(int limit) { this.limitDHL = limit; }

        public int getLimitPost() { return limitPost; }
        public void setLimitPost(int limit) { this.limitPost = limit; }

        public int getParcelLockerDemand() { return parcelLockerDemand; }
        public void setParcelLockerDemand(int demand) { this.parcelLockerDemand = demand; }

        public int getParcelLockerDurationMinutes() { return parcelLockerDurationMinutes; }
        public void setParcelLockerDurationMinutes(int minutes) { this.parcelLockerDurationMinutes = minutes; }
    }

    // =========================================================================
    // NETWORK CONFIGURATION SECTION
    // =========================================================================
    
    /**
     * Network filtering parameters.
     */
    public static class NetworkConfig {
        
        private double minLinkLengthMeters = 5.0;
        private double minFreeSpeedMps = 2.777778;   // ~10 km/h
        private double freeSpeedThresholdMps = 17.0; // ~61 km/h

        public double getMinLinkLengthMeters() { return minLinkLengthMeters; }
        public void setMinLinkLengthMeters(double meters) { this.minLinkLengthMeters = meters; }

        public double getMinFreeSpeedMps() { return minFreeSpeedMps; }
        public void setMinFreeSpeedMps(double mps) { this.minFreeSpeedMps = mps; }

        public double getFreeSpeedThresholdMps() { return freeSpeedThresholdMps; }
        public void setFreeSpeedThresholdMps(double mps) { this.freeSpeedThresholdMps = mps; }
    }

    // =========================================================================
    // MAIN ACCESSORS
    // =========================================================================

    public InputPaths paths() { return inputPaths; }
    public ProviderConfig providers() { return providers; }
    public VehicleConfig vehicles() { return vehicles; }
    public RoutingConfig routing() { return routing; }
    public SupplyConfig supply() { return supply; }
    public HubConfig hubs() { return hubs; }
    public NetworkConfig network() { return network; }

    // =========================================================================
    // SCENARIO SETTINGS
    // =========================================================================

    public Scenario getScenario() { return scenario; }
    
    public void setScenario(Scenario scenario) {
        this.scenario = scenario;
        providers.initializeDefaultRates(scenario);
    }

    public boolean isWhiteLabel() {
        return scenario == Scenario.WHITE_LABEL;
    }

    public LocalDate getSimulationDate() { return simulationDate; }

    /**
     * Sets the version tag appended to the run ID.
     * Must be called <b>before</b> {@link #setSimulationDate(LocalDate)} to take effect.
     *
     * @param tag version tag (e.g. "V1"), or {@code null}/empty to disable
     */
    public void setTag(String tag) {
        this.tag = tag == null ? "" : tag.trim();
    }

    public String getTag() { return tag; }
    
    public void setSimulationDate(LocalDate date) {
        this.simulationDate = date;
        String baseRunId = scenario.name() + "_" + date.format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        this.runId = tag.isEmpty() ? baseRunId : baseRunId + "_" + tag;
        
        // Initialize paths for this run
        hagridPaths.initializeRun(runId);
        
        // Demand uses baseRunId (demand is shared per concept+date, tag doesn't affect it)
        String formattedDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dayOfWeek = date.getDayOfWeek().toString().substring(0, 1).toUpperCase() +
                date.getDayOfWeek().toString().substring(1).toLowerCase();
        inputPaths.setFreightDemand(hagridPaths.demandShapefile(baseRunId, formattedDate, dayOfWeek));
    }

    public String getRunId() { return runId; }

    public Set<Region> getFilterRegions() { return filterRegions; }
    
    public void addFilterRegion(Region region) {
        if (filterRegions.contains(Region.ALL)) {
            filterRegions.remove(Region.ALL);
        }
        filterRegions.add(region);
    }

    public void setFilterRegions(Set<Region> regions) {
        this.filterRegions = new LinkedHashSet<>(regions);
    }

    // =========================================================================
    // OUTPUT PATHS (delegating to HagridPaths)
    // =========================================================================

    /** Central I/O path manager for all pipeline inputs, outputs, and MATSim results. */
    public HagridPaths io() { return hagridPaths; }

    public String getCarrierOutputDirectory() {
        return hagridPaths.carrierDir().toString() + "/";
    }

    public String getDeliveryCarrierOutputFile() {
        return hagridPaths.deliveryCarriersRouted();
    }

    public String getSupplyCarrierOutputFile() {
        return hagridPaths.supplyCarriersRouted();
    }

    // =========================================================================
    // CONVENIENCE METHODS
    // =========================================================================

    /**
     * Get minimum vehicle capacity (for parcel splitting).
     * Convenience method delegating to vehicles().getMinCapacity().
     */
    public int getMinVehicleCapacity() {
        return vehicles.getMinCapacity();
    }

    /**
     * Get maximum vehicle capacity.
     * Convenience method delegating to vehicles().getMaxCapacity().
     */
    public int getMaxVehicleCapacity() {
        return vehicles.getMaxCapacity();
    }

    /**
     * Get demand border for KMeans clustering.
     * Convenience method delegating to routing().getDemandBorder().
     */
    public int getDemandBorder() {
        return routing.getDemandBorder();
    }

    /**
     * Fixed carrier merge threshold (parcels).
     * Carriers with total parcel demand below this value get merged into neighbors.
     * Uses a fixed value (not dependent on vehicle sizes) so scenarios remain comparable.
     * Convenience method delegating to routing().getCarrierMergeThreshold().
     */
    public int getCarrierMergeThreshold() {
        return routing.getCarrierMergeThreshold();
    }

    /**
     * Soft score penalty for U-turns in JSprit routing and MATSim scoring.
     * 0.0 = disabled. Convenience method delegating to routing().
     */
    public double getUTurnPenaltyCost() {
        return routing.getUTurnPenaltyCost();
    }

    // =========================================================================
    // CARRIER ROUTING CACHE
    // =========================================================================

    private boolean carrierRoutingCacheEnabled = false;
    private String carrierRoutingCacheDirOverride = null;

    /**
     * Whether the carrier routing cache is enabled.
     * The Router reads this via reflection — method name must match exactly.
     */
    public boolean isCarrierRoutingCacheEnabled() {
        return carrierRoutingCacheEnabled;
    }

    /**
     * Enable or disable the carrier routing cache.
     * Called via reflection from ScenarioRunner.
     */
    public void setCarrierRoutingCacheEnabled(boolean enabled) {
        this.carrierRoutingCacheEnabled = enabled;
    }

    /**
     * Directory for persisting per-carrier routing plans.
     * The Router reads this via reflection — method name must match exactly.
     */
    public String getCarrierRoutingCacheDir() {
        if (carrierRoutingCacheDirOverride != null) return carrierRoutingCacheDirOverride;
        return hagridPaths.cacheDir().toAbsolutePath().toString();
    }

    /**
     * Override the carrier routing cache directory.
     * Called via reflection from ScenarioRunner.
     */
    public void setCarrierRoutingCacheDir(String dir) {
        this.carrierRoutingCacheDirOverride = dir;
    }

    // =========================================================================
    // LEGACY COMPATIBILITY - Flat accessors for easy migration
    // These delegate to the appropriate section
    // =========================================================================

    // --- Paths ---
    public String getFreightDemandPath() { return inputPaths.getFreightDemand(); }
    public String getNetworkXmlPath() { return inputPaths.getNetwork(); }
    public String getVehicleTypePath() { return inputPaths.getVehicleTypes(); }
    public String getHubDataPath() { return inputPaths.getHubData(); }
    public String getShippingPointDataPath() { return inputPaths.getShippingPoints(); }
    public String getParcelLockerDataPath() { return inputPaths.getParcelLockers(); }

    // --- Providers ---
    public List<String> getShpProviders() { return providers.getShapefileProviders(); }
    public List<String> getLocationProviders() { return providers.getLocationProviders(); }
    public int getDeliveryRateDhl() { return providers.getDeliveryRate("dhl"); }
    public int getDeliveryRateGls() { return providers.getDeliveryRate("gls"); }
    public int getDeliveryRateHermes() { return providers.getDeliveryRate("hermes"); }
    public int getDeliveryRateDpd() { return providers.getDeliveryRate("dpd"); }
    public int getDeliveryRateUps() { return providers.getDeliveryRate("ups"); }
    public int getDeliveryRateAmazon() { return providers.getDeliveryRate("amazon"); }
    public int getDeliveryRateFedex() { return providers.getDeliveryRate("fedex"); }
    public int getDeliveryRateWl() { return providers.getDeliveryRate("wl"); }

    // --- Vehicles ---
    public List<String> getVehicleSizesForProvider(String provider) { return vehicles.getSizesForProvider(provider); }
    public List<Integer> getDispatchHours(String provider) { return vehicles.getDispatchHours(provider); }
    public void setProviderDispatchHours(String provider, List<Integer> hours) { vehicles.setDispatchHours(provider, hours); }
    public void clearProviderDispatchHours() { vehicles.clearDispatchHours(); }
    public void setCepVehicleSizes(List<String> sizes) { vehicles.setActiveSizes(sizes); }
    public void setProviderVehicleSizes(String provider, List<String> sizes) { vehicles.setProviderSizes(provider, sizes); }
    public void clearProviderVehicleSizes() { vehicles.clearProviderSizes(); }

    // --- Routing ---
    public int getMaxRouteDuration() { return routing.getMaxRouteDurationSeconds(); }
    public int getDurationPerParcel() { return routing.getDurationPerParcelMinutes(); }
    public int getMaxDurationPerStop() { return routing.getMaxDurationPerStopMinutes(); }
    public double getDeliveryTimeWindowStart() { return routing.getDeliveryTimeWindow().getStart(); }
    public double getDeliveryTimeWindowEnd() { return routing.getDeliveryTimeWindow().getEnd(); }
    public int getDHLBorder() { return routing.getDhlBorder(); }

    // --- Dispatch Hours (legacy) — when LMD vehicles can START their tours ---
    // NOT the delivery time window for TW penalty scoring (that's in RoutingConfig).
    private final Map<String, int[]> deliveryHours = new LinkedHashMap<>();
    {
        deliveryHours.put("default", new int[]{7, 14});
    }

    public int getDeliveryStartTime(String provider) {
        int[] hours = deliveryHours.getOrDefault(provider.toLowerCase(), deliveryHours.get("default"));
        return hours != null ? hours[0] : 7;
    }

    public int getDeliveryEndTime(String provider) {
        int[] hours = deliveryHours.getOrDefault(provider.toLowerCase(), deliveryHours.get("default"));
        return hours != null ? hours[1] : 14;
    }

    public void setDeliveryHours(String provider, int startHour, int endHour) {
        deliveryHours.put(provider.toLowerCase(), new int[]{startHour, endHour});
    }

    public void setDefaultDeliveryHours(int startHour, int endHour) {
        setDeliveryHours("default", startHour, endHour);
    }

    // --- Supply ---
    public int getSupplyVehCap() { return supply.getVehicleCapacity(); }
    public int getSupplyDurationPerStop() { return supply.getDurationPerStopSeconds(); }
    public int getMinSupplySplitDemand() { return supply.getMinSplitDemand(); }
    public Map<String, String> getSupplyLinkDirections() { return supply.getLinkDirections(); }
    public Map<String, Double> getSupplyDirectionProbabilities() { return supply.getDirectionProbabilities(); }

    // --- Hubs ---
    public int getHubLimitDHL() { return hubs.getLimitDHL(); }
    public int getHubLimitPost() { return hubs.getLimitPost(); }
    public int getParcelLockerDemand() { return hubs.getParcelLockerDemand(); }
    public int getParcelLockerDuration() { return hubs.getParcelLockerDurationMinutes(); }

    // --- Network ---
    public double getMinLinkLength() { return network.getMinLinkLengthMeters(); }
    public double getMinFreeSpeed() { return network.getMinFreeSpeedMps(); }
    public double getFreeSpeedThreshold() { return network.getFreeSpeedThresholdMps(); }

    // --- Study area ---
    public StudyArea getStudyArea() { return studyArea; }

    /** Sets the study area, rebuilds the path resolver, and re-derives input paths.
     * If a run was already initialized (i.e. {@link #setSimulationDate(LocalDate)} was called),
     * the existing run id is re-applied to the new path resolver so that output-path getters
     * keep returning valid, run-scoped paths. */
    public void setStudyArea(StudyArea studyArea) {
        this.studyArea = studyArea;
        this.hagridPaths = new HagridPaths(studyArea);
        if (this.runId != null) {
            this.hagridPaths.initializeRun(this.runId);
        }
        deriveInputPaths();
    }

    /** ScenarioRunner entry point: parse a study-area name (case-insensitive). */
    public void setStudyAreaAsString(String studyArea) {
        setStudyArea(StudyArea.valueOf(studyArea.trim().toUpperCase()));
    }

    // --- Scenario setters for ScenarioRunner ---
    public void setConcept(String concept) {
        this.scenario = Scenario.valueOf(concept.toUpperCase());
        providers.initializeDefaultRates(scenario);
    }

    public void setFilterRegionsAsString(String filterRegions) {
        this.filterRegions = Arrays.stream(filterRegions.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .map(Region::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public String toString() {
        return String.format("HagridConfig[scenario=%s, runId=%s, tag=%s, date=%s, vehicleSizes=%s]",
            scenario, runId, tag, simulationDate, vehicles.getActiveSizes());
    }
}
