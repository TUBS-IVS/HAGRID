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

import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.Point;

import hagrid.HagridConfigGroup;

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
@Singleton
public class DemandProcessor implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(DemandProcessor.class);

    @Inject
    private Scenario scenario;

    @Inject
    private HagridConfigGroup hagridConfig;

    @Override
    public void run() {
        try {
            LOGGER.info("Reading freight demand data from file: {}", hagridConfig.getFreightDemandPath());
            Collection<SimpleFeature> freightFeatures = readFreightDemandData(hagridConfig.getFreightDemandPath());

            // Process the freight demand data
            Map<String, List<SimpleFeature>> carrierDemand = sortCarrierDemandSameSizeKMeans(freightFeatures);

            // Store data in scenario
            scenario.addScenarioElement("carrierDemand", carrierDemand);

            LOGGER.info("Freight demand data processing completed.");
        } catch (Exception e) {
            LOGGER.error("Error reading freight demand data", e);
        }
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
    private Map<String, List<SimpleFeature>> sortCarrierDemandSameSizeKMeans(Collection<SimpleFeature> freightFeatures) {

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
                .filter(feature -> (Long) feature.getAttribute("total") <= 1500)
                .filter(feature -> !((String) feature.getAttribute("postal_cod")).isEmpty())
                .flatMap(feature -> hagridConfig.getShpProviders().stream()
                        .map(provider -> new AbstractMap.SimpleEntry<>(
                                provider.replace("_tag", "") + "_" + (String) feature.getAttribute("postal_cod"),
                                feature)))
                .filter(entry -> (Long) entry.getValue().getAttribute(entry.getKey().split("_")[0] + "_tag") > 0)
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    /**
     * Logs the number of deliveries and parcels for each carrier.
     *
     * @param carrierDemand The carrier demand map to log.
     * @return A map containing the total number of deliveries and parcels.
     */
    private Map<String, Long> logDeliveries(Map<String, List<SimpleFeature>> carrierDemand) {
        long totalDeliveries = 0;
        long totalParcels = 0;

        for (Map.Entry<String, List<SimpleFeature>> entry : carrierDemand.entrySet()) {
            long numberOfDeliveries = entry.getValue().stream()
                    .mapToLong(
                            feature -> (Long) feature.getAttribute(entry.getKey().split("_")[0] + "_tag") > 0 ? 1 : 0)
                    .sum();
            long numberOfParcels = entry.getValue().stream()
                    .mapToLong(feature -> (Long) feature.getAttribute(entry.getKey().split("_")[0] + "_tag"))
                    .sum();

            totalDeliveries += numberOfDeliveries;
            totalParcels += numberOfParcels;
        }

        LOGGER.info("Total Number of Deliveries: {}", totalDeliveries);
        LOGGER.info("Total Number of Parcels: {}", totalParcels);

        Map<String, Long> totals = new HashMap<>();
        totals.put("totalDeliveries", totalDeliveries);
        totals.put("totalParcels", totalParcels);
        return totals;
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
        LOGGER.info("Demand Border: {}", hagridConfig.getDemandBorder());
        carrierDemandNeedForSplit.forEach((key, demand) -> {
            long deliveries = demand.stream()
                    .mapToLong(feature -> (Long) feature.getAttribute(key.split("_")[0] + "_tag") > 0 ? 1 : 0)
                    .sum();
            int toSplit = (int) Math.ceil(deliveries / (double) hagridConfig.getDemandBorder());

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
                .map(feature -> ((MultiPoint) feature.getAttribute(0)).getCentroid())
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

        SameSizeKMeans<NumberVector> kMeans = new SameSizeKMeans<>(SquaredEuclideanDistance.STATIC, toSplit, 100,
                new RandomUniformGenerated(RandomFactory.DEFAULT));
        Clustering<MeanModel> clustering = kMeans.autorun(database);

        List<List<SimpleFeature>> clusterLists = new ArrayList<>();
        for (Cluster<MeanModel> cluster : clustering.getAllClusters()) {
            List<SimpleFeature> clusterFeatures = new ArrayList<>(cluster.size());
            for (DBIDIter iter = cluster.getIDs().iter(); iter.valid(); iter.advance()) {
                int offset = ids.getOffset(iter);
                clusterFeatures.add(features.get(offset));
            }
            clusterLists.add(clusterFeatures);
            LOGGER.info("Cluster {}: {} features", clusterLists.size(), clusterFeatures.size());
        }

        LOGGER.info("Total number of clusters created: {}", clusterLists.size());

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
                Point point = ((MultiPoint) feature.getAttribute(0)).getCentroid();
                xData.add(point.getX());
                yData.add(point.getY());
            }

            org.knowm.xchart.XYSeries series = chart.addSeries("Cluster " + clusterLists.indexOf(clusterFeatures),
                    xData, yData);
            series.setMarker(markers.get(clusterLists.indexOf(clusterFeatures) % markers.size()));
        }

        // Create output directory if it doesn't exist
        File outputDir = new File("phd/output/demand_clustering/");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Save chart to a file:
        try {
            BitmapEncoder.saveBitmap(chart, "phd/output/demand_clustering/" + fileName, BitmapEncoder.BitmapFormat.PNG);
            LOGGER.info("Chart saved successfully at: phd/output/demand_clustering/{}.png", fileName);
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
