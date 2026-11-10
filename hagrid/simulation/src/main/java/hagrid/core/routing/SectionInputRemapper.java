package hagrid.core.routing;
import hagrid.core.routing.sections.SplitRoadNetwork;
import java.util.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.utils.objectattributes.attributable.Attributes;

/** Detached ordinary carrier mapped to the downstream child of each original endpoint. */
public final class SectionInputRemapper {
 private SectionInputRemapper() {}
 public static Carrier remap(Carrier source,SplitRoadNetwork roads) {
  var target=CarrierSearchInput.copy(source);copy(source.getAttributes(),target.getAttributes());
  var hub=target.getAttributes().getAttribute("hub");if(hub instanceof hagrid.core.util.Hub h)target.getAttributes().putAttribute("hub",remapHub(h,roads));
  var originalFleet=source.getCarrierCapabilities();var copiedFleet=target.getCarrierCapabilities();
  var fleet=CarrierCapabilities.Builder.newInstance().setFleetSize(originalFleet.getFleetSize());
  for(var original:originalFleet.getCarrierVehicles().values()) {
   var copy=copiedFleet.getCarrierVehicles().get(original.getId());var type=copy.getType();var originalType=original.getType();
   copy(originalType.getAttributes(),type.getAttributes());copy(originalType.getCapacity().getAttributes(),type.getCapacity().getAttributes());copy(originalType.getCostInformation().getAttributes(),type.getCostInformation().getAttributes());copy(originalType.getEngineInformation().getAttributes(),type.getEngineInformation().getAttributes());
   var vehicle=CarrierVehicle.Builder.newInstance(original.getId(),last(roads,original.getLinkId()),type).setEarliestStart(original.getEarliestStartTime()).setLatestEnd(original.getLatestEndTime()).build();copy(original.getAttributes(),vehicle.getAttributes());fleet.addVehicle(vehicle);
  }
  target.setCarrierCapabilities(fleet.build());target.getServices().clear();target.getShipments().clear();
  for(var original:source.getServices().values()) {var service=CarrierService.Builder.newInstance(original.getId(),last(roads,original.getServiceLinkId()),original.getCapacityDemand()).setServiceDuration(original.getServiceDuration()).setServiceStartingTimeWindow(original.getServiceStaringTimeWindow()).build();copy(original.getAttributes(),service.getAttributes());target.getServices().put(service.getId(),service);}
  for(var original:source.getShipments().values()) {var shipment=CarrierShipment.Builder.newInstance(original.getId(),last(roads,original.getPickupLinkId()),last(roads,original.getDeliveryLinkId()),original.getSize()).setPickupDuration(original.getPickupDuration()).setDeliveryDuration(original.getDeliveryDuration()).setPickupStartingTimeWindow(original.getPickupStartingTimeWindow()).setDeliveryStartingTimeWindow(original.getDeliveryStartingTimeWindow()).build();copy(original.getAttributes(),shipment.getAttributes());target.getShipments().put(shipment.getId(),shipment);}
  return target;
 }
 public static hagrid.core.util.Hub remapHub(hagrid.core.util.Hub hub,SplitRoadNetwork roads) {var result=(hagrid.core.util.Hub)RoutingData.copy(hub);if(result.getLink()!=null)result.setLink(last(roads,result.getLink()));return result;}
 private static Id<Link> last(SplitRoadNetwork roads,Id<Link> original) {var children=roads.children().get(original);if(children==null||children.isEmpty())throw new IllegalArgumentException("Missing split lineage for "+original);return children.getLast();}
 private static void copy(Attributes source,Attributes destination) {destination.clear();source.getAsMap().forEach((key,value)->destination.putAttribute(key,RoutingData.copy(value)));}
}
