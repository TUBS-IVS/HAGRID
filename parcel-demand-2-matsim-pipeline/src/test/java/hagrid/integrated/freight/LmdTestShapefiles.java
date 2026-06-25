package hagrid.integrated.freight;

import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.core.utils.gis.GeoFileWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes tiny point demand shapefiles for LMD tests (columns match the PANDA schema subset). */
final class LmdTestShapefiles {
    private LmdTestShapefiles() {}

    static void writeDemand(Path shp, double[][] xy, long[] dhlTag, long[] dhlType, long[] hermesTag) {
        SimpleFeatureTypeBuilder tb = new SimpleFeatureTypeBuilder();
        tb.setName("demand");
        try {
            tb.setCRS(org.geotools.referencing.CRS.decode("EPSG:25832"));
        } catch (Exception ignored) {
            // CRS is metadata-only in this synthetic test; safe to leave unset if HSQL is unavailable
        }
        tb.add("the_geom", Point.class);
        tb.add("id", Long.class);
        tb.add("postal_cod", String.class);
        tb.add("dhl_tag", Long.class);
        tb.add("dhl_type", Long.class);
        tb.add("hermes_tag", Long.class);
        tb.add("hermes_type", Long.class);
        SimpleFeatureType type = tb.buildFeatureType();

        GeometryFactory gf = new GeometryFactory();
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(type);
        List<SimpleFeature> features = new ArrayList<>();
        for (int i = 0; i < xy.length; i++) {
            Point p = gf.createPoint(new Coordinate(xy[i][0], xy[i][1]));
            fb.add(p);
            fb.add((long) (i + 1));
            fb.add("02977");
            fb.add(dhlTag[i]);
            fb.add(dhlType[i]);
            fb.add(hermesTag[i]);
            fb.add(0L);
            features.add(fb.buildFeature(String.valueOf(i + 1)));
        }
        // NOTE: the repo's GeoFileWriter has writeGeometries (not writeFeatures)
        GeoFileWriter.writeGeometries(features, shp.toString());
    }
}
