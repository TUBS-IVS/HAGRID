package hagrid.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

/**
 * Utility class for coordinate transformations.
 */
public class GeoUtils {

    private static final Logger LOGGER = LogManager.getLogger(GeoUtils.class);

    /**
     * Transforms coordinates to the desired EPSG:25832 format if they are not already in that format.
     *
     * @param coordinates The coordinates to be transformed.
     * @return The transformed coordinates in EPSG:25832 format.
     */
    public static Coord transformIfNeeded(Coord coordinates) {
        double x = coordinates.getX();
        double y = coordinates.getY();

        // Heuristic check if coordinates are likely in WGS84 (longitude and latitude)
        if (x < 100 || y < 100) {
            CoordinateTransformation transformation = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, "EPSG:25832");
            coordinates = transformation.transform(coordinates);
        } else if (x < 250000 || x > 850000 || y < 5100000 || y > 6100000) {
            // Check if coordinates are outside the typical range for EPSG:25832
            LOGGER.warn("Coordinates are outside the typical range for EPSG:25832. Manual verification may be required.");
        }

        return coordinates;
    }
}
