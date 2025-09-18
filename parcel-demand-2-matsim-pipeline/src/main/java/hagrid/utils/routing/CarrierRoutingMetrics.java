package hagrid.utils.routing;

/**
 * Simple immutable DTO capturing per-carrier routing metrics for analysis/CSV export.
 */
public class CarrierRoutingMetrics {
    public final String carrierId;
    public final String provider;
    public final int services;
    public final int shipments;
    public final int totalCapacityDemand;
    public final int b2bParcels;
    public final int b2cParcels;
    public final String sizeClass; // LARGE or SMALL
    public final String threadingType; // enum name as string
    public final String carrierType; // delivery or supply
    public final double jspritSeconds;
    public final double routeSeconds;
    public final double totalSeconds;

    public CarrierRoutingMetrics(
            String carrierId,
            String provider,
            int services,
            int shipments,
            int totalCapacityDemand,
            int b2bParcels,
            int b2cParcels,
            String sizeClass,
            String threadingType,
            String carrierType,
            double jspritSeconds,
            double routeSeconds,
            double totalSeconds) {
        this.carrierId = carrierId;
        this.provider = provider;
        this.services = services;
        this.shipments = shipments;
        this.totalCapacityDemand = totalCapacityDemand;
        this.b2bParcels = b2bParcels;
        this.b2cParcels = b2cParcels;
        this.sizeClass = sizeClass;
        this.threadingType = threadingType;
        this.carrierType = carrierType;
        this.jspritSeconds = jspritSeconds;
        this.routeSeconds = routeSeconds;
        this.totalSeconds = totalSeconds;
    }
}
