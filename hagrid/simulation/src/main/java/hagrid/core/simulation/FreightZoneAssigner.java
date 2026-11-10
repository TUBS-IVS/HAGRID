package hagrid.core.simulation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;

/** Tags the common network once, preserving imported zones and the legacy spatial priority. */
final class FreightZoneAssigner {
    private static final Logger LOGGER = LogManager.getLogger(FreightZoneAssigner.class);
    private FreightZoneAssigner() {}

    static void assign(Network network, Collection<SimpleFeature> features) {
        if (features == null || features.isEmpty()) {
            LOGGER.warn("No zone features provided. Skipping zone assignment.");
            return;
        }
        Instant start = Instant.now();
        int featureCount = features.size();
        int linkCount = network.getLinks().size();
        LOGGER.info("Assigning freight zones using {} zone feature(s) for {} network links", featureCount, linkCount);

        PreparedGeometryFactory prepFactory = new PreparedGeometryFactory();
        GeometryFactory geometryFactory = new GeometryFactory();
        STRtree spatialIndex = new STRtree();

        record ZoneGeometry(int zoneId, PreparedGeometry geometry, double centerX, double centerY, int inputOrder) {}

        int indexedZones = 0;

        for (SimpleFeature feature : features) {
            Geometry geometry = (Geometry) feature.getAttribute(0);
            if (geometry == null || geometry.isEmpty()) {
                continue;
            }
            Number zoneNumber = (Number) feature.getAttribute("NO");
            if (zoneNumber == null) {
                continue;
            }
            long zoneIdLong = zoneNumber.longValue();
            if (zoneIdLong > Integer.MAX_VALUE) {
                LOGGER.warn("Zone id {} exceeds supported Integer range. Skipping feature {}", zoneIdLong,
                        feature.getID());
                continue;
            }
            int zoneId = (int) zoneIdLong;
            PreparedGeometry prepared = prepFactory.create(geometry);
            Envelope bounds = geometry.getEnvelopeInternal();
            spatialIndex.insert(bounds, new ZoneGeometry(zoneId, prepared,
                    (bounds.getMinX() + bounds.getMaxX()) / 2,
                    (bounds.getMinY() + bounds.getMaxY()) / 2, indexedZones));
            indexedZones++;
        }

        spatialIndex.build();
        // The old single-leaf index visited zones by center Y, then X, then input order.
        // Preserve that tie priority even though the bounded index now has several levels.
        var legacyPriority = Comparator.comparingDouble(ZoneGeometry::centerY)
                .thenComparingDouble(ZoneGeometry::centerX).thenComparingInt(ZoneGeometry::inputOrder);
        LOGGER.info("Spatial index prepared for {} unique zone geometries", indexedZones);

        int alreadyTagged = 0;
        int assignedCount = 0;
        for (Link link : network.getLinks().values()) {
            Object existingZone = link.getAttributes().getAttribute("zone");
            if (existingZone != null) {
                Integer normalized = normalizeZone(existingZone);
                if (normalized != null) {
                    link.getAttributes().putAttribute("zone", normalized);
                }
                alreadyTagged++;
                continue;
            }

            Coord[] coords = {
                    link.getCoord(),
                    link.getFromNode().getCoord(),
                    link.getToNode().getCoord()
            };

            boolean linkAssigned = false;
            for (Coord coord : coords) {
                if (coord == null) {
                    continue;
                }
                Point point = geometryFactory.createPoint(new org.locationtech.jts.geom.Coordinate(coord.getX(), coord.getY()));
                Envelope queryEnv = point.getEnvelopeInternal();
                @SuppressWarnings("unchecked")
                java.util.List<ZoneGeometry> candidates = spatialIndex.query(queryEnv);
                candidates.sort(legacyPriority);
                for (ZoneGeometry zoneGeometry : candidates) {
                    if (zoneGeometry.geometry().contains(point)) {
                        link.getAttributes().putAttribute("zone", zoneGeometry.zoneId());
                        assignedCount++;
                        linkAssigned = true;
                        break;
                    }
                }
                if (linkAssigned) {
                    break;
                }
            }
        }

        Duration elapsed = Duration.between(start, Instant.now());
        LOGGER.info("Zone assignment finished: {} links newly tagged, {} links already had zones ({} ms)",
                assignedCount, alreadyTagged, elapsed.toMillis());
    }

    private static Integer normalizeZone(Object value) {
        if (value instanceof Number number) {
            long longValue = number.longValue();
            if (longValue > Integer.MAX_VALUE) {
                LOGGER.warn("Zone id {} exceeds supported Integer range; ignoring.", longValue);
                return null;
            }
            return (int) longValue;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                long parsed = Long.parseLong(trimmed);
                if (parsed > Integer.MAX_VALUE) {
                    LOGGER.warn("Zone id {} exceeds supported Integer range; ignoring.", parsed);
                    return null;
                }
                return (int) parsed;
            } catch (NumberFormatException ex) {
                LOGGER.warn("Unable to parse zone attribute '{}' as integer", trimmed, ex);
                return null;
            }
        }
        return null;
    }
}
