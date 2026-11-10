package hagrid.core.routing;
import org.matsim.freight.carriers.*;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;
import org.matsim.vehicles.*;
import java.util.*;

/** Native plan conversion adds skills to types. Give it worker-owned containers, preserving profile values. */
final class CarrierSearchInput {
    private CarrierSearchInput() {}
    static Carrier copy(Carrier source){
        var result=CarriersUtils.createCarrier(source.getId());AttributesUtils.copyAttributesFromTo(source,result);
        var fleet=CarrierCapabilities.Builder.newInstance().setFleetSize(source.getCarrierCapabilities().getFleetSize());
        var copies=new HashMap<String,VehicleType>();
        source.getCarrierCapabilities().getCarrierVehicles().values().forEach(v->{
            var type=copies.computeIfAbsent(v.getType().getId().toString(),id->copyType(v.getType()));
            var own=CarrierVehicle.Builder.newInstance(v.getId(),v.getLinkId(),type).setEarliestStart(v.getEarliestStartTime()).setLatestEnd(v.getLatestEndTime()).build();
            AttributesUtils.copyAttributesFromTo(v,own);fleet.addVehicle(own);
        });result.setCarrierCapabilities(fleet.build());
        // Native problem construction reads canonical job values and constructs independent jsprit jobs.
        result.getServices().putAll(source.getServices());result.getShipments().putAll(source.getShipments());return result;
    }
    private static VehicleType copyType(VehicleType from){
        var to=VehicleUtils.createVehicleType(from.getId(),from.getNetworkMode());to.setMaximumVelocity(from.getMaximumVelocity());
        to.setLength(from.getLength());to.setWidth(from.getWidth());to.setPcuEquivalents(from.getPcuEquivalents());to.setFlowEfficiencyFactor(from.getFlowEfficiencyFactor());to.setDescription(from.getDescription());
        var a=from.getCostInformation();var b=to.getCostInformation();b.setFixedCost(a.getFixedCosts());b.setCostsPerMeter(a.getCostsPerMeter());b.setCostsPerSecond(a.getCostsPerSecond());
        to.getCapacity().setOther(from.getCapacity().getOther());to.getCapacity().setSeats(from.getCapacity().getSeats());to.getCapacity().setStandingRoom(from.getCapacity().getStandingRoom());
        to.getCapacity().setWeightInTons(from.getCapacity().getWeightInTons());to.getCapacity().setVolumeInCubicMeters(from.getCapacity().getVolumeInCubicMeters());
        AttributesUtils.copyAttributesFromTo(from,to);AttributesUtils.copyAttributesFromTo(a,b);
        AttributesUtils.copyAttributesFromTo(from.getCapacity(),to.getCapacity());AttributesUtils.copyAttributesFromTo(from.getEngineInformation(),to.getEngineInformation());return to;
    }
}