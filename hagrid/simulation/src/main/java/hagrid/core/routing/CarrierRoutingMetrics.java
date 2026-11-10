package hagrid.core.routing;

/**
 * Simple immutable DTO capturing per-carrier routing metrics for analysis/CSV export.
 */
public class CarrierRoutingMetrics {
    /** Measured boundaries; absent for historical/cache searches. */
    public record PhaseTimings(double preparationSeconds, double searchSeconds,
                               double finalRoutingSeconds, double validationSeconds) {}
    public record Execution(String contextFingerprint, String validationStatus, int actualIterations,
                            java.util.Optional<PhaseTimings> phases, double searchCost, double finalCost,
                            Double matSimScore,
                            String searchTerminationReason) {
        public Execution { phases = java.util.Objects.requireNonNull(phases); }
    }
    public final java.util.Optional<Execution> execution;
    /** Cumulative context counters sampled when this result completed, not per-carrier deltas. */
    public final java.util.Optional<RoutingContext.Statistics> routingStatistics;
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
        this(carrierId,provider,services,shipments,totalCapacityDemand,b2bParcels,b2cParcels,sizeClass,
                threadingType,carrierType,jspritSeconds,routeSeconds,totalSeconds,java.util.Optional.empty());
    }

    public CarrierRoutingMetrics(String carrierId,String provider,int services,int shipments,int totalCapacityDemand,
            int b2bParcels,int b2cParcels,String sizeClass,String threadingType,String carrierType,
            double jspritSeconds,double routeSeconds,double totalSeconds,java.util.Optional<Execution> execution) {
        this(carrierId,provider,services,shipments,totalCapacityDemand,b2bParcels,b2cParcels,sizeClass,threadingType,carrierType,
                jspritSeconds,routeSeconds,totalSeconds,execution,java.util.Optional.empty());
    }
    private CarrierRoutingMetrics(String carrierId,String provider,int services,int shipments,int totalCapacityDemand,
            int b2bParcels,int b2cParcels,String sizeClass,String threadingType,String carrierType,
            double jspritSeconds,double routeSeconds,double totalSeconds,java.util.Optional<Execution> execution,
            java.util.Optional<RoutingContext.Statistics> routingStatistics) {
        this.routingStatistics=java.util.Objects.requireNonNull(routingStatistics);
        this.execution = java.util.Objects.requireNonNull(execution);
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
    public CarrierRoutingMetrics withRoutingStatistics(RoutingContext.Statistics statistics) {
        return new CarrierRoutingMetrics(carrierId,provider,services,shipments,totalCapacityDemand,b2bParcels,b2cParcels,
                sizeClass,threadingType,carrierType,jspritSeconds,routeSeconds,totalSeconds,execution,java.util.Optional.of(statistics));
    }
    /** Replaces candidate outcome fields with independently checked current selection fields. */
    public CarrierRoutingMetrics withFinalSelection(boolean valid, double selectedCost, Double matSimScore) {
        var old = execution.orElseThrow(() -> new IllegalStateException("No routing execution evidence"));
        var selected = new Execution(old.contextFingerprint(),valid?"VALIDATED_FINAL":"INVALID_FINAL",old.actualIterations(),
                old.phases(),old.searchCost(),selectedCost,matSimScore,
                old.searchTerminationReason());
        return new CarrierRoutingMetrics(carrierId,provider,services,shipments,totalCapacityDemand,b2bParcels,b2cParcels,
                sizeClass,threadingType,carrierType,jspritSeconds,routeSeconds,totalSeconds,java.util.Optional.of(selected),routingStatistics);
    }}
