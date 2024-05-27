package hagrid.demand;

import org.matsim.api.core.v01.Coord;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a Parcel in the logistics system.
 * This class includes details about the parcel such as its ID, coordinates, provider, amount, type, and weight.
 * It also includes a supply distribution map for white-label logistics.
 */
public class Parcel {

    private final HashMap<String, Integer> supplyDistribution;
    private String id;
    private Coord coordinate;
    private String provider;
    private int amount;
    private String parcelType;
    private Double weight;  // Using Double to allow for null values (unset weights)

    // Private constructor to enforce the use of the Builder pattern
    private Parcel(Builder builder) {
        this.id = builder.id;
        this.coordinate = builder.coordinate;
        this.provider = builder.provider;
        this.amount = builder.amount;
        this.parcelType = builder.parcelType;
        this.weight = builder.weight;
        this.supplyDistribution = new HashMap<>();
    }

    // Getters and Setters

    /**
     * Gets the coordinates of the parcel.
     *
     * @return the coordinates of the parcel.
     */
    public Coord getCoordinate() {
        return coordinate;
    }

    /**
     * Sets the coordinates of the parcel.
     *
     * @param coordinate the coordinates to set.
     */
    public void setCoordinate(Coord coordinate) {
        this.coordinate = coordinate;
    }

    /**
     * Gets the provider of the parcel.
     *
     * @return the provider of the parcel.
     */
    public String getProvider() {
        return provider;
    }

    /**
     * Sets the provider of the parcel.
     *
     * @param provider the provider to set.
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    /**
     * Gets the amount of parcels.
     *
     * @return the amount of parcels.
     */
    public int getAmount() {
        return amount;
    }

    /**
     * Sets the amount of parcels.
     *
     * @param amount the amount to set.
     */
    public void setAmount(int amount) {
        this.amount = amount;
    }

    /**
     * Gets the type of the parcel.
     *
     * @return the type of the parcel.
     */
    public String getParcelType() {
        return parcelType;
    }

    /**
     * Sets the type of the parcel.
     *
     * @param parcelType the type to set.
     */
    public void setParcelType(String parcelType) {
        this.parcelType = parcelType;
    }

    /**
     * Gets the weight of the parcel.
     *
     * @return the weight of the parcel.
     */
    public Double getWeight() {
        return weight;
    }

    /**
     * Sets the weight of the parcel. Must be between 0 and 31.5.
     *
     * @param weight the weight to set.
     */
    public void setWeight(Double weight) {
        if (weight < 0 || weight > 31.5) {
            throw new IllegalArgumentException("Weight must be between 0 and 31.5 kg");
        }
        this.weight = weight;
    }

    /**
     * Gets the ID of the parcel.
     *
     * @return the ID of the parcel.
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the ID of the parcel.
     *
     * @param id the ID to set.
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Gets the supply distribution map for white-label logistics.
     *
     * @return the supply distribution map.
     */
    public Map<String, Integer> getSupplyDistribution() {
        return supplyDistribution;
    }

    /**
     * Sets the supply distribution for a provider.
     *
     * @param provider the provider.
     * @param supplyDemand the supply demand to set.
     */
    public void setSupplyDistribution(String provider, int supplyDemand) {
        supplyDistribution.put(provider, supplyDemand);
    }

    @Override
    public String toString() {
        return "Parcel [ID: " + id + ", Coordinate: " + coordinate + ", Provider: " + provider + ", Amount: " + amount + ", Type: " + parcelType + ", Weight: " + weight + "]";
    }

    /**
     * Builder class for constructing Parcel instances.
     */
    public static class Builder {
        private final String id;
        private final Coord coordinate;
        private String provider;
        private int amount;
        private String parcelType;
        private Double weight;

        /**
         * Constructor for the Builder class.
         *
         * @param id the ID of the parcel.
         * @param coordinate the coordinates of the parcel.
         */
        public Builder(String id, Coord coordinate) {
            this.id = id;
            this.coordinate = coordinate;
        }

        /**
         * Sets the provider for the parcel.
         *
         * @param provider the provider to set.
         * @return the Builder instance.
         */
        public Builder withProvider(String provider) {
            this.provider = provider;
            return this;
        }

        /**
         * Sets the amount of parcels.
         *
         * @param amount the amount to set.
         * @return the Builder instance.
         */
        public Builder withAmount(int amount) {
            this.amount = amount;
            return this;
        }

        /**
         * Sets the type of the parcel.
         *
         * @param parcelType the type to set.
         * @return the Builder instance.
         */
        public Builder withParcelType(String parcelType) {
            this.parcelType = parcelType;
            return this;
        }

        /**
         * Sets the weight of the parcel. Must be between 0 and 31.5.
         *
         * @param weight the weight to set.
         * @return the Builder instance.
         */
        public Builder withWeight(Double weight) {
            if (weight < 0 || weight > 31.5) {
                throw new IllegalArgumentException("Weight must be between 0 and 31.5 kg");
            }
            this.weight = weight;
            return this;
        }

        /**
         * Builds the Parcel instance.
         *
         * @return the constructed Parcel instance.
         */
        public Parcel build() {
            return new Parcel(this);
        }
    }
}
