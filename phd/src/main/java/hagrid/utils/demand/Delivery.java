package hagrid.utils.demand;

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

    /**
     * Enumeration representing the type of delivery.
     */
    public enum ParcelType {
        B2B, B2C, C2C, WHITE_LABEL
    }

    /**
     * Enumeration representing the delivery mode.
     */
    public enum DeliveryMode {
        HOME, PARCEL_LOCKER, PARCEL_LOCKER_EXISTING
    }
}
