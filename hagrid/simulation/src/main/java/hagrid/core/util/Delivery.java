package hagrid.core.util;

import org.matsim.api.core.v01.Coord;

import java.util.ArrayList;
import java.util.HashMap;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@Builder
public class Delivery {

    private final HashMap<String, Integer> supplyDistribution;
    private String id;
    private Coord coordinate;
    private String provider;
    private int amount;
    private ParcelType parcelType;
    private String postalCode;
    private ArrayList<Double> individualWeights;
    private DeliveryMode deliveryMode;
    private String stopId;
    private String strIdx;
    private String sectionId;
    private String side;
    private String part;
    private String positionM;

    /** Normalizes numeric street indices across GIS and XML scalar representations. */
    public static String normalizeStreetIndex(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        String text = value.toString().trim();
        try {
            return new java.math.BigDecimal(text).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    /**
     * Enumeration representing the type of delivery.
     */
    public enum ParcelType {
        MIXED, B2B, B2C, C2C, WHITE_LABEL
    }

    /**
     * Enumeration representing the delivery mode.
     */
    public enum DeliveryMode {
        HOME, PARCEL_LOCKER, PARCEL_LOCKER_EXISTING
    }
}
