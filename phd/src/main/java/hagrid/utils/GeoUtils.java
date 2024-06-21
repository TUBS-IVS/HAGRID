package hagrid.utils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import hagrid.utils.demand.Delivery;
import hagrid.utils.demand.Hub;
import hagrid.utils.general.Region;

/**
 * Utility class for coordinate transformations.
 */
public class GeoUtils {

    private static final Logger LOGGER = LogManager.getLogger(GeoUtils.class);

    // Define the postal codes for each region
    private static final Map<Region, Set<String>> regionPostalCodes = new HashMap<>();

    static {
        regionPostalCodes.put(Region.BARSINGHAUSEN, Set.of("30890"));
        regionPostalCodes.put(Region.BURGDORF, Set.of("31303"));
        regionPostalCodes.put(Region.BURGWEDEL, Set.of("30938"));
        regionPostalCodes.put(Region.GARBSEN, Set.of("30823", "30826", "30827"));
        regionPostalCodes.put(Region.GEHRDEN, Set.of("30989"));
        regionPostalCodes.put(Region.HANNOVER,
                Set.of("30159", "30161", "30163", "30165", "30167", "30169", "30171", "30173", "30175", "30177",
                        "30179", "30419", "30449", "30451", "30453", "30455", "30457", "30459", "30519", "30521",
                        "30539", "30559", "30625", "30627", "30629", "30655", "30657", "30659", "30669"));
        regionPostalCodes.put(Region.HEMMINGEN, Set.of("30966"));
        regionPostalCodes.put(Region.ISERNHAGEN, Set.of("30916"));
        regionPostalCodes.put(Region.LAATZEN, Set.of("30880"));
        regionPostalCodes.put(Region.LANGENHAGEN, Set.of("30851", "30853", "30855"));
        regionPostalCodes.put(Region.LEHRTE, Set.of("31275"));
        regionPostalCodes.put(Region.NEUSTADT, Set.of("31535"));
        regionPostalCodes.put(Region.PATTENSEN, Set.of("30982"));
        regionPostalCodes.put(Region.RONNENBERG, Set.of("30952"));
        regionPostalCodes.put(Region.SEELZE, Set.of("30926"));
        regionPostalCodes.put(Region.SEHNDE, Set.of("31319"));
        regionPostalCodes.put(Region.SPRINGE, Set.of("31832"));
        regionPostalCodes.put(Region.UETZE, Set.of("31311"));
        regionPostalCodes.put(Region.WEDEMARK, Set.of("30900"));
        regionPostalCodes.put(Region.WENNIGSEN, Set.of("30974"));
        regionPostalCodes.put(Region.WUNSTORF, Set.of("31515"));
    }

    /**
     * Filters the hubs by the specified regions.
     *
     * @param hubs           The map of Hub objects representing the hubs.
     * @param hanoverGeoData The collection of SimpleFeature representing Hanover
     *                       GeoData.
     * @param regions        The list of regions to filter by.
     * @return A filtered map of Hub objects.
     */
    public static Map<Id<Hub>, Hub> filterHubsByRegions(Map<Id<Hub>, Hub> hubs,
            Collection<SimpleFeature> hanoverGeoData, Set<Region> regions) {
        // If 'ALL' region is specified, return the unfiltered map
        if (regions.contains(Region.ALL)) {
            LOGGER.info("No filtering applied. 'ALL' region specified. Returning all hubs.");
            return hubs;
        }

        int originalSize = hubs.size();
        LOGGER.info("Original number of hubs: {}", originalSize);

        // Log the regions being used for filtering
        String regionsList = regions.stream()
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        LOGGER.info("Filtering hubs by regions: {}", regionsList);

        // Filter hubs by specified regions
        GeometryFactory geometryFactory = new GeometryFactory();
        Map<Id<Hub>, Hub> filteredHubs = hubs.entrySet().stream()
                .filter(entry -> {
                    Hub hub = entry.getValue();
                    Point hubPoint = geometryFactory
                            .createPoint(new Coordinate(hub.getCoord().getX(), hub.getCoord().getY()));
                    for (Region region : regions) {
                        MultiPolygon regionGeometry = getRegionGeometry(hanoverGeoData, region);
                        if (regionGeometry.contains(hubPoint)) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        int filteredSize = filteredHubs.size();
        LOGGER.info("Filtered number of hubs: {}", filteredSize);
        LOGGER.info("Number of hubs removed: {}", originalSize - filteredSize);

        return filteredHubs;
    }

    /**
     * Filters the freight demand data by the specified regions.
     *
     * @param features       The collection of SimpleFeature representing the
     *                       freight demand data.
     * @param hanoverGeoData The collection of SimpleFeature representing Hanover
     *                       GeoData.
     * @param set            The list of regions to filter by.
     * @return A filtered collection of SimpleFeature representing the freight
     *         demand data.
     */
    public static Collection<SimpleFeature> filterFeaturesByRegions(Collection<SimpleFeature> features,
            Collection<SimpleFeature> hanoverGeoData, Set<Region> set) {
        // If 'ALL' region is specified, return the unfiltered list

        if (set.contains(Region.ALL)) {
            LOGGER.info("No filtering applied. 'ALL' region specified. Returning all features.");
            return features;
        }

        int originalSize = features.size();
        LOGGER.info("Original number of freight features: {}", originalSize);

        // Log the regions being used for filtering
        String regionsList = set.stream()
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        LOGGER.info("Filtering freight features by regions: {}", regionsList);

        // Filter features by specified regions
        Set<Region> finalRegions = set;
        Collection<SimpleFeature> filteredFeatures = features.stream()
                .filter(feature -> {
                    for (Region region : finalRegions) {
                        MultiPolygon regionGeometry = getRegionGeometry(hanoverGeoData, region);
                        if (regionGeometry.contains((Geometry) feature.getDefaultGeometry())) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());

        int filteredSize = filteredFeatures.size();
        LOGGER.info("Filtered number of freight features: {}", filteredSize);
        LOGGER.info("Number of features removed: {}", originalSize - filteredSize);

        /**
         * This filter is necessary because the shapes do not match exactly, and
         * otherwise,
         * deliveries would remain in the model with postal codes outside of Hanover but
         * allegedly located within the Hanover shape. This discrepancy is due to
         * inaccurate
         * input data. While filtering by postal codes alone might suffice, this
         * additional
         * check ensures that all deliveries are indeed within the specified area.
         */

        filteredFeatures = GeoUtils.filterFeaturesByPostalCodes(filteredFeatures, set);

        return filteredFeatures;
    }

    /**
     * Filters the features based on the postal codes for the specified regions.
     *
     * @param features The collection of SimpleFeature representing the freight
     *                 demand data.
     * @param regions  The set of regions to filter by.
     * @return A filtered collection of SimpleFeature based on postal codes.
     */
    private static Collection<SimpleFeature> filterFeaturesByPostalCodes(Collection<SimpleFeature> features,
            Set<Region> regions) {
        int originalSize = features.size();

        Collection<SimpleFeature> filteredFeatures = features.stream()
                .filter(feature -> {
                    String postalCode = (String) feature.getAttribute("postal_cod");
                    for (Region region : regions) {
                        if (regionPostalCodes.get(region).contains(postalCode)) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());

        int filteredSize = filteredFeatures.size();
        LOGGER.info("Filtered number of freight features by postal codes: {}", filteredSize);
        LOGGER.info("Number of features removed by postal codes: {}", originalSize - filteredSize);

        return filteredFeatures;
    }

    /**
     * Gets the geometry of the specified region from the Hanover GeoData.
     *
     * @param hanoverGeoData The collection of SimpleFeature representing Hanover
     *                       GeoData.
     * @param region         The region for which the geometry is to be retrieved.
     * @return The MultiPolygon geometry of the specified region.
     */
    private static MultiPolygon getRegionGeometry(Collection<SimpleFeature> hanoverGeoData, Region region) {
        for (SimpleFeature feature : hanoverGeoData) {
            if (region.name().equalsIgnoreCase((String) feature.getAttribute("NAME_3"))) {
                return (MultiPolygon) feature.getDefaultGeometry();
            }
        }
        throw new IllegalArgumentException("Region " + region + " not found in Hanover GeoData.");
    }

    /**
     * Transforms coordinates to the desired EPSG:25832 format if they are not
     * already in that format.
     *
     * @param coordinates The coordinates to be transformed.
     * @return The transformed coordinates in EPSG:25832 format.
     */
    public static Coord transformIfNeeded(Coord coordinates) {
        double x = coordinates.getX();
        double y = coordinates.getY();

        // Heuristic check if coordinates are likely in WGS84 (longitude and latitude)
        if (x < 100 || y < 100) {
            CoordinateTransformation transformation = TransformationFactory
                    .getCoordinateTransformation(TransformationFactory.WGS84, "EPSG:25832");
            coordinates = transformation.transform(coordinates);
        } else if (x < 250000 || x > 850000 || y < 5100000 || y > 6100000) {
            // Check if coordinates are outside the typical range for EPSG:25832
            LOGGER.warn(
                    "Coordinates are outside the typical range for EPSG:25832. Manual verification may be required.");
        }

        return coordinates;
    }

    /**
     * Finds the closest delivery to the specified coordinate from a list of
     * deliveries.
     *
     * @param deliveries List of Delivery objects to search.
     * @param coord      The coordinate to compare distances.
     * @return The Delivery object closest to the specified coordinate.
     * @throws IllegalArgumentException if the list of deliveries is empty.
     */
    public static Delivery findClosestDeliveryToCoord(List<Delivery> deliveries, Coord coord) {
        if (deliveries.isEmpty()) {
            throw new IllegalArgumentException("The list of deliveries is empty.");
        }

        return deliveries.stream()
                .min((d1, d2) -> {
                    double dist1 = calculateDistance(d1.getCoordinate(), coord);
                    double dist2 = calculateDistance(d2.getCoordinate(), coord);
                    return Double.compare(dist1, dist2);
                })
                .orElseThrow(() -> new IllegalArgumentException("No deliveries found."));
    }

    /**
     * Calculates the Euclidean distance between two coordinates.
     *
     * @param coord1 The first coordinate.
     * @param coord2 The second coordinate.
     * @return The Euclidean distance between the two coordinates.
     */
    private static double calculateDistance(Coord coord1, Coord coord2) {
        double dx = coord1.getX() - coord2.getX();
        double dy = coord1.getY() - coord2.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
