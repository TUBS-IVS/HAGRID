package hagrid.core.routing.sections;
import java.util.*;
import org.matsim.freight.carriers.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.Id;

public final class SectionCatalogBuilder {
 private SectionCatalogBuilder() {}
 private record Candidate(CarrierService service,List<HaltApproach> approaches,double chainage,int capacity) {}
 public static SectionCatalog build(Carrier original,SplitRoadNetwork roads,double maxSpanMeters,int demandBorder){
  if(original==null||roads==null||!Double.isFinite(maxSpanMeters)||maxSpanMeters<=0||demandBorder<=0) throw new IllegalArgumentException("Invalid section input");
  if(!original.getId().toString().equals(roads.network().getAttributes().getAttribute("section.carrierId")) && !Boolean.TRUE.equals(roads.network().getAttributes().getAttribute(SplitRoadNetworkBuilder.carrierKey(original.getId().toString()))))throw new IllegalArgumentException("Prepared carrier ID mismatch: "+original.getId());
  if(!original.getShipments().isEmpty()) throw new IllegalArgumentException("Section catalog does not support shipments");
  if(original.getCarrierCapabilities().getCarrierVehicles().isEmpty()) throw new IllegalArgumentException("No vehicles");
  var groups=new TreeMap<String,List<Candidate>>();
  for(var service:original.getServices().values()){
   var approaches=roads.jobApproaches().get(service.getId().toString()); if(approaches==null||approaches.isEmpty()) throw new IllegalArgumentException("Missing halt for "+service.getId());
   boolean scoped=Boolean.TRUE.equals(roads.network().getAttributes().getAttribute(SplitRoadNetworkBuilder.carrierKey(original.getId().toString())));
   Object assigned=roads.network().getAttributes().getAttribute(scoped ? SplitRoadNetworkBuilder.jobKey(original.getId().toString(),service.getId().toString(),"raw") : "section.job."+service.getId()+".raw");
   if(assigned==null || !assigned.toString().equals(service.getServiceLinkId().toString()))throw new IllegalArgumentException("Assigned raw link mismatch for "+service.getId());
   String prefix=scoped ? SplitRoadNetworkBuilder.jobKey(original.getId().toString(),service.getId().toString(),"") : "section.job."+service.getId()+".";
   var attrs=roads.network().getAttributes(); Object anchor=attrs.getAttribute(prefix+"anchor"); boolean fixed=Boolean.TRUE.equals(attrs.getAttribute(prefix+"fixed"));
   if(fixed){Object mode=service.getAttributes().getAttribute("carrierMode"),prepared=attrs.getAttribute(prefix+"mode");if(mode==null || prepared==null || !mode.toString().equals(prepared.toString()) || !Set.of("PARCEL_LOCKER","PARCEL_LOCKER_EXISTING").contains(mode.toString()))throw new IllegalArgumentException("Fixed out-of-home service mode mismatch for "+service.getId());}
   if(anchor==null)throw new IllegalArgumentException("Missing anchor for "+service.getId());
   if(fixed || !anchor.toString().equals(assigned.toString()) || Boolean.TRUE.equals(attrs.getAttribute(prefix+"sourceAnchorDiff"))){
    Object stop=attrs.getAttribute(prefix+"stop"), actualStop=service.getAttributes().getAttribute("stop_id");
    Object x=attrs.getAttribute(prefix+"x"),y=attrs.getAttribute(prefix+"y"),coord=service.getAttributes().getAttribute("coord");
    if(stop==null || actualStop==null || !stop.toString().equals(actualStop.toString()) || !(x instanceof Number) || !(y instanceof Number) || !(coord instanceof org.matsim.api.core.v01.Coord point) || !Double.isFinite(point.getX()) || !Double.isFinite(point.getY()) || Math.hypot(((Number)x).doubleValue()-point.getX(),((Number)y).doubleValue()-point.getY())>1e-5)throw new IllegalArgumentException("Remapped source identity mismatch for "+service.getId());
   }
   var sorted=approaches.stream().sorted(Comparator.comparing(a->a.originalLinkId().toString())).toList();
   if(sorted.size()>2 || sorted.stream().map(HaltApproach::originalLinkId).distinct().count()!=sorted.size()) throw new IllegalArgumentException("Invalid approaches for "+service.getId());
   var canonical=sorted.getFirst(); var children=roads.children().get(canonical.originalLinkId()); if(children==null)throw new IllegalArgumentException("Missing lineage for "+service.getId());
   Object geometryLength=roads.network().getAttributes().getAttribute("section.geometryLength."+canonical.originalLinkId());
   double length;
   if(fixed)length=children.stream().map(roads.network().getLinks()::get).mapToDouble(Link::getLength).sum();
   else {if(!(geometryLength instanceof Number) || ((Number)geometryLength).doubleValue()<=0)throw new IllegalArgumentException("Missing geometry length for "+service.getId());length=((Number)geometryLength).doubleValue();}
   if(!Double.isFinite(length)||length<=0)throw new IllegalArgumentException("Invalid road length for "+service.getId());
   var window=service.getServiceStartTimeWindow();
   String preparedMode=String.valueOf(roads.network().getAttributes().getAttribute(Boolean.TRUE.equals(roads.network().getAttributes().getAttribute(SplitRoadNetworkBuilder.carrierKey(original.getId().toString()))) ? SplitRoadNetworkBuilder.jobKey(original.getId().toString(),service.getId().toString(),"mode") : "section.job."+service.getId()+".mode"));
   String access=preparedMode.contains("LOCKER")?String.valueOf(roads.network().getAttributes().getAttribute(Boolean.TRUE.equals(roads.network().getAttributes().getAttribute(SplitRoadNetworkBuilder.carrierKey(original.getId().toString()))) ? SplitRoadNetworkBuilder.jobKey(original.getId().toString(),service.getId().toString(),"stop") : "section.job."+service.getId()+".stop")):"";
   String key=preparedMode+"|"+access+"|"+sorted.stream().map(a->a.originalLinkId().toString()).toList()+"|"+window.getStart()+"|"+window.getEnd()+"|"+new TreeSet<>(CarriersUtils.getSkills(service))+"|"+String.valueOf(service.getAttributes().getAttribute("carrierMode"))+"|"+String.valueOf(service.getAttributes().getAttribute("serviceMode"))+"|"+String.valueOf(service.getAttributes().getAttribute("accessConstraint"));
   var required=CarriersUtils.getSkills(service);
   int cap=original.getCarrierCapabilities().getCarrierVehicles().values().stream().filter(v->CarriersUtils.getSkills(v.getType()).containsAll(required)).mapToInt(v->(int)Math.floor(v.getType().getCapacity().getOther())).filter(v->v>0).min().orElse(0);
   groups.computeIfAbsent(key,k->new ArrayList<>()).add(new Candidate(service,sorted,canonical.fraction()*length,Math.min(cap,demandBorder)));
  }
  if(roads.jobApproaches().size()!=original.getServices().size()) throw new IllegalArgumentException("Halt catalog does not match original services");
  var result=new ArrayList<DeliverySection>();
  for(var entry:groups.entrySet()){
   var candidates=entry.getValue(); candidates.sort(Comparator.comparingDouble(Candidate::chainage).thenComparing(c->c.service().getId().toString()));
   int start=0; while(start<candidates.size()) {int end=start; int demand=0; double first=candidates.get(start).chainage();
    while(end<candidates.size()) {var next=candidates.get(end); int nextDemand=Math.addExact(demand,next.service().getCapacityDemand()); if(end>start && (next.chainage()-first>maxSpanMeters || nextDemand>next.capacity()))break; demand=nextDemand; end++;}
    var selected=candidates.subList(start,end); var memberIds=selected.stream().map(c->c.service().getId().toString()).toList(); var variants=new ArrayList<SectionVariant>();
    for(int direction=0;direction<selected.getFirst().approaches().size();direction++){var ordered=new ArrayList<>(selected); if(direction==1)Collections.reverse(ordered); int di=direction; variants.add(new SectionVariant(direction,ordered.stream().map(c->c.service().getId().toString()).toList(),ordered.stream().map(c->c.approaches().get(di).haltLinkId()).toList()));}
    result.add(new DeliverySection("section:"+result.size(),memberIds,demand,variants)); start=end;
   }
  }
  var identity=new StringBuilder(roads.fingerprint()).append('|').append(maxSpanMeters).append('|').append(demandBorder).append('|').append(result);
  identity.append("|").append(SectionCatalog.identity(original));
  return new SectionCatalog(original,result,SplitRoadNetworkBuilder.hash(identity.toString()));
 }
}
