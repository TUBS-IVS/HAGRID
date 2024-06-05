package hagrid.utils.demand;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.utils.objectattributes.attributable.Attributable;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * The Hub class represents a hub in the logistics network.
 * It includes properties such as the hub's ID, company, coordinates, link, and supply demand.
 * Implements Attributable to allow for additional attributes.
 */
public class Hub implements Attributable {

    // Unique identifier for the hub
    private final Id<Hub> id;
    // Company associated with the hub
    private String company;
    // Address of the hub
    private String address;
    // Type of the hub
    private String type;
    // Coordinates of the hub
    private final Coord coord;
    // Attributes of the hub for additional information
    private final Attributes attributes = new AttributesImpl();
    // Supply demand by provider for white-label logistics
    private final Map<String, Integer> wlSupplyDemandByProvider = new HashMap<>();
    // Link associated with the hub
    private Id<Link> link;
    // Total assigned supply demand
    private int assignedSupplyDemand = 0;
    // Capacity limit of the hub
    private int capacityLimit = Integer.MAX_VALUE;
    // Flag indicating if the hub has capacity
    private boolean hasCapacity = true;

    /**
     * Constructor to initialize a Hub with an ID, company, and coordinates.
     *
     * @param id      The ID of the hub.
     * @param company The company associated with the hub.
     * @param coord   The coordinates of the hub.
     */
    public Hub(Id<Hub> id, String company, Coord coord) {
        this.id = id;
        this.company = company;
        this.coord = coord;
    }

    /**
     * Copy constructor to create a Hub from an existing Hub.
     *
     * @param hub The hub to copy.
     */
    public Hub(Hub hub) {
        this.id = hub.id;
        this.company = hub.company;
        this.address = hub.address;
        this.type = hub.type;
        this.coord = hub.coord;
        this.link = hub.link;
        this.assignedSupplyDemand = hub.assignedSupplyDemand;
        this.hasCapacity = hub.hasCapacity;
        this.capacityLimit = hub.capacityLimit;
        for (Entry<String, Object> e : hub.attributes.getAsMap().entrySet()) {
            this.attributes.putAttribute(e.getKey(), e.getValue());
        }
    }

    // Getter for the hub ID
    public Id<Hub> getId() {
        return id;
    }

    // Getter for the assigned supply demand
    public int getAssignedSupplyDemand() {
        return assignedSupplyDemand;
    }

    // Setter for the assigned supply demand and updates the capacity flag
    public void setAssignedSupplyDemand(int assignedSupplyDemand) {
        this.assignedSupplyDemand = Math.max(assignedSupplyDemand, 0);
        this.hasCapacity = assignedSupplyDemand <= capacityLimit;
    }

    // Increases the assigned supply demand by the specified amount and updates the capacity flag
    public void increaseAssignedSupplyDemand(int additionalSupplyDemand) {
        this.assignedSupplyDemand = Math.max(this.assignedSupplyDemand + additionalSupplyDemand, 0);
        this.hasCapacity = this.assignedSupplyDemand <= capacityLimit;
    }

    // Sets the supply demand for a specific provider in the white-label logistics scenario
    public void setProviderWLSupplyDemand(String provider, int supplyDemand) {
        wlSupplyDemandByProvider.put(provider, Math.max(supplyDemand, 0));
    }

    // Increases the supply demand for a specific provider in the white-label logistics scenario
    public void increaseProviderWLSupplyDemand(String provider, int additionalSupplyDemand) {
        wlSupplyDemandByProvider.merge(provider, Math.max(additionalSupplyDemand, 0), Integer::sum);
    }

    // Gets the total supply demand for all providers in the white-label logistics scenario
    public int getTotalWLSupplyDemand() {
        return wlSupplyDemandByProvider.values().stream().mapToInt(Integer::intValue).sum();
    }

    // Getter for the supply demand by provider in the white-label logistics scenario
    public Map<String, Integer> getWLSupplyDemandByProvider() {
        return wlSupplyDemandByProvider;
    }

    // Getter for the company associated with the hub
    public String getCompany() {
        return company;
    }

    // Setter for the company associated with the hub
    public void setCompany(String company) {
        this.company = company;
    }

    // Getter for the coordinates of the hub
    public Coord getCoord() {
        return coord;
    }

    // Getter for the address of the hub
    public String getAddress() {
        return address;
    }

    // Setter for the address of the hub
    public void setAddress(String address) {
        this.address = address;
    }

    // Getter for the type of the hub
    public String getType() {
        return type;
    }

    // Setter for the type of the hub
    public void setType(String type) {
        this.type = type;
    }

    // Getter for the link associated with the hub
    public Id<Link> getLink() {
        return link;
    }

    // Setter for the link associated with the hub
    public void setLink(Id<Link> link) {
        this.link = link;
    }

    // Returns a string representation of the hub
    @Override
    public String toString() {
        return "Hub [id=" + id + ", company=" + company + " with coordinates =" + coord + "]";
    }

    // Getter for the attributes of the hub
    @Override
    public Attributes getAttributes() {
        return this.attributes;
    }

    // Checks if the hub has capacity for a specified number of parcels
    public boolean hasCapacity(int numberOfParcels) {
        return this.hasCapacity && (this.assignedSupplyDemand + numberOfParcels) < this.capacityLimit;
    }

    // Setter for the capacity limit of the hub
    public void setCapacityLimit(int capacityLimit) {
        this.capacityLimit = Math.max(capacityLimit, 0);
    }
}
