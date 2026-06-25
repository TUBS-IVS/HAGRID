package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LmdDemandReader.group")
class LmdDemandReaderTest {

    private SimpleFeatureType type() {
        SimpleFeatureTypeBuilder b = new SimpleFeatureTypeBuilder();
        b.setName("demand");
        b.add("the_geom", Point.class);
        b.add("id", Long.class);
        b.add("postal_cod", String.class);
        b.add("dhl_tag", Long.class);
        b.add("dhl_type", Long.class);
        b.add("hermes_tag", Long.class);
        b.add("hermes_type", Long.class);
        return b.buildFeatureType();
    }

    private SimpleFeature feature(SimpleFeatureType t, long id, double x, double y,
                                  long dhlB2c, long dhlB2b, long herB2c) {
        GeometryFactory gf = new GeometryFactory();
        Point p = gf.createPoint(new org.locationtech.jts.geom.Coordinate(x, y));
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(t);
        fb.add(p);
        fb.add(id);
        fb.add("02977");
        fb.add(dhlB2c);
        fb.add(dhlB2b);
        fb.add(herB2c);
        fb.add(0L);
        return fb.buildFeature(String.valueOf(id));
    }

    @Test
    @DisplayName("groups deliveries by provider, splitting B2B and B2C")
    void groupsByProvider() {
        SimpleFeatureType t = type();
        List<SimpleFeature> features = List.of(
                feature(t, 1, 100, 100, 5, 2, 3),   // dhl 5 B2C + 2 B2B, hermes 3 B2C
                feature(t, 2, 200, 200, 0, 0, 4));  // hermes 4 B2C only

        Map<String, List<Delivery>> grouped = LmdDemandReader.group(features);

        assertThat(grouped).containsOnlyKeys("dhl", "hermes");
        // dhl: one B2C delivery (5) + one B2B delivery (2) from feature 1
        assertThat(grouped.get("dhl")).hasSize(2);
        assertThat(grouped.get("dhl")).anyMatch(
                d -> d.getParcelType() == Delivery.ParcelType.B2B && d.getAmount() == 2);
        assertThat(grouped.get("dhl")).anyMatch(
                d -> d.getParcelType() == Delivery.ParcelType.B2C && d.getAmount() == 5);
        // hermes: B2C 3 (feature 1) + B2C 4 (feature 2)
        assertThat(grouped.get("hermes")).hasSize(2);
        assertThat(grouped.get("hermes")).allMatch(d -> d.getParcelType() == Delivery.ParcelType.B2C);
        assertThat(grouped.get("hermes")).allMatch(d -> d.getDeliveryMode() == Delivery.DeliveryMode.HOME);
    }
}
