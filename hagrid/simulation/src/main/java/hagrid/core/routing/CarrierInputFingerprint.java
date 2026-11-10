package hagrid.core.routing;

import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import java.util.*;

/** Canonical solver inputs; derived bookkeeping and historical plans are not new demand. */
public final class CarrierInputFingerprint {
    private static final Set<String> DERIVED=Set.of("jspritIterations","jspritComputationTime",
            "unassignedJobs","unassignedParcels","unassignedJobsAsString","routingContextFingerprint",
            "routingMeasurementIteration","routingPlanningIteration","validatedRoutingCost","hadFirstReplanning","totalDeliveries","cacheVersion");
    private CarrierInputFingerprint(){}
    static String of(Carrier carrier){
        var attributes=new TreeMap<>(carrier.getAttributes().getAsMap());
        DERIVED.forEach(attributes::remove);
        var vehicles=new TreeMap<String,Object>();
        carrier.getCarrierCapabilities().getCarrierVehicles().values().forEach(v->vehicles.put(v.getId().toString(),
                Arrays.asList(v.getId(),v.getLinkId(),v.getEarliestStartTime(),v.getLatestEndTime(),
                        type(v.getType()),v.getAttributes().getAsMap())));
        var types=new TreeMap<String,Object>();
        carrier.getCarrierCapabilities().getVehicleTypes().forEach(t->types.put(t.getId().toString(),type(t)));
        return RoutingData.hashValues("carrier-input-v1",carrier.getId(),attributes,jobs(carrier),
                carrier.getCarrierCapabilities().getFleetSize(),vehicles,types);
    }
    public static String metadata(Carrier carrier) {
        var attributes=new TreeMap<>(carrier.getAttributes().getAsMap());
        DERIVED.forEach(attributes::remove);
        return RoutingData.hashValues(attributes);
    }
    public static String jobs(Carrier carrier){
        var services=new TreeMap<String,Object>();
        carrier.getServices().values().forEach(s->services.put(s.getId().toString(),Arrays.asList(s.getId(),
                s.getServiceLinkId(),s.getCapacityDemand(),s.getServiceDuration(),
                s.getServiceStaringTimeWindow().getStart(),s.getServiceStaringTimeWindow().getEnd(),s.getAttributes().getAsMap())));
        var shipments=new TreeMap<String,Object>();
        carrier.getShipments().values().forEach(s->shipments.put(s.getId().toString(),Arrays.asList(s.getId(),
                s.getPickupLinkId(),s.getDeliveryLinkId(),s.getSize(),s.getPickupDuration(),s.getDeliveryDuration(),
                s.getPickupStartingTimeWindow().getStart(),s.getPickupStartingTimeWindow().getEnd(),
                s.getDeliveryStartingTimeWindow().getStart(),s.getDeliveryStartingTimeWindow().getEnd(),s.getAttributes().getAsMap())));
        return RoutingData.hashValues(services,shipments);
    }
    private static Object type(VehicleType t){
        var capacity=t.getCapacity();var costs=t.getCostInformation();
        return Arrays.asList(t.getId(),t.getNetworkMode(),t.getMaximumVelocity(),t.getLength(),t.getWidth(),
                t.getPcuEquivalents(),t.getFlowEfficiencyFactor(),t.getDescription(),t.getAttributes().getAsMap(),
                capacity.getOther(),capacity.getSeats(),capacity.getStandingRoom(),capacity.getWeightInTons(),
                capacity.getVolumeInCubicMeters(),capacity.getAttributes().getAsMap(),
                costs.getFixedCosts(),costs.getCostsPerMeter(),costs.getCostsPerSecond(),costs.getAttributes().getAsMap(),
                t.getEngineInformation().getAttributes().getAsMap());
    }
}
