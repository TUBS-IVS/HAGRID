package hagrid.integrated.freight;

import hagrid.utils.demand.Delivery;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.gis.GeoFileReader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the PANDA parcel-demand shapefile and groups it into one bucket per LSP for the Lausitz
 * LMD baseline. Each segment feature yields, per provider, up to two {@link Delivery} objects:
 * one B2C ({@code <provider>_tag}) and one B2B ({@code <provider>_type}/{@code _typ}). No PLZ
 * grouping, no region filtering, no parcel-locker diversion — every delivery is a HOME stop at
 * the segment point (decision: B2C door-vs-Packstation is a non-question for the segment demand).
 */
public final class LmdDemandReader {

    /** Same seven LSPs as {@link LmdDepotLoader#PROVIDERS}, ordered for stable output. */
    static final String[] PROVIDERS = {"dhl", "amazon", "hermes", "dpd", "gls", "ups", "fedex"};

    private LmdDemandReader() {}

    public static Collection<SimpleFeature> read(String shpPath) {
        try {
            return new GeoFileReader().readFileAndInitialize(shpPath);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read LMD demand shapefile: " + shpPath, e);
        }
    }

    public static Map<String, List<Delivery>> group(Collection<SimpleFeature> features) {
        Map<String, List<Delivery>> grouped = new LinkedHashMap<>();
        for (String p : PROVIDERS) {
            grouped.put(p, new ArrayList<>());
        }

        for (SimpleFeature feature : features) {
            Point point = ((Point) feature.getAttribute(0)).getCentroid();
            Coord coord = new Coord(point.getX(), point.getY());
            String pointId = String.valueOf(feature.getAttribute("id"));
            String postalCode = (String) feature.getAttribute("postal_cod");

            for (String provider : PROVIDERS) {
                long b2c = asLong(feature.getAttribute(safe10(provider + "_tag")));
                Object b2bAttr = feature.getAttribute(safe10(provider + "_type"));
                if (b2bAttr == null) {
                    b2bAttr = feature.getAttribute(safe10(provider + "_typ"));
                }
                long b2b = asLong(b2bAttr);

                if (b2b > 0) {
                    grouped.get(provider).add(delivery(pointId + "_B2B", coord, provider,
                            Delivery.ParcelType.B2B, (int) b2b, postalCode));
                }
                if (b2c > 0) {
                    grouped.get(provider).add(delivery(pointId + "_B2C", coord, provider,
                            Delivery.ParcelType.B2C, (int) b2c, postalCode));
                }
            }
        }

        // Drop providers with no demand so downstream builds no empty carriers.
        grouped.values().removeIf(List::isEmpty);
        return grouped;
    }

    private static Delivery delivery(String id, Coord coord, String provider,
                                     Delivery.ParcelType type, int amount, String postalCode) {
        return Delivery.builder()
                .id(id)
                .coordinate(coord)
                .provider(provider)
                .parcelType(type)
                .amount(amount)
                .postalCode(postalCode)
                .deliveryMode(Delivery.DeliveryMode.HOME)
                .build();
    }

    static String safe10(String s) { return s.length() > 10 ? s.substring(0, 10) : s; }
    static long asLong(Object v) { return v instanceof Number ? ((Number) v).longValue() : 0L; }
}
