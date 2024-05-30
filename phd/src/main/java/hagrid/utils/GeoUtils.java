package hagrid.utils;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import hagrid.demand.Delivery;

/**
 * Utility class for coordinate transformations.
 */
public class GeoUtils {

    private static final Logger LOGGER = LogManager.getLogger(GeoUtils.class);

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
