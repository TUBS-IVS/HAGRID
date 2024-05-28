package hagrid.demand;

import org.matsim.api.core.v01.Coord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a Delivery in the logistics system.
 * This class includes details about the delivery such as its ID, coordinates,
 * provider, amount, type, and weights of individual parcels.
 * It also includes a supply distribution map for white-label logistics.
 */
public class Delivery {

    private final HashMap<String, Integer> supplyDistribution;
    private String id;
    private Coord coordinate;
    private String provider;
    private int amount;
    private String parcelType;
    private String postalCode; // Optional postal code as a string
    private ArrayList<Double> individualWeights; // List to store individual weights of parcels

    // Private constructor to enforce the use of the Builder pattern
    private Delivery(Builder builder) {
        this.id = builder.id;
        this.coordinate = builder.coordinate;
        this.provider = builder.provider;
        this.amount = builder.amount;
        this.parcelType = builder.parcelType;
        this.individualWeights = builder.individualWeights;
        this.postalCode = builder.postalCode;
        this.supplyDistribution = new HashMap<>();
    }

    // Getters and Setters

    /**
     * Gets the coordinates of the delivery.
     *
     * @return the coordinates of the delivery.
     */
    public Coord getCoordinate() {
        return coordinate;
    }

    /**
     * Sets the coordinates of the delivery.
     *
     * @param coordinate the coordinates to set.
     */
    public void setCoordinate(Coord coordinate) {
        this.coordinate = coordinate;
    }

    /**
     * Gets the provider of the delivery.
     *
     * @return the provider of the delivery.
     */
    public String getProvider() {
        return provider;
    }

    /**
     * Sets the provider of the delivery.
     *
     * @param provider the provider to set.
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    /**
     * Gets the amount of parcels in the delivery.
     *
     * @return the amount of parcels in the delivery.
     */
    public int getAmount() {
        return amount;
    }

    /**
     * Sets the amount of parcels in the delivery.
     *
     * @param amount the amount to set.
     */
    public void setAmount(int amount) {
        this.amount = amount;
    }

    /**
     * Gets the type of the delivery.
     *
     * @return the type of the delivery.
     */
    public String getParcelType() {
        return parcelType;
    }

    /**
     * Sets the type of the delivery.
     *
     * @param parcelType the type to set.
     */
    public void setParcelType(String parcelType) {
        this.parcelType = parcelType;
    }

    /**
     * Gets the individual weights of parcels in the delivery.
     *
     * @return the list of individual weights of parcels.
     */
    public ArrayList<Double> getIndividualWeights() {
        return individualWeights;
    }

    /**
     * Sets the individual weights of parcels in the delivery. Each weight must be
     * between 0 and 31.5 kg.
     *
     * @param individualWeights the list of individual weights to set.
     */
    public void setIndividualWeights(ArrayList<Double> individualWeights) {
        for (Double weight : individualWeights) {
            if (weight < 0 || weight > 31.5) {
                throw new IllegalArgumentException("Each individual weight must be between 0 and 31.5 kg");
            }
        }
        this.individualWeights = individualWeights;
    }

    /**
     * Gets the total weight of the delivery.
     *
     * @return the total weight of the delivery.
     * @throws IllegalStateException if individual weights are not set.
     */
    public double getTotalWeight() {
        if (individualWeights == null || individualWeights.isEmpty()) {
            throw new IllegalStateException("Individual weights are not set.");
        }
        return individualWeights.stream().mapToDouble(Double::doubleValue).sum();
    }

    /**
     * Gets the ID of the delivery.
     *
     * @return the ID of the delivery.
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the ID of the delivery.
     *
     * @param id the ID to set.
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Gets the postal code of the delivery.
     *
     * @return the postal code of the delivery.
     */
    public String getPostalCode() {
        return postalCode;
    }

    /**
     * Sets the postal code of the delivery. Must be a valid German postal code.
     *
     * @param postalCode the postal code to set.
     */
    public void setPostalCode(String postalCode) {
        if (!postalCode.matches("\\d{5}")) {
            throw new IllegalArgumentException("Postal code must be a 5-digit number.");
        }
        this.postalCode = postalCode;
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
     * @param provider     the provider.
     * @param supplyDemand the supply demand to set.
     */
    public void setSupplyDistribution(String provider, int supplyDemand) {
        supplyDistribution.put(provider, supplyDemand);
    }

    @Override
    public String toString() {
        return "Delivery [ID: " + id + ", Coordinate: " + coordinate + ", Provider: " + provider + ", Amount: " + amount
                + ", Type: " + parcelType + ", TotalWeight: " + getTotalWeight() + ", PostalCode: " + postalCode + "]";
    }

    /**
     * Builder class for constructing Delivery instances.
     */
    public static class Builder {
        private final String id;
        private final Coord coordinate;
        private String provider;
        private int amount;
        private String parcelType;
        private String postalCode; // Optional postal code
        private ArrayList<Double> individualWeights;

        /**
         * Constructor for the Builder class.
         *
         * @param id         the ID of the delivery.
         * @param coordinate the coordinates of the delivery.
         */
        public Builder(String id, Coord coordinate) {
            this.id = id;
            this.coordinate = coordinate;
        }

        /**
         * Sets the provider for the delivery.
         *
         * @param provider the provider to set.
         * @return the Builder instance.
         */
        public Builder withProvider(String provider) {
            this.provider = provider;
            return this;
        }

        /**
         * Sets the amount of parcels in the delivery.
         *
         * @param amount the amount to set.
         * @return the Builder instance.
         */
        public Builder withAmount(int amount) {
            this.amount = amount;
            return this;
        }

        /**
         * Sets the type of the delivery.
         *
         * @param parcelType the type to set.
         * @return the Builder instance.
         */
        public Builder withParcelType(String parcelType) {
            this.parcelType = parcelType;
            return this;
        }

        /**
         * Sets the individual weights of parcels in the delivery. Each weight must be
         * between 0 and 31.5 kg.
         *
         * @param individualWeights the list of individual weights to set.
         * @return the Builder instance.
         */
        public Builder withIndividualWeights(ArrayList<Double> individualWeights) {
            for (Double weight : individualWeights) {
                if (weight < 0 || weight > 31.5) {
                    throw new IllegalArgumentException("Each individual weight must be between 0 and 31.5 kg");
                }
            }
            this.individualWeights = individualWeights;
            return this;
        }

        /**
         * Sets the postal code of the delivery. Must be a valid German postal code.
         *
         * @param postalCode the postal code to set.
         * @return the Builder instance.
         */
        public Builder withPostalCode(String postalCode) {
            if (!postalCode.matches("\\d{5}")) {
                throw new IllegalArgumentException("Postal code must be a 5-digit number.");
            }
            this.postalCode = postalCode;
            return this;
        }

        /**
         * Builds the Delivery instance.
         *
         * @return the constructed Delivery instance.
         */
        public Delivery build() {
            return new Delivery(this);
        }
    }
}
