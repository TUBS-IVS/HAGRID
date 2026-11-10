package hagrid.core.routing.sections;

import hagrid.core.routing.CarrierInputFingerprint;
import java.lang.reflect.Array;
import java.util.*;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.vehicles.*;

/** Detached canonical carrier, guarded at search and publication boundaries. */
public final class SectionCatalog {
 private final Carrier originalCarrier;
 private final List<DeliverySection> sections;
 private final String fingerprint;
 private final String originalIdentity;
 public SectionCatalog(Carrier originalCarrier,List<DeliverySection> sections,String fingerprint) {
  this.originalCarrier=copyCarrier(Objects.requireNonNull(originalCarrier));
  this.sections=List.copyOf(sections);this.fingerprint=Objects.requireNonNull(fingerprint);
  this.originalIdentity=identity(this.originalCarrier);
 }
 public Carrier originalCarrier(){return originalCarrier;}
 public List<DeliverySection> sections(){return sections;}
 public String fingerprint(){return fingerprint;}
 public void requireSourceUnchanged(Carrier source){if(!originalIdentity.equals(identity(Objects.requireNonNull(source))))throw new IllegalStateException("Original carrier changed during section routing");}
 public void requireUnchanged(){if(!originalIdentity.equals(identity(originalCarrier)))throw new IllegalStateException("Section catalog canonical carrier was mutated");}
 static String identity(Carrier c){
  var fleet=new TreeMap<String,String>();
  c.getCarrierCapabilities().getCarrierVehicles().forEach((id,v)->{var t=v.getType();var cost=t.getCostInformation();var cap=t.getCapacity();
   fleet.put(id.toString(),SplitRoadNetworkBuilder.canonical(Arrays.asList(v.getLinkId(),v.getEarliestStartTime(),v.getLatestEndTime(),v.getAttributes().getAsMap(),t.getId(),t.getNetworkMode(),t.getMaximumVelocity(),t.getLength(),t.getWidth(),t.getPcuEquivalents(),t.getFlowEfficiencyFactor(),t.getDescription(),t.getAttributes().getAsMap(),cap.getOther(),cap.getSeats(),cap.getStandingRoom(),cap.getWeightInTons(),cap.getVolumeInCubicMeters(),cap.getAttributes().getAsMap(),cost.getFixedCosts(),cost.getCostsPerMeter(),cost.getCostsPerSecond(),cost.getAttributes().getAsMap(),t.getEngineInformation().getAttributes().getAsMap())));});
  return SplitRoadNetworkBuilder.hash(c.getId()+"|"+CarrierInputFingerprint.jobs(c)+"|"+SplitRoadNetworkBuilder.canonical(c.getAttributes().getAsMap())+"|"+c.getCarrierCapabilities().getFleetSize()+"|"+fleet);

 }
 private static Carrier copyCarrier(Carrier source){
  if(!source.getShipments().isEmpty())throw new IllegalArgumentException("Section catalog does not support shipments");
  Carrier result=CarriersUtils.createCarrier(source.getId());copyAttributes(source.getAttributes(),result.getAttributes());
  var fleet=CarrierCapabilities.Builder.newInstance().setFleetSize(source.getCarrierCapabilities().getFleetSize());var types=new HashMap<String,VehicleType>();
  for(var v:source.getCarrierCapabilities().getCarrierVehicles().values()){
   var type=types.computeIfAbsent(v.getType().getId().toString(),id->copyType(v.getType()));
   var copy=CarrierVehicle.Builder.newInstance(v.getId(),v.getLinkId(),type).setEarliestStart(v.getEarliestStartTime()).setLatestEnd(v.getLatestEndTime()).build();copyAttributes(v.getAttributes(),copy.getAttributes());fleet.addVehicle(copy);
  }
  result.setCarrierCapabilities(fleet.build());
  for(var service:source.getServices().values()){
   var copy=CarrierService.Builder.newInstance(service.getId(),service.getServiceLinkId(),service.getCapacityDemand()).setServiceDuration(service.getServiceDuration()).setServiceStartingTimeWindow(service.getServiceStaringTimeWindow()).build();copyAttributes(service.getAttributes(),copy.getAttributes());result.getServices().put(copy.getId(),copy);
  }
  return result;
 }
 private static VehicleType copyType(VehicleType from){
  var to=VehicleUtils.createVehicleType(from.getId(),from.getNetworkMode());to.setMaximumVelocity(from.getMaximumVelocity());to.setLength(from.getLength());to.setWidth(from.getWidth());to.setPcuEquivalents(from.getPcuEquivalents());to.setFlowEfficiencyFactor(from.getFlowEfficiencyFactor());to.setDescription(from.getDescription());
  var a=from.getCostInformation();var b=to.getCostInformation();b.setFixedCost(a.getFixedCosts());b.setCostsPerMeter(a.getCostsPerMeter());b.setCostsPerSecond(a.getCostsPerSecond());
  to.getCapacity().setOther(from.getCapacity().getOther());to.getCapacity().setSeats(from.getCapacity().getSeats());to.getCapacity().setStandingRoom(from.getCapacity().getStandingRoom());to.getCapacity().setWeightInTons(from.getCapacity().getWeightInTons());to.getCapacity().setVolumeInCubicMeters(from.getCapacity().getVolumeInCubicMeters());
  copyAttributes(from.getAttributes(),to.getAttributes());copyAttributes(a.getAttributes(),b.getAttributes());copyAttributes(from.getCapacity().getAttributes(),to.getCapacity().getAttributes());copyAttributes(from.getEngineInformation().getAttributes(),to.getEngineInformation().getAttributes());return to;
 }
 private static void copyAttributes(Attributes from,Attributes to){from.getAsMap().forEach((k,v)->to.putAttribute(k,copyValue(v)));}
 private static Object copyValue(Object v){
  if(v==null||v instanceof String||v instanceof Number||v instanceof Boolean||v instanceof Character||v instanceof Enum<?>||v instanceof Id<?>||v instanceof java.time.temporal.TemporalAccessor||v instanceof java.time.Duration||v instanceof UUID)return v;
  if(v instanceof Coord c)return c.hasZ()?new Coord(c.getX(),c.getY(),c.getZ()):new Coord(c.getX(),c.getY());
  if(v instanceof hagrid.core.util.Hub h && v.getClass()==hagrid.core.util.Hub.class){var copy=new hagrid.core.util.Hub(h);h.getAttributes().getAsMap().forEach((k,item)->copy.getAttributes().putAttribute(k,copyValue(item)));return copy;}
  if(v instanceof Map<?,?> m){var r=new LinkedHashMap<Object,Object>();m.forEach((k,x)->r.put(copyValue(k),copyValue(x)));return Collections.unmodifiableMap(r);}
  if(v instanceof List<?> l)return l.stream().map(SectionCatalog::copyValue).toList();
  if(v instanceof Set<?> s){var r=new LinkedHashSet<Object>();s.forEach(x->r.add(copyValue(x)));return Collections.unmodifiableSet(r);}
  if(v.getClass().isArray()){int n=Array.getLength(v);Object r=Array.newInstance(v.getClass().getComponentType(),n);for(int i=0;i<n;i++)Array.set(r,i,copyValue(Array.get(v,i)));return r;}
  throw new IllegalArgumentException("Unsupported mutable carrier attribute: "+v.getClass().getName());
 }
}

