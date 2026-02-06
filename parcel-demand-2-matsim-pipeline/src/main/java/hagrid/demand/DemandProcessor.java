package hagrid.demand;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.style.markers.Circle;
import org.knowm.xchart.style.markers.Diamond;
import org.knowm.xchart.style.markers.Marker;
import org.knowm.xchart.style.markers.Square;
import org.knowm.xchart.style.markers.TriangleDown;
import org.knowm.xchart.style.markers.TriangleUp;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.utils.gis.GeoFileReader;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;

import hagrid.HagridConfig;
import hagrid.utils.GeoUtils;
import hagrid.utils.demand.SameSizeKMeans;
import hagrid.utils.general.HAGRIDUtils;
import hagrid.utils.general.Region;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import elki.clustering.kmeans.initialization.RandomUniformGenerated;
import elki.data.Cluster;
import elki.data.Clustering;
import elki.data.NumberVector;
import elki.data.model.MeanModel;
import elki.data.type.TypeUtil;
import elki.database.Database;
import elki.database.StaticArrayDatabase;
import elki.database.ids.DBIDIter;
import elki.database.ids.DBIDRange;
import elki.database.relation.Relation;
import elki.datasource.ArrayAdapterDatabaseConnection;
import elki.datasource.DatabaseConnection;
import elki.distance.minkowski.SquaredEuclideanDistance;
import elki.utilities.random.RandomFactory;

/**
 * The DemandProcessor class is responsible for reading freight demand data
 * from a shapefile and processing it.
 */
// @Singleton
public class DemandProcessor implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(DemandProcessor.class);

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfig hagridConfig;

    @Override
    public void run() {
        try {
            LOGGER.info("Reading freight demand data from file: {}", hagridConfig.getFreightDemandPath());
            Collection<SimpleFeature> freightFeatures = readFreightDemandData(hagridConfig.getFreightDemandPath());
            logRawParcelsPerProvider(freightFeatures);
            // Read Hanover GeoData from scenario
            Collection<SimpleFeature> hanoverGeoData = HAGRIDUtils.getScenarioElementAs("hanoverGeoData", scenario);

            // Filter the freight demand data by regions definied in the configuration
            Collection<SimpleFeature> filteredFreightFeatures = GeoUtils.filterFeaturesByRegions(freightFeatures,
                    hanoverGeoData,
                    hagridConfig.getFilterRegions());

            // Process the freight demand data
            Map<String, List<SimpleFeature>> carrierDemand = sortCarrierDemandSameSizeKMeans(filteredFreightFeatures);

            // Store data in scenario
            scenario.addScenarioElement("carrierDemand", carrierDemand);

            LOGGER.info("Freight demand data processing completed.");
        } catch (Exception e) {
            LOGGER.error("Error reading freight demand data", e);
        }
    }

    /**
     * Logs parcel volumes per provider directly from raw freightFeatures.
     * Uses "<provider>_tag" (B2C) and "<provider>_type"/"_typ" (B2B).
     * Attribute names are truncated to 10 characters for Shapefile safety.
     *
     * @param features collection of freight demand features
     */
    private void logRawParcelsPerProvider(Collection<SimpleFeature> features) {
        final String[] providers = { "amazon", "dhl", "dpd", "fedex", "gls", "hermes", "ups" };

        long grandB2B = 0L;
        long grandB2C = 0L;

        LOGGER.info("Raw freightFeatures validation (per provider):");

        for (String prov : providers) {
            String attrTag = safe10(prov + "_tag");
            String attrType = safe10(prov + "_type");
            String attrTyp = safe10(prov + "_typ"); // fallback

            long sumB2C = 0L;
            long sumB2B = 0L;

            for (SimpleFeature f : features) {
                long b2c = asLongSafe(f.getAttribute(attrTag));

                Long b2bVal = asLongNullable(f.getAttribute(attrType));
                if (b2bVal == null) {
                    b2bVal = asLongNullable(f.getAttribute(attrTyp));
                }
                long b2b = (b2bVal == null ? 0L : b2bVal);

                sumB2C += b2c;
                sumB2B += b2b;
            }

            long total = sumB2C + sumB2B;
            grandB2C += sumB2C;
            grandB2B += sumB2B;

            LOGGER.info("  {} | B2B: {} | B2C: {} | Total: {}",
                    prov,
                    String.format("%,d", sumB2B),
                    String.format("%,d", sumB2C),
                    String.format("%,d", total));
        }

        long grandTotal = grandB2B + grandB2C;
        LOGGER.info("TOTAL | B2B: {} | B2C: {} | Total: {}",
                String.format("%,d", grandB2B),
                String.format("%,d", grandB2C),
                String.format("%,d", grandTotal));
    }

    private Collection<SimpleFeature> readFreightDemandData(String filename) throws Exception {
        return new GeoFileReader().readFileAndInitialize(filename);
    }

    /**
     * This method processes freight demand data to sort carrier demands using the
     * KMeans clustering algorithm.
     * It splits demands that exceed a certain threshold into smaller groups for
     * better management.
     *
     * @param freightFeatures Collection of SimpleFeature representing freight
     *                        demand data.
     * @return Map of carrier demands.
     */
    private Map<String, List<SimpleFeature>> sortCarrierDemandSameSizeKMeans(
            Collection<SimpleFeature> freightFeatures) {

        // Step 1: Filter and group features by provider and postal code
        Map<String, List<SimpleFeature>> carrierDemand = groupFeaturesByProviderAndPostalCode(freightFeatures);

        // Step 2: Log the total number of delivery points (deliveries) and parcels
        // before processing
        Map<String, Long> initialTotals = logDeliveries(carrierDemand);

        // Step 3: Identify carrier demands that need splitting based on the number of
        // delivery points
        Map<String, List<SimpleFeature>> carrierDemandNeedForSplit = identifyCarrierDemandNeedForSplit(carrierDemand);

        // Step 4: Use KMeans clustering to split carrier demands that have too many
        // delivery points
        processCarrierDemandNeedForSplitWithKMeans(carrierDemand, carrierDemandNeedForSplit);

        // Step 5: Validate that the total number of deliveries and parcels remains
        // consistent and Return the carrier demand map
        return validateDeliveriesAndParcels(initialTotals, carrierDemand);

    }

    /** Nullable version used for fallback checks. */
    private static Long asLongNullable(Object v) {
        if (v == null)
            return null;
        if (v instanceof Long)
            return (Long) v;
        if (v instanceof Integer)
            return ((Integer) v).longValue();
        if (v instanceof Double)
            return Math.round((Double) v);
        if (v instanceof Float)
            return (long) Math.round((Float) v);
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Groups freight features by provider and postal code.
     *
     * @param freightFeatures Collection of SimpleFeature representing freight
     *                        demand data.
     * @return Grouped features by provider and postal code.
     */
    private Map<String, List<SimpleFeature>> groupFeaturesByProviderAndPostalCode(
            Collection<SimpleFeature> freightFeatures) {
        return freightFeatures.stream()

                // Note: In my dissertation project, I filtered out features where 'total' >
                // 1500 to exclude large deliveries.
                // These large DHL deliveries are somewhat ambiguous in the dataset and are
                // likely handled differently.
                // Assumption:
                // - DHL manages these large deliveries separately, possibly due to specific
                // business relationships.
                // - At a 'dhl_total' threshold of 1500, considering DHL's market share, this
                // corresponds to about 800 packages.
                // - This is approximately the threshold where deploying a ~3/4 full 7.5-ton
                // truck becomes viable.
                // Reasoning:
                // - Our estimation methods might incorrectly assign packages to other providers
                // at these points,
                // even though DHL likely has unique, provider-specific delivery relationships
                // there.
                // - These points might represent locations where DHL has significant business
                // clients,
                // resulting in large, concentrated delivery volumes that are not representative
                // of standard CEP services.
                // Adjustment:
                // - In the dissertation, we evaluated 'total' values, which may be challenging
                // for external readers to interpret.
                // - To enhance clarity and realism, we adjust the threshold to (2 * 230) for
                // DHL.
                // - The number 230 represents the approximate capacity of a large delivery van.
                // - Therefore, (2 * 230) equals 460 packages, corresponding to more than two
                // fully loaded vans.
                // - Deliveries exceeding this amount are likely handled directly by trucks and
                // are not typical CEP services.
                // - Direct Delivery by Supply Trucks! Not using the CEP Supply Chain /
                // Warehouse Network.

                // Outcome:
                // - This approach is better to read and more realistic.
                // - There are not many features with 'dhl_total' greater than 1500, so
                // filtering at 1500 or 460 yields similar results.
                // - By filtering DHL deliveries over 460 packages (more than two delivery
                // vans), the package input remains effectively the same.
                // - This adjustment is more realistic and easier to understand for external
                // readers!
                // - Updated: Now using getDynamicDHLBorder() = 2 × max vehicle capacity

                .filter(feature -> (Long) feature.getAttribute("dhl_tag") <= hagridConfig.getDHLBorder())
                .filter(feature -> !((String) feature.getAttribute("postal_cod")).isEmpty())
                .flatMap(feature -> hagridConfig.getShpProviders().stream()
                        .map(provider -> new AbstractMap.SimpleEntry<>(
                                provider.replace("_tag", "") + "_" + (String) feature.getAttribute("postal_cod"),
                                feature)))
                .filter(entry -> {
                    String base = entry.getKey().split("_")[0];
                    long b2c = asLongSafe(entry.getValue().getAttribute(base + "_tag"));
                    Long b2bVal = asLongNullable(entry.getValue().getAttribute(base + "_type"));
                    if (b2bVal == null) {
                        b2bVal = asLongNullable(entry.getValue().getAttribute(base + "_typ"));
                    }
                    long b2b = b2bVal == null ? 0L : b2bVal;
                    return b2c > 0L || b2b > 0L;
                })
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    /**
     * Logs the number of deliveries and parcels for each provider.
     * Uses "<provider>_tag" (B2C) and "<provider>_type"/"_typ" (B2B).
     * Attribute names are truncated to 10 characters for Shapefile safety.
     *
     * @param carrierDemand map of carrier key -> features
     * @return map with total counts for deliveries and parcels
     */
    private Map<String, Long> logDeliveries(Map<String, List<SimpleFeature>> carrierDemand) {
        long totalDeliveries = 0L;
        long totalParcels = 0L;

        for (Map.Entry<String, List<SimpleFeature>> entry : carrierDemand.entrySet()) {
            String base = entry.getKey().split("_")[0]; // e.g. "dhl", "amazon"

            // Shapefile-safe names
            String attrTag = safe10(base + "_tag");
            String attrType = safe10(base + "_type");
            String attrTyp = safe10(base + "_typ"); // fallback

            long deliveries = 0L;
            long parcels = 0L;

            for (SimpleFeature f : entry.getValue()) {
                long b2c = asLongSafe(f.getAttribute(attrTag));
                Long b2bVal = asLongNullable(f.getAttribute(attrType));
                if (b2bVal == null) {
                    b2bVal = asLongNullable(f.getAttribute(attrTyp));
                }
                long b2b = (b2bVal == null ? 0L : b2bVal);

                parcels += (b2c + b2b);

                if (b2c > 0L)
                    deliveries++;
                if (b2b > 0L)
                    deliveries++;
            }

            totalDeliveries += deliveries;
            totalParcels += parcels;

        }

        LOGGER.info("Total Deliveries (all carriers): {}", String.format("%,d", totalDeliveries));
        LOGGER.info("Total Parcels (all carriers): {}", String.format("%,d", totalParcels));

        Map<String, Long> totals = new HashMap<>();
        totals.put("totalDeliveries", totalDeliveries);
        totals.put("totalParcels", totalParcels);

        return totals;
    }

    /** Truncate string to 10 characters (Shapefile attribute name limit). */
    private static String safe10(String s) {
        if (s == null)
            return "";
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    /** Parse various numeric attribute types safely to long. */
    private static long asLongSafe(Object v) {
        if (v == null)
            return 0L;
        if (v instanceof Long)
            return (Long) v;
        if (v instanceof Integer)
            return ((Integer) v).longValue();
        if (v instanceof Double)
            return Math.round((Double) v);
        if (v instanceof Float)
            return Math.round((Float) v);
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Identifies carrier demands that need splitting based on the number of
     * delivery points.
     *
     * @param carrierDemand Grouped features by provider and postal code.
     * @return Carrier demands that need splitting.
     */
    private Map<String, List<SimpleFeature>> identifyCarrierDemandNeedForSplit(
            Map<String, List<SimpleFeature>> carrierDemand) {
        return carrierDemand.entrySet().stream()
                .filter(entry -> {
                    long entryTotalDeliveries = entry.getValue().stream()
                            .mapToLong(feature -> (Long) feature.getAttribute(entry.getKey().split("_")[0] + "_tag") > 0
                                    ? 1
                                    : 0)
                            .sum();
                    // Use demandBorder (default 600) for KMeans clustering split
                    // This is different from minVehicleCapacity which is used for parcel splitting!
                    boolean needForSplit = entryTotalDeliveries > hagridConfig.getDemandBorder();

                    return needForSplit;
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Uses KMeans clustering to split carrier demands that have too many delivery
     * points.
     *
     * @param carrierDemand             Original carrier demand map.
     * @param carrierDemandNeedForSplit Carrier demands that need to be split.
     */
    private void processCarrierDemandNeedForSplitWithKMeans(Map<String, List<SimpleFeature>> carrierDemand,
            Map<String, List<SimpleFeature>> carrierDemandNeedForSplit) {
        // Use demandBorder (default 600) for KMeans clustering split
        // This controls max deliveries per carrier cluster (performance/routing complexity)
        // Different from minVehicleCapacity which controls parcel splitting per vehicle!
        final int demandBorder = hagridConfig.getDemandBorder();
        LOGGER.info("KMeans Demand Border (max deliveries per carrier): {}", demandBorder);
        carrierDemandNeedForSplit.forEach((key, demand) -> {
            long deliveries = demand.stream()
                    .mapToLong(feature -> (Long) feature.getAttribute(key.split("_")[0] + "_tag") > 0 ? 1 : 0)
                    .sum();
            int toSplit = (int) Math.ceil(deliveries / (double) demandBorder);

            LOGGER.info("Need for Split: {}: Number of Deliveries: {}", key, deliveries);

            // Prepare data for k-means clustering
            double[][] dataPoints = prepareDataPoints(demand);
            List<SimpleFeature> features = new ArrayList<>(demand);

            // Perform k-means clustering
            List<List<SimpleFeature>> clusterLists = performKMeansClustering(dataPoints, toSplit, features, key);

            for (int i = 0; i < toSplit; i++) {
                List<SimpleFeature> groupedFeatures = clusterLists.get(i);

                String newKey = key + "_" + i;
                int deliveriesNew = (int) groupedFeatures.stream()
                        .mapToLong(feature -> (Long) feature.getAttribute(key.split("_")[0] + "_tag") > 0 ? 1 : 0)
                        .sum();

                LOGGER.info("Assigned Demand for new Carrier {}: {}", newKey, deliveriesNew);
                carrierDemand.put(newKey, groupedFeatures);
            }

            carrierDemand.remove(key);
        });
    }

    /**
     * Prepares data points for k-means clustering.
     *
     * @param demand List of SimpleFeature representing the demand.
     * @return Array of data points.
     */
    private double[][] prepareDataPoints(List<SimpleFeature> demand) {
        if (demand == null || demand.isEmpty()) {
            throw new IllegalArgumentException("Demand list cannot be null or empty.");
        }

        double[][] dataPoints = demand.stream()
                .map(feature -> ((Point) feature.getAttribute(0)).getCentroid())
                .map(point -> new double[] { point.getX(), point.getY() })
                .toArray(double[][]::new);

        if (dataPoints.length != demand.size()) {
            throw new IllegalStateException("Mismatch between the size of the demand list and the data points array.");
        }

        return dataPoints;
    }

    /**
     * Performs k-means clustering on the provided data points.
     *
     * @param dataPoints Array of data points.
     * @param toSplit    Number of clusters.
     * @param features   List of SimpleFeature representing the features.
     * @param carrierId  Name of the Carrier.
     * @return List of clustered features.
     */
    private List<List<SimpleFeature>> performKMeansClustering(double[][] dataPoints, int toSplit,
            List<SimpleFeature> features, String carrierId) {
        LOGGER.info("Initializing KMeans clustering with {} clusters...", toSplit);

        DatabaseConnection databaseConnection = new ArrayAdapterDatabaseConnection(dataPoints);
        Database database = new StaticArrayDatabase(databaseConnection, null);
        database.initialize();

        Relation<NumberVector> relation = database.getRelation(TypeUtil.NUMBER_VECTOR_FIELD);
        DBIDRange ids = (DBIDRange) relation.getDBIDs();

        // Deterministic seed: combine runId (if available) and carrierId to keep stability across runs
        long seedBase = 0L;
        try { seedBase = (hagridConfig.getRunId() != null ? hagridConfig.getRunId().hashCode() : 0); } catch (Exception ignored) {}
        long seed = Objects.hash(seedBase, carrierId, toSplit);
        RandomFactory seededFactory = RandomFactory.get(seed);
        LOGGER.debug("KMeans deterministic seed for carrier {} -> {}", carrierId, seed);

        SameSizeKMeans<NumberVector> kMeans = new SameSizeKMeans<>(
                SquaredEuclideanDistance.STATIC,
                toSplit,
                100,
                new RandomUniformGenerated(seededFactory));
        Clustering<MeanModel> clustering = kMeans.autorun(database);

        List<List<SimpleFeature>> clusterLists = new ArrayList<>();
        for (Cluster<MeanModel> cluster : clustering.getAllClusters()) {
            List<SimpleFeature> clusterFeatures = new ArrayList<>(cluster.size());
            for (DBIDIter iter = cluster.getIDs().iter(); iter.valid(); iter.advance()) {
                int offset = ids.getOffset(iter);
                clusterFeatures.add(features.get(offset));
            }
            clusterLists.add(clusterFeatures);
            LOGGER.info("Cluster {}: {} features (seed={})", clusterLists.size(), clusterFeatures.size(), seed);
        }

        LOGGER.info("Total number of clusters created: {} (seed={})", clusterLists.size(), seed);

        // Plot and save the cluster results
        plotAndSaveClusterResults(clusterLists, "ClusterResults_" + carrierId);
        return clusterLists;
    }

    /**
     * Plots and saves the cluster results.
     *
     * @param clusterLists List of clustered features.
     * @param fileName     Name of the file to save the chart.
     */
    private void plotAndSaveClusterResults(List<List<SimpleFeature>> clusterLists, String fileName) {
        // Prepare chart:
        XYChart chart = new XYChartBuilder().width(800).height(600).build();
        // Define a list of markers
        List<Marker> markers = Arrays.asList(new Circle(), new Square(), new Diamond(), new TriangleUp(),
                new TriangleDown());
        for (List<SimpleFeature> clusterFeatures : clusterLists) {
            List<Double> xData = new ArrayList<>();
            List<Double> yData = new ArrayList<>();

            for (SimpleFeature feature : clusterFeatures) {
                Point point = ((Point) feature.getAttribute(0)).getCentroid();
                xData.add(point.getX());
                yData.add(point.getY());
            }

            org.knowm.xchart.XYSeries series = chart.addSeries("Cluster " + clusterLists.indexOf(clusterFeatures),
                    xData, yData);
            series.setMarker(markers.get(clusterLists.indexOf(clusterFeatures) % markers.size()));
        }

        // Create output directory if it doesn't exist
        File outputDir = hagridConfig.io().clusteringDir().toFile();
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Save chart to a file:
        try {
            String chartPath = hagridConfig.io().clusteringDir().resolve(fileName).toString();
            BitmapEncoder.saveBitmap(chart, chartPath, BitmapEncoder.BitmapFormat.PNG);
            LOGGER.info("Chart saved: {}", chartPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Validates that the total number of deliveries and parcels remains consistent
     * before and after processing.
     *
     * @param initialTotals The initial totals of deliveries and parcels before
     *                      processing.
     * @param carrierDemand The carrier demand map after processing.
     * @return carrierDemand
     */
    private Map<String, List<SimpleFeature>> validateDeliveriesAndParcels(Map<String, Long> initialTotals,
            Map<String, List<SimpleFeature>> carrierDemand) {
        Map<String, Long> finalTotals = logDeliveries(carrierDemand);

        if (!initialTotals.equals(finalTotals)) {
            throw new IllegalStateException("Total number of deliveries and parcels does not match after processing");
        }

        LOGGER.info("Validation completed successfully: Total number of deliveries and parcels match.");
        return carrierDemand;
    }

}